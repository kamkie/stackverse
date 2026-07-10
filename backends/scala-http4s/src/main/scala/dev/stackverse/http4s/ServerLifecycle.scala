package dev.stackverse.http4s

import cats.effect.{IO, Resource}

trait ServerEvents {
  def started: IO[Unit]
  def stopped: IO[Unit]
  def startupFailed(error: Throwable): IO[Unit]
}

object ServerLifecycle {
  def attach[A](runtime: Resource[IO, A], events: ServerEvents): Resource[IO, A] = {
    def reportStartupFailure[B](error: Throwable): IO[B] =
      events.startupFailed(error).attempt.void *> IO.raiseError(error)

    val acquire = runtime.allocated.attempt.flatMap {
      case Left(error)             => reportStartupFailure(error)
      case Right((value, release)) =>
        events.started.attempt.flatMap {
          case Right(_)    => IO.pure((value, release))
          case Left(error) => release.attempt.void *> reportStartupFailure(error)
        }
    }

    Resource.make(acquire) { case (_, release) => release *> events.stopped }.map(_._1)
  }
}
