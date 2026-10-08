import unittest

from mindustry_agent.policy import DecisionError, compact_state, extract_json_object, validate_decision


class PolicyTest(unittest.TestCase):
    def test_keeps_rts_battlefield_lane_facts(self) -> None:
        battlefield = {
            "style": "symmetric_three_lane_showcase",
            "lanes": [{
                "id": "center_technology", "crossing_center_y": 40,
                "ground_characteristic": "shortest direct ground crossing",
            }],
            "air_behavior": "flying units ignore the neutral divider",
        }
        compact = compact_state({
            "game_mode_variant": {"id": "stockpile_rts_pvp"},
            "rts_battlefield": battlefield,
        })
        self.assertEqual(compact["rts_battlefield"], battlefield)

    def test_keeps_rts_execution_and_loss_evidence_including_wiped_squads(self) -> None:
        losses = {
            "self": {"last_10_seconds": 3, "last_30_seconds": 5},
            "opponents": [{"team": "crux", "last_10_seconds": 1}],
        }
        compact = compact_state({
            "game_mode_variant": {"id": "stockpile_rts_pvp"},
            "rts_squads": [{
                "squad_id": "alpha", "member_count": 0,
                "execution_status": "no_surviving_members", "losses_since_order": 6,
            }],
            "recent_combat_losses": losses,
            "recent_unit_command_receipts": [{
                "accepted": True, "commanded_count": 6, "effective_mode": "rally",
            }],
        })
        self.assertEqual(compact["rts_squads"][0]["execution_status"], "no_surviving_members")
        self.assertEqual(compact["recent_combat_losses"], losses)
        self.assertEqual(compact["recent_unit_command_receipts"][0]["commanded_count"], 6)

    def test_extracts_fenced_json(self) -> None:
        parsed = extract_json_object('```json\n{"actions": [], "wait_seconds": 5}\n```')
        self.assertEqual(parsed["wait_seconds"], 5)

    def test_extracts_first_json_object_from_prose(self) -> None:
        parsed = extract_json_object('Result: {"reasoning_summary":"ok","actions":[]} trailing')
        self.assertEqual(parsed["actions"], [])

    def test_accepts_safe_python_dict_literal_from_glm(self) -> None:
        parsed = extract_json_object("Result: {'reasoning_summary': 'ok', 'actions': [], 'wait_seconds': 0.5}")
        self.assertEqual(parsed["reasoning_summary"], "ok")
        self.assertEqual(parsed["actions"], [])

    def test_normalizes_place_action(self) -> None:
        result = validate_decision({
            "reasoning_summary": "start mining",
            "actions": [{"type": "place", "block": "mechanical-drill", "x": 5.0, "y": 6, "rotation": 5}],
            "wait_seconds": 0,
        })
        self.assertEqual(result["actions"][0]["rotation"], 1)
        self.assertEqual(result["wait_seconds"], 0.5)

    def test_normalizes_stockpile_rts_action_references(self) -> None:
        decision = validate_decision({
            "reasoning_summary": "현재 관측 후보를 사용한다.",
            "actions": [
                {
                    "type": "place", "block": "ground-factory",
                    "placement_option_id": "production:3", "rotation": 0,
                },
                {
                    "type": "train_units", "facility_id": "factory:10:20",
                    "unit": "dagger", "count": 2,
                },
                {
                    "type": "upgrade_units", "facility_id": "reconstructor:14:20",
                    "from_unit": "dagger", "to_unit": "mace", "count": 1,
                },
            ],
            "wait_seconds": 0.5,
        })
        self.assertEqual(decision["actions"][0]["placement_option_id"], "production:3")
        self.assertNotIn("x", decision["actions"][0])
        self.assertEqual(decision["actions"][1]["facility_id"], "factory:10:20")
        self.assertEqual(
            decision["actions"][2]["facility_id"], "reconstructor:14:20"
        )

    def test_coerces_and_clamps_harmless_model_number_representation_errors(self) -> None:
        result = validate_decision({
            "wait_seconds": "0.2",
            "actions": [{
                "type": "resupply_turrets", "ammo": "copper",
                "max_items": "20.4", "reserve_copper": 5000.2,
                "below_fraction": "1.4",
            }],
        })
        self.assertEqual(result["wait_seconds"], 0.5)
        self.assertEqual(result["actions"][0]["max_items"], 20)
        self.assertEqual(result["actions"][0]["reserve_copper"], 1000)
        self.assertEqual(result["actions"][0]["below_fraction"], 1.0)

    def test_preserves_agent_authored_world_model_and_intent(self) -> None:
        result = validate_decision({
            "reasoning_summary": "broaden options",
            "world_model": {
                "capabilities": ["stable copper logistics"],
                "constraints": ["no transformed materials"],
                "opportunities": ["catalog shows reachable production choices"],
                "uncertainties": ["route safety"],
            },
            "strategic_intent": {
                "objective": "test a self-selected production capability",
                "rationale": "it may improve future defense choices",
                "success_evidence": ["output reaches storage"],
                "revision_triggers": ["route repeatedly fails"],
            },
            "actions": [],
        })
        self.assertEqual(result["world_model"]["constraints"], ["no transformed materials"])
        self.assertEqual(
            result["strategic_intent"]["objective"],
            "test a self-selected production capability",
        )

    def test_metadata_shape_errors_do_not_discard_valid_actions(self) -> None:
        result = validate_decision({
            "reasoning_summary": "keep acting despite malformed optional metadata",
            "world_model": "unknown",
            "strategic_intent": "supply factory",
            "actions": [{"type": "command_core_unit", "mode": "mine", "resource": "copper"}],
            "wait_seconds": 0.5,
        })
        self.assertEqual(result["world_model"]["constraints"], [])
        self.assertEqual(result["strategic_intent"]["objective"], "")
        self.assertEqual(result["actions"][0]["type"], "command_core_unit")

    def test_rejects_unsupported_action(self) -> None:
        with self.assertRaises(DecisionError):
            validate_decision({"actions": [{"type": "launch_wave"}]})

    def test_normalizes_strategic_macros(self) -> None:
        result = validate_decision({"actions": [
            {"type": "build_mine_to_core", "resource": "copper", "drill": "mechanical-drill",
             "transport": "conveyor", "max_cost": 75, "reserve_copper": 40},
        ]})
        self.assertEqual(result["actions"][0]["resource"], "copper")
        self.assertEqual(result["actions"][0]["drill"], "mechanical-drill")

    def test_rejects_automatic_defense_placement_macro(self) -> None:
        with self.assertRaises(DecisionError):
            validate_decision({"actions": [{
                "type": "build_core_defense", "turret": "duo", "ammo": "copper",
                "wall": "copper-wall", "max_cost": 190, "reserve_copper": 35,
                "walls": 2, "ammo_items": 25, "turrets": 2,
            }]})

    def test_normalizes_open_ended_execution_actions(self) -> None:
        result = validate_decision({"actions": [
            {
                "type": "build_mine_to_target", "resource": "coal",
                "drill": "pneumatic-drill", "transport": "titanium-conveyor",
                "target_x": 12, "target_y": 14, "max_cost": 120, "reserve_copper": 17,
            },
            {
                "type": "route_items", "source_x": 1, "source_y": 2,
                "target_x": 3, "target_y": 4, "transport": "conveyor",
                "max_cost": 61, "reserve_copper": 11,
            },
            {
                "type": "route_items_isolated", "resource": "coal", "source_x": 5, "source_y": 6,
                "target_x": 7, "target_y": 8, "transport": "conveyor",
                "max_cost": 62, "reserve_copper": 12,
            },
            {
                "type": "upgrade_input_network", "target_x": 7, "target_y": 8,
                "transport": "titanium-conveyor", "max_cost": 300, "reserve_copper": 20,
            },
            {"type": "connect_power", "node_x": 5, "node_y": 6, "target_x": 7, "target_y": 8},
            {"type": "remove", "x": 9, "y": 10},
            {"type": "set_unit_factory_plan", "x": 11, "y": 12, "unit": "dagger"},
            {"type": "configure_item_filter", "x": 13, "y": 14, "item": "coal"},
            {
                "type": "place_conveyor_path", "source_x": 1, "source_y": 1,
                "target_x": 4, "target_y": 2, "transport": "conveyor",
                "placements": [
                    {"x": 2, "y": 1, "rotation": 0},
                    {"x": 3, "y": 1, "rotation": 1},
                    {"x": 3, "y": 2, "rotation": 0},
                ],
                "allowed_items": ["coal"], "preview_token": "approved-1",
                "max_cost": 44, "reserve_copper": 9,
            },
            {
                "type": "command_units", "unit": "dagger", "mode": "attack",
                "unit_ids": [101, 102, 101], "target_unit_id": 301,
                "target_x": 30, "target_y": 40,
                "target_radius": 3, "max_units": 12,
            },
        ]})
        self.assertEqual(result["actions"][0]["drill"], "pneumatic-drill")
        self.assertEqual(result["actions"][1]["type"], "route_items")
        self.assertEqual(result["actions"][2]["type"], "route_items_isolated")
        self.assertEqual(result["actions"][2]["resource"], "coal")
        self.assertEqual(result["actions"][3]["type"], "upgrade_input_network")
        self.assertEqual(result["actions"][3]["transport"], "titanium-conveyor")
        self.assertEqual(result["actions"][4]["type"], "connect_power")
        self.assertEqual(result["actions"][5], {"type": "remove", "x": 9, "y": 10})
        self.assertEqual(result["actions"][6]["unit"], "dagger")
        self.assertEqual(result["actions"][7], {
            "type": "configure_item_filter", "x": 13, "y": 14, "item": "coal"
        })
        self.assertEqual(result["actions"][8]["placements"][1], {"x": 3, "y": 1, "rotation": 1})
        self.assertEqual(result["actions"][8]["allowed_items"], ["coal"])
        self.assertEqual(result["actions"][8]["preview_token"], "approved-1")
        self.assertEqual(result["actions"][9], {
            "type": "command_units", "unit": "dagger", "mode": "attack",
            "unit_ids": [101, 102], "target_unit_id": 301,
            "target_x": 30, "target_y": 40,
            "target_radius": 3, "max_units": 12,
        })

    def test_requires_resource_for_isolated_item_route(self) -> None:
        with self.assertRaises(DecisionError):
            validate_decision({"actions": [{
                "type": "route_items_isolated", "source_x": 5, "source_y": 6,
                "target_x": 7, "target_y": 8, "transport": "conveyor",
                "max_cost": 62, "reserve_copper": 12,
            }]})

    def test_requires_target_for_offensive_unit_command(self) -> None:
        with self.assertRaises(DecisionError):
            validate_decision({"actions": [{
                "type": "command_units", "unit": "all", "mode": "attack", "max_units": 20,
            }]})

    def test_compact_enemy_units_keep_trackable_ids(self) -> None:
        compact = compact_state({"enemy_units": [{
            "id": 301, "type": "dagger", "team": "crux", "x": 30.5, "y": 40.5,
            "health": 110, "max_health": 140,
        }]})
        self.assertEqual(compact["enemy_units"][0], {
            "id": 301, "type": "dagger", "team": "crux", "x": 30.5, "y": 40.5,
            "health": 110, "max_health": 140,
        })

    def test_allows_retreat_without_agent_target_coordinate(self) -> None:
        result = validate_decision({"actions": [{
            "type": "command_units", "unit": "all", "mode": "retreat", "max_units": 30,
        }]})
        self.assertEqual(result["actions"][0], {
            "type": "command_units", "unit": "all", "mode": "retreat", "max_units": 30,
        })

    def test_normalizes_persistent_squad_defense_order(self) -> None:
        result = validate_decision({"actions": [{
            "type": "command_units", "unit": "all", "squad_id": "control-west",
            "mode": "defend", "target_x": 49, "target_y": 125,
            "target_radius": 2, "engagement_radius": 16, "max_units": 8,
        }]})
        self.assertEqual(result["actions"][0], {
            "type": "command_units", "unit": "all", "squad_id": "control-west",
            "mode": "defend", "target_x": 49, "target_y": 125,
            "target_radius": 2, "engagement_radius": 16, "max_units": 8,
        })

    def test_rejects_empty_explicit_conveyor_path(self) -> None:
        with self.assertRaises(DecisionError):
            validate_decision({"actions": [{
                "type": "place_conveyor_path", "source_x": 1, "source_y": 1,
                "target_x": 2, "target_y": 2, "placements": [],
            }]})

    def test_normalizes_route_inspection_and_preview(self) -> None:
        result = validate_decision({"actions": [
            {
                "type": "inspect_item_route", "source_x": 1, "source_y": 2,
                "target_x": 8, "target_y": 9, "transport": "conveyor", "margin": 7,
            },
            {
                "type": "preview_conveyor_path", "source_x": 1, "source_y": 2,
                "target_x": 8, "target_y": 9, "transport": "conveyor",
                "placements": [{"x": 2, "y": 2, "rotation": 0}],
                "allowed_items": ["coal", "coal"], "max_cost": 20, "reserve_copper": 5,
            },
        ]})
        self.assertEqual(result["actions"][0]["margin"], 7)
        self.assertEqual(result["actions"][1]["allowed_items"], ["coal"])
        self.assertNotIn("preview_token", result["actions"][1])

    def test_clamps_unsafe_macro_bounds(self) -> None:
        result = validate_decision({"actions": [{
            "type": "build_mine_to_core", "resource": "copper",
            "drill": "mechanical-drill", "transport": "conveyor",
            "max_cost": 0, "reserve_copper": -30,
        }]})["actions"][0]
        self.assertEqual(result["max_cost"], 1)
        self.assertEqual(result["reserve_copper"], 0)

    def test_requires_agent_selected_strategic_parameters(self) -> None:
        with self.assertRaises(DecisionError):
            validate_decision({"actions": [{
                "type": "build_mine_to_core", "resource": "lead", "max_cost": 80,
            }]})

    def test_normalizes_llm_resupply_action(self) -> None:
        result = validate_decision({"actions": [{
            "type": "resupply_turrets", "ammo": "copper", "max_items": 20,
            "reserve_copper": 40, "below_fraction": 0.4,
        }]})
        self.assertEqual(result["actions"][0]["type"], "resupply_turrets")
        self.assertEqual(result["actions"][0]["below_fraction"], 0.4)

    def test_normalizes_core_unit_task(self) -> None:
        result = validate_decision({"actions": [
            {"type": "command_core_unit", "mode": "mine", "resource": "copper"},
            {"type": "command_core_unit", "mode": "supply", "resource": "sand", "target_x": 8, "target_y": 9},
        ]})
        self.assertEqual(result["actions"][0], {
            "type": "command_core_unit", "mode": "mine", "resource": "copper"
        })
        self.assertEqual(result["actions"][1], {
            "type": "command_core_unit", "mode": "supply", "resource": "sand",
            "target_x": 8, "target_y": 9,
        })

    def test_allows_llm_selected_repair_and_rebuild_tasks(self) -> None:
        result = validate_decision({"actions": [
            {"type": "command_core_unit", "mode": "repair"},
            {"type": "command_core_unit", "mode": "rebuild"},
        ]})
        self.assertEqual(result["actions"][0]["mode"], "repair")
        self.assertEqual(result["actions"][1]["mode"], "rebuild")

    def test_normalizes_explicit_input_network_clear(self) -> None:
        result = validate_decision({
            "actions": [{
                "type": "clear_item_input_network",
                "target_x": 30,
                "target_y": 40,
                "max_segments": 160,
            }]
        })["actions"][0]
        self.assertEqual(result, {
            "type": "clear_item_input_network",
            "target_x": 30,
            "target_y": 40,
            "max_segments": 160,
        })

    def test_normalizes_stockpile_rts_training_order(self) -> None:
        result = validate_decision({
            "actions": [{
                "type": "train_units", "x": 20, "y": 30,
                "unit": "dagger", "count": 12,
                "rally_x": 35, "rally_y": 30,
                "rally_radius": 5, "squad_id": "north_alpha",
            }]
        })["actions"][0]
        self.assertEqual(result, {
            "type": "train_units", "x": 20, "y": 30,
            "unit": "dagger", "count": 12,
            "rally_x": 35, "rally_y": 30,
            "rally_radius": 5, "squad_id": "north_alpha",
        })

    def test_training_squad_requires_complete_rally(self) -> None:
        with self.assertRaises(DecisionError):
            validate_decision({"actions": [{
                "type": "train_units", "x": 20, "y": 30,
                "unit": "dagger", "count": 2, "squad_id": "alpha",
            }]})
        with self.assertRaises(DecisionError):
            validate_decision({"actions": [{
                "type": "train_units", "x": 20, "y": 30,
                "unit": "dagger", "count": 2, "rally_x": 35,
            }]})

    def test_normalizes_stockpile_rts_upgrade_order(self) -> None:
        result = validate_decision({
            "actions": [{
                "type": "upgrade_units", "x": 24, "y": 30,
                "from_unit": "dagger", "to_unit": "mace", "count": 6,
            }]
        })["actions"][0]
        self.assertEqual(result, {
            "type": "upgrade_units", "x": 24, "y": 30,
            "from_unit": "dagger", "to_unit": "mace", "count": 6,
        })

    def test_requires_resource_for_mining_task(self) -> None:
        with self.assertRaises(DecisionError):
            validate_decision({"actions": [{"type": "command_core_unit", "mode": "mine"}]})

    def test_compacts_ore_samples(self) -> None:
        state = {"nearby_ores": [{"item": "copper", "x": i, "y": 0} for i in range(100)]}
        compact = compact_state(state)
        self.assertEqual(len(compact["nearby_ores_by_item"]["copper"]), 16)
        self.assertNotIn("nearby_ores", compact)

    def test_compacts_placement_samples_and_building_fields(self) -> None:
        state = {
            "buildings": [{
                "block": "duo", "x": 1, "y": 2, "rotation": 0,
                "health": 10, "max_health": 250, "enabled": True,
            }],
            "valid_placement_samples": {
                "duo": [{"x": i, "y": 0, "distance2_to_core": i, "extra": True} for i in range(100)]
            },
        }
        compact = compact_state(state)
        self.assertEqual(len(compact["valid_placement_samples"]["duo"]), 4)
        self.assertNotIn("enabled", compact["buildings"][0])
        self.assertEqual(compact["buildings"][0]["max_health"], 250)
        self.assertEqual(compact["maintenance_summary"]["damaged_count"], 1)
        self.assertEqual(compact["maintenance_summary"]["most_damaged"][0]["health_fraction"], 0.04)

    def test_keeps_sorter_filter_state(self) -> None:
        compact = compact_state({"buildings": [{
            "block": "sorter", "x": 4, "y": 5, "rotation": 1,
            "item_filter": "coal",
            "filter_behavior": "selected item continues straight; other items exit to the sides",
        }]})
        self.assertEqual(compact["buildings"][0]["item_filter"], "coal")

    def test_compacts_pvp_state_relative_to_the_authenticated_team(self) -> None:
        state = {
            "self_team": {"name": "crux", "id": 1, "color": "#ff0000"},
            "rules": {"pvp": True, "pvp_auto_pause": False, "pause_disabled": True},
            "pvp_fairness": {
                "applied": True, "resource_tile_counts_equal": True,
                "resource_mismatch_pairs_after_copy": 0,
            },
            "core": {"block": "core-shard", "x": 40, "y": 12, "health": 1000},
            "cores": [
                {"block": "core-shard", "x": 40, "y": 12, "health": 1000},
                {"block": "core-shard", "x": 44, "y": 12, "health": 800},
            ],
            "friendly_units": [{
                "id": 101, "type": "dagger", "x": 35, "y": 12, "health": 120, "max_health": 140,
                "commandable": True, "unit_command": "move",
                "command_target": {"kind": "building", "x": 4, "y": 12},
            }],
            "enemy_teams": [{
                "team": {"name": "sharded", "id": 0, "color": "#00aaff"},
                "core": {"block": "core-shard", "x": 4, "y": 12, "health": 900, "max_health": 1000},
                "cores": [
                    {"block": "core-shard", "x": 4, "y": 12, "health": 900, "max_health": 1000},
                    {"block": "core-shard", "x": 8, "y": 12, "health": 700, "max_health": 1000},
                ],
                "buildings": [{
                    "block": "duo", "x": 8, "y": 12, "rotation": 2,
                    "health": 180, "max_health": 250, "items": {"copper": 10},
                    "status": "active",
                }],
                "units": [{
                    "type": "dagger", "x": 9, "y": 12,
                    "health": 120, "max_health": 140,
                }],
            }],
        }
        compact = compact_state(state)
        self.assertEqual(compact["self_team"]["name"], "crux")
        self.assertTrue(compact["rules"]["pvp"])
        self.assertTrue(compact["pvp_fairness"]["resource_tile_counts_equal"])
        self.assertEqual([core["x"] for core in compact["cores"]], [40, 44])
        self.assertEqual(compact["pvp_objective"]["victory_condition"], "destroy_every_enemy_core")
        self.assertEqual(compact["pvp_objective"]["own_cores_remaining"], 2)
        self.assertEqual(compact["pvp_objective"]["enemy_cores_remaining"], 2)
        self.assertEqual(compact["pvp_objective"]["enemy_cores"][0]["distance_tiles"], 36.0)
        core_progress = compact["pvp_objective"]["enemy_core_health_progress"]
        self.assertEqual(core_progress["current_total"], 1600.0)
        self.assertEqual(core_progress["maximum_total"], 2000.0)
        self.assertAlmostEqual(core_progress["damage_fraction"], 0.2)
        self.assertTrue(core_progress["any_direct_victory_damage_observed"])
        self.assertEqual(compact["pvp_objective"]["commandable_friendly_units"], 1)
        enemy = compact["enemy_teams"][0]
        self.assertEqual(enemy["team"]["name"], "sharded")
        self.assertEqual(enemy["core"]["x"], 4)
        self.assertEqual([core["x"] for core in enemy["cores"]], [4, 8])
        self.assertEqual(enemy["building_table"]["rows"][0][0], "duo")
        self.assertEqual(enemy["units"][0]["type"], "dagger")
        self.assertEqual(compact["friendly_units"][0]["command_target"]["x"], 4)
        self.assertEqual(compact["friendly_units"][0]["id"], 101)

    def test_preserves_stockpile_rts_mode_and_training_queues(self) -> None:
        state = {
            "rules": {"pvp": True},
            "game_mode_variant": {
                "id": "stockpile_rts_pvp", "stockpile_rts": True,
                "decision_refresh_seconds": 0.5,
            },
            "self_team": {"name": "sharded"},
            "rts_control_points": [
                {"id": "north_industry", "owner": "sharded"},
                {"id": "center_technology", "owner": "crux"},
                {"id": "south_advanced", "owner": "neutral"},
            ],
            "rts_control_benefits": {
                "production_and_upgrade_speed_bonus_per_owned_point_fraction": 0.1,
                "friendly_unit_repair_max_health_fraction_per_second_in_owned_radius": 0.01,
                "current_production_and_upgrade_speed_multiplier": 1.1,
                "teams": {
                    "sharded": {"owned_points": 1, "production_and_upgrade_speed_multiplier": 1.1},
                    "crux": {"owned_points": 1, "production_and_upgrade_speed_multiplier": 1.1},
                },
            },
            "core": {"items": {"lead": 100, "silicon": 80}},
            "buildings": [{
                "block": "ground-factory", "x": 20, "y": 30,
                "status": "noInput", "stall_reason": "noInput",
            }, {
                "block": "additive-reconstructor", "x": 50, "y": 30,
                "status": "noPower", "stall_reason": "noPower",
            }],
            "friendly_units": [
                {"id": 1, "type": "dagger", "commandable": True},
                {"id": 2, "type": "dagger", "commandable": False},
            ],
            "production_summary": {"stalled_buildings": [{"block": "ground-factory"}]},
            "logistics_analysis": {"transport_segment_count": 99},
            "power_networks": [{"has_consumers_without_generation": True}],
            "available_blocks": [{
                "name": "ground-factory", "category": "units", "affordable_now": True,
                "cost": {"lead": 20},
                "unit_plans": [{
                    "unit": "dagger", "item_cost": {"lead": 10, "silicon": 10},
                    "time_seconds": 15.0,
                    "unit_stats": {
                        "health": 150, "estimated_dps": 18.0,
                        "weapons": [
                            {"name": "dagger-gun", "estimated_dps": 9.0},
                            {"name": "dagger-gun", "estimated_dps": 9.0},
                        ],
                    },
                }],
            }, {
                "name": "additive-reconstructor", "category": "units", "affordable_now": True,
                "cost": {"lead": 20},
                "unit_upgrades": [{
                    "from_unit": "dagger", "to_unit": "mace",
                    "item_cost": {"lead": 30, "silicon": 20}, "time_seconds": 10.0,
                    "from_stats": {"health": 150, "estimated_dps": 18.0},
                    "to_stats": {"health": 500, "estimated_dps": 42.0},
                }],
            }, {
                "name": "combustion-generator", "category": "power", "affordable_now": True,
            }],
            "valid_placement_samples": {
                "ground-factory": [
                    {"x": 20, "y": 30, "distance_band": 0, "direction_sector": 0},
                    {"x": 50, "y": 30, "distance_band": 3, "direction_sector": 0},
                ],
                "air-factory": [
                    {"x": 20, "y": 30, "distance_band": 0, "direction_sector": 0},
                ],
                "additive-reconstructor": [
                    {"x": 50, "y": 30, "distance_band": 3, "direction_sector": 0},
                ],
                "combustion-generator": [{"x": 21, "y": 30}],
            },
            "action_contract": {
                "endpoint": "POST /v1/action", "max_actions": 32,
                "shape": {"actions": [{"type": "route_items"}]},
            },
            "rts_training_queues": [{
                "factory": {"x": 20, "y": 30}, "unit": "dagger",
                "requested": 5, "completed": 2, "remaining": 3,
                "status": "training", "seconds_until_next_unit": 4.5,
            }],
            "rts_upgrade_queues": [{
                "reconstructor": {"x": 50, "y": 30},
                "from_unit": "dagger", "to_unit": "mace",
                "requested": 2, "completed": 1, "remaining": 1,
                "status": "upgrading", "seconds_until_next_unit": 2.0,
            }],
            "rts_construction_queue": [{
                "block": "ground-factory", "x": 60, "y": 30,
                "status": "queued", "seconds_until_completion": 3.0,
            }],
        }
        compact = compact_state(state)
        self.assertEqual(compact["game_mode_variant"]["id"], "stockpile_rts_pvp")
        self.assertEqual(
            compact["pvp_objective"]["victory_condition"],
            "destroy_every_enemy_core",
        )
        benefits = compact["pvp_objective"]["control_point_benefits"]
        self.assertFalse(benefits["direct_victory_effect"])
        self.assertEqual(benefits["owned_by_self"], 1)
        self.assertEqual(benefits["most_owned_by_one_opponent"], 1)
        self.assertEqual(benefits["current_production_and_upgrade_speed_multiplier"], 1.1)
        self.assertEqual(
            benefits["friendly_unit_repair_max_health_fraction_per_second_in_owned_radius"], 0.01
        )
        self.assertEqual(compact["rts_training_queues"][0]["remaining"], 3)
        self.assertEqual(compact["rts_upgrade_queues"][0]["remaining"], 1)
        self.assertEqual(compact["rts_construction_queue"][0]["status"], "queued")
        self.assertNotIn("production_networks", compact)
        self.assertNotIn("logistics_analysis", compact)
        self.assertNotIn("power_networks", compact)
        self.assertNotIn("status", compact["buildings"][0])
        self.assertEqual(compact["available_blocks"], [])
        self.assertIn("offensive_production", compact["capability_catalog"]["meaning"])
        self.assertNotIn("valid_placement_samples", compact)
        placement_table = compact["rts_production_placement_options"]
        placement_options = [
            dict(zip(placement_table["columns"], row)) for row in placement_table["rows"]
        ]
        self.assertEqual(sorted(option["x"] for option in placement_options), [20, 50])
        by_x = {option["x"]: option for option in placement_options}
        self.assertEqual(by_x[20]["compatible_structures"], ["ground-factory"])
        self.assertEqual(
            set(by_x[50]["compatible_structures"]),
            {"ground-factory", "additive-reconstructor"},
        )
        self.assertEqual({option["distance_band"] for option in placement_options}, {0, 3})
        self.assertTrue(all(option["placement_option_id"].startswith("production:") for option in placement_options))
        self.assertIn("no preference", compact["rts_production_placement_options"]["ordering"])
        action_types = {
            action["type"] for action in compact["action_contract"]["shape"]["actions"]
        }
        self.assertEqual(
            action_types,
            {
                "place", "remove", "train_units", "upgrade_units", "command_units",
                "resupply_turrets", "command_core_unit",
            },
        )
        factory = compact["offensive_production"]["factories"][0]
        self.assertEqual(factory["existing_instances"][0]["facility_id"], "factory:20:30")
        self.assertTrue(factory["existing_instances"][0]["native_input_and_power_status_ignored"])
        self.assertFalse(factory["existing_instances"][0]["available_for_new_order"])
        self.assertEqual(factory["existing_instances"][0]["active_queue"]["remaining"], 3)
        reconstructor = compact["offensive_production"]["reconstructors"][0]
        self.assertEqual(
            reconstructor["existing_instances"][0]["facility_id"],
            "reconstructor:50:30",
        )
        self.assertEqual(factory["plans"][0]["maximum_trainable_from_current_stockpile"], 8)
        self.assertNotIn("unit_stats", factory["plans"][0])
        reconstructor = compact["offensive_production"]["reconstructors"][0]
        self.assertEqual(reconstructor["upgrades"][0]["to_unit"], "mace")
        self.assertFalse(reconstructor["existing_instances"][0]["available_for_new_order"])
        self.assertEqual(reconstructor["upgrades"][0]["available_input_units_now"], 2)
        self.assertEqual(
            reconstructor["upgrades"][0]["maximum_startable_now_from_resources_and_units"], 2
        )
        self.assertNotIn("to_stats", reconstructor["upgrades"][0])
        self.assertEqual(compact["offensive_production"]["current_unit_counts_by_type"], {"dagger": 2})
        self.assertEqual(
            compact["offensive_production"]["commandable_unit_counts_by_type"], {"dagger": 1}
        )
        catalog = compact["offensive_production"]["unit_catalog"]
        unit_rows = {
            row[0]: dict(zip(catalog["stat_columns"], row)) for row in catalog["rows"]
        }
        self.assertEqual(unit_rows["mace"]["estimated_dps"], 42.0)
        self.assertEqual(unit_rows["dagger"]["weapon_traits"]["mounts"], 2)

    def test_stockpile_rts_exposes_unranked_turret_wall_and_ammo_facts(self) -> None:
        state = {
            "episode_id": "defense-test",
            "rules": {"pvp": True},
            "game_mode_variant": {"id": "stockpile_rts_pvp"},
            "core": {"items": {"copper": 500, "lead": 400}},
            "buildings": [
                {"block": "duo", "x": 12, "y": 10, "health": 250, "max_health": 250},
                {"block": "copper-wall", "x": 13, "y": 10, "health": 320, "max_health": 320},
            ],
            "available_blocks": [{
                "name": "duo", "category": "turret", "defense_kind": "turret",
                "size": 1, "max_health": 250, "cost": {"copper": 35},
                "build_time_seconds": 0.5, "affordable_now": True,
                "combat": {
                    "range_tiles": 13.75, "minimum_range_tiles": 0,
                    "targets_air": False, "targets_ground": True,
                    "base_shots_per_second": 3.0,
                    "ammunition": [{
                        "resource_type": "item", "resource": "copper",
                        "estimated_dps": 18.0, "direct_damage": 9.0,
                        "hits_air": False, "hits_ground": True,
                    }],
                },
            }, {
                "name": "copper-wall", "category": "defense", "defense_kind": "wall",
                "size": 1, "max_health": 320, "cost": {"copper": 6},
                "build_time_seconds": 0.1, "affordable_now": True,
            }, {
                "name": "arc", "category": "turret", "defense_kind": "turret",
                "cost": {"copper": 50}, "affordable_now": True,
                "combat": {"ammunition": [{"resource_type": "power", "resource": "power"}]},
            }],
            "defense_analysis": {
                "totals": {"turrets": 1, "walls": 1},
                "turrets": [{"block": "duo", "x": 12, "y": 10, "ammo_fraction": 0.2}],
                "current_enemy_comparison": {"enemy_units": 3, "ground_health": 450},
                "approach_directions": [{
                    "direction_id": "enemy-east", "spawn": {"x": 80, "y": 10},
                    "ground_route_reachable": True, "ground_route_length_tiles": 70,
                    "ground_entry_reference": {"x": 34, "y": 10},
                    "air_vector_from_core": {"dx": 70, "dy": 0},
                    "straight_line_distance_tiles": 70,
                    "placement_sample_contract": "unranked turret coordinates",
                    "wall_placement_sample_contract": "unranked wall coordinates",
                    "valid_turret_placement_samples": {
                        "duo": [{
                            "x": 20, "y": 10, "ground_route_intervals": [[8, 20, 13]],
                            "air_route_distance_intervals": [],
                        }],
                        "arc": [{"x": 21, "y": 10}],
                    },
                    "valid_wall_placement_samples": {
                        "copper-wall": [{"x": 18, "y": 10, "steps_from_core": 8}],
                    },
                }],
            },
        }
        compact = compact_state(state)
        self.assertEqual(
            [row[0] for row in compact["rts_defense"]["structure_catalog"]["rows"]],
            ["copper-wall", "duo"],
        )
        self.assertNotIn("arc", [block["name"] for block in compact["available_blocks"]])
        self.assertEqual(compact["rts_defense"]["existing_counts"], {"duo": 1, "copper-wall": 1})
        approach = compact["rts_defense"]["approach_directions"][0]
        self.assertEqual(approach["valid_turret_placement_samples"]["duo"]["rows"][0][0:2], [20, 10])
        self.assertNotIn("arc", approach["valid_turret_placement_samples"])
        self.assertEqual(
            approach["valid_wall_placement_samples"]["copper-wall"]["rows"][0],
            [18, 10, 8],
        )
        self.assertEqual(compact["immediate_evidence_digest"]["defense_totals"]["walls"], 1)

    def test_exposes_attack_unit_production_requirements_without_a_build_order(self) -> None:
        state = {
            "rules": {"pvp": True},
            "core": {"x": 10, "y": 10, "items": {"copper": 60, "lead": 100}},
            "nearby_ores": [
                {"item": "lead", "x": 2, "y": 2},
                {"item": "coal", "x": 3, "y": 3},
                {"item": "sand", "x": 4, "y": 4},
            ],
            "production_summary": {"item_producer_counts": {"silicon": 1}},
            "buildings": [{
                "block": "ground-factory", "x": 12, "y": 12, "status": "noInput",
                "stall_reason": "noInput", "configured_unit": "dagger", "unit_build_progress": 0.25,
                "items": {"lead": 2}, "power": {"satisfaction": 1.0},
            }],
            "available_blocks": [{
                "name": "ground-factory", "category": "units",
                "cost": {"copper": 50, "lead": 120, "silicon": 80},
                "affordable_now": False,
                "inputs": [{"resource": "power", "usage_per_second": 72.0, "optional": False}],
                "unit_plans": [{
                    "unit": "dagger", "item_cost": {"silicon": 10, "lead": 10},
                    "time_seconds": 15.0,
                }],
            }],
        }
        offensive = compact_state(state)["offensive_production"]
        factory = offensive["factories"][0]
        self.assertEqual(factory["factory"], "ground-factory")
        self.assertEqual(factory["construction_shortfall_now"], {"lead": 20.0, "silicon": 80.0})
        self.assertEqual(factory["required_power_per_second_when_producing"], 72.0)
        self.assertEqual(factory["plans"][0]["item_cost_per_unit"]["silicon"], 10)
        self.assertEqual(factory["existing_instances"][0]["configured_unit"], "dagger")
        self.assertEqual(
            factory["existing_instances"][0]["configured_plan_item_shortfall"],
            {"silicon": 10.0, "lead": 8.0},
        )
        self.assertEqual(offensive["required_resource_evidence"]["silicon"]["observed_producer_count"], 1)

    def test_keeps_game_semantics(self) -> None:
        state = {
            "core": {"x": 5, "y": 5, "size": 3, "footprint": [{"x": 5, "y": 5}]},
            "buildings": [{"block": "mechanical-drill", "x": 2, "y": 3, "produces": "copper", "efficiency": 1}],
            "valid_placement_samples": {"mechanical-drill": [{"x": 1, "y": 2, "produces": "copper", "ore_tiles": 4}]},
            "available_blocks": [{"name": "duo", "role": "turret", "affordable_now": True}],
            "action_contract": {"endpoint": "POST /v1/action"},
            "defense_supply": {"turrets_total": 1, "empty_turrets": 1},
            "defense_analysis": {
                "totals": {"turrets": 1, "ground_targeting_turrets": 1},
                "approach_directions": [{
                    "direction_id": "spawn_9_9_entry_1:1",
                    "spawn": {"x": 9, "y": 9},
                    "ground_route_reachable": True,
                    "ground_route_length_tiles": 4,
                    "ground_route": {
                        "ordering": "core_outward_to_spawn",
                        "columns": ["steps_from_core", "x", "y"],
                        "rows": [[0, 5, 5], [1, 6, 5], [2, 6, 6], [3, 7, 6], [4, 7, 7]],
                    },
                    "engagement_interval_columns": [
                        "start_steps_from_core", "end_steps_from_core", "sampled_tiles"
                    ],
                    "existing_turret_route_engagements": [{
                        "block": "duo", "x": 5, "y": 6,
                        "ground_route_intervals": [[0, 3, 4]],
                        "air_route_distance_intervals": [[0, 4, 5]],
                    }],
                    "placement_sample_contract": "unranked",
                    "valid_turret_placement_samples": {
                        "duo": [{
                            "x": 7, "y": 5,
                            "ground_route_intervals": [[0, 4, 5]],
                            "air_route_distance_intervals": [[0, 3, 4]],
                        }],
                    },
                }],
            },
            "logistics_analysis": {
                "open_output_segments": [{
                    "block": "conveyor", "x": 3, "y": 3, "rotation": 0,
                    "flow_fact": "output_does_not_reach_an_owned_building",
                }],
                "transport_components": [{
                    "component_id": "transport@3,3", "segment_count": 2,
                    "bounds": {"min_x": 3, "max_x": 4, "min_y": 3, "max_y": 3},
                    "adjacent_producer_items": ["coal"], "observed_carried_items": ["coal"],
                    "terminal_outputs": [], "open_outputs": [{"x": 4, "y": 3}],
                    "has_two_segment_cycle": False,
                }],
                "transport_topology": [{
                    "block": "conveyor", "x": 6, "y": 3, "rotation": 0,
                    "items": ["copper"], "output_to": {"block": "core-shard", "x": 7, "y": 3},
                }],
                "conveyor_planning_area": {
                    "bounds": {"min_x": 0, "max_x": 10, "min_y": 0, "max_y": 10},
                    "permanent_terrain_blocked_rows": [{"y": 4, "blocked_x_ranges_inclusive": [[1, 3]]}],
                    "owned_non_transport_buildings": [{"block": "duo", "x": 5, "y": 5, "size": 1}],
                },
                "selective_sink_bottlenecks": [{
                    "network_id": "silicon-smelter@8,8",
                    "sink": {"block": "silicon-smelter", "x": 8, "y": 8},
                    "diagnosis": "incompatible_item_contamination",
                    "accepted_items": ["coal", "sand"],
                    "observed_items_in_input_network": ["coal", "copper"],
                    "incompatible_items": ["copper"],
                    "saturated_segments": [{"x": 7, "y": 8}],
                    "transport_blocks": {"conveyor": 4},
                    "minimum_nominal_transport_throughput_per_second": 6.5,
                    "observed_upstream_output_rate_per_second": {"coal": 0.7},
                    "sink_nominal_item_demand_per_second": {"coal": 1.5, "sand": 3.0},
                    "direct_inputs": [{"block": "conveyor", "x": 7, "y": 8}],
                    "upstream_producers": [{"block": "mechanical-drill", "x": 6, "y": 8, "produces": "coal"}],
                    "contamination_sources": [{
                        "block": "mechanical-drill", "x": 6, "y": 9, "produces": "copper",
                        "entry_segments": [{"block": "conveyor", "x": 7, "y": 8}],
                    }],
                    "resource_reachability": [
                        {"item": "coal", "directed_path_reaches_sink": True,
                         "evidence": "item_observed_in_input_network"},
                        {"item": "sand", "directed_path_reaches_sink": False,
                         "evidence": "no_matching_upstream_producer_or_item_observed"},
                    ],
                    "first_flow_break": {
                        "kind": "incompatible_item_observed",
                        "segment": {"block": "conveyor", "x": 7, "y": 8},
                    },
                    "input_segment_count": 4,
                }],
            },
            "threat_summary": {"core_health_fraction": 0.1, "enemies_within_12_tiles": 2},
            "power_networks": [{"id": 7, "satisfaction": 0.5}],
            "production_summary": {
                "item_producer_counts": {"copper": 1, "coal": 1},
                "stalled_buildings": [{"block": "silicon-smelter", "x": 8, "y": 8, "reason": "noInput"}],
                "production_buildings": [
                    {"block": "mechanical-drill", "x": 6, "y": 8, "status": "active", "stall_reason": "none", "outputs": {"coal": "raw_item"}},
                    {
                        "block": "silicon-smelter", "x": 8, "y": 8, "status": "noInput",
                        "stall_reason": "noInput", "efficiency": 0,
                        "outputs": {"silicon": 1},
                        "craft_time_seconds": 0.67,
                        "nominal_item_input_rate_per_second": {"coal": 1.5, "sand": 3.0},
                        "observed_output_rate_per_second": {"silicon": 0.0},
                        "consumption_status": [{
                            "resource": "items", "amount_per_cycle": {"coal": 1, "sand": 2},
                            "available": {"coal": 0, "sand": 5}, "satisfied": False,
                        }],
                        "power": {"network_id": 7, "satisfaction": 0},
                    },
                ],
                "production_buildings_omitted": 0,
            },
        }
        compact = compact_state(state)
        self.assertEqual(compact["buildings"][0]["produces"], "copper")
        self.assertEqual(compact["valid_placement_samples"]["mechanical-drill"][0]["ore_tiles"], 4)
        self.assertTrue(compact["available_blocks"][0]["affordable_now"])
        self.assertEqual(compact["defense_supply"]["empty_turrets"], 1)
        direction = compact["defense_analysis"]["approach_directions"][0]
        self.assertEqual(direction["ground_route"]["rows"][2], [2, 6, 6])
        self.assertEqual(
            direction["valid_turret_placement_samples"]["duo"]["rows"][0],
            [7, 5, [[0, 4, 5]], [[0, 3, 4]]],
        )
        self.assertNotIn("ground_checkpoints_covered", direction)
        self.assertEqual(
            compact["logistics_analysis"]["selective_sink_bottlenecks"][0]["incompatible_items"],
            ["copper"],
        )
        self.assertNotIn("transport_topology", compact["logistics_analysis"])
        self.assertNotIn("conveyor_planning_area", compact["logistics_analysis"])
        self.assertIn("inspect_item_route", compact["logistics_analysis"]["detail_access"])
        self.assertEqual(compact["logistics_analysis"]["open_output_segment_count"], 1)
        self.assertEqual(compact["logistics_analysis"]["transport_components"][0]["segment_count"], 2)
        self.assertEqual(compact["threat_summary"]["enemies_within_12_tiles"], 2)
        self.assertEqual(compact["power_networks"][0]["id"], 7)
        self.assertEqual(compact["production_summary"]["item_producer_counts"]["copper"], 1)
        self.assertEqual(compact["production_networks"]["source_counts_by_resource"]["coal"]["active"], 1)
        silicon = compact["production_networks"]["networks"][0]
        self.assertEqual(silicon["id"], "silicon-smelter@8,8")
        self.assertEqual(silicon["input_network"]["incompatible_items"], ["copper"])
        self.assertEqual(silicon["input_network"]["transport_blocks"], {"conveyor": 4})
        self.assertEqual(silicon["input_network"]["minimum_nominal_transport_throughput_per_second"], 6.5)
        self.assertEqual(silicon["input_network"]["sink_nominal_item_demand_per_second"]["sand"], 3.0)
        self.assertEqual(silicon["nominal_item_input_rate_per_second"]["coal"], 1.5)
        self.assertEqual(
            silicon["input_network"]["contamination_sources"][0]["produces"], "copper"
        )
        self.assertFalse(silicon["input_network"]["resource_reachability"][1]["directed_path_reaches_sink"])
        self.assertEqual(silicon["input_network"]["first_flow_break"]["kind"], "incompatible_item_observed")
        self.assertEqual(
            {fact["kind"] for fact in silicon["observed_failure_facts"]},
            {"building_stall", "unsatisfied_input", "incompatible_items_in_input_network", "identified_contamination_sources", "saturated_input_segments", "power_network_unsatisfied"},
        )
        catalog = compact["capability_catalog"]
        self.assertTrue(any(row[0] == "duo" for row in catalog["rows"]))

    def test_keeps_remote_capabilities_compact_without_full_details(self) -> None:
        state = {
            "core": {"items": {"copper": 20}},
            "available_blocks": [{
                "name": "plastanium-compressor", "category": "crafting",
                "cost": {"silicon": 80, "titanium": 80}, "affordable_now": False,
                "inputs": [{"resource": "items", "amount_per_cycle": {"titanium": 2}}],
                "recipe": {"item_outputs_per_craft": {"plastanium": 1}},
            }],
        }
        compact = compact_state(state)
        self.assertEqual(compact["available_blocks"], [])
        horizon = compact["capability_catalog"]
        entry = dict(zip(horizon["columns"], next(row for row in horizon["rows"] if row[0] == "plastanium-compressor")))
        self.assertEqual(entry["details"]["io"]["plastanium"], 1)
        self.assertEqual(entry["details"]["ii"]["titanium"], 2)
        self.assertEqual(entry["cost"], {"silicon": 80, "titanium": 80})

    def test_keeps_recipe_and_runtime_production_diagnostics(self) -> None:
        state = {
            "buildings": [{
                "block": "graphite-press", "x": 4, "y": 8, "status": "noInput",
                "stall_reason": "noInput", "items": {},
                "consumption_status": [{
                    "resource": "items", "amount_per_cycle": {"coal": 2},
                    "available": {"coal": 0}, "satisfied": False,
                }],
                "output_to": {"block": "conveyor", "x": 5, "y": 8},
            }],
            "available_blocks": [{
                "name": "graphite-press", "cost": {"copper": 75, "lead": 30},
                "inputs": [{"resource": "items", "amount_per_cycle": {"coal": 2}}],
                "recipe": {
                    "craft_time_seconds": 1.5,
                    "item_outputs_per_craft": {"graphite": 1},
                },
                "affordable_now": False,
            }],
        }
        compact = compact_state(state)
        self.assertEqual(compact["buildings"][0]["stall_reason"], "noInput")
        self.assertEqual(
            compact["buildings"][0]["consumption_status"][0]["available"]["coal"], 0
        )
        self.assertEqual(
            compact["available_blocks"][0]["recipe"]["item_outputs_per_craft"]["graphite"], 1
        )

    def test_keeps_turret_combat_catalog(self) -> None:
        state = {"available_blocks": [{
            "name": "hail", "category": "turret", "affordable_now": True,
            "combat": {
                "range_tiles": 29.375, "targets_air": False, "targets_ground": True,
                "base_shots_per_second": 0.5,
                "ammunition": [{
                    "resource": "graphite", "direct_damage": 33,
                    "splash_damage": 25, "estimated_dps": 29.25,
                }],
            },
        }]}
        combat = compact_state(state)["available_blocks"][0]["combat"]
        self.assertFalse(combat["targets_air"])
        self.assertEqual(combat["ammunition"][0]["resource"], "graphite")
        self.assertEqual(combat["ammunition"][0]["splash_damage"], 25)

    def test_keeps_power_link_mechanics_and_live_topology(self) -> None:
        state = {
            "core": {"items": {"lead": 20}},
            "power_node_topology": [{
                "block": "power-node", "x": 10, "y": 20, "network_id": 4,
                "link_range_tiles": 6.0, "max_links": 10, "current_link_count": 1,
                "links": [{"block": "silicon-smelter", "x": 15, "y": 20}],
            }],
            "available_blocks": [{
                "name": "power-node", "category": "power", "cost": {"lead": 5},
                "affordable_now": True,
                "power_connection": {
                    "link_range_tiles": 6.0, "max_links": 10,
                    "mechanic": "wireless links; node tiles do not need to be contiguous",
                },
            }],
        }
        compact = compact_state(state)
        self.assertEqual(compact["power_node_topology"][0]["current_link_count"], 1)
        self.assertEqual(compact["available_blocks"][0]["power_connection"]["link_range_tiles"], 6.0)
        catalog = compact["capability_catalog"]
        entry = dict(zip(catalog["columns"], catalog["rows"][0]))
        self.assertEqual(entry["details"], {})

    def test_defers_unreachable_turrets_without_forgetting_them(self) -> None:
        state = {
            "core": {"items": {"copper": 100, "graphite": 5}},
            "nearby_ores": [
                {"item": "lead", "x": 4, "y": 5},
                {"item": "copper", "x": 6, "y": 7},
                {"item": "coal", "x": 8, "y": 9},
            ],
            "buildings": [],
            "available_blocks": [
                {
                    "name": "scatter", "category": "turret", "cost": {"copper": 85, "lead": 45},
                    "affordable_now": False, "combat": {"targets_air": True},
                },
                {
                    "name": "hail", "category": "turret", "cost": {"copper": 40, "graphite": 17},
                    "affordable_now": False, "combat": {"targets_ground": True},
                },
                {
                    "name": "cyclone", "category": "turret",
                    "cost": {"copper": 200, "titanium": 125, "plastanium": 80},
                    "affordable_now": False, "combat": {"targets_air": True},
                },
                {
                    "name": "graphite-press", "category": "crafting", "cost": {"copper": 75},
                    "affordable_now": True,
                    "recipe": {"item_outputs_per_craft": {"graphite": 1}},
                },
            ],
        }
        compact = compact_state(state)
        detailed_names = {block["name"] for block in compact["available_blocks"]}
        self.assertNotIn("scatter", detailed_names)
        self.assertNotIn("hail", detailed_names)
        self.assertNotIn("cyclone", detailed_names)
        catalog = compact["capability_catalog"]
        entries = {
            row[0]: dict(zip(catalog["columns"], row)) for row in catalog["rows"]
        }
        self.assertTrue(entries["scatter"]["details"]["combat"]["targets_air"])
        self.assertTrue(entries["hail"]["details"]["combat"]["targets_ground"])
        self.assertEqual(compact["deferred_turrets"], [{
            "name": "cyclone",
            "cost": {"copper": 200, "titanium": 125, "plastanium": 80},
            "blocked_by_resources": ["titanium", "plastanium"],
        }])


if __name__ == "__main__":
    unittest.main()
