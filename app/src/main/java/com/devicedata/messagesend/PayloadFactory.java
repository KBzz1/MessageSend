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
            当前上送的 JSON 结构示例（单点）：
      {
        "userid": "demo-001",
        "deviceId": "34:81:F4:75:20:70",
        "timestamp": 1762925484202,
        "hr2": "72",
        "bp": "18",
        "sbpressure": "120",
        "dbpressure": "75",
        "mapressure": "90",
        "bo": "98",
        "hr": "70",
        "temp": "36.5",
                "ecg": 128,
                "respWave": 64,
                "boWave": 130
      }
     */
        public static JSONObject buildPayload(VitalsReading reading,
                                                                                    String userId,
                                                                                    String deviceId,
                                                                                    Integer spo2WavePoint) {
        JSONObject root = new JSONObject();
        try {
            root.put("userid", userId);        // 用户 ID
            root.put("deviceId", deviceId);    // 设备 MAC 地址
            root.put("timestamp", reading.timestamp); // 时间戳（毫秒）

            if (reading.ecgHeartRate != null) {
                root.put("hr2", String.valueOf(reading.ecgHeartRate)); // ECG 计算心率
            }
            if (reading.respirationRate != null) {
                root.put("bp", String.valueOf(reading.respirationRate)); // 设备提供的呼吸频率
            }
            if (reading.systolic != null) {
                root.put("sbpressure", String.valueOf(reading.systolic)); // 收缩压
            }
            if (reading.diastolic != null) {
                root.put("dbpressure", String.valueOf(reading.diastolic)); // 舒张压
            }
            if (reading.meanArterialPressure != null) {
                root.put("mapressure", String.valueOf(reading.meanArterialPressure)); // 平均动脉压
            }
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
