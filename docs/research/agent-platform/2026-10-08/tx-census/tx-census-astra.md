---
title: Transaction census — independent Astra measurement
date: 2026-10-08
lane: tx-census-astra
---

# Transaction census — independent Astra measurement

2026-10-08; lane `tx-census-astra`. Research only; no product changes. The other
transaction-census lane's document was not read.

## Findings and limits first

Ten canonical seven-attribute task rows, written separately, took **179.850 ms**
and touched **744,161 bytes** in ten durable commits. The same shape of ten rows
in one transaction took **22.861 ms**, **94,893 bytes**, one commit. This is
7.9 times less elapsed time and 7.8 times fewer touched bytes. Combining work
that has the same durability boundary is the first change to make.

An ordinary SCI request already commits once. The measured model request has
**seven commits including message ingress: six after ingress**, not seven
removable machinery commits. The executed one-test proof request already has
**two durable commits**; its third attempted transaction is an in-memory overlay.
The current ledger correctly retains admission in EN-6. The older entrants
design's one-commit target must not override that ruling.

Adding two small transaction attributes did not show a warm latency penalty in
this small sample. Metadata is not free: it adds datoms, indexes and validation.
A separate metadata-only transaction has the full commit cost. A two-datom commit
in this store wrote **39,956 bytes**, of which **10,269** were separate index nodes
and **29,687** the commit record and mutable branch head. The historical 200 KB
observation is not a constant per transaction.

Evidence limits and defects:

- Actual default was healthy enough for read probes but reported six recorded
  failures and twelve over-bound operations at intake. Its loaded source was
  `75aa6990b229bac568cf83d885588f1bcf43f5ef`, not checkout HEAD. No prototype was
  loaded into that JVM. Its readiness was 18,174 ms; live heap about 965 MB,
  weighted cache about 308 MB, store inventory 166,693 files.
- All destructive and write measurements below ran through `seon.pass-probe` in
  this lane's throwaway root. The first process loaded `754e20ff6`, completed
  readiness in 85,396 ms, then disappeared after its launcher ended. No retained
  exception established the cause. The replacement loaded
  **`4707b95461f79c1a28900dbd5a8a186b67b1514b`**, reached readiness in 12,102 ms,
  and stayed attached to its owned shell. Tables below come from that process.
- Restart publication left the original cluster's adopted source behind the
  loaded source. Proof results named `:seon.test/loaded-source-drift`. Both direct
  proof repetitions executed and passed; neither is evidence of cached reuse.
- Authoring a new proof for a merge hit definition-digest refusal: expected
  `259887b082391d9387613cc4b2eb780afc4e86de1386a9e3928bf20111cc4a84`, observed
  `c7bee41b5fe446dfd8995dc861c007456d046be0439eae35a414bb7b11a681aa`.
  The private Var existed but its program facts were refused. A successful
  proposal was measured with an existing task and proof instead. It had no
  authored program change; this tests installed request machinery, not merge
  acceptance. OD-Q12's future no-change completion should avoid that request.
- `seon.pass-probe/storage` counts a copied branch head as a durable commit.
  Branch creation actually creates **zero new commit identities**. Tables
  correct that count using store events and transaction history. This belongs
  with **SR-GROW**, not another measurement system.
- These are operation elapsed times, not individual transaction latency samples.
  Transaction attribution below is from `d/listen` and bounded history. Stored
  `:seon.db.transaction/ms` measures preceding work, **not write latency**.
  Cold process boot lacks a full store-hook window; only warm cluster boot has
  complete attempted/commit/byte measurements. Periodic maintenance was not
  forced; the observed background effect and idle Flow window are reported.

## Method and pinned authorities

Datahike checkout and submodule pin were both
**`86c3301a00de8c49b2690083b2b1e69677bf0c8d`**. Every Datahike citation below is
relative to `reference-code/datahike/` at that revision. Seon source citations
refer to the committed `4707b9546` archive, not the dirty shared tree. Research
read the data-oriented Clojure, REPL, Datahike, testing and modeling skills,
agent-loop and merge specifications, and their callers. Skill revision claims
are not installation evidence; the current pin and runtime were checked directly.

| Authority | Guarantee and inputs | When work repeats; cost |
|---|---|---|
| `src/datahike/db/transaction.cljc:904` (`flush-tx-meta`), `:1250` | `:tx-meta` becomes add datoms on the transaction entity; default `:db/txInstant`; metadata is processed before submitted data | Each transaction; proportional to metadata attributes, then normal validation/index work |
| `src/datahike/db/transaction.cljc:1268` | Automatic metadata datoms are included only with `:keep-history?`; explicit `:db/current-tx` data is normal transaction data | Each transaction; configuration is part of the guarantee |
| `src/datahike/core.cljc:126` (`with`) | Produces a tx-report from an immutable database, without committing it | Used for schema-order probes; no durable write |
| `src/datahike/writer.cljc:229` | Drains pending reports and commits the last database once, then answers reports | Concurrent queued transactions may share a commit; sequential synchronous callers cannot depend on that |
| `src/datahike/writer.cljc:426` | Transaction callback and listeners receive the committed report | Listener delivery follows result delivery; a snapshot taken too early can miss callbacks |
| `src/datahike/writing.cljc:74` (`db->stored`) | Flushes EAVT, AEVT, AVET and temporal indexes, content-addresses schema metadata, includes fused roots | Each commit; changed index paths plus commit/head serialization, not the logical datom count alone |
| `src/datahike/writing.cljc:462` (`commit!`) | Writes pending objects, commit and branch head with the dependency's durability ordering | Each new commit; Seon must not implement a second writer |
| `src/datahike/versioning.cljc:212` (`branch!`) | A new branch points at existing immutable indexes/commit | Two store writes in our branch sample; no transaction or new commit |
| `src/datahike/versioning.cljc:738` (`merge!`) | Merge transacts onto target history | New transaction identity; source transaction identity is not portable provenance |
| `src/datahike/gc.cljc:22`, `:59`; `src/datahike/index/persistent_set.cljc:172` | `with-storage`, `-seed-root!`, `-mark` identify reachable index objects, including fused roots | Post-measurement attribution only; walks reachable nodes, not charged to the tiny write |
| Seon `src/seon/db.clj:3570` (`stamp-transaction`), `:5650` | Writer adds operation, optional historical agent and current instrumentation; schema declarations precede the explicit transaction entity | Once per write; already the correct owner for minimal provenance |
| Seon `test/seon/pass_probe.clj:99`, `:131`, `:183`, `:204` | Store hooks, profile delta, real MCP entry and fixture model entry | One bounded operation per window; existing counter needs the branch-copy correction |
| Seon `src/seon/cluster/store.clj:155` | File store uses LZ4, persistent-set indexes, configured page sizes and fused roots | Byte figures are for this configuration, not a portable constant |

Instrumentation: register `d/listen` on home and the known agent connection;
collect full reports, then reduce their logical datoms after the timed window.
Register a konserve write hook on the shared store, using the probe's existing
event decoder. Unknown connections (new agent/checkpoint/boot branch) are
attributed afterward from `d/history`, bounded by observed transaction ids and
timestamps. A transaction id is branch-local: always retain its branch and
commit. A generic operation stamp is augmented by the changed attributes and
the verified first-party caller, not treated as an exact stack trace.

Attempt counts are profiled calls to `seon.db/transact-call`, including overlay
writes. Commit counts are distinct newly written commit records, excluding
copied branch heads. Store bytes are **final file lengths for distinct touched
keys within the window**, including mutable branch heads once. They are neither
filesystem allocated blocks, write-system-call traffic, nor retained growth
after collection. All completed samples had zero missing store files and zero
blob bytes. Allocations are JVM-wide deltas, can include background threads,
and are not retained heap. Negative unattributed-byte readings are measurement
noise in the probe's thread/JVM sampling; no ownership inference uses them.

## Census

KB/MB below are decimal. `+ head` means the probe also counted a copied branch
head which is excluded from the new-commit column. Task/proof/error rows invoke
the owner directly with explicit custody; they exclude an enclosing MCP recording.

| Ordinary operation | Attempts | New durable commits | Elapsed ms | Touched bytes | Allocated MB |
|---|---:|---:|---:|---:|---:|
| Agent creation + first SCI `(+ 1 2)` | 3 | 3 + head | 437.204 | 213,922 | 168.8 |
| SCI `(+ 1 2)`, repetition 1 | 1 | 1 | 126.058 | 56,276 | 47.9 |
| SCI `(+ 1 2)`, repetition 2 | 1 | 1 | 82.679 | 82,792 | 32.3 |
| JVM `(+ 1 2)` | 1 | 1 | 70.760 | 84,062 | 25.2 |
| SCI contracted `plus-one` definition, two forms | 1 | 1 | 667.173 | 182,711 | 346.1 |
| SCI `(plus-one 41)` | 1 | 1 | 228.400 | 104,539 | 128.5 |
| Fixture model request 1, including incoming message | 7 | 7 | 778.836 | 496,408 | 561.7 |
| Fixture model request 2, including incoming message | 7 | 7 | 548.131 | 593,232 | 538.4 |
| First MCP one-test check | 8 | 7 + head | 5,093.189 | 556,520 | 1,727.7 |
| Repeated MCP one-test check | 4 | 3 | 2,948.260 | 388,289 | 497.8 |
| Direct executed one-test proof 1 | 3 | 2 | 382.320 | 178,356 | 381.7 |
| Direct executed one-test proof 2 | 3 | 2 | 300.742 | 241,252 | 381.0 |
| Task create | 1 | 1 | 34.951 | 41,635 | 10.6 |
| Task assign | 1 | 1 | 24.860 | 54,786 | 7.4 |
| Task revise, with current declaration basis | 1 | 1 | 33.920 | 37,391 | 18.8 |
| Branch create | 0 | 0 + head | 16.464 | 14,682 | 3.0 |
| Successful merge proposal, existing task/proof | 7 | 5 | 3,026.563 | 714,128 | 3,325.4 |
| Record one error, origin + home | 2 | 2 | 100.818 | 193,882 | 46.6 |
| Repeat same error signature, origin + home | 2 | 2 | 51.365 | 118,344 | 105.1 |
| Background `printf`, including request and actual exit | 3 | 3 | 249.433 | 472,602 | 470.1 |
| New cluster boot inside running throwaway process | 6 | 6 + head | 3,531.764 | 288,788 | 2,128.0 |
| Deliberate 1-second idle Flow observation | 0 | 0 | 1,005.099 | 0 | 0.016 |

Provider time was 11.951 and 0.479 ms respectively; one fixture response, one
evaluated form, closed call each time. No paid provider ran. The fixture has no
usage payload: this does not census usage-specific writes or provider retries.
The check used `seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol`;
direct proof used `seon.id-test/a-host-object-has-no-identity`, executed once and
passed each repetition. Proof body time was 25.6/19.6 ms, acquisition 20.8/59.5 ms,
cleanup below 1 ms. Most elapsed time is outside that tiny body.

### Transaction attribution

Here `t=945` abbreviates `536870945`. Counts are **logical datoms** unless
explicitly marked physical. Operation names cite installed legacy identifiers;
they are not proposed vocabulary.

- Agent creation: one `seon.cluster.host/home-call` on home and one on the agent
  branch (home t934: 39 logical; branch t934: 40 physical), then first evaluation
  t935: 27 physical. Branch creation copies an existing head between these steps.
- SCI t936/t937: 27 each, `seon.db/absent-call`; JVM t938: 19,
  `seon.turn/record-evaluated-call`. JVM's stamp lacks agent despite the MCP agent
  argument. The actual agent is known at entry; custody and provenance must be
  distinct explicit values. Defn t939: 386; following call t940: 28. About 307
  defn datoms are measurement attributes, already riding the single transaction.
- First check on `agent-bac09c06ef88`: t941 admission 68, t942
  `seon.test.runner/record-tx` 56, t943 enclosing evaluation 831. Checkpoint
  `checkpoint-default-c25a2fc47fb07663ff0c5605` adds four transactions:
  t922 config 120 physical, t923 process 5, t924 cluster/toolkit 18, t925 source
  commit 4. Its old t921 is inherited, not another new transaction. The eighth
  attempt is the overlay. Repetition: t944 admission 34, t945 result 46,
  t946 enclosing evaluation 269, plus overlay attempt. See
  `src/seon/test.clj:1773` (`prepare-checkpoint!`), `:1845`, `:2236` and
  `src/seon/test/runner.clj:1770` through `:2023`.
- Direct proof: t949 admission 18, t950 completion 10; then t951 admission 18,
  t952 completion 10. One overlay attempt each. EN-6's two-transaction target is
  already reached for this single-member common path. Do not promise 3→2 durable
  commits by confusing an overlay with storage or dropping the enclosing MCP row.
- Task create/assign/revise on home t943/t944/t945: 9/7/5 logical datoms.
  Create and revise use `seon.task/adopt-tasks-tx`; assign's stamp is generic.
  Revision already advances related assignment data in the same transaction:
  `src/seon/task.clj:421`, `:435`, `:453`, `:481`.
- Merge proposal on `agent-be25dc69a200`: t957 proof admission 73, t958 proof
  result 12, t959 enclosing evaluation **2,476**. Home t957 creates proposal
  and merge task, 18; t958 separately sends root's message, 9. Two further
  attempts are overlays, not durable writes. The proof was
  `seon.repl-test/a-rendered-value-with-cuts-is-spliced-verbatim`. The successful
  result named proposal `da0849e1674d`, merge `2cce7ea26a14`, source commit
  `6ac73953-2145-5200-8bf9-e84138fb451b`. See `src/seon/task.clj:669` and
  `src/my/program.clj`'s `propose-merge!` caller. The home request and notification
  are a concrete mergeable pair; combining across two different branch writers
  is not the same operation.
- Error signature `9927b…`: origin and home each write `seon.error/commit-call`,
  33 logical datoms each first occurrence, 20 each repeat. `src/seon/fault.clj:138`
  routes two recordings; the repeated signature alone did not suppress the
  occurrence. Do not claim a successful task association from this generic fixture.
- Background: t955 `seon.effect/open-call`, 28; t956 evaluation recording, 521;
  t957 `seon.effect/settle-call`, 10. The observation awaited its stored
  `:seon.effect/settled-at`, not just the request return. Admission and actual exit
  straddle an external effect; they cannot be collapsed by delaying admission.
  See `src/seon/effect.clj:227`, `:280`, `:628`, `:647`.

Model sequence on `agent-f07d24ff70d0`:

| Order | First/repeat t | Logical datoms | Caller and stored facts | Treatment |
|---|---|---:|---|---|
| 1 | 945/952 | 6/6 | `seon.cluster.message/inbound-tx`; incoming message | Keep durable before wake; outside the one model-evaluation transaction |
| 2 | 946/953 | 188/112 | `system-turn`, generic stamp; refresh evaluations | Retire system recording with the accepted request design |
| 3 | 947/954 | 9/9 | `open-call`; open/trigger/runtime pointers | Hold request data; write with final evaluated values |
| 4 | 948/955 | 50/68 | context capture, prompt and contributions | Delete capture; retain input basis needed by readers |
| 5 | 949/956 | 12/12 | `record-attempt!`; model, endpoint, settings copy, finish | Carry observed attempt facts into final transaction |
| 6 | 950/957 | 13/13 | `plan-call`; reply and evaluation skeleton | Carry into final transaction |
| 7 | 951/958 | 14/14 | `receipt-settle-batch-call`; values/timing/answered/closed | One final transaction owns completed request |

Source: `src/seon/turn.clj:2051`, `:3542`, `:4134`, `:4353`, `:4430` through
`:4690`. This sample supports **6→1 after ingress**, or **7→2 including ingress**,
for a successful model request without durable user effects. User effects retain
their own commits. Removing bookkeeping does not roll back an effect already
durable before the request's recording.

Warm new-cluster boot (`cluster-tx-census-boot`), six new transactions:

| t | Physical datoms | Facts/caller |
|---|---:|---|
| 925 | 84 | Fork cluster configuration and source pointer; generic writer |
| 926 | 5 | `seon.config/reconcile-call`, overlay keys and no-provider setting |
| 927 | 4 | Process identity + provenance; generic writer |
| 928 | 15 | Instructions and toolkit, generic writer |
| 929 | 51 | `seon.cluster/ensure-entity-call`, root agent plus bootstrap message |
| 930 | 24 | `seon.schedule/root-maintenance-seed-call`, three schedules |

Readiness milestones in ms: store 31, source 343, branch 658, connection 661,
config 1178, context 1753, graph 3523, served 3530. No missing layer. Config,
process and cluster facts can be composed before graph construction; graph start
must still follow durability. The owners are `src/seon/cluster.clj:3079` (`seed-cluster-facts!`) and
`src/seon/schedule.clj:64`. Initial source publication's inherited history has
t913–921; t917 contains 525,190 physical datoms and t920 task publication 4,796.
These are not nine commits caused by the later warm cluster start.

The idle window saw no timer writes. This is not proof that schedules never
write: `src/seon/schedule.clj:420`, `:559` record completed scheduled work.
The background row above measures the actual Flow completion writer.

## Tiny transactions and index cost

Direct `seon.db/transact!` on the lane's branch, with empty submitted data:

| Sample | Logical datoms | ms | Touched bytes | Allocated bytes |
|---|---:|---:|---:|---:|
| operation + txInstant 1 | 2 | 30.859 | 40,608 | 5,052,824 |
| plus agent/process strings 1 | 4 | 16.974 | 53,951 | 6,063,816 |
| operation + txInstant 2 | 2 | 16.487 | 40,535 | 4,925,664 |
| plus agent/process strings 2 | 4 | 17.170 | 42,519 | 5,893,672 |
| operation + txInstant 3 | 2 | 16.316 | 52,691 | 5,863,440 |
| plus agent/process strings 3 | 4 | 15.707 | 40,423 | 5,036,592 |

All six succeeded, one attempted and one durable transaction each. Two scalar
fields were chosen from installed schemas; this does not propose restoring the
unused process string in production. Alternating samples reduce ordering bias
but are too few to establish statistical equivalence. Four attributes do not
necessarily touch more pages than two at a particular tree position.

The task comparison used the canonical private `seon.task/task-entity` producer,
then the checked writer. Distinct names, otherwise the same seven-attribute
shape. Ten separate writes had nine logical datoms each: 70 task datoms plus 20
transaction datoms. Together: 72, saving eighteen metadata datoms as well as
nine commits. It does not justify combining ten unrelated requests that already
promised individual durable replies.

Per-index attribution of a subsequent two-datom commit, using Datahike's own
marking functions after measurement:

| Stored objects newly touched | Files | Bytes |
|---|---:|---:|
| EAVT separate nodes | 0 | 0 |
| AEVT separate nodes | 2 | 7,389 |
| AVET separate nodes | 1 | 2,880 |
| Temporal EAVT/AEVT/AVET separate nodes | 0 | 0 |
| New commit record, including fused roots | 1 | 14,850 |
| Mutable branch head, including fused roots | 1 | 14,837 |
| Total | 5 | 39,956 |

Commit `6ac73acd-eb0d-5a3d-990f-55b17851ed3a`, branch
`agent-bac09c06ef88`, t979. Wall 53.130 ms; allocation 18,646,576 bytes.
This sample followed speculative schema probes and had 18 ms inclusive
`carried-projection` time; it is not the warm 16-ms sample above. A zero in this
table means no separate written node for that index, **not no index update**:
fused roots are charged to the commit/head records. No schema metadata object was
written. SR-GROW should report separate nodes and embedded roots separately.

## Minimal transaction metadata design

Existing flow: checked writer → Datahike tx-report → immutable indexes and branch
head. Keep that flow. Supply a small immutable map at the actual entrance, pass it
explicitly to the writer, and append its `:db/current-tx` data alongside the work.
There is no new service, queue, event entity or provenance store. The smallest
alternative is the existing `stamp-transaction` owner with its obsolete refs and
profile expansion removed.

This is a proposal for schema members, **not an installed API**:

| Fact | Value and consumer | Availability |
|---|---|---|
| `:db/txInstant` | Datahike instant; history/time query | Every transaction, dependency-owned |
| existing `:seon.db.transaction/operation` | Actual qualified writer/transaction function symbol; diagnosis | Every Seon transaction; improve generic call sites where a real named writer exists |
| existing `:seon.db.transaction/agent` | Observed agent id string; historical attribution | Present for an agent's work, including JVM entrance; no invented agent for boot |
| proposed `:seon.db.transaction/request` | Owner-minted evaluation request id value; effect and evaluation joins | Every write during that request; absent for a writer not entered by an evaluation |
| proposed `:seon.db.transaction/task` | Observed qualified task name, not a living ref; task history | When the requesting agent has a task; do not invent one for an operator operation |
| proposed `:seon.db.transaction/source-commit` | Loaded program's source commit UUID; reproducibility | Once source acquisition succeeded; absent during earlier store/schema acquisition |
| entrant | The entrance's actual qualified function symbol, obtained from request data | Prefer deriving from the recorded request by request id; if needed before recording, a proposed `:seon.db.transaction/entrant` scalar holds that symbol |

The owner's requested fields are an explicit provenance contract, not mandatory
fictional values. Absence means no such subject existed at that entrance. A
missing required value *during an actual evaluation* is a typed diagnostic, not
a fallback to another agent or default cluster. Do not introduce an enum of
origins or a new agent kind: current ledger MA-12 expressly uses ordinary root
evaluation. The entrant is a real function identity or a join, not another tag.

Distinguish **loaded program source commit** from **input database commit**.
The latter is already held by the writer's immutable before value/parents and
the request's read evidence; do not label it as source. The output commit cannot
be inserted into its own hashed input: Datahike adds commit identity to the
report after committing. Use the native report/commit relationship to find it.

Contract the final map with Malli `m/validator`/`m/explain` through the existing
schema population; use named string/symbol/UUID schemas. Request is non-unique:
several effect transactions may share it. A unique request attribute on tx
entities would upsert different transactions together. Index request and agent
only where query consumers need lookup; pull remaining fields by transaction
entity. Task/source indexes require an actual reverse-query consumer, not
automatic indexing of all metadata. No nils, prompt copy, settings EDN, stacks,
per-function profile rows or new timing component trees in this map.

Reader joins, expressed with proposed names:

```clojure
;; Attribute assertion -> writing request. Use history for retracted assertions.
[:find ?entity ?value ?request
 :where [?entity :domain/attribute ?value ?tx]
        [?tx :seon.db.transaction/request ?request]]

;; Request -> all its transactions, including effects before final recording.
[:find [?tx ...] :in $ ?request
 :where [?tx :seon.db.transaction/request ?request]]
```

The reader then pulls provenance from the transaction entity and resolves a
recorded request **if one exists**. A crash before request recording leaves a
meaningful request id on durable effects without a dangling ref. Current values
give latest assertions, not every earlier change; `d/history` supplies earlier
assertions and retractions under the retained history policy. Queries over many
facts should join once per distinct transaction, not pull its metadata per datom.
Common write work is proportional to changed data plus a bounded number of
provenance attributes. Index traversal remains Datahike's job.

Delete `:seon.db/receipt`, `*receipt*`, `*open*` and skeleton records whose only
purpose is to satisfy those refs (RP-14/EN-1). Move transaction-level attribution
off ordinary entity copies when no independent reader needs the copy. Remove
persisted per-function measurement entities through S5-02; they currently
inflate an already necessary transaction and do **not** justify claiming an
extra commit saved. Delete context captures and system refresh recordings via
the already accepted request/context cuts. Keep real evaluations, tasks, test
results, messages, effects and provider attempts when they have independent
consumers. Multiple evaluations or attempts in one transaction cannot become
single scalar tx attributes without losing information. Per-form duration and
value belong on that evaluation; transaction metadata is not a replacement.

### Proven behavior and risks

The speculative schema probe used the branch's real immutable database with
`d/with`. Defining `:tx.census/request` and using it in that operation's `:tx-meta`
returned `{:error :transact/schema :attribute :tx.census/request}`. Declaring the
attribute, then appending `{:db/id :db/current-tx :tx.census/request "fixture"}`
returned five datoms and the pulled value. Installing first, then using `:tx-meta`
returned two datoms and the same value. None of these speculative reports was
committed. Seon's existing appended stamp already handles this ordering.

Adoption must publish schema and writer consumers together on a branch of a live
store. During the first schema-installing write use explicit tx-entity data after
declarations; later `:tx-meta` is supported. With history disabled, automatic
metadata datoms are omitted by this Datahike revision: retain the explicit form
if current transaction provenance must survive that configuration. A dedicated
configuration proof is required; this lane verified the code and the installed
history-enabled database, not the history-disabled runtime.

Merge re-transacts program changes at new transaction entities. Target provenance
describes the **merge writer**, not the source evaluation. MA-11 drops
`:seon.program/defined-in`; do not repair it with a dangling source evaluation
ref. The merge request's source commit plus declaration identity/digest provides
source evidence while retained. If long-lived origin attribution is required,
declare that historical value on the merge data expressly. Do not copy every
source tx entity into target program data. Collection can remove old commits;
history, audit retention and provenance lifetime must agree.

Two branches each recording an error cannot be made one atomic transaction by
concatenating data. Existing error-owner changes must decide which home owns the
durable occurrence and how origin delivery references it. Similarly, background
admission, timeout and actual exit retain their separate correctness boundaries.
Small datom count alone does not establish waste.

## Ranked work folded into existing ledger rows

This is evidence for the owner's ledger, not a new issue index. New proof names
below are the ledger's targets or explicit proposed extensions, not installed
tests claimed to exist. Budgets are transaction/data invariants with measured
parent numbers; no unmeasured after-latency promise is made.

| Rank / existing row | Smallest owned change; files | Proof and before/after budget |
|---|---|---|
| 1 — RP-14 with EN-8b | Retire model open/system/capture/plan writes after facts join the one recording owner; `turn.clj`, `turn/batch.clj`, `db.clj`, `sci/eval.clj`, declared schemas | Ledger `seon.db-test/a-transaction-stamp-carries-no-ref-to-an-unrecorded-evaluation` and model entrant row. This fixture: **6→1 commits after ingress**, **7→2 with ingress**, zero unrecorded-evaluation refs; baseline 548–779 ms / 496–593 KB. Re-run both successful request and crash-after-effect; effects remain durable |
| 2 — EN-1 + RP-14 | Replace effect evaluation/run refs by request id value and form ordinal; attach minimal writer provenance in `db.clj`, `effect.clj`, transaction/effect schemas | Ledger `seon.effect-test/a-receipt-names-its-pass-by-value`; add explicit JVM agent-provenance and same-transaction schema adoption assertions to its writer proof. **0 extra commits for metadata**, no forced evaluation skeleton, metadata increase bounded by applicable scalar fields. Background baseline **3 commits**; do not assert one across external execution |
| 3 — S5-02 | Remove `significant-tx` and measurement persistence; `profile/measurement.clj`, `health.clj`, `turn.clj`, `turn/batch.clj`, schemas | Ledger `seon.health-storage-test/health-uses-live-profile-without-writing-measurements`. Defn **386 logical datoms including ~307 measurement datoms → zero measurement datoms**, still one commit. Baseline 667 ms / 183 KB; profile and bytes after deletion, without promising subtraction of inclusive times |
| 4 — EN-6, with EN-7's existing checkpoint owner | Keep admission, combine all ordinary member completions; `test.clj`, `test/runner.clj`, run schema. Separately compose checkpoint config/process/cluster/source writes through existing seed owner | Current ledger `seon.test-test/an-executed-proof-request-commits-twice`, late-exit/replay rows. One proof already **2 durable**; larger requests must remain **2**, not 1+member count. Repeated MCP check remains **3 including enclosing recording**. First checkpoint **4 commits→1** if final owning validation/read dependencies permit; branch head copy excluded. Baselines 300–382 ms direct, 2,948 ms repeated MCP, 5,093 ms first |
| 5 — MA-11 | One home writer creates/updates merge request and root notification; `task.clj`, `my/program.clj`, message transaction producer; retain proof and supersession checks | Ledger `seon.merge-policy-test/merge-commits-green-and-stops-on-red`, `…/a-merged-definition-carries-no-evaluation-pointer`; extend request proof with one home transaction carrying both request and message. **2→1 home commits**; full installed proposal **5→4** before other cuts; 3,027 ms / 714 KB baseline. Empty diff should create **0** merge requests under OD-Q12 |
| 6 — SR-GROW | Correct probe commit counting and preserve per-index accounting using Datahike `-mark`; `test/seon/pass_probe.clj`, its test, dated evidence | Existing `seon.pass-probe-test/a-pass-of-each-entrant-is-measured`, add branch-copy count assertion. **Branch 1 reported→0 new commits**. Controlled ten-row parent **10→1**, **744,161→94,893 bytes**, **179.850→22.861 ms** already measured. Further commit/head or node-layout change requires this decomposition at matching inputs; do not implement a Seon index writer |
| 7 — S6-05 | Answered basis and continuation ride the final request transaction; `turn.clj`, agent/call schemas | Ledger `seon.turn-work-test/model-passes-answer-only-the-basis-they-presented`, existing mid-request wake test. **0 additional commits** beyond RP-14 final recording; a wake arriving later must remain due. Current final model transaction has 14 logical datoms |

Boot's config/process/cluster composition is the same seed-owner work exposed by
EN-6/EN-7's checkpoint, not another general boot scheduler. Four checkpoint
writes include a one-business-datom process write and a one-business-datom
source-pointer write. Warm boot also writes a one-business-datom process identity.
Combine only where all inputs are already known and no consumer must see an
intermediate commit. Task create/assign/revise each already uses one transaction;
offer one writer composition when a single caller actually owns the whole
operation, not a delayed-write buffer across separately answered calls.

Operations over one second are findings, not explained away as cold: first and
repeated check (EN-6/EN-7 and current reach-cache work), merge proof/request
(MA-11 plus test owner), warm cluster start (seed/config and graph acquisition),
and full process boot. No cold boot cost ranking is asserted from readiness alone.
The one-second idle observation is deliberately a wait, not ordinary computation.

## Reproduction and landing

Scratch was confined to `tmp/tx-census-astra`. The snippets below are measurement
code, not product definitions, and must be evaluated only in an owned throwaway
JVM with the committed `seon.pass-probe` available. The live default was used
only for read-only MCP status/provenance probes with explicit connection.

Foreign boundary: checkout changes to `src/seon/db.clj`, error/recording,
cluster/agent/registry/status, schemas, dependencies and tests belong to other
lanes. They were neither loaded nor edited. Runtime observations use the named
archive. New-proof digest refusal and source drift are recorded above and do not
stop the independent successful request measurements. Failed experimental forms
are excluded from the normal census: a wrong zero-arity `seon.env/of` proof call
recorded three error/evaluation commits; a merge request without proofs was
refused after its one enclosing evaluation commit. They are not successful
proof/merge baselines.

Pre-commit lint ran over a `git archive` of
`3f69939e40c97c8123980202c487960b4e08e93c`, with the dependency lint configuration
linked and `clj-kondo --lint src script test --cache false --parallel`:
**16,390 ms, 0 errors, 1,467 warnings**. No source/test files are in this slice.

Owned process `(15557, 2026-10-08T06:22:51.394Z)` was stopped through
`bin/seon --root tmp/tx-census-astra/root down`; the operator returned
`:process-exit? true`, and the PID no longer existed. Both owned clusters and the
holding shell exited. Last owned-root health had no open calls, about 434–438 MB
live heap and 176–179 MB weighted cache. Fixture errors and rejected experimental
forms account for its recorded errors; they are not live default errors.

The commit gate is explicitly scoped to
`seon.pass-probe-test/a-pass-of-each-entrant-is-measured` with `--include-long`.
It exercises the actual measurement entry points (ordinary MCP, definition,
following call, fixture model). The two named `seon.id-test` proofs in the census
passed during measurement. No entire namespace is substituted for the named
probe test. The lane command must boot and pass that test before committing;
its outcome is not inferred from the historical measurements above.

### Measurement source

The listener wrapper below is the final version. Earlier samples lacked store
key capture; their commit/head pairs and bounded history supplied the correction.
The first ordinary sample did not capture individual store events, but its
three writer attempts and three transactions are independently visible in history.

#### Observation wrapper

```clojure
(ns measure
 (:require [seon.pass-probe :as p] [datahike.api :as d]
           [konserve.core :as k] [konserve.impl.defaults :as kd]
           [clojure.java.io :as io] [seon.db :as db] [seon.cluster.boot :as boot]))
(def c (boot/connection "default"))
(def root-world {::p/store (:store @c) ::p/base (get-in @c [:config :store :path])
                 ::p/branches #{(get-in @c [:config :branch])}})
(defn mcp [agent mode code]
  (#'p/mcp-pass {"agent" agent "cluster" "default" "mode" mode "code" code}))
(defn world [agent] (#'p/agent-world "default" agent))
(defn measure [label w f]
  (let [reports (java.util.concurrent.ConcurrentLinkedQueue.)
        writes (java.util.concurrent.ConcurrentLinkedQueue.)
        connections (distinct (keep identity [c (:seon.db/connection w)]))
        key :tx-census-astra/observe
        hook (fn [event]
               (.add writes (mapv (fn [[op key cid branch]] {:op op :key key :commit cid :branch branch})
                                  (#'p/written event))))]
    (doseq [conn connections] (d/listen conn key #(.add reports %)))
    (k/add-write-hook! (::p/store w) key hook)
    (try
      (let [r (p/window w f)
            txs (mapv (fn [report]
                        (let [datoms (db/logical-datoms report)]
                          {:tx (some :tx datoms) :commit (get-in report [:db-after :meta :datahike/commit-id])
                           :branch (get-in report [:db-after :config :branch])
                           :datoms (count datoms) :attrs (frequencies (map :a datoms))
                           :meta (into {} (map (juxt :a :v)) (filter #(= (:e %) (:tx %)) datoms))})) reports)
            commits (mapv (fn [rows]
                            {:commit (some :commit rows) :branch (some :branch rows) :files (count rows) :keys (mapv :key rows)
                             :bytes (reduce + 0 (map (fn [{:keys [key]}]
                                                      (.length (io/file (::p/base w) (kd/key->store-key key)))) rows))}) writes)]
        (assoc r :label label :transactions txs :commit-writes commits))
      (finally
        (doseq [conn connections] (d/unlisten conn key))
        (k/remove-write-hook! (::p/store w) key)))))
{:ready true :basis (db/basis-t @c) :config (select-keys (:config @c) [:branch :keep-history? :attribute-refs?])}
```

#### Ordinary MCP requests

```clojure
(do
 (def census-first (measure/measure :creation-first measure/root-world (measure/mcp "tx-census-astra" "sci" "(+ 1 2)")))
 (def census-world (measure/world "tx-census-astra"))
 (def census-ordinary
  (mapv (fn [[label mode code]] (measure/measure label census-world (measure/mcp "tx-census-astra" mode code)))
   [[:sci-1 "sci" "(+ 1 2)"]
    [:sci-2 "sci" "(+ 1 2)"]
    [:jvm "jvm" "(+ 1 2)"]
    [:defn "sci" "(in-ns 'scratch.tx-census-astra) (defn plus-one \"Returns x plus one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))"]
    [:after-defn "sci" "(plus-one 41)"]]))
 (into [census-first] census-ordinary))
```

#### Task, branch and direct proof requests

```clojure
(do
 (require '[measure] '[seon.cluster.agent] '[seon.test] '[seon.task] '[seon.db] '[seon.cluster.registry])
 (let [w (measure/world "tx-census-astra")
       conn (:seon.db/connection w)
       handle (seon.cluster.agent/acquire-context! (get-in w [:seon.pass-probe/host :seon.turn.loop/cluster]) "tx-census-astra")
       run-one (fn [] (seon.test/run {:seon.test/execution handle :seon.test/recording-connection conn
                                     :seon.test/policy :named :seon.test/identities #{'seon.id-test/a-host-object-has-no-identity}}))
       observe (fn [label f] (measure/measure label w (fn [] {:seon.pass-probe/complete? true :value (f)})))
       task {:seon.db/connection measure/c :seon.agent/id "tx-census-astra"
             :seon.task/ns 'seon.id :seon.task/name "tx-census-measurement-two" :seon.task/kind :work
             :seon.task/intent "Measure the task writer."}
       created (observe :task-create #(seon.task/create! task))
       id (get-in created [:value :seon.task/id])
       assigned (observe :task-assign #(seon.task/assign! {:seon.db/connection measure/c :seon.task/assigned id
                           :seon.task/agent [:seon.agent/id "tx-census-astra"] :seon.task/branch (get-in @conn [:config :branch])}))
       revised (observe :task-revise #(seon.task/create! (assoc task :seon.task/intent "Measure the revised task writer."
                           :seon.task/basis {id (#'seon.task/declared (seon.db/carried-projection (seon.db/db measure/c))
                                                 (seon.db/pull (seon.db/db measure/c) '[*] [:seon.task/id id]))})))
       branch (observe :branch-create #(seon.cluster.registry/branch! {:seon.store/store (:seon.store/store handle)
                            :seon.store/branch :tx-census-branch :seon.cluster.registry/from (seon.db/commit-id @conn)
                            :seon.db/db (seon.db/db conn)}))]
   [created assigned revised branch (observe :proof-direct-first run-one) (observe :proof-direct-repeat run-one)]))
```

#### Fixture model requests

```clojure
(do
 (require '[measure] '[seon.test-support] '[seon.id] '[seon.pass-probe])
 (let [ledger (atom []) timings (atom [])]
  (seon.test-support/with-provider ledger
   (seon.test-support/reply-queue ["```clojure\n(+ 1 2)\n```" "```clojure\n(+ 1 2)\n```"])
   timings
   (fn [server]
    ((measure/mcp "tx-census-model" "sci"
       (str "(my.agent/settings! " (seon.id/printed (merge (seon.test-support/provider-config server)
                             {:seon.config.ai/no-provider false :seon.config.run/max-episode-runs 1})) ")")))
    (let [w (assoc (measure/world "tx-census-model") :seon.pass-probe/ledger ledger)]
     (mapv (fn [n]
             (let [start (count @timings)
                   r (measure/measure (keyword (str "model-" n)) w (#'seon.pass-probe/model-pass w))]
              (assoc r :provider-ms (/ (reduce + 0 (map (fn [[a b]] (- b a)) (subvec @timings start))) 1e6))))
           [1 2]))))))
```

#### Tiny transaction comparison

```clojure
(do
 (require '[measure] '[seon.db] '[seon.task])
 (let [w (measure/world "tx-census-astra") c (:seon.db/connection w)
       trans (fn [label request]
               (measure/measure label w
                 (fn [] (let [r (seon.db/transact! c request)]
                          {:seon.pass-probe/complete? true :committed? (boolean (:db-after r))
                           :refusal (when-not (:db-after r) r)}))))
       tiny (mapv (fn [[label metadata]] (trans label {:tx-data [] :tx-meta metadata}))
                  [[:tiny-1 {}] [:meta-1 {:seon.db.transaction/agent "tx-census-astra" :seon.db.transaction/process "fixture"}]
                   [:tiny-2 {}] [:meta-2 {:seon.db.transaction/agent "tx-census-astra" :seon.db.transaction/process "fixture"}]
                   [:tiny-3 {}] [:meta-3 {:seon.db.transaction/agent "tx-census-astra" :seon.db.transaction/process "fixture"}]])
       rows (fn [prefix] (mapv (fn [i] (#'seon.task/task-entity
                                         {:seon.task/ns 'seon.id :seon.task/name (str prefix i) :seon.task/kind :work
                                          :seon.task/intent "Transaction census fixture."} "tx-census-astra")) (range 10)))
       separate (mapv (fn [i row] (trans (keyword (str "separate-" i)) [row])) (range 10) (rows "census-separate-"))
       together (trans :together (rows "census-together-"))]
   (into (into tiny separate) [together])))
```

#### Metadata schema order and index attribution

```clojure
(do
 (require '[measure] '[seon.db :as db] '[datahike.api :as d]
          '[datahike.index.interface :as ix] '[konserve.core :as k]
          '[konserve.impl.defaults :as kd] '[clojure.java.io :as io] '[clojure.set :as set])
 (let [w (measure/world "tx-census-astra") conn (:seon.db/connection w) before @conn
       declaration {:db/ident :tx.census/request :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
       attempt (fn [f] (try (let [r (f)] {:datoms (count (:tx-data r))
                                         :metadata (d/pull (:db-after r) '[*] (get-in r [:tempids :db/current-tx]))})
                            (catch Exception e {:error (ex-data e)})))
       metadata {:same-tx-meta (attempt #(d/with before [declaration] {:tx.census/request "fixture"}))
                 :same-tx-entity (attempt #(d/with before [declaration {:db/id :db/current-tx :tx.census/request "fixture"}]))
                 :installed-tx-meta (attempt #(d/with (:db-after (d/with before [declaration])) [] {:tx.census/request "fixture"}))}
       r (measure/measure :index-tiny w #(let [report (db/transact! conn {:tx-data []})]
                                        {:seon.pass-probe/complete? true :committed? (boolean (:db-after report))}))
       cid (get-in @conn [:meta :datahike/commit-id]) store (:store @conn)
       stored (k/get store cid nil {:sync? true}) written (set (mapcat :keys (:commit-writes r)))
       size (fn [keys] (reduce + 0 (map #(.length (io/file (:seon.pass-probe/base w) (kd/key->store-key %))) keys)))
       indexes (into {} (for [[name index-key root-key] [[:eavt :eavt-key :eavt-root] [:aevt :aevt-key :aevt-root] [:avet :avet-key :avet-root]
                                                        [:temporal-eavt :temporal-eavt-key :temporal-eavt-root]
                                                        [:temporal-aevt :temporal-aevt-key :temporal-aevt-root]
                                                        [:temporal-avet :temporal-avet-key :temporal-avet-root]]]
                         (let [idx (cond-> (ix/with-storage (:index (:config @conn)) (get stored index-key) (:storage store))
                                     (get stored root-key) (ix/-seed-root! (get stored root-key)))
                               keys (set/intersection written (ix/-mark idx (atom #{})))]
                           [name {:files (count keys) :bytes (size keys) :keys keys}])))
       all-index (reduce set/union #{} (map :keys (vals indexes)))
       other (set/difference written all-index)]
   {:metadata metadata :measurement r :indexes (update-vals indexes #(dissoc % :keys))
    :index-union {:files (count all-index) :bytes (size all-index)}
    :other (mapv (fn [key] {:key key :bytes (size [key]) :commit? (= key cid)}) other)}))
```

#### MCP check request

```clojure
(do
 (def census-tests
  (mapv (fn [[label code]] (measure/measure label (measure/world "tx-census-astra") (measure/mcp "tx-census-astra" "sci" code)))
   [[:check-first "(my.test/check {:seon.test/changed ['seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol]})"]
    [:check-repeat "(my.test/check {:seon.test/changed ['seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol]})"]
    [:proof-first "(seon.test/run {:seon.test/execution (seon.env/of) :seon.test/policy :named :seon.test/identities #{'seon.id-test/a-host-object-has-no-identity}})"]]))
 census-tests)
```

#### Successful existing-proof merge request

```clojure
(do
 (require '[measure] '[seon.task] '[seon.db])
 ((measure/mcp "tx-census-merge-existing" "sci" "(+ 1 2)"))
 (def existing-world (measure/world "tx-census-merge-existing"))
 (def existing-task (seon.db/pull (seon.db/db measure/c) [:seon.task/id] [:seon.task/sym 'seon.repl/a-selected-render-arrives-as-an-escaped-string]))
 (def existing-assigned (seon.task/assign! {:seon.db/connection measure/c :seon.task/assigned (:seon.task/id existing-task)
                     :seon.task/agent [:seon.agent/id "tx-census-merge-existing"]
                     :seon.task/branch (get-in @(:seon.db/connection existing-world) [:config :branch])}))
 (def existing-proposal (measure/measure :merge-existing existing-world
     (measure/mcp "tx-census-merge-existing" "sci" "(my.program/propose-merge! {})")))
 {:measurement existing-proposal
  :reply (seon.db/q '[:find ?shown :where [?e :seon.cluster.eval/source "(my.program/propose-merge! {})"] [?e :seon.eval/shown ?shown]]
                   (seon.db/db (:seon.db/connection existing-world)))})
```

#### Error recording and background exit

```clojure
(do
 (require '[measure] '[seon.cluster.agent] '[seon.env] '[seon.fault] '[datahike.api :as d] '[seon.test-support])
 (let [w (measure/world "tx-census-astra")
       h (seon.cluster.agent/acquire-context! (get-in w [:seon.pass-probe/host :seon.turn.loop/cluster]) "tx-census-astra")
       failure (ex-info "Transaction census fixture." {})
       record (fn [] {:seon.pass-probe/complete? true
                      :value (seon.fault/fault! (seon.env/of h) failure
                               {:seon.error/layer :seon.test/fixture :seon.error/operation 'seon.id/id
                                :seon.agent/id "tx-census-astra" :seon.db.process/id (:seon.db.process/id w)})})
       a (measure/measure :error-first w record)
       b (measure/measure :error-repeat w record)
       conn (:seon.db/connection w)
       done (promise)
       _ (d/listen conn :tx-census-astra/background
                  (fn [r] (when (some #(= :seon.effect/settled-at (:a %)) (:tx-data r)) (deliver done true))))
       bg (try
            (measure/measure :background-shell w
             (fn []
              (let [reply ((measure/mcp "tx-census-astra" "sci"
                              "(my.background/background (my.shell/run! {:my.shell/argv [\"/usr/bin/printf\" \"census\"] :my.shell/cwd \".\"}))"))]
               (assoc reply :effect-completed (deref done 5000 :timeout)))))
            (finally (d/unlisten conn :tx-census-astra/background)))]
  [a b bg]))
```

#### Warm cluster boot

```clojure
(do
 (require '[measure] '[seon.cluster.boot] '[seon.db] '[datahike.api :as d])
 (def census-boot
   (measure/measure :cluster-boot measure/root-world
    (fn []
      (let [instance (seon.cluster.boot/start! {:seon.boot/root "/Users/sean/src/seon/tmp/tx-census-astra/root"
                                              :seon.boot/cluster-name "tx-census-boot"
                                              :seon.config/manifest {:seon.config.ai/no-provider true}})]
       {:seon.pass-probe/complete? true :value (seon.cluster.boot/readiness instance)}))))
 (let [c (seon.cluster.boot/connection "tx-census-boot") database (seon.db/db c)
       txs (group-by :tx (d/datoms (d/history database) :eavt))]
  {:measurement census-boot
   :history (mapv (fn [[tx datoms]] {:tx tx :datoms (count datoms)
                                    :meta (into {} (map (juxt :a :v)) (filter #(= (:e %) (:tx %)) datoms))})
              (sort-by key txs))}))
```

#### Post-window transaction history and idle Flow

```clojure
(do
 (require '[measure] '[datahike.api :as d] '[datahike.writing :as writing]
          '[konserve.core :as k] '[seon.cluster.boot])
 (let [store (:store @measure/c)
       histories (into {} (for [[branch low high]
                                [[:agent-bac09c06ef88 536870934 536870936]
                                 [:checkpoint-default-c25a2fc47fb07663ff0c5605 536870921 536870930]
                                 [:cluster-tx-census-boot 536870925 536870930]]]
                           (let [stored (k/get store branch nil {:sync? true})]
                             [branch (when stored
                                       (let [database (writing/stored->db stored store {:secondary-indices? false})]
                                         (mapv (fn [[tx ds]] {:tx tx :physical-datoms (count ds)
                                                               :attrs (frequencies (map :a ds))
                                                               :meta (into {} (map (juxt :a :v)) (filter #(= (:e %) (:tx %)) ds))})
                                               (sort-by key (group-by :tx (filter #(<= low (:tx %) high) (d/datoms (d/history database) :eavt)))))))])))
       idle (measure/measure :idle-flow measure/root-world #(do (Thread/sleep 1000) {:seon.pass-probe/complete? true}))]
   {:histories histories :idle idle}))
```

### Raw evidence identities

These SHA-256 values identify the original local EDN reports. The tables and
executable forms above preserve the findings without requiring the disposable
store. Scratch reports are local evidence, not checked-in source.

| Report | SHA-256 |
|---|---|
| `boot-result.edn` | `bd36d716fbe39b97a2562c8ab97da8d4c1691a578855103c52fded3b897396be` |
| `history-result.edn` | `24b1577df8332ab4804be379db0482be229cdae70fcef1346b754ef0192cc1b8` |
| `inspect-result.edn` | `5df40ce1f3107d3e8298bc89ca965ed1494455fb8b1bc90ae4f94ec0fbeb6c21` |
| `merge-existing-result.edn` | `18cba0363e64d391d00d874893c7cc55a5fc12d7093b9a5e398953b25cde1c9f` |
| `merge-result.edn` | `4bf2a70d5e1cd68fb1fc6500d40182bd4451d224295bfdfe37c507feea25a6ff` |
| `merge-retry-result.edn` | `b285f10b54c7f2c107e5905111b5ba0addab1888488fd5e00a21e2efc88c60af` |
| `merge-retry2-result.edn` | `66b30d1e54c021bcc01a733dde2dde325b4b0917b756f797e1fc9d0a549c03e7` |
| `metadata-indices-result.edn` | `690cfba973e6d205f67df5d26b430a5483cd891b07ccbc3c5cceeb60749d14b1` |
| `micro-result.edn` | `963afc5977e65684a03e1c86c43029364395f02039631baf3d0dc824f5a86b96` |
| `model-result.edn` | `1204f05170f9df2f45c68e94df2e8ce5b38e2accc4cacf2594559d9e3d855be4` |
| `more-result.edn` | `b0d424ece2b07b7ed093105de081bafb81d360be3449d1fe09ae3062faaefeb7` |
| `ordinary-result.edn` | `4624656a52833b6428caff3363ffb7b3fe8ad3a229f4bc7dc9b4449f920ea35b` |
| `tasks-proof-result.edn` | `222ab8be666bae889d55202233b2883e7df2dcfb0922794afccd04fc98a47977` |
| `tests-result.edn` | `529d2372b200d5b6de454a9e5e6c0ba41c494e5f213eb1f7cf27f57d357fdd44` |
