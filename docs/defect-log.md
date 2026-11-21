# Defect Log and Regression-Test Convention

* **Task:** `T-30` (`PC-10`, `TQ-8`)
* **Owner:** Gagan (maintained by all contributors across M2–M5)
* **Traceability:** `docs/plan.md`, `docs/traceability.md`

---

## 1. Regression-Test Convention (`TQ-8`)

Every confirmed defect discovered during development, PR review, skill execution (`local-check`, `simulate-transfer`), or testing must follow this workflow before its fix is merged:

1. **Assign a Defect ID:** Sequential `DEF-NNN` (e.g., `DEF-001`).
2. **Write a Failing Regression Test First:** Add a focused test in `src/test/java/...` referencing the defect ID in its Javadoc or `@DisplayName("DEF-NNN: ...")`.
3. **Fix & Verify:** Implement the fix and confirm `./mvnw verify` plus all repository validators (`validate_brain.py`, `validate_skills.py`, `validate_adrs.py`) pass.
4. **Log the Entry Below:** Record root cause, affected requirement/invariant, fix PR/commit, and regression test class/method.
5. **Update Second Brain if Architectural:** If the defect reveals a recurring pitfall, append a lesson to the relevant `brain/areas/` note.

---

## 2. Recorded Defects

| ID | Date | Severity | Requirement / Invariant | Summary & Root Cause | Fix Commit / PR | Regression Test | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| *None yet* | — | — | — | *M2 Wave 1 baseline initialized on 2026-11-21.* | — | — | — |
