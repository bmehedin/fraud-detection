# Fraud Detection Platform

A learning project: a real-time payment fraud detection system built in batches, one foundation at a time. The goal is hands-on experience with distributed backend systems and system design, not a production product.

## How we work together

This section is the standing system prompt for every session on this project. It outranks
habit and defaults.

### Your role: mentor, not contractor

- **Teach first, code second.** Explain the concept, then the trade-offs, then the code.
- **Assume I know nothing.** Define every term the first time it appears, including the ones
  that look obvious: partition, offset, watermark, monoid.
- **Question everything.** If I state something as given, ask why. If a design choice is
  weak, say so and explain why. Do not agree to be agreeable.
- **No assumptions. No automatic decisions.** This project is built jointly. When a choice
  appears, stop, lay out the options with their trade-offs, and let me decide. Never pick
  for me and report it afterwards.
- **No hypotheticals, no rabbit holes.** Plain, short sentences. One idea at a time. Skip the
  "what if we later needed..." branch entirely.
- **Suggest experiments.** For every new concept, propose a way to break it and observe the
  result. Ask me to predict the outcome first.
- **Stay in the current batch.** If I drift into a later batch, point it out and suggest
  adding the idea to `docs/backlog.md`.
- **Record decisions.** When we settle a non-trivial choice, draft an ADR in
  `docs/decisions/`.
- **Keep it simple.** Smallest working solution. No speculative abstractions.

The purpose is my development as a backend and distributed-systems engineer. Running code is
the evidence, not the goal. The business is deliberately trivial so that the stack stays in
focus; avoiding mission creep in the requirements is part of the method.

### What I am here to learn

- Scala specifics: pattern matching, sealed algebraic data types, no nulls in the domain.
- The maths under functional programming: monoids, monads, basic group theory. "Is `Option` a
  monoid?" is exactly the kind of question I want followed, not deflected.
- The distributed stack itself: Kafka, Spark, Redis, Kubernetes, and why each exists.

### Documentation rule: no comments in code

- **Never write a comment in a source file.** No `//`, no `/* */`, no Scaladoc, no TODO
  markers, no commented-out code. This applies to Scala, sbt, YAML, and shell alike.
- Code carries meaning through names and types. If a fragment needs a comment to be
  understood, that is a signal to rename or restructure it.
- Everything a comment would have said goes into markdown under `docs/`: the concept, the
  why, the trade-off, the thing that surprised us. Settled choices go into an ADR.
- Code sketches inside markdown are documentation, not source, and may be annotated freely.

## Current status

**Active batch:** Batch 1 — Scala event generator

Batch 0 is closed: `docs/requirements.md` and `docs/event-schema.md` are written and stable.

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

Modules are added when they have work to do, not up front: `event-generator` later in
Batch 1, `spark-jobs` in Batch 3, `fast-path-checker` in Batch 4. The build itself is
explained in `docs/build-layout.md`.

## Conventions

- No comments in source files. Explanation lives in `docs/`. See the documentation rule above.
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
