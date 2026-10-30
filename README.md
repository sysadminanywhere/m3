# M3 Community v2

Stateless Java 21 service for accepting and executing published rule configurations. The service stores durable state in PostgreSQL and is independent of Enterprise.

```powershell
mvn test
mvn package
docker compose up --build
```

Configuration is validated and published through `/api/v2/configuration`; see `docs/v2/api-contract.yaml` and `docs/v2/configuration.example.json`.

Secrets use an external `M3_SECRET_KEY` (base64-encoded 32-byte key) or `M3_SECRET_KEY_FILE` containing that value. The environment variable takes precedence. Backup and restore requirements are in `docs/operations/backup-and-restore.md`.

For more than one Community replica, `M3_STORAGE_ROOT` must refer to the same shared volume or filesystem for every replica; container-local storage is not valid durable message storage.

Set `M3_EXECUTION_GROUP` when running multiple logical execution groups. A worker only claims jobs assigned to its group; jobs already created retain their original group.

Set `M3_API_TOKEN` in non-local installations. Community then requires the same `X-M3-Service-Token` on all non-health API calls; Enterprise sends it from `COMMUNITY_SERVICE_TOKEN`.

PostgreSQL integration tests are opt-in via `M3_TEST_POSTGRES_URL`. Use an isolated test server with an `m3test`/`m3test` role granted `CREATEDB`: tests create and remove randomly named databases from `template0` without changing the database named in the URL. Abrupt termination may leave a test database behind. The full-storage test additionally requires an isolated small tmpfs through `M3_TEST_INGEST_TMPFS`; do not point it at a shared or production filesystem.
