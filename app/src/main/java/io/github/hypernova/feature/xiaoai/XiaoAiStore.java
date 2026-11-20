// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.xiaoai;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class XiaoAiStore {
    private static final String ALIAS = "hypernova_xiaoai_configs_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte FORMAT = 1;
    private static final int IV_BYTES = 12, TAG_BITS = 128;
    private final AtomicFile file;
    private JSONObject document;
    private XiaoAiConfig cached;

    XiaoAiStore(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "hypernova_xiaoai_configs.bin"));
    }

    synchronized XiaoAiConfig read() throws Exception {
        if (cached != null) return cached;
        byte[] data;
        try { data = file.readFully(); }
        catch (FileNotFoundException missing) {
            if (file.getBaseFile().exists()) throw missing;
            document = new JSONObject();
            return cached = XiaoAiConfig.EMPTY;
        }
        byte[] clear = decrypt(key(false), data);
        document = new JSONObject(new String(clear, StandardCharsets.UTF_8));
        return cached = XiaoAiConfig.fromJson(document);
    }

    synchronized void save(XiaoAiConfig config) throws Exception {
        read();
        // Preserve unknown fields from an earlier build without exposing or using them.
        JSONObject next = config.toJson(new JSONObject(document.toString()));
        byte[] bytes = encrypt(key(true), next.toString().getBytes(StandardCharsets.UTF_8));
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(bytes);
            file.finishWrite(stream);
        } catch (Exception error) {
            file.failWrite(stream);
            throw error;
        }
        document = next;
        cached = config;
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.getKey(ALIAS, null) instanceof SecretKey saved) return saved;
        if (!create) throw new GeneralSecurityException("Missing configuration key");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    static byte[] encrypt(SecretKey key, byte[] clear) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV(), ciphertext = cipher.doFinal(clear);
        if (iv.length != IV_BYTES) throw new GeneralSecurityException("Unexpected GCM IV");
        return ByteBuffer.allocate(1 + IV_BYTES + ciphertext.length).put(FORMAT).put(iv).put(ciphertext).array();
    }

    static byte[] decrypt(SecretKey key, byte[] data) throws GeneralSecurityException {
        if (data.length < 1 + IV_BYTES + TAG_BITS / 8 || data[0] != FORMAT) {
            throw new GeneralSecurityException("Invalid configuration format");
        }
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 1, IV_BYTES));
        return cipher.doFinal(data, 1 + IV_BYTES, data.length - 1 - IV_BYTES);
    }
}
