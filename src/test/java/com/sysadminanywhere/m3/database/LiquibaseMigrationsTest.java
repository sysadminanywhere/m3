package com.sysadminanywhere.m3.database;

import com.sysadminanywhere.m3.messaging.domain.*;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.hibernate.SessionFactory;
import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against an isolated PostgreSQL instance, never the developer's database. */
@Testcontainers(disabledWithoutDocker = true)
class LiquibaseMigrationsTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private String jdbcUrl;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        jdbcUrl = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    @Test
    void createsFreshSchemaCompatibleWithAllEntitiesAndDoesNotReapplyChanges() throws Exception {
        migrate();
        validateMappingsAndPersistPool();
        assertThat(number("SELECT count(*) FROM databasechangelog")).isEqualTo(11);
        assertThat(number("SELECT count(*) FROM rule_worker_pool WHERE name = 'default'")).isEqualTo(1);
        assertThat(number("SELECT increment_by FROM pg_sequences WHERE schemaname = current_schema() AND sequencename = 'task_seq'")).isEqualTo(50);
        assertThat(number("SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema() AND indexname LIKE 'idx_%'")).isEqualTo(11);
        execute("UPDATE rule_worker_pool SET desired_replicas = 3, max_replicas = 8 WHERE name = 'default'");
        insertExistingRuleAndMessage();

        migrate();

        assertThat(number("SELECT count(*) FROM databasechangelog")).isEqualTo(11);
        assertThat(number("SELECT desired_replicas FROM rule_worker_pool WHERE name = 'default'")).isEqualTo(3);
        assertThat(number("SELECT count(*) FROM message WHERE payload = 'preserve me'")).isEqualTo(1);
        assertThatThrownBy(() -> execute("INSERT INTO message(direction,status,payload,payload_type,created_at) VALUES('INBOUND','INVALID','x','text/plain',now())"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void adoptsHibernateSchemaPreservingDataIdentitiesCapacityAndForeignKeys() throws Exception {
        createHibernateSchema();
        execute("INSERT INTO rule_worker_pool(name,desired_replicas,min_replicas,max_replicas,created_at) VALUES('default',3,1,8,now())");
        execute("INSERT INTO task(task_id,description,creation_date) VALUES(nextval('task_seq'),'legacy task',now())");
        insertExistingRuleAndMessage();
        long foreignKeys = number("SELECT count(*) FROM pg_constraint WHERE contype='f' AND conrelid='rule'::regclass");

        migrate();

        validateMappingsAndPersistPool();
        assertThat(number("SELECT count(*) FROM message WHERE message_id = 1 AND payload = 'preserve me'")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM rule WHERE rule_id=1 AND worker_pool_id=(SELECT worker_pool_id FROM rule_worker_pool WHERE name='default')")).isEqualTo(1);
        assertThat(number("SELECT desired_replicas FROM rule_worker_pool WHERE name='default'")).isEqualTo(3);
        assertThat(number("SELECT count(*) FROM task WHERE task_id=1 AND description='legacy task'")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM pg_constraint WHERE contype='f' AND conrelid='rule'::regclass")).isEqualTo(foreignKeys);
        execute("INSERT INTO message(direction,status,payload,payload_bytes,charset,charset_source,payload_format,payload_type,created_at) "
                + "VALUES('OUTBOUND','PENDING','next',convert_to('next','UTF8'),'UTF-8','TEXT_UTF8','TEXT','text/plain',now())");
        assertThat(number("SELECT message_id FROM message WHERE payload='next'")).isEqualTo(2);
    }

    @Test
    void upgradesSchemaFromBeforeWorkerContainersAndAssignsExistingRules() throws Exception {
        createHibernateSchema();
        execute("DROP TABLE rule_execution_job; DROP TABLE worker_pool_metric_sample; ALTER TABLE rule DROP COLUMN worker_pool_id; DROP TABLE rule_worker_pool");
        insertExistingRuleAndMessage();

        migrate();

        validateMappingsAndPersistPool();
        assertThat(number("SELECT count(*) FROM rule WHERE worker_pool_id=(SELECT worker_pool_id FROM rule_worker_pool WHERE name='default')")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM message WHERE payload='preserve me'")).isEqualTo(1);
    }

    @Test
    void upgradesOlderPoolsAndJobsWithAutoscalingAndClaimLeaseColumns() throws Exception {
        createHibernateSchema();
        execute("INSERT INTO rule_worker_pool(name,desired_replicas,min_replicas,max_replicas,created_at) VALUES('default',2,1,4,now())");
        execute("ALTER TABLE rule_worker_pool DROP COLUMN auto_scale_enabled, DROP COLUMN pending_jobs_per_worker; ALTER TABLE rule_execution_job DROP COLUMN claimed_at, DROP COLUMN result");

        migrate();

        validateMappingsAndPersistPool();
        assertThat(number("SELECT desired_replicas FROM rule_worker_pool WHERE name='default'")).isEqualTo(2);
        assertThat(number("SELECT pending_jobs_per_worker FROM rule_worker_pool WHERE name='default'")).isEqualTo(50);
        assertThat(number("SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname='idx_rule_job_pool_claim'")).isEqualTo(1);
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void migrate() throws Exception {
        try (Connection connection = connection();
             Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.xml", new ClassLoaderResourceAccessor(),
                     DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.update(new Contexts(), new LabelExpression());
        }
    }

    private SessionFactory hibernate(String schemaAction) {
        return new Configuration()
                .addAnnotatedClass(ChannelSettings.class).addAnnotatedClass(Message.class)
                .addAnnotatedClass(MessageMetadata.class).addAnnotatedClass(Rule.class)
                .addAnnotatedClass(RuleCondition.class).addAnnotatedClass(RuleAction.class)
                .addAnnotatedClass(RuleWorkerPool.class).addAnnotatedClass(RuleExecutionJob.class)
                .addAnnotatedClass(WorkerPoolMetricSample.class)
                .setPhysicalNamingStrategy(new PhysicalNamingStrategySnakeCaseImpl())
                .setProperty("hibernate.connection.url", jdbcUrl)
                .setProperty("hibernate.connection.username", POSTGRES.getUsername())
                .setProperty("hibernate.connection.password", POSTGRES.getPassword())
                .setProperty("hibernate.hbm2ddl.auto", schemaAction)
                .buildSessionFactory();
    }

    private void createHibernateSchema() throws SQLException {
        try (SessionFactory ignored = hibernate("create")) { }
        // Model the text-only Hibernate schema that existed before the bytea migration.
        execute("ALTER TABLE message DROP COLUMN payload_bytes, DROP COLUMN charset, DROP COLUMN charset_source, DROP COLUMN payload_format; "
                + "ALTER TABLE message ADD COLUMN payload TEXT NOT NULL");
        // Reproduce the removed starter feature to check that upgrades preserve legacy data.
        execute("CREATE SEQUENCE task_seq START WITH 1 INCREMENT BY 50; "
                + "CREATE TABLE task(task_id BIGINT PRIMARY KEY, description VARCHAR(300) NOT NULL, "
                + "creation_date TIMESTAMP(6) WITH TIME ZONE NOT NULL, due_date DATE)");
    }

    private void validateMappingsAndPersistPool() throws SQLException {
        long previousMaxId = number("SELECT COALESCE(MAX(worker_pool_id), 0) FROM rule_worker_pool");
        try (SessionFactory factory = hibernate("validate"); var session = factory.openSession()) {
            session.beginTransaction();
            RuleWorkerPool pool = new RuleWorkerPool("identity-check", 1, 1, 4);
            session.persist(pool);
            session.getTransaction().commit();
            assertThat(pool.getId()).isGreaterThan(previousMaxId);
        }
    }

    private void insertExistingRuleAndMessage() throws SQLException {
        boolean bytesSchema = number("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='message' AND column_name='payload_bytes'") > 0;
        execute("INSERT INTO channel_settings(name,channel_type,direction,enabled,created_at) VALUES('legacy-channel','DIRECTORY','INBOUND',true,now()); "
                + "INSERT INTO rule(name,rule_type,source_channel_id,enabled,priority,created_at) VALUES('legacy-rule','INBOUND',1,true,0,now()); "
                + (bytesSchema ? "INSERT INTO message(direction,status,payload,payload_bytes,charset,charset_source,payload_format,payload_type,created_at) "
                    + "VALUES('INBOUND','PENDING','preserve me',convert_to('preserve me','UTF8'),'UTF-8','TEXT_UTF8','TEXT','text/plain',now())"
                    : "INSERT INTO message(direction,status,payload,payload_type,created_at) VALUES('INBOUND','PENDING','preserve me','text/plain',now())"));
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long number(String sql) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
