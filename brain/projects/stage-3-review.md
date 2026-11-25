---
title: "Stage 3 Review: Testing and Continuous Integration"
date: 2026-11-25
tags: [stage-3, review, testing, ci]
status: completed
---

# Stage 3 Review: Testing and Continuous Integration

Review note for SB-2 and PC-2. Tracker: [[projects/stage-3-testing]]. Results: `docs/testing.md`, `docs/skill_evaluations.md` (Stage 3), `docs/defect_log.md`.

## 1. What Worked
* **Outcome assertions instead of timing assertions.** The concurrency, crash and race tests check only results (exactly one success, balances equal to the arithmetic, a consistent reconciliation, gap-free sequences). Repeated runs of the two-request race in `simulate-transfer` gave the same outcome every time [[resources/crash-safety-and-deterministic-testing]].
* **Provider ports paid off.** The fixed clock, sequential ID generator and fault-injecting store made exact-hash, rollback and expiry tests deterministic without sleeps [[resources/hexagonal-architecture-provider-ports]].
* **One fresh database per test.** Every test and both skills open their own file in a temporary directory, so TQ-5 holds by construction and run order does not matter.
* **Corruption tests document the threat boundary.** `ReconciliationCorruptionTest` has one test per check C1 to C7, each naming the tampering it detects [[resources/reconciliation-algorithms-accounting]].
* **The end-to-end skill runs in CI** on Linux and Windows after `./mvnw verify`, so SK-3 evidence comes from every push, not only from a stage-end run.

## 2. What Did Not Work
* **The first version of the race did not match SK-3** (defect D-2). It raced 12 workers, accepted several commits and counted `503` as acceptable, so it could not tell a correct ledger from an overdraft or a lock timeout. Reading the SK-3 pass rule closely, rather than checking only that the race ran, caught it.
* **The stop step claimed `graceful` without checking it** (defect D-3). On Windows `terminate()` is a hard kill. The script now needs the server's own `stopped` log line, and says plainly when a graceful stop cannot be observed.
* **Test data hit a schema rule** (T-D1): keys shorter than 16 characters tripped the `idempotency_key` length check. Fixtures that bypass the HTTP layer have to respect the same constraints.
* **Evidence gaps found late.** Empty and concurrently written reconciliation, totals beyond 2^53 − 1 and replay after restart for all three operations were added only when the traceability matrix was checked against SRS §8.1 and §11.1. The check should come at the start of the stage.
* **GitHub issue and PR links are still missing** from the defect log and the traceability matrix (PC-7).

## 3. What to Change in Stage 4 (Review)
* Open one issue per defect and per remaining plan task before starting work, and fill the Issue and PR columns as each PR merges.
* Build the performance workloads (NFR-PERF-1 to NFR-PERF-5, plan T-31) as scripts with fixed seeds that end with a reconciliation, and record each result; SRS §11.1 item 8 makes them a release condition.
* Rehearse the live demonstration (FR-1 to FR-5 in an HTTP client, then `simulate-transfer`, `local-check` and `brain-query`) and the walk-through from a brain note to an ADR, requirement, code and test, before the final release tag and changelog.
* Check the traceability matrix against SRS §8.1 and §11.1 at the start of the stage, not the end.
* Keep the `simulate-transfer` run in CI as the release gate for SRS §11.1 item 3.

## 4. Traceability
* **SRS deliverables:** §11.2 Stage 3, §8.1 TQ-1 to TQ-8, §11.1 items 2 to 7.
* **Requirements:** SK-3, SK-7, PC-2, PC-7, PC-10, TQ-5, TQ-6, TQ-8.
* **Notes:** [[index]], [[projects/stage-2-review]], [[projects/stage-3-testing]], [[areas/concurrency-acid]], [[areas/idempotency-design]].
