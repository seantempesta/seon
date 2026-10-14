---
title: "Incremental program-context (overlay) acquisition: derive a branch's next program context from its previous one"
type: research
status: design proposal (Opus, one of two independent plans); prototype measured in the JVM, nothing installed
date: 2026-10-14
lane: overlay-opus
basis: "default pid 10712 running de1f48c23 (before root's restart at 06:09Z); HEAD c31780f9d; agent overlay-opus, branch :agent-c922d5ea9193 (deleted by root's reset)"
---

# Incremental overlay acquisition (overlay-opus)

What exists: a branch's program context is `seon.sci.eval/program-context`, one `seon.cache` entry per program content
(`seon.sci.eval/program-content-key`: `[store loaded-commit]` plus the plan digest, `projection-delta` and armed dials).
On a miss, `seon.sci.eval/derive-program-context` forks the **loaded base** and `seon.sci.eval/interpret-overlay!`
interprets the **whole plan** (`seon.sci.eval/derive-overlay-plan`: every branch-authored row, every changed namespace
row) through `seon.sci.eval/install-selected!` → `seon.sci.eval/install-row!`. The principal approach proposed: on a
miss, fork the branch's **previous** frozen program context (held by `seon.cache`, found by the key the agent's context
already holds) and interpret only the rows whose definition digest changed plus the value rows and macros in their
declared reverse closure (`seon.fn/reverse-closure`); everything else is reused as the identical analyzed root.

## 1. Measured before (default, SCI agent `overlay-opus`, requests through `seon.mcp/call` inside `seon.profile/measure`)

Branch rows: namespace `probe.ov1` with `(def d (seon.db/db))`, `(defn f1 [] 1)`, `(def v1 (f1))`,
`(def L2 (seon.ref/brief d 'seon.edit/single-form))` (refuses in the program context: `d` is the
missing-connection refusal map there), then more rows per step. The plan also carried six foreign namespace rows
inherited from main (`my.agents.root`, `user`, `sentinel.scratch`, `seon.design-prototype-test`,
`my.attempt-a7004ec2ee33`, `probe.ov1`).

| Request | wall ms (measure) | `install-row!` | `interpret-overlay!` | `record-acquisition-refusals!` | L2 occurrence count after |
|---|---:|---:|---:|---:|---:|
| `(+ 1 2)` | 120 | 0 | 0 | 0 | 1 |
| `(+ 1 3)` | 59 | 0 | 0 | 0 | 1 |
| `(defn other2 [] 2)` (unrelated) | 499 | 11 | 2 | 6 | 3 (new occurrence id) |
| `(+ 1 4)` | 71 | 0 | 0 | 0 | 3 |
| `(defn f1 [] 2)` (changed fn) | 326 | 11 | 2 | 6 | 6 |
| `(def v1 (+ 10 (f1)))` (changed value def) | 270 | 11 | 1 | 5 | 8 |
| `(defn other3 [] 3)` (unrelated, 8 fn rows) | 362 | 14 | 2 | 6 | — |

Per request on `other3`, L2's init ran and failed twice (`interpretation-failure` 4 calls, `acquisition-refusal` 4):
once in the overlay derivation and once more in the same request's other acquisition. The refusal count rises 2–3 per
unrelated change. A later reply delivered it as `:seon.error.occurrence/count 11`, then 13 on a reply that changed
nothing (the previous request's test-step recordings landing).

The overlay derivation itself, called directly in the JVM on the same plans (cache bypassed, `derive-overlay-ctx` on the
loaded base), thread allocation:

| Branch fn rows | `install-row!` | ms | MB |
|---:|---:|---:|---:|
| 9 | 16–17 | 36–50 | 77–91 |
| 10–11 | 18–20 | 55–74 | 91–113 |
| 12 | 21 | 73 | 106 |

Cost follows the whole plan: about 5 ms and 9 MB per branch row, paid on every program-changing request, by every
consumer that misses (agent acquisition, test world).

### Semantics observed (the correctness bar)

After `(defn f1 [] 5)`, the agent's own context read `v3` (defined as `(def v3 (f1))` before the change) as **5** and
`v1` as 15: a value row whose init calls a changed function re-runs. The branch behaves like files reloaded by
dependency (clj-reload), not like a REPL where `v3` would keep 2. Any incremental design must keep this: a dependent
value row re-runs.

## 2. Prototype (JVM, read-only: frozen values only, nothing recorded or written)

Per step: `P` = `program-context` of the branch head, one SCI request changes the program, then:
(a) today's `derive-overlay-ctx` on the loaded base with the full plan; (b) `derive-overlay-ctx` on **P** with the
delta plan `{::overridden Δ}` where Δ = the symbols whose `definition-digest-of` differs from P's plan, and with
Δ ∪ `(:seon.fn/affected (seon.fn/reverse-closure Δ))` removed from P's `::interpreted` (the "already interpreted" set).
Values read back by forking each result and evaluating the symbols.

| Change | Δ | (a) rows / ms / MB | (b) rows / ms / MB | (a) values = (b) values |
|---|---|---|---|---|
| `(defn other6 [] 6)` | other6 | 17 / 49.7 / 91.5 | **1 / 7.9 / 13.1** | yes |
| `(defn f1 [] 8)` | f1 | 17 / 45.3 / 81.4 | **3 / 18.1 / 26.4** (f1, v1, v3) | yes: v1 18, v3 8 |
| `(defn g1 [] (+ 100 (f1)))` | g1 | 18 / 59.6 / 109.1 | **1 / 8.7 / 15.8** | yes |
| `(def v4 (g1))` | v4 | 20 / 73.7 / 112.8 | **1 / 11.6 / 19.2** | yes |
| `(defn f1 [] 9)` | f1 | 20 / 55.4 / 91.5 | **4 / 30.0 / 30.6** (f1, v1, v3, v4; g1 rebound lazily) | yes: v4 109 through g1 → f1 |
| `(defn other7 [] 7)` | other7 | 21 / 73.4 / 106.4 | **1 / 12.9 / 15.8** | yes |

In (b) L2's init never ran (`interpretation-failure` 0); its refusal stayed on the acquisition as P's agent mistake
(`::agent-mistakes` count 1), carried, not reproduced.

**The naive delta is wrong, and the prototype shows how.** With Δ passed but P's `::interpreted` left as is,
`(defn f1 [] 7)` installed **0** rows and the result still answered `f1` 5, `v1` 15, `v3` 5: a stale Var (the F57
class). `interpret-overlay!` treats every symbol in `::interpreted` as already current (`already`), which is true only
for a late-bound function root, never for the changed row itself, a value row (its init ran against the old callee) or
a macro (its expansion is baked into callers). The design below states that rule in `interpret-overlay!`.

Not measured: the plan derivation (`derive-overlay-plan`, reads ∝ authored rows) is outside both columns; the
remaining 8–13 ms of (b) for one row is fork, two `reverse-closure` walks (mine and `interpret-overlay!`'s), namespace
entry and `freeze-program`.

**Isolation verified after root's restart** (pid 88215, HEAD, agent `overlay-opus2`, branch `:agent-ee6b768e1a88`):
with `f1`, `g1 = (+ 100 (f1))`, `v1 = (f1)`, `v4 = (g1)`, then `(defn f1 [] 9)`, deriving q from P installed 3 rows in
25.4 ms; q answered `f1` 9, `g1` 109, `v1` 9, `v4` 109, and **P still answered** `f1` 1, `g1` 101, `v1` 1, `v4` 101.
This is SCI's `sci.core/fork` contract (a fork renews the generation and writes no shared Var in place; per-context
bindings live in `:sci/var-bindings`, read by `sci.impl.vars/context-binding`); the Phase 2 proof asserts it.

## 3. Two further findings from the probe

1. **A program context is not a function of its content today: an init reads the deriving thread's bindings.** In
   (a), derived on my root JVM thread (which binds `seon.db/*conn*`), `L2` installed **successfully**; derived inside
   an agent request it refuses. The cached value under one content key therefore depends on which thread derived it
   first. `::custody {}` clears the context's custody but not the thread's `seon.db/*conn*` /
   `seon.db/*read-database*`. Fix at the owner: derivation (`derive-base-ctx`, `derive-overlay-ctx`) runs with those
   bindings absent (`binding` to nil), so `(def d (seon.db/db))` refuses identically everywhere. This matters more
   once inits run once per content.
2. **One acquisition records its refusals twice.** `seon.sci.eval/base-ctx` calls `record-acquisition-refusals!`
   on the source's acquisition unconditionally (its custody is the branch connection from `copy-base-ctx`), and its
   caller `seon.sci.eval/acquire!` records the same acquisition again through `record-refusals-once!`
   (6 `record-acquisition-refusals!` calls per program-changing request). Two producers of one fact; delete the one in
   `base-ctx` and let `base-ctx`'s callers that hold custody record once (they already do: `acquire!`; the others are
   listed in the dig, §1).

## 4. Design

### 4.1 Data flow

```
agent ctx snapshot ::program-key (program-key-input, already held)
  └─ base-ctx (arm-request gains ::predecessor = that key)
       └─ program-context db  ──hit──▶ value (unchanged)
                    │miss
                    ▼
       predecessor P = (seon.cache/present ::program-context
                         (seon.cache/present ::program-key predecessor))   ; two presence probes, no work
       plan q = derive-overlay-plan (now also returns ::held {sym digest})
       Δ = diff of q's ::held / ::namespaces / ::tests against P's (kept on P's ::acquisition as ::plan)
       reusable? P present ∧ same cluster-base-key ∧ same projection-delta ∧ same armed dials
                 ∧ Δ has no removed row, no removed namespace, no macro
       reusable? ─yes─▶ derive-overlay-ctx P … Δ          ; cost ∝ |Δ| + eager closure of Δ
                 └no──▶ derive-overlay-ctx base … q        ; today's path, unchanged
       value kept under q's content key (unchanged key)
```

No new cache kind, no stamp, no atom: the predecessor is found by the key the agent's context already holds; an
evicted predecessor costs today's derivation, never a wrong value. The content key is unchanged, so equal programs on
two branches still share one entry (whichever derived it first).

### 4.2 The rule: when a row re-interprets (correctness argument)

From P (program p) to q, with Δ = rows whose definition digest differs:

- **The Δ rows themselves**: always (digest includes the namespace resolver context their usages resolve through,
  `seon.program/definition-digest` / `used-resolver-context`, so a namespace binding change that alters a row's
  resolution puts the row in Δ).
- **Interpreted functions in reverse-closure(Δ)**: no work. They were analyzed against Var objects; a fork keeps Var
  identity, and the new root is the forked context's binding, read at call time. Measured: g1 sees f1 9.
- **Compiled callers in reverse-closure(Δ)** (JVM roots): bound lazily as today (`bind-lazy-members!`), unless P
  already interpreted them.
- **Value rows and macros in reverse-closure(Δ)**: always re-run, whatever P interpreted: an init evaluated its callees
  at install; a macro body called them at expansion. Measured: v1, v3 and (transitively, via g1) v4.
- **Everything else**: the identical root from P. An unchanged value row's init therefore runs once per content of
  itself and of its declared callees, and its refusal is produced once.
- **Not incremental, derive from the loaded base** (today's path): a removed row or namespace (callers analyzed
  against it would keep a Var whose base derivation would refuse to resolve), a changed or reverse-reached **macro**
  (its callers' expansions are baked; their re-analysis is the general case of reload), a different loaded commit,
  projection delta or armed dials. These are correctness fallbacks to the one existing derivation, not a second path;
  counted: main holds **zero** agent-authored macros (`:seon.fn/macro? true` with admission `:agent`, 06:15Z); removals per day are not yet counted (count before Phase 2) — if removals are common, the
  next step is to re-analyze reverse-closure(removed) on P, not a third mechanism.

Dependents come only from declared edges (`seon.fn/reverse-closure` over `:seon.fn/calls`, value-def inits included),
never symbol scanning. What edges cannot see: an init that reads host state (time, an atom, a database through a
host Var). It runs once per content, exactly as a Clojure file not reloaded keeps its value; with finding 1 fixed it
cannot read the evaluation's bindings at all.

**Path dependence, stated.** Two derivation histories of one content can differ only in (i) which unchanged callers
are lazily interpreted rather than compiled (same behavior: interpreted source equals the compiled source at the loaded
commit) and (ii) an init that reads host state. Both are already true of today's cache (whoever derives first wins).

### 4.3 Recording: once per production

With Δ-only interpretation, P's refusals are carried on q's acquisition. `record-refusals-once!` keys by
`[program-revisions target]`, which an unrelated defn moves, so it would record the carried refusal again. Key it by
the production instead: each refusal carries `::produced-in`, the content key of the derivation that ran the row
(set once in `interpret-overlay!` when the refusal is made); the recording key is `[::produced-in row target]`. A
re-run (an input changed) is a new production and records once, which is a real recurrence; a carried refusal records
nothing. This is not a filter beside a re-run: the re-run is gone, and the key names the event that happened once.

### 4.4 Option 3: deliver in the defining reply — yes

Today `(def L2 …)` returns clean and the refusal surfaces one or more replies later. The request that defines L2
already derives q's program context (its test step; `derive` 12.6 ms and `interpret-overlay!` 1 in that reply's cost).
With 4.3, the refusals whose `::produced-in` is q's key and whose row is among this request's changed rows are exactly
the request's own new mistakes: attach them beside the value in that reply's errors (the one-path fold's error
delivery), at no extra derivation. Recommended, as Phase 4; it needs the derivation to finish before the reply, which
the test step already does on a program-changing request (verify for an unassigned agent with no test step: there the
acquisition happens on the next request, and delivery stays there).

### 4.5 Costs (proportional to)

| Operation | Today | Proposed |
|---|---|---|
| program-changing request, overlay derivation | whole plan: ∝ branch rows + changed namespaces (≈5 ms, 9 MB per row; 45–74 ms, 77–113 MB at 9–12 rows) | ∝ |Δ| + value rows/macros in reverse-closure(Δ) (8–13 ms, 13–19 MB for one row; 18–30 ms for a fn with 2–3 dependent values) |
| finding the predecessor | — | two `seon.cache/present` probes, O(1) |
| plan derivation | reads ∝ authored rows (unchanged) | same; a later step can diff from P's commit through Datahike history |
| unrelated defn, refusal recordings | 2–3 occurrences, 4 failed inits | 0 |
| non-program request | cache hit, no derivation | unchanged |

## 5. Phases, each with its red proof

1. **Derivation reads no thread bindings** (`derive-base-ctx`, `derive-overlay-ctx` bind `seon.db/*conn*` and
   `seon.db/*read-database*` to nil). Red deftest: deriving a program holding `(def d (seon.db/db))` under a bound
   connection yields the same refusal of `d`'s dependent as unbound (today: installs). Delete nothing; small.
2. **Δ derivation from the predecessor.** `derive-overlay-plan` returns `::held`; P keeps its plan on `::acquisition`;
   `base-ctx`/`program-context` take `::predecessor`; `interpret-overlay!`'s `already` excludes Δ and the eager closure
   members. Red proofs: a `seon.profile/measure` assertion on the end-to-end probe (`seon.mcp/call` of an unrelated
   `defn` on a branch holding ≥8 rows): `(calls-of m 'seon.sci.eval/install-row!)` ≤ 2 (today 11–14) and the
   request's `derive` + `test-step` stages below their parent's; plus a table deftest (green today, guards the stale
   case the prototype produced): f1 / v1 / g1 / v4 values after each change equal the from-base derivation, and P's
   own `f1` is unchanged after deriving q from it.
3. **Record once per production; delete `base-ctx`'s second recording.** Red deftest
   `an-unrelated-defn-leaves-a-value-rows-refusal-count-unchanged` (the 8ad2a751b task's proof): through
   `seon.mcp/call`, define `d` and `L2`, read L2's occurrence count, `(defn other [] 2)`, assert the count unchanged
   (today +2 to +3). Retires task `a-program-change-reruns-every-branch-value-rows-init`.
4. **Defining-reply delivery.** Red deftest: the reply of `(def L2 …)` carries L2's install refusal in its errors.

Phases 2 and 3 land together if root prefers one commit: Phase 3's proof is red until both are in (Phase 2 alone
carries the refusal and records it per program; Phase 3 alone keys a production that still repeats per change).

## 6. What disappears

- The per-change whole-plan re-interpretation (≈5 ms, 9 MB per branch row per program-changing request per missing
  consumer), and with it the repeated inits and their repeated refusals.
- `base-ctx`'s unconditional `record-acquisition-refusals!` (a second producer of one fact).
- `record-refusals-once!`'s `[program target]` key and its "an evicted entry records again" recurrence note.
- The plan's opaque `::digest` input list becomes `::held`, from which the digest is computed.
- Later (not in these phases): `interpret-affected!` is the same Δ rule applied to the agent's own evaluated rows; once
  Phase 2's rule is in `interpret-overlay!`, its special `::evaluated` handling can collapse into Δ.

## 7. Risks

- **Cache pressure.** `seon.cache` was at 503 of 512 MB at the probe's start: an evicted predecessor falls back to the
  full derivation (correct, slower). The fork shares P's persistent env structurally, so q's retained weight is its
  delta; `overlay-weight` should charge q only its own interpreted rows, not P's.
- **Retention chain.** q's env is a persistent map built from P's; q holds no reference to the P object, so P is
  evictable independently. Vars created in P are shared by q by design (fork contract).
- **Macro and removal fallbacks** keep today's cost on those changes; measure their frequency first.
- **Host-state inits** run once per content: an owner-visible semantic (Clojure's), stated in the value-def docs.

## 8. Owner questions

1. Accept "an unchanged init runs once per content of itself and its declared callees" (clj-reload semantics), with
   finding 1 making the program context independent of the deriving thread?
2. Removals and macros fall back to the full derivation: acceptable until measured frequent?
3. Option 3 (deliver at definition): recommended yes, Phase 4.

## 9. Cleanup and limits

Probe agent `overlay-opus` (branch `:agent-c922d5ea9193`, namespace `probe.ov1`) was deleted by root's reset at
06:09Z; the verification agent `overlay-opus2` (`:agent-ee6b768e1a88`, `probe.ov2`) has its retirement recorded (`my.agent/retire-branch!` answered `:my.agent/retiring? true`; deleted once its run exits). Allocation numbers are thread allocation of the deriving thread (`seon.profile/measure`); request wall times
are JVM-wide windows on a shared machine with other lanes running. No source, schema or test was changed.
