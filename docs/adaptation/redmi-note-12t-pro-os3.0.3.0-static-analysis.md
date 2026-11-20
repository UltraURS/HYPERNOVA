# 首批材料静态分析

分析日期：2026-11-16。对象为用户提供的第三方 ROM 样本；结论只针对这些文件，不代表所有 HyperOS 3.0.3.0。**没有连接手机、执行 ADB、安装模块或触发更新/重置。**

本文保留开发前的材料分析记录。随后已实现的基础代码与检查结果以 [0.1.0-dev 说明](../releases/0.1.0-dev.md) 为准，真机覆盖状态仍未提升为通过。

## 1. 材料与证据等级

已读取 `apks/` 内 3 个应用 APK、`framework-res.apk`、2 个框架 JAR、`pictures/` 内 8 张截图，以及 HyperCeiler 配置备份。两个 JAR 均含原始 DEX；资源 APK 不含 DEX 属正常情况。完整文件 SHA-256、DEX 列表和原始成员声明见 [机器可读证据](redmi-note-12t-pro-os3.0.3.0-evidence.json)。

核验使用本地 aapt2 35.0.0、JADX 1.5.3，以及只读复用 Connect Colours 的 `tools/verify-apk-hooks.py` 中 `dex_members` 解析函数。原始声明与反编译代码分开核对，未复制 ColorOS 的目标列表。

- 原始 DEX 声明证明类/成员存在及参数类型，不证明设备实际加载、Hook 命中或行为正确。
- Manifest/XML 证明组件、权限请求和资源定义存在，不证明权限已授予或界面当前可达。
- JADX 用于定位调用链。`MiuiCellularTile.handleUpdateState`、`MasterClearReceiver.onReceive` 等存在重建警告，关键条件和执行顺序在编码前还需核对 DEX 指令及运行日志。
- 版本、Root 和控制中心布局来自截图；主用户、单卡、无更新器和未下载更新来自用户描述。安装路径、split 集合、实际进程与权限仍待设备读取。

## 2. 流量磁贴：优先只修改 SystemUI 状态

已核验的原始声明：

```text
com.android.systemui.qs.tiles.MiuiCellularTile
  handleUpdateState(com.android.systemui.plugins.qs.QSTile$State, java.lang.Object): void
com.android.systemui.qs.tileimpl.QSTileImpl
  handleSetListening(boolean): void
  handleDestroy(): void
  handleRefreshState(java.lang.Object): void
  refreshState(): void
miui.systemui.controlcenter.qs.tileview.QSCardItemView
  updateState(com.android.systemui.plugins.qs.QSTile$State, boolean, boolean): void
```

`handleUpdateState` 的声明参数是 `QSTile.State`，实现内部使用 `QSTile.TrafficState`，不能照搬 ColorOS 的 `SignalState` 签名。SystemUI 在该方法内生成 `label`、`secondaryLabel` 和无障碍说明；基类随后把临时状态复制到当前状态并通知回调。插件 `QSCardItemView.updateState` 从 `state.label` 取标题，独立生成开关状态文字。

首版方案：在 `MiuiCellularTile.handleUpdateState` 正常完成后、状态复制之前，把有效缓存写入标题。截图中的移动数据大卡标题改为“今日 …”，保留副标题的开关状态及原点击、长按、SIM 行为。插件暂作为显示链证据，不增加插件 Hook 或加载器监听；真机证明此链路不成立时再调查。

实现前仍需验证：

1. SystemUI 确实加载这份类，统计 API 权限和查询口径有效；Manifest 请求权限不能替代实测。
2. `QSTileImpl` 的监听变化不完全等于面板可见：`mStaleListener` 会在后台短暂监听。不得据此启动持续 30 秒轮询；须区分真实 UI 监听，并验证展开、收起和销毁。
3. 只针对目标磁贴实例处理基类生命周期；保留原状态线程，通过 `refreshState` 触发更新，不直接跨线程写插件视图。
4. 查询失败保留原标题；跨日、时区变化和销毁后的旧结果丢弃。无障碍说明需与流量及开关状态一致，不重复累加。

本地定位文件：`.local/analysis/systemui/{MiuiCellularTile,QSTileImpl}.java`、`.local/analysis/plugin/QSCardItemView.java`。

## 3. OTA：缺少客户端，仍需核查设置残留

用户确认 ROM 没有系统更新器、未下载系统更新；本批材料也没有 Updater APK。无需补传或安装其他固件的更新器。

但设置样本仍包含：

- `my_device_settings.xml` 中的 `miui_updater` 条目元数据。
- `com.android.updater.permission.RECEIVE_BROADCAST` 权限引用，以及与更新器有关的接收动作。
- 系统更新控制器类；存在类本身不能证明这台设备会显示相应入口。

因此分别记录两种状态：设置入口/搜索/红点继续分析；特定客户端的检查、下载、通知和安装路径，在包管理器确认目标缺失后标为 `NOT_APPLICABLE`。这不等于网络封锁已生效，也不证明 ROM 没有其他 OTA 组件。

先核对 `pm path com.android.updater` 和 `dumpsys package com.android.updater`，再从设置实际跳转目标决定是否还有其他客户端。若日后装回未知更新器，当前不适用状态失效，必须重新适配。不会根据网上域名添加 hosts 或全局网络 Hook。

## 4. 重置：厂商入口和前置副作用必须纳入

### 设置入口

截图显示“我的设备”中的恢复出厂设置，以及“更多设置”底部的推荐入口。前者已定位：

- `MiuiFactoryResetController.isAvailable(): boolean`；当前实现包含主用户判断。
- `getPreferenceKey()` 返回 `factory_reset_key`，XML 指向 `com.android.settings.MiuiMasterClear`。
- 另有 `reset_dashboard_fragment.xml` 中的整机重置条目，以及 Manifest 中的直接打开 Activity。

可优先从目标控制器隐藏明确条目，但这只覆盖一处入口。“更多设置”推荐项、搜索结果及直接启动页面尚需分别追踪。不能删除整个重置分类或所有名称带 reset 的设置。

### 执行链

| 路径 | 静态发现 | 对实现的约束 |
| --- | --- | --- |
| `MiuiMasterClear.formatSdCardAndFactoryReset()` | 可先调用存储格式化，再进入重置 | 只拦最终广播太晚 |
| `MiuiMasterClear.doFactoryReset()` | 调用外部 `miui.os.MiuiInit.doFactoryReset`，再进入 FRP/整机清除链 | 厂商库未取得，不能宣称链路完整 |
| `wipeFrpBlockDataAndDoMasterClear` | 存在两个重载，包含 FRP 数据擦除 | 在前置副作用之前拒绝，并恢复页面状态 |
| `MiuiFactoryResetReceived.doMasterClear()` | 反射调用 `RecoverySystem.bootCommand(Context, String[])`，命令含 `--factory_test_reset` | 绕过常见 `rebootWipeUserData`；需追踪该命令至服务端 |
| `MasterClearReceiver.onReceive(Context, Intent)` | 存在整机重置广播、转发及用户相关分支 | 核对重建有警告的分支，不误拦单独移除用户/资料 |
| 设备策略 `FactoryResetter.factoryReset()` | 在内部清除前设置重置状态；`false` 表示等待安全检查回调 | 不可简单返回 false 伪装拒绝，也不能让状态提前改变 |
| `FactoryResetter.factoryResetInternalUnchecked()` | 可先清 FRP 和可采纳存储，再调用 RecoverySystem | 拦截需早于这些步骤 |

`framework.jar` 中核验到 `RecoverySystem.rebootWipeUserData` 的 6 个重载，以及 `bootCommand(Context, String[])`。`services.jar` 中也存在 `RecoverySystemService.rebootRecoveryWithCommand(String)`。这些是追踪与补充防线候选，不代表每个方法都应安装 Hook。

对厂商重置命令按已核验参数语义匹配，不全局封禁 `bootCommand` 或所有重启。`--factory_test_reset` 的厂商 Recovery 行为、服务端调用顺序和其他厂商入口尚未闭环；不为了验证执行真实重置。Settings 及系统端必须各自找到首次副作用之前的拒绝点，并验证调用方的结束契约。

### 缺少的依赖

设置 Manifest 声明可选共享库 `MiuiSettingsSearchLib.jar` 和 `com.miui.system`。当前样本引用了 `com.android.settingslib.search.SettingsTree` 和 `miui.os.MiuiInit`，但未在已给应用/框架的原始声明中找到这两个类。下一步先读取厂商 framework 目录与共享库映射，再确定需要补充的 JAR，不能把共享库逻辑名称当作确定的磁盘文件名。

## 5. HyperCeiler 共存基线

用户确认 HyperCeiler 已启用。已解析 `HyperCeiler_settings_backup_2026-11-16-11_43_43.json`，共 376 项；文件哈希写入证据 JSON。以下只描述备份中的实际值，不推断未知版本的开关含义：

| 配置键 | 值 |
| --- | --- |
| `prefs_key_system_ui_qs_label` | `false` |
| `prefs_key_system_control_center_old_enable` | `false` |
| `prefs_key_system_control_center_unlock_old` | `false` |
| `prefs_key_system_ui_control_center_show_weather` | `false` |
| `prefs_key_system_ui_control_center_hide_operator` | 字符串 `"0"` |
| `prefs_key_system_ui_statusbar_clock_all_status_enable` | `true` |
| `prefs_key_system_settings_unlock_google_header` | `true` |
| `prefs_key_system_settings_safe_wifi` | `true` |
| `prefs_key_various_disable_cloud_control` | `true` |

备份中未找到可明确识别为“移动数据磁贴今日流量”的已启用项目，但这不能证明没有 Hook 冲突。缺失键的默认值、数值枚举含义、导出后的修改、HyperCeiler 版本和实际 LSPosed 作用域均未知；通用云控配置也不能视为 OTA 已被阻断。

联调先保留现有配置，记录标题是否被覆盖、是否重复查询及设置入口是否受到其他模块影响。若出现冲突，先定位双方写入位置和调用顺序，再仅对重叠项做对照验证；不自动修改 HyperCeiler 配置，不用无限提高 Hook 优先级掩盖冲突。发布前补记其版本/来源与实际作用域。

## 6. 下一步与尚缺材料

现在可以进入 P1 最小工程及配置验证，随后开发流量功能；OTA 和重置按上述已知范围继续分析。

尚缺材料集中在以下几项，已有 APK 和截图不需重复提交：

1. 相关包的 `pm path` / `dumpsys package`，确认实际加载文件、是否有 split、权限和更新器缺失状态。
2. `/system/framework`、`/system_ext/framework`、`/product/framework`、`/vendor/framework` 目录清单及 `pm list libraries`；据此定位搜索库和厂商重置库。具体只读命令见 [采集说明](../collection-guide.md)。
3. ROM 名称/来源及 LSPosed 分支目前用户不清楚，保留为未知；不阻止静态分析和最小加载测试。系统框架 Hook 启用前仍需确认实际可用的停用恢复方法。
4. HyperCeiler 的版本/来源和实际 LSPosed 作用域，安排在共存真机验证前补充。

上述分析完成时尚未实现功能；随后已开发基础版本，仍未真机验收。静态分析没有把“成员存在”“其他模块已启用”或“更新器缺失”计为 HYPERNOVA 功能通过。
