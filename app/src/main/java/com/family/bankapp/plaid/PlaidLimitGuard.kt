package com.family.bankapp.plaid

/** Plaid Trial: 10 Production Items lifetime; removing Items does NOT free slots. */
object PlaidCompliance {
    const val PLAID_PRIVACY_URL = "https://plaid.com/legal/#end-user-privacy-policy"
    const val PLAID_DATA_USE = "Transaction data is used only to help mark family bills as paid. " +
        "We do not sell or share your data. Plaid connects your bank — see Plaid's privacy policy."

    fun connectDisclosure(): String = buildString {
        append(PLAID_DATA_USE).append("\n\n")
        append("By continuing, you use Plaid Link to sign in to your bank. ")
        append("Credentials go to Plaid, not stored in this app.\n\n")
        append("Privacy policy: ").append(com.family.bankapp.FamilyAppConfig.PRIVACY_POLICY_URL)
    }
}

object PlaidTrialRules {
    const val DEFAULT_ITEM_LIMIT = 10
    const val TRIAL_DOCS_URL = "https://plaid.com/docs/account/billing/"

    val limitReachedMessage: String
        get() = buildString {
            append("Plaid Trial allows 10 bank connections total (lifetime).\n\n")
            append("You have used all 10. Plaid will block new connections until you upgrade to a paid plan.\n\n")
            append("Important: removing a bank on Trial does NOT give you another slot.")
        }

    fun confirmNewConnection(used: Int, limit: Int, remaining: Int, bankName: String): String =
        buildString {
            append("Connect $bankName via Plaid?\n\n")
            append("Trial usage: $used / $limit used ($remaining left).\n\n")
            append("Each new connection uses one slot forever on the Trial plan — even if you disconnect later.\n\n")
            append("Your family needs at most 3 (your phone + mom's = 3 total). Do not test-connect extra banks.\n\n")
            append(PlaidCompliance.connectDisclosure())
        }

    fun lowSlotsWarning(remaining: Int, used: Int, limit: Int): String =
        "Only $remaining Plaid slot(s) left ($used/$limit used). Each connection is permanent on Trial."
}

data class PlaidUsage(
    val limit: Int,
    val used: Int,
    val remaining: Int,
    val atLimit: Boolean,
    val trialNote: String? = null,
    val source: UsageSource
) {
    enum class UsageSource { SUPABASE, LOCAL_ONLY, UNAVAILABLE }

    val summary: String get() = "$used / $limit Plaid slots used (Trial)"
}

data class PlaidApiBudget(
    val limit: Int,
    val used: Int,
    val remaining: Int,
    val atLimit: Boolean,
    val periodMonth: String? = null,
    val note: String? = null
) {
    val summary: String get() = "$used / $limit used · $remaining left"
    val percentUsed: Float get() = if (limit > 0) used.toFloat() / limit else 0f
}

data class PlaidConnectCheck(
    val allowed: Boolean,
    val usage: PlaidUsage,
    val blockReason: String? = null,
    val confirmMessage: String? = null
)

object PlaidLimitGuard {
    const val WARN_WHEN_REMAINING_AT_OR_BELOW = 2

    fun checkBeforeConnect(
        serverUsage: PlaidUsage?,
        localPlaidConnectedCount: Int,
        configuredLimit: Int,
        bankName: String,
        isReplacingExisting: Boolean
    ): PlaidConnectCheck {
        val usage = serverUsage ?: PlaidUsage(
            limit = configuredLimit,
            used = localPlaidConnectedCount,
            remaining = (configuredLimit - localPlaidConnectedCount).coerceAtLeast(0),
            atLimit = localPlaidConnectedCount >= configuredLimit,
            trialNote = "Local count only — shared Supabase count unavailable (check internet).",
            source = PlaidUsage.UsageSource.LOCAL_ONLY
        )

        if (usage.atLimit || usage.remaining <= 0) {
            if (!isReplacingExisting) {
                return PlaidConnectCheck(
                    allowed = false,
                    usage = usage,
                    blockReason = PlaidTrialRules.limitReachedMessage +
                        if (usage.source == PlaidUsage.UsageSource.LOCAL_ONLY) {
                            "\n\n(Showing this device only — could not reach shared counter.)"
                        } else ""
                )
            }
        }

        val confirm = buildString {
            if (isReplacingExisting) {
                append("Reconnect $bankName?\n\n")
                append("Warning: a full new Plaid login may consume another Trial slot. ")
                append("Prefer fixing the existing link if possible.\n\n")
            } else {
                append(PlaidTrialRules.confirmNewConnection(usage.used, usage.limit, usage.remaining, bankName))
            }
            usage.trialNote?.let { append("\n\n").append(it) }
            if (usage.remaining <= WARN_WHEN_REMAINING_AT_OR_BELOW && !isReplacingExisting) {
                append("\n\n").append(PlaidTrialRules.lowSlotsWarning(usage.remaining, usage.used, usage.limit))
            }
        }

        return PlaidConnectCheck(
            allowed = true,
            usage = usage,
            confirmMessage = confirm
        )
    }
}
