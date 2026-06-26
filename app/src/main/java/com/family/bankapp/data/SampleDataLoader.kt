package com.family.bankapp.data

import com.family.bankapp.data.model.AccountType
import com.family.bankapp.data.model.BillCategory
import com.family.bankapp.data.model.BillRecurrence
import com.family.bankapp.data.repository.BankRepository
import com.family.bankapp.util.BankColors
import kotlinx.coroutines.runBlocking

object SampleDataLoader {
    fun load(repository: BankRepository) = runBlocking {
        if (repository.getBankCount() > 0) return@runBlocking

        val keeslerId = repository.addBank("Keesler FCU", BankColors.colorForIndex(0)).getOrNull() ?: return@runBlocking
        val capitalOneId = repository.addBank("Capital One", BankColors.colorForIndex(1)).getOrNull() ?: return@runBlocking
        val hancockId = repository.addBank("Hancock Whitney", BankColors.colorForIndex(2)).getOrNull() ?: return@runBlocking

        val keeslerChecking = repository.addAccount(keeslerId, "Primary Checking", AccountType.CHECKING)
        val capitalChecking = repository.addAccount(capitalOneId, "360 Checking", AccountType.CHECKING)
        val hancockChecking = repository.addAccount(hancockId, "Checking", AccountType.CHECKING)
        repository.addAccount(capitalOneId, "Venture Card", AccountType.CREDIT)

        repository.addBill(
            com.family.bankapp.data.entity.BillEntity(
                name = "Mortgage",
                amountCents = 185000,
                dueDayOfMonth = 1,
                recurrence = BillRecurrence.MONTHLY,
                category = BillCategory.HOUSING,
                linkedAccountId = keeslerChecking,
                reminderDaysBefore = 5
            )
        )
        repository.addBill(
            com.family.bankapp.data.entity.BillEntity(
                name = "Electric",
                amountCents = 14500,
                dueDayOfMonth = 12,
                recurrence = BillRecurrence.MONTHLY,
                category = BillCategory.UTILITIES,
                linkedAccountId = hancockChecking,
                reminderDaysBefore = 3
            )
        )
        repository.addBill(
            com.family.bankapp.data.entity.BillEntity(
                name = "Internet",
                amountCents = 7999,
                dueDayOfMonth = 18,
                recurrence = BillRecurrence.MONTHLY,
                category = BillCategory.UTILITIES,
                linkedAccountId = capitalChecking,
                reminderDaysBefore = 2
            )
        )
        repository.addBill(
            com.family.bankapp.data.entity.BillEntity(
                name = "Netflix",
                amountCents = 1599,
                dueDayOfMonth = 22,
                recurrence = BillRecurrence.MONTHLY,
                category = BillCategory.SUBSCRIPTION,
                linkedAccountId = capitalChecking,
                reminderDaysBefore = 1
            )
        )
        repository.addBill(
            com.family.bankapp.data.entity.BillEntity(
                name = "Car insurance",
                amountCents = 9200,
                dueDayOfMonth = 5,
                recurrence = BillRecurrence.MONTHLY,
                category = BillCategory.INSURANCE,
                linkedAccountId = hancockChecking,
                reminderDaysBefore = 7
            )
        )
    }
}
