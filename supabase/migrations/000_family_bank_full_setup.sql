-- =============================================================================
-- Family Bank — FULL Supabase setup (run once in SQL Editor)
-- Project: paste entire file → Run
-- Safe to re-run: uses IF NOT EXISTS / CREATE OR REPLACE where possible
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Shared config + Plaid Item slot counters (no PII)
-- -----------------------------------------------------------------------------
create table if not exists family_app_config (
  team_id text primary key,
  plaid_item_limit int not null default 10 check (plaid_item_limit between 1 and 100),
  updated_at timestamptz not null default now()
);

create table if not exists plaid_connection_slots (
  id uuid primary key default gen_random_uuid(),
  team_id text not null references family_app_config (team_id) on delete cascade,
  registered_at timestamptz not null default now(),
  removed_at timestamptz null
);

create index if not exists idx_plaid_slots_team on plaid_connection_slots (team_id);

insert into family_app_config (team_id)
values ('family-bank')
on conflict (team_id) do nothing;

-- Item slot usage (read-only from app)
create or replace function get_plaid_usage(p_team_id text default 'family-bank')
returns json
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  v_limit int;
  v_used int;
begin
  select plaid_item_limit into v_limit
  from family_app_config
  where team_id = p_team_id;

  if v_limit is null then
    v_limit := 10;
  end if;

  select count(*)::int into v_used
  from plaid_connection_slots
  where team_id = p_team_id;

  return json_build_object(
    'team_id', p_team_id,
    'limit', v_limit,
    'used', v_used,
    'remaining', greatest(0, v_limit - v_used),
    'at_limit', v_used >= v_limit,
    'trial_note', 'Trial: slots are lifetime; removing a bank does not free a slot.'
  );
end;
$$;

-- Called from Edge Function after successful Plaid connect
create or replace function register_plaid_slot(p_team_id text default 'family-bank')
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
  v_limit int;
  v_used int;
  v_id uuid;
begin
  insert into family_app_config (team_id)
  values (p_team_id)
  on conflict (team_id) do nothing;

  select plaid_item_limit into v_limit
  from family_app_config
  where team_id = p_team_id;

  select count(*)::int into v_used
  from plaid_connection_slots
  where team_id = p_team_id;

  if v_used >= v_limit then
    return json_build_object(
      'ok', false,
      'error', 'limit_reached',
      'limit', v_limit,
      'used', v_used,
      'remaining', 0,
      'at_limit', true
    );
  end if;

  insert into plaid_connection_slots (team_id)
  values (p_team_id)
  returning id into v_id;

  return json_build_object(
    'ok', true,
    'slot_id', v_id,
    'limit', v_limit,
    'used', v_used + 1,
    'remaining', greatest(0, v_limit - v_used - 1),
    'at_limit', (v_used + 1) >= v_limit
  );
end;
$$;

create or replace function mark_plaid_slot_removed(p_slot_id uuid)
returns json
language plpgsql
security definer
set search_path = public
as $$
begin
  update plaid_connection_slots
  set removed_at = now()
  where id = p_slot_id and removed_at is null;

  return json_build_object('ok', found);
end;
$$;

-- -----------------------------------------------------------------------------
-- 2. Pooled Plaid API call budget (200/month default, resets UTC monthly)
-- -----------------------------------------------------------------------------
alter table family_app_config
  add column if not exists plaid_api_monthly_limit int not null default 200,
  add column if not exists plaid_api_calls_used int not null default 0,
  add column if not exists plaid_api_period_month date not null default (date_trunc('month', now() at time zone 'utc')::date);

create or replace function get_plaid_api_budget(p_team_id text default 'family-bank')
returns json
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  v_limit int;
  v_used int;
  v_period date;
  v_month date := date_trunc('month', now() at time zone 'utc')::date;
begin
  select plaid_api_monthly_limit, plaid_api_calls_used, plaid_api_period_month
  into v_limit, v_used, v_period
  from family_app_config
  where team_id = p_team_id;

  if v_limit is null then
    v_limit := 200;
    v_used := 0;
    v_period := v_month;
  elsif v_period is distinct from v_month then
    v_used := 0;
  end if;

  return json_build_object(
    'team_id', p_team_id,
    'limit', v_limit,
    'used', v_used,
    'remaining', greatest(0, v_limit - v_used),
    'at_limit', v_used >= v_limit,
    'period_month', v_month,
    'expected_items', 3,
    'note', 'Pooled across all Items (banks). Resets each UTC month.'
  );
end;
$$;

-- -----------------------------------------------------------------------------
-- 3. API call audit log + consume (Edge Functions only after step 5 revokes)
-- -----------------------------------------------------------------------------
create table if not exists plaid_api_call_log (
  id bigserial primary key,
  team_id text not null references family_app_config (team_id) on delete cascade,
  call_type text not null,
  calls int not null default 1 check (calls > 0),
  created_at timestamptz not null default now()
);

create index if not exists idx_plaid_api_call_log_team_month
  on plaid_api_call_log (team_id, created_at desc);

create or replace view plaid_api_budget_summary as
select
  team_id,
  plaid_api_monthly_limit as monthly_limit,
  plaid_api_calls_used as calls_used,
  greatest(0, plaid_api_monthly_limit - plaid_api_calls_used) as calls_remaining,
  plaid_api_period_month as period_month,
  updated_at
from family_app_config;

create or replace function consume_plaid_api_calls(
  p_team_id text default 'family-bank',
  p_calls int default 1,
  p_call_type text default 'transactions_sync'
)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
  v_limit int;
  v_used int;
  v_period date;
  v_month date := date_trunc('month', now() at time zone 'utc')::date;
  v_new_used int;
begin
  if p_calls < 1 then
    return json_build_object('ok', false, 'error', 'invalid_call_count');
  end if;

  insert into family_app_config (team_id)
  values (p_team_id)
  on conflict (team_id) do nothing;

  select plaid_api_monthly_limit, plaid_api_calls_used, plaid_api_period_month
  into v_limit, v_used, v_period
  from family_app_config
  where team_id = p_team_id
  for update;

  if v_period is distinct from v_month then
    v_used := 0;
    v_period := v_month;
  end if;

  if v_used + p_calls > v_limit then
    return json_build_object(
      'ok', false,
      'error', 'api_budget_exhausted',
      'call_type', p_call_type,
      'limit', v_limit,
      'used', v_used,
      'remaining', greatest(0, v_limit - v_used),
      'requested', p_calls
    );
  end if;

  v_new_used := v_used + p_calls;

  update family_app_config
  set plaid_api_calls_used = v_new_used,
      plaid_api_period_month = v_month,
      updated_at = now()
  where team_id = p_team_id;

  insert into plaid_api_call_log (team_id, call_type, calls)
  values (p_team_id, p_call_type, p_calls);

  return json_build_object(
    'ok', true,
    'call_type', p_call_type,
    'limit', v_limit,
    'used', v_new_used,
    'remaining', greatest(0, v_limit - v_new_used),
    'period_month', v_month
  );
end;
$$;

-- -----------------------------------------------------------------------------
-- 4. Plaid access token vault (server-side only — Plaid compliance)
-- -----------------------------------------------------------------------------
create table if not exists plaid_item_vault (
  item_id text primary key,
  team_id text not null references family_app_config (team_id) on delete cascade,
  access_token text not null,
  created_at timestamptz not null default now(),
  removed_at timestamptz null
);

create index if not exists idx_plaid_item_vault_team on plaid_item_vault (team_id);

-- -----------------------------------------------------------------------------
-- 5. Row-level security + permissions
-- -----------------------------------------------------------------------------
alter table family_app_config enable row level security;
alter table plaid_connection_slots enable row level security;
alter table plaid_api_call_log enable row level security;
alter table plaid_item_vault enable row level security;

drop policy if exists "anon_read_config" on family_app_config;
create policy "anon_read_config"
  on family_app_config for select to anon using (true);

drop policy if exists "anon_read_slots" on plaid_connection_slots;
create policy "anon_read_slots"
  on plaid_connection_slots for select to anon using (true);

drop policy if exists "anon_read_api_call_log" on plaid_api_call_log;
create policy "anon_read_api_call_log"
  on plaid_api_call_log for select to anon using (true);

-- Token vault: no client access (Edge Functions use service_role)
revoke all on table plaid_item_vault from anon, authenticated;
grant all on table plaid_item_vault to service_role;

grant select on plaid_api_budget_summary to anon, authenticated;

-- App may READ counters only
grant execute on function get_plaid_usage(text) to anon, authenticated;
grant execute on function get_plaid_api_budget(text) to anon, authenticated;

-- Writes only via Edge Functions (service role)
revoke execute on function register_plaid_slot(text) from anon, authenticated;
revoke execute on function mark_plaid_slot_removed(uuid) from anon, authenticated;
revoke execute on function consume_plaid_api_calls(text, int, text) from anon, authenticated;

-- -----------------------------------------------------------------------------
-- 6. Verify (optional — should return JSON with used/limit)
-- -----------------------------------------------------------------------------
select get_plaid_usage('family-bank');
select get_plaid_api_budget('family-bank');
select * from plaid_api_budget_summary;
