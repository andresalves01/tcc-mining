package app.cli.commands;

import app.cli.*;
import app.issues.*;
import app.issues.Universe.Population;
import app.pipeline.clustering.SmileKMeans;
import app.pipeline.embedding.EmbeddingCache;
import app.pipeline.embedding.EmbeddingModel;
import app.study.Row;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import org.bson.Document;

/** Versioned, training-only duration experiment. Frozen partitions, no database writes. */
public final class IssuesAdjustedDurationCommand implements CommandRunner {
  private static final Population P=Population.IN_PROGRESS;
  private final Function<String,String> settings;
  public IssuesAdjustedDurationCommand() { this(n->{String s=System.getenv(n);return s==null?System.getProperty(n):s;}); }
  public IssuesAdjustedDurationCommand(Function<String,String> settings) { this.settings=settings; }
  private Path path(String name) {
    String s=settings.apply(name);if(s==null||s.isBlank())throw new IllegalArgumentException("missing "+name);return Path.of(s);
  }
  private static final ObjectMapper JSON=new ObjectMapper();
  public record Cache(int version,String sourceRule,String matrixSha,String botsSha,Map<String,DurationEvidence> evidence) {}

  @Override public void run(Context context) throws Exception {
    Path input=path("MATRIX"),root=path("ADJUSTED_DIR"),botsFile=path("BOT_ACCOUNTS");
    String mode=Objects.toString(settings.apply("ADJUSTED_STEP"),"matrix");
    if(!Set.of("evidence","audit","matrix","select","report","models","baseline-select","baseline-report","outer-matrix").contains(mode))throw new IllegalArgumentException("unknown ADJUSTED_STEP");
    Files.createDirectories(root);
    Path matrix=root.resolve("adjusted-inner-matrix.tsv"),params=root.resolve("elastic-net-parameters.txt");
    if(mode.startsWith("baseline-")){matrix=root.resolve("matched-baseline-matrix.tsv");params=root.resolve("matched-baseline-parameters.txt");}
    if(mode.equals("outer-matrix")) {
      outerMatrix(context,input,root,botsFile);
      return;
    }
    if(Set.of("select","report","models","baseline-select","baseline-report").contains(mode)) {
      var rows=RowCache.read(matrix,P,"TRAIN");
      var population=BusinessDays.population(rows,Augmentation.Variant.INNER);
      if(!mode.endsWith("report")) {
        var selected=ElasticNetSelection.run(population.entries());
        Files.write(params,ElasticNetSelection.parameters(selected,Sha256.of(matrix)));
      }
      if(!mode.endsWith("select")) {
        var values=Map.of("MATRIX",matrix.toString(),"ELASTIC_NET_PARAMETERS",params.toString(),"REPORT_DIR",root.resolve(mode.startsWith("baseline-")?"matched-baseline-report":"inner-report").toString());
        new IssuesSimilarityInnerReportCommand(values::get).run(context);
        if(!mode.startsWith("baseline-")) AdjustedDurationReport.write(root,matrix);
      }
      return;
    }
    var rows=RowCache.read(input,P,"TRAIN"); // Never parses the outer-test values.
    if(rows.isEmpty()||rows.stream().anyMatch(r->!Set.of("TRAIN","TEST").contains(r.inner(P))))throw new IllegalArgumentException("frozen inner partitions required");
    if(AdjustedDuration.marked(rows))throw new IllegalArgumentException("input is already adjusted");
    if(input.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))throw new IllegalArgumentException("keep the source matrix outside ADJUSTED_DIR");
    var bots=new HashSet<>(Files.readAllLines(botsFile));bots.removeIf(String::isBlank);
    String inputSha=Sha256.of(input),botsSha=Sha256.of(botsFile);
    Path evidenceFile=root.resolve("evidence-v1.json");Cache cache;
    if(Files.exists(evidenceFile)) {
      cache=JSON.readValue(evidenceFile.toFile(),Cache.class);
      if(cache.version()!=1||!DurationEvidence.SOURCE_RULE_VERSION.equals(cache.sourceRule())||!inputSha.equals(cache.matrixSha())||!botsSha.equals(cache.botsSha()))throw new IllegalArgumentException("evidence cache belongs to different input/bot policy");
    } else {
      cache=new Cache(1,DurationEvidence.SOURCE_RULE_VERSION,inputSha,botsSha,context.study().durationEvidence(rows,bots));
      if(!cache.evidence().keySet().equals(rows.stream().map(r->r.unit().issueId()).collect(java.util.stream.Collectors.toSet())))throw new IllegalArgumentException("one evidence envelope per specified issue required");
      Path temp=root.resolve("evidence-v1.partial.json");JSON.writeValue(temp.toFile(),cache);Files.move(temp,evidenceFile,StandardCopyOption.ATOMIC_MOVE);
    }
    if(mode.equals("evidence")){System.out.println("Saved scoped evidence: "+evidenceFile);return;}
    var result=AdjustedDuration.apply(rows,cache.evidence());
    Path thresholdFile=root.resolve("threshold-v1.json");
    if(Files.exists(thresholdFile)) {
      var frozen=JSON.readValue(thresholdFile.toFile(),AdjustedDuration.Threshold.class);
      if(!frozen.equals(result.threshold()))throw new IllegalArgumentException("frozen threshold changed; use a new experiment directory");
    } else JSON.writerWithDefaultPrettyPrinter().writeValue(thresholdFile.toFile(),result.threshold());
    if(mode.equals("audit")){writeAudit(root,rows,result,cache.evidence());return;}
    var descriptions=new HashMap<String,String>();
    for(String line:Files.readAllLines(path("START_DESCRIPTIONS"))){var d=Document.parse(line);if(d.getString("description")!=null)descriptions.put(d.getString("id"),d.getString("description"));}
    var embeddings=EmbeddingCache.open(path("EMBEDDING_CACHE"),EmbeddingCache.pinned(),text->{throw new IllegalStateException("description absent from frozen embedding cache");});
    var adjusted=rebuild(result.entries(),descriptions,embeddings,true);
    var columns=new ArrayList<>(RowCache.columns(input));columns.addAll(AdjustedDuration.COLUMNS);
    RowCache.write(matrix,adjusted,columns,List.of(P));
    // Identical eligible IDs and original partition assignments for the reference target.
    var matched=new ArrayList<RowCache.Entry>();
    for(var r:rows)if(result.counts().get(r.unit().issueId()).total()!=null)matched.add(r);
    var baseline=rebuild(matched,descriptions,embeddings,false);
    RowCache.write(root.resolve("matched-baseline-matrix.tsv"),baseline,RowCache.columns(input),List.of(P));
    if(embeddings.misses()!=0)throw new IllegalStateException("unexpected embedding inference");
    writeAudit(root,rows,result,cache.evidence());
    Files.write(root.resolve("provenance.txt"),List.of("source_matrix_sha256="+inputSha,"bots_sha256="+botsSha,
        "evidence_sha256="+Sha256.of(evidenceFile),"matrix_sha256="+Sha256.of(matrix),
        "start_descriptions_sha256="+Sha256.of(path("START_DESCRIPTIONS")),"outer_test=not read; not present in outputs",
        "partitions=original assignments retained; eligibility never redraws splits",
        "histories=all duration medians regenerated per variant; self excluded; fitting labels only",
        "embeddings=frozen cache; zero inference misses","target="+AdjustedDuration.RULE));
    System.out.printf("Adjusted target ready: %d/%d eligible; threshold %s%n",matched.size(),rows.size(),result.threshold());
  }

  /** Apply the frozen inner-fitting threshold to the reserved rows, then rebuild outer histories. */
  private void outerMatrix(Context context,Path input,Path root,Path botsFile) throws Exception {
    Path external=root.resolve("external-test");
    Files.createDirectories(external);
    String inputSha=Sha256.of(input),botsSha=Sha256.of(botsFile);
    Path trainSource=path("FROZEN_TRAIN_MATRIX");
    Path frozenMatrix=root.resolve("adjusted-inner-matrix.tsv");
    Path frozenBaseline=root.resolve("matched-baseline-matrix.tsv");
    Path thresholdFile=root.resolve("threshold-v1.json");
    Path trainEvidenceFile=root.resolve("evidence-v1.json");
    if(!Files.isRegularFile(frozenMatrix)||!Files.isRegularFile(frozenBaseline)
        ||!Files.isRegularFile(thresholdFile)||!Files.isRegularFile(trainEvidenceFile))
      throw new IllegalArgumentException("complete frozen inner artifacts required");
    Cache trainCache=JSON.readValue(trainEvidenceFile.toFile(),Cache.class);
    if(trainCache.version()!=1||!DurationEvidence.SOURCE_RULE_VERSION.equals(trainCache.sourceRule())
        ||!Sha256.of(trainSource).equals(trainCache.matrixSha())||!botsSha.equals(trainCache.botsSha()))
      throw new IllegalArgumentException("frozen evidence belongs to another source or bot policy");
    AdjustedDuration.Threshold threshold=JSON.readValue(thresholdFile.toFile(),AdjustedDuration.Threshold.class);
    List<RowCache.Entry> rows=RowCache.read(input);
    if(rows.isEmpty()||AdjustedDuration.marked(rows)||!ProjectSplit.differences(rows).isEmpty())
      throw new IllegalArgumentException("unadjusted matrix with frozen outer partitions required");
    List<RowCache.Entry> train=rows.stream().filter(r->"TRAIN".equals(r.outer(P))).toList();
    List<RowCache.Entry> test=rows.stream().filter(r->"TEST".equals(r.outer(P))).toList();
    var originalTrain=RowCache.read(trainSource);
    if(train.size()!=originalTrain.size())throw new IllegalArgumentException("frozen source training size changed");
    for(int k=0;k<train.size();k++) {
      var a=train.get(k);var b=originalTrain.get(k);
      if(!a.unit().equals(b.unit())||!a.row().values().equals(b.row().values())
          ||!a.row().absent().equals(b.row().absent())||!a.outer(P).equals(b.outer(P))
          ||!a.inner(P).equals(b.inner(P)))
        throw new IllegalArgumentException("frozen source training row changed: "+b.unit().key());
    }
    if(test.isEmpty()||train.size()+test.size()!=rows.size()
        ||!trainCache.evidence().keySet().equals(train.stream().map(r->r.unit().issueId()).collect(java.util.stream.Collectors.toSet())))
      throw new IllegalArgumentException("outer partitions differ from frozen training evidence");
    Path outerEvidenceFile=external.resolve("outer-evidence-v1.json");
    Cache testCache;
    if(Files.exists(outerEvidenceFile)) {
      testCache=JSON.readValue(outerEvidenceFile.toFile(),Cache.class);
      if(testCache.version()!=1||!DurationEvidence.SOURCE_RULE_VERSION.equals(testCache.sourceRule())
          ||!inputSha.equals(testCache.matrixSha())||!botsSha.equals(testCache.botsSha()))
        throw new IllegalArgumentException("outer evidence belongs to another source or bot policy");
    } else {
      var bots=new HashSet<>(Files.readAllLines(botsFile));bots.removeIf(String::isBlank);
      testCache=new Cache(1,DurationEvidence.SOURCE_RULE_VERSION,inputSha,botsSha,
          context.study().durationEvidence(test,bots));
      if(!testCache.evidence().keySet().equals(test.stream().map(r->r.unit().issueId()).collect(java.util.stream.Collectors.toSet())))
        throw new IllegalArgumentException("one evidence envelope per reserved issue required");
      Path partial=external.resolve("outer-evidence-v1.partial.json");
      JSON.writeValue(partial.toFile(),testCache);
      Files.move(partial,outerEvidenceFile,StandardCopyOption.ATOMIC_MOVE);
    }
    if(!testCache.evidence().keySet().equals(test.stream().map(r->r.unit().issueId()).collect(java.util.stream.Collectors.toSet())))
      throw new IllegalArgumentException("outer evidence does not cover exactly the reserved rows");
    Map<String,DurationEvidence> evidence=new HashMap<>(trainCache.evidence());
    evidence.putAll(testCache.evidence());
    var result=AdjustedDuration.apply(rows,evidence,threshold);
    // Train labels and eligibility must be exactly those frozen before the test was opened.
    var frozen=RowCache.read(frozenMatrix);
    var frozenOld=RowCache.read(frozenBaseline);
    var frozenById=new HashMap<String,RowCache.Entry>();
    for(var r:frozen)frozenById.put(r.unit().issueId(),r);
    var oldIds=frozenOld.stream().map(r->r.unit().issueId()).collect(java.util.stream.Collectors.toSet());
    if(frozenById.size()!=frozen.size()||frozen.size()!=train.size())
      throw new IllegalArgumentException("frozen adjusted training cohort differs");
    int eligibleTrain=0;
    for(var r:train) {
      var c=result.counts().get(r.unit().issueId());
      var prior=frozenById.get(r.unit().issueId());
      if(prior==null||!Objects.equals(prior.row().values().get(AdjustedDuration.DAYS),
              c.total()==null?null:c.total().doubleValue())
          ||!Objects.equals(prior.inner(P),r.inner(P))
          ||(c.total()!=null)!=oldIds.contains(r.unit().issueId()))
        throw new IllegalArgumentException("reserved extraction changed frozen training label: "+r.unit().key());
      if(c.total()!=null)eligibleTrain++;
    }
    if(eligibleTrain!=frozenOld.size())throw new IllegalArgumentException("frozen eligible training cohort changed");
    var descriptions=new HashMap<String,String>();
    for(String line:Files.readAllLines(path("START_DESCRIPTIONS"))){var d=Document.parse(line);if(d.getString("description")!=null)descriptions.put(d.getString("id"),d.getString("description"));}
    // The frozen descriptions file contains only outer training rows. Recover reserved text at
    // each issue's start instant, without reading its outcome, before recomputing similarities.
    var texts=context.study().texts(test.stream().map(r->r.unit().issueId()).toList());
    var outerDescriptions=new ArrayList<String>();
    int describedTest=0;
    for(var r:test){var u=r.unit();
      String description=descriptionAtStart(r,texts.get(u.issueId()));
      if(description!=null){descriptions.put(u.issueId(),description);describedTest++;}
      if(r.row().values().getOrDefault("A01_desc_length",0.0)>0&&description==null)
        throw new IllegalArgumentException("reserved start description missing: "+u.key());
      outerDescriptions.add(new Document("id",u.issueId()).append("description",description).toJson());
    }
    Files.write(external.resolve("outer-start-descriptions.jsonl"),outerDescriptions);
    var embeddings=EmbeddingCache.open(path("EMBEDDING_CACHE"),EmbeddingCache.pinned(),text->{throw new IllegalStateException("description absent from frozen embedding cache");});
    var eligible=new ArrayList<RowCache.Entry>();
    for(var r:rows)if(result.counts().get(r.unit().issueId()).total()!=null)eligible.add(r);
    var adjusted=rebuild(result.entries().stream().filter(r->result.counts().get(r.unit().issueId()).total()!=null).toList(),descriptions,embeddings,true);
    var baseline=rebuild(eligible,descriptions,embeddings,false);
    if(embeddings.misses()!=0)throw new IllegalStateException("unexpected embedding inference");
    var columns=new ArrayList<>(RowCache.columns(input));columns.addAll(AdjustedDuration.COLUMNS);
    RowCache.write(external.resolve("adjusted-outer-matrix.tsv"),adjusted,columns,List.of(P));
    RowCache.write(external.resolve("matched-baseline-outer-matrix.tsv"),baseline,RowCache.columns(input),List.of(P));
    var audit=new ArrayList<String>();
    audit.add("id\tissue\tquality\told_days\tadjusted_days\tprefix_days\timplementation_days\treview_activity_days\tpost_commit_wait_days\tinternal_removed_days\tpaused_active_dates\tcommits\toriginal_commits\twarnings");
    int eligibleTest=0;
    for(var r:test) {
      var c=result.counts().get(r.unit().issueId());
      if(c.total()!=null)eligibleTest++;
      audit.add(String.join("\t",r.unit().issueId(),r.unit().key(),c.quality(),Integer.toString(c.oldDays()),
          c.total()==null?"":c.total().toString(),Integer.toString(c.prefix()),Integer.toString(c.implementation()),
          Integer.toString(c.review()),Integer.toString(c.waitDays()),Integer.toString(c.removedInternalDays()),
          Integer.toString(c.pausedActivityDays()),Integer.toString(c.commits()),Integer.toString(c.originalCommits()),
          String.join(",",c.warnings())));
    }
    Files.write(external.resolve("outer-per-issue.tsv"),audit);
    System.out.printf("Reserved cohort: %d/%d eligible; frozen training %d; cap %d; "
        + "start descriptions %d/%d; outer histories rebuilt; evidence cache %s%n",
        eligibleTest,test.size(),eligibleTrain,threshold.cap(),describedTest,test.size(),outerEvidenceFile);
  }

  static String descriptionAtStart(RowCache.Entry row,Corpus.Text text) {
    var u=row.unit();
    var issue=new Corpus.Issue(u.issueId(),u.key(),u.project(),u.createdAt().toEpochMilli(),
        null,null,null,null,null,null,List.of(),List.of());
    return DescriptionCleaner.clean(Initial.at(issue,List.of(),text,
        u.startedAt().toEpochMilli()).description());
  }

  private static List<RowCache.Entry> rebuild(List<RowCache.Entry> rows,Map<String,String> descriptions,
      EmbeddingCache embeddings,boolean adjusted) {
    var issues=new ArrayList<Augmentation.Issue>();
    for(var r:rows){var u=r.unit();Double days=r.row().values().get(adjusted?AdjustedDuration.DAYS:BusinessDays.ACTIVE);
      int n=days==null?-1:days.intValue();
      issues.add(new Augmentation.Issue(u.issueId(),u.project(),u.reporter(),u.startedAt().toEpochMilli(),u.resolvedAt().toEpochMilli(),n,n,r.outer(P),r.inner(P),descriptions.get(u.issueId()),adjusted));
    }
    var augmentation=Augmentation.run(issues,embeddings,EmbeddingModel.DIMENSION,new SmileKMeans());
    var out=new ArrayList<RowCache.Entry>();
    for(var r:rows){var values=new HashMap<>(r.row().values());var absent=new HashSet<>(r.row().absent());
      for(String col:Augmentation.columns()){
        Double value=augmentation.columns().get(r.unit().issueId()).get(col);values.remove(col);absent.remove(col);
        if(value==null)absent.add(col);else values.put(col,value);
      }
      out.add(new RowCache.Entry(r.unit(),new Row(r.row().unitId(),values,absent),r.parts()));
    }
    return out;
  }

  private static void writeAudit(Path root,List<RowCache.Entry> rows,AdjustedDuration.Result result,
      Map<String,DurationEvidence> evidence) throws Exception {
    var lines=new ArrayList<String>();lines.add("id\tissue\tinner_split\tquality\told_days\tadjusted_days\tprefix_days\timplementation_days\treview_activity_days\tpost_commit_wait_days\tinternal_removed_days\tpaused_active_dates\tcommits\toriginal_commits\twarnings");
    var byQuality=new TreeMap<String,int[]>();long old=0,adjusted=0,review=0,prefix=0,wait=0,restored=0;int eligible=0,zero=0;
    for(var r:rows){var c=result.counts().get(r.unit().issueId());var q=byQuality.computeIfAbsent(c.quality(),k->new int[3]);q[0]++;q[1]+=c.oldDays();
      if(c.total()!=null){eligible++;if(c.total()==0)zero++;old+=c.oldDays();adjusted+=c.total();review+=c.review();prefix+=c.prefix();wait+=c.waitDays();restored+=c.pausedActivityDays();q[2]+=c.total();}
      lines.add(String.join("\t",r.unit().issueId(),r.unit().key(),r.inner(P),c.quality(),Integer.toString(c.oldDays()),c.total()==null?"":c.total().toString(),Integer.toString(c.prefix()),Integer.toString(c.implementation()),Integer.toString(c.review()),Integer.toString(c.waitDays()),Integer.toString(c.removedInternalDays()),Integer.toString(c.pausedActivityDays()),Integer.toString(c.commits()),Integer.toString(c.originalCommits()),String.join(",",c.warnings())));
    }
    Files.write(root.resolve("per-issue.tsv"),lines);
    var md=new ArrayList<>(List.of("# Duração ajustada: auditoria do treino","", "Teste externo selado; partições originais preservadas.","", "- Cobertura: "+eligible+"/"+rows.size()+" ("+String.format(Locale.ROOT,"%.2f",100.0*eligible/rows.size())+"%).",
        "- Zeros preservados: "+zero+"/"+eligible+".","- Soma nos mesmos IDs: "+old+" → "+adjusted+" dias úteis.","- Prefixo preservado: "+prefix+" dias; atividade pós-código: "+review+"; espera pós-código excluída: "+wait+".","- Datas ativas recuperadas em Open/Reopened: "+restored+".","- Teto congelado do ajuste interno: "+result.threshold()+".","", "| Qualidade da evidência | Issues | Dias antigos | Dias ajustados |","|---|---:|---:|---:|"));
    byQuality.forEach((q,n)->md.add("| "+q+" | "+n[0]+" | "+n[1]+" | "+(q.equals("no_eligible_commit")?"—":Integer.toString(n[2]))+" |"));
    md.addAll(List.of("","Os números descrevem registros, não esforço humano validado. O prefixo permanece inteiro. Datas de autoria recuperadas não certificam a completude da branch. `recorded_originals_recovered` significa os commits presentes nos PRs aceitos e registrados, não todos os commits que existiram. PRs com múltiplas issues são rejeitados; datas conflitantes ficam sinalizadas. O fim da issue continua sendo o limite. O trecho pós-código não mede exclusivamente revisão: inclui comentários e anexos humanos.","","A comparação justa usa matched-baseline-matrix.tsv e adjusted-inner-matrix.tsv nos mesmos IDs elegíveis e com históricos regenerados. Mudanças de erro entre alvos de escalas diferentes não demonstram, sozinhas, melhora de modelagem. Intervalos são prediction intervals; SPSP mantém tolerâncias inferidas e hipótese de alocação fixa."));
    Files.write(root.resolve("audit.md"),md);
  }
}
