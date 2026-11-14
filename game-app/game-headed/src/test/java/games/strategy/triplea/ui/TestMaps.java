package games.strategy.triplea.ui;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.gameparser.GameParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;

/**
 * Request 38, for the screen tests: the installed Flames and Steel map with aircraft on the map. A
 * draft that still has air boxes is changed in memory the way the next draft will be (the same as
 * the engine tests' BasedMap); a draft without boxes is used as it is.
 */
public final class TestMaps {
  public static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");

  private TestMaps() {}

  public static GameData load() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    try {
      final String xml = Files.readString(XML, StandardCharsets.UTF_8);
      if (!xml.contains("airZoneAttachment")) {
        return GameParser.parse(XML, false).orElseThrow();
      }
      final Path copy = Files.createTempFile("fs-based-", ".xml");
      copy.toFile().deleteOnExit();
      Files.writeString(copy, withoutBoxes(xml), StandardCharsets.UTF_8);
      final Path dtd = XML.resolveSibling("game.dtd");
      final Path dtdCopy = copy.resolveSibling("game.dtd");
      if (Files.exists(dtd) && !Files.exists(dtdCopy)) {
        Files.copy(dtd, dtdCopy);
      }
      final GameData data = GameParser.parse(copy, false).orElseThrow();
      // the copy is outside the map folder: name the map as its map.yml does
      data.setMapName("flames_and_steel");
      data.setGameName("Flames and Steel");
      return data;
    } catch (final IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static String withoutBoxes(final String xml) {
    String s = xml;
    s = remove(s, "<attachment name=\"airZoneAttachment\"[^>]*>.*?</attachment>");
    s = remove(s, "<attachment [^>]*attachTo=\"Air - [^\"]*\"[^>]*>.*?</attachment>");
    // one old box stays, as the hidden airborne territory: the map files can draw it
    s = remove(s, "<territory name=\"Air - (?!Western Europe\")[^\"]*\"[^>]*/>");
    s = remove(s, "<connection t1=\"[^\"]*\" t2=\"[^\"]*\"\\s*/>", "Air - ");
    s = remove(s, "<unitPlacement [^>]*territory=\"Air - [^\"]*\"[^>]*/>");
    s = remove(s, "<territoryOwner territory=\"Air - [^\"]*\"[^>]*/>");
    s = remove(s, "<step name=\"airCarrier(Snapshot|Check)\"[^>]*/>");
    s = remove(s, "<delegate name=\"airCarrierCheck\"[^>]*/>");
    s = remove(s, "<delegate name=\"airAllocation\"[^>]*/>");
    s = remove(s, "<step name=\"[^\"]*\" delegate=\"airAllocation\"[^>]*/>");
    s = remove(s, "<property name=\"FS Long Range Raid[^\"]*\"[^>]*/>");
    s = remove(s, "<property name=\"FS Long Range Raid[^\"]*\"[^>]*[^/]>.*?</property>");
    s =
        s.replaceFirst(
            "<propertyList>",
            "<propertyList>\n    <property name=\"FS Airborne Territory\""
                + " value=\"Air - Western Europe\" editable=\"false\"><string/></property>");
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
        .replaceAll(
            m ->
                m.group().contains(marker)
                    ? ""
                    : java.util.regex.Matcher.quoteReplacement(m.group()));
  }
}
