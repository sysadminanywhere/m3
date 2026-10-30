package com.sysadminanywhere.m3.security;

import org.junit.jupiter.api.Test;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.util.Base64;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class SecretServiceTest {
    @Test void encryptsWithExternalKeyAndRandomIv() throws Exception {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        SecretService service = new SecretService(null, key);
        var first = service.encrypt("not-visible"); var second = service.encrypt("not-visible");
        assertFalse(java.util.Arrays.equals(first.ciphertext(), second.ciphertext()));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, SecretService.keyFrom(key), new GCMParameterSpec(128, first.iv()));
        assertEquals("not-visible", new String(cipher.doFinal(first.ciphertext())));
    }
    @Test void rejectsMissingKey() { assertThrows(IllegalStateException.class, () -> SecretService.keyFrom("")); }

    @Test void resolvesKeyFromExternalFileWhenEnvironmentValueIsAbsent() throws Exception {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        var file = Files.createTempFile("m3-secret-key-", ".txt");
        Files.writeString(file, "  " + key + "\n");
        assertEquals(key, SecretService.resolveExternalKey("", file.toString()));
        assertEquals(key, SecretService.resolveExternalKey(key, file.toString()));
        Files.deleteIfExists(file);
    }
}
