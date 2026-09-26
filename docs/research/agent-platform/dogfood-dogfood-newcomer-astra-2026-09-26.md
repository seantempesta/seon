---
type: report
status: complete
created: 2026-09-26
scope: dogfood-newcomer-astra, MCP SCI newcomer exercise
---

# First 30 minutes: newcomer Astra

The live system supplies a branch-hosted agent, durable definitions and tests, and queryable program rows. Ordinary Clojure forms plus the database owner were enough to discover much of it; the attempt to fix a first-party issue stopped at an oversized reaching-test set.

**Outcome:** no issue fix or merge proposal was produced. The selected OPEN issue reached 1,626 tests, exceeding the preamble's approximately 50-test stop rule. A separate scratch function and test passed, proving the basic entrance rather than the issue fix. Nothing was merged. Branch `dogfood-newcomer-astra` was retired successfully.

## Method and limits

- Read the preamble first. Delayed opening CLAUDE.md and the prior dogfood report until all 41 exploratory requests ended. The assignment already supplied AGENTS instructions in conversation, so this is an API-discovery exercise, not a claim of literal absence of all prior context.
- All runtime work used MCP SCI with explicit root `/Users/sean/src/seon`, cluster `default`, and my branch. No JVM eval, source-file API research, source edits, other lane sessions, shell tests, or worktrees.
- “First 30 minutes” is a window: the recorded exploratory forms ran from 16:03Z through approximately 16:07Z, rather than waiting out 30 minutes. Report review and writing followed.
- Counts are submitted top-level forms/MCP eval requests. A `do` wrapper counts once; nested calls do not. The appendix preserves all 41 forms in order. Times are client wall milliseconds, including MCP overhead, not pure function times.
- Branch creation: 429 ms, from Datahike commit `6ab7ecc7-f598-5423-9cf0-75fcc2c7a47d`, basis 536871326. Hosted agent: `mcp/dogfood-newcomer-astra`; namespace: `user`.
- Baseline runtime was alive but **faulted**: 1 open release-admission ClassCastException, 18 failed tests, 76 unknown failed-test observations, 1 unowned namespace. PID 19859, start 2026-09-26T16:02:02Z. This did not stop independent probes.
- Checkout HEAD read only after exploration: `85b4598455012f688a59840660de5445b9674187`. This is not proof of the loaded source revision; that revision was not established. Memory was not measured. Browser paint, publication, adoption and merge were not exercised.

## Discovery score

“Learned” distinguishes a named entrance from successful execution.

| Need | First useful form number | Verified by | Result |
|---|---:|---:|---|
| List namespaces | 5 | 5 | `all-ns` + `ns-name`; printed list cuts after 32, 440 more |
| Read function docs | 4 | 6 | `dir` teaches `callers`; `doc` gives a runnable example |
| Read function source | 18 | 18 | Datalog over `:seon.fn/sym` and `:seon.fn/source`; ordinary `source` failed twice |
| Find callers | 4 | 19 | Names `seon.error/explain-problem`, span, arity, explicit outside-graph unknown |
| Find reaching tests | 4 | 20 | Named set; 1,626 tests for selected helper |
| Define a function | 1 | 29 | Contracted `defn` persisted and returned a Var |
| Run tests | 1 | 32 | `my.test/run`: one assertion passed, no failures/errors |
| See full error cause | — | 28 partially | Catch-before-escape recovers messages/data; uncaught error has no discovered full-cause handle; `*e` nil |
| Query database | 1 / 11 | 12 | Implicit custody works; source query succeeds at 18 |
| Read my task | — | 12 / 37 | Plan exists but has no objective/steps; external assignment was not present |
| Read open issues | 14 | 15 / 17 | `seon.issue/issues`; must bypass issue rendering to see problem body |
| Message root | 1 | 41 | Send returns message `086d71ef84fa`; recipient-side receipt not independently queried |

Ten of twelve needs had a usable entrance in this exercise. Full uncaught-cause retrieval and recovery of the externally supplied task remained unfulfilled. The caught-cause workaround does not prove post-failure inspection.

## Ranked findings

Exact numbered forms and trimmed outputs are in the appendix. Suspected causes below are hypotheses from behavior unless explicitly identified as prior evidence.

| Severity | Finding and evidence | Time (ms) | Suspected mistaken model / existing evidence |
|---|---|---:|---|
| blocks work | F20: `tests-reaching` returns 32 names plus “1594” more for `collection-member-problem`. Safety rule prevents attempting its first-party redefinition. | 3519 | Reach is too broad for a small edit; do not claim the source cause was diagnosed here. Existing [saturated-reach issue](../../seon/issues/a-first-party-redefinition-gates-on-saturated-reach-interpreted-in-sci-and-is-refused.md). |
| blocks work | F38–39: nested uncaught exception shows “newcomer cause” and a platform contract finding; next `*e` is nil, no error result handle shown. F27: `Throwable->map` unresolved. | 439 / 551 / 452 | Display is not an inspectable retained exception. Existing [error parity issue](../../seon/issues/agent-repl-error-output-diverges-from-clojure-main-for-every-ordinary-mistake.md). F28 can recover cause messages/data only inside an authored catch. |
| slows work | F12: self query with wildcard pull returns **181,252 characters**, embedding previous turns including the enormous find-doc result. Approx. 45k tokens at 4 chars/token, not tokenizer-measured. | 772 | Nested declared rendering bypasses the outer budget. Existing [raw-pull issue](../../seon/issues/a-raw-pull-inlines-nested-declared-render-text-past-the-floor.md). |
| slows work | F32: one assertion takes 3456 ms internally / 4276 ms client wall. Profile: `seon.test/run` 3425 ms, `clean-request-handle` 2527 ms, `transact!` ×12 3156 ms inclusive. | 4276 | World preparation dominates the tiny body. Existing [test preparation issue](../../seon/issues/test-world-preparation-spends-seconds-in-config-apply-and-unmemoized-reads.md). Inclusive times overlap, not additive. |
| slows work | F7–10: bare `source` / `apropos` unresolved; qualified `source` prints “Source not found”. A DB query finds exact source at F18. | 423 / 408 / 517 / 548 | Clojure discovery helpers do not expose durable program rows. Prior report cycle 10 already records this. |
| confusing | F16 hides issue problem body behind status/test text and says “0 turns remaining”. F17 `vec` reveals the complete problem. | 680 / 733 | An issue read is treated as a status request. Related [issue render entrance issue](../../seon/issues/the-issue-ai-render-no-longer-teaches-its-requery-form.md). No evidence that my own turn budget was zero. |
| confusing | F14 loses the request key inside `:in`; the `seon.issue/status` key remains discoverable only in arglist destructuring. F31 `my.test/run` `:in` and `:out` are declaration-absent error text, although it runs. | 546 / 605 | Documentation transforms schema names without preserving request structure; [existing doc issue](../../seon/issues/doc-replaces-every-schema-keyword-so-request-keys-disappear.md), prior report cycle 10. |
| confusing | F22–24: OPEN docstring issue names `my.fs/write` and `my.shell/run`; doc unavailable and exact source query returns empty. | 845 / 563 / 722 | Issue lifecycle/citations do not establish installed applicability. A stale issue is a hypothesis, not a proven deletion. Existing issue retained unchanged. |
| confusing | F12/F37: hosted plan has no assigned objective or steps. Help says “Your plan is your instructions”. | 772 / 643 | External task handoff is not demonstrated by hosting an agent. This may be an entrance/setup limitation rather than a runtime defect. |
| confusing | F35 tries to select `:seon.repl/rows` from `dir` and gets `[]`, while F36 visibly shows rows. | 669 / 626 | A printed map-like directory is not necessarily that map as the macro's return value. Runtime type not probed; no stronger cause claim. |
| ugly | F4 directory: **25,923 characters** (~6.5k tokens); F9 find-doc: **141,224 characters** (~35k tokens). Includes plumbing and very broad source matches. | 797 / 662 | Discovery requires knowing narrow names before one can ask narrow questions. The qualified search works but overwhelms the newcomer. |
| slows work | F1 first help: 1595 ms; F29 tiny definition: 1235 ms wall. | 1595 / 1235 | No individual armed profile was returned for these requests; cause and memory cost remain unmeasured. |

## Attempt to resolve an OPEN issue

1. F13–17 discovered `a-collection-member-refusal-does-not-name-the-members-index` in system facts. It describes a helper that narrows a collection violation to its member but loses the member index.
2. F18 read the helper's exact current source from the database. F19 established its direct caller. F20 established its large reaching-test set. I stopped this change before any production redefinition, as the preamble requires; no entry gate was run on these 1,626 tests.
3. Looked for a smaller documentation issue through the same system query (F21–24). Its cited installed functions could not be found. Examined `root-empty-plan-read-shows-nil` (F33–37); the current empty plan read showed explicit empty data, so that probe did not justify changing it.
4. F29–32 used only a private scratch function/test to verify the advertised authoring entrance. Run `ee2c316d7de9`: 1 pass, 0 fail, 0 error, member began and ended, terminated true.
5. F41 sent root the selected-issue evidence and asked for bounded regression scope. This is **a blocked-work message, not a merge proposal**. Calling `propose-merge!` with unrelated scratch code would falsely connect the test to the issue, so I did not do that.
6. MCP branch retire returned `:seon.dev.mcp/retired? true`. A subsequent roster list confirmed the branch absent. That final tool batch took 24.3 seconds (two near-zero shell reads followed by the roster request); individual roster timing was not captured. This unexpected slow read was not repeated or diagnosed. The scratch rows were not written back. No source/test file diff; no production fix claimed.

## Missing entrances: tried and failed

- Ordinary `source` for installed first-party functions: unresolved bare helper, then “Source not found” qualified. Datalog is a successful alternate but requires knowing program attributes.
- Bare `apropos`: unresolved. Qualified `repl/apropos` works after require.
- Post-failure cause inspection: `*e` nil immediately after the error; `Throwable->map` unresolved; no result handle printed. An in-form catch is only a preventive workaround.
- Read the assigned task from the hosted plan: self query and `my.plan/plan {}` show an empty plan.
- Compose the visibly map-shaped `dir` response through `:seon.repl/rows`: yields empty output. This is a discovery mismatch, not proof that clojure.main's printing macro should return data.
- Read the named OPEN issue's `my.fs/write` docs/source: no documentation and empty DB source result.
- Execute the selected first-party edit under the stated safe reaching-test bound: test discovery exceeds it before an edit. No safe complete fix/merge path was demonstrated.

## What the delayed documents added

Reading CLAUDE.md and the prior report after F41 revealed these discoverability gaps. “Not discovered” is limited to this trace, not a proof that a full database search could never find the information.

| Information only learned or clarified afterward | Missing link in the newcomer path |
|---|---|
| Program rows vs data rows; branch custody is a default world, not a security boundary | Help provides examples but no concise model connecting branch, agent, and shared program |
| The assignment's plan must be handed into the hosted world | Help asserts the plan is instructions, yet this hosted plan was empty |
| `my.program/history` is a practical source retrieval path | Directory summary says history/provenance; no `source` failure points to it |
| `my.test/check {:seon.test/changed [...]} ` is the focused change-check entrance | Help teaches only `my.test/run`; issue done condition eventually mentions check, without a workflow |
| Known saturated reach, error retention and source-discovery defects already have reports | Ordinary failures do not link their known issue or distinguish workaround from intended API |
| First-party changes, tested merge, root acceptance, and write-back form one workflow | Directory exposes the functions, but no short end-to-end example; malformed request docs impair execution |
| Strong constraints on publication/adoption proof, ownership and terminal test evidence | These are operational laws in CLAUDE.md, not established by help or the empty plan |

No new issue files: searched existing issues by source/error/discovery/task/render keywords and matched the established defect classes above. Unproven handoff and directory-return questions remain explicitly limited findings in this report.

## What worked well

Branch creation, sequential SCI evaluation and retirement worked. Help was immediately useful. `*ns*` showed `#namespace[user]`. Qualified apropos found issue discovery. Database queries supplied custody automatically and exposed exact source. Caller evidence named spans and unknown scope. Contracted defn, deftest and my.test/run completed without source files. Ordinary caught exception causes were inspectable. Messaging returned a durable identity. The runtime stayed usable after ordinary failed forms.

## Landing evidence

Only this report is owned and released. Production source/test net lines: **0/0**. No boot, reload, publication, platform gate or bulk suite was run; none is implied green. No hook pause was needed for a report-only file. Runtime baseline faults predated the probes; no foreign edit boundary was used as a reason to stop. Client wall timings and output sizes are preserved below; memory and loaded Git revision remain evidence gaps.

## Transcript appendix

Each entry preserves the exact submitted form, client wall time, event character count, turn id, and an explicitly trimmed output excerpt. Full source and cause evidence appear where short enough; huge output is not copied wholesale into the report.

### F1 — 1595 ms; 3127 characters; turn f39b04fab8ef

```clojure
(help)
```

```text
The prompt shows your namespace user and is drawn for you. Send only ;; thinking comments and forms.
Results are data: chain them with ->>, sort-by, filter, map, and get-in. Functions are callable by their fully qualified symbols.
Forms are evaluated in order, and their results arrive in your NEXT turn. Act on a result only after you have seen it; do not complete a step in the same reply as the form that does the work.
The REPL supplies responses: :value (or :error) is result data, :out is printed text, and :result names the live value. Do not write responses yourself.
result/e... is a real symbol bound to the live value: evaluate it, pass it as an argument, or dig in with get-in and keys.
W
[report excerpt ends; 3127 characters total]
```

### F2 — 542 ms; 16 characters; turn 1acb5a11af3d

```clojure
*ns*
```

```text
#namespace[user]
```

### F3 — 690 ms; 3 characters; turn 0171500e9207

```clojure
(require '[my.program :as program])
```

```text
nil
```

### F4 — 797 ms; 25923 characters; turn 7df25430f48f

```clojure
(dir my.program)
```

```text
{:seon.repl/columns [:sym :arglists :doc :in :out :supplied], :seon.repl/rows [[my.program/accept-merge! ([{context :my.program/context, tested :seon.source/candidate, :as proposal}]) "Merge exactly the program `prepare-merge!` tested into my cluster" [:cat [:map [:seon.source/candidate :seon.store/branch] [:seon.test.run/id :seon.test.run/id] [:seon.test/passed? {:optional true} :boolean] [:seon.source/tally {:optional true} :string] [:datahike/expected-basis-t :int] [:parents [:set :seon.source/commit-id]]]] [:or :seon.db/transaction-report :seon.source/test-evidence-error :seon.error/value :my.program/custody-refused-error] [:my.program/context]] [my.program/breaks ([{database :seon.db/db
[report excerpt ends; 25923 characters total]
```

### F5 — 575 ms; 933 characters; turn 654ff7a20f86

```clojure
(mapv ns-name (all-ns))
```

```text
[seon.cluster.store my.program-query-test seon.render.web-debug-test
 seon.test.gitlink-digest-test seon.oversight
 seon.contracts-compile-test seon.cluster.publication-lock-test
 seon.fs-test seon.cluster.publication-findings-test
 seon.cluster.armed-test seon.oversight-test
 seon.render.page-review-test seon.cluster.publication-serialize-test
 seon.cluster.adoption-record-test seon.instrument-replaced-roots-test
 seon.run4-reader-test seon.reset-edge-parity-test seon.context
 seon.render.floor-test seon.dev.hook-measure seon.test.one-request-test
 seon.sci.kernel seon.test.clean-world-test seon.render.walk-graph-test
 seon.edit-test seon.instrument seon.test-resolution-fixture
 seon.blob-p
[report excerpt ends; 933 characters total]
```

### F6 — 739 ms; 522 characters; turn 60c17808a0ae

```clojure
(doc my.program/callers)
```

```text
{:summary
   "Read direct callers and their declaration spans and recorded call arities.",
 :body "Empty groups are absent; unknown coverage remains explicit.",
 :example
   "(my.program/callers {:seon.program/subject 'seon.turn/open?})",
 :arglists
   ([{database :seon.db/db, subject :seon.program/subject, :as request}]),
 :in [:cat [:map [:seon.program/subject :seon.program/subject]]],
 :out
   [:or :seon.program/breakage :seon.program/read-refused-error
    :seon.program/not-found-error],
 :supplied [:seon.db/db]}
```

### F7 — 423 ms; 204 characters; turn f9aef5f17bb0

```clojure
(source my.program/callers)
```

```text
Execution error (ExceptionInfo) at (REPL:1:1).
Unable to resolve symbol: source
Contract finding: seon.sci.kernel/failure-value returned this error, and none of its declared error alternatives matches it.
```

### F8 — 408 ms; 205 characters; turn 3bb369ace611

```clojure
(apropos "source")
```

```text
Execution error (ExceptionInfo) at (REPL:1:1).
Unable to resolve symbol: apropos
Contract finding: seon.sci.kernel/failure-value returned this error, and none of its declared error alternatives matches it.
```

### F9 — 662 ms; 141225 characters; turn 6a291c8499f2

```clojure
(do (require '[clojure.repl :as repl]) (repl/find-doc "source"))
```

```text
-------------------------
seon.contracts-compile-test/check
([{:seon.contracts-compile-test/keys [root paths sources projection candidate], :or {root .}}])
  Findings for every contract declared in `::paths` (read from disk) or
  `::sources` (path to prospective text), compiled against `::projection` or
  the projection of the checkout at `::root` (default the working directory).
  `::candidate` is a projection already in hand — a live cluster's carried
  projection — used when its forms ARE the checkout's packaged forms, so an
  adopted checkout never rebuilds the population it already holds.
  Empty when every contract compiles.
-------------------------
seon.contracts-compile-test/checkou
[report excerpt ends; 141225 characters total]
```

### F10 — 517 ms; 21 characters; turn d428092bfb01

```clojure
(repl/source my.program/callers)
```

```text
Source not found

nil
```

### F11 — 555 ms; 734 characters; turn 089deb9086f3

```clojure
(doc seon.db/q)
```

```text
{:summary
   "Run a Datalog query over explicit inputs or the current database value.",
 :body "",
 :example "",
 :arglists ([query-or-database & arguments]),
 :in
   [:catn
    [:seon.db/query-or-database
     [:or :seon.db/database-value :seon.error/value :seon.db/query
      :seon.db/query-args]]
    [:seon.db/arguments
     [:*
      {:seon.schema.admission/reason
         "Datahike Datalog bindings carry arbitrary values. The function guard derives input count and database source positions from the parsed query.",
       :seon.schema.admission/exemption
         :seon.schema.admission/polymorphic-boundary,
       :gen/elements [[]]}
      :seon.schema/value]]],
 :out [:or :seon.schema/v
[report excerpt ends; 734 characters total]
```

### F12 — 772 ms; 181252 characters; turn 135c7c0a2524

```clojure
(seon.db/q '[:find (pull ?e [*]) :where [?e :seon.agent/id "mcp/dogfood-newcomer-astra"]])
```

```text
[[{:db/id 37166,
   :seon.agent/branch :dogfood-newcomer-astra,
   :seon.agent/id "mcp/dogfood-newcomer-astra",
   :seon.agent/namespace [:seon.ns/name user],
   :seon.agent/plan
     {:db/id 37167,
      :my.plan/agent [:seon.agent/id "mcp/dogfood-newcomer-astra"]},
   :seon.agent/runtime
     {:db/id 37169,
      :seon.runtime/agent [:seon.agent/id "mcp/dogfood-newcomer-astra"],
      :seon.runtime/turns
        ["Run f39b04fab8ef, opened #inst \"2026-09-26T16:03:31.244-00:00\". It completed.\n\nuser=> (help)\nThe prompt shows your namespace user and is drawn for you. Send only ;; thinking comments and forms.\nResults are data: chain them with ->>, sort-by, filter, map, and get-in. Functio
[report excerpt ends; 181252 characters total]
```

### F13 — 526 ms; 46 characters; turn 5cd951a765e3

```clojure
(repl/apropos "issue")
```

```text
(seon.issue/issues seon.plan/run-issue-tests!)
```

### F14 — 546 ms; 536 characters; turn 89a2d107c66c

```clojure
(doc seon.issue/issues)
```

```text
{:summary
   "Query issues in identity order, optionally restricted by lifecycle.",
 :body "",
 :example "",
 :arglists ([{database :seon.db/db, lifecycle :seon.issue/status}]),
 :in
   [:cat
    [:map
     [[:enum
       {:description "The issues README lifecycle; a closed set."}
       :open
       :resolved
       :superseded]
      {:optional true}
      [:enum
       {:description "The issues README lifecycle; a closed set."}
       :open
       :resolved
       :superseded]]]],
 :out [:vector :map],
 :supplied [:seon.db/db]}
```

### F15 — 704 ms; 5828 characters; turn b94490650a6e

```clojure
(mapv #(select-keys % [:seon.issue/id :seon.issue/title :seon.issue/status]) (seon.issue/issues {:seon.issue/status :open}))
```

```text
[#:seon.issue{:id
    "a-blocking-realization-is-not-bounded-by-the-interrupt",
  :title
    "A blocking realization is not bounded by admission's interrupt",
  :status :open}
 #:seon.issue{:id
    "a-call-preparation-facet-requires-unstorable-candidates",
  :title "A call-preparation facet requires unstorable candidates",
  :status :open}
 #:seon.issue{:id
    "a-callee-macro-kind-change-leaves-callers-host-bound-stale",
  :title
    "A callee's macro-kind change leaves other files' callers' host-bound fact stale",
  :status :open}
 #:seon.issue{:id
    "a-cluster-stop-that-misses-armer-quiescence-keeps-the-route-listener",
  :title
    "A cluster stop that misses armer quiescence keeps the
[report excerpt ends; 5828 characters total]
```

### F16 — 680 ms; 466 characters; turn ca5b936110a3

```clojure
(first (filter #(= "a-collection-member-refusal-does-not-name-the-members-index" (:seon.issue/id %)) (seon.issue/issues {:seon.issue/status :open})))
```

```text
Issue a-collection-member-refusal-does-not-name-the-members-index: still open; 0 turns remaining.
A collection member refusal does not name the member's index
Done condition: (my.test/check
  {:seon.test/changed
   [seon.instrument-test/refusal-value-projection-obeys-the-profile-and-html-keeps-the-whole-value]})
seon.instrument-test/refusal-value-projection-obeys-the-profile-and-html-keeps-the-whole-value: not run
No admitted execution is recorded for this test.
```

### F17 — 733 ms; 4581 characters; turn d31df136d464

```clojure
(vec (first (filter #(= "a-collection-member-refusal-does-not-name-the-members-index" (:seon.issue/id %)) (seon.issue/issues {:seon.issue/status :open}))))
```

```text
[[:seon.issue/title
  "A collection member refusal does not name the member's index"]
 [:seon.issue/problem
  "# A collection member refusal does not name the member's index\n\n`940f4b426` (\"Preserve refusal cause evidence\") made a homogeneous\ncollection violation report the failing MEMBER and its element schema\ninstead of the container-level contradiction. That is the ruled direction:\nthe error-entities PRD's \"Offending value\" row\n(`docs/prds/steward-platform/plan/error-entities-prd-2026-09-17.md:56`) says\nthe fault retains the bounded projection of its own LEAF, and the member\nwording is what `seon.error/explain-problem`\n(`src/seon/error.clj:859-872`) now produces.\n\n`seon.erro
[report excerpt ends; 4581 characters total]
```

### F18 — 548 ms; 697 characters; turn 399a1edbe679

```clojure
(seon.db/q '[:find ?source . :where [?e :seon.fn/sym seon.error/collection-member-problem] [?e :seon.fn/source ?source]])
```

```text
"(defn- collection-member-problem\n  {:malli/schema [:=> [:cat :map]\n                  [:or :nil :map]]}\n  [problem]\n  (let [schema (m/deref-all (:schema problem))\n        schema-type (m/type schema)\n        child (when (#{:set :vector :sequential} schema-type)\n                (first (m/children schema)))\n        value (:value problem)]\n    (when (and child (coll? value))\n      (when-let [[member]\n                 (reduce (fn [_ member]\n                           (when-not (m/validate child member)\n                             (reduced [member])))\n                         nil value)]\n        {:schema child\n         :value member\n         ::collection-type schema-type}))))"
```

### F19 — 602 ms; 535 characters; turn d55310ae9602

```clojure
(program/callers {:seon.program/subject 'seon.error/collection-member-problem})
```

```text
{:seon.program/subject seon.error/collection-member-problem,
 :seon.program/kind :seon.fn/sym,
 :seon.db/basis-t 536871364,
 :seon.program/unknown
   [#:seon.program{:gap :outside-graph,
     :message
       "This supplied database value answers; callers outside its program graph are unknown."}],
 :seon.program/callers #{seon.error/explain-problem},
 :seon.program/call-sites
   [#:seon.fn{:sym seon.error/explain-problem,
     :form-span [43240 46157],
     :declared-arities [#:seon.fn.arity{:min 1, :max 1}],
     :call-arity 1}]}
```

### F20 — 3519 ms; 2827 characters; turn 697f1e5467c9

```clojure
(program/tests-reaching {:seon.program/subject 'seon.error/collection-member-problem})
```

```text
{:seon.program/subject seon.error/collection-member-problem,
 :seon.program/kind :seon.fn/sym,
 :seon.db/basis-t 536871366,
 :seon.program/unknown
   [#:seon.program{:gap :outside-graph,
     :message
       "This supplied database value answers; callers outside its program graph are unknown."}],
 :seon.program/gating
   [my.agent-test/branch-mode-is-the-acquisition-decision-not-a-cluster-label
    my.agent-test/settings-are-one-owned-component-with-a-derived-render-pair
    my.agent-test/settings-updates-preserve-one-component-and-agent-isolation
    my.background-test/background-macro-expands-one-direct-call
    my.background-test/poll-and-await-derive-terminal-presence-without-acknowledgi
[report excerpt ends; 2827 characters total]
```

### F21 — 730 ms; 1259 characters; turn c0d207516d36

```clojure
(mapv #(select-keys % [:seon.issue/id :seon.issue/title]) (filter #(some (fn [word] (clojure.string/includes? (:seon.issue/title %) word)) ["docstring" "help" "typo" "example"]) (seon.issue/issues {:seon.issue/status :open})))
```

```text
[#:seon.issue{:id "cd6356a6245c",
  :title "Word frequency helpers for dogfood"}
 #:seon.issue{:id "chart-plan-examples-retain-old-juniper-step-ids",
  :title "Update chart plan examples to the section 17 fixture"}
 #:seon.issue{:id
    "development-adoption-cannot-compile-docstring-var-contracts",
  :title "Development adoption cannot compile docstring Var contracts"}
 #:seon.issue{:id
    "give-offline-roster-discovery-a-current-read-only-helper",
  :title "Give offline roster discovery a current read-only helper"}
 #:seon.issue{:id "help-trial-copies-prompt-and-invents-results",
  :title "The help trial copies the prompt and invents results"}
 #:seon.issue{:id "my-fs-write-docstring-hides
[report excerpt ends; 1259 characters total]
```

### F22 — 845 ms; 2341 characters; turn df5e273dadda

```clojure
(vec (first (filter #(= "my-fs-write-docstring-hides-its-own-request-shape" (:seon.issue/id %)) (seon.issue/issues {:seon.issue/status :open}))))
```

```text
[[:seon.issue/title
  "`my.fs/write` and `my.shell/run` docstrings hide their own request shapes"]
 [:seon.issue/problem
  "An agent writes calls from the docstring — that is the whole point of the\nrendered function surface. `my.fs/write`'s docstring says:\n\n```text\nTakes a path, one text/bytes/blob content source, and an expected absence or\ndigest.\n```\n\nThat sentence reads as a flat map, and it names none of the keys. The actual\nrequest (`resources/seon/schemas/my.fs.edn:105-109`) is NESTED:\n\n```clojure\n{:my.fs/path         \"…\"\n :my.fs/content      {:my.fs/text \"…\"}\n :my.fs/precondition {:my.fs/expected-absence? true}}\n```\n\nWorse, the sibling function in the same namespa
[report excerpt ends; 2341 characters total]
```

### F23 — 563 ms; 341 characters; turn 5158dd8197e8

```clojure
(doc my.fs/write)
```

```text
seon.sci.eval/documentation-unavailable refused :seon.fn/sym at []: expected the declared requirement (:seon.sci.eval/public-documentation), got a symbol my.fs/write. Fix: No public documentation is available; request an installed public declaration. Inspect the named requirement before retrying. Example: No docstring example is available.
```

### F24 — 722 ms; 3 characters; turn 12b60f03d026

```clojure
(seon.db/q '[:find ?sym ?source :where [?e :seon.fn/sym ?sym] [(contains? #{my.fs/write my.shell/run} ?sym)] [?e :seon.fn/source ?source]])
```

```text
#{}
```

### F25 — 765 ms; 1767 characters; turn 92e046ad8be0

```clojure
(mapv #(select-keys % [:seon.issue/id :seon.issue/title]) (filter #(some (fn [word] (clojure.string/includes? (:seon.issue/title %) word)) ["empty" "nil" "arity" "off-by" "sort"]) (seon.issue/issues {:seon.issue/status :open})))
```

```text
[#:seon.issue{:id "a-program-identity-row-pulls-nil",
  :title "A program identity row pulls nil"}
 #:seon.issue{:id
    "a-stored-entity-schema-requires-a-cardinality-many-key-that-empty-cannot-satisfy",
  :title
    "A stored-entity schema requires a cardinality-many key that an empty collection can never satisfy"}
 #:seon.issue{:id
    "a-stored-or-attribute-drops-its-childrens-nil-refusal",
  :title "A stored `:or` attribute drops its children's nil refusal"}
 #:seon.issue{:id
    "an-empty-child-roster-removal-can-orphan-a-concurrent-acquisition",
  :title
    "Removing an empty child roster after a failed acquisition can orphan a concurrent one"}
 #:seon.issue{:id
    "armed-error-vali
[report excerpt ends; 1767 characters total]
```

### F26 — 547 ms; 3 characters; turn 36c0e94862d7

```clojure
*e
```

```text
nil
```

### F27 — 452 ms; 214 characters; turn 8228ba1f1853

```clojure
(do (try (throw (ex-info "newcomer outer" {:probe :outer} (ex-info "newcomer cause" {:probe :inner}))) (catch Exception e (Throwable->map e))))
```

```text
Execution error (ExceptionInfo) at (REPL:1:123).
Unable to resolve symbol: Throwable->map
Contract finding: seon.sci.kernel/failure-value returned this error, and none of its declared error alternatives matches it.
```

### F28 — 694 ms; 104 characters; turn 936a14867a96

```clojure
(try (throw (ex-info "newcomer outer" {:probe :outer} (ex-info "newcomer cause" {:probe :inner}))) (catch Exception e (mapv #(hash-map :message (ex-message %) :data (ex-data %)) (take-while some? (iterate ex-cause e)))))
```

```text
[{:data {:probe :outer}, :message "newcomer outer"}
 {:data {:probe :inner}, :message "newcomer cause"}]
```

### F29 — 1235 ms; 139 characters; turn 64e58bde402d

```clojure
(defn newcomer-increment "Increment one integer for the newcomer entrance probe." {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1))
```

```text
No example test gates user/newcomer-increment; add a deftest in your namespace that calls it, then (my.test/run).
#'user/newcomer-increment
```

### F30 — 721 ms; 30 characters; turn 49f30f7eac8d

```clojure
(deftest newcomer-increment-test (is (= 2 (newcomer-increment 1))))
```

```text
#'user/newcomer-increment-test
```

### F31 — 605 ms; 1398 characters; turn 848687e8125e

```clojure
(doc my.test/run)
```

```text
{:summary "Run my namespace's declared tests and store their results.",
 :body
   "Takes an optional request map; my context, connection, database and agent\nidentity are supplied. Returns a vector of :seon.test/sym,\n:seon.test/pass-count, :seon.test/fail-count and :seon.test/error-count\nmaps. [] means no tests are declared; it is not evidence that any test\npassed.\n\nMy tests run as isolated agents: each gets its own branch off my current\ncommit, runs there, and its branch is unlinked after its body exits. Its\nresult is recorded on my connection. An unchanged green test returns its\nrecorded result with :seon.test/unchanged and no execution.",
 :example "(my.test/run)",
 :arglists ([]
[report excerpt ends; 1398 characters total]
```

### F32 — 4276 ms; 1602 characters; turn 42d012c78996

```clojure
(my.test/run)
```

```text
;; OVER ONE SECOND (3456 ms): this is your defect; fix it before continuing. Likely you over-engineered or are redoing work a dependency or an existing function already does better. Find what the top call's work is proportional to (the whole program? the store? the call count?) and remove it.
;; Armed definitions by inclusive time (callers include callees; concurrent work included):
;; 7076 ms in seon.sci.eval/call-detecting-program-change x7
;; 5506 ms in seon.db/call-with-custody x7
;; 4179 ms in seon.schema/call-with-projection-state x10
;; 3547 ms in seon.sci.kernel/with-arm x5
;; 3525 ms in seon.sci.eval/trusted-evaluation x3
;; 3426 ms in my.test/run-owned x1
;; 3425 ms in seon.test/ru
[report excerpt ends; 1602 characters total]
```

### F33 — 778 ms; 1384 characters; turn cb5390f42eb0

```clojure
(vec (first (filter #(= "root-empty-plan-read-shows-nil" (:seon.issue/id %)) (seon.issue/issues {:seon.issue/status :open}))))
```

```text
[[:db/id 32715]
 [:seon.issue/functions [{:db/id 2911}]]
 [:seon.issue/id "root-empty-plan-read-shows-nil"]
 [:seon.issue/path "docs/seon/issues/root-empty-plan-read-shows-nil.md"]
 [:seon.issue/problem
  "# Root's empty plan read shows nil\n\nThe root-cluster prompt captured on 2026-09-09 contains the declared plan\nread at turn 0, but its result is `nil` when root owns no plan component.\nThe notes concern already returns `[]` for its empty case. The empty plan\nshould likewise show useful empty data through its owning render function.\n\nEvidence: [exact root prompt](../../prds/context-generation/research/root-cluster-prompt-2026-09-09.txt),\nthe plan entry after `(seon.cluster.status/age
[report excerpt ends; 1384 characters total]
```

### F34 — 601 ms; 342 characters; turn 46905f21fd05

```clojure
(doc my.plan/read)
```

```text
seon.sci.eval/documentation-unavailable refused :seon.fn/sym at []: expected the declared requirement (:seon.sci.eval/public-documentation), got a symbol my.plan/read. Fix: No public documentation is available; request an installed public declaration. Inspect the named requirement before retrying. Example: No docstring example is available.
```

### F35 — 669 ms; 2 characters; turn 54ac50752f55

```clojure
(mapv first (:seon.repl/rows (dir my.plan)))
```

```text
[]
```

### F36 — 626 ms; 7097 characters; turn fc49883c5a06

```clojure
(dir my.plan)
```

```text
{:seon.repl/columns [:sym :arglists :doc :in :out :supplied], :seon.repl/rows [[my.plan/add! ([request]) "Add a plan step and return the saved step with its derived state." [:cat [:map [:my.plan.item/id {:optional true} :my.plan.item/id] [:my.plan.item/done-when {:optional true} :my.plan.item/done-when] [:my.plan.item/done-query {:optional true} :my.plan.item/done-query] [:my.plan.item/subject {:optional true} :my.plan.item/subject] [:my.plan.item/title :my.plan.item/title]]] [:or :my.plan/step-summary :seon.error/value] [:seon.agent/id :seon.db/connection]] [my.plan/update! ([request]) "Update an item's content or completion query and return it." [:cat [:map [:my.plan.item/id :my.plan.item/
[report excerpt ends; 7097 characters total]
```

### F37 — 643 ms; 215 characters; turn 76853012ea61

```clojure
(my.plan/plan {})
```

```text
{:seon.agent/id "mcp/dogfood-newcomer-astra"
 :seon.plan/step-lines {}
 :seon.plan/update-example "(my.plan/add! {:my.plan.item/id \"mcp/dogfood-newcomer-astra/first-step\" :my.plan.item/title \"My first step\"} )"}
```

### F38 — 439 ms; 186 characters; turn feae941fa0cf

```clojure
(throw (ex-info "newcomer outer" {:probe :outer} (ex-info "newcomer cause" {:probe :inner})))
```

```text
Execution error (ExceptionInfo) at (REPL:1:1).
newcomer cause
Contract finding: seon.sci.kernel/failure-value returned this error, and none of its declared error alternatives matches it.
```

### F39 — 551 ms; 3 characters; turn aa7a814b8475

```clojure
*e
```

```text
nil
```

### F40 — 729 ms; 1159 characters; turn aa187e5ddf92

```clojure
(doc my.message/send)
```

```text
{:summary
   "Send a message now and return the written message with its identity.",
 :body
   "Returns the stored :seon.message/id, :seon.message/content and endpoint refs.\nSending inside let or do delivers even when its return value is discarded.\nOptional :my.message/about is a nonempty string subject identity token, stored\nverbatim without looking up a target; omit it when there is no subject.\nOptional :my.message/assignment names the evaluation assigned for repair,\nindependently of the subject. The sender is supplied from my agent identity:\n:seon.message/from alone marks an inside wake, never the subject or assignment.\nHandling is recorded when the turn settles.",
 :example
   "(m
[report excerpt ends; 1159 characters total]
```

### F41 — 786 ms; 650 characters; turn 3baa1e08c4db

```clojure
(my.message/send {:my.message/to "root" :my.message/about "a-collection-member-refusal-does-not-name-the-members-index" :my.message/content "Newcomer dogfood: selected this open issue through the REPL. tests-reaching reports 1626 tests, exceeding the preamble's ~50-test safety bound, so no production edit or merge proposal is safe in this lane. A standalone newcomer-increment test passed through my.test/run (run ee2c316d7de9); it is only an entrance probe. Request root scope a bounded issue regression before implementation. No merge authorized or requested for the scratch probe."})
```

```text
#:seon.message{:id "086d71ef84fa",
 :content
   "Newcomer dogfood: selected this open issue through the REPL. tests-reaching reports 1626 tests, exceeding the preamble's ~50-test safety bound, so no production edit or merge proposal is safe in this lane. A standalone newcomer-increment test passed through my.test/run (run ee2c316d7de9); it is only an entrance probe. Request root scope a bounded issue regression before implementation. No merge authorized or requested for the scratch probe.",
 :about "a-collection-member-refusal-does-not-name-the-members-index",
 :to [:seon.agent/id "root"],
 :from [:seon.agent/id "mcp/dogfood-newcomer-astra"]}
```
