package com.devicedata.messagesend;

import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;
import retrofit2.http.Url;

/**
 * Retrofit 接口定义，支持向任意完整 URL 发送 JSON 请求。
 */
interface PaaApiService {

    @POST
    Call<ResponseBody> postJson(@Url String url, @Body RequestBody body);
}
