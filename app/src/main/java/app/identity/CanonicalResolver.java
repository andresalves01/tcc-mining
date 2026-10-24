package app.identity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Collapses developer identities into people.
 *
 * <p>Pure domain logic: no MongoDB, no HTTP, no Spark, no filesystem. That is deliberate — these
 * rules are the part of the framework most likely to be wrong, and they need to be exercised in
 * milliseconds without standing up infrastructure.
 *
 * <p><b>Why the rules stop where they do.</b> Matching on the email local part is not implemented,
 * on purpose. It is the sole cause of fabricated developers: {@code mail@stefan-seelmann.de} and
 * {@code mail@michael-stevens.de} share the local part {@code mail}, which chains Stefan Seelmann,
 * Michael Stevens, Dennis Kieselhorst and Sriharsha Chintalapani into one person who never existed.
 *
 * <p>The asymmetry matters. A false merge invents a developer and pools several people's
 * accumulated work into them, corrupting {@code H_j} and letting team search select one human
 * repeatedly as distinct members. A false split merely under-credits someone and is a stated
 * limitation. So the bar for merging is high, and {@link Strictness} lowers it only in the one
 * direction that was validated.
 */
public final class CanonicalResolver {

  private final Strictness strictness;
  private final Map<String, PersonRecord> records = new LinkedHashMap<>();
  private final Map<String, String> parent = new HashMap<>();
  private final List<RefusedBridge> refused = new ArrayList<>();

  public CanonicalResolver(final Strictness strictness) {
    this.strictness = strictness;
  }

  public void add(final PersonRecord record) {
    records.put(record.personId(), record);
    parent.putIfAbsent(record.personId(), record.personId());
  }

  public void addAll(final Collection<PersonRecord> all) {
    all.forEach(this::add);
  }

  /**
   * Runs the merge rules and returns {@code personId -> canonicalPersonId}.
   *
   * <p>The canonical id is one of the real member ids rather than a synthetic one, so the result
   * joins straight back onto {@code commit.author_id} and {@code issue.assignee_id}.
   */
  public Map<String, String> resolve() {
    refused.clear(); // resolve() is re-runnable; refusals describe one run, not their sum
    mergeByExactEmail();
    if (strictness == Strictness.EMAIL_AND_FULL_NAME) {
      mergeByExactFullName();
    }
    linkJiraToGit();

    final Map<String, String> canonical = new LinkedHashMap<>();
    for (final String id : records.keySet()) {
      canonical.put(id, find(id));
    }
    return canonical;
  }

  /** Same real address means same person. Placeholder addresses are excluded. */
  private void mergeByExactEmail() {
    final Map<String, List<String>> byEmail = new HashMap<>();
    for (final PersonRecord record : records.values()) {
      if (record.identity().hasPlaceholderEmail()) {
        continue;
      }
      byEmail.computeIfAbsent(record.identity().normalizedEmail(), key -> new ArrayList<>())
          .add(record.personId());
    }
    byEmail.values().forEach(this::unionAll);
  }

  /**
   * Same full name means same person. At least two tokens are required, so bare handles
   * ({@code "ggregory"}, {@code "builds"}) never merge on a name alone.
   *
   * <p>This is what recovers a developer across addresses: Jordan Zimmerman appears under
   * {@code jordan@jordanzimmerman.com}, {@code jzimmerman@netflix.com} and
   * {@code jordan.zimmerman@riotgames.com} as he changes employer.
   *
   * <p>Residual risk is genuinely common names — two unrelated "Wei Zhang" would be fused. None
   * were found in the 2.2 corpus, but the resulting clusters should be published for audit rather
   * than asserted correct.
   */
  private void mergeByExactFullName() {
    final Map<String, List<String>> byName = new HashMap<>();
    for (final PersonRecord record : records.values()) {
      if (record.identity().nameTokenCount() < 2 || record.identity().hasPlaceholderName()) {
        continue;
      }
      byName.computeIfAbsent(record.identity().normalizedName(), key -> new ArrayList<>())
          .add(record.personId());
    }
    byName.values().forEach(this::unionAll);
  }

  /**
   * Bridges the two id spaces.
   *
   * <p>A Jira record carries a username and a display name but no address; a git record carries a
   * name and an address. Two exact signals link them:
   *
   * <ul>
   *   <li>Jira username equals the local part of a git address — {@code stain} ↔
   *       {@code stain@apache.org}
   *   <li>Jira display name equals a git author name — {@code "Stian Soiland-Reyes"}
   * </ul>
   *
   * <p>Neither consults the issue↔commit linkage. That matters: a bridge inferred from that linkage
   * would be circular once the resulting map is used to attach commit-derived features to
   * issue-derived labels.
   *
   * <p><b>The username rule is guarded.</b> A handle only identifies when it names one person. Left
   * unguarded it fabricates developers: the Jira username {@code jason} matches the local part of
   * {@code jason@confluent.io}, {@code jason@tesla.io}, {@code jason@cvk.ca} and
   * {@code jason@planet57.com}, which are four different people, and chaining them together produced
   * a single canonical person holding 4,659 commits across Maven, Kafka and Storm. So the union is
   * offered only when every git identity carrying that local part also carries the same full name;
   * otherwise it is declined and recorded in {@link #refusedBridges()} for audit. Measured on the
   * 2.2 corpus: 95 refusals, 29 canonical people changed, 3.35% of authored commits reassigned —
   * while the legitimate handle links survive, {@code randgalt} still resolving to Jordan Zimmerman
   * and {@code revans2} to Bobby Evans.
   */
  private void linkJiraToGit() {
    final Map<String, List<String>> gitByLocalPart = new HashMap<>();
    final Map<String, List<String>> gitByName = new HashMap<>();
    for (final PersonRecord record : records.values()) {
      if (record.source() != Source.GIT) {
        continue;
      }
      if (!record.identity().hasPlaceholderEmail()) {
        gitByLocalPart.computeIfAbsent(record.identity().emailLocalPart(), key -> new ArrayList<>())
            .add(record.personId());
      }
      if (record.identity().nameTokenCount() >= 2 && !record.identity().hasPlaceholderName()) {
        gitByName.computeIfAbsent(record.identity().normalizedName(), key -> new ArrayList<>())
            .add(record.personId());
      }
    }

    for (final PersonRecord record : records.values()) {
      if (record.source() != Source.JIRA) {
        continue;
      }
      final String username = record.username();
      if (username != null && !username.isBlank()) {
        final List<String> byLocalPart = gitByLocalPart.get(username.toLowerCase(Locale.ROOT));
        if (byLocalPart != null) {
          final List<String> names = distinctFullNames(byLocalPart);
          if (names.size() > 1) {
            refused.add(new RefusedBridge(
                record.personId(), username.toLowerCase(Locale.ROOT), List.copyOf(byLocalPart),
                names));
          } else {
            byLocalPart.forEach(gitId -> union(record.personId(), gitId));
          }
        }
      }
      if (record.identity().nameTokenCount() >= 2 && !record.identity().hasPlaceholderName()) {
        final List<String> byName = gitByName.get(record.identity().normalizedName());
        if (byName != null) {
          byName.forEach(gitId -> union(record.personId(), gitId));
        }
      }
    }
  }

  /** The distinct full names carried by a set of identities; single-token names do not count. */
  private List<String> distinctFullNames(final List<String> personIds) {
    return personIds.stream()
        .map(records::get)
        .filter(record -> record != null && record.identity().nameTokenCount() >= 2
            && !record.identity().hasPlaceholderName())
        .map(record -> record.identity().normalizedName())
        .distinct()
        .sorted()
        .toList();
  }

  /** Jira-to-git links declined because the handle named more than one person. */
  public List<RefusedBridge> refusedBridges() {
    return List.copyOf(refused);
  }

  /**
   * Union-find lookup with path compression.
   *
   * <p>{@code root} and {@code cursor} are reassigned by the traversal, so they are the one place
   * in this class where a local cannot be final.
   */
  private String find(final String personId) {
    String root = personId;
    while (!parent.get(root).equals(root)) {
      root = parent.get(root);
    }
    String cursor = personId;
    while (!parent.get(cursor).equals(root)) {
      final String next = parent.get(cursor);
      parent.put(cursor, root);
      cursor = next;
    }
    return root;
  }

  private void union(final String left, final String right) {
    final String leftRoot = find(left);
    final String rightRoot = find(right);
    if (!leftRoot.equals(rightRoot)) {
      parent.put(leftRoot, rightRoot);
    }
  }

  private void unionAll(final List<String> group) {
    for (int i = 1; i < group.size(); i++) {
      union(group.get(0), group.get(i));
    }
  }

  /** Distinct people after merging. Meaningful only once {@link #resolve()} has run. */
  public int canonicalCount() {
    return (int) records.keySet().stream().map(this::find).distinct().count();
  }

  public int identityCount() {
    return records.size();
  }

}
