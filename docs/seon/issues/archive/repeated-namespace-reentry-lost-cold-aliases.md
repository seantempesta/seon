---
type: issue
status: resolved
severity: high
tags: [issue, agent, cljs, flow]
---

# Reconstruct retained namespace aliases for a fresh child

## Problem

A reply may enter one namespace, switch elsewhere, and later return with a
bare `(ns name)`. `cljs.js` retains the namespace's existing aliases in the
live analyzer, so dependent functions continue working. The database stored
the latest authored declaration, however, and cold loading preferred that bare
source over the analyzer-derived `:seon.ns/require-edges`. A restarted child
could therefore lose an alias that the successful eval actually used.

## Evidence

The current-artifact live program declared the consumer before its dependency,
switched through the dependency, and returned to the consumer with a bare
declaration. Both consumer calls returned `43`, while the final stored source
was the bare declaration and the effective require edge still named the base
namespace and alias.

## Resolution

Cold namespace source now merges the persisted effective require libspecs into
the latest authored `ns` declaration. This preserves every other authored
clause while reconstructing the same alias environment retained by `cljs.js`.
Both ordinary execution preparation and the older direct reconstruction helper
use the same projection.

## Verification

`seon.execution-test` pins bare namespace reentry with a retained alias, and
the focused execution plus require gate passes 38 tests and 148 assertions
with no failures, errors, or compile warnings.
