package com.monitoring.coreapi.support;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Один контейнер Postgres на весь прогон тестов (singleton container). Схема создаётся
 * ТЕМИ ЖЕ миграциями Liquibase, что и в приложении, прямо из classpath сервиса.
 * Версия Testcontainers (1.21.4) зафиксирована в корневом pom.xml: более старые версии
 * не работают с Docker 29 и новее.
 */
public final class PostgresTestDatabase {

    private static PostgreSQLContainer<?> container;

    private PostgresTestDatabase() {
    }

    public static synchronized void start() {
        if (container != null) {
            return;
        }
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();
        migrate(postgres);
        container = postgres;
    }

    public static String jdbcUrl() {
        return container.getJdbcUrl();
    }

    public static String username() {
        return container.getUsername();
    }

    public static String password() {
        return container.getPassword();
    }

    private static void migrate(PostgreSQLContainer<?> postgres) {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось применить миграции core-api к тестовой БД", e);
        }
    }
}
