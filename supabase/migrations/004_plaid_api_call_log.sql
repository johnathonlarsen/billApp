-- Call log + summary view for Plaid API budget (run after 003_plaid_api_budget.sql)

create table if not exists plaid_api_call_log (
  id bigserial primary key,
  team_id text not null references family_app_config (team_id) on delete cascade,
  call_type text not null,
  calls int not null default 1 check (calls > 0),
  created_at timestamptz not null default now()
);

create index if not exists idx_plaid_api_call_log_team_month
  on plaid_api_call_log (team_id, created_at desc);

-- Easy read in Supabase Table Editor
create or replace view plaid_api_budget_summary as
select
  team_id,
  plaid_api_monthly_limit as monthly_limit,
  plaid_api_calls_used as calls_used,
  greatest(0, plaid_api_monthly_limit - plaid_api_calls_used) as calls_remaining,
  plaid_api_period_month as period_month,
  updated_at
from family_app_config;

grant select on plaid_api_budget_summary to anon, authenticated;

alter table plaid_api_call_log enable row level security;

create policy "anon_read_api_call_log"
  on plaid_api_call_log for select to anon using (true);

-- Log each consumed call (called from consume_plaid_api_calls)
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

grant execute on function consume_plaid_api_calls(text, int, text) to anon, authenticated;
