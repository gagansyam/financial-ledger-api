---
title: "Area: Concurrency, Locking and ACID Guarantees"
date: 2026-11-09
tags: [area, concurrency, sqlite, acid]
status: area
---

# Area: Concurrency, Locking and ACID Guarantees

Responsible for ensuring race-condition-free wallet operations, database serialization, and isolation.

## Core Mechanisms
* SQLite WAL mode: `PRAGMA journal_mode=WAL;`.
* `BEGIN IMMEDIATE` transaction acquisition to prevent race deadlocks.
* Wallet table `CHECK(balance >= 0)` constraint as invariant backstop.

## Related Resources
* [[resources/sqlite-wal-locking-mechanics|SQLite WAL and Transaction Locking Mechanics]]
* [[resources/acid-transactions-in-relational-databases|ACID Transactions in Relational Databases]]
* [[resources/concurrency-race-conditions-wallets|Concurrency Race Conditions in Wallet Balances]]
* [[resources/crash-safety-and-deterministic-testing|Crash Safety and Deterministic Testing for an Embedded Ledger]]
