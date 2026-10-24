package app.issues;

import static org.junit.jupiter.api.Assertions.*;
import app.issues.Universe.Population;
import app.study.Row;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AdjustedDurationTest {
  static long at(String day) { return Instant.parse("2024-01-" + day + "T12:00:00Z").toEpochMilli(); }
  static RowCache.Entry row(String id, String split, List<Corpus.Event> events) {
    var start=Instant.ofEpochMilli(at("01")); var end=Instant.ofEpochMilli(at("19"));
    var unit=new IssueUnit(id,id,"p","r",null,start,end,"Fixed",false,start);
    return new RowCache.Entry(unit,new Row(id, Map.of(BusinessDays.BOUNDARY,0.0,
        BusinessDays.ACTIVE,(double)BusinessDays.active(events,at("01"),at("19"),0)),Set.of()),
        Map.of(Population.IN_PROGRESS,new String[]{"TRAIN",split}));
  }
  static DurationEvidence evidence(List<String> commits, List<String> comments, List<Corpus.Event> events) {
    return new DurationEvidence(commits.stream().map(d->new DurationEvidence.Change(d,at(d),at(d),"native",d,false)).toList(),
        comments.stream().map(d->new DurationEvidence.Activity(at(d),"comment",d)).toList(),events,"native_provisional",List.of(), List.of());
  }
  @Test void retainsPrefixCapsGapsWithoutAddingActivityTwiceAndRemovesSilentTail() {
    var c=AdjustedDuration.count(row("a","TRAIN",List.of()),evidence(List.of("03","17"),List.of("04","05","08","09","18"),List.of()),2);
    assertEquals(2,c.prefix()); assertEquals(8,c.total()); // prefix2 + active floor5 (including next commit) + review1
    assertEquals(1,c.review()); assertEquals(5,c.removedInternalDays()); assertEquals(1,c.waitDays());
  }
  @Test void explicitDatesDuringOpenCanExceedTheOldStatusCount() {
    var events=List.of(new Corpus.Event("a",at("02"),"status","u","In Progress","Open","1"),
        new Corpus.Event("a",at("19"),"status","u","Open","Resolved","2"));
    var r=row("a","TRAIN",events);
    var c=AdjustedDuration.count(r,evidence(List.of("01"),List.of("03","04"),events),2);
    assertEquals(1,c.oldDays()); assertEquals(2,c.total()); assertEquals(2,c.pausedActivityDays());
  }
  @Test void previousCodeCanYieldZeroButNoCodeIsMissing() {
    var r=row("a","TRAIN",List.of());
    var zero=AdjustedDuration.count(r,evidence(List.of("01"),List.of(),List.of()),2);
    assertEquals(0,zero.total());
    var missing=AdjustedDuration.count(r,evidence(List.of(),List.of("04"),List.of()),2);
    assertNull(missing.total());
    var result=AdjustedDuration.apply(List.of(r),Map.of("a",evidence(List.of("01"),List.of(),List.of())),
        new AdjustedDuration.Threshold(0,0,1,2.5,2,10));
    assertEquals(1,BusinessDays.population(result.entries(),Augmentation.Variant.INNER).entries().size());
    assertArrayEquals(r.parts().get(Population.IN_PROGRESS),result.entries().getFirst().parts().get(Population.IN_PROGRESS));
    assertEquals(14.0,result.entries().getFirst().row().values().get(BusinessDays.ACTIVE));
  }
  @Test void validationCannotMoveTheFittingThresholdAndSavedThresholdIsReused() {
    var fit=row("f","TRAIN",List.of()); var val=row("v","TEST",List.of());
    var ev=Map.of("f",evidence(List.of("01","02","03","04","05","08","09","11"),List.of(),List.of()),
        "v",evidence(List.of("01","18"),List.of(),List.of()));
    var threshold=AdjustedDuration.threshold(List.of(fit,val),ev);
    assertEquals(1,threshold.cap()); assertEquals(7,threshold.gaps());
    var result=AdjustedDuration.apply(List.of(val),ev,threshold);
    assertEquals(threshold,result.threshold()); assertEquals(1,result.counts().get("v").implementation());
  }
  @Test void missingEnvelopeRefusesButNoCodeWithARealPausedHistoryIsMissing() {
    var events=List.of(new Corpus.Event("a",at("02"),"status","u","In Progress","Open","1"));
    var r=row("a","TRAIN",events);
    var threshold=new AdjustedDuration.Threshold(0,0,1,2.5,2,10);
    assertThrows(IllegalArgumentException.class,()->AdjustedDuration.apply(List.of(r),Map.of(),threshold));
    var result=AdjustedDuration.apply(List.of(r),Map.of("a",evidence(List.of(),List.of(),events)),threshold);
    assertNull(result.counts().get("a").total());
    assertTrue(BusinessDays.population(result.entries(),Augmentation.Variant.INNER).entries().isEmpty());
  }
  @Test void zeroHistoryIsIncludedOnlyForTheVersionedTargetAndMissingIsExcluded() {
    var zero=new Augmentation.Issue("z","p","r",0,1,0,0,"TRAIN","TRAIN","text",true);
    var legacy=new Augmentation.Issue("l","p","r",0,1,0,0,"TRAIN","TRAIN","text");
    var missing=new Augmentation.Issue("m","p","r",0,1,-1,-1,"TRAIN","TRAIN","text",true);
    assertTrue(zero.counted(Augmentation.Variant.INNER));
    assertFalse(legacy.counted(Augmentation.Variant.INNER)); assertFalse(missing.counted(Augmentation.Variant.INNER));
  }
  @Test void zeroPredictionIntervalsHaveNoArtificialOneDayFloor() {
    var c=PredictionIntervals.calibrate(new double[]{0,1,2,3},new double[]{0,0,1,2},0);
    assertEquals(0,c.interval(0,0)[0]);
    assertEquals(1,PredictionIntervals.interval(0,0,PredictionIntervals.FLOOR)[0]);
    assertEquals(0,PredictionIntervals.interval(0,0,0)[1]);
  }
  @Test void zeroObservedHasDefinedPredictionDenominatorErrorAndZeroIntervalCoverage() {
    var sample=new EstimationUncertainty.Sample("all",List.of("a","b"),new boolean[]{true,true},
        new double[]{0,0},Map.of("m",new double[]{2,0}),new double[]{0,0,0});
    var result=EstimationUncertainty.read(sample,Map.of("m",PredictionIntervals.calibrate(new double[]{2,0},new double[]{0,0},0)),1);
    var overall=result.summaries().getFirst();
    assertEquals(1,overall.positiveEstimates());assertEquals(1,overall.zeroEstimates());
    assertEquals(-1,overall.metrics().get(1).value());assertEquals(1,overall.metrics().get(0).value());
    var projects=new ArrayList<String>();for(int i=0;i<40;i++)projects.add("p"+(i/5));
    var piSample=new PredictionIntervals.Sample(projects,new double[40],Map.of(SimilarityScoring.GLOBAL,new double[40]),new double[]{0,0,0},0);
    var pi=PredictionIntervals.inner(piSample,piSample,1);
    var zero=pi.reads().getFirst().strata().get("zero");
    assertTrue(zero.n()>0);assertEquals(1,zero.coverage().value());assertTrue(Double.isNaN(zero.relativeWidth()));
  }

  @Test void conflictingClocksAreFlaggedWithoutReplacingAuthorshipWithIntegrationTime() {
    var original=new DurationEvidence.Change("sha",at("05"),at("04"),"original","id",true);
    var integration=new DurationEvidence.Integration("squash",at("03"),at("03"),"native",true);
    var e=new DurationEvidence(List.of(original),List.of(),List.of(),"recovered",List.of(),List.of(integration));
    assertTrue(e.warnings().contains("author_after_committer:sha"));
    assertTrue(e.warnings().contains("original_after_integration:squash"));
    assertEquals(at("05"),e.changes().getFirst().authoredAt());
  }

}
