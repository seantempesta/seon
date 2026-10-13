---
title: "Run 23: chain 5 rerun on 6e8b157ed: Prove wrote both product tests in 6 calls with no scratch helper, but its law names clojure.test.check and clojure.string fully qualified, which test/seon/edit_test.clj does not require; write-back refused (:not-analyzable, now with path:row:col) and relaunched as designed, but the relaunch forked its predecessor's end commit, found the unmerged product already there and ended :satisfied with 0 calls. The product never reached main; Implement is 'ready' with no proofs; the chain stopped 14 s after launch"
type: research
status: complete (lane run23, 2026-10-13); chain STALLED; the product was lost to a :satisfied relaunch
created: 2026-10-13
---

# Run 23: the chain `seon.edit/an-unreadable-odd-map-is-not-one-form` on 6e8b157ed

**Broken first: a relaunch after a refused merge counts the unmerged product as main's (F66).**

1. Attempt 1 (`2ad107668183`, 6 calls, 01:30:18 → 01:30:28) wrote the two declared deftests and nothing else.
   `seon.edit-test`'s branch `:seon.fn/sym` set is unchanged, so there was no scratch helper.
2. Its law calls `clojure.test.check/quick-check`, `clojure.test.check.properties/for-all` and `clojure.string/join`
   fully qualified. The test namespace does not require `clojure.test.check` or `clojure.string`; SCI had them
   loaded.
3. At 01:30:31.8, write-back refused with: "test/seon/edit_test.clj:489:5 :unresolved-namespace Unresolved
   namespace clojure.test.check …; test/seon/edit_test.clj:495:24 … clojure.string … Require the namespace, or
   unmap the declaration (my.program/ns-unmap!), and request the merge again." The attempt ended `:error`, and
   `db<->flow` relaunched it as `6e8b157ed` designed: "Its merge was refused with 3 declarations main lacks:
   relaunch 1 of 2 continues from its end commit".
4. Attempt 2 (`ea899f6589e2`) forked its predecessor's end commit (`4cfa03eab`). The fork point already held the
   whole product red, so `seon.context/step-status` judged it **`:satisfied`** ("main holds its product") at
   01:30:32.5. That was **0.7 s, 0 model calls**, outcome `:closed`, and its branch was deleted.
5. Main does **not** hold the product: `(d/datoms db :avet :seon.test/sym …)` is empty for both tests.
   `seon.task/ready` answers `#{"6569d0dc9a1e"}` (Implement), but `seon.task/obligations` for Implement is
   `:proofs #{}`. Implement cannot be assigned, so nothing ran after 01:30:42 (JVM `ps` 0.0 %, `:open-turns 0`).

The relaunch's reason, which named the exact fix (require the namespaces), was never read by a model.

**What went right.**

- No scratch helper. Prove's help now says "contract or `ns-unmap!` scratch helpers", and attempt 1 defined only
  its product.
- The write-back refusal carried `path:row:col` findings and relaunched (F65 fix live).
- Model calls worked through `#'seon.ai/complete` (`7f96b6cba`): 6 calls, no provider failure.

| Measure | Prove attempt 1 (`2ad107668183`) | Prove attempt 2 (`ea899f6589e2`, relaunch) | Implement (`6569d0dc9a1e`) |
|---|---|---|---|
| Model calls | **6** (01:30:18.9 → 01:30:28) | **0** | 0 (never assigned) |
| End | `:error` at 01:30:31.805 (write-back `:not-analyzable`, 2 findings), relaunch 1 of 2 | **`:satisfied`** at 01:30:32.543, `:closed`, branch deleted | none; "ready" with no proofs |
| Branch | `:attempt-2ad107668183` (+ `:base/…`) kept | deleted | n/a |

## Before launching

- Default pid 92190, booted 01:29:26 UTC from `data/source/6e8b157eddf9…` (HEAD), and reset. Health `:degraded`.
  Not stopped or reset by this lane.
- Subject `68d99c8aef55` `:held`, no attempts, `ready #{}`, branches `[:db :current-src :cluster-default]`. Gap
  re-verified: the four odd-map sources throw.
- **Reaching tests, interpreted, on `run23-probe`**: the same 5 tests. Run `f10ace7a4e42`, all `:interpreted`, all
  green; 2,341 ms and 4.5 GB cold (F27). Retired.
- Baseline lint of `src/seon/edit.clj` and `test/seon/edit_test.clj`: 0/0.
- Config: `apply!` `{no-provider false, max-episode-runs 60}` → 4 operations, `converged? false` (C3). The effective
  values were confirmed.

## Timeline (UTC, 2026-10-13)

| At | Event |
|---|---|
| 01:30:18.74 | root transacts `:seon.task/execution :running`. This was the only hand act |
| :18.9 → :28 | Prove attempt 1, calls 1-6. Call 6 writes both deftests |
| :31.805 | write-back refuses (`clojure.test.check` and `clojure.string` unresolved); attempt 1 `:error`; relaunch 1 of 2 |
| :32.543 | attempt 2 `:satisfied` with 0 calls; closed; branch deleted |
| 01:30:42 → 01:39 | JVM idle, `:open-turns 0`. Health: one `agent-error`, one `errored-receipt` |
| 01:39 | JVM reads, about 9 min after launch. The coordinator asked for immediate reports of failure, and the chain had visibly ended (no runnable step). Cleanup at 01:40:46 |

Another lane committed `a05ba6655` (turn reds as tasks) during the window. It does not touch `seon.edit`.

## Seon git commits from this run

None.

## Watch items

- **`:seon.context/unmet` while working**: not observed, again. Attempt 1 wrote its product on its last call, and
  the `:uncontracted` part never had a helper to name.
- **Scratch helpers and `ns-unmap!`**: none were written, so `ns-unmap!` was not exercised.
- **Implement's `seon.sci.reader` require and ns-form publication; F57; HEAD lint after merge**: not reached.
  Nothing was published; both files still lint 0/0.
- **Model calls**: working through `#'seon.ai/complete`.

## Correctness check

Nothing merged. `seon.edit/single-form?` still throws on odd maps. The product on attempt 1's branch matches the
specification; only its law's fully qualified, unrequired namespaces fail analysis.

## Grades

- Hand acts after launch: **0**. Wakes: **0**. Relaunches: **1** (automatic). Calls: Prove **6 + 0**, Implement **0**.
- Platform: write-back refusal → relaunch works (F65 fixed). The relaunch's verdict is wrong (F66): it inherits the
  unmerged product as if main held it.

## Frictions

| # | Friction (observed) | Owner | Proposed red proof |
|---|---|---|---|
| **F66 (binding)** | A relaunch after a refused merge forks its predecessor's end commit, so its fork point holds the unmerged product. `step-status`'s `:satisfied` ("its fork point already held its whole product red and nothing is new: main holds its product") then closes it with 0 calls. Main never gets the product, and Implement is "ready" with no proofs, which is a dead end. The `:satisfied` rule reads the fork point as a proxy for main, and since `4cfa03eab` that proxy is false for relaunches | `seon.context/step-status` (`:satisfied` must read main's head, the merge target, not the fork point), or `seon.turn/prove-turn` for a relaunched attempt | in `seon.context-test` or `seon.cluster.merge-test`: a Prove relaunched after a write-back refusal, whose fork point holds the unmerged product, is not `:satisfied` while main lacks the product |
| F67 (help or REPL) | Prove's law used fully qualified `clojure.test.check/…` and `clojure.string/…` with no require in the test ns. SCI resolves loaded namespaces and the file analysis does not, so the REPL accepted what the file would refuse. The contract-gaps or unmet check at the REPL did not flag it before merge | the REPL's analysis of a definition against its file's ns form (clj-kondo is already joined by span; surface `:unresolved-namespace` at the defn) | a deftest using an unrequired namespace, evaluated in `seon.edit-test`, carries an `:unresolved-namespace` finding in its reply |
| F68 (minor) | `seon.task/ready` lists Implement although its obligations have no proofs, so `assign!` would refuse it | `seon.task/ready` | `ready` excludes a step whose proofs are empty |
| C3, F27 (again) | as before | | |

## Residue on default (disposable)

- Subject `68d99c8aef55` is `:held` (verified). Prove has attempt 1 `:error` and attempt 2 `:satisfied`/`:closed`.
- **Kept as evidence for F66 and F67**: `:attempt-2ad107668183` and `:base/attempt-2ad107668183`. `run23-probe`
  was retired.
- Config restored to `no-provider true` and `max-episode-runs 100` (verified).
- Shared tree: unchanged by the chain.
