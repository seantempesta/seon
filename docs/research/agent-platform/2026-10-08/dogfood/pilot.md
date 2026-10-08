---
title: "Dogfood pilot: one card through the Seon-agent loop"
type: research
status: active
created: 2026-10-08
---

# Dogfood pilot: one card through the Seon-agent loop

Lane `dogfood-pilot` ran card UL-8a (`my.program/caller-data` reads its call sites with `seon.db/pull-many`) through
the loop a Seon agent will run: root files the task and its proof, assigns it, the agent reads its context block,
works at the REPL on its branch, writes back and commits. Default ran commit `6a244149d` (F4a and RF-1 installed,
RF-2 not landed). Wall clock 16:57 to about 17:15 JST.

**Why UL-8a.** An ordinary `my.*` read (not host-bound), one private function, files `src/my/program.clj` and
`test/my/program_test.clj` unclaimed, card already names the seams (`seon.db/pull-many`, `db.clj`; Datahike
`pull_api.cljc:541-556`) and the before/after REPL comparison.

## Outcome

- The change itself worked at the REPL and wrote back cleanly: `defn` on the branch, value equal to the compiled
  version on three subjects (`seon.turn/open?`, `seon.db/pull`, `seon.id/id`) except for `caller-data`'s own call
  site (it now calls `pull-many`), `my.program/write-branch!` replaced exactly the one declaration (25+/46-).
- The loop around it did not work as specified. **Task filing, assignment and the agent-written proof all failed**
  (frictions 1-6 below). The work ran unassigned, the proof is the card's added assertion written into the test file
  by hand, and the read-count property the task is about could not be asserted by any test an agent can write.
- Commit through the throwaway check (`bin/lane commit … --test my.program-test --test my.program-read-test`); RF-2's
  `--branch-evidence` is not in HEAD.

## Timeline and measurements

Times are the reply's `:ms`; calls are the reply's `:calls` (armed calls). Shown size from the reply's own findings
where it reported one.

| Step | Form | ms | calls | Result |
| --- | --- | --- | --- | --- |
| 0 status | `runtime_status` | — | — | degraded: 2 over-bound warnings, 23 errored receipts |
| 1 probe (root, JVM) | `my.program/callers` with `seon.db/*read-evidence-sink*` | 2 / 354 / 49 | — | `seon.turn/open?` 5 callers 15 reads; `seon.db/pull` 624 callers **1,638 reads, 354 ms**; `seon.id/id` 122 callers 306 reads |
| 1 file task (root, JVM) | `seon.task/create!` | 174 | — | `a182d4e4a636`, runtime row only (resource file held, friction 1) |
| 2 assign (root, JVM) | `seon.task/assign!` | 43 | — | refused `:no-proofs` (friction 2) |
| 3 context (agent) | `(seon.context/current-state)` | 2 | 732 | no task; printed the evaluation world (friction 7) |
| 3 context (agent) | `(my.task/open)` | 10 | 250 | `[]` |
| 4 deftest proof (agent) | `deftest` in `my.program-test` | 1 | 12 | refused by the writer (friction 3) |
| 4 before value (agent) | `caller-data` on 3 subjects | 533 | 97,884 | shown 1,208 of 89,576 value tokens |
| 4 defn (agent) | `defn- caller-data` | 38-56 | ~10,400 | admitted; Var render: 6 pending tests (5 listed) |
| 4 compare (agent) | 3 subjects, interpreted | 64 | 82,596 | equal except the changed function's own site |
| 4 reaching tests (agent) | `my.test/check {:seon.test/changed [caller-data]}` | 15,174 | 5,844,058 | 6 executed, all green |
| 4 again (pilot-2, under check load) | same | 20,360 | 6,572,749 | 5 reused, 1 red: `schema-and-namespace-reads-keep-different-connections-distinct` over its 5 s bound (13.0 s), red before (task `my.program/breaks-on-a-namespace-subject-takes-five-seconds`) |
| 4 same, first attempt | same | >32,000 | — | MCP gave up (task in `seon.dev.mcp.edn`, `timeout_ms`) |
| 5 write-back (pilot) | `write-branch!` | 52-61 | ~1,100 | refused, undigested `pilot.scratch` (friction 8) |
| 5 write-back (pilot-2, same request as defn) | `write-branch!` | 56 | 853 | "The accepted merge changed no declaration." (friction 10) |
| 5 write-back (pilot-2) | `write-branch!` | 484 | 15,306 | `src/my/program.clj` replaced `[:seon.fn/sym my.program/caller-data]` |
| 5 commit | `bin/lane commit` throwaway check | see landing | — | queued behind another check |

Reads after the change could not be counted at the agent REPL (friction 5); the card's expectation is 3 queries plus
2-3 `pull-many`, independent of the caller count. Root measures it on default after the restart that loads the
commit, with the same JVM form as step 1.

Calls the agent made: 27 `eval_clj` (2 lost to the two-branch restart), 1 `runtime_status`. Token sizes of most
replies were small (one value line); the large ones were the before value (1,208 shown), the `my.test/check` reply
(1,406 shown of 4,081), a namespace pull (1,454 shown of 10,798) and `remove-ns!` (about 250 tokens of constant
prose, task `my.program/ns-unmap-and-remove-ns-cost-quarter-thousand-tokens-and-most-of-a-second`).

## Friction (each is a task for its owner)

The task resources are all claimed by lane `task-census` (84 paths, dirty), so these could not be written to
`resources/seon/tasks/<ns>.edn` by this lane (friction 1). The EDN below is ready for the holder or root to add.

1. **A lane cannot file its task as data.** One lane holding every `resources/seon/tasks/*.edn` blocks every other lane's
   step 1. `seon.task/create!` makes the row, but it lives only in the database (existing task in `seon.task.edn`,
   "A task made by `seon.task/create!` exists only in the database").
2. **No task can be assigned by the documented loop.** `seon.task/assign!` reads proofs from the home database only,
   so a proof reaches it only by commit plus a restart of default; and `bin/seon check` refuses the commit while that
   proof is red. A green proof is refused `:proofs-green`. Red-before acceptance exists only in uncommitted RF-2.
3. **An agent cannot `deftest` in a file-backed test namespace.** In `my.program-test` and `seon.id-test` every REPL
   `deftest` (even `(deftest t (is (= 1 1)))`) is refused by `seon.db`'s `write-digest-error`: the stored definition
   digest differs from the one the writer derives with the namespace's resolver context. In a fresh namespace the
   same form is admitted. The edit hook (RF-4) hits the same refusal for every test-file edit.
4. **`seon.test-support` has no public Vars in the agent's SCI context**, so `support/with-database` (the harness
   world every test uses) is unresolvable when an agent writes a test.
5. **SCI `binding` of a host dynamic Var does not reach host code.** `(binding [seon.db/*read-evidence-sink* sink]
   (seon.db/q …))` in SCI sees the binding but `seon.db` appends nothing; test bodies run in SCI, so no test can
   assert read counts or any other dynamic-Var-observed behavior.
6. **Refactors and cost fixes have no red proof.** The value is unchanged by design; with 5 the cost is not
   observable from a test either, so `assign!` refuses the task either way.
7. **`(seon.context/current-state)` prints the evaluation world** (connection, caps, SCI ctx, profile, two schema
   fingerprints that differ) instead of the block; with no assignment the block is only turns-left. The agent learns
   nothing about its card from it.
8. **A namespace made by `in-ns` poisons the branch.** After `my.program/remove-ns!` the namespace row stays (each
   evaluation in it refers to it) without a definition digest, and `my.program/diff` and `write-branch!` refuse forever
   on that branch, even when `:seon.program/identities` names only another declaration. The pilot moved to a second
   agent branch to finish.
9. **A refusal map shows as its message only.** `diff` and `write-branch!` answered the bare text "Program
   declarations are missing their stored definition digest."; the offending `:seon.program/undigested-declarations`
   was visible only by digging the result handle.
10. **`write-branch!` in the same request as the `defn` sees the branch before settlement** and answers "The accepted
    merge changed no declaration." (`:no-replacement`), which names a merge the agent never made.
11. **`my.test/check` refuses a test named as changed** ("Changed identity has no definition, history or incoming
    edge", looked up as `[:seon.fn/sym test]`); the testing skill says a named test is its own member.
12. **`(my.program/diff {})` refuses** for a missing `:my.program/base-ctx`: supply does not fill world members
    missing from a map argument. `(my.program/diff)` works.
13. **clojure.test prints into replies**: `:out` carries "Testing my.program-test" lines and FAIL text, outside the
    value printer.
14. **Small render defects**: the Var render counts 6 pending tests and lists 5; a contract refusal shows a Malli
    generated example `[:./R tV]`; `(seon.db/pull db [:seon.ns/name :seon.program/definition-digest] …)` on a
    namespace renders the whole related-data page (10,798 value tokens) instead of the two selected keys; the
    `assign!` refusal says "seon.task is refused: no-proofs." without naming the task or the fix (tag a deftest).

### Task EDN for the holder of the resources

```clojure
;; resources/seon/tasks/my.program.edn
#:seon.task{:name "callers-reads-each-caller-separately"
            :kind :function
            :intent "`my.program/callers` (through `my.program/caller-data`) read each caller with its own pull, a second pull when it is a test and its own query for `:seon.fn/call-arities`: `seon.db/pull`'s 624 callers cost 1,638 reads and 354 ms. Done when the call sites are read with `seon.db/pull-many` in a bounded number of reads whatever the caller count, the value unchanged. Proof: `my.program-test/program-reads-name-the-referrers-and-propose-without-writing` (declared arities on every site); the read count waits on `seon.sci/binding-a-host-dynamic-var-does-not-reach-host-code`."
            :must-read #{my.program/callers seon.db/pull-many}
            :evidence #{"docs/research/agent-platform/2026-10-08/dogfood/pilot.md" "docs/seon/schedule/cards/UL-8.md"}}
#:seon.task{:name "an-in-ns-namespace-blocks-diff-and-write-branch"
            :kind :function
            :intent "A namespace an agent makes with `in-ns` keeps a row with no `:seon.program/definition-digest` after `my.program/remove-ns!` (its evaluations refer to it), and `my.program/diff` and `my.program/write-branch!` then refuse on that branch, even with `:seon.program/identities` naming another declaration. Done when such a namespace either carries its digest or is outside the compared identities, and write-branch! with given identities compares only those."
            :must-read #{my.program/remove-ns! my.program/write-branch! seon.program/digest-map}
            :evidence #{"docs/research/agent-platform/2026-10-08/dogfood/pilot.md"}}
#:seon.task{:name "write-branch-in-the-defn-request-answers-a-merge-changed-nothing"
            :kind :function
            :intent "`my.program/write-branch!` evaluated in the same request as the `defn` it should write reads the branch before the pass settles and answers `:no-replacement` \"The accepted merge changed no declaration.\" Done when it answers in write-branch terms (nothing on the branch differs from its head yet; evaluate it after the defn's request)."
            :must-read #{my.program/write-branch! seon.cluster.source/write-back!}
            :evidence #{"docs/research/agent-platform/2026-10-08/dogfood/pilot.md"}}

;; resources/seon/tasks/seon.db.edn
#:seon.task{:name "a-repl-deftest-in-a-file-backed-test-namespace-is-refused"
            :kind :function
            :intent "Every `deftest` an agent evaluates in a file-backed test namespace (`my.program-test`, `seon.id-test`) is refused by `seon.db`'s write-digest check: the stored `:seon.program/definition-digest` is not the one the writer derives with the namespace's resolver context. The same form in a fresh namespace is admitted, and the edit hook's test-file edits hit the same refusal. Done when a REPL deftest in an existing test namespace is admitted with the digest the writer derives, proven by a deftest that evaluates one through the one path in `my.program-test`."
            :must-read #{seon.fn/definition-digest seon.db/transact!}
            :evidence #{"docs/research/agent-platform/2026-10-08/dogfood/pilot.md"}}

;; resources/seon/tasks/seon.sci.eval.edn
#:seon.task{:name "binding-a-host-dynamic-var-does-not-reach-host-code"
            :kind :function
            :intent "In the agent's SCI context `(binding [seon.db/*read-evidence-sink* sink] (seon.db/q …))` makes the Var look bound to SCI code, but `seon.db` (host code) appends nothing, so a test body (run in SCI) cannot observe reads or any host dynamic Var. Done when a binding of an exposed host dynamic Var in SCI is the host thread binding for the host calls inside it."
            :must-read #{seon.sci.eval/build-base-ctx seon.db/*read-evidence-sink*}
            :evidence #{"docs/research/agent-platform/2026-10-08/dogfood/pilot.md"}}
#:seon.task{:name "test-support-has-no-vars-in-the-agent-context"
            :kind :function
            :intent "`(count (ns-publics 'seon.test-support))` is 0 in an agent's SCI context, so a test an agent writes at its REPL cannot use `seon.test-support/with-database`, the world every test uses. Done when the harness Vars resolve in the agent's context."
            :must-read #{seon.sci.eval/build-base-ctx seon.test-support/with-database}
            :evidence #{"docs/research/agent-platform/2026-10-08/dogfood/pilot.md"}}

;; resources/seon/tasks/seon.task.edn
#:seon.task{:name "assign-cannot-see-a-red-proof-the-gate-will-not-land"
            :kind :function
            :intent "`seon.task/assign!` reads proofs from the home database only, a proof reaches the home only by commit and restart, and `bin/seon check` refuses a commit whose named test is red; a green proof is refused `:proofs-green`. So the documented loop (root files task and red proof, then assigns) cannot assign anything, and a cost or refactor task (value unchanged) never has a red proof. Its refusal text \"seon.task is refused: no-proofs.\" names neither the task nor the fix. Done when root can assign a task whose red proof exists on the agent's branch or in an accepted-red commit, and the refusal names the task and the tag to add."
            :must-read #{seon.task/assign! seon.task/task-edges}
            :evidence #{"docs/research/agent-platform/2026-10-08/dogfood/pilot.md"}}

;; resources/seon/tasks/seon.context.edn
#:seon.task{:name "current-state-prints-the-world-not-the-block"
            :kind :function
            :intent "`(seon.context/current-state)` at the agent REPL shows the evaluation's world (connection, admission caps, SCI ctx, render profile) beside a value of turns-left only. Done when the agent reads its block: task, card references, branch, pending proofs, and nothing of its world."
            :must-read #{seon.context/current-state seon.context/current-state-ai}
            :evidence #{"docs/research/agent-platform/2026-10-08/dogfood/pilot.md"}}

;; resources/seon/tasks/my.test.edn
#:seon.task{:name "check-refuses-a-test-named-as-changed"
            :kind :function
            :intent "`(my.test/check {:seon.test/changed ['ns/a-test]})` refuses \"Changed identity has no definition, history or incoming edge\" (looked up as `[:seon.fn/sym …]`), and its runs print clojure.test's \"Testing ns\" and FAIL lines into the reply's `:out`. Done when a named test is its own member and the report reaches the agent only as data."
            :must-read #{my.test/check seon.test/run}
            :evidence #{"docs/research/agent-platform/2026-10-08/dogfood/pilot.md"}}
```

Frictions 9, 12 and 14 belong to existing classes (render of error values, supply of map members, render defects);
root folds them into those owners' rows.

## What the template changes

`lane-templates/repl-slice.md` now states the loop as a Seon agent runs it, with the gaps above as named steps that
root does by hand until their tasks close: root files task and proof and assigns; the lane reads its block first,
works only from the block, the card and the REPL, writes back, commits, and files friction.
