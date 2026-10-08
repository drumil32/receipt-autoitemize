# Design — Receipt upload, taxes, auto-itemize

Working design doc. `ARCHITECTURE.md` and `README.md` are derived from this.

## 1. Scope

A backend HTTP API. A user uploads a receipt; the service stores it, then on
`process` reads the receipt (OCR is **stubbed** — we read the fixture text),
extracts a transaction header, tax rows, and auto-itemized line items, and
persists them. If items + taxes do not reconcile with the printed total, the
transaction is kept and flagged `NEEDS_REVIEW` — we never invent a balancing
line or rewrite the total.

Out of scope: auth, UI, PDF rendering, real OCR/LLM calls, ledgers, Redis/Kafka.

## 2. Stack

| Concern | Choice | Rationale |
|---|---|---|
| Language/runtime | Java 21 (LTS) | What Navan compares against; current LTS. |
| Framework | Spring Boot 4.1.1 | Initializr only offers 4.x now; built on Spring 7 / Hibernate 7. |
| Persistence | Spring Data JPA + Hibernate 7 | Unique constraint + `@Transactional` + conflict-catch reads cleanly. |
| Database | SQLite (on-disk `data/receipts.db`) | Real DB file; satisfies "no in-memory" rule; zero setup for reviewer. |
| Money | `BigDecimal` | No float; scale documented (§6). |
| Build | Maven (`./mvnw`) | Wrapper means reviewer needs no local Maven. |

## 3. Data model

Four tables. One receipt → at most one transaction; a transaction has many
taxes and many line items.

### `receipts`
| Column | Type | Notes |
|---|---|---|
| `id` | TEXT (UUID) PK | server-generated |
| `original_filename` | TEXT | |
| `content_hash` | TEXT **UNIQUE** | SHA-256 of file bytes → idempotent upload (§5) |
| `storage_path` | TEXT | where the uploaded bytes live on disk |
| `ocr_text` | TEXT (nullable) | populated by `process`; source for re-itemize |
| `uploaded_at` | TIMESTAMP | |
| `processed` | BOOLEAN | |

### `transactions`
| Column | Type | Notes |
|---|---|---|
| `id` | TEXT (UUID) PK | |
| `receipt_id` | TEXT **UNIQUE** NOT NULL, FK→receipts | **the identity guarantee (§4)** |
| `merchant` | TEXT NOT NULL | |
| `txn_date` | DATE NOT NULL | |
| `currency` | TEXT NOT NULL | ISO 4217 (e.g. EUR) |
| `grand_total` | DECIMAL(19,2) NOT NULL | printed total, never overwritten by itemize |
| `itemize_status` | TEXT NOT NULL | `COMPLETE` \| `NEEDS_REVIEW` \| `FAILED` |

### `taxes`
| Column | Type | Notes |
|---|---|---|
| `id` | TEXT (UUID) PK | |
| `transaction_id` | TEXT NOT NULL, FK→transactions | |
| `name` | TEXT | e.g. VAT |
| `rate` | DECIMAL(6,4) | 0.1900 = 19% |
| `amount` | DECIMAL(19,2) | |

### `line_items`
| Column | Type | Notes |
|---|---|---|
| `id` | TEXT (UUID) PK | |
| `transaction_id` | TEXT NOT NULL, FK→transactions | |
| `description` | TEXT | |
| `amount` | DECIMAL(19,2) | **net** amount (§6) |
| `tax_amount` | DECIMAL(19,2) nullable | optional |
| `quantity` | INTEGER nullable | optional |

## 4. Identity — one receipt → one transaction

The `UNIQUE` constraint on `transactions.receipt_id` is the guarantee, enforced
by the **database**, not application `if`-checks. `process` performs a
transactional upsert: look up the transaction for this `receipt_id`; if absent
insert it, if present update it in place. Re-processing the same receipt always
targets the same row — never a second insert.

## 5. Concurrency & atomicity

**Concurrent `process` (two overlapping calls, same `receipt_id`):**
Both may find "no transaction yet" and attempt an insert. The DB's unique
constraint lets exactly one succeed; the other throws
`DataIntegrityViolationException`. We catch it, roll back that attempt, and
re-read the now-committed transaction to return it. Net result: exactly one
transaction. No external lock service.

> SQLite serializes writers at the DB level, which also prevents the double
> insert. The unique constraint is what makes the guarantee **portable** — the
> identical code gives true row-level concurrency on Postgres.

**Atomicity:** one `process` call = one `@Transactional` method = one commit.
Header + tax rows + line items persist together or not at all. Re-processing
first deletes the existing child rows (taxes, items) and re-inserts inside the
same transaction, so no duplicate or orphaned children accumulate.

**Idempotent upload:** on `POST /receipts` we SHA-256 the bytes. If a receipt
with that `content_hash` exists we return the existing `receipt_id` with `200`
(not a new row). Rationale: duplicate bytes almost always mean a client retry,
so upload is idempotent; the one-transaction invariant is still protected
downstream regardless.

## 6. Money

- Amounts (`grand_total`, tax `amount`, item `amount`, `tax_amount`):
  `BigDecimal`, **scale 2**, `DECIMAL(19,2)`.
- Tax `rate`: `BigDecimal`, **scale 4**, `DECIMAL(6,4)` (0.1900 = 19%).
- Comparisons use `compareTo`, never `equals` (scale-insensitive).
- No `double`/`float` anywhere in the money path.

**Reconciliation rule (one convention, documented):** our auto-itemizer emits
**net** line items (matching `gold.json`). A transaction reconciles iff
`sum(line_items.amount) + sum(taxes.amount) == grand_total` (compared at scale
2). This matches the clean fixture (15.00 + 2.85 = 17.85).

A line item's optional `tax_amount` is **stored metadata only** — reconciliation
always uses the dedicated `taxes` rows, never per-item tax. So PATCH/`complete`
reconcile the same way whether or not per-item tax is supplied.

`itemize_status`:
- `COMPLETE` — items present and the sum reconciles.
- `NEEDS_REVIEW` — items absent, or present but sum ≠ total.
- `FAILED` — reserved for extraction failure (we return 4xx instead of
  persisting a header we can't trust; see §8).

## 7. Endpoints & documented forks

| Method | Path | Behavior / decision |
|---|---|---|
| POST | `/receipts` | Multipart upload. Duplicate bytes → **existing id, 200** (idempotent). |
| GET | `/receipts/{id}` | id, filename, uploaded_at, processed, transaction_id?. 404 if missing. |
| GET | `/receipts/{id}/ocr` | Stored OCR text. 404 if receipt missing; **409** if not processed yet. |
| POST | `/receipts/{id}/process` | Create-or-update the one transaction. Persist header+taxes+items+OCR atomically. |
| DELETE | `/receipts/{id}` | Delete only if unprocessed → 204. If a transaction exists → **409**, delete nothing. |
| GET | `/transactions/{id}` | Header + taxes + line_items + itemize_status + receipt_id. |
| GET | `/transactions?receipt_id={id}` | The one transaction, or 404. Never a list. |
| POST | `/transactions/{id}/itemize` | Re-itemize from **stored OCR**. Replace line items only; never touch header/taxes/total. |
| PATCH | `/transactions/{id}/items` | Edit/merge/split. If items+taxes ≠ total → **409 + mismatch payload**, persist nothing. Accepts optional per-item `tax_amount`/`quantity` (stored if sent, else null). |
| POST | `/transactions/{id}/complete` | Set `COMPLETE` only if reconciles; else **409**, status unchanged. |
| GET | `/health` | liveness. |

## 8. Error handling

- **Parse failure:** if merchant, date, currency, or total cannot be extracted,
  `process` returns **422** (4xx) and persists nothing. No `"Unknown Merchant"`,
  no `1970-01-01`, no `0.00`.
- 404 for missing receipt/transaction; 409 for state conflicts (OCR-before-
  process, delete-processed, mismatch on patch/complete).
- **400** for a bad upload: empty file, non-multipart request, or a multipart
  request missing the `file` part — each mapped to the standard error body.
- Consistent JSON error body `{ "status", "error", "message" }`; mismatch (409)
  responses additionally carry `items_total`, `taxes_total`, `grand_total`,
  `difference` so the caller sees why it failed.

## 9. OCR stub & extraction

OCR is stubbed: `process` reads the stored fixture text verbatim as `ocr_text`.
The extractor parses that text:
- `MERCHANT:`, `DATE:`, `CURRENCY:` prefixes → header fields.
- A line ending in a decimal that is **not** Subtotal/VAT/TOTAL/parenthetical →
  a line item (`description` = leading text, `amount` = trailing number).
- A line containing `VAT` + `NN%` → a tax (name=VAT, rate=NN/100, amount=
  trailing number). Handles `VAT 19%` and `incl. VAT 19%`.
- `TOTAL` line → `grand_total`. `Subtotal` ignored.

Line items carry optional `tax_amount`/`quantity` fields, but the current text
parser doesn't read them (no fixture has per-item tax or quantity) — they stay
null unless a user supplies them via PATCH. One `ExtractedLineItem` type is
reused for both the extractor and PATCH edits, so teaching OCR to read these is
a parser change, not a new type.

Fixture outcomes (vs `gold.json`):
- clean → 3 items, VAT 2.85, total 17.85, `COMPLETE`.
- tax-only → 0 items (fare line has no amount), VAT 3.83, total 24.00, `NEEDS_REVIEW`.
- mismatch → 2 items (10.00) + VAT 1.90 ≠ 18.50 → `NEEDS_REVIEW`, total stays 18.50.

## 10. Layering

`controller → service → repository → entity`. Controllers do HTTP only;
services hold the transactional logic; a dedicated `OcrService` (stub) and
`ReceiptExtractor` isolate parsing; repositories are Spring Data interfaces.
No single class does routing + parsing + storage.

## 11. Test plan (the 9 required)

MockMvc hitting the real HTTP API against a file-backed SQLite test DB.
1. Clean fixture → matches gold (COMPLETE, taxes, items, total).
2. Mismatch → keeps total, no balancing line, `NEEDS_REVIEW`.
3. Tax-only → `NEEDS_REVIEW`, empty items.
4. PATCH mismatch → 409, items + total unchanged.
5. Re-process twice sequentially → one transaction id, no duplicate child rows.
6. Concurrent process → one transaction (unique-constraint race test).
7. GET receipt + GET by receipt_id → same transaction id.
8. DELETE processed receipt → 409, transaction still present.
9. POST complete on mismatch → 409, status stays `NEEDS_REVIEW`.

## 12. Anticipated interview cross-questions

- **Why a DB unique constraint and not an app check?** App checks race
  (check-then-insert has a window); the constraint is atomic at commit.
- **What happens on the losing concurrent `process`?** Catches
  `DataIntegrityViolationException`, re-reads, returns the winner's transaction.
- **Why idempotent upload over 409?** Duplicate bytes = retry; returning the id
  needs no error-handling by the caller. One-transaction invariant is unaffected.
- **Net vs gross line items?** We emit net; reconcile = items + taxes = total.
  Documented so gross would double-count tax — we deliberately avoid that.
- **Why not `float`?** Binary float can't represent 0.1 exactly; cents drift.
  `BigDecimal` at fixed scale is exact.
- **SQLite in production?** No — chosen for zero-setup. Same JPA code + unique
  constraint runs on Postgres for real row-level write concurrency.
- **Why 422 on parse failure, not a stored FAILED row?** A header we can't
  trust (merchant/date/currency/total) shouldn't exist as money data; fail loud.
- **How is re-itemize safe?** Reads stored OCR (no re-upload), replaces only
  line items within one transaction; header/taxes/total untouched.
