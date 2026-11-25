package org.ledgerapi.application;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.ledgerapi.domain.LedgerRules;
import org.ledgerapi.domain.Timestamps;
import org.ledgerapi.domain.TransactionHasher;
import org.ledgerapi.domain.TransactionHasher.HashEntry;
import org.ledgerapi.domain.TransactionHasher.HashInput;
import org.ledgerapi.provider.ClockProvider;
import org.ledgerapi.provider.HashProvider;
import org.ledgerapi.provider.LedgerSnapshot;
import org.ledgerapi.provider.LedgerSnapshot.AccountRow;
import org.ledgerapi.provider.LedgerSnapshot.EntryRow;
import org.ledgerapi.provider.LedgerSnapshot.TransactionRow;
import org.ledgerapi.provider.LedgerStore;

/**
 * Read-only ledger reconciliation (FR-5, ADR-0010). Every check runs over one read snapshot with
 * {@link BigInteger} accumulation; corrupt rows become discrepancies, never exceptions.
 */
public final class ReconciliationService {
  private static final BigInteger MAX_WALLET = BigInteger.valueOf(LedgerRules.MAX_WALLET_BALANCE);
  private static final Set<String> TYPES = Set.of("FUNDING", "TRANSFER", "REVERSAL");

  private final LedgerStore store;
  private final ClockProvider clock;
  private final HashProvider hashes;
  private final List<String> configuredCurrencies;

  public ReconciliationService(
      LedgerStore store,
      ClockProvider clock,
      HashProvider hashes,
      List<String> configuredCurrencies) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.hashes = Objects.requireNonNull(hashes, "hashes");
    this.configuredCurrencies = List.copyOf(configuredCurrencies);
  }

  /** One detected inconsistency; {@code subjectId} may be null. */
  public record Discrepancy(String check, String subjectId, String expected, String actual) {}

  /** Totals of one configured currency. */
  public record CurrencyTotals(String currency, BigInteger totalCredits, BigInteger totalDebits) {}

  /** The FR-5.4 report. */
  public record Report(
      boolean consistent,
      String checkedAt,
      long snapshotSequence,
      long transactionCount,
      long entryCount,
      List<CurrencyTotals> totals,
      List<Discrepancy> discrepancies) {

    public Report {
      totals = List.copyOf(totals);
      discrepancies = List.copyOf(discrepancies);
    }

    /** Public body: counts, sequence and totals as decimal strings (SRS 4.5, BR-12). */
    public Map<String, Object> toBody() {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("status", consistent ? "CONSISTENT" : "INCONSISTENT");
      body.put("checkedAt", checkedAt);
      body.put("snapshotSequence", Long.toString(snapshotSequence));
      body.put("transactionCount", Long.toString(transactionCount));
      body.put("entryCount", Long.toString(entryCount));
      List<Object> totalItems = new ArrayList<>();
      for (CurrencyTotals t : totals) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("currency", t.currency());
        item.put("totalCredits", t.totalCredits().toString());
        item.put("totalDebits", t.totalDebits().toString());
        totalItems.add(item);
      }
      body.put("totals", totalItems);
      List<Object> items = new ArrayList<>();
      for (Discrepancy d : discrepancies) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("check", d.check());
        item.put("subjectId", d.subjectId());
        item.put("expected", d.expected());
        item.put("actual", d.actual());
        items.add(item);
      }
      body.put("discrepancies", items);
      return body;
    }
  }

  public Report reconcile() {
    LedgerSnapshot snapshot = store.read(unit -> unit.snapshot());
    return new Checker(snapshot).run(Timestamps.format(clock.now()));
  }

  /** Per-currency credit and debit sums. */
  private static final class Sums {
    BigInteger credits = BigInteger.ZERO;
    BigInteger debits = BigInteger.ZERO;
  }

  private final class Checker {
    private final LedgerSnapshot snapshot;
    private final List<Discrepancy> found = new ArrayList<>();
    private final Map<String, AccountRow> accounts = new HashMap<>();
    private final Map<String, TransactionRow> transactions = new HashMap<>();
    private final Map<String, List<EntryRow>> entriesByTx = new HashMap<>();

    Checker(LedgerSnapshot snapshot) {
      this.snapshot = snapshot;
      for (AccountRow a : snapshot.accounts()) {
        accounts.put(a.accountId(), a);
      }
      for (TransactionRow t : snapshot.transactions()) {
        transactions.put(t.transactionId(), t);
      }
      for (EntryRow e : snapshot.entries()) {
        entriesByTx.computeIfAbsent(e.transactionId(), k -> new ArrayList<>()).add(e);
      }
    }

    Report run(String checkedAt) {
      checkStructure();
      Map<String, Sums> byCurrency = checkTotals();
      checkStoredBalances();
      checkBounds();
      checkConservation();
      checkSequencesAndHashes();
      checkReversals();
      long snapshotSequence = 0;
      for (TransactionRow t : snapshot.transactions()) {
        if (t.sequence() != null && t.sequence() > snapshotSequence) {
          snapshotSequence = t.sequence();
        }
      }
      List<CurrencyTotals> totals = new ArrayList<>();
      for (String currency : configuredCurrencies) {
        Sums sums = byCurrency.getOrDefault(currency, new Sums());
        totals.add(new CurrencyTotals(currency, sums.credits, sums.debits));
      }
      return new Report(
          found.isEmpty(),
          checkedAt,
          snapshotSequence,
          snapshot.transactions().size(),
          snapshot.entries().size(),
          totals,
          found);
    }

    private void add(String check, String subject, String expected, Object actual) {
      found.add(new Discrepancy(check, subject, expected, String.valueOf(actual)));
    }

    // C1: transaction structure
    private void checkStructure() {
      for (EntryRow e : snapshot.entries()) {
        if (!transactions.containsKey(e.transactionId())) {
          add("C1", e.transactionId(), "entry references an existing transaction", "missing");
        }
        if (!accounts.containsKey(e.accountId())) {
          add("C1", e.transactionId(), "entry references an existing account", e.accountId());
        }
      }
      for (TransactionRow t : snapshot.transactions()) {
        String id = t.transactionId();
        if (!TYPES.contains(t.type())) {
          add("C1", id, "type FUNDING, TRANSFER or REVERSAL", t.type());
        }
        if (t.amount() == null || !LedgerRules.isValidAmount(t.amount())) {
          add("C1", id, "amount within 1.." + LedgerRules.MAX_AMOUNT, t.amount());
        }
        if (Objects.equals(t.sourceAccountId(), t.destinationAccountId())) {
          add("C1", id, "different source and destination accounts", t.sourceAccountId());
        }
        checkEndpoint(id, "source", t.sourceAccountId(), t.currency());
        checkEndpoint(id, "destination", t.destinationAccountId(), t.currency());
        List<EntryRow> entries = entriesByTx.getOrDefault(id, List.of());
        long debits = entries.stream().filter(e -> "DEBIT".equals(e.direction())).count();
        long credits = entries.stream().filter(e -> "CREDIT".equals(e.direction())).count();
        if (entries.size() != 2 || debits != 1 || credits != 1) {
          add(
              "C1",
              id,
              "exactly one DEBIT and one CREDIT entry",
              entries.size() + " entries, " + debits + " debit, " + credits + " credit");
        }
        for (EntryRow e : entries) {
          String label = "entry " + e.entryNumber();
          if (!Objects.equals(e.amount(), t.amount())) {
            add("C1", id, label + " amount " + t.amount(), e.amount());
          }
          if (!Objects.equals(e.currency(), t.currency())) {
            add("C1", id, label + " currency " + t.currency(), e.currency());
          }
          String expectedAccount =
              "DEBIT".equals(e.direction()) ? t.sourceAccountId() : t.destinationAccountId();
          if (!Objects.equals(e.accountId(), expectedAccount)) {
            add("C1", id, label + " account " + expectedAccount, e.accountId());
          }
        }
      }
    }

    private void checkEndpoint(String id, String role, String accountId, String currency) {
      AccountRow account = accounts.get(accountId);
      if (account == null) {
        add("C1", id, role + " account exists", accountId);
      } else if (!Objects.equals(account.currency(), currency)) {
        add("C1", id, role + " account currency " + currency, account.currency());
      }
    }

    // C2: balanced totals overall and per currency
    private Map<String, Sums> checkTotals() {
      Map<String, Sums> byCurrency = new TreeMap<>();
      Sums overall = new Sums();
      for (EntryRow e : snapshot.entries()) {
        BigInteger amount = e.amount() == null ? BigInteger.ZERO : BigInteger.valueOf(e.amount());
        Sums sums = byCurrency.computeIfAbsent(String.valueOf(e.currency()), k -> new Sums());
        if ("CREDIT".equals(e.direction())) {
          sums.credits = sums.credits.add(amount);
          overall.credits = overall.credits.add(amount);
        } else if ("DEBIT".equals(e.direction())) {
          sums.debits = sums.debits.add(amount);
          overall.debits = overall.debits.add(amount);
        }
      }
      for (Map.Entry<String, Sums> c : byCurrency.entrySet()) {
        Sums s = c.getValue();
        if (!s.credits.equals(s.debits)) {
          add(
              "C2",
              c.getKey(),
              "credits equal debits",
              "credits " + s.credits + ", debits " + s.debits);
        }
      }
      if (!overall.credits.equals(overall.debits)) {
        add(
            "C2",
            null,
            "credits equal debits overall",
            "credits " + overall.credits + ", debits " + overall.debits);
      }
      return byCurrency;
    }

    // C3: stored balances equal ledger sums
    private void checkStoredBalances() {
      Map<String, BigInteger> computed = new HashMap<>();
      for (EntryRow e : snapshot.entries()) {
        if (e.amount() == null) {
          continue;
        }
        BigInteger amount = BigInteger.valueOf(e.amount());
        if ("DEBIT".equals(e.direction())) {
          amount = amount.negate();
        } else if (!"CREDIT".equals(e.direction())) {
          continue;
        }
        computed.merge(e.accountId(), amount, BigInteger::add);
      }
      for (AccountRow a : snapshot.accounts()) {
        BigInteger expected = computed.getOrDefault(a.accountId(), BigInteger.ZERO);
        Object actual = a.balance() == null ? "non-integer" : a.balance();
        if (a.balance() == null || !expected.equals(BigInteger.valueOf(a.balance()))) {
          add("C3", a.accountId(), expected.toString(), actual);
        }
      }
    }

    // C4: balance bounds
    private void checkBounds() {
      for (AccountRow a : snapshot.accounts()) {
        if (a.balance() == null) {
          add("C4", a.accountId(), "signed 64-bit integer balance", "non-integer");
          continue;
        }
        if ("WALLET".equals(a.kind())) {
          BigInteger balance = BigInteger.valueOf(a.balance());
          if (balance.signum() < 0 || balance.compareTo(MAX_WALLET) > 0) {
            add("C4", a.accountId(), "wallet balance within 0.." + MAX_WALLET, a.balance());
          }
        } else if (!"SYSTEM".equals(a.kind())) {
          add("C4", a.accountId(), "account kind WALLET or SYSTEM", a.kind());
        }
      }
    }

    // C5: per-currency conservation and one system account per configured currency
    private void checkConservation() {
      Set<String> currencies = new TreeSet<>(configuredCurrencies);
      Map<String, BigInteger> sums = new HashMap<>();
      Map<String, Integer> systems = new HashMap<>();
      for (AccountRow a : snapshot.accounts()) {
        String currency = String.valueOf(a.currency());
        currencies.add(currency);
        if ("SYSTEM".equals(a.kind())) {
          systems.merge(currency, 1, Integer::sum);
        }
        if (a.balance() != null) {
          sums.merge(currency, BigInteger.valueOf(a.balance()), BigInteger::add);
        }
      }
      for (String currency : currencies) {
        BigInteger sum = sums.getOrDefault(currency, BigInteger.ZERO);
        if (sum.signum() != 0) {
          add("C5", currency, "wallet balances plus system balance equal 0", sum);
        }
        int count = systems.getOrDefault(currency, 0);
        if (configuredCurrencies.contains(currency) && count != 1) {
          add("C5", currency, "exactly one SYSTEM account", count + " SYSTEM accounts");
        }
      }
    }

    // C6: gap-free numbering and hash chain
    private void checkSequencesAndHashes() {
      List<TransactionRow> ordered = new ArrayList<>();
      long expectedSequence = 1;
      boolean sequenceReported = false;
      for (TransactionRow t : snapshot.transactions()) {
        if (t.sequence() == null) {
          add("C6", t.transactionId(), "integer sequence", "non-integer");
          continue;
        }
        ordered.add(t);
        if (!sequenceReported && t.sequence() != expectedSequence) {
          add("C6", t.transactionId(), "sequence " + expectedSequence, t.sequence());
          sequenceReported = true;
        }
        expectedSequence++;
      }
      long expectedEntry = 1;
      for (EntryRow e : snapshot.entries()) {
        if (e.entryNumber() == null || e.entryNumber() != expectedEntry) {
          add(
              "C6",
              e.transactionId(),
              "entry number " + expectedEntry,
              e.entryNumber() == null ? "non-integer" : e.entryNumber());
          break;
        }
        expectedEntry++;
      }
      String previous = TransactionHasher.GENESIS_HASH;
      for (TransactionRow t : ordered) {
        if (!Objects.equals(t.previousHash(), previous)) {
          add("C6", t.transactionId(), "previousHash " + previous, t.previousHash());
        }
        String recomputed = recomputeHash(t);
        if (!Objects.equals(t.hash(), recomputed)) {
          add("C6", t.transactionId(), "hash " + recomputed, t.hash());
        }
        previous = t.hash();
      }
    }

    private String recomputeHash(TransactionRow t) {
      List<HashEntry> entries = new ArrayList<>();
      for (EntryRow e : entriesByTx.getOrDefault(t.transactionId(), List.of())) {
        entries.add(
            new HashEntry(
                e.entryNumber(),
                e.accountId(),
                e.direction(),
                e.amount(),
                e.currency(),
                e.createdAt()));
      }
      HashInput input =
          new HashInput(
              t.transactionId(),
              t.sequence(),
              t.type(),
              t.sourceAccountId(),
              t.destinationAccountId(),
              t.amount(),
              t.currency(),
              t.reference(),
              t.reason(),
              t.reversalOf(),
              t.createdAt(),
              t.previousHash(),
              entries);
      return hashes.sha256Hex(
          TransactionHasher.canonicalInput(input).getBytes(StandardCharsets.UTF_8));
    }

    // C7: reversal integrity
    private void checkReversals() {
      Map<String, Integer> reversalsPerOriginal = new HashMap<>();
      for (TransactionRow t : snapshot.transactions()) {
        boolean isReversal = "REVERSAL".equals(t.type());
        if (!isReversal) {
          if (t.reversalOf() != null) {
            add("C7", t.transactionId(), "reversalOf null for type " + t.type(), t.reversalOf());
          }
          continue;
        }
        if (t.reversalOf() == null) {
          add("C7", t.transactionId(), "reversalOf references the original", "null");
          continue;
        }
        reversalsPerOriginal.merge(t.reversalOf(), 1, Integer::sum);
        TransactionRow original = transactions.get(t.reversalOf());
        if (original == null) {
          add("C7", t.transactionId(), "original transaction exists", t.reversalOf());
          continue;
        }
        if ("REVERSAL".equals(original.type())) {
          add("C7", t.transactionId(), "original is not a reversal", original.transactionId());
        }
        boolean mirrors =
            Objects.equals(t.sourceAccountId(), original.destinationAccountId())
                && Objects.equals(t.destinationAccountId(), original.sourceAccountId())
                && Objects.equals(t.amount(), original.amount())
                && Objects.equals(t.currency(), original.currency());
        if (!mirrors) {
          add(
              "C7",
              t.transactionId(),
              "mirror of "
                  + original.transactionId()
                  + " ("
                  + original.destinationAccountId()
                  + " -> "
                  + original.sourceAccountId()
                  + ", "
                  + original.amount()
                  + " "
                  + original.currency()
                  + ")",
              "("
                  + t.sourceAccountId()
                  + " -> "
                  + t.destinationAccountId()
                  + ", "
                  + t.amount()
                  + " "
                  + t.currency()
                  + ")");
        }
      }
      for (Map.Entry<String, Integer> r : reversalsPerOriginal.entrySet()) {
        if (r.getValue() > 1) {
          add("C7", r.getKey(), "at most one reversal", r.getValue() + " reversals");
        }
      }
    }
  }
}
