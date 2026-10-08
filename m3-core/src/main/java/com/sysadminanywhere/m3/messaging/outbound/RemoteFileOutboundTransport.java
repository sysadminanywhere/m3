package com.sysadminanywhere.m3.messaging.outbound;

import com.sysadminanywhere.m3.messaging.domain.*;
import org.apache.commons.net.ftp.FTPClient;
import org.springframework.core.io.FileSystemResource;
import org.springframework.integration.file.remote.session.*;
import org.springframework.integration.ftp.session.DefaultFtpSessionFactory;
import org.springframework.integration.sftp.session.DefaultSftpSessionFactory;
import org.springframework.stereotype.Component;
import java.io.*;
import java.util.*;

@Component
public class RemoteFileOutboundTransport implements OutboundTransport {
    @Override public Set<ChannelType> types() { return Set.of(ChannelType.FTP, ChannelType.SFTP); }
    @Override public void send(ChannelSettings channel, long id, PreparedOutboundDelivery delivery) throws Exception {
        var settings = com.sysadminanywhere.m3.messaging.source.InboundSourceSpec.from(channel);
        SessionFactory<?> factory;
        if (channel.getChannelType() == ChannelType.FTP) {
            var ftp = new DefaultFtpSessionFactory();
            ftp.setHost(settings.required("host")); ftp.setPort(settings.number("port", 21));
            ftp.setUsername(settings.required("username")); ftp.setPassword(settings.value("password", ""));
            ftp.setClientMode(settings.flag("passiveMode", true) ? FTPClient.PASSIVE_LOCAL_DATA_CONNECTION_MODE : FTPClient.ACTIVE_LOCAL_DATA_CONNECTION_MODE);
            ftp.setConnectTimeout(5000); ftp.setDefaultTimeout(5000); ftp.setDataTimeout(5000); factory = ftp;
        } else {
            var sftp = new DefaultSftpSessionFactory();
            sftp.setHost(settings.required("host")); sftp.setPort(settings.number("port", 22));
            sftp.setUser(settings.required("username")); sftp.setPassword(settings.value("password", ""));
            sftp.setTimeout(5000); sftp.setAllowUnknownKeys(settings.flag("allowUnknownKeys", false));
            if (!settings.flag("allowUnknownKeys", false)) sftp.setKnownHostsResource(new FileSystemResource(settings.required("knownHostsPath")));
            if (!settings.value("privateKey", "").isBlank()) sftp.setPrivateKey(new FileSystemResource(settings.value("privateKey", "")));
            if (!settings.value("privateKeyPassphrase", "").isBlank()) sftp.setPrivateKeyPassphrase(settings.value("privateKeyPassphrase", ""));
            factory = sftp;
        }
        String directory = settings.required("remoteDirectory");
        String prefix = directory.endsWith("/") ? directory : directory + "/";
        String target = prefix + OutboundChannelSender.fileName(id, delivery);
        String temporary = prefix + ".m3-" + id + "-" + UUID.randomUUID() + ".part";
        try (Session<?> session = factory.getSession()) {
            if (session.exists(target)) {
                byte[] previous;
                try (var input = session.readRaw(target)) { previous = input.readNBytes(delivery.body().length + 1); }
                if (!session.finalizeRaw() || !Arrays.equals(previous, delivery.body())) throw new IOException("Remote output file already exists with different data");
                return;
            }
            try {
            DeliveryAttempt.started();
                session.write(new ByteArrayInputStream(delivery.body()), temporary);
                session.rename(temporary, target);
            } finally {
                if (session.exists(temporary)) session.remove(temporary);
            }
        } finally { if (factory instanceof DefaultSftpSessionFactory sftp) sftp.destroy(); }
    }
}
