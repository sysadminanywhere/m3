package com.sysadminanywhere.m3.messaging.source;

import com.sysadminanywhere.m3.messaging.domain.Message;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Path;

public final class FilePayloads {
    private FilePayloads() { }
    public static byte[] read(InputStream input) throws IOException {
        int maxBytes = Message.PAYLOAD_MAX_BYTES;
        byte[] bytes = input.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) throw new IOException("File exceeds the supported payload size");
        return bytes;
    }
    public static boolean matches(String pattern, String name) {
        return FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(Path.of(name));
    }
}
