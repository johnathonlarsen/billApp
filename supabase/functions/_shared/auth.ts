/** Optional shared secret so Edge Functions are not open to arbitrary anon callers. */
export function verifyAppRequest(req: Request): Response | null {
  const expected = Deno.env.get('FAMILY_APP_API_SECRET')?.trim();
  if (!expected) return null;

  const provided = req.headers.get('x-family-bank-key')?.trim();
  if (provided !== expected) {
    return new Response(JSON.stringify({ error: 'Unauthorized' }), {
      status: 401,
      headers: { 'Content-Type': 'application/json' },
    });
  }
  return null;
}
