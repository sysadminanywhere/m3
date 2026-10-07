package com.sysadminanywhere.m3.messaging.domain;

import jakarta.persistence.*;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "rule")
public class Rule {

    public static final int NAME_MAX_LENGTH = 200;

    @Version
    @Column(nullable = false, columnDefinition="bigint not null default 0")
    private long version;
    public long getVersion() { return version; }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rule_id")
    private Long id;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    @Nullable
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false)
    private RuleType ruleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "outbound_payload_mode", nullable = false, length = 20)
    private OutboundPayloadMode outboundPayloadMode = OutboundPayloadMode.BODY;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "source_channel_id", nullable = false)
    private ChannelSettings sourceChannel;

    @Column(name = "priority", nullable = false)
    private Integer priority = 0;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "worker_pool_id")
    private RuleWorkerPool workerPool;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    @Nullable
    private Instant updatedAt;

    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("id ASC")
    private Set<RuleCondition> conditions = new java.util.LinkedHashSet<>();

    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Set<RuleAction> actions = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rule_loading_properties", joinColumns = @JoinColumn(name = "rule_id"))
    @MapKeyColumn(name = "property_key")
    @Column(name = "property_value", columnDefinition = "TEXT")
    private java.util.Map<String, String> loadingProperties = new java.util.HashMap<>();

    public java.util.Map<String, String> getLoadingProperties() { return loadingProperties; }

    public static Rule snapshot(Long id, String name, RuleType type, ChannelSettings source) { var item=new Rule(name,type,source); item.id=id; return item; }

    protected Rule() {
    }

    public Rule(String name, RuleType ruleType, ChannelSettings sourceChannel) {
        this.name = name;
        this.ruleType = ruleType;
        this.sourceChannel = sourceChannel;
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
        this.description = description;
        this.updatedAt = Instant.now();
    }

    public RuleType getRuleType() {
        return ruleType;
    }

    public void setRuleType(RuleType ruleType) {
        this.ruleType = ruleType;
        this.updatedAt = Instant.now();
    }

    public OutboundPayloadMode getOutboundPayloadMode() { return outboundPayloadMode; }

    public void setOutboundPayloadMode(OutboundPayloadMode mode) {
        this.outboundPayloadMode = mode == null ? OutboundPayloadMode.BODY : mode;
        this.updatedAt = Instant.now();
    }

    public ChannelSettings getSourceChannel() {
        return sourceChannel;
    }

    public void setSourceChannel(ChannelSettings sourceChannel) {
        this.sourceChannel = sourceChannel;
        this.updatedAt = Instant.now();
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
        this.updatedAt = Instant.now();
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    public @Nullable String getDestinationChannelName() {
        return actions.stream().filter(action -> action.getActionType() == ActionType.ROUTE && action.getTargetChannel() != null)
                .sorted(java.util.Comparator.comparing(RuleAction::getPriority)
                        .thenComparing(RuleAction::getId, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .map(RuleAction::getTargetChannel).findFirst().orElse(null);
    }

    public RuleWorkerPool getWorkerPool() { return workerPool; }

    public void setWorkerPool(RuleWorkerPool workerPool) {
        this.workerPool = workerPool;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public @Nullable Instant getUpdatedAt() {
        return updatedAt;
    }

    public Set<RuleCondition> getConditions() {
        return conditions;
    }

    public Set<RuleAction> getActions() {
        return actions;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !getClass().isAssignableFrom(obj.getClass())) {
            return false;
        }
        if (obj == this) {
            return true;
        }

        Rule other = (Rule) obj;
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
