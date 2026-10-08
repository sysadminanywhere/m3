package com.sysadminanywhere.m3.base.security;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;
class SecretCipherTest {
    private final SecretCipher cipher=new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
    @Test void ciphertextIsRandomizedAndRoundTripsWithoutExposingTheSecret() {
        String a=cipher.encrypt("пароль"),b=cipher.encrypt("пароль");
        assertThat(a).startsWith(SecretCipher.PREFIX).isNotEqualTo(b).doesNotContain("пароль");
        assertThat(cipher.decrypt(a)).isEqualTo("пароль");
        assertThat(cipher.decrypt("legacy")).isEqualTo("legacy");
    }
    @Test void rejectsModifiedCiphertextAndWrongKeys() {
        var stored=cipher.encrypt("secret");
        byte[] bytes=Base64.getDecoder().decode(stored.substring(SecretCipher.PREFIX.length())); bytes[bytes.length-1]^=1;
        assertThatThrownBy(()->cipher.decrypt(SecretCipher.PREFIX+Base64.getEncoder().encodeToString(bytes))).isInstanceOf(IllegalStateException.class);
        byte[] other=new byte[32]; other[0]=1;
        assertThatThrownBy(()->new SecretCipher(Base64.getEncoder().encodeToString(other)).decrypt(stored)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsMissingKeysAndRecognizesProtocolSecrets() {
        assertThatThrownBy(()->new SecretCipher("")).isInstanceOf(IllegalStateException.class);
        assertThat(SecretCipher.isSecret("kafka.sasl.jaas.config")).isTrue();
        assertThat(SecretCipher.isSecret("privateKeyPassphrase")).isTrue();
        assertThat(SecretCipher.isSecret("host")).isFalse();
    }
}
