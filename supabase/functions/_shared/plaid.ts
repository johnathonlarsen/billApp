import { Configuration, PlaidApi, PlaidEnvironments, Products, CountryCode } from 'npm:plaid@30.0.0';

const ALLOWED_PLAID_ENVS = ['production'] as const;

export function getPlaidEnv(): string {
  const envName = (Deno.env.get('PLAID_ENV') ?? 'production').toLowerCase();
  if (!ALLOWED_PLAID_ENVS.includes(envName as (typeof ALLOWED_PLAID_ENVS)[number])) {
    throw new Error(
      `PLAID_ENV must be production (got "${envName}"). Sandbox is disabled for this app.`,
    );
  }
  return envName;
}

export function createPlaidClient(): PlaidApi {
  const clientId = Deno.env.get('PLAID_CLIENT_ID');
  const secret = Deno.env.get('PLAID_SECRET');
  const envName = getPlaidEnv();

  if (!clientId || !secret) {
    throw new Error('Set PLAID_CLIENT_ID and PLAID_SECRET in Supabase Edge Function secrets');
  }

  const basePath = PlaidEnvironments[envName as keyof typeof PlaidEnvironments];
  if (!basePath) {
    throw new Error(`Invalid PLAID_ENV: ${envName}`);
  }

  return new PlaidApi(
    new Configuration({
      basePath,
      baseOptions: {
        headers: {
          'PLAID-CLIENT-ID': clientId,
          'PLAID-SECRET': secret,
        },
      },
    }),
  );
}

export function getAndroidPackage(): string {
  return Deno.env.get('ANDROID_PACKAGE_NAME') ?? 'com.family.bankapp';
}

export function getTeamId(override?: string): string {
  return (override ?? Deno.env.get('PLAID_TEAM_ID') ?? 'family-bank').trim() || 'family-bank';
}

export { Products, CountryCode };
