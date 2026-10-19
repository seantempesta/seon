# Done rule (`done-refusals`) REPL table (2026-10-14)

Experiment lane. Source, tests and spec untouched; nothing committed. Run on default at a disposable agent branch
(`eval_clj` agent `probe-done-a` minted `:agent-b635b698b55a`; the branch name cannot be chosen, so the agent id is the
`probe-done-<x>`). Retired with root's `(my.agent/retire-branch! {:seon.store/branch :agent-b635b698b55a})` (returned
`:my.agent/retired? true`). Everything below was done in SCI on that branch; default's main was only read.

## Setup surprise: main holds no test results at all

`(count (seon.db/q '[:find ?r :where [?r :seon.test.run/id]] main))` is 0 on default's main head (and on the fresh
branch). So no real test is "green on main" and rows 5 and 8 cannot use real code on main. Method:

- **Stand-in main** = the same branch's database value at an earlier commit:
  `(seon.db/commit-database cur <commit-id>)` where the id came from `(seon.db/commit-id (seon.db/db))` taken after a
  baseline (`probe.done.base/inc2` plus `inc2-test`, green) had been recorded by the normal entrance. `done-refusals`
  is called with that value as `main`; the delta is `seon.program/changed-identities` from the stand-in (not from the
  fork), so the baseline is not "changed". Inferred equivalent to main + a branch forked from it.
- **Real main** (`(seon.task/main-head cur)`, no results) is used for the unknown-baseline row.
- **Entrance that records results at current reach**: the eval request's own settle/test-step (the reply shows
  `:seon.turn.loop/tests {:reaching {:seon.test/passed? ...}}` after each `deftest` or `defn`). Nothing was invoked
  by hand. `seon.context/current-results` on the branch then returns that result with its `:seon.test.member/reach-digest`.
- `judged` is built by my `observe` (same shape as `seon.context/step-observation`'s `:seon.source/judged`, minus the
  task: members = proofs + tests in the delta + `seon.fn/gate-sets` of changed functions; results =
  `current-results`; `:seon.source/red-on-target` = `held-red` equivalent). Own helper functions are filtered out of
  the delta by namespace (`probe.done.judge`), else they would show as uncontracted new functions.

## Draft code (SCI namespace `probe.done.judge`; contracts omitted, so each defn returned an `:uncontracted` finding)

```clojure
(defn green? [r]                       ; same test as results-verdict's inner green?
  (and r (not (:seon.error/at r)) (pos? (:seon.test/pass-count r 0))
       (zero? (:seon.test/fail-count r 0)) (zero? (:seon.test/error-count r 0))))
(defn red? [r] (boolean (and r (seon.test.runner/red-result? r))))

(defn done-refusals
  "[branch main judged] -> [refusal ...]"
  [branch main {members :seon.source/members results :seon.source/results
                proofs :seon.task/proofs delta :seon.program/identities}]
  (let [by-sym (into {} (map (juxt #(or (:seon.test/sym %) (:seon.test/error-test-symbol %)) identity)) results)
        unknown (vec (sort (remove by-sym proofs)))                      ; no current result
        red-proofs (vec (sort (filter #(and (by-sym %) (not (green? (by-sym %)))) proofs)))
        branch-red (into #{} (filter #(red? (by-sym %))) (remove proofs members))
        main-results (if (seq branch-red) (seon.context/current-results main branch-red) [])
        main-green (into #{} (comp (filter green?) (map :seon.test/sym)) (when (vector? main-results) main-results))
        regressions (vec (sort (filter main-green branch-red)))
        gaps (seon.cluster.source/contract-gaps branch delta)]
    (cond-> []
      (or (empty? proofs) (seq unknown)) (conj {:refusal :proof-unknown :offending unknown})
      (seq red-proofs)                   (conj {:refusal :proof-red :offending red-proofs})
      (seq regressions)                  (conj {:refusal :regression :offending regressions})
      (seq gaps)                         (conj {:refusal :contract-gap :offending (seon.cluster.source/gap-symbols gaps)}))))
```

Production would return `seon.error.refusal/error` maps of new variants (`:seon.source/proof-unknown-error`, ...) like
`results-verdict` does; I returned `{:refusal kw :offending [...]}` (inferred: the variants need registering in the
`:seon.source/refusals` schema). Everything it calls already exists and is public: `seon.context/current-results`,
`seon.cluster.source/contract-gaps`, `seon.cluster.source/gap-symbols`, `seon.test.runner/red-result?`. It runs no
test, writes nothing.

## Table (actual)

"old" = today's `seon.cluster.source/results-verdict candidate main judged` over the very same `judged` map (in rows 1-7
`main` is the stand-in; row 8 uses real main). Proof set is always `#{probe.done.work/p1}` unless noted.

| # | state (built on the branch) | expected | `done-refusals` actual | `results-verdict` (old) |
|---|---|---|---|---|
| 1 | `f1` contracted + `p1` green | done | `[]` done | `[]` accept |
| 2 | `p1` redefined red | :proof-red | `[{:proof-red [p1]}]` | `:red-test-error` + `:test-evidence-error` (f1 reaches no green test) |
| 3 | proofs `#{p1 p-never}`, `p-never` has no entity (so never run) | :proof-unknown | `[{:proof-unknown [p-never]}]` | `:red-test-error` (member with no result is "not green") |
| 4 | `p1` green + new deftest `r4` red outside proofs | done | `[]` done | `:red-test-error` (r4 is red and not in the product) |
| 5 | `p1` green; `probe.done.base/inc2` changed, `inc2-test` (green on stand-in main) red on branch | :regression | `[{:regression [inc2-test]}]` | `:red-test-error` + `:test-evidence-error` (not distinguished from row 4's kind of red except by verdict inside the error) |
| 6 | `p1` green + new contracted stub `stub6` no test reaches (r4 made green) | done | `[]` done | `:test-evidence-error` offending `[stub6]` ("replaced function reaches no green test") |
| 7 | `p1` green + new `gap7` with `[:cat :any]` | :contract-gap | `[{:contract-gap [gap7]}]` | `:uncontracted-error [gap7]` + `:test-evidence-error [gap7 stub6]` |
| 8 | row 5's state, `main` = real main (it holds no result for `inc2-test`) | done | `[]` done | `:red-test-error` (red-on-target empty, so it is the change's) |

All eight match the expected `done-refusals` column. `results-verdict` refuses rows 2-8 (4, 5, 6, 7, 8 are the rows
where the final merge stays strict: new red test, uncovered stub, broken test, gap; 8 also refuses at merge because its
`prepare-merge!` baseline run is what turns an unknown into red-on-target or red). Rows 4 and 6 refuse in the old
judge exactly as the spec says is intended at the end of a chain.

Each row was built incrementally on one branch (redefining `p1`, `r4`, `gap7`, `inc2`) rather than on separate
branches; reused names make the `old` column in later rows carry the earlier rows' leftovers (`stub6` stays in
row 7's old answer). The `done` column is unaffected: stubs are contracted and carry no red.

## Surprise 1: "at the same reach" cannot mean the branch's reach digest

Row 5 state, `inc2-test`: reach digest on the branch `a60f8bd3...`, on the stand-in main `a1ef46f5...`. A change that
breaks an existing test necessarily changes that test's reach (its closure includes the changed function). So
"main holds green at the same reach" read literally (equal digests) would never fire for the case it exists for;
that test would always be "unknown" and rows 5 and 8 would both be done. What works, and what `held-red` already does
for the opposite polarity: **main's result at main's own current reach** (`seon.context/current-results main tests`),
i.e. "main holds it green as main stands now". Variants measured on row 5:

| main-side read | result for `inc2-test` |
|---|---|
| `current-results main` (main's own current reach) | green, regression (used) |
| `seon.test.runner/latest-results main` (latest at any reach) | green, regression, but would also trust a result recorded at an older, since-changed main reach |
| result whose digest equals the branch's reach digest | none (digests differ), done, wrong |

Proposed wording: "a test the branch holds red whose result on main, at main's own current reach, is green".

## Surprise 2: main's results are the weak input; `main` is a value, no extra read of a branch

`current-results` takes any database value, so `main` costs one `latest-results` + `reach-digests` over the red
candidates only (nothing when no test is red). On real main with zero runs: 0.008 ms; on a stand-in holding one
result: 0.86 ms. The read is pull/datoms on main's value, no connection, no run. In production the chain branch's
`main` argument is `seon.task/main-head` (what `step-observation` already passes as `:seon.context/main`).

## Surprise 3: the contract gap rule should use `seon.cluster.source/contract-gaps`, not `seon.contracts/gaps`

The spec text names `seon.contracts/gaps`. That returns every `seon.fn/contract-findings` row (the Design lane showed
inherited `:maybe`/`bare-map` findings on existing functions). The merge's rule, `contract-gaps`/`incomplete-findings`,
keeps only `missing-spec`, `any` and `some`, skips macros and host-bound functions, and takes the identity set
directly (it ignores `[:seon.ns/name ...]` and test identities). Using it makes the done rule and the final merge refuse
the same gaps, and it took `judged`'s `:seon.program/identities` as-is. Row 7 offending came back as `[gap7]` via
`gap-symbols`. Edit step 4: "with a `seon.cluster.source/contract-gaps` row".

## Surprise 4: members are not "every test the branch defined"

`done-refusals` can only judge what `judged` carries. Today's `step-observation` members = proofs + tests reaching
changed functions. A new red test that reaches no changed function (e.g. Prove's designed red against a function that
does not yet exist or a stub) is never a member, so it neither refuses nor shows. Step 3 of the spec says the runner
runs "every test defined or changed on the branch"; step 4's judged input must then take members from that same
selection (`seon.test/changes`), else a branch-defined test that breaks nothing is invisible. In my runs the new
tests all reached `f1`, so members included them (my `observe` also added tests from the delta explicitly). Inferred
edit: `step-observation` members = proofs + `(delta tests)` + `gate-sets`.

## Surprise 5: unknown versus regression for a stale or absent main

Real main holds no runs at all on default right now, so every branch-red existing test is "unknown" and the done rule
never calls a regression until main has recorded runs. That is by design (`prepare-merge!` runs baselines at the
end), but it makes the regression clause a no-op on a freshly reset cluster. The final merge covers it; state it.

## Surprise 6: definitions flagged with a digest refusal (inferred, not chased)

`probe.done.judge/observe` and `done-refusals` (the two long ones with docstrings containing `->`/`[...]`) came back
with `:diagnostics` `:seon.db/transaction-refused ... definition digest ... is not the one its definition produces`
on first define: "exists in your SCI context only". They ran correctly in SCI, so the judgments above are sound, but
this is a platform finding (admission digest mismatch for a defn on a branch), worth a task. Not reproduced in
isolation; the form is in the draft above. Also `my.program/ns-unmap!` with two symbol args was refused (its 2-arity
is `[context sym]`; the one-arg qualified form returned `:ok` but did not retract the deftest `r4`, which still
ran), and a `def` used inside a defn (`base-cid`) leaves a recurring
"Unable to resolve symbol" install error on every later request: inline constants, never `def` them.

## Cost (warm; row 5/8 state, 3 members, 8 delta identities)

| call | ms | notes |
|---|---|---|
| `done-refusals` (stand-in main, one red candidate) | 1.9 | `seon.profile/measure`: 1.86 ms, 2.2 MB thread alloc, 1,173 calls, 18 reads |
| `done-refusals` (real main, no result) | 0.45 | |
| of which `seon.cluster.source/contract-gaps` | 0.40 | |
| `seon.context/current-results` on main, 1 test | 0.86 stand-in / 0.008 real | |
| `observe` (delta since base + `gate-sets` + `current-results`) | 7.0 | the cost `step-observation` already pays |
| `results-verdict` (old) | 3.4 | |

Cheap enough for root to re-judge on every end: about 2 ms plus the 7 ms observation (both well under the 100 ms
recording budget). Cost is proportional to members and the delta size, not the store. Not measured here (inferred):
a 834-leaf chain's observation, where `gate-sets` and `current-results` over hundreds of members dominate.

## Proposed edits to TASK-CHAIN section 2 step 4

1. Replace "a test the branch holds red whose result at the same reach main holds green" with "a test the branch holds
   red (a proof excluded; it is `:proof-red`) whose result on `main`, at main's own current reach
   (`seon.context/current-results main`), is green". Add: "equal reach digests cannot be required: a change that breaks
   a test changes its reach."
2. Replace "with non-empty `seon.contracts/gaps`" with "with a `seon.cluster.source/contract-gaps` row (the merge's
   own rule: missing contract, unjustified `:any` or `:some`)".
3. State the judged input: `:seon.source/members` is the proofs plus every test defined or changed on the branch
   (`seon.test/changes`) plus the tests reaching changed functions, so a new red test outside the proofs is visible but
   judged only for regression against main, never for greenness.
4. Say the four refusal variants (`:seon.source/proof-unknown-error`, `proof-red`, `regression`, `contract-gap`) join the
   `:seon.source/refusals` schema, each with `:seon.error/offending`.
5. Note: while main holds no recorded runs (fresh or reset cluster) the regression clause cannot fire; the final merge's
   baselines (`prepare-merge!`) catch it.
6. Cost line: "about 2 ms for the rule over a few members, plus the 7 ms observation; reads only".
7. Keep `results-verdict` strict for the final merge (the table shows it refuses rows 2-8, including 4 and 6).

## Not done / limits

- Row 8 used real main (no runs) with a synthetic test, not real platform code; a real-code variant (change a tiny
  `seon.ref` function) was not run because it would auto-run many reaching tests on the branch.
- A proof present but with a stale result (reach moved, not rerun) was not built: the settle step reruns reached tests
  after every request, so I could not hold such a state; `current-results` drops it (reach filter), so it reads as
  `:proof-unknown` (inferred from `current-results`'s docstring and source).
- No `seon.task` assignment/attempt was created; `judged` was hand-built in the same shape.
