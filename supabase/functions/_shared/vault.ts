import { serviceRoleKey, supabaseRestUrl } from './supabase.ts';

export type VaultItemRow = {
  item_id: string;
  access_token: string;
  created_at: string;
};

export async function storeItemAccessToken(
  teamId: string,
  itemId: string,
  accessToken: string,
  replacingItemId?: string,
): Promise<void> {
  const base = supabaseRestUrl();
  const key = serviceRoleKey();
  const headers = {
    'Content-Type': 'application/json',
    apikey: key,
    Authorization: `Bearer ${key}`,
    Prefer: 'return=minimal',
  };

  if (replacingItemId && replacingItemId !== itemId) {
    await fetch(`${base}/rest/v1/plaid_item_vault?item_id=eq.${encodeURIComponent(replacingItemId)}`, {
      method: 'PATCH',
      headers,
      body: JSON.stringify({ removed_at: new Date().toISOString() }),
    });
  }

  const response = await fetch(`${base}/rest/v1/plaid_item_vault`, {
    method: 'POST',
    headers: { ...headers, Prefer: 'resolution=merge-duplicates,return=minimal' },
    body: JSON.stringify({
      team_id: teamId,
      item_id: itemId,
      access_token: accessToken,
    }),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(`Failed to store Plaid token server-side (${response.status}): ${text}`);
  }
}

export async function listActiveVaultItems(teamId: string): Promise<VaultItemRow[]> {
  const base = supabaseRestUrl();
  const key = serviceRoleKey();
  const response = await fetch(
    `${base}/rest/v1/plaid_item_vault?team_id=eq.${encodeURIComponent(teamId)}&removed_at=is.null&select=item_id,access_token,created_at&order=created_at.desc`,
    {
      headers: {
        apikey: key,
        Authorization: `Bearer ${key}`,
      },
    },
  );

  if (!response.ok) {
    const text = await response.text();
    throw new Error(`Failed to list vault items (${response.status}): ${text}`);
  }

  return await response.json() as VaultItemRow[];
}

export async function getItemAccessToken(itemId: string): Promise<string | null> {
  const base = supabaseRestUrl();
  const key = serviceRoleKey();
  const response = await fetch(
    `${base}/rest/v1/plaid_item_vault?item_id=eq.${encodeURIComponent(itemId)}&removed_at=is.null&select=access_token`,
    {
      headers: {
        apikey: key,
        Authorization: `Bearer ${key}`,
      },
    },
  );

  if (!response.ok) {
    const text = await response.text();
    throw new Error(`Failed to load Plaid token (${response.status}): ${text}`);
  }

  const rows = await response.json() as Array<{ access_token: string }>;
  return rows[0]?.access_token ?? null;
}

export async function markItemAccessTokenRemoved(itemId: string): Promise<void> {
  const base = supabaseRestUrl();
  const key = serviceRoleKey();
  await fetch(`${base}/rest/v1/plaid_item_vault?item_id=eq.${encodeURIComponent(itemId)}`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
      apikey: key,
      Authorization: `Bearer ${key}`,
      Prefer: 'return=minimal',
    },
    body: JSON.stringify({ removed_at: new Date().toISOString() }),
  });
}
