export type PlaidUsageJson = {
  team_id: string;
  limit: number;
  used: number;
  remaining: number;
  at_limit: boolean;
  trial_note?: string;
};

function supabaseRestUrl(): string {
  const url = Deno.env.get('SUPABASE_URL');
  if (!url) throw new Error('SUPABASE_URL is not configured');
  return url.replace(/\/$/, '');
}

function serviceRoleKey(): string {
  const key = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
  if (!key) throw new Error('SUPABASE_SERVICE_ROLE_KEY is not configured');
  return key;
}

export { supabaseRestUrl, serviceRoleKey };

async function callRpc<T>(name: string, body: Record<string, unknown>): Promise<T> {
  const response = await fetch(`${supabaseRestUrl()}/rest/v1/rpc/${name}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      apikey: serviceRoleKey(),
      Authorization: `Bearer ${serviceRoleKey()}`,
    },
    body: JSON.stringify(body),
  });

  const text = await response.text();
  if (!response.ok) {
    throw new Error(`Supabase RPC ${name} failed (${response.status}): ${text}`);
  }

  return JSON.parse(text) as T;
}

export async function getPlaidUsage(teamId: string): Promise<PlaidUsageJson> {
  return callRpc<PlaidUsageJson>('get_plaid_usage', { p_team_id: teamId });
}

export async function registerPlaidSlot(teamId: string): Promise<Record<string, unknown>> {
  return callRpc<Record<string, unknown>>('register_plaid_slot', { p_team_id: teamId });
}

export type PlaidApiBudgetJson = {
  team_id: string;
  limit: number;
  used: number;
  remaining: number;
  at_limit: boolean;
  period_month?: string;
  expected_items?: number;
  note?: string;
};

export async function getPlaidApiBudget(teamId: string): Promise<PlaidApiBudgetJson> {
  return callRpc<PlaidApiBudgetJson>('get_plaid_api_budget', { p_team_id: teamId });
}

export async function consumePlaidApiCalls(
  teamId: string,
  calls = 1,
  callType = 'transactions_sync',
): Promise<Record<string, unknown>> {
  return callRpc<Record<string, unknown>>('consume_plaid_api_calls', {
    p_team_id: teamId,
    p_calls: calls,
    p_call_type: callType,
  });
}
