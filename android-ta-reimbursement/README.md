# TA Reimburse – Android app for travel reimbursement (TA) claims

Snap or upload your bills, review what the app reads from them, and get a submission-ready
**TA statement in Excel (PKF format)**, **one combined PDF of all invoices & supporting
documents**, and a **ZIP of receipts renamed `DDth Mon YYYY_Amount` and sorted by accounting head**.
Bills are read on the phone (text recognition + an optional open-source AI model running on the
phone). Internet is used only to download that AI model once and, if you turn it on, to share a
project with your team through your firm's own server.

**Install:** copy `release/TA-Reimburse-v2.0.3.apk` to your phone, open it and allow
"Install unknown apps" for your file manager / browser when Android asks. Android 10 or newer.
v2.0.3 installs over any earlier version and keeps existing claims (database upgraded automatically).

## What's new in 2.0.3

- **Remarks written the way your past annexures are** (learned from 710 remarks in earlier TA
  statements): "Rapido Bike from 148 Halasuru to Bommasandra", "Uber Go from A to B (toll incl.)",
  "Dinner - Hotel Mangalam ( Hosur )", "Lunch at Shrinidhi Upchar, Bommasandra",
  "NHAI Electronic City Toll (two-wheeler) - Bangalore", and exceptions explained, e.g. "Lunch - food
  bill not provided, hence attached payment receipt (PhonePe UPI)". The meal comes from the time on
  the bill; the place from the claim. Changing the head rewrites the remark in that head's style
  (unless you wrote your own). Mode of travel is short: Cab / Auto / Bike / Metro / Food / NA.
- **Accounting head filled in automatically** from the suggestion (you still confirm each bill).
- **Upload many files at once**: "Upload files" → *Photos & screenshots* opens the photo picker with
  tick boxes; *Files, PDFs or ZIP* opens the file picker (long-press to select several). One file
  that can't be read no longer stops the rest. Picking several files on a bill asks whether they are
  separate bills or documents for that bill.

## What's new in 2.0.2

- **Fixed: "The AI model could not be loaded"** in the 2.0.0/2.0.1 release builds. The release
  shrinker (R8) had removed MediaPipe settings fields that are looked up by name at run time; it had
  also removed the signature algorithms, so 2.0.x release outputs were silently left unsigned. Both
  are now kept, and `tools/check-release.sh` checks every release APK for them.
- Bills where the AI couldn't start are retried automatically after updating; the bill shows a plain
  message instead of technical text (details go to Settings → Error report).
- Sign in to Team sharing from the e-mail link (tap it, or paste it in Settings → Team sharing).

## What's new in 2.0.0

- **On-device AI bill reader** – an open-source model (Qwen 2.5 by Alibaba, Apache-2.0; optional
  Gemma 3n for 8 GB phones) runs *on the phone* with Google MediaPipe and reads each bill a second
  time after the text scan: amount, date, vendor, nature, accounting head and a short remark. The
  app picks the model for the phone's memory (Settings → On-device AI; one-time 0.5–1.6 GB download,
  Wi-Fi only by default, checksum-verified). Code checks every AI answer against the bill text – an
  amount or date that is not printed on the bill is rejected – and you still confirm every bill.
  The AI never overwrites what you typed. Test set: dates & amounts 28/32 with rules alone → 32/32.
- **Date from the file** – when no date is printed, the photo's capture date / PDF creation date is
  used and marked "please check".
- **Trust check** on every uploaded file (bills and supporting documents): reads EXIF/XMP/IPTC,
  C2PA Content Credentials, PNG text chunks, PDF producer/creator/dates/revisions and Office
  properties. AI-generated files (AI tool names, `DigitalSourceType = trainedAlgorithmicMedia`,
  image-generator settings) are **blocked**; photo editors, online invoice generators, PDFs edited
  after creation, photos taken before the bill date and invalid GSTIN check digits must be
  **confirmed with a reason**.
- **Duplicate bills across all claims** – same file (SHA-256), same-looking photo (perceptual hash),
  same vendor + bill no., or same date + amount + vendor, in this or any earlier claim. Strong
  matches must be explained before generating.
- **One remarks field** – the extra "Remarks (shown in Annexure)" and "Paid by" fields are gone; the
  single mandatory remarks appears in the Annexure, the PDF index and on top of every bill page.
  Vendor and bill no. moved under "More details". Old extra remarks were merged automatically.
- **Declaration & authenticity** on the Excel annexure and as the last PDF page: who prepared the
  claim, that every bill was confirmed by a person, whether the on-device AI helped read bills, and
  flagged bills that were kept with their reasons.
- **Digital signatures** – the combined PDF carries an embedded signature (PAdES/PKCS#7; Adobe
  Reader shows it in the Signature panel) and the Excel gets a detached `.xlsx.p7s` signature. The key
  lives in the phone's Android Keystore; the certificate names the person. **Settings → Verify a
  document** checks any PDF or Excel + `.p7s`.
- **Team projects** – share a claim with co-travellers by e-mail or mobile number; everyone adds
  their own bills (photos included) from their phone and sees the team's spend; **team chat** per
  project with "Discuss this bill". Uses your firm's own Supabase project — set-up in
  [`server/README.md`](server/README.md). Only the owner edits claim details and generates/submits;
  members edit only their own bills (enforced by the server's row-level security).

## What's new in 1.3.0

- **E-mail fixed** – the app now finds the installed mail apps itself and opens one directly
  (Gmail, Outlook…) with To / CC / subject / summary and all files attached (proper read grants for
  Android 10+). If the phone has no mail app the E-mail button is hidden; Share and Download remain.
- **Security hardening** (see below) and **crash-proofing**: guarded system pickers, tolerant
  share-intent parsing, robust state restore on rotation, local error report in Settings.

## What's new in 1.2.0

- **Better reading of unclear bills (still 100% on-device)** – small images are upscaled, the
  right orientation is found, and each photo is read three ways (original, shadow-removed
  high-contrast, sharpened black & white). Text boxes are rebuilt into printed rows so
  "Grand Total … 830.00" stays together, common misreads are corrected (O→0, l→1, ₹ read as
  % or Z, split decimals) and the readings vote: a value is marked confident only when the
  readings agree, otherwise the field shows "Please verify".
- **Budget per TA claim** – optional approved budget asked when creating the claim; budget vs
  actual bar (used %, remaining / over), forecast at the current pace, budget chip on the claims
  list, warning before submission when over budget, and a *Budget vs actual* section in Insights.
- **Daily spend limit** (optional) – for the whole team or per traveller (using each person's
  share); days over the limit are listed on the claim and before submission.
- **PDF header shows the mandatory Remarks** (plus mode of travel) above every invoice, and the
  index lists remarks instead of vendor.

## What's new in 1.1.0

- **Excel in the firm's TRAVEL CLAIM template format** – sheets `TA CON-SHEET ` and
  `Annexure-Breakup daywise`: client name, client location, nature of audit, team members,
  audit start/end dates, total mandays, tickets booked by (onward/return), accounting numbers
  (6535 Air Fare … 6595 Car Parking) with the template's exact head names and
  `SUMIF('Annexure-Breakup daywise'!B:B, …)` totals, restricted heads greyed, advance,
  balance to repay / receive, submission date, signature; annexure columns Date · Nature of
  Expenditure · Amount · Sup Bills Y/N · Remarks (mandatory) · Mode of travel (mandatory) ·
  Team members name · File Name, total, comments, mandatory-field notes and head guidance.
- **All travellers on one claim** – add co-travellers; each bill records every person's share
  (equal by default, editable) → "Team members name" column + a *Team member-wise* sheet.
- **Billing split across client entities** (optional) – auto (balanced or by %, invoices never
  split, bills can be fixed to an entity) or manual; consolidated statement with a split table
  plus a separate statement & PDF per entity (advance pro-rated); ZIP has a folder per entity.
- **Foreign currency** – INR default; per-bill currency, amount and exchange rate (remembered per
  claim, auto-detected on receipts) → INR, shown as `ROUND(amount*rate,2)` with the conversion in
  the remarks.
- **Own vehicle (mileage)** entries – km × rate + toll, written as `=(134*7.5)+100` like the
  template, Sup Bills = N; default rate per km in Settings.
- **Mode of travel** suggested per bill (Car - Taxi, Auto, Metro, Bus, Food, Laundry…), mandatory
  remarks pre-filled (e.g. "Meal - Hotel Mangalam").

## Workflow

1. **First launch** – enter your name, employee code, designation, department, base location,
   organisation (statement heading) and the approver/accounts e-mail (pre-filled when e-mailing).
2. **New TA claim** – one claim per project assignment: project name, client, project/engagement
   code, place(s) of visit, purpose, travel period, advance received.
3. **Add bills**
   - **Scan bill** with the camera; after each capture you can add a *supporting* photo
     (payment screenshot, boarding pass…) or scan the next bill.
   - **Upload files** – pick many at once: photos (JPG/PNG/HEIC/WebP), PDFs, screenshots,
     text/HTML/e-mail files, Word/Excel/PowerPoint documents, videos (a frame is used) or
     **ZIP archives** (nested ZIPs are expanded; OS junk files are skipped).
   - You can also *Share* files to the app from Gallery, Files or Gmail.
4. **Review** each bill – the app pre-fills date, amount, vendor and bill number using on-device
   text recognition, detects the *nature of expense* and **suggests an accounting head**, but
   **always asks you to choose/confirm the head** (e.g. food bills: *Boarding (Stay & Food)* vs
   *Expenses during travel*). Possible duplicates (same date & amount) are flagged with a one-tap
   *Merge* to keep the extra copy as a supporting document. Documents can be moved between
   expenses, marked as main invoice, or removed. Expenses without a bill are marked self-declared.
5. **Generate TA statement** – blocking issues (missing amount/date/head, unreviewed bills) must be
   fixed; warnings (date outside travel period, duplicates, policy limits, fractional amounts,
   no bill) are shown for a final check. Outputs:
   - `TA Statement_<claim>.xlsx` – in the firm's TRAVEL CLAIM template format (see above); for
     split claims also `TA Statement_<claim>_<entity code>.xlsx` per client entity.
   - `Invoices & Supporting_<claim>.pdf` – cover page with claim summary and an index of bills with
     page numbers, then every invoice/supporting page with a header strip (S.No · date · head · amount).
   - `Receipts_<claim>.zip` – category sub-folders with files renamed `15th Mar 2026_500.jpg`
     (`(1)`, `(2)` added when date & amount clash), plus the Excel and PDF.
   - **Download** (to `Downloads/TA Reimbursement/<claim no>`), **E-mail** (approver pre-filled,
     summary in the body, all three files attached) or **Share**.
6. **Mark completed & archive** – the claim becomes read-only in the **Archive** with its outputs.
   Track **Approved**, **Reimbursed** (amount & date) or **Rejected**; **Reopen** to correct and
   resubmit. Every step is recorded in the claim's history timeline.
7. **Insights** dashboard (All time / This year / Last 12 months / This FY): claimed, reimbursed,
   pending, average per claim and per travel day, settlement time, monthly trend, spend by head,
   high/unusual expenses, repeated & top vendors, per-project totals, claim timeline and
   auto-generated highlights (e.g. category trends, overdue claims).
8. **Settings** – profile, optional per-bill policy limits per head, full **backup/restore** to a
   single ZIP, privacy note.

## Security

- Network: HTTPS only (cleartext disabled, system CAs only). Used for the AI model download from
  huggingface.co (redirects must stay HTTPS; a Hugging Face token is only ever sent to
  huggingface.co) and for the team server you configure. ML Kit's usage-statistics upload has no
  backend (removed from the manifest). Android cloud backup and device-to-device transfer disabled;
  release build not debuggable, R8-shrunk, v2-signed.
- Team server: only the public anon key is on the phone (the `service_role` key is refused);
  row-level security limits every row and file to project members (tested on PostgreSQL with
  owner/member/stranger/anon roles); sign-in by one-time code; session and Hugging Face tokens are
  encrypted with an Android Keystore AES-256-GCM key.
- Signing key: EC P-256 in the Android Keystore, never exported.
- Only one exported activity (launcher + Share-to). Incoming shares accept `content://` URIs from
  other apps only — never `file://` or this app's own FileProvider (blocks "confused deputy" attempts
  to import the app's private database or other claims).
- FileProvider is not exported and exposes only `files/trips/` (claim bills & outputs),
  `files/crash/` and `cache/camera/`; access is per-intent and read-only.
- Untrusted file names are sanitised (extensions alphanumeric, ≤ 5 chars); per-file 200 MB and
  per-ZIP 500 MB / 500-entry limits; ZIP-slip and nested-ZIP depth protection.
- Backup restore accepts only the expected entries, checks size, SQLite integrity and schema version
  before touching anything, and rolls back automatically if the restore fails.
- Excel cells written from receipt text are always plain strings (no formula injection); PDF and XML
  text is escaped.
- Error report (Settings) contains only the stack trace and app/OS version — no claim data.

## Privacy

- Bill reading is on the phone: ML Kit text recognition (bundled model) and the open-source AI model
  (MediaPipe LLM inference, text only, offline). No AI service or cloud reads your bills.
- Claims you don't share never leave the phone except when you Download, E-mail, Share or Back up.
  Shared (team) projects are stored in **your firm's** Supabase project only.

## Build & test

```bash
cd android-ta-reimbursement
./gradlew :app:testDebugUnitTest   # 81 JVM + Robolectric tests (parser, AI prompt/validation eval, trust check, duplicates, signatures verified with pdfsig/openssl, two-phone team sync, Excel template, PDF, split, currency, travellers, OCR, budget, migrations 1→4, security, rotation/crash resilience, workflows, UI)
./gradlew :app:lintRelease
./gradlew :app:assembleRelease     # needs keystore.properties (not committed) or falls back to debug signing
tools/check-release.sh app/build/outputs/apk/release/app-release.apk   # must print RELEASE CHECK PASSED
```

Project layout: `core/` (pure Kotlin: receipt parser, `ai/` prompt + validation, `trust/` metadata &
duplicates, `sign/` PDF/CMS signatures, XLSX writer + PKF workbook, PDF writer, insights), `data/`
(Room), `service/` (import, OCR, `ai/` model manager + MediaPipe engine, `sync/` Supabase client +
sync engine, export, signing, sharing, backup), `ui/` (Jetpack Compose screens), `server/` (Supabase
SQL + set-up guide).
