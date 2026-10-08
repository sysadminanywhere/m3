package com.sysadminanywhere.m3.messaging.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.base.security.*;
import liquibase.*;
import liquibase.database.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.DriverManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@Testcontainers(disabledWithoutDocker=true)
class ExternalProcessingIntegrationTest {
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    static JdbcTemplate jdbc;static DataSourceTransactionManager manager;
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final PayloadMasking MASKING=new PayloadMasking(JSON,"password,token","","");
    MachineAccounts accounts;ExternalProcessingService service;
    @BeforeAll static void migrate()throws Exception{
        try(var connection=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var migration=new Liquibase("db/changelog/db.changelog-master.xml",new ClassLoaderResourceAccessor(),DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)))){migration.update(new Contexts(),new LabelExpression());}
        var ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
    }
    @BeforeEach void reset(){
        SecurityContextHolder.clearContext();jdbc.execute("TRUNCATE message,channel_settings,rule_worker_pool,operational_alert RESTART IDENTITY CASCADE");jdbc.update("UPDATE storage_usage SET hot_bytes=0,max_hot_bytes=0,max_database_bytes=0,admission_blocked=false");
        jdbc.update("INSERT INTO channel_settings(name,channel_type,direction,created_at) VALUES('source','DIRECTORY','INBOUND',now()),('other','DIRECTORY','INBOUND',now())");
        accounts=new MachineAccounts();var target=new ExternalProcessingService(jdbc,JSON,MASKING,new MessageAccess(accounts,jdbc),60,30);
        var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));service=(ExternalProcessingService)proxy.getProxy();
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void monitoringRefreshesAndPreservesExplicitlyStaleDataDuringDatabaseFailure(){
        create("external");var operations=new OperationsService(jdbc,MASKING,0,"",0);var first=operations.refresh();
        assertThat(first.metrics().get("m3_processing_events_pending")).isEqualTo(1);create("external");assertThat(operations.snapshot()).isSameAs(first);
        var latest=operations.refresh();assertThat(latest.metrics().get("m3_processing_events_pending")).isEqualTo(2);assertThat(operations.stale(latest)).isFalse();
        jdbc.execute("ALTER TABLE message_archive_job RENAME TO test_unavailable_archive_jobs");
        try{assertThatThrownBy(operations::refresh).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(operations.snapshot()).isSameAs(latest);assertThat(operations.stale(latest)).isTrue();assertThat(operations.prometheus()).contains("m3_operations_data_stale 1");}
        finally{jdbc.execute("ALTER TABLE test_unavailable_archive_jobs RENAME TO message_archive_job");}
        assertThat(operations.stale(operations.refresh())).isFalse();assertThat(operations.prometheus()).contains("m3_operations_data_stale 0");
    }
    @Test void attemptHistoryIsBoundedNewestFirstAndMasksDiagnostics(){
        long id=create("external");update(id,"external",MessageStatus.PROCESSING_FAILED);var first=current(id,"external");
        jdbc.update("UPDATE message_processing SET created_at=now()-interval '1 day',detail='password=history-secret' WHERE attempt_id=?",first.attemptId());
        var next=service.replay(id,"external",new ExternalProcessingService.Replay(UUID.randomUUID(),first.attemptId(),first.version(),false));
        var history=service.attemptHistory(id,1);assertThat(history.total()).isEqualTo(2);assertThat(history.attempts()).hasSize(1);assertThat(history.attempts().getFirst().attemptId()).isEqualTo(next.processing().attemptId());
        assertThat(service.attemptHistory(id,200).attempts()).extracting(ExternalProcessingService.Attempt::detail).doesNotContain("password=history-secret");
        assertThatThrownBy(()->service.attemptHistory(id,201)).isInstanceOf(IllegalArgumentException.class);
    }
    private long create(String recipients){
        long id=jdbc.queryForObject("INSERT INTO message(direction,status,source_channel_id,payload_bytes,payload_type,payload_format,charset,charset_source,created_at) VALUES('INBOUND','LOADED',1,?,'application/json','TEXT','UTF-8','TEXT_UTF8',now()) RETURNING message_id",Long.class,"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var message=new Message(MessageDirection.INBOUND,"{}","application/json");org.springframework.test.util.ReflectionTestUtils.setField(message,"id",id);
        var rule=Rule.snapshot(1L,"rule",RuleType.INBOUND,ChannelSettings.snapshot(1L,"source",ChannelType.DIRECTORY,ChannelDirection.INBOUND,Map.of()));rule.getLoadingProperties().put("processingRecipients",recipients);
        new TransactionTemplate(manager).executeWithoutResult(tx->service.initialize(message,rule));return id;
    }
    private ExternalProcessingService.Attempt current(long id,String recipient){return service.attempts(id,false).stream().filter(a->a.recipient().equals(recipient)).findFirst().orElseThrow();}
    private ExternalProcessingService.Result update(long id,String recipient,MessageStatus status){var a=current(id,recipient);return service.callback(id,recipient,new ExternalProcessingService.Callback(UUID.randomUUID(),a.attemptId(),a.version(),status,null,"password=private"));}
    @Test void aggregatesRequiredRecipientsAndPreservesOptionalErrors(){
        long id=create("erp,billing,analytics?");assertThat(update(id,"erp",MessageStatus.PROCESSED).messageStatus()).isEqualTo(MessageStatus.PROCESSING);
        assertThat(update(id,"analytics",MessageStatus.PROCESSING_FAILED).messageStatus()).isEqualTo(MessageStatus.PROCESSING);
        assertThat(update(id,"billing",MessageStatus.PROCESSED).messageStatus()).isEqualTo(MessageStatus.PROCESSED);
        assertThat(service.attempts(id,false)).extracting(ExternalProcessingService.Attempt::detail).doesNotContain("password=private");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processing_event_outbox WHERE message_id=?",Long.class,id)).isEqualTo(6);
    }
    @Test void replayCreatesAttemptAndRejectsStaleAndAlteredCallbacks(){
        long id=create("external");var first=current(id,"external");var request=new ExternalProcessingService.Callback(UUID.randomUUID(),first.attemptId(),0L,MessageStatus.PROCESSING_FAILED,null,null);
        var response=service.callback(id,"external",request);assertThat(service.callback(id,"external",request)).isEqualTo(response);
        assertThatThrownBy(()->service.callback(id,"external",new ExternalProcessingService.Callback(request.callbackId(),first.attemptId(),0L,MessageStatus.PROCESSED,null,null))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var failed=current(id,"external");var replay=new ExternalProcessingService.Replay(UUID.randomUUID(),failed.attemptId(),failed.version(),false);
        var next=service.replay(id,"external",replay);assertThat(service.replay(id,"external",replay)).isEqualTo(next);
        assertThat(next.processing().attemptNo()).isEqualTo(2);assertThat(next.processing().attemptId()).isNotEqualTo(first.attemptId());
        assertThatThrownBy(()->service.callback(id,"external",new ExternalProcessingService.Callback(UUID.randomUUID(),first.attemptId(),0L,MessageStatus.PROCESSED,null,null))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        update(id,"external",MessageStatus.PROCESSING);var running=current(id,"external");
        assertThatThrownBy(()->service.callback(id,"external",new ExternalProcessingService.Callback(UUID.randomUUID(),running.attemptId(),0L,MessageStatus.PROCESSED,null,null))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(service.attempts(id,true)).hasSize(2);
    }
    @Test void overdueIsAnObservationAndLateSuccessResolvesAlert(){
        long id=create("external");jdbc.update("UPDATE message_processing SET deadline_at=now()-interval '1 second' WHERE message_id=?",id);service.detectOverdue();service.detectOverdue();
        var a=current(id,"external");assertThat(a.status()).isEqualTo(MessageStatus.LOADED);assertThat(a.overdue()).isTrue();assertThat(a.version()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processing_event_outbox WHERE event_type='processing.overdue'",Long.class)).isEqualTo(1);
        update(id,"external",MessageStatus.PROCESSED);assertThat(current(id,"external").overdue()).isFalse();assertThat(jdbc.queryForObject("SELECT count(*) FROM operational_alert WHERE resolved_at IS NULL",Long.class)).isZero();
    }
    @Test void machinesCannotCrossChannelsRecipientsOrPermissions(){
        long id=create("erp,billing");var account=new MachineAccounts.Account();account.setUsername("erp-service");account.setChannels(Set.of(1L));account.setRecipients(Set.of("erp"));account.setStatus(true);accounts.getServices().put("erp",account);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("erp-service","unused",List.of(new SimpleGrantedAuthority("ROLE_SERVICE"))));
        assertThat(service.attempts(id,false)).extracting(ExternalProcessingService.Attempt::recipient).containsExactly("erp");update(id,"erp",MessageStatus.PROCESSED);
        assertThatThrownBy(()->service.callback(id,"billing",new ExternalProcessingService.Callback(UUID.randomUUID(),UUID.randomUUID(),0L,MessageStatus.PROCESSED,null,null))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var a=current(id,"erp");assertThatThrownBy(()->service.replay(id,"erp",new ExternalProcessingService.Replay(UUID.randomUUID(),a.attemptId(),a.version(),true))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        account.setChannels(Set.of(2L));assertThatThrownBy(()->service.attempts(id,false)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void quotaRejectsExtraPayloadWithoutPartialCommit(){
        jdbc.update("UPDATE storage_usage SET max_hot_bytes=3 WHERE singleton");create("external");
        assertThatThrownBy(()->create("external")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message",Long.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT hot_bytes FROM storage_usage",Long.class)).isEqualTo(2);
    }
    @Test void terminalAttemptsCannotBeReopenedByCallbackAndActiveReplayNeedsAcknowledgement(){
        long id=create("external");var a=current(id,"external");assertThatThrownBy(()->service.replay(id,"external",new ExternalProcessingService.Replay(UUID.randomUUID(),a.attemptId(),a.version(),false))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        update(id,"external",MessageStatus.PROCESSED);var completed=current(id,"external");
        assertThatThrownBy(()->service.callback(id,"external",new ExternalProcessingService.Callback(UUID.randomUUID(),completed.attemptId(),completed.version(),MessageStatus.LOADED,null,null))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void eventPublicationFailureRetainsIdenticalDurableEventForRetry()throws Exception{
        long id=create("external");var publisher=org.mockito.Mockito.mock(com.sysadminanywhere.m3.messaging.broker.ProcessingEventPublisher.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("test broker failure")).when(publisher).publish(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
        var target=new com.sysadminanywhere.m3.messaging.broker.ProcessingEventDispatcher(jdbc,publisher);var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));var dispatcher=(com.sysadminanywhere.m3.messaging.broker.ProcessingEventDispatcher)proxy.getProxy();
        UUID event=jdbc.queryForObject("SELECT event_id FROM processing_event_outbox WHERE message_id=?",UUID.class,id);dispatcher.dispatch();
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL AND attempts=1 AND next_attempt_at>now() FROM processing_event_outbox WHERE event_id=?",Boolean.class,event)).isTrue();
        jdbc.update("UPDATE processing_event_outbox SET next_attempt_at=now()-interval '1 second'");org.mockito.Mockito.doNothing().when(publisher).publish(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());dispatcher.dispatch();
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL AND attempts=2 FROM processing_event_outbox WHERE event_id=?",Boolean.class,event)).isTrue();
        org.mockito.Mockito.verify(publisher,org.mockito.Mockito.times(2)).publish(org.mockito.ArgumentMatchers.eq(event),org.mockito.ArgumentMatchers.eq("processing.requested"),org.mockito.ArgumentMatchers.anyString());
    }
}
