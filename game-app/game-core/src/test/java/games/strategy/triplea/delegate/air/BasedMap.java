package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.gameparser.GameParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;

/**
 * Request 38 tests: the installed Flames and Steel map with aircraft on the map. Until the cloud
 * session's draft without boxes arrives, the installed draft is changed in memory the way that
 * draft will be: box territories, their attachments, connections and aircraft removed, one hidden
 * "Air - Airborne" territory, the carrier-check steps replaced by "airPlanesHome", the raid
 * properties gone. A draft that has no boxes is used as it is.
 */
public final class BasedMap {
  public static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");

  private BasedMap() {}

  public static GameData load() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    try {
      final String xml = Files.readString(XML, StandardCharsets.UTF_8);
      if (!xml.contains("airZoneAttachment")) {
        return GameParser.parse(XML, false).orElseThrow();
      }
      final Path copy = Files.createTempFile("fs-based-", ".xml");
      copy.toFile().deleteOnExit();
      // the DTD sits beside the map's XML
      Files.writeString(copy, withoutBoxes(xml), StandardCharsets.UTF_8);
      final Path dtd = XML.resolveSibling("game.dtd");
      final Path dtdCopy = copy.resolveSibling("game.dtd");
      if (Files.exists(dtd) && !Files.exists(dtdCopy)) {
        Files.copy(dtd, dtdCopy);
      }
      return GameParser.parse(copy, false).orElseThrow();
    } catch (final IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static String withoutBoxes(final String xml) {
    String s = xml;
    s = remove(s, "<attachment name=\"airZoneAttachment\"[^>]*>.*?</attachment>");
    s = remove(s, "<attachment [^>]*attachTo=\"Air - [^\"]*\"[^>]*>.*?</attachment>");
    s = remove(s, "<territory name=\"Air - [^\"]*\"[^>]*/>");
    s = remove(s, "<connection t1=\"[^\"]*\" t2=\"[^\"]*\"\\s*/>", "Air - ");
    s = remove(s, "<unitPlacement [^>]*territory=\"Air - [^\"]*\"[^>]*/>");
    s = remove(s, "<territoryOwner territory=\"Air - [^\"]*\"[^>]*/>");
    s = remove(s, "<step name=\"airCarrier(Snapshot|Check)\"[^>]*/>");
    s = remove(s, "<delegate name=\"airCarrierCheck\"[^>]*/>");
    s = remove(s, "<delegate name=\"airAllocation\"[^>]*/>");
    s = remove(s, "<step name=\"[^\"]*\" delegate=\"airAllocation\"[^>]*/>");
    s = remove(s, "<property name=\"FS Long Range Raid[^\"]*\"[^>]*/>");
    s = remove(s, "<property name=\"FS Long Range Raid[^\"]*\"[^>]*[^/]>.*?</property>");
    s = s.replaceFirst("<map>", "<map>\n    <territory name=\"Air - Airborne\" water=\"true\"/>");
    s =
        s.replaceFirst(
            "(\\s*)<step name=\"endRoundStep\"",
            "$1<step name=\"airPlanesHome\" delegate=\"airStage\" display=\"Planes Go Home\"/>"
                + "$1<step name=\"endRoundStep\"");
    return s;
  }

  private static String remove(final String s, final String regex) {
    return Pattern.compile(regex, Pattern.DOTALL).matcher(s).replaceAll("");
  }

  /** Removes the matches that contain the marker. */
  private static String remove(final String s, final String regex, final String marker) {
    return Pattern.compile(regex)
        .matcher(s)
        .replaceAll(m -> m.group().contains(marker) ? "" : java.util.regex.Matcher.quoteReplacement(m.group()));
  }

  // ---- small helpers for scenarios

  public static Territory t(final GameData data, final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  public static GamePlayer p(final GameData data, final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  /** Every land territory (not the airborne one) owned by the nation; for clean test scenarios. */
  public static void allLandTo(final GameData data, final GamePlayer owner) {
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater() && !AirBases.isAirborneTerritory(t)) {
        t.setOwner(owner);
      }
    }
  }

  /** Removes every unit of these types owned by the nation (e.g. its carriers). */
  public static void clear(final GameData data, final GamePlayer owner, final String... types) {
    final List<String> names = List.of(types);
    for (final Territory t : data.getMap().getTerritories()) {
      final List<Unit> gone =
          t.getUnitCollection().getMatches(
              u -> u.isOwnedBy(owner) && (names.isEmpty() || names.contains(u.getType().getName())));
      t.getUnitCollection().removeAll(gone);
    }
  }

  /** Puts the units in the air over the territory (as a stage reveal does), with range left. */
  public static void fly(final GameData data, final Territory over, final List<Unit> units) {
    final Territory airborne = AirBases.airborne(data).orElseThrow();
    for (final Territory t : data.getMap().getTerritories()) {
      final List<Unit> here = units.stream().filter(u -> t.getUnitCollection().contains(u)).toList();
      if (!here.isEmpty() && !t.equals(airborne)) {
        data.performChange(
            games.strategy.engine.data.changefactory.ChangeFactory.moveUnits(t, airborne, here));
      }
    }
    final List<Unit> fresh =
        units.stream().filter(u -> !airborne.getUnitCollection().contains(u)).toList();
    airborne.getUnitCollection().addAll(fresh);
    for (final Unit u : units) {
      data.performChange(
          games.strategy.engine.data.changefactory.ChangeFactory.unitPropertyChange(
              u, over, Unit.PropertyName.FLIGHT_OVER));
    }
  }

  /** Puts new units on the map. */
  public static List<Unit> add(
      final GameData data, final Territory t, final GamePlayer owner, final String type, final int n) {
    final List<Unit> units = data.getUnitTypeList().getUnitTypeOrThrow(type).create(n, owner);
    t.getUnitCollection().addAll(units);
    return units;
  }
}
