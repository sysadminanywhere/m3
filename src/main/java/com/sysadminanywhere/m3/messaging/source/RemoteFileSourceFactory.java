package com.sysadminanywhere.m3.messaging.source;

import com.sysadminanywhere.m3.messaging.domain.ChannelType;
import com.sysadminanywhere.m3.messaging.service.SourceDeliveryService;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.sshd.sftp.client.SftpClient;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.integration.file.remote.session.Session;
import org.springframework.integration.file.remote.session.SessionFactory;
import org.springframework.integration.ftp.session.DefaultFtpSessionFactory;
import org.springframework.integration.sftp.session.DefaultSftpSessionFactory;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

@Component

public class RemoteFileSourceFactory implements InboundSourceFactory {
    private record Entry(String name, long size, long modified, boolean file) { }
    private final SourceDeliveryService deliveries;
    private final SourcePollScheduler polls;
    public RemoteFileSourceFactory(SourceDeliveryService deliveries, SourcePollScheduler polls) {
        this.deliveries = deliveries; this.polls = polls;
    }
    @Override public Set<ChannelType> types() { return Set.of(ChannelType.FTP, ChannelType.SFTP); }
    @Override public void validate(InboundSourceSpec source) {
        source.required("host"); source.required("username"); source.required("remoteDirectory");
        int port = source.number("port", source.type() == ChannelType.FTP ? 21 : 22);
        if (port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535");
        source.number("pollingInterval", 5000);
        source.number("minFileAgeMs", 1000);
        FilePayloads.matches(source.value("filePattern", "*"), "example");
        if (source.type() == ChannelType.SFTP && !source.flag("allowUnknownKeys", false)) {
            if (!Files.isRegularFile(Path.of(source.required("knownHostsPath"))))
                throw new IllegalArgumentException("knownHostsPath must name an existing OpenSSH known_hosts file");
        }
    }
    @Override public AutoCloseable start(InboundSourceSpec source) {
        SessionFactory<?> factory;
        if (source.type() == ChannelType.FTP) {
            var ftp = new DefaultFtpSessionFactory();
            ftp.setHost(source.required("host")); ftp.setPort(source.number("port", 21));
            ftp.setUsername(source.required("username")); ftp.setPassword(source.value("password", ""));
            ftp.setClientMode(source.flag("passiveMode", true) ? FTPClient.PASSIVE_LOCAL_DATA_CONNECTION_MODE : FTPClient.ACTIVE_LOCAL_DATA_CONNECTION_MODE);
            ftp.setConnectTimeout(5000); ftp.setDefaultTimeout(5000); ftp.setDataTimeout(5000);
            factory = ftp;
        } else {
            var sftp = new DefaultSftpSessionFactory();
            sftp.setHost(source.required("host")); sftp.setPort(source.number("port", 22));
            sftp.setUser(source.required("username")); sftp.setPassword(source.value("password", ""));
            sftp.setTimeout(5000); sftp.setAllowUnknownKeys(source.flag("allowUnknownKeys", false));
            if (!source.value("knownHostsPath", "").isBlank()) sftp.setKnownHostsResource(new FileSystemResource(source.value("knownHostsPath", "")));
            if (!source.value("privateKey", "").isBlank()) sftp.setPrivateKey(new FileSystemResource(source.value("privateKey", "")));
            if (!source.value("privateKeyPassphrase", "").isBlank()) sftp.setPrivateKeyPassphrase(source.value("privateKeyPassphrase", ""));
            factory = sftp;
        }
        var scheduled = polls.schedule(source, () -> poll(source, factory));
        return () -> {
            scheduled.close();
            if (factory instanceof DefaultSftpSessionFactory sftp) sftp.destroy();
        };
    }
    private void poll(InboundSourceSpec source, SessionFactory<?> factory) throws Exception {
        String directory = source.required("remoteDirectory");
        Object[] entries;
        try (Session<?> listing = factory.getSession()) { entries = listing.list(directory); }
        Exception failure = null;
        for (Object raw : entries) {
            if (Thread.currentThread().isInterrupted()) return;
            Entry entry = entry(raw);
            if (!entry.file() || entry.name().startsWith(".")
                    || !FilePayloads.matches(source.value("filePattern", "*"), entry.name())) continue;
            if (System.currentTimeMillis() - entry.modified() < source.number("minFileAgeMs", 1000)) continue;
            try (Session<?> session = factory.getSession()) {
                receiveFile(source, session, directory, entry);
            } catch (Exception error) {
                if (failure == null) failure = error;
            }
        }
        if (failure != null) throw failure;
    }

    private void receiveFile(InboundSourceSpec source, Session<?> session, String directory, Entry entry) throws IOException {
        String path = (directory.endsWith("/") ? directory : directory + "/") + entry.name();
        byte[] content;
        try (var input = session.readRaw(path)) { content = FilePayloads.read(input); }
        if (!session.finalizeRaw()) throw new IOException("Remote transfer did not complete");
        if (content.length != entry.size() || !entry.equals(find(session, directory, entry.name()))) return;
        String key = source.type() + ":" + source.value("host", "") + ":" + source.value("port", "")
                + ":" + source.value("username", "") + ":" + path + ":" + entry.modified() + ":" + SourceDeliveryService.digest(content);
        deliveries.receive(source, content, source.value("payloadType", "application/octet-stream"), Map.of("fileName", entry.name(), "filePath", path,
                "fileSize", Long.toString(content.length)), key);
        if (source.flag("deleteRemoteFiles", false) && entry.equals(find(session, directory, entry.name()))) {
            if (!session.remove(path)) throw new IOException("Remote file deletion failed after successful storage");
        }
    }
    private static Entry find(Session<?> session, String directory, String name) throws IOException {
        for (Object raw : session.list(directory)) { var entry = entry(raw); if (entry.name().equals(name)) return entry; }
        return null;
    }
    private static Entry entry(Object raw) {
        if (raw instanceof FTPFile ftp) return new Entry(ftp.getName(), ftp.getSize(),
                ftp.getTimestamp() == null ? 0 : ftp.getTimestamp().getTimeInMillis(), ftp.isFile());
        var sftp = (SftpClient.DirEntry) raw;
        var attributes = sftp.getAttributes();
        return new Entry(sftp.getFilename(), attributes.getSize(), attributes.getModifyTime().toMillis(), attributes.isRegularFile());
    }
}
