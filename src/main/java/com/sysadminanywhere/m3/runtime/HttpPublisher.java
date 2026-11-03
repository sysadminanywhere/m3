package com.sysadminanywhere.m3.runtime;

import com.sysadminanywhere.m3.security.RuntimeSecretResolver;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

/** HTTP transport with explicit uncertain outcomes after publication starts. */
final class HttpPublisher {
    static final class AmbiguousDelivery extends IOException { AmbiguousDelivery() { super("HTTP delivery outcome is unknown"); } }
    private HttpPublisher() {}
    static void publish(String endpoint,String tokenRef,Path original,String checksum,UUID jobId,
                        RuntimeSecretResolver secrets,DirectoryDeliveryScheduler.BeforePublish fence) throws IOException {
        java.net.URI uri;
        try { uri=com.sysadminanywhere.m3.security.HttpDeliveryEndpoint.parse(endpoint); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid HTTP delivery endpoint"); }
        if (jobId==null || checksum==null || !checksum.matches("[0-9a-f]{64}")) throw new IOException("Invalid HTTP delivery settings");
        Path temporary=Files.createTempFile("m3-http-delivery-",".part");
        try {
            Files.copy(original,temporary,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (var in=Files.newInputStream(temporary)) {
                var digest=MessageDigest.getInstance("SHA-256");
                in.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest));
                if (!MessageDigest.isEqual(HexFormat.of().parseHex(checksum),digest.digest())) throw new IOException("Original content checksum mismatch");
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Content-Type","application/octet-stream")
                    .header("Idempotency-Key",jobId.toString()).POST(HttpRequest.BodyPublishers.ofFile(temporary));
            if (tokenRef!=null && !tokenRef.isBlank()) {
                byte[] token=secrets.resolve(tokenRef);
                try { String value=new String(token,StandardCharsets.UTF_8);
                    if (value.isBlank() || value.chars().anyMatch(c -> c<33 || c>126)) throw new IOException("Invalid HTTP credential");
                    request.header("Authorization","Bearer "+value);
                } finally { Arrays.fill(token,(byte)0); }
            }
            var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).proxy(java.net.ProxySelector.of(null)).build();
            fence.check();
            try {
                int status=http.send(request.build(),HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status<200 || status>=300) throw new AmbiguousDelivery();
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AmbiguousDelivery(); }
            catch (IOException failure) { throw new AmbiguousDelivery(); }
        } finally { try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* Temporary cleanup can be retried by the operator. */ } }
    }
}
