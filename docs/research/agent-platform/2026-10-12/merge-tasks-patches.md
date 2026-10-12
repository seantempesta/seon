# Patches for files held by other lanes (lane merge-tasks, hybrid-done-and-merge task filing)

Lane `cn-renames` holds `resources/seon/tasks/seon.turn.edn`, `resources/seon/tasks/seon.task.edn`,
`test/seon/context_test.clj`, `test/seon/test_test.clj` and `test/seon/turn_continue_test.clj`; lane `root-docs` holds
the untracked `resources/seon/tasks/seon.vocabulary.edn`. The entries below are the rest of the filing in
`docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md` §5 and §6. Apply them in the owner's next commit
of each file, then run `bin/lane commit LANE -m MSG --test seon.task-ingestion-test PATHS`.

## 1. `resources/seon/tasks/seon.turn.edn`

Delete these four entries (retired: their subjects, `seon.turn/end-task!`'s merge clause, `end-merge-request`,
`land-end!` and `root-execution`, are deleted by M8):
`a-merge-request-comes-only-from-a-step-no-task-needs`, `an-end-requests-a-merge-at-most-once`,
`a-green-end-lands-its-merge`, `a-landing-acquires-roots-world-when-root-has-no-branch-row`.

Replace the entry `a-held-task-opens-no-model-turn` (M13, reused) with:

```clojure
#:seon.task{:name "a-held-task-opens-no-model-turn"
             :kind :function
             :intent "Phase P10 of hybrid-step-semantics.md (verify-astra #8), as filed: three starters exist (`seon.cluster.agent/plan`, `seon.cluster.agent/agents-to-start`, `seon.cluster.host/start!`'s graph start) and `seon.turn/next-agent-work`'s held clause opens model turns for a :held task; every graph starts from plan over an assignment of a :running task; :held means no assignment and no model turn; an MCP entrant declares ROOT or AGENT. (hybrid-done-and-merge M13) Delete `seon.cluster.agent/agents-to-start`, `seon.cluster.host/start!`'s graph start and the held clause; `src/seon/mcp.clj` declares the entrant kind; `src/seon/turn.clj` as a patch from lane program-context. Red proof: this task's deftest in `test/seon/turn_test.clj` (a :held task with a wake gets no assignment and no model turn after one `db<->flow` run); `test/seon/cluster/lazy_agents_test.clj` converts. After M5 (`seon.flow/an-ended-attempt-reaches-main-in-one-transaction-and-its-branch-is-deleted`) and M12."
             :must-read #{seon.turn/next-agent-work seon.cluster.agent/agents-to-start seon.cluster.host/start! seon.cluster.agent/plan}
             :needs #{[:seon.task/sym seon.cluster.agent/each-intent-names-one-roster-state]}
             :evidence #{"docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md"}}
```

## 2. `resources/seon/tasks/seon.task.edn`

Replace the entry `the-ready-leaves-are-those-whose-needs-are-done` (M8, reused) with:

```clojure
#:seon.task{:name "the-ready-leaves-are-those-whose-needs-are-done"
             :kind :function
             :intent "Card S2 (`seon.task/ready` answers the handles that should run now over a database value as two `seon.db/q` reads with `seon.task/ready-rules`, the eligible leaves minus `blocked`, evaluated unbound; `seon.task/done?`; `seon.task/resolution`; proof: P1's twelve scenarios on the fifty-leaf tree and the ready read under 10 ms through `seon.profile/measure`). (hybrid-done-and-merge M8, reused) `leaf-done` in `seon.task/ready-rules` and `seon.task/done?` read 'the latest assignment of the leaf has `:seon.task/outcome` `:merged`' instead of an assignment's `:done` end; the scenarios and the cost bound survive."
             :must-read #{seon.task/ready seon.task/ready-rules seon.task/done? seon.task/resolution seon.db/q seon.profile/measure}
             :evidence #{"docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md"}}
```

Add this entry (M8, new):

```clojure
#:seon.task{:name "a-done-branch-merges-with-no-merge-request-written-by-the-agent"
             :kind :function
             :intent "(hybrid-done-and-merge M8) Delete the agent's verb and the request; done has no fact. Delete `seon.task/request-merge!`, `my.program/request-merge!`, `seon.task/red-first`, `seon.turn/end-merge-request`, `seon.turn/land-end!`, `seon.turn/land-ended!`, `seon.turn/landing-tx`, `seon.turn/root-execution`, the `source-land!` delay and `seon.cluster.source/land!`, `:seon.task/merge-request-row` and `:seon.task/merge-request-view` with their members, `:seon.task/kind` `:merge`, `:seon.config.root/land?` (declared in `resources/seon/schemas/seon.config.root.edn`, set in `config/default.edn`); `:done` leaves the `:seon.task.attempt/end` enum (root's `:seon.task/outcome` `:merged` is the record); `close-tx` closes a `:satisfied` step with outcome `:closed` and reason `satisfied`; `leaf-done` in `seon.task/ready-rules` and `seon.task/done?` read 'latest assignment `:merged`'; `seon.turn/end-task!` writes only non-done ends; `seon.task/assignment-read` refuses a task whose proofs are all green by the same rule as M1; the apply step runs `:merge` when the source is done, the gate is green and nothing is unmerged. Docs: `AGENTS.md` and `.agents/skills/repl/SKILL.md` lines naming `my.program/request-merge!`; `docs/seon/plan.md` lines naming `seon.cluster.source/land!` and `:seon.config.root/land?`; `docs/seon/schedule/cards/TASK-CHAIN.md` (the line naming `land-end!`). Retired with their subjects: `seon.cluster.agent/a-running-agents-refinement-reaches-main-at-the-next-pass` and the four `seon.turn` tasks `a-merge-request-comes-only-from-a-step-no-task-needs`, `an-end-requests-a-merge-at-most-once`, `a-green-end-lands-its-merge`, `a-landing-acquires-roots-world-when-root-has-no-branch-row`. Red proofs: `my.root-test/a-done-branch-merges-with-no-merge-request-written-by-the-agent` (outcome :merged; the branch holds no `:seon.task/merge-request` and no :done end) and `a-satisfied-task-closes-with-no-model-call` (all proofs green at the base: :closed, reason satisfied, zero model turns). Cost: detection under 5 ms per changed branch. Platform slice (`--test`); `src/seon/turn.clj` as a patch. After M7."
             :must-read #{seon.task/request-merge! my.program/request-merge! seon.task/red-first seon.turn/end-merge-request seon.turn/land-end! seon.turn/land-ended! seon.turn/landing-tx seon.turn/root-execution seon.turn/end-task! seon.cluster.source/land! seon.task/ready-rules seon.task/done? seon.task/assignment-read seon.task/close! seon.context/step-verdict}
             :needs #{[:seon.task/sym seon.cluster.squash/a-merge-whose-reached-definition-moved-writes-nothing]}
             :evidence #{"docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md"}}
```

## 2b. `resources/seon/tasks/seon.test.edn` (claimed by merge-tasks, entry left in place)

The entry `change-reds-judges-a-test-main-does-not-hold-as-not-red-there` is retired by M7 (`seon.test/change-reds` is
deleted; the behavior is `broken` at B, proven by M7's second deftest). It stays filed because its deftest in
`test/seon/test_test.clj` (362) is tagged with it and `bin/seon check` refuses a dangling tag. In the same commit as
the `test_test.clj` retag of section 5, delete the entry.

## 3. `resources/seon/tasks/seon.vocabulary.edn`

Add this entry (M18, new) beside `living-prose-uses-library-words`:

```clojure
#:seon.task{:name "the-merge-path-names-no-retired-identifier"
             :kind :function
             :intent "(hybrid-done-and-merge M18) Scripted word sweep, one commit, lint zero: attempt becomes assignment (`:seon.task.attempt/*` becomes `:seon.task.assignment/*`, `seon.task/attempt-tx` becomes `assignment-tx`), `land`, `copy` and `settle` become `merge` and `delete-branch`, `:root` becomes `:unmerged` in the `:seon.cluster/conflict` enum (`resources/seon/schemas/seon.cluster.edn`) and in `seon.program/cluster-attributes`' default, `seon.cluster.registry/retire-branch!` becomes `delete-branch!`; entries in `docs/seon/architecture/vocabulary.md` (`frontier` became `ready` in 2ef43687f, CN-27). Red proof: `seon.vocabulary-test/the-merge-path-names-no-retired-identifier`, a table of the identifiers this phase removes (`seon.task.attempt/`, `attempt-tx`, `retire-branch!`, `:root` as a `:seon.cluster/conflict` value) and, for each, the files under `src`, `test`, `resources/seon` and `script` whose text holds it, asserted empty; the proof builds each identifier string from parts so `test/seon/vocabulary_test.clj` does not hold them; red today for every entry, independent of `seon.vocabulary/report`. After M17, M16, M15, M14, M12, M11, M10 and M9."
             :must-read #{seon.task/attempt-tx seon.cluster.registry/retire-branch! seon.program/cluster-attributes}
             :needs #{[:seon.task/sym seon.cluster.registry/a-merged-base-older-than-the-window-survives-collection]}
             :evidence #{"docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md"}}
```

## 4. Needs that name tasks of the files above (add once those entries exist)

Ingestion refuses a lookup ref to a task that does not exist, so these were left out of the committed entries:

| Entry | Add to `:seon.task/needs` |
|---|---|
| `seon.cluster.squash/an-accepted-merge-is-published-by-the-next-pass` (M9) | `[:seon.task/sym seon.task/a-done-branch-merges-with-no-merge-request-written-by-the-agent]` |
| `seon.cluster.agent/a-join-forks-main-after-both-needs-merge` (M10) | the same M8 task |
| `seon.cluster.message/root-offers-a-sent-message-once-and-merges-its-record` (M16) | `[:seon.task/sym seon.turn/a-held-task-opens-no-model-turn]` |
| `seon.cluster.registry/a-merged-base-older-than-the-window-survives-collection` (M17) | `[:seon.task/sym seon.turn/a-held-task-opens-no-model-turn]` (its M6 need is filed) |
| `seon.cluster.agent/the-real-chain-runs-with-no-nudges` (M-final) | `[:seon.task/sym seon.vocabulary/the-merge-path-names-no-retired-identifier]` |

## 5. Retags (drop the tag of a retired task; the test stays until its subject is deleted)

| File | Line | Change |
|---|---|---|
| `test/seon/context_test.clj` | 271 | remove `'seon.turn/a-landing-acquires-roots-world-when-root-has-no-branch-row` from the `:seon/tasks` set |
| `test/seon/turn_continue_test.clj` | 517 | remove the tag `'seon.turn/an-end-requests-a-merge-at-most-once` (the set becomes empty: drop `:seon/tasks` there) |
| `test/seon/turn_continue_test.clj` | 571 | remove `'seon.turn/a-green-end-lands-its-merge` (same) |
| `test/seon/turn_continue_test.clj` | 679 | remove `'seon.turn/a-landing-acquires-roots-world-when-root-has-no-branch-row` (the metadata map becomes empty: drop it) |
| `test/seon/test_test.clj` | 362 | remove `'seon.test/change-reds-judges-a-test-main-does-not-hold-as-not-red-there` (`:seon.test/long` keys stay) |

Done in this lane: the tags `seon.turn/a-merge-request-comes-only-from-a-step-no-task-needs` in
`test/seon/cluster/db_flow_test.clj` (two deftests) were dropped, and the entries `seon.program/merged-and-attempts-are-cluster-attributes`,
and `seon.cluster.agent/a-running-agents-refinement-reaches-main-at-the-next-pass` were deleted.
