# Kubernetes deployment

`community.yaml` is a deployment template, not a verified production installation. It requires PostgreSQL and a storage class providing `ReadWriteMany`; `ReadWriteOnce` is insufficient when several Community pods must process the same originals.

Create `m3-database` and `m3-community-key` Secrets outside this manifest. The latter contains the backup-critical `M3_SECRET_KEY`; never add either Secret to source control.
