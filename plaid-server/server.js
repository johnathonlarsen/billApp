require('dotenv').config();
const express = require('express');
const cors = require('express').Router;
const { Configuration, PlaidApi, PlaidEnvironments, Products, CountryCode } = require('plaid');

const PORT = process.env.PORT || 3000;
const CLIENT_ID = process.env.PLAID_CLIENT_ID;
const SECRET = process.env.PLAID_SECRET;
const PLAID_ENV = process.env.PLAID_ENV || 'sandbox';
const ANDROID_PACKAGE = process.env.ANDROID_PACKAGE_NAME || 'com.family.bankapp';

if (!CLIENT_ID || !SECRET) {
  console.error('Set PLAID_CLIENT_ID and PLAID_SECRET in .env (copy from .env.example)');
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

const app = express();
app.use(require('cors')());
app.use(express.json());

app.get('/health', (_req, res) => {
  res.json({ ok: true, env: PLAID_ENV });
});

app.post('/link/token/create', async (req, res) => {
  try {
    const userId = req.body.user_id || 'family-bank-user';
    const response = await plaid.linkTokenCreate({
      user: { client_user_id: userId },
      client_name: 'Family Bank',
      products: [Products.Transactions, Products.Auth],
      transactions: { days_requested: 90 },
      country_codes: [CountryCode.Us],
      language: 'en',
      android_package_name: ANDROID_PACKAGE,
    });
    res.json({ link_token: response.data.link_token });
  } catch (err) {
    console.error('link/token/create', err.response?.data || err.message);
    res.status(500).json({ error: err.response?.data?.error_message || err.message });
  }
});

app.post('/item/public_token/exchange', async (req, res) => {
  try {
    const { public_token } = req.body;
    if (!public_token) return res.status(400).json({ error: 'public_token required' });
    const response = await plaid.itemPublicTokenExchange({ public_token });
    res.json({
      access_token: response.data.access_token,
      item_id: response.data.item_id,
    });
  } catch (err) {
    console.error('public_token/exchange', err.response?.data || err.message);
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

app.listen(PORT, '0.0.0.0', () => {
  console.log(`Family Bank Plaid server on http://0.0.0.0:${PORT} (${PLAID_ENV})`);
});
