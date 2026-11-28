# TA Reimburse on Power Apps: build prompts

How to use this file:

1. **Prompt 0 (master spec)** is the full description of the app. Paste it first into the AI you are
   building with: Copilot in Power Apps (make.powerapps.com → *Create* → *Start with Copilot*),
   Copilot Studio, or ChatGPT / Claude if you want step-by-step instructions to follow by hand.
2. Then paste **Prompts 1–9 one at a time**, in order. Each one builds one part and ends with a
   checklist, so you can confirm that part works before moving on.
3. Words in `<angle brackets>` are placeholders. Replace them with your firm's values.

Licences needed: Power Apps **Premium** per user (for Dataverse and AI Builder), **AI Builder
credits**, and for the combined PDF / ZIP / signature steps a PDF connector subscription (Encodian
Flowr, Adobe PDF Services or Muhimbi). Check with IT before starting.

---

## Prompt 0: master specification (paste first)

```
You are an expert Microsoft Power Platform architect and Power Fx developer. Help me build a
production-ready app called "TA Reimburse" for travel-allowance (TA) claims at an audit and
accounting firm in India. Use Dataverse, a canvas app (phone layout, also usable on tablet),
AI Builder, Power Automate and Teams. Give exact table/column definitions, Power Fx formulas,
flow steps and settings. Do not leave "TODO" placeholders. Build it in the phases I will ask for.

WHO USES IT
- Employees (auditors) who travel to client sites. They photograph or upload bills during the trip
  and submit one TA claim per project assignment.
- Team members on the same assignment add their own bills to the same claim.
- Approvers (managers/partners) approve or reject claims. Accounts mark them reimbursed.

CORE WORKFLOW
1. Create a claim: project/assignment name, client name, project code, client location visited,
   purpose, from/to dates, advance taken, currency (default INR), approved budget (optional),
   daily spend limit (optional, whole team or per person), tickets booked by (Self / Office /
   Client) for onward and return, co-travellers (name + employee code), and optional billing split
   across client entities (name, code, share %).
2. Add bills: take photos or upload many files at once (photos, screenshots, PDFs). Each file
   becomes a bill. AI Builder reads the date, total amount, vendor and bill number. An AI prompt
   suggests the accounting head, the mode of travel and the mandatory remark (rules below). The
   head is pre-filled but the person must confirm every bill.
3. Review: each bill shows the photo, date, amount, currency (+ exchange rate for foreign
   currency), head, mode of travel, remark (mandatory), who shared it (equal split across
   travellers by default, editable), and whether a supporting bill exists (Sup Bills Y/N).
   Swipe or Next/Previous moves between bills; Confirm marks it reviewed.
4. Checks before generating: missing date/amount/head/remark/mode = blocking; bill date outside
   the trip ±1 day = warning; possible duplicate (same date + amount + vendor, in this or any
   earlier claim of the same person) = blocking until the person types a reason; over budget or
   over the daily limit = warning; no bill attached = warning (Sup Bills = N).
5. Generate outputs: (a) the Excel TA statement in the firm template, (b) one combined PDF of all
   bills in date order with a header strip on each page ("#3 · 21st Jul 2026 · Local Conveyance
   · ₹640 · Remarks: Rapido Bike from Halasuru to Bommasandra"), (c) a ZIP of the original files
   renamed "DDth Mon YYYY_Amount" (e.g. "21st Jul 2026_640.jpg"; clashes get " (1)", " (2)")
   and sorted into folders by accounting head. Save all three to SharePoint in
   /TA Claims/<Year>/<Claim No>/.
6. Submit: sends for approval (Power Automate Approvals) to the approver, with the three files.
   Status flow: Draft → Submitted → Approved / Rejected (with reason, back to Draft) →
   Reimbursed (amount + date). Every change is written to a history table.
7. Archive and insights: submitted claims become read-only; dashboard of totals by status, month,
   head, client, budget vs actual, top vendors, unusually high bills.

ACCOUNTING HEADS (code, label in app, exact label in the Excel template)
6535 Air Fare | Air Fare
6540 Bus Fare | Bus Fare
6545 Expenses during travel | "Expenses during travel " (trailing space)
6555 Train Fare | Train Fare
6560 Pick up & Drop | Pick up & Drop
6565 Boarding Expenses (Stay & Food) | Boarding Expenses(Stay & Food)
6578 Laundry Expenses | Laundry Expenses
6590 Local Conveyance | Local Conveyance
Restricted heads (shown in grey, need a note): 6571 Other Charges, 6575 Telephone charges - clients,
6580 Visa Handling Charges, 6585 Insurance Travel, 6577 Car Hire Charges -Clients,
6595 Car Parking Charges.

EXCEL TEMPLATE (I will upload TRAVEL_CLAIM.xlsx; keep its layout exactly)
- Sheet "TA CON-SHEET": employee and claim details at the top, one row per accounting head with
  its total, total claimed, advance, net payable / refundable, amount in words (Indian system,
  "Rupees … Only"), signatures.
- Sheet "Annexure-Breakup daywise": header "Name of the team members", "Client Location
  Visited", "Nature of Audit", then columns: Date | Nature of Expenditure (template head label) |
  Amount | Sup Bills Y/N | Remarks -Mandatory | Mode of travel-Mandatory | Team members name |
  File Name | (Billed to client entity, only when split). Data rows sorted by date. A Total row.
  Own-vehicle rows keep the formula (km*rate)+toll; foreign rows ROUND(foreign*rate,2).

REMARK STYLE (learned from the firm's past annexures; write remarks exactly like this)
- Cab/auto/bike ride: "<Provider> <Service> from <pickup> to <drop>", e.g.
  "Rapido Bike from 148 Halasuru to Bommasandra", "Uber Go from Bommasandra to 96 Cambridge
  Road (toll incl.)". Places = first two parts of the address, without city/state/PIN.
- Airport cab (Pick up & Drop): "UberXL from IGI Delhi Airport to Dharamshila Hospital".
- Toll: "NHAI Electronic City Toll (two-wheeler) - Bangalore". Metro: "Bangalore Metro
  recharge via PhonePe".
- Food under Boarding: "<Meal> - <Restaurant> ( <City> )", e.g. "Dinner - Hotel Mangalam ( Hosur )".
- Food under Expenses during travel: "<Meal> at <Restaurant>, <City>" or "Late-night dinner via
  Swiggy - Kolkata". Meal from the time on the bill: before 11:00 Breakfast, before 16:00 Lunch,
  before 18:30 Snacks, before 22:30 Dinner, otherwise Late-night dinner; no time → "Meal".
- Hotel stay: "Hotel stay - <Hotel> ( <City> ) - 3 nights". Laundry: "<Hotel> - Laundry bill".
- Exceptions explained in one clause: only a UPI screenshot → "Lunch - food bill not provided,
  hence attached payment receipt (PhonePe UPI) - Delhi"; handwritten memo → "Handwritten cash
  memo - lunch food bill"; several invoices → "(2 bills - Rs 80 + Rs 740)".
- Mode of travel values: Cab, Auto, Bike, Metro, Bus, Train, Flight, Car - Own, Food, NA,
  Laundry, Parking.

NUMBERS AND FORMATS
- Amounts in INR with Indian grouping ₹1,23,456.00 (setting to switch to 123,456 and to hide
  paise). Foreign bills store currency, foreign amount and exchange rate; INR = ROUND(foreign*rate,2).
- Own vehicle without bill: km × rate per km (default ₹7.5, editable) + toll.
- Claim number format TA-YYYYMM-NNN (sequence per month).

SECURITY AND PRIVACY
- Each person sees their own claims and claims they are a team member of. Approvers see claims
  sent to them. Accounts see approved claims. Use Dataverse security roles + team/row sharing.
- A team member can edit only bills they added; the claim owner can edit all and is the only one
  who generates and submits.
- Bill files are stored in Dataverse file columns or SharePoint, never in a public location.
  Show a declaration on the PDF last page and the Excel annexure: "This TA statement was prepared
  by <Name> (<Code>) using TA Reimburse on <date>. Every bill was reviewed and confirmed by
  <Name>. Bills were read with AI assistance and each value was checked against the bill and
  confirmed by a person."

LOOK AND FEEL
- Minimal "airport wayfinding" style: paper-white background #F2F1ED, graphite text #16181C,
  signal-yellow accent #FFCC00 (text on yellow is #16181C), secondary blue #0A7CFF, success
  #12804A, error #C3261C; dark theme background #0F1114, surfaces #181B20, text #ECEBE6.
- Fonts: a condensed bold sans for headings, a plain sans for text, monospace for amounts and
  codes. Rounded corners 6–10 px, no heavy shadows.
- Claims list as a "departure board": each claim shows a route code (home city → client city,
  e.g. BLR→HOS) in a dark tile, title, client, dates, bill count, total, status chip
  (Open / To review / Submitted / Approved / Reimbursed / Rejected), search and filter chips.
- Big yellow "Scan" button on every open claim. Settings screen: theme (Light/Dark/System),
  accent colour, number format, default currency, home city, own-vehicle rate, per-head limits,
  reminders on/off.

Reply first with: the list of Dataverse tables, the screen list, the flows, the AI Builder models,
the licences/connectors needed, and the build order. Then wait for my next prompt.
```

---

## Prompt 1: Dataverse tables

```
Create the Dataverse tables for TA Reimburse with exact column names, data types, required flags,
choices and relationships. Use the publisher prefix "ta_".

1. ta_Claim: Claim Number (text, auto: TA-YYYYMM-NNN), Title, Client Name, Project Code,
   Location Visited, Purpose, Start Date, End Date (date only), Advance (currency), Currency
   (choice: INR, USD, EUR, GBP, AED, SGD, …), Budget (currency, optional), Daily Limit (currency,
   optional), Daily Limit Per Person (yes/no), Onward Booked By / Return Booked By (choice:
   Self Booking, Office, Client), Split Mode (choice: None, Auto, Manual), Status (choice: Draft,
   Submitted, Approved, Rejected, Reimbursed), Submitted On, Approved On, Paid On, Paid Amount,
   Comments (multiline), Approver (lookup User), Owner (system), Home City (text),
   Outputs Folder URL (URL), Total (rollup sum of ta_Bill.Amount INR), Bill Count (rollup),
   To Review Count (rollup where Confirmed = No).
2. ta_Traveller: Claim (lookup), Name, Employee Code, User (lookup User, optional), Contact
   (email/mobile), Is Claimant (yes/no), Invite Status (choice: None, Invited, Joined).
3. ta_BillingEntity: Claim (lookup), Name, Code, Share Percent (decimal 0–100).
4. ta_Bill: Claim (lookup, cascade delete), Bill Date, Amount INR (currency), Currency, Foreign
   Amount, Exchange Rate (decimal 4 places), Vendor, Bill No, Head (choice with the 14 heads,
   value = account code), Suggested Head, Suggestion Reason, Nature, Remarks (required to confirm),
   Mode Of Travel (choice list from the spec), Confirmed (yes/no), No Bill (yes/no),
   Billing Entity (lookup), Entity Locked (yes/no), Shares (multiline JSON
   [{"traveller":"<id>","amount":123.45}]), Mileage Km, Mileage Rate, Toll, OCR Text (multiline
   20,000), AI Note, Date Confident / Amount Confident (yes/no), Duplicate Of (lookup ta_Bill),
   Duplicate Reason (text), Added By (lookup User), File Name (calculated in flow),
   Content Hash (text, SHA-256 from flow).
5. ta_BillFile: Bill (lookup, cascade delete), File (file column, max 100 MB), Original Name,
   Is Primary (yes/no), Mime Type, Sort Order.
6. ta_ClaimEvent: Claim (lookup), Type (choice: Created, Files, Updated, Generated, Submitted,
   Approved, Rejected, Reimbursed, Reopened), Message, When.
7. ta_Message (team chat, if not using Teams): Claim, Bill (optional lookup), Author, Body.
8. ta_UserSettings: User, Theme (Light/Dark/System), Accent (hex), Indian Grouping (yes/no),
   Show Paise (yes/no), Default Currency, Home City, Mileage Rate, Reminders (yes/no),
   Head Limits (JSON).

Also: business rules (Amount INR = ROUND(Foreign Amount * Exchange Rate, 2) when Currency <> INR;
Amount INR = Km * Rate + Toll for own vehicle), and alternate key on ta_Claim.Claim Number.
Finish with a checklist to verify the tables in make.powerapps.com.
```

---

## Prompt 2: security roles and sharing

```
Design the Dataverse security for TA Reimburse:
- Roles: "TA Employee" (create/read/write own claims, bills, files; read claims shared with them),
  "TA Approver" (read claims where Approver = me, update status via approval only),
  "TA Accounts" (read approved claims, set Reimbursed), "TA Admin".
- Team claims: when the owner adds a traveller with a User, share the claim (and its bills) with
  that user via Access Teams (create an access team template on ta_Claim with Read + Append To;
  bills: Create + Read; Write only on bills where Added By = current user, enforced with a
  real-time plugin-free approach: a canvas-app check plus a Power Automate "when a row is
  modified" guard that reverts edits by non-authors and logs them).
- Give me the exact steps in the Power Platform admin center and the Power Fx checks the app must
  do (e.g. If(ThisItem.'Added By'.'Primary Email' <> User().Email && gblClaim.Owner.'Primary Email'
  <> User().Email, DisplayMode.View, DisplayMode.Edit)).
Finish with test steps using two test users.
```

---

## Prompt 3: canvas app screens and navigation

```
Build the canvas app "TA Reimburse" (phone layout 9:19, responsive containers) with these screens.
Give me the controls, their properties and the Power Fx for each. Use named formulas in App.Formulas
for colours/theme so the whole app re-themes from ta_UserSettings.

App.OnStart / Formulas:
- Load the current user's ta_UserSettings into gblSettings (create one if missing).
- Theme: nfDark = Switch(gblSettings.Theme, "Dark", true, "Light", false, App.Theme.Dark? ...);
  colour named formulas: clrBg, clrSurface, clrText, clrMuted, clrAccent, clrOnAccent, clrLine.
- Money format named formula fxMoney(n) honouring Indian grouping and paise, e.g. Indian:
  "₹" & Text(n, If(gblSettings.'Show Paise', "##,##,##0.00", "##,##,##0")) with a custom
  function that inserts Indian grouping (give me the exact UDF).

Screens:
1. scrClaims (home): header "Claims", search box (title, client, place, claim no), filter chips
   (All, Open, To review, Awaiting payment, Team), gallery of claims as departure-board rows:
   route code tile (home city code → location code using a city-code table: Bengaluru BLR,
   Delhi DEL, Mumbai BOM, Chennai MAA, Kolkata CCU, Hyderabad HYD, Pune PNQ, Dubai DXB,
   Singapore SIN, London LHR; otherwise first 3 letters), title, client · dates · N bills,
   total (monospace), status chip colours. "New TA claim" button. Bottom nav: Claims, Insights,
   Archive, Settings.
2. scrClaimForm: create/edit claim with validation (required fields, end ≥ start), co-travellers
   repeating rows, billing entities repeating rows (only when Split Mode <> None), budget and
   daily limit.
3. scrClaim: claim header (client · code · dates · place · claim no), totals (Total, Advance,
   Net payable), budget progress bar, big yellow "Scan" button (camera, batch mode: after each
   photo stay on the camera and add another), "Upload files" (Attachments/Add picture, many at
   once), list of bills grouped by day with day total vs daily limit (red when over), each bill
   row: vendor, date · head, amount, chips (Review, Duplicate?, No bill, By <name>).
   "Generate TA statement" button (owner only).
4. scrBill: photo viewer (pinch zoom), "Bill 4 of 12" with Next/Previous and swipe (use a
   horizontal gallery of bills with snap, or Next/Previous icons), fields: date, currency,
   amount (or foreign amount + rate → INR), head chips (suggested first) + full dropdown,
   mode of travel, remarks (required), More details (vendor, bill no), shares per traveller
   (must sum to the amount), billing entity (if split), supporting files, AI note, duplicate
   warning with "Keep - reason" box, buttons Save draft / Confirm & next. When the head changes
   and the person has not edited the remark, regenerate the remark (call the AI prompt again).
5. scrGenerate: checklist of blocking issues (red) and warnings (amber) computed with Power Fx
   from the bills (rules in the spec), "Generate Excel, PDF & receipts" (calls flow
   TA-GenerateOutputs), then links to the three files, Share/E-mail (Outlook), "Submit for
   approval" (calls flow TA-SubmitForApproval).
6. scrArchive: submitted/approved/reimbursed claims, read-only, status timeline from
   ta_ClaimEvent, buttons Mark approved / Mark reimbursed (Accounts role), Reopen.
7. scrInsights: totals claimed/reimbursed/pending, by month (column chart), by head (bar), budget
   vs actual per claim, top vendors, unusually high bills (amount > 2× median of the same head).
8. scrSettings: Appearance (theme Light/Dark/System, accent swatches yellow #FFCC00, saffron
   #FF8A1F, teal #0F9A8A, blue #2F6BFF, violet #7046E8), Numbers & defaults (Indian/international,
   show paise, default currency, home city, own-vehicle rate, per-head limits), Reminders on/off,
   Profile (name, employee code, designation, department, base location, approver).
Use delegable filters (Filter on Dataverse with StartsWith/Search) and show me any delegation
warnings and how to avoid them.
```

---

## Prompt 4: AI Builder, reading bills

```
Set up bill reading for TA Reimburse:
1. AI Builder "Receipt processing" prebuilt model on each uploaded image/PDF: map MerchantName →
   Vendor, TransactionDate → Bill Date, Total → Amount, plus keep the full recognised text in
   OCR Text. If the total is missing, take the largest amount labelled Total / Grand total /
   Net payable / Amount paid. If no date is printed, leave it empty and set AI Note
   "No date printed - please check".
2. An AI Builder prompt (GPT) named "TA bill classifier" with input OCR Text, Vendor, Client
   Location, Trip Dates, and output JSON:
   {"head_code": 6590, "nature": "Cab ride", "mode": "Bike", "remarks": "Rapido Bike from 148
   Halasuru to Bommasandra", "confidence": "high|low"}
   Put the full REMARK STYLE rules and the 14 heads from the master spec into the prompt
   instructions, with these few-shot examples:
   - Rapido receipt, pickup "148, Halasuru, Bengaluru 560008", drop "Bommasandra Industrial Area"
     → 6590, Bike, "Rapido Bike from 148 Halasuru to Bommasandra Industrial Area"
   - Uber Go with a Toll line → 6590, Cab, "Uber Go from Bommasandra Hosur Rd to 96 Cambridge Road (toll incl.)"
   - UberXL from Indira Gandhi International Airport → 6560, Cab, "UberXL from IGI Delhi Airport to <drop>"
   - Restaurant bill at 20:45 in Hosur → 6565, Food, "Dinner - Hotel Mangalam ( Hosur )"
   - Swiggy order delivered 7:11 PM, trip to Kolkata → 6545, Food, "Dinner via Swiggy - Kolkata"
   - PhonePe "Paid to SRI SAI TIFFIN CENTRE" at 12:48 pm, Delhi → 6545, Food,
     "Lunch - food bill not provided, hence attached payment receipt (PhonePe UPI) - Delhi"
   - NHAI Electronic City toll plaza, two wheeler → 6590, Bike, "NHAI Electronic City Toll (two-wheeler) - Bangalore"
   - Hotel tax invoice, 3 nights → 6565, NA, "Hotel stay - Red Fox Hotel ( Delhi ) - 3 nights"
   Rules: never invent an amount or date that is not in the text; restaurant/meals/food delivery
   and hotel stay = Boarding (6565) unless the claim's setting "Food bills go to" says Expenses
   during travel (6545); airport/station cab = 6560; other cab/auto/bike/metro/toll = 6590.
3. A Power Automate flow "TA-ReadBill" (trigger: when a ta_BillFile row is added with Is Primary =
   Yes): run Receipt processing → run the GPT prompt → update the ta_Bill: Vendor, Bill Date,
   Amount, Suggested Head, Head (pre-filled), Nature, Mode, Remarks (only if empty or not edited),
   AI Note; compute Content Hash; run the duplicate check (Prompt 6). Never overwrite fields the
   person already confirmed.
Show the exact prompt text, JSON schema, flow steps and expressions. Estimate AI Builder credits
per 100 bills.
```

---

## Prompt 5: Power Automate, generating the outputs

```
Create the flow "TA-GenerateOutputs" (trigger: Power Apps V2 with ClaimId):
1. Get the claim, travellers, billing entities and bills (sorted by Bill Date, then head, then
   created on) and their primary/supporting files.
2. Excel: copy the uploaded template TRAVEL_CLAIM.xlsx from SharePoint
   /TA Claims/Templates to /TA Claims/<Year>/<Claim No>/TA Statement_<Claim No>_<Project Code>.xlsx.
   Fill "TA CON-SHEET" cells (employee, claim, per-head totals via SUMIF on the annexure, advance,
   net payable, amount in words in the Indian system) and add annexure rows with the Office Scripts
   action "Run script" (give me the full Office Script in TypeScript that writes the rows, keeps
   formats/borders, writes own-vehicle and foreign-currency formulas, the Total row, the
   declaration block and, if split, the "Billed to" column and per-entity totals).
3. File names: "DDth Mon YYYY_Amount" with ordinal day (1st, 2nd, 3rd, 4th, 11th, 12th, 13th,
   21st, 22nd, 23rd, 31st), month as 3 letters, amount rounded to whole rupees, original extension;
   clashes " (1)", " (2)". Give me the expression.
4. Combined PDF: convert each image to PDF and add a header strip ("#<n> · <date> · <head> ·
   ₹<amount> · Remarks: <remarks>") with the PDF connector (Encodian "Add text watermark" /
   "Merge PDF files" or Adobe PDF Services), cover page with the claim summary and index, last page
   with the declaration. Save as Invoices & Supporting_<Claim No>_<Project Code>.pdf.
5. ZIP of renamed originals in folders by head (Encodian "Create ZIP" or SharePoint folder +
   "Extract/compress" alternative), plus the Excel and PDF.
6. If split: one Excel and PDF per client entity, with the advance pro-rated by its share.
7. Log ta_ClaimEvent "Generated", return the three file URLs to the app.
Add error handling (Scope + Configure run after) that returns a readable message to the app.
```

---

## Prompt 6: duplicates, validation, approvals, reminders

```
Create these flows and Power Fx pieces:
1. Duplicate check (inside TA-ReadBill and on Confirm): flag a bill when, for the same Owner
   (any claim), another bill has the same Content Hash (exact same file), or the same Vendor +
   Bill No, or the same Bill Date + Amount + Vendor. Strong match → Duplicate Of set and the bill
   cannot be confirmed until Duplicate Reason is filled. Show the other claim's title.
2. Validation formula on scrGenerate returning a table {Message, Blocking} for all rules in the
   master spec (missing fields, shares not equal to amount, foreign without rate, date outside
   trip ±1 day, over per-head limit, no bill, budget/daily limit, unresolved duplicates).
3. "TA-SubmitForApproval": start and wait for an approval (Approve/Reject with comments) to the
   claim's Approver with the Excel and PDF attached and a summary (total, advance, net payable,
   bills, period); on Approve set Status Approved + Approved On and notify the employee in Teams;
   on Reject set Status Draft, log the reason. Accounts get a Teams card to mark Reimbursed with
   amount and date.
4. "TA-Reminders" (daily 09:15 IST): for Draft claims whose End Date is 7+ days ago, or with bills
   to review, send the owner a Teams message and an email with a deep link to the claim.
5. Optional Teams: create a channel per team claim ("TA - <Claim No>") and post "New bill added by
   <name>" when a teammate adds a bill; the app's "Discuss this bill" button opens the chat with
   the bill link.
```

---

## Prompt 7: theme, accessibility and performance

```
Apply the TA Reimburse look across the canvas app:
- Named formulas for colours from ta_UserSettings (Light/Dark/System, accent), applied to every
  screen/container/button/label (no hard-coded colours). System = follow App.Theme / the device's
  dark mode if available, else Light.
- Typography: headings 20–28 bold condensed, body 14–16, amounts monospace; a "Text size" setting
  (0.9–1.3) multiplying all Size properties via a named formula.
- Accessibility: AccessibleLabel on every icon/button, tab order, minimum 44 px touch targets,
  contrast ≥ 4.5:1 in both themes (check yellow accent: text on yellow must be #16181C).
- Performance: concurrent loads, delegable queries, load bill photos as thumbnails, cache settings
  in variables, avoid LookUp inside galleries (use AddColumns once).
Give me the exact property values for the key controls.
```

---

## Prompt 8: optional Azure Functions (advanced)

```
Write two Azure Functions (C# or Python) exposed as custom connectors for TA Reimburse:
1. "TrustCheck": input a file; read EXIF/XMP/IPTC/C2PA (images), Producer/Creator/dates/revisions
   (PDF); return {level: OK|INFO|WARN|BLOCK, findings[]}. BLOCK when the file declares AI
   generation (DigitalSourceType trainedAlgorithmicMedia, AI tool names like Midjourney, DALL-E,
   Firefly, Stable Diffusion parameters); WARN for photo editors, online invoice generators, PDF
   modified >24 h after creation or 3+ revisions, photo taken before the bill date, invalid GSTIN
   check digit. Store the result on ta_Bill and block or require a reason in the app.
2. "BalancedSplit": input bills [{id, amount, lockedEntity?}] and entities [{id, sharePercent}];
   assign each whole bill to an entity so totals are as close as possible to the target shares
   (exact subset-sum DP for two entities, greedy + local search for more); return assignments.
Include deployment steps, the OpenAPI definition for the custom connector and how to call both
from Power Automate.
```

---

## Prompt 9: testing and go-live

```
Give me a test plan and go-live checklist for TA Reimburse:
- 20 test bills covering: Rapido/Uber/Ola rides, airport cab, toll, metro, restaurant (breakfast,
  lunch, dinner, late night), Swiggy/Zomato, UPI-only screenshot, hotel stay, laundry, flight,
  train, bus, foreign currency (AED, USD), own vehicle (km × rate + toll), no-bill expense, an
  exact duplicate, a re-uploaded duplicate in another claim.
- Expected head, remark, mode and amount for each; pass criteria (amount and date 100% correct
  after review; remark style matches; head suggestion ≥ 90%).
- Role tests with three users (employee, teammate, approver, accounts).
- Excel output compared against the template cell by cell; PDF page order and headers; ZIP names.
- ALM: solution with environment variables (SharePoint site, template path, approver default),
  managed solution to Test then Production, connection references, DLP policy check.
- Training: a one-page quick guide for employees and approvers.
```
