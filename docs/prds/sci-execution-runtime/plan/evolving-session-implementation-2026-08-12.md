---
type: prd
status: draft
tags: [prd, render, agent, context, runtime]
---

# Evolving-session implementation: the line-by-line contract

This is the implementation companion to
[the evolving-session decision record](evolving-session-prd-2026-08-12.md).
It preserves D1, D2, and T2 exactly and applies them under rulings 24–36 of
[the self-generating-context PRD](self-generating-context-prd-2026-08-11.md).
The inherited REPL grammar comes from
[the transcript PRD](repl-transcript-context-prd-2026-08-10.md). This revision
also applies every finding in
[the independent critique](evolving-session-implementation-critique-2026-08-12.md).

All source `file:line` claims in this revision are verified against committed
baseline `2cd22dc40`. That baseline is authoritative where the shared working
tree contains a later lane's uncommitted edits.

Status labels are literal:

- `[landed]` exists at this document's HEAD;
- `[target]` names the exact enabling change still required.

The implementation remains blocked on the choices under
[Open assumptions awaiting owner markup](#open-assumptions-awaiting-owner-markup).
No implementer may choose one silently.

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
2. **D1 and D2 replace ruling 18's fixed content roster.** Ruling 18's
   measured lesson—that the worked demonstration is load-bearing—survives.
   Its fixed HALF list no longer selects content. The action arc, explained
   closure, one canonical usage row, and declared-render admission in D1/D2
   are the selectors. A render profile still fits selected content and never
   selects it, preserving rulings 3 and 16.
3. **Ruling 8 supersedes transcript DOC-1.** Bare `doc` both prints the
   familiar Clojure lines and returns the acquired map with keys
   `:seon.fn/sym`, `:seon.fn/doc`, `:seon.fn/arglists`, and
   `:seon.fn/contract-lines`; the landed macro does so at
   [`src/seon/sci/eval.clj:1069-1125`](../../../../src/seon/sci/eval.clj#L1069).
4. **T2 supersedes two earlier phrases.** A passive fact change does not
   append settled history. T2 replaces “history appends” in ruling 6 and
   “appends passively to context” in ruling 36. A disposable pending page
   block may change; no run, form, receipt, or settled-history entry is
   created. The next qualifying addressed wake runs `:generate` and settles
   the delta. Whether an addressed error remains such a wake is an owner
   choice below.

## Current HEAD and dependency ledger

| Boundary | HEAD truth | Consequence |
|---|---|---|
| Opening derivation | `[landed]` `seon.bootstrap/next-entry` is `(next-entry request run-id) -> [:maybe :seon.repl/entry]`; it pulls and rebuilds the prefix on every call ([`src/seon/bootstrap.clj:220-282`](../../../../src/seon/bootstrap.clj#L220)). | The accumulator API below is `[target]`, not an undocumented extension of the landed request. |
| Entry source | `[landed]` one entry owns one optional comment and one form; `entry-source` joins them into one reader source ([`src/seon/bootstrap.clj:115-120`](../../../../src/seon/bootstrap.clj#L115), [`resources/seon/schemas/seon.repl.edn:10-17`](../../../../resources/seon/schemas/seon.repl.edn#L10)). | A comment never receives its own REPL prompt. |
| Generated append | `[landed]` `append-generated-call` permits exactly one next ordinal after the previous terminal receipt ([`src/seon/cluster/run.clj:747-816`](../../../../src/seon/cluster/run.clj#L747)). | Generation never gets ahead of execution. |
| Generate→call | `[landed]` commit `7d036203e` stores `:call`, changes it to `:generate` on the first append, changes it back through the guarded `generation-complete-call`, dispatches both situations from facts, and offsets provider-reply ordinals by the generated form count ([`src/seon/cluster/run.clj:640-698`](../../../../src/seon/cluster/run.clj#L640), [`src/seon/cluster/run.clj:747-864`](../../../../src/seon/cluster/run.clj#L747), [`src/seon/cluster/work.clj:570-650`](../../../../src/seon/cluster/work.clj#L570), [`src/seon/cluster/loop.clj:1576-1642`](../../../../src/seon/cluster/loop.clj#L1576)). | Phase 3 consumes this committed contract and its landed focused regressions. |
| Fork lifetime | `[landed]` each `resume-turn` creates a fresh turn fork ([`src/seon/cluster/loop.clj:1321-1345`](../../../../src/seon/cluster/loop.clj#L1321)); each committed program row is installed for later turns. | Generated forms and the provider reply do **not** share one fork. Each generated form gets a fresh fork rehydrated from committed program rows and the agent's defs; the later provider reply gets another. |
| Schema registration | `[landed]` `seon.schema/register!` is exactly `[key definition] -> key`, and the definition must round-trip as EDN ([`src/seon/schema.clj:1254-1321`](../../../../src/seon/schema.clj#L1254)). Admission rejects a named renderer without a coherent contract ([`src/seon/schema.clj:1472-1555`](../../../../src/seon/schema.clj#L1472)). | Renderer functions are defined and committed before registration; renderer identities are quoted symbols. |
| Completion | `[landed]` `my.run/complete` returns disposition/result ([`src/my/run.clj:133-149`](../../../../src/my/run.clj#L133)); settlement adds `:my.run/delivered-to` as an agent-id string or `:outside` ([`src/seon/cluster/loop.clj:398-425`](../../../../src/seon/cluster/loop.clj#L398), [`resources/seon/schemas/my.run.edn:1-25`](../../../../resources/seon/schemas/my.run.edn#L1)). | No completion-shape accretion is required. |
| Delta reads | `[landed]` `seon.db/since` returns a database value and accepts `(since basis)` or `(since database basis)`; `pull` accepts the current-database two-argument form or explicit three-argument form ([`src/seon/db.clj:859-899`](../../../../src/seon/db.clj#L859), [`src/seon/db.clj:1107-1119`](../../../../src/seon/db.clj#L1107)). | A since database is queried for numeric entity ids; each id is then pulled from the current database. |
| Observation basis | `[landed]` render calls record the database value's basis transaction, and derived history entries expose it ([`src/seon/render.clj:549-554`](../../../../src/seon/render.clj#L549), [`src/seon/render/walk.clj:775-824`](../../../../src/seon/render/walk.clj#L775)). | Durable delta cursors still need the receipt member named below. Settlement time is never the cursor. |
| Usage authority | `[landed]` the canonical source row is `my.run-test/the-lifecycle-walkthrough-is-executable-data` ([`test/my/run_test.clj:99-109`](../../../../test/my/run_test.clj#L99)). | The symbol remains stable while its status-render content and byte fixture are retargeted. |

## One generator, one entry at a time

An evolving-session gap-closure run opens on a qualifying addressed wake. This
is not the general run law: existing unanswered-background-result runs remain
valid and may open without a message
([`src/seon/cluster/work.clj:582-650`](../../../../src/seon/cluster/work.clj#L582)).

The recommended D5 mechanism is explicit and cross-owner:

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
([`src/seon/bootstrap.clj:256-281`](../../../../src/seon/bootstrap.clj#L256)).
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
nearest first. The exact admission cap is an owner choice below; it is a
content-selection dial, not a render profile.

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

The V1 D6 recommendation is additions-only because Datahike `since` contains
only datoms added after the time point
([`reference-code/datahike/src/datahike/api/specification.cljc:886-900`](../../../../reference-code/datahike/src/datahike/api/specification.cljc#L886)).
A retraction of an older datom emits no delta row. An add followed by retract
that is absent from the current database emits no per-id pull. Supporting
removed membership would require a history database and is outside D6's “one
since mechanism” choice.

After the owner declares the plan relationship, the exact provenance query is:

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
qualifying addressed wake” never means “all runs require a message”;
background-result runs remain untouched.

## Worked example A — executable frame, suite-owned bytes

There is one byte authority: the recurring source row
`my.run-test/the-lifecycle-walkthrough-is-executable-data` and the ordered
entry/result fixture that its test renders. `[target]` the retargeted test pins
every namespace prompt, comment-plus-form source, admitted result, and flat
error with no abbreviation. Fixture bytes and the source usage row change in
the same commit.

This document instead pins the derivation **frame**: scratch function →
contracted renderer → named schema registration → contracted status → one real
contract error → test declaration → declared AI output → completion. The form
spellings below are executable against HEAD; their automatic generation and
root-tile consumption are `[target]`.

```clojure
(help)

(in-ns 'my.agents.task-agent-9)

(require '[seon.db] '[seon.schema] '[my.run] '[clojure.test])

(dir seon.db)
(doc seon.db/q)
(dir seon.schema)
(doc seon.schema/register!)
(dir clojure.test)
(doc clojure.test/deftest)
(doc clojure.test/is)
(dir my.run)
(doc my.run/complete)

; Scratch first — prove the shape before contracting it.
(defn status []
  {:my.agents.task-agent-9/functions 0
   :my.agents.task-agent-9/tests 0})

(status)

; Define and commit the renderer before its quoted symbol is registered.
(defn status-ai
  "Render my namespace status."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [:my.agents.task-agent-9/functions :int]
      [:my.agents.task-agent-9/tests :int]]]
    :seon.render/ai]}
  [value]
  (let [functions (:my.agents.task-agent-9/functions value)
        tests (:my.agents.task-agent-9/tests value)]
    (str functions " function" (when (not= 1 functions) "s")
         ", " tests " test" (when (not= 1 tests) "s") ".")))

(seon.schema/register!
 :my.agents.task-agent-9/status
 [:map
  {:seon.render/ai 'my.agents.task-agent-9/status-ai}
  [:my.agents.task-agent-9/functions :int]
  [:my.agents.task-agent-9/tests :int]])

; Declaring the database value lets call preparation supply it when absent.
(defn status
  "My namespace's current state."
  {:malli/schema
   [:=> [:cat :seon.db/database-value]
    :my.agents.task-agent-9/status]}
  [database]
  {:my.agents.task-agent-9/functions
   (or (seon.db/q
        database
        '[:find (count ?function) .
          :in $ ?namespace-name
          :where
          [?namespace :seon.ns/name ?namespace-name]
          [?function :seon.fn/ns ?namespace]]
        'my.agents.task-agent-9)
       0)
   :my.agents.task-agent-9/tests
   (or (seon.db/q
        database
        '[:find (count ?test) .
          :in $ ?namespace-name
          :where
          [?namespace :seon.ns/name ?namespace-name]
          [?test :seon.test/ns ?namespace]]
        'my.agents.task-agent-9)
       0)})

(status)

(status "not-a-database-value")

(clojure.test/deftest status-render-is-total
  (clojure.test/is
   (= "2 functions, 1 test."
      (status-ai
       {:my.agents.task-agent-9/functions 2
        :my.agents.task-agent-9/tests 1}))))

(status)

(status-ai (status))

(my.run/complete
 "status is live: contracted, declared as my namespace's render, and its test is declared.")
```

The scratch call returns both keys. The registered definition uses the
two-argument `register!` contract and a quoted renderer symbol. The final
zero-argument call is legal because the function declares the leading
`:seon.db/database-value` supplied default; the caller-written wrong argument
still wins and produces the one contract error. The replica `deftest` is
declared, not executed, and deliberately carries no `:seon.test/usage` stamp.

After the test declaration, the explicit renderer call returns
`"2 functions, 1 test."`. `[target]` the history block for the preceding
status result selects the same declared renderer, and W3's root tile consumes
that same block. The settled completion value is exactly:

```clojure
{:my.run/disposition :completed
 :my.run/result
 "status is live: contracted, declared as my namespace's render, and its test is declared."
 :my.run/delivered-to "root"}
```

There is no claim that the replica test ran green; the source usage test is the
suite gate.

### Explained-set acceptance trace

Core forms and the injected `help`, `dir`, and `doc` are in the initial
explained set. A require or definition may introduce its own subject. Every
other non-core symbol must appear in the “satisfied before” column.

| Entry | Parsed non-core symbols | Satisfied before | Introduced after |
|---:|---|---|---|
| 1 | `help` | injected root form | situation refs and protocol namespace subjects |
| 2 | `my.agents.task-agent-9` | situation namespace ref | current namespace |
| 3 | `seon.db`, `seon.schema`, `my.run`, `clojure.test` | require is each namespace's introduction form | four namespace subjects |
| 4 | `seon.db` | required | public `seon.db/*` symbols, including `seon.db/q` |
| 5 | `seon.db/q` | entry 4 | `seon.db/q` contract |
| 6 | `seon.schema` | required | public schema symbols, including `seon.schema/register!` |
| 7 | `seon.schema/register!` | entry 6 | registration contract |
| 8 | `clojure.test` | required | public test symbols |
| 9 | `clojure.test/deftest` | entry 8 | `deftest` contract |
| 10 | `clojure.test/is` | entry 8 | `is` contract |
| 11 | `my.run` | required | public run symbols, including `my.run/complete` |
| 12 | `my.run/complete` | entry 11 | completion contract |
| 13 | `status` | definition introduces itself | local `status` |
| 14 | `status` | entry 13 | no new symbol |
| 15 | `status-ai` | definition introduces itself | local `status-ai` |
| 16 | `seon.schema/register!`, `my.agents.task-agent-9/status-ai` | entries 7 and 15 | named status schema |
| 17 | `status`, `seon.db/q` | entries 13 and 5 | contracted replacement of `status` |
| 18 | `status` | entry 17 | no new symbol |
| 19 | `status` | entry 17 | one flat contract error value |
| 20 | `clojure.test/deftest`, `clojure.test/is`, `status-ai` | entries 9, 10, and 15 | `status-render-is-total` test |
| 21 | `status` | entry 17 | no new symbol |
| 22 | `status-ai`, `status` | entries 15 and 17 | declared AI bytes |
| 23 | `my.run/complete` | entry 12 | terminal disposition |

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

The plan portion is `[target: owner decision PLAN]`. It awaits a declared
`:seon.cluster.agent/plan` relationship and referenced plan shape; the current
registry has neither
([`docs/seon/issues/agent-plan-has-no-declared-database-relationship.md:12-22`](../../../seon/issues/agent-plan-has-no-declared-database-relationship.md#L12)).
After that declaration, the provenance query in
[Delta basis and provenance](#delta-basis-and-provenance) runs, then the
numeric agent id is pulled from the current database. No
`:seon.cluster.agent/instructions` fact may be called a plan.

Acceptance is structural: prompt N remains a byte prefix of prompt N+1; the
provenance comment derives from the joined transaction facts; already
explained symbols generate no teaching; and every generated entry has a real
terminal receipt before the provider call.

## Phase ownership and dependency order

One phase has one lane owner, but an owner may need several files to close one
mechanism. Overlapping paths serialize explicitly.

| Phase | Exact owned paths | Exit and ordering |
|---|---|---|
| 0. Owner markup | This PRD only | Resolve CAP, ERROR-WAKE, PLAN, and D3–D6. No production work starts before it. |
| 1. Generation state + ordering | `resources/seon/schemas/seon.bootstrap.edn`; `resources/seon/schemas/seon.config.bootstrap.edn`; `config/default.edn`; `src/seon/bootstrap.clj`; `src/seon/render/walk.clj`; `test/seon/bootstrap_test.clj` | One pull per generation invocation; ruling-29 closure plus ruling-14 ready tie-break; prefix drift is flat. Starts only after protected bootstrap/walk owners are clear. |
| 2. Demonstration retarget | `src/my/run.clj`; `test/my/run_test.clj` | Stable usage-test symbol pins the complete T0 bytes; status-render frame replaces `largest`; source row alone retains usage metadata. Disjoint from Phase 1 and may run concurrently after markup. |
| 3. Generate→call integration | `resources/seon/schemas/seon.cluster.run.edn`; `src/seon/cluster/run.clj`; `src/seon/cluster/work.clj`; `src/seon/cluster/loop.clj`; focused run/work/turn tests | Consumes landed commit `7d036203e`. Fresh fork per generated entry; provider ordinals follow the generated prefix. |
| 4. Durable shown basis + T1 | `resources/seon/schemas/seon.cluster.eval.edn`; `src/seon/cluster/run.clj`; `src/seon/cluster/loop.clj`; `src/seon/bootstrap.clj`; focused eval/bootstrap tests | Serial after Phases 1 and 3 because it shares their owners. Exact since query, numeric current pulls, provenance, and self-erasure pass. |
| 5. Plan relationship | Recommendation: `resources/seon/schemas/seon.agent.plan.edn`; `resources/seon/schemas/seon.cluster.agent.edn`; `test/seon/schema_test.clj`; `test/seon/bootstrap_test.clj` | Blocked on PLAN. No instructions-as-plan substitution. If the owner removes plan from V1, this phase is deleted. |
| 6. Budget settlement | `resources/seon/schemas/my.run.edn`; `resources/seon/schemas/seon.cluster.run.edn`; `resources/seon/schemas/seon.cluster.loop.edn`; `src/my/run.clj`; `src/seon/cluster/run.clj`; `src/seon/cluster/loop.clj`; `test/my/run_test.clj`; `test/seon/cluster/run_test.clj`; `test/seon/cluster/turn_test.clj` | Blocked on D3; serial after Phase 3 because it shares run/loop. Both zero-before-open and zero-after-form transitions pass. |
| 7. Corrections | Recommendation: `src/seon/bootstrap.clj`; `src/seon/render/walk.clj`; `test/seon/bootstrap_test.clj`; `test/seon/render/history_test.clj` | Blocked on D4 and serial after Phases 1 and 4. If the owner chooses refresh runs, replace this path set with the ruled event owner plus `src/seon/cluster/run.clj`; no passive T2 append and no second correction mechanism may survive. |
| 8. Integration + drive | Focused gates; one research evidence file; no new production owner | Complete example fixture, markdown/citation gate, one model drive, independent observer, and remeasured MINIMUM. |

Only Phase 2 is presently disjoint enough to overlap Phase 1. Phases 3, 4, 6,
and 7 serialize where their exact path sets overlap. There is no blanket
“parallelize after Phase 1” claim.

## Open assumptions awaiting owner markup

These are owner choices, not implementer details. The recommendation is first
in each row.

| ID | Crisp choice and recommendation | What changes after the ruling |
|---|---|---|
| CAP | **Recommend:** declare `:seon.config.bootstrap/beyond-closure-token-budget` as a database config dial with V1 value `1024` estimated tokens. Count with `seon.ai.tokens/estimate`; admit whole entries only; stop before the first entry that would exceed the cap; order by graph distance then the total key above. Alternative: no beyond-closure admission in V1. | Recommendation adds the config schema/default and Phase 1 consumption. This is content selection and never aliases `:seon.config.render.agent/token-budget`. |
| ERROR-WAKE | **Recommend:** preserve addressed errors as existing operational wakes and state that ruling 36's message-only law governs evolving-session gap-closure caused by ordinary data changes. Alternative: revoke error wakes and require their owner to send a message. | The alternative changes the wake owner and requires a separate source phase; the recommendation changes only this PRD's scope wording. |
| PLAN | **Recommend:** declare a new `:seon.cluster.agent/plan` ref and a separately identified plan shape; do not reuse instructions or run plan-digest. Alternative: remove plan from the V1 delta example and prove provenance on another already-declared relationship. | Recommendation unblocks Phase 5 only after the plan's meaning and ordered item model are ruled. |
| D3 | **Recommend:** add a distinct `:my.run/budget-wait` arm to `:my.run/value`, preserving `:my.run/disposition :wait` but replacing the explicit wait note with `{:my.run.condition/kind :my.run.condition/budget-exhausted, :seon.cluster.run/turns-remaining 0}`. The loop appends and evaluates one system-authored pure constructor form, so the existing terminal receipt stores the ordinary value in `:seon.cluster.eval/result-edn` and completion delivery adds `:my.run/delivered-to`. Alternative: make exhaustion a run error, not a wait. | **Zero before open:** open/claim a generated run, append the budget form at ordinal 0, settle, deliver, close; no provider call. **Zero after a form:** append it at the next ordinal after the terminal receipt, settle, deliver, close. Phase 6 must name the constructor and transaction composition after the owner selects the arm. |
| D4 | **Recommend:** correction is re-observation inside the next qualifying addressed run; evolving sessions never invoke `refresh-tx`. Staleness is retained read evidence no longer current or retained program generation differing from the acquired one. Alternative: a program-generation event invokes `refresh-tx`, creating a correction run. | Recommendation keeps T2's no-passive-history rule and deletes the standalone correction phase; Phase 7 becomes a next-wake generator regression. The alternative must name the event owner and why it is not a second passive mechanism. |
| D5 | **Recommend:** approve the invocation-local, one-entry-at-a-time state machine specified above. Alternative: keep the landed per-entry re-pull until measurement proves it unacceptable. | Recommendation enables Phases 1 and 3. It does not emit an atomic suffix and never retains a fork. |
| D6 | **Recommend:** approve additions-only `since` deltas, durable read-basis receipt facts, and current-database numeric-id pulls; retractions are not V1 delta entries. Alternative: admit a history-database mechanism and broaden the ruling. | Recommendation enables Phase 4 without a new callable arity. The alternative reopens the data/query design and must be ruled before implementation. |

## Graduation gates

- The retargeted usage test owns all T0 bytes and passes through `bin/test`.
- The explained-set trace passes for every emitted entry; ruling 29 readiness
  and ruling 14 ready ordering are separately asserted.
- One generation invocation performs one membership pull, appends one entry at
  a time, and creates a fresh fork per entry.
- Prefix drift settles as the declared flat error; no generator exception
  escapes the loop.
- T1 uses the listing execution basis, an additions-only since database,
  numeric current pulls, and joined tx-meta provenance.
- A passive T2 change creates no run, form, receipt, or settled-history entry.
- Example A's declared AI bytes and root tile are the same block; the replica
  test is not falsely reported as executed.
- The generate→call dependency and its focused regressions are landed in
  commit `7d036203e`; executing that source lane's cluster gate is outside
  this documentation revision's verification boundary.
- Markdown validation is green and every `file:line` citation resolves at the
  implementation revision's HEAD.
