package dev.stackverse.http4s

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*

final class HealthRoutes(service: HealthOperations, handler: RequestHandler) {
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "healthz" => handler(req)(service.healthz)
    case req @ GET -> Root / "readyz"  => handler(req)(service.readyz)
  }
}
