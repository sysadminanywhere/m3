# M3 - Messaging Middleware Platform

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
- **Status Monitoring**: View messages by direction (inbound/outbound) and status (pending, processed, error, sent)
- **Detailed Inspection**: Drill down into message payloads and headers

Message details show execution jobs, retry controls, the payload and metadata together, offer a charset selector for byte-payload previews, and download the complete stored payload. See [message preview and encoding options](docs/message-encodings.md).

## Project Structure

The project follows a **feature-based package structure**:

```
src/main/java/com/sysadminanywhere/m3/
├── Application.java                    # Application entry point
├── base/                               # Shared UI components
│   └── ui/
│       └── MainLayout.java
└── messaging/                          # Core messaging feature
    ├── domain/                         # Domain entities & enums
    │   ├── Message.java                  # Message entity
    │   ├── ChannelSettings.java          # Channel configuration
    │   ├── Rule.java                     # Routing rule
    │   ├── RuleCondition.java            # Condition for rule evaluation
    │   ├── RuleAction.java               # Action to execute on match
    │   └── [Enums: ChannelType, ActionType, ConditionOperator, etc.]
    ├── repository/                     # Data access layer
    │   ├── MessageRepository.java
    │   ├── ChannelSettingsRepository.java
    │   └── RuleRepository.java
    ├── service/                        # Business logic
    │   ├── MessageService.java           # Message CRUD operations
    │   ├── ChannelSettingsService.java   # Channel management
    │   ├── RuleService.java              # Rule management
    │   └── RuleEngine.java               # Rule evaluation engine
    ├── ui/                             # Vaadin UI views
    │   ├── ChannelListView.java          # Channel management UI
    │   ├── ChannelDetailView.java
    │   ├── RuleListView.java             # Rule management UI
    │   ├── RuleDetailView.java
    │   ├── InboundMessagesView.java      # Message monitoring
    │   └── OutboundMessagesView.java
    ├── source/                         # Rule-owned source receivers
    ├── outbound/                       # Durable delivery workers and transports
    ├── broker/                         # Transactional receipt publication
    └── integration/                    # Shared JSON configuration and rule gateway
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

Open **Administration → Workers** to create pools, set target/minimum/maximum container counts, and optionally auto-scale from pending plus processing jobs with a per-worker threshold. The UI samples CPU and memory utilization for each pool every 30 seconds and shows current, peak, and 30-day average values. Open a rule and choose its worker pool to move it; unfinished jobs follow the new assignment after any active claim transaction completes.

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

Then start the app from the IDE or with the Maven wrapper:

Using Maven wrapper:
```bash
./mvnw
```

Or on Windows:
```bash
mvnw.cmd
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

**Rules** opens a paged diagram of source channels → rules → destination channels with worker assignments.
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
