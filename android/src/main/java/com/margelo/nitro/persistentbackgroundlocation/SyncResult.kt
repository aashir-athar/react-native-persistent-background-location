package com.margelo.nitro.persistentbackgroundlocation

/** Mirrors `SyncResult` in the TypeScript layer. */
internal data class SyncResult(
  val success: Boolean,
  val count: Int,
  val status: Int?,
  val error: String?
) {
  /** Bridge payload as the generated Nitro struct (nullable status/error). */
  fun toNitro(): NativeSyncResult = NativeSyncResult(
    success = success,
    count = count.toDouble(),
    status = status?.toDouble(),
    error = error
  )
}
