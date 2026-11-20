# 小窗右上角关闭按钮

更新：0.2.9-dev，2026-11-17；功能始于 0.2.6-dev。基于用户提供的 SystemUI 20240808.0 / 202408080，沿用其固定 SHA-256 和完整构建匹配。当前只有静态分析、单元测试及本地打包检查，尚未验证设备上的窗口位置与输入分发。

用户确认 × 沿用系统原生小窗“关闭”：退出当前小窗并将任务转到后台。

## 宿主与原生关闭

普通小窗的三点装饰由 `com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.MiuiTopDecoration` 管理，归属 SystemUI 主进程，不需要在每个小窗应用中注入。

原生 `MiuiCaptionButtonClickListener.onClick(View)` 对三点菜单关闭项调用 `handleCloseClicked(MiuiWindowDecoration)`。普通小窗分支随后执行 `MulWinSwitchTaskOperations.minimizeTask(RunningTaskInfo)`，通过原 WindowContainerTransaction 和过渡执行器退出小窗、将任务转到后台，并保留原生状态通知。

新增按钮调用同一 `handleCloseClicked`。调用前重新确认当前任务仍存在、仍为普通小窗、可见，并保留原 `isInLockTaskMode()` 限制；请求发出后禁用按钮，防止连续点击。关闭菜单时也走原 `closeHandleMenu(true)`。

## 按钮窗口

不扩大小窗原三点装饰的窗口或触摸范围。`FreeformCloseHook` 为每个普通小窗创建一个 `SurfaceControlViewHost`，其容器作为当前 `mTaskSurface` 的子节点；使用宿主的 Builder、ViewHostFactory 和 WindowlessWindowManager 配置方式。父任务负责移动、缩放、动画、层叠及隐藏，不创建全屏悬浮窗或申请悬浮窗权限。

0.2.9 的局部窗口与 × 的外接正方形同大，显示侧约 7.48 dp，不额外扩大点击区域；图标线端和笔画包含在该边界内。尺寸依据 `MiuiFreeformModeTaskInfo.mDestinationScaleX` 反算任务坐标，取整到任务像素后再设置 ViewHost 宽高及 Surface 裁剪，缩放时保持原有视觉大小。图标为代码绘制的两条圆头交叉线，颜色取原生顶部装饰的黑／白颜色并保持可读透明度；提供“关闭小窗”无障碍说明。

位置不再叠加固定的顶部和右侧边距。读取 `MiuiBaseWindowDecoration.RelayoutResult.mCaptionY` 与 `MiuiDotView.getDotY()`，两者相加作为任务坐标内原生 ··· 的布局中心纵坐标。设图标边长为 `s`、该中心为 `cy`，则上侧留白 `g = cy - s / 2`，按钮位置为 `(任务宽度 - s - g, g)`，实现中心水平对齐及上、右留白相等。保留半像素位置，避免奇数边长取整后偏移；位置使用物理右侧，不随 RTL 换边。原生重排及既有缩放回调后重新计算，无法放入任务边界时不创建点击窗口。

0.2.6 使用 36 dp 点击区域；0.2.7 将其中图标的线长和笔画粗细同比缩小 50%。0.2.9 保留缩小后的图标视觉大小，同时按用户最新要求收缩点击窗口并调整位置。原生关闭行为继续沿用。

只展示在默认屏幕、windowingMode 5、原装饰存在且任务仓库判定 `isNormalState()` 的可见小窗。迷你化、全屏或原装饰释放时回收；尺寸不可用或太小时不创建点击区域。调整大小期间暂时隐藏，缩放回调后重新定位。三点装饰进入淡化状态不会让独立 × 跟着失去点击能力。

`MiuiTopDecoration.relayout(...)` 后与原生事务一起更新位置、裁剪和层级；`updateVisibility(...)` 同步可见性；`releaseViews()` 前释放新增 ViewHost、移除容器并释放 SurfaceControl 句柄。隐藏时同时把局部窗口设为不可触摸；重建或换父 Surface 前先释放旧实例。新增状态绑定装饰实例，按钮通过弱引用读取当前装饰，不维护永久任务列表。

必需方法及字段先全部解析，再安装 Hook；安装失败撤销本组。单个新增窗口创建失败只回收该新增窗口并记录错误，保留原生装饰。没有增加系统框架／应用进程作用域，也没有修改系统文件。

## 本地验证与设备验收

5 项纯 Java 位置测试覆盖与原生三点布局中心对齐、顶部与右侧留白相等、多个缩放比下的图标／点击窗口大小、任务及标题中心更新、奇数像素边长、非法及过小尺寸。新契约在旧实现上 5 项均失败，调整后通过。`mCaptionY` 和 `getDotY()` 已替换旧位置计算所需的 `mCaptionOffsetY` 声明，加入 `tools/verify-targets.py` 及固件证据 JSON；这不等于已经验证真实 Surface 输入。

设备应验证 T37：多小窗分别关闭、三点菜单及拖动缩放、横竖屏、深浅主题、迷你化／全屏／返回小窗、任务隐藏与重开、锁定任务模式、停用重启恢复。检查没有重复 ×、残留透明触摸区域、错误任务关闭或 Surface／ViewHost 持续累积。
