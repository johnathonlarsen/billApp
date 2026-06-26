package com.family.bankapp

import com.family.bankapp.sync.SupabaseSharedStateClient

/** Built-in config — change APP_ACCESS_PASSWORD here for your family. */
object FamilyAppConfig {
    const val SUPABASE_URL = "https://tfpahfhrclojmbfvwasg.supabase.co"
    const val SUPABASE_ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InRmcGFoZmhyY2xvam1iZnZ3YXNnIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODAwOTc5MDksImV4cCI6MjA5NTY3MzkwOX0.wd5vRaw2y-2AfqWnPPSyqAswz9ofP6xdWFdENlMOTt0"
    const val SUPABASE_TEAM_ID = "family-bank"

    /** Must match Supabase secret FAMILY_APP_API_SECRET (Edge Function auth). */
    const val EDGE_FUNCTION_SECRET = "familybank-edge"

    /** First-run password on each device (not Supabase — just keeps casual users out). */
    const val APP_ACCESS_PASSWORD = "familybank"

    /** Plaid Sandbox — use until Production Trial; unlimited Items in sandbox. */
    const val PLAID_MODE = "sandbox"

    /** Household Items (institution logins), not individual accounts. */
    const val PLAID_EXPECTED_ITEMS = 3

    /** Pooled Plaid product API calls per month (verify in dashboard; 200 is conservative). */
    const val PLAID_API_MONTHLY_LIMIT = 200

    /**
     * Auto sync once per bank per day → 3 Items × ~30 days ≈ 90 calls/mo (usually 1 page each).
     * Fits in a pooled 200/mo budget with room for pagination and manual syncs.
     * Daily live balances (/accounts/balance/get) would add ~90 more — skip unless needed.
     */
    const val PLAID_SYNC_INTERVAL_HOURS = 24

    /** Minimum hours between manual "Sync now" per bank on one phone. */
    const val PLAID_MANUAL_SYNC_COOLDOWN_HOURS = 24

    /** Public URL for Plaid Dashboard (Production application profile). */
    const val PRIVACY_POLICY_URL = "https://johnathonlarsen.github.io/billApp/privacy-policy.html"

    /**
     * Auto-update manifest on GitHub Pages (docs/app-update.json).
     * After each build, push docs/ (APK + manifest) and enable Pages on /docs.
     */
    const val UPDATE_MANIFEST_URL = "https://johnathonlarsen.github.io/billApp/app-update.json"

    fun supabaseConfig(): SupabaseSharedStateClient.Config =
        SupabaseSharedStateClient.Config(
            projectUrl = SUPABASE_URL,
            anonKey = SUPABASE_ANON_KEY,
            teamId = SUPABASE_TEAM_ID
        )
}
