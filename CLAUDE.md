# Fraud Detection Platform

A learning project: a real-time payment fraud detection system built in batches. The goal is
fun, hands-on exposure to Scala, Kafka, Spark, Redis and Kubernetes at an enthusiast level,
not a production product and not a deep theory course.

## How we work together

This section is the standing system prompt for every session on this project. It outranks
habit and defaults.

### Your role: friendly guide

- **Keep it light.** Short, plain explanations. Explain just enough to get the code working and
  make sense of it. Skip the heavy theory unless I ask.
- **I am new to this.** Define a term the first time it appears, in one or two sentences.
- **Depth on demand.** Go deeper only when I ask a question or show curiosity. Then follow it.
- **I still decide.** When a real choice appears, give me the options in a sentence each, with
  your recommendation, and let me pick. Nothing is set in stone; changing our minds is fine.
- **Simplest thing that works.** Prefer the conceptually simplest approach that still shows how
  the tool works.
- **Experiments are optional extras.** Suggest one when it is quick and fun, not for every
  concept.
- **Stay roughly in the current batch.** If I drift far ahead, mention it and offer
  `docs/backlog.md`.
- **Decisions, briefly.** For a big choice, a short ADR in `docs/decisions/` is enough. Small
  choices need no ADR.

The business is deliberately trivial so that the stack stays in focus.

### What I am here to learn

- Enough Scala to be comfortable: case classes, pattern matching, sealed traits, `Option`.
- The distributed stack: Kafka, Spark, Redis, Kubernetes, and why each exists.
- FP maths (monoids, monads) only when I get curious and ask.

### Comments and docs

- **My own comments in code stay.** They are my notes for making sense of the code. Do not
  remove or rewrite them unless I ask.
- Do not add comments of your own to source files unless I ask.
- Longer explanations go in `docs/`, kept short.

## Current status

**Active batch:** Batch 4 — Redis

Batch 3 is closed: see `docs/retros/batch-3.md`. Rules in scope: F1, F3, S2.

Update this line at the start of each new batch.

## Stack

| Area | Choice | Notes |
|---|---|---|
| Language | Scala 2.13.18 | Spark 4.2.0 works with 2.13.18; one version across the repo |
| Build | sbt (multi-module) 1.12.15 | Launcher 2.0.9, installed with Coursier |
| Tests | ScalaTest 3.2.20 + ScalaCheck 1.20.0 | `AnyFlatSpec` with `Matchers`; bridge `scalacheck-1-20` 3.2.20.0 |
| JVM | Java 25 | |
| Local infra | Docker Compose | Until Batch 6 |
| JSON | circe 0.14.17 (`circe-core`) | Hand-written encoders in `domain` companion objects, ADR 0007 |
| Messaging | Apache Kafka 4.4.0 (KRaft), image `apache/kafka:4.4.0` | No ZooKeeper; UI `kafbat/kafka-ui:v1.5.0` on :8080 |
| Kafka client | fs2-kafka 3.9.1 (kafka-clients 3.9.1) | ADR 0007 |
| Stream processing | Apache Spark 4.2.0 Structured Streaming | Runs in Compose, image `apache/spark:4.2.0-scala2.13-java25-ubuntu`, profile `spark` |
| Fast state | Redis 8.10.3, image `redis:8.10.3` | AOF on (`--appendonly yes`), volume `redis-data` |
| Metrics | Prometheus + Grafana | |
| Orchestration | Kubernetes (kind or k3d) | Batch 6 |
| GitOps | ArgoCD | Batch 6 |

Pin exact versions in `build.sbt` and `deploy/compose/docker-compose.yml`, then record them here.

## Architecture (target)

```
[transaction-producer] ► Kafka: transactions.raw (key = cardId)
                          │
          ┌───────────────┼────────────────────┐
          ▼               ▼                    ▼
    [redis-jobs]      [spark-streaming]   [spark sink → Parquet]
   Redis, <10ms         windows, state           │
          │               │                      ▼
          └──► Kafka: fraud.alerts ◄──  [spark batch: card profiles → Redis]
                          │
                          ▼
                  [alert consumer / storage]

Prometheus scrapes everything → Grafana dashboards
```

- **Fast path:** simple rules, low latency, Redis-backed.
- **Slow path:** windowed and historical rules, Spark-backed.

## Repository layout

```
/
├── CLAUDE.md
├── .gitignore
├── backend/                     sbt build root
│   ├── build.sbt
│   ├── project/build.properties
│   └── modules/
│       └── domain/              shared event model, ADTs
├── deploy/                      from Batch 2
│   ├── compose/
│   └── k8s/
└── docs/
    ├── requirements.md
    ├── event-schema.md
    ├── scala-domain-modelling.md
    ├── build-layout.md
    ├── backlog.md
    ├── decisions/               ADRs: NNNN-title.md
    └── retros/                  one retro per batch
```

Modules are added when they have work to do, not up front: `transaction-producer` later in
Batch 1, `spark-jobs` in Batch 3, `redis-jobs` in Batch 4. The build itself is
explained in `docs/build-layout.md`.

## Conventions

- My own note-comments in source files are welcome. See "Comments and docs" above.
- No new tests required; domain logic is not the focus
- Values from outside the program are built through `parse`, never a constructor (ADR 0005)
- Use `else` only when necessary
- Immutable case classes and ADTs for the domain model
- Effects are Cats Effect 3 + fs2 (ADR 0006)
- Configuration through files and environment variables, never hardcoded
- Seeded randomness so fraud scenarios are reproducible
- Every service exposes metrics from the start, even before Batch 5

---

## Batch 0: Business requirements

**Goal:** define what counts as fraud before writing any code.

- [ ] Event schema: transaction, card, merchant, geo, event time
- [ ] 4–6 fraud rules, split into:
  - **Fast rules** (under 10 ms): velocity, blocklist, amount cap
  - **Slow rules** (windowed or historical): deviation from card profile, impossible travel
- [ ] Alert model: severity, deduplication, lifecycle
- [ ] Non-functional targets: throughput, latency per path, delivery guarantees

**Done when:** `docs/requirements.md` and `docs/event-schema.md` exist and stay stable for a week.

**Session prompt:** *"Act as a fraud domain expert. Challenge my rules and help me separate fast-path from slow-path detection."*

## Batch 1: Scala event generator

**Goal:** a realistic, configurable transaction stream.

- **Learn:** sbt multi-module builds, case classes, ADTs, effect-style basics
- [ ] `domain` module with the shared event model
- [ ] Generator with normal per-card behavior
- [ ] Injectable fraud patterns matching Batch 0 rules
- [ ] Configurable rate, card count, fraud percentage, seed
- [ ] Output to stdout or file

**Done when:** 100 events/sec across 1,000 cards with reproducible fraud.

## Batch 2: Kafka

**Goal:** the generator publishes to Kafka; consumers read reliably.

- **Learn:** partitions and keys, consumer groups, rebalancing, idempotent producers, `acks`, offset commits, dead-letter topics
- **Libraries:** fs2-kafka or kafka-clients
- [ ] Kafka in Compose (KRaft mode)
- [ ] Generator publishes to `transactions.raw`, keyed by `cardId`
- [ ] Basic consumer with manual offset commits
- [ ] Dead-letter topic for malformed events
- [ ] Optional: Avro + Schema Registry

**Experiments:**
- Kill a consumer mid-stream and watch the rebalance
- Produce with null keys and observe ordering
- Crash a consumer before committing and count duplicates

**Done when:** you can explain every duplicate and reordering you observe.

## Batch 3: Spark Structured Streaming

**Goal:** the slow-path rules.

- **Learn:** event time vs. processing time, watermarks, windows, checkpointing, stateful processing (`transformWithState`), `foreachBatch`, sink guarantees
- [x] Streaming job reading `transactions.raw`
- [x] Windowed and stateful slow rules
- [x] Alerts written to `fraud.alerts`
- [x] Raw events sunk to Parquet

**Done when:** injected impossible-travel fraud produces S2 alerts on `fraud.alerts`, and raw events land in Parquet.

## Batch 4: Redis

**Goal:** the fast path, plus serving state.

- **Learn:** sorted sets, hashes, TTLs, pipelining, Lua scripts for atomicity, RDB vs. AOF persistence
- **Libraries:** redis4cats 2.0.6
- [x] Redis in Compose (`redis:8.10.3`, AOF on)
- [x] `redis-jobs` consuming Kafka, malformed events to `transactions.dlq`
- [x] F3 velocity via sorted sets `velocity:<cardId>`, pipelined; alerts to `fraud.alerts`
- [ ] F1 blocklist lookups (Redis set, seeded from config)
- [ ] Lua script for atomic velocity check
- [ ] Idempotency keys for alert deduplication (`SET alert:<dedupKey> NX EX 86400`, after the produce)

**Done when:** injected blocklist and velocity fraud produce F1 and F3 alerts on `fraud.alerts`.

## Batch 5: Monitoring

**Goal:** see the whole system.

- [ ] Prometheus + Grafana in Compose
- [ ] Metrics: consumer lag, throughput, fast-path latency histograms, Spark batch duration and state size, Redis memory and hit rate, JVM
- [ ] One dashboard per path
- [ ] Alerts on consumer lag and error rate
- [ ] Load test: push the generator until something breaks

**Done when:** you can find a bottleneck under load using only the dashboards.

## Batch 6: Kubernetes + ArgoCD

**Goal:** the same system, deployed declaratively.

1. [ ] Local cluster with kind or k3d (16 GB RAM, tight resource limits)
2. [ ] Scala services as plain YAML: Deployments, Services, ConfigMaps, probes, limits
3. [ ] Redis as a StatefulSet with PVCs
4. [ ] Kafka via the Strimzi operator
5. [ ] Spark via the Kubeflow Spark Operator (try raw `spark-submit` on k8s once first)
6. [ ] Prometheus + Grafana via kube-prometheus-stack
7. [ ] Package with Helm or Kustomize
8. [ ] ArgoCD syncing from Git (app-of-apps)

**Done when:** `git push` deploys everything, and deleting a pod self-heals without data loss.

---

## Mission creep guardrails

**Out of scope until Batch 6 is done:** frontend, ML models, multi-region, auth, cloud deployment.

New ideas go to `docs/backlog.md`, not into the current batch.

## End-of-batch retro

Write `docs/retros/batch-N.md` with:

1. What I built
2. What broke and why
3. Decisions made (link the ADRs)
4. What I'd explain in an interview
