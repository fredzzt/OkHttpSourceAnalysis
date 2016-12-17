/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import okhttp3.RealCall.AsyncCall;
import okhttp3.internal.Util;
import okhttp3.internal.http.HttpEngine;

/**
 * Policy on when async requests are executed.
 *
 * <p>Each dispatcher uses an {@link ExecutorService} to run calls internally. If you supply your
 * own executor, it should be able to run {@linkplain #getMaxRequests the configured maximum} number
 * of calls concurrently.
 * 工作流程的概述
 当我们用OkHttpClient.newCall(request)进行execute/enenqueue时，实际是将请求Call放到了Dispatcher中，
 okhttp使用Dispatcher进行线程分发，它有两种方法，一个是普通的同步单线程；另一种是使用了队列进行并发任务的分发(Dispatch)与回调，
 我们下面主要分析第二种，也就是队列这种情况，这也是okhttp能够竞争过其它库的核心功能之一



 总结：

 OkHttp采用Dispatcher技术，类似于Nginx，与线程池配合实现了高并发，低阻塞的运行
 Okhttp采用Deque作为缓存，按照入队的顺序先进先出
 OkHttp最出彩的地方就是在try/finally中调用了finished函数，可以主动控制等待队列的移动，而不是采用锁，极大减少了编码复杂性

 */
public final class Dispatcher {
  //  最大并发请求数为64
  private int maxRequests = 64;
  //maxRequestsPerHost = 5: 每个主机最大请求数为5
  private int maxRequestsPerHost = 5;

  /** Executes calls. Created lazily.
   * 消费者池（也就是线程池） */
  private ExecutorService executorService;

  /** Ready calls in the order they'll be run. */
  //缓存队列排队等待
  private final Deque<AsyncCall> readyCalls = new ArrayDeque<>();

  /** Running calls. Includes canceled calls that haven't finished yet. */
  // 运行的双向队列     正在运行的任务，仅仅是用来引用正在运行的任务以判断并发量，注意它并不是消费者缓存
  private final Deque<AsyncCall> runningCalls = new ArrayDeque<>();

  /** In-flight synchronous calls. Includes canceled calls that haven't finished yet.
   * */
  private final Deque<RealCall> executedCalls = new ArrayDeque<>();

  public Dispatcher(ExecutorService executorService) {
    this.executorService = executorService;
  }

  //分发者，也就是生产者（默认在主线程）
  public Dispatcher() {
  }

  // 使用如下构造了单例线程池
//  参数说明如下：
//  int corePoolSize: 最小并发线程数，这里并发同时，如果是0的话包括空闲与活动的线程，空闲一段时间后所有线程将全部被销毁。
//  int maximumPoolSize: 最大线程数，当任务进来时可以扩充的线程最大值，当大于了这个值就会根据丢弃处理机制来处理
//  long keepAliveTime: 当线程数大于corePoolSize时，多余的空闲线程的最大存活时间，类似于HTTP中的Keep-alive
//  TimeUnit unit: 时间单位，一般用秒
//  BlockingQueue<Runnable> workQueue: 工作队列
//  ThreadFactory threadFactory: 单个线程的工厂，可以打Log，设置Daemon(即当JVM退出时，线程自动结束)等
  public synchronized ExecutorService getExecutorService() {
    if (executorService == null) {
      executorService = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
          new SynchronousQueue<Runnable>(), Util.threadFactory("OkHttp Dispatcher", false));
      //可以看出，在Okhttp中，构建了一个阀值为[0, Integer.MAX_VALUE]的线程池，它不保留任何最小线程数，随时创建更多的线程数，当线程空闲时只能活60秒，它使用了一个不存储元素的阻塞工作队列，一个叫做"OkHttp Dispatcher"的线程工厂。
//      也就是说，在实际运行中，当收到10个并发请求时，线程池会创建十个线程，当工作完成后，线程池会在60s后相继关闭所有线程。
//      在RxJava的Schedulers.io()中，也有类似的设计，最小的线程数量控制，不设上限的最大线程，以保证I/O任务中高阻塞低占用的过程中，不会长时间卡在阻塞上，有兴趣的可以分析RxJava中4种不同场景的Schedulers

    }
    return executorService;
  }

  /**
   * Set the maximum number of requests to execute concurrently. Above this requests queue in
   * memory, waiting for the running calls to complete.
   *
   * <p>If more than {@code maxRequests} requests are in flight when this is invoked, those requests
   * will remain in flight.
   */
  public synchronized void setMaxRequests(int maxRequests) {
    if (maxRequests < 1) {
      throw new IllegalArgumentException("max < 1: " + maxRequests);
    }
    this.maxRequests = maxRequests;
    promoteCalls();
  }

  public synchronized int getMaxRequests() {
    return maxRequests;
  }

  /**
   * Set the maximum number of requests for each host to execute concurrently. This limits requests
   * by the URL's host name. Note that concurrent requests to a single IP address may still exceed
   * this limit: multiple hostnames may share an IP address or be routed through the same HTTP
   * proxy.
   *
   * <p>If more than {@code maxRequestsPerHost} requests are in flight when this is invoked, those
   * requests will remain in flight.
   */
  public synchronized void setMaxRequestsPerHost(int maxRequestsPerHost) {
    if (maxRequestsPerHost < 1) {
      throw new IllegalArgumentException("max < 1: " + maxRequestsPerHost);
    }
    this.maxRequestsPerHost = maxRequestsPerHost;
    promoteCalls();
  }

  public synchronized int getMaxRequestsPerHost() {
    return maxRequestsPerHost;
  }

//  当HttpClient的请求入队时，根据代码，我们可以发现实际上是Dispatcher进行了入队操作
  synchronized void enqueue(AsyncCall call) {
//    可以发现请求是否进入缓存的条件如下(runningRequests<64 && runningRequestsPerHost<5)：
    if (runningCalls.size() < maxRequests && runningCallsForHost(call) < maxRequestsPerHost) {
      //添加正在运行的请求
      runningCalls.add(call);
      //线程池执行请求
      getExecutorService().execute(call);
    } else {
      //添加到缓存队列排队等待
      readyCalls.add(call);
    }
  }

  /** Cancel all calls with the tag {@code tag}. */
  public synchronized void cancel(Object tag) {
    for (AsyncCall call : readyCalls) {
      if (Util.equal(tag, call.tag())) {
        call.cancel();
      }
    }

    for (AsyncCall call : runningCalls) {
      if (Util.equal(tag, call.tag())) {
        call.get().canceled = true;
        HttpEngine engine = call.get().engine;
        if (engine != null) engine.cancel();
      }
    }

    for (RealCall call : executedCalls) {
      if (Util.equal(tag, call.tag())) {
        call.cancel();
      }
    }
  }

  /** Used by {@code AsyncCall#run} to signal completion. */
  // 当asyncCall 无论成功还是失败都会回调这
  synchronized void finished(AsyncCall call) {
    if (!runningCalls.remove(call)) throw new AssertionError("AsyncCall wasn't running!");
    promoteCalls();
  }

//  这样，就主动的把缓存队列向前走了一步，而没有使用互斥锁等复杂编码
  //将缓存队列向前推进了一步
  private void promoteCalls() {
    //如果目前是最大负荷运转，接着等
    if (runningCalls.size() >= maxRequests) return; // Already running max capacity.
    //如果缓存等待区是空的，接着等
    if (readyCalls.isEmpty()) return; // No ready calls to promote.

    for (Iterator<AsyncCall> i = readyCalls.iterator(); i.hasNext(); ) {
      AsyncCall call = i.next();

      if (runningCallsForHost(call) < maxRequestsPerHost) {
        //将缓存等待区最后一个移动到运行区中，并执行
        i.remove();
        runningCalls.add(call);
        getExecutorService().execute(call);
      }

      if (runningCalls.size() >= maxRequests) return; // Reached max capacity.
    }
  }

  /** Returns the number of running calls that share a host with {@code call}. */
  private int runningCallsForHost(AsyncCall call) {
    int result = 0;
    for (AsyncCall c : runningCalls) {
      if (c.host().equals(call.host())) result++;
    }
    return result;
  }

  /** Used by {@code Call#execute} to signal it is in-flight. */
  //client 调用call 在用realcall 再执行 .executed
  synchronized void executed(RealCall call) {
    executedCalls.add(call);
  }

  /** Used by {@code Call#execute} to signal completion. */
  synchronized void finished(Call call) {
    if (!executedCalls.remove(call)) throw new AssertionError("Call wasn't in-flight!");
  }

  public synchronized int getRunningCallCount() {
    return runningCalls.size();
  }

  public synchronized int getQueuedCallCount() {
    return readyCalls.size();
  }
}
