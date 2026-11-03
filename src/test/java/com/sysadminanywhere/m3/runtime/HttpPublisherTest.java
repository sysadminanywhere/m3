package com.sysadminanywhere.m3.runtime;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HttpPublisherTest {
    @Test void sendsExactBytesAndClassifiesUnknownResponses(@TempDir Path dir) throws Exception {
        byte[] payload="test payload".getBytes(StandardCharsets.UTF_8); Path file=Files.write(dir.resolve("payload"),payload);
        String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        var calls=new AtomicInteger(); var received=new AtomicReference<byte[]>(); var auth=new AtomicReference<String>(); var key=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ok",exchange -> { calls.incrementAndGet(); received.set(exchange.getRequestBody().readAllBytes());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization")); key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key")); exchange.sendResponseHeaders(204,-1); exchange.close(); });
        server.createContext("/failure",exchange -> { calls.incrementAndGet(); exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(500,-1); exchange.close(); });
        server.createContext("/disconnect",exchange -> { calls.incrementAndGet(); received.set(exchange.getRequestBody().readAllBytes()); exchange.close(); });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort(); UUID id=UUID.randomUUID();
            HttpPublisher.publish(base+"/ok","ref",file,checksum,id,ref -> "secret-token".getBytes(StandardCharsets.UTF_8),() -> {});
            assertArrayEquals(payload,received.get()); assertEquals("Bearer secret-token",auth.get()); assertEquals(id.toString(),key.get());
            assertThrows(HttpPublisher.AmbiguousDelivery.class,() -> HttpPublisher.publish(base+"/failure","",file,checksum,id,ref -> new byte[0],() -> {}));
            assertThrows(java.io.IOException.class,() -> HttpPublisher.publish(base+"/ok","",file,"0".repeat(64),id,ref -> new byte[0],() -> {}));
            assertThrows(java.io.IOException.class,() -> HttpPublisher.publish(base+"/ok","",file,checksum,id,ref -> new byte[0],() -> { throw new java.io.IOException("lease lost"); }));
            assertEquals(2,calls.get());
            assertThrows(HttpPublisher.AmbiguousDelivery.class,() -> HttpPublisher.publish(base+"/disconnect","",file,checksum,id,ref -> new byte[0],() -> {}));
            assertArrayEquals(payload,received.get());
            assertEquals(3,calls.get(), "A lost response must not trigger an automatic second POST");
            assertThrows(java.io.IOException.class,() -> HttpPublisher.publish("http://example.com/hook","",file,checksum,id,ref -> new byte[0],() -> {}));
        } finally { server.stop(0); }
    }
}
