-- Plaid compliance: access tokens MUST stay server-side (service role only).
-- Also revoke client write RPCs — only Edge Functions (service role) may mutate counters/tokens.

create table if not exists plaid_item_vault (
  item_id text primary key,
  team_id text not null references family_app_config (team_id) on delete cascade,
  access_token text not null,
  created_at timestamptz not null default now(),
  removed_at timestamptz null
);

create index if not exists idx_plaid_item_vault_team on plaid_item_vault (team_id);

alter table plaid_item_vault enable row level security;
-- No policies: anon/authenticated cannot read or write tokens (service role only via Edge Functions).

revoke all on table plaid_item_vault from anon, authenticated;
grant all on table plaid_item_vault to service_role;

-- Client may READ usage/budget only; writes happen inside Edge Functions.
revoke execute on function register_plaid_slot(text) from anon, authenticated;
revoke execute on function mark_plaid_slot_removed(uuid) from anon, authenticated;
revoke execute on function consume_plaid_api_calls(text, int, text) from anon, authenticated;

-- Read-only for app (shared counters, no secrets)
grant execute on function get_plaid_usage(text) to anon, authenticated;
grant execute on function get_plaid_api_budget(text) to anon, authenticated;
