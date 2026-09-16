---
date: 2026-09-16
lane: R3
status: research — design only, no source edits
---

# R3 — effects as a connected graph, and write-back provenance

Read end to end: `AGENTS.md`;
[namespace-data-model §7.3 rows 10 and 14, §8.2–8.3](../plan/namespace-data-model-2026-09-16.md);
[roadmap §E and §F](../plan/README.md);
[complex issues as schema §3b accretions](complex-issues-as-schema-spec-2026-09-16.md);
`src/seon/effect.clj`, `src/seon/background.clj`, `src/seon/edit.clj`,
`src/seon/edit/jvm.clj`, `src/my/edit.clj`, `src/my/fs.clj`,
`resources/seon/schemas/seon.effect.edn`, `my.edit.edn`, `my.edit.form.edn`,
`my.fs.edn`, `seon.fn.edn`, `seon.fn.file.edn`.

Two read-only `jvm`-mode probes on `default` with explicit custody
(`(seon.operator/connection "default")`), 80 ms and 23 ms; nothing started,
stopped or reforked. Forms in
[`effects-and-write-back-probe-2026-09-16.clj`](effects-and-write-back-probe-2026-09-16.clj).

## 1. The effect entity today

`resources/seon/schemas/seon.effect.edn` declares 16 storable attributes plus
three request/receipt map shapes. Grouped by what they actually are:

| group | attributes | note |
|---|---|---|
| identity | `:seon.effect/id` | `(id/digest 12 [::id turn-id eval-ordinal effect-ordinal])`, `src/seon/effect.clj:637` |
| refs | `:seon.effect/run` → turn, `/owner` → `seon.fn`, `/notify` and `/to` → agent | `owner` is **already** a ref to the capability function's program entity, `src/seon/effect.clj:646` |
| ordinals | `/form-ordinal`, `/ordinal` | `form-ordinal` is the evaluation's ordinal carried as a bare int |
| **EDN text** | `/request-edn`, `/result-edn` (both `:seon.db/no-history? true`) | the whole request map and the whole result map, `admit/canonical-edn`, `src/seon/effect.clj:642` and `:406` |
| blobs | `/result-blob`, `/content-blobs`, `/result-size` | legitimate: the bulky payload, `src/seon/effect.clj:404-424` |
| timing | `/opened-at`, `/settled-at`, `/duration-ms`, `/interrupted-at`, `/disposition` | fine |
| execution options | `/background?`, `/time-limit-ms` (value shapes, not stored) | fine |
| **symbol** | `:seon.effect/capability` — on the **function** entity, not the effect | row 14: a program identity stored as a symbol |

Live on `default` (probe 1): **zero holders for all 16 effect attributes** —
the branch was reforked and no capability request has been made on it, so this
schema can be accreted with no stored-data migration at all. **10 capability
owners**, and the census that matters for §2:

| owner | capability handler | declared request schema | handler has a program entity |
|---|---|---|---|
| `my.fs/read` | `seon.fs.jvm/read` | `:my.fs/read-request` | yes |
| `my.fs/write!` | `seon.fs.jvm/write` | `:my.fs/write-request` | yes |
| `my.fs/glob` | `seon.fs.jvm/glob` | `:my.fs/glob-request` | yes |
| `my.fs/stat` | `seon.fs.jvm/stat` | `:my.fs/stat-request` | yes |
| `my.edit/form!` | `seon.edit.jvm/edit` | `:my.edit/form-request` | yes |
| `my.edit/exact!` | `seon.edit.jvm/edit` | `:my.edit/exact-request` | yes |
| `my.edit/lines!` | `seon.edit.jvm/edit` | `:my.edit/lines-request` | yes |
| `my.web/fetch` | `seon.web.jvm/fetch` | `:my.web/fetch-request` | yes |
| `my.web/search` | `seon.web.jvm/search` | `:my.web/search-request` | yes |
| `my.shell/run!` | `seon.shell.jvm/run` | `:my.shell/run-request` | yes |

**10/10 capabilities already have a declared request schema, and 7/7 distinct
handler symbols already have `seon.fn` entities.** This is the whole finding of
§2: nothing has to be declared. The request map is already a validated map of
fully namespaced keys — `accepts-request?` refuses a request that does not
satisfy the owner's contract before dispatch (`src/seon/effect.clj:177-185`) —
and then `pr-str`s it into one opaque string. The facts exist at the writer and
are destroyed one line later.

Target side of the write-back, also measured (probe 2): `:seon.fn/file` 5 667
holders, `:seon.fn/form-span` 5 667, `:seon.fn.file/path` 332, and
`:seon.fn.file/digest` 332. `my.edit/form!` pulls to
`/Users/sean/src/seon/src/my/edit.clj`, digest `2552861d…`, span `[1265 2317]`.
Roadmap **F1 has landed**; only the effect side is missing.

## 2. The schema

Owner rule check first. An effect is **one request at one time**: it has its own
identity and its own life, more than one fact points at it (its settlement, its
blobs, its write-back), and there is no aggregate it could roll up into without
losing the request. An entity per effect is correct. Its parts are named in §4.
Everything an effect *points at* — the file, the function, the evaluation — is an
aggregate that is **upserted, never minted per event**: an edit refs the existing
`seon.fn.file` entity for its path and replaces that file's digest in place;
Datahike history keeps the prior one. That is §3b's rule applied one level down.

### 2.1 Refs that replace symbols and ordinals

```clojure
;; on the capability owner's program entity — row 14, beside the symbol
#:seon.fn{:capability-fn
          [:and {:description "The function that executes this capability's requests."}
           :seon.db/ref]}          ; -> the seon.fn entity of e.g. seon.fs.jvm/write

;; on the effect
#:seon.effect{:eval
              [:and {:seon.db/index true
                     :description "The evaluation this request was made from."}
               :seon.db/ref]       ; -> the :seon.eval entity; REPLACES /run + /form-ordinal
              :capability-fn
              [:and {:seon.db/index true
                     :description "The function that executed this request."}
               :seon.db/ref]}      ; -> the handler's seon.fn entity
```

`:seon.effect/run` + `:seon.effect/form-ordinal` are exactly the two parts of the
evaluation identity that `seon.id/evaluation` already derives (`src/seon/id.clj:55`),
so storing them separately is a hand-rolled join key beside the entity it names.
One ref replaces both; the turn and the agent derive through it. That is the
connected graph the assignment asks for:

```
agent ←(:seon.turn/agent) turn ←(:seon.eval/run) evaluation ←(:seon.effect/eval) effect
effect →(:seon.effect/owner)        my.edit/form!        →(:seon.fn/ns) namespace
effect →(:seon.effect/capability-fn) seon.edit.jvm/edit
effect →(:seon.effect/file)          seon.fn.file        ←(:seon.fn/file) every declaration in it
effect →(:seon.effect/program)       the seon.fn entity whose span the edit replaced
issue  →(:seon.issue/files)          the same seon.fn.file entity (spec §3a)
```

"What did this worker change?" is then one pull from the agent, and "which issue
does this edit belong to?" is the reverse walk through the file the spec §3a
`:seon.issue/files` ref already lands on. No new join table, no second family.

### 2.2 Declared argument attributes instead of `request-edn`

The request map's keys are already installed attributes or already declared
components. The structural version is **derived, not rostered**: the effect
writer walks the admitted request and asks the projection which keys are
storable — `seon.schema.datahike/storable-attribute-in?`, the same question the
schema bridge already asks (`AGENTS.md` §3) — and transacts those as datoms on
the effect entity; a nested declared map (`:my.fs/content`, `:my.fs/precondition`,
`:my.edit/form`) becomes a component; anything not installed and anything bulky
(`:my.fs/text`, `:my.edit/new-window`, `:my.edit/source`) goes to the blob it
already goes to. **`request-edn` is not replaced by ten hand-written per-capability
mappings; it is replaced by one projection query.** A capability added tomorrow is
recorded as facts on the day it declares its schema, with no writer change — the
same property that makes §3c's derived resolver the recommended option there.

`result-edn` gets the identical treatment at `settle-value!`
(`src/seon/effect.clj:426-456`): the declared result keys become datoms, the blob
stays exactly as it is. `:seon.effect/result-size` and the blob digests are
untouched — they are the bound's evidence.

The old `request-edn`/`result-edn` strings remain, accretively, as the exact text
admitted (they are `:seon.db/no-history? true`, so they cost nothing historical);
they retire in a later commit once every reader uses the attributes. Zero holders
today means no back-fill exists to write.

## 3. Write-back provenance — the span is computed today and thrown away

This is the one defect with a hard file:line, and it blocks roadmap E2/F2.

`seon.edit/actual-edit` computes the exact changed region of the new source —
`:seon.edit/start` and `:seon.edit/end` — for every operation
(`src/seon/edit.clj:225-242`), and `matched-form-result` carries it out
(`src/seon/edit.clj:280-290`). Then `src/seon/edit/jvm.clj:51-64` builds the
result with `select-keys transformed [:my.edit/from-line :my.edit/to-line
:my.edit/source-window :my.edit/source-window-complete? :my.edit/replacements]`
— **the span is dropped and only human-facing line numbers survive.**
`:my.edit/result` (`resources/seon/schemas/my.edit.edn`) has no span key either.
So the system knows precisely which bytes it replaced, tells the agent a line
range, and keeps nothing a merge could use.

Two mechanical facts the fix must respect:

1. **Unit mismatch.** `:seon.fn/form-span` is *half-open UTF-8 byte offsets*
   (`resources/seon/schemas/seon.fn.edn:3`, produced by `exact-form-span`,
   `src/seon/fn.clj:148-161`). `seon.edit`'s start/end are *Java char indices*
   (they index `subs`, `src/seon/edit.clj:213`). Recording the edit span without
   converting is a silent join bug on any file with a non-ASCII character.
   `utf8-bytes` already exists one file over (`src/seon/edit.clj:16`).
2. **Path identity.** `seon.fn.file/path` is the **canonical absolute** path —
   `src/seon/fn.clj:813` uses `.getCanonicalPath`, and `:seon.lint/file` already
   refs `[:seon.fn.file/path path]` that way (`src/seon/fn.clj:834`).
   `:my.edit/path` is whatever the agent typed. The effect writer canonicalizes
   before it refs, or the ref dangles.

### 3.1 The facts an edit effect records

```clojure
#:seon.effect{:file      [:and {:seon.db/index true} :seon.db/ref]   ; -> seon.fn.file/path
              :before-digest :seon.fn.file/digest                    ; = :my.fs/digest before
              :after-digest  :seon.fn.file/digest                    ; = :my.fs/after-digest
              :form-span [:tuple {:description "Half-open UTF-8 byte offsets of the region this effect wrote, in the file AFTER the write — the same unit as :seon.fn/form-span."} :int :int]
              :program   [:and {:seon.db/index true} :seon.db/ref]}  ; -> the seon.fn entity written
```

`before-digest`/`after-digest` are not new information — they are already in the
result map (`src/seon/edit/jvm.clj:56-57`) and therefore land as datoms for free
under §2.2 as `:my.edit/before-digest` / `:my.edit/after-digest`. Declaring them
in the `seon.effect` namespace as well would be a second spelling of one fact, so
**do not**: the write-back query reads the `my.edit` keys. Only `file`, `form-span`
and `program` are genuinely new.

`:seon.effect/program` needs no new derivation either. The declaration whose span
contains a position is already computed — the lint attribution picks
`(<= start position (dec end))` over the file's declarations
(`src/seon/fn.clj:826-829`). Lifting that containment into a named function of
(file-rows, position) is the one refactor; both callers then use it.

### 3.2 What this buys E2 and F2

Roadmap **E2** ("changed program entities since the fork basis, as a pure
projection") becomes `[?effect :seon.effect/program ?fn]` over the effects
transacted after the fork basis `:t` — a Datalog query over facts the writer
already had in hand, with no file diffing and no source comparison. Roadmap
**F2** ("replace the old form's bytes by the new source inside the owning file")
reads its target span from `:seon.fn/form-span` and its provenance chain from the
effect, and **F3**'s round-trip proof becomes an equality check between
`:seon.effect/after-digest` and the re-indexed `:seon.fn.file/digest` — a fact
comparison, not a re-read of the disk.

It also closes a check that reads absence as health: today a merge that finds no
file diff cannot distinguish "nothing changed" from "the edit was never recorded".
With `:seon.effect/program`, an edit that produced no program ref is a loud
refusal naming the file and the span that matched nothing.

### 3.3 Landing on the spec's entities, not new ones

Per the assignment: the file side lands on **`seon.fn.file` + `:seon.fn/form-span`**
(spec §3a `:seon.issue/files` refs the same entity), and the publication side
lands on **`seon.publication` keyed by `:seon.source/commit-id`** (spec §3b) —
an effect never mints a publication; the merge slice refs the one already keyed
by the commit it forked from. Probe 2: `:seon.source/commit-id` is installed with
one holder; `:seon.publication/id` is not installed yet, so R3 **depends on**
that family and must not declare a second one.

## 4. The identity of an effect

Today (`src/seon/effect.clj:637`):

```clojure
(id/digest 12 [::seon.effect/id (:seon.turn/id ctx) (:seon.cluster.eval/ordinal ctx) effect-ordinal])
```

Parts: **the turn id** (which already contains branch, agent and turn ordinal —
`seon.turn/next-id`, `src/seon/turn.clj:452`), **the evaluation's ordinal within
the turn**, and **the effect's ordinal within the evaluation** (a monotone counter
in the request context, `src/seon/effect.clj:576`). No `random-uuid` anywhere on
this path. This is already canonical-parts identity and the verdict is: **keep it**.

One simplification once `:seon.effect/eval` exists: the first two parts are exactly
`(seon.id/evaluation turn ordinal)`, so the derivation becomes
`(seon.id/id [(seon.id/evaluation turn-id eval-ordinal) effect-ordinal])` — the
same parts, one spelling, and the id is then derivable from the ref the entity
stores. It is a different string, so it is a **breaking** change to existing effect
ids; with zero holders on `default` it costs nothing today and costs a refork later.
Do it in the same commit as the ref or not at all.

## 5. Regressions

| name | namespace | assertion | fails today because |
|---|---|---|---|
| `effect-request-lands-declared-attributes` | `seon.effect-test` | a `my.fs/write!` request through the real effect seam leaves `:my.fs/path` and the `:my.fs/precondition` component as datoms on the effect entity, not only `request-edn` | only `request-edn` is transacted (`src/seon/effect.clj:642`) |
| `effect-refs-its-evaluation-and-handler` | `seon.effect-test` | the effect pulls `{:seon.effect/eval [{:seon.eval/run [{:seon.turn/agent [:seon.agent/id]}]}]}` and `{:seon.effect/capability-fn [:seon.fn/sym]}` in one pull; agent id matches the requester | neither ref exists; `run` + `form-ordinal` require a hand join |
| `form-edit-records-its-span-in-utf8-bytes` | `seon.edit-test` | after `my.edit/form!` replaces a form in a file containing a non-ASCII character, `:seon.effect/form-span` equals the UTF-8 byte span of the new text, and `(subs-bytes after-source span)` is byte-identical to the submitted source | the span is dropped at `src/seon/edit/jvm.clj:51`; the values that exist are char indices |
| `form-edit-refs-the-program-entity-it-changed` | `seon.edit-test` | editing `defn` `f` in an indexed fixture file yields `:seon.effect/program` = the `seon.fn` entity for `f`, and an edit landing in no declaration's span returns a typed refusal naming file and span | no ref, and no refusal — a spanless edit is silently "fine" |
| `changed-programs-since-basis-is-a-query` | `seon.program-test` (roadmap E2) | three edits on a fork → the projection returns exactly those three program entities from `:seon.effect/program` datoms after the basis `:t`, with no file read | E2 has no implementation and no fact to read |

Each asserts the wanted behaviour and kills one class; the fourth is the
absence-as-health killer.

## 6. Prices

| | A — refs only | B — refs + derived request/result attributes | C — B + write-back span and program ref |
|---|---|---|---|
| lands | `:seon.fn/capability-fn`, `:seon.effect/capability-fn`, `:seon.effect/eval` (retiring `/run` + `/form-ordinal`) | A plus the one projection-driven attribute writer at open and settle | B plus `:seon.effect/file`, `/form-span`, `/program`, the char→byte conversion and the shared span-containment function |
| cost | ~0.5 day; `src/seon/effect.clj`, `src/seon/fn.clj`, `seon.effect.edn`, `seon.fn.edn` | ~1 day; + `src/seon/background.clj` settlement path | ~1.5 days; + `src/seon/edit.clj`, `src/seon/edit/jvm.clj`, `my.edit.edn` |
| buys | rows 10 (half) and 14 closed; "what did this worker do" is one pull | every capability's arguments queryable the day it declares a schema; no per-capability code, ever | E2 is a Datalog query instead of a file diff; F2/F3 read their target span and prove the round trip by digest equality |
| risk | id spelling change if §4's simplification rides along (zero holders, so free now) | a request key that is declared but not installed must stay in the blob, loudly, not silently vanish | the unit conversion is the whole correctness surface; a non-ASCII fixture is mandatory |
| leaves undone | request/result still opaque | the merge slice still diffs files | the publication ref (blocked on spec §3b's `seon.publication`) |

**Recommendation: C**, because B alone leaves roadmap E2 doing exactly the work
this platform exists to delete, and the marginal half-day in C is the only part
that unblocks §F. A and B are not separately shippable slices so much as the
first two commits of C.

**The one owner decision inside all three:** whether `:seon.effect/eval`
*replaces* `:seon.effect/run` and `:seon.effect/form-ordinal` (recommended —
they are the evaluation identity's parts stored beside the entity they name) or
merely joins them. Replacing is breakage for any reader of those two attributes;
`rg` finds them only inside `src/seon/effect.clj` and `src/seon/background.clj`,
and `default` holds zero of each, so the price is one commit today and a refork
later.

## 7. Files to own, files protected

Owned by the implementing lane: `src/seon/effect.clj`, `src/seon/background.clj`,
`src/seon/edit.clj`, `src/seon/edit/jvm.clj`, `src/seon/fn.clj`,
`resources/seon/schemas/seon.effect.edn`, `resources/seon/schemas/seon.fn.edn`,
`resources/seon/schemas/my.edit.edn`, and the four test namespaces in §5.

Protected: nothing in that set is concurrently edited. `git status` on
`steward-platform` at the time of writing shows uncommitted foreign edits in
`src/seon/turn.clj`, `test/seon/datahike_fork_test.clj`, `reference-code/datahike`
and `.agents/skills/datahike/references/fork-maintenance.md`, plus untracked
`build/`, `workers/` and this research directory's own files. **None of those
intersect an owned path**, but `src/seon/turn.clj` is live under another lane, so
the §2.1 retirement of `:seon.effect/run` must not touch it in the same commit. `src/seon/fn.clj` is shared with the spec §3c indexer
slice; if both are in flight, the span-containment lift belongs to whichever lands
first and the other refs it.
