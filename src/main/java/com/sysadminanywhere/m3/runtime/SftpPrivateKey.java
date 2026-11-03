package com.sysadminanywhere.m3.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.KeyPair;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;

/** Parses one unencrypted SSH identity without disclosing its contents in errors. */
final class SftpPrivateKey {
    private SftpPrivateKey() {}
    static KeyPair parse(byte[] encoded) throws IOException {
        if (encoded==null || encoded.length==0 || encoded.length>1024*1024) throw new IOException("Invalid SFTP private key");
        try (var stream=new ByteArrayInputStream(encoded)) {
            var keys=SecurityUtils.loadKeyPairIdentities(null,NamedResource.ofName("sftp-identity"),stream,FilePasswordProvider.EMPTY).iterator();
            if (!keys.hasNext()) throw new IOException("Invalid SFTP private key");
            KeyPair key=keys.next();
            if (keys.hasNext() || key==null || key.getPrivate()==null || key.getPublic()==null) throw new IOException("Invalid SFTP private key");
            return key;
        } catch (Exception failure) { throw new IOException("Invalid SFTP private key"); }
    }
}
