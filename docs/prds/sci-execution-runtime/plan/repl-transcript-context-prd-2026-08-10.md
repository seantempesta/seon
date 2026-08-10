---
type: prd
status: draft
tags: [prd, render, agent]
---

# The REPL transcript is the context, walk, bootstrap, and live view

This is the line-by-line design for the owner ruling in
[`agent-interface-economy-2026-08-10.md`](agent-interface-economy-2026-08-10.md).
It does not settle the option blocks below. It supplies one recommended initial
answer for each so the owner can accept, reject, or mark it up before production
work starts.

## Decision in one sentence

Derive one ordered session value from message, run-form, receipt, program, and
cluster facts; walk it from an agent root or cluster root; project that same
semantic value independently through `seon.print` to the AI text target under
the agent profile and to the HTML Hiccup target under the page profile; use it
for bootstrap, later context, namespace pages, the live two-pane debug overlay,
and the multi-agent system view. The one mechanism is the semantic unit vector,
not a requirement that two differently fitted targets share one physical tee.

This directly implements rulings 1–11: one mechanism; real REPL teaching;
results as printed data; two P targets; generic printer improvement; measured
minimum context; bulk pull; injected forms without namespace obligations;
automatic declared renderers; unchanged-basis reuse; and queryable bad-output
evidence
([ruled direction, lines 14–70](agent-interface-economy-2026-08-10.md#L14)).
It also preserves the older binding contract: context, transcript, and debug
page are one REPL-session render, and display is form followed by actual value,
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
The transcript, debug capture, and fleet append are current parallel assembly
owners to delete, not dependencies to preserve
(`src/seon/render/transcript.clj:26-226,639-778`,
`src/seon/render/web.clj:336-375,503-566`).

## The acceptance contract

The change is complete only when all nine statements are simultaneously true:

1. A fresh agent, a ten-turn agent, and root use the same session derivation.
2. Every visible session item is a form and the value that form produced; the
   only visible comments are comments the agent actually submitted as source.
3. AI and HTML receive the same ordered unit identities and semantic values,
   then run independent target/profile projection and fit passes. Their bytes
   and retained children may differ. `seon.print/emit-both` is used only when
   both targets have identical fit options; it is not the general two-profile
   contract (`src/seon/print.cljc:584-595,756-791`).
4. Bootstrap is a pinned prefix of real or explicitly injected session forms,
   not a second prompt assembler. The current bootstrap already stores ordered
   form sources (`resources/seon/bootstrap.edn:1-73`); this PRD changes their
   content and projection, not that source-of-truth shape.
5. No namespace must define a special context function. Producer selection
   remains declared/contract-derived at the one render boundary
   (`src/seon/render.clj:110-221`).
6. When program generation, selected profile, root, and retained database-read
   evidence are all current, each target performs zero session queries,
   candidate selections, and producer invocations. Current retained calls
   already compare static and read evidence before invocation reuse, but do not
   prove the broader acquisition gate (`src/seon/render.clj:401-447`); Phases
   1 and 4 add counters at that outer boundary.
7. The printer stress harness supplies generated registered-schema values and
   sampled real database values to both sinks; every failure becomes a printer
   grammar fix or an explicit producer exception, never a test-specific clip.
8. The debug overlay is the live session viewer: left is exactly the model's P
   text, right is the same session's P Hiccup, and each settled form can update
   both via one debug package.
9. The system view is the same walk rooted at the cluster/fleet entity with a
   preview profile. Agent order is derived from unit `changed-at`; no dashboard
   query, stored rank, or second delivery path exists.

## One semantic session owner

The pure target function is `seon.render.walk/session-units`. It receives an
open request map and returns the only ordered semantic value consumed by the
prompt, namespace page, debug overlay, and cluster-root previews:

```clojure
(seon.render.walk/session-units
 {:seon.render.walk/root [:seon.cluster.agent/id "task-agent-17"]
  :seon.render.walk/database-value db
  :seon.render.walk/profile :seon.render.profile/agent})
=>
{:seon.render.session/units [<open session-unit maps>]
 :seon.render.session/read-evidence <retained database evidence>}
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

One unit owns one form and its optional settled value, so adjacency is
structural rather than reconstructed by sorting separate entries. The exact
total order is:

```clojure
bootstrap unit  [0 ordinal form-id]
inbound message [1 at-ms 0 tx ordinal message-id]
run form        [1 run-opened-ms 1 ordinal form-id]
```

Stable identities break every same-transaction tie. A human message that
triggers a run sorts before that run because its recorded arrival precedes the
run opening. A form without a terminal receipt remains as an unsettled unit.
Active-run acquisition retains the current `not-join` exclusion of any run
named by `:seon.cluster.run/supersedes`
(`src/seon/render/transcript.clj:85-91`); attempts/reasoning are not independent
REPL units unless their bytes are exact submitted form source or a settled
value. The current multi-family rank order at
`src/seon/render/transcript.clj:400-416` is deleted, not carried forward.

## Worked example A — complete fresh-task context

This is the recommended starting candidate, written exactly as the model sees
it. It has 12 opening forms, no authored result summaries, no walk headers, and
no schema wall. Prompts are derived from each receipt's recorded namespace, as
the earlier live prototype required
([superseded design, lines 303–309](repl-session-context-2026-08-01.md#L303)).
The hexadecimal object address is the stored print face from the creation run;
it is not regenerated per prompt.

```text
user=> (help)
"You are task-agent-17 in a Seon cluster, operating a real Clojure REPL. Your reply is read as ordered forms; each form settles to the value printed below it. Batch independent forms because model round trips are expensive. Every function in the cluster program graph is callable. Public functions are the API; private functions remain inspectable. A defn with a complete :malli/schema becomes a durable program fact; scratch defs stay on your desk. The cluster is one graph database, so use dir, doc, seon.program/faces, and seon.db/q instead of guessing. Send work with my.message/send. Finish with my.run/complete or my.run/wait."
user=> (in-ns 'my.agents.task-agent-17)
#object[sci.lang.Namespace 0x1a2b3c4d "my.agents.task-agent-17"]
my.agents.task-agent-17=> (require '[my.run :as run] '[my.message :as message] '[seon.db :as db])
nil
my.agents.task-agent-17=> (seon.program/faces {:seon.program/identities ['my.run/complete 'my.run/wait] :seon.program/detail :summary})
| :seon.program/name | :seon.fn/arglists | :seon.fn/doc |
|--------------------+-------------------+--------------|
| my.run/complete    | ([result])        | Finish this run with a reply for its requester. |
| my.run/wait        | ([note])          | Finish this run without a reply and record what you await. |
my.agents.task-agent-17=> (seon.program/faces {:seon.program/identities ['my.message/send 'my.message/decline] :seon.program/detail :summary})
| :seon.program/name | :seon.fn/arglists | :seon.fn/doc |
|--------------------+-------------------+--------------|
| my.message/decline | ([to about reason]) | Decline an assignment and explain why to its sender. |
| my.message/send    | ([to content] [to content about]) | Address a message to another agent. |
my.agents.task-agent-17=> (seon.program/faces {:seon.program/identities ['my.agents.task-agent-17] :seon.program/detail :summary})
[{:seon.ns/name my.agents.task-agent-17,
  :seon.ns/doc nil,
  :seon.ns/public-functions []}]
my.agents.task-agent-17=> (seon.program/faces {:seon.program/identities [:my.run/result] :seon.program/detail :deep})
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
 :seon.cluster.message/to "task-agent-17",
 :seon.cluster.message/content "Inspect the failed invoice import, explain the cause, and propose the smallest durable fix."}
my.agents.task-agent-17=>
```

Every `seon.program/faces` result above is a **target result** until Phase 3
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
 :seon.cluster.message/to "root",
 :seon.cluster.message/content "Which agents are active, and what changed most recently?"}
my.agents.root=> (seon.db/q '[:find ?agent-id ?run-id
                              :where
                              [?agent :seon.cluster.agent/id ?agent-id]
                              [?agent :seon.cluster.agent/run ?run]
                              [?run :seon.cluster.run/id ?run-id]])
#{["invoice" "6177a48d-3dd0-44ef-b351-299bd0b8fe0a"]}
my.agents.root=> ;; Derive the newest changed unit; do not store an activity rank.
                 (seon.render.walk/session-units
                  {:seon.render.walk/root [:seon.cluster/name "default"]
                   :seon.render.walk/profile :seon.render.profile/preview})
{:seon.render.session/units
 [{:seon.cluster.agent/id "invoice",
   :seon.render.walk/changed-at 536871319,
   :seon.render.preview/content
   {:seon.cluster.run.form/source "(db/q invoice-query)"
    :seon.render/value [["inv-203" :invalid-tax-code]]}}
  {:seon.cluster.agent/id "root",
   :seon.render.walk/changed-at 536871311,
   :seon.render.preview/content
   {:seon.cluster.message/content "Which agents are active, and what changed most recently?"}}]}
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

The `session-units`, `seon.program/faces`, and `my.message/read` results in
these examples are normative target values. Ordinary query results retain
actual REPL collection semantics—the `:find` relation above is a set, not an
authored vector
(`src/seon/db.clj:779-823`).

## Exact unit grammar

There is no stored `:seon.repl.unit/kind`. Attribute presence identifies each
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
{:seon.repl/form-source "(total [3 5 8])"
 :seon.repl/namespace 'my.agents.task-agent-17
 :seon.repl/value {:seon.print/face :seon.print/number
                   :seon.print/value 16}
 :seon.cluster.run/id "bootstrap:task-agent-17"
 :seon.cluster.run.form/ordinal 9
 :seon.cluster.eval/id "[\"bootstrap:task-agent-17\" 9]"}
```

For a message, the unit is derived rather than committed as a fake eval:

```clojure
{:seon.repl/form-source "(my.message/read \"inbound-536871250-0\")"
 :seon.repl/namespace 'my.agents.task-agent-17
 :seon.repl/value <admitted message print node>
 :seon.cluster.message/id "inbound-536871250-0"}
```

`;; d0 · [:lookup]`, `;; unit=… branch=…`, `;; Volatile context metadata`,
and `;; (seon.render/walk …) => …` are replaced by nothing in the text target.
The corresponding facts remain on the unit, on the elision value, and as HTML
`data-*` attributes. Today those comment strings are constructed in
`src/seon/render/walk.clj:609-669`; deleting their assembly closes
[`render-walk-frames-values-as-comments.md`](../../../seon/issues/render-walk-frames-values-as-comments.md).

### Printer behavior by unit

- Text emits `namespace=> ` plus exact form source, newline, then the text sink's
  output for the admitted node. HTML emits one stable unit wrapper containing a
  form element and `seon.print/emit-hiccup` for the same node.
- A form with captured stdout emits stdout before the return value, matching a
  REPL; it does not convert stdout to a comment. Current receipts already carry
  optional `:seon.cluster.eval/output` (`src/seon/render/transcript.clj:43-59`).
- A flat error is data and uses the error producer or generic print floor; an
  untriaged execution error may use Clojure's execution-error face, which the
  current transcript builds at `src/seon/render/transcript.clj:539-566`.
- An elision is an ordinary value. The current printer already includes count,
  path, offset, profile, and requery/refusal in text
  (`src/seon/print.cljc:277-296`) and constructs those fields during fitting
  (`src/seon/print.cljc:600-724`).

## Concrete schema and API faces

### Current schema wall versus proposed summary

The before/after contract is literal:

| Current schema-wall line | Proposed face |
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
when several faces are returned:

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
(seon.program/faces
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
`(pr-str [schema-key program-commit-id])`; the face returns both seed and
program commit. Repeated calls at the same acquired program commit return
byte-equal example data; a generator failure returns a flat error in the
example slot, not an invented value. Seon's schema owner already derives
registered forms and properties (`src/seon/schema.clj:482-493,1974-2028`).

### Bulk faces function — exact target signature

```clojure
(seon.program/faces request)

request :=
[:map
 [:seon.program/identities
  [:vector [:or :qualified-symbol :qualified-keyword :seon.ns/name]]]
 [:seon.program/detail {:optional true} [:enum :summary :deep]]]

result :=
[:vector [:or :seon.program/face :seon.error/value]]
```

One mixed call:

```clojure
(seon.program/faces
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
that vector position, so one miss does not erase the other requested faces.
Unqualified function/test symbols and unqualified schema keywords are loud
`:seon.program/ambiguous-identity` errors; namespace identities validate as
`:seon.ns/name`. This removes the ambiguous bare-symbol arm rather than adding
precedence.
Under recommended DOC-1, bare `doc` prints the familiar readable face and
returns `nil`; callers that need data use `seon.program/faces`. Both invoke the
same projection owner. The existing private
`program-documentation` derivation is therefore replaced, not duplicated
(`src/seon/sci/eval.clj:1057-1122`). Concretely, the database acquisition and
SCI binding remain in `src/seon/sci/eval.clj`, while the row-to-face pure
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
| AI walk prose assembler | Comment headers/body/metadata at `src/seon/render/walk.clj:568-671` | Deleted. Ordered session units go straight to the printer text sink. |
| Separate transcript history assembler | Independent queries, ordering, six-entry policy, AI/HTML assembly at `src/seon/render/transcript.clj:26-226,432-455,639-778` | Namespace deleted after its queries/order move into the walk's one session derivation. No second transcript projection survives. |
| `compact-ai-text` schema dump | Full/compact namespace render paths and schema closure at `src/seon/render/ns.clj:354-475` | Deleted. Summary program faces are pulled on demand; namespace HTML may still show program members through the shared profile. |
| Per-family/per-unit size policy | `recent-entry-count` is hard-coded 6 at `src/seon/render/transcript.clj:26-30`; transcript invents its own token budget at `:700-752`; namespace budget logic sits at `src/seon/render/ns.clj:320-330` | Deleted. One render profile is selected at the consumer boundary and one `seon.print/fit` owner spends it. |
| Generic print grammar and two sinks | Sink protocol at `src/seon/print.cljc:10-14`; text/Hiccup/tee at `:98-220`; fitting at `:756-791` | Survives and is strengthened by the stress harness. |
| Bootstrap source forms | 13 current forms at `resources/seon/bootstrap.edn:1-73` | Survives as pinned injected session forms; failing lessons and schema-wall probes are replaced by the 12-form candidate. |
| Prompt acquisition and budget | One retained walk, distance fallback, calibrated budget at `src/seon/cluster/prompt.clj:140-225` | Survives, but acquires the session text projection; distance fallback becomes profile fitting, not “all or nothing” walk depth. |
| Exact provider capture | Capture writes prompt, basis, contributions, and token characters at `src/seon/context.clj:150-188`; schema marks prompt no-history at `resources/seon/schemas/seon.context.capture.edn:1-33` | Survives as forensic “what this provider call saw”; it is not the authority for the live debug pane. |
| Debug overlay | Left reads latest capture at `src/seon/render/web.clj:503-531`; right derives current page and the debug package wraps two panes at `:537-566` | Converted so both panes derive current session projections and settle together. Historical captures remain drillable facts. |
| Debug feed packages | Debug has its own registration key at `src/seon/render/web.clj:700-737,1030-1034`; package/delta/keyframe logic is shared at `:596-643` | Survives. Debug packages contain exactly two target IDs, preventing no-target patches by construction. |
| Fleet oversight special unit | Root page calls `oversight/unit` outside the walk at `src/seon/render/web.clj:336-375`; its bespoke table is `src/seon/oversight.clj:261-301` | Replaced by cluster-root walk + preview profile. Flow observations may remain ordinary values reachable from the cluster unit. |
| Agent renderer | Agent schema currently points both projections to transcript wrappers at `resources/seon/schemas/seon.cluster.agent.edn:1-14` | Updated to the one session producer; no status-plus-transcript composition. |

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
 "#:seon.print{:face :seon.print/list, :items [...]}" ]
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
It also verifies ruling #25's storage face: a small result stores the bounded
`result-edn`; the installed schema also offers `result-blob` and `result-size`
for values crossing the measured serialized-size knee
([program ruling, lines 2390–2404](README.md#L2390)). This sampled row was below
that knee and therefore carried no blob digest.

## Design surface: live debug overlay

### Mid-turn worked example

At basis 536871319, the message read and query have settled; the walk form is
submitted and has no value yet. The debug package contains exactly two
elements:

```clojure
#{"debug-ai-root" "debug-html-root"}
```

The owner-visible bytes inside the left `<pre>` are exactly:

```text
my.agents.root=> (my.message/read "inbound-536871311-0")
{:seon.cluster.message/id "inbound-536871311-0",
 :seon.cluster.message/to "root",
 :seon.cluster.message/content "Which agents are active, and what changed most recently?"}
my.agents.root=> (seon.db/q '[:find ?agent-id ?run-id
                              :where
                              [?agent :seon.cluster.agent/id ?agent-id]
                              [?agent :seon.cluster.agent/run ?run]
                              [?run :seon.cluster.run/id ?run-id]])
#{["invoice" "6177a48d-3dd0-44ef-b351-299bd0b8fe0a"]}
my.agents.root=> (seon.render.walk/session-units
                  {:seon.render.walk/root [:seon.cluster/name "default"]
                   :seon.render.walk/profile :seon.render.profile/preview})
```

There is no following value or prompt because that last form has not settled.
The right pane is the Hiccup target for those same three units, concretely:

```clojure
[:section {:id "debug-html-root"
           :class "seon-debug-body seon-debug-body-html"}
 [:article {:id "repl-message-inbound-536871311-0"}
  [:pre {:class "seon-repl-form"}
   "my.agents.root=> (my.message/read \"inbound-536871311-0\")"]
  [:dl {:class "seon-print-map"}
   [:dt ":seon.cluster.message/id"] [:dd "inbound-536871311-0"]
   [:dt ":seon.cluster.message/to"] [:dd "root"]
   [:dt ":seon.cluster.message/content"]
   [:dd "Which agents are active, and what changed most recently?"]]]
 [:article {:id "repl-form-6177a48d-0"}
  [:pre {:class "seon-repl-form"}
   "my.agents.root=> (seon.db/q '[:find ?agent-id ?run-id :where [?agent :seon.cluster.agent/id ?agent-id] [?agent :seon.cluster.agent/run ?run] [?run :seon.cluster.run/id ?run-id]])"]
  [:table {:class "seon-print-table"}
   [:tbody [:tr [:td "invoice"]
            [:td "6177a48d-3dd0-44ef-b351-299bd0b8fe0a"]]]]]
 [:article {:id "repl-form-6177a48d-1" :data-settled "false"}
  [:pre {:class "seon-repl-form"}
   "my.agents.root=> (seon.render.walk/session-units {:seon.render.walk/root [:seon.cluster/name \"default\"] :seon.render.walk/profile :seon.render.profile/preview})"]]]
```

The HTML may compose a map as a definition list and rows as a table because
the page profile differs from the text profile. Both projections must preserve
the exact three unit identities, but independent fitting may retain different
children inside a unit and represent omitted children as ordinary elision
values. The stable wrapper rule follows the current
debug pane IDs at `src/seon/render/web.clj:525-545`.

When ordinal 1 settles, the database transaction wakes the render proc; one
new debug package morphs both pane IDs. No per-unit normal-page IDs enter that
package. This closes the send-then-discover class documented at
[`debug-pages-receive-block-patches-for-elements-they-do-not-have.md`, lines 56–81](../../../seon/issues/debug-pages-receive-block-patches-for-elements-they-do-not-have.md#L56):
the debug registration produces only elements the debug layout declares.

The left pane is current session text under exactly the agent profile and
immutable database value used by prompt acquisition; a same-basis regression
compares its bytes to the acquired provider-context bytes. The right pane uses
the page profile independently, so only ordered unit identities—not bytes or
retained children—must match. The left pane is not “latest prompt capture.”
Current
code uses the latest capture and otherwise says no capture exists
(`src/seon/render/web.clj:503-531`), while the right pane already derives a live
page (`:547-566`). Historical captures remain available through their identity
because they are durable provider evidence (`src/seon/context.clj:150-188`).

## Design surface: cluster-root system view

### Root and profile

The route asks the same walk for:

```clojure
(seon.render.walk/session-units
 {:seon.render.walk/root [:seon.cluster/name "default"]
  :seon.render.walk/profile :seon.render.profile/preview})
```

Agents already point to their cluster through
`:seon.cluster.agent/cluster` (`resources/seon/schemas/seon.cluster.agent.edn:37-46`),
so the existing bidirectional ref walk can discover every agent without a hand
roster (`src/seon/render/walk.clj:63-137,182-206`). Each walked unit already
carries `:seon.render.walk/changed-at`, derived as the newest transaction that
touched the entity (`src/seon/render/walk.clj:219-225,367-370`).

For each agent, derive candidate blocks from that agent's session/page units,
order by `:seon.render.walk/changed-at` descending and stable path ascending,
and select the first unit admitted by the preview profile. Nothing stores
“active,” “last block,” or rank.
Flow state may render as an ordinary cluster/agent value; current oversight
already derives it without committing (`src/seon/oversight.clj:1-24,161-204`).

Selection is a pure deterministic reduce over the already-derived rows, not a
Datalog relation containing Hiccup. Given ordinary rows
`{:agent-id … :stable-path … :changed-at … :html …}`, reduce by agent and keep
the greatest `changed-at`; on a tie keep the lexicographically least stable
path. The resulting HTML remains opaque ordinary
data. This names no message, transcript, canvas, or error family.
`changed-at` is already derived from the newest datom touching
each walked entity (`src/seon/render/walk.clj:219-225,366-370`), while the path
is already part of walk ordering and text metadata
(`src/seon/render/walk.clj:535-550,609-669`).
A regression passes tied Hiccup candidates `[:div "z"]` and `[:div "a"]` in
both insertion orders and requires the stable-path winner `[:div "a"]`.

Preview profile proposal:

```clojure
{:seon.render.profile/id :seon.render.profile/preview
 :seon.render.profile/token-budget 220
 :seon.render.profile/max-depth 4
 :seon.render.profile/max-children 12
 :seon.render.profile/composition :multiline}
```

All five keys use the current declared profile face
(`resources/seon/schemas/seon.render.profile.edn:1-18`). There is no
system-only max-agent or staleness dial. With 20 agents the generic
`max-children 12` admits the first 12 activity-ordered cards and emits one
ordinary elision for the remaining 8 with total, next offset, profile, and
cluster requery identity. Responsive CSS changes columns, never spend. The 220,
4, and 12 values remain Phase 0 measurement inputs, then one whole-profile
config fact may override them; no independent preview knobs are declared.
The initial staleness decay is therefore one profile-derived step: activity
order gives the newest 12 full cards and collapses every older card into the
ordinary continuation. Any smoother decay is a later generic profile
capability only after measurement; it is not a system-view band table.

### Four-agent worked example

At one immutable database value the owner sees:

```text
default — 4 agents

invoice                                      root
mid-turn · changed at t=536871319             idle · changed at t=536871311
Latest settled result                        Human asked
[["inv-203" :invalid-tax-code]]              Which agents are active, and what changed most recently?

qa                                           archive
parked · changed at t=536871280               parked · changed at t=536870901
Completed                                    Last result
Focused import regression passes.            {:seon.print/face :seon.print/elided,
                                               :seon.print/omitted 18,
                                               :seon.print/elision-unit :children,
                                               :seon.render.data/total 30,
                                               :seon.render.data/path [12],
                                               :seon.render.data/next-offset 12,
                                               :seon.render.profile/id :seon.render.profile/preview,
                                               :seon.print/requery-id
                                               [:seon.cluster.eval/id "[\"archive-run\" 4]"]}
```

The HTML target renders four preview cards in that order. `invoice` can keep
streaming elsewhere, but its card changes only when a form/result fact settles
under recommended PREVIEW-1; the in-flight token strip is not durable session
truth. A settlement changes the cluster-root package, and the existing package
multicast sends delta or repair keyframe (`src/seon/render/web.clj:596-643,
673-784,1003-1085`). No system-view-specific feed exists.

The current embryo is `fleet-call`, which appends one oversight unit outside
the walk (`src/seon/render/web.clj:336-375`). The target removes that append and
makes the cluster entity's ordinary walk produce agent preview units. The
current bespoke fleet table at `src/seon/oversight.clj:261-301` is then deleted
or retained only as a deep explicit doc face, never as the root-page mechanism.

## Live NESTED-2 decision probe

I ran the demanded probe through MCP `eval_clj` against the live `default` JVM
on 2026-08-10. Every run used a fresh SCI fork and ordinary values; the
unrelated-basis case used `datahike.api/db-with` and committed no durable fact.
The realistic workload was 1,000 heterogeneous map nodes per target and an
80-candidate public roster (74 current public schema candidates plus six
controlled candidates):

| Selection design | AI | HTML | Combined work |
|---|---:|---:|---|
| Current per-node full candidate scan | 306.991 ms | 303.866 ms | 687.871 ms first run; 2,000 roster enumerations, 160,000 accepts validations, 48,000 return validations |
| Same database basis, current scan | — | — | 624.906 ms; zero retained selection hits |
| Unrelated data basis, current scan | — | — | 620.353 ms; zero retained selection hits |
| Acquired output-compatible roster + per-node input fit | — | — | 4.720 ms first run; 2 roster builds, 1,998 roster hits, 2,000 accepts validations, 160 return validations |
| Same program acquisition, indexed | — | — | 3.314 ms; zero builds, 2,000 roster hits, 2,000 accepts validations, zero repeated invocations |
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
NESTED-2. The output-compatible roster is derived once into the acquired
program snapshot, keyed by program generation, target, and accepted output
shape. Per-node work validates only that short roster's input contracts.
Unrelated data transactions do not invalidate it; program publication creates
a new acquired generation and therefore a new roster with no check-then-act
cache. Existing retained render calls suppress repeated producer invocation.
The graduation budget is at most 20 ms of candidate-selection work per target
for this 1,000-node/80-candidate fixture.

## Option blocks requiring owner rulings

Every block has exactly three options, simplest first. Cost is relative source,
test, and operational complexity. Recommendations are proposals, not implied
rulings.

### NESTED — producer selection inside print values

Current top-level selection consults contract candidates
(`src/seon/render.clj:203-221`); `project-node*` deliberately consults only
explicit/schema producers (`:294-353`) after a measured recursive cycle.

1. **NESTED-1 — Keep nested schema-only.** Guarantee: the measured cycle class
   stays structurally absent. Cost/risk: low; document that an agent must
   register a schema before its producer affects transcript children.
   Trade-off: the model-authored producer from the drive remains inert in its
   own returned value.
2. **NESTED-2 — Acquired output roster + per-node fit + stack guard
   (Recommended by live probe).** Guarantee: top-level and nested precedence
   agree; ambiguity is a sorted flat error; a producer already on the stack
   cannot re-enter. Cost/risk: medium; derive the roster with each acquired
   program generation and meet the 20 ms fixture budget. Trade-off: a program
   publication rebuilds the roster once. The measured 3.314–4.720 ms result
   decides this recommendation.
3. **NESTED-3 — Cache final producer selection by admitted shape.** Guarantee:
   repeated shape-identical nodes skip even input validation. Cost/risk: high;
   shape identity, open-map extras, and value-sensitive predicates make cache
   validity substantially harder. Trade-off: rejects or misselects producers
   unless the cache key approaches the whole value.

### HUMAN — form shape for an inbound human message

Current inbound messages are durable rows with no `from` ref
(`src/seon/cluster/message.clj:258-304`), and the current renderer turns absence
into “outside this cluster” prose (`:429-463`).

1. **HUMAN-1 — Inject the literal pulled map as `(identity <map>)`.** Guarantee:
   no new API. Cost/risk: low. Trade-off: duplicates the whole message in form
   and result, wastes tokens, and teaches no reusable read.
2. **HUMAN-2 — Inject `(my.message/read "id")` (Recommended).** Guarantee: every
   arrival is an honest rerunnable read whose value is the message fact. Cost/
   risk: medium; add one ambient-db read with positional and argument-map
   interfaces. Trade-off: one new public function.
3. **HUMAN-3 — Inject a raw `seon.db/pull` form.** Guarantee: no message-specific
   API and exact database semantics. Cost/risk: low. Trade-off: onboarding pays
   a long selector/lookup form and couples transcript grammar to storage attrs.

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
   prose-only replies become session values. Cost/risk: medium; the system must
   quote text and creates a form the model did not submit. Trade-off: weaker
   source fidelity.
3. **PROSE-3 — Add a durable prose fact family.** Guarantee: prose is queryable
   independently of forms. Cost/risk: high; introduces a second reply artifact
   and ordering relationship. Trade-off: violates the form/value economy unless
   the owner explicitly wants prose as first-class data.

### SCHEMA — replacement for raw schema walls

1. **SCHEMA-1 — API summaries plus on-demand deep doc (Recommended).** Guarantee:
   opening context shows name, arglists, one-line doc; schemas appear only when
   requested or required to interpret a displayed API refusal. Cost/risk:
   medium; implement one program-faces query. Trade-off: the full contract is a
   follow-up form.
2. **SCHEMA-2 — One compact input/output schema-key line per API.** Guarantee:
   agents see contract identities without Malli bodies. Cost/risk: medium-low.
   Trade-off: repeated schema names consume more context and still need doc.
3. **SCHEMA-3 — Compact every schema into a sentence.** Guarantee: every current
   schema stays visible. Cost/risk: high; generated prose rules must cover ~1,900
   declarations and can lie. Trade-off: retains the wall, only shorter.

### DOC — familiar prose versus returned data

1. **DOC-1 — Keep familiar printed documentation and return nil
   (Recommended).** Guarantee: existing REPL habit and return contract survive;
   `seon.program/faces` is the explicit data API. Cost/risk: low; both use one
   row-to-face projection. Trade-off: scripts cannot use bare `doc` data.
2. **DOC-2 — Make doc return the face it prints.** Guarantee: one call is both
   readable and composable. Cost/risk: medium; silently changes today's nil
   return (`src/seon/sci/eval.clj:1111-1122`). Trade-off: existing code that
   relies on nil changes meaning, so this needs a new name rather than `doc`.
3. **DOC-3 — Make doc print nothing and return data.** Guarantee: result is
   ordinary data only. Cost/risk: medium; loses the familiar Clojure REPL face.
   Trade-off: every human must learn the generic printer's deeper map output.

### OPENING — forms pinned in a fresh session

1. **OPENING-1 — Six forms: help, in-ns, require, two face pulls, task read.**
   Guarantee: smallest hand-designed prefix. Cost/risk: low. Trade-off: does not
   demonstrate durable authoring or graph introspection.
2. **OPENING-2 — The 12-form worked candidate (Recommended for experiment).**
   Guarantee: teaches navigation, deep doc, graph query, valid contract, call,
   contract readback, and task arrival with no deliberate fault. Cost/risk:
   medium, prepaid once. Trade-off: more initial tokens; experiment may delete
   several forms.
3. **OPENING-3 — Model-specific bootstrap selected from a database fact.**
   Guarantee: each model gets calibrated teaching. Cost/risk: high; requires a
   corpus/evaluation owner before minimum context is known. Trade-off: loses one
   generic transcript prefix across providers.

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
contract correctness, help/faces/query use, repair turns, total provider
tokens, c:p, and elapsed time; two blinded reviewers resolve only outcomes the
mechanical facts cannot decide. A candidate passes at ≥45/50 task success,
50/50 settlement, zero undeclared contracts, no task-family success drop over
one attempt versus full context, and no >10% increase in total provider tokens.
After greedy ablation, every removed pair is restored once to expose the
largest pairwise interaction. Seeds, prompts, receipts, judges, and reviewer
disagreements are committed with the report. These thresholds remain
owner-marked experiment inputs.

### DEBUG-LIVE — keeping the text pane current

1. **DEBUG-1 — Re-render after every relevant settlement
   (Recommended).** Retained interest/read evidence rejects unrelated database
   wakes before session acquisition. Guarantee: left and right share one
   immutable database value and one debug package; no extra durable data.
   Cost/risk: medium; text projection joins the same retained-call cache.
   Trade-off: left can differ from the last historical provider capture because
   it is current, which is the point.
2. **DEBUG-2 — Commit a context capture after every form settlement.** Guarantee:
   every intermediate pane is durable. Cost/risk: high write amplification and
   semantic corruption: a capture currently means exact pre-provider bytes
   (`src/seon/context.clj:150-188`). Trade-off: storage growth and misleading
   forensics.
3. **DEBUG-3 — Stream partial model text into the transcript pane.** Guarantee:
   keystroke-like following. Cost/risk: high; partial text is not yet forms or
   settled values and must remain channel-only. Trade-off: the left pane ceases
   to equal model context/session truth mid-stream.

### PREVIEW — content selected for each system-view card

1. **PREVIEW-1 — Most recently changed settled block (Recommended).** Guarantee:
   every card is selected by the same unit `changed-at` fact and profile; a
   transcript result, canvas, message, or error can win without family logic.
   Cost/risk: medium; derive candidate units and deterministic tie-break.
   Trade-off: an old pinned canvas does not remain visible after newer work.
2. **PREVIEW-2 — Pinned canvas when present, otherwise last changed.** Guarantee:
   stable domain-specific focal previews. Cost/risk: medium-high; requires a
   declared pin fact and two precedence arms. Trade-off: activity can be hidden
   behind a stale canvas, and no current pin contract is ruled.
3. **PREVIEW-3 — Transcript tail only.** Guarantee: every card has uniform
   conversational content. Cost/risk: low. Trade-off: ignores an agent's latest
   changed canvas or other block and recreates a transcript-specific system
   view.

### SYSTEM-ROUTE — where the cluster-root walk lives

1. **SYSTEM-1 — Add canonical `/system`; keep `/` as root's namespace page
   (Recommended).** Guarantee: the ruled route truth remains intact and the new
   cluster-root walk is explicit. Cost/risk: low; one route-table line and the
   existing package owner. Trade-off: owner opens `/system` for the wall.
2. **SYSTEM-2 — Make `/` the system walk; move root namespace to
   `/ns/my.agents.root`.** Guarantee: system wall is the landing page.
   Cost/risk: medium; changes the current root route contract
   (`src/seon/render/route.clj:17-76`). Trade-off: existing root links change.
3. **SYSTEM-3 — Put a system preview block inside root's namespace walk.**
   Guarantee: no new route. Cost/risk: high; conflates agent-root and
   cluster-root semantics and revives the current outside-walk append.
   Trade-off: the owner cannot address the system walk independently.

## Critique disposition

I read the independent critique end to end. No finding is answered by silence:

| Finding | Disposition in this revision |
|---|---|
| RC-1 | Fixed: one semantic unit vector, two independent target/profile passes; a tee is only an optimization for identical fit options. |
| RC-2 | Fixed: removed max-agent and staleness-band keys; one ordinary preview profile and elision spend all cards. |
| RC-3 | Fixed: named pure `seon.render.walk/session-units`, its request/result, and every prompt/page/debug/system consumer. |
| RC-4 | Fixed: `seon.render/walk` keeps its string contract; the new data function has a new name and namespaced request keys. |
| RC-5 | Retained: no per-namespace obligation, activity rank, bespoke feed, comment output, or capture-as-live-authority. |
| IS-1 | Fixed: new `:seon.render/producer-request`; all 49 contracts/18 namespaces convert atomically before flattening or `:seon.render/unit` disappears. |
| IS-2 | Fixed: one unit owns form+optional value; exact total keys, same-tx ties, unsettled forms, attempt exclusion, and superseded-run exclusion are specified. |
| IS-3 | Fixed: faces returns a vector whose positions are face-or-error; ambiguous unqualified identities refuse loudly. |
| IS-4 | Fixed: Hiccup selection is a pure reduce, with an insertion-order/tied-path regression. |
| IS-5 | Fixed: query output is a set, target-only values are labeled, and current `doc` nil behavior is preserved. |
| IS-6 | Fixed: deep examples derive a stable seed from schema identity plus program commit and expose both. |
| IS-7 | Fixed: zero producer execution is required only when retained read/program/profile evidence is current; gate counters distinguish query, selection, and invocation. |
| DE-1 | Fixed: fresh context now pulls its own namespace face and honestly shows an empty public-function vector. |
| DE-2 | Fixed: 20 agents means 12 visible plus an 8-card ordinary elision under the proposed profile; CSS changes no spend. |
| DE-3 | Fixed: debug left uses the exact agent profile/database value; right independently uses the page profile; identities, not bytes, correspond. |
| DE-4 | Fixed: DOC-1 preserves printed Clojure-like help plus nil and reserves returned data for faces. |
| Quiet configuration | Fixed: one whole preview profile, explicit debug profiles, bounded acquisition, stable example seed, and SYSTEM-ROUTE options; no hidden bands or knobs. |
| PF-1 | Fixed: changed-basis acquisition is the first implementation gate, with 3.168 s/3,859-pull baseline and subsecond target. |
| PF-2 | Fixed: candidate identity acquisition is bounded before pull/fit; elision carries the continuation. |
| PF-3 | Fixed by probe: full scans are rejected; the acquired output roster and its invalidation/budget are normative. |
| PF-4 | Fixed: every wake checks retained interest/read evidence first; unrelated transactions cause zero session queries, selections, or invocations. |
| PF-5 | Fixed: 20-agent changed-basis target is under 1 s with one cluster identity query, bounded pulls, and no per-agent namespace walk. |

## Measured baselines

These are before-values, not targets.

| Surface | Baseline on 2026-08-10 | Evidence |
|---|---:|---|
| Root stage-1 provider prompt | 12,161 tokens | Drive table, `model-authoring-drive-2026-08-10.md:100-108` |
| Root audited exact context | 15,917 estimated tokens, 18 units | `context-quality-audit-2026-08-10.md:30-42` |
| Audited toolkit namespaces combined | 9,552 estimated tokens | `context-quality-audit-2026-08-10.md:36-40` |
| Audited `my.fs` / `my.web` units | 2,843 / 2,249 estimated tokens | `context-quality-audit-2026-08-10.md:36-39` |
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
| Nested acquired roster, same fixture | 4.720 ms first; 3.314 ms same program; 3.543 ms unrelated data basis | Live NESTED-2 decision probe in this PRD |
| Alternating producer cycle | 147.748 ms cold, invocation order A then B once each | Live NESTED-2 decision probe in this PRD |
| Warm `/data` | 123–131 ms (report as 130 ms) | Observer, `model-authoring-observer-2026-08-10.md:337-354` |
| Drive completion:prompt ratio | 0.12–0.42 for the three directed turns | Driver, `model-authoring-drive-2026-08-10.md:100-105` |
| Debug no-target warnings | 200+ on one load | Issue, `debug-pages-receive-block-patches-for-elements-they-do-not-have.md:24-44` |

The revised fresh worked transcript above is **888 estimated tokens** on the
shipped uncalibrated estimator, including prompts, forms, and values. This
number is a candidate starting point, not the minimum-context answer.

## Implementation phases and lane-ready boundaries

No phase begins until the owner rules all ten option blocks. “One class
regression” below means one recurring test for the structural failure class,
not one test per example.

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

Exit: owner-approved transcript bytes, debug panes, cluster preview, all ten
option verdicts, success rubric, candidate agent/page/preview profiles, exact
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
  history identities admitted by `max-children`; do not pull all history first;
- pull only those bounded identities, then project and fit them; the elision
  carries omitted total, next offset, profile, and a cluster/agent requery
  identity through the current elision grammar (`src/seon/print.cljc:600-724`);
- retain current read evidence before any query, candidate selection, or
  producer invocation; an unrelated transaction yields counters
  `{:session-queries 0 :candidate-selections 0 :producer-invocations 0}`;
- measure a cold changed database value, not only the current unchanged reuse.

Shortest falsifiers:

```clojure
(session-candidate-identities db agent-profile)
```

Class regressions:

1. Adding 10,000 old settled forms cannot increase pulled identity count above
   the selected profile's bounded prefix plus one elision continuation.
2. An unrelated transaction cannot execute a session query, candidate
   selection, or producer.

Live proof: the critique baseline of 3.168 s/3,859 pulls/21,560 datoms falls
below 1.0 s, with bounded pulls independent of schema registry and session
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
- install the measured acquired output roster for NESTED-2, loud sorted
  ambiguity, and the existing rendering-stack guard;
- delete unavailable substitute faces at `src/seon/render.clj:479-519` and
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

Live proof: the 1,000-node roster fixture stays below 20 ms per target; ordinary
unqualified-key maps emit data in both targets; all registered generator
samples complete under the time limit.

### Phase 3 — one session derivation and program faces

Owned production files:

- `src/seon/render/walk.clj`, `src/seon/render/transcript.clj` (delete),
  `src/seon/render/ns.clj`, `src/seon/render/agent.clj`;
- `src/seon/sci/eval.clj`, `src/seon/program.cljc`, and
  `src/seon/bootstrap.clj`; `eval.clj` owns the acquired-database wrapper and
  SCI binding for `seon.program/faces`, while `program.cljc` owns its pure face
  projection;
- `resources/seon/bootstrap.edn`,
  `resources/seon/schemas/seon.cluster.agent.edn`, and the program-face/session
  schemas;
- existing transcript/walk/bootstrap/doc tests, renamed around surviving
  owners rather than retaining a transcript namespace test.

Changes:

- add pure `seon.render.walk/session-units`; derive message-read and
  form-with-optional-receipt units under the exact total order and
  superseded-run rule in this PRD;
- emit exact prompt+form+value through `seon.print`; delete `prose`,
  `compact-ai-text`, schema closures in context, comment metadata, six-entry
  tail policy, and per-family token budgets;
- implement `my.message/read` and `seon.program/faces`; under DOC-1 keep
  familiar `doc` output/nil while sharing the row projection;
- seed deep generated examples by schema identity plus program commit and
  expose both values;
- replace the 13 current bootstrap forms with the approved successful prefix;
- keep capture facts and provider prompt assembly, but make prompt acquisition
  request the session text projection.

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
3. Function/schema/test/namespace faces all come from one database query owner;
   an unknown identity is a flat in-position error.
4. No raw schema form enters initial context unless a displayed `doc` requested
   it.
5. Repeating a deep face call at one program commit returns byte-equal example
   data; an unqualified ambiguous identity returns an in-position flat error.

Live proof: create a scratch agent, compare its exact first prompt to the
approved fresh transcript, execute one new form, and observe one appended
form/value pair on its next prompt.

### Phase 4 — live debug overlay and target-owned packages

Owned production files:

- `src/seon/render/web.clj` and relevant web tests;
- CSS only if the approved two-pane example requires layout changes.

Changes:

- derive both debug panes from one ordered session value; project left with
  the exact prompt agent profile/database value and right independently with
  the page profile;
- use two debug package elements only, with retained text and Hiccup calls;
- keep historical captures accessible but remove latest capture as the live
  pane authority;
- preserve one debug registration key, shared package revision/delta/keyframe,
  sliding-1 tap, and drain-or-close writer.
- check retained interest/read evidence before session acquisition so an
  unrelated database wake produces zero session queries, selections, calls,
  or outgoing delta.

Shortest falsifier: open debug, settle one agent form, and record one revision
whose delta targets exactly `debug-ai-<id>` and `debug-html-<id>`.

Class regressions:

1. Every element ID in a tab's outgoing package belongs to that tab layout.
   This closes the 200+ no-target warning class.
2. At one database value, left bytes equal prompt-acquisition bytes and both
   panes contain the same ordered unit identities.
3. An unrelated transaction cannot produce a debug session query or package.

Live proof: browser console has zero `PatchElementsNoTargetsFound`; both panes
advance on the same settlement; the left bytes equal the next acquired model
context at the same database value.

### Phase 5 — cluster-root preview walk

Owned production files:

- `src/seon/render/walk.clj`, `src/seon/render/web.clj`,
  `src/seon/oversight.clj`, cluster/agent renderers and schemas;
- the existing `resources/seon/schemas/seon.render.profile.edn` face; no
  system-only profile keys;
- walk, web package, and oversight tests.

Changes:

- install the owner-ruled SYSTEM-ROUTE option;
- derive reverse agent connections from existing cluster refs;
- choose the ruled preview unit by changed transaction and stable path;
- remove the outside-walk `fleet-call` append and bespoke root fleet table;
- publish the system view through the existing page package and feed.

Shortest falsifier: fixture with four agents and deliberately scrambled insert
order; update one non-transcript block; assert that agent moves first and its
new block becomes the preview without writing a rank fact.

Class regressions:

1. Activity order cannot depend on stored rank or database insertion order.
2. A preview selection cannot name a family; it consumes walked units and the
   selected profile only.
3. The system view cannot create a second SSE route/package/mult.
4. Twenty agents under the candidate profile always produce 12 cards plus one
   ordinary 8-card elision; responsive layout cannot change that spend.

Live proof: four scratch agents matching the worked example; then a 20-agent
changed-basis fixture. One settlement produces one changed card plus cluster
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
condition; retain the complete worked transcript in this PRD as the marked-up
design record.

### Phase 7 — integrated graduation

Run focused gates after each phase, then the relevant complete checkpoint and
live browser/agent drive. Graduation requires:

- exact approved fresh and mid-conversation transcripts;
- printer stress totality over all registered schema generators and sampled
  real database values;
- automatic authored producer selection through the ruled nested path;
- form/receipt completeness and requeryable elisions;
- unchanged basis executes zero producers in both projections;
- debug panes settle together with zero no-target warnings;
- four-agent cluster preview derives correct order/content through the existing
  multicast;
- measured fresh context and full task economics beat or match the baseline;
- deleted transcript/schema-wall/comment/per-unit paths have zero callers and
  zero schema declarations.

## Explicit non-goals

- No stored transcript, activity rank, latest block, debug snapshot, or preview
  card.
- No third markdown projection in this wave; AI text and HTML Hiccup are the two
  ruled printer targets.
- No per-namespace bootstrap/context obligations.
- No dashboard route, preview feed, or transcript-specific delivery proc.
- No streaming model partial presented as a settled REPL form/value.
- No test-specific character clip, schema allowlist, or family hand list.

## Owner markup checklist

The implementation brief is ready only after the owner marks:

- the exact bytes of Worked examples A and B;
- the exact left/right debug example;
- the four-card system-view example and candidate preview profile;
- one verdict in each of NESTED, HUMAN, PROSE, SCHEMA, DOC, OPENING, MINIMUM,
  DEBUG-LIVE, PREVIEW, and SYSTEM-ROUTE;
- the experiment success thresholds; and
- whether `seon.program/faces` is the accepted concrete public name; its
  database-binding and pure-projection owners are fixed above.
