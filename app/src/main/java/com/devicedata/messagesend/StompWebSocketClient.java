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

/**
 * STOMP over WebSocket 客户端
 * 握手地址：ws://host:port/ws
 * 发送路径：/data/pub/{deviceId}
 * 订阅路径：/data/pub/response
 */
public class StompWebSocketClient {

    private static final String NULL_CHAR = "\u0000";
    private static final String LF = "\n";

    public interface Listener {
        void onLog(String line);
        void onConnected();
        void onDisconnected();
        void onError(String error);
        void onAck(String message);
    }

    private final String wsUrl;
    private final String deviceId;
    private final Listener listener;

    private final OkHttpClient client;
    @Nullable private WebSocket webSocket;
    private boolean connected;
    private boolean stompConnected;
    private boolean shuttingDown;

    private final Queue<String> pending = new ArrayDeque<>();
    private int retryAttempt = 0;
    private int subscriptionId = 0;
    private long sendCount = 0;
    private long lastLogTime = 0;

    public StompWebSocketClient(@NonNull String baseWsUrl,
                                @NonNull String deviceId,
                                @NonNull Listener listener) {
        // 握手地址：ws://host:port/ws
        this.wsUrl = ensureNoTrailingSlash(baseWsUrl) + "/ws";
        this.deviceId = deviceId;
        this.listener = listener;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(25, TimeUnit.SECONDS)
                .build();
    }

    public synchronized void connect() {
        if (shuttingDown) return;
        if (connected || webSocket != null) return;

        Request request = new Request.Builder().url(wsUrl).build();
        log("正在连接 STOMP: " + wsUrl);

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket ws, @NonNull Response response) {
                synchronized (StompWebSocketClient.this) {
                    connected = true;
                    retryAttempt = 0;
                }
                log("WebSocket 已打开，正在发送 STOMP 握手");
                // 发送 STOMP CONNECT 帧
                sendStompConnect(ws);
            }

            @Override
            public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
                // 不打印每条收到的消息，减少日志量
                handleStompFrame(text);
            }

            @Override
            public void onMessage(@NonNull WebSocket ws, @NonNull ByteString bytes) {
                handleStompFrame(bytes.utf8());
            }

            @Override
            public void onClosing(@NonNull WebSocket ws, int code, @NonNull String reason) {
                ws.close(1000, null);
            }

            @Override
            public void onClosed(@NonNull WebSocket ws, int code, @NonNull String reason) {
                synchronized (StompWebSocketClient.this) {
                    connected = false;
                    stompConnected = false;
                    webSocket = null;
                }
                log("STOMP 已断开: " + code);
                listener.onDisconnected();
                scheduleReconnect();
            }

            @Override
            public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t, @Nullable Response response) {
                synchronized (StompWebSocketClient.this) {
                    connected = false;
                    stompConnected = false;
                    webSocket = null;
                }
                String errMsg = "连接失败: " + t.getMessage();
                error(errMsg);
                scheduleReconnect();
            }
        });
    }

    private void sendStompConnect(WebSocket ws) {
        // 简化的 STOMP CONNECT 帧
        String frame = "CONNECT" + LF +
                "accept-version:1.0,1.1,1.2" + LF +
                "heart-beat:0,0" + LF +
                LF + NULL_CHAR;
        ws.send(frame);
        log("STOMP 握手帧已发送");
    }

    private void handleStompFrame(String raw) {
        if (raw == null || raw.isEmpty()) return;

        // 移除末尾的 NULL 字符
        String frame = raw.replace(NULL_CHAR, "").trim();
        if (frame.isEmpty()) return;

        // 解析 STOMP 命令
        String[] lines = frame.split(LF, 2);
        String command = lines[0].trim();

        switch (command) {
            case "CONNECTED":
                synchronized (this) {
                    stompConnected = true;
                }
                log("STOMP 握手成功");
                // 订阅响应通道
                subscribe("/data/pub/response");
                listener.onConnected();
                flushPending();
                break;

            case "MESSAGE":
                // 解析消息体，不打印日志
                String body = extractBody(frame);
                listener.onAck(body);
                break;

            case "RECEIPT":
                // 收到确认，不打印
                break;

            case "ERROR":
                String errorBody = extractBody(frame);
                error("服务器错误: " + errorBody);
                break;

            default:
                // 心跳或其他帧，忽略
                break;
        }
    }

    private String extractBody(String frame) {
        int bodyStart = frame.indexOf(LF + LF);
        if (bodyStart >= 0) {
            return frame.substring(bodyStart + 2).trim();
        }
        return "";
    }

    private void subscribe(String destination) {
        if (webSocket == null) return;
        subscriptionId++;
        String frame = "SUBSCRIBE" + LF +
                "id:sub-" + subscriptionId + LF +
                "destination:" + destination + LF +
                LF + NULL_CHAR;
        webSocket.send(frame);
        log("已订阅: " + destination);
    }

    public synchronized void shutdown() {
        shuttingDown = true;
        if (webSocket != null) {
            // 发送 STOMP DISCONNECT
            try {
                String frame = "DISCONNECT" + LF + LF + NULL_CHAR;
                webSocket.send(frame);
                webSocket.close(1000, "app shutdown");
            } catch (Exception ignored) {}
            webSocket = null;
        }
        connected = false;
        stompConnected = false;
        pending.clear();
    }

    /**
     * 发送 JSON 数据到 /data/pub/{deviceId}
     */
    public synchronized void send(String json) {
        if (shuttingDown) return;

        if (stompConnected && webSocket != null) {
            String destination = "/data/pub/" + deviceId;
            String frame = "SEND" + LF +
                    "destination:" + destination + LF +
                    "content-type:application/json" + LF +
                    LF + json + NULL_CHAR;
            boolean ok = webSocket.send(frame);
            if (ok) {
                sendCount++;
                // 每5秒最多打印一次发送统计
                long now = System.currentTimeMillis();
                if (now - lastLogTime > 5000) {
                    log("已发送 " + sendCount + " 条数据");
                    lastLogTime = now;
                }
            } else {
                error("发送失败，已加入队列");
                pending.add(json);
            }
        } else {
            pending.add(json);
            connect();
        }
    }

    private synchronized void flushPending() {
        int flushed = 0;
        while (!pending.isEmpty() && stompConnected && webSocket != null) {
            String next = pending.poll();
            if (next == null) break;
            send(next);
            flushed++;
        }
        if (flushed > 0) {
            log("已补发 " + flushed + " 条缓存数据");
        }
    }

    private synchronized void scheduleReconnect() {
        if (shuttingDown) return;
        retryAttempt++;
        int delaySec = Math.min(30, (1 << Math.min(5, retryAttempt)));
        log(delaySec + "秒后重连 (第" + retryAttempt + "次)");
    }

    private static String ensureNoTrailingSlash(String base) {
        if (base.endsWith("/")) return base.substring(0, base.length() - 1);
        return base;
    }

    private void log(String line) { listener.onLog(line); }
    private void error(String err) { listener.onError(err); }
}
