export const corsHeaders: Record<string, string> = {
  // Native Android clients do not use CORS; keep browser access locked down.
  'Access-Control-Allow-Origin': 'null',
  'Access-Control-Allow-Headers':
    'authorization, x-client-info, apikey, content-type, x-family-bank-key',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, 'Content-Type': 'application/json' },
  });
}

export function errorResponse(status: number, message: string, extra: Record<string, unknown> = {}): Response {
  return jsonResponse({ error: message, ...extra }, status);
}

export async function readJson<T extends Record<string, unknown>>(req: Request): Promise<T> {
  try {
    return (await req.json()) as T;
  } catch {
    return {} as T;
  }
}
