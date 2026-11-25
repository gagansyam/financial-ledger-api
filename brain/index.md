# Second Brain Index — Financial Ledger & Digital Wallet API

Welcome to the Obsidian Second Brain knowledge vault for the **Financial Ledger & Digital Wallet API** project, designed under the AI-Assisted SDLC framework.

---

## 1. Vault Structure (PARA)

* **[[inbox/]]:** Unprocessed notes, raw research, and incoming technical documentation.
* **[[projects/]]:** Active deliverable notes, stage trackers, and task breakdowns.
  * [[projects/stage-1-requirements|Stage 1: Requirements & Knowledge Base]]
  * [[projects/stage-1-review|Stage 1 Review Note]]
  * [[projects/stage-2-design|Stage 2: Architecture & Design]]
  * [[projects/stage-2-review|Stage 2 Review Note]]
  * [[projects/stage-3-testing|Stage 3: Testing & CI]]
  * [[projects/stage-3-review|Stage 3 Review Note]]
* **[[areas/]]:** Core ongoing technical domains:
  * [[areas/ledger-architecture|Double-Entry Ledger Architecture]]
  * [[areas/concurrency-acid|Concurrency, Locking & ACID Guarantees]]
  * [[areas/idempotency-design|API Idempotency & Fault Tolerance]]
  * [[areas/security-validation|Security, Decimal Safety & Input Validation]]
  * [[areas/authentication-sessions|Authentication and Sessions]]
* **[[resources/]]:** Reference documents, literature notes, and technical standards:
  * [[resources/double-entry-accounting-principles|Double-Entry Accounting Principles]]
  * [[resources/sqlite-wal-locking-mechanics|SQLite WAL and Transaction Locking Mechanics]]
  * [[resources/ietf-idempotency-http-standard|IETF HTTP Idempotency Standard (RFC 9110 / Draft)]]
  * [[resources/integer-currency-minor-units|Financial Minor Units and Floating Point Traps]]
  * [[resources/audit-trail-immutability-patterns|Immutable Audit Trail Design Patterns]]
  * [[resources/rest-api-design-financial-services|RESTful API Design for Financial Services]]
  * [[resources/acid-transactions-in-relational-databases|ACID Transaction Guarantees in Embedded Relational Databases]]
  * [[resources/concurrency-race-conditions-wallets|Concurrency Race Conditions in Wallet Balances]]
  * [[resources/reconciliation-algorithms-accounting|Reconciliation Algorithms in Accounting Engines]]
  * [[resources/agentskills-specification-standards|Agent Skills Specification and Evaluation Standards]]
  * [[resources/hexagonal-architecture-provider-ports|Hexagonal Architecture and Provider Ports]]
  * [[resources/canonical-json-and-hash-chains|Canonical JSON and Hash Chains]]
  * [[resources/argon2id-password-hashing-and-session-tokens|Argon2id Password Hashing and Session Tokens]]
  * [[resources/crash-safety-and-deterministic-testing|Crash Safety and Deterministic Testing for an Embedded Ledger]]
* **[[archive/]]:** Completed stages, historical session records, and deprecated notes.

---

## 2. SDLC Traceability & Links

* **System Requirements Specification:** `docs/srs.pdf` (authoritative; `docs/srs.md` is the superseded Stage 1 baseline)
* **Design:** `docs/design/architecture.md`, `docs/design/data-model.md`, `docs/design/uml.md`
* **API Contract:** `docs/api/openapi.yaml`
* **Plan and Traceability:** `docs/plan.md`, `docs/traceability.md`
* **Agent Guidelines:** `AGENTS.md`
* **Architecture Decision Records:** `docs/adr/`
* **Execution Log:** [[log|Execution Log (brain/log.md)]]

---

## 3. Project Status Dashboard

* **Active Stage:** Stage 3 (Testing & CI) complete; Stage 4 (Review) next.
* **Stack:** Java 21, Javalin, SQLite (WAL), Maven wrapper.
* **Stage 2 Delivered:** architecture, ER and UML diagrams, OpenAPI contract, ADR-0001 to ADR-0012, plan, traceability matrix, `brain-refine`, `brain-query`, `local-check`, foundation skeleton (48 tests).
* **Stage 1:** complete (see [[projects/stage-1-review]]).
* **Stage 3 Delivered:** `simulate-transfer` (in CI), testing plan and results, defect log, 153 tests (95.5% instructions, 88.1% branches), traceability update.
* **Next:** open issues and link PRs (PC-7); performance workloads (T-31); Stage 4 live demonstration and release tag.
