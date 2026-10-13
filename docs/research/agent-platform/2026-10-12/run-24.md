---
title: "Run 24: chain 5 rerun on 371a754df: Prove merged and published its two product tests (62eae1983, with auto-required namespaces in the ns form; lint 0/0), the first time chain 5 got past Prove. Implement wrote a correct-looking single-form in 39 calls, about 25 of them spent looking for a way to see or change the ns form; its REPL requires (including (require 'seon.edit) inside seon.edit) accreted into the ns form as a self-require and duplicate entries; write-back refused the file :not-equivalent (every declaration offending), the attempt ended :error, and nothing relaunched"
type: research
status: complete (lane run24, 2026-10-13); chain STALLED at Implement's write-back; observations only, per the night audit
created: 2026-10-13
---

# Run 24: the chain `seon.edit/an-unreadable-odd-map-is-not-one-form` on 371a754df

Per `docs/research/agent-platform/2026-10-13/audit-night.md`, this is the last per-run check before a lifecycle
design round. It reports what happened and proposes no patches.

**Broken first.** Implement's attempt `8667f9088300` ended `:error` at 01:59:40.771. The reason was
`seon.cluster.source/write-back!` `:seon.source/write-back-refused :not-equivalent` on `src/seon/edit.clj`, "The
reindexed candidate does not declare exactly the accepted program". `:seon.error/offending` lists **every**
declaration in the file (21 identities, `[:seon.ns/name seon.edit]` among them), which means the reindexed candidate
declared none of them as accepted.

The branch's `seon.edit` ns form at the attempt's end was:

```clojure
(:require [rewrite-clj.zip :as z]
          [seon.error.refusal :as error]
          [seon.edit]
          [seon.sci.reader]
          [seon.sci.reader]
          [seon.sci.reader :as reader])
```

It held a **self-require** `[seon.edit]` and two bare duplicates of `[seon.sci.reader]` beside the intended alias.
They match the agent's own REPL forms: `(require 'seon.edit)` (08:58:10 local, call 6), `(require 'seon.sci.reader)`
(call 7, and again later), then `(require '[seon.sci.reader :as reader])`. Its `:seon.ns/requires` fact reads
`[rewrite-clj.zip seon.error.refusal seon.sci.reader]` and omits the self-require. The attempt was not relaunched.
The error remains open, notified to root. The JVM was idle from about 02:00 (`:open-turns 0`).

**What went right.**

- **Prove reached main for the first time on chain 5.** Prove merged (`:done`, 3 declarations) and published
  `62eae1983` (`test/seon/edit_test.clj` +46/−1). The two product tests are exactly as specified.
- **Auto-require (`68fd72410`) worked for Prove.** Its fully qualified `clojure.test.check/…` and `clojure.string/join`
  were added to the published ns form as `[clojure.string :as str]`, `[clojure.test.check.generators]`,
  `[clojure.test.check]` and `[clojure.test.check.properties]`.
- **HEAD lint after the merge**: `src/seon/edit.clj` and `test/seon/edit_test.clj` give 0 errors, 0 warnings.
- **F66 fix (`941fb2814`)**: not exercised, because nothing was relaunched.

| Measure | Prove (`bbbdd44c7791`) | Implement (`6569d0dc9a1e`) |
|---|---|---|
| Assignment | `2ad107668183`, `:done`, `:merged` (3 declarations), git `62eae1983` (01:58:00), branch deleted | `8667f9088300`, **`:error`** (write-back `:not-equivalent`, 01:59:40.771), branch `:attempt-8667f9088300` (+ `:base/…`) kept |
| Model calls | not counted (branch deleted; fast: launch 01:57:40 → git 01:58:00) | **39** (01:58:00 → 01:59:35) |
| Product / change | table (13 rows) and law; the table's message is `(str "… for " source)`, prose plus a value | `single-form` reads with `reader/read` after `parse-root` and refuses unreadable source with the form-count refusal (the refusal is duplicated in two branches of the body). Contract unchanged. The ns form was corrupted as shown above |
| Relaunches / `send-back!` / `refine!` | 0 / 0 / 0 | 0 / 0 / 0 |

**How Implement spent its calls.** About 25 of the 39 calls looked for a way to *see or change the `seon.edit` ns
form*. Among them: `(my.program/source 'seon.edit/ns)` (four times), `(clojure.repl/source seon.edit/ns)`,
`(macroexpand '(ns seon.edit))`, `(-> #'seon.edit/ns)`, `(my.program/ns 'seon.edit)`, `(my.program/diff …)`,
`(my.program/ns-diff …)`, `(my.program/declaration-diff …)` and `(my.program/breaks …)`. Several of these name
functions that do not exist. Its replies repeat "Let me check the ns form" on almost every call from 3 to 30. Late
(call 36) it noticed "the ns currently has broken requires (duplicate `[seon.sci.reader]` entries, an accidental
`[seon.edit]` self-require)" and went on to redefine `single-form` without repairing them.

## Before launching

- Default pid 99642, booted 01:56:23 UTC from `data/source/371a754dfbc2…` (HEAD), and reset. Health `:degraded`.
  Not stopped or reset by this lane.
- Subject `68d99c8aef55` `:held`, no attempts, `ready #{}`, branches `[:db :current-src :cluster-default]`. Gap
  re-verified: the four odd-map sources throw.
- **Reaching tests, interpreted, on `run24-probe`**: the same 5 tests. Run `919f590c94d0`, all `:interpreted`, all
  green; 2,509 ms and 4.8 GB cold (F27). My `(def reach …)` in `user` drew an `:auto-required #{my.program
  seon.edit}` finding, and `(def run1 (my.test/check …))` drew `#{my.test}`: auto-require also fires on scratch
  `def`s in `user`.
- Baseline lint of both `seon.edit` files: 0/0. Probe retired.
- Config: `apply!` → 4 operations, `converged? false` (C3). The effective values were confirmed.

## Measurement for `68fd72410` (on `run24-probe`)

In a namespace that already requires `clojure.string` (`(ns run24.scratch (:require [clojure.string :as str]))`), I
timed a contracted `defn` (`[:=> [:cat :string] :string]`, body `str/upper-case`):

| Request | `:seon.cost/ms` | evaluate | derive | settle | test-step | finding |
|---|---|---|---|---|---|---|
| first `defn shout` | **348.7** | **14.6** | **13.6** | 171.6 | 64.6 | none (no `:auto-required`, correctly) |
| redefinition (`str/trim (str/upper-case s)`) | **221.0** | 10.2 | 12.0 | 119.0 | 24.2 | none |

The coordinator's "before" was 450 / 34.5 / 40.6 ms. Settle is now the largest stage.

## Timeline (UTC, 2026-10-13)

| At | Event |
|---|---|
| 01:57:40.26 | root transacts `:seon.task/execution :running`. This was the only hand act |
| 01:58:00 | Prove merged and published, git `62eae1983` |
| 01:58:00 → 01:59:35 | Implement, 39 calls (see above) |
| 01:59:40.771 | write-back refuses `src/seon/edit.clj` `:not-equivalent`; Implement `:error`; no relaunch |
| 02:00 → 02:07 | JVM idle, `:open-turns 0`. Health: `open-fault 1`, `agent-error 1` |
| 02:07:47 | JVM reads, 10 min after launch. The chain had visibly ended with no runnable step, as in Run 23. Cleanup at 02:10 |

## Seon git commits from this run

```
commit 62eae1983e2db84f81b447d576724ed6c04e3b3a
    Merge seon.edit/prove-an-unreadable-odd-map-is-not-one-form from agent attempt/2ad107668183: merged on main
 test/seon/edit_test.clj | 47 ++++++++++++++++++++++++++++++++++++++++++++++-
 1 file changed, 46 insertions(+), 1 deletion(-)
```

## Watch items

- **Prove reaching main**: yes. The product is on HEAD, with auto-required namespaces in the ns form.
- **Implement's `seon.sci.reader` require and ns-form publication**: the require reached the branch's ns form, but
  as one of four accreted entries, among them a self-require. Write-back refused the file, so nothing was published.
- **HEAD lint after the merge**: 0/0.
- **F57 on a fresh branch**: not applicable. No function merged, and the merged tests are new declarations, not
  changes to a compiled Var.
- **One-test gate cost**: not re-measured. The 5-test cold gate took 2,509 ms.

## Correctness check

Nothing of Implement's merged. `seon.edit/single-form?` still throws on odd maps on HEAD, and HEAD's new tests are
red there, as designed. Implement's branch body reads the source once and refuses unreadable source before
`location-sexpr`. It was not run here; the branch is kept.

## Observations for the design round (no patches proposed)

| # | Observed | Where it lives |
|---|---|---|
| O1 | REPL `require` calls inside a namespace accrete into that namespace's merged ns form, including `(require 'seon.edit)` from inside `seon.edit` (a self-require) and repeated bare requires. The resulting file does not reindex to the accepted program | ns-form derivation from REPL requires (`68fd72410` auto-require plus the existing require recording) |
| O2 | A write-back `:not-equivalent` refusal ends the attempt `:error` and does not relaunch. The `:not-analyzable` refusal now does (Run 23), so the two write-back refusals take different lifecycle paths | `seon.cluster.agent/apply-lifecycle!` |
| O3 | The `:not-equivalent` refusal names every declaration as offending, not the ns form that caused it | `seon.cluster.source/write-back!` |
| O4 | An agent has no tool to show or edit an ns form at the REPL. Implement spent about 25 of 39 calls looking for one, several times calling `my.program` functions that do not exist | `my.program` surface; help |
| O5 | Auto-require also fires on scratch `def`s in `user` (`#{my.program seon.edit}`, `#{my.test}` on this lane's probe) | `68fd72410` |
| O6 | Prove's table message interpolates the source (`(str "… for " source)`): the F56 class, merged | Prove help / merge lint |
| O7 | C3 (`apply!` `converged? false`) and F27 (cold gate 2.5 s / 4.8 GB) persist | as before |

## Residue on default (disposable)

- Subject `68d99c8aef55` is `:held` (verified). Prove is `:merged`; Implement has one `:error` attempt.
- **Kept as evidence for O1-O3**: `:attempt-8667f9088300` and `:base/attempt-8667f9088300`. `run24-probe` was
  retired. `:agent-a2f5c739968e` is not this lane's.
- The open error (`write-back!`, 01:59:40) is still open.
- Config restored to `no-provider true` and `max-episode-runs 100` (verified).
- Shared tree: `62eae1983` is on `refactor/agent-platform`. Its two tests are red on HEAD until Implement's fix
  merges.
