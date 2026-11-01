// DEPRECATED: use Supabase Edge Functions instead (supabase/functions/).
// This local relay is no longer required for Family Bank.
require('dotenv').config();
const express = require('express');
const cors = require('cors');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { Configuration, PlaidApi, PlaidEnvironments, Products, CountryCode } = require('plaid');

const PORT = process.env.PORT || 3000;
const CLIENT_ID = process.env.PLAID_CLIENT_ID;
const SECRET = process.env.PLAID_SECRET;
const PLAID_ENV = process.env.PLAID_ENV || 'sandbox';
const ANDROID_PACKAGE = process.env.ANDROID_PACKAGE_NAME || 'com.family.bankapp';
const ITEM_LIMIT = parseInt(process.env.PLAID_ITEM_LIMIT || '10', 10);
const ITEMS_FILE = path.join(__dirname, 'items.json');
const API_SECRET = (process.env.FAMILY_APP_API_SECRET || process.env.API_SECRET || '').trim();
const ALLOW_DEPRECATED = process.env.ALLOW_DEPRECATED_PLAID_SERVER === 'true';

if (!ALLOW_DEPRECATED) {
  console.error(
    'Refusing to start deprecated plaid-server. Use Supabase Edge Functions.\n' +
      'Set ALLOW_DEPRECATED_PLAID_SERVER=true only for local emergency use.'
  );
  process.exit(1);
}

if (!CLIENT_ID || !SECRET) {
  console.error('Set PLAID_CLIENT_ID and PLAID_SECRET in .env (copy from .env.example)');
  process.exit(1);
}

if (API_SECRET.length < 16) {
  console.error(
    'Set FAMILY_APP_API_SECRET (or API_SECRET) to a value at least 16 characters. ' +
      'Unauthenticated plaid-server is not allowed.'
  );
  process.exit(1);
}

const plaid = new PlaidApi(
  new Configuration({
    basePath: PlaidEnvironments[PLAID_ENV],
    baseOptions: {
      headers: {
        'PLAID-CLIENT-ID': CLIENT_ID,
        'PLAID-SECRET': SECRET,
      },
    },
  })
);

function loadItems() {
  try {
    if (fs.existsSync(ITEMS_FILE)) {
      return JSON.parse(fs.readFileSync(ITEMS_FILE, 'utf8'));
    }
  } catch (e) {
    console.error('Failed to read items.json', e.message);
  }
  return { items: [] };
}

function saveItems(data) {
  fs.writeFileSync(ITEMS_FILE, JSON.stringify(data, null, 2));
}

function getUsage() {
  const data = loadItems();
  // Trial: count is lifetime — never decreases when Items are removed (matches Plaid billing docs)
  const used = data.items.length;
  return {
    limit: ITEM_LIMIT,
    used,
    remaining: Math.max(0, ITEM_LIMIT - used),
    at_limit: used >= ITEM_LIMIT,
    plan: PLAID_ENV === 'production' ? 'trial' : PLAID_ENV,
    trial_note: PLAID_ENV === 'production'
      ? 'Trial: 10 Items lifetime. Removing a bank does NOT free a slot.'
      : 'Sandbox — unlimited test Items.',
    items: data.items.map((i) => ({
      item_id: i.item_id,
      bank_label: i.bank_label || 'Unknown bank',
      connected_at: i.connected_at,
      removed: i.removed || false,
    })),
  };
}

function registerItem(itemId, bankLabel, replacingItemId) {
  const data = loadItems();
  if (replacingItemId) {
    const idx = data.items.findIndex((i) => i.item_id === replacingItemId);
    if (idx >= 0) {
      // Update in place — same registry slot (Plaid may still count new item on their side)
      data.items[idx] = {
        ...data.items[idx],
        item_id: itemId,
        bank_label: bankLabel || data.items[idx].bank_label,
        connected_at: new Date().toISOString(),
        removed: false,
      };
      saveItems(data);
      return;
    }
  }
  if (!data.items.find((i) => i.item_id === itemId)) {
    data.items.push({
      item_id: itemId,
      bank_label: bankLabel || 'Bank',
      connected_at: new Date().toISOString(),
      removed: false,
    });
    saveItems(data);
  }
}

/** Marks Item removed in Plaid but keeps registry entry — Trial slots are not freed. */
function markItemRemoved(itemId) {
  const data = loadItems();
  const item = data.items.find((i) => i.item_id === itemId);
  if (item) {
    item.removed = true;
    saveItems(data);
  }
}

function timingSafeEqualString(a, b) {
  const left = Buffer.from(String(a));
  const right = Buffer.from(String(b));
  if (left.length !== right.length) {
    crypto.timingSafeEqual(left, left);
    return false;
  }
  return crypto.timingSafeEqual(left, right);
}

function requireApiAuth(req, res, next) {
  const provided = String(
    req.get('x-family-bank-key') || req.get('authorization')?.replace(/^Bearer\s+/i, '') || ''
  ).trim();
  if (!provided || !timingSafeEqualString(provided, API_SECRET)) {
    return res.status(401).json({ error: 'Unauthorized' });
  }
  return next();
}

const app = express();
app.use(cors({ origin: false }));
app.use(express.json({ limit: '64kb' }));

// Health is the only unauthenticated route (no secrets / no Plaid data).
app.get('/health', (_req, res) => {
  res.json({ ok: true, env: PLAID_ENV, deprecated: true });
});

app.use(requireApiAuth);

/** Check Plaid item usage before connecting — call this before opening Link. */
app.get('/plaid/usage', (_req, res) => {
  res.json(getUsage());
});

app.post('/link/token/create', async (req, res) => {
  try {
    const usage = getUsage();
    const replacing = req.body.replacing_item_id;
    const isReplacement = replacing && usage.items.some((i) => i.item_id === replacing);

    if (usage.at_limit && !isReplacement) {
      return res.status(429).json({
        error: `Plaid Trial limit reached (${usage.used}/${usage.limit} lifetime Items). ` +
          'Upgrade to a paid plan to add more. Removing banks does NOT free Trial slots.',
        usage,
      });
    }

    const userId = req.body.user_id || 'family-bank-user';
    // Transactions — sync bank activity to auto-detect bill payments (manual bills stay the source of truth)
    const response = await plaid.linkTokenCreate({
      user: { client_user_id: userId },
      client_name: 'Family Bank',
      products: [Products.Transactions],
      transactions: { days_requested: 90 },
      country_codes: [CountryCode.Us],
      language: 'en',
      android_package_name: ANDROID_PACKAGE,
    });
    res.json({
      link_token: response.data.link_token,
      usage,
      warning: usage.remaining <= 2 && !isReplacement
        ? `Only ${usage.remaining} Trial slot(s) left (${usage.used}/${usage.limit} used). Each connection is permanent.`
        : null,
    });
  } catch (err) {
    console.error('link/token/create', err.response?.data || err.message);
    res.status(500).json({ error: err.response?.data?.error_message || err.message });
  }
});

app.post('/item/public_token/exchange', async (req, res) => {
  try {
    const { public_token, bank_label, replacing_item_id } = req.body;
    if (!public_token) return res.status(400).json({ error: 'public_token required' });

    const usage = getUsage();
    if (usage.at_limit && !replacing_item_id) {
      return res.status(429).json({
        error: `Plaid Trial limit reached (${usage.used}/${usage.limit}). Connection blocked.`,
        usage,
      });
    }

    const response = await plaid.itemPublicTokenExchange({ public_token });
    const itemId = response.data.item_id;

    registerItem(itemId, bank_label, replacing_item_id);

    // Plaid policy: never return access_token to clients.
    res.json({
      item_id: itemId,
      usage: getUsage(),
      note: 'access_token retained server-side only',
    });
  } catch (err) {
    console.error('public_token/exchange', err.response?.data || err.message);
    res.status(500).json({ error: err.response?.data?.error_message || err.message });
  }
});

app.post('/plaid/item/remove', async (req, res) => {
  try {
    const { access_token, item_id } = req.body;
    if (access_token) {
      await plaid.itemRemove({ access_token });
    }
    if (item_id) {
      markItemRemoved(item_id);
    }
    res.json({ ok: true, usage: getUsage(), note: 'Trial slots are not freed when Items are removed.' });
  } catch (err) {
    console.error('item/remove', err.response?.data || err.message);
    res.status(500).json({ error: err.response?.data?.error_message || err.message });
  }
});

app.post('/accounts/balance', async (req, res) => {
  try {
    const { access_token } = req.body;
    if (!access_token) return res.status(400).json({ error: 'access_token required' });
    const response = await plaid.accountsBalanceGet({ access_token });
    res.json({
      accounts: response.data.accounts.map((a) => ({
        account_id: a.account_id,
        name: a.name,
        official_name: a.official_name,
        type: a.type,
        subtype: a.subtype,
        mask: a.mask,
        balance_current: a.balances.current,
        balance_available: a.balances.available,
      })),
    });
  } catch (err) {
    console.error('accounts/balance', err.response?.data || err.message);
    res.status(500).json({ error: err.response?.data?.error_message || err.message });
  }
});

app.post('/transactions/sync', async (req, res) => {
  try {
    const { access_token, cursor } = req.body;
    if (!access_token) return res.status(400).json({ error: 'access_token required' });
    const response = await plaid.transactionsSync({
      access_token,
      cursor: cursor || undefined,
    });
    const { added, modified, removed, next_cursor, has_more } = response.data;
    res.json({
      added: added.map((t) => ({
        transaction_id: t.transaction_id,
        account_id: t.account_id,
        amount: t.amount,
        date: t.date,
        name: t.name,
        merchant_name: t.merchant_name,
        pending: t.pending,
      })),
      modified: modified.length,
      removed: removed.length,
      next_cursor,
      has_more,
    });
  } catch (err) {
    console.error('transactions/sync', err.response?.data || err.message);
    res.status(500).json({ error: err.response?.data?.error_message || err.message });
  }
});

app.listen(PORT, '127.0.0.1', () => {
  console.log(`DEPRECATED Family Bank Plaid server on http://127.0.0.1:${PORT} (${PLAID_ENV})`);
  console.log('All routes except /health require X-Family-Bank-Key.');
});
