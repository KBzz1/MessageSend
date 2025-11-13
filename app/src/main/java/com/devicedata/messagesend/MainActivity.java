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
import com.devicedata.messagesend.NetworkClient;
import com.devicedata.messagesend.ble.BleManager;
import com.devicedata.messagesend.model.VitalsReading;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用主界面：负责蓝牙设备扫描、手动选择连接，以及定时上传生命体征数据。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_PERMISSIONS = 100;
    private static final long UPLOAD_INTERVAL_MS = 2_000L;
    private static final String TARGET_NAME_PREFIX = "";
    private static final String TARGET_DEVICE_ADDRESS = "34:81:F4:75:20:70";
    private static final String DEFAULT_USER_ID = "demo-001";
    private static final String TARGET_USER_ID = "";

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BleManager bleManager;
    private TextView statusText;
    private TextView logText;
    private long lastUploadAt;
    private ArrayAdapter<BluetoothDevice> deviceAdapter;
    private final List<BluetoothDevice> nearbyDevices = new ArrayList<>();
    private final List<Integer> pendingSpo2Wave = new ArrayList<>();
    private final StringBuilder logBuffer = new StringBuilder();
    private final SimpleDateFormat logTimeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

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
            }

            @Override
            public void onDisconnected() {
                statusText.setText(R.string.status_disconnected);
                appendLog(getString(R.string.status_disconnected));
            }

            @Override
            public void onVitals(@NonNull android.bluetooth.BluetoothDevice device, @NonNull VitalsReading reading) {
                sendVitals(device, reading);
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

    private void sendVitals(android.bluetooth.BluetoothDevice device, VitalsReading reading) {
        // 收集血氧波形数据，等待下一次批量上传
        if (reading.spo2Waveform != null && !reading.spo2Waveform.isEmpty()) {
            pendingSpo2Wave.addAll(reading.spo2Waveform);
        }
        long now = System.currentTimeMillis();
        if (now - lastUploadAt < UPLOAD_INTERVAL_MS) {
            return;
        }
        lastUploadAt = now;
    statusText.setText(R.string.status_uploading);
    final List<Integer> waveformBatch = pendingSpo2Wave.isEmpty()
                ? Collections.emptyList()
                : new ArrayList<>(pendingSpo2Wave);
    appendLog("准备上传生命体征数据，血氧样本数：" + waveformBatch.size());
        pendingSpo2Wave.clear();
        networkExecutor.execute(() -> {
            String userId = TARGET_USER_ID.isEmpty() ? DEFAULT_USER_ID : TARGET_USER_ID;
            String deviceId = device.getAddress();
            JSONObject payload = PayloadFactory.buildPayload(reading, userId, deviceId, waveformBatch); // 构造最终上行 JSON 数据
            NetworkClient.Result result = NetworkClient.postJson(NetworkClient.SERVER_URL, payload);
            mainHandler.post(() -> {
                if (result.success) {
                    statusText.setText(getString(R.string.status_upload_success, result.message));
                    appendLog(result.message);
                } else {
                    statusText.setText(getString(R.string.status_upload_fail, result.message));
                    Toast.makeText(MainActivity.this, R.string.toast_upload_failed, Toast.LENGTH_SHORT).show();
                    appendLog(result.message);
                    if (!waveformBatch.isEmpty()) {
                        pendingSpo2Wave.addAll(0, waveformBatch);
                    }
                }
            });
        });
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
        super.onDestroy();
    }

    private void addDevice(@NonNull BluetoothDevice device) {
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