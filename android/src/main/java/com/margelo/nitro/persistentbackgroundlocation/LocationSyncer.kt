package com.margelo.nitro.persistentbackgroundlocation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/**
 * Drains the [LocationBufferStore] to a remote endpoint in FIFO batches.
 *
 * Runs entirely natively (no JS, no Play Services) so it keeps working while the
 * app is killed. Rows are deleted only after the server acknowledges them with a
 * 2xx, giving at-least-once delivery semantics. A [Mutex] enforces single-flight
 * so an auto-sync tick and a manual `flush()` never double-send.
 */
internal class LocationSyncer(
  private val store: LocationBufferStore,
  private val debug: Boolean
) {
  private val mutex = Mutex()

  /**
   * Flush as much of the buffer as possible. Stops at the first failed batch (so
   * a flaky network leaves the rest queued) and at [MAX_BATCHES_PER_FLUSH] to
   * bound a single call's duration.
   */
  suspend fun flush(config: LocationConfig): SyncResult = mutex.withLock {
    val url = config.syncUrl
    if (url.isNullOrEmpty()) {
      return@withLock SyncResult(success = false, count = 0, status = null, error = "no-sync-url")
    }

    withContext(Dispatchers.IO) {
      var totalSent = 0
      var batches = 0
      var lastStatus: Int? = null
      while (batches < MAX_BATCHES_PER_FLUSH) {
        val batch = store.takeBatch(config.batchSize)
        if (batch.isEmpty()) break

        val payload = JSONArray().apply {
          batch.forEach { (_, fix) -> put(fix.toJson()) }
        }.toString()

        val result = postBatch(url, config, payload, batch.size)
        if (!result.success) {
          // Leave this batch (and the rest) in the buffer for the next attempt.
          // On a partial flush report `status = null` rather than the failed
          // batch's code, so `success = true` never travels with a 4xx/5xx.
          return@withContext if (totalSent > 0) {
            SyncResult(success = true, count = totalSent, status = lastStatus, error = null)
          } else {
            result
          }
        }

        lastStatus = result.status
        store.deleteIds(batch.map { it.first })
        totalSent += batch.size
        batches++
      }
      SyncResult(success = true, count = totalSent, status = if (totalSent > 0) lastStatus else null, error = null)
    }
  }

  private fun postBatch(
    urlString: String,
    config: LocationConfig,
    payload: String,
    count: Int
  ): SyncResult {
    var connection: HttpURLConnection? = null
    return try {
      connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
        requestMethod = if (config.httpMethod.equals("PUT", ignoreCase = true)) "PUT" else "POST"
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Content-Encoding", "gzip")
        config.headers.forEach { (k, v) -> setRequestProperty(k, v) }
      }

      val bytes = payload.toByteArray(Charsets.UTF_8)
      BufferedOutputStream(connection.outputStream).use { raw ->
        GZIPOutputStream(raw).use { gzip -> gzip.write(bytes) }
      }

      val status = connection.responseCode
      if (status in 200..299) {
        if (debug) Log.d(Constants.TAG, "Synced $count fix(es) → HTTP $status")
        SyncResult(success = true, count = count, status = status, error = null)
      } else {
        val message = runCatching {
          connection.errorStream?.bufferedReader()?.use { it.readText() }
        }.getOrNull()?.take(256)
        if (debug) Log.w(Constants.TAG, "Sync failed → HTTP $status: $message")
        SyncResult(success = false, count = 0, status = status, error = message ?: "http-$status")
      }
    } catch (t: Throwable) {
      if (debug) Log.w(Constants.TAG, "Sync request threw", t)
      SyncResult(success = false, count = 0, status = null, error = t.message ?: t.javaClass.simpleName)
    } finally {
      connection?.disconnect()
    }
  }

  companion object {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_BATCHES_PER_FLUSH = 20
  }
}
