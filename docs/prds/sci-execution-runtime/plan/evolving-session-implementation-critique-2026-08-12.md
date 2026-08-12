---
type: research
status: active
tags: [research, render, agent, context, runtime]
---

# Independent critique — evolving-session implementation, 2026-08-12

## Verdict

**Revision is required before implementation.** The document has a coherent
spine, but a deliberately unimaginative implementer cannot execute it without
making design decisions. The two worked examples are not executable against
the current tree, the proposed one-pass contract crosses three source owners
while its phase claims one, and several later rulings are not explicitly
reconciled with earlier ones.

I read the subject, the immediate evolving-session parent, the 36-ruling
self-generating-context parent, and the transcript parent end to end. I checked
the named contracts and examples against `bc1732d26` plus the shared working
tree as observed on 2026-08-12. I did not mutate a cluster or edit production
source. The generate-to-call work was in flight during this pass; that did not
block read-only verification. It subsequently landed as commit `7d036203e`,
and the revised subject cites the committed seam and landed focused
regressions rather than the observed shared-tree diff.

## Blocking ambiguities

### BA-1 — The one-pass fold has no executable interface or retention owner

The proposed request/response at lines 51-67 is not an extension of the
current function contract. Current `seon.bootstrap/next-entry` is
`(next-entry request run-id) -> [:maybe :seon.repl/entry]`; `request` is a
`:seon.render.walk/request`. It pulls, reconstructs settled rows, derives the
episode, validates the prefix, and returns exactly one entry
([`src/seon/bootstrap.clj:220-284`](../../../../src/seon/bootstrap.clj#L220)).
None of `:seon.bootstrap/pull`, `history`, `fold`, `explained`, `shown`, or
`frontier` is declared in the current schema population
([`resources/seon/schemas/seon.render.walk.edn:35-53`](../../../../resources/seon/schemas/seon.render.walk.edn#L35)).

The proposed lifetime is also undecidable. “Derivable from history” and
“carrying it is disposable acceleration” do not say whether the value is local
to one function call, proc state between single-entry calls, or stored state.
Current `generate-turn` appends one entry and invokes `resume-turn`; each
`resume-turn` creates a fresh fork
([`src/seon/cluster/loop.clj:1329-1353`](../../../../src/seon/cluster/loop.clj#L1329),
[`src/seon/cluster/loop.clj:1584-1650`](../../../../src/seon/cluster/loop.clj#L1584)).
Current `append-generated-call` intentionally prevents generation ahead of
execution ([`src/seon/cluster/run.clj:747-816`](../../../../src/seon/cluster/run.clj#L747)).
Therefore “emit the ready suffix in one pass,” “execute at ordinals `0..n`,”
“same fork,” and “Phase 1 owns only `bootstrap.clj`” cannot all be true.

**Exact fix:** choose and specify one mechanism. The simpler compatible shape
is: reconstruct an invocation-local accumulator once from the settled
system-authored prefix; derive only the next dependency-ready entry; append and
execute it under the existing ordinal/terminal-receipt fence; discard the
accumulator; repeat without re-pulling by carrying the immutable pull result in
the run-loop invocation. If the intended optimization is instead an atomic
multi-entry suffix followed by one fold, name the new open-map schemas and the
required `bootstrap.clj`, `run.clj`, and `loop.clj` changes, including how later
forms can depend on earlier real values. State explicitly whether generated
prefix forms and the later provider reply use separate fresh forks rehydrated
from committed program rows and the agent's defs. Delete “same fork” unless the
loop actually retains one fork across generate-to-call.

### BA-2 — The arc seed and beyond-closure cap are not derivable

“The open run's trigger message and the demonstration's shape” does not name
the seed rows, their order, how the canonical demonstration is selected, or
what happens when it is absent or ambiguous. “Declared render function,
nearest-first, to the cap” does not name the cap's schema fact, value, units,
token estimator, boundary behavior, or tie-break. Two implementers can produce
different candidate sets before reaching ordering.

This is also unreconciled with ruling 18, which fixes HALF's contents as own
namespace detail, required namespace dirs, the worked demonstration, and the
task message, while the subject says “no namespace tours” and admits a new
declared-render frontier. A generator admission cap is content selection; it
must not be disguised as a render profile, because ruling 3 says profiles fit
content and never select it
([ruling 3](self-generating-context-prd-2026-08-11.md#L36),
[ruling 16](self-generating-context-prd-2026-08-11.md#L369), and
[ruling 18](self-generating-context-prd-2026-08-11.md#L466)).

**Exact fix:** specify the initial ordered seed vector as data: situation root
`(help)`; the open run's trigger reached through
`:seon.cluster.run/trigger`; and exactly one source `:seon.test` row satisfying
`:seon.test/usage true` and calling `my.run/walkthrough`, whose declared form
projection supplies the demonstration entries. Refuse loudly on zero or
multiple canonical usage rows. State that the source usage row keeps the usage
fact while the rendered per-agent `deftest` form intentionally does not copy
it. Then name one database config/schema fact and exact V1 cap, including
units, estimator, whole-entry boundary rule, distance order, and complete
lexicographic tie-break. State whether D1/D2 supersede ruling 18's fixed
content roster or preserve it.

### BA-3 — The ordering rule contradicts ruling 14

The subject requires “fact-owned ties ... never string/hash.” Ruling 14
requires “stable alphabetical tie-breaks”
([ruling 14](self-generating-context-prd-2026-08-11.md#L360)).
Current code instead preserves the pull's candidate-vector order through the
ready filter; it has no explicit ready-set tie key
([`src/seon/render/walk.clj:708-769`](../../../../src/seon/render/walk.clj#L708)).
`entry-source` uses `pr-str` only to serialize the selected form, not to order
candidates
([`src/seon/bootstrap.clj:115-120`](../../../../src/seon/bootstrap.clj#L115)).

**Exact fix:** explicitly supersede ruling 14's alphabetical clause or retain
it. Then give one total lexicographic key for every candidate family, with
direction and missing-value behavior—for example introduction ordinal,
transaction ordinal, then stable database identity. Replace implicit
candidate-vector ordering at the one selection owner; retain alphabetical
symbol spelling only if ruling 14 keeps it.

### BA-4 — Worked example A does not specify an admissible D2 declaration sequence

The demonstrated sequence cannot run:

- scratch `status` takes `[db]` and is called with no argument before it has a
  contract, so call preparation cannot supply the database;
- contracted `status` names `:my.agents.task-agent-9/status` before that schema
  exists;
- the registration names an undefined, uncontracted `status-ai`, contrary to
  ruling 35's render-contract coherence;
- in executable Clojure the renderer must be a symbol value, such as
  `'my.agents.task-agent-9/status-ai`; the unquoted name evaluates to a Var or
  fails resolution, and `register!` rejects function objects that do not
  round-trip as EDN;
- the scratch value has `functions` and `tests`, while the final function and
  schema drop `tests`; and
- the example never calls the declared status after registration, never shows
  its declared AI render, and never proves that same block feeds root's tile.

`register!` itself is correctly a two-argument `[key definition]` function
returning the key
([`src/seon/schema.clj:1254-1321`](../../../../src/seon/schema.clj#L1254)).
The defect is the exchange order and render coherence, not the raw Malli map
shape. Admission checks the named renderer's actual contract
([`src/seon/schema.clj:1472-1555`](../../../../src/seon/schema.clj#L1472)).

**Exact fix:** replace the exchange with a complete, probed sequence. One
viable order is: define and call a zero-arity scratch `status`; define a
contracted `status-ai` whose first input is the inline status map and whose
output is `:seon.render/ai`; register the named status schema with
`:seon.render/ai 'my.agents.task-agent-9/status-ai`; define contracted `status`
returning that named schema; call it; define the qualified test; and show the
status result's declared AI bytes and root tile. Keep `functions` and `tests`
in every version or remove `tests` from the task and scratch result. Paste all
forms and outputs; do not leave the staging order to the implementer.

### BA-5 — The explained-set acceptance has no executable introduction trace

Example A remains at `user=>`, never executes `in-ns` or `require`, and then
uses `message/inbox`, `message/read`, `db/q`, `run/complete`, bare `register!`,
`deftest`, `is`, and `seon.test/run`. Explaining `my.message/inbox` does not
introduce the different symbol `message/inbox`. Rulings 14, 22, and 29 require
namespace/alias introduction and recursive explanation before use
([ruling 14](self-generating-context-prd-2026-08-11.md#L360),
[ruling 22](self-generating-context-prd-2026-08-11.md#L453), and
[ruling 29](self-generating-context-prd-2026-08-11.md#L112)).

**Exact fix:** add the exact generated `(in-ns
'my.agents.task-agent-9)` and `require` forms, then use
`my.agents.task-agent-9=>`. Use fully qualified calls or show the alias facts
created by the require. Before every non-core symbol's first use, include the
exact `dir`/`doc` entry that introduces it. Add an acceptance table with one
row per entry: parsed referenced symbols, introduced symbols before, and
introduced symbols after. The regression should consume that table/vector,
not infer success from plausible prose.

### BA-6 — The document declares two incompatible byte authorities

Lines 105-111 say Example A is normative byte acceptance and any different
settlement fails. Lines 190-193 say exact bodies may differ and only an
abstract scratch-to-complete shape is normative. Both examples contain
ellipses, so neither can be compared byte-for-byte. Comments are also displayed
as separate prompt entries although `entry-source` stores comment plus form as
one source string
([`src/seon/bootstrap.clj:115-120`](../../../../src/seon/bootstrap.clj#L115)).

**Exact fix:** make the named recurring usage-test row and its rendered ordered
entry vector the sole byte authority. Name the exact `:seon.test/sym`, include
every form, comment, admitted result, namespace prompt, and error value with no
ellipses, and require fixture and PRD bytes to change together. Alternatively
label both examples schematic and delete every byte-authority/prefix-exact
claim. Pin explicitly whether the demonstration shape includes the renderer
defn, schema registration, one contract error, post-registration rendered
output, test declaration/execution, and complete.

### BA-7 — T2 is ruled two different ways, and wake scope is unresolved

The subject and immediate parent say a passive fact change creates no settled
history entry until the next wake. Earlier ruling 6 says passive refresh means
“blocks update, history appends, page morphs,” and ruling 36 says the plan
change “appends passively to context”
([ruling 6](self-generating-context-prd-2026-08-11.md#L46),
[ruling 36](self-generating-context-prd-2026-08-11.md#L159)).
An implementer can validly build either passive durable append or pending-only
display. The subject also says only a message opens work, while ruling 6 keeps
addressed errors as wakes.

**Exact fix:** add an explicit supersession sentence: T2 replaces the “history
appends” phrase in ruling 6 and “appends passively to context” in ruling 36.
A passive change may update only a disposable pending page block; it creates no
run, form, receipt, or settled-history entry. At the next addressed wake,
`:generate` executes and settles the delta. Then state whether addressed errors
remain work wakes or whether the new message-only ruling revokes that earlier
case.

### BA-8 — The delta basis and provenance algorithm are underspecified

“Shown `{collection-key basis}`” never says which basis is retained. The
receipt settlement transaction, render-call observation basis, generated-form
basis, and database value on which the listing actually executed can differ.
Using settlement transaction time can skip changes committed between the read
and its receipt. The existing history vocabulary exposes
`:seon.render.history/basis-transaction`, but the document does not name it or
prove it denotes the listing's read database
([`src/seon/render/walk.clj:775-824`](../../../../src/seon/render/walk.clj#L775)).

The subject also says provenance comes from tx metadata but gives no query
joining changed datoms' transaction ids to `:seon.db/user` and
`:seon.db/process`. Retained read evidence contains dependency revisions, not
an automatically obvious delta cursor
([`resources/seon/schemas/seon.db.edn:23-110`](../../../../resources/seon/schemas/seon.db.edn#L23)).

**Exact fix:** define `shown` as the basis transaction of the immutable database
value on which the listing form actually executed, and name the exact receipt
member or new underivable domain fact that preserves it. Do not derive it from
receipt settlement time. Provide the exact Datalog query that selects datoms
strictly after that basis and joins each datom's transaction entity to
`:seon.db/user`/`:seon.db/process`; specify additions, retractions, and
add-then-retract behavior. Then show current-database per-id pulls separately.

### BA-9 — Zero-turn settlement has no declared value or transition

The document says the loop force-settles `:wait` with a typed
budget-exhausted condition, but names no schema key, fields, receipt member,
transaction function, or delivery projection. Current code derives
`turns-remaining` from `:seon.config.run/max-episode-runs` and gates which
triggers open; no budget-exhausted value exists
([`src/seon/bootstrap.clj:43-66`](../../../../src/seon/bootstrap.clj#L43),
[`src/seon/cluster/work.clj:444-461`](../../../../src/seon/cluster/work.clj#L444)).
Current `:my.run/wait` requires a note and is documented as no reply, which is
not automatically compatible with “requester sees”
([`src/my/run.clj:117-131`](../../../../src/my/run.clj#L117)).

**Exact fix:** before Phase 4, name the condition schema and exact ordinary
value, the terminal receipt attribute containing it, the transaction function
that constructs the forced disposition, and the delivery result visible to an
inside or outside requester. State whether this is a new arm of
`:my.run/value` or a run error; do not overload the existing wait note with new
semantics. Add exact zero-before-open and zero-after-a-form transition tables.

### BA-10 — Corrections have a mechanism but no trigger contract

The current tree has `refresh-call`/`refresh-tx`, which creates an ordinary
system run from a prior system-authored form after proving a terminal receipt,
read evidence, and no successor
([`src/seon/cluster/run.clj:900-986`](../../../../src/seon/cluster/run.clj#L900)).
But the immediate parent rejects refresh runs for T2, while the subject's D4
regression says “fix renderer, refresh” without naming who detects the change,
who calls the transaction, or whether program-generation change is sufficient.

**Exact fix:** distinguish correction from passive T2 delta. Name the exact
event and owner that invokes `refresh-tx`, the program-generation/static/read
evidence check that makes the prior result stale, and how the successor enters
the next wake's history. If refresh runs remain rejected, delete this phase and
express correction as a generated re-observation inside the next addressed
run. Do not leave both mechanisms available.

## Tree mismatches

### TM-1 — The claimed landed situation bytes use four wrong keys

With the open trigger plus one additional unanswered fixture message, current
`situation` returns (the trigger itself is excluded from the unread count):

```clojure
{:seon.cluster.agent/id "task-agent-9"
 :seon.cluster.agent/namespace-ref
 [:seon.ns/name my.agents.task-agent-9]
 :seon.cluster.agent/unread-message-count 1
 :seon.cluster.run/turns-remaining 6
 :seon.cluster.agent/protocol-namespaces [my.message my.run seon.db]
 :seon.cluster.agent/open-run-ref [:seon.cluster.run/id "run-..."]
 :seon.cluster.run/trigger [:seon.cluster.message/id "task-..."]}
```

The subject instead uses `namespace`, `message/unread`, `run/open`, and
`repl/protocol`. The current implementation and schema agree on the former
names
([`src/seon/bootstrap.clj:60-80`](../../../../src/seon/bootstrap.clj#L60),
[`resources/seon/schemas/seon.cluster.agent.edn:92-112`](../../../../resources/seon/schemas/seon.cluster.agent.edn#L92)).

**Exact fix:** replace the alleged landed bytes with the current map above,
using exact fixture ids rather than ellipses.

### TM-2 — Example A calls functions and aliases that do not exist

`my.message` currently exposes only `send` and `decline`; there is no `inbox`
or `read` ([`src/my/message.clj:48-96`](../../../../src/my/message.clj#L48)).
The transcript renderer currently synthesizes those nonexistent forms, which
is residue, not a callable contract
([`src/seon/render/transcript.clj:847-857`](../../../../src/seon/render/transcript.clj#L847)).
Agent creation records requires but no `message`, `run`, or `db` aliases
([`src/seon/cluster/agent.clj:93-112`](../../../../src/seon/cluster/agent.clj#L93)).
Bare `register!` is not injected; it is `seon.schema/register!`. Bare
`deftest`/`is` are not demonstrated refers. `seon.test/run` does not exist; the
only current runner is system-side `seon.test.runner/run!`.

Current acquired `doc` also returns keys `:seon.fn/sym`, `:seon.fn/doc`,
`:seon.fn/arglists`, and `:seon.fn/contract-lines`, not
`:seon.program/name`
([`src/seon/sci/eval.clj:1069-1125`](../../../../src/seon/sci/eval.clj#L1069)).
This later value-returning `doc` behavior contradicts the transcript parent's
older DOC-1 print-plus-nil contract, and the subject has no phase for resolving
that parent mismatch.

**Exact fix:** either add, contract, bind, and test `my.message/inbox` and
`my.message/read` in an explicit phase before the example, or replace them with
valid current `seon.db` forms. Use qualified `seon.schema/register!`,
`clojure.test/deftest`, and `clojure.test/is`. Remove `seon.test/run` and stop
claiming the replica test ran, or specify a new callable contract, owner, result
schema, and test-result fact. Mark ruling 8 as superseding transcript DOC-1 and
use the current `doc` result keys.

### TM-3 — The settled `complete` value is already accreted and is spelled wrong

`my.run/complete` returns disposition/result, and terminal settlement adds
delivery visibility. `:my.run/delivered-to` already accepts an agent-id string
or `:outside`, not a lookup ref
([`src/my/run.clj:133-149`](../../../../src/my/run.clj#L133),
[`resources/seon/schemas/my.run.edn:1-8`](../../../../resources/seon/schemas/my.run.edn#L1),
[`src/seon/cluster/loop.clj:398-425`](../../../../src/seon/cluster/loop.clj#L398)).

**Exact fix:** replace the output with:

```clojure
{:my.run/disposition :completed
 :my.run/result "status is live: contracted, declared as my namespace's render, tested green."
 :my.run/delivered-to "root"}
```

Delete the “extend if invisible” accretion note. If the test is only declared,
not executed, remove “tested green.”

### TM-4 — Example B's `since` forms are not expressible current calls

`seon.db/since` is positional and returns a database value. `seon.db/pull`
accepts `(database selector eid)` or Datahike's argument map with
`:selector`/`:eid`; `{:seon.db/since 1041}` is neither
([`src/seon/db.clj:859-899`](../../../../src/seon/db.clj#L859),
[`src/seon/db.clj:1107-1119`](../../../../src/seon/db.clj#L1107)). A lookup ref
may also fail against a since database because its identity datom can predate
the cutoff; the since view contains only later datoms
([Datahike specification](../../../../reference-code/datahike/src/datahike/api/specification.cljc#L886)).

**Exact fix:** bind `(seon.db/since 1041)`, query changed numeric entity ids and
their transaction ids from that database value, and then pull each numeric id
from the current database. Do not invent an inbox options arity under D6.
Specify whether the delta is additions-only or includes retractions.

### TM-5 — The plan delta names an undeclared relationship

There is no `:seon.cluster.agent/plan` relationship or plan shape in the
current registry. The issue is already recorded and explicitly awaits a design
ruling
([agent-plan issue](../../../seon/issues/agent-plan-has-no-declared-database-relationship.md)).
The nearest landed relationship, `:seon.cluster.agent/instructions`, has
different semantics.

**Exact fix:** block Example B and T1 implementation on one explicit ruling:
reuse a named existing shape or declare a new agent-plan relationship and
referenced schema. Only after that choice, give the exact transaction and
delta query. Do not use `instructions` while calling it a plan.

### TM-6 — “A run opens only on a message” is false for the current work owner

Current `next-agent-work` can open a run for an unanswered background result
without a trigger
([`src/seon/cluster/work.clj:582-650`](../../../../src/seon/cluster/work.clj#L582)).

**Exact fix:** narrow the sentence to “an evolving-session gap-closure run
opens on an addressed wake” and explicitly preserve background-result runs, or
rule that background results must first become addressed message facts and
change that owner. Do not state the narrower T1 law as the general run law.

### TM-7 — Prefix drift is not currently a declared flat refusal

The subject calls `:seon.bootstrap/prefix-drift` a loud refusal that “stays.”
Current `next-entry` throws `ExceptionInfo`, and the direct call from
`generate-turn` is not wrapped by `phase`
([`src/seon/bootstrap.clj:256-284`](../../../../src/seon/bootstrap.clj#L256),
[`src/seon/cluster/loop.clj:1600-1612`](../../../../src/seon/cluster/loop.clj#L1600)).

**Exact fix:** either describe the current thrown invariant honestly or add an
explicit error schema and catch/settlement boundary to the phase inventory.
The latter is required if the document wants a flat agent-visible refusal.

### TM-8 — Phase ownership and parallelism claims are false

Phase 1 changes a caller if per-form re-derivation is deleted; Phase 2 says
“usage test + situation,” where situation is in `bootstrap.clj`; Phase 3
consumes `loop.clj`/`work.clj`; and Phase 4 explicitly owns `loop.clj`.
Therefore “no two phases share a file” and “parallelize after phase 1” are not
true.

**Exact fix:** list exact owned paths per phase and order overlapping owners.
At minimum, fold/closure must precede T1 wiring; demonstration situation edits
must serialize with `bootstrap.clj`; and T1 loop work must precede budget
settlement in `loop.clj`. Keep only genuinely disjoint proof/driving work in
parallel.

### TM-9 — Generate-to-call was present only as in-flight shared-tree work

At the audit observation, a protected shared diff proposed storing `:call`,
changing it to `:generate` on the first generated append, changing it back to
`:call` at the fixed point, dispatching work from that fact, and offsetting
provider-reply ordinals by the generated form count. Those observations were
not landed evidence at audit time.

The seam subsequently landed as commit `7d036203e`. The committed owners now
offset provider-reply ordinals by existing form count
([`src/seon/cluster/run.clj:640-698`](../../../../src/seon/cluster/run.clj#L640)),
guard the first append and terminal transition
([`src/seon/cluster/run.clj:747-864`](../../../../src/seon/cluster/run.clj#L747)),
dispatch `:generate` and `:call` from stored facts
([`src/seon/cluster/work.clj:570-650`](../../../../src/seon/cluster/work.clj#L570)),
and invoke the transition at the generator fixed point
([`src/seon/cluster/loop.clj:1584-1650`](../../../../src/seon/cluster/loop.clj#L1584)).

**Exact fix:** replace the historical line claims with those committed
function/contract names and commit id, cite the landed focused regressions,
and make Phase 3 depend on that commit. Execution of the source lane's cluster
gate is not part of this documentation revision's verification boundary.

## Minor findings

### MI-1 — Comment/prompt bytes do not match the stored entry model

One `:seon.repl/entry` has one optional comment and one form. `entry-source`
joins them into one reader source, and transcript bytes prepend one prompt to
that source, not a second `user=>` for the comment
([`src/seon/render/transcript.clj:866-870`](../../../../src/seon/render/transcript.clj#L866)).

**Exact fix:** show one namespace prompt followed by the comment newline and
form indentation, or declare a new renderer contract. Do not depict comments
as independent REPL forms.

### MI-2 — Example B cannot be a prefix-byte fixture while containing abbreviations

The output maps, ids, and final plan all contain `...` or `…`, yet acceptance
claims prompt N plus “exactly” these bytes.

**Exact fix:** replace every abbreviation with fixture-stable admitted bytes,
or downgrade the example to an ordering/shape example and point prefix-byte
acceptance at a recurring fixture.

### MI-3 — Determinism needs the program generation among its inputs

“Same `(pull-basis, history)` implies byte-identical entries” omits the acquired
program generation whose render and form functions produce those bytes. A
renderer correction at the same database basis is the document's own D4 case.

**Exact fix:** define determinism over the immutable database value/commit,
retained history identity, acquired program generation, and relevant render
caps/profile—or state that program publication necessarily changes the pull
identity used by the generator.

## Calibration — executable or correctly grounded as written

- The central model—one generator over the current pull and retained history,
  T0 as maximal-gap T1, no authored bootstrap plan, and real
  system-authored forms/receipts—matches rulings 24, 28, and 32.
- `:seon.repl/entry` is landed as required form plus optional comment/key/
  subject
  ([`resources/seon/schemas/seon.repl.edn:10-17`](../../../../resources/seon/schemas/seon.repl.edn#L10)).
- Generated forms have system authorship, sequential ordinal fences, and real
  terminal receipts before the next append. Commit `7d036203e` strengthens
  that boundary with the guarded generate-to-call transition rather than
  inventing another run path.
- The situation concept is correct: id, namespace ref, unread count, open-run
  ref, protocol namespaces, trigger, and turns remaining are all landed. Only
  the example's key spellings are wrong.
- Prefix comparison against stored generated source is real. The mismatch is
  its thrown-vs-flat error description.
- Tx-meta provenance and a Datahike `since` database are the correct owners for
  change attribution and deltas. The examples need valid compositions and an
  explicit cursor.
- Render declaration coherence is already enforced at schema admission. D2 is
  implementable once the exact declaration order and renderer function are
  supplied.
- `my.run/complete` delivery enrichment is already implemented and schema
  valid. No accretion is required.
- Append-only correction through a refreshed system-authored read has a
  current transaction owner. The remaining decision is when that owner is
  invoked relative to the later T2 ruling.
- T2 pending-page/settle-at-wake is a simple, coherent rule once it explicitly
  supersedes the earlier passive-history wording.

## Disposition in the implementation revision

Applied to
[the revised implementation contract](evolving-session-implementation-2026-08-12.md).
The final ruling round removes every owner-choice marker; the only external
statuses left are a separately owned plan data-model dependency and ruling
40's test-result-facts lane.

| Finding | Disposition |
|---|---|
| BA-1 | Replaced the fictitious one-pass response with an explicit `[target]` invocation-local generation-state interface. It performs one pull, derives one next entry, appends and executes under the existing fence, advances from the real receipt, and uses a fresh fork per entry. Ruling 43 now makes that interface binding and deletes the per-form re-pull. |
| BA-2 | Declared the ordered seed vector: `(help)`, the run trigger, and exactly one usage row calling `my.run/walkthrough`; zero/multiple rows refuse loudly, and only the source row retains `:seon.test/usage`. D1/D2 explicitly supersede ruling 18's fixed roster. The V1 beyond-closure cap is now the exact `:seon.config.bootstrap/beyond-closure-token-budget` dial with `1024` estimated tokens, whole-entry admission, and distance/total-key order. |
| BA-3 | States that ruling 29 subsumes ruling 14: 29 governs dependency readiness; 14 governs only the stable alphabetical order among already-ready entries. Adds one total key with direction and missing-fact refusal. |
| BA-4 | Replaced Example A with HEAD-valid form spellings and the admissible order: scratch call, contracted renderer, quoted-symbol registration, contracted database-supplied status, wrong call, test declaration, explicit renderer output, completion. The replica test is not claimed green. |
| BA-5 | Adds `in-ns`, explicit requires, qualified calls, `dir`/`doc` introductions, and a 23-row referenced-symbol before/after acceptance trace consumed by the regression. |
| BA-6 | Establishes one boundary: `my.run-test/the-lifecycle-walkthrough-is-executable-data` and its recurring rendered fixture own every demonstration byte; the PRD owns only the derivation frame. |
| BA-7 | Explicitly makes T2 supersede ruling 6's “history appends” and ruling 36's “appends passively to context.” Passive change creates no run/form/receipt/history entry. Ruling 44 preserves addressed errors as wakes alongside messages. |
| BA-8 | Defines `shown` as the listing execution database's basis, proposes the exact durable receipt member `:seon.cluster.eval/read-basis-transaction`, supplies the since/provenance query, and separates numeric current-database pulls. Additions, retractions, and add-then-retract behavior are explicit. |
| BA-9 | Ruling 41 settles D3. The distinct `:my.run/budget-wait` arm carries the presence-marked budget-exhausted condition; the pure constructor, one in-transaction force-settlement owner, ordinary receipt/delivery path, and zero-before-open/zero-after-form transitions are now named. |
| BA-10 | Ruling 42 settles D4 as re-observation inside the next message/error run. Read-evidence or program-generation staleness emits the same read at a newer basis; no evolving-session `refresh-tx`, meta-entry, or mutation survives. |
| TM-1 | Replaced all four wrong situation keys with the landed keys and exact deterministic fixture ids. The fixture's unread `1` is explained by one additional unanswered message because the open trigger is excluded. |
| TM-2 | Removed nonexistent `my.message/inbox`, `my.message/read`, aliases, bare `register!`, and `seon.test/run`. Uses current `seon.db` forms plus qualified `seon.schema/register!`, `clojure.test/deftest`, and `clojure.test/is`; ruling 8 explicitly supersedes DOC-1 and the current `doc` keys are named. |
| TM-3 | Uses the landed completion disposition/result/delivery shape and agent-id string. The reply says the replica test is declared, not green; no accretion note remains. |
| TM-4 | Example B binds the positional `seon.db/since` result, queries numeric ids, and pulls those ids from the current database with the landed `pull` arity. It is explicitly additions-only. |
| TM-5 | Example B's plan arm is `[target: external plan-model dependency]`, not owner markup in this PRD. The linked issue must land a real ref and separately identified shape before the example consumes it; instructions-as-plan remains forbidden. |
| TM-6 | Narrows the law to evolving-session gap-closure wakes and explicitly preserves unanswered-background-result runs. |
| TM-7 | Describes the current thrown prefix drift honestly and names the target flat error schema plus loop settlement boundary. |
| TM-8 | Replaces false one-file/parallel claims with exact multi-file ownership and explicit serialization across shared bootstrap, walk, run, work, and loop owners. |
| TM-9 | Records the audit-time status, then cites landed commit `7d036203e`, its committed transition/dispatch/ordinal owners, and the focused regressions consumed by Phase 3. The source lane owns their execution gate. |
| MI-1 | The entry model and example now keep comment plus form in one source under one namespace prompt; comments are never independent forms. |
| MI-2 | Downgrades Example B to an executable ordering/shape frame and assigns all literal ids/output bytes to the recurring fixture; no abbreviation is presented as byte authority. |
| MI-3 | Defines determinism over database commit/basis, retained history identities/bytes, acquired program generation, and admission cap/profile; the missing program-generation input is an exact `[target]` field. |

## Owner-rejection round disposition (ruling 37)

Ruling 37 supersedes the earlier disposition of BA-4, BA-5, and the content
side of BA-6 wherever those rows describe scratch-first status development.
The suite/PRD byte-authority boundary from BA-6 remains unchanged.

| Owner finding | Disposition |
|---|---|
| OR-1 — spec-first, one definition | Deleted the scratch → contract → redefine arc. The revised frame names the already declared `:seon.ns/ns` input and `:seon.render/ai` output before one `render-namespace-ai` definition. Comments narrate that data model; no redefinition remains. |
| OR-2 — use the existing artifact | Deleted the database-taking status function, invented status shape, and `register!` exchange. The artifact is one public function in the agent's own namespace accepting the namespace unit and returning AI bytes. The implementation PRD cites the landed owner propagation and unique contract-fit selection chain. |
| OR-3 — genuine emitted forms | Removed invented `in-ns`, `require`, and hand-picked `doc` forms. The prefix is only `(help)` plus namespace `dir` forms produced by landed owners. The final five forms are the exact `[target]` return of the retargeted `my.run/walkthrough`; Phase 2 names the `clojure.test` required-namespace change that makes its discovery form derivable. All eight forms parse, and every invoked function/macro exists at the cited HEAD signature. |
| OR-4 — retain the real exchanges | Kept one wrong-call exchange and pinned its class assertion to `:seon.instrument/contract-violated`, never temporary `:seon.error/kind`; kept the declared usage test, successful AI value, and one-argument `my.run/complete`. The settled result includes `:my.run/delivered-to "root"`. |
| OR-5 — D2 and phase ownership | Reconciled D2 in both evolving-session documents to ruling 37. Phase 2 now owns the bootstrap required-namespace change and regression as well as `my.run` and its usage test, and therefore serializes after Phase 1 instead of claiming false disjointness. |
| OR-6 — two byte authorities | Preserved BA-6's boundary: the recurring usage test and rendered fixture pin every demonstration byte; the implementation document pins only the derivation frame and enabling source changes. |

## Final ruling and supersession round disposition

This round read rulings 38–44 and commits `174898321`, `5a01449de`,
`aeb70b1cc`, and `e0b64758a` end to end before revising the subject. It then
reconciled the later current-HEAD rulings 45–48 from commits `13a8c8519`,
`fd0fb1892`, and `316fce6ec`.

| Authority | Final disposition |
|---|---|
| Ruling 38 — environment carriage | The implementation and decision record now say `seon.env` carries only derivation inputs. Opening context is derived per agent from the walk and is never stored in or on the environment. |
| Ruling 39 — derived root preview | Root's preview is explicitly the same gap closure over the agent. The phase and graduation gates contain no fixed preview-depth constant or second preview mechanism. |
| Ruling 40 — test-result facts | Added a hard external prerequisite in the dependency ledger and Phase 0: `[in flight -> cite its commit when you land]`. No evolving-session phase owns a stub or may begin before that lane lands. |
| Ruling 41 / D3 | Deleted the owner choice. Phase 6 now names the typed budget-wait value, pure constructor, one in-transaction force-settlement owner, ordinary receipt/delivery path, and both zero transitions. |
| Ruling 42 / D4 | Deleted the owner choice and rejected refresh runs. Phase 7 re-observes the same read on the next message/error wake, appends at the newer basis, preserves old bytes, and adds no meta-entry. |
| Ruling 43 / D5+D6 | Deleted both owner choices. Phases 1 and 4 now require the one-pass invocation-local state, deletion of per-form re-pulls, additions-only `since` deltas, per-new-id current pulls, and zero new callable arities. |
| Ruling 44 / error wakes | Removed ERROR-WAKE markup. Messages and errors addressed to the agent wake it; all other changes remain passive. |
| Rulings 45–46 / rebirth and known facts | Added the real reborn-episode graduation proof. Current facts plus empty history must yield compact valid context; functions, declared renders, and green test-result facts suppress teaching they already demonstrate. |
| Ruling 47 / survivable plans | Strengthened the external plan dependency: fact-backed statuses and a declared current-state render are required; history replay and prose are not substitutes. |
| Ruling 48 / universal rebirth-first check | Made compact rendering from current facts alone an integration acceptance criterion rather than a plan-only concern. |
| Rulings 11/16/18/22 sweep | The binding reconciliation retains only the surviving lessons: gap closure subsumes injection, the demonstration remains load-bearing, and the complete action arc stands. It deletes standing-form, HALF-roster, concise-until-cap, and stale itemization claims. |
| Transcript supersession sweep | The transcript PRD now contributes only surviving history/storage/printing grammar. Ordering bands, pinned bootstrap bytes, HUMAN-2 synthesized reads, and identity-hash prints are explicitly non-authoritative. |
