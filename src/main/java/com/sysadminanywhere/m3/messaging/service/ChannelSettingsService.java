package com.sysadminanywhere.m3.messaging.service;

import com.sysadminanywhere.m3.messaging.domain.ChannelDirection;
import com.sysadminanywhere.m3.messaging.domain.ChannelSettings;
import com.sysadminanywhere.m3.messaging.domain.ChannelType;
import com.sysadminanywhere.m3.messaging.integration.config.DirectoryInboundIntegration;
import com.sysadminanywhere.m3.messaging.repository.ChannelSettingsRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ChannelSettingsService {

    private final ChannelSettingsRepository channelSettingsRepository;
    private final DirectoryInboundIntegration directoryInboundIntegration;

    public ChannelSettingsService(ChannelSettingsRepository channelSettingsRepository,
                                  @Lazy DirectoryInboundIntegration directoryInboundIntegration) {
        this.channelSettingsRepository = channelSettingsRepository;
        this.directoryInboundIntegration = directoryInboundIntegration;
    }

    @Transactional(readOnly = true)
    public List<ChannelSettings> findAll() {
        return channelSettingsRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<ChannelSettings> findById(Long id) {
        return channelSettingsRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<ChannelSettings> findByName(String name) {
        return channelSettingsRepository.findByName(name);
    }

    @Transactional(readOnly = true)
    public List<ChannelSettings> findByType(ChannelType channelType) {
        return channelSettingsRepository.findByChannelType(channelType);
    }

    @Transactional(readOnly = true)
    public List<ChannelSettings> findByDirection(ChannelDirection direction) {
        return channelSettingsRepository.findByDirection(direction);
    }

    @Transactional(readOnly = true)
    public List<ChannelSettings> findEnabledInboundChannels() {
        return channelSettingsRepository.findByChannelTypeAndDirectionAndEnabledTrue(null, ChannelDirection.INBOUND);
    }

    @Transactional(readOnly = true)
    public List<ChannelSettings> findEnabledOutboundChannels() {
        return channelSettingsRepository.findByChannelTypeAndDirectionAndEnabledTrue(null, ChannelDirection.OUTBOUND);
    }

    @Transactional(readOnly = true)
    public List<ChannelSettings> findEnabledInboundChannelsByType(ChannelType type) {
        return channelSettingsRepository.findByChannelTypeAndDirectionAndEnabledTrue(type, ChannelDirection.INBOUND);
    }

    @Transactional(readOnly = true)
    public List<ChannelSettings> findEnabledOutboundChannelsByType(ChannelType type) {
        return channelSettingsRepository.findByChannelTypeAndDirectionAndEnabledTrue(type, ChannelDirection.OUTBOUND);
    }

    @Transactional
    public ChannelSettings createChannel(String name, ChannelType channelType, ChannelDirection direction,
                                          String description, Map<String, String> properties) {
        if (channelSettingsRepository.existsByName(name)) {
            throw new IllegalArgumentException("Channel with name '" + name + "' already exists");
        }

        var channel = new ChannelSettings(name, channelType, direction);
        channel.setDescription(description);
        if (properties != null) {
            channel.setProperties(properties);
        }

        var savedChannel = channelSettingsRepository.save(channel);
        directoryInboundIntegration.restartChannel(savedChannel);
        return savedChannel;
    }

    @Transactional
    public ChannelSettings updateChannel(Long channelId, String name, String description,
                                         Boolean enabled, Map<String, String> properties) {
        var channel = channelSettingsRepository.findById(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));

        if (!channel.getName().equals(name) && channelSettingsRepository.existsByName(name)) {
            throw new IllegalArgumentException("Channel with name '" + name + "' already exists");
        }

        channel.setName(name);
        channel.setDescription(description);
        if (enabled != null) {
            channel.setEnabled(enabled);
        }
        if (properties != null) {
            channel.setProperties(properties);
        }

        var savedChannel = channelSettingsRepository.save(channel);
        directoryInboundIntegration.restartChannel(savedChannel);
        return savedChannel;
    }

    @Transactional
    public void deleteChannel(Long channelId) {
        directoryInboundIntegration.stopChannel(channelId);
        channelSettingsRepository.deleteById(channelId);
    }

    @Transactional
    public void toggleEnabled(Long channelId) {
        var channel = channelSettingsRepository.findById(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        channel.setEnabled(!channel.getEnabled());
        var savedChannel = channelSettingsRepository.save(channel);
        directoryInboundIntegration.restartChannel(savedChannel);
    }

    @Transactional(readOnly = true)
    public boolean existsByName(String name) {
        return channelSettingsRepository.existsByName(name);
    }

    public Map<String, String> getDefaultPropertiesForType(ChannelType type) {
        return switch (type) {
            case FTP -> Map.of(
                    "host", "",
                    "port", "21",
                    "username", "",
                    "password", "",
                    "remoteDirectory", "/",
                    "filePattern", "*",
                    "passiveMode", "true",
                    "deleteRemoteFiles", "false"
            );
            case SFTP -> Map.of(
                    "host", "",
                    "port", "22",
                    "username", "",
                    "password", "",
                    "privateKey", "",
                    "remoteDirectory", "/",
                    "filePattern", "*",
                    "deleteRemoteFiles", "false"
            );
            case KAFKA -> Map.of(
                    "bootstrapServers", "localhost:9092",
                    "topic", "",
                    "groupId", "m3-consumer",
                    "autoOffsetReset", "earliest",
                    "keyDeserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                    "valueDeserializer", "org.apache.kafka.common.serialization.StringDeserializer"
            );
            case DIRECTORY -> Map.of(
                    "directoryPath", "",
                    "filePattern", "*",
                    "pollingInterval", "5000",
                    "deleteAfterProcessing", "true",
                    "recursive", "false"
            );
            case RABBITMQ -> Map.of(
                    "host", "localhost",
                    "port", "5672",
                    "username", "guest",
                    "password", "guest",
                    "virtualHost", "/",
                    "queue", "",
                    "exchange", "",
                    "routingKey", ""
            );
        };
    }
}
