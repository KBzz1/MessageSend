# MessageSend 项目迁移指南（精简版，WebSocket-only）

本文档说明如何将 MessageSend 项目的 BLE 采集与上报逻辑迁移到其他 Android 应用；当前网络通道为 HTTP（不含 Retrofit、MQTT；使用 OkHttp 直发）。

## 1. 代码文件

将以下 Java 类复制到目标项目中，并保持包结构一致（或按需调整 import 语句）：

- `app/src/main/java/com/devicedata/messagesend/MainActivity.java`
- `app/src/main/java/com/devicedata/messagesend/ble/BleManager.java`
- `app/src/main/java/com/devicedata/messagesend/ble/VitalsAggregator.java`
- `app/src/main/java/com/devicedata/messagesend/model/VitalsReading.java`
- `app/src/main/java/com/devicedata/messagesend/PayloadFactory.java`
- `app/src/main/java/com/devicedata/messagesend/DataHttpClient.java`

如需重命名包名，请同步修改所有引用路径。

## 2. AndroidManifest 权限与设置

在目标应用的 `AndroidManifest.xml` 中，确保包含以下权限与特性：

```xml
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />

<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<!-- Android 12+ 需额外申请 -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

确保在 Manifest 中注册承载该逻辑的 `activity`，并配置成你自身应用的界面结构。例如可将逻辑集成到自有 Activity 中，再在 Manifest 中声明该 Activity。

## 3. Gradle 依赖（HTTP Only）

在模块级 `build.gradle.kts` 中，保留以下依赖即可：

```kts
dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.okhttp) // 用于 HTTP 直发
}
```

确认 `minSdk`、`targetSdk` 与 `compileSdk` 符合 BLE 要求。

## 4. 网关与目标设备配置

- HTTP 端点：`http://10.242.20.72:8080/data/{deviceId}`（POST JSON）。在 `MainActivity` 中可修改 `baseHttp`。
- 设备 ID：使用蓝牙 MAC 地址作为 `{deviceId}`，直接使用设备真实 ID（不再做 URL 编码）。
- 目标蓝牙设备：在 `MainActivity` 的 `TARGET_DEVICE_ADDRESS` 设置固定 MAC；如需按名称过滤，设置 `TARGET_NAME_PREFIX`。

## 5. 权限申请

`MainActivity` 中的 `requiredPermissions()` 方法已经根据 Android 版本自动申请 `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT` 与定位权限。如目标项目使用不同的权限模型（例如自定义弹窗、权限代理库等），请同步调整。


## 6. 调试与验证

1. 在目标项目中运行应用，确认 UI、权限申请与扫描流程正常。
2. 已连接蓝牙设备后，监测 `statusText` 文本或日志输出验证连接状态。
3. 连接后，观察 WS 日志：出现“WS 已连接”表示通道就绪；出现“WS -> (xxx bytes)”表示数据已发送。

### 界面布局与调试体验

- 顶部状态区采用卡片式面板，便于快速查看连接状态并一键扫描/断开。
- 按钮美化：`scanButton` 与 `disconnectButton` 使用圆角涟漪背景（`button_primary.xml` / `button_secondary.xml`），高度固定 48dp，提升可点击面积与视觉层次。
- 设备列表与日志面板均为圆角描边卡片（`panel_background.xml`），整体使用柔和浅色底（`surface_background`）。
- 日志区权重提高（deviceList 0.9 : logContainer 1.8），深色背景 + 等宽字体，保证大量滚动日志仍清晰易读；颜色在 `colors.xml` (`log_background`, `log_text`, `log_timestamp`)。
- 可调节项：
    1. 修改圆角：编辑 `panel_background.xml` / 按钮 drawable 的 `<corners android:radius>`。
    2. 修改按钮配色：更新 `primary_accent` / `secondary_accent`。
    3. 调整高度或权重：在 `activity_main.xml` 中调整按钮 `layout_height` 与容器 `layout_weight`。
    4. 日志字体/大小：修改 `logText` 的 `android:textSize` 或 `fontFamily`。


### 数据发送策略（250Hz 单点流）

- 发送频率：固定 250Hz（每 4ms 一帧），通过调度器统一节拍发送。
- 高频波形：血氧波形以单点字段 `boWave` 发送；ECG/呼吸波形若设备有上报，也会以最新点随帧发送（`ecg`/`respWave`）。
- 低频字段：心率、血压、体温、血氧饱和度等低于 250Hz 的指标，按最近值在每一帧重复发送；当设备上报变更时自动更新。
- 传输：通过 HTTP POST 发送每一帧 JSON；高频下对服务端与网络压力较大，请谨慎评估并考虑后端限流/聚合。
- 兼容性：不再使用 `boWaveSamples` 数组字段；服务端需按单点解析。


## 7. 其他注意事项

- 如项目使用 Kotlin，请将 Java 类迁移时确保兼容性或转换为 Kotlin。
- 若使用混淆（ProGuard/R8），请保留核心类与字段；当前 JSON 构造基于 `org.json`，无需额外序列化库。
- 检查 `gradle.properties` 与 `settings.gradle.kts`，确保项目结构正确导入。

按照以上步骤逐项迁移，可完整复制 MessageSend 的核心逻辑到其他应用中。