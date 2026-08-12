---
type: research
status: active
tags: [research, agent, runtime, curation]
---

# Curation editor and trigger design (2026-08-05)

## Verdict

W3 should begin with declarations, not an agent prompt. Declare one durable
editor request, one revision response, and one rejection response before the
trigger or editor exists. The trigger is a payload-free wake at every committed
run boundary; a curation owner then derives all eligible, not-yet-requested
closed runs from facts and commits the request plus the ordinary message that
wakes the editor. Startup runs the same derivation once, so a crash between
close and request loses no work.

The editor works on a scratch branch forked at the original opening commit. The
opening commit predates the failed run, its plan, and its receipts, so checkout
does **not** recover the failed span. The system must project that evidence from
the live branch into the editor request while keeping the opening basis a
separate fact. The editor's successful response names a revision run whose
forms are the existing per-form entities, each carrying ordinal and source.
There is no second ordered vector of bare sources.

Automatic eligibility must fail closed on writes outside the database branch.
Current receipts prove exact effect-door crossings and, indirectly by terminal
transaction, delivered messages. They do not classify a door request as read
or write and do not attribute bare `seon.db/transact!` calls to a run/form.
Consequently the production trigger cannot certify an arbitrary real session
until leaf-declared read/write receipt facts and F7 transaction provenance
land. The first R10 proof should instead be a supervised selection of one real,
human-triggered, closed run containing a simple error followed by a successful
pure correction, with no effect receipt or delivered-message transaction. That
is a genuine first curation, but its write-freedom is a reviewed hypothesis,
not a fact the current database can prove.

## Authority and dependency ledger

I read the following named authorities end to end before reaching this design:

- [docs/prds/sci-execution-runtime/plan/session-curation-prd-2026-08-04.md](../plan/session-curation-prd-2026-08-04.md), including its owner rulings and ugly-output roll-up;
- the complete curation ruling block in [docs/prds/sci-execution-runtime/plan/README.md](../plan/README.md), lines 373-531;
- [docs/prds/sci-execution-runtime/plan/agent-desk-and-checkout-prd-2026-08-05.md](../plan/agent-desk-and-checkout-prd-2026-08-05.md), especially the checkout contract at lines 84-104;
- [docs/prds/sci-execution-runtime/plan/state-of-the-program-2026-08-05.md](../plan/state-of-the-program-2026-08-05.md), including R10 at lines 475-479; and
- the repository and localized `AGENTS.md` authorities.

The selected dependency revisions are:

- Datahike `56f1c62105b7`, with branch-from-commit mechanics in `reference-code/datahike/src/datahike/versioning.cljc:212-255`;
- core.async `dc35f3e0d7bc`, with the existing payload-free wake pattern grounded through `src/seon/cluster/wake.clj:78-240`; and
- SCI `2db3358cba91`, whose generation-aware copy-on-write fork is the admitted candidate-context mechanism.

The current first-party owners inspected are:

- `src/seon/cluster/curate.clj`, completely;
- `src/seon/cluster/run.clj:250-535,537-628,890-1158`;
- `src/seon/cluster/loop.clj:247-355,376-498,1493-1533,1600-1765`;
- `src/seon/cluster/wake.clj`, completely;
- `src/seon/effect.clj:418-550` and `src/seon/db.clj:970-1009,1119-1143`;
- `resources/seon/schemas/seon.cluster.curate.edn`,
  `seon.cluster.run.edn`, `seon.cluster.run.form.edn`,
  `seon.cluster.eval.edn`, `seon.effect.edn`, and
  `seon.cluster.message.edn`; and
- the current W-A desk owners in `resources/seon/schemas/seon.def.edn`,
  `src/seon/sci/eval.clj:1309-1367`, and the terminal settlement seams above.

## Current facts constrain the design

### The settled run boundary is coherent, but has several producers

An eval receipt is identified by run and ordinal and becomes terminal by the
presence of result, error, or interruption facts (`src/seon/cluster/run.clj:537-628,969-1051`).
The terminal request records an error from either evaluation or a routed form
problem (`src/seon/cluster/loop.clj:247-289`), so error presence is the correct
trigger fact; it must not be narrowed to one exception class.

W-A moved the desk into that same settlement. Staged result and desk values,
exact desk upserts/retractions, terminal receipt facts, delivered messages, and
a disposition-bearing run close are committed together
(`src/seon/cluster/loop.clj:1625-1670`; `src/seon/cluster/run.clj:943-1051`).
An after-commit curation query therefore sees either the complete settled
boundary or none of it.

There is not, however, one source-code close call:

- `terminal-tx` composes receipt settlement and close for `complete`/`wait`
  (`src/seon/cluster/loop.clj:291-346`);
- `close-turn` closes an already-consumed run later
  (`src/seon/cluster/loop.clj:1718-1765`);
- terminal-settlement refusal closes inside `receipt-refusal-call`; and
- recovery closes after interrupting dangling receipts/effects
  (`src/seon/cluster/run.clj:1076-1158`).

A hook in `terminal-tx`, `close-call`, or the returned Flow report would miss a
real boundary. The trigger must instead derive from the committed
`:seon.cluster.run/closed-at` fact.

### The opening commit does not contain the failed span

`open-call` reads the current database commit and asserts the new run in the
same transaction (`src/seon/cluster/run.clj:250-290`). Thus the run's
`:seon.cluster.run/opening-commit-id` names the database **before** the run
exists. Its plan forms and receipts arrive still later. A branch forked from
that commit has the right program and database world for replay but cannot
query the failed run.

This separates three histories that the request must never conflate:

1. origin evidence on the live branch: original run, ordered forms, receipts,
   terminal result, desk/program changes, and delivery evidence;
2. the immutable opening commit used for editor checkout and later proof; and
3. the editor's writable scratch branch, which is never the proof branch.

### Ordered forms already have one owner

`run/plan-call` stores a component form entity for every source with identity
`(pr-str [run-id ordinal])`, explicit run ref, ordinal, exact source, and
optional namespace (`src/seon/cluster/run.clj:410-488`;
`resources/seon/schemas/seon.cluster.run.form.edn:1-24`). The run's component
connection is cardinality-many and therefore a set; every consumer derives
order from ordinal.

The current curation schema instead aliases `:seon.cluster.curate/revision` to
`:seon.cluster.reply/sources`, and `prove!`/`adopt!` accept and copy that vector
(`resources/seon/schemas/seon.cluster.curate.edn:1-47`;
`src/seon/cluster/curate.clj:148-251,301-339`). That was sufficient to prove W2,
but it is now superseded by the owner's per-form entity ruling. W3 must change
the engine boundary to consume a revision run's existing form entities; it
must not add `revision-form`, an ordered cardinality-many value, or a parallel
source vector.

## 1. Trigger design

### Recommended — after-commit wake plus idempotent fact derivation

Add one curation proc to the existing cluster plumbing graph. It is a focused
fact-to-message owner, not a scheduler or central run loop.

1. The one Datahike listener offers a payload-free wake when a transaction
   adds `:seon.cluster.run/closed-at`. It does not query, transact, park, or
   carry the transaction report.
2. The curation proc, on `:io`, reads the current database and derives every
   closed, unsuperseded run that has at least one
   `:seon.cluster.eval/error`, passes the destructive-span gate, and has no
   request with the deterministic identity `(pr-str [run-id])`.
3. It transacts the durable editor request and an ordinary message to the
   declared editor agent in one transaction. The message is only the wake;
   the request fact is the work.
4. Graph arm offers one prime wake. Lost/coalesced wakes and a crash after
   close are free because the next pass derives the same missing request.

This follows the existing `wake/route!` law: listener work is only nonblocking
`offer!`; the payload carries no truth; the woken pass derives from facts
(`src/seon/cluster/wake.clj:146-196`). A `(sliding-buffer 1)` is sufficient.
The derivation must reuse the transcript owner's existing `active-runs` rule
and `bootstrap/run-id` derivation (`src/seon/render/transcript.clj:84-114`), not
grow a second exclusion or inspect the `bootstrap:` text prefix.

The editor recipient is also a missing fact. Declare one cluster/root
connection to the editor agent; do not infer an id or namespace. Absence means
curation is not armed and is reported as configuration state, not silently sent
to root by convention.

### Option B — add curation transaction data to every close producer

This can create a request atomically with close and can see receipt and W-A desk
facts earlier in the same transaction. It is rejected because four distinct
close producers must remember the composition, and `seon.cluster.curate`
already depends on `seon.cluster.run`. The resulting cycle or copied gate would
be a second mechanism.

### Option C — act on `loop/turn`'s `:closed` report

This is cheap and immediately knows which run returned. It is rejected as the
authority: Flow reports are observation, recovery can close without that
report, and a process can die after close but before request. Adding a catch-up
scan makes Option A the real mechanism anyway.

## 2. Editor request shape

The stored request should be minimal, identified, and derivable. The editor's
rendered input is a projection over it, not stored prompt prose.

```clojure
;; Proposed declarations. Maps remain open.
:seon.cluster.curate.request/id       ; unique identity, (pr-str [run-id]) in v1
:seon.cluster.curate.request/run      ; ref to the one original run
:seon.cluster.curate.request/editor   ; ref to the selected editor agent
:seon.cluster.curate.request/branch   ; origin live branch
:seon.cluster.curate.request/scratch-branch ; editor's writable branch

:seon.cluster.curate/editor-request
[:map
 [:seon.cluster.curate.request/id :seon.cluster.curate.request/id]
 [:seon.cluster.curate.request/run :seon.cluster.curate.request/run]
 [:seon.cluster.curate.request/editor :seon.cluster.curate.request/editor]
 [:seon.cluster.curate.request/branch :seon.store/branch]
 [:seon.cluster.curate.request/scratch-branch :seon.store/branch]]

;; The value rendered to the editor. The sets are deliberately unordered;
;; every form/receipt carries its authoritative ordinal.
:seon.cluster.curate/context-forms    ; set of pulled run.form entities
:seon.cluster.curate/context-receipts ; set of pulled receipt evidence

:seon.cluster.curate/editor-context
[:map
 [:seon.cluster.curate.request/id :seon.cluster.curate.request/id]
 [:seon.cluster.run/opening-commit-id :seon.cluster.run/opening-commit-id]
 [:seon.cluster.run/starting-ns :seon.cluster.run/starting-ns]
 [:seon.cluster.agent/id :seon.cluster.agent/id]
 [:seon.cluster.curate/context-forms
  [:set :seon.cluster.run.form/form]]
 [:seon.cluster.curate/context-receipts
  [:set :seon.cluster.curate/proof-receipt]]]
```

Do not duplicate opening commit, original agent, starting namespace, or status
onto the live request: they derive from the referenced run. Request state
derives from response/proof/adoption connections. Required maps rigorously
validate their named keys and ignore future extras.

Before the editor turn, the curation owner:

1. pulls the original run, forms ordered by ordinal, receipts, completed
   result, declarations, W-A desk changes, and destructive-gate evidence from
   the live database;
2. forks `scratch-branch` from the original opening commit using the one
   registry/store lifecycle owner;
3. installs the request evidence as ordinary scratch-branch facts/blob-backed
   data and addresses an ordinary message to the editor; and
4. uses the desk PRD's checkout resolution: run override, then editor-agent
   checkout, then live head. The editor run explicitly selects scratch branch
   plus opening commit, and its SCI base ctx and ambient database value derive
   from that same immutable value.

The rendered editor block must show, in order:

- origin run id, agent, live branch, opening commit, starting namespace, and
  trigger;
- every original form entity ordered by ordinal, with exact source, parse/eval
  namespace, actual result/output/error/triage, and which facts made it fixed;
- the original terminal outcome and completed value used by proof;
- declarations and desk consequences committed at settlement; and
- the gate evidence and verdict. An automatically unknown span never reaches
  the editor; if the editor recognizes destructiveness beyond an eligible
  receipt verdict, it returns the declared rejection instead of a revision.

Because the opening commit lacks these facts, the scratch copy is transport,
not another history authority. The live request/run remains the provenance
owner; the editor branch is disposable. Bulky pulled evidence should use the
existing blob identity/digest/size pattern rather than a large inline entity.

### Request options

- **Recommended: one-run request ref plus derived evidence projection.** It
  avoids unresolved multi-run ordering and keeps one opening basis.
- **Option B: ordered multi-run span entries.** Defer. Current
  `candidate-span` checks only that all runs exist, are closed, and share an
  agent; it does not prove episode continuity, chronological adjacency, shared
  ancestry, or compatible opening bases (`src/seon/cluster/curate.clj:33-68`).
- **Option C: message prose containing the failed session.** Reject. It loses
  queryability, makes exact source recovery depend on rendering, and cannot
  carry a stable response identity.

## 3. Revision-response shape

The editor either names one revision run or rejects. Presence of the revision
connection versus rejection reason distinguishes the shapes; no `:type` or
`:status` key is needed.

```clojure
:seon.cluster.curate.response/request      ; ref to editor request
:seon.cluster.curate.response/revision-run ; ref to scratch-branch run
:seon.cluster.curate.response/rejected-reason ; non-empty string

:seon.cluster.curate/revision-response
[:map
 [:seon.cluster.curate.response/request
  :seon.cluster.curate.response/request]
 [:seon.cluster.curate.response/revision-run
  :seon.cluster.curate.response/revision-run]]

:seon.cluster.curate/rejection-response
[:map
 [:seon.cluster.curate.response/request
  :seon.cluster.curate.response/request]
 [:seon.cluster.curate.response/rejected-reason
  :seon.cluster.curate.response/rejected-reason]]
```

The revision run is planned through the existing `run/plan-tx`. Its
`:seon.cluster.run/forms` are component `:seon.cluster.run.form/form`
entities, and each entity carries:

```clojure
{:seon.cluster.run.form/id      (pr-str [revision-run-id ordinal])
 :seon.cluster.run.form/run     [:seon.cluster.run/id revision-run-id]
 :seon.cluster.run.form/ordinal ordinal
 :seon.cluster.run.form/source  exact-source
 ;; optional :seon.cluster.run.form/ns
}
```

The proof owner queries those entities by revision run and sorts by ordinal.
It then creates its own fresh proof run at the original opening basis. Adoption
does the same query and uses the proved run facts. The editor's messy run and
desk are never replay input.

### Response options

- **Recommended: response names a revision run containing canonical form
  entities.** One durable ordered-forms mechanism; proof/adopt can verify
  contiguous unique ordinals before execution.
- **Option B: return a vector of form-entity maps, then persist it.** Better
  than bare strings, but it duplicates a durable plan as a transient ordered
  container and creates an avoidable validate-then-write seam.
- **Option C: retain `:seon.cluster.reply/sources`.** Reject. It has implicit
  vector position, no ordinal, and is exactly the second ordered representation
  the new ruling removes.

This declaration requires a W2 boundary adjustment before W3 implementation:
replace `:seon.cluster.curate/revision`'s source-vector alias and change
`prove!`/`adopt!` to read the revision run's form entities. Do not preserve a
compatibility arity; reset/rebuild is the repository policy.

## 4. Destructive-span gate

The ruled boundary is **writes outside the database branch**, not effect-door
crossing:

- database writes are replayable on the scratch/proof branch;
- filesystem and web reads are replayable and proof equivalence catches
  material drift;
- delivered messages, real file edits, web writes, and other external writes
  are destructive and make the span ineligible; and
- the editor must additionally reject with a reason when semantic context
  reveals destructiveness receipts cannot see.

### What facts prove now

- An effect receipt identifies exact run, form ordinal, effect ordinal, owner,
  request, and timestamps. `effect/request*` derives identity from
  `[run-id form-ordinal effect-ordinal]` and commits it before dispatch
  (`src/seon/effect.clj:469-499`).
- A delivered message can be joined to the issuing terminal receipt by the
  transaction in database history because both are committed at
  `src/seon/cluster/loop.clj:1625-1670`. This is honest current evidence, but
  F6's direct message-to-form ref remains absent.
- Current effect rows have no declared read/write fact
  (`resources/seon/schemas/seon.effect.edn:18-54`). An owner/handler symbol is
  not a write classification.
- Agent `seon.db/transact!` reaches `d/transact` without run/form transaction
  provenance (`src/seon/db.clj:970-1009,1119-1143`). Concurrent transaction
  history cannot reconstruct causation. Absence of an attributed write is not
  proof that none occurred.

### Required fact lift

Apply the workload-classification pattern a second time:

1. each capability leaf declares read versus write in defn metadata;
2. indexing lifts that declaration to a `:seon.fn/*` fact;
3. effect request opening copies the resolved classification onto its receipt,
   preserving what was known at execution time even if the program changes;
4. call chains derive advisory mutation reachability over `:seon.fn/calls`;
   unresolved reachability fails closed; and
5. F7 attaches the already-bound run and form ordinal as transaction metadata
   on agent-issued `seon.db/transact!`.

The exact read/write attribute name must be registry-query-first at
implementation time. Do not infer it from function names, capability family,
request EDN, or a hand-maintained roster.

### Gate options

- **Recommended: tri-state fact gate — eligible, destructive, unknown.** Reads
  pass; declared external writes and delivered messages reject; missing
  classification or missing F7 provenance is unknown and fails closed. Store
  evidence, not a mutable status; the verdict derives.
- **Option B: pin every door-crossing form and replay it unchanged.** This is
  safe only for duplicate branch-local work. Replaying a message or external
  write still duplicates the effect, so pinning does not solve destructiveness.
- **Option C: reject every effect receipt.** Safe but contradicts the ruled
  reads-not-writes boundary and discards valuable sessions with ordinary file
  or web reads.

Until both receipt classification and F7 exist, production automatic curation
of arbitrary real runs remains honestly gated. W3 may declare and render the
unknown verdict, but must not relabel it eligible.

## 5. First genuine curation candidate (R10)

R10 states that W2 curated only a seeded messy run; no real failing session has
ever been curated. The first genuine candidate should therefore minimize new
semantics while remaining model-authored.

### Recommended candidate class

Select one run from the live default cluster, after the ops boundary is fixed,
with all of these queryable coarse properties:

- closed, no custody, opening commit and starting namespace present;
- not superseded and not the run selected by the existing transcript
  `active-runs`/`bootstrap/run-id` rule;
- triggered by an inbound/human message (trigger message has no
  `:seon.cluster.message/from`);
- at least one non-interruption eval-error receipt and a later successful
  receipt;
- `eval.drive/terminal-state` and `completed-result` confirm completion;
- no effect receipt, no message created in a receipt's terminal transaction,
  no background effect, and no declaration committed by that run; and
- preferably the smallest plan with one ordinary correctable REPL mistake
  (unresolved symbol, wrong arity, or malformed value) followed by its pure
  correction.

Pull sources from the existing form entities and sort by ordinal. Rank
deterministically by closed instant, run id, then first error ordinal; inspect
the smallest candidates rather than choosing by prose search. The final
supervised source review uses the parsed forms, not a regex.

A coarse candidate query is:

```clojure
[:find ?run-id ?opened-at ?closed-at ?opening-commit
       ?agent-id ?trigger-id ?error-ordinal ?later-ordinal
 :where
 [?run :seon.cluster.run/id ?run-id]
 [?run :seon.cluster.run/opened-at ?opened-at]
 [?run :seon.cluster.run/closed-at ?closed-at]
 [?run :seon.cluster.run/opening-commit-id ?opening-commit]
 [?run :seon.cluster.run/starting-ns _]
 [?run :seon.cluster.run/agent ?agent]
 [?agent :seon.cluster.agent/id ?agent-id]
 (not [?run :seon.cluster.run/process _])
 (not-join [?run] [?replacement :seon.cluster.run/supersedes ?run])
 [?run :seon.cluster.run/trigger ?trigger]
 [?trigger :seon.cluster.message/id ?trigger-id]
 (not [?trigger :seon.cluster.message/from _])
 [?failed :seon.cluster.eval/run ?run]
 [?failed :seon.cluster.eval/ordinal ?error-ordinal]
 [?failed :seon.cluster.eval/error _]
 (not [?failed :seon.cluster.eval/interrupted-at _])
 [?later :seon.cluster.eval/run ?run]
 [?later :seon.cluster.eval/ordinal ?later-ordinal]
 [?later :seon.cluster.eval/result-edn _]
 [(> ?later-ordinal ?error-ordinal)]
 (not-join [?run] [?effect :seon.effect/run ?run])]
```

Then exclude the cluster's explicit bootstrap-plan run, collapse duplicate
error/later pairs by run, apply the terminal-result owner, history-join
delivered messages and declarations, pull forms, and review.

This produces a candidate class, not a safety proof. Before F7, even a run with
no effect receipt can contain a bare transaction. The first genuine curation
must record that limitation and be manually approved; unattended selection
waits for the facts.

### Candidate options

- **Recommended: one human-triggered error-to-correction run.** Genuine model
  behavior, one opening basis, small equivalence surface.
- **Option B: any closed error run.** More representative but mixes external
  effects and declarations before the gate can prove them.
- **Option C: multi-run recovery span.** Defer until span adjacency, ordering,
  and basis compatibility are declared. It needlessly combines R10 with a new
  span model.

## Acceptance boundary for W3 unpark

Before implementation, the owner should be able to query the declarations for
editor request, revision response, rejection response, editor-agent connection,
and receipt read/write evidence. W3 then exits only when:

1. every close path produces the same idempotent derived request after commit,
   including a close observed only by startup catch-up;
2. the editor runs on a scratch checkout at the original opening commit while
   receiving the copied failed-span evidence;
3. its response names one revision run made of canonical ordinal+source form
   entities;
4. a fact-unknown span creates no editor request, while an editor-recognized
   destructive task returns the rejection response with evidence;
5. the existing fresh proof mechanically executes that run's forms and only a
   clean equivalent proof is adopted; and
6. one supervised real default-cluster failure is curated, with the original
   still queryable and the safety limitation stated honestly.

## Ugly output and observations

- The known source-unrelated live boundary rendered
  `:seon.operator/root-creator-mismatch` with creator PID `45466` and
  ephemeral-owner PID `37924`; MCP separately complained that it could not
  locate `seon/operator/state` on the classpath. These were two disconnected,
  implementation-facing diagnostics for the same known ops repair, with no
  concise “known boundary / no action required” face. Per owner direction they
  did not block this source-only work.
- `eval.drive/run-receipts` represents absent result/error fields as empty
  strings and absent error kind as `:seon.eval.drive/absent`; `prove!` therefore
  tests a blank string and a sentinel together
  (`src/seon/cluster/curate.clj:207-214`). That is an ugly internal proof face:
  absence should remain absence rather than becoming two unrelated sentinels.
- The prior curation research's larger roll-up remains active at
  `docs/prds/sci-execution-runtime/plan/session-curation-prd-2026-08-04.md:314-328`;
  this source-only pass did not reproduce those live faces.
