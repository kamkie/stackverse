package controllers

import play.api.mvc.{Action, AnyContent}
import services.StackverseActions

import javax.inject.{Inject, Singleton}

@Singleton
class ModerationController @Inject() (actions: StackverseActions) {
  def createReport(bookmarkId: String): Action[AnyContent] = actions.createReport(bookmarkId)
  def listOwnReports: Action[AnyContent] = actions.listMyReports
  def updateOwnReport(id: String): Action[AnyContent] = actions.updateMyReport(id)
  def withdrawOwnReport(id: String): Action[AnyContent] = actions.withdrawReport(id)
  def listReports: Action[AnyContent] = actions.listReports
  def resolveReport(id: String): Action[AnyContent] = actions.resolveReport(id)
  def setBookmarkStatus(id: String): Action[AnyContent] = actions.setBookmarkStatus(id)
}
