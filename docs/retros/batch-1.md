# Batch 1 retro: Scala event generator

## What I built

- `domain` module: transaction model with sealed traits, case classes and `parse` validation.
- `Rand`: a hand-written state monad over a seed (`nextLong`, `between`, `oneOf`, `unit`,
  `listOfN`).
- `TransactionGen`: 1,000 cards, each with a random home city. Normal transactions happen at
  home; fraud patterns are injected with a labelled `Fraud` ADT:
  - `Blocklist` (F1): a transaction at merchant `m-999`.
  - `Velocity` (F3): 6 transactions on one card back to back.
  - `ImpossibleTravel` (S2): one transaction at home, the next in another city.
- An fs2 stream paced with `.metered`, stamped with wall-clock event time, written to stdout
  or a file.
- Config from env vars: `RATE`, `CARDS`, `FRAUD_PERCENT`, `SEED`, `OUTPUT`.

Run: `sbt eventGenerator/run`, or e.g. `FRAUD_PERCENT=10 OUTPUT=out.txt sbt eventGenerator/run`.

## What broke and why

- Passing the same seed every time gave the same number forever: the returned seed has to
  be threaded into the next call.
- Without `n >>> 32`, `between` cycled through the same small set of values: the low bits of
  this generator repeat in short cycles.
- `sbt run` without `fork := true` runs inside sbt's JVM, so Ctrl-C kills sbt itself.

## Decisions made

- [ADR 0005](../decisions/0005-smart-constructors.md): `parse` for outside values.
- [ADR 0006](../decisions/0006-effect-style-cats-effect.md): Cats Effect + fs2.
- Rules cut to F1, F3, S2 (see `backlog.md`).
- Event time is wall clock, so the card/fraud sequence is reproducible but timestamps are not.

## Key takeaways

- A state monad: a function `Seed => (A, Seed)` with `map`/`flatMap`, which makes `for`
  comprehensions thread state automatically.
- Why normal traffic must look normal: fraud is only detectable against a baseline.
- `Stream.unfold` plus `metered`: a pure infinite sequence, paced by an effectful runtime.
