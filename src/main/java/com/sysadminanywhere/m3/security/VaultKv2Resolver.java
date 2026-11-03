package com.sysadminanywhere.m3.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Read-only Vault KV v2 resolver. Credentials and responses are never included in errors. */
@Component
public class VaultKv2Resolver {
    private static final int MAX_BYTES=1024*1024;
    private final String address,tokenFile;
    private final ObjectMapper mapper;
    public VaultKv2Resolver(@Value("${M3_VAULT_ADDR:}") String address,@Value("${M3_VAULT_TOKEN_FILE:}") String tokenFile,ObjectMapper mapper) {
        this.address=address; this.tokenFile=tokenFile; this.mapper=mapper;
    }
    public static boolean validReference(String reference) {
        return reference!=null && reference.length()<=512 && reference.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)+#[A-Za-z0-9_-]+");
    }
    public byte[] resolve(String reference) {
        try {
            if (!validReference(reference) || tokenFile==null || tokenFile.isBlank()) throw new IllegalArgumentException();
            URI base=URI.create(address); String host=base.getHost();
            boolean local=host!=null && java.util.Set.of("localhost","127.0.0.1","::1","[::1]").contains(host.toLowerCase(java.util.Locale.ROOT));
            if (host==null || base.getUserInfo()!=null || base.getRawQuery()!=null || base.getRawFragment()!=null
                    || (base.getRawPath()!=null && !base.getRawPath().isEmpty() && !"/".equals(base.getRawPath()))
                    || base.getPort()==0 || base.getPort()>65535 || !("https".equalsIgnoreCase(base.getScheme()) || (local && "http".equalsIgnoreCase(base.getScheme())))) throw new IllegalArgumentException();
            byte[] tokenBytes; try (var input=Files.newInputStream(Path.of(tokenFile))) { tokenBytes=input.readNBytes(65537); }
            String token;
            try { if (tokenBytes.length==0 || tokenBytes.length>65536) throw new IllegalArgumentException(); token=new String(tokenBytes,StandardCharsets.UTF_8).trim(); }
            finally { java.util.Arrays.fill(tokenBytes,(byte)0); }
            if (token.isBlank() || token.chars().anyMatch(c -> c<33 || c>126)) throw new IllegalArgumentException();
            String[] parts=reference.split("#",2); int slash=parts[0].indexOf('/');
            String path="/v1/"+parts[0].substring(0,slash)+"/data/"+parts[0].substring(slash+1);
            URI target=new URI(base.getScheme(),null,host,base.getPort(),path,null,null);
            var request=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(10)).header("X-Vault-Token",token).GET().build();
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).proxy(java.net.ProxySelector.of(null)).build();
            var pending=client.sendAsync(request,ignored -> new BoundedBody());
            HttpResponse<byte[]> response;
            try { response=pending.get(10,java.util.concurrent.TimeUnit.SECONDS); }
            catch (java.util.concurrent.TimeoutException timeout) { pending.cancel(true); throw new IllegalStateException(); }
            byte[] payload=response.body();
            try {
                if (response.statusCode()!=200) throw new IllegalStateException();
                var value=mapper.readTree(payload).path("data").path("data").path(parts[1]);
                if (!value.isTextual() || value.asText().isBlank()) throw new IllegalStateException();
                return value.asText().getBytes(StandardCharsets.UTF_8);
            } finally { java.util.Arrays.fill(payload,(byte)0); }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Unable to resolve Vault secret"); }
        catch (Exception failure) { throw new IllegalStateException("Unable to resolve Vault secret"); }
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
        private final WipingBuffer bytes=new WipingBuffer();
        private java.util.concurrent.Flow.Subscription subscription;
        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription=value; value.request(1); }
        @Override public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining()>MAX_BYTES-bytes.size()) { subscription.cancel(); bytes.wipe(); result.completeExceptionally(new IllegalStateException("Response too large")); return; }
                byte[] chunk=new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk); java.util.Arrays.fill(chunk,(byte)0);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { bytes.wipe(); result.completeExceptionally(failure); }
        @Override public void onComplete() { byte[] value=bytes.toByteArray(); bytes.wipe(); result.complete(value); }
    }
    private static final class WipingBuffer extends java.io.ByteArrayOutputStream {
        void wipe() { java.util.Arrays.fill(buf,(byte)0); reset(); }
    }
}
