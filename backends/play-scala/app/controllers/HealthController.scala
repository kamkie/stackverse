package controllers

import play.api.mvc.{Action, AnyContent}
import services.StackverseActions

import javax.inject.{Inject, Singleton}

@Singleton
class HealthController @Inject() (actions: StackverseActions) {
  def healthz: Action[AnyContent] = actions.healthz
  def readyz: Action[AnyContent] = actions.readyz
}
