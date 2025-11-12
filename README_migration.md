# MessageSend 项目迁移指南

本文档说明如何将 MessageSend 项目中的蓝牙采集与上传逻辑迁移到其他 Android 应用，确保功能与配置完整无缺。

## 1. 代码文件

将以下 Java 类复制到目标项目中，并保持包结构一致（或按需调整 import 语句）：

- `app/src/main/java/com/devicedata/messagesend/MainActivity.java`
- `app/src/main/java/com/devicedata/messagesend/ble/BleManager.java`
- `app/src/main/java/com/devicedata/messagesend/ble/VitalsAggregator.java`
- `app/src/main/java/com/devicedata/messagesend/model/VitalsReading.java`
- `app/src/main/java/com/devicedata/messagesend/NetworkClient.java`
- `app/src/main/java/com/devicedata/messagesend/PayloadFactory.java`

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

## 3. Gradle 配置

在模块级 `build.gradle.kts`（或 `build.gradle`）中，确认包含以下依赖（通常 Android 项目默认已含）：

```kts
dependencies {
    implementation("androidx.appcompat:appcompat:<最新版本>")
    implementation("com.google.android.material:material:<最新版本>")
    implementation("androidx.core:core-ktx:<最新版本>")
    // 网络库：使用 HttpURLConnection 无需额外依赖；如改用 OkHttp，需要手动添加。
}
```

同样确认启用了 `minSdk`、`targetSdk` 与 `compileSdk` 版本支持 BLE。

## 4. 服务器与目标设备配置

- **服务器地址**：在 `NetworkClient.SERVER_URL` 修改为实际后端接口，例如 `http://10.242.20.72:8080/surgery`。
- **蓝牙目标设备**：在 `MainActivity` 静态常量 `TARGET_DEVICE_ADDRESS` 设置目标设备 MAC 地址。（目前不用管，只使用那一台生理参数仪）
- 如需针对特定名称过滤，调整 `TARGET_NAME_PREFIX`。

## 5. 权限申请

`MainActivity` 中的 `requiredPermissions()` 方法已经根据 Android 版本自动申请 `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT` 与定位权限。如目标项目使用不同的权限模型（例如自定义弹窗、权限代理库等），请同步调整。


## 7. 调试与验证

1. 在目标项目中运行应用，确认 UI、权限申请与扫描流程正常。
2. 已连接蓝牙设备后，监测 `statusText` 文本或日志输出验证连接状态。
3. 使用 `server.py` 可验证 JSON 数据格式，或者发到 *康迪* 的后端验证（`http://10.242.20.72:8080/surgery`）。

## 8. 其他注意事项

- 如项目使用 Kotlin，请将 Java 类迁移时确保兼容性或转换为 Kotlin。
- 若使用混淆（ProGuard/R8），请添加规则保留关键类或字段，避免 JSON 序列化失败。
- 检查 `gradle.properties` 与 `settings.gradle.kts`，确保项目结构正确导入。

按照以上步骤逐项迁移，可完整复制 MessageSend 的核心逻辑到其他应用中。