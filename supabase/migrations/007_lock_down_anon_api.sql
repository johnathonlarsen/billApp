-- Lock down all PostgREST access for anon/authenticated.
-- Shared state reads go through authenticated Edge Function plaid-status (service role).
-- Writes already require service role via Edge Functions.

-- Drop permissive SELECT policies
drop policy if exists "anon_read_config" on family_app_config;
drop policy if exists "anon_read_slots" on plaid_connection_slots;
drop policy if exists "anon_read_api_call_log" on plaid_api_call_log;

-- No policies = deny for anon/authenticated (RLS already enabled). Keep vault locked.
revoke all on table family_app_config from anon, authenticated;
revoke all on table plaid_connection_slots from anon, authenticated;
revoke all on table plaid_api_call_log from anon, authenticated;
revoke all on table plaid_item_vault from anon, authenticated;

grant all on table family_app_config to service_role;
grant all on table plaid_connection_slots to service_role;
grant all on table plaid_api_call_log to service_role;
grant all on table plaid_item_vault to service_role;

revoke select on plaid_api_budget_summary from anon, authenticated;
grant select on plaid_api_budget_summary to service_role;

-- RPCs: service role only
revoke execute on function get_plaid_usage(text) from anon, authenticated, public;
revoke execute on function get_plaid_api_budget(text) from anon, authenticated, public;
revoke execute on function register_plaid_slot(text) from anon, authenticated, public;
revoke execute on function mark_plaid_slot_removed(uuid) from anon, authenticated, public;
revoke execute on function consume_plaid_api_calls(text, int, text) from anon, authenticated, public;

grant execute on function get_plaid_usage(text) to service_role;
grant execute on function get_plaid_api_budget(text) to service_role;
grant execute on function register_plaid_slot(text) to service_role;
grant execute on function mark_plaid_slot_removed(uuid) to service_role;
grant execute on function consume_plaid_api_calls(text, int, text) to service_role;
