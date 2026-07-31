---
type: issue
status: open
severity: blocker
tags: [issue, source, indexing, clj-kondo]
---

# Correct clj-kondo's `vswap!` arity before program publication

## Problem

Valid two- and three-argument `vswap!` calls are reported as error-level invalid arities, blocking current-source publication and every database-backed test population.

## Evidence

`seon.fn.analyzer/analyze` reports `src/seon/render/block.clj:717` and `src/seon/render/walk.clj:247,363` as requiring four arguments, while `clojure -M:dev` loads both namespaces and the calls match `clojure.core/vswap!`.

## Owner

The maintained clj-kondo core Var metadata and dependency-cache publication path.

## Acceptance

Valid `vswap!` arities analyze cleanly, an actually invalid arity still refuses, current-source publication advances, and the render/context namespace gate reaches its assertions.

## Second kondo type defect, same wave (2026-07-31)

clj-kondo also mistypes `(volatile! x)` as returning `nil`, blocking any
edit that derefs a volatile local (observed twice by the sci ground-truth
lane). Same analyzer-correction wave as the `vswap!` arity.
