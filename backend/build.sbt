ThisBuild / scalaVersion := "2.13.18"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xlint",
  "-Xfatal-warnings"
)

lazy val catsEffectVersion = "3.7.1"
lazy val fs2Version = "3.14.0"

lazy val testDependencies = Seq(
  "org.scalatest" %% "scalatest" % "3.2.20" % Test,
  "org.scalacheck" %% "scalacheck" % "1.20.0" % Test,
  "org.scalatestplus" %% "scalacheck-1-20" % "3.2.20.0" % Test
)

lazy val root = (project in file("."))
  .aggregate(domain, eventGenerator)
  .settings(
    name := "fraud-detection-backend",
    publish / skip := true
  )

lazy val domain = (project in file("modules/domain"))
  .settings(
    name := "domain",
    libraryDependencies ++= testDependencies
  )

lazy val eventGenerator = (project in file("modules/event-generator"))
  .dependsOn(domain)
  .settings(
    name := "event-generator",
    Compile / run / fork := true,
    libraryDependencies ++= testDependencies ++ Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-io" % fs2Version
    )
  )
