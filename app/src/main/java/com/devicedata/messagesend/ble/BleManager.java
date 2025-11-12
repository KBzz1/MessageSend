package com.devicedata.messagesend.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.devicedata.messagesend.R;
import com.devicedata.messagesend.model.VitalsReading;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 封装 BLE 连接流程：扫描、配对、订阅特征以及将数据事件回调给 UI。
 */
public class BleManager {

    private static final String TAG = "BleManager";
    private static final long SCAN_TIMEOUT = 10_000L;
    private static final UUID SERVICE_UUID = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455");
    private static final UUID READ_UUID = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616");
    private static final UUID CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public interface Listener {
        void onStatus(String message);

        void onConnected(@NonNull BluetoothDevice device);

        void onDisconnected();

        void onVitals(@NonNull BluetoothDevice device, @NonNull VitalsReading reading);

        void onError(String error);

        void onDeviceFound(@NonNull BluetoothDevice device);
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final VitalsAggregator aggregator = new VitalsAggregator();
    private final ByteArrayOutputStream recvBuffer = new ByteArrayOutputStream();

    @Nullable
    private final BluetoothAdapter adapter;
    @Nullable
    private BluetoothLeScanner scanner;
    @Nullable
    private BluetoothGatt bluetoothGatt;
    @Nullable
    private BluetoothDevice currentDevice;
    @Nullable
    private String targetNamePrefix;
    @Nullable
    private String targetAddress;

    private boolean scanning;

    public BleManager(@NonNull Context context, @NonNull Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = manager != null ? manager.getAdapter() : BluetoothAdapter.getDefaultAdapter();
    }

    public void startScan() {
        if (adapter == null) {
            emitError(context.getString(R.string.status_bluetooth_unavailable));
            return;
        }
        if (!adapter.isEnabled()) {
            emitError(context.getString(R.string.status_bluetooth_disabled));
            return;
        }
        if (scanning) {
            emitStatus(context.getString(R.string.status_scanning_in_progress));
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            emitError(context.getString(R.string.status_ble_scanner_unavailable));
            return;
        }
        emitStatus(context.getString(R.string.status_scanning));
        scanning = true;
        scanner.startScan(scanCallback);
        mainHandler.postDelayed(this::stopScan, SCAN_TIMEOUT);
    }

    public void setTargetNamePrefix(@Nullable String prefix) {
        targetNamePrefix = prefix;
    }

    public void setTargetAddress(@Nullable String address) {
        targetAddress = address != null ? address.toUpperCase() : null;
    }

    public void disconnect() {
        stopScan();
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        currentDevice = null;
        recvBuffer.reset();
    }

    public void shutdown() {
        disconnect();
    }

    private void stopScan() {
        if (!scanning) {
            return;
        }
        if (scanner != null) {
            scanner.stopScan(scanCallback);
        }
        scanning = false;
        emitStatus(context.getString(R.string.status_scan_stopped));
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) {
                return;
            }
            if (targetAddress != null && !targetAddress.equalsIgnoreCase(device.getAddress())) {
                emitStatus(context.getString(R.string.status_ignoring_device, safeName(device)));
                return;
            }
            if (targetNamePrefix != null) {
                String name = device.getName();
                if (name == null || !name.startsWith(targetNamePrefix)) {
                    emitStatus(context.getString(R.string.status_ignoring_device, safeName(device)));
                    return;
                }
            }
            emitDeviceFound(device);
            emitStatus(context.getString(R.string.status_device_found, safeName(device)));
            if (targetAddress != null || targetNamePrefix != null) {
                stopScan();
                connect(device);
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (!results.isEmpty()) {
                onScanResult(0, results.get(0));
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            emitError(context.getString(R.string.status_scan_failed, errorCode));
            stopScan();
        }
    };

    public void connectTo(@NonNull BluetoothDevice device) {
        stopScan();
        // 主动触发配对请求，确保连接前完成绑定
        if (device.getBondState() == BluetoothDevice.BOND_NONE) {
            device.createBond();
            emitStatus(context.getString(R.string.status_request_bond, safeName(device)));
        }
        // 立即发起 GATT 连接
        connect(device);
    }

    private void connect(BluetoothDevice device) {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        emitStatus(context.getString(R.string.status_connecting, safeName(device)));
        currentDevice = device;
        recvBuffer.reset();
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitError(context.getString(R.string.status_gatt_error, status));
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                emitStatus(context.getString(R.string.status_discovering_services));
                if (!gatt.discoverServices()) {
                    emitError(context.getString(R.string.status_service_discovery_failed, BluetoothGatt.GATT_FAILURE));
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                emitStatus(context.getString(R.string.status_disconnected));
                emitDisconnected();
                disconnect();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitError(context.getString(R.string.status_service_discovery_failed, status));
                return;
            }
            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                emitError(context.getString(R.string.status_service_missing));
                return;
            }
            BluetoothGattCharacteristic notifyCharacteristic = service.getCharacteristic(READ_UUID);
            if (notifyCharacteristic == null) {
                emitError(context.getString(R.string.status_characteristic_missing));
                return;
            }
            boolean enabled = gatt.setCharacteristicNotification(notifyCharacteristic, true);
            if (!enabled) {
                emitError(context.getString(R.string.status_enable_notification_failed));
                return;
            }
            for (BluetoothGattDescriptor descriptor : notifyCharacteristic.getDescriptors()) {
                if (descriptor == null) {
                    continue;
                }
                if (CLIENT_CONFIG_UUID.equals(descriptor.getUuid())) {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(descriptor);
                }
            }
            if (currentDevice != null) {
                emitConnected(currentDevice);
            }
            emitStatus(context.getString(R.string.status_notifications_enabled));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            processIncoming(data);
        }
    };

    private void processIncoming(@Nullable byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        for (byte b : data) {
            recvBuffer.write(b);
            byte[] buf = recvBuffer.toByteArray();
            if (buf.length == 1 && (buf[0] & 0xFF) != 0x55) {
                recvBuffer.reset();
                continue;
            }
            if (buf.length == 2 && (buf[1] & 0xFF) != 0xAA) {
                recvBuffer.reset();
                continue;
            }
            if (buf.length >= 3) {
                int len = buf[2] & 0xFF;
                int fullLen = len + 2;
                if (buf.length == fullLen) {
                    handlePacket(buf);
                    recvBuffer.reset();
                } else if (buf.length > fullLen) {
                    recvBuffer.reset();
                }
            }
        }
    }

    private void handlePacket(byte[] packet) {
        if (packet.length < 4) {
            return;
        }
        int len = packet[2] & 0xFF;
        if (packet.length != len + 2) {
            return;
        }
        byte[] body = Arrays.copyOfRange(packet, 3, packet.length - 1);
        if (body.length == 0) {
            return;
        }
        byte checksumRecv = packet[packet.length - 1];
        int sum = len;
        for (byte b : body) {
            sum += (b & 0xFF);
        }
        byte checksumCalc = (byte) ~sum;
        if (checksumCalc != checksumRecv) {
            Log.w(TAG, "Checksum mismatch");
            return;
        }
        int type = body[0] & 0xFF;
        VitalsReading reading = aggregator.update(type, body);
        if (reading != null && !reading.isWaveformOnly()) {
            emitVitals(reading);
        }
    }

    private void emitStatus(String message) {
        mainHandler.post(() -> listener.onStatus(message));
    }

    private void emitError(String message) {
        Log.e(TAG, message);
        mainHandler.post(() -> listener.onError(message));
    }

    private void emitVitals(VitalsReading reading) {
        BluetoothDevice device = currentDevice;
        if (device == null) {
            return;
        }
        mainHandler.post(() -> listener.onVitals(device, reading));
    }

    private void emitConnected(BluetoothDevice device) {
        mainHandler.post(() -> listener.onConnected(device));
    }

    private void emitDisconnected() {
        mainHandler.post(listener::onDisconnected);
    }

    private void emitDeviceFound(BluetoothDevice device) {
        mainHandler.post(() -> listener.onDeviceFound(device));
    }

    private static String safeName(BluetoothDevice device) {
        String name = device.getName();
        return name != null ? name : device.getAddress();
    }
}
