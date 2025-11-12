package com.devicedata.messagesend;

import com.devicedata.messagesend.model.VitalsReading;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.List;

/**
 * 负责构造上传用的 JSON 数据对象，保证字段格式符合后台要求。
 */
public final class PayloadFactory {

    private PayloadFactory() {
    }

    /**
      当前上送的 JSON 结构示例：
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
        "boWaveSamples": [130, 131, 132 ...]
      }
     */
    public static JSONObject buildPayload(VitalsReading reading,
                                          String userId,
                                          String deviceId,
                                          List<Integer> spo2WaveSamples) {
        JSONObject root = new JSONObject();
        try {
            root.put("userid", userId); // 用户 ID
            root.put("deviceId", deviceId); // 设备 MAC 地址
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
            if (spo2WaveSamples != null && !spo2WaveSamples.isEmpty()) {
                // 将高频血氧数据打包成数组，避免频繁 HTTP 请求
                JSONArray samplesArray = new JSONArray();
                for (Integer sample : spo2WaveSamples) {
                    if (sample != null) {
                        samplesArray.put(sample);
                    }
                }
                if (samplesArray.length() > 0) {
                    root.put("boWaveSamples", samplesArray); // 血氧波形批量样本
                }
            }
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to build payload", e);
        }
        return root;
    }
}
