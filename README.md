# Flik — Real-Time AI Generation Pipeline

A distributed task processing system for AI content generation at scale. Handles millions of concurrent generation requests with priority queuing, real-time status updates, automatic retries, and graceful degradation under load.

## Quick Start

```bash
# Clone and start everything
git clone <repo-url> && cd flik-pipeline
docker compose up --build

# Services available at:
# API Gateway:       http://localhost:8080
# API Gateway (us-west): http://localhost:8081
# Autoscaler:        http://localhost:8082
# RabbitMQ Console:  http://localhost:15672  (flik/flik)
# Grafana Dashboard: http://localhost:3000   (admin/admin)
# Prometheus:        http://localhost:9090
```

All services start automatically — API gateway (us-east + us-west), worker pool, autoscaler, RabbitMQ, PostgreSQL, Redis, Prometheus, and Grafana with pre-provisioned dashboards.

**Swagger UI:** [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) — Interactive API explorer (click **Authorize**, enter any token e.g. `test-token`).

## Architecture Overview

```
                ┌──────────────────────────────────────────────────────┐
                │              API Gateways (Multi-Region)             │
 Client ───────►│  us-east (:8080)  │  us-west (:8081)                │
                │  Auth │ Rate Limit │ Region Routing │ Swagger UI     │
                │  DAG Orchestration │ Tiered Storage │ Cost Tracking  │
 Client ◄──────│  WebSocket (Redis Pub/Sub) │ REST Polling             │
                └──────────┬───────────────────────────────────────────┘
                           │
                ┌──────────▼───────────────────────────────────────────┐
                │                     RabbitMQ                         │
                │  P0 (real-time) ──► Text Workers  (version tracked)  │
                │  P1 (interactive) ► Image Workers (version tracked)  │
                │  P2 (background) ─► Video Workers (version tracked)  │
                │  Retry Exchanges (5s/15s/60s TTL backoff)            │
                │  Dead Letter Queue                                   │
                └──────────┬───────────────────────────────────────────┘
                           │
                ┌──────────▼───────────────────────────────────────────┐
                │                    Worker Pool                       │
                │  Stateless, horizontally scalable                    │
                │  Simulated AI processing + worker version reporting  │
                │  ──► PostgreSQL (results + cost + storage tier)      │
                │  ──► Redis HOT cache (10-min TTL) + pub/sub          │
                └──────────────────────────────────────────────────────┘

 Autoscaler ──► Queue depth monitoring ──► Cost-aware scaling via Docker API
             ──► Canary deploy management (10% → 50% → 100% with auto-rollback)
 Prometheus ──► Scrapes all services (both regions) ──► Grafana dashboards
```

## Technology Choices

| Component | Technology | Why This, Not That |
|---|---|---|
| **Language** | Java 21 | Virtual threads for high-concurrency I/O (WebSocket connections, queue consumers) without thread pool exhaustion |
| **Framework** | Spring Boot 3.2 | Mature ecosystem: Spring AMQP for RabbitMQ, Spring WebSocket, Spring Data JPA, Micrometer metrics — all production-tested |
| **Queue** | RabbitMQ 3.13 | **Over Kafka:** Native priority queues (`x-max-priority`) and dead letter exchanges — both critical for this use case. Kafka requires custom priority logic and has no DLX. **Over Redis Streams:** Consumer groups lack priority support; DLQ must be built manually. **Over NATS:** Weaker persistence guarantees for task-critical workloads |
| **Database** | PostgreSQL 16 | ACID guarantees for task state transitions, JSONB for flexible payloads, mature indexing for status queries. **Over Redis:** Need durable persistence for task audit trail. **Over SQLite:** No concurrent write support at scale |
| **Cache/Rate Limit** | Redis 7 | Atomic Lua scripts for token-bucket rate limiting, pub/sub for real-time WebSocket fan-out, sub-millisecond latency |
| **Metrics** | Micrometer + Prometheus | Spring Boot Actuator auto-instruments JVM, HTTP, and RabbitMQ metrics. Prometheus pull model scales well |
| **Dashboard** | Grafana | Industry standard, pre-provisioned via config-as-code, rich visualization for p50/p95/p99 histograms |
| **Load Test** | Gatling (Java DSL) | JVM-native for consistency with the codebase, built-in WebSocket protocol support, HTML report generation |
| **Build** | Maven | Multi-module project with shared dependency management via parent POM |

## Project Structure

```
flik-pipeline/
├── pom.xml                    # Parent POM — dependency management
├── docker-compose.yml         # Full stack: all services + infrastructure
├── init.sql                   # PostgreSQL schema (auto-loaded on first start)
├── flik-common/               # Shared models, DTOs, constants
├── flik-api-gateway/          # REST API, WebSocket, auth, rate limiting
├── flik-worker/               # Task processors (text, image, video)
├── flik-autoscaler/           # Queue-depth monitoring, Docker scaling
├── flik-load-test/            # Gatling simulations (7 scenarios)
├── scripts/                   # Chaos test helper scripts
├── grafana/                   # Pre-provisioned dashboards
├── prometheus/                # Scrape configuration
└── docs/                      # Architecture & load test results
```

## API Reference

### Submit Task
```
POST /api/v1/tasks
Authorization: Bearer <api-key>

{
  "tenantId": "tenant-abc",
  "taskType": "IMAGE",       // TEXT | IMAGE | VIDEO
  "priority": 1,             // 0 (real-time) | 1 (interactive) | 2 (background)
  "payload": {
    "prompt": "A sunset over mountains",
    "style": "photorealistic"
  }
}

Response: 202 Accepted
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "QUEUED",
  "createdAt": "2026-02-17T14:00:00Z"
}
```

### Check Task Status
```
GET /api/v1/tasks/{taskId}

Response: 200 OK
{
  "taskId": "550e8400-...",
  "status": "COMPLETED",
  "taskType": "IMAGE",
  "result": { "url": "...", "metadata": {...} },
  "createdAt": "...",
  "completedAt": "..."
}
```

### Real-Time Status (WebSocket)
```
STOMP CONNECT ws://localhost:8080/ws
SUBSCRIBE /topic/tasks/{taskId}

Messages:
  { "taskId": "...", "status": "PROCESSING", "timestamp": "..." }
  { "taskId": "...", "status": "COMPLETED", "result": {...}, "timestamp": "..." }
```

### Health Check
```
GET /health

Response: 200 OK
{
  "status": "UP",
  "components": {
    "rabbitmq": "UP",
    "postgresql": "UP",
    "redis": "UP"
  }
}
```

### Submit DAG (Task Chain)
```
POST /api/v1/dags
Authorization: Bearer <api-key>

{
  "tenantId": "tenant-abc",
  "priority": 0,
  "region": "us-east",
  "steps": [
    { "taskType": "TEXT", "payload": { "prompt": "Generate description" } },
    { "taskType": "IMAGE", "payload": { "action": "upscale" } },
    { "taskType": "VIDEO", "payload": { "action": "watermark" } }
  ]
}

Response: 202 Accepted
{
  "dagId": "...",
  "status": "RUNNING",
  "tasks": [
    { "taskId": "...", "taskType": "TEXT", "status": "QUEUED" },
    { "taskId": "...", "taskType": "IMAGE", "status": "PENDING", "parentTaskId": "..." },
    { "taskId": "...", "taskType": "VIDEO", "status": "PENDING", "parentTaskId": "..." }
  ]
}
```

### Check DAG Status
```
GET /api/v1/dags/{dagId}

Response: 200 OK
{
  "dagId": "...",
  "status": "COMPLETED",
  "tasks": [...]
}
```

### Cost Summary
```
GET /api/v1/costs

Response: 200 OK
{
  "totalCost": 12.45,
  "costPerType": { "TEXT": 0.001, "IMAGE": 0.01, "VIDEO": 0.10 },
  "workerCostPerHour": { "TEXT": 0.50, "IMAGE": 2.00, "VIDEO": 8.00 },
  "costPerTenant": { "tenant-abc": 5.20 }
}
```

### Canary Deployment (Autoscaler — internal)
```
POST /api/v1/canary/start     { "version": "v2.0.0" }
GET  /api/v1/canary/status
POST /api/v1/canary/rollback
POST /api/v1/canary/result    { "version": "v2.0.0", "success": true }
```

## Multi-Region

Two API Gateway instances simulate geographic regions:

| Region | Gateway URL | Swagger UI |
|---|---|---|
| us-east | `http://localhost:8080` | `http://localhost:8080/swagger-ui.html` |
| us-west | `http://localhost:8081` | `http://localhost:8081/swagger-ui.html` |

Cross-region requests (e.g., submitting to us-east with `"region": "us-west"`) incur simulated inter-region latency (~70ms). Tasks submitted without a region default to the gateway's local region.

## Tiered Storage

Task results flow through three storage tiers:
- **HOT** — Redis cache, 10-minute TTL, sub-millisecond reads
- **WARM** — PostgreSQL, indefinite retention, 1-2ms reads
- **COLD** — PostgreSQL archived, promoted on access

Retrieval is transparent — `GET /api/v1/tasks/{taskId}` checks Redis first, falls back to PostgreSQL.

## Cost Modeling

Every task incurs a simulated cost based on type (TEXT: $0.001, IMAGE: $0.010, VIDEO: $0.100). Every running worker incurs an hourly cost (TEXT: $0.50/hr, IMAGE: $2.00/hr, VIDEO: $8.00/hr). The autoscaler enforces a configurable budget ceiling (`AUTOSCALE_BUDGET_PER_HOUR`, default $50/hr) — it refuses to scale up if doing so would exceed the budget. Per-tenant and per-type costs are tracked via `GET /api/v1/costs`.

## Canary Deploys

New worker versions are rolled out progressively via the autoscaler API (`http://localhost:8082`):

1. `POST /api/v1/canary/start { "version": "v2.0.0" }` — begins rollout at 10% traffic
2. Workers report results via `POST /api/v1/canary/result` — the canary evaluator runs every 10s
3. After 50+ samples with error rate ≤ 20%: auto-promotes 10% → 50% → 100%
4. If error rate > 20% at any stage: auto-rollback to stable version

Current status is available at `GET /api/v1/canary/status`. Manual rollback via `POST /api/v1/canary/rollback`.

## Load Tests

Seven simulations validate the system under realistic conditions:

| Simulation | Description | Success Criteria |
|---|---|---|
| **Baseline** | 1,000 concurrent users, 500 req/s for 5 minutes | p99 < 200ms, <5% failures |
| **Burst** | Spike to 5,000 req/s for 30 seconds, return to baseline | p99 < 5,000ms |
| **Chaos** | Kill 50% of workers mid-test, measure end-to-end completion | System recovers, <10% failures |
| **Fairness** | One tenant sends 10x traffic | Normal tenants <1% failures, heavy tenant >20% failures |
| **DAG** | Task dependency chains (TEXT → IMAGE → VIDEO) complete end-to-end | <5% failures, all 3 steps verified |
| **Multi-Region** | Cross-region requests incur simulated latency (~70ms) | <2% failures |
| **Canary** | Progressive rollout 10% → 50% → 100% with auto-promotion | <5% failures, stage=FULL_100 |

Run from `flik-load-test/`:

```bash
cd flik-load-test
mvn gatling:test
```

An interactive picker will prompt you to choose a simulation by number:

| # | Simulation | Description |
|---|---|---|
| 0 | BaselineSimulation | 1,000 users, 500 req/s steady state |
| 1 | BurstSimulation | Spike to 5,000 req/s |
| 2 | CanarySimulation | Progressive rollout 10% → 50% → 100% |
| 3 | ChaosSimulation | Kill 50% of each worker type |
| 4 | DagSimulation | Task dependency chains (TEXT → IMAGE → VIDEO) |
| 5 | FairnessSimulation | One tenant sends 10x traffic |
| 6 | MultiRegionSimulation | Cross-region with simulated latency |

Results are generated as interactive HTML reports in `flik-load-test/target/gatling/`. Actual results data with metrics and analysis is documented in [docs/LOAD-TEST-RESULTS.md](docs/LOAD-TEST-RESULTS.md).

## Observability

### Grafana Dashboard (http://localhost:3000)

Pre-provisioned panels:
- **Queue Depth** — per priority level (P0/P1/P2), real-time
- **Throughput** — tasks submitted/completed per second
- **Latency** — p50, p95, p99 processing duration by task type
- **Failure Rate** — percentage of failed tasks over time
- **Worker Utilization** — active workers vs. capacity
- **Rate Limit Rejections** — per-tenant 429 responses
- **Retry Rate** — retry attempts by type and attempt number
- **Per-Tenant Distribution** — request volume by tenant
- **Estimated Hourly Cost** — current projected worker cost (stretch: cost modeling)
- **Cumulative Cost by Type** — cost breakdown over time (stretch: cost modeling)
- **Cost per Tenant** — per-tenant spend table (stretch: cost modeling)
- **Cross-Region Requests** — inter-region request volume (stretch: multi-region)
- **Inter-Region Latency** — simulated latency between regions (stretch: multi-region)
- **Storage Tier Hits** — HOT/WARM/COLD/MISS distribution (stretch: tiered storage)

### Structured Logging

All services emit JSON-structured logs with MDC context:
```json
{
  "timestamp": "2026-02-17T14:00:00.000Z",
  "level": "INFO",
  "logger": "com.flik.worker.processor.ImageProcessor",
  "message": "Task completed",
  "taskId": "550e8400-...",
  "tenantId": "tenant-abc",
  "taskType": "IMAGE",
  "durationMs": 7832,
  "traceId": "abc123"
}
```

## Configuration

Key environment variables (configured in `docker-compose.yml`):

| Variable | Default | Description |
|---|---|---|
| `RATE_LIMIT_REQUESTS_PER_SEC` | 300 | Per-tenant rate limit |
| `WORKER_CONCURRENCY` | 5 | Concurrent tasks per worker instance |
| `AUTOSCALE_MIN_WORKERS` | 2 | Minimum worker count |
| `AUTOSCALE_MAX_WORKERS` | 10 | Maximum worker count |
| `AUTOSCALE_QUEUE_THRESHOLD` | 100 | Queue depth to trigger scale-up |
| `AUTOSCALE_BUDGET_PER_HOUR` | 50.0 | Max hourly worker cost before blocking scale-up |
| `FLIK_REGION` | us-east | Gateway region identifier (us-east, us-west) |
| `WORKER_VERSION` | v1.0.0 | Worker version for canary tracking |

## Development

### Prerequisites
- Docker & Docker Compose
- Java 21 (for local development only)
- Maven 3.9+ (for local development only)

### Build Locally
```bash
mvn clean package -DskipTests
```

### Run Unit Tests
```bash
mvn test
```

8 unit test files across 3 modules:
- **flik-api-gateway** (4 tests) — `TaskControllerTest`, `DagControllerTest`, `CostControllerTest`, `HealthControllerTest`
- **flik-autoscaler** (2 tests) — `ScalingServiceTest`, `CanaryServiceTest`
- **flik-common** (2 tests) — `QueueConstantsTest`, `CostConstantsTest`

Uses JUnit 5 + Mockito + MockMvc with `@WebMvcTest` for controller testing.

### Run Load / Integration Tests

The 7 Gatling simulations serve as end-to-end integration tests against the full Docker Compose stack. See the [Load Tests](#load-tests) section for commands.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed design decisions, trade-offs, scaling analysis, and the full [Testing & Quality Assurance](docs/ARCHITECTURE.md#testing--quality-assurance) breakdown.
