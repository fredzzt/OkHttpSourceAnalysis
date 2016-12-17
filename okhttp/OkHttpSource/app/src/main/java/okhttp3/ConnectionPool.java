/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3;

import java.lang.ref.Reference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import okhttp3.internal.Internal;
import okhttp3.internal.RouteDatabase;
import okhttp3.internal.Util;
import okhttp3.internal.http.StreamAllocation;
import okhttp3.internal.io.RealConnection;

import static okhttp3.internal.Util.closeQuietly;

/**
 * Socket连接池，对连接缓存进行回收与管理
 * Manages reuse of HTTP and SPDY connections for reduced network latency. HTTP requests that share
 * the same {@link Address} may share a {@link Connection}. This class implements the policy of
 * which connections to keep open for future use.
 */
public final class ConnectionPool {
  /**
   * Background threads are used to cleanup expired connections. There will be at most a single
   * thread running per connection pool. The thread pool executor permits the pool itself to be
   * garbage collected.
   */
  private static final Executor executor = new ThreadPoolExecutor(0 /* corePoolSize */,
      Integer.MAX_VALUE /* maximumPoolSize */, 60L /* keepAliveTime */, TimeUnit.SECONDS,
      new SynchronousQueue<Runnable>(), Util.threadFactory("OkHttp ConnectionPool", true));

  /** The maximum number of idle connections for each address. */
  private final int maxIdleConnections;
  private final long keepAliveDurationNs;

  //当用户socket连接成功，向连接池中put新的socket时，回收函数会被主动调用，线程池就会执行cleanupRunnable，如下
  //Socket清理的Runnable，每当put操作时，就会被主动调用
//注意put操作是在网络线程
//而Socket清理是在`OkHttp ConnectionPool`线程池中调用

//  这段死循环实际上是一个阻塞的清理任务，首先进行清理(clean)，
// 并返回下次需要清理的间隔时间，然后调用wait(timeout)进行等待以释放锁与时间片，当等待时间到了后，再次进行清理，并返回下次要清理的间隔时间...
  private final Runnable cleanupRunnable = new Runnable() {
    @Override public void run() {
      while (true) {
        //执行清理并返回下场需要清理的时间
        long waitNanos = cleanup(System.nanoTime());
        if (waitNanos == -1) return;
        if (waitNanos > 0) {
          long waitMillis = waitNanos / 1000000L;
          waitNanos -= (waitMillis * 1000000L);
          synchronized (ConnectionPool.this) {
            try {
              //在timeout内释放锁与时间片
              ConnectionPool.this.wait(waitMillis, (int) waitNanos);
            } catch (InterruptedException ignored) {
            }
          }
        }
      }
    }
  };

  //真是连接的线程队列
  private final Deque<RealConnection> connections = new ArrayDeque<>();
  //维护着一个RouteDatabase，它用来记录连接失败的Route的黑名单，当连接失败的时候就会把失败的线路加进去（本文不讨论）
  final RouteDatabase routeDatabase = new RouteDatabase();
  boolean cleanupRunning;

  /**
   * Create a new connection pool with tuning parameters appropriate for a single-user application.
   * The tuning parameters in this pool are subject to change in future OkHttp releases. Currently
   * this pool holds up to 5 idle connections which will be evicted after 5 minutes of inactivity.
   */
  public ConnectionPool() {
    this(5, 5, TimeUnit.MINUTES);
  }

  public ConnectionPool(int maxIdleConnections, long keepAliveDuration, TimeUnit timeUnit) {
    this.maxIdleConnections = maxIdleConnections;
    this.keepAliveDurationNs = timeUnit.toNanos(keepAliveDuration);

    // Put a floor on the keep alive duration, otherwise cleanup will spin loop.
    if (keepAliveDuration <= 0) {
      throw new IllegalArgumentException("keepAliveDuration <= 0: " + keepAliveDuration);
    }
  }

  /** Returns the number of idle connections in the pool. */
  public synchronized int getIdleConnectionCount() {
    int total = 0;
    for (RealConnection connection : connections) {
      if (connection.allocations.isEmpty()) total++;
    }
    return total;
  }

  /**
   * Returns total number of connections in the pool. Note that prior to OkHttp 2.7 this included
   * only idle connections and SPDY connections. In OkHttp 2.7 this includes all connections, both
   * active and inactive. Use {@link #getIdleConnectionCount()} to count connections not currently
   * in use.
   */
  public synchronized int getConnectionCount() {
    return connections.size();
  }

  /** Returns total number of multiplexed connections in the pool. */
  public synchronized int getMultiplexedConnectionCount() {
    int total = 0;
    for (RealConnection connection : connections) {
      if (connection.isMultiplexed()) total++;
    }
    return total;
  }

  /** Returns total number of http connections in the pool. */
  public synchronized int getHttpConnectionCount() {
    return connections.size() - getMultiplexedConnectionCount();
  }

  /** Returns a recycled connection to {@code address}, or null if no such connection exists. */
  RealConnection get(Address address, StreamAllocation streamAllocation) {
    assert (Thread.holdsLock(this));
    for (RealConnection connection : connections) {
      // TODO(jwilson): this is awkward. We're already holding a lock on 'this', and
      //     connection.allocationLimit() may also lock the FramedConnection.
      if (connection.allocations.size() < connection.allocationLimit()
          && address.equals(connection.route().address)
          && !connection.noNewStreams) {
        streamAllocation.acquire(connection);

        return connection;
      }
    }
    return null;
  }
  //向连接池中put新的socket时，回收函数会被主动调用，线程池就会执行cleanupRunnable，如下
  void put(RealConnection connection) {
    assert (Thread.holdsLock(this));
    if (!cleanupRunning) {
      cleanupRunning = true;
      executor.execute(cleanupRunnable);
    }
    connections.add(connection);
  }

  /**
   * Notify this pool that {@code connection} has become idle. Returns true if the connection has
   * been removed from the pool and should be closed.
   */
  boolean connectionBecameIdle(RealConnection connection) {
    assert (Thread.holdsLock(this));
    if (connection.noNewStreams || maxIdleConnections == 0) {
      connections.remove(connection);
      return true;
    } else {
      notifyAll(); // Awake the cleanup thread: we may have exceeded the idle connection limit.
      return false;
    }
  }

  /** Close and remove all idle connections in the pool. */
  public void evictAll() {
    List<RealConnection> evictedConnections = new ArrayList<>();
    synchronized (this) {
      for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
        RealConnection connection = i.next();
        if (connection.allocations.isEmpty()) {
          connection.noNewStreams = true;
          evictedConnections.add(connection);
          i.remove();
        }
      }
    }

    for (RealConnection connection : evictedConnections) {
      closeQuietly(connection.socket());
    }
  }

  /**
   * Performs maintenance on this pool, evicting the connection that has been idle the longest if
   * either it has exceeded the keep alive limit or the idle connections limit.
   *
   * <p>Returns the duration in nanos to sleep until the next scheduled call to this method. Returns
   * -1 if no further cleanups are required.
   * cleanup使用了类似于GC的标记-清除算法，也就是首先标记出最不活跃的连接(我们可以叫做泄漏连接，或者空闲连接)，接着进行清除，流程如下:
   *    1.遍历Deque中所有的RealConnection，标记泄漏的连接
       2.如果被标记的连接满足(空闲socket连接超过5个&&keepalive时间大于5分钟)，就将此泄漏连接，也就是从Deque中移除，并关闭连接，返回0，也就是将要执行wait(0)，提醒立刻再次扫描
       3.如果(目前还可以塞得下5个连接，但是有可能泄漏的连接(即空闲时间即将达到5分钟))，就返回此连接即将到期的时间，供下次清理
      4. 如果(全部都是活跃的连接)，就返回默认的keep-alive时间，也就是5分钟后再执行清理
      5. 如果(没有任何连接)，就返回-1,跳出清理的死循环
     6. 再次注意：这里的“并发”==(“空闲”＋“活跃”)==5，而不是说并发连接就一定是活跃的连接


   */
  long cleanup(long now) {
    int inUseConnectionCount = 0;
    int idleConnectionCount = 0;
    RealConnection longestIdleConnection = null;
    long longestIdleDurationNs = Long.MIN_VALUE;

    // Find either a connection to evict, or the time that the next eviction is due.
    //遍历`Deque`中所有的`RealConnection`，标记泄漏的连接
    synchronized (this) {
      for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
        RealConnection connection = i.next();

        // If the connection is in use, keep searching.
        // 查询此连接内部StreamAllocation的引用数量
        if (pruneAndGetAllocationCount(connection, now) > 0) {
          inUseConnectionCount++;
          continue;
        }

        idleConnectionCount++;

        // If the connection is ready to be evicted, we're done.
        //选择排序法，标记出空闲连接
        long idleDurationNs = now - connection.idleAtNanos;
        if (idleDurationNs > longestIdleDurationNs) {
          longestIdleDurationNs = idleDurationNs;
          longestIdleConnection = connection;
        }
      }

      if (longestIdleDurationNs >= this.keepAliveDurationNs
          || idleConnectionCount > this.maxIdleConnections) {
        // We've found a connection to evict. Remove it from the list, then close it below (outside
        // of the synchronized block).
        //如果(`空闲socket连接超过5个`
        //且`keepalive时间大于5分钟`)
        //就将此泄漏连接从`Deque`中移除
        connections.remove(longestIdleConnection);
      } else if (idleConnectionCount > 0) {
        //返回此连接即将到期的时间，供下次清理
        //这里依据是在上文`connectionBecameIdle`中设定的计时
        // A connection will be ready to evict soon.
        return keepAliveDurationNs - longestIdleDurationNs;
      } else if (inUseConnectionCount > 0) {
        //全部都是活跃的连接，5分钟后再次清理
        // All connections are in use. It'll be at least the keep alive duration 'til we run again.
        return keepAliveDurationNs;
      } else {
        // No connections, idle or in use.
        //没有任何连接，跳出循环
        cleanupRunning = false;
        return -1;
      }
    }

    closeQuietly(longestIdleConnection.socket());

    // Cleanup again immediately.
    //关闭连接，返回`0`，也就是立刻再次清理
    return 0;
  }

  /**
   * Prunes any leaked allocations and then returns the number of remaining live allocations on
   * {@code connection}. Allocations are leaked if the connection is tracking them but the
   * application code has abandoned them. Leak detection is imprecise and relies on garbage
   * collection.
   * 如何标记并找到最不活跃的连接呢，这里使用了pruneAndGetAllocationCount的方法，它主要依据弱引用是否为null而判断这个连接是否泄漏
   * //类似于引用计数法，如果引用全部为空，返回立刻清理
   *
   * 1.遍历RealConnection连接中的StreamAllocationList，它维护着一个弱应用列表
  2. 查看此StreamAllocation是否为空(它是在线程池的put/remove手动控制的)，如果为空，说明已经没有代码引用这个对象了，需要在List中删除
  3. 遍历结束，如果List中维护的StreamAllocation删空了，就返回0，表示这个连接已经没有代码引用了，是泄漏的连接;否则返回非0的值，表示这个仍然被引用，是活跃的连接。

   */
  private int pruneAndGetAllocationCount(RealConnection connection, long now) {
    //弱引用列表
    List<Reference<StreamAllocation>> references = connection.allocations;
    //遍历弱引用列表
    for (int i = 0; i < references.size(); ) {

      Reference<StreamAllocation> reference = references.get(i);
//如果正在被使用，跳过，接着循环
      //是否置空是在上文`connectionBecameIdle`的`release`控制的
      if (reference.get() != null) {
        i++;
        continue;
      }

      // We've discovered a leaked allocation. This is an application bug.
      Internal.logger.warning("A connection to " + connection.route().address().url()
          + " was leaked. Did you forget to close a response body?");
      //否则移除引用
      references.remove(i);
      connection.noNewStreams = true;

      // If this was the last allocation, the connection is eligible for immediate eviction.
      //如果所有分配的流均没了，标记为已经距离现在空闲了5分钟
      if (references.isEmpty()) {
        connection.idleAtNanos = now - keepAliveDurationNs;
        return 0;
      }
    }

    return references.size();
  }
}
