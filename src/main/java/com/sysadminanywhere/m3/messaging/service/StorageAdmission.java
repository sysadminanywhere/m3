package com.sysadminanywhere.m3.messaging.service;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@Service
public class StorageAdmission {
    private final JdbcTemplate jdbc;private final long maxDatabaseBytes,minFree;private final String volume;
    public StorageAdmission(JdbcTemplate jdbc,@Value("${m3.storage.max-database-bytes:0}") long maxDatabaseBytes,
            @Value("${m3.storage.local-volume-path:}") String volume,@Value("${m3.storage.min-local-free-bytes:0}") long minFree){this.jdbc=jdbc;this.maxDatabaseBytes=maxDatabaseBytes;this.volume=volume;this.minFree=minFree;if(maxDatabaseBytes<0||minFree<0||minFree>0&&volume.isBlank())throw new IllegalArgumentException("Invalid storage quota or local volume");}
    public void check(int bytes){
        var quota=jdbc.queryForMap("SELECT hot_bytes,max_hot_bytes,max_database_bytes,admission_blocked FROM storage_usage WHERE singleton");
        if(Boolean.TRUE.equals(quota.get("admission_blocked")))throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE,"Storage monitor has paused intake");
        long limit=((Number)quota.get("max_hot_bytes")).longValue(),used=((Number)quota.get("hot_bytes")).longValue();
        if(limit>0 && used+bytes>limit)throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE,"Hot payload storage quota exceeded; intake was not accepted");
        long databaseMax=((Number)quota.get("max_database_bytes")).longValue();
        if(databaseMax>0 && jdbc.queryForObject("SELECT pg_database_size(current_database())",Long.class)>=databaseMax)throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE,"Database storage quota exceeded; intake was not accepted");
        if(minFree>0)try{if(java.nio.file.Files.getFileStore(java.nio.file.Path.of(volume)).getUsableSpace()<minFree)throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE,"Local storage has insufficient free space");}catch(java.io.IOException failure){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Local storage cannot be inspected");}
    }
}
