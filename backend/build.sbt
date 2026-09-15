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
