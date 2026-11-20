// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.xiaoai;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import de.robv.android.xposed.XposedHelpers;

final class XiaoAiMiuix {
    // The host drawer explicitly uses AlertDialog.Theme.DayNight.
    private static final int DIALOG_THEME = 0x7f140027;
    private final Class<?> dialogBuilder, edit, text, toggle, button;

    XiaoAiMiuix(ClassLoader loader) {
        dialogBuilder = XposedHelpers.findClass("miuix.appcompat.app.t$a", loader);
        edit = XposedHelpers.findClass("miuix.androidbasewidget.widget.EditText", loader);
        text = XposedHelpers.findClass("miuix.appcompat.widget.TextView", loader);
        toggle = XposedHelpers.findClass("miuix.slidingwidget.widget.SlidingButton", loader);
        button = XposedHelpers.findClass("miuix.appcompat.widget.Button", loader);
        XposedHelpers.findConstructorExact(dialogBuilder, Context.class, int.class);
        for (Class<?> widget : new Class<?>[]{edit, text, toggle, button}) {
            XposedHelpers.findConstructorExact(widget, Context.class);
        }
    }

    EditText edit(Context context) { return (EditText) XposedHelpers.newInstance(edit, context); }
    TextView text(Context context) { return (TextView) XposedHelpers.newInstance(text, context); }
    CompoundButton toggle(Context context) { return (CompoundButton) XposedHelpers.newInstance(toggle, context); }
    Button button(Context context) { return (Button) XposedHelpers.newInstance(button, context); }

    Builder dialog(Activity activity, CharSequence title) {
        Object builder = XposedHelpers.newInstance(dialogBuilder, activity, DIALOG_THEME);
        XposedHelpers.callMethod(builder, "setTitle", title);
        return new Builder(activity, builder);
    }

    static Button button(Dialog dialog, int which) {
        return (Button) XposedHelpers.callMethod(dialog, "getButton", which);
    }

    static ListView items(DialogInterface dialog) {
        return (ListView) XposedHelpers.callMethod(dialog, "getListView");
    }

    static final class Builder {
        private final Activity owner;
        private final Object builder;

        Builder(Activity owner, Object builder) {
            this.owner = owner;
            this.builder = builder;
        }

        Builder setView(View view) {
            XposedHelpers.callMethod(builder, "setView", view);
            return this;
        }

        Builder setItems(CharSequence[] labels, DialogInterface.OnClickListener listener) {
            XposedHelpers.callMethod(builder, "setItems", labels, listener);
            return this;
        }

        Builder setSingleChoiceItems(CharSequence[] labels, int selected, DialogInterface.OnClickListener listener) {
            XposedHelpers.callMethod(builder, "setSingleChoiceItems", labels, selected, listener);
            return this;
        }

        Builder setPositiveButton(String label, DialogInterface.OnClickListener listener) {
            XposedHelpers.callMethod(builder, "setPositiveButton", label, listener);
            return this;
        }

        Builder setNegativeButton(String label, DialogInterface.OnClickListener listener) {
            XposedHelpers.callMethod(builder, "setNegativeButton", label, listener);
            return this;
        }

        Builder setNeutralButton(String label, DialogInterface.OnClickListener listener) {
            XposedHelpers.callMethod(builder, "setNeutralButton", label, listener);
            return this;
        }

        Dialog show(boolean secure, Runnable onClose) {
            Dialog dialog = (Dialog) XposedHelpers.callMethod(builder, "create");
            dialog.setOwnerActivity(owner);
            View decor = owner.getWindow().getDecorView();
            View.OnAttachStateChangeListener listener = new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { }
                @Override public void onViewDetachedFromWindow(View view) { dialog.dismiss(); }
            };
            dialog.setOnDismissListener(ignored -> {
                decor.removeOnAttachStateChangeListener(listener);
                if (onClose != null) onClose.run();
            });
            if (secure) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            dialog.show();
            decor.addOnAttachStateChangeListener(listener);
            return dialog;
        }
    }
}
