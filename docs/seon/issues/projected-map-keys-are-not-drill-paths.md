---
type: issue
status: open
tags: [web, rendering, issue]
severity: reliability
---

# Projected map keys are not drill paths

## Problem

`seon.render.value` says retained map keys remain valid path components, but
the bounded sampler replaces long, collection, and opaque keys with safe
display projections. Those replacement values are not the original keys and
cannot honestly address the child value during a drill request.

## Evidence

`map-key-projection` records projected-key metadata while emitting a display
replacement into the sampled entry. Treating that replacement as an ordinary
path would return the wrong child or falsely claim that a visible node is
navigable.

## Owner

The bounded value projection owns drillability metadata. The later route/UI
consumer may expose a drill control only for an ordinary retained original key
or for a separately proven opaque path token that safely crosses IPC.

## Acceptance

- Sampled entries distinguish display labels from valid original path
  components.
- Projected keys never produce a drill link or child request using the
  replacement value.
- Ordinary retained scalar keys remain deterministically drillable.
- Focused sampler, route, and UI tests cover both branches.
