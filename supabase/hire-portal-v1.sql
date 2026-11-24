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
-- Every account that already exists in this project is an owner account, so each one becomes a Hire Portal admin.
insert into public.hire_portal_admins (user_id) select id from auth.users on conflict do nothing;

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

select (select count(*) from public.hire_portal_admins) as admins, (select count(*) from public.hire_portal_applications) as applications;
