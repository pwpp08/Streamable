-- Settings that follow the account to every device (theme, home rows, languages, playback).
-- Run once in the Supabase SQL editor. Safe to run again.

create table if not exists public.user_settings (
    user_id uuid primary key references auth.users (id) on delete cascade,
    settings jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now()
);

alter table public.user_settings enable row level security;

drop policy if exists "Users read their own settings" on public.user_settings;
create policy "Users read their own settings"
    on public.user_settings for select to authenticated
    using ((select auth.uid()) = user_id);

drop policy if exists "Users insert their own settings" on public.user_settings;
create policy "Users insert their own settings"
    on public.user_settings for insert to authenticated
    with check ((select auth.uid()) = user_id);

drop policy if exists "Users update their own settings" on public.user_settings;
create policy "Users update their own settings"
    on public.user_settings for update to authenticated
    using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);

drop policy if exists "Users delete their own settings" on public.user_settings;
create policy "Users delete their own settings"
    on public.user_settings for delete to authenticated
    using ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.user_settings to authenticated;
