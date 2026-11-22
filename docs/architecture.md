# 架构设计

本文区分“已核验的源项目实现”“首版设计决定”和“尚待固件验证的候选路径”。目标设备信息见 [适配档案](adaptation/redmi-note-12t-pro-os3.0.3.0.md)。

当前范围更新（0.2.0）：恢复出厂设置只隐藏入口，不拦截执行；OTA 仍限客户端，缺少更新器时显示不适用，不添加设备级规则。

侧边栏增量（0.2.2）：保留 100～500 ms、默认 300 ms 的配置及 `feature/sidebar/SidebarGesture` 状态机；计时起点按用户新要求改为系统返回就绪震动，取消动画最大距离限制，并修正相反侧原生窗口隐藏。开关默认关闭，用户反馈 0.2.1 无法呼出，本版待真机回归。接入依据与验收条件见 [侧边栏适配记录](adaptation/sidebar-gesture.md)。

## 1. 设计基线

- 首版只针对 Redmi Note 12T Pro / HyperOS 3.0.3.0.VLHCNXM / Android 15 的已给第三方 ROM 样本建立适配档案；主用户、单卡为当前验证场景。同名版本及 fingerprint 不保证二进制一致。
- 使用一个 Android `app` 模块，按职责分包；采用 Java，减少从 Java 源项目迁移的改写量。
- 包名建议为 `io.github.hypernova`，工程名与显示名为 `HYPERNOVA`。包名是规划建议，需在首次安装前固定。
- `compileSdk`、`targetSdk`、`minSdk` 首版均规划为 35，不沿用源项目的 Android 13 限制。
- 优先验证传统 Xposed API 82 编译接口及 LSPosed 共享偏好桥接，以复用现有实现。**截图已确认 LSPosed 2.2.1 (7912)、API 102，但分支未知，传统接口与配置桥接仍需实测。**保留当前 API 调用保护设置；若验证不通过，在搭建工程阶段改用该框架实际支持的接口，不并行维护两套入口。
- 构建工具可先用源项目的 AGP 8.7.3、Gradle 8.9、JDK 21、SDK/Build Tools 35 组合做最小构建验证；通过后锁定版本，补齐 Gradle Wrapper。工具路径来自本机配置，不写死到其他项目目录。
- 不引入常驻服务、数据库、云端规则、通用插件框架、全局类扫描或多 ROM 分发系统。首版用一个明确的适配描述类；有第二个真实目标时再扩展。

0.2.30-dev 新增现代入口、作用域声明和远程偏好。LSPosed 2.2.1 选择现代入口，使用 API 101 兼容现有功能的 Hook 调用。旧配置过渡包单独按传统模块构建。[libxposed 官方 API 文档](https://libxposed.github.io/api/io/github/libxposed/api/package-summary.html)

## 2. 源码移植边界

下表路径相对于 `../Connect Colours`；结论来自 2026-11-16 的本地源码检查，不能视为 HyperOS 真机验证。

| 来源 | 已有能力 | HYPERNOVA 处理 |
| --- | --- | --- |
| `usage/DailyUsage.java` | 日界线、时区、流量单位与文案 | 复用计算逻辑，迁移对应边界测试 |
| `usage/MobileUsageReader.java` | `NetworkStatsManager` 汇总移动流量 | 复用查询思路，重新验证宿主权限及返回口径 |
| `hook/TrafficTileHook.java` | 后台统计、30 秒节流、磁贴生命周期 | 保留调度思路，重写 HyperOS 磁贴绑定；首版优先无需插件 Hook |
| `hook/UpdatePolicy.java` | 根据 key、包名、action 精确识别 OTA | 保留纯策略结构，替换所有 ColorOS 标识 |
| `hook/SettingsHook.java` | 入口、推荐卡、搜索过滤、红点 | 复用过滤语义，重写控制器、字段和搜索提供方 Hook |
| `hook/OtaHook.java` | 状态、弹窗、通知、Java 网络拦截 | 拆分职责后重写；补充下载委托和安装请求阻断 |
| `hook/ModuleEntry.java`、`FeatureConfig.java` | 包加载分发、版本门槛、共享偏好 | 重写为独立功能安装，显式默认值与能力检测 |
| `tools/CoreChecks.java`、`SettingsHookTest.java` | 日期、格式、搜索过滤测试 | 迁移相关用例，不迁入无关测试 |
| `tools/verify-apk-hooks.py` | 原始 DEX 成员检查 | 参考其核验流程；支持目标 APK/JAR 与多 DEX，不复制旧目标列表 |
| 恢复出厂设置 | 本次在源码、测试和工具中未找到实现 | 新增功能，不作为现有代码的简单改名移植 |

源项目的 `OplusCellularTile`、`com.oplus.ota`、混淆类名、通知 ID、状态码和 Android 13 版本白名单均不适用于直接移植。它的网络阻断只覆盖 OTA 进程内已分析的 Java 路径，不能证明下载服务、原生网络或安装链被阻断。

## 3. 代码结构与依赖

以下是目标工程布局。0.1.0-dev 已创建基础工程；入口隐藏共用 `feature/update/SettingsEntryHook`，重置执行链未创建空实现。

```text
app/src/main/java/io/github/hypernova/
  ui/                         Miuix 风格原生界面、包名分类、模块状态与重启提示
  config/                     配置键、显式默认值、只读配置快照
  hook/                       ModuleEntry、包/进程分发、日志、安装结果
  compat/                     首个固件描述、成员签名、能力探测
  feature/
    traffic/                  日期/格式、统计查询、磁贴 Hook
    update/                   识别策略、设置入口、提示、任务/联网/安装 Hook
    sidebar/                  停留状态、桌面手势/图标与左右原生面板
    reset/                    识别策略、设置入口、执行链 Hook
app/src/test/                 纯逻辑与宿主契约测试
tools/                        固件资料采集、DEX 核验、构建检查
docs/adaptation/              每个受支持固件的证据和验收结果
```

依赖方向为 `模块界面 → 配置存储 → 目标进程配置快照 → 独立功能 Hook`。`ModuleEntry` 只负责分发；`compat` 只描述已确认的宿主差异；日期、匹配策略等纯逻辑不引用 Xposed。

不提前创建只有一个实现的多层接口。只有必须替换真实擦除/安装副作用进行测试的位置，才设置窄的测试边界。编译时使用 `compileOnly` Xposed API；目标系统 APK/JAR 是分析材料，不打包进模块。

## 4. 目标进程与加载流程

| 作用域候选 | 职责 | 加载要求 |
| --- | --- | --- |
| 模块自身 | 检查框架激活、配置桥接，提供设置界面 | 激活结果只代表自身注入成功 |
| `com.android.systemui` | 移动数据磁贴与统计 | 主进程加载；需要确认实际磁贴所在 ClassLoader |
| `miui.systemui.plugin` | 已确认读取磁贴状态，暂不单独注入 | 首版仅修改 SystemUI 状态；若真机显示需要插件 Hook，再核验其宿主与 ClassLoader |
| `com.android.settings` | OTA 和恢复出厂入口、搜索、红点 | 搜索可能位于独立组件/进程，需据 Manifest 确认 |
| `com.android.updater` | 用户确认当前 ROM 无此客户端 | 设备确认缺失后请求组不适用；若发现其他客户端或以后装回，重新适配 |
| `com.miui.home` | 返回手势停留、图标和震动 | 仅主进程，侧边栏开关开启时校验桌面和手机管家两份 APK |
| `com.miui.securitycenter` | 接收侧边栏请求并选择左右原生面板 | 仅 `com.miui.securitycenter:ui`；沿用服务的原签名权限，不注入其他进程 |
| 系统框架 / `system_server` | 当前不需要 | 不注册作用域或重置执行 Hook |

SystemUI、插件和 Settings 的包名已由本机 APK Manifest 确认。实际加载路径/进程待设备核验；不能仅凭包名、文件名或另一个模块的支持列表放行。

加载步骤：

1. 先按包名和进程名过滤，只进入目标路由。不同框架的系统作用域标识按实测填写，不混淆传统 `android` 回调与现代 `system` 作用域。
2. 读取配置快照；校验 API 35、完整固件标识、宿主版本及目标成员签名。
3. 获取正确 ClassLoader。仅当确需插件 Hook 时等待已核验的插件加载事件，不全局轮询 `ClassLoader.loadClass`；当前流量方案不额外增加插件加载监听。
4. 每个功能先完整解析必需类、方法、参数、返回值及字段，再安装 Hook。
5. 同一进程、同一 ClassLoader、同一功能只安装一次。插件重载时解绑旧实例的任务，防止重复刷新。
6. 一个功能安装失败不阻止其他功能；同一进程内的功能安装组若中途失败，撤销该组已安装 Hook。不同进程之间不宣称原子安装。
7. 记录 `DISABLED`、`UNSUPPORTED`、`NOT_APPLICABLE`、`INSTALLED`、`ERROR`；实际运行命中另记 `OBSERVED`。`NOT_APPLICABLE` 必须附目标不存在的证据；包可见性受限不能当作包不存在。安装成功不等于功能验收通过。

未知固件或必需签名不匹配时跳过对应功能，保留系统原行为并明确报告“未受保护”。不能采用模糊猜测方法名的兜底，尤其不能在系统框架中试错。版本升级后重新核验，不能仅凭 `HyperOS 3` 或 APK `versionName` 放行。

## 5. 配置与状态

| 功能组 | 配置键 | 开发阶段初值 | 行为 |
| --- | --- | --- | --- |
| 每日流量 | `traffic_daily_usage` | 关闭 | 流量磁贴独立启用 |
| 系统 OTA | `ota_hide_entries` | 关闭 | 入口、搜索和红点 |
| 系统 OTA | `ota_block_prompts` | 关闭 | 精确匹配的通知和弹窗 |
| 系统 OTA | `ota_block_requests` | 关闭 | 检查、下载、联网和安装委托作为一组 |
| 恢复出厂设置 | `reset_hide_device_entry` | 关闭 | 隐藏普通入口和已核验的推荐跳转，不拦截执行 |
| 侧边栏手势 | `sidebar_back_gesture` | 关闭 | 双侧系统返回震动后计时、图标切换、松手呼出与回划取消 |
| 侧边栏阈值 | `sidebar_hold_millis` | `300` | 范围 100～500 ms，滑块步进 10 ms |

所有键显式提供默认值，不沿用“缺省全部开启”。0.2.2 起界面按应用包名分类，`Feature.packages` 显式声明关联包名；同一 Feature 在每个关联包页面使用原键读写。侧边栏开关与阈值在桌面、手机管家页面均显示，共用 `sidebar_back_gesture` 和 `sidebar_hold_millis`，没有按宿主创建配置副本。完整 OTA 目标保留上述三个开关便于独立诊断；不增加“伪装版本号”或“显示无新版本”独立功能。界面结构见 [界面说明](ui.md)。

上表为完整功能的目标配置。0.1.0-dev 只实现 `traffic_daily_usage`、`ota_hide_device_entry`、`reset_hide_device_entry` 三个基础开关；后两者只隐藏已核验 key 的条目，不复用 `factory_reset_guard`，避免把部分入口处理误称为执行保护。完整 OTA 开关暂不提供；重置执行拦截已移出当前范围。0.1.1 在原隐藏开关下补充推荐入口。

首版采用**保存后重启设备生效**，目标进程启动时读取不可变快照。这样不需要跨进程热更新或在每个系统调用中读取文件。界面显示待重启，不声称点击后立即受到保护。

传统版本使用 `xposedsharedprefs` 和 `XSharedPreferences`。从 0.2.30-dev 起，LSPosed 2.2.1 使用 libxposed 101 入口分发到现有功能 Hook，模块界面与宿主通过同一组远程偏好读写配置。过渡包读取传统共享偏好，在首次建立远程偏好时导入其开关和阈值。作用域申请由 LSPosed 服务发起授权通知。[libxposed 服务接口](https://github.com/libxposed/service)

共享偏好存布尔开关、侧边栏停留毫秒数和配置版本，不存设备标识、流量历史或其他敏感数据。配置不可读时本次进程不安装阻断功能，记录明确错误；不能以关闭系统调用或破坏启动来补偿。此行为意味着防护不具备框架缺失时仍然生效的保证。

界面显示模块服务连接状态和配置状态。各目标进程的安装与命中以 LSPosed 日志为准。libxposed 服务通过库的 Provider 建立连接，访问需要系统权限。

## 6. 每日流量流程

统计口径沿用 Connect Colours：设备当前时区的自然日、全部 SIM 移动数据 `rxBytes + txBytes`，不含 Wi-Fi，字节累计使用 `long`，单位按 1024 换算。显示例如 `今日 123.4 MB`。模块不累计自己的计数，重启后继续查询系统历史。

在拥有必要权限的宿主 Context 中调用 `NetworkStatsManager.querySummaryForDevice(TYPE_MOBILE, null, start, end)`。`null` 的移动订阅参数用于全部移动网络；查询可能耗时数秒，应在后台线程运行。必须实测 HyperOS 宿主权限，不能因处于 SystemUI 就假定成功。[Android NetworkStatsManager 文档](https://developer.android.com/reference/android/app/usage/NetworkStatsManager)

流程为：控制中心可见 → 读取有效缓存 → 单线程查询 → 回到宿主状态线程更新原磁贴文案 → 可见期间每 30 秒刷新。关闭面板停止定时，销毁磁贴丢弃在途结果；多个视图实例共享一个统计请求，避免并发查询。

实现要求：

- 本次静态定位为 `MiuiCellularTile.handleUpdateState(QSTile.State, Object)`，内部使用 `TrafficState`；插件大卡读取 `state.label`。优先在状态生成正常完成后改标题，保留副标题，详见 [静态分析](adaptation/redmi-note-12t-pro-os3.0.3.0-static-analysis.md)。
- 基类监听中存在后台 `mStaleListener`；不能把任意监听开启当作面板展开。仅对目标磁贴实例管理任务，实际可见性和停止条件须真机验证。
- 优先替换移动数据磁贴标题；如果目标布局标题固定、截断或实际由插件另行渲染，根据 APK 和截图确定可用副标题位置并记录，不新增独立磁贴。
- 不改点击、长按、开关值、图标和 SIM 选择逻辑；无障碍说明保持原状态语义且不重复拼接。
- 查询前固定起止时间、日期、时区和请求代次；提交结果时再次验证。跨日、改时间、改时区或视图销毁后的过期结果不得覆盖当前标签。
- 异常或结果无效时恢复原标签，不把错误显示为 `0 B`。旧日缓存不能继续作为“今日”显示。
- 节流使用单调时钟，日界线使用本地日期计算，不用“当前时间减 24 小时”。
- 无卡、飞行模式、双卡切换时保留系统交互；统计为全部移动网络，不承诺仅代表当前数据卡或运营商计费值。

用户已启用 HyperCeiler。其备份中没有可明确识别为今日流量的已启用项目，但版本、默认值和作用域未知。初测保留现有配置，观察双方是否覆盖同一标题；需要对照时只处理已定位的重叠项，不自动修改其他模块配置或用 Hook 优先级竞争掩盖问题。

## 6.1 单条通知锁定与置顶

使用 `notification_item_controls` 开关，首次启用后重启生效；长按菜单中的选择和取消即时生效。作用对象为 `StatusBarNotification.getKey()`，其中包含发布应用、用户、id/tag 等身份；不按渠道、标题或分组 key 匹配。锁定与置顶各限一条，可选同一条或不同条，选择新目标替换旧目标。

- 菜单：在本固件 `MiuiNotificationMenuRow.createMenuViews` 后，复用宿主布局，在“更多设置”左/右插入锁定/置顶；文字随选择更新。菜单引用使用弱引用，避免保留已消失通知。
- 锁定：对准确命中的通知行返回不可滑动清除。若折叠分组包含该行，限制整组滑动以保护它；展开后其他通知仍按原规则处理。应用撤回与系统移除不拦截。
- 独立置顶：仅在 SystemUI 中把目标 SBN 的 `isGroup()` 视为 false；本固件原生 `groupNotifs` 会将其作为 `ROOT_ENTRY` 下的独立项。同类后来通知保持原分组。取消置顶后恢复原返回值，再重建列表。
- 排序：在原生 sectioners 最前增加一个只匹配该条 `NotificationEntry` 的 section，不把整个分组置顶；为当前/刚取消目标放行原生分组、section 和重排稳定性检查。其余通知保留原生规则。
- 状态：两个通知 key 保存在 SystemUI 的模块专用私有偏好中，模块共享配置仍只存功能开关。`NotifCollection.tryRemoveNotification` 真正返回移除成功后清理相应选择；应用对同一个 key 的内容更新仍视为同一条通知。
- 加载：桥接 DEX 的父加载器使用 SystemUI；宿主签名占位类仅参与编译。此处只影响 SystemUI 进程，不增加系统框架作用域。

本机 APK、57 个 DEX 成员和桥接打包检查是静态/构建证据；通知真实分组、手势、无障碍与 HyperCeiler 共存仍需要真机验证。

## 7. 系统 OTA 屏蔽流程

将入口呈现与更新执行分开验证，不能用“没有通知”代替“没有发起更新”。首期只覆盖本机 ROM OTA 客户端，不建立设备级域名封锁或 Root 防火墙。

当前 ROM 无系统更新器，设置样本仍有 `miui_updater` 等引用。先处理可确认的残留入口；特定客户端请求组在确认包不存在后标为不适用，不阻塞其他功能，不声称服务器封锁成功。若发现独立 OTA 组件则另行分析；日后装回未知客户端时必须重新适配。

| 层次 | 阻断位置 | 必须保留的行为 |
| --- | --- | --- |
| 入口 | 设置控制器、关于手机卡片、搜索提供方/结果、直接跳转入口 | 关于手机和版本信息、其他设置条目 |
| 提示 | OTA 自身通知/弹窗生成处、已核验的红点数据源 | 非 OTA 通知，尤其不能误拦其他前台服务通知 |
| 检查 | 手动、启动、定时任务进入网络请求前 | 按原任务契约结束回调，避免转圈和无限重试 |
| 下载 | 客户端下载请求、委托下载服务或 `update_engine` 之前 | 无关下载与系统服务工作 |
| 安装 | 客户端本地包/在线包安装入口、跨进程提交之前 | 普通重启、应用安装与非 OTA 的恢复功能 |
| 网络 | 已识别 OTA 进程中的 HTTP 客户端、URL 等实际发送路径 | 不在 `system_server` 或其他共享进程中全局断网 |

入口依据 component、action、Preference key 和资源标识精确匹配，不能按“更新”文本或模糊包名前缀删除。搜索缓存刷新与外部入口也列入适配清单；未经验证的面不得算入“所有入口已屏蔽”。

网络层作为补充：先阻断业务任务与委托，再阻断已识别 Java 网络发送路径。仅在证明进程专用于 OTA 后，才考虑像源项目一样阻断该进程全部 HTTP/TCP 连接；共享进程须限定请求归属。记录实际端点、协议和调用方，不预填网上域名，不使用全局 `Socket.connect` Hook。

Android 的 A/B 更新中，客户端可以将 URL 交给独立 `update_engine` 执行，因此只 Hook 更新器的 HTTP 方法不足以阻断安装。需在委托前阻断；已进入引擎的更新不自动终止，也不篡改 slot 或引擎状态。[AOSP A/B 更新流程](https://source.android.com/docs/core/ota/ab)

拒绝后的返回值、回调和任务完成语义必须从目标代码确认。优先走原生取消/受限路径；无该路径时才选择已经验证的结束方式。不能直接返回任意 `null`、假装安装成功，或修改系统构建号和安全补丁日期。保留原权限检查，过滤成功结果不能绕过鉴权。

只取消已识别的存量 OTA 通知，不调用全包 `cancelAll`；不清空更新数据、不禁用更新包、不修改 hosts。后台任务不应因阻断而反复唤醒。开关关闭并重启后恢复原请求链，但已被取消的通知或任务不会自动重建，需由系统正常再触发。

## 8. 恢复出厂入口隐藏

按用户最新要求，本期只处理界面入口，不拦截恢复出厂执行，不注入系统框架，也不需要补齐厂商擦除库。

- 普通条目：保持对 `factory_reset_key` 的可见性过滤。
- 推荐条目：已核验 Settings 的 `RecommendUIHelper.getLinkData(RecommendItem)`；在调用前检查配置 Intent，调用后检查最终链接 Intent，仅过滤 `com.android.settings.SubSettings` 且 fragment 为 `com.android.settings.MiuiMasterClear` 的目标。这样同时覆盖已知静态条目与搜索索引替换跳转的情况。
- 该目标返回空链接时，原 `addRecommendItem` 返回 false，不添加该推荐。其他推荐保持原对象、顺序和点击行为；不按中文标题匹配，不隐藏整个“在查找其他设置吗？”分类。
- 开关关闭不安装推荐过滤；该开关仍保存后重启生效。搜索及其他直达页面没有完整覆盖，需要继续按证据扩充入口，而非添加执行保护。

先前关于广播、FRP、存储和厂商 Recovery 的研究保留在静态分析文档中，仅作为历史材料；不再是本阶段实施门槛或功能承诺。

## 9. 故障、恢复与诊断

- 热路径仅使用已解析成员与内存配置；后台查询、文件读取、网络操作不得放入 SystemUI/system_server 主线程 Hook。
- 外层捕获安装错误并隔离功能，不能用大范围吞异常掩盖部分安装。回调中的异常处理按宿主契约设计；运行时拦截命中和错误均限频记录。
- 日志采用 `[HYPERNOVA][feature][process][profile]` 前缀，记录版本、目标签名、安装/命中/跳过原因；不记录 IMSI、账户、完整 Intent extras 或包含令牌的 URL。
- 正常恢复：停用相应开关并重启，或在 LSPosed 停用模块并重启。不依赖修改系统文件才能恢复。
- 当前不加载系统框架 Hook。停用与恢复按用户实际 LSPosed 分支验证，不给未经验证的通用按键或目录操作。
- 首版不实现自动修改框架数据库、自动删除文件或自动更改作用域的“救砖”逻辑。

## 10. 架构验收条件

只有以下事实分别成立，才能报告功能完成：固件匹配、配置跨进程可读、目标进程已注入、必需 Hook 安装成功、实际调用命中、正向和不受影响的反向场景通过、停用恢复通过。编译成功、DEX 匹配、模块主页显示激活都不能单独替代这些验证。

## 11. 0.2.5 日历与天气广告扩展

新增 `com.android.calendar`、`com.miui.weather2` 主进程及各自独立开关，沿用后台配置读取、完整固件和 APK 校验、精确成员解析和安装失败回滚。界面在相应包名分类生成选项，不新增跨进程配置服务、权限或全局网络拦截。

日历使用原生推广关闭分支，拦截两个专用广告请求并抑制缓存广告卡；天气拦截预报及首屏专用广告请求，在共用卡片数据读出时过滤广告类型，并隐藏详情广告容器。共用内容接口不整段拦截，不能承诺其响应完全不含广告 JSON。具体成员、数据／展示链、生效时机及设备验收见 [广告适配记录](adaptation/calendar-weather-ads.md)。

## 12. 0.2.6 收回手势与小窗按钮

`SidebarSwipeHook` 只在手机管家 `:ui` 进程识别已展开应用列表上的向外滑动。纯状态类区分方向、滚动、长按和多指，接管后取消子点击，松手委托原生收回；原生动画的开始／完成状态与隐藏触发条、辅助窗口恢复协调，不增加屏幕边缘触摸窗口。

`FreeformCloseHook` 只在 SystemUI 维护普通小窗的任务子 Surface 和局部按钮 ViewHost，复用宿主原生关闭并跟随装饰生命周期回收。与原三点窗口各自保持点击区域；不增加应用进程或系统框架作用域。两个新开关独立、默认关闭，分别列在手机管家和系统界面分类。

## 13. 0.2.10 蓝牙自动重连

Settings 的 `BluetoothSettingsHook` 在 MIUI `DeviceProfilesSettings` 与通用 `BluetoothDeviceDetailsFragment` 注入逐设备开关和次数。SystemUI 的 `BluetoothReconnectHook` 持有应用级 Context、工作线程、系统事件接收器、配置观察器和有限期限的 AlarmManager 任务；设备重试状态由移植的纯 Java `RetryRound` 驱动。两个宿主都核验自身及对端 APK，再解析必需成员，不增加蓝牙服务注入。

全局开关仍使用模块共享偏好；设备开关／次数与手动暂停会话由这两个已有权限的宿主读写专用 `Settings.Secure` 键。暂停先于原生手动断开发生，采用启动次数与蓝牙会话标记区分重启；不注册模块 Provider，不启动模块应用。配置读取失败停止后续请求，后台安装失败撤销新增 Hook 并释放接收器、观察器、定时器和工作线程。

该功能不修改系统原有自动连接或音频配置策略，不把连接请求返回成功视为设备已经连接。详细接口证据、运行边界与设备验收见 [蓝牙适配记录](adaptation/bluetooth-reconnect.md)。

0.2.11 将成功判据从平台 ACL 状态改为宿主 `CachedBluetoothDevice.isConnected()`／`isBusy()`，并观察 `onProfileStateChanged`。`RetryRound` 增加稳定确认状态，连续 5 秒后才成功；短暂连接继续原次数，稳定连接断开后冷却 10 秒。等待和确认期间每秒复核，稳定后由事件驱动；系统处于连接／断开中时推迟模块请求。专用日志用进程内设备编号记录请求、配置事件和状态转换，保留原有配置及手动暂停协议。

## 14. 0.2.12 自动省电

`feature/power/PowerSaveSettingsHook` 自 0.2.13 起在手机管家主进程的 `PowerSaveFragment.onCreatePreferences()` 后追加 HYPERNOVA 分类，在首次列表绑定前准备控件；`onResume()` 前将该实例列表的 `ItemAnimator` 设为 null，结束并停用条目移动动画，之后刷新配置且按 key 防重复。用该宿主的 `CheckBoxPreference` 与 `SeekBarPreference` 显示开关和条件阈值，将百分比写入标题以兼容宿主不显示滑动条数值／摘要的布局。此 APK 将监听接口及滑动条成员混淆，固定固件声明与 DEX 定位记录其原始签名，不复用 Settings APK 的类名假设。

0.2.14 在模块创建的滑动条上标记一个标题 View 的弱引用，绑定时更新引用，原生 `SeekBarPreference.n(int)` 更新数值后直接刷新当前标题。拖动时不调用 `Preference.setTitle()`，避免通知适配器重绑并重置进度；不启用连续持久化，仍由原生松手回调保存。原生回退数值时同步恢复标题，其他滑动条没有该标记，不修改其标题。

`AutoPowerSaveHook` 仅在 Manifest 声明的 `com.miui.securitycenter.remote` 安装，工作线程监听系统电池广播和专用配置通知；通过原有 `PowerSaverProvider/changePowerMode` 开启普通省电，保留原生策略和通知流程。页面与后台共用原子 `Settings.Secure` 配置。新增省电路径核验手机管家本身的完整哈希、版本和 splits，不增加模块服务、权限或作用域包。

`PowerSaveRule` 只处理一次触发与重新允许触发：未接电、严格低于阈值、当前未处于普通／超级省电时请求开启；手动关闭后本轮保持关闭。接电或电量恢复到阈值后重新允许下一次触发。后台保留收到的恢复事件，并在实际请求前读取最新电池状态，分别避免快速插拔遗漏和过期广播在充电时触发。配置连续通知合并处理，无定时轮询。使用、生命周期边界和验收见 [自动省电适配记录](adaptation/auto-power-save.md)。
