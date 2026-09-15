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
- Compiler flags (`-Xfatal-warnings` and friends): not set, so sbt's defaults apply.
- `organization`: not set, so no artifact coordinates exist yet. Nothing is published.

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
