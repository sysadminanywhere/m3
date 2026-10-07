package com.sysadminanywhere.m3.messaging.api;

import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.repository.ChannelSettingsRepository;
import com.sysadminanywhere.m3.messaging.repository.MessageRepository;
import com.sysadminanywhere.m3.messaging.service.SourceDeliveryService;
import com.sysadminanywhere.m3.messaging.source.InboundSourceSpec;
import com.sysadminanywhere.m3.messaging.source.SourceHealth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Profile("!worker")
public class MessageApi {
    @org.springframework.beans.factory.annotation.Autowired private com.sysadminanywhere.m3.messaging.service.MessageInspectionService inspection;
    @org.springframework.beans.factory.annotation.Autowired private com.sysadminanywhere.m3.messaging.service.MessageHistoryService history;
    private final MessageRepository messages;
    private final com.sysadminanywhere.m3.messaging.service.MessageService messageService;
    private final ChannelSettingsRepository channels;
    private final SourceDeliveryService deliveries;
    private final SourceHealth sourceHealth;
    private final com.sysadminanywhere.m3.messaging.repository.RuleRepository loadingRules;

    public MessageApi(MessageRepository messages, ChannelSettingsRepository channels, SourceDeliveryService deliveries, SourceHealth sourceHealth,
            com.sysadminanywhere.m3.messaging.repository.RuleRepository loadingRules, com.sysadminanywhere.m3.messaging.service.MessageService messageService) {
        this.messages = messages;
        this.channels = channels;
        this.deliveries = deliveries;
        this.sourceHealth = sourceHealth;
        this.loadingRules = loadingRules;
        this.messageService = messageService;
    }

    @GetMapping("/messages/{id}")
    @Transactional
    public MessageResponse get(@PathVariable long id,@RequestParam(defaultValue="false") boolean original) {
        var message = messages.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
        return response(message,inspection.view(id,null,original,null));
    }

    @GetMapping("/messages/{id}/jobs")
    public List<com.sysadminanywhere.m3.messaging.service.MessageService.JobInfo> jobs(@PathVariable long id) {
        if (!messages.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Message not found");
        return messageService.executionJobs(id);
    }
    @PostMapping("/messages/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable long id,@RequestParam(defaultValue="false") boolean acknowledgeUncertain) {
        if (!messages.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Message not found");
        try { messageService.retry(id,acknowledgeUncertain); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.CONFLICT,invalid.getMessage()); }
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/messages/{id}/payload")
    @Transactional
    public ResponseEntity<byte[]> payload(@PathVariable long id,@RequestParam(defaultValue="false") boolean original,
            @RequestParam(required=false) Long jobId) {
        var message = messages.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
        byte[] bytes=inspection.view(id,jobId,original,null).bytes();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("message-" + id + ".bin").build().toString())
                .contentLength(bytes.length).body(bytes);
    }

    @GetMapping("/messages/{id}/text")
    @Transactional
    public TextResponse text(@PathVariable long id, @RequestParam(required = false) String charset,
            @RequestParam(defaultValue="false") boolean original,@RequestParam(required=false) Long jobId) {
        var message = messages.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
        if (charset==null && jobId==null && original && message.getCharset()==null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Charset is unknown; specify charset for preview");
        try { var view=inspection.view(id,jobId,original,charset);return new TextResponse(view.text(),view.charset()); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage()); }
    }

    @GetMapping("/sources")
    @Transactional(readOnly = true)
    public List<SourceResponse> sources() {
        return channels.findAll().stream().filter(channel -> channel.getDirection() == ChannelDirection.INBOUND)
                .map(channel -> new SourceResponse(channel.getId(), channel.getName(), channel.getChannelType(),
                        Boolean.TRUE.equals(channel.getEnabled()), channelState(channel))).toList();
    }

    private SourceHealth.State channelState(ChannelSettings channel) {
        var states = loadingRules.findBySourceChannelAndEnabled(channel,true).stream()
                .filter(rule -> rule.getRuleType() == RuleType.INBOUND).map(rule -> sourceHealth.get(rule.getId())).toList();
        return states.stream().filter(state -> !"RUNNING".equals(state.status())).findFirst()
                .orElseGet(() -> states.isEmpty() ? new SourceHealth.State("IDLE",null,null) : states.getFirst());
    }

    @GetMapping("/loading-rules")
    @Transactional(readOnly = true)
    public List<LoadingRuleResponse> loadingRules() {
        return loadingRules.findByRuleType(RuleType.INBOUND).stream().map(rule -> new LoadingRuleResponse(
                rule.getId(),rule.getName(),rule.getSourceChannel().getId(),rule.getEnabled(),sourceHealth.workers(rule.getId()))).toList();
    }
    public record LoadingRuleResponse(Long id,String name,Long channelId,boolean enabled,List<SourceHealth.WorkerState> workers) { }

    @PostMapping("/channels/{channelId}/messages")
    @Transactional
    public ResponseEntity<MessageResponse> receive(@PathVariable long channelId, @Valid @RequestBody IncomingMessage request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 200)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be 1-200 characters");
        }
        var channel = channels.findById(channelId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        if (channel.getDirection() != ChannelDirection.INBOUND) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Channel must be inbound");
        }
        Map<String, String> metadata = new java.util.HashMap<>(request.metadata() == null ? Map.of() : request.metadata());
        long id;
        try {
            String charset = PayloadCodec.charset(request.charset());
            if (charset != null) {
                String previous = PayloadCodec.charset(metadata.get("charset"));
                if (previous != null && !previous.equals(charset)) throw new IllegalArgumentException("Conflicting charset declarations");
                metadata.put("charset", charset);
            }
            if (request.ruleId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Specify the loading ruleId");
            var rule = loadingRules.findById(request.ruleId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loading rule not found"));
            if (rule.getRuleType() != RuleType.INBOUND || !Boolean.TRUE.equals(rule.getEnabled()) || rule.getWorkerPool() == null
                    || !rule.getSourceChannel().getId().equals(channelId))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Rule must be active, INBOUND and use the selected source channel");
            id = deliveries.receive(InboundSourceSpec.fromRule(rule), request.payload(), request.payloadType(),
                    metadata, idempotencyKey == null ? null : "http:" + idempotencyKey);
        } catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage()); }
        var stored = messages.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "Message was deleted"));
        return ResponseEntity.created(URI.create("/api/v1/messages/" + stored.getId())).body(response(stored,inspection.view(stored.getId(),null,false,null)));
    }

    static MessageResponse response(Message message,com.sysadminanywhere.m3.messaging.service.MessageInspectionService.PayloadView view) {
        String payload=view.original() && "BASE64".equals(message.getPayloadFormat()) ? java.util.Base64.getEncoder().encodeToString(view.bytes()) : view.text();
        return new MessageResponse(message.getId(), message.getDirection(), message.getStatus(), payload,
                message.getPayloadType(), message.getSourceSystem(), message.getTargetSystem(), message.getCreatedAt(),
                message.getProcessedAt(), view.original()?message.getCharset():"UTF-8", message.getCharsetSource(), message.getPayloadSize(),
                view.original() && "BASE64".equals(message.getPayloadFormat()) ? "base64" : "text", view.metadata().entrySet().stream()
                .map(value -> new Metadata(value.getKey(), value.getValue())).toList(),message.isArchived(),!view.original());
    }

    @GetMapping("/messages/{id}/history")
    public List<com.sysadminanywhere.m3.messaging.service.MessageHistoryService.Event> history(@PathVariable long id,@RequestParam(defaultValue="false") boolean original) {
        if(!messages.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Message not found");
        return history.events(id,original);
    }
    @GetMapping("/messages/{id}/related")
    public List<com.sysadminanywhere.m3.messaging.service.MessageHistoryService.Link> related(@PathVariable long id) {
        if(!messages.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Message not found");
        return history.links(id);
    }
    @GetMapping("/messages/{id}/versions")
    public List<com.sysadminanywhere.m3.messaging.service.MessageInspectionService.BodyVersion> versions(@PathVariable long id) {
        if(!messages.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Message not found");
        return inspection.versions(id);
    }

    public record IncomingMessage(@jakarta.validation.constraints.NotNull @Size(max = Message.PAYLOAD_REQUEST_MAX_LENGTH) String payload,
                                  @NotBlank @Size(max = Message.PAYLOAD_TYPE_MAX_LENGTH) String payloadType,
                                  @Size(max = 64) String charset,
                                  @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive Long ruleId,
                                  @Size(max = 100) Map<@NotBlank @Size(max = MessageMetadata.KEY_MAX_LENGTH) String,
                                          @jakarta.validation.constraints.NotNull @Size(max = MessageMetadata.VALUE_MAX_LENGTH) String> metadata) { }
    public record Metadata(String key, String value) { }
    public record TextResponse(String text, String charset) { }
    public record SourceResponse(Long id, String name, ChannelType type, boolean enabled, SourceHealth.State runtime) { }
    public record MessageResponse(Long id, MessageDirection direction, MessageStatus status, String payload,
                                  String payloadType, String sourceSystem, String targetSystem, Instant createdAt,
                                  Instant processedAt, String charset, String charsetSource, int payloadSize,
                                  String payloadEncoding, List<Metadata> metadata,boolean archived,boolean masked) { }
}
