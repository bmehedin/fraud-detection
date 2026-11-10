# Metrics: what we track and how to read it

Prometheus pulls (`scrapes`) every target's `/metrics` endpoint every 5 s and stores each
number as a time series. Grafana only draws queries against Prometheus; it stores nothing.

## Three kinds of metric

| Type | What it is | How you query it | Example |
|---|---|---|---|
| Counter | Only goes up (resets to 0 on restart) | `rate(x[...])` = per second, `increase(x[5m])` = how many in 5 min | `redis_jobs_events_total` |
| Gauge | Goes up and down; a reading right now | Use as is | `redis_memory_used_bytes`, `kafka_consumergroup_lag` |
| Histogram | Counts observations into buckets (`le` = "less or equal") | `histogram_quantile(0.99, sum by (le) (rate(x_bucket[...])))` | `redis_jobs_check_duration_seconds` |

A raw counter graph is a line that only climbs, which says little. Always `rate()` it.
`$__rate_interval` lets Grafana pick a window that is safe for the scrape interval.

**p99** = 99 % of transactions were faster than this. Averages hide slow outliers; percentiles
show them. Quantiles are estimated inside the bucket, so with few events they are rough.

## Where the numbers come from

| Target | Port | Source |
|---|---|---|
| `transaction-producer` | 9401 | Our code, Prometheus Java client |
| `redis-jobs` | 9400 | Our code, Prometheus Java client, plus JVM metrics |
| `kafka-exporter` | 9308 | Asks Kafka for offsets; computes lag |
| `redis-exporter` | 9121 | Runs `INFO` on Redis |
| `spark-streaming` | 4040 | Spark's built-in `PrometheusServlet` |

An **exporter** is a sidecar that translates a tool's own stats into Prometheus format, for
tools we cannot add code to.

## Overview dashboard

- **Scrape targets up**: 1 = reachable. A 0 means every other panel for that job is blind.
- **Producer events/sec**: the load we put in. Should equal `RATE`.
- **Topic message rate**: `transactions.raw` should match the producer; `transactions.dlq`
  near 0; `fraud.alerts` small (about `FRAUD_PERCENT` of raw).
- **Consumer lag**: messages produced but not yet committed by a consumer group.
  - Flat and small: keeping up. Bumps of up to ~100 are commit batching (100 records / 5 s).
  - Climbing steadily: consumer slower than producer. This is the first sign of a bottleneck.
  - Climbing on one partition only: a hot key or a stuck partition.

## Fast path (`redis-jobs`)

- **Events/sec**: should track producer events/sec. If it is lower *and* lag climbs,
  `redis-jobs` is the bottleneck.
- **Alerts (last 5m)**: F1 and F3 counts. Zero for a long time with fraud injected = broken rule.
- **Check latency**: p50/p95/p99 of running F1 + F3 against Redis. The red line is the 10 ms
  target. p99 above it while p50 is fine = occasional slow calls (GC pause, network).
  All three rising together = Redis or the network is saturated.
- **Consumer lag**: same as overview, just this group.
- **Redis memory**: should level off, because velocity entries and dedup keys have TTLs.
  Steady growth = keys without expiry.
- **Redis hit rate**: share of lookups where the *key* existed, not whether a set contained the
  member. `blocklist:cards` is empty (so it does not exist) and misses on every event, while
  `blocklist:merchants` hits. So a rate near 50 % is normal here. Redis is our state store,
  not a cache, so this is not a health signal the way it is for a cache.
- **Redis ops/sec**: a steady multiple of events/sec (blocklist pipeline, Lua velocity script,
  dedup writes). If it flattens while events/sec should rise, Redis is the limit.
- **JVM**: heap should sawtooth (fill, GC, drop). A rising floor = something is held forever.

## Slow path (Spark)

Spark Structured Streaming works in **micro-batches**: every trigger it reads what is new
from Kafka and processes it as a small batch.

- **Input vs processing rate**: rows/sec arriving vs rows/sec handled. Processing below input
  for minutes = Spark cannot keep up; batches get bigger and slower.
- **Batch latency**: duration of the last micro-batch. If it grows past the trigger interval,
  batches queue up.
- **S2 state rows / size**: per-card state for impossible travel. Should plateau near the card
  count (1,000). Growth past that = state is not being evicted.
- **S2 watermark delay**: how far the watermark trails real time. Events older than the
  watermark are dropped as too late. Stable = fine; growing = event time is stalling.
- **Driver JVM**: in `local[*]` mode the driver also does the executor work, so this is all
  of Spark's memory.

## Finding a bottleneck (Batch 5 "done when")

1. Raise the producer `RATE` step by step.
2. Watch **lag**. The first group whose lag climbs is behind.
3. Open that path's dashboard. Is the work rate flat while load rises? Is latency rising?
4. Check what it depends on: Redis ops/sec and latency for the fast path, input vs processing
   rate and batch latency for Spark, JVM heap and GC for both.
