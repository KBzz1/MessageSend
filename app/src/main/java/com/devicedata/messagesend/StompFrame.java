package com.devicedata.messagesend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * STOMP 帧工具类，用于构造和解析 STOMP 协议帧。
 * 
 * STOMP 帧格式：
 * COMMAND
 * header1:value1
 * header2:value2
 * 
 * body^@
 * 
 * 其中 ^@ 是 NULL 字符（\0）
 */
public class StompFrame {
    private static final String NULL_CHAR = "\0";
    private static final String LINE_FEED = "\n";

    public final String command;
    public final Map<String, String> headers;
    public final String body;

    public StompFrame(@NonNull String command, @Nullable Map<String, String> headers, @Nullable String body) {
        this.command = command;
        this.headers = headers != null ? new LinkedHashMap<>(headers) : new LinkedHashMap<>();
        this.body = body != null ? body : "";
    }

    /**
     * 构造 CONNECT 帧
     */
    public static StompFrame buildConnect() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept-version", "1.0,1.1,1.2");
        headers.put("heart-beat", "0,0");
        return new StompFrame("CONNECT", headers, null);
    }

    /**
     * 构造 SUBSCRIBE 帧
     */
    public static StompFrame buildSubscribe(String destination, String id) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("id", id);
        headers.put("destination", destination);
        return new StompFrame("SUBSCRIBE", headers, null);
    }

    /**
     * 构造 SEND 帧
     */
    public static StompFrame buildSend(String destination, String contentType, String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("destination", destination);
        if (contentType != null && !contentType.isEmpty()) {
            headers.put("content-type", contentType);
        }
        if (body != null && !body.isEmpty()) {
            headers.put("content-length", String.valueOf(body.length()));
        }
        return new StompFrame("SEND", headers, body);
    }

    /**
     * 序列化为 STOMP 帧字符串
     */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append(command).append(LINE_FEED);
        
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            sb.append(entry.getKey()).append(":").append(entry.getValue()).append(LINE_FEED);
        }
        
        sb.append(LINE_FEED);
        
        if (body != null && !body.isEmpty()) {
            sb.append(body);
        }
        
        sb.append(NULL_CHAR);
        return sb.toString();
    }

    /**
     * 解析 STOMP 帧
     */
    public static StompFrame parse(String frameText) {
        if (frameText == null || frameText.isEmpty()) {
            return null;
        }

        // 移除结尾的 NULL 字符
        String text = frameText;
        if (text.endsWith(NULL_CHAR)) {
            text = text.substring(0, text.length() - 1);
        }

        String[] lines = text.split(LINE_FEED, -1);
        if (lines.length == 0) {
            return null;
        }

        String command = lines[0].trim();
        Map<String, String> headers = new LinkedHashMap<>();
        int bodyStartIndex = -1;

        // 解析头部
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                bodyStartIndex = i + 1;
                break;
            }
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex);
                String value = line.substring(colonIndex + 1);
                headers.put(key, value);
            }
        }

        // 解析 body
        StringBuilder bodyBuilder = new StringBuilder();
        if (bodyStartIndex > 0 && bodyStartIndex < lines.length) {
            for (int i = bodyStartIndex; i < lines.length; i++) {
                if (i > bodyStartIndex) {
                    bodyBuilder.append(LINE_FEED);
                }
                bodyBuilder.append(lines[i]);
            }
        }

        return new StompFrame(command, headers, bodyBuilder.toString());
    }

    /**
     * 判断是否为 CONNECTED 帧
     */
    public boolean isConnected() {
        return "CONNECTED".equals(command);
    }

    /**
     * 判断是否为 MESSAGE 帧
     */
    public boolean isMessage() {
        return "MESSAGE".equals(command);
    }

    /**
     * 判断是否为 ERROR 帧
     */
    public boolean isError() {
        return "ERROR".equals(command);
    }

    /**
     * 获取目标地址
     */
    @Nullable
    public String getDestination() {
        return headers.get("destination");
    }

    @Override
    public String toString() {
        return "StompFrame{command='" + command + "', headers=" + headers.size() + ", bodyLen=" + body.length() + "}";
    }
}
