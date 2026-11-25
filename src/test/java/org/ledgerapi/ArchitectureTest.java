package org.ledgerapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Layer dependency rules from the architecture (DC-4, NFR-MNT-1, ADR-0002). */
class ArchitectureTest {
  private static final Path MAIN = Path.of("src", "main", "java", "org", "ledgerapi");

  private static List<String> violations(String layer, String... forbidden) throws IOException {
    List<String> found = new ArrayList<>();
    try (Stream<Path> files = Files.walk(MAIN.resolve(layer))) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        for (String line : Files.readAllLines(file)) {
          if (!line.startsWith("import ")) {
            continue;
          }
          for (String prefix : forbidden) {
            if (line.contains(" " + prefix) || line.contains("static " + prefix)) {
              found.add(MAIN.relativize(file) + ": " + line);
            }
          }
        }
      }
    }
    return found;
  }

  @Test
  void domainIsPure() throws IOException {
    assertThat(
            violations(
                "domain",
                "org.ledgerapi.application",
                "org.ledgerapi.provider",
                "org.ledgerapi.infrastructure",
                "org.ledgerapi.http",
                "io.javalin",
                "java.sql",
                "org.sqlite"))
        .isEmpty();
  }

  @Test
  void applicationDependsOnlyOnDomainAndProviders() throws IOException {
    assertThat(
            violations(
                "application",
                "org.ledgerapi.infrastructure",
                "org.ledgerapi.http",
                "io.javalin",
                "java.sql",
                "org.sqlite"))
        .isEmpty();
  }

  @Test
  void providersAreInterfacesWithoutTechnology() throws IOException {
    assertThat(violations("provider", "org.ledgerapi.infrastructure", "io.javalin", "java.sql"))
        .isEmpty();
  }

  @Test
  void noFloatingPointInMoneyLayers() throws IOException {
    List<String> found = new ArrayList<>();
    for (String layer : new String[] {"domain", "application"}) {
      try (Stream<Path> files = Files.walk(MAIN.resolve(layer))) {
        for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
          String text = Files.readString(file);
          if (text.matches("(?s).*\\b(float|double|Float|Double|BigDecimal)\\b.*")) {
            found.add(MAIN.relativize(file).toString());
          }
        }
      }
    }
    assertThat(found).isEmpty();
  }
}
