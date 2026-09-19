---
type: research
status: active
tags: [render, error, contracts]
---

# Render-family kind retirement — 2026-09-20

Initial checkpoint at `4ea9a5a0a` (superseded by the continuation below):
work in progress; no completion or green claim. The five source files and
`resources/seon/schemas/seon.render.edn` contain uncommitted partial edits.
Producer and consumer changes must land together; these edits are not a
landing-ready checkpoint.

## Grounding

Read the supplied AGENTS.md sections 0–5, namespace-agents plan section 8,
inventory replacement actions R1–R8 and the five owned source sections,
and error-family landing note Step 6 / D13 continuations. Read the base and
component declarations and the error PRD render manifest. Used the
data-oriented-clojure, datastar-web-ui, clojure-testing and repl skills.

Dependency ledger: the existing `seon.error/project-observation`
(`src/seon/error.clj:582`) owns evidence projection and calls its bounded
admission seam (`:405`), which consumes supplied SCI admission caps. The
render data cursor (`src/seon/render/data.clj:43`) accepts arbitrary values;
its regression explicitly navigates an infinite sequence
(`test/seon/render/data_test.clj:50`). New evidence serialization cannot
assume those roots are finite.

## Owner decision pending

AGENTS.md section 2.5 requires the owner design gate before a cross-owner
change. The new `:seon.render.data/error` requires an error-root projection
and requested-location, while `at` receives only a value and cursor, without
caps. An asynchronous question presented these options:

1. Recommended: carry evidence caps in the request/cursor and use the
   existing projector. Guarantees bounded evidence; costs caller changes,
   including held/out-of-scope `src/seon/cluster.clj:540`.
2. Record only root identity/type and path. Preserves the API and avoids
   realizing arbitrary values; gives up the failed root value as evidence.
3. Add a declared default evidence policy here. Preserves callers; adds
   policy acquisition at a boundary that currently carries none.

No option has been selected. No independent clipping helper or new default
bound has been introduced.

## Interim source census

Point-in-time literal occurrence counts (not completion):

| File | Before | Remaining |
|---|---:|---:|
| src/seon/render.clj | 37 | 9 |
| src/seon/render/transcript.clj | 50 | 8 |
| src/seon/render/web.clj | 38 | 9 |
| src/seon/render/walk.clj | 10 | 3 |
| src/seon/render/data.clj | 8 | 2 |

Straightforward consumers inspect all three base members inline. There is
no new general predicate. The invalid-output producers now supply the base
and retain the concrete requested output; their facet is
`:seon.render/invalid-output-error`. All other producer facets and test
conversions remain unfinished.

## Verification

Read-only MCP runtime status observed default pid 41822 alive with replying
procs. A read-only JVM probe reproduced the old missing-path observation.
Default lifecycle and loaded definitions were not changed by this lane.

The five-namespace load command returned `:loads` after the initial consumer
edits, before the later invalid-output contract edit. It is not proof of
final HEAD or adoption.

The requested existing test namespaces are `seon.render.transcript-test`,
`seon.render.web-test`, `seon.render.walk-test`, and
`seon.render.data-test`. `test/seon/render_test.clj` does not exist.

Main-tree fast overlay exited 64 before tests: required dirty callers
`src/seon/db.clj`, `test/seon/error_test.clj`, and
`test/seon/schema_test.clj` are held by another lane. No held file was added.
The prescribed detached HEAD worktree at bcb0ef256 excluded those edits;
its first overlay exited 64 because that worktree lacked a published base.
Linking the existing `target/test-published-bases` cache allowed admission.
The subsequent run acquired its packaged projection and armed 1,312
contracts, then began the first transcript test. The lane deliberately
terminated its own test JVM (pid 41067) while awaiting the owner decision;
the launcher exited 143. No test/assertion tally was emitted. This was an
interrupted diagnostic run, not a test failure attribution or a green claim.

## Cross-file needs and proof owed

- `src/seon/cluster.clj:540`: data cursor evidence policy, pending decision.
- `src/seon/sci/reader.cljc:11,623`: reader observation still stamps the old
  classification; the transcript consumer needs the declared reply-phase
  evidence, not another boolean marker.
- `src/seon/db.clj:4268`: unknown write completion still uses the old stamp
  and boolean. The web HTTP status consumer is being moved to the declared
  write-attempt completion-unavailable observation.
- Callees still declaring generic error values need their owning later
  sweeps; no foreign source or test was edited.

Cold gate and platform proof remain owed by the orchestrator, followed by
live adoption and browser observation. No cold gate or lifecycle command
was run by this lane.

## Cleanup

The owned diagnostic JVM and launcher exited. The scratch worktree was
removed after copying nothing over the main draft. All six source/schema
drafts remain in the main working tree; unrelated edits were preserved.


## Continuation — producer evidence ruling

The orchestrator rejected all three interim options. The binding rule is
that producers retain ordinary offending values in memory without bounding
or omitting them. Recorder admission and the value renderer remain the two
existing bounding seams. The earlier cross-file request against
`src/seon/cluster.clj:540` is withdrawn; no caller change is needed.

Read `docs/prds/steward-platform/plan/error-conversion-prd-2026-09-20.md`
end to end, including message grammar and per-commit verification. Also
read the data-modeling skill for the facet resource changes.

`seon.render.data/at` now retains the identical root object as diagnostic
Offending data, the full requested path, the root's JVM description, and
the requested path length. Its regression includes an unrealized sequence
whose body throws if evaluated; this probes producer evidence retention
without introducing any presentation or recorder policy.

The first continuation snapshot exposed an unstorable cursor path member
in a persistent facet. The correction keeps the arbitrary path as ordinary
diagnostic evidence and persists root description and requested path length.
The second snapshot exposed a newly added three-argument contract on the
two-argument `ensure-namespace-owner!`; the contract now names the actual
service-map and namespace inputs. Both fixture failures were then obscured
by the held `test/seon/test_support.clj:515` error reporter calling the new
diagnostic constructor without its required base members. That thread
fails before delivering its promise, so the runner's liveness bound is the
terminal observation for those runs, not a test tally.

The scratch worktree is based at `a931e68b8` and overlays only the owned
paths. Its `reference-code` and published-base cache link to this checkout.
It excludes held in-flight edits. No foreign source, schema, test, lane
session, or lifecycle command is part of the change.


## Conversion census

The counts below are matching lines, the same convention as the assignment's
143-source-site inventory. The transcript file has 51 literal occurrences
on its 50 matching lines. These measurements compare the unchanged owner
files at HEAD with the continuation drafts.

| File | Before matching lines | After matching lines |
|---|---:|---:|
| `resources/seon/schemas/seon.render.data.edn` | 0 | 0 |
| `resources/seon/schemas/seon.render.edn` | 0 | 0 |
| `resources/seon/schemas/seon.render.transcript.edn` | 0 | 0 |
| `resources/seon/schemas/seon.render.unknown.edn` | 0 | 0 |
| `resources/seon/schemas/seon.render.walk.edn` | 0 | 0 |
| `resources/seon/schemas/seon.render.web.edn` | 0 | 0 |
| `src/seon/render.clj` | 37 | 0 |
| `src/seon/render/data.clj` | 8 | 0 |
| `src/seon/render/transcript.clj` | 50 | 0 |
| `src/seon/render/walk.clj` | 10 | 0 |
| `src/seon/render/web.clj` | 38 | 0 |
| `test/seon/render/data_test.clj` | 6 | 0 |
| `test/seon/render/faults_test.clj` | 2 | 0 |
| `test/seon/render/history_test.clj` | 1 | 0 |
| `test/seon/render/page_review_test.clj` | 2 | 0 |
| `test/seon/render/retained_test.clj` | 4 | 0 |
| `test/seon/render/root_pull_test.clj` | 2 | 0 |
| `test/seon/render/runtime_test.clj` | 1 | 0 |
| `test/seon/render/transcript_run_test.clj` | 1 | 0 |
| `test/seon/render/transcript_test.clj` | 6 | 0 |
| `test/seon/render/walk_test.clj` | 4 | 0 |
| `test/seon/render/web_debug_test.clj` | 10 | 0 |
| `test/seon/render/web_test.clj` | 7 | 0 |

## Producer declarations

Every listed facet extends the base and requires substantive observation
members. The errors retain their diagnostic data; none of these producers
acquires caps or bounds its offending value.

| Owner and producing functions | Declared facet | Distinguishing observation |
|---|---|---|
| render: request-profile, source-provenance-error, render-call | `:seon.render/request-error` | refused member, supplied request or selected candidate |
| render: ambiguity | `:seon.render/ambiguous-error` | at least two candidate function symbols |
| render: raw-output, render-form-value | `:seon.render/invalid-output-error` | requested output and actual returned value |
| render: unknown | `:seon.render/unknown` | invocation outcome, producer/output, observed refusal operation |
| render: walk-error | `:seon.render/walk-failed-error` | observing walk operation; rendered through the error pair |
| data: at | `:seon.render.data/no-such-path-error` | root description and path length; identical offending root and full path retained in memory |
| data: observation-error (incoming-page, entity-observation) | `:seon.render.data/observation-error` | refused subject/continuation member and actual request evidence |
| transcript: missing-selected-run, runtime-owner, render-runtime-html | `:seon.render.transcript/request-error` | missing turn/runtime member, requested identity and inputs |
| walk: connection-observation, distance-cap-unit; web: generic-entity | `:seon.render.walk/elided-error` | bound attribute, limit and continuation subject |
| walk: neighborhood | `:seon.render.walk/no-such-entity-error` | missing lookup |
| web: debug-diagnostic, debug-page-result, ensure-namespace-owner!, context-response | `:seon.render.web/request-error` | refused page/context/owner member and original input |
| web: write-package!, await-feed-package! | `:seon.render.web/request-error` | requested future or port operations, carried wait bound and completion identity |
| web: turn-function-result | `:seon.render.web/function-unavailable-error` | unresolved qualified function symbol |
| web: render-step | `:seon.render.web/missing-port-error` | missing port and full missing-port set |
| web: data-response | `:seon.render.web/value-not-found-error`, `:seon.render.web/value-unreadable-error` | requested digest, with read exception evidence when present |

The two web delivery helpers supply the diagnostic base to their existing
`seon.await/await!` request. On refusal they add the web facet at their own
boundary and preserve the await evidence. Feed consumers now inspect that
facet. No await owner or additional wait mechanism was introduced.

Known error branches in the debug renderer call the existing error pair;
they do not dump the full raw diagnostic map. The unknown renderer names
its facet and stable observation fields. The remaining implementation of
the full message grammar in `seon.error/render-ai` and `render-html` is held
by the error-family owner.

## Verification continuation

- First isolated continuation run: exit 124 at the runner's liveness bound;
  no tally, due to the unstorable cursor-path declaration and then the
  fixture reporter failure described above.
- Second isolated continuation run: exit 124 at the runner's liveness bound;
  no tally, due to the contract arity mismatch and the same reporter failure.
- Third isolated continuation run: **86 tests / 560 assertions / 19 failures /
  16 errors**, exit 1. The missing-path regression, including the unrealized
  root, passed under instrumentation. Corrections made after that tally:
  nullable empty selection input to pulled-many; positive transaction-result
  assertions; complete identified/owned fixtures; complete web wait requests.
- The later main-checkout overlay admission exited 64 and named dirty held
  `src/seon/error.clj`, `src/seon/sci/eval.clj`, and
  `test/seon/error_test.clj`. The following run again uses the isolated
  HEAD worktree and the twelve owned test namespaces.

The existing issue
[docs/seon/issues/test-refusal-observations-overflow-in-projection-acquisition.md](../../../seon/issues/test-refusal-observations-overflow-in-projection-acquisition.md)
records the compiled-schema normalization boundary also observed by this
lane. In this snapshot, native database read refusals reach
`seon.fn.schema-shape/normalized-form:130`; SCI execution failures reach the
old diagnostic invocation in `seon.sci.kernel:583`. These are observed
foreign boundaries, not a claim that every rendered-page failure has the
same cause.

## Final review continuation

Read the binding error-conversion PRD end to end, including §1.4 and §4,
and read the later constructor-leaf amendment at `00be89d1f`. The constructor
leaf is an error-family landing dependency; this snapshot still uses the
existing constructor. No second constructor was introduced.

The expanded twelve-namespace run on `a931e68b8` exited **124**, without a
complete tally. It reached
`seon.render.web-debug-test/turn-details-use-the-loop-opening-and-exact-segments`;
a thread probe of this lane's JVM 59299 observed:

```text
main: clojure.core.async/alts!!
  seon.context-blocks-fixture/submit! await! (context_blocks_fixture.clj:201)
  seon.context-blocks-fixture/submit! (context_blocks_fixture.clj:223)
  seon.render.web-debug-test (web_debug_test.clj:85)
```

The fixture carries the configured 600,000 ms turn-completion deadline;
the suite's reporter-silence bound fired first. This is a blocked observation,
not a passing debug namespace or an unbounded-wait claim. The original
worktree was removed after its JVM exited.

Review corrections before the next run: the namespace ambiguity caller now
retains qualified symbols; selection's nested errors name the ambiguity and
request facets; source-generation and namespace-connections name database
pass-through facets; ledger-rows names its database errors. The entity-pull
cache checks the database callee's transitional base, with named debt, rather
than inspecting unrelated renderer members. Diagnostic layers match their
observing boundaries. Cursor restart inspects the refused continuation member.
Owned fixtures now use the plan/settings/cluster writers and canonical program
function helper where those fixtures had drifted.

### Remaining cross-file boundaries

- `src/seon/db.clj`: read/write producers and its `:seon.db/error-result` union
  still admit the base in the tested snapshot. Native index refusals reached
  `seon.fn.schema-shape/normalized-form:130`. The render pass-through unions
  inherit that transitional contract; this is not a claim of transitive D12
  completion for the whole program.
- `src/seon/sci/kernel.clj:583`: the tested diagnostic call lacks the base
  members. `seon.sci.admit` must admit the new render facets at its existing
  value-admission boundary. Neither owner was edited.
- `src/seon/error.clj` and `src/seon/instrument.clj`: the complete message
  grammar and constructor leaf remain owned by 1a. A pulled-fault render
  produced a stack overflow through instrument wrappers and `seon.env/of`;
  no blanket attribution is made for the other failed pages.
- `src/seon/sci/reader.clj`: reply failures still use the retired stamp in
  this snapshot; transcript recognition uses the recorded reader phase
  instead.
- `src/seon/await.clj`: web supplies the diagnostic base and adds its own
  exact request facet at the existing delivery boundary. Its two inline
  checks remain explicitly named debt until the await contract is exact.
- `test/seon/context_blocks_fixture.clj:201`: the debug test waited for a
  terminal turn that was not observed before the suite liveness bound.
  `test/seon/test_support.clj:515` was also the reporter boundary in the two
  earlier no-tally runs. These paths were not edited.
- `test/seon/render_source_test.clj:299` is outside this lane's test paths
  and still expects the retired namespace-owner case. The later render-2
  source/test owners also remain outside this cut.
- Page/history tests additionally observed refused old turn-edge retractions,
  absent expected neighbours/evaluations, component-schema fixture refusals,
  and unavailable SCI admission caps. These are unresolved observations;
  a final tally below reports them without relabeling them as green.

### Step-6 consumer debt census

The following is a dated source-derived census. Every inline base-three
check has its callee named in a `;; debt:` comment. Locally produced errors
name their exact facets; database, turn, configuration, SCI and await
pass-through debts must be removed when those callees land exact contracts.

| Callee or explicit call chain | Consumer locations |
|---|---|
| `seon.await/await!` | `src/seon/render/web.clj:2745`, `src/seon/render/web.clj:2782` |
| `seon.cluster.prompt/agent-calibration` | `src/seon/render/transcript.clj:1547`, `src/seon/render/transcript.clj:1914` |
| `seon.cluster.prompt/prompt` | `src/seon/render/transcript.clj:1616`, `src/seon/render/transcript.clj:1622` |
| `seon.cluster/ensure-entity!` | `src/seon/render/web.clj:3080` |
| `seon.config/effective` | `src/seon/render.clj:74`, `src/seon/render.clj:133`, `src/seon/render/transcript.clj:893` |
| `seon.db/database-value-identity` | `src/seon/render/data.clj:215` |
| `seon.db/db` | `src/seon/render.clj:109` |
| `seon.db/index-page` | `src/seon/render/data.clj:153`, `src/seon/render/data.clj:182` |
| `seon.db/pull` | `src/seon/render/data.clj:105`, `src/seon/render/data.clj:217`, `src/seon/render/transcript.clj:822`, `src/seon/render/transcript.clj:932`, `src/seon/render/transcript.clj:2140`, `src/seon/render/transcript.clj:2396`, `src/seon/render/walk.clj:167`, `src/seon/render/walk.clj:438`, `src/seon/render/walk.clj:466`, `src/seon/render/web.clj:1704` |
| `seon.db/q` | `src/seon/render/transcript.clj:697`, `src/seon/render/transcript.clj:698`, `src/seon/render/transcript.clj:829`, `src/seon/render/transcript.clj:891`, `src/seon/render/transcript.clj:1781`, `src/seon/render/transcript.clj:2048`, `src/seon/render/walk.clj:419`, `src/seon/render/web.clj:1742` |
| `seon.db/q / seon.db/pull-many` | `src/seon/render.clj:722`, `src/seon/render/transcript.clj:923`, `src/seon/render/transcript.clj:1153`, `src/seon/render/transcript.clj:1362`, `src/seon/render/transcript.clj:1668`, `src/seon/render/transcript.clj:1816` |
| `seon.db/q / seon.db/pull-many; seon.eval/of-agent` | `src/seon/render/transcript.clj:2342` |
| `seon.db/transact! / seon.turn/system-turn` | `src/seon/render/web.clj:2466`, `src/seon/render/web.clj:2994`, `src/seon/render/web.clj:3384`, `src/seon/render/web.clj:3640` |
| `seon.eval/of-agent` | `src/seon/render/transcript.clj:2348`, `src/seon/render/transcript.clj:2351`, `src/seon/render/walk.clj:995` |
| `seon.render.data/entity-observation via seon.db/pull` | `src/seon/render/web.clj:922` |
| `seon.render.transcript/agent-history via seon.db/q` | `src/seon/render/transcript.clj:976` |
| `seon.render.transcript/ledger-effects via seon.db/q` | `src/seon/render/transcript.clj:1801` |
| `seon.render.walk/acquire-entity via seon.db/pull` | `src/seon/render/web.clj:1385`, `src/seon/render/web.clj:1692` |
| `seon.render.walk/history via seon.eval/of-agent` | `src/seon/render/web.clj:2518` |
| `seon.render/acquire-context! via seon.turn/system-turn` | `src/seon/render/transcript.clj:1432`, `src/seon/render/transcript.clj:1672`, `src/seon/render/transcript.clj:1723`, `src/seon/render/transcript.clj:1955`, `src/seon/render/transcript.clj:2338` |
| `seon.render/render-call via seon.db/error-result` | `src/seon/render/transcript.clj:1593`, `src/seon/render/web.clj:489`, `src/seon/render/web.clj:495`, `src/seon/render/web.clj:1031` |
| `seon.sci.kernel/invoke` | `src/seon/render.clj:1080` |
| `seon.turn/changed-reads` | `src/seon/render/transcript.clj:2176`, `src/seon/render/transcript.clj:2178` |
| `seon.turn/opening-db` | `src/seon/render.clj:1671`, `src/seon/render/transcript.clj:2068`, `src/seon/render/transcript.clj:2132` |
| `seon.turn/preview-sources` | `src/seon/render/web.clj:1474`, `src/seon/render/web.clj:1477`, `src/seon/render/web.clj:1496`, `src/seon/render/web.clj:1501` |

## Resume boundary after the projection repair

Re-read git status, the preserved owned diff, the replacement AGENTS.md,
and the amended error-conversion PRD end to end. The draft code, schemas,
and tests have **zero retired discriminator references**, zero class markers,
and no base-three consumer check lacking a same-line named debt comment.
The census above contains **71 sites in 25 callee/call-chain groups**.

The completed eleven-namespace iteration on HEAD `00be89d1f` plus the owned
paths reported **113 tests / 792 assertions / 49 failures / 19 errors**,
exit 1. It ran these existing namespaces:

```text
seon.render.data-test seon.render.transcript-test seon.render.web-test
seon.render.walk-test seon.render.faults-test seon.render.history-test
seon.render.page-review-test seon.render.retained-test
seon.render.root-pull-test seon.render.runtime-test
seon.render.transcript-run-test
```

`seon.render-test` does not exist. The additional `seon.render.web-debug-test`
was observed in the preceding expanded run, which exited 124 at its
liveness bound as recorded above. The later targeted data run was interrupted
by TERM during the orchestrator's lane pause; it did not produce a tally.
After the complete tally, data assertions were strengthened to inspect the
actual offending subject/cursor/snapshot and the native read operation.
Two retained-renderer fixture lookup strings were corrected to symbols,
and the synthetic program fixture now hands `db/db` to the canonical
`program-fn-row` helper. These final test-only corrections have not completed
a fresh run. No green claim is made for them.

After `4806aad03` landed, the resumed shared-tree command used all 23 owned
paths from the source/schema/test census and the same eleven namespaces:

```text
bin/test-fast --paths <the 23 owned paths above> -- <the eleven namespaces above>
HEAD: 9ad085939
exit: 64
Incomplete --paths overlay; add changed caller files:
  src/seon/error.clj
  src/seon/test/runner.clj
  test/seon/error_test.clj
```

These are dirty foreign paths. No foreign file was added to the overlay.
The new AGENTS.md item 12 explicitly requires stopping here; no replacement
worktree was created. The old lane worktree was removed after verifying its
JVM had exited and its test-run directory was empty. The resumed command
launched no test JVM. Earlier load commands returned `:loads`; current HEAD
has not been re-probed past this stop boundary. The production draft was
covered by the completed iteration; final debt-comment placement is the only
subsequent production-source difference.

**Source commits remain owed.** The 23 draft source/schema/test files are
preserved for the next admitted run and per-file commits. This checkpoint
commits only this landing note, rather than claiming the required per-commit
verification completed. The constructor pass-through and leaf changes are
still 1a debt: the drafts merge domain members after the existing constructor,
so the old constructor cannot discard their facets or raw offending values.

**Cold proof owed:** the orchestrator's path-limited cold gate over all owned
namespaces, including web-debug, then the platform gate, and the reset/adoption
and browser observation. RESET NEEDED for the retired stored attributes and
updated facet declarations; no migration, default lifecycle operation, cold
gate, or platform proof was performed by this lane. Remaining failed history,
page and fixture observations above still require verification; they are not
all attributed to the repaired projection stall.
