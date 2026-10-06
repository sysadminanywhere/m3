# Outgoing messages

Submit a message to the control plane with the ID of the rule that must deliver it:

```http
POST /api/v1/messages/outbound
Content-Type: application/json
Idempotency-Key: order-123

{
  "ruleId": 12,
  "payload": "{\"orderId\":123}",
  "payloadType": "application/json",
  "metadata": {"externalId":"order-123", "fileName":"order.json"}
}
```

The response is `202 Accepted`, containing the saved message ID and a `Location` pointing to `GET /api/v1/messages/{id}`. Acceptance means that the message, metadata and delivery job have committed to PostgreSQL; transport delivery happens asynchronously. The original payload and metadata remain available by ID, including after delivery.

## Rule and channel configuration

Select **Destination Channel** on a rule to route matching messages. This works for inbound rules as well as enabled `OUTBOUND` rules. Choose whether the destination receives the message body or its message ID. In ID mode, the transport body is the decimal ID of the original message, its content type is `text/plain`, and metadata `m3MessagePayloadPath` contains the relative path `GET /api/v1/messages/{id}/payload`, which returns the original bytes. Body transformations are skipped in ID mode, while filters, conditions and metadata actions still apply. Saving creates or updates the single `ROUTE` action automatically in the same transaction as the rule. The destination and delivery format are shown in the rule list. Open **Configure** to assign a worker pool. For `OUTBOUND` rules, the source selector is hidden; new outbound rules use the destination as their source context. Existing source context is preserved. Only the explicitly requested rule executes. `GET /api/v1/rules/outbound` lists available rule IDs, pool assignments and payload mode.

Conditions, filtering, transformations and enrichment use the existing rule engine. A condition mismatch or filter rejects delivery and marks the message `FAILED`. Transformations affect the transmitted payload; they do not overwrite the saved original. Actions run in priority order. A disabled rule also stops subsequent attempts until it is enabled and the failed message is retried.

Supported destinations:

| Type | Required channel properties | Delivery |
| --- | --- | --- |
| DIRECTORY | `directoryPath` | Local file in the worker filesystem |
| FTP | `host`, `username`, `password`, `remoteDirectory` | Binary transfer; optional `port`, `passiveMode` |
| SFTP | `host`, `username`, `remoteDirectory`, authentication | Password or private key; configure `knownHostsPath` or explicitly `allowUnknownKeys` |
| KAFKA | `bootstrapServers`, `topic` | Acknowledged record; extra producer settings use `kafka.*` |
| RABBITMQ | `host`, `username`, `queue` for default exchange, or `exchange` and `routingKey` | Persistent message with publisher confirmation; optional `declareQueue=true` |

Remote directories must already exist. For DIRECTORY, FTP and SFTP deliveries, the file name is `message-<id>-<fileName>`. The suffix comes from the message metadata key `fileName`; the outbound submitter must include it in `metadata` if a particular name is required. If the key is absent, the suffix defaults to `payload.dat`. Inbound directory and remote-file sources populate `fileName` from the source file's name, and forwarding preserves that metadata. `fileName` must be one file name without path separators. Files are written through temporary files and renamed. Existing identical contents are accepted as an already delivered message; conflicting contents fail without replacement.

Bodies are stored as original bytes with optional charset. Unchanged outgoing messages reuse these bytes. For exact original-byte preservation, pass a Base64 payload, an appropriate `payloadType`, optional `charset`, and metadata `"encoding":"base64"`. Base64 is decoded before database storage and is not sent as the transport body. Kafka and RabbitMQ forward scalar metadata as headers and identify the original message with `m3MessageId`; RabbitMQ also uses message ID `m3:<id>`. Kafka uses the message ID as its record key unless `kafkaKey` is provided in metadata. See [charsets and transformations](message-encodings.md).

## Reliability and status

Message storage and job creation share one transaction. A worker commits the prepared delivery plan before performing network or filesystem operations. Retries reuse that plan, preserving the destination ID, transformed bytes and headers across worker restarts. Channel connection settings are read at delivery time. Editing a rule affects messages whose plans have not yet been prepared.

Transient failures retry with delays of 2, 4, 8, 16 and then 32 seconds. `M3_OUTBOUND_MAX_ATTEMPTS` sets the attempt limit (default 20). Invalid configuration or exhausted attempts marks the message `FAILED`. Expired claims are reclaimed; claim tokens prevent a stale worker from changing the status of a newer attempt. Transport delivery is at least once: if a worker stops after the destination accepts a message but before the database records success, broker delivery may repeat. Consumers should deduplicate using `m3MessageId`.

- `GET /api/v1/messages/{id}`: original message, metadata and status (`PENDING`, `SENT`, `FAILED`).
- `GET /api/v1/messages/{id}/delivery`: job status, attempts, worker pool, next attempt and error.
- `POST /api/v1/messages/{id}/retry`: retry a failed outgoing message after fixing configuration; returns `202`.

An optional `Idempotency-Key` deduplicates submissions within the selected rule. Repeating the same request returns the original message ID. Reusing the key with different payload, payload type or metadata returns `409 Conflict`. Incoming receipt notifications are not published for outgoing submissions.

## Docker storage

Compose mounts the `message_files` volume at `/data` in the UI application and all dynamically created workers. Use paths such as `/data/outbound` for directory channels and `/data/ssh` for SFTP keys and known-host files. Files survive worker removal and scaling. Standalone workers need their own shared mount configuration; `M3_WORKER_FILES_VOLUME` configures the named volume attached to managed workers. `docker compose down --volumes` removes this storage as well as the database and broker volumes.

Migration `009-outbound-delivery.sql` adds attempt scheduling, claim tokens, submission deduplication and larger channel-name columns. Rebuild the common application image and start the UI before worker containers so Liquibase applies the schema upgrade.

## Inbound routing and upgrades

An INBOUND rule may select a destination too. Its finalization stores the outbound copy and a delivery job with the prepared body and destination ID atomically. Delivery does not run its transformations a second time. A finalization error marks the input and its job `FAILED`, with the error recorded on the job.

ROUTE destinations use a database foreign key; renaming the channel keeps the destination intact. Migration 012 backfills existing links and adds receiver heartbeat and container metrics tables. Migration 013 restores jobs for old pending outbound copies with an unambiguous routing rule. Copies whose owner cannot be identified become `FAILED` with `deliveryError` metadata; resubmit these with an explicit rule ID.

The Workers page opens per-container metrics through its chart button: current, peak and mean CPU/RAM over retained samples (30 days), container ID, name and last sample time. Historical stopped containers remain visible and are marked stale. Pool metrics remain aggregate figures.

## Conditions and worker maintenance

Conditions follow creation order. Each connector joins its condition to the preceding expression; the first connector is ignored. AND binds more tightly than OR. Missing fields do not match, numeric comparisons use decimal numbers, and non-numeric fields do not satisfy GREATER/LESS. Invalid regular expressions and numeric comparison constants are rejected when saving a condition.

The Docker controller inspects the configured image ID and replaces one outdated worker per pool reconciliation. New workers are pinned to that inspected image ID. The Docker API proxy needs `IMAGES=1` (included in Compose). Rebuild the image and recreate the proxy/UI services to enable this update behavior. A single-replica pool briefly stops during replacement; transport delivery retains its documented at-least-once behavior.

Pools with rules or job history cannot be deleted. The UI validates deletion before stopping containers. Failed reconciliation of one pool does not stop reconciliation of the others; managed containers belonging to deleted pools are cleaned up on a later reconciliation.

## Forwarding a stored message to another channel

Open an inbound or outbound message and click **Forward to channel**. Select the destination channel and an enabled outbound rule for it, then click **Create copy and send**. The original message, status, body and metadata remain unchanged. M3 creates an independent OUTBOUND record with an exact snapshot of the original bytes, charset, charset origin, payload format, media type and metadata, plus a durable job in the selected rule's pool. The copy stores `sourceMessageId` referencing the source record; the detail dialog offers **View original**. Delivery bookkeeping (`outboundRuleId`, `m3MessageId`, `m3RuleId`) identifies the new copy and rule.

The selected rule controls filtering, transformations and delivery. A delivery failure affects the copy only; it can be retried using the existing retry operation. The original remains available even after successful delivery. All supported outbound transports use this same flow. Forwarding also works when the original has an unknown charset; a rule requiring text decoding still needs a known charset.

External services can use:

```http
POST /api/v1/messages/42/forward
Content-Type: application/json
Idempotency-Key: forward-42-to-rule-12

{"ruleId":12}
```

The response is `202 Accepted`, includes the new message and its ID, and provides its `Location`. `GET /api/v1/rules/forwarding` lists enabled, assigned rules and their available destination channel IDs/names. The optional idempotency key is scoped to the rule and shared with outbound submission: repeating the same forwarding operation returns the same copy; reusing a key for a different source message or ordinary submission returns `409 Conflict`. The UI uses one key per forwarding dialog to prevent duplicate submission on repeated clicks. Copy creation, metadata, delivery job and idempotency receipt commit together; forwarding does not delete or update the source record.
