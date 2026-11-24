# TA Reimburse – Android app for travel reimbursement (TA) claims

Snap or upload your bills, review what the app reads from them, and get a submission-ready
**TA statement in Excel (PKF format)**, **one combined PDF of all invoices & supporting
documents**, and a **ZIP of receipts renamed `DDth Mon YYYY_Amount` and sorted by accounting head**.
Everything is processed on the phone; the app has no internet permission.

**Install:** copy `release/TA-Reimburse-v1.3.0.apk` to your phone, open it and allow
"Install unknown apps" for your file manager / browser when Android asks. Android 10 or newer.
v1.3.0 installs over any earlier version and keeps existing claims (database upgraded automatically).

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

- No network permission at all (INTERNET removed, incl. ML Kit telemetry); Android cloud backup and
  device-to-device transfer disabled; release build not debuggable, R8-shrunk, v2-signed.
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

- On-device OCR (Google ML Kit bundled model) – no images or text are uploaded.
- `INTERNET` permission is removed from the manifest; Android cloud backup/device transfer are
  disabled. Files leave the phone only when you Download, E-mail, Share or Back up.

## Build & test

```bash
cd android-ta-reimbursement
./gradlew :app:testDebugUnitTest   # 57 JVM + Robolectric tests (parser, Excel template, PDF, split, currency, travellers, OCR clean-up & voting, budget, migrations, security, e-mail intents, rotation/crash resilience, workflows, UI)
./gradlew :app:lintRelease
./gradlew :app:assembleRelease     # needs keystore.properties (not committed) or falls back to debug signing
```

Project layout: `core/` (pure Kotlin: receipt parser, file namer, XLSX writer + PKF workbook, PDF
writer + combined PDF, insights), `data/` (Room), `service/` (import, OCR, export, sharing, backup,
background jobs), `ui/` (Jetpack Compose screens).
