ThisBuild / scalaVersion := "2.13.18"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xlint",
  "-Xfatal-warnings"
)

lazy val root = (project in file("."))
  .aggregate(domain)
  .settings(
    name := "fraud-detection-backend",
    publish / skip := true
  )

lazy val domain = (project in file("modules/domain"))
  .settings(
    name := "domain",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.20" % Test,
      "org.scalacheck" %% "scalacheck" % "1.20.0" % Test,
      "org.scalatestplus" %% "scalacheck-1-20" % "3.2.20.0" % Test
    )
  )
