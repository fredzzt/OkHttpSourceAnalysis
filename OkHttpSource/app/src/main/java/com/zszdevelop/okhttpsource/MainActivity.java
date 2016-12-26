package com.zszdevelop.okhttpsource;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;
import okio.Okio;
import retrofit2.Retrofit;
import retrofit2.http.GET;
import retrofit2.http.Path;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        retrofitHttp();
    }

    private void retrofitHttp() {


        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("https://api.github.com/")
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        GitHubService service = retrofit.create(GitHubService.class);
        Call<List<Repo>> repos = service.listRepos("octocat");
        repos.enqueue(new Callback<List<Repo>>() {
            @Override
            public void onResponse(Call<List<Repo>> call, Response<List<Repo>> response) {

            }
            @Override
            public void onFailure(Call<List<Repo>> call, Throwable t) {

            }
        });


    }

    public static void readString(InputStream in) throws IOException {
        BufferedSource source = Okio.buffer(Okio.source(in));  //创建BufferedSource
        String s = source.readUtf8();  //以UTF-8读
        System.out.println(s);     //打印

    }

    private void postAsynHttp() {
        OkHttpClient   mOkHttpClient=new OkHttpClient();
        RequestBody formBody = new FormBody.Builder()
                .add("size", "10")
                .build();
        Request request = new Request.Builder()
                .url("http://api.1-blog.com/biz/bizserver/article/list.do")
                .post(formBody)
                .build();
        Call call = mOkHttpClient.newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Request request, IOException e) {
                Log.e(">>>>>>>>>","onFailure");
            }

            @Override
            public void onResponse(Response response) throws IOException {
                Log.e(">>>>>>>>>","onFailure");
                String str = response.body().string();
                Log.i("wangshu", str);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(getApplicationContext(), "请求成功", Toast.LENGTH_SHORT).show();
                    }
                });
            }



        });
    }

    /**
     * API的编写

     我们已经new好了一个我们需要的Retrofit对象，那么下一步就是编写API了。如何编写API呢？Retrofit的方式是用过java interface和注解的方式进行定义。例如：
     */
    public interface GitHubService {
        @GET("users/{user}/repos")
        //通过Call<T>构建成一个interface。Call<T>这个接口分别在OkHttpCall和ExecutorCallbackCall中做了具体的实现。
        Call<List<Repo>> listRepos(@Path("user") String user);
    }
}
