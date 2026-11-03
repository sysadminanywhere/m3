package com.sysadminanywhere.m3.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.apache.sshd.sftp.client.SftpClient;
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
    @Test void checksConnectionWithoutWriting() throws Exception {
        int port=Integer.parseInt(System.getenv("M3_TEST_SFTP_PORT")); String fingerprint=System.getenv("M3_TEST_SFTP_FINGERPRINT");
        var checker=new SftpConnectionCheckController(ref -> "testpass".getBytes(StandardCharsets.UTF_8));
        var valid=new SftpConnectionCheckController.Request("127.0.0.1",port,"m3test","ref",fingerprint,"PASSWORD","/upload");
        assertEquals("connected",checker.check(valid).get("status"));
        var wrong=new SftpConnectionCheckController.Request("127.0.0.1",port,"m3test","ref","SHA256:"+"A".repeat(43),"PASSWORD","/upload");
        assertEquals(org.springframework.http.HttpStatus.BAD_GATEWAY,assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> checker.check(wrong)).getStatusCode());
        var missing=new SftpConnectionCheckController.Request("127.0.0.1",port,"m3test","ref",fingerprint,"PASSWORD","/missing");
        assertEquals(org.springframework.http.HttpStatus.BAD_GATEWAY,assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> checker.check(missing)).getStatusCode());
    }
    @Test @EnabledIfEnvironmentVariable(named="M3_TEST_POSTGRES_URL",matches=".+")
    @EnabledIfEnvironmentVariable(named="M3_TEST_SFTP_KEY_FILE",matches=".+")
    void deliversWithOpenSshKeyAfterSecretRotation(@TempDir Path dir, DriverManagerDataSource ds) throws Exception {
        int port=Integer.parseInt(System.getenv("M3_TEST_SFTP_PORT")); String fingerprint=System.getenv("M3_TEST_SFTP_FINGERPRINT");
        byte[] validKey=Files.readAllBytes(Path.of(System.getenv("M3_TEST_SFTP_KEY_FILE")));
        Path wrongFile=dir.resolve("wrong-key");
        var generator=new ProcessBuilder("ssh-keygen","-q","-t","rsa","-b","2048","-N","","-f",wrongFile.toString()).redirectErrorStream(true).start();
        assertTrue(generator.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0,generator.exitValue());
        byte[] wrongKey=Files.readAllBytes(wrongFile);
        var current=new java.util.concurrent.atomic.AtomicReference<>(wrongKey);
        var secrets=(com.sysadminanywhere.m3.security.RuntimeSecretResolver) ref -> current.get().clone();
        var mapper=new ObjectMapper(); var jdbc=new JdbcTemplate(ds); Path original=Files.writeString(dir.resolve("original"),"key delivery payload");
        String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(original)));
        var config=mapper.createObjectNode(); var settings=config.putArray("recipients").addObject().put("id","out").put("type","sftp").putObject("settings");
        settings.put("host","127.0.0.1").put("port",port).put("username","m3test").put("credentialRef","ref").put("hostKeyFingerprint",fingerprint).put("path","/upload").put("authMethod","PRIVATE_KEY");
        config.putArray("rules").addObject().put("id","r").put("recipientId","out");
        jdbc.update("insert into community.config_bundle(version,document_json) values (1,cast(? as jsonb))",config.toString());
        var message=java.util.UUID.randomUUID(); var job=java.util.UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s',?,'key',?,'RECEIVED')",message,checksum,original.toString());
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'READY')",job,message);
        var scheduler=new DirectoryDeliveryScheduler(jdbc,mapper,new DataSourceTransactionManager(ds),"test","default",1,300,false,secrets);
        try {
            scheduler.dispatch();
            assertEquals("RETRY",jdbc.queryForObject("select status from community.execution_job where id=?",String.class,job));
            assertFalse(jdbc.queryForObject("select last_error from community.execution_job where id=?",String.class,job).contains("PRIVATE KEY"));
            current.set(validKey); jdbc.update("update community.execution_job set next_attempt_at=now() where id=?",job); scheduler.dispatch();
            assertEquals("DELIVERED",jdbc.queryForObject("select status from community.execution_job where id=?",String.class,job));
            try (var connection=SftpConnection.open("127.0.0.1",port,"m3test",fingerprint,"ref","PRIVATE_KEY",Duration.ofSeconds(10),secrets)) {
                String remote="/upload/"+job+"-key";
                try (var input=connection.client().read(remote)) { assertArrayEquals(Files.readAllBytes(original),input.readAllBytes()); }
                connection.client().remove(remote);
            }
        } finally { java.util.Arrays.fill(validKey,(byte)0); java.util.Arrays.fill(wrongKey,(byte)0); }
    }
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
    void refusesRemoteFileChangedDuringDownload(@TempDir Path dir, DriverManagerDataSource ds) throws Exception {
        int port=Integer.parseInt(System.getenv("M3_TEST_SFTP_PORT")); String fingerprint=System.getenv("M3_TEST_SFTP_FINGERPRINT");
        var secrets=(com.sysadminanywhere.m3.security.RuntimeSecretResolver) ref -> "testpass".getBytes(StandardCharsets.UTF_8);
        var jdbc=new JdbcTemplate(ds); var mapper=new ObjectMapper(); String name="changing-"+java.util.UUID.randomUUID();
        byte[] payload=new byte[8*1024*1024]; new java.util.Random(42).nextBytes(payload);
        try (var control=SftpConnection.open("127.0.0.1",port,"m3test",fingerprint,"ref",Duration.ofSeconds(10),secrets);
             var proxy=new Forwarder(0,port,3)) {
            try (var output=control.client().write("/upload/"+name)) { output.write(payload); }
            var config=mapper.createObjectNode(); var settings=config.putArray("connections").addObject().put("id","c").put("type","sftp").putObject("settings");
            settings.put("host","127.0.0.1").put("port",proxy.port()).put("username","m3test").put("credentialRef","ref").put("hostKeyFingerprint",fingerprint).put("path","/upload");
            config.putArray("sources").addObject().put("id","s").put("connectionId","c");
            jdbc.update("insert into community.config_bundle(version,document_json) values (1,cast(? as jsonb))",config.toString());
            Path storage=dir.resolve("storage"); var scheduler=new DirectoryIngestScheduler(jdbc,mapper,storage.toString(),0,"test",secrets);
            scheduler.poll();
            var changed=new java.util.concurrent.CompletableFuture<Void>();
            var watcher=new Thread(() -> {
                try {
                    long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos(); Path staging=storage.resolve(".m3-ingest-tmp");
                    while (System.nanoTime()<deadline) {
                        if (Files.isDirectory(staging)) try (var files=Files.list(staging)) {
                            if (files.anyMatch(path -> { try { return Files.size(path)>0; } catch (java.io.IOException ignored) { return false; } })) {
                                try (var output=control.client().write("/upload/"+name,SftpClient.OpenMode.Write,SftpClient.OpenMode.Append)) { output.write(1); }
                                changed.complete(null); return;
                            }
                        }
                        Thread.sleep(5);
                    }
                    changed.completeExceptionally(new AssertionError("Download did not begin"));
                } catch (Exception failure) { changed.completeExceptionally(failure); }
            });
            watcher.setDaemon(true); watcher.start(); scheduler.poll(); changed.get(35,java.util.concurrent.TimeUnit.SECONDS); watcher.join();
            assertEquals(0,jdbc.queryForObject("select count(*) from community.message_record where original_name=?",Integer.class,name));
            scheduler.poll(); scheduler.poll();
            var records=jdbc.queryForList("select storage_key from community.message_record where original_name=?",name);
            assertEquals(1,records.size()); byte[] accepted=Files.readAllBytes(Path.of(records.get(0).get("storage_key").toString()));
            assertEquals(payload.length+1,accepted.length); assertArrayEquals(payload,java.util.Arrays.copyOf(accepted,payload.length)); assertEquals(1,accepted[accepted.length-1]);
            control.client().remove("/upload/"+name);
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
    @Test @EnabledIfEnvironmentVariable(named="M3_TEST_POSTGRES_URL",matches=".+")
    void recordsUnknownWhenConnectionBreaksAfterRemoteRename(@TempDir Path dir,DriverManagerDataSource ds) throws Exception {
        int port=Integer.parseInt(System.getenv("M3_TEST_SFTP_PORT")); String fingerprint=System.getenv("M3_TEST_SFTP_FINGERPRINT");
        var secrets=(com.sysadminanywhere.m3.security.RuntimeSecretResolver) ref -> "testpass".getBytes(StandardCharsets.UTF_8);
        var jdbc=new JdbcTemplate(ds); var mapper=new ObjectMapper(); Path original=Files.writeString(dir.resolve("original"),"rename disconnect payload");
        String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(original)));
        try (var control=SftpConnection.open("127.0.0.1",port,"m3test",fingerprint,"ref",Duration.ofSeconds(10),secrets);
             var proxy=new Forwarder(0,port,100)) {
            var config=mapper.createObjectNode(); var settings=config.putArray("recipients").addObject().put("id","out").put("type","sftp").putObject("settings");
            settings.put("host","127.0.0.1").put("port",proxy.port()).put("username","m3test").put("credentialRef","ref").put("hostKeyFingerprint",fingerprint).put("path","/upload");
            config.putArray("rules").addObject().put("id","r").put("recipientId","out");
            jdbc.update("insert into community.config_bundle(version,document_json) values (1,cast(? as jsonb))",config.toString());
            var message=java.util.UUID.randomUUID(); var job=java.util.UUID.randomUUID(); String remote="/upload/"+job+"-disconnect";
            jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s',?,'disconnect',?,'RECEIVED')",message,checksum,original.toString());
            jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'READY')",job,message);
            var disconnected=new java.util.concurrent.CompletableFuture<Void>();
            var watcher=new Thread(() -> {
                try {
                    long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
                    while (System.nanoTime()<deadline) {
                        try { control.client().lstat(remote); proxy.disconnect(); disconnected.complete(null); return; }
                        catch (java.io.IOException absent) { Thread.sleep(5); }
                    }
                    disconnected.completeExceptionally(new AssertionError("Remote target never appeared"));
                } catch (Exception failure) { disconnected.completeExceptionally(failure); }
            });
            watcher.setDaemon(true); watcher.start();
            new DirectoryDeliveryScheduler(jdbc,mapper,new DataSourceTransactionManager(ds),"test","default",1,300,false,secrets).dispatch();
            disconnected.get(35,java.util.concurrent.TimeUnit.SECONDS); watcher.join();
            assertEquals("UNCERTAIN",jdbc.queryForObject("select status from community.execution_job where id=?",String.class,job));
            try (var input=control.client().read(remote)) { assertArrayEquals(Files.readAllBytes(original),input.readAllBytes()); }
            control.client().remove(remote);
        }
    }
    private static final class Forwarder implements AutoCloseable {
        private final java.net.ServerSocket server; private final java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newCachedThreadPool();
        private final java.util.Set<java.net.Socket> connections=java.util.concurrent.ConcurrentHashMap.newKeySet();
        Forwarder(int port,int destination) throws Exception {
            this(port,destination,0);
        }
        Forwarder(int port,int destination,int delayMs) throws Exception {
            server=new java.net.ServerSocket(port,10,java.net.InetAddress.getByName("127.0.0.1"));
            workers.execute(() -> { while (!server.isClosed()) try {
                var incoming=server.accept(); var outgoing=new java.net.Socket("127.0.0.1",destination);
                connections.add(incoming); connections.add(outgoing);
                workers.execute(() -> copy(incoming,outgoing,delayMs)); workers.execute(() -> copy(outgoing,incoming,delayMs));
            } catch (java.io.IOException ignored) { if (!server.isClosed()) break; } });
        }
        int port() { return server.getLocalPort(); }
        private static void copy(java.net.Socket from,java.net.Socket to,int delayMs) {
            try (from;to) {
                byte[] buffer=new byte[16384]; int count;
                while ((count=from.getInputStream().read(buffer))!=-1) {
                    if (delayMs>0) Thread.sleep(delayMs);
                    to.getOutputStream().write(buffer,0,count);
                }
            } catch (java.io.IOException ignored) { } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        void disconnect() { for (var socket : connections) try { socket.close(); } catch (java.io.IOException ignored) { } }
        public void close() throws Exception { server.close(); disconnect(); workers.shutdownNow(); workers.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS); }
    }
}
