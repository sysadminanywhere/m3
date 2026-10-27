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
    @Test void receiverHealthDistinguishesStaleReportsFromCurrentFailures() {
        jdbc.update("INSERT INTO channel_settings(name,channel_type,direction,created_at) VALUES('source','DIRECTORY','INBOUND',now())");
        jdbc.update("INSERT INTO rule(name,rule_type,source_channel_id,worker_pool_id,created_at) VALUES('load','INBOUND',1,1,now())");
        var health=new com.sysadminanywhere.m3.messaging.source.SourceHealth(jdbc,new org.springframework.mock.env.MockEnvironment());
        assertThat(health.get(1).status()).isEqualTo("NO_WORKER");
        jdbc.update("INSERT INTO rule_source_health(rule_id,worker_id,status,checked_at,error_message) VALUES(1,'old','RUNNING',now()-interval '36 seconds',NULL)");
        assertThat(health.get(1).status()).isEqualTo("STALE");
        jdbc.update("INSERT INTO rule_source_health(rule_id,worker_id,status,checked_at,error_message) VALUES(1,'active','RUNNING',now(),NULL)");
        assertThat(health.get(1).status()).isEqualTo("RUNNING");
        jdbc.update("UPDATE rule_source_health SET status='RETRYING',error_message='Connection failed' WHERE worker_id='active'");
        assertThat(health.get(1).status()).isEqualTo("RETRYING");
        assertThat(health.get(1).error()).isEqualTo("Connection failed");
        jdbc.update("UPDATE rule_source_health SET checked_at=now()-interval '36 seconds' WHERE worker_id='active'");
        assertThat(health.get(1).status()).isEqualTo("STALE");
    }
    @Test void localUsageSummarizesOnlyRetainedRecentMessagesAndEnabledConfiguration() {
        message("INBOUND","LOADED","private-source","private-target",Instant.now());
        message("OUTBOUND","SENT","private-source","private-target",Instant.now());
        message("OUTBOUND","FAILED","private-source","private-target",Instant.now().minusSeconds(86400));
        message("OUTBOUND","SENT","private-source","private-target",Instant.now().minusSeconds(31L*86400));
        jdbc.update("INSERT INTO channel_settings(name,channel_type,direction,enabled,created_at) VALUES('enabled','DIRECTORY','INBOUND',true,now()),('disabled','KAFKA','INBOUND',false,now())");
        jdbc.update("INSERT INTO rule(name,rule_type,source_channel_id,worker_pool_id,enabled,created_at) VALUES('active','INBOUND',1,1,true,now()),('inactive','INBOUND',2,1,false,now())");
        jdbc.update("INSERT INTO rule_action(rule_id,action_type,priority) VALUES(1,'FILTER',0),(2,'ENRICH',0)");
        var summary=new LocalUsageService(jdbc).summary();
        assertThat(summary.activeDays()).isEqualTo(2);
        assertThat(summary.inbound()).isEqualTo(1);
        assertThat(summary.outbound()).isEqualTo(2);
        assertThat(summary.sent()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.enabledRules()).isEqualTo(1);
        assertThat(summary.channels()).containsExactly(new LocalUsageService.Count("DIRECTORY",1));
        assertThat(summary.actions()).containsExactly(new LocalUsageService.Count("FILTER",1));
        assertThat(summary.toString()).doesNotContain("private-source", "private-target");
    }
    @Test void receiptRetryWaitsUntilDueAndKeepsItsEventId() throws Exception {
        long id=message("INBOUND","LOADED","s","t",Instant.now());
        var eventId=java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO message_receipt_outbox(event_id,message_id) VALUES(?,?)",eventId,id);
        var publisher=org.mockito.Mockito.mock(com.sysadminanywhere.m3.messaging.broker.MessageReceivedPublisher.class);
        org.mockito.Mockito.doThrow(new java.io.IOException("broker unavailable"))
                .doNothing().when(publisher).publish(org.mockito.ArgumentMatchers.any());
        var dispatcher=new com.sysadminanywhere.m3.messaging.broker.MessageReceiptDispatcher(jdbc,publisher);
        var transactions=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));

        transactions.executeWithoutResult(status -> dispatcher.dispatch());
        assertThat(jdbc.queryForObject("SELECT attempts=1 AND published_at IS NULL AND next_attempt_at>now() FROM message_receipt_outbox WHERE event_id=?",Boolean.class,eventId)).isTrue();
        transactions.executeWithoutResult(status -> dispatcher.dispatch());
        org.mockito.Mockito.verify(publisher,org.mockito.Mockito.times(1)).publish(org.mockito.ArgumentMatchers.any());
        jdbc.update("UPDATE message_receipt_outbox SET next_attempt_at=now()-interval '1 second' WHERE event_id=?",eventId);
        transactions.executeWithoutResult(status -> dispatcher.dispatch());
        assertThat(jdbc.queryForObject("SELECT attempts=2 AND published_at IS NOT NULL AND last_error IS NULL FROM message_receipt_outbox WHERE event_id=?",Boolean.class,eventId)).isTrue();
        var events=org.mockito.ArgumentCaptor.forClass(com.sysadminanywhere.m3.messaging.broker.MessageReceivedEvent.class);
        org.mockito.Mockito.verify(publisher,org.mockito.Mockito.times(2)).publish(events.capture());
        assertThat(events.getAllValues()).extracting(com.sysadminanywhere.m3.messaging.broker.MessageReceivedEvent::eventId).containsExactly(eventId,eventId);
    }
    @Test void expiredClaimAfterSendingIsFailedInsteadOfAutomaticallyReplayed() {
        long uncertain=message("OUTBOUND","PENDING","s","t",Instant.now());
        long safe=message("OUTBOUND","PENDING","s","t",Instant.now());
        long fresh=message("OUTBOUND","PENDING","s","t",Instant.now());
        long otherPool=message("OUTBOUND","PENDING","s","t",Instant.now());
        jdbc.update("INSERT INTO channel_settings(name,channel_type,direction,created_at) VALUES('test','DIRECTORY','OUTBOUND',now())");
        jdbc.update("INSERT INTO rule(name,rule_type,source_channel_id,worker_pool_id,created_at) VALUES('test','OUTBOUND',1,1,now())");
        jdbc.update("INSERT INTO rule_execution_job(message_id,rule_id,worker_pool_id,status,created_at,claimed_at,delivery_started,claim_token) VALUES(?,1,1,'PROCESSING',now(),now()-interval '10 minutes',true,gen_random_uuid()),(?,1,1,'PROCESSING',now(),now()-interval '10 minutes',false,gen_random_uuid())",uncertain,safe);
        jdbc.update("INSERT INTO rule_execution_job(message_id,rule_id,worker_pool_id,status,created_at,claimed_at,delivery_started,claim_token) VALUES(?,1,1,'PROCESSING',now(),now(),false,gen_random_uuid()),(?,1,2,'PROCESSING',now(),now()-interval '10 minutes',false,gen_random_uuid())",fresh,otherPool);
        try(var session=factory.openSession()) {
            session.beginTransaction();
            var jobs=new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(session)
                    .getRepository(com.sysadminanywhere.m3.messaging.repository.RuleExecutionJobRepository.class);
            var processor=new RuleExecutionProcessor(jobs,null,null,null,null,"default","test-worker",300);
            org.springframework.test.util.ReflectionTestUtils.setField(processor,"entityManager",session);
            assertThat(processor.reclaimExpired()).isEqualTo(2);
            session.getTransaction().commit();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM message WHERE message_id=?",String.class,uncertain)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT delivery_uncertain FROM rule_execution_job WHERE message_id=?",Boolean.class,uncertain)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM rule_execution_job WHERE message_id=?",String.class,safe)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT claim_token FROM rule_execution_job WHERE message_id=?",java.util.UUID.class,safe)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM rule_execution_job WHERE message_id=?",String.class,fresh)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT status FROM rule_execution_job WHERE message_id=?",String.class,otherPool)).isEqualTo("PROCESSING");
    }
}
