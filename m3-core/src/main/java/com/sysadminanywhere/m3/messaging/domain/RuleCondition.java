package com.sysadminanywhere.m3.messaging.domain;

import jakarta.persistence.*;
import jakarta.annotation.Nullable;

@Entity
@Table(name = "rule_condition")
public class RuleCondition {

    public static final int FIELD_MAX_LENGTH = 100;
    public static final int VALUE_MAX_LENGTH = 1000;

    @Version
    @Column(nullable = false, columnDefinition="bigint not null default 0")
    private long version;
    public long getVersion() { return version; }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "condition_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    private Rule rule;

    @Column(name = "field", nullable = false, length = FIELD_MAX_LENGTH)
    private String field;

    @Enumerated(EnumType.STRING)
    @Column(name = "operator", nullable = false)
    private ConditionOperator operator;

    @Column(name = "value", nullable = false, length = VALUE_MAX_LENGTH)
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(name = "logical_operator", nullable = false)
    private LogicalOperator logicalOperator = LogicalOperator.AND;

    public static RuleCondition snapshot(Long id, Rule rule, String field, ConditionOperator operator, String value) { var item=new RuleCondition(rule,field,operator,value); item.id=id; return item; }

    protected RuleCondition() {
    }

    public RuleCondition(Rule rule, String field, ConditionOperator operator, String value) {
        this.rule = rule;
        this.field = field;
        this.operator = operator;
        this.value = value;
    }

    public @Nullable Long getId() {
        return id;
    }

    public Rule getRule() {
        return rule;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        if (field.length() > FIELD_MAX_LENGTH) {
            throw new IllegalArgumentException("Field length exceeds " + FIELD_MAX_LENGTH);
        }
        this.field = field;
    }

    public ConditionOperator getOperator() {
        return operator;
    }

    public void setOperator(ConditionOperator operator) {
        this.operator = operator;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        if (value.length() > VALUE_MAX_LENGTH) {
            throw new IllegalArgumentException("Value length exceeds " + VALUE_MAX_LENGTH);
        }
        this.value = value;
    }

    public LogicalOperator getLogicalOperator() {
        return logicalOperator;
    }

    public void setLogicalOperator(LogicalOperator logicalOperator) {
        this.logicalOperator = logicalOperator;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !getClass().isAssignableFrom(obj.getClass())) {
            return false;
        }
        if (obj == this) {
            return true;
        }

        RuleCondition other = (RuleCondition) obj;
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
