package com.sysadminanywhere.m3.messaging.domain;

import jakarta.persistence.*;
import jakarta.annotation.Nullable;

@Entity
@Table(name = "message_metadata")
public class MessageMetadata {

    public static final int KEY_MAX_LENGTH = 100;
    public static final int VALUE_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "metadata_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @Column(name = "key", nullable = false, length = KEY_MAX_LENGTH)
    private String key;

    @Column(name = "value", nullable = false, length = VALUE_MAX_LENGTH)
    private String value;

    protected MessageMetadata() {
    }

    public MessageMetadata(Message message, String key, String value) {
        this.message = message;
        this.key = key;
        this.value = value;
    }

    public @Nullable Long getId() {
        return id;
    }

    public Message getMessage() {
        return message;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        if (key.length() > KEY_MAX_LENGTH) {
            throw new IllegalArgumentException("Key length exceeds " + KEY_MAX_LENGTH);
        }
        this.key = key;
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

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !getClass().isAssignableFrom(obj.getClass())) {
            return false;
        }
        if (obj == this) {
            return true;
        }

        MessageMetadata other = (MessageMetadata) obj;
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
