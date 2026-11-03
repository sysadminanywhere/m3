package com.sysadminanywhere.m3.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import com.sysadminanywhere.m3.test.DisposablePostgres;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named="M3_TEST_SFTP_PORT",matches="[0-9]+")
@ExtendWith(DisposablePostgres.class)
class SftpOpenSshIntegrationTest {
    @Test void atomicPublicationAndLeaseFence(@TempDir Path dir) throws Exception {
        int port=Integer.parseInt(System.getenv("M3_TEST_SFTP_PORT")); String fingerprint=System.getenv("M3_TEST_SFTP_FINGERPRINT");
        Path original=Files.writeString(dir.resolve("original"),"actual payload");
        String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(original)));
        try (var connection=SftpConnection.open("127.0.0.1",port,"m3test",fingerprint,"ref",Duration.ofSeconds(10),ref -> "testpass".getBytes(StandardCharsets.UTF_8))) {
            String name="delivery-"+java.util.UUID.randomUUID();
            SftpPublisher.publish(connection.client(),original,"/upload",name,checksum,() -> {});
            try (var input=connection.client().read("/upload/"+name)) { assertArrayEquals(Files.readAllBytes(original),input.readAllBytes()); }
            assertThrows(java.io.IOException.class,() -> SftpPublisher.publish(connection.client(),original,"/upload",name,"0".repeat(64),() -> fail("checksum must be checked before publication")));
            assertThrows(java.io.IOException.class,() -> SftpPublisher.publish(connection.client(),original,"/upload",name,checksum,() -> { throw new java.io.IOException("lease lost"); }));
            try (var input=connection.client().read("/upload/"+name)) { assertArrayEquals(Files.readAllBytes(original),input.readAllBytes()); }
            assertThrows(java.io.IOException.class,() -> SftpPublisher.publish(connection.client(),original,"/upload","../escape",checksum,() -> {}));
            connection.client().remove("/upload/"+name);
        }
    }

    @Test @EnabledIfEnvironmentVariable(named="M3_TEST_POSTGRES_URL",matches=".+")
    void queueRecordsDeliveryAndAmbiguousCommit(@TempDir Path dir, DriverManagerDataSource ds) throws Exception {
        int port=Integer.parseInt(System.getenv("M3_TEST_SFTP_PORT")); String fingerprint=System.getenv("M3_TEST_SFTP_FINGERPRINT");
        var mapper=new ObjectMapper(); var jdbc=new JdbcTemplate(ds); Path original=Files.writeString(dir.resolve("original"),"queued payload");
        String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(original)));
        var config=mapper.createObjectNode(); var settings=config.putArray("recipients").addObject().put("id","out").put("type","sftp").putObject("settings");
        settings.put("host","127.0.0.1").put("port",port).put("username","m3test").put("credentialRef","ref").put("hostKeyFingerprint",fingerprint).put("path","/upload");
        config.putArray("rules").addObject().put("id","r").put("recipientId","out");
        jdbc.update("insert into community.config_bundle(version,document_json) values (1,cast(? as jsonb))",config.toString());
        var message=java.util.UUID.randomUUID(); var job=java.util.UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s',?,'queued',?,'RECEIVED')",message,checksum,original.toString());
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'READY')",job,message);
        var secrets=(com.sysadminanywhere.m3.security.RuntimeSecretResolver) ref -> "testpass".getBytes(StandardCharsets.UTF_8);
        new DirectoryDeliveryScheduler(jdbc,mapper,new DataSourceTransactionManager(ds),"test","default",1,300,false,secrets).dispatch();
        assertEquals("DELIVERED",jdbc.queryForObject("select status from community.execution_job where id=?",String.class,job));
        var uncertain=java.util.UUID.randomUUID(); jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'READY')",uncertain,message);
        var flaky=new JdbcTemplate(ds) {
            @Override public int update(String sql,Object... args) {
                if (sql.startsWith("update community.execution_job set status='DELIVERED'")) throw new org.springframework.dao.DataAccessResourceFailureException("simulated acknowledgement loss");
                return super.update(sql,args);
            }
        };
        new DirectoryDeliveryScheduler(flaky,mapper,new DataSourceTransactionManager(ds),"test","default",1,300,false,secrets).dispatch();
        assertEquals("UNCERTAIN",jdbc.queryForObject("select status from community.execution_job where id=?",String.class,uncertain));
        try (var connection=SftpConnection.open("127.0.0.1",port,"m3test",fingerprint,"ref",Duration.ofSeconds(10),secrets)) {
            for (var id : java.util.List.of(job,uncertain)) {
                String path="/upload/"+id+"-queued";
                try (var input=connection.client().read(path)) { assertArrayEquals(Files.readAllBytes(original),input.readAllBytes()); }
                connection.client().remove(path);
            }
        }
    }

    @Test @EnabledIfEnvironmentVariable(named="M3_TEST_POSTGRES_URL",matches=".+")
    void receivesOnceAndPreservesOriginal(@TempDir Path dir, DriverManagerDataSource ds) throws Exception {
        int port=Integer.parseInt(System.getenv("M3_TEST_SFTP_PORT")); String fingerprint=System.getenv("M3_TEST_SFTP_FINGERPRINT");
        var secrets=(com.sysadminanywhere.m3.security.RuntimeSecretResolver) ref -> "testpass".getBytes(StandardCharsets.UTF_8);
        var mapper=new ObjectMapper(); var jdbc=new JdbcTemplate(ds); String name="in-"+java.util.UUID.randomUUID();
        try (var connection=SftpConnection.open("127.0.0.1",port,"m3test",fingerprint,"ref",Duration.ofSeconds(10),secrets)) {
            try (var output=connection.client().write("/upload/"+name)) { output.write("incoming content".getBytes(StandardCharsets.UTF_8)); }
            String temporary=".m3-"+java.util.UUID.randomUUID()+".part";
            try (var output=connection.client().write("/upload/"+temporary)) { output.write("incomplete".getBytes(StandardCharsets.UTF_8)); }
            var config=mapper.createObjectNode(); var settings=config.putArray("connections").addObject().put("id","c").put("type","sftp").putObject("settings");
            settings.put("host","127.0.0.1").put("port",port).put("username","m3test").put("credentialRef","ref").put("hostKeyFingerprint",fingerprint).put("path","/upload");
            config.putArray("sources").addObject().put("id","s").put("connectionId","c");
            config.putArray("rules").addObject().put("id","r").put("recipientId","out").putArray("sourceIds").add("s");
            jdbc.update("insert into community.config_bundle(version,document_json) values (1,cast(? as jsonb))",config.toString());
            var scheduler=new DirectoryIngestScheduler(jdbc,mapper,dir.resolve("storage").toString(),0,"test",secrets);
            scheduler.poll(); scheduler.poll(); scheduler.poll();
            var records=jdbc.queryForList("select original_name,checksum,storage_key from community.message_record where original_name=?",name);
            assertEquals(1,records.size());
            assertEquals(0,jdbc.queryForObject("select count(*) from community.message_record where original_name=?",Integer.class,temporary));
            assertEquals("incoming content",Files.readString(Path.of(records.get(0).get("storage_key").toString())));
            assertEquals(1,jdbc.queryForObject("select count(*) from community.execution_job",Integer.class));
            try (var input=connection.client().read("/upload/"+name)) { assertEquals("incoming content",new String(input.readAllBytes(),StandardCharsets.UTF_8)); }
            connection.client().remove("/upload/"+name);
            connection.client().remove("/upload/"+temporary);
        }
    }
    @Test @EnabledIfEnvironmentVariable(named="M3_TEST_POSTGRES_URL",matches=".+")
    void retriesAfterCredentialRotationWithoutLeakingPassword(@TempDir Path dir,DriverManagerDataSource ds) throws Exception {
        int port=Integer.parseInt(System.getenv("M3_TEST_SFTP_PORT")); String fingerprint=System.getenv("M3_TEST_SFTP_FINGERPRINT");
        var jdbc=new JdbcTemplate(ds); var mapper=new ObjectMapper(); Path original=Files.writeString(dir.resolve("file"),"retry payload");
        String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(original)));
        var config=mapper.createObjectNode(); var settings=config.putArray("recipients").addObject().put("id","out").put("type","sftp").putObject("settings");
        settings.put("host","127.0.0.1").put("port",port).put("username","m3test").put("credentialRef","ref").put("hostKeyFingerprint",fingerprint).put("path","/upload");
        config.putArray("rules").addObject().put("id","r").put("recipientId","out");
        jdbc.update("insert into community.config_bundle(version,document_json) values (1,cast(? as jsonb))",config.toString());
        var message=java.util.UUID.randomUUID(); var job=java.util.UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s',?,'retry',?,'RECEIVED')",message,checksum,original.toString());
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'READY')",job,message);
        var password=new java.util.concurrent.atomic.AtomicReference<>("invalid-secret");
        var secrets=(com.sysadminanywhere.m3.security.RuntimeSecretResolver) ref -> password.get().getBytes(StandardCharsets.UTF_8);
        var scheduler=new DirectoryDeliveryScheduler(jdbc,mapper,new DataSourceTransactionManager(ds),"test","default",1,300,false,secrets);
        scheduler.dispatch();
        assertEquals("RETRY",jdbc.queryForObject("select status from community.execution_job where id=?",String.class,job));
        assertFalse(jdbc.queryForObject("select last_error from community.execution_job where id=?",String.class,job).contains("invalid-secret"));
        password.set("testpass"); jdbc.update("update community.execution_job set next_attempt_at=now() where id=?",job); scheduler.dispatch();
        assertEquals("DELIVERED",jdbc.queryForObject("select status from community.execution_job where id=?",String.class,job));
        try (var connection=SftpConnection.open("127.0.0.1",port,"m3test",fingerprint,"ref",Duration.ofSeconds(10),secrets)) {
            String remote="/upload/"+job+"-retry"; try (var input=connection.client().read(remote)) { assertArrayEquals(Files.readAllBytes(original),input.readAllBytes()); }
            connection.client().remove(remote);
        }
    }
    @Test @EnabledIfEnvironmentVariable(named="M3_TEST_POSTGRES_URL",matches=".+")
    void retriesAfterNetworkEndpointReturns(@TempDir Path dir,DriverManagerDataSource ds) throws Exception {
        int serverPort=Integer.parseInt(System.getenv("M3_TEST_SFTP_PORT")); String fingerprint=System.getenv("M3_TEST_SFTP_FINGERPRINT");
        int proxyPort; try (var socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))) { proxyPort=socket.getLocalPort(); }
        var jdbc=new JdbcTemplate(ds); var mapper=new ObjectMapper(); Path original=Files.writeString(dir.resolve("file"),"network recovery");
        String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(original)));
        var config=mapper.createObjectNode(); var settings=config.putArray("recipients").addObject().put("id","out").put("type","sftp").putObject("settings");
        settings.put("host","127.0.0.1").put("port",proxyPort).put("username","m3test").put("credentialRef","ref").put("hostKeyFingerprint",fingerprint).put("path","/upload");
        config.putArray("rules").addObject().put("id","r").put("recipientId","out");
        jdbc.update("insert into community.config_bundle(version,document_json) values (1,cast(? as jsonb))",config.toString());
        var message=java.util.UUID.randomUUID(); var job=java.util.UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s',?,'network',?,'RECEIVED')",message,checksum,original.toString());
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'READY')",job,message);
        var secrets=(com.sysadminanywhere.m3.security.RuntimeSecretResolver) ref -> "testpass".getBytes(StandardCharsets.UTF_8);
        var scheduler=new DirectoryDeliveryScheduler(jdbc,mapper,new DataSourceTransactionManager(ds),"test","default",1,300,false,secrets);
        scheduler.dispatch(); assertEquals("RETRY",jdbc.queryForObject("select status from community.execution_job where id=?",String.class,job));
        try (var proxy=new Forwarder(proxyPort,serverPort)) {
            jdbc.update("update community.execution_job set next_attempt_at=now() where id=?",job); scheduler.dispatch();
            assertEquals("DELIVERED",jdbc.queryForObject("select status from community.execution_job where id=?",String.class,job));
        }
        try (var connection=SftpConnection.open("127.0.0.1",serverPort,"m3test",fingerprint,"ref",Duration.ofSeconds(10),secrets)) {
            String remote="/upload/"+job+"-network"; try (var input=connection.client().read(remote)) { assertArrayEquals(Files.readAllBytes(original),input.readAllBytes()); }
            connection.client().remove(remote);
        }
    }
    private static final class Forwarder implements AutoCloseable {
        private final java.net.ServerSocket server; private final java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newCachedThreadPool();
        Forwarder(int port,int destination) throws Exception {
            server=new java.net.ServerSocket(port,10,java.net.InetAddress.getByName("127.0.0.1"));
            workers.execute(() -> { while (!server.isClosed()) try {
                var incoming=server.accept(); var outgoing=new java.net.Socket("127.0.0.1",destination);
                workers.execute(() -> copy(incoming,outgoing)); workers.execute(() -> copy(outgoing,incoming));
            } catch (java.io.IOException ignored) { if (!server.isClosed()) break; } });
        }
        private static void copy(java.net.Socket from,java.net.Socket to) {
            try (from;to) { from.getInputStream().transferTo(to.getOutputStream()); } catch (java.io.IOException ignored) { }
        }
        public void close() throws Exception { server.close(); workers.shutdownNow(); workers.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS); }
    }
}
