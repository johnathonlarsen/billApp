# Deprecated

This local Plaid relay is **not used** by the Family Bank app.

All Plaid API calls go through **Supabase Edge Functions** with `PLAID_ENV=production`.

Do not run this server — it defaults to sandbox and returns access tokens to clients (Plaid policy violation).
