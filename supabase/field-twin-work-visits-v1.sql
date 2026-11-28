-- Field Twin work visits: every time the phone sits at a known locker it is logged here on its own, no ticket needed.
-- One row per visit: which locker, when you got there, when you left, minutes on site, whether you are there now,
-- and the ticket it lines up with when one is open at that locker.
create table if not exists public.field_twin_work_visits (
  id text primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  place_id text,
  locker text not null default '',
  address text not null default '',
  lat double precision,
  lon double precision,
  arrived_at timestamptz not null,
  left_at timestamptz,
  minutes integer not null default 0,
  on_site boolean not null default false,
  ticket_id text,
  ticket text not null default '',
  source text not null default 'auto',
  updated_at timestamptz not null default now()
);
create index if not exists field_twin_work_visits_user_time on public.field_twin_work_visits (user_id, arrived_at desc);
alter table public.field_twin_work_visits enable row level security;
drop policy if exists field_twin_work_visits_own on public.field_twin_work_visits;
create policy field_twin_work_visits_own on public.field_twin_work_visits for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
grant select, insert, update, delete on public.field_twin_work_visits to authenticated;
notify pgrst, 'reload schema';
