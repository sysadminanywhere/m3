package com.sysadminanywhere.m3.messaging.domain;

import jakarta.persistence.*;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "message")
public class Message {

    public static final int PAYLOAD_MAX_LENGTH = 1_000_000;
    public static final int PAYLOAD_MAX_BYTES = 1_000_000;
    public static final int PAYLOAD_REQUEST_MAX_LENGTH = 4 * ((PAYLOAD_MAX_BYTES + 2) / 3);
    public static final int SOURCE_SYSTEM_MAX_LENGTH = 200;
    public static final int TARGET_SYSTEM_MAX_LENGTH = 200;
    public static final int PAYLOAD_TYPE_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private MessageDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MessageStatus status = MessageStatus.PENDING;

    @Column(name = "payload_bytes", nullable = false, columnDefinition = "bytea")
    private byte[] payloadBytes;

    @Column(name = "charset", length = 64)
    private String charset;

    @Column(name = "charset_source", nullable = false, length = 32)
    private String charsetSource;

    @Column(name = "payload_format", nullable = false, length = 16)
    private String payloadFormat;

    @Column(name = "payload_type", nullable = false, length = PAYLOAD_TYPE_MAX_LENGTH)
    private String payloadType;

    @Column(name = "source_system", length = SOURCE_SYSTEM_MAX_LENGTH)
    @Nullable
    private String sourceSystem;

    @Column(name = "target_system", length = TARGET_SYSTEM_MAX_LENGTH)
    @Nullable
    private String targetSystem;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    @Nullable
    private Instant processedAt;

    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<MessageMetadata> metadata = new HashSet<>();

    protected Message() {
    }

    public Message(MessageDirection direction, String payload, String payloadType) {
        setDirection(direction);
        setPayloadType(payloadType);
        setPayload(payload);
        this.createdAt = Instant.now();
    }

    public @Nullable Long getId() {
        return id;
    }

    public MessageDirection getDirection() {
        return direction;
    }

    public void setDirection(MessageDirection direction) {
        this.direction = direction;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public void setStatus(MessageStatus status) {
        this.status = status;
    }

    public String getPayload() {
        return "BASE64".equals(payloadFormat) ? java.util.Base64.getEncoder().encodeToString(payloadBytes)
                : PayloadCodec.decode(payloadBytes, charset);
    }

    public void setPayload(String payload) {
        if (payload.length() > PAYLOAD_MAX_LENGTH) {
            throw new IllegalArgumentException("Payload length exceeds " + PAYLOAD_MAX_LENGTH);
        }
        setContent(PayloadCodec.encode(payload, "UTF-8"), "UTF-8", "TEXT_UTF8", "TEXT");
    }

    public byte[] getPayloadBytes() { return payloadBytes.clone(); }
    public int getPayloadSize() { return payloadBytes.length; }
    public @Nullable String getCharset() { return charset; }
    public String getCharsetSource() { return charsetSource; }
    public String getPayloadFormat() { return payloadFormat; }
    public String getDecodedText() { return PayloadCodec.decode(payloadBytes, charset); }
    public void setContent(byte[] bytes, @Nullable String charset, String source, String format) {
        if (bytes == null || bytes.length > PAYLOAD_MAX_BYTES) throw new IllegalArgumentException("Payload exceeds the supported byte size");
        if (!java.util.Set.of("TEXT", "BASE64").contains(format)) throw new IllegalArgumentException("Invalid payload format");
        String canonical = PayloadCodec.charset(charset);
        if (format.equals("TEXT") && canonical == null) throw new IllegalArgumentException("Text requires a charset");
        this.payloadBytes = bytes.clone(); this.charset = canonical; this.charsetSource = source; this.payloadFormat = format;
    }

    public String getPayloadType() {
        return payloadType;
    }

    public void setPayloadType(String payloadType) {
        if (payloadType == null || payloadType.isBlank() || payloadType.length() > PAYLOAD_TYPE_MAX_LENGTH) {
            throw new IllegalArgumentException("Payload type length exceeds " + PAYLOAD_TYPE_MAX_LENGTH);
        }
        this.payloadType = payloadType;
    }

    public @Nullable String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(@Nullable String sourceSystem) {
        if (sourceSystem != null && sourceSystem.length() > SOURCE_SYSTEM_MAX_LENGTH) {
            throw new IllegalArgumentException("Source system length exceeds " + SOURCE_SYSTEM_MAX_LENGTH);
        }
        this.sourceSystem = sourceSystem;
    }

    public @Nullable String getTargetSystem() {
        return targetSystem;
    }

    public void setTargetSystem(@Nullable String targetSystem) {
        if (targetSystem != null && targetSystem.length() > TARGET_SYSTEM_MAX_LENGTH) {
            throw new IllegalArgumentException("Target system length exceeds " + TARGET_SYSTEM_MAX_LENGTH);
        }
        this.targetSystem = targetSystem;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public @Nullable Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(@Nullable Instant processedAt) {
        this.processedAt = processedAt;
    }

    public Set<MessageMetadata> getMetadata() {
        return metadata;
    }

    public void addMetadata(String key, String value) {
        var existing = metadata.stream().filter(item -> item.getKey().equals(key)).findFirst();
        if (existing.isPresent()) { existing.get().setValue(value); return; }
        metadata.add(new MessageMetadata(this, key, value));
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !getClass().isAssignableFrom(obj.getClass())) {
            return false;
        }
        if (obj == this) {
            return true;
        }

        Message other = (Message) obj;
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
