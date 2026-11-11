-- Field Twin places: named lockers plus spots you drove to or picked in GO, so GO can rank them first on every phone.
create table if not exists public.field_twin_places (
  id text primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  kind text not null default 'place',
  name text not null default '',
  lat double precision not null,
  lon double precision not null,
  address text not null default '',
  notes text not null default '',
  arrivals integer not null default 0,
  searches integer not null default 0,
  last_at timestamptz,
  updated_at timestamptz not null default now()
);
alter table public.field_twin_places enable row level security;
drop policy if exists field_twin_places_own on public.field_twin_places;
create policy field_twin_places_own on public.field_twin_places for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
grant select, insert, update, delete on public.field_twin_places to authenticated;
alter table public.field_twin_places add column if not exists source text;
alter table public.field_twin_places add column if not exists site jsonb;
