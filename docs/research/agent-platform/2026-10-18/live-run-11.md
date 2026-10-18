---
title: "live run 11: a mix of four chains on 5e8426095, two green (one merged), median 23 turns to green"
type: evidence
status: "run complete 2026-10-18: 2 of 4 done (render test fix merged at call 23; fork point done at call 32, then refused by the final merge), 2 capped; cached share 87%; no heap crossing (old gen peak 798 MB of 4,096); help fix 393b1e74a; three defects filed red (039a5cc75, 9bf6358ce, 1201c92d6); my.root/turns-report fails after a restart (filed, owner is the Datahike fork)"
---

# Live run 11: a mix of four chains, attempt 1

Lane live-run-11 (Opus). Default JVM pid 45451, started 06:39:51 UTC at 5e8426095. There was one root act, an SCI
request at 06:44:12 UTC with four `seon.task/chain!` calls in run 10's form:
- template `seon.step.implement/template`;
- chain config `{:seon.config.ai/no-provider :seon.config/absent :seon.config.run/max-episode-runs 40}`;
- model `deepseek-flash` and `:seon.config.agent/max-running` 4, main's effective values;
- task names carry the suffix `-run-11`.

Three plans and proofs came from the filed tasks on main. The fourth task was new, and its plan was written inline in
the same request (below). It is not filed, because its chain merged it.

Everything else was JVM-mode reads: `seon.mine/grade`, `seon.context/step-verdict` at each call's commit, the error
facts and MemoryPoolMXBean. There were five SCI requests as this lane's own agent `live-run-11-probe`, on its own
branch, to reproduce Broken 4. Hand acts on the agents: 0. All four chains ended by 06:46:30 UTC, about two minutes
after the start. Scratch output is in `tmp/live-run-11/`.

## The metric

`(my.root/turns-report)` refused on default: `seon.mine/top` rejects its pairs (Broken 3). The numbers below are
`seon.mine/report` over `seon.mine/grades`, with each stored tuple turned back into a vector.

| window | attempts | green | median turns | median / p90 turns to green |
|---|---|---|---|---|
| ab89888ef (run 10, from 05:59:12 UTC) | 4 | 0 | 40 | none |
| 5e8426095 (run 11, from 06:45:01 UTC) | 4 | **2** | 32 | **23 / 32** |
| whole post-nuke series | 8 | 2 | | 23 / 32 |

Both "green" attempts ended `:done`, but only one is a good solution on main:
- render, at 23 turns, merged as e061bbed3;
- fork point, at 32 turns: the done rule passed it, and root's final merge then refused it (Broken 1).

The report's top waste rules were `:re-read` 24 forms, `:redundant-in-ns` 11 and `:repeats-error` 5. Its top
producers were `my.program/render-source-ai` 17, `seon.context/current-state` 11 and `seon.render.value/show` 5.
I read the forms behind them: as in run 10, these are mostly model re-reads. The producers that cost this run most
are below, under Producer attribution, and none of them is in that list.

## Task selection

The screen was one check JVM: `bin/seon check --at HEAD` on `seon.program-test`, `seon.cluster.registry-test`,
`seon.error-test`, `seon.render.ns-test` and `seon.supplied-documentation-test`. Run `53771d1118d1`: 174 tests, 12
red, 60 s.

| chain | task | kind | proof at HEAD | other reds in its reach |
|---|---|---|---|---|
| `800bf0a0469d` render | `seon.render.schema/the-graph-node-test-reads-a-retired-plan-schema-form-run-11` (new, plan inline) | ordinary: a test reads `:seon.task/plan`'s form as `[:string {:min 1}]`; the registered form now carries a `:description` | `seon.render.ns-test/task-and-schema-entities-render-as-graph-nodes` red in four assertions (lines 192, 197, 202, 215) | none (the only red in `seon.render.ns-test`) |
| `79389614d0d3` program | `seon.program/a-declared-attribute-without-a-registered-schema-is-refused` | ordinary: producer or retired assumption | both proofs error, "The attribute has no registered schema." | `typed-cross-namespace-deletion-...` and `indexed-and-evaluated-...` are red in the same namespace, reached only if the fix changes `seon.program` |
| `76421b37515d` fork | `seon.cluster.registry/the-fork-point-is-walked-for-on-every-request` (retry, plan carries run 10's lessons) | cost | red 42 against 3 | none |
| `cd8a339333ef` unres | `seon.error/an-unresolved-symbol-request-costs-thirty-megabytes` (retry) | cost | red 11,072,144 bytes against 2 MB | two `seon.error-test` reds; run 10 showed they do not reach `nearest-declared` |

Rejected:
- `seon.config/a-test-clusters-seed-config-lacks-a-result-cap-dial`: its third proof is a long `seon.cluster.agent-test`
  member, and its producer may be a compiled configuration default.
- `seon.documentation/doc-in-shows-schema-internals-in-a-doc-tree`: as in run 10, it reaches its subject only through
  compiled MCP code.
- `seon.error-test/error-declared-schemas-persist-...` and `preparation-and-recording-preserve-every-instrumentation-arm`:
  no task, and they are written in retired words (`:seon.turn/error`).

No new red proof was needed before the start, so there was no restart.

## Summary

Wall is first call opened to last call closed. Tokens are prompt / cached / completion.

| chain | end | calls | wall | per call | tokens p / c / out | cached | forms (in-ns) | first defn | wasted calls | agent errors | eval ms / MB |
|---|---|---|---|---|---|---|---|---|---|---|---|
| render | **done at 23, merged** e061bbed3 | 23 | 48.7 s | 2.1 s | 340,650 / 288,768 / 5,068 | 85% | 40 (3) | form 31 (call 17) | 6 | 3 | 329 / 490 |
| fork | **done at 32**, final merge refused `:one-file` | 32 | 101.9 s | 3.2 s | 864,631 / 749,568 / 11,141 | 87% | 76 (3) | form 55 (call 21) | 3 | 4 | 920 / 926 |
| unres | **capped**, proof green at call 38 | 40 | 99.5 s | 2.5 s | 987,578 / 859,392 / 11,300 | 87% | 73 (4) | form 25 (call 10) | 14 | 16 | 1,791 / 769 |
| program | **capped**, proofs red | 40 | 81.4 s | 2.0 s | 925,844 / 808,704 / 6,765 | 87% | 71 (3) | form 50 (call 28) | 11 | 9 | 437 / 447 |

Totals:
- 135 calls and 3,118,703 prompt tokens, of which 2,706,432 were cached (**87%**);
- 34,274 completion tokens;
- 260 forms, 13 of them `in-ns` (5.0%);
- 34 wasted calls (by the grade's rule) and 32 agent errors;
- 331.5 s of summed wall;
- 3.5 s of evaluation and 2,631 MB allocated.

Classes:
- read 93 and re-read 10;
- search 27;
- probe 47;
- progress 20;
- navigation 13;
- error 25, contract refusal 10 and unresolved 10;
- repeat 5.

The start request itself cost 1,105 ms and 598 MB of evaluation (request 1,227 ms, 1,278 MB, 71,967 calls), as run
10 measured (Broken 6 there). It is still unfiled, because its fixture test is green.

## Broken things first

1. **The fork-point chain was done, and the final merge refused it.** Its proof was green at call 32, and the done
   rule found no regression. Root's merge then refused `:seon.source/write-back-error`, "Each change needs its source
   and one file". It kept `merge-46932050e1be` and wrote a held merge step,
   `the-fork-point-is-walked-for-on-every-request-run-11-2`.

   The cause, read from the kept branch: the merge identities were `[:seon.ns/name user]`,
   `[:seon.ns/name seon.cluster.registry]` and `[:seon.fn/sym seon.cluster.registry/fork-point]`. At form 49 (call 17)
   the agent ran `(require 'seon.test-support)` in `user`, as help step 4 taught. `user` has no file.

   There are two producers:
   - **Help taught the change.** Fixed in 393b1e74a: help now loads the fixtures from the agent's own
     `my.attempt-<key>` namespace. `seon.cluster.source/merge-changes` drops that namespace as agent-owned, so it
     never merges. Proof: two new rows in `seon.bootstrap-test/help-teaches-removing-what-a-branch-does-not-keep`,
     green in the commit's check (run `e58ff5f88c27`).
   - **The done rule cannot see what the merge refuses.** `seon.cluster.source/publication` runs `proven-files` but
     keeps only its lint, so a write-back refusal from the file proof is dropped before `done-refusals` reads it.
     Filed red (039a5cc75): task `seon.cluster.source/the-done-judgment-passes-a-change-the-merge-refuses-to-write`,
     proof `seon.cluster.write-back-test/a-change-to-a-namespace-with-no-file-is-refused-by-the-done-judgment`. The
     proof is red on the lane branch because the publication carries no `:one-file`; its row asserting that the
     merge identities hold `user` is green.

   The design the chain reached holds the fork point in `seon.cache` under the fork entity's `:seon.branch/fork-id`,
   read from the branch's own database (`datahike.versioning/branch-as-db`). That fixes run 10's name-keyed
   regression. It calls `datahike.versioning` directly outside `seon.db` and releases the database in a `try`. Review
   both at the merge step.
2. **A request that redefines a caller and then unmaps its helper loses everything.** At call 25 the unres chain
   did what help says (callers first): it redefined `seon.error/candidate-names*`, `alias-target` and
   `nearest-declared` off `seon.error/candidate-names`, then unmapped `candidate-names`, all in one request.
   `seon.turn.batch/removal-facts` refused "repair the named referrers first", because it reads the surviving
   referrers from the request's basis, where the old `nearest-declared` still calls `candidate-names`. The refusal
   dropped the whole settlement, the three redefinitions with it. Each form's reply still showed its value, with only
   "Not settled" in the error. The agent redid the definitions at call 38.

   Reproduced as `live-run-11-probe`: `(defn caller-a [x] (+ x 2)) (my.program/ns-unmap! 'lr11.scratch/helper-b)`
   in one request is refused, naming `caller-a`. The same two forms in two requests are accepted. Filed red: task
   `seon.turn.batch/a-removal-judges-referrers-from-before-its-own-request`, proof
   `seon.repl-differential-p1-test/a-caller-redefined-then-its-helper-unmapped-in-one-request-stores-both` (red: the
   helper stays stored).
3. **`my.root/turns-report` fails on default after a restart.** `seon.mine/top` refuses `java.util.Arrays$ArrayList`.
   Of the 8 grades on main, run 10's 4 (written before the restart and read back from storage) hold
   `Arrays$ArrayList` rules, and run 11's 4 hold vectors. `:seon.grade/rules` is `:db.type/tuple`, cardinality many.
   The fork's 6cb35744 made two lists order like vectors, but a stored tuple still reads back as a list, so every
   tuple attribute changes class across a restart.

   The owner is the Datahike fork's read of a stored tuple. Filed as `seon.mine/the-turns-report-refuses-grades-read-back-from-storage`
   with no Seon proof: its proof belongs beside `datahike.test.list-value-order-test` in the fork, and a fork bump goes
   in root's restart batch. Until then the report fails after every restart.
4. **A malformed contract is reported as a missing one.** At call 28 the program chain defined
   `seon.program/storable-entry-attribute` with `[:or :nil :vector]` in its contract. `:vector` needs a child
   (`:malli.core/child-error`), so the contract is stored as `:seon.fn/malformed-spec`. `seon.fn/contract-findings`
   answers `missing-spec` for it, so from call 29 to the cap the step's `:seon.source/uncontracted-error` said that
   the function declares no `:malli/schema`. The agent never touched the contract. Filed red (9bf6358ce): task
   `seon.fn/a-malformed-contract-reads-as-a-missing-one`, proof
   `seon.fn-test/a-malformed-contract-is-a-finding-of-its-own-not-a-missing-one` (red: `missing-spec`).
5. **The merged render test carries an unused binding.** e061bbed3 adds `ns-form` to
   `seon.render.ns-test/task-and-schema-entities-render-as-graph-nodes` but never uses it. The merge's lint passed,
   since kondo counts unused bindings as warnings. The fix itself is right: the test now reads the registered form from
   its database.
6. **One agent ran an elision placeholder as code.** At form 17 (call 8) the render chain ran
   `(my.test/check {:seon.test/identities '...})`. The step's refusal data held the full form,
   `(my.test/check {:seon.test/identities '#{seon.render.ns-test/task-and-schema-entities-render-as-graph-nodes}})`.
   Whether the value printer cut it to `'...` in that turn's prompt is unverified: `seon.context/model-context` needs
   the turn's caps and time limit to rerun. This is one instance, unfiled.

## The refusals block, per chain

The block of each call was rebuilt with `seon.context/step-verdict` on the branch commit whose `:max-tx` is at or
before the call's opening transaction (`tmp/live-run-11/refusals.clj`).

| chain | calls | refusals shown | acted next turn? |
|---|---|---|---|
| render | 1 to 23 | the red proof only | yes, throughout: the test redefined at call 23 turned it green; no other refusal arose |
| fork | 1 to 21 | the red proof | |
| fork | 22 to 32 | **5 reaching tests red** after the call-21 `defn fork-point`: 3 in `seon.cluster.registry-test`, `seon.task-test/a-chain-is-linked-...` and `seon.cluster.db-flow-test/a-join-forks-main-after-both-needs-merge` | **yes**: calls 22 to 31 probed the store and the fork entity on the branch database, and the call-32 `defn` turned all five green and ended the task done. It never ran the offered `my.test/check` form, because the next eval request reran them itself |
| fork | 28 | plus `test-evidence-error` on `seon.cluster.registry/database`, a probe `def` from call 27 | **yes, the same turn**: `ns-unmap!` at call 28 (form 68) |
| unres | 13 to 38 | the red proof, plus `test-evidence-error` on the replaced helpers (`candidate-names`, `nearest-declared`, then `alias-target` from 23, `candidate-names*` from 25) | partly: calls 13 to 24 kept working the proof, and call 25 unmapped `candidate-names` in the right order but lost the request (Broken 2) |
| unres | 39 to 40 | proof green; only `test-evidence-error` on `alias-target` and `candidate-names` | **yes**: calls 39 and 40 ran `my.program/callers` on exactly the named functions, the operation the refusal offers. The cap came first |
| program | 1 to 28 | both proofs red | |
| program | 29 to 40 | plus `uncontracted-error` on `storable-entry-attribute` and `test-evidence-error` on it and on `derived-shape` from 30 | **no** on the contract, misled by "missing" (Broken 4); it kept probing the red proofs |

Since 5e8426095, a refusal that arose was in the block from the next call on. In three of four cases the agent's next
forms went at what it named. Run 10's unres chain read "reaches no green test" for 15 calls with no form to act on;
run 11's read it for 2 calls and ran `callers` on both names.

## Producer attribution (verified by reading the forms)

| producer | forms / calls | chain | verdict | action |
|---|---|---|---|---|
| help step 4 sent fixtures through `user` | 1 form, and the fork chain's merge | fork | **real** | **fixed** 393b1e74a |
| `publication` drops the file-proof refusal, so done passes what the merge refuses | the fork chain's merge | fork | **real** | filed red 039a5cc75 |
| `removal-facts` reads referrers from the request's basis | 4 forms lost at call 25, redone at 38 (about 13 calls) | unres | **real** | filed red 1201c92d6 |
| `contract-findings` calls a malformed contract missing | 12 calls under a misleading refusal | program | **real** | filed red 9bf6358ce |
| fixture probes failing on the agent's own code: unresolved locals, a guessed `seon.embed/request`, `reply-value` given an exception, `#[...]` (the reader answers a raw `ClassCastException` message) | 8 of unres's 22 `with-database` forms | unres | model behavior; the reader's message is poor | none |
| the program chain chased the test loader (`ClassLoader`, `proxy`, `run-selected+`) | forms 7 to 11, 14 to 17 and 31 to 42, about 12 calls | program | model behavior: the proof's error names `seon.schema.datahike/attribute-declaration`, not a loader | none |
| `:re-read` charged to `my.program/render-source-ai` | 10 forms in this run | all | model behavior, as in run 10 | none |

## Runs 7 to 11

| measure | run 7 (5 render chains, d76806d39) | run 8 (3 real chains, c87f0fb77) | run 9 (run 8's tasks, d24ad58e7) | run 10 (4 cost/design chains, ab89888ef) | run 11 (mix of 4, 5e8426095) |
|---|---|---|---|---|---|
| done / merged | 1 of 5 merged | 0 of 3 | 1 of 3 merged (plus 1 correct send-back) | 0 of 4 (2 proofs green at the cap) | **2 of 4 done, 1 merged** (1 refused by the final merge) |
| turns to green | n/a | none | 17 | none | **23 and 32** (median 23, p90 32) |
| calls per chain | 39 | 40 | 27 (40, 17, 23) | 40 (all capped) | 34 (23, 32, 40, 40) |
| wall per call | 2.3 to 2.8 s | 3.8 to 4.6 s | 2.4 to 2.7 s | 1.5 to 3.8 s | 2.0 to 3.2 s |
| cached share of prompt | 87% | 22% | 87% | 90% | 87% |
| `in-ns` share of forms | 9.8% | 2.4% | 10.4% | 7.3% | **5.0%** (3 or 4 per chain) |
| agent errors per form | 12% | 6% | 12% | 5% | 12% (32 of 260; 16 in unres) |
| reads and re-reads | 47% | 44% | 47% | 43% | 40% |
| text searches | not counted | 31 | 5 | 17 | 14 |
| `ns-unmap!` refused | n/a | n/a | n/a | 14 of 21 | **1 of 7** (Broken 2's) |
| first write (defn tried) | form 49 to 90 | form 20, 27, never | form 36, 35, none needed | form 16, 53, 45, never | form 31, 55, 25, 50 |
| wasted calls (grade) | 47 | 25 | 19 | 18 | 34 |
| evaluation per chain | 0.7 to 1.7 s, 1.1 to 3.0 GB | 3.8 to 6.5 s, 1.9 to 2.8 GB | 0.4 to 0.7 s, 0.4 to 0.9 GB | 0.6 to 2.1 s, 0.5 to 1.2 GB | 0.3 to 1.8 s, 0.4 to 0.9 GB |
| largest form | 1,207 ms | 950 ms | 97 ms | 164 ms | 226 ms (fixture probe) |
| heap | peak 836 MB | peak 1,178 MB | peak 1,026 MB | peak 812 MB | peak 798 MB, no crossing |

What improved:
- The first greens of the post-nuke series came from one task of each kind. The ordinary test fix took 23 turns, and
  the retried fork-point task took 32, now with the right key.
- Refused `ns-unmap!` forms fell from 14 to 1, after run 10's help fix.
- `in-ns` fell to 5%.

What did not:
- Neither retried cost task merged. The fork chain was undone by a help recipe at the end. The unres chain reached
  green with two turns left and lost about 13 calls to Broken 2.
- The grade's wasted calls rose to 34. 25 of them are in the two capped chains, and they are the error-heavy probes
  above.

## Heap and health

There was no crossing in process 45451. The old gen pool peaked at 798 MB of 4,096, which includes this lane's JVM
reads: the per-call `step-verdict` rebuilds allocated 1.1 to 4.3 GB per chain. Status `heap-live` was 821 MB after
the run, with `seon.cache` at 337 MB of 512.

Health reads `:faulted` on the open `seon.cluster.boot/heap-crossed!` fault. All 12 of its occurrences are in
process 36207 (run 10's), the last at 06:18:20 UTC, from before the heap-reading fix 5c64d54e0 (Broken 3 in run 10).
The other open faults are older than this process.

## Fixes and filings

- 393b1e74a: help step 4 loads the fixtures from the agent's own namespace. Proof rows in
  `seon.bootstrap-test/help-teaches-removing-what-a-branch-does-not-keep`. It needs root's restart to reach default.
- 039a5cc75: red proof and task for the done rule against the merge's file refusal (Broken 1); also files
  `seon.mine/the-turns-report-refuses-grades-read-back-from-storage` (Broken 3).
- 9bf6358ce: red proof and task for the malformed contract read as missing (Broken 4).
- 1201c92d6: red proof and task `seon.turn.batch/a-removal-judges-referrers-from-before-its-own-request`
  (Broken 2).

## Next run

- Root: restart onto HEAD so help 393b1e74a is live. Root also decides the fork chain's held merge step
  (`the-fork-point-is-walked-for-on-every-request-run-11-2` on `merge-46932050e1be`). The step needs only the `user`
  namespace's change reverted.
- Retry unres after Broken 2 lands. Its design (`nearest-declared` held under the carried projection) went green
  again.
- Retry the program task after Broken 4 lands, so its refusal names the malformed contract.
- The kept branches are `:chain-76421b37515d`, `:chain-cd8a339333ef`, `:chain-79389614d0d3` and
  `:chain-800bf0a0469d`.
