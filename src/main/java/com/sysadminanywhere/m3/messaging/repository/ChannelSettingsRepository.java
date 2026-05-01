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

    Optional<ChannelSettings> findByName(String name);

    List<ChannelSettings> findByChannelType(ChannelType channelType);

    List<ChannelSettings> findByDirection(ChannelDirection direction);

    List<ChannelSettings> findByEnabledTrue();

    List<ChannelSettings> findByChannelTypeAndDirectionAndEnabledTrue(ChannelType channelType, ChannelDirection direction);

    boolean existsByName(String name);
}
