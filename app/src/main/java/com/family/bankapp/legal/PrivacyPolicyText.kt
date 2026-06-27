package com.family.bankapp.legal

/**
 * Privacy policy text for Family Bank.
 * Also published at [FamilyAppConfig.PRIVACY_POLICY_URL] for Plaid Dashboard.
 */
object PrivacyPolicyText {
    const val TITLE = "Family Bank Privacy Policy"
    const val LAST_UPDATED = "June 27, 2026"
    const val CONTACT = "johnathon.larsen@gmail.com"

    val sections: List<Pair<String, String>> = listOf(
        "Who we are" to
            "Family Bank is a private bill-tracking app for our household. It is not a bank and does not " +
            "hold deposits. We built it for family use only — not for the public.",

        "What we collect" to
            "On your phone (locally):\n" +
            "• Bills you enter (names, amounts, due dates, pay-from labels)\n" +
            "• Bank and account labels you create\n" +
            "• Payment records when you mark bills paid\n\n" +
            "Via Plaid (when you connect a bank):\n" +
            "• Account names and last-four digits (masks)\n" +
            "• Account balances\n" +
            "• Transaction descriptions, amounts, and dates (up to 90 days)\n" +
            "• Bank login is handled by Plaid — we never see or store your bank password\n\n" +
            "On our shared server (Supabase):\n" +
            "• Anonymous counters (Plaid slot usage, API call budget)\n" +
            "• Plaid access tokens stored server-side only (not on your phone)\n" +
            "• No bill names, amounts, or personal identifiers in the cloud",

        "How we use data" to
            "• Show your bills and due dates on your device\n" +
            "• Remind you when bills are due\n" +
            "• Match bank transactions to bills you entered (optional, via Plaid)\n" +
            "• Track Plaid usage limits across family phones\n\n" +
            "We do not sell, rent, or share your data with advertisers or data brokers.",

        "Plaid" to
            "We use Plaid Inc. to connect to financial institutions. When you connect a bank, Plaid's " +
            "privacy policy applies to that connection: https://plaid.com/legal/#end-user-privacy-policy\n\n" +
            "You can also manage connections at https://my.plaid.com/",

        "Where data is stored" to
            "• Bills and most app data stay on your phone only (no cloud sync between devices)\n" +
            "• Plaid tokens and usage counters are stored on Supabase (encrypted at rest by Supabase)\n" +
            "• Each family member's phone has its own local copy of bills",

        "Retention and removing Plaid" to
            "• Local bill data stays until you delete it or uninstall the app\n" +
            "• Uninstalling the app does not remove your Plaid connection from our server — " +
            "you can restore the saved link on a new install without relinking\n" +
            "• Remove Plaid connection (Banks screen, family password required) revokes the link with Plaid, " +
            "deletes the server token, and unlinks the bank on your phone. Local accounts and cached " +
            "transactions remain as labels only\n" +
            "• Removing a connection does not refund Plaid Trial slots on Plaid's billing plan\n" +
            "• API usage counters reset monthly",

        "Your choices" to
            "• You can use the app without connecting Plaid (manual bill entry only)\n" +
            "• You can restore a saved Plaid link after reinstalling the app\n" +
            "• You can permanently remove a Plaid connection with the password-protected option in the Banks screen\n" +
            "• You can delete bills, banks, and payment history in the app\n" +
            "• Contact us at $CONTACT to request deletion of server-side Plaid data",

        "Children" to
            "This app is for adult family members managing household bills. It is not directed at children under 13.",

        "Changes" to
            "We may update this policy as the app changes. The \"Last updated\" date at the top will change when we do.",

        "Contact" to
            "Questions about this policy: $CONTACT"
    )
}
