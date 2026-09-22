package app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O perfil de pull request e uma lista derivada, nao uma edicao da especificacao do TCC.
 *
 * <p>Este teste existe por causa de um erro real: a primeira versao da coluna derivada foi
 * acrescentada dentro de {@code build()}, o que a faria aparecer tambem no perfil ISSUE e mudar o
 * estudo ja escrito sem ninguem pedir.
 */
class ColumnsProfileTest {

  @AfterEach
  void restoreDefault() {
    Columns.use(Columns.Profile.ISSUE);
  }

  @Test
  @DisplayName("o perfil ISSUE e exatamente a especificacao do TCC, sem a coluna derivada")
  void issueProfileIsUntouched() {
    Columns.use(Columns.Profile.ISSUE);

    final List<String> names = Columns.names();

    assertEquals(Columns.ALL.stream().map(Columns.Column::name).toList(), names);
    assertFalse(names.contains(Columns.LINES_PER_COMMIT),
        "uma coluna criada para o outro perfil nao pode vazar para este");
    assertTrue(names.contains("ln(commits dev)"));
    assertTrue(names.contains("descricao ausente"));
  }

  @Test
  @DisplayName("o perfil de pull request troca a contagem de commits pela razao, no lugar dela")
  void pullRequestProfileSwapsTheContrast() {
    Columns.use(Columns.Profile.PULL_REQUEST);

    final List<String> names = Columns.names();

    assertFalse(names.contains("ln(commits dev)"));
    assertTrue(names.contains(Columns.LINES_PER_COMMIT));
    assertEquals(names.indexOf("ln(LOC acum. dev)") + 1, names.indexOf(Columns.LINES_PER_COMMIT),
        "a derivada fica junto do volume de onde sai, para o grupo DEV ficar legivel");
    assertFalse(names.contains("descricao ausente"));
    assertFalse(names.contains("ln(McCC_tot dev)"));
  }

  @Test
  @DisplayName("a derivada pertence ao grupo do desenvolvedor, senao a ablacao a conta no lugar errado")
  void derivedColumnBelongsToTheDeveloperGroup() {
    Columns.use(Columns.Profile.PULL_REQUEST);

    final int[] developer = Columns.indicesOf(Columns.Group.DEV);
    final int derived = Columns.names().indexOf(Columns.LINES_PER_COMMIT);

    assertTrue(List.of(developer).stream().anyMatch(all -> {
      for (final int index : all) {
        if (index == derived) {
          return true;
        }
      }
      return false;
    }));
  }
}
