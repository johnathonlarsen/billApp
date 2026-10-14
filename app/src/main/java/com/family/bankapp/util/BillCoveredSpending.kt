package com.family.bankapp.util

import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.entity.PlaidTransactionEntity
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.max

/**
 * Plaid debits that are already represented by a bill reserved in free-to-spend.
 * Counting them again as misc spending double-subtracts the same money.
 *
 * Food and fuel bills are tracked by hand, so grocery, restaurant, and gas
 * purchases are covered by those bills instead of being matched one-by-one.
 */
object BillCoveredSpending {

    data class Cover(
        val transaction: PlaidTransactionEntity,
        val bill: BillEntity,
        /** Short explanation for the debug report. */
        val reason: String
    )

    private val GENERIC_TOKENS = setOf(
        "fee", "bill", "monthly", "payment", "card", "auto", "the", "and", "from"
    )

    private val FOOD_MARKERS = listOf(
        "COCA COLA", "COCA-COLA", "GREER", "BUFFET", "MCDONALD", "RESTAURANT",
        "WALMART", "KROGER", "PUBLIX", "ALDI", "PIZZA", "DOORDASH", "UBER EATS",
        "GRUBHUB", "STARBUCKS", "CHICK-FIL", "TACO BELL", "WENDY", "BURGER KING",
        "SUBWAY", "GROCERY", "CAFE", "COFFEE", "WHATABURGER", "POPEYE", "CHIPOTLE",
        "PANERA", "DUNKIN", "SONIC", "WAFFLE", "LITTLE CAESAR", "DOMINO",
        "FOOD LION", "PIGGLY", "WINN-DIXIE", "TRADER JOE", "WHOLE FOODS"
    )

    private val FUEL_MARKERS = listOf(
        "SHELL", "EXXON", "CHEVRON", "CIRCLE K", "MURPHY", "RACETRAC", "QUIKTRIP",
        "PILOT", "LOVES", "LOVE'S", "SPEEDWAY", "VALERO", "SUNOCO", "MARATHON",
        "CASEY", "KWIK", "MAPCO", "PETRO", "FUEL", "GAS STATION"
    )

    fun covers(
        transactions: List<PlaidTransactionEntity>,
        countedBills: List<BillEntity>,
        spendingPlaidAccountIds: Set<String>,
        linkedTransactionIds: Set<String>,
        yearMonth: YearMonth
    ): List<Cover> {
        if (countedBills.isEmpty() || spendingPlaidAccountIds.isEmpty()) return emptyList()
        val candidates = transactions.filter { tx ->
            tx.plaidAccountId in spendingPlaidAccountIds &&
                tx.amountCents > 0 &&
                !tx.pending &&
                !tx.excludeFromFreeToSpend &&
                tx.plaidTransactionId !in linkedTransactionIds &&
                !PlaidMiscSpending.isOwnCapOneTransfer(tx) &&
                inMonth(tx, yearMonth)
        }
        val used = mutableSetOf<String>()
        val covers = mutableListOf<Cover>()

        val specificBills = countedBills.filter { !isManualFoodBill(it) && !isManualFuelBill(it) }
        candidates.forEach { tx ->
            val match = specificBills
                .filter { matchesBillPayment(tx, it) }
                .minByOrNull { abs(tx.amountCents - it.amountCents) }
                ?: return@forEach
            used += tx.plaidTransactionId
            covers += Cover(
                transaction = tx,
                bill = match,
                reason = "${match.name} bill ${MoneyFormatter.format(match.amountCents)}"
            )
        }

        val foodBill = countedBills.firstOrNull { isManualFoodBill(it) }
        val fuelBill = countedBills.firstOrNull { isManualFuelBill(it) }
        candidates.forEach { tx ->
            if (tx.plaidTransactionId in used) return@forEach
            val bill = when {
                foodBill != null && isFoodPurchase(tx) -> foodBill
                fuelBill != null && isFuelPurchase(tx) -> fuelBill
                else -> null
            } ?: return@forEach
            val kind = if (bill === foodBill) "food" else "fuel"
            covers += Cover(
                transaction = tx,
                bill = bill,
                reason = "${bill.name} bill (you track $kind spending on the bill)"
            )
        }
        return covers.sortedByDescending { it.transaction.date }
    }

    fun isManualFoodBill(bill: BillEntity): Boolean {
        val name = bill.name.lowercase()
        return name.contains("food") || name.contains("groc")
    }

    fun isManualFuelBill(bill: BillEntity): Boolean {
        val name = bill.name.lowercase()
        return name.contains("fuel") || name.contains("gas")
    }

    private fun matchesBillPayment(tx: PlaidTransactionEntity, bill: BillEntity): Boolean {
        val tokens = significantTokens(bill.name)
        if (tokens.isEmpty()) return false
        val haystack = haystack(tx)
        val longest = tokens.maxBy { it.length }
        if (!haystack.contains(longest)) return false
        return amountsClose(bill.amountCents, tx.amountCents)
    }

    private fun significantTokens(billName: String): List<String> =
        BillTransactionMatcher.normalizeMatchText(billName)
            .split(" ")
            .filter { it.length >= 4 && it !in GENERIC_TOKENS }

    private fun haystack(tx: PlaidTransactionEntity): String =
        BillTransactionMatcher.normalizeMatchText(
            listOfNotNull(tx.name, tx.merchantName?.takeIf { it.isNotBlank() }).joinToString(" ")
        )

    /** Within $15, or 10% of the bill when that is larger (Coast Electric $330 vs $338). */
    private fun amountsClose(billCents: Long, actualCents: Long): Boolean {
        if (billCents <= 0L || actualCents <= 0L) return false
        val tolerance = max(1_500L, billCents / 10)
        return abs(actualCents - billCents) <= tolerance
    }

    private fun isFoodPurchase(tx: PlaidTransactionEntity): Boolean {
        val raw = rawText(tx)
        return FOOD_MARKERS.any { raw.contains(it) }
    }

    private fun isFuelPurchase(tx: PlaidTransactionEntity): Boolean {
        val raw = rawText(tx)
        return FUEL_MARKERS.any { raw.contains(it) }
    }

    private fun rawText(tx: PlaidTransactionEntity): String =
        listOfNotNull(tx.name, tx.merchantName?.takeIf { it.isNotBlank() })
            .joinToString(" ")
            .uppercase()

    private fun inMonth(tx: PlaidTransactionEntity, yearMonth: YearMonth): Boolean =
        runCatching { YearMonth.from(LocalDate.parse(tx.date)) }.getOrNull() == yearMonth
}
