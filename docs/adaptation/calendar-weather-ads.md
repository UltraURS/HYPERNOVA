# 日历与天气广告适配

0.2.5-dev，状态为静态分析与本地验证，尚未在设备检查广告消失或抓包证明请求阻断。仅适配用户提供的两个 APK；原文件及反编译源码不随模块分发，哈希和 DEX 定位在固件证据 JSON。

| 应用 | 包名 | versionName / versionCode | SHA-256 |
| --- | --- | --- | --- |
| 日历 | `com.android.calendar` | 17.0.6.26 / 17000626 | `bd5f85837e95cb80c54bebc598814ee4166002101a1d26b7193698192fb0de26` |
| 天气 | `com.miui.weather2` | 17.0.3.15 / 17000315 | `a8af7c8494c4c9ad882ad96adb96c2779ac60b7bbbb7f0d176e566d4c7469ec1` |

各一个开关，默认关闭，分别归属日历和天气分类；LSPosed 增加两项对应作用域。只处理各自主进程，不注入日历 widget、天气 pushservice、系统框架或其他应用。保留现有完整 fingerprint、SDK、版本、文件哈希与无 split 条件，不按版本名猜测混淆方法。

## 日历

截图中的月视图广告由广告卡 type 20 展示，属于独立推广链。

| 接入点 | 已核验原生用途 | 模块行为 |
| --- | --- | --- |
| `com.android.calendar.settings.y.h(Context)` | `key_content_promotion` 的读取入口 | 返回 false，走原生推广关闭分支；不写原偏好 |
| `AdsExtraManager.f(Context)` | 调用 `getAdCardsData`，请求 `adcard/get` | 在创建／入队前返回 |
| `AdsExtraManager.g(Context,long,String)` | 调用 `getAdData`，请求 `ad/get` | 在创建／入队前返回 |
| `AdsExtraManager.d(int)` | 从主卡与额外缓存选取广告 | 返回 null |
| `AdPresenter.isRemoveCard()` | 决定广告卡从列表删除 | 返回 true，由原控制器移除整卡 |

`CardFactory.createCardPresenter` 在 type 20 下检查 `y.h`，关闭时返回 null，调用者已有 null 分支，不会创建广告 Presenter。旧 `AdSingleCard.j()` 同样检查该推广开关，子类沿用此判断。Summary 的推广分支也读取此入口。

日程、节日等卡片共用 `card/list`，因此保留该请求与整份普通内容。原生签名参数生成过程读取 `y.h`，按推广关闭状态生成 `ad_status=0`；不自行重写已签名参数。专用 `adcard/get` 与 `ad/get` 则直接拦截调用方，不能把仅关闭个性化广告当作停止请求。

安装完成后读取已有 `AdsExtraManager.e` 静态实例，对两个专用请求字段 `a`、`b` 调用宿主 `retrofit2.Call.cancel()`；不主动创建单例，不取消普通日历请求。取消异常单独记录，不撤销已经安装的保护。缓存不删除，而是在读取／创建／展示处抑制，因此关闭开关并重启后恢复原生路径。

## 天气

截图中的 15 天天气预报广告由 `DailyDetailAdView` 展示，和正常预报加载链分开。

| 接入点 | 已核验原生用途 | 模块行为 |
| --- | --- | --- |
| `q1.e.j(String,int)` | 调度预报详情广告任务 | 不调度 |
| `w1.d.a(Map,Callback)` | 调用 Retrofit `/wtr-v3/ad/forecast` | 不创建／入队广告请求 |
| `I1.a.f(Context,int,int,String,String)` | 请求首屏 `/wtr-v3/ad/speaker` | 返回 null，不访问网络 |
| `H1.b.b(String,Context)` | 解析首屏广告并报告 LOAD | 返回 null，抑制缓存／迟到广告响应 |
| `InfoCardBean.getList()` | 共用内容与缓存的卡片节点 | 过滤广告类型 7／10，保留普通节点及顺序 |
| `DailyDetailAdView.onFinishInflate()` | 初始化详情广告控件 | 初始化完成后将整个广告 View 设为 GONE |
| `DailyDetailAdView.m(InfoDataBean,DailyForecastAdData)` | 绑定详情广告、加载素材、报告曝光 | 隐藏整个容器并阻止绑定 |
| `com.miui.weather2.tools.u.a/b(...)` | 专用广告事件报告 | 不执行 |

`BaseInfo.TYPE_AD_NEW` 为 `7`，`TYPE_ALERT_AD_NEW` 为 `10`；按类型过滤，不按“红果”或标题文本匹配。`n1.i.f(InfoCardBean)` 的原生逻辑对空列表直接返回，所以纯广告卡不创建外层卡片；混合卡仅保留正常内容。广告下载、图片素材与曝光链在绑定前被截断。过滤返回新列表，不修改原始节点、缓存列表或持久数据；无广告时保留原列表。

首页内容接口 `/v1/page` 同时供应普通内容和广告，保留整次请求，仅在读取结果时过滤广告节点。**这不能保证共用响应的 JSON 中没有广告数据**；当前实现阻止其广告卡绑定、素材加载和展示。城市、天气预报、空气质量、生活指数及普通内容请求不按整域名拦截。

## 生效与限制

Hook 在宿主 Application.attach 后读取配置、完成 APK 校验，再安装到精确接入点。不能保证校验完成前没有已发出的请求，也无法收回已经发送的字节；日历尝试取消已有的两个专用 Call，天气对迟到绑定／解析继续抑制。启用、停用或升级后应重启手机，再做冷启动验证。

不修改 hosts、DNS、防火墙或系统文件，不禁用整个网络栈，不关闭日历／天气应用的联网能力。每组安装前解析全部必需方法／字段，安装失败撤销本组 Hook并记录错误，不影响另一应用。

本地新增 5 项广告过滤测试：混合缓存的顺序及原列表不变、纯广告卡清空、未知类型保留、节点身份和 null 保留、空列表处理。与固件声明核验、打包和构建检查共同验证实现，不能代替设备验收。

## 设备验收

1. 两个开关分别启用、分别关闭、同时启用，检查作用域与 `calendar-ads INSTALLED`／`weather-ads INSTALLED` 日志；不同 APK 应跳过而不是盲目安装。
2. 日历离线冷启动、已有广告缓存、月份／日期切换、刷新后广告卡不显示，不留下广告空卡；日程增改、提醒、节日和其他卡正常。
3. 天气首页和 15 天预报详情，进入、返回、切换城市、刷新、离线缓存后无广告卡或残留占位；预报、空气质量、生活指数和普通内容正常。
4. 分别触发专用广告入口并观察请求，确认 `/calendar/adcard/get`、`/calendar/ad/get`、`/wtr-v3/ad/forecast`、`/wtr-v3/ad/speaker` 未新增请求；共用 `card/list`、`/v1/page` 继续可用。仅离线或没展示广告不足以证明网络拦截。
5. 确认无持续加载、忙循环、异常重试，关闭开关并重启后原生行为恢复；与当前 HyperCeiler 配置共存时检查重复处理。
