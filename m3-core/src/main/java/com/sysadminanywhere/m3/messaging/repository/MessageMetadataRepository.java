package com.sysadminanywhere.m3.messaging.repository;

import com.sysadminanywhere.m3.messaging.domain.MessageMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageMetadataRepository extends JpaRepository<MessageMetadata, Long> {

    List<MessageMetadata> findByMessageId(Long messageId);

    List<MessageMetadata> findByKey(String key);
}
