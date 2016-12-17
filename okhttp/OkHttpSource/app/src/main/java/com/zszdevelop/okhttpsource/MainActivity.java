package com.zszdevelop.okhttpsource;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        postAsynHttp();
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
}
