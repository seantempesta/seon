---
type: research
status: completed
tags: [research, prd, flow, agent]
---

# Test impact selection and runner audit (2026-07-14)

## TL;DR

Seon's focused CLJS test latency is mostly compiler-process startup, not test
execution. A four-test namespace took 10.42 seconds with a one-shot Shadow JVM
and 0.82 seconds from the exact current bundle. A 48-test database namespace
took 12.22 seconds with compilation and 1.84 seconds from the current bundle.
The right first optimization is therefore not another test harness and not a
speculative function call graph. It is to let the existing managed Shadow JVM
maintain the complete `:test` artifact, keep each actual test invocation in a
fresh Node process, and make `bin/test-cljs` understand that a current complete
bundle covers any namespace or var subset.

The dependable impact selector is also narrower than the requested ideal:

- changed resource -> reverse-transitive Shadow namespace dependents -> test
  namespaces;
- exact changed test namespace when the changed resource is itself a test;
- explicit, machine-readable widening for macros, shared CLJC, configuration,
  dependencies, runner changes, deletions, missing compiler state, or any
  unknown edge; and
- exact qualified test vars only when selected manually or identified from
  compiler/analyzer structure, never by searching source text.

The runtime database graph cannot currently prove function-to-test impact. It
contains test identity and ownership, function identity and ownership,
namespace require edges, function schema references, and some database-read
attributes. It does not contain test-body-to-var or function-body-to-var call
edges, and platform tests are intentionally absent from the boot database.
Using it as if it were complete would create quiet false negatives. The Shadow
compiler graph is the authority for platform build/test impact; the database
graph remains the authority for agent-authored runtime facts.

The current suite also has a real isolation defect. A focused
`seon.client.extra-core-test` run failed after its first test replayed a database
with no schema facts and replaced the process-global Malli projection. The next
test then could not resolve `:seon.config/manifest`. In the full run, that
broken global state contaminated every later namespace. This is not an
argument for one Node process per namespace. It is evidence that shared global
test fixtures must restore the schema projection, ambient database connection,
and other replaced roots after every test on both Promise rails.

Keep the current runner boundaries:

- `bin/test-cljs` for platform CLJS tests;
- `bin/test-writer` for the JVM database server;
- `seon.dev.test-runner` for the small Babashka operator gate;
- `seon.test.runner` for agent-authored tests inside the runtime; and
- `bin/seon test` as the public delegator, not another runner.

## Scope and method

This audit inspected the active scripts, Shadow configuration, tests, runtime
program graph, and vendored library source. It also ran focused and complete
CLJS gates. No production code, runner, or test was changed.

Sources read:

- `bin/test-cljs`, `bin/test-writer`, `bin/test-parser`, `bin/seon`,
  `script/seon/dev/cli.clj`, `script/seon/dev/process.clj`, `deps.edn`, and
  `shadow-cljs.edn`;
- `src/seon/agent.cljs`, `src/seon/analyzer_info.cljs`, `src/seon/eval.cljs`,
  `src/seon/schema.cljc`, and `src/seon/test/runner.cljs`;
- active test namespaces and the prior 2026-07-12 suite audit;
- Shadow's node-test target, runner, build graph, resolver, and watcher source;
- `cljs.test`, test.check, Malli generator/check, and Kaocha source under
  `reference-code/`.

The Cognitect JVM test runner is referenced by vendored projects but is not
vendored as a source tree here. It is not relevant to the CLJS compile/runtime
cost in any case. Kaocha is vendored and was inspected, but adopting it would
add a CLJ-oriented third platform runner without removing Shadow's CLJS build.

The measurements below are observations from this checkout on 2026-07-14.
Everything under “Target design” is a proposal until implemented and
remeasured.

## Proven current behavior

### Focused latency is compiler startup

| Command | Shadow/build | Node | Total | Result |
|---|---:|---:|---:|---|
| `bin/test-cljs --test=seon.agent.schedule-test` | script reported 10 s | under 1 s | 10.42 s | 4 tests, 22 assertions, pass |
| same command with `--no-build` | skipped | under 1 s | 0.82 s | same result |
| `bin/test-cljs --test=seon.db-test` | script reported 10 s | about 2 s | 12.22 s | 48 tests, 342 assertions, pass |
| same command with `--no-build` | skipped | about 2 s | 1.84 s | same result |

The focused compile still resolved 394 or 434 source files and launched a new
JVM with the `:cljs` heap policy. Runtime selection is already efficient:
Shadow accepts namespace and qualified-var selectors and filters actual test
Vars by metadata (`reference-code/shadow-cljs/src/main/shadow/test/node.cljs:20-56`).
The dominant repeated cost is the fresh Shadow process and compile/flush.

`bin/test-cljs:157-189` also computes a content digest over the complete source,
test, config, and dependency tree for every invocation. That is defensible for
staleness, but the artifact fingerprint includes the exact selected namespace
set. Consequently, a current complete bundle cannot be reused for a smaller
selection even though it contains that selection.

### The current full run is incomplete and exposes two distinct bugs

A full `bin/test-cljs` invocation compiled 116 test namespaces in about 12
seconds, then ran for about 106 seconds and stopped after starting 49
namespaces. It emitted no final summary and correctly failed rather than
reporting a false green. The transcript is
`tmp/test-cljs-20260714-082731-86132.log`.

The failure is reproducible in the focused namespace. Running only
`seon.client.extra-core-test` took 20.65 seconds and ended with three tests, 17
assertions, and one error. Its first async test calls
`client/replay-program-graph!` against a database containing function and
namespace rows but no complete schema projection. The replay boundary calls
`schema/activate-projection!` (`src/seon/client.cljs:957`) and replaces the
process-global Malli default. The later `core-overlap-dedups-silently` test
then fails resolving `:seon.config/manifest`; the focused transcript is
`tmp/test-cljs-20260714-083608-9120.log`. The full run carries that state into
later provider, config, context, provenance, and database tests. This is a
namespace-local fixture leak that becomes suite-wide contamination.

The same incomplete full transcript contains `WARNING: Async test called done
more than one time.` There are 692 `(async done ...)` forms and 114 references
to the shared `settle!` helper in the current test tree. Async completion is
still inconsistent enough to truncate the one sequential Shadow run.

The runner's diagnosis is also wrong in this case. `bin/test-cljs:320-329`
labels any incomplete transcript containing an `invalid-schema` string as a
cold bundle load failure, even if dozens of `Testing ...` events prove test
execution began. Load failure should mean failure before the begin-run/first
test event, preferably from a structured runner event rather than a grep over
the whole transcript.

### Current suite shape

The active tree contains:

- 136 `*_test.clj`, `*_test.cljc`, or `*_test.cljs` files;
- 115 CLJS test files;
- 34 CLJS test files that directly call `d/create-database`;
- 692 explicit async continuations; and
- four synthetic probe namespaces under `test/seon/test/`.

The gym runner and the former list/tail-recovery mechanisms described by the
2026-07-12 audit are gone. The default full gate is therefore substantially
smaller than that historical five-minute run, but it is not currently a valid
green baseline because of the fixture leak and double completion above.

## What the vendored libraries support

### Shadow is already the correct CLJS build and selection authority

Shadow's `:node-test` target does the useful things Seon needs:

- An explicit `:namespaces` vector bypasses regexp discovery
  (`reference-code/shadow-cljs/src/main/shadow/build/test_util.clj:7-24`).
- Runtime `--test=` accepts namespace symbols and qualified test-var symbols,
  then selects Vars by `cljs.test` metadata, not source text
  (`reference-code/shadow-cljs/src/main/shadow/test/node.cljs:20-89`).
- Every watch compile cycle re-resolves test entries, so additions and
  deletions can enter the maintained bundle
  (`reference-code/shadow-cljs/src/main/shadow/build/targets/node_test.clj:35-63`).
- `:autorun` is opt-in. The managed watcher can build `:test` without letting
  Shadow spawn an unbounded test subprocess
  (`reference-code/shadow-cljs/src/main/shadow/build/targets/node_test.clj:65-102`).
- Build state owns `:immediate-deps`, resource provides, macro dependencies,
  and compiler output containing `:used-var-namespaces`
  (`reference-code/shadow-cljs/src/main/shadow/build/data.clj:47-50` and
  `reference-code/shadow-cljs/src/main/shadow/build/api.clj:325-379`).

Shadow explicitly says it cannot invalidate at individual-var granularity
because it does not know which vars changed
(`reference-code/shadow-cljs/src/main/shadow/build/api.clj:331-335`). Its live
algorithm therefore finds affected resources at namespace/resource granularity.
That is the honest granularity for Seon's first selector too.

There is a commented prototype named `execute-affected-tests!` in
`reference-code/shadow-cljs/src/main/shadow/build/test.clj:81-103`. It validates
the resource-dependent-to-test-namespace concept, but it is not active
supported API and relies on other commented helpers. Seon should not resurrect
that code verbatim. A small Shadow build hook can export the active build
facts Seon needs as an artifact owned by the existing `:test` build.

### `cljs.test` and test.check already provide stable test identities

`deftest` stores a real test function in Var metadata
(`reference-code/clojurescript/src/main/cljs/cljs/test.cljc:230-252`). The
async continuation must be invoked exactly once
(`reference-code/clojurescript/src/main/cljs/cljs/test.cljc:254-271`). Shadow
groups those Vars by namespace and produces the final summary only after the
whole block completes
(`reference-code/shadow-cljs/src/main/shadow/test.cljs:7-106`). This is why a
missing continuation truncates all later namespaces and why Seon's final
summary gate is load-bearing.

test.check's `defspec` also becomes an ordinary test Var with `:test` metadata
(`reference-code/test.check/src/main/clojure/clojure/test/check/clojure_test.cljc:75-98`).
Property tests therefore stay inside the same runner. They do not require a
new harness or a separate impact selector.

### Malli generates cases; it does not select affected tests

Malli's generator and function-check machinery composes with test.check and
caches generators by schema. It is useful inside behavioral tests, not as a
test dependency graph. Function contracts can provide additional conservative
schema-impact edges, but a contract does not prove which tests call a
function.

### Kaocha has good concepts but the wrong runtime boundary

Kaocha builds a structured plan from `clojure.test` Var metadata, supports
stable IDs and metadata filters, and in watch mode reruns failures before a
wider pass (`reference-code/kaocha/src/kaocha/watch.clj:211-245` and
`reference-code/kaocha/src/kaocha/plugin/filter.clj:101-169`). Its reload path
is JVM/tools.namespace oriented. Adding Kaocha would leave Shadow compilation
in place and add a second CLJS orchestration system. Borrow stable IDs,
machine-readable selection reasons, and optional failed-first ergonomics; do
not adopt Kaocha as another Seon runner.

## The database graph is not a sound platform test graph

The runtime currently knows these exact relationships:

- `:seon.test/sym` -> `:seon.test/ns` and per-test source/outcome facts
  (`src/seon/test/runner.cljs:142-175`);
- `:seon.fn/sym` -> `:seon.fn/ns`, source, and function contract
  (`src/seon/agent.cljs:186-221`);
- persisted runtime namespace require edges;
- analyzer-derived namespace dependencies for SCI/resume
  (`src/seon/analyzer_info.cljs:298-342`);
- function contract -> transitive Malli schema references in the process-local
  immutable schema projection (`src/seon/schema.cljc:266-345`); and
- function -> qualified database attribute literals used for render
  invalidation (`src/seon/eval.cljs:2488-2533`).

It does not know:

- test body -> called Vars;
- function body -> called Vars;
- complete macro/build/generated-source dependencies;
- higher-order, dynamic resolution, protocol dispatch, or runtime lookup
  edges; or
- platform test entities, which are intentionally not imported into the boot
  database.

The agent eval path already handles this honestly. It automatically runs only
new tests found by an exact analyzer definition diff and explicitly refuses to
guess existing tests from source substrings
(`src/seon/eval.cljs:1831-1843`, `src/seon/eval.cljs:2376-2400`, and
`src/seon/eval.cljs:4258-4289`). The platform selector should preserve the same
rule.

The database graph may eventually answer impact for agent-authored code if the
self-host analyzer emits complete test-to-var facts. It should not be used to
select platform tests compiled by Shadow. Those are two views of code with two
appropriate authorities, not two runners.

## Target design

### One public operation, existing runners underneath

Add an affected-files mode to `bin/seon test`; do not add a runner:

```text
bin/seon test changed [--base <git-ref>] [path ...]
```

It computes changed resources once, explains the decision, and delegates exact
namespace/var selectors to `bin/test-cljs`, exact JVM namespaces to
`bin/test-writer`, and the existing operator gate when operator/docs/build
files require it. The existing direct focused commands remain available.

The selector itself should be a small pure data transformation in the operator
code, not shell grep logic. Its input and output should be fully namespaced
maps so behavior is unit-testable. One output record per included test should
carry the changed path/resource, selected namespace or var, reason edge, runner
boundary, graph fingerprint, and any widening reason. This is diagnostic data,
not persistent application state.

### Changed-resource algorithm

For the CLJS boundary:

1. Resolve changed paths to Shadow resources/provided namespaces using the
   current `:test` build manifest.
2. Include a changed test namespace directly.
3. Starting from each changed production resource, walk the reverse-transitive
   closure of compiler-owned immediate dependencies and used-var namespaces.
4. Select affected resources that are known test namespaces.
5. Include the conventional sibling test namespace only when it actually
   exists in the compiler manifest; never manufacture a missing name.
6. Emit why each namespace was included and the exact chain when short enough.
7. Widen rather than omit when the graph is missing, stale, incomplete, or the
   change class is not modeled.

The build manifest should come from a small hook in the existing Shadow test
target and contain pure serializable facts: source/resource name, provides,
immediate dependencies, macro dependencies, used-var namespaces, test
namespace membership, source digest, and build/config digest. Do not parse
CLJS source a second time. CLJC reader conditionals, macros, aliases, generated
code, and npm resources are exactly why the compiler graph is the authority.

Conservative widening matrix:

| Change | Minimum safe selection |
|---|---|
| CLJS test resource | exact test namespace |
| ordinary CLJS source with current graph | reverse-transitive affected test namespaces |
| CLJ macro or shared CLJC resource with complete graph | affected test namespace closure |
| writer CLJ source | mapped writer namespace(s), otherwise full writer gate |
| shared wire/protocol CLJC | affected pod closure plus writer gate |
| config, schema/bootstrap core, generated source | owning tier; full pod when graph cannot prove closure |
| `deps.edn`, `shadow-cljs.edn`, package lock, runner/build hook | full relevant boundary |
| rename, deletion, unmapped path, stale/missing manifest | full relevant boundary |
| operator script/docs-linter code | operator gate plus any directly owned boundary |

The selector must never return “nothing to test” for an unknown source change.
“Nothing” is valid only when the graph proves the path is non-executable and
the decision record says why.

### Function-level selection is a later optimization with a strict proof gate

Manual qualified-var selection already works and is robust:

```text
bin/test-cljs --test=seon.example-test/a-specific-behavior
```

Automatic changed-function -> test-var selection should not ship from the
current graph. A safe future implementation requires a compiler/analyzer pass
that records, for every test Var, the resolved Vars in its analyzed body. It
also needs an equivalent function-body call graph and explicit completeness
markers for higher-order/dynamic/macro cases. Unknown edges widen first to the
test namespace and then to the tier. Source substring matching and line-based
test-name guessing are forbidden.

Changed test definitions themselves are a smaller opportunity. The compiler
could compare structural test Var metadata/body digests from successive build
states and select those exact qualified Vars. Until that fact is available,
run the changed test namespace; a one-namespace Node run is already cheap.

### Warm compiler, current complete bundle, fresh Node

The normal development topology already owns one managed Shadow watcher for
`:client` (`script/seon/dev/process.clj:124-157`). Have that same JVM watch
both `client` and `test`, with `:autorun` left false. This does not add another
background process. It gives the operator a current complete test bundle and
current compiler graph after each edit.

`bin/test-cljs` should then separate artifact coverage from runtime selection:

- A complete current bundle covers every namespace/var subset.
- A targeted cold bundle covers only its declared namespace set.
- The artifact manifest records content/build fingerprint and coverage set.
- `--no-build --test=...` is permitted when current coverage contains the
  request, not only when the selector string exactly matches.
- The test runner still starts a fresh Node process for every invocation.
- The live pod never runs platform tests, and overlapping Node invocations
  never share mutable runtime state.

The existing compile-plus-run lock cannot simply be held by a permanent
watcher. Publish versioned or atomically replaced test artifact/manifest pairs;
the runner snapshots one matching pair before launching Node. If the watcher
is absent or its manifest does not cover the current source digest, fall back
to the existing focused one-shot compile. Never silently run stale output.

Measure the one-JVM two-build watcher before making it default. The expected
trade is a slightly slower cold `bin/seon up` and more retained compiler state
in exchange for sub-two-second focused Node feedback. Acceptance must include
idle CPU, watcher RSS, incremental compile latency, and proof that edits during
a run cannot swap its artifact.

### Isolation stays inside one Node run

Do not hide fixture leaks by spawning a Node process per namespace. A full
checkpoint should remain one process so ordering and global-state defects are
observable. Fix the shared test state instead:

- one canonical fresh-connection helper for Datahike-backed tests;
- one schema-projection fixture that snapshots and restores the Seon
  projection and Malli default on success and rejection;
- root restoration for `db/*conn*` after every async path;
- restoration for instrumented Vars, `fetch`, environment overrides, timers,
  and other replaced globals;
- one Promise terminal that asserts rejection, enforces a timeout, clears its
  timer, and calls `done` exactly once; and
- optional deterministic order-perturbation as a dedicated acceptance check,
  not as a substitute for the normal stable order.

Pure data/query tests should use immutable database values or Datahike `with`
when transaction/listener/history behavior is not under test. Mutable
connections remain fresh when the behavior genuinely concerns a connection or
transaction. Do not share one mutable connection across unrelated tests for
speed.

## Legacy and duplicate material to remove

The following cleanup is safe in the test-refinement slice after behavior is
covered through the canonical runner:

- Replace the four `test/seon/test/*_probes.cljs` namespaces with ordinary
  seam tests in `seon.test.runner-test`; synthetic registered tests should not
  join the outer platform suite.
- Remove or update the stale `deps.edn` comment claiming
  `seon.dev.test-preload` imports platform tests into the client; that namespace
  no longer exists and platform tests intentionally stay out of the pod.
- Remove stale list/verify logs and extend bounded log cleanup to all canonical
  `test-cljs` diagnostic names, not just timestamp-shaped run logs.
- Treat `.shadow-cljs/`, `out/`, and old generated source containing removed
  gym/store/inventory namespaces as disposable artifacts. Canonical reset must
  rebuild them; searches and architecture audits must exclude them.
- Remove the isolated `:lora-audit` build after its concurrent owner completes
  and the audit has migrated or ended. Do not remove it while that lane is
  active.
- Consolidate the 34 direct database-creation sites and the many hand-written
  async terminals into shared support based on behavior, without creating a
  fixture framework beside `cljs.test`.
- Suppress expected library error payloads at the narrow assertion seam so a
  passing negative test does not flood the run. Do not suppress unexpected
  errors globally.

These are not duplicates and should remain:

- `bin/test-parser` is a sub-second Babashka inner loop for one pure CLJC parser
  and explicitly keeps `bin/test-cljs` authoritative.
- `seon.dev.test-runner` is the small operator boundary, not a CLJS runner.
- `seon.test.runner` executes and records agent-authored runtime tests; it does
  not replace the platform code gate.
- `bin/seon test` delegates; it does not implement another harness.

## Staged implementation plan

### Stage 0 — restore a trustworthy baseline

- Fix `seon.client.extra-core-test` so replay cannot leave the global schema
  projection altered; add shared restore-on-both-rails support.
- Find and fix the double-`done` test.
- Correct incomplete-run classification to use structured start/end evidence.
- Get one complete single-process CLJS summary and record namespace/test/
  assertion counts and timings.

Exit: full CLJS gate completes once without stitched retries, registry leakage,
or duplicate async completion.

### Stage 1 — remove the repeated compiler tax

- Make the managed Shadow JVM watch `client` and `test` without autorun.
- Atomically publish a complete test artifact manifest with content digest and
  coverage.
- Let `bin/test-cljs` reuse a current superset artifact for any subset and keep
  fresh Node execution.
- Preserve the focused one-shot compile as cold fallback, not a parallel path.

Exit: focused warm namespace and var runs start Node without a new JVM and
cannot observe stale or mid-swap artifacts.

### Stage 2 — namespace-level affected selection

- Export compiler-owned dependency facts from the existing test build.
- Add one pure selector and decision schema under the existing operator.
- Add `bin/seon test changed`, delegating to current boundary runners.
- Implement conservative widening and human/machine-readable explanations.

Exit: a corpus of source/test/macro/CLJC/config/deletion cases produces the
expected namespace/tier selections with no quiet unknowns.

### Stage 3 — shrink runtime cost and fragility

- Consolidate database/schema/ambient-root fixtures.
- Move pure derivations off mutable connections where behavior allows.
- Make one bounded async terminal universal.
- Remove probe namespaces, expected-error floods, stale comments/artifacts,
  and context-wording assertions that do not encode a contract.
- Keep behavioral, edge, protocol, schema-key, escaping, and route/SSE tests.

Exit: full runtime decreases materially, order perturbation finds no leaked
global state, and deleted legacy production paths no longer retain tests.

### Stage 4 — evaluate function-level facts, do not assume them

- Prototype compiler/analyzer extraction of test-var -> resolved-var and
  function-var -> resolved-var facts.
- Measure completeness on dynamic, higher-order, protocol, macro, and generated
  cases.
- Adopt exact var impact only for facts marked complete; otherwise widen.

Exit: mutation/corpus evaluation demonstrates no missed affected tests against
the namespace selector. If it cannot, keep namespace-level selection.

## Acceptance matrix

| Scenario | Required observation |
|---|---|
| Warm pure test var | fresh Node, no JVM compile, exact one Var, final summary |
| Warm CLJS namespace | fresh Node, no JVM compile, exact namespace, final summary |
| Cold CLJS namespace | one focused compile then fresh Node; matching artifact published |
| Changed production CLJS file | all reverse-transitive affected test namespaces selected with reasons |
| Changed test file | owning test namespace selected directly |
| Macro/shared CLJC change | graph closure selected or explicit full-boundary widening |
| Writer source change | exact proven writer namespace or full writer gate |
| Shared wire change | both pod and writer boundaries selected |
| Config/dependency/runner change | explicit full relevant boundary |
| Deleted/unmapped file | never “no tests”; explicit conservative widening |
| Stale/missing manifest | compile/fallback, never stale execution |
| Edit during test run | running Node sees one immutable artifact version |
| Async rejection/timeout | assertion plus one completion; later tests still run |
| Schema/ambient root mutation | later namespace observes baseline state |
| Full checkpoint | one Node process reaches final summary; no retries stitched green |
| Watcher idle | bounded idle CPU/RSS; no test subprocess because autorun is false |
| Selector audit | machine-readable selected set, reasons, graph digest, fallback reason |
| Function-level prototype | selected set never smaller than corpus/mutation truth without explicit widening |

Latency budgets should be set only after Stage 0 produces a green current
baseline and Stage 1 is measured. The present measurements establish direction,
not permanent thresholds. A reasonable initial success criterion is that a
warm focused namespace stays near its observed Node-only cost and does not
launch another JVM.

## Permanent instruction text for root `AGENTS.md` and `CLAUDE.md`

After implementation, add the same concise section to both root instruction
files and keep detail in `docs/seon/components/testing.md`:

```markdown
## Testing — affected first, one checkpoint

- Use `bin/seon test changed` for ordinary edits. It selects tests from the
  compiler/database facts and prints why each boundary/namespace was included.
- For a known target, use the canonical focused door:
  `bin/seon test pod <test-ns-or-qualified-var>`,
  `bin/seon test database <test-ns>`, or `bin/seon test operator`.
- Keep one runner per boundary. Do not add a harness, source-substring selector,
  test index, or ad hoc test command to avoid fixing an existing runner.
- A current complete Shadow test artifact may serve any subset; every
  invocation still runs in a fresh Node process. Never run platform tests in
  the live pod and never use a stale artifact.
- Unknown dependency edges widen to the owning namespace/tier. A quiet false
  negative is never an optimization.
- Run the smallest gate while iterating, then the affected batch once at the
  unit boundary. Run `bin/seon test all` only for a branch checkpoint or when
  shared build/protocol/config changes require it.
- Async tests use the shared bounded terminal exactly once on both Promise
  rails. Database tests use the canonical isolated fixture and restore every
  replaced root/global.
- Tests assert behavior, edge cases, schemas, protocol bytes, and stable data
  contracts—not incidental agent/context prose.
```

Do not put implementation details, selection matrices, or timing snapshots in
the root instruction files. They load into every agent context and will go
stale. The component doc owns commands and invariants; this research file owns
the evidence and staged design.

## Bottom line

The shortest reliable path is:

1. fix the current suite's leaked schema projection and double completion;
2. reuse the existing managed Shadow process and complete artifact;
3. select affected test namespaces from compiler facts with explicit widening;
4. simplify fixtures and remove probe/legacy noise; and
5. pursue function-level selection only after analyzer facts prove it safe.

That sequence changes the common loop from roughly ten seconds of compiler
startup plus test time to the already observed sub-two-second Node-only focused
cost, without introducing a parallel runner or pretending the current database
graph knows relationships it does not.
