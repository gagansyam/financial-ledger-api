package org.ledgerapi.application;

import org.ledgerapi.domain.LedgerTransaction;

/**
 * Outcome of a money-moving request: the exact status and body to return. {@code committed} is the
 * new transaction, or null when the stored response of an earlier commit is replayed (FR-2.6).
 */
public record MoneyResult(int status, String body, boolean replayed, LedgerTransaction committed) {}
