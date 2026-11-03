package com.sysadminanywhere.m3.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SftpConnectionTest {
    @Test void pinsHostKeyAndAuthenticatesAgainstRealSftp(@TempDir Path dir) throws Exception {
        try (SshServer server=SshServer.setUpDefaultServer()) {
            server.setHost("127.0.0.1"); server.setPort(0);
            server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve("host-key")));
            server.setPasswordAuthenticator((user,password,session) -> user.equals("test") && password.equals("secret"));
            server.setFileSystemFactory(new VirtualFileSystemFactory(dir));
            server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build())); server.start();
            String fingerprint=KeyUtils.getFingerPrint(BuiltinDigests.sha256,server.getKeyPairProvider().loadKeys(null).iterator().next().getPublic());
            byte[] secret="secret".getBytes(StandardCharsets.UTF_8);
            try (var connection=SftpConnection.open("127.0.0.1",server.getPort(),"test",fingerprint,"ref",Duration.ofSeconds(10),ref -> secret)) {
                try (var output=connection.client().write("/payload")) { output.write("test bytes".getBytes(StandardCharsets.UTF_8)); }
                try (var input=connection.client().read("/payload")) { assertEquals("test bytes",new String(input.readAllBytes(),StandardCharsets.UTF_8)); }
            }
            assertArrayEquals(new byte[6],secret);
            assertEquals("test bytes",Files.readString(dir.resolve("payload")));
            var rejected=assertThrows(java.io.IOException.class,() -> SftpConnection.open("127.0.0.1",server.getPort(),"test","SHA256:"+"A".repeat(43),"ref",Duration.ofSeconds(10),ref -> "secret".getBytes(StandardCharsets.UTF_8)));
            assertFalse(rejected.toString().contains("secret")); assertNull(rejected.getCause());
            assertThrows(java.io.IOException.class,() -> SftpConnection.open("127.0.0.1",server.getPort(),"test",fingerprint,"ref",Duration.ofSeconds(10),ref -> "wrong".getBytes(StandardCharsets.UTF_8)));
            assertThrows(IllegalArgumentException.class,() -> SftpConnection.open("127.0.0.1",server.getPort(),"test","invalid","ref",Duration.ofSeconds(10),ref -> new byte[0]));
        }
    }
}
