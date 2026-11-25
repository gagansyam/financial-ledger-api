---
name: simulate-transfer
description: Drives the packaged ledger service end to end against a disposable database (registration, login, wallets, funding, transfers, idempotent replay and key reuse, rule rejections, two concurrent debits of which exactly one may succeed, reversal, reconciliation and logout) and reports PASS or FAIL for each step.
---

# Simulate Transfer Skill

Answers one question: does the running product move money correctly through its public API? Implements SK-3 and follows SK-8 (no credentials printed or stored).

---

## 1. Trigger Conditions

Run this skill when:
* A change touches funding, transfers, reversals, idempotency, authentication, ownership or reconciliation.
* Before opening or approving a pull request that changes the HTTP contract or the posting path.
* At the end of each stage from Stage 3 onwards, before skill evaluations are logged (SK-7).

Do not use it to check that the build compiles or that the server starts; that is `local-check` (SK-2). This skill assumes a working build.

---

## 2. Prerequisites

* JDK 21 or later on `PATH`. The Maven Wrapper builds the jar when it is missing or `--build` is passed.
* Python 3.9 or later. The script uses only the standard library.
* No credentials are needed. The script generates a random admin key and password for each run, passes the admin key to the server only as a SHA-256 digest in `LEDGER_ADMIN_KEYS`, and never prints or writes either value. To supply your own, set `LEDGER_SIM_ADMIN_KEY` and `LEDGER_SIM_PASSWORD`; both are removed from the server's environment.

---

## 3. Procedure

From the repository root:

```bash
python3 scripts/simulate_transfer.py --build
```

The script starts the jar on a free loopback port with `LEDGER_DB_PATH` in a fresh temporary directory and `LEDGER_CURRENCIES=INR:2`, then runs these steps:

| # | Step | Passes when | Requirements |
|---|------|-------------|--------------|
| 1 | build (only with `--build` or no jar) | wrapper exits 0 and the jar exists | – |
| 2 | start server | `/api/v1/health` answers `OK` before the timeout | FR-6 |
| 3 | register, login and create an INR wallet for two users | 201, 200 with `Cache-Control: no-store`, 201 | FR-1.1–FR-1.6, FR-7.1 |
| 4 | admin funds the first wallet with 10000 | 201 | FR-1.7, FR-1.8 |
| 5 | record initial balances (read with each owner's session) | 10000 and 0 | SK-3 |
| 6 | transfer 2500 between the wallets | 201 | FR-2.1–FR-2.4 |
| 7 | replay with the same key and payload | same status and body, `Idempotency-Replayed: true`, no new entries on either wallet, balance unchanged | FR-2.6–FR-2.8 |
| 8 | same key, different amount | 422 `IDEMPOTENCY_KEY_REUSED` | FR-2.9 |
| 9 | another user's wallet, fractional amount | 403 `FORBIDDEN`, 400 `INVALID_AMOUNT` | NFR-SEC-7, NFR-SEC-2 |
| 10 | choose the race amount | `remaining // 2 + 1`: more than half and at most the remaining balance, which is at least 2 | SK-3 |
| 11 | two distinct-key transfers sent together from the source owner's session | exactly one 201 and one 422 `INSUFFICIENT_FUNDS`; any 503 or other outcome fails | FR-3.2–FR-3.6, SK-3 |
| 12 | balances after the race | equal the expected arithmetic exactly | BR-4 |
| 13 | admin reverses the first transfer, then tries again | 201 with `reversalOf`, then 409 `ALREADY_REVERSED` | FR-4.2–FR-4.4, BR-9 |
| 14 | final balances | equal the expected arithmetic exactly | BR-4, BR-9 |
| 15 | reconciliation with the admin key | 200 `CONSISTENT` with no discrepancies | FR-5.1–FR-5.5 |
| 16 | logout | 204, and the old token then gets 401 | FR-7.2 |
| 17 | stop server | the process exits on the termination signal (`graceful`) | FR-6.3 |
| 18 | server log contains no credentials | neither the admin key nor the password appears in the log | SK-8, NFR-SEC-4 |
| 19 | discard database | the temporary directory is gone; no other database is touched | SK-8 |

A 503 in step 11 is a failure, not a pass. Record it in the evaluation and diagnose the conditions (for example a slow filesystem pushing the writer past `busy_timeout`).

Options:
* `--build` packages the jar first (tests skipped; run `local-check` for tests).
* `--startup-timeout N` waits up to `N` seconds for step 2 (default 60).

The exit code is `0` only when every step passed, and the last line reads `OVERALL: PASS` or `OVERALL: FAIL`.

---

## 4. Interpreting Failures

| Failing step | Usual cause | Next action |
|--------------|-------------|-------------|
| start server | configuration rejected or port in use | rerun `local-check`, which prints the server output |
| replay or key reuse | fingerprint or idempotency record not stored with the transaction | `IdempotencyGuard`, `RequestFingerprint` |
| concurrent race | both succeed (overdraft), both fail, or a 503 under contention | `TransactionPoster` balance check inside the write lock; `ConcurrencyTest` |
| reconciliation | a check C1–C7 failed; the response lists the discrepancy | `ReconciliationService`; `ReconciliationCorruptionTest` shows each check's trigger |
| server log contains no credentials | a header or body logged without sanitising | the request log sanitiser in the HTTP layer |

A failure here should get a regression test at the lowest layer that reproduces it before the fix (PC-10).

---

## 5. Recording the Run

After a stage-end run, add a row to `docs/skill_evaluations.md` with the date, commit, step count, pass count, the race outcome and anything that had to be repeated. Append a short entry to `brain/log.md`.

---

## 6. Brain Citations

* [[resources/concurrency-race-conditions-wallets|Concurrency and Race Conditions in Wallets]]: why the race sends two debits that cannot both fit against one source wallet.
* [[resources/ietf-idempotency-http-standard|IETF Idempotency-Key HTTP Header]]: replay and reuse behaviour checked in steps 6 and 7.
* [[resources/reconciliation-algorithms-accounting|Reconciliation Algorithms in Accounting]]: the final consistency check.
* [[areas/idempotency-design|Area: Idempotency Design]]
* [[resources/agentskills-specification-standards|Agent Skills Specification and Evaluation Standards]]: skill format and evaluation fields.
