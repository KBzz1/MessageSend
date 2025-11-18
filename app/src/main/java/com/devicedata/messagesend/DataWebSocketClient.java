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
// 实现 STOMP 协议，维护一个发送队列，连接就绪后会自动 flush。
public class DataWebSocketClient {

    public interface Listener {
        void onLog(String line);
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    private final String baseWsUrl;
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
        // WebSocket 端点：ws://host:port/data/{deviceId}
        String url = baseWsUrl + "/data/" + deviceId;
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
                sendStompConnect(ws);
            }

            @Override public void onMessage(WebSocket ws, String text) {
                // 解析 STOMP 帧
                parseStompFrame(text);
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
            // 包装为 STOMP SEND 帧
            String stompFrame = buildStompSendFrame(json);
            boolean ok = webSocket.send(stompFrame);
            if (ok) {
                log("WS -> (" + json.length() + " bytes)");
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
            String stompFrame = buildStompSendFrame(next);
            webSocket.send(stompFrame);
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

    // STOMP 协议实现

    /**
     * 发送 STOMP CONNECT 帧
     * 格式：
     * CONNECT
     * accept-version:1.2
     * heart-beat:0,0
     * 
     * ^@
     */
    private void sendStompConnect(WebSocket ws) {
        StringBuilder frame = new StringBuilder();
        frame.append("CONNECT\n");
        frame.append("accept-version:1.2\n");
        frame.append("heart-beat:0,0\n");
        frame.append("\n");
        frame.append('\0');
        ws.send(frame.toString());
        log("STOMP -> CONNECT");
    }

    /**
     * 发送 STOMP SUBSCRIBE 帧
     * 格式：
     * SUBSCRIBE
     * id:sub-0
     * destination:/data/pub/response
     * 
     * ^@
     */
    private synchronized void sendStompSubscribe() {
        if (webSocket == null) return;
        StringBuilder frame = new StringBuilder();
        frame.append("SUBSCRIBE\n");
        frame.append("id:sub-0\n");
        frame.append("destination:/data/pub/response\n");
        frame.append("\n");
        frame.append('\0');
        webSocket.send(frame.toString());
        log("STOMP -> SUBSCRIBE to /data/pub/response");
    }

    /**
     * 构造 STOMP SEND 帧
     * 格式：
     * SEND
     * destination:/data/pub/{deviceId}
     * content-type:application/json
     * content-length:{bodyLength}
     * 
     * {jsonBody}^@
     */
    private String buildStompSendFrame(String jsonBody) {
        StringBuilder frame = new StringBuilder();
        frame.append("SEND\n");
        frame.append("destination:/data/pub/").append(deviceId).append("\n");
        frame.append("content-type:application/json\n");
        frame.append("content-length:").append(jsonBody.length()).append("\n");
        frame.append("\n");
        frame.append(jsonBody);
        frame.append('\0');
        return frame.toString();
    }

    /**
     * 解析 STOMP 帧
     * 帧格式：
     * COMMAND
     * header1:value1
     * header2:value2
     * 
     * body^@
     */
    private void parseStompFrame(String frameText) {
        if (frameText == null || frameText.isEmpty()) {
            return;
        }

        // 移除结尾的 NULL 字节
        String frame = frameText.replace("\0", "");
        
        // 分割帧为行
        String[] lines = frame.split("\n", -1);
        if (lines.length == 0) {
            return;
        }

        String command = lines[0].trim();
        
        // 解析头部
        int bodyStartIndex = -1;
        String destination = null;
        String contentType = null;
        
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                // 空行表示头部结束，body 开始
                bodyStartIndex = i + 1;
                break;
            }
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                if ("destination".equals(key)) {
                    destination = value;
                } else if ("content-type".equals(key)) {
                    contentType = value;
                }
            }
        }

        // 提取 body
        String body = "";
        if (bodyStartIndex > 0 && bodyStartIndex < lines.length) {
            StringBuilder bodyBuilder = new StringBuilder();
            for (int i = bodyStartIndex; i < lines.length; i++) {
                if (i > bodyStartIndex) {
                    bodyBuilder.append("\n");
                }
                bodyBuilder.append(lines[i]);
            }
            body = bodyBuilder.toString();
        }

        // 处理不同的 STOMP 命令
        handleStompCommand(command, destination, body);
    }

    /**
     * 处理 STOMP 命令
     */
    private void handleStompCommand(String command, String destination, String body) {
        log("STOMP <- " + command + (destination != null ? " [" + destination + "]" : ""));
        
        switch (command) {
            case "CONNECTED":
                // STOMP 连接成功
                synchronized (this) {
                    stompConnected = true;
                }
                log("STOMP connected");
                // 订阅响应通道
                sendStompSubscribe();
                // 通知应用层连接成功
                listener.onConnected();
                // 发送待发送的消息
                flushPending();
                break;

            case "MESSAGE":
                // 收到消息
                if ("/data/pub/response".equals(destination)) {
                    // 解析响应 JSON
                    parseResponseMessage(body);
                } else {
                    log("Received MESSAGE from " + destination + ": " + 
                        (body.length() > 120 ? body.substring(0, 120) + "..." : body));
                }
                break;

            case "ERROR":
                // 错误消息
                error("STOMP ERROR: " + body);
                break;

            case "RECEIPT":
                // 接收确认
                log("STOMP RECEIPT received");
                break;

            default:
                log("Unknown STOMP command: " + command);
                break;
        }
    }

    /**
     * 解析来自 /data/pub/response 的响应消息
     * 预期包含 count 和 t 字段
     */
    private void parseResponseMessage(String body) {
        if (body == null || body.trim().isEmpty()) {
            return;
        }
        
        try {
            JSONObject json = new JSONObject(body);
            int count = json.optInt("count", -1);
            long t = json.optLong("t", -1);
            
            if (count >= 0 || t >= 0) {
                log("ACK: count=" + count + ", t=" + t);
            } else {
                log("Response: " + body);
            }
        } catch (JSONException e) {
            log("Response (non-JSON): " + body);
        }
    }

    private void log(String line) { listener.onLog(line); }
    private void error(String err) { listener.onError(err); }
}