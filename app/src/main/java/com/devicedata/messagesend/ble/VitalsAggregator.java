package com.devicedata.messagesend.ble;

import com.devicedata.messagesend.model.VitalsReading;

import java.util.ArrayList;
import java.util.List;

/**
 * 汇总设备发来的分包数据，组合成完整的生命体征快照。
 */
class VitalsAggregator {

    private Integer ecgWave;
    private Integer ecgHeartRate;
    private Integer respirationRate;
    private Integer systolic;
    private Integer diastolic;
    private Integer meanArterialPressure;
    private Integer bloodOxygen;
    private Integer pulseRate;
    private Double temperature;
    private Integer respWave;
    // 不再批量缓存血氧波形，按需逐点发出

    VitalsReading update(int type, byte[] body) {
        switch (type) {
            case 0x01:
                ecgWave = body.length > 1 ? body[1] & 0xFF : null;
                return null;
            case 0x02:
                ecgHeartRate = body.length > 2 ? body[2] & 0xFF : null;
                respirationRate = body.length > 3 ? body[3] & 0xFF : null;
                return snapshot();
            case 0x03:
                systolic = body.length > 3 ? body[3] & 0xFF : null;
                meanArterialPressure = body.length > 4 ? body[4] & 0xFF : null;
                diastolic = body.length > 5 ? body[5] & 0xFF : null;
                return snapshot();
            case 0x04:
                bloodOxygen = body.length > 2 ? body[2] & 0xFF : null;
                pulseRate = body.length > 3 ? body[3] & 0xFF : null;
                return snapshot();
            case 0x05:
                if (body.length > 3) {
                    int tInt = body[2] & 0xFF;
                    int tDec = body[3] & 0xFF;
                    temperature = tInt + (tDec / 10.0);
                }
                return snapshot();
            case 0xFE:
                if (body.length > 1) {
                    // 直接构造仅包含单点血氧波形的 Reading
                    List<Integer> one = new ArrayList<>(1);
                    one.add(body[1] & 0xFF);
                    return new VitalsReading(System.currentTimeMillis(),
                            null, // ecgWave
                            null, // ecgHeartRate
                            null, // respirationRate
                            null, // systolic
                            null, // diastolic
                            null, // map
                            null, // bloodOxygen (饱和度为低频字段，单点发送由上层按最近值复用)
                            null, // pulseRate
                            null, // temperature
                            one,  // spo2Waveform 单点
                            null  // respWave
                    );
                }
                return null;
            case 0xFF:
                respWave = body.length > 1 ? body[1] & 0xFF : null;
                // 呼吸波也可视为波形，直接返回以便上层获取最新值
                return new VitalsReading(System.currentTimeMillis(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        respWave);
            default:
                return null;
        }
    }

    private VitalsReading snapshot() {
    return new VitalsReading(System.currentTimeMillis(),
                ecgWave,
                ecgHeartRate,
                respirationRate,
                systolic,
                diastolic,
                meanArterialPressure,
                bloodOxygen,
                pulseRate,
                temperature,
        null,
                respWave);
    }
}
