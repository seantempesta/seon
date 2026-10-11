# sol-reg-rest

The central-round regressions enter through the ordinary test runner on a committed candidate archive. Fix each producing owner or its stale caller, preserving the existing tagged proof and runner bounds; no alternate evaluation or test machinery is added.

## C6

At HEAD d5912971e, `bin/seon check --test seon.operator-move-test/a-move-states-branch-heads-before-its-boot-writes` recorded run `48eec6683251`: executed 1, pass 0, error 1, 3154 ms. It failed with `clojure.lang.ArityException`, three arguments to `seon.operator/launch-form`. The fourth argument introduced by 05139fa1f is the launcher pid; this generated-form inspection supplies nil (no child is launched). Both stale callers now use the current four-argument entrance. Complexity remains proportional to the generated form.

Full-tree lint: `clj-kondo --lint src script test --cache false`, 6770 ms, 0 errors, 1541 existing warnings. Default was running with faulted health; it was not stopped or changed. `bin/seon check --help` answers the already-filed unknown-option defect (`seon.operator/a-command-with-help-answers-an-error-map-instead-of-its-usage`).

Foreign ownership: `astra-cost-handoff` holds `src/seon/db.clj`, `src/seon/program.cljc`, `test/seon/cluster/squash_test.clj`; `sol-reg-config` holds `src/seon/config.clj`, `test/seon/config_test.clj`, `test/seon/reconcile_test.clj`. None is edited here.
