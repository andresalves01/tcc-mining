package app.paper5.port;

import app.paper5.Artigo4Correction;

/**
 * Where the correction of the previous paper's columns reads its tables from, read-only. A port so
 * the command that validates and writes the corrected export never learns where the data lives.
 */
public interface Artigo4TableSource {

  Artigo4Correction.Inputs load();

  String describe();
}
