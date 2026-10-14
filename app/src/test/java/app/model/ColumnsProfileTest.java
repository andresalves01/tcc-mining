package app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.labels.TtcTarget;
import app.pullrequests.PullRequestExtras;
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
    Columns.target(TtcTarget.TOTAL);
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
  @DisplayName("toda coluna do perfil que le campo de extras usa um nome que PullRequestExtras declara")
  void everyExtraColumnNamesADeclaredField() {
    Columns.use(Columns.Profile.PULL_REQUEST);
    Columns.target(TtcTarget.AUTOR);

    final List<String> declared = PullRequestExtras.FIELDS;
    for (final Columns.Column column : Columns.all()) {
      final String field = column.field();
      if (field.startsWith("pr_") || field.startsWith("exame_")) {
        assertTrue(declared.contains(field),
            "campo '" + field + "' nao existe em PullRequestExtras: viraria coluna constante "
                + "em silencio em vez de erro");
      }
    }
  }

  @Test
  @DisplayName("as colunas do exame so existem no alvo AUTOR -- nos outros dois elas SAO o desfecho")
  void examColumnsOnlyExistForTheAuthorCycle() {
    Columns.use(Columns.Profile.PULL_REQUEST);

    Columns.target(TtcTarget.AUTOR);
    assertTrue(Columns.names().contains("ln(espera realizada)"));

    Columns.target(TtcTarget.ESPERA);
    assertFalse(Columns.names().contains("ln(espera realizada)"),
        "a espera realizada E o desfecho ESPERA: ajustada ali ela tomou coeficiente 1,49 e zerou "
            + "todo o resto, com correlacao de posto 1,000");
    assertEquals(0, Columns.indicesOf(Columns.Group.EXAME).length);

    Columns.target(TtcTarget.TOTAL);
    assertFalse(Columns.names().contains("ln(espera realizada)"),
        "o total e a soma das duas parcelas, entao uma parcela dele nao e preditor dele");
    assertEquals(0, Columns.indicesOf(Columns.Group.EXAME).length);
  }

  @Test
  @DisplayName("as colunas da fronteira do exame ficam num grupo proprio, nunca no do desenvolvedor")
  void examColumnsAreTheirOwnGroup() {
    Columns.use(Columns.Profile.PULL_REQUEST);
    Columns.target(TtcTarget.AUTOR);

    final List<String> names = Columns.names();
    for (final int index : Columns.indicesOf(Columns.Group.EXAME)) {
      assertTrue(names.get(index).contains("revisao") || names.get(index).contains("exame")
              || names.get(index).contains("examinou") || names.get(index).contains("espera"),
          names.get(index));
    }
    for (final int index : Columns.indicesOf(Columns.Group.DEV)) {
      assertTrue(!names.get(index).contains("examinou"),
          "o volume de quem examina nao e historia de quem escreveu: misturar os dois faria a "
              + "ablacao 'so o desenvolvedor' creditar ao autor o que e do revisor");
    }
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
