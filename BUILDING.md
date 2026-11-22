# 构建 HYPERNOVA

工具基线为 JDK 21、Gradle 8.9、AGP 8.7.3、Android SDK 36 与 Build Tools 35.0.0。APK 的最低版本和目标版本均为 Android 15，Java 源码目标为 17。Gradle Wrapper 已固定版本与分发包 SHA-256。

Windows PowerShell：

```powershell
./build.ps1 -SdkRoot 'D:/Android/Sdk' -JdkHome 'C:/Program Files/Java/jdk-21'
```

路径按本机修改，也可设置 `ANDROID_HOME`、`JAVA_HOME` 后执行 `./build.ps1`。依赖已缓存时加 `-Offline`；已安装 Gradle 8.9 时可传 `-GradleHome` 跳过 Wrapper 下载。脚本把 Gradle 缓存和 Android 用户文件放在项目 `.local/`，不改其他项目。

等价的主要任务：

```text
gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

本机本次通过已有 Gradle 8.9 和离线依赖构建；Wrapper 已生成并固定哈希，其首次联网下载未测试。SDK 和 JDK 本地路径不写入共享工程配置。

目标签名核验需要用户提供的原始样本：

```powershell
python tools/verify-targets.py --apks ./apks
```

该工具核对已适配组件的文件哈希与 361 个 DEX 成员。SystemHelper、RegService 和 Analytics 样本位于 `apks/隐私组件/`。样本清单在 `docs/adaptation/`。厂商 APK/JAR 由设备提供。

超级小爱原生人格回归使用 `python tools/verify-xiaoai-persona.py --jdk <JDK目录> --json <org.json测试依赖JAR>`。该脚本编译实际 Hook，在 JVM 中检查手势浮窗的文字请求、转写后提交的语音、原生大模型事件、提示词回显处理和离线请求隔离。

构建输出为 `app/build/outputs/apk/debug/app-debug.apk`。测试报告在 `app/build/reports/tests/testDebugUnitTest/`，Lint 报告在 `app/build/reports/lint-results-debug.html`。开发版使用本机调试签名，密钥位于 `.local/android-user/`，不提交；后续在同机升级需保留该密钥。

使用 `./build.ps1 -LegacyConfigMigration` 可构建旧配置过渡包。该包保留传统加载元数据，通过现有共享偏好读取旧版配置并备份到应用私有存储。先保留正式开发版输出，再构建过渡包，两次构建共用 APK 输出路径。

主要源码入口：

- `ui/MainActivity.java`：Miuix 风格原生界面、包名分类与共享配置写入。
- `hook/ModuleEntry.java`：作用域、配置读取和后台样本校验。
- `compat/DeviceProfile.java`：构建标识、版本与文件哈希。
- `feature/traffic/TrafficTileHook.java`：磁贴生命周期、查询和文案。
- `feature/update/SettingsEntryHook.java`：按精确 key 隐藏设置条目。
- `feature/update/SettingsSearchHook.java`：过滤恢复出厂搜索结果。
- `feature/notification/NotificationControlsHook.java`：单条通知菜单、滑动／批量清理保护、独立分组与排序。
- `feature/notification/NotificationClearAllHook.java`：复用原生清理按钮，调整底部位置和通知中心显示条件。
- `feature/sidebar/SidebarGesture.java`：侧边栏停留、就绪、回划、中断与事件时间判断。
- `feature/sidebar/SidebarGestureHook.java`：系统桌面原生返回手势、箭头替换、震动和取消处理。
- `feature/sidebar/SidebarPanelHook.java`：手机管家 `:ui` 进程内选择左右原生面板。
- `feature/sidebar/SidebarTriggerHook.java`：原生侧边栏收起时隐藏窗口并禁止接收触摸，展开时恢复。
- `feature/sidebar/SidebarDismissGesture.java`：向外收回手势的方向、阈值、纵向／长按／多指区分。
- `feature/sidebar/SidebarSwipeHook.java`：应用列表触摸拦截、原生关闭和动画结束后的状态恢复。
- `feature/freeform/FreeformCloseHook.java`：任务子窗口按钮、原生小窗关闭、移动／缩放与资源释放。
- `feature/freeform/FreeformClosePlacement.java`：按任务大小及缩放计算物理右上角位置。
- `feature/ads/CalendarAdHook.java`：原生推广分支、专用请求及缓存广告卡片。
- `feature/ads/WeatherAdHook.java`：天气广告请求、详情容器及共用内容过滤。
- `feature/ads/WeatherAdFilter.java`：保留普通内容和顺序的广告节点过滤。
- `feature/bluetooth/BluetoothSettingsHook.java`：HyperOS 两种蓝牙设备详情页、自动连接开关与次数。
- `feature/bluetooth/BluetoothReconnectHook.java`：SystemUI 后台重试、事件与定时器、手动断开暂停。
- `feature/bluetooth/BluetoothConfig.java`：逐设备系统配置键、手动暂停会话及取消配对清理。
- `feature/bluetooth/RetryRound.java`：有限次数、等待超时、失败间隔及暂停状态。

上述路径相对于 `app/src/main/java/io/github/hypernova/`。入口隐藏暂共用一个实现，不提前为重置执行链建立空抽象。

通知置顶需要扩展宿主的 `NotifSectioner`。`app/src/hostApi/` 只提供已核验的编译签名；`app/src/hostBridge/` 中的 `PinSectioner` 单独编译成 `assets/classes.dex`，通过以 SystemUI 为父加载器的内存 DEX 加载器使用。签名占位类不打包，主 DEX 不依赖宿主类；这避免模块与宿主的类加载器不一致。APK 校验时应同时检查桥接 DEX 存在、桥接内容与生成文件一致、占位类未打包。

成品通知桥接与依赖检查：`python tools/verify-development-apk.py <apk>`。该检查能发现生成 DEX 未进入 APK、桥接过期或宿主占位类误打包。

侧边栏触摸窗口回归：`python tools/verify-sidebar-window-lifecycle.py --jdk <JDK目录>`。该脚本编译实际的 `SidebarPanelHook`、`SidebarTriggerHook`、`SidebarSwipeHook` 和收回手势类，在 JVM 宿主替身中检查左右侧呼出后的 MIUI 点击穿透标志清理、重复请求、隐藏触发条开关组合、窗口更新、失败重试、动画期间可见／完成后隐藏、即时关闭和无关窗口；不需要新增 Android 测试依赖，也不代替真机触摸验证。

蓝牙配置回归：`python tools/verify-bluetooth-config.py --jdk <JDK目录>`。编译实际 `BluetoothConfig`，用 SettingsProvider 存储替身验证设备／用户隔离、配置保存、手动暂停、蓝牙重启／重启手机、取消配对、命名空间、通知过滤和写入失败。连接与提示的纯逻辑测试纳入 Gradle 单元测试；工具不执行真实蓝牙连接，也不验证手机上的系统权限或 Binder。

蓝牙后台回归：`python tools/verify-bluetooth-reconnect.py --jdk <JDK目录>`。编译实际后台 Hook、配置、状态与提示类，在独立的 ACL／功能配置状态、事件队列和时钟模型中检查假成功、5 秒稳定确认、快速断连、有限预算、仅配置断开、系统忙时避让、手动暂停和缓存就绪；模型不代替真实蓝牙服务与手表验证。

自动省电回归：`python tools/verify-auto-power-save.py --jdk <JDK目录>`。编译实际页面 Hook、配置、规则与后台类，检查实时标题百分比、拖动时不保存或通知重绑、仅更新当前绑定行、松手保存与失败回退、其他滑动条不受影响、首次绑定前添加分类、仅本页禁用条目动画、分类不重复、开关显隐、滑动条范围、配置通知、原生 Provider 请求、异步请求去重、快速插拔电源、过期电池事件、超级省电保护和资源清理；不执行真实电源策略，也不替代手机页面的外观与触摸验证。
