---
title: "Run 22: chain 5 rerun on d8b470c57: Prove now reached :done on its error-red product in 8 calls (the F60 fix holds), but its merge carried a scratch helper seon.edit-test/rv (calls seon.sci.reader, which the test namespace does not require); write-back's analysis refused the test file, the attempt ended :error, the refusal went root → root, nothing relaunched, and the chain stopped 21 s after launch"
type: research
status: complete (lane run22, 2026-10-13); chain STALLED at Prove's merge; nothing shipped
created: 2026-10-13
---

# Run 22: the chain `seon.edit/an-unreadable-odd-map-is-not-one-form` on d8b470c57

**Broken first: an exploration helper became merged program, and its refusal is a dead end (F64, F65).**

1. Prove's call 7 (00:56:14), probing the reader in `seon.edit-test`, defined
   `(defn rv [s] (let [r (seon.sci.reader/read {…})] …))`. In SCI that defined a program entity on the branch:
   `seon.edit-test/rv` is in the branch's `:seon.fn/sym` set beside the two product deftests.
2. Prove's step ended `:done`. `db<->flow` merged and wrote back `test/seon/edit_test.clj`.
3. At 00:56:23.382, `seon.cluster.source/write-back!` refused with `:seon.source/write-back-refused :not-analyzable`:
   clj-kondo found `test/seon/edit_test.clj:471:12` "Unresolved namespace seon.sci.reader. Are you missing a
   require?". That is `rv`'s call. The test namespace never requires `seon.sci.reader`; in SCI the namespace was
   simply loaded.
4. The attempt ended `:error` with that reason. The error's one notification (`45f5ba17bf3e`) went
   **root → root**. Nothing relaunched (`:seon.task/attempts-made 1`, relaunch dial 2). Implement never started. The
   JVM was idle from 00:56:36 (`ps` 0.0 %), and the basis stayed at t 536871306 through 01:16.

`rv` is also uncontracted, so the merge's contract check would have refused it too. Lint simply came first. The
`770733432` "lint refusal relaunches" rule did not apply to this path: the write-back analysis refusal, not the
merge-lint refusal.

**What went right: F60.** On the Prove head the table ran **9 pass / 0 fail / 4 error** and the law **0 / 0 / 1**.
Both are red only by the error the subject throws, and the step reached `:done`. Before `d8b470c57`, the same
product capped three times (Run 21).

| Measure | Prove (`bbbdd44c7791`) | Implement (`6569d0dc9a1e`) |
|---|---|---|
| Assignment / branch | `2ad107668183` / `:attempt-2ad107668183` (+ `:base/…`), **kept** | never launched |
| Model calls | **8** (00:56:02.8 → 00:56:17) | 0 |
| Product | both declared deftests exactly as specified, with fixed-prose assertion messages. The law is written fully qualified (`clojure.test.check/quick-check`, `clojure.test.check.properties/for-all`, …). It also carried the stray `seon.edit-test/rv` | n/a |
| Head results | table 9/0/4, law 0/0/1, `:interpreted` | n/a |
| End | `:error` at 00:56:23.391: "The candidate file does not analyze cleanly; its namespace form may lack a require the accepted declarations use." | n/a |
| Relaunches / `send-back!` / `refine!` | 0 / 0 / 0 | n/a |

## Before launching

- Default pid 81232, booted 00:55:14 UTC from `data/source/d8b470c57cc0…` (HEAD), and reset. Health `:degraded`
  (warnings). Not stopped or reset by this lane.
- Subject `68d99c8aef55` `:held`; no attempts; `ready #{}`; branches `[:db :current-src :cluster-default]`. The
  gap was re-verified: the four odd-map sources throw and `"(a)"` → true.
- **Reaching tests, interpreted, on `run22-probe`**: the same 5 tests as Run 21. Run `18ad3c96b27a`, all
  `:interpreted`, all green (5, 5, 6, 5, 3 passes). It took 2,371 ms and 4.4 GB when cold.
- **One-test gate cost now** (`874377c5f`): `(my.test/check {:seon.test/identities
  #{seon.edit-test/selectors-refuse-ambiguity-and-use-parsed-dispatch} :seon.test/rerun? true})`, run
  `e245e77747ff`, took **212 ms and 128 MB** (warm, right after the 5-test run). The coordinator's figure was
  about 93 ms / 181 MB.
- Baseline lint of `src/seon/edit.clj` and `test/seon/edit_test.clj`: 0 errors, 0 warnings. The probe was retired.
- Config: `apply!` `{no-provider false, max-episode-runs 60}` → 4 operations, `converged? false` (C3). The
  effective values were confirmed.

## Timeline (UTC, 2026-10-13)

| At | Event |
|---|---|
| 00:56:02.65 | root transacts `:seon.task/execution :running`. This was the only hand act; no write by this lane until 01:16:04 |
| 00:56:02.8 | Prove call 1: sources of `single-form`, `single-form?` and the reader |
| :05 / :07 | calls 2-3: `(require '[seon.edit-test :as et])`, then a `require` vector in `seon.edit-test` (aliases `test-support`, `tc`, `gen`, `prop`, `z`, …) |
| :13 | call 6: probes `single-form?` on the red and green rows |
| :14 | call 7: **`(defn rv …)`** in `seon.edit-test`, a scratch reader probe that becomes a program entity |
| :17 | call 8: both product deftests |
| :22 | head run: table 9/0/4, law 0/0/1. Prove `:done` |
| :23.382 | write-back refuses `test/seon/edit_test.clj` (unresolved `seon.sci.reader` at 471:12). Error notified root → root. Attempt `:error` |
| 00:56:36 → 01:16 | JVM idle, `:open-turns 0`, no relaunch, no write |
| 01:16:04 | JVM reads; cleanup (config, `:held`) |

Another lane committed `7f96b6cba` to the shared tree at 01:04. It does not touch `seon.edit`.

## Seon git commits from this run

None. Write-back refused before the merge's git commit.

## Watch items

- **Prove `:done` on an error-red product**: **yes** (F60 fixed by `d8b470c57`).
- **`:seon.context/unmet` shown while working**: **not observed**. Prove went from writing to `:done` with no
  working reply after its product existed, and I did not rebuild its context.
- **Implement's `seon.sci.reader` require and ns-form publication; F57 on a fresh branch**: not reached.
- **HEAD lint after merge**: nothing was published. `src/seon/edit.clj` and `test/seon/edit_test.clj` still lint
  0/0. The write-back lint is what stopped the chain, correctly, since the candidate would not have analyzed.
- **One-test gate cost**: 212 ms and 128 MB, measured above.

## Correctness check

Nothing merged. `seon.edit/single-form?` still throws on odd maps on HEAD. The two product tests on the Prove branch
match the specification.

## Grades

- Hand acts after launch: **0**. Wakes: **0**. Relaunches: **0**. Calls: Prove **8**, Implement **0**.
- Platform: launch → Prove `:done` (F60 holds) → merge → write-back refusal → **dead end** (F65).
- Product: the tests are right. A scratch helper came along (F64). Nothing shipped.

## Frictions

| # | Friction (observed) | Owner | Proposed red proof |
|---|---|---|---|
| **F64 (binding, merge scope)** | A Prove agent's scratch `defn` (`seon.edit-test/rv`, defined while probing at the REPL) became a program entity and was carried by the merge with the declared product. It is uncontracted, it is not part of `:seon.task/product`, and it calls a namespace the file does not require | the Prove merge selection (`seon.cluster.source/prepare-merge!`: a Prove step merges its declared product, not every definition on its branch), or the REPL help (scratch helpers in a scratch namespace, as Run 20's chain-filing probe did) | in `seon.cluster.merge-test`, a Prove branch holding its declared product plus an undeclared helper `defn` in the test namespace merges only the product (or refuses, naming the helper) |
| **F65 (binding, unattended)** | `seon.cluster.source/write-back!`'s `:not-analyzable` refusal ends the attempt `:error`, notifies root → root and never relaunches. It is the F45 class (`ea800d326` fixed it for a missing contract), now for analysis or lint. The refusal names the exact cause the agent could fix: the offending finding at file, row and column | `seon.cluster.agent/apply-lifecycle!` (a write-back analysis refusal relaunches with the finding in its reason, as `770733432` does for merge lint) | in `seon.cluster.merge-test`, an attempt whose write-back is refused `:not-analyzable` is relaunched with the clj-kondo finding in its context |
| F57, F61 (not reached / not observed) | as above | — | — |
| F27 (improved), C3 (again) | one-test check 212 ms and 128 MB warm; the 5-test cold check 2,371 ms and 4.4 GB; `apply!` 4 then 3 operations, `converged? false` | as before | as before |

## Residue on default (disposable)

- Subject `68d99c8aef55` is `:held` (verified). Prove has one `:error` attempt; Implement has none.
- **Branches kept as evidence for F64 and F65**: `:attempt-2ad107668183` and `:base/attempt-2ad107668183`.
  `run22-probe` was retired.
- The open error `seon.cluster.source/write-back!` (00:56:23) is still open on default.
- Config restored to `no-provider true` and `max-episode-runs 100` (verified).
- Shared tree: unchanged by the chain.
