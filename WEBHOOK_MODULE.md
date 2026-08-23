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

## 对上游文件的改动（5 处钩子点，合并时注意）

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
步数 / 心率 / 强度），与具体手表型号无关；电量取 `GBDevice.getBatteryLevel()`。
首次上传无游标时回补最近 24 小时；单次最大回传 7 天（防异常）。
