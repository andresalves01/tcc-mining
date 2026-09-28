package app.pipeline.mongo;

import app.cost.Contribution;
import app.cost.port.ContributionLedger;
import app.identity.port.PersonRegistry;
import app.paper4.PullRequestExtras;
import app.paper4.port.PullRequestExtraSource;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Projections;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Calcula os preditores especificos de pull request, todos como-de a abertura ou como-de a
 * fronteira do exame -- nunca lendo o futuro da linha que descreve.
 *
 * <p><b>Familiaridade pela via barata.</b> O historico de arquivos de uma pessoa poderia sair de
 * {@code commit -> file_action -> file}, a passagem mais cara do pipeline. Sai daqui de
 * {@code pull_request_file} sobre os pull requests mesclados anteriores da mesma pessoa no mesmo
 * projeto: uma colecao que esta adaptador ja abre, sem juncao nova. A aproximacao subestima quem
 * contribuiu por fora de pull request, e isso e ressalva a declarar -- mas se a sobreposicao nao
 * predisser nada por esta via, a via cara dificilmente salvaria.
 *
 * <p><b>Espera tipica do projeto.</b> Mediana da espera ate o primeiro exame sobre os pull requests
 * do projeto mesclados na janela anterior. Estritamente anterior: um pull request aberto exatamente
 * no instante {@code t} nao e observavel em {@code t}, a mesma regra que {@code Seniority} ja
 * documenta.
 */
public final class MongoPullRequestExtras implements PullRequestExtraSource {

  public static final Set<String> READS = Set.of(
      "pull_request", "pull_request_event", "pull_request_commit", "pull_request_file",
      "pull_request_review", "pull_request_comment", "pull_request_system", "project");

  private static final Duration PROJECT_WINDOW = Duration.ofDays(90);

  private final MongoDatabase source;
  private final String sourceName;
  private final ContributionLedger ledger;
  private final PersonRegistry registry;

  /**
   * O registro entra como porta, nao como mapa ja lido: {@code App.wire()} monta este adaptador
   * para todo comando, inclusive {@code help}, e ler identidades no arranque faria cada um deles
   * pagar por uma coisa que so este estagio usa.
   */
  public MongoPullRequestExtras(final MongoClient client, final String sourceDb,
      final ContributionLedger ledger, final PersonRegistry registry) {

    this.source = client.getDatabase(sourceDb);
    this.sourceName = sourceDb;
    this.ledger = ledger;
    this.registry = registry;
  }

  /** Um pull request reduzido ao que estes preditores precisam dele. */
  private record Row(String id, String project, String author, Instant opened,
      Instant examined, String examState, String examiner, boolean byComment, List<String> paths,
      String association, String examinerAssociation, Instant merged) {}

  @Override
  public Map<String, PullRequestExtras> of(final Collection<String> ids) {
    final Set<String> wanted = new HashSet<>(ids);
    final Map<String, String> canonical = registry.readCanonical();
    final List<Row> rows = scan(wanted);

    // Ledger por pessoa canonica, para a experiencia de quem examinou.
    final Map<String, List<Contribution>> byReviewer = new HashMap<>(1 << 12);
    for (final Contribution c : ledger.readAll()) {
      byReviewer.computeIfAbsent(c.canonicalId(), k -> new ArrayList<>()).add(c);
    }
    for (final List<Contribution> list : byReviewer.values()) {
      list.sort(Comparator.comparing(Contribution::authoredAt));
    }

    // Historico de arquivos por (pessoa canonica, projeto). Chaveado pelo canonico, e nao pelo id
    // bruto, porque o revisor precisa ser encontrado neste mesmo indice -- ele aparece ali como
    // autor de pull requests anteriores dele, e os dois ids so se encontram depois de resolvidos.
    final Map<String, List<Row>> byPersonProject = new HashMap<>(1 << 12);
    // Historico de arquivos por (pessoa, projeto), ordenado por abertura, para a familiaridade.
    final Map<String, List<Row>> byAuthorProject = new HashMap<>(1 << 12);
    // Esperas por projeto, ordenadas por abertura, para a espera tipica.
    final Map<String, List<Row>> byProject = new HashMap<>(1 << 8);
    for (final Row row : rows) {
      if (row.author() != null) {
        byAuthorProject.computeIfAbsent(row.author() + "\u0000" + row.project(),
            k -> new ArrayList<>()).add(row);
        final String person = canonical.get(row.author());
        if (person != null) {
          byPersonProject.computeIfAbsent(person + "\u0000" + row.project(),
              k -> new ArrayList<>()).add(row);
        }
      }
      byProject.computeIfAbsent(row.project(), k -> new ArrayList<>()).add(row);
    }
    byAuthorProject.values().forEach(l -> l.sort(Comparator.comparing(Row::opened)));
    byPersonProject.values().forEach(l -> l.sort(Comparator.comparing(Row::opened)));
    byProject.values().forEach(l -> l.sort(Comparator.comparing(Row::opened)));

    final Map<String, PullRequestExtras> out = new HashMap<>(wanted.size() * 2);
    for (final Row row : rows) {
      if (!wanted.contains(row.id())) {
        continue;
      }
      final Map<String, Double> v = new HashMap<>(12);
      v.put(PullRequestExtras.FILES, (double) row.paths().size());

      final int overlap = familiarity(row, byAuthorProject);
      v.put(PullRequestExtras.FAMILIARITY, (double) overlap);
      v.put(PullRequestExtras.FAMILIARITY_SHARE,
          row.paths().isEmpty() ? 0.0 : (double) overlap / row.paths().size());

      v.put(PullRequestExtras.PROJECT_WAIT, projectWait(row, byProject));
      v.put(PullRequestExtras.ASSOC_MEMBER, "MEMBER".equals(row.association()) ? 1.0 : 0.0);
      v.put(PullRequestExtras.ASSOC_NONE, "NONE".equals(row.association()) ? 1.0 : 0.0);

      if (row.examined() != null) {
        v.put(PullRequestExtras.REALISED_WAIT,
            Duration.between(row.opened(), row.examined()).toMillis() / 86_400_000.0);
        v.put(PullRequestExtras.EXAM_BY_COMMENT, row.byComment() ? 1.0 : 0.0);
        v.put(PullRequestExtras.EXAM_CHANGES_REQUESTED,
            "CHANGES_REQUESTED".equals(row.examState()) ? 1.0 : 0.0);
        v.put(PullRequestExtras.EXAM_COMMENTED, "COMMENTED".equals(row.examState()) ? 1.0 : 0.0);
        v.put(PullRequestExtras.REVIEWER_VOLUME, reviewerVolume(row, byReviewer, canonical));
        v.put(PullRequestExtras.REVIEWER_ASSOC_MEMBER,
            "MEMBER".equals(row.examinerAssociation()) ? 1.0 : 0.0);

        final String reviewer = row.examiner() == null ? null : canonical.get(row.examiner());
        final int seen = reviewerFamiliarity(row, reviewer, byPersonProject);
        v.put(PullRequestExtras.REVIEWER_FAMILIARITY, (double) seen);
        v.put(PullRequestExtras.REVIEWER_FAMILIARITY_SHARE,
            row.paths().isEmpty() ? 0.0 : (double) seen / row.paths().size());

        final List<Contribution> history = reviewer == null ? null : byReviewer.get(reviewer);
        v.put(PullRequestExtras.REVIEWER_LINES_PER_COMMIT,
            reviewerLinesPerCommit(history, row.examined()));
        v.put(PullRequestExtras.REVIEWER_TENURE, reviewerTenure(history, row.examined()));
      }
      out.put(row.id(), new PullRequestExtras(v));
    }
    return out;
  }

  /** Arquivos deste pull request que a pessoa ja tocou em pull requests mesclados anteriores. */
  private static int familiarity(final Row row, final Map<String, List<Row>> byAuthorProject) {
    if (row.author() == null || row.paths().isEmpty()) {
      return 0;
    }
    final List<Row> history = byAuthorProject.get(row.author() + "\u0000" + row.project());
    if (history == null) {
      return 0;
    }
    final Set<String> seen = new HashSet<>();
    for (final Row prior : history) {
      if (!prior.opened().isBefore(row.opened())) {
        break; // anterioridade estrita: o proprio pull request nunca conta para si
      }
      seen.addAll(prior.paths());
    }
    int overlap = 0;
    for (final String path : row.paths()) {
      if (seen.contains(path)) {
        overlap++;
      }
    }
    return overlap;
  }

  /** Mediana da espera nos pull requests do projeto abertos na janela estritamente anterior. */
  private static double projectWait(final Row row, final Map<String, List<Row>> byProject) {
    final Instant from = row.opened().minus(PROJECT_WINDOW);
    final List<Double> waits = new ArrayList<>();
    for (final Row other : byProject.get(row.project())) {
      if (!other.opened().isBefore(row.opened())) {
        break;
      }
      if (other.opened().isBefore(from) || other.examined() == null) {
        continue;
      }
      waits.add(Duration.between(other.opened(), other.examined()).toMillis() / 86_400_000.0);
    }
    if (waits.isEmpty()) {
      return 0.0; // projeto sem historico na janela; o indicador de equipe vazia ja marca o caso
    }
    waits.sort(Double::compare);
    return waits.get(waits.size() / 2);
  }

  /** Arquivos desta mudanca que quem examinou ja tinha tocado, em pull requests proprios. */
  private static int reviewerFamiliarity(final Row row, final String reviewer,
      final Map<String, List<Row>> byPersonProject) {

    if (reviewer == null || row.paths().isEmpty()) {
      return 0;
    }
    final List<Row> history = byPersonProject.get(reviewer + "\u0000" + row.project());
    if (history == null) {
      return 0;
    }
    final Set<String> seen = new HashSet<>();
    for (final Row prior : history) {
      if (!prior.opened().isBefore(row.examined())) {
        break;
      }
      seen.addAll(prior.paths());
    }
    int overlap = 0;
    for (final String path : row.paths()) {
      if (seen.contains(path)) {
        overlap++;
      }
    }
    return overlap;
  }

  /** Tamanho tipico da mudanca de quem examinou, estritamente antes do exame. */
  private static double reviewerLinesPerCommit(final List<Contribution> history,
      final Instant examined) {

    if (history == null) {
      return 0;
    }
    long lines = 0;
    int commits = 0;
    for (final Contribution c : history) {
      if (!c.authoredAt().isBefore(examined)) {
        break;
      }
      lines += c.javaLinesAdded() + c.javaLinesDeleted();
      commits++;
    }
    return commits == 0 ? 0 : (double) lines / commits;
  }

  /** Dias desde a primeira contribuicao de quem examinou ate o exame. */
  private static double reviewerTenure(final List<Contribution> history, final Instant examined) {
    if (history == null || history.isEmpty()) {
      return 0;
    }
    final Instant first = history.get(0).authoredAt();
    return first.isBefore(examined)
        ? Duration.between(first, examined).toMillis() / 86_400_000.0 : 0;
  }

  /** Volume acumulado de quem examinou, estritamente antes do exame. */
  private static double reviewerVolume(final Row row,
      final Map<String, List<Contribution>> byReviewer, final Map<String, String> canonical) {
    if (row.examiner() == null) {
      return 0.0;
    }
    final String person = canonical.get(row.examiner());
    if (person == null) {
      return 0.0;
    }
    final List<Contribution> history = byReviewer.get(person);
    if (history == null) {
      return 0.0;
    }
    long lines = 0;
    for (final Contribution c : history) {
      if (!c.authoredAt().isBefore(row.examined())) {
        break;
      }
      lines += c.javaLinesAdded() + c.javaLinesDeleted();
    }
    return lines;
  }

  private List<Row> scan(final Set<String> wanted) {
    final Map<String, String> projectNameById = new HashMap<>(1 << 8);
    for (final Document p : source.getCollection("project").find()
        .projection(Projections.include("name"))) {
      projectNameById.put(p.getObjectId("_id").toHexString(), p.getString("name"));
    }
    final Map<String, String> projectBySystem = new HashMap<>(1 << 8);
    for (final Document s : source.getCollection("pull_request_system").find()
        .projection(Projections.include("project_id"))) {
      final ObjectId projectId = s.getObjectId("project_id");
      final String name = projectId == null ? null : projectNameById.get(projectId.toHexString());
      if (name != null) {
        projectBySystem.put(s.getObjectId("_id").toHexString(), name);
      }
    }

    final Map<String, Instant> mergedAt = new HashMap<>(1 << 15);
    for (final Document e : source.getCollection("pull_request_event")
        .find(com.mongodb.client.model.Filters.eq("event_type", "merged"))
        .projection(Projections.include("pull_request_id", "created_at"))) {
      final String id = e.getObjectId("pull_request_id").toHexString();
      mergedAt.merge(id, e.getDate("created_at").toInstant(), (a, b) -> a.isBefore(b) ? a : b);
    }

    final Map<String, Set<String>> authorsByPr = new HashMap<>(1 << 15);
    for (final Document c : source.getCollection("pull_request_commit").find()
        .projection(Projections.include("pull_request_id", "author_id"))) {
      final ObjectId author = c.getObjectId("author_id");
      if (author != null) {
        authorsByPr.computeIfAbsent(c.getObjectId("pull_request_id").toHexString(),
            k -> new HashSet<>(4)).add(author.toHexString());
      }
    }

    final Map<String, Document> firstReview = new HashMap<>(1 << 15);
    for (final Document r : source.getCollection("pull_request_review").find()
        .projection(Projections.include(
            "pull_request_id", "submitted_at", "state", "creator_id", "author_association"))) {
      if (r.getDate("submitted_at") == null) {
        continue;
      }
      final String id = r.getObjectId("pull_request_id").toHexString();
      final Document held = firstReview.get(id);
      if (held == null || r.getDate("submitted_at").before(held.getDate("submitted_at"))) {
        firstReview.put(id, r);
      }
    }

    final Map<String, Document> firstComment = new HashMap<>(1 << 15);
    for (final Document c : source.getCollection("pull_request_comment").find()
        .projection(Projections.include("pull_request_id", "created_at", "author_id"))) {
      if (c.getDate("created_at") == null) {
        continue;
      }
      final String id = c.getObjectId("pull_request_id").toHexString();
      final ObjectId commenter = c.getObjectId("author_id");
      final Set<String> commitAuthors = authorsByPr.get(id);
      if (commenter != null && commitAuthors != null
          && commitAuthors.contains(commenter.toHexString())) {
        continue;
      }
      final Document held = firstComment.get(id);
      if (held == null || c.getDate("created_at").before(held.getDate("created_at"))) {
        firstComment.put(id, c);
      }
    }

    final Map<String, List<String>> pathsByPr = new HashMap<>(1 << 15);
    for (final Document f : source.getCollection("pull_request_file").find()
        .projection(Projections.include("pull_request_id", "path"))) {
      final String path = f.getString("path");
      if (path != null) {
        pathsByPr.computeIfAbsent(f.getObjectId("pull_request_id").toHexString(),
            k -> new ArrayList<>(8)).add(path);
      }
    }

    // Todos os pull requests mesclados entram na varredura, mesmo os que nao sao rotulo: o
    // historico de arquivos e a espera tipica do projeto se constroem com eles tambem.
    final List<Row> rows = new ArrayList<>(mergedAt.size());
    for (final Document pr : source.getCollection("pull_request").find()
        .projection(Projections.include(
            "pull_request_system_id", "created_at", "author_association"))) {
      final String id = pr.getObjectId("_id").toHexString();
      final Instant merged = mergedAt.get(id);
      if (merged == null) {
        continue;
      }
      final ObjectId systemId = pr.getObjectId("pull_request_system_id");
      final String project = systemId == null ? null : projectBySystem.get(systemId.toHexString());
      if (project == null) {
        continue;
      }
      final Instant opened = pr.getDate("created_at").toInstant();
      final Set<String> authors = authorsByPr.get(id);
      final String author = authors != null && authors.size() == 1
          ? authors.iterator().next() : null;

      final Document review = firstReview.get(id);
      final Document comment = firstComment.get(id);
      final Instant reviewAt = inside(review == null ? null
          : review.getDate("submitted_at").toInstant(), opened, merged);
      final Instant commentAt = inside(comment == null ? null
          : comment.getDate("created_at").toInstant(), opened, merged);

      Instant examined = reviewAt;
      boolean byComment = false;
      String state = review == null ? null : review.getString("state");
      String examiner = review == null || review.getObjectId("creator_id") == null
          ? null : review.getObjectId("creator_id").toHexString();
      String examinerAssociation = review == null ? null : review.getString("author_association");
      if (commentAt != null && (examined == null || commentAt.isBefore(examined))) {
        examined = commentAt;
        byComment = true;
        state = null;
        examiner = comment.getObjectId("author_id") == null
            ? null : comment.getObjectId("author_id").toHexString();
        examinerAssociation = null; // comentario nao carrega vinculo no mesmo formato
      }
      rows.add(new Row(id, project, author, opened, examined, state, examiner, byComment,
          pathsByPr.getOrDefault(id, List.of()), pr.getString("author_association"),
          examinerAssociation, merged));
    }
    return rows;
  }

  @Override
  public Map<String, long[]> temporalAudit(final Collection<String> ids) {
    final Set<String> wanted = new HashSet<>(ids);
    final List<Row> rows = scan(wanted);
    final Map<String, List<Row>> byAuthorProject = new HashMap<>(1 << 12);
    final Map<String, List<Row>> byProject = new HashMap<>(1 << 8);
    for (final Row row : rows) {
      if (row.author() != null) {
        byAuthorProject.computeIfAbsent(row.author() + "\u0000" + row.project(),
            k -> new ArrayList<>()).add(row);
      }
      byProject.computeIfAbsent(row.project(), k -> new ArrayList<>()).add(row);
    }
    byAuthorProject.values().forEach(l -> l.sort(Comparator.comparing(Row::opened)));
    byProject.values().forEach(l -> l.sort(Comparator.comparing(Row::opened)));

    final Map<String, long[]> out = new HashMap<>(wanted.size() * 2);
    for (final Row row : rows) {
      if (!wanted.contains(row.id())) {
        continue;
      }
      final long[] a = new long[7];
      final Instant t0 = row.opened();
      // Espera tipica: exatamente o laco de projectWait, contando o que ele usou.
      final Instant from = t0.minus(PROJECT_WINDOW);
      final List<Double> known = new ArrayList<>();
      for (final Row other : byProject.get(row.project())) {
        if (!other.opened().isBefore(t0)) {
          break;
        }
        if (other.opened().isBefore(from) || other.examined() == null) {
          continue;
        }
        a[0]++;
        if (!other.examined().isBefore(t0)) {
          a[1]++;
        }
        if (!other.merged().isBefore(t0)) {
          a[2]++;
        }
        if (other.examined().isBefore(t0) && other.merged().isBefore(t0)) {
          known.add(Duration.between(other.opened(), other.examined()).toMillis() / 86_400_000.0);
        }
      }
      known.sort(Double::compare);
      final double corrected = known.isEmpty() ? 0.0 : known.get(known.size() / 2);
      a[3] = corrected == projectWait(row, byProject) ? 0 : 1;
      // Familiaridade: o laco de familiarity, contando os anteriores mesclados depois de t0.
      final List<Row> history = row.author() == null ? null
          : byAuthorProject.get(row.author() + "\u0000" + row.project());
      if (history != null && !row.paths().isEmpty()) {
        final Set<String> seenKnown = new HashSet<>();
        for (final Row prior : history) {
          if (!prior.opened().isBefore(t0)) {
            break;
          }
          a[4]++;
          if (!prior.merged().isBefore(t0)) {
            a[5]++;
          } else {
            seenKnown.addAll(prior.paths());
          }
        }
        int overlapKnown = 0;
        for (final String path : row.paths()) {
          if (seenKnown.contains(path)) {
            overlapKnown++;
          }
        }
        a[6] = overlapKnown == familiarity(row, byAuthorProject) ? 0 : 1;
      }
      out.put(row.id(), a);
    }
    return out;
  }

  private static Instant inside(final Instant at, final Instant from, final Instant to) {
    return at != null && !at.isBefore(from) && !at.isAfter(to) ? at : null;
  }

  @Override
  public String describe() {
    return sourceName + ".pull_request* (preditores de revisao e familiaridade)";
  }
}
