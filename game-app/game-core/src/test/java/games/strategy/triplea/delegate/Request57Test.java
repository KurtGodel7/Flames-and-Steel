package games.strategy.triplea.delegate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.delegate.air.AirValues;
import games.strategy.triplea.delegate.air.BasedMap;
import games.strategy.triplea.delegate.apc.Apcs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Request 57 on the installed map: the build screen's SPC allowance and its reason, and air stat
 * lines kept to two whole lines for every aircraft type and nation.
 */
class Request57Test {
  private GameData data;
  private GamePlayer british;

  @BeforeEach
  void setUp() {
    data = BasedMap.load();
    british = BasedMap.p(data, "British");
  }

  private void grant(final GamePlayer player, final String tech) {
    final TechAdvance advance = data.getTechnologyFrontier().getAdvanceByName(tech);
    data.performChange(
        advance instanceof GenericTechAdvance g && g.getAdvance() == null
            ? ChangeFactory.genericTechChange(
                player.getTechAttachment(), true, advance.getProperty())
            : ChangeFactory.attachmentPropertyChange(
                player.getTechAttachment(), "true", advance.getProperty()));
    data.getTechTracker().clearCache();
  }

  @Test
  void spcAllowanceAndReason() {
    Apcs.Allowance a = Apcs.allowance(british, data.getMap());
    assertThat(a.allowed(), is(0));
    assertThat(a.reason(), is("needs Advanced 1 research"));

    grant(british, "Advanced1");
    a = Apcs.allowance(british, data.getMap());
    assertThat(a.allowed(), is(1));
    assertThat(a.reason(), is(""));

    // one bought, not yet placed
    british
        .getUnitCollection()
        .addAll(data.getUnitTypeList().getUnitTypeOrThrow("apc").create(1, british));
    a = Apcs.allowance(british, data.getMap());
    assertThat(a.allowed(), is(0));
    assertThat(a.reason(), is("limit reached (1 of 1)"));
    assertThat(Apcs.allowanceRule(a), containsString("national limit of " + a.nationalCap()));
  }

  @Test
  void nationalLimitReachedWhenTechsExceedIt() {
    final int cap = games.strategy.triplea.attachments.PlayerAttachment.get(british).getApcLimit();
    for (int i = 1; i <= 4; i++) {
      grant(british, "Advanced" + i);
    }
    british
        .getUnitCollection()
        .addAll(data.getUnitTypeList().getUnitTypeOrThrow("apc").create(cap, british));
    final Apcs.Allowance a = Apcs.allowance(british, data.getMap());
    assertThat(a.allowed(), is(0));
    assertThat(
        a.reason(),
        is(cap < 4 ? "national limit reached (" + cap + ")" : "limit reached (4 of 4)"));
  }

  @Test
  void everyAirStatLineIsTwoWholeLines() {
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      for (final UnitType type : data.getUnitTypeList().getAllUnitTypes()) {
        if (!AirValues.applies(type)) {
          continue;
        }
        final String html = AirValues.statLineHtml(type, player);
        final String body = html.replace("<html>", "").replace("</html>", "");
        assertThat(type.getName(), body.split("<br>").length, is(2));
        assertThat(type.getName(), body, not(containsString(" ")));
      }
    }
  }
}
