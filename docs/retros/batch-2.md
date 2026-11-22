# Batch 2 retro: Kafka

## What I built

- Kafka 4.4.0 in Compose, KRaft mode (no ZooKeeper), plus Kafbat UI on :8080.
- Topics created by `kafka-init`: `transactions.raw` (3 partitions), `transactions.dlq` (1).
- Generator with `OUTPUT=kafka` publishes JSON to `transactions.raw`, keyed by `cardId`, with
  `acks=all` and idempotence on.
- `transaction-consumer`: manual offset commits via `commitBatchWithin(100, 5.seconds)`;
  events that fail to decode go to `transactions.dlq` with an `error` header.

## Decisions made

- [ADR 0007](../decisions/0007-fs2-kafka-and-json.md): fs2-kafka and JSON on the wire.
- Skipped: the three experiments (rebalance, null keys, crash before commit) and Avro +
  Schema Registry. Both are in `backlog.md`.

## Key takeaways

- Keying by `cardId` sends every event of one card to the same partition, so they stay in
  order for that card.
- `acks=all` + idempotence: the broker de-duplicates producer retries.
- Committing after processing gives at-least-once: a crash before the commit replays up to
  one uncommitted batch.
- A dead-letter topic keeps one bad event from blocking its partition.
