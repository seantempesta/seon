---
type: research
status: in-progress
date: 2026-09-08
tags: [render, agent, test]
---

# Record render — landing evidence

The binding authority is
[the agent record PRD](../plan/agent-record-and-turn-loop-prd-2026-09-07.md),
including the owner's subsequent sections 13–15. AGENTS.md was read end to
end, including its lane paragraph and the default development cluster rule;
the named PRD was read end to end, and each appended ruling was read when
received. This is an integration record, not a completed acceptance report.

## Current slices

- Agent entity render pair: `seon.render.ns/render-agent-ai` and
  `render-agent-html`. One identity read includes id, namespace, and its
  steward. HTML uses the value renderer. The profile lookup derives the
  cluster name from the database, with no agent-to-cluster join.
- Compact plan reads: `my.plan/current`, `ready`, `blocked`, and `steps`.
  `start!` selects an owned step at the writer; `add!` and `complete!` return
  compact step maps. The plan pair is on `:my.plan/component-view`; its AI
  source chooses empty/populated forms from the data.

## Required final blocks

Identity, plan, settings, unanswered wakes, steward-routed faults, history. Empty wake
and fault concerns disappear. History is last, includes all evaluations in
chronological order, and walks their entity render pairs. Generated forms
are for the ordinary system turn that turn-cut stores; page previews write
nothing. The five-block page assembly is not yet implemented.

## Exact identity source observed for Juniper

MCP JVM evaluation on main-root `default` returned these source bytes from
the hot-reloaded Var; this was not a browser observation or proof of source
adoption convergence:

```clojure
; This is my identity and namespace; its steward is responsible for it.
(seon.db/pull (quote [:seon.cluster.agent/id #:seon.cluster.agent{:namespace [:seon.ns/name #:seon.ns{:steward [:seon.cluster.agent/id]}]}]) [:seon.cluster.agent/id "juniper"])
```

The direct database read returned Juniper, namespace `my.agents.juniper`,
and Juniper as that namespace's steward. The evaluated page response bytes
have not yet been captured.

The populated plan source authored for section 13 is:

```clojure
; Your plan. (dir my.plan) is its API; (doc my.plan/complete!) explains one form.
(my.plan/current)
(my.plan/ready)
(my.plan/blocked)
```

The empty plan source authored for section 13 is:

```clojure
; You have no plan yet.
(dir my.plan)
(doc my.plan/add!)
```

These plan sources are authored bytes, not a completed live proof.

## Evidence and limits

- `bin/test --platform`: 73 tests, 398 assertions, zero failures and errors.
  This was an intermediate snapshot, before the later plan changes.
- The subject invocation started but has no completed tally. Bare `bin/test`
  and the final focused gates remain outstanding.
- The identity regression uses the canonical database fixture and checks a
  distinct namespace steward without an agent cluster attribute.
- Juniper was initially absent from a read; later present. The prescribed
  fixture call refused with `Conflicting upsert: "step-render-plan" resolves
  both to 34744 and 34813`. Existing fixture facts were preserved.
- Repeated development adoption attempts refused concurrent source changes.
  A later refusal specifically named
  `my.agents.turn-cut-a-eb5b8a72-24a8-41a4-ac8b-be90edc02d5c/shared-inc`
  at `seon.sci.eval/install-row!`, invalid input. Its diagnostic listed
  required program-row keys for namespace and schema branches. This is not
  evidence that the function lacks a contract.
- A subsequent MCP call with `(+ 1 1)` returned missing effective config
  `[:seon.config.blob/max-bytes]`. The owner then explicitly instructed this
  lane to continue independent work under sections 15 and the history ruling.

## Integration boundaries still outstanding

- `dir` and `doc` are installed by the protected
  `src/seon/sci/eval.clj` functions `program-documentation`, `program-doc-var`,
  `program-dir-var`, and `install-program-doc!`. They still print prose and
  return their old values. The requested data-only injected macros need a
  turn-cut hunk at that seam.
- The existing history owner's `agent-config` still reads the agent's
  cluster attribute. Its replacement must derive the cluster from the same
  database value, and the old history assembly must be deleted under the
  newest ruling.
- Stored shown text, the live result-object carrier, evaluation inspection,
  the history namespace replacement, read-only page previews, and all final
  acceptance proofs remain unfinished.

## Dependency ledger

Datahike pull selectors and read dependencies:
`reference-code/datahike/src/datahike/pull_api.cljc`; first-party idioms are
`seon.render.ns/namespace-row` and `seon.db/pull`. Schema-declared pairs use
`seon.render/schema-producers` and `seon.render/render-call`. The preview
boundary is `seon.render.web/render-source-call`, calling the protected
`seon.cluster.loop/preview-sources`; the evaluation pair belongs to
`seon.repl`. Plan ownership and mutations use the existing `my.plan/rules`
and `transact-plan!` writer path.

## Stop boundary and final lane tally

The lane stopped under the owner's concurrent-breakage rule. The completed
focused invocation was `bin/test my.plan-test seon.render.ns-test` at snapshot
`628025af69ae1d60c19e480b00cd02c50507c75c`. It exited **1 before running any
subject tests**, during shared published-base preparation. The exception
contains `{:schema :char, :form :char}` at `seon.schema/build-projection`.
The snapshot's `src/seon/id.clj:45`, outside record-render ownership, declares
that schema in `seon.id/symbol-in`. The same minimal Malli probe on default
returned that rejection. No foreign files or sessions were edited or resumed.

The runner was reaped: launcher 31932, runner 33036, exit 1 at
2026-09-08T18:24:03Z. The retained evidence root is
`tmp/test-runs/run.9bJmLJ`; the focused log is
`tmp/record-render-subject-final.log`. The failure happened before a test
counter existed: **zero executed subject tests**, not a passing zero-test
suite. Bare `bin/test` and a final `bin/test --platform` were not attempted
after this stop condition. The earlier platform tally remains 73 tests,
398 assertions, zero failures/errors, and does not validate the final slice.

Commits:

- `57264aeb9` — identity entity pair and database-derived request profile.
- `080628130` — compact plan API and data-dependent teaching source, with
  updated canonical-fixture tests; verification blocked as above.

Implementation paths touched by these slices:

- `src/seon/render.clj` (request-profile only)
- `src/seon/render/ns.clj`
- `resources/seon/schemas/seon.cluster.agent.edn` (render properties only)
- `test/seon/render/ns_test.clj`
- `src/my/plan.clj`
- `resources/seon/schemas/my.plan.edn`
- `test/my/plan_test.clj`

No completed browser proof is claimed. Ten-load no-write and reply-byte
identity proofs were not rerun. The page still needs entity/component/derived
block assembly, scalar and Cluster-section removal, wake/fault pairs, and
read-only stored-history plus would-be-system-turn rendering. The custom
history namespace has not been replaced. `my.turn`, data-only `dir`/`doc`,
and compacting the older `item`/`items` read surfaces remain unfinished.
The old `:my.plan/ready-items` collection render declaration remains and
must be removed with its callers. Existing format helpers also remain.
The plan source test currently checks exact source bytes, despite its older
shared-reader name; it does not prove execution through SCI. This must be
corrected and supplemented with the real SCI proof before acceptance.

## Resumed for sections 17 and 16

The owner resumed this lane and replaced the concurrency gate with
`bin/test --paths` over only lane-owned files. The earlier stop is historical.
The plan arity change and all four callers were already committed in
`080628130`; the working tree had no residual plan arity diff when rechecked.

Section 17 component slice:

- `:seon.agent/plan` owns the objective, root steps, and current-step ref.
  `my.plan` ownership queries follow that edge, and the existing writer
  operations target its component. Juniper's fixture now has an objective
  and four root steps rather than an objective-shaped root step.
- `:seon.agent/settings` owns the overlay. `my.agent/settings` delegates to
  the existing `seon.ai/agent-overlay` reader, which now pulls that component.
  The existing schema derivation still supplies all per-agent dial keys and
  now declares the settings render pair. Evaluation and agent completion
  time limits are per-agent dials.
- Optional-only entity maps enter schema discovery by their declared
  attributes; an unrelated map does not match them merely because they have
  no required attributes. Both full and incremental projection indexes use
  the same admission rule. This was necessary for a sparse settings entity.
- Malli requires `[:and {:seon.db/component true} :seon.db/ref]` for these
  alias-backed refs; the literal vector-headed alias in section 17 refused.
  The component metadata and storage semantics are unchanged.

The first isolated attempt caught a misplaced attribute declaration and the
second caught that alias syntax. Both were fixed in this lane. The third
attempt (`tmp/record-render-components-test3.log`) passed shared-base
preparation and entered the runner; its tally is pending at this checkpoint.
Main-root development adoption reached program reconciliation; no browser
or component fixture proof is claimed at this checkpoint.

Additional implementation paths in this slice: `src/my/agent.clj`,
`src/seon/ai.clj` (overlay reader only), `src/seon/schema.clj` (shape discovery),
`src/seon/schema/edn.clj` (derived overlay pair),
`resources/seon/schemas/seon.agent.edn`, the config-agent and config-eval
schema resources, `test/my/agent_test.clj`, and `test/seon/ai_test.clj`.
The Juniper fixture and section 17's PRD integration paragraph changed too.
The section 16 page draft is still uncommitted and not included in this slice.

### Component follow-up, 2026-09-08

The component commit is `74b5b4b05`. Its first completed isolated plan gate
(`tmp/record-render-plan-gate.log`, root `run.WAmrg9`, HEAD `dbb0cda83`)
ran 19 tests / 68 assertions: 5 failures and 4 errors. These are owned
failures, not attributed to another lane. The earlier three-namespace gate
was terminated by TERM before a tally; it is not a green proof.

The report exposed reads using a not-yet-created component tempid: sibling
counting assigned the first step an erroneous position, and reconciliation
attempted a retract against that tempid. The follow-up only reads existing
component ids. The start writer tests membership in the owned step identity
set. The HTML fixture now carries a root identity and a fixed complete
profile. The plan HTML function asks the shared renderer for structural data
and propagates a typed error instead of placing an error map inside Hiccup.
The replacement gate is `tmp/record-render-components-test4.log` (plan and
settings namespaces); its result is pending at this checkpoint.

Default MCP answered arithmetic and an explicit value-renderer call returned
`:seon.render.value/missing-root-identity`, confirming the fixture omission.
A call supplying the root subsequently timed out at 3000 ms. Development
adoption again refused because source changed during adoption; no schema
refusal or successful convergence is claimed. No refork, reseed, browser
paint, or fresh/system/virtual/compact lifecycle proof has completed.

Live read evidence after `3f42669e0`: the previously timed-out write did
commit. `record-render-components/one` exists under the plan component with
position 4, reproducing the pre-fix tempid-count bug. No write was repeated
on the assumption that timeout meant absence. A settings component with
1234 ms evaluation time and 5678 ms completion backstop was then written.
Under its database projection, `my.agent/settings` returned exactly
`{:seon.config.eval/time-limit-ms 1234,
:seon.config.agent/turn-completion-backstop-ms 5678}` and the matching schema
was `:seon.config/agent-overlay`. The read-only reproduction is
`record_render_components_proof_2026_09_08.clj` in this directory.

The direct JVM settings call originally lacked a handed projection. The
follow-up derives it from the supplied database at the existing overlay
reader; callers no longer need a hidden projection binding for this read.
The value renderer also demonstrated its namespace-map spelling
(`#:my.plan{` and `:steps`), so the HTML assertions now check its actual
structural output instead of demanding one unsplit qualified-key string.
The test4 gate was deliberately terminated and reaped before assertions
because it snapshotted those known stale assertions. Test5 contains the
updated plan/settings/AI paths and selects all three subject namespaces.

### Debug page repair, 2026-09-08

The owner observed HTTP 500 at `/ns/my.agents.juniper/debug`: the SCI
invocation lacked an integer `:seon.sci.eval/time-limit-ms`. The page's new
`debug-turn-request` copied the handle but did not translate its
`:seon.config.eval/time-limit-ms`. It now supplies the evaluation key,
`:seon.sci.admit/caps`, and the agent root identity for value rendering.
A real-socket regression requests the algorithm page through the existing
canonical web fixture and checks HTTP 200 and its three controls.

MCP JVM evaluation reloaded ONLY that private function from its checked-in
source form. HTTP GET on default, port 7994, was 500 before and 200 after
(`tmp/record-render-debug-before.html`, `tmp/record-render-debug-after.html`).
This is a hot-reloaded-Var proof, not successful source adoption or browser
layout proof. The repair commit necessarily includes the pending page
scaffold and its context-action schemas: the faulty helper belongs to that
new page path and does not exist in the prior HEAD.

The scaffold groups identity and component concerns, supplies typed values
naming unavailable `seon.eval/of-agent` and `seon.turn/system-turn`, and routes
system/virtual/compact requests through the existing context channel.
It is NOT section 16 completion: ordered stored bytes, last-turn basis,
as-of checks, prompt digest, derived concern blocks, and the full lifecycle
proof remain unfinished. No green page test tally is claimed here.

Gate6 reached the canonical SCI fixture and exposed an owned load-time
blocker in `test/seon/render/web_prompt_test.clj`: its two `with-redefs-fn`
tests refer to the deleted private `prospective-prompt` Var. The JVM refused
that test namespace, so the worker could not run assertions. Gate6 was
terminated and reaped after preserving the exact cause. The obsolete mocked
capture-comparison tests are removed; the real-socket algorithm-page
regression in `web_test.clj` remains. This is not a foreign-lane attribution.

A native Safari fallback subsequently rendered the Juniper debug page,
including Context now, Would-be system turn, all three controls, and typed
messages naming `seon.eval/of-agent` and `seon.turn/system-turn`. The entity
and graph regions still showed loading placeholders; a complete feed/HTML
block proof is not claimed. HTTP GET was independently 200 again.

Additional positive live writer observations on default: two `add!` calls
on `record-render-fixed` returned their own item maps and `steps` returned
first then second. On a new `record-render-reconcile` agent, `plan!` returned
one added step; repeating the same authored input returned converged true
and a zero diff. These exercised the loaded definitions after development
adoption reload work; source-commit convergence has not yet been established.

### Protected publication boundary

The next explicit default publication (`tmp/record-render-publication7.log`)
failed static analysis: `src/seon/turn.clj:135:63`, `Unmatched bracket:
unexpected )`. This is the concurrent turn-cut owner and was not edited or
operated by record-render. No schema refusal occurred, so no refork was
performed. Per the assignment's stop rule, no further live adoption is
attempted in this run. The already-running gate7 uses an owned-path HEAD
snapshot and can report independently of that in-flight source.

Current commits since the previously landed plan slice: `74b5b4b05`
(components), `3f42669e0` (tempid/read and render fixes), `b48034365`
(database-grounded settings reader), `38d2fc91b` (debug HTTP 500 repair and
algorithm scaffold), and `a86a3d280` (obsolete mocked capture tests removed).
Source convergence, Juniper reseeding, complete page blocks, the stored
prefix/as-of/digest checks, and the fresh→system→virtual→compact proof remain
unfinished. The missing turn/evaluation APIs and their storage contracts
remain integration dependencies; typed unavailability is visible in Safari.

### 2026-09-08 20:10 UTC — HTTP system-turn handle repair

The owner resumed this lane for the new system-turn input refusal. The
HTTP construction in `src/seon/cluster.clj` selected the render view without
the instance's cluster handle. It now carries that exact handle to the web
service; `src/seon/render/web.clj` uses the landed namespaced
`:seon.turn/write?` flag for both preview and explicit execution. The narrow
HTTP construction hunk is outside the original render files, but is the
necessary caller change for the owner's explicit handle repair; no files
under the protected `src/seon/cluster/` directory were changed.

Live proof: MCP JVM arithmetic returned 2 on default. Publication refused
because it could not locate `seon/test_support` on the live classpath.
The existing default instance's HTTP service alone was rebound with its
actual handle after re-evaluating the changed private functions from source.
GET `http://127.0.0.1:7994/ns/my.agents.juniper/debug` changed from HTTP 500
to HTTP 200. Response bytes are in `tmp/record-render-handle-debug.html`.
This proves hot-reloaded HTTP reachability, not source convergence or full
§16 behavior: `seon.eval/of-agent` remains visibly unavailable, and the
would-be value is currently rendered as an elision. No new test tally is
claimed at this checkpoint.

### 2026-09-08 20:27 UTC — would-be forms and verification boundary

The next page slice renders the system result's forms individually: lookup,
status, basis, changed facts, then the API's evaluated text (or unchanged
source), each through the value renderer. It no longer feeds the whole walk
result into the algorithm pane. The web fixture now carries one canonical
cluster handle through both the proc and HTTP service. Obsolete mocked
capture/scalar-block tests were deleted; schema-order verification uses a
real agent with plan/settings components. The HTTP regression loads the
page ten times and checks that the immutable database value did not change.
Settings schema verification derives the projection from its database.

Observed fast-loop tallies, all red and all before a complete final proof:

- Components: 20 tests, 102 assertions, 5 failures, 0 errors. Four HTML
  expectations failed; settings shape discovery was the fifth.
- First web run: 64 tests, 412 assertions, 39 failures, 1 error. This
  exposed obsolete capture/scalar tests as well as remaining render failures.
- Revised settings/web run: 59 tests, 105 assertions, 0 failures, 46 errors.
  Canonical fixture construction failed in `seon.fn/exact-source:142` before
  the affected test bodies; the error does not identify its source file.

Gate8 was cancelled after test revisions made its snapshot obsolete. Gate9
and the platform attempt were cancelled and reaped while waiting on the
shared dependency-cache lock, with no test tally. No green gate is claimed.
The existing shared-base issue records the exact stack and paths.

Default GET remained 200 after hot-reloading the two new private page
functions. The actual visible would-be bytes remain an upstream elision,
recorded in `docs/seon/issues/debug-system-turn-elision-hides-generated-forms.md`.
`seon.eval/of-agent` remains absent. Re-seeding the fixture refused a
conflicting step upsert, recorded in its issue; Juniper has no new plan
component yet. Development adoption still refuses loading `seon.test-support`
from the live classpath. These are unresolved observations, not successful
§16/§17 proof. No protected turn-cut files or other lane sessions were edited.

Unfinished: component/render failures, successful publication/refork and
Juniper reseeding, empty-plan proof, derived wakes/fault/history blocks,
`dir`/`doc` data integration, `my.turn`, the history namespace replacement,
stored-prefix/as-of/digest display, full control/feed lifecycle proof, and
green isolated/platform gates. The broader lane is not complete.

Final HTTP observation for this slice: status 200, 11 would-be source blocks.
The exact contents of those source blocks are retained (separated by two
newlines for inspection, not claimed as provider-prompt concatenation) in
[record-render-juniper-ai-observed-2026-09-08.txt](record-render-juniper-ai-observed-2026-09-08.txt),
7,650 UTF-8 bytes. The latest page uses the already-rendered
`seon.turn/text` directly in an escaped HTML pre element; passing that string
through another printer had shown quotes and literal newline escapes.
The earlier elision disappeared after subsequent partial development
reloads, but the 11 blocks still include legacy per-step queries.

The identity block observed exactly:

```clojure
; This is my identity and namespace; its steward is responsible for it.
my.agents.juniper=> (seon.db/pull (quote [:seon.cluster.agent/id {:seon.cluster.agent/namespace [:seon.ns/name {:seon.ns/steward [:seon.cluster.agent/id]}]}]) [:seon.cluster.agent/id "juniper"])
#:seon.repl{:value #:seon.cluster.agent{:id "juniper", :namespace #:seon.ns{:name my.agents.juniper,
    :steward #:seon.cluster.agent{:id "juniper"}}}, :ms 9}
```

The separate ten-load HTTP script timed out waiting for a response under
its 30-second per-request bound; it produced no successful total and does
not establish the no-write claim. The later single GET returned 200. Native
Safari observation could not acquire a window (`cgWindowNotFound`), so this
checkpoint does not claim fresh browser paint or feed/control verification.

This slice touches `src/seon/render/web.clj`, `test/seon/render/web_test.clj`,
`test/my/agent_test.clj`, this landing note, the raw AI observation, the HTTP
probe script, and four issue notes (the existing shared-base issue plus
the elision, fixture-upsert, and development-classpath issues). The preceding
`4c24e894d` repair additionally touched `src/seon/cluster.clj`.

Final fast-loop rerun, after displaying `seon.turn/text` without a second
printer: 59 tests / 358 assertions, 9 failures / 1 error. The settings
component, canonical component-order, and ten-load HTTP regression completed
without a reported failure. The namespace remains red: old bind-fixture and
page expectations, render elision expectations, a fault-count assertion,
and the message-on-feed test remain unresolved. Output:
`tmp/record-render-fast-web3.log`. This is an iteration result, never an
isolated gate or a claim that the whole page works.

### 2026-09-08: resumed assertion repair

The nine failures and one error above belong to six tests:

- `failed-ephemeral-bind-preserves-the-bind-failure` (two failures): the
  incomplete service request was refused before reaching socket binding.
  It now uses the canonical service request, including a real SCI context.
- `an-undeclared-incoming-reference-is-reachable-from-the-page` (two
  failures): provenance remains in the reference graph, rather than adding
  an extra context concern. The assertion waits for that graph's SSE patch.
- `a-five-megabyte-value-is-elided-for-ai-and-complete-for-html` (two failures): the elision now carries
  an executable `get-in` requery; the assertions name that actual grammar.
- `each-agent-has-an-isolated-debug-route` (two failures): §16 makes the
  algorithm visible on the ordinary debug route. Isolation remains checked.
- `a-page-failing-every-pass-offers-one-fault-and-the-proc-survives`
  (one failure): the injected render fault now
  receives a real cluster context and agent, so admission cannot precede
  the deliberately failing renderer.
- The message-on-feed test (one timeout error): its retired surface
  expectation is replaced by a real HTTP route test checking the durable
  addressed message. This is route coverage; derived-wake repaint coverage
  remains part of the next implementation slice.

The first retry failed before these assertions because clj-kondo's shared
cache was locked. The next retry reached armed test bodies. An independent
GET of `/ns/my.agents.juniper/debug` returned 200 during this retry.

That retry completed 59 tests / 356 assertions with two failures and no
errors. Both remaining assertions were corrected from the observed values:
the message Datalog result is a set of tuples, and the reference graph's
JSON escapes the slash in its attribute string. The final rerun and the
path-isolated gate are separate processes. The latter snapshot contains
only `test/seon/render/web_test.clj` over HEAD `b7e8a914336511cfcfeb37b53896ba1a22c0237d`;
its dependency-cache lock wait completed after 19,284 ms.

The edit hook publication `0376b9c6-520c-4ce8-9a5f-9b4bbd52fad4` ended
with operator exit 124. Kondo reported warnings (unused requires/bindings
and shadowed fixture names), not a syntax/arity rejection. A separate
read-only MCP pull/query of Juniper timed out after 10,000 ms. Neither
observation is claimed as successful development adoption or fixture proof.

The final fast run is green: **59 tests / 356 assertions, zero failures,
zero errors** (`tmp/record-render-fast-fixes-final.log`). It includes the
ten-load no-write regression. The 30-second retry of the live Juniper pull
completed in 14,567 ms and returned only its agent id: the plan component
is still absent. The short MCP deadline, rather than absence of the agent,
explains the earlier unavailable observation; no write was attempted.

### Juniper component slice, 2026-09-08

The fixture resolves existing step identities inside its transaction function,
using the writer's database value. Fresh steps keep transaction-local ids;
existing steps use their entity ids. The plan's own component id is reused,
and the former agent-level step/current edges are retracted. This avoids
Datahike's `retry-with-tempid` conflicting-upsert path
(`reference-code/datahike/src/datahike/db/transaction.cljc:843`). No schema
refusal occurred, so default was not reforked.

The first install exceeded the MCP 30-second response bound, but a subsequent
read established that it committed. A second install returned the plan,
and the subsequent query returned `#{[73475 4]}`: the same plan component
and four root steps. Current is `juniper/render-plan`; positions 0–3 are
inspect, render, compare, live-turn. The latter two remain blocked by their
declared dependencies. The fixture's `install!` now declares its contract.

The shared value renderer had reduced component contents to identity refs.
It now preserves values under `:db/isComponent` attributes; ordinary refs
still use their identities. This restores the plan's titles and completion
data in HTML without adding another renderer. The plan fixture regression
now uses the objective plus four root steps, matching the live shape.

Fast and isolated component gates both passed: **41 tests / 181 assertions,
zero failures and zero errors**, namespaces `my.plan-test` and
`seon.render.value-test`. The isolated gate overlaid exactly
`src/seon/render/value.clj`, `test/my/plan_test.clj`, and the Juniper fixture.

The page returned HTTP 200 in 16.580543 seconds after the HTTP projection
repair described below. Its exact 12 source blocks (5,414 UTF-8 bytes,
joined by two newlines for inspection) are in
[record-render-juniper-component-ai-2026-09-08.txt](record-render-juniper-component-ai-2026-09-08.txt).
This includes the plan's actual evaluated current/ready/blocked reads;
the extra legacy blocks are not claimed as the final ruled page structure.

### Projection propagation diagnosis, 2026-09-08

After reseeding, GETs exceeded 30 and 60 seconds. A JVM thread dump including
virtual threads found 26 debug requests constructing schema projections:
18 in `seon.schema/admission-from-asserting-transaction` and eight in
`fold-contract-validations`. That observation locates work, not ownership
of its cause. A narrower probe supplying the existing SCI projection made
the same Juniper pull complete in 1 ms. The owned HTTP boundary supplied
that value only around configuration lookup, dropping it for later reads.

The Ring handler and render pass now supply their handed SCI projection for
the entire operation. Live verification re-evaluated the owned handler,
re-armed contracts, rebound only default's HTTP service on 7994, and then
re-evaluated the private render pass. It did not reload a foreign owner or
claim successful development adoption. The subsequent MCP envelope called
default degraded even though the private reload returned successfully.

### Final boundary for this continuation, 2026-09-08

Component commit: `00b2b95e2`. The original nine assertion failures and one
error were corrected or re-expressed as listed above. The subsequently
observed fixed-zero-digest expectation now compares the header with the
digest in the canonical database; a published fixture has a real digest.
The final fast run with both projection propagation fixes passed **59 tests
/ 356 assertions, zero failures and zero errors**.

The final page path gate overlaid only `src/seon/render/web.clj` and
`test/seon/render/web_test.clj`. It completed **59 tests / 317 assertions,
zero failures and four errors**. All four were bounded completion waits in
the pooled worker and passed the runner's isolated confirmation:

- `reconnect-is-repaint`;
- `reconnect-is-repaint-wire-test`;
- `reconnect-mid-stream-is-a-fact-only-repaint`;
- `render-proc-one-derivation-many-tabs-test`.

This is not an isolated green gate. No timeout was increased, and no cause
is inferred from the pooled/isolated difference. The open finding is
[debug feed waits](../../../seon/issues/debug-feed-waits-fail-only-in-pooled-worker.md).

`bin/test --platform` completed **82 tests / 483 assertions, two failures
and zero errors**, both reproduced by isolated confirmation:

- `seon.schema.declaration-population-test/an-unhanded-declaration-projection-refuses-without-reading-resources`
  expected its test namespace as caller but received
  `seon.instrument (instrument.clj:603) [no declared source root — nearest frame]`.
- `seon.test-support-test/an-instrumentation-test-restores-the-entering-contracts-on-failure`
  expected an empty instrumented-Var set after removal, but Vars remained armed.

`src/seon/schema.clj`, `src/seon/instrument.clj`, and the corresponding test
files had another lane's uncommitted changes at this observation. The explicit
assignment stop rule applies to this confirmed boundary. No foreign source,
test, or session was changed to pass it. Cache-lock waits completed normally;
they were not the stop condition.

Default was restarted during verification into JVM 45036 and its HTTP service
fell back to port 58444. After that start completed, the owned handler and
render pass were re-evaluated, contracts re-armed, and only default's HTTP
service rebound to 7994. A GET returned 200 in 7.502320 seconds. The checked-in
HTTP proof then completed ten GETs with statuses
`[200, 200, 200, 200, 200, 200, 200, 200, 200, 200]`; the final response was
140,564 bytes. These are HTTP observations, not a browser or lifecycle proof.
The canonical no-write regression passed in the final fast run. No full
integration or provider request is claimed.

Still unfinished, in the owner's requested order: `my.turn/evals` and `eval`
depend on the absent `seon.eval/of-agent` query and stored shown-text field;
the protected SCI `dir`/`doc` injection still needs its data-returning change.
Derived wake/fault query blocks, the as-of check, prompt digest, the history
namespace cut, empty-plan page bytes, and the three-controls lifecycle proof
remain unfinished. The PRD's existing record-render integration boundary names
the evaluation owner dependencies; no second store or reconstructed saved text
was introduced to conceal them.

All owned test commands ended and were reaped before reporting. No process
held the inspected test roots. Superseded roots were already absent at
cleanup; the last failed page/platform roots are retained by the test gate
for their unresolved evidence (`run.Z0t7FF`, `run.ll9Sm1`, if not subsequently
reaped by the operator's normal sweep). No foreign process was stopped.
