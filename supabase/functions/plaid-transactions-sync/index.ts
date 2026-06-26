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
    const body = await readJson<{ item_id?: string; cursor?: string; team_id?: string }>(req);
    const itemId = body.item_id?.trim();
    if (!itemId) {
      return errorResponse(400, 'item_id required');
    }

    getTeamId(body.team_id); // validates team config exists

    const accessToken = await getItemAccessToken(itemId);
    if (!accessToken) {
      return errorResponse(404, 'Plaid Item not found or disconnected');
    }

    const teamId = getTeamId(body.team_id);
    const budgetBefore = await getPlaidApiBudget(teamId);
    if (budgetBefore.at_limit || budgetBefore.remaining < 1) {
      return errorResponse(429, 'Plaid API monthly budget exhausted', { budget: budgetBefore });
    }

    const consumed = await consumePlaidApiCalls(teamId, 1, 'transactions_sync');
    if (consumed.ok === false) {
      return errorResponse(429, 'Plaid API monthly budget exhausted', { budget: consumed });
    }

    const plaid = createPlaidClient();
    const response = await plaid.transactionsSync({
      access_token: accessToken,
      cursor: body.cursor || undefined,
    });

    const { added, modified, removed, next_cursor, has_more } = response.data;
    const budget = await getPlaidApiBudget(teamId);

    return jsonResponse({
      added: added.map((t) => ({
        transaction_id: t.transaction_id,
        account_id: t.account_id,
        amount: t.amount,
        date: t.date,
        name: t.name,
        merchant_name: t.merchant_name,
        pending: t.pending,
      })),
      modified: modified.length,
      removed: removed.length,
      next_cursor,
      has_more,
      budget,
      sync_note: has_more
        ? 'More pages available — call again if budget allows (each page = 1 API call).'
        : null,
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    console.error('plaid-transactions-sync', message);
    return errorResponse(500, message);
  }
});
