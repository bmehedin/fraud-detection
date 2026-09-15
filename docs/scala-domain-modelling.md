# Scala notes: modelling the domain

Working notes from building the Batch 0 model. Written to be re-read later.

## 1. `val` and `var`

```scala
val x = 5    // can never be reassigned
var y = 5    // can be reassigned
```

We use `val` everywhere and `var` essentially nowhere. A transaction that happened cannot
un-happen. Nothing in the domain needs to change after it is built.

## 2. Why `case class` instead of `class`

A plain class compares by **memory address** — "are these the same object?" — not by content:

```scala
class GeoPoint(val lat: Double, val lon: Double)

val a = new GeoPoint(44.4, 26.1)
val b = new GeoPoint(44.4, 26.1)

a == b        // false
println(a)    // GeoPoint@6d06d69c
```

Both results are useless to us. Add one keyword:

```scala
final case class GeoPoint(lat: Double, lon: Double)

val a = GeoPoint(44.4, 26.1)
val b = GeoPoint(44.4, 26.1)

a == b        // true
println(a)    // GeoPoint(44.4,26.1)
```

**`case class` gives four things for free:**

1. **Equality by content.** Two values with the same fields are equal.
2. **A readable `toString`.** Logs and test failures become legible.
3. **No `new` needed.**
4. **A `copy` method**, for making a changed version without mutating anything:

```scala
val moved = a.copy(lat = 45.0)   // GeoPoint(45.0,26.1)
// a is unchanged
```

`copy` is how you "change" an immutable value: you don't, you build a new one.

It also enables pattern matching (§5), which is the reason the rest of the model works.

**Why content equality matters here.** Kafka can hand us the same transaction twice, and
every test asks "is this the alert I expected?". Both need *same contents*, not *same object
in memory*. With a plain class both comparisons silently fail.

**`final`** means nobody can subclass it. For data that is what we want — see §6.

## 3. Wrapping ids: `AnyVal`

Don't use `String` for identifiers:

```scala
final case class CardId(value: String)     extends AnyVal
final case class MerchantId(value: String) extends AnyVal
```

A plain `String` accepts anything — a card id, a merchant id, an empty string. The program
runs happily and is wrong. The wrapper turns a business rule into a **type rule**, so the
compiler enforces it:

```scala
def lookup(id: MerchantId): Merchant = ???
lookup(CardId("card-000412"))
// error: type mismatch;
//  found   : CardId
//  required: MerchantId
```

**`extends AnyVal` makes it free.** At compile time it is a distinct type. At runtime the
wrapper is erased and the JVM passes the plain `String` — no object is allocated.

Caveat: erasure is not total. Put them in an `Array[CardId]` or certain generic positions and
Scala re-creates the wrapper object ("boxing"). Irrelevant at our scale.

This pattern is called a **newtype**. Scala 3 has `opaque type` for it; on 2.13, `AnyVal`.

## 4. Sum types: `sealed trait`

`case class` is "AND". This is "OR".

```scala
sealed trait Severity
object Severity {
  case object Low    extends Severity
  case object Medium extends Severity
  case object High   extends Severity
}
```

- **`trait`** — a type with no data of its own. A category name.
- **`sealed`** — every subtype must be declared in the same file, so the compiler knows the
  complete list.
- **`case object`** — a single value, not a class.

### `case object` or `case class`?

**`case object` when the case carries no data. `case class` when it does.**

```scala
case object High extends Severity                      // carries nothing
final case class Velocity(count: Int, windowSeconds: Int) extends Evidence  // carries two numbers
```

A common mistake is to reach for `case object` because the *choice* is binary, and so lose
the data each branch needs to carry. The choice being two-way says nothing about whether the
branches are empty. `Origin` (see `event-schema.md`) is a two-way choice where both branches
carry data.

### Abstract members: making every case answer a question

So far traits have been pure labels. A trait can also *demand* something from each case:

```scala
sealed trait Evidence {
  def rule: RuleId
  def severity: Severity
}

final case class Velocity(count: Int, windowSeconds: Int) extends Evidence {
  val rule: RuleId       = RuleId.F3
  val severity: Severity = Severity.High
}
```

`def` with no body is abstract — a question the trait asks. Every case must answer it or it
will not compile. A case answers with `val` when the answer is a constant, `def` when it has
to be computed.

**Why this instead of a separate field on `Alert`.** The draft had `rule` and `severity` as
their own fields next to `evidence`, which permitted:

```scala
Alert(rule = RuleId.F1, evidence = Evidence.Velocity(7, 60))
```

A blocklist alert carrying velocity evidence. The same defect as `channel` + `location`: two
fields that can disagree. Moving them onto the trait means the evidence answers for itself
and the contradiction cannot be written.

The general move: **when one field can always be computed from another, compute it.** Don't
store both and hope they stay in step.

## 5. Pattern matching, and why `sealed` pays off

```scala
def describe(s: Severity): String =
  s match {
    case Severity.Low    => "worth a look"
    case Severity.Medium => "investigate"
    case Severity.High   => "act now"
  }
```

Delete the last case. Because the trait is `sealed`, the compiler knows one is missing:

```
warning: match may not be exhaustive.
It would fail on the following input: High
```

Add `scalacOptions += "-Xfatal-warnings"` to `build.sbt` and that warning fails the build.

**This is the whole payoff.** Add a seventh `MerchantCategory` months from now and every rule
that inspects a category stops compiling until you decide what it means. The compiler hands
you a to-do list. With a `String` you get silent wrong answers, discovered via a bad alert.

You can also match on shape and pull data out at the same time:

```scala
(previous.origin, current.origin) match {
  case (Origin.Physical(a), Origin.Physical(b)) => checkSpeed(a, b)
  case _                                        => None
}
```

`a` and `b` are the `GeoPoint`s, extracted by the match.

## 6. ADTs versus inheritance

The rule: **in a domain model, reach for a sum type, never a subclass.**

Tempting but wrong:

```scala
class GeoPoint(val lat: Double, val lon: Double)
class IpLocation(lat: Double, lon: Double, val ip: String) extends GeoPoint(lat, lon)
```

Right:

```scala
sealed trait Origin
object Origin {
  final case class Physical(point: GeoPoint) extends Origin
  final case class Remote(ip: IpAddress)     extends Origin
}
```

Three reasons, the third decisive:

**It models the wrong relationship.** Inheritance means "is-a". An IP address is not a kind
of coordinate. We mean "one **or** the other" — a sum, not a hierarchy.

**It destroys `sealed`.** An open base class means the compiler cannot know the full list of
possibilities, so exhaustiveness checking is gone — the entire benefit of §5.

**Scala forbids it.** A case class cannot extend another case class:

```
error: case class Remote has case ancestor GeoPoint,
       but case-to-case inheritance is prohibited.
```

Content equality and inheritance genuinely cannot coexist. If `Remote` extended `GeoPoint`,
should a `GeoPoint` equal a `Remote` with matching coordinates? Every possible answer breaks
a law that `equals` must obey, so the language bans the construct.

**Inheritance is for sharing behaviour. ADTs are for enumerating possibilities.** In a domain
model we always want the second.

## 7. Why "algebraic" data type

The name is literal. Types compose by multiplication and addition over how many values they
can hold.

- **Product (AND).** `GeoPoint` is a lat AND a lon AND a country, so the count of possible
  values is `|Double| × |Double| × |CountryCode|` — a product.
- **Sum (OR).** `Severity` is `Low` OR `Medium` OR `High` → `1 + 1 + 1 = 3` — a sum.
  `Option[A]` is `1 + |A|`: `None`, or `Some(a)` for each `a`.
- **Both at once.** `Origin` is `|GeoPoint| + |IpAddress|` — a sum whose branches are
  products.

So `Evidence` is a six-way sum whose cases are products.

The practical reading of this: **make illegal states unrepresentable.** Two loose fields
(`channel` and `location`) could contradict each other — `Online` paired with a shop's
coordinates. One `Origin` field cannot. The bad combination has no way to be written down.

## 8. No nulls

Absence is `Option[A]` — `Some(a)` or `None`. Alternatives are sealed traits. Both are things
the compiler can check; `null` is not. A field that cannot be absent is not an `Option`.

Note the pattern that keeps recurring: a rule that cannot answer returns `None` rather than
guessing. S1 and S3 return `None` for a card with too little history. S2 returns `None` when
either side was online. Silence is a valid answer; a fabricated one is not.

## Open questions to answer later

- Is `Option[A]` a monoid? Under what conditions? (It depends on what you may assume
  about `A`.)
- `Severity` under `max` is a commutative idempotent monoid with identity `Low`. Why does
  that make it safe to fold alerts in any order?
