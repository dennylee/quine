package com.thatdot.quine.util

import scala.concurrent.ExecutionContext

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.dispatch.{Dispatchers, MessageDispatcher}

import com.typesafe.scalalogging.LazyLogging

import com.thatdot.quine.util.QuineDispatchers._

abstract class ComputeAndBlockingExecutionContexts {
  def nodeDispatcherEC: ExecutionContext
  def blockingDispatcherEC: ExecutionContext
}

/** Initializes and maintains the canonical reference to each of the dispatchers Quine uses.
  * Similar to pekko-typed's DispatcherSelector
  *
  * See quine-core's `reference.conf` for definitions and documentation of the dispatchers
  *
  * @param system the actorsystem for which the dispatchers will be retrieved
  */
class QuineDispatchers(system: ActorSystem) extends ComputeAndBlockingExecutionContexts with LazyLogging {
  val shardDispatcherEC: MessageDispatcher =
    system.dispatchers.lookup(shardDispatcherName)
  val nodeDispatcherEC: MessageDispatcher =
    system.dispatchers.lookup(nodeDispatcherName)
  val blockingDispatcherEC: MessageDispatcher =
    system.dispatchers.lookup(blockingDispatcherName)
}
object QuineDispatchers {
  val shardDispatcherName = "pekko.quine.graph-shard-dispatcher"
  val nodeDispatcherName = "pekko.quine.node-dispatcher"
  val blockingDispatcherName = "pekko.quine.persistor-blocking-dispatcher"
}
