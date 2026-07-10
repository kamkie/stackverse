package controllers

import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import services.{ApiAction, AuthService}

import javax.inject.{Inject, Singleton}

@Singleton
class IdentityController @Inject() (cc: ControllerComponents, api: ApiAction, auth: AuthService)
    extends AbstractController(cc) {

  def me: Action[AnyContent] = api.authenticated { implicit request =>
    Ok(auth.me(request.caller))
  }
}
