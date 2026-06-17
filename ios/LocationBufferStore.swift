import Foundation
import SQLite3

/// Append-only SQLite ring buffer for location fixes — the iOS twin of the
/// Android `LocationBufferStore`.
///
/// Every fix is persisted *before* delivery, so a force-quit-then-relaunch (via
/// significant-location-change) never loses the samples captured while the app
/// was dead. A serial queue serializes all access; the raw `sqlite3` C API keeps
/// it dependency-free and fast.
final class LocationBufferStore {
  static let shared = LocationBufferStore()

  private let queue = DispatchQueue(label: "rn.pbl.buffer")
  private var db: OpaquePointer?
  // sqlite3 needs to copy bound text/blobs since our Swift buffers are transient.
  private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

  private init() {
    queue.sync { openAndMigrate() }
  }

  deinit {
    if let db = db { sqlite3_close(db) }
  }

  private func openAndMigrate() {
    let fm = FileManager.default
    guard let dir = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return }
    try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
    let path = dir.appendingPathComponent("rn_persistent_background_location.db").path

    if sqlite3_open(path, &db) != SQLITE_OK {
      // Avoid logging the full sandbox path (minor information disclosure).
      NSLog("\(PBLConstants.logTag) Failed to open the SQLite location buffer.")
      db = nil
      return
    }
    exec("PRAGMA journal_mode=WAL;")
    exec("PRAGMA synchronous=NORMAL;")
    exec(
      """
      CREATE TABLE IF NOT EXISTS fixes (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        ts INTEGER NOT NULL,
        body TEXT NOT NULL
      );
      """
    )
    exec("CREATE INDEX IF NOT EXISTS idx_fixes_ts ON fixes(ts);")
  }

  // MARK: - Public API

  @discardableResult
  func insert(_ fix: LocationFixModel, maxRecords: Int) -> Int64 {
    return queue.sync {
      guard let db = db, let body = jsonString(fix) else { return -1 }
      var stmt: OpaquePointer?
      defer { sqlite3_finalize(stmt) }
      guard sqlite3_prepare_v2(db, "INSERT INTO fixes (ts, body) VALUES (?, ?);", -1, &stmt, nil) == SQLITE_OK else {
        return -1
      }
      sqlite3_bind_int64(stmt, 1, fix.timestamp)
      sqlite3_bind_text(stmt, 2, body, -1, SQLITE_TRANSIENT)
      guard sqlite3_step(stmt) == SQLITE_DONE else { return -1 }
      let rowId = sqlite3_last_insert_rowid(db)
      trimToMaxLocked(maxRecords)
      return rowId
    }
  }

  func count() -> Int {
    return queue.sync {
      guard let db = db else { return 0 }
      var stmt: OpaquePointer?
      defer { sqlite3_finalize(stmt) }
      guard sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM fixes;", -1, &stmt, nil) == SQLITE_OK else { return 0 }
      return sqlite3_step(stmt) == SQLITE_ROW ? Int(sqlite3_column_int(stmt, 0)) : 0
    }
  }

  /// Newest-first read for `getBufferedLocations()`. `limit <= 0` returns all.
  func recent(_ limit: Int) -> [LocationFixModel] {
    return queue.sync {
      var sql = "SELECT id, body FROM fixes ORDER BY id DESC"
      if limit > 0 { sql += " LIMIT \(limit)" }
      sql += ";"
      return read(sql)
    }
  }

  /// Oldest-first FIFO batch for sync, paired with row ids for exact deletion.
  func takeBatch(_ batchSize: Int) -> [(Int64, LocationFixModel)] {
    return queue.sync {
      guard let db = db else { return [] }
      var stmt: OpaquePointer?
      defer { sqlite3_finalize(stmt) }
      guard sqlite3_prepare_v2(db, "SELECT id, body FROM fixes ORDER BY id ASC LIMIT ?;", -1, &stmt, nil) == SQLITE_OK else {
        return []
      }
      sqlite3_bind_int(stmt, 1, Int32(max(1, batchSize)))
      var out: [(Int64, LocationFixModel)] = []
      while sqlite3_step(stmt) == SQLITE_ROW {
        let id = sqlite3_column_int64(stmt, 0)
        if let body = columnText(stmt, 1), let model = parse(body, id: id) {
          out.append((id, model))
        }
      }
      return out
    }
  }

  @discardableResult
  func deleteIds(_ ids: [Int64]) -> Int {
    guard !ids.isEmpty else { return 0 }
    return queue.sync {
      guard let db = db else { return 0 }
      let placeholders = ids.map { _ in "?" }.joined(separator: ",")
      var stmt: OpaquePointer?
      defer { sqlite3_finalize(stmt) }
      guard sqlite3_prepare_v2(db, "DELETE FROM fixes WHERE id IN (\(placeholders));", -1, &stmt, nil) == SQLITE_OK else {
        return 0
      }
      for (i, id) in ids.enumerated() {
        sqlite3_bind_int64(stmt, Int32(i + 1), id)
      }
      guard sqlite3_step(stmt) == SQLITE_DONE else { return 0 }
      return Int(sqlite3_changes(db))
    }
  }

  @discardableResult
  func clear() -> Int {
    return queue.sync {
      guard let db = db else { return 0 }
      let existing = countLocked()
      exec("DELETE FROM fixes;")
      return existing
    }
  }

  // MARK: - Internals (must run on `queue`)

  private func trimToMaxLocked(_ maxRecords: Int) {
    guard maxRecords > 0 else { return }
    exec("DELETE FROM fixes WHERE id NOT IN (SELECT id FROM fixes ORDER BY id DESC LIMIT \(maxRecords));")
  }

  private func countLocked() -> Int {
    guard let db = db else { return 0 }
    var stmt: OpaquePointer?
    defer { sqlite3_finalize(stmt) }
    guard sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM fixes;", -1, &stmt, nil) == SQLITE_OK else { return 0 }
    return sqlite3_step(stmt) == SQLITE_ROW ? Int(sqlite3_column_int(stmt, 0)) : 0
  }

  private func read(_ sql: String) -> [LocationFixModel] {
    guard let db = db else { return [] }
    var stmt: OpaquePointer?
    defer { sqlite3_finalize(stmt) }
    guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
    var out: [LocationFixModel] = []
    while sqlite3_step(stmt) == SQLITE_ROW {
      let id = sqlite3_column_int64(stmt, 0)
      if let body = columnText(stmt, 1), let model = parse(body, id: id) {
        out.append(model)
      }
    }
    return out
  }

  private func exec(_ sql: String) {
    guard let db = db else { return }
    var err: UnsafeMutablePointer<CChar>?
    if sqlite3_exec(db, sql, nil, nil, &err) != SQLITE_OK, let err = err {
      NSLog("\(PBLConstants.logTag) SQLite exec failed: \(String(cString: err))")
      sqlite3_free(err)
    }
  }

  private func columnText(_ stmt: OpaquePointer?, _ index: Int32) -> String? {
    guard let cString = sqlite3_column_text(stmt, index) else { return nil }
    return String(cString: cString)
  }

  private func jsonString(_ fix: LocationFixModel) -> String? {
    guard let data = try? JSONSerialization.data(withJSONObject: fix.toJSONObject(), options: []) else {
      return nil
    }
    return String(data: data, encoding: .utf8)
  }

  private func parse(_ body: String, id: Int64) -> LocationFixModel? {
    guard let data = body.data(using: .utf8),
          let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
      return nil
    }
    return LocationFixModel.fromJSONObject(json, id: String(id))
  }
}
