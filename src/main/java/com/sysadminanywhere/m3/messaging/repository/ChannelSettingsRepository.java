package com.sysadminanywhere.m3.messaging.repository;

import com.sysadminanywhere.m3.messaging.domain.ChannelDirection;
import com.sysadminanywhere.m3.messaging.domain.ChannelSettings;
import com.sysadminanywhere.m3.messaging.domain.ChannelType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChannelSettingsRepository extends JpaRepository<ChannelSettings, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from ChannelSettings c where c.id=:id")
    Optional<ChannelSettings> findForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    org.springframework.data.domain.Page<ChannelSettings> findByChannelType(ChannelType type, org.springframework.data.domain.Pageable pageable);

    Optional<ChannelSettings> findByName(String name);

    List<ChannelSettings> findByChannelType(ChannelType channelType);

    List<ChannelSettings> findByDirection(ChannelDirection direction);

    List<ChannelSettings> findByEnabledTrue();

    List<ChannelSettings> findByChannelTypeAndDirectionAndEnabledTrue(ChannelType channelType, ChannelDirection direction);

    boolean existsByName(String name);
}
