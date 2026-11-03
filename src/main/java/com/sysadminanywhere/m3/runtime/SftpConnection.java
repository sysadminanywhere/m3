package com.sysadminanywhere.m3.runtime;

import com.sysadminanywhere.m3.security.RuntimeSecretResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.auth.BuiltinUserAuthFactories;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.sftp.SftpModuleProperties;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

/** Internal transport; not yet exposed as a supported configuration type. */
final class SftpConnection implements AutoCloseable {
    private final SshClient client;
    private final ClientSession session;
    private final SftpClient sftp;
    private SftpConnection(SshClient client, ClientSession session, SftpClient sftp) {
        this.client=client; this.session=session; this.sftp=sftp;
    }
    static SftpConnection open(String host, int port, String user, String fingerprint, String credentialRef,
                               Duration timeout, RuntimeSecretResolver secrets) throws IOException {
        if (host==null || host.isBlank() || user==null || user.isBlank() || port<1 || port>65535 || timeout==null
                || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(2))>0
                || fingerprint==null || !fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}"))
            throw new IllegalArgumentException("Invalid SFTP connection settings");
        SshClient client=SshClient.setUpDefaultClient(); ClientSession session=null;
        client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        client.setUserAuthFactories(List.of(BuiltinUserAuthFactories.PASSWORD.create()));
        client.setServerKeyVerifier((ignored,address,key) -> MessageDigest.isEqual(
                fingerprint.getBytes(StandardCharsets.US_ASCII),
                KeyUtils.getFingerPrint(BuiltinDigests.sha256,key).getBytes(StandardCharsets.US_ASCII)));
        CoreModuleProperties.IO_CONNECT_TIMEOUT.set(client,timeout);
        CoreModuleProperties.NIO2_READ_TIMEOUT.set(client,timeout);
        CoreModuleProperties.IDLE_TIMEOUT.set(client,timeout);
        SftpModuleProperties.SFTP_CHANNEL_OPEN_TIMEOUT.set(client,timeout);
        try {
            client.start();
            session=client.connect(user,host,port).verify(timeout).getSession();
            byte[] password=secrets.resolve(credentialRef);
            try { session.addPasswordIdentity(new String(password,StandardCharsets.UTF_8)); }
            finally { Arrays.fill(password,(byte)0); }
            session.auth().verify(timeout);
            return new SftpConnection(client,session,SftpClientFactory.instance().createSftpClient(session));
        } catch (Exception failure) {
            if (session!=null) try { session.close(); } catch (Exception ignored) { }
            client.stop();
            throw new IOException("SFTP connection or authentication failed");
        }
    }
    SftpClient client() { return sftp; }
    @Override public void close() throws IOException {
        try { sftp.close(); } finally { try { session.close(); } finally { client.stop(); } }
    }
}
