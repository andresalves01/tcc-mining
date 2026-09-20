package app.pipeline.mongo;

import app.identity.GitReconstruction;
import app.identity.Identity;
import app.identity.port.GitIdentitySource;
import app.pipeline.port.Progress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * Recovers the git half of {@code people} from the repositories bundled inside the dump.
 *
 * <p><b>Why this is necessary.</b> The release contains 31 collections and {@code people} is not
 * among them, so every {@code author_id} and {@code committer_id} is an opaque {@code ObjectId}
 * with no name or address stored anywhere. Re-running the SHARK plugins does not help: they would
 * mint fresh {@code ObjectId}s that do not join to the ones already in the corpus.
 *
 * <p><b>Why it is nevertheless possible.</b> The dump ships the mined repositories as gzipped
 * tarballs in the {@code repository_data} GridFS bucket, each including a complete {@code .git}
 * directory. {@code commit.revision_hash} is the git SHA and is immutable, so it joins the two
 * sides exactly — no clone, no network, no {@code git} binary.
 *
 * <p><b>Why the result is exact.</b> {@code pycoshark} declares {@code People.email} as
 * {@code unique_with=["name"]}, making {@code (name, email)} the primary key. The join is therefore
 * a bijection, and this class reports violations rather than assuming there are none. Measured over
 * the full corpus: 452,286 commits, 7,199 person ids, zero violations, 59 commits (0.013%)
 * unresolvable because their history was rewritten after the 2021 mining run.
 */
public final class GridFsGitIdentitySource implements GitIdentitySource {

  private static final String BUCKET = "repository_data";

  private static final String COMMIT = "commit";
  private static final String VCS_SYSTEM = "vcs_system";

  /** The collections this source opens. Declared here so it cannot drift from
   * the queries above it; {@link app.pipeline.mongo.MongoCorpusCheck} verifies it. */
  public static final Set<String> READS = Set.of(BUCKET + ".files", COMMIT, VCS_SYSTEM);
  private static final int GIT_SEARCH_DEPTH = 3;

  private final MongoClient client;
  private final String sourceDb;
  private final Path workDir;
  private final Progress progress;

  public GridFsGitIdentitySource(final MongoClient client, final String sourceDb,
      final Path workDir, final Progress progress) {
    this.client = client;
    this.sourceDb = sourceDb;
    this.workDir = workDir;
    this.progress = progress;
  }

  @Override
  public GitReconstruction load() throws IOException {
    final MongoDatabase db = client.getDatabase(sourceDb);

    progress.phase(1, 3, "reading identities from the bundled repositories");
    final Map<String, Identity[]> byRevision = readBundledRepositories(db);
    progress.detail("%,d commits across the bundled history", byRevision.size());

    progress.phase(2, 3, "joining on commit.revision_hash");
    final Map<String, Map<Identity, Integer>> observed = new HashMap<>();
    final Map<String, Integer> authored = new HashMap<>();
    int scanned = 0;
    int unresolved = 0;

    // Iterated per repository so the vcs_system_id index is used. An unfiltered scan of `commit`
    // drags the code_entity_states arrays along with it -- tens of gigabytes of pointless I/O.
    // This phase is disk-bound rather than CPU-bound, so it reports per repository: without that,
    // a long run is indistinguishable from a hung one.
    final MongoCollection<Document> commits = db.getCollection(COMMIT);
    final List<Document> vcsSystems = new ArrayList<>();
    db.getCollection(VCS_SYSTEM).find()
        .projection(Projections.include("_id", "url"))
        .forEach(vcsSystems::add);

    int repository = 0;
    for (final Document vcs : vcsSystems) {
      repository++;
      final ObjectId vcsId = vcs.getObjectId("_id");
      final int scannedBefore = scanned;
      final int unresolvedBefore = unresolved;

      for (final Document commit : commits.find(Filters.eq("vcs_system_id", vcsId))
          .projection(Projections.fields(
              Projections.include("revision_hash", "author_id", "committer_id"),
              Projections.excludeId()))) {
        scanned++;
        final String hash = commit.getString("revision_hash");
        final Identity[] pair = hash == null ? null : byRevision.get(hash);
        if (pair == null) {
          unresolved++;
          continue;
        }
        final ObjectId authorId = commit.getObjectId("author_id");
        final ObjectId committerId = commit.getObjectId("committer_id");
        if (authorId != null) {
          observe(observed, authorId.toHexString(), pair[0]);
          authored.merge(authorId.toHexString(), 1, Integer::sum);
        }
        if (committerId != null) {
          observe(observed, committerId.toHexString(), pair[1]);
        }
      }

      progress.item(repository, vcsSystems.size(), shortName(vcs.getString("url")),
          "%,7d joined, %,5d unresolved",
          scanned - scannedBefore, unresolved - unresolvedBefore);
    }

    progress.phase(3, 3, "checking the mapping is a bijection");
    final List<String> violations = new ArrayList<>();
    final Map<String, Identity> identities = new LinkedHashMap<>();
    for (final Map.Entry<String, Map<Identity, Integer>> entry : observed.entrySet()) {
      if (entry.getValue().size() > 1) {
        violations.add(entry.getKey() + " -> " + entry.getValue().keySet());
      }
      final Identity mostObserved = entry.getValue().entrySet().stream()
          .max(Comparator.comparingInt(Map.Entry::getValue))
          .orElseThrow()
          .getKey();
      identities.put(entry.getKey(), mostObserved);
    }

    return new GitReconstruction(identities, authored, scanned, unresolved, violations);
  }

  private static void observe(
      final Map<String, Map<Identity, Integer>> observed, final String personId, final Identity identity) {
    observed.computeIfAbsent(personId, key -> new HashMap<>()).merge(identity, 1, Integer::sum);
  }

  /** {@code https://github.com/apache/commons-rdf.git} -&gt; {@code commons-rdf}, for progress lines. */
  static String shortName(final String url) {
    if (url == null || url.isBlank()) {
      return "(unknown)";
    }
    final String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    final int slash = trimmed.lastIndexOf('/');
    final String last = slash < 0 ? trimmed : trimmed.substring(slash + 1);
    return last.endsWith(".git") ? last.substring(0, last.length() - 4) : last;
  }

  /** @return {@code revisionHash -> [authorIdentity, committerIdentity]} */
  private Map<String, Identity[]> readBundledRepositories(final MongoDatabase db) throws IOException {
    final GridFSBucket bucket = GridFSBuckets.create(db, BUCKET);
    final Map<String, Identity[]> byRevision = new HashMap<>();
    Files.createDirectories(workDir);

    final List<GridFSFile> files = new ArrayList<>();
    bucket.find().forEach(files::add);

    final Path repoDir = workDir.resolve("repo");
    int index = 0;
    for (final GridFSFile file : files) {
      index++;
      deleteRecursively(repoDir);
      Files.createDirectories(repoDir);

      try (InputStream raw = bucket.openDownloadStream(file.getObjectId());
          TarArchiveInputStream tar =
              new TarArchiveInputStream(new GzipCompressorInputStream(new BufferedInputStream(raw)))) {
        unpackGitDirectory(tar, repoDir);
      }

      final Path gitDir = findGitDirectory(repoDir);
      if (gitDir == null) {
        progress.item(index, files.size(), file.getFilename(), "no .git, skipped");
        continue;
      }

      final int before = byRevision.size();
      readHistory(gitDir, byRevision);
      progress.item(index, files.size(), file.getFilename(),
          "%,7d commits", byRevision.size() - before);
    }
    deleteRecursively(repoDir);
    return byRevision;
  }

  /** Unpacks only {@code .git} metadata; the working tree is irrelevant here and large. */
  private static void unpackGitDirectory(final TarArchiveInputStream tar, final Path target)
      throws IOException {
    final Path root = target.toRealPath();
    TarArchiveEntry entry = tar.getNextEntry();
    while (entry != null) {
      final String name = entry.getName();
      if (name.contains("/.git/") || name.endsWith("/.git")) {
        final Path out = root.resolve(name).normalize();
        if (out.startsWith(root)) { // otherwise the entry tries to escape the extraction root
          if (entry.isDirectory()) {
            Files.createDirectories(out);
          } else {
            Files.createDirectories(out.getParent());
            Files.copy(tar, out);
          }
        }
      }
      entry = tar.getNextEntry();
    }
  }

  private static Path findGitDirectory(final Path root) throws IOException {
    try (Stream<Path> stream = Files.walk(root, GIT_SEARCH_DEPTH)) {
      return stream
          .filter(path -> path.getFileName().toString().equals(".git") && Files.isDirectory(path))
          .findFirst()
          .orElse(null);
    }
  }

  /** Walks every ref, so commits on non-default branches are included. */
  private static void readHistory(final Path gitDir, final Map<String, Identity[]> out) throws IOException {
    final FileRepositoryBuilder builder = new FileRepositoryBuilder().setGitDir(gitDir.toFile());
    try (Repository repo = builder.build(); RevWalk walk = new RevWalk(repo)) {
      final Set<org.eclipse.jgit.lib.ObjectId> starts = new HashSet<>();
      for (final Ref ref : repo.getRefDatabase().getRefs()) {
        final org.eclipse.jgit.lib.ObjectId oid = ref.getObjectId();
        if (oid == null) {
          continue;
        }
        try {
          RevObject object = walk.parseAny(oid);
          while (object instanceof RevTag tag) { // annotated tags point at the commit
            object = walk.parseAny(tag.getObject());
          }
          if (object instanceof RevCommit commit) {
            starts.add(commit.getId());
          }
        } catch (final IOException ignored) {
          // A ref may point at an object missing from a truncated bundle.
        }
      }
      for (final org.eclipse.jgit.lib.ObjectId start : starts) {
        walk.markStart(walk.parseCommit(start));
      }
      for (final RevCommit commit : walk) {
        final PersonIdent author = commit.getAuthorIdent();
        final PersonIdent committer = commit.getCommitterIdent();
        out.put(commit.getName(), new Identity[] {
            new Identity(author.getName(), author.getEmailAddress()),
            new Identity(committer.getName(), committer.getEmailAddress())
        });
      }
    }
  }

  static void deleteRecursively(final Path path) {
    if (!Files.exists(path)) {
      return;
    }
    try (Stream<Path> stream = Files.walk(path)) {
      stream.sorted(Comparator.reverseOrder()).forEach(entry -> {
        try {
          Files.deleteIfExists(entry);
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        }
      });
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
