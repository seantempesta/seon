---
name: seon-context-config
description: "Change or diagnose Seon's database-backed cluster configuration: schema dials, defaults, sparse overrides, apply operations, and live versus instrument-time acquisition."
---

# Cluster configuration

Configuration is declared data, compiled and reconciled into the selected
cluster. Use [agent-loop.md](../../../docs/seon/specs/agent-loop.md)
when a dial touches agent context, rendering, or result lifetime.

## Declare and compile

Declare each namespaced dial once in its owning schema family.
Config composites derive from those leaves
(`seon.schema.edn/derive-config-forms`).
`seon.config/default-decisions` validates the shipped decisions
(through `validate-default-decisions`).

`seon.config/compile-manifest` combines defaults, a sparse
override, and an explicit typed environment map in that order. Required
absence refuses; the absence marker is removed from the effective map
(`seon.config/compile-settings`). This explicit input is not permission
for running consumers to fetch process environment state.

## Apply and inspect

Use `bin/seon config apply [NAME] PATH`; the live operation is
the `:config-apply` branch of `seon.cluster.boot/request!`. `seon.config/apply!` compiles and
reconciles the selected document; `seon.config/effective`
reads from the database.

Boot applies the checkout's compiled entities to a branch that may not have
reloaded the checkout's declarations. `apply-compiled!` installs an added
dial's attribute (`accrete-entity-attributes!`) and keeps the current value of
an attribute the checkout no longer declares (`hold-retired-attributes`).
A schema transaction retires that attribute with its data (`seon.cluster/branch-declaration-changes`).
Deleting a dial therefore needs only its declaration and decision lines removed;
there is no two-step retirement.

Verify both the resulting datoms and the consumer behavior. An applied
entity does not by itself rebuild a graph, executor, or server. For each
dial, follow its actual read site to determine whether it is acquired
at boot, graph start, a turn, or a request. Do not copy a momentary
acquisition table whose referenced mechanisms are being deleted.

## Context and result contract

Each turn forks the agent's SCI context from the cluster program context
(`seon.sci.eval/program-context`) and carries its private layer over
(`seon.sci.eval/fork-for-turn`); defs, atoms, and result objects stay in memory
(CLAUDE.md, "The system: one JVM, many realities"). The evaluation stores
the printed value produced under the render profile once, plus out and error.
Do not restore a def-blob threshold, result serializer, or separate
result storage cap as a config requirement.

One entity schema declares one render pair, and render functions choose
forms from their data.

Routes remain code data in `seon.render.route/routes`.
Context blocks, manual curation, rendered output, and route inventories
are not another config manifest. Use the data-modeling skill for new
schema choices and clojure-testing for the canonical fixture and gate.
