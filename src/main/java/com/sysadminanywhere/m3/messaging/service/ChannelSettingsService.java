package com.sysadminanywhere.m3.messaging.service;

import com.sysadminanywhere.m3.messaging.domain.ChannelDirection;
import com.sysadminanywhere.m3.messaging.domain.ChannelSettings;
import com.sysadminanywhere.m3.messaging.domain.ChannelType;
import com.sysadminanywhere.m3.messaging.source.InboundSourceRegistry;
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
    private final InboundSourceRegistry sourceRegistry;
    private final com.sysadminanywhere.m3.messaging.repository.RuleRepository rules;
    private final com.sysadminanywhere.m3.messaging.repository.RuleActionRepository actions;

    public ChannelSettingsService(ChannelSettingsRepository channelSettingsRepository,
                                  @Lazy InboundSourceRegistry sourceRegistry,
                                  com.sysadminanywhere.m3.messaging.repository.RuleActionRepository actions, com.sysadminanywhere.m3.messaging.repository.RuleRepository rules) {
        this.channelSettingsRepository = channelSettingsRepository;
        this.sourceRegistry = sourceRegistry;
        this.actions = actions;
        this.rules = rules;
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<ChannelSettings> page(ChannelType type, org.springframework.data.domain.Pageable pageable) {
        return type == null ? channelSettingsRepository.findAll(pageable) : channelSettingsRepository.findByChannelType(type, pageable);
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
        return channelSettingsRepository.findByDirection(ChannelDirection.INBOUND).stream()
                .filter(channel -> Boolean.TRUE.equals(channel.getEnabled())).toList();
    }

    @Transactional(readOnly = true)
    public List<ChannelSettings> findEnabledOutboundChannels() {
        return channelSettingsRepository.findByDirection(ChannelDirection.OUTBOUND).stream()
                .filter(channel -> Boolean.TRUE.equals(channel.getEnabled())).toList();
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
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public ChannelSettings createChannel(String name, ChannelType channelType, ChannelDirection direction,
                                          String description, Map<String, String> properties) {
        return createChannel(name, channelType, direction, description, properties, true);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public ChannelSettings createChannel(String name, ChannelType channelType, ChannelDirection direction,
                                          String description, Map<String, String> properties, boolean enabled) {
        if (channelSettingsRepository.existsByName(name)) {
            throw new IllegalArgumentException("Channel with name '" + name + "' already exists");
        }

        var channel = new ChannelSettings(name, channelType, direction);
        channel.setEnabled(direction==ChannelDirection.INBOUND || enabled);
        channel.setDescription(description);
        if (properties != null) {
            channel.setProperties(endpointProperties(properties));
        }

        sourceRegistry.validate(channel);
        var savedChannel = channelSettingsRepository.save(channel);
        sourceRegistry.restartChannel(savedChannel);
        return savedChannel;
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public ChannelSettings updateChannel(Long channelId, String name, String description, Boolean enabled, Map<String,String> properties, long expectedVersion) {
        var channel=channelSettingsRepository.findById(channelId).orElseThrow();
        com.sysadminanywhere.m3.base.persistence.Revisions.check(channel.getVersion(), expectedVersion);
        return updateChannel(channelId,name,description,enabled,properties);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public ChannelSettings updateChannel(Long channelId, String name, String description,
                                         Boolean enabled, Map<String, String> properties) {
        var channel = channelSettingsRepository.findById(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));

        if (!channel.getName().equals(name) && channelSettingsRepository.existsByName(name)) {
            throw new IllegalArgumentException("Channel with name '" + name + "' already exists");
        }

        // Resolve legacy callers that still supplied a destination name before renaming it.
        actions.findByTargetChannel(channel.getName()).stream()
                .filter(action -> action.getDestinationChannel() == null)
                .forEach(action -> action.setDestinationChannel(channel));
        channel.setName(name);
        channel.setDescription(description);
        if (enabled != null || channel.getDirection()==ChannelDirection.INBOUND)
            channel.setEnabled(channel.getDirection()==ChannelDirection.INBOUND || enabled);
        if (properties != null) {
            var merged = endpointProperties(properties);
            channel.getProperties().forEach((key,value) -> {
                if (com.sysadminanywhere.m3.base.security.SecretCipher.isSecret(key) && merged.containsKey(key) && merged.get(key).isEmpty()) merged.put(key,value);
            });
            channel.setProperties(merged);
        }

        sourceRegistry.validate(channel);
        var savedChannel = channelSettingsRepository.save(channel);
        sourceRegistry.restartChannel(savedChannel);
        return savedChannel;
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public void deleteChannel(Long channelId) {
        var channel=channelSettingsRepository.findById(channelId).orElseThrow(() -> new IllegalArgumentException("Channel not found"));
        if (rules.existsBySourceChannel_Id(channelId) || actions.existsByDestinationChannel_Id(channelId)
                || !actions.findByTargetChannel(channel.getName()).isEmpty())
            throw new IllegalArgumentException("Channel is referenced by rules; change their source or destination before deleting");
        sourceRegistry.stopChannel(channelId);
        channelSettingsRepository.deleteById(channelId);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public void toggleEnabled(Long channelId) {
        var channel = channelSettingsRepository.findById(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
        if (channel.getDirection()==ChannelDirection.INBOUND)
            throw new IllegalArgumentException("Enable or disable the loading rule to control inbound reception");
        channel.setEnabled(!channel.getEnabled());
        sourceRegistry.validate(channel);
        var savedChannel = channelSettingsRepository.save(channel);
        sourceRegistry.restartChannel(savedChannel);
    }

    @Transactional(readOnly = true)
    public boolean existsByName(String name) {
        return channelSettingsRepository.existsByName(name);
    }

    public Map<String, String> getDefaultPropertiesForType(ChannelType type) {
        Map<String,String> defaults = switch (type) {
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
                    "valueDeserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer"
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
        return endpointProperties(defaults);
    }
    private Map<String,String> endpointProperties(Map<String,String> properties) {
        if (properties.entrySet().stream().anyMatch(entry -> entry.getKey()==null || entry.getKey().isBlank()
                || entry.getKey().length()>255 || entry.getValue()==null))
            throw new IllegalArgumentException("Channel properties require nonblank keys and nonnull values");
        var result = new java.util.HashMap<>(properties);
        result.remove("keyDeserializer"); result.remove("valueDeserializer");
        com.sysadminanywhere.m3.messaging.source.InboundSourceSpec.LOADING_KEYS.forEach(result::remove);
        return result;
    }
}
