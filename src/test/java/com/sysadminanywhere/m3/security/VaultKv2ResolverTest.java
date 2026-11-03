package com.sysadminanywhere.m3.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VaultKv2ResolverTest {
    @Test void readsKv2AndRereadsTokenWithoutExposingFailures(@TempDir Path dir) throws Exception {
        var seen=new AtomicReference<String>(); var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/secret/data/team/key",exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("X-Vault-Token"));
            byte[] body=("{\"data\":{\"data\":{\"private_key\":\"key-"+seen.get()+"\"}}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            Path token=Files.writeString(dir.resolve("token"),"token-one\n");
            var resolver=new VaultKv2Resolver("http://127.0.0.1:"+server.getAddress().getPort(),token.toString(),new ObjectMapper());
            assertEquals("key-token-one",new String(resolver.resolve("secret/team/key#private_key"),StandardCharsets.UTF_8));
            Files.writeString(token,"token-two"); assertEquals("key-token-two",new String(resolver.resolve("secret/team/key#private_key"),StandardCharsets.UTF_8));
            var error=assertThrows(IllegalStateException.class,() -> resolver.resolve("secret/team/key#missing"));
            assertFalse(error.toString().contains("token-two")); assertNull(error.getCause());
            assertThrows(IllegalStateException.class,() -> resolver.resolve("secret/../key#private_key"));
            assertThrows(IllegalStateException.class,() -> new VaultKv2Resolver("http://example.com",token.toString(),new ObjectMapper()).resolve("secret/team/key#private_key"));
        } finally { server.stop(0); }
    }
    @Test void rejectsUntrustedReferenceShapes() {
        assertTrue(VaultKv2Resolver.validReference("secret/team/key#private_key"));
        for (String value : java.util.List.of("secret#key","secret/../key#value","secret/a?x=1#v","secret/a#v/extra","secret/a#")) assertFalse(VaultKv2Resolver.validReference(value));
    }
    @Test void rejectsOversizedResponseAndRedirectWithoutLeakingBody(@TempDir Path dir) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/secret/data/oversized",exchange -> {
            exchange.sendResponseHeaders(200,1024*1024+1); try (var out=exchange.getResponseBody()) { out.write(new byte[1024*1024+1]); } catch (java.io.IOException ignored) { }
        });
        server.createContext("/v1/secret/data/redirect",exchange -> { exchange.getResponseHeaders().add("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/stolen"); exchange.sendResponseHeaders(302,-1); exchange.close(); });
        var stolen=new java.util.concurrent.atomic.AtomicInteger(); server.createContext("/stolen",exchange -> { stolen.incrementAndGet(); exchange.sendResponseHeaders(204,-1); exchange.close(); });
        server.start();
        try {
            Path token=Files.writeString(dir.resolve("token"),"sensitive-token"); var resolver=new VaultKv2Resolver("http://127.0.0.1:"+server.getAddress().getPort(),token.toString(),new ObjectMapper());
            for (String reference : java.util.List.of("secret/oversized#value","secret/redirect#value")) {
                var failure=assertThrows(IllegalStateException.class,() -> resolver.resolve(reference)); assertEquals("Unable to resolve Vault secret",failure.getMessage()); assertNull(failure.getCause());
            }
            assertEquals(0,stolen.get());
        } finally { server.stop(0); }
    }
    @Test void timesOutWhenResponseBodyStalls(@TempDir Path dir) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/secret/data/stalled",exchange -> {
            try { exchange.sendResponseHeaders(200,0); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); Thread.sleep(12000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } catch (java.io.IOException ignored) { }
            finally { exchange.close(); }
        }); server.start();
        try {
            Path token=Files.writeString(dir.resolve("token"),"test-token"); var resolver=new VaultKv2Resolver("http://127.0.0.1:"+server.getAddress().getPort(),token.toString(),new ObjectMapper());
            long start=System.nanoTime(); var failure=assertThrows(IllegalStateException.class,() -> resolver.resolve("secret/stalled#value"));
            assertEquals("Unable to resolve Vault secret",failure.getMessage()); assertTrue(java.time.Duration.ofNanos(System.nanoTime()-start).toSeconds()<20);
        } finally { server.stop(0); }
    }
}
