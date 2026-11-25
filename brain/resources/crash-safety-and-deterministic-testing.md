---
title: "Crash Safety and Deterministic Testing for an Embedded Ledger"
date: 2026-11-25
tags: [testing, crash-recovery, concurrency, sqlite, flaky-tests, research]
source: "SQLite, 'Atomic Commit In SQLite', https://www.sqlite.org/atomiccommit.html; Martin Fowler, 'Eradicating Non-Determinism in Tests' (14 April 2011), https://martinfowler.com/articles/nonDeterminism.html"
status: resource
---

# Crash Safety and Deterministic Testing for an Embedded Ledger

## 1. Summary & Core Concept
Two questions shape the Stage 3 test suite. First, what does SQLite promise when a process dies mid-commit, and how can a test prove it? Second, how can tests of concurrent, time-dependent code give the same answer on every run?

SQLite promises that a transaction appears atomic even if it is interrupted by an operating system crash or a power failure: after recovery either all of its changes are visible or none are. Fowler argues that a test which passes sometimes and fails sometimes, with no change in code or environment, is worse than useless: it teaches the team to ignore red builds.

## 2. Key Architectural Mechanisms
* **Commit point (rollback mode).** SQLite flushes the rollback journal before changing the database, flushes the database, then deletes the journal. Deleting the journal is the commit. WAL mode reaches atomic commit by a different mechanism, documented separately.
* **Recovery on next open.** A leftover ("hot") journal is rolled back by the next connection, so the interrupted transaction looks as if it never began.
* **Durability depends on fsync.** SQLite assumes the OS reorders and buffers writes and that fsync returns only once writes are complete. `synchronous=FULL` flushes at every critical point; weaker settings trade durability for speed.
* **How SQLite itself tests this.** A crash-simulating file layer produces torn sector writes, garbage pages and reordered writes; each test reopens the database and checks that the transaction happened completely or not at all.
* **Deterministic tests (Fowler).**
  * Isolation: rebuild the starting state for every test rather than relying on clean-up; a fresh database file per test is one way.
  * Asynchrony: never use bare sleeps; prefer a completion signal, otherwise poll at a short interval with a generous, named timeout.
  * Time: wrap the system clock and substitute a fixed clock in tests; a static check can forbid direct clock calls.
  * Resource leaks: a small pool makes a leak fail at the first test that causes it.
  * Quarantine: move a flaky test out of the main pipeline and fix it quickly rather than tolerating it.

## 3. Pitfalls & Failure Modes
* A crash test that only throws an exception inside the JVM proves rollback, not crash recovery; killing the process is needed to exercise the recovery path (SRS: crash tests must distinguish a known pre-commit rollback from an interrupted commit).
* fsync is broken on some operating system versions and SQLite cannot detect it; tests on a developer laptop do not prove durability on every host.
* Deleting or renaming a hot journal (or WAL file) by hand corrupts the database; clean-up scripts must remove the whole disposable directory, never individual files.
* Race tests that assert on timing ("both requests finish within 50 ms") are flaky. Assert on outcomes instead: exactly one success, balances equal the arithmetic, reconciliation is consistent.
* A shared developer database makes test results depend on run order (TQ-5 forbids it).

## 4. Relevance to Financial Ledger API
* **FR-3.8, NFR-REL-1, NFR-REL-2:** rollback on injected failure and recovery after a killed process. Evidence: `FaultInjectionTest`, `CrashRecoveryTest` (a separate JVM is killed during writes, and the retry with the original key posts exactly once).
* **FR-3.2 to FR-3.6, FR-2.10:** concurrency tests assert outcomes, not timings. Evidence: `ConcurrencyTest`, the two-request race in `simulate-transfer` (SK-3).
* **TQ-5:** every test opens its own file under a JUnit temporary directory; `simulate-transfer` and `local-check` use a fresh temporary directory and delete it.
* **DC-4, TQ-1:** the clock, ID generator and hasher sit behind provider ports, so tests use a fixed clock and sequential IDs and can assert exact hashes.
* **FR-3.4:** `synchronous=FULL` is required for the durability argument above. Evidence: `SqliteDatabaseTest.everyConnectionUsesTheRequiredPragmas`.

## 5. Wikilinks & References
* [[index]]
* [[areas/concurrency-acid|Area: Concurrency, Locking and ACID Guarantees]]
* [[resources/sqlite-wal-locking-mechanics|SQLite WAL and Transaction Locking Mechanics]]
* [[resources/acid-transactions-in-relational-databases|ACID Transactions in Relational Databases]]
* [[resources/hexagonal-architecture-provider-ports|Hexagonal Architecture and Provider Ports]]
