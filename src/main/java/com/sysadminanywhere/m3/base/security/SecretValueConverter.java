package com.sysadminanywhere.m3.base.security;

import jakarta.persistence.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Converter
@Component
public class SecretValueConverter implements AttributeConverter<String,String> {
    private final SecretCipher cipher;
    @Autowired public SecretValueConverter(SecretCipher cipher) { this.cipher=cipher; }
    /** Plain Hibernate tests can validate mappings without a Spring BeanContainer. */
    public SecretValueConverter() { this.cipher=null; }
    @Override public String convertToDatabaseColumn(String value) {
        if (value == null) return null;
        if (cipher == null) throw new IllegalStateException("Encrypted configuration requires a managed SecretCipher");
        return cipher.encrypt(value);
    }
    @Override public String convertToEntityAttribute(String value) {
        if (cipher == null) {
            if (value != null && value.startsWith(SecretCipher.PREFIX)) throw new IllegalStateException("Encrypted configuration requires a managed SecretCipher");
            return value;
        }
        return cipher.decrypt(value);
    }
}
