# ADR 0006: Cats Effect 3 and fs2 as the effect style

Date: 2026-09-17. Status: accepted.

## Decision

The generator, and every service after it, describes its side effects as values using
Cats Effect 3 (`IO`, `Resource`) and streams them with fs2. Plain Scala — a `while` loop that
calls `println` — is rejected.

## The choice

| | Plain Scala | Cats Effect 3 + fs2 |
|---|---|---|
| Generator shape | `Iterator[Transaction]` + loop | `Stream[IO, Transaction]` |
| Rate limiting | `Thread.sleep` | `.metered(10.millis)` |
| Output handle | open, `try/finally`, close | `Resource` |
| Dependencies | none | cats-effect, fs2 |
| Teaches | nothing new | what a side effect is and how to compose one |

## Why

The generator is the wrong place to optimise for the smallest diff. It is the first program in
the project that has effects at all — a clock, a random source, a sink — and each one is a
chance to see the difference between *performing* an effect and *describing* one.

Three things follow the decision rather than motivate it, but they are real:

- Batch 2 uses fs2-kafka, which is the same library family. Choosing plain Scala now means
  rewriting the generator then.
- Seeded randomness (a project convention) is state. `IO` forces that state to be explicit
  instead of a hidden `var`, which is what makes a run reproducible.
- `Either[DomainError, Transaction]` from ADR 0005 has to be threaded through the stream. How
  badly that reads is the evidence ADR 0005 asked us to collect.

## Cost, stated plainly

Two things are being learned at once: the library and the concept underneath it. The
concept comes first — `IO` and side effects are already queued as item 2 in
[the algebra notes](../algebra-and-categories.md), ahead of any generator code. If the library
starts driving the design instead of the domain, that is the signal this ADR was wrong.

`IO` is also a monad, so the `flatMap` from §6 of the algebra notes is the same `flatMap`. The
`Writer` question — logging as a value that accumulates through calls — lands in the same
territory and is queued next to it.

## What this does not decide

Nothing about Spark, which brings its own execution model in Batch 3, and nothing about
whether the Kafka consumer in Batch 2 uses fs2-kafka or plain `kafka-clients`. Those stay open.
