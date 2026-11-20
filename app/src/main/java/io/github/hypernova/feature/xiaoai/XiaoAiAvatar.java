// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.xiaoai;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Base64;
import android.widget.ImageView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;

final class XiaoAiAvatar {
    static final int ICON = 0x7f08059e; // icon_svg_person
    static final int OUTPUT_SIZE = 256;
    private static final int MAX_PREVIEW_SIDE = 2048;
    private final Activity activity;
    private final Consumer<Bitmap> result;
    private Object launcher;
    private boolean closed, pending;

    XiaoAiAvatar(Activity activity, Consumer<Bitmap> result) {
        this.activity = activity;
        this.result = result;
    }

    void choose() {
        if (closed || pending) return;
        try {
            if (launcher == null) register();
            pending = true;
            Intent picker = new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
            try { XposedHelpers.callMethod(launcher, "launch", picker); }
            catch (XposedHelpers.InvocationTargetError error) {
                if (!(error.getCause() instanceof ActivityNotFoundException)) throw error;
                Intent document = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
                XposedHelpers.callMethod(launcher, "launch", document);
            }
        } catch (Throwable error) {
            pending = false;
            fail("无法打开照片选择器", error);
        }
    }

    private void register() {
        ClassLoader loader = activity.getClassLoader();
        Object registry = XposedHelpers.callMethod(activity, "getActivityResultRegistry");
        Class<?> contract = XposedHelpers.findClass("androidx.activity.result.contract.ActivityResultContracts$StartActivityForResult", loader);
        Class<?> callback = XposedHelpers.findClass("androidx.activity.result.ActivityResultCallback", loader);
        Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{callback}, (self, method, args) ->
                switch (method.getName()) {
                    case "onActivityResult" -> { accept(args[0]); yield null; }
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    case "toString" -> "HYPERNOVAXiaoAiAvatarPicker";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        launcher = XposedHelpers.callMethod(registry, "register", "hypernova_persona_" + UUID.randomUUID(),
                XposedHelpers.newInstance(contract), listener);
    }

    private void accept(Object reply) {
        if (closed) return;
        try {
            if ((int) XposedHelpers.callMethod(reply, "getResultCode") != Activity.RESULT_OK
                    || !(XposedHelpers.callMethod(reply, "getData") instanceof Intent data)
                    || data.getData() == null || !"content".equals(data.getData().getScheme())) {
                pending = false;
                return;
            }
            Uri image = data.getData();
            new Thread(() -> {
                try {
                    Bitmap photo = decodePreview(image);
                    activity.runOnUiThread(() -> {
                        pending = false;
                        if (!closed) result.accept(photo);
                    });
                } catch (Exception error) {
                    activity.runOnUiThread(() -> {
                        pending = false;
                        if (!closed) fail("无法读取所选头像", error);
                    });
                }
            }, "HYPERNOVA-XiaoAi-avatar").start();
        } catch (Throwable error) {
            pending = false;
            fail("无法读取所选头像", error);
        }
    }

    private Bitmap decodePreview(Uri image) throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(activity.getContentResolver(), image);
        return ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
            int width = info.getSize().getWidth(), height = info.getSize().getHeight();
            float scale = Math.min(1f, (float) MAX_PREVIEW_SIDE / Math.max(width, height));
            decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    static String encode(Bitmap square) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!square.compress(Bitmap.CompressFormat.WEBP_LOSSY, 86, bytes)) throw new IOException("Avatar encoding failed");
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
    }

    static ImageView view(Activity activity, String avatar) {
        ImageView view = new ImageView(activity);
        var circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x22808080);
        view.setBackground(circle);
        view.setClipToOutline(true);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setContentDescription("人格头像");
        display(view, avatar);
        return view;
    }

    static void display(ImageView view, String avatar) {
        if (!avatar.isEmpty()) {
            try {
                byte[] pixels = Base64.decode(avatar, Base64.DEFAULT);
                Bitmap image = BitmapFactory.decodeByteArray(pixels, 0, pixels.length);
                if (image != null) {
                    view.setImageBitmap(image);
                    return;
                }
            } catch (IllegalArgumentException ignored) { }
        }
        view.setImageResource(ICON);
    }

    boolean pending() { return pending; }

    void close() {
        closed = true;
        if (launcher == null) return;
        try { XposedHelpers.callMethod(launcher, "unregister"); }
        catch (Throwable error) { HookLog.error("XiaoAi avatar unregister", error); }
        launcher = null;
    }

    private void fail(String label, Throwable error) {
        HookLog.error("XiaoAi avatar pick", error);
        Toast.makeText(activity, label, Toast.LENGTH_SHORT).show();
    }
}
