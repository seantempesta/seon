---
type: notes
status: reviewed; fix-forward
created: 2026-10-07
lane: s2a-review-codex
---

# Landed S2a diff review — Codex

Reviewed `25bb94be02d7ca720f7d86dd0bc0663d4ba6213e` against its first parent, using design-notes.md v5 and
code-notes.md. **Verdict: fix forward.** Keep the deletion and the lazy context view. Fix the five missing
contracts, add the specified refusal regression, finish deletion cleanup, and obtain paired parent/candidate
failure evidence before calling the six reds identical. No evidence here warrants restoring the deleted views.

## Findings, ranked

### P1 — five changed functions have no contract

The installed, baseline-aware `seon.contracts-compile-test/check` reports `:incomplete-contract` for:

| Definition | Line at reviewed commit |
|---|---:|
| `seon.render.transcript/session-header` | transcript.clj:1090 |
| `seon.render.web/debug-query` | web.clj:224 |
| `seon.render.web/debug-query-strings` | web.clj:265 |
| `seon.render.web/debug-response` | web.clj:3199 |
| `seon.turn-test/virtual-turn-fixture` | turn_test.clj:515 |

These functions existed before, but their bodies changed in this slice, so the exception for unchanged legacy
functions does not apply. The two-source check took 267 ms; the complete nine changed Clojure files took 134 ms
and found the fifth, test-side helper. Result handles: `result/e3f4ee4482e81`, `result/e1b329f8821d8`.

No declared contract failed compilation. `shell`, `derive-context!`, and `sent-context-html` retain their contracts.
The checker additionally reports `:uncontained-throw` on `debug-response`, row 3208, three paths. Its suggested
blanket local catch/error union conflicts with the current entrant-boundary law; do not mechanically follow it.
That checker-policy defect already has task
`seon.contracts-compile-test/edit-hook-refuses-a-lawful-throw-of-a-registered-error` (`6ab2051a63aa`).

New task: `seon.render.web/s2a-changed-functions-lack-complete-contracts` (`8cfde74f7351`).

### P2 — the required refusal regression did not land

Design §4.5 explicitly requires the context refusal case. The new
`seon.render.web-context-test/the-context-view-is-computed-only-when-opened` covers successful opening only;
there is no corresponding missing-config/missing-cluster context test in that file or the landed additions.

**The installed refusal behavior works in the read-only probe below:** prompt returns
`:seon.error/type :seon.cluster.prompt/error` with `:seon.cluster.prompt/missing-cluster "root"`; the installed
`debug-response` answers status 200 with a `.seon-data-panel` showing the refusal. Basis delta 0, 4 ms.
This is a regression-coverage gap, not a reproduced broken refusal renderer. It used the installed functions and
an immutable copy of their request; no Var, SCI environment, live configuration or connection was modified.

New task: `seon.render.web/s2a-context-refusal-has-no-regression` (`6afb1109d853`). Proposed proof name:
`seon.render.web-context-test/a-refused-context-renders-through-the-value-printer` (not installed).

### P2 — the cut orphaned `seon.cluster.prompt/agent-calibration`

The independently landed Opus review named this additional candidate; I verified it against both revisions.
Parent transcript.clj:1461 and :1768 call it by `requiring-resolve`. Both callers disappear in S2a. At the candidate,
`git grep agent-calibration -- src test` finds only its definition at prompt.clj:140 and five calls in two tests.
The production prompt uses `model-calibration` directly. Retire the orphan wrapper and convert its test-only
calls while preserving the underlying live calibration behavior. Existing task, verified in default:
`seon.cluster.prompt/agent-calibration-has-no-caller-after-the-debug-cut` (`dc19491ceb10`).

### P2 — “identical on the parent” is not established by the available evidence

The six named red test bodies are byte-identical between parent and candidate (top-level test spans compared,
including the golden test's metadata). That does **not** establish identical failures: their callees and fixtures
changed. The landing note supplies no paired run identities, failure maps, or candidate input digests. Default
contains only two recorded runs, `385398dee21f` and `52a8f4ea10fe`, at 08:55 and 08:56 UTC, neither with a git-sha
or named test selection establishing this comparison; their loaded commit id is
`6ac5f960-0dad-5559-b783-9c28d609b8eb`. The reviewed commit was made at 12:41 UTC.

Therefore the claim is **unverified**, not proven false. Per the assignment, this lane launched no test runs.
Root must run the six exact tests listed below on both archived revisions and compare assertion/error data,
including timeout classification and tested-input provenance. Existing task
`seon.render/landing-reds-2-render-bow-outs` covers the render-red triage; this note does not reclassify the turn
reds without that evidence. The separate claim about the old turn-details strip red is also not reproduced here.
The later Opus review records specific tasks for the four render reds (`d9178db45287`, `a83707de68e0`,
`5135c292e279`, `049c5dd83424`) and existing tasks for the turn reds (`ec9e966a44a9`, `c1c8c0a34adc`);
those task records are not substitutes for paired runs.

### P3 — orphan JavaScript and stale current documentation remain

`resources/public/js/seon-graph.js` still contains:

- normalization of walk-only `level`, `groups`, `group`, `expanded`, `more` fields (28–33);
- reads of deleted `--seon-graph-level-N` variables (124–129) and walk-only styles;
- walk/concentric and grouped-tree selection (249–263), `layoutTree` (273 onward), and group/more interactions.

The surviving producer `seon.render.web/graph-model` (852–894) emits node id/label/href and edge
id/source/target/attribute; none of those walk fields. Delete the orphan branches while preserving the shared
entity-graph script. **The CSS deletion does not itself throw:** the level lookup tests for an empty string and
omits that style. The required base colors remain. No graph crash was reproduced or inferred from the missing
variables.

Current claims left in the edited `docs/seon/specs/render-dedup.md`: `nav-click` is still listed at 105;
160–162 claim page-load acquisitions and live `reread-summary`; the estimate and budget discussion at 169–181
still retain ledger/outline/findings; 186 describes a current `render-session` call; 207–210 still asks whether to
delete the ledger. `transcript.clj:1139` also retains the orphan “The outline” banner. The open issue
`docs/seon/issues/debug-reread-summary-duplicates-shown-value-and-diff-owners.md` now has a deleted subject.
Historical references explicitly describing the deletion, and the historical repro in
`resources/seon/tasks/seon.instrument.edn:33`, are legitimate history, not unresolved executable callers.

Runtime task cleanup is still outstanding, as the landing already disclosed: `a9a7bb6e6f29` and `ec70b6017334`
still have must-reads `seon.render.transcript/turn-kind` and `seon.render.transcript/ledger-effects`, respectively.
Neither task has an outcome in the pulled value. `17390920f38d` also remains, but its `seon.eval/of-agent`
must-read is still live. Root owns closing these; this review did not edit another lane's task declarations.

New task: `seon.render.web/s2a-deletion-leaves-walk-javascript-and-current-claims` (`17cca8bb7af6`).

Two further references identified by the later Opus review were checked directly: the ui-print task's must-read
at `resources/seon/tasks/seon.render.web.edn:35` still names `seon.render.web/debug-value-html`. S2a edited this
same set to remove `node-attributes-html`; it should also remove the already-stale member. The former Var was
deleted by `b55945b57`, before S2a, and `ns-resolve` on default confirms it absent. The installed task
`seon.render.web/pages-print-values-instead-of-the-printer-html-face` (`603a5566cb6c`) still carries that member.
Also, `docs/seon/schedule/README.md:122` and `triage.tsv:48` still schedule the task
`seon.render.transcript/fault-problems-counts-occurrences-in-prose`, whose declaration S2a deleted. Root owns
that schedule; this review did not edit it.

## What verified

The design holds in its principal composition: one on-demand call to the turn's prompt function, then the existing
value printer's HTML face. No new renderer, cache or assembly was added. This is a BEHAVIOR CHANGE for the page,
with the lazy success proof present. No target S4 API was assumed installed.

- Compared the removed defn names against `src test script resources .agents docs/seon` in the committed archive,
  including qualified and alias-shaped references. No remaining executable call to a removed definition found.
  The removed schema render pair was converted; task-reference exceptions are enumerated above.
- `:seon.render.transcript/prefix-subject` is absent from the live default projection, not merely its source EDN.
  The ledger switch and old prompt-preview keys are absent from live source roots. This does not reconstruct
  publication/adoption evidence from the landing; it establishes their installed absence after root's restart.
- Exact CSS-token census: 86 distinct selectors disappear from input.css; none remain as emitted tokens in
  committed `src`, `test`, or public JS. The JS custom-property lookup above is a separate surviving reference.
- Old `?prompt=true`, `?outline=true`, `?turn=does-not-exist`, and `?n=2` GETs, with Datastar header, all answer 200
  and the plain closed-context page, without a printer panel: 31.59, 6.18, 5.21, 4.02 ms respectively.
- Header controls, Record, runtime transcript renders and the entity ref graph have surviving callers and were
  explicitly retained by v5. Their survival is not evidence of incomplete deletion.

## Default HTTP measurements

`bin/seon status` identified PID 13771, start `2026-10-07T12:41:46.451Z`, source archive exactly `25bb94be0`.
The measurement followed a recorded root MCP evaluation, so root's history had changed. A single following JVM
measurement form issued these four GETs sequentially, with no settlement between them. All used
`datastar-request: true`; the ordinary page's response branch is the same with or without that header.

| GET | ms | Process-wide allocated bytes | prompt calls | acquisition calls | basis delta | status |
|---|---:|---:|---:|---:|---:|---:|
| page, first after write | 45.966 | 33,246,080 | 0 | 0 | 0 | 200 |
| page, warm | 6.364 | 3,215,544 | 0 | 0 | 0 | 200 |
| `?sent=true`, first after write | 607.253 | 1,080,104,168 | 1 | 1 | 0 | 200 |
| `?sent=true`, warm | 17.926 | 22,763,976 | 1 | 1 | 0 | 200 |

Whole form: 692 ms, `result/ed6e95e38c0e2`. Page HTML ≈803 tokens; context HTML ≈6,290 tokens using length/4
(wire markup included, not a claim about visible prose). The context responses contain the value-printer panel.
Counter cells were verified installed before measurement. JVM-wide counter/allocation deltas can include unrelated
threads; no counter reset or Var redefinition was used. The observed zero/one pattern matches source and the
central regression, but is not an isolation guarantee under arbitrary concurrency.

The context cost remains severe: about 1.08 GB cold, 22.8 MB warm. Design §3 explicitly accepts this only until S4.
That exception remains an S4 obligation, not a green performance verdict. The page's first-call allocation also
exceeds 10 MB; warm page allocation does not. There is no parent live measurement from this reviewer, so this is
not a measured speedup claim. The landing's 6.4 s/1 s default page numbers were not reproduced on the installed
candidate. Store footprint delta and retained-heap attribution were not measured; HTTP basis deltas were zero.
Initial status: heap used 1,181,383,968, heap live 728,399,136, heap max 10,997,465,088 bytes.

Measurement form, JVM mode, agent root, cluster default (host observation, no definitions):

```clojure
(let [conn (seon.cluster.boot/connection "default")
      client (java.net.http.HttpClient/newHttpClient)
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      counts (fn []
               (into {} (map (fn [c] [(:seon.profile/sym c)
                                      (:seon.profile/calls (seon.profile/observation c))]))
                     (filter #(contains? '#{seon.cluster.prompt/prompt seon.render/acquire-context!}
                                         (:seon.profile/sym %)) (seon.profile/cells))))]
  (mapv
   (fn [path]
     (let [request (-> (java.net.http.HttpRequest/newBuilder
                       (java.net.URI/create (str "http://127.0.0.1:7994" path)))
                      (.header "datastar-request" "true") .GET .build)
           before (counts) basis (seon.db/basis-t (seon.db/db conn))
           allocated (.getTotalThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean)
           started (System/nanoTime)
           response (.send client request (java.net.http.HttpResponse$BodyHandlers/ofString))
           ms (/ (- (System/nanoTime) started) 1e6)
           allocation (- (.getTotalThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean) allocated)
           body (.body response)]
       {:path path :ms ms :allocated-bytes-process-wide allocation :status (.statusCode response)
        :calls (merge-with - (counts) before)
        :write-delta (- (seon.db/basis-t (seon.db/db conn)) basis)
        :panel? (clojure.string/includes? body "seon-data-panel")
        :estimated-tokens (quot (count body) 4)}))
   ["/agent/root/debug" "/agent/root/debug"
    "/agent/root/debug?sent=true" "/agent/root/debug?sent=true"]))
```

Refusal probe (`result/e368f83c3a29c`, 4 ms):

```clojure
(let [conn (seon.cluster.boot/connection "default") database (seon.db/db conn)
      instance (get @seon.operator.runtime/running-instances "default")
      ctx (:seon.sci.eval/ctx instance) environment (seon.env/of ctx)
      service (assoc (:seon.render.web/view instance)
                     :seon.store/connection-object conn :seon.agent/id "root"
                     :seon.sci.admit/caps (:seon.sci.admit/caps environment)
                     :seon.sci.eval/ctx (dissoc ctx :seon.env/environment :seon.sci.eval/projection-state))
      request (#'seon.render.web/debug-turn-request database conn "root" (:seon.sci.admit/caps service) service)
      result (seon.cluster.prompt/prompt database request)
      response (#'seon.render.web/debug-response service 'my.agents.root "root"
                 {:query-string "sent=true" :headers {"datastar-request" "true"}})]
  {:refusal (select-keys result [:seon.error/type :seon.cluster.prompt/missing-cluster :seon.error/message])
   :status (:status response) :panel? (clojure.string/includes? (:body response) "seon-data-panel")
   :error-key-shown? (clojure.string/includes? (:body response) "missing-cluster")
   :basis-delta (- (seon.db/basis-t (seon.db/db conn)) (seon.db/basis-t database))})
```

## Checks, foreign boundaries, and central test list

Claimed this note with `bin/lane claim s2a-review-codex` before writing. Only this note is changed by the review.
Landed slice: 20 files, +261/−3482; source Clojure net −1522, tests net −905. Review source/test delta: zero.

Static checks use `scratchpad/s2a-review-codex/archive`, made by `git archive HEAD` while HEAD was `25bb94be0`,
plus this note. Imported ignored clj-kondo configuration was linked as bin/lane does; no analysis cache was used.
`clj-kondo --lint src script test --cache false --parallel`: **0 errors, 1461 warnings, 4279 ms** initially;
the repeat with this note copied into the archive took 5306 ms, with the same counts.
Baseline-aware contract compilation of all nine changed Clojure files: the six findings above, 134 ms; no
declared-schema compilation failures. Checker was required from the running **committed** archive, never loaded
from the dirty checkout. It reads candidate source as data. Malli pin `43c6dca8d2d8df3162b13725939954807be4a4ed`.
The checker calls `malli.core/function-schema` (reference-code/malli/src/malli/core.cljc:3130), over
`malli.core/schema` at :2596; candidate resource
changes determine the packaged projection. No new dependency seam or production recomputation was introduced.

To repeat the contract audit, call `seon.contracts-compile-test/check` with `::root` set to a candidate archive,
`::sources` mapping each of the nine paths below to its candidate text, `::baselines` mapping the same paths to
`git show 25bb94be0^:<path>`, and `::candidate` from
`(seon.db/carried-projection (seon.db/db (seon.cluster.boot/connection "default")))` only while its resource
population matches that archive. Paths: src/seon/render/{web,transcript}.clj;
test/seon/error_test.clj; test/seon/render/{transcript,web_context,web_debug,web}_test.clj;
test/seon/{run6_stall,turn}_test.clj. No tests are run by that static check.

Foreign boundaries: `contract-check-cost` owns dirty fn/analyzer/checker files; `printer-code` owns dirty printer,
render/value, repl and SCI files; `s5a-fix` owns dirty turn, transcript, MCP, related schema and test files.
All were excluded by the committed archive. No foreign session was resumed, messaged or edited. No worktree,
test JVM, provider request, branch or prototype definition was created; default was not restarted or reset.
During review HEAD advanced to `8985125c9` through documentation-only commits. The final commit candidate is a
fresh archive of that HEAD plus this note; the original archive remains the subject of the S2a contract/diff audit.
Final HEAD-plus-note lint: **5626 ms, 0 errors, 1461 warnings**. All three intervening changed files are
documentation; source, test, script and schema resources remain the reviewed revision's inputs.
The later review's contract list mistakenly includes `shell`: this review's archived source and installed Var
both confirm its existing contract. The fifth actual missing contract is the changed test helper, as above.

Default's startup health was faulted: show-row/shown-failure nil (existing
`seon.sci.eval/a-stack-overflow-in-the-error-path-reaches-shown-failure-as-nil`, `81b1ff4eed9e`), branch-custody
recording fault `3d2fb1b41dc8`, entity-emission missing database (existing task set in runtime_status),
derive-base-ctx 1172 ms (`4a738d4f7138`), and loaded-function-rows cache 0% hit rate/22,968,042 bytes. These are
not evidence of an S2a regression. Stale REPL-skill claims are already tracked by
`seon.turn/repl-and-flow-skills-describe-retired-loop-contracts` (`53e2a265edb0`).

Probe mistakes, not product findings: a task pull accidentally requested uninstalled `:seon.task/status`
(`result/e73bc6b6acbbd`, 10 ms, returned read refusals); corrected to installed keys. A malformed compile form
with an extra closing parenthesis returned “The run was evaluated but not recorded,” applied=true/tx-count=0;
the corrected form produced the 134 ms checker result. A missing-agent GET returned the expected 404 in 2.817 ms,
which is not the prompt-refusal case. The first lint attempt lacked imported config due to a wrong shell cwd;
the successful archive lint above supersedes it.

Browser paint is unverified: CUA could not create an in-app browser; browser inventory returned `[]`. HTTP panel
and source checks do not prove browser paint or click/morph behavior. This is a concrete tool limitation, not a
claim that HTTP failed.

Root's one focused list, after fixing contracts and adding the refusal member:

- `seon.render.web-context-test/the-context-view-is-computed-only-when-opened`
- `seon.render.web-context-test/a-refused-context-renders-through-the-value-printer` — proposed, add first
- `seon.render.web-test/inspecting-the-page-writes-no-run-evaluation-or-fault-facts`
- `seon.render.web-test/each-agent-has-an-isolated-debug-route`
- `seon.render.web-test/debug-algorithm-carries-the-render-evaluation-inputs`
- `seon.render.web-debug-test/turn-details-use-the-loop-opening-and-exact-segments`
- `seon.render.web-debug-test/saved-history-preserves-shown-text-with-numeric-lookups`
- `seon.render.web-debug-test/debug-links-omit-defaults-and-round-trip-every-override`
- `seon.turn-test/virtual-turns-use-the-proc-and-compaction-is-agent-scoped`
- `seon.run6-stall-test/a-stalled-session-header-names-its-provider-refusal`
- `seon.contracts-compile-test/a-new-or-changed-function-needs-a-complete-contract`
- `seon.cluster.prompt-test/calibration-uses-the-agents-recent-attempts-and-config-prior` — convert orphan calls
- `seon.cluster.prompt-test/the-prompt-reads-the-cluster-its-caller-names` — retain live prompt refusal coverage

Paired parent/candidate classification, same central round and matched fixture/input provenance:

- `seon.render.transcript-test/the-transcript-is-whole-and-the-ai-boundary-elides-it`
- `seon.render.web-context-test/context-and-page-do-not-demand-the-render-proc`
- `seon.render.web-context-test/root-page-blocks-read-attribute-bounded`
- `seon.render.web-context-test/unrelated-adoption-preserves-pages-with-an-unchanged-sci-snapshot`
- `seon.turn-test/a-definition-changed-since-the-run-read-it-is-refused-at-the-writer`
- `seon.turn-test/a-form-whose-facts-are-refused-says-it-was-not-admitted`

Run the armed zero/one counter member without competing prompt/page work, as the design requires.
No central results are claimed by this lane. **RESET NEEDED: no; review-only documentation needs no restart.**
