-- Fix: get_plaid_usage must not INSERT (STABLE functions cannot write).
-- Run this in Supabase SQL Editor after 001_family_app_shared_state.sql

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

grant execute on function get_plaid_usage(text) to anon, authenticated;
