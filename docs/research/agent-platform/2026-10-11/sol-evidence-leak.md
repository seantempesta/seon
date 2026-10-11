# sol-evidence-leak: measured cause and handoff

The reported cross-read is a leaked fixture attempt branch, not a test-symbol result-cache key. The result reuse path already compares the exercised reach digest. A second fixture reacquires the first fixture's attempt branch and genuinely executes its old source, recording that old source's reach digest.

## Evidence

Default ran committed archive `2b49056a8`. Initial status was degraded by existing `seon.profile/adder?` untested-hot and SCI base/overlay cache-discipline observations. No default process was stopped, reset, restarted or loaded from uncommitted files.

Two MCP SCI agents, `sol-evidence-leak-a` and `sol-evidence-leak-b`, defined `scratch.sol-proof/same-symbol` on separate branches:

```clojure
(ns scratch.sol-proof)
(clojure.test/deftest same-symbol (clojure.test/is (= 1 2)))
;; B instead defines (= 1 1).
(my.test/check {:seon.test/identities #{'scratch.sol-proof/same-symbol}})
(my.test/check {:seon.test/identities #{'scratch.sol-proof/same-symbol}
                :seon.test/rerun? true})
```

A correctly answered false and B true, including unchanged reuse. Forced execution took A 239 ms / 85.6 MB and B 191 ms / 93.2 MB. Each executed one member and reused zero. Stored definition digests differed: A `c587d0edbf4d9f42af2a7bd5d3216cbe1f221cc9ed715c1e5ffc487db7005a25`, B `92d210a38f424a8498a02d98e9f6c25991d5253ba00769a7db42b9f09297fddb`.

The first independent-fixture candidate proof, using `seon.test-support/define!` on two canonical fixture branches, also passed: run `9d01db4f4174`, 11 assertions, 3,339 ms. No evidence-cache producer defect was observed.

The decisive JVM probe acquired those two scratch agents through `seon.cluster.agent/acquire-context!`, called `seon.cluster.agent/fork-attempt!` twice with the same disposable branch name, first from A then B, and pulled that branch's `:seon.test/source`. A held `(= 1 2)`, B held `(= 1 1)`, and the attempted branch held `(= 1 2)`. The form took 117 ms. Running `seon.test/run` as B on that acquired attempt branch, with explicit custody and forced execution, returned red: run `44bb6b014062`, executed 1, reused 0, fail 1, error 0; 384 ms / 142.5 MB. Its recorded reach digest was `fad56a1611d5ade84eb1a24719ee45c8027022df4ece1e6065163c4ba197be80`, the red body's reach. Both disposable attempt branches were retired in `finally`.

## Red proof

`seon.test-test/same-test-symbol-on-two-branches-runs-each-bodys-content` reuses the existing `seon.cluster.db-flow-test/chain-world` fixture, starts its attempt paused through `seon.cluster.agent/plan` and `seon.cluster.agent/apply-plan!`, and forces then reuses its named proof through `seon.test/run`. Two successive worlds define the same task/proof identities with red and green bodies.

Candidate `428a20b02e509f7fba8f3de7c9c674bb0f5b97d0`, run `94e3a828aab5`: 8 pass, 3 fail, 0 error. The second world's forced execution returned false, fail-count 1, and its unchanged check also returned false. This is the requested red cross-read proof. Check diagnostic: `data/clusters/default/logs/operator-check-20261011T224338.118.edn`. The runner reported 6,026 ms for the whole nested proof; the smaller equivalent candidate fixture proof reported 4,001 ms (run `1d6e316893b6`, 8 pass / 3 fail / 0 error). The candidate runs are explicit investigation evidence, not positive landing proof or a timeout exemption.

## Producer and patch

`seon.cluster.agent/plan` derives an attempt branch from `[task-handle attempt-number]`. `seon.cluster.agent/fork-attempt!` correctly reacquires an existing store roster name for restart. `seon.cluster.agent/release-context!` explicitly preserves borrowed branches. `seon.cluster.db-flow-test/chain-world` owns its test-created attempt branches but its final cleanup only released executions; the next fixture consequently found the previous fixture's attempt branch. Its unique task namespace workaround avoided that collision.

`sol-evidence-leak.patch` changes the fixture owner to retire only the attempt branches of its own task handles after its final graph cleanup, and makes the green-chain test use the same task namespace as the red-chain test again. Mid-fixture `release!` preserves branches for the restart proof. The patch was prepared without editing `db_flow_test.clj`, which belonged to `prove-to-model` at diagnosis; the assignment explicitly names it as a foreign boundary. No cache key, refusal, filter or retry is added.

The handoff was taken by `sol-loose-ends`. After `bca39033a`, candidate `9251858bbc5844894f1ed9d08a61c3b8089f65ad` still reproduced the cross-read: run `5ce277a54f08`, 8 pass / 3 fail / 0 error, 6,268 ms. The fixture patch was adapted to the work-launcher nesting now installed. Candidate `307b5969343f3c40e8e1482557472eeacf089164` passed the regression and both chain tests: run `33e07c956d6b`, 28 pass / 0 fail / 0 error, 8,773 ms aggregate. Full lint: 0 errors / 1,534 warnings, 7,021 ms. The nested regression's cost remains above one second; these checks establish correctness, not sub-second performance. The final commit check names every test calling the fixture. No changes were made to `src/seon/test.clj` or `src/seon/test/runner.clj`. The previous fixture-file ownership boundary is resolved by the explicit handoff; foreign working-tree changes are excluded from the slice. Default remains on archive `2b49056a8` with its existing health findings.

## Other observations and cleanup

The initial scratch namespace with `(:require [clojure.test :refer [deftest is]])` failed automatic testing in `seon.sci.eval/interpret-source-namespaces!` because its explicit database argument was nil. Subsequent requests on those contexts failed error recording: `seon.error/prepare` rejected `:seon.instrument/arm :guard`, whose installed schema admits input/output. These are separate observed boundaries. The plain namespace reproduction above completed.

All four owned scratch agent branches were retired. A and B retired on their own exits; the two failed contexts required explicit release of their remaining owned branch connections before roster retirement. No foreign graph or branch was touched. Full `clj-kondo --lint src script test --cache false`: 0 errors / 1,534 warnings, 6,887 ms. Foreign edits in `seon.turn` tasks and `db_flow_test.clj` were not included in the candidate checks or modified by this lane.
