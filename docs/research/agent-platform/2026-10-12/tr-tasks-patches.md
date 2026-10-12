# Patches for files held by lane m3

Lane `tr-tasks` filed hybrid-test-results phases R1-R12 as tasks. `resources/seon/tasks/seon.cluster.squash.edn` is held by lane m3, so R8's entry and one `:needs` edit wait here for its owner (or root) to apply.

## 1. Append to `resources/seon/tasks/seon.cluster.squash.edn` (R8, after M7)

```clojure
 #:seon.task{:name "main-keeps-the-merge-results-after-the-branch-is-deleted"
             :kind :function
             :intent "(hybrid-test-results R8, after M7) Root reads branches and the merge gate's answers stay on main. Root's `seon.test.runner/results-at` databases are main and every live branch head; the gate's run on the merge branch is admitted on main through R5's `seon.test/admit-run` (tested branch the merge branch), as root recording its own observation (owner: decided). Red proof: new `seon.cluster.merge-test/main-keeps-the-merge-results-after-the-branch-is-deleted`: after merge and branch deletion, `results-at` on main holds the gate's results at the merged keys and root's next request executes no body for them (HEAD: `:seon.data` runs stay on the deleted branch). Cost: recording increment under 100 ms; root's read over live branch heads under 20 ms (0.15 ms per head). Files: `src/seon/test/runner.clj`, `src/seon/test.clj`, M7's merge owner (today `seon.cluster.source/accept-merge!`), `test/seon/cluster/merge_test.clj`"
             :must-read #{seon.cluster.source/accept-merge! seon.test/admit-run seon.test.runner/result-at-reach seon.context/main-database}
             :needs #{[:seon.task/sym seon.cluster.squash/a-merge-whose-reached-definition-moved-writes-nothing] [:seon.task/sym seon.test/a-base-run-is-kept-on-the-agents-branch]}
             :evidence #{"docs/research/agent-platform/2026-10-12/hybrid-test-results.md"}}]
```

## 2. Add R7 to M7's needs in the same file

R6 and R7 land before M7 so M7's gate inherits `:seon.context/unknown` (hybrid-test-results §6, Order). In task `a-merge-whose-reached-definition-moved-writes-nothing` replace

```clojure
:needs #{[:seon.task/sym my.root/a-merge-is-keyed-by-the-assignment]}
```

with

```clojure
:needs #{[:seon.task/sym my.root/a-merge-is-keyed-by-the-assignment] [:seon.task/sym seon.context/every-agent-sees-where-its-reds-came-from]}
```

Both edits were checked: the file still reads as an EDN vector and every `:needs` target exists.
