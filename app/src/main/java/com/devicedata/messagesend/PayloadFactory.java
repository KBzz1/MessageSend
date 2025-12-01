package com.devicedata.messagesend;

import com.devicedata.messagesend.model.VitalsReading;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 构造上送 JSON（字段与后台约定保持一致）。
 * 注意：按最新需求，以 250Hz 频率逐点发送波形，不再批量。
 */
public final class PayloadFactory {

    private PayloadFactory() {
    }

    /**
     * 当前上送的 JSON 结构示例（单点）：
     * {
     *   "ecg": 100,
     *   "resp": 25,
     *   "bo": 95,
     *   "hr": 75,
     *   "temp": 36.6,
     *   "boWave": 90,
     *   "respWave": 90,
     *   "timestamp": 1731420000000
     * }
     *
     * 说明：
     * - deviceId 通过 STOMP 目的地 /data/pub/{deviceId} 传递，这里不再重复
     * - userid 如无后端要求，也不再放入 payload
     */
    public static JSONObject buildPayload(VitalsReading reading,
                                          String userId,
                                          String deviceId,
                                          Integer spo2WavePoint) {
        JSONObject root = new JSONObject();
        try {
            root.put("timestamp", reading.timestamp); // 时间戳（毫秒）

            if (reading.bloodOxygen != null) {
                root.put("bo", String.valueOf(reading.bloodOxygen)); // 血氧饱和度
            }
            if (reading.pulseRate != null) {
                root.put("hr", String.valueOf(reading.pulseRate)); // 脉率
            }
            if (reading.temperature != null) {
                root.put("temp", String.format(Locale.US, "%.1f", reading.temperature)); // 体温（摄氏）
            }
            if (reading.ecgWave != null) {
                root.put("ecg", reading.ecgWave); // ECG 波形点
            }
            if (reading.respirationRate != null) {
                root.put("resp", reading.respirationRate); // 呼吸率（低频）
            }
            if (reading.respWave != null) {
                root.put("respWave", reading.respWave); // 呼吸波形点
            }
            if (spo2WavePoint != null) {
                root.put("boWave", spo2WavePoint); // 血氧波形单点
            }
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to build payload", e);
        }
        return root;
    }
}
