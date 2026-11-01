import { corsHeaders } from './http.ts';

const MIN_SECRET_LENGTH = 16;

/** Constant-time string compare for API secrets. */
function timingSafeEqual(a: string, b: string): boolean {
  const enc = new TextEncoder();
  const left = enc.encode(a);
  const right = enc.encode(b);
  const len = Math.max(left.length, right.length);
  let diff = left.length ^ right.length;
  for (let i = 0; i < len; i++) {
    diff |= (left[i] ?? 0) ^ (right[i] ?? 0);
  }
  return diff === 0;
}

function unauthorized(message: string): Response {
  return new Response(JSON.stringify({ error: message }), {
    status: 401,
    headers: { ...corsHeaders, 'Content-Type': 'application/json' },
  });
}

/**
 * Require X-Family-Bank-Key matching FAMILY_APP_API_SECRET.
 * Fails closed when the server secret is missing or too short.
 */
export function verifyAppRequest(req: Request): Response | null {
  const expected = Deno.env.get('FAMILY_APP_API_SECRET')?.trim() ?? '';
  if (expected.length < MIN_SECRET_LENGTH) {
    console.error(
      'FAMILY_APP_API_SECRET is missing or shorter than 16 characters — rejecting all requests',
    );
    return unauthorized('Server API auth is not configured');
  }

  const provided = req.headers.get('x-family-bank-key')?.trim() ?? '';
  if (!provided || !timingSafeEqual(provided, expected)) {
    return unauthorized('Unauthorized');
  }
  return null;
}
