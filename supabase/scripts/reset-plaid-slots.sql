-- Reset shared Plaid counters for production fresh start (family-bank).
-- Run: npx supabase@2.108.0 db query --linked -f supabase/scripts/reset-plaid-slots.sql

delete from plaid_item_vault
where team_id = 'family-bank';

delete from plaid_connection_slots
where team_id = 'family-bank';

delete from plaid_api_call_log
where team_id = 'family-bank';

update family_app_config
set
  plaid_api_calls_used = 0,
  plaid_api_period_month = date_trunc('month', now() at time zone 'utc')::date,
  updated_at = now()
where team_id = 'family-bank';

select get_plaid_usage('family-bank') as item_slots;
select get_plaid_api_budget('family-bank') as api_budget;
