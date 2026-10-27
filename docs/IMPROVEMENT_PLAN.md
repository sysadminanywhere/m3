# M3 improvement plan

The goal is to make a first message flow easy to configure and dependable to operate. Complete each phase with tests and documentation; validate assumptions against the current implementation before changing behavior.

## 1. Delivery reliability and recovery

- [ ] Exercise worker, PostgreSQL, broker and destination failures, including recovery and duplicate risks for each transport.
- [ ] Document the actual delivery guarantees, uncertain outcomes and recovery procedures.
- [x] Cover expired and stale claims, retry limits and destination failures with meaningful tests.

## 2. Troubleshooting

- [ ] Make failed and stalled work, its rule, destination, attempt history and recovery actions easy to find.
- [ ] Review overview alerts for worker health, queues and storage; verify operator permissions and uncertain-delivery confirmation.

## 3. First message flow

- [ ] Design and implement a guided setup for a source, destination, rule and worker, with validation and a test message.
- [x] Verify the first directory flow on a clean isolated installation and smoke-test a clean Compose build. Managed-worker isolation was checked separately with two disposable Compose projects.

## 4. Rule editor

- [ ] Validate configuration and explain incompatible data types before execution.
- [x] Show the result of individual steps in preview and clarify the first-filter limitation or implement multiple filters.

## 5. Operations and upgrades

- [x] Rehearse backup and restore, including the encryption key, on a clean installation; record the remaining recovery checks in the runbook.
- [ ] Verify schema upgrades and worker shutdown/restart; improve the runbooks and readiness checks.

## 6. Performance and scaling

- [ ] Measure throughput, latency, queue growth and storage use under representative workloads.
- [ ] Use results to prioritize scaling, retention and archival improvements.

## 7. Understand adoption and usage

**Questions:** How is an individual installation used, which capabilities are configured, and where do users struggle to set up or operate a flow? Local metrics do not tell the M3 maintainers how many other installations exist.

**Decision:** Do not connect Google Analytics. Keep the usage summary local. No remote reporting service or collection of installation data is currently planned; any future approach requires a separate decision.

**Implementation:**

- [ ] Extend the installation-local usage summary with first-flow progress and decide whether durable aggregates are needed beyond retained message history. The current summary is derived locally from retained records and current configuration; it does not use or modify security audit records.
- [ ] Consider voluntary feedback for qualitative context. The local summary cannot measure installations owned by other users and must not imply that it can.

## Progress

- Initial review: outbound retries, stale claim tokens, uncertain-delivery confirmation and an operator overview already exist. Extend and verify them rather than rebuilding them.
- First increment: clarify uncertain-delivery behavior and cover stale claims and exhausted retries. Full transport failure testing remains open.
- PostgreSQL integration coverage exercises real repository recovery for expired claims, including uncertain delivery, safe replay, fresh claims and pool isolation. Message details show the next scheduled retry for previously attempted pending jobs.
- Rule preview follows ID-delivery behavior: it skips body transformations, applies metadata actions and explains that the receiver gets the stored message ID. It also shows intermediate payload and metadata after each completed action using the worker's execution order.
- Rule details display readiness of the saved rule, source and destination and report whether its pool has a recent worker heartbeat. This does not test channel connectivity; guided setup and a clean-install walkthrough remain open.
- Corrected the architecture guide to describe worker-hosted sources, heartbeat monitoring and the distinction between safe claim recovery and uncertain outbound delivery.
- Receipt outbox coverage includes a RabbitMQ outage and application restart, plus PostgreSQL verification that retry waits until due and preserves the event ID.
- Administrators can open a first-flow guide from the overview with links to channels, rules, workers and message lists. It does not test connectivity.
- Rule details also show the existing receiver health and diagnostic message for enabled inbound rules, separately from the worker heartbeat.
- Message job details explicitly mark uncertain delivery and let administrators open the associated rule; other roles see its ID without an administration link.
- PostgreSQL integration coverage checks absent, stale, running and retrying receiver reports, including stale reports alongside a current worker.
- Added an administrator-only [local usage summary](local-usage.md) for retained message counts and current enabled configuration. It sends no data.
- On a temporary clean database, browser checks covered the guide, local summary, receiver readiness and step preview; regression tests verify admin access and viewer denial for the two new pages. End-to-end guided setup and test-message delivery remain open.
- Corrected the outbound recovery guide: retries use frozen rule and endpoint configuration, while a new submission is needed to apply changed settings.
- Fixed search indexing of decoded text containing NUL: the projection replaces NUL with a space, preserving original bytes and allowing the rest of the batch to complete.
- The first-flow guide can create a disabled inbound directory rule from existing channels and a pool, then opens it for review. Browser verification confirmed the saved rule is disabled; creating channels and sending a test file are still manual.
- The first-flow guide rejects obvious identical source and destination paths to reduce accidental message loops; filesystem aliases still require operator verification.
- A fresh database, RabbitMQ, application and worker were started in disposable containers. An administrator created both directory channels and a rule in the UI, enabled it, placed a test file in the worker mount, and verified the inbound `LOADED` and outbound `SENT` records and matching output file.
- A separate clean Compose build started PostgreSQL, RabbitMQ and the application, applied 26 migrations and passed the administrator's browser smoke test. Worker reconciliation was disabled in this run to isolate other M3 containers on the shared Docker engine.
- Docker worker management scopes names, listing and cleanup to the database installation ID and uses a new marker that older controllers do not select. Legacy workers on the configured network block reconciliation rather than being adopted or removed. A shared-engine upgrade requires old workers to be removed using their owning installation's shutdown procedure.
- On a shared Docker engine, two disposable Compose projects with distinct installation IDs each created a scoped worker. Scaling one to zero removed only its own worker. An unlabelled legacy container on the other project's network blocked reconciliation without being changed; reconciliation resumed after it was removed. This used a test image built from the packaged JAR, not the release Dockerfile image.
- A disposable backup/restore rehearsal verified database and object checksums, installation identity, application stop/restart, an encrypted setting with the original key and an original archived payload read through the recovered API. The [runbook](backup-restore.md) records remaining recovery checks.
- Recorded [preliminary directory-flow measurements](performance-baseline.md) for a short burst and a paced 4 KiB workload, including throughput, queue sampling, latency, storage growth and output checks. Repeated longer workloads, other transports and resource peaks remain open.
