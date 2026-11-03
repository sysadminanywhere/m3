package com.sysadminanywhere.m3.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SftpPrivateKeyTest {
    @Test void authenticatesWithPinnedKeyAgainstLocalSftpServer(@TempDir Path dir) throws Exception {
        Path file=dir.resolve("client"); Process process;
        try { process=new ProcessBuilder("ssh-keygen","-q","-t","rsa","-b","2048","-N","","-f",file.toString()).redirectErrorStream(true).start(); }
        catch (java.io.IOException missing) { Assumptions.abort("ssh-keygen unavailable"); return; }
        assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0,process.exitValue());
        byte[] encoded=Files.readAllBytes(file); var identity=SftpPrivateKey.parse(encoded);
        var generator=java.security.KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var host=generator.generateKeyPair();
        String fingerprint=org.apache.sshd.common.config.keys.KeyUtils.getFingerPrint(org.apache.sshd.common.digest.BuiltinDigests.sha256,host.getPublic());
        var server=org.apache.sshd.server.SshServer.setUpDefaultServer(); server.setHost("127.0.0.1"); server.setPort(0);
        server.setKeyPairProvider(org.apache.sshd.common.keyprovider.KeyPairProvider.wrap(host));
        server.setPublickeyAuthenticator((user,key,session) -> user.equals("test") && org.apache.sshd.common.config.keys.KeyUtils.compareKeys(key,identity.getPublic()));
        server.setSubsystemFactories(java.util.List.of(new org.apache.sshd.sftp.server.SftpSubsystemFactory()));
        server.setFileSystemFactory(new org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(dir)); server.start();
        try {
            try (var connection=SftpConnection.open("127.0.0.1",server.getPort(),"test",fingerprint,"key-ref","PRIVATE_KEY",java.time.Duration.ofSeconds(10),ref -> encoded.clone())) {
                try (var out=connection.client().write("/payload")) { out.write("hello".getBytes(StandardCharsets.UTF_8)); }
            }
            assertEquals("hello",Files.readString(dir.resolve("payload")));
            assertThrows(java.io.IOException.class,() -> SftpConnection.open("127.0.0.1",server.getPort(),"test","SHA256:"+"A".repeat(43),"key-ref","PRIVATE_KEY",java.time.Duration.ofSeconds(10),ref -> encoded.clone()));
        } finally { server.stop(true); java.util.Arrays.fill(encoded,(byte)0); }
    }
    @Test void readsAnOpenSshIdentityWithoutLeakingInvalidInput(@TempDir Path dir) throws Exception {
        Path file=dir.resolve("identity"); Process process;
        try { process=new ProcessBuilder("ssh-keygen","-q","-t","rsa","-b","2048","-N","","-f",file.toString()).redirectErrorStream(true).start(); }
        catch (java.io.IOException missing) { Assumptions.abort("ssh-keygen unavailable"); return; }
        assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0,process.exitValue());
        byte[] encoded=Files.readAllBytes(file);
        try { assertEquals("RSA",SftpPrivateKey.parse(encoded).getPrivate().getAlgorithm()); }
        finally { java.util.Arrays.fill(encoded,(byte)0); }
        var error=assertThrows(java.io.IOException.class,() -> SftpPrivateKey.parse("sensitive-invalid-key".getBytes(StandardCharsets.UTF_8)));
        assertFalse(error.toString().contains("sensitive-invalid-key")); assertNull(error.getCause());
        Path encrypted=dir.resolve("encrypted");
        var protectedKey=new ProcessBuilder("ssh-keygen","-q","-t","rsa","-b","2048","-N","test-passphrase","-f",encrypted.toString()).redirectErrorStream(true).start();
        assertTrue(protectedKey.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0,protectedKey.exitValue());
        byte[] encryptedBytes=Files.readAllBytes(encrypted);
        try { assertThrows(java.io.IOException.class,() -> SftpPrivateKey.parse(encryptedBytes)); }
        finally { java.util.Arrays.fill(encryptedBytes,(byte)0); }
    }
}
