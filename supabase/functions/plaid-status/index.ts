import { verifyAppRequest } from '../_shared/auth.ts';
import { corsHeaders, errorResponse, jsonResponse, readJson } from '../_shared/http.ts';
import { getTeamId } from '../_shared/plaid.ts';
import { getPlaidApiBudget, getPlaidUsage } from '../_shared/supabase.ts';

/** Authenticated read of Plaid item-slot usage + monthly API budget (no PostgREST anon access). */
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
    const [usage, budget] = await Promise.all([
      getPlaidUsage(teamId),
      getPlaidApiBudget(teamId),
    ]);
    return jsonResponse({ usage, budget });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    console.error('plaid-status', message);
    return errorResponse(500, message);
  }
});
