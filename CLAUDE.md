# Fraud Detection Platform

A learning project: a real-time payment fraud detection system built in batches, one foundation at a time. The goal is hands-on experience with distributed backend systems and system design, not a production product.

## How to work with me (instructions for Claude)

- **Teach first, code second.** Explain the concept and the trade-offs before proposing code. I want to understand, not just ship.
- **Stay in the current batch.** If I drift into a later batch, point it out and suggest adding the idea to `docs/backlog.md`.
- **Challenge my decisions.** If a design choice is weak, say so and explain why.
- **Suggest experiments.** For every new concept, propose a way to break it and observe the result. Ask me to predict the outcome first.
- **Record decisions.** When we settle a non-trivial choice, draft a short ADR in `docs/decisions/`.
- **Keep it simple.** Prefer the smallest working solution. No speculative abstractions.
- the business requirements shall be very simple and basic, to avoid any mission creep. the business is not the focus. learning the stack is the focus.
- i am very interested in scala specifics, pattern matching in jdk 25 and sealed algebraic data types. we shall build and model the domain cleanly around this. no nulls in the domain.
- code must be organised cleanly in modules or the idiomatic scala way
- i would like to learn the intuition behind functional programming and the intersection with maths. basic group theory, monads and monoids. is option a monoid? keep in mind these are the questions, intuitions and concepts that spark my interest and i want to learn about
- keep the language plain and simple, formulate brief sentences. assume i know nothing and want to learn. do not go into hypothetical scenarios and rabbit holes.

## Current status

**Active batch:** Batch 0 — Business requirements

Update this line at the start of each new batch.

## Stack

| Area | Choice | Notes |
|---|---|---|
| Language | Scala 2.13.18 | Spark 4.2.0 works with 2.13.18; one version across the repo |
| Build | sbt (multi-module) 1.12.15 |
| JVM | Java 25 | |
| Local infra | Docker Compose | Until Batch 6 |
| Messaging | Apache Kafka 4.x (KRaft) | No ZooKeeper |
| Stream processing | Apache Spark 4.2.0 Structured Streaming | |
| Fast state | Redis 8 | |
| Metrics | Prometheus + Grafana | |
| Orchestration | Kubernetes (kind or k3d) | Batch 6 |
| GitOps | ArgoCD | Batch 6 |

Pin exact versions in `build.sbt` and `deploy/compose/docker-compose.yml`, then record them here.

## Architecture (target)

```
[event-generator] ──► Kafka: transactions.raw (key = cardId)
                          │
          ┌───────────────┼────────────────────┐
          ▼               ▼                    ▼
 [fast-path-checker]  [spark-streaming]   [spark sink → Parquet]
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
├── build.sbt
├── modules/
│   ├── domain/             # shared event model, ADTs
│   ├── event-generator/
│   ├── fast-path-checker/
│   └── spark-jobs/
├── deploy/
│   ├── compose/
│   └── k8s/
└── docs/
    ├── requirements.md
    ├── event-schema.md
    ├── backlog.md
    ├── decisions/          # ADRs: NNNN-title.md
    └── retros/             # one retro per batch
```

## Conventions

- Immutable case classes and ADTs for the domain model
- Pick one effect style (Cats Effect or plain Scala) in Batch 1 and record it in an ADR
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
- [ ] Streaming job reading `transactions.raw`
- [ ] Windowed and stateful slow rules
- [ ] Alerts written to `fraud.alerts`
- [ ] Raw events sunk to Parquet
- [ ] Batch job computing card profiles

**Experiments:**
- Send events 10 minutes late and verify watermark behavior
- Kill the job mid-stream and restart from checkpoint

**Done when:** late events behave as the watermark predicts, and restarts lose no state.

## Batch 4: Redis

**Goal:** the fast path, plus serving state.

- **Learn:** sorted sets, hashes, TTLs, pipelining, Lua scripts for atomicity, RDB vs. AOF persistence
- **Libraries:** redis4cats or Lettuce
- [ ] Redis in Compose
- [ ] `fast-path-checker` consuming Kafka and evaluating fast rules
- [ ] Velocity via sorted sets, blocklist lookups
- [ ] Card profiles written from Spark into Redis
- [ ] Idempotency keys for alert deduplication

**Done when:** fast-path p99 is under 10 ms, and replaying a topic creates no duplicate alerts.

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
