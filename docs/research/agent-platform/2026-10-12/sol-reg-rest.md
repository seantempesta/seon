# sol-reg-rest

The central-round regressions enter through the ordinary test runner on a committed candidate archive. Fix each producing owner or its stale caller, preserving the existing tagged proof and runner bounds; no alternate evaluation or test machinery is added.

## C6

At HEAD d5912971e, `bin/seon check --test seon.operator-move-test/a-move-states-branch-heads-before-its-boot-writes` recorded run `48eec6683251`: executed 1, pass 0, error 1, 3154 ms. It failed with `clojure.lang.ArityException`, three arguments to `seon.operator/launch-form`. The fourth argument introduced by 05139fa1f is the launcher pid; this generated-form inspection supplies nil (no child is launched). Both stale callers now use the current four-argument entrance. Complexity remains proportional to the generated form.

Full-tree lint: `clj-kondo --lint src script test --cache false`, 6770 ms, 0 errors, 1541 existing warnings. Default was running with faulted health; it was not stopped or changed. `bin/seon check --help` answers the already-filed unknown-option defect (`seon.operator/a-command-with-help-answers-an-error-map-instead-of-its-usage`).

Foreign ownership: `astra-cost-handoff` holds `src/seon/db.clj`, `src/seon/program.cljc`, `test/seon/cluster/squash_test.clj`; `sol-reg-config` holds `src/seon/config.clj`, `test/seon/config_test.clj`, `test/seon/reconcile_test.clj`. None is edited here.

## C8

Baseline run `ba46c5a966da` at d5912971e executed the eight remaining named Vars: 35 assertions passed, 11 failed, no errors, 81009 ms. C2's two tagged/companion Vars and the fresh-JVM config Var were green. C8's second repetition of each task had zero model calls and terminal `:deadline`; its first repetition was green. `seon.task/attempt-tx` identifies the task's next attempt by ended attempts and returns [] while that attempt already exists. Launching all repetitions together therefore gives the second agent no assignment; this is not grading arithmetic. `seon.competency/launch!` now completes and grades each planned attempt before assigning the next. The existing task owner, branch entrance and grading remain; no second task identity or scheduling mechanism is introduced. This trades overlapping model calls for the task owner's actual sequential attempt semantics, with work proportional to requested runs.

Candidate run `60d83fdb4077`: C8 launch proof, fixture-model grade and C9 Flow proof all green, 12 assertions, 11947 ms runner total; check 40113 ms (ready 9722 ms, tests 12020 ms). Flow's fixture now enters its detached case through `seon.db/call-with-root-bindings`, exactly the production Flow launcher premise; the separately bound outer-world case remains. Baseline C9 Flow's five failures were an inherited runner world, not a broken detached-world constructor. The unchanged tagged proof went red to green.
