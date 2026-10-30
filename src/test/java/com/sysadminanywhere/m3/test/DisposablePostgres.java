package com.sysadminanywhere.m3.test;

import java.net.URI;
import java.sql.Connection;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Gives each integration test a new database, never modifying the URL's database. */
public class DisposablePostgres implements ParameterResolver, AfterEachCallback {
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(DisposablePostgres.class);

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == DriverManagerDataSource.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        var store = context.getStore(NAMESPACE);
        var existing = store.get("database", Database.class);
        if (existing != null) return existing.dataSource;
        String url = System.getenv("M3_TEST_POSTGRES_URL");
        if (url == null || !url.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("M3_TEST_POSTGRES_URL must be a PostgreSQL JDBC URL");
        }
        URI uri = URI.create(url.substring(5));
        if (uri.getRawAuthority() == null || uri.getRawPath() == null || uri.getRawPath().length() < 2 || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("M3_TEST_POSTGRES_URL must include a host and database");
        }
        String name = "m3_test_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new DriverManagerDataSource(url, "m3test", "m3test");
        try (Connection connection = admin.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name + " TEMPLATE template0");
        } catch (java.sql.SQLException e) {
            throw new ParameterResolutionException("Cannot create disposable PostgreSQL database; the m3test role needs CREATEDB", e);
        }
        String testUrl = "jdbc:postgresql://" + uri.getRawAuthority() + "/" + name
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        var database = new Database(admin, name, new DriverManagerDataSource(testUrl, "m3test", "m3test"));
        store.put("database", database);
        Flyway.configure().dataSource(database.dataSource).schemas("community").defaultSchema("community").load().migrate();
        return database.dataSource;
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        var database = context.getStore(NAMESPACE).remove("database", Database.class);
        if (database != null) {
            try (Connection connection = database.admin.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + database.name);
            }
        }
    }

    private record Database(DriverManagerDataSource admin, String name, DriverManagerDataSource dataSource) { }
}
