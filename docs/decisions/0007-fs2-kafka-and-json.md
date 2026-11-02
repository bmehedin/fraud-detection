# ADR 0007: fs2-kafka and JSON on the wire

Date: 2026-10-31. Status: accepted.

## Decision

Kafka producers and consumers use fs2-kafka. Events are encoded as JSON with circe, following
[the event schema](../event-schema.md).

## Options

| | Chosen | Rejected |
|---|---|---|
| Client | fs2-kafka: `Stream[IO, ...]`, fits ADR 0006 | kafka-clients: raw Java API, poll loops by hand |
| Format | JSON (circe): readable in the UI, no extra infra | Avro + Schema Registry: typed contract, one more container |

## Why

fs2-kafka keeps the generator and consumer in the same style as Batch 1. It is a thin wrapper
over kafka-clients, so producer and consumer settings (`acks`, idempotence, manual commits)
stay visible.

JSON lets you read every event in Kafbat UI while learning. Avro is in the backlog.
