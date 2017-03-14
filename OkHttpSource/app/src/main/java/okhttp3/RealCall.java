/*
 * Copyright (C) 2014 Square, Inc.
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

import java.io.IOException;
import java.net.ProtocolException;
import java.util.logging.Level;
import okhttp3.internal.NamedRunnable;
import okhttp3.internal.http.HttpEngine;
import okhttp3.internal.http.RequestException;
import okhttp3.internal.http.RouteException;
import okhttp3.internal.http.StreamAllocation;

import static okhttp3.internal.Internal.logger;
import static okhttp3.internal.http.HttpEngine.MAX_FOLLOW_UPS;

/**
 * OkHttp拥有2种运行方式，一种是同步阻塞调用并直接返回的形式execute，
 * 另一种是通过内部线程池分发调度实现非阻塞的异步回调。
 */
final class RealCall implements Call {
  private final OkHttpClient client;

  // Guarded by this.
  private boolean executed;
  volatile boolean canceled;

  /** The application's original request unadulterated by redirects or auth headers.
   * 原始的网络请求 重定向或认证报头的纯粹。*/
  Request originalRequest;
  HttpEngine engine;

  protected RealCall(OkHttpClient client, Request originalRequest) {
    // Copy the client. Otherwise changes (socket factory, redirect policy,
    // etc.) may incorrectly be reflected in the request when it is executed.
    this.client = client.copyWithDefaults();
    this.originalRequest = originalRequest;
  }
  //同步阻塞调用并直接返回的形式execute
  @Override public Response execute() throws IOException {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    try {
      //同样都是调用 client.getDispatcher().executed
      client.getDispatcher().executed(this);
      //拦截器
      Response result = getResponseWithInterceptorChain(false);
      if (result == null) throw new IOException("Canceled");
      return result;
    } finally {
      client.getDispatcher().finished(this);
    }
  }

  Object tag() {
    return originalRequest.tag();
  }

  //通过内部线程池分发调度实现非阻塞的异步回调。
  @Override public void enqueue(Callback responseCallback) {
    enqueue(responseCallback, false);
  }

  void enqueue(Callback responseCallback, boolean forWebSocket) {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    //执行Call enqueue 方法后其实是调用了client.getDispatcher().enqueue();
    // 将Call --> RealCall -->AsyncCall
    client.getDispatcher().enqueue(new AsyncCall(responseCallback, forWebSocket));
  }

  @Override public void cancel() {
    canceled = true;
    if (engine != null) engine.cancel();
  }

  @Override public synchronized boolean isExecuted() {
    return executed;
  }

  @Override public boolean isCanceled() {
    return canceled;
  }

  /**
   * 请求元素AsyncCall（它实现了Runnable接口），它内部实现的execute方法
   */
  final class AsyncCall extends NamedRunnable {
    private final Callback responseCallback;
    private final boolean forWebSocket;

    private AsyncCall(Callback responseCallback, boolean forWebSocket) {
      super("OkHttp %s", originalRequest.url().toString());
      this.responseCallback = responseCallback;
      this.forWebSocket = forWebSocket;
    }

    String host() {
      return originalRequest.url().host();
    }

    Request request() {
      return originalRequest;
    }

    Object tag() {
      return originalRequest.tag();
    }

    void cancel() {
      RealCall.this.cancel();
    }

    RealCall get() {
      return RealCall.this;
    }

    /**
     * 这里就是处理 Callback 的各种回调
     */
    @Override protected void execute() {
      boolean signalledCallback = false;
      try {
        //执行耗时IO任务（拦截器，在后面有将成员变量 请求originalRequest传递进去）
        Response response = getResponseWithInterceptorChain(forWebSocket);
        if (canceled) {
          signalledCallback = true;
          //回调，注意这里回调是在线程池中，而不是想当然的主线程回调
          responseCallback.onFailure(originalRequest, new IOException("Canceled"));
        } else {
          signalledCallback = true;
          //回调，同上
          responseCallback.onResponse(response);
        }
      } catch (IOException e) {
        if (signalledCallback) {
          // Do not signal the callback twice!
          logger.log(Level.INFO, "Callback failure for " + toLoggableString(), e);
        } else {
          Request request = engine == null ? originalRequest : engine.getRequest();
          responseCallback.onFailure(request, e);
        }
      } finally {
        //最关键的代码
        //当任务执行完成后，无论是否有异常，finally代码段总会被执行，
        // 也就是会调用Dispatcher的finished函数，打开源码，
        // 发现它将正在运行的任务Call从队列runningAsyncCalls中移除后，接着执行promoteCalls()函数
        client.getDispatcher().finished(this);
      }
    }
  }

  /**
   * Returns a string that describes this call. Doesn't include a full URL as that might contain
   * sensitive information.
   */
  private String toLoggableString() {
    String string = canceled ? "canceled call" : "call";
    HttpUrl redactedUrl = originalRequest.url().resolve("/...");
    return string + " to " + redactedUrl;
  }

  /**
   * 拦截器
   * Interceptor本身的文档解释：观察，修改以及可能短路的请求输出和响应请求的回来。通常情况下拦截器用来添加，移除或者转换请求或者回应的头部信息。
   * 主要是针对Request和Response的切面处理。
   * 不要误以为它只负责拦截请求进行一些额外的处理（例如 cookie），实际上它把实际的网络请求、缓存、透明压缩等功能都统一了起来
   * @param forWebSocket
   * @return
   * @throws IOException
     */
  private Response getResponseWithInterceptorChain(boolean forWebSocket) throws IOException {
    // originalRequest
    Interceptor.Chain chain = new ApplicationInterceptorChain(0, originalRequest, forWebSocket);
    return chain.proceed(originalRequest);
  }

  class ApplicationInterceptorChain implements Interceptor.Chain {
    private final int index;
    private final Request request;
    private final boolean forWebSocket;

    ApplicationInterceptorChain(int index, Request request, boolean forWebSocket) {
      this.index = index;
      this.request = request;
      this.forWebSocket = forWebSocket;
    }

    @Override public Connection connection() {
      return null;
    }

    @Override public Request request() {
      return request;
    }

    @Override public Response proceed(Request request) throws IOException {
      // If there's another interceptor in the chain, call that.
      // 自增递归(recursive)调用Chain.process()，直到interceptors().size()中的拦截器全部调用完。
      // 主要做了两件事：
      // 递归调用Interceptors，依次入栈对response进行处理
      // 当全部递归出栈完成后，移交给网络模块(getResponse)
      if (index < client.interceptors().size()) {

        Interceptor.Chain chain = new ApplicationInterceptorChain(index + 1, request, forWebSocket);
        Interceptor interceptor = client.interceptors().get(index);
        Response interceptedResponse = interceptor.intercept(chain);

        if (interceptedResponse == null) {
          throw new NullPointerException("application interceptor " + interceptor
              + " returned null");
        }

        return interceptedResponse;
      }

      // No more interceptors. Do HTTP.
      //没有更多的拦截。去做HTTP请求。
      return getResponse(request, forWebSocket);
    }
  }

  /**
   * Performs the request and returns the response. May return null if this call was canceled.
   * 正式的网络请求
   *
   * 1.在okhttp中，通过RequestLine，Requst，HttpEngine，Header等参数进行序列化操作，也就是拼装参数为socketRaw数据。
   * 拼装方法也比较暴力，直接按照RFC协议要求的格式进行concat输出就实现了
      2.通过sink写入write到socket连接。

   */
  Response getResponse(Request request, boolean forWebSocket) throws IOException {
    // Copy body metadata to the appropriate request headers.
    //将本体元数据复制到适当的请求头。
    RequestBody body = request.body();
    if (body != null) {
      Request.Builder requestBuilder = request.newBuilder();

      MediaType contentType = body.contentType();
      if (contentType != null) {
        requestBuilder.header("Content-Type", contentType.toString());
      }

      long contentLength = body.contentLength();
      if (contentLength != -1) {
        requestBuilder.header("Content-Length", Long.toString(contentLength));
        requestBuilder.removeHeader("Transfer-Encoding");
      } else {
        requestBuilder.header("Transfer-Encoding", "chunked");
        requestBuilder.removeHeader("Content-Length");
      }

      request = requestBuilder.build();
    }

    // Create the initial HTTP engine. Retries and redirects need new engine for each attempt.
    // 创建初始HTTP引擎。重试次数和每次尝试重定向需要新的发动机。
    engine = new HttpEngine(client, request, false, false, forWebSocket, null, null, null);

    int followUpCount = 0;
    while (true) {
      if (canceled) {
        engine.releaseStreamAllocation();
        throw new IOException("Canceled");
      }

      boolean releaseConnection = true;
      try {
        // 通过engine 进行 请求与缓存
        engine.sendRequest();
        engine.readResponse();
        releaseConnection = false;
      } catch (RequestException e) {
        // The attempt to interpret the request failed. Give up.
        throw e.getCause();
      } catch (RouteException e) {
        // The attempt to connect via a route failed. The request will not have been sent.
        //尝试通过路由连接失败。 请求将不会被发送。
        HttpEngine retryEngine = engine.recover(e.getLastConnectException(), null);
        if (retryEngine != null) {
          releaseConnection = false;
          engine = retryEngine;
          continue;
        }
        // Give up; recovery is not possible.
        throw e.getLastConnectException();
      } catch (IOException e) {
        // An attempt to communicate with a server failed. The request may have been sent.
      //尝试与服务器通信失败。 请求可能已发送。
        HttpEngine retryEngine = engine.recover(e, null);
        if (retryEngine != null) {
          releaseConnection = false;
          engine = retryEngine;
          continue;
        }

        // Give up; recovery is not possible.
        throw e;
      } finally {
        // We're throwing an unchecked exception. Release any resources.
        //释放任何资源。
        if (releaseConnection) {
          StreamAllocation streamAllocation = engine.close();
          streamAllocation.release();
        }
      }

      // 得到真是的请求 response
      Response response = engine.getResponse();
      Request followUp = engine.followUpRequest();

      if (followUp == null) {
        if (!forWebSocket) {
          engine.releaseStreamAllocation();
        }
        return response;
      }
      //释放资源
      StreamAllocation streamAllocation = engine.close();

      if (++followUpCount > MAX_FOLLOW_UPS) {
        streamAllocation.release();
        throw new ProtocolException("Too many follow-up requests: " + followUpCount);
      }

      if (!engine.sameConnection(followUp.url())) {
        streamAllocation.release();
        streamAllocation = null;
      }

      request = followUp;
      engine = new HttpEngine(client, request, false, false, forWebSocket, streamAllocation, null,
          response);
    }
  }
}
