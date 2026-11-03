package com.sysadminanywhere.m3.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sysadminanywhere.m3.test.DisposablePostgres;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
class HttpDeliveryPostgresTest {
    @Test void tracksSuccessUnknownOutcomeAndSecretRotation(@TempDir Path dir,DriverManagerDataSource ds) throws Exception {
        var received=new AtomicReference<byte[]>(); var auth=new AtomicReference<String>(); var idempotency=new AtomicReference<String>(); var disconnectCalls=new java.util.concurrent.atomic.AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/receive",exchange -> {
            received.set(exchange.getRequestBody().readAllBytes()); auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            if ("Bearer valid".equals(auth.get())) exchange.sendResponseHeaders(204,-1); else exchange.sendResponseHeaders(500,-1);
            exchange.close();
        });
        server.createContext("/disconnect",exchange -> { disconnectCalls.incrementAndGet(); received.set(exchange.getRequestBody().readAllBytes()); exchange.close(); });
        server.start();
        try {
            var jdbc=new JdbcTemplate(ds); var mapper=new ObjectMapper(); Path original=Files.write(dir.resolve("original"),new byte[]{0,1,2,(byte)255});
            String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(original)));
            var config=mapper.createObjectNode(); config.putArray("recipients").addObject().put("id","out").put("type","http").putObject("settings")
                    .put("url","http://127.0.0.1:"+server.getAddress().getPort()+"/receive").put("tokenRef","token");
            config.putArray("rules").addObject().put("id","r").put("recipientId","out");
            jdbc.update("insert into community.config_bundle(version,document_json) values (1,cast(? as jsonb))",config.toString());
            UUID message=UUID.randomUUID(), job=UUID.randomUUID();
            jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s',?,'payload',?,'RECEIVED')",message,checksum,original.toString());
            jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'READY')",job,message);
            var token=new AtomicReference<>("invalid");
            var scheduler=new DirectoryDeliveryScheduler(jdbc,mapper,new DataSourceTransactionManager(ds),"http-test","default",1,300,false,ref -> token.get().getBytes(StandardCharsets.UTF_8));
            scheduler.dispatch();
            assertEquals("UNCERTAIN",jdbc.queryForObject("select status from community.execution_job where id=?",String.class,job));
            assertArrayEquals(Files.readAllBytes(original),received.get()); assertEquals(job.toString(),idempotency.get());
            assertFalse(jdbc.queryForObject("select last_error from community.execution_job where id=?",String.class,job).contains("invalid"));
            token.set("valid"); scheduler.dispatch();
            assertEquals("UNCERTAIN",jdbc.queryForObject("select status from community.execution_job where id=?",String.class,job));
            assertEquals(202,new com.sysadminanywhere.m3.api.OperationsController(jdbc).retry(job).getStatusCode().value());
            scheduler.dispatch();
            assertEquals("DELIVERED",jdbc.queryForObject("select status from community.execution_job where id=?",String.class,job));
            assertEquals("Bearer valid",auth.get()); assertEquals(job.toString(),idempotency.get()); assertArrayEquals(Files.readAllBytes(original),received.get());
            assertEquals(java.util.List.of("READY","RUNNING","UNCERTAIN","READY","RUNNING","DELIVERED"),jdbc.queryForList("select status from community.job_event where job_id=? order by id",String.class,job));
            ((com.fasterxml.jackson.databind.node.ObjectNode) config.path("recipients").get(0).path("settings")).put("url","http://127.0.0.1:"+server.getAddress().getPort()+"/disconnect");
            jdbc.update("insert into community.config_bundle(version,document_json) values (2,cast(? as jsonb))",config.toString());
            UUID lost=UUID.randomUUID(); jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',2,'READY')",lost,message);
            scheduler.dispatch(); assertEquals("UNCERTAIN",jdbc.queryForObject("select status from community.execution_job where id=?",String.class,lost));
            assertArrayEquals(Files.readAllBytes(original),received.get()); scheduler.dispatch(); assertEquals(1,disconnectCalls.get());
        } finally { server.stop(0); }
    }
}
