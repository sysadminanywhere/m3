package com.sysadminanywhere.m3.messaging.integration.config;

import com.sysadminanywhere.m3.messaging.domain.ChannelDirection;
import com.sysadminanywhere.m3.messaging.domain.ChannelSettings;
import com.sysadminanywhere.m3.messaging.domain.ChannelType;
import com.sysadminanywhere.m3.messaging.service.ChannelSettingsService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.context.IntegrationFlowContext;
import org.springframework.integration.dsl.Pollers;
import org.springframework.integration.file.FileReadingMessageSource;
import org.springframework.integration.file.filters.CompositeFileListFilter;
import org.springframework.integration.file.filters.SimplePatternFileListFilter;
import org.springframework.integration.file.filters.IgnoreHiddenFileListFilter;
import org.springframework.integration.file.filters.AcceptOnceFileListFilter;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class DirectoryInboundIntegration {

    private static final Logger log = LoggerFactory.getLogger(DirectoryInboundIntegration.class);

    private final ChannelSettingsService channelSettingsService;
    private final MessageChannel messageInputChannel;
    private final IntegrationFlowContext flowContext;
    private final Map<Long, String> flowIds = new HashMap<>();
    private final Map<Long, FileReadingMessageSource> messageSources = new HashMap<>();

    public DirectoryInboundIntegration(ChannelSettingsService channelSettingsService,
                                       MessageChannel messageInputChannel,
                                       IntegrationFlowContext flowContext) {
        this.channelSettingsService = channelSettingsService;
        this.messageInputChannel = messageInputChannel;
        this.flowContext = flowContext;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing directory inbound endpoints...");
        var channels = channelSettingsService.findAll().stream()
                .filter(channel -> channel.getChannelType() == ChannelType.DIRECTORY)
                .filter(channel -> channel.getDirection() == ChannelDirection.INBOUND)
                .filter(ChannelSettings::getEnabled)
                .toList();
        log.info("Found {} enabled DIRECTORY INBOUND channels", channels.size());
        channels.forEach(this::createDirectoryInboundEndpoint);
        log.info("Directory inbound endpoints initialization complete");
    }

    public void createDirectoryInboundEndpoint(ChannelSettings channel) {
        // Prevent duplicate flow creation
        if (flowIds.containsKey(channel.getId())) {
            log.warn("Flow already exists for channel '{}', stopping it first", channel.getName());
            stopChannel(channel.getId());
        }

        var properties = channel.getProperties();
        var directoryPath = properties.get("directoryPath");
        var filePattern = properties.getOrDefault("filePattern", "*");
        var pollingInterval = Long.parseLong(properties.getOrDefault("pollingInterval", "5000"));
        var recursive = Boolean.parseBoolean(properties.getOrDefault("recursive", "false"));
        var deleteAfterProcessing = Boolean.parseBoolean(properties.getOrDefault("deleteAfterProcessing", "false"));

        log.info("Creating directory inbound endpoint for channel '{}': path={}, pattern={}, interval={}ms",
                channel.getName(), directoryPath, filePattern, pollingInterval);

        if (directoryPath == null || directoryPath.isBlank()) {
            log.warn("Directory path is empty for channel '{}', skipping", channel.getName());
            return;
        }

        var directory = new File(directoryPath);
        if (!directory.exists()) {
            log.info("Directory {} does not exist, creating it", directoryPath);
            directory.mkdirs();
        }

        var fileSource = new FileReadingMessageSource();
        fileSource.setDirectory(directory);
        fileSource.setScanEachPoll(true);

        var filter = new CompositeFileListFilter<File>();
        filter.addFilter(new IgnoreHiddenFileListFilter());
        filter.addFilter(new SimplePatternFileListFilter(filePattern));
        filter.addFilter(new AcceptOnceFileListFilter<>());
        fileSource.setFilter(filter);

        messageSources.put(channel.getId(), fileSource);

        var flowId = "directoryInboundFlow-" + channel.getId();
        var flow = IntegrationFlow.from(fileSource,
                        c -> c.poller(Pollers.fixedRate(pollingInterval).maxMessagesPerPoll(1)))
                .handle((payload, headers) -> {
                    var file = (File) payload;
                    log.info("File detected: {} ({} bytes) in channel '{}'", file.getAbsolutePath(), file.length(), channel.getName());

                    var message = MessageBuilder.withPayload(file)
                            .setHeader("channelName", channel.getName())
                            .setHeader("sourceChannelId", channel.getId())
                            .setHeader("channelType", channel.getChannelType().name())
                            .setHeader("fileName", file.getName())
                            .setHeader("filePath", file.getAbsolutePath())
                            .setHeader("fileSize", file.length())
                            .setHeader("deleteAfterProcessing", deleteAfterProcessing)
                            .build();

                    log.debug("Sending message to messageInputChannel: headers={}", message.getHeaders());
                    messageInputChannel.send(message);
                    log.info("File {} sent to processing pipeline", file.getName());

                    if (deleteAfterProcessing) {
                        boolean deleted = file.delete();
                        log.info("File {} deleted after processing: {}", file.getName(), deleted);
                    }

                    return null;
                })
                .get();

        flowContext.registration(flow).id(flowId).register();
        flowIds.put(channel.getId(), flowId);
        
        log.info("Directory inbound endpoint started for channel '{}' (flowId: {})", channel.getName(), flowId);
    }

    @PreDestroy
    public void destroy() {
        flowIds.values().forEach(flowId -> {
            try {
                flowContext.remove(flowId);
                log.info("Removed flow: {}", flowId);
            } catch (Exception e) {
                log.warn("Error removing flow {}: {}", flowId, e.getMessage());
            }
        });
        messageSources.values().forEach(FileReadingMessageSource::destroy);
    }

    public void restartChannel(ChannelSettings channel) {
        log.info("Restarting channel '{}' (id={}, enabled={}, type={}, direction={})", 
                channel.getName(), channel.getId(), channel.getEnabled(), 
                channel.getChannelType(), channel.getDirection());
        stopChannel(channel.getId());
        if (Boolean.TRUE.equals(channel.getEnabled()) 
                && channel.getChannelType() == ChannelType.DIRECTORY
                && channel.getDirection() == ChannelDirection.INBOUND) {
            createDirectoryInboundEndpoint(channel);
        }
    }

    public void stopChannel(Long channelId) {
        log.info("Stopping directory endpoint for channel id={}", channelId);
        var flowId = flowIds.remove(channelId);
        if (flowId != null) {
            try {
                flowContext.remove(flowId);
                log.info("Flow {} removed for channel id={}", flowId, channelId);
            } catch (Exception e) {
                log.warn("Error removing flow for channel id={}: {}", channelId, e.getMessage());
            }
        }
        var source = messageSources.remove(channelId);
        if (source != null) {
            source.destroy();
        }
    }
}
