# 自动开启省电模式

0.2.12-dev 新增，0.2.13-dev 修正页面显示与条目动画，0.2.14-dev 增加拖动时的实时百分比，2026-11-17。目标为既有 Redmi Note 12T Pro / HyperOS 3.0.3.0.VLHCNXM / Android 15 的手机管家样本，包名 `com.miui.securitycenter`、versionCode `40001213`、SHA-256 `9b532d84e0206b3e1bc42ee9bdb14840b45e637becbdf84dd4a4f2577c06ddf4`。该功能不取自 Connect Colours。

## 页面与使用

在 LSPosed 勾选手机管家，模块“设置 → 手机管家”开启“自动省电设置”并重启。系统“省电模式”页面底部追加 HYPERNOVA 分类：

- “自动打开省电模式”：默认关闭。
- “触发电量：N%”：开启上项后显示滑动条，20%～80%、步进 1%、默认 20%。拖动时标题实时显示当前位置的百分比，松手后保存；保存失败回退到原阈值，重新进入时显示保存值。宿主布局不显示滑动条数值／摘要时仍可从标题读到阈值。关闭上项后隐藏滑动条并保留阈值。

页面内保存立即生效；已经未接电且低于阈值时，开启选项或更改阈值会立即重新评估。触发比较为严格小于，等于阈值不触发；使用原始 `level/scale` 交叉相乘，不依赖整数百分比截断。

按用户确认，自动开启后手动关闭，本轮低电量区间不再强制开启。插拔电源，或电量恢复到阈值后再次下降，重新允许触发；显式修改自动配置也重新评估。不主动关闭已有省电模式、不替换已有超级省电，不修改原生定时开关或充电自动退出设置。其他原生策略仍可能自行切换模式。

## 已核验的宿主路径

| 位置 | 实现与证据 |
| --- | --- |
| 页面 | `com.miui.powercenter.savemode.PowerSaveFragment`，`classes3.dex`；包含截图中的 `close_notification_wakeup`、`auto_exit_power_save_mode`、`key_ontime_enabled`、`preference_key_superpower_autoleave`。在 `onCreatePreferences(Bundle,String)` 后添加，首次绑定前完成；`onResume()` 后刷新，按 key 查找分类防止重复 |
| 条目动画 | 手机管家 `miuix.preference.PreferenceFragment` 为列表设置卡片 `ItemAnimator`；在该 `PowerSaveFragment.onResume()` 前通过继承的 `getListView()` 调用 `RecyclerView.setItemAnimator(null)`，结束并停用本页条目动画，避免原生超级省电行单独移动；不全局 Hook RecyclerView，不改变 Activity 切换动画 |
| 原生控件 | 手机管家自己的 `androidx.preference.PreferenceCategory`、`CheckBoxPreference`、`SeekBarPreference`。滑动条 `(Context,AttributeSet)` 构造；原始 `b:I` 为 min，`i:Z` 为显示数值；`i(int)` 为 max、`j(int)` 为步进、`k(int)` 为当前值 |
| 实时百分比 | `SeekBarPreference.onBindViewHolder(androidx.preference.i)` 绑定前，经 holder 的 `b(android.R.id.title)` 捕获标题 View 的弱引用；原生 `n(int)` 数值更新后直接设置该 TextView。仅模块创建的滑动条有标记；拖动期间不通知适配器重绑、不写配置，松手及保存拒绝沿用原生提交／回退流程 |
| 监听接口 | 此 APK 为 `androidx.preference.Preference$c.onPreferenceChange(Preference,Object)`，不能使用 Settings APK 中未混淆的接口名称 |
| 后台进程 | Manifest 中 `PowerSaveService` 与 `PowerSaverProvider` 均为 `com.miui.securitycenter.remote`，注意是点号，不是 `:remote`。模块只在该进程启动自己的工作线程；无需模块应用常驻 |
| 省电执行 | 原生 `z7.A.I0(Context,boolean,String,boolean)` 与 `PowerSaverProvider.call` 共同确认：`content://com.miui.powercenter.powersaver`，方法 `changePowerMode`，参数 `POWER_SAVE_MODE_OPEN=true`、`POWER_DEVICE_NOTICE=false`；Provider 将操作交给原生工作队列 |
| 模式查询 | `z7.A.T(Context)` 读取 `Settings.System.POWER_SAVE_MODE_OPEN`；`V(Context)` 读取 `power_supersave_mode_open`。模块使用同一键，已在普通／超级省电时不再发开启请求 |

该路径保留原生省电流程，而非仅写一个显示状态或单独调用 AOSP PowerManager。Provider 是异步执行，日志只记 `power-auto REQUESTED`，不把请求受理记录成已经切换成功。

## 配置和事件

专用 `Settings.Secure` 键 `hypernova_power_auto`：正负号表示启用／关闭，绝对值保存 20～80 的阈值，缺省 `-20`。一次写入同时更新开关与阈值，不使用宿主偏好持久化；`setPersistent(false)` 避免污染原生配置。写入失败时页面拒绝更改并提示；后台读取失败或非法数值时停止该次请求，不使用未知阈值。

新增路径单独校验手机管家的完整 APK 哈希、版本、splits 和固件；后台还检查已有的 `WRITE_SECURE_SETTINGS` 权限。该样本 Manifest 的 shared UID 为 `android.uid.system`，实际授权仍需设备验证。没有新增模块权限、Provider、Service 或作用域包。

工作线程动态接收受保护的 `ACTION_BATTERY_CHANGED`，使用 `RECEIVER_NOT_EXPORTED`；通过自己的配置 URI 接收变更。实际请求前查询最新 sticky 电池状态，以 `EXTRA_PLUGGED == 0` 表示未接电；交流电、USB、无线和其他非零来源都不触发，插着电但暂停充电也不会被当成未接电。缺失／无效电池样本不触发。保留收到的接电／恢复事件，避免事件排队期间快速插拔被最新状态覆盖。

配置连续通知合并，防止异步原生操作尚未落地时重复开启。无 AlarmManager、定时轮询、模块自启动服务或网络请求。停止／安装失败回收接收器、观察器和线程。后台进程重建或系统重启后按保存的开关与当前电池状态重新判断；“本轮已处理”在后台内存中，不承诺跨进程重建保持手动关闭状态。停用模块并重启后，残留配置自身不会执行请求。

## 验证

新增 12 项 `PowerSaveRuleTest`，覆盖关闭、20／80 边界、严格小于、原始电量比例和溢出、全部接电来源、无效样本、单轮去重、手动关闭、重新插拔、恢复电量、已有省电和配置重置。

`tools/verify-auto-power-save.py` 编译实际配置、规则、页面 Hook 和后台四个类，用不同 Context、宿主偏好、配置通知队列及异步 Provider 模型验证：分类不重复、保留原选项、显隐、阈值／步进、跨 Context 保存、写入拒绝、权限拒绝无注册泄漏、即时触发、去重、快速插拔、过期电池事件、超级省电保护、重新进入页面及清理。0.2.13 的模型在旧实现上先报出标题无百分比、分类在绑定后插入、原生行可单独播放动画；修复后验证 20%／80%／恢复的 50% 标题、保存失败保留旧百分比、首次绑定前有控件、当前列表动画结束及其他列表不受影响。模型不渲染真实 Miuix 控件。

0.2.14 扩展模型先复现旧版拖动到 37% 时标题仍未更新；修复后检查往返拖动和边界值即时显示、拖动不保存或通知重绑、松手提交、回到原位置不提交、保存拒绝后显示回退、重绑后只更新当前行及其他原生滑动条不受影响。

0.2.12 新增 24 项宿主声明，0.2.13 增加 3 项生命周期／列表声明，0.2.14 增加 3 项数值／绑定声明及 DEX 定位，总计 251 项声明／225 条定位证据。真实页面样式与动画按 T42 验收，滑动触摸、跨进程 Binder、后台生命周期、休眠状态、实际省电副作用和 HyperCeiler 共存待 T41 真机验收。
