package io.github.hypernova.bridge;
import java.util.function.Predicate;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner;

/** Loaded by a small DEX loader whose parent is SystemUI's loader. */
public final class PinSectioner extends NotifSectioner {
    private final Predicate<Object> matches;
    public PinSectioner(int bucket, Predicate<Object> matches) {
        super("HYPERNOVA pinned notification", bucket);
        this.matches = matches;
    }
    @Override public boolean isInSection(ListEntry entry) { return matches.test(entry); }
}
