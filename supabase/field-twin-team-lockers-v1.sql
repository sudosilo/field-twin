-- Field Twin team lockers: the locker list everyone on Field Twin can load. Each person adds and updates only
-- their own rows; anyone signed in can read all of them. Only the name, spot and address are shared, never notes.
create table if not exists public.field_twin_team_lockers (
  id text primary key,
  owner uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text not null default '',
  lat double precision not null,
  lon double precision not null,
  address text not null default '',
  updated_at timestamptz not null default now()
);
alter table public.field_twin_team_lockers enable row level security;
drop policy if exists field_twin_team_lockers_read on public.field_twin_team_lockers;
create policy field_twin_team_lockers_read on public.field_twin_team_lockers for select to authenticated using (true);
drop policy if exists field_twin_team_lockers_write on public.field_twin_team_lockers;
create policy field_twin_team_lockers_write on public.field_twin_team_lockers for all to authenticated using (owner = auth.uid()) with check (owner = auth.uid());
grant select, insert, update, delete on public.field_twin_team_lockers to authenticated;
-- Locker spots are public places, so the list can also be read without signing in. That lets a Field Twin copy
-- that signs into a different Supabase project still preload this list.
drop policy if exists field_twin_team_lockers_public on public.field_twin_team_lockers;
create policy field_twin_team_lockers_public on public.field_twin_team_lockers for select to anon using (true);
-- Without signing in, only the locker itself shows: never who added it or when it was last shared.
revoke select on public.field_twin_team_lockers from anon;
grant select (id, name, lat, lon, address) on public.field_twin_team_lockers to anon;
