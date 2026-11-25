package org.ledgerapi.provider;

/** Source of opaque session tokens with at least 256 bits of entropy (SRS 4.6). */
@FunctionalInterface
public interface TokenGenerator {
  String newToken();
}
