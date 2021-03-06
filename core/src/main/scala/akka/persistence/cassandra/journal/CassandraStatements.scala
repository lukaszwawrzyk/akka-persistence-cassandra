/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.cassandra.journal

import akka.Done
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import com.datastax.driver.core.Session

import scala.concurrent.{ ExecutionContext, Future }

trait CassandraStatements {
  private[akka] def config: CassandraJournalConfig

  private[akka] def createKeyspace =
    s"""
      CREATE KEYSPACE IF NOT EXISTS ${config.keyspace}
      WITH REPLICATION = { 'class' : ${config.replicationStrategy} }
    """

  // message is the serialized PersistentRepr that was used in v0.6 and earlier
  // event is the serialized application event that is used in v0.7 and later
  // The event's serialization manifest is stored in ser_manifest and the
  // PersistentRepr.manifest is stored in event_manifest (sorry for naming confusion).
  // PersistentRepr.manifest is used by the event adapters (in akka-persistence).
  // ser_manifest together with ser_id is used for the serialization of the event (payload).
  private[akka] def createTable =
    s"""
      CREATE TABLE IF NOT EXISTS ${tableName} (
        used boolean static,
        persistence_id text,
        partition_nr bigint,
        sequence_nr bigint,
        timestamp timeuuid,
        timebucket text,
        writer_uuid text,
        ser_id int,
        ser_manifest text,
        event_manifest text,
        event blob,
        meta_ser_id int,
        meta_ser_manifest text,
        meta blob,
        message blob,
        tags set<text>,
        PRIMARY KEY ((persistence_id, partition_nr), sequence_nr, timestamp, timebucket))
        WITH gc_grace_seconds =${config.gcGraceSeconds}
        AND compaction = ${config.tableCompactionStrategy.asCQL}
    """

  private[akka] def createTagsTable =
    s"""
      CREATE TABLE IF NOT EXISTS ${tagTableName} (
        tag_name text,
        persistence_id text,
        sequence_nr bigint,
        timebucket bigint,
        timestamp timeuuid,
        tag_pid_sequence_nr bigint,
        writer_uuid text,
        ser_id int,
        ser_manifest text,
        event_manifest text,
        event blob,
        meta_ser_id int,
        meta_ser_manifest text,
        meta blob,
        PRIMARY KEY ((tag_name, timebucket), timestamp, persistence_id, tag_pid_sequence_nr))
        WITH gc_grace_seconds =${config.tagTable.gcGraceSeconds}
        AND compaction = ${config.tagTable.compactionStrategy.asCQL}
        ${if (config.tagTable.ttl.isDefined) "AND default_time_to_live = " + config.tagTable.ttl.get.toSeconds else ""}
    """

  def createTagsProgressTable =
    s"""
       CREATE TABLE IF NOT EXISTS $tagProgressTableName(
        persistence_id text,
        tag text,
        sequence_nr bigint,
        tag_pid_sequence_nr bigint,
        offset timeuuid,
        PRIMARY KEY (persistence_id, tag))
       """

  def createTagScanningTable =
    s"""
       CREATE TABLE IF NOT EXISTS $tagScanningTableName(
        persistence_id text,
        sequence_nr bigint,
        PRIMARY KEY (persistence_id))
       """

  private[akka] def createMetadataTable =
    s"""
      CREATE TABLE IF NOT EXISTS $metadataTableName(
        persistence_id text PRIMARY KEY,
        deleted_to bigint,
        properties map<text,text>)
   """

  private[akka] def writeMessage(withMeta: Boolean) =
    s"""
      INSERT INTO $tableName (persistence_id, partition_nr, sequence_nr, timestamp, timebucket, writer_uuid, ser_id, ser_manifest, event_manifest, event,
        ${if (withMeta) "meta_ser_id, meta_ser_manifest, meta," else ""}
        used, tags)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ${if (withMeta) "?, ?, ?, " else ""} true, ?)
    """

  // could just use the write tags statement if we're going to update all the fields.
  // Fields that are not updated atm: writer_uuid and metadata fields
  private[akka] def updateMessagePayloadAndTags =
    s"""
       UPDATE $tableName
       SET
        event = ?,
        ser_manifest = ?,
        ser_id = ?,
        event_manifest = ?,
        tags = ?
       WHERE
        persistence_id = ? AND
        partition_nr = ? AND
        sequence_nr = ? AND
        timestamp = ? AND
        timebucket = ?
     """

  private[akka] def addTagsToMessagesTable =
    s"""
       UPDATE $tableName
       SET tags = tags + ?
       WHERE
        persistence_id = ? AND
        partition_nr = ? AND
        sequence_nr = ? AND
        timestamp = ? AND
        timebucket = ?
     """

  private[akka] def writeTags(withMeta: Boolean) =
    s"""
       INSERT INTO $tagTableName(
        tag_name,
        timebucket,
        timestamp,
        tag_pid_sequence_nr,
        event,
        event_manifest,
        persistence_id,
        sequence_nr,
        ser_id,
        ser_manifest,
        writer_uuid
        ${if (withMeta) ", meta_ser_id, meta_ser_manifest, meta" else ""}
        ) VALUES (?,?,?,?,?,?,?,?,?,?,
        ${if (withMeta) "?, ?, ?," else ""}
        ?)
     """

  private[akka] def updateMessagePayloadInTagView =
    s"""
       UPDATE $tagTableName
       SET
        event = ?,
        ser_manifest = ?,
        ser_id = ?,
        event_manifest = ?
       WHERE
        tag_name = ? AND
        timebucket = ? AND
        timestamp = ? AND
        persistence_id = ? AND
        tag_pid_sequence_nr = ?
     """

  private[akka] def selectTagPidSequenceNr =
    s"""
       SELECT tag_pid_sequence_nr
       FROM $tagTableName WHERE
       tag_name = ? AND
       timebucket = ? AND
       timestamp = ? AND
       persistence_id = ?
     """.stripMargin

  private[akka] def writeTagProgress =
    s"""
       INSERT INTO $tagProgressTableName(
        persistence_id,
        tag,
        sequence_nr,
        tag_pid_sequence_nr,
        offset) VALUES (?, ?, ?, ?, ?)
     """

  private[akka] def selectTagProgress =
    s"""
       SELECT * from $tagProgressTableName WHERE
       persistence_id = ? AND
       tag = ?
     """

  private[akka] def selectTagProgressForPersistenceId =
    s"""
       SELECT * from $tagProgressTableName WHERE
       persistence_id = ?
     """

  private[akka] def writeTagScanning =
    s"""
       INSERT INTO $tagScanningTableName(
         persistence_id, sequence_nr) VALUES (?, ?)
     """

  private[akka] def selectTagScanningForPersistenceId =
    s"""
       SELECT sequence_nr from $tagScanningTableName WHERE
       persistence_id = ?
     """

  private[akka] def deleteMessage =
    s"""
      DELETE FROM ${tableName} WHERE
        persistence_id = ? AND
        partition_nr = ? AND
        sequence_nr = ?
    """

  private[akka] def deleteMessages = {
    if (config.cassandra2xCompat)
      s"""
      DELETE FROM ${tableName} WHERE
        persistence_id = ? AND
        partition_nr = ? AND
        sequence_nr = ?
    """
    else
      s"""
      DELETE FROM ${tableName} WHERE
        persistence_id = ? AND
        partition_nr = ? AND
        sequence_nr <= ?
    """
  }

  private[akka] def selectMessages =
    s"""
      SELECT * FROM ${tableName} WHERE
        persistence_id = ? AND
        partition_nr = ? AND
        sequence_nr >= ? AND
        sequence_nr <= ?
    """

  private[akka] def selectHighestSequenceNr =
    s"""
     SELECT sequence_nr, used FROM ${tableName} WHERE
       persistence_id = ? AND
       partition_nr = ?
       ORDER BY sequence_nr
       DESC LIMIT 1
   """

  private[akka] def selectDeletedTo =
    s"""
      SELECT deleted_to FROM ${metadataTableName} WHERE
        persistence_id = ?
    """

  private[akka] def insertDeletedTo =
    s"""
      INSERT INTO ${metadataTableName} (persistence_id, deleted_to)
      VALUES ( ?, ? )
    """

  private[akka] def writeInUse =
    s"""
       INSERT INTO ${tableName} (persistence_id, partition_nr, used)
       VALUES(?, ?, true)
     """

  protected def tableName = s"${config.keyspace}.${config.table}"
  private def tagTableName = s"${config.keyspace}.${config.tagTable.name}"
  private def tagProgressTableName = s"${config.keyspace}.tag_write_progress"
  private def tagScanningTableName = s"${config.keyspace}.tag_scanning"
  private def metadataTableName = s"${config.keyspace}.${config.metadataTable}"

  /**
   * Execute creation of keyspace and tables is limited to one thread at a time
   * reduce the risk of (annoying) "Column family ID mismatch" exception
   * when write and read-side plugins are started at the same time.
   * Those statements are retried, because that could happen across different
   * nodes also but serializing those statements gives a better "experience".
   */
  private[akka] def executeCreateKeyspaceAndTables(session: Session, config: CassandraJournalConfig)(implicit ec: ExecutionContext): Future[Done] = {
    import akka.persistence.cassandra.listenableFutureToFuture

    def create(): Future[Done] = {

      def tagStatements: Future[Done] =
        if (config.eventsByTagEnabled) {
          for {
            _ <- session.executeAsync(createTagsTable)
            _ <- session.executeAsync(createTagsProgressTable)
            _ <- session.executeAsync(createTagScanningTable)
          } yield Done
        } else Future.successful(Done)

      val keyspace: Future[Done] =
        if (config.keyspaceAutoCreate) session.executeAsync(createKeyspace).map(_ => Done)
        else Future.successful(Done)

      if (config.tablesAutoCreate) {
        for {
          _ <- keyspace
          _ <- session.executeAsync(createTable)
          _ <- session.executeAsync(createMetadataTable)
          done <- tagStatements
        } yield done
      } else keyspace
    }

    CassandraSession.serializedExecution(
      recur = () => executeCreateKeyspaceAndTables(session, config),
      exec = () => create()
    )
  }
}
