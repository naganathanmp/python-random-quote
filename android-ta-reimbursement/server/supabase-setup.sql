-- TA Reimburse v2.0 - team sync server (Supabase / PostgreSQL)
-- Run once in your Supabase project: Dashboard > SQL Editor > New query > paste > Run.
-- Safe to re-run. Every table has row-level security: a person only ever sees projects they
-- own or joined, and the bills, files and chat of those projects.

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------- tables

create table if not exists public.projects (
  id          uuid primary key,
  owner       uuid not null default auth.uid() references auth.users(id) on delete cascade,
  data        jsonb not null default '{}'::jsonb,       -- claim details, travellers, client entities
  deleted     boolean not null default false,
  updated_by  uuid default auth.uid(),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create table if not exists public.project_members (
  project_id    uuid not null references public.projects(id) on delete cascade,
  contact       text not null,                          -- lower-case e-mail or +countrycode mobile
  user_id       uuid references auth.users(id) on delete set null,
  display_name  text not null default '',
  traveller_uid uuid,
  role          text not null default 'MEMBER' check (role in ('OWNER','MEMBER')),
  status        text not null default 'INVITED' check (status in ('INVITED','JOINED','REMOVED')),
  invited_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  primary key (project_id, contact)
);
create index if not exists project_members_user on public.project_members(user_id);

create table if not exists public.expenses (
  id          uuid primary key,
  project_id  uuid not null references public.projects(id) on delete cascade,
  data        jsonb not null,
  deleted     boolean not null default false,
  created_by  uuid default auth.uid(),
  updated_by  uuid default auth.uid(),
  updated_at  timestamptz not null default now()
);
create index if not exists expenses_project_updated on public.expenses(project_id, updated_at);

create table if not exists public.attachments (
  id           uuid primary key,
  project_id   uuid not null references public.projects(id) on delete cascade,
  expense_id   uuid not null references public.expenses(id) on delete cascade,
  storage_path text not null,
  sha256       text not null default '',
  data         jsonb not null default '{}'::jsonb,
  deleted      boolean not null default false,
  created_by   uuid default auth.uid(),
  updated_by   uuid default auth.uid(),
  updated_at   timestamptz not null default now()
);
create index if not exists attachments_project_updated on public.attachments(project_id, updated_at);

create table if not exists public.messages (
  id          uuid primary key,
  project_id  uuid not null references public.projects(id) on delete cascade,
  expense_id  uuid,
  author      uuid not null default auth.uid(),
  author_name text not null default '',
  body        text not null check (length(body) between 1 and 4000),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create index if not exists messages_project_updated on public.messages(project_id, updated_at);

-- ---------------------------------------------------------------- server clock for sync cursors

create or replace function public.ta_touch() returns trigger language plpgsql as $$
begin
  new.updated_at := clock_timestamp();
  if tg_table_name <> 'project_members' and tg_table_name <> 'messages' then
    new.updated_by := auth.uid();
  end if;
  return new;
end $$;

do $$ declare t text; begin
  foreach t in array array['projects','project_members','expenses','attachments','messages'] loop
    execute format('drop trigger if exists ta_touch on public.%I', t);
    execute format('create trigger ta_touch before insert or update on public.%I for each row execute function public.ta_touch()', t);
  end loop;
end $$;

-- ---------------------------------------------------------------- membership helpers

create or replace function public.ta_is_member(pid uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.projects p where p.id = pid and p.owner = auth.uid())
      or exists (select 1 from public.project_members m where m.project_id = pid and m.user_id = auth.uid() and m.status = 'JOINED');
$$;

create or replace function public.ta_is_owner(pid uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.projects p where p.id = pid and p.owner = auth.uid());
$$;

-- My sign-in identities (e-mail and/or phone) in the form used for invites.
create or replace function public.ta_my_contacts() returns text[]
language sql stable security definer set search_path = public, auth as $$
  select array_remove(array[lower(u.email), case when u.phone is null or u.phone = '' then null else '+' || ltrim(u.phone, '+') end], null)
  from auth.users u where u.id = auth.uid();
$$;

-- Called by the app after sign-in: links every invitation sent to my e-mail / mobile to my account.
create or replace function public.accept_invites(my_name text default '') returns setof uuid
language plpgsql security definer set search_path = public as $$
begin
  return query
  update public.project_members m
     set user_id = auth.uid(), status = 'JOINED',
         display_name = case when coalesce(my_name, '') <> '' then my_name else m.display_name end
   where m.contact = any(public.ta_my_contacts())
     and (m.user_id is null or m.user_id = auth.uid())
     and m.status <> 'REMOVED'
  returning m.project_id;
end $$;

revoke all on function public.accept_invites(text) from public, anon;
grant execute on function public.accept_invites(text) to authenticated;

-- ---------------------------------------------------------------- row-level security

alter table public.projects        enable row level security;
alter table public.project_members enable row level security;
alter table public.expenses        enable row level security;
alter table public.attachments     enable row level security;
alter table public.messages        enable row level security;

drop policy if exists projects_select on public.projects;
drop policy if exists projects_insert on public.projects;
drop policy if exists projects_update on public.projects;
-- "owner = auth.uid()" also lets the owner's first upsert pass (Postgres checks the read rule on the new row of an upsert).
create policy projects_select on public.projects for select to authenticated using (owner = auth.uid() or public.ta_is_member(id));
create policy projects_insert on public.projects for insert to authenticated with check (owner = auth.uid());
create policy projects_update on public.projects for update to authenticated using (owner = auth.uid()) with check (owner = auth.uid());

drop policy if exists members_select on public.project_members;
drop policy if exists members_write on public.project_members;
create policy members_select on public.project_members for select to authenticated
  using (public.ta_is_member(project_id) or user_id = auth.uid() or contact = any(public.ta_my_contacts()));
-- Only the project owner invites, changes or removes members.
create policy members_write on public.project_members for all to authenticated
  using (public.ta_is_owner(project_id)) with check (public.ta_is_owner(project_id));

-- Every member sees and adds bills; a bill can be changed only by the person who added it or the owner.
do $$ declare t text; begin
  foreach t in array array['expenses','attachments'] loop
    execute format('drop policy if exists %1$s_rw on public.%1$I', t);
    execute format('drop policy if exists %1$s_select on public.%1$I', t);
    execute format('drop policy if exists %1$s_insert on public.%1$I', t);
    execute format('drop policy if exists %1$s_update on public.%1$I', t);
    execute format('create policy %1$s_select on public.%1$I for select to authenticated using (public.ta_is_member(project_id))', t);
    execute format('create policy %1$s_insert on public.%1$I for insert to authenticated with check (public.ta_is_member(project_id) and created_by = auth.uid())', t);
    execute format('create policy %1$s_update on public.%1$I for update to authenticated using (public.ta_is_member(project_id) and (created_by = auth.uid() or public.ta_is_owner(project_id))) with check (public.ta_is_member(project_id))', t);
  end loop;
end $$;

drop policy if exists messages_select on public.messages;
drop policy if exists messages_insert on public.messages;
create policy messages_select on public.messages for select to authenticated using (public.ta_is_member(project_id));
create policy messages_insert on public.messages for insert to authenticated
  with check (public.ta_is_member(project_id) and author = auth.uid());

-- No access at all for signed-out (anon) users.
revoke all on public.projects, public.project_members, public.expenses, public.attachments, public.messages from anon;
grant select, insert, update on public.projects, public.project_members, public.expenses, public.attachments to authenticated;
grant delete on public.project_members to authenticated;
grant select, insert on public.messages to authenticated;

-- ---------------------------------------------------------------- bill files (private bucket)

insert into storage.buckets (id, name, public, file_size_limit)
values ('bills', 'bills', false, 209715200)
on conflict (id) do update set public = false;

-- Files are stored as bills/<project id>/<file id>.<ext>
drop policy if exists bills_read on storage.objects;
drop policy if exists bills_insert on storage.objects;
drop policy if exists bills_update on storage.objects;
create policy bills_read on storage.objects for select to authenticated
  using (bucket_id = 'bills' and public.ta_is_member(((storage.foldername(name))[1])::uuid));
create policy bills_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'bills' and public.ta_is_member(((storage.foldername(name))[1])::uuid));
create policy bills_update on storage.objects for update to authenticated
  using (bucket_id = 'bills' and public.ta_is_member(((storage.foldername(name))[1])::uuid));
