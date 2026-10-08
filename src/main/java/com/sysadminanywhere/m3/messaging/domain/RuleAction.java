package com.sysadminanywhere.m3.messaging.domain;

import jakarta.persistence.*;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "rule_action")
public class RuleAction {

    public static final int TARGET_CHANNEL_MAX_LENGTH = 200;
    public static final int METADATA_KEY_MAX_LENGTH = 100;
    public static final int METADATA_VALUE_MAX_LENGTH = 1000;

    @Version
    @Column(nullable = false, columnDefinition="bigint not null default 0")
    private long version;
    public long getVersion() { return version; }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "action_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    private Rule rule;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false)
    private ActionType actionType;

    @Column(name = "target_channel", length = TARGET_CHANNEL_MAX_LENGTH)
    @Nullable
    private String targetChannel;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "target_channel_id")
    private ChannelSettings destinationChannel;

    public ChannelSettings getDestinationChannel() { return destinationChannel; }
    public void setDestinationChannel(ChannelSettings channel) {
        destinationChannel = channel;
        targetChannel = channel == null ? null : channel.getName();
    }

    @Column(name = "transformation_script", columnDefinition = "TEXT")
    @Nullable
    private String transformationScript;

    @Column(name = "filter_result")
    @Nullable
    private Boolean filterResult;

    @Column(name = "metadata_key", length = METADATA_KEY_MAX_LENGTH)
    @Nullable
    private String metadataKey;

    @Column(name = "metadata_value", length = METADATA_VALUE_MAX_LENGTH)
    @Nullable
    private String metadataValue;

    @Column(name = "priority", nullable = false)
    private Integer priority = 0;

    public static RuleAction snapshot(Long id, Rule rule, ActionType type) { var item=new RuleAction(rule,type); item.id=id; return item; }

    protected RuleAction() {
    }

    public RuleAction(Rule rule, ActionType actionType) {
        this.rule = rule;
        this.actionType = actionType;
    }

    public @Nullable Long getId() {
        return id;
    }

    public Rule getRule() {
        return rule;
    }

    public ActionType getActionType() {
        return actionType;
    }

    public void setActionType(ActionType actionType) {
        this.actionType = actionType;
    }

    public @Nullable String getTargetChannel() {
        return destinationChannel == null ? targetChannel : destinationChannel.getName();
    }

    public void setTargetChannel(@Nullable String targetChannel) {
        if (targetChannel != null && targetChannel.length() > TARGET_CHANNEL_MAX_LENGTH) {
            throw new IllegalArgumentException("Target channel length exceeds " + TARGET_CHANNEL_MAX_LENGTH);
        }
        this.targetChannel = targetChannel;
        this.destinationChannel = null;
    }

    public @Nullable String getTransformationScript() {
        return transformationScript;
    }

    public void setTransformationScript(@Nullable String transformationScript) {
        this.transformationScript = transformationScript;
    }

    public @Nullable Boolean getFilterResult() {
        return filterResult;
    }

    public void setFilterResult(@Nullable Boolean filterResult) {
        this.filterResult = filterResult;
    }

    public @Nullable String getMetadataKey() {
        return metadataKey;
    }

    public void setMetadataKey(@Nullable String metadataKey) {
        if (metadataKey != null && metadataKey.length() > METADATA_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException("Metadata key length exceeds " + METADATA_KEY_MAX_LENGTH);
        }
        this.metadataKey = metadataKey;
    }

    public @Nullable String getMetadataValue() {
        return metadataValue;
    }

    public void setMetadataValue(@Nullable String metadataValue) {
        if (metadataValue != null && metadataValue.length() > METADATA_VALUE_MAX_LENGTH) {
            throw new IllegalArgumentException("Metadata value length exceeds " + METADATA_VALUE_MAX_LENGTH);
        }
        this.metadataValue = metadataValue;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !getClass().isAssignableFrom(obj.getClass())) {
            return false;
        }
        if (obj == this) {
            return true;
        }

        RuleAction other = (RuleAction) obj;
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
