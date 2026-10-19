# with-redefs / alter-var-root / intern inventory in test/**

Date: 2026-10-13. Research only; no test or source file was changed. Scope: `test/**` (416 Clojure files scanned, including helpers such as `boot_drill_child.clj`, `reload_measure.clj`, `hook_measure.clj`). Method: a Clojure reader in Python (`scratchpad/inv.py`) reads every top-level form, tracks the enclosing `deftest` (or top-level `def`), resolves aliases and `:refer`s from each `ns` form, and resolves each target against the `def`s in `src/` and `script/`. Grouping and classification are hand-written per Var (`scratchpad/gen.py`), with a rule fallback for Vars not hand-classified.

## Headline

- grep hits for `with-redefs`, `with-redefs-fn`, `alter-var-root`, `intern` (plus `(intern `): **208 lines**. 24 of them are comments, docstrings or strings (for example `help_test.clj:250`, `test_census_test.clj:8`, `repl_parity_test.clj:976`, `repl_differential_p1_test.clj`, `turn_test.clj:79/208`), so they are not forms.
- Real forms: **185** top-level-reachable forms: with-redefs 130, with-redefs-fn 16, alter-var-root 17, intern 22. They hold **243** redefinition targets (a with-redefs map binds several Vars per form).
- Targets that redefine a Var: **221** in **117** distinct Vars across **70** files. Of those, **78** are external effects (model call, process, prepl socket, filesystem, classpath, clj-kondo, Datahike); the rest are internal Seon logic, spies, tripwires and constant stubs.
- intern: **22** targets. Most create fresh test-owned Vars for instrumentation and program fixtures, not redefinitions of an existing Var. They are listed separately below and are not counted in the table.
- The law is `test/seon/test_census_test.clj` `no-test-redefines-a-var`. It asserts that the census of `test` and `script` finds **no** top-level form that uses one of these symbols. With these 185 forms in the tree, that assertion should fail. I did not run the test (no JVM in this research), so treat "red on HEAD" as the expected result to verify, not a measured one.

## Summary by redefined Var (sorted by count)

Kind: `external` = substitutes a real effect (model, process, socket, filesystem, classpath, clj-kondo, Datahike dependency); `internal` = substitutes Seon logic. Replacement is the data-injection form: pass a value, a declared input or a seam the owner already exposes, or assert on data the system already records.

| Var | Count | Files | Kind | Likely data-injection replacement |
|---|---:|---:|---|---|
| `ai/complete` | 48 | 8 | external: model call | Pass the completer as a declared input down the turn/agent call chain (turn.clj calls `ai/complete` directly at the model step); default stays `seon.ai/complete`. Tests then pass a value, no Var swap. |
| `db/transact!` | 8 | 6 | internal: writer seam (spy or failure injection) | Spies: read the transaction report or the stored facts. Failures: produce the real refusal with a fact the checked writer rejects; if the writer must be told to fail, pass the transactor as an input to the caller. |
| `<local: candidate>` | 7 | 4 | test-owned fresh Var (intern d in the test) with instrumentation undone via `mi/-f->original` | Remove the redefinition: the test owns the fresh Var; undo instrumentation through the owner (`seon.instrument` remove) or assert on the instrumented call. |
| `sci.eval/evaluate` | 7 | 2 | internal: evaluator (spy or stub) | turn.clj reaches the evaluator through `#(sci.eval/evaluate request)` (turn.clj ~4179): add an `:seon.turn/evaluate` input defaulting to `sci.eval/evaluate`, and pass the test double as a value. |
| `operator/prepl-value!` | 5 | 4 | external: prepl socket to a JVM | Pass the client (a function of endpoint and form) as an input to the hook/operator entry; the test passes a recording fn. |
| `db/q` | 4 | 4 | internal: query seam (stubbed refusal) | Produce the refusal from real data (a fact the query cannot read) or pass the query result as a declared input. |
| `turn/next-agent-work` | 4 | 3 | internal: scheduling read (failure injection or wrapper) | Pass the work source as a declared input to the turn loop; injected faults become input values. |
| `blob/with-publication!` | 3 | 1 | internal: publication wrapper | Pass the publication as an input value; assert on the commit roots returned. |
| `compiled-runtime-victim` | 3 | 1 | test-local Var (intentional: law under test) | The eval_test rows test that SCI cannot redefine compiled Vars. Keep the subject but express it as data: evaluate forms and compare the returned values; no Var swap in the test host. |
| `context/model-context` | 3 | 1 | internal: model context (stub) | Pass the context as a declared input to the turn. |
| `eval/evaluate` | 3 | 1 | internal: evaluator (tripwire or spy) | Assert no evaluation fact was written (read the store) instead of throwing from the Var. |
| `operator/advertisement` | 3 | 2 | external: endpoint file or socket advertisement | Pass the endpoint map as an input to the hook entry instead of redefining the lookup. |
| `schema/projection-from-database` | 3 | 3 | internal: schema projection (spy) | Read the recorded derivation count or pass the projection as an input. |
| `store/release-branch!` | 3 | 2 | internal: store release (failure injection) | Pass the release fn as an input to the acquisition owner, or make the release refuse for a real reason. |
| `<helper: (hook 'load-config)>` | 2 | 2 | hook helper Var (config stub) | Pass the config as the hook entry input. |
| `<helper: (ns-resolve 'seon.web.jvm 'credential)>` | 2 | 1 | private credential read (constant stub) | Write the credential as a configuration fact on the test branch, not a Var stub. |
| `agent/submit-source!` | 2 | 1 | internal: source submission (tripwire) | Assert no submission fact was written. |
| `ai/agent-overlay` | 2 | 2 | internal: agent settings read | Write the overlay as a database fact on the test branch (running configuration is facts) or pass it as an input to the caller. |
| `blob/stage!` | 2 | 2 | internal: blob staging (tripwire) | Assert no blob fact was written (read the store) instead. |
| `config/effective` | 2 | 2 | internal: configuration read (constant stub) | Write the configuration as facts and call the real `config/effective`. |
| `db/projection-fallback` | 2 | 2 | internal: fallback (tripwire) | Assert on the result data (no fallback value in the reply) instead of a throwing stub. |
| `injected-evaluation` | 2 | 1 | test-local def (value rebound) | Pass the injected evaluation as an argument to turn; the test-local Var is a value, not a seam (turn_test.clj uses it at 1803, 1966). |
| `io/resource` | 2 | 2 | external: classpath resource read | Put the fixture on a real temporary classpath directory, or pass the resource reader as an input. |
| `kernel/invoke` | 2 | 2 | internal: SCI kernel invoke (spy) | Read the recorded calls from the render/function evidence; or pass the invoker as input. |
| `load-config` | 2 | 1 | UNRESOLVED: not defined in Clojure src/script/test (lives in bin/seon-hook) | Verify whether this with-redefs compiles; if the hook script is the subject, pass the config as the hook entry input. |
| `schema/build-projection` | 2 | 2 | internal: schema projection build (tripwire or spy) | Assert on the returned projection; for tripwires, assert the reply carries no rebuild evidence. |
| `seon.db/declaration-content-key` | 2 | 1 | internal: content key (spy) | Compare the returned digests; remove the counting wrapper. |
| `seon.turn/step` | 2 | 1 | internal: turn step (wrap or stub) | Pass the step fn as an input to the agent entry. |
| `*compiled-runtime-dynamic-victim*` | 1 | 1 | test-local Var (intentional: law under test) | As compiled-runtime-victim: move the assertion to data. |
| `<expr>` | 1 | 1 | expression target (unresolved statically) | Inspect by hand; name the Var or pass the seam. |
| `<helper: (private-var 'cache-root)>` | 1 | 1 | private Var redirected to a temp path (dev-cache private) | Pass the path as an input to the dev-cache owner (a parameter), instead of redirecting a private Var. |
| `<helper: (private-var 'lock-file)>` | 1 | 1 | private Var redirected to a temp path (dev-cache private) | Pass the path as an input to the dev-cache owner (a parameter), instead of redirecting a private Var. |
| `<helper: (private-var 'process-reference-root)>` | 1 | 1 | private Var redirected to a temp path (dev-cache private) | Pass the path as an input to the dev-cache owner (a parameter), instead of redirecting a private Var. |
| `<helper: (private-var 'result-file)>` | 1 | 1 | private Var redirected to a temp path (dev-cache private) | Pass the path as an input to the dev-cache owner (a parameter), instead of redirecting a private Var. |
| `<helper: (private-var 'run-build!)>` | 1 | 1 | private Var redirected to a temp path (dev-cache private) | Pass the path as an input to the dev-cache owner (a parameter), instead of redirecting a private Var. |
| `<helper: (private-var 'selection-file)>` | 1 | 1 | private Var redirected to a temp path (dev-cache private) | Pass the path as an input to the dev-cache owner (a parameter), instead of redirecting a private Var. |
| `<helper: (private-var 'staging-root)>` | 1 | 1 | private Var redirected to a temp path (dev-cache private) | Pass the path as an input to the dev-cache owner (a parameter), instead of redirecting a private Var. |
| `<local: boundary>` | 1 | 1 | test-local seam (bound from a fresh or ns-resolve Var) | Pass the seam as a declared input to the function under test. |
| `<local: probe>` | 1 | 1 | test-local seam (bound from a fresh or ns-resolve Var) | Pass the seam as a declared input to the function under test. |
| `<map variable: wrappers>` | 1 | 1 | map of Var redefinitions passed as a variable (indirect) | Inline the map and convert each entry by its Var. |
| `acquire-var` | 1 | 1 | internal: store acquisition (ns-resolve Var) | Boot drill child: pass the acquire fn into the child entry as an input, or run the real acquisition on a real lock. |
| `agent/db<->flow` | 1 | 1 | internal: db<->flow (stub) | Pass the flow handle as an input or drive the real listener. |
| `ai/settings` | 1 | 1 | internal: configuration read | Apply the settings through `config/apply!` as facts on the test branch; no redefinition. |
| `analyzer/analyze` | 1 | 1 | external: clj-kondo analysis wrapper | Pass the analysis as a value to the publication input; test on a real fixture file. |
| `async/close!` | 1 | 1 | library: core.async channel close (tripwire) | Assert the channel state on the real channel. |
| `await-var` | 1 | 1 | internal: feed wait (ns-resolve Var of private fn) | Drive the real tap and registration; pass the wait fn as input only if timing must be controlled. |
| `babashka.process/process` | 1 | 1 | external: subprocess | Pass a runner fn as an input to the caller, or run a real subprocess. |
| `blob/read-chunk` | 1 | 1 | external: filesystem read | Pass the chunk reader as an input to the blob reader, or write a real file. |
| `bridge/assert-storable-schema!` | 1 | 1 | internal: schema assertion (tripwire) | Assert on the real refusal value. |
| `clojure.core/require` | 1 | 1 | library: core require (observer) | Assert the reload evidence recorded by the real reload; do not replace require. |
| `clojure.core/requiring-resolve` | 1 | 1 | library: core resolver (tripwire) | Assert on the resolved value the real call returns. |
| `cluster/load-development-definitions!` | 1 | 1 | internal: reload (observer) | Assert on the reload evidence recorded by the real reload, or pass the loader as an input. |
| `cluster/publication-roots` | 1 | 1 | internal: publication roots (constant stub) | Pass the roots as the declared input to the publication step. |
| `cluster/refresh-source!` | 1 | 1 | internal: source refresh (observer) | Pass the refresh fn as input or assert on the recorded publication. |
| `cluster/serve!` | 1 | 1 | external: web/prepl server start (failure injection) | Pass the server starter as an input to boot, or make the port occupied for real. |
| `config/apply!` | 1 | 1 | internal: configuration apply (failure stub) | Make the apply refuse for a real reason (an invalid manifest value). |
| `config/reconcile-call` | 1 | 1 | internal: config reconcile (wrapper) | Pass the reconcile call as an input or assert on the reconciled facts. |
| `d/db` | 1 | 1 | external: Datahike database value | Pass the database value explicitly (the caller takes it as an input) instead of rebinding `d/db`. |
| `d/force-branch!` | 1 | 1 | external: Datahike branch force (failure stub) | Make the failure real: a branch in the wrong state, or pass the branch-forcing fn as an input. |
| `d/history` | 1 | 1 | external: Datahike history (spy) | Read history through `as-of`/`history` on a real store and count via read evidence. |
| `d/release` | 1 | 1 | external: Datahike connection release (failure stub) | Pass the release fn as an input to the store owner. |
| `d/transact!` | 1 | 1 | external: Datahike transact | Dependency seam; see d/transact!. |
| `db/arity-mismatches-with` | 1 | 1 | internal: validator (spy) | Read the returned arity mismatches; spy counting is not needed. |
| `db/datoms` | 1 | 1 | internal: index read (spy) | Read the facts through the public query; the count is evidence already recorded by read evidence. |
| `db/pull` | 1 | 1 | internal: pull seam (stubbed entity) | Assert on stored facts with a real pull; no stub needed. |
| `db/pull-many` | 1 | 1 | internal: pull seam (spy) | Read the stored facts; if the count matters, use the recorded read evidence the system already keeps. |
| `dbi/datoms` | 1 | 1 | external: Datahike index datoms | Pass a real database; the datoms are the dependency, not Seon logic. |
| `dbi/search` | 1 | 1 | external: Datahike index search | Pass a real database; the search is the dependency, not Seon logic. |
| `delete-var` | 1 | 1 | external: filesystem delete (ns-resolve Var) | Use a real temp root the child owns; the destroy path is real filesystem work. |
| `doubled` | 1 | 1 | test-local def (value rebound) | Pass the function as an argument to the instrumented caller. |
| `entrance-lane` | 1 | 1 | UNRESOLVED: not defined anywhere in Clojure | Symbol is not a Var in any namespace; pass the lane as an input to the hook entry or delete the row. |
| `flow/submit!` | 1 | 1 | internal: Flow submit (thread start stub) | Pass the submit fn as an input to the agent entry, or assert on the submitted flow value. |
| `fs/delete-recursively!` | 1 | 1 | external: filesystem delete | Use a real temp directory the test owns and assert on what was removed; no failure stub. |
| `functions/add-contract-facts` | 1 | 1 | internal: contract facts (observer) | Assert on the facts the real call writes. |
| `gc-guard/acquire-reachability-permit!` | 1 | 1 | internal store concurrency permit (failure stub) | Hold a real permit on a second thread or branch, as production does, instead of replacing the acquire fn. |
| `gc-guard/acquire-sweep-permit!` | 1 | 1 | internal store concurrency permit (failure stub) | Hold a real permit on a second thread, as production does. |
| `hiccup/->string` | 1 | 1 | library: hiccup serializer (tripwire) | Assert the data-level render result rather than a serializer stub. |
| `instances-var` | 1 | 1 | internal: running instance registry (Var holding an atom) | Pass the registry as an input (the source instrumentation test) rather than rebinding the Var. |
| `instrument/apply!` | 1 | 1 | internal: instrumentation apply (observer) | Assert on the instrumentation evidence (contract facts), or pass the applier as an input. |
| `instrument/remove!` | 1 | 1 | internal: instrumentation remove (observer) | Assert on evidence; pass the remover as an input. |
| `kondo/run!` | 1 | 1 | external: clj-kondo run | Pass the analysis result as input; or run real clj-kondo on a fixture file. |
| `m/function-schema` | 1 | 1 | library: malli compile (tripwire) | Assert the reply carries no recompilation evidence (read the instrumentation counter). |
| `m/validator` | 1 | 1 | library: malli compile (tripwire) | Assert via the instrumentation evidence; drop the tripwire. |
| `mr/lazy-registry` | 1 | 1 | stub or wrapper of internal logic | Pass the function as a declared input to the caller (the owner seam), or assert on data. |
| `operator/live-root-value!` | 1 | 1 | external: live root evaluation | Pass the evaluator as an input to the hook entry. |
| `process/process` | 1 | 1 | external: subprocess | Pass a runner fn as an input to the caller, or run a real `bb` subprocess fixture. |
| `program/shapes` | 1 | 1 | internal: program shapes (tripwire) | Assert on digests from the real call; the tripwire should be a fact absence check. |
| `pull-api/compile-pull-plan` | 1 | 1 | external: Datahike pull plan (spy) | Read the compiled plan from the real call. |
| `pull-api/pull-many-plan-with-evidence` | 1 | 1 | external: Datahike pull plan (spy) | Read the recorded plan/evidence from the real call. |
| `read-pass-var` | 1 | 1 | external: window-pass file read (ns-resolve Var) | Pass the reader as an input to the window pass, or use a real temp file. |
| `reconcile/plan` | 1 | 1 | internal: reconcile plan (wrapper) | Assert on the plan the real function returns. |
| `registry/retire-branch!` | 1 | 1 | internal: registry retire (failure injection) | Make the failure real (a retire on a branch that cannot be retired) or pass the retire fn as input. |
| `render.walk/neighborhood` | 1 | 1 | internal: render walk (spy) | Assert on the returned neighborhood. |
| `render.walk/root-pull-plan` | 1 | 1 | internal: render walk (spy) | Assert on the returned plan. |
| `render.walk/root-selector` | 1 | 1 | internal: render walk (wrapper) | Assert on the returned selector. |
| `render/request-profile` | 1 | 1 | internal: render profile (constant stub) | Pass the profile as input. |
| `resource-paths-var` | 1 | 1 | internal: schema resource listing (ns-resolve Var of private fn) | Use a real directory on the classpath (test fixture resources) instead of reordering the listing. |
| `runner/reach-digests` | 1 | 1 | internal: reach digests (wrapper) | Pass the digests as input to selection. |
| `schema.edn/declaration-stamp` | 1 | 1 | internal: declaration stamp (constant stub) | Write the stamp as a fact or pass it as input. |
| `schema.edn/packaged-forms` | 1 | 1 | internal: packaged forms (constant stub) | Pass the forms as a declared input. |
| `schema.internal/assert-compilable-schema!` | 1 | 1 | internal: schema assertion (spy) | Assert on compiled results from the real call. |
| `schema/compilable-form` | 1 | 1 | internal: schema compile (spy) | Read the compiled result from the real call. |
| `schema/declaration-projection` | 1 | 1 | internal: declaration projection (spy) | Pass the projection as a declared input. |
| `schema/load-projection` | 1 | 1 | internal: projection load (wrapper) | Pass the projection value into the writer. |
| `schema/sha-256` | 1 | 1 | internal: hash (spy) | Compare the returned digests directly; remove the capture wrapper. |
| `sci/eval-form` | 1 | 1 | internal: SCI form eval (spy) | Assert on the evaluation result from the real SCI context. |
| `sci/install-row!` | 1 | 1 | internal: SCI row install (spy) | Assert on the installed rows read back from the branch. |
| `seon.db/resolve-database-value` | 1 | 1 | internal: value resolution (metadata wrapper) | Pass the database value explicitly. |
| `seon.db/write-error` | 1 | 1 | internal: error writer (spy) | Assert on the returned error map. |
| `seon.id/digest` | 1 | 1 | internal: digest (stub via -f->original) | Use the real digest; compare values. |
| `seon.schema/call-with-registration-delta` | 1 | 1 | internal: schema registration (wrapper) | Assert the returned delta. |
| `seon.turn/preview-sources` | 1 | 1 | internal: preview (spy) | Assert no source was submitted (read the store). |
| `source/capture-paths` | 1 | 1 | external: source file capture | Use a real fixture directory; pass the paths as input. |
| `store/open-branch!` | 1 | 1 | internal: store open (failure injection) | Create the real failure (a branch that does not exist) rather than throwing from the Var. |
| `turn/more-agent-work?` | 1 | 1 | internal: scheduling predicate (constant) | Pass the predicate as input to the turn loop. |
| `turn/turn` | 1 | 1 | internal: turn (failure injection) | Pass the turn fn as input. |
| `web/handler` | 1 | 1 | internal: web handler (spy) | Call the handler with a real request map; count via reply. |

## Subject check: redefined Vars that name nothing in the tree

A Var that resolves to no `def` in `src/`, `script/` or `test/` makes its test a candidate for deletion or repair (the subject is gone or never was Clojure).

- `load-config` (2 use, test/seon/dev/hook_test.clj): UNRESOLVED: not defined in Clojure src/script/test (lives in bin/seon-hook). `load-config` lives in `bin/seon-hook` (a babashka script, not a Clojure namespace), so `with-redefs` on it cannot resolve as a Var in the JVM. Check whether `dev/hook_test.clj` compiles and runs at all.
- `entrance-lane` (1 use, test/seon/dev/hook_test.clj): UNRESOLVED: not defined anywhere in Clojure. `load-config` lives in `bin/seon-hook` (a babashka script, not a Clojure namespace), so `with-redefs` on it cannot resolve as a Var in the JVM. Check whether `dev/hook_test.clj` compiles and runs at all.

Test files whose `ns` requires a `seon.*` namespace with no file in `src/`, `script/` or `test/` (a tested subject that looks deleted):

- `test/seon/cluster/boot_test.clj` requires `seon.operator.runtime`, which has no file.
- `test/seon/cluster/boot_timeline_test.clj` requires `seon.operator.runtime`, which has no file.
- `test/seon/cluster/mcp_test.clj` requires `seon.operator.runtime`, which has no file.
- `test/seon/cluster/publication_adoption_test.clj` requires `seon.operator.runtime`, which has no file.
- `test/seon/cluster/publication_host_test.clj` requires `seon.operator.runtime`, which has no file.
- `test/seon/cluster/rehearsal_test.clj` requires `seon.operator.runtime`, which has no file.
- `test/seon/competency.clj` requires `seon.operator.runtime`, which has no file.
- `test/seon/config_application_test.clj` requires `seon.operator.runtime`, which has no file.
- `test/seon/health_test.clj` requires `seon.operator.runtime`, which has no file.
- `test/seon/maintenance_test.clj` requires `seon.operator.runtime`, which has no file.
- `test/seon/mcp_entrance_test.clj` requires `seon.operator.runtime`, which has no file.
- `test/seon/pass_probe.clj` requires `seon.operator.runtime`, which has no file.
- `test/seon/problems_test.clj` requires `seon.operator.runtime`, which has no file.
- `test/seon/repl_differential_reader_test.clj` requires `seon.operator.runtime`, which has no file.
- `test/seon/test_support.clj` requires `seon.operator.runtime`, which has no file.
- `test/seon/transact_feedback_probe.clj` requires `seon.operator.runtime`, which has no file.
- `test/seon/turn/batch_test.clj` requires `seon.operator.runtime`, which has no file.
- `test/seon/turn_continue_test.clj` requires `seon.operator.runtime`, which has no file.

Summary: 18 require lines name a `seon.*` namespace with no file. The largest is `seon.operator.runtime`: `src/seon/operator/` is an empty directory, and the namespace was removed with the operator lifecycle replacement (commit 076827cc9). Every test that requires it cannot load, and their with-redefs targets on `seon.operator.runtime` cannot be resolved.

Dead subject found by the Var check: `instances-var` in `dev/source_instrumentation_test.clj` resolves `seon.cluster/running-instances` with `ns-resolve`. No `def` of `running-instances` exists in `src/` or `script/` (the atom now lives under the removed `seon.operator.runtime`), so the resolution returns nil and the test cannot run. Delete it or repoint it at the current registry.

Redefined Vars present in source but with no caller left in `src/` would also be deletion candidates. The `ns-resolve` and `private-var` rows resolve to live defs (`seon.cluster/acquire-root-store!`, `seon.render.web/await-feed-package!`, `seon.cluster/load-development-definitions!`, `schema.edn/schema-resource-paths`, `seon.fs.jvm/window-pass`), so they are live subjects, not deleted ones.

## Test-owned Vars (value rebound; the test owns the Var, so the redefinition is a seam, not a dependency)

- `injected-evaluation` (`cluster/turn_test.clj`, 2 uses; a `def` in the same file). Pass the injected evaluation as an argument to `seon.turn/turn`.
- `compiled-runtime-victim`, `*compiled-runtime-dynamic-victim*` (`sci/eval_test.clj`, 4 uses). These are the law under test (evaluated code must not redefine compiled Vars). Re-express the assertion as data (evaluate forms, compare returned values) rather than rebinding the victim in the host.
- `doubled` (`instrument_test.clj`, 1 use). Pass the function as an argument to the instrumented caller.

## Fixture interns (intern of fresh Vars; not redefinitions unless the name already exists)

| File:line | Enclosing test | Intern target |
|---|---|---|
| `test/seon/cluster/program_change_test.clj:42` | `member-run` | `owned/probe (with-meta)` |
| `test/seon/cluster/reload_measure.clj:141` | `-main` | `user/<expr: 'reload-caller-var>` |
| `test/seon/cluster/reload_measure.clj:142` | `-main` | `user/<expr: 'reload-caller-root>` |
| `test/seon/cluster/reload_measure.clj:144` | `-main` | `user/<expr: 'reload-phases>` |
| `test/seon/instrument_test.clj:212` | `host-boundaries-enforce-per-arity-declared-schemas-in-both-modes` | `seon.instrument-test/candidate-name` |
| `test/seon/instrument_test.clj:892` | `a-later-database-value-of-the-same-program-keeps-every-wrapper` | `seon.instrument-test/var-name` |
| `test/seon/instrument_test.clj:930` | `authored-contract-changes-rearm-without-reloading` | `seon.instrument-test/var-name` |
| `test/seon/instrument_test.clj:965` | `referenced-contract-changes-rearm-only-dependent-wrappers` | `seon.instrument-test/candidate-name` |
| `test/seon/instrument_test.clj:966` | `referenced-contract-changes-rearm-only-dependent-wrappers` | `seon.instrument-test/unrelated-name` |
| `test/seon/instrument_test.clj:1031` | `one-wrapper-per-adoption-keyed-by-content-never-by-call` | `namespace-name/<expr: 'content-keyed>` |
| `test/seon/instrument_test.clj:1131` | `cold-arming-keeps-acquired-predicates-without-an-ambient-binding` | `namespace-name/cold-contract (with-meta)` |
| `test/seon/instrument_test.clj:1353` | `applying-uses-the-acquired-projection-without-publishing-it` | `seon.instrument-test/var-name` |
| `test/seon/instrument_test.clj:1795` | `adoption-disarms-a-retired-contract` | `seon.instrument-test/name` |
| `test/seon/instrument_test.clj:1865` | `a-handed-projection-without-the-contracts-declarations-uses-the-loaded-ones` | `seon.instrument-test/var-name` |
| `test/seon/profile_test.clj:43` | `arm-probe!` | `seon.profile-test/var-name` |
| `test/seon/test/duration_test.clj:12` | `duration-bound-requires-both-the-allowance-and-its-reason` | `namespace-object/<expr: 'probe>` |
| `test/seon/test/one_request_test.clj:162` | `a-body-outliving-its-bound-is-released-only-after-it-exits` | `owned/gated-probe (with-meta)` |
| `test/seon/test/runner_test.clj:236` | `nested-runs-own-their-counters-and-evidence` | `namespace-object/test-name` |
| `test/seon/test/seed_preparation_test.clj:19` | `run-seeded` | `owned/probe (with-meta)` |
| `test/seon/test_runner_test.clj:293` | `throwaway-test-var` | `namespace-object/<expr: 'probe>` |
| `test/seon/test_runner_test.clj:355` | `the-programs-own-arming-is-no-tests-drift` | `namespace-object/own (with-meta)` |
| `test/seon/test_runner_test.clj:356` | `the-programs-own-arming-is-no-tests-drift` | `namespace-object/foreign (with-meta)` |

Interns are instrumentation fixtures (fresh Vars carrying `:malli/schema` or `:test` metadata). They are not seams to replace; the replacement is to keep them and remove only the `alter-var-root` or `with-redefs` that touches a Var from `src`.

## Per-occurrence list

| File:line | Test var | Form | Redefined Var | Substitutes | Value (truncated) |
|---|---|---|---|---|---|
| `test/seon/blob_publication_test.clj:101` | `publication-and-collection-are-exclusive-in-both-orderings` | with-redefs | `gc-guard/acquire-reachability-permit!` | internal store concurrency permit (failure stub) | `(fn ([store-id mode] (when (= :blob mode) (.countDown publication-requested)) (original-ac` |
| `test/seon/blob_publication_test.clj:143` | `publication-and-collection-are-exclusive-in-both-orderings` | with-redefs | `gc-guard/acquire-sweep-permit!` | internal store concurrency permit (failure stub) | `(fn [store-id opts] (.countDown sweep-requested) (original-acquire store-id opts))` |
| `test/seon/cluster/acquisition_test.clj:135` | `acquisition-owns-only-its-isolated-resources` | with-redefs | `sci/install-row!` | internal: SCI row install (spy) | `(fn [request] (swap! installs inc) (original request))` |
| `test/seon/cluster/acquisition_test.clj:151` | `acquisition-owns-only-its-isolated-resources` | with-redefs | `store/open-branch!` | internal: store open (failure injection) | `(fn [_ _] (throw (ex-info "forced open failure" {:seon.proof/forced true})))` |
| `test/seon/cluster/agent_test.clj:749` | `stop-flow-drops-the-route-before-closing-it` | with-redefs | `async/close!` | library: core.async channel close (tripwire) | `(fn [candidate] (when (identical? candidate channel) (reset! observed {:routed? (contains?` |
| `test/seon/cluster/agent_test.clj:813` | `stop-flow-interrupts-an-in-flight-turn-and-records-it-interrupted` | with-redefs | `context/model-context` | internal: model context (stub) | `(fn [_database _request _agent-id] (.countDown prelude-entered) (try (.await never-release` |
| `test/seon/cluster/agent_test.clj:890` | `stop-flow-has-a-declared-loud-turn-completion-backstop` | with-redefs | `context/model-context` | internal: model context (stub) | `(fn [_database _request _agent-id] (.countDown provider-entered) (.acquireUninterruptibly ` |
| `test/seon/cluster/agent_test.clj:993` | `release-joins-every-owned-graph-before-it-closes-the-branch` | with-redefs | `context/model-context` | internal: model context (stub) | `(fn [_database _request _agent-id] (.countDown entered) (.acquireUninterruptibly release-p` |
| `test/seon/cluster/agent_test.clj:1192` | `a-failed-turn-transform-releases-its-permit-and-retires-its-backstop` | with-redefs | `turn/next-agent-work` | internal: scheduling read (failure injection or wrapper) | `(fn [& _] {:seon.turn.work/situation :resume :seon.agent/id agent-id :seon.turn/id "failed` |
| `test/seon/cluster/agent_test.clj:1192` | `a-failed-turn-transform-releases-its-permit-and-retires-its-backstop` | with-redefs | `turn/turn` | internal: turn (failure injection) | `(fn [& _] (throw (ex-info "turn transform escaped" {:seon.test/turn-escaped true})))` |
| `test/seon/cluster/agent_test.clj:1586` | `hot-reload-var-test` | alter-var-root | `seon.turn/step` | internal: turn step (wrap or stub) | `(constantly (wrap original))` |
| `test/seon/cluster/agent_test.clj:1604` | `hot-reload-var-test` | alter-var-root | `seon.turn/step` | internal: turn step (wrap or stub) | `(constantly original)` |
| `test/seon/cluster/armed_test.clj:216` | `booting-spends-no-model-call` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] (swap! calls inc) (throw (ex-info "boot made a model call" {})))` |
| `test/seon/cluster/armed_test.clj:236` | `booting-spends-no-model-call` | with-redefs | `turn/next-agent-work` | internal: scheduling read (failure injection or wrapper) | `(fn [& arguments] (let [result (apply next-agent-work arguments)] (.countDown derived) res` |
| `test/seon/cluster/armed_test.clj:266` | `an-escaped-throwable-becomes-a-fact-and-a-message` | with-redefs | `turn/next-agent-work` | internal: scheduling read (failure injection or wrapper) | `(fn [& _] (throw (ex-info "injected core fault" {})))` |
| `test/seon/cluster/armed_test.clj:351` | `the-first-cluster-proc-fault-at-resume-becomes-a-fact` | with-redefs | `agent/db<->flow` | internal: db<->flow (stub) | `(fn ([] (db<->flow)) ([args] (db<->flow args)) ([state transition] (if (and (= ::flow/resu` |
| `test/seon/cluster/armed_test.clj:351` | `the-first-cluster-proc-fault-at-resume-becomes-a-fact` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _request] {:seon.ai/text "(seon.run/complete \"fault observed\")"})` |
| `test/seon/cluster/boot_drill_child.clj:32` | `-main` | with-redefs-fn | `acquire-var` | internal: store acquisition (ns-resolve Var) | `(fn [& arguments] (let [opened (apply acquire arguments)] (when (and (= "start" mode) (nil` |
| `test/seon/cluster/boot_drill_child.clj:32` | `-main` | with-redefs-fn | `delete-var` | external: filesystem delete (ns-resolve Var) | `(fn [& arguments] (when (and (= "destroy" mode) (= (str root "/data/store") (first argumen` |
| `test/seon/cluster/boot_test.clj:237` | `stop-instance` | with-redefs | `store/release-branch!` | internal: store release (failure injection) | `(fn [connection] (if (identical? connection (:seon.boot/cluster-connection replacement)) (` |
| `test/seon/cluster/boot_test.clj:388` | `start-during-reset` | with-redefs | `fs/delete-recursively!` | external: filesystem delete | `(fn [& args] (when (and (= (str root "/data/store") (first args)) (not (realized? deleting` |
| `test/seon/cluster/boot_test.clj:388` | `start-during-reset` | with-redefs | `cluster/serve!` | external: web/prepl server start (failure injection) | `(fn [instance dials] (when (= name (get-in instance [:seon.boot/config :seon.boot/cluster-` |
| `test/seon/cluster/evaluate_sources_test.clj:87` | `ordered-evaluation-retains-one-explicit-basis-without-publication` | with-redefs | `blob/stage!` | internal: blob staging (tripwire) | `(fn [& _] (throw (ex-info "preview staged a blob" {})))` |
| `test/seon/cluster/evaluate_sources_test.clj:87` | `ordered-evaluation-retains-one-explicit-basis-without-publication` | with-redefs | `db/transact!` | internal: writer seam (spy or failure injection) | `(fn [& args] (swap! writes inc) (apply original-transact args))` |
| `test/seon/cluster/evaluate_sources_test.clj:87` | `ordered-evaluation-retains-one-explicit-basis-without-publication` | with-redefs | `sci.eval/evaluate` | internal: evaluator (spy or stub) | `(fn [request] (is (= 1700 (:seon.sci.eval/time-limit-ms request))) (let [result (original-` |
| `test/seon/cluster/evaluate_sources_test.clj:156` | `ordered-evaluation-retains-one-explicit-basis-without-publication` | with-redefs | `sci.eval/evaluate` | internal: evaluator (spy or stub) | `(fn [& _] (throw (ex-info "saving re-executed source" {})))` |
| `test/seon/cluster/evaluate_sources_test.clj:254` | `one-turn-derives-the-render-profile-exactly-once` | with-redefs | `render/request-profile` | internal: render profile (constant stub) | `(fn [request] (when-not (contains? request :seon.render/profile) (swap! derivations inc)) ` |
| `test/seon/cluster/program_restart_test.clj:170` | `an-agent-definition-survives-restart-and-another-agent-calls-it` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text (if (= "restart-a" (active-agent-id @connection)) (str ` |
| `test/seon/cluster/program_restart_test.clj:340` | `an-agent-definition-survives-restart-and-another-agent-calls-it` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text (if (= "restart-b" (active-agent-id @connection)) (str ` |
| `test/seon/cluster/publication_adoption_test.clj:70` | `freshly-forked-host-already-has-its-published-source` | with-redefs-fn | `db/transact!` | internal: writer seam (spy or failure injection) | `(observe :transact db/transact!)` |
| `test/seon/cluster/publication_adoption_test.clj:70` | `freshly-forked-host-already-has-its-published-source` | with-redefs-fn | `cluster/load-development-definitions!` | internal: reload (observer) | `(observe :reload @#'cluster/load-development-definitions!)` |
| `test/seon/cluster/publication_host_test.clj:49` | `live-init-keeps-the-host-process-and-launches-no-jvm` | with-redefs | `schema/projection-from-database` | internal: schema projection (spy) | `(fn [database & arguments] (when (= (registry/cluster-branch fork-name) (get-in database [` |
| `test/seon/cluster/publication_host_test.clj:70` | `live-init-keeps-the-host-process-and-launches-no-jvm` | with-redefs | `babashka.process/process` | external: subprocess | `(fn [& args#] (let [record# (apply start# args#)] (swap! records# conj {:seon.operator.sub` |
| `test/seon/cluster/publication_inputs_test.clj:29` | `changed-paths-hash-only-the-named-files-and-use-recorded-pins` | with-redefs | `schema/sha-256` | internal: hash (spy) | `(fn [parts] (swap! captured into (map #(String. ^bytes % "UTF-8") parts)) (sha-256 parts))` |
| `test/seon/cluster/publication_inputs_test.clj:68` | `publication-inputs-are-file-facts-but-not-analysis-inputs` | with-redefs | `analyzer/analyze` | external: clj-kondo analysis wrapper | `(fn [request] (swap! lint-files conj (:seon.fn.analyzer/paths request)) (analyze request))` |
| `test/seon/cluster/publication_inputs_test.clj:101` | `an-unchanged-explicit-path-is-captured-once-and-keeps-the-head` | with-redefs | `source/capture-paths` | external: source file capture | `(fn [directory paths] (swap! calls conj paths) (capture-paths directory paths))` |
| `test/seon/cluster/registry_test.clj:353` | `failed-forced-refork-keeps-the-prior-branch-reachable` | with-redefs | `d/force-branch!` | external: Datahike branch force (failure stub) | `(fn [& _] (throw failure))` |
| `test/seon/cluster/release_context_test.clj:44` | `a-failing-connection-release-still-unlinks-and-forgets-the-context` | with-redefs | `store/release-branch!` | internal: store release (failure injection) | `(fn [connection] (release connection) (throw (ex-info "forced connection release failure" ` |
| `test/seon/cluster/release_context_test.clj:61` | `every-release-failure-surfaces-with-the-later-ones-suppressed` | with-redefs | `store/release-branch!` | internal: store release (failure injection) | `(fn [connection] (release connection) (throw (ex-info "forced connection release failure" ` |
| `test/seon/cluster/release_context_test.clj:61` | `every-release-failure-surfaces-with-the-later-ones-suppressed` | with-redefs | `registry/retire-branch!` | internal: registry retire (failure injection) | `(fn [_] (throw (ex-info "forced unlink failure" {:seon.proof/forced :unlink})))` |
| `test/seon/cluster/reload_measure.clj:40` | `measured-request` | with-redefs-fn | `<map variable: wrappers>` | map of Var redefinitions passed as a variable (indirect) | `` |
| `test/seon/cluster/reload_measure.clj:103` | `-main` | with-redefs-fn | `<helper: (hook 'load-config)>` | hook helper Var (config stub) | `(constantly config)` |
| `test/seon/cluster/reload_measure.clj:103` | `-main` | with-redefs-fn | `operator/prepl-value!` | external: prepl socket to a JVM | `(fn [& args] (swap! calls conj (second args)) (apply original-client (assoc (vec args) 1 (` |
| `test/seon/cluster/store_test.clj:768` | `a-failed-release-never-drops-the-fence` | with-redefs | `d/release` | external: Datahike connection release (failure stub) | `(fn [& _] (throw (ex-info "injected release fault" {::injected true})))` |
| `test/seon/cluster/subprocess_test.clj:123` | `foreign-process-deadline-covers-output-capture` | with-redefs | `process/process` | external: subprocess | `(fn [& _] (assoc process-record :out (future @release-capture)))` |
| `test/seon/cluster/turn_test.clj:296` | `with-cluster` | with-redefs | `sci.eval/evaluate` | internal: evaluator (spy or stub) | `(fn [request] (let [injected (evaluator request) evaluated (evaluate (-> request (dissoc :` |
| `test/seon/cluster/turn_test.clj:453` | `a-whole-turn-runs-a-REAL-sci-evaluation-end-to-end` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text (str "(def widgets (map inc (range 3)))\n" "(do (seon.d` |
| `test/seon/cluster/turn_test.clj:509` | `an-ordinary-turn-advances-no-program-attribute-revision` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text (str "(seon.db/q '[:find [?id ...] :where [_ :seon.agen` |
| `test/seon/cluster/turn_test.clj:531` | `agent-code-with-defn-and-println-folds-green-without-in-ns` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text (str "(defn widget-count {:malli/schema [:=> [:cat :int` |
| `test/seon/cluster/turn_test.clj:558` | `mixed-plan-publishes-only-the-contracted-function` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text (str "(defn ^{:malli/schema [:=> [:cat [:map " "[:my.ag` |
| `test/seon/cluster/turn_test.clj:601` | `ns-unmap-retracts-the-owned-function-after-the-terminal-commit` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} obs` |
| `test/seon/cluster/turn_test.clj:613` | `ns-unmap-retracts-the-owned-function-after-the-terminal-commit` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text "(ns-unmap 'my.agents.agent-a 'obsolete)\n(seon.run/com` |
| `test/seon/cluster/turn_test.clj:631` | `reply-reading-follows-evaluated-alias-and-dynamic-require-state` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text (str "(alias 'str 'clojure.string)\n" "{:x ::str/after-` |
| `test/seon/cluster/turn_test.clj:655` | `import-only-ns-unmap-installs-exactly-after-its-context-commit` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text (str "(clojure.core/ns-unmap *ns* (symbol \"String\"))\` |
| `test/seon/cluster/turn_test.clj:655` | `import-only-ns-unmap-installs-exactly-after-its-context-commit` | with-redefs | `sci.eval/evaluate` | internal: evaluator (spy or stub) | `(fn [request] (reset! evaluated-ctx (:seon.sci.eval/ctx request)) (original-evaluate reque` |
| `test/seon/cluster/turn_test.clj:699` | `import-addition-is-ordinary-data-and-reacquires-exactly` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text "(import java.lang.String)"})` |
| `test/seon/cluster/turn_test.clj:759` | `refused-terminal-program-transactions-settle-and-do-not-refire` | with-redefs | `ai/complete` | external: model call | `(fn [_projection request] (swap! calls conj request) {:seon.ai/text (if (= 1 (count @calls` |
| `test/seon/cluster/turn_test.clj:759` | `refused-terminal-program-transactions-settle-and-do-not-refire` | with-redefs | `db/transact!` | internal: writer seam (spy or failure injection) | `(fn [target transaction] (let [tx-data (if (map? transaction) (:tx-data transaction) trans` |
| `test/seon/cluster/turn_test.clj:871` | `evaluation-follows-the-readers-parse-time-namespace` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text (str "(in-ns 'my.gen.alpha)\n" "(defn ^{:malli/schema [` |
| `test/seon/cluster/turn_test.clj:898` | `contracted-redefinition-exactly-replaces-the-entity` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text (str "(defn ^{:malli/schema [:=> [:cat :int] :int] " ":` |
| `test/seon/cluster/turn_test.clj:957` | `a-refused-contract-commits-a-receipt-and-no-entity` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text "(defn ^{:malli/schema [:=> [:cat :missing/schema] :int` |
| `test/seon/cluster/turn_test.clj:982` | `runtime-schema-registration-commits-the-evaluated-form-and-attribute` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text (str "(require '[seon.schema :as schema])\n" "(schema/r` |
| `test/seon/cluster/turn_test.clj:1056` | `runtime-schema-key-changes-pass-the-one-usage-guarded-decision` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text (str "(require '[seon.schema :as schema])\n" "(schema/r` |
| `test/seon/cluster/turn_test.clj:1089` | `runtime-schema-unregister-removes-one-unused-global-schema` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text (str "(require '[seon.schema :as schema])\n" "(schema/r` |
| `test/seon/cluster/turn_test.clj:1118` | `runtime-tests-install-run-redefine-and-delete-exactly` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text (str "(require '[clojure.test :refer [deftest]])\n" "(d` |
| `test/seon/cluster/turn_test.clj:1147` | `incompatible-clusters-alternate-runtime-schema-validation-without-bleed` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text (str "(require '[seon.schema :as schema])\n" "(schema/r` |
| `test/seon/cluster/turn_test.clj:1162` | `incompatible-clusters-alternate-runtime-schema-validation-without-bleed` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text (str "(require '[seon.schema :as schema])\n" "(schema/r` |
| `test/seon/cluster/turn_test.clj:1223` | `accepted-first-party-definition-reaches-an-existing-agent-next-turn` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] (let [[before _] (swap-vals! replies #(if (seq %) (subvec % 1) %))] {:` |
| `test/seon/cluster/turn_test.clj:1294` | `another-agent-sees-a-flat-contract-violation-after-live-install` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] (let [[before _] (swap-vals! replies #(if (seq %) (subvec % 1) %))] {:` |
| `test/seon/cluster/turn_test.clj:1361` | `a-refused-definition-stays-in-its-agents-defs` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text (let [reply (first @replies)] (swap! replies subvec 1) ` |
| `test/seon/cluster/turn_test.clj:1361` | `a-refused-definition-stays-in-its-agents-defs` | with-redefs | `db/transact!` | internal: writer seam (spy or failure injection) | `(fn [target transaction] (or (injected transaction) (transact! target transaction)))` |
| `test/seon/cluster/turn_test.clj:1485` | `a-lost-model-call-leaves-a-durable-readable-reason` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] ;; the identity `seon.ai/complete` declares for this refusal (vary-met` |
| `test/seon/cluster/turn_test.clj:1525` | `a-lost-model-call-leaves-a-durable-readable-reason` | with-redefs | `ai/complete` | external: model call | `(fn [_projection request] (swap! prompts conj (:seon.ai/prompt request)) {:seon.ai/text "(` |
| `test/seon/cluster/turn_test.clj:1543` | `a-real-evaluation-that-runs-away-is-stopped-and-recorded` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text "(loop [] (recur))"})` |
| `test/seon/cluster/turn_test.clj:1647` | `an-unreadable-reply-is-a-settled-form-with-paid-attempt-evidence` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text reply-text :seon.ai/usage usage :seon.ai/finish-reason ` |
| `test/seon/cluster/turn_test.clj:1717` | `a-prose-only-provider-reply-settles-without-parking-the-turn-proc` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _request] {:seon.ai/text reply-text :seon.ai/finish-reason "stop"})` |
| `test/seon/cluster/turn_test.clj:1754` | `a-combined-evaluation-projects-every-terminal-receipt-datom` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text source})` |
| `test/seon/cluster/turn_test.clj:1801` | `a-waiting-disposition-frees-the-agent-and-keeps-its-note` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text "(seon.run/wait \"need input\")"})` |
| `test/seon/cluster/turn_test.clj:1803` | `a-waiting-disposition-frees-the-agent-and-keeps-its-note` | with-redefs | `injected-evaluation` | test-local def (value rebound) | `{:seon.eval/shown (pr-str (seon.run/wait "need input")) :seon.sci.admit/value (seon.run/wa` |
| `test/seon/cluster/turn_test.clj:1918` | `successful-call-persists-the-providers-open-usage-document` | with-redefs | `ai/complete` | external: model call | `(recording-completer requests [{:seon.ai/text "(seon.run/complete \"one\")" :seon.ai/reaso` |
| `test/seon/cluster/turn_test.clj:1961` | `a-partial-stream-truncation-is-a-durable-nonfailure-attempt-fact` | with-redefs | `ai/complete` | external: model call | `(recording-completer requests [{:seon.ai/text "(seon.run/complete \"partial\")" :seon.ai/t` |
| `test/seon/cluster/turn_test.clj:1966` | `a-partial-stream-truncation-is-a-durable-nonfailure-attempt-fact` | with-redefs | `injected-evaluation` | test-local def (value rebound) | `{:seon.cluster.eval/result-edn (pr-str (seon.run/complete "partial")) :seon.sci.admit/valu` |
| `test/seon/cluster/turn_test.clj:2035` | `reasoning-starvation-persists-usage-finish-and-the-named-error` | with-redefs | `ai/complete` | external: model call | `(recording-completer requests [failure])` |
| `test/seon/cluster/turn_test.clj:2059` | `a-provider-failure-is-one-call-and-an-error-value` | with-redefs | `ai/complete` | external: model call | `(recording-completer requests [unpaid])` |
| `test/seon/cluster/turn_test.clj:2085` | `a-turn-delivers-what-a-form-asks-to-send-and-still-finishes` | with-redefs | `ai/complete` | external: model call | `(recording-completer requests [{:seon.ai/text (str "(do (my.message/send {:my.message/to \` |
| `test/seon/cluster/turn_test.clj:2134` | `a-refused-delivery-becomes-a-durable-error-fact` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text (str source "\n(seon.run/complete \"tried\")")})` |
| `test/seon/cluster/turn_test.clj:2134` | `a-refused-delivery-becomes-a-durable-error-fact` | with-redefs | `sci.eval/evaluate` | internal: evaluator (spy or stub) | `(fn [request] (let [result (evaluate request)] (when (= source (:seon.cluster.eval/source ` |
| `test/seon/cluster/turn_test.clj:2212` | `turn-intent-is-the-complete-crash-falsifier` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text source})` |
| `test/seon/cluster/turn_test.clj:2212` | `turn-intent-is-the-complete-crash-falsifier` | with-redefs | `sci.eval/evaluate` | internal: evaluator (spy or stub) | `(fn [request] (swap! evaluations inc) (evaluate request))` |
| `test/seon/cluster/turn_test.clj:2212` | `turn-intent-is-the-complete-crash-falsifier` | with-redefs | `db/transact!` | internal: writer seam (spy or failure injection) | `(fn [target transaction] (let [outcome (transact! target transaction) intent? (some #(and ` |
| `test/seon/cluster/turn_test.clj:2253` | `turn-intent-is-the-complete-crash-falsifier` | with-redefs | `sci.eval/evaluate` | internal: evaluator (spy or stub) | `(fn [request] (swap! evaluations inc) (evaluate request))` |
| `test/seon/cluster/turn_test.clj:2528` | `a-run-prompts-from-its-opening-database-value` | with-redefs | `ai/complete` | external: model call | `(recording-completer requests [{:seon.ai/text "(seon.run/complete \"A settled\")"} {:seon.` |
| `test/seon/cluster/turn_test.clj:2668` | `streaming-writes-zero-datoms-test` | with-redefs | `ai/complete` | external: model call | `(let [complete (streaming-completer requests chunks)] (fn [projection request] (let [reply` |
| `test/seon/cluster/turn_test.clj:2768` | `a-turn-hands-its-clusters-projection-to-every-database-call` | with-redefs | `schema/projection-from-database` | internal: schema projection (spy) | `(fn [& arguments] (swap! derivations inc) (apply original arguments))` |
| `test/seon/cluster/turn_test.clj:2768` | `a-turn-hands-its-clusters-projection-to-every-database-call` | with-redefs | `ai/complete` | external: model call | `(fn [_projection _] {:seon.ai/text "(+ 1 1)\n(seon.run/complete \"two\")"})` |
| `test/seon/cluster_test.clj:28` | `boot-recovery-refuses-an-unreadable-open-turn-query` | with-redefs | `db/q` | internal: query seam (stubbed refusal) | `(fn [& _] refusal)` |
| `test/seon/concurrency_independence_test.clj:598` | `n-agents-fold-independently-on-one-live-cluster` | with-redefs | `ai/complete` | external: model call | `(fn [_projection request] (swap! model-calls conj request) {:seon.ai/text "(seon.run/compl` |
| `test/seon/config_test.clj:243` | `config-writer-submits-only-the-fact-difference` | with-redefs-fn | `config/reconcile-call` | internal: config reconcile (wrapper) | `(fn [database desired request] (let [tx-data (original database desired request)] (swap! t` |
| `test/seon/config_test.clj:285` | `converged-apply-uses-carried-projection-and-remains-exact` | with-redefs | `schema/projection-from-database` | internal: schema projection (spy) | `(fn [& arguments] (when (identical? caller (Thread/currentThread)) (swap! rebuilds inc)) (` |
| `test/seon/config_test.clj:285` | `converged-apply-uses-carried-projection-and-remains-exact` | with-redefs | `db/projection-fallback` | internal: fallback (tripwire) | `(fn [operation] (when (identical? caller (Thread/currentThread)) (swap! fallbacks inc)) (f` |
| `test/seon/config_test.clj:285` | `converged-apply-uses-carried-projection-and-remains-exact` | with-redefs | `reconcile/plan` | internal: reconcile plan (wrapper) | `(fn [database request] (let [operations (plan database request)] (when (identical? caller ` |
| `test/seon/config_test.clj:349` | `packaged-defaults-use-the-same-shipped-document-authority` | with-redefs | `io/resource` | external: classpath resource read | `(fn [path & loader] (if (= config/default-manifest-path path) (-> packaged-file .toURI .to` |
| `test/seon/config_test.clj:685` | `sparse-file-reading-does-not-compile-a-second-time` | with-redefs | `schema/declaration-projection` | internal: declaration projection (spy) | `(fn [forms] (swap! acquisitions inc) (acquire forms))` |
| `test/seon/db_test.clj:532` | `transaction-wrappers-cannot-hide-a-classified-refusal` | with-redefs | `d/transact!` | external: Datahike transact | `(fn [& _] (let [result (datahike.tools/throwable-promise)] (result wrapped) result))` |
| `test/seon/db_test.clj:838` | `current-database-resolves-once-per-call` | with-redefs | `d/db` | external: Datahike database value | `(fn [bound-connection] (when (identical? this-thread (Thread/currentThread)) (swap! calls ` |
| `test/seon/db_test.clj:1454` | `pull-many-preserves-input-alignment-with-one-shared-plan` | with-redefs | `pull-api/pull-many-plan-with-evidence` | external: Datahike pull plan (spy) | `(fn [db-value pattern eids] (swap! calls conj [db-value pattern eids]) (pull-many-with-evi` |
| `test/seon/db_test.clj:3080` | `equal-revisions-prove-a-read-current-without-scanning-history` | with-redefs | `d/history` | external: Datahike history (spy) | `(fn [database] (swap! scans inc) (history database))` |
| `test/seon/dev/dependency_cache_test.clj:150` | `refresh-preserves-a-recorded-jvms-exact-cache-directory` | with-redefs-fn | `<helper: (private-var 'cache-root)>` | private Var redirected to a temp path (dev-cache private) | `(.getCanonicalPath cache-root)` |
| `test/seon/dev/dependency_cache_test.clj:150` | `refresh-preserves-a-recorded-jvms-exact-cache-directory` | with-redefs-fn | `<helper: (private-var 'staging-root)>` | private Var redirected to a temp path (dev-cache private) | `(.getCanonicalPath staging-root)` |
| `test/seon/dev/dependency_cache_test.clj:150` | `refresh-preserves-a-recorded-jvms-exact-cache-directory` | with-redefs-fn | `<helper: (private-var 'selection-file)>` | private Var redirected to a temp path (dev-cache private) | `(.getCanonicalPath selection-file)` |
| `test/seon/dev/dependency_cache_test.clj:150` | `refresh-preserves-a-recorded-jvms-exact-cache-directory` | with-redefs-fn | `<helper: (private-var 'process-reference-root)>` | private Var redirected to a temp path (dev-cache private) | `(.getCanonicalPath references-root)` |
| `test/seon/dev/dependency_cache_test.clj:150` | `refresh-preserves-a-recorded-jvms-exact-cache-directory` | with-redefs-fn | `<helper: (private-var 'lock-file)>` | private Var redirected to a temp path (dev-cache private) | `(.getCanonicalPath lock-file)` |
| `test/seon/dev/dependency_cache_test.clj:150` | `refresh-preserves-a-recorded-jvms-exact-cache-directory` | with-redefs-fn | `<helper: (private-var 'result-file)>` | private Var redirected to a temp path (dev-cache private) | `(.getCanonicalPath result-file)` |
| `test/seon/dev/dependency_cache_test.clj:150` | `refresh-preserves-a-recorded-jvms-exact-cache-directory` | with-redefs-fn | `<helper: (private-var 'run-build!)>` | private Var redirected to a temp path (dev-cache private) | `(fn [_basis staging] (compile-probes! source-root entities staging))` |
| `test/seon/dev/hook_measure.clj:59` | `-main` | with-redefs-fn | `<helper: (hook 'load-config)>` | hook helper Var (config stub) | `(constantly config)` |
| `test/seon/dev/hook_measure.clj:59` | `-main` | with-redefs-fn | `operator/prepl-value!` | external: prepl socket to a JVM | `(fn [& args] (swap! calls conj (second args)) (apply original-client args))` |
| `test/seon/dev/hook_test.clj:26` | `save-probe` | with-redefs | `load-config` | UNRESOLVED: not defined in Clojure src/script/test (lives in bin/seon-hook) | `(constantly config)` |
| `test/seon/dev/hook_test.clj:26` | `save-probe` | with-redefs | `operator/advertisement` | external: endpoint file or socket advertisement | `(record :advertisement)` |
| `test/seon/dev/hook_test.clj:26` | `save-probe` | with-redefs | `operator/prepl-value!` | external: prepl socket to a JVM | `(record :prepl)` |
| `test/seon/dev/hook_test.clj:26` | `save-probe` | with-redefs | `operator/live-root-value!` | external: live root evaluation | `(record :live-root)` |
| `test/seon/dev/hook_test.clj:99` | `entrance-probe` | with-redefs | `load-config` | UNRESOLVED: not defined in Clojure src/script/test (lives in bin/seon-hook) | `(constantly config)` |
| `test/seon/dev/hook_test.clj:99` | `entrance-probe` | with-redefs | `entrance-lane` | UNRESOLVED: not defined anywhere in Clojure | `(constantly "probe-lane")` |
| `test/seon/dev/hook_test.clj:99` | `entrance-probe` | with-redefs | `operator/advertisement` | external: endpoint file or socket advertisement | `(constantly endpoint)` |
| `test/seon/dev/hook_test.clj:99` | `entrance-probe` | with-redefs | `operator/prepl-value!` | external: prepl socket to a JVM | `(fn [_ form & _] (swap! requests conj (if (str/starts-with? form "(seon.mcp/call") (get-in` |
| `test/seon/dev/publication_test.clj:9` | `publication-forwards-output-and-returns-terminal-value` | with-redefs | `operator/advertisement` | external: endpoint file or socket advertisement | `(fn [_ _] endpoint)` |
| `test/seon/dev/publication_test.clj:9` | `publication-forwards-output-and-returns-terminal-value` | with-redefs | `operator/prepl-value!` | external: prepl socket to a JVM | `(fn [target form bound observe!] (is (= endpoint target)) (is (= "(+ 1 1)" form)) (is (pos` |
| `test/seon/dev/source_instrumentation_test.clj:41` | `source-publication-restores-contracts-after-early-reload-failure` | with-redefs-fn | `instances-var` | internal: running instance registry (Var holding an atom) | `instances` |
| `test/seon/dev/source_instrumentation_test.clj:41` | `source-publication-restores-contracts-after-early-reload-failure` | with-redefs-fn | `clojure.core/require` | library: core require (observer) | `(fn [& _] (swap! events conj :reload) (when (= :reload failure-point) (throw failure)))` |
| `test/seon/dev/source_instrumentation_test.clj:41` | `source-publication-restores-contracts-after-early-reload-failure` | with-redefs-fn | `cluster/refresh-source!` | internal: source refresh (observer) | `(fn [& _] (swap! events conj :publication) (when (= :publication failure-point) (throw fai` |
| `test/seon/dev/source_instrumentation_test.clj:41` | `source-publication-restores-contracts-after-early-reload-failure` | with-redefs-fn | `config/effective` | internal: configuration read (constant stub) | `(fn [_ _] (is (identical? projection (schema/handed-projection))) {})` |
| `test/seon/dev/source_instrumentation_test.clj:41` | `source-publication-restores-contracts-after-early-reload-failure` | with-redefs-fn | `instrument/remove!` | internal: instrumentation remove (observer) | `(fn [] (swap! events conj :unstrument))` |
| `test/seon/dev/source_instrumentation_test.clj:41` | `source-publication-restores-contracts-after-early-reload-failure` | with-redefs-fn | `instrument/apply!` | internal: instrumentation apply (observer) | `(fn [request] (is (identical? projection (schema/handed-projection))) (swap! events conj :` |
| `test/seon/effect_test.clj:242` | `background-settlement-carries-its-connection-across-a-thread-hop` | with-redefs | `flow/submit!` | internal: Flow submit (thread start stub) | `(fn [_ submission] (.start (Thread. ^Runnable (fn [] (try (let [before-work [effect/*reque` |
| `test/seon/error_test.clj:871` | `recurrence-counting-does-not-require-a-notification-threshold` | with-redefs | `config/apply!` | internal: configuration apply (failure stub) | `(fn [request] (when (= "error-test" (:seon.boot/cluster-name request)) (swap! applications` |
| `test/seon/error_write_timing_test.clj:11` | `program-call-changes-still-enter-final-arity-validation` | with-redefs-fn | `db/arity-mismatches-with` | internal: validator (spy) | `(fn [& args] (swap! calls inc) (apply original args))` |
| `test/seon/fn/publication_cache_test.clj:28` | `lint-direct-callers-after-committed-declaration-changes` | with-redefs | `kondo/run!` | external: clj-kondo run | `(fn [options] (is (true? (:cache options))) (swap! observed into (map #(.getName (io/file ` |
| `test/seon/fn/publication_test.clj:61` | `incremental-indexing-reconciles-only-selected-file-entities` | with-redefs-fn | `db/transact!` | internal: writer seam (spy or failure injection) | `(fn [conn request] (let [report (transact conn request)] (swap! reports conj report) repor` |
| `test/seon/fn/publication_test.clj:61` | `incremental-indexing-reconciles-only-selected-file-entities` | with-redefs-fn | `functions/add-contract-facts` | internal: contract facts (observer) | `(fn [entities progress projection] (swap! observed into entities) (add-contracts entities ` |
| `test/seon/fn_test.clj:1371` | `a-refused-reference-read-refuses-gate-set-derivation` | with-redefs | `db/datoms` | internal: index read (spy) | `(fn [& arguments] (if (and (identical? thread (Thread/currentThread)) (= [:aevt :seon.fn/i` |
| `test/seon/fn_test.clj:1839` | `refused-index-reads-never-become-publication-entities` | with-redefs | `db/q` | internal: query seam (stubbed refusal) | `(fn [& arguments] (if (identical? database (second arguments)) refusal (apply query argume` |
| `test/seon/fn_test.clj:1839` | `refused-index-reads-never-become-publication-entities` | with-redefs | `db/transact!` | internal: writer seam (spy or failure injection) | `(fn [& arguments] (swap! submitted conj (last arguments)) (apply transact! arguments))` |
| `test/seon/fn_test.clj:1853` | `refused-index-reads-never-become-publication-entities` | with-redefs | `db/pull-many` | internal: pull seam (spy) | `(fn [& arguments] (if (identical? database (first arguments)) refusal (apply pull-many arg` |
| `test/seon/fs/jvm_test.clj:200` | `an-attribute-change-after-the-pass-refuses-mixed-evidence` | with-redefs-fn | `read-pass-var` | external: window-pass file read (ns-resolve Var) | `(fn [& arguments] (let [pass (apply original arguments)] (write-octets! path (repeat 4097 ` |
| `test/seon/fs/jvm_test.clj:401` | `blob-backed-content-is-streamed-and-digest-verified` | with-redefs | `blob/read-chunk` | external: filesystem read | `read-chunk` |
| `test/seon/gen/loop_test.clj:300` | `completion-prose-does-not-erase-failed-evaluations` | with-redefs | `ai/complete` | external: model call | `(fn [_projection {prompt :seon.ai/prompt}] {:seon.ai/text (if (= "planner" (prompt-agent-i` |
| `test/seon/incremental_publication_test.clj:88` | `non-program-inputs-have-their-own-publication-owner` | with-redefs-fn | `cluster/publication-roots` | internal: publication roots (constant stub) | `(constantly roots)` |
| `test/seon/incremental_publication_test.clj:88` | `non-program-inputs-have-their-own-publication-owner` | with-redefs-fn | `io/resource` | external: classpath resource read | `(fn [name & args] (cond (or (= name "seon/schemas") (str/starts-with? name "seon/schemas/"` |
| `test/seon/instrument_replaced_roots_test.clj:20` | `a-namespace-loaded-by-its-absolute-path-reports-no-replaced-roots` | alter-var-root | `<expr>` | expression target (unresolved statically) | `` |
| `test/seon/instrument_test.clj:1423` | `the-selection-is-declared-vars-with-schemas-and-nothing-else` | alter-var-root | `<local: boundary>` | test-local seam (bound from a fresh or ns-resolve Var) | `mi/-f->original` |
| `test/seon/instrument_test.clj:1551` | `restoring-instrumentation-state-never-reinstalls-a-replaced-definition` | alter-var-root | `<local: probe>` | test-local seam (bound from a fresh or ns-resolve Var) | `(fn [original] (with-meta (fn [& arguments] (apply original arguments)) {:seon.instrument/` |
| `test/seon/instrument_test.clj:1849` | `a-root-replaced-outside-a-reload-is-named` | with-redefs | `doubled` | test-local def (value rebound) | `(fn [n] n)` |
| `test/seon/owned_value_test.clj:188` | `new-entities-find-their-owners-without-an-index-seek` | with-redefs | `dbi/search` | external: Datahike index search | `(fn [database pattern] (when (and (nil? (first pattern)) (components (second pattern))) (s` |
| `test/seon/owned_value_test.clj:188` | `new-entities-find-their-owners-without-an-index-seek` | with-redefs | `dbi/datoms` | external: Datahike index datoms | `(fn [database index components-arg] (when (and (= :avet index) (components (first componen` |
| `test/seon/profile_test.clj:29` | `preserving-roots` | alter-var-root | `<local: candidate>` | test-owned fresh Var (intern d in the test) with instrumentation undone via `mi/-f->original` | `mi/-f->original` |
| `test/seon/profile_test.clj:31` | `preserving-roots` | alter-var-root | `<local: candidate>` | test-owned fresh Var (intern d in the test) with instrumentation undone via `mi/-f->original` | `(constantly root)` |
| `test/seon/profile_test.clj:94` | `a-same-definition-rearm-keeps-its-cell-and-a-new-definition-gets-a-new-one` | alter-var-root | `<local: candidate>` | test-owned fresh Var (intern d in the test) with instrumentation undone via `mi/-f->original` | `(constantly (fn [n] (inc n)))` |
| `test/seon/program_test.clj:1404` | `a-declaration-added-after-the-first-call-is-a-cache-miss` | with-redefs | `schema.edn/packaged-forms` | internal: packaged forms (constant stub) | `(constantly declared)` |
| `test/seon/program_test.clj:1404` | `a-declaration-added-after-the-first-call-is-a-cache-miss` | with-redefs | `schema.edn/declaration-stamp` | internal: declaration stamp (constant stub) | `(constantly [["declared-after.edn" 1 1]])` |
| `test/seon/registry_isolation_test.clj:21` | `None` | alter-var-root | `<local: candidate>` | test-owned fresh Var (intern d in the test) with instrumentation undone via `mi/-f->original` | `mi/-f->original` |
| `test/seon/registry_isolation_test.clj:23` | `None` | alter-var-root | `<local: candidate>` | test-owned fresh Var (intern d in the test) with instrumentation undone via `mi/-f->original` | `(constantly root)` |
| `test/seon/render/web_adoption_test.clj:16` | `listening-server-follows-the-handler-var` | with-redefs | `web/handler` | internal: web handler (spy) | `(fn [service] (swap! calls inc) (original service))` |
| `test/seon/render/web_context_test.clj:24` | `unrelated-adoption-preserves-pages-with-an-unchanged-sci-snapshot` | with-redefs | `kernel/invoke` | internal: SCI kernel invoke (spy) | `(fn [request] (swap! calls update (str (:seon.fn/sym request)) (fnil inc 0)) (invoke reque` |
| `test/seon/render/web_feed_test.clj:14` | `first-paint-precedes-proc-deltas-and-refusal-precedes-close` | with-redefs-fn | `await-var` | internal: feed wait (ns-resolve Var of private fn) | `(fn [tap registration-key _backstop-ms] (original tap registration-key 100))` |
| `test/seon/render/web_performance_test.clj:35` | `one-pass-derives-one-profile-for-every-page-unit` | with-redefs | `config/effective` | internal: configuration read (constant stub) | `(fn [_database _cluster-name] (swap! effective-calls inc) effective)` |
| `test/seon/render/web_performance_test.clj:35` | `one-pass-derives-one-profile-for-every-page-unit` | with-redefs | `render.walk/neighborhood` | internal: render walk (spy) | `(fn [request] ;; A real page asks for the profile from many render ;; calls. Repeating the` |
| `test/seon/render_simplification_test.clj:272` | `function-arities-are-matched-without-recompilation` | with-redefs | `m/function-schema` | library: malli compile (tripwire) | `(fn [& _] (throw (ex-info "unexpected recompilation" {})))` |
| `test/seon/render_simplification_test.clj:272` | `function-arities-are-matched-without-recompilation` | with-redefs | `m/validator` | library: malli compile (tripwire) | `(fn [& _] (throw (ex-info "unexpected validator compilation" {})))` |
| `test/seon/render_simplification_test.clj:520` | `renderer-invocation-is-sci-only-and-live-var-backed` | with-redefs | `clojure.core/requiring-resolve` | library: core resolver (tripwire) | `(fn [& _] (throw (ex-info "compiled resolver trap" {})))` |
| `test/seon/render_simplification_test.clj:643` | `settled-package-is-reused-by-every-join` | with-redefs | `hiccup/->string` | library: hiccup serializer (tripwire) | `(fn [& _] (throw (ex-info "serialized on join" {})))` |
| `test/seon/render_simplification_test.clj:679` | `shared-invocation-cache-reuses-identical-entity-inputs-across-call-ids` | with-redefs | `kernel/invoke` | internal: SCI kernel invoke (spy) | `(fn [invocation] (swap! invocations inc) (invoke invocation))` |
| `test/seon/render_simplification_test.clj:762` | `authored-source-invocation-reuses-one-stored-run-across-presentations` | with-redefs | `seon.turn/preview-sources` | internal: preview (spy) | `(fn [request] (swap! previews inc) (preview request))` |
| `test/seon/render_source_test.clj:99` | `preview-cache-is-memory-only-and-invalidates-on-read-changes` | with-redefs | `db/transact!` | internal: writer seam (spy or failure injection) | `(fn [& _] (throw (ex-info "preview wrote database facts" {})))` |
| `test/seon/render_source_test.clj:99` | `preview-cache-is-memory-only-and-invalidates-on-read-changes` | with-redefs | `blob/stage!` | internal: blob staging (tripwire) | `(fn [& _] (throw (ex-info "preview staged a blob" {})))` |
| `test/seon/render_source_test.clj:99` | `preview-cache-is-memory-only-and-invalidates-on-read-changes` | with-redefs | `agent/submit-source!` | internal: source submission (tripwire) | `(fn [& _] (throw (ex-info "preview submitted a run" {})))` |
| `test/seon/render_source_test.clj:99` | `preview-cache-is-memory-only-and-invalidates-on-read-changes` | with-redefs | `eval/evaluate` | internal: evaluator (tripwire or spy) | `(fn [request] (swap! evaluated inc) (original-evaluate request))` |
| `test/seon/render_source_test.clj:127` | `preview-cache-is-memory-only-and-invalidates-on-read-changes` | with-redefs | `eval/evaluate` | internal: evaluator (tripwire or spy) | `(fn [& _] (throw (ex-info "unchanged runtime wake re-evaluated" {})))` |
| `test/seon/render_source_test.clj:297` | `source-contracts-execute-in-memory-and-terminal-transcripts-never-reexecute` | with-redefs | `agent/submit-source!` | internal: source submission (tripwire) | `(fn [& _] (throw (ex-info "preview submitted" {})))` |
| `test/seon/render_source_test.clj:297` | `source-contracts-execute-in-memory-and-terminal-transcripts-never-reexecute` | with-redefs | `eval/evaluate` | internal: evaluator (tripwire or spy) | `(fn [request] (swap! evaluations inc) (original-evaluate request))` |
| `test/seon/schema/edn_test.clj:184` | `declaration-digest-is-independent-of-resource-order` | with-redefs-fn | `resource-paths-var` | internal: schema resource listing (ns-resolve Var of private fn) | `(fn [resource] (reverse (resource-paths resource)))` |
| `test/seon/schema/projection_acquisition_test.clj:78` | `shared-schema-descendants-are-validated-once-per-role` | with-redefs | `bridge/assert-storable-schema!` | internal: schema assertion (tripwire) | `(fn [identity compiled] (when (#{::leaf ::left ::right ::root} identity) (swap! visits upd` |
| `test/seon/schema/projection_writer_test.clj:114` | `a-speculative-value-reuses-its-basis-projection-unless-it-writes-a-declaration` | with-redefs | `seon.db/declaration-content-key` | internal: content key (spy) | `(fn [value] (swap! content-keys inc) (content-key value))` |
| `test/seon/schema/projection_writer_test.clj:114` | `a-speculative-value-reuses-its-basis-projection-unless-it-writes-a-declaration` | with-redefs | `schema/load-projection` | internal: projection load (wrapper) | `(fn ([value] (derive-projection value)) ([value base] (swap! derivation-bases conj base) (` |
| `test/seon/schema/projection_writer_test.clj:193` | `a-new-branch-at-an-equal-commit-reads-the-held-projection` | with-redefs | `seon.db/declaration-content-key` | internal: content key (spy) | `(fn [value] (swap! content-keys inc) (content-key value))` |
| `test/seon/schema/projection_writer_test.clj:231` | `the-writer-derives-from-its-database-never-a-stale-stamp` | with-redefs | `seon.db/resolve-database-value` | internal: value resolution (metadata wrapper) | `(fn [c] (vary-meta (resolve-value c) assoc :seon.schema/projection stale))` |
| `test/seon/schema/projection_writer_test.clj:231` | `the-writer-derives-from-its-database-never-a-stale-stamp` | with-redefs | `seon.db/write-error` | internal: error writer (spy) | `(fn [database projection transaction] (swap! seen conj projection) (validate database proj` |
| `test/seon/schema_test.clj:58` | `retained-generation-recompiles-dependent-roots` | with-redefs | `mr/lazy-registry` | stub or wrapper of internal logic | `(fn [defaults provider] (lazy-registry defaults (fn [k r] (when (or (contains? forms k) (c` |
| `test/seon/schema_test.clj:686` | `equal-packaged-populations-reuse-the-shape-projection` | with-redefs | `schema/build-projection` | internal: schema projection build (tripwire or spy) | `(fn [& arguments] (swap! builds inc) (apply original-build arguments))` |
| `test/seon/schema_test.clj:979` | `one-declaration-validates-only-its-dependency-closure` | with-redefs | `schema/compilable-form` | internal: schema compile (spy) | `(fn [& args] (swap! binding-walks inc) (apply original-bind args))` |
| `test/seon/schema_test.clj:979` | `one-declaration-validates-only-its-dependency-closure` | with-redefs | `schema.internal/assert-compilable-schema!` | internal: schema assertion (spy) | `(fn [& args] (swap! population-compilations inc) (apply original-compile args))` |
| `test/seon/sci/eval_instrumentation_test.clj:40` | `an-instrumented-dev-cluster-builds-an-attempt-ready-prompt` | with-redefs | `ai/complete` | external: model call | `(fn [_projection request] (async/offer! attempt-requests request) {:seon.ai/text "(seon.ru` |
| `test/seon/sci/eval_test.clj:627` | `compiled-runtime-roots-cannot-be-redefined-by-agent-code` | alter-var-root | `compiled-runtime-victim` | test-local Var (intentional: law under test) | `(constantly victim-root)` |
| `test/seon/sci/eval_test.clj:628` | `compiled-runtime-roots-cannot-be-redefined-by-agent-code` | alter-var-root | `*compiled-runtime-dynamic-victim*` | test-local Var (intentional: law under test) | `(constantly dynamic-root)` |
| `test/seon/sci/eval_test.clj:723` | `sci-fork-preserves-compiled-var-hot-reload` | alter-var-root | `compiled-runtime-victim` | test-local Var (intentional: law under test) | `(constantly (fn [] :hot-reloaded))` |
| `test/seon/sci/eval_test.clj:729` | `sci-fork-preserves-compiled-var-hot-reload` | alter-var-root | `compiled-runtime-victim` | test-local Var (intentional: law under test) | `(constantly entering-root)` |
| `test/seon/sci/eval_test.clj:1005` | `evaluate-invokes-eval-form-exactly-once-on-every-path` | with-redefs | `sci/eval-form` | internal: SCI form eval (spy) | `(fn [execution-ctx form] (swap! calls conj form) (eval-form execution-ctx form))` |
| `test/seon/sci/eval_test.clj:1005` | `evaluate-invokes-eval-form-exactly-once-on-every-path` | with-redefs | `seon.schema/call-with-registration-delta` | internal: schema registration (wrapper) | `(fn ([delta body] (let [before (seon.schema/registration-delta-form delta :user/once-schem` |
| `test/seon/sci/eval_test.clj:1130` | `evaluation-projection-prefers-the-live-context` | with-redefs | `schema/build-projection` | internal: schema projection build (tripwire or spy) | `(fn [& _] (throw (ex-info "Unexpected projection rebuild" {})))` |
| `test/seon/sci/eval_test.clj:1130` | `evaluation-projection-prefers-the-live-context` | with-redefs | `db/projection-fallback` | internal: fallback (tripwire) | `(fn [operation] (swap! missing conj operation) (fallback operation))` |
| `test/seon/sci/eval_test.clj:1783` | `root-acquisition-and-the-web-share-one-pull-plan` | with-redefs | `render.walk/root-pull-plan` | internal: render walk (spy) | `(fn [request] (let [acquired (root-pull-plan request)] (swap! plan-acquisitions conj {:seo` |
| `test/seon/sci/eval_test.clj:1783` | `root-acquisition-and-the-web-share-one-pull-plan` | with-redefs | `render.walk/root-selector` | internal: render walk (wrapper) | `(fn [database distance supplied-caps] (let [selector (root-selector database distance supp` |
| `test/seon/sci/eval_test.clj:1783` | `root-acquisition-and-the-web-share-one-pull-plan` | with-redefs | `pull-api/compile-pull-plan` | external: Datahike pull plan (spy) | `(fn ([selector-or-plan] (when (some #(identical? selector-or-plan %) @root-selectors) (swa` |
| `test/seon/test/reach_test.clj:49` | `program-digest-uses-the-databases-program-shapes` | with-redefs | `program/shapes` | internal: program shapes (tripwire) | `#(throw (ex-info "Program digest read authored resources." {}))` |
| `test/seon/test/selection_test.clj:165` | `named-selection-reuses-green-members-by-reachable-content` | with-redefs | `runner/reach-digests` | internal: reach digests (wrapper) | `(fn [database symbols & extra-roots] (swap! requested into symbols) (apply acquire databas` |
| `test/seon/test_provenance_test.clj:108` | `verified-requires-the-subject-positive-assertions-and-exact-program` | with-redefs | `db/q` | internal: query seam (stubbed refusal) | `(fn [& _] refused)` |
| `test/seon/test_reaching_test.clj:303` | `in-process-results-detect-worker-global-drift` | alter-var-root | `seon.id/digest` | internal: digest (stub via -f->original) | `malli.instrument/-f->original` |
| `test/seon/test_runner_test.clj:351` | `the-programs-own-arming-is-no-tests-drift` | alter-var-root | `<local: candidate>` | test-owned fresh Var (intern d in the test) with instrumentation undone via `mi/-f->original` | `(fn [f] (with-meta (fn [& arguments] (apply f arguments)) {:seon.instrument/var candidate ` |
| `test/seon/test_support_test.clj:261` | `an-instrumentation-test-restores-the-entering-contracts-on-failure` | alter-var-root | `<local: candidate>` | test-owned fresh Var (intern d in the test) with instrumentation undone via `mi/-f->original` | `mi/-f->original` |
| `test/seon/test_test.clj:202` | `recording-distinguishes-run-replay-from-a-new-event` | with-redefs | `db/pull` | internal: pull seam (stubbed entity) | `(fn [database selector entity] (if (= [:seon.test.run/id (:seon.test.run/id first-run)] en` |
| `test/seon/turn_continue_test.clj:114` | `prove-session` | with-redefs | `ai/complete` | external: model call | `(fn [_projection request] (when (= scenario :slow-provider) (Thread/sleep 10000) (async/of` |
| `test/seon/turn_loop_test.clj:374` | `prompt-and-call-resolve-once-record-settings-and-see-next-turn-config` | with-redefs | `ai/agent-overlay` | internal: agent settings read | `(fn [db id] (swap! overlays conj [db id]) (let [value (overlay-fn db id)] (swap! overlay-v` |
| `test/seon/turn_loop_test.clj:374` | `prompt-and-call-resolve-once-record-settings-and-see-next-turn-config` | with-redefs | `ai/settings` | internal: configuration read | `(fn [cluster-settings agent-settings] (let [resolved (settings-fn cluster-settings agent-s` |
| `test/seon/turn_loop_test.clj:374` | `prompt-and-call-resolve-once-record-settings-and-see-next-turn-config` | with-redefs | `ai/complete` | external: model call | `(fn [_projection request] (swap! requests conj request) (let [completion (first @completio` |
| `test/seon/turn_loop_test.clj:892` | `a-private-def-settles-without-staging-a-blob-and-closes` | with-redefs | `blob/with-publication!` | internal: publication wrapper | `(fn [conn staged-writes commit-roots!] (vreset! handed staged-writes) (publish conn staged` |
| `test/seon/turn_loop_test.clj:918` | `a-refused-terminal-commit-still-closes-the-run` | with-redefs | `blob/with-publication!` | internal: publication wrapper | `(fn [conn staged-writes commit-roots!] (vswap! attempts inc) (if (= 1 @attempts) (throw (e` |
| `test/seon/turn_loop_test.clj:1002` | `a-refused-batch-settlement-closes-the-turn-and-the-agent-turns-again` | with-redefs | `blob/with-publication!` | internal: publication wrapper | `(fn [conn staged-writes commit-roots!] (vswap! attempts inc) (if (= 1 @attempts) (if (= :t` |
| `test/seon/turn_test.clj:549` | `an-episode-budget-read-refusal-reaches-next-work-verbatim` | with-redefs | `db/q` | internal: query seam (stubbed refusal) | `(fn [form & arguments] (if (= budget-query form) refusal (apply query form arguments)))` |
| `test/seon/turn_test.clj:1331` | `a-refused-turn-write-is-bounded-and-commits-exactly-one-fault` | with-redefs | `turn/next-agent-work` | internal: scheduling read (failure injection or wrapper) | `(fn [& _] {:seon.turn.work/situation :close :seon.agent/id agent-id :seon.turn/id "no-such` |
| `test/seon/turn_test.clj:1331` | `a-refused-turn-write-is-bounded-and-commits-exactly-one-fault` | with-redefs | `turn/more-agent-work?` | internal: scheduling predicate (constant) | `(fn [& _] true)` |
| `test/seon/turn_work_cost_test.clj:31` | `generated-state-agrees-with-the-writer` | with-redefs | `ai/agent-overlay` | internal: agent settings read | `(fn [& _] (throw (ex-info "Read unrelated agent settings." {})))` |
| `test/seon/web/jvm_test.clj:391` | `search-projects-the-live-serper-shape-and-blobs-the-raw-response` | with-redefs-fn | `<helper: (ns-resolve 'seon.web.jvm 'credential)>` | private credential read (constant stub) | `(constantly "test-serper-key")` |
| `test/seon/web/jvm_test.clj:444` | `public-search-settles-one-receipt-with-provider-credits` | with-redefs-fn | `<helper: (ns-resolve 'seon.web.jvm 'credential)>` | private credential read (constant stub) | `(constantly "test-serper-key")` |

