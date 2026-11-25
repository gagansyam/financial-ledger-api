package org.ledgerapi.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

/**
 * Resolves the source IP used by the authentication throttle (NFR-SEC-9). {@code X-Forwarded-For}
 * is consulted only when the direct peer is a configured trusted proxy; the list is then walked
 * from the right and the first address that is not itself a trusted proxy is the client.
 */
final class ClientAddress {
  private static final int MAX_ADDRESS_LENGTH = 64;

  private final Set<String> trustedProxies;

  ClientAddress(List<String> trustedProxies) {
    this.trustedProxies = Set.copyOf(trustedProxies);
  }

  String resolve(String peer, Enumeration<String> forwardedHeaders) {
    String direct = peer == null ? "unknown" : peer;
    if (!trustedProxies.contains(direct) || forwardedHeaders == null) {
      return direct;
    }
    List<String> hops = new ArrayList<>();
    for (String header : Collections.list(forwardedHeaders)) {
      for (String part : header.split(",", -1)) {
        hops.add(part.strip());
      }
    }
    for (int i = hops.size() - 1; i >= 0; i--) {
      String hop = hops.get(i);
      if (hop.isEmpty() || hop.length() > MAX_ADDRESS_LENGTH) {
        return direct;
      }
      if (!trustedProxies.contains(hop)) {
        return hop;
      }
    }
    return direct;
  }
}
