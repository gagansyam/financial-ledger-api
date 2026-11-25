# Agent Skill Evaluations Record (TQ-7)

This document records deterministic evaluations of all Agent Skills required by the AI-Assisted SDLC before stage deliverables are signed off.

---

## Stage 1 Skills Evaluation

### 1. `brain-ingest` Skill Evaluation

* **Evaluation Date:** 2026-11-16
* **Evaluated By:** Antigravity AI Assistant
* **Skill Path:** `skills/brain-ingest/SKILL.md`
* **Test Objective:** Ingest 10 foundational technical research notes into `brain/resources/` with valid YAML frontmatter, grounded content, and bidirectional `[[wikilinks]]`.
* **Execution Details:**
  * Ingested 10 notes:
    1. `double-entry-accounting-principles.md`
    2. `sqlite-wal-locking-mechanics.md`
    3. `ietf-idempotency-http-standard.md`
    4. `integer-currency-minor-units.md`
    5. `audit-trail-immutability-patterns.md`
    6. `acid-transactions-in-relational-databases.md`
    7. `concurrency-race-conditions-wallets.md`
    8. `reconciliation-algorithms-accounting.md`
    9. `rest-api-design-financial-services.md`
    10. `agentskills-specification-standards.md`
* **Verification Command:**
  ```bash
  python3 scripts/validate_skills.py
  python3 scripts/validate_brain.py
  ```
* **Validation Output:**
  ```
  Validating 1 skill(s)...
  All skills successfully validated against agentskills.io format!
  Scanning 18 markdown notes in brain/ for link integrity...
  All Second Brain notes and wikilinks successfully validated!
  ```
* **Result:** **PASSED**

---

## Stage 2 Skills Evaluation

### 1. `brain-ingest` (re-run)

* **Evaluation Date:** 2026-11-20
* **Evaluated By:** Developer
* **Skill Path:** `skills/brain-ingest/SKILL.md`
* **Test Objective:** Ingest the design research behind Stage 2 decisions as notes with the frontmatter and sections the skill requires.
* **Execution Details:** Four notes were added: hexagonal architecture and provider ports, canonical JSON and hash chains, Argon2id password hashing and session tokens, authentication and sessions. Each maps to FR/NFR IDs and was logged in `brain/log.md`.
* **Verification:** `python3 scripts/validate_brain.py` checks the frontmatter fields, the status per folder and the links.
* **Result:** **PASSED**

### 2. `brain-refine`

* **Evaluation Date:** 2026-11-20
* **Evaluated By:** Developer
* **Skill Path:** `skills/brain-refine/SKILL.md`
* **Test Objective:** Triage inbox notes into PARA, update the index and log, and leave zero broken links.
* **Execution Details:**
  * Three notes moved to `resources/` and one to `areas/`, with the status changed from `inbox` to `resource`/`area`.
  * `sqlite-wal-locking-mechanics` was reconciled with the single-writer decision instead of being duplicated.
  * `brain/index.md` now links all new notes, and the inbox is empty.
* **Verification Command:** `python3 scripts/validate_brain.py`
* **Validation Output:**
  ```
  Scanning 24 markdown notes in brain/ for link integrity...
  All Second Brain notes and wikilinks successfully validated!
  ```
* **Result:** **PASSED**

### 3. `brain-query`

* **Evaluation Date:** 2026-11-20
* **Evaluated By:** Developer
* **Skill Path:** `skills/brain-query/SKILL.md`
* **Test Objective:** Draft ADRs and answer design questions where every claim cites a brain note or an SRS requirement.
* **Execution Details:** Used to draft ADR-0001 to ADR-0012. Each ADR's context cites notes with wikilinks and lists them under *Brain notes*. Example question: "How should concurrent debits on one wallet be serialised?" The answer is ADR-0003, citing `sqlite-wal-locking-mechanics`, `concurrency-race-conditions-wallets` and `concurrency-acid`.
* **Verification Command:** `python3 scripts/validate_adrs.py`
* **Validation Output:**
  ```
  Validating 12 ADR(s)...
  All ADRs successfully validated!
  ```
* **Result:** **PASSED**

### 4. `local-check` (first full run)

* **Evaluation Date:** 2026-11-20
* **Evaluated By:** Developer
* **Skill Path:** `skills/local-check/SKILL.md` (`scripts/local_check.py`)
* **Test Objective:** Build, run every automated test with the quality gates, start the packaged server on a disposable database, probe `GET /api/v1/health`, stop the server gracefully and delete the database, reporting PASS/FAIL per step.
* **Verification Command:** `python3 scripts/local_check.py`
* **Validation Output:**
  ```
  [PASS] build
  [PASS] tests - unit, integration, system tests and quality gates
  [PASS] start server - listening on 127.0.0.1:56191
  [PASS] health probe - HTTP 200 status=OK database=reachable version=0.1.0-SNAPSHOT
  [PASS] stop server - graceful
  [PASS] discard database - /tmp/ledger-local-check-y6w9uxe0

  OVERALL: PASS
  ```
* **Result:** ****PASSED****

---

## Stage 3 Skills Evaluation

### 1. `brain-ingest`

* **Evaluation Date:** 2026-11-25
* **Evaluated By:** Developer
* **Skill Path:** `skills/brain-ingest/SKILL.md`
* **Test Objective:** Ingest the sources behind the Stage 3 test design into `brain/inbox/` with frontmatter, the required sections and FR/NFR mappings.
* **Execution Details:** One note from SQLite "Atomic Commit In SQLite" and Fowler "Eradicating Non-Determinism in Tests", mapped to FR-3.8, NFR-REL-1, NFR-REL-2, TQ-1 and TQ-5.
* **Verification Command:** `python3 scripts/validate_brain.py`
* **Result:** **PASSED**

### 2. `brain-refine`

* **Evaluation Date:** 2026-11-25
* **Evaluated By:** Developer
* **Skill Path:** `skills/brain-refine/SKILL.md`
* **Test Objective:** Triage the inbox note into PARA and link it both ways.
* **Execution Details:** Moved to `resources/crash-safety-and-deterministic-testing.md` (`status: resource`), linked from `areas/concurrency-acid` and `index.md`; inbox empty again.
* **Verification Command:** `python3 scripts/validate_brain.py`
* **Result:** **PASSED**

### 3. `brain-query`

* **Evaluation Date:** 2026-11-25
* **Evaluated By:** Developer
* **Skill Path:** `skills/brain-query/SKILL.md`
* **Test Objective:** Answer a Stage 3 design question with citations only.
* **Execution Details:** "How must the simulate-transfer race be built so its outcome is deterministic?" Answer recorded in `brain/projects/stage-3-testing.md` §2, citing two resource notes, one area note, ADR-0003 and SRS FR-3.1, FR-3.5 and SK-3. It drove the fix for defect D-2.
* **Result:** **PASSED**

### 4. `local-check`

* **Evaluation Date:** 2026-11-25
* **Evaluated By:** Developer
* **Skill Path:** `skills/local-check/SKILL.md` (`scripts/local_check.py`)
* **Test Objective:** Build, all tests and gates, start, `/health`, graceful stop (now checked against the server's `stopped` log line, defect D-3), database removed.
* **Verification Command:** `python3 scripts/local_check.py`
* **Validation Output:**
  ```
Disposable workspace: /tmp/ledger-local-check-ykh3f9y6
  [PASS] build
  [PASS] tests - unit, integration, system tests and quality gates
  [PASS] start server - listening on 127.0.0.1:56221
  [PASS] health probe - HTTP 200 status=OK database=reachable version=0.1.0-SNAPSHOT
  [PASS] stop server - graceful
  [PASS] discard database - /tmp/ledger-local-check-ykh3f9y6
  
  OVERALL: PASS
  ```
* **Result:** **PASSED**

### 5. `simulate-transfer` (first evaluation)

* **Evaluation Date:** 2026-11-25
* **Evaluated By:** Developer
* **Skill Path:** `skills/simulate-transfer/SKILL.md` (`scripts/simulate_transfer.py`)
* **Test Objective:** SK-3: two users, funding, transfer, replay without new entries, key reuse, ownership and amount rejections, a two-request race of which exactly one may succeed, reversal, exact balances, CONSISTENT reconciliation, logout, graceful stop, no credentials in the log, database discarded.
* **Verification Command:** `python3 scripts/simulate_transfer.py`
* **Validation Output:**
  ```
[PASS] start server
  [PASS] register sim_alice - HTTP 201
  [PASS] login sim_alice - HTTP 200
  [PASS] create INR wallet for sim_alice - HTTP 201
  [PASS] register sim_bob - HTTP 201
  [PASS] login sim_bob - HTTP 200
  [PASS] create INR wallet for sim_bob - HTTP 201
  [PASS] admin funds alice with 10000 - HTTP 201
  [PASS] initial balances recorded - alice 10000, bob 0
  [PASS] transfer 2500 alice -> bob - HTTP 201
  [PASS] replay returns the original status and body without new entries - HTTP 201, entries (2, 1) -> (2, 1)
  [PASS] same key with a different payload is rejected - HTTP 422 IDEMPOTENCY_KEY_REUSED
  [PASS] spending from another user's wallet is forbidden - HTTP 403 FORBIDDEN
  [PASS] fractional amount is rejected - HTTP 400 INVALID_AMOUNT
  [PASS] race amount is more than half and at most the remaining balance - remaining 7500, each transfer 3751
  [PASS] two concurrent transfers: exactly one succeeds, the other is INSUFFICIENT_FUNDS - HTTP 201, HTTP 422 INSUFFICIENT_FUNDS
  [PASS] balances equal the expected arithmetic - alice 3749 (expected 3749), bob 6251 (expected 6251)
  [PASS] admin reverses the first transfer - HTTP 201
  [PASS] a second reversal is rejected - HTTP 409 ALREADY_REVERSED
  [PASS] final balances equal the expected arithmetic - alice 6249 (expected 6249), bob 3751 (expected 3751)
  [PASS] reconciliation is CONSISTENT - HTTP 200 CONSISTENT, 4 transactions
  [PASS] logout revokes the session - HTTP 401
  [PASS] stop server - graceful
  [PASS] server log contains no credentials
  [PASS] discard database
  OVERALL: PASS
  ```
* **Repetitions:** the race step was repeated on five further runs; every run gave exactly one `201` and one `422 INSUFFICIENT_FUNDS`. It also runs in CI on Linux and Windows.
* **Result:** **PASSED**

---

## Status of Scheduled Skills Across Stages

| Skill Name | Target Stage | Current Status | Verification Gate |
| :--- | :--- | :--- | :--- |
| **`brain-ingest`** | Stage 1 | **PASSED** | Validated via `scripts/validate_skills.py` & 10 ingested notes |
| **`brain-refine`** | Stage 2 | **PASSED** | Inbox triage; `validate_brain.py` clean |
| **`brain-query`** | Stage 2 | **PASSED** | ADR-0001 to ADR-0012 drafted with citations; `validate_adrs.py` clean |
| **`local-check`** | Stage 2 | **PASSED** | Full run: build, tests, start, `/health`, stop, cleanup |
| **`simulate-transfer`** | Stage 3 | **PASSED** | 25/25 steps; race gave exactly one 201 and one 422; reconciliation CONSISTENT; runs in CI |
