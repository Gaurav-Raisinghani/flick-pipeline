# Architecture Document — Flik AI Generation Pipeline

## Table of Contents

1. [System Overview](#system-overview)
2. [Design Principles](#design-principles)
3. [Component Architecture](#component-architecture)
4. [Queue Design & Topology](#queue-design--topology)
5. [Fair Scheduling Strategy](#fair-scheduling-strategy)
6. [Retry & Dead Letter Strategy](#retry--dead-letter-strategy)
7. [Auto-Scaling Mechanics](#auto-scaling-mechanics)
8. [Real-Time Delivery (WebSocket)](#real-time-delivery-websocket)
9. [Data Model](#data-model)
10. [Observability Architecture](#observability-architecture)
11. [Trade-offs & Alternatives Rejected](#trade-offs--alternatives-rejected)
12. [Failure Modes & Mitigations](#failure-modes--mitigations)
13. [What Changes at 10x and 100x Scale](#what-changes-at-10x-and-100x-scale)
14. [Stretch: Multi-Region Simulation](#stretch-multi-region-simulation)
15. [Stretch: Task DAGs](#stretch-task-dags)
16. [Stretch: Cost Modeling](#stretch-cost-modeling)
17. [Stretch: Tiered Storage](#stretch-tiered-storage)
18. [Stretch: Canary Deploys](#stretch-canary-deploys)
19. [Load Test Simulations](#load-test-simulations)
20. [Testing & Quality Assurance](#testing--quality-assurance)
21. [Configuration Reference](#configuration-reference)

---

## System Overview

The Flik pipeline processes AI content generation requests (text, image, video) through a distributed architecture:

1. **Ingestion** — API Gateway accepts requests, authenticates, rate-limits, and enqueues
2. **Scheduling** — RabbitMQ routes tasks to priority queues with fair distribution
3. **Processing** — Stateless workers pull tasks, execute simulated AI work, persist results
4. **Delivery** — Results pushed to clients via WebSocket, also available via REST polling
5. **Resilience** — Failed tasks retry with exponential backoff, eventually dead-lettered
6. **Observability** — Prometheus metrics, Grafana dashboards, structured JSON logs
7. **Multi-Region** — Two simulated regions with inter-region latency and region-aware routing
8. **Task DAGs** — Dependent task chains (generate → upscale → watermark) with automatic progression
9. **Cost Modeling** — Per-task and per-worker cost tracking with budget-aware auto-scaling
10. **Tiered Storage** — Hot (Redis) → Warm (PostgreSQL) → Cold (archived) with transparent retrieval
11. **Canary Deploys** — Progressive rollout (10% → 50% → 100%) with automatic error-rate rollback

### System Architecture

```
                              ┌─────────────────────────────────────────────┐
                              │              Observability                  │
                              │  ┌────────────┐       ┌─────────┐          │
                              │  │ Prometheus │──────>│ Grafana │          │
                              │  │  :9090     │scrape │  :3000  │          │
                              │  └─────┬──────┘       └─────────┘          │
                              │        │ scrapes /actuator/prometheus       │
                              └────────┼───────────────────────────────────┘
                                       │
┌────────┐   REST/WS    ┌──────────────┼──────────────────┐
│        │──────────────>│     API Gateway (us-east :8080) │
│ Client │<──────────────│     API Gateway (us-west :8081) │
│        │  202 + taskId │                                 │
└────────┘               │  Auth │ Rate Limit │ Region     │
                         │       │  (Redis)   │ Routing    │
                         └───┬───┴──────┬─────┴────────────┘
                             │          │
                    enqueue  │          │ persist task
                             │          │
                    ┌────────▼──┐  ┌────▼───────┐   ┌───────┐
                    │ RabbitMQ  │  │ PostgreSQL │   │ Redis │
                    │  :5672    │  │  :5432     │   │ :6379 │
                    │           │  │            │   │       │
                    │ P0 P1 P2  │  │  tasks     │   │ cache │
                    │ retry DLQ │  │  table     │   │ pub/  │
                    └────┬──────┘  └────────────┘   │ sub   │
                         │                          └───┬───┘
                    pull │                              │
                         │         ┌────────────────────┘
                    ┌────▼─────────▼──┐
                    │     Workers     │
                    │                 │
                    │ Text (x2)  1-3s │
                    │ Image (x2) 5-12s│
                    │ Video (x1) 30-90s│
                    └────────┬────────┘
                             │ result + status
                             ▼
                    ┌─────────────────┐
                    │   Autoscaler    │
                    │   :8082         │
                    │                 │
                    │ monitors queues │
                    │ scales workers  │
                    │ canary deploys  │
                    └─────────────────┘
```

### Request Lifecycle

```
Client POST /api/v1/tasks (or POST /api/v1/dags for DAG chains)
  → Auth filter validates API key
  → Rate limiter checks tenant quota (Redis token bucket)
  → Region routing resolves target region, simulates inter-region latency if cross-region
  → Task persisted to PostgreSQL (status: QUEUED, region, dagId, storageTier: HOT)
  → Message published to RabbitMQ priority queue
  → 202 Accepted returned with task ID

Worker picks message from queue
  → Status updated to PROCESSING (PostgreSQL + Redis pub/sub → WebSocket)
  → Simulated AI processing (sleep + random failure, records workerVersion)
  → On success:
      → Result stored in PostgreSQL with workerVersion and cost
      → Result cached in Redis (HOT tier, 10-min TTL)
      → Redis pub/sub → WebSocket
      → Gateway DagCompletionListener triggers next DAG step (if part of a DAG)
  → On failure: retry count incremented, message routed to retry exchange
    → After max retries: message routed to dead letter queue, status DEAD_LETTERED
```

---

## Design Principles

1. **Async-first** — Every generation request returns immediately with a task ID. No blocking.
2. **Backpressure via queuing** — RabbitMQ absorbs traffic spikes. Workers pull at their own pace.
3. **Stateless workers** — Any worker can process any task of its type. No affinity, no session state.
4. **Fail-safe defaults** — Tasks are durable (persisted to disk in RabbitMQ), acknowledged only after completion, retried on failure.
5. **Observable by default** — Every component exports metrics. An on-call engineer can diagnose issues from the Grafana dashboard alone.

---

## Component Architecture

```
Docker Compose Services
═══════════════════════

┌─ Infrastructure ──────────────────────────────────────────────────┐
│                                                                   │
│  postgresql:5432     redis:6379        rabbitmq:5672/15672        │
│  (task storage)      (cache/pub-sub)   (message broker)          │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
         │                    │                    │
         ▼                    ▼                    ▼
┌─ Application ─────────────────────────────────────────────────────┐
│                                                                   │
│  api-gateway:8080          api-gateway-region-b:8081              │
│  (us-east)                 (us-west)                              │
│                                                                   │
│  worker-text (x2)   worker-image (x2)   worker-video (x1)       │
│  profile=text        profile=image       profile=video            │
│                                                                   │
│  autoscaler:8082                                                  │
│  (scaling + canary)                                               │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
         │                    │
         ▼                    ▼
┌─ Observability ───────────────────────────────────────────────────┐
│                                                                   │
│  prometheus:9090           grafana:3000                           │
│  (metrics collection)      (dashboards)                          │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
```

### API Gateway (`flik-api-gateway`)

**Responsibilities:**
- REST API for task submission (`POST /api/v1/tasks`), DAG submission (`POST /api/v1/dags`), and status queries
- Cost reporting endpoint (`GET /api/v1/costs`)
- Interactive Swagger UI for API exploration and testing (`/swagger-ui.html`)
- WebSocket endpoint for real-time status streaming
- Authentication via API key (Spring Security filter)
- Per-tenant rate limiting via Redis token bucket
- Task routing to correct RabbitMQ queue based on priority
- Region-aware routing with simulated inter-region latency
- Tiered storage management (hot/warm/cold) with transparent result retrieval
- DAG completion listening (subscribes to Redis events, triggers dependent tasks)

**Swagger / OpenAPI UI:**

All REST endpoints are documented with OpenAPI 3.0 annotations and accessible through an interactive Swagger UI:

| Region | Swagger UI URL | OpenAPI JSON |
|---|---|---|
| us-east | `http://localhost:8080/swagger-ui.html` | `http://localhost:8080/v3/api-docs` |
| us-west | `http://localhost:8081/swagger-ui.html` | `http://localhost:8081/v3/api-docs` |

The UI groups endpoints by tag:

| Tag | Endpoints | Description |
|---|---|---|
| **Tasks** | `POST /api/v1/tasks`, `GET /api/v1/tasks/{taskId}` | Submit and query AI generation tasks |
| **DAGs** | `POST /api/v1/dags`, `GET /api/v1/dags/{dagId}` | Submit and query task dependency chains |
| **Costs** | `GET /api/v1/costs` | Cost tracking and budget reporting |
| **Health** | `GET /health` | Infrastructure health checks (PostgreSQL, RabbitMQ, Redis) |

Authentication is configured as a Bearer token scheme — click **Authorize** in the Swagger UI and enter any non-blank token (e.g. `test-token`) to authenticate. The `/health`, `/swagger-ui/**`, `/v3/api-docs/**`, `/actuator/**`, and `/ws/**` paths are publicly accessible without a token.

Implementation uses `springdoc-openapi-starter-webmvc-ui` (v2.5.0), which auto-discovers all `@RestController` classes and generates the OpenAPI spec at startup with zero code generation or build-time processing.

**Key Design Decisions:**
- Two gateway instances run in Docker Compose — one for `us-east` (port 8080) and one for `us-west` (port 8081) — demonstrating multi-region routing.
- Java 21 virtual threads handle thousands of concurrent WebSocket connections without thread pool exhaustion.
- Rate limiting uses a Lua script executed atomically in Redis to avoid race conditions in the token bucket algorithm.
- The gateway does NOT process tasks — it only enqueues. This separation ensures the gateway stays responsive under worker backpressure.
- The `DagCompletionListener` subscribes to `task:*` Redis pub/sub channels to detect task completions and trigger the next DAG step. This avoids coupling the worker to the DAG orchestration logic.

### Worker Pool (`flik-worker`)

**Responsibilities:**
- Consume tasks from RabbitMQ queues
- Execute simulated AI processing (type-specific durations and failure rates)
- Persist results to PostgreSQL
- Publish status updates via Redis pub/sub

**Worker Types:**

| Type | Duration | Failure Rate | Queue |
|---|---|---|---|
| Text | 1-3s | 5% | flik.tasks.p0 |
| Image | 5-12s | 10% | flik.tasks.p1 |
| Video | 30-90s | 15% | flik.tasks.p2 |

Workers are deployed as separate Docker Compose services per type, each independently scalable. A single `flik-worker` Spring Boot application uses Spring profiles to activate the correct processor.

**Why not one worker type for all tasks?**
Different task types have vastly different resource profiles. A video worker sleeping for 90s would block a concurrency slot that could process 30-90 text tasks. Separate worker types allow independent scaling — add more video workers without affecting text throughput.

### Autoscaler (`flik-autoscaler`)

**Responsibilities:**
- Monitor RabbitMQ queue depths via Management HTTP API
- Scale worker containers up/down via Docker socket
- Enforce hourly budget constraints — scale-up is blocked when estimated cost exceeds the budget limit
- Expose canary deployment management API (`/api/v1/canary/*`)

**Scaling Algorithm:**
```
every 5 seconds:
  calculate_current_hourly_cost()            # sum of replicas × cost-per-worker-hour
  for each queue (p0, p1, p2):
    depth = GET /api/queues/%2F/{queue_name} → messages
    if depth > SCALE_UP_THRESHOLD for 3 consecutive checks (15s):
      if estimated_cost + worker_cost <= BUDGET_PER_HOUR:
        scale workers up by 1 (up to MAX)
      else:
        log "Budget limit: skipping scale-up"
    if depth == 0 for 12 consecutive checks (60s):
      scale workers down by 1 (down to MIN)
```

This is a proportional controller with hysteresis — the sustained-check requirement prevents flapping during brief spikes. The budget guard prevents runaway cost during sustained load.

---

## Queue Design & Topology

### Why RabbitMQ Over Alternatives

| Feature | RabbitMQ | Kafka | Redis Streams |
|---|---|---|---|
| Priority Queues | Native (`x-max-priority`) | Not supported | Not supported |
| Dead Letter Exchange | Native (DLX) | Manual consumer logic | Manual implementation |
| Per-message TTL | Native | Not supported | Manual expiry |
| Consumer Acknowledgment | Per-message ACK/NACK | Offset-based | XACK per entry |
| Ordering Guarantee | Per-queue FIFO | Per-partition FIFO | Per-stream FIFO |
| Operational Complexity | Moderate | High (ZooKeeper/KRaft) | Low |
| Management UI | Built-in | Third-party | None |

RabbitMQ wins on three critical requirements: priority queues, dead letter handling, and per-message retry with TTL. Kafka would require building all three as application-level logic.

### Queue Topology

```
Exchange: flik.tasks (type: topic, durable: true)
├── Binding: routing key "task.p0" → Queue: flik.tasks.p0
├── Binding: routing key "task.p1" → Queue: flik.tasks.p1
└── Binding: routing key "task.p2" → Queue: flik.tasks.p2

Queue: flik.tasks.p0
  x-max-priority: 10
  x-dead-letter-exchange: flik.retry
  x-dead-letter-routing-key: retry.p0
  durable: true

Queue: flik.tasks.p1 (same config, routing key retry.p1)
Queue: flik.tasks.p2 (same config, routing key retry.p2)

Exchange: flik.retry (type: topic, durable: true)
├── Queue: flik.retry.wait-5s
│     x-message-ttl: 5000
│     x-dead-letter-exchange: flik.tasks
├── Queue: flik.retry.wait-15s
│     x-message-ttl: 15000
│     x-dead-letter-exchange: flik.tasks
└── Queue: flik.retry.wait-60s
      x-message-ttl: 60000
      x-dead-letter-exchange: flik.tasks

Exchange: flik.dlq (type: fanout, durable: true)
└── Queue: flik.dead-letter (durable: true)
```

### Message Format

```json
{
  "taskId": "UUID",
  "tenantId": "string",
  "taskType": "TEXT|IMAGE|VIDEO",
  "priority": 0,
  "payload": {},
  "retryCount": 0,
  "region": "us-east",
  "dagId": "UUID (nullable)",
  "parentTaskId": "UUID (nullable)",
  "createdAt": "ISO-8601"
}
```

Headers: `x-retry-count` (integer), `x-original-routing-key` (string for routing back after retry).

---

## Fair Scheduling Strategy

```
Layer 1: Ingestion (API Gateway)          Layer 2: Consumption (Workers)
════════════════════════════════          ══════════════════════════════

 tenant-1 ── 50 req/s ──┐                ┌──────────┐  prefetch=1
                         │                │          │  pull one
 tenant-2 ── 50 req/s ──┤  ┌──────────┐  │ RabbitMQ │──────────────> Worker A
                         ├─>│   Rate   │─>│          │
 tenant-3 ── 50 req/s ──┤  │  Limiter │  │ messages │──────────────> Worker B
                         │  │  (Redis) │  │ interleave              
 tenant-X ─ 500 req/s ──┘  └──────────┘  │ naturally│──────────────> Worker C
                   │             │        └──────────┘
                   │        ┌────┘                │
                   │        ▼                     │
              over limit = 429              ACK before next pull
              (tenant-X throttled)          (no batching per tenant)
```

Fair scheduling is enforced at two layers:

### Layer 1: Ingestion Rate Limiting (API Gateway)

Per-tenant token bucket implemented in Redis:

```
Algorithm: Token Bucket
  - Bucket capacity: RATE_LIMIT_REQUESTS_PER_SEC (default 300)
  - Refill rate: RATE_LIMIT_REQUESTS_PER_SEC tokens/second
  - Scope: per tenant ID
  - Implementation: Redis Lua script (atomic check-and-decrement)
```

This prevents any single tenant from flooding the queue. A tenant sending 10x traffic hits the rate limit and receives 429 Too Many Requests.

### Layer 2: Consumer Fairness (Worker Prefetch)

RabbitMQ consumer prefetch is set to 1 (`spring.rabbitmq.listener.simple.prefetch=1`). Each worker pulls exactly one message, processes it, ACKs, then pulls the next. This ensures:

- No worker buffers a batch of messages from one tenant
- Messages from different tenants interleave naturally in the queue
- A burst from one tenant doesn't preempt tasks already queued by others

### Why Not Per-Tenant Queues?

Creating a queue per tenant would provide perfect isolation but doesn't scale:
- 10,000 tenants = 10,000 queues × 3 priorities = 30,000 queues
- Worker routing becomes a complex scheduling problem
- RabbitMQ performance degrades with excessive queues

The rate-limit + prefetch approach provides sufficient fairness at the target scale with zero queue proliferation.

---

## Retry & Dead Letter Strategy

```
                         ┌──────────────────────────────────────────────┐
                         │              Retry Flow                      │
                         │                                              │
  Task fails             │   retry=1         retry=2         retry=3   │
  Worker NACKs           │   ┌──────┐        ┌──────┐        ┌──────┐ │
  ──────────────────────>│──>│ 5s   │──DLX──>│ 15s  │──DLX──>│ 60s  │ │
                         │   │ wait │        │ wait │        │ wait │ │
                         │   └──┬───┘        └──┬───┘        └──┬───┘ │
                         │      │ TTL expires    │ TTL expires    │     │
                         │      ▼                ▼               │     │
                         │   back to          back to            │     │
                         │   task queue       task queue          │     │
                         └───────────────────────────────────────┼─────┘
                                                                 │
                                                          retry > max (3)
                                                                 │
                                                                 ▼
                                                        ┌──────────────┐
                                                        │  Dead Letter  │
                                                        │    Queue      │
                                                        │              │
                                                        │ status =     │
                                                        │ DEAD_LETTERED│
                                                        └──────────────┘
```

### Exponential Backoff via TTL Queues

Rather than implementing retry delays in application code (which would block a worker), we use RabbitMQ's TTL + DLX mechanism:

1. Worker NACKs a failed message (no requeue)
2. Message goes to retry exchange based on retry count:
   - Retry 1 → `flik.retry.wait-5s` (5-second TTL)
   - Retry 2 → `flik.retry.wait-15s` (15-second TTL)
   - Retry 3 → `flik.retry.wait-60s` (60-second TTL)
3. After TTL expires, message is dead-lettered back to the original task exchange
4. After max retries (3), message is routed to `flik.dead-letter` queue

**Why TTL queues instead of delayed message plugin?**
The RabbitMQ delayed message exchange plugin is not included in the official Docker image and adds operational complexity. TTL queues are a standard RabbitMQ feature, require no plugins, and are well-understood.

### Dead Letter Queue

Messages in `flik.dead-letter` are:
- Stored indefinitely for manual inspection
- Task status updated to `DEAD_LETTERED` in PostgreSQL
- Visible in Grafana dashboard (DLQ depth metric)
- Can be replayed manually via RabbitMQ Management UI

---

## Auto-Scaling Mechanics

### Scaling Model

```
                    ┌─────────────┐
                    │ Autoscaler  │
                    │   Service   │
                    └──────┬──────┘
                           │ polls every 5s
                    ┌──────▼──────┐
                    │  RabbitMQ   │
                    │ Mgmt API    │
                    │ /api/queues │
                    └──────┬──────┘
                           │ queue depth
                    ┌──────▼──────┐
                    │   Docker    │
                    │   Socket    │
                    │ scale up/dn │
                    └─────────────┘
```

### Scaling Rules

| Condition | Action | Cooldown |
|---|---|---|
| Queue depth > 100 for 15s | Scale up +1 worker (if budget allows) | 30s |
| Queue depth = 0 for 60s | Scale down -1 worker | 60s |
| Queue depth > 500 | Emergency scale +2 (if budget allows) | None |
| Estimated hourly cost > budget | Block all scale-up | — |

Bounds: min=2, max=10 per worker type. Default budget: $50/hr.

**Worker Cost Model (per hour):**

| Worker Type | Cost/Hour | Cost/Task |
|---|---|---|
| Text | $0.50 | $0.001 |
| Image | $2.00 | $0.010 |
| Video | $8.00 | $0.100 |

### Why Not Kubernetes HPA?

The spec requires `docker compose up`. Kubernetes would add significant complexity. The Docker socket approach demonstrates the scaling concept while remaining operationally simple. In production, this would be replaced by Kubernetes HPA with custom metrics from Prometheus.

---

## Real-Time Delivery (WebSocket)

### Architecture

```
┌──────────┐  result   ┌────────────┐  PUBLISH      ┌───────┐  subscribe    ┌─────────┐
│  Worker  │──────────>│ PostgreSQL │  "task:{id}"  │ Redis │  "task:*"    │ Gateway │
│          │  persist  │            │──────────────>│ pub/  │────────────> │         │
└──────────┘           └────────────┘               │ sub   │             │  STOMP  │
                                                    └───────┘             │  Broker │
                                                                          └────┬────┘
                                                                               │
                                                                          WebSocket
                                                                          /topic/tasks/{id}
                                                                               │
                                                                          ┌────▼────┐
                                                                          │ Client  │
                                                                          │ (real-  │
                                                                          │  time)  │
                                                                          └─────────┘
                                                                               │
                                                                          fallback:
                                                                          GET /api/v1/tasks/{id}
```

### Why Redis Pub/Sub (Not RabbitMQ)?

- Workers already publish to RabbitMQ for task processing — using the same broker for WebSocket notifications would couple the status delivery path to the task processing path.
- Redis pub/sub is fire-and-forget, which is correct semantics for status updates (if the client isn't connected, they can poll).
- Redis is already in the stack for rate limiting — no additional infrastructure.

### STOMP Protocol

We use STOMP over WebSocket (via Spring's built-in broker) for:
- Topic-based subscriptions (`/topic/tasks/{taskId}`)
- Automatic message framing
- Client library availability (JavaScript, Python, etc.)

---

## Data Model

### Task Lifecycle

```
PENDING → QUEUED → PROCESSING → COMPLETED
                              → FAILED → (retry) → PROCESSING
                              → DEAD_LETTERED
```

### PostgreSQL Schema

```sql
CREATE TABLE tasks (
    id              UUID PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    task_type       VARCHAR(32) NOT NULL,
    priority        INT NOT NULL,
    status          VARCHAR(32) NOT NULL,
    payload         JSONB NOT NULL,
    result          JSONB,
    retry_count     INT DEFAULT 0,
    error_message   TEXT,
    region          VARCHAR(32) DEFAULT 'us-east',
    dag_id          UUID,
    parent_task_id  UUID,
    cost            DOUBLE PRECISION DEFAULT 0.0,
    storage_tier    VARCHAR(16) DEFAULT 'HOT',
    worker_version  VARCHAR(32),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMP
);

CREATE INDEX idx_tasks_tenant_status ON tasks(tenant_id, status);
CREATE INDEX idx_tasks_status ON tasks(status);
CREATE INDEX idx_tasks_created_at ON tasks(created_at);
CREATE INDEX idx_tasks_dag_id ON tasks(dag_id);
CREATE INDEX idx_tasks_parent_id ON tasks(parent_task_id);
CREATE INDEX idx_tasks_storage_tier ON tasks(storage_tier);
```

**Index justification:**
- `idx_tasks_tenant_status` — "show me all pending tasks for tenant X" (dashboard, status queries)
- `idx_tasks_status` — "count tasks by status" (metrics, monitoring)
- `idx_tasks_created_at` — "purge tasks older than X" (data retention)
- `idx_tasks_dag_id` — "show all tasks in this DAG" (DAG status queries)
- `idx_tasks_parent_id` — "find dependent tasks to trigger" (DAG progression)
- `idx_tasks_storage_tier` — "batch migrate tasks between storage tiers" (tiered storage eviction)

---

## Observability Architecture

### Metrics Pipeline

```
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│   API Gateway    │  │     Workers      │  │   Autoscaler     │
│                  │  │                  │  │                  │
│ Micrometer       │  │ Micrometer       │  │ Micrometer       │
│ /actuator/       │  │ /actuator/       │  │ /actuator/       │
│  prometheus      │  │  prometheus      │  │  prometheus      │
└────────┬─────────┘  └────────┬─────────┘  └────────┬─────────┘
         │                     │                      │
         └──────────┬──────────┴──────────────────────┘
                    │  scrape every 15s
                    ▼
           ┌────────────────┐
           │  Prometheus    │
           │  :9090         │
           │                │
           │  TSDB storage  │
           │  PromQL engine │
           └───────┬────────┘
                   │  query
                   ▼
           ┌────────────────┐        ┌───────────────────────────────┐
           │  Grafana       │        │  Structured Logging           │
           │  :3000         │        │                               │
           │                │        │  Logback + JSON layout + MDC  │
           │  Dashboards:   │        │  Fields: traceId, taskId,     │
           │  - Queue Depth │        │    tenantId, workerType       │
           │  - Latency     │        │                               │
           │  - Throughput  │        │  stdout → Docker logs         │
           │  - Failure Rate│        │                               │
           │  - Cost        │        └───────────────────────────────┘
           └────────────────┘
```

### Key Metrics

| Metric | Type | Labels | Purpose |
|---|---|---|---|
| `flik_tasks_submitted_total` | Counter | type, priority, tenant | Ingestion rate |
| `flik_tasks_completed_total` | Counter | type, status | Completion/failure rate |
| `flik_task_processing_seconds` | Histogram | type | p50/p95/p99 latency |
| `flik_queue_depth` | Gauge | queue | Backpressure indicator |
| `flik_worker_active` | Gauge | type | Worker utilization |
| `flik_rate_limit_rejected_total` | Counter | tenant | Rate limit pressure |
| `flik_retry_total` | Counter | type, attempt | Retry frequency |
| `flik_dlq_depth` | Gauge | — | Dead letter accumulation |
| `flik_cross_region_requests_total` | Counter | from, to | Cross-region request volume |
| `flik_inter_region_latency_seconds` | Timer | from, to | Simulated inter-region latency |
| `flik_task_cost_total` | Counter | type, tenant | Cumulative cost per task type/tenant |
| `flik_estimated_hourly_cost` | Gauge | — | Current projected hourly worker cost |
| `flik_storage_hits_total` | Counter | tier | Storage tier hit distribution (HOT/WARM/COLD/MISS) |

### Alerting Thresholds (Dashboard Visual)

- Queue depth > 1000 → Yellow
- Queue depth > 5000 → Red
- Failure rate > 20% → Red
- p99 latency > 30s → Yellow
- DLQ depth increasing → Red

### Structured Logging

All services use Logback with JSON layout and MDC (Mapped Diagnostic Context):

- `traceId` — unique per request, propagated from gateway to worker
- `taskId` — set when processing a specific task
- `tenantId` — set from the task's tenant
- `workerType` — TEXT/IMAGE/VIDEO

---

## Trade-offs & Alternatives Rejected

### 1. Single Queue vs. Per-Priority Queues

**Chosen:** Per-priority queues (flik.tasks.p0, p1, p2).

**Why not single queue with priority field?** RabbitMQ's priority queue implementation has a known behavior: when the queue is empty and a low-priority message arrives, it's delivered immediately. Priority only takes effect when messages are waiting. With separate queues, we can configure workers to always drain P0 before P1, giving true priority ordering.

**Trade-off:** More queues to manage, but only 3 — well within operational limits.

### 2. Push vs. Pull for Status Updates

**Chosen:** Push via WebSocket + Pull via REST polling.

**Why both?** WebSocket provides real-time updates when the client is connected. REST polling is the fallback for clients that disconnect and reconnect. Serving only one would either sacrifice real-time experience or require all clients to maintain persistent connections.

### 3. PostgreSQL vs. Redis for Task Storage

**Chosen:** PostgreSQL for durable storage, Redis only for ephemeral data (rate limits, pub/sub).

**Why not Redis for everything?** Task data is the system's source of truth. Redis persistence (RDB/AOF) can lose recent writes on crash. PostgreSQL's WAL guarantees we never lose a completed task result. The slight latency penalty (1-2ms vs 0.1ms) is irrelevant for task storage operations.

### 4. Separate Worker Binaries vs. Single Binary with Profiles

**Chosen:** Single Spring Boot application, different profiles activate different processors.

**Why?** Reduces build complexity (one JAR, one Dockerfile). Docker Compose service definitions set the `SPRING_PROFILES_ACTIVE` environment variable to `text`, `image`, or `video`. In production, you'd build separate images for each worker type for independent versioning.

### 5. At-Least-Once vs. Exactly-Once Processing

**Chosen:** At-least-once delivery with idempotent result storage.

**Why?** Exactly-once is impossible in a distributed system without 2PC. Our approach: RabbitMQ delivers at-least-once (ACK after processing), and the PostgreSQL upsert on task ID ensures duplicate completions are harmless. This is the standard pattern for reliable task processing.

### 6. Circuit Breakers — Why Not Needed Here

**Chosen:** No circuit breaker library (e.g., Resilience4j). Resilience is handled by retry/DLQ, health checks, and the async-first architecture.

**Why?** Circuit breakers protect against cascading failures in synchronous call chains (Service A → B → C). This architecture has no such chain — the gateway enqueues to RabbitMQ and returns 202 immediately. Workers pull asynchronously. There is no synchronous dependency from the client through to a worker that could cascade.

The system achieves equivalent resilience through:
- **Retry with exponential backoff** (5s → 15s → 60s via RabbitMQ TTL queues) — handles transient failures
- **Dead letter queue** after 3 retries — prevents poison messages from blocking the queue
- **Health check endpoints** — gateway returns 503 when PostgreSQL/RabbitMQ/Redis are down
- **Graceful degradation** — Redis down → fallback to in-memory rate limiting; WebSocket unavailable → REST polling fallback
- **Backpressure via queuing** — RabbitMQ absorbs spikes; workers pull at their own pace

**When would we add circuit breakers?** If the architecture evolved to include synchronous service-to-service calls (e.g., gateway calling worker directly, or cross-service REST calls), Resilience4j circuit breakers would be appropriate. In the current queue-based design, they would add complexity with no benefit.

---

## Failure Modes & Mitigations

| Failure | Impact | Mitigation |
|---|---|---|
| Worker crash mid-processing | Task appears stuck | RabbitMQ redelivers unACKed message after consumer timeout (30s). Task retried on another worker. |
| RabbitMQ down | Cannot enqueue new tasks | API Gateway health check returns 503. Client retries. Messages in flight are durable (persisted to disk). |
| PostgreSQL down | Cannot persist results | Workers NACK messages (requeue). Results are delivered when DB recovers. No data loss. |
| Redis down | Rate limiting disabled, WebSocket updates stop | Gateway falls back to in-memory rate limiting. Clients fall back to REST polling. Degraded but functional. |
| Network partition (gateway ↔ RabbitMQ) | Enqueue fails | Gateway returns 503 for new tasks. Existing in-flight tasks unaffected (workers have their own RabbitMQ connection). |
| Queue overflow (millions of messages) | Memory pressure on RabbitMQ | Queue length limit configured. Overflow messages are dropped (newest first) with metric increment. Autoscaler adds workers. |
| Slow consumer (video worker backlog) | P2 queue grows | Autoscaler detects depth > threshold, adds video workers. P0/P1 unaffected (separate queues, separate workers). |

---

## What Changes at 10x and 100x Scale

### Current Design Limits

The current single-node architecture handles ~500-5000 req/s comfortably. Here's what breaks and what changes:

### At 10x (5,000-50,000 req/s)

| Component | Bottleneck | Solution |
|---|---|---|
| API Gateway | Single instance saturates | Deploy multiple gateway instances behind a load balancer. Session affinity for WebSocket (or use Redis-backed Spring Session). |
| RabbitMQ | Single node message throughput | RabbitMQ clustering with quorum queues. Alternatively, shard queues across nodes. |
| PostgreSQL | Write throughput on tasks table | Connection pooling (PgBouncer), partitioning by `created_at`, or write-ahead to Redis with async PostgreSQL flush. |
| WebSocket | Too many connections for one gateway | Distribute WebSocket connections across gateway instances. Redis pub/sub already supports multi-instance fan-out. |

### At 100x (50,000-500,000 req/s)

| Component | Bottleneck | Solution |
|---|---|---|
| Queue | RabbitMQ clustering limits | Migrate to Kafka for raw throughput. Implement priority as consumer-side logic (consume from high-priority topics first). |
| Database | PostgreSQL single-writer limit | Shard by tenant ID across multiple PostgreSQL instances. Use Citus for distributed PostgreSQL or move to a distributed DB (CockroachDB, YugabyteDB). |
| Status Delivery | Redis pub/sub fan-out at 100k+ | Replace with Kafka topics for status events. Clients consume from partitioned status topics. |
| Rate Limiting | Redis single-node throughput | Redis Cluster with hash-slot distribution by tenant ID. |
| Autoscaling | Docker Compose doesn't scale | Kubernetes with HPA based on custom Prometheus metrics. Worker deployments auto-scale on queue depth. |
| Observability | Prometheus scrape volume | Switch to push-based metrics (OpenTelemetry Collector → Prometheus remote write or Thanos). |

### Architecture Evolution Path

```
Current:  Monolith gateway + RabbitMQ + PostgreSQL
  ↓ 10x
Mid:      Multi-instance gateway + RabbitMQ cluster + PgBouncer + Redis Cluster
  ↓ 100x
Target:   K8s + Kafka + Sharded PostgreSQL + OpenTelemetry + Multi-region
```

The current architecture is intentionally simple for the target scale. Every component was chosen with a clear upgrade path — no dead ends.

---

## Stretch: Multi-Region Simulation

### Overview

The pipeline simulates two geographic regions — `us-east` (port 8080) and `us-west` (port 8081) — each running its own API Gateway instance. All workers and infrastructure (RabbitMQ, PostgreSQL, Redis) are shared, simulating a single-cluster-multi-gateway topology.

### How It Works

```
Client → POST /api/v1/tasks { "region": "us-west" }
  → us-east gateway receives request
  → RegionRoutingService resolves target region: "us-west"
  → Since local region (us-east) ≠ target (us-west):
      → Simulate 70ms ± 20% inter-region latency (Thread.sleep)
      → Increment flik_cross_region_requests_total{from=us-east, to=us-west}
      → Record flik_inter_region_latency_seconds{from=us-east, to=us-west}
  → Task persisted with region="us-west"
  → Message enqueued with region field in payload
  → Worker result includes region in output metadata
```

### Inter-Region Latency Matrix

| From \ To | us-east | us-west | eu-west | ap-south |
|---|---|---|---|---|
| **us-east** | 1ms | 70ms | 90ms | 200ms |
| **us-west** | 70ms | 1ms | 140ms | 150ms |
| **eu-west** | 90ms | 140ms | 1ms | 130ms |
| **ap-south** | 200ms | 150ms | 130ms | 1ms |

Each latency has ±20% random jitter to simulate real-world variance.

### Docker Compose Topology

```yaml
api-gateway:          # FLIK_REGION=us-east, port 8080
api-gateway-region-b: # FLIK_REGION=us-west, port 8081
```

Both gateways share the same RabbitMQ, PostgreSQL, and Redis instances. Prometheus scrapes both with region labels.

### Design Decisions

- **Shared infrastructure, separate gateways:** In a real multi-region deployment, each region would have its own RabbitMQ and PostgreSQL replica. This simulation focuses on demonstrating request routing and latency behavior without the operational complexity of multi-cluster data replication.
- **Latency via `Thread.sleep`:** Java 21 virtual threads make this safe — sleeping a virtual thread does not block a platform thread. This accurately simulates the caller's experience without consuming worker resources.
- **Region stored on the task:** The `region` column enables per-region analytics ("what's the p99 latency for us-west tasks?") and could be used for region-affinity scheduling in a production system.

---

## Stretch: Task DAGs

### Overview

Task DAGs allow submitting a chain of dependent tasks that execute sequentially — the next step only starts when the previous step completes. A typical use case: `TEXT_GENERATE → IMAGE_UPSCALE → VIDEO_WATERMARK`.

### API

**Submit a DAG:**

```bash
POST /api/v1/dags
Authorization: Bearer <token>

{
  "tenantId": "tenant-1",
  "priority": 0,
  "region": "us-east",
  "steps": [
    { "taskType": "TEXT", "payload": { "prompt": "Generate a product description" } },
    { "taskType": "IMAGE", "payload": { "action": "upscale", "source": "from_previous" } },
    { "taskType": "VIDEO", "payload": { "action": "watermark", "source": "from_previous" } }
  ]
}
```

**Response:**

```json
{
  "dagId": "UUID",
  "status": "RUNNING",
  "tasks": [
    { "taskId": "UUID-1", "taskType": "TEXT", "status": "QUEUED", "dagId": "..." },
    { "taskId": "UUID-2", "taskType": "IMAGE", "status": "PENDING", "dagId": "...", "parentTaskId": "UUID-1" },
    { "taskId": "UUID-3", "taskType": "VIDEO", "status": "PENDING", "dagId": "...", "parentTaskId": "UUID-2" }
  ]
}
```

**Check DAG status:**

```bash
GET /api/v1/dags/{dagId}
```

### How DAG Progression Works

```
DagService.submitDag()
  → Creates N tasks in PostgreSQL with parent-child relationships
  → Only the FIRST task is set to QUEUED and enqueued to RabbitMQ
  → Remaining tasks are PENDING (not enqueued)

Worker completes Task-1
  → ResultService publishes "COMPLETED" to Redis pub/sub channel "task:{taskId}"
  → DagCompletionListener (in gateway) receives the event
  → Calls dagService.triggerNextStep(completedTaskId)
    → Finds tasks where parent_task_id = completedTaskId AND status = PENDING
    → Sets status to QUEUED and enqueues to RabbitMQ
  → Process repeats for Task-2 → Task-3
```

### Data Model

```
Task-1 (dagId=X, parentTaskId=null)  → QUEUED → PROCESSING → COMPLETED
                                                                    ↓ triggers
Task-2 (dagId=X, parentTaskId=1)     → PENDING ────────────→ QUEUED → PROCESSING → COMPLETED
                                                                                          ↓ triggers
Task-3 (dagId=X, parentTaskId=2)     → PENDING ────────────────────────────────→ QUEUED → PROCESSING → COMPLETED
```

### Design Decisions

- **Linear chains only (not arbitrary DAGs):** Each task has at most one `parentTaskId`. This keeps the implementation simple and covers the primary use case (sequential processing pipelines). Supporting fan-out/fan-in would require a `task_dependencies` join table and a barrier mechanism.
- **Event-driven progression via Redis pub/sub:** The `DagCompletionListener` subscribes to `task:*` channels. This decouples the DAG orchestration from the worker — workers don't know about DAGs. The gateway handles all orchestration.
- **Failure behavior:** If a step in the DAG fails and exhausts retries, it goes to DLQ and the DAG status becomes `FAILED`. Subsequent steps remain `PENDING` and are never enqueued.

---

## Stretch: Cost Modeling

### Overview

Every task processed incurs a simulated cost based on its type. Every running worker incurs an hourly cost. The autoscaler enforces a configurable budget ceiling — it refuses to scale up if doing so would exceed the budget.

### Cost Structure

**Per-task costs** (charged on completion):

| Task Type | Cost per Task |
|---|---|
| TEXT | $0.001 |
| IMAGE | $0.010 |
| VIDEO | $0.100 |

**Per-worker-hour costs** (charged continuously while running):

| Worker Type | Cost per Hour |
|---|---|
| Text worker | $0.50 |
| Image worker | $2.00 |
| Video worker | $8.00 |

### How It Works

```
Task completes
  → DagCompletionListener receives Redis event
  → CostService.recordTaskCost(tenantId, taskType)
    → Looks up cost from CostConstants
    → Increments flik_task_cost_total{type=TEXT, tenant=tenant-1} by $0.001
    → Stores cost on Task entity in PostgreSQL
    → Tracks per-tenant cumulative cost in memory (DoubleAdder)

Autoscaler evaluate loop (every 5s)
  → calculateCurrentHourlyCost()
    → For each worker type: replicas × cost_per_hour
    → Publishes flik_estimated_hourly_cost gauge
  → Before any scale-up:
    → isBudgetExceeded(service)?
    → If currentCost + additionalWorkerCost > BUDGET_PER_HOUR:
        → Log "Budget limit: skipping scale-up"
        → Do NOT scale
```

### API

```bash
GET /api/v1/costs
Authorization: Bearer <token>

Response:
{
  "totalCost": 12.45,
  "costPerType": { "TEXT": 0.001, "IMAGE": 0.01, "VIDEO": 0.10 },
  "workerCostPerHour": { "TEXT": 0.50, "IMAGE": 2.00, "VIDEO": 8.00 },
  "costPerTenant": { "tenant-1": 5.20, "tenant-2": 7.25 }
}
```

### Configuration

| Environment Variable | Default | Purpose |
|---|---|---|
| `AUTOSCALE_BUDGET_PER_HOUR` | `50.0` | Maximum allowed hourly worker cost |

### Design Decisions

- **Simulated costs, real constraints:** The dollar amounts are illustrative, but the budget enforcement is real — the autoscaler genuinely blocks scale-up when the budget is exceeded. This demonstrates cost-aware infrastructure.
- **Per-tenant tracking in memory:** The `CostService` uses `ConcurrentHashMap<String, DoubleAdder>` for thread-safe, lock-free cost accumulation. In production, this would be backed by a persistent store.
- **Budget applies to worker costs only:** Task costs are informational (tracked in Prometheus). The budget ceiling governs worker scaling decisions because that's where the real infrastructure spend occurs.

---

## Stretch: Tiered Storage

### Overview

Task results flow through three storage tiers with different access latencies and retention characteristics:

```
HOT  (Redis)       → 0.1ms reads, 10-minute TTL, auto-populated on completion
WARM (PostgreSQL)  → 1-2ms reads, indefinite retention, promoted from HOT after TTL
COLD (PostgreSQL)  → 1-2ms reads, archived marker, demoted from WARM after 24 hours
```

### How It Works

**Write path (on task completion):**
```
Worker completes task
  → ResultService persists result to PostgreSQL (storageTier = "HOT")
  → DagCompletionListener caches result in Redis with 10-min TTL
    → Key: "result:{taskId}", Value: result JSON
```

**Read path (on task retrieval):**
```
GET /api/v1/tasks/{taskId}
  → TieredStorageService.getResult(taskId)
    → Check Redis "result:{taskId}"
      → HIT: return immediately (flik_storage_hits_total{tier=HOT}++)
    → MISS: query PostgreSQL
      → Found: return result (flik_storage_hits_total{tier=WARM|COLD}++)
              → Re-cache in Redis for future reads
      → Not found: flik_storage_hits_total{tier=MISS}++
```

**Eviction/migration (scheduled every 60 seconds):**
```
TieredStorageService.evictAndMigrate()
  → UPDATE tasks SET storage_tier='WARM' WHERE storage_tier='HOT' AND completed_at < (now - 1 hour)
  → UPDATE tasks SET storage_tier='COLD' WHERE storage_tier='WARM' AND completed_at < (now - 24 hours)
```

### Storage Tier Lifecycle

```
Task completed
  → storageTier = HOT, result cached in Redis (TTL 10 min)
  → After 10 min: Redis key expires (automatic)
  → After 1 hour: batch job migrates HOT → WARM in PostgreSQL
  → After 24 hours: batch job migrates WARM → COLD in PostgreSQL
  → On cold read: result fetched from PostgreSQL, re-cached in Redis
```

### Metrics

| Metric | Meaning |
|---|---|
| `flik_storage_hits_total{tier=HOT}` | Cache hit — served from Redis |
| `flik_storage_hits_total{tier=WARM}` | Recent result — served from PostgreSQL |
| `flik_storage_hits_total{tier=COLD}` | Archived result — served from PostgreSQL with promotion |
| `flik_storage_hits_total{tier=MISS}` | Result not found |

### Design Decisions

- **Redis as hot tier, not a separate cache layer:** Redis is already in the stack for rate limiting and pub/sub. Using it as a result cache adds near-zero operational overhead.
- **PostgreSQL for both warm and cold:** In production, cold storage would be an object store (S3, MinIO) with compressed result blobs. For this simulation, we differentiate tiers with a `storage_tier` column — the query path is the same, but the tier marker enables analytics on access patterns.
- **Transparent retrieval:** Callers always use `GET /api/v1/tasks/{taskId}`. The storage tier is an implementation detail — the API doesn't change regardless of where the result lives.
- **Batch migration over per-record TTL:** Migrating tiers in batch (every 60s) is more efficient than per-record scheduled jobs. The batch `UPDATE` uses an index on `storage_tier` for performance.

---

## Stretch: Canary Deploys

### Overview

New worker versions are rolled out progressively: 10% of traffic → 50% → 100%. If the error rate of the canary version exceeds 20% (after at least 50 samples), the system automatically rolls back to the stable version.

### Rollout Stages

```
NONE ──→ CANARY_10 ──→ CANARY_50 ──→ FULL_100
  │           │              │
  │           ▼              ▼
  │      ROLLED_BACK    ROLLED_BACK
  │           │              │
  └───────────┴──────────────┘
```

| Stage | Canary % | Promotion Condition | Rollback Condition |
|---|---|---|---|
| `CANARY_10` | 10% | ≥50 samples, error rate ≤20% | Error rate >20% after ≥50 samples |
| `CANARY_50` | 50% | ≥50 samples, error rate ≤20% | Error rate >20% after ≥50 samples |
| `FULL_100` | 100% | Canary becomes new stable | — |
| `ROLLED_BACK` | 0% | — | Automatic on error spike |

### API (on Autoscaler, internal service)

**Start a canary deployment:**
```bash
POST /api/v1/canary/start
{ "version": "v2.0.0" }
```

**Check canary status:**
```bash
GET /api/v1/canary/status

Response:
{
  "stableVersion": "v1.0.0",
  "canaryVersion": "v2.0.0",
  "stage": "CANARY_10",
  "canaryPercentage": 10,
  "canarySuccesses": 42,
  "canaryFailures": 3,
  "canaryErrorRate": 0.0667
}
```

**Manual rollback:**
```bash
POST /api/v1/canary/rollback
```

**Report a result (called by workers or test harness):**
```bash
POST /api/v1/canary/result
{ "version": "v2.0.0", "success": true }
```

### How It Works

```
1. Operator calls POST /api/v1/canary/start { "version": "v2.0.0" }
   → CanaryService sets stage = CANARY_10
   → Workers report their version in task results (workerVersion field)

2. Workers process tasks and call POST /api/v1/canary/result
   → CanaryService.recordResult(version, success)
   → Increments per-version success/failure counters

3. Every 10 seconds, CanaryService.evaluateCanary() runs:
   → Counts successes and failures for canary version
   → If total < 50: not enough data, wait
   → If errorRate > 0.20: ROLLBACK (set stage = ROLLED_BACK, clear canary)
   → If errorRate ≤ 0.20:
       → CANARY_10 → promote to CANARY_50 (reset counters)
       → CANARY_50 → promote to FULL_100 (canary becomes stable)

4. On FULL_100: stableVersion = canaryVersion
   → All traffic now goes to new version
```

### Traffic Routing

The `CanaryService.getVersionForTraffic(trafficSlot)` method determines which version to use:

```java
int pct = getCanaryPercentage();  // 10, 50, or 100
return (trafficSlot % 100) < pct ? canaryVersion : stableVersion;
```

The `trafficSlot` can be derived from a hash of the task ID, ensuring consistent routing for the same task across retries.

### Worker Version Tracking

- Workers are configured with `WORKER_VERSION` environment variable (default: `v1.0.0`)
- The version is injected via `@Value("${worker.version}")` into the `TaskProcessor`
- On task completion, the `workerVersion` is:
  - Stored on the `Task` entity in PostgreSQL
  - Included in the task result JSON
  - Usable for per-version Prometheus metric analysis

### Design Decisions

- **Autoscaler hosts the canary controller:** The autoscaler is already the infrastructure management component. Adding canary logic here avoids a new service and keeps deployment orchestration centralized.
- **Statistical promotion (not time-based):** The canary promotes after collecting 50 samples at each stage, not after a fixed time window. This ensures promotion decisions are statistically meaningful even under low traffic.
- **20% error threshold:** Conservative default. In production this would be configurable and could use more sophisticated statistical tests (chi-squared, CUSUM).
- **Manual trigger, automatic progression:** Starting a canary requires an explicit API call. Progression and rollback are fully automatic. This balances operator control with automated safety.

---

## Load Test Simulations

All load tests are implemented as Gatling simulations using the Java DSL, located in `flik-load-test/src/test/java/com/flik/loadtest/`. Each simulation is self-contained and can be run independently.

### Simulation Summary

| Simulation | Spec Requirement | Duration | Peak Load | Key Assertion |
|---|---|---|---|---|
| Baseline | 1,000 concurrent users, 500 req/s for 5 min | ~6 min | 500 req/s | p99 < 200ms, <5% failures |
| Burst | Spike to 5,000 req/s for 30s, return to baseline | ~3 min | 5,000 req/s | p99 < 5,000ms |
| Chaos | Kill 50% of workers mid-test, system recovers | ~6 min | 50 req/s | <10% failures |
| Fairness | One tenant sends 10x, others not degraded | ~3 min | 650 req/s total | Normal tenants <1% failures, heavy tenant >20% failures |
| DAG | Task dependency chains complete end-to-end | ~2.5 min | 50 DAGs/s | <5% failures, all 3 steps verified |
| Multi-Region | Cross-region routing with simulated latency | ~2.5 min | 400 req/s | <2% failures |
| Canary | Progressive rollout 10% → 50% → 100% with auto-promotion | ~5 min | 100 req/s | <5% failures, stage=FULL_100 |

---

### 1. Baseline Simulation

**Spec:** *1,000 concurrent users, sustained 500 req/s for 5 minutes.*

Validates the system handles steady-state production load with low latency and zero errors.

```
┌─────────┐         ┌─────────┐         ┌────────────┐
│ Gatling │         │ Gateway │         │ PostgreSQL │
└────┬────┘         └────┬────┘         └─────┬──────┘
     │                   │                    │
     │  ╔═══════════════════════════════════╗ │
     │  ║  loop: 500 users/sec for 5 min   ║ │
     │  ╚═══════════════════════════════════╝ │
     │                   │                    │
     │ POST /api/v1/tasks│                    │
     │──────────────────>│                    │
     │   202 + taskId    │                    │
     │<──────────────────│                    │
     │                   │                    │
     │  (pause 750-1250ms)                    │
     │                   │                    │
     │ GET /tasks/{id}   │                    │
     │──────────────────>│  SELECT task       │
     │                   │───────────────────>│
     │  200 + status     │    result          │
     │<──────────────────│<───────────────────│
     │                   │                    │
```

**Injection Profile:**
- Ramp from 10 to 500 users/sec over 30 seconds
- Hold at 500 users/sec for 5 minutes
- Pause between submit and check: 750-1250ms (achieves ~1,000 concurrent users)

**Assertions:**
- p99 response time < 200ms
- Failed requests < 5%

**What It Proves:** The gateway can sustain 500 req/s with sub-200ms p99 latency. The async architecture (immediate 202 response) keeps the API fast regardless of backend processing load.

---

### 2. Burst Simulation

**Spec:** *Spike to 5,000 req/s for 30 seconds, return to baseline — no dropped requests.*

Validates the system absorbs traffic spikes via RabbitMQ buffering without connection errors.

```
req/s
5000 │              ┌──────────┐
     │             ╱            ╲
     │            ╱              ╲
 500 │ ──────────┘                └──────────────────────── ╲
     │╱                                                      ╲
  10 │                                                        └──
     └───┬──────┬──────┬──────────┬──────┬──────────────────┬────→ time
       0:15   0:45   0:55      1:25   1:35               2:35  2:50
       ramp   base   ramp     BURST   ramp      recovery      cool
      to 500   500  to 5000   5000   to 500       500          down
```

**Injection Profile:**
- Ramp to 500 req/s baseline (15s)
- Hold baseline (30s)
- Ramp to 5,000 req/s (10s)
- Hold burst at 5,000 req/s (30s)
- Ramp back down to 500 req/s (10s)
- Recovery at 500 req/s (60s)
- Cool down to 0 (15s)

**Assertions:**
- p99 response time < 5,000ms

**What It Proves:** RabbitMQ absorbs the burst — tasks queue up during the spike but the gateway keeps accepting requests. The return-to-baseline phase shows the system recovers gracefully as workers drain the queue. Rate limiting (429) fires for tenants exceeding their per-second quota during the spike.

---

### 3. Chaos Simulation

**Spec:** *Kill 50% of workers mid-test — system recovers, redistributes, loses no tasks.*

This is the most architecturally nuanced test. Because the API is async (returns 202 instantly), killing workers has no effect on POST response times. To make chaos visible, this simulation measures **end-to-end task completion time** — submit a task, then poll until it reaches COMPLETED.

```
Timeline:  0 min          2 min                    4 min          6 min
           │──── BASELINE ──│───── DEGRADED ─────────│── RECOVERY ──│

Phase 1: Baseline (0-2 min) — all workers running
┌─────────┐    POST /api/v1/tasks     ┌─────────┐    enqueue     ┌──────────┐
│ Gatling │ ─────────────────────────> │ Gateway │ ─────────────> │ RabbitMQ │
│         │ <───── 202 + taskId ────── │         │                └────┬─────┘
│         │                            │         │                     │
│         │  GET /tasks/{id} (poll)    │         │                ┌────▼─────┐
│         │ ─────────────────────────> │         │                │ Workers  │
│         │ <───── COMPLETED ───────── │         │                │ (all up) │
└─────────┘  (1-2 polls = ~4s)        └─────────┘                └──────────┘

Phase 2: Degraded (2-4 min) — 50% workers killed, autoscaler stopped
┌──────────────┐  docker stop autoscaler
│ Orchestrator │  docker stop 50% workers
└──────┬───────┘  docker ps (verify)
       │
┌──────▼──┐    POST /api/v1/tasks     ┌─────────┐    enqueue     ┌──────────┐
│ Gatling │ ─────────────────────────> │ Gateway │ ─────────────> │ RabbitMQ │
│         │ <───── 202 + taskId ────── │         │                └────┬─────┘
│         │                            │         │                     │ piling up
│         │  GET /tasks/{id} (poll)    │         │                ┌────▼─────┐
│         │ ─────────────────────────> │         │                │ Workers  │
│         │ <───── QUEUED ──────────── │         │                │ (50% ×)  │
└─────────┘  (many polls, tasks stuck) └─────────┘                └──────────┘

Phase 3: Recovery (4-6 min) — workers restarted, queue drains
┌──────────────┐  docker start workers
│ Orchestrator │  docker start autoscaler
└──────┬───────┘
       │
┌──────▼──┐  GET /tasks/{id} (poll)   ┌─────────┐                ┌──────────┐
│ Gatling │ ─────────────────────────> │ Gateway │                │ Workers  │
│         │ <───── COMPLETED ───────── │         │                │ (all up) │
└─────────┘  (queue drains, normal)    └─────────┘                └──────────┘
```

**Key Design Decisions:**

- **Dynamic worker discovery:** Uses `docker compose ps --format {{.Name}} --status running` to find all worker containers at runtime, then kills exactly 50% by stopping specific container names.
- **Autoscaler stopped first:** The autoscaler is stopped before any workers are killed and verified to be down. This prevents it from detecting the reduced capacity and spinning up replacements.
- **Restart order:** Workers are restarted first, then the autoscaler, so workers are healthy before monitoring resumes.
- **Why polling, not POST latency:** The gateway returns 202 in 2-5ms regardless of worker state — it only enqueues to RabbitMQ. The chaos impact is visible in how long tasks take to reach COMPLETED status, which requires polling.

**Injection Profile:**
- Ramp from 5 to 50 users/sec over 20 seconds
- Hold at 50 users/sec for 6 minutes
- Each user: submit task → poll every 2s until COMPLETED/FAILED (max 30 polls = 60s timeout)
- Orchestrator runs as a single parallel user controlling Docker commands

**Assertions:**
- Failed requests < 10% (accounts for tasks that time out during degraded phase)

**What It Proves:** Tasks submitted during the degraded phase are not lost — they queue in RabbitMQ and complete once workers restart. The increased poll count during degradation is visible in the Gatling report as higher response time distribution. Recovery phase shows the system returning to normal throughput.

---

### 4. Fairness Simulation

**Spec:** *One tenant sends 10x more requests — other tenants' latency is not significantly degraded.*

Validates that per-tenant rate limiting prevents a noisy neighbor from starving other tenants.

```
Injection                    API Gateway               Expected Outcome
─────────                    ───────────               ────────────────

tenant-heavy ─── 500 req/s ──┐
                              │
tenant-normal-1 ─ 50 req/s ──┤   ┌─────────────────┐
                              ├──>│  Rate Limiter    │──> Heavy:  >20% rejected (429)
tenant-normal-2 ─ 50 req/s ──┤   │  300 req/s/tenant│
                              │   └─────────────────┘──> Normal: <1% rejected
tenant-normal-3 ─ 50 req/s ──┘

Total: ~650 req/s                 Heavy exceeds 300 limit → throttled
                                  Normal tenants under limit → unaffected
```

**Injection Profile:**
- Heavy tenant (`tenant-heavy`): ramp to 500 req/s, hold for 3 minutes
- Normal tenants (`tenant-normal-1/2/3`): ramp to 50 req/s each, hold for 3 minutes
- Total: ~650 req/s (500 + 3×50)

**Assertions:**
- Normal tenants: <1% failure rate (all requests accepted)
- Heavy tenant: >20% failure rate (rate-limited by Redis token bucket)

**What It Proves:** The per-tenant rate limiter (Redis token bucket at 300 req/s) throttles the heavy tenant while normal tenants operate unimpeded. RabbitMQ's `prefetch=1` ensures fair consumption at the worker level — one tenant's burst doesn't preempt others in the queue.

---

### 5. DAG Simulation

**Spec (stretch goal):** *Tasks that depend on other tasks (generate → upscale → watermark).*

Validates that multi-step DAG chains execute sequentially and complete end-to-end under load.

```
┌─────────┐  POST /api/v1/dags   ┌─────────┐                ┌──────────┐  ┌─────────┐
│ Gatling │ ────────────────────> │ Gateway │                │ RabbitMQ │  │ Workers │
│         │ <── 202 + dagId ───── │         │                └────┬─────┘  └────┬────┘
│         │    + step1Id          │         │                     │              │
│         │    + step2Id          │         │  enqueue step1      │              │
│         │    + step3Id          │         │ ───────────────────>│  pull step1  │
│         │                       │         │                     │─────────────>│
│         │                       │         │                     │  COMPLETED   │
│         │                       │         │ <── Redis pub/sub ──│<─────────────│
│         │                       │  DagCompletionListener        │              │
│         │                       │  triggers step2               │              │
│         │                       │         │ ───────────────────>│  pull step2  │
│         │                       │         │                     │─────────────>│
│         │                       │         │                     │  COMPLETED   │
│         │                       │         │ <── Redis pub/sub ──│<─────────────│
│         │                       │  DagCompletionListener        │              │
│         │                       │  triggers step3               │              │
│         │                       │         │ ───────────────────>│  pull step3  │
│         │                       │         │                     │─────────────>│
│         │                       │         │                     │  COMPLETED   │
│         │                       │         │ <── Redis pub/sub ──│<─────────────│
│         │                       │         │                     │              │
│         │  Poll: GET /dags/{id} │         │                     │              │
│         │ ────────────────────> │         │                     │              │
│         │ <── COMPLETED ─────── │         │                     │              │
│         │                       │         │                     │              │
│         │  Verify each step:    │         │                     │              │
│         │  GET /tasks/{step1Id} │         │                     │              │
│         │ ────────────────────> │ COMPLETED                     │              │
│         │  GET /tasks/{step2Id} │         │                     │              │
│         │ ────────────────────> │ COMPLETED                     │              │
│         │  GET /tasks/{step3Id} │         │                     │              │
│         │ ────────────────────> │ COMPLETED                     │              │
└─────────┘                       └─────────┘                     └──────────────┘
```

**Injection Profile:**
- Ramp from 1 to 50 DAGs/sec over 15 seconds
- Hold at 50 DAGs/sec for 2 minutes
- Each DAG: submit → poll every 3s until COMPLETED (max 20 polls = 60s timeout) → verify all 3 steps individually

**Assertions:**
- Failed requests < 5%

**What It Proves:** The DAG completion listener correctly triggers each subsequent step after the previous one completes. Under load (50 concurrent DAGs/sec = 150 tasks/sec across 3 types), the event-driven progression via Redis pub/sub remains reliable. Individual step verification confirms the full chain (TEXT → IMAGE → VIDEO) ran in order.

---

### 6. Multi-Region Simulation

**Spec (stretch goal):** *Simulate two regions with inter-region latency, demonstrate request routing.*

Validates that cross-region requests incur simulated latency while local requests remain fast.

```
┌──────────────────────┐
│    Gatling Client     │
│                       │
│  Local:  200 req/s    │
│  Cross:  200 req/s    │
└──────┬───────┬────────┘
       │       │
       │       │  region=us-west
       │       └──────────────────────┐
       │  region=us-east              │
       ▼                              ▼
┌──────────────────────────────────────────┐
│       us-east Gateway (port 8080)        │
│                                          │
│  ┌────────────────────────────────────┐  │
│  │      RegionRoutingService          │  │
│  │                                    │  │
│  │  local (us-east):   ~1ms           │  │
│  │  cross (us-west):  +70ms ± 20%    │  │
│  └────────────────────────────────────┘  │
└──────────────────┬───────────────────────┘
                   │
                   ▼
            ┌────────────┐
            │  RabbitMQ  │
            └────────────┘
```

**Injection Profile:**
- Local region scenario: ramp to 200 req/s, hold for 2 minutes (TEXT tasks, region=us-east)
- Cross-region scenario: ramp to 200 req/s, hold for 2 minutes (IMAGE tasks, region=us-west)
- Both scenarios hit the us-east gateway; cross-region requests incur simulated 70ms ± 20% latency

**Assertions:**
- Failed requests < 2%

**What It Proves:** The `RegionRoutingService` correctly detects cross-region requests and applies simulated inter-region latency (70ms ± jitter). Local requests are unaffected. The Gatling report shows a clear bimodal latency distribution — local requests cluster around 2-5ms while cross-region requests cluster around 70-90ms.

---

### 7. Canary Simulation

**Spec (stretch goal):** *Roll new worker version to 10% → 50% → 100% with auto-rollback on error spike.*

Validates the full canary deployment lifecycle: start a new version, report results, auto-promote through stages, and confirm the stable version updates.

```
Timeline:  0s            30s                              ~2 min         ~5 min
           │── BASELINE ──│───── CANARY LIFECYCLE ─────────│── VERIFY ────│

Phase 1: Baseline (0-30s) — all traffic on stable v1.0.0
┌─────────┐  100 req/s   ┌─────────┐
│ Gatling │ ────────────> │ Gateway │     All tasks processed by v1.0.0
│  Load   │ <── 202 ──── │  :8080  │
└─────────┘               └─────────┘

Phase 2: Start canary (30s)
┌──────────────┐  POST /api/v1/canary/start   ┌────────────┐
│ Orchestrator │ ───────────────────────────> │ Autoscaler │
│              │  { "version": "v2.0.0" }     │   :8082    │
│              │ <── stage: CANARY_10 ─────── │            │
└──────────────┘                              └────────────┘

Phase 3: Promote 10% → 50% (30s-~1min)
┌──────────────┐  POST /api/v1/canary/result (x60)  ┌────────────┐
│ Orchestrator │ ──────────────────────────────────> │ Autoscaler │
│              │  55 successes + 5 failures           │            │
│              │  (8.3% error rate < 20% threshold)   │            │
│              │                                      │ evaluateCanary()
│              │  GET /api/v1/canary/status            │ every 10s  │
│              │ ──────────────────────────────────> │            │
│              │ <── stage: CANARY_50 ─────────────── │            │
└──────────────┘                                      └────────────┘

Phase 4: Promote 50% → 100% (~1min-~2min)
┌──────────────┐  POST /api/v1/canary/result (x60)  ┌────────────┐
│ Orchestrator │ ──────────────────────────────────> │ Autoscaler │
│              │  55 successes + 5 failures           │            │
│              │                                      │            │
│              │  GET /api/v1/canary/status            │            │
│              │ ──────────────────────────────────> │            │
│              │ <── stage: FULL_100 ──────────────── │            │
│              │     stableVersion: v2.0.0            │            │
└──────────────┘                                      └────────────┘

Phase 5: Verification (~2min-~5min)
┌──────────────┐  GET /api/v1/canary/status   ┌────────────┐
│ Orchestrator │ ──────────────────────────> │ Autoscaler │
│              │ <── stableVersion: v2.0.0 ── │            │
└──────────────┘     stage: FULL_100          └────────────┘
```

**Two Parallel Scenarios:**
- **Load scenario:** Submits tasks at 100 req/s against the gateway (:8080) throughout the entire test
- **Orchestrator:** Controls the canary API on the autoscaler (:8082) — starts canary, reports results, polls for promotion, verifies final state

**Canary Promotion Logic:**
- The `CanaryService.evaluateCanary()` runs every 10 seconds
- Requires 50+ samples before making a promotion decision
- Error rate > 20% triggers automatic rollback; ≤ 20% triggers promotion
- Counters reset on each promotion (50+ fresh samples needed per stage)
- The simulation sends 55 successes + 5 failures (8.3% error rate) per stage

**Assertions:**
- Failed requests < 5%

**What It Proves:** The canary deployment mechanism works end-to-end — starting a canary, accumulating results, auto-promoting through 10% → 50% → 100%, and updating the stable version. The load scenario running in parallel confirms the system remains healthy throughout the rollout.

---

### Running the Simulations

From `flik-load-test/`:

```bash
cd flik-load-test
mvn gatling:test
```

An interactive picker will prompt you to choose a simulation by number (0 = Baseline, 1 = Burst, 2 = Canary, 3 = Chaos, 4 = DAG, 5 = Fairness, 6 = Multi-Region).

Reports are generated in `flik-load-test/target/gatling/` with interactive HTML dashboards showing request/response timelines, latency distributions, and error breakdowns.

**Actual results data** from running all 7 simulations (request counts, p50/p95/p99 latency, success rates, analysis) is available in [LOAD-TEST-RESULTS.md](LOAD-TEST-RESULTS.md). Pre-captured Gatling report PDFs and Grafana dashboard screenshots are in `load-test-results/`.

---

## Testing & Quality Assurance

### Unit Tests

Unit tests validate individual components in isolation using mocks and Spring's test slicing. All tests use **JUnit 5** with **Mockito** for mocking and **MockMvc** for controller testing via `@WebMvcTest`.

| Module | Test File | What It Tests |
|---|---|---|
| `flik-api-gateway` | `TaskControllerTest` | Task submission endpoint (202 response, validation, error cases) |
| `flik-api-gateway` | `DagControllerTest` | DAG submission and status query endpoints |
| `flik-api-gateway` | `CostControllerTest` | Cost summary endpoint |
| `flik-api-gateway` | `HealthControllerTest` | Health check endpoint (PostgreSQL, RabbitMQ, Redis status) |
| `flik-autoscaler` | `ScalingServiceTest` | Queue-depth-based scaling decisions, budget enforcement |
| `flik-autoscaler` | `CanaryServiceTest` | Canary promotion stages, rollback on error threshold, counter resets |
| `flik-common` | `QueueConstantsTest` | Queue name and routing key constants |
| `flik-common` | `CostConstantsTest` | Per-task and per-worker cost constant values |

**Test configuration notes:**
- Controller tests use `@WebMvcTest` with `@Import(SecurityConfig.class)` to include the API key auth filter
- A `@TestConfiguration` provides `SimpleMeterRegistry` to satisfy Micrometer dependencies without a real Prometheus endpoint

**Running unit tests:**

```bash
cd flik-pipeline
mvn test
```

### Integration / Load Tests (Gatling)

The 7 Gatling simulations serve as end-to-end integration tests — they exercise the full Docker Compose stack (gateway → RabbitMQ → workers → PostgreSQL → Redis) under realistic traffic patterns. Each simulation validates a specific system behavior:

| Simulation | Integration Coverage |
|---|---|
| Baseline | Full request lifecycle: submit → queue → process → persist → retrieve |
| Burst | Backpressure handling: RabbitMQ buffering under 10x traffic spike |
| Chaos | Fault tolerance: worker failure → RabbitMQ redelivery → recovery |
| Fairness | Rate limiting: Redis token bucket + RabbitMQ prefetch=1 |
| DAG | Task orchestration: Redis pub/sub → DagCompletionListener → sequential step triggering |
| Multi-Region | Region routing: inter-region latency simulation → region-specific task routing |
| Canary | Deployment lifecycle: autoscaler canary API → result reporting → auto-promotion |

**Running a single simulation:**

```bash
cd flik-load-test
mvn gatling:test
# Choose [0] for Baseline, [3] for Chaos, etc.
```

Reports are generated in `flik-load-test/target/gatling/` as interactive HTML dashboards.

### API Documentation (Swagger / OpenAPI)

All REST endpoints are documented with OpenAPI 3.0 annotations and served via an interactive Swagger UI. See the [API Gateway section](#api-gateway-flik-api-gateway) for full details including URLs, endpoint tags, and authentication configuration.

---

## Configuration Reference

All application-specific tuning knobs, organized by module. Standard Spring Boot infrastructure properties (datasource URLs, RabbitMQ host/port, Redis host/port) are omitted — they follow standard `SPRING_*` naming conventions and are configured in `docker-compose.yml`.

### API Gateway (`flik-api-gateway`)

| Environment Variable | Property Path | Default | Description |
|---|---|---|---|
| `RATE_LIMIT_REQUESTS_PER_SEC` | `rate-limit.requests-per-sec` | `300` | Max requests per second per tenant (Redis token bucket) |
| `FLIK_REGION` | `flik.region` | `us-east` | Gateway region identifier; determines local vs. cross-region routing |

### Worker Pool (`flik-worker`)

| Environment Variable | Property Path | Default | Description |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `spring.profiles.active` | — | Activates worker type: `text`, `image`, or `video` |
| `WORKER_CONCURRENCY` | `worker.concurrency` | `5` (video: `3`) | Concurrent RabbitMQ consumers per worker instance |
| `WORKER_VERSION` | `worker.version` | `v1.0.0` | Version tag reported in task results; used for canary tracking |

### Autoscaler (`flik-autoscaler`)

| Environment Variable | Property Path | Default | Description |
|---|---|---|---|
| `AUTOSCALE_MIN_WORKERS` | `autoscale.min-workers` | `2` | Minimum worker replicas per type (scale-down floor) |
| `AUTOSCALE_MAX_WORKERS` | `autoscale.max-workers` | `10` | Maximum worker replicas per type (scale-up ceiling) |
| `AUTOSCALE_QUEUE_THRESHOLD` | `autoscale.queue-threshold` | `100` | Queue depth that triggers a scale-up (sustained for 3 checks = 15s) |
| `AUTOSCALE_POLL_INTERVAL_MS` | `autoscale.poll-interval-ms` | `5000` | How often the autoscaler evaluates queue depth (ms) |
| `AUTOSCALE_BUDGET_PER_HOUR` | `autoscale.budget-per-hour` | `50.0` | Maximum allowed hourly worker cost ($); blocks scale-up when exceeded |

### Infrastructure Services (`docker-compose.yml`)

| Service | Variable | Default | Description |
|---|---|---|---|
| PostgreSQL | `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `flik` / `flik` / `flik` | Database name and credentials |
| RabbitMQ | `RABBITMQ_DEFAULT_USER` / `RABBITMQ_DEFAULT_PASS` | `flik` / `flik` | Broker credentials (Management UI: port 15672) |
| Grafana | `GF_SECURITY_ADMIN_USER` / `GF_SECURITY_ADMIN_PASSWORD` | `admin` / `admin` | Dashboard admin credentials (port 3000) |

### Queue Tuning (hardcoded in `flik-common`)

These are compile-time constants in `QueueConstants.java`. To change them, modify the source and rebuild.

| Parameter | Value | Description |
|---|---|---|
| Max priority | `10` | RabbitMQ `x-max-priority` on task queues |
| Retry TTLs | `5s`, `15s`, `60s` | Exponential backoff delays for retry queues |
| Max retries | `3` | Attempts before routing to dead letter queue |
| Prefetch count | `1` | RabbitMQ consumer prefetch (ensures fair scheduling) |

### Tiered Storage Timings

| Parameter | Value | Description |
|---|---|---|
| HOT cache TTL | 10 minutes | Redis key expiry for cached task results |
| HOT → WARM migration | 1 hour | Batch job moves completed tasks from HOT to WARM tier |
| WARM → COLD migration | 24 hours | Batch job moves completed tasks from WARM to COLD tier |
| Eviction job interval | 60 seconds | How often the migration batch job runs |

### Canary Deploy Thresholds

| Parameter | Value | Description |
|---|---|---|
| Error rate threshold | 20% | Canary version is rolled back if error rate exceeds this |
| Min samples for promotion | 50 | Minimum results before a promotion/rollback decision is made |
| Evaluation interval | 10 seconds | How often `evaluateCanary()` checks canary health |
| Rollout stages | 10% → 50% → 100% | Traffic percentages at each canary stage |
