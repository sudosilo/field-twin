-- Field Twin photos: any number of photos per entry or per locker. The image files live in the photos storage
-- bucket under your own folder, and this table lists each one with the entry or locker it belongs to.
create table if not exists public.field_twin_photos (
  id text primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  entry_id text,
  place_id text,
  path text not null,
  lat double precision,
  lon double precision,
  ts timestamptz not null default now()
);
create index if not exists field_twin_photos_entry on public.field_twin_photos (entry_id);
create index if not exists field_twin_photos_place on public.field_twin_photos (place_id);
alter table public.field_twin_photos enable row level security;
drop policy if exists field_twin_photos_own on public.field_twin_photos;
create policy field_twin_photos_own on public.field_twin_photos for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
grant select, insert, update, delete on public.field_twin_photos to authenticated;
