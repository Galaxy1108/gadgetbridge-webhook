# Webhook 上传模块（本 fork 的改动说明）

本 fork 在 Gadgetbridge 中加入了一个自包含的 Webhook 上传模块：把健康数据
（步数 / 心率 / 睡眠 / 电量）通过 HTTPS 主动上传到你自己服务器上的
[AstrBot 插件](https://github.com/Galaxy1108/astrbot_plugin_health_monitor)。

## 新增文件（全部自包含，git merge 自动合并）

| 文件 | 作用 |
|---|---|
| `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/webhook/WebhookConfig.kt` | 配置读写（走 GB 统一 SharedPreferences，键前缀 `webhook_`） |
| `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/webhook/WebhookUploader.kt` | 核心：读库 → JSON → POST；每设备游标，服务端确认才推进 |
| `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/webhook/WebhookWorker.kt` | WorkManager Worker |
| `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/webhook/WebhookScheduler.kt` | 周期调度 + 同步完成立即触发（2 分钟限频） |
| `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/webhook/WebhookSettingsActivity.kt` | 设置界面（Activity + Fragment） |
| `app/src/main/res/xml/webhook_settings.xml` | 设置界面布局 |
| `WEBHOOK_MODULE.md` | 本文档 |

## 对上游文件的改动（6 处钩子点，合并时注意）

1. `app/src/main/AndroidManifest.xml`
   - INTERNET 权限：上游 `tools:node="remove"` 改为保留（本模块需要直连 HTTPS）。
     注意：这会让本 fork 的行为与官方 F-Droid 版不同（官方通过 Internet Helper 中转）。
   - 新增 `WebhookSettingsActivity` 声明（`exported=false`）。
2. `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/GBApplication.java`
   - `onCreate()` 中 `PeriodicZipExporter` 调度后加一行 `WebhookScheduler.INSTANCE.schedule(context);`
3. `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/GB.java`
   - `signalActivityDataFinish()` 中加一行 `WebhookScheduler.INSTANCE.scheduleImmediate(GBApplication.getContext());`
     （小米 / 华米 / Casio / Garmin 等同步完成都会走到此函数 → 近实时上传）
4. `app/src/main/res/xml/automations_settings.xml`
   - 新增 `pref_header_webhook` 分类，含指向 `WebhookSettingsActivity` 的入口。
5. `app/src/main/res/values/strings.xml` 与 `values-zh-rCN/strings.xml`
   - 追加 `webhook_*`、`pref_header_webhook` 字符串（纯增量）。
6. `app/build.gradle` — mainline flavor 增加 `applicationIdSuffix ".webhook"`
   （安装包名 = `nodomain.freeyourgadget.gadgetbridge.webhook`：保持官方命名主体，
   仅后缀标识 fork，可与官方版并存安装；`@string/applicationId` 由 AGP 自动生成，无需手改）。

## 与上游合并

```bash
git checkout master && git fetch upstream && git merge upstream/master
git checkout webhook-module && git merge master
```

冲突只可能出现在上述 5 处附近；`webhook/` 包与 `webhook_settings.xml` 自动合并。

## 数据流

```
手表 ⇄BLE⇄ Gadgetbridge（本 fork）
  → 同步完成 signalActivityDataFinish() → scheduleImmediate()（2 分钟限频）
  → WorkManager 周期任务（默认 15 分钟，可配置）
  → WebhookUploader：
      GBDatabaseManager.acquireDB() → DeviceHelper.getAvailableDevices()
      → DeviceCoordinator.getSampleProvider() → getAllActivitySamples(cursor, now)
      → JSON POST（Authorization: Bearer <token>）→ 服务端返回 {"status":"ok"} → 推进游标
```

数据读取走 GB 统一的 `SampleProvider` 抽象（每设备每分钟一条：时间戳 / 归一化活动类型 /
步数 / 心率 / 强度 / 距离 / 卡路里），与具体手表型号无关；电量取 `GBDevice.getBatteryLevel()`。
首次上传无游标时回补最近 24 小时；单次最大回传 7 天（防异常）。

**扩展指标**：`WebhookUploader.EXTENDED_TABLES` 定义了各类别对应的数据表（血氧 / 压力 /
HRV(RR 间期) / 睡眠呼吸率 / 睡眠时段 / 每日汇总 / PAI / 运动记录），用原始 SQL 按
`(DEVICE_ID, TIMESTAMP)` 范围读取，只读实际存在的表；行 JSON 键为小写列名，
`timestamp` 统一为 epoch 秒；每表每轮最多 2000 条（取最新）。

**上传开关（具体上传哪些数据）**：设置页「上传的数据」多选（`webhook_data_types`，
默认全选），控制分钟样本内附加的距离/卡路里、设备电量以及上述各类扩展指标是否上传；
分钟样本主体（步数/心率/睡眠/运动类型）始终上传。

**绑定码（多用户隔离）**：设置页显示 `GB-XXXXXX`（首次进入自动生成并持久化，见
`WebhookConfig.getOrCreateBindingCode()`），随每次上传的 `device.binding_code` 上报；
对服务器机器人发送 `/bind GB-XXXXXX` 即可把设备绑定到你的会话，之后查询与告警都按
会话隔离（详见插件仓库 README）。
