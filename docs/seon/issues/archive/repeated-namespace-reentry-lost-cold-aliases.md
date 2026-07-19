---
type: issue
status: resolved
severity: high
tags: [issue, agent, cljs, flow]
---

# Reconstruct retained namespace aliases for a fresh child

## Problem

A reply may enter one namespace, switch elsewhere, and later return with a
bare `(ns name)`. Dependent functions already compiled in the live child keep
working, but evaluating Seon's augmented declaration can replace the analyzer
aliases. The database then stored both the latest declaration and the reduced
`:seon.ns/require-edges`. A restarted child therefore lost an alias that the
successful function still used.

## Evidence

The current-artifact live program declared the consumer before its dependency,
switched through the dependency, and returned to the consumer with a bare
declaration. Both consumer calls returned `43`, while the final stored source
contained only the standard toolkit requires and its require edges no longer
named the base namespace. The earlier projection-only regression had assumed
the retained edge instead of exercising its recording owner.

## Resolution

Before evaluating an authored bare namespace declaration, the eval owner now
captures that namespace's current analyzer require edges. Successful bare
re-entry persists their union with the standard edges added during evaluation.
An authored declaration containing `:require` remains authoritative and keeps
replacement semantics; `ns-unalias` remains the explicit removal operation.
Cold namespace source continues to merge the persisted effective libspecs into
the latest declaration.

## Verification

`seon.eval.promise-ergonomics-test` pins the recording decision for bare versus
explicit namespace declarations. A live ordered batch and fresh execution-child
reconstruction prove the persisted edge and function behavior together.
