---
type: prd
status: draft
tags: [prd, render, agent]
---

# The agent's REPL history is the context, walk, bootstrap, and live view

This is the line-by-line design for the owner ruling in
[`agent-interface-economy-2026-08-10.md`](agent-interface-economy-2026-08-10.md).
It records received rulings and supplies priced options only where the owner
has not yet ruled, so the remaining lines can be confirmed or marked up before
production work starts.

## Decision in one sentence

Derive one ordered agent-history value from message, run-form, receipt, program, and
cluster facts; walk it from an agent root or cluster root; project that same
semantic value independently through `seon.print` to the AI text target under
the agent profile and to the HTML Hiccup target under the page profile; use it
for bootstrap, later context, namespace pages, the live AI-context debug route,
the main HTML view, and the multi-agent system view. The one mechanism is the
agent's form/value history,
not a requirement that two differently fitted targets share one physical tee.

This directly implements rulings 1–11: one mechanism; real REPL teaching;
results as printed data; two P targets; generic printer improvement; measured
minimum context; bulk pull; injected forms without namespace obligations;
automatic declared renderers; unchanged-basis reuse; and queryable bad-output
evidence
([ruled direction, lines 14–70](agent-interface-economy-2026-08-10.md#L14)).
It also preserves the older binding contract: context, agent history, and debug
page are one REPL-history render, and display is form followed by actual value,
never `;; =>` or comment-framed output
([program rulings, lines 2360–2389](README.md#L2360)).

## Research completed

I read every requested source and evidence document end to end, not by grep:

- `src/seon/render.clj` (684 lines), `src/seon/render/walk.clj` (671),
  `src/seon/render/ns.clj` (632), `src/seon/render/transcript.clj` (805),
  `src/seon/cluster/prompt.clj` (225), `src/seon/context.clj` (202), and
  `src/seon/print.cljc` (797);
- `resources/seon/bootstrap.edn`,
  `resources/seon/schemas/seon.render.edn`,
  `resources/seon/schemas/seon.cluster.run.edn`, and
  `resources/seon/schemas/seon.context.capture.edn`;
- `.agents/skills/repl/SKILL.md` and
  `.agents/skills/datastar-web-ui/SKILL.md`;
- the complete owner-ruling document, the complete superseded
  [`repl-session-context-2026-08-01.md`](repl-session-context-2026-08-01.md),
  the current working edge at
  [`unsettled.md`, lines 19–64](unsettled.md#L19), the 2026-07-31 render rulings
  at [`README.md`, lines 1926–2035](README.md#L1926), and the real-REPL ruling
  at [`README.md`, lines 2360–2424](README.md#L2360);
- the complete
  [`context-quality-audit-2026-08-10.md`](../research/context-quality-audit-2026-08-10.md),
  [`model-authoring-drive-2026-08-10.md`](../research/model-authoring-drive-2026-08-10.md),
  and
  [`model-authoring-observer-2026-08-10.md`](../research/model-authoring-observer-2026-08-10.md);
- the complete issue notes
  [`render-walk-frames-values-as-comments.md`](../../../seon/issues/render-walk-frames-values-as-comments.md),
  [`render-value-floor-refuses-any-map-with-unqualified-keys.md`](../../../seon/issues/render-value-floor-refuses-any-map-with-unqualified-keys.md),
  [`contract-fit-render-selection-never-reaches-a-nested-value.md`](../../../seon/issues/contract-fit-render-selection-never-reaches-a-nested-value.md),
  and
  [`debug-pages-receive-block-patches-for-elements-they-do-not-have.md`](../../../seon/issues/debug-pages-receive-block-patches-for-elements-they-do-not-have.md).
- the independent revision critique,
  [`repl-transcript-context-prd-critique-2026-08-10.md`](repl-transcript-context-prd-critique-2026-08-10.md),
  all 520 lines, end to end. The disposition of every numbered finding is
  explicit in [Critique disposition](#critique-disposition).

For the two added design surfaces I also read the current web delivery and
fleet owners: `src/seon/render/web.clj:306-427,503-566,673-800,978-1085`,
`src/seon/oversight.clj:1-301`, `src/seon/render/agent.clj:1-43`, and the
cluster/agent schema declarations at
`resources/seon/schemas/seon.cluster.edn:1-18` and
`resources/seon/schemas/seon.cluster.agent.edn:1-86`.

## Dependency ledger

This design adds no dependency. It strengthens these pinned mechanisms:

| Dependency | Selected revision | Boundary read and first-party owner |
|---|---|---|
| Datahike | `10540578248eaa686c1f88a7fe57644ee4c9f993` | Relation and tuple inputs become query relations at `reference-code/datahike/src/datahike/query.cljc:832-890`; all calls remain through `src/seon/db.clj:779-823`. |
| Malli | `80138076960e7820523b4cb932c5b5d1936d4e7f` | Schema properties/forms are dependency-owned protocol operations at `reference-code/malli/src/malli/core.cljc:30-43`; Seon's declared producer/profile properties remain in `resources/seon/schemas/seon.render.edn:1-55` and `resources/seon/schemas/seon.render.profile.edn:1-18`. |
| core.async | `dc35f3e0d7bc2eef502e77982f48641f025c8051` | A mult distributes each value to all taps and requires buffering to isolate slow taps at `reference-code/core.async/src/main/clojure/clojure/core/async.clj:797-845`; Seon's package taps and writer live at `src/seon/render/web.clj:673-800`. |
| Datastar Clojure | `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` | `patch-elements!` sends ID-bearing fragments over an SSE response at `reference-code/datastar-clojure/src/dev/examples/snippets.clj:10-16,26-33`; Seon's one patch call and target package live at `src/seon/render/web.clj:978-1085`. |

The first-party render call graph being changed is therefore database facts →
`seon.render/walk` → `seon.print` sinks → retained package → existing SSE tap.
The agent-history assembler, debug capture, and fleet append are current
parallel assembly owners to delete, not dependencies to preserve
(`src/seon/render/transcript.clj:26-226,639-778`,
`src/seon/render/web.clj:336-375,503-566`).

## The acceptance contract

The change is complete only when all nine statements are simultaneously true:

1. A fresh agent, a ten-turn agent, and root use the same agent-history derivation.
2. Every visible agent-history entry is a form and the value that form produced; the
   only visible comments are comments the agent actually submitted as source.
3. AI and HTML receive the same ordered history-entry identities and values,
   then run independent target/profile projection and fit passes. Their bytes
   and retained children may differ. `seon.print/emit-both` is used only when
   both targets have identical fit options; it is not the general two-profile
   contract (`src/seon/print.cljc:584-595,756-791`).
4. Bootstrap is a pinned prefix of real or explicitly injected agent-history forms,
   not a second prompt assembler. The current bootstrap already stores ordered
   form sources (`resources/seon/bootstrap.edn:1-73`); this PRD changes their
   content and projection, not that source-of-truth shape.
5. No namespace must define a special context function. Producer selection
   remains declared/contract-derived at the one render boundary
   (`src/seon/render.clj:110-221`).
6. When program generation, selected profile, root, and retained database-read
   evidence are all current, each target performs zero agent-history queries,
   candidate selections, and producer invocations. Current retained calls
   already compare static and read evidence before invocation reuse, but do not
   prove the broader acquisition gate (`src/seon/render.clj:401-447`); Phases
   1 and 4 add counters at that outer boundary.
7. The printer stress harness supplies generated registered-schema values and
   sampled real database values to both sinks; every failure becomes a printer
   grammar fix or an explicit producer exception, never a test-specific clip.
8. The debug route is the live AI-context pane; the normal route is the main
   HTML view. Both project the same agent's history at one database value, and
   a settled form can update whichever route is open through the existing
   package path.
9. The system view is the same walk rooted at the cluster/fleet entity with a
   preview profile. Agent order is derived from rendered-value `changed-at`; no dashboard
   query, stored rank, or second delivery path exists.

## One agent-history owner

The pure target function is `seon.render.walk/history`. “The agent's history”
always means REPL form/value history here, never Datahike's `:history`
time-axis. It receives an open request map and returns the only ordered value
consumed by the prompt, namespace page, debug AI-context pane, and system view:

```clojure
(seon.render.walk/history
 {:seon.render.walk/root [:seon.cluster.agent/id "task-agent-17"]
  :seon.render.walk/database-value db
  :seon.render.walk/profile :seon.render.profile/agent})
=>
[{:seon.repl/form "(help)"
  :seon.repl/value "You are task-agent-17 in a Seon cluster…"}
 <more open form/value entries>]
```

The public `seon.render/walk` contract remains a rendered string during this
wave; current callers and its schema require that string
(`src/seon/render/walk.clj:469-496,568-671`). This new data-returning function
is added in the existing owner rather than silently changing `walk`. Profile
selection uses the namespaced request key `:seon.render.walk/profile` and a
declared profile identity. Exact consumers are `src/seon/cluster/prompt.clj`
for provider context, `src/seon/render/web.clj` for page/debug/system packages,
and `src/seon/render/agent.clj` for an agent-root render. The separate assembly
in `src/seon/render/transcript.clj:26-226,639-778` is deleted after those
consumers switch in the same phase.

One agent-history entry owns one form and its optional settled value, so adjacency is
structural rather than reconstructed by sorting separate entries. The exact
total order is:

```clojure
bootstrap entry [0 ordinal form-id]
inbound message [1 at-ms 0 tx ordinal message-id]
run form        [1 run-opened-ms 1 ordinal form-id]
```

Stable identities break every same-transaction tie. A human message that
triggers a run sorts before that run because its recorded arrival precedes the
run opening. A form without a terminal receipt remains as an unsettled entry.
Active-run acquisition retains the current `not-join` exclusion of any run
named by `:seon.cluster.run/supersedes`
(`src/seon/render/transcript.clj:85-91`); attempts/reasoning are not independent
entries in the agent's REPL history unless their bytes are exact submitted
form source or a settled value. The current multi-family rank order at
`src/seon/render/transcript.clj:400-416` is deleted, not carried forward.

## Worked example A — complete fresh-task context

This is the recommended starting candidate, written exactly as the model sees
it. It has 12 opening forms, no authored result summaries, no walk headers, and
no schema wall. Prompts are derived from each receipt's recorded namespace, as
the earlier live prototype required
([superseded design, lines 303–309](repl-session-context-2026-08-01.md#L303)).
The hexadecimal object address is the stored printed representation from the creation run;
it is not regenerated per prompt.

```text
user=> (help)
"You are task-agent-17 in a Seon cluster, operating a real Clojure REPL. Your reply is read as ordered forms; each form settles to the value printed below it. Batch independent forms because model round trips are expensive. Every function in the cluster program graph is callable. Public functions are the API; private functions remain inspectable. A defn with a complete :malli/schema becomes a durable program fact; scratch defs remain the agent's defs. The cluster is one graph database, so use dir, doc, seon.program/docs, and seon.db/q instead of guessing. Send work with my.message/send. Finish with my.run/complete or my.run/wait."
user=> (in-ns 'my.agents.task-agent-17)
#object[sci.lang.Namespace 0x1a2b3c4d "my.agents.task-agent-17"]
my.agents.task-agent-17=> (require '[my.run :as run] '[my.message :as message] '[seon.db :as db])
nil
my.agents.task-agent-17=> (seon.program/docs {:seon.program/identities ['my.run] :seon.program/detail :summary})
[{:seon.ns/name my.run,
  :seon.ns/public-functions
  [{:seon.program/name my.run/complete,
    :seon.fn/arglists ([result]),
    :seon.fn/doc "Finish this run with a reply for its requester."}
   {:seon.program/name my.run/wait,
    :seon.fn/arglists ([note]),
    :seon.fn/doc "Finish this run without a reply and record what you await."}],
  :seon.ns/used-schemas [:my.run/completed :my.run/result :my.run/waited]}]
my.agents.task-agent-17=> (seon.program/docs {:seon.program/identities ['my.message] :seon.program/detail :summary})
[{:seon.ns/name my.message,
  :seon.ns/public-functions
  [{:seon.program/name my.message/decline,
    :seon.fn/arglists ([to about reason]),
    :seon.fn/doc "Decline an assignment and explain why to its sender."}
   {:seon.program/name my.message/send,
    :seon.fn/arglists ([to content] [to content about]),
    :seon.fn/doc "Address a message to another agent."}],
  :seon.ns/used-schemas
  [:my.message/about :my.message/content :my.message/to :my.message/value]}]
my.agents.task-agent-17=> (seon.program/docs {:seon.program/identities ['my.agents.task-agent-17] :seon.program/detail :summary})
[{:seon.ns/name my.agents.task-agent-17,
  :seon.ns/doc nil,
  :seon.ns/public-functions [],
  :seon.ns/used-schemas []}]
my.agents.task-agent-17=> (seon.program/docs {:seon.program/identities [:my.run/result] :seon.program/detail :deep})
[{:seon.schema/key :my.run/result,
  :seon.schema/definition [:string {:min 1}],
  :seon.schema/properties {:min 1},
  :seon.schema/example "The report is complete and the focused tests pass.",
  :seon.schema/example-seed 1224898382,
  :seon.schema/program-commit #uuid "6a7a4c6f-9745-55b1-ae67-510c8e622317",
  :seon.schema/accepted-by [{:seon.fn/sym my.run/complete,
                             :seon.fn/arglists ([result])}],
  :seon.schema/returned-by [],
  :seon.schema/contained-by [:my.run/completed]}]
my.agents.task-agent-17=> (db/q '[:find (count ?function) . :where [?function :seon.fn/sym _]])
2775
my.agents.task-agent-17=> (defn total
                            "Sum integers."
                            {:malli/schema [:=> [:cat [:sequential :int]] :int]}
                            [values]
                            (reduce + 0 values))
#'my.agents.task-agent-17/total
my.agents.task-agent-17=> (total [3 5 8])
16
my.agents.task-agent-17=> (db/q '[:find ?spec . :in $ ?sym :where [?function :seon.fn/sym ?sym] [?function :seon.fn/spec ?spec]] "my.agents.task-agent-17/total")
"[:=> [:cat [:sequential :int]] :int]"
my.agents.task-agent-17=> (my.message/read "inbound-536871250-0")
{:seon.cluster.message/id "inbound-536871250-0",
 :seon.cluster.message/to [:seon.cluster.agent/id "task-agent-17"],
 :seon.cluster.message/content "Inspect the failed invoice import, explain the cause, and propose the smallest durable fix."}
my.agents.task-agent-17=>
```

Every `seon.program/docs` result above is a **target result** until Phase 3
implements and re-executes the candidate; it is not claimed as current REPL
behavior. Current `doc` prints its familiar human documentation and returns
`nil` (`src/seon/sci/eval.clj:1111-1122`), so the example uses the new data API
where the returned value itself is the lesson. The candidate is intentionally
not declared minimal. Its measured size is
recorded after authoring in [Measured baselines](#measured-baselines); Phase 6
must remove forms until quality fails, then restore the last necessary form.
The successful `defn` replaces the current deliberate `:any` and wrong-arity
teaching failures at `resources/seon/bootstrap.edn:49-70`, which the audit
found were committed as faults
([audit, lines 149–202](../research/context-quality-audit-2026-08-10.md#L149)).

## Worked example B — root mid-conversation

The human message is a derived injected read form under recommended Option
HUMAN-2. The agent's own comments remain exact submitted source; values remain
ordinary printer output.

```text
my.agents.root=> (my.message/read "inbound-536871311-0")
{:seon.cluster.message/id "inbound-536871311-0",
 :seon.cluster.message/to [:seon.cluster.agent/id "root"],
 :seon.cluster.message/content "Which agents are active, and what changed most recently?"}
my.agents.root=> (my.view/current)
{:seon.render.view/id "default/root",
 :seon.render.view/root [:seon.cluster.agent/id "root"],
 :seon.render.view/subject [:seon.cluster/name "default"]}
my.agents.root=> (seon.db/q '[:find ?agent-id ?run-id
                              :where
                              [?agent :seon.cluster.agent/id ?agent-id]
                              [?agent :seon.cluster.agent/run ?run]
                              [?run :seon.cluster.run/id ?run-id]])
#{["invoice" "6177a48d-3dd0-44ef-b351-299bd0b8fe0a"]}
my.agents.root=> ;; Derive the newest changed value; do not store an activity rank.
                 (seon.render.walk/history
                  {:seon.render.walk/root [:seon.cluster/name "default"]
                   :seon.render.walk/profile :seon.render.profile/preview})
[{:seon.cluster.agent/id "invoice",
   :seon.render.walk/changed-at 536871319,
   :seon.render.preview/content
   {:seon.cluster.run.form/source "(db/q invoice-query)"
    :seon.render/value [["inv-203" :invalid-tax-code]]}}
  {:seon.cluster.agent/id "root",
   :seon.render.walk/changed-at 536871311,
   :seon.render.preview/content
   {:seon.cluster.message/content "Which agents are active, and what changed most recently?"}}]
my.agents.root=> (my.run/complete "invoice is active; its latest settled query found invoice inv-203 has an invalid tax code. The other agents are parked.")
{:my.run/disposition :completed,
 :my.run/result "invoice is active; its latest settled query found invoice inv-203 has an invalid tax code. The other agents are parked."}
```

The current reply reader already preserves leading agent comments inside the
following form source (`src/seon/cluster/reply.clj:155-268,330-390`); this
example does not turn comments into result entries. The message maps omit
`:seon.cluster.message/from` deliberately: current storage uses absence of that
ref for an origin outside the cluster, rather than inventing a human identity
(`src/seon/cluster/message.clj:267-269,437-440`).

The `history`, `seon.program/docs`, `my.message/read`, and `my.view`
results in these examples are normative target values. Ordinary query results retain
actual REPL collection semantics—the `:find` relation above is a set, not an
authored vector
(`src/seon/db.clj:779-823`).

## Exact history-entry grammar

There is no stored history-entry discriminator. Attribute presence identifies each
fact family. The renderer derives the following open maps and hands their
`:seon.repl/value` print node to the printer. The address remains data for
requery and HTML identity; it is not emitted as a decorative text header.

| Family | Derived form | Actual value | Durable address |
|---|---|---|---|
| Inbound message | `(my.message/read "<message-id>")` | Pulled admitted message map | `[:seon.cluster.message/id <id>]` |
| Agent form | Exact `:seon.cluster.run.form/source` | Receipt's admitted `:seon.print` node | `[run-id ordinal]` |
| Settled success | Same agent form; never a second form | `:seon.cluster.eval/result-edn`, decoded as one print node | `[:seon.cluster.eval/id <id>]` |
| Settled error | Same agent form | Flat `:seon.error` value printed through the same node grammar | `[:seon.cluster.eval/id <id>]` |
| Elision | The exact read that produced the bounded value | `:seon.print/elided` with omitted count, total when known, path, next offset, profile, and requery identity/refusal | Result identity or explicit refusal |
| Injected require | `(require '[my.run :as run] ...)` | `nil` | Bootstrap run + ordinal |

The derived in-memory shape is concrete:

```clojure
{:seon.repl/form "(total [3 5 8])"
 :seon.repl/namespace 'my.agents.task-agent-17
 :seon.repl/value {:seon.print/value 16}
 :seon.cluster.run/id "bootstrap:task-agent-17"
 :seon.cluster.run.form/ordinal 9
 :seon.cluster.eval/id "[\"bootstrap:task-agent-17\" 9]"}
```

For a message, the entry is derived rather than committed as a fake eval:

```clojure
{:seon.repl/form "(my.message/read \"inbound-536871250-0\")"
 :seon.repl/namespace 'my.agents.task-agent-17
 :seon.repl/value <admitted message print node>
 :seon.cluster.message/id "inbound-536871250-0"}
```

`;; d0 · [:lookup]`, `;; unit=… branch=…`, `;; Volatile context metadata`,
and `;; (seon.render/walk …) => …` are replaced by nothing in the text target.
The corresponding facts remain on the agent-history entry, on the elision value, and as HTML
`data-*` attributes. Today those comment strings are constructed in
`src/seon/render/walk.clj:609-669`; deleting their assembly closes
[`render-walk-frames-values-as-comments.md`](../../../seon/issues/render-walk-frames-values-as-comments.md).

### Printer behavior by agent-history entry

- Text emits `namespace=> ` plus exact form source, newline, then the text sink's
  output for the admitted node. HTML emits one stable entry wrapper containing a
  form element and `seon.print/emit-hiccup` for the same node.
- A form with captured stdout emits stdout before the return value, matching a
  REPL; it does not convert stdout to a comment. Current receipts already carry
  optional `:seon.cluster.eval/output` (`src/seon/render/transcript.clj:43-59`).
- A flat error is data and uses the error producer or generic print floor; an
  untriaged execution error may use Clojure's execution-error printed
  representation, which the
  current assembler builds at `src/seon/render/transcript.clj:539-566`.
- An elision is an ordinary value. The current printer already includes count,
  path, offset, profile, and requery/refusal in text
  (`src/seon/print.cljc:277-296`) and constructs those fields during fitting
  (`src/seon/print.cljc:600-724`).

## Concrete schema and API docs

`seon.program/docs` is the plural data-returning companion to
`clojure.repl/doc`: “docs” names documentation, not a generic rendering
abstraction. Its canonical result members validate as `:seon.program/doc`
shapes and then print through the one value printer.

### Current schema wall versus proposed printed representation

The before/after contract is literal:

| Current schema-wall line | Proposed printed representation |
|---|---|
| `; schema :my.fs/not-found-error = [:map {:seon.error/class true, :seon.render/ai seon.error/render-ai, :seon.render/html seon.error/render-html, :error/message "must identify the absent filesystem path"} [:my.fs/not-found :my.fs/not-found] [:seon.error/message :seon.error/message]]` | `:my.fs/not-found-error — refusal: must identify the absent filesystem path.` |

This exact class accounted for 43 lines and 3,459 estimated tokens in the
audited root context
([audit, lines 204–234](../research/context-quality-audit-2026-08-10.md#L204)).
Under recommended SCHEMA-1 even that line appears only when the error can be
returned by a displayed API member; unreferenced schema source is absent from
the opening context.

### Light API line

The exact summary line is:

```text
my.fs/read [path] — Read UTF-8 text from path.
```

Multiple arities repeat the bracketed vectors:

```text
my.message/send [to content] [to content about] — Address a message to another agent.
```

The data behind a line is an open map, so the generic printer may choose a table
when several docs are returned:

```clojure
{:seon.program/name 'my.message/send
 :seon.fn/arglists '([to content] [to content about])
 :seon.fn/doc "Address a message to another agent."}
```

Current `doc` already derives public function docs, arglists, and input/output
schema refs from one database value (`src/seon/sci/eval.clj:1016-1086`). This
phase moves that derivation behind one public data function instead of adding a
second documentation index.

### Deep schema doc — actual target call and result

```clojure
(seon.program/docs
 {:seon.program/identities [:my.run/result]
  :seon.program/detail :deep})
[{:seon.schema/key :my.run/result
  :seon.schema/definition [:string {:min 1}]
  :seon.schema/properties {:min 1}
  :seon.schema/example "The report is complete and the focused tests pass."
  :seon.schema/example-seed 1224898382
  :seon.schema/program-commit #uuid "6a7a4c6f-9745-55b1-ae67-510c8e622317"
  :seon.schema/accepted-by
  [{:seon.fn/sym my.run/complete
    :seon.fn/arglists ([result])}]
  :seon.schema/returned-by []
  :seon.schema/contained-by [:my.run/completed]}]
```

The definition is current fact content
(`resources/seon/schemas/my.run.edn:1-8`); `my.run/complete` declares it as its
input (`src/my/run.clj:32-47`). The generated example seed is the low 31 bits
of the first eight hex digits of `seon.schema/sha-256` over UTF-8
`(pr-str [schema-key program-commit-id])`; the doc returns both seed and
program commit. Repeated calls at the same acquired program commit return
byte-equal example data; a generator failure returns a flat error in the
example slot, not an invented value. Seon's schema owner already derives
registered forms and properties (`src/seon/schema.clj:482-493,1974-2028`).

### Bulk docs function — exact target signature

```clojure
(seon.program/docs request)

request :=
[:map
 [:seon.program/identities
  [:vector [:or :qualified-symbol :qualified-keyword :seon.ns/name]]]
 [:seon.program/detail {:optional true} [:enum :summary :deep]]]

result :=
[:vector [:or :seon.program/doc :seon.error/value]]
```

One mixed call:

```clojure
(seon.program/docs
 {:seon.program/identities
  ['my.run/complete :my.run/result 'my.run
   'my.run-test/a-disposition-is-an-ordinary-value]
  :seon.program/detail :summary})
[{:seon.program/name my.run/complete
  :seon.fn/arglists ([result])
  :seon.fn/doc "Finish this run with a reply for its requester."}
 {:seon.schema/key :my.run/result
  :seon.schema/definition [:string {:min 1}]}
 {:seon.ns/name my.run
 :seon.ns/doc "Return values that tell the run loop to wait or complete."}
 {:seon.test/sym my.run-test/a-disposition-is-an-ordinary-value
  :seon.fn/calls
  [my.run/complete my.run/wait seon.schema/valid-candidate-value?]}]
```

Order equals request order; unknown identities produce a flat error value in
that vector position, so one miss does not erase the other requested docs.
Unqualified function/test symbols and unqualified schema keywords are loud
`:seon.program/ambiguous-identity` errors; namespace identities validate as
`:seon.ns/name`. This removes the ambiguous bare-symbol arm rather than adding
precedence.
Under recommended DOC-1, bare `doc` prints the familiar readable representation and
returns `nil`; callers that need data use `seon.program/docs`. Both invoke the
same projection owner. The existing private
`program-documentation` derivation is therefore replaced, not duplicated
(`src/seon/sci/eval.clj:1057-1122`). Concretely, the database acquisition and
SCI binding remain in `src/seon/sci/eval.clj`, while the row-to-doc pure
projection lives with the four canonical program shapes in
`src/seon/program.cljc:12-42`; no second index or documentation namespace is
introduced.

## Current-state and deletion inventory

| Current mechanism | Current evidence | Target disposition |
|---|---|---|
| Producer precedence and guarded SCI invocation | Explicit → contract candidates → schema → floor at `src/seon/render.clj:150-221`; calls execute through the guarded kernel at `:238-265` | Survives; one option below settles whether nested values also consult candidates. |
| Recursive print-node projection | `project-node*` traverses admitted child nodes at `src/seon/render.clj:274-353` | Survives, with the selected nested policy and cycle guard. |
| Render-call reuse | Static/read evidence reuse at `src/seon/render.clj:401-447` | Survives and becomes the unchanged-basis hard gate for both targets. |
| Neighbourhood traversal | Forward/reverse refs and bounded walk at `src/seon/render/walk.clj:56-206,280-496` | Survives as the one walk for agent roots and cluster roots. |
| AI walk prose assembler | Comment headers/body/metadata at `src/seon/render/walk.clj:568-671` | Deleted. Ordered agent-history entries go straight to the printer text sink. |
| Separate `seon.render.transcript` agent-history assembler | Independent queries, ordering, six-entry policy, AI/HTML assembly at `src/seon/render/transcript.clj:26-226,432-455,639-778` | Namespace deleted after its queries/order move into `seon.render.walk/history`. No second agent-history projection survives. |
| `compact-ai-text` schema dump | Full/compact namespace render paths and schema closure at `src/seon/render/ns.clj:354-475` | Deleted. Summary program docs are pulled on demand; namespace HTML may still show program members through the shared profile. |
| Per-family/per-rendered-value size policy | `recent-entry-count` is hard-coded 6 at `src/seon/render/transcript.clj:26-30`; that namespace invents its own token budget at `:700-752`; namespace budget logic sits at `src/seon/render/ns.clj:320-330` | Deleted. One render profile is selected at the consumer boundary and one `seon.print/fit` owner spends it. |
| Generic print grammar and two sinks | Sink protocol at `src/seon/print.cljc:10-14`; text/Hiccup/tee at `:98-220`; fitting at `:756-791` | Survives and is strengthened by the stress harness. |
| Bootstrap source forms | 13 current forms at `resources/seon/bootstrap.edn:1-73` | Survives as pinned injected agent-history forms; failing lessons and schema-wall probes are replaced by the 12-form candidate. |
| Prompt acquisition and budget | One retained walk, distance fallback, calibrated budget at `src/seon/cluster/prompt.clj:140-225` | Survives, but acquires the agent-history text projection; distance fallback becomes profile fitting, not “all or nothing” walk depth. |
| Exact provider capture | Capture writes prompt, basis, contributions, and token characters at `src/seon/context.clj:150-188`; schema marks prompt no-history at `resources/seon/schemas/seon.context.capture.edn:1-33` | Survives as forensic “what this provider call saw”; it is not the authority for the live debug pane. |
| Debug overlay | AI pane reads latest capture at `src/seon/render/web.clj:503-531`; current debug code also wraps the normal HTML projection at `:537-566` | Converted to an AI-context-only debug route. The normal route remains the main HTML view; each route receives only its declared targets. Historical captures remain drillable facts. |
| Debug feed packages | Debug has its own registration key at `src/seon/render/web.clj:700-737,1030-1034`; package/delta/keyframe logic is shared at `:596-643` | Survives. Debug packages contain exactly two target IDs, preventing no-target patches by construction. |
| Fleet oversight special value | Root page calls `oversight/unit` outside the walk at `src/seon/render/web.clj:336-375`; its bespoke table is `src/seon/oversight.clj:261-301` | Replaced by cluster-root walk + preview profile. Flow observations may remain ordinary values reached from the cluster root. |
| Agent renderer | Agent schema currently points both projections to history wrappers at `resources/seon/schemas/seon.cluster.agent.edn:1-14` | Updated to the one agent-history producer; no status-plus-history composition. |

### Render-producer argument ABI conversion

The target does **not** redefine `:seon.render/unit`. Current
`render-argument` merges a rendered map's arbitrary keys into the producer
argument, so existing producers read flat domain attributes alongside render
custody (`src/seon/render.clj:76-108`). A live program-graph query on
2026-08-10 found 49 function contracts in 18 namespaces accepting
`:seon.render/unit`: `seon.ai`, `seon.bootstrap`, `seon.cluster`,
`seon.cluster.agent`, `seon.cluster.instruction`, `seon.cluster.message`,
`seon.cluster.run`, `seon.config`, `seon.context`, `seon.effect`,
`seon.oversight`, `seon.print`, `seon.problems`, `seon.render.agent`,
`seon.render.ns`, `seon.render.transcript`, `seon.render.value`, and
`seon.render.web`. For example, the message producer reads the flat message
attributes at `src/seon/cluster/message.clj:429-463`. Removing the merge
without converting these consumers would break their input semantics while
their old schema still validated; that is forbidden breakage.

Phase 2 therefore introduces a new, globally identified request shape:

```clojure
[:seon.render/producer-request
 [:map
  [:seon.render/value :seon.render/value]
  [:seon.render/context
   [:map
    [:seon.render/target [:enum :seon.render/ai :seon.render/html]]
    [:seon.render/profile :qualified-keyword]
    [:seon.render/database-value :seon.db/database-value]
    [:seon.render/rendering-stack [:set :qualified-symbol]]]]]]
```

Every surviving producer reads domain data only below
`:seon.render/value` and custody only below `:seon.render/context`. Phase 2
adds the new declarations, converts all 49 contracts and all 18 implementation
owners in one wave, converts the selector/invocation callers, proves zero
remaining `:seon.render/unit` consumers by database query, and only then
deletes the flattening branch. The old key is not widened, narrowed, aliased,
or given new meaning; it disappears with its final consumer. This is the
accretion-safe conversion demanded by the rule that a key's definition and
relationship to output never change (`AGENTS.md:516-548`).

### Storage verification — live `default`, door mode, 2026-08-10

I probed the live default cluster rather than relying on the roadmap statement.
The exact join was:

```clojure
(seon.db/q
 {:query
  '[:find ?run-id ?ordinal ?form-id ?receipt-id ?source ?result-edn
    :where
    [?run :seon.cluster.run/id ?run-id]
    [?form :seon.cluster.run.form/run ?run]
    [?form :seon.cluster.run.form/ordinal ?ordinal]
    [?form :seon.cluster.run.form/id ?form-id]
    [?form :seon.cluster.run.form/source ?source]
    [?receipt :seon.cluster.eval/run ?run]
    [?receipt :seon.cluster.eval/ordinal ?ordinal]
    [?receipt :seon.cluster.eval/id ?receipt-id]
    [?receipt :seon.cluster.eval/result-edn ?result-edn]]
  :limit 5})
```

One live row was:

```clojure
["fe408373-9c06-4b41-87d5-9c5bebeee9d8"
 0
 "[:seon.cluster.run.form/id \"fe408373-9c06-4b41-87d5-9c5bebeee9d8\" 0]"
 "[\"fe408373-9c06-4b41-87d5-9c5bebeee9d8\" 0]"
 "(map (fn [sym] ...) [\"my.agents.root/token-pressure\" ...])"
 "#:seon.print{:items [...]}" ]
```

This matches the source contract: form identity and receipt identity both
derive from run + ordinal (`src/seon/cluster/run.clj:567-583`); plan admission
stores exact source/ordinal/run on the form (`:639-663`); receipt start stores
run and ordinal (`:731-765`); settlement adds `result-edn`, optional blob/size,
error/output/ns, and other terminal facts (`:767-790,1172-1245`). The form
schema declares source/ordinal/run at
`resources/seon/schemas/seon.cluster.run.form.edn:1-24`; the eval schema
declares the result projection at
`resources/seon/schemas/seon.sci.eval.edn:20-47`.

The live installed `:seon.cluster.eval/*` attributes are `at`, `error`, `id`,
`interrupted-at`, `ns`, `ordinal`, `output`, `result-blob`, `result-edn`,
`result-size`, `run`, and `triage-edn`. There is no direct receipt→form ref, but
the run+ordinal join above is total and queryable. **No new linkage fact is
missing.** Adding `:seon.cluster.eval/form` would duplicate a relationship
already derived from two required facts and could disagree with it.

The live row also corrects a common misreading: `result-edn` is serialized
admitted print-node data, not the final AI string. That is exactly why one
stored result can emit text or Hiccup at render time, as ruling #26 requires
([program ruling, lines 2408–2417](README.md#L2408)).
It also verifies ruling #25's stored printed representation: a small result stores the bounded
`result-edn`; the installed schema also offers `result-blob` and `result-size`
for values crossing the measured serialized-size knee
([program ruling, lines 2390–2404](README.md#L2390)). This sampled row was below
that knee and therefore carried no blob digest.

## Design surface: live AI-context debug route

### Mid-turn worked example

At basis 536871319, the message read, current-view retrieval, and query have
settled; the history form is submitted and has no value yet. The debug route's
package contains exactly
the AI-context element:

```clojure
#{"debug-ai-root"}
```

The owner-visible bytes inside the debug `<pre>` are exactly:

```text
my.agents.root=> (my.message/read "inbound-536871311-0")
{:seon.cluster.message/id "inbound-536871311-0",
 :seon.cluster.message/to [:seon.cluster.agent/id "root"],
 :seon.cluster.message/content "Which agents are active, and what changed most recently?"}
my.agents.root=> (my.view/current)
{:seon.render.view/id "default/root",
 :seon.render.view/root [:seon.cluster.agent/id "root"],
 :seon.render.view/subject [:seon.cluster/name "default"]}
my.agents.root=> (seon.db/q '[:find ?agent-id ?run-id
                              :where
                              [?agent :seon.cluster.agent/id ?agent-id]
                              [?agent :seon.cluster.agent/run ?run]
                              [?run :seon.cluster.run/id ?run-id]])
#{["invoice" "6177a48d-3dd0-44ef-b351-299bd0b8fe0a"]}
my.agents.root=> (seon.render.walk/history
                  {:seon.render.walk/root [:seon.cluster/name "default"]
                   :seon.render.walk/profile :seon.render.profile/preview})
```

There is no following value or prompt because that last form has not settled.
The normal route is the main HTML view; it is not a right debug pane. At the
same database value its HTML target contains the same four history-entry
identities, concretely:

```clojure
[:section {:id "agent-html-root"
           :class "seon-main-view"}
 [:article {:id "repl-message-inbound-536871311-0"}
  [:pre {:class "seon-repl-form"}
   "my.agents.root=> (my.message/read \"inbound-536871311-0\")"]
  [:dl {:class "seon-print-map"}
   [:dt ":seon.cluster.message/id"] [:dd "inbound-536871311-0"]
   [:dt ":seon.cluster.message/to"]
   [:dd "[:seon.cluster.agent/id \"root\"]"]
   [:dt ":seon.cluster.message/content"]
   [:dd "Which agents are active, and what changed most recently?"]]]
 [:article {:id "repl-view-current-root"}
  [:pre {:class "seon-repl-form"}
   "my.agents.root=> (my.view/current)"]
  [:dl {:class "seon-print-map"}
   [:dt ":seon.render.view/id"] [:dd "default/root"]
   [:dt ":seon.render.view/root"]
   [:dd "[:seon.cluster.agent/id \"root\"]"]
   [:dt ":seon.render.view/subject"]
   [:dd "[:seon.cluster/name \"default\"]"]]]
 [:article {:id "repl-form-6177a48d-0"}
  [:pre {:class "seon-repl-form"}
   "my.agents.root=> (seon.db/q '[:find ?agent-id ?run-id :where [?agent :seon.cluster.agent/id ?agent-id] [?agent :seon.cluster.agent/run ?run] [?run :seon.cluster.run/id ?run-id]])"]
  [:table {:class "seon-print-table"}
   [:tbody [:tr [:td "invoice"]
            [:td "6177a48d-3dd0-44ef-b351-299bd0b8fe0a"]]]]]
 [:article {:id "repl-form-6177a48d-1" :data-settled "false"}
  [:pre {:class "seon-repl-form"}
   "my.agents.root=> (seon.render.walk/history {:seon.render.walk/root [:seon.cluster/name \"default\"] :seon.render.walk/profile :seon.render.profile/preview})"]]]
```

The HTML may compose a map as a definition list and rows as a table because
the page profile differs from the text profile. Both projections preserve the
same four history-entry identities, but independent fitting may retain
different children inside an entry and represent omitted children as ordinary
elision values. The current debug layout and page projection are distinct at
`src/seon/render/web.clj:503-566`.

When ordinal 1 settles, the database transaction wakes the render proc. The
same existing multicast reaches every subscribed route, but the debug
registration derives only `debug-ai-root` and the normal registration derives
only its main-view elements. No normal-route ID enters a debug package and no
debug ID enters a normal package. This closes the send-then-discover class at
[`debug-pages-receive-block-patches-for-elements-they-do-not-have.md`, lines 56–81](../../../seon/issues/debug-pages-receive-block-patches-for-elements-they-do-not-have.md#L56):
the package owner: every registration produces only elements its route
declares.

The debug AI-context pane is current agent-history text under exactly the
agent profile and immutable database value used by prompt acquisition; a
same-basis regression compares its bytes to the acquired provider-context
bytes. The main HTML view uses the page profile independently, so only ordered
history-entry identities—not bytes or retained children—must match. The debug
route is not “latest prompt capture.” Current code uses that capture and
otherwise says no capture exists (`src/seon/render/web.clj:503-531`), while the
normal page already derives live HTML (`:547-566`). Historical captures remain
available through their identity because they are durable provider evidence
(`src/seon/context.clj:150-188`).

## Design surface: cluster-root system view

### Root and profile

The ruled `/` route includes root's message bar and asks the same walk for:

```clojure
(seon.render.walk/history
 {:seon.render.walk/root [:seon.cluster/name "default"]
  :seon.render.walk/profile :seon.render.profile/preview})
```

Agents already point to their cluster through
`:seon.cluster.agent/cluster` (`resources/seon/schemas/seon.cluster.agent.edn:37-46`),
so the existing bidirectional ref walk can discover every agent without a hand
list (`src/seon/render/walk.clj:63-137,182-206`). Each walked value already
carries `:seon.render.walk/changed-at`, derived as the newest transaction that
touched the entity (`src/seon/render/walk.clj:219-225,367-370`).

For each agent, derive candidate rendered values from that agent's history/page values,
order by `:seon.render.walk/changed-at` descending and stable path ascending,
and select the first value admitted by the preview profile. Nothing stores
“active,” “last rendered value,” or rank.
Flow state may render as an ordinary cluster/agent value; current oversight
already derives it without committing (`src/seon/oversight.clj:1-24,161-204`).

Selection is a pure deterministic reduce over the already-derived rows, not a
Datalog relation containing Hiccup. Given ordinary rows
`{:agent-id … :stable-path … :changed-at … :html …}`, reduce by agent and keep
the greatest `changed-at`; on a tie keep the lexicographically least stable
path. The resulting HTML remains opaque ordinary
data. This names no message, history, canvas, or error family.
`changed-at` is already derived from the newest datom touching
each walked entity (`src/seon/render/walk.clj:219-225,366-370`), while the path
is already part of walk ordering and text metadata
(`src/seon/render/walk.clj:535-550,609-669`).
A regression passes tied Hiccup candidates `[:div "z"]` and `[:div "a"]` in
both insertion orders and requires the stable-path winner `[:div "a"]`.

### Root's message bar and current view

`/` is the system view, not root's namespace page. Its fixed message bar posts
to root through the current `POST /agent/{id}/message` route
(`src/seon/render/route.clj:5-27`). Root does not appear as another
activity-sorted rendered value; root sees and changes the main view.

The target records one ordinary open entity for the cluster's root view:

```clojure
{:seon.render.view/id "default/root"
 :seon.render.view/root [:seon.cluster.agent/id "root"]
 :seon.render.view/subject [:seon.cluster/name "default"]}
```

`:seon.render.view/id` is the identity attribute; `root` and `subject` are
refs. The default subject is the cluster, producing the system view. A
task-specific subject is an agent ref, producing that agent's normal HTML
view in the same main element. There is no stored route, rank, HTML, or
browser-local duplicate. This is one cluster-global view state; every open
`/` subscriber sees the same morph.

Root's agent history injects the demonstrated retrieval form:

```clojure
my.agents.root=> (my.view/current)
{:seon.render.view/id "default/root",
 :seon.render.view/root [:seon.cluster.agent/id "root"],
 :seon.render.view/subject [:seon.cluster/name "default"]}
```

When the user asks to inspect invoice work, root changes navigation with:

```clojure
my.agents.root=> (my.view/show [:seon.cluster.agent/id "invoice"])
{:seon.render.view/id "default/root",
 :seon.render.view/subject [:seon.cluster.agent/id "invoice"]}
```

`my.view/show` is root's guarded database effect; its transaction replaces
only the `subject` ref. The existing database listener wakes the existing
render proc, whose normal package morphs the main element through the existing
SSE multicast (`src/seon/cluster/wake.clj:163-228`;
`src/seon/render/web.clj:596-643,673-784`). Task work still goes to the selected
agent through `my.message/send`; view navigation never assigns work.

Preview profile proposal:

```clojure
{:seon.render.profile/id :seon.render.profile/preview
 :seon.render.profile/token-budget 220
 :seon.render.profile/max-depth 4
 :seon.render.profile/max-children 12
 :seon.render.profile/composition :multiline}
```

All five keys use the current declared profile representation
(`resources/seon/schemas/seon.render.profile.edn:1-18`). There is no
system-only max-agent or staleness dial. With 20 agents the generic
`max-children 12` admits the first 12 activity-ordered rendered values and emits one
ordinary elision for the remaining 8 with total, next offset, profile, and
cluster requery identity. Responsive CSS changes columns, never spend. The 220,
4, and 12 values remain Phase 0 measurement inputs, then one whole-profile
config fact may override them; no independent preview knobs are declared.
The initial staleness decay is therefore one profile-derived step: activity
order gives the newest 12 full rendered values and collapses every older one into the
ordinary continuation. Any smoother decay is a later generic profile
capability only after measurement; it is not a system-view band table.

### Four-agent worked example

At one immutable database value the owner sees:

```text
default — 4 task agents
[Message root: Which agents are active, and what changed most recently?]

invoice                                      research
mid-turn · changed at t=536871319             active · changed at t=536871311
Latest settled result                        Latest changed canvas
[["inv-203" :invalid-tax-code]]              Import failure map: tax-code branch highlighted

qa                                           archive
parked · changed at t=536871280               parked · changed at t=536870901
Completed                                    Last result
Focused import regression passes.            {:seon.print/omitted 18,
                                               :seon.print/elision-unit :children,
                                               :seon.render.data/total 30,
                                               :seon.render.data/path [12],
                                               :seon.render.data/next-offset 12,
                                               :seon.render.profile/id :seon.render.profile/preview,
                                               :seon.print/requery-id
                                               [:seon.cluster.eval/id "[\"archive-run\" 4]"]}
```

The HTML target renders each agent's most-recently-changed rendered value at
the preview profile in that order. `invoice` can keep streaming elsewhere, but
its rendered value changes only when a form/result fact settles
under recommended PREVIEW-1; the in-flight token strip is not durable agent-history
truth. A settlement changes the cluster-root package, and the existing package
multicast sends delta or repair keyframe (`src/seon/render/web.clj:596-643,
673-784,1003-1085`). No system-view-specific feed exists.

The current embryo is `fleet-call`, which appends one oversight value outside
the walk (`src/seon/render/web.clj:336-375`). The target removes that append and
makes the cluster entity's ordinary walk produce each agent's
most-recently-changed rendered value. The
current bespoke fleet table at `src/seon/oversight.clj:261-301` is then deleted
or retained only as deep explicit docs, never as the root-page mechanism.

## Live NESTED-2 decision probe

I ran the demanded probe through MCP `eval_clj` against the live `default` JVM
on 2026-08-10. Every run used a fresh SCI fork and ordinary values; the
unrelated-basis case used `datahike.api/db-with` and committed no durable fact.
The realistic workload was 1,000 heterogeneous map nodes per target and an
80-member set of acquired candidates (74 current public schema candidates plus six
controlled candidates):

| Selection design | AI | HTML | Combined work |
|---|---:|---:|---|
| Current per-node full candidate scan | 306.991 ms | 303.866 ms | 687.871 ms first run; 2,000 candidate enumerations, 160,000 accepts validations, 48,000 return validations |
| Same database basis, current scan | — | — | 624.906 ms; zero retained selection hits |
| Unrelated data basis, current scan | — | — | 620.353 ms; zero retained selection hits |
| Acquired output-compatible candidates + per-node input fit | — | — | 4.720 ms first run; 2 candidate derivations, 1,998 acquired-candidate hits, 2,000 accepts validations, 160 return validations |
| Same program acquisition, indexed | — | — | 3.314 ms; zero derivations, 2,000 acquired-candidate hits, 2,000 accepts validations, zero repeated invocations |
| Unrelated data basis, indexed | — | — | 3.543 ms; same counts as same-program run |

The heterogeneous full scan classified 1,000 single-fit, 500 ambiguous, and
500 no-fit target/node pairs. An ambiguity probe supplied the same two
candidates in reverse orders and returned the same flat
`:seon.render/ambiguous` value with sorted candidate identities in 1 ms.
The target must print that error node; current `project-node*` may silently
fall through after a nested error (`src/seon/render.clj:294-353`), which the
class regression forbids.

The cycle falsifier installed controlled producers in an SCI fork. A
self-delegating producer ran once and returned total output in 92.823 ms cold.
The alternating A→B→A case completed in 147.748 ms cold with invocation order
`[A B]`: A and B each ran once and A never re-entered. This verifies the
rendering-stack fence passed through recursive projection
(`src/seon/render.clj:238-265,294-353`).

**Decision:** the probe refutes uncached NESTED-2 but supports indexed
NESTED-2. The output-compatible candidates are derived once into the acquired
program snapshot, keyed by program generation, target, and accepted output
shape. Per-node work validates only those acquired candidates' input contracts.
Unrelated data transactions do not invalidate it; program publication creates
a new acquired generation and therefore new acquired candidates with no check-then-act
cache. Existing retained render calls suppress repeated producer invocation.
The graduation budget is at most 20 ms of candidate-selection work per target
for this 1,000-node/80-candidate fixture.

## Option blocks and received owner rulings

Unsettled blocks keep exactly three options, simplest first. Received rulings
remain under their original block names and mark rejected alternatives
explicitly.

### NESTED — producer selection inside print values

Current top-level selection consults contract candidates
(`src/seon/render.clj:203-221`); `project-node*` deliberately consults only
explicit/schema producers (`:294-353`) after a measured recursive cycle.

1. **NESTED-1 — Keep nested schema-only.** Guarantee: the measured cycle class
   stays structurally absent. Cost/risk: low; document that an agent must
   register a schema before its producer affects agent-history children.
   Trade-off: the model-authored producer from the drive remains inert in its
   own returned value.
2. **NESTED-2 — Acquired candidates + per-node visit + stack guard
   (Recommended by live probe; owner wording awaits confirmation).** Each
   visited entity or value piece checks only the acquired top-level declared
   candidates; entity descent and value projection share that visit rule.
   Guarantee: top-level and nested precedence agree; ambiguity is a sorted flat
   error; a producer already on the stack cannot re-enter. Cost/risk: medium;
   derive the candidates with each acquired program generation and meet the
   20 ms fixture budget. Trade-off: a program publication re-derives them once.
3. **NESTED-3 — Cache final producer selection by admitted shape.** Guarantee:
   repeated shape-identical nodes skip even input validation. Cost/risk: high;
   shape identity, open-map extras, and value-sensitive predicates make cache
   validity substantially harder. Trade-off: rejects or misselects producers
   unless the cache key approaches the whole value.

### HUMAN — form shape for an inbound human message

Current inbound messages are durable rows with no `from` ref
(`src/seon/cluster/message.clj:258-304`), and the current renderer turns absence
into “outside this cluster” prose (`:429-463`).
`:seon.cluster.message/to` is already a recipient ref and inbound storage uses
`[:seon.cluster.agent/id id]` (`resources/seon/schemas/seon.cluster.message.edn:1-73`;
`src/seon/cluster/message.clj:258-304`). One owner confirmation remains only
for the printed `my.message/read` value: preserve that lookup ref
(recommended and shown in the examples) or resolve it to the recipient id
string. Storage is not open.

1. **HUMAN-1 — Inject the literal pulled map as `(identity <map>)`.** Guarantee:
   no new API. Cost/risk: low. Trade-off: duplicates the whole message in form
   and result, wastes tokens, and teaches no reusable read.
2. **HUMAN-2 — Inject `(my.message/read "id")` (Recommended).** Guarantee: every
   arrival is an honest rerunnable read whose value is the message fact. Cost/
   risk: medium; add one ambient-db read with positional and argument-map
   interfaces. Trade-off: one new public function.
3. **HUMAN-3 — Inject a raw `seon.db/pull` form.** Guarantee: no message-specific
   API and exact database semantics. Cost/risk: low. Trade-off: onboarding pays
   a long selector/lookup form and couples agent-history grammar to storage attrs.

### PROSE — model-authored prose in a reply

Current reply parsing attaches prose to a neighbouring form as exact `;` source
comments and rejects prose-only replies (`src/seon/cluster/reply.clj:155-268,
330-390`).

1. **PROSE-1 — Preserve comments only as submitted source (Recommended).**
   Guarantee: leading/trailing prose attached to a real form appears exactly
   once with that form; outputs never acquire comments. Cost/risk: low; current
   reader semantics survive. Trade-off: prose-only replies remain a loud
   no-forms result.
2. **PROSE-2 — Store prose as a string-returning injected form.** Guarantee:
   prose-only replies become agent-history values. Cost/risk: medium; the system must
   quote text and creates a form the model did not submit. Trade-off: weaker
   source fidelity.
3. **PROSE-3 — Add a durable prose fact family.** Guarantee: prose is queryable
   independently of forms. Cost/risk: high; introduces a second reply artifact
   and ordering relationship. Trade-off: violates the form/value economy unless
   the owner explicitly wants prose as first-class data.

### SCHEMA — replacement for raw schema walls

1. **SCHEMA-1 — Demonstrated docs retrieval plus on-demand depth
   (Owner-reframed recommendation).** Opening agent history executes real
   `seon.program/docs` calls on namespace identities. Their programmatically
   assembled values contain public functions and the schemas their contracts
   reference; deeper schema definitions appear only when requested. Guarantee:
   every lesson is rerunnable data, with no hand-written API list. Cost/risk:
   medium; one docs query owner. Trade-off: full definitions are follow-up docs.
2. **SCHEMA-2 — One compact input/output schema-key line per API.** Guarantee:
   agents see contract identities without Malli bodies. Cost/risk: medium-low.
   Trade-off: repeated schema names consume more context and still need doc.
3. **SCHEMA-3 — Compact every schema into a sentence.** Guarantee: every current
   schema stays visible. Cost/risk: high; generated prose rules must cover ~1,900
   declarations and can lie. Trade-off: retains the wall, only shorter.

### DOC — familiar prose versus returned data

1. **DOC-1 — Keep familiar printed documentation and return nil
   (Recommended).** Guarantee: existing REPL habit and return contract survive;
   `seon.program/docs` is the explicit data API. Cost/risk: low; both use one
   row-to-doc projection. Trade-off: scripts cannot use bare `doc` data.
2. **DOC-2 — Make doc return the value it prints.** Guarantee: one call is both
   readable and composable. Cost/risk: medium; silently changes today's nil
   return (`src/seon/sci/eval.clj:1111-1122`). Trade-off: existing code that
   relies on nil changes meaning, so this needs a new name rather than `doc`.
3. **DOC-3 — Make doc print nothing and return data.** Guarantee: result is
   ordinary data only. Cost/risk: medium; loses the familiar Clojure REPL
   printed representation.
   Trade-off: every human must learn the generic printer's deeper map output.

### OPENING — forms pinned in a fresh agent history

1. **OPENING-1 — Six forms: help, in-ns, require, two demonstrated namespace
   docs calls, task read.** Guarantee: the minimum candidate teaches genuine
   retrieval with no hand-written API list. Cost/risk: low. Trade-off: does not
   demonstrate durable authoring or graph introspection.
2. **OPENING-2 — The 12-form worked candidate (Recommended for experiment).**
   Guarantee: teaches navigation, deep doc, graph query, valid contract, call,
   contract readback, and task arrival with no deliberate fault. Cost/risk:
   medium, prepaid once. Trade-off: more initial tokens; experiment may delete
   several forms.
3. **OPENING-3 — Model-specific bootstrap selected from a database fact.**
   Guarantee: each model gets calibrated teaching. Cost/risk: high; requires a
   corpus/evaluation owner before minimum context is known. Trade-off: loses one
   generic agent-history prefix across providers.

### MINIMUM — experiment and judgment

1. **MINIMUM-1 — Greedy ablation of the 12-form candidate (Recommended).** Run
   the same fixed task suite and seed against full context, then remove one form
   family at a time; restore the first whose removal crosses a predeclared
   quality boundary. Guarantee: simple attributable deltas. Cost/risk: medium;
   interaction effects require a final pairwise pass. Judgment: task success,
   valid forms/receipts, contract correctness, help/doc/query use, total provider
   tokens, c:p, and time; no prose score alone.
2. **MINIMUM-2 — Factorial subset search.** Guarantee: captures interactions.
   Cost/risk: very high provider spend across 2^12 candidates. Trade-off: slow
   and statistically noisy.
3. **MINIMUM-3 — Let the model choose which context to request.** Guarantee:
   potentially smallest initial prefix. Cost/risk: high; first-turn ignorance
   makes the choice circular and adds a context negotiation turn. Trade-off:
   latency and a new protocol.

MINIMUM-1 uses five task families—function authoring, schema-driven data
modeling, query/debugging, message delegation, and recovery from one flat
error—with ten fixed seeds each, 50 attempts per condition. Attempts use the
same model descriptor/settings, opening program commit, task order, and fresh
refork per attempt. Mechanical judges score task facts, valid forms/receipts,
contract correctness, help/docs/query use, repair turns, total provider
tokens, c:p, and elapsed time; two blinded reviewers resolve only outcomes the
mechanical facts cannot decide. A candidate passes at ≥45/50 task success,
50/50 settlement, zero undeclared contracts, no task-family success drop over
one attempt versus full context, and no >10% increase in total provider tokens.
After greedy ablation, every removed pair is restored once to expose the
largest pairwise interaction. Seeds, prompts, receipts, judges, and reviewer
disagreements are committed with the report. These thresholds remain
owner-marked experiment inputs.

### DEBUG-LIVE — RULED live AI-context pane

1. **DEBUG-1 — Re-render the AI-context pane after every relevant settlement
   (RULED).** Retained interest/read evidence rejects unrelated database wakes
   before agent-history acquisition. The debug route shows current AI context;
   the normal route is the main HTML view. Cost/risk: medium; text projection
   joins retained calls. Trade-off: current context can differ from the last
   historical provider capture.
2. **DEBUG-2 — Commit a context capture after every form settlement
   (Rejected).** Guarantee:
   every intermediate pane is durable. Cost/risk: high write amplification and
   semantic corruption: a capture currently means exact pre-provider bytes
   (`src/seon/context.clj:150-188`). Trade-off: storage growth and misleading
   forensics.
3. **DEBUG-3 — Stream partial model text into the debug pane (Rejected).** Guarantee:
   keystroke-like following. Cost/risk: high; partial text is not yet forms or
   settled values and must remain channel-only. Trade-off: the debug pane ceases
   to equal model context or agent-history truth mid-stream.

### PREVIEW — content selected for each agent's system-view value

1. **PREVIEW-1 — Most recently changed settled rendered value (Recommended).** Guarantee:
   every agent's rendered value is selected by the same `changed-at` fact and
   profile; a
   agent-history result, canvas, message, or error can win without family logic.
   Cost/risk: medium; derive candidate values and deterministic tie-break.
   Trade-off: an old pinned canvas does not remain visible after newer work.
2. **PREVIEW-2 — Pinned canvas when present, otherwise last changed.** Guarantee:
   stable domain-specific focal previews. Cost/risk: medium-high; requires a
   declared pin fact and two precedence arms. Trade-off: activity can be hidden
   behind a stale canvas, and no current pin contract is ruled.
3. **PREVIEW-3 — Agent-history tail only.** Guarantee: every rendered value has uniform
   conversational content. Cost/risk: low. Trade-off: ignores an agent's latest
   changed canvas or other rendered value and recreates an agent-history-specific system
   view.

### SYSTEM-ROUTE — RULED root system view

1. **SYSTEM-1 — Make `/` the system view with root's message bar
   (RULED).** Guarantee: root sees the same current-view fact as the user and
   can morph the main view to an agent through one database effect plus the
   existing SSE package. Cost/risk: medium; adds the declared view fact and
   root navigation functions. Trade-off: view state is cluster-global.
2. **SYSTEM-2 — Add `/system` and retain root namespace at `/`
   (Rejected).** Guarantee: system view has an independent route.
   Cost/risk: medium; adds a second system-surface route beside the existing
   route table (`src/seon/render/route.clj:17-76`). Trade-off: contradicts the
   ruled root fleet landing and splits one view across two route contracts.
3. **SYSTEM-3 — Render root as another agent value (Rejected).** Guarantee:
   uniform agent presentation. Cost/risk: low. Trade-off: contradicts root's
   fleet surface and loses the fixed message bar/current-view relationship.

## Critique disposition

I read the independent critique end to end. No finding is answered by silence:

| Finding | Disposition in this revision |
|---|---|
| RC-1 | Fixed: one agent form/value history, two independent target/profile passes; a tee is only an optimization for identical fit options. |
| RC-2 | Fixed: removed max-agent and staleness-band keys; one ordinary preview profile and elision spend all rendered values. |
| RC-3 | Fixed: named pure `seon.render.walk/history`, its request/result, and every prompt/page/debug/system consumer. |
| RC-4 | Fixed: `seon.render/walk` keeps its string contract; the new data function has a new name and namespaced request keys. |
| RC-5 | Retained: no per-namespace obligation, activity rank, bespoke feed, comment output, or capture-as-live-authority. |
| IS-1 | Fixed: new `:seon.render/producer-request`; all 49 contracts/18 namespaces convert atomically before flattening or `:seon.render/unit` disappears. |
| IS-2 | Fixed: one agent-history entry owns form+optional value; exact total keys, same-tx ties, unsettled forms, attempt exclusion, and superseded-run exclusion are specified. |
| IS-3 | Fixed: docs returns a vector whose positions are doc-or-error; ambiguous unqualified identities refuse loudly. |
| IS-4 | Fixed: Hiccup selection is a pure reduce, with an insertion-order/tied-path regression. |
| IS-5 | Fixed: query output is a set, target-only values are labeled, and current `doc` nil behavior is preserved. |
| IS-6 | Fixed: deep examples derive a stable seed from schema identity plus program commit and expose both. |
| IS-7 | Fixed: zero producer execution is required only when retained read/program/profile evidence is current; gate counters distinguish query, selection, and invocation. |
| DE-1 | Fixed: fresh context now pulls its own namespace docs and honestly shows an empty public-function vector. |
| DE-2 | Fixed: 20 agents means 12 visible rendered values plus an ordinary elision of 8 under the proposed profile; CSS changes no spend. |
| DE-3 | Fixed: the debug route uses the exact agent profile/database value; the main route independently uses the page profile; identities, not bytes, correspond. |
| DE-4 | Fixed: DOC-1 preserves printed Clojure-like help plus nil and reserves returned data for docs. |
| Quiet configuration | Fixed: one whole preview profile, explicit debug/main profiles, bounded acquisition, stable example seed, and the ruled root route; no hidden bands or knobs. |
| PF-1 | Fixed: changed-basis acquisition is the first implementation gate, with 3.168 s/3,859-pull baseline and subsecond target. |
| PF-2 | Fixed: candidate identity acquisition is bounded before pull/fit; elision carries the continuation. |
| PF-3 | Fixed by probe: full scans are rejected; the acquired candidates and their invalidation/budget are normative. |
| PF-4 | Fixed: every wake checks retained interest/read evidence first; unrelated transactions cause zero agent-history queries, selections, or invocations. |
| PF-5 | Fixed: 20-agent changed-basis target is under 1 s with one cluster identity query, bounded pulls, and no per-agent namespace walk. |

## Measured baselines

These are before-values, not targets.

| Surface | Baseline on 2026-08-10 | Evidence |
|---|---:|---|
| Root stage-1 provider prompt | 12,161 tokens | Drive table, `model-authoring-drive-2026-08-10.md:100-108` |
| Root audited exact context | 15,917 estimated tokens, 18 rendered values | `context-quality-audit-2026-08-10.md:30-42` |
| Audited toolkit namespaces combined | 9,552 estimated tokens | `context-quality-audit-2026-08-10.md:36-40` |
| Audited `my.fs` / `my.web` rendered values | 2,843 / 2,249 estimated tokens | `context-quality-audit-2026-08-10.md:36-39` |
| Latest live-capture `my.background` | 801 estimated tokens | Door-mode measurement in this PRD lane, cluster `default`, latest root capture |
| Latest live-capture `my.edit` | 1,010 estimated tokens | Same probe |
| Latest live-capture `my.fs` | 990 estimated tokens | Same probe; current source reads the profile at `src/seon/render/ns.clj:320-325` |
| Latest live-capture `my.message` | 744 estimated tokens | Same probe |
| Latest live-capture `my.run` | 511 estimated tokens | Same probe |
| Latest live-capture `my.shell` | 885 estimated tokens | Same probe |
| Latest live-capture `my.web` | 997 estimated tokens | Same probe |
| Latest live-capture `my.agents.root` | 459 estimated tokens | Same probe |
| Warm namespace HTML | 17 ms | Observer route table, `model-authoring-observer-2026-08-10.md:331-343` |
| Changed-basis agent-page walk | 3.168 s, 178 render calls, 3,859 pulls, 21,560 datoms | Critique live probe, `repl-transcript-context-prd-critique-2026-08-10.md:287-302,385-407` |
| Unchanged-basis agent-page walk | 16.5 ms, zero render calls, zero pulls | Same critique probe |
| Nested full scan, 1,000 nodes × 2 targets × 80 candidates | 687.871 ms first; 624.906 ms same basis | Live NESTED-2 decision probe in this PRD |
| Nested acquired candidates, same fixture | 4.720 ms first; 3.314 ms same program; 3.543 ms unrelated data basis | Live NESTED-2 decision probe in this PRD |
| Alternating producer cycle | 147.748 ms cold, invocation order A then B once each | Live NESTED-2 decision probe in this PRD |
| Warm `/data` | 123–131 ms (report as 130 ms) | Observer, `model-authoring-observer-2026-08-10.md:337-354` |
| Drive completion:prompt ratio | 0.12–0.42 for the three directed turns | Driver, `model-authoring-drive-2026-08-10.md:100-105` |
| Debug no-target warnings | 200+ on one load | Issue, `debug-pages-receive-block-patches-for-elements-they-do-not-have.md:24-44` |

The revised fresh worked agent history above is **1,194 estimated tokens** on the
shipped uncalibrated estimator, including prompts, forms, and values. This
number is a candidate starting point, not the minimum-context answer.

## Implementation phases and lane-ready boundaries

No production phase begins until the owner confirms the remaining NESTED and
HUMAN wording plus the unresolved PROSE, DOC, MINIMUM, and PREVIEW blocks. “One
class regression” below means one recurring test for the structural failure
class, not one test per example.

### Phase 0 — freeze examples and measurement harness

Owned files:

- this PRD plus the affected architecture targets
  `docs/seon/architecture/context.md` and `docs/seon/architecture/ui.md`;
- one research record under
  `docs/prds/sci-execution-runtime/research/` containing exact baseline
  captures and experiment seeds.

Falsifier: two reviewers independently reconstruct every worked value from
current facts or mark it explicitly injected/target; no unexplained authored
output survives.

Exit: owner-approved agent-history bytes, debug pane, system rendered values,
remaining verdicts, success rubric, candidate agent/page/preview profiles, exact
20-agent zero-config spend, stable example-seed rule, bounded-acquisition
rule, and canonical system route. No numeric preview value becomes a config
dial independently; the only override is one whole declared profile value.

### Phase 1 — bound changed-basis acquisition before expanding the walk

Owned production files:

- `src/seon/render/walk.clj` and its existing tests;
- `src/seon/render.clj` only for retained read-evidence counters;
- the changed-basis measurement record under the PRD research directory.

Changes:

- query only ordered candidate identities: pinned bootstrap plus the newest
  agent-history identities admitted by `max-children`; do not pull the entire
  agent history first;
- pull only those bounded identities, then project and fit them; the elision
  carries omitted total, next offset, profile, and a cluster/agent requery
  identity through the current elision grammar (`src/seon/print.cljc:600-724`);
- retain current read evidence before any query, candidate selection, or
  producer invocation; an unrelated transaction yields counters
  `{:history-queries 0 :candidate-selections 0 :producer-invocations 0}`;
- measure a cold changed database value, not only the current unchanged reuse.

Shortest falsifiers:

```clojure
(agent-history-candidate-identities db agent-profile)
```

Class regressions:

1. Adding 10,000 old settled forms cannot increase pulled identity count above
   the selected profile's bounded prefix plus one elision continuation.
2. An unrelated transaction cannot execute an agent-history query, candidate
   selection, or producer.

Live proof: the critique baseline of 3.168 s/3,859 pulls/21,560 datoms falls
below 1.0 s, with bounded pulls independent of schema registry and the agent's
history size; unchanged basis stays in the measured tens-of-milliseconds
class (`src/seon/render.clj:401-447`).

### Phase 2 — convert the producer ABI and make the printer floor total

Owned production files:

- `src/seon/render.clj`, `src/seon/render/value.clj`, `src/seon/print.cljc`;
- all 18 producer owners: `src/seon/ai.clj`, `src/seon/bootstrap.clj`,
  `src/seon/cluster.clj`, `src/seon/cluster/agent.clj`,
  `src/seon/cluster/instruction.clj`, `src/seon/cluster/message.clj`,
  `src/seon/cluster/run.clj`, `src/seon/config.clj`, `src/seon/context.clj`,
  `src/seon/effect.clj`, `src/seon/oversight.clj`, `src/seon/problems.clj`,
  `src/seon/render/agent.clj`, `src/seon/render/ns.clj`,
  `src/seon/render/transcript.clj`, and `src/seon/render/web.clj`;
- `resources/seon/schemas/seon.render.edn` and print schemas;
- existing producer/render/print tests.

Changes:

- declare `:seon.render/producer-request` without changing
  `:seon.render/unit`;
- convert all 49 contracts, implementations, selectors, and invocation sites
  to nested value/context access in one phase; query zero old consumers before
  deleting the flattening branch at `src/seon/render.clj:76-108`;
- install the measured acquired output candidates for NESTED-2, loud sorted
  ambiguity, and the existing rendering-stack guard;
- delete unavailable substitute printed representations at
  `src/seon/render.clj:479-519` and
  `src/seon/render/walk.clj:424-438`;
- stress generated registered-schema values and sampled real database values
  independently through AI and HTML profiles. Use `emit-both` only for a
  same-profile cross-sink assertion (`src/seon/print.cljc:584-595`).

Shortest falsifier: query all functions whose input refs contain
`:seon.render/unit`; the phase may delete flattening only when the result is
empty and a message-map producer succeeds solely through
`:seon.render/value`.

Class regressions:

1. Domain keys can never collide with producer custody because the new request
   has no flat domain-key position.
2. Ambiguous nested producers can never fall through to generic output.
3. An A→B→A producer chain can never re-enter A.
4. A selected failure can never become an unavailable substitute.
5. An elision can never omit both requery identity and explicit refusal.

Live proof: the 1,000-node acquired-candidates fixture stays below 20 ms per
target; ordinary unqualified-key maps emit data in both targets; all registered
generator samples complete under the time limit.

### Phase 3 — one agent-history derivation and program docs

Owned production files:

- `src/seon/render/walk.clj`, `src/seon/render/transcript.clj` (delete),
  `src/seon/render/ns.clj`, `src/seon/render/agent.clj`;
- `src/seon/sci/eval.clj`, `src/seon/program.cljc`, and
  `src/seon/bootstrap.clj`; `eval.clj` owns the acquired-database wrapper and
  SCI binding for `seon.program/docs`, while `program.cljc` owns its pure doc
  projection;
- `resources/seon/bootstrap.edn`,
  `resources/seon/schemas/seon.cluster.agent.edn`, and the program-doc and
  agent-history schemas;
- existing history/walk/bootstrap/doc tests, renamed around surviving owners
  rather than retaining a test namespace for the deleted assembler.

Changes:

- add pure `seon.render.walk/history`; derive message-read and
  form-with-optional-receipt entries under the exact total order and
  superseded-run rule in this PRD;
- emit exact prompt+form+value through `seon.print`; delete `prose`,
  `compact-ai-text`, schema closures in context, comment metadata, six-entry
  tail policy, and per-family token budgets;
- implement `my.message/read` and `seon.program/docs`; under DOC-1 keep
  familiar `doc` output/nil while sharing the row projection;
- seed deep generated examples by schema identity plus program commit and
  expose both values;
- replace the 13 current bootstrap forms with the approved successful prefix;
- keep capture facts and provider prompt assembly, but make prompt acquisition
  request the agent-history text projection.

Shortest falsifier: render bootstrap run + one later run from one database
value; assert the exact bytes match the approved worked examples after replacing
identities/times with fixed fixtures.

Class regressions:

1. No displayed result line begins with a comment unless that comment is in
   `:seon.cluster.run.form/source`.
2. Every receipt with run+ordinal appears immediately after exactly one form;
   every form without a terminal receipt is visibly unsettled, never dropped.
   One fixture includes two same-transaction messages, two form ordinals, an
   unsettled form, and a superseded run, and compares exact identity order
   before comparing bytes.
3. Function/schema/test/namespace docs all come from one database query owner;
   an unknown identity is a flat in-position error.
4. No raw schema form enters initial context unless a displayed `doc` requested
   it.
5. Repeating a deep docs call at one program commit returns byte-equal example
   data; an unqualified ambiguous identity returns an in-position flat error.

Live proof: create a scratch agent, compare its exact first prompt to the
approved fresh agent history, execute one new form, and observe one appended
form/value pair on its next prompt.

### Phase 4 — live AI-context debug route and target-owned packages

Owned production files:

- `src/seon/render/web.clj` and relevant web tests;
- no two-pane CSS is introduced.

Changes:

- derive the debug AI-context pane from the same ordered agent history and
  exact prompt agent profile/database value;
- keep the main HTML projection on the normal route under the page profile;
- produce only the debug AI element in the debug registration;
- keep historical captures accessible but remove latest capture as the live
  pane authority;
- preserve one debug registration key, shared package revision/delta/keyframe,
  sliding-1 tap, and drain-or-close writer.
- check retained interest/read evidence before agent-history acquisition so an
  unrelated database wake produces zero agent-history queries, selections, calls,
  or outgoing delta.

Shortest falsifier: open debug and the normal view, settle one agent form, and
record separate route packages: debug targets exactly `debug-ai-<id>`; normal
targets only main-view elements.

Class regressions:

1. Every element ID in a tab's outgoing package belongs to that tab layout.
   This closes the 200+ no-target warning class.
2. At one database value, debug bytes equal prompt-acquisition bytes and the
   main view contains the same ordered history-entry identities.
3. An unrelated transaction cannot produce a debug agent-history query or package.

Live proof: browser console has zero `PatchElementsNoTargetsFound`; debug and
main routes advance through their own registered targets on the same
settlement; debug bytes equal the next acquired model context at the same
database value.

### Phase 5 — cluster-root preview walk

Owned production files:

- `src/seon/render/walk.clj`, `src/seon/render/web.clj`,
  `src/seon/render/route.clj`, `src/seon/oversight.clj`, `src/my/view.clj`,
  cluster/agent renderers and schemas;
- `resources/seon/schemas/seon.render.view.edn` for the view identity, root ref,
  subject ref, and `my.view` request/result contracts;
- the existing `resources/seon/schemas/seon.render.profile.edn` representation; no
  system-only profile keys;
- walk, web package, and oversight tests.

Changes:

- make `/` the system view with root's fixed message bar;
- add `my.view/current` retrieval and root's `my.view/show` guarded database
  effect over the one current-view entity;
- derive reverse agent connections from existing cluster refs;
- choose the ruled most-recently-changed rendered value by transaction and
  stable path;
- remove the outside-walk `fleet-call` append and bespoke root fleet table;
- publish the system view through the existing page package and feed.

Shortest falsifier: fixture with four agents and deliberately scrambled insert
order; update one non-history rendered value; assert that agent moves first and its
new rendered value becomes the preview without writing a rank fact. Then
`(my.view/show [:seon.cluster.agent/id "invoice"])` must commit exactly one
subject-ref replacement and morph `/` to invoice's normal HTML.

Class regressions:

1. Activity order cannot depend on stored rank or database insertion order.
2. A preview selection cannot name a family; it consumes walked values and the
   selected profile only.
3. The system view cannot create a second SSE route/package/mult.
4. Twenty agents under the candidate profile always produce 12 rendered values
   plus one ordinary elision of 8; responsive layout cannot change that spend.
5. Current view cannot diverge between root's demonstrated retrieval and the
   main element because both read the same subject ref.
6. Navigation cannot assign work; only `my.message/send` creates that message.

Live proof: four scratch agents matching the worked example; then a 20-agent
changed-basis fixture. One settlement produces one changed rendered value plus cluster
order, uses one shared cluster identity query and bounded pulls, performs no
per-agent namespace walk, and completes below 1.0 s.

### Phase 6 — minimum-context and performance experiment

Owned files:

- no production files during the experiment;
- reproducible drive harness under the existing evaluation surface and one
  committed research report with all prompts, settings, attempts, outcomes,
  and bootstrap variants.

Run the ruled MINIMUM option. Measure full candidate, then ablations. Reuse the
same model descriptor and fresh branch basis per condition. The verdict is a
table of success, malformed replies, forms/receipts, prompt/completion/reasoning
tokens, cache hits, wall time, and tool/doc/query use.

Falsifier: a “smaller” context that saves tokens but increases total provider
tokens, drops task success, or causes extra repair turns is not smaller in the
only economic sense that matters.

Exit: replace candidate profile/bootstrap values with the smallest passing
condition; retain the complete worked agent history in this PRD as the marked-up
design record.

### Phase 7 — integrated graduation

Run focused gates after each phase, then the relevant complete checkpoint and
live browser/agent drive. Graduation requires:

- exact approved fresh and mid-conversation histories;
- printer stress totality over all registered schema generators and sampled
  real database values;
- automatic authored producer selection through the ruled nested path;
- form/receipt completeness and requeryable elisions;
- unchanged basis executes zero producers in both projections;
- debug and main routes settle through only their registered targets with zero
  no-target warnings;
- four-agent system rendered values derive correct order/content through the existing
  multicast;
- measured fresh context and full task economics beat or match the baseline;
- deleted agent-history-assembler/schema-wall/comment/per-rendered-value paths
  have zero callers and zero schema declarations.

## Explicit non-goals

- No stored agent-history projection, activity rank, latest changed rendered
  value, debug snapshot, or preview output.
- No third markdown projection in this wave; AI text and HTML Hiccup are the two
  ruled printer targets.
- No per-namespace bootstrap/context obligations.
- No dashboard route, preview feed, or agent-history-specific delivery proc.
- No streaming model partial presented as a settled REPL form/value.
- No test-specific character clip, schema allowlist, or family hand list.

## Owner rulings incorporated (2026-08-10 markup round 1)

- **SYSTEM-ROUTE — RULED:** `/` is the
  system view (all agents, activity-sorted, each represented by its live
  most-recently-changed rendered value at the preview profile) WITH root's
  message bar. Root is an agent the user talks to, but root "isn't
  supposed to look like another agent" — its surface is the fleet.
  Root SEES WHAT THE USER SEES (the current view state joins root's
  context), and root CAN CHANGE THE VIEW into a specific agent's view
  when the user asks for something task-specific — navigation is
  root's effect; the work itself goes to the agent by message.
  View steering is the `:seon.render.view/subject` ref changed by
  `my.view/show` before Phase 5; the existing transaction wake and SSE package
  morph the main view, never a second channel.
- **DEBUG-LIVE — RULED:** the debug
  variant shows the AI-rendered context; THE MAIN VIEW IS the HTML
  side. No two-pane requirement.
- **SCHEMA/OPENING — REFRAMED:** the governing question is
  "what do we show by default," answered by DEMONSTRATED RETRIEVAL:
  opening forms include real, concise, programmatically assembled
  `seon.program/docs` calls whose genuine outputs include the functions in the
  requested namespace and the schemas those functions use. Those outputs
  replace both the schema wall and hand-written API lists.
- **NESTED — wording pending one confirmation:** "visit and
  only look at top-level declarations" — each visited node (entity or
  value piece) checks the acquired candidates of top-level declared
  producers; the walk's entity descent and value projection unify on
  that one visit rule (this IS indexed NESTED-2).
- **HUMAN — recipient printing pending one confirmation:** storage is already
  the `:seon.cluster.message/to` ref. The examples show its lookup-ref value;
  the only open confirmation is whether `my.message/read` preserves that value
  or resolves it to the recipient id string.

## Owner markup checklist

The implementation brief is ready only after the owner marks:

- the exact bytes of Worked examples A and B;
- the exact debug AI-context and main-view examples;
- the four-agent system-view example, root message bar, current-view values,
  and candidate preview profile;
- the NESTED visit wording and HUMAN recipient printed value;
- the remaining verdicts in PROSE, DOC, MINIMUM, and PREVIEW;
- the experiment success thresholds; and
- the exact demonstrated `seon.program/docs` outputs; its database-binding and
  pure-projection owners are fixed above.
