package com.sysadminanywhere.m3.security;

import java.time.OffsetDateTime;

/** Internal write-only secret management boundary; implementations must not expose values in metadata. */
public interface SecretStore {
    void replace(String id, String value);
    SecretMetadata metadata(String id);

    record SecretMetadata(String id, OffsetDateTime updatedAt) {}
}
