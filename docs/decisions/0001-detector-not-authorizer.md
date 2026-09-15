# 0001 — The system detects, it does not authorize

Date: 2026-09-15
Status: Accepted

## Context

A fraud system can sit in one of two places, and the choice determines every latency and
delivery target downstream.

An **authorizer** sits inline in the payment call. It must answer every transaction inside a
hard deadline; a timeout is itself a decision (fail-open approves fraud, fail-closed declines
good customers); and the correctness property is "exactly one verdict per transaction."
Windowed and historical rules are effectively unusable, because nothing that needs a
watermark can answer in 10 ms.

A **detector** consumes transactions after the fact. It may be late, it may emit nothing, and
its correctness property is "no duplicate alerts."

The target architecture in `CLAUDE.md` — a `fraud.alerts` topic, Spark windows, watermarks,
replay — is a detector. The "fast path under 10 ms" phrasing is authorizer vocabulary. Left
unresolved, the latency target and the watermark design contradict each other.

## Decision

The system is a **detector**. It consumes already-authorized transactions and emits `Alert`
events. It never approves, declines, or delays a payment.

The p99 < 10 ms fast-path number is kept, but reclassified as a **self-imposed SLO**.

## Consequences

- Breaching 10 ms degrades timeliness, never correctness. This is what makes the target safe
  to measure honestly under load in Batch 5 instead of quietly widening it.
- The slow path is free to use event time and watermarks, and to emit an alert an hour after
  the transaction. No caller is blocked.
- Delivery is at-least-once with an idempotent `dedupKey`, not exactly-once. Replay
  correctness becomes a property we can actually demonstrate (Batch 4).
- There is no fail-open/fail-closed policy to design, because there is no verdict.
- Cost: the project never exercises a hard real-time deadline with a timeout policy. That is
  a genuine gap, and it is the right trade — the learning goals are streaming, state, and
  event time, all of which live on the detector side.

## Alternatives rejected

- **Authorizer.** Would make S1–S3 advisory-only and turn watermarks into decoration.
- **Both.** Realistic, roughly doubles the Batch 0 spec and the service count, for no extra
  distributed-systems concept.
