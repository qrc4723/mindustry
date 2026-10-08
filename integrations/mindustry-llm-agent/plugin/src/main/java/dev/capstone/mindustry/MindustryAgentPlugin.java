package dev.capstone.mindustry;

import arc.Core;
import arc.Events;
import arc.ApplicationListener;
import arc.math.geom.Vec2;
import arc.util.CommandHandler;
import arc.util.Log;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.Units;
import mindustry.entities.units.AIController;
import mindustry.ai.UnitCommand;
import mindustry.ai.types.BuilderAI;
import mindustry.ai.types.CommandAI;
import mindustry.ai.types.MinerAI;
import mindustry.ai.types.RepairAI;
import mindustry.game.EventType.DisposeEvent;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.UnitDestroyEvent;
import mindustry.game.EventType.WaveEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.game.SpawnGroup;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.mod.Plugin;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.Liquid;
import mindustry.type.LiquidStack;
import mindustry.type.UnitType;
import mindustry.type.Weapon;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.Drill.DrillBuild;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.production.GenericCrafter.GenericCrafterBuild;
import mindustry.world.blocks.power.PowerGenerator;
import mindustry.world.blocks.power.ConsumeGenerator;
import mindustry.world.blocks.power.PowerNode;
import mindustry.world.blocks.power.PowerNode.PowerNodeBuild;
import mindustry.world.blocks.distribution.Sorter;
import mindustry.world.blocks.distribution.Sorter.SorterBuild;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.defense.Wall;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild;
import mindustry.world.blocks.units.Reconstructor;
import mindustry.world.blocks.units.Reconstructor.ReconstructorBuild;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeItemFilter;
import mindustry.world.consumers.ConsumeItems;
import mindustry.world.consumers.ConsumeLiquid;
import mindustry.world.consumers.ConsumeLiquidFilter;
import mindustry.world.consumers.ConsumeLiquids;
import mindustry.world.consumers.ConsumePower;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild;
import mindustry.world.blocks.defense.turrets.ContinuousTurret;
import mindustry.world.blocks.defense.turrets.LiquidTurret;
import mindustry.world.blocks.defense.turrets.PowerTurret;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.defense.turrets.Turret.TurretBuild;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.blocks.storage.Unloader;
import mindustry.world.blocks.storage.Unloader.UnloaderBuild;
import arc.struct.Seq;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * A localhost-only bridge between a Mindustry headless server and an external agent.
 *
 * <p>The first experiment deliberately exposes strategic macro actions instead of mouse/keyboard
 * input. Placement is server-authoritative, placement rules and item costs are enforced, and the
 * completed block appears immediately. Both future baselines and swarm agents must use this same
 * adapter for a fair comparison.</p>
 */
public final class MindustryAgentPlugin extends Plugin {
    private static final int DEFAULT_PORT = 8765;
    private static final int GAME_THREAD_REQUEST_TIMEOUT_SECONDS = 20;
    private static final int MAX_ACTIONS_PER_REQUEST = 32;
    private static final int MAX_EXPLICIT_PATH_TILES = 192;
    private static final int MAX_MACRO_COPPER = 5000;
    private static final int MAX_TURRETS_PER_DEFENSE_ACTION = 8;
    private static final int MAX_RTS_TRAIN_COUNT = 50;
    private static final int MAX_BUILDINGS_IN_STATE = 300;
    private static final int MAX_UNITS_IN_STATE = 300;
    private static final int MAX_ORES_IN_STATE = 400;
    private static final int ORE_SCAN_RADIUS = 48;
    private static final int PLACEMENT_HINT_RADIUS = 36;
    private static final int RTS_PLACEMENT_HINT_RADIUS = 64;
    private static final int PVP_ARENA_WIDTH = 168;
    private static final int PVP_ARENA_HEIGHT = 81;
    private static final int PVP_CORE_HALF_SEPARATION = 56;
    private static final int MAX_PLACEMENT_HINTS_PER_BLOCK = 220;
    private static final int MACRO_PATH_RADIUS = 64;
    private static final double RTS_SQUAD_UPDATE_INTERVAL_TICKS = 30d;
    private static final double RTS_EMPTY_SQUAD_RETENTION_TICKS = 30d * 60d;
    private static final double CORE_DEFENDER_RESPAWN_DELAY_TICKS = 12d * 60d;
    private static final double RTS_CONTROL_CAPTURE_RADIUS_TILES = 11d;
    private static final double RTS_CONTROL_CAPTURE_SECONDS = 12d;
    private static final double RTS_CONTROL_PRODUCTION_SPEED_BONUS_PER_POINT = 0.10d;
    private static final double RTS_CONTROL_REPAIR_MAX_HEALTH_PER_SECOND = 0.01d;
    private static final int RTS_ABUNDANT_ITEM_AMOUNT = 12000;
    private static final int[][] DIRECTIONS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final AtomicInteger wavesSeen = new AtomicInteger();
    private HttpServer httpServer;
    private ExecutorService httpExecutor;
    private String episodeId = UUID.randomUUID().toString();
    private String gameResult = "running";
    private Team gameWinner;
    private Map<String, Object> previousEpisodeResult = Map.of();
    private String authToken = "";
    private final Map<String, Team> tokenTeams = new HashMap<>();
    private final ThreadLocal<Team> requestTeam = ThreadLocal.withInitial(() -> Team.sharded);
    private boolean autoCoreDefense = true;
    private final Map<Integer, TeamAgentState> teamAgentStates = new HashMap<>();
    private final Set<String> spectatorPlayerUuids = new HashSet<>();
    private Process agentProcess;
    private final Map<String, Process> pvpAgentProcesses = new LinkedHashMap<>();
    private boolean restartScheduled;
    private CommandHandler serverCommands;
    private final Map<String, String> approvedConveyorPlans = new HashMap<>();
    private Map<String, Object> pvpFairness = Map.of("applied", false);
    private String gameModeVariant = "standard";
    private final List<RtsConstructionOrder> rtsConstructionOrders = new ArrayList<>();
    private final List<RtsTrainingOrder> rtsTrainingOrders = new ArrayList<>();
    private final List<RtsUpgradeOrder> rtsUpgradeOrders = new ArrayList<>();
    private final List<RtsControlPoint> rtsControlPoints = new ArrayList<>();
    private double lastRtsControlUpdateTick;
    private double nextRtsControlLabelTick;
    private double nextSpectatorHudTick;
    private boolean spectatorHudVisible;
    private String gameEndReason = "";

    @Override
    public void init() {
        int port = readPort();
        authToken = System.getenv().getOrDefault("MINDUSTRY_AGENT_TOKEN", "").trim();
        loadTeamTokens();
        autoCoreDefense = readBoolean("MINDUSTRY_AUTO_CORE_DEFENSE", true);

        Events.on(WorldLoadEvent.class, event -> {
            episodeId = UUID.randomUUID().toString();
            gameResult = "running";
            gameWinner = null;
            gameEndReason = "";
            wavesSeen.set(0);
            teamAgentStates.clear();
            approvedConveyorPlans.clear();
            pvpFairness = Map.of("applied", false);
            gameModeVariant = "standard";
            rtsConstructionOrders.clear();
            rtsTrainingOrders.clear();
            rtsUpgradeOrders.clear();
            rtsControlPoints.clear();
            lastRtsControlUpdateTick = 0d;
            nextRtsControlLabelTick = 0d;
            nextSpectatorHudTick = 0d;
            spectatorHudVisible = false;
            restartScheduled = false;
            if (Vars.state.rules.pvp) Vars.state.rules.pvpAutoPause = false;
            Log.info("[LLM bridge] episode @ started on map @", episodeId,
                Vars.state.map == null ? "unknown" : Vars.state.map.name());
        });
        Events.run(Trigger.update, () -> {
            updateCoreDefender();
            updateRtsConstruction();
            updateRtsTraining();
            updateRtsUpgrades();
            updateRtsControlPoints();
            updateRtsControlPointVisuals();
            cleanupDeadRtsUnits();
            updateRtsSquads();
            updateSpectatorPlayers();
            updateSpectatorHud();
        });
        Events.on(WaveEvent.class, event -> wavesSeen.incrementAndGet());
        Events.on(UnitDestroyEvent.class, event -> recordRtsUnitLoss(event.unit));
        Events.on(PlayerJoin.class, event -> {
            if ("stockpile_rts_pvp".equals(gameModeVariant) && canControlAgent(event.player)) {
                spectatorPlayerUuids.add(event.player.uuid());
                Core.app.post(() -> enterSpectatorMode(event.player, true));
            }
        });
        Events.on(GameOverEvent.class, event -> {
            if (gameEndReason.isBlank()) gameEndReason = "enemy_core_destroyed";
            gameWinner = event.winner;
            gameResult = event.winner == null ? "ended" : event.winner.name + "_won";
            Map<String, Object> completed = new LinkedHashMap<>();
            completed.put("episode_id", episodeId);
            completed.put("winner", event.winner == null ? null : event.winner.name);
            completed.put("result", gameResult);
            completed.put("victory_reason", gameEndReason);
            completed.put("ended_at", Instant.now().toString());
            completed.put("ended_tick", Vars.state == null ? null : Vars.state.tick);
            previousEpisodeResult = completed;
        });
        Events.on(DisposeEvent.class, event -> shutdown());
        Core.app.addListener(new ApplicationListener() {
            @Override
            public void dispose() {
                shutdown();
            }
        });

        try {
            httpServer = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            httpServer.createContext("/health", this::handleHealth);
            httpServer.createContext("/v1/state", this::handleState);
            httpServer.createContext("/v1/action", this::handleAction);
            httpExecutor = Executors.newFixedThreadPool(3, runnable -> {
                Thread thread = new Thread(runnable, "mindustry-llm-http");
                thread.setDaemon(true);
                return thread;
            });
            httpServer.setExecutor(httpExecutor);
            httpServer.start();
            Log.info("[LLM bridge] listening at http://127.0.0.1:@", port);
            if (authToken.isEmpty() && tokenTeams.isEmpty()) {
                Log.warn("[LLM bridge] MINDUSTRY_AGENT_TOKEN is unset; localhost requests are unauthenticated.");
            }
        } catch (IOException error) {
            throw new IllegalStateException("Unable to start Mindustry LLM bridge", error);
        }
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        serverCommands = handler;
        handler.register("agent-status", "Show active LLM agent status.", args ->
            Log.info("LLM bridge: episode=@ result=@ wavesSeen=@ agents=@",
                episodeId, gameResult, wavesSeen.get(), activeAgentStatus()));
        handler.register("agent-start", "[turns]", "Start one agent, or both agents in PvP.", args ->
            Log.info(startActiveAgents(parseTurns(args))));
        handler.register("agent-stop", "Stop all LLM agent processes.", args ->
            Log.info(stopAllAgents()));
        handler.register("pvp-agent-start", "[turns]", "Start the primary cloud model and DeepSeek simultaneously.", args ->
            Log.info(startPvpAgents(parseTurns(args))));
        handler.register("pvp-agent-stop", "Stop both PvP agents.", args -> Log.info(stopPvpAgents()));
        handler.register("pvp-agent-status", "Show both PvP agent processes.", args -> Log.info(pvpAgentStatus()));
        handler.register("game-restart", "Restart the current map as a fresh episode.", args ->
            Log.info(scheduleGameRestart()));
        handler.register("pvp-start", "[map]", "Start a fresh two-agent flat symmetric PvP game (default: Glacier cores).", args ->
            Log.info(schedulePvpStart(args.length == 0 ? "Glacier" : String.join(" ", args))));
        handler.register("rts-start", "[map]", "Start stockpile RTS PvP without mining/logistics (default: Glacier cores).", args ->
            Log.info(scheduleRtsStart(args.length == 0 ? "Glacier" : String.join(" ", args))));
        handler.register("showcase-rts-start", "[map]", "Start stockpile RTS PvP on a symmetric three-lane battlefield.", args ->
            Log.info(scheduleShowcaseRtsStart(args.length == 0 ? "Glacier" : String.join(" ", args))));
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        handler.<Player>register("agent-start", "[turns]", "Start agent actions; starts both sides in PvP.", (args, player) -> {
            if (!canControlAgent(player)) {
                player.sendMessage("[scarlet]Only a local player or server admin can control the agent.");
                return;
            }
            player.sendMessage("[accent]" + startActiveAgents(parseTurns(args)));
        });
        handler.<Player>register("agent-stop", "Stop all LLM agent actions.", (args, player) -> {
            if (!canControlAgent(player)) {
                player.sendMessage("[scarlet]Only a local player or server admin can control the agent.");
                return;
            }
            player.sendMessage("[accent]" + stopAllAgents());
        });
        handler.<Player>register("agent-status", "Show LLM agent status.", (args, player) ->
            player.sendMessage("[accent]Agents: " + activeAgentStatus()));
        handler.<Player>register("pvp-agent-start", "[turns]", "Start the primary cloud model and DeepSeek simultaneously.", (args, player) -> {
            if (!canControlAgent(player)) {
                player.sendMessage("[scarlet]Only a local player or server admin can control the agents.");
                return;
            }
            player.sendMessage("[accent]" + startPvpAgents(parseTurns(args)));
        });
        handler.<Player>register("pvp-agent-stop", "Stop both PvP agents.", (args, player) -> {
            if (!canControlAgent(player)) {
                player.sendMessage("[scarlet]Only a local player or server admin can control the agents.");
                return;
            }
            player.sendMessage("[accent]" + stopPvpAgents());
        });
        handler.<Player>register("pvp-agent-status", "Show both PvP agent processes.", (args, player) ->
            player.sendMessage("[accent]PvP agents: " + pvpAgentStatus()));
        handler.<Player>register("game-restart", "Restart this map as a fresh episode.", (args, player) -> {
            if (!canControlAgent(player)) {
                player.sendMessage("[scarlet]Only a local player or server admin can restart the game.");
                return;
            }
            player.sendMessage("[accent]" + scheduleGameRestart());
        });
        handler.<Player>register("pvp-start", "[map]", "Start a fresh two-agent PvP game.", (args, player) -> {
            if (!canControlAgent(player)) {
                player.sendMessage("[scarlet]Only a local player or server admin can restart the game.");
                return;
            }
            player.sendMessage("[accent]" + schedulePvpStart(args.length == 0 ? "Glacier" : String.join(" ", args)));
        });
        handler.<Player>register("rts-start", "[map]", "Start stockpile RTS PvP without mining/logistics.", (args, player) -> {
            if (!canControlAgent(player)) {
                player.sendMessage("[scarlet]Only a local player or server admin can restart the game.");
                return;
            }
            player.sendMessage("[accent]" + scheduleRtsStart(args.length == 0 ? "Glacier" : String.join(" ", args)));
        });
        handler.<Player>register("showcase-rts-start", "[map]", "Start the symmetric three-lane stockpile RTS showcase.", (args, player) -> {
            if (!canControlAgent(player)) {
                player.sendMessage("[scarlet]Only a local player or server admin can start the RTS showcase.");
                return;
            }
            player.sendMessage("[accent]" + scheduleShowcaseRtsStart(args.length == 0 ? "Glacier" : String.join(" ", args)));
        });
        handler.<Player>register("spectate", "Enter neutral free-camera spectator mode.", (args, player) -> {
            if (!canControlAgent(player)) {
                player.sendMessage("[scarlet]Only a local player or server admin can enter benchmark spectator mode.");
                return;
            }
            spectatorPlayerUuids.add(player.uuid());
            enterSpectatorMode(player, true);
        });
        handler.<Player>register("spectate-off", "[sharded|crux]", "Leave spectator mode and join a team.", (args, player) -> {
            if (!canControlAgent(player)) {
                player.sendMessage("[scarlet]Only a local player or server admin can leave benchmark spectator mode.");
                return;
            }
            Team team = args.length == 0 ? Team.sharded : findTeamByName(args[0]);
            if (team == null || team == Team.derelict || (team != Team.sharded && team != Team.crux)) {
                player.sendMessage("[scarlet]Use /spectate-off sharded or /spectate-off crux.");
                return;
            }
            spectatorPlayerUuids.remove(player.uuid());
            player.team(team);
            CoreBuild core = team.core();
            if (core != null && player.con != null) Call.setCameraPosition(player.con, core.x, core.y);
            player.sendMessage("[accent]Spectator mode disabled. Joined " + team.name + ".");
        });
    }

    private void updateSpectatorPlayers() {
        if (spectatorPlayerUuids.isEmpty()) return;
        for (Player player : Groups.player) {
            if (spectatorPlayerUuids.contains(player.uuid())
                && (player.team() != Team.derelict || player.unit() != null)) {
                enterSpectatorMode(player, false);
            }
        }
    }

    private void enterSpectatorMode(Player player, boolean notify) {
        Unit controlled = player.unit();
        if (controlled != null && controlled.isAdded()) controlled.kill();
        player.team(Team.derelict);
        player.clearUnit();
        if (player.con != null && Vars.world != null) {
            Call.setCameraPosition(player.con,
                Vars.world.width() * Vars.tilesize / 2f,
                Vars.world.height() * Vars.tilesize / 2f);
        }
        if (notify) {
            player.sendMessage(
                "[accent]관전자 모드로 전환했습니다.[] 마우스 휠로 축소하고 드래그/WASD로 자유롭게 이동하세요. "
                + "복귀: /spectate-off sharded 또는 /spectate-off crux"
            );
        }
    }

    private String scheduleGameRestart() {
        if (Vars.state == null || !Vars.state.isGame() || Vars.state.map == null) {
            return "restart failed: no map is loaded";
        }
        if (serverCommands == null) return "restart failed: server command handler is unavailable";
        if (restartScheduled) return "restart already scheduled";
        restartScheduled = true;
        stopAllAgents();
        Core.app.post(() -> {
            try {
                serverCommands.handleMessage("stop");
                serverCommands.handleMessage("host Fork survival");
                Log.info("[LLM bridge] restarted Fork survival as a fresh episode");
            } catch (Exception error) {
                restartScheduled = false;
                Log.err("[LLM bridge] game restart failed", error);
            }
        });
        return "restarting Fork survival; agent stopped (reconnect after a moment)";
    }

    private String schedulePvpStart(String mapName) {
        return schedulePvpStart(mapName, false, false);
    }

    private String scheduleRtsStart(String mapName) {
        return schedulePvpStart(mapName, true, false);
    }

    private String scheduleShowcaseRtsStart(String mapName) {
        return schedulePvpStart(mapName, true, true);
    }

    private String schedulePvpStart(String mapName, boolean stockpileRts, boolean strategicBattlefield) {
        if (serverCommands == null) return "PvP start failed: server command handler is unavailable";
        if (restartScheduled) return "restart already scheduled";
        restartScheduled = true;
        stopAllAgents();
        Core.app.post(() -> {
            try {
                if (Vars.state != null && Vars.state.isGame()) serverCommands.handleMessage("stop");
                serverCommands.handleMessage("host " + mapName + " pvp");
                // Headless PvP normally pauses as soon as the last human leaves. Agent-only
                // matches must keep advancing, and both teams receive this identical rule.
                Vars.state.rules.pvpAutoPause = false;
                Vars.state.rules.pauseDisabled = true;
                String requestedModeVariant = stockpileRts ? "stockpile_rts_pvp" : "economy_pvp";
                gameModeVariant = requestedModeVariant;
                pvpFairness = createSymmetricPvpArena(
                    Team.sharded, Team.crux, !stockpileRts, strategicBattlefield
                );
                // World resizing emits a fresh load event, which intentionally resets per-match state.
                // Restore the caller-selected PvP variant before initializing its mechanics.
                gameModeVariant = requestedModeVariant;
                if (stockpileRts) {
                    applyEqualRtsStockpiles(Team.sharded, Team.crux);
                    initializeRtsControlPoints(Team.sharded, Team.crux);
                }
                serverCommands.handleMessage("pause off");
                Log.info("[LLM bridge] started @ in @ mode; fairness=@", mapName, gameModeVariant, pvpFairness);
            } catch (Exception error) {
                restartScheduled = false;
                Log.err("[LLM bridge] PvP start failed", error);
            }
        });
        String modeName = strategicBattlefield ? "three-lane stockpile RTS showcase"
            : stockpileRts ? "stockpile RTS PvP" : "economy PvP";
        return "starting " + mapName + " " + modeName
            + "; connect the client again if the current game closes";
    }

    private Map<String, Object> createSymmetricPvpArena(
        Team sourceTeam, Team targetTeam, boolean includeOrePatches, boolean strategicBattlefield
    ) {
        CoreBuild sourceCore = sourceTeam.core();
        CoreBuild targetCore = targetTeam.core();
        if (sourceCore == null || targetCore == null) {
            return Map.of("applied", false, "reason", "both_agent_cores_required");
        }

        // Replace Glacier's tall 150x250 tile grid with the actual wide arena. A map-area limit alone
        // cannot become wider than the source map, so resizing the world is required for a visibly
        // wider battlefield that still fits in one overview.
        int arenaWidth = PVP_ARENA_WIDTH;
        int arenaHeight = PVP_ARENA_HEIGHT;
        int arenaX = 0;
        int arenaY = 0;
        int arenaCenterX = arenaX + arenaWidth / 2;
        int arenaCenterY = arenaY + arenaHeight / 2;
        int halfSeparation = Math.min(PVP_CORE_HALF_SEPARATION, Math.max(12, arenaWidth / 2 - 18));
        int sourceCoreX = arenaCenterX - halfSeparation;
        int targetCoreX = arenaX + arenaWidth - 1 - (sourceCoreX - arenaX);

        Block sourceCoreBlock = sourceCore.block;
        Block targetCoreBlock = targetCore.block;
        CoreBuild relocatedSource;
        CoreBuild relocatedTarget;
        int flattenedTiles = 0;
        Vars.world.beginMapLoad();
        try {
            Vars.world.resize(arenaWidth, arenaHeight).fill();
            clearStaleTeamBuildingRegistry(sourceTeam);
            clearStaleTeamBuildingRegistry(targetTeam);
            for (int x = 0; x < Vars.world.width(); x++) {
                for (int y = 0; y < Vars.world.height(); y++) {
                    Tile tile = Vars.world.tile(x, y);
                    tile.setBlock(Blocks.air);
                    tile.setFloor(Blocks.stone.asFloor());
                    tile.setOverlay(Blocks.air);
                    flattenedTiles++;
                }
            }

            Tile newSourceCoreTile = Vars.world.tile(sourceCoreX, arenaCenterY);
            Tile newTargetCoreTile = Vars.world.tile(targetCoreX, arenaCenterY);
            newSourceCoreTile.setBlock(sourceCoreBlock, sourceTeam);
            newTargetCoreTile.setBlock(targetCoreBlock, targetTeam);
            if (!(newSourceCoreTile.build instanceof CoreBuild sourceBuild)
                || !(newTargetCoreTile.build instanceof CoreBuild targetBuild)) {
                throw new IllegalStateException("wide PvP arena core placement failed");
            }
            relocatedSource = sourceBuild;
            relocatedTarget = targetBuild;
        } finally {
            Vars.world.endMapLoad();
        }
        sourceCore = relocatedSource;
        targetCore = relocatedTarget;

        Map<String, Object> battlefield = strategicBattlefield
            ? createSymmetricThreeLaneBattlefield(sourceCore, targetCore)
            : Map.of(
                "style", "open_flat_baseline",
                "ground_route_choices", 1,
                "description", "Obstacle-free baseline arena; neutral objectives remain available."
            );

        Vars.state.rules.limitMapArea = true;
        Vars.state.rules.limitX = arenaX;
        Vars.state.rules.limitY = arenaY;
        Vars.state.rules.limitWidth = arenaWidth;
        Vars.state.rules.limitHeight = arenaHeight;

        int sumX = sourceCore.tileX() + targetCore.tileX();
        int sumY = sourceCore.tileY() + targetCore.tileY();

        double directionX = targetCore.tileX() - sourceCore.tileX();
        double directionY = targetCore.tileY() - sourceCore.tileY();
        double directionLength = Math.max(1d, Math.sqrt(directionX * directionX + directionY * directionY));
        double forwardX = directionX / directionLength, forwardY = directionY / directionLength;
        double lateralX = -forwardY, lateralY = forwardX;

        // These are resource opportunities, not a prescribed strategy. Every patch is authored once on the
        // canonical side and point-reflected onto the opponent side with the same shape and tile count.
        Object[][] patches = includeOrePatches ? new Object[][]{
            {Blocks.oreCopper, 9d, -8d, 2}, {Blocks.oreCopper, 17d, 5d, 2},
            {Blocks.oreLead, 9d, 8d, 2}, {Blocks.oreLead, 17d, -5d, 2},
            {Blocks.oreCoal, 17d, -11d, 2}, {Blocks.oreCoal, 24d, 7d, 2},
            {Blocks.sand, 17d, 11d, 2}, {Blocks.sand, 24d, -7d, 2},
            {Blocks.oreTitanium, 25d, -14d, 2}, {Blocks.oreTitanium, 30d, 9d, 2},
            {Blocks.oreThorium, 32d, 15d, 2}
        } : new Object[][]{};
        int authoredResourceTilesPerSide = 0;
        for (Object[] patch : patches) {
            Block resourceBlock = (Block) patch[0];
            double forward = (double) patch[1], lateral = (double) patch[2];
            int radius = (int) patch[3];
            int centerX = (int) Math.round(sourceCore.tileX() + forwardX * forward + lateralX * lateral);
            int centerY = (int) Math.round(sourceCore.tileY() + forwardY * forward + lateralY * lateral);
            authoredResourceTilesPerSide += placeSymmetricResourcePatch(
                centerX, centerY, radius, resourceBlock, sumX, sumY, sourceCore, targetCore
            );
        }

        Map<String, Integer> sourceResources = new LinkedHashMap<>();
        Map<String, Integer> targetResources = new LinkedHashMap<>();
        int comparablePairs = 0, terrainMismatches = 0, resourceMismatches = 0;
        for (int x = 0; x < Vars.world.width(); x++) {
            for (int y = 0; y < Vars.world.height(); y++) {
                Tile source = Vars.world.tile(x, y);
                Tile target = Vars.world.tile(sumX - x, sumY - y);
                if (source == null || target == null || source == target) continue;
                if (distance2(x, y, sourceCore.tileX(), sourceCore.tileY())
                    >= distance2(x, y, targetCore.tileX(), targetCore.tileY())) continue;
                if (source.build != null || target.build != null) continue;
                comparablePairs++;
                String sourceDrop = source.drop() == null ? "none" : source.drop().name;
                String targetDrop = target.drop() == null ? "none" : target.drop().name;
                if (!sourceDrop.equals("none")) sourceResources.merge(sourceDrop, 1, Integer::sum);
                if (!targetDrop.equals("none")) targetResources.merge(targetDrop, 1, Integer::sum);
                if (!sourceDrop.equals(targetDrop)) resourceMismatches++;
                if (source.floor() != target.floor() || source.overlay() != target.overlay()
                    || source.block() != target.block()) terrainMismatches++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applied", true);
        result.put("method", includeOrePatches
            ? "flat_arena_with_point_reflected_resource_patches"
            : strategicBattlefield
                ? "symmetric_three_lane_stockpile_rts_arena_without_mineable_resources"
                : "flat_stockpile_rts_arena_without_mineable_resources");
        result.put("canonical_side", sourceTeam.name);
        result.put("source_core", buildingRef(sourceCore));
        result.put("target_core", buildingRef(targetCore));
        result.put("compact_visible_arena", Map.of(
            "x", arenaX, "y", arenaY, "width", arenaWidth, "height", arenaHeight,
            "aspect_ratio", "56:27", "both_cores_visible_at_max_zoom", true
        ));
        result.put("core_separation_tiles", Math.round(directionLength));
        result.put("coordinate_transform", Map.of("x", sumX + " - x", "y", sumY + " - y"));
        result.put("flat_floor", Blocks.stone.name);
        result.put("flattened_non_core_tiles", flattenedTiles);
        result.put("authored_resource_tiles_per_side", authoredResourceTilesPerSide);
        result.put("comparable_tile_pairs", comparablePairs);
        result.put("terrain_mismatch_pairs_after_copy", terrainMismatches);
        result.put("resource_mismatch_pairs_after_copy", resourceMismatches);
        result.put("source_resource_tiles", sourceResources);
        result.put("target_resource_tiles", targetResources);
        result.put("resource_tile_counts_equal", sourceResources.equals(targetResources));
        result.put("strategic_battlefield", battlefield);
        result.put("note", includeOrePatches
            ? "Ore does not deplete; both sides have point-reflected mineable tiles on an obstacle-free arena."
            : "No mineable resources exist; both sides receive the same abundant stockpile of every non-hidden item and use RTS training queues.");
        return result;
    }

    private Map<String, Object> createSymmetricThreeLaneBattlefield(CoreBuild sourceCore, CoreBuild targetCore) {
        int sumX = sourceCore.tileX() + targetCore.tileX();
        int sumY = sourceCore.tileY() + targetCore.tileY();
        int centerY = sumY / 2;
        int[] laneCenters = {centerY + 28, centerY, centerY - 28};
        int dividerLeft = (sumX - 3) / 2;
        int dividerRight = sumX - dividerLeft;
        int openingHalfHeight = 7;
        int neutralWallTiles = 0;

        // Visually separate the three routes and the two deployment zones without changing movement speed.
        // This makes the strategic topology legible to a human spectator while preserving identical mechanics.
        for (int laneCenter : laneCenters) {
            paintFloorRect(0, laneCenter - openingHalfHeight, Vars.world.width() - 1,
                laneCenter + openingHalfHeight, Blocks.metalFloor);
            paintFloorRect(dividerLeft - 10, laneCenter - 5, dividerRight + 10,
                laneCenter + 5, Blocks.darkPanel1);
        }
        paintFloorRect(sourceCore.tileX() - 12, sourceCore.tileY() - 15,
            sourceCore.tileX() + 12, sourceCore.tileY() + 15, Blocks.metalFloor2);
        paintFloorRect(targetCore.tileX() - 12, targetCore.tileY() - 15,
            targetCore.tileX() + 12, targetCore.tileY() + 15, Blocks.metalFloor2);

        // A broken central ridge creates three crossings. It is not one continuous visual divider:
        // the additional point-reflected cover below creates staging pockets, flank screens and
        // offset firing lines while leaving every lane connected for ground units.
        for (int x = dividerLeft; x <= dividerRight; x++) {
            for (int y = 0; y < Vars.world.height(); y++) {
                boolean laneOpening = false;
                for (int laneCenter : laneCenters) {
                    if (Math.abs(y - laneCenter) <= openingHalfHeight) {
                        laneOpening = true;
                        break;
                    }
                }
                if (laneOpening) continue;
                Tile tile = Vars.world.tile(x, y);
                if (tile == null || tile.build instanceof CoreBuild) continue;
                tile.setBlock(Blocks.stoneWall);
                neutralWallTiles++;
            }
        }

        int tacticalCoverTiles = 0;
        tacticalCoverTiles += placeSymmetricTerrainRect(46, 24, 52, 30, Blocks.stoneWall, sumX, sumY);
        tacticalCoverTiles += placeSymmetricTerrainRect(46, 50, 52, 56, Blocks.stoneWall, sumX, sumY);
        tacticalCoverTiles += placeSymmetricTerrainRect(64, 34, 69, 37, Blocks.stoneWall, sumX, sumY);
        tacticalCoverTiles += placeSymmetricTerrainRect(69, 61, 74, 64, Blocks.stoneWall, sumX, sumY);
        tacticalCoverTiles += placeSymmetricTerrainRect(69, 16, 74, 19, Blocks.stoneWall, sumX, sumY);

        // Small cover pillars around (not on) each objective prevent every engagement from becoming
        // a single unobstructed firing line. The center tile and capture circle remain traversable.
        for (int laneCenter : laneCenters) {
            tacticalCoverTiles += placeTerrainRect(dividerLeft - 8, laneCenter - 5,
                dividerLeft - 6, laneCenter - 2, Blocks.stoneWall);
            tacticalCoverTiles += placeTerrainRect(dividerRight + 6, laneCenter + 2,
                dividerRight + 8, laneCenter + 5, Blocks.stoneWall);
        }

        List<Map<String, Object>> lanes = new ArrayList<>();
        lanes.add(strategicLane(
            "north_industry", "north", dividerLeft, dividerRight, laneCenters[0], openingHalfHeight,
            "wide industrial flank with offset cover and room to stage or rotate before the crossing"
        ));
        lanes.add(strategicLane(
            "center_technology", "center", dividerLeft, dividerRight, laneCenters[1], openingHalfHeight,
            "short direct crossing with staggered central cover; fastest pressure route but exposed to crossfire"
        ));
        lanes.add(strategicLane(
            "south_advanced", "south", dividerLeft, dividerRight, laneCenters[2], openingHalfHeight,
            "wide advanced flank with offset cover and a separate approach from the central firing line"
        ));

        return Map.ofEntries(
            Map.entry("style", "symmetric_three_lane_tactical_showcase_v2"),
            Map.entry("ground_route_choices", 3),
            Map.entry("neutral_divider_x_min", dividerLeft),
            Map.entry("neutral_divider_x_max", dividerRight),
            Map.entry("neutral_wall_tiles", neutralWallTiles),
            Map.entry("tactical_cover_tiles", tacticalCoverTiles),
            Map.entry("wall_behavior", "indestructible neutral terrain that redirects ground units"),
            Map.entry("air_behavior", "flying units ignore the neutral divider and can cross between lanes"),
            Map.entry("lanes", lanes),
            Map.entry("tactical_features", List.of(
                "three visually distinct lane corridors",
                "point-reflected staging pockets and offset firing lines",
                "objective-side cover with open capture centers",
                "open home-side rotation space between lanes"
            )),
            Map.entry("strategy_contract", "lane facts are observations, not a preferred opening, force split, or attack order")
        );
    }

    private void paintFloorRect(int minX, int minY, int maxX, int maxY, Block floor) {
        for (int x = Math.max(0, minX); x <= Math.min(Vars.world.width() - 1, maxX); x++) {
            for (int y = Math.max(0, minY); y <= Math.min(Vars.world.height() - 1, maxY); y++) {
                Tile tile = Vars.world.tile(x, y);
                if (tile != null) tile.setFloor(floor.asFloor());
            }
        }
    }

    private int placeSymmetricTerrainRect(
        int minX, int minY, int maxX, int maxY, Block block, int sumX, int sumY
    ) {
        int placed = placeTerrainRect(minX, minY, maxX, maxY, block);
        placed += placeTerrainRect(sumX - maxX, sumY - maxY, sumX - minX, sumY - minY, block);
        return placed;
    }

    private int placeTerrainRect(int minX, int minY, int maxX, int maxY, Block block) {
        int placed = 0;
        for (int x = Math.max(0, minX); x <= Math.min(Vars.world.width() - 1, maxX); x++) {
            for (int y = Math.max(0, minY); y <= Math.min(Vars.world.height() - 1, maxY); y++) {
                Tile tile = Vars.world.tile(x, y);
                if (tile == null || tile.build instanceof CoreBuild) continue;
                tile.setBlock(block);
                placed++;
            }
        }
        return placed;
    }

    private Map<String, Object> strategicLane(
        String id, String mapSide, int dividerLeft, int dividerRight, int centerY,
        int openingHalfHeight, String characteristic
    ) {
        return Map.of(
            "id", id,
            "map_side", mapSide,
            "objective_id", id,
            "crossing_x_min", dividerLeft,
            "crossing_x_max", dividerRight,
            "crossing_center_y", centerY,
            "crossing_y_min", centerY - openingHalfHeight,
            "crossing_y_max", centerY + openingHalfHeight,
            "ground_characteristic", characteristic,
            "air_access", "unrestricted"
        );
    }

    private void clearStaleTeamBuildingRegistry(Team team) {
        // World.resize removes entities from the old tile grid, but TeamData keeps its cached
        // building/core sequences. If they are not cleared, Team.core() returns an off-map Glacier
        // core and every state coordinate derived from it is wrong.
        var data = team.data();
        data.cores.clear();
        data.buildings.clear();
        data.buildingTypes.clear();
        data.lastCore = null;
        if (data.buildingTree != null) data.buildingTree.clear();
        if (data.turretTree != null) data.turretTree.clear();
    }

    private void applyEqualRtsStockpiles(Team... teams) {
        Map<Item, Integer> stockpile = new LinkedHashMap<>();
        for (Item item : Vars.content.items()) {
            if (!item.hidden) stockpile.put(item, RTS_ABUNDANT_ITEM_AMOUNT);
        }
        for (Team team : teams) {
            CoreBuild core = team.core();
            if (core == null) continue;
            stockpile.forEach((item, amount) -> core.items.set(item, amount));
        }
        Map<String, Integer> named = namedCost(stockpile);
        Map<String, Object> enriched = new LinkedHashMap<>(pvpFairness);
        enriched.put("equal_core_stockpile", named);
        enriched.put("resource_tile_counts_equal", true);
        enriched.put("rts_construction", "each team has one identical sequential construction queue using standard block build times; strategic block choices are unrestricted");
        enriched.put("rts_training", "factory slots consume standard unit-plan item costs from an identical abundant all-item stockpile; production time and slot availability remain binding");
        enriched.put("rts_upgrading", "reconstructors consume standard item costs and time; input units are committed to an upgrade queue without belts, payload conveyors, liquids, or power");
        enriched.put("rts_defense", "item-ammunition turrets and walls use standard construction cost/time; compatible ammunition is transferred from the abundant core stockpile only by an explicit resupply_turrets action");
        enriched.put("resource_scarcity_expected", false);
        enriched.put("all_non_hidden_items_per_core", RTS_ABUNDANT_ITEM_AMOUNT);
        pvpFairness = enriched;
    }

    private void initializeRtsControlPoints(Team sourceTeam, Team targetTeam) {
        rtsControlPoints.clear();
        lastRtsControlUpdateTick = Vars.state.tick;
        CoreBuild source = sourceTeam.core(), target = targetTeam.core();
        if (source == null || target == null) return;
        double centerX = (source.tileX() + target.tileX()) / 2d;
        double centerY = (source.tileY() + target.tileY()) / 2d;
        double forwardX = target.tileX() - source.tileX();
        double forwardY = target.tileY() - source.tileY();
        double length = Math.max(1d, Math.sqrt(forwardX * forwardX + forwardY * forwardY));
        double lateralX = -forwardY / length;
        double lateralY = forwardX / length;
        addRtsControlPoint("north_industry", centerX + lateralX * 28d, centerY + lateralY * 28d);
        addRtsControlPoint("center_technology", centerX, centerY);
        addRtsControlPoint("south_advanced", centerX - lateralX * 28d, centerY - lateralY * 28d);
        Map<String, Object> enriched = new LinkedHashMap<>(pvpFairness);
        enriched.put("strategic_control_points", "three neutral symmetric-access objectives; each owned point grants 10% faster unit training and upgrading plus 1% maximum-health repair per second to friendly combat units inside its radius; control points never directly win the match");
        enriched.put("victory_conditions", List.of("destroy_every_enemy_core"));
        enriched.put("starting_stockpile_is_finite_but_abundant", true);
        pvpFairness = enriched;
    }

    private void addRtsControlPoint(String id, double x, double y) {
        int tileX = Math.max(2, Math.min(Vars.world.width() - 3, (int)Math.round(x)));
        int tileY = Math.max(2, Math.min(Vars.world.height() - 3, (int)Math.round(y)));
        rtsControlPoints.add(new RtsControlPoint(id, tileX, tileY));
    }

    private void updateRtsControlPoints() {
        if (!"stockpile_rts_pvp".equals(gameModeVariant) || Vars.state == null
            || !Vars.state.isGame() || Vars.state.gameOver || rtsControlPoints.isEmpty()) return;
        double elapsedTicks = lastRtsControlUpdateTick <= 0d
            ? 0d : Math.max(0d, Vars.state.tick - lastRtsControlUpdateTick);
        lastRtsControlUpdateTick = Vars.state.tick;
        double radiusWorld = RTS_CONTROL_CAPTURE_RADIUS_TILES * Vars.tilesize;
        for (RtsControlPoint point : rtsControlPoints) {
            Map<Team, Integer> presence = new LinkedHashMap<>();
            for (Team team : configuredAgentTeams()) {
                int count = 0;
                Unit coreDefender = teamAgentState(team).coreDefender;
                for (Unit unit : team.data().units) {
                    if (unit == coreDefender || unit.dead() || !unit.isAdded() || !unit.isCommandable()) continue;
                    if (!unit.type.canAttack || !unit.type.hasWeapons()) continue;
                    float dx = unit.x - point.worldX(), dy = unit.y - point.worldY();
                    if (dx * dx + dy * dy <= radiusWorld * radiusWorld) count++;
                }
                if (count > 0) presence.put(team, count);
            }
            point.lastPresence.clear();
            presence.forEach((team, count) -> point.lastPresence.put(team.id, count));
            point.contested = presence.size() > 1;
            if (presence.size() != 1) {
                if (point.owner == null && presence.isEmpty() && point.captureProgress > 0d) {
                    point.captureProgress = Math.max(0d, point.captureProgress - 0.5d / (RTS_CONTROL_CAPTURE_SECONDS * 60d));
                    if (point.captureProgress == 0d) point.capturingTeam = null;
                }
                continue;
            }
            Map.Entry<Team, Integer> occupant = presence.entrySet().iterator().next();
            Team team = occupant.getKey();
            if (point.owner == team) {
                point.capturingTeam = null;
                point.captureProgress = 0d;
                continue;
            }
            if (point.capturingTeam != team) {
                point.capturingTeam = team;
                point.captureProgress = 0d;
            }
            double strength = Math.min(1.5d, 1d + Math.max(0, occupant.getValue() - 1) * 0.25d);
            point.captureProgress = Math.min(1d,
                point.captureProgress + strength / (RTS_CONTROL_CAPTURE_SECONDS * 60d));
            if (point.captureProgress >= 1d) {
                point.owner = team;
                point.capturingTeam = null;
                point.captureProgress = 0d;
                point.captures++;
                Log.info("[LLM bridge] @ captured RTS control point @", team.name, point.id);
            }
        }
        if (elapsedTicks <= 0d) return;
        double repairFraction = RTS_CONTROL_REPAIR_MAX_HEALTH_PER_SECOND * elapsedTicks / 60d;
        for (RtsControlPoint point : rtsControlPoints) {
            if (point.owner == null || point.contested || point.capturingTeam != null) continue;
            for (Unit unit : point.owner.data().units) {
                if (unit.dead() || !unit.isAdded() || !unit.type.canAttack || !unit.type.hasWeapons()
                    || unit.health >= unit.maxHealth) continue;
                float dx = unit.x - point.worldX(), dy = unit.y - point.worldY();
                if (dx * dx + dy * dy <= radiusWorld * radiusWorld) {
                    unit.heal((float)(unit.maxHealth * repairFraction));
                }
            }
        }
    }

    private List<Map<String, Object>> rtsControlPointState(Team observer) {
        if (!"stockpile_rts_pvp".equals(gameModeVariant)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (RtsControlPoint point : rtsControlPoints) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", point.id);
            row.put("x", point.x);
            row.put("y", point.y);
            row.put("capture_radius_tiles", RTS_CONTROL_CAPTURE_RADIUS_TILES);
            row.put("owner", point.owner == null ? "neutral" : point.owner.name);
            row.put("capturing_team", point.capturingTeam == null ? null : point.capturingTeam.name);
            row.put("capture_progress", point.captureProgress);
            row.put("contested", point.contested);
            row.put("benefit_active", point.owner != null && !point.contested && point.capturingTeam == null);
            int capturingPresence = point.capturingTeam == null
                ? 0 : point.lastPresence.getOrDefault(point.capturingTeam.id, 0);
            row.put("capture_speed_multiplier", capturingPresence > 0
                ? Math.min(1.5d, 1d + Math.max(0, capturingPresence - 1) * 0.25d) : 0d);
            Map<String, Integer> presence = new LinkedHashMap<>();
            for (Team team : configuredAgentTeams()) {
                presence.put(team.name, point.lastPresence.getOrDefault(team.id, 0));
            }
            row.put("combat_unit_presence", presence);
            row.put("captures", point.captures);
            row.put("direct_victory_effect", false);
            row.put("production_and_upgrade_speed_bonus_fraction", RTS_CONTROL_PRODUCTION_SPEED_BONUS_PER_POINT);
            row.put("friendly_unit_repair_max_health_fraction_per_second_in_radius",
                RTS_CONTROL_REPAIR_MAX_HEALTH_PER_SECOND);
            CoreBuild ownCore = observer.core();
            row.put("distance_from_own_core_tiles", ownCore == null ? null : Math.sqrt(
                distance2(point.x, point.y, ownCore.tileX(), ownCore.tileY())));
            result.add(row);
        }
        return result;
    }

    private Map<String, Object> rtsControlBenefitState(Team observer) {
        if (!"stockpile_rts_pvp".equals(gameModeVariant)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("direct_victory_effect", false);
        result.put("victory_condition", "destroy_every_enemy_core");
        result.put("production_and_upgrade_speed_bonus_per_owned_point_fraction",
            RTS_CONTROL_PRODUCTION_SPEED_BONUS_PER_POINT);
        result.put("maximum_production_and_upgrade_speed_bonus_fraction",
            RTS_CONTROL_PRODUCTION_SPEED_BONUS_PER_POINT * rtsControlPoints.size());
        result.put("friendly_unit_repair_max_health_fraction_per_second_in_owned_radius",
            RTS_CONTROL_REPAIR_MAX_HEALTH_PER_SECOND);
        result.put("owned_points", ownedRtsControlPointCount(observer));
        result.put("active_benefit_points", activeRtsControlPointCount(observer));
        result.put("contested_owned_points_suspend_benefits", true);
        result.put("current_production_and_upgrade_speed_multiplier", rtsProductionSpeedMultiplier(observer));
        Map<String, Object> teams = new LinkedHashMap<>();
        for (Team team : configuredAgentTeams()) {
            teams.put(team.name, Map.of(
                "owned_points", ownedRtsControlPointCount(team),
                "active_benefit_points", activeRtsControlPointCount(team),
                "production_and_upgrade_speed_multiplier", rtsProductionSpeedMultiplier(team)
            ));
        }
        result.put("teams", teams);
        return result;
    }

    private int ownedRtsControlPointCount(Team team) {
        int count = 0;
        for (RtsControlPoint point : rtsControlPoints) if (point.owner == team) count++;
        return count;
    }

    private int activeRtsControlPointCount(Team team) {
        int count = 0;
        for (RtsControlPoint point : rtsControlPoints) {
            if (point.owner == team && !point.contested && point.capturingTeam == null) count++;
        }
        return count;
    }

    private double rtsProductionSpeedMultiplier(Team team) {
        return 1d + activeRtsControlPointCount(team) * RTS_CONTROL_PRODUCTION_SPEED_BONUS_PER_POINT;
    }

    private void updateRtsControlPointVisuals() {
        if (!"stockpile_rts_pvp".equals(gameModeVariant) || Vars.state == null
            || !Vars.state.isGame() || Vars.state.gameOver || rtsControlPoints.isEmpty()) return;
        if (Vars.state.tick < nextRtsControlLabelTick) return;
        nextRtsControlLabelTick = Vars.state.tick + 30d;
        for (RtsControlPoint point : rtsControlPoints) {
            StringBuilder label = new StringBuilder("[accent]◆ ")
                .append(controlPointDisplayName(point.id)).append("[]");
            if (point.contested) {
                label.append("\n[scarlet]교전 중[]");
            } else if (point.capturingTeam != null) {
                label.append("\n").append(teamMarkup(point.capturingTeam))
                    .append(" 점령 ").append(progressBar(point.captureProgress)).append(' ')
                    .append(String.format(Locale.ROOT, "%.0f%%", point.captureProgress * 100d)).append("[]");
            } else if (point.owner != null) {
                label.append("\n").append(teamMarkup(point.owner)).append(" 점령[]");
            } else {
                label.append("\n[lightgray]중립 거점[]");
            }
            Call.label(label.toString(), 0.75f, point.worldX(), point.worldY() + Vars.tilesize * 2f);
        }
    }

    private String controlPointDisplayName(String id) {
        return switch (id) {
            case "north_industry" -> "북부 산업";
            case "center_technology" -> "중앙 기술";
            case "south_advanced" -> "남부 고급";
            default -> id;
        };
    }

    private String teamMarkup(Team team) {
        if (team == Team.sharded) return "[sky]sharded";
        if (team == Team.crux) return "[scarlet]crux";
        return "[#" + team.color + "]" + team.name;
    }

    private String progressBar(double progress) {
        int filled = Math.max(0, Math.min(8, (int)Math.round(progress * 8d)));
        return "■".repeat(filled) + "□".repeat(8 - filled);
    }

    private int placeSymmetricResourcePatch(int centerX, int centerY, int radius, Block resourceBlock,
                                            int sumX, int sumY, CoreBuild sourceCore, CoreBuild targetCore) {
        int placedPerSide = 0;
        for (int offsetX = -radius; offsetX <= radius; offsetX++) {
            for (int offsetY = -radius; offsetY <= radius; offsetY++) {
                if (offsetX * offsetX + offsetY * offsetY > radius * radius + 1) continue;
                int sourceX = centerX + offsetX, sourceY = centerY + offsetY;
                int targetX = sumX - sourceX, targetY = sumY - sourceY;
                Tile source = Vars.world.tile(sourceX, sourceY);
                Tile target = Vars.world.tile(targetX, targetY);
                if (source == null || target == null || source == target) continue;
                if (source.build instanceof CoreBuild || target.build instanceof CoreBuild) continue;
                if (distance2(sourceX, sourceY, sourceCore.tileX(), sourceCore.tileY()) <= 16
                    || distance2(targetX, targetY, targetCore.tileX(), targetCore.tileY()) <= 16) continue;

                if (resourceBlock == Blocks.sand) {
                    source.setFloor(Blocks.sand.asFloor());
                    target.setFloor(Blocks.sand.asFloor());
                    source.setOverlay(Blocks.air);
                    target.setOverlay(Blocks.air);
                } else {
                    if (Vars.indexer != null) {
                        Vars.indexer.removeIndex(source);
                        Vars.indexer.removeIndex(target);
                    }
                    source.setOverlay(resourceBlock);
                    target.setOverlay(resourceBlock);
                    if (Vars.indexer != null) {
                        Vars.indexer.addIndex(source);
                        Vars.indexer.addIndex(target);
                    }
                }
                placedPerSide++;
            }
        }
        return placedPerSide;
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        Team team = authorizeTeam(exchange);
        if (team == null) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "method_not_allowed", "Use GET.");
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("service", "mindustry-llm-bridge");
        body.put("version", "0.1.0");
        body.put("episode_id", episodeId);
        body.put("game_loaded", Vars.state != null && Vars.state.isGame());
        body.put("auto_core_defense", autoCoreDefense);
        body.put("controlled_team", team.name);
        body.put("configured_agent_teams", configuredAgentTeams().stream().map(value -> value.name).toList());
        body.put("agent_process", activeAgentStatus());
        sendJson(exchange, 200, body);
    }

    private void handleState(HttpExchange exchange) throws IOException {
        Team team = authorizeTeam(exchange);
        if (team == null) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "method_not_allowed", "Use GET.");
            return;
        }
        try {
            sendJson(exchange, 200, onGameThread(() -> withControlledTeam(team, this::snapshot)));
        } catch (Exception error) {
            Log.err(error);
            sendError(exchange, 503, "state_unavailable", error.getMessage());
        }
    }

    private void handleAction(HttpExchange exchange) throws IOException {
        Team team = authorizeTeam(exchange);
        if (team == null) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "method_not_allowed", "Use POST.");
            return;
        }
        try {
            String raw = readBody(exchange);
            JsonObject request = JsonParser.parseString(raw).getAsJsonObject();
            Map<String, Object> response = onGameThread(() ->
                withControlledTeam(team, () -> applyActions(request)));
            sendJson(exchange, 200, response);
        } catch (StaleEpisodeException error) {
            sendError(exchange, 409, "stale_episode", error.getMessage());
        } catch (IllegalArgumentException error) {
            sendError(exchange, 400, "invalid_request", error.getMessage());
        } catch (Exception error) {
            Log.err(error);
            sendError(exchange, 503, "action_failed", error.getMessage());
        }
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", 2);
        root.put("episode_id", episodeId);
        if (!previousEpisodeResult.isEmpty()) root.put("previous_episode_result", previousEpisodeResult);
        root.put("observed_at", Instant.now().toString());
        Team team = controlledTeam();
        String teamResult = !Vars.state.gameOver ? "running"
            : gameWinner == team ? "won" : "lost";
        root.put("result", teamResult);
        root.put("self_team", Map.of("id", team.id, "name", team.name, "color", team.color.toString()));
        boolean waveMode = Vars.state != null && Vars.state.rules.waves && !Vars.state.rules.pvp;
        root.put("waves_seen", waveMode ? wavesSeen.get() : 0);

        if (Vars.state == null || !Vars.state.isGame()) {
            root.put("playing", false);
            root.put("message", "No map is loaded. Use the server host command first.");
            return root;
        }

        root.put("playing", Vars.state.isPlaying());
        root.put("paused", Vars.state.isPaused());
        root.put("game_over", Vars.state.gameOver);
        root.put("won", gameWinner == team);
        root.put("tick", Vars.state.tick);
        root.put("wave", waveMode ? Vars.state.wave : null);
        root.put("wave_time_remaining_seconds", waveMode ? Math.max(0f, Vars.state.wavetime / 60f) : null);
        root.put("map", Map.of(
            "name", Vars.state.map == null ? "unknown" : Vars.state.map.name(),
            "width", Vars.world.width(),
            "height", Vars.world.height()
        ));
        root.put("rules", Map.of(
            "waves", Vars.state.rules.waves,
            "pvp", Vars.state.rules.pvp,
            "pvp_auto_pause", Vars.state.rules.pvpAutoPause,
            "pause_disabled", Vars.state.rules.pauseDisabled,
            "attack_mode", Vars.state.rules.attackMode,
            "win_wave", Vars.state.rules.winWave,
            "infinite_resources", Vars.state.rules.infiniteResources,
            "build_cost_multiplier", Vars.state.rules.buildCostMultiplier
        ));
        root.put("game_mode_variant", Map.of(
            "id", gameModeVariant,
            "stockpile_rts", "stockpile_rts_pvp".equals(gameModeVariant),
            "economy_and_logistics_required", !"stockpile_rts_pvp".equals(gameModeVariant),
            "decision_refresh_seconds", "stockpile_rts_pvp".equals(gameModeVariant) ? 0.5 : -1.0,
            "construction_mechanic", "stockpile_rts_pvp".equals(gameModeVariant)
                ? "place reserves its standard core cost immediately and enters one sequential standard-build-time queue per team"
                : "direct placement",
            "unit_training_mechanic", "stockpile_rts_pvp".equals(gameModeVariant)
                ? "build unit factories and reconstructors; train_units and upgrade_units enforce standard item costs and production times against the core stockpile"
                : "native Mindustry factory inputs and power",
            "defense_mechanic", "stockpile_rts_pvp".equals(gameModeVariant)
                ? "item turrets and walls use standard construction cost/time; resupply_turrets transfers chosen compatible ammunition from the abundant core stockpile without belts"
                : "native Mindustry turret supply and wall mechanics",
            "territory_mechanic", "stockpile_rts_pvp".equals(gameModeVariant)
                ? "combat units capture neutral map objectives; each owned point grants 10% faster unit training/upgrading and repairs friendly combat units inside its radius; control points do not directly win"
                : "none"
        ));
        if ("stockpile_rts_pvp".equals(gameModeVariant)) {
            root.put("victory_conditions", List.of("destroy_every_enemy_core"));
        }
        root.put("pvp_fairness", pvpFairness);
        root.put("rts_battlefield", pvpFairness.getOrDefault("strategic_battlefield", Map.of()));

        CoreBuild core = team.core();
        root.put("core", core == null ? null : coreState(core));
        root.put("cores", coreStates(team));
        root.put("buildings", buildingState(team));
        root.put("power_networks", powerNetworkState(team));
        root.put("power_node_topology", powerNodeTopology(team));
        root.put("production_summary", productionSummary(team));
        root.put("rts_construction_queue", rtsConstructionQueueState(team));
        root.put("rts_training_queues", rtsTrainingQueueState(team));
        root.put("rts_upgrade_queues", rtsUpgradeQueueState(team));
        root.put("rts_control_points", rtsControlPointState(team));
        root.put("rts_control_benefits", rtsControlBenefitState(team));
        root.put("rts_squads", rtsSquadState(team));
        root.put("recent_unit_command_receipts", recentUnitCommandReceipts(team));
        root.put("recent_combat_losses", recentCombatLossState(team));
        root.put("friendly_units", unitState(team, false));
        root.put("enemy_units", unitState(team, true));
        root.put("enemy_teams", enemyTeamState(team));
        root.put("enemy_spawns", spawnState());
        root.put("wave_forecast", waveMode ? waveForecast() : Map.of(
            "meaning", "No scripted waves are active in this PvP match; opposing teams are the only combat threat.",
            "waves", List.of()
        ));
        root.put("threat_summary", threatSummary(core));
        root.put("defense_analysis", defenseAnalysis(core));
        root.put("logistics_analysis", logisticsAnalysis(team));
        root.put("nearby_ores", oreState(core));
        root.put("core_defender", coreDefenderState());
        root.put("defense_supply", defenseSupplyState(core));
        root.put("valid_placement_samples", placementHints(core));
        root.put("available_blocks", availableBlocks());
        root.put("action_contract", Map.of(
            "endpoint", "POST /v1/action",
            "max_actions", MAX_ACTIONS_PER_REQUEST,
            "coordinates", "integer tile coordinates",
            "placement", "stockpile_rts_pvp".equals(gameModeVariant)
                ? "server-validated coordinates; standard core cost is reserved immediately and each team executes one sequential standard-build-time queue"
                : "server-validated strategic macros and direct placement; standard validity and core costs enforced",
            "shape", Map.of(
                "request_id", "string",
                "expected_episode_id", episodeId,
                "observed_tick", Vars.state.tick,
                "actions", List.of(
                Map.of("type", "build_mine_to_core", "resource", "copper", "drill", "mechanical-drill", "transport", "conveyor", "max_cost", 80, "reserve_copper", 40),
                Map.of("type", "build_mine_to_target", "resource", "coal", "target_x", 10, "target_y", 20, "drill", "mechanical-drill", "transport", "conveyor", "max_cost", 120),
                Map.of("type", "resupply_turrets", "ammo", "copper", "max_items", 30, "reserve_copper", 40, "below_fraction", 0.5),
                Map.of("type", "command_core_unit", "mode", "mine", "resource", "copper"),
                Map.of("type", "command_core_unit", "mode", "supply", "resource", "sand", "target_x", 30, "target_y", 40),
                Map.of("type", "place", "block", "duo", "x", 10, "y", 20, "rotation", 0),
                Map.of("type", "inspect_item_route", "source_x", 10, "source_y", 20,
                    "target_x", 13, "target_y", 21, "transport", "conveyor", "margin", 6),
                Map.of(
                    "type", "preview_conveyor_path", "source_x", 10, "source_y", 20,
                    "target_x", 13, "target_y", 21, "transport", "conveyor",
                    "placements", List.of(
                        Map.of("x", 11, "y", 20, "rotation", 0),
                        Map.of("x", 12, "y", 20, "rotation", 1),
                        Map.of("x", 12, "y", 21, "rotation", 0)
                    ),
                    "allowed_items", List.of("coal"), "max_cost", 100, "reserve_copper", 40
                ),
                Map.ofEntries(
                    Map.entry("type", "place_conveyor_path"), Map.entry("source_x", 10), Map.entry("source_y", 20),
                    Map.entry("target_x", 13), Map.entry("target_y", 21), Map.entry("transport", "conveyor"),
                    Map.entry("placements", List.of(
                        Map.of("x", 11, "y", 20, "rotation", 0),
                        Map.of("x", 12, "y", 20, "rotation", 1),
                        Map.of("x", 12, "y", 21, "rotation", 0)
                    )),
                    Map.entry("allowed_items", List.of("coal")), Map.entry("preview_token", "returned-preview-token"),
                    Map.entry("max_cost", 100), Map.entry("reserve_copper", 40)
                ),
                Map.of("type", "configure_item_filter", "x", 10, "y", 20, "item", "coal"),
                Map.of("type", "remove", "x", 10, "y", 20),
                Map.of("type", "route_items", "source_x", 10, "source_y", 20, "target_x", 30, "target_y", 40, "transport", "conveyor", "max_cost", 100, "reserve_copper", 40),
                Map.of("type", "route_items_isolated", "resource", "coal", "source_x", 10, "source_y", 20, "target_x", 30, "target_y", 40, "transport", "conveyor", "max_cost", 100, "reserve_copper", 40),
                Map.of("type", "clear_item_input_network", "target_x", 30, "target_y", 40, "max_segments", 160),
                Map.of("type", "upgrade_input_network", "target_x", 30, "target_y", 40, "transport", "titanium-conveyor", "max_cost", 300, "reserve_copper", 40),
                Map.of("type", "connect_power", "node_x", 10, "node_y", 20, "target_x", 30, "target_y", 40),
                Map.of("type", "set_unit_factory_plan", "x", 10, "y", 20, "unit", "dagger"),
                Map.ofEntries(
                    Map.entry("type", "train_units"), Map.entry("x", 10), Map.entry("y", 20),
                    Map.entry("unit", "dagger"), Map.entry("count", 5),
                    Map.entry("rally_x", 18), Map.entry("rally_y", 22), Map.entry("rally_radius", 4),
                    Map.entry("squad_id", "alpha")
                ),
                Map.of("type", "upgrade_units", "x", 15, "y", 20, "from_unit", "dagger", "to_unit", "mace", "count", 5),
                Map.ofEntries(
                    Map.entry("type", "command_units"), Map.entry("unit", "dagger"),
                    Map.entry("squad_id", "alpha"), Map.entry("mode", "attack_move"),
                    Map.entry("target_x", 30), Map.entry("target_y", 40),
                    Map.entry("target_radius", 2), Map.entry("engagement_radius", 14),
                    Map.entry("max_units", 10)
                )
            ))
        ));
        return root;
    }

    private void updateCoreDefender() {
        if (!autoCoreDefense || Vars.state == null || !Vars.state.isGame() || Vars.state.gameOver) return;
        for (Team team : configuredAgentTeams()) updateCoreDefender(team);
    }

    private void updateCoreDefender(Team team) {
        CoreBuild core = team.core();
        if (core == null) return;
        TeamAgentState state = teamAgentState(team);
        if (state.coreDefender != null && (state.coreDefender.dead() || !state.coreDefender.isAdded())) {
            state.coreDefender = null;
            state.coreDefenderRespawnTick = Vars.state.tick
                + Math.max(Player.deathDelay, CORE_DEFENDER_RESPAWN_DELAY_TICKS);
        }
        if (state.coreDefender != null || Vars.state.tick < state.coreDefenderRespawnTick) return;
        if (!(core.block instanceof CoreBlock coreBlock) || coreBlock.unitType == null) return;

        Unit defender = coreBlock.unitType.create(team);
        defender.set(core.x, core.y);
        defender.spawnedByCore(false);
        applyCoreUnitTask(defender, state);
        defender.add();
        state.coreDefender = defender;
        Log.info("[LLM bridge] spawned automatic core defender for @: @", team.name, defender.type.name);
    }

    private static final class CoreDefenseAI extends AIController {
        @Override
        public void updateTargeting() {
            target = Units.closestTarget(unit.team, unit.x, unit.y, unit.range());
            boolean shoot = target != null && !Units.invalidateTarget(target, unit, unit.range());
            if (shoot) {
                unit.aim(target);
                unit.lookAt(target);
            }
            unit.controlWeapons(true, shoot);
        }

        @Override
        public void updateMovement() {
            CoreBuild core = unit.closestCore();
            if (core != null) {
                float guardRadius = core.block.size * Vars.tilesize / 2f + 24f;
                moveTo(core, guardRadius, 20f);
            }
            Teamc currentTarget = target;
            if (currentTarget != null) unit.lookAt(currentTarget);
        }
    }

    private static final class CoreUnitIdleAI extends AIController {
        @Override
        public void updateTargeting() {
            target = null;
            unit.controlWeapons(true, false);
        }

        @Override
        public void updateMovement() {
            // Intentionally idle until the strategic agent assigns a task.
        }
    }

    private static final class CoreUnitInterceptAI extends AIController {
        @Override
        public void updateTargeting() {
            target = Units.closestTarget(unit.team, unit.x, unit.y, Float.MAX_VALUE);
            boolean shoot = target != null && !Units.invalidateTarget(target, unit, unit.range());
            if (target != null) {
                unit.aim(target);
                unit.lookAt(target);
            }
            unit.controlWeapons(true, shoot);
        }

        @Override
        public void updateMovement() {
            if (target != null) moveTo(target, Math.max(8f, unit.range() * 0.75f), 12f);
        }
    }

    private static final class FixedResourceMinerAI extends MinerAI {
        private final Item assignedResource;

        private FixedResourceMinerAI(Item assignedResource) {
            this.assignedResource = assignedResource;
            targetItem = assignedResource;
        }

        @Override
        public void updateMovement() {
            targetItem = assignedResource;
            super.updateMovement();
            if (targetItem != assignedResource) {
                targetItem = assignedResource;
                ore = null;
                unit.mineTile(null);
            }
        }
    }

    private static final class CoreUnitSupplyAI extends AIController {
        private final Item resource;
        private final int targetX;
        private final int targetY;
        private final TeamAgentState agentState;

        private CoreUnitSupplyAI(Item resource, int targetX, int targetY, TeamAgentState agentState) {
            this.resource = resource;
            this.targetX = targetX;
            this.targetY = targetY;
            this.agentState = agentState;
        }

        @Override
        public void updateTargeting() {
            target = Units.closestTarget(unit.team, unit.x, unit.y, unit.range());
            boolean shoot = target != null && !Units.invalidateTarget(target, unit, unit.range());
            if (shoot) unit.aim(target);
            unit.controlWeapons(true, shoot);
        }

        @Override
        public void updateMovement() {
            CoreBuild core = unit.closestCore();
            Building destination = Vars.world.build(targetX, targetY);
            if (core == null || destination == null || destination.team != unit.team) return;
            boolean carryingAssigned = unit.stack.amount > 0 && unit.stack.item == resource;
            if (!carryingAssigned) {
                float radius = core.block.size * Vars.tilesize / 2f + 6f;
                moveTo(core, radius, 12f);
                if (!unit.within(core, radius + 3f)) return;
                if (unit.stack.amount > 0 && unit.stack.item != null) {
                    core.items.add(unit.stack.item, unit.stack.amount);
                    unit.stack.set(null, 0);
                }
                int available = core.items.get(resource);
                int amount = Math.min(unit.type.itemCapacity, available);
                if (amount > 0) {
                    core.items.remove(resource, amount);
                    unit.stack.set(resource, amount);
                }
                return;
            }
            float radius = destination.block.size * Vars.tilesize / 2f + 5f;
            moveTo(destination, radius, 12f);
            if (!unit.within(destination, radius + 3f)) return;
            int accepted = destination.acceptStack(resource, unit.stack.amount, unit);
            if (accepted > 0) {
                destination.handleStack(resource, accepted, unit);
                unit.stack.amount -= accepted;
                agentState.coreUnitDeliveredItems += accepted;
                if (unit.stack.amount <= 0) {
                    agentState.coreUnitDeliveryTrips++;
                    unit.stack.set(null, 0);
                }
            }
        }
    }

    private void applyCoreUnitTask(Unit unit, TeamAgentState state) {
        switch (state.coreUnitTask) {
            case "defend_core" -> unit.controller(new CoreDefenseAI());
            case "intercept" -> unit.controller(new CoreUnitInterceptAI());
            case "repair" -> unit.controller(new RepairAI());
            case "rebuild" -> unit.controller(new BuilderAI());
            case "supply" -> {
                Item resource = findItem(state.coreUnitResource);
                Building target = Vars.world.build(state.coreUnitTargetX, state.coreUnitTargetY);
                if (resource == null || target == null || target.team != unit.team) {
                    state.coreUnitTask = "idle";
                    state.coreUnitResource = "";
                    unit.controller(new CoreUnitIdleAI());
                } else {
                    unit.controller(new CoreUnitSupplyAI(resource, state.coreUnitTargetX, state.coreUnitTargetY, state));
                }
            }
            case "mine" -> {
                Item resource = findItem(state.coreUnitResource);
                if (resource == null || !unit.canMine(resource) || !resourceAvailableForMining(resource)) {
                    state.coreUnitTask = "idle";
                    state.coreUnitResource = "";
                    unit.controller(new CoreUnitIdleAI());
                } else {
                    MinerAI miner = new FixedResourceMinerAI(resource);
                    unit.controller(miner);
                    miner.targetItem = resource;
                }
            }
            default -> unit.controller(new CoreUnitIdleAI());
        }
    }

    private Map<String, Object> coreDefenderState() {
        TeamAgentState state = teamAgentState(controlledTeam());
        Unit coreDefender = state.coreDefender;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("enabled", autoCoreDefense);
        value.put("active", coreDefender != null && coreDefender.isAdded() && !coreDefender.dead());
        value.put("respawn_delay_seconds", CORE_DEFENDER_RESPAWN_DELAY_TICKS / 60d);
        value.put("respawn_seconds_remaining", coreDefender == null
            ? Math.max(0d, state.coreDefenderRespawnTick - Vars.state.tick) / 60d : 0d);
        if (coreDefender != null) {
            value.put("id", coreDefender.id);
            value.put("type", coreDefender.type.name);
            value.put("task", state.coreUnitTask);
            value.put("task_resource", state.coreUnitResource.isEmpty() ? null : state.coreUnitResource);
            if ("supply".equals(state.coreUnitTask)) {
                value.put("task_target", Map.of("x", state.coreUnitTargetX, "y", state.coreUnitTargetY));
            }
            value.put("x", coreDefender.x / Vars.tilesize);
            value.put("y", coreDefender.y / Vars.tilesize);
            value.put("health", coreDefender.health);
            value.put("max_health", coreDefender.maxHealth);
            value.put("weapon_range_tiles", coreDefender.range() / Vars.tilesize);
            value.put("can_mine", coreDefender.canMine());
            value.put("can_build", coreDefender.canBuild());
            value.put("build_speed", coreDefender.type.buildSpeed);
            value.put("build_range_tiles", coreDefender.type.buildRange / Vars.tilesize);
            value.put("item_capacity", coreDefender.type.itemCapacity);
            value.put("carried_item", coreDefender.stack.item == null ? null : coreDefender.stack.item.name);
            value.put("carried_amount", coreDefender.stack.amount);
            value.put("task_age_seconds", Math.max(0d, Vars.state.tick - state.coreUnitTaskAssignedTick) / 60d);
            Map<String, Object> progress = new LinkedHashMap<>();
            if ("supply".equals(state.coreUnitTask)) {
                boolean carryingAssigned = coreDefender.stack.amount > 0
                    && coreDefender.stack.item != null
                    && coreDefender.stack.item.name.equals(state.coreUnitResource);
                progress.put("phase", carryingAssigned ? "carrying_resource_to_target" : "returning_to_core_or_loading");
                progress.put("items_delivered_since_assignment", state.coreUnitDeliveredItems);
                progress.put("completed_delivery_trips", state.coreUnitDeliveryTrips);
                Building destination = Vars.world.build(state.coreUnitTargetX, state.coreUnitTargetY);
                progress.put("target_still_exists", destination != null && destination.team == controlledTeam());
            } else if ("mine".equals(state.coreUnitTask)) {
                boolean carryingAssigned = coreDefender.stack.amount > 0
                    && coreDefender.stack.item != null
                    && coreDefender.stack.item.name.equals(state.coreUnitResource);
                progress.put("phase", carryingAssigned ? "carrying_mined_resource_or_returning_to_core" : "seeking_or_extracting_resource");
                CoreBuild core = controlledTeam().core();
                Item resource = findItem(state.coreUnitResource);
                if (core != null && resource != null) {
                    progress.put("net_core_inventory_change_since_assignment",
                        core.items.get(resource) - state.coreUnitInitialCoreItemCount);
                }
            } else {
                progress.put("phase", "task_active");
            }
            value.put("task_progress", progress);
            List<String> mineable = new ArrayList<>();
            for (Item item : Vars.content.items()) {
                if (coreDefender.canMine(item) && resourceAvailableForMining(item)) mineable.add(item.name);
            }
            value.put("mineable_resources", mineable);
        }
        value.put("current_task_effect", coreUnitTaskEffect(state.coreUnitTask));
        value.put("available_task_effects", Map.of(
            "idle", coreUnitTaskEffect("idle"),
            "mine", coreUnitTaskEffect("mine"),
            "defend_core", coreUnitTaskEffect("defend_core"),
            "intercept", coreUnitTaskEffect("intercept"),
            "supply", coreUnitTaskEffect("supply"),
            "repair", coreUnitTaskEffect("repair"),
            "rebuild", coreUnitTaskEffect("rebuild")
        ));
        return value;
    }

    private Map<String, Object> coreUnitTaskEffect(String task) {
        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("engages_enemies", Set.of("defend_core", "intercept").contains(task));
        effect.put("pursues_enemies", "intercept".equals(task));
        effect.put("guards_near_core", "defend_core".equals(task));
        effect.put("repairs_surviving_buildings", "repair".equals(task));
        effect.put("rebuilds_destroyed_plans", "rebuild".equals(task));
        effect.put("mines_resource", "mine".equals(task));
        effect.put("supplies_selected_building_from_core", "supply".equals(task));
        if (Set.of("idle", "mine", "repair", "rebuild").contains(task)) {
            effect.put("combat_note", "This task does not deliberately acquire or engage enemy targets.");
        }
        return effect;
    }

    private Map<String, Object> defenseSupplyState(CoreBuild core) {
        List<Map<String, Object>> turrets = new ArrayList<>();
        int emptyTurrets = 0;
        for (Building building : controlledTeam().data().buildings) {
            if (!(building instanceof ItemTurretBuild turret)) continue;
            float fraction = turret.getAmmoFraction();
            if (fraction <= 0.001f) emptyTurrets++;
            turrets.add(Map.of(
                "block", turret.block.name,
                "x", turret.tileX(),
                "y", turret.tileY(),
                "ammo_fraction", fraction,
                "compatible_ammo", compatibleAmmo(turret)
            ));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("controlled_by", "llm_action_only");
        result.put("turrets_total", turrets.size());
        result.put("empty_turrets", emptyTurrets);
        result.put("turrets", turrets);
        result.put("core_items", core == null ? Map.of() : coreState(core).get("items"));
        return result;
    }

    private List<String> compatibleAmmo(ItemTurretBuild turret) {
        List<String> result = new ArrayList<>();
        if (!(turret.block instanceof ItemTurret itemTurret)) return result;
        for (Item item : Vars.content.items()) if (itemTurret.ammoTypes.containsKey(item)) result.add(item.name);
        return result;
    }

    private List<String> compatibleAmmo(ItemTurret turret) {
        List<String> result = new ArrayList<>();
        for (Item item : Vars.content.items()) if (turret.ammoTypes.containsKey(item)) result.add(item.name);
        return result;
    }

    private Map<String, Object> coreState(CoreBuild core) {
        Map<String, Integer> items = new LinkedHashMap<>();
        for (Item item : Vars.content.items()) {
            int amount = core.items.get(item);
            if (amount > 0) items.put(item.name, amount);
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("x", core.tileX());
        value.put("y", core.tileY());
        value.put("size", core.block.size);
        value.put("footprint", footprintState(core.block, core.tileX(), core.tileY()));
        value.put("health", core.health);
        value.put("max_health", core.maxHealth);
        value.put("items", items);
        return value;
    }

    private List<Map<String, Object>> buildingState(Team team) {
        List<Map<String, Object>> result = new ArrayList<>();
        int count = 0;
        for (Building building : team.data().buildings) {
            if (count++ >= MAX_BUILDINGS_IN_STATE) break;
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("block", building.block.name);
            value.put("x", building.tileX());
            value.put("y", building.tileY());
            value.put("rotation", building.rotation);
            value.put("health", building.health);
            value.put("max_health", building.maxHealth);
            value.put("enabled", building.enabled);
            value.put("efficiency", building.efficiency);
            value.put("status", building.status().name());
            value.put("should_consume", building.shouldConsume());
            value.put("production_valid", building.productionValid());
            Map<String, Integer> storedItems = new LinkedHashMap<>();
            if (building.items != null) {
                for (Item item : Vars.content.items()) {
                    int amount = building.items.get(item);
                    if (amount > 0) storedItems.put(item.name, amount);
                }
            }
            if (!storedItems.isEmpty()) value.put("items", storedItems);
            if (building.block.hasItems) value.put("item_capacity", building.block.itemCapacity);
            if (building.liquids != null) {
                Map<String, Float> storedLiquids = new LinkedHashMap<>();
                for (Liquid liquid : Vars.content.liquids()) {
                    float amount = building.liquids.get(liquid);
                    if (amount > 0.001f) storedLiquids.put(liquid.name, amount);
                }
                if (!storedLiquids.isEmpty()) value.put("liquids", storedLiquids);
                value.put("liquid_capacity", building.block.liquidCapacity);
            }
            if (building.power != null && building.power.graph != null) {
                value.put("power", Map.of(
                    "network_id", building.power.graph.getID(),
                    "status", building.power.status,
                    "satisfaction", building.power.graph.getSatisfaction(),
                    "production_per_second", building.power.graph.getLastScaledPowerIn() * 60f,
                    "needed_per_second", building.power.graph.getLastScaledPowerOut() * 60f
                ));
            }
            value.put("consumption_status", consumptionStatus(building));
            value.put("stall_reason", stallReason(building));
            Building front = building.front();
            if (front != null && front.team == building.team) {
                value.put("output_to", buildingRef(front));
            }
            if (building.proximity != null && !building.proximity.isEmpty()
                && (building.block.hasItems || building.block.hasLiquids || building.block.hasPower)) {
                List<Map<String, Object>> adjacent = new ArrayList<>();
                for (Building neighbor : building.proximity) {
                    if (neighbor.team == building.team && adjacent.size() < 8) adjacent.add(buildingRef(neighbor));
                }
                if (!adjacent.isEmpty()) value.put("adjacent", adjacent);
            }
            if (building instanceof DrillBuild drill) {
                value.put("produces", drill.dominantItem == null ? null : drill.dominantItem.name);
                value.put("ore_tiles", drill.dominantItems);
                value.put("last_drill_speed", drill.lastDrillSpeed);
            }
            if (building instanceof ItemTurretBuild turret) {
                value.put("ammo_fraction", turret.getAmmoFraction());
                value.put("compatible_ammo", compatibleAmmo(turret));
            }
            if (building instanceof GenericCrafterBuild crafter) {
                value.put("craft_progress", crafter.progress);
                value.put("warmup", crafter.warmup);
            }
            if (building instanceof SorterBuild sorter && building.block instanceof Sorter sorterBlock) {
                value.put("item_filter", sorter.sortItem == null ? null : sorter.sortItem.name);
                value.put("filter_behavior", sorterBlock.invert
                    ? "selected item exits to the sides; other items continue straight"
                    : "selected item continues straight; other items exit to the sides");
            }
            if (building instanceof UnloaderBuild unloader) {
                value.put("item_filter", unloader.sortItem == null ? null : unloader.sortItem.name);
                value.put("filter_behavior", "extracts only the selected item from adjacent storage/core and outputs it to adjacent item receivers");
            }
            if (building instanceof UnitFactoryBuild factory) {
                value.put("unit_plan_index", factory.currentPlan);
                value.put("unit_build_progress", factory.fraction());
                value.put("configured_unit", factory.unit() == null ? null : factory.unit().name);
            }
            result.add(value);
        }
        return result;
    }

    private Map<String, Object> logisticsAnalysis(Team team) {
        List<Building> transports = new ArrayList<>();
        for (Building building : team.data().buildings) {
            if (isDirectedItemTransport(building)) transports.add(building);
        }

        List<Map<String, Object>> mixed = new ArrayList<>();
        List<Map<String, Object>> saturated = new ArrayList<>();
        List<Map<String, Object>> deadEnds = new ArrayList<>();
        List<Map<String, Object>> openOutputs = new ArrayList<>();
        for (Building transport : transports) {
            Set<String> itemTypes = storedItemTypes(transport);
            int itemCount = storedItemCount(transport);
            if (itemTypes.size() > 1 && mixed.size() < 40) mixed.add(transportDiagnostic(transport));
            if (transport.block.itemCapacity > 0 && itemCount >= transport.block.itemCapacity && saturated.size() < 40) {
                saturated.add(transportDiagnostic(transport));
            }
            Building front = transport.front();
            if ((front == null || front.team != team) && openOutputs.size() < 80) {
                Map<String, Object> diagnostic = new LinkedHashMap<>(transportDiagnostic(transport));
                diagnostic.put("flow_fact", "output_does_not_reach_an_owned_building");
                openOutputs.add(diagnostic);
            }
            if ((front == null || front.team != team) && itemCount > 0 && deadEnds.size() < 20) {
                deadEnds.add(transportDiagnostic(transport));
            }
        }

        List<Map<String, Object>> bottlenecks = new ArrayList<>();
        for (Building sink : team.data().buildings) {
            Set<String> accepted = acceptedItemTypes(sink);
            if (accepted.isEmpty() || sink instanceof SorterBuild || sink instanceof CoreBuild) continue;

            List<Building> directInputs = new ArrayList<>();
            for (Building transport : transports) {
                if (transport.front() == sink) directInputs.add(transport);
            }
            if (directInputs.isEmpty()) {
                if (!sink.status().name().equals("active")) {
                    List<Map<String, Object>> nearbyMisdirected = new ArrayList<>();
                    for (Building transport : transports) {
                        if (transport.proximity.contains(sink) && nearbyMisdirected.size() < 12) {
                            nearbyMisdirected.add(transportDiagnostic(transport));
                        }
                    }
                    List<Map<String, Object>> reachability = accepted.stream().map(item -> Map.<String, Object>of(
                        "item", item,
                        "directed_path_reaches_sink", false,
                        "evidence", "no_transport_segment_outputs_into_sink"
                    )).toList();
                    Map<String, Object> missingInput = new LinkedHashMap<>();
                    missingInput.put("network_id", sink.block.name + "@" + sink.tileX() + "," + sink.tileY());
                    missingInput.put("sink", buildingRef(sink));
                    missingInput.put("sink_status", sink.status().name());
                    missingInput.put("accepted_items", accepted);
                    missingInput.put("observed_items_in_input_network", Set.of());
                    missingInput.put("incompatible_items", Set.of());
                    missingInput.put("input_segment_count", 0);
                    missingInput.put("transport_blocks", Map.of());
                    missingInput.put("direct_inputs", List.of());
                    missingInput.put("upstream_producers", List.of());
                    missingInput.put("contamination_sources", List.of());
                    missingInput.put("contaminated_segments", List.of());
                    missingInput.put("saturated_segments", List.of());
                    missingInput.put("resource_reachability", reachability);
                    missingInput.put("nearby_misdirected_segments", nearbyMisdirected);
                    missingInput.put("first_flow_break", Map.of(
                        "kind", "missing_directed_sink_entry",
                        "sink", buildingRef(sink),
                        "meaning", "No owned directed transport currently points into this sink."
                    ));
                    missingInput.put("diagnosis", "no_directed_transport_reaches_sink");
                    bottlenecks.add(missingInput);
                    if (bottlenecks.size() >= 20) break;
                }
                continue;
            }

            Set<Building> network = new LinkedHashSet<>();
            ArrayDeque<Building> queue = new ArrayDeque<>(directInputs);
            while (!queue.isEmpty() && network.size() < 160) {
                Building current = queue.removeFirst();
                if (!network.add(current)) continue;
                for (Building candidate : transports) {
                    if (!network.contains(candidate) && candidate.front() == current) queue.addLast(candidate);
                }
            }

            Set<String> observed = new LinkedHashSet<>();
            List<Map<String, Object>> contaminatedSegments = new ArrayList<>();
            List<Map<String, Object>> fullSegments = new ArrayList<>();
            Map<String, Integer> transportBlockCounts = new LinkedHashMap<>();
            Float minimumTransportThroughput = null;
            for (Building segment : network) {
                transportBlockCounts.merge(segment.block.name, 1, Integer::sum);
                Float throughput = itemTransportThroughputPerSecond(segment.block);
                if (throughput != null) {
                    minimumTransportThroughput = minimumTransportThroughput == null
                        ? throughput : Math.min(minimumTransportThroughput, throughput);
                }
                Set<String> segmentItems = storedItemTypes(segment);
                observed.addAll(segmentItems);
                boolean incompatibleHere = segmentItems.stream().anyMatch(item -> !accepted.contains(item));
                if (incompatibleHere && contaminatedSegments.size() < 30) {
                    contaminatedSegments.add(transportDiagnostic(segment));
                }
                if (segment.block.itemCapacity > 0 && storedItemCount(segment) >= segment.block.itemCapacity
                    && fullSegments.size() < 30) {
                    fullSegments.add(transportDiagnostic(segment));
                }
            }
            Set<String> incompatible = new LinkedHashSet<>(observed);
            incompatible.removeAll(accepted);

            List<Map<String, Object>> producers = new ArrayList<>();
            List<Map<String, Object>> contaminationSources = new ArrayList<>();
            Map<String, Float> observedProducerRates = new LinkedHashMap<>();
            Set<Building> seenProducers = new HashSet<>();
            for (Building segment : network) {
                for (Building neighbor : segment.proximity) {
                    if (neighbor == sink || neighbor.team != team || network.contains(neighbor)
                        || !seenProducers.add(neighbor)) continue;
                    String produced = producedItemName(neighbor);
                    if (produced != null && producers.size() < 30) {
                        Map<String, Object> producer = new LinkedHashMap<>(buildingRef(neighbor));
                        producer.put("produces", produced);
                        Map<String, Float> rates = producerRatesPerSecond(neighbor);
                        if (!rates.isEmpty()) {
                            producer.put("observed_output_rate_per_second", rates);
                            rates.forEach((item, rate) -> observedProducerRates.merge(item, rate, Float::sum));
                        }
                        producers.add(producer);
                        if (incompatible.contains(produced) && contaminationSources.size() < 20) {
                            List<Map<String, Object>> entrySegments = new ArrayList<>();
                            for (Building candidate : network) {
                                if (candidate.proximity.contains(neighbor) && entrySegments.size() < 8) {
                                    entrySegments.add(buildingRef(candidate));
                                }
                            }
                            Map<String, Object> source = new LinkedHashMap<>(producer);
                            source.put("entry_segments", entrySegments);
                            contaminationSources.add(source);
                        }
                    }
                }
            }

            Map<String, Object> value = new LinkedHashMap<>();
            value.put("network_id", sink.block.name + "@" + sink.tileX() + "," + sink.tileY());
            value.put("sink", buildingRef(sink));
            value.put("sink_status", sink.status().name());
            value.put("accepted_items", accepted);
            value.put("observed_items_in_input_network", observed);
            value.put("incompatible_items", incompatible);
            value.put("input_segment_count", network.size());
            value.put("transport_blocks", transportBlockCounts);
            if (minimumTransportThroughput != null) {
                value.put("minimum_nominal_transport_throughput_per_second", minimumTransportThroughput);
            }
            value.put("observed_upstream_output_rate_per_second", observedProducerRates);
            value.put("sink_nominal_item_demand_per_second", nominalItemInputRatesPerSecond(sink));
            value.put("direct_inputs", directInputs.stream().map(this::buildingRef).toList());
            value.put("contaminated_segments", contaminatedSegments);
            value.put("saturated_segments", fullSegments);
            value.put("upstream_producers", producers);
            value.put("contamination_sources", contaminationSources);
            List<Map<String, Object>> reachability = new ArrayList<>();
            for (String item : accepted) {
                boolean producerObserved = producers.stream().anyMatch(producer -> item.equals(producer.get("produces")));
                boolean itemObserved = observed.contains(item);
                reachability.add(Map.of(
                    "item", item,
                    "directed_path_reaches_sink", producerObserved || itemObserved,
                    "evidence", itemObserved ? "item_observed_in_input_network"
                        : producerObserved ? "matching_upstream_producer_connected"
                        : "no_matching_upstream_producer_or_item_observed"
                ));
            }
            value.put("resource_reachability", reachability);
            if (!contaminatedSegments.isEmpty()) {
                value.put("first_flow_break", Map.of(
                    "kind", "incompatible_item_observed",
                    "segment", contaminatedSegments.get(0)
                ));
            } else if (!fullSegments.isEmpty()) {
                value.put("first_flow_break", Map.of(
                    "kind", "saturated_segment_observed",
                    "segment", fullSegments.get(0)
                ));
            } else if (reachability.stream().anyMatch(row -> !Boolean.TRUE.equals(row.get("directed_path_reaches_sink")))) {
                value.put("first_flow_break", Map.of(
                    "kind", "required_resource_has_no_observed_upstream_path",
                    "sink", buildingRef(sink)
                ));
            }
            boolean missingRequiredPath = reachability.stream()
                .anyMatch(row -> !Boolean.TRUE.equals(row.get("directed_path_reaches_sink")));
            value.put("diagnosis", !incompatible.isEmpty() ? "incompatible_item_contamination"
                : !fullSegments.isEmpty() ? "saturated_input_network"
                : missingRequiredPath ? "required_resource_path_not_observed" : "no_observed_item_conflict");
            if (!incompatible.isEmpty() || !fullSegments.isEmpty() || !sink.status().name().equals("active")) {
                bottlenecks.add(value);
                if (bottlenecks.size() >= 20) break;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("meaning", "Directed item-flow evidence. Mixing is only classified as contamination when observed items are incompatible with a selective sink.");
        result.put("transport_segment_count", transports.size());
        result.put("transport_topology", transports.stream().map(this::transportTopologyState).toList());
        result.put("conveyor_planning_area", conveyorPlanningArea(team.core(), team));
        result.put("mixed_transport_segments", mixed);
        result.put("saturated_transport_segments", saturated);
        result.put("loaded_dead_end_segments", deadEnds);
        result.put("open_output_segments", openOutputs);
        result.put("transport_components", transportComponentSummaries(transports, team));
        result.put("selective_sink_bottlenecks", bottlenecks);
        return result;
    }

    private List<Map<String, Object>> transportComponentSummaries(List<Building> transports, Team team) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<Building> remaining = new LinkedHashSet<>(transports);
        while (!remaining.isEmpty() && result.size() < 40) {
            Building seed = remaining.iterator().next();
            Set<Building> component = new LinkedHashSet<>();
            ArrayDeque<Building> queue = new ArrayDeque<>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                Building current = queue.removeFirst();
                if (!component.add(current)) continue;
                remaining.remove(current);
                Building front = current.front();
                if (front != null && isDirectedItemTransport(front) && front.team == team) queue.add(front);
                for (Building candidate : transports) {
                    if (!component.contains(candidate) && candidate.front() == current) queue.add(candidate);
                }
            }

            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            Set<String> observedItems = new LinkedHashSet<>();
            Set<String> producerItems = new LinkedHashSet<>();
            List<Map<String, Object>> terminalOutputs = new ArrayList<>();
            List<Map<String, Object>> open = new ArrayList<>();
            boolean cycle = false;
            for (Building segment : component) {
                minX = Math.min(minX, segment.tileX());
                maxX = Math.max(maxX, segment.tileX());
                minY = Math.min(minY, segment.tileY());
                maxY = Math.max(maxY, segment.tileY());
                observedItems.addAll(storedItemTypes(segment));
                Building front = segment.front();
                if (front == null || front.team != team) {
                    if (open.size() < 12) open.add(transportDiagnostic(segment));
                } else if (!isDirectedItemTransport(front) && terminalOutputs.size() < 12) {
                    Map<String, Object> terminal = new LinkedHashMap<>(transportDiagnostic(segment));
                    terminal.put("sink", buildingRef(front));
                    terminal.put("sink_accepted_items", front instanceof CoreBuild ? "all_items" : acceptedItemTypes(front));
                    terminalOutputs.add(terminal);
                }
                if (front != null && component.contains(front) && front.front() == segment) cycle = true;
                for (Building neighbor : segment.proximity) {
                    if (neighbor.team != team || component.contains(neighbor)) continue;
                    String produced = producedItemName(neighbor);
                    if (produced != null) producerItems.add(produced);
                }
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("component_id", "transport@" + seed.tileX() + "," + seed.tileY());
            summary.put("segment_count", component.size());
            summary.put("bounds", Map.of("min_x", minX, "max_x", maxX, "min_y", minY, "max_y", maxY));
            summary.put("adjacent_producer_items", producerItems);
            summary.put("observed_carried_items", observedItems);
            summary.put("terminal_outputs", terminalOutputs);
            summary.put("open_outputs", open);
            summary.put("has_two_segment_cycle", cycle);
            result.add(summary);
        }
        return result;
    }

    private Map<String, Object> transportTopologyState(Building building) {
        Map<String, Object> result = new LinkedHashMap<>(buildingRef(building));
        result.put("rotation", building.rotation);
        Set<String> items = storedItemTypes(building);
        if (!items.isEmpty()) result.put("items", items);
        Building front = building.front();
        if (front != null && front.team == building.team) result.put("output_to", buildingRef(front));
        return result;
    }

    private Map<String, Object> conveyorPlanningArea(CoreBuild core, Team team) {
        if (core == null) return Map.of();
        int radius = MACRO_PATH_RADIUS;
        int minX = Math.max(0, core.tileX() - radius), maxX = Math.min(Vars.world.width() - 1, core.tileX() + radius);
        int minY = Math.max(0, core.tileY() - radius), maxY = Math.min(Vars.world.height() - 1, core.tileY() + radius);
        List<Map<String, Object>> blockedRows = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            List<List<Integer>> ranges = new ArrayList<>();
            int runStart = -1;
            for (int x = minX; x <= maxX + 1; x++) {
                boolean blocked = x <= maxX && permanentConveyorTerrainBlocked(Vars.world.tile(x, y));
                if (blocked && runStart < 0) runStart = x;
                if (!blocked && runStart >= 0) {
                    ranges.add(List.of(runStart, x - 1));
                    runStart = -1;
                }
            }
            if (!ranges.isEmpty()) blockedRows.add(Map.of("y", y, "blocked_x_ranges_inclusive", ranges));
        }
        List<Map<String, Object>> occupied = new ArrayList<>();
        for (Building building : team.data().buildings) {
            if (isDirectedItemTransport(building)) continue;
            if (building.tileX() < minX || building.tileX() > maxX || building.tileY() < minY || building.tileY() > maxY) continue;
            Map<String, Object> value = new LinkedHashMap<>(buildingRef(building));
            value.put("size", building.block.size);
            occupied.add(value);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("meaning", "Exact planning evidence: avoid permanent terrain ranges and occupied non-transport footprints; transport_topology lists every reusable item-transport tile.");
        result.put("bounds", Map.of("min_x", minX, "max_x", maxX, "min_y", minY, "max_y", maxY));
        result.put("permanent_terrain_blocked_rows", blockedRows);
        result.put("owned_non_transport_buildings", occupied);
        return result;
    }

    private Map<String, Object> routeEndpointEvidence(Building building) {
        Map<String, Object> result = new LinkedHashMap<>(buildingRef(building));
        result.put("size", building.block.size);
        result.put("footprint", footprintState(building.block, building.tileX(), building.tileY()));
        result.put("status", building.status().name());
        String produced = producedItemName(building);
        if (produced != null) result.put("produces", produced);
        Set<String> stored = storedItemTypes(building);
        if (!stored.isEmpty()) result.put("stored_items", stored);
        Set<String> accepted = acceptedItemTypes(building);
        result.put("accepted_items", building instanceof CoreBuild ? "all_items" : accepted);
        return result;
    }

    private Map<String, Object> localPathMap(
        Building source, Building target, Block transport, int margin) {
        int minX = Math.max(0, Math.min(source.tileX(), target.tileX()) - margin);
        int maxX = Math.min(Vars.world.width() - 1, Math.max(source.tileX(), target.tileX()) + margin);
        int minY = Math.max(0, Math.min(source.tileY(), target.tileY()) - margin);
        int maxY = Math.min(Vars.world.height() - 1, Math.max(source.tileY(), target.tileY()) + margin);
        List<Map<String, Object>> exceptions = new ArrayList<>();
        int omitted = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                Tile tile = Vars.world.tile(x, y);
                if (tile == null) continue;
                List<Integer> validRotations = new ArrayList<>();
                for (int rotation = 0; rotation < DIRECTIONS.length; rotation++) {
                    if (Build.validPlace(transport, controlledTeam(), x, y, rotation)) validRotations.add(rotation);
                }
                Building existing = tile.build;
                Item ore = tile.drop();
                boolean terrainFeature = tile.block() != null && tile.block() != Blocks.air;
                boolean ordinaryClear = existing == null && ore == null && !terrainFeature
                    && validRotations.size() == 4;
                if (ordinaryClear) continue;
                if (exceptions.size() >= 1200) {
                    omitted++;
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("x", x);
                row.put("y", y);
                row.put("valid_transport_rotations", validRotations);
                row.put("terrain_block", tile.block() == null ? null : tile.block().name);
                row.put("floor", tile.floor() == null ? null : tile.floor().name);
                row.put("ore", ore == null ? null : ore.name);
                row.put("terrain_status", !validRotations.isEmpty() ? "replaceable_or_buildable_now"
                    : existing != null ? "building_occupied"
                    : tile.floor() != null && tile.floor().isDeep() ? "deep_floor_blocked"
                    : tile.solid() ? "solid_environment_blocked" : "game_rule_blocked");
                if (existing != null) {
                    row.put("building", buildingRef(existing));
                    row.put("building_size", existing.block.size);
                    row.put("building_rotation", existing.rotation);
                    Set<String> items = storedItemTypes(existing);
                    if (!items.isEmpty()) row.put("building_items", items);
                    Building front = existing.front();
                    if (front != null && front.team == controlledTeam()) row.put("building_output_to", buildingRef(front));
                }
                exceptions.add(row);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("coordinate_system", "+x east, +y north; rotations 0 east, 1 north, 2 west, 3 south");
        result.put("bounds", Map.of("min_x", minX, "max_x", maxX, "min_y", minY, "max_y", maxY));
        result.put("default_unlisted_tile",
            "Currently buildable for this transport in all rotations, with no building, ore, or terrain feature.");
        result.put("tile_exceptions", exceptions);
        result.put("exceptions_omitted", omitted);
        result.put("note",
            "A terrain feature with valid rotations is replaceable/buildable now; empty valid rotations means the current game state rejects this transport there.");
        return result;
    }

    private boolean permanentConveyorTerrainBlocked(Tile tile) {
        if (tile == null) return true;
        if (tile.floor() != null && tile.floor().isDeep()) return true;
        return tile.solid() && tile.build == null;
    }

    private boolean isDirectedItemTransport(Building building) {
        return building.block.size == 1 && building.block.rotate && building.block.conveyorPlacement
            && building.block.hasItems && building.block.outputsItems();
    }

    private Set<String> storedItemTypes(Building building) {
        Set<String> result = new LinkedHashSet<>();
        if (building.items == null) return result;
        for (Item item : Vars.content.items()) {
            if (building.items.get(item) > 0) result.add(item.name);
        }
        return result;
    }

    private int storedItemCount(Building building) {
        int result = 0;
        if (building.items == null) return result;
        for (Item item : Vars.content.items()) result += building.items.get(item);
        return result;
    }

    private Map<String, Object> transportDiagnostic(Building building) {
        Map<String, Object> result = new LinkedHashMap<>(buildingRef(building));
        result.put("rotation", building.rotation);
        result.put("items", storedItemTypes(building));
        result.put("item_count", storedItemCount(building));
        result.put("item_capacity", building.block.itemCapacity);
        Float throughput = itemTransportThroughputPerSecond(building.block);
        if (throughput != null) result.put("nominal_item_throughput_per_second", throughput);
        Building front = building.front();
        if (front != null && front.team == building.team) result.put("output_to", buildingRef(front));
        return result;
    }

    private Float itemTransportThroughputPerSecond(Block block) {
        if (block instanceof Conveyor conveyor) return conveyor.displayedSpeed;
        return null;
    }

    private Map<String, Float> producerRatesPerSecond(Building building) {
        Map<String, Float> result = new LinkedHashMap<>();
        if (building instanceof DrillBuild drill && drill.dominantItem != null) {
            result.put(drill.dominantItem.name, Math.max(0f, drill.lastDrillSpeed * 60f));
        } else if (building instanceof GenericCrafterBuild && building.block instanceof GenericCrafter crafter
            && crafter.craftTime > 0f) {
            float craftsPerSecond = 60f / crafter.craftTime * Math.max(0f, building.efficiency);
            for (ItemStack output : crafterOutputs(crafter)) {
                result.put(output.item.name, output.amount * craftsPerSecond);
            }
        }
        return result;
    }

    private Map<String, Float> nominalItemInputRatesPerSecond(Building building) {
        Map<String, Float> result = new LinkedHashMap<>();
        if (building.block instanceof GenericCrafter crafter && crafter.craftTime > 0f
            && building.block.consumers != null) {
            float craftsPerSecond = 60f / crafter.craftTime;
            for (Consume consumer : building.block.consumers) {
                if (consumer instanceof ConsumeItems items && !consumer.optional) {
                    for (ItemStack stack : items.items) {
                        result.merge(stack.item.name, stack.amount * craftsPerSecond, Float::sum);
                    }
                }
            }
        }
        if (building.block instanceof ConsumeGenerator generator && generator.itemDuration > 0f
            && generator.filterItem != null) {
            for (Item item : Vars.content.items()) {
                if (!generator.filterItem.filter.get(item)) continue;
                float durationMultiplier = generator.itemDurationMultipliers.get(item, 1f);
                result.put(item.name,
                    60f / (generator.itemDuration * Math.max(0.0001f, durationMultiplier)));
            }
        }
        if (building instanceof UnitFactoryBuild factory && building.block instanceof UnitFactory block
            && factory.currentPlan >= 0 && factory.currentPlan < block.plans.size) {
            UnitFactory.UnitPlan plan = block.plans.get(factory.currentPlan);
            if (plan.time > 0f) {
                float unitsPerSecond = 60f / plan.time;
                for (ItemStack stack : plan.requirements) {
                    result.merge(stack.item.name, stack.amount * unitsPerSecond, Float::sum);
                }
            }
        }
        return result;
    }

    private Set<String> acceptedItemTypes(Building building) {
        Set<String> result = new LinkedHashSet<>();
        if (building instanceof ItemTurretBuild turret) result.addAll(compatibleAmmo(turret));
        if (building instanceof UnitFactoryBuild factory && building.block instanceof UnitFactory block
            && factory.currentPlan >= 0 && factory.currentPlan < block.plans.size) {
            for (ItemStack stack : block.plans.get(factory.currentPlan).requirements) {
                result.add(stack.item.name);
            }
        }
        if (building.block.consumers != null) {
            for (Consume consumer : building.block.consumers) {
                if (consumer instanceof ConsumeItems items) {
                    for (ItemStack stack : items.items) result.add(stack.item.name);
                } else if (consumer instanceof ConsumeItemFilter filter) {
                    for (Item item : Vars.content.items()) if (filter.filter.get(item)) result.add(item.name);
                }
            }
        }
        return result;
    }

    private String producedItemName(Building building) {
        if (building instanceof DrillBuild drill && drill.dominantItem != null) return drill.dominantItem.name;
        if (building instanceof UnloaderBuild unloader && unloader.sortItem != null) return unloader.sortItem.name;
        if (building instanceof GenericCrafterBuild && building.block instanceof GenericCrafter crafter) {
            List<ItemStack> outputs = crafterOutputs(crafter);
            if (!outputs.isEmpty()) return outputs.get(0).item.name;
        }
        return null;
    }

    private Map<String, Object> buildingRef(Building building) {
        return Map.of("block", building.block.name, "x", building.tileX(), "y", building.tileY());
    }

    private List<Map<String, Object>> consumptionStatus(Building building) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (building.block.consumers != null) {
            for (Consume consumer : building.block.consumers) {
                if (consumer == null || consumer.ignore()) continue;
                Map<String, Object> value = consumerDefinition(consumer);
                value.put("optional", consumer.optional);
                value.put("efficiency", consumer.efficiency(building));
                value.put("satisfied", consumer.optional || consumer.efficiency(building) > 0.001f);
                if (consumer instanceof ConsumeItems items) {
                    Map<String, Integer> available = new LinkedHashMap<>();
                    for (ItemStack stack : items.items) available.put(stack.item.name,
                        building.items == null ? 0 : building.items.get(stack.item));
                    value.put("available", available);
                } else if (consumer instanceof ConsumeLiquid liquid) {
                    value.put("available", building.liquids == null ? 0f : building.liquids.get(liquid.liquid));
                } else if (consumer instanceof ConsumeLiquids liquids) {
                    Map<String, Float> available = new LinkedHashMap<>();
                    for (LiquidStack stack : liquids.liquids) available.put(stack.liquid.name,
                        building.liquids == null ? 0f : building.liquids.get(stack.liquid));
                    value.put("available", available);
                }
                result.add(value);
            }
        }
        if (building instanceof UnitFactoryBuild factory && building.block instanceof UnitFactory block
            && factory.currentPlan >= 0 && factory.currentPlan < block.plans.size) {
            UnitFactory.UnitPlan plan = block.plans.get(factory.currentPlan);
            Map<String, Integer> required = new LinkedHashMap<>();
            Map<String, Integer> available = new LinkedHashMap<>();
            boolean satisfied = true;
            for (ItemStack stack : plan.requirements) {
                required.put(stack.item.name, stack.amount);
                int held = building.items == null ? 0 : building.items.get(stack.item);
                available.put(stack.item.name, held);
                if (held < stack.amount) satisfied = false;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("resource", "unit_plan_items");
            value.put("configured_unit", plan.unit.name);
            value.put("amount_per_unit", required);
            value.put("available", available);
            value.put("optional", false);
            value.put("satisfied", satisfied);
            result.add(value);
        }
        return result;
    }

    private String stallReason(Building building) {
        if (!building.enabled) return "disabled";
        String status = building.status().name();
        if (!status.equals("active")) return status;
        if (building.efficiency <= 0.001f && building.block.hasConsumers) return "zero_efficiency";
        return "none";
    }

    private List<Map<String, Object>> powerNetworkState(Team team) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Building building : team.data().buildings) {
            if (building.power == null || building.power.graph == null) continue;
            var graph = building.power.graph;
            if (!seen.add(graph.getID())) continue;
            List<Map<String, Object>> members = new ArrayList<>();
            for (Building member : graph.all) {
                if (members.size() >= 40) break;
                members.add(buildingRef(member));
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", graph.getID());
            value.put("satisfaction", graph.getSatisfaction());
            // LastPowerProduced/Needed are frame-delta-scaled energy. The scaled
            // accessors normalize by delta, so multiplying those by 60 yields
            // stable per-second rates on both headless and graphical hosts.
            value.put("production_per_second", graph.getLastScaledPowerIn() * 60f);
            value.put("needed_per_second", graph.getLastScaledPowerOut() * 60f);
            value.put("balance_per_second", graph.getPowerBalance() * 60f);
            value.put("battery_stored", graph.getLastPowerStored());
            value.put("battery_capacity", graph.getLastCapacity());
            value.put("producer_count", graph.producers.size);
            value.put("consumer_count", graph.consumers.size);
            value.put("member_count", graph.all.size);
            value.put("has_generation_without_consumers", graph.producers.size > 0 && graph.consumers.isEmpty());
            value.put("has_consumers_without_generation", graph.consumers.size > 0 && graph.producers.isEmpty()
                && graph.getLastPowerStored() <= 0.001f);
            value.put("members", members);
            result.add(value);
        }
        return result;
    }

    private List<Map<String, Object>> powerNodeTopology(Team team) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Building building : team.data().buildings) {
            if (!(building instanceof PowerNodeBuild node) || !(building.block instanceof PowerNode block)) continue;
            List<Map<String, Object>> links = new ArrayList<>();
            for (int i = 0; node.power != null && i < node.power.links.size; i++) {
                Building linked = Vars.world.build(node.power.links.get(i));
                if (linked == null || linked.team != team) continue;
                links.add(Map.of(
                    "block", linked.block.name, "x", linked.tileX(), "y", linked.tileY()
                ));
            }
            result.add(Map.of(
                "block", block.name,
                "x", node.tileX(), "y", node.tileY(),
                "network_id", node.power == null || node.power.graph == null ? -1 : node.power.graph.getID(),
                "link_range_tiles", block.laserRange,
                "max_links", block.maxNodes,
                "current_link_count", links.size(),
                "links", links
            ));
        }
        return result;
    }

    private Map<String, Object> productionSummary(Team team) {
        Map<String, Integer> producers = new LinkedHashMap<>();
        List<Map<String, Object>> stalled = new ArrayList<>();
        List<Map<String, Object>> productionBuildings = new ArrayList<>();
        for (Building building : team.data().buildings) {
            if (building instanceof DrillBuild drill && drill.dominantItem != null) {
                producers.merge(drill.dominantItem.name, 1, Integer::sum);
            }
            if (building.block instanceof GenericCrafter crafter) {
                for (ItemStack output : crafterOutputs(crafter)) producers.merge(output.item.name, 1, Integer::sum);
            }
            String reason = stallReason(building);
            if (!reason.equals("none") && (building.block.hasConsumers || building.block instanceof Drill
                || building.block instanceof GenericCrafter || building.block instanceof PowerGenerator)) {
                Map<String, Object> entry = new LinkedHashMap<>(buildingRef(building));
                entry.put("reason", reason);
                entry.put("efficiency", building.efficiency);
                if (stalled.size() < 40) stalled.add(entry);
            }
            boolean productionRelevant = building instanceof DrillBuild
                || building instanceof GenericCrafterBuild
                || building instanceof PowerGenerator.GeneratorBuild
                || building instanceof UnitFactoryBuild;
            if (productionRelevant && productionBuildings.size() < 80) {
                Map<String, Object> node = new LinkedHashMap<>(buildingRef(building));
                node.put("status", building.status().name());
                node.put("stall_reason", reason);
                node.put("efficiency", building.efficiency);
                node.put("consumption_status", consumptionStatus(building));
                Map<String, Integer> stored = new LinkedHashMap<>();
                if (building.items != null) {
                    for (Item item : Vars.content.items()) {
                        int amount = building.items.get(item);
                        if (amount > 0) stored.put(item.name, amount);
                    }
                }
                if (!stored.isEmpty()) node.put("items", stored);
                if (building.power != null && building.power.graph != null) {
                    node.put("power", Map.of(
                        "network_id", building.power.graph.getID(),
                        "satisfaction", building.power.graph.getSatisfaction(),
                        "production_per_second", building.power.graph.getLastScaledPowerIn() * 60f,
                        "needed_per_second", building.power.graph.getLastScaledPowerOut() * 60f
                    ));
                }
                if (building instanceof DrillBuild drill && drill.dominantItem != null) {
                    node.put("outputs", Map.of(drill.dominantItem.name, "raw_item"));
                    node.put("ore_tiles", drill.dominantItems);
                    node.put("observed_output_rate_per_second", producerRatesPerSecond(building));
                } else if (building.block instanceof GenericCrafter crafter) {
                    Map<String, Integer> outputs = new LinkedHashMap<>();
                    for (ItemStack output : crafterOutputs(crafter)) outputs.put(output.item.name, output.amount);
                    if (!outputs.isEmpty()) node.put("outputs", outputs);
                    node.put("craft_time_seconds", crafter.craftTime / 60f);
                    node.put("nominal_item_input_rate_per_second", nominalItemInputRatesPerSecond(building));
                    node.put("observed_output_rate_per_second", producerRatesPerSecond(building));
                } else if (building instanceof PowerGenerator.GeneratorBuild) {
                    node.put("outputs", Map.of("power", "network"));
                }
                if (building instanceof UnitFactoryBuild factory) {
                    node.put("configured_unit", factory.unit() == null ? null : factory.unit().name);
                    node.put("unit_build_progress", factory.fraction());
                }
                productionBuildings.add(node);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("item_producer_counts", producers);
        result.put("stalled_buildings", stalled);
        result.put("production_buildings", productionBuildings);
        result.put("production_buildings_omitted", Math.max(0, team.data().buildings.count(building ->
            building instanceof DrillBuild || building instanceof GenericCrafterBuild
                || building instanceof PowerGenerator.GeneratorBuild || building instanceof UnitFactoryBuild) - productionBuildings.size()));
        return result;
    }

    private List<Map<String, Object>> unitState(Team friendly, boolean enemies) {
        List<Unit> units = new ArrayList<>();
        for (Team team : Team.all) {
            if (team == Team.derelict) continue;
            // Classify units relative to the authenticated agent team.
            boolean isEnemy = team != friendly;
            if ((enemies && isEnemy) || (!enemies && team == friendly)) {
                for (Unit unit : team.data().units) {
                    if (!unit.isAdded() || unit.dead() || unit.health <= 0f) continue;
                    units.add(unit);
                }
            }
        }
        units.sort(Comparator.comparingDouble(unit -> distanceToCore(unit.x, unit.y, friendly.core())));

        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < Math.min(units.size(), MAX_UNITS_IN_STATE); index++) {
            Unit unit = units.get(index);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", unit.id);
            value.put("type", unit.type.name);
            value.put("team", unit.team.name);
            value.put("x", unit.x / Vars.tilesize);
            value.put("y", unit.y / Vars.tilesize);
            value.put("health", unit.health);
            value.put("max_health", unit.maxHealth);
            value.put("commandable", unit.isCommandable());
            if (unit.controller() instanceof CommandAI command) {
                value.put("unit_command", command.currentCommand() == null ? null : command.currentCommand().name);
                if (command.attackTarget != null) {
                    value.put("command_target", Map.of(
                        "kind", command.attackTarget instanceof Building ? "building" : "unit",
                        "x", command.attackTarget.x() / Vars.tilesize,
                        "y", command.attackTarget.y() / Vars.tilesize
                    ));
                } else if (command.targetPos != null) {
                    value.put("command_target", Map.of(
                        "kind", "position",
                        "x", command.targetPos.x / Vars.tilesize,
                        "y", command.targetPos.y / Vars.tilesize
                    ));
                }
            }
            result.add(value);
        }
        return result;
    }

    private List<Map<String, Object>> coreStates(Team team) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CoreBuild core : team.cores()) result.add(coreState(core));
        return result;
    }

    private List<Map<String, Object>> enemyTeamState(Team self) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Team team : Team.all) {
            if (team == self || team == Team.derelict) continue;
            if (team.data().buildings.isEmpty() && team.data().units.isEmpty()) continue;
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("team", Map.of("id", team.id, "name", team.name, "color", team.color.toString()));
            CoreBuild core = team.core();
            value.put("core", core == null ? null : coreState(core));
            value.put("cores", coreStates(team));
            value.put("buildings", buildingState(team));
            value.put("units", unitState(team, false));
            result.add(value);
        }
        return result;
    }

    private double distanceToCore(float x, float y, CoreBuild core) {
        if (core == null) return Double.MAX_VALUE;
        double dx = x - core.x;
        double dy = y - core.y;
        return dx * dx + dy * dy;
    }

    private List<Map<String, Object>> spawnState() {
        List<Map<String, Object>> result = new ArrayList<>();
        Seq<Tile> spawns = Vars.spawner == null ? new Seq<>() : Vars.spawner.getSpawns();
        for (int index = 0; index < spawns.size; index++) {
            Tile tile = spawns.get(index);
            result.add(Map.of("spawn_index", index, "x", tile.x, "y", tile.y));
        }
        return result;
    }

    private Map<String, Object> threatSummary(CoreBuild core) {
        int enemies = 0, within30 = 0, within20 = 0, within12 = 0;
        double minimum = Double.MAX_VALUE;
        if (core != null) {
            for (Team team : Team.all) {
                if (team == controlledTeam() || team == Team.derelict) continue;
                for (Unit unit : team.data().units) {
                    enemies++;
                    double dx = unit.x / Vars.tilesize - core.tileX();
                    double dy = unit.y / Vars.tilesize - core.tileY();
                    double distance = Math.sqrt(dx * dx + dy * dy);
                    minimum = Math.min(minimum, distance);
                    if (distance <= 30) within30++;
                    if (distance <= 20) within20++;
                    if (distance <= 12) within12++;
                }
            }
        }
        double healthFraction = core == null || core.maxHealth <= 0f ? 0d : core.health / core.maxHealth;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("core_health_fraction", healthFraction);
        result.put("enemy_units", enemies);
        result.put("enemies_within_30_tiles", within30);
        result.put("enemies_within_20_tiles", within20);
        result.put("enemies_within_12_tiles", within12);
        result.put("nearest_enemy_distance_tiles", minimum == Double.MAX_VALUE ? null : minimum);
        return result;
    }

    private Map<String, Object> defenseAnalysis(CoreBuild core) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("meaning", "Raw terrain-aware route, enemy, turret, wall, and valid-placement evidence; no defense adequacy score or prescribed build order. +y is north. Route engagement intervals show only where a compatible turret can fire, not whether its damage, durability, ammunition, or survival margin is sufficient.");
        if (core == null) return result;

        List<TurretBuild> turrets = new ArrayList<>();
        List<Building> walls = new ArrayList<>();
        for (Building building : controlledTeam().data().buildings) {
            if (building instanceof TurretBuild turret) turrets.add(turret);
            if (building.block instanceof Wall) walls.add(building);
        }

        int groundTurrets = 0, airTurrets = 0;
        List<Map<String, Object>> turretEvidence = new ArrayList<>();
        for (TurretBuild turret : turrets) {
            Turret block = (Turret) turret.block;
            double groundDps = turretEstimatedDps(turret, false);
            double airDps = turretEstimatedDps(turret, true);
            if (block.targetGround) groundTurrets++;
            if (block.targetAir) airTurrets++;
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("block", block.name);
            evidence.put("x", turret.tileX());
            evidence.put("y", turret.tileY());
            evidence.put("range_tiles", block.range / Vars.tilesize);
            evidence.put("health_fraction", turret.maxHealth <= 0f ? 0f : turret.health / turret.maxHealth);
            evidence.put("ammo_fraction", turret instanceof ItemTurretBuild itemTurret ? itemTurret.getAmmoFraction() : null);
            evidence.put("estimated_ground_dps_with_loaded_ammo", groundDps);
            evidence.put("estimated_air_dps_with_loaded_ammo", airDps);
            turretEvidence.add(evidence);
        }
        result.put("totals", Map.of(
            "turrets", turrets.size(),
            "ground_targeting_turrets", groundTurrets,
            "air_targeting_turrets", airTurrets,
            "walls", walls.size()
        ));
        result.put("turrets", turretEvidence);

        int currentEnemies = 0;
        double currentGroundHealth = 0d, currentAirHealth = 0d;
        for (Team team : Team.all) {
            if (team == controlledTeam() || team == Team.derelict) continue;
            for (Unit unit : team.data().units) {
                currentEnemies++;
                boolean flying = unit.isFlying();
                if (flying) currentAirHealth += unit.health;
                else currentGroundHealth += unit.health;
            }
        }
        result.put("current_enemy_comparison", Map.of(
            "enemy_units", currentEnemies,
            "ground_health", currentGroundHealth,
            "air_health", currentAirHealth
        ));

        List<Map<String, Object>> directions = new ArrayList<>();
        for (int[] spawn : spawnTargets(core.tileX(), core.tileY())) {
            directions.add(defenseDirectionState(core, spawn, turrets, walls));
        }
        result.put("approach_directions", directions);
        return result;
    }

    private Map<String, Object> waveForecast() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("meaning", "Server-scheduled enemy composition for the current and following waves. Counts, health, armor, shields, movement type, and speed are evidence; no defense adequacy threshold is implied.");
        List<Map<String, Object>> waves = new ArrayList<>();
        int currentWave = Math.max(1, Vars.state.wave);
        Seq<Tile> spawnTiles = Vars.spawner == null ? new Seq<>() : Vars.spawner.getSpawns();
        for (int offset = 0; offset < 3; offset++) {
            int waveNumber = currentWave + offset;
            int waveIndex = waveNumber - 1;
            List<Map<String, Object>> groups = new ArrayList<>();
            int totalUnits = 0, groundUnits = 0, airUnits = 0;
            double baseHealth = 0d, shieldHealth = 0d;
            for (SpawnGroup group : Vars.state.rules.spawns) {
                if (group == null || group.type == null) continue;
                int count = group.getSpawned(waveIndex);
                if (count <= 0) continue;
                float shieldEach = group.getShield(waveIndex);
                List<Map<String, Object>> scheduledSpawns = new ArrayList<>();
                for (int spawnIndex = 0; spawnIndex < spawnTiles.size; spawnIndex++) {
                    if (group.spawn >= 0 && group.spawn != spawnIndex) continue;
                    Tile tile = spawnTiles.get(spawnIndex);
                    scheduledSpawns.add(Map.of("spawn_index", spawnIndex, "x", tile.x, "y", tile.y));
                }
                int spawnInstances = Math.max(1, scheduledSpawns.size());
                int actualCount = count * spawnInstances;
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("unit", group.type.name);
                value.put("count_per_spawn", count);
                value.put("spawn_instances", spawnInstances);
                value.put("total_count", actualCount);
                value.put("scheduled_spawns", scheduledSpawns);
                value.put("flying", group.type.flying);
                value.put("health_each", group.type.health);
                value.put("armor", group.type.armor);
                value.put("shield_each", shieldEach);
                value.put("speed_tiles_per_second", group.type.speed * 60f / Vars.tilesize);
                value.put("spawn_selector", group.spawn < 0 ? "all_enemy_spawns" : group.spawn);
                if (group.effect != null) value.put("status_effect", group.effect.name);
                groups.add(value);
                totalUnits += actualCount;
                if (group.type.flying) airUnits += actualCount; else groundUnits += actualCount;
                baseHealth += (double) actualCount * group.type.health;
                shieldHealth += (double) actualCount * shieldEach;
            }
            Map<String, Object> wave = new LinkedHashMap<>();
            wave.put("wave", waveNumber);
            wave.put("is_current", offset == 0);
            wave.put("total_units", totalUnits);
            wave.put("ground_units", groundUnits);
            wave.put("air_units", airUnits);
            wave.put("base_health_total", baseHealth);
            wave.put("shield_total", shieldHealth);
            wave.put("groups", groups);
            waves.add(wave);
        }
        result.put("waves", waves);
        return result;
    }

    private Map<String, Object> defenseDirectionState(CoreBuild core, int[] spawn,
                                                       List<TurretBuild> turrets, List<Building> walls) {
        double airDx = spawn[0] - core.tileX(), airDy = spawn[1] - core.tileY();
        double straightLength = Math.max(1d, Math.sqrt(airDx * airDx + airDy * airDy));
        double airUx = airDx / straightLength, airUy = airDy / straightLength;
        List<int[]> groundPath = groundTerrainPath(spawn, core);
        boolean groundReachable = groundPath != null;
        int[] groundReference = groundReachable
            ? pathPointFromCore(groundPath, Math.min(24, Math.max(1, groundPath.size() - 1)))
            : spawn;
        double dx = groundReference[0] - core.tileX(), dy = groundReference[1] - core.tileY();
        double length = Math.max(1d, Math.sqrt(dx * dx + dy * dy));
        double ux = dx / length, uy = dy / length;
        String entryDirection = Integer.compare(groundReference[0], core.tileX()) + ":"
            + Integer.compare(groundReference[1], core.tileY());
        String directionId = "spawn_" + spawn[0] + "_" + spawn[1] + "_entry_" + entryDirection;

        List<Map<String, Object>> wallEvidence = new ArrayList<>();
        for (Building wall : walls) {
            double wx = wall.tileX() - core.tileX(), wy = wall.tileY() - core.tileY();
            double forward = wx * ux + wy * uy;
            double lateral = Math.abs(-wx * uy + wy * ux);
            if (Math.abs(forward) > 40d || lateral > 16d) continue;
            if (wallEvidence.size() < 16) wallEvidence.add(Map.of(
                "block", wall.block.name, "x", wall.tileX(), "y", wall.tileY(),
                "health", wall.health, "max_health", wall.maxHealth
            ));
        }

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("direction_id", directionId);
        value.put("spawn", Map.of("x", spawn[0], "y", spawn[1]));
        value.put("ground_route_reachable", groundReachable);
        value.put("ground_route_length_tiles", groundReachable ? groundPath.size() - 1 : null);
        value.put("ground_entry_reference", groundReachable
            ? Map.of("x", groundReference[0], "y", groundReference[1], "direction_from_core", entryDirection)
            : null);
        value.put("ground_route", Map.of(
            "ordering", "core_outward_to_spawn",
            "columns", List.of("steps_from_core", "x", "y"),
            "rows", groundReachable ? groundRouteRowsFromCore(groundPath) : List.of()
        ));
        value.put("air_vector_from_core", Map.of(
            "dx", airDx, "dy", airDy, "unit_dx", airUx, "unit_dy", airUy));
        value.put("straight_line_distance_tiles", straightLength);
        value.put("wall_evidence", wallEvidence);
        value.put("engagement_interval_columns",
            List.of("start_steps_from_core", "end_steps_from_core", "sampled_tiles"));
        value.put("existing_turret_route_engagements",
            existingTurretRouteEngagements(turrets, groundPath, core, spawn));
        value.put("placement_sample_contract",
            "Every row is currently placeable and can engage at least one sampled ground or air route point. Rows are evenly sampled after x/y sorting; they have no score, rank, adequacy judgment, or recommendation.");
        value.put("valid_turret_placement_samples",
            defensePlacementCandidates(core, groundPath, spawn));
        value.put("wall_placement_sample_contract",
            "Every row is a currently placeable wall anchor on or near the current ground route. Rows have no score, rank, required count, or recommendation; walls change the next observed route after construction.");
        value.put("valid_wall_placement_samples",
            defenseWallPlacementCandidates(core, groundPath));
        return value;
    }

    private Map<String, List<Map<String, Object>>> defensePlacementCandidates(
        CoreBuild core, List<int[]> groundPath, int[] airThreat) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        List<Block> candidatesBlocks = new ArrayList<>();
        for (Block block : Vars.content.blocks()) {
            if (!(block instanceof Turret) || !blockAvailable(block)) continue;
            if ("stockpile_rts_pvp".equals(gameModeVariant) && !(block instanceof ItemTurret)) continue;
            boolean exists = controlledTeam().data().buildings.contains(building -> building.block == block);
            if (exists || canAfford(core, effectiveCost(block))) candidatesBlocks.add(block);
        }
        candidatesBlocks.sort(Comparator.comparing(block -> block.name));
        for (Block candidateBlock : candidatesBlocks.subList(0, Math.min(candidatesBlocks.size(), 16))) {
            Turret turret = (Turret) candidateBlock;
            List<Map<String, Object>> placements = new ArrayList<>();
            for (int radius = core.block.size / 2 + 1; radius <= 18; radius++) {
                for (int x = core.tileX() - radius; x <= core.tileX() + radius; x++) {
                    for (int y = core.tileY() - radius; y <= core.tileY() + radius; y++) {
                        if (Math.max(Math.abs(x - core.tileX()), Math.abs(y - core.tileY())) != radius) continue;
                        if (!Build.validPlace(candidateBlock, controlledTeam(), x, y, 0)
                            || ("stockpile_rts_pvp".equals(gameModeVariant)
                                && rtsPlacementReserved(candidateBlock, x, y))) continue;
                        List<List<Integer>> groundIntervals = turret.targetGround && groundPath != null
                            ? groundRouteIntervals(turret, x, y, groundPath) : List.of();
                        List<List<Integer>> airIntervals = turret.targetAir
                            ? airRouteIntervals(turret, x, y, core, airThreat) : List.of();
                        if (groundIntervals.isEmpty() && airIntervals.isEmpty()) continue;
                        Map<String, Object> placement = new LinkedHashMap<>();
                        placement.put("x", x);
                        placement.put("y", y);
                        placement.put("ground_route_intervals", groundIntervals);
                        placement.put("air_route_distance_intervals", airIntervals);
                        placements.add(placement);
                    }
                }
            }
            placements.sort(Comparator
                .comparingInt((Map<String, Object> placement) -> ((Number) placement.get("x")).intValue())
                .thenComparingInt(placement -> ((Number) placement.get("y")).intValue()));
            result.put(candidateBlock.name, evenlySamplePlacements(placements, 24));
        }
        return result;
    }

    private Map<String, List<Map<String, Object>>> defenseWallPlacementCandidates(
        CoreBuild core, List<int[]> groundPath) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        if (groundPath == null || groundPath.isEmpty()) return result;
        List<Block> wallBlocks = new ArrayList<>();
        for (Block block : Vars.content.blocks()) {
            if (!(block instanceof Wall) || !blockAvailable(block)) continue;
            boolean exists = controlledTeam().data().buildings.contains(building -> building.block == block);
            if (exists || canAfford(core, effectiveCost(block))) wallBlocks.add(block);
        }
        wallBlocks.sort(Comparator.comparing(block -> block.name));
        int firstStep = Math.max(3, core.block.size / 2 + 2);
        int lastStep = Math.min(30, groundPath.size() - 1);
        for (Block wallBlock : wallBlocks) {
            Map<Integer, Map<String, Object>> unique = new LinkedHashMap<>();
            for (int steps = firstStep; steps <= lastStep; steps++) {
                int[] point = pathPointFromCore(groundPath, steps);
                for (int offsetX = -3; offsetX <= 3; offsetX++) {
                    for (int offsetY = -3; offsetY <= 3; offsetY++) {
                        int x = point[0] + offsetX, y = point[1] + offsetY;
                        if (!Build.validPlace(wallBlock, controlledTeam(), x, y, 0)) continue;
                        if ("stockpile_rts_pvp".equals(gameModeVariant)
                            && rtsPlacementReserved(wallBlock, x, y)) continue;
                        unique.putIfAbsent(tileKey(x, y), Map.of(
                            "x", x, "y", y, "steps_from_core", steps
                        ));
                    }
                }
            }
            List<Map<String, Object>> placements = new ArrayList<>(unique.values());
            placements.sort(Comparator
                .comparingInt((Map<String, Object> placement) -> ((Number) placement.get("x")).intValue())
                .thenComparingInt(placement -> ((Number) placement.get("y")).intValue()));
            result.put(wallBlock.name, evenlySamplePlacements(placements, 16));
        }
        return result;
    }

    private List<int[]> groundTerrainPath(int[] spawn, CoreBuild core) {
        int width = Vars.world.width(), height = Vars.world.height();
        int start = tileKey(spawn[0], spawn[1]);
        if (start < 0 || start >= width * height) return null;
        int[] previous = new int[width * height];
        java.util.Arrays.fill(previous, -1);
        boolean[] visited = new boolean[width * height];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        visited[start] = true;
        int reached = -1;
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            int x = current % width, y = current / width;
            Tile tile = Vars.world.tile(x, y);
            if (tile != null && tile.build == core) {
                reached = current;
                break;
            }
            for (int[] direction : DIRECTIONS) {
                int nx = x + direction[0], ny = y + direction[1];
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                int next = nx + ny * width;
                if (visited[next]) continue;
                Tile nextTile = Vars.world.tile(nx, ny);
                if (!groundTerrainPassable(nextTile, core)) continue;
                visited[next] = true;
                previous[next] = current;
                queue.addLast(next);
            }
        }
        if (reached < 0) return null;
        List<int[]> reversed = new ArrayList<>();
        for (int cursor = reached; cursor >= 0; cursor = previous[cursor]) {
            reversed.add(new int[]{cursor % width, cursor / width});
            if (cursor == start) break;
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private boolean groundTerrainPassable(Tile tile, CoreBuild core) {
        if (tile == null) return false;
        if (tile.build == core) return true;
        if (tile.floor() != null && tile.floor().isDeep()) return false;
        // Player buildings are destructible obstacles; natural solid blocks are permanent terrain barriers.
        return !tile.solid() || tile.build != null;
    }

    private int[] pathPointFromCore(List<int[]> spawnToCorePath, int stepsFromCore) {
        int index = Math.max(0, spawnToCorePath.size() - 1 - Math.max(0, stepsFromCore));
        return spawnToCorePath.get(index);
    }

    private List<List<Integer>> groundRouteRowsFromCore(List<int[]> spawnToCorePath) {
        List<List<Integer>> result = new ArrayList<>();
        int lastIndex = spawnToCorePath.size() - 1;
        for (int steps = 0; steps <= lastIndex; steps++) {
            int[] point = pathPointFromCore(spawnToCorePath, steps);
            result.add(List.of(steps, point[0], point[1]));
        }
        return result;
    }

    private List<Map<String, Object>> existingTurretRouteEngagements(
        List<TurretBuild> turrets, List<int[]> groundPath, CoreBuild core, int[] airThreat) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TurretBuild build : turrets) {
            if (!(build.block instanceof Turret turret)) continue;
            List<List<Integer>> groundIntervals = turret.targetGround && groundPath != null
                ? groundRouteIntervals(turret, build.tileX(), build.tileY(), groundPath) : List.of();
            List<List<Integer>> airIntervals = turret.targetAir
                ? airRouteIntervals(turret, build.tileX(), build.tileY(), core, airThreat) : List.of();
            if (groundIntervals.isEmpty() && airIntervals.isEmpty()) continue;
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("block", turret.name);
            evidence.put("x", build.tileX());
            evidence.put("y", build.tileY());
            evidence.put("health_fraction", build.maxHealth <= 0f ? 0f : build.health / build.maxHealth);
            evidence.put("ammo_fraction", build instanceof ItemTurretBuild itemTurret
                ? itemTurret.getAmmoFraction() : null);
            evidence.put("estimated_ground_dps_with_loaded_ammo", turretEstimatedDps(build, false));
            evidence.put("estimated_air_dps_with_loaded_ammo", turretEstimatedDps(build, true));
            evidence.put("ground_route_intervals", groundIntervals);
            evidence.put("air_route_distance_intervals", airIntervals);
            result.add(evidence);
        }
        return result;
    }

    private List<List<Integer>> groundRouteIntervals(
        Turret turret, int turretX, int turretY, List<int[]> spawnToCorePath) {
        List<List<Integer>> result = new ArrayList<>();
        int intervalStart = -1;
        int lastIndex = spawnToCorePath.size() - 1;
        for (int steps = 0; steps <= lastIndex; steps++) {
            int[] point = pathPointFromCore(spawnToCorePath, steps);
            boolean engaged = turretCanEngagePoint(turret, turretX, turretY, point[0], point[1], false);
            if (engaged && intervalStart < 0) intervalStart = steps;
            if (!engaged && intervalStart >= 0) {
                result.add(List.of(intervalStart, steps - 1, steps - intervalStart));
                intervalStart = -1;
            }
        }
        if (intervalStart >= 0) {
            result.add(List.of(intervalStart, lastIndex, lastIndex - intervalStart + 1));
        }
        return result;
    }

    private List<List<Integer>> airRouteIntervals(
        Turret turret, int turretX, int turretY, CoreBuild core, int[] airThreat) {
        List<List<Integer>> result = new ArrayList<>();
        double dx = airThreat[0] - core.tileX(), dy = airThreat[1] - core.tileY();
        int lastDistance = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy)));
        double length = Math.max(1d, Math.sqrt(dx * dx + dy * dy));
        double ux = dx / length, uy = dy / length;
        int intervalStart = -1;
        for (int distance = 0; distance <= lastDistance; distance++) {
            double x = core.tileX() + ux * Math.min(distance, length);
            double y = core.tileY() + uy * Math.min(distance, length);
            boolean engaged = turretCanEngagePoint(turret, turretX, turretY, x, y, true);
            if (engaged && intervalStart < 0) intervalStart = distance;
            if (!engaged && intervalStart >= 0) {
                result.add(List.of(intervalStart, distance - 1, distance - intervalStart));
                intervalStart = -1;
            }
        }
        if (intervalStart >= 0) {
            result.add(List.of(intervalStart, lastDistance, lastDistance - intervalStart + 1));
        }
        return result;
    }

    private boolean turretCanEngagePoint(
        Turret turret, int turretX, int turretY, double targetX, double targetY, boolean air) {
        if (air && !turret.targetAir || !air && !turret.targetGround) return false;
        double dx = turretX - targetX, dy = turretY - targetY;
        double distance = Math.sqrt(dx * dx + dy * dy);
        double maximum = turret.range / Vars.tilesize;
        double minimum = turret.minRange / Vars.tilesize;
        return distance <= maximum && distance >= minimum;
    }

    private List<Map<String, Object>> evenlySamplePlacements(
        List<Map<String, Object>> placements, int limit) {
        if (placements.size() <= limit) return new ArrayList<>(placements);
        List<Map<String, Object>> result = new ArrayList<>();
        int previousIndex = -1;
        for (int sample = 0; sample < limit; sample++) {
            int index = (int) Math.round((double) sample * (placements.size() - 1) / (limit - 1));
            if (index != previousIndex) result.add(placements.get(index));
            previousIndex = index;
        }
        return result;
    }

    private int[] effectiveDefenseThreat(CoreBuild core, int[] spawn, Turret turret) {
        if (turret.targetGround) {
            List<int[]> path = groundTerrainPath(spawn, core);
            if (path != null) return pathPointFromCore(path, Math.min(24, Math.max(1, path.size() - 1)));
        }
        return spawn;
    }

    private boolean turretCoversPoint(TurretBuild build, double x, double y, boolean air) {
        if (!(build.block instanceof Turret turret)) return false;
        if (air && !turret.targetAir || !air && !turret.targetGround) return false;
        double dx = build.tileX() - x, dy = build.tileY() - y;
        double distance = Math.sqrt(dx * dx + dy * dy);
        double range = turret.range / Vars.tilesize;
        double minimum = turret.minRange / Vars.tilesize;
        return distance <= range && distance >= minimum;
    }

    private double turretEstimatedDps(TurretBuild build, boolean air) {
        if (!(build.block instanceof Turret turret)) return 0d;
        if (air && !turret.targetAir || !air && !turret.targetGround) return 0d;
        BulletType bullet = build.peekAmmo();
        if (bullet == null || air && !bullet.collidesAir || !air && !bullet.collidesGround) return 0d;
        int shots = turret.shoot == null ? 1 : Math.max(1, turret.shoot.shots);
        float firstShotDelay = turret.shoot == null ? 0f : turret.shoot.firstShotDelay;
        float reloadTicks = turret.reload + (!turret.reloadWhileCharging ? firstShotDelay : 0f);
        if (reloadTicks <= 0f) return 0d;
        float shotsPerSecond = 60f / reloadTicks * shots;
        return bullet.estimateDPS() * shotsPerSecond * bullet.reloadMultiplier;
    }

    private List<Map<String, Object>> oreState(CoreBuild core) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        if (core == null) return candidates;
        int minX = Math.max(0, core.tileX() - ORE_SCAN_RADIUS);
        int maxX = Math.min(Vars.world.width() - 1, core.tileX() + ORE_SCAN_RADIUS);
        int minY = Math.max(0, core.tileY() - ORE_SCAN_RADIUS);
        int maxY = Math.min(Vars.world.height() - 1, core.tileY() + ORE_SCAN_RADIUS);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                Tile tile = Vars.world.tile(x, y);
                Item drop = tile == null ? null : tile.drop();
                if (drop != null) candidates.add(Map.of("item", drop.name, "x", x, "y", y));
            }
        }
        candidates.sort(Comparator.comparingDouble(value -> {
            int dx = (int) value.get("x") - core.tileX();
            int dy = (int) value.get("y") - core.tileY();
            return dx * dx + dy * dy;
        }));
        return new ArrayList<>(candidates.subList(0, Math.min(candidates.size(), MAX_ORES_IN_STATE)));
    }

    private Map<String, List<Map<String, Object>>> placementHints(CoreBuild core) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        if (core == null) return result;
        boolean stockpileRts = "stockpile_rts_pvp".equals(gameModeVariant);
        List<String> blockNames = stockpileRts ? List.of(
            "ground-factory", "air-factory", "naval-factory",
            "additive-reconstructor", "multiplicative-reconstructor",
            "exponential-reconstructor", "tetrative-reconstructor"
        ) : List.of(
            "mechanical-drill", "pneumatic-drill", "conveyor", "router",
            "duo", "scatter", "scorch", "hail", "salvo", "lancer", "arc",
            "copper-wall", "copper-wall-large", "titanium-wall", "titanium-wall-large", "mender",
            "combustion-generator", "steam-generator", "solar-panel", "power-node", "battery",
            "graphite-press", "silicon-smelter", "kiln", "water-extractor", "mechanical-pump",
            "conduit", "liquid-router", "ground-factory", "air-factory", "naval-factory"
        );
        for (String blockName : blockNames) {
            Block block = findBlock(blockName);
            if (block == null || Vars.state.rules.isBanned(block)) continue;
            if (!canAfford(core, effectiveCost(block))) continue;
            List<Map<String, Object>> candidates = new ArrayList<>();
            int radius = stockpileRts ? RTS_PLACEMENT_HINT_RADIUS : PLACEMENT_HINT_RADIUS;
            int minX = Math.max(0, core.tileX() - radius);
            int maxX = Math.min(Vars.world.width() - 1, core.tileX() + radius);
            int minY = Math.max(0, core.tileY() - radius);
            int maxY = Math.min(Vars.world.height() - 1, core.tileY() + radius);
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    if (Build.validPlace(block, controlledTeam(), x, y, 0)
                        && (!stockpileRts || !rtsPlacementReserved(block, x, y))) {
                        int dx = x - core.tileX();
                        int dy = y - core.tileY();
                        Map<String, Object> candidate = new LinkedHashMap<>();
                        candidate.put("x", x);
                        candidate.put("y", y);
                        candidate.put("distance2_to_core", dx * dx + dy * dy);
                        if (block instanceof Drill drill) candidate.putAll(drillOutput(drill, x, y));
                        candidates.add(candidate);
                    }
                }
            }
            if (stockpileRts) {
                candidates = distributedRtsPlacementHints(candidates, core);
            } else {
                candidates.sort(Comparator.comparingInt(value -> (int) value.get("distance2_to_core")));
            }
            result.put(blockName,
                new ArrayList<>(candidates.subList(0, Math.min(candidates.size(), MAX_PLACEMENT_HINTS_PER_BLOCK))));
        }
        return result;
    }

    private List<Map<String, Object>> distributedRtsPlacementHints(
        List<Map<String, Object>> candidates, CoreBuild core
    ) {
        int[] bandLimits = {12, 26, 42, RTS_PLACEMENT_HINT_RADIUS};
        double[] targetDistances = {8.0, 20.0, 34.0, 50.0};
        Map<String, Map<String, Object>> bestByRegion = new LinkedHashMap<>();
        Map<String, Double> bestScores = new LinkedHashMap<>();
        for (Map<String, Object> candidate : candidates) {
            int dx = (int) candidate.get("x") - core.tileX();
            int dy = (int) candidate.get("y") - core.tileY();
            double distance = Math.sqrt(dx * dx + dy * dy);
            int band = 0;
            while (band < bandLimits.length - 1 && distance > bandLimits[band]) band++;
            double angle = Math.atan2(dy, dx);
            int sector = Math.floorMod((int) Math.floor((angle + Math.PI / 8.0) / (Math.PI / 4.0)), 8);
            String key = band + ":" + sector;
            double score = Math.abs(distance - targetDistances[band]);
            if (bestScores.containsKey(key) && bestScores.get(key) <= score) continue;

            Map<String, Object> enriched = new LinkedHashMap<>(candidate);
            enriched.put("distance_tiles", Math.round(distance * 10.0) / 10.0);
            enriched.put("distance_band", band);
            enriched.put("direction_sector", sector);
            enriched.put("relative_to_core", Map.of("dx", dx, "dy", dy));
            bestByRegion.put(key, enriched);
            bestScores.put(key, score);
        }

        List<Map<String, Object>> distributed = new ArrayList<>();
        for (int band = 0; band < bandLimits.length; band++) {
            for (int sector = 0; sector < 8; sector++) {
                Map<String, Object> candidate = bestByRegion.get(band + ":" + sector);
                if (candidate != null) distributed.add(candidate);
            }
        }
        return distributed;
    }

    private List<Map<String, Object>> availableBlocks() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Block block : Vars.content.blocks()) {
            if (!blockAvailable(block)) continue;
            Map<String, Object> value = blockDefinition(block);
            value.put("affordable_now", canAfford(controlledTeam().core(), effectiveCost(block)));
            result.add(value);
        }
        result.sort(Comparator
            .comparing((Map<String, Object> value) -> String.valueOf(value.get("category")))
            .thenComparing(value -> String.valueOf(value.get("name"))));
        return result;
    }

    private boolean blockAvailable(Block block) {
        return block != null && block.canBeBuilt() && block.isVisible()
            && block.buildVisibility != null && block.buildVisibility.visible()
            && block.supportsEnv(Vars.state.rules.env)
            && !Vars.state.rules.isBanned(block);
    }

    private Map<String, Object> blockDefinition(Block block) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", block.name);
        value.put("category", block.category == null ? "unknown" : block.category.name());
        value.put("size", block.size);
        value.put("cost", blockCost(block));
        value.put("build_time_seconds", block.buildTime / 60f);
        value.put("role", blockRole(block.name));
        value.put("max_health", block.health);
        if (block instanceof Turret) value.put("defense_kind", "turret");
        if (block instanceof Wall) value.put("defense_kind", "wall");
        value.put("item_capacity", block.itemCapacity);
        value.put("liquid_capacity", block.liquidCapacity);
        value.put("accepts_items", block.acceptsItems);
        value.put("outputs_items", block.outputsItems());
        value.put("has_power", block.hasPower);
        value.put("consumes_power", hasRequiredPowerInput(block));
        value.put("outputs_power", block.outputsPower);

        List<Map<String, Object>> consumers = new ArrayList<>();
        if (block.consumers != null) {
            for (Consume consumer : block.consumers) {
                if (consumer == null || consumer.ignore()) continue;
                Map<String, Object> definition = consumerDefinition(consumer);
                definition.put("optional", consumer.optional);
                definition.put("booster", consumer.booster);
                consumers.add(definition);
            }
        }
        value.put("inputs", consumers);

        if (block instanceof GenericCrafter crafter) {
            Map<String, Object> recipe = new LinkedHashMap<>();
            recipe.put("craft_time_seconds", crafter.craftTime / 60f);
            recipe.put("item_outputs_per_craft", namedStacks(crafterOutputs(crafter)));
            recipe.put("liquid_outputs_per_second", crafterLiquidOutputs(crafter));
            value.put("recipe", recipe);
        }
        if (block instanceof Drill drill) {
            value.put("drill", Map.of(
                "tier", drill.tier,
                "base_drill_time_seconds", drill.drillTime / 60f,
                "output", "dominant mineable ore under footprint; exact output is in placement samples"
            ));
        }
        Float transportThroughput = itemTransportThroughputPerSecond(block);
        if (transportThroughput != null) {
            value.put("item_throughput_per_second", transportThroughput);
        }
        if (block instanceof PowerGenerator generator) {
            value.put("power_output_per_second", generator.powerProduction * 60f);
        }
        if (block instanceof PowerNode node) {
            value.put("power_connection", Map.of(
                "link_range_tiles", node.laserRange,
                "max_links", node.maxNodes,
                "mechanic", "wireless links; node tiles do not need to be contiguous"
            ));
        }
        if (block instanceof ConsumeGenerator generator) {
            value.put("fuel", Map.of(
                "accepted_item_demand_per_second", nominalItemInputRatesPerSecondForBlock(generator),
                "base_item_duration_seconds", generator.itemDuration / 60f
            ));
        }
        if (block instanceof Turret turret) value.put("combat", turretCombatDefinition(turret));
        if (block instanceof UnitFactory factory) {
            List<Map<String, Object>> plans = new ArrayList<>();
            for (UnitFactory.UnitPlan plan : factory.plans) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("unit", plan.unit.name);
                row.put("time_seconds", plan.time / 60f);
                row.put("item_cost", namedStacks(List.of(plan.requirements)));
                row.put("unit_stats", unitCombatDefinition(plan.unit));
                plans.add(row);
            }
            value.put("unit_plans", plans);
        }
        if (block instanceof Reconstructor reconstructor) {
            Map<String, Integer> itemCost = namedCost(requiredItemConsumerCost(reconstructor));
            List<Map<String, Object>> upgrades = new ArrayList<>();
            for (UnitType[] pair : reconstructor.upgrades) {
                if (pair == null || pair.length < 2 || pair[0] == null || pair[1] == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("from_unit", pair[0].name);
                row.put("to_unit", pair[1].name);
                row.put("time_seconds", reconstructor.constructTime / 60f);
                row.put("item_cost", itemCost);
                row.put("from_stats", unitCombatDefinition(pair[0]));
                row.put("to_stats", unitCombatDefinition(pair[1]));
                upgrades.add(row);
            }
            value.put("unit_upgrades", upgrades);
        }
        return value;
    }

    private Map<String, Object> unitCombatDefinition(UnitType unit) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("health", unit.health);
        value.put("armor", unit.armor);
        value.put("speed_tiles_per_second", unit.speed * 60f / Vars.tilesize);
        value.put("range_tiles", Math.max(unit.range, unit.maxRange) / Vars.tilesize);
        value.put("estimated_dps", unit.estimateDps());
        value.put("movement", unit.flying ? "air" : unit.naval ? "naval" : unit.hovering ? "hover" : "ground");
        value.put("targets_air", unit.targetAir);
        value.put("targets_ground", unit.targetGround);
        value.put("can_attack", unit.canAttack && unit.hasWeapons());
        value.put("build_speed", unit.buildSpeed);
        value.put("mine_tier", unit.mineTier);
        value.put("mine_speed", unit.mineSpeed);
        value.put("item_capacity", unit.itemCapacity);
        value.put("payload_capacity", unit.payloadCapacity);
        value.put("can_heal", unit.canHeal);
        List<Map<String, Object>> weapons = new ArrayList<>();
        for (Weapon weapon : unit.weapons) {
            if (weapon == null || weapon.noAttack || weapon.bullet == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", weapon.name);
            row.put("range_tiles", weapon.range() / Vars.tilesize);
            row.put("shots_per_second", weapon.shotsPerSec());
            row.put("estimated_dps", weapon.dps());
            row.put("direct_damage", weapon.bullet.damage);
            row.put("splash_damage", weapon.bullet.splashDamage);
            row.put("splash_radius_tiles", Math.max(0f, weapon.bullet.splashDamageRadius) / Vars.tilesize);
            row.put("hits_air", weapon.bullet.collidesAir);
            row.put("hits_ground", weapon.bullet.collidesGround);
            row.put("building_damage_multiplier", weapon.bullet.buildingDamageMultiplier);
            row.put("armor_piercing", weapon.bullet.pierceArmor);
            row.put("homing", weapon.bullet.homingPower > 0f);
            weapons.add(row);
        }
        value.put("weapons", weapons);
        value.put("ability_count", unit.abilities.size);
        return value;
    }

    private Map<Item, Integer> requiredItemConsumerCost(Block block) {
        Map<Item, Integer> result = new LinkedHashMap<>();
        if (block.consumers == null) return result;
        for (Consume consumer : block.consumers) {
            if (!(consumer instanceof ConsumeItems items) || consumer.optional) continue;
            for (ItemStack stack : items.items) result.merge(stack.item, stack.amount, Integer::sum);
        }
        return result;
    }

    private Map<String, Float> nominalItemInputRatesPerSecondForBlock(ConsumeGenerator generator) {
        Map<String, Float> result = new LinkedHashMap<>();
        if (generator.itemDuration <= 0f || generator.filterItem == null) return result;
        for (Item item : Vars.content.items()) {
            if (!generator.filterItem.filter.get(item)) continue;
            float durationMultiplier = generator.itemDurationMultipliers.get(item, 1f);
            result.put(item.name,
                60f / (generator.itemDuration * Math.max(0.0001f, durationMultiplier)));
        }
        return result;
    }

    private Map<String, Object> turretCombatDefinition(Turret turret) {
        Map<String, Object> combat = new LinkedHashMap<>();
        int shots = turret.shoot == null ? 1 : Math.max(1, turret.shoot.shots);
        float firstShotDelay = turret.shoot == null ? 0f : turret.shoot.firstShotDelay;
        float reloadTicks = turret.reload + (!turret.reloadWhileCharging ? firstShotDelay : 0f);
        float shotsPerSecond = reloadTicks <= 0f ? 0f : 60f / reloadTicks * shots;
        combat.put("range_tiles", turret.range / Vars.tilesize);
        combat.put("minimum_range_tiles", turret.minRange / Vars.tilesize);
        combat.put("targets_air", turret.targetAir);
        combat.put("targets_ground", turret.targetGround);
        combat.put("targets_buildings", turret.targetBlocks);
        combat.put("reload_seconds", turret.reload / 60f);
        combat.put("shots_per_burst", shots);
        combat.put("base_shots_per_second", shotsPerSecond);
        combat.put("ammo_per_shot", turret.ammoPerShot);
        combat.put("inaccuracy_degrees", turret.inaccuracy);

        List<Map<String, Object>> ammunition = new ArrayList<>();
        if (turret instanceof ItemTurret itemTurret) {
            for (Item item : Vars.content.items()) {
                BulletType bullet = itemTurret.ammoTypes.get(item);
                if (bullet != null) ammunition.add(ammoCombatDefinition("item", item.name, bullet, shotsPerSecond));
            }
        } else if (turret instanceof LiquidTurret liquidTurret) {
            for (Liquid liquid : Vars.content.liquids()) {
                BulletType bullet = liquidTurret.ammoTypes.get(liquid);
                if (bullet != null) ammunition.add(ammoCombatDefinition("liquid", liquid.name, bullet, shotsPerSecond));
            }
        } else {
            BulletType bullet = null;
            if (turret instanceof PowerTurret powerTurret) bullet = powerTurret.shootType;
            if (turret instanceof ContinuousTurret continuousTurret) bullet = continuousTurret.shootType;
            if (bullet != null) ammunition.add(ammoCombatDefinition("power", "power", bullet, shotsPerSecond));
        }
        if (!ammunition.isEmpty()) combat.put("ammunition", ammunition);
        return combat;
    }

    private Map<String, Object> ammoCombatDefinition(String resourceType, String resourceName,
                                                      BulletType bullet, float baseShotsPerSecond) {
        Map<String, Object> ammo = new LinkedHashMap<>();
        ammo.put("resource_type", resourceType);
        ammo.put("resource", resourceName);
        ammo.put("direct_damage", bullet.damage);
        ammo.put("splash_damage", bullet.splashDamage);
        ammo.put("splash_radius_tiles", Math.max(0f, bullet.splashDamageRadius) / Vars.tilesize);
        ammo.put("estimated_damage_per_shot", bullet.estimateDPS());
        ammo.put("estimated_dps", bullet.estimateDPS() * baseShotsPerSecond * bullet.reloadMultiplier);
        ammo.put("ammo_multiplier", bullet.ammoMultiplier);
        ammo.put("reload_multiplier", bullet.reloadMultiplier);
        ammo.put("building_damage_multiplier", bullet.buildingDamageMultiplier);
        ammo.put("shield_damage_multiplier", bullet.shieldDamageMultiplier);
        ammo.put("hits_air", bullet.collidesAir);
        ammo.put("hits_ground", bullet.collidesGround);
        ammo.put("pierces", bullet.pierce);
        ammo.put("pierce_cap", bullet.pierceCap);
        ammo.put("armor_piercing", bullet.pierceArmor);
        ammo.put("homing", bullet.homingPower > 0f);
        ammo.put("incendiary", bullet.makeFire || bullet.incendAmount > 0);
        ammo.put("knockback", bullet.knockback);
        if (bullet.status != null && !"none".equals(bullet.status.name)) {
            ammo.put("status_effect", bullet.status.name);
            ammo.put("status_duration_seconds", bullet.statusDuration / 60f);
        }
        return ammo;
    }

    private boolean hasRequiredPowerInput(Block block) {
        if (block.consumers == null) return false;
        for (Consume consumer : block.consumers) {
            if (consumer instanceof ConsumePower power && !consumer.optional
                && (power.usage > 0f || power.capacity > 0f)) return true;
        }
        return false;
    }

    private Map<String, Object> consumerDefinition(Consume consumer) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", consumer.getClass().getSimpleName());
        if (consumer instanceof ConsumeItems items) {
            value.put("resource", "items");
            value.put("amount_per_cycle", namedStacks(List.of(items.items)));
        } else if (consumer instanceof ConsumeItemFilter filter) {
            List<String> accepted = new ArrayList<>();
            for (Item item : Vars.content.items()) if (filter.filter.get(item)) accepted.add(item.name);
            value.put("resource", "item_fuel");
            value.put("accepted_items", accepted);
            value.put("amount_per_cycle", 1);
        } else if (consumer instanceof ConsumeLiquid liquid) {
            value.put("resource", "liquid");
            value.put("liquid", liquid.liquid.name);
            value.put("amount_per_second", liquid.amount * 60f);
        } else if (consumer instanceof ConsumeLiquids liquids) {
            Map<String, Float> amounts = new LinkedHashMap<>();
            for (LiquidStack stack : liquids.liquids) amounts.put(stack.liquid.name, stack.amount * 60f);
            value.put("resource", "liquids");
            value.put("amount_per_second", amounts);
        } else if (consumer instanceof ConsumeLiquidFilter filter) {
            List<String> accepted = new ArrayList<>();
            for (Liquid liquid : Vars.content.liquids()) if (filter.filter.get(liquid)) accepted.add(liquid.name);
            value.put("resource", "liquid_filter");
            value.put("accepted_liquids", accepted);
            value.put("amount_per_second", filter.amount * 60f);
        } else if (consumer instanceof ConsumePower power) {
            value.put("resource", "power");
            value.put("usage_per_second", power.usage * 60f);
            value.put("buffer_capacity", power.capacity);
            value.put("buffered", power.buffered);
        } else {
            value.put("resource", "special");
        }
        return value;
    }

    private List<ItemStack> crafterOutputs(GenericCrafter crafter) {
        List<ItemStack> result = new ArrayList<>();
        if (crafter.outputItems != null && crafter.outputItems.length > 0) {
            Collections.addAll(result, crafter.outputItems);
        } else if (crafter.outputItem != null) {
            result.add(crafter.outputItem);
        }
        return result;
    }

    private Map<String, Float> crafterLiquidOutputs(GenericCrafter crafter) {
        Map<String, Float> result = new LinkedHashMap<>();
        if (crafter.outputLiquids != null && crafter.outputLiquids.length > 0) {
            for (LiquidStack stack : crafter.outputLiquids) result.put(stack.liquid.name, stack.amount * 60f);
        } else if (crafter.outputLiquid != null) {
            result.put(crafter.outputLiquid.liquid.name, crafter.outputLiquid.amount * 60f);
        }
        return result;
    }

    private Map<String, Integer> namedStacks(List<ItemStack> stacks) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (ItemStack stack : stacks) result.merge(stack.item.name, stack.amount, Integer::sum);
        return result;
    }

    private Map<String, Object> applyActions(JsonObject request) {
        ensureGameReady();
        String expectedEpisodeId = requireString(request, "expected_episode_id");
        if (!episodeId.equals(expectedEpisodeId)) {
            throw new StaleEpisodeException(
                "Observed episode " + expectedEpisodeId + " but current episode is " + episodeId + ".");
        }
        // Required for auditability even though v1 does not reject ordinary within-episode latency.
        if (!request.has("observed_tick") || !request.get("observed_tick").isJsonPrimitive()) {
            throw new IllegalArgumentException("observed_tick must be a number.");
        }
        request.get("observed_tick").getAsDouble();
        updateAgentTelemetry(request);
        JsonArray actions = requireArray(request, "actions");
        if (actions.size() > MAX_ACTIONS_PER_REQUEST) {
            throw new IllegalArgumentException("At most " + MAX_ACTIONS_PER_REQUEST + " actions are allowed.");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (int index = 0; index < actions.size(); index++) {
            JsonElement element = actions.get(index);
            if (!element.isJsonObject()) {
                results.add(actionError(index, "invalid_action", "Each action must be an object."));
                continue;
            }
            JsonObject action = element.getAsJsonObject();
            String type = requireString(action, "type");
            switch (type) {
                case "place" -> results.add(place(index, action));
                case "remove" -> results.add(remove(index, action));
                case "build_mine_to_core" -> results.add(buildMineToCore(index, action));
                case "build_mine_to_target" -> results.add(buildMineToTarget(index, action));
                case "build_core_defense" -> results.add(actionError(index, action,
                    "automatic_defense_placement_disabled",
                    "Choose exact turret and wall coordinates from defense_analysis raw routes, engagement intervals, and unranked valid placement samples, then use place actions."));
                case "route_items" -> results.add(routeTransport(index, action, false));
                case "route_items_isolated" -> results.add(routeTransport(index, action, false, true));
                case "clear_item_input_network" -> results.add(clearItemInputNetwork(index, action));
                case "upgrade_input_network" -> results.add(upgradeInputNetwork(index, action));
                case "inspect_item_route" -> results.add(inspectItemRoute(index, action));
                case "preview_conveyor_path" -> results.add(conveyorPathAction(index, action, true));
                case "place_conveyor_path" -> results.add(conveyorPathAction(index, action, false));
                case "route_liquid" -> results.add(routeTransport(index, action, true));
                case "connect_power" -> results.add(connectPower(index, action));
                case "set_unit_factory_plan" -> results.add(setUnitFactoryPlan(index, action));
                case "train_units" -> results.add(trainUnits(index, action));
                case "upgrade_units" -> results.add(upgradeUnits(index, action));
                case "command_units" -> results.add(commandUnits(index, action));
                case "configure_item_filter" -> results.add(configureItemFilter(index, action));
                case "resupply_turrets" -> results.add(resupplyTurrets(index, action));
                case "command_core_unit" -> results.add(commandCoreUnit(index, action));
                default -> results.add(actionError(index, action, "unsupported_action",
                    "Supported actions: place, remove, build_mine_to_core, build_mine_to_target, "
                        + "route_items, route_items_isolated, clear_item_input_network, upgrade_input_network, inspect_item_route, preview_conveyor_path, place_conveyor_path, route_liquid, connect_power, set_unit_factory_plan, command_units, configure_item_filter, "
                        + "train_units, upgrade_units, resupply_turrets, command_core_unit."));
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("request_id", optionalString(request, "request_id", UUID.randomUUID().toString()));
        response.put("episode_id", episodeId);
        response.put("tick", Vars.state.tick);
        response.put("results", results);
        return response;
    }

    private void updateAgentTelemetry(JsonObject request) {
        JsonElement raw = request.get("agent_telemetry");
        if (raw == null || !raw.isJsonObject()) return;
        JsonObject telemetry = raw.getAsJsonObject();
        TeamAgentState state = teamAgentState(controlledTeam());
        state.modelName = boundedHudText(optionalTelemetryString(telemetry, "model"), 48);
        state.strategy = boundedHudText(
            optionalTelemetryString(telemetry, "strategy").isBlank()
                ? optionalTelemetryString(telemetry, "reasoning_summary")
                : optionalTelemetryString(telemetry, "strategy"),
            120
        );
        state.lastDecisionTurn = optionalTelemetryInt(telemetry, "turn", state.lastDecisionTurn);
        state.lastLatencySeconds = optionalTelemetryDouble(
            telemetry, "latency_seconds", state.lastLatencySeconds, 0d, 999d
        );
        state.telemetryLatencyTotal += state.lastLatencySeconds;
        state.telemetryLatencySamples++;
        state.lastExecutedActionCount = optionalTelemetryInt(
            telemetry, "executed_action_count", state.lastExecutedActionCount
        );
        state.lastPreflightSkippedCount = optionalTelemetryInt(
            telemetry, "preflight_skipped_count", state.lastPreflightSkippedCount
        );
        state.lastTelemetryTick = Vars.state == null ? 0d : Vars.state.tick;
    }

    private void updateSpectatorHud() {
        if (Vars.state == null || !Vars.state.isGame() || !Vars.state.rules.pvp) {
            if (spectatorHudVisible) {
                Call.hideHudText();
                spectatorHudVisible = false;
            }
            return;
        }
        if (Vars.state.tick < nextSpectatorHudTick) return;
        nextSpectatorHudTick = Vars.state.tick + 30d;
        boolean hasTelemetry = teamAgentStates.values().stream()
            .anyMatch(state -> state.telemetryLatencySamples > 0);
        if (!hasTelemetry) return;

        StringBuilder hud = new StringBuilder("[accent]LLM PvP 관전[]");
        if (gameWinner != null) {
            hud.append("\n[accent]경기 종료 — ").append(teamMarkup(gameWinner)).append(" 승리[]")
                .append(" (적 코어 파괴)");
        }
        appendSpectatorTeam(hud, Team.sharded, "[sky]");
        appendSpectatorTeam(hud, Team.crux, "[scarlet]");
        if (!rtsControlPoints.isEmpty()) {
            hud.append('\n').append("거점: ");
            for (int index = 0; index < rtsControlPoints.size(); index++) {
                if (index > 0) hud.append(" | ");
                RtsControlPoint point = rtsControlPoints.get(index);
                hud.append(point.id).append('=')
                    .append(point.owner == null ? "중립" : point.owner.name);
                if (point.contested) hud.append("(교전)");
                else if (point.capturingTeam != null) {
                    hud.append('(').append(point.capturingTeam.name).append(' ')
                        .append(String.format(Locale.ROOT, "%.0f%%", point.captureProgress * 100d)).append(')');
                }
            }
            hud.append('\n').append("거점 보너스: ")
                .append(teamMarkup(Team.sharded)).append(' ')
                .append(String.format(Locale.ROOT, "x%.1f", rtsProductionSpeedMultiplier(Team.sharded)))
                .append(" | ").append(teamMarkup(Team.crux)).append(' ')
                .append(String.format(Locale.ROOT, "x%.1f", rtsProductionSpeedMultiplier(Team.crux)))
                .append(" (점령 범위 내 초당 1% 회복)");
        }
        Call.setHudText(hud.toString());
        spectatorHudVisible = true;
    }

    private void appendSpectatorTeam(StringBuilder hud, Team team, String color) {
        TeamAgentState state = teamAgentState(team);
        Team opponent = team == Team.sharded ? Team.crux : Team.sharded;
        int losses = state.unitLossesByType.values().stream().mapToInt(Integer::intValue).sum();
        int kills = teamAgentState(opponent).unitLossesByType.values().stream()
            .mapToInt(Integer::intValue).sum();
        CoreBuild core = team.core();
        String coreHealth = core == null
            ? "파괴됨"
            : String.format(Locale.ROOT, "%.0f/%.0f", core.health, core.maxHealth);
        double averageLatency = state.telemetryLatencySamples == 0
            ? 0d : state.telemetryLatencyTotal / state.telemetryLatencySamples;
        String model = state.modelName.isBlank() ? "에이전트 대기 중" : state.modelName;
        hud.append('\n').append(color).append(team.name).append("[]  [white]").append(model).append("[]")
            .append("  코어 ").append(coreHealth)
            .append(" | 유닛 ").append(team.data().units.size)
            .append(" | 처치/손실 ").append(kills).append('/').append(losses)
            .append(" | 판단 ").append(Math.max(0, state.lastDecisionTurn))
            .append(" | 응답 ").append(String.format(Locale.ROOT, "%.1f초 (평균 %.1f초)",
                state.lastLatencySeconds, averageLatency))
            .append(" | 행동 ").append(state.lastExecutedActionCount);
        if (state.lastPreflightSkippedCount > 0) {
            hud.append(" (실행 전 제외 ").append(state.lastPreflightSkippedCount).append(')');
        }
        hud.append('\n').append("전략: ")
            .append(state.strategy.isBlank() ? "첫 판단 대기 중" : state.strategy);
    }

    private String optionalTelemetryString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) return "";
        try {
            return value.getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private int optionalTelemetryInt(JsonObject object, String name, int fallback) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) return fallback;
        try {
            return Math.max(0, value.getAsInt());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private double optionalTelemetryDouble(
        JsonObject object, String name, double fallback, double minimum, double maximum
    ) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) return fallback;
        try {
            double parsed = value.getAsDouble();
            if (!Double.isFinite(parsed)) return fallback;
            return Math.max(minimum, Math.min(parsed, maximum));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private String boundedHudText(String value, int maximumLength) {
        if (value == null) return "";
        String normalized = value.replace('[', '(').replace(']', ')')
            .replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        return normalized.length() <= maximumLength
            ? normalized : normalized.substring(0, maximumLength - 1) + "…";
    }

    private Map<String, Object> clearItemInputNetwork(int index, JsonObject action) {
        int targetX = requireInt(action, "target_x");
        int targetY = requireInt(action, "target_y");
        int maxSegments = Math.max(1, Math.min(requireInt(action, "max_segments"), 300));
        Building target = ownedBuildingAt(targetX, targetY);
        if (target == null) {
            return actionError(index, action, "invalid_target", "No owned building exists at the target coordinates.");
        }
        Set<String> accepted = acceptedItemTypes(target);
        if (accepted.isEmpty() || target instanceof CoreBuild) {
            return actionError(index, action, "target_not_selective_sink",
                "clear_item_input_network requires a non-core building with explicit accepted item inputs.");
        }

        List<Building> allTransports = new ArrayList<>();
        for (Building building : controlledTeam().data().buildings) {
            if (isDirectedItemTransport(building)) allTransports.add(building);
        }
        Set<Building> network = new LinkedHashSet<>();
        ArrayDeque<Building> queue = new ArrayDeque<>();
        for (Building transport : allTransports) if (transport.front() == target) queue.add(transport);
        while (!queue.isEmpty()) {
            Building current = queue.removeFirst();
            if (!network.add(current)) continue;
            if (network.size() > maxSegments) {
                return actionError(index, action, "input_network_exceeds_limit",
                    "Observed input network exceeds max_segments; nothing was removed.",
                    Map.of("observed_at_least", network.size(), "max_segments", maxSegments));
            }
            for (Building candidate : allTransports) {
                if (!network.contains(candidate) && candidate.front() == current) queue.addLast(candidate);
            }
        }
        if (network.isEmpty()) {
            return actionError(index, action, "no_input_network", "No directed item transport feeds this sink.");
        }

        List<Map<String, Object>> removed = network.stream().map(this::transportDiagnostic).toList();
        for (Building transport : network) {
            if (transport.isAdded()) Call.removeTile(transport.tile);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("ok", true);
        result.put("type", "clear_item_input_network");
        result.put("target", buildingRef(target));
        result.put("accepted_items", accepted);
        result.put("removed_segment_count", removed.size());
        result.put("removed_segments", removed);
        result.put("refund", Map.of());
        result.put("next_observation_fact",
            "The sink now has no directed item input; choose and construct replacement source-to-sink routes explicitly.");
        return result;
    }

    private Map<String, Object> upgradeInputNetwork(int index, JsonObject action) {
        int targetX = requireInt(action, "target_x");
        int targetY = requireInt(action, "target_y");
        String transportName = requireString(action, "transport");
        int maxCost = requireInt(action, "max_cost");
        int reserveCopper = optionalInt(action, "reserve_copper", 0);
        Building target = Vars.world.build(targetX, targetY);
        if (target == null || target.team != controlledTeam()) {
            return actionError(index, action, "invalid_target", "No owned building exists at the target coordinates.");
        }
        if (acceptedItemTypes(target).isEmpty()) {
            return actionError(index, action, "target_not_selective_sink",
                "upgrade_input_network requires a sink with explicit accepted item inputs.");
        }
        Block replacement = findBlock(transportName);
        if (replacement == null || !blockAvailable(replacement) || replacement.size != 1
            || !replacement.rotate || !replacement.conveyorPlacement || !replacement.hasItems
            || !replacement.outputsItems()) {
            return actionError(index, action, "invalid_transport", transportName + " is not an available directed item transport.");
        }

        List<Building> allTransports = new ArrayList<>();
        for (Building building : controlledTeam().data().buildings) {
            if (isDirectedItemTransport(building)) allTransports.add(building);
        }
        Set<Building> network = new LinkedHashSet<>();
        ArrayDeque<Building> queue = new ArrayDeque<>();
        for (Building transport : allTransports) if (transport.front() == target) queue.add(transport);
        while (!queue.isEmpty() && network.size() < 160) {
            Building current = queue.removeFirst();
            if (!network.add(current)) continue;
            for (Building candidate : allTransports) {
                if (!network.contains(candidate) && candidate.front() == current) queue.addLast(candidate);
            }
        }
        if (network.isEmpty()) {
            return actionError(index, action, "no_input_network", "No directed item transport feeds this sink.");
        }

        List<Building> changed = network.stream().filter(building -> building.block != replacement).toList();
        if (changed.isEmpty()) {
            return actionError(index, action, "already_upgraded", "Every segment in this input network already uses " + transportName + ".");
        }
        for (Building existing : changed) {
            if (!replacement.canReplace(existing.block)) {
                return actionError(index, action, "transport_not_replaceable",
                    replacement.name + " cannot replace " + existing.block.name + " at " + existing.tileX() + "," + existing.tileY() + ".");
            }
        }

        CoreBuild core = controlledTeam().core();
        Map<Item, Integer> unitCost = effectiveCost(replacement);
        Map<Item, Integer> total = new LinkedHashMap<>();
        for (int i = 0; i < changed.size(); i++) {
            unitCost.forEach((item, amount) -> total.merge(item, amount, Integer::sum));
        }
        int copperCost = total.getOrDefault(Items.copper, 0);
        if (copperCost > maxCost) {
            return actionError(index, action, "max_cost_exceeded",
                "Upgrade needs " + copperCost + " copper for " + changed.size() + " segments.");
        }
        if (!Vars.state.rules.infiniteResources) {
            for (Map.Entry<Item, Integer> entry : total.entrySet()) {
                int remaining = core.items.get(entry.getKey()) - entry.getValue();
                if (remaining < 0) return actionError(index, action, "insufficient_resources",
                    "Need " + entry.getValue() + " " + entry.getKey().name + ".");
                if (entry.getKey() == Items.copper && remaining < reserveCopper) {
                    return actionError(index, action, "copper_reserve",
                        "Upgrade would leave " + remaining + " copper below reserve " + reserveCopper + ".");
                }
            }
            total.forEach((item, amount) -> core.items.remove(item, amount));
        }

        List<Map<String, Object>> placements = new ArrayList<>();
        for (Building existing : changed) {
            int x = existing.tileX(), y = existing.tileY(), rotation = existing.rotation;
            Map<Item, Integer> carried = new LinkedHashMap<>();
            if (existing.items != null) {
                for (Item item : Vars.content.items()) {
                    int amount = existing.items.get(item);
                    if (amount > 0) carried.put(item, amount);
                }
            }
            Vars.world.tile(x, y).setNet(replacement, controlledTeam(), rotation);
            Building upgraded = Vars.world.build(x, y);
            if (upgraded != null && upgraded.items != null) {
                for (Map.Entry<Item, Integer> entry : carried.entrySet()) {
                    upgraded.items.add(entry.getKey(), Math.min(entry.getValue(), replacement.itemCapacity));
                }
            }
            placements.add(Map.of("x", x, "y", y, "rotation", rotation));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("ok", true);
        result.put("type", "upgrade_input_network");
        result.put("target", buildingRef(target));
        result.put("transport", transportName);
        result.put("upgraded_segment_count", changed.size());
        result.put("preserved_rotations", true);
        result.put("cost", namedCost(total));
        result.put("placements", placements);
        return result;
    }

    private Map<String, Object> place(int index, JsonObject action) {
        String blockName = requireString(action, "block");
        int x = requireInt(action, "x");
        int y = requireInt(action, "y");
        int rotation = Math.floorMod(optionalInt(action, "rotation", 0), 4);

        Block block = findBlock(blockName);
        if (block == null) return actionError(index, action, "unknown_block", blockName);
        if (!blockAvailable(block)) return actionError(index, action, "block_unavailable", blockName);
        if (validItemTransport(block)) {
            return actionError(index, action, "transport_requires_authored_path",
                "Directed item transports cannot be installed with independent place actions. Inspect, preview, and commit one complete LLM-authored path instead.");
        }
        if ("stockpile_rts_pvp".equals(gameModeVariant) && rtsPlacementReserved(block, x, y)) {
            return actionError(index, action, "placement_reserved",
                "This footprint overlaps a pending RTS construction order. Choose another currently valid placement option.");
        }
        if (!Build.validPlace(block, controlledTeam(), x, y, rotation)) {
            return actionError(index, action, "invalid_placement", "Cannot place " + blockName + " at " + x + "," + y,
                placementDiagnostics(block, x, y, rotation));
        }

        CoreBuild core = controlledTeam().core();
        if (core == null) return actionError(index, action, "no_core", "Team " + controlledTeam().name + " has no core.");
        Map<Item, Integer> cost = effectiveCost(block);
        if (!Vars.state.rules.infiniteResources) {
            for (Map.Entry<Item, Integer> entry : cost.entrySet()) {
                if (core.items.get(entry.getKey()) < entry.getValue()) {
                    return actionError(index, action, "insufficient_resources",
                        "Need " + entry.getValue() + " " + entry.getKey().name);
                }
            }
            for (Map.Entry<Item, Integer> entry : cost.entrySet()) {
                core.items.remove(entry.getKey(), entry.getValue());
            }
        }

        if ("stockpile_rts_pvp".equals(gameModeVariant)) {
            return queueRtsConstruction(index, block, x, y, rotation, cost);
        }

        Tile tile = Vars.world.tile(x, y);
        tile.setNet(block, controlledTeam(), rotation);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("ok", true);
        result.put("type", "place");
        result.put("block", blockName);
        result.put("x", x);
        result.put("y", y);
        result.put("rotation", rotation);
        result.put("cost", namedCost(cost));
        return result;
    }

    private Map<String, Object> queueRtsConstruction(
        int index, Block block, int x, int y, int rotation, Map<Item, Integer> cost
    ) {
        Team team = controlledTeam();
        double startsAtTick = Vars.state.tick;
        int position = 1;
        for (RtsConstructionOrder order : rtsConstructionOrders) {
            if (order.team != team || !"queued".equals(order.status)) continue;
            startsAtTick = Math.max(startsAtTick, order.completesAtTick);
            position++;
        }
        double buildTicks = Math.max(1d, block.buildTime);
        RtsConstructionOrder order = new RtsConstructionOrder(
            team, block.name, x, y, rotation, buildTicks, startsAtTick,
            startsAtTick + buildTicks, new LinkedHashMap<>(cost)
        );
        rtsConstructionOrders.add(order);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("ok", true);
        result.put("type", "place");
        result.put("status", "queued");
        result.put("block", block.name);
        result.put("x", x);
        result.put("y", y);
        result.put("rotation", rotation);
        result.put("cost_reserved_immediately", namedCost(cost));
        result.put("team_queue_position", position);
        result.put("standard_build_time_seconds", buildTicks / 60d);
        result.put("seconds_until_construction_starts",
            Math.max(0d, startsAtTick - Vars.state.tick) / 60d);
        result.put("seconds_until_completed",
            Math.max(0d, order.completesAtTick - Vars.state.tick) / 60d);
        result.put("mechanic",
            "One sequential construction queue per team; choices and queue order come only from that team's agent.");
        return result;
    }

    private boolean rtsPlacementReserved(Block block, int x, int y) {
        Set<Integer> candidate = footprintKeys(block, x, y);
        for (RtsConstructionOrder order : rtsConstructionOrders) {
            if (!"queued".equals(order.status)) continue;
            Block reservedBlock = findBlock(order.blockName);
            if (reservedBlock == null) continue;
            Set<Integer> reserved = footprintKeys(reservedBlock, order.x, order.y);
            if (candidate.stream().anyMatch(reserved::contains)) return true;
        }
        return false;
    }

    private void updateRtsConstruction() {
        if (!"stockpile_rts_pvp".equals(gameModeVariant) || Vars.state == null || !Vars.state.isGame()
            || Vars.state.gameOver) return;
        for (RtsConstructionOrder order : rtsConstructionOrders) {
            if (!"queued".equals(order.status) || Vars.state.tick < order.completesAtTick) continue;
            Block block = findBlock(order.blockName);
            if (block == null || !blockAvailable(block)
                || !Build.validPlace(block, order.team, order.x, order.y, order.rotation)) {
                order.status = "cancelled_invalid_at_completion";
                refundRtsConstruction(order);
                continue;
            }
            Tile tile = Vars.world.tile(order.x, order.y);
            if (tile == null) {
                order.status = "cancelled_invalid_at_completion";
                refundRtsConstruction(order);
                continue;
            }
            tile.setNet(block, order.team, order.rotation);
            order.status = "completed";
        }
        if (rtsConstructionOrders.size() > 160) {
            int completedToRemove = rtsConstructionOrders.size() - 120;
            for (var iterator = rtsConstructionOrders.iterator(); iterator.hasNext() && completedToRemove > 0;) {
                RtsConstructionOrder order = iterator.next();
                if (!"queued".equals(order.status)) {
                    iterator.remove();
                    completedToRemove--;
                }
            }
        }
    }

    private void refundRtsConstruction(RtsConstructionOrder order) {
        CoreBuild core = order.team.core();
        if (core == null || Vars.state.rules.infiniteResources) return;
        order.cost.forEach(core.items::add);
    }

    private List<Map<String, Object>> rtsConstructionQueueState(Team team) {
        if (!"stockpile_rts_pvp".equals(gameModeVariant)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = Math.max(0, rtsConstructionOrders.size() - 120);
             index < rtsConstructionOrders.size(); index++) {
            RtsConstructionOrder order = rtsConstructionOrders.get(index);
            if (order.team != team) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("block", order.blockName);
            row.put("x", order.x);
            row.put("y", order.y);
            row.put("rotation", order.rotation);
            row.put("status", order.status);
            row.put("cost_reserved", namedCost(order.cost));
            row.put("standard_build_time_seconds", order.buildTicks / 60d);
            row.put("seconds_until_start", "queued".equals(order.status)
                ? Math.max(0d, order.startsAtTick - Vars.state.tick) / 60d : 0d);
            row.put("seconds_until_completion", "queued".equals(order.status)
                ? Math.max(0d, order.completesAtTick - Vars.state.tick) / 60d : 0d);
            result.add(row);
        }
        return result;
    }

    private Map<String, Object> remove(int index, JsonObject action) {
        int x = requireInt(action, "x");
        int y = requireInt(action, "y");
        if ("stockpile_rts_pvp".equals(gameModeVariant)) {
            for (RtsConstructionOrder order : rtsConstructionOrders) {
                if (order.team != controlledTeam() || order.x != x || order.y != y
                    || !"queued".equals(order.status)) continue;
                order.status = "cancelled_by_agent";
                refundRtsConstruction(order);
                return Map.of(
                    "index", index, "ok", true, "type", "remove",
                    "cancelled_queued_construction", Map.of(
                        "block", order.blockName, "x", x, "y", y,
                        "reserved_cost_refunded", namedCost(order.cost)
                    )
                );
            }
        }
        Tile tile = Vars.world.tile(x, y);
        Building building = tile == null ? null : tile.build;
        if (building == null) return actionError(index, action, "no_building", "No building at " + x + "," + y);
        if (building.team != controlledTeam()) return actionError(index, action, "not_owned", "The building is not owned by " + controlledTeam().name + ".");
        if (building instanceof CoreBuild) return actionError(index, action, "core_protected", "The core cannot be removed.");
        Map<String, Object> removed = buildingRef(building);
        Call.removeTile(building.tile);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("ok", true);
        result.put("type", "remove");
        result.put("removed", removed);
        result.put("refund", Map.of());
        return result;
    }

    private Map<String, Object> routeTransport(int index, JsonObject action, boolean liquid) {
        return routeTransport(index, action, liquid, false);
    }

    private Map<String, Object> routeTransport(int index, JsonObject action, boolean liquid, boolean isolated) {
        int sourceX = requireInt(action, "source_x");
        int sourceY = requireInt(action, "source_y");
        int targetX = requireInt(action, "target_x");
        int targetY = requireInt(action, "target_y");
        String transportName = requireString(action, "transport");
        int maxCost = Math.max(1, Math.min(requireInt(action, "max_cost"), MAX_MACRO_COPPER));
        int reserveCopper = Math.max(0, Math.min(requireInt(action, "reserve_copper"), 1000));
        Building source = ownedBuildingAt(sourceX, sourceY);
        Building target = ownedBuildingAt(targetX, targetY);
        if (source == null || target == null) {
            return actionError(index, action, "invalid_endpoint", "Source and target must be existing owned buildings.");
        }
        Block transport = findBlock(transportName);
        boolean valid = liquid ? validLiquidTransport(transport) : validItemTransport(transport);
        if (!valid) return actionError(index, action, "invalid_transport", transportName);
        if (liquid && (!source.block.hasLiquids || !target.block.hasLiquids)) {
            return actionError(index, action, "incompatible_endpoint", "Liquid routes require liquid-capable endpoints.");
        }
        if (!liquid && !source.block.outputsItems()) {
            return actionError(index, action, "incompatible_source", source.block.name + " does not output items.");
        }
        String isolatedResource = isolated ? requireString(action, "resource") : "";
        if (isolated) {
            String produced = producedItemName(source);
            Set<String> stored = storedItemTypes(source);
            if ((produced != null && !isolatedResource.equals(produced))
                || (produced == null && (stored.size() != 1 || !stored.contains(isolatedResource)))) {
                return actionError(index, action, "incompatible_source_resource",
                    "Source must produce the requested resource or currently contain only that resource: "
                        + isolatedResource + ".");
            }
            Set<String> accepted = acceptedItemTypes(target);
            if (!accepted.isEmpty() && !accepted.contains(isolatedResource)) {
                return actionError(index, action, "incompatible_target_resource",
                    target.block.name + " does not accept requested isolated resource " + isolatedResource + ".");
            }
        }

        Set<Integer> sourceFootprint = footprintKeys(source.block, source.tileX(), source.tileY());
        Set<Integer> targetFootprint = footprintKeys(target.block, target.tileX(), target.tileY());
        Set<Integer> forbidden = new HashSet<>(sourceFootprint);
        forbidden.addAll(targetFootprint);
        CoreBuild core = controlledTeam().core();
        int centerX = core == null ? source.tileX() : core.tileX();
        int centerY = core == null ? source.tileY() : core.tileY();
        List<int[]> path = transportPath(sourceFootprint, targetFootprint, forbidden, centerX, centerY,
            transport, !isolated, isolatedResource, source, target);
        if (path == null || path.isEmpty()) {
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("source", buildingRef(source));
            diagnostics.put("target", buildingRef(target));
            diagnostics.put("transport", transportName);
            diagnostics.put("search_center", Map.of("x", centerX, "y", centerY));
            diagnostics.put("search_radius", MACRO_PATH_RADIUS);
            diagnostics.put("existing_transport_policy", isolated
                ? "isolated route uses newly placeable tiles only and never merges with existing transport"
                : "same-team matching transports are reused only when their direction continues toward the target");
            return actionError(index, action, "no_valid_transport_route",
                "No directed route from source to target can be placed or reused using " + transportName + ".", diagnostics);
        }
        if (isolated) {
            int directLength = minimumTransportTileDistance(sourceFootprint, targetFootprint);
            int maximumIsolatedLength = Math.max(directLength + 12, directLength * 2);
            if (path.size() > maximumIsolatedLength) {
                Map<String, Object> diagnostics = new LinkedHashMap<>();
                diagnostics.put("source", buildingRef(source));
                diagnostics.put("target", buildingRef(target));
                diagnostics.put("resource", isolatedResource);
                diagnostics.put("shortest_found_path_tiles", path.size());
                diagnostics.put("direct_manhattan_path_tiles", directLength);
                diagnostics.put("maximum_allowed_path_tiles", maximumIsolatedLength);
                diagnostics.put("likely_obstruction",
                    "existing transport/buildings or terrain leave no reasonably short isolated conveyor-only lane");
                return actionError(index, action, "isolated_route_detour_excessive",
                    "The shortest clean route is an excessive detour; no transport was placed.", diagnostics);
            }
        }
        if (!liquid) {
            Set<String> sourceItems = new LinkedHashSet<>();
            String produced = producedItemName(source);
            if (produced != null) sourceItems.add(produced);
            else sourceItems.addAll(storedItemTypes(source));
            Set<Integer> routeTiles = path.stream()
                .map(point -> tileKey(point[0], point[1])).collect(Collectors.toSet());
            Set<String> possibleItems = possibleItemsForTransportTiles(sourceItems, routeTiles, source, target);
            Set<String> targetAccepted = acceptedItemTypes(target);
            Set<String> incompatible = new LinkedHashSet<>(possibleItems);
            incompatible.removeAll(targetAccepted);
            if (!(target instanceof CoreBuild) && !targetAccepted.isEmpty() && !incompatible.isEmpty()) {
                return actionError(index, action, "shared_route_item_incompatibility",
                    "The planned route can carry items the selective target does not accept; no transport was placed.",
                    Map.of("possible_input_items", possibleItems, "target_accepted_items", targetAccepted,
                        "incompatible_items", incompatible));
            }
        }
        List<Placement> plan = new ArrayList<>();
        appendTransport(plan, path, targetFootprint, transport.name);
        String type = liquid ? "route_liquid" : isolated ? "route_items_isolated" : "route_items";
        Map<String, Object> applied = applyPlan(index, action, type, plan, maxCost, reserveCopper);
        if (Boolean.TRUE.equals(applied.get("ok"))) {
            applied.put("source", buildingRef(source));
            applied.put("target", buildingRef(target));
            applied.put("transport", transport.name);
            if (isolated) applied.put("resource", isolatedResource);
        }
        return applied;
    }

    private Map<String, Object> inspectItemRoute(int index, JsonObject action) {
        int sourceX = requireInt(action, "source_x");
        int sourceY = requireInt(action, "source_y");
        int targetX = requireInt(action, "target_x");
        int targetY = requireInt(action, "target_y");
        int margin = Math.max(2, Math.min(optionalInt(action, "margin", 6), 12));
        String transportName = requireString(action, "transport");
        Building source = ownedBuildingAt(sourceX, sourceY);
        Building target = ownedBuildingAt(targetX, targetY);
        if (source == null || target == null) {
            return actionError(index, action, "invalid_endpoint",
                "Source and target must be existing owned buildings.");
        }
        if (!source.block.outputsItems()) {
            return actionError(index, action, "incompatible_source",
                source.block.name + " does not output items.");
        }
        Block transport = findBlock(transportName);
        if (!validItemTransport(transport)) {
            return actionError(index, action, "invalid_transport", transportName);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("ok", true);
        result.put("type", "inspect_item_route");
        result.put("source", routeEndpointEvidence(source));
        result.put("target", routeEndpointEvidence(target));
        result.put("transport", transportName);
        result.put("local_path_map", localPathMap(source, target, transport, margin));
        result.put("instruction",
            "Use this task-local evidence to author the ordered path. Then call preview_conveyor_path; inspection does not place anything.");
        return result;
    }

    private Map<String, Object> conveyorPathAction(int index, JsonObject action, boolean preview) {
        int sourceX = requireInt(action, "source_x");
        int sourceY = requireInt(action, "source_y");
        int targetX = requireInt(action, "target_x");
        int targetY = requireInt(action, "target_y");
        String transportName = requireString(action, "transport");
        int maxCost = Math.max(1, Math.min(requireInt(action, "max_cost"), MAX_MACRO_COPPER));
        int reserveCopper = Math.max(0, Math.min(requireInt(action, "reserve_copper"), 1000));
        Set<String> allowedItems = requireStringSet(action, "allowed_items");
        if (allowedItems.isEmpty()) {
            return actionError(index, action, "empty_allowed_items",
                "allowed_items must name every item the LLM permits to enter this path.");
        }
        Building source = ownedBuildingAt(sourceX, sourceY);
        Building target = ownedBuildingAt(targetX, targetY);
        if (source == null || target == null) {
            return actionError(index, action, "invalid_endpoint", "Source and target must be existing owned buildings.");
        }
        if (!source.block.outputsItems()) {
            return actionError(index, action, "incompatible_source", source.block.name + " does not output items.");
        }
        Block transport = findBlock(transportName);
        if (!validItemTransport(transport)) {
            return actionError(index, action, "invalid_transport", transportName);
        }
        JsonArray submitted = requireArray(action, "placements");
        if (submitted.isEmpty() || submitted.size() > MAX_EXPLICIT_PATH_TILES) {
            return actionError(index, action, "invalid_path_length",
                "placements must contain 1 to " + MAX_EXPLICIT_PATH_TILES + " path tiles.");
        }

        List<Placement> authoredPath = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int pathIndex = 0; pathIndex < submitted.size(); pathIndex++) {
            JsonElement element = submitted.get(pathIndex);
            if (!element.isJsonObject()) {
                return actionError(index, action, "invalid_path_tile", "placements[" + pathIndex + "] must be an object.");
            }
            JsonObject tile = element.getAsJsonObject();
            int x = requireInt(tile, "x");
            int y = requireInt(tile, "y");
            int rotation = Math.floorMod(requireInt(tile, "rotation"), 4);
            int key = tileKey(x, y);
            if (key < 0 || !seen.add(key)) {
                return actionError(index, action, "invalid_path_tile",
                    "Path contains an out-of-map or duplicate tile at " + x + "," + y + ".");
            }
            authoredPath.add(new Placement(transportName, x, y, rotation));
        }

        Set<Integer> sourceFootprint = footprintKeys(source.block, source.tileX(), source.tileY());
        Set<Integer> targetFootprint = footprintKeys(target.block, target.tileX(), target.tileY());
        Placement first = authoredPath.get(0), last = authoredPath.get(authoredPath.size() - 1);
        if (!adjacentTo(first.x(), first.y(), sourceFootprint)) {
            return actionError(index, action, "path_not_adjacent_to_source",
                "The first path tile must be orthogonally adjacent to the source footprint.");
        }
        if (!adjacentTo(last.x(), last.y(), targetFootprint)) {
            return actionError(index, action, "path_not_adjacent_to_target",
                "The final path tile must be orthogonally adjacent to the target footprint.");
        }

        List<Placement> newPlacements = new ArrayList<>();
        List<Map<String, Object>> reused = new ArrayList<>();
        List<Map<String, Object>> fullPath = new ArrayList<>();
        for (int pathIndex = 0; pathIndex < authoredPath.size(); pathIndex++) {
            Placement placement = authoredPath.get(pathIndex);
            int nextX, nextY;
            if (pathIndex + 1 < authoredPath.size()) {
                Placement next = authoredPath.get(pathIndex + 1);
                int manhattan = Math.abs(next.x() - placement.x()) + Math.abs(next.y() - placement.y());
                if (manhattan != 1) {
                    return actionError(index, action, "non_contiguous_path",
                        "Path tiles " + pathIndex + " and " + (pathIndex + 1) + " are not orthogonally adjacent.");
                }
                nextX = next.x();
                nextY = next.y();
            } else {
                int[] destination = adjacentTarget(placement.x(), placement.y(), targetFootprint);
                nextX = destination[0];
                nextY = destination[1];
            }
            int requiredRotation = rotationToward(placement.x(), placement.y(), nextX, nextY);
            if (placement.rotation() != requiredRotation) {
                return actionError(index, action, "path_rotation_mismatch",
                    "Path tile " + pathIndex + " at " + placement.x() + "," + placement.y()
                        + " must use rotation " + requiredRotation + " to point to the next tile or target.");
            }

            Tile worldTile = Vars.world.tile(placement.x(), placement.y());
            Building existing = worldTile == null ? null : worldTile.build;
            Map<String, Object> pathTile = new LinkedHashMap<>();
            pathTile.put("x", placement.x());
            pathTile.put("y", placement.y());
            pathTile.put("rotation", placement.rotation());
            if (existing != null) {
                if (existing.team != controlledTeam() || existing.block != transport || existing.rotation != placement.rotation()) {
                    return actionError(index, action, "path_tile_occupied",
                        "Path tile " + placement.x() + "," + placement.y()
                            + " is not an explicitly matching owned " + transportName + ".");
                }
                pathTile.put("mode", "explicit_reuse");
                reused.add(new LinkedHashMap<>(pathTile));
            } else {
                if (!Build.validPlace(transport, controlledTeam(), placement.x(), placement.y(), placement.rotation())) {
                    return actionError(index, action, "invalid_path_placement",
                        "Cannot place " + transportName + " at " + placement.x() + "," + placement.y() + ".",
                        placementDiagnostics(transport, placement.x(), placement.y(), placement.rotation()));
                }
                pathTile.put("mode", "new");
                newPlacements.add(placement);
            }
            fullPath.add(pathTile);
        }

        Set<String> possibleItems = possibleItemsForAuthoredPath(source, target, authoredPath);
        Set<String> unexpectedItems = new LinkedHashSet<>(possibleItems);
        unexpectedItems.removeAll(allowedItems);
        Set<String> targetAccepted = acceptedItemTypes(target);
        Set<String> incompatibleItems = new LinkedHashSet<>();
        if (!(target instanceof CoreBuild) && !targetAccepted.isEmpty()) {
            incompatibleItems.addAll(possibleItems);
            incompatibleItems.removeAll(targetAccepted);
        }
        Map<String, Object> preflight = new LinkedHashMap<>();
        preflight.put("possible_input_items", possibleItems);
        preflight.put("llm_allowed_items", allowedItems);
        preflight.put("unexpected_items", unexpectedItems);
        preflight.put("target_accepted_items", target instanceof CoreBuild ? "all_items" : targetAccepted);
        preflight.put("target_incompatible_items", incompatibleItems);
        preflight.put("path_length", authoredPath.size());
        preflight.put("new_tiles", newPlacements.size());
        preflight.put("explicitly_reused_tiles", reused.size());
        if (!unexpectedItems.isEmpty()) {
            return actionError(index, action, "undeclared_network_items",
                "Existing side inputs or reused transport can introduce items not declared in allowed_items.", preflight);
        }
        if (!incompatibleItems.isEmpty()) {
            return actionError(index, action, "target_item_incompatibility",
                "The authored path can deliver items the selective target does not accept.", preflight);
        }

        String signature = conveyorPlanSignature(source, target, transportName, authoredPath,
            allowedItems, maxCost, reserveCopper);
        if (preview) {
            String previewToken = UUID.randomUUID().toString();
            approvedConveyorPlans.put(previewToken, signature);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("index", index);
            result.put("ok", true);
            result.put("type", "preview_conveyor_path");
            result.put("source", buildingRef(source));
            result.put("target", buildingRef(target));
            result.put("transport", transportName);
            result.put("authored_path", fullPath);
            result.put("preflight", preflight);
            result.put("preview_token", previewToken);
            result.put("next_action",
                "Submit the identical place_conveyor_path with this preview_token to commit it; otherwise revise and preview again.");
            return result;
        }
        String previewToken = requireString(action, "preview_token");
        String approvedSignature = approvedConveyorPlans.get(previewToken);
        if (!signature.equals(approvedSignature)) {
            return actionError(index, action, "missing_or_stale_path_preview",
                "This exact path, endpoints, allowed_items, budget, and reserve were not approved by preview_conveyor_path.", preflight);
        }

        Map<String, Object> result = applyPlan(index, action, "place_conveyor_path",
            newPlacements, maxCost, reserveCopper);
        if (Boolean.TRUE.equals(result.get("ok"))) {
            approvedConveyorPlans.remove(previewToken);
            result.put("source", buildingRef(source));
            result.put("target", buildingRef(target));
            result.put("transport", transportName);
            result.put("authored_path", fullPath);
            result.put("explicitly_reused", reused);
            result.put("path_length", authoredPath.size());
            result.put("preflight", preflight);
        }
        return result;
    }

    private Map<String, Object> connectPower(int index, JsonObject action) {
        int nodeX = requireInt(action, "node_x");
        int nodeY = requireInt(action, "node_y");
        int targetX = requireInt(action, "target_x");
        int targetY = requireInt(action, "target_y");
        Building nodeBuilding = ownedBuildingAt(nodeX, nodeY);
        Building target = ownedBuildingAt(targetX, targetY);
        if (!(nodeBuilding instanceof PowerNodeBuild node) || !(nodeBuilding.block instanceof PowerNode powerNode)) {
            return actionError(index, action, "invalid_power_node", "node_x,node_y must identify an owned power node.");
        }
        if (target == null || target.power == null) {
            return actionError(index, action, "invalid_power_target", "Target must be an owned power-capable building.");
        }
        if (!powerNode.linkValid(node, target)) {
            return actionError(index, action, "invalid_power_link", "Power link is out of range, insulated, or exceeds node limits.");
        }
        node.configure(target.pos());
        return Map.of(
            "index", index, "ok", true, "type", "connect_power",
            "node", buildingRef(node), "target", buildingRef(target)
        );
    }

    private Map<String, Object> setUnitFactoryPlan(int index, JsonObject action) {
        int x = requireInt(action, "x");
        int y = requireInt(action, "y");
        String unitName = requireString(action, "unit");
        Building building = ownedBuildingAt(x, y);
        if (!(building instanceof UnitFactoryBuild factory) || !(building.block instanceof UnitFactory block)) {
            return actionError(index, action, "invalid_unit_factory", "No owned unit factory at " + x + "," + y);
        }
        for (int plan = 0; plan < block.plans.size; plan++) {
            if (block.plans.get(plan).unit.name.equals(unitName)) {
                factory.configure(plan);
                return Map.of(
                    "index", index, "ok", true, "type", "set_unit_factory_plan",
                    "factory", buildingRef(factory), "unit", unitName, "plan_index", plan
                );
            }
        }
        return actionError(index, action, "unsupported_unit_plan", unitName);
    }

    private Map<String, Object> trainUnits(int index, JsonObject action) {
        if (!"stockpile_rts_pvp".equals(gameModeVariant)) {
            return actionError(index, action, "rts_training_unavailable",
                "train_units is available only in the stockpile_rts_pvp mode started with rts-start.");
        }
        int x = requireInt(action, "x");
        int y = requireInt(action, "y");
        String unitName = requireString(action, "unit");
        int count = Math.max(1, Math.min(requireInt(action, "count"), MAX_RTS_TRAIN_COUNT));
        boolean hasRallyX = action.has("rally_x"), hasRallyY = action.has("rally_y");
        if (hasRallyX != hasRallyY) {
            return actionError(index, action, "incomplete_training_rally",
                "rally_x and rally_y must be supplied together.");
        }
        Integer rallyX = hasRallyX ? requireInt(action, "rally_x") : null;
        Integer rallyY = hasRallyY ? requireInt(action, "rally_y") : null;
        int rallyRadius = Math.max(2, Math.min(optionalInt(action, "rally_radius", 4), 12));
        String squadId = optionalString(action, "squad_id", "").trim();
        if (!squadId.isEmpty() && !squadId.matches("[A-Za-z0-9_-]{1,40}")) {
            return actionError(index, action, "invalid_squad_id",
                "squad_id must contain 1-40 letters, digits, underscores, or hyphens.");
        }
        if (!squadId.isEmpty() && rallyX == null) {
            return actionError(index, action, "training_squad_requires_rally",
                "squad_id requires rally_x and rally_y so newly produced units have an explicit staging point.");
        }
        if (rallyX != null && Vars.world.tile(rallyX, rallyY) == null) {
            return actionError(index, action, "training_rally_out_of_bounds", rallyX + "," + rallyY);
        }
        Building building = ownedBuildingAt(x, y);
        if (!(building instanceof UnitFactoryBuild factory) || !(building.block instanceof UnitFactory block)) {
            return actionError(index, action, "invalid_unit_factory", "No owned unit factory at " + x + "," + y);
        }
        if (rtsTrainingOrders.stream().anyMatch(order -> order.team == controlledTeam()
            && order.factoryX == x && order.factoryY == y && "training".equals(order.status))) {
            return actionError(index, action, "factory_training_queue_busy",
                "This factory already has an active RTS training order; use another factory or wait for completion.");
        }

        int planIndex = -1;
        UnitFactory.UnitPlan selected = null;
        for (int candidate = 0; candidate < block.plans.size; candidate++) {
            UnitFactory.UnitPlan plan = block.plans.get(candidate);
            if (plan.unit.name.equals(unitName)) {
                planIndex = candidate;
                selected = plan;
                break;
            }
        }
        if (selected == null) return actionError(index, action, "unsupported_unit_plan", unitName);

        CoreBuild core = controlledTeam().core();
        if (core == null) return actionError(index, action, "no_core", "The team has no surviving core.");
        TeamAgentState agentState = teamAgentState(controlledTeam());
        RtsSquad trainingSquad = squadId.isEmpty() ? null : agentState.squads.get(squadId);
        Map<Item, Integer> totalCost = new LinkedHashMap<>();
        for (ItemStack stack : selected.requirements) {
            totalCost.put(stack.item, Math.multiplyExact(stack.amount, count));
        }
        for (Map.Entry<Item, Integer> entry : totalCost.entrySet()) {
            if (core.items.get(entry.getKey()) < entry.getValue()) {
                return actionError(index, action, "insufficient_training_resources",
                    "Need " + entry.getValue() + " " + entry.getKey().name + " in the core for this order.",
                    Map.of("unit", unitName, "count", count, "total_cost", namedCost(totalCost),
                        "core_available", core.items.get(entry.getKey())));
            }
        }
        totalCost.forEach((item, amount) -> core.items.remove(item, amount));
        factory.configure(planIndex);
        RtsTrainingOrder order = new RtsTrainingOrder(
            controlledTeam(), x, y, unitName, count, selected.time, Vars.state.tick + selected.time,
            Vars.state.tick, namedCost(totalCost), rallyX, rallyY, rallyRadius, squadId
        );
        rtsTrainingOrders.add(order);
        if (!squadId.isEmpty() && trainingSquad == null) {
            trainingSquad = new RtsSquad(squadId);
            trainingSquad.unitSelector = unitName;
            trainingSquad.mode = "rally";
            trainingSquad.targetX = rallyX;
            trainingSquad.targetY = rallyY;
            trainingSquad.targetRadius = rallyRadius;
            trainingSquad.assignedTick = Vars.state.tick;
            trainingSquad.lastProgressTick = Vars.state.tick;
            trainingSquad.nextUpdateTick = Vars.state.tick;
            agentState.squads.put(squadId, trainingSquad);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("ok", true);
        result.put("type", "train_units");
        result.put("factory", buildingRef(factory));
        result.put("unit", unitName);
        result.put("count", count);
        result.put("total_cost", order.totalCost);
        result.put("seconds_per_unit", selected.time / 60f);
        result.put("current_control_point_speed_multiplier", rtsProductionSpeedMultiplier(controlledTeam()));
        result.put("estimated_total_seconds_at_current_control", selected.time * count
            / 60f / rtsProductionSpeedMultiplier(controlledTeam()));
        if (rallyX != null) result.put("rally", Map.of("x", rallyX, "y", rallyY, "radius", rallyRadius));
        if (!squadId.isEmpty()) result.put("squad_id", squadId);
        result.put("squad_reinforcement_behavior",
            "a new squad rallies at the supplied coordinate; an existing squad keeps its standing order and each completed unit joins that order");
        result.put("mechanic", "one unit is created per standard plan time; this factory queue runs independently of belts and power in stockpile RTS mode");
        return result;
    }

    private void updateRtsTraining() {
        if (!"stockpile_rts_pvp".equals(gameModeVariant) || Vars.state == null || !Vars.state.isGame()
            || Vars.state.gameOver) return;
        for (RtsTrainingOrder order : rtsTrainingOrders) {
            double multiplier = rtsProductionSpeedMultiplier(order.team);
            double elapsed = Math.max(0d, Vars.state.tick - order.lastSpeedUpdateTick);
            order.nextCompletionTick -= elapsed * (multiplier - 1d);
            order.lastSpeedUpdateTick = Vars.state.tick;
            if (!"training".equals(order.status) || Vars.state.tick < order.nextCompletionTick) continue;
            Building building = Vars.world.build(order.factoryX, order.factoryY);
            if (!(building instanceof UnitFactoryBuild factory) || factory.team != order.team
                || !(building.block instanceof UnitFactory block)) {
                order.status = "cancelled_factory_missing";
                continue;
            }
            UnitFactory.UnitPlan selected = null;
            for (UnitFactory.UnitPlan plan : block.plans) {
                if (plan.unit.name.equals(order.unitName)) {
                    selected = plan;
                    break;
                }
            }
            if (selected == null) {
                order.status = "cancelled_plan_unavailable";
                continue;
            }
            Unit unit = selected.unit.create(order.team);
            unit.set(factory.x, factory.y + factory.block.size * Vars.tilesize / 2f + 6f);
            unit.add();
            if (order.rallyX != null) {
                Tile rallyTile = Vars.world.tile(order.rallyX, order.rallyY);
                CoreBuild core = order.team.core();
                if (rallyTile != null && core != null) {
                    issueRtsUnitOrder(unit, "rally", null, rallyTile.worldx(), rallyTile.worldy(), core, 14);
                }
            }
            if (!order.squadId.isEmpty()) {
                RtsSquad squad = teamAgentState(order.team).squads.get(order.squadId);
                if (squad != null) {
                    if (squad.unitIds.add(unit.id)) squad.assignedMemberCount++;
                    squad.emptySinceTick = Double.NaN;
                    squad.nextUpdateTick = Vars.state.tick;
                }
            }
            order.completed++;
            order.remaining--;
            if (order.remaining <= 0) {
                order.status = "completed";
            } else {
                order.nextCompletionTick += order.ticksPerUnit;
            }
        }
        if (rtsTrainingOrders.size() > 80) {
            rtsTrainingOrders.removeIf(order -> !"training".equals(order.status));
        }
    }

    private List<Map<String, Object>> rtsTrainingQueueState(Team team) {
        if (!"stockpile_rts_pvp".equals(gameModeVariant)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = Math.max(0, rtsTrainingOrders.size() - 40); index < rtsTrainingOrders.size(); index++) {
            RtsTrainingOrder order = rtsTrainingOrders.get(index);
            if (order.team != team) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("factory", Map.of("x", order.factoryX, "y", order.factoryY));
            row.put("unit", order.unitName);
            row.put("requested", order.requested);
            row.put("completed", order.completed);
            row.put("remaining", order.remaining);
            row.put("status", order.status);
            row.put("total_cost_paid", order.totalCost);
            if (order.rallyX != null) row.put("rally", Map.of(
                "x", order.rallyX, "y", order.rallyY, "radius", order.rallyRadius));
            if (!order.squadId.isEmpty()) row.put("squad_id", order.squadId);
            double multiplier = rtsProductionSpeedMultiplier(team);
            row.put("standard_seconds_per_unit", order.ticksPerUnit / 60d);
            row.put("current_control_point_speed_multiplier", multiplier);
            row.put("effective_seconds_per_unit_at_current_control", order.ticksPerUnit / 60d / multiplier);
            row.put("seconds_until_next_unit", "training".equals(order.status)
                ? Math.max(0d, order.nextCompletionTick - Vars.state.tick) / 60d / multiplier : 0d);
            result.add(row);
        }
        return result;
    }

    private Map<String, Object> upgradeUnits(int index, JsonObject action) {
        if (!"stockpile_rts_pvp".equals(gameModeVariant)) {
            return actionError(index, action, "rts_upgrading_unavailable",
                "upgrade_units is available only in stockpile_rts_pvp mode.");
        }
        int x = requireInt(action, "x");
        int y = requireInt(action, "y");
        String fromName = requireString(action, "from_unit");
        String toName = requireString(action, "to_unit");
        int count = Math.max(1, Math.min(requireInt(action, "count"), MAX_RTS_TRAIN_COUNT));
        Building building = ownedBuildingAt(x, y);
        if (!(building instanceof ReconstructorBuild) || !(building.block instanceof Reconstructor block)) {
            return actionError(index, action, "invalid_reconstructor",
                "No owned reconstructor at " + x + "," + y);
        }
        if (rtsUpgradeOrders.stream().anyMatch(order -> order.team == controlledTeam()
            && order.reconstructorX == x && order.reconstructorY == y && "upgrading".equals(order.status))) {
            return actionError(index, action, "reconstructor_queue_busy",
                "This reconstructor already has an active RTS upgrade order.");
        }

        UnitType fromType = null;
        UnitType toType = null;
        for (UnitType[] pair : block.upgrades) {
            if (pair != null && pair.length >= 2 && pair[0] != null && pair[1] != null
                && pair[0].name.equals(fromName) && pair[1].name.equals(toName)) {
                fromType = pair[0];
                toType = pair[1];
                break;
            }
        }
        if (fromType == null || toType == null) {
            return actionError(index, action, "unsupported_upgrade_pair",
                fromName + " cannot be upgraded to " + toName + " by " + block.name);
        }

        List<Unit> inputs = new ArrayList<>();
        for (Unit unit : controlledTeam().data().units) {
            if (unit.type == fromType && unit.isAdded()) inputs.add(unit);
        }
        inputs.sort(Comparator.comparingDouble(unit -> {
            double dx = unit.x - building.x;
            double dy = unit.y - building.y;
            return dx * dx + dy * dy;
        }));
        if (inputs.size() < count) {
            return actionError(index, action, "insufficient_input_units",
                "Need " + count + " existing " + fromName + " units; only " + inputs.size() + " are available.");
        }

        CoreBuild core = controlledTeam().core();
        if (core == null) return actionError(index, action, "no_core", "The team has no surviving core.");
        Map<Item, Integer> unitCost = requiredItemConsumerCost(block);
        Map<Item, Integer> totalCost = new LinkedHashMap<>();
        unitCost.forEach((item, amount) -> totalCost.put(item, Math.multiplyExact(amount, count)));
        for (Map.Entry<Item, Integer> entry : totalCost.entrySet()) {
            if (core.items.get(entry.getKey()) < entry.getValue()) {
                return actionError(index, action, "insufficient_upgrade_resources",
                    "Need " + entry.getValue() + " " + entry.getKey().name + " in the core for this upgrade order.",
                    Map.of("from_unit", fromName, "to_unit", toName, "count", count,
                        "total_cost", namedCost(totalCost), "core_available", core.items.get(entry.getKey())));
            }
        }

        totalCost.forEach((item, amount) -> core.items.remove(item, amount));
        for (int input = 0; input < count; input++) inputs.get(input).remove();
        RtsUpgradeOrder order = new RtsUpgradeOrder(
            controlledTeam(), x, y, fromName, toName, count, block.constructTime,
            Vars.state.tick + block.constructTime, Vars.state.tick, namedCost(totalCost)
        );
        rtsUpgradeOrders.add(order);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("ok", true);
        result.put("type", "upgrade_units");
        result.put("reconstructor", buildingRef(building));
        result.put("from_unit", fromName);
        result.put("to_unit", toName);
        result.put("count", count);
        result.put("input_units_committed_immediately", count);
        result.put("total_cost", order.totalCost);
        result.put("seconds_per_unit", block.constructTime / 60f);
        result.put("current_control_point_speed_multiplier", rtsProductionSpeedMultiplier(controlledTeam()));
        result.put("estimated_total_seconds_at_current_control", block.constructTime * count
            / 60f / rtsProductionSpeedMultiplier(controlledTeam()));
        return result;
    }

    private void updateRtsUpgrades() {
        if (!"stockpile_rts_pvp".equals(gameModeVariant) || Vars.state == null || !Vars.state.isGame()
            || Vars.state.gameOver) return;
        for (RtsUpgradeOrder order : rtsUpgradeOrders) {
            double multiplier = rtsProductionSpeedMultiplier(order.team);
            double elapsed = Math.max(0d, Vars.state.tick - order.lastSpeedUpdateTick);
            order.nextCompletionTick -= elapsed * (multiplier - 1d);
            order.lastSpeedUpdateTick = Vars.state.tick;
            if (!"upgrading".equals(order.status) || Vars.state.tick < order.nextCompletionTick) continue;
            Building building = Vars.world.build(order.reconstructorX, order.reconstructorY);
            if (!(building instanceof ReconstructorBuild) || building.team != order.team
                || !(building.block instanceof Reconstructor block)) {
                order.status = "cancelled_reconstructor_missing";
                continue;
            }
            UnitType output = null;
            for (UnitType[] pair : block.upgrades) {
                if (pair != null && pair.length >= 2 && pair[0] != null && pair[1] != null
                    && pair[0].name.equals(order.fromUnitName) && pair[1].name.equals(order.toUnitName)) {
                    output = pair[1];
                    break;
                }
            }
            if (output == null) {
                order.status = "cancelled_upgrade_unavailable";
                continue;
            }
            Unit unit = output.create(order.team);
            unit.set(building.x, building.y + building.block.size * Vars.tilesize / 2f + 6f);
            unit.add();
            order.completed++;
            order.remaining--;
            if (order.remaining <= 0) {
                order.status = "completed";
            } else {
                order.nextCompletionTick += order.ticksPerUnit;
            }
        }
        if (rtsUpgradeOrders.size() > 80) {
            rtsUpgradeOrders.removeIf(order -> !"upgrading".equals(order.status));
        }
    }

    private List<Map<String, Object>> rtsUpgradeQueueState(Team team) {
        if (!"stockpile_rts_pvp".equals(gameModeVariant)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = Math.max(0, rtsUpgradeOrders.size() - 40); index < rtsUpgradeOrders.size(); index++) {
            RtsUpgradeOrder order = rtsUpgradeOrders.get(index);
            if (order.team != team) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reconstructor", Map.of("x", order.reconstructorX, "y", order.reconstructorY));
            row.put("from_unit", order.fromUnitName);
            row.put("to_unit", order.toUnitName);
            row.put("requested", order.requested);
            row.put("completed", order.completed);
            row.put("remaining", order.remaining);
            row.put("status", order.status);
            row.put("total_cost_paid", order.totalCost);
            double multiplier = rtsProductionSpeedMultiplier(team);
            row.put("standard_seconds_per_unit", order.ticksPerUnit / 60d);
            row.put("current_control_point_speed_multiplier", multiplier);
            row.put("effective_seconds_per_unit_at_current_control", order.ticksPerUnit / 60d / multiplier);
            row.put("seconds_until_next_unit", "upgrading".equals(order.status)
                ? Math.max(0d, order.nextCompletionTick - Vars.state.tick) / 60d / multiplier : 0d);
            result.add(row);
        }
        return result;
    }

    private Map<String, Object> commandUnits(int index, JsonObject action) {
        Team team = controlledTeam();
        String unitName = requireString(action, "unit");
        String mode = requireString(action, "mode");
        if (!Set.of("attack", "attack_move", "rally", "defend", "retreat", "stop").contains(mode)) {
            return actionError(index, action, "invalid_unit_command",
                "Mode must be attack, attack_move, rally, defend, retreat, or stop.");
        }
        int maxUnits = Math.max(1, Math.min(optionalInt(action, "max_units", 20), 200));
        int targetRadius = Math.max(0, Math.min(optionalInt(action, "target_radius", 2), 30));
        int engagementRadius = Math.max(2, Math.min(optionalInt(action, "engagement_radius", 14), 40));
        String squadId = optionalString(action, "squad_id", "").trim();
        if (squadId.length() > 40 || (!squadId.isEmpty() && !squadId.matches("[A-Za-z0-9_-]+"))) {
            return actionError(index, action, "invalid_squad_id",
                "squad_id must contain 1-40 letters, digits, underscores, or hyphens.");
        }
        Set<Integer> requestedUnitIds = new LinkedHashSet<>();
        if (action.has("unit_ids")) {
            if (!action.get("unit_ids").isJsonArray()) {
                return actionError(index, action, "invalid_unit_ids", "unit_ids must be an array of unit IDs.");
            }
            for (JsonElement element : action.getAsJsonArray("unit_ids")) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                    return actionError(index, action, "invalid_unit_ids", "Every unit_ids entry must be an integer.");
                }
                requestedUnitIds.add(element.getAsInt());
                if (requestedUnitIds.size() > 200) {
                    return actionError(index, action, "too_many_unit_ids", "At most 200 unit IDs may be selected.");
                }
            }
        }
        CoreBuild core = team.core();
        if (core == null) return actionError(index, action, "no_core", "Team " + team.name + " has no core.");

        TeamAgentState agentState = teamAgentState(team);
        RtsSquad existingSquad = squadId.isEmpty() ? null : agentState.squads.get(squadId);
        int pendingReinforcements = existingSquad == null ? 0 : pendingRtsTrainingUnits(team, squadId);
        boolean acceptsPendingStandingOrder = existingSquad != null && requestedUnitIds.isEmpty()
            && pendingReinforcements > 0;
        boolean useExistingMembership = existingSquad != null && requestedUnitIds.isEmpty();
        if (useExistingMembership) requestedUnitIds.addAll(existingSquad.unitIds);

        boolean needsTarget = Set.of("attack", "attack_move", "rally", "defend").contains(mode);
        if (needsTarget && (!action.has("target_x") || !action.has("target_y"))) {
            return actionError(index, action, "missing_unit_target",
                "target_x and target_y are required for " + mode + ".");
        }
        int targetX = needsTarget ? requireInt(action, "target_x") : core.tileX();
        int targetY = needsTarget ? requireInt(action, "target_y") : core.tileY();
        Tile targetTile = Vars.world.tile(targetX, targetY);
        if (needsTarget && targetTile == null) {
            return actionError(index, action, "unit_target_out_of_bounds", targetX + "," + targetY);
        }
        float targetWorldX = needsTarget ? targetTile.worldx() : core.x;
        float targetWorldY = needsTarget ? targetTile.worldy() : core.y;

        Teamc attackTarget = null;
        Integer requestedTargetUnitId = null;
        String effectiveMode = mode;
        String fallbackReason = null;
        if ("attack".equals(mode)) {
            if (action.has("target_unit_id")) {
                JsonElement rawTargetUnitId = action.get("target_unit_id");
                if (!rawTargetUnitId.isJsonPrimitive() || !rawTargetUnitId.getAsJsonPrimitive().isNumber()) {
                    return actionError(index, action, "invalid_target_unit_id", "target_unit_id must be an integer.");
                }
                requestedTargetUnitId = rawTargetUnitId.getAsInt();
                attackTarget = enemyUnitById(team, requestedTargetUnitId);
                if (attackTarget != null) {
                    targetWorldX = attackTarget.x();
                    targetWorldY = attackTarget.y();
                }
            }
            if (attackTarget == null) {
                Building exactBuilding = targetTile.build;
                if (exactBuilding != null && exactBuilding.team != team && exactBuilding.team != Team.derelict) {
                    attackTarget = exactBuilding;
                } else {
                    attackTarget = Units.closestTarget(team, targetWorldX, targetWorldY,
                        Math.max(1, targetRadius) * Vars.tilesize,
                        unit -> unit.team != Team.derelict,
                        building -> building.team != Team.derelict);
                }
            }
            if (attackTarget == null) {
                effectiveMode = "attack_move";
                fallbackReason = requestedTargetUnitId == null
                    ? "no_enemy_at_observed_coordinate"
                    : "target_unit_missing_or_destroyed";
            }
        }

        Unit coreDefender = agentState.coreDefender;
        List<Unit> candidates = new ArrayList<>();
        for (Unit unit : team.data().units) {
            if (unit == coreDefender || unit.dead() || !unit.isAdded() || !unit.isCommandable()) continue;
            if (useExistingMembership && requestedUnitIds.isEmpty()) continue;
            if (!useExistingMembership && !"all".equals(unitName) && !unit.type.name.equals(unitName)) continue;
            if (!requestedUnitIds.isEmpty() && !requestedUnitIds.contains(unit.id)) continue;
            if (!useExistingMembership && requestedUnitIds.isEmpty() && !squadId.isEmpty()
                && assignedToOtherSquad(agentState, squadId, unit.id)) continue;
            if (attackTarget != null && !unit.canTarget(attackTarget)) continue;
            candidates.add(unit);
        }
        final float sortTargetWorldX = targetWorldX;
        final float sortTargetWorldY = targetWorldY;
        candidates.sort(Comparator.comparingDouble(unit -> {
            float dx = unit.x - sortTargetWorldX;
            float dy = unit.y - sortTargetWorldY;
            return dx * dx + dy * dy;
        }));

        List<Map<String, Object>> commanded = new ArrayList<>();
        for (int selected = 0; selected < Math.min(maxUnits, candidates.size()); selected++) {
            Unit unit = candidates.get(selected);
            issueRtsUnitOrder(unit, effectiveMode, attackTarget, targetWorldX, targetWorldY, core,
                engagementRadius);
            commanded.add(Map.of(
                "id", unit.id,
                "type", unit.type.name,
                "from_x", unit.x / Vars.tilesize,
                "from_y", unit.y / Vars.tilesize
            ));
        }

        boolean accepted = !commanded.isEmpty() || acceptsPendingStandingOrder;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("ok", accepted);
        result.put("type", "command_units");
        result.put("mode", mode);
        result.put("effective_mode", effectiveMode);
        result.put("unit_selector", unitName);
        if (!squadId.isEmpty()) result.put("squad_id", squadId);
        result.put("requested_unit_ids", requestedUnitIds);
        if (requestedTargetUnitId != null) result.put("requested_target_unit_id", requestedTargetUnitId);
        result.put("commanded_count", commanded.size());
        result.put("commanded_units", commanded);
        result.put("pending_reinforcements", pendingReinforcements);
        result.put("standing_order_registered_for_pending_units",
            commanded.isEmpty() && acceptsPendingStandingOrder);
        result.put("target", Map.of("x", targetX, "y", targetY));
        if (attackTarget != null) {
            Map<String, Object> resolvedTarget = new LinkedHashMap<>();
            resolvedTarget.put("kind", attackTarget instanceof Building ? "building" : "unit");
            if (attackTarget instanceof Unit targetUnit) resolvedTarget.put("id", targetUnit.id);
            resolvedTarget.put("x", attackTarget.x() / Vars.tilesize);
            resolvedTarget.put("y", attackTarget.y() / Vars.tilesize);
            result.put("attack_target", resolvedTarget);
        }
        if (fallbackReason != null) result.put("fallback_reason", fallbackReason);
        if (!squadId.isEmpty() && accepted) {
            RtsSquad squad = existingSquad == null ? new RtsSquad(squadId) : existingSquad;
            if (!useExistingMembership || action.has("unit_ids")) {
                squad.unitIds.clear();
                for (Map<String, Object> row : commanded) squad.unitIds.add((Integer)row.get("id"));
            } else {
                squad.unitIds.retainAll(commanded.stream()
                    .map(row -> (Integer)row.get("id")).collect(Collectors.toSet()));
            }
            squad.unitSelector = unitName;
            squad.mode = effectiveMode;
            squad.targetX = targetX;
            squad.targetY = targetY;
            squad.targetRadius = targetRadius;
            squad.engagementRadius = engagementRadius;
            squad.targetUnitId = requestedTargetUnitId;
            squad.assignedTick = Vars.state.tick;
            squad.assignedMemberCount = commanded.size();
            squad.lastAverageDistanceTiles = Double.NaN;
            squad.lastProgressTick = Vars.state.tick;
            squad.nextUpdateTick = Vars.state.tick + RTS_SQUAD_UPDATE_INTERVAL_TICKS;
            agentState.squads.put(squadId, squad);
        }
        if (!accepted) {
            result.put("error", "no_matching_commandable_units");
            result.put("message", "No matching produced units can execute this command; the automatic core unit uses command_core_unit.");
        } else if (commanded.isEmpty()) {
            result.put("message",
                "Standing order registered; pending squad reinforcements will execute it as they finish training.");
        }
        List<Integer> commandedIds = commanded.stream()
            .map(row -> (Integer)row.get("id")).toList();
        agentState.recentUnitCommands.addLast(new RtsUnitCommandReceipt(
            Vars.state.tick, squadId, unitName, mode, effectiveMode, commandedIds, accepted,
            pendingReinforcements, targetX, targetY, fallbackReason
        ));
        while (agentState.recentUnitCommands.size() > 12) agentState.recentUnitCommands.removeFirst();
        return result;
    }

    private boolean assignedToOtherSquad(TeamAgentState state, String squadId, int unitId) {
        for (RtsSquad squad : state.squads.values()) {
            if (!squad.id.equals(squadId) && !"stop".equals(squad.mode) && squad.unitIds.contains(unitId)) return true;
        }
        return false;
    }

    private Unit enemyUnitById(Team team, int targetUnitId) {
        for (Team other : Team.all) {
            if (other == team || other == Team.derelict) continue;
            for (Unit candidate : other.data().units) {
                if (candidate.id == targetUnitId && !candidate.dead() && candidate.isAdded()) return candidate;
            }
        }
        return null;
    }

    private void issueRtsUnitOrder(
        Unit unit, String mode, Teamc attackTarget, float targetWorldX, float targetWorldY,
        CoreBuild core, int engagementRadius
    ) {
        CommandAI command = unit.command();
        command.clearCommands();
        if ("stop".equals(mode)) return;
        command.command(UnitCommand.moveCommand);
        if ("attack".equals(mode) && attackTarget != null && unit.canTarget(attackTarget)) {
            command.commandTarget(attackTarget);
            return;
        }
        if ("attack_move".equals(mode) || "defend".equals(mode)) {
            float searchX = "defend".equals(mode) ? targetWorldX : unit.x;
            float searchY = "defend".equals(mode) ? targetWorldY : unit.y;
            Teamc nearby = Units.closestTarget(unit.team, searchX, searchY,
                engagementRadius * Vars.tilesize,
                candidate -> unit.canTarget(candidate),
                building -> unit.canTarget(building));
            if (nearby != null) {
                command.commandTarget(nearby);
                return;
            }
        }
        if ("retreat".equals(mode)) command.commandPosition(new Vec2(core.x, core.y));
        else command.commandPosition(new Vec2(targetWorldX, targetWorldY));
    }

    private void updateRtsSquads() {
        if (!"stockpile_rts_pvp".equals(gameModeVariant) || Vars.state == null
            || !Vars.state.isGame() || Vars.state.gameOver) return;
        for (Team team : configuredAgentTeams()) {
            TeamAgentState state = teamAgentState(team);
            CoreBuild core = team.core();
            if (core == null) continue;
            // Keep an empty squad record so the next observation can distinguish total losses
            // from an order that was never received. A later command with the same ID reuses it.
            for (RtsSquad squad : state.squads.values()) {
                squad.unitIds.removeIf(id -> findOwnedCommandableUnit(team, id) == null);
                if (squad.unitIds.isEmpty() && pendingRtsTrainingUnits(team, squad.id) == 0) {
                    if (Double.isNaN(squad.emptySinceTick)) squad.emptySinceTick = Vars.state.tick;
                } else {
                    squad.emptySinceTick = Double.NaN;
                }
            }
            state.squads.values().removeIf(squad -> !Double.isNaN(squad.emptySinceTick)
                && Vars.state.tick - squad.emptySinceTick >= RTS_EMPTY_SQUAD_RETENTION_TICKS);
            for (RtsSquad squad : state.squads.values()) {
                if (squad.unitIds.isEmpty() || "stop".equals(squad.mode)
                    || Vars.state.tick < squad.nextUpdateTick) continue;
                squad.nextUpdateTick = Vars.state.tick + RTS_SQUAD_UPDATE_INTERVAL_TICKS;
                Teamc target = null;
                if ("attack".equals(squad.mode)) {
                    if (squad.targetUnitId != null) target = enemyUnitById(team, squad.targetUnitId);
                    if (target == null) {
                        Tile tile = Vars.world.tile(squad.targetX, squad.targetY);
                        if (tile != null && tile.build != null && tile.build.team != team
                            && tile.build.team != Team.derelict) target = tile.build;
                    }
                    if (target == null) squad.mode = "attack_move";
                }
                Tile targetTile = Vars.world.tile(squad.targetX, squad.targetY);
                float targetWorldX = target == null
                    ? (targetTile == null ? core.x : targetTile.worldx()) : target.x();
                float targetWorldY = target == null
                    ? (targetTile == null ? core.y : targetTile.worldy()) : target.y();
                for (Integer id : new ArrayList<>(squad.unitIds)) {
                    Unit unit = findOwnedCommandableUnit(team, id);
                    if (unit == null) continue;
                    float dx = unit.x - targetWorldX, dy = unit.y - targetWorldY;
                    float rallyRadiusWorld = Math.max(1.5f, squad.targetRadius) * Vars.tilesize;
                    if ("rally".equals(squad.mode) && dx * dx + dy * dy <= rallyRadiusWorld * rallyRadiusWorld) {
                        unit.command().clearCommands();
                        continue;
                    }
                    issueRtsUnitOrder(unit, squad.mode, target, targetWorldX, targetWorldY, core,
                        squad.engagementRadius);
                }
            }
        }
    }

    private void cleanupDeadRtsUnits() {
        if (!"stockpile_rts_pvp".equals(gameModeVariant) || Vars.state == null
            || !Vars.state.isGame() || Vars.state.gameOver) return;
        Seq<Unit> deadUnits = new Seq<>();
        Groups.unit.each(unit -> {
            if (unit.dead() || unit.health <= 0f) deadUnits.add(unit);
        });
        // Flying units normally remain as falling wreck entities. A plain server-side remove() is
        // insufficient because clients can retain the last synchronized sprite as a ghost. Use the
        // authoritative destroy RPC so every client marks the entity removed immediately.
        deadUnits.each(unit -> Call.unitDestroy(unit.id));
    }

    private Unit findOwnedCommandableUnit(Team team, int id) {
        Unit coreDefender = teamAgentState(team).coreDefender;
        for (Unit unit : team.data().units) {
            if (unit.id == id && unit != coreDefender && !unit.dead() && unit.isAdded() && unit.isCommandable()) {
                return unit;
            }
        }
        return null;
    }

    private List<Map<String, Object>> rtsSquadState(Team team) {
        if (!"stockpile_rts_pvp".equals(gameModeVariant)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (RtsSquad squad : teamAgentState(team).squads.values()) {
            List<Unit> livingUnits = squad.unitIds.stream()
                .map(id -> findOwnedCommandableUnit(team, id)).filter(unit -> unit != null).toList();
            List<Integer> living = livingUnits.stream().map(unit -> unit.id).toList();
            CoreBuild core = team.core();
            Tile targetTile = Vars.world.tile(squad.targetX, squad.targetY);
            float targetWorldX = "retreat".equals(squad.mode) && core != null
                ? core.x : targetTile == null ? squad.targetX * Vars.tilesize : targetTile.worldx();
            float targetWorldY = "retreat".equals(squad.mode) && core != null
                ? core.y : targetTile == null ? squad.targetY * Vars.tilesize : targetTile.worldy();
            double totalDistance = 0d, totalHealthFraction = 0d, centerX = 0d, centerY = 0d;
            int arrived = 0, engaging = 0, moving = 0;
            double arrivalRadius = Math.max(1.5d, squad.targetRadius) * Vars.tilesize;
            for (Unit unit : livingUnits) {
                double distance = Math.sqrt(distance2f(unit.x, unit.y, targetWorldX, targetWorldY));
                totalDistance += distance / Vars.tilesize;
                totalHealthFraction += Math.max(0d, unit.health / Math.max(1f, unit.maxHealth));
                centerX += unit.x / Vars.tilesize;
                centerY += unit.y / Vars.tilesize;
                if (distance <= arrivalRadius) arrived++;
                if (unit.isShooting()) engaging++;
                if (unit.vel().len2() > 0.04f) moving++;
            }
            double averageDistance = livingUnits.isEmpty() ? 0d : totalDistance / livingUnits.size();
            if (!livingUnits.isEmpty()) {
                centerX /= livingUnits.size();
                centerY /= livingUnits.size();
            }
            double spread = 0d;
            for (Unit unit : livingUnits) {
                spread = Math.max(spread, Math.sqrt(distance2f(
                    unit.x / Vars.tilesize, unit.y / Vars.tilesize, centerX, centerY)));
            }
            double distanceChange = Double.isNaN(squad.lastAverageDistanceTiles)
                ? 0d : squad.lastAverageDistanceTiles - averageDistance;
            if (Double.isNaN(squad.lastAverageDistanceTiles) || distanceChange >= 0.25d
                || engaging > 0 || arrived == livingUnits.size()) {
                squad.lastProgressTick = Vars.state.tick;
            }
            squad.lastAverageDistanceTiles = averageDistance;
            boolean stalled = !livingUnits.isEmpty() && arrived < livingUnits.size() && engaging == 0
                && Vars.state.tick - squad.lastProgressTick >= 300d;
            int pendingTrainingUnits = pendingRtsTrainingUnits(team, squad.id);
            boolean productionComplete = pendingTrainingUnits == 0;
            boolean readyForNewOrder = "rally".equals(squad.mode) && productionComplete
                && !livingUnits.isEmpty() && arrived == livingUnits.size();
            String executionStatus;
            if (livingUnits.isEmpty()) executionStatus = "no_surviving_members";
            else if (engaging > 0) executionStatus = "engaging";
            else if (stalled) executionStatus = "no_distance_progress_for_5_seconds";
            else if (arrived == livingUnits.size()) {
                executionStatus = switch (squad.mode) {
                    case "rally" -> "assembled_at_rally";
                    case "retreat" -> "returned_to_core";
                    default -> "holding_target_area";
                };
            } else executionStatus = "moving_to_target";
            String operationPhase;
            if (livingUnits.isEmpty()) {
                if (pendingTrainingUnits > 0) operationPhase = "producing";
                else if (squad.assignedMemberCount > 0) operationPhase = "eliminated";
                else operationPhase = "empty";
            } else if (stalled) operationPhase = "stalled";
            else if (engaging > 0) operationPhase = "engaging";
            else if ("rally".equals(squad.mode)) {
                if (readyForNewOrder) operationPhase = "ready";
                else operationPhase = "assembling";
            } else if ("attack".equals(squad.mode) || "attack_move".equals(squad.mode)) {
                operationPhase = arrived == livingUnits.size() && !livingUnits.isEmpty() ? "at_objective" : "advancing";
            } else if ("retreat".equals(squad.mode)) {
                operationPhase = arrived == livingUnits.size() && !livingUnits.isEmpty() ? "regrouped" : "withdrawing";
            } else if ("defend".equals(squad.mode)) operationPhase = "holding";
            else operationPhase = "idle";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("squad_id", squad.id);
            row.put("mode", squad.mode);
            row.put("unit_selector", squad.unitSelector);
            row.put("member_ids", living);
            row.put("member_count", living.size());
            row.put("target", Map.of("x", squad.targetX, "y", squad.targetY));
            if (squad.targetUnitId != null) row.put("target_unit_id", squad.targetUnitId);
            row.put("engagement_radius", squad.engagementRadius);
            row.put("assigned_seconds_ago", Math.max(0d, Vars.state.tick - squad.assignedTick) / 60d);
            row.put("execution_status", executionStatus);
            row.put("operation_phase", operationPhase);
            row.put("pending_training_units", pendingTrainingUnits);
            row.put("production_complete", productionComplete);
            row.put("ready_for_new_order", readyForNewOrder);
            row.put("assigned_member_count", squad.assignedMemberCount);
            row.put("losses_since_order", Math.max(0, squad.assignedMemberCount - living.size()));
            row.put("arrived_count", arrived);
            row.put("arrived_fraction", livingUnits.isEmpty() ? 0d : arrived / (double)livingUnits.size());
            row.put("engaging_count", engaging);
            row.put("moving_count", moving);
            row.put("average_distance_to_target_tiles", averageDistance);
            row.put("distance_closed_since_previous_observation_tiles", distanceChange);
            row.put("seconds_since_distance_progress", Math.max(0d, Vars.state.tick - squad.lastProgressTick) / 60d);
            row.put("centroid", Map.of("x", centerX, "y", centerY));
            row.put("spread_radius_tiles", spread);
            row.put("average_health_fraction", livingUnits.isEmpty() ? 0d
                : totalHealthFraction / livingUnits.size());
            result.add(row);
        }
        return result;
    }

    private int pendingRtsTrainingUnits(Team team, String squadId) {
        int pending = 0;
        for (RtsTrainingOrder order : rtsTrainingOrders) {
            if (order.team == team && order.squadId.equals(squadId) && "training".equals(order.status)) {
                pending += order.remaining;
            }
        }
        return pending;
    }

    private double distance2f(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2, dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    private void recordRtsUnitLoss(Unit unit) {
        if (!"stockpile_rts_pvp".equals(gameModeVariant) || Vars.state == null || unit == null
            || !configuredAgentTeams().contains(unit.team)) return;
        TeamAgentState state = teamAgentState(unit.team);
        if (unit == state.coreDefender) return;
        String squadId = null, orderMode = null;
        for (RtsSquad squad : state.squads.values()) {
            if (!squad.unitIds.contains(unit.id)) continue;
            squadId = squad.id;
            orderMode = squad.mode;
            break;
        }
        state.totalUnitLosses++;
        state.unitLossesByType.merge(unit.type.name, 1, Integer::sum);
        state.recentUnitLosses.addLast(new RtsUnitLoss(
            Vars.state.tick, unit.id, unit.type.name, unit.x / Vars.tilesize, unit.y / Vars.tilesize,
            squadId, orderMode
        ));
        while (state.recentUnitLosses.size() > 200) state.recentUnitLosses.removeFirst();
    }

    private Map<String, Object> recentCombatLossState(Team observer) {
        if (!"stockpile_rts_pvp".equals(gameModeVariant)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("meaning", "server-observed destroyed combat units; rolling windows are facts, not replacement quotas");
        result.put("self", teamLossState(observer));
        List<Map<String, Object>> opponents = new ArrayList<>();
        for (Team team : configuredAgentTeams()) {
            if (team != observer) opponents.add(teamLossState(team));
        }
        result.put("opponents", opponents);
        return result;
    }

    private List<Map<String, Object>> recentUnitCommandReceipts(Team team) {
        if (!"stockpile_rts_pvp".equals(gameModeVariant)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (RtsUnitCommandReceipt receipt : teamAgentState(team).recentUnitCommands) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seconds_ago", Math.max(0d, Vars.state.tick - receipt.tick) / 60d);
            row.put("accepted", receipt.accepted);
            if (!receipt.squadId.isBlank()) row.put("squad_id", receipt.squadId);
            row.put("unit_selector", receipt.unitSelector);
            row.put("requested_mode", receipt.requestedMode);
            row.put("effective_mode", receipt.effectiveMode);
            row.put("commanded_count", receipt.unitIds.size());
            row.put("commanded_unit_ids", receipt.unitIds);
            row.put("pending_reinforcements", receipt.pendingReinforcements);
            row.put("standing_order_registered_without_live_members",
                receipt.accepted && receipt.unitIds.isEmpty() && receipt.pendingReinforcements > 0);
            row.put("target", Map.of("x", receipt.targetX, "y", receipt.targetY));
            if (receipt.fallbackReason != null) row.put("fallback_reason", receipt.fallbackReason);
            result.add(row);
        }
        return result;
    }

    private Map<String, Object> teamLossState(Team team) {
        TeamAgentState state = teamAgentState(team);
        double now = Vars.state.tick;
        int last10 = 0, last30 = 0;
        Map<String, Integer> last30ByType = new LinkedHashMap<>();
        List<Map<String, Object>> recent = new ArrayList<>();
        for (RtsUnitLoss loss : state.recentUnitLosses) {
            double age = Math.max(0d, now - loss.tick) / 60d;
            if (age <= 10d) last10++;
            if (age <= 30d) {
                last30++;
                last30ByType.merge(loss.unitType, 1, Integer::sum);
            }
            if (age <= 30d) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("seconds_ago", age);
                row.put("unit_id", loss.unitId);
                row.put("unit", loss.unitType);
                row.put("x", loss.x);
                row.put("y", loss.y);
                if (loss.squadId != null) row.put("squad_id", loss.squadId);
                if (loss.orderMode != null) row.put("order_mode", loss.orderMode);
                recent.add(row);
            }
        }
        if (recent.size() > 24) recent = new ArrayList<>(recent.subList(recent.size() - 24, recent.size()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("team", team.name);
        result.put("last_10_seconds", last10);
        result.put("last_30_seconds", last30);
        result.put("last_30_seconds_by_type", last30ByType);
        result.put("total_this_match", state.totalUnitLosses);
        result.put("total_by_type", new LinkedHashMap<>(state.unitLossesByType));
        result.put("recent_events", recent);
        return result;
    }

    private Map<String, Object> configureItemFilter(int index, JsonObject action) {
        int x = requireInt(action, "x");
        int y = requireInt(action, "y");
        String itemName = requireString(action, "item");
        Building building = ownedBuildingAt(x, y);
        if (!(building instanceof SorterBuild) && !(building instanceof UnloaderBuild)) {
            return actionError(index, action, "invalid_item_filter_building",
                "No owned sorter, inverted-sorter, or unloader at " + x + "," + y);
        }
        Item item = findItem(itemName);
        if (item == null) return actionError(index, action, "unknown_item", itemName);
        String behavior;
        if (building instanceof SorterBuild sorter && building.block instanceof Sorter sorterBlock) {
            sorter.configure(item);
            behavior = sorterBlock.invert
                ? "selected item exits to the sides; other items continue straight"
                : "selected item continues straight; other items exit to the sides";
        } else {
            ((UnloaderBuild) building).configure(item);
            behavior = "extracts only the selected item from adjacent storage/core";
        }
        return Map.of("index", index, "ok", true, "type", "configure_item_filter",
            "building", buildingRef(building), "item", itemName, "behavior", behavior);
    }

    private Building ownedBuildingAt(int x, int y) {
        Tile tile = Vars.world.tile(x, y);
        return tile != null && tile.build != null && tile.build.team == controlledTeam() ? tile.build : null;
    }

    private Map<String, Object> buildMineToCore(int index, JsonObject action) {
        CoreBuild core = controlledTeam().core();
        if (core == null) return actionError(index, action, "no_core", "Team " + controlledTeam().name + " has no core.");
        return buildMineRoute(index, action, core);
    }

    private Map<String, Object> buildMineToTarget(int index, JsonObject action) {
        int targetX = requireInt(action, "target_x");
        int targetY = requireInt(action, "target_y");
        Tile targetTile = Vars.world.tile(targetX, targetY);
        Building target = targetTile == null ? null : targetTile.build;
        if (target == null || target.team != controlledTeam()) {
            return actionError(index, action, "invalid_target", "Target must be an existing owned building.");
        }
        return buildMineRoute(index, action, target);
    }

    private Map<String, Object> buildMineRoute(int index, JsonObject action, Building target) {
        CoreBuild core = controlledTeam().core();
        if (core == null) return actionError(index, action, "no_core", "Team " + controlledTeam().name + " has no core.");
        String resource = requireString(action, "resource");
        int maxCost = Math.max(1, Math.min(requireInt(action, "max_cost"), MAX_MACRO_COPPER));
        int reserveCopper = Math.max(0, Math.min(requireInt(action, "reserve_copper"), 1000));
        String drillName = requireString(action, "drill");
        String transportName = requireString(action, "transport");
        Block drillBlock = findBlock(drillName);
        Block transport = findBlock(transportName);
        if (!(drillBlock instanceof Drill drill) || !blockAvailable(drillBlock)) {
            return actionError(index, action, "invalid_drill", drillName + " is not an available drill.");
        }
        if (!validItemTransport(transport)) {
            return actionError(index, action, "invalid_transport", transportName + " is not an available directed item transport block.");
        }

        List<int[]> candidates = new ArrayList<>();
        int minX = Math.max(0, core.tileX() - PLACEMENT_HINT_RADIUS);
        int maxX = Math.min(Vars.world.width() - 1, core.tileX() + PLACEMENT_HINT_RADIUS);
        int minY = Math.max(0, core.tileY() - PLACEMENT_HINT_RADIUS);
        int maxY = Math.min(Vars.world.height() - 1, core.tileY() + PLACEMENT_HINT_RADIUS);
        for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) {
            if (!Build.validPlace(drill, controlledTeam(), x, y, 0)) continue;
            Map<String, Object> output = drillOutput(drill, x, y);
            if (resource.equals(output.get("produces"))) {
                candidates.add(new int[]{x, y, (int) output.get("ore_tiles")});
            }
        }
        candidates.sort(Comparator.<int[]>comparingInt(point -> -point[2])
            .thenComparingInt(point -> distance2(point[0], point[1], core.tileX(), core.tileY())));

        Set<Integer> targetFootprint = footprintKeys(target.block, target.tileX(), target.tileY());
        Map<String, Object> lastUnsafeRoute = null;
        for (int[] candidate : candidates) {
            Set<Integer> drillFootprint = footprintKeys(drill, candidate[0], candidate[1]);
            Set<Integer> forbidden = new HashSet<>(drillFootprint);
            forbidden.addAll(targetFootprint);
            List<int[]> path = transportPath(drillFootprint, targetFootprint, forbidden,
                core.tileX(), core.tileY(), transport);
            if (path == null || path.isEmpty()) continue;
            if (!(target instanceof CoreBuild)) {
                Set<Integer> routeTiles = path.stream()
                    .map(point -> tileKey(point[0], point[1])).collect(Collectors.toSet());
                Set<String> possibleItems = possibleItemsForTransportTiles(
                    Set.of(resource), routeTiles, null, target);
                Set<String> accepted = acceptedItemTypes(target);
                Set<String> incompatible = new LinkedHashSet<>(possibleItems);
                incompatible.removeAll(accepted);
                if (!accepted.isEmpty() && !incompatible.isEmpty()) {
                    lastUnsafeRoute = Map.of(
                        "possible_input_items", possibleItems,
                        "target_accepted_items", accepted,
                        "incompatible_items", incompatible,
                        "candidate_drill", Map.of("x", candidate[0], "y", candidate[1], "resource", resource)
                    );
                    continue;
                }
            }
            List<Placement> plan = new ArrayList<>();
            plan.add(new Placement(drill.name, candidate[0], candidate[1], 0));
            appendTransport(plan, path, targetFootprint, transport.name);
            String type = target instanceof CoreBuild ? "build_mine_to_core" : "build_mine_to_target";
            Map<String, Object> applied = applyPlan(index, action, type, plan, maxCost, reserveCopper);
            if (Boolean.TRUE.equals(applied.get("ok"))) {
                applied.put("resource", resource);
                applied.put("drill", drill.name);
                applied.put("transport", transport.name);
                applied.put("target", buildingRef(target));
                applied.put("ore_tiles", drillOutput(drill, candidate[0], candidate[1]).get("ore_tiles"));
                return applied;
            }
        }
        if (lastUnsafeRoute != null) {
            return actionError(index, action, "shared_route_item_incompatibility",
                "Every placeable shared route found could carry an item the selective target does not accept; nothing was built.",
                lastUnsafeRoute);
        }
        return actionError(index, action, "no_valid_mining_route",
            "No affordable valid " + resource + " route using " + drillName + " and " + transportName + " was found.");
    }

    private Map<String, Object> buildCoreDefense(int index, JsonObject action) {
        int maxCost = Math.max(1, Math.min(requireInt(action, "max_cost"), MAX_MACRO_COPPER));
        int reserveCopper = Math.max(0, Math.min(requireInt(action, "reserve_copper"), 1000));
        int wallCount = Math.max(0, Math.min(requireInt(action, "walls"), 8));
        String turretName = requireString(action, "turret");
        String wallName = requireString(action, "wall");
        String ammoName = requireString(action, "ammo");
        int ammoItems = Math.max(0, Math.min(requireInt(action, "ammo_items"), 200));
        int requestedTurrets = Math.max(1, Math.min(requireInt(action, "turrets"), MAX_TURRETS_PER_DEFENSE_ACTION));
        Block turretBlock = findBlock(turretName);
        if (!(turretBlock instanceof Turret) || !blockAvailable(turretBlock)) {
            return actionError(index, action, "invalid_turret", turretName + " is not an available turret.");
        }
        Block wall = null;
        if (!wallName.isBlank() && !wallName.equals("none")) {
            wall = findBlock(wallName);
            if (!(wall instanceof Wall) || !blockAvailable(wall)) {
                return actionError(index, action, "invalid_wall", wallName + " is not an available wall.");
            }
        }
        Item ammo = null;
        if (turretBlock instanceof ItemTurret itemTurret) {
            ammo = findItem(ammoName);
            if (ammo == null || !itemTurret.ammoTypes.containsKey(ammo)) {
                return actionError(index, action, "incompatible_ammo",
                    turretName + " cannot use " + ammoName + ". Compatible: " + compatibleAmmo(itemTurret));
            }
        } else {
            ammoItems = 0;
        }
        int remainingBudget = maxCost;
        int built = 0;
        int totalAmmoLoaded = 0;
        Map<String, Integer> totalCost = new LinkedHashMap<>();
        List<Map<String, Object>> allPlacements = new ArrayList<>();
        Map<String, Object> lastFailure = null;
        CoreBuild core = controlledTeam().core();
        if (core == null) return actionError(index, action, "no_core", "Team " + controlledTeam().name + " has no core.");
        List<int[]> threats = spawnTargets(core.tileX(), core.tileY());
        int existingTurrets = (int) controlledTeam().data().buildings.count(
            building -> building.block == turretBlock);

        for (int count = 0; count < requestedTurrets && remainingBudget > 0; count++) {
            int[] spawn = threats.get((existingTurrets + count) % threats.size());
            int[] threat = effectiveDefenseThreat(core, spawn, (Turret) turretBlock);
            Map<String, Object> result = buildOneCoreDefense(index, action, remainingBudget,
                reserveCopper, wallCount, ammoItems, ammo, turretBlock, wall, threat);
            if (!Boolean.TRUE.equals(result.get("ok"))) {
                lastFailure = result;
                break;
            }
            built++;
            @SuppressWarnings("unchecked")
            Map<String, Integer> cost = (Map<String, Integer>) result.get("cost");
            int spent = cost.getOrDefault("copper", 0);
            cost.forEach((item, amount) -> totalCost.merge(item, amount, Integer::sum));
            if (ammo != null) {
                @SuppressWarnings("unchecked")
                Map<String, Integer> loaded = (Map<String, Integer>) result.get("ammo_loaded");
                totalAmmoLoaded += loaded.getOrDefault(ammo.name, 0);
            }
            remainingBudget -= spent;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> placements = (List<Map<String, Object>>) result.get("placements");
            allPlacements.addAll(placements);
        }
        if (built == 0) return lastFailure == null
            ? actionError(index, action, "no_valid_defense_route", "No affordable valid defense line was found.")
            : lastFailure;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("ok", true);
        result.put("type", "build_core_defense");
        result.put("turrets_requested", requestedTurrets);
        result.put("turrets_built", built);
        result.put("turret", turretName);
        result.put("wall", wall == null ? "none" : wall.name);
        result.put("cost", totalCost);
        result.put("ammo_loaded", ammo == null ? Map.of() : Map.of(ammo.name, totalAmmoLoaded));
        result.put("placements", allPlacements);
        if (lastFailure != null) result.put("partial_reason", lastFailure.get("message"));
        return result;
    }

    private Map<String, Object> resupplyTurrets(int index, JsonObject action) {
        CoreBuild core = controlledTeam().core();
        if (core == null) return actionError(index, action, "no_core", "Team " + controlledTeam().name + " has no core.");
        String ammoName = requireString(action, "ammo");
        Item ammo = findItem(ammoName);
        if (ammo == null) return actionError(index, action, "unknown_ammo", "Unknown item: " + ammoName);
        int maxItems = Math.max(1, Math.min(requireInt(action, "max_items"), 200));
        int reserveCopper = Math.max(0, Math.min(requireInt(action, "reserve_copper"), 1000));
        double belowFraction = Math.max(0d, Math.min(requireDouble(action, "below_fraction"), 1d));
        int available = core.items.get(ammo) - (ammo == Items.copper ? reserveCopper : 0);
        if (!Vars.state.rules.infiniteResources && available <= 0) {
            return actionError(index, action, "ammo_reserve", "No " + ammoName + " is available above the reserve.");
        }

        int remaining = Math.min(maxItems, Vars.state.rules.infiniteResources ? maxItems : available);
        List<Map<String, Object>> supplied = new ArrayList<>();
        for (Building building : controlledTeam().data().buildings) {
            if (remaining <= 0) break;
            if (!(building instanceof ItemTurretBuild turret) || turret.getAmmoFraction() >= belowFraction) continue;
            if (!(turret.block instanceof ItemTurret itemTurret) || !itemTurret.ammoTypes.containsKey(ammo)) continue;
            int accepted = turret.acceptStack(ammo, remaining, core);
            if (accepted <= 0) continue;
            if (!Vars.state.rules.infiniteResources) core.items.remove(ammo, accepted);
            turret.handleStack(ammo, accepted, core);
            remaining -= accepted;
            supplied.add(Map.of(
                "block", turret.block.name, "x", turret.tileX(), "y", turret.tileY(),
                "items", accepted, "ammo_fraction_after", turret.getAmmoFraction()
            ));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("ok", true);
        result.put("type", "resupply_turrets");
        result.put("ammo", ammoName);
        result.put("items_transferred", maxItems - remaining);
        result.put("turrets_refilled", supplied.size());
        result.put("supplied", supplied);
        result.put("core_remaining", core.items.get(ammo));
        return result;
    }

    private Map<String, Object> commandCoreUnit(int index, JsonObject action) {
        TeamAgentState state = teamAgentState(controlledTeam());
        Unit coreDefender = state.coreDefender;
        if (coreDefender == null || !coreDefender.isAdded() || coreDefender.dead()) {
            return actionError(index, action, "core_unit_unavailable", "The core unit is not currently active.");
        }
        String mode = requireString(action, "mode");
        String resourceName = optionalString(action, "resource", "");
        if (!Set.of("idle", "mine", "supply", "defend_core", "intercept", "repair", "rebuild").contains(mode)) {
            return actionError(index, action, "invalid_unit_mode",
                "Mode must be idle, mine, supply, defend_core, intercept, repair, or rebuild.");
        }
        if (Set.of("mine", "supply").contains(mode)) {
            Item resource = findItem(resourceName);
            if (resource == null) return actionError(index, action, "unknown_resource", resourceName);
            if ("mine".equals(mode) && !coreDefender.canMine(resource)) {
                return actionError(index, action, "cannot_mine_resource",
                    coreDefender.type.name + " cannot mine " + resourceName + ".");
            }
            if ("mine".equals(mode) && !resourceAvailableForMining(resource)) {
                return actionError(index, action, "resource_not_present",
                    "No mineable " + resourceName + " deposit exists on this map.");
            }
        }
        int targetX = "supply".equals(mode) ? requireInt(action, "target_x") : -1;
        int targetY = "supply".equals(mode) ? requireInt(action, "target_y") : -1;
        if ("supply".equals(mode)) {
            Building target = ownedBuildingAt(targetX, targetY);
            Item resource = findItem(resourceName);
            if (target == null) return actionError(index, action, "invalid_supply_target",
                "Supply target must be an existing owned building.");
            Set<String> accepted = acceptedItemTypes(target);
            if (!accepted.contains(resourceName)) return actionError(index, action, "incompatible_supply_resource",
                target.block.name + " does not require or accept " + resourceName + ".",
                Map.of("accepted_items", accepted));
            CoreBuild core = controlledTeam().core();
            if (core == null || (!Vars.state.rules.infiniteResources && core.items.get(resource) <= 0)) {
                return actionError(index, action, "resource_not_in_core",
                    "The core currently has no " + resourceName + " to deliver.");
            }
        }
        String requestedResource = Set.of("mine", "supply").contains(mode) ? resourceName : "";
        boolean changed = !mode.equals(state.coreUnitTask) || !requestedResource.equals(state.coreUnitResource)
            || targetX != state.coreUnitTargetX || targetY != state.coreUnitTargetY;
        if (changed) {
            state.coreUnitTaskAssignedTick = Vars.state.tick;
            state.coreUnitDeliveredItems = 0;
            state.coreUnitDeliveryTrips = 0;
            CoreBuild core = controlledTeam().core();
            Item resource = findItem(requestedResource);
            state.coreUnitInitialCoreItemCount = core != null && resource != null ? core.items.get(resource) : 0;
        }
        state.coreUnitTask = mode;
        state.coreUnitResource = requestedResource;
        state.coreUnitTargetX = targetX;
        state.coreUnitTargetY = targetY;
        if (changed) applyCoreUnitTask(coreDefender, state);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("ok", true);
        result.put("type", "command_core_unit");
        result.put("mode", state.coreUnitTask);
        result.put("changed", changed);
        if (!state.coreUnitResource.isEmpty()) result.put("resource", state.coreUnitResource);
        if ("supply".equals(mode)) result.put("target", Map.of("x", targetX, "y", targetY));
        result.put("task_semantics", "persistent asynchronous assignment; a later command_core_unit replaces this task");
        return result;
    }

    private Map<String, Object> buildOneCoreDefense(int index, JsonObject action, int maxCost,
                                                     int reserveCopper, int wallCount, int ammoItems, Item ammo,
                                                     Block turretBlock, Block wall, int[] threat) {
        CoreBuild core = controlledTeam().core();
        if (core == null) return actionError(index, action, "no_core", "Team " + controlledTeam().name + " has no core.");

        List<int[]> candidates = new ArrayList<>();
        for (int radius = core.block.size / 2 + 2; radius <= 16; radius++) {
            for (int x = core.tileX() - radius; x <= core.tileX() + radius; x++) {
                for (int y = core.tileY() - radius; y <= core.tileY() + radius; y++) {
                    if (Math.max(Math.abs(x - core.tileX()), Math.abs(y - core.tileY())) != radius) continue;
                    if (Build.validPlace(turretBlock, controlledTeam(), x, y, 0)) candidates.add(new int[]{x, y});
                }
            }
        }
        candidates.sort(Comparator.comparingInt(point -> distance2(point[0], point[1], threat[0], threat[1])));
        for (int[] candidate : candidates) {
            List<Placement> plan = new ArrayList<>();
            plan.add(new Placement(turretBlock.name, candidate[0], candidate[1], 0));
            if (wall != null) appendThreatWalls(plan, wall, candidate[0], candidate[1], threat, wallCount);
            if (ammo != null && !Vars.state.rules.infiniteResources) {
                int required = ammoItems + (ammo == Items.copper ? reserveCopper : 0);
                if (core.items.get(ammo) < required) {
                    return actionError(index, action, "insufficient_ammo",
                        "Need " + ammoItems + " " + ammo.name + " plus applicable reserve.");
                }
            }
            int ammoCopperReserve = ammo == Items.copper ? ammoItems : 0;
            Map<String, Object> applied = applyPlan(index, action, "build_core_defense", plan,
                maxCost, reserveCopper + ammoCopperReserve);
            if (Boolean.TRUE.equals(applied.get("ok"))) {
                Tile turretTile = Vars.world.tile(candidate[0], candidate[1]);
                int loaded = 0;
                if (ammo != null && turretTile != null && turretTile.build instanceof ItemTurretBuild turret) {
                    loaded = turret.acceptStack(ammo, ammoItems, core);
                    if (loaded > 0) {
                        if (!Vars.state.rules.infiniteResources) core.items.remove(ammo, loaded);
                        turret.handleStack(ammo, loaded, core);
                    }
                }
                @SuppressWarnings("unchecked")
                Map<String, Integer> cost = (Map<String, Integer>) applied.get("cost");
                if (ammo != null) cost.put(ammo.name, cost.getOrDefault(ammo.name, 0) + loaded);
                applied.put("ammo_loaded", ammo == null ? Map.of() : Map.of(ammo.name, loaded));
                return applied;
            }
        }
        return actionError(index, action, "no_valid_defense_position",
            "No affordable valid " + turretBlock.name + " defense position was found.");
    }

    private Map<String, Object> applyPlan(int index, JsonObject action, String type,
                                           List<Placement> plan, int maxCopper, int reserveCopper) {
        CoreBuild core = controlledTeam().core();
        Map<Item, Integer> total = new LinkedHashMap<>();
        Set<Integer> occupied = new HashSet<>();
        for (Placement placement : plan) {
            Block block = findBlock(placement.block());
            if (block == null || !Build.validPlace(block, controlledTeam(), placement.x(), placement.y(), placement.rotation())) {
                Map<String, Object> diagnostics = block == null
                    ? Map.of("block", placement.block(), "x", placement.x(), "y", placement.y(), "rotation", placement.rotation(), "reason", "unknown_block")
                    : placementDiagnostics(block, placement.x(), placement.y(), placement.rotation());
                return actionError(index, action, "plan_became_invalid",
                    "Planned " + placement.block() + " at " + placement.x() + "," + placement.y()
                        + " rotation " + placement.rotation() + " is not placeable.", diagnostics);
            }
            for (int key : footprintKeys(block, placement.x(), placement.y())) {
                if (!occupied.add(key)) return actionError(index, action, "overlapping_plan", "Macro placements overlap.");
            }
            for (Map.Entry<Item, Integer> cost : effectiveCost(block).entrySet()) total.merge(cost.getKey(), cost.getValue(), Integer::sum);
        }
        Item copper = Items.copper;
        int copperCost = total.getOrDefault(copper, 0);
        if (copperCost > maxCopper) return actionError(index, action, "max_cost_exceeded", "Plan needs " + copperCost + " copper.");
        if (!Vars.state.rules.infiniteResources) {
            for (Map.Entry<Item, Integer> cost : total.entrySet()) {
                int remaining = core.items.get(cost.getKey()) - cost.getValue();
                if (remaining < 0) return actionError(index, action, "insufficient_resources", "Need " + cost.getValue() + " " + cost.getKey().name);
                if (cost.getKey() == copper && remaining < reserveCopper) {
                    return actionError(index, action, "copper_reserve", "Plan would leave " + remaining + " copper below reserve " + reserveCopper + ".");
                }
            }
            for (Map.Entry<Item, Integer> cost : total.entrySet()) core.items.remove(cost.getKey(), cost.getValue());
        }
        List<Map<String, Object>> placements = new ArrayList<>();
        for (Placement placement : plan) {
            Block block = findBlock(placement.block());
            Vars.world.tile(placement.x(), placement.y()).setNet(block, controlledTeam(), placement.rotation());
            placements.add(Map.of("block", placement.block(), "x", placement.x(), "y", placement.y(), "rotation", placement.rotation()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("ok", true);
        result.put("type", type);
        result.put("cost", namedCost(total));
        result.put("placements", placements);
        return result;
    }

    private Map<String, Object> drillOutput(Drill drill, int x, int y) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        Tile anchor = Vars.world.tile(x, y);
        if (anchor != null) {
            Seq<Tile> linked = new Seq<>();
            anchor.getLinkedTilesAs(drill, linked);
            for (Tile tile : linked) {
                Item drop = drill.getDrop(tile);
                if (drop != null && drill.canMine(tile)) counts.merge(drop, 1, Integer::sum);
            }
        }
        Item dominant = null;
        int dominantCount = 0;
        for (Map.Entry<Item, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > dominantCount) {
                dominant = entry.getKey();
                dominantCount = entry.getValue();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("produces", dominant == null ? null : dominant.name);
        result.put("ore_tiles", dominantCount);
        return result;
    }

    private List<Map<String, Integer>> footprintState(Block block, int x, int y) {
        List<Map<String, Integer>> result = new ArrayList<>();
        Tile anchor = Vars.world.tile(x, y);
        if (anchor == null) return result;
        Seq<Tile> linked = new Seq<>();
        anchor.getLinkedTilesAs(block, linked);
        for (Tile tile : linked) result.add(Map.of("x", (int) tile.x, "y", (int) tile.y));
        return result;
    }

    private Set<Integer> footprintKeys(Block block, int x, int y) {
        Set<Integer> result = new HashSet<>();
        Tile anchor = Vars.world.tile(x, y);
        if (anchor == null) return result;
        Seq<Tile> linked = new Seq<>();
        anchor.getLinkedTilesAs(block, linked);
        for (Tile tile : linked) result.add(tileKey(tile.x, tile.y));
        return result;
    }

    private boolean validItemTransport(Block block) {
        return blockAvailable(block) && block.size == 1 && block.rotate
            && block.conveyorPlacement && block.outputsItems();
    }

    private boolean validLiquidTransport(Block block) {
        return blockAvailable(block) && block.size == 1 && block.rotate
            && block.hasLiquids && block.outputsLiquid && block.outputFacing;
    }

    private List<int[]> transportPath(Set<Integer> source, Set<Integer> target, Set<Integer> forbidden,
                                      int centerX, int centerY, Block transport) {
        return transportPath(source, target, forbidden, centerX, centerY, transport, true, "", null, null);
    }

    private List<int[]> transportPath(Set<Integer> source, Set<Integer> target, Set<Integer> forbidden,
                                      int centerX, int centerY, Block transport, boolean reuseExisting,
                                      String isolatedResource, Building isolatedSource, Building isolatedTarget) {
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        Map<Integer, Integer> previous = new HashMap<>();
        for (int sourceKey : source) {
            int sx = sourceKey % Vars.world.width();
            int sy = sourceKey / Vars.world.width();
            for (int[] direction : DIRECTIONS) {
                int x = sx + direction[0], y = sy + direction[1];
                int key = tileKey(x, y);
                if (target.contains(key) || forbidden.contains(key) || previous.containsKey(key)) continue;
                if (!insideMacroArea(x, y, centerX, centerY)
                    || !potentialTransportTile(x, y, transport, reuseExisting)
                    || !isolationSafeTransportTile(x, y, isolatedResource, isolatedSource, isolatedTarget)) continue;
                previous.put(key, Integer.MIN_VALUE);
                queue.add(key);
            }
        }
        Integer found = null;
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            int x = current % Vars.world.width(), y = current / Vars.world.width();
            if (adjacentTo(x, y, target)) {
                int[] destination = adjacentTarget(x, y, target);
                int rotation = rotationToward(x, y, destination[0], destination[1]);
                if (usableTransportTile(x, y, transport, rotation, reuseExisting)) {
                    found = current;
                    break;
                }
            }
            for (int[] direction : DIRECTIONS) {
                int nx = x + direction[0], ny = y + direction[1];
                int next = tileKey(nx, ny);
                if (target.contains(next) || forbidden.contains(next) || previous.containsKey(next)) continue;
                int rotation = rotationToward(x, y, nx, ny);
                if (!usableTransportTile(x, y, transport, rotation, reuseExisting)) continue;
                if (!insideMacroArea(nx, ny, centerX, centerY)
                    || !potentialTransportTile(nx, ny, transport, reuseExisting)
                    || !isolationSafeTransportTile(nx, ny, isolatedResource, isolatedSource, isolatedTarget)) continue;
                previous.put(next, current);
                queue.addLast(next);
            }
        }
        if (found == null) return null;
        List<int[]> path = new ArrayList<>();
        for (int key = found; key != Integer.MIN_VALUE; key = previous.get(key)) {
            path.add(new int[]{key % Vars.world.width(), key / Vars.world.width()});
        }
        Collections.reverse(path);
        return path;
    }

    private int minimumTransportTileDistance(Set<Integer> source, Set<Integer> target) {
        int minimum = Integer.MAX_VALUE;
        for (int sourceKey : source) {
            int sourceX = sourceKey % Vars.world.width(), sourceY = sourceKey / Vars.world.width();
            for (int targetKey : target) {
                int targetX = targetKey % Vars.world.width(), targetY = targetKey / Vars.world.width();
                minimum = Math.min(minimum,
                    Math.abs(sourceX - targetX) + Math.abs(sourceY - targetY) - 1);
            }
        }
        return Math.max(1, minimum == Integer.MAX_VALUE ? 1 : minimum);
    }

    private boolean isolationSafeTransportTile(int x, int y, String resource,
                                                Building source, Building target) {
        if (resource == null || resource.isBlank()) return true;
        for (int[] direction : DIRECTIONS) {
            Tile neighborTile = Vars.world.tile(x + direction[0], y + direction[1]);
            Building neighbor = neighborTile == null ? null : neighborTile.build;
            if (neighbor == null || neighbor == source || neighbor == target) continue;

            String produced = producedItemName(neighbor);
            if (produced != null && !resource.equals(produced)) return false;

            if (isDirectedItemTransport(neighbor)) {
                Building front = neighbor.front();
                if (front != null && front.tileX() == x && front.tileY() == y) return false;
            } else if (produced == null && neighbor.block.size == 1 && neighbor.block.hasItems
                && neighbor.block.outputsItems()) {
                // Routers, junctions, containers, and similar undirected item blocks may emit toward
                // any adjacent tile. Keep a newly isolated lane away from that unknown item source.
                return false;
            }
        }
        return true;
    }

    private void appendTransport(List<Placement> plan, List<int[]> path, Set<Integer> target, String transportName) {
        for (int index = 0; index < path.size(); index++) {
            int[] current = path.get(index);
            int nextX, nextY;
            if (index + 1 < path.size()) {
                nextX = path.get(index + 1)[0];
                nextY = path.get(index + 1)[1];
            } else {
                int[] destination = adjacentTarget(current[0], current[1], target);
                nextX = destination[0];
                nextY = destination[1];
            }
            int rotation = rotationToward(current[0], current[1], nextX, nextY);
            Tile tile = Vars.world.tile(current[0], current[1]);
            Building existing = tile == null ? null : tile.build;
            if (existing != null && existing.team == controlledTeam()
                && existing.block.name.equals(transportName) && existing.rotation == rotation) {
                continue;
            }
            plan.add(new Placement(transportName, current[0], current[1], rotation));
        }
    }

    private boolean potentialTransportTile(int x, int y, Block transport) {
        return potentialTransportTile(x, y, transport, true);
    }

    private boolean potentialTransportTile(int x, int y, Block transport, boolean reuseExisting) {
        for (int rotation = 0; rotation < DIRECTIONS.length; rotation++) {
            if (usableTransportTile(x, y, transport, rotation, reuseExisting)) return true;
        }
        return false;
    }

    private boolean usableTransportTile(int x, int y, Block transport, int rotation) {
        return usableTransportTile(x, y, transport, rotation, true);
    }

    private boolean usableTransportTile(int x, int y, Block transport, int rotation, boolean reuseExisting) {
        Tile tile = Vars.world.tile(x, y);
        if (tile == null) return false;
        Building existing = tile.build;
        if (existing != null) {
            return reuseExisting && existing.team == controlledTeam()
                && existing.block == transport && existing.rotation == rotation;
        }
        return Build.validPlace(transport, controlledTeam(), x, y, rotation);
    }

    private Set<String> possibleItemsForAuthoredPath(
        Building source, Building target, List<Placement> authoredPath) {
        Set<String> initialItems = new LinkedHashSet<>();
        String sourceOutput = producedItemName(source);
        if (sourceOutput != null) initialItems.add(sourceOutput);
        else initialItems.addAll(storedItemTypes(source));

        Set<Integer> networkTiles = new HashSet<>();
        for (Placement placement : authoredPath) networkTiles.add(tileKey(placement.x(), placement.y()));
        return possibleItemsForTransportTiles(initialItems, networkTiles, source, target);
    }

    private Set<String> possibleItemsForTransportTiles(
        Set<String> initialItems, Set<Integer> plannedTiles, Building source, Building target) {
        Set<String> result = new LinkedHashSet<>(initialItems);
        Set<Integer> networkTiles = new HashSet<>(plannedTiles);
        List<Building> transports = new ArrayList<>();
        for (Building building : controlledTeam().data().buildings) {
            if (isDirectedItemTransport(building)) transports.add(building);
        }
        boolean changed;
        do {
            changed = false;
            for (Building transport : transports) {
                int key = tileKey(transport.tileX(), transport.tileY());
                Building front = transport.front();
                if (networkTiles.contains(key)) {
                    result.addAll(storedItemTypes(transport));
                    continue;
                }
                if (front != null && networkTiles.contains(tileKey(front.tileX(), front.tileY()))) {
                    networkTiles.add(key);
                    result.addAll(storedItemTypes(transport));
                    changed = true;
                }
            }
        } while (changed);

        for (Building building : controlledTeam().data().buildings) {
            if (building == source || building == target || building instanceof CoreBuild
                || isDirectedItemTransport(building)) continue;
            String produced = producedItemName(building);
            if (produced == null) continue;
            if (footprintAdjacentToKeys(building, networkTiles)) result.add(produced);
        }
        return result;
    }

    private boolean footprintAdjacentToKeys(Building building, Set<Integer> keys) {
        for (int footprintKey : footprintKeys(building.block, building.tileX(), building.tileY())) {
            int x = footprintKey % Vars.world.width(), y = footprintKey / Vars.world.width();
            for (int[] direction : DIRECTIONS) {
                if (keys.contains(tileKey(x + direction[0], y + direction[1]))) return true;
            }
        }
        return false;
    }

    private String conveyorPlanSignature(
        Building source, Building target, String transportName, List<Placement> placements,
        Set<String> allowedItems, int maxCost, int reserveCopper) {
        Map<String, Object> signature = new LinkedHashMap<>();
        signature.put("episode_id", episodeId);
        signature.put("source", List.of(source.tileX(), source.tileY()));
        signature.put("target", List.of(target.tileX(), target.tileY()));
        signature.put("transport", transportName);
        signature.put("placements", placements.stream()
            .map(placement -> List.of(placement.x(), placement.y(), placement.rotation())).toList());
        signature.put("allowed_items", allowedItems);
        signature.put("max_cost", maxCost);
        signature.put("reserve_copper", reserveCopper);
        return gson.toJson(signature);
    }

    private Map<String, Object> placementDiagnostics(Block block, int x, int y, int rotation) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("block", block.name);
        diagnostics.put("x", x);
        diagnostics.put("y", y);
        diagnostics.put("rotation", rotation);
        Tile tile = Vars.world.tile(x, y);
        if (tile == null) {
            diagnostics.put("reason", "outside_map");
            return diagnostics;
        }
        Building existing = tile.build;
        if (existing != null) {
            diagnostics.put("reason", "occupied");
            diagnostics.put("occupied_by", buildingRef(existing));
        } else {
            diagnostics.put("reason", "terrain_footprint_or_game_rule_rejected");
        }
        List<Integer> validRotations = new ArrayList<>();
        for (int candidate = 0; candidate < DIRECTIONS.length; candidate++) {
            if (Build.validPlace(block, controlledTeam(), x, y, candidate)) validRotations.add(candidate);
        }
        diagnostics.put("valid_rotations_at_anchor", validRotations);
        return diagnostics;
    }

    private void appendThreatWalls(List<Placement> plan, Block wall, int turretX, int turretY,
                                   int[] threat, int wallCount) {
        int dx = Integer.compare(threat[0], turretX);
        int dy = Integer.compare(threat[1], turretY);
        if (Math.abs(threat[0] - turretX) >= Math.abs(threat[1] - turretY)) dy = 0;
        else dx = 0;
        int px = -dy, py = dx;
        Set<Integer> planned = new HashSet<>();
        for (Placement placement : plan) planned.addAll(footprintKeys(findBlock(placement.block()), placement.x(), placement.y()));
        int requested = wallCount;
        int placed = 0;
        for (int offset = -(requested / 2); offset <= requested / 2 && placed < requested; offset++) {
            int x = turretX + dx * 2 + px * offset;
            int y = turretY + dy * 2 + py * offset;
            int key = tileKey(x, y);
            if (!planned.contains(key) && Build.validPlace(wall, controlledTeam(), x, y, 0)) {
                plan.add(new Placement(wall.name, x, y, 0));
                planned.add(key);
                placed++;
            }
        }
    }

    private int[] nearestSpawn(int x, int y) {
        int[] best = {x, y};
        int bestDistance = Integer.MAX_VALUE;
        for (int tx = 0; tx < Vars.world.width(); tx++) for (int ty = 0; ty < Vars.world.height(); ty++) {
            Tile tile = Vars.world.tile(tx, ty);
            if (tile != null && tile.overlay() == Blocks.spawn) {
                int distance = distance2(x, y, tx, ty);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new int[]{tx, ty};
                }
            }
        }
        return best;
    }

    private List<int[]> spawnTargets(int x, int y) {
        Map<String, int[]> byDirection = new LinkedHashMap<>();
        if (Vars.state != null && Vars.state.rules.pvp) {
            for (Team team : Team.all) {
                if (team == controlledTeam() || team == Team.derelict) continue;
                CoreBuild enemyCore = team.core();
                if (enemyCore == null) continue;
                int dx = enemyCore.tileX() - x, dy = enemyCore.tileY() - y;
                String direction = Integer.compare(dx, 0) + ":" + Integer.compare(dy, 0);
                int[] point = new int[]{enemyCore.tileX(), enemyCore.tileY()};
                int[] existing = byDirection.get(direction);
                if (existing == null || distance2(x, y, point[0], point[1]) < distance2(x, y, existing[0], existing[1])) {
                    byDirection.put(direction, point);
                }
            }
        }
        for (int tx = 0; tx < Vars.world.width(); tx++) for (int ty = 0; ty < Vars.world.height(); ty++) {
            Tile tile = Vars.world.tile(tx, ty);
            if (tile == null || tile.overlay() != Blocks.spawn) continue;
            int dx = tx - x, dy = ty - y;
            String direction = Integer.compare(dx, 0) + ":" + Integer.compare(dy, 0);
            int[] existing = byDirection.get(direction);
            if (existing == null || distance2(x, y, tx, ty) < distance2(x, y, existing[0], existing[1])) {
                byDirection.put(direction, new int[]{tx, ty});
            }
        }
        List<int[]> result = new ArrayList<>(byDirection.values());
        result.sort(Comparator.comparingInt(point -> distance2(x, y, point[0], point[1])));
        if (result.isEmpty()) result.add(new int[]{x, y});
        return result;
    }

    private boolean adjacentTo(int x, int y, Set<Integer> target) {
        for (int[] direction : DIRECTIONS) if (target.contains(tileKey(x + direction[0], y + direction[1]))) return true;
        return false;
    }

    private int[] adjacentTarget(int x, int y, Set<Integer> target) {
        for (int[] direction : DIRECTIONS) {
            if (target.contains(tileKey(x + direction[0], y + direction[1]))) return new int[]{x + direction[0], y + direction[1]};
        }
        throw new IllegalStateException("Path does not end next to target.");
    }

    private boolean insideMacroArea(int x, int y, int centerX, int centerY) {
        return x >= 0 && y >= 0 && x < Vars.world.width() && y < Vars.world.height()
            && Math.abs(x - centerX) <= MACRO_PATH_RADIUS && Math.abs(y - centerY) <= MACRO_PATH_RADIUS;
    }

    private int tileKey(int x, int y) {
        if (x < 0 || y < 0 || x >= Vars.world.width() || y >= Vars.world.height()) return Integer.MIN_VALUE + 1;
        return x + y * Vars.world.width();
    }

    private int distance2(int x1, int y1, int x2, int y2) {
        int dx = x1 - x2, dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    private int rotationToward(int x, int y, int nextX, int nextY) {
        int dx = nextX - x, dy = nextY - y;
        for (int rotation = 0; rotation < DIRECTIONS.length; rotation++) {
            if (DIRECTIONS[rotation][0] == dx && DIRECTIONS[rotation][1] == dy) return rotation;
        }
        return 0;
    }

    private TeamAgentState teamAgentState(Team team) {
        return teamAgentStates.computeIfAbsent(team.id, ignored -> new TeamAgentState());
    }

    private static final class TeamAgentState {
        private Unit coreDefender;
        private double coreDefenderRespawnTick;
        private String coreUnitTask = "idle";
        private String coreUnitResource = "";
        private int coreUnitTargetX = -1;
        private int coreUnitTargetY = -1;
        private double coreUnitTaskAssignedTick;
        private int coreUnitDeliveredItems;
        private int coreUnitDeliveryTrips;
        private int coreUnitInitialCoreItemCount;
        private String modelName = "";
        private String strategy = "";
        private int lastDecisionTurn;
        private double lastLatencySeconds;
        private double telemetryLatencyTotal;
        private int telemetryLatencySamples;
        private int lastExecutedActionCount;
        private int lastPreflightSkippedCount;
        private double lastTelemetryTick;
        private final Map<String, RtsSquad> squads = new LinkedHashMap<>();
        private final ArrayDeque<RtsUnitCommandReceipt> recentUnitCommands = new ArrayDeque<>();
        private final ArrayDeque<RtsUnitLoss> recentUnitLosses = new ArrayDeque<>();
        private final Map<String, Integer> unitLossesByType = new LinkedHashMap<>();
        private int totalUnitLosses;
    }

    private static final class RtsControlPoint {
        private final String id;
        private final int x;
        private final int y;
        private final Map<Integer, Integer> lastPresence = new LinkedHashMap<>();
        private Team owner;
        private Team capturingTeam;
        private double captureProgress;
        private boolean contested;
        private int captures;

        private RtsControlPoint(String id, int x, int y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }

        private float worldX() { return x * Vars.tilesize; }
        private float worldY() { return y * Vars.tilesize; }
    }

    private static final class RtsSquad {
        private final String id;
        private final LinkedHashSet<Integer> unitIds = new LinkedHashSet<>();
        private String unitSelector = "all";
        private String mode = "stop";
        private int targetX;
        private int targetY;
        private int targetRadius = 2;
        private int engagementRadius = 14;
        private Integer targetUnitId;
        private double assignedTick;
        private double nextUpdateTick;
        private int assignedMemberCount;
        private double lastAverageDistanceTiles = Double.NaN;
        private double lastProgressTick;
        private double emptySinceTick = Double.NaN;

        private RtsSquad(String id) {
            this.id = id;
        }
    }

    private static final class RtsUnitLoss {
        private final double tick;
        private final int unitId;
        private final String unitType;
        private final double x;
        private final double y;
        private final String squadId;
        private final String orderMode;

        private RtsUnitLoss(
            double tick, int unitId, String unitType, double x, double y,
            String squadId, String orderMode
        ) {
            this.tick = tick;
            this.unitId = unitId;
            this.unitType = unitType;
            this.x = x;
            this.y = y;
            this.squadId = squadId;
            this.orderMode = orderMode;
        }
    }

    private static final class RtsUnitCommandReceipt {
        private final double tick;
        private final String squadId;
        private final String unitSelector;
        private final String requestedMode;
        private final String effectiveMode;
        private final List<Integer> unitIds;
        private final boolean accepted;
        private final int pendingReinforcements;
        private final int targetX;
        private final int targetY;
        private final String fallbackReason;

        private RtsUnitCommandReceipt(
            double tick, String squadId, String unitSelector, String requestedMode,
            String effectiveMode, List<Integer> unitIds, boolean accepted,
            int pendingReinforcements, int targetX, int targetY, String fallbackReason
        ) {
            this.tick = tick;
            this.squadId = squadId;
            this.unitSelector = unitSelector;
            this.requestedMode = requestedMode;
            this.effectiveMode = effectiveMode;
            this.unitIds = List.copyOf(unitIds);
            this.accepted = accepted;
            this.pendingReinforcements = pendingReinforcements;
            this.targetX = targetX;
            this.targetY = targetY;
            this.fallbackReason = fallbackReason;
        }
    }

    private static final class RtsConstructionOrder {
        private final Team team;
        private final String blockName;
        private final int x;
        private final int y;
        private final int rotation;
        private final double buildTicks;
        private final double startsAtTick;
        private final double completesAtTick;
        private final Map<Item, Integer> cost;
        private String status = "queued";

        private RtsConstructionOrder(
            Team team, String blockName, int x, int y, int rotation, double buildTicks,
            double startsAtTick, double completesAtTick, Map<Item, Integer> cost
        ) {
            this.team = team;
            this.blockName = blockName;
            this.x = x;
            this.y = y;
            this.rotation = rotation;
            this.buildTicks = buildTicks;
            this.startsAtTick = startsAtTick;
            this.completesAtTick = completesAtTick;
            this.cost = cost;
        }
    }

    private static final class RtsTrainingOrder {
        private final Team team;
        private final int factoryX;
        private final int factoryY;
        private final String unitName;
        private final int requested;
        private final double ticksPerUnit;
        private final Map<String, Integer> totalCost;
        private final Integer rallyX;
        private final Integer rallyY;
        private final int rallyRadius;
        private final String squadId;
        private int completed;
        private int remaining;
        private double nextCompletionTick;
        private double lastSpeedUpdateTick;
        private String status = "training";

        private RtsTrainingOrder(Team team, int factoryX, int factoryY, String unitName,
                                 int requested, double ticksPerUnit, double nextCompletionTick,
                                 double lastSpeedUpdateTick, Map<String, Integer> totalCost,
                                 Integer rallyX, Integer rallyY, int rallyRadius, String squadId) {
            this.team = team;
            this.factoryX = factoryX;
            this.factoryY = factoryY;
            this.unitName = unitName;
            this.requested = requested;
            this.remaining = requested;
            this.ticksPerUnit = ticksPerUnit;
            this.nextCompletionTick = nextCompletionTick;
            this.lastSpeedUpdateTick = lastSpeedUpdateTick;
            this.totalCost = totalCost;
            this.rallyX = rallyX;
            this.rallyY = rallyY;
            this.rallyRadius = rallyRadius;
            this.squadId = squadId;
        }
    }

    private static final class RtsUpgradeOrder {
        private final Team team;
        private final int reconstructorX;
        private final int reconstructorY;
        private final String fromUnitName;
        private final String toUnitName;
        private final int requested;
        private final double ticksPerUnit;
        private final Map<String, Integer> totalCost;
        private int completed;
        private int remaining;
        private double nextCompletionTick;
        private double lastSpeedUpdateTick;
        private String status = "upgrading";

        private RtsUpgradeOrder(Team team, int reconstructorX, int reconstructorY,
                                String fromUnitName, String toUnitName, int requested,
                                double ticksPerUnit, double nextCompletionTick, double lastSpeedUpdateTick,
                                Map<String, Integer> totalCost) {
            this.team = team;
            this.reconstructorX = reconstructorX;
            this.reconstructorY = reconstructorY;
            this.fromUnitName = fromUnitName;
            this.toUnitName = toUnitName;
            this.requested = requested;
            this.remaining = requested;
            this.ticksPerUnit = ticksPerUnit;
            this.nextCompletionTick = nextCompletionTick;
            this.lastSpeedUpdateTick = lastSpeedUpdateTick;
            this.totalCost = totalCost;
        }
    }

    private String blockRole(String name) {
        if (name.contains("drill")) return "extracts ore under its footprint; output must reach the core";
        if (name.contains("conveyor") || name.equals("junction") || name.contains("sorter") || name.contains("gate") || name.equals("router")) return "item transport/logistics";
        if (name.equals("duo")) return "short-range turret; accepts copper/lead/graphite/silicon ammunition";
        if (Set.of("scatter", "scorch", "hail", "salvo", "lancer", "arc", "wave").contains(name)) return "defensive turret; requires compatible ammo or power/liquid";
        if (name.contains("wall")) return "blocks enemies and absorbs damage; produces nothing";
        if (name.contains("generator") || name.contains("solar") || name.startsWith("battery") || name.startsWith("power-node")) return "power production/storage/distribution";
        if (name.equals("graphite-press") || name.equals("silicon-smelter") || name.equals("kiln")) return "resource factory; requires input delivery and may require power";
        if (name.contains("pump") || name.contains("conduit") || name.equals("liquid-router")) return "liquid extraction/transport";
        return "specialized production, storage, repair, or unit infrastructure";
    }

    private boolean canAfford(CoreBuild core, Map<Item, Integer> cost) {
        if (Vars.state.rules.infiniteResources) return true;
        if (core == null) return false;
        for (Map.Entry<Item, Integer> entry : cost.entrySet()) if (core.items.get(entry.getKey()) < entry.getValue()) return false;
        return true;
    }

    private record Placement(String block, int x, int y, int rotation) {}

    private Map<Item, Integer> effectiveCost(Block block) {
        Map<Item, Integer> result = new LinkedHashMap<>();
        for (ItemStack requirement : block.requirements) {
            int amount = Math.max(0, Math.round(requirement.amount * Vars.state.rules.buildCostMultiplier));
            if (amount > 0) result.put(requirement.item, amount);
        }
        return result;
    }

    private Map<String, Integer> blockCost(Block block) {
        return namedCost(effectiveCost(block));
    }

    private Map<String, Integer> namedCost(Map<Item, Integer> raw) {
        Map<String, Integer> result = new LinkedHashMap<>();
        raw.forEach((item, amount) -> result.put(item.name, amount));
        return result;
    }

    private Block findBlock(String name) {
        return Vars.content.blocks().find(block -> block.name.equals(name));
    }

    private Item findItem(String name) {
        return Vars.content.items().find(item -> item.name.equals(name));
    }

    private boolean resourceAvailableForMining(Item item) {
        return Vars.indexer != null && (Vars.indexer.hasOre(item) || Vars.indexer.hasWallOre(item));
    }

    private void ensureGameReady() {
        if (Vars.state == null || !Vars.state.isGame()) {
            throw new IllegalArgumentException("No map is loaded.");
        }
        if (Vars.state.gameOver) throw new IllegalArgumentException("The episode is over.");
    }

    private Team authorizeTeam(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        String supplied = exchange.getRequestHeaders().getFirst("Authorization");
        String bearer = supplied != null && supplied.startsWith("Bearer ")
            ? supplied.substring("Bearer ".length()) : "";
        if (!tokenTeams.isEmpty()) {
            Team team = tokenTeams.get(bearer);
            if (team != null) return team;
            sendError(exchange, 401, "unauthorized", "Missing or invalid team bearer token.");
            return null;
        }
        if (!authToken.isEmpty() && !authToken.equals(bearer)) {
            sendError(exchange, 401, "unauthorized", "Missing or invalid bearer token.");
            return null;
        }
        String requested = exchange.getRequestHeaders().getFirst("X-Mindustry-Team");
        Team team = requested == null || requested.isBlank() ? Team.sharded : findTeamByName(requested.trim());
        if (team != null && team != Team.derelict) return team;
        sendError(exchange, 400, "invalid_team", "Unknown or uncontrollable Mindustry team.");
        return null;
    }

    private void loadTeamTokens() {
        tokenTeams.clear();
        String raw = System.getenv().getOrDefault("MINDUSTRY_TEAM_TOKENS", "").trim();
        if (raw.isEmpty()) return;
        for (String entry : raw.split(";")) {
            String[] parts = entry.trim().split("=", 2);
            if (parts.length != 2 || parts[1].isBlank()) {
                throw new IllegalArgumentException(
                    "MINDUSTRY_TEAM_TOKENS entries must use team=token separated by semicolons.");
            }
            Team team = findTeamByName(parts[0].trim());
            if (team == null || team == Team.derelict) {
                throw new IllegalArgumentException("Unknown team in MINDUSTRY_TEAM_TOKENS: " + parts[0]);
            }
            if (tokenTeams.put(parts[1].trim(), team) != null) {
                throw new IllegalArgumentException("Duplicate token in MINDUSTRY_TEAM_TOKENS.");
            }
        }
    }

    private Team findTeamByName(String name) {
        for (Team team : Team.all) if (team.name.equalsIgnoreCase(name)) return team;
        return null;
    }

    private Set<Team> configuredAgentTeams() {
        if (!tokenTeams.isEmpty()) return new LinkedHashSet<>(tokenTeams.values());
        return Set.of(Team.sharded);
    }

    private Team controlledTeam() {
        Team team = requestTeam.get();
        return team == null ? Team.sharded : team;
    }

    private <T> T withControlledTeam(Team team, ThrowingSupplier<T> supplier) throws Exception {
        Team previous = requestTeam.get();
        requestTeam.set(team);
        try {
            return supplier.get();
        } finally {
            requestTeam.set(previous);
        }
    }

    private <T> T onGameThread(ThrowingSupplier<T> supplier) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        Core.app.post(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        try {
            return future.get(GAME_THREAD_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw error;
        }
    }

    private int readPort() {
        String raw = System.getenv().getOrDefault("MINDUSTRY_AGENT_PORT", String.valueOf(DEFAULT_PORT));
        try {
            int port = Integer.parseInt(raw);
            if (port < 1 || port > 65535) throw new NumberFormatException();
            return port;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid MINDUSTRY_AGENT_PORT: " + raw);
        }
    }

    private boolean readBoolean(String name, boolean defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return defaultValue;
        return !Set.of("0", "false", "no", "off").contains(value.trim().toLowerCase());
    }

    private int parseTurns(String[] args) {
        if (args.length == 0) return 20;
        try {
            return Math.max(1, Math.min(Integer.parseInt(args[0]), 10_000));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("turns must be an integer from 1 to 10000");
        }
    }

    private boolean canControlAgent(Player player) {
        String ip = player.ip();
        return player.admin || "127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip);
    }

    private synchronized String startActiveAgents(int turns) {
        if (Vars.state != null && Vars.state.isGame() && Vars.state.rules.pvp) {
            return startPvpAgents(turns);
        }
        return startAgent(turns);
    }

    private synchronized String startPvpAgents(int turns) {
        if (Vars.state == null || !Vars.state.isGame() || Vars.state.gameOver || !Vars.state.rules.pvp) {
            return "cannot start PvP agents: no active PvP game (run pvp-start first)";
        }
        if (agentProcess != null && agentProcess.isAlive()) {
            return "cannot start PvP agents: single agent is already running (pid " + agentProcess.pid() + ")";
        }
        pvpAgentProcesses.entrySet().removeIf(entry -> !entry.getValue().isAlive());
        if (!pvpAgentProcesses.isEmpty()) return "already running: " + pvpAgentStatus();
        if (Team.sharded.core() == null || Team.crux.core() == null) {
            return "cannot start PvP agents: this map must have sharded and crux cores";
        }

        String projectDir = System.getenv().getOrDefault("MINDUSTRY_AGENT_PROJECT_DIR", "").trim();
        File workingDirectory = new File(projectDir);
        if (projectDir.isEmpty() || !workingDirectory.isDirectory()) {
            return "cannot start PvP agents: MINDUSTRY_AGENT_PROJECT_DIR is invalid";
        }
        String primaryToken = tokenForTeam(Team.sharded);
        String deepSeekToken = tokenForTeam(Team.crux);
        if (primaryToken == null || deepSeekToken == null) {
            return "cannot start PvP agents: MINDUSTRY_TEAM_TOKENS must contain sharded and crux";
        }

        String python = System.getenv().getOrDefault("MINDUSTRY_AGENT_PYTHON", "python").trim();
        String primaryModel = System.getenv().getOrDefault("PVP_PRIMARY_MODEL", "glm-5.3-flash:cloud").trim();
        String deepSeekModel = System.getenv().getOrDefault("PVP_DEEPSEEK_MODEL", "deepseek-v4.1-flash:cloud").trim();
        String primaryReasoning = System.getenv().getOrDefault("PVP_PRIMARY_REASONING_EFFORT", "low").trim();
        String deepSeekReasoning = System.getenv().getOrDefault("PVP_DEEPSEEK_REASONING_EFFORT", "none").trim();
        Process primaryProcess = null;
        try {
            primaryProcess = startTeamAgentProcess(python, workingDirectory, primaryModel, primaryModel,
                primaryReasoning, Team.sharded, primaryToken, turns);
            Process deepSeekProcess = startTeamAgentProcess(python, workingDirectory, "DeepSeek", deepSeekModel,
                deepSeekReasoning, Team.crux, deepSeekToken, turns);
            pvpAgentProcesses.put(Team.sharded.name, primaryProcess);
            pvpAgentProcesses.put(Team.crux.name, deepSeekProcess);
            watchPvpAgentProcess(primaryModel, Team.sharded.name, primaryProcess);
            watchPvpAgentProcess("DeepSeek", Team.crux.name, deepSeekProcess);
            return "started " + primaryModel + " vs " + deepSeekModel + " for " + turns
                + " turns (primary pid " + primaryProcess.pid()
                + ", DeepSeek pid " + deepSeekProcess.pid() + ")";
        } catch (IOException error) {
            if (primaryProcess != null && primaryProcess.isAlive()) primaryProcess.destroy();
            pvpAgentProcesses.clear();
            Log.err("Unable to start PvP agents", error);
            return "failed to start PvP agents: " + error.getMessage();
        }
    }

    private Process startTeamAgentProcess(String python, File workingDirectory, String label,
                                           String model, String reasoningEffort, Team team, String token,
                                           int turns) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
            python, "-m", "mindustry_agent",
            "--model", model,
            "--reasoning-effort", reasoningEffort,
            "--team", team.name,
            "--game-token", token,
            "--max-turns", Integer.toString(turns));
        builder.directory(workingDirectory);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        Log.info("[PvP] started @ as @ with model @ (pid @)", label, team.name, model, process.pid());
        return process;
    }

    private void watchPvpAgentProcess(String label, String teamName, Process process) {
        Thread outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) Log.info("[@ PvP agent] @", label, line);
                int exitCode = process.waitFor();
                Log.info("[@ PvP agent] exited with code @", label, exitCode);
            } catch (Exception error) {
                if (process.isAlive()) Log.err(label + " PvP agent output reader failed", error);
            } finally {
                synchronized (this) {
                    if (pvpAgentProcesses.get(teamName) == process) pvpAgentProcesses.remove(teamName);
                }
            }
        }, "mindustry-pvp-" + teamName + "-output");
        outputThread.setDaemon(true);
        outputThread.start();
    }

    private String tokenForTeam(Team team) {
        for (Map.Entry<String, Team> entry : tokenTeams.entrySet()) {
            if (entry.getValue() == team) return entry.getKey();
        }
        return null;
    }

    private synchronized String startAgent(int turns) {
        if (agentProcess != null && agentProcess.isAlive()) {
            return "already running (pid " + agentProcess.pid() + ")";
        }
        if (Vars.state == null || !Vars.state.isGame() || Vars.state.gameOver) {
            return "cannot start: no active game";
        }

        String projectDir = System.getenv().getOrDefault("MINDUSTRY_AGENT_PROJECT_DIR", "").trim();
        if (projectDir.isEmpty()) {
            return "cannot start: MINDUSTRY_AGENT_PROJECT_DIR is unset";
        }
        File workingDirectory = new File(projectDir);
        if (!workingDirectory.isDirectory()) {
            return "cannot start: project directory does not exist";
        }

        String python = System.getenv().getOrDefault("MINDUSTRY_AGENT_PYTHON", "python").trim();
        ProcessBuilder builder = new ProcessBuilder(
            python, "-m", "mindustry_agent", "--max-turns", Integer.toString(turns));
        builder.directory(workingDirectory);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            agentProcess = process;
            Thread outputThread = new Thread(() -> watchAgentProcess(process), "mindustry-llm-agent-output");
            outputThread.setDaemon(true);
            outputThread.start();
            return "started for " + turns + " turns (pid " + process.pid() + ")";
        } catch (IOException error) {
            Log.err("Unable to start LLM agent", error);
            return "failed to start: " + error.getMessage();
        }
    }

    private void watchAgentProcess(Process process) {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Log.info("[LLM agent] @", line);
            }
            int exitCode = process.waitFor();
            Log.info("[LLM agent] exited with code @", exitCode);
        } catch (Exception error) {
            if (process.isAlive()) Log.err("LLM agent output reader failed", error);
        } finally {
            synchronized (this) {
                if (agentProcess == process) agentProcess = null;
            }
        }
    }

    private synchronized String stopAgent() {
        if (agentProcess == null || !agentProcess.isAlive()) {
            agentProcess = null;
            return "not running";
        }
        long pid = agentProcess.pid();
        agentProcess.destroy();
        return "stop requested (pid " + pid + ")";
    }

    private synchronized String agentStatus() {
        if (agentProcess == null || !agentProcess.isAlive()) return "stopped";
        return "running (pid " + agentProcess.pid() + ")";
    }

    private synchronized String stopPvpAgents() {
        pvpAgentProcesses.entrySet().removeIf(entry -> !entry.getValue().isAlive());
        if (pvpAgentProcesses.isEmpty()) return "PvP agents not running";
        List<String> stopped = new ArrayList<>();
        for (Map.Entry<String, Process> entry : pvpAgentProcesses.entrySet()) {
            Process process = entry.getValue();
            if (process.isAlive()) {
                process.destroy();
                stopped.add(entry.getKey() + " pid " + process.pid());
            }
        }
        pvpAgentProcesses.clear();
        return "PvP agent stop requested (" + String.join(", ", stopped) + ")";
    }

    private synchronized String pvpAgentStatus() {
        pvpAgentProcesses.entrySet().removeIf(entry -> !entry.getValue().isAlive());
        if (pvpAgentProcesses.isEmpty()) return "stopped";
        List<String> running = new ArrayList<>();
        for (Map.Entry<String, Process> entry : pvpAgentProcesses.entrySet()) {
            running.add(entry.getKey() + "=running(pid " + entry.getValue().pid() + ")");
        }
        return String.join(", ", running);
    }

    private synchronized String stopAllAgents() {
        String single = stopAgent();
        String pvp = stopPvpAgents();
        return "single: " + single + "; pvp: " + pvp;
    }

    private synchronized String activeAgentStatus() {
        return "single=" + agentStatus() + "; pvp=" + pvpAgentStatus();
    }

    private void shutdown() {
        stopAllAgents();
        stopHttpServer();
    }

    private void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream stream = exchange.getRequestBody()) {
            byte[] body = stream.readNBytes(256 * 1024 + 1);
            if (body.length > 256 * 1024) throw new IllegalArgumentException("Request body is too large.");
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] encoded = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, encoded.length);
        exchange.getResponseBody().write(encoded);
        exchange.close();
    }

    private void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        sendJson(exchange, status, Map.of("ok", false, "error", code,
            "message", message == null ? "Unknown error" : message));
    }

    private Map<String, Object> actionError(int index, String code, String message) {
        return Map.of("index", index, "ok", false, "error", code, "message", message);
    }

    private Map<String, Object> actionError(int index, JsonObject action, String code, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("ok", false);
        result.put("error", code);
        result.put("message", message);
        result.put("attempted_action", gson.fromJson(action, Object.class));
        return result;
    }

    private Map<String, Object> actionError(int index, JsonObject action, String code, String message,
                                             Map<String, Object> diagnostics) {
        Map<String, Object> result = new LinkedHashMap<>(actionError(index, action, code, message));
        result.put("diagnostics", diagnostics);
        return result;
    }

    private JsonArray requireArray(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) throw new IllegalArgumentException(name + " must be an array.");
        return value.getAsJsonArray();
    }

    private Set<String> requireStringSet(JsonObject object, String name) {
        JsonArray values = requireArray(object, name);
        Set<String> result = new LinkedHashSet<>();
        for (JsonElement value : values) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(name + " must contain only strings.");
            }
            String item = value.getAsString().trim();
            if (!item.isEmpty()) result.add(item);
        }
        return result;
    }

    private String requireString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) throw new IllegalArgumentException(name + " must be a string.");
        return value.getAsString();
    }

    private int requireInt(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) throw new IllegalArgumentException(name + " must be an integer.");
        try {
            return value.getAsInt();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be an integer.");
        }
    }

    private int optionalInt(JsonObject object, String name, int fallback) {
        return object.has(name) ? requireInt(object, name) : fallback;
    }

    private double optionalDouble(JsonObject object, String name, double fallback) {
        if (!object.has(name)) return fallback;
        JsonElement value = object.get(name);
        if (!value.isJsonPrimitive()) throw new IllegalArgumentException(name + " must be a number.");
        try {
            return value.getAsDouble();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be a number.");
        }
    }

    private double requireDouble(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) throw new IllegalArgumentException(name + " must be a number.");
        try {
            return value.getAsDouble();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be a number.");
        }
    }

    private String optionalString(JsonObject object, String name, String fallback) {
        return object.has(name) ? requireString(object, name) : fallback;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static final class StaleEpisodeException extends IllegalArgumentException {
        private StaleEpisodeException(String message) {
            super(message);
        }
    }
}

