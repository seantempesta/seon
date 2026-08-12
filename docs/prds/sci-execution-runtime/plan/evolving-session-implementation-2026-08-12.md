---
type: prd
status: draft
tags: [prd, render, agent, context, runtime]
---

# Evolving-session implementation: the line-by-line contract

This is the implementation companion to
[the evolving-session decision record](evolving-session-prd-2026-08-12.md).
It preserves D1, D2, and T2 exactly and applies them under rulings 24–48 of
[the self-generating-context PRD](self-generating-context-prd-2026-08-11.md).
Only the surviving history/storage/printing grammar comes from
[the transcript PRD](repl-transcript-context-prd-2026-08-10.md). This revision
also applies every finding in
[the independent critique](evolving-session-implementation-critique-2026-08-12.md).

All source `file:line` claims in this revision are verified against committed
HEAD `316fce6ec`. Uncommitted shared-tree changes are not cited as landed
evidence.

Status labels are literal:

- `[landed]` exists at this document's HEAD;
- `[in flight]` is owned by another lane and must be replaced by that lane's
  commit citation when it lands; and
- `[target]` names the exact enabling change still required.

There are no remaining owner choices in this document. Ruling 40's
test-results-as-facts dependency is `[in flight]`; no evolving-session source
phase starts until its lane lands and this document cites that commit.

## Binding reconciliation

The following rules remove the apparent conflicts among the parent PRDs.

1. **Ruling 29 subsumes ruling 14.** The explained-set fixed point in
   [ruling 29](self-generating-context-prd-2026-08-11.md#rulings-29-31-owner-2026-08-12-afternoon--the-expansion-frame)
   governs whether an entry is dependency-ready: every non-core symbol must
   already be introduced, recursively, before the dependent entry may emit.
   The define-before-use rule and stable alphabetical clause in
   [ruling 14](self-generating-context-prd-2026-08-11.md#rulings-appended-after-w0-markup-owner-same-session)
   govern only the total order among entries that are already ready under
   ruling 29. Ruling 14 never admits an unexplained entry; ruling 29 never
   supplies an arbitrary tie-break.
2. **Ruling 32 subsumes ruling 11's injection language.** Gap-closure from
   `(pull, retained history)` emits a true missing form or emits nothing. No
   injection mechanism, injection counter, or standing-form roster survives.
3. **D1, D2, and ruling 37 replace the fixed content itemizations in rulings
   16, 18, and 22.** The action arc, explained closure, one canonical usage
   row, and declared-render admission select content. Ruling 18's measured
   lesson—that the worked demonstration is load-bearing—and ruling 22's
   complete-episode arc survive; the HALF roster and concise-until-cap rule do
   not. A render profile fits selected content and never selects it, as ruling
   3 requires.
4. **Ruling 8 supersedes transcript DOC-1.** Bare `doc` both prints the
   familiar Clojure lines and returns the acquired map with keys
   `:seon.fn/sym`, `:seon.fn/doc`, `:seon.fn/arglists`, and
   `:seon.fn/contract-lines`; the landed macro does so at
   [`src/seon/sci/eval.clj:1069-1125`](../../../../src/seon/sci/eval.clj#L1069).
5. **The transcript PRD contributes no opening mechanics.** Its ordering-key
   bands, pinned bootstrap candidate, HUMAN-2 synthesized read, and
   identity-hash object prints are superseded. Generated episodes,
   explained-set ordering, and the stable printer are authoritative.
6. **T2 supersedes two earlier phrases, and ruling 44 settles wake scope.** A
   passive fact change does not
   append settled history. T2 replaces “history appends” in ruling 6 and
   “appends passively to context” in ruling 36. A disposable pending page
   block may change; no run, form, receipt, or settled-history entry is
   created. The next message or error addressed to the agent runs `:generate`
   and settles the delta; all other changes stay passive.
7. **Ruling 37 governs D2's demonstration shape.** It supersedes D2's earlier
   scratch-first and namespace-state/status wording, but preserves D2's ruled
   purpose: the demonstration teaches a durable, visible contribution. The
   contribution is the existing render-selection mechanism—one function in
   the agent's own namespace accepting the existing `:seon.ns/ns` unit and
   returning `:seon.render/ai`. The named input and output specs precede the
   function; the function is written once. There is no new status family,
   schema registration, or redefinition arc.
8. **Ruling 38 makes the environment carriage-only.** `seon.env` carries the
   projection, database basis and connection, and agent scope needed by the
   derivation. Opening context is derived per agent from the walk; it is never
   stored in or on the environment.
9. **Ruling 39 removes fixed preview depth.** Root's preview of an agent is
   root's own gap-closure walk over that agent. The newest block remains in
   membership and unshown messages appear by their retained shown basis; no
   separate preview-depth constant or phase exists.
10. **Rulings 45–48 make rebirth an acceptance property.** Generation from
    current facts plus empty history must produce a compact valid episode;
    functions, declared renders, and green test-result facts close their own
    teaching gaps. Anything that must survive is a fact with a declared
    render. The plan dependency below therefore requires fact-backed statuses
    and a current-state render, never history replay or prose recovery.

## Current HEAD and dependency ledger

| Boundary | HEAD truth | Consequence |
|---|---|---|
| Opening derivation | `[landed]` `seon.bootstrap/next-entry` is `(next-entry request run-id) -> [:maybe :seon.repl/entry]`; it pulls and rebuilds the prefix on every call ([`src/seon/bootstrap.clj:220-284`](../../../../src/seon/bootstrap.clj#L220)). | The accumulator API below is `[target]`, not an undocumented extension of the landed request. |
| Entry source | `[landed]` one entry owns one optional comment and one form; `entry-source` joins them into one reader source ([`src/seon/bootstrap.clj:115-120`](../../../../src/seon/bootstrap.clj#L115), [`resources/seon/schemas/seon.repl.edn:10-17`](../../../../resources/seon/schemas/seon.repl.edn#L10)). | A comment never receives its own REPL prompt. |
| Generated append | `[landed]` `append-generated-call` permits exactly one next ordinal after the previous terminal receipt ([`src/seon/cluster/run.clj:747-816`](../../../../src/seon/cluster/run.clj#L747)). | Generation never gets ahead of execution. |
| Generate→call | `[landed]` commit `7d036203e` stores `:call`, changes it to `:generate` on the first append, changes it back through the guarded `generation-complete-call`, dispatches both situations from facts, and offsets provider-reply ordinals by the generated form count ([`src/seon/cluster/run.clj:640-698`](../../../../src/seon/cluster/run.clj#L640), [`src/seon/cluster/run.clj:747-864`](../../../../src/seon/cluster/run.clj#L747), [`src/seon/cluster/work.clj:570-650`](../../../../src/seon/cluster/work.clj#L570), [`src/seon/cluster/loop.clj:1584-1650`](../../../../src/seon/cluster/loop.clj#L1584)). | Phase 3 consumes this committed contract and its landed focused regressions. |
| Fork lifetime | `[landed]` each `resume-turn` creates a fresh turn fork ([`src/seon/cluster/loop.clj:1329-1353`](../../../../src/seon/cluster/loop.clj#L1329)); each committed program row is installed for later turns ([`src/seon/cluster/loop.clj:1499-1511`](../../../../src/seon/cluster/loop.clj#L1499)). | Generated forms and the provider reply do **not** share one fork. Each generated form gets a fresh fork rehydrated from committed program rows and the agent's defs; the later provider reply gets another. |
| Owning-namespace render selection | `[landed]` each walked member carries its explicitly acquired owning namespace into the render request ([`src/seon/render/walk.clj:427-462`](../../../../src/seon/render/walk.clj#L427), [`src/seon/render/walk.clj:508-528`](../../../../src/seon/render/walk.clj#L508)). The selector then chooses a unique public function in that namespace whose input accepts the actual render argument and whose output fits the requested projection, before consulting the schema declaration or floor ([`src/seon/render.clj:120-147`](../../../../src/seon/render.clj#L120), [`src/seon/render.clj:227-246`](../../../../src/seon/render.clj#L227)). | The demonstration defines one function accepting the existing `:seon.ns/ns` unit and returning `:seon.render/ai`; no schema registration or invented status value exists. |
| Completion | `[landed]` `my.run/complete` returns disposition/result ([`src/my/run.clj:133-149`](../../../../src/my/run.clj#L133)); settlement adds `:my.run/delivered-to` as an agent-id string or `:outside` ([`src/seon/cluster/loop.clj:398-425`](../../../../src/seon/cluster/loop.clj#L398), [`resources/seon/schemas/my.run.edn:1-25`](../../../../resources/seon/schemas/my.run.edn#L1)). | No completion-shape accretion is required. |
| Delta reads | `[landed]` `seon.db/since` returns a database value and accepts `(since basis)` or `(since database basis)`; `pull` accepts the current-database two-argument form or explicit three-argument form ([`src/seon/db.clj:859-899`](../../../../src/seon/db.clj#L859), [`src/seon/db.clj:1107-1119`](../../../../src/seon/db.clj#L1107)). | A since database is queried for numeric entity ids; each id is then pulled from the current database. |
| Observation basis | `[landed]` render calls record the database value's basis transaction, and derived history entries expose it ([`src/seon/render.clj:549-554`](../../../../src/seon/render.clj#L549), [`src/seon/render/walk.clj:775-824`](../../../../src/seon/render/walk.clj#L775)). | Durable delta cursors still need the receipt member named below. Settlement time is never the cursor. |
| Usage authority | `[landed]` the canonical source row is `my.run-test/the-lifecycle-walkthrough-is-executable-data` ([`test/my/run_test.clj:99-114`](../../../../test/my/run_test.clj#L99)). | The symbol remains stable while its namespace-render content and byte fixture are retargeted. |
| Test-result facts | `[in flight → cite its commit when it lands]` Ruling 40 requires the one runner to commit declared test-run/result facts before this build. | This is an external prerequisite, not a stub or a target owned by any phase below. The demonstration's test exchange, problems list, and after-change auto-runs consume the landed facts. |

## One generator, one entry at a time

An evolving-session gap-closure run opens on a message or error addressed to
the agent, as ruling 44 confirms. This is not the general run law: existing
unanswered-background-result runs remain valid and may open without either
([`src/seon/cluster/work.clj:582-650`](../../../../src/seon/cluster/work.clj#L582)).

Ruling 43 makes the one-pass D5 mechanism explicit and cross-owner:

```clojure
(seon.bootstrap/begin-generation request run-id)
=> :seon.bootstrap/generation-state

(seon.bootstrap/next-entry generation-state)
=> {:seon.bootstrap/entry :seon.repl/entry
    :seon.bootstrap/generation-state advanced-state}
;; or
=> {:seon.bootstrap/complete true
    :seon.bootstrap/generation-state final-state}
```

`[target]` `resources/seon/schemas/seon.bootstrap.edn` declares both open maps.
The state contains exactly the immutable pull result, ordered settled prefix,
explained symbols, shown collection bases, frontier introductions, and acquired
program generation. Its lifetime is one `generate-turn` invocation. It is
never proc state and never a database fact. On crash, `begin-generation`
reconstructs it from the run's terminal receipts.

The loop performs this sequence:

1. call `begin-generation` once, performing one membership pull;
2. derive **only the next** dependency-ready entry;
3. append it through `append-generated-tx` at the next ordinal;
4. evaluate it in a fresh fork and settle its terminal receipt;
5. advance the invocation-local state with the real admitted result; and
6. repeat until `:seon.bootstrap/complete`, then take the guarded
   generate→call transition.

There is no atomic multi-entry suffix, no execution ahead of receipts, and no
fork retained across entries. `[target]` this requires coordinated changes in
`src/seon/bootstrap.clj`, `src/seon/cluster/loop.clj`, and their schemas/tests;
Phase 1 therefore cannot truthfully own only `bootstrap.clj`.

Prefix drift is currently a thrown `ExceptionInfo`, not a flat refusal
([`src/seon/bootstrap.clj:256-284`](../../../../src/seon/bootstrap.clj#L256)).
`[target]` `:seon.bootstrap/prefix-drift-error` declares this value as a
class-marked error shape (`:seon.error/class` with its class-specific
members — the presence-not-kind model the scheduled kind migration installs
everywhere; see
[error-class-catalog-and-renderers-disagree](../../../seon/issues/error-class-catalog-and-renderers-disagree.md)):

```clojure
{:seon.error/message "The generated prefix differs from its settled receipts."
 :seon.cluster.run/id "bootstrap:task-agent-9"
 :seon.bootstrap/prefix-drift {:seon.bootstrap/expected ["expected source"]
                               :seon.bootstrap/actual ["settled source"]}}
```

Until the migration lands, the constructor may additionally carry the
current `:seon.error/kind` member for existing consumers (accretion — extra
key ignored after the migration deletes its readers), but NO regression in
this document may assert on `kind`: assertions dispatch on the declared
class shape (`:seon.bootstrap/prefix-drift` presence), so the migration
deletes the member without touching a single evolving-session test.

`begin-generation` returns that value, and the loop settles it through its
existing phase/error boundary. The direct uncaught call is deleted.

## Seed, admission, and total order

The initial ordered seed vector is data:

1. the situation root, form `(help)`;
2. the open run's trigger reached through `:seon.cluster.run/trigger`; and
3. exactly one source `:seon.test` row with `:seon.test/usage true` and a
   `:seon.fn/calls` edge to `my.run/walkthrough`.

The canonical row must have
`:seon.test/sym "my.run-test/the-lifecycle-walkthrough-is-executable-data"`.
Zero matches return `:seon.bootstrap/usage-walkthrough-absent`; more than one
returns `:seon.bootstrap/usage-walkthrough-ambiguous` with all matching
symbols. The source row retains `:seon.test/usage true`; the rendered
per-agent `deftest` intentionally omits that metadata, so replicas cannot
become competing canonical demonstrations.

Ruling 29 decides readiness. Among the ready set, ruling 14 decides the total
order using this ascending lexicographic key:

```clojure
[family-rank dependency-depth introduction-ordinal alphabetical-subject
 stable-database-identity form-ordinal]
```

- `family-rank` is root `0`, recursive explanation `1`, demonstration `2`,
  beyond-closure declared render `3`;
- `dependency-depth` is the number of unexplained edges to the form that
  requested the explanation;
- `introduction-ordinal` is the usage-vector ordinal for demonstration forms,
  the earliest dependent ordinal for explanations, and graph distance for
  beyond-closure forms;
- `alphabetical-subject` is the canonical qualified symbol spelling required
  by ruling 14;
- `stable-database-identity` is the declared identity lookup ref, never a hash;
  and
- `form-ordinal` is the render function's declared entry position.

Missing family, ordinal, or stable identity is a flat
`:seon.bootstrap/order-fact-missing` refusal naming the candidate. Current
candidate-vector order at
[`src/seon/render/walk.clj:708-769`](../../../../src/seon/render/walk.clj#L708)
is replaced. `sort-by str` remains valid only for ruling 14's qualified-symbol
slot; `pr-str` and hashes are not tie-breaks.

Beyond closure, only a shape with a declared render function is eligible,
nearest first. `[target]`
`:seon.config.bootstrap/beyond-closure-token-budget` is the one database
content-selection dial, with V1 default `1024` estimated tokens. Phase 1
counts with `seon.ai.tokens/estimate`, admits whole entries only, and stops
before the first entry that would exceed the cap. It never aliases a render
profile budget.

Determinism is over all four immutable inputs: database commit ID and basis
transaction, retained history identities and bytes, acquired program
generation, and the selected admission cap plus render profile. `[target]`
the generation-state schema carries `:seon.bootstrap/program-generation`;
the acquisition owner must derive it from the installed program rows. Same
inputs imply byte-identical entries. A hot-reloaded renderer without a new
program generation is not allowed to claim that guarantee.

## Delta basis and provenance

`shown` means the basis transaction of the immutable database value on which
the listing form actually executed. It is not the receipt settlement
transaction. `[target]` add
`:seon.cluster.eval/read-basis-transaction` to the receipt and settle request
schemas in `resources/seon/schemas/seon.cluster.eval.edn`; the loop copies the
evaluation database value's `seon.db/basis-t` into the same terminal
transaction. `shown` is then
`{collection-key :seon.cluster.eval/read-basis-transaction}`.

Ruling 43 makes the V1 D6 contract additions-only because Datahike `since`
contains only datoms added after the time point
([`reference-code/datahike/src/datahike/api/specification.cljc:886-900`](../../../../reference-code/datahike/src/datahike/api/specification.cljc#L886)).
A retraction of an older datom emits no delta row. An add followed by retract
that is absent from the current database emits no per-id pull. Supporting
removed membership would require a history database and is outside ruling
43's one-since-mechanism contract. No callable gains a delta arity.

After the separately owned plan relationship lands, the exact provenance
query is:

```clojure
(let [changes-db (seon.db/since 536871041)]
  (->> (seon.db/q
        changes-db
        '[:find ?agent ?tx ?user ?process
          :where
          [?agent :seon.cluster.agent/plan _ ?tx]
          [?tx :seon.db/user ?user]
          [?tx :seon.db/process ?process]])
       (sort-by (juxt second first))
       vec))
```

The since query yields numeric `?agent` ids and their transaction ids. Each
numeric id is then pulled separately from the **current** database. No lookup
ref is resolved inside the since database, because its identity datom may
predate the cursor.

## Situation and wake law

For the fixture `task-agent-9`, the trigger ids are deterministic. This map is
the exact landed key shape. The fixture includes one additional unanswered
message besides the trigger, hence unread count `1`; the open trigger itself is
excluded from that count by the query at
[`src/seon/bootstrap.clj:50-80`](../../../../src/seon/bootstrap.clj#L50).

```clojure
{:seon.cluster.agent/id "task-agent-9"
 :seon.cluster.agent/namespace-ref
 [:seon.ns/name my.agents.task-agent-9]
 :seon.cluster.agent/unread-message-count 1
 :seon.cluster.run/turns-remaining 6
 :seon.cluster.agent/protocol-namespaces [my.message my.run seon.db]
 :seon.cluster.agent/open-run-ref
 [:seon.cluster.run/id "bootstrap:task-agent-9"]
 :seon.cluster.run/trigger
 [:seon.cluster.message/id "bootstrap-task:task-agent-9"]}
```

This corrects the retired `namespace`, `message/unread`, `run/open`, and
`repl/protocol` spellings. “An evolving-session gap-closure run opens on a
message or error addressed to the agent” never means every run requires one;
background-result runs remain untouched.

## Worked example A — executable frame, suite-owned bytes

There is one byte authority: the recurring source row
`my.run-test/the-lifecycle-walkthrough-is-executable-data` and the ordered
entry/result fixture that its test renders. `[target]` the retargeted test pins
every namespace prompt, comment-plus-form source, admitted result, and flat
error with no abbreviation. Fixture bytes and the source usage row change in
the same commit.

This document instead pins the derivation **frame** and the boundary between
landed and target behavior. Ruling 37 makes the arc spec-first: discover the
already declared `:seon.ns/ns` input and `:seon.render/ai` output → define one
render function once → make one real wrong call → declare its usage test →
observe the correct AI output → complete. No scratch definition, redefinition,
new value family, or `register!` exchange exists.

The discovery prefix below uses only landed form owners. Agent creation already
emits `(help)` from `seon.cluster.agent/situation-form`
([`src/seon/cluster/agent.clj:114-120`](../../../../src/seon/cluster/agent.clj#L114)).
A namespace unit emits `(dir 'namespace)` from `namespace-form`
([`src/seon/render/ns.clj:619-635`](../../../../src/seon/render/ns.clj#L619)).
`[target]` Phase 2 changes `seon.cluster.agent/creation-tx` to add
`clojure.test` to the created agent namespace's `:seon.ns/requires`, so the
landed derivation can emit its `dir`; it also retargets
`seon.bootstrap/task-message` from `largest` to this namespace-render task.
The run already starts in the assigned agent namespace, so no generated
`in-ns` or `require` form is invented.

```clojure
(help)

(dir 'my.run)

(dir 'clojure.test)

; The data model comes first: :seon.ns/ns already names the namespace unit,
; and :seon.render/ai already names the projection other agents' walks read.
(defn render-namespace-ai
  "Render my namespace for other agents' walks."
  {:malli/schema [:=> [:cat :seon.ns/ns] :seon.render/ai]}
  [unit]
  (str "I control how other agents see " (:seon.ns/name unit) "."))

; A wrong input proves the named contract fails as a flat class-shaped value.
(render-namespace-ai :not-a-namespace-unit)

; Pin the intended namespace-unit rendering in a discoverable usage test.
(clojure.test/deftest namespace-render-usage
  (clojure.test/is
   (= "I control how other agents see my.agents.task-agent-9."
      (render-namespace-ai
       {:seon.ns/name 'my.agents.task-agent-9}))))

; Owning namespace plus contract fit makes this the closest render for my unit.
(render-namespace-ai {:seon.ns/name 'my.agents.task-agent-9})

(my.run/complete
 "Authored my namespace's AI render; other agents' walks now select it by owning namespace and contract fit, and its usage test is declared.")
```

The first three forms are the discovery prefix. The final five forms are
exactly the `[target]` vector returned by `my.run/walkthrough`; `usage-form`
already prepends the `my.run` namespace form
([`src/my/run.clj:85-111`](../../../../src/my/run.clj#L85)). Phase 2 retargets
that vector and the stable recurring test together. The wrong call's result is
the `:seon.instrument/contract-violated` class shape declared at
[`resources/seon/schemas/seon.instrument.edn:20-21`](../../../../resources/seon/schemas/seon.instrument.edn#L20),
with the violated function's qualified spelling as a string and a message. Its
exact bytes belong to the suite fixture; no assertion depends on the temporary
`:seon.error/kind` member. The replica `deftest` is declared, not executed, and
deliberately carries no `:seon.test/usage` stamp.

All eight displayed forms parse at this revision's HEAD. Their callable
surface is also landed: `help` is zero-argument and `dir` takes one namespace
([`src/seon/bootstrap.clj:10-18`](../../../../src/seon/bootstrap.clj#L10),
[`src/seon/bootstrap.clj:82-90`](../../../../src/seon/bootstrap.clj#L82)); SCI
derives the full public `clojure.test` namespace, including its real macros
([`src/seon/sci/eval.clj:217-225`](../../../../src/seon/sci/eval.clj#L217));
and `my.run/complete` takes one non-blank result string
([`src/my/run.clj:133-149`](../../../../src/my/run.clj#L133)). The input and
output contract names are existing registry declarations
([`resources/seon/schemas/seon.ns.edn:8-22`](../../../../resources/seon/schemas/seon.ns.edn#L8),
[`resources/seon/schemas/seon.render.edn:1-5`](../../../../resources/seon/schemas/seon.render.edn#L1)).

The successful call returns
`"I control how other agents see my.agents.task-agent-9."`. On another agent's
walk, the namespace unit carries owner `my.agents.task-agent-9`; the selector
checks public functions in that namespace against the actual unit and requested
`:seon.render/ai` output, so this unique function wins before the schema's
default render or structural floor. The settled completion value is exactly:

```clojure
{:my.run/disposition :completed
 :my.run/result
 "Authored my namespace's AI render; other agents' walks now select it by owning namespace and contract fit, and its usage test is declared."
 :my.run/delivered-to "root"}
```

There is no claim that the replica test ran green; the source usage test is the
suite gate.

### Explained-set acceptance trace

Core forms and the injected `help` and `dir` are in the initial explained set.
The pulled namespace relationships introduce `my.run` and `clojure.test`; the
namespace forms explain their public members before the demonstration. A
definition introduces its own subject. Every other non-core symbol must appear
in the “satisfied before” column.

| Entry | Parsed non-core symbols | Satisfied before | Introduced after |
|---:|---|---|---|
| 1 | `help` | injected root form | situation refs and protocol namespace subjects |
| 2 | `my.run` | pulled namespace relationship | public `my.run/*`, including `my.run/complete` |
| 3 | `clojure.test` | pulled namespace relationship | public test symbols, including `clojure.test/deftest` and `clojure.test/is` |
| 4 | `render-namespace-ai` | definition introduces itself; `:seon.ns/ns` and `:seon.render/ai` are acquired named specs | local renderer |
| 5 | `render-namespace-ai` | entry 4 | one class-shaped contract error value |
| 6 | `clojure.test/deftest`, `clojure.test/is`, `render-namespace-ai` | entries 3 and 4 | `namespace-render-usage` test |
| 7 | `render-namespace-ai` | entry 4 | declared AI bytes |
| 8 | `my.run/complete` | entry 2 | terminal disposition |

The recurring regression consumes this table's parsed form vector and checks
the before/after sets; plausible prose is not acceptance.

## Worked example B — executable delta frame, not byte authority

This is an ordering/shape example. It is deliberately not a prefix-byte
fixture; the recurring generator fixture owns literal numeric entity ids and
admitted output bytes.

The landed message-delta forms use the actual `since`, `q`, and `pull`
signatures:

```clojure
(let [changes-db (seon.db/since 536871041)]
  (->> (seon.db/q
        changes-db
        '[:find ?message ?tx
          :where
          [?message :seon.cluster.message/to _ ?tx]])
       (sort-by (juxt second first))
       vec))

(seon.db/pull
 '[:seon.cluster.message/id
   :seon.cluster.message/content
   {:seon.cluster.message/from [:seon.cluster.agent/id]}]
 177)

(seon.db/pull
 '[:seon.cluster.message/id
   :seon.cluster.message/content
   {:seon.cluster.message/from [:seon.cluster.agent/id]}]
 183)
```

The fixture makes `177` and `183` the two numeric ids returned by the listing
query; the pulls use the current database supplied by call preparation. The
query is additions-only. Each listing and pull settles at consecutive
ordinals before a model call.

The plan portion is `[target: external plan-model dependency]`. It awaits a declared
`:seon.cluster.agent/plan` relationship and referenced plan shape; the current
registry has neither
([`docs/seon/issues/agent-plan-has-no-declared-database-relationship.md:12-22`](../../../seon/issues/agent-plan-has-no-declared-database-relationship.md#L12)).
This is a separately owned missing data model, not an evolving-session owner
choice or permission to invent the relationship in Phase 4.
After that declaration, the provenance query in
[Delta basis and provenance](#delta-basis-and-provenance) runs, then the
numeric agent id is pulled from the current database. No
`:seon.cluster.agent/instructions` fact may be called a plan.

Acceptance is structural: prompt N remains a byte prefix of prompt N+1; the
provenance comment derives from the joined transaction facts; already
explained symbols generate no teaching; and every generated entry has a real
terminal receipt before the provider call.

## Budget exhaustion is a typed wait

Ruling 41 force-settles `:wait` when turns remaining reaches zero. `[target]`
`:my.run/budget-exhausted` is a presence marker (`[:= true]`), and
`:my.run/budget-wait` is a distinct arm of `:my.run/value`:

```clojure
{:my.run/disposition :wait
 :my.run/budget-exhausted true
 :seon.cluster.run/turns-remaining 0}
```

`[target]` the pure zero-argument `my.run/budget-exhausted` constructor
returns that ordinary value. The one in-transaction
`seon.cluster.run/force-budget-wait-call` owns both zero transitions: before
open it opens and claims the generated run and appends the constructor at
ordinal 0; after a terminal generated form it appends the constructor at the
next ordinal. The loop evaluates and settles that form through the ordinary
receipt path, delivery adds `:my.run/delivered-to`, and the run closes without
a provider call. The requester therefore sees the typed condition; the agent
stays alive and its next addressed wake includes the prior condition in the
retained history.

## Corrections are re-observations

Ruling 42 makes correction the same read at a newer database basis. At the
next message or error addressed to the agent, stale read evidence or a newer
acquired program generation makes the gap generator emit that read again. Its
new terminal receipt appends; the prior entry and bytes remain unchanged;
newest-basis selection wins in blocks. Evolving sessions never call
`refresh-tx`, write apology/meta entries, mutate an entry, or introduce a
second correction mechanism.

## Phase ownership and dependency order

One phase has one lane owner, but an owner may need several files to close one
mechanism. Overlapping paths serialize explicitly.

| Phase | Exact owned paths | Exit and ordering |
|---|---|---|
| 0. Test-result facts prerequisite | No paths in this PRD; ruling 40's separate lane owns the runner and result declarations. | `[in flight → cite its commit when it lands]`. The one runner commits declared result facts. No later phase builds a stub or starts before this dependency lands. |
| 1. Generation state + ordering | `resources/seon/schemas/seon.bootstrap.edn`; `resources/seon/schemas/seon.config.bootstrap.edn`; `config/default.edn`; `src/seon/bootstrap.clj`; `src/seon/render/walk.clj`; `test/seon/bootstrap_test.clj` | Ruling 43: one pull per generation invocation, one-entry incremental state, ruling-29 closure plus ruling-14 ready tie-break, exact `1024`-token beyond-closure admission, and flat prefix drift. Starts only after protected bootstrap/walk owners are clear. |
| 2. Demonstration retarget | `src/seon/bootstrap.clj`; `src/seon/cluster/agent.clj`; `src/my/run.clj`; `test/seon/bootstrap_test.clj`; `test/seon/cluster/agent_namespace_test.clj`; `test/my/run_test.clj` | Stable usage-test symbol pins the complete T0 bytes; the task message is retargeted; `clojure.test` becomes a declared required namespace so its landed namespace form is derivable; the spec-first owning-namespace renderer replaces `largest`; source row alone retains usage metadata and ruling 40's result facts report actual runs. Serial after Phase 1 because it shares bootstrap owners. |
| 3. Generate→call integration | `resources/seon/schemas/seon.cluster.run.edn`; `src/seon/cluster/run.clj`; `src/seon/cluster/work.clj`; `src/seon/cluster/loop.clj`; focused run/work/turn tests | Consumes landed commit `7d036203e`. Fresh fork per generated entry; provider ordinals follow the generated prefix. |
| 4. Durable shown basis + T1 | `resources/seon/schemas/seon.cluster.eval.edn`; `src/seon/cluster/run.clj`; `src/seon/cluster/loop.clj`; `src/seon/bootstrap.clj`; focused eval/bootstrap tests | Ruling 43: exact since query, additions-only deltas, numeric current pulls, zero new callable arities, provenance, and no emission for gaps already closed by retained history. Serial after Phases 1 and 3 because it shares their owners. |
| 5. Plan relationship dependency | No paths in this PRD; the linked plan-model issue owns the declaration and its proof. | Not an owner choice. Ruling 47 requires a real `:seon.cluster.agent/plan` ref, fact-backed statuses, and a separately identified plan shape whose declared render derives current state; instructions, plan digests, and history replay remain forbidden substitutes. |
| 6. Budget settlement | `resources/seon/schemas/my.run.edn`; `resources/seon/schemas/seon.cluster.run.edn`; `resources/seon/schemas/seon.cluster.loop.edn`; `src/my/run.clj`; `src/seon/cluster/run.clj`; `src/seon/cluster/loop.clj`; `test/my/run_test.clj`; `test/seon/cluster/run_test.clj`; `test/seon/cluster/turn_test.clj` | Ruling 41: `force-budget-wait-call` appends and settles the typed `:wait`, delivers it, and closes without a provider call. Both zero-before-open and zero-after-form transitions pass. Serial after Phase 3 because it shares run/loop. |
| 7. Corrections | `src/seon/bootstrap.clj`; `src/seon/render/walk.clj`; `test/seon/bootstrap_test.clj`; `test/seon/render/history_test.clj` | Ruling 42: the next message/error wake re-observes the same stale read at a newer basis; newest basis wins and old bytes remain. Serial after Phases 1 and 4; no `refresh-tx`, passive T2 append, meta-entry, or second correction mechanism survives. |
| 8. Integration + drive | Focused gates; one research evidence file; no new production owner | Complete example fixture, markdown/citation gate, one model drive, independent observer, remeasured MINIMUM, ruling 39 root preview from the same gap closure with no preview-depth constant, and a real ruling-45 reborn episode beside its original queryable history. |

Phase 2 serializes after Phase 1 because both own bootstrap derivation and its
regression. Phases 3, 4, 6, and 7 likewise serialize where their exact path
sets overlap. There is no blanket “parallelize after Phase 1” claim.

## Graduation gates

- Ruling 40's test-result-facts lane has landed and this document cites its
  commit; no stubbed result path remains.
- The retargeted usage test owns all T0 bytes and passes through `bin/test`.
- The explained-set trace passes for every emitted entry; ruling 29 readiness
  and ruling 14 ready ordering are separately asserted.
- One generation invocation performs one membership pull, appends one entry at
  a time, creates a fresh fork per entry, and never performs the deleted
  per-form re-pull.
- Prefix drift settles as the declared flat error; no generator exception
  escapes the loop.
- T1 uses the listing execution basis, an additions-only since database,
  numeric current pulls, joined tx-meta provenance, and zero new callable
  arities.
- A passive T2 change creates no run, form, receipt, or settled-history entry.
- Messages and errors addressed to the agent both wake it; unrelated data
  changes remain passive.
- Zero turns force-settles and delivers the typed budget-exhausted `:wait` in
  both zero-before-open and zero-after-form cases, without a provider call.
- A correction appends the same read at a newer basis, keeps the old bytes
  unchanged, and creates no refresh run or meta-entry.
- Example A's namespace unit selects the agent-authored AI render by explicit
  owning namespace plus unique input/output contract fit; another agent's walk
  sees those bytes, and the replica test is not falsely reported as executed.
- Root's preview is the same gap closure over the agent; no fixed preview depth
  or second preview mechanism exists.
- The environment carries derivation inputs and contains no opening context.
- From current facts and empty history, a real reborn episode is compact and
  valid; demonstrated functions, declared renders, and green tests are not
  retaught, current fact renders preserve durable meaning, and the superseded
  history remains queryable.
- The generate→call dependency and its focused regressions are landed in
  commit `7d036203e`; executing that source lane's cluster gate is outside
  this documentation revision's verification boundary.
- Markdown validation is green and every `file:line` citation resolves at the
  implementation revision's HEAD.
