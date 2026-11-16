-- Field Twin tickets: work tickets read from a photo of the work phone, with their SLA due time, so every phone shows the same countdowns.
create table if not exists public.field_twin_tickets (
  id text primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  ticket text not null default '',
  site text not null default '',
  address text not null default '',
  place_id text,
  lat double precision,
  lon double precision,
  priority text not null default '',
  summary text not null default '',
  opened_at timestamptz,
  due_at timestamptz,
  status text not null default 'open',
  closed_at timestamptz,
  made_sla boolean,
  points integer not null default 0,
  updated_at timestamptz not null default now()
);
alter table public.field_twin_tickets enable row level security;
drop policy if exists field_twin_tickets_own on public.field_twin_tickets;
create policy field_twin_tickets_own on public.field_twin_tickets for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
grant select, insert, update, delete on public.field_twin_tickets to authenticated;
