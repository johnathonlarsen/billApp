import { verifyAppRequest } from '../_shared/auth.ts';
import { corsHeaders, errorResponse, jsonResponse, readJson } from '../_shared/http.ts';
import { createPlaidClient, getTeamId } from '../_shared/plaid.ts';
import { getItemAccessToken, listActiveVaultItems, markItemAccessTokenRemoved } from '../_shared/vault.ts';

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
    const vaultRows = await listActiveVaultItems(teamId);
    if (!vaultRows.some((row) => row.item_id === itemId)) {
      return errorResponse(404, 'Plaid Item not found for this team');
    }

    const accessToken = await getItemAccessToken(itemId);
    if (!accessToken) {
      await markItemAccessTokenRemoved(itemId);
      return jsonResponse({
        ok: true,
        item_id: itemId,
        note: 'Connection was already removed from the server vault.',
      });
    }

    const plaid = createPlaidClient();
    try {
      await plaid.itemRemove({ access_token: accessToken });
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      console.error('plaid-item-remove itemRemove', message);
      // Still purge vault so the app cannot restore a dead link.
    }

    await markItemAccessTokenRemoved(itemId);

    return jsonResponse({
      ok: true,
      item_id: itemId,
      note: 'Plaid connection removed. Trial slots on the Plaid plan are not refunded.',
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    console.error('plaid-item-remove', message);
    return errorResponse(500, message);
  }
});
