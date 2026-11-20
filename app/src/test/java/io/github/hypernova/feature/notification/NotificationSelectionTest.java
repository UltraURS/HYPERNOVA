package io.github.hypernova.feature.notification;
import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationSelectionTest {
    // Same app/channel can still post two different notification ids or tags.
    private final String manager = "0|org.lsposed.manager|1|manager|1000";
    private final String activation = "0|org.lsposed.manager|2|activation|1000";
    @Test public void selectingOneNotificationNeverSelectsSiblingFromSameApp() {
        NotificationSelection state = new NotificationSelection(null, null).toggleLock(manager).togglePin(manager);
        assertTrue(state.locked(manager)); assertTrue(state.pinned(manager));
        assertFalse(state.locked(activation)); assertFalse(state.pinned(activation));
        assertFalse(state.pinned("10|org.lsposed.manager|1|manager|101000"));
    }
    @Test public void eachActionOwnsOneTargetAndReplacementIsIndependent() {
        NotificationSelection state = new NotificationSelection(null, null).toggleLock(manager).togglePin(activation);
        assertTrue(state.locked(manager)); assertTrue(state.pinned(activation));
        state = state.toggleLock(activation);
        assertFalse(state.locked(manager)); assertTrue(state.locked(activation)); assertTrue(state.pinned(activation));
        state = state.togglePin(manager);
        assertTrue(state.locked(activation)); assertFalse(state.pinned(activation)); assertTrue(state.pinned(manager));
    }
    @Test public void secondClickCancelsOnlyItsActionAndRestoresLabels() {
        NotificationSelection state = new NotificationSelection(null, null).togglePin(manager).toggleLock(manager);
        assertEquals("取消锁定", state.lockLabel(manager)); assertEquals("取消置顶", state.pinLabel(manager));
        state = state.toggleLock(manager);
        assertEquals("锁定", state.lockLabel(manager)); assertTrue(state.pinned(manager));
        state = state.togglePin(manager);
        assertEquals("置顶", state.pinLabel(manager)); assertNull(state.pinned); assertNull(state.locked);
    }
    @Test public void unrelatedCancellationDoesNotClearSelectedNotification() {
        NotificationSelection state = new NotificationSelection(manager, manager);
        assertSame(state, state.removed(activation)); assertSame(state, state.removed(null));
    }
    @Test public void actualRemovalClearsSelectionSoRepostDoesNotInheritIt() {
        NotificationSelection state = new NotificationSelection(manager, manager).removed(manager);
        assertFalse(state.locked(manager)); assertFalse(state.pinned(manager));
        assertNull(state.locked); assertNull(state.pinned);
    }
    @Test public void removalOfOneActionPreservesOtherTarget() {
        NotificationSelection state = new NotificationSelection(manager, activation).removed(manager);
        assertNull(state.locked); assertTrue(state.pinned(activation));
        state = state.removed(activation); assertNull(state.pinned);
    }
    @Test(expected = IllegalArgumentException.class) public void nullTargetIsRejected() {
        new NotificationSelection(null, null).togglePin(null);
    }
    @Test public void clearProtectsLockedItemButAllowsAnUnlockedPinnedItem() {
        NotificationSelection state = new NotificationSelection(manager, activation);
        assertTrue(state.protectsClear(manager, "group", false, "group", false));
        assertFalse(state.protectsClear(activation, "group", false, "group", false));
    }
    @Test public void clearCannotRemoveTheSummaryOfALockedChild() {
        NotificationSelection state = new NotificationSelection(manager, null);
        assertTrue(state.protectsClear("summary", "group", true, "group", false));
        assertFalse(state.protectsClear("other-summary", "other-group", true, "group", false));
    }
    @Test public void lockedSummaryRetainsItsChildrenToPreventIndirectSummaryRemoval() {
        NotificationSelection state = new NotificationSelection("summary", null);
        assertTrue(state.protectsClear(manager, "group", false, "group", true));
        assertFalse(state.protectsClear(activation, "other-group", false, "group", true));
    }
    @Test public void noLockOrMissingGroupDoesNotProtectUnrelatedNotifications() {
        assertFalse(new NotificationSelection(null, manager).protectsClear(manager, "group", true, "group", true));
        NotificationSelection state = new NotificationSelection(manager, null);
        assertFalse(state.protectsClear(activation, null, true, null, false));
        state = state.toggleLock(manager);
        assertFalse(state.protectsClear(manager, "group", false, "group", false));
    }
}
