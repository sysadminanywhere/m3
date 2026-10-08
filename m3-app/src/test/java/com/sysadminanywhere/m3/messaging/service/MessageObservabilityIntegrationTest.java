package com.sysadminanywhere.m3.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.outbound.PreparedOutboundDelivery;
import com.sysadminanywhere.m3.messaging.repository.*;
import com.sysadminanywhere.m3.messaging.worker.WorkerSlotService;
import io.minio.*;
import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL + S3 protocol: durable archive, search privacy, history and concurrent admission. */
@Testcontainers(disabledWithoutDocker=true)
class MessageObservabilityIntegrationTest {
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    @Container static final GenericContainer<?> S3=new GenericContainer<>(System.getProperty("m3.test.s3-image","minio/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e"))
            .withEnv("MINIO_ROOT_USER","test-user").withEnv("MINIO_ROOT_PASSWORD","test-password-123")
            .withCommand("server","/data").withExposedPorts(9000).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    static JdbcTemplate jdbc;static DataSourceTransactionManager manager;
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final PayloadMasking MASKING=new PayloadMasking(JSON,"phone,password,token","","");
    static ColdPayloadStore store;
    @BeforeAll static void initialize() throws Exception {
        try(var connection=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
            var liquibase=new Liquibase("db/changelog/db.changelog-master.xml",new ClassLoaderResourceAccessor(),DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.update(new Contexts(),new LabelExpression());
        }
        var datasource=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());jdbc=new JdbcTemplate(datasource);manager=new DataSourceTransactionManager(datasource);
        String endpoint="http://"+S3.getHost()+":"+S3.getMappedPort(9000);
        MinioClient.builder().endpoint(endpoint).credentials("test-user","test-password-123").build().makeBucket(MakeBucketArgs.builder().bucket("m3-tests").build());
        store=new S3ColdPayloadStore(endpoint,"test-user","test-password-123","m3-tests","us-east-1");
    }
    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE message,channel_settings,rule_worker_pool,worker_slot,worker_drain,cold_object_cleanup RESTART IDENTITY CASCADE");
        jdbc.update("UPDATE storage_usage SET hot_bytes=0,max_hot_bytes=0,max_database_bytes=0,admission_blocked=false");
        jdbc.update("UPDATE m3_installation SET license_document=NULL");
        jdbc.update("INSERT INTO rule_worker_pool(name,desired_replicas,min_replicas,max_replicas,created_at) VALUES('default',1,1,1,now())");
        jdbc.update("INSERT INTO channel_settings(name,channel_type,direction,created_at) VALUES('test','DIRECTORY','INBOUND',now())");
        jdbc.update("INSERT INTO rule(name,rule_type,source_channel_id,worker_pool_id,created_at) VALUES('test','INBOUND',1,1,now())");
    }
    private long message(String state,String payload) {
        return jdbc.queryForObject("INSERT INTO message(direction,status,payload_bytes,charset,charset_source,payload_format,payload_type,created_at,processed_at,stored_size) VALUES('INBOUND',?,?,'UTF-8','TEXT_UTF8','TEXT','application/json',now()-interval '9 days',now()-interval '8 days',?) RETURNING message_id",Long.class,state,payload.getBytes(StandardCharsets.UTF_8),payload.getBytes(StandardCharsets.UTF_8).length);
    }
    private long job(long message,String state) {
        return jdbc.queryForObject("INSERT INTO rule_execution_job(message_id,rule_id,worker_pool_id,status,created_at,configuration_hash) VALUES(?,1,1,?,now(),?) RETURNING job_id",Long.class,message,state,"a".repeat(64));
    }
    private MessageArchiveService archive(ColdPayloadStore selected) { return new MessageArchiveService(jdbc,selected,JSON,MASKING,manager,"ARCHIVE",7); }
    private Message load(long id) {
        var message=new Message(MessageDirection.INBOUND,"","application/json");ReflectionTestUtils.setField(message,"id",id);
        jdbc.query("SELECT payload_bytes,archive_key,archive_sha256,stored_size FROM message WHERE message_id=?",rs -> {
            ReflectionTestUtils.setField(message,"payloadBytes",rs.getBytes(1));ReflectionTestUtils.setField(message,"archiveKey",rs.getString(2));
            ReflectionTestUtils.setField(message,"archiveSha256",rs.getString(3));ReflectionTestUtils.setField(message,"storedSize",rs.getInt(4));
        },id);return message;
    }
    @Test void recordsPreviousAttemptsEvenAfterRetryResetsCounter() {
        long id=message("PENDING","{}");long job=job(id,"PENDING");
        jdbc.update("UPDATE rule_execution_job SET status='PROCESSING',attempts=1,worker_id='first-worker' WHERE job_id=?",job);
        jdbc.update("UPDATE rule_execution_job SET status='FAILED',error_message='password=secret' WHERE job_id=?",job);
        jdbc.update("UPDATE rule_execution_job SET status='PENDING',attempts=0,error_message='Retry requested' WHERE job_id=?",job);
        var events=new MessageHistoryService(jdbc,JSON,MASKING).events(id);
        assertThat(events).extracting(MessageHistoryService.Event::kind).containsSubsequence("RECEIVED","QUEUED","ATTEMPT_STARTED","ATTEMPT_FAILED","REQUEUED");
        assertThat(events.stream().filter(e -> e.kind().equals("ATTEMPT_FAILED")).findFirst().orElseThrow().attempt()).isEqualTo(1);
        assertThat(events).extracting(MessageHistoryService.Event::detail).doesNotContain("password=secret");
    }
    @Test void indexesMaskedContentAndLiteralSearchCharactersOnly() {
        long id=message("PROCESSED","{\"password\":\"secret-p\",\"note\":\"literal %_ marker\"}");
        jdbc.update("INSERT INTO message_metadata(message_id,key,value) VALUES(?,'correlationId','trace-42')",id);
        new MessageIndexService(jdbc,MASKING,manager).index();
        assertThat(jdbc.queryForObject("SELECT search_text FROM message WHERE message_id=?",String.class,id)).doesNotContain("secret-p").contains("literal %_ marker");
        assertThat(jdbc.queryForList("SELECT message_id FROM message WHERE lower(search_text) LIKE ? ESCAPE '\\'",Long.class,MessageSearch.pattern("%_"))).containsExactly(id);
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM message WHERE message_id=?",String.class,id)).isEqualTo("trace-42");
    }
    @Test void archivesOriginalAndActualDeliveryBytesAndRestoresWithoutChangingCatalogue() {
        byte[] sent="actual transformed body".getBytes(StandardCharsets.UTF_8);
        long id=message("PROCESSED","{\"password\":\"secret-original\"}");long job=job(id,"COMPLETED");
        new MessageHistoryService(jdbc,JSON,MASKING).prepared(id,job,new PreparedOutboundDelivery(1,sent,"text/plain",Map.of("charset","UTF-8","token","secret-token")));
        new MessageIndexService(jdbc,MASKING,manager).index();var archive=archive(store);archive.enqueue();assertThat(archive.archiveNext()).isTrue();
        assertThat(jdbc.queryForObject("SELECT payload_bytes IS NULL FROM message WHERE message_id=?",Boolean.class,id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT payload_bytes IS NULL FROM message_delivery_snapshot WHERE job_id=?",Boolean.class,job)).isTrue();
        var message=load(id);assertThat(message.isArchived()).isTrue();
        var envelope=archive.read(message);assertThat(envelope.payload()).isEqualTo("{\"password\":\"secret-original\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(envelope.deliveries().getFirst().bytes()).isEqualTo(sent);assertThat(envelope.deliveries().getFirst().metadata()).contains("secret-token");
        archive.hydrate(message);assertThat(message.getPayloadBytes()).isEqualTo(envelope.payload());assertThat(message.isArchived()).isTrue();
        var repository=mock(MessageRepository.class);when(repository.findById(id)).thenReturn(Optional.of(message));
        var inspector=new MessageInspectionService(repository,archive,jdbc,JSON,MASKING,mock(OriginalPayloadAccess.class));
        assertThat(inspector.view(id,null,false,null).text()).doesNotContain("secret-original");
        assertThat(inspector.view(id,job,false,null).metadata().get("token")).isEqualTo("[REDACTED]");
    }
    @Test void checksumFailurePreservesHotBytesAndSchedulesRetry() {
        long id=message("PROCESSED","{}");new MessageIndexService(jdbc,MASKING,manager).index();
        var corrupted=mock(ColdPayloadStore.class);when(corrupted.configured()).thenReturn(true);when(corrupted.get(anyString())).thenReturn(new byte[]{1});
        var archive=archive(corrupted);archive.enqueue();archive.archiveNext();
        assertThat(jdbc.queryForObject("SELECT payload_bytes IS NOT NULL FROM message WHERE message_id=?",Boolean.class,id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempts FROM message_archive_job WHERE message_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT claimed_at IS NULL AND next_attempt_at>now() FROM message_archive_job WHERE message_id=?",Boolean.class,id)).isTrue();
    }
    @Test void pendingFailedAndUnpublishedMessagesStayHot() {
        long pending=message("PENDING","{}");long failed=message("FAILED","{}");long awaiting=message("PROCESSED","{}");
        jdbc.update("INSERT INTO message_receipt_outbox(event_id,message_id) VALUES(gen_random_uuid(),?)",awaiting);
        new MessageIndexService(jdbc,MASKING,manager).index();archive(store).enqueue();
        assertThat(jdbc.queryForList("SELECT message_id FROM message_archive_job",Long.class)).isEmpty();
    }
    private WorkerSlotService transactional(WorkerSlotService target) {
        var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return (WorkerSlotService)proxy.getProxy();
    }
    private com.sysadminanywhere.m3.extensions.ExecutionPolicy communityPolicy() {
        var policy=mock(com.sysadminanywhere.m3.extensions.ExecutionPolicy.class);
        when(policy.state()).thenReturn(com.sysadminanywhere.m3.extensions.ExecutionPolicy.State.community("COMMUNITY"));
        when(policy.targets(anyList(),anyLong())).thenReturn(Map.of("default",1));
        return policy;
    }
    @Test void concurrentManuallyStartedWorkersShareOneCommunitySlot() throws Exception {
        var licenses=communityPolicy();var pools=mock(RuleWorkerPoolRepository.class);
        when(pools.findAll()).thenReturn(List.of(new RuleWorkerPool("default",1,1,1)));
        var allocation=new WorkerCapacityAllocation(pools,jdbc,licenses);
        var workers=new ArrayList<WorkerSlotService>();for(int i=0;i<8;i++) workers.add(transactional(new WorkerSlotService(jdbc,licenses,allocation,"default","manual-"+i)));
        try(var executor=Executors.newFixedThreadPool(8)) {
            var futures=new ArrayList<Future<Boolean>>();for(var worker:workers) futures.add(executor.submit(worker::acquire));
            int admitted=0;for(var future:futures) if(future.get(20,TimeUnit.SECONDS)) admitted++;
            assertThat(admitted).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM worker_slot",Long.class)).isEqualTo(1);
        }
        workers.forEach(WorkerSlotService::release);assertThat(workers.getFirst().acquire()).isTrue();workers.getFirst().release();
    }
    @Test void expiredLeaseInterruptsExecutionAndCannotBeRenewedOrUsedForSending(){
        var licenses=communityPolicy();var pools=mock(RuleWorkerPoolRepository.class);when(pools.findAll()).thenReturn(List.of(new RuleWorkerPool("default",1,1,1)));
        var slot=transactional(new WorkerSlotService(jdbc,licenses,new WorkerCapacityAllocation(pools,jdbc,licenses),"default","expiring-worker"));
        assertThat(slot.acquire()).isTrue();slot.begin();
        try{
            jdbc.update("UPDATE worker_slot SET expires_at=now()-interval '1 second'");slot.renew();assertThat(Thread.currentThread().isInterrupted()).isTrue();Thread.interrupted();
            assertThatThrownBy(slot::assertOwned).isInstanceOf(IllegalStateException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM worker_slot WHERE expires_at>now()",Long.class)).isZero();
        }finally{Thread.interrupted();slot.release();}
    }
    @Test void leaseExpiryUsesWallClockInsideAnAlreadyOpenTransaction(){
        var licenses=communityPolicy();var pools=mock(RuleWorkerPoolRepository.class);when(pools.findAll()).thenReturn(List.of(new RuleWorkerPool("default",1,1,1)));
        var slot=transactional(new WorkerSlotService(jdbc,licenses,new WorkerCapacityAllocation(pools,jdbc,licenses),"default","transaction-worker"));
        assertThat(slot.acquire()).isTrue();slot.begin();
        try{
            new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx->{
                jdbc.update("UPDATE worker_slot SET expires_at=clock_timestamp()+interval '100 milliseconds'");
                jdbc.query("SELECT pg_sleep(0.2)",rs->{});
                assertThatThrownBy(slot::assertOwned).isInstanceOf(IllegalStateException.class);
                Thread.interrupted();
            });
        }finally{Thread.interrupted();slot.release();}
    }
    @Test void coldCleanupRetriesFailureAndNeverDeletesLiveCatalogObjects(){
        long id=message("PROCESSED","{}");new MessageIndexService(jdbc,MASKING,manager).index();var archiver=archive(store);archiver.enqueue();archiver.archiveNext();
        String key=jdbc.queryForObject("SELECT archive_key FROM message WHERE message_id=?",String.class,id);
        jdbc.update("INSERT INTO cold_object_cleanup(object_key,message_id) VALUES(?,?)",key,id);
          new ExtendedRetention(jdbc,store,manager,0,0,"KEEP",0,0).clean();assertThat(store.get(key)).isNotEmpty();
          assertThat(jdbc.queryForObject("SELECT count(*) FROM cold_object_cleanup",Long.class)).isZero();
        jdbc.update("DELETE FROM message WHERE message_id=?",id);
        var failing=mock(ColdPayloadStore.class);when(failing.configured()).thenReturn(true);doThrow(new IllegalStateException("test outage")).when(failing).delete(key);
        new ExtendedRetention(jdbc,failing,manager,0,0,"KEEP",0,0).clean();assertThat(jdbc.queryForObject("SELECT attempts FROM cold_object_cleanup WHERE object_key=?",Integer.class,key)).isEqualTo(1);
        jdbc.update("UPDATE cold_object_cleanup SET next_attempt_at=now()-interval '1 second'");new ExtendedRetention(jdbc,store,manager,0,0,"KEEP",0,0).clean();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cold_object_cleanup",Long.class)).isZero();assertThatThrownBy(()->store.get(key)).isInstanceOf(IllegalStateException.class);
    }
    @Test void staleRestoredCleanupDoesNotStarveOrphans(){
        var cold=mock(ColdPayloadStore.class);when(cold.configured()).thenReturn(true);
        for(int index=0;index<25;index++){long id=message("PROCESSED","{}");String key="live-"+index;jdbc.update("UPDATE message SET archive_key=? WHERE message_id=?",key,id);jdbc.update("INSERT INTO cold_object_cleanup(object_key,message_id) VALUES(?,?)",key,id);}
        jdbc.update("INSERT INTO cold_object_cleanup(object_key,message_id) VALUES('orphan',9999)");
        var retention=new ExtendedRetention(jdbc,cold,manager,0,0,"KEEP",0,0);retention.clean();retention.clean();
        verify(cold).delete("orphan");verify(cold,times(2)).configured();verifyNoMoreInteractions(cold);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cold_object_cleanup",Long.class)).isZero();
    }
    @Test void postgresDumpAndColdObjectCopyRestoreAChecksummedRecoveryPoint()throws Exception{
        long id=message("PROCESSED","{\"order\":\"recovery-test\"}");new MessageIndexService(jdbc,MASKING,manager).index();var archiver=archive(store);archiver.enqueue();archiver.archiveNext();
        String key=jdbc.queryForObject("SELECT archive_key FROM message WHERE message_id=?",String.class,id);
        byte[] object=store.get(key);String digest=jdbc.queryForObject("SELECT archive_sha256 FROM message WHERE message_id=?",String.class,id);
        assertThat(DB.execInContainer("pg_dump","-U",DB.getUsername(),"-d",DB.getDatabaseName(),"-Fc","-f","/tmp/m3-recovery.dump").getExitCode()).isZero();
        assertThat(DB.execInContainer("psql","-U",DB.getUsername(),"-d",DB.getDatabaseName(),"-c","CREATE DATABASE m3_recovered").getExitCode()).isZero();
        var restored=DB.execInContainer("pg_restore","-U",DB.getUsername(),"-d","m3_recovered","--no-owner","--exit-on-error","/tmp/m3-recovery.dump");assertThat(restored.getExitCode()).as(restored.getStderr()).isZero();
        String endpoint="http://"+S3.getHost()+":"+S3.getMappedPort(9000);String bucket="recovery-"+UUID.randomUUID();
        MinioClient.builder().endpoint(endpoint).credentials("test-user","test-password-123").build().makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        var recoveryStore=new S3ColdPayloadStore(endpoint,"test-user","test-password-123",bucket,"us-east-1");recoveryStore.put(key,object);assertThat(SourceDeliveryService.digest(recoveryStore.get(key))).isEqualTo(digest);
        var ds=new DriverManagerDataSource(DB.getJdbcUrl().replace("/"+DB.getDatabaseName(),"/m3_recovered"),DB.getUsername(),DB.getPassword());var recovered=new JdbcTemplate(ds);
        assertThat(recovered.queryForObject("SELECT installation_id FROM m3_installation",UUID.class)).isEqualTo(jdbc.queryForObject("SELECT installation_id FROM m3_installation",UUID.class));
        assertThat(recovered.queryForObject("SELECT archive_sha256 FROM message WHERE message_id=?",String.class,id)).isEqualTo(digest);
        var envelope=new MessageArchiveService(recovered,recoveryStore,JSON,MASKING,new DataSourceTransactionManager(ds),"ARCHIVE",7).read(load(id));
        assertThat(new String(envelope.payload(),StandardCharsets.UTF_8)).contains("recovery-test");
    }
}
