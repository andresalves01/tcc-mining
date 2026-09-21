package app.model.port;

import app.model.Observations;
import app.model.Partition;

/** Where the design matrix is read from, already joined to its outcome and its partition. */
public interface ObservationSource {

  Observations of(Partition partition);

  String describe();
}
