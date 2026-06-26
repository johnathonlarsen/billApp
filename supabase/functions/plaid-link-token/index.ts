import { verifyAppRequest } from '../_shared/auth.ts';
import { corsHeaders, errorResponse, jsonResponse, readJson } from '../_shared/http.ts';
import { CountryCode, createPlaidClient, getAndroidPackage, getTeamId, Products } from '../_shared/plaid.ts';
import { getOrphanConnectBlock, parseLinkedItemIds } from '../_shared/plaid-connect-guard.ts';
import { getPlaidUsage } from '../_shared/supabase.ts';

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
      replacing_item_id?: string;
      team_id?: string;
      linked_item_ids?: unknown;
    }>(req);
    const teamId = getTeamId(body.team_id);
    const replacing = body.replacing_item_id?.trim();
    const linkedItemIds = parseLinkedItemIds(body.linked_item_ids);

    const orphanBlock = await getOrphanConnectBlock(teamId, linkedItemIds, replacing);
    if (orphanBlock) {
      return errorResponse(409, orphanBlock.error, { orphans: orphanBlock.orphans });
    }

    const usage = await getPlaidUsage(teamId);
    if (usage.at_limit && !replacing) {
      return errorResponse(429, `Plaid Trial limit reached (${usage.used}/${usage.limit})`, { usage });
    }

    const plaid = createPlaidClient();
    const response = await plaid.linkTokenCreate({
      user: { client_user_id: `family-bank-${teamId}` },
      client_name: 'Family Bank',
      products: [Products.Transactions],
      transactions: { days_requested: 90 },
      country_codes: [CountryCode.Us],
      language: 'en',
      android_package_name: getAndroidPackage(),
    });

    return jsonResponse({
      link_token: response.data.link_token,
      usage,
      warning: usage.remaining <= 2 && !replacing
        ? `Only ${usage.remaining} Trial slot(s) left (${usage.used}/${usage.limit} used).`
        : null,
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    console.error('plaid-link-token', message);
    return errorResponse(500, message);
  }
});
