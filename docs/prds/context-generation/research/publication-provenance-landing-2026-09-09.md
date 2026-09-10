---
type: research
status: active
tags: [research, operator, database]
date: 2026-09-09
---

# Incremental publication provenance

Read AGENTS.md's verbatim §10 lane rules, the turn PRD §10, and
`docs/seon/issues/incremental-publication-refuses-missing-function-provenance.md`
end to end. Read the plan README and working edge and the data-oriented
Clojure, REPL, testing, and Datahike skills.

Dependency ledger: Datahike map upserts leave omitted facts untouched
(`reference-code/datahike/src/datahike/db/transaction.cljc:738`, `explode`,
and `:949`, `entity-map->op-vec`; gitlink
`cdcb5792db8bd599487f099437265d18a31164a5`); the first-party transaction
boundary validates the authored entity map (`src/seon/db.clj:2387`,
`write-map-error`). The schema bridge
already derives component and cardinality-many attributes
(`src/seon/fn.clj`, `many-or-component-attributes`, through
`src/seon/schema/datahike.clj`). Static artifacts already attach core
provenance (`src/seon/fn.clj`, `artifact`). Reuse those owners.

The live JVM probe on default returned an incremental row containing only
`{:seon.fn/doc "after", :seon.fn/sym "probe/value"}` from a complete
immutable input containing `:seon.fn/ns [:seon.ns/name 'probe]` and
`:seon.schema.admission/source :core`. Both omitted attributes are required
by `resources/seon/schemas/seon.fn.edn`. A first installed-row probe returned
nil and was inconclusive; the explicit immutable example established the
projection defect without mutating default.

The fix retains each changed row's scalar declaration, excluding attributes
the existing schema bridge identifies as components or cardinality-many.
This retains provenance for namespace, function, and test rows and all
required scalar keys without a second required-key roster. The existing
planner still sends structural edits to complete publication.

Verification:

- `bin/test-fast --paths src/seon/fn.clj test/seon/cluster/source_test.clj -- seon.cluster.source-test`:
  13 tests, 96 assertions, zero failures/errors. Contracts armed in panic mode:
  944 instrumented functions. The first iteration failed because this lane's
  new test lacked a `seon.program` require; corrected before this green run.
- `bin/test --paths src/seon/fn.clj test/seon/cluster/source_test.clj -- seon.cluster.source-test`:
  13 tests, 98 assertions, zero failures/errors; coordinator/tests 55 seconds.
  Snapshot basis `2b9ebe98c433a45c22ae3d8208b066b3ad4f5c24`.
- `bin/test --paths src/seon/fn.clj test/seon/cluster/source_test.clj --platform`:
  84 tests, 505 assertions, zero failures/errors; coordinator/tests 64 seconds.
  This gate includes the final component-presence assertions. Both successful
  isolated roots were removed by the gate. No worktree was needed.
- Final regression explicitly asserts the preexisting alias and function
  arity components are present before comparing their identities. Its
  first-party source bytes change `One identity entry:` to
  `The identity entry:` and `SHA-256 of (pr-str data), truncated` to
  `SHA-256 of (pr-str data), shortened` in a disposable copy of `seon/id.clj`.
  It runs authored-map validation on `with-database`, then publishes through
  `source/upsert!` over the canonical manifest populated by
  `seon.cluster/populate-source!`.

Foreign boundary: data-lane owns in-flight schema, turn, message, plan,
and other writer/test edits. None were edited, reverted, messaged, or operated.
Its protected `test/seon/fn_test.clj:993` still expects the invalid sparse row;
the integration follow-up is
`docs/seon/issues/incremental-planner-test-expects-incomplete-rows.md`.
The complete `seon.fn-test` namespace is outside this lane's verification.

Implementation committed as `1e778e880`. Live verification used in-place
development adoption, never a restart, stop, or refork. Default remained PID
92059, generation `31635c79-fdaf-4632-ac48-8c1541fb66db`.

Initial attempts refused at the source-digest stability check while edits
were ongoing. A later command selected complete publication because changes
included schemas and added/removed identities. It refused during contract
projection (2511 schemas, 980 functions): `:my.note/agent` allegedly did not
fit `seon.note/render-notes-ai`'s `[:or :my.note/notes :seon.render/unit]` input.
This was **stale loaded code**, not evidence of a data-lane schema defect:
`src/seon/schema.clj:1542` already explicitly accepts this input (commit
`848d08a22`). With one immutable supplied contract, live
`render-contract-observation` returned false before reloading `seon.schema`
and true afterward. The file had no in-flight edits; only its loaded Vars
were reloaded, with the cluster projection supplied and contracts rearmed.
No schema source, data-lane file, or lifecycle state was changed by this
repair. This observation belongs to the existing issue
`docs/seon/issues/publication-reload-hand-lists-namespaces-and-misses-dependencies.md`.

After the reload, complete publication passed. One adoption reached SCI
acquisition and JVM instrumentation but detected a source change and refused
to record convergence. A later attempt met clj-kondo cache contention, the
existing issue `docs/seon/issues/source-publication-cache-contention-hides-dependency-analysis-failure.md`.
Neither refusal was treated as success. Retrying the ordinary operator
completed publication and adoption; no other lane's session was operated.

Final command, exit 0:

```sh
bin/seon init --dev default --changed src/seon/fn.clj
```

Exact successful output:

```text
● current-src: incremental scalar publication: 1 paths; reasons=()
● current-src: development cluster converged
● :current-src commit 6aa204e6-946e-5f6e-8648-1e034bf7dc85 digest 9b2a1abae7bff3bf5e0c02e11ee7b53a702e5dccd7bc67910b019039daedcbfc
```

The one-file probe temporarily changed `plan-file-change`'s docstring from
`Classify one file change as safe upserts or a clean rebuild.` to
`Classify one file change as complete scalar upserts or a clean rebuild.`
That version converged at `6aa20423-ef8a-548a-9d23-53c5ba877976`.
Restoring the original wording caused the final scalar publication above.
The final source files exactly match the committed, gated bytes (SHA-256):

```text
e03d9793bf9365e1f6555cfc4be1d116996519ddf4bceefc3206a6461a3bbd2d  src/seon/fn.clj
b348c1b565d93e60f95dd243650ab3ac2473c8ae131e3f24b8404f6eaa47d818  test/seon/cluster/source_test.clj
```

MCP JVM readback independently observed BOTH commit IDs equal to
`6aa204e6-946e-5f6e-8648-1e034bf7dc85`, plus the restored docstring,
`:seon.schema.admission/source :core`, and `:seon.fn/ns {:db/id 7766}` on
`seon.fn/plan-file-change`. Reproducible readback form:

```clojure
(let [c (seon.operator/connection "default")
      config (seon.cluster/resolve-bootstrap {:seon.boot/root "."})
      held (get @seon.operator.runtime/root-store-holder
                (.getCanonicalPath
                 (clojure.java.io/file (:seon.boot/store-dir config))))
      current (seon.cluster.source/current (:seon.store/store held))
      installed (seon.db/pull @c [:seon.source/commit-id]
                             [:seon.cluster/name "default"])
      row (seon.db/pull @c
                       [:seon.fn/doc :seon.fn/ns :seon.schema.admission/source]
                       [:seon.fn/sym "seon.fn/plan-file-change"])]
  {:publication.probe/current current
   :publication.probe/installed installed
   :publication.probe/row row
   :publication.probe/converged?
   (and (uuid? (:seon.source/commit-id installed))
        (= (:seon.source/commit-id current)
           (:seon.source/commit-id installed)))
   :publication.probe/pid (.pid (java.lang.ProcessHandle/current))})
```

All explicit test/publication shells completed. The successful gate roots
were removed by the harness; this lane's logs and one JVM thread-dump scratch
file were removed after recording the evidence. No scratch cluster or
worktree was created. The protected planner-test follow-up remains open;
no broader test-suite or browser-health claim is made.
