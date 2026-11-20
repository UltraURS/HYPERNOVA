# Redmi Note 12T Pro / HyperOS 3.0.3.0 适配档案

档案建立及更新日期：2026-11-16。状态：**首批及侧边栏新增材料已核验。用户反馈 0.2.1 侧边栏无法呼出，0.2.2 已改用系统返回震动计时并修正相反侧窗口隐藏，待真机回归。开发端尚未连接手机验证。** 用户确认系统由第三方 ROM 刷入，名称与发布来源未知。代码范围见 [开发版说明](../releases/0.2.2-dev.md)，恢复出厂执行拦截已按用户要求移出范围，原 RS-02/03/04/06 保留为历史静态研究；下面的覆盖状态仍按设备证据填写，不因代码编译成功提升为真机通过。

## 1. 已确认与待确认

| 项目 | 内容 | 证据状态 |
| --- | --- | --- |
| 机型 | Redmi Note 12T Pro | 用户提供 |
| HyperOS 完整版本 | 3.0.3.0.VLHCNXM | 关于手机截图 |
| Android | 15 / API 35，arm64-v8a，4 KB 页 | 系统详情与 LSPosed 截图；未直接读设备 |
| LSPosed | 2.2.1 (7912)，API 102；API 调用保护开启 | 状态截图；用户不清楚分支/来源 |
| 网络屏蔽范围 | OTA 客户端检查、下载和安装请求 | 用户已确认 |
| 设备代号、安全补丁 | 截图 fingerprint 含 `pearl`；安全补丁 2026-04-01 | 截图所报，不据此认定官方固件 |
| Root | SukiSU LKM；管理器 v4.3.0 (41100-6)，内核 v4.3.0-e67b14e6@release | Root 管理器截图 |
| Zygisk | Zygisk Next 1.5.3 (880-22a1bf5-release) | Root 管理器截图 |
| 内核版本 | 5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284 | 系统详情截图 |
| 配置桥接、恢复方法 | 传统 API 兼容性、共享偏好和可用停用/救援方法待实测 | API 102 不能单独证明旧桥接可用 |
| 使用场景 | 主用户、单卡 | 用户确认 |
| 控制中心 | 顶部 Wi-Fi/移动数据双大卡；有 `miui.systemui.plugin` 样本 | 控制中心截图与 APK |
| 系统 OTA | 无系统更新器，未下载系统更新 | 用户确认；包管理器存在性待读 |
| 共存模块 | HyperCeiler 已启用，配置备份已解析 | 用户确认；模块版本/来源与实际作用域待确认 |

截图所报 fingerprint：

```text
Redmi/pearl/pearl:15/AP3A.240905.015.A2/OS3.0.3.0.VLHCNXM:user/release-keys
```

第三方 ROM 可沿用同一版本号和 fingerprint，它们不足以区分修改后的二进制。运行时至少结合完整构建标识、SDK、宿主版本和关键成员结构；系统框架 Hook 还需匹配核验过的 framework/services 基线。文件 SHA-256 固定本次样本，最终匹配策略在 P1 落实，不在系统热路径反复计算，也不把同名 ROM 自动视为已支持。

## 2. 材料清单

材料由用户放入根目录 `apks/`，截图在 `apks/pictures/`；保留原文件不移动。所有样本可解析，完整 SHA-256 和 DEX 分布见 [证据 JSON](redmi-note-12t-pro-os3.0.3.0-evidence.json)，详细结论见 [静态分析](redmi-note-12t-pro-os3.0.3.0-static-analysis.md)。

| 文件 | 包/版本 | 已核验结果 |
| --- | --- | --- |
| `系统界面_20240808.0.apk` | `com.android.systemui`，20240808.0 / 202408080 | 4 DEX；磁贴状态与基类生命周期已定位 |
| `系统界面组件_16.5.3.42.0.apk` | `miui.systemui.plugin`，16.5.3.42.0 / 165034200 | 5 DEX；大卡读取 `state.label` |
| `设置_15.apk` | `com.android.settings`，15 / 35 | 11 DEX；OTA 引用、重置入口与前置副作用 |
| `framework.jar` | 框架库 | 5 DEX；RecoverySystem 重载 |
| `services.jar` | 系统服务库 | 3 DEX；广播、设备策略与 Recovery 服务 |
| `framework-res.apk` | `android`，15 / 35 | 资源/Manifest 可解析，无 DEX 正常 |
| `HyperCeiler_settings_backup_2026-11-16-11_43_43.json` | HyperCeiler 配置快照 | 376 项；与磁贴/设置相关项已记录，不能替代实际作用域 |
| `系统桌面.apk` | `com.miui.home`，RELEASE-6.01.03.1943-01051706 / 601031943 | 2 DEX；原生返回手势、箭头与取消路径已定位 |
| `手机管家.apk` | `com.miui.securitycenter`，12.1.3-251205.0.1 / 40001213 | 4 DEX；`:ui` 进程的签名权限服务与左右面板已定位 |
| `日历.apk` | `com.android.calendar`，17.0.6.26 / 17000626 | 2 DEX；原生推广关闭分支、广告专用请求和缓存已定位 |
| `天气.apk` | `com.miui.weather2`，17.0.3.15 / 17000315 | 2 DEX；预报／首屏广告请求及共用内容广告节点已定位 |

三个应用 Manifest 声明共享系统 UID；SystemUI 声明统计相关权限，Settings 声明 MASTER_CLEAR/RECOVERY 等权限。上述均是静态声明，实际 UID、授权、sourceDir/splitSourceDirs 仍需包管理器结果。用户提供的是每应用一个 APK，尚不能证明无需 split。

截图索引（文件名位于 `apks/pictures/`）：

| 文件 | 已提取信息 |
| --- | --- |
| `5d1313e4f04657b4eae1e38712ca690c.jpg` | 关于手机、完整版本 |
| `698ba5f04212ba82db34a410eeaad9ea.jpg` | Android、安全补丁、内核 |
| `8b5715c78deea3c6d8a115df759112be.jpg` | 系统详情补充 |
| `70d443f24734359cfbd8f418c4df78e1.jpg` | 控制中心移动数据大卡 |
| `7801c9593f1d8ce57f23079f81464766.jpg` | SukiSU、Zygisk、fingerprint |
| `9379f83b115e89f4cfdf05046ed2bdcd.jpg` | LSPosed 版本、API、架构 |
| `930c75c9772cd8eae7024e03aa75b140.jpg` | “我的设备”恢复出厂入口 |
| `b65def9129bb9bc17ad73757088fedbc.jpg` | “更多设置”底部推荐入口 |

侧边栏所需系统桌面与手机管家样本已收到并完成静态核验；见 [侧边栏适配记录](sidebar-gesture.md)。基础功能仍可补包路径/包信息、厂商 framework 目录及共享库列表，不重复索取已有 APK、截图或不存在的更新器。ROM 与 LSPosed 来源暂记未知，HyperCeiler 版本/作用域在联调前补记。命令和存放方法见 [采集说明](../collection-guide.md)。

以下仅为后续采集可用的只读命令示例，本轮未执行：

```powershell
adb shell getprop ro.product.model
adb shell getprop ro.product.device
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.incremental
adb shell getprop ro.build.fingerprint
adb shell getprop ro.build.version.security_patch
adb shell pm path com.android.systemui
adb shell pm path miui.systemui.plugin
adb shell pm path com.android.settings
adb shell pm path com.android.updater
adb shell dumpsys package com.android.updater
```

完整 HyperOS 版本同时从“关于手机”记录，不能假定一个属性就包含所有地区/构建信息。`pm path` 返回多个路径时都要采集；按实际返回路径使用 `adb pull`，不猜文件位置。不在采集脚本中加入触发重置或更新安装的命令。

## 3. Hook 证据模板

每个准备实现的 Hook 填写一条记录：

| 字段 | 必填内容 |
| --- | --- |
| 目标功能和目的 | 具体要改变的行为，而非只有类名 |
| 文件与哈希 | APK/JAR、版本、原始 DEX 所在位置 |
| 包/进程/加载器 | 真正执行位置；共享或插件加载情况 |
| 原始成员 | 类全名、方法名、参数、返回类型或字段类型 |
| 调用链 | 上游入口、下游委托、第一次副作用的位置 |
| 拦截时机 | before/after/replacement 及原因 |
| 返回契约 | 拒绝、取消、成功、异步回调分别代表什么 |
| 权限与资源 | 原有鉴权、线程、Cursor/回调/监听器释放要求 |
| 必需/可选 | 缺失是否使整组功能不受支持 |
| 验证结果 | DEX 匹配、设备安装、实际命中、验收编号 |

已保存 31 个原始 DEX 成员声明，用于确定分析候选；不存在已安装或真机通过的 Hook。调用方契约、加载器与副作用边界仍按上表逐项补齐，不能把静态匹配写成支持完成。

## 4. 覆盖清单

状态可用：`待分析`、`静态定位（附未完成项）`、`已安装`、`真机通过`、`不适用（附原因）`、`未覆盖（附原因）`。本轮没有安装或真机通过项。

| ID | 目标表面/路径 | 状态 | 关联验收 |
| --- | --- | --- | --- |
| TR-01 | 移动数据磁贴文案、点击、长按 | 静态定位：状态生成与插件读取；待真机 | T02、T04、T05 |
| TR-02 | 控制中心展开/关闭、插件重载和销毁 | 静态定位：基类监听/销毁；需区分后台 stale 监听 | T03 |
| TR-03 | 全部 SIM 今日流量查询与权限 | Manifest 请求权限已知；查询待测 | T01、T02、T04 |
| OT-01 | 设置主页/关于手机更新入口、卡片、红点 | 静态定位：有 `miui_updater` 引用；可达性待核查 | T06、T07 |
| OT-02 | 设置搜索、动态结果与旧索引 | 缺搜索共享库，待分析 | T06 |
| OT-03 | Updater 主入口、外部 Intent/深链接 | 用户确认无更新器；包存在性及其他目标待核查 | T06 |
| OT-04 | 更新弹窗、新通知、存量通知及通知点击 | 同上；Settings 残留提示单独核查 | T07、T08 |
| OT-05 | 手动/启动/定时检查、所有相关子进程 | 客户端缺失，待确认不适用；未记为阻断通过 | T09、T12 |
| OT-06 | 客户端网络发送与下载委托 | 客户端缺失，待确认不适用；无服务器封锁证据 | T09、T10 |
| OT-07 | 在线/缓存/本地包安装、引擎委托 | 客户端缺失，待确认不适用；用户未下载更新 | T10、T11 |
| RS-01 | 恢复出厂设置入口、搜索、直接跳转 | 普通条目与推荐过滤已实现；推荐目标由 assets/recommend.json 确认；设置搜索过滤已实现，尚未真机验收 | T13 |
| RS-02 | 整机重置广播及厂商接收/转发链 | 静态定位；需核对重建警告与厂商分支 | T14 |
| RS-03 | 设备策略/厂商整机重置及此前副作用 | 静态定位：存储、FRP、状态变更；缺 MiuiInit 实现 | T14 |
| RS-04 | RecoverySystem 重载及跨进程最终委托 | 6 个重载、直接 bootCommand 与服务成员已定位 | T14 |
| RS-06 | 厂商 `--factory_test_reset` 路径 | 已见调用，命令至服务端/Recovery 行为未闭环 | T14、T15 |
| RS-05 | 非整机重置、普通重启、资料移除不误伤 | 待分析 | T15 |
| NT-01 | 单条通知锁定/取消、分组保护 | 代码及原始成员已核验；待真机 | T19、T21 |
| NT-02 | 单条通知独立置顶/取消与生命周期 | 原生分组入口与优先 section 已接入；待真机 | T20–T23 |
| ALL-01 | 配置桥接、未解锁启动、停用恢复 | 待分析 | T16、T17 |
| ALL-02 | HyperCeiler 共存、标题覆盖和设置过滤 | 已检查配置快照；版本/作用域/实际命中待测 | T18 |

OTA 和重置分析中发现的新入口必须追加到表里，不能只验证已有表格而忽略新路径。远程请求、设备管理和厂商原生工具要注明是否存在、是否经过已覆盖的执行链。Recovery/Fastboot/刷机清除属于本项目明确不覆盖的系统外路径。

## 5. 本次源项目核验记录

源项目为同级目录 `Connect Colours`。源码声明应用版本 `0.2.4`、versionCode `17`，目录中另有不同后缀 APK；本规划依据源码，不把目录里 APK 名称当成源码版本证据。其 `NOTICE` 标明 `GPL-3.0-only`。

| 文件 | 本次 SHA-256 |
| --- | --- |
| `app/src/main/java/io/github/connectcolours/usage/DailyUsage.java` | `A96B8ADA526D2F23115CB71843CA98660A762507FEBA833B8E6BD17FE18B678B` |
| `app/src/main/java/io/github/connectcolours/hook/TrafficTileHook.java` | `EB4D7BE62DF05B1AB8A6CEA97FC48CD9E5E00508EF803B29595DF849C9B5650B` |
| `app/src/main/java/io/github/connectcolours/hook/OtaHook.java` | `6915DA6D469837E2EB519352BC95FEDBF5322B554A1AC32485199BC537EB1232` |

流量日期计算与格式化可复用；ColorOS 磁贴类、设置控制器、OTA 混淆类及通知 ID 必须重做适配。本次搜索未发现恢复出厂设置拦截代码。未运行源项目测试，也未改动源项目。

## 6. 开发交接入口

下一次验证 0.2.0-dev 的模块加载、配置读取、流量磁贴与精确 key 入口隐藏，完成 [实施计划 P1](../implementation-plan.md#p1最小工程与配置闭环) 的真机条件。继续补充厂商依赖与包管理器证据；没有更新器不阻塞其他功能。P0 的静态定位门槛已满足，完整材料和真机证据仍未齐备。

完成一项验收后填写设备完整版本、模块构建号、开关状态、操作步骤、预期/实际结果和脱敏日志位置。不要只记录“可用”。
