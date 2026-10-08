# Consistent PostgreSQL and S3 recovery

Back up the catalog and cold bodies as one recovery point. `Backup-M3.ps1` temporarily stops every explicitly named M3 UI/worker container, dumps PostgreSQL, mirrors the bucket with the externally configured MinIO `mc` client, verifies each live archive key against its catalog SHA-256, writes a manifest with dump/object checksums, and restarts only previously running containers. An incomplete run has no complete manifest and cannot be restored by the companion script. Pass **all** writers, including additional UI instances and manual workers. External database writers must also be quiesced separately.

Requirements: PowerShell, Docker CLI, `mc` on PATH, a configured `mc` alias, PostgreSQL container permissions and an archive bucket. Both scripts use fresh destinations, fail on existing restore tables/objects, avoid password command-line arguments and do not overwrite or recursively delete existing data. Scripts do not back up encryption keys, customer credentials or issuer private keys; keep those in a separately protected recovery store. A database dump contains customer data and encrypted endpoint configuration and needs restricted access and encrypted storage.

```powershell
./scripts/Backup-M3.ps1 -DatabaseContainer m3-db-1 -ApplicationContainers m3-app-1,m3-worker-default-1 -Database m3 -ArchiveLocation backup-source/m3-messages -Destination D:\Backups\m3-2026-10-07
./scripts/Restore-M3.ps1 -Snapshot D:\Backups\m3-2026-10-07 -DatabaseContainer m3-recovery-db-1 -Database m3_recovery -ArchiveLocation recovery/m3-messages
```

The restore destination must be an empty database and empty precreated bucket, isolated from live M3. Verify encryption-key recovery, original/masked hot and cold reads, prepared delivery versions, processing attempts/history, pending outbox publication and worker admission before switching traffic. Check broker recovery separately: outbox rows provide at-least-once republication, so consumers must deduplicate by event ID and business idempotency key.

Test recovery regularly in an isolated environment and record measured recovery time/data-loss window. These scripts create a quiesced logical snapshot, not continuous PostgreSQL point-in-time recovery or a broker backup. Retain PostgreSQL WAL and object versions separately if your recovery objectives require them. Restore preserves installation UUID and its license; never activate the original and restored installation concurrently. Obtain an independent identity/entitlement for an independent clone. After restore, delayed callbacks from old attempts are rejected by the persisted attempt/version contract.

Failure recovery: leave an incomplete snapshot as evidence; rerun backup into a new directory. If restore fails, keep M3 stopped and provision fresh empty targets for the next attempt. No automatic destructive rollback is attempted.

After restore, stale cold-cleanup tasks referring to live catalog objects are cancelled under a catalog row lock. Those objects remain available, and a later catalog deletion enqueues cleanup again. Cancelling stale tasks prevents them from blocking cleanup of unrelated orphan objects.
