import { verifyAppRequest } from '../_shared/auth.ts';
import { corsHeaders, errorResponse, jsonResponse, readJson } from '../_shared/http.ts';
import { createPlaidClient, getTeamId } from '../_shared/plaid.ts';
import { consumePlaidApiCalls, getPlaidApiBudget } from '../_shared/supabase.ts';
import { getItemAccessToken } from '../_shared/vault.ts';

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders });
  }

  const unauthorized = verifyAppRequest(req);
  if (unauthorized) return unauthorized;

  if (req.method !== 'POST') {
    return errorResponse(405, 'POST required');
  }

  try {
    const body = await readJson<{ item_id?: string; team_id?: string }>(req);
    const itemId = body.item_id?.trim();
    if (!itemId) {
      return errorResponse(400, 'item_id required');
    }

    const teamId = getTeamId(body.team_id);
    const accessToken = await getItemAccessToken(itemId);
    if (!accessToken) {
      return errorResponse(404, 'Plaid Item not found or disconnected');
    }

    const budgetBefore = await getPlaidApiBudget(teamId);
    if (budgetBefore.at_limit || budgetBefore.remaining < 1) {
      return errorResponse(429, 'Plaid API monthly budget exhausted', { budget: budgetBefore });
    }

    const consumed = await consumePlaidApiCalls(teamId, 1, 'accounts_get');
    if (consumed.ok === false) {
      return errorResponse(429, 'Plaid API monthly budget exhausted', { budget: consumed });
    }

    const plaid = createPlaidClient();
    const response = await plaid.accountsGet({ access_token: accessToken });
    const budget = await getPlaidApiBudget(teamId);

    return jsonResponse({
      accounts: response.data.accounts.map((a) => ({
        account_id: a.account_id,
        name: a.name,
        official_name: a.official_name,
        mask: a.mask,
        type: a.type,
        subtype: a.subtype,
        balance_current: a.balances.current,
        balance_available: a.balances.available,
      })),
      budget,
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    console.error('plaid-accounts-sync', message);
    return errorResponse(500, message);
  }
});
