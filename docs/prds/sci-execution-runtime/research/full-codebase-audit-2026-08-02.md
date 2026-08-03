---
type: research
status: active
tags: [research, audit]
---

# Full fresh-codebase adversarial audit — 2026-08-02

## Verdict

Fresh Seon is a **promising, unusually principled nucleus with several sharp
unfinished seams**. It is not a rewrite wreck, and it is not close to clean.
The strongest code is the database/run transition core, source indexing,
schema admission, SCI value admission, Hiccup serialization, and exact process
identity. The roughness concentrates where those mechanisms meet: provider
bytes become executable code, Flow admits bounded work, SCI code enters render,
liveness enters debug UI, and dev/test gates decide that absent evidence is
health.

Three new defects are blockers:

1. malformed streamed provider data can be deleted and splice surrounding
   chunks into a different valid program (`src/seon/ai.cljc:418-458`,
   `src/seon/cluster/loop.cljc:1205-1226`);
2. a full Flow submission buffer can block before `submit!!` starts its time
   limit (`src/seon/flow.clj:479-499`, pinned Flow
   `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:190-197`);
3. the render router cannot invoke a function defined in the live SCI program
   context at all (`src/seon/render.clj:282-305,331-382`,
   `src/seon/sci/eval.clj:1210-1233`).

The first and third are **in flight (schema-edn-consolidation lane)** because
their source files are modified in the shared tree. The observed diffs only
change schema-resource comments (`src/seon/ai.cljc:73-76`,
`src/seon/render.clj:51-54`) and do not address either defect.

## Scope and method

I read the actual fresh sources in `src/`, `test/`, `script/`, and `bin/`, plus
`resources/seon/schema.edn`, `resources/seon/bootstrap.edn`, `dev_cache.clj`,
and `build.clj`. The audit snapshot contained 59 `src/` files, 95 `test/`
files/helpers/fixtures, nine `script/` files, ten `bin/` files, and the four
named root/resource files: 177 paths and 74,575 lines. Reproduce those counts
with:

```bash
rg --files src test script bin
wc -l $(rg --files src test script bin | sort) \
  resources/seon/schema.edn resources/seon/bootstrap.edn build.clj dev_cache.clj
```

The tree moved during the audit as instructed. At the final source review it
had 31 modified tracked files plus untracked `test/seon/fs_test.clj`; findings
in any modified owner are marked in-flight. No source, test, resource, script,
or live cluster was changed by this audit.

Grounding preceded judgment: root `AGENTS.md`, `docs/TRANSFER_PROMPT.md`, the
open issue index and every open note's problem statement, the localized PRD
instructions, the working edge, and rulings #20–#40. Dependency claims below
were checked against the pinned sources named at the boundary, notably Flow
injection (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:190-197`)
and SCI context lifetime (`reference-code/sci/src/sci/core.cljc:318-323`).

No `bin/test` run was started. Cheap suspicions were falsified with load-only
`clojure -M:dev` probes. The important observed results are included below.

## Ranked findings

### Blockers

#### B1. Malformed SSE data can silently change executable agent code

`stream-event` calls unreadable `data:` JSON presentation noise, catches it as
`nil`, and continues accumulating later deltas (`src/seon/ai.cljc:403-458`).
`streamed-completion` promotes that partial text to an ordinary success
(`src/seon/ai.cljc:671-697`), and the turn freezes it into run forms
(`src/seon/cluster/loop.cljc:1205-1226`). The test explicitly blesses the loss
(`test/seon/ai_stream_fold_test.clj:117-124`).

Probe: valid prefix `(my.run/complete "safe`, malformed middle JSON, valid
suffix `")` returned:

```clojure
#:seon.ai{:text "(my.run/complete \"safe\")", :tokens 3}
```

The parser therefore produced a valid program that was not the byte sequence
the provider sent. Filed as
`docs/seon/issues/malformed-sse-data-can-change-agent-code.md`.

#### B2. `submit!!` can wait forever before its time limit begins

`submit!!` performs an unbounded `.get` on `flow/inject`; only afterward does it
start the timed dereference (`src/seon/flow.clj:479-499`). Pinned Flow injection
uses blocking `>!!` on the target channel
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:190-197`).
Current tests leave either compute or buffer capacity available
(`test/seon/flow_test.clj:271-335,387-446`).

A no-cluster falsifier used compute concurrency 1, queue depth 1, one latched
active task, and one buffered task. A third submission with a 20 ms limit was
still blocked after 120 ms and completed only after release, reporting a 128 ms
submission wait. Filed as
`docs/seon/issues/work-submission-can-block-before-its-time-limit.md`.

#### B3. Agent-authored renderers cannot enter the SCI program context

The settled rule says agent render and context code runs through the guarded
SCI door (`docs/prds/sci-execution-runtime/plan/README.md:1333-1352,1671-1682`).
The router instead does JVM-only `requiring-resolve` and direct invocation
(`src/seon/render.clj:282-305,331-382`); the live SCI program context is a
separate value (`src/seon/sci/eval.clj:1210-1233`).

A load-only probe defined `my.audit.renderer/render-ai` in SCI. Direct SCI
evaluation returned `"SCI-only"`; the router returned
`:seon.render/unresolvable`. The eventual repair must also remove the false
safety assumption around public unescaped `raw` HTML
(`src/seon/render/hiccup.clj:68-77`): ruling #20 permits every function call.
Filed as `docs/seon/issues/agent-renderers-never-enter-the-sci-program-context.md`.

#### Existing blockers remain real

This audit did not duplicate the following open notes:

- cold `acquire!` has no per-row containment
  (`src/seon/sci/eval.clj:978-1208`;
  `docs/seon/issues/acquire-has-no-per-row-containment.md`);
- agent evaluation still ignores assigned namespaces at its derivation sites
  (`src/seon/sci/eval.clj:229-236,1434-1436`;
  `docs/seon/issues/evals-ignore-the-agents-assigned-namespace.md`);
- a guarded-context re-arm throws before the totalizing boundary
  (`src/seon/sci/eval.clj:1427-1441`;
  `docs/seon/issues/sci-evaluate-throws-when-a-guarded-context-is-re-armed.md`);
- each armed agent still creates a platform-thread error fan-out
  (`src/seon/flow.clj:685-701`;
  `docs/seon/issues/armed-agent-holds-a-platform-thread.md`);
- new-cluster boot can consume a stale published source
  (`src/seon/cluster.clj:1317-1336`;
  `docs/seon/issues/new-cluster-boot-fails-on-a-stale-published-source.md`);
- `seon.db` still exposes only the small read facade while first-party code
  calls Datahike directly (`src/seon/db.clj:1-125`;
  `docs/seon/issues/seon-db-is-not-the-one-database-namespace.md`).

The ambient-connection defect was also present when this audit inspected
`src/seon/cluster/loop.cljc:1388-1402`, but a concurrent lane resolved it and
archived its note in commit `52803435d`; the evidence now lives at
`docs/seon/issues/archive/agent-evals-never-bind-the-ambient-cluster-connection.md`.

### Friction

#### F1. Debug pages manufacture liveness evidence

The ordinary page contract explicitly refuses to guess the live-process set
(`src/seon/render/web.clj:306-321`), but debug passes `#{}`
(`src/seon/render/web.clj:457-472`). `seon.problems/block` documents that this
invents wedged runs, while omission would render an honest missing-input card
(`src/seon/problems.clj:391-423`). Filed as
`docs/seon/issues/debug-pages-invent-wedged-runs.md` and marked in-flight.

#### F2. One AI test can green with zero assertions

`the-leaf-records-phase-from-the-jdks-own-taxonomy` makes a real network call
and wraps every assertion in a `when` matching one expected error kind
(`test/seon/ai_test.clj:658-671`). Any other outcome passes silently. Filed as
`docs/seon/issues/ai-transport-taxonomy-test-can-run-zero-assertions.md`.

#### F3. The reader owns a hidden 1 MiB production dial

The private `1048576` fallback claims no production callers
(`src/seon/sci/reader.cljc:7-10`), yet `read` applies it whenever callers omit a
bound (`src/seon/sci/reader.cljc:569-619`), which evaluation and reply parsing
do (`src/seon/sci/eval.clj:651-666`,
`src/seon/cluster/reply.cljc:115-134,272-281`). Filed as
`docs/seon/issues/sci-reader-hides-a-production-source-cap.md`.

#### F4. The render walk has a second connection registry

Forward refs derive from entity attributes and reverse refs from the installed
schema (`src/seon/render/walk.clj:194-268`). Two exceptional queries then enter
through the private `derived-edge-functions` vector
(`src/seon/render/walk.clj:270-325`). Filed as
`docs/seon/issues/render-walk-maintains-a-derived-edge-hand-list.md` and marked
in-flight.

#### F5. Eval drivers duplicate a four-minute-per-run clock

The terminal condition is already database-observable through `d/listen`
(`src/seon/eval/drive.clj:54-73`). Nevertheless, both the Inspect driver and
bootstrap driver invent `(or supplied (* run-cap 240000))`
(`src/seon/eval/drive.clj:320-328`,
`src/seon/bootstrap_drive.clj:375-393`). Filed as
`docs/seon/issues/eval-drives-duplicate-a-four-minute-run-clock.md`.

#### F6. Anonymous runtime contracts recurred

`config/effective` accepts `:any` then calls Datahike (`src/seon/config.cljc:257-277`),
and `flow/var-process` accepts `:any` then checks `var?`
(`src/seon/flow.clj:83-111`). This recurs after the archived boundary cleanup.
Filed as `docs/seon/issues/anonymous-runtime-contracts-have-recurred.md`.

#### F7. The operator still classifies processes by names and substrings

Current launch detection parses literal form fragments
(`script/seon/fresh_operator.clj:464-479`), a compatibility classifier preserves
four retired pod/CLJS roles (`:481-510`), and an OS-wide scan treats that roster
as Seon truth (`:586-608`). Tests positively preserve the legacy roles
(`test/seon/dev/fresh_operator_test.clj:397-449`). Filed as
`docs/seon/issues/operator-classifies-processes-by-command-substrings.md`.

#### F8. Operator subprocesses and two direct tests can wedge indefinitely

Offline roster and source initialization `slurp` child output before unbounded
`.waitFor` (`script/seon/fresh_operator.clj:746-756,1846-1853`); detached launch
has the same missing bounded handoff (`:1454-1470`). Browser/tail helpers also
wait unboundedly (`:2046-2063,2419-2439`). The only two direct dev-test waits
without backstops are `test/seon/dev/changed_test_test.clj:5-13` and
`test/seon/dev/edit_feedback_test.clj:11-21`. Filed as
`docs/seon/issues/operator-subprocesses-have-unbounded-read-and-wait-paths.md`.

#### F9. The bootstrap teaches bare map keys

The always-on example claims to teach honest durable contracts
(`resources/seon/bootstrap.edn:19-22`) but defines, corrects, and calls
`largest` using bare `:label`/`:amount` keys
(`resources/seon/bootstrap.edn:45-63`). Filed as
`docs/seon/issues/bootstrap-teaches-bare-map-keys.md`.

#### F10. Datahike transaction encoding has a readerless duplicate

`seon.schema.datahike` contains a projection-parameterized recursive codec
(`src/seon/schema/datahike.cljc:320-382`) and a second ambient implementation
(`:384-434`). Production calls only the ambient `encode-transaction`
(`src/seon/cluster/store.clj:460-475`); no source/test reader uses the explicit
surface. Filed as
`docs/seon/issues/schema-datahike-keeps-a-readerless-second-codec.md`.

#### F11. The standalone artifact has a store-ownership handoff gap

Artifact install opens, publishes, and releases the store
(`src/seon/artifact.clj:51-70`), then cluster start reacquires it later
(`src/seon/artifact.clj:72-97`, `src/seon/cluster.clj:1309-1329`). This is not a
demonstrated two-writer corruption path—the fence still excludes overlap—but a
competitor can make startup fail after installation. No artifact regression
owns the seam. Filed as
`docs/seon/issues/artifact-releases-the-fence-between-install-and-start.md`.

#### F12. Active dev feedback gates observe deleted owners

The edit hook still reads `logs/pod.log`, `SEON_CONFIG`, and retired
`:gate/:crash/:log` semantics, skipping cleanly when the log is absent
(`bin/seon-hook:833-902,967`). The Markdown floor rule targets deleted
`src/seon/agent/ctx.cljs`, converts absence to an empty n-gram set, and remains
enabled (`script/seon/dev/markdown.clj:598-648,705-713`). Filed together as the
one absence-as-health feedback class in
`docs/seon/issues/dev-feedback-gates-observe-deleted-owners.md`.

#### F13. Production docstrings and renderers teach deleted semantics

Agent-visible docs disagree about error shapes, shared SCI context, and run
closure. Decision 11 settles the comment boundary: `;`/`;;` remain an
input-side source-writing convention, while displayed forms, results,
docstrings, and notices never use comments as output. The remaining current
violations are `program-doc-var`'s `;`-prefixed docstrings
(`src/seon/sci/eval.clj:838-855`), transcript comment headers and elision
(`src/seon/render/transcript.clj:326-330,503-505`), effect notices
(`src/seon/effect.clj:618-634`), and walk error/state framing
(`src/seon/render.clj:375-377,404-408`). The reply parser's source-comment
construction (`src/seon/cluster/reply.clj:77-84,210-244`) is intentionally
unchanged and is not display authority. Other namespace docs still
sequence unbuilt work or call plan reduction a fold
(`src/seon/reconcile.cljc:5-12`, `src/seon/cluster/run.cljc:5-12`,
`src/seon/cluster/loop.cljc:1380-1387,1547-1585`). Filed as
`docs/seon/issues/production-docstrings-teach-deleted-semantics.md`; modified
owners are marked in-flight.

#### Existing friction is extensive but already scheduled

The audit independently confirmed these open classes and did not duplicate
them:

- render failures collapse to absence/silence
  (`src/seon/render.clj:282-305,364-382`,
  `src/seon/render/web.clj:775-797`;
  `docs/seon/issues/render-resolution-and-feed-swallow-failures.md`);
- static render functions remain beside the one walk
  (`src/seon/render/agent.clj:87-335`, `src/seon/context.clj:58-70`;
  `docs/seon/issues/static-render-blocks-survive-the-one-walk-cutover.md`);
- oversight turns a 20 ms ping absence into state
  (`src/seon/oversight.clj:34-39,87-142,188-194`;
  `docs/seon/issues/oversight-treats-a-20ms-ping-absence-as-state.md`);
- Flow retains prototype procs, a non-priority hand proc, and unsanctioned
  egress (`src/seon/flow.clj:314-357,717-960`;
  `docs/seon/issues/flow-prototype-procs-survive-beside-the-live-agent-graphs.md`,
  `docs/seon/issues/work-launcher-control-alts-lacks-priority.md`, and
  `docs/seon/issues/flow-has-no-read-set-control-and-a-hand-rolled-egress.md`);
- the public-contract census can prove zero subjects, and its scope omits 32
  uncontracted public functions across first-party tooling
  (`test/seon/public_contract_test.clj:73-81`; representative omissions at
  `build.clj:133-154`, `script/seon/dev/changed_test.clj:18-468`, and
  `script/seon/dev/state.clj:11-86`;
  `docs/seon/issues/public-contract-census-can-pass-with-no-subjects.md`);
- opaque Flow generators reuse one mutable sample, and the same class extends
  to SCI ctx, cluster server, and render server/mult generators
  (`src/seon/flow.clj:56-79`, `src/seon/sci/eval.clj:140-145`,
  `src/seon/cluster.clj:69-73`, `src/seon/render/web.clj:88-114`;
  `docs/seon/issues/flow-generators-reuse-one-mutable-sample.md`). A load-only
  probe returned `true` for identity of all four repeated samples.
- changed-test selection classifies by path, polls process exit, and can run
  the same JVM gate under two stale boundary names
  (`script/seon/dev/changed_test.clj:73-181,243-280,378-429`;
  `docs/seon/issues/changed-test-selector-classifies-hosts-by-path-prefix.md`
  and `docs/seon/issues/changed-test-process-cleanup-polls-observable-exit.md`).

### Cleanup

#### C1. Four `.cljc` files are JVM-only in fact

Unconditional JVM forms survive in `seon.ai`, `seon.config`,
`seon.cluster.loop`, and `seon.schema` (`src/seon/ai.cljc:67-71,429,607-611,689-690`,
`src/seon/config.cljc:104-119,179-184`,
`src/seon/cluster/loop.cljc:1360,1388-1389`,
`src/seon/schema.cljc:356-372`). A CLJS clj-kondo pass counted 19 platform
errors across exactly those four files. Schema also retains explicit retired
CLJS/compatibility paths (`src/seon/schema.cljc:1051-1092,2063-2160`). Filed as
`docs/seon/issues/fresh-cljc-files-are-jvm-only.md`.

#### C2. Duplicate-refusal evidence is nondeterministic

`seon.reconcile` chooses a duplicate from `(frequencies identities)`, so its
diagnostic follows hash-map iteration (`src/seon/reconcile.cljc:83-90`). A
12-identity probe received `:k8` although `:k0` was the first desired duplicate.
Filed as `docs/seon/issues/duplicate-identity-refusal-evidence-is-unordered.md`
and marked in-flight.

#### C3. Four operator helpers have only private test readers

Clj-kondo and a reference census agree that
`terminate-observed-process!`, `require-readable-process-records!`,
`assert-store-flock-free!`, and `delete-cluster-root-no-follow!` have no
production reader (`script/seon/fresh_operator.clj:1542-1552,2216-2223,2342-2384`).
Their only readers are private-Var tests
(`test/seon/dev/fresh_operator_test.clj:496,511,532,640,901,908,946`). Filed as
`docs/seon/issues/operator-private-helpers-have-only-test-readers.md`.

Existing cleanup remains scheduled for readerless schema rows, readerless
cluster export, Flow prototypes/monitor behavior, and render residue
(`docs/seon/issues/schema-population-retains-five-readerless-rows.md`,
`docs/seon/issues/cluster-export-is-implemented-without-a-runtime-reader.md`,
`docs/seon/issues/monitor-graph-command-proc-throws.md`, and
`docs/seon/issues/value-floor-residue-duplicate-cursors-and-marker-hand-lists.md`).

## Schema and test honesty

The consolidated `resources/seon/schema.edn` is substantive: a load-only probe
counted 680 packaged forms, all 680 admitted, producing 164 structural shapes,
344 indexed shape attributes, and 22 predicate registrations. The schema
admission code checks dishonest predicates and reference structure rather than
merely parsing EDN (`src/seon/schema/internal.cljc:111-165,305-336`,
`src/seon/schema.cljc:1900-1991`).

Contract coverage is strong inside production `src/`: every public function in
the three independently partitioned source audits carried Malli metadata. It is
not codebase-wide: the tooling census counted 41 public functions across 11
build/dev files, 32 without contracts; examples are `build.clj:133-154`,
`dev_cache.clj:230`, `script/seon/dev/issues.clj:52-159`, and
`script/seon/dev/test_roots.clj:90-126`. This is assigned to the existing
public-contract-census issue, not re-filed.

The test tree contains 85 `_test.clj[c]` files, 758 literal `deftest` forms, 69
generated `defparity` forms, and 51 `tc/quick-check` calls across 32 files.
These are exact syntax-aware census counts. The suite has real state-transition,
flock, no-follow deletion, admission, and event-listener tests
(`test/seon/cluster/run_test.clj:589-967`,
`test/seon/cluster/store_test.clj:297-390`,
`test/seon/sci/admit_test.clj:213-227`,
`test/seon/sci/eval_instrumentation_test.clj:15-121`). Its weak pockets align
with the findings above: zero-assert branches, shared mutable generator samples,
polling, and absence-as-health claims.

## Calibration: what is genuinely good

### Database, run, wake, and store

Run transitions are largely pure transaction-data functions with closed Malli
contracts (`src/seon/cluster/run.cljc:217-394,464-571`). Wake registration and
delivery derive from database attributes rather than a parallel durable queue
(`src/seon/cluster/wake.cljc:94-253`). Store opening acquires the flock before
existence or Datahike access and retains it through the returned value
(`src/seon/cluster/store.clj:280-338`); release keeps the fence when Datahike
release fails (`:340-356`). The child-JVM flock and symlink cleanup tests are
real, not mocked (`test/seon/cluster/store_test.clj:297-390`,
`test/seon/dev/fresh_operator_test.clj:617-654,891-949`).

### Source indexing and program facts

`seon.fn` and `seon.fn.analyzer` use exact spans, canonical ordering, stable
digests, and explicit fallback reasons (`src/seon/fn.clj:294-328,459-540`,
`src/seon/fn/analyzer.clj:84-167`). `seon.program` derives component facts and
replaces components exactly instead of accumulating stale children
(`src/seon/program.cljc:278-344,420-475`). This was the cleanest audited
subsystem.

### SCI admission and printing

`seon.sci.admit` is one bounded traversal with interrupt checks and flat error
values (`src/seon/sci/admit.clj:107-248,402-548`); its property deliberately
constructs hostile partitions (`test/seon/sci/admit_test.clj:213-227`).
`seon.print` is one admitted-value grammar with invocation-local sinks rather
than a parallel printer (`src/seon/print.cljc:219-402,674-821`).

### Render serialization and delivery

The render system has good local engineering despite its missing SCI boundary:
one data route table (`src/seon/render/route.clj:5-55`), a strict deterministic
Hiccup grammar (`src/seon/render/hiccup.clj:89-180,371-508`), byte-based
equality suppression (`src/seon/render/web.clj:474-513`), and http-kit's actual
drain event rather than a sleep for socket backpressure
(`src/seon/render/web.clj:726-752`). `seon.problems/problems` itself is a strong
derive-don't-store function (`src/seon/problems.clj:264-301`); its debug caller
is the liar.

### Operator safety

The operator's exact `(pid, start-instant, generation)` records are careful
(`script/seon/fresh_operator.clj:1130-1230,1488-1552`), and recursive deletion
checks root containment and refuses symlink traversal
(`script/seon/fresh_operator.clj:2346-2400`). `seon.dev.state` performs temp
write, fsync, atomic move, and parent sync
(`script/seon/dev/state.clj:40-85`). `bin/test` fails closed when it selects no
namespaces and runs explicit selections in one JVM (`bin/test:84-145`).

## Rough-versus-clean verdict by area

| Area | Verdict | Evidence-backed reason |
|---|---|---|
| Cluster runtime | **Mixed, leaning solid** | Pure run/wake/store transitions are strong (`src/seon/cluster/run.cljc:217-571`, `src/seon/cluster/store.clj:280-356`); large call/resume kernels and existing connection/namespace/session-image defects remain (`src/seon/cluster/loop.cljc:1127-1576`). |
| SCI eval | **Rough but principled** | Shared context, admission, and instrumentation are coherent (`src/seon/sci/eval.clj:71-92,1210-1233`, `test/seon/sci/eval_instrumentation_test.clj:15-121`); cold acquisition, namespace, re-arm, hidden-cap, and render-invocation seams are not settled (`src/seon/sci/eval.clj:978-1208,1427-1441`). |
| Render | **Roughest area** | Serializer, routes, byte suppression, and drain backpressure are good (`src/seon/render/hiccup.clj:371-508`, `src/seon/render/web.clj:474-513,726-752`); the settled SCI execution boundary is absent, debug invents liveness, failures disappear, and second registries/residue survive (`src/seon/render.clj:282-382`, `src/seon/render/web.clj:457-472`). |
| DB/schema | **Mixed, structurally good** | The 680-form population admits and source/program models are deterministic (`src/seon/schema.cljc:1900-1991`, `src/seon/program.cljc:278-475`); anonymous contracts, a duplicate codec, compatibility residue, and already-filed lifecycle/index issues keep it from clean (`src/seon/schema/datahike.cljc:320-434`). |
| AI | **Mixed/rough** | Settings/request construction and evidence-derived retry are data-oriented (`src/seon/ai.cljc:103-241,628-669`); malformed stream deletion is a correctness blocker and its test blesses the loss (`src/seon/ai.cljc:403-458`, `test/seon/ai_stream_fold_test.clj:117-124`). |
| Flow plumbing | **Rough** | Workloads and finite buffers are explicit (`src/seon/flow.clj:83-115,362-444`); pre-accept submission can wedge, each agent retains a platform thread, and hand-rolled control/egress plus prototypes remain (`src/seon/flow.clj:314-357,479-520,685-960`). |
| Operator scripts | **Rough around a strong safety core** | Exact process identity, flocking, and no-follow deletion are good (`script/seon/fresh_operator.clj:1130-1230,2320-2400`); substring classifiers, legacy roles, unbounded subprocesses, dead helpers, and stale hook gates are live (`script/seon/fresh_operator.clj:464-608,746-756,1846-1853`, `bin/seon-hook:833-902`). |
| Tests | **Broad and useful, not uniformly trustworthy** | Real database/Flow/process properties exist (`test/seon/cluster/run_test.clj:589-967`, `test/seon/cluster/store_test.clj:297-390`); zero-assert branches, shared mutable samples, polling, and exact tests of false liveness/prose remain (`test/seon/ai_test.clj:658-671`, `test/seon/public_contract_test.clj:73-111`). |

## Bottom line

The codebase is rough enough that the three new blockers and the existing
blocker set must be treated as real system constraints, not cleanup debt. It is
clean enough that a rewrite would destroy good work: the right move is the
project's stated one—strengthen the surviving owner, delete second mechanisms,
and make every integration boundary fail as data with evidence. The measured
answer to “how rough is it actually?” is: **the nucleus is good; the seams are
still dangerous; production-readiness is not an honest claim yet.**
