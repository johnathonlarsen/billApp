package com.family.bankapp.util

/**
 * Spreads a lump-sum payment across unpaid (including partially paid) bills in due-date order.
 * Extra beyond the remaining month total is added to the last allocated bill.
 */
object MonthPaymentAllocator {
    data class Allocation(
        val entry: MonthBillEntry,
        val newAmountCents: Long
    )

    data class Preview(
        val allocations: List<Allocation>,
        val fullyPaidCount: Int,
        val partialCount: Int,
        val unpaidCount: Int
    )

    fun allocate(
        outstanding: List<MonthBillEntry>,
        totalPaidCents: Long
    ): List<Allocation> {
        if (totalPaidCents <= 0L || outstanding.isEmpty()) return emptyList()
        val ordered = outstanding.sortedWith(compareBy({ it.dueDate }, { it.bill.id }))
        var pool = totalPaidCents
        val result = mutableListOf<Allocation>()

        for (entry in ordered) {
            if (pool <= 0L) break
            val alreadyPaid = entry.paidCents
            val stillOwed = entry.remainingCents
            if (stillOwed <= 0L) continue
            val applied = minOf(stillOwed, pool)
            result.add(Allocation(entry, alreadyPaid + applied))
            pool -= applied
        }

        if (pool > 0L && result.isNotEmpty()) {
            val lastIdx = result.lastIndex
            val last = result[lastIdx]
            result[lastIdx] = last.copy(newAmountCents = last.newAmountCents + pool)
        } else if (pool > 0L) {
            val last = ordered.last()
            result.add(Allocation(last, last.paidCents + pool))
        }
        return result
    }

    fun preview(outstanding: List<MonthBillEntry>, totalPaidCents: Long): Preview {
        val allocations = allocate(outstanding, totalPaidCents)
        val allocatedIds = allocations.map { it.entry.bill.id }.toSet()
        val fullyPaidCount = allocations.count { it.newAmountCents >= it.entry.bill.amountCents }
        val partialCount = allocations.count {
            it.newAmountCents > 0L && it.newAmountCents < it.entry.bill.amountCents
        }
        val unpaidCount = outstanding.count { it.bill.id !in allocatedIds && it.remainingCents > 0L }
        return Preview(
            allocations = allocations,
            fullyPaidCount = fullyPaidCount,
            partialCount = partialCount,
            unpaidCount = unpaidCount
        )
    }
}
