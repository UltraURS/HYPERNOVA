# 返回手势呼出侧边栏

更新：2026-11-17。目标为 Redmi Note 12T Pro / HyperOS 3.0.3.0.VLHCNXM / Android 15。

**状态：用户反馈侧栏应用点击会同时触发底层 QQ 条目。0.2.8-dev 补齐原生点击穿透标志清理；实际 Hook 源码的 JVM 回归已复现旧流程遗漏并通过修正，设备上的交互隔离仍待确认。**

## 交互与范围

- 模块内选项为“屏幕边缘返回手势呼出侧边栏”，默认关闭。
- 停留阈值 100～500 ms，默认 300 ms，滑块步进 10 ms。开关和阈值保存后重启生效。
- 从系统返回就绪震动回调开始计时，默认 300 ms 后切换图标，不再要求最大位移。没有原生回调时不会凭位移或按下时间启动计时；同一手势的重复回调不延长时限。
- 到期时将本次返回箭头临时替换为独立绘制的侧边栏图标，同时调用桌面的原生返回就绪触感接口一次，区分计时起点和侧边栏就绪；松手才打开面板。
- 计时中或就绪后向边缘回划达到系统 touch slop 时取消本次操作，再向内也不重新就绪。计时中退出原生返回位置也取消。抬手位置同样检查回划，避免漏掉最后一段移动。多指、系统取消、失去可触摸状态、旋转或分离视图也会取消。
- 左右两侧都使用原生面板和同一应用列表，按实际物理方向选择面板，不修改原生手柄位置配置。右侧没有额外常驻手柄。
- 需先在系统设置中启用侧边栏，并允许当前应用显示。保留系统当前游戏／视频／普通侧边栏模式和显示限制，不强制打开被系统禁用的面板。

## 两个新增样本

| 文件 | 包名、版本 | SHA-256 |
| --- | --- | --- |
| `apks/系统桌面.apk` | `com.miui.home`，RELEASE-6.01.03.1943-01051706 / 601031943，2 DEX | `36323641c5af2bc0178fb30dad28b6e8e6a35a3aee98e51a2384e84d3e22981f` |
| `apks/手机管家.apk` | `com.miui.securitycenter`，12.1.3-251205.0.1 / 40001213，4 DEX | `9b532d84e0206b3e1bc42ee9bdb14840b45e637becbdf84dd4a4f2577c06ddf4` |

两端启动时均在工作线程校验完整构建和这两份 APK 的版本、SHA-256；未核验 split 不放行。运行时不扫描全部类，不在触摸或绘制回调读取文件。

## 手势与取消链

原生链为 `GestureStubView.onTouchEvent` → `GesturesBackTouchProcessor.onPointerEvent` → `processPointerEvent` → `GestureStubView` 内的 `GesturesBackCallback`。实际返回／返回动画触发在 `onSwipeStop(true, ...)` 分支，不在触摸抬手的上游直接执行。

`SidebarGestureHook` Hook `onPointerEvent(MotionEvent, GestureStubView)`，只有原生识别为 `mSwipeStatus == 2`、视图可触摸且未转交排除区域才参与判断。使用原生 `mDownX` 和 `mGestureStubPos` 计算向内距离，调用 `GesturesBackTouchProcessor.isShowBackArrow(float)` 保留原生返回位置判断；这个条件本身不启动计时。

已核验的震动链为 `GestureBackArrowView.startArrowAnimating(true, 100)` → `GestureBackArrowView$1.onAnimationSuccess(Animator)` → `HapticFeedbackCompat.performGestureReadyBack()` → `mArrowFeedBackDone = true`。Hook 该匿名监听器的成功回调：仅当所属箭头的标志由 false 变为 true、会话仍有效、屏幕未锁定且手指仍在返回位置时，记录 uptime 并进入 HOLDING。隐藏动画、重复回调、抬手或取消后的回调均不启动新的计时。该时刻对应原生震动请求，不通过麦克风、传感器或全局 Vibrator Hook 检测物理震动；系统触感设置仍由系统决定。

纯状态流程为 `IDLE → TRACKING → HOLDING → READY → FINISHED`；HOLDING 之前不累计时长，回划或中断进入 CANCELED。0.2.1 使用原生动画饱和值（理论内滑约 1008 px）作为起点，用户反馈无法呼出后已按最新要求移除此门槛。运行时不再解析 `BackGestureUtils`，没有套用 ColorOS 的距离公式，也不覆盖全屏触摸窗口。

定时器与 MotionEvent 使用 uptime 同一时间基准；抬手时以 UP 事件自己的时间再次判断，避免 UI 线程卡顿导致定时器先就绪、把实际早已松开的短划误判为呼出。未到阈值的普通 UP 仍交给原生返回。

就绪后的 UP、计时或就绪阶段的回划会向原生处理器传入独立复制的 `ACTION_CANCEL`，使 `onSwipeStop(false, ...)` 调用 `onBackCancelled()`，既不注入返回键也不进入返回完成分支。原事件不修改；回调结束恢复参数并回收复制事件。只有原生取消调用正常完成才请求打开面板；未到阈值的正常 UP 仍执行普通返回。

`GestureBackArrowView.onDraw` 就绪时临时替换 `mArrow` 及宽高字段，绘制结束后全部恢复。旧版沿用原始 18×36 箭头画布，导致图标窄小；0.2.4 以原箭头高度为边长生成方形图标，采用圆角面板、实心侧条和两条短线，左右镜像，继承原箭头颜色和原生画笔透明度。原资源变化后重新生成图标。停止手势、配置变化、不可触摸和视图分离会移除计时器并恢复原图标。Hook 安装中途失败会撤销该组已装 Hook。

纯状态机的 `advance()` 只在首次进入 READY 时返回 true，因此定时器和移动事件不会重复触发就绪震动。到期通过 `HapticFeedbackCompat.getInstance().performGestureReadyBack()` 请求触感；触感失败单独记录，不取消已经就绪的手势。实际触感强度和设备表现仍需真机确认。

## 原生左右面板与通信

Settings 的 `MiuiDockUtils` 查询安全中心 remoteprovider 只能证明能力归属，不能作为打开接口。实际面板位于 `com.miui.gamebooster.service.DockWindowManagerService`，Manifest 指定进程 `com.miui.securitycenter:ui`，exported 服务要求 `com.miui.securitycenter.permission.ACCESS_SECURITY_CENTER_PROVIDER` 签名权限；系统桌面 Manifest 已申请此权限。

桌面向该显式组件发送模块专用 `io.github.hypernova.OPEN_SIDEBAR` Intent，附 side 和系统 `ResultReceiver`。Android 原有服务权限检查保持生效；不修改 provider、权限判断或服务 exported 状态，不添加系统框架中继。

回调在装入 Intent 前经 Parcel 转成系统 `ResultReceiver` 实例，其他宿主无需加载模块的匿名回调子类；仍通过原 Binder 回到桌面线程。该处理依据 [Android 15 ResultReceiver](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android15-release/core/java/android/os/ResultReceiver.java) 和 [Parcel](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android15-release/core/java/android/os/Parcel.java) 的序列化契约。

`SidebarPanelHook` 只在 `:ui` 进程处理该 action，其余服务启动请求保持原生行为。处理流程：

1. 检查屏幕交互、锁屏和服务当前激活状态，调用原生 `A4.n.L1()` 保留 dock 模式、显示与解锁限制。
2. 原生管理器的 `e`、`f` 都是已加入 WindowManager 的 `com.miui.dock.sidebar.j` 包装器；`H()` 表示当前位置。按请求方向选择主或相反侧对象，不假定原手柄永远在左侧。
3. `A4.n.a2()` 在普通模式会把辅助对象 `f` 的面板窗口 `z()` 和触摸覆盖窗口 `u()` 同时设为 GONE。保存两者原有可见性并临时设为 VISIBLE，等待面板 pre-draw，核对其子手柄 `w()` 已完成布局且位于请求的物理一侧，再按原生 `P1()` 顺序执行 `Q()`、`A1(target, completion)`、`Q1(target)`；0.2.8 在 `Q()` 返回后补调用 `X0(target)`，承担原生触摸松手时的点击穿透清理。已打开的同侧请求不重复创建内容。
4. 原生窗口 `b0/f0` 根据 `H()` 选择左右 gravity、偏移和动画；`TurboLayout.r` 传入左右参数。普通应用列表由 `newbox.e` 读取共享的 `N2.v.e().d()`，不另建右侧列表。
5. 原生动画 completion 回调后，等待 `TurboLayout` 有内容、alpha 大于 0.5、可见矩形非空且确实位于请求一侧。0.2.8 在成功回执前再次调用 `X0(target)`，覆盖已经打开的同侧请求，再回传 `ACCEPTED`。服务端 2 秒内未满足条件则关闭并回传不可用；桌面仍保留 3 秒未响应提示。不再用“已附着”冒充面板显示成功，也不无限重试。
6. 原生关闭经 `j.U(false)` 清除打开状态时恢复两个窗口的原有可见性。请求中途失败也关闭未完成面板并恢复状态；同一管理器已有打开请求时拒绝重入，不创建右侧常驻手柄，不修改原手柄位置或系统 dock 模式。

静态分析发现：原生动画 `com.miui.dock.sidebar.a.d()` 读取 `w().getLocationOnScreen()`，右侧的终点计算依赖该坐标。如果刚将 GONE 窗口恢复可见就立即打开，布局尚未完成，可能使用旧坐标或 0，导致终点落到屏幕外。这与“左侧正常、右侧无面板且旧回执仍成功”的反馈相符，但属于代码分析推断，未取得设备日志证明唯一根因。

代码未自动开启系统侧边栏，也未放宽锁屏、当前模式和应用显示限制。

## 作用域、来源与验证

### 隐藏原生触发条

0.2.4 新增独立开关“隐藏系统侧边栏触发条”，默认关闭，仅归属手机管家。开启后不写系统侧边栏启用设置，不停止服务、不移除原生窗口，也不修改系统桌面返回手势的监听、事件或排除区域。

`SidebarTriggerHook` 在 `A4.n.L1()` 和服务启动后接入左右包装器，跟踪各自 `u()` 触摸覆盖窗口及 `z()` 面板窗口。`F() == false` 时，在 WindowManager 参数层设置 `alpha = 0` 和 `FLAG_NOT_TOUCHABLE`；窗口保留布局尺寸、坐标及测量能力，既不显示原生触发线，也没有可交互的透明边缘热区。只修改这四个已知原生窗口，不全局 Hook WindowManager。

这里使用窗口透明度，而非仅将触发线 View 的 alpha 设为零：[Android 官方窗口参数说明](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE) 将完全透明的窗口列为触摸可穿透的情形，避免留下遮挡底层应用／返回手势的窗口。此依据不代替实际设备的边缘交互测试。

另外核对了 [Android 15 的手势排除区域计算](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android15-release/services/core/java/com/android/server/wm/DisplayContent.java)：带 `FLAG_NOT_TOUCHABLE` 的窗口被跳过，不继续占用边缘手势排除区域。这为保留普通返回提供平台依据；本模块没有清空桌面或前台应用自己的排除区域，也不修改系统框架的该计算。

0.2.5 根据“呼出后无法交互”的反馈，修正展开准备的时序：原生 `Q()` 先调用 `U2.h.k(..., true)`，经 `B1.H` 清除 `FLAG_NOT_TOUCHABLE`，再创建内容并调用 `U(true)`。0.2.4 只看 `F()`，在标志仍为 false 的布局更新中重新加回不可触摸位，之后又恢复了原生收起窗口本来就带有的该位，导致可见面板无法接收触摸。

现在进入 `Q()` 前先标记“展开准备中”，恢复模块保存的窗口参数，并在该调用期间暂停收起遮罩。原生解除触摸限制的布局更新因此可以生效；`Q()` 返回或异常时都清除准备标记，再按真实 `F()` 刷新。展开成功后保持原生触摸参数，失败时重新隐藏，关闭经 `U(false)` 再次隐藏并禁止接收触摸；其他原生 flags 保留。`A4.n.Z1()` 后续更新布局也使用该准备状态。服务端成功回执额外要求面板窗口 alpha 大于 0.5 且没有 `FLAG_NOT_TOUCHABLE`，不再仅凭内容可见回报成功。

`tools/verify-sidebar-window-lifecycle.py` 用实际 Hook 源码和 JVM 宿主替身复现上述原生顺序。取消新准备处理时，会在“expanded panel must receive touches”断言失败；修正后覆盖两个包装器各三次展开／关闭、布局更新、异常恢复与重试，并检查无关窗口不被改变。它验证了窗口参数时序，不代表设备点击、滚动或普通返回已经验收。

隐藏时没有把 View 设为 GONE，因此返回手势呼出过程仍能等待手柄测量和正确的屏幕坐标。该功能不增加 `ACTION_CANCEL`、返回键或触摸消费逻辑；未到阈值的短划沿用既有返回手势路径。需要验证 T32 的普通返回、边缘点击／滚动、呼出及关闭后状态，不能仅凭透明参数宣称触摸验收通过。

新增作用域为系统桌面与手机管家。`ModuleEntry` 仅进入 `com.miui.home` 主进程和 `com.miui.securitycenter:ui`；侧边栏开关关闭时不安装这两端 Hook。SystemUI、Settings 及原有功能保持各自配置。

状态机、定时调度和临时替换图标的做法来自同级 Connect Colours；保留 GPL-3.0-only 与来源声明。ColorOS 的 Oplus 检测器、距离公式、provider、中继和桌面返回特例均未注册到 HyperOS。

`tools/verify-targets.py` 在 0.2.6 共核验 187 项声明，其中系统桌面 21 项、手机管家 25 项；完整声明列表在该脚本中，固件证据 JSON 保存输入及成员定位记录。临时反编译源码位于 `.local/analysis/home/`、`.local/analysis/securitycenter/`，不随模块分发。

16 项侧边栏呼出纯逻辑测试覆盖 41 个阈值档位、默认／越界、仅系统回调启动计时、移动不重计、重复或迟到回调、单次就绪、计时中及就绪后回划、短划、中断、方向、新触摸重置，以及迟到定时器与早到 UP 的时序。0.2.6 完整单测共 59 项，另有窗口生命周期回归。仍需在设备验证 T24–T29：系统震动后 0.10／0.30／0.50 秒的就绪震动／图标、回划不额外返回、正确侧窗口、关闭后恢复隐藏、共享列表、旋转／锁屏／多指、显示限制、HyperCeiler 共存及按包名分类设置同步。不能用成员匹配或构建成功替代这些验收。

日志依次为 `FEEDBACK side=...`、`READY side=...`、`REQUEST side=...`，手机管家侧为 `LAYOUT_READY side=... handleX=...`、`ANIMATION_DONE side=...`、`ACCEPTED side=... reason=visible`。未能打开时记录 `UNAVAILABLE reason=...` 或错误类型；可区分计时起点、请求、布局、动画及最终可见性，不记录前台应用名或通知内容。

## 0.2.6 向外滑动收回

手机管家分类新增独立开关 `sidebar_swipe_close`，默认关闭。只在 `j.U(true)` 已展开的 `TurboLayout` 上登记识别器，且触摸起点必须位于其 `getDockLayout()` 返回的原生应用列表可见矩形内。没有在收起屏幕边缘增加触摸窗口；该功能可用于原生方式或模块手势打开的侧栏。

识别使用物理方向：左侧向左、右侧向右。位移至少为 24 dp 或两倍系统 touch slop 中的较大值，横向位移须大于纵向的 1.5 倍。先发生纵向滚动、明显内划、长按超时或多指时放弃收回；达到阈值后回划到不足阈值或收到取消事件也不关闭。

Hook 仅在当前 `:ui` 进程的 `ViewGroup.onInterceptTouchEvent`／`View.onTouchEvent` 中检查已登记的目标实例。阈值之前保留原列表分发；拦截时使用框架原生子事件取消机制，随后消费已接管的手势，避免松手点击应用。UP 确认方向后调用原 `A4.n.h1(wrapper)`，沿 `i1` 和原 Folme 动画收回，不另绘动画。

`i1` 在收回动画开始时就设置 `U(false)`，而真正清理／恢复布局在动画回调 `K0` 中进行。为避免隐藏触发条及右侧可见性恢复截断动画，新开关启用时先标记“正在收回”，这段时间延后收起遮罩和辅助窗口 GONE 恢复；`K0` 完成后再隐藏并禁止触摸。`k1` 即时关闭或原调用异常时清除该标记并恢复状态。没有就此修改桌面普通返回事件。

新增 10 项手势测试，覆盖左右方向、阈值、纵向、内划、斜划、点击抖动、长按、多指、取消／回划及重置。JVM 回归现在编译实际 `SidebarTriggerHook` 与 `SidebarSwipeHook`，检查动画期间窗口仍可见、完成后隐藏、即时关闭不遗留标记，保留此前展开触摸参数及异常重试检查。真实动画与触摸仍按 T36 验收。

## 0.2.8 点击隔离修正

原生 `A4.n.O(wrapper)` 创建内容时，先 `U(true)`，随后在对应系统配置开启时通过 `com.miui.gamebooster.utils.I0.d(LayoutParams)` 为面板设置 `miuiFlags` 的 `0x20` 位。`I0.b(LayoutParams)` 的代码及日志将该位明确用于点击向下传递。原生 `com.miui.dock.sidebar.f.onTouch` 在抬手分支调用 `q()`，最终经 `A4.n.X0(wrapper)` 清除该位并更新面板窗口。模块之前只执行创建与动画，没有经过该触摸松手分支；这是静态分析发现的遗漏，与用户反馈相符，尚未取得设备输入日志证明它是唯一原因。

修正仅在模块呼出流程调用原生 `X0(wrapper)`：`Q()` 返回、原生完成内容创建后立即调用，成功回执前再调用以覆盖重复请求。不能在 `U(true)` Hook 中提前清除，因为原生稍后还会添加该位。没有直接设置任意 MIUI 标志、修改其他窗口或添加全屏拦截层；保持原生列表点击、纵向滚动、长按拖动、向外收回及窗口外点击关闭的处理。

必需方法 `LA4/n;->X0(Lcom/miui/dock/sidebar/j;)V` 已加入安装前解析、固定样本声明核验及 DEX 定位证据。总声明数为 188，手机管家 26 项。JVM 回归改为编译实际 `SidebarPanelHook`，在宿主模型中模拟原生于 `U(true)` 之后设置点击穿透位的顺序：修正前在展开断言失败，修正后检查左右侧各三轮呼出、既有面板重复请求、其他 MIUI 标志保留，以及隐藏触发条开关前后的两组路径。原窗口生命周期与动画结束回归继续通过。模型验证标志清理与调用时序，不模拟真实系统输入派发；设备仍需按 T38 验证底层应用没有收到同一次点击。
