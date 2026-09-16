---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [schema, render, selection, class, open-maps]
---

# An open map keyed only by universal attributes shadows every entity

## Problem

Maps are open (AGENTS.md §2.5), so a declared entity map whose REQUIRED keys
are all attributes every entity of some family carries is satisfied by the
whole family. Three consumers hit it on 2026-09-16:

1. `:seon.problems/stale-var` — `[:map [:seon.fn/sym …] + render pair]`:
   its AI producer was selected for all 4,868 `:seon.fn` rows, so every
   function pull from SCI returned the "Restart the JVM…" sentence
   (`59c7d78c4`).
2. `:seon.render.transcript/pulled-transaction` — `[:map [:db/id :int]
   [:db/txInstant {:optional true} :inst]]`: every `'[*]` pull satisfied it
   and `declared-entity-units` folded `:db/id`/`:db/txInstant` into every
   page's unit list (`415ab40fc`).
3. The `seon.error` `:seon.error/at` fact shape vs the installed attribute
   set (transcript second pass) is the same disease from the write side.

Each fix was local (remove the pair; filter units by declared forms). The
class remains: nothing refuses a declaration that can only ever match by
accident.

## Fix shape (one checker, one rule)

A schema checker over the merged registry, run where declarations are
admitted: an entity map that participates in selection — declares a render
pair, contributes units, or is cited by `:seon.program/row-schema` — must
require at least one attribute that is NOT universal, where universal means
`:db/id`, `:db/txInstant`, and any `:db.unique/identity` attribute of a
DIFFERENT family. Drift fails the checker with the offending map named. The
three sightings become its regression cases. Selection itself may also rank
a map by how many of its required keys are family-specific, but the checker
is the class kill; ranking is a heuristic.
