package com.thatdot.quine.persistor

import java.io.File

import scala.concurrent.duration.FiniteDuration

import org.apache.pekko.stream.Materializer

import com.codahale.metrics.MetricRegistry

import com.thatdot.quine.graph.NamespaceId
import com.thatdot.quine.util.{ComputeAndBlockingExecutionContexts, QuineDispatchers}

abstract class AbstractMapDbPrimePersistor(
  writeAheadLog: Boolean,
  commitInterval: FiniteDuration,
  metricRegistry: MetricRegistry,
  persistenceConfig: PersistenceConfig,
  bloomFilterSize: Option[Long] = None,
  executionContexts: ComputeAndBlockingExecutionContexts
)(implicit materializer: Materializer)
    extends UnifiedPrimePersistor(persistenceConfig, bloomFilterSize) {

  //private val quineDispatchers = new QuineDispatchers(materializer.system)
  private val interval = Option.when(writeAheadLog)(commitInterval)
  def dbForPath(dbPath: MapDbPersistor.DbPath) =
    new MapDbPersistor(
      dbPath,
      null,
      writeAheadLog,
      interval,
      persistenceConfig,
      metricRegistry,
      executionContexts,
      materializer.system.scheduler
    )

}
class TempMapDbPrimePersistor(
  writeAheadLog: Boolean,
  numberPartitions: Int,
  commitInterval: FiniteDuration,
  metricRegistry: MetricRegistry,
  persistenceConfig: PersistenceConfig,
  bloomFilterSize: Option[Long],
  executionContexts: ComputeAndBlockingExecutionContexts
)(implicit materializer: Materializer)
    extends AbstractMapDbPrimePersistor(
      writeAheadLog,
      commitInterval,
      metricRegistry,
      persistenceConfig,
      bloomFilterSize,
      executionContexts
    ) {

  protected def agentCreator(persistenceConfig: PersistenceConfig, namespace: NamespaceId): PersistenceAgent =
    numberPartitions match {
      case 1 => dbForPath(MapDbPersistor.TemporaryDb)
      case n => new ShardedPersistor(Vector.fill(n)(dbForPath(MapDbPersistor.TemporaryDb)), persistenceConfig)
    }
}

class PersistedMapDbPrimePersistor(
  createParentDir: Boolean,
  basePath: File,
  writeAheadLog: Boolean,
  numberPartitions: Int,
  commitInterval: FiniteDuration,
  metricRegistry: MetricRegistry,
  persistenceConfig: PersistenceConfig,
  bloomFilterSize: Option[Long],
  executionContexts: ComputeAndBlockingExecutionContexts
)(implicit materializer: Materializer)
    extends AbstractMapDbPrimePersistor(
      writeAheadLog,
      commitInterval,
      metricRegistry,
      persistenceConfig,
      bloomFilterSize,
      executionContexts
    ) {

  private val parentDir = basePath.getAbsoluteFile.getParentFile

  if (createParentDir)
    if (parentDir.mkdirs())
      logger.warn("Parent directory: {} of requested persistence location did not exist; created.", parentDir)
    else if (!parentDir.isDirectory)
      sys.error(s"$parentDir is not a directory")

  private val namespacesDir = new File(parentDir, "namespaces")

  private def possiblyShardedDb(path: File) = numberPartitions match {
    case 1 => dbForPath(MapDbPersistor.PersistedDb(path))
    case n =>
      val parent = path.getParent
      val fileName = path.getName
      new ShardedPersistor(
        Vector.tabulate(n)(i => dbForPath(MapDbPersistor.PersistedDb(new File(parent, s"part$i.$fileName")))),
        persistenceConfig
      )
  }

  protected def agentCreator(persistenceConfig: PersistenceConfig, namespace: NamespaceId): PersistenceAgent =
    namespace match {
      case Some(name) =>
        val dir = new File(namespacesDir, name.name)
        dir.mkdirs() // the parent dir "namespaces" will be created if it doesn't exist already
        possiblyShardedDb(
          new File(dir, basePath.getName) // Use whatever name was set in config as the name of our mapdb file.
        )
      case None => possiblyShardedDb(basePath)
    }
}
