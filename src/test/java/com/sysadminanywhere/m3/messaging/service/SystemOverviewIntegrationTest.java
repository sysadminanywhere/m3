package com.sysadminanywhere.m3.messaging.service;

import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.worker.WorkerHeartbeat;
import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.hibernate.SessionFactory;
import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.DriverManager;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** Isolated real PostgreSQL: filters, complete migrations and worker telemetry. */
@Testcontainers(disabledWithoutDocker = true)
class SystemOverviewIntegrationTest {
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17-alpine");
    static JdbcTemplate jdbc;
    static SessionFactory factory;

    @BeforeAll static void initialize() throws Exception {
        try (var connection = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             var liquibase = new Liquibase("db/changelog/db.changelog-master.xml", new ClassLoaderResourceAccessor(),
                     DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.update(new Contexts(), new LabelExpression());
        }
        jdbc = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        factory = new Configuration().addAnnotatedClass(ChannelSettings.class).addAnnotatedClass(Message.class)
                .addAnnotatedClass(MessageMetadata.class).addAnnotatedClass(Rule.class).addAnnotatedClass(RuleCondition.class)
                .addAnnotatedClass(RuleAction.class).addAnnotatedClass(RuleWorkerPool.class).addAnnotatedClass(RuleExecutionJob.class)
                .addAnnotatedClass(WorkerPoolMetricSample.class).setPhysicalNamingStrategy(new PhysicalNamingStrategySnakeCaseImpl())
                .setProperty("hibernate.connection.url", DB.getJdbcUrl()).setProperty("hibernate.connection.username", DB.getUsername())
                .setProperty("hibernate.connection.password", DB.getPassword()).setProperty("hibernate.hbm2ddl.auto", "validate").buildSessionFactory();
    }
    @AfterAll static void close() { if (factory != null) factory.close(); }
    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE message, channel_settings, rule_worker_pool, message_receipt_outbox RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO rule_worker_pool(name,desired_replicas,min_replicas,max_replicas,created_at) VALUES('default',1,1,4,now()),('second',1,1,4,now())");
    }
    private long message(String direction, String status, String source, String target, Instant time) {
        return jdbc.queryForObject("INSERT INTO message(direction,status,payload_bytes,charset,charset_source,payload_format,payload_type,source_system,target_system,created_at) VALUES(?,?,convert_to('x','UTF8'),'UTF-8','TEXT_UTF8','TEXT','text/plain',?,?,?) RETURNING message_id",
                Long.class, direction,status,source,target,java.sql.Timestamp.from(time));
    }
    private List<Message> search(MessageSearch filters, ZoneId zone) {
        try (var session = factory.openSession()) {
            var cb = session.getCriteriaBuilder(); var query = cb.createQuery(Message.class); var root = query.from(Message.class);
            query.where(filters.specification(MessageDirection.INBOUND, zone).toPredicate(root,query,cb));
            return session.createQuery(query).getResultList();
        }
    }
    @Test void combinesDirectionIdStatusLiteralTextAndInclusiveLocalDates() {
        var zone = ZoneId.of("Europe/Moscow"); var day = LocalDate.of(2026,10,6);
        var start = day.atStartOfDay(zone).toInstant();
        long first = message("INBOUND","FAILED","FILE%_\\PATH","Destination",start);
        long last = message("INBOUND","FAILED","file%_\\path","destination",day.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1));
        message("INBOUND","FAILED","file%_\\path","destination",start.minusSeconds(1));
        message("INBOUND","FAILED","file%_\\path","destination",day.plusDays(1).atStartOfDay(zone).toInstant());
        message("OUTBOUND","FAILED","file%_\\path","destination",start);
        message("INBOUND","SENT","file%_\\path","destination",start);
        message("INBOUND","FAILED","fileXY\\path","destination",start);
        var filter = new MessageSearch(null,MessageStatus.FAILED,"%_\\","DEST",day,day);
        assertThat(search(filter,zone)).extracting(Message::getId).containsExactlyInAnyOrder(first,last);
        assertThat(search(new MessageSearch(first,MessageStatus.FAILED,"","",null,null),zone)).extracting(Message::getId).containsExactly(first);
    }
    @Test void reportsQueuesMovedRulesFailuresReceiptsAndFreshWorkerProcesses() {
        long inbound = message("INBOUND","FAILED","s","t",Instant.now());
        message("OUTBOUND","FAILED","s","t",Instant.now());
        message("OUTBOUND","SENT","s","t",Instant.now());
        jdbc.update("INSERT INTO channel_settings(name,channel_type,direction,created_at) VALUES('test','DIRECTORY','INBOUND',now())");
        jdbc.update("INSERT INTO rule(name,rule_type,source_channel_id,worker_pool_id,created_at) VALUES('test','INBOUND',1,2,now())");
        for (String status : List.of("PENDING","PROCESSING","FAILED","COMPLETED"))
            jdbc.update("INSERT INTO rule_execution_job(message_id,rule_id,worker_pool_id,status,created_at) VALUES(?,1,1,?,now()-interval '1 minute')",inbound,status);
        jdbc.update("INSERT INTO message_receipt_outbox(event_id,message_id,attempts,published_at) VALUES(gen_random_uuid(),?,0,NULL),(gen_random_uuid(),?,2,NULL),(gen_random_uuid(),?,2,now())",inbound,inbound,inbound);
        var worker = new WorkerHeartbeat(jdbc,"default","test-worker"); worker.heartbeat(); worker.heartbeat();
        new WorkerHeartbeat(jdbc,"default","test-worker").heartbeat();
        var snapshot = new SystemOverviewService(jdbc).snapshot();
        assertThat(snapshot.pending()).isEqualTo(1); assertThat(snapshot.processing()).isEqualTo(1);
        assertThat(snapshot.failedInbound()).isEqualTo(1); assertThat(snapshot.failedOutbound()).isEqualTo(1);
        assertThat(snapshot.pendingReceipts()).isEqualTo(2); assertThat(snapshot.retryingReceipts()).isEqualTo(1);
        assertThat(snapshot.oldestPending()).isBefore(snapshot.sampledAt().minusSeconds(50));
        var defaultPool = snapshot.pools().getFirst(); var second = snapshot.pools().get(1);
        assertThat(defaultPool.name()).isEqualTo("default"); assertThat(defaultPool.activeWorkers()).isEqualTo(2);
        assertThat(defaultPool.pending()).isZero(); assertThat(defaultPool.processing()).isEqualTo(1);
        assertThat(second.pending()).isEqualTo(1); assertThat(second.failed()).isZero(); assertThat(defaultPool.failed()).isEqualTo(1);
        jdbc.update("UPDATE worker_heartbeat SET checked_at=now()-interval '40 seconds'");
        assertThat(new SystemOverviewService(jdbc).snapshot().pools().getFirst().activeWorkers()).isZero();
        worker.heartbeat(); assertThat(new SystemOverviewService(jdbc).snapshot().pools().getFirst().activeWorkers()).isEqualTo(1);
        jdbc.update("DELETE FROM rule_execution_job"); jdbc.update("DELETE FROM rule_worker_pool WHERE name='default'");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM worker_heartbeat",Long.class)).isZero();
    }
    @Test void retentionDeletesOnlyCompletedMessagesAndPublishedReceipts() {
        long done=message("INBOUND","PROCESSED","s","t",Instant.now().minusSeconds(864000));
        long failed=message("INBOUND","FAILED","s","t",Instant.now().minusSeconds(864000));
        long awaitingReceipt=message("INBOUND","PROCESSED","s","t",Instant.now().minusSeconds(864000));
        jdbc.update("UPDATE message SET processed_at=created_at");
        jdbc.update("INSERT INTO message_receipt_outbox(event_id,message_id,published_at) VALUES(gen_random_uuid(),?,NULL)",awaitingReceipt);
        new RetentionService(jdbc,1,30,0,365).clean();
        assertThat(jdbc.queryForList("SELECT message_id FROM message ORDER BY message_id",Long.class)).containsExactly(failed,awaitingReceipt);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_receipt_outbox",Long.class)).isEqualTo(1);
    }
    @Test void expiredClaimAfterSendingIsFailedInsteadOfAutomaticallyReplayed() {
        long uncertain=message("OUTBOUND","PENDING","s","t",Instant.now());
        long safe=message("OUTBOUND","PENDING","s","t",Instant.now());
        jdbc.update("INSERT INTO channel_settings(name,channel_type,direction,created_at) VALUES('test','DIRECTORY','OUTBOUND',now())");
        jdbc.update("INSERT INTO rule(name,rule_type,source_channel_id,worker_pool_id,created_at) VALUES('test','OUTBOUND',1,1,now())");
        jdbc.update("INSERT INTO rule_execution_job(message_id,rule_id,worker_pool_id,status,created_at,claimed_at,delivery_started,claim_token) VALUES(?,1,1,'PROCESSING',now(),now()-interval '10 minutes',true,gen_random_uuid()),(?,1,1,'PROCESSING',now(),now()-interval '10 minutes',false,gen_random_uuid())",uncertain,safe);
        var jobs=org.mockito.Mockito.mock(com.sysadminanywhere.m3.messaging.repository.RuleExecutionJobRepository.class);
        var processor=new RuleExecutionProcessor(jobs,null,null,null,null,"default","test-worker",300);
        try(var session=factory.openSession()) {
            session.beginTransaction();
            org.springframework.test.util.ReflectionTestUtils.setField(processor,"entityManager",session);
            assertThat(processor.reclaimExpired()).isEqualTo(1);
            session.getTransaction().commit();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM message WHERE message_id=?",String.class,uncertain)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT delivery_uncertain FROM rule_execution_job WHERE message_id=?",Boolean.class,uncertain)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM rule_execution_job WHERE message_id=?",String.class,safe)).isEqualTo("PROCESSING");
        org.mockito.Mockito.verify(jobs).releaseExpiredClaims("default",300);
    }
}
