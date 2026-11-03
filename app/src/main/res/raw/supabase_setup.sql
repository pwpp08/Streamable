create table if not exists public.user_media_state (
    user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    provider text not null,
    media_type text not null check (media_type in ('movie', 'tv_show', 'episode')),
    media_id text not null,
    parent_show_id text,
    parent_show_title text,
    parent_show_poster text,
    parent_show_banner text,
    season_id text,
    season_number integer,
    season_title text,
    season_poster text,
    episode_number integer,
    title text not null default '',
    poster text,
    banner text,
    is_favorite boolean not null default false,
    favorited_at_millis bigint,
    is_watched boolean not null default false,
    watched_at_millis bigint,
    last_engagement_at_millis bigint,
    playback_position_millis bigint,
    duration_millis bigint,
    is_watching boolean,
    last_played_at_millis bigint,
    last_played_episode_id text,
    client_updated_at_millis bigint not null,
    server_updated_at timestamptz not null default now(),
    primary key (user_id, provider, media_type, media_id)
);

create index if not exists user_media_state_user_updated_idx
    on public.user_media_state (user_id, server_updated_at desc);

alter table public.user_media_state enable row level security;

drop policy if exists "Users read their own media state" on public.user_media_state;
create policy "Users read their own media state"
    on public.user_media_state for select to authenticated
    using ((select auth.uid()) = user_id);

drop policy if exists "Users insert their own media state" on public.user_media_state;
create policy "Users insert their own media state"
    on public.user_media_state for insert to authenticated
    with check ((select auth.uid()) = user_id);

drop policy if exists "Users update their own media state" on public.user_media_state;
create policy "Users update their own media state"
    on public.user_media_state for update to authenticated
    using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);

drop policy if exists "Users delete their own media state" on public.user_media_state;
create policy "Users delete their own media state"
    on public.user_media_state for delete to authenticated
    using ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.user_media_state to authenticated;

create or replace function public.set_user_media_state_server_updated_at()
returns trigger language plpgsql security invoker set search_path = ''
as $$
begin
    if new.client_updated_at_millis < old.client_updated_at_millis then
        return null;
    end if;
    new.server_updated_at = now();
    return new;
end;
$$;

drop trigger if exists set_user_media_state_server_updated_at on public.user_media_state;
create trigger set_user_media_state_server_updated_at
before update on public.user_media_state
for each row execute function public.set_user_media_state_server_updated_at();

do $$
begin
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'user_media_state'
    ) then
        alter publication supabase_realtime add table public.user_media_state;
    end if;
end;
$$;

-- 20261103000000_devices_and_commands
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
