# SPDX-License-Identifier: GPL-3.0-only
# DEX parser adapted from Connect Colours, Copyright (C) 2026 UltraURS and contributors.
"""Check this development build's hook declarations against the supplied firmware."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path


def dex_members(data):
    def u16(offset):
        return struct.unpack_from("<H", data, offset)[0]

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def uleb(offset):
        value = shift = 0
        while True:
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
            shift += 7

    strings = []
    for index in range(u32(56)):
        offset = u32(u32(60) + index * 4)
        _, offset = uleb(offset)
        strings.append(data[offset:data.index(b"\0", offset)].decode("utf-8", errors="replace"))
    types = [strings[u32(u32(68) + i * 4)] for i in range(u32(64))]
    protos = []
    for index in range(u32(72)):
        offset = u32(76) + index * 12
        params = u32(offset + 8)
        args = "" if not params else "".join(
            types[u16(params + 4 + i * 2)] for i in range(u32(params)))
        protos.append("(" + args + ")" + types[u32(offset + 4)])
    method_ids = []
    for index in range(u32(88)):
        offset = u32(92) + index * 8
        method_ids.append(types[u16(offset)] + "->" + strings[u32(offset + 4)]
                          + protos[u16(offset + 2)])
    field_ids = []
    for index in range(u32(80)):
        offset = u32(84) + index * 8
        field_ids.append(types[u16(offset)] + "->" + strings[u32(offset + 4)]
                         + ":" + types[u16(offset + 2)])
    methods, fields, classes = set(), set(), set()
    for index in range(u32(96)):
        offset = u32(100) + index * 32
        classes.add(types[u32(offset)])
        pos = u32(offset + 24)
        if not pos:
            continue
        counts = []
        for _ in range(4):
            count, pos = uleb(pos)
            counts.append(count)
        for group, count in enumerate(counts):
            member_id = 0
            for _ in range(count):
                delta, pos = uleb(pos)
                member_id += delta
                _, pos = uleb(pos)  # access flags
                if group >= 2:
                    _, pos = uleb(pos)  # code offset
                    methods.add(method_ids[member_id])
                else:
                    fields.add(field_ids[member_id])
    return methods, fields, classes


TARGETS = {
    "systemui": [
        "Lcom/android/settingslib/bluetooth/CachedBluetoothDevice;->disconnect()V",
        "Lcom/android/settingslib/bluetooth/CachedBluetoothDevice;->mDevice:Landroid/bluetooth/BluetoothDevice;",
        "Lcom/android/settingslib/bluetooth/CachedBluetoothDevice;->isConnected()Z",
        "Lcom/android/settingslib/bluetooth/CachedBluetoothDevice;->isBusy()Z",
        "Lcom/android/settingslib/bluetooth/CachedBluetoothDevice;->onProfileStateChanged(Lcom/android/settingslib/bluetooth/LocalBluetoothProfile;I)V",
        "Lcom/android/settingslib/bluetooth/CachedBluetoothDeviceManager;->findDevice(Landroid/bluetooth/BluetoothDevice;)Lcom/android/settingslib/bluetooth/CachedBluetoothDevice;",
        "Lcom/android/settingslib/bluetooth/LocalBluetoothManager;->getInstance(Landroid/content/Context;)Lcom/android/settingslib/bluetooth/LocalBluetoothManager;",
        "Lcom/android/settingslib/bluetooth/LocalBluetoothManager;->mCachedDeviceManager:Lcom/android/settingslib/bluetooth/CachedBluetoothDeviceManager;",
        "Lcom/android/systemui/qs/tiles/MiuiCellularTile;->handleUpdateState(Lcom/android/systemui/plugins/qs/QSTile$State;Ljava/lang/Object;)V",
        "Lcom/android/systemui/qs/tileimpl/QSTileImpl;->setListening(Ljava/lang/Object;Z)V",
        "Lcom/android/systemui/qs/tileimpl/QSTileImpl;->handleDestroy()V",
        "Lcom/android/systemui/qs/tileimpl/QSTileImpl;->refreshState()V",
        "Lcom/android/systemui/qs/tileimpl/QSTileImpl;->mContext:Landroid/content/Context;",
        "Lcom/android/systemui/qs/tileimpl/QSTileImpl;->mHandler:Lcom/android/systemui/qs/tileimpl/QSTileImpl$H;",
        "Lcom/android/systemui/qs/tileimpl/QSTileImpl;->mListeners:Landroid/util/ArraySet;",
        "Lcom/android/systemui/qs/tileimpl/QSTileImpl;->mStaleListener:Ljava/lang/Object;",
        "Lcom/android/systemui/plugins/qs/QSTile$State;->label:Ljava/lang/CharSequence;",
        "Lcom/android/systemui/plugins/qs/QSTile$State;->contentDescription:Ljava/lang/CharSequence;",
        "Lcom/android/systemui/statusbar/notification/row/MiuiNotificationMenuRow;->createMenuViews(Z)V",
        "Lcom/android/systemui/statusbar/notification/row/MiuiNotificationMenuRow;->mMenuContainer:Landroid/view/ViewGroup;",
        "Lcom/android/systemui/statusbar/notification/row/MiuiNotificationMenuRow;->mInfoItem:Lcom/android/systemui/statusbar/notification/row/MiuiNotificationMenuRow$MiuiNotificationMenuItem;",
        "Lcom/android/systemui/statusbar/notification/row/MiuiNotificationMenuRow;->mParent:Lcom/android/systemui/statusbar/notification/row/ExpandableNotificationRow;",
        "Lcom/android/systemui/statusbar/notification/row/ExpandableNotificationRow;->canViewBeDismissed()Z",
        "Lcom/android/systemui/statusbar/notification/row/ExpandableNotificationRow;->getAttachedChildren()Ljava/util/List;",
        "Lcom/android/systemui/statusbar/notification/row/ExpandableNotificationRow;->mEntry:Lcom/android/systemui/statusbar/notification/collection/NotificationEntry;",
        "Lcom/android/systemui/statusbar/notification/collection/NotificationEntry;->mSbn:Lcom/android/systemui/statusbar/notification/ExpandedNotification;",
        "Lcom/android/systemui/statusbar/notification/collection/ShadeListBuilder;->setSectioners(Ljava/util/List;)V",
        "Lcom/android/systemui/statusbar/notification/collection/ShadeListBuilder;->buildList()V",
        "Lcom/android/systemui/statusbar/notification/collection/ShadeListBuilder;->rebuildListIfBefore(I)V",
        "Lcom/android/systemui/statusbar/notification/collection/ShadeListBuilder;->mNotifSections:Ljava/util/List;",
        "Lcom/android/systemui/statusbar/notification/collection/ShadeListBuilder;->mNotifStabilityManager:Lcom/android/systemui/statusbar/notification/collection/listbuilder/pluggable/NotifStabilityManager;",
        "Lcom/android/systemui/statusbar/notification/collection/ShadeListBuilder;->mPipelineState:Lcom/android/systemui/statusbar/notification/collection/listbuilder/PipelineState;",
        "Lcom/android/systemui/statusbar/notification/collection/ShadeListBuilder;->mIterationCount:I",
        "Lcom/android/systemui/statusbar/notification/collection/ShadeListBuilder;->DEFAULT_SECTIONER:Lcom/android/systemui/statusbar/notification/collection/ShadeListBuilder$2;",
        "Lcom/android/systemui/statusbar/notification/collection/listbuilder/pluggable/NotifSectioner;-><init>(Ljava/lang/String;I)V",
        "Lcom/android/systemui/statusbar/notification/collection/listbuilder/pluggable/NotifSectioner;->isInSection(Lcom/android/systemui/statusbar/notification/collection/ListEntry;)Z",
        "Lcom/android/systemui/statusbar/notification/collection/listbuilder/pluggable/NotifSectioner;->mBucket:I",
        "Lcom/android/systemui/statusbar/notification/collection/listbuilder/NotifSection;->sectioner:Lcom/android/systemui/statusbar/notification/collection/listbuilder/pluggable/NotifSectioner;",
        "Lcom/android/systemui/statusbar/notification/collection/listbuilder/PipelineState;->mState:I",
        "Lcom/android/systemui/statusbar/notification/collection/NotifPipeline;->mShadeListBuilder:Lcom/android/systemui/statusbar/notification/collection/ShadeListBuilder;",
        "Lcom/android/systemui/statusbar/notification/modal/ModalController;->pipeline:Lcom/android/systemui/statusbar/notification/collection/NotifPipeline;",
        "Lcom/android/systemui/Dependency;->sDependency:Lcom/android/systemui/Dependency;",
        "Lcom/android/systemui/Dependency;->getDependencyInner(Ljava/lang/Object;)Ljava/lang/Object;",
        "Lcom/android/systemui/statusbar/notification/stack/MiuiNotificationSwipeCallback;->canChildBeDismissed(Landroid/view/View;)Z",
        "Lcom/android/systemui/statusbar/notification/stack/MiuiNotificationSwipeCallback;->canChildBeDismissedInDirection(Landroid/view/View;)Z",
        "Lcom/android/systemui/statusbar/notification/stack/NotificationStackScrollLayoutController$9;->canChildBeDismissed(Landroid/view/View;)Z",
        "Lcom/android/systemui/statusbar/notification/stack/NotificationStackScrollLayoutController$9;->canChildBeDismissedInDirection(Landroid/view/View;)Z",
        "Lcom/android/systemui/statusbar/notification/collection/NotifCollection;->tryRemoveNotification(Lcom/android/systemui/statusbar/notification/collection/NotificationEntry;)Z",
        "Lcom/android/systemui/statusbar/notification/collection/listbuilder/pluggable/NotifStabilityManager;->isGroupChangeAllowed(Lcom/android/systemui/statusbar/notification/collection/NotificationEntry;)Z",
        "Lcom/android/systemui/statusbar/notification/collection/listbuilder/pluggable/NotifStabilityManager;->isSectionChangeAllowed(Lcom/android/systemui/statusbar/notification/collection/NotificationEntry;)Z",
        "Lcom/android/systemui/statusbar/notification/collection/listbuilder/pluggable/NotifStabilityManager;->isEntryReorderingAllowed(Lcom/android/systemui/statusbar/notification/collection/ListEntry;)Z",
        "Lcom/android/systemui/shade/MiuiNotificationPanelViewController;->getShowDismissView()Z",
        "Lcom/android/systemui/shade/MiuiNotificationPanelViewController;->dismissViewController:Lcom/android/systemui/shade/NotificationDismissViewController;",
        "Lcom/android/systemui/shade/NotificationDismissViewController;->onInit()V",
        "Lcom/android/systemui/shade/NotificationDismissViewController;->updateOrientation()V",
        "Lcom/android/systemui/shade/NotificationDismissViewController;->dismissView:Lcom/miui/systemui/widget/CircleAndTickAnimView;",
        "Lcom/android/systemui/statusbar/notification/stack/NotificationStackScrollLayoutController;->hasNotifications(IZ)Z",
        "Lcom/android/systemui/statusbar/notification/stack/NotificationStackScrollLayoutController;->mNotifCollection:Lcom/android/systemui/statusbar/notification/collection/NotifCollection;",
        "Lcom/android/systemui/statusbar/notification/stack/NotificationStackScrollLayoutController;->mVisibilityProvider:Lcom/android/systemui/statusbar/notification/collection/render/NotificationVisibilityProvider;",
        "Lcom/android/systemui/statusbar/notification/stack/NotificationStackScrollLayout;->clearNotifications(IZZ)V",
        "Lcom/android/systemui/statusbar/notification/stack/NotificationStackScrollLayout;->includeChildInClearAll(Lcom/android/systemui/statusbar/notification/row/ExpandableNotificationRow;I)Z",
        "Lcom/android/systemui/statusbar/notification/stack/NotificationStackScrollLayout;->mController:Lcom/android/systemui/statusbar/notification/stack/NotificationStackScrollLayoutController;",
        "Lcom/android/systemui/statusbar/notification/collection/NotifCollection;->dismissAllNotifications(I)V",
        "Lcom/android/systemui/statusbar/notification/collection/NotifCollection;->dismissNotifications(Ljava/util/List;)V",
        "Lcom/android/systemui/statusbar/notification/collection/NotifCollection;->getEntry(Ljava/lang/String;)Lcom/android/systemui/statusbar/notification/collection/NotificationEntry;",
        "Lcom/android/systemui/statusbar/notification/collection/NotifCollection;->mReadOnlyNotificationSet:Ljava/util/Collection;",
        "Lcom/android/systemui/statusbar/notification/collection/NotificationEntry;->isClearable()Z",
        "Lcom/android/systemui/statusbar/notification/collection/NotificationEntry;->mDismissState:Lcom/android/systemui/statusbar/notification/collection/NotificationEntry$DismissState;",
        "Lcom/android/systemui/statusbar/notification/collection/provider/NotificationVisibilityProviderImpl;->obtain(Lcom/android/systemui/statusbar/notification/collection/NotificationEntry;Z)Lcom/android/internal/statusbar/NotificationVisibility;",
        "Lcom/android/systemui/statusbar/notification/collection/notifcollection/DismissedByUserStats;-><init>(ILcom/android/internal/statusbar/NotificationVisibility;)V",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiTopDecoration;->relayout(Landroid/view/SurfaceControl$Transaction;Landroid/view/SurfaceControl$Transaction;Landroid/window/WindowContainerTransaction;Z)V",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiTopDecoration;->updateVisibility(ZLandroid/view/SurfaceControl$Transaction;)V",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiTopDecoration;->releaseViews()V",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiTopDecoration;->getResult()Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiTopDecoration$RelayoutResult;",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiTopDecoration;->closeHandleMenu(Z)V",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiBaseWindowDecoration;->onMiuiFreeformResizeStart(Z)V",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiBaseWindowDecoration;->onMiuiFreeformScaleChanged(F)V",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiBaseWindowDecoration$SurfaceControlViewHostFactory;->create(Landroid/content/Context;Landroid/view/Display;Landroid/view/WindowlessWindowManager;)Landroid/view/SurfaceControlViewHost;",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiCaptionButtonClickListener;->handleCloseClicked(Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiWindowDecoration;)V",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MulWinSwitchDecorViewModel;->isInLockTaskMode()Z",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiBaseWindowDecoration;->mContext:Landroid/content/Context;",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiBaseWindowDecoration;->mDecoration:Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiWindowDecoration;",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiBaseWindowDecoration;->mRunningTaskInfo:Landroid/app/ActivityManager$RunningTaskInfo;",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiBaseWindowDecoration;->mWindowingMode:I",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiBaseWindowDecoration;->mTaskBounds:Landroid/graphics/Rect;",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiBaseWindowDecoration;->mCaptionVisible:Z",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiBaseWindowDecoration;->mSurfaceControlBuilderSupplier:Ljava/util/function/Supplier;",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiBaseWindowDecoration;->mSurfaceControlViewHostFactory:Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiBaseWindowDecoration$SurfaceControlViewHostFactory;",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiBaseWindowDecoration;->mWindowDecorViewModel:Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MulWinSwitchDecorViewModel;",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiBaseWindowDecoration;->mTaskOrganizer:Lcom/android/wm/shell/ShellTaskOrganizer;",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiBaseWindowDecoration;->mMultiTaskingTaskRepository:Lcom/android/wm/shell/multitasking/taskmanager/MultiTaskingTaskRepository;",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiTopDecoration;->mOnCaptionButtonClickListener:Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiCaptionButtonClickListener;",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiWindowDecoration;->mDisplay:Landroid/view/Display;",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiWindowDecoration;->mTaskSurface:Landroid/view/SurfaceControl;",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiBaseWindowDecoration$RelayoutResult;->mRootView:Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiDecorationRootView;",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiBaseWindowDecoration$RelayoutResult;->mCaptionY:I",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiDotView;->getDotY()F",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MiuiDecorationRootView;->mPaint:Landroid/graphics/Paint;",
        "Lcom/android/wm/shell/multitasking/miuimultiwinswitch/miuiwindowdecor/MulWinSwitchTaskOperations;->minimizeTask(Landroid/app/ActivityManager$RunningTaskInfo;)V",
        "Lcom/android/wm/shell/ShellTaskOrganizer;->getRunningTaskInfo(I)Landroid/app/ActivityManager$RunningTaskInfo;",
        "Lcom/android/wm/shell/multitasking/taskmanager/MultiTaskingTaskRepository;->getMiuiFreeformTaskInfo(I)Lcom/android/wm/shell/multitasking/taskmanager/MiuiFreeformModeTaskInfo;",
        "Lcom/android/wm/shell/multitasking/taskmanager/MiuiFreeformModeTaskInfo;->isNormalState()Z",
        "Lcom/android/wm/shell/multitasking/taskmanager/MiuiFreeformModeTaskInfo;->mDestinationScaleX:F",
    ],
    "settings": [
        "Lmiuix/preference/DropDownPreference;-><init>(Landroid/content/Context;)V",
        "Lmiuix/preference/DropDownPreference;->setEntries([Ljava/lang/CharSequence;)V",
        "Lmiuix/preference/DropDownPreference;->setEntryValues([Ljava/lang/CharSequence;)V",
        "Lmiuix/preference/DropDownPreference;->setValue(Ljava/lang/String;)V",
        "Lcom/android/settings/bluetooth/DeviceProfilesSettings;->onResume()V",
        "Lcom/android/settings/bluetooth/DeviceProfilesSettings;->mCachedDevice:Lcom/android/settingslib/bluetooth/CachedBluetoothDevice;",
        "Lcom/android/settings/bluetooth/BluetoothDeviceDetailsFragment;->onResume()V",
        "Lcom/android/settings/bluetooth/BluetoothDeviceDetailsFragment;->mCachedDevice:Lcom/android/settingslib/bluetooth/CachedBluetoothDevice;",
        "Lcom/android/settingslib/bluetooth/CachedBluetoothDevice;->disconnect()V",
        "Lcom/android/settingslib/bluetooth/CachedBluetoothDevice;->mDevice:Landroid/bluetooth/BluetoothDevice;",
        "Landroidx/preference/PreferenceCategory;-><init>(Landroid/content/Context;)V",
        "Landroidx/preference/SwitchPreference;-><init>(Landroid/content/Context;)V",
        "Landroidx/preference/Preference$OnPreferenceChangeListener;->onPreferenceChange(Landroidx/preference/Preference;Ljava/lang/Object;)Z",
        "Landroidx/preference/Preference;->setKey(Ljava/lang/String;)V",
        "Landroidx/preference/Preference;->setTitle(Ljava/lang/CharSequence;)V",
        "Landroidx/preference/Preference;->setOrder(I)V",
        "Landroidx/preference/Preference;->setPersistent(Z)V",
        "Landroidx/preference/Preference;->setSummary(Ljava/lang/CharSequence;)V",
        "Landroidx/preference/Preference;->setOnPreferenceChangeListener(Landroidx/preference/Preference$OnPreferenceChangeListener;)V",
        "Landroidx/preference/Preference;->getContext()Landroid/content/Context;",
        "Landroidx/preference/PreferenceGroup;->findPreference(Ljava/lang/CharSequence;)Landroidx/preference/Preference;",
        "Landroidx/preference/PreferenceFragmentCompat;->getPreferenceScreen()Landroidx/preference/PreferenceScreen;",
        "Landroidx/preference/TwoStatePreference;->setChecked(Z)V",
        "Landroidx/fragment/app/Fragment;->getActivity()Landroidx/fragment/app/FragmentActivity;",
        "Landroidx/preference/Preference;->getKey()Ljava/lang/String;",
        "Landroidx/preference/Preference;->setVisible(Z)V",
        "Landroidx/preference/PreferenceGroup;->addPreference(Landroidx/preference/Preference;)Z",
        "Lcom/android/settings/recommend/RecommendUIHelper;->getLinkData(Lcom/android/settings/recommend/bean/RecommendItem;)Lcom/android/settings/recommend/RecommendUIHelper$RecommendItemLinkData;",
        "Lcom/android/settings/recommend/RecommendUIHelper$RecommendItemLinkData;->getIntent()Landroid/content/Intent;",
        "Lcom/android/settings/recommend/bean/RecommendItem;->getIntent()Ljava/lang/String;",
        "Lcom/android/settings/search/SearchResult;->getSearchResultList(Landroid/content/Context;Ljava/lang/String;)Ljava/util/List;",
        "Lcom/android/settings/search/SearchResultItem;->intent:Landroid/content/Intent;",
        "Lcom/android/settings/search/SearchResultItem;->pkg:Ljava/lang/String;",
        "Lcom/android/settings/search/SearchResultItem;->preferenceKey:Ljava/lang/String;",
        "Lcom/android/settings/SettingsFragment$SearchResultAdapter;->refresh(Ljava/util/List;)V"
    ],
    "plugin": [
        "Lmiui/systemui/controlcenter/qs/tileview/QSCardItemView;->updateState(Lcom/android/systemui/plugins/qs/QSTile$State;ZZ)V"
    ],
    "framework": [
        "Landroid/service/notification/StatusBarNotification;->getKey()Ljava/lang/String;",
        "Landroid/service/notification/StatusBarNotification;->isGroup()Z",
        "Landroid/view/ViewGroup;->onInterceptTouchEvent(Landroid/view/MotionEvent;)Z",
        "Landroid/view/View;->onTouchEvent(Landroid/view/MotionEvent;)Z",
        "Landroid/view/WindowlessWindowManager;-><init>(Landroid/content/res/Configuration;Landroid/view/SurfaceControl;Landroid/window/InputTransferToken;)V",
        "Landroid/view/SurfaceControl$Builder;->setContainerLayer()Landroid/view/SurfaceControl$Builder;",
        "Landroid/view/SurfaceControlViewHost;->setView(Landroid/view/View;Landroid/view/WindowManager$LayoutParams;)V",
        "Landroid/view/SurfaceControlViewHost;->relayout(Landroid/view/WindowManager$LayoutParams;)V",
        "Landroid/view/SurfaceControl$Transaction;->setTrustedOverlay(Landroid/view/SurfaceControl;Z)Landroid/view/SurfaceControl$Transaction;",
        "Landroid/view/SurfaceControl$Transaction;->remove(Landroid/view/SurfaceControl;)Landroid/view/SurfaceControl$Transaction;",
        "Landroid/view/WindowManager$LayoutParams;->setTrustedOverlay()V",
        "Landroid/app/TaskInfo;->getWindowingMode()I",
        "Landroid/app/TaskInfo;->taskId:I",
        "Landroid/app/TaskInfo;->displayId:I",
        "Landroid/app/TaskInfo;->isVisible:Z",
        "Landroid/app/TaskInfo;->configuration:Landroid/content/res/Configuration;",
    ],
    "home": [
        "Lcom/miui/home/recents/GesturesBackTouchProcessor;->onPointerEvent(Landroid/view/MotionEvent;Lcom/miui/home/recents/GestureStubView;)V",
        "Lcom/miui/home/recents/GesturesBackTouchProcessor;->isShowBackArrow(F)Z",
        "Lcom/miui/home/recents/GestureBackArrowView$1;->onAnimationSuccess(Landroid/animation/Animator;)V",
        "Lcom/miui/home/recents/GestureBackArrowView$1;->this$0:Lcom/miui/home/recents/GestureBackArrowView;",
        "Lcom/miui/home/recents/GestureBackArrowView;->mArrowFeedBackDone:Z",
        "Lcom/miui/home/recents/GesturesBackTouchProcessor;->mDownX:F",
        "Lcom/miui/home/recents/GesturesBackTouchProcessor;->mSwipeStatus:I",
        "Lcom/miui/home/recents/GestureStubView;->mGestureStubPos:I",
        "Lcom/miui/home/recents/GestureStubView;->mGestureBackArrowView:Lcom/miui/home/recents/GestureBackArrowView;",
        "Lcom/miui/home/recents/GestureStubView;->mHandler:Lcom/miui/home/recents/GestureStubView$H;",
        "Lcom/miui/home/recents/GestureStubView;->mTouchable:Z",
        "Lcom/miui/home/recents/GestureStubView;->mShouldRedirectMotionEvent:Z",
        "Lcom/miui/home/recents/GestureStubView;->onDetachedFromWindow()V",
        "Lcom/miui/home/recents/GestureStubView;->setBackTouchable(Z)V",
        "Lcom/miui/home/recents/GestureBackArrowView;->mArrow:Landroid/graphics/Bitmap;",
        "Lcom/miui/home/recents/GestureBackArrowView;->onDraw(Landroid/graphics/Canvas;)V",
        "Lcom/miui/home/recents/GestureBackArrowView;->onConfigurationChanged(Landroid/content/res/Configuration;)V",
        "Lcom/miui/home/recents/GestureBackArrowView;->mArrowWidth:I",
        "Lcom/miui/home/recents/GestureBackArrowView;->mArrowHeight:I",
        "Lcom/miui/home/common/hapticfeedback/HapticFeedbackCompat;->getInstance()Lcom/miui/home/common/hapticfeedback/HapticFeedbackCompat;",
        "Lcom/miui/home/common/hapticfeedback/HapticFeedbackCompat;->performGestureReadyBack()V"
    ],
    "calendar": [
        "Lcom/android/calendar/settings/y;->h(Landroid/content/Context;)Z",
        "Lcom/miui/calendar/ad/AdsExtraManager;->f(Landroid/content/Context;)V",
        "Lcom/miui/calendar/ad/AdsExtraManager;->g(Landroid/content/Context;JLjava/lang/String;)V",
        "Lcom/miui/calendar/ad/AdsExtraManager;->d(I)Lcom/miui/calendar/card/schema/CustomCardSchema;",
        "Lcom/android/calendar/cards/AdPresenter;->isRemoveCard()Z",
        "Lcom/miui/calendar/ad/AdsExtraManager;->e:Lcom/miui/calendar/ad/AdsExtraManager;",
        "Lcom/miui/calendar/ad/AdsExtraManager;->a:Lretrofit2/Call;",
        "Lcom/miui/calendar/ad/AdsExtraManager;->b:Lretrofit2/Call;",
        "Lretrofit2/Call;->cancel()V"
    ],
    "systemhelper": [
        "Lcom/mobiletools/systemhelper/control/AppInfoRegisterReceiver;->onReceive(Landroid/content/Context;Landroid/content/Intent;)V",
        "Lcom/mobiletools/systemhelper/control/GetSignalInfoReceiver;->onReceive(Landroid/content/Context;Landroid/content/Intent;)V",
        "Lcom/mobiletools/systemhelper/control/SignalInfoRegisterReceiver;->onReceive(Landroid/content/Context;Landroid/content/Intent;)V",
        "Lcom/mobiletools/systemhelper/control/e;->a(Lorg/json/JSONObject;)Z",
    ],
    "regservice": [
        "Lu0/i;->g()Z",
    ],
    "analytics": [
        "La/b/a/e/g;->h(Z)V",
    ],
    "health": [
        "Lcom/mi/health/appupgrade/AppUpgradeUtil;->showUpdateDialogIfNeed(Landroidx/fragment/app/FragmentManager;Lcom/mi/health/appupgrade/model/AppUpgradeResult;Z)V",
        "Lcom/mi/health/appupgrade/model/AppUpgradeResult;->getNew_version()I",
    ],
    "stk": [
        "Lcom/android/stk/MiStkStubImpl;->filterDisplayText(Ljava/lang/String;)Z",
        "Lcom/android/stk/MiStkStubImpl;->launchTextDialog(Landroid/content/Intent;ILcom/android/internal/telephony/cat/TextMessage;)Z",
    ],
    "weather": [
        "Lq1/e;->j(Ljava/lang/String;I)V",
        "Lw1/d;->a(Ljava/util/Map;Lretrofit2/Callback;)V",
        "LI1/a;->f(Landroid/content/Context;IILjava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        "LH1/b;->b(Ljava/lang/String;Landroid/content/Context;)[Lcom/miui/weather2/structures/AdvertisementData;",
        "Lcom/miui/weather2/structures/InfoCardBean;->getList()Ljava/util/List;",
        "Lcom/miui/weather2/structures/InfoListNodeBean;->getType()Ljava/lang/String;",
        "Lcom/miui/weather2/ad/dailyDetailPageAd/DailyDetailAdView;->onFinishInflate()V",
        "Lcom/miui/weather2/ad/dailyDetailPageAd/DailyDetailAdView;->m(Lcom/miui/weather2/structures/InfoDataBean;Lcom/miui/weather2/structures/DailyForecastAdData;)V",
        "Lcom/miui/weather2/tools/u;->a(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Landroid/content/Context;)V",
        "Lcom/miui/weather2/tools/u;->b(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/content/Context;)V"
    ],
    "xiaoai": [
        "Lcom/xiaomi/voiceassistant/settings/MiuiVoiceSettingActivity$a;->onCreatePreferences(Landroid/os/Bundle;Ljava/lang/String;)V",
        "Lcom/xiaomi/voiceassistant/settings/MiuiVoiceSettingActivity$a;->onResume()V",
        "Lcom/xiaomi/voiceassistant/settings/preference/StyleableTextPreference;-><init>(Landroid/content/Context;)V",
        "Lcom/xiaomi/voiceassistant/settings/preference/ValuePreference;->setShowRightArrow(Z)V",
        "Landroidx/fragment/app/Fragment;->getActivity()Landroidx/fragment/app/FragmentActivity;",
        "Landroidx/preference/PreferenceFragmentCompat;->getPreferenceScreen()Landroidx/preference/PreferenceScreen;",
        "Landroidx/preference/PreferenceGroup;->findPreference(Ljava/lang/CharSequence;)Landroidx/preference/Preference;",
        "Landroidx/preference/PreferenceGroup;->addPreference(Landroidx/preference/Preference;)Z",
        "Landroidx/preference/Preference;->getContext()Landroid/content/Context;",
        "Landroidx/preference/Preference;->setKey(Ljava/lang/String;)V",
        "Landroidx/preference/Preference;->setTitle(Ljava/lang/CharSequence;)V",
        "Landroidx/preference/Preference;->setOrder(I)V",
        "Landroidx/preference/Preference;->setPersistent(Z)V",
        "Landroidx/preference/Preference;->setSummary(Ljava/lang/CharSequence;)V",
        "Landroidx/preference/Preference;->setOnPreferenceClickListener(Landroidx/preference/Preference$OnPreferenceClickListener;)V",
        "Landroidx/preference/Preference$OnPreferenceClickListener;->onPreferenceClick(Landroidx/preference/Preference;)Z",
        "Lyf0/g0;->J()V",
        "Lyf0/g0;->r:Lyf0/m0;",
        "Lyf0/g0;->a:Lcom/xiaomi/voiceassistant/TabHostMainActivity;",
        "Lyf0/m0;->a:Ljava/util/List;",
        "Lyf0/m0;->addItem(Lzf0/a;I)V",
        "Lzf0/a;-><init>(ILjava/lang/String;Landroid/view/View$OnClickListener;Lyf0/o;)V",
        "Lzf0/a;->getTitle()Ljava/lang/String;",
        "Lyf0/o;->SKILL:Lyf0/o;",
        "Lcom/xiaomi/voiceassistant/TabHostMainActivity;->R:Lyf0/g0;",
        "Lcom/xiaomi/voiceassistant/TabHostMainActivity;->t0(Lcom/xiaomi/voiceassistant/TabHostMainActivity;Landroid/view/View;)V",
        "Lcom/xiaomi/voiceassistant/TabHostMainActivity;->onBackPressed()V",
        "Lmiuix/appcompat/app/t$a;-><init>(Landroid/content/Context;I)V",
        "Lmiuix/appcompat/app/t$a;->setTitle(Ljava/lang/CharSequence;)Lmiuix/appcompat/app/t$a;",
        "Lmiuix/appcompat/app/t$a;->setView(Landroid/view/View;)Lmiuix/appcompat/app/t$a;",
        "Lmiuix/appcompat/app/t$a;->setItems([Ljava/lang/CharSequence;Landroid/content/DialogInterface$OnClickListener;)Lmiuix/appcompat/app/t$a;",
        "Lmiuix/appcompat/app/t$a;->setSingleChoiceItems([Ljava/lang/CharSequence;ILandroid/content/DialogInterface$OnClickListener;)Lmiuix/appcompat/app/t$a;",
        "Lmiuix/appcompat/app/t$a;->setPositiveButton(Ljava/lang/CharSequence;Landroid/content/DialogInterface$OnClickListener;)Lmiuix/appcompat/app/t$a;",
        "Lmiuix/appcompat/app/t$a;->setNegativeButton(Ljava/lang/CharSequence;Landroid/content/DialogInterface$OnClickListener;)Lmiuix/appcompat/app/t$a;",
        "Lmiuix/appcompat/app/t$a;->setNeutralButton(Ljava/lang/CharSequence;Landroid/content/DialogInterface$OnClickListener;)Lmiuix/appcompat/app/t$a;",
        "Lmiuix/appcompat/app/t$a;->create()Lmiuix/appcompat/app/t;",
        "Lmiuix/appcompat/app/t;->getButton(I)Landroid/widget/Button;",
        "Lmiuix/appcompat/app/t;->getListView()Landroid/widget/ListView;",
        "Lmiuix/androidbasewidget/widget/EditText;-><init>(Landroid/content/Context;)V",
        "Lmiuix/appcompat/widget/TextView;-><init>(Landroid/content/Context;)V",
        "Lmiuix/slidingwidget/widget/SlidingButton;-><init>(Landroid/content/Context;)V",
        "Lmiuix/appcompat/widget/Button;-><init>(Landroid/content/Context;)V",
        "Landroidx/activity/ComponentActivity;->getActivityResultRegistry()Landroidx/activity/result/ActivityResultRegistry;",
        "Landroidx/activity/result/ActivityResultRegistry;->register(Ljava/lang/String;Landroidx/activity/result/contract/ActivityResultContract;Landroidx/activity/result/ActivityResultCallback;)Landroidx/activity/result/ActivityResultLauncher;",
        "Landroidx/activity/result/ActivityResultCallback;->onActivityResult(Ljava/lang/Object;)V",
        "Landroidx/activity/result/contract/ActivityResultContracts$StartActivityForResult;-><init>()V",
        "Landroidx/activity/result/ActivityResultLauncher;->launch(Ljava/lang/Object;)V",
        "Landroidx/activity/result/ActivityResultLauncher;->unregister()V",
        "Landroidx/activity/result/ActivityResult;->getResultCode()I",
        "Landroidx/activity/result/ActivityResult;->getData()Landroid/content/Intent;",
        "Lh30/f0;->e(Lh30/f0$c;)Landroid/content/Intent;",
        "Lm10/b1;->r(Landroid/content/Intent;)V",
        "Lcom/xiaomi/voiceassistant/SpeechQueryService;->onStartCommand(Landroid/content/Intent;II)I",
        "Lh30/f0;->a(Ljava/lang/String;ZLh30/f0$e;ZZ)Lcom/xiaomi/ai/api/common/Event;",
        "Lcom/xiaomi/ai/api/common/Message;->getPayload()Ljava/lang/Object;",
        "Lcom/xiaomi/ai/api/Nlp$RequestLargeLanguageModelContent;->setQuery(Ljava/lang/String;)Lcom/xiaomi/ai/api/Nlp$RequestLargeLanguageModelContent;",
        "Lcom/xiaomi/ai/api/Template$Query;->getText()Ljava/lang/String;",
        "Lcom/xiaomi/ai/api/Template$Query;->getTextInfo()Liw/a;",
        "Lcom/xiaomi/ai/api/Template$Toast;->getQuery()Liw/a;",
        "Liw/a;->isPresent()Z",
        "Liw/a;->get()Ljava/lang/Object;",
        "Liw/a;->ofNullable(Ljava/lang/Object;)Liw/a;",
        "Lcom/xiaomi/voiceassistant/instruction/card/stream/a;-><init>(ILjava/lang/String;Ljava/lang/String;ZLjava/lang/String;ZLj70/c0;ZZLjava/lang/String;)V",
        "Ls10/c;->getRequestInfo(Landroid/content/Intent;Ls10/c$a;)Ls10/c;",
        "Lt10/x0;->l(Ld30/c;Ld30/f;Ls10/c;)V",
        "Lm10/n;->startNlpRequest(Ld30/c;Ld30/f;)V",
        "Lm10/s0$d;->d:Ld30/c;",
        "Lm10/m0;->I0(Lm10/s0$d;)Z",
        "Lm10/m0;->J0(Lm10/s0$d;)Z",
        "Lcom/xiaomi/ai/api/Nlp$Request;-><init>(Ljava/lang/String;)V",
        "Lcom/xiaomi/voiceassistant/mainui/inputmodulev3/e;->i(Lcom/xiaomi/voiceassistant/mainui/inputmodulev3/b;Lgf0/b;)V",
        "Lcom/xiaomi/voiceassistant/ConversationFragment$d;->j:Lcom/xiaomi/voiceassistant/ConversationFragment;",
        "Lff0/e0;->onSendClick(Ljava/lang/String;)V",
        "Lff0/e0;->d:Lcom/xiaomi/voiceassistant/widget/d;",
        "Lcom/xiaomi/voiceassistant/widget/d;->isMultiModalUploadShow()Z",
        "Lcom/xiaomi/voiceassistant/widget/d;->isMultiModalUploadSuccess()Z",
        "Lr21/d0$a;-><init>()V",
        "Lr21/d0$a;->followRedirects(Z)Lr21/d0$a;",
        "Lr21/d0$a;->followSslRedirects(Z)Lr21/d0$a;",
        "Lr21/d0$a;->retryOnConnectionFailure(Z)Lr21/d0$a;",
        "Lr21/d0$a;->connectTimeout(JLjava/util/concurrent/TimeUnit;)Lr21/d0$a;",
        "Lr21/d0$a;->readTimeout(JLjava/util/concurrent/TimeUnit;)Lr21/d0$a;",
        "Lr21/d0$a;->callTimeout(JLjava/util/concurrent/TimeUnit;)Lr21/d0$a;",
        "Lr21/d0$a;->build()Lr21/d0;",
        "Lr21/n;->b:Lr21/n;",
        "Lr21/b;->b:Lr21/b;",
        "Lr21/z;->get(Ljava/lang/String;)Lr21/z;",
        "Lr21/g0;->create([BLr21/z;)Lr21/g0;",
        "Lr21/f0$a;-><init>()V",
        "Lr21/f0$a;->url(Ljava/lang/String;)Lr21/f0$a;",
        "Lr21/f0$a;->header(Ljava/lang/String;Ljava/lang/String;)Lr21/f0$a;",
        "Lr21/f0$a;->post(Lr21/g0;)Lr21/f0$a;",
        "Lr21/f0$a;->build()Lr21/f0;",
        "Lr21/d0;->newCall(Lr21/f0;)Lr21/e;",
        "Lr21/e;->execute()Lr21/h0;",
        "Lr21/e;->cancel()V",
        "Lr21/h0;->code()I",
        "Lr21/h0;->body()Lr21/i0;",
        "Lr21/h0;->close()V",
        "Lr21/i0;->byteStream()Ljava/io/InputStream;"
    ],
    "securitycenter": [
        "Lcom/miui/powercenter/savemode/PowerSaveFragment;->onCreatePreferences(Landroid/os/Bundle;Ljava/lang/String;)V",
        "Lcom/miui/powercenter/savemode/PowerSaveFragment;->onResume()V",
        "Lcom/miui/powercenter/powersaver/PowerSaverProvider;->call(Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;",
        "Landroidx/fragment/app/Fragment;->getActivity()Landroidx/fragment/app/FragmentActivity;",
        "Landroidx/preference/PreferenceFragmentCompat;->getPreferenceScreen()Landroidx/preference/PreferenceScreen;",
        "Landroidx/preference/PreferenceFragmentCompat;->getListView()Landroidx/recyclerview/widget/RecyclerView;",
        "Landroidx/recyclerview/widget/RecyclerView;->setItemAnimator(Landroidx/recyclerview/widget/RecyclerView$ItemAnimator;)V",
        "Landroidx/preference/PreferenceCategory;-><init>(Landroid/content/Context;)V",
        "Landroidx/preference/CheckBoxPreference;-><init>(Landroid/content/Context;)V",
        "Landroidx/preference/SeekBarPreference;-><init>(Landroid/content/Context;Landroid/util/AttributeSet;)V",
        "Landroidx/preference/SeekBarPreference;->b:I",
        "Landroidx/preference/SeekBarPreference;->i:Z",
        "Landroidx/preference/SeekBarPreference;->i(I)V",
        "Landroidx/preference/SeekBarPreference;->j(I)V",
        "Landroidx/preference/SeekBarPreference;->k(I)V",
        "Landroidx/preference/SeekBarPreference;->n(I)V",
        "Landroidx/preference/SeekBarPreference;->onBindViewHolder(Landroidx/preference/i;)V",
        "Landroidx/preference/i;->b(I)Landroid/view/View;",
        "Landroidx/preference/Preference$c;->onPreferenceChange(Landroidx/preference/Preference;Ljava/lang/Object;)Z",
        "Landroidx/preference/Preference;->setOnPreferenceChangeListener(Landroidx/preference/Preference$c;)V",
        "Landroidx/preference/Preference;->setVisible(Z)V",
        "Landroidx/preference/Preference;->setTitle(Ljava/lang/CharSequence;)V",
        "Landroidx/preference/Preference;->setSummary(Ljava/lang/CharSequence;)V",
        "Landroidx/preference/Preference;->setKey(Ljava/lang/String;)V",
        "Landroidx/preference/Preference;->setOrder(I)V",
        "Landroidx/preference/Preference;->setPersistent(Z)V",
        "Landroidx/preference/Preference;->getContext()Landroid/content/Context;",
        "Landroidx/preference/TwoStatePreference;->setChecked(Z)V",
        "Landroidx/preference/PreferenceGroup;->addPreference(Landroidx/preference/Preference;)Z",
        "Landroidx/preference/PreferenceGroup;->findPreference(Ljava/lang/CharSequence;)Landroidx/preference/Preference;",
        "Lcom/miui/gamebooster/service/DockWindowManagerService;->onStartCommand(Landroid/content/Intent;II)I",
        "Lcom/miui/gamebooster/service/DockWindowManagerService;->b:LA4/n;",
        "Lcom/miui/gamebooster/service/DockWindowManagerService;->c:Z",
        "LA4/n;->e:Lcom/miui/dock/sidebar/j;",
        "LA4/n;->f:Lcom/miui/dock/sidebar/j;",
        "LA4/n;->p:Z",
        "LA4/n;->L1()V",
        "LA4/n;->e1()V",
        "LA4/n;->A1(Lcom/miui/dock/sidebar/j;Ljava/lang/Runnable;)V",
        "LA4/n;->Q1(Lcom/miui/dock/sidebar/j;)V",
        "LA4/n;->X0(Lcom/miui/dock/sidebar/j;)V",
        "LA4/n;->Z1(Landroid/view/View;Landroid/view/WindowManager$LayoutParams;)V",
        "Lcom/miui/dock/sidebar/j;->H()Z",
        "Lcom/miui/dock/sidebar/j;->F()Z",
        "Lcom/miui/dock/sidebar/j;->Q()V",
        "Lcom/miui/dock/sidebar/j;->U(Z)V",
        "Lcom/miui/dock/sidebar/j;->z()Landroid/widget/FrameLayout;",
        "Lcom/miui/dock/sidebar/j;->u()Lcom/miui/dock/sidebar/b;",
        "Lcom/miui/dock/sidebar/j;->w()Lcom/miui/dock/sidebar/RegionSamplingImageView;",
        "Lcom/miui/dock/sidebar/j;->A()Lcom/miui/gamebooster/windowmanager/newbox/TurboLayout;",
        "LA4/n;->i1(Lcom/miui/dock/sidebar/j;Z)V",
        "LA4/n;->K0(ZLcom/miui/gamebooster/windowmanager/newbox/TurboLayout;Lcom/miui/dock/sidebar/j;Landroid/view/View;)V",
        "LA4/n;->k1(Lcom/miui/dock/sidebar/j;Z)V",
        "LA4/n;->h1(Lcom/miui/dock/sidebar/j;)V",
        "Lcom/miui/dock/sidebar/j;->o()LA4/n;",
        "Lcom/miui/gamebooster/windowmanager/newbox/TurboLayout;->getDockLayout()Lcom/miui/gamebooster/windowmanager/newbox/e;",
    ]
}

def main():
    root = Path(__file__).resolve().parents[1]
    args = argparse.ArgumentParser()
    args.add_argument('--apks', type=Path, default=root / 'apks')
    options = args.parse_args()
    evidence = json.loads((root / 'docs/adaptation/redmi-note-12t-pro-os3.0.3.0-evidence.json').read_text(encoding='utf-8'))
    total = 0
    for item in evidence['inputs']:
        label = item['id']
        if label not in TARGETS:
            continue
        path = options.apks / Path(item['file']).relative_to('apks')
        if not path.is_file():
            path = options.apks / path.name
        if hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
            raise SystemExit('SHA-256 mismatch: ' + path.name)
        declared = set()
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if name.endswith('.dex'):
                    methods, fields, _ = dex_members(archive.read(name))
                    declared.update(methods | fields)
        missing = set(TARGETS[label]) - declared
        if missing:
            raise SystemExit('Missing DEX declarations: ' + repr(sorted(missing)))
        total += len(TARGETS[label])
        print(label + ': hash and ' + str(len(TARGETS[label])) + ' declarations OK')
    print('Verified ' + str(total) + ' declarations; runtime behavior remains untested.')


if __name__ == '__main__':
    main()
