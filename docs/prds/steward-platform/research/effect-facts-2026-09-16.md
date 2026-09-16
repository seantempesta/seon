---
type: research
status: active
date: 2026-09-16
tags: [research, schema, database, effect, edit, program-graph]
---

# Effect facts and write-back provenance — landing note

Read end to end before implementing: `AGENTS.md`; the effects research
[effects-and-write-back-2026-09-16.md](effects-and-write-back-2026-09-16.md);
the program-provenance landing note
[program-provenance-2026-09-16.md](program-provenance-2026-09-16.md); the
`data-modeling`, `datahike`, `data-oriented-clojure`, `clojure-testing` and
`repl` skills. Option **C** of the research, implemented end to end, with three
deviations recorded below.

Commit: `0e15593aa`, path-limited, on `steward-platform`.

## What landed

### 1. Refs replace symbols and a hand-rolled join key

| attribute | value | where it is written |
|---|---|---|
| `:seon.fn/capability-fn` | ref to the handler's own declaration | `src/seon/fn.clj` `var-row`, beside the `:seon.effect/capability` symbol |
| `:seon.effect/capability-fn` | ref to the same declaration | `seon.effect/open-call`, read off the owner row |
| `:seon.effect/eval` | ref to the `:seon.cluster.eval` entity | `seon.effect/open-call`, resolved from the run and the form ordinal |

`:seon.effect/eval` is resolved AT THE WRITER: `evaluation-eid` pulls the run's
`:seon.turn/id`, derives `(seon.id/evaluation turn-id ordinal)` and resolves it,
so nothing crosses the writer boundary that the writer cannot re-derive.

### 2. A request and its result are datoms, derived not rostered

`seon.effect/declared-datoms` records every entry of the admitted request map
(at `open-call`) and of the admitted result map (at `settle-call`) whose
attribute THIS BRANCH has installed. The question is asked of the database
through the new `seon.db/attribute-installed?`, because the database is the
authority on what can be a datom: a projection may map an attribute the bridge
could store while the branch never installed it, and transacting that attribute
refuses.

Which keys are facts is therefore a SCHEMA decision, not a writer decision: the
per-key `:seon.db/index true` markers now on `:my.fs/path`, `/before-digest`,
`/after-digest`, `/bytes-written`, `/changed?`, `/created?` and on
`:my.edit/path`, `/expected-digest`, `/operation`, `/changed?`,
`/before-digest`, `/after-digest`, `/replacements`, `/from-line`, `/to-line`.
A capability declared tomorrow records its arguments the day its schema marks
them, with no writer change. `:seon.effect/request-edn` and `/result-edn` are
untouched: they remain the exact admitted text.

### 3. Write-back provenance

`seon.edit` computed the exact changed region for every operation and
`seon.edit.jvm` dropped it, keeping only human line numbers
(`src/seon/edit/jvm.clj:51` before this commit). Now:

- `seon.edit/byte-span` converts the operation's Java char indices into
  half-open UTF-8 byte offsets — the unit `:seon.fn/form-span` uses — and all
  three operations (`form`, `exact`, `lines`) merge `:seon.edit/form-span`;
- `seon.edit.jvm` reports `:seon.effect/provenance` (canonical absolute path +
  span) to the writer. It never reaches the agent: `settle-value!` removes it
  from the handler's value exactly as it removes `:seon.blob/staged-writes`;
- `seon.effect/write-back-adds` derives, at the database that owns the
  declarations, `:seon.effect/file` (ref to `seon.fn.file` by canonical path),
  `:seon.effect/form-span`, and `:seon.effect/program` (the declaration whose
  span contains the written region).

`seon.program/declaration-at` is the one containment rule, lifted out of the
lint attribution in `seon.fn` (`src/seon/fn.clj:952` now calls it). **It never
answers nil**: a position inside no declaration comes back as a flat
`:seon.program/no-declaration-at` value naming the position and the number of
spanned declarations examined. That is the absence-as-health killer the
research asked for — a merge that finds no program ref reads a refusal, not
silence.

## Measured, live, on `default` (pid 45917), jvm mode, explicit custody

**Unit conversion (the whole correctness surface).** Source
`(def caffè "caffè")\n(defn foo [] 1)\n`, replacing `foo`:

| | value |
|---|---|
| `[:seon.edit/start :seon.edit/end]` (char indices) | `[20 35]` |
| `:seon.edit/form-span` (UTF-8 bytes) | `[22 37]` |
| bytes `22..37` of the new source | `(defn foo [] 2)` — byte-identical to the submitted source |

Two non-ASCII characters above the edit move the span by exactly two bytes.
Recording the char indices would have been a silent join bug on any file with a
non-ASCII character.

**Containment, against the live program graph.** `my.edit/form!` has
`:seon.fn/form-span [1265 2317]` in `/Users/sean/src/seon/src/my/edit.clj`
(3 spanned declarations):

| position | `seon.program/declaration-at` |
|---|---|
| `1265` (start) | `my.edit/form!` |
| `2316` (end - 1) | `my.edit/form!` |
| `2317` (end) | `:seon.program/no-declaration-at` — half-open, as declared |
| `0` | `:seon.program/no-declaration-at`, "No declaration span contains byte 0." |

**Schema adoption.** All ten new attributes install on `default`:
`:seon.effect/eval`, `/capability-fn`, `/file`, `/form-span`, `/program`,
`:seon.fn/capability-fn`, `:my.fs/path`, `:my.edit/path`, `/operation`,
`/after-digest` — verified with `seon.db/attribute-installed?`, itself adopted
in the same publication. Before this change NONE of the `my.fs`/`my.edit`
request or result keys were installed attributes, which is why the research's
"ask the projection which keys are storable" had to become "ask the database
which keys are installed": `seon.schema.datahike/storable-attribute-in?`
answered `true` for `:my.fs/path` while the branch held no such attribute.

**Indexer.** After `bin/seon init --dev default`, `:seon.fn/capability-fn` has
exactly **10 holders** — the ten capability owners — and
`my.edit/form!` pulls `{:seon.fn/capability-fn {:seon.fn/sym
"seon.edit.jvm/edit" :seon.fn/form-span [3034 4455]}}`.

**One live `my.edit/form!` effect, end to end.** A virtual turn on `default`
(agent `effect-facts-probe`, turn `effect-facts-probe-turn`, evaluation
ordinal 0), a real fixture file indexed with the production `build-artifact`,
and `my.edit/form!` replacing `(defn subject [] 1)` with `(defn subject [] 2)`.
The effect pulls as one walk:

```clojure
{:seon.effect/id      "2c022892d197"
 :seon.effect/owner        {:seon.fn/sym "my.edit/form!"}
 :seon.effect/capability-fn {:seon.fn/sym "seon.edit.jvm/edit"}
 :seon.effect/eval    {:seon.cluster.eval/ordinal 0
                       :seon.cluster.eval/run
                       {:seon.turn/id "effect-facts-probe-turn"
                        :seon.turn/agent {:seon.agent/id "effect-facts-probe"}}}
 :seon.effect/file    {:seon.fn.file/path ".../fixture_subject.clj"
                       :seon.fn.file/digest "75e9be59eac9d9…"}
 :seon.effect/form-span [47 66]
 :seon.effect/program {:seon.fn/sym "fixture-subject/subject"
                       :seon.fn/form-span [47 66]}
 :my.edit/path ".../fixture_subject.clj"
 :my.edit/operation :replace
 :my.edit/changed? true
 :my.edit/from-line 3
 :my.edit/before-digest "75e9be59eac9d9…"
 :my.edit/after-digest  "6a710db594a97b…"
 :seon.effect/duration-ms 178}
```

Bytes `47..66` read back off disk are exactly `(defn subject [] 2)`. The
fixture's heading comment contains `caffè`, so these byte offsets are two
greater than the char indices the edit used — the conversion is doing work in
this very proof. The agent's own result carried no `:seon.effect/provenance`.

**The comment edit.** `my.edit/exact!` replacing text inside the heading
comment records `:seon.effect/file` and `:seon.effect/form-span [10 22]` and
**no `:seon.effect/program`** — no declaration contains a comment, and none is
fabricated.

**Roadmap E2, live.** After those edits,

```clojure
(seon.db/q '[:find [?sym ...] :in $ ?basis
             :where [?effect :seon.effect/program ?d ?t] [(> ?t ?basis)]
                    [?d :seon.fn/sym ?sym]]
           db basis)
;; => ["fixture-subject/subject"]
```

— which declarations a worker changed, with no file read and no source
comparison. The probe's seven entities were retracted by id list and its
directory removed.

## Deviations from the research, and why

1. **`:seon.effect/run` and `:seon.effect/form-ordinal` are NOT retired.** The
   research states `rg` finds them only in `seon.effect` and `seon.background`.
   That is wrong: `src/seon/turn.clj:1747` (through
   `seon.effect/interruption-stamps`) and `src/seon/turn.clj:2005-2006`
   (`read-only-evaluation?`) both read them, and `src/seon/turn.clj` was
   concurrently edited and explicitly protected for this lane. Retiring them
   would have broken a namespace I may not touch. `:seon.effect/eval` landed
   beside them and the retirement is
   [its own issue](../../../seon/issues/effect-run-and-form-ordinal-duplicate-the-evaluation-ref.md).
   `src/seon/background.clj` reads neither, so it needed no conversion.
   The §4 identity respelling was skipped with them: the research marks it
   optional and it only pays off once the parts are gone.
2. **`:seon.effect/eval` is recorded when the evaluation entity exists, not
   always.** `receipt-start-call` opens the evaluation before the form runs, so
   every request from a real turn has one; several existing fixtures across
   eight test namespaces construct a turn and no evaluation. Refusing there
   would have been correct and wide. The absence is not silent — `/run` and
   `/form-ordinal` still name the evaluation — and making it mandatory is in
   the same issue.
3. **Nested map arguments stay in `:seon.effect/request-edn`.** The research
   asks for `:my.fs/precondition` as a component. Its declaration is
   `[:and [:map …] [:fn seon.fs/write-precondition?]]`, and the predicate is
   what refuses a write carrying both fences or neither; redeclaring the
   attribute as a component ref deletes that from the request contract, and a
   second `:seon.effect/…` spelling is the per-capability mapping the derived
   writer exists to remove. Priced for the owner in
   [its own issue](../../../seon/issues/capability-component-arguments-stay-in-request-edn.md).
4. **`changed-programs-since-basis-is-a-query` lives in `seon.edit-test`, not
   `seon.program-test`.** The regression needs real edits on a real indexed
   file, which is where the edit fixtures are.

## Regressions

All five of research §5, in existing namespaces:

| test | namespace | asserts |
|---|---|---|
| `effect-request-lands-declared-attributes` | `seon.effect-test` | a real `my.fs/write!` through the effect seam leaves `:my.fs/path`, `:my.fs/created?`, `/after-digest`, `/bytes-written` as datoms, and the exact EDN still rides its own attributes |
| `effect-refs-its-evaluation-and-handler` | `seon.effect-test` | one pull walks effect -> evaluation -> turn -> agent, and `:seon.effect/capability-fn` is `seon.fs.jvm/write` |
| `form-edit-records-its-span-in-utf8-bytes` | `seon.edit-test` | the non-ASCII byte round trip, and that char and byte spans differ; all three operation shapes record a span |
| `form-edit-refs-the-program-entity-it-changed` | `seon.edit-test` | a real indexed fixture file (built with `seon.fn/build-artifact`, not hand-rostered rows) edited through `my.edit/form!` yields `:seon.effect/program`; an edit in a comment records file and span and NO program; `declaration-at` names the position it refused |
| `changed-programs-since-basis-is-a-query` | `seon.edit-test` | two edits on two declarations, then exactly those two come back from `[?effect :seon.effect/program ?d ?t]` after the basis `:t`, with no file read |

**Verification boundary — read this before trusting the slice.** The four
database regressions were NOT run in process. `seon.test-support/database-base`
on `default` (pid 45917) was realized before this schema change, so that shared
base holds none of the new attributes; a pull of `:seon.fn/capability-fn`
against it answers `Bad entity attribute … not defined in current schema`.
Rebuilding the shared delay is another lane's business by the base-construction
rule, so these are gate-only: a fresh worker JVM builds a base from the current
schema. What IS proven in process is the pure half — the byte-span conversion
and the containment rule, both measured above against the live JVM and the live
program graph. Gate request:
`tmp/orchestrator/gate-requests/effect-facts.txt`.

## Open at hand-off

- **No RESET NEEDED.** Every schema change here is accretion; adoption on
  `default` succeeded in place, schema and rows.
- **A foreign half-edit is blocking the publication RETRY.**
  `bin/seon init --dev default` completed its adoption (reload, SCI
  acquisition, JVM instrumentation, program rows) and then reported
  `development source changed; retrying adoption once`; that retry refused with
  `Static program analysis found blocking errors` —
  `test/seon/test_failure_facts_test.clj:166`, "seon.problems/problems is
  called with 1 arg but expects 2" (`:invalid-arity`). That file and
  `src/seon/problems.clj` belong to another lane and were uncommitted at the
  time. It is not this slice's breakage, but every publication on this tree
  refuses its retry until that lane lands. Reported, not worked around.
- The four database regressions are gate-only; see the verification boundary
  above.
