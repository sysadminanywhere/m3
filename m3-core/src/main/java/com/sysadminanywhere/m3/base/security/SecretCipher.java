package com.sysadminanywhere.m3.base.security;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class SecretCipher {
    public static final String PREFIX = "m3enc:v1:";
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    public SecretCipher(@Value("${m3.secret-key}") String encoded) {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("M3_SECRET_KEY must be a Base64 AES-256 key"); }
        if (bytes.length != 32) throw new IllegalStateException("M3_SECRET_KEY must decode to 32 bytes; preserve the existing key on upgrades");
        key = new SecretKeySpec(bytes, "AES");
    }
    public String encrypt(String value) {
        if (value == null) return null;
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));
            cipher.updateAAD(PREFIX.getBytes(StandardCharsets.UTF_8));
            byte[] data = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(iv.length+data.length).put(iv).put(data).array());
        } catch (Exception failure) { throw new IllegalStateException("Could not encrypt stored configuration",failure); }
    }
    public String decrypt(String value) {
        if (value == null || !value.startsWith(PREFIX)) return value; // Legacy rows are rewritten by the startup migrator.
        try {
            byte[] bytes=Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (bytes.length < 28) throw new IllegalArgumentException();
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,bytes,0,12)); cipher.updateAAD(PREFIX.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(bytes,12,bytes.length-12),StandardCharsets.UTF_8);
        } catch (Exception invalid) { throw new IllegalStateException("Stored configuration could not be decrypted; check the encryption key",invalid); }
    }
    public static boolean isSecret(String name) {
        String key=name.toLowerCase(java.util.Locale.ROOT);
        return key.contains("password") || key.contains("passphrase") || key.contains("secret") || key.contains("token") || key.endsWith("jaas.config");
    }
}
