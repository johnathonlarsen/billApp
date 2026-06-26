-- Fix get_plaid_api_budget: STABLE functions cannot INSERT/UPDATE.
-- Run this if 000_family_bank_full_setup.sql failed partway, or after 003.

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

grant execute on function get_plaid_api_budget(text) to anon, authenticated;

select get_plaid_api_budget('family-bank');
