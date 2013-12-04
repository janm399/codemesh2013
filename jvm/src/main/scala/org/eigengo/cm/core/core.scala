package org.eigengo.cm.core

import akka.actor.{ Props, ActorSystem }
import com.rabbitmq.client.ConnectionFactory
import com.github.sstone.amqp.ConnectionOwner

/**
 * Contains configuration for the core
 */
trait CoreConfiguration {

  def amqpConnectionFactory: ConnectionFactory

}

/**
 * Implementation of the ``CoreConfiguration`` that uses the underlying ``system``'s ``settings``.
 */
trait ConfigCoreConfiguration extends CoreConfiguration {
  def system: ActorSystem

  // connection factory
  lazy val amqpConnectionFactory = {
    val amqpHost = system.settings.config.getString("spray-akka.amqp.host")
    val cf = new ConnectionFactory()
    cf.setHost(amqpHost)
    cf
  }

}

/**
 * Contains the functionality of the "headless" part of our app
 */
trait Core {
  this: CoreConfiguration =>

  // start the actor system
  implicit lazy val system = ActorSystem("recog")

  // create a "connection owner" actor, which will try and reconnect automatically if the connection ins lost
  lazy val amqpConnection = system.actorOf(Props(new ConnectionOwner(amqpConnectionFactory)))

  // create the coordinator actor
  lazy val coordinator = system.actorOf(Props(new CoordinatorActor(amqpConnection)), "coordinator")

}
