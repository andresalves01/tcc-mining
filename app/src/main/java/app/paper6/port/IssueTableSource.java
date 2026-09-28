package app.paper6.port;

import app.paper6.Tables;
import java.util.Collection;
import java.util.Set;

/** Loads the tables the constructs read, read-only, all at once -- see {@link Tables} for why. */
public interface IssueTableSource {

  /**
   * @param populationIds the issues whose own fields and changes are loaded. History is loaded for
   *     the whole corpus: a reporter's earlier issues count whether or not they are labels
   */
  Tables load(Collection<String> populationIds);

  Set<String> reads();

  String describe();
}
