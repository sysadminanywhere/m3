# Original bytes and message charsets

Messages store `payload_bytes` in PostgreSQL `bytea`. Nullable `charset` identifies the text charset; `charset_source` records its origin. `payload_format` controls the compatible JSON representation (`TEXT` or `BASE64`). Base64 is an HTTP transport representation, not the database storage format. New writes do not populate the legacy `payload` text column.

## Ingestion

DIRECTORY, FTP and SFTP store original file bytes. Kafka requires `ByteArrayDeserializer` for message values so the original body reaches the database unchanged. RabbitMQ stores the original AMQP body. Charset selection uses an explicit declaration (Kafka `charset` header, RabbitMQ charset-valued `contentEncoding`, HTTP `charset` or metadata `charset`, or media type charset), then the channel's `charset` setting. A BOM supplies the charset when no declaration or channel default exists. Otherwise bytes remain stored with an unknown charset; no statistical detection or silent UTF-8 conversion occurs.

Charset sources include `REQUEST`, `PROTOCOL`, `CHANNEL`, `BOM`, `UNKNOWN` and `TEXT_UTF8`. Native bytes with a contradictory BOM remain saved with `BOM_CONFLICT`; text rules fail instead of silently interpreting conflicting declarations. API submissions reject conflicting declarations. Charset names use Java's supported canonical names. Text conversions report malformed input and unencodable characters. Binary and malformed text can be preserved as bytes; decoding is separate from storage.

Unicode string submissions are encoded using the declared charset, or UTF-8 if none is declared. This constructs bytes from the supplied string; it cannot recover the sender's previous byte representation. For exact byte preservation, submit Base64 and metadata `encoding=base64`:

```json
{
  "ruleId": 12,
  "payload": "<Base64 of the original Windows-1251 bytes>",
  "payloadType": "application/xml",
  "charset": "windows-1251",
  "metadata": {"encoding":"base64", "fileName":"order.xml"}
}
```

Use this body with `POST /api/v1/messages/outbound`. For inbound submissions to `POST /api/v1/channels/{id}/messages`, use the ID of an active INBOUND rule for that channel. `charset` is optional for binary data or unknown text. Metadata `charset` remains supported for existing clients; conflicting declarations return `400`. New payloads are limited to 1,000,000 decoded bytes; HTTP Base64 allows up to 1,333,336 characters. Invalid or unsupported charset names are rejected.

## Read API and UI

- `GET /api/v1/messages/{id}` returns metadata and the compatible payload representation, plus `charset`, `charsetSource`, `payloadSize` and `payloadEncoding` (`text` or `base64`). For byte submissions the JSON `payload` remains Base64, calculated from `bytea`.
- `GET /api/v1/messages/{id}/payload` downloads exact original bytes as `application/octet-stream`.
- `GET /api/v1/messages/{id}/text?charset=windows-1251` returns decoded text and the selected charset without modifying the record. Without the parameter it uses the stored charset; unknown charset returns `409`, decoding errors return `400`.

Inbound and outbound lists open the same detail dialog using **View** or a double click. Payload and metadata appear together. The dialog shows the stored charset, its origin and byte count. A selector changes only the text preview; strict decoding errors are shown explicitly. Preview is limited to 5,000 characters; download always returns complete saved bytes. Binary formats may not have a useful text representation. **Refresh** reloads the list and status filtering takes place before pagination.

## Rules and outgoing transports

Routing and header-only rules keep the body as bytes, even for malformed text. Conditions on payload, transformations and explicit re-encoding require a known charset and decode strictly; a leading BOM is excluded from JSON parsing while remaining in saved bytes. Set a channel charset before applying text or JSON transformations to an unknown text encoding.

Without a payload change, outgoing transports reuse original bytes, including BOM and line endings. Kafka's record value is the raw byte array, not Base64 and not an envelope. Kafka headers contain `m3MessageId`, `contentType`, charset when known, and scalar metadata encoded as UTF-8. RabbitMQ uses the original body plus properties/headers; files contain only payload bytes. Read associated metadata by message ID through the API.

Transformed text uses an explicit `outputCharset` header, which a rule can set through an `ENRICH` action, then the target channel's `outputCharset`, then the source charset or UTF-8 if unknown. An explicit rule `outputCharset` also requests re-encoding when text content stays the same. A target channel setting applies when the payload changes. Unrepresentable characters fail delivery rather than being replaced. Transformations may create a new BOM according to the chosen charset's encoder; unchanged forwarding preserves the original BOM. Prepared outgoing delivery plans retain transformed bytes across retries.

## Migration and deployment

Liquibase changeset `010-message-bytes` backfills existing messages. Records marked `encoding=base64` are decoded into bytea with origin `LEGACY_BYTES`; saved charset metadata is carried over. Ordinary text is converted to UTF-8 with origin `LEGACY_TEXT_UTF8`: its original byte encoding is unavailable. Invalid legacy Base64 remains recoverable as UTF-8 text with origin `LEGACY_INVALID_BASE64`. The legacy text column remains for diagnostics and does not receive new application writes. Existing prepared delivery plans are unchanged.

Rebuild the shared image. Stop old UI and worker versions before starting the new UI to migrate, then start new workers. Old versions cannot write the new schema: every new row requires bytes and format fields. Compilation alone does not validate a migration against PostgreSQL.
