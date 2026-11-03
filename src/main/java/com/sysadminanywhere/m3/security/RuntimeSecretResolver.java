package com.sysadminanywhere.m3.security;

/** Internal runtime boundary. Callers must clear the returned bytes after use. */
public interface RuntimeSecretResolver {
    byte[] resolve(String id);
}
