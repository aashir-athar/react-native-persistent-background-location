import Foundation

/// Mirrors `SyncResult` in the TypeScript layer.
struct SyncResultModel {
  let success: Bool
  let count: Int
  let status: Int?
  let error: String?

  func toDictionary() -> [String: Any?] {
    return ["success": success, "count": count, "status": status, "error": error]
  }

  /// Nitro struct for the JS bridge. Numeric fields are `Double` per the
  /// generated `NativeSyncResult` initializer.
  func toNitro() -> NativeSyncResult {
    return NativeSyncResult(
      success: success,
      count: Double(count),
      status: status.map { Double($0) },
      error: error
    )
  }
}

/// Drains the ``LocationBufferStore`` to a remote endpoint in FIFO batches over
/// `URLSession`. Rows are deleted only after a 2xx, giving at-least-once
/// delivery. A lock enforces single-flight so an auto-sync tick and a manual
/// `flush()` never double-send.
final class LocationSyncer {
  static let shared = LocationSyncer()

  private let queue = DispatchQueue(label: "rn.pbl.sync")
  private let lock = NSLock()
  private var flushing = false
  private let maxBatchesPerFlush = 20

  func flush(_ config: LocationConfig, completion: @escaping (SyncResultModel) -> Void) {
    lock.lock()
    if flushing {
      lock.unlock()
      completion(SyncResultModel(success: false, count: 0, status: nil, error: "busy"))
      return
    }
    flushing = true
    lock.unlock()

    queue.async {
      let result = self.flushSync(config)
      self.lock.lock()
      self.flushing = false
      self.lock.unlock()
      completion(result)
    }
  }

  private func flushSync(_ config: LocationConfig) -> SyncResultModel {
    guard let urlString = config.syncUrl, let url = URL(string: urlString) else {
      return SyncResultModel(success: false, count: 0, status: nil, error: "no-sync-url")
    }

    let store = LocationBufferStore.shared
    var totalSent = 0
    var batches = 0

    while batches < maxBatchesPerFlush {
      let batch = store.takeBatch(config.batchSize)
      if batch.isEmpty { break }

      let payload = batch.map { $0.1.toJSONObject() }
      let res = post(url: url, config: config, payload: payload, count: batch.count)
      if !res.success {
        // Leave this batch (and the rest) queued for the next attempt.
        return totalSent > 0
          ? SyncResultModel(success: true, count: totalSent, status: res.status, error: nil)
          : res
      }

      store.deleteIds(batch.map { $0.0 })
      totalSent += batch.count
      batches += 1
    }

    return SyncResultModel(
      success: true,
      count: totalSent,
      status: totalSent > 0 ? 200 : nil,
      error: nil
    )
  }

  private func post(url: URL, config: LocationConfig, payload: [[String: Any]], count: Int) -> SyncResultModel {
    guard let body = try? JSONSerialization.data(withJSONObject: payload, options: []) else {
      return SyncResultModel(success: false, count: 0, status: nil, error: "encode-failed")
    }

    var request = URLRequest(url: url)
    request.httpMethod = config.httpMethod.uppercased() == "PUT" ? "PUT" : "POST"
    request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
    request.setValue("application/json", forHTTPHeaderField: "Accept")
    for (key, value) in config.headers {
      request.setValue(value, forHTTPHeaderField: key)
    }
    request.httpBody = body
    request.timeoutInterval = 20

    let semaphore = DispatchSemaphore(value: 0)
    var result = SyncResultModel(success: false, count: 0, status: nil, error: "unknown")

    let task = URLSession.shared.dataTask(with: request) { _, response, error in
      defer { semaphore.signal() }
      if let error = error {
        result = SyncResultModel(success: false, count: 0, status: nil, error: error.localizedDescription)
        return
      }
      guard let http = response as? HTTPURLResponse else {
        result = SyncResultModel(success: false, count: 0, status: nil, error: "no-response")
        return
      }
      if (200..<300).contains(http.statusCode) {
        result = SyncResultModel(success: true, count: count, status: http.statusCode, error: nil)
      } else {
        result = SyncResultModel(success: false, count: 0, status: http.statusCode, error: "http-\(http.statusCode)")
      }
    }
    task.resume()
    // Bounded wait so a hung connection can't wedge the sync queue forever.
    _ = semaphore.wait(timeout: .now() + 30)
    return result
  }
}
