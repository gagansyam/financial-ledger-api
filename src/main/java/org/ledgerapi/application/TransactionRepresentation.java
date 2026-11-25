package org.ledgerapi.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.ledgerapi.domain.CanonicalJson;
import org.ledgerapi.domain.Entry;
import org.ledgerapi.domain.LedgerTransaction;

/**
 * The common transaction schema of SRS 4.5. Sequences and entry numbers are decimal strings;
 * amounts are JSON integers. Field order is fixed so stored and replayed bodies are byte-equal.
 */
public final class TransactionRepresentation {
  private TransactionRepresentation() {}

  public static Map<String, Object> of(LedgerTransaction tx, String reversedBy) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("transactionId", tx.transactionId());
    body.put("type", tx.type().name());
    body.put("sequence", Long.toString(tx.sequence()));
    body.put("sourceAccountId", tx.sourceAccountId());
    body.put("destinationAccountId", tx.destinationAccountId());
    body.put("amount", tx.amount());
    body.put("currency", tx.currency());
    body.put("reference", tx.reference());
    body.put("reason", tx.reason());
    body.put("reversalOf", tx.reversalOf());
    body.put("reversedBy", reversedBy);
    body.put("createdAt", tx.createdAt());
    body.put("previousHash", tx.previousHash());
    body.put("hash", tx.hash());
    List<Object> entries = new ArrayList<>();
    for (Entry entry : tx.entries()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("entryNumber", Long.toString(entry.entryNumber()));
      item.put("accountId", entry.accountId());
      item.put("direction", entry.direction().name());
      item.put("amount", entry.amount());
      item.put("currency", entry.currency());
      item.put("createdAt", entry.createdAt());
      entries.add(item);
    }
    body.put("entries", entries);
    return body;
  }

  public static String json(LedgerTransaction tx, String reversedBy) {
    return CanonicalJson.write(of(tx, reversedBy));
  }
}
