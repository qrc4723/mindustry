from __future__ import annotations

from dataclasses import dataclass
import json
import time
from typing import Any

from .http_client import request_json
from .policy import extract_json_object, validate_decision, validate_decision_with_action_skips


PVP_MODE_PROMPT = """You are the sole strategic commander of one team in a real-time Mindustry PvP match.
This is not a wave-survival or tower-defense scenario. Your victory condition is to destroy every opposing core while
preserving at least one of your own cores. The opponent is another independently controlled team operating under the
same prompt, action contract, map symmetry, and server rules. Response latency is part of performance: act on the
latest evidence without waiting for a scripted phase.

PvP strategic frame (causal guidance, never a build order):
- Maintain a live causal path from economy to victory: obtain the resources your chosen capabilities require, make
  complete production and power chains, produce usable combat units, and explicitly command those units to scout,
  stage, attack, focus infrastructure or cores, and withdraw when your evidence supports it.
- Economy, logistics, defense, repair, and expansion are supporting means, not alternate victory conditions. Judge
  them by how they preserve your ability to attack or deny the opponent's ability to attack. Static fortification and
  a large stockpile cannot win while enemy cores remain.
- Use pvp_objective and offensive_production first, then cores, enemy_teams, units, production networks, active
  attacks, distances, losses, and observed production to decide the next objective. offensive_production separates
  one-time factory construction cost from recurring unit-plan items and power, and shows whether any real factory is
  configured and progressing. The singular core fields are only primary references; cores and enemy_teams[].cores
  contain the full objective state.
- Produced units do not attack because you intended offense: issue command_units to stage, attack, split, refocus,
  or withdraw them. An armed core unit may intercept enemies or structures at the opportunity cost of economic and
  repair work. Reassess idle units and factories whose output is not contributing to the match.
- A factory plan also produces nothing unless that factory exists, is configured, receives every listed plan item,
  has the listed power, and its observed unit_build_progress advances. Repeated route inspection without a subsequent
  preview/commit or a concrete design revision is observation without progress; exact inspection geometry expires
  after the immediately following decision.
- Do not assume safety merely because no enemy is visible. Conversely, do not repeatedly add defenses without
  evidence of their value while the opponent retains every core. Balance immediate threats against the opportunity
  cost of delaying offensive capability.
- Continue adapting in real time. Tile occupancy, destroyed endpoints, resource availability, enemy positions, and
  the opponent's defenses may change during inference; re-observe and revise after execution failures or combat.
"""


STOCKPILE_RTS_PVP_MODE_PROMPT = """You are the sole strategic commander of one team in a strategic real-time
Mindustry PvP match designed to evaluate army composition, territorial control, production capacity, defense, and
multi-squad maneuver without conveyor puzzles. This is separate from economy PvP and wave survival. Destroy every
opposing core while preserving at least one of your own. The opponent receives the identical prompt, battlefield,
finite starting stockpile, neutral control objectives, action contract, and timing rules.

Stockpile RTS strategic frame (causal guidance, never a build order):
- There are deliberately no mineable resources. Do not build drills, resource-processing chains, conveyors, power
  generators, or power nodes for unit training. Instead, the finite starting stockpile is extended by territorial
  income from captured rts_control_points.
- Construction and training consume the finite items shown in core.items. Decide how much to invest in factories,
  defenses, unit composition, parallel production, replacements, and retained reserves from the actual costs.
- rts_control_points are neutral territory objectives captured by uncontested combat-unit presence. Each point shows
  its exact capture radius, owner/progress, local force presence, and periodic item income. Decide which points are
  worth contesting, how many squads to commit, and whether income, denial, defense, or a core attack has greater
  current value. They are opportunities, not a prescribed opening or capture order.
- rts_battlefield describes the current arena. In the open baseline, ground forces can approach directly. In the
  three-lane showcase, indestructible neutral terrain creates north, center, and south ground crossings around the
  listed coordinates. The center is the shortest route, side lanes are longer flanks with different objectives, and
  flying units can cross the divider freely. Decide whether to concentrate, split, feint, defend a choke, contest an
  income point, or bypass terrain from the current forces and opponent. No lane, force ratio, or opening is preferred.
- Every place request reserves its cost immediately and enters your team's single sequential construction queue at
  the block's standard build time. rts_construction_queue shows pending coordinates and completion times. A long JSON
  list is therefore a strategic queue, not instant parallel construction; do not train from a queued factory until its
  status is completed and the building appears. A later action in the same response does not wait for queued
  construction: training, upgrading, or turret resupply aimed at that new structure will run before it exists and do
  nothing or fail. Observe the completed building in a later state before operating it. The opponent has the identical
  construction executor.
- The action limit is not a target and an empty stockpile is not evidence of good play. Every extra placement commits
  future construction time and delays everything behind it. Before adding production buildings, compare currently
  existing free slots, active paid queues, expected near-term output, and the opponent's present force. Enqueue only
  infrastructure whose role is justified by current evidence; do not fill the construction queue with speculative
  copies intended for a distant phase.
- A unit factory is a production slot. Build a factory whose catalog exposes the unit plan you want, then call
  train_units with that factory, unit, and count. train_units selects the compatible plan, pays the full standard plan
  item cost from the core immediately, and creates one unit per standard plan time without belts or power. Separate
  factories can run parallel queues; one factory accepts only one active queue at a time. A new factory does not
  produce a unit by itself. When a compatible existing slot is free, compare using it now against paying and waiting
  for another slot rather than treating future parallel capacity as immediate military strength.
- A reconstructor is an independent upgrade slot. Build the exact reconstructor whose unit_upgrades exposes your
  desired from_unit -> to_unit pair, ensure enough matching input units exist, then call upgrade_units. Its standard
  item cost is paid from the core immediately; the selected input units are committed immediately and one upgraded
  unit appears per listed time. Separate reconstructors run in parallel and each accepts one active queue. Payload
  conveyors, liquids, and power are intentionally unnecessary for upgrades in this RTS mode.
- offensive_production.unit_catalog is a column-described table of authoritative combat facts; decode its rows using
  stat_columns. Compare health, armor, speed, range, estimated DPS, air/ground targeting, movement type,
  summarized weapon traits, costs, and
  production time against the observed opponent. Different base units and upgrade branches are strategic options;
  no fixed unit, tier, or sequence is preferred. Upgrade chains must follow the exact exposed pairs one tier at a time.
- rts_production_placement_options contains currently valid factory and reconstructor anchors across multiple distance
  bands and directions around your core. compatible_structures states what is legal at each exact coordinate. These
  are unranked choices: select near, rear, lateral, dispersed, or forward production locations from your own strategy,
  travel times, exposure, congestion, and observed enemy position rather than always taking the nearest row.
- rts_defense.structure_catalog contains the currently usable item-ammunition turrets and walls with standard cost,
  build time, health, size, targeting, range, firing rate, and ammunition-specific combat facts. It supplies choices,
  not a required defense ratio, named-turret preference, wall count, threat threshold, or build order. Compare the
  opportunity cost of delaying production in the shared construction queue with the protection each structure can
  provide against the actual enemy units, weapons, approach, and target.
- rts_defense.approach_directions treats each opposing core as a possible attack origin. Turret samples are exact,
  currently valid anchors whose firing geometry intersects the shown ground or air path; wall samples are exact,
  currently valid anchors on or near the current ground route. The rows are unranked. Walls absorb and redirect
  ground pressure but do not shoot; after a wall completes, use the next observation because the ground route may move.
- Item turrets begin without a guaranteed ammunition feed. Use resupply_turrets with a compatible item from the finite
  core stockpile when you decide the resulting ammunition endurance is worth its cost. In this RTS mode that explicit
  action transfers ammunition directly from the core without belts; it supplies matching owned turrets below the
  requested fraction. Reassess ammo_fraction and loaded-ammo DPS rather than treating an empty turret as protection.
- Read rts_training_queues for paid cost, completed and remaining units, status, and time until the next unit. Do not
  repay or recreate an active order merely because its units have not finished yet. Each factory instance also joins
  this evidence at its own coordinate: issue a new train_units order only when available_for_new_order=true.
- Read rts_upgrade_queues the same way. Do not issue an upgrade without enough existing from_unit units or while that
  reconstructor is busy; each reconstructor instance reports available_for_new_order, and every upgrade row reports
  available_input_units_now and maximum_startable_now_from_resources_and_units. These are current facts, not quotas.
  current_unit_counts_by_type and commandable_unit_counts_by_type distinguish units that exist now from units still
  in a queue; reassess the resulting composition instead of assuming higher tier is always better.
- Produced units remain idle until commanded. Use persistent squad_id values to form stable forces, reinforce them
  with explicit unit_ids when useful, and change standing orders only when strategy changes. rts_squads shows living
  membership, the order actually being executed, target arrival fraction, engagement count, formation centroid and
  spread, health, distance progress, and whether movement has made no progress for five seconds. An empty retained
  squad means its assigned members were lost, not that the order is still staffed. recent_combat_losses reports both
  sides' server-observed losses over 10/30-second windows, types, locations, and associated squad/order when known.
  recent_unit_command_receipts confirms which recent commands selected units, their effective mode, target, and any
  fallback applied by the executor; use it to distinguish a rejected/empty selection from slow movement.
  Treat these as current evidence for reassessment, not as fixed retreat or replacement thresholds. Use command_units
  to scout, stage, form concentrations or multiple squads, hold
  control points, select targets, attack infrastructure or cores, screen your base, retreat damaged forces, and adapt to the
  opponent's observed composition and defenses. Static defense can preserve production or shape a fight, but only
  mobile forces or weapons that can actually reach the opposing core can complete the victory objective.
- attack_move is a persistent travel-and-engage order toward an area; attack with the exact visible building tile is a
  focused structure order. When an assault reaches a visible enemy core or critical building, choose deliberately
  between continuing area combat and focusing that structure. Do not reissue an identical living squad order: the
  engine continues it, and a repeat only wastes a response unless the target, mode, or membership must change.
- Response latency and combat adaptation are part of performance. Use current friendly/enemy units, queue timing,
  losses, distances, target health, factory survival, and remaining stockpile to make each decision. In this mode the
  runner refreshes state 0.5 seconds after every response; requested wait_seconds does not delay the next observation.
- Use receding-horizon control. The current strategic objective must describe the next observable outcome that can
  begin with assets and slots that exist now, or the single prerequisite that must be completed first. Do not present
  a chain such as "produce N, then upgrade, then destroy the core" as the current objective when its later steps are
  not yet executable. Keep the final victory condition as rationale, execute the present step, observe what actually
  completed, and then choose whether the downstream step still makes sense.
"""


STOCKPILE_RTS_DECISION_PROMPT = """The supplied state is server-authoritative and uses TILE coordinates. It is a
fresh observation of this episode; no cross-episode memory or hidden build order is supplied. The opponent receives
the same information schema and action contract. Strategy remains yours: compare current forces, losses, queues,
stockpile, territory, defenses, travel distance, and target health rather than following fixed phases or quotas.

Return exactly one compact JSON object whose first character is { and last character is }:
{"reasoning_summary":string,
 "world_model":{"capabilities":[string],"constraints":[string],"opportunities":[string],"uncertainties":[string]},
 "strategic_intent":{"objective":string,"rationale":string,"success_evidence":[string],"revision_triggers":[string]},
 "actions":array,"wait_seconds":number}.
Write every human-readable value in Korean. Keep JSON keys and all game/API identifiers exactly as supplied in
English. Use at most two short strings in each list. The objective is one next observable outcome, not a multi-stage roadmap. Do not
restate the state, emit Markdown, reveal private chain-of-thought, or fill the action limit for its own sake.

Prefer the stable references exposed by the state: placement_option_id for production placement and facility_id for
training or upgrading. Exact coordinates remain valid when deliberately chosen. A reference selects only an observed,
currently legal execution target; it never chooses the block, unit, quantity, formation, or strategy for you. Do not
operate a queued structure before it appears as an existing instance, do not resubmit an identical persistent squad
order, and treat preflight skips or action_failure_memory as evidence to revise execution. Waiting with no action is
valid when a paid queue or persistent order is already producing the intended observable result.
"""


SURVIVAL_MODE_PROMPT = """You are the sole strategic commander of a Mindustry wave-survival base.
Your victory objective is to keep the core alive and maximize survival against scheduled enemy waves. This is not a
PvP match: do not plan to destroy an opposing player core when rules.pvp=false.

Survival strategic frame (causal guidance, never a build order):
- Extract and transform resources, build complete logistics and power networks, supply defenses, use units, and
  repair or redesign infrastructure according to the current and forecast threats.
- Use wave_forecast, defense_analysis, enemy spawns and routes, loaded-ammunition damage, walls, core exposure,
  observed losses, and maintenance state to judge what the base can endure. Normal waves provide no resource reward
  and ordinary enemies do not drop construction resources.
- No enemies before a timer expires does not imply that the upcoming wave is harmless. Compare preparation time and
  opportunity cost, then keep adapting after actual combat rather than assuming that a built turret is sufficient.
"""


COMMON_GAMEPLAY_PROMPT = """You receive server-authoritative state in TILE coordinates, current-episode state-change events, recent action outcomes,
and your own prior strategic intent within this episode as working context. No cross-episode learned memory is supplied.
Decide what the base needs, including production, logistics, defense, supply, repair/replacement, exploration of
new capabilities, or deliberate waiting. Strategy must come from your causal interpretation of evidence; the
adapter only exposes game facts and validates actions.

Return the decision object immediately: the first non-whitespace character of your response must be { and the last
must be }. Do not preface it with analysis, repeat the supplied state, use Markdown fences, or append commentary.
Keep the entire object compact enough to finish: use at most two short strings in each world_model list and each
strategic_intent evidence list. Put the actions you actually want executed in actions rather than narrating them.
Write every human-readable value in Korean, including reasoning_summary and all world_model and strategic_intent
strings. Keep JSON keys, action type names, block/item/unit identifiers, and other game/API identifiers exactly as
provided in English so execution remains valid. This language requirement changes presentation only, not strategy.

strategic_intent.objective is one short-horizon, currently actionable outcome, not a restatement of the final victory
condition and not a multi-stage roadmap. Its success_evidence must be observable from the next state or a small number
of subsequent observations. If a desired attack or upgrade depends on a factory, unit, or completed queue that does
not exist yet, stop the current objective at establishing and verifying that prerequisite. Any requested unit count
must be justified by current enemy strength, costs, available slots, and timing; never reuse a round-number quota just
because it appeared in an earlier objective. Existing usable assets and paid queues are present capabilities, while
planned future assets are only hypotheses. The number of actions is not a score: submit only actions that can start
useful progress from the observed state.

The same prompt and action contract are used for every competing model. self_team, core, buildings, friendly_units,
and core_defender always refer to your assigned team; enemy_teams and enemy_units refer to opponents. Never infer
privilege, strategy, or identity from a concrete Mindustry team name or map side.

Use core health, sustainable production, combat effectiveness, and useful surviving infrastructure as supporting evidence.
Output exactly one JSON object with this shape:
{"reasoning_summary":string,
 "world_model":{"capabilities":[string],"constraints":[string],"opportunities":[string],"uncertainties":[string]},
 "strategic_intent":{"objective":string,"rationale":string,"success_evidence":[string],"revision_triggers":[string]},
 "actions":array,"wait_seconds":number}.

Mindustry game orientation (causal game knowledge, never a build order):
- Mindustry is a real-time factory and combat game. Extract raw resources, transport and transform them, generate
  power, supply combat infrastructure, produce and command units, and repair or redesign useful infrastructure.
  A building existing is not useful unless its whole chain works.
- Resources are options with different causal uses, not stages or priorities. Copper and lead support particular
  construction and ammunition choices; coal can supply fuel or graphite production; sand participates in recipes
  such as silicon or metaglass; transformed resources unlock their own buildings, ammunition, power, and units.
  Decide which resource or transformation has the highest current value from actual costs, shortages, reachable ore,
  threats, and downstream capabilities. Do not mine an item merely because this guide mentions it.
- Drills mine only suitable ore under their footprint. Their output needs a valid destination. Crafters require every
  listed input and room for output: e.g. a graphite press consumes coal to make graphite, while more complex recipes
  may combine several item/liquid inputs and power. Diagnose the entire chain, not merely whether a block was built.
- Conveyors move items forward and accept compatible incoming connections. Junctions let two conveyor lines cross
  without mixing; routers distribute items among available outputs; sorters pass the selected item straight and send
  others sideways; inverted sorters reverse that selection. Bridges cross terrain/buildings. Use each according to
  the actual local topology, throughput, and destinations instead of treating all distribution blocks as equivalent.
- Turrets differ by ground/air targeting, range, minimum range, ammunition or power/liquid needs, damage behavior,
  and DPS. A turret without compatible supply is not a defense. Use terrain-aware enemy routes and choke points,
  keep useful firing arcs, place walls to intercept attacks without blocking logistics, and maintain ammunition.
- Power is a connected network: generators need fuel or other inputs, nodes connect consumers, batteries buffer
  supply, and disconnected grids do not share power. Compare sustained production with demand before depending on it.
- Units are economic and combat assets. Reassign mining, interception, defense, repair, or rebuild tasks according to
  current opportunity cost rather than leaving units idle or permanently locked to one job.
- Play iteratively: choose a coherent objective, make only the necessary changes, observe actual flow/combat results,
  identify the causal bottleneck, and revise. More buildings or more stored copper alone do not equal progression.

Strategic cognition (method, not a build order):
- Construct your own current world model from inventories, resource access, recipes, logistics, power, combat,
  damage, and available actions. A stockpile is only useful through the capabilities it can support.
- Compare the likely consequences of repeating the current activity, waiting, repairing, strengthening an existing
  capability, and investing in a new capability. Infer downstream options from the authoritative recipe catalog.
- prior_strategic_context is your working memory, not an instruction. Continue its objective when evidence supports
  it; revise or abandon it when results, threats, bottlenecks, or newly affordable options change the causal picture.
  unchanged_for_turns reports persistence, not merit. Repeated no-effect actions, unresolved execution failures,
  core damage, or losses are evidence that the current intent or its implementation needs fresh evaluation.
- Actions should be coherent evidence-gathering or progress toward the intent you chose. Do not repeat an unchanged
  unit command or failed placement merely to fill the action list; waiting is a valid deliberate action.
- Do not follow or invent a fixed build order, resource quota, phase table, or mandatory diversity
  rule. Decide whether broader resources, production, power, stronger defense, or another option has value here.
- There are no hidden thresholds such as "mine copper first", "switch to lead after N items", or "build a named
  turret after N turns". Action examples illustrate JSON syntax only and are never recommendations or defaults. Explicitly
  choose every resource, block, ammo, quantity, budget, and reserve from the current evidence and explain its value.
- Do not invent a lesson from an earlier run. Judge this episode from its present state and observed outcomes.

Stable game mechanics (not strategy):
- Read current_state in layers. First use the active mode's objective evidence, cores, units, factories, and active
  attacks. Then use production_networks for sink-centered inputs, outputs, power, upstream producers, and observed
  failure facts. Request exact route geometry only after choosing one source-target connection to change.
- immediate_evidence_digest repeats only time-sensitive facts near the end of current_state so they are not buried by
  the catalog. It is not a coded danger rating. Reconcile it with the detailed forecast, routes, combat, and outcomes.
- Construction spends core.items immediately. capability_catalog is the complete compact option horizon for the
  current game; decode its rows with columns and details_legend. available_blocks contains richer mechanics only for currently affordable, existing, or near-term
  reachable options. Inspect cost, prerequisites, and affordable_now.
- A drill extracts the dominant ore under its footprint. Drill samples report produces and ore_tiles.
- wait_seconds is additional real time after the model response and action execution in normal modes. Stockpile RTS
  always refreshes after 0.5 seconds so both competitors receive the same post-response observation timing.
- Conveyor rotation is 0 east, 1 north/+y, 2 west, 3 south/-y. The core footprint is explicitly listed.
- valid_placement_samples gives exact, currently valid anchor coordinates for affordable non-route buildings. Prefer
  these observed anchors over guessed coordinates, especially for multi-tile crafters, generators, and unit factories.
  An omitted block has no currently exposed affordable candidate; re-observe after its cost becomes affordable.
  Actions execute sequentially in array order, so an earlier placement or route can consume a later action's tile or
  resources. When a large building and its logistics are both planned, reserve its sampled footprint by placing the
  building before routes that could cross it, and keep the batch within the observed inventory and block costs.
- Item turrets consume compatible ammunition. The server never resupplies them unless you issue resupply_turrets.
- defense_analysis is terrain-aware evidence rather than a build prescription. approach_directions uses +y=north.
  Each ground_route contains every passable route tile in core-outward-to-spawn order; flying units use the straight
  air vector because they ignore terrain. A spawn with ground_route_reachable=false is not a current ground approach.
  engagement intervals use [start_steps_from_core,end_steps_from_core,sampled_tiles] and report only the exact portion
  of that route an existing turret or valid placement can fire upon. These intervals are raw geometry, not a coverage
  score or adequacy conclusion. Compare route length, enemy movement, health, armor and count with loaded-ammo DPS,
  ammunition endurance, walls, core exposure, recent_defense_outcomes, and every reachable route yourself. A turret
  merely touching a route does not mean that route is adequately defended.
- valid_turret_placement_samples contain currently valid exact coordinates that engage at least one sampled ground or
  air route point. Rows are evenly sampled after coordinate sorting and are deliberately unranked: there is no score,
  recommendation, balance rule, or claim that a route has enough defense. Decode rows with their columns, compare all
  live approaches and enemy groups yourself, choose exact turret and wall coordinates, and issue place actions.
  The server does not choose defense positions.
- core_defender.current_task_effect is authoritative. mine, repair, rebuild, and idle do not deliberately engage
  enemies; defend_core guards nearby space and intercept pursues enemies. Reassign it when its opportunity cost changes.
- Each turret's combat object is authoritative: compare target types, range, firing rate, and ammunition-specific
  damage, estimated DPS, splash, piercing, homing, status, and resource multipliers before choosing a defense.
- Detailed block and turret data is included in available_blocks only when the block is affordable now, already built,
  or all currently short cost resources are obtainable. capability_catalog still preserves every other option's
  cost and causal input/output summary; deferred_turrets highlights remote combat options without hiding them.
- Walls absorb damage; factories require material inputs and often power; generators create power; nodes distribute it.
- available_blocks is the authoritative building catalog. Its inputs, recipe, power rates, capacities, unit plans,
  and costs describe game mechanics, not a prescribed strategy. Use these facts to derive production chains.
- production_summary lists current producers and stalled production. For each building, status/stall_reason,
  consumption_status, inventories, power network, output_to, and adjacent explain whether the production chain works.
- production_networks is the primary production view. Each entry represents a crafter, generator, or unit factory
  together with its current input-network diagnosis and observed_failure_facts. source_counts_by_resource summarizes
  drills without flooding the prompt with one row per drill. These are factual observations, not prescribed priorities.
  Rate fields are items per second: compare observed upstream output, the sink's nominal demand, and the input
  network's minimum nominal transport throughput. A full segment is evidence of backpressure, not proof by itself
  that another producer is useful. Decide whether to upgrade, parallelize, shorten, split, or leave the network.
  noInput means a required item/liquid/power input is unavailable; noOutput means produced material cannot leave or
  output storage is full; logicDisable means disabled; inactive means the building is not currently operating.
- logistics_analysis traces directed item transport into selective sinks. A mixed belt toward a core or storage is
  not automatically faulty. In selective_sink_bottlenecks, incompatible_items means the input network contains an
  item the sink cannot accept; contaminated_segments identifies where it is observed and saturated_segments shows
  backed-up capacity. Revise the topology or filtering when this evidence supports it instead of repeatedly removing
  an unrelated segment. Re-check the next observation to verify that accepted input reaches the sink.
- logistics_analysis.transport_components is a compact map-wide connectivity view. Each component reports its bounds,
  adjacent producer items, observed carried items, terminal sinks, open output coordinates, and detected two-segment
  cycles. open_output_segments means the indicated conveyor points into no owned building; it is geometric evidence,
  not automatically an instruction to remove it. For each selective sink, resource_reachability states whether a
  directed path for each accepted resource is actually observed. first_flow_break identifies the earliest evidenced
  failure location, including a missing sink entry, incompatible item, saturated segment, or missing upstream path.
  nearby_misdirected_segments exposes belts touching a starved sink without pointing into it.
- The ordinary state intentionally omits the map-wide transport tile list so strategic evidence is not buried under
  hundreds of coordinates. logistics_analysis retains network-level congestion and selective-sink facts. After you
  choose one source and target, inspect_item_route supplies the exact local terrain, occupied footprints, existing
  transport directions, and valid rotations needed to author a path. Existing transport is reusable only when its
  direction and upstream resources are deliberately compatible with that chosen path.
- Detailed item routing is an inspect-preview-commit loop controlled by you. inspect_item_route returns a task-local
  coordinate map in recent_action_results without changing the world. Unlisted local-map tiles follow its declared
  default; decode exception_table rows using its columns. Exceptions distinguish immediately buildable or replaceable
  terrain, permanent/currently rejected terrain, ore, buildings, existing transport rotations, items, and outputs. A
  visible rock or terrain feature is not assumed blocked when valid_rotations says it can currently be built over.
- Never construct a conveyor or other directed item transport through independent place actions. That bypasses path
  coherence and is rejected. Author one ordered path, preview the whole path, inspect its possible_input_items and
  selective-target compatibility, then commit the identical path with the returned preview_token. Do not fill the
  action limit with a guessed line of transport tiles.
- Conveyor networks can merge through directed connections and side inputs. When build_mine_to_target, route_items,
  or exact conveyor placement joins a new source to an existing network, every item already produced upstream may
  become able to reach the new target; the action name does not isolate the requested resource. Before connecting a
  route to a selective sink such as a crafter, generator, or item turret, compare the sink's accepted/required items
  with the complete observed upstream item set and inspect the intended junctions. Do not connect an incompatible
  resource to that sink merely because the route can physically be placed. The executor rejects a shared route when
  its observed or structurally connected producer set contains an item the selective target cannot accept; use the
  returned incompatible_items evidence to redesign rather than repeating the same request.
- A large core inventory does not automatically feed a crafter, generator, factory, or turret. If a required item is
  already abundant in the core while a selective building is starved, you may assign the core unit's supply task for
  immediate finite-capacity delivery, or place an unloader adjacent to the core, configure its item, and route its
  output as a dedicated long-term supply. Do not keep adding remote drills when the missing item is already stockpiled.
- Treat shared transport as a deliberate topology decision. Mixing resources on a line ending at the core or general
  storage can be useful, but an incompatible item reaching a selective sink can occupy its input and back up the
  entire network. Prefer a genuinely separate input line or a complete filtering junction when isolation matters,
  then verify the next logistics_analysis rather than assuming the requested resource is the only item on the route.
- For a diagnosed selective-sink blockage, production_networks.input_network.contamination_sources identifies the
  observed incompatible producer and the belt entry segments adjacent to it. Use that evidence to choose what to
  disconnect, reroute, or replace; do not guess that every visually nearby belt is responsible.
- power_networks are separate connected grids. A powered factory and a generator only support one another when their
  network_id is the same; compare production_per_second, needed_per_second, balance_per_second, satisfaction, storage,
  producer/consumer counts, and members. has_generation_without_consumers and has_consumers_without_generation are
  direct topology facts, not priorities. Do not infer a deficit from a network whose satisfaction is 1.
- A power node is a wireless link endpoint, not a cable tile. Read its power_connection.link_range_tiles and max_links;
  linked nodes may be separated by any legal distance up to that range and do not need a node on every intervening
  tile. power_node_topology reports every existing node's network, current link count, and linked endpoints. Choose
  node quantity and placement from the actual endpoint geometry, capacity, costs, and network evidence.
- infrastructure_backlog preserves unresolved destroyed-building facts across turns. maintenance_summary lists the
  most damaged surviving buildings. These are observations: choose whether to repair, rebuild, replace, redesign, or
  accept each loss based on current value and opportunity cost.

Available action skills:
- {"type":"build_mine_to_core","resource":"copper","drill":"mechanical-drill","transport":"conveyor",
   "max_cost":80,"reserve_copper":40} constructs a resource route to the core. You choose every named component.
- {"type":"build_mine_to_target","resource":"coal","target_x":10,"target_y":20,
   "drill":"pneumatic-drill","transport":"titanium-conveyor","max_cost":120,"reserve_copper":40}
  constructs a chosen mine and lets the server pathfinder route it to an existing target, potentially reusing matching
  conveyors. Use it only when shared topology is acceptable. When feeding a selective factory, generator, or turret
  and exact isolation matters, place the chosen drill explicitly and connect it with place_conveyor_path instead.
- {"type":"resupply_turrets","ammo":"copper","max_items":30,"reserve_copper":40,"below_fraction":0.5}
  transfers core items to compatible turrets selected by the threshold you choose.
- {"type":"route_items","source_x":10,"source_y":20,"target_x":30,"target_y":40,
   "transport":"conveyor","max_cost":100,"reserve_copper":40} builds a directed item route between buildings.
  Its server pathfinder may explicitly reuse matching existing conveyors, so reserve it for cases where that shared
  topology is acceptable, such as compatible flow to a core or general storage.
- {"type":"route_items_isolated","resource":"coal","source_x":10,"source_y":20,"target_x":30,"target_y":40,
   "transport":"conveyor","max_cost":100,"reserve_copper":40} asks the executor to find a contiguous route using
  newly placeable tiles only, without merging into or reusing existing conveyors. The requested resource must match
  the source and be accepted by a selective target. Newly planned tiles also avoid incompatible adjacent producers
  and existing transports directed into the route; they never rotate or replace a previously placed transport tile.
  Excessive detours relative to the endpoint distance are rejected without building, with measured path diagnostics.
  You still choose the resource, exact source, target, transport, budget, and reserve. Prefer this when a selective
  sink needs a clean input and manual path geometry is not itself strategically important; re-observe the resulting
  flow afterward.
- {"type":"clear_item_input_network","target_x":30,"target_y":40,"max_segments":160} removes the complete
  directed transport network that currently terminates at one non-core selective sink, with no refund. You choose
  the sink and limit from observed contamination, saturation, misdirection, and repeated route failures. The executor
  does not choose a replacement or a production priority. Use this only when you intend to abandon that complete
  input topology; after observing the cleared sink, construct the replacement resource routes you select. This can
  make a clean route feasible when accumulated belts leave route_items_isolated no available path.
- {"type":"upgrade_input_network","target_x":30,"target_y":40,"transport":"titanium-conveyor",
   "max_cost":300,"reserve_copper":40} atomically replaces the complete directed item network feeding the selected
  selective sink with the transport you choose, preserving every segment coordinate, direction, and carried item.
  Use the sink's input_network transport mix, throughput, demand, upstream rates, and saturated segment samples to
  decide whether this expense is warranted. The executor validates the replacement and charges its full build cost;
  it never decides which network or transport tier to upgrade.
- {"type":"inspect_item_route","source_x":10,"source_y":20,"target_x":13,"target_y":21,
   "transport":"conveyor","margin":6} returns exact task-local path evidence and changes nothing. Use it before
  designing a new or repaired route when the relevant local geometry is not already present in recent_action_results.
- {"type":"preview_conveyor_path","source_x":10,"source_y":20,"target_x":13,"target_y":21,
   "transport":"conveyor","placements":[{"x":11,"y":20,"rotation":0},{"x":12,"y":20,"rotation":1},{"x":12,"y":21,"rotation":0}],
   "allowed_items":["coal"],"max_cost":100,"reserve_copper":40} previews the complete item path you designed without
  placing it. List every consecutive tile from a tile adjacent to the source through a final tile adjacent to the
  target. Every rotation must point to the next tile; the final rotation must point into the target. allowed_items is
  your explicit statement of every item you permit on this route. Existing conveyors are reused only when explicitly
  included with the same transport and rotation. The server reports possible side/reused-network items and rejects an
  undeclared mixture or an item the target cannot accept; it never chooses or reroutes your path.
- {"type":"place_conveyor_path","source_x":10,"source_y":20,"target_x":13,"target_y":21,
   "transport":"conveyor","placements":[{"x":11,"y":20,"rotation":0},{"x":12,"y":20,"rotation":1},{"x":12,"y":21,"rotation":0}],
   "allowed_items":["coal"],"preview_token":"token returned by preview","max_cost":100,"reserve_copper":40}
  commits only the identical path approved by preview_conveyor_path. If the preview fails, use its diagnostics to
  revise your own path; do not submit a commit or replace it with independent place actions.
- {"type":"route_liquid","source_x":10,"source_y":20,"target_x":30,"target_y":40,
   "transport":"conduit","max_cost":100,"reserve_copper":40} builds a directed liquid route.
- {"type":"connect_power","node_x":10,"node_y":20,"target_x":30,"target_y":40} links an existing power
  node to a power-capable building when the game's range and connection rules allow it.
- {"type":"remove","x":10,"y":20} removes an owned non-core building with no refund, allowing redesign or
  replacement. A higher-tier building is a separate block, not an in-place upgrade.
- {"type":"set_unit_factory_plan","x":10,"y":20,"unit":"dagger"} selects a plan exposed by that factory.
- {"type":"train_units","x":10,"y":20,"unit":"dagger","count":5} is available only when
  game_mode_variant.id is stockpile_rts_pvp. It selects that factory's compatible plan, deducts the complete standard
  item cost for the requested count from core stock immediately, and trains sequentially at the plan's standard time
  without item belts or power. Each factory has one active queue, while separate factories train in parallel. You
  choose every factory, unit type, count, timing, composition, and follow-up command.
- {"type":"upgrade_units","x":14,"y":20,"from_unit":"dagger","to_unit":"mace","count":5} is available only
  in stockpile_rts_pvp. The coordinate must contain your reconstructor with that exact exposed upgrade pair. It deducts
  the reconstructor's standard item cost for the full count, commits that many existing matching input units, and
  produces one upgraded unit per standard reconstruction time. Choose the branch, tier, quantity, timing, and degree
  of parallel reconstruction from unit statistics, costs, current armies, queues, and observed enemy evidence.
- {"type":"command_units","unit":"dagger","unit_ids":[101,104],"squad_id":"alpha","mode":"attack",
   "target_unit_id":301,"target_x":30,"target_y":40,"target_radius":2,"engagement_radius":14,
   "max_units":10} assigns up to max_units currently produced,
  commandable matching units.
  unit may be a concrete type or "all"; omit unit_ids to select the nearest matching units, or provide IDs from
  friendly_units to form an exact squad. For a moving enemy unit, provide its current ID from enemy_units as
  target_unit_id together with its observed coordinates; the executor follows the live unit even if it moves during
  model latency. For a building, omit target_unit_id and use its coordinate. If an attack target disappears before
  execution, the executor preserves intent by falling back to attack_move toward the observed coordinate instead of
  discarding the command. attack uses Mindustry's native terrain-aware command pathing to pursue and fire on its
  resolved target. A new squad_id fixes the selected membership; later commands using that squad_id with no unit_ids
  reuse surviving members instead of selecting whichever units happen to be nearest. attack_move is a persistent order
  that advances and automatically reacquires hostile units/buildings within engagement_radius; defend holds the chosen
  coordinate and engages enemies entering that radius; rally moves to a staging coordinate and then holds without
  seeking combat; retreat persistently returns members to your core; stop clears the standing order.
  These are execution capabilities, not an automatic combat policy: you choose unit composition, force size, timing,
  staging position, target, concentration or splitting, and when to retreat from current evidence. The automatic core
  unit is deliberately excluded and remains controlled separately by command_core_unit.
- {"type":"configure_item_filter","x":10,"y":20,"item":"coal"} configures an existing sorter or
  inverted-sorter, or selects the item extracted by an unloader. A normal sorter sends the selected item straight and other items sideways; an inverted-sorter
  sends the selected item sideways and other items straight. Placement and configuration are separate actions. A
  sorter is not a magic cleanup: every relevant inbound line must actually pass through it, and each rejected/side
  output needs a valid route or storage destination. Check for bypass lines and blocked side exits after configuring.
- {"type":"command_core_unit","mode":"mine","resource":"copper"} assigns the core unit to mine and return
  that resource. {"type":"command_core_unit","mode":"supply","resource":"sand","target_x":30,"target_y":40}
  repeatedly carries that core-stockpiled item to the selected compatible building using the unit's real item
  capacity and travel time; use it as a recovery path when fixed logistics are blocked, then decide whether a
  permanent unloader route is worth building. Other modes are idle, defend_core, intercept, repair, and rebuild. repair uses the unit's native
  repair AI on surviving damaged allied buildings; rebuild uses its native builder AI and the team's destroyed-block
  plans. You decide when these tasks are worth their opportunity cost and may reassign whenever observations change.
  A core-unit task is persistent and asynchronous. Only one task can exist at a time, so a later command_core_unit in
  the same response immediately replaces an earlier one. Issue one assignment, then use core_defender.task_progress,
  task_age_seconds, delivered item count, cargo, and target existence on later observations to decide whether it has
  completed, is still progressing, or should be replaced.
- {"type":"place","block":name,"x":int,"y":int,"rotation":0..3} performs an exact placement of any block
  present in available_blocks; standard costs, bans, visibility, and placement rules still apply. In stockpile RTS it
  reserves cost and enters rts_construction_queue instead of appearing immediately; one construction per team runs at
  a time in agent-chosen queue order. Removing the coordinate before completion cancels it and refunds reserved cost.

Use observed_events, recent_defense_outcomes, and the current-episode infrastructure backlog to decide whether
rebuilding, replacing with a different design, repairing, or accepting the loss is appropriate.
action_failure_memory aggregates unresolved repeated execution failures across the episode. Treat it as evidence
that unchanged execution is infeasible, not as a prohibition on the strategic objective. It also records successful
requests that produced no state change, such as transferring zero ammunition or reissuing an identical unit task.
Use recent failures to
revise coordinates, endpoints, components, or the objective rather than repeating the same action. Keep actions coherent and wait_seconds
appropriate to the urgency you infer. Keep reasoning_summary short and do not reveal private chain-of-thought.
"""


def system_prompt_for_state(state: dict[str, Any]) -> str:
    """Select one objective frame; only mechanics are shared between game modes."""
    rules = state.get("rules") if isinstance(state.get("rules"), dict) else {}
    variant = state.get("game_mode_variant") if isinstance(state.get("game_mode_variant"), dict) else {}
    if rules.get("pvp") is True and variant.get("id") == "stockpile_rts_pvp":
        return f"{STOCKPILE_RTS_PVP_MODE_PROMPT}\n\n{STOCKPILE_RTS_DECISION_PROMPT}"
    else:
        mode_prompt = PVP_MODE_PROMPT if rules.get("pvp") is True else SURVIVAL_MODE_PROMPT
    return f"{mode_prompt}\n\n{COMMON_GAMEPLAY_PROMPT}"


@dataclass(frozen=True)
class LlmResult:
    decision: dict[str, Any]
    latency_seconds: float
    usage: dict[str, Any]
    raw_text: str
    repair_attempted: bool = False
    validation_skips: tuple[dict[str, Any], ...] = ()


class OpenAICompatibleLlm:
    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        model: str,
        timeout_seconds: float,
        max_tokens: int,
        reasoning_effort: str,
        json_mode: bool,
    ) -> None:
        self.url = f"{base_url.rstrip('/')}/chat/completions"
        self.api_key = api_key
        self.model = model
        self.timeout_seconds = timeout_seconds
        self.max_tokens = max_tokens
        self.reasoning_effort = reasoning_effort
        self.json_mode = json_mode

    def decide(
        self,
        state: dict[str, Any],
        recent_results: list[dict[str, Any]],
        observed_events: list[dict[str, Any]] | None = None,
        prior_strategic_context: dict[str, Any] | None = None,
        action_failure_memory: dict[str, Any] | None = None,
    ) -> LlmResult:
        recent_for_prompt: list[dict[str, Any]] = []
        retained = recent_results[-4:]
        for index, outcome in enumerate(retained):
            compact_outcome = dict(outcome)
            # Route inspection maps are intentionally detailed and can dominate the
            # context. They are task-local geometry for the immediately following
            # decision, not historical memory. Keep summaries for older turns while
            # retaining exact geometry only from the latest outcome.
            if index < len(retained) - 1:
                compact_outcome.pop("evidence_results", None)
            recent_for_prompt.append(compact_outcome)
        user_payload = {
            "current_state": state,
            "observed_events": (observed_events or [])[-20:],
            "recent_action_results": recent_for_prompt,
            "prior_strategic_context": prior_strategic_context or {},
            "action_failure_memory": action_failure_memory or {},
        }
        payload: dict[str, Any] = {
            "model": self.model,
            "temperature": 0.2,
            "max_tokens": self.max_tokens,
            "reasoning_effort": self.reasoning_effort,
            "messages": [
                {"role": "system", "content": system_prompt_for_state(state)},
                {"role": "user", "content": json.dumps(user_payload, ensure_ascii=False)},
            ],
        }
        if self.json_mode:
            payload["response_format"] = {"type": "json_object"}

        started = time.perf_counter()
        response = request_json(
            self.url,
            method="POST",
            payload=payload,
            headers={"Authorization": f"Bearer {self.api_key}"},
            timeout=self.timeout_seconds,
        )
        raw_text = _message_text(response)
        repair_attempted = False
        validation_skips: list[dict[str, Any]] = []
        responses = [response]
        try:
            decision = validate_decision(extract_json_object(raw_text))
        except ValueError as validation_error:
            repair_attempted = True
            repair_payload: dict[str, Any] = {
                "model": self.model,
                "temperature": 0,
                "max_tokens": self.max_tokens,
                "reasoning_effort": self.reasoning_effort,
                "messages": [
                    {
                        "role": "system",
                        "content": (
                            "Output the corrected JSON object immediately. Your first character must be { and your "
                            "last character must be }; do not analyze, explain, restate the input, or use Markdown. "
                            "Convert the previous attempted response into exactly one compact valid JSON object. "
                            "Preserve its intended strategy and action values; do not add commentary. "
                            f"The exact validation failure was: {validation_error}. "
                            "Do not invent action types. Use only: place, remove, build_mine_to_core, "
                            "build_mine_to_target, route_items, route_items_isolated, clear_item_input_network, "
                            "upgrade_input_network, inspect_item_route, preview_conveyor_path, place_conveyor_path, "
                            "route_liquid, connect_power, set_unit_factory_plan, train_units, upgrade_units, command_units, "
                            "configure_item_filter, resupply_turrets, command_core_unit. "
                            "Required top-level keys are reasoning_summary, world_model, strategic_intent, "
                            "actions, and wait_seconds."
                        ),
                    },
                    {"role": "user", "content": raw_text[:20_000]},
                ],
            }
            if self.json_mode:
                repair_payload["response_format"] = {"type": "json_object"}
            repaired_response = request_json(
                self.url,
                method="POST",
                payload=repair_payload,
                headers={"Authorization": f"Bearer {self.api_key}"},
                timeout=self.timeout_seconds,
            )
            responses.append(repaired_response)
            raw_text = _message_text(repaired_response)
            try:
                decision = validate_decision(extract_json_object(raw_text))
            except ValueError as repair_error:
                repaired_object = extract_json_object(raw_text)
                try:
                    decision, validation_skips = validate_decision_with_action_skips(
                        repaired_object
                    )
                except ValueError:
                    preview = raw_text[:1000].replace("\r", " ").replace("\n", " ")
                    raise ValueError(
                        f"{repair_error}; repaired model output preview={preview!r}"
                    ) from repair_error

        latency = time.perf_counter() - started
        usage: dict[str, Any] = {}
        for candidate in responses:
            candidate_usage = candidate.get("usage", {})
            if not isinstance(candidate_usage, dict):
                continue
            for key, value in candidate_usage.items():
                if isinstance(value, (int, float)):
                    usage[key] = usage.get(key, 0) + value
        return LlmResult(
            decision=decision,
            latency_seconds=latency,
            usage=usage,
            raw_text=raw_text,
            repair_attempted=repair_attempted,
            validation_skips=tuple(validation_skips),
        )

def _message_text(response: dict[str, Any]) -> str:
    try:
        content = response["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as error:
        raise ValueError(f"Unexpected chat-completions response: {response}") from error
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        texts = [part.get("text", "") for part in content if isinstance(part, dict)]
        return "".join(texts)
    raise ValueError("The model message content is not text.")
