---
type: research
status: evidence — read-only study; implementation frozen
created: 2026-09-24
scope: generating each agent's SCI context in proportion to its diff (question tmp/orchestrator/sci-context-research-question.md)
pins: seon HEAD 8a881e236 (line numbers); live JVM pid 90471 runs archive ce7384682 (fn.clj, db.clj, agent.clj identical; eval.clj differs only in error-constructor hunks); sci fcbd8862; datahike c79cd03a
---

# Generating the exact SCI context efficiently (2026-09-24)

What exists: `acquire-context!` (`src/seon/cluster/agent.clj:779`) picks a branch, then calls
`seon.sci.eval/acquire!` (`src/seon/sci/eval.clj:2618`). On a program-identity miss, that
rebuilds a whole base context from the branch value (`derive-base-ctx` `:2416` → `acquire-program!`
`:1859`), compares every digest with the loaded commit, walks a reverse closure, and interprets the
difference. `fork-for-turn` (`:2280`) then forks it for the agent. The principal Clojure approach
this report recommends: one host base per loaded program, plus a content-keyed override layer that
is a function of `{sym digest}` for exactly the rows that differ. SCI's O(1) copy-on-write `fork`
is the only per-agent step.

All numbers are live JVM measurements on default at basis-t 536872648. The program has 4,752 function
rows, 2,107 tests and 435 namespace rows; 120 namespaces are loaded into SCI (4,298 entries) and
the store holds 575,837 datoms. Time is wall clock, and allocation comes from
`ThreadMXBean/getThreadAllocatedBytes` on the calling thread. The probes ran in the scratch
namespace `scratch.opus-ctx-probe`, MCP session `opus-ctx`. Fresh, uncached database values came
from `(d/db-with db [])`. I made no writes, created no branches and redefined no default Vars.

## Broken first

1. **Acquisition with one override costs 916–977 ms and 3.3 GB. With no override it costs
   76–140 ms and 250–350 MB.** Default carries one agent row (`my.agents.root/largest`), so every
   new default program identity pays the high figure. A simulated 1-row change
   (`seon.note/add-note!` digest via `d/db-with`, 4 rows interpreted) measured 936–977 ms and 3.37 GB.
2. **About 85% of that is one Datalog rule that scans the whole store for 0 results.**
   `declared-reference-rules` rule 2 (`src/seon/fn.clj:1463-1468`) binds `?attribute` from an
   earlier clause, then matches `[?caller ?attribute ?target]`. Datahike does not pick an index for a
   join-bound attribute. It costs **519 ms / 1.47 GB for 0 edges** cold. The same clause with
   `?attribute` supplied as a scalar `:in` costs **1.1 ms / 0.4 MB**.
3. **The cold path is the ordinary path.** Datahike's result cache is weight-bounded at 1,000,000
   (`reference-code/datahike/src/datahike/query.cljc:2467`). One snapshot of whole-program
   relations weighed 840,902, and `query-cache-metrics` showed `snapshot-count 1`.
   `acquire-program!` queries the *loaded* commit's digests (`eval.clj:1916`) right before the
   branch's `reverse-closure` (`:1924`). That evicts the branch's bucket (weight fell to 318,318, and
   the next `reverse-closure` took 799 ms / 3.0 GB, against 17 ms / 53 MB warm). Every derivation
   therefore runs the rule cold.
4. **Per evaluation, the whole program is walked three times.** `turn-interns` (`eval.clj:382`)
   enumerates all 4,259 SCI Vars to find the few owned by the fork's generation. It is called
   before evaluation (`:3413`), in `definition-row` (`:472`) and in `bindings` (`:485`). Each call is
   2.3 ms / 4.6 MB. The measured `(+ 1 2)` on `exp-mcp-check` took 4 ms and allocated **9.6 MB**.
5. **Every `seon.db/q` re-decodes its whole result, even on a Datahike cache hit.** The result is
   decoded even when no find attribute is EDN-encoded (`decode-query-result`, `src/seon/db.clj:1939`).
   For the 19,232 file-reference tuples, `seon.db/q` takes 12.9 ms / 45 MB, while raw `d/q` on the
   same cached result takes 0.02 ms / 0.02 MB. The identity relation is 7 ms / 21.5 MB against
   0.02 ms. This is the "53 MB per call". It is `reverse-closure`'s five whole-program reads decoded
   per call. It is not contract arming: `seon.db/q` armed took 5.43 ms, and its unwrapped
   `:malli.instrument/original` took 5.25 ms.

The end-to-end first evaluation on a new branch (3,196 ms in the evidence) was not re-measured,
because it needs a branch write. Its components are reproduced below.

## 1. The pipeline and what each stage is proportional to

| Stage (owner) | Measured | Proportional to |
|---|---|---|
| `acquire-context!` under `(locking contexts …)` (`agent.clj:823`) | serializes every agent behind the slowest acquisition | agents × acquisition |
| `acquired-database?` (`eval.clj:2533`): revision-basis compare over acquisition attributes | not separately timed (map compares) | attribute count |
| miss → `load-core-namespaces!` (`:1397`), called in `acquire!` `:2640` | 20.9 ms / 42.6 MB | loaded namespaces |
| `loaded-program` (`:893`) `d/commit-as-db` + `vary-meta` | 2.7–3.0 ms / 5.8 MB | constant |
| `base-ctx` memo lookup (`:2476`), key = `[revision basis, loaded commit, commit-fault! fn]` (`:2444-2465`), LRU 4 | hit 2.4 ms / 6 MB (fork + loaded-program) | constant |
| `derive-base-ctx` → `build-base-ctx` (`:203`) | 1.2 ms / 1.9 MB | injected symbols |
| `acquire-program!` queries: namespaces, all 4,752 function rows (source strings) | ~8 ms / ~19 MB | program |
| `function-digests` loaded + branch (`:910`) | 7.3 + 5.1 ms / 34.5 MB | program |
| `seon.fn/reverse-closure` (`fn.clj:1599`), only when any override exists | warm 17 ms / 53 MB; **in situ 800–836 ms / 3.0 GB** | whole store (rule 2) |
| 435 namespace pulls with aliases/imports/refers (`eval.clj:1937-1955`) | 42 ms / 151 MB | namespaces |
| `install-first-party-namespaces!` (`:1421`), copying 4,259 Vars | 14.3 ms / 65 MB | program Vars |
| interpreted `install-row!` (`:945`) + per-function `install-function-contract!` (`:702`, reads effective config per function) | small at 1–4 rows | interpreted rows |
| **`derive-base-ctx` total, 0 overrides** (on the loaded db) | **76–140 ms / 252–347 MB** | program |
| **`derive-base-ctx` total, 1 override** (default today) | **916–942 ms / 3.31 GB** | store |
| `fork-cluster-ctx` (`:2563`): re-wraps every interpreted fn's contract with `db/pull '[*]` (`:2596-2601`) | per sovereign branch | interpreted rows |
| `fork-for-turn` / `regenerate-agent-context!` (`:2217`): `base-bindings` over 4,298 entries | 1.8 ms / 2.1 MB | program entries |
| `sci/fork` (`reference-code/sci/src/sci/core.cljc:345`) | 0.01 ms / 0.02 MB | constant |
| per eval: `turn-interns` ×3 | 3 × 2.3 ms / 4.6 MB; `(+ 1 2)` = 4 ms / 9.6 MB | program Vars |

A base miss happens for any commit that touches an acquisition attribute. That set is program
attributes, projection attributes, config dials and `:seon.config/cluster` (`acquisition-attributes`,
`:2336`). The key also includes the connection id and generation (`revision-basis`, `:2355`) and
the fault-recorder *function*. So identical programs in two clusters, or on a branch after its
first write, each rebuild from zero.

## 2. Root causes, ranked by measured cost

1. **Store scan in `declared-reference-edges` (`fn.clj:1520-1528`).** Datahike leaves a
   join-bound attribute variable unindexed. The whole rule set cold costs 569 ms / 1.6 GB. Rewriting
   it as scalar `:in ?attribute` queries per attribute, dispatched on `:db/valueType` (ref values
   for rules 2–3, symbol values for rule 4), costs **18 ms / 15 MB and returns the equal 26-edge set**
   (probe verified `equal? true`). Prior research saw the cost but blamed "unbound" traversal
   (`context-cost-2026-09-23.md:181`; `slow-assumptions-audit-runtime-2026-09-23.md:420` T8, 3.5 GB).
   The planner behaviour is the new finding. `test-reach-rules` (`fn.clj:1478`, used at `:1696`,
   `:1721`) inherits the same clause shape.
2. **Whole-program reads thrash a weight-bounded shared cache.** Each acquisition materializes the
   identity relation (6,859), file references (19,232), digests (4,752 × 2) and all function sources.
   These fill Datahike's 1 M weight and leave one snapshot cached. Querying the loaded commit in
   between evicts the branch. Content that did not change is recomputed because the cache is keyed
   by database snapshot and the reads are whole-program.
3. **Whole-program rebuild per program identity instead of per diff.** `derive-base-ctx` rebuilds
   the interpreter, pulls 435 namespaces, copies 4,259 Vars and recompares 4,752 digests on every
   miss. It runs at 76–140 ms even with nothing to interpret. The key is the attribute-revision basis,
   not the content that decides the context (the set of `{sym digest}` that differ from loaded).
   Config dials and the recorder identity sit in the key, although neither changes the
   interpreted program.
4. **Per-evaluation and per-turn whole-program walks.** These are `turn-interns` ×3 and
   `base-bindings`, which total 9.6 MB per trivial form. SCI already gives the answer structurally:
   `bind-root!`/`def` in a fork `assoc-in` only touched namespace maps (`sci/impl/utils.cljc:362-379`),
   so an untouched namespace map is `identical?` to the base's.
5. **Decoding copies every cached result per call** (`db.clj:1939-1968`). This costs 21–45 MB for a
   19 k-tuple result on a cache hit.
6. **Contract re-arming is per acquisition and per fork, not per content.** `compiled-wrapper`
   (`src/seon/instrument.clj:638`) runs `m/schema` + `m/-instrument` once per wrap. That is correct
   Malli use: validators are built at instrument time, and per-call cost is Malli's. But wraps repeat
   on every derive, every `fork-cluster-ctx` and every `install-function-contract!`, and each
   re-reads effective config (`eval.clj:687-700`). They close over the recorder, which is why the
   recorder is in the base key.
7. **Stamps.** A projection is `vary-meta`'d onto database values at `eval.clj:1873-1875`, `:899`
   and `:3219`. This is a "derive without stamps" violation. It hides that the projection is an
   argument.

**The one pattern:** acquisition derives the whole world from the branch value and then compares it
with the loaded world, instead of deriving only the difference. Every stage scales with program or
store size (4,752 rows / 575 k datoms). The difference is small, 1–4 rows today, and known exactly
by digest. Items 4 and 5 are the same pattern at call granularity. They walk everything to find
the few things that changed.

## 3. Target design: host base once per JVM program, override layer per diff

**Layer 0 — host base, one per loaded program.**
- **Key:** the loaded source commit (`loaded-commit-id`, `eval.clj:879`) plus the projection
  fingerprint. It is not keyed per branch, per cluster, per recorder or per config.
- **Contents:** `build-base-ctx` plus every first-party namespace with its aliases and imports
  (this also fixes defect 3 in `three-entry-defects-2026-09-24.md`), with compiled Vars copied by
  `copy-host-var` (`:189`).
- **When built:** at boot and after each adoption or reload. It costs about 100 ms / 300 MB, the
  measured 0-override derive, and after this redesign that is paid once per reload, not per commit.
- **Reload:** `require :reload` of namespaces N produces base′ = `(sci/fork base)` with
  `sci/add-namespace!` of re-copied Vars for N ∪ dependents only (`sci/core.cljc:679`). Cost is
  proportional to the reloaded namespaces.
- **Option to verify, not assumed:** installing the JVM `Var` objects directly into `:namespaces`
  (supported by fork commit 6ee57c9c, hook fired for host Vars) would make reload visible without
  re-copying. `utils/bind-root!` copy-on-writes any Var whose meta lacks this generation
  (`utils.cljc:366-379`). Probe first that `vars/getRawRoot` works on a `clojure.lang.Var` and that
  no fork write reaches the JVM root.

**Layer 1 — override layer, a pure function of content.**
- **Input:** `overrides = {sym digest}` where the branch digest ≠ the loaded digest. The loaded
  digests are memoized once per loaded commit. The branch digests come from one `:aevt` read of
  `:seon.program/definition-digest`, which is O(program) datoms but about 2 ms, well below the cache.
  The truly diff-proportional form reads the entities in the tx-data of commits since the last
  acquired commit (`d/listen` already runs, `eval.clj:2410-2419`). Datahike's `since` is the upstream
  seam; verify its cost before choosing it.
- **Closure:** `affected = gate-set-in` from the seeds. This is an AVET walk that already exists
  (`fn.clj:1531`), with declared edges read per reached target through scalar-bound attributes.
  Cost is proportional to the affected rows.
- **Layer value:** `(fork base)` plus the interpreted rows (seeds ∪ affected, in namespace order).
  It is memoized by `[loaded-commit, projection-fingerprint, sorted #{[sym digest]}]`, so two
  branches or clusters with equal overrides share one value. An empty override set is the base
  itself.
- **Why callers must be interpreted:** analysis captures Var objects, so a caller analysed in the
  base keeps calling the base Var. Compiled JVM callers never see SCI at all. That is the necessary
  and sufficient reason to include affected callers. Nothing else is interpreted.
- **SCI limits** (`reference-code/sci/README.md:773`): `deftype` has limited support and
  `definterface` is unsupported, so host-bound rows stay refused by name (`host-bound-row?`,
  `eval.clj:933`). This is correct as it stands.

**Turn start (acquisition).** Read the branch head. If its override set equals the held layer's
key, the only work is swapping the database value into the environment (µs). Otherwise, look up or
derive the layer for the new override set, then `regenerate-agent-context!` onto it (the existing
mechanism, `:2217`). Derivation cost is proportional to overrides ∪ affected. No lock over
`contexts` is needed for derivation: a `delay` in the memo cell gives single flight, as
`base-ctx` already does at `:2493`.

**Merge into a cluster.** Merge admits rows onto the cluster branch. The cluster's override set
grows by those `{sym digest}`, and its next acquisition derives only the new layer. Write-back to
files and a reload make the loaded digest equal the branch digest, so the syms leave the override
set, the layer shrinks, and the functions run compiled again. This is "local runtime vars when
they are the same".

**Config overrides as data.** Dials, caps and the fault recorder are read at call time from the
executing ctx's environment, not closed over at wrap time. The call-preparation hook already
receives the executing ctx (`sci/core.cljc:310-322`). The layer key therefore has no config and no
recorder.

**Agent-private layer.** Generation-owned Vars, defs and atoms stay in the agent's fork and are
carried by `regenerate-agent-context!` as live objects (`:2228-2275`). Find them by diffing
`sci/namespace-state` maps against the base with `identical?` per namespace, then inspecting only
namespaces that differ. That replaces `turn-interns`' 4,259-Var walk and `base-bindings`'
4,298-entry snapshot. Cost is proportional to the namespaces the agent touched.

## 4. Contracts: arm once per content

- **Core compiled functions** are armed once in the JVM by `seon.instrument`. SCI copies the armed
  root, so there is no per-agent work and it is already correct.
- **Interpreted rows** get a wrapper memoized by `[sym, spec-edn, definition-digest,
  projection-fingerprint]`, the Malli-compiled `m/-instrument` of `compiled-wrapper`, cached with
  `clojure.core.cache` like the other memos in this namespace (`eval.clj:2325-2334`). It is built
  when the layer is derived and reused by every fork. `on-core-error`, caps and the recorder are
  read from the executing ctx at the refusal branch, so they are not wrap inputs.
- **An evaluated contracted `defn`** is armed at `evaluate` (defect 4, `three-entry-defects` §4)
  from the same memo.
- **Why "53 MB per call":** the number is `reverse-closure`'s whole-program reads decoded by
  `seon.db/q`. It is not Malli (5.43 ms armed against 5.25 ms unwrapped). Fixing root causes 1 and 5
  removes it.

## 5. What to delete, sizes, and slices

**Delete (estimated about 330 lines):**
- program-identity/basis machinery: `acquisition-attributes`, `revision-basis`, `program-basis`,
  `program-identity`, `remember-program-identity!`, `watch-program-identity!`, `acquired-database?`
  lineage branch (`eval.clj:2325-2466`, `:2533-2568`, about 150 lines);
- `base-ctx-key`'s recorder/config members;
- the `fork-cluster-ctx` re-arm loop (`:2596-2601`);
- `turn-interns`/`intern-values` whole walks (`:363-415`);
- the three `vary-meta` projection stamps;
- whole-program joins in `gate-sets-in` (`fn.clj:1566-1579`) once seeds walk AVET only.

**Add (about 150 lines total):** a layer memo, an override-set derivation, a content-keyed wrapper
memo, and a namespace-identity diff.

**Slices (each ≤100 added lines, each with a before/after probe):**
1. **`declared-reference-edges` via scalar-bound attributes** (`fn.clj:1520`, about 20 lines, owner
   of `fn.clj`). Probe: `(derive-base-ctx db {})` on default. Before: **916–942 ms / 3.31 GB**.
   Expected: about 150 ms / about 350 MB. Also cold `declared-reference-edges` on `(d/db-with db [])`:
   569 ms / 1.6 GB, prototyped at 18 ms / 15 MB with an equal 26-edge set. The regression asserts
   set equality with the rule form on the live program. Datahike's planner (join-bound attribute →
   no index) goes to the datahike fork as an issue, because `test-reach-rules` has the same shape.
2. **Decode only encoded attributes** (`db.clj:1939`). If `query-find-attributes` finds none
   EDN-encoded, return the result unchanged. Probe: the file-reference query via `seon.db/q`, before
   12.9 ms / 45 MB, target about 0.1 ms.
3. **Generation diff by namespace identity** in place of `turn-interns` ×3. Probe: MCP SCI
   `(+ 1 2)` on `exp-mcp-check`, before 4 ms / 9.6 MB `:seon.eval/allocated-bytes`, target
   < 1 MB.
4. **Base keyed by loaded commit and layer keyed by override set** (the Layer 0/1 split). This
   deletes the basis machinery. Probe: acquire a branch after one program write. Before about
   940 ms (after slice 1, about 150 ms). Target is proportional to overrides: < 30 ms for 1–4 rows.
5. **Content-keyed contract wrappers**, recorder read from ctx, arming at evaluate.

Slice 1 alone removes about 85% of today's acquisition time and allocation. Slices 1–3 need no
design ruling. Slice 4 changes the base key and should go to design review first, per CLAUDE.md
step 4.

## Verification limits

- The end-to-end new-branch first evaluation was not re-run, to avoid a branch write.
- The recorder-in-key effect on sharing across clusters is inferred from code (`:2444-2465`), not
  measured with two clusters.
- The direct-host-Var base option is unverified.
- The live JVM runs archive ce7384682, so the probes measured that code, not HEAD. eval.clj hunks
  between the two are error-constructor edits.
- Profiling totals (`call-with-projection` 58 s ×76 k) are inclusive wrapper times and do not name
  a separate cost.
- Other hot spots outside SCI acquisition were observed but not analysed: `hiccup?` ×1.6 M and
  `canonical-value-string` ×3.5 M.
