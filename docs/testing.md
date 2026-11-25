# Continuous Testing and CI Plan and Results

Stage 3 deliverable (SRS §11.2). It covers how the product is tested, what runs on every push and pull request, and the latest results. Requirements: TQ-1 to TQ-8, PC-8, PC-10, SK-3, and SRS §8.1 and §11.1.

Updated: 2026-11-25 (Stage 3)

## 1. Principles

* **One fresh database per test (TQ-5).** Each test opens its own SQLite file in a JUnit `@TempDir`. `local-check` and `simulate-transfer` each start the jar on a new temporary directory and delete it afterwards. Nothing reads or writes a developer or production database.
* **File-backed WAL everywhere it matters (DC-2).** Concurrency, crash, integration and system tests use real files in WAL mode with `synchronous=FULL`, never an in-memory database.
* **Deterministic inputs.** The clock, ID generator and hasher sit behind provider ports (DC-4), so tests use `FixedClock` and `SequentialIds` and can assert exact hashes, timestamps and expiry.
* **Assert outcomes, not timings.** Race and crash tests check results (exactly one success, balances equal the arithmetic, gap-free sequences, a valid hash chain, a consistent reconciliation) and never depend on how long something took.
* **One regression guard per significant defect (PC-10, TQ-8).** See `docs/defect_log.md`.

## 2. Test Layers

| Layer | SRS | Test classes | What they prove |
|-------|-----|--------------|-----------------|
| Unit | TQ-1 | `LedgerRulesTest`, `MoneyTest`, `CurrencyCodeTest`, `IdentifiersTest`, `TextAndIdRulesTest`, `CanonicalJsonTest`, `RequestFingerprintTest`, `TransactionHasherTest`, `TimestampsTest`, `ErrorCodeTest`, `BodySchemaTest`, `HttpSupportTest`, `ConfigLoaderTest`, `AuthThrottleTest`, `HealthServiceTest`, `Argon2idPasswordHasherTest` | Amount and balance limits with checked arithmetic, exact integer parsing, canonical JSON and request fingerprints, hash-chain inputs against a known-answer vector, validation rules, the error catalogue, configuration defaults and rejection, throttling windows |
| Integration | TQ-2 | `SqliteDatabaseTest`, `SchemaMigratorTest`, `FaultInjectionTest`, `CrashRecoveryTest`, `ConcurrencyTest`, `IdempotencyRestartTest`, `ReconciliationIntegrationTest`, `ReconciliationCorruptionTest` | Connection pragmas, atomic commit and rollback, append-only triggers, migration idempotence, a fault injected before each persistence step leaves no trace, a writer process killed mid-transfer recovers and a retry posts exactly once, lock timeout becomes `STORAGE_BUSY` with no partial effects, replay after restart |
| System | TQ-3 | `HealthSystemTest`, `LedgerFlowSystemTest`, `RequestValidationSystemTest`, `ThrottleAndLoggingSystemTest` | Every FR-1 to FR-5 group and FR-7 authentication and ownership over real HTTP against the running application, plus health, restart and request logging |
| Negative | TQ-4 | `RequestValidationSystemTest`, `LedgerFlowSystemTest` (`businessRuleRejectionsLeaveNoTrace`, `walletRules`, `fundingRules`, `reversalMirrorsOriginalOnceAndOnlyOnce`), `ConcurrencyTest` | Zero, negative, fractional and exponent amounts, insufficient funds, malformed IDs, unknown and duplicate fields, unsupported currency, reused keys, concurrent same-key and different-key requests, duplicate reversals, balance limits |
| Precision | TQ-6 | `LedgerRulesTest`, `BodySchemaTest`, `RequestValidationSystemTest.amountMustBeAnIntegerTokenInRange`, `ReconciliationIntegrationTest.aggregateTotalsBeyondTwoToTheFiftyThreeAreExact`, `ArchitectureTest.noFloatingPointInMoneyLayers` | Integer-only amounts, overflow prevention, totals above 2^53 − 1 reported exactly as decimal strings, no `float` or `double` in the domain or application layers |
| Reconciliation | §8.1 | `ReconciliationIntegrationTest` (empty, concurrently written, very large), `ReconciliationCorruptionTest` (clean, then one deliberate corruption per check C1 to C7), `LedgerFlowSystemTest` | Each check reports its discrepancy, and a reconciliation running while transfers commit sees only consistent snapshots |
| Security | §8.1 | `RequestValidationSystemTest`, `LedgerFlowSystemTest`, `ThrottleAndLoggingSystemTest`, `HttpSupportTest`, `ConfigLoaderTest` | Ownership, admin separation, expired and revoked sessions, login and registration throttling, trusted-proxy handling, log redaction and injection, no internal details in errors |
| Architecture | NFR-MNT-1 | `ArchitectureTest` | The domain depends on nothing outside it, the application layer only on the domain and provider ports, and provider ports are technology-free interfaces |
| End to end | SK-3, §11.1 item 3 | `scripts/simulate_transfer.py` (skill `simulate-transfer`) | The packaged jar moves money correctly through the public API: replay, key reuse, ownership, a two-request race with exactly one success, reversal, reconciliation and logout |
| Health | SK-2 | `scripts/local_check.py` (skill `local-check`) | Build, all tests and gates, start, `/health`, graceful stop, database removed |

Crash tests distinguish the two failure kinds the SRS names. `FaultInjectionTest` raises a known failure before the commit inside the JVM and checks the rollback. `CrashRecoveryTest` kills a separate writer process during transfers, reopens the file and checks that recovery left whole transactions only, and that retrying with the original keys posts each transfer exactly once.

## 3. Quality Gates in `./mvnw verify`

| Gate | Tool | Fails the build when |
|------|------|----------------------|
| Formatting | Spotless (google-java-format) | any file differs from the formatted version |
| Compiler | `javac -Xlint:all,-processing,-serial -Werror` | any warning in those categories |
| Toolchain and dependency rules | Maven Enforcer | Java is older than 21, Maven is older than 3.9, or transitive dependency versions do not converge |
| Tests | Surefire | any test fails |
| Coverage | JaCoCo `check` | line coverage of the bundle is below 85% (NFR-MNT-2) |
| Architecture | `ArchitectureTest` | a layer imports something it must not |

## 4. Continuous Integration

`.github/workflows/ci.yml` runs on every push to `main` and every pull request to `main`. A newer run on the same ref cancels the older one.

| Job | Runs on | Steps |
|-----|---------|-------|
| Build and test | `ubuntu-latest`, `windows-latest` (NFR-POR-1) | Temurin 21, `./mvnw -B verify` (all gates above), then `python scripts/simulate_transfer.py` against the jar just built, then uploads the Surefire and JaCoCo reports |
| Brain, skills, ADRs and lint | `ubuntu-latest` | `validate_skills.py`, `validate_brain.py`, `validate_adrs.py`, `flake8 scripts/`, and a grep that fails on floating-point types in the domain and application packages |
| Secret scan | `ubuntu-latest` | gitleaks over the full history |

Every job starts from a fresh checkout and every database it creates lives in a temporary directory on the runner (TQ-5). Branch protection on `main` should require all three jobs before merge (PC-8).

## 5. Running Locally

```bash
./mvnw -B verify                               # everything CI's build job runs, except simulate-transfer
./mvnw -B -q spotless:apply                    # fix formatting
./mvnw -B test -Dtest=ConcurrencyTest          # one class
python3 scripts/local_check.py                 # skill local-check
python3 scripts/simulate_transfer.py --build   # skill simulate-transfer
python3 scripts/validate_skills.py && python3 scripts/validate_brain.py && python3 scripts/validate_adrs.py
```

## 6. Results (2026-11-25)

### 6.1 `./mvnw -B verify`

`BUILD SUCCESS` in 3 min 54 s. 153 tests, 0 failures, 0 errors, 0 skipped. All coverage checks met.

| Coverage | Covered | Total | Percent |
|----------|---------|-------|---------|
| Instructions | 10738 | 11244 | 95.5% |
| Branches | 712 | 808 | 88.1% |
| Lines | 2252 | 2365 | 95.2% |

| Test class | Tests | Time (s) |
|------------|-------|----------|
| `ArchitectureTest` | 4 | 3.5 |
| `application.AuthThrottleTest` | 4 | 2.6 |
| `application.FaultInjectionTest` | 7 | 10.0 |
| `application.HealthServiceTest` | 5 | 0.2 |
| `config.ConfigLoaderTest` | 6 | 0.5 |
| `domain.CanonicalJsonTest` | 10 | 0.0 |
| `domain.CurrencyCodeTest` | 2 | 0.0 |
| `domain.ErrorCodeTest` | 4 | 0.1 |
| `domain.IdentifiersTest` | 2 | 0.0 |
| `domain.LedgerRulesTest` | 5 | 0.1 |
| `domain.MoneyTest` | 5 | 0.0 |
| `domain.RequestFingerprintTest` | 2 | 0.0 |
| `domain.TextAndIdRulesTest` | 5 | 0.1 |
| `domain.TimestampsTest` | 2 | 0.0 |
| `domain.TransactionHasherTest` | 5 | 0.0 |
| `http.BodySchemaTest` | 5 | 1.0 |
| `http.HealthSystemTest` | 7 | 20.3 |
| `http.HttpSupportTest` | 5 | 0.0 |
| `infrastructure.sqlite.SchemaMigratorTest` | 11 | 1.3 |
| `infrastructure.sqlite.SqliteDatabaseTest` | 8 | 0.5 |
| `infrastructure.system.Argon2idPasswordHasherTest` | 3 | 0.3 |
| `integration.ConcurrencyTest` | 4 | 6.7 |
| `integration.CrashRecoveryTest` | 3 | 27.2 |
| `integration.IdempotencyRestartTest` | 1 | 0.3 |
| `integration.ReconciliationCorruptionTest` | 8 | 1.3 |
| `integration.ReconciliationIntegrationTest` | 3 | 3.9 |
| `system.LedgerFlowSystemTest` | 13 | 6.0 |
| `system.RequestValidationSystemTest` | 9 | 3.3 |
| `system.ThrottleAndLoggingSystemTest` | 5 | 1.9 |

### 6.2 Skills

`simulate-transfer` and `local-check` both report `OVERALL: PASS`; the full outputs are in `docs/skill_evaluations.md` (Stage 3). The race step was repeated on five further runs, and each one gave exactly one `201` and one `422 INSUFFICIENT_FUNDS`.

### 6.3 Repository checks

`validate_skills.py` (5 skills), `validate_brain.py`, `validate_adrs.py` (12 ADRs) and `flake8 scripts/` all pass.

## 7. Known Limits

* On Windows the scripts cannot send a termination signal, so `terminate()` is a hard kill; the stop step says so, and graceful shutdown (FR-6.3) is checked on the Linux runner, and the in-process close path is exercised on both platforms by `HealthSystemTest.restartOnSameDatabaseSucceeds`.
* Durability under power loss depends on the host honouring fsync (assumption A-3). Kill tests prove crash recovery for a process, not for the disk.
* Performance targets (NFR-PERF-1 to NFR-PERF-5) are Stage 4 work (plan T-31).
