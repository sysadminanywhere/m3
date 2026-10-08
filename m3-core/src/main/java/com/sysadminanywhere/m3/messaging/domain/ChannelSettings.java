package com.sysadminanywhere.m3.messaging.domain;

import jakarta.persistence.*;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "channel_settings")
public class ChannelSettings {

    public static final int NAME_MAX_LENGTH = 200;
    public static final int DESCRIPTION_MAX_LENGTH = 500;

    @Version
    @Column(nullable = false, columnDefinition="bigint not null default 0")
    private long version;
    public long getVersion() { return version; }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "channel_id")
    private Long id;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH, unique = true)
    private String name;

    @Column(name = "description", length = DESCRIPTION_MAX_LENGTH)
    @Nullable
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false)
    private ChannelType channelType;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "direction", nullable = false)
    @Enumerated(EnumType.STRING)
    private ChannelDirection direction = ChannelDirection.INBOUND;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    @Nullable
    private Instant updatedAt;

    @Convert(attributeName="value", converter=com.sysadminanywhere.m3.base.security.SecretValueConverter.class)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "channel_properties", joinColumns = @JoinColumn(name = "channel_id"))
    @MapKeyColumn(name = "property_key")
    @Column(name = "property_value", columnDefinition = "TEXT")
    private Map<String, String> properties = new HashMap<>();

    public static ChannelSettings snapshot(Long id, String name, ChannelType type, ChannelDirection direction, java.util.Map<String,String> properties) { var item=new ChannelSettings(name,type,direction); item.id=id; item.setProperties(new java.util.HashMap<>(properties)); return item; }

    protected ChannelSettings() {
    }

    public ChannelSettings(String name, ChannelType channelType, ChannelDirection direction) {
        this.name = name;
        this.channelType = channelType;
        this.direction = direction;
        this.createdAt = Instant.now();
    }

    public @Nullable Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (name.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Name length exceeds " + NAME_MAX_LENGTH);
        }
        this.name = name;
        this.updatedAt = Instant.now();
    }

    public @Nullable String getDescription() {
        return description;
    }

    public void setDescription(@Nullable String description) {
        if (description != null && description.length() > DESCRIPTION_MAX_LENGTH) {
            throw new IllegalArgumentException("Description length exceeds " + DESCRIPTION_MAX_LENGTH);
        }
        this.description = description;
        this.updatedAt = Instant.now();
    }

    public ChannelType getChannelType() {
        return channelType;
    }

    public void setChannelType(ChannelType channelType) {
        this.channelType = channelType;
        this.updatedAt = Instant.now();
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    public ChannelDirection getDirection() {
        return direction;
    }

    public void setDirection(ChannelDirection direction) {
        this.direction = direction;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public @Nullable Instant getUpdatedAt() {
        return updatedAt;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
        this.updatedAt = Instant.now();
    }

    public String getProperty(String key) {
        return properties.get(key);
    }

    public void setProperty(String key, String value) {
        properties.put(key, value);
        this.updatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !getClass().isAssignableFrom(obj.getClass())) {
            return false;
        }
        if (obj == this) {
            return true;
        }

        ChannelSettings other = (ChannelSettings) obj;
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return name + " (" + channelType + ")";
    }
}
