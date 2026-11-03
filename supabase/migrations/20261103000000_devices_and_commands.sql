-- Devices signed in to the same account, and commands sent between them
-- (phone -> TV "play on TV", remote control, search). Idempotent.

create table if not exists public.user_devices (
    id text not null,
    user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    name text not null default '',
    kind text not null check (kind in ('tv', 'phone', 'tablet')),
    last_seen_at timestamptz not null default now(),
    primary key (user_id, id)
);

alter table public.user_devices enable row level security;

drop policy if exists "Users read their own devices" on public.user_devices;
create policy "Users read their own devices"
    on public.user_devices for select to authenticated
    using ((select auth.uid()) = user_id);

drop policy if exists "Users insert their own devices" on public.user_devices;
create policy "Users insert their own devices"
    on public.user_devices for insert to authenticated
    with check ((select auth.uid()) = user_id);

drop policy if exists "Users update their own devices" on public.user_devices;
create policy "Users update their own devices"
    on public.user_devices for update to authenticated
    using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);

drop policy if exists "Users delete their own devices" on public.user_devices;
create policy "Users delete their own devices"
    on public.user_devices for delete to authenticated
    using ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.user_devices to authenticated;

create table if not exists public.device_commands (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    target_device_id text not null,
    source_device_id text,
    kind text not null,
    payload jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    consumed_at timestamptz
);

create index if not exists device_commands_target_idx
    on public.device_commands (user_id, target_device_id, consumed_at);

alter table public.device_commands enable row level security;

drop policy if exists "Users read their own commands" on public.device_commands;
create policy "Users read their own commands"
    on public.device_commands for select to authenticated
    using ((select auth.uid()) = user_id);

drop policy if exists "Users insert their own commands" on public.device_commands;
create policy "Users insert their own commands"
    on public.device_commands for insert to authenticated
    with check ((select auth.uid()) = user_id);

drop policy if exists "Users update their own commands" on public.device_commands;
create policy "Users update their own commands"
    on public.device_commands for update to authenticated
    using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);

drop policy if exists "Users delete their own commands" on public.device_commands;
create policy "Users delete their own commands"
    on public.device_commands for delete to authenticated
    using ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.device_commands to authenticated;

do $$
begin
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'device_commands'
    ) then
        alter publication supabase_realtime add table public.device_commands;
    end if;
end;
$$;

alter table public.user_media_state add column if not exists last_device_id text;
