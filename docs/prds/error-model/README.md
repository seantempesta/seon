---
type: prd
status: active
tags: [prd, runtime, error, data-model, render]
---

# Error model conversion: attribute-shaped classes, one wave

## Decision

Owner-ruled 2026-08-03 night (recorded in
[plan/unsettled.md](../sci-execution-runtime/plan/unsettled.md), "THE ERROR
MODEL" block and its renderer amendment): `:seon.error/kind` is the kind
anti-pattern in value space and is DELETED. Each failure class becomes its own
namespaced attribute set whose PRESENCE makes it what it is, validated by a
registered schema carrying the `{:seon.error/class true}` Malli property;
`matching-shapes-in` finds the class. ONE MODEL covers in-flight error values
and committed fault facts (historical facts stay as history — never migrated).
Errors get renderers: one default render function pair for any class-carrying value,
per-class overrides only where a class earns richer display.

The complete authority is the census:
[error-catalog-2026-08-03.md](../sci-execution-runtime/research/error-catalog-2026-08-03.md)
— read it END TO END before implementing any slice. Its §1 census is the
work list, §2 the replacement shape, §3 the five dispatch conversions
verbatim, §4 the transform templates, §6 the fault-fact alignment, §7 the
wave order this PRD executes.

Owner rulings incorporated (all explicit, 2026-08-03, final batch confirmed
in-session 2026-08-03 night):

1. Subjectless class markers hold boolean `true` — never `false`, never a
   nested map (Datahike-bridgeable by construction).
2. The schema projection emits arbitrary storable namespaced Malli properties
   as `:seon.schema` row attributes — LANDED `f08d79e96` with the
   property-lift discrimination (a property lifts exactly when its own
   declaration exists AND is bridge-storable; `:gen/*` stays compile-time).
   §5's `[?s :seon.error/class true]` query is live.
3. Refusals: per-namespace refusal classes, each carrying
   `{:seon.error/refusal true}` so "all refusals" is one query, all
   referencing ONE shared registered shape for the rule/transition
   attributes.
4. `:seon.error/message` stays REQUIRED on every error value.
5. The §2.3 merge table is approved as recommended (both invalid-output
   classes merge; the install-mismatch pair merges; the eleven refusals stay
   per-namespace on the shared shape; `:core-bug`/`:user-input` are deleted
   with real classes per site; the `:seon.db` pair is kept and renamed).
6. The `:seon.ai/error-class` second taxonomy converts IN THIS WAVE.
7. Function output specs MAY declare error branches (`[:or success err…]`);
   the install gate TEACHES (advisory, never blocks) when an observed class
   is undeclared.
8. `:seon.error/class` (the string throwable name) was renamed
   `:seon.error/throwable-class` pre-wave; the freed key is the boolean
   schema-row marker property.

## Dependency ledger — all landed, nothing hypothetical

| Dependency | State | Evidence |
|---|---|---|
| The census and per-class shapes | DONE | the error catalog; corrected count 225 target class schemas, 5 dispatch sites + the ai second taxonomy |
| Property lift (`:seon.error/class` queryable on schema rows) | LANDED | `f08d79e96` + property-lift discrimination gates green (62 tests / 1,565 assertions, 2026-08-03 night) |
| Keyword edges (the completeness query) | LANDED | `bd4494239`: `:seon.fn/keywords` on 2,213 rows / 14,083 edges; "zero functions still touching `:seon.error/kind`" is now a query, which is why the wave waited for it |
| `matching-shapes-in` | STANDING | `src/seon/schema.clj:2361-2383` — presence-indexed, required-attrs filtered, validated; ambiguity already handled |
| Default renderer precedent | STANDING | `resources/seon/schemas/seon.error.edn` already declares `:seon.render/ai seon.error/render-ai` and `:seon.render/html seon.error/render-html` as schema properties |
| Schema split layout | LANDED | flat `resources/seon/schemas/<full.namespace>.edn`, one file per key namespace, verbatim names, no munging |
| Edit tooling honesty | CONSTRAINT | `seon.edit` has only top-level `form` and `exact` operations (catalog §4); the mechanical majority runs as per-site edits generated from the census, or a rewrite-clj zip pass in `tmp/` — never a regex |

## Wave structure — cut-first, kind dies in the same wave

Timing: W1 may start immediately (additive, independently green). W2–W5
launch only after the `wave-close` checkpoint (full gate + live-default
refork) completes — a six-lane rewrite must not race a frozen-tree
checkpoint.

**W1 — schemas + renderer (one lane).** Declare every class schema from the
census under `resources/seon/schemas/<ns>.edn` with the §2.1 shape: marker
attribute valued by the primary subject (boolean `true` when subjectless),
`:seon.error/message` required, everything else optional, maps open,
`{:seon.error/class true}` property, `{:seon.error/refusal true}` on the
refusal classes, the one shared refusal shape. Land the default error
renderer pair (`seon.error/render-ai` generalized to any class-carrying
value: what failed, the load-bearing attribute values, what to do next; the
HTML card with message headline, marker row, evidence rows, evidence link)
and the overrides the catalog's §2.2b table names. Land `seon.error/error?`
(class-match non-empty; for registry-free leaves — `seon.edit`,
`seon.sci.reader` — presence of `:seon.error/message` in a map). Nothing
else changes; the tree stays green with both models coexisting for the
duration of the wave only.

**W2 — emissions + constructors (six parallel lanes, file-disjoint).** Apply
templates T1/T2/T3 across all ~165 construction sites and DELETE the seven
local constructors in the same commits. Lane owners by census family:

| Lane | Families | Owns |
|---|---|---|
| `err-fs-edit` | §1.1 fs/edit | `src/seon/fs/`, `src/seon/edit*`, `src/my/fs*`-adjacent tests |
| `err-ai` | §1.2 + the `:seon.ai/error-class` taxonomy (ruling 6; catalog §3-sixth) | `src/seon/ai.clj`, its tests |
| `err-fn-schema` | §1.3 program/schema/eval/kernel, incl. the 11-way `index-refused` split | `src/seon/fn*`, `src/seon/schema*`, `src/seon/sci/`, `src/seon/instrument.clj`, `src/seon/program.cljc`, tests |
| `err-cluster` | §1.4 lifecycle/store/config | `src/seon/cluster/`, `src/seon/cluster.clj`, `src/seon/config*`, `src/seon/bootstrap*`, `src/seon/flow.clj`, `src/seon/db.clj`, tests |
| `err-render` | §1.5 render/walk/web/mcp | `src/seon/render/`, `src/seon/dev/`, tests |
| `err-my` | §1.1 `my.*` + operator/test-runner residue | `src/my/`, `src/seon/operator.clj`, `src/seon/test/`, tests |

T2 sites need a hand-written honest `:seon.error/message` per site — one
line each, never generated boilerplate. Each lane also converts its own
test assertions (the three mechanical classes in catalog §7) in the same
commits — an emission commit that leaves its tests asserting kinds is
incomplete.

**W3 — presence tests + dispatches.** T4 across ~90
`(:seon.error/kind x)`-as-truth sites → `error/error?`, plus the five §3
dispatch conversions verbatim and the ai taxonomy dispatch. Runs inside the
same six lanes where file ownership already places the sites; the five
dispatch sites are named hand work.

**W4 — facts + wake (one lane, hand work).** The normalizer
(`normalize`/`value`/`notice`/`signature`), receipt and run schemas,
`commit-fault!` gains `:seon.error/to` (owner derivation via
`seon.cluster.work/form-owner`), the one-line `wake-attributes` addition and
`route!` branch, `seon.problems`' Datalog. **Drop `:seon.error/kind` from
`:seon.error/fact` and every request/value schema in the same commit.** The
attribute declaration survives for history reads only. The by-hand test
namespaces (error_test, turn_test, problem_routing_test) are rewritten
against the surviving mechanism here, never green-washed.

**W5 — spec branches + advisory gate.** `[:or …]` error branches on the
capability and SCI evaluation entry points first, then the advisory install-gate
check (observed class not in declared output-refs → one teaching line).
Composes with the accretion-testing PRD's gate; the check itself is a
query over `:seon.fn.arity/output-refs` × `:seon.error/class`.

## Standing falsifiers (recurring surfaces, not lane proofs)

1. **No kind survives — by query, not grep.** A standing test asserts zero
   `:seon.fn` rows whose `:seon.fn/keywords` include `:seon.error/kind`
   (the keyword-edge query this wave waited for). `rg` confirms only the
   history-read declaration remains, as commit evidence.
2. **Every class is findable and unambiguous.** Generative property: for
   every schema row carrying `:seon.error/class true`, a generated sample
   value fed to `matching-shapes-in` matches exactly that class; two classes
   matching one value fails the property.
3. **No error reaches the value floor.** One generated value per class,
   both projections rendered, non-generic face asserted for every one.
4. **The dispatches still decide the same thing.** One behavioral test per
   converted dispatch site (HTTP status triple, edit-error translation,
   refused-tag branch, instrument face, walk elision, ai disposition).
5. **The wake fires.** Live at a reset boundary: a fault carrying
   `:seon.error/to` wakes the owning agent's mailbox; plus the standing
   disjointness property between `wake-attributes` and
   `committed-attributes`.
6. **Declared branches match reality.** Drive each converted capability's
   failure paths; every observed class is in its declared output-refs — the
   advisory gate stays silent on converted code.

## What not to build

- no compatibility layer, dual-emission period, or kind-to-class bridge —
  cut-first; the only coexistence is W1's additive slice;
- no error constructor function — the target is a map literal validated by
  its class schema; the seven local constructors die in W2;
- no regex over source to find or rewrite sites (standing regex ruling; the
  census is the work list, `seon.edit`/rewrite-clj are the tools);
- no second renderer path beside the declared render function discovery query;
- no blame taxonomy resurrection — who-is-at-fault is the channel's answer;
- no migration of historical kind-carrying facts.

## Graduation

The full gate green with all six standing falsifiers claimed by recurring
surfaces; a live cluster demonstrates one refused capability call, one
contract violation, and one deadline cut each rendering their class faces
in agent context and on the debug page; "what errors can `f` return" and
its reverse answered by query for a converted capability; the error
catalog's status flipped to historical and the architecture error text
updated in the same wave.
