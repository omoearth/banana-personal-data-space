package requests

import actors.supervisors.{Response, Message}

/**
 * Message that contains the names of the event classes
 * which must be sent first in order to use this actor.
 * @param messages A list of case class names
 */
case class UninitializedResponse(messages:List[String]) extends Response
//@todo: Replace list of strings with a list of types