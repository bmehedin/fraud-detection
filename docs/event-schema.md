# Event Schema (Batch 0)

Status: draft, 2026-09-15. Stable-by target: 2026-09-22.

Three types cross a boundary: `Transaction` (ingested), `CardProfile` (derived, Spark batch →
Redis), and `Alert` (emitted). Everything else is internal.

## Principles

- **No nulls in the domain.** Absence is `Option`, alternatives are sealed traits. A field
  that cannot be absent is not an `Option`.
- **No raw `String` identifiers.** Every id is a value class over `String` so that
  `CardId` and `MerchantId` cannot be swapped by accident.
- **Money is a `Long` of minor units, never `Double`.** Floating point cannot represent
  1.10 EUR, and rules compare amounts to thresholds.
- **Event time is carried by the event**, never inferred from arrival. The whole slow path
  depends on this.
- Wire format is JSON for Batches 1–2 (readable while learning). Avro + Schema Registry is
  an optional Batch 2 exercise; the field set below is the contract either way.

## Transaction

The only input event. Kafka topic `transactions.raw`, key = `cardId` (as raw UTF-8 bytes).

| Field | Type | Optional | Notes |
|---|---|---|---|
| `transactionId` | `TransactionId` | no | UUIDv4. Unique per event; the natural idempotency handle. |
| `cardId` | `CardId` | no | Partition key. Ordering is guaranteed only within one of these. |
| `merchantId` | `MerchantId` | no | |
| `merchantCategory` | `MerchantCategory` | no | Sealed ADT, ~8 cases. Not an open string. |
| `amountMinor` | `Long` | no | EUR cents. Always > 0; a refund is out of scope. |
| `origin` | `Origin` | no | Where it happened: `Physical(GeoPoint)` or `Remote(IpAddress)`. See ADR 0003. |
| `eventTime` | `Instant` | no | UTC, millisecond precision. When the transaction happened. |

Seven fields. Every one is read by at least one rule or by the partitioning, which is the
test for whether a field earns its place.

### Scala 2.13 sketch

```scala
final case class TransactionId(value: String) extends AnyVal
final case class CardId(value: String)        extends AnyVal
final case class MerchantId(value: String)    extends AnyVal
final case class CountryCode(value: String)   extends AnyVal  // ISO 3166-1 alpha-2
final case class IpAddress(value: String)     extends AnyVal

sealed trait MerchantCategory
object MerchantCategory {
  case object Grocery       extends MerchantCategory
  case object Fuel          extends MerchantCategory
  case object Restaurant    extends MerchantCategory
  case object Electronics   extends MerchantCategory
  case object Travel        extends MerchantCategory
  case object Entertainment extends MerchantCategory
  case object Pharmacy      extends MerchantCategory
  case object CashWithdrawal extends MerchantCategory
}

final case class GeoPoint(lat: Double, lon: Double, country: CountryCode)

sealed trait Origin
object Origin {
  final case class Physical(point: GeoPoint) extends Origin
  final case class Remote(ip: IpAddress)     extends Origin
}

final case class Transaction(
  transactionId:    TransactionId,
  cardId:           CardId,
  merchantId:       MerchantId,
  merchantCategory: MerchantCategory,
  amountMinor:      Long,
  origin:           Origin,
  eventTime:        Instant
)
```

`MerchantCategory` and `Origin` are sealed, so a `match` over them is checked for
exhaustiveness at compile time. Add a category and every rule that switches on one stops
compiling until it is handled — which is the point of modelling it this way rather than as a
`String`.

### Why `origin` replaced `channel` + `location`

An earlier draft had two fields: `channel` (`CardPresent` | `Online`) and `location`
(`GeoPoint`). An online transaction has no physical location, so the generator would have
given it the cardholder's home coordinates — a lie that produces fake distances in S2.

The two fields could also contradict each other. Nothing stopped `Online` being paired with a
shop's coordinates. One `Origin` field makes that combination impossible to write down, which
is the point of §7 in `scala-domain-modelling.md`: make illegal states unrepresentable.

`Origin` is a two-way choice where **both branches carry data**, so both are `case class`, not
`case object`. See [ADR 0003](decisions/0003-origin-sum-type.md).

### Wire example

A sum type needs a discriminator on the wire, so each `origin` carries a `type` tag.

In person:

```json
{
  "transactionId": "8f1c7c4a-9b2e-4d31-9d5b-1f2a3b4c5d6e",
  "cardId": "card-000412",
  "merchantId": "merch-0091",
  "merchantCategory": "Electronics",
  "amountMinor": 249900,
  "origin": {
    "type": "Physical",
    "point": { "lat": 44.4268, "lon": 26.1025, "country": "RO" }
  },
  "eventTime": "2026-09-15T12:04:31.220Z"
}
```

Online — note there is no `point` field at all, not a null one:

```json
{
  "transactionId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
  "cardId": "card-000412",
  "merchantId": "merch-0342",
  "merchantCategory": "Entertainment",
  "amountMinor": 1499,
  "origin": { "type": "Remote", "ip": "203.0.113.47" },
  "eventTime": "2026-09-15T12:31:08.004Z"
}
```

### Validation

A record failing any of these is malformed and goes to the dead-letter topic
`transactions.dlq` in Batch 2, never dropped silently:

- JSON does not parse, or a required field is absent
- `amountMinor <= 0`
- `merchantCategory` is not a known case
- `origin.type` is neither `Physical` nor `Remote`
- for `Physical`: `lat` outside [-90, 90] or `lon` outside [-180, 180]
- for `Remote`: `ip` is not a syntactically valid IPv4 or IPv6 address
- `eventTime` is more than 24 h in the future (clock skew, not late data)

Late data is **not** malformed. An old `eventTime` is valid input; the watermark decides
whether the slow path still uses it.

## CardProfile

Derived by the Spark batch job, written to Redis, read by S1 and S3. Never ingested, never
on Kafka.

| Field | Type | Notes |
|---|---|---|
| `cardId` | `CardId` | Redis key |
| `txnCount` | `Long` | Rules stay silent below 30 |
| `meanAmountMinor` | `Double` | |
| `stdDevAmountMinor` | `Double` | |
| `p95AmountMinor` | `Long` | |
| `knownCategories` | `Set[MerchantCategory]` | |
| `homeCountry` | `CountryCode` | |
| `computedAt` | `Instant` | Staleness is observable, not guessed |

## Alert

Emitted to `fraud.alerts`, key = `cardId` (so alerts for one card stay ordered and land
beside its transactions). Field list is in `requirements.md` §4.

```scala
final case class DedupKey(value: String) extends AnyVal

sealed trait RuleId
object RuleId {
  case object F1 extends RuleId
  case object F2 extends RuleId
  case object F3 extends RuleId
  case object S1 extends RuleId
  case object S2 extends RuleId
  case object S3 extends RuleId
}

sealed trait Severity
object Severity {
  case object Low    extends Severity
  case object Medium extends Severity
  case object High   extends Severity
}

sealed trait BlockReason
object BlockReason {
  final case class BlockedCard(cardId: CardId)             extends BlockReason
  final case class BlockedMerchant(merchantId: MerchantId) extends BlockReason
}
```

`Evidence` declares two abstract members, so every case is forced to say which rule it
belongs to and how serious it is:

```scala
sealed trait Evidence {
  def rule: RuleId
  def severity: Severity
}

object Evidence {

  final case class Blocklisted(reason: BlockReason) extends Evidence {
    val rule: RuleId       = RuleId.F1
    val severity: Severity = Severity.High
  }

  final case class AmountCap(amountMinor: Long, capMinor: Long) extends Evidence {
    val rule: RuleId       = RuleId.F2
    val severity: Severity = Severity.Medium
  }

  final case class Velocity(count: Int, windowSeconds: Int) extends Evidence {
    val rule: RuleId       = RuleId.F3
    val severity: Severity = Severity.High
  }

  final case class AmountDeviation(amountMinor: Long, thresholdMinor: Double) extends Evidence {
    val rule: RuleId       = RuleId.S1
    val severity: Severity = Severity.Medium
  }

  final case class ImpossibleTravel(from: GeoPoint, to: GeoPoint, kmh: Double) extends Evidence {
    val rule: RuleId       = RuleId.S2
    val severity: Severity = Severity.High
  }

  final case class UnusualCategory(
    category:    MerchantCategory,
    amountMinor: Long,
    p95Minor:    Long
  ) extends Evidence {
    val rule: RuleId       = RuleId.S3
    val severity: Severity = Severity.Medium
  }
}

final case class Alert(
  dedupKey:    DedupKey,
  cardId:      CardId,
  triggeredBy: TransactionId,
  detectedAt:  Instant,
  evidence:    Evidence
) {
  def rule:     RuleId   = evidence.rule
  def severity: Severity = evidence.severity
}
```

One `Evidence` case per rule. Pattern matching over it is exhaustive, and an alert that
cannot state its numbers cannot be constructed.

Two details worth noting:

`Blocklisted` takes a `BlockReason`, not a `String`. F1 fires on either the card or the
merchant, and a bare string could not say which — the same argument that made ids into
wrappers.

`ImpossibleTravel` carries both `GeoPoint`s, not just the two country codes, so the alert can
show the exact positions it compared rather than asking someone to take 930 km/h on trust.

### Wire example

`Evidence` is a sum type, so it carries a `"type"` tag like `Origin` does:

```json
{
  "dedupKey": "card-000412:S2:485912",
  "cardId": "card-000412",
  "triggeredBy": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
  "detectedAt": "2026-09-15T12:31:08.004Z",
  "evidence": {
    "type": "ImpossibleTravel",
    "from": { "lat": 44.4268, "lon": 26.1025, "country": "RO" },
    "to":   { "lat": 35.6762, "lon": 139.6503, "country": "JP" },
    "kmh": 28640.5
  }
}
```

`rule` and `severity` are absent from the wire format because they are derived. A consumer
computes them from the evidence exactly as we do. Writing them out would let a hand-edited or
buggy producer emit an alert whose `rule` disagreed with its `evidence`.

## Schema evolution

Until Batch 2 there is no compatibility guarantee — change the case classes freely. Once
Avro lands, the rule is **backward compatible only**: add optional fields, never remove or
retype. Renaming `amountMinor` after that point means a new topic.
