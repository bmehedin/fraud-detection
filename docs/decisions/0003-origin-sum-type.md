# 0003 — Location is a sum type, not a field plus a flag

Date: 2026-09-15
Status: Accepted
Refines: [ADR 0002](0002-geo-as-coordinates.md)

## Context

The first draft of `Transaction` carried two separate fields:

```scala
location: GeoPoint,
channel:  Channel     // CardPresent | Online
```

An online transaction has no physical location. The card was not anywhere. The draft dealt
with this by having the generator assign online transactions the cardholder's home
coordinates.

Two problems with that.

**It is a lie that a rule then believes.** S2 (impossible travel) divides distance by elapsed
time. Fed invented coordinates, it computes an invented speed, and either misses real fraud
or invents some.

**The two fields can contradict each other.** Nothing prevented `Online` paired with a shop's
coordinates. The type permitted a combination the business considers meaningless.

The alternative of making `location` an `Option[GeoPoint]` fixes neither problem well: it
records that the location is missing but not *why*, and it forces every rule to unwrap an
option whose empty case it has no way to interpret.

## Decision

Replace both fields with one sum type:

```scala
sealed trait Origin
object Origin {
  final case class Physical(point: GeoPoint) extends Origin
  final case class Remote(ip: IpAddress)     extends Origin
}
```

`Channel` is deleted. `Origin` carries the same distinction and the matching data together.

S2 matches on the pair and answers only when both sides are physical:

```scala
(previous.origin, current.origin) match {
  case (Origin.Physical(a), Origin.Physical(b)) =>
    val kmh = speedBetween(a, b, previous.eventTime, current.eventTime)
    if (kmh > 900) Some(alert(current, kmh)) else None

  case _ =>
    None   // at least one was online — there is no physical claim to check
}
```

## Consequences

- **Seven fields instead of eight**, and one fewer chance to be inconsistent. The illegal
  combination is now unrepresentable rather than merely discouraged.
- **S2 gets smaller and more honest.** It declines to answer rather than fabricating a
  distance. This matches the cold-start policy already used by S1 and S3: a rule that cannot
  know returns nothing.
- **S2 now covers fewer transactions.** Card-not-present fraud is real and common, and we
  have given up detecting travel anomalies for it. Accepted: the alternative was detecting it
  wrongly.
- **JSON needs a discriminator.** Each `origin` carries a `"type"` tag. This is the ordinary
  cost of putting a sum type on a wire.
- **The generator must choose an origin per transaction**, and cards need a plausible
  physical/online mix. Slightly more work in Batch 1.
- **Geo-locating the IP is explicitly rejected.** It would let S2 treat `Remote` as a point,
  but needs an external GeoIP database — a dependency that teaches nothing about this stack.

## Alternatives rejected

- **Keep `channel` + `location`, fill online rows with home coordinates.** Simplest, and
  actively wrong: it feeds fiction to a rule that does arithmetic on it.
- **`location: Option[GeoPoint]` alongside `channel`.** Honest about absence but not about
  cause, keeps the two fields able to disagree, and pushes an uninterpretable `None` into
  every rule.
- **Inheritance — `IpLocation extends GeoPoint`.** Considered and dropped. It models "is-a"
  where we mean "either-or", it breaks `sealed` so exhaustiveness checking is lost, and Scala
  prohibits case-to-case inheritance outright because content equality and subclassing cannot
  both hold. See §6 of `../scala-domain-modelling.md`.
