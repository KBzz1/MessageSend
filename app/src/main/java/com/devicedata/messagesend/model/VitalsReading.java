package com.devicedata.messagesend.model;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * 生命体征数据实体，封装单次采样的所有指标与波形。
 */
public class VitalsReading {

    public final long timestamp;
    @Nullable
    public final Integer ecgWave;
    @Nullable
    public final Integer ecgHeartRate;
    @Nullable
    public final Integer respirationRate;
    @Nullable
    public final Integer systolic;
    @Nullable
    public final Integer diastolic;
    @Nullable
    public final Integer meanArterialPressure;
    @Nullable
    public final Integer bloodOxygen;
    @Nullable
    public final Integer pulseRate;
    @Nullable
    public final Double temperature;
    @Nullable
    public final List<Integer> spo2Waveform;
    @Nullable
    public final Integer respWave;

    public VitalsReading(long timestamp,
                         @Nullable Integer ecgWave,
                         @Nullable Integer ecgHeartRate,
                         @Nullable Integer respirationRate,
                         @Nullable Integer systolic,
                         @Nullable Integer diastolic,
                         @Nullable Integer meanArterialPressure,
                         @Nullable Integer bloodOxygen,
                         @Nullable Integer pulseRate,
                         @Nullable Double temperature,
                         @Nullable List<Integer> spo2Waveform,
                         @Nullable Integer respWave) {
        this.timestamp = timestamp;
        this.ecgWave = ecgWave;
        this.ecgHeartRate = ecgHeartRate;
        this.respirationRate = respirationRate;
        this.systolic = systolic;
        this.diastolic = diastolic;
        this.meanArterialPressure = meanArterialPressure;
        this.bloodOxygen = bloodOxygen;
        this.pulseRate = pulseRate;
        this.temperature = temperature;
        this.spo2Waveform = spo2Waveform != null ? Collections.unmodifiableList(spo2Waveform) : null;
        this.respWave = respWave;
    }

    public boolean isWaveformOnly() {
        return ecgHeartRate == null
                && respirationRate == null
                && systolic == null
                && diastolic == null
                && bloodOxygen == null
                && temperature == null;
    }
}
