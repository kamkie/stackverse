import com.typesafe.sbt.packager.archetypes.JavaAppPackaging

ThisBuild / scalaVersion := "3.8.4"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "stackverse-backend-scala-http4s",
    organization := "dev.stackverse",
    version := "0.1.0",
    Compile / mainClass := Some("dev.stackverse.http4s.Main"),
    scalacOptions ++= Seq("-deprecation", "-feature", "-Werror"),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.7.0",
      "org.http4s" %% "http4s-ember-server" % "0.23.36",
      "org.http4s" %% "http4s-dsl" % "0.23.36",
      "org.http4s" %% "http4s-circe" % "0.23.36",
      "io.circe" %% "circe-core" % "0.14.16",
      "io.circe" %% "circe-parser" % "0.14.16",
      "com.zaxxer" % "HikariCP" % "7.1.0",
      "org.postgresql" % "postgresql" % "42.7.13",
      "org.flywaydb" % "flyway-core" % "12.11.0",
      "org.flywaydb" % "flyway-database-postgresql" % "12.11.0",
      "com.nimbusds" % "nimbus-jose-jwt" % "10.9.1",
      "io.opentelemetry" % "opentelemetry-api" % "1.63.0",
      "io.opentelemetry" % "opentelemetry-sdk" % "1.63.0",
      "io.opentelemetry" % "opentelemetry-sdk-extension-autoconfigure" % "1.63.0",
      "io.opentelemetry" % "opentelemetry-exporter-otlp" % "1.63.0",
      "org.scalatest" %% "scalatest" % "3.2.20" % Test
    ),
    Test / fork := true,
    scalafmtOnCompile := false
  )
