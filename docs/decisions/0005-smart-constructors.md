# ADR 0005: Smart constructors on the value types, fail-fast `Either`

Date: 2026-09-15. Status: accepted. Supersedes [ADR 0004](0004-validation-at-the-boundary.md).

## Decision

Every value type that comes from outside the program hides its constructor and exposes
`parse`, returning `Either[DomainError, A]`. `DomainError` is a sealed trait.

```scala
final case class CardId private (value: String) extends AnyVal

object CardId {
  def parse(raw: String): Either[DomainError, CardId] =
    if (raw.trim.isEmpty) Left(DomainError.EmptyCardId)
    else Right(new CardId(raw))
}
```

`private` on the constructor of a `case class` also makes the generated `apply` and `copy`
private, since Scala 2.13.2. So `CardId("")` and `someCard.copy(value = "")` are both compile
errors, and `parse` is the only way in. Pattern matching still works, because extraction only
reads.

## What validates where

In the constructor, because the rule depends on nothing but the value:

| Type | Rule |
|---|---|
| `TransactionId`, `CardId`, `MerchantId` | not empty, not only whitespace |
| `CountryCode` | exactly two characters, each `A`–`Z` |
| `AmountMinor` | strictly greater than zero |
| `GeoPoint` | `lat` in [-90, 90], `lon` in [-180, 180], neither `NaN` |
| `IpAddress` | four dotted octets, each 0–255, no leading zeros |

At the Kafka boundary in Batch 2, because the rule needs context the type does not have:

- the JSON parses at all, and every required field is present
- `merchantCategory` names a known case, `origin.type` is `Physical` or `Remote`
- `eventTime` is not more than 24 h in the future, which needs to know what *now* is

A rejected record goes to `transactions.dlq`. That was true before this ADR and is unchanged.

## Fail fast, not accumulate

`Either` stops at the first error. A bad record reports one problem, not all of them.

Accumulating every error needs `Validated` from Cats, which is an *applicative* rather than a
*monad* — the difference is exactly that a monad's second step depends on the first succeeding,
so it cannot run both and collect two failures. Adopting it here would have settled the
effect-style ADR that Batch 1 schedules deliberately. `Either` costs no dependency and leaves
that choice open.

## `NaN` is why the latitude check reads oddly

```scala
if (lat.isNaN || lat < -90.0 || lat > 90.0)
```

Every comparison against `NaN` is false, including `NaN > 90`. Without the explicit `isNaN`
test, `NaN` passes both range checks and becomes a valid latitude. IEEE-754 requires this; it
is not a Scala quirk. A test pins it.

## IPv4 only

`IpAddress.parse` accepts IPv4 and nothing else. A correct IPv6 check needs to handle `::`
compression, which means counting the groups on each side and proving they leave room for the
zeros the `::` stands for — about twenty lines of parsing for a format the generator will never
produce. It was written, reviewed, and removed as overkill.

The cost: a real payment system sees IPv6, and this one would send those records to the
dead-letter topic. Noted in `backlog.md`.

## Deliberately left unwrapped

`DedupKey` has no `parse`. We build it ourselves from values already validated; it is never
read from untrusted input.

The numbers inside `Evidence` and `CardProfile` — `capMinor`, `thresholdMinor`, `p95Minor`,
`meanAmountMinor` — are still raw. Some are thresholds from configuration, some are statistics
computed by Spark. Whether the transaction amounts among them should become `AmountMinor` is
an open question, not a settled one.

## Cost

Anything constructing a `Transaction` now threads `Either` through code that often cannot fail.
The generator in Batch 1 is the first to feel it, and how it does so is worth watching: if the
`Either` handling becomes noise there, that is evidence against this ADR, not a reason to add
an escape hatch.
