import hlt.*;

import java.util.*;

public class MyBot {

    private final static boolean DEBUG_LOGGING = true;

    private static HaliteGameInstance gameInstance;

    public static void main(final String[] args) {

        final Networking networking = new Networking();
        final GameMap gameMap = networking.initialize("Tamagocchi");

        // We now have 1 full minute to analyse the initial map.
        final String initialMapIntelligence =
                "width: " + gameMap.getWidth() +
                "; height: " + gameMap.getHeight() +
                "; players: " + gameMap.getAllPlayers().size() +
                "; planets: " + gameMap.getAllPlanets().size();

        logDebug(initialMapIntelligence);

        final ArrayList<Move> moveList = new ArrayList<>();

        gameInstance = new HaliteGameInstance(gameMap.getMyPlayerId());

        for (;;) {
            moveList.clear();
            networking.updateMap(gameMap);
            gameInstance.synchronize(gameMap);

            logDebug(gameInstance.echoStatistics());

            for (final Ship ship : gameMap.getMyPlayer().getShips().values()) {

                logDebug("++++ Iterating on SHIP [" + ship.getId() + "]");

                // START - construct a HaliteShipMoveInstance object; find and group all nearby objects. Double (key) = distance to, Entity = nearby entity
                HaliteShipMoveInstance shipMoveInstance = new HaliteShipMoveInstance();
                shipMoveInstance.initialize(gameMap, ship, gameInstance.myPlayerId);

                // logMapData(shipMoveInstance.myNearbyShips, shipMoveInstance.nearbyEnemyShips, shipMoveInstance.nearbyOwnedPlanets);

                // Direective #0 - Determine ship docking status.
                if (ship.getDockingStatus() != Ship.DockingStatus.Undocked) {
                    // TODO Rules for when to undock?
                    logDebug("Ship " + ship.getId() + " is DOCKED, iterating to next ship.");
                    continue;
                } else {
                    logDebug("Ship " + ship.getId() + " is not docked (" + ship.getDockingStatus() + "), continuing!");
                }

                // Directive #1 - Thrust toward nearby unowned planets.
                Move planetMove = shouldThrustTowardPlanet(gameInstance, shipMoveInstance, gameMap, ship);
                if (planetMove != null) {
                    moveList.add(planetMove);
                    logDebug("Ship " + ship.getId() + " thrust toward unowned planet move has been issued.");
                    continue; // Next ship in armada
                } else {
                    logDebug("No ship commands issued, searching for enemy ships.");
                }

                // Directive #2 - Search out enemy ships.
                Move enemyShipMove = thrustTowardEnemyShip(shipMoveInstance, gameMap, ship);
                if (enemyShipMove != null) {
                    moveList.add(enemyShipMove);
                    logDebug("Ship " + ship.getId() + " thrust toward enemy ship move has been issued.");
                    continue; // Next ship in armada
                } else {
                    logDebug("No ship commands issued, searching for enemy ships.");
                }

                shipMoveInstance.destroy();
            }

            Networking.sendMoves(moveList);
        }
    }

    static Move shouldThrustTowardPlanet(HaliteGameInstance gameInstance, HaliteShipMoveInstance shipMoveInstance, GameMap gameMap, Ship ship) {

        final double PLANETS_OWNED_THRESHOLD = 0.6d;

        if (gameInstance.percentageOfPlanetsOwned >= PLANETS_OWNED_THRESHOLD) {
            logDebug("Bypassing thrust toward any planets as % owned threshold of [" + gameInstance.percentageOfPlanetsOwned +
                    "] is >= [" + PLANETS_OWNED_THRESHOLD + "]");
            return null;
        }

        for (Map.Entry<Double,Planet> nearbyUnownedPlanetEntry : shipMoveInstance.nearbyUnownedPlanets.entrySet()) {

            Planet planet = nearbyUnownedPlanetEntry.getValue();

            if (planet.isOwned()) {
                continue;
            }

            if (ship.canDock(planet)) {
                logDebug("Ship " + ship.getId() + "/" + ship.getOwner() + " DOCKING on unowned Planet " + planet.toString());
                return new DockMove(ship, planet);
            }

            if (gameInstance.isShipAlreadyThrustingToPlanet(planet.getId())) {
                logDebug("Ship is already thrusting to planet [" + planet.getId() + "], bypassing.");
                continue;
            }

            final ThrustMove newThrustMove = Navigation.navigateShipToDock(gameMap, ship, planet, Constants.MAX_SPEED);
            if (newThrustMove != null) {
                logDebug("Ship " + ship.getId() + "/" + ship.getOwner() + " THRUSTING for unowned Planet " + planet.toString());
                gameInstance.registerShipThrustingToPlanet(planet.getId());
                return newThrustMove;
            }

            break;
        }

        return null;
    }

    private static double getHypotenuse(double height, double width) {
        return Math.sqrt((height * height) + (width * width));
    }

    static Move thrustTowardEnemyShip(HaliteShipMoveInstance shipMoveInstance, GameMap gameMap, Ship ship) {

        final double MIN_ATTACK_RANGE = 4.0d;
        final double MAX_ATTACK_DOCKED_SHIP_RANGE = 0.5d * getHypotenuse(gameMap.getHeight(), gameMap.getWidth());

        /*logDebug("Calculated max attack dock ship range at " + MAX_ATTACK_DOCKED_SHIP_RANGE +
            " from map height of " + gameMap.getHeight() + " and map width of " + gameMap.getWidth());*/

        // Attack docked ships first.
        for (Map.Entry<Double,Ship> nearbyDockedEnemyShipEntry : shipMoveInstance.dockedEnemyShips.entrySet()) {

            Ship dockedEnemyShip = nearbyDockedEnemyShipEntry.getValue();
            double dockedEnemyShipDistance = nearbyDockedEnemyShipEntry.getKey();

            if (dockedEnemyShipDistance > MAX_ATTACK_DOCKED_SHIP_RANGE) { continue; }

            Position dockedEnemyShipPosition = new Position(dockedEnemyShip.getXPos(), dockedEnemyShip.getYPos());

            final ThrustMove thrustTowardDockedEnemyShipMove = Navigation.navigateShipTowardsTarget(
                    gameMap, ship, dockedEnemyShipPosition, Constants.MAX_SPEED, true,
                    Constants.MAX_NAVIGATION_CORRECTIONS, Math.PI / 365.0);
            if (thrustTowardDockedEnemyShipMove != null) {
                logDebug("Adding move to moveList: " + thrustTowardDockedEnemyShipMove.toString());
                return thrustTowardDockedEnemyShipMove;
            } else {
                logDebug("Could not thrust toward enemy ship for some damn reason!");
            }
        }

        // If no docked ships are nearby, head for any available ship on the map.
        for (Map.Entry<Double,Ship> nearbyEnemyShipEntry : shipMoveInstance.allEnemyShips.entrySet()) {

            Ship enemyShip = nearbyEnemyShipEntry.getValue();
            double enemyShipDistance = nearbyEnemyShipEntry.getKey();

            // Don't thrust toward ship if we're already in attack range.
            if (enemyShipDistance <= MIN_ATTACK_RANGE) { continue; }

            Position enemyShipPosition = new Position(enemyShip.getXPos(), enemyShip.getYPos());

            final ThrustMove thrustTowardEnemyShipMove = Navigation.navigateShipTowardsTarget(
                    gameMap, ship, enemyShipPosition, Constants.MAX_SPEED, true,
                    Constants.MAX_NAVIGATION_CORRECTIONS, Math.PI / 365.0);
            if (thrustTowardEnemyShipMove != null) {
                logDebug("Adding move to moveList: " + thrustTowardEnemyShipMove.toString());
                return thrustTowardEnemyShipMove;
            } else {
                logDebug("Could not thrust toward enemy ship for some damn reason!");
            }

        }

        return null;
    }

    static void logMapData(Map<Double,Ship> myNearbyShips, Map<Double,Ship> enemyNearbyShips, Map<Double,Planet> nearbyOwnedPlanets) {

        if (!DEBUG_LOGGING) { return; }

        for (Map.Entry<Double,Ship> nearbyShipEntry : myNearbyShips.entrySet()) {
            Log.log("My nearby ship, distance: " + nearbyShipEntry.getKey() + ", entity: " + nearbyShipEntry.getValue().toString());
        }
        for (Map.Entry<Double,Ship> nearbyShipEntry : enemyNearbyShips.entrySet()) {
            Log.log("ENEMY nearby ship, distance: " + nearbyShipEntry.getKey() + ", entity: " + nearbyShipEntry.getValue().toString());
        }

        for (Map.Entry<Double,Planet> nearbyPlanetEntry : nearbyOwnedPlanets.entrySet()) {
            Log.log("Nearby planets, distance: " + nearbyPlanetEntry.getKey() + ", entity: " + nearbyPlanetEntry.getValue().toString());
        }
    }

    static void logDebug(String statement) {

        if (!DEBUG_LOGGING) { return; }

        Log.log(statement);
    }

    private static class HaliteGameInstance {

        int myPlayerId = -1;
        int myShipCount = 0;
        int myPlanetCount = 0;
        double percentageOfShipsOwned = 0.0d;

        int totalShipCount = 0;
        int totalPlanetCount = 0;
        int totalPlayerCount = 0;

        Map<Integer,Planet> myPlanets;
        double percentageOfPlanetsOwned = 0.0d;

        List<Integer> thrustingToPlanets;

        HaliteGameInstance(int playerId) {
            myPlayerId = playerId;
            myShipCount = 0;
            myPlanetCount = 0;
            totalShipCount = 0;
            totalPlanetCount = 0;
            totalPlayerCount = 0;
            percentageOfPlanetsOwned = 0.0d;
            percentageOfShipsOwned = 0.0d;
            myPlanets = new TreeMap<>();
            thrustingToPlanets = new ArrayList<>();
        }

        void synchronize(GameMap gameMap) {

            thrustingToPlanets.clear();

            myShipCount = gameMap.getMyPlayer().getShips().size();
            totalPlanetCount = gameMap.getAllPlanets().size();
            totalPlayerCount = gameMap.getAllPlayers().size();

            myPlanets.clear();
            for (Map.Entry<Integer,Planet> planetEntry : gameMap.getAllPlanets().entrySet()) {
                if (planetEntry.getValue().getOwner() == myPlayerId) {
                    myPlanets.put(planetEntry.getKey(), planetEntry.getValue());
                }
            }
            myPlanetCount = myPlanets.size();

            if (gameMap.getAllPlanets().size() == 0) {
                percentageOfPlanetsOwned = 0.0d;
            } else {
                percentageOfPlanetsOwned = ((double)myPlanetCount / (double)gameMap.getAllPlanets().size());
            }

            totalShipCount = gameMap.getAllShips().size();
            if (totalShipCount > 0) {
                percentageOfShipsOwned = ((double)myShipCount / (double)totalShipCount);
            } else {
                percentageOfShipsOwned = 0.0d;
            }
        }

        void registerShipThrustingToPlanet(Integer planetId) {
            thrustingToPlanets.add(planetId);
        }

        boolean isShipAlreadyThrustingToPlanet(Integer planetId) {
            return thrustingToPlanets.contains(planetId);
        }

        String echoStatistics() {
            return "GameInstance: Own [" + myShipCount + "] of [" + totalShipCount + "] ships (" + percentageOfShipsOwned + "%), " +
                    "own [" + myPlanetCount + "] of [" + totalPlanetCount + "] planets (" + percentageOfPlanetsOwned + "%) ";
        }
    }

    private static class HaliteShipMoveInstance {

        Map<Double,Ship> allMyShips;
        Map<Double,Ship> myNearbyShips;

        Map<Double,Ship> allEnemyShips;
        Map<Double,Ship> nearbyEnemyShips;
        Map<Double,Ship> undockedEnemyShips;
        Map<Double,Ship> dockedEnemyShips;

        Map<Double,Planet> nearbyOwnedPlanets;
        Map<Double,Planet> nearbyUnownedPlanets;
        Map<Double,Planet> nearbyEnemyOwnedPlanets;

        HaliteShipMoveInstance() {
            myNearbyShips = new TreeMap<>();
            allEnemyShips = new TreeMap<>();
            dockedEnemyShips = new TreeMap<>();
            undockedEnemyShips = new TreeMap<>();
            allMyShips = new TreeMap<>();
            nearbyEnemyShips = new TreeMap<>();
            nearbyOwnedPlanets = new TreeMap<>();
            nearbyUnownedPlanets = new TreeMap<>();
            nearbyEnemyOwnedPlanets = new TreeMap<>();
        }

        void initialize(GameMap gameMap, Ship myShip, Integer myPlayerId) {

            for (Map.Entry<Double,Entity> entry : gameMap.nearbyEntitiesByDistance(myShip).entrySet()) {
                if (entry.getValue() instanceof Ship) {
                    if (entry.getValue().equals(myShip)) { continue; } // Skip ship currently focused
                    if (entry.getValue().getOwner() == myShip.getOwner()) {
                        this.myNearbyShips.put(entry.getKey(), (Ship) entry.getValue());
                    } else {
                        nearbyEnemyShips.put(entry.getKey(), (Ship) entry.getValue());
                    }
                }
                if (entry.getValue() instanceof Planet) {
                    Planet thisPlanet = (Planet) entry.getValue();
                    if (thisPlanet.isOwned()) {
                        if (thisPlanet.getOwner() == myPlayerId) {
                            nearbyOwnedPlanets.put(entry.getKey(), (Planet) entry.getValue());
                        } else {
                            nearbyEnemyOwnedPlanets.put(entry.getKey(), (Planet) entry.getValue());
                        }
                    } else {
                        nearbyUnownedPlanets.put(entry.getKey(), (Planet) entry.getValue());
                    }
                }
            }

            for (Ship ship : gameMap.getAllShips()) {
                Position shipPosition = new Position(ship.getXPos(), ship.getYPos());
                Position myPosition = new Position(myShip.getXPos(), myShip.getYPos());
                Double distance = myPosition.getDistanceTo(shipPosition);
                if (ship.getOwner() != myPlayerId) {
                    allEnemyShips.put(distance, ship);
                    // If enemy ship is docked, docking, or undocking
                    if (ship.getDockingStatus() != Ship.DockingStatus.Undocked) {
                        dockedEnemyShips.put(distance, ship);
                    } else {
                        undockedEnemyShips.put(distance, ship);
                    }
                } else {
                    allMyShips.put(distance, ship);
                }
            }

        }

        void destroy() {
            myNearbyShips.clear();
            nearbyEnemyShips.clear();
            nearbyOwnedPlanets.clear();
            nearbyUnownedPlanets.clear();
            nearbyEnemyOwnedPlanets.clear();
        }

    }
}
