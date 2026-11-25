# Defect Log (PC-7, PC-10, TQ-8)

Every significant defect gets a GitHub issue, a fix through a pull request, and a regression test or check that failed before the fix and passes after it. A defect is significant when it could let money move incorrectly, hide a failed check, or make a required verification report a false pass.

Updated: 2026-11-25 (Stage 3)

| ID | Found | Area | Summary | Severity | Issue | Fix PR | Regression guard | Status |
|----|-------|------|---------|----------|-------|--------|------------------|--------|
| D-1 | 2026-11-20 (Stage 2 review) | `brain/` | `brain/inbox/` and `brain/archive/` were empty, so git did not track them; on a fresh clone `validate_brain.py` failed on missing PARA folders and broken `[[inbox/]]` / `[[archive/]]` links | Medium | _pending_ | _pending_ | `validate_brain.py` runs on a fresh checkout in the CI `repository-checks` job | Fixed |
| D-2 | 2026-11-25 (Stage 3, SK-3 review) | `scripts/simulate_transfer.py` | The concurrent step raced 12 transfers and passed with up to 7 commits, and counted `503 STORAGE_BUSY` as acceptable. SK-3 requires two distinct-key debits that cannot both fit, exactly one success, and treats 503 as a failure, so an overdraft or a lock timeout could have been reported as PASS | High | _pending_ | _pending_ | Step 11 of `simulate-transfer` now fails unless the outcome is exactly one `201` and one `422 INSUFFICIENT_FUNDS`; step 7 fails if a replay adds entries. Runs on every push in the CI `java` job (Linux and Windows) | Fixed |
| D-3 | 2026-11-25 (Stage 3, CI review) | `scripts/local_check.py`, `scripts/simulate_transfer.py` | The stop step reported `graceful` whenever the process exited after `terminate()`. On Windows that call is a hard kill, and on Linux nothing checked that the shutdown hook ran, so FR-6.3 evidence could be a false pass | Medium | _pending_ | _pending_ | The stop step now passes as `graceful` only when the process exits in time and the server logged `stopped`; on Windows it says the stop was forced and that graceful shutdown is checked on Linux. Exercised by both scripts in CI and in every `local-check` run | Fixed |
| D-4 | 2026-11-25 (Stage 3, integration with `main`) | `domain` | `CanonicalJson`, `RequestFingerprint`, `TransactionHasher`, `CurrencyCode` and `Money` were built twice on parallel branches (PR #2, PR #3 and the ledger core) with different method names and types, so the merged tree did not compile. Both versions followed ADR-0004, so the canonical and hashed bytes were identical | Medium | _pending_ | _pending_ | One implementation kept per class; the PR #2 and PR #3 tests were ported onto it (`CanonicalJsonTest`, `RequestFingerprintTest`, `TransactionHasherTest`, `MoneyTest`, `CurrencyCodeTest`, `IdentifiersTest`) and pin the ADR-0004 byte format | Fixed |

## Test defects (not product defects)

| ID | Found | Summary | Fix |
|----|-------|---------|-----|
| T-D1 | 2026-11-25 | `ReconciliationIntegrationTest` used 13-character idempotency keys, which the schema rejects (`length(idempotency_key) BETWEEN 16 AND 64`). The service layer relies on the HTTP layer to validate key length, so this is test data, not a product fault | Keys changed to `recon-writer-<n>-<8 digits>` |

## Process

1. Open a GitHub issue with the `defect` label, the steps to reproduce and the requirement IDs affected.
2. Write the regression test at the lowest layer that reproduces the defect and confirm it fails.
3. Fix on a `fix/<issue>-<slug>` branch; the PR links the issue and names the regression test.
4. Add or update the row above with the issue and PR numbers, and update `docs/traceability.md`.
