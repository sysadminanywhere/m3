package com.sysadminanywhere.m3.messaging.service;

import com.sysadminanywhere.m3.messaging.domain.ChannelDirection;
import com.sysadminanywhere.m3.messaging.domain.ChannelSettings;
import com.sysadminanywhere.m3.messaging.domain.ChannelType;
import com.sysadminanywhere.m3.messaging.source.InboundSourceRegistry;
import com.sysadminanywhere.m3.messaging.repository.ChannelSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChannelSettingsServiceTest {

    @Mock
    private ChannelSettingsRepository channelSettingsRepository;

    @Mock
    private InboundSourceRegistry sourceRegistry;

    @InjectMocks
    private ChannelSettingsService channelSettingsService;

    private ChannelSettings testChannel;

    @BeforeEach
    void setUp() {
        testChannel = new ChannelSettings("test-channel", ChannelType.DIRECTORY, ChannelDirection.INBOUND);
    }

    @Test
    void findAll_ShouldReturnAllChannels() {
        // Given
        when(channelSettingsRepository.findAll()).thenReturn(List.of(testChannel));

        // When
        List<ChannelSettings> result = channelSettingsService.findAll();

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("test-channel");
    }

    @Test
    void findById_WhenChannelExists_ShouldReturnChannel() {
        // Given
        when(channelSettingsRepository.findById(1L)).thenReturn(Optional.of(testChannel));

        // When
        Optional<ChannelSettings> result = channelSettingsService.findById(1L);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("test-channel");
    }

    @Test
    void findById_WhenChannelNotExists_ShouldReturnEmpty() {
        // Given
        when(channelSettingsRepository.findById(1L)).thenReturn(Optional.empty());

        // When
        Optional<ChannelSettings> result = channelSettingsService.findById(1L);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void findByName_WhenChannelExists_ShouldReturnChannel() {
        // Given
        when(channelSettingsRepository.findByName("test-channel")).thenReturn(Optional.of(testChannel));

        // When
        Optional<ChannelSettings> result = channelSettingsService.findByName("test-channel");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("test-channel");
    }

    @Test
    void findByType_ShouldReturnChannelsOfType() {
        // Given
        when(channelSettingsRepository.findByChannelType(ChannelType.DIRECTORY)).thenReturn(List.of(testChannel));

        // When
        List<ChannelSettings> result = channelSettingsService.findByType(ChannelType.DIRECTORY);

        // Then
        assertThat(result).hasSize(1);
    }

    @Test
    void findByDirection_ShouldReturnChannelsOfDirection() {
        // Given
        when(channelSettingsRepository.findByDirection(ChannelDirection.INBOUND)).thenReturn(List.of(testChannel));

        // When
        List<ChannelSettings> result = channelSettingsService.findByDirection(ChannelDirection.INBOUND);

        // Then
        assertThat(result).hasSize(1);
    }

    @Test
    void createChannel_WhenNameUnique_ShouldCreateChannel() {
        // Given
        when(channelSettingsRepository.existsByName("new-channel")).thenReturn(false);
        when(channelSettingsRepository.save(any(ChannelSettings.class))).thenAnswer(inv -> {
            ChannelSettings ch = inv.getArgument(0);
            return ch;
        });

        // When
        ChannelSettings result = channelSettingsService.createChannel(
                "new-channel",
                ChannelType.FTP,
                ChannelDirection.INBOUND,
                "Test description",
                Map.of("host", "localhost")
        );

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("new-channel");
        assertThat(result.getChannelType()).isEqualTo(ChannelType.FTP);
        assertThat(result.getDirection()).isEqualTo(ChannelDirection.INBOUND);
        assertThat(result.getDescription()).isEqualTo("Test description");
        assertThat(result.getEnabled()).isTrue();
        assertThat(result.getCreatedAt()).isNotNull();
        verify(channelSettingsRepository).save(result);
        verify(sourceRegistry).restartChannel(result);
    }

    @Test
    void createChannel_WhenNameExists_ShouldThrowException() {
        // Given
        when(channelSettingsRepository.existsByName("existing-channel")).thenReturn(true);

        // When / Then
        assertThatThrownBy(() -> channelSettingsService.createChannel(
                "existing-channel",
                ChannelType.FTP,
                ChannelDirection.INBOUND,
                null,
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");

        verify(channelSettingsRepository, never()).save(any());
    }

    @Test
    void updateChannel_WhenChannelExistsAndNameUnique_ShouldUpdateChannel() {
        // Given
        ChannelSettings existingChannel = new ChannelSettings("old-name", ChannelType.DIRECTORY, ChannelDirection.INBOUND);
        when(channelSettingsRepository.findById(1L)).thenReturn(Optional.of(existingChannel));
        when(channelSettingsRepository.existsByName("new-name")).thenReturn(false);
        when(channelSettingsRepository.save(any(ChannelSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        ChannelSettings result = channelSettingsService.updateChannel(
                1L,
                "new-name",
                "Updated description",
                false,
                Map.of("directoryPath", "/new/path")
        );

        // Then
        assertThat(result.getName()).isEqualTo("new-name");
        assertThat(result.getDescription()).isEqualTo("Updated description");
        assertThat(result.getEnabled()).isFalse();
        assertThat(result.getUpdatedAt()).isNotNull();
        verify(sourceRegistry).restartChannel(result);
    }

    @Test
    void updateChannel_WhenChannelNotExists_ShouldThrowException() {
        // Given
        when(channelSettingsRepository.findById(1L)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> channelSettingsService.updateChannel(
                1L,
                "name",
                null,
                null,
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Channel not found");
    }

    @Test
    void deleteChannel_ShouldStopChannelAndDelete() {
        // When
        channelSettingsService.deleteChannel(1L);

        // Then
        verify(sourceRegistry).stopChannel(1L);
        verify(channelSettingsRepository).deleteById(1L);
    }

    @Test
    void toggleEnabled_ShouldToggleAndRestartChannel() {
        // Given
        ChannelSettings channel = spy(new ChannelSettings("channel", ChannelType.DIRECTORY, ChannelDirection.INBOUND));
        when(channelSettingsRepository.findById(1L)).thenReturn(Optional.of(channel));
        when(channelSettingsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // When
        channelSettingsService.toggleEnabled(1L);

        // Then
        verify(channel).setEnabled(false);
        verify(sourceRegistry).restartChannel(any());
    }

    @Test
    void existsByName_ShouldReturnRepositoryResult() {
        // Given
        when(channelSettingsRepository.existsByName("test")).thenReturn(true);

        // When
        boolean result = channelSettingsService.existsByName("test");

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void getDefaultPropertiesForType_FTP_ShouldReturnCorrectDefaults() {
        // When
        Map<String, String> defaults = channelSettingsService.getDefaultPropertiesForType(ChannelType.FTP);

        // Then
        assertThat(defaults).containsEntry("port", "21");
        assertThat(defaults).containsEntry("passiveMode", "true");
        assertThat(defaults).containsEntry("deleteRemoteFiles", "false");
    }

    @Test
    void getDefaultPropertiesForType_SFTP_ShouldReturnCorrectDefaults() {
        // When
        Map<String, String> defaults = channelSettingsService.getDefaultPropertiesForType(ChannelType.SFTP);

        // Then
        assertThat(defaults).containsEntry("port", "22");
        assertThat(defaults).containsEntry("deleteRemoteFiles", "false");
    }

    @Test
    void getDefaultPropertiesForType_Kafka_ShouldReturnCorrectDefaults() {
        // When
        Map<String, String> defaults = channelSettingsService.getDefaultPropertiesForType(ChannelType.KAFKA);

        // Then
        assertThat(defaults).containsEntry("bootstrapServers", "localhost:9092");
        assertThat(defaults).containsEntry("groupId", "m3-consumer");
    }

    @Test
    void getDefaultPropertiesForType_Directory_ShouldReturnCorrectDefaults() {
        // When
        Map<String, String> defaults = channelSettingsService.getDefaultPropertiesForType(ChannelType.DIRECTORY);

        // Then
        assertThat(defaults).containsEntry("pollingInterval", "5000");
        assertThat(defaults).containsEntry("deleteAfterProcessing", "true");
    }

    @Test
    void getDefaultPropertiesForType_RabbitMQ_ShouldReturnCorrectDefaults() {
        // When
        Map<String, String> defaults = channelSettingsService.getDefaultPropertiesForType(ChannelType.RABBITMQ);

        // Then
        assertThat(defaults).containsEntry("host", "localhost");
        assertThat(defaults).containsEntry("port", "5672");
    }
}
