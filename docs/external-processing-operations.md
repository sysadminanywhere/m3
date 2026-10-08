# External processing lifecycle and operations

An inbound message has a business status independent of its routing/delivery jobs. Migration 025 adds durable attempts per recipient; migration 026 adds shared storage accounting. Old inbound records receive one `external` recipient using their business status where possible. Legacy PENDING/FAILED records are adopted as LOADED attempts because a routing failure does not prove a business-processing failure. The original catalog status is not rewritten during migration.

Configure an INBOUND rule's loading settings:

```properties
initialStatus=LOADED
processingRecipients=erp,billing,analytics?
loadedTimeoutSeconds=86400
processingTimeoutSeconds=3600
```

Recipient keys are unique ASCII identifiers, at most 80 characters; at most 20 recipients are supported and at least one must be required. The `?` suffix marks an optional recipient. Every required recipient must succeed for aggregate PROCESSED; any required failure yields PROCESSING_FAILED. Otherwise progress yields PROCESSING, and no progress yields LOADED. Optional failure is visible per recipient without failing the aggregate. Settings are captured into attempt rows when accepted; editing a rule does not reinterpret earlier messages.

## Callback identity and state

1. Consume `processing.requested` or read `GET /api/v1/messages/{id}/processing`.
2. Obtain `attemptId`, `version` and the recipient key. Fetch exact bytes with an authorized original reader if needed.
3. Send progress/completion to `PATCH /api/v1/messages/{id}/processing/{recipient}/status`:

```json
{
  "callbackId": "00000000-0000-0000-0000-000000000001",
  "processingAttemptId": "00000000-0000-0000-0000-000000000002",
  "expectedVersion": 0,
  "status": "PROCESSING",
  "expectedStatus": "LOADED",
  "detail": "Accepted by ERP"
}
```

`callbackId`, `processingAttemptId`, `expectedVersion` and `status` are required. `expectedStatus` and detail (2,000 characters maximum) are optional. Use the version returned by the preceding successful response. The default-recipient shortcut `/messages/{id}/status` also requires callback identity and version. This intentionally replaces the earlier unversioned contract; old clients must be upgraded.

Each request ID is deduplicated transactionally across retries. An identical retry returns its saved response even if later state changed; it is an acknowledgement of that operation, not a fresh state read. Reusing its ID with different data returns 409. An old attempt or version returns 409. LOADED may become PROCESSING or either terminal status; PROCESSING may become terminal. Terminal states cannot be reopened by callback. Repeating the current state does not change its timestamp/version; an existing callback ID cannot acquire new effects. Timeline diagnostics are masked; callback responses never return raw errors.

Both API and worker catalog changes lock the message row. Routing cannot overwrite external state. Neither aggregate completion nor callback arrival bypasses active routing or unpublished event checks for retention.

## Replay and deadlines

`POST /api/v1/messages/{id}/processing/{recipient}/replay` takes:

```json
{
  "requestId": "00000000-0000-0000-0000-000000000003",
  "processingAttemptId": "00000000-0000-0000-0000-000000000002",
  "expectedVersion": 1,
  "acknowledgeDuplicate": false
}
```

Failed or overdue attempts can be replayed. Active/successful attempts require explicit `acknowledgeDuplicate=true`. Resolve pending/running/failed routing jobs first. Replay creates a new attempt UUID, increments its number, preserves previous results, emits `processing.requested` and records the actor. The message retains its ID and body; it does not rerun successful routing or create an outbound copy. Retry remains the separate operation for failed routing/delivery jobs.

The external consumer must subscribe to processing events to implement business replay. Event `idempotencyKey` is stable for one attempt and changes on intentional replay. Deduplicate event IDs and attempt keys; use a domain-specific stable business key if the business operation must never be repeated across attempts. M3 cannot undo previously committed effects at a recipient.

The message's Processing tab includes **Attempt history**, showing up to 200 newest attempts with recipient, status, timestamps, masked diagnostics and attempt UUID. The total count identifies omitted older rows; the existing processing API with `history=true` remains available for export. Replay distinguishes rejection, an unconfirmed result, and successful creation followed by a failed screen refresh. Refresh current state before repeating an unconfirmed operation.

The deadline watcher emits one `processing.overdue` event/alert per attempt. Overdue is an observation, not a synthetic business failure, and does not increment callback version. Late completion remains valid and resolves the alert. Default timeouts are one day awaiting intake and one hour processing; per-rule values are 1–31,536,000 seconds. A restart retains attempts, deadlines and request acknowledgements.

## Machine accounts

Configure service credentials on the UI/API host through a protected configuration file or `M3_SERVICES_JSON` in Compose. Only BCrypt hashes are accepted; plaintext service passwords are not stored in config:

```properties
m3.security.services.erp.username=erp-service
m3.security.services.erp.password-hash={bcrypt}<BCrypt hash>
m3.security.services.erp.channels=12,18
m3.security.services.erp.recipients=erp
m3.security.services.erp.original=true
m3.security.services.erp.status=true
m3.security.services.erp.replay=false
```

Service accounts can read only scoped inbound message previews/payload/text and their processing recipients. Original bytes require the explicit permission; access is audited. Callback and replay permissions are independent. They cannot access configuration, licenses, global operations, arbitrary history, forwarding, deletion or routing retry. Human admin/operator access remains unchanged. Use TLS, a unique account per integration and rotate by updating the hash/restarting the UI; in-progress attempts survive rotation. Machine accounts are not interactive UI accounts.

All state-changing API calls require `X-M3-Request: 1` and explicit HTTP Basic credentials; browser cookies do not authorize the stateless API.

## Events and operational visibility

Processing events use a separate durable PostgreSQL outbox, committed with state. Publishing uses RabbitMQ persistent messages, mandatory routing and publisher confirms. Unconfirmed events retry every 10 seconds. A crash after broker confirmation can repeat an event ID; delivery is at least once. Event bodies contain IDs, recipient, status, version, deadline and resource path, without payload, credentials or diagnostic errors.

Routing keys: `processing.requested`, `processing.started`, `processing.completed`, `processing.failed`, `processing.overdue`. Bind recipient consumers to `processing.#` and filter recipients. For independent consumers create independent durable queues; competing consumers on the same queue share deliveries. The default `m3.processing.events` queue retains events for seven days, configurable with `M3_PROCESSING_EVENTS_TTL_MS`. Queue-argument changes require a broker migration. Consumers returning after TTL expiry reconcile current state through the API. The outbox's published rows are retained for 30 days by default; pending rows are never aged out.

The Operations UI and authenticated `GET /api/v1/operations` show active alerts, overdue recipients, storage and backlog. `GET /api/v1/operations/metrics` exports Prometheus gauge exposition. Sampling defaults to 30 seconds. Configure your monitoring system to alert on processing deadlines, publication lag, archive failures, indexing lag, hot/database usage and cold cleanup backlog. No email, webhook or external monitoring credentials are silently provisioned.

**Refresh** requests a new sample. Automatic screen refresh uses the cached sample and displays its timestamp. A failed database sample preserves the last successful values with an explicit stale warning; it never presents retained values as healthy fresh data. Metrics `m3_operations_sample_age_seconds` and `m3_operations_data_stale` expose freshness to external monitoring. The default age threshold is 90 seconds (`M3_OPERATIONS_STALE_AFTER_MS`); adjust it when changing the sampling interval.

## Storage limits and retention

```properties
m3.storage.max-hot-bytes=10737418240
m3.storage.max-database-bytes=21474836480
m3.storage.catalog-days=365
m3.storage.error-mode=KEEP
m3.storage.error-days=0
m3.retention.message-history-days=0
m3.retention.processing-events-days=30
```

Zero quotas/retention mean unlimited/disabled, preserving existing data. Shared PostgreSQL counters enforce the hot quota across original and prepared delivery byte arrays under concurrent writes. The database-size intake guard includes catalog/index/metadata growth and is an admission threshold, not a hard filesystem cap. Keep headroom for routing snapshots, indexes, maintenance and PostgreSQL/WAL; snapshots also count against the hot quota, so exhausting it may pause delivery preparation. Intake returns 507 without acknowledging storage success; native receivers retain/requeue their source. Configure identical limits on all UI instances.

Local free-space monitoring is optional: set `m3.storage.local-volume-path` and `m3.storage.min-local-free-bytes` only to an actual filesystem visible to that UI process. It cannot inspect an unrelated remote PostgreSQL disk. A critical threshold/unavailable monitored volume pauses intake through shared database state. Monitor remote DB/S3 physical capacity externally as well.

Error handling is explicit: KEEP preserves errors; ARCHIVE requires main ARCHIVE mode and configured S3; DELETE permanently removes eligible errors after `error-days`. Active recipient attempts, active routing, unpublished receipts/events and linked children prevent policy deletion. Cold replay reads verified original bytes from S3. `catalog-days` expires completed message catalogs/history and queues their cold objects for durable cleanup. A deletion trigger also queues cold objects after manual removal. Cleanup never deletes an object still referenced by a live catalog and retries failures. Bucket lifecycle rules must not delete live objects earlier than the catalog policy. See [backup/restore](backup-restore.md).

## Worker lease loss and rollout

Every execution obtains a fresh lease UUID; renewals start after slot admission commits. A failed/expired renewal invalidates the execution and interrupts its thread. Lease ownership is checked before transport start and before sending, and before committing inbound routing. No new external send is authorized after detected lease loss. Already transmitted network requests cannot be recalled; their outcomes can be uncertain and receivers still need idempotency. Lease metadata is diagnostic, not a receiver-enforced monotonic fencing protocol.

Stop intake during rollout, deploy the UI to apply migrations 025–026, then update **all** workers and callback consumers before reopening traffic. Older callback consumers lack mandatory identity/version; older workers lack the new lifecycle/admission checks. Exercise stale callbacks, broker loss, expired leases, quota exhaustion and consistent recovery in an isolated environment. [Distribution instructions](code-distribution.md) describes quotes, renewals and installation transfer.
