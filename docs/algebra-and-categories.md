# Algebra and categories: the ladder up to monads

Companion to [§9 of the Scala notes](scala-domain-modelling.md), which showed that
`Either[DomainError, *]` is a monad and left the word undefined. This document builds the
definition from the bottom.

The route, so the destination is never a surprise:

**set + one operation → semigroup → monoid → (group) → category → functor → endofunctor →
natural transformation → monad.**

Each step adds exactly one requirement. Sessions so far have covered the whole route, through
*monad*. The next rung after it is *applicative*, which sits between functor and monad.

---

## 1. A set and one binary operation

A binary operation on a set `A` is a function `combine: (A, A) => A`. It may have four
properties:

| Property | Statement |
|---|---|
| **Closure** | The result is still in `A`. |
| **Associativity** | `combine(combine(a, b), c) == combine(a, combine(b, c))` |
| **Identity** | Some fixed `empty` with `combine(a, empty) == a == combine(empty, a)` |
| **Inverse** | Every `a` has an `a'` with `combine(a, a') == empty` |

Naming the rungs:

- closed + associative = **semigroup**
- plus identity = **monoid**
- plus inverse = **group**

Closure is a proof obligation in maths and a type signature in Scala: `(A, A) => A` cannot
return anything else. `Int` under `/` is not closed over the integers — `1 / 2` leaves the set.

### Worked examples

**`String` under `++`.** Monoid, `empty = ""`. No inverses: nothing concatenates with `"ab"` to
give `""`, because the result only ever grows. Not commutative — `"a" ++ "b" != "b" ++ "a"`.

**`Int` under `-`.** Closed, and that is all. Associativity fails: `(1 - 2) - 3 == -4` but
`1 - (2 - 3) == 2`. Identity fails too, because the law is two-sided: `a - 0 == a`, but
`0 - a != a`. So integer subtraction is not even a semigroup.

**`Severity` under `max`** (where `max` returns the higher precedence, `Low < Medium < High`).
A monoid, with `empty = Low`.

The identity must be **one fixed value that works for every element**, and `Low` is:

```
max(Low, Low) = Low       max(Medium, Low) = Medium       max(High, Low) = High
```

*The mistake worth remembering:* testing the identity law as `max(a, empty) == empty` and
concluding that the identity "changes with the argument", therefore semigroup only. The law is
`max(a, empty) == a`. An identity is fixed by definition, and inverses are defined relative to
it, so an "unfixed identity" is not a thing that can exist. No inverses here either:
`max(High, x)` is `High` for every `x`, never `Low`.

`max` has two properties that `++` does not:

- **commutative**: `max(a, b) == max(b, a)`
- **idempotent**: `max(a, a) == a`

## 2. Why those four properties are a distributed-systems concern

This is the reason the project cares. Each law licenses one thing the runtime does to your
fold without asking:

| Law | What it buys |
|---|---|
| **Identity** | An empty partition contributes `empty` and disappears. Without it, "no alerts" needs a special case. |
| **Associativity** | Spark may regroup the fold into per-partition partials and combine those. |
| **Commutativity** | Partitions finish in nondeterministic order, so the combine order varies run to run. |
| **Idempotence** | At-least-once delivery and replay are safe. |

`Severity` under `max` has all four. `Long` under `+` has the first three and **not** the
fourth — which is precisely why Batch 4 needs idempotency keys for alert deduplication while a
severity fold needs nothing at all. A counter is corrupted by a duplicate; a maximum is not.

*A second mistake worth remembering:* answering "duplicates are safe because `max` is
deterministic". Every pure function is deterministic, including `++`, and duplicates still
corrupt a concatenation. The property that makes replay safe is **idempotence**.

## 3. Categories

A category consists of:

- **objects**
- **arrows** (= morphisms), each with a source and a target object, written `f: A → B`
- **composition**: given `f: A → B` and `g: B → C`, an arrow `g ∘ f: A → C`
- an **identity arrow** `id_A: A → A` for each object

subject to two laws:

- associativity: `h ∘ (g ∘ f) == (h ∘ g) ∘ f`
- identity: `f ∘ id_A == f == id_B ∘ f`

Those are §1's associativity and identity again, with one addition: a typing restriction on
which pairs may be composed. Targets must meet sources.

**A monoid is a category with exactly one object.** One object means every arrow starts and
ends in the same place, so any two arrows compose and the restriction vanishes.

### The category we work in, call it Scal

Objects are Scala types (`Int`, `CardId`, `Either[DomainError, GeoPoint]`), arrows are
functions `A => B`, composition is `andThen`, identity is `identity[A]`.

```scala
val f: String => Either[DomainError, CardId]  = CardId.parse
val g: Either[DomainError, CardId] => Boolean = _.isRight
val h: String => Boolean                      = f andThen g
```

### `Severity` under `max` as a one-object category

- the **object** is a single content-free dot. Not the type `Severity`.
- the **arrows** are the three values `Low`, `Medium`, `High`, each going `• → •`.
- **composition** is `max`. `max` is not an arrow; it is the composition operator.
- the **identity arrow** is `Low`.

*The mistake worth remembering:* calling `Severity` the object and `max` the arrow. That
inversion makes composition come out as "max of max" and the identity arrow impossible to
name — the confusion is the signal that the assignment is wrong. In a one-object category the
object is a placeholder and **all the content lives in the arrows**.

Note that these arrows are values, not functions. That is allowed: arrows need not be
functions and objects need not be sets. The generality is the whole point of the definition.

If you want the arrows to *be* functions, represent each severity `s` as the function
`max(s, _): Severity => Severity`, with composition as `andThen` and `Low` as `identity`. That
is Cayley's theorem, and it is how an abstract monoid embeds into **Scal**.

### Vocabulary, precisely

| Term | What it is |
|---|---|
| **object** | A primitive of a category. A node. The theory never looks inside it. |
| **arrow** / morphism | A primitive of a category, with a source and a target object. Also never looked inside. |
| **function** | A set-theoretic mapping. What the arrows *happen to be* in **Scal** — not in every category. |
| **functor** | A mapping *between* categories: objects to objects and arrows to arrows. |

Two corrections that matter:

- An arrow does not *transform* an object. It **connects** two objects. `f: A → B` is an edge.
  The theory says nothing about what an arrow does to anything, because objects have no
  insides. All that can be said about an object is which arrows enter and leave it. In
  **Scal** the arrows happen to be inspectable functions, but that is a fact about **Scal**.
- "Object" is the general notion; "type" is what plays that role *in this particular
  category*. **Scal** is *defined* by the choice: objects := Scala types, arrows := functions.
  In **Set** the objects are sets; in the `Severity` monoid the object is a dot.

And the unification: **Cat** is the category whose objects are categories and whose arrows are
functors. A functor *is* an arrow, one level up. Which word applies depends on which category
you are standing in. The next rung goes one further: arrows between functors, called natural
transformations.

## 4. Functors and endofunctors

### The notation `Either[DomainError, *]`

`*` is a **hole**, not a runtime choice. `Either` takes two type parameters; fix the first and
one slot stays open, giving something that still needs a type before it is a type. Type-level
partial application:

```scala
Either[DomainError, CardId]    // a type: both slots filled. Its values are a Left or a Right.
Either[DomainError, *]         // one slot open: a type constructor, not a type.
```

`Either[DomainError, *]` is kind-projector plugin syntax, not stock Scala 2.13. Written by
hand it is a type lambda:

```scala
({ type L[A] = Either[DomainError, A] })#L     // Scala 2.13
[A] =>> Either[DomainError, A]                 // Scala 3
```

`List`, `Option` and `Future` already have exactly one hole and need nothing fixed.

### The definition

`Either[DomainError, *]` is neither an object nor an arrow of **Scal**. It is not a type, so
it cannot be an object; it is an assignment *taking* objects *to* objects, which lives outside
the category. That is half of a functor. The other half is the arrows.

A functor `F` from category **C** to **D** is two mappings:

- on objects: `A` becomes `F[A]`. Here `GeoPoint` becomes `Either[DomainError, GeoPoint]`.
- on arrows: `f: A → B` becomes `F[f]: F[A] → F[B]`.

The arrow half is `map`:

```scala
val lat: GeoPoint => Double = _.lat
GeoPoint.parse(44.4268, 26.1025, romania).map(lat)   // Either[DomainError, Double]
```

`map` took the arrow `GeoPoint => Double` and produced the arrow
`Either[DomainError, GeoPoint] => Either[DomainError, Double]`. That is the entire job of a
functor: **lift an arrow into the mapped world**.

Two laws, which are §1's two laws once more:

| Law | In Scala |
|---|---|
| Preserves identity | `fa.map(identity) == fa` |
| Preserves composition | `fa.map(f).map(g) == fa.map(g compose f)` |

An **endofunctor** is a functor whose source and target category are the same.
`Either[DomainError, *]` maps **Scal** to **Scal**: types to types, functions to functions.
Every Scala functor is an endofunctor, which is why Scala programmers say "functor" and mean
"endofunctor" — there is only one category in play.

### A lawless functor, and what it teaches

```scala
def badMap[A, B](e: Either[DomainError, A])(f: A => B): Either[DomainError, B] =
  e match {
    case Right(a) => Right(f(a))
    case Left(_)  => Left(DomainError.EmptyCardId)   // rewrites the error
  }
```

Smallest proof that it breaks the identity law — no `GeoPoint` or `Double` required, it fails
on the `Left` branch alone:

```scala
badMap(Left(DomainError.LatOutOfRange(91.0)))(identity)
// Left(EmptyCardId), but the law demands Left(LatOutOfRange(91.0))
```

Composition, meanwhile, **survives**: on `Left(e)`, both `badMap(badMap(Left(e))(f))(g)` and
`badMap(Left(e))(g compose f)` give `Left(EmptyCardId)`. So the two laws are independent, and
identity is the one that forbids `map` from touching the structure. A `List.map` that reversed
the list, or dropped the head, would break identity the same way and pass composition.

The lesson underneath: **the compiler accepts a lawless functor.** The `Left` channel is fixed
at `DomainError`, so `Left(EmptyCardId)` typechecks. Laws are a contract the types cannot
express, which is why Cats ships law-checking test suites rather than trusting signatures.

The genericity of `A` and `B` is *not* what is wrong here — a natural first guess, but the
violation is behavioural, not a type error.

## 5. Natural transformations

A functor maps a category to a category. A natural transformation maps a *functor* to a
functor. Both functors must share a source and a target category, or there is nothing to
compare.

`α: F ⇒ G` is not one arrow. It is a **family** of arrows, one per object:

```
α_A : F[A] → G[A]
```

A family indexed by every type is, in Scala, a polymorphic method. Cats names the type
`FunctionK` and gives it the infix alias `~>`:

```scala
trait FunctionK[F[_], G[_]] {
  def apply[A](fa: F[A]): G[A]
}
```

The `[A]` sits on `apply`, not on the trait. That placement is the whole definition:
`Option[Int] => List[Int]` is a single arrow in **Scal**; `Option ~> List` is the entire
family plus one uniform rule that generates it.

`F ~> G` is only sugar for `FunctionK[F, G]` — Scala writes any two-parameter type infix.
After `new`, an infix type needs parentheses.

```scala
import cats.~>

type DomainEither[A] = Either[DomainError, A]

val forgetError: DomainEither ~> Option = new (DomainEither ~> Option) {
  def apply[A](fa: DomainEither[A]): Option[A] = fa.toOption
}

forgetError(CardId.parse(""))      // None
forgetError(CardId.parse("abc"))   // Some(CardId("abc"))
```

`type DomainEither[A] = ...` is the hand-written form of `Either[DomainError, *]` from §4. One
hole, so it is a type constructor, so it fits where `F[_]` is expected.

### The naturality law

The square commutes: mapping before or after `α` gives the same answer.

```
α_B(fa.map(f)) == α_A(fa).map(f)
```

Three candidates, and only the polymorphic ones qualify:

```scala
def a[A](xs: List[A]): Option[A]    = xs.headOption   // natural
def b(xs: List[Int]): Option[Int]   = xs.maxOption    // not
def c[A](xs: List[A]): List[A]      = xs.reverse      // natural
```

`b` fails twice over. Shallowly, it is one arrow at `Int`, not a family — there is nothing for
it to be a natural transformation *of*. Deeply, the `Ordering[Int]` it needs is a licence to
look inside the `A`, and that licence breaks the square:

```scala
val xs = List(1, -5)
b(xs.map(_.abs))   // b(List(1, 5)) = Some(5)
b(xs).map(_.abs)   // Some(1).map   = Some(1)
```

`headOption` cannot do that: it selects a position, and `map` preserves positions.

### `reverse` is lawful here and lawless in §4

§4 said a `map` that reversed the list breaks the functor identity law. §5 says `reverse` is a
perfectly good natural transformation `List ~> List`. Not a contradiction — the two laws
forbid opposite things:

| | rewrites | must not touch |
|---|---|---|
| `map`, the arrow-half of a functor | contents | structure |
| `α`, a natural transformation | structure | contents |

`α` never even receives an `A`, so it cannot rewrite one. That orthogonality is *why* the
square commutes: neither step disturbs what the other one does. And `reverse` was never a
candidate for the functor law in the first place — it takes no `f`, so it is not a `map`.

*The correction worth remembering:* "reverse is one morphism, a map that reverses is two" is
reaching for this, but the sharp cut is which of contents/structure each law protects.

### Naturality is free

Any total, pure `def α[A](fa: F[A]): G[A]` in Scala is automatically natural. Parametricity:
with `A` abstract you cannot inspect a value of it, so you can only rearrange structure, and
rearranging structure is exactly what the square permits. Wadler's "theorems for free".

Contrast §4 sharply. The functor laws are *not* free — `badMap` typechecks and violates them.
Naturality you get by writing the signature; functor laws you must test.

## 6. Monads, concretely

A monad is an endofunctor `F` with two natural transformations:

- `pure : Id ~> F` — `Id[A] = A`, the do-nothing endofunctor. Puts a bare value in, adding
  nothing.
- `flatten : F∘F ~> F` (Cats calls it `join`) — removes one layer.

```scala
def pure[A](a: A): DomainEither[A] = Right(a)

def flatten[A](ffa: DomainEither[DomainEither[A]]): DomainEither[A] =
  ffa match {
    case Left(e)   => Left(e)
    case Right(fa) => fa
  }
```

Neither inspects the `A`, so both are natural by the previous section.

### What two layers look like

`Option[Option[Int]]` has three shapes, and `flatten` collapses each:

```scala
Some(Some(1)).flatten                 // Some(1)
Some(None).flatten                    // None
(None: Option[Option[Int]]).flatten   // None
```

Absent at either level collapses to absent. Same three shapes in the domain —
`Left(e)`, `Right(Left(e))`, `Right(Right(id))` — and `flatten` keeps the first error it meets.

### Where the second layer comes from

```scala
def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = flatten(map(fa)(f))
```

*The mistake worth remembering:* thinking `flatMap` flattens first. It receives `F[A]` — one
layer. `flatten(fa)` does not typecheck; there is nothing to flatten yet. The second layer is
**manufactured by `f`**, because `f` returns a wrapped value:

```
fa           : F[A]
map(fa)(f)   : F[F[B]]     f returns F[B], so map wraps a wrapped thing
flatten(...) : F[B]
```

So `map` creates the mess and `flatten` clears it. The order is forced by the types.

### Why a functor alone is not enough

With `map` only, each fallible step buries you one layer deeper, and reaching the value costs
a nested `map`:

```scala
step1.map(step2)                          // Either[E, Either[E, CardId]]
step1.map(step2).map(_.map(step3))        // Either[E, Either[E, Either[E, Card]]]
```

The function handed to that second `map` is `Either[E, CardId] => Either[E, Either[E, Card]]`
— not `step3` itself, because `map`'s contents are now an `Either`, not a `CardId`. Four steps
means four layers and three nested `map`s.

*The mistake worth remembering:* naming `step3`'s own type as the outer function's type. The
outer `map` never sees a `CardId`.

`flatMap` accumulates nothing:

```scala
step1.flatMap(step2).flatMap(step3)   // Either[DomainError, Card]
```

That is the concrete reason monads exist. Every `parse`, every lookup, every fallible step in
this domain returns a wrapped value, which is precisely the case a functor handles badly.

## 7. What knowing this buys

**One syntax, many effects.** `for` is desugared by the compiler into `flatMap` and `map`, so
it works for any monad, and the monad alone decides what "sequencing" means:

```scala
for { a <- List(1,2); b <- List(10,20) } yield a * b   // List(10,20,20,40) — nested loops
for { a <- opt1; b <- opt2(a) } yield a + b            // short-circuits on None
for { a <- io1;  b <- io2(a)  } yield a + b            // runs effects in order
```

Identical shape; `Either` short-circuits, `List` enumerates, `IO` sequences side effects. The
`List` case is a genuine nested loop, not a product — the inner range may depend on the outer
value:

```scala
for { a <- List(1, 2, 3); b <- List(a, a * 10) } yield (a, b)
// 6 pairs: (1,1) (1,10) (2,2) (2,20) (3,3) (3,30)
```

**A refactoring licence.** The monad laws are exactly what makes this substitution safe:

```scala
for { s <- step1; id <- step2(s); c <- step3(id) } yield c
// ==
def lookup(s: String) = for { id <- step2(s); c <- step3(id) } yield c
for { s <- step1; c <- lookup(s) } yield c
```

Associativity says regrouping steps cannot change the result; the `pure` laws say binding a
`pure(a)` is the same as substituting `a`. Extracting a helper out of a chain therefore needs
no reasoning about how failure propagates — it is guaranteed, not inspected.

**It says when a monad is the wrong tool.** After a `Left`, later steps do not run. So
`Either` *cannot* report three validation errors at once: short-circuiting is definitional,
not an implementation gap. That is the applicative rung, and `Validated`.

---

## Where we stopped

Covered: semigroup, monoid, group, category, functor, endofunctor, natural transformation,
monad, and why `flatMap` is `map` followed by `flatten`.

### `Option[A]` as a monoid — closed

The question is underspecified as asked: a monoid is a set *plus a chosen operation*. With
`empty = None` the identity law forces three of the four cases, and only
`combine(Some(a), Some(b))` is open. With nothing known about `A` there is no way to build a
new `A`, so there are exactly two honest moves:

- **Pick a side.** `case (Some(a), _) => Some(a)` — this is `orElse`. A monoid for every `A`,
  no assumptions, not commutative.
- **Demand a rule.** `case (Some(a), Some(b)) => Some(a combine b)` — requires `A` to be a
  semigroup. This is the Cats instance, and the weakest assumption that makes the case
  non-arbitrary.

The payoff: `A` need only be a *semigroup*, yet `Option[A]` comes out a full *monoid*. The
identity is `None`, which the ADT supplies for free. `Option` **adjoins an identity** — it
hands a semigroup an `empty` by adding one element that was not there. A type like
`NonEmptyList[DomainError]` has `++` and structurally cannot have an empty value; wrap it in
`Option` and it folds like a monoid.

*Objection answered:* "that is not the purpose of `Option`". True about intent, irrelevant to
the question. A monoid instance is not a claim about why a type exists, only that a lawful
operation exists on it.

### Deferred: "a monoid in the category of endofunctors"

Attempted and abandoned mid-session as too many rungs at once. The missing scaffolding, for
whenever it is picked up again:

- **`End(Scal)`** is a category whose *objects* are endofunctors on **Scal** and whose
  *arrows* are natural transformations. Composition is componentwise,
  `(β ∘ α)_A = β_A ∘ α_A`; the identity arrow on `F` is the family `identity[F[A]]`.
- That category has a second structure an ordinary category lacks: you can combine two
  **objects**. `F∘G` (apply `G`, then `F`) is again an endofunctor, with `Id` as its unit.
  `type ListOption[A] = List[Option[A]]` is `List ∘ Option`.
- §1's table then reads with the set as "the endofunctors" and the operation as `∘`.

The slogan is an ornament. Nothing in §6 or §7 depends on it.

### Next

1. **Applicative** — `map` plus independent combination. Where `Validated` accumulates errors
   and `Either` structurally cannot. This is the rung §7 ended on and the most useful next one.
2. **`IO` and side effects** — *done, written up in
   [`io-and-side-effects.md`](io-and-side-effects.md).* It was the prerequisite for writing any
   Batch 1 code. What a side effect is, why `IO` describes one rather than performing it, and how that
   makes an effect a value. [ADR 0006](decisions/0006-effect-style-cats-effect.md) committed
   the project to Cats Effect 3 and fs2, so this is not a detour — the generator cannot be
   written until it is understood.
3. **Logging as a monad (`Writer`)** — requested. A computation that carries a log beside its
   value, where `flatMap` combines the two logs instead of discarding one. The log type has to
   be a monoid, so §1's `combine` and `empty` turn out to be the machinery that makes the
   piggybacking work. The point of interest: writing a line somewhere looks like an
   unavoidable side effect, and `Writer` shows it is an ordinary value threaded through pure
   calls.
4. Connect back to §9 of the Scala notes, which asserted the monad laws for `Either` without
   deriving them.

Pending experiment, still not done: the two functor laws as ScalaCheck properties over
`Either[DomainError, *]`, plus a third showing `badMap` failing identity. ScalaCheck 1.20.0 is
already on the build.
