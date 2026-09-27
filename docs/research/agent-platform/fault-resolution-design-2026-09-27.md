# Fault resolution design — 2026-09-27 (design only; Astra reviews before implementation)

Lane `fault-resolution-design-opus`, stability sweep finding 3
([stability-sweep-2026-09-27.md](stability-sweep-2026-09-27.md) §3, §10). Probes ran on `default`, pid 75447
(archive 9e61340b2), from 18:05Z to 18:21Z. They were read-only JVM-mode queries in the scratch namespace
`tmp.fault-res`. The scratch dumps live in the session scratchpad. The forms are in §9.

## What exists and its data flow

- `seon.fault/fault!` → `seon.error/record!`/`commit-call` upserts one `:seon.error/error` entity per
  signature. Each occurrence names its process as `:seon.error.occurrence/process`. The entity carries
  `:seon.error/declared-schema`, `:seon.error/operation` and `:seon.instrument/fn`.
- `seon.error/resolution` (`src/seon/error.clj:1309-1354`) says whether a fault is resolved:
  - by `:seon.error/resolved-tx` (`seon.problems/resolve!`) at or after the latest occurrence; or
  - when the `:seon.program/definition-digest` of `:seon.instrument/fn` changed after the latest occurrence.
- `seon.problems/error-signatures` attaches `:seon.error/resolution`. `open-faults` keeps the unresolved
  signatures.
- `seon.cluster/mcp-runtime-observation` (`cluster.clj:620-657`) turns open faults into
  `:seon.dev.mcp/health :faulted` and shows them.
- `opening` (`error.clj:1356`) uses the same `resolution` at the writer, which gives one notification per
  opening.

Principal approach of this design: keep every step of that flow. Change only the clauses of
`resolution`, as Datalog over facts that already exist, plus one declared schema property and one task
attribute. **A fault reports a condition, and a task reports a defect.** A fault is resolved when its
condition's scope ends (the process) or when the authority that owns the defect closes it (task
closure, or `resolve!`). A recurrence always re-opens it. A definition-digest change is neither
evidence nor authority, so it is deleted.

## 1. Answers to the questions

**What does "still true" mean for each class?**

| class | true while | derived from | exists today? |
|---|---|---|---|
| process | any occurrence's process is the store's live process | `:seon.error.occurrence/process` against the newest *booted* process row (§2) | Yes: memory pressure and a parked proc. Neither is declared as such yet (§5). |
| code (the default) | until root closes a task that names the fault, or calls `resolve!` | task history and `:seon.error/resolved-tx` | Yes: every other fault |
| data | the offending entity exists | an occurrence ref to that entity | **No fault among the 30 needs it.** Not added (AGENTS "Add no hypothetical restrictions"). It would be one optional occurrence ref plus one clause, added by accretion when a producer needs it. |

**Who declares the class?** The producer's error schema declares it, as the property
`:seon.error/scope :process` on the declared schema. The property is read with
`seon.schema.internal/entity-properties`, the same seam `commit-call` already uses for
`:seon.db/attributes` (`error.clj` ~1450). Absence means code scope. There is no hand list. **The
problem is that producers do not declare their schema today.**
- 18 of the 30 signatures carry the fallback `:seon.flow/exception-error`.
- `memory-pressure-crossed!` holds a declared `:seon.sci.kernel/memory-pressure-error` value, wraps it
  in `ex-info` and records it as `:seon.flow/exception-error` (`cluster/boot.clj:407-414`).
- `record-write-refusal-fault!` has no named schema (`turn.clj:5889-5903`).

So the sweep's proposal (a), a scope on the precise schemas, would today be read by **zero** existing
faults. The producer fixes in §5 are part of the cut.

**Is the failing operation already on the occurrence? No.** No stored field names the fix site, and
the probe refutes the sweep's proposal (b), "resolve by `:seon.error/operation`":

| field | what it holds on the 30 | examples |
|---|---|---|
| `:seon.error/operation` | The error value's own declared operation, else the observing boundary. For 7 flow faults it is `seon.cluster.agent/turn-step`. | Keyed on it, any `turn-step` edit would resolve all 7. The refusing operation (`record-tx`, `seon.db/transact!`) usually refused *correctly*; the defect is in its caller. |
| `:seon.instrument/fn` | The contract's function, else the first first-party frame of the outer throwable (`stack-failing-function`, `error.clj:~376`), which is the builder of the wrapper. | `seon.test/run` on 13 signatures; builders or reporters on 10 (`exception-value`, `record-write-refusal-fault!`, `turn-completion-backstop-failure` ×6, `memory-pressure-crossed!`, `seon.render/unknown`); `superv.async` on 2; plausible fix sites on only 5 |

Choosing a different field cannot make a digest rule sound. That a digest changed says only that
someone edited a function, not that the defect is fixed. **Delete the digest rule.** `:seon.instrument/fn`
stays for steward routing (`steward`, `for-agent`); it just stops deciding resolution.

**How does `resolve!` fit?** It stays unchanged as root's authority for a fix that has no task. It is
also the one-time cutover tool (Q3). Nothing new infers it.

**How does a fault link to its task?** The task's author declares the link in task EDN, as
`:seon.task/faults #{<64-char signature>}` on the entry. It is file-reconciled like every task member
(`issue.clj:617-651`, `tasks-tx`). The task row then carries value edges, not refs. Closing the task
(deleting the entry) retracts the row (`task-delta`, `issue.clj:634`), and that retraction is the
resolution.

Today this knowledge exists only as prose. The sweep wrote "(317111871b50, 3 occurrences …)" into
`:intent` strings (AGENTS "Return data, not comments"). Health then shows:
- **open, tasked**: `:seon.task/tasks #{handle …}` on the shown fault. This reuses the declared
  attribute, whose meaning is "the tasks it is subject to" (`seon.task.edn`).
- **open, untasked**: the key is absent, plus `:seon.dev.mcp/untasked-fault-count`.

The alternatives are Q1. The `:seon/tasks` value on the operation's defn is too coarse:
- `seon.test/run` carries `eb0fdb7e0ffd` (collection stop), which would mark all 13 `seon.test/run`
  faults as tasked.
- A defn annotation cannot say which of its tasks a fault belongs to, so closing a task cannot resolve
  the fault.

## 2. The rule

`(seon.error/resolution database signature)` keeps its arity and stays a pure function of the database
value. Callers are unchanged (`opening`, `error-signatures`, `transcript.clj:374`, `render-html`
`error.clj:1999`). `latest` is the existing max transaction of the occurrences' `last-at`. The first
matching clause wins:

```clojure
;; 1 resolve!      (max :seon.error/resolved-tx) >= latest
;;                 -> {:seon.error/resolved-by :seon.error/resolved-tx  :seon.error/resolved-tx tx}
;; 2 task closed   no current task row declares sig, and a task row that declared it was
;;                 retracted (its :seon.task/id retracted in the same tx) at tx >= latest
;;                 [:find ?h (max ?tx) :in $ ?sig
;;                  :where [?t :seon.task/faults ?sig ?tx false] [?t :seon.task/id ?h ?tx false]]  ; history
;;                 -> {:seon.error/resolved-by :seon.task/id  :seon.task/id h  :seon.error/resolved-tx tx}
;; 3 process ended (:seon.error/scope (entity-properties declared-schema)) = :process, and every
;;                 occurrence's process is a BOOTED process row older than the newest booted row
;;                 -> {:seon.error/resolved-by :seon.error.occurrence/process  :seon.error/resolved-tx latest}
;; else open, and error-signatures attaches :seon.task/tasks = current task rows declaring sig
```

**Liveness is a database fact, with no new input.**
- `ensure-cluster-entity!` (`cluster.clj:3273-3295`) asserts each process's row under the provenance
  `:seon.db/process [:seon.db.process/id "seon.db.process/boot"]`.
- On default, 69 rows carry that provenance. The newest is `75447-1790532049537`, which equals
  `(process-identity (current-identity))`. The query takes 1.8 ms.
- AGENTS says "One process root holds the store's lifetime lock", so an older booted process on this
  store is dead.
- The constants `seon.db.process/boot` and `seon.db.process/config` are not booted rows. An occurrence
  under one of them is **unknown**, and it keeps a process-scoped fault open ("never read unknown as
  dead").
- A store read offline treats its last boot as live, which errs toward open.
- The writer's `opening` needs nothing new. A process-scoped fault that recurs in a later process finds
  its earlier occurrences dead, opens once and notifies once per process life.

`notification-id`/`message-tx`/`opening` tokens become integers only. Every clause answers a
transaction.

## 3. Each fault under the old and new rule (30 signatures, probe at 18:17Z)

The columns are:
- **old**: `seon.error/resolution` today.
- **new, today's facts**: the rule in §2 with no task declaring faults and no producer fix yet.
- **after landing**: the task entries declare the signatures named in the sweep; producers declare
  process scope (§5); the cutover resolves the 11 digest-only signatures and the stale conditions
  recorded under the generic schema (Q3).

"proc live" means the fault has an occurrence in 75447. Three signatures recurred in the live JVM
*after* the sweep's 18:01Z snapshot:
- 93e4a1e2 at 18:04Z (root's test round);
- 477a97dc at 18:05Z;
- c2268b2f at 18:16:31Z. `mcp/stabfix-a` was parked by a deterministic deletion refusal.

| sig | operation / `:seon.instrument/fn` | last (Z) | proc live | old | new, today | after landing | right answer |
|---|---|---|---|---|---|---|---|
| 477a97dc | turn-step / superv.async | 09-27 18:05 | yes | open | open, untasked | open, tasked (item 4) | open: wrapped interrupt, live defect |
| 93e4a1e2 | record-tx / seon.test/run | 18:04 | yes | open | open, untasked | open, tasked 80aa14c43291 | open |
| c2268b2f | write-refusal-error / record-write-refusal-fault! | 18:16 | yes | open | open, untasked | old sig resolved at cutover; new occurrences are process-scoped and **open while 75447 lives** | open (stabfix-a is parked now) |
| 31711187 | turn-step / settle-batch-refusal! | 17:51 | no | open | open, untasked | open, tasked (item 1) | **open**: the throw path in `settle-batch-refusal!` still exists (`turn.clj:4166`). The sweep called it stale, but only its trigger was fixed. |
| b76fae17 | acquire-arm / memory-pressure-crossed! | 17:27 | no | open | open, untasked | resolved (cutover); new ones are process-scoped | resolved: the pressure died with its process |
| d2082881 | stop-work-launcher! / seon.test/run | 03:09 | no | open | open, untasked | open, tasked f12bf9eda5f1 | open: recurs at every restart |
| a0323a94 | exception-value (both) | 03:06 | no | open | open, untasked | open, tasked (item 5) | open until item 5 decides race versus loss |
| 504062b8 | seon.render/unknown (both) | 02:35 | no | open | open, untasked | resolved (cutover) | resolved: cause was memory pressure; OOM walk fixed (d1efccd3ab7e) |
| 721e82de | turn-completion-error / backstop | 02:08 | boot constant | open | open, untasked | resolved (cutover) | resolved: copy rule fixed |
| 67029fe7 | turn-step / superv.async | 02:08 | no | open | open, untasked | open, tasked (item 4) | open: same defect as 477a97dc |
| 8f6fdfc0, 7535402c | turn-step / install-function-contract! | 03:28, 02:21 | no | digest | open, untasked | resolved (cutover) | unknown; no recurrence since |
| fa60ad84 | turn-step / valid-candidate-value? | 09-26 17:13 | no | digest | open, untasked | resolved (cutover) | unknown; no recurrence |
| 2decd62d | transact! / seon.test/run | 09-26 20:46 | no | digest | open, untasked | resolved (cutover) | unknown |
| 017bd6fe, 28ce3d56, 4dfa157e, 104b2c82 | record-tx / seon.test/run | 23:17 to 01:56 | no | **digest (false)** | open, untasked | resolved (cutover); the live class is 93e4a1e2 | old answer false: recurred as 93e4a1e2 |
| 04197210, 1870b33b | stop-work-launcher! / seon.test/run | 00:19, 01:42 | no | **digest (false)** | open, untasked | resolved (cutover); the live class is d2082881 | old answer false |
| b559b883 | turn-step / settle-batch-refusal! | 09-26 22:24 | no | **digest (false)** | open, untasked | resolved (cutover); the live class is 31711187 | old answer false |
| bdbbae25, eb8254dc, e02d2dcc, e66a24b8 | transact! / seon.test/run | 09-26 | no | digest | resolved-tx | resolved-tx | resolved (`resolve!` recorded) |
| 05a4d415, d65f3d2c, a609c069, dc6a374f, a232ccb2 | turn-completion-error / backstop | 09-26/27 | boot constant | resolved-tx | resolved-tx | resolved-tx | resolved |

Counts:
- **old**: 10 open, 15 by digest (7 demonstrably false, because the same class recurred), 5 by
  `resolve!`.
- **new, today's facts**: 21 open (all untasked), 9 `resolve!`.
- **after landing**: 6 open, all tasked (477a97dc, 93e4a1e2, 31711187, d2082881, a0323a94, 67029fe7).
  The rest are resolved, and future memory-pressure and park faults are open only while their process
  lives.

The sweep said 3 record-tx signatures were falsely resolved. The data shows 4, plus 2 stop-work and 1
settle-batch. The "new, today's facts" column is why the cutover (Q3) is part of the landing, not a
follow-up.

## 4. What is deleted and added (estimate)

| deleted | lines |
|---|---|
| `resolution`: the `current` query, the `then` history query over definition digests, and the digest branch (`error.clj:1326-1343`, `:1350-1352`) | −22 |
| `opening` digest token; `:seon.program/definition-digest` alternatives in the `opening`, `notification-id` and `message-tx` contracts (`error.clj:1362,1372,1380,1390`) | −4 |
| digest branches of `resolution-text` (`problems.clj:477-478`) and `render-html` (`error.clj:2002`) | −3 |
| schema: `:seon.error/digest-then`, `:digest-now`, `:seon.instrument/fn` in `:seon.error/resolution`, `:resolved-by` value `:seon.program/definition-digest` | −4 |
| `:seon.error/issue` and `:seon.error/regressions`: declared on `:error` and `:fact` (`seon.error.edn:139-140,219-220,298-300`) and restored in `problems.clj:108-111`. They are **never written**: 0 facts on default and no writer in `src`. | −10 |
| test `a-fault-resolves-when-its-function-changes-and-reopens-on-recurrence` (`problems_test.clj:373-418`), replaced by regression 2 | −46 |

| added | lines |
|---|---|
| `resolution` clauses 2–3: booted-process query, scope read, task-closure history query | +16 |
| `error-signatures`: one query attaching `:seon.task/tasks` to open signatures | +4 |
| schema: `:seon.error/scope [:enum :process]`; `:resolved-by` gains `:seon.task/id` and `:seon.error.occurrence/process`; `:seon.task/faults [:set [:string {:min 64 :max 64}]]` (not `:seon.error/signature`, which carries `:seon.db/identity`) on `:entry`, `:declaration` and `:task` | +5 |
| `mcp-runtime-observation`: `:seon.task/tasks` per shown fault, and `untasked-fault-count` | +3 |
| producers (§5): memory-pressure records its declared value; a named park schema with `:seon.error/scope :process` | +8 |
| regressions 1, 3, 4, 7 (§7) | +~55 |

Net: about −43 source and resource lines against +36, and test −46/+55. No new store, surface,
function family or writer. `resolve!` and `open-faults` are unchanged.

## 5. Producer fixes the rule needs (owners elsewhere)

1. `memory-pressure-crossed!` (`cluster/boot.clj:407-414`) should record `refusal` as the source with
   `:seon.error/declared-schema :seon.sci.kernel/memory-pressure-error`. That schema gains
   `:seon.error/scope :process`. This adds the evidence line for task d287c254b094. Its defn also lacks
   `:seon/tasks #{"d287c254b094"}` (sweep §10).
2. `record-write-refusal-fault!` (`turn.clj:5889-5903`) should pass a named
   `:seon.turn.loop/write-refusals-exhausted-error`, declared with `:seon.error/scope :process`, in its
   context. This belongs with the item 2 task (turn.clj holder).

Both change their signatures, so the old ones (b76fae17, c2268b2f) are closed by the cutover
`resolve!`.

A park is really per agent graph, not per process: retiring `stabfix-a` ends it while 75447 lives. The
process scope over-approximates it by keeping the fault open until the JVM ends. That is conservative,
and item 2's fix removes the long-lived park anyway.

## 6. Cost (measured on 75447, warm)

| operation | now | new |
|---|---|---|
| `resolution` for all 30 signatures | 30.9 ms (per-signature `resolution`) | 0.93 ms (batched probe, 20-run average; 10.4 ms cold) |
| booted-process query | — | 1.8 ms |
| `problems/problems` | 673 ms first call, 224 ms warm | unchanged except about −12 ms |
| of which `error-signatures` / `open-faults` | 12.1 / 12.5 ms | about 3 ms / 0 (the working-tree `open-faults` already reuses the attached resolution) |
| `mcp-runtime-observation "default"` | 230 ms warm | about 218 ms |

The brief's 1.6 s was not reproduced warm. Faults are about 25 ms of `problems`. Most of the rest is
`deferred-agents` (120–213 ms, per-agent derivations) and `failed-tests` (83 ms). That belongs to a
perf lane, not this rule.

The shared implementation should keep one `resolution` per signature. At the reader it is batched by
binding `[?sig ...]` in the same queries, so there is no second function family.

## 7. Regressions (focused list for root)

1. `a-process-scoped-fault-closes-with-its-process`. A fault under a schema declaring
   `:seon.error/scope :process` is open while its occurrence's process is the newest booted row. It is
   resolved (`:seon.error.occurrence/process`) once a later booted row exists. A recurrence under the
   later process re-opens it and delivers exactly one new notification.
2. `a-changed-definition-does-not-resolve-a-fault` (inverts `problems_test.clj:373`). Changing the
   `:seon.instrument/fn` digest leaves the fault open.
3. `closing-its-task-resolves-a-fault`. A task entry declaring `:seon.task/faults #{sig}` shows the fault
   open with `:seon.task/tasks #{handle}`. Deleting the entry through `tasks-tx` resolves it
   (`:seon.task/id`). Editing the entry to drop the signature *without* closing the task leaves it open
   and untasked. A recurrence after closure is open and untasked.
4. `an-unknown-process-keeps-a-process-scoped-fault-open`. An occurrence under `seon.db.process/boot`
   does not resolve by process.
5. `a-resolved-fault-stays-closed-until-it-recurs` (`problems_test.clj:353`) and
   `a-fault-is-delivered-and-shown-open-once-per-opening` (`error/branch_test.clj:83`) are unchanged and
   must stay green.
6. `mcp_test.clj:~298` (the `resolve!` status case) adds `:seon.dev.mcp/untasked-fault-count`.
7. Task reconciliation: an entry with `:seon.task/faults` round-trips through `tasks-tx` (revision
   compare included).

## 8. Owner questions (priced)

**Q1. Where does the fault → task link live?**
- **(a) Recommended:** the task EDN entry declares `:seon.task/faults #{sig}`. This takes 3 schema lines
  and reuses file reconciliation. The link is reviewed in git with the task and survives data resets,
  because signatures are deterministic. Closing the task resolves the fault. What it gives up: a task
  must name each sibling signature (see finding 5).
- (b) Root writes `:seon.task/tasks` onto the error entity with a `link!` beside `resolve!` (+~8 lines).
  The link is lost with the data.
- (c) Derive the link from `:seon/tasks` on the operation's defn. This needs no new data, but it is
  imprecise (`seon.test/run` would mark 13 faults as tasked by an unrelated task) and it cannot resolve.

**Q2. What does health say when every open fault is tasked?**
- **(a) Recommended, 0 extra lines:** `:faulted` whenever any fault is open, plus
  `untasked-fault-count` and per-fault tasks. The value stays true; the reader sees whether anything
  is untriaged.
- (b) `:faulted` only for untasked or live-process faults. Tasked faults from dead processes read
  `:observed` with `:known-fault-count` (+~6 lines). This is quieter, but health would then read green
  over known live defects.

**Q3. Cutover of today's facts.**
- **(a) Recommended:** one `resolve!` pass at landing, recorded in the landing note. It covers the 11
  digest-only signatures plus b76fae17, c2268b2f, 504062b8 and 721e82de: 15 transactions, under 1 s.
  It keeps every answer the old rule gave that was not false, and the false ones already have live
  successors.
- (b) Let 21 faults re-open as untasked and triage them by hand. About an hour of root time, with
  noisy health meanwhile.
- (c) Reset the data (AGENTS/MEMORY "DB data is disposable"). This loses the fault evidence the tasks
  cite.

## 9. Findings from this lane's probes (surprises, for task EDN by root)

1. **An agent's own refused write became a core fault, and its proc parked.** At 18:16:31Z on HEAD,
   `mcp/stabfix-a` hit "Program deletion leaves surviving referrers" three times. This parked its turn
   proc and recorded c2268b2f. That is sweep item 2 reproduced live. It is also a deterministic
   refusal by the agent itself, which problems' own docstring says "is NOT a core fault".
2. **The floor printer renders plain integers as entity refs.** `(frequencies …)` returned
   `{:seon.error/resolved-tx 9 …}` and printed as
   `{:seon.error/resolved-tx [:db/ident :datahike.read/revision]}`: the key names a ref attribute, so
   the count 9 was resolved as an entity id. This is wrong output, not only ugly.
3. **The floor printer shows an empty relation result as opaque.** `seon.db/q` returns
   `datahike.java.QueryResult` (a `java.util.Set`, `set?` true, count 0) for an empty relation `:find`.
   The printer shows it as `#:seon.sci.admit{:opaque "datahike.java.QueryResult"}` instead of `#{}`.
4. **A small result was windowed and cut inside tuples.** A 5-element vector of small maps (8 KB) was
   windowed into a blob, with elisions inside `:seon.error/frame` tuples.
5. **Signature churn: one defect class, five signatures.** The five record-tx signatures (017bd6fe,
   28ce3d56, 4dfa157e, 104b2c82, 93e4a1e2) have the same layer, operation, declared schema, exception
   class, `frame-site`, and message. The input that differs was not identified; candidates are the
   location path and expected-key/shape in `signature` (`error.clj:159`). Every per-signature link
   inherits this churn.
6. **6 backstop signatures carry a non-process as their process.** They are recorded under the
   constant `seon.db.process/boot`, which names no process. §2 treats it as unknown.
7. **The first probe form timed out at 30 s** (`ns` + `require` + a 30-row pull) with no result. The
   defs it made were bound when checked seconds later. The same pull then took 2.5 ms. The cause is
   unexplained; it may be contention during the collection or test round (sweep §8).
8. `problems.clj` has an **uncommitted working-tree edit by another holder**: `open-faults` no longer
   recomputes, and there is a new `seon.cluster.wake` require. Implementation of this design waits for
   that file's owner. HEAD is 152ceb62c, and the JVM runs 9e61340b2.

## 10. Evidence and limits

- Scratch namespace `tmp.fault-res` on pid 75447. The central forms:
  - `(db/q '[:find [(pull ?e [...]) ...] :where [?e :seon.error/signature]] d)`, 30 rows in 2.5 ms;
  - `(seon.error/resolution d sig)` for each signature, 30.9 ms total;
  - the batched `new-resolutions` probe, which implements §2 with a stand-in task map;
  - the booted-process query
    `[:find ?id ?tx :where [?b :seon.db.process/id "seon.db.process/boot"] [?tx :seon.db/process ?b] [?p :seon.db.process/id ?id ?tx]]`;
  - `seon.problems/problems` and each family, timed.
- **Not proven:**
  - the task-closure clause, because `:seon.task/faults` does not exist yet. It was simulated with a
    map. The history query on `:seon.task/id` retractions takes 1.6 ms over 7 closed tasks.
  - the process clause on real process-scoped schemas, because no producer declares one yet. It was
    simulated for b76fae17 and c2268b2f.
  - multi-JVM stores. The rule relies on the one-lock-holder law. **Astra should check that no second
    live JVM (for example an isolated test JVM) ever writes occurrences into a store it does not boot.**
- No source, test or resource edits. Nothing was written to any database.
