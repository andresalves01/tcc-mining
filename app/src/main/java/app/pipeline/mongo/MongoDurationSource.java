package app.pipeline.mongo;

import app.issues.*;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

/** Scoped read-only duration evidence. No issue outcome or outer-test row is queried here. */
public final class MongoDurationSource {
  private final MongoDatabase source, thesis;
  public MongoDurationSource(MongoDatabase source, MongoDatabase thesis) {
    this.source=source; this.thesis=thesis;
  }
  private static String id(Object o) { return o instanceof ObjectId x ? x.toHexString() : null; }
  private static Long at(Document d,String field) { return d.getDate(field)==null?null:d.getDate(field).getTime(); }
  private static long num(Document d,String field) { return d!=null&&d.get(field) instanceof Number n?n.longValue():0; }
  private static Set<ObjectId> ids(Collection<Document> docs,String field) {
    var out=new HashSet<ObjectId>();for(var d:docs)if(d.get(field) instanceof ObjectId x)out.add(x);return out;
  }
  private static List<Document> find(MongoDatabase db,String coll,Bson filter,String...fields) {
    long start=System.nanoTime();
    var result=db.getCollection(coll).find(filter).projection(Projections.include(fields))
        .maxTime(120,TimeUnit.SECONDS).into(new ArrayList<>());
    System.out.printf(Locale.ROOT,"Evidence %s: %,d records, %.1fs%n",coll,result.size(),(System.nanoTime()-start)/1e9);
    return result;
  }
  private List<Document> find(String coll,Bson filter,String...fields) { return find(source,coll,filter,fields); }
  private static Map<String,List<Document>> grouped(List<Document> docs,String field) {
    var out=new HashMap<String,List<Document>>();for(var d:docs){String key=id(d.get(field));if(key!=null)out.computeIfAbsent(key,k->new ArrayList<>()).add(d);}return out;
  }
  private static String url(String s) { return Objects.toString(s,"").replaceFirst("\\.git/?$","").replaceFirst("/$","").toLowerCase(Locale.ROOT); }
  private static String keys(Collection<String> keys) {
    var byPrefix=new TreeMap<String,List<String>>();
    for(String key:keys){int dash=key.lastIndexOf('-');String prefix=dash<0?key:key.substring(0,dash+1);String suffix=dash<0?"":key.substring(dash+1);byPrefix.computeIfAbsent(prefix,k->new ArrayList<>()).add(Pattern.quote(suffix));}
    var terms=new ArrayList<String>();byPrefix.forEach((prefix,numbers)->terms.add(Pattern.quote(prefix)+"(?:"+String.join("|",numbers)+")"));
    return "(?<![A-Za-z0-9_])(?:"+String.join("|",terms)+")(?![A-Za-z0-9_])";
  }
  private static final Pattern KEY=Pattern.compile("(?i)(?<![A-Z0-9_])[A-Z][A-Z0-9]+-[0-9]+(?![A-Z0-9_])");
  private static final Pattern CLOSE=Pattern.compile("(?i)\\b(?:closes?|fix(?:es)?)\\s+#([0-9]+)");
  private static final Pattern BLOCK=Pattern.compile("(?m)^commit ([0-9a-f]{40})\\r?$");
  private static final Pattern DATE=Pattern.compile("(?m)^Date:[ \\t]*([^\\r\\n]+)");
  static boolean foreign(String text,String key) {
    var m=KEY.matcher(Objects.toString(text,""));while(m.find())if(!m.group().equalsIgnoreCase(key))return true;return false;
  }
  private static String title(Document d) { return Objects.toString(d.getString("message"),"").lines().findFirst().orElse("").strip(); }
  record Stamp(long at,Long committed,String source,String id) {}
  static Long parseDate(String value) {
    try {return OffsetDateTime.parse(value.strip()).toInstant().toEpochMilli();}
    catch(RuntimeException e) {
      try {return ZonedDateTime.parse(value.strip(),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();}
      catch(RuntimeException ignored) {return null;}
    }
  }
  static void gitDates(String body,String collection,String sourceId,Map<String,List<Document>> wanted,
      Map<String,List<Stamp>> stamps) {
    var m=BLOCK.matcher(Objects.toString(body,""));var starts=new ArrayList<Integer>();var ends=new ArrayList<Integer>();var shas=new ArrayList<String>();
    while(m.find()){starts.add(m.start());ends.add(m.end());shas.add(m.group(1));}
    for(int i=0;i<shas.size();i++) {
      String sha=shas.get(i);if(!wanted.containsKey(sha))continue;
      String block=body.substring(ends.get(i),i+1<starts.size()?starts.get(i+1):body.length());
      // An exact full SHA plus matching original subject prevents quoting a different change.
      if(wanted.get(sha).stream().map(MongoDurationSource::title).filter(t->!t.isEmpty()).noneMatch(block::contains))continue;
      var date=DATE.matcher(block);if(!date.find())continue;Long t=parseDate(date.group(1));
      if(t!=null)stamps.computeIfAbsent(sha,k->new ArrayList<>()).add(new Stamp(t,null,collection+".GitDate",sourceId));
    }
  }
  static boolean human(String raw,Set<String> bots,Map<String,Corpus.Person> people) {
    if(raw==null||bots.contains(raw)||bots.contains(World.person(raw,people)))return false;
    return people.get(raw)==null||!people.get(raw).bot();
  }

  public Map<String,DurationEvidence> load(List<RowCache.Entry> rows,Set<String> bots) {
    var byId=new LinkedHashMap<String,RowCache.Entry>();rows.forEach(r->byId.put(r.unit().issueId(),r));
    if(byId.size()!=rows.size())throw new IllegalArgumentException("duplicate issue ids");
    var wanted=rows.stream().map(r->new ObjectId(r.unit().issueId())).toList();
    if(wanted.isEmpty())throw new IllegalArgumentException("no scoped issues");
    System.out.printf("Duration evidence: %,d specified issues; reading linked metadata%n",rows.size());
    String[] fields={"vcs_system_id","revision_hash","author_id","author_date","committer_date","parents","linked_issue_ids","message"};
    var initial=find("commit",Filters.in("linked_issue_ids",wanted),fields);
    var authored=new HashSet<Date>();initial.forEach(d->{if(d.getDate("author_date")!=null)authored.add(d.getDate("author_date"));});
    var raw=new LinkedHashMap<String,Document>();initial.forEach(d->raw.put(id(d.get("_id")),d));
    if(!authored.isEmpty())find("commit",Filters.in("author_date",authored),fields).forEach(d->raw.put(id(d.get("_id")),d));
    var contrib=new HashMap<String,Document>();for(var d:find(thesis,"developer_contribution",Filters.in("_id",ids(raw.values(),"_id")),"is_bot","java_files_touched","java_lines_added","java_lines_deleted"))contrib.put(id(d.get("_id")),d);
    var projects=find("project",Filters.in("name",rows.stream().map(r->r.unit().project()).distinct().toList()),"name");
    var projectNames=new HashMap<String,String>();projects.forEach(d->projectNames.put(id(d.get("_id")),d.getString("name")));
    var systems=find("vcs_system",Filters.in("project_id",ids(projects,"_id")),"project_id","url");
    var vcs=new HashMap<String,String>();var repo=new HashMap<String,String>();
    systems.forEach(d->{String p=projectNames.get(id(d.get("project_id")));vcs.put(id(d.get("_id")),p);repo.put(url(d.getString("url")),p);});
    var hashes=raw.values().stream().map(d->d.getString("revision_hash")).filter(Objects::nonNull).distinct().toList();
    var membership=find("pull_request_commit",Filters.in("commit_sha",hashes),"pull_request_id","commit_sha");
    var merged=new HashMap<String,Long>();var mergeDates=new HashMap<String,Long>();
    for(var p:find("pull_request",Filters.in("_id",ids(membership,"pull_request_id")),"merged_at"))if(at(p,"merged_at")!=null)mergeDates.put(id(p.get("_id")),at(p,"merged_at"));
    for(var c:membership){Long t=mergeDates.get(id(c.get("pull_request_id")));if(t!=null)merged.merge(c.getString("commit_sha"),t,Math::min);}
    // Find PRs by direct issue key or explicit closing PR number, constrained to the issue project.
    var pairs=new HashMap<String,Set<String>>();
    for(var c:initial){String project=vcs.get(id(c.get("vcs_system_id")));var m=CLOSE.matcher(Objects.toString(c.getString("message"),""));
      while(m.find())for(var linked:c.getList("linked_issue_ids",Object.class,List.of())){
        var row=byId.get(id(linked));if(row==null||!row.unit().project().equals(project))continue;
        for(var u:repo.entrySet())if(u.getValue().equals(project)){
          String pair=u.getKey()+"#"+m.group(1);
          pairs.computeIfAbsent(pair,k->new HashSet<>()).add(row.unit().issueId());
        }
      }
    }
    var repoUrls=new HashSet<String>(repo.keySet());repo.keySet().forEach(u->repoUrls.add(u+".git"));
    // Repository metadata is a candidate index, not an outcome read. Only exact training issue
    // matches below can contribute histories, PR activity or recovered commit timestamps.
    var prs=find("pull_request",Filters.in("target_repo_url",repoUrls),"title","source_branch","target_repo_url","external_id","created_at","merged_at","merge_commit_id");
    var byProjectKey=new HashMap<String,RowCache.Entry>();rows.forEach(r->byProjectKey.put(r.unit().project()+"\0"+r.unit().key().toUpperCase(Locale.ROOT),r));
    var associated=new HashMap<String,List<Document>>();var kept=new LinkedHashMap<String,Document>();
    for(var p:prs) {
      String repoUrl=url(p.getString("target_repo_url")),project=repo.get(repoUrl);
      String label=Objects.toString(p.getString("title"),"")+" "+Objects.toString(p.getString("source_branch"),"");
      var candidateIds=new HashSet<>(pairs.getOrDefault(repoUrl+"#"+p.getString("external_id"),Set.of()));
      var matcher=KEY.matcher(label);while(matcher.find()){
        var row=byProjectKey.get(project+"\0"+matcher.group().toUpperCase(Locale.ROOT));if(row!=null)candidateIds.add(row.unit().issueId());
      }
      for(String candidate:candidateIds){var r=byId.get(candidate);
        if(r==null||!r.unit().project().equals(project)||at(p,"created_at")==null||at(p,"created_at")>=r.unit().resolvedAt().toEpochMilli()||foreign(label,r.unit().key()))continue;
        associated.computeIfAbsent(candidate,k->new ArrayList<>()).add(p);kept.put(id(p.get("_id")),p);
      }
    }
    System.out.printf("Duration evidence: %,d directly associated PRs; reading original dates and human activity%n",kept.size());
    var mergeHashes=new HashMap<String,String>();
    for(var c:find("commit",Filters.in("_id",ids(kept.values(),"merge_commit_id")),"revision_hash"))mergeHashes.put(id(c.get("_id")),c.getString("revision_hash"));
    var prCommits=find("pull_request_commit",Filters.in("pull_request_id",ids(kept.values(),"_id")),"pull_request_id","commit_sha","parents","message","author_id");
    var bySha=new HashMap<String,List<Document>>();var byPr=new HashMap<String,List<Document>>();
    for(var c:prCommits)if(c.getList("parents",Object.class,List.of()).size()<=1&&c.getString("commit_sha")!=null){bySha.computeIfAbsent(c.getString("commit_sha"),k->new ArrayList<>()).add(c);byPr.computeIfAbsent(id(c.get("pull_request_id")),k->new ArrayList<>()).add(c);}
    var nativeOriginals=find("commit",Filters.in("revision_hash",bySha.keySet()),fields);
    var stamps=new HashMap<String,List<Stamp>>();
    for(var c:nativeOriginals)if(at(c,"author_date")!=null&&c.getList("parents",Object.class,List.of()).size()<=1)stamps.computeIfAbsent(c.getString("revision_hash"),k->new ArrayList<>()).add(new Stamp(at(c,"author_date"),at(c,"committer_date"),"commit.author_date",id(c.get("_id"))));
    var events=find("event",Filters.in("issue_id",wanted),"issue_id","created_at","status","author_id","old_value","new_value","external_id");
    var comments=find("issue_comment",Filters.in("issue_id",wanted),"issue_id","created_at","author_id","comment");
    for(var c:comments)gitDates(c.getString("comment"),"issue_comment",id(c.get("_id")),bySha,stamps);
    var mailing=find("mailing_list",Filters.in("project_id",ids(projects,"_id")),"project_id");
    for(var p:projects) {
      String name=p.getString("name");var listIds=mailing.stream().filter(d->Objects.equals(d.get("project_id"),p.get("_id"))).map(d->d.getObjectId("_id")).toList();
      var relevant=associated.entrySet().stream().filter(e->byId.get(e.getKey()).unit().project().equals(name)).toList();
      if(listIds.isEmpty()||relevant.isEmpty())continue;
      var issueKeys=relevant.stream().map(e->byId.get(e.getKey()).unit().key()).toList();
      var numbers=relevant.stream().flatMap(e->e.getValue().stream()).map(d->d.getString("external_id")).filter(Objects::nonNull).distinct().toList();
      String subject=keys(issueKeys)+"|(?:pull request|issue|PR)\\s*#(?:"+String.join("|",numbers)+")(?![0-9])";
      for(var mail:find("message",Filters.and(Filters.in("mailing_list_id",listIds),Filters.regex("subject",subject,"i")),"body"))gitDates(mail.getString("body"),"message",id(mail.get("_id")),bySha,stamps);
    }
    var prComments=find("pull_request_comment",Filters.in("pull_request_id",ids(kept.values(),"_id")),"pull_request_id","author_id","created_at");
    var reviews=find("pull_request_review",Filters.in("pull_request_id",ids(kept.values(),"_id")),"pull_request_id","creator_id","submitted_at","state");
    var reviewComments=find("pull_request_review_comment",Filters.in("pull_request_review_id",ids(reviews,"_id")),"pull_request_review_id","creator_id","created_at");
    var allAuthors=new HashSet<ObjectId>();
    for(var list:List.of(new ArrayList<>(raw.values()),prCommits,nativeOriginals,events,comments,prComments,reviews,reviewComments)){allAuthors.addAll(ids(list,"author_id"));allAuthors.addAll(ids(list,"creator_id"));}
    var people=new HashMap<String,Corpus.Person>();for(var d:find(thesis,"person_canonical",Filters.in("_id",allAuthors),"canonical_id","is_bot"))people.put(id(d.get("_id")),new Corpus.Person(id(d.get("canonical_id")),Boolean.TRUE.equals(d.getBoolean("is_bot"))));
    var candidates=new ArrayList<World.Commit>();
    for(var c:raw.values()) {
      Long t=at(c,"author_date");String project=vcs.get(id(c.get("vcs_system_id")));if(t==null||project==null||c.getList("parents",Object.class,List.of()).size()>1)continue;
      String author=id(c.get("author_id"));var contribution=contrib.get(id(c.get("_id")));boolean bot=!human(author,bots,people)||(contribution!=null&&Boolean.TRUE.equals(contribution.getBoolean("is_bot")));
      var links=c.getList("linked_issue_ids",Object.class,List.of()).stream().map(MongoDurationSource::id).filter(Objects::nonNull).toList();
      candidates.add(new World.Commit(id(c.get("_id")),project,c.getString("revision_hash"),World.person(author,people),bot,t,World.visible(t,at(c,"committer_date")==null?t:at(c,"committer_date"),merged.get(c.getString("revision_hash"))),num(contribution,"java_files_touched")>0,num(contribution,"java_lines_added")+num(contribution,"java_lines_deleted"),null,links));
    }
    var linked=new HashMap<String,List<World.Commit>>();for(var c:World.oncePerChange(candidates))for(String issue:c.linked())if(byId.containsKey(issue))linked.computeIfAbsent(issue,k->new ArrayList<>()).add(c);
    var eventsByIssue=grouped(events,"issue_id");var commentsByIssue=grouped(comments,"issue_id");
    var commentsByPr=grouped(prComments,"pull_request_id");var reviewsByPr=grouped(reviews,"pull_request_id");
    var commentsByReview=grouped(reviewComments,"pull_request_review_id");
    var result=new LinkedHashMap<String,DurationEvidence>();
    for(var r:rows) {
      String issue=r.unit().issueId();long end=r.unit().resolvedAt().toEpochMilli();var changes=new ArrayList<DurationEvidence.Change>();var integrations=new ArrayList<DurationEvidence.Integration>();var activity=new ArrayList<DurationEvidence.Activity>();var warnings=new ArrayList<String>();var status=new ArrayList<Corpus.Event>();
      for(var e:eventsByIssue.getOrDefault(issue,List.of())) {
        if("status".equals(e.getString("status")))status.add(new Corpus.Event(issue,at(e,"created_at"),"status",id(e.get("author_id")),MongoStudySource.text(e.get("old_value")),MongoStudySource.text(e.get("new_value")),e.getString("external_id")));
        if("attachment".equalsIgnoreCase(e.getString("status"))&&human(id(e.get("author_id")),bots,people)&&at(e,"created_at")!=null)activity.add(new DurationEvidence.Activity(at(e,"created_at"),"attachment",id(e.get("_id"))));
      }
      for(var c:commentsByIssue.getOrDefault(issue,List.of()))if(at(c,"created_at")!=null&&human(id(c.get("author_id")),bots,people)&&!DayBoundaries.templated(Objects.toString(c.getString("comment"),"")))activity.add(new DurationEvidence.Activity(at(c,"created_at"),"jira_comment",id(c.get("_id"))));
      var replacementNumbers=new HashSet<String>();var replacementIds=new HashSet<String>();var replacementHashes=new HashSet<String>();int full=0,partial=0;
      for(var p:associated.getOrDefault(issue,List.of())) {
        String pr=id(p.get("_id"));var originals=byPr.getOrDefault(pr,List.of());
        if(originals.stream().anyMatch(c->foreign(title(c),r.unit().key()))){warnings.add("mixed_issue_pr:"+pr);continue;}
        var humanOriginals=originals.stream().filter(c->human(id(c.get("author_id")),bots,people)).toList();
        int recovered=0;
        for(var c:humanOriginals) {
          String sha=c.getString("commit_sha");var dates=stamps.getOrDefault(sha,List.of());
          if(dates.stream().map(Stamp::at).distinct().count()>1){warnings.add("conflicting_date:"+sha);continue;}
          if(dates.isEmpty())continue;var stamp=dates.stream().filter(s->s.source().equals("commit.author_date")).findFirst().orElse(dates.getFirst());
          if(stamp.at()>=end){warnings.add("original_after_resolution:"+sha);continue;}
          if(!human(id(c.get("author_id")),bots,people))continue;
          recovered++;changes.add(new DurationEvidence.Change(sha,stamp.at(),stamp.committed(),stamp.source(),stamp.id(),true));
        }
        if(!humanOriginals.isEmpty()&&recovered==humanOriginals.size()){
          full++;replacementNumbers.add(p.getString("external_id"));
          String mergeId=id(p.get("merge_commit_id"));if(mergeId!=null){replacementIds.add(mergeId);String hash=mergeHashes.get(mergeId);if(hash!=null)replacementHashes.add(hash);}
        }else partial++;
        for(var c:commentsByPr.getOrDefault(pr,List.of()))if(at(c,"created_at")!=null&&human(id(c.get("author_id")),bots,people))activity.add(new DurationEvidence.Activity(at(c,"created_at"),"pr_comment",id(c.get("_id"))));
        var reviewIds=new HashSet<String>();for(var c:reviewsByPr.getOrDefault(pr,List.of())){
          reviewIds.add(id(c.get("_id")));if(at(c,"submitted_at")!=null&&human(id(c.get("creator_id")),bots,people)&&!"PENDING".equalsIgnoreCase(c.getString("state")))activity.add(new DurationEvidence.Activity(at(c,"submitted_at"),"pr_review",id(c.get("_id"))));
        }
        for(String reviewId:reviewIds)for(var c:commentsByReview.getOrDefault(reviewId,List.of()))if(at(c,"created_at")!=null&&human(id(c.get("creator_id")),bots,people))activity.add(new DurationEvidence.Activity(at(c,"created_at"),"pr_review_comment",id(c.get("_id"))));
      }
      for(var c:linked.getOrDefault(issue,List.of()))if(!c.bot()&&c.who()!=null&&c.visible()<end){
        var document=raw.get(c.id());var m=CLOSE.matcher(Objects.toString(document.getString("message"),""));boolean integration=replacementIds.contains(c.id())||replacementHashes.contains(c.hash());
        while(m.find())if(replacementNumbers.contains(m.group(1)))integration=true;
        integrations.add(new DurationEvidence.Integration(c.hash(),at(document,"committer_date"),c.visible(),c.id(),integration));
        if(!integration)changes.add(new DurationEvidence.Change(c.hash(),c.authoredAt(),at(document,"committer_date"),"native.commit.author_date",c.id(),false));
      }
      // Same changelog ordering as the original target, including tied Jira event instants.
      status=new ArrayList<>(app.issues.DurationEvidence.ordered(status));
      boolean originalsPresent=changes.stream().anyMatch(DurationEvidence.Change::original);
      var originalHashes=new HashSet<String>();changes.stream().filter(DurationEvidence.Change::original).forEach(c->originalHashes.add(c.sha()));
      boolean nativeAlongside=originalsPresent&&changes.stream().anyMatch(c->!c.original()&&!originalHashes.contains(c.sha()));
      if(nativeAlongside)warnings.add("native_changes_alongside_originals");
      String quality=changes.isEmpty()?"no_eligible_commit":full>0&&partial==0&&!nativeAlongside?"recorded_originals_recovered":changes.stream().anyMatch(DurationEvidence.Change::original)?"partial_originals":"native_provisional";
      result.put(issue,new DurationEvidence(changes,activity,status,quality,warnings,integrations));
    }
    return Map.copyOf(result);
  }
}
