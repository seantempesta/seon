---
type: research
status: complete — change proven at the REPL, not installable (install gate)
created: 2026-09-27
lane: repl-dogfood-opus
jvm: default 441988b49, cd4ca3d39 (restart 22:01:39Z), e999fdd1b (restart 22:23:18Z)
branch: dogfood-string-total (retired)
---

# REPL dogfood: one real change through Seon's REPL entrance (2026-09-27)

Lane `repl-dogfood-opus`. Owner goal: "Get to the point that working with the repl is the best way to build in the
system." One real change was attempted only through MCP `eval_clj` in SCI mode on a named branch
(`dogfood-string-total`). Following root's ruling that MCP is a thin wrapper over the one path, the key steps were then
run again as a Seon agent turn, using a scripted local provider stub. The stub was an OpenAI-compatible
`HttpServer` started in default's JVM that serves reply files and writes each request to a ledger. No paid call was
made. Default ran 441988b49, then cd4ca3d39 (restart 22:01:39Z), then e999fdd1b (restart 22:23:18Z).

**Verdict: the change did not land.** It was proven at the REPL, and its regression was written and recorded red on the
base. It stopped at **install**: the install gate refused both definitions. Nothing could be proposed, merged or written
back. The steps after install were exercised only far enough to record their refusals.

## 1. The change

Task `an-uncut-string-node-carries-no-total` (`resources/seon/tasks/seon.render.value.edn`) has two parts:

- give the floor's uncut `:string` node its `:seon.render.data/total` in `seon.render.value/tree`;
- delete the remainder special case in `seon.cluster/mcp-get-value`.

The chosen total is `(+ (:base node 0) (:total node))`, the same absolute total a cut already names
(`cut-node`, value.clj:1136). With it, `mcp-get-value` shrinks to
`total (or tree-total cut-total)` and `beyond-end? (and total (>= offset total) (pos? total))`, and its `remainder`
branch goes (5 lines out, 1 in). Nobody held either file.

Proof at the REPL: the new `tree` is `old-tree` plus that one entry, applied to real `select-nodes` output. It gave
`"abcdef"@0 -> 6`, `@2 -> 6`, `"abc"@3 -> 3` and `""@0 -> 0`; the old `tree` gave no total for any of them. Regression
`seon.render.value-test/an-uncut-string-names-its-absolute-total` was entered with `deftest` and recorded on the branch:
**4 fail**, red on the base as TDD requires. The branch is retired. It was in no merge.

## 2. Transcript as data

`tok` estimates the size of the shown reply (characters ÷ 4). `ms` is the armed time the reply reported. The MCP
branch tool reports no time.

```clojure
[;; --- MCP, SCI mode, branch dogfood-string-total (default 441988b49) ---
 {:step 1  :form "(branch create dogfood-string-total)" :ms nil :tok 60   :verdict :ok :note "no timing in the reply"}
 {:step 2  :form "(doc seon.render.value/tree)" :ms 5 :tok 200 :verdict :refused
  :note "documentation-unavailable: tree is private, and private declarations are what an agent edits"}
 {:step 3  :form "(source seon.render.value/tree)" :ms 0 :tok 90 :verdict :error :note "source is not referred in user"}
 {:step 4  :form "(clojure.repl/source seon.render.value/tree)" :ms 4 :tok 900 :verdict :ok
  :note "prints to :out, and the :large-output finding tells the agent to return a value instead"}
 {:step 5  :form "(clojure.repl/source seon.cluster/mcp-get-value)" :ms 2 :tok 1500 :verdict :ok}
 {:step 6  :form "(dir seon.render.value)" :ms 35 :tok 1350 :verdict :error
  :note "V1 lower-selector symbol selector; fixed in 18ce1b1a4 before the second restart"}
 {:step 7  :form "(doc seon.render.value/prepare)" :ms 6 :tok 350 :verdict :ok
  :note "the summary is cut mid-sentence at the first newline"}
 {:step 8  :form "(q host-bound? of tree/mcp-get-value/prepare)" :ms 7 :tok 250 :verdict :ok :note "all false"}
 {:step 9  :form "(def probe-unit ...) (:seon.render.value/tree (prepare (assoc probe-unit :seon.render/value \"abc\")))"
  :ms 1 :tok 250 :verdict :ok :note "reproduced: no total on the uncut string"}
 {:step 10 :form "(in-ns 'seon.render.value) (defn- tree ... +1 entry)" :ms 51 :tok 180 :verdict :refused
  :note "\"its gate failed\" and nothing else; the value holds: 34 tests, 15 passed, 19 failed, auto-check 1/25 failed on ({} {}) -> nil"}
 {:step 11 :form "(select-keys result/e... [gate keys])" :ms 164 :tok 2000 :verdict :ugly
  :note "the map matched install-refused's renderer by key; its refusal became a turn-write refusal; three of them parked the agent's turn proc"}
 {:step 12 :form "(orientation of the refusal)" :ms 0 :tok 200 :verdict :refused
  :note "the JVM was under memory pressure (9.5 of 11 GB old gen), so new evaluations were refused"}
 {:step 13 :form "(failing reaching tests)" :ms 9 :tok 1000 :verdict :ok
  :note "every failure: gen/ sci/ running-instances unresolved in SCI; with-database refuses (no execution handle)"}
 ;; --- default restarted at cd4ca3d39; branch intact, private defs lost ---
 {:step 14 :form "(branch list) during boot" :ms nil :tok 700 :verdict :panic
  :note "raw #error NPE 'fut is null' plus reader error, not a readiness value"}
 {:step 15 :form "(defn- tree ...) again" :ms 112 :tok 400 :verdict :refused
  :note "0/34 passed: the test namespace's private helpers (unit, probe-unit, render-request, caps) are now unresolved too"}
 {:step 16 :form "(def old-tree @#'seon.render.value/tree)" :ms 34 :tok 150 :verdict :refused
  :note "a def holding a fn is refused for a missing :malli/schema"}
 {:step 17 :form "(let [old-tree ... new-tree ...] (mapv ... select-nodes ...))" :ms 30 :tok 200 :verdict :ok
  :note "change proven on real selections"}
 {:step 18 :form "(in-ns 'seon.render.value-test) (deftest an-uncut-string-names-its-absolute-total ...)" :ms 35 :tok 60
  :verdict :ok :note "the reply is the Var only: no run and no red/green"}
 {:step 19 :form "(my.test/run)" :ms nil :tok 60 :verdict :timeout
  :note "MCP's 30 s bound; the run went on and recorded 52 members at 22:04:14, and no handle was returned"}
 {:step 20 :form "(seon.db/pull db '[*] [:seon.test/sym ...])" :ms 260 :tok 1001 :verdict :ugly
  :note "4 fail, the same failure text 3 times, triple-escaped, cut"}
 {:step 21 :form "(my.program/propose-merge! {})" :ms 36 :tok 50 :verdict :refused
  :note "bare string \"seon.task is refused: unassigned.\" with no next step"}
 {:step 22 :form "(in-ns 'seon.cluster) (defn mcp-get-value ... simplified)" :ms 69 :tok 1400 :verdict :refused
  :note "auto-check: offset -1 passes :int and then fails inside render.walk/continue, a real contract gap"}
 {:step 23 :form "(defn mcp-get-value ... [:int {:min 0}])" :ms 77 :tok 1400 :verdict :refused
  :note "auto-check 25/25 green; the only blocker is 1 reaching file test with an unresolved private helper"}
 ;; --- Seon agent turns, same forms, local stub provider (default cd4ca3d39) ---
 {:turn 0 :forms "(doc prepare) (clojure.repl/source tree) (my.agent/done)" :open->close-ms 4708 :prompt-tok 22741
  :verdict :same-shown-text}
 {:turn 1 :forms "(in-ns ...) (defn- tree ...) (my.agent/done)" :open->close-ms 9110 :gate-ms 70 :prompt-tok 23660
  :verdict :same-refusal-text}
 {:turn 2 :forms "(my.program/propose-merge! {}) (my.agent/done)" :open->close-ms 5880 :prompt-tok 24374 :verdict :same}
 {:turn 3 :forms "(my.agent/done)" :message->open-ms 5400 :open->request-ms 5400 :open->close-ms 6107 :prompt-tok 24478}
 {:turn 4 :forms "(in-ns 'seon.render.value-test) (my.test/run) (my.agent/done)" :verdict :completed-in-turn
  :note "evaluated 52 members inside the turn; the root restart at 22:23:18 cut off the next wake"}]
```

## 3. Findings, ranked by how much each blocks "the REPL is the best way to build"

| # | Finding | Task |
|---|---|---|
| 1 | **The install gate cannot run first-party tests.** It re-evaluates each reaching file test's body in the candidate's SCI. There, test-namespace aliases (`gen/`, `sci/`), private helpers (`unit`, `probe-unit`, `render-request`) and host helpers (`running-instances`) do not resolve, and `with-database` refuses because it has no execution handle. Every reaching test is red, so **no first-party function reached by a file test can be installed from the REPL**. The pass count moved from 15/34 to 0/34 across a restart with the same inputs. | existing `install-gate-interprets-file-tests-in-sci` (evidence added) |
| 2 | The gate judges absolute results, not the change. A test red on the base refuses every change reaching it, and the reply cannot say what the change broke. | new `the-install-gate-judges-absolute-results-not-the-change` |
| 3 | The refusal hides its reason. The reply shows only `its gate failed`. The value holds the orientation (`34 tests · 15 passed · 19 failed · auto-check 1/25`), the arguments and the failing tests, but digging for them cost 5 evaluations and about 4k tokens, and one of those digs parked the agent (#7). | existing `agent-defn-of-core-row-refused-by-install-gate`, `generated-input-gate-refuses-valid-code` |
| 4 | Entry differs by entrant. The REPL gate runs the seeded auto-check and file loads never do, so contracts nobody touched block a one-line change: `tree` returns nil for `({} {})` under a `:map` output, and `mcp-get-value`'s `:int` offset admits -1 (a real defect, found only because the REPL path was used). | new `file-loads-skip-the-auto-check-the-repl-runs`, `mcp-get-value-admits-a-negative-offset`; evidence on `an-uncut-string-node-carries-no-total` |
| 5 | **A Seon agent turn is about 100× slower than the same forms via MCP.** Message to turn open was 5.4 s, open to provider request 4–6 s, and a trivial turn took 4.7–9.1 s open to close. The same forms through MCP SCI took 13–112 ms. | new `a-trivial-agent-turn-takes-eleven-seconds` |
| 6 | The agent's context lacks what MCP shows. It has no `:errors` fault entries: 0 in a 22.7k-token prompt, while every MCP reply carried one. The trigger of a later wake never appears, so the agent cannot see why it woke. | new `an-agents-context-omits-the-faults-mcp-shows`, `a-later-wake-message-never-reaches-the-context` |
| 7 | A render refusal of one value became a turn-write refusal. Three of them **parked the agent's turn proc** ("will not re-fire"). | existing `a-transient-write-refusal-parks-the-agent-until-restart`, `late-faults-accompany-evaluated-values`, `a-renderer-is-chosen-by-key-presence` |
| 8 | There is no partial-edit entrance for a definition. Each attempt resends the whole defn: `tree` is ~1,200 tokens per try, and `mcp-get-value` ~1,300 tokens to change one keyword. A file edit is one line. | new `a-one-line-change-resends-the-whole-definition` |
| 9 | `(my.test/run)` runs the whole namespace. That is 52 members and more than 30 s, past MCP's bound, which returned a timeout with no handle. The answer is 52 maps in symbol order with no verdict, so the red test is cut from view. The `deftest` reply carries no run result. | new `run-answers-a-whole-namespace-without-a-verdict` |
| 10 | An agent cannot read the private code it must change. `doc` refuses privates, `source`/`dir`/`doc` are not referred in `user`, and `clojure.repl/source` draws a misleading `:large-output` finding. | new `an-agent-cannot-read-the-private-code-it-must-change` |
| 11 | Propose is a dead end for an agent. The refusal is a bare string with no fix naming assignment. Agents have no entrance to make their own branch, which only MCP's `branch` tool offers. | new `propose-refusal-names-no-next-step`; related existing `an-agent-cannot-see-or-choose-its-change` |
| 12 | Output noise costs tokens on every step. `transact!` returns raw datoms including the run's own bookkeeping (~1,000 tok). The same open fault repeats in every reply (~150 tok each). A test entity renders its failures 3 times. An enum refusal prints the dial's property map. A pull with a nested selector renders through the agent's declared renderer (1,871 tok of opening prose). Counts print as entity references. | new `a-transaction-report-prints-its-runs-bookkeeping`, `every-reply-repeats-open-faults`, `a-test-entity-shows-its-failures-twice`, `an-enum-refusal-prints-the-schema-properties`; existing `a-renderer-is-chosen-by-key-presence`, `a-count-prints-as-an-entity-reference` |
| 13 | A local stub provider cannot be configured for an agent. `no-provider` is `[:= true]` and cannot be lifted per agent (the fix was to retract the cluster row on the branch). `no-auth` plus a model without a descriptor makes `seon.ai/complete` refuse on a missing `:seon.ai/api-key-variable` (fault d31e076c). Test stubs also replace `seon.ai/complete` JVM-wide (runtime_status `replaced-roots`, from `turn_test`). | new `no-provider-cannot-be-lifted-per-agent`, `no-auth-refuses-at-complete`; existing `tests-redefine-default-vars` |
| 14 | Restart churn. Default restarted twice in 35 minutes. Each restart lost private defs, result handles, the stub server and an in-flight agent turn. Memory pressure refused evaluations once. | operational; no task |
| 15 | MCP-only defects. `branch list` during boot panics with a raw `#error` NPE. The `seon.dev.mcp/namespace` field says `user` while the run evaluates in the namespace where the last run ended; after the restart a scratch `def` landed in `seon.render.value`. | not filed: `seon.dev.mcp.edn` has another lane's uncommitted edit |
| 16 | A `def` holding a function is refused for a missing contract. The current law is that a missing contract is never refused at the REPL, only warned. | existing `definitions-bypass-entry-gate` (asks for the opposite, so it is stale against AGENTS.md) |
| — | `decode-vectors` threw on a UUID on every agent turn step (the provider turn could not run), and `dir` failed. Both are vector-storage V2/V1, fixed in 18ce1b1a4 and installed at the restart. | none needed |

## 4. MCP versus a Seon agent turn (root's ruling)

The same forms were run through both entrances. The agent's provider request is one user message: its REPL transcript
(`ns=> form` then the saved shown text) followed by `turns left: N of M`. **For every scripted form, the saved shown text
the agent read was byte-identical to the `#:seon.repl{...}` text MCP returned.** This covered doc, source, the refused
defn, the propose refusal and done. The differences:

| Difference | MCP | Seon agent | Finding |
|---|---|---|---|
| Open faults | `:errors` on every reply | none in the context | #6 (and #12 for the MCP side's repetition) |
| Latency per step | 13–112 ms | 4.7–9.1 s open to close, plus 5.4 s wake to open | #5 |
| Why it woke | not applicable | a later wake's message is not shown | #6 |
| Long operation | a client bound (30 s default, 120 s max) and a timeout with no handle, though the work finishes | the eval limit (30 s) applies inside the turn, and the test run completed in the turn | #9 |
| Branch | the `branch` tool creates and retires | no `my.*` entrance | #11 |
| JVM mode | available | none, by design | — |
| Envelope | JSON around an escaped EDN string (roughly a fifth of the characters are escapes) | plain transcript | one-path §5.2 deletes the re-print |
| History | the caller keeps its own | the hosted agent's context holds **every MCP run on its branch** (22.7k tokens at the first provider turn) | by design (one agent), but it grows without compaction during MCP work |
| Failure isolation | an MCP eval never parks | a render refusal parked the turn proc | #7 |
| Provider setup | not applicable | two config defects before a local provider works | #13 |

## 5. What the one-path rewrite (rev 3 in progress) already fixes

The spec is `docs/research/agent-platform/one-repl-path-spec-2026-09-27.md`; this lane did not edit it.

- **Fixed:**
  - #7, because a failing show or render stage becomes a diagnostic beside the value (V2-3, OQ1) and never a settlement refusal;
  - #16, because G1 is closed: an uncontracted definition is admitted with the note or warning;
  - the `deftest` feedback in #9, because `:seon.eval/feedback` reaching-tests carry a status (§7);
  - the MCP envelope in #15, because MCP only encodes the one reply (§5.2).
- **Partially fixed:**
  - #3, because G5 makes reaching-test results facts that name the failing test, but its own proofs still print "its gate failed" as the facts error;
  - #1, because §5.1 runs reaching tests as test agents. The spec does not say how a host-authored file test's private helpers and requires resolve in that agent's SCI. That is the reach-executor ruling the existing task waits for;
  - #6 (faults), because every entrant gets the same reply keys, but nothing states that open faults render into the next context.
- **Not addressed:** #2, #4, #5, #6 (wake message), #8, #9 (namespace-wide run), #10, #11, #12, #13, #14.

## 6. Smallest fix for each of the rest

| # | Smallest fix |
|---|---|
| 1 | Run unchanged file tests as their compiled Vars with only the differing definitions interpreted (the task's option). Until then, make the gate's reaching set exclude members that fail on the base with the same error (see #2). |
| 2 | Run the gate's tests on the base once (they are cached by reach digest) and refuse only tests that go from green to red. Report pre-existing reds as data. |
| 3 | Put `:seon.test.accretion/orientation` and the first failure group (test symbol or generated arguments, expected, actual) into the reply's error map. This is about 10 lines in `install-refusal`, using the renderer that already exists. |
| 4 | Run the seeded auto-check at publication for changed contracted definitions and report it as feedback, or report it as feedback (not refusal) on both paths until the contracts are clean. |
| 5 | Profile the wake-to-open and open-to-request windows (profile hooks exist) and name the armed functions. The earlier issue `a-turn-spends-seconds-before-its-provider-call.md` places most of it in the prelude. |
| 6 | Render the trigger message and the open-fault observation in the context walk, reading the same saved observation the MCP reply shows. |
| 8 | Add `my.program/edit!`: take `[sym old-form new-form]`, splice at the stored declaration's form, and evaluate the result as the `defn` would. This reuses rewrite-clj, which `my.edit` already uses for files. |
| 9 | Let `my.test/run` accept `:seon.test/identities` and answer `seon.test/tally`'s verdict (passed?, counts, reds first). Have MCP return a result handle on timeout. |
| 10 | Serve `doc` and `source` of private declarations from the stored entity, and refer `source`/`doc`/`dir` in agent namespaces. |
| 11 | Put the assignment query and `seon.task/assign!` form in the refusal's `:seon.error/fix`, and add a `my.program/branch!` over `seon.cluster.registry/branch!`. |
| 12 | Each is a renderer change: a transaction report shows the caller's changed entities; faults show once per evaluation; the test renderer shows its failures once; enum refusals print the value. |
| 13 | Make `no-provider` a boolean dial, and admit a no-auth target in `complete`'s contract. |
| 14 | Root batches restarts (already the law). An agent's REPL needs its private state to survive, or needs to be told the restart happened. The run after a restart showed no notice. |

## 7. Time and cost: REPL versus the file workflow for this change

| | REPL (this run) | File workflow (estimate) |
|---|---|---|
| Wall time to a proven behavior | about 25 min over 23 MCP steps, excluding the agent-turn comparison. The sum of armed `:ms` is about 1.2 s; the rest was round trips, digging into refusals and two restarts | about 5 min: read 2 functions (~3k tokens), a 2-line edit plus the contract, write the test, lint `src test` (~30–60 s) |
| Tokens read | about 25k over 23 steps. Two whole-defn resends (~2.6k per round, 4 rounds) and refusal digging (~6k) dominate | about 5k |
| Feedback | immediate but mostly unusable: behavior was proven in seconds; the gate named no cause in its reply; auto-check found a real contract defect (-1) the file path never would; tests could not run through the gate | late but usable: the next central round (restart 40–90 s plus `seon.render.value-test` ~22 s plus `seon.cluster.mcp-test`) names each red test, but in practice lands 30–120 min later behind batched restarts |
| Outcome | **not installable** (gate) | lands at the next central round |

Per step the REPL is faster (milliseconds) and catches more (the auto-check). As a path to landing a first-party change
it does not work today. Findings #1–#3 alone make it impossible, and #8 makes each iteration cost more tokens than the
file path.
