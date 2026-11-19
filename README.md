# nicehck-popup

原道（NiceHCK / YuanDao）TWS 耳机的**免 root** 电量读取工具，目标是在小米 HyperOS 上做到「连上耳机就弹出左耳 / 右耳 / 充电盒三路电量」。

参考实现（协议来源）：

- [`ZaeXT/NiceHCK_Controller`](https://github.com/ZaeXT/NiceHCK_Controller) — 原版 Java App，帧解析来源
- [`silverpoetry/HyperEars`](https://github.com/silverpoetry/HyperEars) — HyperOS 系统级模块，`NiceHckWireCodec.kt`
- 原道官方 App `com.yuandao.nicehck` 走的也是同一条通道

## 协议摘要

经典蓝牙 **RFCOMM / SPP**（不是 BLE GATT）：

| 项 | 值 |
|---|---|
| SPP UUID | `0000a100-1000-8000-4e48-434b4354524c`（尾部 ASCII `NHCKCTRL`） |
| 帧格式 | `4E` \| len(LE16) \| `00` \| opcode(LE16) \| params |
| len | `3 + params.length`，整帧长 `6 + params.length` |
| 电量 | `0x0005` → params = 左耳 / 右耳 / 充电盒（1..100，`0` = 未知） |
| 状态 | `0x0006` → params[1]: 02=合盖 / 03=开盖；params[3]: 02=双耳在盒 / 03=有耳机离盒 |
| 其它 | `0x0003` 固件、`0x0101` 降噪查询、`0x0201` 设降噪 |

`0x0006` 的字节含义由真机动作序列标定（合盖→开盖→取一只→放回→关盖），五个状态转换全部吻合。

## 目录结构

```
protocol/    纯 JVM，零 Android 依赖 —— 可本地编译+自测
  Frame.java            帧编码 / 解析
  FrameDecoder.java     流式解码（半包、粘包、垃圾重同步）
  BatteryLevels.java    三路电量语义（0 = 未知，沿用上次值）
  ProtocolSelfTest.java 39 项断言，main() 直接跑
app/         Android 层（纯 Java，无 AndroidX、无 Kotlin）
  MainActivity.java     M1 验证台：按钮 + 三路电量 + 内存日志面板
  NiceHckClient.java    SPP 会话：读线程 + 轮询 ticker
  DeviceFinder.java     从已配对列表挑出原道耳机
  DebugLog.java         环形日志（无 adb/logcat 时代的唯一观测手段）
```

## 本地验证协议层（不需要 Android SDK）

```bash
javac --release 17 -d /tmp/nhp-out protocol/src/main/java/dev/nhp/protocol/*.java
java -cp /tmp/nhp-out dev.nhp.protocol.ProtocolSelfTest   # passed=39 failed=0
```

## 本地编译检查 App 层（可选，只下一个 android.jar，不装 SDK）

```bash
curl -o /tmp/platform-35.zip https://dl.google.com/android/repository/platform-35_r02.zip
unzip -q /tmp/platform-35.zip -d /tmp/pf35

javac -source 17 -target 17 -Xlint:all -Xlint:-options \
  -cp /tmp/pf35/android-35/android.jar -d /tmp/app-out \
  protocol/src/main/java/dev/nhp/protocol/*.java \
  app/src/main/java/dev/nhp/app/*.java
```

这一步能抓出真实类型错误（例如 `BluetoothSocket` 没有 `setSoTimeout()`），显著降低云编往返次数。

## 出包（GitHub Actions）

推送到 `master` / `main` 即触发，或在 Actions 页面手动 `Run workflow`：

1. `gradle :protocol:selftest` — 协议层断言
2. `gradle :app:assembleDebug` — 出 debug APK
3. 产物在 run 的 **Artifacts → nicehck-popup-debug**

想直接在手机上下载安装：打一个 tag（如 `v0.1.0-m1`），workflow 会把 APK 发布成 Release 资产，手机浏览器点开即可下载。

## 手机侧安装（小米 17 / HyperOS）

1. 允许「安装未知应用」（首次点 APK 时按提示授权）
2. 安装后打开 App，授予**附近设备**（`BLUETOOTH_CONNECT`）权限
3. 先在系统设置里把耳机配对好，并让耳机处于连接状态
4. 点「连接并读电量」，看三路数字与官方「原道」App 是否一致

## M1 验收

- [ ] 三路电量读得到，与官方 App 一致（±1%）
- [ ] 充电盒显示 `0` 时按「未知」处理（显示 `--` 或 `~上次值`）
- [ ] 单耳使用时另一耳显示 `--` 而不是 `0%`
- [ ] 断开 / 重连后仍能继续读
- [ ] 「复制日志」能导出完整 TX/RX 十六进制

## 路线

| 阶段 | 内容 | 状态 |
|---|---|---|
| M1 | 协议验证台：能读到三路电量 | ✅ 真机验证通过（三路电量 + 粘包拆包 + 0 值沿用） |
| M2 | 前台 Service + 常驻通知轮询 | ✅ 代码完成 |
| M3 | `ACL_CONNECTED` 触发悬浮窗（仿系统卡片） | ✅ 代码完成，待真机验证 |
| M4 | 降噪控制（`0x0201`）、弹窗样式设置、开机自启 | 待做 |

## 权限清单（M3 起）

| 权限 | 用途 | 获取方式 |
|---|---|---|
| 附近设备（`BLUETOOTH_CONNECT`） | 读配对列表 + 建 SPP | 运行时弹窗 |
| 通知（`POST_NOTIFICATIONS`） | 前台服务常驻通知 | 运行时弹窗（Android 13+） |
| 显示在其他应用上层 | 弹窗本体 | 跳系统设置 |
| **后台弹出界面** | HyperOS 私有，缺它必不弹 | 应用详情 → 权限管理，手动开 |
| 自启动 / 电池无限制 | 防止 HyperOS 冻结服务 | 应用详情，手动开 |

## 已知限制

- 充电盒偶尔不上报电量（帧里为 `0`），此时显示上次的值并标 `~`
- 官方「原道」App 在前台时可能抢占 SPP 通道，需要先强行停止它
- **开盖即弹做不到**：`0x0006` 走 SPP，而 SPP 要求耳机已连上手机；触发点只能是 `ACL_CONNECTED`（出仓后约 1–2 秒）
- secure 通道不稳定（SDP 记录时有时无），已改为**优先 insecure**，失败再试 secure
- 融合设备中心 / 超级岛 / 系统蓝牙设置页电量需要 root + LSPosed（见 HyperEars），本项目不做
