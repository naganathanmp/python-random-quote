# Team server setup (Supabase) — one time, by the firm's admin

TA Reimburse works fully offline. Team sharing (invite members, sync bills and photos, chat) needs a
small server. The app uses **Supabase** (free tier is enough for a team). Your firm owns the
project and the data; nothing goes to the app's developer.

## 1. Create the project (5 minutes)

1. Go to <https://supabase.com> → sign in → **New project**.
2. Name it e.g. `ta-reimburse`, choose a strong database password, and pick the region closest to
   you (e.g. **Mumbai (ap-south-1)** for India).
3. Wait until the project is ready.

## 2. Create the tables and security rules

1. In the project: **SQL Editor** → **New query**.
2. Paste the whole of [`supabase-setup.sql`](supabase-setup.sql) and click **Run**.
   It creates the tables, row-level security (each person sees only projects they own or were
   invited to), the invite function and a private `bills` storage bucket. It is safe to run again.

## 3. Sign-in e-mails

1. **Authentication → URL Configuration**: set **Site URL** to `tareimburse://auth` and add
   `tareimburse://auth` under **Redirect URLs**. The sign-in link in the e-mail then opens the app
   and signs the person in (choose *TA Reimburse* if the phone asks which app to open). The person
   can also long-press the link, copy it, and paste it in Settings → Team sharing.
2. Free plan limits with Supabase's built-in e-mail sender: **only 2 sign-in e-mails per hour**
   for the whole project, and the e-mail text can't be changed (it shows a link, not a code).
   For a team, add your own sender under **Authentication → Emails → SMTP Settings**, e.g.
   Brevo (free, 300 e-mails/day: host `smtp-relay.brevo.com`, port 587) or a Gmail account with an
   App Password (`smtp.gmail.com`, port 587). Then you can also change the **Magic Link** and
   **Confirm signup** templates to show the 6-digit code `{{ .Token }}`.

**Sign-in by mobile number (optional, paid SMS):** Authentication → Sign In / Providers → **Phone**,
choose an SMS provider (Twilio, MessageBird, Vonage, Textlocal) and enter its keys.

## 4. Give the app the server details

1. **Project Settings → API** (or **Data API**): copy the **Project URL**
   (`https://xxxx.supabase.co`) and the **anon / public** key.
2. In the app: **Settings → Team sharing → 1. Team server** → paste both → **Save server**.
   Then **Share code** creates a one-line `TA1:…` code that team members can paste instead.

> **Never use the `service_role` / secret key in the app.** It bypasses all security rules. The
> app refuses it. The anon key is meant to be public: the row-level security rules protect the data.

## 5. Day-to-day

* Owner: open a claim → **Team** → **Share with team** → **Invite** each traveller (e-mail or
  mobile). The app opens WhatsApp / e-mail / SMS with the invitation and the team server code.
* Member: installs the app → Settings → Team sharing → pastes the code → signs in with the
  invited e-mail/mobile → the project appears. They add their own bills; everyone sees the team's
  spend; the **chat** icon on the claim opens the team chat ("Discuss this bill" on any bill).
* Only the owner edits claim details, generates and submits the TA statement. A member can edit
  only the bills they added (enforced by the server).

## What is stored on the server

Shared projects only: claim details, travellers, bills (amounts, remarks, heads, trust-check
results), bill files (private `bills` bucket) and chat messages. Claims you never share stay on
your phone. Deleting a shared claim on the owner's phone closes it for everyone.

To delete everything: Supabase dashboard → **Project Settings → General → Delete project**.
