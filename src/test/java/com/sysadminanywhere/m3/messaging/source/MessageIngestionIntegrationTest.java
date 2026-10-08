package com.sysadminanywhere.m3.messaging.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.Application;
import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.service.ChannelSettingsService;
import com.sysadminanywhere.m3.messaging.service.RuleService;
import com.sysadminanywhere.m3.messaging.repository.RuleActionRepository;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real sources, PostgreSQL, receipt broker and HTTP API; never uses the developer's database. */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MessageIngestionIntegrationTest {
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17-alpine");
    @Container static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:4-management-alpine")
            .withEnv("RABBITMQ_DEFAULT_USER", "m3").withEnv("RABBITMQ_DEFAULT_PASS", "m3")
            .withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:4.0.0");
    private static HttpRequest.Builder authorizedRequest(java.net.URI uri) {
        return HttpRequest.newBuilder(uri).header("X-M3-Request", "1").header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString("admin:test-admin-password-123456".getBytes(StandardCharsets.UTF_8)));
    }
    private static ConfigurableApplicationContext app;
    private static ConfigurableApplicationContext receiverWorker;
    @TempDir Path temp;

    @BeforeAll static void startApplication() { app = start(); receiverWorker = worker("default"); }
    @AfterAll static void stopApplication() { if (receiverWorker != null) receiverWorker.close(); if (app != null) app.close(); }
    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(Application.class).run(
                "--m3.security.services.erp.username=erp-service", "--m3.security.services.erp.password-hash={bcrypt}"+new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("test-erp-password-123456"),
                "--m3.security.services.erp.channels=1", "--m3.security.services.erp.recipients=erp", "--m3.security.services.erp.status=true", "--m3.security.services.erp.original=true", "--m3.security.services.erp.replay=false",
                "--m3.security.admin-password=test-admin-password-123456", "--m3.security.viewer-password=test-viewer-password-123456", "--m3.security.operator-password=test-operator-password-123456", "--m3.secret-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "--m3.security.vaadin-ui=false", "--server.port=0", "--vaadin.launch-browser=false", "--spring.jpa.show-sql=false",
                "--spring.datasource.url=" + DB.getJdbcUrl(), "--spring.datasource.username=" + DB.getUsername(),
                "--spring.datasource.password=" + DB.getPassword(), "--m3.docker.api-url=",
                "--m3.broker.rabbit.host=" + RABBIT.getHost(), "--m3.broker.rabbit.port=" + RABBIT.getMappedPort(5672),
                "--m3.broker.rabbit.username=m3", "--m3.broker.rabbit.password=m3",
                "--m3.sources.reconcile-delay-ms=200", "--m3.receipts.poll-delay-ms=100",
                "--spring.autoconfigure.exclude=com.vaadin.flow.spring.SpringBootAutoConfiguration,com.vaadin.flow.spring.SpringSecurityAutoConfiguration,com.vaadin.hilla.EndpointController,com.vaadin.hilla.push.PushConfigurer,com.vaadin.hilla.ApplicationContextProvider,com.vaadin.hilla.startup.EndpointRegistryInitializer,com.vaadin.hilla.startup.RouteUnifyingServiceInitListener,com.vaadin.hilla.route.RouteUtilConfiguration,com.vaadin.hilla.route.RouteUnifyingConfiguration,com.vaadin.hilla.signals.config.SignalsConfiguration",
                "--logging.level.org.apache.ftpserver=WARN", "--logging.level.org.apache.sshd=WARN",
                "--logging.level.org.apache.kafka=WARN", "--logging.level.org.springframework.kafka=ERROR");
    }
    @BeforeEach void disableOldSourcesAndDrainReceipts() {
        for (var channel : channels().findAll()) {
            if (channel.getDirection() == ChannelDirection.OUTBOUND && Boolean.TRUE.equals(channel.getEnabled())) channels().toggleEnabled(channel.getId());
        }
        for (var rule : app.getBean(RuleService.class).findAll()) {
            if (Boolean.TRUE.equals(rule.getEnabled())) app.getBean(RuleService.class).toggleEnabled(rule.getId());
        }
        receiverWorker.getBean(InboundSourceRegistry.class).reconcile();
        jdbc().execute("DROP TRIGGER IF EXISTS reject_test_message ON message");
        app.getBean(RabbitAdmin.class).initialize();
        while (rabbit().receive("m3.message.received", 50) != null) { }
    }
    private static ChannelSettingsService channels() { return app.getBean(ChannelSettingsService.class); }
    private static InboundSourceRegistry registry() { return app.getBean(InboundSourceRegistry.class); }
    private static JdbcTemplate jdbc() { return app.getBean(JdbcTemplate.class); }
    private static RabbitTemplate rabbit() { return app.getBean(RabbitTemplate.class); }
    private static ObjectMapper json() { return app.getBean(ObjectMapper.class); }
    private static ConfigurableApplicationContext worker(String pool) {
        return new SpringApplicationBuilder(Application.class).run("--spring.profiles.active=worker",
                "--m3.secret-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "--spring.main.web-application-type=none", "--spring.liquibase.enabled=false", "--spring.jpa.show-sql=false",
                "--spring.datasource.url=" + DB.getJdbcUrl(), "--spring.datasource.username=" + DB.getUsername(),
                "--spring.datasource.password=" + DB.getPassword(), "--m3.worker.pool=" + pool,
                "--m3.broker.rabbit.host=" + RABBIT.getHost(),"--m3.broker.rabbit.port=" + RABBIT.getMappedPort(5672),
                "--m3.broker.rabbit.username=m3","--m3.broker.rabbit.password=m3",
                "--m3.worker.poll-delay-ms=100", "--m3.worker.reaper-delay-ms=100", "--vaadin.launch-browser=false");
    }
    private long outboundRule(ChannelSettings target) {
        var service = app.getBean(RuleService.class);
        var rule = service.createRule("send-" + target.getName(), RuleType.OUTBOUND, target.getId(), 0);
        var action = service.addAction(rule.getId(), ActionType.ROUTE); action.setTargetChannel(target.getName());
        app.getBean(RuleActionRepository.class).save(action);
        return rule.getId();
    }
    private static HttpResponse<String> submit(long ruleId, String payload, String payloadType, Map<String, String> metadata, String key) throws Exception {
        var body = json().writeValueAsString(Map.of("ruleId", ruleId, "payload", payload, "payloadType", payloadType, "metadata", metadata));
        var request = authorizedRequest(api("/api/v1/messages/outbound")).header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private long submitBinaryAndDeliver(String name, ChannelType type, Map<String, String> properties) throws Exception {
        var target = channels().createChannel(name, type, ChannelDirection.OUTBOUND, null, properties);
        long rule = outboundRule(target);
        var response = submit(rule, Base64.getEncoder().encodeToString(binary()), "application/octet-stream",
                Map.of("encoding", "base64", "fileName", "payload.bin", "traceId", "outbound-42"), name);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        long id = json().readTree(response.body()).path("id").asLong();
        assertThat(json().readTree(response.body()).path("status").asText()).isEqualTo("PENDING");
        assertThat(jdbc().queryForObject("SELECT count(*) FROM rule_execution_job WHERE message_id=?", Long.class, id)).isEqualTo(1);
        try (var worker = worker("default")) {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc().queryForObject("SELECT status FROM message WHERE message_id=?", String.class, id)).isEqualTo("SENT"));
        }
        assertThat(json().readTree(get("/api/v1/messages/" + id).body()).path("payload").asText()).isEqualTo(Base64.getEncoder().encodeToString(binary()));
        assertThat(json().readTree(get("/api/v1/messages/" + id + "/delivery").body()).get(0).path("ruleId").asLong()).isEqualTo(rule);
        return id;
    }
    private ChannelSettings source(String name, ChannelType type, Map<String, String> properties) {
        var saved = channels().createChannel(name, type, ChannelDirection.INBOUND, null, properties);
        var loading = new HashMap<String,String>();
        properties.forEach((key,value) -> { if (InboundSourceSpec.LOADING_KEYS.contains(key)) loading.put(key,value); });
        var service = app.getBean(RuleService.class);
        var rule = service.createRule("load-" + name,RuleType.INBOUND,saved.getId(),0);
        service.saveConfiguration(rule.getId(),rule.getName(),null,RuleType.INBOUND,saved.getId(),0,true,
                rule.getWorkerPool().getId(),null,loading);
        receiverWorker.getBean(InboundSourceRegistry.class).reconcile();
        return saved;
    }
    private long loadingRuleId(ChannelSettings source) {
        return app.getBean(RuleService.class).findBySourceChannel(source).stream()
                .filter(rule -> rule.getRuleType() == RuleType.INBOUND).findFirst().orElseThrow().getId();
    }
    private void disableLoading(ChannelSettings source) {
        app.getBean(RuleService.class).toggleEnabled(loadingRuleId(source));
        receiverWorker.getBean(InboundSourceRegistry.class).reconcile();
    }
    private void rejectStorageFor(String sourceName) {
        jdbc().execute("""
                CREATE OR REPLACE FUNCTION reject_test_message() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.source_system = '%s' THEN RAISE EXCEPTION 'test storage outage'; END IF; RETURN NEW; END $$
                """.formatted(sourceName));
        jdbc().execute("CREATE TRIGGER reject_test_message BEFORE INSERT ON message FOR EACH ROW EXECUTE FUNCTION reject_test_message()");
    }
    private void waitForStorageFailure(ChannelSettings source) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(app.getBean(SourceHealth.class).get(loadingRuleId(source)).status()).isEqualTo("RETRYING");
            assertThat(messageCount(source.getName())).isZero();
            assertThat(jdbc().queryForObject("SELECT count(*) FROM message_receipt_outbox o JOIN message m ON m.message_id=o.message_id WHERE m.source_system=?",
                    Long.class, source.getName())).isZero();
        });
    }
    private static long messageCount(String source) {
        return jdbc().queryForObject("SELECT count(*) FROM message WHERE source_system=?", Long.class, source);
    }
    private JsonNode receiptAndApi(String sourceName, byte[] expected, String metadataKey, String metadataValue) throws Exception {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(messageCount(sourceName)).isEqualTo(1));
        long id = jdbc().queryForObject("SELECT message_id FROM message WHERE source_system=?", Long.class, sourceName);
        AtomicReference<org.springframework.amqp.core.Message> notification = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            var candidate = rabbit().receive("m3.message.received", 100);
            if (candidate != null && json().readTree(candidate.getBody()).path("messageId").asLong() == id) {
                notification.set(candidate); return true;
            }
            return false;
        });
        var event = json().readTree(notification.get().getBody());
        assertThat(event.path("eventType").asText()).isEqualTo("message.received");
        assertThat(event.path("resourcePath").asText()).isEqualTo("/api/v1/messages/" + id);
        var response = get("/api/v1/messages/" + id);
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json().readTree(response.body());
        assertThat(Base64.getDecoder().decode(body.path("payload").asText())).isEqualTo(expected);
        assertThat(body.path("sourceSystem").asText()).isEqualTo(sourceName);
        assertThat(body.path("metadata").findValuesAsText("key")).contains("encoding", metadataKey);
        assertThat(body.path("metadata").findValuesAsText("value")).contains("base64", metadataValue);
        return event;
    }
    private static HttpResponse<String> get(String path) throws Exception {
        path=path+(path.contains("?")?"&":"?")+"original=true";
        return HttpClient.newHttpClient().send(authorizedRequest(api(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static URI api(String path) { return URI.create("http://localhost:" + app.getEnvironment().getProperty("local.server.port") + path); }
    private static byte[] binary() { return new byte[]{0, 1, 2, -1, -128, 65, 66}; }
    private Path file(Path root) throws Exception {
        Files.createDirectories(root);
        Path file = Files.write(root.resolve("payload.bin"), binary());
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 5000));
        return file;
    }

    @Test @Order(1)
    void directoryRetriesDatabaseFailureAndDeduplicatesAfterApplicationRestart() throws Exception {
        Path input = file(temp.resolve("directory"));
        rejectStorageFor("retry-directory");
        var source = source("retry-directory", ChannelType.DIRECTORY, Map.of("directoryPath", input.getParent().toString(),
                "pollingInterval", "100", "minFileAgeMs", "1", "deleteAfterProcessing", "false"));
        waitForStorageFailure(source);
        assertThat(input).exists();
        jdbc().execute("DROP TRIGGER reject_test_message ON message");
        receiptAndApi(source.getName(), binary(), "fileName", "payload.bin");
        app.close(); app = start();
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(messageCount(source.getName())).isEqualTo(1));
        assertThat(jdbc().queryForObject("SELECT count(*) FROM source_delivery_receipt WHERE channel_id=?", Long.class, source.getId())).isEqualTo(1);
        var service = app.getBean(RuleService.class);
        var rule = service.findById(loadingRuleId(source));
        var properties = new HashMap<>(rule.getLoadingProperties()); properties.put("deleteAfterProcessing", "true");
        service.saveConfiguration(rule.getId(),rule.getName(),null,RuleType.INBOUND,source.getId(),0,true,
                rule.getWorkerPool().getId(),null,properties);
        await().atMost(Duration.ofSeconds(10)).until(() -> !Files.exists(input));
        assertThat(messageCount(source.getName())).isEqualTo(1);
    }

    @Test @Order(2)
    void ftpReceivesBinaryAndDeletesOnlyAfterSuccessfulDatabaseCommit() throws Exception {
        Path input = file(temp.resolve("ftp"));
        var factory = new FtpServerFactory();
        var listener = new ListenerFactory();
        int port; try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        listener.setPort(port); listener.setServerAddress("127.0.0.1");
        factory.addListener("default", listener.createListener());
        var user = new BaseUser(); user.setName("m3"); user.setPassword("m3");
        user.setHomeDirectory(input.getParent().toString()); user.setAuthorities(List.of(new WritePermission()));
        factory.getUserManager().save(user);
        var server = factory.createServer(); server.start();
        try {
            rejectStorageFor("retry-ftp");
            var source = source("retry-ftp", ChannelType.FTP, Map.of("host", "127.0.0.1", "port", Integer.toString(port),
                    "username", "m3", "password", "m3", "remoteDirectory", "/", "pollingInterval", "100",
                    "minFileAgeMs", "1", "deleteRemoteFiles", "true"));
            waitForStorageFailure(source); assertThat(input).exists();
            jdbc().execute("DROP TRIGGER reject_test_message ON message");
            receiptAndApi(source.getName(), binary(), "fileName", "payload.bin");
            await().atMost(Duration.ofSeconds(10)).until(() -> !Files.exists(input));
            disableLoading(source);
            long id = submitBinaryAndDeliver("ftp-output", ChannelType.FTP, source.getProperties());
            assertThat(Files.readAllBytes(input.getParent().resolve("message-" + id + "-payload.bin"))).isEqualTo(binary());
        } finally { server.stop(); }
    }

    @Test @Order(3)
    void sftpValidatesKnownHostAndReceivesAndDeletesBinary() throws Exception {
        Path input = file(temp.resolve("sftp"));
        var server = SshServer.setUpDefaultServer(); server.setHost("127.0.0.1"); server.setPort(0);
        var keys = new SimpleGeneratorHostKeyProvider(temp.resolve("host-key")); server.setKeyPairProvider(keys);
        server.setPasswordAuthenticator((user, password, session) -> user.equals("m3") && password.equals("m3"));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(input.getParent())); server.start();
        try {
            var publicKey = keys.loadKeys(null).iterator().next().getPublic();
            Path knownHosts = Files.writeString(temp.resolve("known_hosts"), "[127.0.0.1]:" + server.getPort() + " " + PublicKeyEntry.toString(publicKey) + "\n");
            var source = source("inbound-sftp", ChannelType.SFTP, Map.of("host", "127.0.0.1", "port", Integer.toString(server.getPort()),
                    "username", "m3", "password", "m3", "remoteDirectory", "/", "pollingInterval", "100",
                    "minFileAgeMs", "1", "deleteRemoteFiles", "true", "knownHostsPath", knownHosts.toString()));
            receiptAndApi(source.getName(), binary(), "fileName", "payload.bin");
            await().atMost(Duration.ofSeconds(10)).until(() -> !Files.exists(input));
            disableLoading(source);
            long id = submitBinaryAndDeliver("sftp-output", ChannelType.SFTP, source.getProperties());
            assertThat(Files.readAllBytes(input.getParent().resolve("message-" + id + "-payload.bin"))).isEqualTo(binary());
        } finally { server.stop(true); }
    }

    @Test @Order(4)
    void kafkaRetriesWithoutCommittingFailedRecord() throws Exception {
        String topic = "ingestion-" + UUID.randomUUID();
        try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
        rejectStorageFor("retry-kafka");
        var source = source("retry-kafka", ChannelType.KAFKA, Map.of("bootstrapServers", KAFKA.getBootstrapServers(), "topic", topic,
                "groupId", "test-" + UUID.randomUUID(), "autoOffsetReset", "earliest"));
        try (var producer = new KafkaProducer<String, byte[]>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class, ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            producer.send(new ProducerRecord<>(topic, "order-1", binary())).get(20, TimeUnit.SECONDS);
        }
        waitForStorageFailure(source);
        jdbc().execute("DROP TRIGGER reject_test_message ON message");
        receiptAndApi(source.getName(), binary(), "kafkaKey", Base64.getEncoder().encodeToString("order-1".getBytes(StandardCharsets.UTF_8)));
        disableLoading(source); disableLoading(source);
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(messageCount(source.getName())).isEqualTo(1));
        disableLoading(source);
        long id = submitBinaryAndDeliver("kafka-output", ChannelType.KAFKA, source.getProperties());
        try (var consumer = new KafkaConsumer<String, byte[]>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "verify-output-" + UUID.randomUUID(), ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class, ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    var header = record.headers().lastHeader("m3MessageId");
                    if (header != null && new String(header.value(), StandardCharsets.UTF_8).equals(Long.toString(id))) {
                        assertThat(record.value()).isEqualTo(binary());
                        assertThat(new String(record.headers().lastHeader("traceId").value(), StandardCharsets.UTF_8)).isEqualTo("outbound-42");
                        return true;
                    }
                }
                return false;
            });
        }
    }

    @Test @Order(5)
    void rabbitRequeuesFailedStorageAndDeduplicatesMessageId() throws Exception {
        String queue = "ingestion-" + UUID.randomUUID();
        rejectStorageFor("retry-rabbit");
        var source = source("retry-rabbit", ChannelType.RABBITMQ, Map.of("host", RABBIT.getHost(), "port", RABBIT.getMappedPort(5672).toString(),
                "username", "m3", "password", "m3", "queue", queue, "declareQueue", "true"));
        var properties = new MessageProperties(); properties.setMessageId("order-1"); properties.setContentType("application/octet-stream");
        var message = new org.springframework.amqp.core.Message(binary(), properties);
        rabbit().send("", queue, message);
        waitForStorageFailure(source);
        jdbc().execute("DROP TRIGGER reject_test_message ON message");
        receiptAndApi(source.getName(), binary(), "amqpMessageId", "order-1");
        rabbit().send("", queue, message);
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(messageCount(source.getName())).isEqualTo(1));
        disableLoading(source);
        var propertiesOut = new HashMap<>(source.getProperties()); propertiesOut.put("queue", queue + "-output");
        long id = submitBinaryAndDeliver("rabbit-output", ChannelType.RABBITMQ, propertiesOut);
        var outgoing = rabbit().receive(queue + "-output", 10000);
        assertThat(outgoing).isNotNull(); assertThat(outgoing.getBody()).isEqualTo(binary());
        assertThat(outgoing.getMessageProperties().getMessageId()).isEqualTo("m3:" + id);
        assertThat(outgoing.getMessageProperties().getHeaders()).containsEntry("traceId", "outbound-42");
    }

    @Test @Order(6)
    void httpRemainsAvailableDuringBrokerOutageAndOutboxSurvivesApplicationRestart() throws Exception {
        var source = source("outbox-restart", ChannelType.DIRECTORY, Map.of("directoryPath", temp.resolve("empty").toString(), "pollingInterval", "100"));
        assertThat(RABBIT.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
        try {
            String request = json().writeValueAsString(Map.of("ruleId",loadingRuleId(source),"payload","saved while broker offline","payloadType","text/plain","metadata",Map.of("externalId","restart-1")));
            var response = HttpClient.newHttpClient().send(authorizedRequest(api("/api/v1/channels/" + source.getId() + "/messages"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(request)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(201);
            long id = json().readTree(response.body()).path("id").asLong();
            assertThat(get("/api/v1/messages/" + id).statusCode()).isEqualTo(200);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc().queryForObject(
                    "SELECT attempts FROM message_receipt_outbox WHERE message_id=?", Integer.class, id)).isPositive());
            String eventId = jdbc().queryForObject("SELECT event_id::text FROM message_receipt_outbox WHERE message_id=?", String.class, id);
            app.close(); app = start();
            assertThat(RABBIT.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(jdbc().queryForObject(
                    "SELECT count(*) FROM message_receipt_outbox WHERE message_id=? AND published_at IS NOT NULL", Long.class, id)).isEqualTo(1));
            var notification = rabbit().receive("m3.message.received", 10000);
            assertThat(notification).isNotNull();
            assertThat(json().readTree(notification.getBody()).path("eventId").asText()).isEqualTo(eventId);
            var stored = json().readTree(get("/api/v1/messages/" + id).body());
            assertThat(stored.path("payload").asText()).isEqualTo("saved while broker offline");
            assertThat(stored.path("metadata").findValuesAsText("value")).contains("restart-1");
            assertThat(get("/api/v1/messages/9223372036854775807").statusCode()).isEqualTo(404);
        } finally {
            RABBIT.execInContainer("rabbitmqctl", "start_app");
        }
    }

    @Test @Order(7)
    void concurrentHttpRetriesCreateOneMessageAndOneReceipt() throws Exception {
        var source = source("http-idempotency", ChannelType.DIRECTORY,
                Map.of("directoryPath", temp.resolve("http").toString(), "pollingInterval", "100"));
        var request = authorizedRequest(api("/api/v1/channels/" + source.getId() + "/messages"))
                .header("Content-Type", "application/json").header("Idempotency-Key", "order-42")
                .POST(HttpRequest.BodyPublishers.ofString(json().writeValueAsString(Map.of("ruleId",loadingRuleId(source),"payload","same order","payloadType","text/plain","metadata",Map.of("externalId","order-42")))))
                .build();
        var client = HttpClient.newHttpClient();
        var requests = new ArrayList<CompletableFuture<HttpResponse<String>>>();
        for (int i = 0; i < 10; i++) requests.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
        Set<Long> ids = new HashSet<>();
        for (var future : requests) {
            assertThat(future.get().statusCode()).isEqualTo(201);
            ids.add(json().readTree(future.get().body()).path("id").asLong());
        }
        assertThat(ids).hasSize(1);
        assertThat(messageCount(source.getName())).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM message_receipt_outbox WHERE message_id=?", Long.class, ids.iterator().next())).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM source_delivery_receipt WHERE channel_id=?", Long.class, source.getId())).isEqualTo(1);
    }

    @Test @Order(8)
    void failureWritingOutboxRollsBackMessageAndMetadataAndSourceReceipt() throws Exception {
        Path input = file(temp.resolve("atomic"));
        jdbc().execute("""
                CREATE OR REPLACE FUNCTION reject_test_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test outbox storage outage'; END $$
                """);
        jdbc().execute("CREATE TRIGGER reject_test_outbox BEFORE INSERT ON message_receipt_outbox FOR EACH ROW EXECUTE FUNCTION reject_test_outbox()");
        var source = source("atomic-directory", ChannelType.DIRECTORY, Map.of("directoryPath", input.getParent().toString(),
                "pollingInterval", "100", "minFileAgeMs", "1", "deleteAfterProcessing", "true"));
        try {
            waitForStorageFailure(source);
            assertThat(input).exists();
            assertThat(jdbc().queryForObject("SELECT count(*) FROM source_delivery_receipt WHERE channel_id=?", Long.class, source.getId())).isZero();
        } finally { jdbc().execute("DROP TRIGGER reject_test_outbox ON message_receipt_outbox"); }
        receiptAndApi(source.getName(), binary(), "fileName", "payload.bin");
        await().atMost(Duration.ofSeconds(10)).until(() -> !Files.exists(input));
    }

    @Test @Order(9)
    void outboundUsesExplicitRuleAndKeepsOriginalWhileSendingTransformedPayload() throws Exception {
        Path directory = temp.resolve("outbound-transform");
        var target = channels().createChannel("transform-output", ChannelType.DIRECTORY, ChannelDirection.OUTBOUND, null, Map.of("directoryPath", directory.toString()));
        long rule = outboundRule(target);
        var service = app.getBean(RuleService.class);
        service.addCondition(rule, "header.approved", ConditionOperator.EQUALS, "yes", LogicalOperator.AND);
        var transformation = service.addAction(rule, ActionType.TRANSFORM); transformation.setTransformationScript("$.body");
        app.getBean(RuleActionRepository.class).save(transformation);
        String original = "{\"body\":\"transformed delivery\"}";
        var response = submit(rule, original, "application/json", Map.of("approved", "yes", "fileName", "result.txt"), "transform-1");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        long id = json().readTree(response.body()).path("id").asLong();
        try (var worker = worker("default")) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc().queryForObject("SELECT status FROM message WHERE message_id=?", String.class, id)).isEqualTo("SENT"));
        }
        assertThat(Files.readString(directory.resolve("message-" + id + "-result.txt"))).isEqualTo("transformed delivery");
        assertThat(json().readTree(get("/api/v1/messages/" + id).body()).path("payload").asText()).isEqualTo(original);
        var repeated = submit(rule, original, "application/json", Map.of("approved", "yes", "fileName", "result.txt"), "transform-1");
        assertThat(json().readTree(repeated.body()).path("id").asLong()).isEqualTo(id);
        assertThat(submit(rule, "{\"body\":\"different\"}", "application/json", Map.of(), "transform-1").statusCode()).isEqualTo(409);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM rule_execution_job WHERE message_id=?", Long.class, id)).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM message_receipt_outbox WHERE message_id=?", Long.class, id)).isZero();
    }

    @Test @Order(10)
    void outboundRestartsWorkerAndRetriesBrokerFailureUsingPersistedPlan() throws Exception {
        receiverWorker.close(); receiverWorker = null;
        try {
            String queue = "outgoing-retry-" + UUID.randomUUID();
            var target = channels().createChannel("retry-output", ChannelType.RABBITMQ, ChannelDirection.OUTBOUND, null,
                    Map.of("host", RABBIT.getHost(), "port", RABBIT.getMappedPort(5672).toString(), "username", "m3", "password", "m3", "queue", queue, "declareQueue", "true"));
            long rule = outboundRule(target);
            var response = submit(rule, "durable outbound", "text/plain", Map.of("traceId", "retry-42"), "retry-outgoing");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
            long id = json().readTree(response.body()).path("id").asLong();
            assertThat(RABBIT.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
            try {
                try (var worker = worker("default")) {
                    // Broker shutdown can also fail RabbitMQ's background confirm cleanup.
                    // Verify the durable job state rather than unrelated client cleanup threads.
                    await().dontCatchUncaughtExceptions().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                        assertThat(jdbc().queryForObject("SELECT attempts FROM rule_execution_job WHERE message_id=?", Integer.class, id)).isPositive();
                        assertThat(jdbc().queryForObject("SELECT status FROM rule_execution_job WHERE message_id=?", String.class, id)).isEqualTo("PENDING");
                        assertThat(jdbc().queryForObject("SELECT result FROM rule_execution_job WHERE message_id=?", String.class, id)).contains("channelId");
                    });
                }
            } finally { RABBIT.execInContainer("rabbitmqctl", "start_app"); }
            try (var worker = worker("default")) {
                await().dontCatchUncaughtExceptions().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc().queryForObject("SELECT status FROM message WHERE message_id=?", String.class, id)).isEqualTo("SENT"));
            }
            var outgoing = rabbit().receive(queue, 10000);
            assertThat(new String(outgoing.getBody(), StandardCharsets.UTF_8)).isEqualTo("durable outbound");
            assertThat(outgoing.getMessageProperties().getHeaders()).containsEntry("traceId", "retry-42");
        } finally { receiverWorker = worker("default"); }
    }

    @Test @Order(11)
    void outboundRejectedRulesAndNonmatchingConditionsDoNotSend() throws Exception {
        Path directory = temp.resolve("filtered");
        var target = channels().createChannel("filtered-output", ChannelType.DIRECTORY, ChannelDirection.OUTBOUND, null, Map.of("directoryPath", directory.toString()));
        long rule = outboundRule(target);
        var service = app.getBean(RuleService.class);
        service.toggleEnabled(rule);
        assertThat(submit(rule, "blocked", "text/plain", Map.of(), null).statusCode()).isEqualTo(409);
        assertThat(submit(Long.MAX_VALUE, "blocked", "text/plain", Map.of(), null).statusCode()).isEqualTo(404);
        service.toggleEnabled(rule);
        var condition = service.addCondition(rule, "header.approved", ConditionOperator.EQUALS, "yes", LogicalOperator.AND);
        var response = submit(rule, "blocked", "text/plain", Map.of(), null);
        long id = json().readTree(response.body()).path("id").asLong();
        try (var worker = worker("default")) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc().queryForObject("SELECT status FROM message WHERE message_id=?", String.class, id)).isEqualTo("FAILED"));
        }
        assertThat(directory).doesNotExist();
        service.deleteCondition(condition.getId());
        assertThat(jdbc().queryForObject("SELECT count(*) FROM rule_condition WHERE condition_id=?",Long.class,condition.getId())).isZero();
        var retry = HttpClient.newHttpClient().send(authorizedRequest(api("/api/v1/messages/" + id + "/retry"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(retry.statusCode()).isEqualTo(202);
        try (var worker = worker("default")) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc().queryForObject("SELECT status FROM message WHERE message_id=?", String.class, id)).isEqualTo("FAILED"));
            assertThat(directory).doesNotExist(); // Retry preserves the originally accepted condition.
            var fresh=submit(rule,"blocked","text/plain",Map.of(),null);
            long freshId=json().readTree(fresh.body()).path("id").asLong();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc().queryForObject("SELECT status FROM message WHERE message_id=?", String.class, freshId)).as(jdbc().queryForObject("SELECT error_message FROM rule_execution_job WHERE message_id=?",String.class,freshId)).isEqualTo("SENT"));
            assertThat(Files.readString(directory.resolve("message-" + freshId + "-payload.dat"))).isEqualTo("blocked");
        }
    }

    @Test @Order(12)
    void directoryLoadingRuleRoutesFileAndRetainsOriginalBytes() throws Exception {
        Path inputDirectory = Files.createDirectories(temp.resolve("route-in"));
        Path outputDirectory = temp.resolve("route-out");
        var target = channels().createChannel("directory-route-output", ChannelType.DIRECTORY, ChannelDirection.OUTBOUND,
                null, Map.of("directoryPath", outputDirectory.toString()));
        var source = source("directory-route-input", ChannelType.DIRECTORY, Map.of("directoryPath", inputDirectory.toString()));
        var service = app.getBean(RuleService.class);
        var rule = service.findById(loadingRuleId(source));
        service.saveConfiguration(rule.getId(), rule.getName(), null, RuleType.INBOUND, source.getId(), 0, true,
                rule.getWorkerPool().getId(), target.getId(), Map.of("pollingInterval", "100", "minFileAgeMs", "1",
                        "deleteAfterProcessing", "true", "filePattern", "*.bin"));
        receiverWorker.getBean(InboundSourceRegistry.class).reconcile();
        Path input = file(inputDirectory);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(jdbc().queryForObject("SELECT count(*) FROM message WHERE source_system=? AND direction='OUTBOUND' AND status='SENT'",
                    Long.class, source.getName())).isEqualTo(1);
        });
        long original = jdbc().queryForObject("SELECT message_id FROM message WHERE source_system=? AND direction='INBOUND'", Long.class, source.getName());
        long copy = jdbc().queryForObject("SELECT message_id FROM message WHERE source_system=? AND direction='OUTBOUND'", Long.class, source.getName());
        assertThat(input).doesNotExist();
        assertThat(Files.readAllBytes(outputDirectory.resolve("message-" + copy + "-payload.bin"))).isEqualTo(binary());
        assertThat(jdbc().queryForObject("SELECT payload_bytes FROM message WHERE message_id=?", byte[].class, original)).isEqualTo(binary());
        assertThat(jdbc().queryForObject("SELECT status FROM message WHERE message_id=?", String.class, original)).isEqualTo("LOADED");
        assertThat(jdbc().queryForObject("SELECT value FROM message_metadata WHERE message_id=? AND key='sourceMessageId'", String.class, copy))
                .isEqualTo(Long.toString(original));
        assertThat(jdbc().queryForObject("SELECT count(*) FROM rule_execution_job WHERE message_id=? AND status='COMPLETED'", Long.class, copy)).isEqualTo(1);
    }

    @Test @Order(13)
    void forwardingStoredFileCreatesIndependentDeliveryAndDeduplicatesRequests() throws Exception {
        Path inputDirectory = Files.createDirectories(temp.resolve("forward-in"));
        var source = source("forward-file-input", ChannelType.DIRECTORY, Map.of("directoryPath", inputDirectory.toString(), "charset", "windows-1251"));
        file(inputDirectory);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(messageCount(source.getName())).isEqualTo(1));
        long original = jdbc().queryForObject("SELECT message_id FROM message WHERE source_system=?", Long.class, source.getName());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc().queryForObject("SELECT status FROM message WHERE message_id=?",
                String.class, original)).isEqualTo("LOADED"));
        Path outputDirectory = temp.resolve("forward-out");
        var target = channels().createChannel("forward-file-output", ChannelType.DIRECTORY, ChannelDirection.OUTBOUND, null,
                Map.of("directoryPath", outputDirectory.toString()));
        long rule = outboundRule(target);
        var before = get("/api/v1/messages/" + original).body();
        var request = authorizedRequest(api("/api/v1/messages/" + original + "/forward"))
                .header("Content-Type", "application/json").header("Idempotency-Key", "forward-file-test")
                .POST(HttpRequest.BodyPublishers.ofString("{\"ruleId\":" + rule + "}")).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        long copy = json().readTree(response.body()).path("id").asLong();
        var repeated = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(repeated.statusCode()).as(repeated.body()).isEqualTo(202);
        assertThat(json().readTree(repeated.body()).path("id").asLong()).isEqualTo(copy);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc().queryForObject("SELECT status FROM message WHERE message_id=?", String.class, copy)).isEqualTo("SENT"));
        assertThat(Files.readAllBytes(outputDirectory.resolve("message-" + copy + "-payload.bin"))).isEqualTo(binary());
        assertThat(get("/api/v1/messages/" + original).body()).isEqualTo(before);
        assertThat(json().readTree(get("/api/v1/messages/" + copy).body()).path("charset").asText()).isEqualTo("windows-1251");
        assertThat(jdbc().queryForObject("SELECT count(*) FROM rule_execution_job WHERE message_id=?", Long.class, copy)).isEqualTo(1);
    }
    @Test @Order(15)
    void masksApiAndDownloadsRequiresOriginalPermissionAndAuditsReads() throws Exception {
        var target=channels().createChannel("privacy-output",ChannelType.DIRECTORY,ChannelDirection.OUTBOUND,null,Map.of("directoryPath",temp.resolve("privacy").toString()));
        String payload="{\"password\":\"body-private-secret\",\"name\":\"Alice\"}";
        var submitted=submit(outboundRule(target),payload,"application/json",Map.of("token","metadata-private-secret"),"privacy-test");
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        assertThat(submitted.body()).doesNotContain("body-private-secret","metadata-private-secret");
        long id=json().readTree(submitted.body()).path("id").asLong();var client=HttpClient.newHttpClient();
        String credentials=Base64.getEncoder().encodeToString("viewer:test-viewer-password-123456".getBytes(StandardCharsets.UTF_8));
        for(String suffix:List.of("","/payload","/text")) {
            var masked=client.send(HttpRequest.newBuilder(api("/api/v1/messages/"+id+suffix)).header("Authorization","Basic "+credentials).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(masked.statusCode()).as(masked.body()).isEqualTo(200);assertThat(masked.body()).doesNotContain("body-private-secret","metadata-private-secret");
            var forbidden=client.send(HttpRequest.newBuilder(api("/api/v1/messages/"+id+suffix+"?original=true")).header("Authorization","Basic "+credentials).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(forbidden.statusCode()).as(forbidden.body()).isEqualTo(403);
        }
        assertThat(json().readTree(get("/api/v1/messages/"+id).body()).path("payload").asText()).isEqualTo(payload);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM configuration_audit WHERE operation='authorize' AND entity_id=?",Long.class,id)).isPositive();
        assertThat(jdbc().queryForObject("SELECT convert_from(payload_bytes,'UTF8') FROM message WHERE message_id=?",String.class,id)).isEqualTo(payload);
    }
    private static HttpResponse<String> changeStatus(long id, String body, String username) throws Exception {
        var data=(com.fasterxml.jackson.databind.node.ObjectNode)json().readTree(body);
        var attempts=jdbc().queryForList("SELECT attempt_id,version FROM message_processing WHERE message_id=? AND recipient='external' AND is_current",id);
        data.put("callbackId",UUID.randomUUID().toString());data.put("processingAttemptId",attempts.isEmpty()?UUID.randomUUID().toString():attempts.getFirst().get("attempt_id").toString());
        data.put("expectedVersion",attempts.isEmpty()?0:((Number)attempts.getFirst().get("version")).longValue());body=json().writeValueAsString(data);
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(api("/api/v1/messages/" + id + "/status"))
                .header("X-M3-Request", "1").header("Content-Type", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((username + ":test-" + username + "-password-123456").getBytes(StandardCharsets.UTF_8)))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test @Order(16)
    void externalServiceOwnsProcessingStatusAndRoutingPreservesCallbacks() throws Exception {
        var source = source("external-status-input", ChannelType.DIRECTORY,
                Map.of("directoryPath", Files.createDirectories(temp.resolve("status-in")).toString()));
        receiverWorker.close();
        try {
            long ruleId = loadingRuleId(source);
            var sourceRule = app.getBean(RuleService.class).findById(ruleId);
            var saved = app.getBean(com.sysadminanywhere.m3.messaging.service.SourceDeliveryService.class).receive(
                    InboundSourceSpec.fromRule(sourceRule), "business payload", "text/plain", Map.of(), null);
            assertThat(jdbc().queryForObject("SELECT status FROM message WHERE message_id=?", String.class, saved)).isEqualTo("LOADED");
            assertThat(changeStatus(saved, "{\"status\":\"PROCESSED\"}", "viewer").statusCode()).isEqualTo(403);
            assertThat(changeStatus(saved, "{\"status\":\"SENT\"}", "operator").statusCode()).isEqualTo(400);
            assertThat(changeStatus(Long.MAX_VALUE, "{\"status\":\"PROCESSED\"}", "operator").statusCode()).isEqualTo(404);
            assertThat(changeStatus(saved, "{\"status\":\"PROCESSING\",\"expectedStatus\":\"LOADED\"}", "operator").statusCode()).isEqualTo(200);
            assertThat(changeStatus(saved, "{\"status\":\"PROCESSED\",\"expectedStatus\":\"LOADED\"}", "operator").statusCode()).isEqualTo(409);
            var callback = changeStatus(saved, "{\"status\":\"PROCESSING_FAILED\",\"expectedStatus\":\"PROCESSING\",\"detail\":\"Receiver rejected password=private\"}", "operator");
            assertThat(callback.statusCode()).as(callback.body()).isEqualTo(200);
            assertThat(json().readTree(callback.body()).path("processedAt").isNull()).isFalse();
            assertThat(changeStatus(saved, "{\"status\":\"PROCESSING_FAILED\"}", "operator").body()).isEqualTo(callback.body());
            assertThat(jdbc().queryForObject("SELECT count(*) FROM message_event WHERE message_id=? AND kind='EXTERNAL_STATUS_CHANGED'", Long.class, saved)).isEqualTo(2);
            assertThat(jdbc().queryForObject("SELECT count(*) FROM configuration_audit WHERE operation='callback' AND entity_id=?", Long.class, saved)).isPositive();
            assertThat(HttpClient.newHttpClient().send(authorizedRequest(api("/api/v1/messages/" + saved + "/history")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()).doesNotContain("password=private");
            try (var processing = worker("default")) {
                await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc().queryForObject("SELECT status FROM rule_execution_job WHERE message_id=?", String.class, saved)).isEqualTo("COMPLETED"));
            }
            assertThat(jdbc().queryForObject("SELECT status FROM message WHERE message_id=?", String.class, saved)).isEqualTo("PROCESSING_FAILED");
            assertThat(changeStatus(saved, "{\"status\":\"PROCESSED\",\"expectedStatus\":\"PROCESSING_FAILED\"}", "operator").statusCode()).isEqualTo(409);
            assertThat(changeStatus(saved, "{\"status\":\"LOADED\"}", "operator").statusCode()).isEqualTo(409);
            assertThat(jdbc().queryForObject("SELECT processed_at FROM message WHERE message_id=?", java.sql.Timestamp.class, saved)).isNotNull();
            var configured = app.getBean(RuleService.class).saveConfiguration(ruleId, sourceRule.getName(), null, RuleType.INBOUND,
                    source.getId(), 0, true, sourceRule.getWorkerPool().getId(), null, Map.of("initialStatus", "PROCESSING"));
            long configuredMessage = app.getBean(com.sysadminanywhere.m3.messaging.service.SourceDeliveryService.class).receive(
                    InboundSourceSpec.fromRule(configured), "another payload", "text/plain", Map.of(), null);
            assertThat(jdbc().queryForObject("SELECT status FROM message WHERE message_id=?", String.class, configuredMessage)).isEqualTo("PROCESSING");
            app.getBean(RuleService.class).addCondition(ruleId, "payload", ConditionOperator.CONTAINS, "ok", LogicalOperator.AND);
            long broken = app.getBean(com.sysadminanywhere.m3.messaging.service.SourceDeliveryService.class).receive(
                    InboundSourceSpec.fromRule(app.getBean(RuleService.class).findById(ruleId)), "invalid JSON", "application/json", Map.of(), null);
            try (var processing = worker("default")) {
                await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc().queryForObject("SELECT status FROM rule_execution_job WHERE message_id=?", String.class, broken)).isEqualTo("FAILED"));
            }
            assertThat(jdbc().queryForObject("SELECT status FROM message WHERE message_id=?", String.class, broken)).isEqualTo("PROCESSING");
            assertThat(HttpClient.newHttpClient().send(authorizedRequest(api("/api/v1/messages/" + broken + "/retry"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(202);
            assertThat(jdbc().queryForObject("SELECT status FROM message WHERE message_id=?", String.class, broken)).isEqualTo("PROCESSING");
            assertThat(jdbc().queryForObject("SELECT status FROM rule_execution_job WHERE message_id=?", String.class, broken)).isEqualTo("PENDING");
            var outbound = channels().createChannel("external-status-output", ChannelType.DIRECTORY, ChannelDirection.OUTBOUND, null, Map.of("directoryPath", temp.resolve("status-out").toString()));
            long outboundId = json().readTree(submit(outboundRule(outbound), "data", "text/plain", Map.of(), null).body()).path("id").asLong();
            assertThat(changeStatus(outboundId, "{\"status\":\"PROCESSED\"}", "operator").statusCode()).isEqualTo(409);
        } finally { receiverWorker = worker("default"); }
    }

    @Test @Order(17)
    void machineCredentialsAreScopedAtTheHttpBoundary()throws Exception {
        var source=source("machine-account-input",ChannelType.DIRECTORY,Map.of("directoryPath",Files.createDirectories(temp.resolve("machine-in")).toString()));
        var rule=app.getBean(RuleService.class).findById(loadingRuleId(source));
        rule=app.getBean(RuleService.class).saveConfiguration(rule.getId(),rule.getName(),null,RuleType.INBOUND,source.getId(),0,true,rule.getWorkerPool().getId(),null,Map.of("processingRecipients","erp,billing?"));
        long id=app.getBean(com.sysadminanywhere.m3.messaging.service.SourceDeliveryService.class).receive(InboundSourceSpec.fromRule(rule),"{\"password\":\"machine-secret\"}","application/json",Map.of(),null);
        var account=app.getBean(com.sysadminanywhere.m3.base.security.MachineAccounts.class).getServices().get("erp");account.setChannels(Set.of(source.getId()));
        var client=HttpClient.newHttpClient();String authorization="Basic "+Base64.getEncoder().encodeToString("erp-service:test-erp-password-123456".getBytes(StandardCharsets.UTF_8));
        java.util.function.Function<String,HttpRequest.Builder> request=path->HttpRequest.newBuilder(api(path)).header("Authorization",authorization).header("X-M3-Request","1");
        var masked=client.send(request.apply("/api/v1/messages/"+id).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(masked.statusCode()).isEqualTo(200);assertThat(masked.body()).doesNotContain("machine-secret");
        var original=client.send(request.apply("/api/v1/messages/"+id+"/payload?original=true").GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(original.statusCode()).isEqualTo(200);assertThat(original.body()).contains("machine-secret");
        var list=client.send(request.apply("/api/v1/messages/"+id+"/processing").GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(list.statusCode()).isEqualTo(200);assertThat(json().readTree(list.body())).hasSize(1);
        var attempt=json().readTree(list.body()).get(0);assertThat(attempt.path("recipient").asText()).isEqualTo("erp");
        var body=json().writeValueAsString(Map.of("callbackId",UUID.randomUUID(),"processingAttemptId",attempt.path("attemptId").asText(),"expectedVersion",attempt.path("version").asLong(),"status","PROCESSED"));
        var forbidden=client.send(request.apply("/api/v1/messages/"+id+"/processing/billing/status").header("Content-Type","application/json").method("PATCH",HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());assertThat(forbidden.statusCode()).isEqualTo(403);
        var accepted=client.send(request.apply("/api/v1/messages/"+id+"/processing/erp/status").header("Content-Type","application/json").method("PATCH",HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(client.send(request.apply("/api/v1/operations").GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(client.send(request.apply("/api/v1/messages/"+id+"/retry").POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        account.setChannels(Set.of(Long.MAX_VALUE));assertThat(client.send(request.apply("/api/v1/messages/"+id+"/payload?original=true").GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }

    @Test @Order(14)
    void requiresAuthenticationRolesAndExplicitMutationHeaderAndEncryptsStoredConfiguration() throws Exception {
        var client=HttpClient.newHttpClient();
        assertThat(client.send(HttpRequest.newBuilder(api("/api/v1/messages/1")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        var viewer=HttpRequest.newBuilder(api("/api/v1/messages/1/retry")).header("X-M3-Request","1")
                .header("Authorization","Basic "+Base64.getEncoder().encodeToString("viewer:test-viewer-password-123456".getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(client.send(viewer,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(client.send(HttpRequest.newBuilder(api("/api/v1/messages/1/retry")).header("Authorization","Basic "+Base64.getEncoder().encodeToString("admin:test-admin-password-123456".getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        var channel=channels().createChannel("encrypted-properties",ChannelType.RABBITMQ,ChannelDirection.OUTBOUND,null,Map.of("host",RABBIT.getHost(),"port",RABBIT.getMappedPort(5672).toString(),"exchange","m3.events","routingKey","test","username","m3","password","private-test-value"));
        assertThat(jdbc().queryForObject("SELECT property_value FROM channel_properties WHERE channel_id=? AND property_key='password'",String.class,channel.getId())).startsWith("m3enc:v1:").doesNotContain("private-test-value");
        assertThat(channels().findById(channel.getId()).orElseThrow().getProperties().get("password")).isEqualTo("private-test-value");
        assertThat(jdbc().queryForObject("SELECT count(*) FROM rule_execution_job WHERE configuration_snapshot IS NOT NULL AND configuration_snapshot NOT LIKE 'm3enc:v1:%'",Long.class)).isZero();
        assertThat(jdbc().queryForObject("SELECT count(*) FROM configuration_audit",Long.class)).isPositive();
        var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("operator","unused",List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OPERATOR")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(()->channels().toggleEnabled(channel.getId())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
}
