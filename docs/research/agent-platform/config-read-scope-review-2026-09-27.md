# Config read scope design review — 2026-09-27

**Verdict: REVISE.** Explicit cluster selection fixes the demonstrated defect, and
`seon.config/effective` is the existing semantic owner. The proposed global supplier
changes unrelated target-selection contracts, and B2 does not contain its caller
closure. Revise those parts before source edits; no alternative registry, membership
fact, cache, or global current-cluster state is needed.

Reviewed design: `23f60c53b68a3ca7a54dc24b560306466152fbbe`, unchanged in the checkout.
Source observations below use HEAD `5d537eaf37fb4caf0f7bdfff8746f0ee9db1b7ac` unless
explicitly identified as runtime observations. Read AGENTS.md, the design, triage
(defect 1), configuration/environment/call-preparation owners, and the cited sites.
Runtime: default, root `/Users/sean/src/seon`, pid 22787, start 2026-09-27T05:47:52Z,
committed source `da10c8912b189704fb550e5852d431d118682f11`. It is reachable but
**faulted**: nine open faults, 124 failed tests and 44 unknown failed-test observations.
These are pre-existing observations, not results of this review. No tests, provider
requests, transactions, reloads, adoption, arming, or restarts were performed.

1. **The inventory covers the arbitrary-row queries, but not every unscoped dial read.**
   Independent searches of `src` for `:seon.config/cluster`, all `:seon.config.*`
   occurrences, `:seon.cluster/config`, variable-attribute queries and datom reads
   reproduce the design's 18 groups. Agent-bound overlay reads are legitimate;
   a dial need not bind a cluster when its entity is explicitly an agent's settings.
   Two additional branch-wide reads must be classified: `src/seon/db.clj:4567`
   takes the minimum of every asserted `validation-node-limit`, and `:4823` takes
   the minimum of every asserted `write-time-limit-ms`. These use AEVT datoms,
   not Datalog, so the proposed query scan misses them. They are deterministic
   aggregate policies, not arbitrary scalar reads; the former explicitly says
   every asserted policy applies to every writer. Do not silently change their
   semantics in this fix. Add them to the intentional aggregate inventory and
   cite/rule the writer policy. The existing collection aggregate at
   `src/seon/cluster/registry.clj:549-577` and configuration reconciliation at
   `src/seon/config.clj:753-779` also remain deliberate.

   **Required change:** distinguish arbitrary selection from aggregate policy and
   scoped overlays in §1; withdraw the universal claim that every dial read must
   call `effective`. Row 3 is a reference selection, not a dial: `effective` drops
   identity fields (`config.clj:899-900`). At `turn.clj:4411-4416,4462`, use the named
   configuration lookup ref (or its resolved entity) as the settings reference,
   preserving the agent-settings preference; do not put the effective map in a ref.

2. **B1 is not a harmless standalone supplier addition. It supplies target names too.**
   `call_preparation.clj:550-580` matches positional arguments by declared schema
   name, and required map entries by both key and value schema name. This is not
   structural string matching, but `:seon.boot/cluster-name` already denotes more
   than the calling environment's cluster. `config/compile-manifest` constructs
   a requested target row (`config.clj:546-576`); `config/apply!` changes the named
   target (`:838-858`); `registry/ensure-cluster!` and `reset-cluster!` select a
   requested branch (`cluster/registry.clj:279-333`). The existing test
   `test/seon/config_test.clj:506-521` explicitly requires compile/apply omission
   to refuse, with the database basis unchanged.

   A read-only runtime probe inserted the proposed row into an immutable snapshot
   value and called `arm-plan`, without installing the row or invoking its supplier.
   It made compile-manifest's entire request suppliable, apply!'s request suppliable
   with both connection and cluster, and filled the cluster entry of ensure/reset.
   The same mechanism fills required entries even on full-arity calls: an explicit
   request map missing the target no longer necessarily refuses. This affects JVM
   functions called under an evaluation arm too, not just direct SCI calls
   (`call_preparation.clj:987-1033`; see `a-compiled-first-party-call-is-prepared`).
   Host writer/boot calls without an evaluation world still receive no defaults.

   **Required change:** keep target selection explicit. Use a separately declared
   *current cluster* argument identity for the new environment default, and use it
   only where omission means this evaluation's cluster; retain existing boot/target
   identities for constructors, mutations and observations of other clusters. Do not
   add a function allowlist to call preparation or change its matching algorithm.
   Price the extra schema/supplier/error declaration and its consumers in B1. A
   supplier copied from `supplied-agent-id` also needs a real declared error arm
   (`env.clj:338-353`, `call_preparation.clj:185-242`), not the agent-id error renamed
   only in prose. The design's “no new schema attribute”/line estimate needs revision.

   The installed contract inventory below is derived from compiled argument facts,
   including named map schemas, rather than a grep of function bodies. Every row
   becomes newly suppliable under the proposed global row. Explicit full positional
   calls retain precedence; optional entries and `[:maybe ...]`/`[:or ...]` positional
   schemas do not match the bare supplied name.

   | Kind | Contracts and checkout evidence |
   |---|---|
   | Configuration target/read | `seon.config/held-overlay` :814, `effective` :876, `effective-in` :888 (positions); `compile-manifest` :546 and `apply!` :838 (request entries; `resources/seon/schemas/seon.config.edn:9-34`) |
   | Branch target construction/lifecycle | `seon.cluster.branch/cluster-branch` :12; `seon.cluster.registry/fork-row` :243, `record-fork!` :251 (positions); `ensure-cluster!` :288 and `reset-cluster!` :314 (request entries; `seon.cluster.registry.edn:16-24`) |
   | MCP named observation/projection | `seon.cluster/mcp-effective` :313, `mcp-project` :355, `mcp-valf` :489, `mcp-get-value` :521, `mcp-runtime-observation` :598 |
   | Paths/source selection | `seon.cluster/cluster-paths` :812, `source-base!` :1849; `seon.render.web/derived-port` :3851 |
   | Execution identity | `seon.eval.drive/inbound!` :73; `seon.fault/record!` :101; `seon.sci.eval/projection-state` :202 |
   | Boot inputs/observations | `seon.cluster.boot/stand-cluster-runtime!` :33 (position and config-map entry), `listening-instance` :423 and `banner` :783 (named map entries) |
   | Other required map entries | `seon.schedule/fire-call` :293, `fire-due!` :631; `seon.render.transcript/agent-history` :987; `seon.maintenance/project-cluster-cleanup-result` :112 |
   | Installed test helper | `seon.sci.branch-execution-test/on-branch`, `test/seon/sci/branch_execution_test.clj:41` (direct slot 2) |

   In particular, cleanup-result projection consumes a historical observation, not
   a request for current-cluster custody. Filling an omitted result member from the
   caller can turn malformed evidence into a plausible observation. Constructor
   targets and historical results cannot all be reinterpreted as current defaults.

3. **B2's caller closure is incomplete, and B4 is not file-disjoint from it.**
   `turn/deferred-triggers` calls `opening-deferred?` at `turn.clj:2962`; its callers
   include `problems.clj:280` and `turn.clj:5590`. `eval/drive.clj:256-260` and
   `render/transcript.clj:1241` construct `next-agent-work` requests without a name.
   `:seon.turn.work/agent-request` currently declares only agent id
   (`resources/seon/schemas/seon.turn.work.edn:1-4`): an open map allows a new key but
   does not require or validate the name on which correctness now depends.

   More critically, the writer chain is
   `turn -> plan/settle-call -> issue/settle-call -> issue/exhaust-tx`:
   `turn.clj:2387,3734,3910,5444`, `plan.clj:765-787`, `issue.clj:1507-1518`.
   B2 names the last tx-function edit but omits plan.clj and its incoming calls.
   `plan/settle-call` also reaches the unscoped query deadline, so the supposedly
   independent B4 plan leaf belongs to this same signature closure.
   `issue/status` needs the name in its request (`issue.clj:1011-1014,1068`), and
   its internal and `my.issue/status` callers must pass it too.

   **Required change:** make one loadable B2 contain this closure, the declared
   request schemas, problem derivation callers, evaluation driver, and all tests
   and quoted forms naming changed arities. The closure includes substantially
   more than the two named tests: e.g. `turn_work_test.clj:725`,
   `run6_stall_test.clj:88`, `issue_settlement_test.clj:198`,
   `agent_situation_test.clj:36`, `cluster/prompt_test.clj:317,489,529`,
   `render/web_debug_test.clj:325`, `sci/supplied_database_test.clj:24-29`, and the
   `next-agent-work` request constructors across turn/loop tests. Mechanically
   search the complete source/test/script corpus, including symbols in data.
   Schedule problems.clj and plan.clj with B2 or behind it, not concurrently.

   The blob leaf also misses a caller: `blob/put-binary!` invokes `stage-binary!`
   at `blob.clj:346`; its public contract and callers in `test/seon/blob_test.clj`
   must change in the same slice. Passing the already-held threshold in
   `effect.clj:506-519` is smaller than another config read there.

4. **New positional omission is ambiguous even with correctly named schemas.**
   The proposed `[database cluster-name agent-id]` contract has two string-valued
   supplied slots. Using Malli's real compiled schemas and call preparation's
   private pure plan functions, `[database "root"]` produced
   `{:seon.call-preparation/ambiguous? true, :seon.call-preparation/candidates [[1] [2]]}`.
   Zero arguments has an unambiguous all-default plan, but the old explicit
   database/agent call is no longer an explicit complete call. The regression
   `sci/supplied_database_test.clj:24-29` currently emits exactly that old shape.
   `partial-insertions`/`partial-preparation` (`call_preparation.clj:856-911`)
   correctly refuse this; do not teach them to guess.

   **Required change:** document the new full arity and convert every authored
   example/test/caller. Prefer keyed request entries where independent partial
   omission is intended; otherwise explicitly require all positional arguments
   when selecting another agent/cluster. Include zero-argument, fully explicit,
   and ambiguous partial-call behavior in supplier proof. “Gives up nothing” is
   false for the signature migration.

5. **The claimed refusal propagation does not exist at several affected boundaries.**
   `repl/frame` returns `:string` (`repl.clj:63-67`); `bootstrap/situation` allows
   only its situation and missing-agent error (`bootstrap.clj:103-107`);
   `send-call` and `exhaust-tx` return tx-data (`cluster/message.clj:677-680`,
   `issue.clj:1477-1479`). `deferred-triggers` returns a vector and currently
   treats any truthy error from `opening-deferred?` as deferral (`turn.clj:2957-2967`).
   A map passed to `get`, `ai/settings`, `pos?`, or `zero?` does not automatically
   propagate as a declared error (`ai.clj:321-327`, `issue.clj:1488`,
   `render/transcript.clj:1240`). `effective` itself admits configuration,
   projection-validation and database-read errors (`config.clj:876-883`).
   `sci/eval.clj:2800-2807` additionally replaces a configuration refusal with
   `config/defaults`; scoping its query alone preserves that silent fallback.

   **Required change:** give each changed producer its exact error alternatives,
   explicitly branch before reading/merging a refused value, and route the result
   through its existing caller boundary. Transaction functions must throw the
   whole typed refusal to abort, never return a refusal map as tx-data:
   Datahike `db/transaction.cljc:1171-1172` splices their returned data into the
   transaction. Remove the touched SCI default substitution or explicitly deliver
   that fault through the existing acquisition boundary. Preserve actual absence:
   `run/max-episode-runs` is optional (`seon.config.run.edn:1-7`) and a missing dial
   deliberately defers opening; it is not the same as a missing cluster row.

6. **Keep one semantic reader, but acquire once per operation and reuse held data.**
   Independent warm JVM probe (same immutable default db, 10 warmups, 200 calls)
   measured unscoped query 54.22 µs, scoped query 59.96 µs, one-dial pull 17.30 µs,
   `effective` 150.64 µs; the complete probe took 67 ms. These are unarmed host
   observations, not an armed hot-path benchmark or a parent/change comparison.
   They support the design's order of magnitude, not “no regression” evidence.

   Work scales with calls: `next-agent-work`/opening checks per work derivation,
   plus `problems/deferred-agents` once per agent (`problems.clj:274-287`);
   plan settlement reaches configuration per evaluation settlement
   (`turn.clj:2387`, `plan.clj:779`, `issue.clj:1491`), including writer db values;
   SCI function-contract installation reads it per installed function
   (`sci/eval.clj:1359`); transcript classification reads historical opening values
   per displayed turn (`render/transcript.clj:1161-1170`); web coalescing reads it
   per render pass (`render/web.clj:2693`). Blob reads are per capture/reply/provider
   attempt. These are not all “once per woken agent.”

   **Required change:** reuse resolved settings already held by prompt/frame
   (`cluster/prompt.clj:406-407`) and threshold values already held by effects;
   share one effective map for runner threshold and caps, and one value for any
   same-db same-cluster derivation. Keep writer decisions inside their tx function.
   Measure armed work derivation and settlement, plus the historical rendering
   case, before/after. A one-dial named pull is sufficient to select a stored dial,
   and is the smallest physical operation; it is not a drop-in replacement for
   required-dial accretion defaults, missing-row refusal and projection semantics
   in `config.clj:891-916`. If measurement warrants selective acquisition, extend
   the existing config owner rather than copying those semantics at each leaf.
   A literal selector for the particular dial a consumer uses is not a forbidden
   duplicate schema inventory. No cache is justified by these measurements.

7. **Two proposed sources of cluster identity need concrete ownership rules.**
   `problems` takes a request declared as `[:map]`, ignores it, and its MCP caller
   passes `{}` (`problems.clj:421-433`, `resources/seon/schemas/seon.problems.edn:65`,
   `cluster.clj:611`). The claim that its request already carries the cluster is
   false. Its requery form also passes `{}` (`problems.clj:640`). Retaining every
   agent overlay while restricting only the cluster row means branch-wide agent
   diagnostics, not proven membership in the chosen cluster: there is no stored
   membership relation. `whoami` inspecting a different agent has the same limit.

   `:seon.test.run/cluster` is a ref, not a string. Its schema deliberately permits
   absence for published snapshots and sweeping on deletion
   (`resources/seon/schemas/seon.test.run.edn:28,43,119`); the producer writes
   `[:seon.cluster/name cluster]` (`test.clj:2271`). `prepare-failures!` distinguishes
   tested and recording databases (`test/runner.clj:1247-1253`). Making the stored
   ref required changes deletion/history semantics and still does not resolve
   which world's threshold governs recording.

   **Required change:** declare/thread problems' cluster at its actual callers and
   describe overlay reporting honestly as branch-wide unless membership is known.
   For completion, name whether recording custody or tested custody supplies storage
   policy, resolve its ref on that database or pass the explicit cluster/effective
   value from the producer, and handle unavailable identity as a typed error. Require
   the input at the execution/completion boundary where necessary; do not retroactively
   make historical stored refs mandatory. Include src/seon/test.clj and the relevant
   request schemas in the runner slice if producer changes are needed.

8. **The proposed proof needs behavioral assertions and a working inspection seam.**
   A source-text assertion banning every unbound config query outside two namespaces
   would reject legitimate agent overlays (`turn.clj:2882-2886`, `plan.clj:642-645`)
   and misses the datom reads in finding 1. It codifies a syntax/owner handlist rather
   than the wrong-value behavior. Use the two-cluster class regression, including
   real writer delivery, false overlay precedence, missing row versus absent optional
   dial, current-environment defaults and explicit cross-cluster selection. Cover
   required-dial defaults on old rows before choosing a selective reader.

   The design's proposed `supplied-map-entries` audit currently gives false negatives:
   `call_preparation.clj:605` passes `database` to `planned-arities`, which expects
   a projection (`:585-590`). On live default, `supplied-map-entries` for config/apply!
   returned `[]`, while `arm-plan` exposed `[:seon.db/connection]` for each arity.
   The probe took 4 ms. This is a foreign call-preparation defect; no source changed.

   **Required change:** remove the proposed source-scan regression; use actual
   compiled arity plans for this design's compatibility evidence, and have the
   inspection owner repair the false-negative seam separately. No new atoms,
   mirrors, membership roster, JVM-global state or consumer-specific preparation
   exceptions are warranted. The proposed pure supplier otherwise composes with
   the existing machinery.

## Probe and dependency evidence

The runtime probes used `eval_clj`, mode `jvm`, `read_only true`, explicit root and
cluster. Hypothetical supplier data existed only in a local immutable value; the
supplier function does not exist and was never invoked or represented as installed.
Inventory/plan probes took 8 ms and 5 ms; the positional ambiguity probe took 5 ms.
No allocation benchmark was performed. Runtime status reported central cache weighted
size 107,079,316 bytes against its configured 512,000,000-byte bound; this is status,
not this change's retained-size measurement.

Reproduction of the decisive plan probe:

```clojure
(let [d @(seon.cluster.boot/connection "default")
      p (seon.db/carried-projection d)
      s (seon.call-preparation/snapshot d p)
      k :seon.boot/cluster-name
      candidate {:seon.call-preparation/key k
                 :seon.call-preparation/schema-key k
                 :seon.call-preparation/supplier-symbol 'seon.env/supplied-cluster-name}
      proposed (assoc-in s [:seon.call-preparation/supplied-defaults k] candidate)]
  (mapv (fn [sym]
          [sym (mapv (fn [a]
                       [(:seon.fn.arity/order a)
                        (mapv :seon.fn.argument/index (:seon.call-preparation/slots a))
                        (mapv :seon.call-preparation/entry-key (:seon.call-preparation/entries a))])
                     (:seon.call-preparation/arities
                       (seon.call-preparation/arm-plan proposed p sym)))])
        '[seon.config/effective seon.config/compile-manifest seon.config/apply!
          seon.cluster.registry/reset-cluster! seon.cluster.registry/ensure-cluster!]))
```

Result, `[arity slots entries]`: effective `[[0 [0 1] []]]`; compile-manifest
`[[0 [0] [:seon.boot/cluster-name]]]`; apply!
`[[0 [0] [:seon.db/connection :seon.boot/cluster-name]]
[1 [0] [:seon.db/connection :seon.boot/cluster-name]]]`; reset/ensure each
`[[0 [] [:seon.boot/cluster-name]]]`.

For inventory, enumerate `#'seon.call-preparation/contract-arities p`, then each
`:seon.call-preparation/arity-arguments`: direct `:seon.call-preparation/schema-key`
equals the proposed name, or a required `:seon.call-preparation/map-entries` tuple
has both that key and value name. For ambiguity, compile
`[:=> [:cat :seon.db/database-value :seon.boot/cluster-name :seon.agent/id] :int]`
with `malli.core/function-schema` under p's registry; use `arity-facts`, `arity-plan`
with `by-schema-key` of proposed defaults, `compiled-plan`, then
`partial-preparation` with `[d "root"]`. These pure functions produce finding 4.

The timing probe (results in finding 6):

```clojure
(let [database @(seon.cluster.boot/connection "default")
      dial :seon.config.run/max-episode-runs
      readers {:unscoped #(seon.db/q '[:find ?v . :where
                                       [?c :seon.config/cluster _]
                                       [?c :seon.config.run/max-episode-runs ?v]] database)
               :scoped #(seon.db/q '[:find ?v . :in $ ?n :where
                                     [?c :seon.config/cluster ?n]
                                     [?c :seon.config.run/max-episode-runs ?v]] database "default")
               :pull #(seon.db/pull database [dial] [:seon.config/cluster "default"])
               :effective #(seon.config/effective database "default")}]
  (into {} (map (fn [[k f]]
                  (dotimes [_ 10] (f))
                  (let [start (System/nanoTime)]
                    (dotimes [_ 200] (f))
                    [k {:us (/ (- (System/nanoTime) start) 200000.0)
                        :value (let [v (f)] (if (map? v) (get v dial) v))}]))
                readers)))
```

Malli pin `43c6dca8d2d8df3162b13725939954807be4a4ed`: `core.cljc:3130`
`function-schema` verifies the compiled function shape; call preparation reads its
arity children through `m/-function-schema-arities`/`m/-function-info`
(`call_preparation.clj:132-177`). Datahike pin
`fe1d4e26edcc251f4471e720504b56353f4c2d09`: `pull_api.cljc:517-539`
resolves selector/eid; `db/utils.cljc:116-141` resolves a unique lookup ref through
AVET, so name selection does not scan every cluster. Effective's additional work
is proportional to declared dials and the pulled row; it recomputes per call.

## Landing and central verification

Review-only change: this file; net source/test lines **0/0**. No runtime behavior
is installed by this commit. No worktree or another lane's session was used.
Pre-existing shared edits (.gitmodules, reference-code/CLM, the http-kit submodule,
next-turn/src-clm additions and a deleted tmp patch) are outside this assignment.
Static verification uses a committed archive plus only this review, excluding those
edits. The inspection false negative in finding 8 and existing runtime faults are
foreign boundaries, not reasons to stop the review.

Static checks on archive `5d537eaf37fb4caf0f7bdfff8746f0ee9db1b7ac` plus this
review:

- `clj-kondo --cache false --lint src script test`: **0 errors**, 963 existing
  warnings, 5,985 ms, exit 2 because of warnings. Log:
  `tmp/config-read-scope-review-astra-20260927/lint.txt`. This is not zero findings.
- Direct `seon.contracts-compile-test/check` (no deftest or test request) over 485
  source/script/test files: 2,182 ms, **0 src/test contract findings; 10 script
  findings**. The unchanged script contracts use schema Vars/symbols which the
  publication canonicalizer refuses: `script/seon/dev/docstring.clj:285,318,334,360`
  and `script/seon/dev/markdown.clj:1020,1050,1090,1124,1170,1215`. These are outside
  the checker's ordinary src/test population (its namespace docstring and
  `every-declared-contract-compiles-against-the-packaged-projection`). Full returned
  evidence is artifact `f568eceb5680d5b0ee075af20923d437de71b30b4b82f7aa67b63b887e393fe3`
  at default. The broader check is not green; no foreign source was edited to make
  it green. This documentation diff declares no contracts or schema/config resources.

The compile form called `source-files` on archive `/src`, `/script`, `/test`, then
`check` with that archive as `:seon.contracts-compile-test/root`, those absolute
`:seon.contracts-compile-test/paths`, and default's carried projection as the
`:seon.contracts-compile-test/candidate`. The checker compares packaged forms before
reusing the candidate, otherwise builds the archive's projection. No namespace from
the archive's source population was loaded by the static checker.

Root's focused requests after implementation (not run by this lane):

- New `seon.config-scope-test/config-read-scope-test`, with the behaviors in finding 8.
- `seon.cluster.agent-test/episode-cap-refusal-test`.
- `seon.cluster.turn-test/generated-opening-preserves-one-provider-turn-budget`.
- `seon.config-test/cluster-scoped-config-refuses-an-omitted-cluster`.
- `seon.sci.supplied-database-test/own-record-reads-supply-the-same-database-and-agent-as-explicit-calls`.
- `seon.call-preparation-test/a-two-slot-arity-prepares-only-unique-partial-placements`.
- `seon.call-preparation-test/an-optional-entry-is-never-supplied-and-a-required-one-refuses`.
- `seon.call-preparation-test/a-call-matching-a-declared-arity-is-never-prefixed`.
- `seon.plan-completion-test/query-completion-is-decided-by-the-writing-database`.
- `seon.blob-test/binary-content-round-trips-on-both-sides-of-the-inline-threshold`.
- `seon.agent-situation-test/situation-is-the-live-derived-control-surface`.
- `seon.turn-work-cost-test/generated-state-agrees-with-the-writer` plus the same
  armed next-agent-work/settlement timing probe on parent and change.
