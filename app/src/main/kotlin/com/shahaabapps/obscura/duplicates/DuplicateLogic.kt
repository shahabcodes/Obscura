package com.shahaabapps.obscura.duplicates

import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.VaultFile
import kotlin.math.abs

enum class DuplicateKind(val label: String) {
    EXACT("Exact copies"),
    LOOK_ALIKE("Look-alikes"),
    BURST("Bursts"),
}

/** Files that are the same or nearly the same. [best] is the copy suggested to keep. */
data class DuplicateGroup(val kind: DuplicateKind, val files: List<VaultFile>, val best: VaultFile) {
    val extras: List<VaultFile> get() = files.filter { it.id != best.id }
    val reclaimableBytes: Long get() = extras.sumOf { it.sizeBytes }
}

/**
 * Pure duplicate detection, free of Android so it can be unit-tested:
 * - exact copies share a SHA-256 of their contents (any file type);
 * - look-alikes are photos whose visual fingerprints differ in at most [LOOK_ALIKE_BITS] bits
 *   (re-saved, resized or lightly edited copies);
 * - bursts are photos taken within [BURST_WINDOW_MS] of each other that are still fairly similar.
 */
object DuplicateLogic {
    const val LOOK_ALIKE_BITS = 6
    const val BURST_BITS = 16
    const val BURST_WINDOW_MS = 5_000L

    /**
     * Difference hash of a 9×8 greyscale image (row-major, 72 values): each bit says whether a
     * pixel is brighter than its right neighbour. Robust to scaling, compression and colour shifts.
     */
    fun dHash(luma: IntArray): Long {
        require(luma.size == 72) { "dHash needs 9x8 pixels" }
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (luma[y * 9 + x] > luma[y * 9 + x + 1]) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun find(files: List<VaultFile>): List<DuplicateGroup> {
        val groups = mutableListOf<DuplicateGroup>()

        // Exact copies: identical contents. Keep the one sealed first (the original import).
        val exactExtras = mutableSetOf<String>()
        files.groupBy { it.sha256 }.values.filter { it.size > 1 }.forEach { same ->
            val best = same.minWith(compareBy<VaultFile> { it.sealedAt }.thenBy { it.id })
            groups += DuplicateGroup(DuplicateKind.EXACT, same.sortedBy { it.sealedAt }, best)
            exactExtras += same.filter { it.id != best.id }.map { it.id }
        }

        // Photos for visual comparison; exact extras are already accounted for.
        val photos = files.filter { it.kind == FileKind.PHOTO && it.phash != null && it.id !in exactExtras }
        val parent = IntArray(photos.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var c = i
            while (parent[c] != r) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }
        val lookAlikeEdge = BooleanArray(photos.size)
        for (i in photos.indices) {
            val hi = photos[i].phash!!
            for (j in i + 1 until photos.size) {
                val d = distance(hi, photos[j].phash!!)
                val lookAlike = d <= LOOK_ALIKE_BITS
                val burst = !lookAlike && d <= BURST_BITS && abs(photos[i].takenAt - photos[j].takenAt) <= BURST_WINDOW_MS
                if (lookAlike || burst) {
                    val ri = root(i)
                    val rj = root(j)
                    if (ri != rj) parent[ri] = rj
                    if (lookAlike) { lookAlikeEdge[i] = true; lookAlikeEdge[j] = true }
                }
            }
        }
        photos.indices.groupBy(::root).values.filter { it.size > 1 }.forEach { members ->
            val list = members.map { photos[it] }
            val kind = if (members.any { lookAlikeEdge[it] }) DuplicateKind.LOOK_ALIKE else DuplicateKind.BURST
            groups += DuplicateGroup(kind, list.sortedBy { it.takenAt }, bestPhoto(list))
        }
        return groups.sortedByDescending { it.reclaimableBytes }
    }

    /** Highest resolution wins, then the larger (less compressed) file, then the earliest. */
    fun bestPhoto(list: List<VaultFile>): VaultFile = list.maxWith(
        compareBy<VaultFile> { (it.width ?: 0).toLong() * (it.height ?: 0) }
            .thenBy { it.sizeBytes }
            .thenByDescending { it.takenAt }
            .thenByDescending { it.id },
    )
}
