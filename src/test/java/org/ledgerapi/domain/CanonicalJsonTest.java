package org.ledgerapi.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Known-answer vectors for ADR-0004 canonical serialization (TQ-1). */
class CanonicalJsonTest {
  @Test
  void sortsKeysRecursivelyAndKeepsNulls() {
    Map<String, Object> inner = new HashMap<>();
    inner.put("b", 2L);
    inner.put("a", null);
    Map<String, Object> outer = new HashMap<>();
    outer.put("z", List.of(1, "x"));
    outer.put("m", inner);
    assertThat(CanonicalJson.canonical(outer))
        .isEqualTo("{\"m\":{\"a\":null,\"b\":2},\"z\":[1,\"x\"]}");
  }

  @Test
  void writeKeepsInsertionOrder() {
    Map<String, Object> ordered = new LinkedHashMap<>();
    ordered.put("z", 1L);
    ordered.put("a", 2L);
    assertThat(CanonicalJson.write(ordered)).isEqualTo("{\"z\":1,\"a\":2}");
  }

  @Test
  void escapesExactlyAsSpecified() {
    String input = "q\" s\\ \b\t\n\f\r \u0001\u001f é😀/";
    assertThat(CanonicalJson.canonical(input))
        .isEqualTo("\"q\\\" s\\\\ \\b\\t\\n\\f\\r \\u0001\\u001f é😀/\"");
  }

  @Test
  void numbersAreIntegersOnly() {
    assertThat(CanonicalJson.canonical(Long.MIN_VALUE)).isEqualTo("-9223372036854775808");
    assertThat(CanonicalJson.canonical(0)).isEqualTo("0");
    assertThatThrownBy(() -> CanonicalJson.canonical(1.5))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void listsMayHoldNulls() {
    List<Object> values = new ArrayList<>(Arrays.asList(null, "a"));
    assertThat(CanonicalJson.canonical(values)).isEqualTo("[null,\"a\"]");
  }

  @Test
  void fingerprintsAreCanonicalAndDistinguishOperations() {
    String w = "00000000-0000-4000-8000-000000000001";
    assertThat(RequestFingerprint.funding(w, 5, "INR"))
        .isEqualTo(
            "{\"body\":{\"amount\":5,\"currency\":\"INR\"},\"op\":\"FUNDING\",\"path\":{\"walletId\":\""
                + w
                + "\"}}");
    assertThat(RequestFingerprint.reversal(w, null))
        .isEqualTo(
            "{\"body\":{\"reason\":null},\"op\":\"REVERSAL\",\"path\":{\"transactionId\":\""
                + w
                + "\"}}");
    assertThat(RequestFingerprint.transfer(w, w, 1, "INR", null))
        .contains("\"reference\":null")
        .startsWith("{\"body\":{\"amount\":1,");
    assertThat(RequestFingerprint.transfer(w, w, 1, "INR", ""))
        .isNotEqualTo(RequestFingerprint.transfer(w, w, 1, "INR", null));
  }

  @Test
  void sortsObjectKeysDeterministicallyRegardlessOfInsertionOrder() {
    Map<String, Object> m1 = new LinkedHashMap<>();
    m1.put("z", 1L);
    m1.put("a", "first");
    m1.put("m", null);

    Map<String, Object> m2 = new LinkedHashMap<>();
    m2.put("m", null);
    m2.put("z", 1);
    m2.put("a", "first");

    assertThat(CanonicalJson.canonical(m1)).isEqualTo("{\"a\":\"first\",\"m\":null,\"z\":1}");
    assertThat(CanonicalJson.canonical(m2)).isEqualTo(CanonicalJson.canonical(m1));
    assertThat(CanonicalJson.canonical(m1).getBytes(StandardCharsets.UTF_8))
        .isEqualTo("{\"a\":\"first\",\"m\":null,\"z\":1}".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void escapesControlCharactersAndQuotesPerAdr0004() {
    String raw = "quote:\" slash:\\ bs:\b tab:\t nl:\n ff:\f cr:\r nul:\u0000 esc:\u001f ok:₹";
    assertThat(CanonicalJson.canonical(raw))
        .isEqualTo(
            "\"quote:\\\" slash:\\\\ bs:\\b tab:\\t nl:\\n ff:\\f cr:\\r nul:\\u0000 esc:\\u001f ok:₹\"");
  }

  @Test
  void serializesNestedArraysAndNullsAndEmptyStrings() {
    Map<String, Object> obj = new HashMap<>();
    obj.put("empty", "");
    obj.put("missing", null);
    obj.put("items", List.of(Map.of("b", -5L, "a", 0L)));

    assertThat(CanonicalJson.canonical(obj))
        .isEqualTo("{\"empty\":\"\",\"items\":[{\"a\":0,\"b\":-5}],\"missing\":null}");
  }

  @Test
  void rejectsUnsupportedTypesAndNonStringKeys() {
    assertThatThrownBy(() -> CanonicalJson.canonical(Boolean.TRUE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CanonicalJson.canonical(Map.of(1, "x")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
