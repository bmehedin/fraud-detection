# `IO` and side effects

Item 2 of [the algebra notes](algebra-and-categories.md), and the prerequisite
[ADR 0006](decisions/0006-effect-style-cats-effect.md) named before any Batch 1 code could be
written. The question answered here: what is a side effect, and what does `IO` actually do
about it.

The route: **substitution → thunk → by-name parameter → `IO` → composition → the edge →
`IOApp`.** Nothing in it is magic; the last rung is a `main` method someone else typed.

---

## 1. A side effect is a broken substitution

The useful definition is not "touches the outside world". It is this:

> An expression is **referentially transparent** if replacing it with its value, everywhere it
> appears, leaves the program's meaning unchanged.

An expression that is not referentially transparent has a **side effect**. That is the whole
definition, and it is testable.

```scala
val a = println("hi")
(a, a)                            // prints once
(println("hi"), println("hi"))    // prints twice
val b = ()
(b, b)                            // prints zero times
```

All three expressions have type `(Unit, Unit)` and all three evaluate to `((), ())`. They
print 1, 2 and 0 times. One expression, three behaviours, decided by *where it was written*.
That spread is the failure.

Look at what `val a = println("hi")` did: it evaluated the call once, at the binding; the
printing happened and was discarded; `a` kept only `()`. **The effect was never in the value,
so the name could not carry it.** Everything below is a way of fixing that one sentence.

## 2. The crude fix: a thunk

```scala
val b: () => Unit = () => println("hi")
(b, b)                                          // prints zero times
(() => println("hi"), () => println("hi"))      // prints zero times
b()                                             // prints once
```

`() => println("hi")` is a function literal with an empty parameter list: left of the arrow is
what it takes, right is the body. Its type `() => Unit` reads "takes nothing, gives back
`Unit`". `Unit` is Scala's "no interesting result"; its single value is written `()`, so the
two `()` in that line mean different things — the empty parameter list, and the value.

A **thunk** is jargon for exactly this: a zero-argument function whose purpose is to delay a
computation rather than to compute from inputs.

The important part is what changed and what did not. `b` *is* referentially transparent —
substituting its definition changes nothing, both spellings print zero times. `b()` is not.
**The impurity did not disappear; it moved** — from the moment the expression is written to
the moment someone runs it.

Behaviour is the test, not `==`. Two lambdas are never `==`, and neither are two `IO`s;
substitution is a claim about what the program does.

## 3. By-name parameters: how `IO` stores the expression

`IO(println("hi"))` does not print. The mechanism is a Scala feature, not a library trick, and
it fits in three lines:

```scala
final class MyIO[A](val run: () => A)

object MyIO {
  def apply[A](body: => A): MyIO[A] = new MyIO(() => body)
}

val c = MyIO(println("hi"))    // prints nothing
c.run()                        // prints hi
```

`body: => A` is a **by-name parameter**: the argument is *not* evaluated at the call site, it
is passed unevaluated. So `MyIO(println("hi"))` compiles to `new MyIO(() => println("hi"))`.
The thunk from §2 is still doing all the work; `apply` only wraps it.

One character decides it:

```scala
def byName[A](body: => A): MyIO[A] = new MyIO(() => body)   // byName(println("hi")) prints nothing
def byValue[A](body: A): MyIO[A]   = new MyIO(() => body)   // byValue(println("hi")) prints immediately
```

By-value evaluates the argument before the call — which is precisely the `val a = println("hi")`
failure of §1, wearing a different hat.

Cats Effect's real constructor is `def apply[A](thunk: => A): IO[A]`. The same line.

## 4. Aside: a type alias is not a type

`Thunk` above could be written as an alias, and it is worth being clear what that buys:

```scala
type Thunk[A] = () => A
val f: Thunk[Unit] = () => println("hi")
val g: () => Unit  = f                    // compiles: same type, different spelling
```

A type alias creates **no new type**. The compiler erases it on sight. `A` is a type
parameter, a placeholder any type can fill, like a Java generic.

This is why the domain model uses `final case class AmountMinor(value: Long)` and not
`type AmountMinor = Long`. The case class is a genuinely new type the compiler keeps separate
from every other `Long`; an alias would let any `Long` through. Aliases buy readability only.

## 5. Composition: `flatMap` runs nothing

```scala
val hi   = IO(println("hi"))
val both = hi.flatMap(_ => hi)      // prints nothing

both.unsafeRunSync()                // prints hi twice
```

Substitute `hi` by its definition and the behaviour is identical:

```scala
IO(println("hi")).flatMap(_ => IO(println("hi")))   // also two prints
```

Substitution held. `hi` is referentially transparent, and §1's failure is repaired — because
`hi` is not `()`. Its type is `IO[Unit]`: a description object holding an unevaluated thunk,
where `Unit` is what that description will *produce when run*, later, possibly many times.
Being a `val` does not mean "already computed"; it means the recipe is computed, not the
cooking.

**The effect moved into the type.** `Unit` could not carry a print. `IO[Unit]` can.

What happens when `both` runs, in order:

1. the runtime runs the left `hi` — prints, produces `()`;
2. it feeds `()` to `_ => hi`, and the lambda *returns a description* — it prints nothing;
3. the runtime runs that returned description — prints again.

Step 2 is the one worth pausing on. The signature is `def flatMap[B](f: A => IO[B]): IO[B]`:
`f` hands back a recipe, never a result. Every print happened inside the runtime, never inside
`flatMap` and never inside the lambda.

The sugar for the same thing:

```scala
val both = for {
  _ <- hi
  _ <- hi
} yield ()
```

That is not a loop. The compiler rewrites it into the `flatMap` above. `for` in Scala is
syntax for `flatMap`/`map` on any type that has them — which is why the same shape works for
`Option`, for the `Either` in `GeoPoint.parse`, and for `IO`.

## 6. Where the impurity went

`both` is pure. `both.unsafeRunSync()` is not, and deliberately so:

```scala
val r = both.unsafeRunSync()
(r, r)                                        // 2 prints
(both.unsafeRunSync(), both.unsafeRunSync())  // 4 prints
```

§1's failure, reproduced exactly. Purity was never achieved by eliminating the effect — it was
achieved by **cornering** it. The program is pure description, and one call performs it. The
call is named `unsafe` so that it is grep-able: if it appears anywhere but the edge, someone
made a mistake.

The **edge** is the boundary between the program and the operating system. Something has to
cross it, because the OS does not accept recipes.

## 7. `IOApp`: the `main` you inherit

```scala
package fraud.generator

import cats.effect.{IO, IOApp}

object Main extends IOApp.Simple {
  val run: IO[Unit] = both
}
```

No `main`, no `unsafeRunSync`. `IOApp` is an ordinary trait containing a `final def main` that
the object inherits. There is no JVM magic: the compiler emits the static `main` the JVM looks
for, and the JVM calls it as it calls any other.

`javap -p` on the compiled class, which is the evidence:

```
public final class fraud.generator.Main {
  public static cats.effect.IO<scala.runtime.BoxedUnit> run();
  public static cats.effect.IO<cats.effect.ExitCode> run(scala.collection.immutable.List<java.lang.String>);
  public static void main(java.lang.String[]);
}
```

`public static void main(java.lang.String[])` — byte for byte the Java signature. Scala's
`Array[String]` *is* `String[]`, and `Unit` erases to `void`. (`BoxedUnit` appears because JVM
generics cannot hold `void`.)

What the inherited body does, none of which is our code: starts the Cats Effect runtime and
its thread pools, runs `run` as a **fiber** (a lightweight thread the runtime schedules itself,
not an OS thread), blocks the JVM's main thread until that fiber finishes, installs a shutdown
hook so Ctrl-C *cancels* the `IO` rather than killing the process, and calls `System.exit` with
the `ExitCode`.

Two flavours: `IOApp.Simple` wants a `val run: IO[Unit]`; full `IOApp` wants
`def run(args: List[String]): IO[ExitCode]`, which is how command-line arguments and the exit
code become available.

## 8. What was run

`backend/modules/event-generator`, created for this: cats-effect 3.7.1, fs2 3.14.0, depending
on `domain`. `sbt eventGenerator/run` printed `hi` twice, as predicted.

sbt warned that `IOApp` was running on a non-main thread, because `sbt run` executes the
program inside sbt's own JVM. The fix is `Compile / run / fork := true` — a separate JVM per
run. Worth knowing that `sbt run` is not a faithful reproduction of `java -jar` unless forked.

## 9. Open

- **Stack safety.** `() => A` has a lawful `flatMap`, so thunks form a monad in the sense of §6
  of the algebra notes. Chaining 100,000 of them and running the result overflows the stack;
  `IO` does not, because it interprets the chain in a loop rather than nesting calls. Left as
  an experiment to run rather than a claim to believe.
- **What else `IO` adds over a thunk:** errors as values, cancellation, async callbacks,
  `Resource`. None of it needed yet.
- **Seeded randomness.** The next decision, and the first place Batch 1 needs state. Started,
  and stopped mid-exercise: [`pure-randomness.md`](pure-randomness.md) is the resume point.
