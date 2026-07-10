package dev.stackverse.http4s

import cats.effect.{IO, Resource}

trait ServerEvents {
  def started: IO[Unit]
  def stopped: IO[Unit]
}

object ServerLifecycle {
  def attach[A](server: Resource[IO, A], events: ServerEvents): Resource[IO, A] =
    Resource
      .make(server.allocated) { case (_, release) => release *> events.stopped }
      .evalTap(_ => events.started)
      .map(_._1)
}
