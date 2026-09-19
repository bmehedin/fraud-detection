# Pure randomness

**Status: the core is built.** `Rand[A]` with `map` and `flatMap` exists, compiles, and
behaves as predicted. The behaviour model on top of it has not been started.

Batch 1 needs seeded, reproducible randomness. This is the first place the generator needs
*state*. An earlier session stopped mid-exercise on `flatMap`; that stall and its resolution
are both recorded here, because the shape of the confusion is worth keeping.

---

## 1. Decided

Three choices, settled and not up for re-litigation:

1. **One sequential stream, not one fiber per card.** If two fibers draw from a shared
   generator, the thread interleaving decides who gets which number, and a fixed seed no
   longer gives a fixed run. Sequential generation removes the question.
2. **A seed per card, derived from one master seed.** With 1,000 cards sharing a generator,
   card 42's transactions depend on how many draws cards 1–41 happened to make — so changing
   the fraud percentage would silently change every card's "normal" behaviour. Per-card seeds
   make card 42 replay identically in isolation, which is what the Batch 1 experiments need.
3. **Pure seed threading**, over a mutable `scala.util.Random`, over `IO(rnd.nextInt())`, and
   over `Random[IO]` from cats-effect-std. The deciding factor: with the seed threaded as a
   value, generating is a pure function and its tests need no runtime and no
   `unsafeRunSync`.

Point 2 is not implemented yet. An ADR covering all three is still owed.

## 2. The problem

`scala.util.Random` is mutable: `rnd.nextInt()` answers differently on each call. That is
exactly the broken substitution of
[§1 of the `IO` notes](io-and-side-effects.md) — an effect hidden in a field.

The insight the whole approach rests on: **`Random` already is a pure function; it just hides
its input.** Strip the hiding and a generator is a function `Seed => (A, Seed)` — hand it a
seed, it hands back a value *and the next seed*.

A linear congruential generator: multiply, add, let the `Long` overflow. `nextLong` answers
the same pair for the same seed every time, forever, so it is referentially transparent and
can be substituted freely. "Random" here means consecutive seeds look unrelated, not that
anything is unpredictable.

## 3. The cost that `Rand` removes

Threading by hand:

```scala
val (a, s1) = nextLong(Seed(42))
val (b, s2) = nextLong(s1)
val (c, s3) = nextLong(s2)
```

Every draw yields a seed that must be carried to the next call. Building one transaction needs
an amount, a merchant, a category and a timestamp — four draws, four hand-offs, and writing
`nextLong(s1)` twice produces the same "random" value twice with no compiler error.

`Rand[A]` names that shape. It is an endofunctor on **Scal**, and a monad in the sense of
[§6 of the algebra notes](algebra-and-categories.md). `flatMap` threads the seed correctly
once, so `for { a <- nextLong; b <- nextLong } yield (a, b)` threads it correctly always, and
the duplicate-seed bug becomes unexpressible.

## 4. What exists

`backend/modules/event-generator/src/main/scala/fraud/generator/Rand.scala`:

```scala
final case class Seed(value: Long)

trait Rand[A] { outer =>

  def run(seed: Seed): (A, Seed)

  def map[B](f: A => B): Rand[B] = new Rand[B] {
    def run(seed: Seed): (B, Seed) = {
      val (a, nextSeed) = outer.run(seed)
      (f(a), nextSeed)
    }
  }

  def flatMap[B](f: A => Rand[B]): Rand[B] = new Rand[B] {
    def run(seed: Seed): (B, Seed) = {
      val (a, nextSeed) = outer.run(seed)
      f(a).run(nextSeed)
    }
  }
}

object Rand {

  val nextLong: Rand[Long] = new Rand[Long] {
    def run(seed: Seed): (Long, Seed) = {
      val next = seed.value * 6364136223846793005L + 1442695040888963407L
      (next, Seed(next))
    }
  }
}
```

Four things in that file were the actual lesson:

- **`Rand` is a trait, not the type alias `Seed => (A, Seed)`.** The alias was tried first and
  abandoned. It hides the fact that `Rand[B]` is a function, so "return a `Rand[B]`" silently
  means "return a function", and every implementation has to start by taking a seed it was
  never visibly given. The trait makes the same thing ordinary: `new Rand[B] { ... }`.
- **`new Rand[B] { ... }` is an anonymous instance** — a class with no name, declared at the
  point of use. It is what `seed => { ... }` was all along, spelled out.
- **`outer =>` is a self alias.** It names the enclosing `Rand[A]` so the anonymous `Rand[B]`
  inside can reach it. Without it, a bare `run(seed)` in there binds to the *new* instance's
  own `run` — infinite recursion, and it compiles.
- **`nextLong` is a `val`, not a `def`.** It takes no arguments. It does not generate
  anything; it is one fixed *description* of a generator. `run` is the method that generates,
  and `run` is where the `Seed` parameter belongs. A parameterless `def` would rebuild an
  identical instance on every mention.

Naming the methods `map` and `flatMap` is what makes `for` work: Scala desugars a `for`
comprehension into calls to those two methods, by name. There is no interface to implement and
no import to add.

## 5. The two answers that carry the weight

**Which seed does `flatMap` feed to `f`'s result?** The one the outer generator produced —
`nextSeed`, never the incoming `seed`. Passing the original would reproduce the
`nextLong(s1)`-twice bug at the level of the combinator, making it permanent instead of
occasional.

**What is `f(a)`?** A `Rand[B]`, which is *not* a `(B, Seed)` — it is a thing that produces one
when handed a seed. So it has to be run: `f(a).run(nextSeed)`. The two steps are the whole
trick: the first call picks the generator, the second runs it. In the abandoned alias version
this was `f(a)(nextSeed)`, and the double parentheses were exactly what made it unreadable.

`map` by contrast just rewraps. The value is transformed, the seed passes through untouched,
because `f` knows nothing about seeds.

## 6. The experiment, and what it showed

Predicted first, then run:

```scala
val pair: Rand[(Long, Long)] =
  for {
    a <- Rand.nextLong
    b <- Rand.nextLong
  } yield (a, b)
```

| | Predicted | Observed |
|---|---|---|
| The two `Long`s in one run | different, because `nextSeed` is threaded | `(-7964744663189004623, 4159066171780167020)` |
| `run(Seed(42))` twice in one process | identical | identical |
| `flatMap` sabotaged to pass `seed` | both `Long`s equal | `(-7964744663189004623, -7964744663189004623)` |

The third row is the one to remember. The sabotaged `flatMap` **compiled without a warning**:
`seed` and `nextSeed` have the same type, so the type checker cannot tell them apart. Correct
seed threading is a property of that one method and is enforced nowhere else in the program.
Every generated stream in the system rests on four lines the compiler will not check.

## 7. Open

- **`Rand.nextLong` is the only generator that exists.** Everything the transaction model
  needs is derived from it through `map` and `flatMap`: a bounded `Int`, an amount in a range,
  a pick from `MerchantCategory`'s eight cases, a timestamp. None written.
- **Per-card seed derivation** (§1, point 2) is decided but not implemented.
- **No tests.** `Rand` has none. The reproducibility claim above is backed only by a throwaway
  `main` that has since been deleted, and §6's third row is the argument for testing it.
- **The ADR** for §1's three decisions.
- **The behaviour model** — amount distribution, timing between transactions, merchant choice
  — is untouched. Randomness is the machinery, not the model.
- `Compile / run / fork := true` for the `eventGenerator` module, to silence the Cats Effect
  non-main-thread warning. Not decided.
- `backend/.idea/misc.xml` and `backend/.idea/.gitignore` are tracked by git and probably
  should not be.
