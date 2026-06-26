-- Reset shared Plaid connection slots and server-side tokens for family-bank.
-- Run: npx supabase@2.108.0 db query --linked -f supabase/scripts/reset-plaid-slots.sql

delete from plaid_item_vault
where team_id = 'family-bank';

delete from plaid_connection_slots
where team_id = 'family-bank';

select get_plaid_usage('family-bank');
