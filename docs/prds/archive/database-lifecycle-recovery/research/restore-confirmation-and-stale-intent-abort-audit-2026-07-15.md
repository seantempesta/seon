---
type: research
status: completed
tags: [research, database, flow]
---

# Restore confirmation and stale-intent abort audit — 2026-07-15

## Result

Public retained-head restore is not yet safe to expose. The storage transition
now fails closed when an accepted write moves main between intent publication
and the exclusive fence, but the public CLI still derives, publishes, and
applies an intent without exact human confirmation. A rejected stale intent
then has no proved public abort/replan boundary.

The correction belongs around the existing immutable intent and coordinator,
not in a second lifecycle service:

- `seon.dev.restore` owns canonical intent bytes, the plan digest, and the
  exact confirmation phrase as pure data;
- `seon.dev.restore-state` separates read-only `plan!`, first-authority
  `apply!`, already-authorized `resume!`, and tightly bounded pre-preparation
  `abort!` operations around the same fact-derived transition;
- `seon.dev.cli` owns prompting, noninteractive plan-file parsing,
  presentation, and the existing outer `:stack` lock; and
- `seon.dev.process` exposes one exact restore-admin absence proof from the
  existing gated containment record. It does not add another process registry
  or kill path.

An abort is allowed only while both reserved branches are absent, the exact
intent has no completion, no admin result exists, and the generation-gated
admin workload is proved absent. It may delete an intent whose observed main
or selected target has moved, because that ordinary movement is the stale-plan
case this operation exists to recover. Once either reserved branch exists,
abort is forbidden: the ordinary immutable transition must converge or a
separately designed exact branch-release operation must clean it.

## Exit measure blocked

This audit closes the design question behind the open issue
[[../../../seon/issues/restore-intent-lacks-exclusive-writer-fence]]. It does
not close the issue or authorize destructive proof. The retained-head exit is
blocked until all of the following are integrated:

1. a read-only public plan produces one closed immutable intent and exact
   confirmation phrase;
2. apply rejects missing, shortened, differently cased, or stale confirmation
   before intent publication or lifecycle mutation;
3. a confirmed intent is durably published before the first mutation and is
   thereafter sufficient authority for crash recovery without prompting;
4. the already implemented drain/re-observation happens before `U` creation;
5. an explicit abort passes the exact no-effect preconditions below; and
6. focused crash/concurrency proof plus a source-frozen live restore show that
   a late accepted write either lands before the frozen head or produces a
   safely abortable stale intent, never a guessed restore.

## Dependency ledger

| Dependency or mechanism | Selected identity | Exact source grounding | Constraint on this design |
|---|---|---|---|
| Datahike | `org.replikativ/datahike` `9ada755087228e10cfb179fa5779ce227a6ed220` | `deps.edn:23-27`; `reference-code/datahike/src/datahike/versioning.cljc:171-225,257-378` | `branch!` publishes immutable content before the roster pointer. `force-branch!` checks the expected destination head before work and inside the mutable-head update, but explicitly requires exclusive write access because Konserve has no cross-operation CAS. Confirmation cannot replace the writer drain. |
| Konserve | `org.replikativ/konserve` `b5c99bc02a7175652a610324215288b78551801f` | `deps.edn:28-34`; exact pinned object read with `git -C reference-code/konserve show b5c99bc...:src/konserve/filestore.clj`; `reference-code/konserve/src/konserve/protocols.cljc` | Synchronous publication forces file content and directory metadata, but no transaction joins an external intent, process drain, reserved branches, and the Datahike main head. The external fsync-published intent and fact-derived retry remain necessary. The protected checkout currently points at older `df6818...`; the exact selected object is locally present and was read without moving it. |
| Proximum | `org.replikativ/proximum` `9846d3e79e1aee48474bc876d3d563d7137209c6` | `deps.edn:41-43`; exact pinned object read with `git -C reference-code/proximum show 9846d3e...:src/proximum/versioning.clj:119-182` | Guarded existing-destination force adopts only the exact source generation or expected destination generation and rejects every other head. Abort must never infer that a missing Datahike completion means the secondary force did not run; the absence proof must precede any admin launch. The protected checkout currently points at older `5f7142...`; the selected object is locally present. |
| Malli | `metosin/malli` `0.20.0`, checkout `80138076960e7820523b4cb932c5b5d1936d4e7f` | `deps.edn:6-7,20-21`; actual `:writer` classpath; `reference-code/malli/src/malli/core.cljc:1223-1313` | Closed maps reject extra confirmation or digest inputs and distinguish missing keys. Malli validates the shape; relational checks must still rederive the digest, phrase, selected branch, and abort eligibility. |
| Database transport | Seon's Transit UDS boundary, `com.cognitect/transit-clj` `1.0.333` | `deps.edn:40`; `src/seon/db/transport/uds.clj`; `src/seon/db/protocol.cljc:260-358,535-541` | Planning and abort observation reuse the typed lifecycle request. No EDN-over-argv database operation or second transport is needed. Datahike's optional Kabel `0.3.100` appears only in its test/source alias and is absent from Seon's actual `:writer` classpath, so it owns no part of this local operator boundary. |
| Canonical EDN and hashing | Clojure `1.12.0`, Babashka runtime, JDK SHA-256 | `src/seon/dev/restore.clj:299-320`; `script/seon/dev/state.clj:27-62` | The digest must be byte-identical in Babashka and the writer artifact. Publication remains temp-write, file fsync, atomic rename, parent fsync; deletion fsyncs the parent. Unsupported or printer-dependent leaves must fail rather than being accepted by a catch-all canonicalizer. |
| Process containment | one `:seon.dev.process/restore-admin` record and gated workload | `script/seon/dev/process.clj:1309-1326,1589-1737`; `script/seon/dev/detach.py` | The workload cannot pass its generation gate until the exact owner/anchor/workload record is durable. Therefore an exact absent record under the existing `:restore-admin` lock proves no admitted admin workload from this mechanism; an uncertain or foreign record blocks abort. |
| Current restore intent and completion | `seon.dev.restore`, `seon.dev.restore-state`, `seon.db.restore` | `src/seon/dev/restore.clj`; `script/seon/dev/restore_state.clj`; `src/seon/db/restore/schema.cljc` | The one immutable intent remains authority. Completion remains a database fact. Confirmation must not become a domain datom, copied provenance, mutable phase, or second intent shape. |

The actual `clojure -Spath -M:writer` basis selected the three public Git SHAs,
Malli `0.20.0`, and Transit CLJ `1.0.333`; Kabel was absent.

## Executable digest probe

The current private digest function was called on the same unordered map/set
value from Babashka and the JVM writer basis:

```clojure
{:seon.dev.restore/intent-version 1
 :seon.dev.restore/intent-id "0123456789abcd"
 :x #{:b :a}
 :y {:z 2 :a 1}}
```

Both returned:

```text
b1ac775f9b6c28c8ad9d5f0cd24ca4d6ce135dcdc792d8078c43efb6b2d009b7
```

This is useful parity evidence, but the current fallback `:else value` still
admits leaves whose printed representation is not a settled cross-runtime
format. Public confirmation should first make the supported canonical leaf set
explicit and test every actual intent value type across both runtimes.

## Current implementation findings

### What is already correct

- `restore/derive-intent` creates a closed intent and computes
  `:seon.dev.restore/plan-digest` before associating that digest into the value.
  `validate-intent` removes and recomputes it. The basic no-self-hash structure
  is therefore correct.
- `restore-state/publish-intent!` rejects different retained bytes and uses the
  one fsync-durable state writer.
- The current in-progress coordinator materializes blobs, stops retained pods,
  drains main pod and writer, starts only an observation writer, and calls
  `require-next-command!` before creating `U`. A write accepted before writer
  shutdown therefore changes the fresh observation and blocks `U` creation.
- The guarded branch-create and force requests retain exact expected-head
  fences. A stale plan fails closed instead of silently restoring a newer head.
- `up` and `restart` treat a published intent as sufficient recovery authority;
  competing branch mutation and reset paths reject it. This is the correct
  post-confirmation crash behavior.

### What remains unsafe

- `script/seon/dev/cli.clj:569-587` accepts only a branch and immediately calls
  the destructive coordinator. There is no plan mode, phrase, token, TTY gate,
  or exact confirmation schema.
- `derive-initial-intent!` observes `H`, `T`, roster, blob digest, artifact, and
  random generations and immediately calls `publish-intent!`. Derivation,
  confirmation, first publication, and resume are conflated.
- A normal transaction can still commit after the first observation and intent
  publication but before the drain. The fresh re-observation correctly rejects
  it, but leaves a durable stale intent with no public way to prove no restore
  effect and remove it.
- `restore!` can derive a new intent or resume an existing one through the same
  request. That makes it impossible for the public caller to prove that the
  first publication was confirmed while allowing automatic crash recovery to
  remain prompt-free.
- Plan digest and confirmation are not public pure operations, and the
  canonicalizer silently accepts arbitrary leaves.

## Canonical plan contract

### One digest, no circular field

Keep `:seon.dev.restore/plan-digest` as the only token. Do not add a redundant
`confirmation-digest`, `confirmed?`, `confirmed-at`, or copied user identity.
The canonical algorithm is:

1. validate one closed intent payload that does **not** contain
   `:seon.dev.restore/plan-digest`;
2. encode it as a domain-separated canonical tree;
3. SHA-256 the UTF-8 bytes of that tree;
4. associate the lowercase 64-hex digest into the final intent; and
5. validate an existing intent by removing exactly that key, recomputing, and
   requiring equality.

The domain separator is part of the bytes, for example
`[:seon.dev.restore.canonical/v1 <tree>]`. The intent's own version remains in
the tree. Changing the canonical algorithm requires a new intent/canonical
version; it must never reinterpret retained v1 bytes.

The canonical tree supports only the values the closed intent schemas admit:

```clojure
nil       => [:nil]
boolean   => [:boolean "true" | "false"]
string    => [:string value]
keyword   => [:keyword (or (namespace value) "") (name value)]
uuid      => [:uuid (str value)]
integer   => [:integer (str value)]
vector    => [:vector (mapv canonical value)]
set       => [:set (sort-by canonical-bytes (map canonical value))]
map       => [:map (sort-by canonical-key-bytes
                            (map (fn [[k v]] [(canonical k) (canonical v)]) value))]
```

Sequences, symbols, floating point values, dates, records, tagged literals, and
all other leaves fail. This avoids relying on host map/set iteration order,
integer representation suffixes, or arbitrary `pr-str` implementations. The
final tagged tree may be printed with `pr-str` because every leaf is now a
normalized string or fixed keyword/vector tag.

The exact confirmation text is a pure projection of the already validated
final intent and is **not** an intent field and is **not** hashed. This avoids
both self-reference and a second authority. Suggested exact apply phrase:

```text
RESTORE <runtime-cluster> DATABASE <full-database-uuid> FROM :db/<full-H-commit>@<H.t> TO <full-target-branch>/<full-T-commit>@<T.t> INTENT <intent-id> PLAN <full-plan-digest>
```

Suggested abort phrase:

```text
ABORT RESTORE <runtime-cluster> DATABASE <full-database-uuid> TARGET <full-target-branch>/<full-T-commit>@<T.t> INTENT <intent-id> PLAN <full-plan-digest>
```

No abbreviation, prefix digest, branch display alias, trimming, case folding,
or whitespace normalization is accepted. The strings are authorization
challenges, not secrets, so ordinary exact equality is sufficient.

### Closed pure schemas

Add these schemas in `src/seon/dev/restore.clj` (portable leaf identities stay
in the existing `restore/schema.cljc` only when the pod consumes them):

```clojure
::confirmation-action
[:enum :seon.dev.restore.confirmation/apply
       :seon.dev.restore.confirmation/abort]

::confirmation-text
[:string {:min 1 :max 512}]

::plan
[:map {:closed true}
 [::intent ::intent]
 [::confirmation-text ::confirmation-text]]

::confirmation-request
[:map {:closed true}
 [::intent ::intent]
 [::confirmation-action ::confirmation-action]]
```

Public pure owners:

- `canonical-intent-bytes` accepts a validated digest-free payload;
- `plan-digest` returns the one lowercase SHA-256 token;
- `confirmation-text` derives the exact apply or abort phrase; and
- `validate-plan` validates the intent and rederives the exact phrase.

The final function is relational validation. A closed Malli map alone cannot
prove that a well-formed phrase names the same coordinates and digest.

## CLI plan/apply UX

### Interactive default

`bin/seon cluster restore <retained-branch>` is a two-stage operation in one
process:

1. under the existing `:stack` lock, run the read-only plan and release the
   lock;
2. print full `H`, `T`, database id, intent id, artifact digest, reachable-blob
   digest, reserved branches, and the exact apply phrase;
3. require a real console; read one line and compare it byte-for-byte; and
4. reacquire `:stack`, freshen every plan observation, publish the exact
   confirmed intent, and enter the existing convergence loop.

If `System/console` is absent, the command fails before effect and points to the
noninteractive plan/apply form. It never treats piped `yes`, `--yes`, an empty
line, or a prefix token as authority.

### Noninteractive two-command form

The automation-safe surface is explicit data, not a hidden proposal registry:

```bash
bin/seon cluster restore <branch> --plan --edn > restore-plan.edn
bin/seon cluster restore <branch> \
  --apply-plan restore-plan.edn \
  --confirm '<exact confirmation phrase from the plan>'
```

The plan file is caller-owned input. It is never written under the cluster
`lifecycle/` directory, never scanned on boot, and carries no authority without
the exact phrase. Apply reads it once with a bounded size, validates the closed
EDN value and digest, and uses the in-memory value thereafter. A concurrent
edit to the caller file cannot change the value eventually published.

`--plan --edn` emits exactly one EDN value and no prose. Human plan output may
format the same facts but cannot omit the full token. The plan contains launch
paths and coordinates but no environment or credentials; the launch descriptor
schema confirms that environment data is not part of it.

### Apply ordering

Inside the reacquired outer `:stack` lock:

1. validate the supplied plan and exact phrase before any effect;
2. reject a different retained intent; if the identical intent is already
   retained, delegate to prompt-free `resume!` because authority survived;
3. when no intent exists, re-observe manifest/writer digest, exact `H`, full
   roster, target descriptor/head `T`, and target reachable-blob digest using
   the supplied plan's already frozen intent id and consumer generations;
4. derive a fresh candidate and require whole-value equality with the supplied
   intent;
5. on mismatch, return/throw typed
   `:seon.dev.restore.error/stale-confirmed-plan`, retain no new intent, perform
   no stop, materialization, branch, or admin effect, and print a newly planned
   candidate only as unconfirmed data;
6. on equality, fsync-publish that exact intent; and
7. call `resume!`, which performs the existing materialize, drain,
   re-observation, reserved branches, admin, reconstruction, completion, and
   readiness sequence.

The final observe-to-publish interval cannot be made atomic with ordinary
database work. The immutable expected-head fences remain necessary. If a write
lands in that interval, the post-drain `require-next-command!` rejects before
`U`; the explicit abort below is the safe recovery boundary.

## Exact stale-intent abort boundary

### Public operation

Use the retained canonical intent rather than a caller plan file:

```bash
bin/seon cluster restore <branch> --abort
```

Interactive use prints the exact abort phrase and reads it from the real
console. Noninteractive use requires `--confirm '<exact abort phrase>'`.
`--abort --edn` without confirmation may return a read-only eligibility report,
but it must not delete anything.

`abort!` is a narrow cancellation before preparation, not a generic rollback.
It never deletes Datahike branches, rewinds main, retracts completion, removes
blob content, or guesses whether force happened.

### Required preconditions

Under the existing outer `:stack` lock, all conditions must be true in one
bounded attempt:

1. the canonical intent exists, validates, selects the CLI branch, and exactly
   derives the supplied abort phrase;
2. `process/read-process` for
   `:seon.dev.process/restore-admin` is absent under the existing
   `:restore-admin` lock, with no containment uncertainty or ownership
   conflict;
3. the intent-specific admin-result path is absent; a success, rejection, read
   error, or `effect/unknown` result all block abort;
4. a fresh typed lifecycle observation contains no completion with this intent
   id and its completion-id set/map agree on that absence;
5. neither the intent-derived undo branch `U` nor prepared target branch `P`
   appears in the complete branch roster or branch-coordinate map; and
6. no process is running with the intent's restore-only pod generation. In the
   expected pre-`U` path this is structurally impossible, but the explicit
   process check prevents deletion around corrupt/manual state.

Current main is deliberately **not** required to equal frozen `H`, and selected
target is not required to equal frozen `T`. Either may have advanced through an
ordinary authorized operation; that is why the plan became stale. No legitimate
restore admin can have run without first publishing both `U` and `P`, and those
branches are never automatically deleted, so their exact absence plus admin
process/result absence and completion absence is the no-effect proof.

Any missing, corrupt, uncertain, or contradictory evidence retains the intent
and returns typed `:seon.dev.restore.error/abort-unsafe`. In particular:

- `U` alone, `P` alone, or both present blocks abort;
- any completion for the intent blocks abort;
- any admin result, including effect unknown, blocks abort;
- a live, adoptable, foreign, or containment-uncertain admin record blocks
  abort; and
- a restore-generation pod blocks abort.

### Deletion and crash retry

After all preconditions pass:

1. delete the intent-specific blob materialization result if present and fsync
   its parent;
2. require the admin result still absent;
3. delete the canonical intent **last** and fsync the lifecycle directory; and
4. return an exact result containing intent id, plan digest, prior main/current
   main, selected target/current target when available, and
   `:seon.dev.restore.abort/aborted? true`.

Materialized content-addressed blob bytes remain in the append-only main
archive; deleting verified immutable content is neither necessary nor safe.
If a crash occurs before intent deletion, boot still sees the intent and retry
repeats the same eligibility proof. If intent deletion completed, ordinary boot
is authoritative. No phase or abort marker is retained.

Post-abort process readiness is separate from abort truth. Ordinary `bin/seon
up` reconciles main. If the failed attempt stopped the retained target pod, its
existing desired-open lifecycle record is reconciled through `branch restart`;
abort must not mutate or replace that record while deleting authority.

### Replan

Replan is just the ordinary read-only `plan!` after canonical intent absence.
It generates a new intent id and consumer generations and observes new `H`,
`T`, roster, blobs, and artifact. It never overwrites or edits a retained
intent and never carries the old plan digest forward. A stale-plan diagnostic
may display a candidate, but only a newly typed exact phrase authorizes it.

## Function and file ownership

| Owner | Exact work |
|---|---|
| `src/seon/dev/restore.clj` | Replace the permissive canonical leaf fallback; expose canonical bytes/digest; add closed plan/confirmation schemas and pure phrase derivation/validation. Keep intent and `next-command` as the one semantic state machine. |
| `script/seon/dev/restore_state.clj` | Extract current initial observation into read-only `plan!`; make `apply!` accept a validated plan plus phrase and publish only after fresh whole-value equality; make `resume!` require an already retained intent; add exact bounded `abort!` and eligibility result. Reuse lifecycle observation, admin invocation paths, and fsync state owner. |
| `script/seon/dev/process.clj` | Add one public typed `restore-admin-absence!`/status projection under the existing `:restore-admin` lock. It reuses the gated record and ownership evidence; no second registry, scan, signal, or cleanup path. |
| `script/seon/dev/cli.clj` | Parse interactive, `--plan --edn`, `--apply-plan PATH --confirm TEXT`, and `--abort --confirm TEXT`; own console prompting, presentation, bounded plan-file read, and outer `:stack` lock. Split first apply from `up`/`restart` prompt-free resume. |
| `test/seon/dev/restore_test.clj` | Canonical-byte parity fixtures, whole-plan/phrase relational tests, apply revalidation/mutation ordering, and abort fact matrix/crash cuts. |
| `test/seon/dev/cli_test.clj` | Exact parsing, real-console versus noninteractive refusal, no-effect wrong confirmation, plan-only output, already-retained resume, and competing-operation locks. |
| `test/seon/dev/process_test.clj` | Exact absent, live/adoptable, terminal-but-uncleaned, foreign, and uncertain restore-admin evidence. |
| `src/seon/db/protocol.cljc`, `src/seon/db/registry.clj`, `src/seon/db/writer.clj` | No semantic change expected. The current complete lifecycle observation already supplies branch heads/roster, completion facts/ids/transaction coordinates, and main parents. Change only if the implementation falsifies that sufficiency. |
| `docs/seon/issues/restore-intent-lacks-exclusive-writer-fence.md` | Close/archive only after focused and live accepted-write race proof plus public abort/replan proof. |

## Focused proof matrix

### Canonical and confirmation

- Different insertion orders for every map and set produce byte-equal canonical
  bytes and digest in Babashka and `:writer` JVM.
- Vector order changes the digest.
- Every supported leaf has an exact fixture; symbol, ratio, float, date,
  record, tagged literal, and sequence are rejected.
- Removing, adding, or changing each intent field changes the digest or fails
  schema. Changing only `plan-digest` fails revalidation.
- Exact phrase round-trips through EDN plan output; a prefix digest, abbreviated
  UUID, case change, added newline, double space, other branch, or other action
  fails before publication.
- Confirmation text is absent from canonical intent bytes and adding it as an
  intent key fails the closed schema.

### Plan and apply

- Plan performs no state-file write, process stop/start, blob materialization,
  branch create/delete, admin invocation, or database transaction.
- Missing confirmation and non-TTY interactive use perform no effect.
- Apply rereads and rejects independent drift in `H`, `T`, roster, blob digest,
  writer artifact, protocol version, and plan identity.
- Exact apply publishes intent before the first mutation.
- Crash immediately after publication is resumed by `up` without a prompt.
- An equal already-retained intent resumes; a different retained intent blocks.

### Accepted-write race

- Hold one already accepted UDS transaction across pod shutdown.
- If it commits before writer terminal evidence, the post-drain observation sees
  its new main head and no reserved branch is created.
- If it did not commit, writer shutdown proves it terminal before observation
  and the exact confirmed `H` remains valid.
- No timing produces `U` at an older head while main has advanced.

### Abort and replan

| Facts | Abort result |
|---|---|
| No intent | Reject; nothing deleted. |
| Exact intent, no `U/P`, no admin process/result/completion, main=`H` | Allowed explicit cancellation. |
| Exact intent, no `U/P`, no admin process/result/completion, main ordinary-advanced | Allowed stale-plan abort. |
| Target advanced or unrelated roster branch added, but no `U/P` or admin/completion | Allowed; those facts make the old plan stale but are not restore effects. |
| `U` only, `P` only, or both | Reject; converge or exact release later. |
| Any completion for intent | Reject even if process/result files are absent. |
| Any admin result, including unknown/rejected | Reject. |
| Live/adoptable/foreign/uncertain admin record | Reject. |
| Restore-generation pod exists | Reject. |
| Blob result only | Delete result, retain blob bytes, then delete intent. |
| Crash before intent deletion | Intent remains and abort retry re-proves all facts. |
| Crash after intent deletion | Ordinary boot; no abort marker or stale plan reuse. |

After successful abort, a new plan must have a different intent id and digest
and must reflect the new exact heads. Applying the old plan remains rejected.

## Live acceptance sequence

Use the authorized default cluster only after a coordinated source freeze:

1. create one proof-owned retained branch and record exact `H`, `T`, roster,
   artifact, and blob digest;
2. run plan-only and prove no durable/process/database change;
3. reject a wrong phrase and prove the same absence;
4. inject an accepted main transaction between confirmed intent publication
   and the drain, then prove no `U/P`, no admin process/result/completion, and a
   typed stale-plan result;
5. run exact abort, prove lifecycle directory fsync-visible intent absence and
   preserved blob bytes, restart ordinary main and the retained pod, and derive
   a different plan;
6. type the new exact phrase and run the existing crash-convergent restore
   matrix; and
7. retain the resulting completion/branches for the immediately following undo
   proof rather than cleaning evidence early.

The live gate fails if confirmation is inferred from a command name, if a stale
intent is silently replaced, if abort accepts any reserved/admin/completion
evidence, or if an accepted write can land after writer terminal proof.
