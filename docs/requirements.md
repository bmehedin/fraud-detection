# Fraud Detection — Business Requirements (Batch 0)

Status: draft, 2026-09-15. Stable-by target: 2026-09-22.

## 1. What this system is

A **detector**, not an authorizer. It consumes a stream of already-authorized card
transactions and emits `Alert` events. It never approves, declines, or delays a payment.

Consequences, which the rest of this document assumes:

- Missing an alert is a miss, not an outage. There is no verdict-per-transaction contract.
- The <10 ms fast-path target is a **self-imposed SLO**, not a correctness requirement.
  Breaching it degrades timeliness, never correctness.
- Because nothing waits on us, the slow path is free to use event time and watermarks, and
  to emit an alert minutes after the transaction happened.

See [ADR 0001](decisions/0001-detector-not-authorizer.md).

## 2. Actors and entities

| Entity | Identity | Notes |
|---|---|---|
| Card | `CardId` | The partition key for everything. All ordering guarantees are per-card. |
| Merchant | `MerchantId` | Has one `MerchantCategory`. |
| Transaction | `TransactionId` | The only input event. See `event-schema.md`. |
| Card profile | `CardId` | Derived, not ingested. Computed by a Spark batch job from history. |
| Alert | `DedupKey` | The only output event. Its identity is the dedup key — see §4. |

There are no users, accounts, issuers, or acquirers. A card is the top-level subject.
Deliberately thin: the business is not the point.

## 3. Fraud rules

Six rules, three per path. Every rule is a pure function from (event, available state) to
`Option[Alert]` — no rule performs I/O of its own.

### Fast path (Redis-backed, target p99 < 10 ms)

A rule belongs on the fast path when it needs **O(1) state keyed by the card**, and when
processing-time ordering is good enough.

| ID | Rule | Trigger | Severity | State |
|---|---|---|---|---|
| F1 | Blocklist | `cardId` or `merchantId` is on a denylist | High | Redis set |
| F2 | Amount cap | `amount > 10_000.00 EUR` | Medium | none (stateless) |
| F3 | Velocity | more than 5 transactions for one card within 60 s | High | Redis sorted set, score = event time |

F2 is stateless: a pure function of the event alone. It exists to prove that the cheapest
possible rule still flows through the whole pipeline.

### Slow path (Spark Structured Streaming, event time)

A rule belongs on the slow path when it needs **history, a window, or event-time ordering**.

| ID | Rule | Trigger | Severity | State |
|---|---|---|---|---|
| S1 | Amount deviation | `amount > profile.meanAmount + 3 * profile.stdDevAmount`, and `profile.txnCount >= 30` | Medium | batch profile |
| S2 | Impossible travel | implied speed between consecutive event-time-ordered transactions for a card exceeds 900 km/h. Only applies when **both** transactions have a `Physical` origin. | High | streaming state per card: last (origin, eventTime) |
| S3 | Unusual category spend | `merchantCategory` not in `profile.knownCategories` **and** `amount > profile.p95Amount` | Medium | batch profile |

**Why S2 is not a fast rule.** It only needs the card's previous location and timestamp —
O(1) state, cheap enough for Redis. It is on the slow path because the fast path sees events
in *arrival* order, and a delayed event would produce a fictitious speed (the card appears to
jump backwards). Spark's watermark lets us order by *event* time before comparing. This is
the clearest illustration in the project of processing time versus event time, and it is
worth being able to explain out loud.

**Cold start.** S1 and S3 emit nothing when no profile exists or `txnCount < 30`. Silence is
the correct output for an unknown card; a rule that fires on ignorance is a bad rule. S2
follows the same principle for a different reason: if either transaction was online there is
no physical location to check, so it returns nothing rather than inventing a distance
([ADR 0003](decisions/0003-origin-sum-type.md)).

**Deliberate overlap.** F3 (velocity) is also computed on the slow path over a 1-hour sliding
window. The fast path is an approximation over processing time; the slow path is
authoritative over event time. Comparing the two under load is a Batch 5 experiment, not a
seventh rule.

## 4. Alert model

```
Alert
  dedupKey       DedupKey       -- the identity; see below
  cardId         CardId
  triggeredBy    TransactionId  -- the transaction that set the rule off
  detectedAt     EventTime      -- event time of that transaction
  evidence       Evidence       -- sealed ADT, one case per rule

  rule           derived from evidence
  severity       derived from evidence
```

Five fields. `Evidence` carries the numbers that justify the alert — the observed speed for
S2, the count and window for F3, the threshold and the amount for S1 — so every rule is
forced to explain itself, and matching over it is exhaustive.

**`rule` and `severity` are derived, not stored.** An earlier draft had both as free fields
beside `evidence`, which allowed `Alert(rule = F1, evidence = Velocity(...))` — a blocklist
alert holding velocity evidence. The evidence already determines which rule fired, and each
rule has one fixed severity, so both are computed from it. Same reasoning as `Origin` in
[ADR 0003](decisions/0003-origin-sum-type.md): if two fields can disagree, make it one.

Severity is fixed per rule (F1 High, F2 Medium, F3 High, S1 Medium, S2 High, S3 Medium)
rather than scaled by magnitude. Scaling would mean inventing thresholds we have no basis
for.

**There is no `alertId` and no `emittedAt`.** A random id and a wall-clock timestamp would
both make an alert non-reproducible: replaying the same input would yield alerts that are not
equal by content, which is exactly the property §5 says to be strict about. `dedupKey` is the
identity, and it is deterministic. How late an alert was is a log line, not domain data.

### Deduplication

```
dedupKey = hash(cardId, ruleId, floor(detectedAt / dedupWindow))
```

with `dedupWindow = 5 minutes`. One card triggering F3 forty times in five minutes yields one
alert. Because the key is derived only from event data — never from wall clock, offsets, or a
random UUID — **replaying the source topic reproduces the same keys and creates no
duplicates.** This is the property Batch 4 has to demonstrate.

### Lifecycle

An `Alert` is immutable and has exactly one state: emitted. There is no acknowledge/resolve
workflow, because nothing in this system consumes one. Adding a lifecycle would be modelling
a support process we are not building. If an operator workflow is ever wanted, it goes in
`backlog.md`.

## 5. Non-functional targets

Kept deliberately loose. This is a learning project; tuning is not the goal.

- **Volume:** 100 transactions per second across 1,000 cards. Enough to see behaviour, small
  enough to run on a laptop.
- **Fast path:** answers in milliseconds. It only does simple lookups, so it should.
- **Slow path:** may take minutes. It waits for late data on purpose.
- **Nothing is lost.** A malformed transaction goes to a dead-letter topic, never silently
  dropped.
- **No duplicate alerts.** Replaying the input must not produce the same alert twice. This is
  what `dedupKey` (§4) is for, and it is the one guarantee worth being strict about.
- **Order is per card only.** Transactions for one card stay in order. Across different
  cards, no order is promised and none is needed.

## 6. Open questions

- **Single currency.** All amounts are EUR, stored as minor units in a `Long`. Multi-currency
  would need an FX table, which adds business complexity and teaches nothing about the stack.
  Revisit only if a rule genuinely needs it — none currently does.
- **Blocklist source.** F1's denylist is seeded from config in Batch 4. Whether slow-path
  alerts feed back into it is a feedback loop that deserves its own decision, and is out of
  scope for Batch 0.

## 7. Algebra notes

Kept here because the domain model should make these visible rather than hide them.

- `Severity` under `max` is a **commutative idempotent monoid** with identity `Low`. That
  makes "the severity of a group of alerts" well defined, order-independent, and safe to fold
  in any order — which is exactly what a distributed system needs. Merging alerts is a fold.
- Alert deduplication is idempotent by construction: `dedup(dedup(xs)) == dedup(xs)`. That
  idempotence is what makes replay safe, and it is the same property as `max` above.
- Open question to answer during Batch 1: is `Option[A]` a monoid? Under what conditions?
  (It depends entirely on what you are allowed to assume about `A`.)

## 8. Glossary

### Kinds of domain object

| Kind | Defined by | Mutable | Ours |
|---|---|---|---|
| Entity | identity that survives change | conceptually | Card, Merchant |
| Event | an immutable fact at a point in time | never | Transaction, Alert |
| Derived state | a computation over history | recomputed | CardProfile |
| Value object | its contents alone; no identity | never | GeoPoint, Origin, Severity, all ids |

`Card` and `Merchant` are never materialized as case classes. A card exists as a `CardId`
plus its derived profile; a merchant's category is denormalized onto every transaction so
that **no rule needs a join to evaluate**. Trade-off: a merchant that changes category leaves
old transactions carrying the old one — which for fraud detection is arguably more correct,
since we want what was true at the time.

### Sum and product types

The "algebraic" in ADT is literal — types compose by addition and multiplication over how
many values they can hold.

- **Product (AND):** `GeoPoint` is a lat AND a lon AND a country →
  `|Double| × |Double| × |CountryCode|`.
- **Sum (OR):** `Severity` is `Low` OR `Medium` OR `High` → `1 + 1 + 1 = 3`. `Option[A]` is
  `1 + |A|`.

`Evidence` is a six-way sum whose cases are products. The payoff is not aesthetic: because
the trait is **sealed**, the compiler knows every case, so a `match` that misses one fails to
compile. Add a seventh `MerchantCategory` and every rule that switches on category breaks
until handled. Same reason there are no nulls: absence is `Option`, alternatives are sums,
and the compiler checks both.

