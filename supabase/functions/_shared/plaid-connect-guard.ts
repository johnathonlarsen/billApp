import { createPlaidClient } from './plaid.ts';
import { listActiveVaultItems, VaultItemRow } from './vault.ts';

export type OrphanVaultItem = {
  item_id: string;
  institution_name: string;
};

export function parseLinkedItemIds(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value
    .filter((id): id is string => typeof id === 'string')
    .map((id) => id.trim())
    .filter(Boolean);
}

export async function findOrphanVaultItems(
  teamId: string,
  linkedItemIds: string[],
): Promise<VaultItemRow[]> {
  const linked = new Set(linkedItemIds);
  const vault = await listActiveVaultItems(teamId);
  return vault.filter((row) => !linked.has(row.item_id));
}

async function enrichOrphans(orphans: VaultItemRow[]): Promise<OrphanVaultItem[]> {
  if (orphans.length === 0) return [];
  const plaid = createPlaidClient();
  return await Promise.all(
    orphans.map(async (row) => {
      try {
        const item = await plaid.itemGet({ access_token: row.access_token });
        return {
          item_id: row.item_id,
          institution_name: item.data.item.institution_name ?? 'Unknown bank',
        };
      } catch {
        return { item_id: row.item_id, institution_name: 'Unknown bank' };
      }
    }),
  );
}

export async function getOrphanConnectBlock(
  teamId: string,
  linkedItemIds: string[],
  replacingItemId?: string,
): Promise<{ error: string; orphans: OrphanVaultItem[] } | null> {
  if (replacingItemId) return null;

  const orphans = await findOrphanVaultItems(teamId, linkedItemIds);
  if (orphans.length === 0) return null;

  const details = await enrichOrphans(orphans);
  const names = details.map((item) => item.institution_name).join(', ');
  return {
    error:
      `Saved Plaid link(s) on the server are not restored on this phone yet (${names}). ` +
      'Use Restore saved link before Connect — otherwise you may burn a Trial slot.',
    orphans: details,
  };
}

export async function rejectDuplicateInstitutionConnect(
  teamId: string,
  newItemId: string,
  newAccessToken: string,
  replacingItemId?: string,
): Promise<{ error: string; institution_name: string } | null> {
  const plaid = createPlaidClient();
  const newItem = await plaid.itemGet({ access_token: newAccessToken });
  const newInstitutionId = newItem.data.item.institution_id;
  const newInstitutionName = newItem.data.item.institution_name ?? 'this bank';

  const vault = await listActiveVaultItems(teamId);
  for (const row of vault) {
    if (row.item_id === newItemId) continue;
    if (replacingItemId && row.item_id === replacingItemId) continue;

    try {
      const existing = await plaid.itemGet({ access_token: row.access_token });
      if (existing.data.item.institution_id === newInstitutionId) {
        await plaid.itemRemove({ access_token: newAccessToken });
        return {
          error:
            `A saved Plaid link for ${newInstitutionName} already exists. ` +
            'Restore the saved link instead of connecting again.',
          institution_name: newInstitutionName,
        };
      }
    } catch {
      // Ignore broken vault rows; orphan restore flow handles them separately.
    }
  }

  return null;
}
