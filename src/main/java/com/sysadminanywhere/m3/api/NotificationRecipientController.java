package com.sysadminanywhere.m3.api;

import com.sysadminanywhere.m3.runtime.NotificationRecipientStore;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Internal management API; Community must be isolated behind its technical service token. */
@RestController
@RequestMapping("/api/v2/notification-recipients")
public class NotificationRecipientController {
    private final NotificationRecipientStore store;
    public NotificationRecipientController(NotificationRecipientStore store) { this.store=store; }
    public record UpdateRequest(long expectedRevision,NotificationRecipientStore.Settings settings) {}
    @GetMapping public List<NotificationRecipientStore.Recipient> list() { return store.list(); }
    @PostMapping("/{id}/test") @ResponseStatus(HttpStatus.ACCEPTED) public java.util.Map<String,UUID> test(@PathVariable UUID id) {
        UUID event=store.requestTest(id); if (event==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Recipient is disabled or missing");
        return java.util.Map.of("eventId",event);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public java.util.Map<String,UUID> create(@RequestBody NotificationRecipientStore.Settings settings) {
        try { return java.util.Map.of("id",store.create(settings)); }
        catch (IllegalArgumentException failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid recipient settings"); }
    }
    @PutMapping("/{id}") public void update(@PathVariable UUID id,@RequestBody UpdateRequest request) {
        try { if (!store.update(id,request.expectedRevision(),request.settings())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Recipient was changed or removed"); }
        catch (IllegalArgumentException failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid recipient settings"); }
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID id,@RequestParam long expectedRevision) {
        try { if (!store.delete(id,expectedRevision)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Recipient was changed or removed"); }
        catch (IllegalArgumentException failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid recipient revision"); }
    }
}
