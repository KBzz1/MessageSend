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
    private final List<Integer> spo2WaveBuffer = new ArrayList<>();

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
                    // 血氧波形为高频数据，逐点写入缓存
                    spo2WaveBuffer.add(body[1] & 0xFF);
                }
                return null;
            case 0xFF:
                respWave = body.length > 1 ? body[1] & 0xFF : null;
                return null;
            default:
                return null;
        }
    }

    private VitalsReading snapshot() {
    // 截取血氧波形缓存，随同本次快照上传
    List<Integer> spo2Waveform = spo2WaveBuffer.isEmpty() ? null : new ArrayList<>(spo2WaveBuffer);
    spo2WaveBuffer.clear();
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
        spo2Waveform,
                respWave);
    }
}
