package com.devicedata.messagesend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

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
// 使用 STOMP 协议进行通信。
// 连接成功后发送 CONNECT 帧，收到 CONNECTED 后订阅 /data/pub/response。
// 数据发送时包装成 STOMP SEND 帧到 /data/pub/{deviceId}。
public class DataWebSocketClient {

    public interface Listener {
        void onLog(String line);
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    private final String baseWsUrl;
    private final String deviceIdPath;
    private final String deviceId;
    private final Listener listener;

    private final OkHttpClient client;
    @Nullable private WebSocket webSocket;
    private boolean connected;
    private boolean stompConnected;
    private boolean shuttingDown;

    private final Queue<String> pending = new ArrayDeque<>();
    private int retryAttempt = 0;

    public DataWebSocketClient(@NonNull String baseWsUrl,
                               @NonNull String deviceId,
                               @NonNull Listener listener) {
    this.baseWsUrl = ensureNoTrailingSlash(baseWsUrl);
        // 使用设备真实 ID 作为路径段（服务端将按原始 ID 识别，例如 MAC 地址包含冒号）
        this.deviceIdPath = "/data/" + deviceId;
        this.deviceId = deviceId;
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
                // 发送 STOMP CONNECT 帧
                sendStompConnect();
            }

            @Override public void onMessage(WebSocket ws, String text) {
                // 解析 STOMP 帧
                handleStompFrame(text);
            }

            @Override public void onMessage(WebSocket ws, ByteString bytes) {
            }

            @Override public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }

            @Override public void onClosed(WebSocket ws, int code, String reason) {
                synchronized (DataWebSocketClient.this) {
                    connected = false;
                    stompConnected = false;
                    webSocket = null;
                }
                log("WS closed: " + code + ", " + reason);
                listener.onDisconnected();
                scheduleReconnect();
            }

            @Override public void onFailure(WebSocket ws, Throwable t, @Nullable Response response) {
                synchronized (DataWebSocketClient.this) {
                    connected = false;
                    stompConnected = false;
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
        stompConnected = false;
        pending.clear();
    }

    public synchronized void send(String json) {
        if (shuttingDown) return;
        if (stompConnected && webSocket != null) {
            // 将 JSON 包装成 STOMP SEND 帧
            String destination = "/data/pub/" + deviceId;
            StompFrame frame = StompFrame.buildSend(destination, "application/json", json);
            String frameText = frame.serialize();
            boolean ok = webSocket.send(frameText);
            if (ok) {
                log("WS -> SEND to " + destination + " (" + json.length() + " bytes)");
            } else {
                error("WS send returned false, queueing");
                pending.add(json);
            }
        } else {
            pending.add(json);
            if (!connected) {
                connect();
            }
        }
    }

    private synchronized void flushPending() {
        while (!pending.isEmpty() && stompConnected && webSocket != null) {
            String next = pending.poll();
            if (next == null) break;
            // 将 JSON 包装成 STOMP SEND 帧
            String destination = "/data/pub/" + deviceId;
            StompFrame frame = StompFrame.buildSend(destination, "application/json", next);
            webSocket.send(frame.serialize());
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

    /**
     * 发送 STOMP CONNECT 帧
     */
    private synchronized void sendStompConnect() {
        if (webSocket == null || !connected) return;
        StompFrame connectFrame = StompFrame.buildConnect();
        String frameText = connectFrame.serialize();
        boolean ok = webSocket.send(frameText);
        if (ok) {
            log("WS -> STOMP CONNECT");
        } else {
            error("Failed to send STOMP CONNECT");
        }
    }

    /**
     * 发送 STOMP SUBSCRIBE 帧
     */
    private synchronized void sendStompSubscribe() {
        if (webSocket == null || !stompConnected) return;
        String destination = "/data/pub/response";
        StompFrame subscribeFrame = StompFrame.buildSubscribe(destination, "sub-0");
        String frameText = subscribeFrame.serialize();
        boolean ok = webSocket.send(frameText);
        if (ok) {
            log("WS -> STOMP SUBSCRIBE to " + destination);
        } else {
            error("Failed to send STOMP SUBSCRIBE");
        }
    }

    /**
     * 处理接收到的 STOMP 帧
     */
    private void handleStompFrame(String text) {
        try {
            StompFrame frame = StompFrame.parse(text);
            if (frame == null) {
                log("WS <- invalid STOMP frame");
                return;
            }

            if (frame.isConnected()) {
                // 收到 CONNECTED 帧
                synchronized (DataWebSocketClient.this) {
                    stompConnected = true;
                }
                log("WS <- STOMP CONNECTED");
                // 订阅响应通道
                sendStompSubscribe();
                // 通知应用层连接就绪
                listener.onConnected();
                // 发送队列中的消息
                flushPending();
            } else if (frame.isMessage()) {
                // 收到 MESSAGE 帧
                String destination = frame.getDestination();
                log("WS <- STOMP MESSAGE from " + destination);
                // 解析 body 中的 JSON
                if ("/data/pub/response".equals(destination) && !frame.body.isEmpty()) {
                    try {
                        JSONObject json = new JSONObject(frame.body);
                        int count = json.optInt("count", -1);
                        long t = json.optLong("t", -1);
                        log("ACK received: count=" + count + ", t=" + t);
                    } catch (JSONException e) {
                        log("Failed to parse MESSAGE body: " + e.getMessage());
                    }
                }
            } else if (frame.isError()) {
                // 收到 ERROR 帧
                String message = frame.body.isEmpty() ? "unknown error" : frame.body;
                error("WS <- STOMP ERROR: " + message);
            } else {
                log("WS <- STOMP " + frame.command);
            }
        } catch (Exception e) {
            error("Error parsing STOMP frame: " + e.getMessage());
        }
    }

    private void log(String line) { listener.onLog(line); }
    private void error(String err) { listener.onError(err); }
}