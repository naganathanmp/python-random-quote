# TA Reimburse – Android app for travel reimbursement (TA) claims

Snap or upload your bills, review what the app reads from them, and get a submission-ready
**TA statement in Excel (PKF format)**, **one combined PDF of all invoices & supporting
documents**, and a **ZIP of receipts renamed `DDth Mon YYYY_Amount` and sorted by accounting head**.
Everything is processed on the phone; the app has no internet permission.

**Install:** copy `release/TA-Reimburse-v1.0.0.apk` to your phone, open it and allow
"Install unknown apps" for your file manager / browser when Android asks. Android 10 or newer.

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
   - `TA Statement_<claim>.xlsx` – **CON-SHEET** (claim header, 14 accounting heads via `SUMIF`
     over the Annexure, total, less advance, net payable/refundable, amount in words, declaration,
     sign-off block) and **Annexure** (itemised bills with date, bill no., vendor, head, amount,
     renamed file reference, remarks).
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

## Privacy

- On-device OCR (Google ML Kit bundled model) – no images or text are uploaded.
- `INTERNET` permission is removed from the manifest; Android cloud backup/device transfer are
  disabled. Files leave the phone only when you Download, E-mail, Share or Back up.

## Build & test

```bash
cd android-ta-reimbursement
./gradlew :app:testDebugUnitTest   # 25 JVM + Robolectric tests (parser, Excel, PDF, workflows, UI)
./gradlew :app:lintRelease
./gradlew :app:assembleRelease     # needs keystore.properties (not committed) or falls back to debug signing
```

Project layout: `core/` (pure Kotlin: receipt parser, file namer, XLSX writer + PKF workbook, PDF
writer + combined PDF, insights), `data/` (Room), `service/` (import, OCR, export, sharing, backup,
background jobs), `ui/` (Jetpack Compose screens).
