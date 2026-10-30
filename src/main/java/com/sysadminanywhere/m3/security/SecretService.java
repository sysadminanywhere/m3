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
import java.time.OffsetDateTime;
import java.util.Base64;

@Service
public class SecretService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final SecretKey key;

    @Autowired
    public SecretService(JdbcTemplate jdbc, @Value("${M3_SECRET_KEY:}") String encodedKey,
                         @Value("${M3_SECRET_KEY_FILE:}") String keyFile) {
        this.jdbc = jdbc;
        this.key = keyFrom(resolveExternalKey(encodedKey, keyFile));
    }

    public SecretService(JdbcTemplate jdbc, String encodedKey) {
        this.jdbc = jdbc;
        this.key = keyFrom(encodedKey);
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
                new SecretMetadata(rs.getString(1), rs.getObject(2, OffsetDateTime.class)), id);
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
    public record SecretMetadata(String id, OffsetDateTime updatedAt) {}
}
