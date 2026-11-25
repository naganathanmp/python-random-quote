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

## 3. Sign-in by e-mail code

1. **Authentication → Sign In / Providers → Email**: keep **Email** enabled. Turn **Confirm email**
   on (default).
2. **Authentication → Emails → Templates → Magic Link**: make the e-mail show the 6-digit code.
   Replace the body with, for example:

   ```html
   <h2>Your TA Reimburse sign-in code</h2>
   <p>Enter this code in the app: <strong>{{ .Token }}</strong></p>
   <p>It expires in 1 hour. If you did not ask for it, ignore this e-mail.</p>
   ```

   Do the same for the **Confirm signup** template (first sign-in of a new person).
3. Supabase's built-in e-mail sender is limited to a few e-mails per hour. For a team, add your
   own SMTP under **Authentication → Emails → SMTP Settings** (e.g. your firm's Office 365 / Google
   Workspace SMTP, or a service like Brevo / Amazon SES).

**Sign-in by mobile number (optional, paid SMS):** Authentication → Sign In / Providers → **Phone**,
choose an SMS provider (Twilio, MessageBird, Vonage, Textlocal) and enter its keys. Members can then
sign in and be invited with their mobile number.

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
