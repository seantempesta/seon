---
name: seon-context-config
type: skill
status: active
description: "Change or diagnose Seon's database-backed cluster configuration: schema dials, defaults, sparse overlays, apply operations, and live versus arm-time acquisition."
---

# Cluster configuration

Configuration is declared data, compiled and reconciled into the selected
cluster. Use [the turn PRD](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
§13–§15 when a dial touches agent context, rendering, or result lifetime.

## Declare and compile

Declare each namespaced dial once in its owning schema family.
Config composites derive from those leaves
(`src/seon/schema/edn.clj:67`).
`seon.config/default-decisions` validates the shipped decisions
(`src/seon/config.clj:330`).

`compile-manifest` combines defaults, a sparse overlay, and an explicit
typed environment map in that order. Required absence refuses; the
absence marker is removed from the effective map
(`src/seon/config.clj:358`). This explicit input is not permission
for running consumers to fetch process environment state.

## Apply and inspect

Use `bin/seon config apply [CLUSTER] PATH`; its parser and live
operation are `script/seon/fresh_operator.clj:395` and `:2161`.
`seon.config/apply!` compiles and reconciles the selected document
(`src/seon/config.clj:504`); `effective` reads from the database
(`:533`).

Verify both the resulting datoms and the consumer behavior. An applied
row does not by itself rebuild a graph, executor, or server. For each
dial, follow its actual read site to determine whether it is acquired
at boot, graph arming, a turn, or a request. Do not copy a momentary
acquisition table whose referenced mechanisms are being deleted.

## Context and result contract — target

Each agent keeps one live SCI context receiving base diffs across turns.
Defs, atoms, and result objects stay in memory. The evaluation stores
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
