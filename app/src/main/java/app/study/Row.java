package app.study;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One unit's attribute values, with absence kept apart from zero.
 *
 * <p>The distinction is the whole reason this is a record and not a {@code double[]}. A person who
 * finished tasks before and none of them on time scores zero on a share of on-time tasks, and that
 * zero is a fact about them. A person who had finished none has no share at all, and writing zero
 * there would assert the same fact about somebody the corpus says nothing about. The specification forbids both the silent zero and the division by one.
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
}
