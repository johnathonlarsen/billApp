-- Pooled Plaid API call budget (household-wide, resets each calendar month UTC)
-- Counts billable product calls (transactions/sync pages). Link token + exchange are not counted.

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
    'note', 'Pooled across all Items (banks). Sandbox/Trial — keep sync infrequent.'
  );
end;
$$;

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

grant execute on function get_plaid_api_budget(text) to anon, authenticated;
grant execute on function consume_plaid_api_calls(text, int, text) to anon, authenticated;
