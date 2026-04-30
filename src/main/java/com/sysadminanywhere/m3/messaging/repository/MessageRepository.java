package com.sysadminanywhere.m3.messaging.repository;

import com.sysadminanywhere.m3.messaging.domain.Message;
import com.sysadminanywhere.m3.messaging.domain.MessageDirection;
import com.sysadminanywhere.m3.messaging.domain.MessageStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    Page<Message> findByDirection(MessageDirection direction, Pageable pageable);

    Page<Message> findByStatus(MessageStatus status, Pageable pageable);

    Page<Message> findBySourceSystem(String sourceSystem, Pageable pageable);

    Page<Message> findByDirectionAndStatus(MessageDirection direction, MessageStatus status, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.sourceSystem = :sourceSystem AND m.createdAt >= :from AND m.createdAt <= :to")
    List<Message> findBySourceSystemAndDateRange(@Param("sourceSystem") String sourceSystem,
                                                 @Param("from") Instant from,
                                                 @Param("to") Instant to);

    @Query("SELECT m FROM Message m WHERE m.direction = :direction AND m.status = :status ORDER BY m.createdAt DESC")
    List<Message> findRecentByDirectionAndStatus(@Param("direction") MessageDirection direction,
                                                 @Param("status") MessageStatus status,
                                                 Pageable pageable);
}
