---
type: research
status: active
tags: [research, curation, effect, program-graph, database, sci]
---

# Per-form effect visibility for session curation (2026-08-04)

Curation repairs a run's forms, re-executes them on a fork, and adopts the
clean session. It is safe exactly where loss is free. A form whose effect LEFT
the branch — a delivered message, a capability request through the door, a real
file edit, an external process — must be pinned and preserved in order. The
design therefore needs one question answerable by query: **was this form
effectful, and how.**

This report answers that question against the landed system. Every claim below
carries a `file:line` or a live probe on the scratch cluster
`opuseffect0804` (`bin/seon start opuseffect0804`, HEAD of
`codex/runtime-reliability-refactor`). Probe transcripts are inline. I read
`plan/bootstrap-vector-design-2026-08-01.md` end to end before starting, and
`research/workload-classification-2026-07-28.md` end to end before writing §4.

The short version: **one of the four effect channels is fully queryable and
three are not.** The capability request handler records everything curation needs. Message
delivery, agent-issued database writes, and static pre-execution prediction
each have a specific missing fact, and in two cases the missing fact makes an
existing safety derivation silently answer "safe".

## 1. The capability request handler — complete, and the query works today

### What is recorded

`seon.effect/request*` (`src/seon/effect.clj:395-529`) commits an effect
receipt BEFORE the handler runs and settles it exactly once afterwards. The
receipt's identity is the form's identity:

```clojure
effect-id (pr-str [(:seon.cluster.run/id *context*)
                   (:seon.cluster.run.form/ordinal *context*)
                   effect-ordinal])              ; effect.clj:447-450
```

The open request (`effect.clj:453-470`) asserts `:seon.effect/run` (a ref to
the run), `:seon.effect/owner` (a ref to the `:seon.fn` row of the capability
function), `:seon.effect/form-ordinal`, `:seon.effect/ordinal`,
`:seon.effect/request-edn`, and `:seon.effect/opened-at`. Settlement
(`settle-call`, `effect.clj:210-248`) adds `:seon.effect/result-edn`,
`:seon.effect/result-size`, `:seon.effect/duration-ms`,
`:seon.effect/settled-at`, and an optional `:seon.effect/result-blob`.
Interruption (`interrupt-call`, `effect.clj:250-281`) is the third terminal
state. The entity is declared at
`resources/seon/schemas/seon.effect.edn` under `:seon.effect/receipt`.

Two structural properties matter for curation, both verified live:

**No capability request can happen outside a form.** With `*context*` unbound the
door refuses:

```text
;; door mode, no run
(my.fs/read {:my.fs/path "deps.edn"})
#:seon.error{:kind :seon.effect/no-evaluation-context,
             :message "Capability requests require a current run form."}
```

(`effect.clj:399-402`.) So every door effect is attributed to exactly one
`(run, form-ordinal)` by construction — there is no unattributed capability
path to audit for.

**Replay is refused by identity, not by policy.** `open-call`
(`effect.clj:194-208`) throws `:seon.effect/already-recorded` when the effect
id already exists. Because the id is `[run-id form-ordinal effect-ordinal]`,
re-executing the same form of the same run cannot re-dispatch the same effect.
On a curation FORK this protection follows the run id: a fork that keeps the
run id inherits the receipts and therefore cannot re-dispatch; a fork that
re-opens under a new run id will dispatch again. That is a design lever
curation should choose deliberately, not a bug.

### The query, run live

Probe: one seeded run whose four forms were a pure expression, a `my.fs/read`,
a `my.message/send`, and a `seon.db/transact!`; folded through the real
`seon.cluster.loop/turn` (`:resume`, 4 forms run).

```clojure
(db/q '[:find ?form-ordinal ?effect-ordinal ?owner ?handler ?id
        :in $ ?run-id
        :where
        [?run     :seon.cluster.run/id       ?run-id]
        [?receipt :seon.effect/run           ?run]
        [?receipt :seon.effect/form-ordinal  ?form-ordinal]
        [?receipt :seon.effect/ordinal       ?effect-ordinal]
        [?receipt :seon.effect/id            ?id]
        [?receipt :seon.effect/owner         ?owner-eid]
        [?owner-eid :seon.fn/sym             ?owner]
        [?owner-eid :seon.effect/capability  ?handler]]
      db "probe-effects-1")
⟹ ([1 0 "my.fs/read" seon.fs.jvm/read "[\"probe-effects-1\" 1 0]"])
```

Form 1 issued one capability request; the capability family is named twice over —
by the owner symbol's namespace (`my.fs`) and by the declared handler
(`seon.fs.jvm/read`). Adding `(get-else $ ?receipt :seon.effect/interrupted-at
...)` and `:seon.effect/result-edn` distinguishes pending, returned, and
interrupted receipts (`receipt-state`, `effect.clj:37-42`).

The capability inventory is itself a query, not a list — 10 leaves in 4
families on this branch:

```clojure
(db/q '[:find ?s ?h :where [?f :seon.effect/capability ?h] [?f :seon.fn/sym ?s]])
⟹ my.edit/{exact,form,lines} -> seon.edit.jvm/edit
;;    my.fs/{read,write,glob,stat} -> seon.fs.jvm/{read,write,glob,stat}
;;    my.shell/run -> seon.shell.jvm/run
;;    my.web/{fetch,search} -> seon.web.jvm/{fetch,search}
```

**Verdict for §1: no missing fact.** Door effects are pinned by query, per
form, with capability family, request payload, result size, duration, and
terminal state. Background effects (`:seon.effect/notify`, `effect.clj:467-470`)
are covered by the same receipt.

## 2. Message sends — the fact exists, the form linkage does not

`my.message/send` is deliberately NOT a door capability
(`src/my/message.clj:19-29`). It is a pure constructor returning a value; the
run loop's terminal transaction commits the message
(`src/seon/cluster/loop.clj:1586-1621`, rows from
`seon.cluster.message/delivery`, `src/seon/cluster/message.clj:305-421`). That
is the right shape and it is not what is missing.

What is missing is the ref back to the form. The committed entity, pulled live
from the probe run:

```clojure
{:seon.cluster.message/id      "probe-effects-1-2-message-0"
 :seon.cluster.message/to      {:db/id 13992}
 :seon.cluster.message/from    {:db/id 13992}
 :seon.cluster.message/content "probe message from form 2"
 :seon.cluster.message/at      #inst "2026-08-04T20:30:24Z"}
```

The declared entity (`resources/seon/schemas/seon.cluster.message.edn`,
`:seon.cluster.message/message`) permits exactly `id`, `to`, `content`, `at`,
`from`, `caused-by`, `about`, and `:my.message/reason`. **There is no
`:seon.cluster.message/run` and no form ordinal.**

The provenance exists only inside the identity STRING. `message-id`
(`message.clj:195-201`) builds `"<run-id>-<ordinal>-message-<index>"`. So
"which form delivered this message" is answerable today only by taking that
string apart — which is the banned shape twice over: it is a naming convention
standing in for a fact, and recovering the fields from it requires parsing text
that a run id containing a dash already makes ambiguous.

Worse, the linkage is not even uniformly present. A message that names an
`about` identity takes a different id from `assignment-message-id`
(`message.clj:394-403`), which encodes the assignment target and the recipient
and drops the run and ordinal entirely. An assignment message therefore has NO
form provenance in any form.

The delivery REQUEST already carries the ordinal
(`:seon.cluster.message/delivery-request` declares
`:seon.cluster.run.form/ordinal`, and `loop.clj:1593` passes it) — the fact is
computed, used to derive a string, and then discarded.

**Verdict for §2: one missing attribute.** `:seon.cluster.message/form` (a ref
to the `:seon.cluster.run.form` entity, which is already an identified entity —
`resources/seon/schemas/seon.cluster.run.form.edn`) closes it completely, for
both ordinary and assignment messages, and makes the derived id an ordinary
identity rather than a provenance carrier.

## 3. Agent-issued `seon.db/transact!` — no receipt, no provenance, no linkage

### What is recorded: nothing beyond the datoms

`seon.db/transact!` (`src/seon/db.clj:916-940`) commits through the ambient or
explicit connection. It attaches no transaction metadata — `grep -n "tx-meta"
src/seon/db.clj` returns nothing. Live, through the door:

```clojure
(let [r (seon.db/transact! [{:seon.cluster.agent/id "probe-agent-door"}])]
  (select-keys r [:tx-meta :tempids]))
⟹ {:tx-meta #:db{:txInstant #inst "2026-08-04T20:32:32.417-00:00"
;;                   :commitId #uuid "6a724c60-04e5-50f5-b3b8-1475dc97c4cf"}
;;     :tempids #:db{:current-tx 536870977}}
```

Only Datahike's own two keys. No `:seon.db/user`, no `:seon.db/process`, no run,
no form. System call sites do supply provenance (`src/seon/cluster.clj:1158`,
`1219`, `1262`; `src/seon/fn.clj`; `src/seon/render/web.clj`), which makes the
absence on the agent path the anomaly rather than the norm.

The loop's own terminal transaction is in the same state: `loop.clj:1612-1621`
passes `{:tx-data …}` with no `:tx-meta`, and the door's open/settle
transactions (`effect.clj:472-474`, `359-361`) likewise. So today, given a
committed datom, there is no query that names the form that wrote it.

A failed agent write does leave a trace — as a flat error value in the eval
receipt's `:seon.cluster.eval/result-edn`:

```text
;; probe form 3, verbatim from the receipt
#:seon.error{:kind :seon.db/rejected
             :message "clojure.lang.ExceptionInfo: Bad entity value … "}
```

That is the errors-as-values contract working. It is not effect provenance: a
SUCCESSFUL write leaves an ordinary transaction report in the receipt and
nothing that identifies what changed.

### Is an in-branch write replay-free, or pinned?

Reasoning from the branch/fork model rather than from the code, because this is
a design question:

A curation fork starts from a chosen basis commit of the branch and re-executes
the kept forms. The datoms the ORIGINAL branch holds are not consulted by the
fork — the fork re-derives them by re-running the form. So in the loss-is-free
sense an in-branch database write **is replay-free**: it did not leave the
branch, and re-execution reproduces it. This is the same property that makes
the whole "adopt the clean session" move legal.

Three exceptions, and they are real:

1. **Basis-derived identities.** Any write whose identity is a function of the
   basis diverges on a fork. `inbound-message-id` (`message.clj:253-256`) uses
   `(:max-tx db)`; `exact-session-row-tx` (`loop.clj:371-384`) upserts by
   `:seon.code.def/id`. A curated fork produces different identities for the
   first shape, so anything that referenced the old identity dangles.
2. **Cross-agent visibility.** A write is confined to the branch, but not to
   the agent. If the write changed facts another agent was already awake on and
   that agent acted, the effect left the FORM even though it never left the
   branch. The pin rule "effects that left the branch" is therefore not quite
   the right predicate; the operational predicate is "effects another actor
   could already have observed", and for database writes that means: was any
   other run's receipt committed after this transaction on the original branch.
3. **Adoption is a branch-head replacement.** Anything committed on the original
   branch after the curation basis — by another agent, by the web boundary, by
   a system reconcile — is lost when the fork is adopted, whether or not the
   curated run wrote it.

So: **replay-free by default, pinned by exception**, and today NONE of the
three exceptions is detectable by query, because the write carries no
identity of its writer.

**Verdict for §3: one missing fact, cheaply available.** `seon.effect/*context*`
already holds `:seon.cluster.run/id` and `:seon.cluster.run.form/ordinal`
during every agent evaluation (`resources/seon/schemas/seon.effect.edn`,
`:seon.effect/request-context`). `seon.db/transact!` attaching that context as
`:tx-meta` when it is bound turns "which forms wrote to the database, and what
did they write" into a Datalog query over transaction entities, with zero new
storage and no second mechanism.

## 4. Static prediction before execution — the mechanism exists and is currently blind

### The mechanism

Two implementations of the same reachability question already exist:

- `seon.effect/capabilities` (`effect.clj:140-158`) — a Datalog rule pair over
  `:seon.fn/calls` returning the set of capability owners reachable from a
  symbol. Live: `(capabilities db 'my.fs/read)` ⇒ `#{"my.fs/read"}`;
  `(capabilities db 'seon.bootstrap/help-text)` ⇒ `#{}`.
- `capability-free-references?` (`loop.clj:343-369`) — the loop's own
  fail-closed walk. It is the exact shape curation needs: given the Vars a form
  mentions, does any of them reach a `:seon.effect/capability` leaf, with an
  unresolvable Var answering `false` (unsafe).

The inputs are computed statically, without executing the form:
`resolved-form-vars` (`src/seon/sci/eval.clj:364-380`) and
`unproven-called-vars` (`eval.clj:382-400`) walk `(tree-seq coll? seq form)` and
resolve symbols through the cluster ctx. Nothing is evaluated. The consumer
today is `session-image-tx` (`loop.clj:386-463`), which uses the answer to mark
a session definition `:seon.code.def/unrestorable` with reasons including
`"Defining form reaches a capability leaf."`

So the answer to the question as asked — *can the same reachability answer
"does this form's call graph reach a capability leaf" for a candidate replay
form?* — is **yes in principle, and no in practice on this build.** Three
independent gaps, each probed.

### Gap 4a — capability leaves are HOST Vars, so the walk skips them entirely

Both `resolved-form-vars` and `unproven-called-vars` keep a candidate only when
`sci.impl.utils/var?` is true (`eval.clj:377`, `eval.clj:396`). Probed against
the live cluster ctx:

```clojure
(sci/binding [sci/ns (sci/create-ns 'my.agents.root)]
  (into {} (map (fn [s] [s {:class (.getName (class (sci/resolve ctx s)))
                            :sci-var? (sci.impl.utils/var? (sci/resolve ctx s))}]))
        '[my.fs/read my.message/send seon.db/transact! +]))
⟹ {my.fs/read        {:class "clojure.lang.Var" :sci-var? false}
;;     my.message/send   {:class "clojure.lang.Var" :sci-var? false}
;;     seon.db/transact! {:class "sci.lang.Var"     :sci-var? true}
;;     +                 {:class "sci.lang.Var"     :sci-var? true}}
```

The capability leaves resolve to host `clojure.lang.Var` objects, so the var?
predicate rejects them and they never enter either set. The consequence,
measured directly:

```clojure
(check "(my.fs/read {:my.fs/path \"deps.edn\"})")
⟹ {:referenced [] :unproven [] :capability-free? true}
(check "(+ 1 2)")
⟹ {:referenced [clojure.core/+] :unproven [] :capability-free? true}
(check "(seon.db/transact! [{:seon.cluster.agent/id \"z\"}])")
⟹ {:referenced [seon.db/transact!] :unproven [seon.db/transact!]
;;     :capability-free? true}
```

A form that calls the filesystem door directly is classified capability-free.
The fail-closed design (`eval.clj:382-385` is explicit that a missing program
row must not read as pure) is defeated before it runs, because the symbol is
dropped rather than reported as unproven. Note the third line: `seon.db/transact!`
IS seen and IS marked unproven, so the classifier is not uniformly blind — it is
blind exactly at the capability boundary it was written to guard.

This is a defect in the current build, not a design limit. It is the one item
in this report I would fix before building curation on the derivation.

### Gap 4b — agent-authored functions carry no call edges at all

Probe: a run whose single form was a contracted `defn` calling the door.

```clojure
;; the form, run through the real fold
(defn grab-deps
  {:malli/schema [:=> [:cat] [:or :my.fs/read-result :seon.error/value]]}
  []
  (my.fs/read {:my.fs/path "deps.edn"}))

;; the resulting program row
(db/pull db '[* {:seon.fn/calls [:seon.fn/sym]}]
         [:seon.fn/sym "my.agents.root/grab-deps"])
⟹ has :seon.fn/sym :seon.fn/ns :seon.fn/source :seon.fn/spec
;;         :seon.fn/arities :seon.fn/ast :seon.fn/arglists
;;    and NO :seon.fn/calls
```

`:seon.fn/calls` is populated only by the clj-kondo index of first-party source
(`src/seon/fn.clj:292-349`, edges at `338-341` from `calls-by-caller`). The
agent path installs its row through `install-program-row!`
(`eval.clj:637-…`, row built at `eval.clj:1345`), and `seon.program/canonical-row`
(`src/seon/program.cljc:28`) admits `:seon.fn/calls` as an attribute nobody on
that path computes.

So reachability over `:seon.fn/calls` is **vacuous for exactly the functions
curation must judge**: every agent-authored function is a leaf with no outgoing
edges, and therefore trivially "capability-free" no matter what its body does.

For calibration, the mechanism is intact where the edges exist — `my.fs/read`'s
own row has `:seon.fn/calls #{seon.effect/request!}` and its capability
declaration, and the rule returns the right answer for first-party symbols. The
graph is 2265 `:seon.fn` rows on this branch; the hole is the agent-authored
subset.

### Gap 4c — derived workload classification is not implemented as facts

`research/workload-classification-2026-07-28.md` §2 specifies the propagation
rule (only-compute ⇒ `:compute`, only-io ⇒ `:io`, both ⇒ `:mixed`, unresolved ⇒
`:mixed`) as "one Datalog rule over `:seon.fn/calls` + `:seon.fn/workload`". The
metadata lift landed (`fn.clj:344-345`). The derivation did not. Live:

```clojure
(db/q '[:find ?s ?w :where [?f :seon.fn/workload ?w] [?f :seon.fn/sym ?s]])
⟹ exactly 10 rows, all :io — the same 10 capability leaves as §1
```

There is no derived `:io`/`:compute`/`:mixed` answer for any non-leaf function
anywhere in the database. Curation cannot ask the workload question at all
today; it can only ask the capability question, subject to 4a and 4b.

This is not a criticism of that design — the research doc is explicit that the
derivation is a query and should not be stored on consumers. It is a statement
of what a curation design may assume: **assume `:seon.fn/calls` +
`:seon.effect/capability` and compute; do not assume `:seon.fn/workload` says
anything about a chain.**

### Named limits that remain even after the gaps close

Both are inherent to static analysis and should be answered by fail-closed
classification, never by cleverness:

- **Dynamic calls.** `((resolve (symbol "my.fs" "write")) {})` and
  `(let [g my.fs/write] (g {}))` both probe as `:referenced
  [clojure.core/resolve clojure.core/symbol]` / `[clojure.core/let]` — the
  capability is invisible. `unproven-called-vars` is the right home for this:
  a call whose operator is not a resolvable Var must report as unproven.
- **Higher-order capability passing.** A pure-looking `(map f rows)` where `f`
  was bound to a door function earlier in the session is not decidable from the
  form. The receipt-based answer in §1 is the ground truth here; static
  prediction is the pre-filter, and its errors must fall to the pinned side.

## 5. The honest gap list

Ranked by what blocks a correct curation design.

| # | Missing fact | Consequence for curation | Where it belongs |
|---|---|---|---|
| G1 | Capability leaves are skipped by the form-Var walk (host `clojure.lang.Var` vs `sci.lang.Var`) | A form calling `my.fs/write` is statically classified capability-free — the classifier fails OPEN at its own boundary | `eval.clj:377`, `eval.clj:396` |
| G2 | Agent-authored `:seon.fn` rows have no `:seon.fn/calls` | Reachability is vacuous for every function an agent wrote; a curated replay cannot predict its own effects | `eval.clj:1345` row build / `install-program-row!` |
| G3 | No transaction provenance on agent database writes | "Which forms wrote to the database, and what" is unanswerable; the three fork exceptions in §3 are undetectable | `db.clj:916-940` (`:tx-meta` from `effect/*context*`) |
| G4 | Message entities carry no form ref | Message provenance survives only inside a derived id string, and not at all for assignment messages | `resources/seon/schemas/seon.cluster.message.edn` + `message.clj:394-418` |
| G5 | No fact records which facts a form READ | A form kept for replay may depend on a value an earlier discarded form wrote; nothing detects it | new; see §6.4 |
| G6 | No derived workload/effect class as a query surface | Every consumer re-implements reachability (there are already two: `effect/capabilities`, `loop/capability-free-references?`) | one owner, §6.5 |

G1 and G2 are defects in landed code. G3 and G4 are absent attributes. G5 is a
genuinely new fact. G6 is consolidation.

## 6. Recommended design

The principle throughout: **curation asks one derived question per form, and
every input to it is an existing fact plus a ref.** Nothing about "effectful" is
stored on the form; it is computed from receipts, refs, and reachability at the
basis being curated.

### 6.1 Close G1 by treating any Var as a Var

`resolved-form-vars` and `unproven-called-vars` should accept a host
`clojure.lang.Var` as well as a `sci.lang.Var`, mapping both to a qualified
symbol. The fail-closed intent of `unproven-called-vars` is already documented
in its own docstring (`eval.clj:382-385`); the predicate simply does not match
the objects the cluster ctx actually binds. This is a two-line change at one
choke point and it repairs `capability-free-references?`,
`:seon.code.def/unrestorable`, and any future curation consumer at once.

Falsifier for the fix: the probe in §4a must flip `(my.fs/read …)` to
`:capability-free? false`, and `(+ 1 2)` must stay `true`.

### 6.2 Close G2 by publishing call edges for agent-authored definitions

The SCI evaluation already resolves every symbol in the form (that is what
`resolved-form-vars` is). The same resolution, restricted to symbols that have
a `:seon.fn/sym` row, IS the call-edge set for the definition being installed.
`install-program-row!` should assert it as `:seon.fn/calls`, exactly as the
clj-kondo indexer does for first-party source — same attribute, same meaning,
one mechanism.

Two properties to keep honest:

- an edge to a symbol with no program row is NOT dropped; it is the unproven
  case, and the row should say so rather than look edgeless (an
  `:seon.fn/unproven-calls` set of qualified symbols, or the same fail-closed
  treatment applied at query time);
- the edges are over-approximate by construction (a shadowed local can only add
  a spurious edge), which is the safe direction: a spurious edge pins a form
  that could have been replayed; a missing edge replays a form that should have
  been pinned.

Falsifier: after the fix, `my.agents.root/grab-deps` from §4b must have
`:seon.fn/calls` containing `my.fs/read`, and
`(seon.effect/capabilities db 'my.agents.root/grab-deps)` must return
`#{"my.fs/read"}`.

### 6.3 Close G3 and G4 with two refs, not two mechanisms

**Database writes.** `seon.db/transact!` attaches `:tx-meta` derived from
`seon.effect/*context*` when that context is bound — the run and the form
ordinal, alongside the `:seon.db/process` the system call sites already use.
Absent context (a system caller, a REPL probe) keeps today's behavior. This
makes the query "which forms wrote datoms in run R" a join over transaction
entities, and it makes §3's exception 2 (another actor observed the write)
answerable by comparing transaction ordering.

**Messages.** Add `:seon.cluster.message/form` as a ref to the
`:seon.cluster.run.form` entity, asserted in `message.clj:394-418` from the
ordinal the delivery request already carries (`loop.clj:1593`). Assignment
messages get it too, which is the case that has no provenance at all today. The
derived id then goes back to being only an identity.

### 6.4 G5 — reads, stated rather than solved

A form is unsafe to drop when a KEPT form depended on what it wrote. Door
receipts and message rows cover the write side. The read side has no fact, and
inventing one is a real design decision (a read set per form is potentially
large and is exactly the kind of stored-derived state this codebase rejects).

Two options, offered without a recommendation because this belongs to the owner:

- **Basis-interval reasoning, no new fact.** Each form's eval runs at the
  previous form's `:db-after` (`loop.clj`'s fold contract, `turn` docstring at
  `loop.clj:1712-1720`). Curation can therefore bound dependency by transaction
  interval: a kept form MAY have read anything written before its basis. This
  over-pins, costs nothing, and is derivable today.
- **Attribute-grain read facts.** Record the attributes a form's queries
  touched. Precise, and a new durable fact per form with unclear bounds.

The first is the accretion-safe starting point; the second is a later
refinement if over-pinning proves expensive in practice.

### 6.5 G6 — one owner for the reachability answer

There are already two implementations of "what capabilities does this reach"
(`effect/capabilities`, `loop/capability-free-references?`) with different
fail-closed semantics and different inputs. Curation would be the third. One
owner should answer, for a candidate form at a basis:

- the capability receipts already recorded for that form (§1) — ground truth;
- the messages and transactions referencing that form (§6.3) — ground truth;
- the capability set statically reachable from the form's resolved Vars (§6.1,
  §6.2) — prediction, fail-closed;
- and therefore one derived disposition: replayable, or pinned with the named
  reason.

That derived disposition is a QUERY over the basis, never an attribute stored
on the form. The reason string is for readers only; the reason as DATA is the
receipt/message/transaction/capability-symbol it came from. (The existing
`:seon.code.def/unrestorable` prose is the counterexample already filed at
`docs/seon/issues/session-image-stores-derived-unrestorable-prose.md`; curation
must not repeat it.)

## 7. Ugly rendered output met while probing

Per the standing order. None of these is this lane's to fix.

1. **A contract violation renders a serialized print-face tree as a string
   inside an error value.** `(my.fs/read "deps.edn")` through the door returns
   `:seon.error/data` whose `:seon.instrument/problems` is a `pr-str` of
   `#:seon.print{:face :seon.print/vector, :items [#:seon.print{...}]}` —
   several hundred characters of face machinery wrapping the two words that
   matter (`"deps.edn"`, `"invalid type"`). Same for `:seon.instrument/args`.
   An agent reading this learns nothing without decoding it.
2. **A database write renders the whole transaction report, including both
   database values.** `seon.db/transact!` of a 7-datom transaction returned a
   value that settled into the blob tier at **1,999,538 bytes** — `:db-before`
   and `:db-after` are full database values and are serialized. This is the
   single largest ugly result I met, and it is on the most common write an
   agent can make.
3. **`seon.effect/capabilities` violates its own output contract on a symbol
   absent from the graph.** `(capabilities db 'some/unknown)` pulls a nil root,
   passes `nil` as an entity id into the rule, and the resulting
   `NullPointerException` becomes a `:seon.db/invalid-read` flat error — which
   is not a `[:set :seon.fn/sym]` and so fails the declared output schema. The
   function is not total over its declared `:qualified-symbol` input.

Findings 1 and 3 are new; finding 2 is adjacent to
`docs/seon/issues/elided-marker-carries-no-count-or-identity.md` but is a
distinct cause (the value itself is wrong, not its elision).

## Appendix — probe procedure

Scratch cluster `opuseffect0804` (never the default). Runs were seeded exactly
as `plan/bootstrap-vector-design-2026-08-01.md` §1 describes — `run/open-tx` +
`run/claim-tx` + `run/plan-tx` with system-authored sources, then
`seon.cluster.loop/turn` with the `:resume` situation derived by
`seon.cluster.work/next-agent-work`. Three runs:

- `probe-effects-1` — 4 forms: pure, `my.fs/read`, `my.message/send`,
  `seon.db/transact!`. Outcome `:released`, 4 forms run. Source of §1's query
  result, §2's message entity, §3's rejected-write receipt.
- `probe-effects-2` — abandoned; `next-agent-work` correctly preferred an
  earlier `:call` situation created by probe 1's own message, which cost one
  real model turn (59 s). Recorded because it is evidence that the message
  path works end to end.
- `probe-effects-3` — one contracted `defn` calling the door. Source of §4b.

Static probes called `seon.sci.eval/resolved-form-vars`,
`seon.sci.eval/unproven-called-vars`, and
`seon.cluster.loop/capability-free-references?` directly against the cluster's
live ctx and branch database, evaluating no candidate form.
