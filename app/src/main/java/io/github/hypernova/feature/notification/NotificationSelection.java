package io.github.hypernova.feature.notification;

/** Each action owns one exact StatusBarNotification key, never an app, channel or group key. */
public final class NotificationSelection {
    public final String locked, pinned;
    public NotificationSelection(String locked, String pinned) { this.locked = locked; this.pinned = pinned; }
    public boolean locked(String key) { return key != null && key.equals(locked); }
    public boolean pinned(String key) { return key != null && key.equals(pinned); }
    public boolean protectsClear(String key, String group, boolean summary, String lockedGroup, boolean lockedSummary) {
        if (locked(key)) return true;
        // Clearing a summary also cancels its children; an auto-summary can disappear with its last child.
        return locked != null && lockedGroup != null && lockedGroup.equals(group) && (summary || lockedSummary);
    }
    public NotificationSelection toggleLock(String key) {
        requireKey(key);
        return new NotificationSelection(locked(key) ? null : key, pinned);
    }
    public NotificationSelection togglePin(String key) {
        requireKey(key);
        return new NotificationSelection(locked, pinned(key) ? null : key);
    }
    public NotificationSelection removed(String key) {
        if (!locked(key) && !pinned(key)) return this;
        return new NotificationSelection(locked(key) ? null : locked, pinned(key) ? null : pinned);
    }
    public String lockLabel(String key) { return locked(key) ? "取消锁定" : "锁定"; }
    public String pinLabel(String key) { return pinned(key) ? "取消置顶" : "置顶"; }
    private static void requireKey(String key) {
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("No notification key");
    }
}
