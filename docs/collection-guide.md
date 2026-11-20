# 首批固件资料采集

目标：用户当前 Redmi Note 12T Pro 上运行的 HyperOS 3.0.3.0 / Android 15。所有 APK 与框架文件必须来自同一套当前系统；不要用网上下载的“相同名称”应用代替。

2026-11-16 已收到根目录 `apks/` 中的 SystemUI、控制中心插件、Settings、framework/services、framework-res、8 张截图及 HyperCeiler 配置备份。用户确认第三方 ROM、主用户、单卡、无系统更新器、未下载更新。**这些文件和截图不需要重复采集，也不需要补传不存在的更新器。**本轮未连接手机或执行下面的 ADB 命令。

当前尚缺：相关包的 `pm path` / `dumpsys package`，厂商 framework 目录清单及 `pm list libraries`。它们分别用于核对实际加载文件/split、确认更新器缺失，以及定位搜索和厂商重置依赖。ROM 与 LSPosed 来源用户暂不清楚，保留未知；HyperCeiler 版本和实际作用域在真机联调前补记。下文保留完整采集步骤供后续复用，只需补缺项。

## 本次侧边栏材料（已收到）

用户已补充 `apks/系统桌面.apk` 和 `apks/手机管家.apk`，包名和版本已核验，不需要重复导出。下面保留后续更新样本时的采集方法：

| 应用 | 包名 | 用途 |
| --- | --- | --- |
| 系统桌面 | `com.miui.home` | 已定位原生返回手势、箭头绘制与取消路径 |
| 手机管家／安全中心 | `com.miui.securitycenter` | 已定位 `:ui` 服务和左右面板，保留原服务权限 |

应用导出工具可导出原始 APK。如果 `pm path` 返回多个文件，请保留全部 split，分别放在 `apks/com.miui.home/` 和 `apks/com.miui.securitycenter/`，不要重新打包或签名。用 ADB 时，先运行下面的只读查询，再对返回的每条 APK 路径执行 `adb pull <路径> <电脑目标目录>`；也可复用下文导出循环，把 `$packageNames` 改为这两个包。

```powershell
adb shell pm path com.miui.home
adb shell pm path com.miui.securitycenter
adb shell dumpsys package com.miui.home
adb shell dumpsys package com.miui.securitycenter
```

这两个包的 `pm path` 和 `dumpsys package` 输出如能一并保存，可用于核对版本、进程、实际加载路径和 split。暂不要求全量日志或新截图；没有发现相关组件时保留查询结果，不安装其他 ROM 的包替代。

## 1. 需要哪些文件

| 优先级 | 软件/文件 | 包名或系统路径 | 分析用途 |
| --- | --- | --- | --- |
| 首批必需 | 系统界面 | `com.android.systemui` | 移动数据磁贴、统计权限、控制中心生命周期 |
| 首批核查 | 系统界面组件/控制中心插件 | `miui.systemui.plugin` | 确认控制中心是否由插件渲染及其 ClassLoader |
| 首批必需 | 设置 | `com.android.settings` | 系统更新和恢复出厂入口、搜索、红点与跳转 |
| 首批核查 | 系统更新器 | `com.android.updater` | OTA 检查、下载、安装、通知与后台任务 |
| 重置执行链必需 | Android 框架 | `/system/framework/framework.jar` | `RecoverySystem` 等框架接口 |
| 重置执行链必需 | 系统服务 | `/system/framework/services.jar` | 重置广播、设备策略、系统服务实现 |
| 建议一起提供 | 系统资源 | `/system/framework/framework-res.apk` | 解析系统资源引用，不属于 LSPosed 注入作用域 |
| 首批提供目录清单 | 厂商框架文件列表 | `/system/framework`、`/system_ext/framework`、`/product/framework`、`/vendor/framework` | 确定应补充哪些厂商 JAR 或资源 |

这些应用包名是候选，是否存在及实际安装路径以设备结果为准。插件或更新器查询不到时保留命令输出即可，不安装其他版本来补齐。厂商 JAR 先给目录清单，后续根据实际调用链指定文件，不需要第一轮导出整个系统分区。

对每个应用导出当前安装的完整 APK 集：`pm path` 如果返回 `base.apk` 和多个 `split_*.apk`，全部保留。不要重新签名、重新打包或只提供反编译结果。文件按包名分文件夹保存，避免多个 `base.apk` 互相覆盖。

每个应用的 `dumpsys package <包名>` 输出也一并保留，用于核对 versionName、versionCode、安装路径、UID、权限与组件。文件哈希和 DEX 检查收到后由开发端生成，不需要手工填写。

## 2. 还需要哪些信息

下列是完整记录项；本次已知结果见 [适配档案](adaptation/redmi-note-12t-pro-os3.0.3.0.md)。已有截图已覆盖完整版本、Root、LSPosed 状态和布局，无需重复整理：

- 完整 HyperOS 版本字符串，包含地区/构建后缀；“关于手机”和详细版本页截图即可。
- ROM 来源：官方系统或第三方修改版；是否手工替换/更新过 SystemUI、控制中心插件、设置或更新器。
- LSPosed 的具体分支/下载来源、完整版本号和构建号、激活状态；管理器“关于”或状态页截图。不清楚分支时给截图和下载链接，不推断。
- Root 方案及版本，例如实际使用的 Magisk、KernelSU 或 APatch；如使用独立 Zygisk 实现，提供其名称和版本。
- 当前是否启用了其他会修改控制中心、系统设置、OTA 或恢复出厂行为的模块，列名称和相关功能即可；采集阶段无需为此停用模块。
- 当前使用主用户还是系统分身/第二空间；单卡或双卡，日常使用的控制中心布局。
- 系统更新当前是否已经下载更新包、显示等待重启或正在安装。只报告现状，不为采集而主动检查、下载或安装更新。

建议附三类界面截图：控制中心展开且能看到移动数据磁贴；关于手机的版本/更新区域；恢复出厂设置的入口所在页面。若现有更新通知或弹窗自然出现，也可附图。不要点击最终清除确认按钮。

资料不需要 IMEI、IMSI、设备序列号、手机号或账号信息；截图中出现时遮盖即可。初次分析不需要完整 bugreport、全量 logcat 或个人应用列表。

## 3. 存放位置

已准备目录：

```text
.local\firmware\redmi-note-12t-pro-os3.0.3.0\
  apks\
    com.android.systemui\
    miui.systemui.plugin\
    com.android.settings\
    com.android.updater\
  framework\
  info\
  screenshots\
```

当前用户已采用项目根目录 `apks/`，保留原位即可；上面的 `.local/firmware/` 用于补充信息。两者均已加入 `.gitignore`，厂商二进制和设备资料不作为模块源码提交。目录内的包名子目录可在导出时创建。

## 4. Windows PowerShell / ADB 采集步骤

先启用手机 USB 调试，连接电脑并在手机确认调试授权。确保 `adb` 在 PATH 中；若在 Platform Tools 目录直接执行，可将命令中的 `adb` 换成 `./adb.exe`。以下步骤假设只有一台目标设备在线；有多台时对每条命令使用同一个 `adb -s <serial>` 目标。[Android 官方 ADB 文档](https://developer.android.com/tools/adb)

### 准备路径与设备属性

在项目根目录打开 PowerShell，按顺序运行以下代码块。命令从手机读取资料并写入电脑。

```powershell
adb devices
$collectDir = Join-Path (Get-Location).Path '.local\firmware\redmi-note-12t-pro-os3.0.3.0'
foreach ($folder in @('apks', 'framework', 'info', 'screenshots')) {
    New-Item -ItemType Directory -Path (Join-Path $collectDir $folder) -Force | Out-Null
}
$propertyNames = @(
    'ro.product.model', 'ro.product.device',
    'ro.build.version.release', 'ro.build.version.sdk',
    'ro.build.version.incremental', 'ro.build.fingerprint',
    'ro.build.version.security_patch', 'ro.product.cpu.abilist'
)
$propertyLines = foreach ($propertyName in $propertyNames) {
    $propertyValue = adb shell getprop $propertyName
    if ($LASTEXITCODE -ne 0) { throw "Failed to read $propertyName" }
    "$propertyName=$($propertyValue -join '')"
}
$propertyLines | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $collectDir 'info\device-properties.txt')
```

`adb devices` 应显示目标状态为 `device`。若为 `unauthorized`、`offline` 或没有目标，先解决连接问题再继续；不需要把显示设备序列号的输出交给开发端。

### 核对目标应用及其包信息

`pm path` 查询已安装 APK 路径，`adb pull` 将文件复制到电脑；`dumpsys package` 读取该包的系统诊断信息。[ADB 命令说明](https://developer.android.com/tools/adb)、[dumpsys 文档](https://developer.android.com/tools/dumpsys)

本次已有 APK，可只保存 `pm path` 和 `dumpsys package` 输出，跳过下面的 `adb pull` 循环；仅当发现此前未提供的 split 或文件已变化时再导出。保留对更新器的只读查询，以记录包缺失状态。

```powershell
$packageNames = @(
    'com.android.systemui', 'miui.systemui.plugin',
    'com.android.settings', 'com.android.updater'
)
foreach ($packageName in $packageNames) {
    $packageDir = Join-Path (Join-Path $collectDir 'apks') $packageName
    New-Item -ItemType Directory -Path $packageDir -Force | Out-Null
    $pathOutput = @(adb shell pm path $packageName)
    $pathOutput | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $packageDir 'paths.txt')
    adb shell dumpsys package $packageName |
        Set-Content -Encoding UTF8 -LiteralPath (Join-Path $packageDir 'package-info.txt')
    $apkPaths = @($pathOutput | Where-Object { $_ -match '^package:' })
    if ($apkPaths.Count -eq 0) {
        Write-Warning "No APK paths returned for $packageName; keep the output for inspection."
        continue
    }
    foreach ($pathLine in $apkPaths) {
        $remotePath = ($pathLine -replace '^package:', '').Trim()
        adb pull $remotePath $packageDir
        if ($LASTEXITCODE -ne 0) { throw "Failed to pull $remotePath" }
    }
}
```

如果导出失败，保留错误消息；不使用更改系统文件权限、重新挂载系统分区或替换 APK 的方式绕过。

### 导出基础框架与目录清单

本次已有 3 个基础框架文件，跳过下面 3 行 `adb pull`，只补目录与共享库列表即可。

```powershell
$frameworkDir = Join-Path $collectDir 'framework'
adb pull /system/framework/framework.jar $frameworkDir
adb pull /system/framework/services.jar $frameworkDir
adb pull /system/framework/framework-res.apk $frameworkDir

adb shell ls -l /system/framework |
    Set-Content -Encoding UTF8 -LiteralPath (Join-Path $collectDir 'info\system-framework-list.txt')
adb shell ls -l /system_ext/framework |
    Set-Content -Encoding UTF8 -LiteralPath (Join-Path $collectDir 'info\system-ext-framework-list.txt')
adb shell ls -l /product/framework |
    Set-Content -Encoding UTF8 -LiteralPath (Join-Path $collectDir 'info\product-framework-list.txt')
adb shell ls -l /vendor/framework |
    Set-Content -Encoding UTF8 -LiteralPath (Join-Path $collectDir 'info\vendor-framework-list.txt')
adb shell pm list libraries |
    Set-Content -Encoding UTF8 -LiteralPath (Join-Path $collectDir 'info\shared-libraries.txt')
```

某个可选目录不存在时，报告不存在即可。若基础文件不存在或无读取权限，提供具体报错和已取得的目录清单，再根据本机路径处理。若 JAR 不含可用 DEX，开发端检查后再指定所需 OAT/VDEX 或其他依赖，不预先要求导出全部文件。

## 5. 提供材料后的处理顺序

1. 校验已提供应用的版本、split 完整性和文件哈希，补全设备适配档案；不存在的更新器保留缺失证据。
2. 先分析 SystemUI/控制中心插件，定位流量磁贴及刷新生命周期。
3. 分析 Settings/Updater 的入口、提示和检查/下载/安装调用链。
4. 分析 framework/services 中的恢复出厂执行链，明确需要补充的厂商库。
5. 建立最小模块工程，再按已确认签名实现与验证。

当前材料已足够进行最小模块及流量开发准备；优先补包信息和厂商目录清单。只有调用链明确依赖某个库时，再指定其实际文件，不要求导出整个系统分区。
