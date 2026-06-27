package com.family.bankapp.data.model

enum class ConnectionType {
    MANUAL,
    CONNECTED
}

enum class AccountType(val label: String) {
    CHECKING("Checking"),
    SAVINGS("Savings"),
    CREDIT("Credit Card")
}

enum class BillRecurrence(val label: String) {
    MONTHLY("Monthly"),
    BIWEEKLY("Biweekly"),
    WEEKLY("Weekly"),
    YEARLY("Yearly"),
    ONE_TIME("One-time")
}

enum class BillCategory(val label: String) {
    HOUSING("Housing"),
    UTILITIES("Utilities"),
    SUBSCRIPTION("Subscription"),
    INSURANCE("Insurance"),
    LOAN("Loan"),
    OTHER("Other")
}
