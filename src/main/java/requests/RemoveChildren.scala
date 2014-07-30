package requests

import actors.behaviors.{Response, Request}
import akka.actor.ActorRef


case class RemoveChildren(children:List[ActorRef]) extends Request
case class RemoveChildrenResponse(request:Request) extends Response(request.messageId)
// @todo: Don't f****ng pass every Request with each answer