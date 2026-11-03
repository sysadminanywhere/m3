package com.sysadminanywhere.m3.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class SecretService implements SecretStore, RuntimeSecretResolver {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final SecretKey key;
    private final VaultKv2Resolver vault;

    @Autowired
    public SecretService(JdbcTemplate jdbc, @Value("${M3_SECRET_KEY:}") String encodedKey,
                         @Value("${M3_SECRET_KEY_FILE:}") String keyFile, @org.springframework.context.annotation.Lazy VaultKv2Resolver vault) {
        this.jdbc = jdbc;
        this.key = keyFrom(resolveExternalKey(encodedKey, keyFile));
        this.vault=vault;
    }

    public SecretService(JdbcTemplate jdbc, String encodedKey) {
        this.jdbc = jdbc;
        this.key = keyFrom(encodedKey);
        this.vault=null;
    }

    public void replace(String id, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Secret value must not be empty");
        Encrypted encrypted = encrypt(value);
        jdbc.update("insert into community.secret_record(id, ciphertext, iv, updated_at) values (?, ?, ?, now()) " +
                        "on conflict (id) do update set ciphertext=excluded.ciphertext, iv=excluded.iv, updated_at=excluded.updated_at",
                id, encrypted.ciphertext(), encrypted.iv());
    }

    public SecretMetadata metadata(String id) {
        return jdbc.queryForObject("select id, updated_at from community.secret_record where id=?", (rs, row) ->
                new SecretMetadata(rs.getString(1), rs.getObject(2, java.time.OffsetDateTime.class)), id);
    }

    @Override public byte[] resolve(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Secret reference is required");
        try {
            Encrypted encrypted=jdbc.queryForObject("select ciphertext,iv from community.secret_record where id=?",
                    (rs,row) -> new Encrypted(rs.getBytes(1),rs.getBytes(2)),id);
            if (encrypted == null) throw new IllegalStateException();
            return decrypt(encrypted);
        } catch (Exception failure) { throw new IllegalStateException("Unable to resolve secret reference"); }
    }
    @Override public byte[] resolve(String provider,String id) {
        if (provider==null || "COMMUNITY".equals(provider)) return resolve(id);
        if ("VAULT".equals(provider) && vault!=null) return vault.resolve(id);
        throw new IllegalStateException("Secret provider unavailable");
    }

    private byte[] decrypt(Encrypted encrypted) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,encrypted.iv()));
        return cipher.doFinal(encrypted.ciphertext());
    }

    Encrypted encrypt(String value) {
        try {
            byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new Encrypted(cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), iv);
        } catch (Exception e) { throw new IllegalStateException("Unable to encrypt secret", e); }
    }

    static SecretKey keyFrom(String encoded) {
        try {
            byte[] raw = Base64.getDecoder().decode(encoded);
            if (raw.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(raw, "AES");
        } catch (Exception e) { throw new IllegalStateException("M3_SECRET_KEY must be a base64-encoded 32-byte key"); }
    }
    static String resolveExternalKey(String encodedKey, String keyFile) {
        if (encodedKey != null && !encodedKey.isBlank()) return encodedKey.trim();
        if (keyFile == null || keyFile.isBlank()) return "";
        try { return java.nio.file.Files.readString(java.nio.file.Path.of(keyFile)).trim(); }
        catch (Exception e) { throw new IllegalStateException("Unable to read M3_SECRET_KEY_FILE"); }
    }
    record Encrypted(byte[] ciphertext, byte[] iv) {}
}
