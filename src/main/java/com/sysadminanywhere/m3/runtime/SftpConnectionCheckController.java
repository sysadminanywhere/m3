package com.sysadminanywhere.m3.runtime;

import com.sysadminanywhere.m3.security.RuntimeSecretResolver;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Checks authentication and directory visibility without writing to the remote server. */
@RestController
@RequestMapping("/api/v2/sftp")
public class SftpConnectionCheckController {
    private final RuntimeSecretResolver secrets;
    public SftpConnectionCheckController(@Lazy RuntimeSecretResolver secrets) { this.secrets=secrets; }
    public record Request(String host, Integer port, String username, String credentialRef, String hostKeyFingerprint, String authMethod, String path, String credentialProvider) {
        public Request(String host,Integer port,String username,String credentialRef,String hostKeyFingerprint,String authMethod,String path) { this(host,port,username,credentialRef,hostKeyFingerprint,authMethod,path,null); }
    }
    @PostMapping("/check") public Map<String,String> check(@RequestBody Request request) {
        if (request==null || request.credentialRef()==null || request.credentialRef().isBlank() || request.path()==null
                || !request.path().startsWith("/") || request.path().contains("\\") || request.path().contains("\u0000")
                || Arrays.asList(request.path().split("/",-1)).contains(".."))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_sftp_settings");
        try (var connection=SftpConnection.open(request.host(),request.port()==null?22:request.port(),request.username(),request.hostKeyFingerprint(),
                request.credentialRef(),request.authMethod()==null?"PASSWORD":request.authMethod(),request.credentialProvider()==null?"COMMUNITY":request.credentialProvider(),Duration.ofSeconds(5),secrets)) {
            if (!connection.client().stat(request.path()).isDirectory()) throw new java.io.IOException();
            return Map.of("status","connected");
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"sftp_check_failed");
        }
    }
}
