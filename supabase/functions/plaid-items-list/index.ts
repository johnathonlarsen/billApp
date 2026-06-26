import { verifyAppRequest } from '../_shared/auth.ts';
import { corsHeaders, errorResponse, jsonResponse, readJson } from '../_shared/http.ts';
import { createPlaidClient, getTeamId } from '../_shared/plaid.ts';
import { listActiveVaultItems } from '../_shared/vault.ts';

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
    const body = await readJson<{ team_id?: string }>(req);
    const teamId = getTeamId(body.team_id);
    const vaultRows = await listActiveVaultItems(teamId);
    const plaid = createPlaidClient();

    const items = await Promise.all(
      vaultRows.map(async (row) => {
        try {
          const item = await plaid.itemGet({ access_token: row.access_token });
          return {
            item_id: row.item_id,
            institution_name: item.data.item.institution_name ?? 'Unknown bank',
            created_at: row.created_at,
          };
        } catch {
          return {
            item_id: row.item_id,
            institution_name: 'Unknown bank',
            created_at: row.created_at,
          };
        }
      }),
    );

    return jsonResponse({ items });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    console.error('plaid-items-list', message);
    return errorResponse(500, message);
  }
});
