import play.sbt.PlayImport._

ThisBuild / scalaVersion := "3.8.4"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala)
  .settings(
    name := "stackverse-backend-play-scala",
    organization := "dev.stackverse",
    version := "0.1.0",
    scalacOptions ++= Seq("-deprecation", "-feature", "-Werror"),
    dependencyOverrides ++= Seq(
      "com.fasterxml.jackson.core" % "jackson-core" % "2.22.1",
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.22.1",
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % "2.22.1",
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % "2.22.1",
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % "2.22.1",
      "com.fasterxml.jackson.module" % "jackson-module-parameter-names" % "2.22.1",
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.22.1",
      "org.codehaus.plexus" % "plexus-utils" % "4.0.3"
    ),
    excludeDependencies ++= Seq(
      "io.appium" % "java-client",
      "org.seleniumhq.selenium" % "htmlunit-driver",
      "net.sourceforge.htmlunit" % "htmlunit",
      "net.sourceforge.htmlunit" % "htmlunit-cssparser",
      "org.eclipse.jetty.websocket" % "websocket-client"
    ),
    libraryDependencies ++= Seq(
      guice,
      "com.zaxxer" % "HikariCP" % "7.1.0",
      "org.postgresql" % "postgresql" % "42.7.13",
      "org.flywaydb" % "flyway-core" % "13.2.0",
      "org.flywaydb" % "flyway-database-postgresql" % "13.2.0",
      "com.nimbusds" % "nimbus-jose-jwt" % "10.9.1",
      "io.opentelemetry" % "opentelemetry-api" % "1.65.0",
      "io.opentelemetry" % "opentelemetry-sdk" % "1.65.0",
      "io.opentelemetry" % "opentelemetry-sdk-extension-autoconfigure" % "1.65.0",
      "io.opentelemetry" % "opentelemetry-exporter-otlp" % "1.65.0",
      "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.2" % Test,
      "org.seleniumhq.selenium" % "htmlunit3-driver" % "4.46.0" % Test,
      "org.testcontainers" % "postgresql" % "1.21.4" % Test
    ),
    Test / fork := true,
    scalafmtOnCompile := false
  )
