package com.devicedata.messagesend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
// WebSocket 客户端：负责连接 ws://host:port/data/{deviceId}，
// 维护一个发送队列，连接就绪后会自动 flush。
public class DataWebSocketClient {

    public interface Listener {
        void onLog(String line);
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    private final String baseWsUrl;
    private final String deviceIdPath;
    private final Listener listener;

    private final OkHttpClient client;
    @Nullable private WebSocket webSocket;
    private boolean connected;
    private boolean shuttingDown;

    private final Queue<String> pending = new ArrayDeque<>();
    private int retryAttempt = 0;

    public DataWebSocketClient(@NonNull String baseWsUrl,
                               @NonNull String deviceId,
                               @NonNull Listener listener) {
    this.baseWsUrl = ensureNoTrailingSlash(baseWsUrl);
        // 使用设备真实 ID 作为路径段（服务端将按原始 ID 识别，例如 MAC 地址包含冒号）
        this.deviceIdPath = "/data/" + deviceId;
        this.listener = listener;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
                .build();
    }

    public synchronized void connect() {
        if (shuttingDown) return;
        if (connected || webSocket != null) return;
    String url = baseWsUrl + deviceIdPath; // 最终形如 ws://host:port/data/{deviceId}
        Request request = new Request.Builder().url(url).build();
        log("WS connecting to " + url);
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                synchronized (DataWebSocketClient.this) {
                    connected = true;
                    retryAttempt = 0;
                }
                log("WS connected");
                listener.onConnected();
                flushPending();
            }

            @Override public void onMessage(WebSocket ws, String text) {
                log("WS <- " + (text.length() > 120 ? text.substring(0, 120) + "..." : text));
            }

            @Override public void onMessage(WebSocket ws, ByteString bytes) {
            }

            @Override public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }

            @Override public void onClosed(WebSocket ws, int code, String reason) {
                synchronized (DataWebSocketClient.this) {
                    connected = false;
                    webSocket = null;
                }
                log("WS closed: " + code + ", " + reason);
                listener.onDisconnected();
                scheduleReconnect();
            }

            @Override public void onFailure(WebSocket ws, Throwable t, @Nullable Response response) {
                synchronized (DataWebSocketClient.this) {
                    connected = false;
                    webSocket = null;
                }
                StringBuilder sb = new StringBuilder();
                sb.append("WS failure: ").append(t.getMessage());
                if (response != null) {
                    sb.append(" | HTTP ").append(response.code()).append(" ").append(response.message());
                    try {
                        if (response.body() != null) {
                            String body = response.body().string();
                            if (body != null && !body.isEmpty()) {
                                sb.append(" | body: ");
                                sb.append(body.length() > 256 ? body.substring(0, 256) + "..." : body);
                            }
                        }
                    } catch (Exception ignored) {}
                }
                error(sb.toString());
                scheduleReconnect();
            }
        });
    }

    public synchronized void shutdown() {
        shuttingDown = true;
        if (webSocket != null) {
            try { webSocket.close(1000, "app shutdown"); } catch (Exception ignored) {}
            webSocket = null;
        }
        connected = false;
        pending.clear();
    }

    public synchronized void send(String json) {
        if (shuttingDown) return;
        if (connected && webSocket != null) {
            boolean ok = webSocket.send(json);
            if (ok) {
                log("WS -> (" + json.length() + " bytes)");
            } else {
                error("WS send returned false, queueing");
                pending.add(json);
            }
        } else {
            pending.add(json);
            connect();
        }
    }

    private synchronized void flushPending() {
        while (!pending.isEmpty() && connected && webSocket != null) {
            String next = pending.poll();
            if (next == null) break;
            webSocket.send(next);
        }
        if (!pending.isEmpty()) {
            log("WS pending queue size: " + pending.size());
        }
    }

    private synchronized void scheduleReconnect() {
        if (shuttingDown) return;
        retryAttempt++;
        int delaySec = Math.min(30, (1 << Math.min(5, retryAttempt)));
        // 这里只提示重连间隔；如需自动重连，可结合 Handler/定时任务在 delaySec 后调用 connect()
        log("WS reconnect in " + delaySec + "s (attempt " + retryAttempt + ")");
    }

    private static String ensureNoTrailingSlash(String base) {
        if (base.endsWith("/")) return base.substring(0, base.length() - 1);
        return base;
    }

    private void log(String line) { listener.onLog(line); }
    private void error(String err) { listener.onError(err); }
}