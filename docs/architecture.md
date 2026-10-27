# M3 runtime architecture

## Runtime layout

M3 has a control plane and independently scalable rule workers. The Vaadin application remains the control plane: it edits channels and rules, displays message history, manages worker capacity, and changes rule assignments. It does not evaluate rules.

```mermaid
flowchart LR
    Sources[Native message sources] --> W1[Rule worker]
    Client[External API client] --> UI[Vaadin control plane and API]
    UI --> DB[(PostgreSQL)]
    UI --> Queue[(PostgreSQL job queue)]
    Queue --> W1
    Queue --> W2[Rule worker]
    W1 --> DB
    W2 --> DB
    UI --> Proxy[Restricted Docker API proxy]
    Proxy --> Engine[Docker Engine]
    Engine --> W1
    Engine --> W2
```

## Responsibilities

### Vaadin control plane

- Owns channel, rule, worker-pool, assignment, and capacity settings.
- Configures message sources and accepts API submissions; it persists submitted messages and their jobs.
- Shows messages, worker health/load, and rule-to-worker assignments.
- Starts, stops, and scales worker containers through a restricted Docker API proxy.
- Persists control-plane state in PostgreSQL.

### Rule workers

- Run without the Vaadin web interface.
- Start inbound adapters for enabled rules assigned to their pool, ingest messages and persist jobs before acknowledging input.
- Report process heartbeats; container load metrics are sampled separately when Docker management is configured.
- Claim jobs from PostgreSQL using `FOR UPDATE SKIP LOCKED` and execute only the rules assigned to their worker pool.
- Persist execution results and message status in PostgreSQL.
- Commit the rule result and message status in the same transaction as the job completion.

### PostgreSQL work queue

- PostgreSQL is the source of truth for rule definitions, assignments, worker desired state, message history, and execution status.
- The job table buffers incoming work durably and distributes it across replicas of a worker pool through row-level locking and `SKIP LOCKED`.
- Expired claims that have not started outbound delivery return to `PENDING`. A claim that expires after delivery starts becomes an uncertain failure and requires an explicit, acknowledged retry after checking the receiver. See [outgoing message reliability](outbound-messages.md#reliability-and-status).
- Rule assignment changes move pending jobs to the new pool. A job already claimed keeps running to completion in its original worker.

### Container management

- Worker pools have desired replica count and scaling limits configured through the Vaadin UI.
- The control plane reconciles desired and running containers through a Docker API proxy with only the container operations it needs enabled.
- The Docker socket is never mounted directly into the Vaadin application container.
- Docker Compose starts the control plane, database, and restricted Docker API proxy. The control plane reconciles worker containers against the Docker Engine API; Compose itself is not treated as a runtime orchestration API.

## Rule assignment and movement

Each rule belongs to one worker pool. The UI can move it through the rule detail page. Jobs that have not started move immediately; a job already claimed finishes in its original worker. Once all rule jobs for a message finish, the last worker combines the matches in priority order and creates the routed outbound message, preserving the existing priority-based rule behavior.

## Processing and monitoring

Native inbound adapters run in processes using the `worker` profile, which disables the web interface. An enabled inbound rule starts its source in the assigned pool. The source persists the message and its durable rule job before acknowledging input. API clients can also submit messages through the control plane. Workers claim jobs from PostgreSQL and run condition evaluation; the final worker applies the matched rules' filters, transformations, and routing in priority order.

The Vaadin Workers page stores a fixed target or queue-based auto-scaling settings, minimum/maximum replica counts, and a pending-job threshold per worker. Auto-scaling uses pending plus currently processing jobs; the Docker API reconciler creates/removes containers for each pool and retries desired state every 30 seconds. The page reports running containers and pool queue depth. Rule detail now has a worker-pool selector, and the rule list shows each assignment.

The claim lease is five minutes by default. Failed rule executions remain visible as failed jobs, independently of an inbound message's business-processing status. Workers report a heartbeat every ten seconds; the overview considers a heartbeat older than 35 seconds stale. A fresh heartbeat indicates that a worker process is reporting, not that every source or destination is connected. Auto-scaling is driven by queue depth rather than CPU or latency.
