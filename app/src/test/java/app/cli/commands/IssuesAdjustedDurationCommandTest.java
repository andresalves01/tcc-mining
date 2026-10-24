package app.cli.commands;

import static org.junit.jupiter.api.Assertions.*;
import app.cli.Context;
import app.issues.*;
import app.issues.port.StudySource;
import app.issues.Universe.Population;
import app.pipeline.port.Progress;
import app.study.Row;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import app.pipeline.port.SilentProgress;

class IssuesAdjustedDurationCommandTest {
  @TempDir Path dir;
  @Test void reservedDescriptionIsRecoveredAtStartRatherThanAtResolution() {
    String id="000000000000000000000002";
    var created=Instant.parse("2024-01-01T12:00:00Z");
    var started=created.plusSeconds(86400);
    var resolved=started.plusSeconds(86400);
    var unit=new IssueUnit(id,"P-2","p",null,null,created,resolved,"Fixed",false,started);
    var row=new RowCache.Entry(unit,new Row(id,Map.of(),Set.of()),
        Map.of(Population.IN_PROGRESS,new String[]{"TEST",""}));
    var afterStart=new Corpus.Event(id,started.plusSeconds(3600).toEpochMilli(),"desc",null,
        "Original description","Later revision",null);
    var text=new Corpus.Text("Title","Later revision",List.of(afterStart));
    assertEquals(DescriptionCleaner.clean("Original description"),
        IssuesAdjustedDurationCommand.descriptionAtStart(row,text));
    assertNull(IssuesAdjustedDurationCommand.descriptionAtStart(row,null));
  }

  @Test void evidenceExportIsTrainingOnlyCachedAndPinnedToBotPolicy() throws Exception {
    String id="000000000000000000000001";
    var start=Instant.parse("2024-01-01T12:00:00Z");var end=start.plusSeconds(86400);
    var u=new IssueUnit(id,"P-1","p",null,null,start,end,"Fixed",false,start);
    var row=new RowCache.Entry(u,new Row(id,Map.of(BusinessDays.BOUNDARY,0.,BusinessDays.ACTIVE,1.),Set.of()),Map.of(Population.IN_PROGRESS,new String[]{"TRAIN","TRAIN"}));
    Path input=dir.resolve("input.tsv"),bots=dir.resolve("bots.txt"),out=dir.resolve("out");
    RowCache.write(input,List.of(row),List.of(BusinessDays.BOUNDARY,BusinessDays.ACTIVE),List.of(Population.IN_PROGRESS));
    // The test row is deliberately malformed except for its split verdict. It must not be parsed.
    var lines=Files.readAllLines(input);var head=Arrays.asList(lines.getFirst().split("\t",-1));
    var bad=lines.get(1).split("\t",-1);bad[head.indexOf("id")]="outer-sentinel";
    bad[head.indexOf("andamento_externa")]="TEST";bad[head.indexOf("resolvida_em")]="SEALED";
    lines.add(String.join("\t",bad));Files.write(input,lines);Files.writeString(bots,"");
    int[] calls={0};
    StudySource source=new StudySource(){
      public Corpus load(){throw new AssertionError("full source read");}
      public Map<String,Corpus.Text> texts(Collection<String> ids){throw new AssertionError("texts read");}
      public Set<String> reads(){return Set.of();} public String describe(){return "fake";}
      public Map<String,DurationEvidence> durationEvidence(List<RowCache.Entry> rows,Set<String> bots){
        calls[0]++;assertEquals(List.of(id),rows.stream().map(r->r.unit().issueId()).toList());
        return Map.of(id,new DurationEvidence(List.of(),List.of(),List.of(),"no_eligible_commit",List.of(),List.of()));
      }
    };
    var context=new Context(null,null,null,null,null,null,null,null,null,null,null,SilentProgress.INSTANCE,source,null,null);
    var values=Map.of("MATRIX",input.toString(),"ADJUSTED_DIR",out.toString(),"BOT_ACCOUNTS",bots.toString(),"ADJUSTED_STEP","evidence");
    var command=new IssuesAdjustedDurationCommand(values::get);command.run(context);command.run(context);
    assertEquals(1,calls[0]);assertTrue(Files.exists(out.resolve("evidence-v1.json")));
    Files.writeString(bots,"new-bot\n");
    assertThrows(IllegalArgumentException.class,()->command.run(context));assertEquals(1,calls[0]);
  }
}
