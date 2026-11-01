# Deprecated

This local Plaid relay is **not used** by the Family Bank app.

All Plaid API calls go through **Supabase Edge Functions** with `PLAID_ENV=production`.

Do not run this server. If you must for local emergency use:

1. Set `ALLOW_DEPRECATED_PLAID_SERVER=true`
2. Set `FAMILY_APP_API_SECRET` (≥16 characters)
3. Send `X-Family-Bank-Key` on every request except `GET /health`
4. Know that `/item/public_token/exchange` no longer returns `access_token`
