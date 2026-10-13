---
title: "Second narrow check of the step-lifecycle hybrid: the ten §8 questions, the phase deletions, the Run 26 subject"
type: research
status: narrow check of hybrid-lifecycle.md (69f9c4ef9) section 8. Three claims refuted or materially wrong (Q1, Q6/Q7, Q10: hand lists beside the task schema and generators are not total); C1, C2, C3, C5, C6, A3, A4, A5, B1 get file or order corrections; Run 26 subject named with a caveat
model: Claude Sonnet 5.5 (lane narrow-lifecycle-2)
basis: "HEAD 69f9c4ef9; default on basis commit 6acd9b63 (JVM-mode reads as root, no write); SCI only on my own agent narrow2 (probes, test runs, one task write on its branch); no restart, no reset, no write to main"
---

# Second narrow check

Method as `narrow-lifecycle.md`: the exact form, the reply, the timing, then the consequence for the phase that depends on it.
Cold interpreted test runs on `narrow2` hit the 5 s bound (SCI first run); a red that turned green on the immediate rerun is called a
cold bound, not a red.

## Result in one table

| Q | Claim | Verdict | Consequence |
|---|---|---|---|
| 1 | `create!` admits `:seon.task/template` and `:survey`; `proven-task-file` round-trips a clone with `:template` and `:exit` | **REFUTED** (three hand lists drop the new members) | C1 and C2 gain `src/seon/cluster/source.clj` (`task-entry-node`) and `src/seon/task.clj` (`create!`/`task-entity`): derive the member list from `:seon.task/declaration` |
| 2 | `end-task-call` is the one writer of `:seon.task.attempt/end :done` | REPRODUCED (production); tests write it directly | C3 stands; keep `step-status` and the transaction function in one commit or `end-task!` faults every pass |
| 3 | A compiled validator over the open effective map under 5 ms | REPRODUCED (0.1 ms compile, 0.00008 ms validate, 0.015 ms explain) | C3's check costs nothing measurable |
| 4 | `effective`'s pull of eight content keys under 10 ms on 50 tasks | REPRODUCED (2.4 ms for 50 pulls of eight keys; `effective` 4.5-6.6 ms for 50) | C2/C3 stand; add the keys to `effective`'s content vector |
| 5 | The test member's world accepts `:seon.task/id` and a body reads the step's declaration there | PARTIAL (the world and the branch already do; the custody and request do not) | C4 is four small edits, listed below |
| 6 | An `:examples` entry's printed `:actual` round-trips through the one printer | PARTIAL (data yes; text no) | Survey stores `:actual` as the printed text and Prove compares `read-string` data; objects never round-trip |
| 7 | Every schema an existing producer registers has a generator | **REFUTED** (11 of 88 output schemas fail, 3 input; 31.5 s, 139 GB allocated for 176 generations) | Design's handoff cannot assume generators; examples are the fallback, generation is never run in a step |
| 8 | The relaunch after a refused `:done` ends `:done` with 0 model calls; `a-fresh-attempt-is-work-without-a-message` green on HEAD | PARTIAL (its proof is green warm, red cold; no test proves the 0-model-call end for `:done`, only the `:satisfied` one D4 deletes) | A5/A6 each need their own red proof for the zero-model-call relaunch |
| 9 | Name the Run 26 subject | named, with a caveat (below) | none of the 17 has a clean red + local fix |
| 10 | `refine!` writing a set-valued key passes the declaration compare and write-back | **REFUTED** (`refine!` through `create!` erases `:seon.task/product` today) | C6 first fixes `create!`; this is a live defect on HEAD |

## Q1 REFUTED: three hand lists beside the declaration schema

Reads (JVM mode):

```clojure
(let [f @#'seon.cluster.source/task-file-text
      decl {:seon.task/ns 'x.y :seon.task/name "b" :seon.task/intent "i" :seon.task/product '#{x/y}
            :seon.task/template [:seon.task/sym 'seon.step/survey] :seon.task/exit :seon.task/surveyed}]
  (f "[{:name \"a\" :intent \"x\"}]" [["b" decl]]))
```

Reply (13 ms): `"[{:name \"a\" :intent \"x\"}\n {:seon.task/name \"b\" :seon.task/intent \"i\"}]"`. `:product`, `:template` and `:exit` are
dropped from the written entry: `task-entry-node` orders members by the old entry's keys plus the fixed list
`[:name :step :intent :instructions :children :needs]` (`seon.cluster.source/task-entry-node`), so a new entry never writes
`:product`, and would write none of the new keys. Write-back would then refuse `:not-equivalent` (the candidate read-back differs).
`:product` is already affected at HEAD for any appended entry.

Two more lists: `seon.task/create!` hands `task-entity` `(select-keys request [ns name step intent instructions children needs])`
(`:product` and every new key are dropped silently; the contract map is open, so nothing refuses), and the `:entry` and
`:declaration` maps in `resources/seon/schemas/seon.task.edn` (closed `:entry`; the declaration members are what `task-delta` and
`declaration` already derive from the schema, `seon.task/declaration`). The enum extension itself (`:step` gaining `:survey :define :design`) is
a one-line schema change and was not exercised (publishing is a reload; Q1's first half rests on the enum being closed:
`resources/seon/schemas/seon.task.edn` `:step [:enum :prove :implement]`).

Consequence: C1 (schema) lists `src/seon/task.clj` and `src/seon/cluster/source.clj`; one change replaces the three lists with the
members of `:seon.task/declaration` (`task-entry-node` orders by the entry's own keys then the declaration's remaining keys sorted;
`create!` selects the declaration members and its contract becomes the declaration schema plus connection, agent and basis).
That deletes a list rather than extending it. C2's round-trip proof (`a-chain-is-cloned-from-its-templates`) is red for the right
reason until it lands; add `:seon.task/product` to its fixture (Prove steps carry it).

## Q2 REPRODUCED: one writer

`rg -n 'attempt/end' src` writes the end in exactly these places: `seon.turn/end-task-call` (`:done`, `:satisfied`, `:sent-back`,
`:given-back`, `:capped`, `:stalled` through `task-end`), `seon.cluster.agent` (`:error` at 2205/2236, `:cancelled` at 2263, both
main-side db<->flow). The only `:done` writes elsewhere are fixtures (`test/my/root_test.clj` lines 82 and 113 transact it
directly; `test/seon/cluster/db_flow_test.clj` uses it as plan input). `end-task!` is `end-task-call`'s only caller (turn.clj 5771).
A throw inside the transaction function is `end-task!`'s `fault!`; since `task-end` is re-derived each pass, an exit refused by the
writer but not by `step-status` would fault once per pass. C3 therefore changes both in one commit (it does) and its red proof
should also assert that a refused end leaves no fault (the step reads `:working`).

## Q3 REPRODUCED: validator cost

```clojure
(let [reg (:seon.schema.projection/registry (db/carried-projection main))
      ex [:map [:seon.task/id :seon.task/id] [:seon.task/sym :seon.task/sym] [:seon.task/intent :seon.task/intent]
          [:seon.task/instructions {:optional true} :seon.task/instructions] [:seon.task/references :seon.task/references]]]
  ...)
```

Compile 0.108 ms; `(v eff)` 0.000077 ms (2,000 calls); `m/explain` 0.0146 ms (500 calls); the named
`:seon.task/effective` validator 0.000075 ms. Limit 5 ms: five orders under.

## Q4 REPRODUCED: `effective` with eight keys

On main (837 tasks, 50 in `seon.turn`; every one of the 50 is a standalone subject, lineage depth 0 for all): `seon.task/effective`
for the 50, warm: 4.5, 4.9, 6.6 ms (the first, cold, 25-77 ms); fifty pulls of eight content keys: 2.4, 2.4, 2.7 ms warm (0.05 ms
each; one cold pull 8 ms). Depth was 0, so a chain of seven stages adds six more parent pulls per call; at 0.05 ms that is 0.3 ms.
Limit 10 ms: stands.

## Q5 PARTIAL: the member world

`seon.turn.world/world` already carries `:seon.task/id` (`carried`), `seon.batch/world-members` already names it, and
call preparation fills an omitted `:seon.task/id` input by that name. A member's branch holds the whole task population: on
`narrow2`, `(seon.db/q '[:find [?h ...] :where [?t :seon.task/id ?h]] (seon.db/db))` answered 837 handles, so a body can read
the step's declaration (`seon.task/effective` takes the handle). Missing: `seon.test/bounded-result`'s custody map and its contract
(`[:map [:seon.db/connection ...] [:seon.sci.eval/ctx ...] [:seon.test/member ...]]`) have no task, and the `world/world` request in
its body builds only the cluster, connection and ctx. C4: the request key `:seon.test/task` (schema), the custody entry at the
`bounded-result` call (`seon.test/run-var!`, around line 2435), the custody contract, and one `:seon.task/id` in the world request.
No new machinery.

## Q6 PARTIAL: printed `:actual`

On `narrow2`, a map, a lazy seq, a 400-element vector and a fn were evaluated in one form. The reply printed the map, the lazy
seq as `(1 2 3)` and the vector whole, wrapped at about 72 columns by layout; the fn printed as
`#object[clojure.lang.AFunction/1 0x324e2ffb]`. A stored `:seon.eval/shown` is therefore layout-dependent text (its wrapping
changes with the profile) and holds objects that cannot be read back. Consequence for Survey and Prove: store `:actual` as the
printed text, compare by `(= (read-string expected-text) (read-string shown))` for readable values only; an example whose value is
an object is refused at `end-task-call`'s exit validation with the explain (the exit schema for `:examples` should say
`[:string {:min 1}]` for `:actual` and C3's contract adds a `read-string`-safe predicate, a registered named one).

## Q7 REFUTED: generators are not total

```clojure
(mg/generate sch {:registry reg :size 3 :seed 1}) over the output and the input of the 88 functions the 106 references of the
50 turn tasks resolve to with a `:malli/schema`
```

Reply: **31,502 ms** and 139,207 MB allocated in the request (JVM window: 146 s of GC), outputs `:ok` 77,
`:malli.generator/such-that-failure` 11 (among them `seon.task/ready`, `seon.task/agent-attempt`, `seon.test/run`, `seon.test/run-request`,
`seon.test/recorded-result`, `seon.ai/complete`, `seon.cluster.host/start!`, `seon.turn/planned-sources`), inputs `:ok` 84, one each of
`such-that-failure`, `:malli.core/invalid-schema` (`seon.turn/planned-sources`), `:malli.generator/no-generator` and
`:malli.core/infinitely-expanding-schema` (`seon.schema/register!`). So 13% of outputs have no working generator, the probe itself
is a problem (a finding for the owner: a generator probe over 176 schemas allocated 139 GB on default; I disturbed default's heap with
it and will not repeat it), and the Design stage cannot depend on `malli.generator`. Consequence: Design's handoff is a set of
examples (Q6) and, where a general law exists, a `:gen/` hint named in the schema; the Design template says "generate if the schema
generates" never "generate". D-row 7's Design handoff text should change accordingly; C5's `design-leaves-its-successor-red-proofs`
reads examples, not generators.

## Q8 PARTIAL: the zero-model-call relaunch

`seon.cluster.db-flow-test/a-red-fresh-attempt-takes-one-model-turn-whose-send-back-names-its-target` (the proof of
`seon.turn/a-fresh-attempt-is-work-without-a-message`) on `narrow2`: first run 5.46 s, 1 error (SCI `time-limit` interrupt) and a bound
failure; two reruns with `:seon.test/rerun? true`: 3 passes, 0 fails, about 3.2 s each. So it is green warm, with a thin margin
(3.2 of 5 s). It proves a RED fresh attempt takes one model turn. The "already green ends done with no model call" half is proven
only by `…db-flow-test/a-prove-step-whose-product…` and the `:satisfied` test around line 360 of the same file (`(zero? (model-turns …))`
with `:satisfied`), which D4 deletes. `refused-merge-relaunch` writes `:error` plus the end commit and relaunch reason, so the relaunched
attempt forks at the end commit: its proofs are green at the fork, which today reads `:satisfied` (`green-before`), and after A5
reads `:done`. Consequence: A6's red proof
`a-merge-refused-after-main-moved-relaunches-and-ends-done-without-a-model-call` is a new test (not an existing green one) and A5
must convert the `:satisfied` db-flow test to assert `:done` in the same commit, or the zero-model-turn guarantee loses its only proof.

## Q9 Run 26 subject: no clean candidate

Of the 17 tasks with proofs in `resources/seon/tasks/seon.turn.edn` I ran every proof I could on `narrow2` (the branch holds no
stored runs from main, so the red status is my own measurement; interpreted cold runs add `time-limit` noise):

| Task | Proof (result on narrow2, HEAD) |
|---|---|
| an-end-is-written-on-the-attempt, the-test-step-writes-nothing-on-main, a-send-back-names-its-target-on-the-attempt, a-task-end-never-reaches-root, a-closed-attempt-releases-its-connection-before-branch-deletion, a-reached-red-test-keeps-a-task-from-ending-done, a-reached-test-unrun-or-red-on-main-does-not-block-done | green (already done; they prove nothing for a chain) |
| a-fresh-attempt-is-work-without-a-message | green warm, red cold (Q8) |
| turn-continue-read-results-arrive-before-done-takes-six-seconds | proof `provider-progress-has-its-own-bound`: 1 pass 1 fail 1 error, 6.7 s (a cold bound) |
| a-send-inside-a-do-form-leaves-a-message | `cluster.turn-test/a-turn-delivers-what-a-form-asks-to-send-and-still-finishes`: 4 pass, 3 fail, 2 error; the message query answers `#{}` |
| an-identical-re-record-conflicts-with-itself | `evaluate-sources-test/ordered-evaluation-retains-one-explicit-basis-without-publication`: 4 failures at once (a pull of `later-agent` nil, `writes` 0 not 1, no `:seon.turn/refused`); the task text claims the writer refuses, the test expects a refusal the code gives only when the stored content differs: the premise is stale, not a local fix |
| stop-flow-interrupts…, code-evaluates-as-one-ordinary-run… (a-stop-during-model-context-assembly…, a-turn-pass-derives-its-work-twice) | error: SCI cannot interpret the test support (`seon.cluster.agent-test/with-connection` ClassCastException); a host-bound red, not a task fix |
| a-green-proof-completes-the-task, a-wake-reopens-a-finished-task, the-turn-settlement-and-schema-tests-are-red, the-planner-terminal-census… | red on the triage run (`turn-reds-triage.md`): the first two are the lifecycle's own rule (not independent of this design); the settlement five are five unrelated reds; the census is a bound |

**Subject: `seon.turn/a-send-inside-a-do-form-leaves-a-message`.** The smallest: one deftest, a one-form repro written into its intent,
a named owner (`seon.cluster.message/send-call`, `send!`) and no interface work; red (4/3/2 above). Caveat, measured on `narrow2`: a plain
`(do (my.message/send {:my.message/to "narrow2" :my.message/content "..."}) 1)` evaluated by an outside agent DOES record the message
(`#{["narrow2 probe in do" "narrow2"]}` on the branch, 52 ms), so the defect is not the `do` form; it lies in the model-origin or
turn-delivery path the test uses. That makes Survey do real discriminating work (its exit `:surveyed` must say where the send is
lost), which is what Run 26 is for, but it may end `:send-back` as moot. Fallback if Survey proves it moot:
`seon.turn/an-identical-re-record-conflicts-with-itself`, after Survey rewrites its premise. Neither is a "clear red + local fix"; Run 26
should be told so, and the 17-with-proofs figure overstates the ready defects: 8 are green, 4 are bounds or host-bound, 5 red.

## Q10 REFUTED: `refine!` erases `:product`

The pattern `my.task/refine!` uses (`(merge (get basis target) changes)` into `seon.task/create!`) on `narrow2`, target
`seon.edit/prove-an-unreadable-odd-map-is-not-one-form` (handle `bbbdd44c7791`, a Prove step):

```clojure
(seon.task/create! (merge held {:seon.task/instructions "narrow2 probe: instructions rewritten."
                                :seon.task/basis (select-keys basis [target])}))
```

`held` keys: `:ns :name :step :instructions :product` (product a two-test set). Reply `{:seon.task/changed? true}`; the task afterwards
pulls `{:seon.task/instructions "narrow2 probe…"}` with **no `:seon.task/product`**. So a Prove step that calls `my.task/refine!`
loses the tests it must write, today, on HEAD (`create!` selects a fixed member list that omits `:product`, Q1). Consequences: (1) this
is a defect with a task of its own, to file against `seon.task/create!` and `my.task/refine!` (regression: refine keeps every other
declared member); (2) C6 cannot extend `refine!` before `create!` stops dropping members, and its red proof
`refine-writes-the-surveys-findings-as-data` should assert `:product` survives a refine; (3) `task-delta` is fine (it derives its
members from `:seon.task/declaration`, so a set-valued `:examples` would compare as a set once declared). I did not exercise write-back
for a set-valued new key (needs the schema from C1).

## Phase check: does each deletion sit with its last caller?

`rg -F` over `src resources test script bin` (docs excluded):

| Deletion (phase) | Callers found | Verdict and correction |
|---|---|---|
| `unmet` (A4) | `context.clj` 6; `bootstrap.clj` 1 is prose; tests `context_test.clj` 9; schema `:seon.context/unmet` | sits with `step-verdict`/`step-status`; also edit `resources/seon/tasks/seon.turn.edn` and `seon.context.edn` (cite it) |
| private `contract-gaps` (A4) | `context.clj` 8 callers inside; `source/contract-gaps` public stays (batch.clj, turn.clj, source.clj) | fine; do not delete the public one |
| private `designed-red?` (A4) | `context.clj` 3; `runner/designed-red?` stays (source.clj 2, runner.clj, test) | fine |
| `base-results`, `reds` (A4) | `context.clj` only | fine |
| fork-point proxy (A4) | `turn.clj` `test-step` (5442-5468) passes `:seon.context/fork-point`; `seon.test/fork-point-red` stays (used by A2's judge) | A3 must keep passing fork-point until A4 stops reading it, or A3/A4 merge; A4 lists `src/seon/turn.clj` |
| `record-broken!` (A3) | `turn.clj` 3, `context.clj` 1 reads `:seon.agent/broken`; schema `seon.agent.edn` | its reader in `context.clj` is converted in A4, one phase after the writer disappears in A3: A3 keeps writing `:seon.agent/broken` or A3/A4 are one commit; also edit `seon.turn.edn` (2) and `seon.context.edn` (1) |
| `record-reached-red!` | already deleted; still cited by `seon.turn.edn` 1 and `seon.context.edn` 1 | pre-existing dangling references; A3 corrects them (same commit) |
| `:satisfied` (A4/A5) | `context.clj` 5, `turn.clj` 3 (`task-end`'s `#{:done :satisfied}`), `task.clj` 3, `agent.clj` 4, tests 3+4, schemas `seon.context.edn` 3, `seon.task.edn` 2, `seon.task.attempt.edn` 1 | A5's file list omits `src/seon/turn.clj` and `resources/seon/tasks/seon.context.edn`; add them. The retained test around line 360 of `db_flow_test.clj` must become a `:done` zero-model-turn test (Q8) |
| `fixable-refusals`, `refused-merge-relaunch` (A6) | `agent.clj` only (4 each) | sits with `failed-pass!`; keep `refused-merge-relaunch`, delete the set (D6) |
| `declared-source` (B1) | `sci/eval.clj` 4, one test string label only | sits with its callers; edit `resources/seon/tasks/seon.sci.eval.edn` |
| `runtime-require-specs` public (B1) | `fn.clj` 2, task `seon.fn.edn` | fine |
| three hand member lists (new, Q1/Q10) | `source.clj` `task-entry-node`, `task.clj` `create!`, `my/task.clj` | add to C1; delete the lists in the commit that makes `create!` derive its members |

Phase changes: **C1** gains `src/seon/task.clj` and `src/seon/cluster/source.clj` and a proof that a clone with `:product`,
`:template`, `:exit` round-trips; **C6** waits for that and asserts `:product` survives `refine!`; **C4** lists its four edits;
**C5** drops generators from Design's handoff; **A3/A4** are one commit or A3 keeps the `:seon.agent/broken` write; **A5** adds
`src/seon/turn.clj` and converts the `:satisfied` db-flow test; **A6** names a new red proof; **A3/A4/B1** edit the cited task files.
New task to file: `my.task/refine!` erases `:seon.task/product` on HEAD (Q10). Finding to record: a generator probe over 176
schemas allocates 139 GB (Q7), and several interpreted tests hit the 5 s bound cold.
