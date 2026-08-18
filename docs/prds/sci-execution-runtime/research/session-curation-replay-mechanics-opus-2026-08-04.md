---
type: research
status: active
tags: [research, agent, runtime, database, sci, testing]
---

# Inline session curation — replay mechanics and the adoption question (2026-08-04)

Independent research lane. Read END TO END before designing, and this
report states it: `plan/bootstrap-vector-design-2026-08-01.md`,
`plan/grader-in-fact-space-2026-08-01.md`, `src/seon/bootstrap.clj`,
`src/seon/bootstrap_drive.clj`, `src/seon/eval/drive.clj`, plus the
owning implementation seams `src/seon/cluster/run.clj`,
`src/seon/cluster/loop.clj`, `src/seon/cluster/work.cljc`,
`src/seon/cluster/registry.clj`, `src/seon/program.cljc`,
`src/seon/sci/eval.clj`, `src/seon/render/transcript.clj`, and the
dependency source `reference-code/datahike/src/datahike/versioning.cljc`.

Every measurement below was produced on my own scratch cluster
`curation-opus` (booted with `bin/seon start curation-opus`, prepl 60839)
through `mcp__seon__eval_clj`. No other lane's cluster was touched; every
probe branch created was retired.

The problem: when a run closes carrying eval errors, a curator agent
proposes a corrected ordered vector of form sources; the system
re-executes them from the run's opening basis; if the replay is clean
(zero error receipts, equivalent terminal result), the curated session
becomes the agent's history.

## Headline

**Nothing in the replay path needs to be invented.** Open + claim + plan
with caller-provided sources, a fork at an arbitrary ancestor commit, an
independent SCI context over that fork, and the fold that executes the
plan without any model call — all four exist today and were driven end to
end in this lane. What does not exist is (1) the run's OPENING COMMIT as a
recorded fact, (2) a supersession fact, and (3) a `system-run-tx` seam
that `seon.bootstrap/seed-tx` would then be one caller of.

The adoption question has a decisive answer: **(A) projection-level
supersession**. Datahike's `merge!`
(`reference-code/datahike/src/datahike/versioning.cljc:688-702`) does not
compute a merge — the caller must supply the merged tx-data — so (B) can
only adopt the curated fork by `force-branch!`, which is documented as
`git reset --hard` and DISCARDS every fact any other agent committed on
the cluster branch after the fork point. Section 4 develops this.

## 1. Generalizing `seed-tx` — every seam, named

`seon.bootstrap/seed-tx` (bootstrap.clj:254-308) builds, for a NEW agent,
one transaction of `[namespace-row, open-tx, claim-tx, plan-tx]`. Its only
caller is `seon.cluster/ensure-entity-call` (cluster.clj:1233-1249), which
composes it with `cluster.agent/creation-tx` when the agent is absent.

### 1.1 The falsifier: it already generalizes

Probed live on a fork of `curation-opus`, for the EXISTING agent `root`,
with caller-provided sources and no bootstrap plan involved:

```clojure
(let [id "curated:probe1"
      sources [{:seon.cluster.run.form/source "(def opus-x 41)"
                :seon.ns/name 'my.agents.root}
               {:seon.cluster.run.form/source "(inc opus-x)"
                :seon.ns/name 'my.agents.root}]]
  (db/transact! fc {:tx-data (into [] cat
                                   [(run/open-tx  {…})
                                    (run/claim-tx {…})
                                    (run/plan-tx  {… :seon.cluster.run/sources sources})])}))
⟹ 19 datoms, no refusal
(work/next-agent-work @fc {:seon.cluster.agent/id "root" …})
⟹ {:seon.cluster.work/situation :resume
;;     :seon.cluster.run/id "curated:probe1"
;;     :seon.cluster.agent/id "root"
;;     :seon.cluster.run.form/ordinal 0}
```

The `:resume` derivation is `work.cljc`'s "has a `plan-digest` ⇒ never
`:call`" rule the bootstrap design already relies on. A system-authored
run for an existing agent is therefore the SAME object as a bootstrap run;
only its sources differ.

### 1.2 The seams that must change, one line each

| # | Seam | Today | What generalization requires |
|---|---|---|---|
| S1 | sources | `bootstrap/ordered-sources` derives from `:seon.bootstrap.plan/forms` (bootstrap.clj:207-222) | the general function TAKES `:seon.cluster.reply/sources`; the bootstrap becomes one caller that supplies them |
| S2 | run identity | `bootstrap/run-id` = `"bootstrap:<agent-id>"` (bootstrap.clj:130-135) | a curated run needs its own deterministic id, e.g. `"curated:<original-run-id>"`. Determinism IS the idempotence fence: a second curation of the same run refuses `:seon.cluster.run/run-exists` at `open-call` (run.clj:271) |
| S3 | plan digest | `bootstrap/plan-digest` digests the cluster's plan rows (bootstrap.clj:247-252) | digest the caller's sources. Nothing verifies the digest against anything — `plan-call` only requires ABSENCE of a prior digest (run.clj:430) — so a sources digest is the honest value |
| S4 | namespace row | `seed-tx` upserts the agent namespace with `requires`/`refers` | bootstrap-specific; must NOT ride the general seam. `plan-call` already upserts a bare `:seon.ns/name` per form namespace (run.clj:439-448) |
| S5 | the one-open-run fence | `open-call` refuses `::agent-already-running` when `:seon.cluster.agent/run` is present (run.clj:273-275) | curation may only open when the agent is idle. True immediately after the original run closes; a curation lane must not race a new inbound message |
| S6 | trigger | `seed-tx` passes none; `::trigger` is optional (run.clj:261) | DECISION: pointing the curated run at the original's trigger message makes it findable by `eval.drive/objective-run-ids` (drive.clj:110-119) and keeps grading queries unchanged. Recommended |
| S7 | custody | `seed-tx` claims for the boot process | the replay driver's process claims it on the FORK; the adopted copy on the cluster branch carries no `::process` (it is closed) |
| S8 | receipt identity | `(pr-str [run-id ordinal])`, "at most one attempt per form, EVER" (run.clj:484-491) | this is why curation is a NEW RUN and never a re-execution of the old one. The model forbids the alternative by construction — a feature, not an obstacle |
| S9 | effects | the fold's forms may call the guarded door (`seon.effect/request!`) | replay RE-EXECUTES real capability requests (fs writes, web, shell, llm). Curation of an effectful run repeats those effects. Recorded here as the one genuine semantic constraint of any replay design; §6 states the containment |

The refactor is therefore: extract `seon.cluster.run`-shaped
`system-run-tx {db, agent-id, run-id, sources, process, opened-at,
trigger?}` → tx-data, and make `seed-tx` its first caller (supplying
S1/S2/S4 from the bootstrap plan). That is the whole of "new mechanism"
for question 1.

## 2. The fork — how it works, what it costs, and forking at the OPENING commit

### 2.1 The grading fork today

`eval.drive/grading-branch!` (drive.clj:258-263) is three lines:

```clojure
(registry/branch! {:seon.store/store store
                   :seon.cluster.registry/from ending-commit
                   :seon.store/branch (keyword (str "inspect-grade-" episode-id))})
```

`ending-commit` is `(db/commit-id ending-db)` captured at the moment the
terminal state is observed (drive.clj:301-306). `registry/branch!`
(registry.clj:160-198) is the ONE owner of branch creation; `from` may be
a branch keyword or a **commit UUID**, supported because Datahike's
`:commit-graph?` defaults true (versioning.cljc:246-257). The grader then
opens a connection with `store/open-branch!` and retires the branch in a
`finally` (bootstrap_drive.clj:340-374).

### 2.2 Measured cost (curation-opus, 2263 `:seon.fn` rows)

| Operation | Measured |
|---|---|
| `registry/branch!` from HEAD commit | **28.2 ms** |
| `registry/branch!` from a 20-commits-back ancestor | **24.4 ms** |
| `store/open-branch!` (branch connection) | **4.7 ms** |
| `sci.eval/cluster-ctx` over the fork (cold acquire of the whole program graph) | **1888 ms** |
| `d/branch-history` full walk (191 commits) | 342 ms |

So a replay sandbox costs **~1.9 s**, essentially all of it the SCI
context acquire; the branch itself is free. The ctx is the price of not
polluting the live one — see §6.

### 2.3 Forking at the run's OPENING commit — the same seam, one missing fact

Proven: forking from an arbitrary ancestor commit yields a branch whose
basis is exactly that ancestor.

```clojure
;; ancestor = the 21st commit back on this branch
{:fork-ms 24.4 :ancestor-t 536870934 :branch-t 536870934 :head-t 536870955}
```

The database value on the forked branch carries the ancestor's `:max-tx`,
not the head's, and every `:seon.fn` row present at that basis (2262 of
them) is queryable there. **The opening-basis fork needs no new
mechanism** — it needs the opening COMMIT ID.

What exists today is `:seon.context.capture/basis-t`
(`resources/seon/schemas/seon.context.capture.edn`), a basis transaction
`t`, and `:seon.cluster.run/opened-at`, an instant. Neither is a commit
id, and `branch!` refuses anything that is not a branch keyword or a
commit UUID. The commit id CAN be recovered — every commit record carries
`{:meta {:datahike/commit-id … :datahike/parents #{…}}}` plus `:max-tx`,
so a walk backwards from the head finds the commit whose `:max-tx` equals
the recorded basis — but that walk is O(commits since the run) at roughly
2 ms per hop (measured: 25 hops = 49 ms; the full 191-commit walk = 342
ms) and it is exactly the shape the standing rule calls a defect: **the
question "where did this run open?" is not answerable by query, so the
missing fact is the defect.**

> **Seam N1 (new fact).** `:seon.cluster.run/opening-commit-id`
> (`:uuid`), asserted by `open-call` from the commit id of the
> mid-transaction database value. One datom, O(1), and it makes "fork the
> world this run started in" a `pull` followed by `registry/branch!`.

### 2.4 The replay executor already exists

The decisive probe. A loop-cluster record with the fork's connection and
the fork's own ctx substituted, driven through the ordinary public
`seon.cluster.loop/turn`:

```clojure
(def fcl (assoc cl :seon.store/branch-connection fc :seon.sci.eval/ctx fctx))
(cloop/turn {:seon.cluster.loop/cluster fcl :seon.cluster.work/next w} now)
⟹ {:seon.cluster.work/situation :resume
;;     :seon.cluster.loop/forms-run 2
;;     :seon.cluster.loop/outcome :released
;;     :seon.cluster.run/id "curated:probe1"}
;; receipts: 0 => #:seon.print{:face :seon.print/var :name "my.agents.root/opus-x"}
;;           1 => #:seon.print{:face :seon.print/number :value 42}
(cloop/turn … :close …)  ;; => :closed, zero error receipts
```

Two forms executed, correct values, receipts and program rows committed on
the fork, run closed — **with no model call and no new executor**, because
a run carrying a frozen plan never reaches `:call`. The replay driver is a
loop over `work/next-agent-work` + `loop/turn` against a substituted
cluster record.

## 3. Durable defns during replay — probed

Question: if the original run installed program rows and the replay
installs the same symbols, is it an idempotent upsert or a conflict?

**Answer: idempotent by IDENTITY, not by datoms.** `program-row-tx`
(run.clj:669-767) resolves the existing row by its identity attribute and
emits `program/exact-replacement-tx` (program.cljc:449-465). Measured on
`curation-opus` by evaluating one contracted `defn` twice and applying the
real seam both times:

| | first install | second install (byte-identical source) |
|---|---|---|
| tx-data items | 1 | 4 |
| datoms committed | 42 | **69, of which 34 are RETRACTIONS** |
| `:seon.fn/sym` rows afterwards | 1 | 1 |
| entity id | 14080 | 14080 (stable) |

No conflict, no refusal, no duplicate row, stable entity id. But it is
**not a no-op**: `program/changed-attributes` (program.cljc:424-444)
compares the PULLED entity shape against the DESIRED row shape, so

```clojure
(program/changed-attributes existing row)
⟹ [:seon.fn/ns :seon.fn/arities :seon.fn/ast]
;; existing :seon.fn/ns => #:db{:id 13991}     desired => [:seon.ns/name my.agents.root]
```

a lookup ref never equals a pulled `{:db/id …}` map, and pulled component
trees never equal the desired plain maps, so those three attributes report
"changed" on EVERY redeclaration and their component trees are retracted
and rebuilt. Consequences for curation:

- correctness is fine — replaying identical declarations converges;
- **equivalence must be judged over declared CONTENT** (`:seon.fn/source`,
  `:seon.fn/spec`, `:seon.fn/private?`), never over datom counts, entity
  ids of component rows, or a datom-level diff of the two branches;
- on a fork taken at the OPENING basis the curated declarations are
  usually clean inserts, so the churn only appears at adoption time.

Filed: [`docs/seon/issues/program-row-replacement-churns-identical-redeclarations.md`](../../../seon/issues/program-row-replacement-churns-identical-redeclarations.md).

**The schema case is sharper and is an argument in §4.** When the row is a
`:seon.schema/key` whose form differs, `program-row-tx` calls
`assert-schema-data-unused!` (run.clj:623-635), which REFUSES
(`:seon.schema/current-data-blocks-change`) while any current datom uses
an affected attribute. On a fork at the opening basis that data does not
exist yet; on the LIVE branch after the original run it may. A curated run
that redefines a schema the original run's data already uses cannot be
adopted by naive row copy — it must be adopted as receipts plus the
declarations that do not collide, with the collision surfaced as a
refusal, not silently dropped.

## 4. The adoption question — (A) supersession versus (B) branch adoption

### 4.1 What each design would be

**(A) projection-level supersession.** The cluster branch stays
append-only. The curated run is replayed and verified on a fork at the
opening commit; on success ONE transaction on the cluster branch asserts
the curated run entity, its form rows, its receipt rows, its program rows,
and a `supersedes` ref to the original run. The transcript projection
excludes superseded runs.

**(B) branch adoption.** The cluster branch head is moved to the curated
fork.

### 4.2 What exists, per design

| | (A) | (B) |
|---|---|---|
| exists | `registry/branch!` fork, `loop/turn` replay, ordinary `db/transact!` of plain rows | `registry/branch!` fork, `loop/turn` replay |
| new mechanism | ONE declared attribute (`:seon.cluster.run/supersedes`, a `:seon.db/ref`), one clause in two transcript queries, the adoption transaction builder | `datahike.versioning/force-branch!` — **not exposed by `seon.cluster.registry`, the ONE owner of branch lifecycle** — plus a whole-cluster connection replacement, plus a policy for concurrent facts |

`force-branch!` (versioning.cljc:323-336) states its own hazards in its
docstring: it "overwrites the branch head unconditionally, like git reset
--hard", "existing connections to this branch will see stale state and
must be released and reconnected", and "you can render data
inaccessible". `registry/reset-cluster!` — the only existing operation
that moves a cluster to a different commit — is `delete-branch!` +
`branch!` and REFUSES `::cluster-connected` while this process holds a
connection (registry.clj:224-251, 273-277). So (B) on a live cluster means
stopping the cluster.

### 4.3 The decisive fact: other agents' concurrent facts

Between the run's opening commit and the moment curation adopts, every
other agent in the cluster has been committing to the same branch:
receipts, messages, program rows, memory facts. The curated fork was taken
BEFORE all of it.

- Under **(A)** those facts are untouched. The adoption transaction is an
  ordinary append at the current head.
- Under **(B)** moving the head to the fork **discards every one of
  them**. There is no merge available to avoid this: Datahike's `merge!`
  (versioning.cljc:688-702) records parent commits and explicitly makes it
  "the responsibility of the caller to make sure that tx-data contains the
  data to be merged" — it computes nothing. Building that tx-data is a
  bespoke three-way datom merge over receipts, messages, program rows and
  schema declarations: a second mechanism, in the exact place the
  one-mechanism rule forbids one.

There is a second, worse consequence of (B). The original run's messages
were DELIVERED — other agents' runs already read them, and their own
receipts reference them. Rewriting the branch so those messages never
existed leaves other agents' committed history referring to facts that are
gone. (A) erases nothing: the original run's messages remain, and the
curated run is a later, better-explained fact beside them.

### 4.4 Crash and recovery

**(A).** The fork is disposable — a crash mid-replay leaves an unreachable
branch that `registry/retire-branch!` (idempotent) and `collect!` reclaim;
the roster IS the fact (registry.clj:44-63). Adoption is ONE transaction,
so there is no partial adoption to detect. Verified live: the curated run
plus its forms and receipts transplanted from the fork onto the live
`curation-opus` branch in a single `db/transact!` — 34 datoms, no refusal,
run present and closed, and `work/next-agent-work` did NOT pick it up (a
closed run is not work, and the agent's busy pointer was untouched).

**(B).** A crash before `force-branch!` leaves the old head (safe); a
crash after it leaves live connections holding a database value whose
branch head no longer descends from it, which is precisely the state
Datahike warns must be repaired by releasing and reconnecting. Recovery
would have to be a cluster restart. The crash model says "nothing
re-executes; reopen the store, mark dangling receipts interrupted,
re-derive" — (B) adds a case that model does not have.

### 4.5 Consistency with derive-don't-store and append-only

(A) is append-only by construction and the supersession is DERIVED at read
time: the transcript asks "is there a run that supersedes this one?" and
omits the superseded receipts. Nothing is stored twice, nothing is
retracted, and the debug page can render both — which is exactly what the
observability rule wants, because "what did the agent actually do, and
what did curation replace it with" must remain a query.

(B) is history rewriting: it makes the original run unqueryable rather
than superseded, which destroys the forensic record the whole
observability design exists to keep, and it does so with a Datahike
operation nobody in this system owns.

**Recommendation: (A).**

## 5. Result equivalence — which predicate seams already answer it

From the grading work (`plan/grader-in-fact-space-2026-08-01.md`,
implemented in `src/seon/eval/drive.clj` and `src/seon/bootstrap_drive.clj`),
five seams together decide "the curated replay reached the same completed
result". All of them run on a FORK, which is the point: grading never
touches the record it grades.

1. **`eval.drive/read-result`** (drive.clj:121-130) — the decoder. Receipt
   results are stored as print faces (measured:
   `#:seon.print{:face :seon.print/number :value 42}`), so equivalence is
   a comparison of DECODED VALUES. Comparing `:seon.cluster.eval/result-edn`
   strings would be comparing presentation.
2. **`eval.drive/completion-values` / `completed-result`**
   (drive.clj:161-168) — the terminal result: the last receipt value whose
   `:my.run/disposition` is `:completed`, projected to `:my.run/result`.
   This is the "same completed result" predicate.
3. **`eval.drive/terminal-state`** (drive.clj:211-244) — the outcome enum
   `:completed` / `:capped` / `:stopped`, derived from completions, closed
   run count, and `work/next-agent-work` returning nil (idle). A curated
   replay must reach `:completed`, not merely stop.
4. **`eval.drive/run-receipts`** (drive.clj:132-159) — carries
   `:seon.cluster.eval/error` and `:seon.error/kind` per ordinal. **The
   zero-error predicate is `(every? str/blank? (map :seon.cluster.eval/error
   receipts))`** over the curated run's receipts — the primary curation
   gate.
5. **`bootstrap-drive/candidate-functions` + `evaluate-function`**
   (bootstrap_drive.clj:120-158) — the strongest available equivalence:
   the durable symbols the run authored, then CALLING them on held-out
   inputs inside the grading branch. Applied to curation this reads:
   the curated run's declarations must behave the same as the original's
   on inputs neither run saw. `grade-o3` (bootstrap_drive.clj:204-209) is
   the pattern to copy for derived expectations — the expected value is
   COMPUTED by query at the graded basis, never stored.

Two gaps this lane found in those seams, both relevant to curation:

- **`grade-o2` and `grade-o5` match REGEXES over form source**
  (bootstrap_drive.clj:186-187, 258-259) to answer "did this receipt
  declare a symbol" and "which symbol". That is text where a parsed
  representation exists: the evaluation already produces
  `:seon.sci.eval/program-row` and the settle transaction already installs
  it. **The missing fact is a receipt → declaration ref** — today
  `candidate-functions` recovers it by joining the fn row's transaction to
  the receipt's `result-edn` transaction, which is clever and fragile.
  Declaring `:seon.cluster.eval/declared` (ref to the program row) turns
  both graders and every curation equivalence check into a join. Regexes
  over code additionally require the owner's permission.
- **`eval.drive/full-transcript` passes `:seon.config.eval/time-limit-ms`**
  (drive.clj:246-256) where `seon.render/render-ai`'s contract requires
  `:seon.sci.eval/time-limit-ms` — the contract refused this exact call
  shape live in this lane. Either the drive's transcript is being rendered
  through an unchecked path or it is passing a key nothing reads.

## 6. Recommended design — session curation, with named seams

**Shape:** curation is a system-authored run replayed on a fork of the
run's opening commit, verified in fact-space, and adopted by ONE
append-only transaction plus a supersession ref.

**New facts (the only new declarations):**

- **N1 `:seon.cluster.run/opening-commit-id`** `:uuid`, asserted by
  `run/open-call` from the mid-transaction database value's commit id.
  Makes "fork the world this run started in" O(1).
- **N2 `:seon.cluster.run/supersedes`** `:seon.db/ref`, asserted on the
  curated run, pointing at the original. Append-only; the original keeps
  every datom it had.
- **N3 (recommended, shared with the graders)
  `:seon.cluster.eval/declared`** `:seon.db/ref` from a receipt to the
  program row its form installed — deletes two regexes and makes
  declaration equivalence a join.

**New code, in the owners that already exist:**

- **C1 `seon.cluster.run/system-run-tx`** — `{db, agent-id, run-id,
  sources, plan-digest, process, opened-at, trigger?}` → the
  `open`/`claim`/`plan` tx-data (seams S1-S7). `bootstrap/seed-tx` becomes
  its first caller, keeping the bootstrap-specific namespace row and the
  plan-derived sources on the bootstrap side. No behavior changes for the
  bootstrap.
- **C2 `seon.curation/replay!`** — fork at N1's commit
  (`registry/branch!`), open a branch connection, build
  `sci.eval/cluster-ctx` over it, transact C1's tx-data for run id
  `"curated:<original>"`, then loop `work/next-agent-work` +
  `loop/turn` until the run closes. ~1.9 s of setup, then real execution.
  This is a driver over existing seams, not a new executor.
- **C3 `seon.curation/verdict`** — the §5 predicates on the fork: zero
  error receipts, `:completed` disposition, decoded `:my.run/result`
  equivalence, and (when the run authored functions) held-out execution of
  the curated declarations.
- **C4 `seon.curation/adopt-tx`** — one transaction on the cluster branch:
  the curated run row (closed, no `::process`, N2 pointing at the
  original), its form rows, its receipt rows copied from the fork, and its
  program rows through the SAME `program-row-tx` upsert (identity-stable,
  §3). Proven shape: 34 datoms in one commit, invisible to
  `next-agent-work`. Then `registry/retire-branch!` the fork.
- **C5 the projection** — one negation clause in
  `render/transcript`'s `recent-receipt-rows` (transcript.clj:156-171) and
  `pinned-receipt-ids` (transcript.clj:176-186): exclude receipts of a run
  that some other run supersedes. The debug page keeps rendering both,
  because both are still facts.

**Constraints this design accepts and states rather than hides:**

- **Effects re-execute (S9).** A replayed form that calls the guarded door
  performs its capability request again. Curation is therefore safe for
  computation-and-declaration runs and NOT automatically safe for runs
  that wrote files, sent web requests, or messaged peers. The honest
  containment is a precondition query — "does any form in the curated
  vector reach a capability leaf?" — which the program graph already
  answers by the reachability walk `loop/capability-free-references?`
  (loop.clj:343-369) uses. Curation of an effectful run is an owner
  decision, not a default.
- **Messages already delivered stay delivered.** The original run's
  messages remain visible; curation supersedes the agent's own transcript,
  never other agents' observations. This is a property of (A) and a reason
  for it.
- **Schema redefinition may refuse at adoption** (§3). That refusal is
  correct and must surface as a curation verdict, never be swallowed.

**What this deliberately does not do:** it does not move a branch head, it
does not introduce `force-branch!` into the registry, it does not build a
datom merge, and it does not re-execute anything inside the original run —
the receipt identity `(pr-str [run-id ordinal])` makes that
unrepresentable, which is the model working as designed.

## 7. Ugly or wrong rendered output met in this lane

Per the standing order, reported rather than fixed here.

1. **A bare `NullPointerException` from a konserve read.** Reading a
   commit record with `k/get … {:sync? true}` from a prepl session
   returned `Cannot invoke "java.util.concurrent.Future.get()" because
   "fut" is null` — no store id, no key, no operation. The same call had
   succeeded moments earlier in a different form, so the failure is
   context-dependent and completely unexplained by its own message.
2. **`seon.config/effective` returns `{}` for a live cluster name**, and
   the consequence is a ~5 KB wall of "missing required key" from
   `seon.config/result-caps`'s contract listing every config key, instead
   of one sentence naming the cluster whose config facts were not found.
   On `curation-opus` the config singleton carried
   `:seon.config/cluster "opuseffect0804"` — another cluster's name,
   inherited through the published source fork. `bootstrap_drive` and
   `eval.drive` both resolve caps this way (`config/effective db
   cluster-name`), so this is a live hazard for the drive harness, not
   only for probes. Filed:
   [`docs/seon/issues/forked-cluster-inherits-the-ancestors-config-cluster-name.md`](../../../seon/issues/forked-cluster-inherits-the-ancestors-config-cluster-name.md).
3. **`defn` returns a string, `def` returns a var face.** Measured this
   lane: `(defn opus-total …)` ⇒ `"my.agents.root/opus-total"`, while
   `(def opus-x 41)` ⇒ `#:seon.print{:face :seon.print/var :name
   "my.agents.root/opus-x"}`. The bootstrap design's finding #1 is still
   true AND the two declaration forms disagree with each other, which is
   worse than either divergence alone.
4. **Rendering a transcript out of band takes two contract violations to
   discover.** `render-ai` refuses first on
   `:seon.sci.eval/time-limit-ms` (a key `eval.drive` does not pass) and
   then on a missing `:seon.db/capture-context` atom, whose message —
   "must be a process-local atom collecting database read evidence" — does
   not say that the caller is supposed to be inside
   `render/call-with-walk-context`.

## 8. Probe log

All on `curation-opus`, 2026-08-04, via `mcp__seon__eval_clj` session
`opuscur`. Branches created: `:opus-probe-<ms>`, `:opus-past-<ms>`,
`:opus-replay-<ms>`, `:opus-curate-<ms>` — all retired; roster verified
clean afterwards.

- fork from HEAD commit: 28.2 ms; branch connection: 4.7 ms; 2263 fn rows.
- fork from a 20-back ancestor: 24.4 ms; branch basis `t` = the ancestor's.
- `d/branch-history`: 191 commits, 342 ms; commit records carry
  `:datahike/commit-id`, `:datahike/parents`, `:max-tx`.
- `sci.eval/cluster-ctx` over a fork: 1888 ms; an agent-authored function
  from the parent branch resolved and evaluated there.
- system-authored run for an existing agent: 19 datoms, `:resume` derived.
- `loop/turn` on the fork: 2 forms run, correct values, `:released`, then
  `:close` ⇒ closed, zero error receipts, no model call.
- identical `defn` re-install through `program-row-tx`: 42 datoms then 69
  (34 retractions), one row, stable entity id.
- adoption transplant onto the live branch: 34 datoms, one transaction,
  closed run, `next-agent-work` unaffected.
