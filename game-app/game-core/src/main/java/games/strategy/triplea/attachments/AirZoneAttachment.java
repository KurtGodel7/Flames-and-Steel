package games.strategy.triplea.attachments;

import games.strategy.engine.data.Attachable;
import games.strategy.engine.data.DefaultAttachment;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MutableProperty;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.gameparser.GameParseException;
import java.io.Serial;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.Getter;
import org.jetbrains.annotations.NonNls;

/**
 * Flames and Steel: marks a territory as an abstract air zone (a box on the map). Aircraft live
 * only in air zones; they fight in the air phase at the start of each round and never in ordinary
 * battles. Note: Empty collection fields default to null to minimize memory use and serialization
 * size.
 */
public class AirZoneAttachment extends DefaultAttachment {
  @Serial private static final long serialVersionUID = 6157429381045236110L;
  public static final String ATTACHMENT_NAME = "airZoneAttachment";

  /** The kinds of air zone. */
  public enum ZoneType {
    /** New and resting aircraft; they do not fight. */
    RESERVE,
    /** Strategic bombing of one nation ("Bomb Germany"). */
    BOMB,
    /** Coastal sea zones; land-based aircraft allowed. */
    COASTAL,
    /** Open ocean; limited to the nation's own carrier capacity. */
    OCEAN,
    /** A land front ("Germany vs. USSR"). */
    LAND
  }

  private @Nullable ZoneType zoneType = null;
  // nations that may put aircraft here; empty means any nation
  private @Nullable List<GamePlayer> players = null;
  // BOMB zones: the nation being bombed
  @Getter private @Nullable GamePlayer bombedPlayer = null;
  // land territories and sea zones this zone covers (where its targets are)
  private @Nullable Set<Territory> coveredTerritories = null;
  // air zones aircraft may move to directly, without a round in reserve
  private @Nullable Set<Territory> adjacentZones = null;

  public AirZoneAttachment(
      final String name, final Attachable attachable, final GameData gameData) {
    super(name, attachable, gameData);
  }

  public static Optional<AirZoneAttachment> get(final Territory t) {
    return Optional.ofNullable((AirZoneAttachment) t.getAttachment(ATTACHMENT_NAME));
  }

  public static boolean isAirZone(final Territory t) {
    return get(t).isPresent();
  }

  /** The aircraft reserve zone, if the map has one. */
  public static Optional<Territory> getReserve(final GameMap map) {
    return map.getTerritories().stream()
        .filter(t -> get(t).map(a -> a.getZoneType() == ZoneType.RESERVE).orElse(false))
        .findFirst();
  }

  public static List<Territory> getAirZones(final GameMap map) {
    return map.getTerritories().stream().filter(AirZoneAttachment::isAirZone).toList();
  }

  private void setZoneType(final String value) throws GameParseException {
    try {
      zoneType = ZoneType.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException e) {
      throw new GameParseException(
          "zoneType must be reserve, bomb, coastal, ocean or land" + thisErrorMsg());
    }
  }

  private void setZoneType(final ZoneType value) {
    zoneType = value;
  }

  public ZoneType getZoneType() {
    return zoneType == null ? ZoneType.LAND : zoneType;
  }

  private void resetZoneType() {
    zoneType = null;
  }

  private void setPlayers(final String value) throws GameParseException {
    players = parsePlayerList(value, players);
  }

  private void setPlayers(final List<GamePlayer> value) {
    players = value;
  }

  /** Nations named by this zone; empty means any nation may enter. */
  public List<GamePlayer> getPlayers() {
    return getListProperty(players);
  }

  private void resetPlayers() {
    players = null;
  }

  private void setBombedPlayer(final String value) throws GameParseException {
    bombedPlayer =
        getPlayerByName(value)
            .orElseThrow(
                () -> new GameParseException("bombedPlayer: no player " + value + thisErrorMsg()));
  }

  private void setBombedPlayer(final GamePlayer value) {
    bombedPlayer = value;
  }

  private void resetBombedPlayer() {
    bombedPlayer = null;
  }

  private void setCoveredTerritories(final String value) throws GameParseException {
    coveredTerritories = parseTerritories(value, coveredTerritories, "coveredTerritories");
  }

  private void setCoveredTerritories(final Set<Territory> value) {
    coveredTerritories = value;
  }

  public Set<Territory> getCoveredTerritories() {
    return getSetProperty(coveredTerritories);
  }

  private void resetCoveredTerritories() {
    coveredTerritories = null;
  }

  private void setAdjacentZones(final String value) throws GameParseException {
    adjacentZones = parseTerritories(value, adjacentZones, "adjacentZones");
  }

  private void setAdjacentZones(final Set<Territory> value) {
    adjacentZones = value;
  }

  public Set<Territory> getAdjacentZones() {
    return getSetProperty(adjacentZones);
  }

  private void resetAdjacentZones() {
    adjacentZones = null;
  }

  private Set<Territory> parseTerritories(
      final String value, final @Nullable Set<Territory> existing, final String option)
      throws GameParseException {
    final Set<Territory> result = existing == null ? new LinkedHashSet<>() : existing;
    for (final String territoryName : splitOnColon(value)) {
      result.add(
          getTerritory(territoryName)
              .orElseThrow(
                  () ->
                      new GameParseException(
                          option + ": no territory " + territoryName + thisErrorMsg())));
    }
    return result;
  }

  @Override
  public void validate(final GameState data) throws GameParseException {
    if (zoneType == null) {
      throw new GameParseException("air zone needs a zoneType" + thisErrorMsg());
    }
    if (zoneType == ZoneType.BOMB && bombedPlayer == null) {
      throw new GameParseException("bomb air zone needs a bombedPlayer" + thisErrorMsg());
    }
    final Territory self = (Territory) getAttachedTo();
    for (final Territory other : getAdjacentZones()) {
      final Optional<AirZoneAttachment> otherZone = get(other);
      if (otherZone.isEmpty()) {
        throw new GameParseException(
            "adjacentZones: " + other.getName() + " is not an air zone" + thisErrorMsg());
      }
      if (!otherZone.get().getAdjacentZones().contains(self)) {
        throw new GameParseException(
            "adjacentZones must go both ways: "
                + other.getName()
                + " does not list "
                + self.getName()
                + thisErrorMsg());
      }
    }
    for (final Territory covered : getCoveredTerritories()) {
      if (isAirZone(covered)) {
        throw new GameParseException(
            "coveredTerritories: " + covered.getName() + " is an air zone" + thisErrorMsg());
      }
    }
  }

  @Override
  public Optional<MutableProperty<?>> getPropertyOrEmpty(final @NonNls String propertyName) {
    return switch (propertyName) {
      case "zoneType" ->
          Optional.of(
              MutableProperty.of(
                  this::setZoneType, this::setZoneType, this::getZoneType, this::resetZoneType));
      case "players" ->
          Optional.of(
              MutableProperty.of(
                  this::setPlayers, this::setPlayers, this::getPlayers, this::resetPlayers));
      case "bombedPlayer" ->
          Optional.of(
              MutableProperty.of(
                  this::setBombedPlayer,
                  this::setBombedPlayer,
                  this::getBombedPlayer,
                  this::resetBombedPlayer));
      case "coveredTerritories" ->
          Optional.of(
              MutableProperty.of(
                  this::setCoveredTerritories,
                  this::setCoveredTerritories,
                  this::getCoveredTerritories,
                  this::resetCoveredTerritories));
      case "adjacentZones" ->
          Optional.of(
              MutableProperty.of(
                  this::setAdjacentZones,
                  this::setAdjacentZones,
                  this::getAdjacentZones,
                  this::resetAdjacentZones));
      default -> Optional.empty();
    };
  }
}
