package app.pullrequests.port;

import app.pullrequests.PriorStudyCorrection;

/**
 * Where the correction of the previous paper's columns reads its tables from, read-only. A port so
 * the command that validates and writes the corrected export never learns where the data lives.
 */
public interface PriorStudyTableSource {

  PriorStudyCorrection.Inputs load();

  String describe();
}
