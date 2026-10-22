package app.cli.commands;

import static org.junit.jupiter.api.Assertions.*;
import app.issues.*;
import app.issues.Universe.Population;
import app.study.Row;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdjustedDurationReportTest {
  @TempDir Path root;
  @Test void evidenceStrataUseOnlyTheirMatchingValidationIdsAndKeepZeroTruth() throws Exception {
    Path report=root.resolve("inner-report");Files.createDirectories(report);
    Files.write(root.resolve("per-issue.tsv"),List.of("id\tissue\tinner_split\tquality", "a\tP-1\tTEST\tnative_provisional", "b\tP-2\tTEST\trecorded_originals_recovered"));
    Files.write(report.resolve(IssuesSimilarityInnerReportCommand.PREDICTIONS),List.of(
        "id\tkey\tproject\tobserved_business_days\tusual_flow\thas_implementation_commit\tm",
        "a\tP-1\tp\t0\ttrue\ttrue\t2", "b\tP-2\tq\t4\tfalse\ttrue\t3"));
    Files.write(report.resolve(IssuesSimilarityInnerReportCommand.CALIBRATION),List.of(
        "model\tlevel\tcalibration_issues\tfactor\tprediction_min_bd\tprediction_max_bd",
        "m\t0.8\t20\t0.5\t2\t3", "m\t0.95\t20\t1.0\t2\t3"));
    var start=Instant.parse("2024-01-01T12:00:00Z");var rows=new ArrayList<RowCache.Entry>();
    for(String id:List.of("a","b")){
      var u=new IssueUnit(id,"P-1","p",null,null,start,start.plusSeconds(86400),"Fixed",false,start);
      rows.add(new RowCache.Entry(u,new Row(id,Map.of("A01_desc_length",1.),Set.of()),Map.of(Population.IN_PROGRESS,new String[]{"TRAIN","TEST"})));
    }
    Path matrix=root.resolve("matrix.tsv");RowCache.write(matrix,rows,List.of("A01_desc_length"),List.of(Population.IN_PROGRESS));
    AdjustedDurationReport.write(root,matrix);
    var output=Files.readAllLines(report.resolve("evidence-quality-models.tsv"));
    assertEquals(4,output.size());
    assertTrue(output.stream().anyMatch(l->l.startsWith("all\tnative_provisional\tm\t1\t2.0\t")));
    assertTrue(output.stream().anyMatch(l->l.startsWith("all\trecorded_originals_recovered\tm\t1\t1.0\t")));
    assertFalse(output.stream().anyMatch(l->l.startsWith("usual_flow\trecorded_originals_recovered")));
    var uncertainty=Files.readString(report.resolve("evidence-quality-uncertainty.tsv"));
    assertTrue(uncertainty.contains("mean_signed_relative_error\t-1.0"));
  }
}
