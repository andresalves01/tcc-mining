package app.paper5;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One unit's attribute values, with absence kept apart from zero.
 *
 * <p>The distinction is the whole reason this is a record and not a {@code double[]}. A creator who
 * had opened pull requests before and had none of them merged scores zero on
 * {@code owner_merged_ratio}, and that zero is a fact about them. A creator who had opened none has
 * no ratio at all, and writing zero there would assert the same fact about somebody the corpus says
 * nothing about. The specification forbids both the silent zero and the division by one.
 *
 * @param values every column that has one, including the {@code _ausente} indicators, which are
 *     always present and always zero or one
 * @param absent the constructs with no value on this row. A column named here is never also in
 *     {@code values}
 */
public record Row(String unitId, Map<String, Double> values, Set<String> absent) {

  public Row {
    values = Map.copyOf(values);
    absent = Set.copyOf(absent);
    for (final String name : absent) {
      if (values.containsKey(name)) {
        throw new IllegalArgumentException(
            name + " is both absent and valued, which is the confusion this type exists to stop");
      }
    }
  }

  public Optional<Double> get(final String name) {
    return Optional.ofNullable(values.get(name));
  }

  public boolean isAbsent(final String name) {
    return absent.contains(name);
  }
}
