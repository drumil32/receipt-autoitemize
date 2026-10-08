# API test plan — manual/live endpoint verification

Thorough black-box check of every endpoint against a running server (real on-disk SQLite),
driven by `curl`. This complements the 68 automated tests; it exercises the actual HTTP
surface end-to-end, including status codes, headers, and error bodies.

- **Base URL:** `http://localhost:8080`
- **Fixtures:** `fixtures/task-a/{receipt-clean,receipt-mismatch,receipt-tax-only}.txt`
- **Run date:** 2026-10-08 · server `spring-boot:run`, fresh DB · driver script `api-smoke.sh`.
- **Result:** **40/40 passed.** ✅ pass · ❌ fail · — not run

Reconciliation rule under test: `sum(line_items.amount) + sum(taxes.amount) == grand_total`
(exact, scale 2). `COMPLETE` requires ≥1 item **and** reconciliation; else `NEEDS_REVIEW`.

Fixture facts:
- `receipt-clean`: 3 items net 15.00 + VAT 2.85 = **17.85** → reconciles → `COMPLETE`.
- `receipt-mismatch`: items 10.00 + VAT 1.90 = 11.90 ≠ **18.50** → `NEEDS_REVIEW` (difference −6.60).
- `receipt-tax-only`: 0 items + VAT → `NEEDS_REVIEW` (empty items can never complete).

---

## 1. `GET /health`

| # | Case | Expected | Result | Actual |
|---|------|----------|--------|--------|
| 1.1 | Liveness | 200, `{"status":"UP"}` | ✅ | 200, `status=UP` |

## 2. `POST /receipts` (multipart `file`)

| # | Case | Expected | Result | Actual |
|---|------|----------|--------|--------|
| 2.1 | Upload new bytes | 201 + `receipt_id` | ✅ | 201, id returned |
| 2.2 | Upload identical bytes again | 200, **same** id | ✅ | 200, same id (hash dedup) |
| 2.3 | Upload different bytes | 201, **new** id | ✅ | 201, new id |
| 2.4 | Empty file | 400, "Uploaded file is empty" | ✅ | 400, exact message |
| 2.5 | Missing `file` part | 400, standard body | ✅ | 400, `message: "Required file part 'file' is missing"` (fixed — see Obs. 1) |
| 2.6 | Non-multipart request | 400, "Expected a multipart file upload" | ✅ | 400, exact message |

## 3. `GET /receipts/{id}`

| # | Case | Expected | Result | Actual |
|---|------|----------|--------|--------|
| 3.1 | Known, before process | 200, `processed:false`, `transaction_id:null` | ✅ | 200, `processed=false, transaction_id=null` |
| 3.2 | Known, after process | 200, `processed:true`, `transaction_id` set | ✅ | 200, `processed=true, transaction_id` set |
| 3.3 | Unknown id | 404 | ✅ | 404, "Receipt not found: …" |

## 4. `GET /receipts/{id}/ocr`

| # | Case | Expected | Result | Actual |
|---|------|----------|--------|--------|
| 4.1 | Before process (no OCR) | 409 | ✅ | 409, "Receipt not processed yet: …" |
| 4.2 | After process | 200, OCR text | ✅ | 200, 278-byte text body |
| 4.3 | Unknown id | 404 | ✅ | 404, "Receipt not found: …" |

## 5. `DELETE /receipts/{id}`

| # | Case | Expected | Result | Actual |
|---|------|----------|--------|--------|
| 5.1 | Unprocessed receipt | 204, then GET → 404 | ✅ | 204; subsequent GET → 404 |
| 5.2 | Receipt with a transaction | 409 | ✅ | 409, "…has a transaction and cannot be deleted" |
| 5.3 | Unknown id | 404 | ✅ | 404, "Receipt not found: …" |

## 6. `POST /receipts/{id}/process`

| # | Case | Expected | Result | Actual |
|---|------|----------|--------|--------|
| 6.1 | First process (clean) | 201, `Location: /transactions/{id}`, `COMPLETE` | ✅ | 201 + Location header, `COMPLETE` |
| 6.2 | Re-process same receipt | 200, same txn id | ✅ | 200, same txn id |
| 6.3 | Mismatch fixture | 201, `NEEDS_REVIEW`, total 18.50 | ✅ | 201, `NEEDS_REVIEW`, `grand_total=18.5` |
| 6.4 | Tax-only fixture | 201, `NEEDS_REVIEW`, 0 items, taxes present | ✅ | 201, `NEEDS_REVIEW`, items=0, taxes=1 |
| 6.5 | Unparseable file | 422 | ✅ | 422, "Could not extract required fields…" |
| 6.6 | Unknown receipt id | 404 | ✅ | 404, "Receipt not found: …" |

## 7. `GET /transactions/{id}`

| # | Case | Expected | Result | Actual |
|---|------|----------|--------|--------|
| 7.1 | Known | 200, children loaded | ✅ | 200, taxes=1, line_items=3 |
| 7.2 | Unknown id | 404 | ✅ | 404, "Transaction not found: …" |

## 8. `GET /transactions?receipt_id={id}`

| # | Case | Expected | Result | Actual |
|---|------|----------|--------|--------|
| 8.1 | Receipt with a transaction | 200, single object | ✅ | 200, single object (`COMPLETE`) |
| 8.2 | Receipt without a transaction | 404 | ✅ | 404, "No transaction for receipt: …" |
| 8.3 | Unknown receipt id | 404 | ✅ | 404, "No transaction for receipt: …" (see Obs. 2) |

## 9. `POST /transactions/{id}/itemize`

| # | Case | Expected | Result | Actual |
|---|------|----------|--------|--------|
| 9.1 | Clean txn | 200, same id, total untouched, 3 items, `COMPLETE` | ✅ | 200, `grand_total=17.85`, items=3, `COMPLETE` |
| 9.2 | Mismatch txn | 200, total 18.50, 2 items, `NEEDS_REVIEW` | ✅ | 200, `grand_total=18.5`, items=2, `NEEDS_REVIEW` |
| 9.3 | Unknown id | 404 | ✅ | 404, "Transaction not found: …" |

## 10. `PATCH /transactions/{id}/items`

| # | Case | Expected | Result | Actual |
|---|------|----------|--------|--------|
| 10.1 | Reconciling edit | 200, items replaced, `COMPLETE` | ✅ | 200, items=1, `COMPLETE` |
| 10.2 | `tax_amount`/`quantity` supplied | 200, both stored | ✅ | 200, `tax_amount=2.85, quantity=3` |
| 10.3 | Optional fields omitted | 200, both `null` | ✅ | 200, `tax_amount=null, quantity=null` |
| 10.4 | Non-reconciling | 409 + payload, nothing persisted | ✅ | 409, `items=5.0 taxes=2.85 grand=17.85 diff=-10.0`; GET after = unchanged |
| 10.5 | Empty items | 409 | ✅ | 409 |
| 10.6 | Negative amount | 400 | ✅ | 400 |
| 10.7 | Unknown txn id | 404 | ✅ | 404 |

## 11. `POST /transactions/{id}/complete`

| # | Case | Expected | Result | Actual |
|---|------|----------|--------|--------|
| 11.1 | Reconciling txn | 200, `COMPLETE` | ✅ | 200, `COMPLETE` |
| 11.2 | Idempotent re-complete | 200, `COMPLETE` | ✅ | 200, `COMPLETE` |
| 11.3 | Mismatch txn | 409 + payload, stays `NEEDS_REVIEW` | ✅ | 409, `items=10 taxes=1.9 grand=18.5 diff=-6.6`; GET after = `NEEDS_REVIEW` |
| 11.4 | Unknown id | 404 | ✅ | 404, "Transaction not found: …" |

---

## Summary

**40/40 cases passed.** All happy paths, error codes (400/404/409/422), the 201-vs-200
distinction, the `Location` header, hash-dedup idempotency, reconciliation payloads, and the
"persist nothing on 409" guarantees behave as specified. Mirrors and extends the 68 automated tests.

### Observations (not failures — status codes all correct)

1. ~~**`POST /receipts` missing `file` part (2.5)** returns 400 but with Spring's *default* error
   body (no `message` field), whereas every other 400 uses our `ErrorResponse` shape.~~
   **✅ FIXED** (commit `12d3ec6`): added `@ExceptionHandler(MissingServletRequestPartException.class)`
   → 400 with our `ErrorResponse` shape (`message: "Required file part 'file' is missing"`), plus a
   regression test. The error contract is now uniform across all upload failures.

2. **`GET /transactions?receipt_id=` (8.3)** returns the same 404 ("No transaction for receipt: …")
   for an *unknown* receipt id as for a *known receipt without a transaction* (8.2). Both are
   legitimately 404; the lookup is by the transaction→receipt join, so it doesn't distinguish the
   two. Acceptable as-is; only matters if the API needs to tell "no such receipt" apart from
   "receipt exists but unprocessed."
