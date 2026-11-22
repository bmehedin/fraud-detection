# Batch 3 retro: Spark Structured Streaming

## What I built

- `spark-jobs` module, run in Compose (profile `spark`) with `spark-submit --master local[*]`.
- One streaming job, `StreamingMain`, with two queries reading `transactions.raw`:
  - **S2 impossible travel → `fraud.alerts`.** Watermark of 10 minutes on `eventTime`,
    `groupByKey(cardId)`, `transformWithState` with `ImpossibleTravelProcessor`. JSON built in
    Spark with `to_json(struct(...))`, keyed by `cardId`.
  - **Raw events → Parquet** in `data/transactions/`, partitioned by `date`, triggered every
    minute, with Kafka partition and offset kept.
- RocksDB state store; checkpoints per query on the `spark-work` volume.
- `fraud.alerts` topic (3 partitions) created by `kafka-init`.
- Config from env: `WATERMARK_DELAY`, `MAX_SPEED_KMH`, `CHECKPOINT_DIR`, `ALERTS_TOPIC`,
  `PARQUET_DIR`, `PARQUET_CHECKPOINT_DIR`, `PARQUET_TRIGGER`.

## What broke and why

- The first run printed raw transactions instead of hits: `sbt package` failed with
  `AccessDeniedException` because the running container held the jar, so the old jar ran.
  Fix: stop the Spark container before packaging.
- IntelliJ's sbt shell holds sbt's server lock, so command-line builds need
  `-Dsbt.server.forcestart=true`.
- Switching the sink from console to Kafka meant deleting the checkpoint volume: a checkpoint
  belongs to one query shape.

## Decisions made

- `transformWithState` over `flatMapGroupsWithState`; RocksDB is required by it.
- **Evaluate immediately:** a late event (within the watermark) is compared with its previous
  and next visit right away. We want an alert, not the correct sorted path, so A→C may alert
  before B arrives.
- `MapState[transactionId → Visit]` over `ListState`, because only `MapState` can remove a
  single entry. Visits older than the watermark are pruned, except the newest one.
- Alert JSON built in Spark rather than through `domain` + circe: no new dependencies, at the
  cost of a second copy of the wire format.
- Readable `dedupKey`: `cardId:S2:<detectedAt ms / 300000>`.
- Dropped: windowed rules (S1/S3 were cut on 2026-10-31), the card-profile batch job and the
  experiments. See `backlog.md`.

## Key takeaways

- The watermark is `max(eventTime seen) − delay`, driven by event time, not by arrival time.
- `transformWithState` calls my processor once per key per micro-batch; Spark does the
  shuffle, state storage and checkpointing.
- The processor is serialised from the driver to the executors, so the state handle is
  `@transient` and set in `init`.
- Checkpoint = offsets + state; a restart resumes from it and ignores `startingOffsets`.
- Kafka sink is at-least-once, so a deterministic `dedupKey` is what makes replays harmless.
  The file sink gets exactly-once from `_spark_metadata`.
- Each trigger reads everything since the last batch from Kafka; Kafka, not Spark memory, is
  the buffer.
