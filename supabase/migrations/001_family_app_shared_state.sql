-- Family Bank shared state (no PII)
-- Run in your Supabase dev project: SQL Editor → New query → paste → Run
--
-- Stores only: team config + anonymous Plaid slot counters (UUID + timestamp).
-- No bank names, access tokens, Plaid item_ids, or user identifiers.

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

-- Lifetime slot count (removed_at does NOT free a Trial slot)
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

-- Call after a successful Plaid connection (from app or Edge Function)
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

-- Mark removed in app/Plaid terms; row stays for lifetime count
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

alter table family_app_config enable row level security;
alter table plaid_connection_slots enable row level security;

create policy "anon_read_config"
  on family_app_config for select to anon using (true);

create policy "anon_read_slots"
  on plaid_connection_slots for select to anon using (true);

grant execute on function get_plaid_usage(text) to anon, authenticated;
grant execute on function register_plaid_slot(text) to anon, authenticated;
grant execute on function mark_plaid_slot_removed(uuid) to anon, authenticated;
