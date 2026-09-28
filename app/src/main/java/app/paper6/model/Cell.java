package app.paper6.model;

import app.paper6.Attribute;
import app.paper6.Attributes;
import app.paper6.Family;
import app.paper6.Profile;
import app.paper6.TemporalClass;
import java.util.ArrayList;
import java.util.List;

/**
 * One cell of the grid: a named set of constructs.
 *
 * <p>Most cells are a family read under a profile. Two are not, and exist for the ablation: the
 * block that describes the person who did the work, alone, and the block that describes the work,
 * alone. They are fitted and frozen like every other cell, so the ablation reaches the reserved set
 * under the same registration instead of being a development-side footnote.
 */
public record Cell(String label, Family family, Profile profile, List<Attribute> attributes) {

  public static final String SO_ATRIBUICAO = "SO_ATRIBUICAO (so o bloco da pessoa que fez)";
  public static final String SO_DESFECHO = "SO_DESFECHO (so a mudanca que resolveu)";

  public Cell {
    attributes = List.copyOf(attributes);
  }

  public static Cell of(final Family family, final Profile profile) {
    return new Cell(family + " / " + profile, family, profile, Attributes.of(family, profile));
  }

  static Cell block(final String label, final TemporalClass temporalClass) {
    return new Cell(label, null, null, Attributes.all().stream()
        .filter(a -> a.temporalClass() == temporalClass).toList());
  }

  /**
   * Every family under every profile, minus two kinds of cell, both reported by name.
   *
   * <p>An <b>empty</b> cell -- the triage family has nothing dated, so nothing under the strict
   * profile. A <b>repeated</b> cell -- where a profile adds no construct to that family, the cell is
   * the one below it, and fitting it again would spend an hour to print the same number twice.
   */
  public static List<Cell> grid(final List<String> skipped) {
    final List<Cell> cells = new ArrayList<>();
    for (final Family family : Family.values()) {
      List<Attribute> previous = null;
      for (final Profile profile : Profile.values()) {
        final Cell cell = of(family, profile);
        if (cell.attributes().isEmpty()) {
          skipped.add(cell.label() + ": vazia");
        } else if (cell.attributes().equals(previous)) {
          skipped.add(cell.label() + ": igual ao degrau anterior");
        } else {
          cells.add(cell);
        }
        previous = cell.attributes();
      }
    }
    cells.add(block(SO_ATRIBUICAO, TemporalClass.CONDICIONADO_A_ATRIBUICAO));
    cells.add(block(SO_DESFECHO, TemporalClass.OBSERVAVEL_APENAS_NO_DESFECHO));
    return List.copyOf(cells);
  }

  /** Finds a cell of {@link #grid} by its label, which is how the registration names them. */
  public static Cell byLabel(final String label) {
    for (final Cell cell : grid(new ArrayList<>())) {
      if (cell.label().equals(label)) {
        return cell;
      }
    }
    throw new IllegalArgumentException("no such cell: " + label);
  }
}
