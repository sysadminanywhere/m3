package com.sysadminanywhere.m3.security;

import org.junit.jupiter.api.Test;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.util.Base64;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

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

    @Test void resolvesOnlyAtRuntimeAndFailsClosedOnTampering() throws Exception {
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var service=new SecretService(jdbc,Base64.getEncoder().encodeToString(new byte[32]));
        var encrypted=service.encrypt("runtime-only");
        when(jdbc.queryForObject(anyString(),any(org.springframework.jdbc.core.RowMapper.class),anyString())).thenAnswer(call -> {
            var rs=mock(java.sql.ResultSet.class); when(rs.getBytes(1)).thenReturn(encrypted.ciphertext()); when(rs.getBytes(2)).thenReturn(encrypted.iv());
            org.springframework.jdbc.core.RowMapper<?> row=call.getArgument(1); return row.mapRow(rs,0);
        });
        byte[] value=service.resolve("ref");
        assertEquals("runtime-only",new String(value,java.nio.charset.StandardCharsets.UTF_8)); java.util.Arrays.fill(value,(byte)0);
        encrypted.ciphertext()[0]^=1;
        var error=assertThrows(IllegalStateException.class,() -> service.resolve("ref"));
        assertEquals("Unable to resolve secret reference",error.getMessage()); assertNull(error.getCause());
    }

    @Test void resolvesKeyFromExternalFileWhenEnvironmentValueIsAbsent() throws Exception {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        var file = Files.createTempFile("m3-secret-key-", ".txt");
        Files.writeString(file, "  " + key + "\n");
        assertEquals(key, SecretService.resolveExternalKey("", file.toString()));
        assertEquals(key, SecretService.resolveExternalKey(key, file.toString()));
        Files.deleteIfExists(file);
    }
}
