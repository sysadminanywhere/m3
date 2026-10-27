# M3 improvement plan

The goal is to make a first message flow easy to configure and dependable to operate. Complete each phase with tests and documentation; validate assumptions against the current implementation before changing behavior.

## 1. Delivery reliability and recovery

- [ ] Exercise worker, PostgreSQL, broker and destination failures, including recovery and duplicate risks for each transport.
- [ ] Document the actual delivery guarantees, uncertain outcomes and recovery procedures.
- [ ] Cover expired and stale claims, retry limits and destination failures with meaningful tests.

## 2. Troubleshooting

- [ ] Make failed and stalled work, its rule, destination, attempt history and recovery actions easy to find.
- [ ] Review overview alerts for worker health, queues and storage; verify operator permissions and uncertain-delivery confirmation.

## 3. First message flow

- [ ] Design and implement a guided setup for a source, destination, rule and worker, with validation and a test message.
- [ ] Verify the complete first-run experience on a clean installation.

## 4. Rule editor

- [ ] Validate configuration and explain incompatible data types before execution.
- [x] Show the result of individual steps in preview and clarify the first-filter limitation or implement multiple filters.

## 5. Operations and upgrades

- [ ] Rehearse backup and restore, including the encryption key, on a clean installation.
- [ ] Verify schema upgrades and worker shutdown/restart; improve the runbooks and readiness checks.

## 6. Performance and scaling

- [ ] Measure throughput, latency, queue growth and storage use under representative workloads.
- [ ] Use results to prioritize scaling, retention and archival improvements.

## 7. Understand adoption and usage

**Questions:** How many installations are active, which versions and capabilities are used, and where do users struggle to set up or operate a flow? Existing audit records and Prometheus operations metrics help an installation's operator, but do not by themselves tell the M3 maintainers about other installations.

**Decision:** Use Google Analytics 4 (GA4) for product analytics. Ask the installation administrator whether to enable reporting; do not send usage data before an affirmative choice. GA4 reports represent only installations that opted in, not the entire user base.

**Implementation:**

- [ ] Extend the installation-local usage summary with first-flow progress and decide whether durable aggregates are needed beyond retained message history. The current summary is derived locally from retained records and current configuration; it does not use or modify security audit records.
- [ ] Ask an administrator to enable or decline an optional, reviewable periodic report. Keep remote reporting disabled by default; explain exactly what is sent, its destination (M3's collection endpoint and Google Analytics), and frequency. Let administrators preview, enable, disable and delete pending reports. Local metrics must work without external connectivity.
- [ ] For reporting, use a random, resettable installation identifier and coarse daily or weekly aggregates: application version and edition, active period, configured transport types, feature counts, message-volume buckets and aggregated error categories. Exclude payloads, message metadata, credentials, channel names, addresses, usernames and raw exception text. Define retention, access and deletion policies for both sender and receiver before launch.
- [ ] Distinguish installations that have opted in and reported recently from all installations: opt-in counts are a lower bound, and active installations are not the same as active people or organizations. Account for restored or cloned installations when interpreting identifiers.
- [ ] Evaluate a small M3 collection endpoint that validates and forwards reports to GA4 without embedding a GA4 Measurement Protocol secret in the distributed application. Verify GA4's server-only reporting limitations, event mapping, deduplication and operational costs before implementation.
- [ ] Configure GA4 reports showing opted-in active installations over time, version distribution, feature adoption and setup drop-off. Add manual feedback or an optional survey for qualitative context; do not infer user intent from counters alone.
- [ ] Define event names, aggregation windows and success criteria before implementation. Test consent changes, report contents, offline operation, retries, duplicate reports and deletion; document the data contract for self-hosted administrators.

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
- Added an administrator-only [local usage summary](local-usage.md) for retained message counts and current enabled configuration. It sends no data; GA4 reporting and consent are not implemented.
- On a temporary clean database, browser checks covered the guide, local summary, receiver readiness and step preview; regression tests verify admin access and viewer denial for the two new pages. End-to-end guided setup and test-message delivery remain open.
- Documented the proposed [opt-in reporting contract](telemetry-design.md), including its boundaries and the still-unconfigured GA4 collector.
- Corrected the outbound recovery guide: retries use frozen rule and endpoint configuration, while a new submission is needed to apply changed settings.
- Fixed search indexing of decoded text containing NUL: the projection replaces NUL with a space, preserving original bytes and allowing the rest of the batch to complete.
- The first-flow guide can create a disabled inbound directory rule from existing channels and a pool, then opens it for review. Browser verification confirmed the saved rule is disabled; creating channels and sending a test file are still manual.
- A disposable backup/restore rehearsal verified database and object checksums, installation identity, application stop/restart, an encrypted setting with the original key and an original archived payload read through the recovered API. The [runbook](backup-restore.md) records remaining recovery checks.
- Recorded a [preliminary directory-flow measurement](performance-baseline.md) with throughput, queue sampling, latency, storage growth and output checks. Repeated and sustained workloads, other transports and resource peaks remain open.
