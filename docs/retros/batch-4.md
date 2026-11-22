# Batch 4 retro: Redis

## What I built

- Redis 8.10.3 in Compose with AOF on (`--appendonly yes`) and the `redis-data` volume.
- `redis-jobs` module (redis4cats 2.0.6), run with `sbt redisJobs/run`. It reads
  `transactions.raw` with manual offset commits and sends malformed events to `transactions.dlq`.
- **F1 blocklist:** sets `blocklist:cards` and `blocklist:merchants`, seeded at startup from
  `BLOCKED_CARDS` and `BLOCKED_MERCHANTS` (default `m-999`), checked with two pipelined
  `SISMEMBER`s.
- **F3 velocity:** sorted set `velocity:<cardId>` scored by event time. First a pipeline, then
  a Lua script (`velocity.lua`: `ZADD`, `ZREMRANGEBYSCORE`, `EXPIRE`, `ZCARD`) loaded once with
  `SCRIPT LOAD` and run with `EVALSHA`.
- **Alert dedup:** `EXISTS alert:<dedupKey>`, produce to `fraud.alerts`, then
  `SET alert:<dedupKey> NX EX <ALERT_DEDUP_TTL_SECONDS>` (default 86400).
- Config from env: `VELOCITY_MAX`, `VELOCITY_WINDOW_SECONDS`, `BLOCKED_CARDS`,
  `BLOCKED_MERCHANTS`, `ALERT_DEDUP_TTL_SECONDS`, `REDIS_URI`.

## What broke and why

- The first end-to-end run showed no F1 or F3 alerts because `redis-jobs` was not running. It
  is not in Compose, so it has to be started by hand. A sign it never ran: `blocklist:merchants`
  was missing from Redis.

## Decisions made

- Lua over a pipeline for velocity. A pipeline only saves round trips; a script runs as one
  step, so nothing else can run between the commands.
- The script is loaded once at startup. Redis forgets loaded scripts on restart (AOF does not
  save them), so a Redis restart while `redis-jobs` is running fails with `NOSCRIPT` until
  `redis-jobs` is restarted.
- The dedup key is written after the produce: a crash between the two gives a duplicate
  alert, never a lost one (at-least-once).
- `dedupKey` = `cardId:<rule>:<eventTime ms / 300000>`, a fixed 5-minute clock bucket. A
  burst that crosses a bucket boundary gives two alerts.

## Key takeaways

- Sorted set as a sliding window: score = timestamp, trim everything older than the window,
  count what is left.
- Pipelining vs. Lua: fewer round trips vs. atomicity.
- `SET NX EX` as an idempotency key: "only if missing" plus an expiry so keys clean themselves up.
- AOF logs every write and replays it on startup; RDB is a periodic snapshot. Since Redis 7,
  AOF starts from an RDB snapshot and appends commands after it.
- Kafka keys by `cardId`, so one consumer owns a card, and the check-then-set dedup cannot race
  for the same card.
