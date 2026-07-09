package controllers

import play.api.mvc.{Action, AnyContent}
import services.StackverseActions

import javax.inject.{Inject, Singleton}

@Singleton
class IdentityController @Inject() (actions: StackverseActions) {
  def me: Action[AnyContent] = actions.me
}
