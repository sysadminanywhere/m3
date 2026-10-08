package com.sysadminanywhere.m3.messaging.source;

import com.sysadminanywhere.m3.messaging.domain.ChannelType;
import com.sysadminanywhere.m3.messaging.service.SourceDeliveryService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Set;

@Component

public class DirectorySourceFactory implements InboundSourceFactory {
    private final SourceDeliveryService deliveries;
    private final SourcePollScheduler polls;
    public DirectorySourceFactory(SourceDeliveryService deliveries, SourcePollScheduler polls) { this.deliveries = deliveries; this.polls = polls; }
    @Override public Set<ChannelType> types() { return Set.of(ChannelType.DIRECTORY); }
    @Override public void validate(InboundSourceSpec source) {
        Path.of(source.required("directoryPath"));
        source.number("pollingInterval", 5000);
        source.number("minFileAgeMs", 1000);
        FilePayloads.matches(source.value("filePattern", "*"), "example");
    }
    @Override public AutoCloseable start(InboundSourceSpec source) throws IOException {
        if (java.io.File.separatorChar != '\\' && source.required("directoryPath").matches("(?i)^[a-z]:[\\\\/].*"))
            throw new IllegalArgumentException("Windows source path is unavailable in a Linux worker; mount the directory and use its container path");
        Path root = Path.of(source.required("directoryPath")).toAbsolutePath().normalize();
        if (!Files.isDirectory(root))
            throw new IllegalArgumentException("Source directory does not exist in the worker filesystem: " + root);
        return polls.schedule(source, () -> poll(source, root));
    }
    void poll(InboundSourceSpec source, Path root) throws Exception {
        int depth = source.flag("recursive", false) ? Integer.MAX_VALUE : 1;
        Exception failure = null;
        try (var paths = Files.walk(root, depth)) {
            for (Path path : paths.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .filter(file -> !file.getFileName().toString().startsWith("."))
                    .filter(file -> FilePayloads.matches(source.value("filePattern", "*"), file.getFileName().toString()))
                    .toList()) {
                if (Thread.currentThread().isInterrupted()) return;
                try {
                    receiveFile(source, path);
                } catch (Exception error) {
                    // A bad or temporarily unavailable file must not block the rest of the directory.
                    if (failure == null) failure = error;
                }
            }
        }
        if (failure != null) throw failure;
    }

    private void receiveFile(InboundSourceSpec source, Path path) throws IOException {
        var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (System.currentTimeMillis() - before.lastModifiedTime().toMillis() < source.number("minFileAgeMs", 1000)) return;
        byte[] content;
        try (var input = Files.newInputStream(path)) { content = FilePayloads.read(input); }
        var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!unchanged(before, after)) return;
        String key = path + ":" + before.lastModifiedTime().toMillis() + ":" + SourceDeliveryService.digest(content);
        deliveries.receive(source, content, source.value("payloadType", "application/octet-stream"), Map.of("fileName", path.getFileName().toString(),
                "filePath", path.toString(), "fileSize", Long.toString(content.length)), key);
        // The transactional receive method has returned: message, metadata and outbox have committed.
        if (source.flag("deleteAfterProcessing", false)
                && unchanged(before, Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS))) {
            Files.delete(path);
        }
    }
    private static boolean unchanged(BasicFileAttributes left, BasicFileAttributes right) {
        return left.size() == right.size() && left.lastModifiedTime().equals(right.lastModifiedTime())
                && java.util.Objects.equals(left.fileKey(), right.fileKey());
    }
}
