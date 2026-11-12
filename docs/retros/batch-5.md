# Batch 5 retro: Monitoring

## What I built

- Prometheus 3.16.0 (:9090) and Grafana 13.3.0 (:3000) in Compose, data source provisioned.
- Exporters: `kafka-exporter` v1.11.0 for consumer lag, `redis_exporter` v1.97.0 for Redis.
  Spark through its built-in `PrometheusServlet` on :4040.
- Prometheus Java client 1.10.0 in our code: `redis-jobs` on :9400 (events by result, alerts by
  rule, check latency histogram, JVM), `transaction-producer` on :9401 (events acknowledged).
- Three provisioned dashboards: Overview, Fast path (`redis-jobs`), Slow path (Spark). The JSON
  lives in `deploy/compose/grafana/provisioning/dashboards/json/`.
- Consumer lag per group shown as **seconds behind**: lag divided by the group's consume rate.
  Lag per partition stays in messages.
- Alert rules in `deploy/compose/prometheus/alerts.yml`, all `for: 1m`:
  - `TargetDown`: `up == 0`.
  - `ConsumerLagHigh`: lag per group > 1000 messages.
  - `RedisJobsErrorRate`: more than 5 % of `redis-jobs` events go to the DLQ.

## What broke and why

- `kafka-exporter` crashed at startup with `connection refused` on `kafka:19092` and stayed
  down: it exits on a failed first connect and had no restart policy. `TargetDown` caught it.
  Fixed with `restart: on-failure`.
- `TargetDown` sat in *pending* longer than its `for: 1m`. Prometheus evaluates rules every
  `evaluation_interval`, default 1 m, so pending to firing takes up to 2 minutes.
- The rule panel on the Prometheus Alerts page shows the raw `{{ $labels.job }}` template. The
  filled-in text is on each alert instance, not on the rule.
- A dashboard made by hand in the Grafana UI duplicated the provisioned one. Deleted it;
  dashboards come only from files.

## Load test

At `RATE=10000`, `FRAUD_PERCENT=1`:

| | Measured |
|---|---|
| Producer | ~1,510/s |
| `redis-jobs` consumed | ~126/s |
| `redis-jobs` lag | 714,133 messages, ~95 min behind |
| Check latency p99 | 24 ms (target 10 ms) |
| Spark `s2` / `parquet` | keeping up at ~1,590/s in; batch latency ~20 s / ~18 s |

- **Bottleneck: `redis-jobs` handles one record at a time.** `.evalMap` in `Main.scala` waits
  for each event's Redis round trips before taking the next, across all 3 partitions, so
  throughput is about 1 / time per event. Redis runs in Docker and `redis-jobs` on the host, so
  every round trip crosses `host.docker.internal`.
- **The generator caps near 1,500/s.** It emits one event per tick with `metered`, which cannot
  keep a 100 µs tick. Above that, the test measures the generator, not the system.
- **Fix:** process each partition in its own stream (fs2-kafka `partitionedRecords` +
  `parJoinUnbounded`). Ordering per card is kept, because a card always lands on the same
  partition. Ceiling: 3x with 3 partitions; more needs more partitions.

Second run, after the fix, at `RATE=1500` and a higher `FRAUD_PERCENT`:

| | Measured |
|---|---|
| Producer | ~1,180/s (set to 1,500) |
| `redis-jobs` consumed | ~242/s (was ~126/s) |
| `redis-jobs` lag | 143,456 messages, ~589 s behind, growing ~960/s; `ConsumerLagHigh` firing |
| Lag per partition | 50k / 44k / 49k: even, no hot key |
| Check latency p50 / p99 | 8.3 ms / 37 ms |
| Redis ops/s | ~1,860 |
| Spark `s2` | 1,141/s in, 1,267/s processed: keeping up |

- About 2x, not 3x. Time per event stayed at ~8 ms (p50); only more events run at once. The
  three streams share one Redis connection, so the tail grew (p99 24 → 37 ms).
- **Redis itself is not the limit.** ~1,860 ops/s is far below what Redis handles. The cost is
  the round trip: `redis-jobs` runs on the Windows host and reaches Redis in Docker Desktop's
  VM through `host.docker.internal`. Not proven yet; the Batch 6 rerun tests it.

## Carried into Batch 6

Rerun at `RATE=1500`, `FRAUD_PERCENT=10` with `redis-jobs` and Redis in the same cluster
(pod to pod, no host-to-VM hop). Compare `redis-jobs` events/sec, check latency p50/p99 and
lag in seconds against the table above. If p50 drops well below 8 ms and lag stops growing,
the round trip was the bottleneck.

## Decisions made

- Prometheus alert rules only, no Alertmanager. Alerts are visible on :9090 and in Grafana,
  not sent anywhere.
- Lag per group in seconds, because a message count means nothing without the rate. Empty when
  a group stops consuming (rate 0), so lag per partition stays in messages.
- Dashboards and data sources are provisioned from files, never edited in the UI.

## What I'd explain in an interview

See "What I'd explain in an interview" in `docs/metrics.md`. Plus:

- Finding a bottleneck: raise the load, watch which group's lag climbs first, then check that
  path's work rate. If it stays flat while the load rises, that path is the limit.
- A consumer's throughput is capped by 1 / latency per event when it processes one at a time.
  Concurrency per partition is how you scale it without breaking per-key ordering.
- Make sure the load generator is not the bottleneck before blaming the system.
