package com.sysadminanywhere.m3.messaging.service;

import io.minio.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;

@Service
public class S3ColdPayloadStore implements ColdPayloadStore {
    public static final int MAX_ARCHIVE_BYTES=64*1024*1024;
    private final MinioClient client;
    private final String bucket;
    public S3ColdPayloadStore(@Value("${m3.archive.endpoint:}") String endpoint,
            @Value("${m3.archive.access-key:}") String accessKey,@Value("${m3.archive.secret-key:}") String secretKey,
            @Value("${m3.archive.bucket:m3-messages}") String bucket,@Value("${m3.archive.region:us-east-1}") String region) {
        this.bucket=bucket;
        client=endpoint.isBlank()?null:MinioClient.builder().endpoint(endpoint).credentials(accessKey,secretKey).region(region).build();
        if(client!=null) client.setTimeout(10000,30000,30000);
    }
    public boolean configured() { return client!=null; }
    public void delete(String key) {
        if(client==null)throw new IllegalStateException("Archive storage is not configured");
        try{client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());}catch(Exception e){throw new IllegalStateException("Archive deletion failed",e);}
    }
    public void put(String key,byte[] bytes) {
        if(client==null) throw new IllegalStateException("Archive storage is not configured");
        if(bytes.length>MAX_ARCHIVE_BYTES) throw new IllegalArgumentException("Archive envelope exceeds supported size");
        try { client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                .stream(new ByteArrayInputStream(bytes),(long)bytes.length,-1).contentType("application/json").build()); }
        catch(Exception error) { throw new IllegalStateException("Archive write failed",error); }
    }
    public byte[] get(String key) {
        if(client==null) throw new IllegalStateException("Archive storage is not configured");
        try(var stream=client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            byte[] bytes=stream.readNBytes(MAX_ARCHIVE_BYTES+1);
            if(bytes.length>MAX_ARCHIVE_BYTES) throw new IllegalStateException("Archive envelope exceeds supported size");
            return bytes;
        } catch(Exception error) { throw new IllegalStateException("Archive read failed",error); }
    }
}
