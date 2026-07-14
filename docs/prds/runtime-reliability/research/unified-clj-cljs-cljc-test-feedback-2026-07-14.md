---
type: research
status: completed
tags: [research, prd, flow, agent]
---

# Unified CLJ, CLJS, and CLJC test feedback (2026-07-14)

## TL;DR

Extend the mechanism that now exists; do not build another one.

`bin/seon-hook` should continue to normalize Claude `Edit`/`Write` and Codex
`apply_patch`, parse every touched Clojure file, and call one public operation:
`bin/seon test changed --path ...`. Broaden the existing
`seon.dev.changed-test` operation from “one Shadow selection and one Node run”
to “one decision containing zero or more existing boundary runs”:

- `bin/test-cljs` for the pod;
- `bin/test-writer` for the database server; and
- `seon.dev.test-runner` for the Babashka operator.

Tests are always advisory. A failed, timed-out, or unavailable test run must
return `PostToolUse.additionalContext` with `:continue true`; it must never
become an edit decision. The one permitted code gate is parse-all of the
resulting file. For Claude payloads it can gate before the write because the
prospective content is available. For a Codex multi-file patch, the smallest
safe first implementation parses every resulting file after the write, skips
tests if any file is malformed, and returns blocking/actionable syntax
feedback without attempting a second patch engine or an automatic revert.

Use each platform's actual graph:

- Shadow's successful `:test` build remains the sole CLJS dependency
  authority, including `.cljc`, macro requirements, and used-var namespaces.
- A bounded host-only clj-kondo analysis supplies `.clj` and the CLJ half of
  `.cljc` namespace definitions/usages. It is not a runner, is never used to
  reconstruct the CLJS graph, and widens to the complete relevant host gate if
  absent or ambiguous.
- The runtime database remains the authority only for agent-authored code.
  The current exact analyzer diff should keep running a newly defined test.
  A changed function must not pretend that the current database graph proves
  test impact; namespace/all-agent-test widening remains correct until the
  self-host analyzer emits explicit resolved-reference facts plus limitations.

For `.cljc`, query both authorities and union their selections. “No host test”
is valid only when the host graph and explicit runner roots prove no host test
depends on that namespace; it must be recorded, not silently treated as CLJS.

The first implementation should stay deliberately small: recompute the host
namespace graph per request (the audited scan is sub-second), execute selected
boundaries sequentially without stopping after the first failure, retain full
logs plus one EDN report, and return a token-bounded two-failure summary. Add no
daemon, database notification, test registry, debounce, function call graph,
or fourth runner.

## Scope and evidence

This audit read the two preceding test-feedback studies, the old edit-parser
study, the current hook declarations and implementation, the managed process
graph, immutable Shadow artifact publisher, changed-test selector, all three
test doors, their current tests, and the relevant vendored Shadow and
clj-kondo source. No production code was changed.

Important current facts:

- Both tool clients declare the same direct `PreToolUse` and `PostToolUse`
  adapter for `apply_patch|Edit|Write` (`.codex/hooks.json:3-23` and
  `.claude/settings.json:3-23`). The command spelling differs appropriately:
  Codex uses checkout-relative `./bin/seon-hook`; Claude uses its project-root
  variable.
- The hook already extracts every add/update/delete/move path, canonicalizes
  checkout containment, and supports multi-file Codex patches
  (`bin/seon-hook:88-138`).
- Edamame already uses `parse-string-all`, reader conditionals, both platform
  features, permissive aliases, and reader tags (`bin/seon-hook:144-157`).
- The changed-test hook currently filters to only `.cljs` and `.cljc`; `.clj`
  never reaches the operation (`bin/seon-hook:293-317`).
- The managed Shadow JVM already watches both `client` and `test`, and
  readiness requires a successful latest result for both
  (`script/seon/dev/process.clj:123-136,263-272`).
- The successful Shadow flush publishes an immutable content-addressed bundle
  and an atomic manifest (`script/seon/dev/test_artifact.clj:152-240`). Its
  resource rows already union ordinary requires, macro requires, and compiler
  used-var namespaces (`script/seon/dev/test_artifact.clj:49-64`).
- The current changed-test operation is still CLJS-only. It treats every
  `.cljc` or `.clj` as a broad pod input, selects only Shadow test namespaces,
  and launches only Node (`script/seon/dev/changed_test.clj:55-64,102-138,
  169-223`).
- `bin/test-writer` already accepts exact retained test namespaces
  (`bin/test-writer:9-33`). The operator gate is the only boundary that does
  not yet accept a selector (`script/seon/dev/cli.clj:325-353`).
- Shadow itself says its used-var information improves namespace invalidation
  but cannot identify which individual vars changed
  (`reference-code/shadow-cljs/src/main/shadow/build/api.clj:325-379`). Exact
  automatic platform test-var selection is therefore still unjustified.
- Active Babashka and writer bases do not include tools.namespace or
  clj-reload. clj-kondo 2025.04.07 is installed and its analysis API emits
  namespace definitions/usages with a `:lang` field for each `.cljc` branch
  (`reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:116-146`). A
  `--skip-lint` namespace-only probe of two `.cljc` files took 185 ms; the
  complete 37-file retained writer/source probe took 618 ms. This is cheap
  enough to derive, not cache, initially.

The current immutable manifest observed 246 first-party compiler resources:
20 `.cljc` and zero `.clj`. That zero is expected: `.clj` macro namespaces are
host compiler inputs, while their consumers carry macro-require edges in the
Shadow rows. The host analyzer must map a changed `.clj` path to its namespace;
the existing Shadow graph then finds its CLJS consumers. It must not invent a
second CLJS graph.

## One decision, three existing execution adapters

The owning operation remains `seon.dev.changed-test/run-changed!`. Change it in
place so the flow is:

```text
normalized changed paths
        |
        +--> parse every resulting .clj/.cljs/.cljc file
        |      malformed -> syntax feedback; no tests
        |
        +--> snapshot exact Shadow manifest
        +--> derive host CLJ namespace facts with clj-kondo
        |
        +--> pure selection fold
        |      pod selectors
        |      database selectors
        |      operator selectors
        |      explicit widening / no-covered-test reasons
        |
        +--> existing runner adapters (attempt every selected boundary)
        |
        +--> full logs + one EDN report + short advisory result
        |
        +--> PostToolUse.additionalContext
```

The hook owns payload adaptation and delivery only. `run-changed!` owns
freshness, selection, widening, execution deadlines, reports, and formatting.
The boundary commands continue to own actual test semantics. Manual focused
doors and complete checkpoint gates stay unchanged.

Do not add Shadow `:autorun`: the vendored implementation launches the whole
bundle synchronously and contains an unresolved child-termination FIXME. Do
not run platform tests inside the live pod. Every pod selection still gets a
fresh bounded Node process against one immutable artifact.

## Graph authorities

| Corpus and platform | Authority | What it may prove | What it must not claim |
|---|---|---|---|
| Platform CLJS | Successful Shadow `:test` manifest | Resource/namespace reverse dependents, macro consumers, test namespaces | Changed-function to exact test-var impact |
| Platform CLJ | Fresh host namespace analysis scoped to active source and explicit runner roots | Path to namespace, CLJ requires, reverse-dependent retained test namespaces | Dynamic calls, exact function impact, CLJS dependencies |
| Platform CLJC | Union of Shadow CLJS result and host CLJ result | Both compiled branches and each branch's test roots | That the Shadow half covers the host half |
| Agent-authored CLJS | Self-host analyzer plus database program facts | Newly defined test identity today; namespace requirements today | Complete existing test-to-function calls today |

clj-kondo is the smallest host source because it already understands namespace
forms and emits separate `:clj`/`:cljs` rows for `.cljc`. Invoke namespace
analysis with lint findings disabled; lint warnings are a different concern.
Pin/check the supported analyzer version in the operator prerequisites when
implemented. If the executable is missing, returns malformed data, reports
duplicate first-party namespace ownership, or cannot map an executable path,
the selector widens. Correctness must not depend on clj-kondo being installed.

Only analyze `.clj` plus the `:clj` projection of `.cljc` for host selection.
Do not ingest `.cljs` rows with no language tag as host facts. Explicit runner
root lists, not a `-test` suffix, determine tests: the production namespace
`seon.dev.changed-test` itself ends in `-test`, proving suffix classification
is unsafe.

The host graph is an ephemeral content-addressed input to one decision. Do not
persist it in the application database, run a watcher for it, or cache it until
measurement shows the sub-second scan matters.

## Selection algorithm

1. Canonicalize and deduplicate every source and destination path. Reject a
   mixed inside/outside-checkout patch exactly as the hook does now.
2. Parse every resulting `.clj`, `.cljs`, and `.cljc` file. Deleted paths have
   no content to parse but remain selection inputs. For `.cljc`, retain both
   language projections for the later graph step.
3. Obtain the latest successful Shadow manifest matching the changed compiler
   inputs. Wait within the current bounded deadline. Never run a stale bundle.
4. Derive the host namespace facts from active first-party source plus the
   exact retained writer and operator test roots.
5. Seed each graph by changed namespace, not by filename convention. A changed
   test file selects its explicit root directly.
6. Walk reverse-transitive namespace dependents and intersect with that
   boundary's explicit test roots. Record a short dependency chain/reason for
   every selection.
7. Apply boundary-specific widening. An unknown executable path can never
   become an unexplained `:no-affected-tests`.
8. Execute every selected boundary, even if an earlier one fails. Sequential
   execution is the smallest robust first version; parallelism is a measured
   later optimization.
9. Aggregate statuses without changing hook continuation. Preserve each full
   transcript and write one machine-readable decision report.

### CLJS and CLJ macro selection

For `.cljs` and the CLJS half of `.cljc`, keep the current Shadow
reverse-transitive algorithm. For `.clj`, use host analysis only to recover the
namespace. If that namespace appears in any Shadow resource's exported
requires set, it is a macro/compiler dependency; seed the same Shadow reverse
walk from that namespace. Thus an edit to `src/seon/indexing.clj` reaches the
tests compiled through `seon.client` without a second CLJS parser.

If the watcher is unavailable or the exact manifest does not arrive, use the
existing one-shot `bin/test-cljs` fallback. With no trustworthy graph, run the
full pod gate; for an explicitly changed known test namespace, the exact test
namespace is safe. A compiler failure is `build-unavailable`/`compile-failed`
feedback, never permission to use the previous manifest.

### Focused writer selection

The database roots are exactly the eleven namespaces owned by
`bin/test-writer`, not every historical JVM test. A source namespace change
selects retained test roots in its host reverse closure. The audit produced
these representative results:

| Changed source | Focused retained writer tests |
|---|---|
| `seon.embed` | `seon.embed-writer-test` |
| `seon.db.writer` | generated-id, replay, request-receipt, writer-integration |
| `seon.db.registry` | generated-id, registry-routing, registry, replay, request-receipt, writer-integration |
| `seon.db.backend` | backend plus the six downstream registry/writer suites |
| `seon.db.protocol` | eight protocol/id/registry/writer/transport suites |
| `seon.schema` or `seon.schema.internal` | all eleven retained writer suites |

The implementation should consume the runner's explicit roots through one
machine-readable list operation or shared data owner; do not duplicate the
eleven names inside `changed_test.clj`.

Widen to the full writer gate when:

- namespace analysis is absent, stale, ambiguous, or cannot map a writer path;
- a writer entrypoint such as `seon.db.server` or preflight source has no
  covered dependent test (also report the coverage gap; the full gate does not
  magically add missing coverage);
- a writer source/test is deleted or moved and the prior identity is unknown;
- `deps.edn`, writer aliases, build/uber inputs, `bin/test-writer`, protocol
  generation, or retained-root data changes; or
- a `.cljc` host branch reaches the writer closure but its exact test closure
  is uncertain.

### Focused operator selection

Let the existing `seon.dev.test-runner` accept explicit test namespace
selectors; this is an extension of the current runner, not a new harness. Use
its explicit root list as authority and exclude its synthetic “require every
test” loading edges from impact analysis.

Representative host closures are small and useful:

- `seon.dev.docstring` -> `seon.dev.docstring-test`;
- `seon.dev.markdown` -> `seon.dev.markdown-test`;
- `seon.dev.process` -> `seon.dev.process-test` plus `seon.dev.cli-test`;
- `seon.dev.changed-test` -> `seon.dev.changed-test-test` plus
  `seon.dev.cli-test`; and
- `seon.dev.test-artifact` -> artifact, changed-test, and CLI tests.

`bin/seon-hook` has no namespace row, so give that adapter an explicit owner
edge to `seon.dev.hook-cli-test`. Widen to the full operator gate for its hook
declarations/config, entry scripts, runner/root-list changes, ambiguous paths,
or shared lifecycle/config/state changes whose graph is unavailable.

### CLJC rule

A `.cljc` request always produces two recorded decisions:

- Shadow selection for `:seon.dev.changed-test.language/cljs`; and
- host selection for `:seon.dev.changed-test.language/clj`.

Union boundary executions after selection. A shared database protocol file can
therefore select pod tests and writer tests in the same advisory operation.
If one graph is missing, widen only that platform's relevant boundary while
retaining the other platform's exact result. A proven lack of host test roots
is recorded as `:seon.dev.changed-test.reason/no-covered-host-test`; it is not
silently dropped.

## Widening matrix

| Change | Minimum result |
|---|---|
| Known `.cljs` source | Shadow affected pod namespaces |
| Known `.cljs` test | Exact pod test namespace |
| Known `.clj` writer source | Host affected retained writer namespaces |
| Known `.clj` operator source | Host affected operator namespaces |
| `.clj` macro consumed by CLJS | Shadow macro-consumer closure plus applicable host selection |
| `.cljc` | Union of Shadow and host selections; explicit result for both halves |
| Deleted/moved executable file | Prior/current mapping when known, otherwise full relevant boundary |
| `shadow-cljs.edn`, package/dependency input, test artifact publisher, `bin/test-cljs` | Full pod plus operator ownership tests |
| Writer alias/build input or `bin/test-writer` | Full writer plus operator ownership tests |
| `bb.edn`, hook scripts/config | Full operator; actual hook-dispatch proof for hook definition changes |
| Global config/protocol source with uncertain ownership | Full relevant boundaries, up to `test all` |
| Markdown/non-executable doc | No tests with an explicit non-executable reason; keep Markdown feedback |
| Unknown executable path | Never quiet; full relevant boundaries or all three when ownership is unknowable |

## Concrete data contract

Keep the existing `:seon.dev.changed-test/*` owner and expand it. Do not create
a parallel `test-impact-v2` namespace. A decision should have this shape:

```clojure
{:seon.dev.changed-test/request-id #uuid "00000000-0000-0000-0000-000000000000"
 :seon.dev.changed-test/paths ["src/seon/db/protocol.cljc"]
 :seon.dev.changed-test/origin :seon.dev.changed-test.origin/edit-hook
 :seon.dev.changed-test/advisory? true
 :seon.dev.changed-test/graph-snapshots
 [{:seon.dev.changed-test/authority :seon.dev.changed-test.authority/shadow
   :seon.dev.changed-test/language :seon.dev.changed-test.language/cljs
   :seon.dev.changed-test/digest "shadow-digest"
   :seon.dev.changed-test/complete? true}
  {:seon.dev.changed-test/authority :seon.dev.changed-test.authority/clj-kondo
   :seon.dev.changed-test/language :seon.dev.changed-test.language/clj
   :seon.dev.changed-test/digest "host-source-digest"
   :seon.dev.changed-test/complete? true}]
 :seon.dev.changed-test/selections
 [{:seon.dev.changed-test/boundary :seon.dev.changed-test.boundary/pod
   :seon.dev.changed-test/runner :seon.dev.changed-test.runner/test-cljs
   :seon.dev.changed-test/selectors ['seon.db.envelope-test]
   :seon.dev.changed-test/widened? false
   :seon.dev.changed-test/reasons
   [{:seon.dev.changed-test/reason
     :seon.dev.changed-test.reason/reverse-namespace-dependent
     :seon.dev.changed-test/path "src/seon/db/protocol.cljc"
     :seon.dev.changed-test/dependency-chain
     ['seon.db.protocol 'seon.db 'seon.db.envelope-test]}]}
  {:seon.dev.changed-test/boundary :seon.dev.changed-test.boundary/database
   :seon.dev.changed-test/runner :seon.dev.changed-test.runner/test-writer
   :seon.dev.changed-test/selectors ['seon.db.writer-integration-test]
   :seon.dev.changed-test/widened? false
   :seon.dev.changed-test/reasons
   [{:seon.dev.changed-test/reason
     :seon.dev.changed-test.reason/reverse-namespace-dependent
     :seon.dev.changed-test/path "src/seon/db/protocol.cljc"
     :seon.dev.changed-test/dependency-chain
     ['seon.db.protocol 'seon.db.writer 'seon.db.writer-integration-test]}]}]}
```

Each boundary result extends that decision with fully namespaced data:

```clojure
{:seon.dev.changed-test/boundary :seon.dev.changed-test.boundary/database
 :seon.dev.changed-test/status :seon.dev.changed-test.status/failed
 :seon.dev.changed-test/exit 1
 :seon.dev.changed-test/summary "Ran 4 tests containing 31 assertions."
 :seon.dev.changed-test/counts "1 failures, 0 errors."
 :seon.dev.changed-test/failures
 ["FAIL in (replay-is-bounded) ...\nexpected: ...\nactual: ..."]
 :seon.dev.changed-test/log "tmp/test-changed/<id>/database.log"
 :seon.dev.changed-test/report "tmp/test-changed/<id>/report.edn"}
```

The combined report retains graph digests, selections, reasons, commands,
deadlines, exits, summaries, and full-log paths. Keep a bounded tail (the
current 20-decision policy is adequate) and a stable latest report pointer.
This is ephemeral operator evidence, never a database entity or notification.

## Short feedback without lost evidence

The hook response should contain, in order:

1. `ADVISORY — edit accepted; tests never gate refactoring.`
2. one line per attempted boundary with status and selected namespace count;
3. at most two failure blocks across all boundaries;
4. an explicit widening/build-unavailable/coverage-gap line; and
5. the combined report path.

Full output always stays in boundary logs. Do not splice stderr and stdout
without a separator, and do not discard a boundary merely because another one
failed.

The current hook caps feedback with `(count message)` and configures
`:feedback {:max-length 1000}` (`bin/seon-hook:325-334` and
`.claude/seon-hook.edn:20-21`). That violates Seon's token-reporting rule.
Replace it with a token budget using the canonical `seon.ai.tokens/estimate`
from the existing `.cljc` namespace. The human-facing config and result should
say `:seon.dev.changed-test/max-tokens`, never characters or bytes.

## Agent-authored function and test indexing

Do not import the platform test graph into the database. Platform code is
compiled by Shadow/the JVM and its manifests are filesystem/operator facts.
Agent-authored code is self-host compiled and its durable program facts belong
in the database. These are different corpora sharing one selection vocabulary,
not competing authorities.

Today the runtime does two honest things:

- detects a newly defined test from the exact analyzer diff
  (`src/seon/eval.cljs:1831-1843`); and
- runs exactly those vars after their program facts commit
  (`src/seon/eval.cljs:4260-4289`).

It also deliberately stores test identity, namespace, and source without
parsing source as a dependency index (`src/seon/eval.cljs:2376-2400`). Preserve
that.

For a changed agent function, the smallest conservative next behavior is:

1. identify the changed function and owning namespace from the exact analyzer
   diff;
2. run agent tests in that namespace and reverse-dependent agent namespaces
   from the existing durable `:seon.ns/require-edges`;
3. if namespace edges are incomplete/dynamic, run all agent-authored tests in
   the cluster; and
4. execute through the existing `seon.test.runner/run!`, which already accepts
   exact vars or one namespace (`src/seon/test/runner.cljs:760-806`).

Later, the self-host analyzer may add inclusion facts such as
`:seon.test/uses-fn` refs and `:seon.fn/calls-fn` refs while teeing a definition.
Those facts can add exact tests to the selection. They must also carry explicit
analysis limitations; resolved references do not prove the absence of dynamic,
higher-order, protocol, macro-generated, or runtime-resolved calls. Exact
function-level exclusion is allowed only when a corpus/mutation test proves a
complete analyzer mode. Until then, reference facts add tests and namespace/all
widening supplies safety.

If a second runtime caller becomes real, extract only the pure graph-closure
and reason-building fold into one genuinely shared `.cljc` namespace. Do not
preemptively duplicate the host process runner, Shadow reader, or runtime DB
queries behind a false abstraction.

## Hook trust and proof

Trust comes from a small reviewable adapter plus mechanical observation, not
from assuming that a restart ran it:

- the direct Babashka hook derives its own checkout root, proves containment,
  and has no live Seon/nREPL dependency;
- Codex trust remains the user's per-handler hash approval; never commit or
  bypass it;
- changing `.codex/hooks.json` correctly invalidates the prior hash;
- restarting reloads hook configuration but is not proof of dispatch; and
- the acceptance proof is one real Codex `apply_patch` whose unique path and
  active session appear in `logs/hook-debug.log` and whose
  `additionalContext` reaches that same turn.

Manual stdin fixtures prove parser behavior, not Codex dispatch. Keep both
levels of proof.

## Smallest ordered implementation plan

### Stage 1 — broaden the current operation

- Change the hook's executable-path filter from `.cljs|.cljc` to
  `.clj|.cljs|.cljc`.
- Make post-edit parse-all cover every touched resulting file before invoking
  tests; malformed files skip tests and use the syntax-gate response.
- Generalize `run-changed!` to return `selections` and `results` by boundary.
- Add focused selectors to the existing operator runner.
- Expose writer/operator root lists from their owning runners instead of
  copying them into the selector.

Exit: exact changed `.clj` test files can invoke writer/operator tests through
the same advisory hook path.

### Stage 2 — host namespace impact

- Add bounded clj-kondo namespace-only analysis for `.clj` and CLJ `.cljc`.
- Join `.clj` macro namespace identity into Shadow's existing macro edges.
- Implement writer/operator reverse closures and the widening matrix.
- Make `.cljc` mechanically emit both platform decisions.
- Fall back to full relevant gates whenever host analysis is unavailable.

Exit: source `.clj`, macro `.clj`, and shared `.cljc` fixtures select the
expected boundary union with reasons and no quiet unknowns.

### Stage 3 — evidence and fallback

- Capture full logs for all three adapters and one namespaced EDN report.
- Bound children/process groups and attempt every selected boundary.
- Add the one-shot full-pod fallback when the managed Shadow manifest is
  unavailable or stale.
- Replace character caps with the canonical token estimate.
- Keep `bin/seon test changed` advisory; keep `test all` as the failing
  checkpoint gate.

Exit: failures, timeouts, compiler failures, and missing host tooling all reach
the editing agent concisely while preserving full evidence.

### Stage 4 — runtime agent selection, separately proven

- Add namespace-level reruns for changed agent functions using durable require
  edges and all-agent-test widening.
- Prototype analyzer-resolved reference facts as inclusion-only evidence.
- Adopt exact function-to-test exclusion only after mutation/corpus proof.

Exit: agent function updates trigger useful tests without claiming platform or
dynamic-call completeness.

## Acceptance matrix

| Scenario | Required observation |
|---|---|
| Claude malformed Write/Edit | PreToolUse parse-all blocks before write |
| Codex malformed multi-file patch | every resulting Clojure file named; tests skipped; no auto-revert |
| Passing/failing test | hook response remains `continue true` |
| Changed `.cljs` source/test | Shadow closure/exact test; fresh Node; immutable artifact |
| Changed `.clj` writer source/test | focused retained writer roots with reasons |
| Changed `.clj` operator source/test | focused existing operator roots with reasons |
| Changed `.clj` macro | Shadow macro-consumer tests selected |
| Changed `.cljc` protocol | both CLJS and host decisions recorded and run |
| Host analyzer missing | full relevant host gate and explicit widening |
| Shadow manifest stale/failed | no stale artifact; bounded one-shot/full fallback or clear build failure |
| Deletion/move | source and destination considered; unknown identity widens |
| Multiple boundaries | every selected boundary attempted despite an earlier failure |
| Failure output | two short actionable excerpts plus full log/report paths |
| Feedback budget | measured/reported in canonical estimated tokens |
| Hook restart/trust | real `apply_patch` log entry and same-turn additional context |
| New agent deftest | exact analyzer-diff var runs as today |
| Changed agent function | namespace/all-agent widening unless complete facts are proven |

## Bottom line

The rational reusable system is not a universal guessed call graph. It is one
namespaced decision operation that asks each real compiler/runtime authority
for the facts it actually owns, widens whenever those facts stop, and delegates
to the three existing runners. That is small enough to ship now, conservative
for `.clj`, `.cljs`, and both halves of `.cljc`, and compatible with later
agent-authored function indexing without lying about completeness.
