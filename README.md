# GNSS Data Logger

面向自动化长时间测试的 Android GNSS 数据采集应用。应用在后台通过前台服务持续监听位置、`GnssStatus`、NMEA 与 Raw GNSS Measurements，将每个采集会话写入多份 CSV，并支持通过 UI、**adb broadcast** 或应用内广播更新当前测试场景名称。

## 功能概述

- 前台服务（`location` 类型）保障后台采集。
- `LocationManager` + `LocationListener` 获取经纬度、海拔、精度、速度与方位。
- `GnssStatus.Callback` 获取可见卫星、参与定位卫星、星座、SVID、C/N0、方位角、仰角等。
- `OnNmeaMessageListener` 记录原始 NMEA 句子。
- `GnssMeasurementsEvent.Callback` 记录 Raw GNSS Measurements。
- 实时 `flush` 的多 CSV 写入，降低异常退出时的数据丢失。
- 广播：`ACTION_START_LOGGING` / `ACTION_STOP_LOGGING` / `ACTION_UPDATE_SCENE`。
- 主界面展示状态、场景、文件路径、卫星表格、写入计数、导出入口与配置项。

## 权限说明

| 权限 | 用途 |
|------|------|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 卫星状态与位置 |
| `ACCESS_BACKGROUND_LOCATION` | 长时间后台采集（建议「始终允许」） |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` | 前台服务（含定位类型，Android 10+） |
| `POST_NOTIFICATIONS` | 通知栏展示采集状态（Android 13+） |
| `WRITE_EXTERNAL_STORAGE` | 仅 `maxSdkVersion=28` 声明，兼容极老存储习惯；实际写入以应用专属目录为主 |

## 手动使用

1. 安装 APK 后打开应用，授予通知（若系统要求）、定位及（建议）后台定位。
2. 设置文件名前缀、测试场景标签、是否按日期建子目录，以及是否记录 NMEA / Raw GNSS。
3. 点击 **开始采集**：出现前台通知后开始写 CSV；可退回桌面或锁屏继续采集。
4. 采集中可随时修改测试场景标签，后续 CSV 行会写入新的 `scene_name`。
5. 点击 **停止采集**：关闭写入并结束前台服务；下次开始会生成新的 `session_id` 与新 CSV 文件。
6. **打开保存目录** 可查看所有会话文件；**导出 CSV/KML** 可分享当前会话的 CSV 与 KML 轨迹文件。

CSV 默认目录（与 Android 应用专属外部目录一致）：

`/sdcard/Android/data/com.example.gnsslogger/files/gnss/`（可按配置再分子目录 `YYYYMMDD/`）

## adb 控制

以下命令与代码中的 `action` 完全一致。若隐式广播在部分机型上不稳定，请使用 **显式组件** 或 **`-p` 包名** 形式。

### 推荐：显式指定 Receiver

```text
adb shell am broadcast -n com.example.gnsslogger/com.example.gnsslogger.GnssCommandReceiver -a com.example.gnsslogger.ACTION_START_LOGGING
```

```text
adb shell am broadcast -n com.example.gnsslogger/com.example.gnsslogger.GnssCommandReceiver -a com.example.gnsslogger.ACTION_STOP_LOGGING
```

```text
adb shell am broadcast -n com.example.gnsslogger/com.example.gnsslogger.GnssCommandReceiver -a com.example.gnsslogger.ACTION_UPDATE_SCENE --es scene_name "stopwatch_scene"
```

### 备选：限定包名的隐式广播

```text
adb shell am broadcast -p com.example.gnsslogger -a com.example.gnsslogger.ACTION_START_LOGGING
```

```text
adb shell am broadcast -p com.example.gnsslogger -a com.example.gnsslogger.ACTION_STOP_LOGGING
```

```text
adb shell am broadcast -p com.example.gnsslogger -a com.example.gnsslogger.ACTION_UPDATE_SCENE --es scene_name "white_screen_scene"
```

### 场景名称示例

```text
adb shell am broadcast -n com.example.gnsslogger/com.example.gnsslogger.GnssCommandReceiver -a com.example.gnsslogger.ACTION_UPDATE_SCENE --es scene_name "video_play_scene"
```

`scene_name` 为空或缺失时，应用会写入 `unknown_scene`。

每个会话默认生成：

- `*_satellites.csv`：定位 + 卫星状态。
- `*_location.csv`：按定位回调记录的经纬度、海拔、速度、精度与时间戳。
- `*_raw.csv`：Raw GNSS Measurements（开启 Raw 记录时生成）。
- `*_nmea.csv`：NMEA 原始句子（开启 NMEA 记录时生成）。
- `*_track.kml`：基于 `*_location.csv` 生成的 Google Earth Pro 轨迹文件。

停止采集时，App 会自动根据 `*_location.csv` 生成同名前缀的 `*_track.kml`。例如：

- `session_20260513_153000_location.csv`
- `session_20260513_153000_track.kml`

点击 **导出 CSV/KML** 时会同时分享 CSV 与 `*_track.kml`；如果 KML 文件不存在，App 会尝试根据 `*_location.csv` 重新生成。`*_track.kml` 可直接用 Google Earth Pro 打开，导入方式为：**文件 -> 打开 -> 选择 `*_track.kml`**。

## CSV 字段说明

### `*_satellites.csv`

`timestamp_ms,timestamp_iso,elapsed_realtime_nanos,device_model,android_version,package_version,session_id,scene_name,provider,latitude,longitude,altitude,accuracy,speed,bearing,satellite_count,used_in_fix_count,constellation_type,constellation_name,svid,cn0_dbhz,elevation_deg,azimuth_deg,used_in_fix,carrier_frequency_hz,baseband_cn0_dbhz,has_almanac,has_ephemeris`

- 同一帧 `GnssStatus` 回调内所有卫星共享相同的 `timestamp_ms` / `timestamp_iso`。
- 无定位点时经纬度等可为空。
- `constellation_name`：`GPS` / `SBAS` / `GLONASS` / `QZSS` / `BEIDOU` / `GALILEO` / `IRNSS` / `UNKNOWN`。
- `carrier_frequency_hz`：Android 8+（API 26）起在硬件支持时填写。
- `baseband_cn0_dbhz`：Android 11（API 30）起在支持时填写。

### `*_raw.csv`

`timestamp_ms,timestamp_iso,elapsed_realtime_nanos,device_model,android_version,package_version,session_id,scene_name,clock_time_nanos,clock_full_bias_nanos,clock_bias_nanos,clock_bias_uncertainty_nanos,clock_drift_nanos_per_second,clock_drift_uncertainty_nanos_per_second,hardware_clock_discontinuity_count,constellation_type,constellation_name,svid,time_offset_nanos,state,received_sv_time_nanos,received_sv_time_uncertainty_nanos,cn0_dbhz,pseudorange_rate_mps,pseudorange_rate_uncertainty_mps,accumulated_delta_range_state,accumulated_delta_range_m,accumulated_delta_range_uncertainty_m,carrier_frequency_hz,baseband_cn0_dbhz,automatic_gain_control_db,snr_db,multipath_indicator`

- 同一帧 Raw GNSS 回调内所有 measurement 共享相同的 `clock_*` 字段。
- 设备或芯片不支持的字段为空。

### `*_nmea.csv`

`timestamp_ms,timestamp_iso,elapsed_realtime_nanos,device_model,android_version,package_version,session_id,scene_name,nmea_timestamp_ms,message`

- `message` 保留原始 NMEA 句子内容。
- `nmea_timestamp_ms` 来自 Android NMEA 回调。

## 与自动化测试平台集成

1. 安装固定包名 `com.example.gnsslogger` 的构建产物。
2. 首次建议通过 UI 完成权限授权；无人值守可在支持设备上使用 `adb shell pm grant` 授予运行时权限（视 ROM 策略而定）。
3. 用 **开始/停止** 广播控制采集生命周期；在切换用例时发送 **更新场景** 广播，后续 CSV 行中的 `scene_name` 即切换为新值。
4. 拉取 `Android/data/com.example.gnsslogger/files/gnss/` 下 CSV 与自动化框架对齐时间轴与用例 ID。
5. 也可以通过 App 内 **导出本次 CSV** 分享当前会话文件。

## CI 与发布

仓库包含 GitHub Actions workflow：

- 向任意分支提交或打开 PR 时，自动执行 `lintDebug`、`testDebugUnitTest`、`assembleDebug`、`assembleRelease`。
- 推送 tag 时，自动创建 GitHub Release，并上传构建出的 APK。
- 若未配置签名密钥，release 包会以 `app-release-unsigned.apk` 形式上传；debug 包始终可用于内部安装验证。

如需生成已签名 release APK，请在 GitHub 仓库的 **Settings > Secrets and variables > Actions** 中添加：

| Secret | 说明 |
|------|------|
| `ANDROID_KEYSTORE_BASE64` | release keystore 文件的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key 密码 |

创建 tag 并触发发布：

```bash
git tag v1.0.0
git push origin v1.0.0
```

## 工程与构建

- Kotlin + Android Gradle Plugin 8.x，`minSdk` 26，`targetSdk` 35。
- 编译：`./gradlew :app:assembleDebug`（Windows 使用 `gradlew.bat`）。
