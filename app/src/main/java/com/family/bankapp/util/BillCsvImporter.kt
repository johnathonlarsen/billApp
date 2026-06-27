package com.family.bankapp.util

import com.family.bankapp.data.entity.AccountEntity
import com.family.bankapp.data.entity.BankEntity
import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.model.BillCategory
import com.family.bankapp.data.model.BillRecurrence
import java.util.Locale

data class BillCsvImportResult(
    val imported: List<BillEntity>,
    val errors: List<String>
) {
    val importedCount: Int get() = imported.size
    val success: Boolean get() = imported.isNotEmpty() && errors.isEmpty()
}

object BillCsvImporter {
    /**
     * Expected columns (header row optional):
     * name, amount, due_day, category, recurrence, pay_from, reminder_days, notes
     *
     * pay_from format: "Bank Name · Account Name" (must match banks added in the app)
     */
    fun parse(
        csvText: String,
        banks: List<BankEntity>,
        accounts: List<AccountEntity>
    ): BillCsvImportResult {
        val payFromMap = buildPayFromMap(banks, accounts)
        val lines = csvText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            return BillCsvImportResult(emptyList(), listOf("File is empty"))
        }

        val startIndex = if (isHeaderRow(lines.first())) 1 else 0
        val imported = mutableListOf<BillEntity>()
        val errors = mutableListOf<String>()

        for (i in startIndex until lines.size) {
            val lineNum = i + 1
            val fields = parseCsvLine(lines[i])
            if (fields.isEmpty()) continue

            try {
                imported.add(parseRow(fields, lineNum, payFromMap, errors))
            } catch (e: Exception) {
                errors.add("Line $lineNum: ${e.message ?: "Invalid row"}")
            }
        }

        return BillCsvImportResult(imported, errors)
    }

    fun export(
        bills: List<BillEntity>,
        banks: List<BankEntity>,
        accounts: List<AccountEntity>
    ): String {
        val payFromLabels = buildPayFromLabelMap(banks, accounts)
        val header = "name,amount,due_day,category,recurrence,pay_from,reminder_days,notes"
        val rows = bills
            .sortedBy { it.name.lowercase() }
            .map { bill ->
                listOf(
                    escapeCsvField(bill.name),
                    escapeCsvField(formatAmount(bill.amountCents)),
                    bill.dueDayOfMonth.toString(),
                    escapeCsvField(bill.category.label),
                    escapeCsvField(bill.recurrence.label),
                    escapeCsvField(bill.linkedAccountId?.let { payFromLabels[it] }.orEmpty()),
                    bill.reminderDaysBefore.toString(),
                    escapeCsvField(bill.notes)
                ).joinToString(",")
            }
        return (listOf(header) + rows).joinToString("\n")
    }

    fun templateCsv(): String = """
        name,amount,due_day,category,recurrence,pay_from,reminder_days,notes
        Mortgage,1850.00,1,Housing,Monthly,Capital One · 360 Checking,5,
        Electric,145.00,12,Utilities,Monthly,Keesler FCU · Primary Checking,3,
        Internet,79.99,18,Utilities,Monthly,,2,
    """.trimIndent()

    private fun isHeaderRow(line: String): Boolean {
        val first = parseCsvLine(line).firstOrNull()?.lowercase() ?: return false
        return first == "name" || first == "bill" || first == "bill_name"
    }

    private fun buildPayFromMap(
        banks: List<BankEntity>,
        accounts: List<AccountEntity>
    ): Map<String, Long> {
        val map = mutableMapOf<String, Long>()
        accounts.forEach { account ->
            val bank = banks.find { it.id == account.bankId } ?: return@forEach
            val label = "${bank.name} · ${account.name}"
            map[label.lowercase()] = account.id
            map[normalize(label)] = account.id
        }
        return map
    }

    private fun buildPayFromLabelMap(
        banks: List<BankEntity>,
        accounts: List<AccountEntity>
    ): Map<Long, String> =
        accounts.mapNotNull { account ->
            val bank = banks.find { it.id == account.bankId } ?: return@mapNotNull null
            account.id to "${bank.name} · ${account.name}"
        }.toMap()

    private fun formatAmount(amountCents: Long): String =
        String.format(Locale.US, "%.2f", amountCents / 100.0)

    private fun escapeCsvField(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun parseRow(
        fields: List<String>,
        lineNum: Int,
        payFromMap: Map<String, Long>,
        errors: MutableList<String>
    ): BillEntity {
        val name = fields.getOrNull(0)?.trim().orEmpty()
        if (name.isBlank()) throw IllegalArgumentException("Name is required")

        val amountCents = parseAmount(fields.getOrNull(1))
            ?: throw IllegalArgumentException("Invalid amount '${fields.getOrNull(1)}'")

        val dueDay = fields.getOrNull(2)?.trim()?.toIntOrNull()?.coerceIn(1, 28) ?: 1
        val category = parseCategory(fields.getOrNull(3))
        val recurrence = parseRecurrence(fields.getOrNull(4))
        val payFromRaw = fields.getOrNull(5)?.trim().orEmpty()
        val reminderDays = fields.getOrNull(6)?.trim()?.toIntOrNull()?.coerceIn(0, 14) ?: 3
        val notes = fields.getOrNull(7)?.trim().orEmpty()

        var linkedAccountId: Long? = null
        if (payFromRaw.isNotBlank()) {
            linkedAccountId = payFromMap[payFromRaw.lowercase()]
                ?: payFromMap[normalize(payFromRaw)]
            if (linkedAccountId == null) {
                errors.add("Line $lineNum: pay_from '$payFromRaw' not found — bill imported without link")
            }
        }

        return BillEntity(
            name = name,
            amountCents = amountCents,
            dueDayOfMonth = dueDay,
            recurrence = recurrence,
            category = category,
            linkedAccountId = linkedAccountId,
            reminderDaysBefore = reminderDays,
            notes = notes
        )
    }

    private fun parseAmount(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return MoneyFormatter.parse(raw)
            ?: raw.replace(",", "").toDoubleOrNull()?.let { (it * 100).toLong() }
    }

    private fun parseCategory(raw: String?): BillCategory {
        if (raw.isNullOrBlank()) return BillCategory.OTHER
        return BillCategory.entries.find {
            it.name.equals(raw.trim(), ignoreCase = true) ||
                it.label.equals(raw.trim(), ignoreCase = true)
        } ?: BillCategory.OTHER
    }

    private fun parseRecurrence(raw: String?): BillRecurrence {
        if (raw.isNullOrBlank()) return BillRecurrence.MONTHLY
        return BillRecurrence.entries.find {
            it.name.equals(raw.trim(), ignoreCase = true) ||
                it.label.equals(raw.trim(), ignoreCase = true)
        } ?: BillRecurrence.MONTHLY
    }

    /** Simple CSV line parser (handles quoted fields). */
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    result.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString().trim())
        return result
    }
}
