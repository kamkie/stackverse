import play.sbt.PlayImport._

ThisBuild / scalaVersion := "3.8.4"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala)
  .settings(
    name := "stackverse-backend-play-scala",
    organization := "dev.stackverse",
    version := "0.1.0",
    scalacOptions ++= Seq("-deprecation", "-feature", "-Werror"),
    libraryDependencies ++= Seq(
      guice,
      "com.zaxxer" % "HikariCP" % "7.1.0",
      "org.postgresql" % "postgresql" % "42.7.13",
      "org.flywaydb" % "flyway-core" % "12.11.0",
      "org.flywaydb" % "flyway-database-postgresql" % "12.11.0",
      "com.nimbusds" % "nimbus-jose-jwt" % "10.9.1",
      "io.opentelemetry" % "opentelemetry-api" % "1.64.0",
      "io.opentelemetry" % "opentelemetry-sdk" % "1.64.0",
      "io.opentelemetry" % "opentelemetry-sdk-extension-autoconfigure" % "1.64.0",
      "io.opentelemetry" % "opentelemetry-exporter-otlp" % "1.64.0",
      "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.2" % Test,
      "org.testcontainers" % "postgresql" % "1.21.4" % Test
    ),
    Test / fork := true,
    scalafmtOnCompile := false
  )
