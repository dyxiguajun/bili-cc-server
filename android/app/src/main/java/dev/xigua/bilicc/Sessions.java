package dev.xigua.bilicc;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

final class Sessions implements CookieJar {
    private static final String SESSION = "__Host-bilicc_session";
    private static final String QR = "__Host-bilicc_qr";
    private static final String KEY_ALIAS = "bili_cc_nas_session_v1";
    private final SharedPreferences encrypted;
    private final Map<String, Map<String, Cookie>> byHost = new HashMap<>();

    Sessions(Context context) {
        encrypted = context.getSharedPreferences("nas_sessions", Context.MODE_PRIVATE);
    }

    @Override
    public synchronized void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        if (!url.isHttps()) return;
        Map<String, Cookie> current = cookiesFor(url);
        for (Cookie cookie : cookies) {
            String name = cookie.name();
            if (!(name.equals(SESSION) || name.equals(QR)) || !cookie.hostOnly() || !cookie.secure()
                    || !cookie.httpOnly() || !cookie.path().equals("/") || !cookie.domain().equals(url.host())) {
                continue;
            }
            if (cookie.expiresAt() <= System.currentTimeMillis()) {
                current.remove(name);
                if (name.equals(SESSION)) encrypted.edit().remove(url.host()).apply();
            } else {
                current.put(name, cookie);
                if (name.equals(SESSION)) saveEncrypted(url.host(), cookie);
            }
        }
    }

    @Override
    public synchronized List<Cookie> loadForRequest(HttpUrl url) {
        List<Cookie> result = new ArrayList<>();
        if (!url.isHttps()) return result;
        for (Cookie cookie : cookiesFor(url).values()) {
            if (cookie.expiresAt() > System.currentTimeMillis() && cookie.matches(url)) result.add(cookie);
        }
        return result;
    }

    private Map<String, Cookie> cookiesFor(HttpUrl url) {
        return byHost.computeIfAbsent(url.host(), host -> {
            Map<String, Cookie> values = new HashMap<>();
            String saved = encrypted.getString(host, null);
            if (saved != null) {
                try {
                    String[] parts = saved.split(":", 2);
                    byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
                    cipher.updateAAD(host.getBytes(StandardCharsets.UTF_8));
                    byte[] plain = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP));
                    Cookie cookie = Cookie.parse(url, new String(plain, StandardCharsets.UTF_8));
                    if (cookie != null && cookie.expiresAt() > System.currentTimeMillis()) values.put(SESSION, cookie);
                    else encrypted.edit().remove(host).apply();
                } catch (Exception invalid) {
                    encrypted.edit().remove(host).apply();
                }
            }
            return values;
        });
    }

    private void saveEncrypted(String host, Cookie cookie) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            cipher.updateAAD(host.getBytes(StandardCharsets.UTF_8));
            byte[] data = cipher.doFinal(cookie.toString().getBytes(StandardCharsets.UTF_8));
            String sealed = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                    + ":" + Base64.encodeToString(data, Base64.NO_WRAP);
            encrypted.edit().putString(host, sealed).apply();
        } catch (Exception unavailable) {
            // Keep the current session in memory when Android Keystore is unavailable.
            encrypted.edit().remove(host).apply();
        }
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey present = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (present != null) return present;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
