package com.android.systemui.statusbar.notification.collection.listbuilder.pluggable;
import com.android.systemui.statusbar.notification.collection.ListEntry;
/** Compile-time signature only. Never packaged. Constructor verified against the target DEX. */
public abstract class NotifSectioner {
    public NotifSectioner(String name, int bucket) { }
    public abstract boolean isInSection(ListEntry entry);
}
