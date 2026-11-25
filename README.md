# Fraud Detection

A small system that watches a stream of card payments and raises alerts for suspicious ones.
It does not block payments; it only flags them.

## Technologies

- Scala, with Cats Effect and fs2
- Kafka for moving events between services
- Spark Structured Streaming for rules that need history or time windows
- Redis for fast, per-card state
- Prometheus and Grafana for metrics and dashboards
- Kubernetes, deployed with ArgoCD

## How data flows

A generator creates card transactions, including some planted fraud, and publishes them to Kafka.

Two paths read those transactions:

- The fast path (`redis-jobs`) checks each payment against Redis in milliseconds. It flags cards
  or merchants on a blocklist and cards making too many payments in a few seconds.
- The slow path (`spark-jobs`) looks at events over time. It flags impossible travel, such as one
  card paying in two distant cities too close together. It also stores every transaction as
  Parquet files.

Both paths write their alerts back to Kafka, on the `fraud.alerts` topic. Malformed events go to
`transactions.dlq`. Prometheus collects metrics from every service, and Grafana shows them.
