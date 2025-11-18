package com.devicedata.messagesend;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.devicedata.messagesend.PayloadFactory;
import com.devicedata.messagesend.ble.BleManager;
import com.devicedata.messagesend.model.VitalsReading;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
// 主页：
// - 扫描并手动选择蓝牙设备
// - 以 250Hz 频率逐点发送（4ms 周期），低频字段保持最近值重复，直到更新
// - 通过 WebSocket 发送到网关 ws://host:port/data/{deviceId}
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_PERMISSIONS = 100;
    private static final String TARGET_NAME_PREFIX = "";
    private static final String TARGET_DEVICE_ADDRESS = "34:81:F4:75:20:70";
    private static final String DEFAULT_USER_ID = "demo-001";
    private static final String TARGET_USER_ID = "";

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private ScheduledExecutorService streamExecutor;
    private ScheduledFuture<?> streamTask;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BleManager bleManager;
    private TextView statusText;
    private TextView logText;
    // 切换为 WebSocket 发送客户端（支持 STOMP 协议）
    private DataWebSocketClient wsClient;
    private String currentDeviceId;
    private ArrayAdapter<BluetoothDevice> deviceAdapter;
    private final List<BluetoothDevice> nearbyDevices = new ArrayList<>();
    private final StringBuilder logBuffer = new StringBuilder();
    private final SimpleDateFormat logTimeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // 最新值缓存（流式发送每 4ms 复用）
    private volatile Integer latestEcgWave;
    private volatile Integer latestRespWave;
    private volatile Integer latestBoWave;
    private volatile Integer latestEcgHr;
    private volatile Integer latestRespRate;
    private volatile Integer latestSystolic;
    private volatile Integer latestDiastolic;
    private volatile Integer latestMap;
    private volatile Integer latestBoPercent;
    private volatile Integer latestPulseRate;
    private volatile Double latestTemp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        logText = findViewById(R.id.logText);
        logText.setMovementMethod(new ScrollingMovementMethod());
        Button scanButton = findViewById(R.id.scanButton);
        Button disconnectButton = findViewById(R.id.disconnectButton);
        ListView deviceList = findViewById(R.id.deviceList);
        TextView emptyView = findViewById(R.id.emptyView);
        deviceList.setEmptyView(emptyView);

        appendLog("应用已启动，等待操作");

        // 适配器用于展示附近扫描到的蓝牙设备
        deviceAdapter = new ArrayAdapter<BluetoothDevice>(this, android.R.layout.simple_list_item_2, android.R.id.text1, nearbyDevices) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView nameView = view.findViewById(android.R.id.text1);
                TextView addressView = view.findViewById(android.R.id.text2);
                BluetoothDevice device = getItem(position);
                if (device != null) {
                    String name = device.getName();
                    nameView.setText(name != null ? name : getString(R.string.device_unknown));
                    addressView.setText(device.getAddress());
                }
                return view;
            }
        };
        deviceList.setAdapter(deviceAdapter);
        deviceList.setOnItemClickListener((parent, view, position, id) -> {
            BluetoothDevice device = nearbyDevices.get(position);
            statusText.setText(getString(R.string.status_connecting, displayName(device)));
            appendLog(getString(R.string.status_connecting, displayName(device)));
            bleManager.connectTo(device);
        });

    bleManager = new BleManager(this, new BleManager.Listener() {
            @Override
            public void onStatus(String message) {
                statusText.setText(message);
                appendLog(message);
            }

            @Override
            public void onConnected(@NonNull android.bluetooth.BluetoothDevice device) {
                String label = device.getName() != null ? device.getName() : device.getAddress();
                statusText.setText(getString(R.string.status_connected, label));
                appendLog(getString(R.string.status_connected, label));
                // 建立 WebSocket：后端接口格式 ws://host:port/data/{deviceId}（使用设备真实 ID，不再编码）
                String deviceId = device.getAddress();
                String baseWs = "ws://10.242.20.72:8080"; // 修改为你的网关地址
                wsClient = new DataWebSocketClient(baseWs, deviceId, new DataWebSocketClient.Listener() {
                    @Override public void onLog(String line) { appendLog(line); }
                    @Override public void onConnected() { appendLog("STOMP 已连接并订阅响应通道"); }
                    @Override public void onDisconnected() { appendLog("WebSocket 已断开"); }
                    @Override public void onError(String error) { appendLog(error); }
                });
                wsClient.connect();
                currentDeviceId = deviceId;
                startStreaming();
            }

            @Override
            public void onDisconnected() {
                statusText.setText(R.string.status_disconnected);
                appendLog(getString(R.string.status_disconnected));
                if (wsClient != null) {
                    wsClient.shutdown();
                    wsClient = null;
                }
                stopStreaming();
            }

            @Override
            public void onVitals(@NonNull android.bluetooth.BluetoothDevice device, @NonNull VitalsReading reading) {
                // 只更新“最新值缓存”，真正的上传由 250Hz 调度器统一完成
                updateLatestValues(reading);
            }

            @Override
            public void onError(String error) {
                statusText.setText(error);
                Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show();
                appendLog(error);
            }

            @Override
            public void onDeviceFound(@NonNull BluetoothDevice device) {
                addDevice(device);
            }
        });

        if (!TARGET_NAME_PREFIX.isEmpty()) {
            bleManager.setTargetNamePrefix(TARGET_NAME_PREFIX);
        }
        if (!TARGET_DEVICE_ADDRESS.isEmpty()) {
            bleManager.setTargetAddress(TARGET_DEVICE_ADDRESS);
        }

        scanButton.setOnClickListener(v -> {
            nearbyDevices.clear();
            deviceAdapter.notifyDataSetChanged();
            statusText.setText(R.string.status_scanning);
            appendLog(getString(R.string.status_scanning));
            if (hasAllPermissions()) {
                bleManager.startScan();
            } else {
                requestBlePermissions();
            }
        });

        disconnectButton.setOnClickListener(v -> {
            bleManager.disconnect();
            statusText.setText(R.string.status_disconnected);
            appendLog(getString(R.string.status_disconnected));
        });
    }

    // 更新最新值缓存
    private void updateLatestValues(VitalsReading reading) {
        if (reading.ecgWave != null) latestEcgWave = reading.ecgWave;
        if (reading.respWave != null) latestRespWave = reading.respWave;
        if (reading.spo2Waveform != null && !reading.spo2Waveform.isEmpty()) {
            latestBoWave = reading.spo2Waveform.get(reading.spo2Waveform.size() - 1);
        }
        if (reading.ecgHeartRate != null) latestEcgHr = reading.ecgHeartRate;
        if (reading.respirationRate != null) latestRespRate = reading.respirationRate;
        if (reading.systolic != null) latestSystolic = reading.systolic;
        if (reading.diastolic != null) latestDiastolic = reading.diastolic;
        if (reading.meanArterialPressure != null) latestMap = reading.meanArterialPressure;
        if (reading.bloodOxygen != null) latestBoPercent = reading.bloodOxygen;
        if (reading.pulseRate != null) latestPulseRate = reading.pulseRate;
        if (reading.temperature != null) latestTemp = reading.temperature;
    }

    private void startStreaming() {
        stopStreaming();
        streamExecutor = Executors.newSingleThreadScheduledExecutor();
        streamTask = streamExecutor.scheduleAtFixedRate(() -> {
            // 以固定 4ms 周期发送：复用低频字段的最近值
            String userId = TARGET_USER_ID.isEmpty() ? DEFAULT_USER_ID : TARGET_USER_ID;
            String deviceId = currentDeviceId;
            if (deviceId == null || wsClient == null) return;
            VitalsReading snapshot = new VitalsReading(
                    System.currentTimeMillis(),
                    latestEcgWave,
                    latestEcgHr,
                    latestRespRate,
                    latestSystolic,
                    latestDiastolic,
                    latestMap,
                    latestBoPercent,
                    latestPulseRate,
                    latestTemp,
                    null,
                    latestRespWave
            );
            Integer spo2Point = latestBoWave; // 若低频未更新，则保留上次值
            JSONObject payload = PayloadFactory.buildPayload(snapshot, userId, deviceId, spo2Point);
            wsClient.send(payload.toString());
        }, 0, 4, TimeUnit.MILLISECONDS);
        appendLog("开始 250Hz 流式发送（4ms 周期）");
    }

    private void stopStreaming() {
        if (streamTask != null) {
            try { streamTask.cancel(false); } catch (Exception ignored) {}
            streamTask = null;
        }
        if (streamExecutor != null) {
            try { streamExecutor.shutdownNow(); } catch (Exception ignored) {}
            streamExecutor = null;
        }
    }

    private boolean hasAllPermissions() {
        for (String permission : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestBlePermissions() {
        String[] permissions = requiredPermissions();
        List<String> toRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(permission);
            }
        }
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
            appendLog("请求蓝牙相关权限");
        }
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else {
            return new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (hasAllPermissions()) {
                bleManager.startScan();
                appendLog("权限已授予，继续扫描设备");
            } else {
                Toast.makeText(this, R.string.toast_permission_denied, Toast.LENGTH_SHORT).show();
                appendLog(getString(R.string.toast_permission_denied));
            }
        }
    }

    @Override
    protected void onDestroy() {
        bleManager.shutdown();
        networkExecutor.shutdownNow();
        stopStreaming();
        if (wsClient != null) { wsClient.shutdown(); }
        super.onDestroy();
    }

    private void addDevice(@NonNull BluetoothDevice device) {
        if (wsClient != null) wsClient.shutdown();
        for (BluetoothDevice existing : nearbyDevices) {
            if (existing.getAddress().equals(device.getAddress())) {
                return;
            }
        }
        nearbyDevices.add(device);
        deviceAdapter.notifyDataSetChanged();
        statusText.setText(getString(R.string.status_device_found, displayName(device)));
        appendLog(getString(R.string.status_device_found, displayName(device)));
    }

    private String displayName(@NonNull BluetoothDevice device) {
        String name = device.getName();
        return name != null ? name : device.getAddress();
    }

    private void appendLog(String message) {
        mainHandler.post(() -> {
            if (logText == null) {
                return;
            }
            String line = "[" + logTimeFormat.format(new Date()) + "] " + message;
            logBuffer.append(line).append('\n');
            final int maxLength = 10_000;
            if (logBuffer.length() > maxLength) {
                int excess = logBuffer.length() - maxLength;
                int cutIndex = logBuffer.indexOf("\n", excess);
                if (cutIndex >= 0) {
                    logBuffer.delete(0, cutIndex + 1);
                } else {
                    logBuffer.delete(0, excess);
                }
            }
            logText.setText(logBuffer.toString());
            logText.post(() -> {
                if (logText.getLayout() == null) {
                    return;
                }
                int scrollAmount = logText.getLayout().getLineTop(logText.getLineCount()) - logText.getHeight();
                logText.scrollTo(0, Math.max(scrollAmount, 0));
            });
        });
    }
}