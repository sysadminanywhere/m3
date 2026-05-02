# M3 - Messaging Middleware Platform

M3 is a lightweight, extensible messaging middleware platform built with Spring Boot and Vaadin. It provides a visual interface for configuring message channels, defining routing rules, and monitoring message flows across various protocols.

## Features

### Channel Management
- **Multi-Protocol Support**: Configure channels for FTP, SFTP, Kafka, RabbitMQ, and local directories
- **Inbound/Outbound Channels**: Support for both incoming and outgoing message flows
- **Dynamic Configuration**: Runtime channel activation/deactivation without restart

### Message Routing & Processing
- **Visual Rule Engine**: Define message processing rules through an intuitive UI
- **Conditions**: Match messages based on header values, payload content, or metadata
- **Actions**: Route, transform, filter, or enrich messages as they flow through the system
- **Priority-Based Processing**: Rules are evaluated in priority order

### Message Tracking
- **Complete Audit Trail**: Track all messages with timestamps, status, and metadata
- **Status Monitoring**: View messages by direction (inbound/outbound) and status (pending, processed, error, sent)
- **Detailed Inspection**: Drill down into message payloads and headers

## Project Structure

The project follows a **feature-based package structure**:

```
src/main/java/com/sysadminanywhere/m3/
├── Application.java                    # Application entry point
├── base/                               # Shared UI components
│   └── ui/
│       ├── MainLayout.java
│       └── ViewTitle.java
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
    └── integration/                    # Spring Integration adapters
        ├── config/                       # Integration configuration
        ├── handler/                      # Message handlers
        └── gateway/                      # Message gateways
```

## Technology Stack

- **Backend**: Spring Boot 4.0.6, Spring Integration 7.0.4
- **Frontend**: Vaadin 25.1.3 (Java-based web UI)
- **Database**: PostgreSQL (with Spring Data JPA)
- **Build**: Maven
- **Java**: JDK 21

## Quick Start

### Prerequisites
- Java 21 or later
- Maven 3.9+ (or use the included `./mvnw` wrapper)
- PostgreSQL database

### Configuration
Create or update `application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/m3
spring.datasource.username=postgres
spring.datasource.password=yourpassword
spring.jpa.hibernate.ddl-auto=update
```

### Run in Development Mode

Using Maven wrapper:
```bash
./mvnw
```

Or on Windows:
```bash
mvnw.cmd
```

### Build for Production

```bash
./mvnw package
```

### Docker Build

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
