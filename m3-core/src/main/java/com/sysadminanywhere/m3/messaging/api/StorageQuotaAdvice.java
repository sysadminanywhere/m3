package com.sysadminanywhere.m3.messaging.api;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
@RestControllerAdvice(basePackages="com.sysadminanywhere.m3.messaging.api")
public class StorageQuotaAdvice {
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<java.util.Map<String,String>> databaseFailure(DataAccessException error){
        for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql && "53000".equals(sql.getSQLState()))return ResponseEntity.status(507).body(java.util.Map.of("error","Storage quota exceeded; message was not accepted"));
        throw error;
    }
}
