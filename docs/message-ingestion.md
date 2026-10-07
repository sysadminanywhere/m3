# Message ingestion and external consumers

API previews and downloads are masked by default. Exact-byte consumers must have `PAYLOAD_ORIGINAL` authority and request `original=true`, for example `/api/v1/messages/{id}/payload?original=true`. Optional tiering can move completed bodies to object storage while retaining the catalog. See [troubleshooting, storage and Scale](troubleshooting-storage-scale.md) for the access policy and upgrade contract.

The ingestion contract is:

1. Receive data from a configured source.
2. Store the payload, source identity and metadata in PostgreSQL.
3. Store a receipt event in the outbox in the same transaction.
4. After commit, publish `message.received` to the broker.
5. A consumer reads the message and metadata by `messageId` through the HTTP API.

Rule jobs are also created during ingestion. Notifications do not depend on a rule matching or completing. The original inbound payload remains available independently of rule results. Existing records are not announced retrospectively.

## Sources and adapters

Manage endpoint descriptions in **Settings → Channels**, and loading in **Settings → Rules**. DIRECTORY, FTP, SFTP, KAFKA and RABBITMQ have built-in receivers. An enabled INBOUND rule with a worker pool starts its own receiver in that pool's worker containers. A channel alone never starts loading. The worker registry reconciles committed rules every three seconds, stops obsolete receivers and retries failed startup. Editing, disabling, deleting or moving a rule changes its receiver; every ingestion rechecks the rule snapshot so stale receivers cannot accept new deliveries. The UI does not run native receivers.

The rule's **Loading settings** form owns `pollingInterval`, `filePattern`, `recursive`, `minFileAgeMs`, `deleteAfterProcessing`, `deleteRemoteFiles`, Kafka `groupId` and `autoOffsetReset`. Channels retain addresses, paths, topics/queues, authentication, transport security and charset declarations. Existing channel loading settings move to existing INBOUND rules in migration 011. Channels with no such rule remain idle until a rule is created.

Each receiver submits only to its owning rule. Deduplication keys include that rule ID, so separate rules may ingest the same retained file independently. File polling uses a PostgreSQL session lock per rule to coordinate replicas, while each delivery commits before deleting the file. Kafka replicas use the rule's group ID (default `m3-rule-<id>`); RabbitMQ replicas compete for the configured queue. Rules sharing a Kafka group or RabbitMQ queue share its deliveries. For independent receipt use distinct groups/queues. Deleting files requires a single active loading rule for the channel. Shared file sources must retain their files; conflicting legacy rules are stopped with a visible policy error.

New native adapters implement `InboundSourceFactory` as Spring beans and send data through `SourceDeliveryService`. Adding a new source type also requires a `ChannelType` value, a configuration form and a new migration extending the database type constraint. External adapters can submit through `MessageGateway` within the app or the HTTP ingestion endpoint externally.

| Source | Required channel configuration | Optional settings (loading policy belongs to the rule) |
| --- | --- | --- |
| DIRECTORY | `directoryPath` | `filePattern`, `recursive`, `pollingInterval`, `minFileAgeMs`, `deleteAfterProcessing` |
| FTP | `host`, `username`, `remoteDirectory` | `port` (21), `password`, `passiveMode`, `filePattern`, `pollingInterval`, `minFileAgeMs`, `deleteRemoteFiles` |
| SFTP | `host`, `username`, `remoteDirectory`, `knownHostsPath` | `port` (22), `password`, `privateKey`, `privateKeyPassphrase`, `filePattern`, `pollingInterval`, `minFileAgeMs`, `deleteRemoteFiles`, `allowUnknownKeys` |
| KAFKA | `bootstrapServers`, `topic`, `groupId` | `autoOffsetReset`, `keyDeserializer`, `valueDeserializer`, `payloadType`; native client properties prefixed with `kafka.` |
| RABBITMQ | `host`, `username`, `queue` | `port` (5672), `password`, `virtualHost`, `declareQueue`, `exchange`, `routingKey` |

File polling defaults to 5,000 ms with a minimum file age of 1,000 ms. Hidden files are excluded. Files that change during a read are retried. Producers should write or upload under a hidden or nonmatching temporary name, then rename the completed file into the matching name. Files are removed only after successful storage and only if their size and modification time remain unchanged. A failed file does not block other files in the directory. FTP uses binary transfer; SFTP verifies OpenSSH host keys against `knownHostsPath` unless `allowUnknownKeys` is explicitly enabled. Paths in connection settings refer to the application's filesystem; mount source directories, private keys and known-host files into worker containers when deploying with Compose.

Kafka disables auto-commit and acknowledges each offset only after database commit. RabbitMQ uses manual acknowledgements and requeues failed storage attempts. The receiver can optionally declare a durable queue and a durable topic exchange binding with `declareQueue=true`; otherwise it consumes an existing queue.

`source_delivery_receipt` deduplicates file deliveries using path, modification time and content hash; Kafka uses source/topic/partition/offset; RabbitMQ uses the producer's `messageId`. RabbitMQ producers should set a unique stable message ID for reliable deduplication. Without it, redelivery after a commit followed by an acknowledgement failure can create another stored message. Deduplication records remain after message deletion, so restarting a receiver does not restore deliberately deleted messages.

New connectors must use this common ingestion path. Scalar transport headers and the explicit `metadata` map are stored; internal Spring IDs, timestamps and channel objects are excluded. Channel connection properties and credentials are not returned by the source listing or published in events.

Original payload bytes are stored as PostgreSQL `bytea`, together with optional charset and its origin. Byte payloads use Base64 only in JSON API responses (`encoding=base64`). Files also retain name, size and path. Limits for new messages: 1,000,000 payload bytes, 100 metadata key characters and 1,000,000 metadata value characters. See [message encoding](message-encodings.md) for declarations, migration and raw downloads.

## HTTP API

- `GET /api/v1/sources`: list inbound endpoint ID, name, type and availability flag. Runtime ownership is `RULE_MANAGED`; Channels no longer report a locally running receiver, since loading happens in worker containers.
- `POST /api/v1/channels/{channelId}/messages`: submit with required `ruleId` for an active INBOUND rule using that source; returns `201`, `Location` and the saved message ID. This explicitly selected rule receives the job.
- `GET /api/v1/messages/{messageId}`: return payload, metadata, source/target, timestamps, direction and current status; returns `404` for an unknown or deleted message.

Example ingestion request:

```json
{
  "ruleId": 12,
  "payload": "{\"orderId\":123}",
  "payloadType": "application/json",
  "metadata": {"externalId": "order-123"}
}
```

Custom metadata cannot override transport headers such as `sourceSystem`, `channelName`, `sourceChannelId` or `payloadType`. Responses contain metadata as a list of `{ "key": "...", "value": "..." }` records. Source adapters acknowledge upstream reception only after ingestion succeeds. HTTP adapters can send `Idempotency-Key` (1–200 characters) to retrieve the original message ID on retries, including concurrent requests. The key is scoped to the source and must identify the same delivery. Reusing it returns the original message; if that message was deleted, the API returns `410`. Submissions without a key create separate messages.

## RabbitMQ receipt events

Compose includes RabbitMQ with persistent storage. Defaults: durable topic exchange `m3.events`, routing key `message.received`, durable queue `m3.message.received`. AMQP port: `5672`; management UI: `15672`. Development credentials: `m3` / `m3`; configure Compose with `RABBITMQ_USER` and `RABBITMQ_PASSWORD`.

```json
{
  "eventId": "d85bdf55-366d-4b67-bdfb-8af1e5746bad",
  "eventType": "message.received",
  "schemaVersion": 1,
  "messageId": 42,
  "receivedAt": "2026-10-03T18:00:00Z",
  "resourcePath": "/api/v1/messages/42"
}
```

Consumers combine `resourcePath` with the reachable M3 API base URL. Consumers sharing a queue share work; services needing every event should each bind their own durable queue to `m3.events` using `message.received`.

Publication uses persistent messages, broker confirms and mandatory routing. The dispatcher reads committed rows using `FOR UPDATE SKIP LOCKED`. Unconfirmed or unroutable events remain pending and retry after 10 seconds. A crash after broker acknowledgement but before the database commit can repeat an event with the same `eventId`. Consumers deduplicate by `eventId` and acknowledge after fetching and handling the message. Message availability follows the application's retention/deletion policy.

Inspect publication state in `message_receipt_outbox`: `published_at`, `attempts`, `last_error`, `next_attempt_at`. Both the UI and workers publish receipt events using row locks, so publication continues when the UI stops. Another broker can implement `MessageReceivedPublisher` and replace the RabbitMQ configuration without changing ingestion.

Local configuration: `M3_BROKER_RABBIT_HOST`, `M3_BROKER_RABBIT_PORT`, `M3_BROKER_RABBIT_USERNAME`, `M3_BROKER_RABBIT_PASSWORD`, `M3_BROKER_RABBIT_VIRTUAL_HOST`, `M3_BROKER_RABBIT_EXCHANGE`, `M3_BROKER_RABBIT_QUEUE`. Outbox poll delay: `M3_RECEIPTS_POLL_DELAY_MS`, default 1000.

## Verification

`./mvnw test` includes integration tests using isolated PostgreSQL, RabbitMQ and Kafka Docker containers plus embedded FTP/SFTP servers. The tests cover binary payloads and metadata through the HTTP API, storage failures and upstream redelivery, file deletion after commit, message-ID deduplication, application restart with a retained source file, broker outage followed by application restart, concurrent HTTP retries, and rollback when the outbox insert fails. Docker is required to run these integration tests; they are skipped when Docker is unavailable.

## Receiver status

`GET /api/v1/loading-rules` returns each loading rule and its worker states. Workers persist heartbeats every 10 seconds; an absent heartbeat for 35 seconds is marked `STALE`. The Rules screen refreshes the receiver status every 10 seconds and shows the error in its tooltip. `/api/v1/sources` reports the combined state of active loading rules, or `IDLE` when none exist. Stopping the UI leaves workers running.

## Repeated HTTP requests

Migration 014 adds a digest of HTTP request data to the delivery receipt. For new receipts, reusing `Idempotency-Key` with a different payload, payload type or metadata returns `409 Conflict`. Native transport deduplication continues to use the upstream delivery identity, allowing protocol redelivery headers to change. Legacy receipts without a digest retain their previous deduplication behavior.

## Message operations and native metadata

`GET /api/v1/messages/{id}/jobs` reports rule jobs, pool, attempts, scheduling and errors. `POST /api/v1/messages/{id}/retry` retries failed jobs when their rules are enabled and assigned to a pool. The message details dialog exposes these operations. Messages with pending or processing jobs, or unpublished receipt notifications, cannot be deleted.

Kafka stores key and value bytes directly, including null values marked as tombstones. `kafkaHeadersJson` preserves repeated headers, order and null values as typed entries; binary values use Base64. RabbitMQ uses `amqpHeadersJson` and `amqpPropertiesJson` to preserve protocol headers and basic properties. Unsupported native charset declarations are kept as metadata while the original body remains stored; text rules require a known charset. File endpoints may set `payloadType`, for example `application/json`, without changing the stored bytes.

## Removing source files

For DIRECTORY, FTP and SFTP loading rules, **Delete source file after loading** is a dedicated checkbox in both the rule edit dialog and its configuration page. Saving maps it to `deleteAfterProcessing` for local directories or `deleteRemoteFiles` for FTP/SFTP. Existing saved settings populate the fields automatically. Polling interval, file name pattern, minimum file age, subdirectory traversal, deletion, Kafka consumer group and initial offset policy are edited through individual fields shown for the selected source type. If enabled, the source file is removed after durable ingestion commits, independently of asynchronous delivery; the original message bytes and metadata remain in the database. If disabled, the file remains in the source folder. Shared file sources cannot enable deletion while multiple loading rules use the same channel.

## Editing connection settings

Channel connection fields and additional properties no longer require JSON editing. Additional connection properties are editable key/value rows with **Add property** and a remove button. Values may contain multiple lines. Saving rejects duplicate or empty keys and properties that belong to the dedicated connection fields or the rule. Existing properties load into the rows without changing their stored representation.
