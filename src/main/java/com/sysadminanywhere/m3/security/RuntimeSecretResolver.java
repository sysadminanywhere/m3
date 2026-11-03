package com.sysadminanywhere.m3.security;

/** Internal runtime boundary. Callers must clear the returned bytes after use. */
public interface RuntimeSecretResolver {
    byte[] resolve(String id);
    default byte[] resolve(String provider,String id) {
        if (provider==null || "COMMUNITY".equals(provider)) return resolve(id);
        throw new IllegalStateException("Secret provider unavailable");
    }
}
