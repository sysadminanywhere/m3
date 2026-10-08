# Message troubleshooting, storage and Scale

The complete per-recipient callback/replay contract, deadlines, service accounts, operational metrics and extended retention are described in [external processing and operations](external-processing-operations.md). See [consistent backup/restore](backup-restore.md) and [distribution instructions](code-distribution.md) for recovery and issuer workflows.

The message lists search by ID, correlation ID, status, endpoints, dates and a literal fragment of the **masked hot payload**. Filters remain shareable. Payload indexing runs asynchronously on a separate maintenance scheduler: newly accepted messages may take a few seconds to appear in content searches. ID lookup is immediate. Invalid links display no results.

Message details include a routing timeline, linked originals/copies, stored payload and immutable prepared delivery versions. JSON/XML previews are formatted. A prepared delivery contains the actual transport body and metadata before external I/O; its presence alone does not mean the receiver accepted it. Check `DELIVERY_STARTED`, completion and uncertainty events. A configuration hash identifies the frozen job configuration; retries continue using that snapshot. Timeline history begins at migration 022. Existing messages receive a baseline marker; earlier attempts cannot be reconstructed.

**Retry processing** requeues failed work and records the actor without erasing earlier events. **Forward to channel** creates an independent copy using an enabled outbound rule. Neither operation rolls back business effects at the receiver. An uncertain delivery requires explicit acknowledgement; a repeat can create a duplicate.

## Masking and original access

Masking runs on the server for previews, metadata, downloads, API responses and the content search projection. Transport bytes are preserved. By default, fields named `password,secret,token,authorization,phone,mobile,cardNumber,pan,cvv,ssn` are hidden recursively, case-insensitively. Card/phone-like numeric sequences in text are also hidden; this conservative heuristic may mask business identifiers. Configure field names and explicit paths for your actual schemas.

```properties
m3.masking.fields=password,token,phone,cardNumber
m3.masking.json-paths=$.customer.privateCode;$.customers[*].account
m3.masking.xml-paths=//customer/privateCode;//payment/@account
m3.security.original-readers=admin
```

JSON paths support `$.field`, nested fields and `[*]` array traversal, not arbitrary JSONPath expressions. XML paths use XPath and are separated by semicolons. XML external entities/DTDs are disabled. Invalid structured payloads and undecodable bytes produce `[REDACTED]`. Add sensitive metadata keys to `m3.masking.fields`. Free text cannot reliably identify every secret: use structured schemas and explicit masking rules. Original job error details are hidden by default because receivers may echo payloads.

An account listed in `m3.security.original-readers` receives the separate `PAYLOAD_ORIGINAL` authority. Administrative rights alone do not grant it. The UI still opens a masked view; **Show original (access is audited)** explicitly reveals original bodies, metadata and timeline errors. Every original preview/download is audited. Removing that account from the property and restarting revokes the permission. Search always uses the masked projection, even for original readers. A policy change invalidates old content-search projections while hot rows are rebuilt.

API responses and downloads are now masked by default. Machine consumers needing exact bytes must authenticate with an original-reader account and explicitly use `original=true`:

- `GET /api/v1/messages/{id}?original=true`: original stored representation.
- `GET /api/v1/messages/{id}/payload?original=true`: exact stored bytes.
- `GET /api/v1/messages/{id}/text?original=true&charset=UTF-8`: decoded original text.
- `GET /api/v1/messages/{id}/history`: masked timeline; add `original=true` to inspect full errors with permission/audit.
- `GET /api/v1/messages/{id}/related`: original/copy links.
- `GET /api/v1/messages/{id}/versions`: stored/prepared body selectors.
- Add `jobId` to `/payload` or `/text` for a prepared delivery version.

Without `original=true`, exports are masked UTF-8 text. `payloadSize` describes the stored original, not the masked export. Responses expose `archived` and `masked`. ID-mode deliveries now advertise an original payload path with `?original=true`; receivers must have the original-read authority. Existing ID-mode delivery plans already saved before upgrade retain their old URL; update consumers to request `original=true` explicitly.

## Storage policy

Primary acceptance remains transactional: original bytes, metadata, job and receipt outbox commit before the source is acknowledged. Routing is already asynchronous through durable PostgreSQL jobs. In-memory Spring events are not used as a substitute for durable acceptance.

Inbound business status defaults to LOADED, or the rule's explicit initial status. External services report processing progress/results through `PATCH /api/v1/messages/{id}/status`; routing and retry preserve that status. See [the callback contract](message-ingestion.md#http-api). Unfinished and error statuses stay hot; a successful callback alone does not make active routing jobs eligible for tiering.

`M3_STORAGE_MODE` selects `KEEP` (default), `ARCHIVE` or `DELETE`. `M3_STORAGE_HOT_DAYS` defaults to **7**. Age is measured from completion, not acceptance; pending, failed, active and unpublished-receipt messages remain hot. KEEP preserves existing deployments. ARCHIVE/DELETE are explicit opt-ins; legacy `M3_RETENTION_MESSAGES_DAYS` applies only in KEEP mode and excludes cold records.

For S3/MinIO, create the bucket and grant the application's credentials object read/write access:

```properties
m3.storage.mode=ARCHIVE
m3.storage.hot-days=7
m3.archive.endpoint=https://s3.example.com
m3.archive.bucket=m3-messages
m3.archive.region=us-east-1
m3.archive.access-key=<access key>
m3.archive.secret-key=<secret key>
```

Compose passes the corresponding `M3_STORAGE_*` and `M3_ARCHIVE_*` environment variables. Use an endpoint reachable from the application container. Credentials are configuration secrets; do not commit them. Workers use KEEP mode and do not need archive write credentials.

Archive jobs are durable and retried. The maintenance process snapshots original and prepared delivery bodies, uploads an envelope to `messages/{installationId}/{messageId}.json`, reads it back and verifies SHA-256, then conditionally clears hot bytes and completed delivery plans. Network I/O holds no database row locks. Failed uploads/checks retain hot data. Archived reads verify checksum, message identity and size. The envelope size limit is 64 MiB; larger envelopes stay hot and the archive job reports failure. The catalog, lineage, correlation ID and routing history remain in PostgreSQL; arbitrary body search only covers hot rows. Archive mode does not impose cold-object expiration: preserve the bucket for as long as the catalog needs replay, and back up both together.

DELETE removes eligible messages and their jobs/metadata/history. Linked source messages remain until their copies are deleted. Archive mode preserves linked source catalogs. Deleting an archived catalog queues its cold object for durable cleanup; failed cleanup retains a retry record. Optional catalog/error/history policies are documented in the operations guide. Configuration-audit retention remains a separate policy. This is operational audit history, not a tamper-proof compliance certification.

## Community and Scale

Community includes message troubleshooting, masking, durable delivery, one worker pool and **one concurrently executing worker slot**. A worker runs at most one inbound/outbound execution at a time. Idle processes and source intake do not consume an execution slot; additional manual processes wait for a slot. This budget counts execution capacity, not CPU cores or messages. Scale licenses increase the installation-wide slot budget, allow additional pools and optionally enable autoscaling.

Pool minimums must fit the global license budget. Fixed desired counts and autoscaling share remaining capacity. Lower **Capacity priority** values receive capacity first; equal-priority pools receive extras in rounds. Maximum values are ceilings, not reserved capacity. Workers shows licensed use, allocated targets and backlog. The License page shows the installation ID, entitlement and expiration, and accepts a signed document. The admin-only `/api/v1/license` supports GET, PUT of the signed JSON, and DELETE; mutations require the existing `X-M3-Request: 1` header.

Licenses are verified offline with Ed25519. Configure the issuer's Base64 X.509 public key as `M3_LICENSE_PUBLIC_KEY` on the UI and every manual worker. Managed Docker workers receive it automatically. The signed license is stored in the shared database and tied to its generated installation UUID. The issuer private key never belongs in a customer installation.

Issuer workflow, on the issuer's computer only:

```powershell
java ../m3-scale/scripts/LicenseIssuer.java keygen C:\Secure\m3-issuer
java ../m3-scale/scripts/LicenseIssuer.java sign C:\Secure\m3-issuer\issuer-private.key entitlement.json license.json
```

Example entitlement (replace installation ID and dates):

```json
{
  "installationId": "00000000-0000-0000-0000-000000000000",
  "customer": "Example team",
  "maxWorkers": 8,
  "autoScale": true,
  "externalAuthentication": false,
  "validFrom": "2026-10-07T00:00:00Z",
  "expiresAt": "2027-10-07T00:00:00Z"
}
```

Protect issuer files with OS access controls and offline backups. License payload bytes are signed exactly as supplied. Applications warn during the last 30 days and retain capacity during a 14-day grace period. After expiry or license removal, capacity returns to one; active work drains and no new over-budget work starts. Existing pools/queues are retained and the single slot rotates among pools with work each minute. Docker workers selected for removal are marked draining before stopping, coordinated with slot admission under a database lock. A crashed worker's slot expires after 90 seconds; active leases renew every 10 seconds on a separate scheduler. Operational lease checks do not replace transport idempotency or exactly-once delivery guarantees.

**Licensing:** the [English draft agreements](licenses/README.md) define Community and commercial Scale terms for first publication. Community permits internal business use without code modification or redistribution of customer builds. Third-party components retain their own licenses. Scale documents carry an optional `externalAuthentication` right (missing means false), subject to signature, installation and validity checks. Future connectors must enforce it at login and during external sessions. Keycloak/AD connectors are not implemented yet. Runtime controls enforce entitlements in the supplied application but are not a guarantee against source tampering.

## Upgrade and validation

Deploy the UI first to apply migrations 022–026, then restart **all workers** with the matching image. Older workers do not enforce slot admission or record all new snapshots. Callback consumers must adopt the mandatory attempt/version contract. Test machine consumers with the new default masking before rollout. Hot history/search backfills are asynchronous. No migration changes existing pool targets or silently deletes business messages.

Unit tests cover JSON/XML masking, signature tampering, installation binding, grace periods and global allocation. PostgreSQL/MinIO integration tests cover archive restore, checksum failure, preserved attempts, masked indexing and concurrent manual-worker admission. `MessageIngestionIntegrationTest` verifies API/download masking, original-read permissions/audit and unchanged original bytes in addition to transport regressions. MinIO tests pin a known test image digest; override `-Dm3.test.s3-image=<compatible test image>` if your registry differs. This test fixture is not a recommendation for a production object-store version.
