package com.sysadminanywhere.m3.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.security.SecretService;
import com.sysadminanywhere.m3.security.VaultKv2Resolver;
import com.sysadminanywhere.m3.test.DisposablePostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(DisposablePostgres.class)
@EnabledIfEnvironmentVariable(named="M3_TEST_POSTGRES_URL",matches=".+")
@EnabledIfEnvironmentVariable(named="M3_TEST_SFTP_PORT",matches="[0-9]+")
@EnabledIfEnvironmentVariable(named="M3_TEST_VAULT_ADDR",matches=".+")
@EnabledIfEnvironmentVariable(named="M3_TEST_SFTP_KEY_FILE",matches=".+")
class VaultSftpIntegrationTest {
    @Test void resolvesRotatedVaultKeyForActualSftpJob(@TempDir Path dir,DriverManagerDataSource ds) throws Exception {
        var mapper=new ObjectMapper(); var jdbc=new JdbcTemplate(ds);
        String address=System.getenv("M3_TEST_VAULT_ADDR"); String token=Files.readString(Path.of(System.getenv("M3_TEST_VAULT_TOKEN_FILE"))).trim();
        Path tokenFile=Files.writeString(dir.resolve("vault-token"),token);
        String path="m3test/"+UUID.randomUUID().toString(), reference="secret/"+path+"#private_key";
        var vault=new VaultKv2Resolver(address,tokenFile.toString(),mapper);
        var secrets=new SecretService(jdbc,Base64.getEncoder().encodeToString(new byte[32]),"",vault);
        String fingerprint=System.getenv("M3_TEST_SFTP_FINGERPRINT"); int port=Integer.parseInt(System.getenv("M3_TEST_SFTP_PORT"));
        var config=mapper.createObjectNode(); var settings=config.putArray("recipients").addObject().put("id","out").put("type","sftp").putObject("settings");
        settings.put("host","127.0.0.1").put("port",port).put("username","m3test").put("credentialRef",reference)
                .put("hostKeyFingerprint",fingerprint).put("path","/upload").put("authMethod","PRIVATE_KEY").put("credentialProvider","VAULT");
        config.putArray("rules").addObject().put("id","r").put("recipientId","out");
        jdbc.update("insert into community.config_bundle(version,document_json) values (1,cast(? as jsonb))",config.toString());
        Path original=Files.writeString(dir.resolve("original"),"vault-key-delivery");
        String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(original)));
        UUID message=UUID.randomUUID(),job=UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s',?,'vault',?,'RECEIVED')",message,checksum,original.toString());
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'READY')",job,message);
        var scheduler=new DirectoryDeliveryScheduler(jdbc,mapper,new DataSourceTransactionManager(ds),"vault-test","default",1,300,false,secrets);
        put(address,token,path,"invalid-key",mapper); scheduler.dispatch();
        assertEquals("RETRY",jdbc.queryForObject("select status from community.execution_job where id=?",String.class,job));
        assertFalse(jdbc.queryForObject("select last_error from community.execution_job where id=?",String.class,job).contains("invalid-key"));
        put(address,token,path,Files.readString(Path.of(System.getenv("M3_TEST_SFTP_KEY_FILE"))),mapper);
        jdbc.update("update community.execution_job set next_attempt_at=now() where id=?",job); scheduler.dispatch();
        assertEquals("DELIVERED",jdbc.queryForObject("select status from community.execution_job where id=?",String.class,job));
        var check=new SftpConnectionCheckController(secrets);
        assertEquals("connected",check.check(new SftpConnectionCheckController.Request("127.0.0.1",port,"m3test",reference,fingerprint,"PRIVATE_KEY","/upload","VAULT")).get("status"));
        try (var connection=SftpConnection.open("127.0.0.1",port,"m3test",fingerprint,reference,"PRIVATE_KEY","VAULT",Duration.ofSeconds(10),secrets)) {
            String remote="/upload/"+job+"-vault";
            try (var input=connection.client().read(remote)) { assertArrayEquals(Files.readAllBytes(original),input.readAllBytes()); }
            connection.client().remove(remote);
        }
        assertFalse(jdbc.queryForObject("select document_json::text from community.config_bundle where version=1",String.class).contains("PRIVATE KEY-----"));
        String policy="m3-"+UUID.randomUUID();
        var policyBody=Map.of("policy","path \"secret/data/"+path+"\" { capabilities = [\"read\"] }");
        post(address,token,"/v1/sys/policies/acl/"+policy,policyBody,mapper,204);
        var issued=post(address,token,"/v1/auth/token/create",Map.of("policies",java.util.List.of(policy),"no_default_policy",true),mapper,200);
        String limited=issued.path("auth").path("client_token").asText(); assertFalse(limited.isBlank());
        Files.writeString(tokenFile,limited);
        assertTrue(vault.resolve(reference).length>0);
        var denied=assertThrows(IllegalStateException.class,() -> vault.resolve("secret/other/path#private_key"));
        assertFalse(denied.toString().contains(limited)); assertNull(denied.getCause());
        Files.writeString(tokenFile,"revoked-or-invalid-token");
        var invalid=assertThrows(IllegalStateException.class,() -> vault.resolve(reference));
        assertFalse(invalid.toString().contains("revoked-or-invalid-token")); assertNull(invalid.getCause());
    }
    private static void put(String address,String token,String path,String key,ObjectMapper mapper) throws Exception {
        post(address,token,"/v1/secret/data/"+path,Map.of("data",Map.of("private_key",key)),mapper,200);
    }
    private static com.fasterxml.jackson.databind.JsonNode post(String address,String token,String path,Object body,ObjectMapper mapper,int status) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(address+path)).header("X-Vault-Token",token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()); assertEquals(status,response.statusCode());
        return response.body().isBlank()?mapper.createObjectNode():mapper.readTree(response.body());
    }
}
