package com.devicedata.messagesend;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 处理网络上传的工具类，负责发送 JSON 请求并返回结果。
 */
public final class NetworkClient {

    public static final String SERVER_URL = "http://10.242.2.207:8080/api/vitals";

    private NetworkClient() {
    }

    public static Result postJson(String url, JSONObject payload) {
        HttpURLConnection connection = null;
        try {
            URL targetUrl = URI.create(url).toURL();
            connection = (HttpURLConnection) targetUrl.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");

            // 统一使用 UTF-8 编码发送 JSON 数据
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body);
            }

            int status = connection.getResponseCode();
            boolean success = status >= 200 && status < 300;
            InputStream stream = success ? connection.getInputStream() : connection.getErrorStream();
            String response = stream != null ? readStream(stream) : "";
            String message = success
                    ? "HTTP " + status + " 请求成功"
                    : "HTTP " + status + " 错误：" + response;
            return new Result(success, message);
        } catch (IOException e) {
            return new Result(false, "网络错误：" + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readStream(InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString();
        }
    }

    public static final class Result {
        public final boolean success;
        public final String message;

        public Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
