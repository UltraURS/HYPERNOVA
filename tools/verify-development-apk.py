# SPDX-License-Identifier: GPL-3.0-only
"""Check development APK packaging, including the separately loaded notification bridge."""
import argparse
import importlib.util
import zipfile
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path, nargs='?', default=root / 'app/build/outputs/apk/debug/app-debug.apk')
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('dex_reader', root / 'tools/verify-targets.py')
    reader = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(reader)
    bridge_class = 'Lio/github/hypernova/bridge/PinSectioner;'
    with zipfile.ZipFile(args.apk) as archive:
        bridge = archive.read('assets/classes.dex')
        generated = root / 'app/build/generated/assets/dexHostBridge/classes.dex'
        if generated.exists():
            assert bridge == generated.read_bytes(), 'Stale bridge asset'
        methods, _, classes = reader.dex_members(bridge)
        assert classes == {bridge_class}, classes
        assert bridge_class + '-><init>(ILjava/util/function/Predicate;)V' in methods
        declared = set()
        for name in archive.namelist():
            if name.endswith('.dex') and not name.startswith('assets/'):
                _, _, classes = reader.dex_members(archive.read(name))
                declared.update(classes)
        for required in ('hook/ModuleEntry', 'feature/notification/NotificationControlsHook', 'feature/notification/NotificationClearAllHook', 'feature/update/SettingsSearchHook',
                         'feature/sidebar/SidebarGesture', 'feature/sidebar/SidebarGestureHook', 'feature/sidebar/SidebarPanelHook',
                         'feature/sidebar/SidebarTriggerHook', 'feature/sidebar/SidebarSwipeHook', 'feature/freeform/FreeformCloseHook',
                         'feature/ads/CalendarAdHook', 'feature/ads/WeatherAdHook',
                         'feature/bluetooth/BluetoothReconnectHook', 'feature/bluetooth/BluetoothSettingsHook', 'feature/bluetooth/BluetoothConfig',
                         'feature/power/PowerSaveSettingsHook', 'feature/power/AutoPowerSaveHook', 'feature/power/PowerSaveConfig', 'feature/power/PowerSaveRule',
                         'feature/xiaoai/XiaoAiHook', 'feature/xiaoai/XiaoAiConfig', 'feature/xiaoai/XiaoAiStore',
                         'feature/xiaoai/XiaoAiDialogs', 'feature/xiaoai/ChatCompletions', 'feature/xiaoai/XiaoAiPersona',
                         'feature/xiaoai/XiaoAiNativePersona', 'feature/xiaoai/XiaoAiMiuix', 'feature/xiaoai/XiaoAiAvatar',
                         'feature/xiaoai/XiaoAiPersonaPage', 'feature/xiaoai/XiaoAiQueryDisplay',
                         'feature/xiaoai/XiaoAiAvatarCropView', 'feature/xiaoai/XiaoAiAvatarCrop',
                         'feature/screenshot/ScreenshotTileHook', 'feature/screenshot/ScreenshotAppHook'):
            assert 'Lio/github/hypernova/' + required + ';' in declared, required
        assert 'Lio/github/hypernova/feature/notification/ChannelKey;' not in declared
        assert 'Lio/github/hypernova/feature/xiaoai/XiaoAiConfig$Agent;' not in declared, 'Retired local-agent feature bundled'
        assert bridge_class not in declared, 'Host bridge must not be loaded by the module class loader'
        forbidden = ('Lcom/android/systemui/', 'Lcom/android/wm/shell/', 'Lcom/android/calendar/', 'Lcom/miui/', 'LA4/', 'Lde/robv/android/xposed/',
                     'Lcom/xiaomi/voiceassistant/', 'Lr21/', 'Lorg/json/', 'Lorg/junit/', 'Lio/github/connectcolours/')
        assert not any(item.startswith(forbidden) for item in declared), 'Host/test/API classes bundled'
        assert archive.read('assets/xposed_init').decode().strip() == 'io.github.hypernova.hook.ModuleEntry'
    print('APK entry, notification bridge, generated asset and dependency exclusions OK')


if __name__ == '__main__':
    main()
