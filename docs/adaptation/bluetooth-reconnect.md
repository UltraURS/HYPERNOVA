# 蓝牙自动重连

0.2.11-dev，2026-11-17。0.2.10 从同级 Connect Colours 0.2.4（versionCode 17）移植蓝牙配置、重试状态、后台执行、设备选项及提示代码；保留 GPL-3.0-only 与来源声明。本次修正连接判据与状态机，目标仍为主用户环境下的 Redmi Note 12T Pro / HyperOS 3.0.3.0.VLHCNXM / Android 15。

当前完成源码接入与本地验证，开发端没有连接手机，不把实际连接和系统页面交互记为已通过。

## 使用和行为

1. LSPosed 作用域需要设置 `com.android.settings`、系统界面 `com.android.systemui`。不需要勾选蓝牙服务。
2. 模块中“蓝牙自动重连”在这两个包名分类共用配置，默认关闭，开启后重启。
3. 系统“设置 → 蓝牙 → 已配对设备详情 → HYPERNOVA”新增自动连接开关和尝试次数下拉，每台默认关闭／1 次，可选 1～5 次，含首次；保存立即生效。
4. 模块的系统界面分类另有“蓝牙重连提示”，默认关闭。开启后显示每次尝试的设备名及进度、成功或耗尽次数的结果。

开启设备选项、蓝牙重新开启或系统重启后，未连接的已配对设备开始一轮。请求后等待连接 15 秒；系统功能连接出现后还需连续稳定 5 秒，才结束本轮并提示成功。确认期间断开会中断稳定计时，保留本轮次数和原请求等待期限；不会因为底层链路或短暂连接回到第 1 次。稳定连接意外断开后等 10 秒再开始新一轮，失败后同样间隔 10 秒，接口立即报错时直接进入间隔。系统配置处于连接或断开中时延后新请求，不抢占系统尝试、不消耗模块次数。次数耗尽后停止定时器；更改次数重建该设备的本轮预算。后台进程重建也会重建内存预算。

在已适配的设置／系统界面整机断开入口手动断开时，先持久化该设备的暂停状态，再执行原生断开；重新开蓝牙或重启手机后恢复。单独关闭某个音频配置项、伴侣应用自己断开等操作不作为整机手动断开的识别入口。取消配对事件在功能运行期间清理该设备配置。关闭设备选项停止后续模块请求，不主动断开已有连接；停用模块并重启后，残留配置本身不会执行操作。

## 宿主接入

- Settings：`DeviceProfilesSettings.onResume()`、`BluetoothDeviceDetailsFragment.onResume()`，两者均用各自 `mCachedDevice` 获取当前目标。只为已配对设备创建分类，按 key 查找控件避免重复。使用宿主 AndroidX 分类／开关与 Miuix `DropDownPreference`，不注册 ColorOS 的 COUI 或 `t5.m`。
- Settings 和 SystemUI：Hook 本 APK 的 `CachedBluetoothDevice.disconnect()`。SystemUI 样本已经裁剪掉源模块使用的 `getAddress()`，改为读取已核验的 `mDevice` 并调用平台的 `getAddress()`。只暂停该设备，保留原断开行为。
- SystemUI 主进程：工作线程处理配对设备枚举、蓝牙状态事件、配置刷新和 AlarmManager 单次期限，不在 UI 线程发起后台连接。设备页保存及手动暂停在原入口同步写入，以保证先保存再断开。等待连接和稳定确认期间每秒复查；空闲、稳定连接、暂停、蓝牙关闭及耗尽次数时没有持续轮询。

使用原生 `BluetoothDevice.connect()` 发起连接。返回 `0` 仅为请求受理，不是连接成功；也不再用 `BluetoothDevice.isConnected()` 的 ACL 链路状态作为成功判据。[Android 35 BluetoothDevice 源码](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-constraintlayout-release/android-35/android/bluetooth/BluetoothDevice.java)

连接状态改为读取宿主 `LocalBluetoothManager.getInstance(Context).mCachedDeviceManager.findDevice(device)` 返回的 `CachedBluetoothDevice.isConnected()` 和 `isBusy()`。所给 SystemUI 样本的 `isConnected()` 查询实际配置的连接状态（含原生 LE Audio 分支），`isBusy()` 查询配置连接／断开中的状态；两个方法内部已有同步。缺少缓存时不回退到 ACL，也不盲目发连接请求，等待后续系统事件。

接收器监听蓝牙状态、适配器连接状态、ACL 连接／断开和配对状态五种系统事件；ACL 只触发重新查询。另在宿主 `CachedBluetoothDevice.onProfileStateChanged(LocalBluetoothProfile,int)` 后观察配置连接事件，覆盖 ACL 仍在、功能连接已经断开的情况。事件线程先捕获功能连接是否消失，再交给工作线程，防止快速断开又连上时遗漏稳定计时重置。Android 的蓝牙广播可能由非 system UID 的高权限应用发送，因此使用 `RECEIVER_EXPORTED` 接收这些系统事件，不增加自定义导出组件。[Android 广播接收器说明](https://developer.android.com/develop/background-work/background-tasks/broadcasts)

## 配置与失败处理

全局开关继续保存在模块共享偏好，设备选项由宿主已有 `WRITE_SECURE_SETTINGS` 权限保存到 `hypernova_bluetooth_` 专用系统键。启用状态及次数合并为一个带正负号的原子值；手动暂停单独保存，不被旧界面的配置副本覆盖。暂停会话包括系统启动计数和蓝牙会话 UUID。只读取和写入自己的键，不迁移或覆盖 Connect Colours 的条目，不要求模块申请该系统权限或后台自启动。

两端启动都检查当前固件、自身及对端 APK 的版本／完整哈希／split。后台在安装前解析连接请求接口及返回类型、宿主连接状态／缓存／事件成员和手动断开成员，并检查蓝牙连接、特权连接和系统配置写入权限；缺失时不安装重连任务。部分安装失败回收已建立的资源和 Hook。配置非法、不可读时停止后续请求，不用未知配置连接设备。

提供的 `framework.jar` 不包含独立蓝牙框架模块的 `BluetoothDevice` 声明；其接口由宿主原有调用、Android 35 源码和运行时签名检查共同约束，未把它误列为已核验的 APK 声明。蓝牙服务 APK 不作为注入宿主，也没有直接修改服务或网络配置。

这是系统通用蓝牙连接流程；不实现伴侣应用的专用 BLE GATT 协议，也不改变系统或设备自己的重连策略。“成功”指宿主功能连接稳定，不表示全部配置均连接、正在使用音频或配套应用已完成数据握手。

## 用户日志与本次修复依据

用户提供 `LSPosed_20261117_124443.zip` 与 `LSPosed_20261117_124456.zip`，设备为小米手表 S3，异常界面为手机系统蓝牙页。两份归档含同一段模块日志：12:33:37、12:34:22、12:34:55、12:35:52、12:36:29 均记录 `attempt 1/3 result=0`。这与旧代码在任何 ACL 连接后进入成功、断开后清零次数的路径一致；旧日志没有记录确认状态，不能把每一条请求据此归因到某个调用方。

系统日志还记录通话配置短暂连接，例如 12:34:12.929 为 HFP 状态 2、12:34:13.064 为状态 0；12:36:48 附近也有通话配置连接后迅速关闭的记录。这证明存在实际功能连接不稳定；未获得足以确定断开发起方的证据，不能断定由模块、系统、手表或配套应用哪一方单独造成。

0.2.11 增加功能配置事件、状态转换、次数和请求结果日志，使用进程内 `device#N` 区分目标，不记录完整蓝牙地址。它用于区分受理、等待、稳定确认与实际断开；模块代码不主动断开连接、不改音频开关。

## 本地验证与设备验收

在原有 9 项蓝牙状态与提示测试上新增 10 项回归：旧实现先出现 5 项预期失败，覆盖误报、提前停止、次数重置、无 ACL 事件的状态卡住和期限末尾的确认；修复后另覆盖系统忙、确认中配置变化、快速事件、暂停与提示时机。全工程共 79 项单元测试。

`tools/verify-bluetooth-config.py` 编译实际 `BluetoothConfig`，用存储替身检查默认值、设备／用户隔离、新 Context 保留配置、旧 UI 保存不覆盖暂停、蓝牙会话／重启恢复、取消配对、其他命名空间不受影响、通知过滤、非法参数和写入失败。它不验证真实 SettingsProvider 权限或跨进程 Binder。

`tools/verify-bluetooth-reconnect.py` 编译实际后台 Hook、配置、状态与提示类，以相互独立的 ACL／功能配置状态、事件队列和时钟验证：ACL 连接不成功、不停计时；短暂功能连接不清零；仅功能连接断开能恢复重试；快速事件不能跳过确认；稳定后停止轮询；系统忙时避让；确认中手动断开暂停；缓存缺失不回退 ACL。它不模拟真实蓝牙 Binder、无线环境或手表。

新增 6 项宿主成员均在 SystemUI `classes.dex`，已加入固定固件声明检查及 DEX 定位证据（总计 221 项声明／195 条定位记录）。设备按 T39 和 T40 检查配置、稳定确认、手表通话连接、仅配置断开、有限预算、系统尝试共存及 HyperCeiler 共存。
