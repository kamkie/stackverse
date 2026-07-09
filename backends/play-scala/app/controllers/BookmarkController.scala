package controllers

import play.api.mvc.{Action, AnyContent}
import services.StackverseActions

import javax.inject.{Inject, Singleton}

@Singleton
class BookmarkController @Inject() (actions: StackverseActions) {
  def listV1: Action[AnyContent] = actions.listBookmarksV1
  def listV2: Action[AnyContent] = actions.listBookmarksV2
  def create: Action[AnyContent] = actions.createBookmark
  def get(id: String): Action[AnyContent] = actions.getBookmark(id)
  def update(id: String): Action[AnyContent] = actions.updateBookmark(id)
  def delete(id: String): Action[AnyContent] = actions.deleteBookmark(id)
  def tags: Action[AnyContent] = actions.listTags
}
