-- Hub View shared parts library. Finished builds are readable by anyone.
-- Phones can queue a job and upload its photo; the worker does everything else with the service key.
create table if not exists public.hv_builds (
  key text primary key,
  name text,
  maker text,
  exact_model text,
  kind text,
  status text not null default 'draft',
  data jsonb,
  ident jsonb,
  extracted jsonb,
  verify jsonb,
  pages_url text,
  diagrams jsonb,
  photo_url text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create table if not exists public.hv_jobs (
  id uuid primary key default gen_random_uuid(),
  key text not null,
  status text not null default 'queued',
  stage text not null default 'queued',
  note text,
  ident jsonb,
  photo_path text,
  error text,
  attempts int not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists hv_jobs_status_idx on public.hv_jobs (status, created_at);
create index if not exists hv_builds_updated_idx on public.hv_builds (updated_at desc);

alter table public.hv_builds enable row level security;
alter table public.hv_jobs enable row level security;

do $$ begin
  drop policy if exists hv_builds_read on public.hv_builds;
  create policy hv_builds_read on public.hv_builds for select to anon, authenticated using (true);
  drop policy if exists hv_jobs_read on public.hv_jobs;
  create policy hv_jobs_read on public.hv_jobs for select to anon, authenticated using (true);
  drop policy if exists hv_jobs_queue on public.hv_jobs;
  create policy hv_jobs_queue on public.hv_jobs for insert to anon, authenticated
    with check (status = 'queued' and stage = 'queued' and attempts = 0 and error is null and length(key) between 3 and 80);
end $$;

grant select on public.hv_builds to anon, authenticated;
grant select, insert on public.hv_jobs to anon, authenticated;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('hv-files', 'hv-files', true, 52428800, array['image/jpeg', 'image/png', 'application/pdf'])
on conflict (id) do update set public = true, file_size_limit = 52428800, allowed_mime_types = array['image/jpeg', 'image/png', 'application/pdf'];

do $$ begin
  drop policy if exists hv_photo_upload on storage.objects;
  create policy hv_photo_upload on storage.objects for insert to anon, authenticated
    with check (bucket_id = 'hv-files' and (storage.foldername(name))[1] = 'photos' and lower(right(name, 4)) = '.jpg');
end $$;
