# M3 - Messaging Middleware Platform

## Licensing

The [licensing notice](LICENSE.md) describes the first-publication model:
free Community for internal business use without
code modification or redistribution of customer builds, and commercial Scale for
additional execution capacity, multiple pools, autoscaling and external authentication.
This proposed model is source-available rather than open source. The
[Community and Scale draft agreements](docs/licenses/README.md) identify the remaining
release and rights-holder fields. Third-party components retain their own licenses.
The official full Docker image may be downloaded and used for free in Community
mode with paid features disabled. A purchased signed entitlement activates Scale
within the order's agreed limits. After expiration and grace, the same image may
continue running in Community mode. The Scale agreement grants free distribution
use separately from paid activation; its order fields apply only to paid activation.
External authentication currently has a signed entitlement check; Keycloak/AD
connectors will be implemented separately.

M3 is a lightweight, extensible messaging middleware platform built with Spring Boot and Vaadin. It provides a visual interface for configuring message channels, defining routing rules, and monitoring message flows across various protocols.

## Features

### Durable ingestion and receipt notifications

Channels describe endpoints; enabled INBOUND rules manage loading in their assigned worker pools. Messages and metadata are saved before receipt notifications are published to RabbitMQ. A transactional outbox retries failures. External services fetch data using `GET /api/v1/messages/{id}`; source adapters submit a loading `ruleId` through `POST /api/v1/channels/{channelId}/messages`. See [the ingestion contract and API](docs/message-ingestion.md).

### Durable outgoing delivery

Submit a payload and metadata with `ruleId` to `POST /api/v1/messages/outbound`. M3 stores the message and delivery job atomically, then workers apply the selected rule and deliver to DIRECTORY, FTP, SFTP, Kafka or RabbitMQ. Delivery status, retries and submission deduplication are available through the API. See [outgoing messages](docs/outbound-messages.md).

### Channel Management
- **Multi-Protocol Support**: Configure channels for FTP, SFTP, Kafka, RabbitMQ, and local directories
- **Inbound/Outbound Channels**: Support for both incoming and outgoing message flows
- **Dynamic Configuration**: Endpoint changes without restart; loading is enabled and disabled through rules

### Message Routing & Processing
- **Visual Rule Engine**: Define message processing rules through an intuitive UI
- **Conditions**: Match messages based on header values, payload content, or metadata
- **Actions**: Route, transform, filter, or enrich messages as they flow through the system
- **Explicit Rule Selection**: Each submission or source receiver uses its selected rule; condition groups use AND before OR

### Message Tracking
- **Complete Audit Trail**: Track all messages with timestamps, status, and metadata
- **Status Monitoring**: Inbound messages start as loaded (configurable per rule); external services report processing/results through the API. Routing jobs have independent status; outbound deliveries use pending/sent/failed.
- **Detailed Inspection**: Drill down into message payloads and headers

Message details show execution jobs, retry controls, the payload and metadata together, offer a charset selector for byte-payload previews, and download the complete stored payload. See [message preview and encoding options](docs/message-encodings.md).

## Project Structure

Community is a standalone Spring Boot project with one `pom.xml` and a standard `src/` tree. Scale is a separate standalone project that reuses Community source at build time. See [build and distribution instructions](docs/code-distribution.md).

The application entry point is `src/main/java/com/sysadminanywhere/m3/Application.java`. Shared code follows a **feature-based package structure**:

```
src/main/java/com/sysadminanywhere/m3/
в”њв”Ђв”Ђ Application.java                    # Application entry point
в”њв”Ђв”Ђ base/                               # Shared UI components
в”‚   в””в”Ђв”Ђ ui/
в”‚       в””в”Ђв”Ђ MainLayout.java
в””в”Ђв”Ђ messaging/                          # Core messaging feature
    в”њв”Ђв”Ђ domain/                         # Domain entities & enums
    в”‚   в”њв”Ђв”Ђ Message.java                  # Message entity
    в”‚   в”њв”Ђв”Ђ ChannelSettings.java          # Channel configuration
    в”‚   в”њв”Ђв”Ђ Rule.java                     # Routing rule
    в”‚   в”њв”Ђв”Ђ RuleCondition.java            # Condition for rule evaluation
    в”‚   в”њв”Ђв”Ђ RuleAction.java               # Action to execute on match
    в”‚   в””в”Ђв”Ђ [Enums: ChannelType, ActionType, ConditionOperator, etc.]
    в”њв”Ђв”Ђ repository/                     # Data access layer
    в”‚   в”њв”Ђв”Ђ MessageRepository.java
    в”‚   в”њв”Ђв”Ђ ChannelSettingsRepository.java
    в”‚   в””в”Ђв”Ђ RuleRepository.java
    в”њв”Ђв”Ђ service/                        # Business logic
    в”‚   в”њв”Ђв”Ђ MessageService.java           # Message CRUD operations
    в”‚   в”њв”Ђв”Ђ ChannelSettingsService.java   # Channel management
    в”‚   в”њв”Ђв”Ђ RuleService.java              # Rule management
    в”‚   в””в”Ђв”Ђ RuleEngine.java               # Rule evaluation engine
    в”њв”Ђв”Ђ ui/                             # Vaadin UI views
    в”‚   в”њв”Ђв”Ђ ChannelListView.java          # Channel management UI
    в”‚   в”њв”Ђв”Ђ ChannelDetailView.java
    в”‚   в”њв”Ђв”Ђ RuleListView.java             # Rule management UI
    в”‚   в”њв”Ђв”Ђ RuleDetailView.java
    в”‚   в”њв”Ђв”Ђ InboundMessagesView.java      # Message monitoring
    в”‚   в””в”Ђв”Ђ OutboundMessagesView.java
    в”њв”Ђв”Ђ source/                         # Rule-owned source receivers
    в”њв”Ђв”Ђ outbound/                       # Durable delivery workers and transports
    в”њв”Ђв”Ђ broker/                         # Transactional receipt publication
    в””в”Ђв”Ђ integration/                    # Shared JSON configuration and rule gateway
```

## Technology Stack

- **Backend**: Spring Boot 4.0.6, Spring Integration 7.0.4
- **Frontend**: Vaadin 25.1.3 (Java-based web UI)
- **Database**: PostgreSQL, Spring Data JPA and Liquibase migrations
- **Build**: Maven
- **Java**: JDK 21

## Quick Start

### Prerequisites
- Java 21 or later
- Maven 3.9+ (or use the included `./mvnw` wrapper)
- PostgreSQL database

### Worker runtime

The Vaadin application stores incoming messages and creates durable per-rule jobs in PostgreSQL. Rule execution runs in separate headless containers built from the same image. On startup the control plane creates the default worker pool and reconciles its configured replica count through the restricted Docker API proxy.

Open **Administration в†’ Workers** to create pools, set target/minimum/maximum container counts, and optionally auto-scale from pending plus processing jobs with a per-worker threshold. The UI samples CPU and memory utilization for each pool every 30 seconds and shows current, peak, and 30-day average values. Open a rule and choose its worker pool to move it; unfinished jobs follow the new assignment after any active claim transaction completes.

`docker compose up --build -d` starts PostgreSQL, RabbitMQ, the Docker socket proxy, and the Vaadin app. The proxy needs access to the local Docker socket and is kept on the internal Compose network. Worker containers are created by the app and are not exposed on a host port. Each pool consumes its own jobs using PostgreSQL row locks, so replicas in that pool can process concurrently. Workers continue running when the UI stops, including receipt publication. Pool reconciliation replaces containers when the image or worker configuration changes. Managed workers must be stopped before removing their Docker network.

### Configuration

Database migrations run automatically in the UI application before Hibernate validates the schema. Worker containers validate the shared schema and do not run migrations. Existing compatible Hibernate databases can be adopted without recreating tables. See [Database migrations](docs/database-migrations.md) for the changelog, upgrade procedure, Maven commands and integration tests.

Create or update `application.properties`:

```properties
spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/m3}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME:postgres}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:password}
spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.xml
spring.jpa.hibernate.ddl-auto=validate
```

### Run in Development Mode

Start PostgreSQL and the receipt-notification broker in the background first:

```bash
docker compose up -d db rabbitmq
```

Then build the selected edition and start the application from the repository root:

Using Maven wrapper:
```bash
./mvnw package
java -jar target/m3-community.jar
```

Or on Windows:
```powershell
.\mvnw.cmd package
.\scripts\run-app.ps1
```

The UI alone does not execute rules or load files. For a local Windows run, start a separate worker in another terminal after the UI has completed database migrations:

```powershell
.\scripts\run-worker.ps1 -Pool default
```

The pool must match the rule's **Worker Pool**. Set the same datasource and receipt broker environment variables in both processes. Keep the worker running; stopping the UI does not stop it. For another pool, start another worker with that pool's name. The **Receiver** column shows `NO_WORKER` until a worker reports its heartbeat, with a tooltip explaining how to start it.

Local Windows workers use Windows endpoint paths directly. Docker workers use Linux paths: mount files into the shared `/data` volume and configure endpoints such as `/data/in` and `/data/out`. A Windows `D:\...` path is not a usable Linux container path. An inbound directory must already exist and be readable in the worker filesystem. For directory delivery, output names are `message-<id>-<original-name>` to avoid replacing existing files. Enabling `deleteAfterProcessing` in the loading rule removes the input file only after its bytes, metadata and job commit to the database; otherwise the input file stays in place.

The local app defaults to the same database name and credentials as the Compose database. If you change the Compose credentials, also set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` in the IDE run configuration.

Local runs use RabbitMQ at `localhost:5672`. If you change the broker port or credentials, set `M3_BROKER_RABBIT_PORT`, `M3_BROKER_RABBIT_USERNAME` and `M3_BROKER_RABBIT_PASSWORD` in the IDE as well. When the broker is unavailable, messages remain saved and receipt notifications wait in the outbox until the connection is restored.

### Build for Production

```bash
./mvnw package
```

### Docker Build

Start the application and PostgreSQL with Docker Compose:

```bash
docker compose up --build
```

Open [http://localhost:8080](http://localhost:8080). The database is exposed on port 5432 and stored in the `postgres_data` volume.

The default database credentials are for local development. Set `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_PORT`, or `M3_PORT` in a `.env` file to change the defaults.

Stop the services with:

```bash
docker compose down
```

To remove the database volume as well, use `docker compose down --volumes`.

To build the image without starting the services:

```bash
docker build -t m3:latest .
```

## Core Concepts

### Channels
Channels define endpoints for message exchange. Each channel has:
- **Type**: Protocol (FTP, SFTP, Kafka, RabbitMQ, Directory)
- **Direction**: Inbound (receiving) or Outbound (sending)
- **Properties**: Protocol-specific settings (host, port, credentials, etc.)

### Rules
Rules define how messages are processed:
- **Source Channel**: Where the rule applies
- **Conditions**: Criteria that must match (e.g., `header.contentType equals application/json`)
- **Actions**: Operations to perform (route to channel, transform payload, filter, enrich headers)
- **Priority**: Evaluation order (lower = earlier)

### Rule Engine
The `RuleEngine` evaluates rules against incoming messages:
1. Finds all enabled rules for the source channel
2. Evaluates conditions using AND/OR logic
3. Applies actions in priority order
4. Supports field access: `header.<name>`, `payload.<field>`, `payload`

## API & Usage

### Creating a Channel

```java
ChannelSettings channel = channelSettingsService.createChannel(
    "ftp-inbound",
    ChannelType.FTP,
    ChannelDirection.INBOUND,
    "FTP input channel",
    Map.of(
        "host", "ftp.example.com",
        "port", "21",
        "username", "user",
        "password", "pass",
        "remoteDirectory", "/incoming",
        "filePattern", "*.xml"
    )
);
```

### Creating a Routing Rule

```java
// Create rule
Rule rule = ruleService.createRule(
    "xml-to-kafka",
    RuleType.ROUTING,
    sourceChannelId,
    10  // priority
);

// Add condition: payload type is XML
ruleService.addCondition(
    rule.getId(),
    "header.fileExtension",
    ConditionOperator.EQUALS,
    "xml",
    LogicalOperator.AND
);

// Add action: route to Kafka
RuleAction action = ruleService.addAction(rule.getId(), ActionType.ROUTE);
action.setTargetChannel("kafka-outbound");
```

## Testing

Run unit tests:
```bash
./mvnw test
```

## Architecture

M3 uses **Spring Integration** for message flow orchestration:
- **Inbound Adapters**: Poll external systems (FTP, directory, etc.)
- **Message Handlers**: Process messages through the rule engine
- **Outbound Adapters**: Send messages to target systems

The **Vaadin** UI provides a reactive, component-based interface for managing the system without page reloads.

## Next Steps

- [Vaadin Documentation](https://vaadin.com/docs/v25)
- [Spring Integration Reference](https://docs.spring.io/spring-integration/reference/)
- [Building Apps Guide](https://vaadin.com/docs/v25/building-apps)


## Visual rule configuration

Open a rule and use **Add Condition** or **Add Action**. Existing entries have an **Edit** button.
The message flow cards show the selected source, conditions, first filter, ordered transformations/metadata additions, and destination.
Conditions compare metadata, a top-level JSON field, or the whole body. AND takes precedence over OR in creation order.

Body transformations are selected from a list: extract a JSON field, trim whitespace, uppercase/lowercase,
replace literal text, add a prefix/suffix, or serialize as JSON text. Each transformation has a live preview
for a text or JSON example. This preview evaluates only the selected operation in memory; it does not run
previous actions, write messages, or contact channels. For a chain, use the previous operation's result as the next example.

Execution order controls transformations and metadata additions (lower numbers first, ties by creation order).
Filtering runs before transformations; when several filters exist, only the first configured filter is used.
The metadata action can set `outputCharset` (for example `UTF-8` or `windows-1251`).
Text operations require a known source charset; JSON extraction requires a JSON object, and text operations
on JSON require extracting a string field first. Original stored message bytes are retained.

The editor and workers share the same transformation implementation. Existing `$.field` transformations
remain compatible. New operation configurations use the existing `transformation_script` column;
no additional database migration is needed. Deploy the updated UI and workers together.


## Interface languages

The UI supports English, Russian, German, French, Italian and Portuguese. Use the language selector
at the bottom of the left navigation rail. The initial language follows the browser language when
supported, otherwise English. Selection reloads the current page (save open forms first) and is stored
in the `m3-language` browser cookie for one year. Other browsers/users have independent preferences.

UTF-8 catalogs are in `src/main/resources/i18n/ui_<language>.json`; English text is the translation key.
Add the same key to all six catalogs when adding interface text, and call `Translations.t(key)`.
Parameterized text uses `{0}`, `{1}`, etc. The Vaadin `I18NProvider` is registered as a Spring bean;
page titles, labels, enum display names and dates use the selected locale. Protocol identifiers,
channel/rule names, metadata keys, message bodies and stored database values remain unchanged.
Diagnostic details without a translation retain their original text.


## Rule diagrams and interactive pipeline editor

**Rules** opens a paged diagram of source channels в†’ rules в†’ destination channels with worker assignments.
Select a rule block to open its pipeline; the **Table** display mode keeps the tabular overview available.

The rule editor uses a connected canvas as its main workspace. Source/destination selectors, loading settings,
and the worker pool (in the rule details view) are inside the relevant blocks. Conditions and filters can be added,
edited and removed directly on the canvas. Transformation/metadata blocks have edit/delete and up/down controls.
Changing their order persists the same order used by workers. Filters always run before transformations;
additional filters are marked inactive. A discard filter marks subsequent stages as skipped.

**Preview entire rule** accepts a text/JSON example and manually entered metadata. It evaluates the saved
conditions, filter, and all transformations/metadata additions with the worker's `RuleEngine`, showing the
result body and metadata. The preview does not perform channel I/O or store messages. It starts from decoded
example text; it does not simulate transport, charset detection or final byte encoding.
Canvas actions/conditions are saved immediately. Channel/loading/worker settings require **Save** on the rule.


## Message search, overview and unsaved changes

Message troubleshooting now adds a masked payload-fragment search, correlation IDs, routing/attempt history, original/copy links and prepared delivery snapshots. Optional S3/MinIO tiering moves completed bodies out of PostgreSQL after seven days. Community executes one worker slot; signed offline Scale licenses enable shared capacity, multiple pools and autoscaling. See [troubleshooting, storage and Scale](docs/troubleshooting-storage-scale.md) for configuration, original-access permissions, API changes and upgrade requirements.

Inbound and outbound lists filter by message ID, status, source, target and calendar dates.
Source/target search is case insensitive and treats `%`, `_` and backslashes literally. Dates
use the displayed server time zone and include the entire final day. **Apply filters** updates
the URL (`id`, `status`, `source`, `target`, `from`, `to`); links can be shared and browser Back
restores filters. Invalid links show an error and no results. Reset clears all filters.

**Overview** is the start page. Queue counts, failed messages and unpublished receipts refresh
every 15 seconds, with a manual refresh button. Failed message cards open the filtered list.
Availability counts worker processes reporting in the last 35 seconds; idle and outbound-only
workers report every 10 seconds. Heartbeats prove process liveness, not transport health.
Deploy the UI first to apply migration 020, then restart workers with the updated version.
Older workers do not report heartbeats. Records older than a day are removed by workers.

Channel, rule, condition, action and worker pool forms show **Unsaved changes**. Navigating
away, Cancel, Escape or clicking outside a dialog offers discard/continue. Browser reload
and closing the tab use the browser's native warning. Reverting editable fields clears the
indicator; successful Save establishes the new baseline. Preview inputs and immediately
saved rule steps do not mark the parent form dirty.

## Access, encrypted configuration and deployment

Initialize local credentials with `pwsh -File scripts/Initialize-LocalSecurity.ps1` before starting.
The script creates `.m3/local-security.properties`, excluded from Git and restricted to the current user.
It never overwrites an existing key. Accounts are `admin`, `operator` and `viewer`; their generated
passwords are in that file. Admin manages configuration, operator submits/forwards/retries/deletes messages,
and viewer reads messages and monitoring. Use **Sign out** to end a browser session.
Admin can inspect successful configuration and message operations in **Administration в†’ Audit log**.
Audit records contain actor, operation, entity ID and time, without payloads or credentials.

For Docker set `M3_ADMIN_PASSWORD` (16вЂ“72 UTF-8 bytes) and `M3_SECRET_KEY` (32 random bytes in Base64).
Optional `M3_OPERATOR_PASSWORD` and `M3_VIEWER_PASSWORD` enable those accounts. Serve production through HTTPS.
The control plane forwards the same encryption key to managed workers. Preserve and back up the key
separately from the database: changing or losing it prevents reading existing configuration.
Start the upgraded control plane to apply migration 021 before starting upgraded workers; stop old
control planes/workers before this upgrade. Existing channel properties are encrypted at startup with
AES-256-GCM, and existing secrets are never populated into browser fields. Blank secret fields keep the
saved value; removing an extra-property row removes that property. Migration 021 installs `pg_trgm` in
the public schema, so the migration account needs permission to install that extension.

API calls require HTTP Basic credentials. Mutating calls also require `X-M3-Request: 1`.
Browser session cookies do not authorize the API. For example, use `curl -u admin -H "X-M3-Request: 1"`
with the existing POST examples; curl prompts for the password. Payload download links also require
authorization. Avoid putting credentials in URLs or committed scripts.

New jobs save an encrypted snapshot of rule steps and channel settings in their acceptance transaction.
Later edits affect new jobs; retries preserve their original snapshot. Resubmit or forward a new copy to
use updated rules. Pool assignment remains operational, and disabling a channel stops sending.
Legacy queued jobs without a snapshot adopt the configuration when first processed.
Concurrent edits use version checks and report a conflict instead of silently overwriting changes.

Outbound transports persist the start of an irreversible send. A failure or expired worker claim after
that boundary becomes **uncertain delivery**, with automatic retries stopped. Verify the receiver first;
the UI then allows an explicitly acknowledged retry. API retries need `acknowledgeUncertain=true`.
Receivers should deduplicate the stable `m3DeliveryId` header. Arbitrary external systems cannot provide
an exactly-once guarantee; an acknowledged manual retry can still create a duplicate.

Condition regular expressions use RE2J to avoid catastrophic backtracking. Backreferences and lookaround
are unsupported; update existing expressions using those constructs before upgrading. Unsupported
expressions fail the job visibly instead of silently treating a matching message as a non-match.

## Retention and regression checks

Message troubleshooting provides masked payload inspection, routing history, Retry and Redirect.
Incoming business processing is tracked separately per recipient, with versioned callbacks, replay,
deadlines and durable RabbitMQ events. **Operations** shows overdue processing, backlog and storage pressure.
See [processing and operations](docs/external-processing-operations.md),
[storage, masking and Scale](docs/troubleshooting-storage-scale.md),
[consistent backup and restore](docs/backup-restore.md), and
[Community and full build instructions](docs/code-distribution.md).

Business messages and deduplication keys are retained indefinitely by default. Set
`M3_RETENTION_MESSAGES_DAYS` or `M3_RETENTION_IDEMPOTENCY_DAYS` to a positive number to enable deletion.
Only completed messages are removed; pending/running/failed jobs, unpublished receipts and originals
referenced by copies are preserved. Idempotency expiry defines the window for rejecting repeated requests.
Published notification rows default to 30 days; audit rows default to 365 days. Override with
`M3_RETENTION_RECEIPTS_DAYS` and `M3_RETENTION_AUDIT_DAYS`; zero disables deletion.
Cleanup runs every six hours in bounded batches of 500 rows.

Run Java checks with `./mvnw test` and JavaScript checks with `node --test src/test/js/*.test.cjs`.
Browser regression checks require Chrome and a running test instance:
`npm --prefix browser-tests ci`, then `npm --prefix browser-tests test`.
They check authentication, roles, audit, logout, URL filters and unsaved dialog changes without saving
configuration. Set `M3_TEST_URL` to target a separate instance; passwords come from the environment
or the ignored local credentials file. Use isolated instances for mutation/transport integration tests;
the Java integration suite creates its own PostgreSQL, RabbitMQ and Kafka containers.
