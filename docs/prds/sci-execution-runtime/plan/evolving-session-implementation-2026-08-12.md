---
type: prd
status: draft
tags: [prd, render, agent, context, runtime]
---

# Evolving-session implementation: the line-by-line contract

The implementation-grade companion to
[evolving-session-prd-2026-08-12.md](evolving-session-prd-2026-08-12.md)
(the decision record: D1/D2/T2 ruled; D3–D6 carried below as MARKED
ASSUMPTIONS with their recommended answers so one owner line flips each).
Written to the [repl-transcript PRD](repl-transcript-context-prd-2026-08-10.md)
standard: the worked examples are the byte acceptance contract; an
implementer executes this document, they do not interpret it.

## How this works (read first)

An agent's context is its own REPL history. The history is produced by ONE
derivation — already landed as `seon.bootstrap/next-entry` and its
callers — which this document extends from birth (T0, landed) to every
wake (T1) and passive change (T2):

1. A run opens ONLY on an addressed fact (a message; ruling 36). The run's
   work situation is a DECLARED enum fact: `:generate` while the system's
   pre-prompt forms derive and execute; `:call` once the generated prefix
   reaches its fixed point and the model may be consulted. (The
   generate→call transition is pre-factored by the
   generate-call-transition lane; this PRD consumes it.)
2. In `:generate`, the derivation emits (comment, form) entries —
   `:seon.repl/entry`, the landed shape — in explained-set order
   (ruling 29), gap-closure against the retained history (ruling 32),
   membership-gated by the one pull. Every emitted form EXECUTES through
   the ordinary run fold at ordinals `0..n`: same reader (comments
   preserved in `:seon.cluster.run.form/source`), same fork, same
   receipts, `:seon.cluster.run.form/author :system`.
3. The prompt is acquired only after the fixed point; the model reply's
   forms append at `n+1..` with `:author :agent`. Agent forms NEVER
   re-execute (ruling 12's fence).
4. At T0 the history is empty, so the "delta" is the whole episode. There
   is no bootstrap plan, no EDN, no special path: T0 is T1 with maximal
   gap (ruling 24 deleted the plan machinery; nothing may reintroduce it).
5. At T2 (a fact changes, no message) NOTHING enters settled history; the
   page may render the pending block (W3); the next wake's `:generate`
   phase emits the delta (T2-ruled).

## Contracts

### The derivation (landed owner, extended)

`seon.bootstrap/next-entry` — pure; extended per D5-ASSUMPTION to the
one-pass fold. Request and return are open maps:

```clojure
{:seon.bootstrap/pull        <the one membership pull result>
 :seon.bootstrap/history     <ordered retained entries (settled receipts)>
 :seon.bootstrap/fold        {:seon.bootstrap/explained #{symbols...}
                              :seon.bootstrap/shown     {collection-key basis}
                              :seon.bootstrap/frontier  [introductions...]}}
=> {:seon.repl/entries [{:seon.repl/comment "..." :seon.repl/form (...)}...]
    :seon.bootstrap/fold <advanced accumulator>}
```

- The fold accumulator is DERIVABLE from history (crash-safe); carrying it
  is disposable acceleration (D5-ASSUMPTION: one pass per wake, no
  per-form re-pull; the landed per-form re-derivation is deleted in the
  same change).
- Emission rule (D1 RULED — pure closure): seed the frontier from the
  ACTION ARC — the open run's trigger message and the demonstration's
  shape — never from the whole pull. A symbol enters the emitted set only
  through the explained-set closure of arc forms. Beyond-closure
  admission: ONLY shapes carrying a declared render function
  (nearest-first, to the cap) — no other beyond-closure source exists.
- Ordering: introduction order with fact-owned ties (ordinals,
  transaction order, stable identities — never string/hash; the N8 class).
  Determinism: same (pull-basis, history) ⇒ byte-identical entries; the
  landed `:seon.bootstrap/prefix-drift` refusal stays the loud invariant.

### Delta forms (D6-ASSUMPTION: zero new arities)

A collection already shown at basis T generates the honest delta with the
ONE existing mechanism: the `since` database read plus per-new-id entity
reads. No callable grows delta options. Provenance comments derive from
tx-meta (`:seon.db/user`/`:seon.db/process`; ruling 33): a plan changed by
root renders `; Root updated my plan — reviewing the new items.` — the
comment is a render of the transaction's facts, never inference.

### The situation (landed, extended)

`(help)` returns the agent-situation shape (landed): id, namespace ref,
unread count, open-run ref, protocol namespaces, plus (landed tonight)
`:seon.cluster.run/trigger` and the turn budget. D3-ASSUMPTION: when
turns-remaining reaches zero the loop force-settles `:wait` with a typed
budget-exhausted condition — a flat declared value on the receipt the
requester sees; nothing silently stops.

### Corrections (D4-ASSUMPTION)

A misleading earlier entry is corrected ONLY by appending the same read
freshly derived at a newer basis. No mutation, no apology prose. The
regression: render a value with renderer v1, fix the renderer, refresh —
the history holds both entries, newest-basis wins in blocks (rulings
4/19), bytes of the old entry unchanged.

## Worked example A — the generated T0 episode (byte authority)

Normative target for a fresh `task-agent-9` whose trigger message asks it
to establish its namespace status. `[landed]` = produced by current code;
`[target]` = this implementation's acceptance bytes. Values are REAL
executions at creation; if any exchange settles differently, creation
fails loudly (the drift guarantee).

```clojure
user=> (help)                                                    ; [landed]
{:seon.cluster.agent/id "task-agent-9"
 :seon.cluster.agent/namespace my.agents.task-agent-9
 :seon.cluster.message/unread 1
 :seon.cluster.run/open [:seon.cluster.run/id "run-…"]
 :seon.cluster.run/trigger [:seon.cluster.message/id "task-…"]
 :seon.cluster.run/turns-remaining 6
 :seon.repl/protocol [my.run my.message]}

user=> ; One unread message and an open run — read the trigger first.
user=> (doc 'my.message/inbox)                                   ; [target: explained-set forces the tool's doc before first use]
{:seon.program/name my.message/inbox
 :seon.fn/arglists ([] [{:seon.db/since basis}])
 :seon.fn/doc "The messages addressed to you, newest last."}
user=> (message/inbox)
[{:seon.cluster.message/id "task-…"
  :seon.cluster.message/from [:seon.cluster.agent/id "root"]
  :seon.cluster.message/preview "Establish your namespace status…"}]
user=> (message/read "task-…")
{:seon.cluster.message/content
 "Define your namespace's status render so the cluster can see your
  state: a contracted function returning your status shape, declared as
  its render."}

user=> ; The task IS the demonstration (D2): my own status render.
user=> ; Scratch first — prove the shape before contracting it.
user=> (defn status [db]
         {:my.agents.task-agent-9/functions 0
          :my.agents.task-agent-9/tests 0})
#'my.agents.task-agent-9/status
user=> (status)
{:my.agents.task-agent-9/functions 0 :my.agents.task-agent-9/tests 0}

user=> ; Contract it — schemas as metadata make it a program fact others
user=> ; can rely on; declaring :seon.db/database-value gets the db
user=> ; supplied automatically.
user=> (defn status
         "My namespace's current state."
         {:malli/schema [:=> [:cat :seon.db/database-value]
                         :my.agents.task-agent-9/status]}
         [db]
         {:my.agents.task-agent-9/functions
          (or (db/q '[:find (count ?f) . :in $ ?ns :where
                      [?n :seon.ns/name ?ns] [?f :seon.fn/ns ?n]]
                    db 'my.agents.task-agent-9) 0)})
#'my.agents.task-agent-9/status

user=> ; Is the contract enforced? One wrong call to see the error shape.
user=> (status "not-a-db")
{:seon.error/kind :seon.instrument/contract-violated
 :seon.error/message "status violated its contract (invalid-input): …"}

user=> ; Declare it as my status shape's render and pin it with a test.
user=> (register! :my.agents.task-agent-9/status
         [:map {:seon.render/ai my.agents.task-agent-9/status-ai}
          [:my.agents.task-agent-9/functions :int]])
:my.agents.task-agent-9/status
user=> (deftest status-counts-functions
         (is (<= 0 (:my.agents.task-agent-9/functions (status)))))
#'my.agents.task-agent-9/status-counts-functions
user=> (seon.test/run 'my.agents.task-agent-9/status)
{:seon.test/passed 1 :seon.test/failed 0}

user=> ; Defined, contracted, rendered, tested. Report and close.
user=> (run/complete "status is live: contracted, declared as my
        namespace's render, tested green.")
{:my.run/completed "status is live: …"
 :my.run/delivered-to [:seon.cluster.agent/id "root"]}
```

Acceptance: the derivation emits exactly this arc's closure (D1) — no
namespace tours, no unrelated dirs; every symbol used passes the
explained-set regression; the register!/render exchange makes the agent's
status a declared shape whose block root's tiles consume (rulings 19/D2);
`delivered-to` is visible in complete's settled value (ruling 31 — extend
`:my.run/completed`'s shape if delivery is invisible today, accretion).
The exact demo defn bodies may differ as the demonstration's usage test
evolves; the SHAPE (scratch → contract → one error → render declaration →
test → complete, comments narrating intent) is normative, byte-pinned by
the suite-gated usage test, not by this document.

## Worked example B — the T1 delta (byte authority)

`task-agent-9`, later. Two messages arrived since the inbox was shown at
basis 1041; root also updated the agent's plan (tx-meta user "root").
The wake's `:generate` phase emits, executes, and appends ordinals 0..3;
the prompt then carries prompt-N's bytes + exactly:

```clojure
user=> ; Two new messages since I last looked, and root changed my plan.
user=> (message/inbox {:seon.db/since 1041})
[{:seon.cluster.message/id "m-77" …} {:seon.cluster.message/id "m-78" …}]
user=> (message/read "m-77")
{…}
user=> (message/read "m-78")
{…}
user=> ; Root updated my plan — reviewing the new items.
user=> (db/pull {:seon.db/since 1041} '[:seon.cluster.agent/plan]
                [:seon.cluster.agent/id "task-agent-9"])
{:seon.cluster.agent/plan […only the changed items…]}
```

Acceptance: prompt N is a byte prefix of prompt N+1 (landed regression);
the generated comment's "root" derives from the transaction's
`:seon.db/user` (ruling 33 regression); re-teaching is absent (the
explained-set already contains every symbol above — self-erasure
regression); each entry has a real receipt at ordinals 0..n before any
model call (the generate→call transition regression).

## Deletions (in the same changes)

The per-form re-pull in the landed `next-entry` caller (D5); any remaining
authored-form residue the strict-dogfood audit's issues name; the
`largest` walkthrough content when the usage test retargets to the
status-render arc (D2 — the my.run usage TEST persists, retargeted).

## Phases, each with one class regression

1. **Fold + closure** (bootstrap.clj): one-pass fold (D5-ASSUMPTION);
   arc-seeded frontier (D1 RULED); regression: derivation of example A
   performs ONE pull and zero per-form re-derivations (count at the door).
2. **Demonstration retarget** (my.run usage test + situation): the
   status-render arc replaces largest; regression: the usage test is
   suite-green and `usage-form` renders it; no `:seon.test/usage` stamp
   in the per-agent replica (D2 RULED).
3. **T1 wiring** (consumes the pre-factored generate→call + prefix-append):
   regression: example B's ordinals/receipts/prefix assertions.
4. **Budget settle** (loop.clj; D3-ASSUMPTION): regression: a run at zero
   turns settles `:wait` with the typed condition visible to the requester.
5. **Corrections** (D4-ASSUMPTION): the renderer-v1/v2 refresh regression.
6. **Drive**: one flash drive on the generated episode with an independent
   observer; MINIMUM re-measured (expect below HALF's 7,393 tokens).

Lane ownership per phase is one owner each (bootstrap.clj; the usage
test's namespace; loop/work consume-only; loop.clj; render/history;
drives) — no two phases share a file, so they parallelize after phase 1.

## Open assumptions awaiting owner markup

D3 (zero-turn force-`:wait`), D4 (corrections-as-re-observations),
D5 (one-pass fold), D6 (zero new delta arities) — each specced above at
its recommended answer; a contrary ruling changes only its phase.
