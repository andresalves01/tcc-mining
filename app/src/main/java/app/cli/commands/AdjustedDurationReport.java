package app.cli.commands;

import app.issues.*;
import app.study.model.Score;
import app.model.Parallel;
import java.nio.file.*;
import java.util.*;

/** Descriptive validation results by evidence quality, without selecting models on subgroups. */
final class AdjustedDurationReport {
  private AdjustedDurationReport() {}
  static void write(Path root,Path matrix) throws Exception {
    Path report=root.resolve("inner-report");
    var quality=new HashMap<String,String>();
    var audit=Files.readAllLines(root.resolve("per-issue.tsv"));
    for(String line:audit.subList(1,audit.size())){var f=line.split("\t",-1);quality.put(f[0],f[3]);}
    var lines=Files.readAllLines(report.resolve(IssuesSimilarityInnerReportCommand.PREDICTIONS));
    var header=List.of(lines.getFirst().split("\t",-1));
    var rows=lines.subList(1,lines.size()).stream().map(l->l.split("\t",-1)).toList();
    var calibrationRows=Files.readAllLines(report.resolve(IssuesSimilarityInnerReportCommand.CALIBRATION));
    var factors=new LinkedHashMap<String,double[]>();var spans=new HashMap<String,double[]>();
    for(String line:calibrationRows.subList(1,calibrationRows.size())){var f=line.split("\t",-1);
      var values=factors.computeIfAbsent(f[0],k->new double[2]);values[Double.parseDouble(f[1])==.8?0:1]=Double.parseDouble(f[3]);
      spans.put(f[0],new double[]{Double.parseDouble(f[2]),Double.parseDouble(f[4]),Double.parseDouble(f[5])});
    }
    var calibration=new LinkedHashMap<String,PredictionIntervals.Calibration>();
    factors.forEach((m,f)->{var s=spans.get(m);calibration.put(m,new PredictionIntervals.Calibration((int)s[0],f,s[1],s[2],0));});
    var entries=RowCache.read(matrix,Universe.Population.IN_PROGRESS,"TRAIN");
    var descriptions=EstimationUncertainty.descriptions(entries);
    var groups=new TreeSet<>(quality.values());groups.remove("no_eligible_commit");
    var results=new ArrayList<EstimationUncertainty.Reading>();
    var scores=new ArrayList<String>();scores.add("reading\tquality\tmodel\tn\tmae_bd\tspearman\tpairwise");
    for(String reading:List.of("usual_flow","all"))for(String group:groups){
      var selected=rows.stream().filter(f->group.equals(quality.get(f[0]))&&(reading.equals("all")||Boolean.parseBoolean(f[4]))).toList();
      if(selected.isEmpty())continue;
      var observed=selected.stream().mapToDouble(f->Double.parseDouble(f[3])).toArray();
      var predicted=new LinkedHashMap<String,double[]>();
      for(int col=6;col<header.size();col++){final int index=col;predicted.put(header.get(col),selected.stream().mapToDouble(f->Double.parseDouble(f[index])).toArray());}
      var available=new boolean[selected.size()];for(int i=0;i<available.length;i++)available[i]=Boolean.TRUE.equals(descriptions.get(selected.get(i)[0]));
      var sample=new EstimationUncertainty.Sample(reading+" / "+group,selected.stream().map(f->f[2]).toList(),available,observed,predicted,new double[]{0,0,0});
      var result=EstimationUncertainty.read(sample,calibration,Parallel.threads(Parallel.BOOTSTRAP_THREADS));
      results.add(new EstimationUncertainty.Reading(result.summaries().stream().filter(s->s.grouping().equals("overall")).toList(),result.percentiles()));
      predicted.forEach((model,p)->{var s=Score.of(model,p,observed,1,1);scores.add(String.join("\t",reading,group,model,Integer.toString(s.n()),Double.toString(s.mae()),Double.toString(s.spearman()),Double.toString(s.pairwise())));});
    }
    var tables=UncertaintyTables.render(results);
    var md=new ArrayList<String>(List.of("# Validação por qualidade da evidência","", "Leitura descritiva da mesma validação interna otimista; os subgrupos não selecionam modelos. Os IC95 reamostram projetos. Os intervalos preditivos ilustrados usam a calibração agrupada, sem garantia específica por qualidade. A tabela de qualidade descreve a recuperação dos registros, sem certificar a completude real da branch.",""));
    md.addAll(tables.markdown());
    Files.write(report.resolve("evidence-quality-report.md"),md);
    Files.write(report.resolve("evidence-quality-uncertainty.tsv"),tables.uncertainty());
    Files.write(report.resolve("evidence-quality-percentiles.tsv"),tables.percentiles());
    Files.write(report.resolve("evidence-quality-models.tsv"),scores);
  }
}
