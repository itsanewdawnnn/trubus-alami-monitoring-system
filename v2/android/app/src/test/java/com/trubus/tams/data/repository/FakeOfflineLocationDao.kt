package com.trubus.tams.data.repository

import com.trubus.tams.data.local.OfflineLocationDao
import com.trubus.tams.data.model.OfflineLocation
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory stand-in for [OfflineLocationDao], used by [MemberRepositoryTest]
 * instead of a real Room/SQLite database. MemberRepository's write-ahead
 * queue behavior (insert before the network attempt, delete only once the
 * outcome is known) is orchestration logic this class exercises directly --
 * Room's own generated SQL is already Google's own tested code, not this
 * project's, so there's nothing gained by routing these tests through a
 * real (or in-memory) Room database just to reach it.
 */
class FakeOfflineLocationDao : OfflineLocationDao {

    private val nextId = AtomicInteger(1)
    private val rows = linkedMapOf<Int, OfflineLocation>()

    override suspend fun insert(location: OfflineLocation): Long {
        val id = nextId.getAndIncrement()
        rows[id] = location.copy(id = id)
        return id.toLong()
    }

    override suspend fun getAll(): List<OfflineLocation> =
        rows.values.sortedBy { it.timestamp }

    override suspend fun deleteByIds(ids: List<Int>) {
        ids.forEach { rows.remove(it) }
    }

    override suspend fun deleteByUserId(userId: Int) {
        rows.entries.removeAll { it.value.userId == userId }
    }

    override suspend fun trimToMostRecent(keepCount: Int) {
        val toKeep = rows.values.sortedByDescending { it.timestamp }.take(keepCount).map { it.id }.toSet()
        rows.entries.removeAll { it.key !in toKeep }
    }

    /** Test-only helper: current queue contents, oldest first, with no coroutine scope needed. */
    fun snapshotForTest(): List<OfflineLocation> = rows.values.sortedBy { it.timestamp }
}
