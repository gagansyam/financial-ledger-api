---
title: "Stage 3 Tracker: Testing and Continuous Integration"
date: 2026-11-25
tags: [stage-3, testing, ci, tracker]
status: completed
---

# Stage 3 Tracker: Testing and Continuous Integration

SRS §11.2 Stage 3 deliverables: a continuous testing and CI plan with results, `simulate-transfer` with evaluations, a defect log with regression tests, updated traceability, and a stage review note with the required skill results.

## 1. Deliverables

| Deliverable | Location | Status |
|-------------|----------|--------|
| Continuous testing and CI plan, with results | [`docs/testing.md`](../../docs/testing.md), `.github/workflows/ci.yml` | Done |
| `simulate-transfer` skill and script | `skills/simulate-transfer/SKILL.md`, `scripts/simulate_transfer.py` | Done; runs in CI on Linux and Windows |
| Skill evaluations (SK-7) | [`docs/skill_evaluations.md`](../../docs/skill_evaluations.md), Stage 3 section | Done |
| Defect log and regression guards (PC-10, TQ-8) | [`docs/defect_log.md`](../../docs/defect_log.md) | Done; issue and PR links to add |
| Traceability update | [`docs/traceability.md`](../../docs/traceability.md) | Done |
| Reconciliation evidence on clean, empty, concurrent and corrupted ledgers (§8.1) | `ReconciliationIntegrationTest`, `ReconciliationCorruptionTest`, `LedgerFlowSystemTest` | Done |
| Exact totals beyond 2^53 − 1 (TQ-6) | `ReconciliationIntegrationTest.aggregateTotalsBeyondTwoToTheFiftyThreeAreExact` | Done |
| Replay after restart and after state changes (§11.1 item 4) | `IdempotencyRestartTest`, `CrashRecoveryTest` | Done |
| Review note (SB-2, PC-2) | [[projects/stage-3-review]] | Done |

## 2. Brain Query Used for the Race Design

```
Question: How must the simulate-transfer race be built so its outcome is deterministic (SRS SK-3, FR-3.2, FR-3.6)?
Answer: Two debits that cannot both fit against one source wallet must be sent together, because a TOCTOU double spend only shows when both requests read the same balance [[resources/concurrency-race-conditions-wallets]]. Every money-moving operation reads and checks the balance inside one BEGIN IMMEDIATE transaction on a single writer, so the second request sees the first one's debit (ADR-0003, SRS FR-3.1). The test should assert outcomes, not timings: exactly one success, balances equal to the arithmetic, and a consistent reconciliation [[resources/crash-safety-and-deterministic-testing]]. A lock timeout becomes 503 STORAGE_BUSY with no partial effects (SRS FR-3.5), and SK-3 does not count it as a pass, so a 503 is a failure to diagnose rather than an accepted outcome.
Sources: [[resources/concurrency-race-conditions-wallets]], [[resources/crash-safety-and-deterministic-testing]], [[areas/concurrency-acid]], ADR-0003, SRS FR-3.1, FR-3.5, SK-3
Gaps: none
```

The result was the race in step 11 of `simulate-transfer`: amount `remaining // 2 + 1`, two threads released by a barrier, and exactly one `201` plus one `422 INSUFFICIENT_FUNDS` required (defect D-2).

## 3. Links

* [[index]]
* [[projects/stage-2-design]]
* [[projects/stage-3-review]]
* [[areas/concurrency-acid]]
* [[areas/idempotency-design]]
