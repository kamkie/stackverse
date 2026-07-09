package controllers

import play.api.mvc.{Action, AnyContent}
import services.StackverseActions

import javax.inject.{Inject, Singleton}

@Singleton
class AdminController @Inject() (actions: StackverseActions) {
  def listUsers: Action[AnyContent] = actions.listUsers
  def getUser(username: String): Action[AnyContent] = actions.getUser(username)
  def setUserStatus(username: String): Action[AnyContent] = actions.setUserStatus(username)
  def auditLog: Action[AnyContent] = actions.auditLog
  def stats: Action[AnyContent] = actions.stats
}
