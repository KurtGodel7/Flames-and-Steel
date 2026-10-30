package games.strategy.triplea.attachments;

import games.strategy.engine.data.Attachable;
import games.strategy.engine.data.DefaultAttachment;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MutableProperty;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.gameparser.GameParseException;
import java.io.Serial;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.Getter;
import org.jetbrains.annotations.NonNls;
import org.triplea.java.collections.IntegerMap;
import org.triplea.util.Triple;

/**
 * An attachment for instances of {@link GamePlayer} that defines properties unrelated to rules (see
 * the class description of {@link AbstractPlayerRulesAttachment}). Note: Empty collection fields
 * default to null to minimize memory use and serialization size.
 */
public class PlayerAttachment extends DefaultAttachment {
  @Serial private static final long serialVersionUID = 1880755875866426270L;
  public static final String LIBERATED_TO_ORIGINAL_OWNER = "originalOwner";
  public static final String LIBERATED_TO_LIBERATOR = "liberator";

  @Getter private int vps = 0;
  // need to store some data during a turn
  @Getter private int captureVps = 0;
  // number of capitals needed before we lose all our money
  @Getter private int retainCapitalNumber = 1;
  // number of capitals needed before we lose ability to gain money and produce units
  private int retainCapitalProduceNumber = 1;
  private @Nullable List<GamePlayer> giveUnitControl = null;
  private boolean giveUnitControlInAllTerritories = false;
  private @Nullable List<GamePlayer> captureUnitOnEnteringBy = null;
  // gives any technology researched to this player automatically
  private @Nullable List<GamePlayer> shareTechnology = null;
  // allows these players to help pay for technology
  private @Nullable List<GamePlayer> helpPayTechCost = null;
  // do we lose our money and have it disappear or is that money captured?
  private boolean destroysPus = false;
  // are we immune to being blockaded?
  private boolean immuneToBlockade = false;
  // Flames and Steel: who receives our territory when an ally liberates it from an enemy.
  // null keeps the engine default (back to us only while we hold a capital).
  private @Nullable String liberatedTerritoryGoesTo = null;
  // Flames and Steel: at the end of our turn, allied units in our land territories are removed
  // and we receive their purchase cost in PUs.
  private boolean sellsAlliedUnitsAtEndTurn = false;
  // Flames and Steel: munitions on our land become our PUs at the end of our turn
  private boolean convertsMunitions = false;
  // PUs paid per unit of each non-PU resource in a sold unit's purchase cost (e.g. Manpower: 2)
  private @Nullable IntegerMap<Resource> alliedUnitSaleResourceValue = null;
  // Flames and Steel APCs: national cap on APCs (the Advanced techs also limit them)
  @Getter private int apcLimit = 0;
  // what each APC builds (APC unit id -> unit type name), and turns of progress towards it
  private @Nullable Map<String, String> apcProduction = null;
  private @Nullable IntegerMap<String> apcProgress = null;
  // request 50: turns each APC still waits for a tech's free units ("<APC unit id>:<tech name>")
  private @Nullable IntegerMap<String> apcFreeUnitsWait = null;
  // Flames and Steel: units still unplaced from the nation's last turn (unit type name -> count),
  // recorded when its purchase step starts
  private @Nullable IntegerMap<String> carriedOverUnits = null;
  // what resources can be used for suicide attacks, and at what attack power
  private @Nullable IntegerMap<Resource> suicideAttackResources = null;
  // what can be hit by suicide attacks
  private @Nullable Set<UnitType> suicideAttackTargets = null;

  // placement limits on a flexible per player basis
  private @Nullable Set<Triple<Integer, String, Set<UnitType>>> placementLimit = null;

  // movement limits on a flexible per player basis
  private @Nullable Set<Triple<Integer, String, Set<UnitType>>> movementLimit = null;

  // attacking limits on a flexible per player basis
  private @Nullable Set<Triple<Integer, String, Set<UnitType>>> attackingLimit = null;

  public PlayerAttachment(final String name, final Attachable attachable, final GameData gameData) {
    super(name, attachable, gameData);
  }

  /** Convenience method. can be null */
  public static @Nullable PlayerAttachment get(final GamePlayer p) {
    // allow null
    return p.getPlayerAttachment();
  }

  static PlayerAttachment get(final GamePlayer p, final String nameOfAttachment) {
    final PlayerAttachment playerAttachment = p.getPlayerAttachment();
    if (playerAttachment == null) {
      throw new IllegalStateException(
          "No player attachment for: " + p.getName() + " with name: " + nameOfAttachment);
    }
    return playerAttachment;
  }

  private void setPlacementLimit(final String value) throws GameParseException {
    if (placementLimit == null) {
      placementLimit = new HashSet<>();
    }
    placementLimit.add(parseUnitLimit("placementLimit", value));
  }

  private void setPlacementLimit(final Set<Triple<Integer, String, Set<UnitType>>> value) {
    placementLimit = value;
  }

  public Set<Triple<Integer, String, Set<UnitType>>> getPlacementLimit() {
    return getSetProperty(placementLimit);
  }

  private void resetPlacementLimit() {
    placementLimit = null;
  }

  private void setMovementLimit(final String value) throws GameParseException {
    if (movementLimit == null) {
      movementLimit = new HashSet<>();
    }
    movementLimit.add(parseUnitLimit("movementLimit", value));
  }

  private void setMovementLimit(final Set<Triple<Integer, String, Set<UnitType>>> value) {
    movementLimit = value;
  }

  public Set<Triple<Integer, String, Set<UnitType>>> getMovementLimit() {
    return getSetProperty(movementLimit);
  }

  private void resetMovementLimit() {
    movementLimit = null;
  }

  private void setAttackingLimit(final String value) throws GameParseException {
    if (attackingLimit == null) {
      attackingLimit = new HashSet<>();
    }
    attackingLimit.add(parseUnitLimit("attackingLimit", value));
  }

  private void setAttackingLimit(final Set<Triple<Integer, String, Set<UnitType>>> value) {
    attackingLimit = value;
  }

  public Set<Triple<Integer, String, Set<UnitType>>> getAttackingLimit() {
    return getSetProperty(attackingLimit);
  }

  private void resetAttackingLimit() {
    attackingLimit = null;
  }

  private Triple<Integer, String, Set<UnitType>> parseUnitLimit(
      final String type, final String value) throws GameParseException {
    final String[] s = splitOnColon(value);
    if (s.length < 3) {
      throw new GameParseException(
          type + " must have 3 parts: count, type, unit list" + thisErrorMsg());
    }
    final int max = getInt(s[0]);
    if (max < 0) {
      throw new GameParseException(type + " count must have a positive number" + thisErrorMsg());
    }
    if (!(s[1].equals("owned") || s[1].equals("allied") || s[1].equals("total"))) {
      throw new GameParseException(
          type + " type must be: owned, allied, or total" + thisErrorMsg());
    }
    final Set<UnitType> types = new HashSet<>();
    if (s[2].equalsIgnoreCase("all")) {
      types.addAll(getData().getUnitTypeList().getAllUnitTypes());
    } else {
      for (int i = 2; i < s.length; i++) {
        types.add(getUnitTypeOrThrow(s[i]));
      }
    }
    return Triple.of(max, s[1].intern(), types);
  }

  private void setSuicideAttackTargets(final String value) throws GameParseException {
    suicideAttackTargets = parseUnitTypes("suicideAttackTargets", value, suicideAttackTargets);
  }

  private void setSuicideAttackTargets(final Set<UnitType> value) {
    suicideAttackTargets = value;
  }

  public Set<UnitType> getSuicideAttackTargets() {
    return getSetProperty(suicideAttackTargets);
  }

  private void resetSuicideAttackTargets() {
    suicideAttackTargets = null;
  }

  private void setSuicideAttackResources(final String value) throws GameParseException {
    final String[] s = splitOnColon(value);
    if (s.length != 2) {
      throw new GameParseException(
          "suicideAttackResources must have exactly 2 fields" + thisErrorMsg());
    }
    final int attackValue = getInt(s[0]);
    if (attackValue < 0) {
      throw new GameParseException(
          "suicideAttackResources attack value must be positive" + thisErrorMsg());
    }
    final Resource r = getResourceOrThrowGameParseException(s[1]);
    if (suicideAttackResources == null) {
      suicideAttackResources = new IntegerMap<>();
    }
    suicideAttackResources.put(r, attackValue);
  }

  private void setSuicideAttackResources(final IntegerMap<Resource> value) {
    suicideAttackResources = value;
  }

  public IntegerMap<Resource> getSuicideAttackResources() {
    return getIntegerMapProperty(suicideAttackResources);
  }

  private void resetSuicideAttackResources() {
    suicideAttackResources = null;
  }

  private void setVps(final int value) {
    vps = value;
  }

  private void setCaptureVps(final String value) {
    captureVps = getInt(value);
  }

  private void setCaptureVps(final Integer value) {
    captureVps = value;
  }

  private void resetCaptureVps() {
    captureVps = 0;
  }

  private void setRetainCapitalNumber(final String value) {
    retainCapitalNumber = getInt(value);
  }

  private void setRetainCapitalNumber(final Integer value) {
    retainCapitalNumber = value;
  }

  private void resetRetainCapitalNumber() {
    retainCapitalNumber = 1;
  }

  private void setRetainCapitalProduceNumber(final String value) {
    retainCapitalProduceNumber = getInt(value);
  }

  private void setRetainCapitalProduceNumber(final Integer value) {
    retainCapitalProduceNumber = value;
  }

  int getRetainCapitalProduceNumber() {
    return retainCapitalProduceNumber;
  }

  private void resetRetainCapitalProduceNumber() {
    retainCapitalProduceNumber = 1;
  }

  private void setGiveUnitControl(final String value) throws GameParseException {
    giveUnitControl = parsePlayerList(value, giveUnitControl);
  }

  private void setGiveUnitControl(final List<GamePlayer> value) {
    giveUnitControl = value;
  }

  public List<GamePlayer> getGiveUnitControl() {
    return getListProperty(giveUnitControl);
  }

  private void resetGiveUnitControl() {
    giveUnitControl = null;
  }

  private void setGiveUnitControlInAllTerritories(final boolean value) {
    giveUnitControlInAllTerritories = value;
  }

  public boolean getGiveUnitControlInAllTerritories() {
    return giveUnitControlInAllTerritories;
  }

  private void setCaptureUnitOnEnteringBy(final String value) throws GameParseException {
    captureUnitOnEnteringBy = parsePlayerList(value, captureUnitOnEnteringBy);
  }

  private void setCaptureUnitOnEnteringBy(final List<GamePlayer> value) {
    captureUnitOnEnteringBy = value;
  }

  public List<GamePlayer> getCaptureUnitOnEnteringBy() {
    return getListProperty(captureUnitOnEnteringBy);
  }

  private void resetCaptureUnitOnEnteringBy() {
    captureUnitOnEnteringBy = null;
  }

  private void setShareTechnology(final String value) throws GameParseException {
    shareTechnology = parsePlayerList(value, shareTechnology);
  }

  private void setShareTechnology(final List<GamePlayer> value) {
    shareTechnology = value;
  }

  public List<GamePlayer> getShareTechnology() {
    return getListProperty(shareTechnology);
  }

  private void resetShareTechnology() {
    shareTechnology = null;
  }

  private void setHelpPayTechCost(final String value) throws GameParseException {
    helpPayTechCost = parsePlayerList(value, helpPayTechCost);
  }

  private void setHelpPayTechCost(final List<GamePlayer> value) {
    helpPayTechCost = value;
  }

  public List<GamePlayer> getHelpPayTechCost() {
    return getListProperty(helpPayTechCost);
  }

  private void resetHelpPayTechCost() {
    helpPayTechCost = null;
  }

  private void setDestroysPUs(final String value) {
    destroysPus = getBool(value);
  }

  private void setDestroysPUs(final Boolean value) {
    destroysPus = value;
  }

  public boolean getDestroysPUs() {
    return destroysPus;
  }

  private void resetDestroysPUs() {
    destroysPus = false;
  }

  private void setImmuneToBlockade(final String value) {
    immuneToBlockade = getBool(value);
  }

  private void setImmuneToBlockade(final Boolean value) {
    immuneToBlockade = value;
  }

  public boolean getImmuneToBlockade() {
    return immuneToBlockade;
  }

  private void resetImmuneToBlockade() {
    immuneToBlockade = false;
  }

  private void setLiberatedTerritoryGoesTo(final String value) throws GameParseException {
    if (!value.equals(LIBERATED_TO_ORIGINAL_OWNER) && !value.equals(LIBERATED_TO_LIBERATOR)) {
      throw new GameParseException(
          "liberatedTerritoryGoesTo must be "
              + LIBERATED_TO_ORIGINAL_OWNER
              + " or "
              + LIBERATED_TO_LIBERATOR
              + thisErrorMsg());
    }
    liberatedTerritoryGoesTo = value.intern();
  }

  private @Nullable String getLiberatedTerritoryGoesTo() {
    return liberatedTerritoryGoesTo;
  }

  private void resetLiberatedTerritoryGoesTo() {
    liberatedTerritoryGoesTo = null;
  }

  /** True if our territory always returns to us when an ally liberates it. */
  public boolean isLiberatedTerritoryToOriginalOwner() {
    return LIBERATED_TO_ORIGINAL_OWNER.equals(liberatedTerritoryGoesTo);
  }

  private void setConvertsMunitions(final String value) {
    convertsMunitions = getBool(value);
  }

  private void setConvertsMunitions(final Boolean value) {
    convertsMunitions = value;
  }

  public boolean getConvertsMunitions() {
    return convertsMunitions;
  }

  private void setSellsAlliedUnitsAtEndTurn(final String value) {
    sellsAlliedUnitsAtEndTurn = getBool(value);
  }

  private void setSellsAlliedUnitsAtEndTurn(final Boolean value) {
    sellsAlliedUnitsAtEndTurn = value;
  }

  public boolean getSellsAlliedUnitsAtEndTurn() {
    return sellsAlliedUnitsAtEndTurn;
  }

  private void resetSellsAlliedUnitsAtEndTurn() {
    sellsAlliedUnitsAtEndTurn = false;
  }

  private void setAlliedUnitSaleResourceValue(final String value) throws GameParseException {
    final String[] s = splitOnColon(value);
    if (s.length != 2) {
      throw new GameParseException(
          "alliedUnitSaleResourceValue must have exactly 2 fields" + thisErrorMsg());
    }
    final int puValue = getInt(s[0]);
    if (puValue < 0) {
      throw new GameParseException(
          "alliedUnitSaleResourceValue PU value must be positive" + thisErrorMsg());
    }
    final Resource r = getResourceOrThrowGameParseException(s[1]);
    if (alliedUnitSaleResourceValue == null) {
      alliedUnitSaleResourceValue = new IntegerMap<>();
    }
    alliedUnitSaleResourceValue.put(r, puValue);
  }

  private void setAlliedUnitSaleResourceValue(final IntegerMap<Resource> value) {
    alliedUnitSaleResourceValue = value;
  }

  public IntegerMap<Resource> getAlliedUnitSaleResourceValue() {
    return getIntegerMapProperty(alliedUnitSaleResourceValue);
  }

  private void resetAlliedUnitSaleResourceValue() {
    alliedUnitSaleResourceValue = null;
  }

  private void setApcLimit(final String value) {
    apcLimit = getInt(value);
  }

  private void setApcLimit(final Integer value) {
    apcLimit = value;
  }

  private void setApcProduction(final String value) throws GameParseException {
    throw new GameParseException("apcProduction is set during the game, not in the XML");
  }

  private void setApcProduction(final Map<String, String> value) {
    apcProduction = value;
  }

  /** What each APC builds: APC unit id to unit type name. */
  public Map<String, String> getApcProduction() {
    return apcProduction == null ? Map.of() : Collections.unmodifiableMap(apcProduction);
  }

  private void setApcProgress(final String value) throws GameParseException {
    throw new GameParseException("apcProgress is set during the game, not in the XML");
  }

  private void setApcProgress(final IntegerMap<String> value) {
    apcProgress = value;
  }

  /** Turns of progress of each APC (by unit id) towards its next slow-built unit. */
  public IntegerMap<String> getApcProgress() {
    return getIntegerMapProperty(apcProgress);
  }

  private void setApcFreeUnitsWait(final String value) throws GameParseException {
    throw new GameParseException("apcFreeUnitsWait is set during the game, not in the XML");
  }

  private void setApcFreeUnitsWait(final IntegerMap<String> value) {
    apcFreeUnitsWait = value;
  }

  /** Turns each APC still waits for a tech's free units, by "APC unit id:tech name". */
  public IntegerMap<String> getApcFreeUnitsWait() {
    return getIntegerMapProperty(apcFreeUnitsWait);
  }

  private void setCarriedOverUnits(final String value) throws GameParseException {
    throw new GameParseException("carriedOverUnits is set during the game, not in the XML");
  }

  private void setCarriedOverUnits(final IntegerMap<String> value) {
    carriedOverUnits = value;
  }

  /** Units (by type name) the nation had left unplaced when this turn's purchase began. */
  public IntegerMap<String> getCarriedOverUnits() {
    return getIntegerMapProperty(carriedOverUnits);
  }

  /** True if our territory is kept by whichever ally liberates it. */
  public boolean isLiberatedTerritoryToLiberator() {
    return LIBERATED_TO_LIBERATOR.equals(liberatedTerritoryGoesTo);
  }

  @Override
  public void validate(final GameState data) {}

  @Override
  public Optional<MutableProperty<?>> getPropertyOrEmpty(final @NonNls String propertyName) {
    return switch (propertyName) {
      case "vps" ->
          Optional.of(
              MutableProperty.ofMapper(
                  DefaultAttachment::getInt, this::setVps, this::getVps, () -> 0));
      case "captureVps" ->
          Optional.of(
              MutableProperty.of(
                  this::setCaptureVps,
                  this::setCaptureVps,
                  this::getCaptureVps,
                  this::resetCaptureVps));
      case "retainCapitalNumber" ->
          Optional.of(
              MutableProperty.of(
                  this::setRetainCapitalNumber,
                  this::setRetainCapitalNumber,
                  this::getRetainCapitalNumber,
                  this::resetRetainCapitalNumber));
      case "retainCapitalProduceNumber" ->
          Optional.of(
              MutableProperty.of(
                  this::setRetainCapitalProduceNumber,
                  this::setRetainCapitalProduceNumber,
                  this::getRetainCapitalProduceNumber,
                  this::resetRetainCapitalProduceNumber));
      case "giveUnitControl" ->
          Optional.of(
              MutableProperty.of(
                  this::setGiveUnitControl,
                  this::setGiveUnitControl,
                  this::getGiveUnitControl,
                  this::resetGiveUnitControl));
      case "giveUnitControlInAllTerritories" ->
          Optional.of(
              MutableProperty.ofMapper(
                  DefaultAttachment::getBool,
                  this::setGiveUnitControlInAllTerritories,
                  this::getGiveUnitControlInAllTerritories,
                  () -> false));
      case "captureUnitOnEnteringBy" ->
          Optional.of(
              MutableProperty.of(
                  this::setCaptureUnitOnEnteringBy,
                  this::setCaptureUnitOnEnteringBy,
                  this::getCaptureUnitOnEnteringBy,
                  this::resetCaptureUnitOnEnteringBy));
      case "shareTechnology" ->
          Optional.of(
              MutableProperty.of(
                  this::setShareTechnology,
                  this::setShareTechnology,
                  this::getShareTechnology,
                  this::resetShareTechnology));
      case "helpPayTechCost" ->
          Optional.of(
              MutableProperty.of(
                  this::setHelpPayTechCost,
                  this::setHelpPayTechCost,
                  this::getHelpPayTechCost,
                  this::resetHelpPayTechCost));
      case "destroysPUs" ->
          Optional.of(
              MutableProperty.of(
                  this::setDestroysPUs,
                  this::setDestroysPUs,
                  this::getDestroysPUs,
                  this::resetDestroysPUs));
      case "immuneToBlockade" ->
          Optional.of(
              MutableProperty.of(
                  this::setImmuneToBlockade,
                  this::setImmuneToBlockade,
                  this::getImmuneToBlockade,
                  this::resetImmuneToBlockade));
      case "liberatedTerritoryGoesTo" ->
          Optional.of(
              MutableProperty.of(
                  this::setLiberatedTerritoryGoesTo,
                  this::setLiberatedTerritoryGoesTo,
                  this::getLiberatedTerritoryGoesTo,
                  this::resetLiberatedTerritoryGoesTo));
      case "apcLimit" ->
          Optional.of(
              MutableProperty.of(
                  this::setApcLimit, this::setApcLimit, this::getApcLimit, () -> apcLimit = 0));
      case "apcProduction" ->
          Optional.of(
              MutableProperty.of(
                  this::setApcProduction,
                  this::setApcProduction,
                  this::getApcProduction,
                  () -> apcProduction = null));
      case "apcProgress" ->
          Optional.of(
              MutableProperty.of(
                  this::setApcProgress,
                  this::setApcProgress,
                  this::getApcProgress,
                  () -> apcProgress = null));
      case "apcFreeUnitsWait" ->
          Optional.of(
              MutableProperty.of(
                  this::setApcFreeUnitsWait,
                  this::setApcFreeUnitsWait,
                  this::getApcFreeUnitsWait,
                  () -> apcFreeUnitsWait = null));
      case "carriedOverUnits" ->
          Optional.of(
              MutableProperty.of(
                  this::setCarriedOverUnits,
                  this::setCarriedOverUnits,
                  this::getCarriedOverUnits,
                  () -> carriedOverUnits = null));
      case "convertsMunitions" ->
          Optional.of(
              MutableProperty.of(
                  this::setConvertsMunitions,
                  this::setConvertsMunitions,
                  this::getConvertsMunitions,
                  () -> convertsMunitions = false));
      case "sellsAlliedUnitsAtEndTurn" ->
          Optional.of(
              MutableProperty.of(
                  this::setSellsAlliedUnitsAtEndTurn,
                  this::setSellsAlliedUnitsAtEndTurn,
                  this::getSellsAlliedUnitsAtEndTurn,
                  this::resetSellsAlliedUnitsAtEndTurn));
      case "alliedUnitSaleResourceValue" ->
          Optional.of(
              MutableProperty.of(
                  this::setAlliedUnitSaleResourceValue,
                  this::setAlliedUnitSaleResourceValue,
                  this::getAlliedUnitSaleResourceValue,
                  this::resetAlliedUnitSaleResourceValue));
      case "suicideAttackResources" ->
          Optional.of(
              MutableProperty.of(
                  this::setSuicideAttackResources,
                  this::setSuicideAttackResources,
                  this::getSuicideAttackResources,
                  this::resetSuicideAttackResources));
      case "suicideAttackTargets" ->
          Optional.of(
              MutableProperty.of(
                  this::setSuicideAttackTargets,
                  this::setSuicideAttackTargets,
                  this::getSuicideAttackTargets,
                  this::resetSuicideAttackTargets));
      case "placementLimit" ->
          Optional.of(
              MutableProperty.of(
                  this::setPlacementLimit,
                  this::setPlacementLimit,
                  this::getPlacementLimit,
                  this::resetPlacementLimit));
      case "movementLimit" ->
          Optional.of(
              MutableProperty.of(
                  this::setMovementLimit,
                  this::setMovementLimit,
                  this::getMovementLimit,
                  this::resetMovementLimit));
      case "attackingLimit" ->
          Optional.of(
              MutableProperty.of(
                  this::setAttackingLimit,
                  this::setAttackingLimit,
                  this::getAttackingLimit,
                  this::resetAttackingLimit));
      default -> Optional.empty();
    };
  }
}
