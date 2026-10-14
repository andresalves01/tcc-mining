package app.issues;

/** The three families of attributes the study compares, and their union. */
public enum Family {

  /** The author's own specification: Article 1's columns and the additions translated from Article 4. */
  OWN,

  /** Kikas, Dumas and Pfahl (MSR 2016), without the text score and the subscribers: "Kikas parcial". */
  KIKAS,

  /** Al-Zubaidi, Dam, Ghose and Li (PROMISE 2017), 12 of the 13 attributes. */
  ALZUBAIDI,

  /** The three together, a construct shared by two of them entering once. Only a cell family. */
  UNION
}
