package org.ledgerapi.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Enforces hexagonal architecture boundaries (T-27, NFR-MNT-1, ADR-0002) and floating-point ban
 * (DC-5, TQ-6) across {@code domain} and {@code application} source files.
 */
class ArchitectureRulesTest {
  private static final Path MAIN_JAVA = Path.of("src/main/java/org/ledgerapi");
  private static final Pattern IMPORT_LINE =
      Pattern.compile("^\\s*import\\s+(?:static\\s+)?([^;]+);");
  private static final Pattern FLOAT_OR_DOUBLE =
      Pattern.compile("\\b(float|double|Float|Double)\\b");

  @Test
  void domainLayerDependsOnlyOnJdkAndDomain() throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path file : javaFiles(MAIN_JAVA.resolve("domain"))) {
      for (String imp : importsOf(file)) {
        boolean allowed = imp.startsWith("java.") || imp.startsWith("org.ledgerapi.domain.");
        if (!allowed) {
          violations.add(file + " -> forbidden import: " + imp);
        }
      }
    }
    assertThat(violations)
        .as("domain must only import java.* or org.ledgerapi.domain.* (ADR-0002)")
        .isEmpty();
  }

  @Test
  void applicationLayerNeverImportsHttpJdbcOrInfrastructure() throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path file : javaFiles(MAIN_JAVA.resolve("application"))) {
      for (String imp : importsOf(file)) {
        boolean forbidden =
            imp.startsWith("io.javalin.")
                || imp.startsWith("java.sql.")
                || imp.startsWith("javax.sql.")
                || imp.startsWith("org.sqlite.")
                || imp.startsWith("org.ledgerapi.http.")
                || imp.startsWith("org.ledgerapi.infrastructure.");
        if (forbidden) {
          violations.add(file + " -> forbidden import: " + imp);
        }
      }
    }
    assertThat(violations)
        .as("application must not import Javalin, JDBC, http, or infrastructure (NFR-MNT-1)")
        .isEmpty();
  }

  @Test
  void domainAndApplicationNeverUseFloatingPointTypes() throws IOException {
    List<String> violations = new ArrayList<>();
    List<Path> files = new ArrayList<>();
    files.addAll(javaFiles(MAIN_JAVA.resolve("domain")));
    files.addAll(javaFiles(MAIN_JAVA.resolve("application")));
    for (Path file : files) {
      List<String> lines = Files.readAllLines(file);
      for (int i = 0; i < lines.size(); i++) {
        String stripped = stripLineComments(lines.get(i));
        if (FLOAT_OR_DOUBLE.matcher(stripped).find()) {
          violations.add(file + ":" + (i + 1) + " -> " + stripped.trim());
        }
      }
    }
    assertThat(violations)
        .as("float/double types are forbidden in domain and application (DC-5, TQ-6)")
        .isEmpty();
  }

  private static List<Path> javaFiles(Path root) throws IOException {
    if (!Files.exists(root)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    }
  }

  private static List<String> importsOf(Path file) throws IOException {
    List<String> result = new ArrayList<>();
    for (String line : Files.readAllLines(file)) {
      var matcher = IMPORT_LINE.matcher(line);
      if (matcher.find()) {
        result.add(matcher.group(1).trim());
      }
    }
    return result;
  }

  private static String stripLineComments(String line) {
    int idx = line.indexOf("//");
    String withoutSlash = idx >= 0 ? line.substring(0, idx) : line;
    String trimmed = withoutSlash.trim();
    if (trimmed.startsWith("*") || trimmed.startsWith("/*")) {
      return "";
    }
    return withoutSlash;
  }
}
