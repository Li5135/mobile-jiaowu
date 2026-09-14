package com.hynu.jiaowu;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 账号密码本地存储，使用 Android Keystore 中的 AES/GCM 密钥加密。
 *
 * <p>密钥由系统 Keystore 生成并保管，永远不出安全硬件/系统进程；本应用只持有密钥别名，
 * 落盘内容为「IV + 密文（含 GCM 认证标签）」的 Base64。因此即使应用私有目录被读取，
 * 也无法在设备之外还原明文密码。
 *
 * <p>刻意不引入 {@code androidx.security:security-crypto}：该库已被 AndroidX 弃用，
 * 且其 API 长期停留在 alpha。这里只依赖 API 23+ 就稳定的平台接口。
 */
final class CredentialVault {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "jiaowu_cred_key";
    private static final String PREFS_NAME = "jiaowu_creds";
    private static final String KEY_ACCT = "acct";
    private static final String KEY_PWD = "pwd";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    /** Keystore 不可用时的降级标记，避免每次读写都重复尝试初始化 */
    private static boolean sKeystoreUnavailable = false;

    private CredentialVault() {}

    /** 保存凭证。加密或写入失败时返回 false，调用方应据此提示用户。 */
    static boolean save(Context ctx, String acct, String pwd) {
        SecretKey key = secretKey();
        if (key == null) return false;
        try {
            String encAcct = encrypt(key, acct);
            String encPwd = encrypt(key, pwd);
            if (encAcct == null || encPwd == null) return false;
            // commit() 而非 apply()：凭证很小，这里需要确定性的落盘结果以便返回真实成败
            return prefs(ctx).edit().putString(KEY_ACCT, encAcct).putString(KEY_PWD, encPwd).commit();
        } catch (Exception e) {
            return false;
        }
    }

    /** 读取凭证，返回 {@code [账号, 密码]}；无有效凭证时返回 null。 */
    static String[] load(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        String encAcct = sp.getString(KEY_ACCT, null);
        String encPwd = sp.getString(KEY_PWD, null);
        if (encAcct == null || encPwd == null) return null;
        SecretKey key = secretKey();
        if (key == null) return null;
        try {
            String acct = decrypt(key, encAcct);
            String pwd = decrypt(key, encPwd);
            if (acct == null || acct.isEmpty() || pwd == null || pwd.isEmpty()) return null;
            return new String[] { acct, pwd };
        } catch (Exception e) {
            // 密钥被清除 / 备份还原到新设备 / 数据损坏：清掉无法解密的残留，让用户重新登录一次
            clear(ctx);
            return null;
        }
    }

    /** 清除已保存的凭证（退出账号、或解密失败时调用）。 */
    static void clear(Context ctx) {
        try {
            prefs(ctx).edit().remove(KEY_ACCT).remove(KEY_PWD).commit();
        } catch (Exception ignored) {
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static SecretKey secretKey() {
        if (sKeystoreUnavailable) return null;
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            java.security.Key existing = ks.getKey(KEY_ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            kg.init(
                new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // 不要求用户认证：App 需要在冷启动时静默填充，不能弹指纹/密码
                    .setUserAuthenticationRequired(false)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            );
            return kg.generateKey();
        } catch (Exception e) {
            sKeystoreUnavailable = true;
            return null;
        }
    }

    /** 加密并打包为 Base64(IV || ciphertext)。GCM 每次加密都会用新的随机 IV。 */
    private static String encrypt(SecretKey key, String plain) throws Exception {
        if (plain == null || plain.isEmpty()) return null;
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] body = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + body.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(body, 0, out, iv.length, body.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private static String decrypt(SecretKey key, String packed) throws Exception {
        byte[] raw = Base64.decode(packed, Base64.NO_WRAP);
        if (raw.length <= GCM_IV_BYTES) return null;
        byte[] iv = new byte[GCM_IV_BYTES];
        System.arraycopy(raw, 0, iv, 0, GCM_IV_BYTES);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] body = cipher.doFinal(raw, GCM_IV_BYTES, raw.length - GCM_IV_BYTES);
        return new String(body, StandardCharsets.UTF_8);
    }
}
