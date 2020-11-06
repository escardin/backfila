package app.cash.backfila.service.runner.statemachine

import app.cash.backfila.protos.clientservice.GetNextBatchRangeResponse
import app.cash.backfila.protos.clientservice.RunBatchResponse
import app.cash.backfila.service.persistence.BackfillState
import app.cash.backfila.service.persistence.DbEventLog
import app.cash.backfila.service.runner.BackfillRunner
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import misk.hibernate.load
import misk.logging.getLogger
import okio.ByteString

class BatchAwaiter(
  private val backfillRunner: BackfillRunner,
  private val receiveChannel: ReceiveChannel<AwaitingRun>,
  private val rpcBackpressureChannel: ReceiveChannel<Unit>
) {
  private val scannedRateCounter = RateCounter(backfillRunner.factory.clock)
  private val matchingRateCounter = RateCounter(backfillRunner.factory.clock)

  // TODO on shutdown can this wait for all rpcs to finish, with a ~5s time bound?
  fun run(
    scope: CoroutineScope
  ) = scope.launch {
    logger.info { "BatchAwaiter started ${backfillRunner.logLabel()}" }
    main@ while (true) {
      var (batch, runBatchRpc, startedAt) = try {
        receiveChannel.receive()
      } catch (e: CancellationException) {
        logger.info(e) { "BatchAwaiter job cancelled ${backfillRunner.logLabel()}" }
        break
      } catch (e: ClosedReceiveChannelException) {
        logger.info { "No more batches to await, completed ${backfillRunner.logLabel()}" }
        completePartition()
        break
      }

      // Repeat this batch until it succeeds.
      retry@ while (true) {
        val nextRequest: Deferred<RunBatchResponse> = try {
          val response: RunBatchResponse = runBatchRpc.await()

          if (response.exception_stack_trace != null) {
            throw RunBatchException(response.exception_stack_trace)
          }

          if (!backfillRunner.runBatchBackoff.backingOff()) {
            if (response.backoff_ms ?: 0 > 0) {
              backfillRunner.runBatchBackoff.addMillis(response.backoff_ms)
            } else if (backfillRunner.metadata.extraSleepMs > 0) {
              backfillRunner.runBatchBackoff.addMillis(backfillRunner.metadata.extraSleepMs)
            }
          }

          if (response.partial_batch_bookmark != null) {
            // We have a partial batch bookmark, continue the batch.
            backoffAndSendRunBatchAsync(batch, response.partial_batch_bookmark
            ) { "${backfillRunner.logLabel()} continuing partial runbatch for $batch" }
          } else {
            // We got a 200 response on the batch with no error or partial bookmark. This batch is
            // done.
            logger.info { "Runbatch finished for ${backfillRunner.logLabel()} $batch" }

            backfillRunner.onRpcSuccess()

            matchingRateCounter.add(batch.matching_record_count)
            scannedRateCounter.add(batch.scanned_record_count)
            // Track our progress in DB for when another runner takes over.
            // TODO update this less often, probably in the lease updater task
            backfillRunner.factory.transacter.transaction { session ->
              val dbRunPartition = session.load(backfillRunner.partitionId)
              dbRunPartition.pkey_cursor = batch.batch_range.end
              dbRunPartition.backfilled_scanned_record_count += batch.scanned_record_count
              dbRunPartition.backfilled_matching_record_count += batch.matching_record_count
              dbRunPartition.scanned_records_per_minute = scannedRateCounter.projectedRate()
              dbRunPartition.matching_records_per_minute = matchingRateCounter.projectedRate()
            }
            break@retry
          }
        } catch (e: CancellationException) {
          logger.info(e) { "BatchAwaiter job cancelled ${backfillRunner.logLabel()}" }
          break@main
        } catch (e: Exception) {
          logger.info(e) { "Rpc failure when running batch for ${backfillRunner.logLabel()}" }

          backfillRunner.onRpcFailure(
              e,
              "running batch [${batch.batch_range.start.utf8()}, ${batch.batch_range.end.utf8()}]",
              Duration.between(startedAt, backfillRunner.factory.clock.instant())
          )

          backoffAndSendRunBatchAsync(
              batch) { "${backfillRunner.logLabel()} running runbatch retry for $batch" }
        }

        runBatchRpc = nextRequest
        // Reset the started clock for the next request
        startedAt = backfillRunner.factory.clock.instant()
      }

      // Signal to the rpc sender that there is more capacity to send rpcs.
      rpcBackpressureChannel.receive()
    }
  }

  private suspend fun CoroutineScope.backoffAndSendRunBatchAsync(
    batch: GetNextBatchRangeResponse.Batch,
    partialBatchBookmark: ByteString? = null,
    onRunMsg: () -> Any?
  ): Deferred<RunBatchResponse> {
    // After backing off then run the request.
    if (backfillRunner.globalBackoff.backingOff()) {
      val backoffMs = backfillRunner.globalBackoff.backoffMs()
      logger.info {
        "BatchAwaiter ${backfillRunner.logLabel()} backing off for $backoffMs ms"
      }
      delay(backoffMs)
    }
    logger.info(onRunMsg)
    return backfillRunner.runBatchAsync(
        this,
        batch,
        partialBatchBookmark = partialBatchBookmark)
  }

  private fun completePartition() {
    val runComplete = backfillRunner.factory.transacter.transaction { session ->
      val dbRunPartition = session.load(backfillRunner.partitionId)
      dbRunPartition.run_state = BackfillState.COMPLETE

      session.save(DbEventLog(
          backfillRunner.backfillRunId,
          partition_id = dbRunPartition.id,
          type = DbEventLog.Type.STATE_CHANGE,
          message = "partition completed"
      ))

      // If all states are COMPLETE the whole backfill will be completed.
      // If multiple partitions finish at the same time they will retry due to the hibernate
      // version mismatch on the DbBackfillRun.
      val partitions = dbRunPartition.backfill_run.partitions(session,
          backfillRunner.factory.queryFactory)
      if (partitions.all { it.run_state == BackfillState.COMPLETE }) {
        dbRunPartition.backfill_run.complete()
        logger.info { "Backfill ${backfillRunner.backfillName} completed" }

        session.save(DbEventLog(
            backfillRunner.backfillRunId,
            type = DbEventLog.Type.STATE_CHANGE,
            message = "backfill completed"
        ))

        return@transaction true
      }
      false
    }

    if (runComplete) {
      backfillRunner.factory.slackHelper.runCompleted(backfillRunner.backfillRunId)
    }
  }

  companion object {
    private val logger = getLogger<BatchAwaiter>()
  }
}
