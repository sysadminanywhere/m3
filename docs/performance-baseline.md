# Performance baseline

These are repeatable orientation measurements for the Community v2 directory
transport. They are not capacity limits or an SLA.

## Disposable Compose run: 2026-10-30

- Environment: `docker compose -p m3-recovery`, PostgreSQL 16 Alpine and the
  production `Dockerfile` image built from the packaged JAR.
- Payload: 100 different binary files, approximately 4 KiB each.
- Flow: directory ingest, PostgreSQL-backed jobs, directory delivery.
- Result: 100 files delivered in 14.7 seconds.
- Integrity: SHA-256 sets matched 100/100 between input and output.
- Flyway: migrations V1, V2 and V3 applied successfully.
- Health: `/api/v2/health/live` and `/api/v2/health/ready` returned HTTP 200.

The first exploratory run used identical file contents and correctly produced
one accepted message because `(source_id, checksum)` is the deduplication key.
It is excluded from the baseline; benchmark inputs must have distinct content.

Throughput depends on filesystem, PostgreSQL latency, polling intervals,
payload size, and recipient behavior. Repeat the measurement on the target
deployment before capacity planning.
