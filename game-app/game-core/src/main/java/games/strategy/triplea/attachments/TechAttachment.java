package games.strategy.triplea.attachments;

import games.strategy.engine.data.Attachable;
import games.strategy.engine.data.DefaultAttachment;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MutableProperty;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.gameparser.GameParseException;
import games.strategy.triplea.Constants;
import games.strategy.triplea.delegate.GenericTechAdvance;
import games.strategy.triplea.delegate.TechAdvance;
import java.io.Serial;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import lombok.Getter;
import org.jetbrains.annotations.NonNls;
import org.triplea.java.collections.IntegerMap;

/**
 * An attachment for instances of {@link GamePlayer} that defines properties related to technology
 * advances. Note: Empty collection fields default to null to minimize memory use and serialization
 * size.
 */
public class TechAttachment extends DefaultAttachment {
  @Serial private static final long serialVersionUID = -8780929085456199961L;

  // getters
  @Getter private int techCost = 5;
  private boolean heavyBomber = false;
  private boolean longRangeAir = false;
  private boolean jetPower = false;
  private boolean rocket = false;
  private boolean industrialTechnology = false;
  private boolean superSub = false;
  private boolean destroyerBombard = false;
  private boolean improvedArtillerySupport = false;
  private boolean paratroopers = false;
  private boolean increasedFactoryProduction = false;
  private boolean warBonds = false;
  private boolean mechanizedInfantry = false;
  private boolean aaRadar = false;
  private boolean shipyards = false;
  @Getter private final Map<String, Boolean> genericTech = new HashMap<>();
  // Flames and Steel research centres.
  // Number of research centres this player has.
  @Getter private int researchCenters = 0;
  // Flames and Steel (request 15): PUs the nation lost to strategic bombing in this round's air
  // phase, shown beside its PUs; set by the air strike step, which clears it first each round
  @Getter private int bombedThisRound = 0;
  // Stars (breakthroughs) gained so far, by tech name. Starting stars are set in the XML.
  private @Nullable IntegerMap<String> researchStars = null;
  // Per-nation star counts that override the tech's own researchStarsRequired, by tech name.
  private @Nullable IntegerMap<String> researchStarsRequired = null;
  // Flames and Steel: the nation's own dogfight bonus or penalty per unit type, e.g. "-1:fighter"
  private @Nullable IntegerMap<UnitType> dogfightBonus = null;
  // Flames and Steel (request 35): the nation's own interception bonus or penalty per unit type,
  // e.g. "-2:fighter"
  private @Nullable IntegerMap<UnitType> interceptionBonus = null;
  // Flames and Steel (request 43): the nation's starting bonus die for a bomber type, used instead
  // of the type's own value (sets and additions from techs then work as usual), e.g.
  // "0:torpedoBomber" for the American torpedo bombers before the Mark 13 fix
  private @Nullable IntegerMap<UnitType> landBonusBase = null;
  private @Nullable IntegerMap<UnitType> navalBonusBase = null;
  private @Nullable IntegerMap<UnitType> subBonusBase = null;
  // Flames and Steel (request 37): the nation's own attack and defense bonus or penalty per unit
  // type, e.g. "-1:diveBomber" (the Soviet Il-2)
  private @Nullable IntegerMap<UnitType> attackBonus = null;
  private @Nullable IntegerMap<UnitType> defenseBonus = null;
  // Focus of each research centre, one entry per centre: "primary|secondary" (either may be "").
  private @Nullable List<String> researchFocus = null;

  public TechAttachment(final String name, final Attachable attachable, final GameData gameData) {
    super(name, attachable, gameData);
    setGenericTechs();
  }

  static TechAttachment get(final GamePlayer gamePlayer, final String nameOfAttachment) {
    if (!nameOfAttachment.equals(Constants.TECH_ATTACHMENT_NAME)) {
      throw new IllegalStateException(
          "TechAttachment may not yet get attachments not named: "
              + Constants.TECH_ATTACHMENT_NAME);
    }
    return gamePlayer.getTechAttachment();
  }

  // setters
  public void setTechCost(final String s) {
    techCost = getInt(s);
  }

  private void setTechCost(final Integer s) {
    techCost = s;
  }

  private void resetTechCost() {
    techCost = 5;
  }

  private void setHeavyBomber(final String s) {
    heavyBomber = getBool(s);
  }

  private void setHeavyBomber(final Boolean s) {
    heavyBomber = s;
  }

  private void resetHeavyBomber() {
    heavyBomber = false;
  }

  private void setDestroyerBombard(final String s) {
    destroyerBombard = getBool(s);
  }

  private void setDestroyerBombard(final Boolean s) {
    destroyerBombard = s;
  }

  private void resetDestroyerBombard() {
    destroyerBombard = false;
  }

  private void setLongRangeAir(final String s) {
    longRangeAir = getBool(s);
  }

  private void setLongRangeAir(final Boolean s) {
    longRangeAir = s;
  }

  private void resetLongRangeAir() {
    longRangeAir = false;
  }

  private void setJetPower(final String s) {
    jetPower = getBool(s);
  }

  private void setJetPower(final Boolean s) {
    jetPower = s;
  }

  private void resetJetPower() {
    jetPower = false;
  }

  private void setRocket(final String s) {
    rocket = getBool(s);
  }

  private void setRocket(final Boolean s) {
    rocket = s;
  }

  private void resetRocket() {
    rocket = false;
  }

  private void setIndustrialTechnology(final String s) {
    industrialTechnology = getBool(s);
  }

  private void setIndustrialTechnology(final Boolean s) {
    industrialTechnology = s;
  }

  private void resetIndustrialTechnology() {
    industrialTechnology = false;
  }

  private void setSuperSub(final String s) {
    superSub = getBool(s);
  }

  private void setSuperSub(final Boolean s) {
    superSub = s;
  }

  private void resetSuperSub() {
    superSub = false;
  }

  private void setImprovedArtillerySupport(final String s) {
    improvedArtillerySupport = getBool(s);
  }

  private void setImprovedArtillerySupport(final Boolean s) {
    improvedArtillerySupport = s;
  }

  private void resetImprovedArtillerySupport() {
    improvedArtillerySupport = false;
  }

  public void setParatroopers(final String s) {
    paratroopers = getBool(s);
  }

  private void setParatroopers(final Boolean s) {
    paratroopers = s;
  }

  private void resetParatroopers() {
    paratroopers = false;
  }

  private void setIncreasedFactoryProduction(final String s) {
    increasedFactoryProduction = getBool(s);
  }

  private void setIncreasedFactoryProduction(final Boolean s) {
    increasedFactoryProduction = s;
  }

  private void resetIncreasedFactoryProduction() {
    increasedFactoryProduction = false;
  }

  private void setWarBonds(final String s) {
    warBonds = getBool(s);
  }

  private void setWarBonds(final Boolean s) {
    warBonds = s;
  }

  private void resetWarBonds() {
    warBonds = false;
  }

  public void setMechanizedInfantry(final String s) {
    mechanizedInfantry = getBool(s);
  }

  private void setMechanizedInfantry(final Boolean s) {
    mechanizedInfantry = s;
  }

  private void resetMechanizedInfantry() {
    mechanizedInfantry = false;
  }

  public void setAaRadar(final String s) {
    aaRadar = getBool(s);
  }

  private void setAaRadar(final Boolean s) {
    aaRadar = s;
  }

  private void resetAaRadar() {
    aaRadar = false;
  }

  private void setShipyards(final String s) {
    shipyards = getBool(s);
  }

  private void setShipyards(final Boolean s) {
    shipyards = s;
  }

  private void resetShipyards() {
    shipyards = false;
  }

  public boolean getHeavyBomber() {
    return heavyBomber;
  }

  public boolean getLongRangeAir() {
    return longRangeAir;
  }

  public boolean getJetPower() {
    return jetPower;
  }

  public boolean getRocket() {
    return rocket;
  }

  public boolean getIndustrialTechnology() {
    return industrialTechnology;
  }

  public boolean getSuperSub() {
    return superSub;
  }

  public boolean getDestroyerBombard() {
    return destroyerBombard;
  }

  public boolean getImprovedArtillerySupport() {
    return improvedArtillerySupport;
  }

  public boolean getParatroopers() {
    return paratroopers;
  }

  public boolean getIncreasedFactoryProduction() {
    return increasedFactoryProduction;
  }

  public boolean getWarBonds() {
    return warBonds;
  }

  public boolean getMechanizedInfantry() {
    return mechanizedInfantry;
  }

  public boolean getAaRadar() {
    return aaRadar;
  }

  public boolean getShipyards() {
    return shipyards;
  }

  // custom techs

  private void setGenericTechs() {
    for (final TechAdvance ta : getData().getTechnologyFrontier()) {
      if (ta instanceof GenericTechAdvance && ((GenericTechAdvance) ta).getAdvance() == null) {
        genericTech.put(ta.getProperty().intern(), Boolean.FALSE);
      }
    }
  }

  public Boolean hasGenericTech(final String name) {
    return genericTech.get(name);
  }

  public void setGenericTech(final String name, final boolean value) {
    genericTech.put(name, value);
    getData().getTechTracker().clearCache();
  }

  private void setResearchCenters(final String value) throws GameParseException {
    final int centers = getInt(value);
    if (centers < 0) {
      throw new GameParseException("researchCenters may not be negative" + thisErrorMsg());
    }
    researchCenters = centers;
  }

  private void setResearchCenters(final Integer value) {
    researchCenters = value;
  }

  private void resetResearchCenters() {
    researchCenters = 0;
  }

  private void setBombedThisRound(final String value) {
    bombedThisRound = getInt(value);
  }

  private void setBombedThisRound(final Integer value) {
    bombedThisRound = value;
  }

  private void resetBombedThisRound() {
    bombedThisRound = 0;
  }

  private void setResearchStars(final String value) throws GameParseException {
    final String[] s = splitOnColon(value);
    if (s.length != 2) {
      throw new GameParseException(
          "researchStars must be a star count and a tech name, e.g. count=2 value=Jet"
              + thisErrorMsg());
    }
    final int stars = getInt(s[0]);
    if (stars < 0) {
      throw new GameParseException("researchStars may not be negative" + thisErrorMsg());
    }
    if (researchStars == null) {
      researchStars = new IntegerMap<>();
    }
    researchStars.put(s[1].intern(), stars);
  }

  private void setResearchStars(final IntegerMap<String> value) {
    researchStars = value;
  }

  /** Stars gained so far, by tech name. */
  public IntegerMap<String> getResearchStars() {
    return getIntegerMapProperty(researchStars);
  }

  private void resetResearchStars() {
    researchStars = null;
  }

  private void setResearchStarsRequired(final String value) throws GameParseException {
    final String[] s = splitOnColon(value);
    if (s.length != 2) {
      throw new GameParseException(
          "researchStarsRequired must be a star count and a tech name, e.g. count=4 value=Jet"
              + thisErrorMsg());
    }
    final int stars = getInt(s[0]);
    if (stars < 1) {
      throw new GameParseException("researchStarsRequired must be at least 1" + thisErrorMsg());
    }
    if (researchStarsRequired == null) {
      researchStarsRequired = new IntegerMap<>();
    }
    researchStarsRequired.put(s[1].intern(), stars);
  }

  private void setResearchStarsRequired(final IntegerMap<String> value) {
    researchStarsRequired = value;
  }

  /** This nation's own star counts, overriding the tech's, by tech name. */
  public IntegerMap<String> getResearchStarsRequired() {
    return getIntegerMapProperty(researchStarsRequired);
  }

  private void resetResearchStarsRequired() {
    researchStarsRequired = null;
  }

  private void setDogfightBonus(final String value) throws GameParseException {
    final String[] s = splitOnColon(value);
    if (s.length != 2) {
      throw new GameParseException(
          "dogfightBonus must be a number and a unit type, e.g. -1:fighter" + thisErrorMsg());
    }
    if (dogfightBonus == null) {
      dogfightBonus = new IntegerMap<>();
    }
    dogfightBonus.add(getUnitTypeOrThrow(s[1]), getInt(s[0]));
  }

  private void setDogfightBonus(final IntegerMap<UnitType> value) {
    dogfightBonus = value;
  }

  public IntegerMap<UnitType> getDogfightBonus() {
    return getIntegerMapProperty(dogfightBonus);
  }

  private void setLandBonusBase(final String value) throws GameParseException {
    final String[] s = splitOnColon(value);
    if (s.length != 2) {
      throw new GameParseException(
          "landBonusBase must be a number and a unit type, e.g. 0:torpedoBomber" + thisErrorMsg());
    }
    if (landBonusBase == null) {
      landBonusBase = new IntegerMap<>();
    }
    landBonusBase.put(getUnitTypeOrThrow(s[1]), getInt(s[0]));
  }

  private void setLandBonusBase(final IntegerMap<UnitType> value) {
    landBonusBase = value;
  }

  /** Request 43: the nation's starting bonus die, replacing the unit type's own (listed types). */
  public IntegerMap<UnitType> getLandBonusBase() {
    return getIntegerMapProperty(landBonusBase);
  }

  private void setNavalBonusBase(final String value) throws GameParseException {
    final String[] s = splitOnColon(value);
    if (s.length != 2) {
      throw new GameParseException(
          "navalBonusBase must be a number and a unit type, e.g. 0:torpedoBomber" + thisErrorMsg());
    }
    if (navalBonusBase == null) {
      navalBonusBase = new IntegerMap<>();
    }
    navalBonusBase.put(getUnitTypeOrThrow(s[1]), getInt(s[0]));
  }

  private void setNavalBonusBase(final IntegerMap<UnitType> value) {
    navalBonusBase = value;
  }

  /** Request 43: the nation's starting bonus die, replacing the unit type's own (listed types). */
  public IntegerMap<UnitType> getNavalBonusBase() {
    return getIntegerMapProperty(navalBonusBase);
  }

  private void setSubBonusBase(final String value) throws GameParseException {
    final String[] s = splitOnColon(value);
    if (s.length != 2) {
      throw new GameParseException(
          "subBonusBase must be a number and a unit type, e.g. 0:torpedoBomber" + thisErrorMsg());
    }
    if (subBonusBase == null) {
      subBonusBase = new IntegerMap<>();
    }
    subBonusBase.put(getUnitTypeOrThrow(s[1]), getInt(s[0]));
  }

  private void setSubBonusBase(final IntegerMap<UnitType> value) {
    subBonusBase = value;
  }

  /** Request 43: the nation's starting bonus die, replacing the unit type's own (listed types). */
  public IntegerMap<UnitType> getSubBonusBase() {
    return getIntegerMapProperty(subBonusBase);
  }

  private void setInterceptionBonus(final String value) throws GameParseException {
    final String[] s = splitOnColon(value);
    if (s.length != 2) {
      throw new GameParseException(
          "interceptionBonus must be a number and a unit type, e.g. -2:fighter" + thisErrorMsg());
    }
    if (interceptionBonus == null) {
      interceptionBonus = new IntegerMap<>();
    }
    interceptionBonus.add(getUnitTypeOrThrow(s[1]), getInt(s[0]));
  }

  private void setInterceptionBonus(final IntegerMap<UnitType> value) {
    interceptionBonus = value;
  }

  /** Request 35: the nation's own interception bonus or penalty, by unit type. */
  public IntegerMap<UnitType> getInterceptionBonus() {
    return getIntegerMapProperty(interceptionBonus);
  }

  private IntegerMap<UnitType> parseBonus(
      final String name, final String value, final @Nullable IntegerMap<UnitType> existing)
      throws GameParseException {
    final String[] s = splitOnColon(value);
    if (s.length != 2) {
      throw new GameParseException(
          name + " must be a number and a unit type, e.g. -1:diveBomber" + thisErrorMsg());
    }
    final IntegerMap<UnitType> map = existing == null ? new IntegerMap<>() : existing;
    map.add(getUnitTypeOrThrow(s[1]), getInt(s[0]));
    return map;
  }

  private void setAttackBonus(final String value) throws GameParseException {
    attackBonus = parseBonus("attackBonus", value, attackBonus);
  }

  private void setAttackBonus(final IntegerMap<UnitType> value) {
    attackBonus = value;
  }

  /** Request 37: the nation's own attack bonus or penalty, by unit type. */
  public IntegerMap<UnitType> getAttackBonus() {
    return getIntegerMapProperty(attackBonus);
  }

  private void setDefenseBonus(final String value) throws GameParseException {
    defenseBonus = parseBonus("defenseBonus", value, defenseBonus);
  }

  private void setDefenseBonus(final IntegerMap<UnitType> value) {
    defenseBonus = value;
  }

  /** Request 37: the nation's own defense bonus or penalty, by unit type. */
  public IntegerMap<UnitType> getDefenseBonus() {
    return getIntegerMapProperty(defenseBonus);
  }

  private void setResearchFocus(final String value) {
    if (researchFocus == null) {
      researchFocus = new ArrayList<>();
    }
    researchFocus.addAll(List.of(splitOnColon(value)));
  }

  private void setResearchFocus(final List<String> value) {
    researchFocus = value;
  }

  /** Focus of each research centre, as "primary|secondary". */
  public List<String> getResearchFocus() {
    return getListProperty(researchFocus);
  }

  private void resetResearchFocus() {
    researchFocus = null;
  }

  @Override
  public void validate(final GameState data) throws GameParseException {
    for (final String techName : getResearchStarsRequired().keySet()) {
      if (data.getTechnologyFrontier().getAdvanceByName(techName) == null) {
        throw new GameParseException(
            "researchStarsRequired names an unknown technology: " + techName + thisErrorMsg());
      }
    }
    for (final String techName : getResearchStars().keySet()) {
      if (data.getTechnologyFrontier().getAdvanceByName(techName) == null) {
        throw new GameParseException(
            "researchStars names an unknown technology: " + techName + thisErrorMsg());
      }
    }
  }

  @Override
  public Optional<MutableProperty<?>> getPropertyOrEmpty(final @NonNls String propertyName) {
    return switch (propertyName) {
      case "techCost" ->
          Optional.of(
              MutableProperty.of(
                  this::setTechCost, this::setTechCost, this::getTechCost, this::resetTechCost));
      case "heavyBomber" ->
          Optional.of(
              MutableProperty.of(
                  this::setHeavyBomber,
                  this::setHeavyBomber,
                  this::getHeavyBomber,
                  this::resetHeavyBomber));
      case "longRangeAir" ->
          Optional.of(
              MutableProperty.of(
                  this::setLongRangeAir,
                  this::setLongRangeAir,
                  this::getLongRangeAir,
                  this::resetLongRangeAir));
      case "jetPower" ->
          Optional.of(
              MutableProperty.of(
                  this::setJetPower, this::setJetPower, this::getJetPower, this::resetJetPower));
      case "rocket" ->
          Optional.of(
              MutableProperty.of(
                  this::setRocket, this::setRocket, this::getRocket, this::resetRocket));
      case "industrialTechnology" ->
          Optional.of(
              MutableProperty.of(
                  this::setIndustrialTechnology,
                  this::setIndustrialTechnology,
                  this::getIndustrialTechnology,
                  this::resetIndustrialTechnology));
      case "superSub" ->
          Optional.of(
              MutableProperty.of(
                  this::setSuperSub, this::setSuperSub, this::getSuperSub, this::resetSuperSub));
      case "destroyerBombard" ->
          Optional.of(
              MutableProperty.of(
                  this::setDestroyerBombard,
                  this::setDestroyerBombard,
                  this::getDestroyerBombard,
                  this::resetDestroyerBombard));
      case "improvedArtillerySupport" ->
          Optional.of(
              MutableProperty.of(
                  this::setImprovedArtillerySupport,
                  this::setImprovedArtillerySupport,
                  this::getImprovedArtillerySupport,
                  this::resetImprovedArtillerySupport));
      case "paratroopers" ->
          Optional.of(
              MutableProperty.of(
                  this::setParatroopers,
                  this::setParatroopers,
                  this::getParatroopers,
                  this::resetParatroopers));
      case "increasedFactoryProduction" ->
          Optional.of(
              MutableProperty.of(
                  this::setIncreasedFactoryProduction,
                  this::setIncreasedFactoryProduction,
                  this::getIncreasedFactoryProduction,
                  this::resetIncreasedFactoryProduction));
      case "warBonds" ->
          Optional.of(
              MutableProperty.of(
                  this::setWarBonds, this::setWarBonds, this::getWarBonds, this::resetWarBonds));
      case "mechanizedInfantry" ->
          Optional.of(
              MutableProperty.of(
                  this::setMechanizedInfantry,
                  this::setMechanizedInfantry,
                  this::getMechanizedInfantry,
                  this::resetMechanizedInfantry));
      case "aARadar" ->
          Optional.of(
              MutableProperty.of(
                  this::setAaRadar, this::setAaRadar, this::getAaRadar, this::resetAaRadar));
      case "shipyards" ->
          Optional.of(
              MutableProperty.of(
                  this::setShipyards,
                  this::setShipyards,
                  this::getShipyards,
                  this::resetShipyards));
      case "bombedThisRound" ->
          Optional.of(
              MutableProperty.of(
                  this::setBombedThisRound,
                  this::setBombedThisRound,
                  this::getBombedThisRound,
                  this::resetBombedThisRound));
      case "researchCenters" ->
          Optional.of(
              MutableProperty.of(
                  this::setResearchCenters,
                  this::setResearchCenters,
                  this::getResearchCenters,
                  this::resetResearchCenters));
      case "researchStars" ->
          Optional.of(
              MutableProperty.of(
                  this::setResearchStars,
                  this::setResearchStars,
                  this::getResearchStars,
                  this::resetResearchStars));
      case "attackBonus" ->
          Optional.of(
              MutableProperty.of(
                  this::setAttackBonus,
                  this::setAttackBonus,
                  this::getAttackBonus,
                  () -> attackBonus = null));
      case "defenseBonus" ->
          Optional.of(
              MutableProperty.of(
                  this::setDefenseBonus,
                  this::setDefenseBonus,
                  this::getDefenseBonus,
                  () -> defenseBonus = null));
      case "landBonusBase" ->
          Optional.of(
              MutableProperty.of(
                  this::setLandBonusBase,
                  this::setLandBonusBase,
                  this::getLandBonusBase,
                  () -> landBonusBase = null));
      case "navalBonusBase" ->
          Optional.of(
              MutableProperty.of(
                  this::setNavalBonusBase,
                  this::setNavalBonusBase,
                  this::getNavalBonusBase,
                  () -> navalBonusBase = null));
      case "subBonusBase" ->
          Optional.of(
              MutableProperty.of(
                  this::setSubBonusBase,
                  this::setSubBonusBase,
                  this::getSubBonusBase,
                  () -> subBonusBase = null));
      case "interceptionBonus" ->
          Optional.of(
              MutableProperty.of(
                  this::setInterceptionBonus,
                  this::setInterceptionBonus,
                  this::getInterceptionBonus,
                  () -> interceptionBonus = null));
      case "dogfightBonus" ->
          Optional.of(
              MutableProperty.of(
                  this::setDogfightBonus,
                  this::setDogfightBonus,
                  this::getDogfightBonus,
                  () -> dogfightBonus = null));
      case "researchStarsRequired" ->
          Optional.of(
              MutableProperty.of(
                  this::setResearchStarsRequired,
                  this::setResearchStarsRequired,
                  this::getResearchStarsRequired,
                  this::resetResearchStarsRequired));
      case "researchFocus" ->
          Optional.of(
              MutableProperty.of(
                  this::setResearchFocus,
                  this::setResearchFocus,
                  this::getResearchFocus,
                  this::resetResearchFocus));
      default -> Optional.empty();
    };
  }
}
