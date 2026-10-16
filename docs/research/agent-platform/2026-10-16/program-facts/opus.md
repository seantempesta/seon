---
title: "Program facts from text: one source, one owner per field (independent plan, Opus)"
type: research
status: plan (one of two independent plans; Astra writes the other); no production code
date: 2026-10-16
basis: "HEAD 8347b2d94; dig docs/research/agent-platform/2026-10-16/digs/program-facts.md (4683a909d)"
---

# Program facts from text

## 0. Answer in one paragraph

The source of truth for every declaration, on every branch and in every mode, is **the text that was evaluated or
edited**, read once and analysed by clj-kondo exactly as a file is: a top-level form plus the namespace's `ns` form
text. That is Clojure's and clj-reload's model (a namespace is its file's text; clj-reload's `parse-ns-form` reads
requires from the `ns` form, never from the runtime) and it is the model main already uses (`seon.fn/analyzed-artifacts`
-> `seon.fn/analysis-entities-by-file`). SCI's runtime contributes exactly two things, neither of them a fact: the
**live value** (it stays in the context that evaluated it) and a **confirmation** that a Var the text declares was
bound (a before/after `ns-interns` delta, which exists in both the JVM and SCI). Three fields lose their second
source: a namespace's resolver context comes only from its `ns` text (never `seon.sci.eval/binding-rows`); a
declaration's file comes only from its namespace (never a per-row carry); a value definition's live value lives only
in its evaluating context (never `seon.cache` keyed by compartment). The SCI-to-facts path, the span join, the
per-row file carry and the kept-value cache are deleted, about 600 lines.

Owner direction (2026-10-16, via root) asked: "Do you see a way that works in JVM mode AND sci?" Yes: the record
stage below is a pure function of `(namespace text, form text, interns delta)`; neither argument is an SCI value, so
`seon.turn.batch/jvm-request` can call it unchanged (§4.5).

## 1. What exists (measured, 2026-10-16, default and branch `pf-plan-opus`)

Data flow today (dig §1): form text -> SCI eval with the fork's write observer (`:seon.batch/write`, `:op :bind`,
`:origin` spans) -> `seon.turn.batch/analyze-form` (clj-kondo over a **synthesized** text: a `declare` prelude, a
`ns` form printed by `seon.fn/runtime-namespace-form` from a namespace row built from SCI's live resolver tables,
then the form) -> `seon.turn.batch/join-write` joins each SCI write to one analysed definition by span ->
`seon.turn.batch/write-entity` -> `derive-form` -> per-form `apply-facts` (`db/with`) -> `settle`, which derives the
folded program's facts **again** on the head (`program-facts`) -> `seon.sci.eval/install-evaluated-rows!`, which
re-pulls the committed rows, compares their source (`same-declaration-source?`) and either records them in the
evaluating context (`record-settled-rows!`) or installs them (`install-row!`), and keeps def values in `seon.cache`
by compartment (`keep-evaluated-values!`).

Probes run for this plan (exact forms in §7):

| Observation | Value |
|---|---|
| clj-kondo, one text holding `ns` + top-level `(require '[clojure.string :as str])` + 11 definition forms | 20.6 ms; resolves `str/join` to `clojure.string/join` with `:alias str` through the **top-level require**; names `f area P pm R ->R map->R T ->T v once later mydef`; does **not** name `gen` (custom `mydef` macro) or an `intern` |
| clj-kondo `:java-class-usages` | records `java.util.UUID` usages per row (imports can be judged by use, today they stay whole) |
| clj-kondo unused-namespace, no checkout config | flags `[clojure.set :as set]` unused; leaves `[clojure.walk]` (side-effect load) and a used refer alone. The checkout's `.clj-kondo` config silences the linter (same text, findings `[]`) |
| SCI `ns-interns` before/after delta on branch `pf-plan-opus` | `{:added (gen2) :rebound (gen)}` for `(mydef gen2 5) (mydef gen 4)`; 108 interns compared, 2 ms |
| main: rows with `:seon.fn/file` / rows whose file differs from their namespace's file | **11,079 / 0** |
| main: namespaces without a file | 12 |
| main: `:seon.fn/defined-by` over every function | `defn-` 4,270, `defn` 1,929, `def` 938, `defonce` 80, `defmacro` 11, `defrecord` 8, `deftype` 4; **0 from a non-core macro** (the expansion-hook path serves no stored declaration) |
| files with top-level `(load ...)` / `(in-ns ...)` in src/test/script | 0 (one fixture `.txt`): one namespace is one file |
| first request on a new branch: `ns`, REPL `require`, `defn`, `defmacro`, custom-macro def, `def` atom | 871.7 ms, 953.7 MB, 61,366 calls. Stages: evaluate 160, settle 217, test-step 213, custody 104, apply 75, derive 55, facts 4 ms |
| what that request stored | `:seon.ns/source` is a **reprinted** ns form `(ns pf.scratch (:require [clojure.set :as set] [clojure.string :as str]))`, the REPL require folded in; `gen` stored with `:seon.fn/defined-by clojure.core/def` (SCI's expansion), source `"(mydef gen 3)"` |
| a pull + one query on the branch | 190 ms, 214 MB (finding: read cost; outside this plan) |

Cluster state at probe time: `:faulted`, 29 open faults, 400 errored receipts (runtime_status); none on this path was
diagnosed here.

## 2. The model, taken whole

**Clojure's file model, with the REPL as the editor.** A namespace is its text: its `ns` form, then its top-level
forms in order. The analyser reads that text and nothing else. A REPL evaluation of a top-level form in namespace
`n` is an edit of `n`'s text: a definition replaces the definition of the same name (or is appended), a `ns` form
replaces the `ns` form. This is what CIDER plus clj-kondo already do: the editor's analysis is of the buffer, the
runtime is the runtime. Seon's twist is only that the buffer is held as facts on the branch, and write-back puts it
into the file.

What each layer owns (one owner per field):

| Field | Owner | Derived by | Never from |
|---|---|---|---|
| declaration source, kind (`defined-by`), arglists, contract, calls, references, keywords, host-bound? | the evaluated top-level form text | `seon.fn/analysis-entities-by-file` over namespace text | SCI expansion, SCI Var metadata |
| namespace requires, aliases, refers, imports | the namespace's `ns` form text (`:seon.ns/source`) | `seon.fn.analyzer/namespace-context` (the file indexer's own) | `seon.sci.eval/binding-rows`, `seon.turn.batch/live-bindings` |
| definition digest | the two above | `seon.db/definition-digest-of` (unchanged owner) | the evaluating context |
| a declaration's file | its namespace's file link (`:seon.fn/file` on the namespace entity, set by indexing and write-back) | a join through `:seon.fn/ns` | a per-row carry |
| a declaration's position in its file | the file text | the analyser over the file, at write-back and at display (cached by file digest) | a stored `:seon.fn/form-span` |
| whether the form bound the Var | the evaluation | `ns-interns` delta (JVM and SCI) | the fork's write observer |
| a value definition's live value | the context that evaluated it | its Var | `seon.cache` keyed by compartment |

What "registered" means (owner's question). A Var is a recorded declaration when **the analysis of the form's text
names `ns/name` and the interns delta shows it added or rebound after a successful evaluation**. The two cases where
they disagree are both ordinary:

- The delta shows a Var the analysis does not name (`(mydef gen 3)`, `intern`, a `def` inside a nested `eval`): it is
  the agent's runtime state, reported in the reply as private with reason `:not-declared-by-text` (today's
  `:seon.batch/private` note, one reason replacing `:no-definition-at-origin`, `:ambiguous`, `:no-complete-span`,
  `:rebound`). Main holds 0 such declarations; agents who want one recorded write a core defining form or teach
  clj-kondo the macro (`:lint-as`, the checkout's config), exactly as a file author does.
- The analysis names a Var the delta does not show (`(when false (defn f []))`, a failed evaluation, a `declare`):
  nothing is recorded.

`defmulti`, `defprotocol` members, `defrecord`/`deftype` factories are named by clj-kondo (probe: `area`, `pm`, `->R`,
`map->R`, `->T`). A form that defines several Vars (a record, a protocol) records each, all with the one top-level
form as source, which is what `seon.batch/member-of` approximates today.

REPL directives (`require`, `alias`, `refer`, `import`, `ns-unalias` outside an `ns` form). The owner's rule
(3da13c834, proof `seon.turn.batch-test/a-defn-keeps-its-namespace-row`): "a REPL require reaches a namespace's ns
form only through a declaration that uses it". Kept, computed from text:

1. The evaluated directive is runtime state of the evaluating context, as in Clojure: SCI (or the JVM) evaluates it.
   Its **text** is also kept in that context's private layer, per namespace, in evaluation order
   (`{n [directive-text ...]}`, the same place the context keeps its private defs; a later `ns-unalias` drops the
   entry). No fact is written.
2. The analysis text for a form in `n` is: `n`'s stored `ns` text, then the context's pending directive texts for
   `n`, then the `declare` prelude of `n`'s known names (today's `seon.fn.analyzer/program-prelude`, derived from
   facts), then the form. clj-kondo resolves an alias the pending require introduced (probe: `str/join` ->
   `clojure.string`, `:alias str`).
3. A recorded declaration whose analysis uses a pending libspec (a var-usage's `:alias` or `:refer`, a java class
   usage) folds that libspec into `n`'s `ns` text with rewrite-clj, in the same settlement, in evaluation order.
   That edit is the only writer of `:seon.ns/source` besides an evaluated `ns` form and file indexing. Unused
   directives never become facts; a restart loses them, as a Clojure REPL does.

This keeps both existing proofs' observable rules (`a-defn-keeps-its-namespace-row`,
`a-repl-require-is-written-back-in-the-ns-form-and-reindexes-equal`) and changes their source. The alternative (a
directive edits the `ns` text immediately and write-back prunes clj-kondo's unused-namespace libspecs) is simpler by
one held map but flips the first proof's middle assertion; it needs the owner (§6, option B).

The self-require of 6cda06602 becomes ordinary: `(require '[seon.ref :as ref])` inside `seon.ref` is pending; a
declaration spelling `ref/x` folds it in (Clojure accepts a self-require); one that calls `x` bare resolves through
its own namespace and folds nothing. No digest moves for a declaration that does not spell the alias, because the
digest's resolver input is the namespace's text-derived context filtered by what the declaration uses (the existing
`seon.program/used-resolver-context`, unchanged in P1-P4).

## 3. What disappears

Line counts are `awk` spans on HEAD 8347b2d94 (the function to the next top-level `def`).

| Deleted or reduced | Lines | Replaced by |
|---|---|---|
| `seon.turn.batch/join-write`, `synthetic`, `span-of`, `contains-pos?` | ~65 | join by `[form ordinal, qualified name]`: analysis rows of the form, confirmed by the delta (~10) |
| `seon.turn.batch/form-relative-expansions`, the expansion branch of `seon.fn/runtime-analysis-batch`, `seon.fn.analyzer/expansion-hooks` and its caller | ~60 | nothing: 0 stored declarations come from a non-core macro |
| `seon.turn.batch/namespace-entity`, `live-bindings`, `stored-namespace-row`, `declared-bindings` | ~61 | the stored `ns` text plus pending directive texts (§2) |
| `seon.turn.batch/namespace-facts` SCI path, `seon.sci.eval/namespace-context-row`, `seon.sci.eval/binding-rows` (SCI -> rows direction) | ~79 | rewrite-clj fold of used libspecs into `:seon.ns/source`; attributes derived by `seon.fn.analyzer/namespace-context` (~25) |
| `seon.fn/runtime-namespace-form` | 24 | the stored `ns` text, verbatim |
| the SCI mutation inside `seon.turn.batch/refuse-cycle!` (`sci/install-namespace-bindings!`) | ~8 | nothing to undo: a refused require was never folded into the text |
| `seon.turn.batch/file-attributes` and its carry in `entity-facts` | ~10 | file through the namespace |
| `seon.sci.eval/compartment`, `agent-value-definition?`, `value-key`, `keep-value!`, `kept-value`, `keep-evaluated-values!`, `agent-value-definitions` and its share of `derive-overlay-plan`, the kept branch of `install-function-from-database!` | ~90 | the evaluating context keeps its own Var (§4.4) |
| `seon.sci.eval/installation-covers-program-change?`, `same-declaration-source?`, `declaration-source-value`, the evaluated path of `install-evaluated-rows!` | ~180 | basis equality: the context advances to the settled commit when the settlement's `:db-before` is its acquired basis (~8) |
| per-row `:seon.fn/file` / `:seon.fn/form-span` readers (24 `form-span` sites in 9 files; `file` readers in `seon.cluster.source/file-digests`, `change-path`, `proven-file`, `my.program`) | converted, not deleted | one namespace join; spans from the file's analysis cached by file digest |

Net: about 600 lines of source deleted, about 120 added, plus the fork's write-observer origin patch becoming
removable (a fork bump, so only inside a root restart batch). Concerns separated: text (analysis) decides what is
declared, the runtime decides only whether it was bound, the file decides where it lives.

## 4. Design per question

### 4.1 Source of truth on an agent's branch

The evaluated form text, analysed in its namespace's text (§2). Not the file: a branch's namespace may never have
been written (12 namespaces on main have no file). Not SCI: its resolver tables are runtime state that legitimately
differs from what the text declares (pending directives, a refused require, `intern`). The dig's four defects that
were "a value from one source surfacing in another's output" (self-require, REPL require, lost file link, kept value)
each lose their second source.

### 4.2 What SCI runtime state may become a fact

None. The interns delta is a confirmation that filters analysis rows; it contributes no attribute. The printed value
of an evaluation is already a value-printer output stored as evaluation evidence, not program.

### 4.3 How a file location is derived

From the namespace: `[?e :seon.fn/ns ?n] [?n :seon.fn/file ?f]` (measured 11,079 rows, 0 disagree). Position: the
analyser over the file text when it is needed (write-back already runs `seon.cluster.source/file-rows` over the
committed bytes; display reads it through `seon.cache` keyed by the file's digest). This is the dig's §7.5 inverted:
the file owns its declarations' positions, the database owns which file a namespace is.

### 4.4 A value definition's live value

The row is its source (`"(def cell (atom 0))"`); the value is the Var in the context that evaluated it. Two changes
make the kept-value cache unnecessary:

1. `seon.sci.eval/fork-for-turn` keeps a Var this agent's context evaluated when the branch head's row for it has the
   digest the context recorded at settlement (the context already records what it settled: `record-settled-rows!`'s
   snapshot entry), instead of replacing it by the derived base's binding.
2. The test step (`seon.turn/test-step` -> `seon.cluster.agent/acquire-context!`) forks the agent's evaluating
   context, not a context re-derived for the head. Tests then see the agent's atom, as `clojure.test/run-tests` at a
   Clojure REPL does.

Every other derived context (another agent, a merge's run, after a restart) runs the init, as loading the file does.
Program contexts become content-keyed again (no compartment in the overlay digest), so identical branches share
them, per "Key it by content". Risk: a test green only because of agent private state; the merge's own run (a
derived context) catches it.

### 4.5 JVM mode, host-bound code, file edits

- **JVM mode.** `seon.turn.batch/jvm-request` reads with `read+string` (the text) at a host namespace; the record
  stage takes `(namespace text from facts, form text, host ns-interns delta)`. Identical analysis, identical rows, so
  a merge compares like with like. Whether root's JVM-mode definitions on default become main's facts is an owner
  decision (P5): recommended yes, because then default's loaded Vars and its entities stop drifting and write-back
  owns the file update, but it is a change to "never load uncommitted source into default's JVM" practice.
- **Host-bound code.** Recorded identically (clj-kondo names `deftype`, `->T`; `seon.fn/host-bound-declaration?` is
  already an analysis judgement). Execution is unchanged: a branch's change to a host-bound declaration is text on the
  branch and reaches the platform at merge and reload.
- **File edits.** A file edit is a whole-namespace text; the indexer (`seon.fn/analyzed-artifacts`) already derives its
  entities with the same `analysis-entities-by-file`. The REPL path differs only in the text it hands over (the `ns`
  text + pending directives + prelude + form instead of the whole file). Write-back splices text and reindexes; with
  P3 it finds positions by analysing the file, not by stored spans.

### 4.6 The second derivation

`settle` derives `program-facts` on the head again even when the head is the basis (the common case: only the agent
writes its branch). After P1-P2 the per-form facts are a pure function of text plus the working value; settle can
commit the folded program's facts derived once, re-deriving only on a moved head (`rebase` already exists). This
plan names it (task `seon.turn/a-definition-is-settled-twice`) and measures it in P4 but does not redesign it.

## 5. Phases

Every phase is one loadable slice: HEAD boots after it, each deletion is in the commit that converts its last
caller (tests included), and each names its red proof. Cost per eval request is measured with
`seon.profile/measure` on the same probe on parent and change in one run: the 6-form first request of §7 (parent
871.7 ms / 953.7 MB on 8347b2d94) and the filed `seon.repl/the-first-requests-on-a-new-agent-branch-are-sub-second`
probe (`ns`, `def`, one query; filed 1.2-1.4 s, 1.6-1.7 GB).

### P0 Red proofs (Sonnet, test files only; ~15 min)

New rows in `test/seon/turn/batch_test.clj` (each red on HEAD, reason in parentheses):

- `a-namespace-row-is-its-ns-text`: evaluating `(ns x (:require [clojure.set :as s]))` stores `:seon.ns/source`
  byte-equal to the read text, and its requires and aliases equal `seon.fn/analyze-entities` over a file holding
  the same text (red: the stored source is reprinted from SCI's tables).
- `a-repl-definition-digests-as-its-file-does`: one declaration recorded at the REPL and the same text indexed from a
  file hold one definition digest (red where the ns source is reprinted; covers
  `seon.program/an-evaluated-namespace-keeps-its-read-source-and-call-edges`).
- `a-var-the-text-does-not-declare-is-private`: `(defmacro mydef [n v] ...) (mydef gen 3)` records `mydef`, reports
  `gen` private with `:not-declared-by-text`, stores no `gen` entity (red: `gen` is stored with
  `defined-by clojure.core/def`).
- `the-record-stage-is-one-function-of-text`: a table over `seon.turn.batch/record-form` (new, pure) with
  `[ns-text pending form-text delta]` rows: defn, redefinition, record, protocol, value def, `declare`, failed eval,
  custom macro, REPL alias use, unused REPL require, self-require; asserts the change map. Red until P1 defines it.
- `write-back-finds-a-declaration-through-its-namespace-file` in `test/seon/cluster/write_back_test.clj`: a branch row
  with no `:seon.fn/file` whose namespace has one is written into that file and `file-digests` holds it (red:
  `file-digests` selects by the per-row link).

Kept green throughout: `a-defn-keeps-its-namespace-row`, `a-repl-require-is-written-back-in-the-ns-form-and-reindexes-equal`,
`a-retargeted-alias-leaves-no-stale-digest-for-the-test-world`, `an-identical-value-def-in-two-agents-holds-two-values`,
`a-value-def-init-runs-once-for-every-later-context`, `removals-in-one-request-are-judged-as-one-set`,
`seon.sci.fork-isolation-test`.

### P1 One record stage from text (Opus; `seon.turn.batch`, `seon.fn`)

Define `seon.turn.batch/record-form`: `(namespace text, pending directives, prelude, form text, interns delta) ->
:seon.batch/change`, calling `seon.fn/analysis-entities-by-file` through `runtime-analysis-batch` with the namespace
text supplied instead of `runtime-namespace-form`'s output. `derive-form` reads the delta (before/after `ns-interns`
of the form's namespaces, plus `all-ns` for `remove-ns`) in place of `:seon.batch/write` `:bind` joins. Delete
`join-write`, `synthetic`, `span-of`, `contains-pos?`, `form-relative-expansions`, the expansion hooks.
Proofs: `the-record-stage-is-one-function-of-text`, `a-var-the-text-does-not-declare-is-private`; the `seon.turn.batch-test`
namespace and `seon.turn-test` green. Measure the 6-form probe (expect `derive` down: no expansion bookkeeping).
Retires `seon.turn.batch/an-unjoined-definition-write-is-silent` (no join), `seon.sci.eval/non-defn-definitions-store-no-entity`
if its cases are core forms, `seon.sci.eval/analysis-order-differs-from-evaluation-order` (one text in evaluation order).

### P2 The namespace is its `ns` text (Opus; `seon.turn.batch`, `seon.sci.eval`, `seon.fn`)

`:seon.ns/source` is written only by an evaluated `ns` form (its read text), by the rewrite-clj fold of used pending
libspecs (§2), and by file indexing. The resolver attributes are derived from it by
`seon.fn.analyzer/namespace-context`. Pending directive texts live in the context's private layer. Delete
`namespace-entity`, `live-bindings`, `stored-namespace-row`, `declared-bindings`, the SCI path of `namespace-facts`,
`seon.sci.eval/binding-rows` and `namespace-context-row` (the DB -> SCI direction, `row-bindings`, stays: acquisition
installs a namespace from its row), `seon.fn/runtime-namespace-form`, the SCI undo in `refuse-cycle!`.
Proofs: `a-namespace-row-is-its-ns-text`, `a-repl-definition-digests-as-its-file-does`, `a-defn-keeps-its-namespace-row`,
`a-repl-require-is-written-back-in-the-ns-form-and-reindexes-equal`, new rows for
`my.program/alias-and-require-as-retarget-silently` and `my.program/a-repl-alias-stays-on-the-namespace-row-after-ns-unalias`.
Prove on a branch of the live store that no main digest moves: recompute every function's
`seon.db/definition-digest-of` under the text-derived namespace context; expected 0 changed (main's namespace rows are
already file-derived). Retires `seon.sci.eval/repl-namespace-changes-drift-from-the-ns-text`,
`seon.program/write-back-keeps-the-evaluated-order-of-ns-requires`, `seon.turn.batch/a-repl-namespace-row-keeps-neither-doc-nor-source`.

### P3 A declaration's file is its namespace's (Sonnet with the brief; `seon.cluster.source`, `my.program`, `seon.turn.batch`, readers of `:seon.fn/form-span`)

Convert `file-digests`, `change-path`, `proven-file`'s `file-of`, and every `:seon.fn/form-span` reader to the
namespace join and to spans from the file's analysis (`seon.cache` kind keyed by file digest). Delete `file-attributes`
and its carry in `entity-facts`. Then, in the same commit as the last reader, stop the indexer writing per-row
`:seon.fn/file`/`:seon.fn/form-span` and retract them by a norm (schema resource change: published and reloaded on a
branch of the live store; a host-bound slice through `bin/lane commit --test`). Proofs:
`write-back-finds-a-declaration-through-its-namespace-file`, `test/seon/cluster/write_back_test.clj`,
`seon.cluster.source-test`, `an-identical-redefinition-leaves-every-owned-attribute-of-the-stored-row-unchanged`
(rewritten: the row owns no file attributes). Measure the write-back of one declaration on parent and change (the
added file analysis is bounded by one file, cached).

### P4 The live value stays in its context (Opus; `seon.sci.eval`, `seon.turn`, `seon.cluster.agent`)

`fork-for-turn` keeps the context's own evaluated Vars whose settled digest is the head's; the test step forks the
agent's evaluating context; `install-evaluated-rows!` with an evaluating context advances its acquired basis when the
settlement's `:db-before` basis is the one it acquired, and does nothing else. Delete the kept-value family, the
compartment term of `derive-overlay-plan`, `installation-covers-program-change?`, `same-declaration-source?`,
`declaration-source-value`. Convert `test/seon/sci/program_change_report_test.clj` (deleted with its subject) and the
`install-evaluated-rows!` callers in `seon.test-test`, `seon.contracts-fixture`, `seon.sci.documentation-test`,
`seon.sci.eval-test`, `my.test-test`. Proofs: `a-value-def-init-runs-once-for-every-later-context`,
`an-identical-value-def-in-two-agents-holds-two-values`, a new row `identical-branches-share-one-program-context`
(red on HEAD when a value def is present: the compartment splits them). Measure: the 6-form probe's `test-step`
(parent 212.8 ms) and `settle` (217.3 ms); the filed first-request probe. A measured regression is not committed.

### P5 JVM mode records through the same stage (owner ruling first; Sonnet)

`jvm-request` gains the derive observation: host `ns-interns` delta, `read+string` text. Proof: the P0 table run with
JVM deltas, and an integration row: one `defn` evaluated by root in JVM mode and in SCI mode on two disposable
branches yields equal rows. Never on default's main until the owner rules.

### P6 One parser for the digest (optional; Astra measures first)

`seon.program/source-resolver-names` re-reads the source with edamame to find spelled qualifiers and refers; clj-kondo's
usages of the same text already carry `:alias` and `:refer`. Replace only if a recompute over main shows 0 changed
digests and the probe shows a measured gain; otherwise keep it.

## 6. Options for the owner

- **A (recommended): §2 as written.** Pending directives are context-private text; a declaration's use folds them into
  the `ns` text. Keeps 3da13c834's rule.
- **B: a directive edits the `ns` text at once; write-back prunes libspecs clj-kondo reports unused over the whole
  file** (the linter must be enabled for that call; the checkout's config silences it). One held map less; the branch's
  namespace entity changes on an exploratory require, so `a-defn-keeps-its-namespace-row`'s middle assertion flips,
  and the merge's equivalence must compare against the pruned text.
- **P5 on default:** record root's JVM-mode definitions as main's facts (recommended), or keep JVM mode
  fact-free.

## 7. Evidence forms

- JVM (read-only): clj-kondo `run!` over a 14-line text with `:analysis {:arglists true}`: 20.6 ms; definitions and
  namespace usages as in §1. Same with `:java-class-usages true`. Same with `:config-dir "/nonexistent"` and
  `:unused-namespace :warning`: one finding on `clojure.set`.
- JVM (read-only, default): `[:find (count ?e) :where [?e :seon.fn/file _]]` 11,079; rows whose file path differs
  from their namespace's: 0; namespaces with no file: 12; `defined-by` counts as in §1.
- SCI, agent `pf-plan-opus` (own branch): the 6-form request (cost result `result/ceff4f6359b98`: stages above);
  pulled `pf.scratch`'s namespace and functions (reprinted `ns` source, `gen` as `clojure.core/def`); interns delta
  probe `{:added (gen2) :rebound (gen) :n 108}`.

## 8. Risks

- Tests forking the agent's context (P4) can pass on private state; the merge run is the check.
- Pending directives are lost on restart; a declaration evaluated after a restart that spells an alias only a lost
  directive declared is analysed with that alias unresolved. Delivered as clj-kondo's unresolved-namespace finding
  beside the value, the same message a file author gets.
- Removing per-row file attributes is a schema change across 9 files; it must ride a root restart batch if the norm
  needs one.
- `captured-locals` (a nested definition reading an enclosing local) is text-based already and stays; under the file
  model such a definition's unit would be its top-level form; counted before changing (not counted here).
