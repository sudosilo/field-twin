-- Hire Portal applications from the Pajamastan careers site, kept in the shared Supabase project.
-- Applicants send without signing in and cannot read anything back. Only Hire Portal admins can read.
create table if not exists public.hire_portal_admins (
  user_id uuid primary key references auth.users(id) on delete cascade,
  added_at timestamptz not null default now()
);
alter table public.hire_portal_admins enable row level security;
drop policy if exists hire_portal_admins_self on public.hire_portal_admins;
create policy hire_portal_admins_self on public.hire_portal_admins for select to authenticated using (user_id = auth.uid());
grant select on public.hire_portal_admins to authenticated;
-- Admins join by signing in and entering the owner code once. Only the code's hash lives here.
delete from public.hire_portal_admins where added_at < '2026-11-24 00:15:40+00';
create or replace function public.hire_portal_claim_admin(code text)
returns boolean language plpgsql security definer set search_path = public, extensions as $$
begin
  if auth.uid() is null then return false; end if;
  if encode(extensions.digest(code, 'sha256'), 'hex') <> 'c3ec47944237b7404b22c92c594ae561dad753e07bafbf2d995c316a433ba6ed' then return false; end if;
  insert into public.hire_portal_admins (user_id) values (auth.uid()) on conflict do nothing;
  return true;
end $$;
revoke all on function public.hire_portal_claim_admin(text) from public;
grant execute on function public.hire_portal_claim_admin(text) to authenticated;

create table if not exists public.hire_portal_applications (
  id text primary key,
  created_at timestamptz not null default now(),
  role text not null default '',
  name text not null default '',
  account_email text not null default '',
  human_check text not null default '',
  profile jsonb not null
);
alter table public.hire_portal_applications enable row level security;
drop policy if exists hire_portal_applications_send on public.hire_portal_applications;
create policy hire_portal_applications_send on public.hire_portal_applications for insert to anon, authenticated
  with check (octet_length(profile::text) < 3000000 and id ~ '^MTG-[A-Z0-9-]{6,40}$');
drop policy if exists hire_portal_applications_admin on public.hire_portal_applications;
create policy hire_portal_applications_admin on public.hire_portal_applications for select to authenticated
  using (exists (select 1 from public.hire_portal_admins a where a.user_id = auth.uid()));
drop policy if exists hire_portal_applications_admin_delete on public.hire_portal_applications;
create policy hire_portal_applications_admin_delete on public.hire_portal_applications for delete to authenticated
  using (exists (select 1 from public.hire_portal_admins a where a.user_id = auth.uid()));
grant insert on public.hire_portal_applications to anon, authenticated;
grant select, delete on public.hire_portal_applications to authenticated;

-- The badge photo is chosen after the application is sent, so it goes in through this one narrow door.
create or replace function public.hire_portal_set_photo(app_id text, photo text)
returns void language sql security definer set search_path = public as $$
  update public.hire_portal_applications
     set profile = jsonb_set(profile, '{photo}', to_jsonb(photo))
   where id = app_id and created_at > now() - interval '1 day' and octet_length(photo) < 1500000;
$$;
revoke all on function public.hire_portal_set_photo(text, text) from public;
grant execute on function public.hire_portal_set_photo(text, text) to anon, authenticated;

delete from public.hire_portal_applications where name = 'Claude Test Run';
select (select count(*) from public.hire_portal_admins) as admins, (select count(*) from public.hire_portal_applications) as applications;
