package io.github.hypernova.feature.screenshot;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class ScreenshotAppHook {
    public static final String PACKAGE = "com.miui.screenshot";
    static final String SERVICE = PACKAGE + ".TakeScreenshotService";
    static final int REGION_REQUEST = 0x484e01;
    static final int LONG_REQUEST = 0x484e02;
    private static final int NATIVE_REGION = 3;
    private static final String EDITOR = "com.miui.gallery.editor.photo.screen.home.ScreenEditorActivity";
    private static final String DRAFT_KEY = "io.github.hypernova.screenshot.draft";
    private static final String KEEP_ORIGINAL = "io.github.hypernova.screenshot.keepOriginal";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Executor STORAGE = Executors.newSingleThreadExecutor();
    private static final ThreadLocal<Draft> REQUEST = new ThreadLocal<>();
    private static final ThreadLocal<Uri> SCROLL_WRITE = new ThreadLocal<>();

    private ScreenshotAppHook() { }

    public static void install(ClassLoader loader, boolean captureProcess) {
        try {
            installEditor(loader);
            if (captureProcess) installCapture(loader);
            HookLog.info("screenshot-app INSTALLED " + (captureProcess ? "capture" : "photo_editor"));
        } catch (Throwable error) {
            HookLog.error("screenshot-app install", error);
        }
    }

    private static void installCapture(ClassLoader loader) {
        Class<?> handler = XposedHelpers.findClass(SERVICE + "$d", loader);
        Class<?> requestClass = XposedHelpers.findClass("com.android.internal.util.ScreenshotRequest", loader);
        Class<?> controller = XposedHelpers.findClass(PACKAGE + ".j", loader);
        Class<?> saveTask = XposedHelpers.findClass(PACKAGE + ".G", loader);
        Class<?> saveData = XposedHelpers.findClass(PACKAGE + ".D", loader);
        Class<?> media = XposedHelpers.findClass(PACKAGE + ".util.MediaUtils", loader);
        Class<?> entry = XposedHelpers.findClass(PACKAGE + ".N", loader);
        Class<?> tracker = XposedHelpers.findClass("J2.a", loader);
        Class<?> saved = XposedHelpers.findClass("I2.a", loader);
        Class<?> done = XposedHelpers.findClass("I2.b", loader);
        Class<?> launch = XposedHelpers.findClass("N2.a", loader);
        Class<?> scroll = XposedHelpers.findClass(PACKAGE + ".GlobalScreenshotDisplay", loader);

        XposedHelpers.findAndHookMethod(controller, "Z0", Runnable.class, saved, done, boolean.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (REQUEST.get() != null) XposedHelpers.setAdditionalInstanceField(param.thisObject, DRAFT_KEY, REQUEST.get());
            }
        });
        XposedHelpers.findAndHookConstructor(saveTask, saveData, tracker, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!param.hasThrowable() && REQUEST.get() != null) {
                    Object record = XposedHelpers.getObjectField(param.thisObject, "a");
                    XposedHelpers.setAdditionalInstanceField(record, DRAFT_KEY, REQUEST.get());
                }
            }
        });
        XposedHelpers.findAndHookMethod(media, "r", Context.class, entry, tracker, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Draft draft = draftOf(param.args[1]);
                if (draft != null && param.getResult() instanceof Uri uri) draft.remember((Context) param.args[0], uri);
            }
        });
        XposedHelpers.findAndHookMethod(media, "o", Context.class, entry, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object record = param.args[1];
                if (draftOf(record) == null) return;
                param.setResult(null);
                if (XposedHelpers.getBooleanField(record, "j")) return;
                if (!Boolean.TRUE.equals(XposedHelpers.getObjectField(record, "h"))) {
                    XposedHelpers.setBooleanField(record, "i", true);
                    return;
                }
                // Keep the host's completion callback, including its editor launch, without publishing the source.
                MAIN.postAtFrontOfQueue(() -> {
                    try {
                        if (!XposedHelpers.getBooleanField(record, "j")) XposedHelpers.callStaticMethod(media, "j", record);
                    } catch (Throwable error) {
                        draftOf(record).discard();
                        HookLog.error("screenshot draft completion", error);
                    }
                });
            }
        });
        XposedHelpers.findAndHookMethod(launch, "L", Context.class, entry, String.class,
            boolean.class, boolean.class, boolean.class, String.class, int[].class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Draft draft = draftOf(param.args[1]);
                    if (draft == null || !"edit".equals(param.args[2]) || !(param.getResult() instanceof Intent intent)) return;
                    intent.setClassName(PACKAGE, EDITOR);
                    intent.setAction("com.miui.screenshot.intent.action.SCREEN_EDIT");
                    intent.putExtra(DRAFT_KEY, true);
                    XposedHelpers.setAdditionalInstanceField(intent, DRAFT_KEY, draft);
                }
            });
        XposedHelpers.findAndHookMethod(launch, "V", Context.class, Intent.class, Bundle.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Intent intent = (Intent) param.args[1];
                Draft draft = draftOf(intent);
                if (draft == null) return;
                param.setResult(null);
                if (!draft.handoff()) return;
                Context context = (Context) param.args[0];
                try {
                    context.startActivity(intent, (Bundle) param.args[2]);
                } catch (RuntimeException error) {
                    draft.failedHandoff();
                    HookLog.error("screenshot editor launch", error);
                    Toast.makeText(context, "无法打开截屏编辑器", Toast.LENGTH_SHORT).show();
                }
            }
        });
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass(PACKAGE + ".j$h", loader), "b", Uri.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Draft draft = draftOf(XposedHelpers.getObjectField(param.thisObject, "d"));
                if (draft != null) MAIN.post(draft::discard);
            }
        });
        installScrolling(loader, controller, scroll, entry, tracker, done);

        // Install the request ingress last so a missing host member can never start a draft capture.
        XposedHelpers.findAndHookMethod(handler, "handleMessage", Message.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Message message = (Message) param.args[0];
                if (!requestClass.isInstance(message.obj) || (message.what != 0 && message.what != 2)) return;
                Draft draft = draftOf(message.obj);
                if (message.what == 0) {
                    if (XposedHelpers.getIntField(message, "sendingUid") != 1000) return;
                    int type = (int) XposedHelpers.callMethod(message.obj, "getType");
                    if (type != REGION_REQUEST && type != LONG_REQUEST) return;
                    param.setObjectExtra("translated", true);
                    if (type == REGION_REQUEST) {
                        // The native selector remains visible while the user chooses the crop.
                        message.what = NATIVE_REGION;
                        HookLog.info("screenshot-app request region selector");
                        return;
                    }
                    draft = new Draft();
                    HookLog.info("screenshot-app request long");
                    XposedHelpers.setAdditionalInstanceField(message.obj, DRAFT_KEY, draft);
                    message.what = 1;
                }
                if (draft != null) {
                    REQUEST.set(draft);
                    param.setObjectExtra("capture", true);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.getObjectExtra("capture") != null) REQUEST.remove();
                if (param.getObjectExtra("translated") != null) ((Message) param.args[0]).what = 0;
            }
        });
    }

    private static void installScrolling(ClassLoader loader, Class<?> controller, Class<?> scroll,
            Class<?> entry, Class<?> tracker, Class<?> done) {
        XposedHelpers.findAndHookMethod(controller, "f1", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object capture = param.thisObject;
                Draft draft = draftOf(capture);
                if (draft == null) return;
                // The native capability check posts the button's enabled state at the front of the queue.
                MAIN.post(() -> {
                    draft.supportChecked = true;
                    openLongScreenshot(capture, draft);
                });
            }
        });
        XposedHelpers.findAndHookMethod(controller, "W0", Runnable.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object capture = param.thisObject;
                Draft draft = draftOf(capture);
                if (draft == null) return;
                Runnable finishAnimation = (Runnable) param.args[0];
                param.args[0] = (Runnable) () -> {
                    if (finishAnimation != null) finishAnimation.run();
                    MAIN.post(() -> {
                        draft.thumbnailReady = true;
                        openLongScreenshot(capture, draft);
                    });
                };
            }
        });
        XposedHelpers.findAndHookMethod(controller, "A0", boolean.class, boolean.class, boolean.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Draft draft = draftOf(param.thisObject);
                if (draft != null && !draft.scrolling) draft.discard();
            }
        });
        XposedHelpers.findAndHookMethod(scroll, "g0", Bitmap.class, entry, tracker,
            int.class, int.class, int.class, int.class, boolean.class, done, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Draft draft = draftOf(param.args[1]);
                    if (draft != null && !param.hasThrowable()) draft.scrolling = true;
                }
            });
        XposedHelpers.findAndHookMethod(scroll, "F", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (scrollDraft(param.thisObject) != null) XposedHelpers.setBooleanField(param.thisObject, "F", true);
            }
        });
        XposedHelpers.findAndHookMethod(scroll, "M", boolean.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(param.args[0]) && scrollDraft(param.thisObject) != null) {
                    XposedHelpers.setBooleanField(param.thisObject, "F", true);
                }
            }
        });
        XposedHelpers.findAndHookMethod(scroll, "a0", boolean.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Draft draft = scrollDraft(param.thisObject);
                if (draft != null) draft.discard();
            }
        });
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass(PACKAGE + ".GlobalScreenshotDisplay$j", loader),
            "a", Void[].class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object display = XposedHelpers.getObjectField(param.thisObject, "a");
                    Object record = XposedHelpers.getObjectField(display, "f");
                    if (draftOf(record) != null) SCROLL_WRITE.set((Uri) XposedHelpers.getObjectField(record, "g"));
                }

                @Override protected void afterHookedMethod(MethodHookParam param) { SCROLL_WRITE.remove(); }
            });
        XposedHelpers.findAndHookMethod(ContentResolver.class, "update", Uri.class, ContentValues.class,
            Bundle.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Uri draftUri = SCROLL_WRITE.get();
                    if (draftUri == null || !draftUri.equals(param.args[0])) return;
                    ContentValues values = (ContentValues) param.args[1];
                    if (values != null && Integer.valueOf(0).equals(values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))) {
                        ContentValues pending = new ContentValues(values);
                        pending.remove(MediaStore.MediaColumns.IS_PENDING);
                        param.args[1] = pending;
                    }
                }
            });
    }

    private static void openLongScreenshot(Object capture, Draft draft) {
        if (!draft.thumbnailReady || !draft.supportChecked || draft.longStarted || draft.isDiscarded()) return;
        if (XposedHelpers.getBooleanField(capture, "D")) return;
        draft.longStarted = true;
        View button = (View) XposedHelpers.getObjectField(capture, "t");
        if (button != null && button.isAttachedToWindow() && button.isShown() && button.isEnabled()
                && button.performClick()) return;
        Context context = (Context) XposedHelpers.getObjectField(capture, "d");
        Toast.makeText(context, "此区域不支持长截屏", Toast.LENGTH_SHORT).show();
        XposedHelpers.callMethod(capture, "A0", false, true, true);
    }

    private static void installEditor(ClassLoader loader) {
        Class<?> editor = XposedHelpers.findClass(EDITOR, loader);
        XposedHelpers.findAndHookMethod(editor, "g1", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (editorDraft(activity) != null && !activity.isFinishing()) param.setObjectExtra("saving", true);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (param.getObjectExtra("saving") == null || param.hasThrowable() || !activity.isFinishing()) return;
                // The native completion button finishes immediately when it can keep the source as-is.
                Uri uri = editorDraft(activity);
                if (uri == null) return;
                XposedHelpers.setAdditionalInstanceField(activity, KEEP_ORIGINAL, true);
                Context context = activity.getApplicationContext();
                STORAGE.execute(() -> publish(context, uri));
            }
        });
        XposedHelpers.findAndHookMethod(editor, "W0", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (editorDraft(activity) == null) return;
                // The host normally deletes published images through Gallery. This source is still a draft.
                param.setResult(null);
                activity.finish();
            }
        });
        XposedHelpers.findAndHookMethod(editor, "onDestroy", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                Uri uri = editorDraft(activity);
                if (uri == null || !activity.isFinishing()
                    || XposedHelpers.getAdditionalInstanceField(activity, KEEP_ORIGINAL) != null) return;
                delete(activity.getApplicationContext(), uri);
            }
        });
    }

    private static Uri editorDraft(Activity activity) {
        Intent intent = activity.getIntent();
        if (intent == null || !intent.getBooleanExtra(DRAFT_KEY, false)) return null;
        ComponentName component = intent.getComponent();
        // The translucent editor can forward the same intent to :photo_editor and finish.
        return component != null && component.getClassName().equals(activity.getClass().getName())
            ? intent.getData() : null;
    }

    private static Draft draftOf(Object host) {
        return host == null ? null : (Draft) XposedHelpers.getAdditionalInstanceField(host, DRAFT_KEY);
    }

    private static Draft scrollDraft(Object display) {
        return draftOf(XposedHelpers.getObjectField(display, "f"));
    }

    private static void publish(Context context, Uri uri) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        values.putNull(MediaStore.MediaColumns.DATE_EXPIRES);
        try {
            if (context.getContentResolver().update(uri, values, null, null) == 1) return;
        } catch (RuntimeException error) {
            HookLog.error("screenshot publish", error);
        }
        delete(context, uri);
        MAIN.post(() -> Toast.makeText(context, "保存截屏失败", Toast.LENGTH_SHORT).show());
    }

    private static void delete(Context context, Uri uri) {
        STORAGE.execute(() -> {
            try { context.getContentResolver().delete(uri, null, null); }
            catch (RuntimeException error) { HookLog.error("screenshot discard", error); }
        });
    }

    private static final class Draft {
        boolean thumbnailReady;
        boolean supportChecked;
        boolean longStarted;
        boolean scrolling;
        private Context context;
        private Uri uri;
        private boolean handedOff;
        private boolean discarded;

        synchronized void remember(Context context, Uri uri) {
            if (discarded) {
                delete(context, uri);
                return;
            }
            this.context = context;
            this.uri = uri;
        }

        synchronized boolean handoff() {
            if (discarded || handedOff || uri == null) return false;
            handedOff = true;
            return true;
        }

        synchronized void failedHandoff() {
            handedOff = false;
            discard();
        }

        synchronized void discard() {
            if (discarded || handedOff) return;
            discarded = true;
            if (uri != null) delete(context, uri);
        }

        synchronized boolean isDiscarded() { return discarded; }
    }
}
