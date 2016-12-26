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
package retrofit2.http;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import okhttp3.HttpUrl;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** Make a GET request.
 * 这是一个运行时的方法注解，用来构造get请求，唯一的参数是一个string值，默认是空字符串
 * 。那么我们可以理解了，@GET(xxxxx)就是构造了一个用于get请求的url。下一个注解是Path注解，是一个运行时的参数注解，它是为了方便我们构建动态的url，参数是一个string值，还可以设置参数是否已经是URL encode编码，默认是false。
 * 最后我们看到，通过Call<T>构建成一个interface。Call<T>这个接口分别在OkHttpCall和ExecutorCallbackCall中做了具体的实现。
 * */

//@Documented用于描述其它类型的annotation应该被作为被标注的程序成员的公共API，因此可以被例如javadoc此类的工具文档化。Documented是一个标记注解，没有成员。
@Documented
//Target说明了Annotation所修饰的对象范围：方法有效
@Target(METHOD)
//用于描述注解的生命周期（即：被描述的注解在什么范围内有效）(运行时有效)
@Retention(RUNTIME)

//自定义注解：使用@interface自定义注解时，自动继承了java.lang.annotation.Annotation接口，
// 由编译程序自动完成其他细节。在定义注解时，不能继承其他的注解或接口。
// @interface用来声明一个注解，其中的每一个方法实际上是声明了一个配置参数。
// 方法的名称就是参数的名称，返回值类型就是参数的类型（返回值类型只能是基本类型、Class、String、enum）。
// 可以通过default来声明参数的默认值。
//
//        　　定义注解格式：
//        　　public @interface 注解名 {定义体}

/**
 * 这是一个运行时的方法注解，用来构造get请求，唯一的参数是一个string值，默认是空字符串。那么我们可以理解了，@GET(xxxxx)就是构造了一个用于get请求的url。
 */
public @interface GET {
  /**
   * A relative or absolute path, or full URL of the endpoint. This value is optional if the first
   * parameter of the method is annotated with {@link Url @Url}.
   * <p>
   * See {@linkplain retrofit2.Retrofit.Builder#baseUrl(HttpUrl) base URL} for details of how
   * this is resolved against a base URL to create the full endpoint URL.
   */
  //第一,只能用public或默认(default)这两个访问权修饰.例如,String value();这里把方法设为defaul默认类型；
//  　第二,参数成员只能用基本类型byte,short,char,int,long,float,double,boolean八种基本数据类型和 String,Enum,Class,annotations等数据类型,以及这一些类型的数组.例如,String value();这里的参数成员就为String;　　
//   第三,如果只有一个参数成员,最好把参数名称设为"value",后加小括号.例:下面的例子FruitName注解就只有一个参数成员。


  String value() default "";
}
