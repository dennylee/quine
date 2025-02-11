package com.thatdot.quine.graph

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.locks.StampedLock

import scala.collection.compat._
import scala.collection.mutable
import scala.compat.ExecutionContexts
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import org.apache.pekko.actor.{Actor, ActorLogging}
import org.apache.pekko.stream.scaladsl.Keep

import cats.data.NonEmptyList
import cats.implicits._
import org.apache.pekko

import com.thatdot.quine.graph.AbstractNodeActor.internallyDeduplicatePropertyEvents
import com.thatdot.quine.graph.NodeEvent.WithTime
import com.thatdot.quine.graph.PropertyEvent.{PropertyRemoved, PropertySet}
import com.thatdot.quine.graph.behavior.DomainNodeIndexBehavior.{NodeParentIndex, SubscribersToThisNodeUtil}
import com.thatdot.quine.graph.behavior.{
  ActorClock,
  AlgorithmBehavior,
  CypherBehavior,
  DomainNodeIndexBehavior,
  GoToSleepBehavior,
  LiteralCommandBehavior,
  MultipleValuesStandingQueryBehavior,
  MultipleValuesStandingQuerySubscribers,
  PriorityStashingBehavior,
  QuinePatternQueryBehavior
}
import com.thatdot.quine.graph.edges.{EdgeProcessor, MemoryFirstEdgeProcessor, PersistorFirstEdgeProcessor}
import com.thatdot.quine.graph.messaging.BaseMessage.Done
import com.thatdot.quine.graph.messaging.LiteralMessage.{
  DgnLocalEventIndexSummary,
  LocallyRegisteredStandingQuery,
  NodeInternalState,
  SqStateResult,
  SqStateResults
}
import com.thatdot.quine.graph.messaging.{BaseMessage, QuineIdOps, QuineRefOps, SpaceTimeQuineId}
import com.thatdot.quine.model.DomainGraphNode.DomainGraphNodeId
import com.thatdot.quine.model.{HalfEdge, Milliseconds, PropertyValue, QuineId, QuineIdProvider}
import com.thatdot.quine.persistor.{EventEffectOrder, NamespacedPersistenceAgent, PersistenceConfig}
import com.thatdot.quine.util.ByteConversions

/** The fundamental graph unit for both data storage (eg [[properties]]) and
  * computation (as a Pekko actor).
  * At most one [[AbstractNodeActor]] exists in the actor system ([[graph.system]]) per node per moment in
  * time (see [[qidAtTime]]).
  *
  * [[AbstractNodeActor]] is the default place to define implementation of interfaces exposed by [[BaseNodeActor]] and
  * [[BaseNodeActorView]]. Classes extending [[AbstractNodeActor]] (e.g., [[NodeActor]]) should be kept as lightweight
  * as possible, ideally including only construction-time logic and an [[Actor.receive]] implementation.
  *
  * @param qidAtTime    the ID that comprises this node's notion of nominal identity -- analogous to pekko's ActorRef
  * @param graph        a reference to the graph in which this node exists
  * @param costToSleep  see [[CostToSleep]]
  * @param wakefulState an atomic reference used like a variable to track the current lifecycle state of this node.
  *                     This is (and may be expected to be) threadsafe, so that [[GraphShardActor]]s can access it
  * @param actorRefLock a lock on this node's [[ActorRef]] used to hard-stop messages when sleeping the node (relayTell uses
  *                     tryReadLock during its tell, so if a write lock is held for a node's actor, no messages can be
  *                     sent to it)
  */
abstract private[graph] class AbstractNodeActor(
  val qidAtTime: SpaceTimeQuineId,
  val graph: StandingQueryOpsGraph with CypherOpsGraph,
  costToSleep: CostToSleep,
  protected val wakefulState: AtomicReference[WakefulState],
  protected val actorRefLock: StampedLock,
  protected var properties: Map[Symbol, PropertyValue],
  initialEdges: Iterable[HalfEdge],
  initialDomainGraphSubscribers: mutable.Map[
    DomainGraphNodeId,
    SubscribersToThisNodeUtil.DistinctIdSubscription
  ],
  protected val domainNodeIndex: DomainNodeIndexBehavior.DomainNodeIndex,
  protected val multipleValuesStandingQueries: NodeActor.MultipleValuesStandingQueries
) extends Actor
    with ActorLogging
    with BaseNodeActor
    with QuineRefOps
    with QuineIdOps
    with LiteralCommandBehavior
    with AlgorithmBehavior
    with DomainNodeIndexBehavior
    with GoToSleepBehavior
    with PriorityStashingBehavior
    with CypherBehavior
    with MultipleValuesStandingQueryBehavior
    with QuinePatternQueryBehavior
    with ActorClock {
  val qid: QuineId = qidAtTime.id
  val namespace: NamespaceId = qidAtTime.namespace
  val atTime: Option[Milliseconds] = qidAtTime.atTime
  implicit val idProvider: QuineIdProvider = graph.idProvider
  protected val persistor: NamespacedPersistenceAgent = graph.namespacePersistor(namespace).get // or throw!
  protected val persistenceConfig: PersistenceConfig = persistor.persistenceConfig
  protected val metrics: HostQuineMetrics = graph.metrics

  /** Utility for inheritors to choose a default EdgeProcessor. Accounts for configuration, and returns an edge
    * processor appropriate for arbitrary usage by this node, and only this node
    */
  protected[this] def defaultSynchronousEdgeProcessor: EdgeProcessor = {
    val edgeCollection = graph.edgeCollectionFactory(qid)
    initialEdges.foreach(edgeCollection.addEdge)
    val persistEventsToJournal: NonEmptyList[WithTime[EdgeEvent]] => Future[Unit] =
      if (persistor.persistenceConfig.journalEnabled)
        events => metrics.persistorPersistEventTimer.time(persistor.persistNodeChangeEvents(qid, events))
      else
        _ => Future.unit

    graph.effectOrder match {
      case EventEffectOrder.PersistorFirst =>
        new PersistorFirstEdgeProcessor(
          edges = edgeCollection,
          persistToJournal = persistEventsToJournal,
          pauseMessageProcessingUntil = pauseMessageProcessingUntil,
          updateSnapshotTimestamp = () => updateLasttWriteAfterSnapshot(),
          runPostActions = runPostActions,
          qid = qid,
          costToSleep = costToSleep,
          nodeEdgesCounter = metrics.nodeEdgesCounter(namespace)
        )
      case EventEffectOrder.MemoryFirst =>
        new MemoryFirstEdgeProcessor(
          edges = edgeCollection,
          persistToJournal = persistEventsToJournal,
          updateSnapshotTimestamp = () => updateLasttWriteAfterSnapshot(),
          runPostActions = runPostActions,
          qid = qid,
          costToSleep = costToSleep,
          nodeEdgesCounter = metrics.nodeEdgesCounter(namespace)
        )(graph.system, idProvider)
    }
  }

  protected val dgnRegistry: DomainGraphNodeRegistry = graph.dgnRegistry
  protected val domainGraphSubscribers: SubscribersToThisNode = SubscribersToThisNode(initialDomainGraphSubscribers)

  protected var latestUpdateAfterSnapshot: Option[EventTime] = None
  protected var lastWriteMillis: Long = 0

  protected def updateRelevantToSnapshotOccurred(): Unit = {
    if (atTime.nonEmpty) {
      log.warning("Attempted to flag a historical node as being updated -- this update will not be persisted.")
    }
    // TODO: should this update `lastWriteMillis` too?
    latestUpdateAfterSnapshot = Some(peekEventSequence())
  }

  /** @see [[StandingQueryLocalEventIndex]]
    */
  protected var localEventIndex: StandingQueryLocalEventIndex =
    // NB this initialization is non-authoritative: only after journal restoration is complete can this be
    // comprehensively reconstructed (see the block below the definition of [[nodeParentIndex]]). However, journal
    // restoration may access [[localEventIndex]] and/or [[nodeParentIndex]] so they must be at least initialized
    StandingQueryLocalEventIndex
      .from(
        dgnRegistry,
        domainGraphSubscribers.subscribersToThisNode.keysIterator,
        multipleValuesStandingQueries.iterator.map { case (sqIdAndPartId, (_, state)) => sqIdAndPartId -> state }
      )
      ._1 // take the index, ignoring the record of which DGNs no longer exist (addressed in the aforementioned block)

  /** @see [[NodeParentIndex]]
    */
  protected var domainGraphNodeParentIndex: NodeParentIndex =
    // NB this initialization is non-authoritative: only after journal restoration is complete can this be
    // comprehensively reconstructed (see the block below the definition of [[nodeParentIndex]]). However, journal
    // restoration may access [[localEventIndex]] and/or [[nodeParentIndex]] so they must be at least initialized
    NodeParentIndex
      .reconstruct(domainNodeIndex, domainGraphSubscribers.subscribersToThisNode.keys, dgnRegistry)
      ._1 // take the index, ignoring the record of which DGNs no longer exist (addressed in the aforementioned block)

  /** Synchronizes this node's operating standing queries with those currently active on the thoroughgoing graph.
    * After a node is woken and restored to the state it was in before sleeping, it may need to catch up on new/deleted
    * standing queries which changed while it was asleep. This function catches the node up to the current collection
    * of live standing queries. If called from a historical node, this function is a no-op.
    * - Registers and emits initial results for any standing queries not yet registered on this node
    * - Removes any standing queries defined on this node but no longer known to the graph
    */
  protected def syncStandingQueries(): Unit =
    if (atTime.isEmpty) {
      updateDistinctIdStandingQueriesOnNode(shouldSendReplies = true)
      updateMultipleValuesStandingQueriesOnNode()
    }

  protected def propertyEventHasEffect(event: PropertyEvent): Boolean = event match {
    case PropertySet(key, value) => !properties.get(key).contains(value)
    case PropertyRemoved(key, _) => properties.contains(key)
  }

  /** Enforces processEvents invariants before delegating to `onEffecting` (see block comment in [[BaseNodeActor]]
    * @param hasEffectPredicate A function that, given an event, returns true if and only if the event would change the
    *                           state of the node
    * @param events             The events to apply to this node, in the order they should be applied
    * @param atTimeOverride     Supply a number if you wish to override the number produced by the node's actor clock,
    *                           recorded as the timestamp of the event when writing to the journal.
    * @param onEffecting        The effect to be run -- this will be provided the final, deduplicated list of events to
    *                           apply, in order. The events represent the minimal set of events that will change node
    *                           state in a way equivalent to if all of the original `events` were applied.
    */
  protected[this] def guardEvents[E <: NodeChangeEvent](
    hasEffectPredicate: E => Boolean,
    events: List[E],
    atTimeOverride: Option[EventTime],
    onEffecting: NonEmptyList[NodeEvent.WithTime[E]] => Future[Done.type]
  ): Future[Done.type] = {
    val produceEventTime = atTimeOverride.fold(() => tickEventSequence())(() => _)
    refuseHistoricalUpdates(events)(
      NonEmptyList.fromList(events.filter(hasEffectPredicate)) match {
        case Some(effectfulEvents) => onEffecting(effectfulEvents.map(e => NodeEvent.WithTime(e, produceEventTime())))
        case None => Future.successful(Done)
      }
    )
  }

  // This is marked private and wrapped with two separate callable methods that either allow a collection or allow passing a custom `atTime`, but not both.
  private[this] def propertyEvents(events: List[PropertyEvent], atTime: Option[EventTime]): Future[Done.type] =
    guardEvents[PropertyEvent](
      propertyEventHasEffect,
      events,
      atTime,
      persistAndApplyEventsEffectsInMemory[PropertyEvent](
        _,
        persistor.persistNodeChangeEvents(qid, _),
        events => events.toList.foreach(applyPropertyEffect)
      )
    )

  protected def processPropertyEvent(
    event: PropertyEvent,
    atTimeOverride: Option[EventTime] = None
  ): Future[Done.type] = propertyEvents(event :: Nil, atTimeOverride)

  protected def processPropertyEvents(events: List[PropertyEvent]): Future[Done.type] =
    propertyEvents(internallyDeduplicatePropertyEvents(events), None)

  protected[this] def edgeEvents(events: List[EdgeEvent], atTime: Option[EventTime]): Future[Done.type] =
    refuseHistoricalUpdates(events)(
      edges.processEdgeEvents(events, atTime.fold(() => tickEventSequence())(() => _))
    ).map(_ => Done)(ExecutionContexts.parasitic)

  protected def processEdgeEvents(
    events: List[EdgeEvent]
  ): Future[Done.type] =
    edgeEvents(events, None)
      .flatMap(_ =>
        Future.traverse[HalfEdge => Unit, Unit, Iterable](edgePatterns.values)(f =>
          Future.apply(events.foreach(e => f(e.edge)))(ExecutionContexts.parasitic)
        )(implicitly, ExecutionContexts.parasitic)
      )(ExecutionContexts.parasitic)
      .map(_ => BaseMessage.Done)(ExecutionContexts.parasitic)

  protected def processEdgeEvent(
    event: EdgeEvent,
    atTimeOverride: Option[EventTime]
  ): Future[Done.type] = edgeEvents(event :: Nil, atTimeOverride)

  /** This is just an assertion to guard against programmer error.
    * @param events Just for the [[IllegalHistoricalUpdate]] error returned, which doesn't even use it in its message?
    *               Maybe it should be passed-through as an arg to [[action]], so callers don't have to specify it
    *               twice?
    * @param action The action to run if this is indeed not a hisorical node.
    * @tparam A
    * @return
    */
  def refuseHistoricalUpdates[A](events: Seq[NodeEvent])(action: => Future[A]): Future[A] =
    atTime.fold(action)(historicalTime => Future.failed(IllegalHistoricalUpdate(events, qid, historicalTime)))

  protected def processDomainIndexEvent(
    event: DomainIndexEvent
  ): Future[Done.type] =
    refuseHistoricalUpdates(event :: Nil)(
      persistAndApplyEventsEffectsInMemory[DomainIndexEvent](
        NonEmptyList.one(NodeEvent.WithTime(event, tickEventSequence())),
        persistor.persistDomainIndexEvents(qid, _),
        // We know there is only one event here, because we're only passing one above.
        // So just calling .head works as well as .foreach
        events => applyDomainIndexEffect(events.head, shouldCauseSideEffects = true)
      )
    )

  protected def persistAndApplyEventsEffectsInMemory[A <: NodeEvent](
    effectingEvents: NonEmptyList[NodeEvent.WithTime[A]],
    persistEvents: NonEmptyList[WithTime[A]] => Future[Unit],
    applyEventsEffectsInMemory: NonEmptyList[A] => Unit
  ): Future[Done.type] = {
    val persistAttempts = new AtomicInteger(1)
    def persistEventsToJournal(): Future[Unit] =
      if (persistenceConfig.journalEnabled) {
        metrics.persistorPersistEventTimer
          .time(persistEvents(effectingEvents))
          .transform(
            _ =>
              // TODO: add a metric to count `persistAttempts`
              (),
            (e: Throwable) => {
              val attemptCount = persistAttempts.getAndIncrement()
              log.info(
                s"Retrying persistence from node: ${qid.pretty} with events: $effectingEvents after: " +
                s"$attemptCount attempts, with error: $e"
              )
              e
            }
          )(cypherEc)
      } else Future.unit

    graph.effectOrder match {
      case EventEffectOrder.MemoryFirst =>
        val events = effectingEvents.map(_.event)
        applyEventsEffectsInMemory(events)
        notifyNodeUpdate(events collect { case e: NodeChangeEvent => e })
        pekko.pattern
          .retry(
            () => persistEventsToJournal(),
            Int.MaxValue,
            1.millisecond,
            10.seconds,
            randomFactor = 0.1d
          )(cypherEc, context.system.scheduler)
          .map(_ => Done)(ExecutionContexts.parasitic)
      case EventEffectOrder.PersistorFirst =>
        pauseMessageProcessingUntil[Unit](
          persistEventsToJournal(),
          {
            case Success(_) =>
              // Executed by this actor (which is not slept), in order before any other messages are processed.
              val events = effectingEvents.map(_.event)
              applyEventsEffectsInMemory(events)
              notifyNodeUpdate(events collect { case e: NodeChangeEvent => e })
            case Failure(e) =>
              log.info(
                s"Persistor error occurred when writing events to journal on node: ${qid.pretty} Will not apply " +
                s"events: $effectingEvents to in-memory state. Returning failed result. Error: $e"
              )
          }
        ).map(_ => Done)(ExecutionContexts.parasitic)
    }

  }

  private[this] def persistSnapshot(): Unit = if (atTime.isEmpty) {
    val occurredAt: EventTime = tickEventSequence()
    val snapshot = toSnapshotBytes(occurredAt)
    metrics.snapshotSize.update(snapshot.length)

    def persistSnapshot(): Future[Unit] =
      metrics.persistorPersistSnapshotTimer
        .time(
          persistor.persistSnapshot(
            qid,
            if (persistenceConfig.snapshotSingleton) EventTime.MaxValue else occurredAt,
            snapshot
          )
        )

    def infinitePersisting(logFunc: String => Unit, f: => Future[Unit]): Future[Unit] =
      f.recoverWith { case NonFatal(e) =>
        logFunc(s"Persisting snapshot for: $occurredAt is being retried after the error: $e")
        infinitePersisting(logFunc, f)
      }(cypherEc)

    graph.effectOrder match {
      case EventEffectOrder.MemoryFirst =>
        infinitePersisting(log.info, persistSnapshot())
      case EventEffectOrder.PersistorFirst =>
        // There's nothing sane to do if this fails; there's no query result to fail. Just retry forever and deadlock.
        // The important intention here is to disallow any subsequent message (e.g. query) until the persist succeeds,
        // and to disallow `runPostActions` until persistence succeeds.
        val _ = pauseMessageProcessingUntil[Unit](infinitePersisting(log.warning, persistSnapshot()), _ => ())
    }
    latestUpdateAfterSnapshot = None
  } else {
    log.debug("persistSnapshot called on historical node: This indicates programmer error.")
  }

  /** The folling two methods apply effects of the provided events to the node state.
    * For [[PropertyEvent]], and [[DomainIndexEvent]], respectively
    * @param event                 thee event to apply
    */

  protected[this] def applyPropertyEffect(event: PropertyEvent): Unit = event match {
    case PropertySet(key, value) =>
      metrics.nodePropertyCounter(namespace).increment(previousCount = properties.size)
      properties = properties + (key -> value)
      selfPatterns.foreach(p => p._2())
    case PropertyRemoved(key, _) =>
      metrics.nodePropertyCounter(namespace).decrement(previousCount = properties.size)
      properties = properties - key
  }

  /** Apply a [[DomainIndexEvent]] to the node state
    * @param event the event to apply
    * @param shouldCauseSideEffects whether the application of this event should cause off-node side effects, such
    *                               as Standing Query results. This value should be false when restoring
    *                               events from a journal.
    */
  protected[this] def applyDomainIndexEffect(event: DomainIndexEvent, shouldCauseSideEffects: Boolean): Unit = {
    import DomainIndexEvent._
    event match {
      case CreateDomainNodeSubscription(dgnId, nodeId, forQuery) =>
        receiveDomainNodeSubscription(Left(nodeId), dgnId, forQuery, shouldSendReplies = shouldCauseSideEffects)

      case CreateDomainStandingQuerySubscription(dgnId, sqId, forQuery) =>
        receiveDomainNodeSubscription(Right(sqId), dgnId, forQuery, shouldSendReplies = shouldCauseSideEffects)

      case DomainNodeSubscriptionResult(from, dgnId, result) =>
        receiveIndexUpdate(from, dgnId, result, shouldSendReplies = shouldCauseSideEffects)

      case CancelDomainNodeSubscription(dgnId, fromSubscriber) =>
        cancelSubscription(dgnId, Some(Left(fromSubscriber)), shouldSendReplies = shouldCauseSideEffects)

    }
  }

  protected[this] def updateLasttWriteAfterSnapshot(): Unit = {
    latestUpdateAfterSnapshot = Some(peekEventSequence())
    lastWriteMillis = previousMessageMillis()
    if (persistenceConfig.snapshotOnUpdate) persistSnapshot()
  }

  /** Call this if effects were applied to the node state (it was modified)
    * to update the "last update" timestamp, save a snapshot (if configured to),
    * and notify any subscribers of the applied [[NodeChangeEvent]]s
    * @param events
    */
  protected[this] def notifyNodeUpdate(events: List[NodeChangeEvent]): Unit = {
    updateLasttWriteAfterSnapshot()
    runPostActions(events)
  }

  /** Hook for registering some arbitrary action after processing a node event. Right now, all this
    * does is advance standing queries
    *
    * @param events ordered sequence of node events produced from a single message.
    */
  protected[this] def runPostActions(events: List[NodeChangeEvent]): Unit = {

    var eventsForMvsqs: Map[StandingQueryLocalEventIndex.StandingQueryWithId, Seq[NodeChangeEvent]] = Map.empty

    events.foreach { event =>
      localEventIndex.standingQueriesWatchingNodeEvent(
        event,
        {
          case cypherSubscriber: StandingQueryLocalEventIndex.StandingQueryWithId =>
            eventsForMvsqs += cypherSubscriber -> (event +: eventsForMvsqs.getOrElse(cypherSubscriber, Seq.empty))
            false
          case StandingQueryLocalEventIndex.DomainNodeIndexSubscription(dgnId) =>
            dgnRegistry.getIdentifiedDomainGraphNode(dgnId) match {
              case Some(dgn) =>
                // ensure that this node is subscribed to all other necessary nodes to continue processing the DGN
                ensureSubscriptionToDomainEdges(
                  dgn,
                  domainGraphSubscribers.getRelatedQueries(dgnId),
                  shouldSendReplies = true
                )
                // inform all subscribers to this node about any relevant changes caused by the recent event
                domainGraphSubscribers.updateAnswerAndNotifySubscribers(dgn, shouldSendReplies = true)
                false
              case None =>
                true // true returned to standingQueriesWatchingNodeEvent indicates record should be removed
            }
        }
      )
    }
    eventsForMvsqs.foreach { case (sq, events) =>
      updateMultipleValuesSqs(events, sq)
    }
  }

  /** Serialize node state into a binary node snapshot
    *
    * @note returning just bytes instead of [[NodeSnapshot]] means that we don't need to worry
    * about accidentally leaking references to (potentially thread-unsafe) internal actor state
    *
    * @return Snapshot bytes, as managed by [[SnapshotCodec]]
    */
  def toSnapshotBytes(time: EventTime): Array[Byte] = {
    latestUpdateAfterSnapshot = None // TODO: reconsider what to do if saving the snapshot fails!
    NodeSnapshot.snapshotCodec.format.write(
      NodeSnapshot(
        time,
        properties,
        edges.toSerialize,
        domainGraphSubscribers.subscribersToThisNode,
        domainNodeIndex.index
      )
    )
  }

  def debugNodeInternalState(): Future[NodeInternalState] = {
    // Return a string that (if possible) shows the deserialized representation
    def propertyValue2String(propertyValue: PropertyValue): String =
      propertyValue.deserialized.fold(
        _ => ByteConversions.formatHexBinary(propertyValue.serialized),
        _.toString
      )

    val subscribersStrings = domainGraphSubscribers.subscribersToThisNode.toList
      .map { case (a, c) =>
        a -> c.subscribers.map {
          case Left(q) => q.pretty
          case Right(x) => x
        } -> c.lastNotification -> c.relatedQueries
      }
      .map(_.toString)

    val domainNodeIndexStrings = domainNodeIndex.index.toList
      .map(t => t._1.pretty -> t._2.map { case (a, c) => a -> c })
      .map(_.toString)

    val dgnLocalEventIndexSummary = {
      val propsIdx = localEventIndex.watchingForProperty.toMap.map { case (propertyName, notifiables) =>
        propertyName.name -> notifiables.toList.collect {
          case StandingQueryLocalEventIndex.DomainNodeIndexSubscription(dgnId) =>
            dgnId
        }
      }
      val edgesIdx = localEventIndex.watchingForEdge.toMap.map { case (edgeLabel, notifiables) =>
        edgeLabel.name -> notifiables.toList.collect {
          case StandingQueryLocalEventIndex.DomainNodeIndexSubscription(dgnId) =>
            dgnId
        }
      }
      val anyEdgesIdx = localEventIndex.watchingForAnyEdge.collect {
        case StandingQueryLocalEventIndex.DomainNodeIndexSubscription(dgnId) =>
          dgnId
      }

      DgnLocalEventIndexSummary(
        propsIdx,
        edgesIdx,
        anyEdgesIdx.toList
      )
    }

    persistor
      .getJournalWithTime(
        qid,
        startingAt = EventTime.MinValue,
        endingAt =
          atTime.map(EventTime.fromMillis).map(_.largestEventTimeInThisMillisecond).getOrElse(EventTime.MaxValue),
        includeDomainIndexEvents = false
      )
      .recover { case err =>
        log.error(err, "failed to get journal for node: {}", qidAtTime.debug)
        Iterable.empty
      }(context.dispatcher)
      .map { journal =>
        NodeInternalState(
          atTime,
          properties.fmap(propertyValue2String),
          edges.toSet,
          latestUpdateAfterSnapshot,
          subscribersStrings,
          domainNodeIndexStrings,
          getSqState(),
          dgnLocalEventIndexSummary,
          multipleValuesStandingQueries.view.map {
            case ((globalId, sqId), (MultipleValuesStandingQuerySubscribers(_, _, subs), st)) =>
              LocallyRegisteredStandingQuery(
                sqId.toString,
                globalId.toString,
                subs.map(_.toString).toSet,
                st.toString
              )
          }.toVector,
          journal.toSet,
          getNodeHashCode().value
        )
      }(context.dispatcher)
  }

  def getNodeHashCode(): GraphNodeHashCode =
    GraphNodeHashCode(qid, properties, edges.toSet)

  def getSqState(): SqStateResults =
    SqStateResults(
      domainGraphSubscribers.subscribersToThisNode.toList.flatMap { case (dgnId, subs) =>
        subs.subscribers.toList.collect { case Left(q) => // filters out receivers outside the graph
          SqStateResult(dgnId, q, subs.lastNotification)
        }
      },
      domainNodeIndex.index.toList.flatMap { case (q, m) =>
        m.toList.map { case (dgnId, lastN) =>
          SqStateResult(dgnId, q, lastN)
        }
      }
    )
}

object AbstractNodeActor {
  private[graph] def internallyDeduplicatePropertyEvents(events: List[PropertyEvent]): List[PropertyEvent] =
    // Use only the last event for each property key. This form of "internal deduplication" is only applied to
    // a) batches of b) property events.
    events
      .groupMapReduce(_.key)(identity)(Keep.right)
      .values
      .toList

}
