package games.strategy.triplea.delegate;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.delegate.air.BasedMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;

/**
 * Request 65 tests: the installed Flames and Steel map with Vichy France added in memory, the way
 * the cloud session is asked to add it (a map that already has Vichy is used as it is).
 */
final class VichyMap {
  static final List<String> TERRITORIES =
      List.of(
          "Morocco",
          "Oran",
          "Algiers",
          "Constantine",
          "Tunisia",
          "Algerian Sahara",
          "Dakar",
          "Mauritania",
          "French Guinea",
          "French Sudan",
          "Ivory Coast",
          "Dahomey",
          "Air",
          "Damascus",
          "Lebanon",
          "Aleppo",
          "Tonkin",
          "Annam",
          "Cambodia",
          "Cochinchina",
          "French Somaliland",
          "Tananarive",
          "French Guiana",
          "French Antilles");
  static final List<String> NATIONS =
      List.of(
          "Germans",
          "Russians",
          "Japanese",
          "British",
          "Italians",
          "Chinese",
          "AmericansPacific",
          "Americans",
          "Dutch");

  private VichyMap() {}

  static GameData load() {
    final Path xmlPath = BasedMap.XML;
    Assumptions.assumeTrue(Files.exists(xmlPath), "Flames and Steel map not installed");
    try {
      final String xml = Files.readString(xmlPath, StandardCharsets.UTF_8);
      if (xml.contains("<player name=\"Vichy\"")) {
        return BasedMap.load();
      }
      final Path copy = Files.createTempFile("fs-vichy-", ".xml");
      copy.toFile().deleteOnExit();
      Files.writeString(copy, withVichy(xml), StandardCharsets.UTF_8);
      final Path dtdCopy = copy.resolveSibling("game.dtd");
      if (!Files.exists(dtdCopy)) {
        Files.copy(xmlPath.resolveSibling("game.dtd"), dtdCopy);
      }
      final GameData data = GameParser.parse(copy, false).orElseThrow();
      data.setMapName("flames_and_steel");
      data.setGameName("Flames and Steel");
      return data;
    } catch (final IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static String withVichy(final String original) {
    String xml = original;
    xml =
        once(
            xml,
            "    <player name=\"Dutch\" optional=\"false\"/>\n",
            "    <player name=\"Dutch\" optional=\"false\"/>\n"
                + "    <player name=\"Vichy\" optional=\"false\"/>\n");
    xml =
        once(
            xml,
            "    <alliance player=\"Dutch\" alliance=\"Dutch\"/>\n",
            "    <alliance player=\"Dutch\" alliance=\"Dutch\"/>\n"
                + "    <alliance player=\"Vichy\" alliance=\"Vichy\"/>\n");
    final StringBuilder relations = new StringBuilder("    <relationshipInitialize>\n");
    for (final String nation : NATIONS) {
      relations
          .append("      <relationship type=\"War\" player1=\"")
          .append(nation)
          .append("\" player2=\"Vichy\" roundValue=\"1\"/>\n");
    }
    xml = once(xml, "    <relationshipInitialize>\n", relations.toString());
    final StringBuilder owners = new StringBuilder("    <ownerInitialize>\n");
    for (final String t : TERRITORIES) {
      owners
          .append("      <territoryOwner territory=\"")
          .append(t)
          .append("\" owner=\"Vichy\"/>\n");
      final Matcher m =
          Pattern.compile("(<unitPlacement [^>]*territory=\"" + Pattern.quote(t) + "\"[^>]*?)/>")
              .matcher(xml);
      final StringBuilder sb = new StringBuilder();
      while (m.find()) {
        final String placement = m.group(1);
        m.appendReplacement(
            sb,
            Matcher.quoteReplacement(
                placement.contains("owner=") ? m.group() : placement + " owner=\"Vichy\"/>"));
      }
      m.appendTail(sb);
      xml = sb.toString();
    }
    xml = once(xml, "    <ownerInitialize>\n", owners.toString());
    xml =
        once(
            xml,
            "      <step name=\"airPlanesHome\"",
            "      <step name=\"vichyEndTurn\" delegate=\"endTurn\" player=\"Vichy\"/>\n"
                + "      <step name=\"airPlanesHome\"");
    xml =
        once(
            xml,
            "    <property name=\"FS Stats Sides\"",
            "    <property name=\"FS Neutral-Like Players\" value=\"Vichy\" editable=\"false\">\n"
                + "      <string/>\n"
                + "    </property>\n"
                + "    <property name=\"FS Income Share Vichy\""
                + " value=\"Germans:50:REST;Japanese:50:Tonkin:Annam:Cambodia:Cochinchina\""
                + " editable=\"false\">\n"
                + "      <string/>\n"
                + "    </property>\n"
                + "    <property name=\"FS Display Name Vichy\" value=\"Vichy France\""
                + " editable=\"false\">\n"
                + "      <string/>\n"
                + "    </property>\n"
                + "    <property name=\"FS Stats Sides\"");
    return xml;
  }

  private static String once(final String xml, final String find, final String replace) {
    final int at = xml.indexOf(find);
    if (at < 0 || xml.indexOf(find, at + 1) >= 0) {
      throw new IllegalStateException("not exactly once: " + find);
    }
    return xml.substring(0, at) + replace + xml.substring(at + find.length());
  }
}
