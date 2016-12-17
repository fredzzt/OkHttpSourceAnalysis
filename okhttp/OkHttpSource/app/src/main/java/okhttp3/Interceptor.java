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

/**
 * Observes, modifies, and potentially short-circuits requests going out and the corresponding
 * requests coming back in. Typically interceptors will be used to add, remove, or transform headers
 * on the request or response.
 * 拦截器是okhttp中强大的流程装置，它可以用来监控log，修改请求，修改结果，甚至是对用户透明的GZIP压缩。
 * 类似于脚本语言中的map操作。
 * 在okhttp中，内部维护了一个Interceptors的List，通过InterceptorChain进行多次拦截修改操作。

 */
public interface Interceptor {
  Response intercept(Chain chain) throws IOException;

  interface Chain {
    Request request();

    Response proceed(Request request) throws IOException;

    Connection connection();
  }
}
