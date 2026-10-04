package com.sysadminanywhere.m3.messaging.outbound;

import com.sysadminanywhere.m3.messaging.domain.*;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Component
public class DirectoryOutboundTransport implements OutboundTransport {
    @Override public Set<ChannelType> types() { return Set.of(ChannelType.DIRECTORY); }
    @Override public void send(ChannelSettings channel, long id, PreparedOutboundDelivery delivery) throws IOException {
        String directory = channel.getProperties().get("directoryPath");
        if (directory == null || directory.isBlank()) throw new IllegalArgumentException("directoryPath is required");
        if (java.io.File.separatorChar != '\\' && directory.matches("(?i)^[a-z]:[\\\\/].*"))
            throw new IllegalArgumentException("Windows destination path is unavailable in a Linux worker; mount the directory and use its container path");
        Path root = Path.of(directory).toAbsolutePath().normalize();
        Path target = root.resolve(OutboundChannelSender.fileName(id, delivery)).normalize();
        if (!target.getParent().equals(root)) throw new IllegalArgumentException("Output file must stay in the configured directory");
        Files.createDirectories(root);
        if (Files.exists(target)) {
            verifyExisting(target, delivery.body()); return;
        }
        Path temporary = Files.createTempFile(root, ".m3-" + id + "-", ".part");
        try {
            Files.write(temporary, delivery.body());
            try { Files.move(temporary, target); }
            catch (FileAlreadyExistsException anotherWorkerFinished) { verifyExisting(target, delivery.body()); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void verifyExisting(Path target, byte[] expected) throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != expected.length
                || !Arrays.equals(Files.readAllBytes(target), expected)) throw new IOException("Output file already exists with different data");
    }
}
