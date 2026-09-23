---
name: seon-context-config
description: "Change or diagnose Seon's database-backed cluster configuration: schema dials, defaults, sparse overlays, apply operations, and live versus arm-time acquisition."
---

# Cluster configuration

Configuration is declared data, compiled and reconciled into the selected
cluster. Use [the turn PRD](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
§13–§15 when a dial touches agent context, rendering, or result lifetime.

## Declare and compile

Declare each namespaced dial once in its owning schema family.
Config composites derive from those leaves
(`derive-config-forms`, `src/seon/schema/edn.clj:62`).
`seon.config/default-decisions` validates the shipped decisions
(`src/seon/config.clj:418`, through `validate-default-decisions` `:378`).

`compile-manifest` (`src/seon/config.clj:521`) combines defaults, a sparse
overlay, and an explicit typed environment map in that order. Required
absence refuses; the absence marker is removed from the effective map
(`compile-settings`, `:468-519`). This explicit input is not permission
for running consumers to fetch process environment state.

## Apply and inspect

Use `bin/seon config apply [CLUSTER] PATH`; its parser is
`script/seon/operator.clj:1319-1320`, `:1345-1352`, and the live operation is
the `:config-apply` branch of `seon.cluster.boot/request!`
(`src/seon/cluster/boot.clj:513-518`). `seon.config/apply!` compiles and
reconciles the selected document (`src/seon/config.clj:725`); `effective`
reads from the database (`:754`).

Verify both the resulting datoms and the consumer behavior. An applied
row does not by itself rebuild a graph, executor, or server. For each
dial, follow its actual read site to determine whether it is acquired
at boot, graph arming, a turn, or a request. Do not copy a momentary
acquisition table whose referenced mechanisms are being deleted.

## Context and result contract

Each turn forks the agent's SCI context from the cluster base and carries its
private layer over (`fork-for-turn`, `src/seon/sci/eval.clj:2287`); defs,
atoms, and result objects stay in memory (`AGENTS.md:175`). The evaluation stores
shown text produced under the render profile once, plus out and error.
Do not restore a def-blob threshold, result serializer, or separate
result storage cap as a config requirement.

One entity schema declares one render pair, and render functions choose
forms from their data. System turns store opening and refreshed reads.
The since-query diff covers every distinct read form's latest evidence;
writes/effects never rerun. The prompt is stored evaluations rendered
in order, with an immutable prefix until compaction wipes evaluations
and regenerates the opening.

Routes remain code data in `src/seon/render/route.clj:5`.
Context blocks, manual curation, rendered output, and route inventories
are not another config manifest. Use the data-modeling skill for new
schema choices and clojure-testing for the canonical fixture and gate.
