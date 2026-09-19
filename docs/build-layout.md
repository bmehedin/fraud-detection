# Build layout (Batch 1)

Status: 2026-09-15.

Source files in this project carry no comments, so this file explains the build.

## Where things live

```
fraud-detection/
├── CLAUDE.md
├── docs/
├── deploy/                      (from Batch 2)
└── backend/                     sbt build root
    ├── build.sbt
    ├── project/
    │   └── build.properties     pins sbt 1.12.15
    └── modules/
        └── domain/
            └── src/
                ├── main/scala/
                └── test/scala/
```

`backend/` is the root of one sbt build. The repository root stays free for things that are
not JVM code: docs, and later `deploy/` for Compose and Kubernetes manifests.

Modules sit under `backend/modules/` so that they are visibly separate from sbt's own
`project/` directory, which is sbt's own build code, not ours.

## What `build.sbt` says

```scala
ThisBuild / scalaVersion := "2.13.18"

lazy val root = (project in file("."))
  .aggregate(domain)
  .settings(
    name := "fraud-detection-backend",
    publish / skip := true
  )

lazy val domain = (project in file("modules/domain"))
  .settings(
    name := "domain"
  )
```

Four things are happening.

**`ThisBuild / scalaVersion`** sets one Scala version for every project in the build. The
`ThisBuild /` prefix is the scope: set it once here rather than repeating it per module. One
version across the repo is a constraint from the stack table — Spark 4.2.0 needs 2.13.

**`lazy val`** is required, not stylistic. sbt reads these definitions while constructing the
build graph, and `lazy` lets them refer to each other in any order without a null at
initialisation time.

**`project in file("...")`** binds a project name to a directory. The directory is where sbt
looks for `src/main/scala` and `src/test/scala`. That layout is a convention inherited from
Maven; sbt expects it and we do not fight it.

**`.aggregate(domain)`** means a command run on `root` is also run on `domain`. `sbt compile`
at the build root compiles every aggregated module.

`root` has no sources of its own. It is an empty shell whose job is to aggregate and to hold
shared settings, so `publish / skip := true` stops it from ever being published as an
artifact.

## aggregate vs dependsOn

These two are easy to confuse and do unrelated things.

- `.aggregate(x)` — *command fan-out*. Running a task on the parent also runs it on `x`.
  Nothing is shared at compile time.
- `.dependsOn(x)` — *classpath dependency*. `x`'s compiled classes are available to this
  module's code. This is a real dependency and the graph must be acyclic.

Right now only `domain` exists, so only `aggregate` appears. When `event-generator` arrives it
will be declared as:

```scala
lazy val eventGenerator = (project in file("modules/event-generator"))
  .dependsOn(domain)
```

and added to `root.aggregate(...)`. It needs both: `dependsOn` to see `Transaction`,
`aggregate` so that one `sbt compile` still builds everything.

## Compiler flags

```scala
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xlint",
  "-Xfatal-warnings"
)
```

The last one is the point. `-Xfatal-warnings` turns every warning into a compile error, so a
non-exhaustive `match` fails the build instead of printing a line you scroll past. That is the
payoff of `sealed` described in `scala-domain-modelling.md` §5: add a `MerchantCategory` and
the build stops until every rule says what it means.

The other four make the warnings worth failing on. `-deprecation` names the deprecated call,
`-feature` the language feature used without an import, `-unchecked` a pattern match whose
type test cannot actually be performed at runtime, and `-Xlint` enables a bundle of smaller
checks.

Set on `ThisBuild`, so every module compiles under the same rules.

## The `domain` module

Package `fraud.domain`, one directory deep at
`modules/domain/src/main/scala/fraud/domain/`. Four files, grouped by concept rather than one
per type, because `sealed` requires every case of a trait to sit in the same file as the trait
itself.

| File | Types |
|---|---|
| `DomainError.scala` | `DomainError` and its cases |
| `Ids.scala` | `TransactionId`, `CardId`, `MerchantId`, `CountryCode`, `IpAddress` |
| `Transaction.scala` | `AmountMinor`, `GeoPoint`, `Origin`, `MerchantCategory`, `Transaction` |
| `Alert.scala` | `DedupKey`, `RuleId`, `Severity`, `BlockReason`, `Evidence`, `Alert` |
| `CardProfile.scala` | `CardProfile` |

Field-by-field meaning is in `event-schema.md`; why the types are shaped this way is in
`scala-domain-modelling.md`. Neither is repeated here.

Values that come from outside hide their constructor behind `parse`, returning
`Either[DomainError, A]`. `CardId("")` no longer compiles. Which rule lives in the constructor
and which waits for the Kafka boundary is in
[ADR 0005](decisions/0005-smart-constructors.md).

## Tests

```scala
libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "3.2.20" % Test,
  "org.scalacheck" %% "scalacheck" % "1.20.0" % Test,
  "org.scalatestplus" %% "scalacheck-1-20" % "3.2.20.0" % Test
)
```

`% Test` is a *configuration*: these three land on the test classpath only, so nothing ships
them. The third is a bridge — ScalaTest and ScalaCheck are separate projects, and
`scalacheck-1-20` is the adapter that lets `forAll` run inside a ScalaTest suite. Its version
`3.2.20.0` tracks the ScalaTest version it adapts.

Style is `AnyFlatSpec` with `Matchers`:

```scala
class IdsSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  "CardId.parse" should "reject an empty value" in {
    CardId.parse("") shouldBe Left(DomainError.EmptyCardId)
  }
}
```

Two kinds of test sit side by side. An **example** states one input and one expected output. A
**property** states something that must hold for every input, and ScalaCheck generates cases to
attack it — 100 per property by default, shrinking any failure to the smallest input that still
breaks:

```scala
it should "reject any negative amount" in {
  forAll { (minor: Long) =>
    whenever(minor <= 0L) {
      AmountMinor.parse(minor) shouldBe Left(DomainError.NonPositiveAmount(minor))
    }
  }
}
```

Tests were written before the implementation. In a statically typed language the first red step
is not a failing assertion but a compile error: `value parse is not a member of object
fraud.domain.CardId`.

## Why only `domain` exists

The domain module holds the event model from Batch 0 — `Transaction`, `CardProfile`, `Alert`
and the ADTs around them, as written in `event-schema.md`. It depends on nothing, so it is the
only module that can compile on its own today.

The other three modules in the target architecture are created when they have a reason to
exist: `event-generator` later in Batch 1, `spark-jobs` in Batch 3, `fast-path-checker` in
Batch 4. An empty module that does nothing for a month is a directory that lies about the
state of the project.

## Things not decided yet

- Dependency versions and the effect style (Cats Effect or plain Scala): Batch 1, recorded as
  an ADR.
- `organization`: not set, so no artifact coordinates exist yet. Nothing is published.

## Toolchain

Installed 2026-09-15 with [Coursier](https://get-coursier.io), the Scala ecosystem's own
installer. `cs setup` put its launchers in
`%USERPROFILE%\AppData\Local\Coursier\data\bin` and added that directory to the user
`PATH`: `cs`, `sbt`, `sbtn`, `scala`, `scalac`, `scala-cli`, `amm`, `scalafmt`.

`JAVA_HOME` is set at user level to `C:\Program Files\Java\jdk-25.0.3`. sbt, Spark and the
Metals language server all read it; unset, each tool guesses from `PATH` and they can disagree.

| Tool | Version | Note |
|---|---|---|
| JDK | Oracle 25.0.3 | `JAVA_HOME` points here |
| sbt launcher | 2.0.9 | The `sbt` command itself |
| sbt | 1.12.15 | What actually runs, pinned in `project/build.properties` |
| Scala (project) | 2.13.18 | Downloaded by sbt, pinned in `build.sbt` |
| `scala` on PATH | scala-cli 1.16.0, Scala 3.9.0 | Unrelated to this build |

Three of those numbers look like a contradiction and are not.

**The launcher is not sbt.** `sbt.bat` is a small program whose only job is to read
`project/build.properties`, download the sbt version named there, and hand over. Launcher
2.0.9 ran sbt 1.12.15 without being asked twice. This is why an sbt build from years ago still
builds: the version is a property of the project, not of the machine.

**The `scala` command is not the project's compiler.** Coursier installs scala-cli, whose
default is Scala 3.9.0. Nothing in this repository uses it. sbt fetches the 2.13.18 compiler
itself and compiles with that. For a REPL that speaks the project's language and already has
the domain classes on its classpath, run `sbt console` from `backend/` instead.

**The `sun.misc.Unsafe` warnings on startup** come from the launcher running on JDK 25 and are
harmless noise, not a problem with the build.

## Commands

Run from `backend/`.

| Command | Effect |
|---|---|
| `sbt compile` | Compiles every aggregated module |
| `sbt domain/compile` | Compiles one module |
| `sbt test` | Runs tests in every aggregated module |
| `sbt projects` | Lists the projects sbt found |
| `sbt clean` | Deletes `target/` output |

`target/` directories are build output and are ignored by git, along with the IDE and build
server directories `.bsp/`, `.bloop/`, `.metals/`, and `.idea/`.

## One sbt at a time

Starting a second sbt on the same project while IntelliJ's sbt shell is running fails with:

```
Could not create lock for \\.\pipe\sbt-load<number>_lock, error 5
sbt thinks that server is already booting
```

sbt opens a Windows named pipe per project so that an editor and a terminal can share one
build server. The name is derived from the project path, so the second process finds the pipe
taken and refuses rather than corrupting the first one's state.

Stop the sbt shell in IntelliJ, or use that shell instead of a terminal. To typecheck without
sbt at all:

```
scala-cli compile --scala 2.13.18 modules/domain/src/main/scala
```
