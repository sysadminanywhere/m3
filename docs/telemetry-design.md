# Proposed opt-in usage reporting

Status: design only. M3 currently provides a [local summary](local-usage.md); it does not collect consent or send usage reports. No collection endpoint or GA4 property is configured by this repository.

## Purpose and scope

Measure the number of reporting installations, versions, enabled transport and action types, and broad message volume. Reporting is per installation, not per person or organization. Installations that decline, cannot connect, or have not reported are absent; the results are a lower bound and cannot be extrapolated reliably to every installation. A restored backup or cloned database can duplicate an installation identity.

## Consent and control

An administrator must explicitly opt in before a report is queued or sent. The default and an unanswered prompt both mean disabled. The prompt must identify the M3-operated collection endpoint and Google Analytics as recipients, explain frequency and data fields, and provide a preview. Administrators can decline, later opt in or out, delete unsent reports and reset the random installation identifier. Disabling stops future collection and sending; deletion of already transmitted data requires a documented receiver-side procedure. Configuration, retries and prompts must not interfere with message processing when offline or when the collector is unavailable.

## Proposed report

Send at most one aggregated report per completed UTC week. A versioned allowlist, validated on both sides, contains only:

- A random resettable installation identifier and a separate random report ID for retry deduplication.
- Application version, edition and the UTC week represented.
- Boolean presence of enabled transport and action types, a bucket for the number of enabled rules, and coarse buckets for inbound and outbound message counts and outbound failures.

Message counts derived from retained records are lower bounds, not durable usage totals. If the product later stores independent aggregates, their retention and deletion policy must be specified separately. Reports must exclude bodies, metadata, channel or rule names, paths, hosts, usernames, credentials, IP addresses as payload fields, and raw error messages. Transport-level network addresses may still be visible to the receiving service and its infrastructure; its logging and retention policy must account for this.

## Delivery and collection

Store a report only after consent and retry with the same report ID; cap queue size and retention and delete queued reports on opt-out. A receiver must validate the schema, reject unexpected fields, deduplicate report IDs durably and protect its GA4 Measurement Protocol secret. Do not embed that secret in distributed M3 builds. Configure rate limits, logging, access controls, retention and deletion before deployment. GA4's server-only Measurement Protocol has reporting limitations, so validate the chosen event mapping and reports against an actual property before relying on its active-user counts.

The collector deployment, endpoint, GA4 property and access are not yet configured. Until they exist and the complete consent and failure tests pass, no feature should claim remote reporting is operational.
