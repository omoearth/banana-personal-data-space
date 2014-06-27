package actors

import akka.actor.Actor
import messages.Add

class AddActor extends Actor {

  def receive = {
    case msg: Add => (_:String) =>
      println(s"LocalActor received message: '$msg'")
  }
}
