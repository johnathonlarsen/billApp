import { verifyAppRequest } from '../_shared/auth.ts';
import { corsHeaders, errorResponse, jsonResponse, readJson } from '../_shared/http.ts';
import { createPlaidClient, getTeamId } from '../_shared/plaid.ts';
import {
  getOrphanConnectBlock,
  parseLinkedItemIds,
  rejectDuplicateInstitutionConnect,
} from '../_shared/plaid-connect-guard.ts';
import { getPlaidUsage, registerPlaidSlot } from '../_shared/supabase.ts';
import { storeItemAccessToken } from '../_shared/vault.ts';

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
    const body = await readJson<{
      public_token?: string;
      replacing_item_id?: string;
      team_id?: string;
      linked_item_ids?: unknown;
    }>(req);

    const publicToken = body.public_token?.trim();
    if (!publicToken) {
      return errorResponse(400, 'public_token required');
    }

    const teamId = getTeamId(body.team_id);
    const replacing = body.replacing_item_id?.trim();
    const linkedItemIds = parseLinkedItemIds(body.linked_item_ids);

    const orphanBlock = await getOrphanConnectBlock(teamId, linkedItemIds, replacing);
    if (orphanBlock) {
      return errorResponse(409, orphanBlock.error, { orphans: orphanBlock.orphans });
    }

    const usageBefore = await getPlaidUsage(teamId);
    if (usageBefore.at_limit && !replacing) {
      return errorResponse(429, `Plaid Trial limit reached (${usageBefore.used}/${usageBefore.limit})`, {
        usage: usageBefore,
      });
    }

    const plaid = createPlaidClient();
    const exchange = await plaid.itemPublicTokenExchange({ public_token: publicToken });
    const itemId = exchange.data.item_id;
    const accessToken = exchange.data.access_token;

    if (!replacing) {
      const duplicate = await rejectDuplicateInstitutionConnect(teamId, itemId, accessToken);
      if (duplicate) {
        return errorResponse(409, duplicate.error, { institution_name: duplicate.institution_name });
      }
    }

    const slotResult = replacing
      ? { ok: true, note: 'replacement — slot count unchanged' }
      : await registerPlaidSlot(teamId);

    if (!replacing && slotResult.ok === false) {
      return errorResponse(429, 'Could not register Plaid slot', { slot: slotResult, usage: usageBefore });
    }

    // Plaid policy: store access_token server-side only — never return to mobile client.
    await storeItemAccessToken(teamId, itemId, accessToken, replacing);

    const usage = await getPlaidUsage(teamId);

    return jsonResponse({
      item_id: itemId,
      slot_id: slotResult.slot_id ?? null,
      usage,
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    console.error('plaid-exchange', message);
    return errorResponse(500, message);
  }
});
