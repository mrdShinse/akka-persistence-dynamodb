/**
 * Copyright (C) 2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.dynamodb.journal

import java.util.{ Collections, List => JList, Map => JMap, HashMap => JHMap }
import akka.persistence.{ PersistentRepr, AtomicWrite }
import com.amazonaws.services.dynamodbv2.model._
import scala.collection.JavaConverters._
import scala.collection.{ immutable, mutable }
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal
import akka.Done
import akka.pattern.after

trait DynamoDBRequests {
  this: DynamoDBJournal =>

  import settings._

  /**
   * Write all messages in a sequence of AtomicWrites. Care must be taken to
   * not have concurrent writes happening that touch the highest sequence number.
   * The current implementation is the simplest with this guarantee in that it
   * will run each AtomicWrite in sequence without even batching those that could
   * be batched. The most common case is that there is just one message in total
   * anyway.
   */
  def writeMessages(writes: Seq[AtomicWrite]): Future[List[Try[Unit]]] =
    // optimize the common case
    if (writes.size == 1) {
      writeMessages(writes.head).map(_ :: Nil)(akka.dispatch.ExecutionContexts.sameThreadExecutionContext)
    } else {
      def rec(todo: List[AtomicWrite], acc: List[Try[Unit]]): Future[List[Try[Unit]]] =
        todo match {
          case write :: remainder => writeMessages(write).flatMap(result => rec(remainder, result :: acc))
          case Nil                => Future.successful(acc.reverse)
        }
      rec(writes.toList, Nil)
    }

  /**
   * Write all PersistentRepr in the AtomicWrite provided
   *
   * If there are any errors serializing (preparing the batch writes), then we must return
   * a Future.success(Failure) as the result.  This is needed to be compliant with
   * Akka Persistence 2.4
   *
   * @param atomicWrite Contains a list of persistentRepr that need to be persisted atomically
   * @return a successfully completed Future that contains either a Success or Failure
   */
  def writeMessages(atomicWrite: AtomicWrite): Future[Try[Unit]] =
    // optimize the common case
    if (atomicWrite.size == 1) {
      try {
        val event = toMsgItem(atomicWrite.payload.head)
        if (event.get(Sort).getN == "0") {
          val hs = toHSItem(atomicWrite.persistenceId, atomicWrite.lowestSequenceNr)
          liftUnit(dynamo.batchWriteItem(batchWriteReq(putReq(event) :: putReq(hs) :: Nil)))
        } else
          liftUnit(dynamo.putItem(putItem(event)))
      } catch {
        case NonFatal(ex) =>
          log.error(ex, "Failure during message write preparation: {}", ex.getMessage)
          Future.successful(Failure(ex))
      }
    } else {
      val itemTry = Try { atomicWrite.payload.map(repr => toMsgItem(repr)) }

      itemTry match {
        case Success(items) =>
          // we created our writes successfully, send them off to DynamoDB
          val low = atomicWrite.lowestSequenceNr
          val high = atomicWrite.highestSequenceNr
          val id = atomicWrite.persistenceId
          val size = N(high - low)

          val writes = items.iterator.zipWithIndex.map {
            case (item, index) =>
              item.put(AtomIndex, N(index))
              item.put(AtomEnd, size)
              putReq(item)
          } ++ (if (low / 100 != high / 100) Some(putReq(toHSItem(id, high))) else None)

          val futures = writes.grouped(MaxBatchWrite).map {
            batch =>
              dynamo.batchWriteItem(batchWriteReq(batch)).flatMap(r => sendUnprocessedItems(r))
          }

          // Squash all of the futures into a single result
          trySequence(futures).map(seq => Try(seq.foreach(_.get)))

        case Failure(e) =>
          log.error(e, "Failure during message batch write preparation: {}", e.getMessage)
          val rej = new DynamoDBJournalRejection(s"AtomicWrite rejected as a whole due to ${e.getMessage}", e)
          Future.successful(Failure(rej))
      }
    }

  def deleteMessages(persistenceId: String, start: Long, end: Long): Future[Done] =
    doBatch(
      batch => s"execute batch delete $batch",
      (start to end).map(deleteReq(persistenceId, _))
    )

  def setHS(persistenceId: String, to: Long): Future[PutItemResult] = {
    val put = putItem(toHSItem(persistenceId, to))
    dynamo.putItem(put)
  }

  def removeHS(persistenceId: String): Future[Done] =
    doBatch(
      _ => s"remove highest sequence number entry for $persistenceId",
      (0 until SequenceShards).map(deleteHSItem(persistenceId, _))
    )

  def setLS(persistenceId: String, to: Long): Future[PutItemResult] = {
    val put = putItem(toLSItem(persistenceId, to))
    dynamo.putItem(put)
  }

  def removeLS(persistenceId: String): Future[Done] =
    doBatch(
      _ => s"remove lowest sequence number entry for $persistenceId",
      (0 until SequenceShards).map(deleteLSItem(persistenceId, _))
    )

  /*
   * Request and Item construction helpers.
   */

  /**
   * Converts a sequence of PersistentRepr to a single batch write request
   */
  private def toBatchWriteItemRequest(msgs: Seq[PersistentRepr]): BatchWriteItemRequest = {
    val writes = msgs.foldLeft(new mutable.ArrayBuffer[WriteRequest](msgs.length)) {
      case (ws, repr) =>
        ws += putReq(toMsgItem(repr))
        ws += putReq(toHSItem(repr.persistenceId, repr.sequenceNr))
        ws
    }
    val reqItems = Collections.singletonMap(JournalTable, writes.asJava)
    batchWriteReq(reqItems)
  }

  /**
   * Convert a PersistentRepr into a DynamoDB item, throwing a rejection exception
   * if the MaxItemSize constraint of the database would be violated.
   */
  private def toMsgItem(repr: PersistentRepr): Item = {
    val v = B(serialization.serialize(repr).get)
    val itemSize = keyLength(repr.persistenceId, repr.sequenceNr) + v.getB.remaining
    if (itemSize > MaxItemSize)
      throw new DynamoDBJournalRejection(s"MaxItemSize exceeded: $itemSize > $MaxItemSize")
    val item: Item = messageKey(repr.persistenceId, repr.sequenceNr)
    item.put(Payload, v)
    item
  }

  /**
   * Store the highest sequence number for this persistenceId.
   *
   * Note that this number must be rounded down to the next 100er increment,
   * see the implementation of readSequenceNr for details.
   */
  private def toHSItem(persistenceId: String, sequenceNr: Long): Item = {
    val seq = sequenceNr / 100
    val item: Item = highSeqKey(persistenceId, seq % SequenceShards)
    item.put(SequenceNr, N(seq * 100))
    item
  }

  /**
   * Deleting a highest sequence number entry is done directly by shard number.
   */
  private def deleteHSItem(persistenceId: String, shard: Int): WriteRequest =
    new WriteRequest().withDeleteRequest(new DeleteRequest().withKey(highSeqKey(persistenceId, shard)))

  /**
   * Store the lowest sequence number for this persistenceId. This is only done
   * by DeleteMessagesTo, which the user has to use sensibly (i.e. non-concurrently)
   * and which then stores the real sequence number, not rounded to 100er increments
   * because replay must start exactly here.
   */
  private def toLSItem(persistenceId: String, sequenceNr: Long): Item = {
    val seq = sequenceNr / 100
    val item: Item = lowSeqKey(persistenceId, seq % SequenceShards)
    item.put(SequenceNr, N(sequenceNr))
    item
  }

  /**
   * Deleting a lowest sequence number entry is done directly by shard number.
   */
  private def deleteLSItem(persistenceId: String, shard: Int): WriteRequest =
    new WriteRequest().withDeleteRequest(new DeleteRequest().withKey(lowSeqKey(persistenceId, shard)))

  private def putReq(item: Item): WriteRequest = new WriteRequest().withPutRequest(new PutRequest().withItem(item))

  private def putItem(item: Item): PutItemRequest = new PutItemRequest().withTableName(JournalTable).withItem(item)

  private def deleteReq(persistenceId: String, sequenceNr: Long): WriteRequest =
    new WriteRequest().withDeleteRequest(new DeleteRequest().withKey(messageKey(persistenceId, sequenceNr)))

  private def batchWriteReq(writes: Seq[WriteRequest]): BatchWriteItemRequest =
    batchWriteReq(Collections.singletonMap(JournalTable, writes.asJava))

  private def batchWriteReq(items: JMap[String, JList[WriteRequest]]): BatchWriteItemRequest =
    new BatchWriteItemRequest()
      .withRequestItems(items)
      .withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL)

  /*
   * Request execution helpers.
   */

  /**
   * Execute the given WriteRequests in batches of MaxBatchWrite, ignoring and
   * logging all errors. The returned Future never fails.
   */
  private def doBatch(desc: Seq[WriteRequest] => String, writes: Seq[WriteRequest]): Future[Done] =
    Future.sequence {
      writes
        .grouped(MaxBatchWrite)
        .map { batch =>
          dynamo.batchWriteItem(batchWriteReq(batch))
            .flatMap(sendUnprocessedItems(_))
            .recover {
              case NonFatal(ex) => log.error(ex, "cannot " + desc(batch))
            }
        }
    }.map(_ => Done)

  /**
   * Sends the unprocessed batch write items, and sets the back-off.
   * if no more retries remain (number of back-off retries exhausted), we throw a Runtime exception
   *
   * Note: the DynamoDB client supports automatic retries, however a batch will not fail if some of the items in the
   * batch fail; that is why we need our own back-off mechanism here.  If we exhaust OUR retry logic on top of
   * the retries from the client, then we are hosed and cannot continue; that is why we have a RuntimeException here
   */
  private def sendUnprocessedItems(
    result:           BatchWriteItemResult,
    retriesRemaining: Int                  = 10,
    backoff:          FiniteDuration       = 1.millis
  ): Future[BatchWriteItemResult] = {
    val unprocessed: Int = result.getUnprocessedItems.get(JournalTable) match {
      case null  => 0
      case items => items.size
    }
    if (unprocessed == 0) Future.successful(result)
    else if (retriesRemaining == 0) {
      throw new RuntimeException(s"unable to batch write ${result.getUnprocessedItems.get(JournalTable)} after 10 tries")
    } else {
      val rest = batchWriteReq(result.getUnprocessedItems)
      after(backoff, context.system.scheduler)(dynamo.batchWriteItem(rest).flatMap(r => sendUnprocessedItems(r, retriesRemaining - 1, backoff * 2)))
    }
  }

}
