# Collection coverage

This is a bounded first release, not a claim that every modpack action is observable.

| Source / machine family | Coverage | Accounting |
|---|---|---|
| Registered numeric Minecraft statistics | Supported, nonzero/previously observed values | Reads ServerUtilities' `ForgePlayer.stats()`; includes offline players at startup/import/shutdown |
| ServerUtilities play / AFK / active leaderboard | Supported | Same underlying play and AFK stats, ticks / 20; AFK availability follows SU configuration |
| Vanilla `SlotCrafting` and subclasses invoking its hooks | Supported | One completed pickup = one recipe operation; `amountCrafted` counts outputs, including shift-click |
| Completed food/potion use | Supported | Server `PlayerUseItemEvent.Finish`; creative consumption excluded |
| `MTEBasicMachine` subclasses that reach its `onPostTick` progress step | Supported | Completions and output buffers; physical input changes when the recipe checks are inherited, otherwise outputs only |
| `MTEMultiBlockBase` subclasses that reach its `runMachine` progress step, including custom recipe selection (`checkProcessing` overrides) | Supported | Completions, outputs and current/loaded parallelism; inputs measured as physical bus/hatch stack decreases when the input accessors are inherited. Custom-hatch inventories are not included |
| GT steam single-block machines (bronze and steel alloy smelter, compressor, extractor, forge hammer, furnace, macerator) | Supported | They replace `checkRecipe()`, so a dedicated hook wraps those overrides; input slot and fluid consumption, recipe outputs at completion |
| GT++ steam multiblocks (`MTESteamMultiBlockBase`) | Supported | Covered by the multiblock row: `getStoredInputs` reads their steam input buses and fluid hatches. Steam drawn for power is not counted as an input |
| GT Bricked Blast Furnace (`MTEBrickedBlastFurnace`) | Unsupported, detected at runtime | Runs its own loop outside `MTEMultiBlockBase`; coverage gauges only |
| GT coke oven (`MTECokeOven`) | Supported | Dedicated hook on its private recipe loop; input slot 0 consumption, recipe outputs at completion |
| ME input buses/hatches, dual-input inventories, debug input hatches | Outputs only | Deferred/virtual consumption cannot be inferred from a physical-stack snapshot; completions and outputs still count (`gregfana_machines_inputs_untracked`) |
| Machines that replace the base processing loop (no hook reached) | Unsupported, detected at runtime | A class active across two collections (~10 s) without reaching any hook is logged and reported unsupported; seeing it hook once clears this. The coke oven has a dedicated hook |
| Generators, pipes, hatches as standalone devices, AE2/other mods' automation | Outside v1 production coverage | No production counters |
| Individual coordinates, ownership, team/base attribution | Outside v1 | Machine identity is GT's stable metatile name, including tier variants, plus recipe map |

The runtime `gregfana_machines_loaded`, `gregfana_machines_active`, and `gregfana_machines_supported` gauges count loaded processing controllers by metatile name. Subtract supported from loaded to see unavailable configurations. An unsupported machine is not a zero-output supported machine. Historical counters remain after unload; gauges disappear when no matching controller is loaded. Nothing force-loads chunks.

Input snapshots deduplicate aliased references and compare before/after quantities, so catalysts and zero-size recipe selectors are not counted. They count physical consumption even if a later recipe check fails after consuming inputs. Failed checks that do not mutate inputs add nothing. Output buffers already contain realized chance outputs. The completion hook runs after progress reaches the completion branch, before output buffers are transferred/cleared. Transfers between inventories are not production.

Runs started/completed and recipe executions are distinct. Interrupted runs keep their input consumption but do not gain a completion. A run already in progress when Gregfana is installed can complete without a matching observed start. Loaded multiblock parallelism uses GT's `lastParallel` fallback, matching GT's own completed-recipe accounting.

`scripts/audit_gt.py` generates the [217-class override inventory](gt-overrides.md) from the pinned GT source. It is an aid to reviewing new integrations; the table is not an allowlist. The bytecode contract tests assert that the targeted completion points exist and precede output transfer/clear. Full gameplay correctness still requires the validation scenarios.
