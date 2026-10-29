# M3 Community v2

Stateless Java 21 service for accepting and executing published rule configurations. The service stores durable state in PostgreSQL and is independent of Enterprise.

```powershell
mvn test
mvn package
docker compose up --build
```

Configuration is validated and published through `/api/v2/configuration`; see `docs/v2/api-contract.yaml` and `docs/v2/configuration.example.json`.

Secrets use an external `M3_SECRET_KEY` (base64-encoded 32-byte key). Backup and restore requirements are in `docs/operations/backup-and-restore.md`.

For more than one Community replica, `M3_STORAGE_ROOT` must refer to the same shared volume or filesystem for every replica; container-local storage is not valid durable message storage.

Set `M3_API_TOKEN` in non-local installations. Community then requires the same `X-M3-Service-Token` on all non-health API calls; Enterprise sends it from `COMMUNITY_SERVICE_TOKEN`.
