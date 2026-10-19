---
type: issue
status: open
severity: defect
tags: [issue, cache, sci, runtime]
---

# A `seon.cache` derivation made by a branch's code is served to main, and main's to the branch

A `seon.cache/lookup` key names the derivation's input, never the code deriving it. Since the branch runtime
(85299376b) a branch's change is live in every analysed caller it reaches, so a derivation whose deriving code reaches
a branch change is computed by different code on that branch and on main under one key: whichever runtime derives
first serves both. Review finding F7 ([runtime-review](../../research/agent-platform/2026-10-19/runtime-review.md)).

Reproduced 2026-10-19 (lane runtime-f7): `seon.sci.runtime-test/a-composed-runtime-answers-what-its-own-source-answers`
with one more shaped name, `cc`, deriving `(+ k (h0 x))` through `seon.cache/lookup` keyed by `[x]`, and the runtimes
answered in both orders, is red on 85299376b (dangling commit `8cc7ab7f2`, the test change of
`runtime-f7-union.patch`).

## Option 2, landed with open names keeping their keys (lane runtime-f7b): key by the changes the deriving code reaches

`docs/research/agent-platform/2026-10-19/runtime-f7-union.patch`: `seon.cache/*reached*` (a set of `[name digest]`)
joins the key in `lookup`, `lookup-within` (its derivation thread conveys the binding), `present` and `evict!`;
`seon.sci.eval/bind-analysed!` binds each analysed function as `seon.sci.runtime/reaching`, which adds the changes its
code reaches (`seon.sci.runtime/reached-changes`: the closed reach through `::reached-by`, every change for an open
name) to what its callers bound, and wraps the runtime's own definitions with every change. The extended test is
green with it (check of `ab197fc8f`).

Cost, same-run pairs of throwaway check JVMs, MB allocated, HEAD against the change
(`tmp/runtime-f7/cost{2,4}`):

| Row | HEAD | exact (open = every change) | open names keep their keys |
|---|---|---|---|
| `:unchanged-request` | 18.8 | 18.5 | 18.9 |
| `:reuse-one-unchanged-test` | 31.6 | 34.1 | 31.0 |
| `:seon-db-nine-forms` | 1,238.7 | 2,944.9 (2.38x) | 1,235.8 (1.00x) |
| `:hub-behavior-change` | 1,006.4 | 5,141.7 (5.11x) | 1,133.7 (1.12x) |
| `:similar-by-name-warm` | 22.8 | 23.9 | 26.7 |

The cost is the open rule alone. An open function (one reaching `requiring-resolve`, `resolve`, `find-ns` and the rest
of `seon.sci.eval/run-time-resolution`, about 3,400 of 7,686 names) has no reach the index can follow, so the exact
key is every change the runtime holds, an agent's brand-new definitions included; an open frame around the schema
projection (`seon.db/projection` 4-5 s, `seon.schema/projection`, `seon.schema/declaration-projection`,
`seon.config/effective`) then derives it again per branch. Replacing instead of adding the callers' changes measured
the same (5.1x), so the higher-order rule costs nothing measurable.

Landed (lane runtime-f7b, root's decision): the union patch with the last column's rule, so
`seon.sci.runtime/reached-changes` keys a derivation only by the changes a closed reach holds; an open name is not
bound again on each change, since it carries no key of its own.

## What is not decided

Option 2 is exact and within 1.3x only for derivations whose code the reach index follows. Making it exact needs the
names an open function resolves at run time: the quoted symbols its `requiring-resolve` names are static and could
join `:seon.fn/calls` (an analyzer change), but render functions found in the viewing agent's namespace are resolved
from data. Not covered either way by the patch: an own definition re-rooted by `seon.sci.arm/arm!` when the write is
`behavior-held?` (no `interpret-affected!` runs) or by `rearm-dependents!` loses its wrapper.
