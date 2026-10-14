---
title: Errors cut, the occurrence stamps first
type: plan
date: 2026-10-14
status: design lane plan, for root
---

# Errors cut: the occurrence stamps first

**What exists.** `seon.error.refusal/error` is the one validating constructor. It merges `:seon.error/at (Date.)`
and `:seon.error/layer alternative`. Then `seon.error.refusal/diagnostic` calls `seon.error.refusal/attributed`,
which adds `:seon.error/world` (read ambiently through `current-world`) and `:seon.error/producer`. About 160
`diagnostic` callers and about 113 hand literals stamp `(java.util.Date.)` themselves. `seon.error.refusal/error?`
and about 480 readers recognize an error by `:seon.error/at`. `:seon.error/base` (`resources/seon/schemas/seon.error.edn`)
requires `:at`, `:layer` and `:operation`.

**Data flow after this plan.** A constructed error is a value of its inputs: operation, message and its variant
members, with `:seon.error/type` added by the constructor (and required at E7). The time, world and
producer of an *occurrence* are attributed where the occurrence is recorded: at the entrant boundary
(`seon.error/prepare`, `seon.sci.kernel/failure-value`), and at E5 by the recording transaction itself
(`:db/txInstant`).

**Principal approach.** First one predicate. Then one stamp at a time. The cut stops making each stamp, then deletes
it. This is no new mechanism. It is deletion in an order where HEAD boots after every slice.

**Relation to the spec.** `docs/seon/specs/errors.md` §7 plans one dependency-complete cut, and owner decision 9.7
backs that plan. This plan takes the parts of that cut that never touch the recorded signature out ahead of it: the
`at` and `world` stamps, `error?` and the readers. `seon.error/signature` hashes layer, operation, declared schema,
class, frame site, keys, path and producer. It never hashes time or world. So E1–E4 and E6 change no recorded
identity, and they need no reset. E5 and E7 change recorded entities, so they stay in the spec's cut, with its
reset. If root accepts this plan, §7 is revised in place to name E1–E6 as its preparatory slices. This lane edits no
spec.

## Why now

`docs/research/agent-platform/2026-10-14/render-reuse.md` defect 1: `seon.cluster.source/results-verdict` builds
every refusal with `seon.error.refusal/error`. Each refusal therefore carries a fresh `:seon.error/at` and the
reading evaluation's `:seon.error/world`. `seon.context/step-status` → `seon.context/step-verdict` puts those
refusals into the `:step` section. The block's bytes differ on every call, so neither content-keyed render reuse
nor the provider's prefix cache can hit. The test suite already works around this:
`seon.context-test/stable` dissocs `:seon.error/at` and `:seon.error/world` before it compares two reads. That
helper is a test-side fence, and E4a deletes it.

Probe, 2026-10-14, JVM mode on default (`result/e7478c19194bf`): two `refusal/error` calls on the same members,
**inside one evaluation and one millisecond**, are `=`. They differ only by `:at` and `:world` once either moves.
A valid construction costs 0.028 ms; a bare `diagnostic` costs 0.013 ms. The stamps are not a cost problem. They
are an identity problem.

## 1. Inventory

Method: `rg` over `src script resources` and `test`, then a line classifier (in the lane's scratchpad). The
classifier is a heuristic. The E2 rewrite-clj script gives the exact count when it runs, and it reports any site it
does not rewrite. Line counts are occurrences, not distinct functions.

| Key | Side | Identity test | Writes (stamp or literal) | Display | Recording / time | Schema | Prose |
|---|---|---:|---:|---:|---:|---:|---:|
| `:seon.error/at` | src+script | 431 (48 files) | 135 (44 files) | 3 | 6 | 8 | 7 |
| `:seon.error/at` | test | 92 (42 files) | 133 (49 files) | 1 | – | 1 | 3 |
| `:seon.error/layer` | src+script | 53 (16 files) | ~214 (86 distinct literal layers plus 11 `::` sites) | 9 | 1 (signature) | 12 | 5 |
| `:seon.error/layer` | test | 35 | ~130 | 19 | – | 1 | – |
| `:seon.error/world` | src | 2 | 1 | 0 | 1 | 2 | 0 |
| `:seon.error/world` | test | 0 | 0 | 0 | 0 | – | 3 (strips or excludes it) |
| `:seon.error/producer` | src | 9 | 4 | 0 | 21 (recorder, resolution) | 11 | 11 |

The `:seon.error/at` identity tests have five shapes:

- `(:seon.error/at x)` in a test position: 389.
- `(contains? x :seon.error/at)`: 36.
- The keyword used as a function, as in `filter`, `remove`, `some`, `not-any?` or `juxt`: 13.
- The three-key check `(and (map? x) (contains? … :at) (contains? … :layer) (contains? … :operation))`: 15 sites,
  in `seon.test` (8), `seon.sci.kernel/failure-value`, `seon.error.refusal/refusal`,
  `seon.error.refusal/error?` and `seon.test-support/refusal-data`.
- `seon.error.refusal/error?` itself: 38 callers.

The largest holders are `seon.test` (77), `seon.context` (36), `seon.cluster.source` (34), `seon.turn` (33),
`seon.fn` (27), `seon.error` (20), `seon.cluster` (18), `seon.task` and `seon.merge` (15 each), and `my.test` and
`seon.mcp` (11 each).

### The readers that are not identity tests, by symbol

| Symbol | Reads | Class | Fate |
|---|---|---|---|
| `seon.error/prepare` | `(:seon.error/at observation)` → `:seon.db.write.attempt/observed-at` | recording | E4b: take the recorder's own `at` argument (it already holds one) |
| `seon.error/fact-source` | `(or (:seon.error/at fact) (Date.))` | recording (stored fact) | E5: the transaction's `:db/txInstant`; the `(Date.)` fallback goes |
| `seon.error/latest-fact` | the occurrence's `:seon.error/at` | recording (stored) | E5 |
| `seon.error/resolution` | sorts occurrences by `:seon.error.occurrence/last-at` | recording (stored) | E5 |
| `seon.error/recording`, `seon.schedule/error-request`, `seon.turn/error-tx`, `seon.fault/record!` | stamp the recorder's own time onto the request and fact | recording | already correct in kind: the recorder's time, not the value's. E5 replaces it with `:db/txInstant` |
| `seon.db/loaded-builder` | `(id/digest 64 [::mixed-program (str (:seon.error/at loaded)) …])` | **identity built over a timestamp: a defect** | E4b: digest the priors' commit ids only |
| `seon.sci.kernel/failure-value` | `own?` = `(select-keys % [:at :operation])` equality | occurrence identity | E4b: the link whose `:seon.error/data` is the `existing` value (`=`) |
| `seon.mcp/call` | `(:seon.error/at value)` picks the printed branch | identity | E2 |
| `seon.health/unit-summary`, `seon.health/unit-section` | `(:seon.error/at value)` → shows message | identity, then display | E2 |
| `seon.render.transcript/receipt-selector` | pulls `:at :layer :operation :data-edn` | display (stored) | E6 drops `:at`; E7 drops `:layer` |
| `seon.error/classifying-error-keys` | lists `:at :layer` as classifying | display of over-bound evidence | E6 / E7 |
| `seon.dev.context-corpus/noise-keys` | excludes `:at :layer` as noise | a dev script's workaround | E6 |
| `seon.test.runner/report-occurrence-attributes` | its comment: a rerun stores another blob because the thrown value "carries its own `:seon.error/at`" | storage growth caused by the stamp | E4b: same claim → same blob; re-judge whether `:seon.test.failure/actual-blob` still needs to be an occurrence attribute |
| `seon.error/signature` | `(:seon.error/layer observation)` | recording identity | E7, with the signature rewrite (reset) |
| `seon.error/commit-call` | `(:seon.error/world (:seon.error/source request))` | recording | unchanged: the source is `prepare`'s attributed observation (below) |
| `seon.batch/produced-failures` | `(:seon.error/producer error)` of the kernel's boundary value | recording | unchanged: `seon.sci.kernel/failure-value` attributes at the boundary |
| health schema `:seon.health/program-unknown`, problems `:open-fault` (`:seon.health/at :seon.error/at`), `:seon.problems/error-signature`, `:seon.maintenance.result/collection-error` | require or read `:seon.error/at` on stored facts | recording (stored) | E5 |

**Who reads the value's time?** Only three readers read the *value's* time: `prepare`, `loaded-builder` and
`failure-value`'s `own?`. Each one is either redundant (the recorder holds its own time) or a defect (time used as
identity). About 274 sites stamp a time that nobody legitimately reads: 113 literals, about 159 positional
`diagnostic` calls and the 2 funnels.

**Who reads the value's world?** Only `attributed` reads it, and only to skip re-attribution. The occurrence's world
comes from `prepare` → `attributed`, which runs in the recording evaluation's world.

## 2. The target value

```clojure
;; constructed (returned or thrown): a value of its inputs
{:seon.error/type      :seon.source/test-evidence-error   ; added by the constructor; required at E7
 :seon.error/operation 'seon.cluster.source/results-verdict
 :seon.error/message   "Every proof must be a member of the named run."
 :seon.error/offending [...]                               ; variant members
 :seon.test.run/branch .. :seon.source/refused-test-run ..}
```

| Member | Today | After | Where it lives instead |
|---|---|---|---|
| `:seon.error/at` | on the value (constructor, `diagnostic` positional argument, literals) | gone (E4); dropped from base (E6) | the occurrence: the recorder's `at` argument until E5, then the recording transaction's `:db/txInstant` |
| `:seon.error/world` | on the value (`attributed` in `diagnostic`) | gone (E1) | the occurrence: `seon.error/prepare` → `attributed` at recording, and `seon.sci.kernel/failure-value` at the SCI boundary. Both run in the recording evaluation's world. |
| `:seon.error/producer` | on the value when a throwable names one (`attributed` in `diagnostic`); it is dropped when it equals the operation | gone from constructed values (E1); kept on boundary values | the boundary's `attributed`, which is the blame rule's only correct place, since only the boundary sees the cause. Instrumentation's `:seon.error/caller` and producer digest stay on contract errors. |
| `:seon.error/layer` | on the value | stays through E6 (it is a constant per site, so it does not hurt reuse); goes at E7 | the type's namespace (spec §1) |

## 3. Slices

Each slice is serial and leaves HEAD booting. "Host-bound" means the slice commits through
`bin/lane commit LANE -m MSG --test NS… PATHS` (`bin/seon check`). "Restart batch" means a schema resource changes:
the slice is proven by publish and reload on a branch of the live store, then joins root's next batched restart.

### E1. The constructor stops attributing (world and producer)

- **Change.** `seon.error.refusal/diagnostic` stops calling `attributed`. It keeps `references`, the chain, the
  frame and the root message. `attributed` stays for its two boundary callers, `seon.error/prepare` and
  `seon.sci.kernel/failure-value`. `world-reference` and `current-world` are then reached only from `attributed`.
  Rewrite the `error` docstring sentence "the calling world and producer are attributed".
- **Files.** `src/seon/error/refusal.clj`, `test/seon/error/refusal_test.clj`, `test/seon/error_test.clj` (the
  `(dissoc intermediate :seon.error/world)` at the `check` case becomes a plain compare).
- **Red proof.** `seon.error.refusal-test/a-constructed-error-is-a-value-of-its-inputs-but-time`. Build two errors
  with `seon.error.refusal/error :seon.source/test-evidence-error` under two different `seon.batch/*world*`
  bindings (different agent, turn and ordinal). Assert `(not (contains? a :seon.error/world))` and
  `(= (dissoc a :seon.error/at) (dissoc b :seon.error/at))`. This is red today.
- **Reaching tests.**
  - `seon.error.attribution-test`: `blame-follows-the-producer`, `a-fault-in-an-agents-world-is-copied-to-the-cluster`
    and `only-a-compiled-producer-not-redefined-by-the-batch-is-a-fault`. The recorded occurrence still carries
    world and producer, through `prepare`.
  - `seon.error.refusal-test`: `every-producer-references-the-worlds-it-was-handed`,
    `constructor-output-keeps-the-producing-contract` and `a-world-is-projected-once-per-walk`.
  - `seon.error-test`.
- **Restart batch.** No; host-bound only.
- **Task.** It advances `seon.error/error-transactions-carry-references-never-worlds`.

### E2. One predicate: every identity reader calls `seon.error.refusal/error?`

- **Change.** One rewrite-clj script over `src script test` rewrites the shapes of §1 into
  `(seon.error.refusal/error? x)`, adds the require where it is missing, then lints. The definition of `error?` does
  not change.
  - `seon.error.refusal` is a leaf namespace (it requires only `seon.id`), so there is no load cycle.
  - The `.cljc` files (`seon.program`, `seon.sci.reader`) use it under `:clj`. CLJS is dead (R28).
  - The script skips the enumerated non-identity readers of §1 by symbol, and prints every site it leaves.
- **Behavior.** None changes. The slice can land in several commits, one per file group (src core, cluster and
  turn, test), because each commit is behavior-neutral. The sweep is still one script over the full set.
- **Red proof.** `seon.error.refusal-test/only-the-predicate-asks-whether-a-value-is-an-error`. It reads the
  clj-kondo `:keywords` analysis of `src script` (the analysis the lint already runs). It asserts that no usage of
  `:seon.error/at` or `:seon.error/layer` in a reading position lies outside `seon.error.refusal` and the named
  recording owners. This is red today: about 480 usages. It is the same kind of check as the atom lint (ledger §3.19).
- **Reaching tests.** Every namespace the script touches. Name them in the commit. The script prints them.
- **Restart batch.** No; host-bound.
- **Also.** `seon.test-support/refusal-data` moves to `error?`. That is the first half of
  `seon.test-support/refusal-data-recognizes-errors-by-at-and-layer`.

### E3. `error?` recognizes an error without the stamps; base makes `at` optional

- **Change.**
  - `seon.error.refusal/error?` becomes `(and (map? v) (qualified-symbol? (:seon.error/operation v)))`. Operation
    is required today and in the target, and it is the one member every error already carries. This is a strict
    superset of today's at+layer+operation test, so no error stops being recognized.
  - `seon.error.refusal/refusal` uses `error?`.
  - `diagnostic`'s input schema makes `:at` and `:layer` optional.
  - `:seon.error/base` marks `[:seon.error/at {:optional true}]` and `[:seon.error/layer {:optional true}]`
    (accretion: require no more).
- **Check before committing.** Look for maps that carry `:seon.error/operation` but are not errors. Two are known,
  and both are summaries of an error: `seon.health/unit-summary` (operation and message) and
  `seon.render.value/reply-value`. The lane reads each of their consumers.
- **Files.** `src/seon/error/refusal.clj`, `resources/seon/schemas/seon.error.edn`, `test/seon/test_support.clj`,
  `test/seon/error/refusal_test.clj`.
- **Red proof.**
  - `seon.error.refusal-test/helpers-build-recognize-and-short-circuit-on-errors` gains a case:
    `(error? {:seon.error/type :x.y/z-error :seon.error/operation 'x.y/z :seon.error/message "m"})` is true, and
    `let-ok` short-circuits on it. Red today.
  - `seon.test-support-test/refusal-data-recognizes-a-typed-error`, which the task already names, holds the same
    for `refusal-data`.
- **Reaching tests.** `seon.error.refusal-test`, `seon.test-support-test`, `seon.sci.kernel-test`,
  `seon.schema-test` (base publication), `seon.cluster.publication-declared-schema-test`.
- **Restart batch.** Yes: schema resource. Publish and reload it on a live-store branch.

### E4a. The funnels stop stamping time (the acceptance slice)

- **Change.**
  - `seon.error.refusal/error` merges only `{:seon.error/layer alternative}`. Layer stays a constant until E7.
  - `construction-refusal` stops stamping `(java.util.Date.)`.
  - Delete `seon.context-test/stable` and assert the raw value.
- **Files.** `src/seon/error/refusal.clj`, `test/seon/context_test.clj` (currently modified in the working tree by
  another lane, so root sequences this after it), `test/seon/cluster/source_test.clj`.
- **Red proof: the acceptance of §4.**
- **Reaching tests.**
  - `seon.context-test/broken-tests-show-with-their-failures`, now without `stable`.
  - The `seon.context-test` step-status table.
  - `seon.cluster.source-test`, `seon.error.refusal-test`, `seon.cluster.merge-test` (the merge verdict refusals).
- **Restart batch.** No (E3 already made `at` optional); host-bound.

### E4b. Every other producer stops stamping time

- **Change.**
  - The script deletes the `:seon.error/at` entry from every error map literal (about 113 in src, about 133 in
    test).
  - It rewrites the positional `(diagnostic at layer operation members)` arity, about 159 callers, to the map
    arity, and it deletes the 4-arity.
  - clj-kondo then reports the `at` locals that are now unused. Remove them by hand: `(let [at (java.util.Date.)]`
    bindings whose only use was the stamp.
  - The hand fixes of §1:
    - `seon.error/prepare` takes the recorder's own `at`.
    - `seon.db/loaded-builder` drops the timestamp from its digest.
    - `seon.sci.kernel/failure-value`'s `own?` compares the link's data to `existing`.
    - Correct the `seon.test.runner/report-occurrence-attributes` comment, and test whether a rerun of the same
      claim now stores one blob.
- **Red proof.**
  - `seon.error.refusal-test/no-producer-stamps-a-time`. It extends E2's kondo check: no `:seon.error/at` key
    appears in any map literal or `assoc` in `src script` outside the recording owners. Red today: about 113.
  - `seon.test.runner-test/a-rerun-of-one-claim-stores-one-blob`. Red today, per the runner's own comment (fault
    `93e4a1e2`).
- **Reaching tests.** Every touched namespace, which the script prints. Also `seon.cluster.boot-test`
  (`loaded-builder`), `seon.sci.kernel-test` and `seon.test.runner-test`.
- **Restart batch.** No; host-bound. It touches boot (`seon.db/loaded-builder`), so the archive boot inside
  `bin/seon check` is the proof.
- **Split.** If the script's output exceeds one 15-minute lane, split it by file group. Each group is
  behavior-neutral once E3 is in.
- **Task.** It retires most of `seon.error.refusal/hand-built-error-maps-remain-across-the-namespaces` (the stamp
  half; the typing half is E7).

### E5. The occurrence's time comes from the recording transaction (joins the spec's cut)

- **Change.**
  - The recorder stops writing `:seon.error/at` on the error fact and `:seon.error.occurrence/last-at` on the
    occurrence.
  - Readers take the time of the transaction that asserted the datom: the `?tx` of the
    `[?o :seon.error/occurrence-count _ ?tx]` datom in history, then `[?tx :db/txInstant ?t]`. This is Datahike's
    time, with no stamp.
  - The readers are `seon.error/latest-fact`, `seon.error/resolution`, `seon.error/fact-source` (delete the
    `(Date.)` fallback), the problems `:open-fault` `:seon.health/at`, `:seon.health/program-unknown` and
    `:seon.maintenance.result/collection-error`.
  - The request schemas `:seon.error/normalize-request` and `:seon.error/commit-tx-request` drop `:at`. The recorder
    entrants (`seon.schedule/settle-call`, `seon.fault/record!`, `seon.fault/fault!`,
    `seon.cluster.boot/stand-cluster-runtime!`, `seon.cluster/development-source-refresh!`,
    `seon.turn/refusal-terminal-data`, `seon.turn/settle-batch-refusal!`, `seon.turn/record-attempt!`) stop passing
    `at`/`now`.
- **World by reference (owner, 2026-10-14).** The occurrence records where it happened as references, never a copy:
    the evaluation it happened in (which already carries agent, branch, namespace and basis t) and the commit id. From
    them the failure's world is reproducible: Datahike's value exactly (`seon.db/commit-database`), and the agent's
    SCI program context exactly (the base context is a pure function of the branch's program content at that commit,
    86430115b); only the agent's private live state (atoms, host objects, result handles) is not, by design. This
    replaces `:seon.error/world` on recorded facts as well as on values.
- **Red proof.** `seon.error-test/an-occurrences-time-is-its-transactions`. Record one failure twice, then assert
  that `latest-fact`'s time `=` the `:db/txInstant` of the second recording transaction, and that no
  `:seon.error/at` datom exists on the entity.
- **Reaching tests.**
  - `seon.error-test`, `seon.error.resolution-test`, `seon.fault-test`.
  - `seon.cluster.fault-storage-test`, `seon.cluster.fault-message-test`, `seon.problems-test`, `seon.health-test`.
- **Restart batch.** Yes, plus a reset. Recorded errors are not migrated (§9.6).
- **Spec.** This replaces the spec §6 entity's stored `latest-at`/`opened-at` by derivation (CLAUDE.md: "history /
  as-of for time … no stamps"). Root decides whether E5 lands alone or inside the cut's §6 entity rewrite, which
  resets anyway. Recommended: inside the cut, so default resets once.

### E6. Delete `:seon.error/at` from the error value's grammar

- **Change.**
  - `:seon.error/base` drops the `:seon.error/at` member (the top-level `:seon.error/at :inst` attribute stays
    until E5 retires its last stored use).
  - `diagnostic`'s contract drops it.
  - `classifying-error-keys`, `seon.render.transcript/receipt-selector` and `seon.dev.context-corpus/noise-keys`
    drop it.
  - The two task intents that cite it (`seon.sci.eval`, `seon.sci.lookup`) are retired or reworded.
- **Red proof.** E2's kondo check tightens to zero `:seon.error/at` usages in `src script` outside the recorder
  (before E5) or zero overall (after E5).
- **Reaching tests.** `seon.schema-test`, `seon.error.refusal-test`, `seon.render.transcript-test`,
  `seon.cluster.publication-declared-schema-test`.
- **Restart batch.** Yes: schema resource.

### E7. Type is the identity; layer leaves; `(error type operation message data)` and `fail!` (the spec's P1–P3 and cut)

This slice is unchanged from `docs/seon/specs/errors.md` §7, and only its order and size are restated here.

- **P2 arities (invisible).** Add `[type operation message]` and `[type operation message data]`, then `fail!`.
  Proofs: T8 and T12.
- **P3 typing, per owner family.** There are 86 distinct literal layers and 11 `::` layers across about 214 sites,
  237 `diagnostic` calls, and 25 typed `refusal/error` calls. The S2 notes' open ruling (sites whose output
  declares only base or several alternatives) still needs root's answer before P3 starts. Recommended: every such
  function gains a named variant in its owner's EDN, and base is never a type.
- **Cut.**
  - `error?` becomes `(qualified-keyword? (:seon.error/type v))`. After E2 that is a one-line change.
  - Base requires type, operation and message, and drops layer.
  - `seon.error/signature` hashes type in place of layer (reset).
  - One script converts the two-argument callers.
- **Restart batch.** Yes, plus a reset.

## 4. Acceptance for the reason we are doing it now (E4a's red proofs)

1. **`seon.cluster.source-test/a-verdict-is-a-value-of-its-inputs`.** Hold one `candidate`, one `main` and one
   `judged` value that yield at least three refusal kinds: test evidence, red test and uncontracted (the
   `seon.context-test` step table already builds such inputs). Call `seon.cluster.source/results-verdict` twice,
   under two different `seon.batch/*world*` bindings and at least 2 ms apart. Assert that the two refusal vectors
   are `=`, and that `(not-any? #(contains? % :seon.error/at) v)` and
   `(not-any? #(contains? % :seon.error/world) v)` hold. Red today: `:at` differs, and after E1 alone it still
   differs.
2. **Two renders of the same block print identical bytes.**
   - `seon.context-test/broken-tests-show-with-their-failures` with `stable` deleted: its two block reads are `=`.
   - The S3d case that render-reuse.md R0 names: two `seon.context/model-context` calls on one branch value give `=`
     `:seon.ai/prompt` strings.
   - Both are red today. render-reuse.md measured the first prompt difference at character 5,036, at `:seon.error/at`.
   - If either still differs after E4a, the next difference is a new producer defect. Record it as a task on its
     producer. Never strip it in the test.

## 5. Dropped or simplified instead of converted

- **`attributed` on the construction path (E1).** Today every construction pays a blame-rule walk and an ambient
  `find-ns`/`ns-resolve` world read. The boundary repeats that walk anyway. Afterward only the two boundaries
  attribute.
- **The positional `diagnostic` arity (E4b).** Its first parameter exists only to carry the stamp, so it is deleted,
  not converted. Its about 159 callers drop their `at` local. Test `positional-construction-evaluates-each-input-once-in-order`
  goes with it.
- **`construction-refusal`'s stamp (E4a)** and its whole-stack chain. The probe `result/e6399a7f4050c` showed one
  malformed construction carrying 40 JVM frames, at about 1.2 ms and about 1.2 MB each. That is
  `seon.error.refusal/errors-capture-whole-stack-traces`. The spec already folds the stamp into the literal
  `malformed` at E7. E4a only removes the stamp.
- **Test-side fences.** `seon.context-test/stable`, the `(dissoc … :seon.error/world)` in `seon.error-test`, the
  `select-keys … [:at :layer :world]` empty check in `seon.error-test`, and the 5 test `dissoc :seon.error/at` sites
  become plain equality.
- **`seon.error/fact-source`'s `(or (:seon.error/at fact) (Date.))` (E5).** Inventing a time when none was stored
  is a fallback. The transaction's time always exists.
- **`seon.db/loaded-builder`'s timestamp digest (E4b).** An identity must be a structural digest, so a timestamp
  never enters it.
- **The runner's per-rerun blob (E4b).** Once the thrown value has no time, a rerun of the same claim stores the
  same blob. The storage growth stops, and an occurrence attribute may go.
- **The three-key checks (E2).** All 15 become `error?`, including `seon.sci.kernel/failure-value`'s, whose
  in-code "debt:" comment goes with it.
- **The volatile noise list (E6).** `seon.dev.context-corpus/noise-keys` loses `:at` and `:layer`. That list exists
  only because of the stamps.

## Order and owners

E1 → E2 → E3 → E4a → E4b → E6 are serial. Each one boots, and only E3 and E6 need a restart batch.

- **E4a is the earliest point** at which render reuse (S3d, R1/R2) can prove its determinism case. It depends on E3,
  because E3 is where base stops requiring `at`.
- **Models.** E1, E3 and E4a are small Opus or Sonnet slices on `seon.error.refusal`, which is platform code. E2 and
  E4b are Sonnet script sweeps. Each needs a Haiku pre-brief of the touched files and of the reds on HEAD.
- **Restart batches.** E5 and E7 belong to the spec's cut and its reset.
- **File collision.** `test/seon/context_test.clj` and `src/seon/turn.clj` currently carry uncommitted changes from
  another lane, so root sequences E4a's test edit and E5's `seon.turn` edits after that lane commits.
