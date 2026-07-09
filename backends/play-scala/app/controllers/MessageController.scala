package controllers

import play.api.mvc.{Action, AnyContent}
import services.StackverseActions

import javax.inject.{Inject, Singleton}

@Singleton
class MessageController @Inject() (actions: StackverseActions) {
  def list: Action[AnyContent] = actions.listMessages
  def bundle: Action[AnyContent] = actions.messageBundle
  def get(id: String): Action[AnyContent] = actions.getMessage(id)
  def create: Action[AnyContent] = actions.createMessage
  def update(id: String): Action[AnyContent] = actions.updateMessage(id)
  def delete(id: String): Action[AnyContent] = actions.deleteMessage(id)
}
