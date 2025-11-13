package com.devicedata.messagesend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 简单的 HTTP 发送客户端：将 JSON 以 POST 方式发送到 http://host:port/data/{deviceId}
 * 模拟 WebSocket 客户端的行为：提供 connect()/send()/shutdown() 与日志回调。
 */
public class DataHttpClient {

    public interface Listener {
        void onLog(String line);
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String baseHttpUrl;
    private final String deviceIdPath;
    private final Listener listener;

    private final OkHttpClient client;
    private final Queue<String> pending = new ArrayDeque<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;
    private volatile boolean shuttingDown = false;

    public DataHttpClient(@NonNull String baseHttpUrl,
                          @NonNull String deviceId,
                          @NonNull Listener listener) {
        this.baseHttpUrl = ensureNoTrailingSlash(baseHttpUrl);
        this.deviceIdPath = "/data/" + deviceId; // 与 WS 路径保持一致
        this.listener = listener;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    public synchronized void connect() {
        if (running) return;
        running = true;
        shuttingDown = false;
        log("HTTP queue ready at " + baseHttpUrl + deviceIdPath);
        listener.onConnected();
        worker.execute(this::drainLoop);
    }

    public synchronized void shutdown() {
        shuttingDown = true;
        running = false;
        try { worker.shutdownNow(); } catch (Exception ignored) {}
        listener.onDisconnected();
    }

    public synchronized void send(@NonNull String json) {
        if (shuttingDown) return;
        pending.add(json);
    }

    private void drainLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            String next;
            synchronized (this) {
                next = pending.poll();
            }
            if (next == null) {
                try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                continue;
            }
            postJson(next);
        }
    }

    private void postJson(String json) {
        String url = baseHttpUrl + deviceIdPath;
        RequestBody body = RequestBody.create(json, JSON);
        Request req = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        try (Response resp = client.newCall(req).execute()) {
            int code = resp.code();
            if (code >= 200 && code < 300) {
                log("HTTP -> 2xx ok (" + json.length() + " bytes)");
            } else {
                String snippet = null;
                try { snippet = resp.body() != null ? resp.body().string() : null; } catch (Exception ignored) {}
                error("HTTP non-2xx: " + code + (snippet != null ? (" | body: " + truncate(snippet, 256)) : ""));
            }
        } catch (IOException e) {
            error("HTTP failure: " + e.getMessage());
        }
    }

    private static String ensureNoTrailingSlash(String base) {
        if (base.endsWith("/")) return base.substring(0, base.length() - 1);
        return base;
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private void log(String line) { listener.onLog(line); }
    private void error(String err) { listener.onError(err); }
}
