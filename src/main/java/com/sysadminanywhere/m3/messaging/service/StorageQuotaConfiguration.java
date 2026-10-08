package com.sysadminanywhere.m3.messaging.service;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
@Component @Profile("!worker")
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class StorageQuotaConfiguration {
    public StorageQuotaConfiguration(JdbcTemplate jdbc,@Value("${m3.storage.max-hot-bytes:0}") long max,@Value("${m3.storage.max-database-bytes:0}")long databaseMax){if(max<0||databaseMax<0)throw new IllegalArgumentException("Storage quotas must be nonnegative");jdbc.update("UPDATE storage_usage SET max_hot_bytes=?,max_database_bytes=? WHERE singleton",max,databaseMax);}
}
