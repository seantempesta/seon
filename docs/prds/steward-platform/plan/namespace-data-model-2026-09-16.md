---
type: plan
status: draft for the owner's markup (schemas proposed, none landed)
created: 2026-09-16
tags: [plan, steward, schema, data-model]
---

# The namespace as the unit of responsibility — data model

Owner (2026-09-16 01:20Z): "Focus on writing good schemas with solid names
for solid data that we need to store and link up correctly in our Datahike
graph, in order to later define tasks on having this data around. What do
agents do, what data will we need, what do we have, what is missing. Mine
the codebase for examples. Issues rot because they are not linked, connected
and assigned. The namespace-centric viewpoint makes this data discoverable,
actionable, and later automatically triggered."

## 0. The one principle

**Every fact about a problem carries a ref to the program row it is about.**
A test refs the function it tests; a fault refs the function that raised it;
an issue refs the namespaces, functions, tests and fault signatures it
names; a lint finding refs its function; a task refs its subject and its
tests; a namespace refs its steward. Then "everything about namespace N" is
ONE pull from `[:seon.ns/name N]` through reverse refs, the steward is the
engineer it is assigned to, and a task is a row that links some of those
facts to a set of tests and an agent. Nothing is discoverable by grepping
prose, and nothing is a stamp.

The examples below were mined on default at basis ~536872540 and in
`docs/seon/issues/` (240 notes). Counts are holders, not datoms.

## 1. What agents will actually do, with a real example each

| Work | Real example on this code base | Facts it needs |
|---|---|---|
| Fix a red test | `seon.fn-test/agent-source-reaches-the-evaluator-through-one-visible-path`, red at run `c938001fb2f6` (batch 12b) | result rows (exist), test → subject function (0 holders), the changed functions since the last green (reach digest, in flight) |
| Test an untested public function | 288 today, e.g. `my.agent/done`, `my.edit/exact!`, `my.background/await` | `seon.fn/functions-without-tests` (exists); test → subject to record the new coverage durably |
| Complete a contract | `seon.render.web` has 4 public specs containing `:any`/`:some`/`:maybe`, `seon.turn` 9, `seon.cluster` 8, `seon.cluster.message` 5 | specs (exist); the checker as a query (audit A chain 4) |
| Repair a recurring fault | `seon.render.web/feed` and `seon.turn/turn-completion-backstop-failure` each raised faults on default; 3,444 fault rows overall, 3,391 with `:seon.instrument/fn` | fault → function as a REF (today a string), fault → steward (0 holders) |
| Dissolve a duplicate mechanism | 9 recorded `class/n11` judgments, e.g. "the agent HTML still uses the retired transcript assembler" (`seon.render.transcript/render-session-html` vs `seon.render.walk/history`) | issue → both functions as refs (today prose), a retirement ruling as a fact |
| Close an issue | 240 notes; 137 name a source file and 113 a qualified symbol, in prose; the most-cited files are `cluster.clj` (37), `sci/eval.clj` (32), `render/web.clj` (28) | issue rows with refs, status, steward, task |
| Answer a user | root → juniper `e10231f6`, unanswered; three unanswered messages on default | message facts (exist); the relative predicate |
| Bound ugly output | `dir seon.test` in SCI elided 5,619 of 5,619 characters | elision observations on the evaluation (audit B §4) |
| Fix a lint finding | the hook runs clj-kondo on every edit; findings are printed and lost | findings as rows |

The single most damaging gap in that table: **no production namespace has a
steward** (2 stewards exist, both for agent namespaces) and **no test is
linked to what it tests** (`:seon.test/subject` 0 holders; "tests of
namespace N" cannot be asked, because `:seon.test/ns` is the namespace the
test is DEFINED in). Reach through calls is the only bridge, and the reach
probe measured it at 39 seconds for all tests with a median closure of 668
functions, because every test reaches the platform through its fixtures.

## 2. What exists, per concern (holders on default)

| Concern | Attributes | Holders | Usable for the namespace view? |
|---|---|---|---|
| namespace | `:seon.ns/name`, `source`, `requires`, `aliases/refers/imports`, `steward` | 411 / 313 / 305 / … / **2** | yes, except stewardship |
| function | `:seon.fn/sym`, `ns`, `source`, `spec`, `arities…`, `calls`, `keywords`, `private?`, `doc` | 4,621 / 4,608 / 3,850 / 1,019 / 5,328 | yes |
| test | `:seon.test/sym`, `ns`, `source`, `calls`, `subject`, `pending-subject`, results, `run` | 1,753 (94 without source) / 1,599 / **0** / 0 / 524 latest results / 459 runs | definition yes; **subject no** |
| test run | `:seon.test.run/id`, `program-digest`, `basis-t`, `branch` | 459 | yes |
| fault | `:seon.error/id`, `signature`, `kind`, `message`, `instrument/fn` (string), `agent`, `run`, `steward` | 3,534 / 3,391 / 21 / 1 / **0** | by string prefix only |
| message | `:seon.message/id`, `from`, `to`, `about`, `inbox`, `read-tx` | 15 / 2 / 15 / 11 / 7 / 8 | yes |
| plan | `:my.plan/*`, `:my.plan.item/*` incl. `subject`, `done-query` | 2 plans, 7 items | yes |
| render cost / elision | `:seon.render.cost/*`, elision facts | **0** / not installed | no |
| lint findings | `:seon.fn.file/findings` as a transient value | not stored | no |
| issues | markdown frontmatter: `type`, `status`, `severity`, `tags`, `created` | 240 files; 1 has `namespace:` | **no** |
| task | — | — | the [prototype](task-prototype-2026-09-16.md) |

## 3. What is missing — the schemas

Names follow the dependency's vocabulary (Datahike ref, clj-kondo finding,
clojure.test var) or an existing family. Every addition is an optional key
or a new family; nothing changes an existing key's meaning.

### 3.1 Tests know their subject; program rows know their file

```clojure
;; seon.test.edn — populate what is declared: the analyzer already emits
;; :seon.test/subject from ^{:seon.test/subject 'ns/f} metadata (src/seon/fn.clj:320)
;; and :seon.test/pending-subject for test-first rows. Missing: a DERIVED
;; default when no metadata exists — the function in the namespace under test
;; that the test calls directly. One query at index time, one ref per test.
#:seon.test{:subject [:and {:description "The function this test is about; declared by metadata or derived at index time from the test's direct calls into its namespace under test."} :seon.db/ref]
            :namespace-under-test [:and {:description "The namespace whose behaviour this test exercises, derived from its subject or from the -test naming convention ONLY at index time, then stored as a ref so no reader ever needs the convention."} :seon.db/ref]
            :reach-digest [:and {:description "seon.id/digest of the sorted [sym source spec] tuples of every function this test reaches plus the schema forms they name, recorded on the result from the database value the test ran against."} :seon.id/digest]}

;; seon.fn.edn — where a row lives, so a merged change can be written back exactly
#:seon.fn{:file [:and {:description "The indexed file this definition was read from."} :seon.db/ref]        ; -> seon.fn.file/path row
          :form-span [:tuple {:description "Start and end offsets of the top-level form's exact bytes within :seon.fn/file at index time."} :int :int]}
```

Write these at the two seams that already know them: static indexing
(`src/seon/fn.clj:1182`, the file artifact has path, rows, identities) and
runtime admission (`src/seon/turn.clj:1170`).

### 3.2 Faults point at functions and stewards

```clojure
;; seon.error.edn — :seon.instrument/fn is a string today; a ref makes fault → ns → steward one join
#:seon.error{:fn [:and {:description "The function whose contract or body raised this fault, as a ref to its program row; :seon.instrument/fn remains the exact string the wrapper saw."} :seon.db/ref]}
;; :seon.error/steward exists (0 holders): the fault committer sets it from fn → ns → steward
;; (src/seon/error.clj:1095) whenever the namespace has one.
;; seon.test.edn
#:seon.test{:error-signatures [:set {:description "Fault signatures this regression proves repaired; declared as deftest metadata, emitted by the analyzer."} :seon.error/signature]}
```

### 3.3 Issues become rows, indexed from the notes

The markdown stays the prose; the ROW is what links, assigns and closes.
Indexed at publication the way source files are, from frontmatter plus
the `file:line` and qualified-symbol citations already in every note.

```clojure
;; seon.issue.edn (new family)
#:seon.issue{:id        [:string {:min 1 :seon.db/identity true :description "The note's file slug."}]
             :title     [:string {:min 1}]
             :status    [:enum {:description "The lifecycle the issues README declares; a closed set, so an enum is honest."} :open :resolved :superseded]
             :severity  [:enum :blocker :friction :cleanup]
             :opened    :inst
             :path      [:string {:min 1 :description "The note's repository path; the body is read from the file, never copied."}]
             :namespaces [:set {:description "Namespaces the note names, from its citations at index time."} :seon.db/ref]
             :functions  [:set :seon.db/ref]
             :tests      [:set :seon.db/ref]
             :signatures [:set :seon.error/signature]
             :class      [:and {:description "The class note this is a member of (class/n11 → its class row)."} :seon.db/ref]
             :task       [:and {:description "The task opened to resolve it, when one exists."} :seon.db/ref]
             :issue [:map {:seon.db/attributes true
                           :seon.render/units [:seon.issue/namespaces :seon.issue/functions :seon.issue/tests :seon.issue/task]
                           :seon.render/ai seon.issue/render-ai :seon.render/html seon.issue/render-html}
                     [:seon.issue/id :seon.issue/id] [:seon.issue/title :seon.issue/title]
                     [:seon.issue/status :seon.issue/status] [:seon.issue/severity :seon.issue/severity]
                     [:seon.issue/path :seon.issue/path]
                     [:seon.issue/opened {:optional true} :seon.issue/opened]
                     [:seon.issue/namespaces {:optional true} :seon.issue/namespaces]
                     [:seon.issue/functions {:optional true} :seon.issue/functions]
                     [:seon.issue/tests {:optional true} :seon.issue/tests]
                     [:seon.issue/signatures {:optional true} :seon.issue/signatures]
                     [:seon.issue/class {:optional true} :seon.issue/class]
                     [:seon.issue/task {:optional true} :seon.issue/task]]}
```

Assignment is NOT a stored attribute on the issue: the steward of the
issue's namespaces owns it, derived. An issue with no namespace ref is the
visible defect "unassigned", not a silent default. Mined evidence for the
extractor: 137 of 240 notes cite a `src/…clj` path, 113 cite a qualified
symbol; the class tags (`class/n11` 11 members, `class/p1` 11, `class/n1`
10) map to class rows by the same slug rule.

### 3.4 Lint findings and elisions as rows

```clojure
;; seon.lint.edn (new family; clj-kondo's own vocabulary: finding, level, type)
#:seon.lint{:id       [:string {:seon.db/identity true :description "seon.id/id of [fn rule row col]."}]
            :fn       :seon.db/ref
            :rule     [:qualified-keyword {:description "clj-kondo's :type, e.g. :unused-binding."}]
            :level    [:enum :error :warning]
            :message  :string
            :row :int :col :int}
;; seon.eval.edn — audit B §4: bounded observations from the print tree, per evaluation
#:seon.eval{:elisions [:vector {:description "Each cut the value renderer made: unit, omitted count, path, bound; an explicit empty vector means measured none."} :seon.print/elision]}
```

### 3.5 Stewards for production namespaces

No new attribute. The missing DATA is the assignments themselves: one
steward agent per production namespace family, created with the same
`creation-tx` and `steward-call` the platform already uses (`src/seon/cluster/agent.clj:121`).
Which namespaces get a steward first is an owner decision (§5).

### 3.6 The task

The [prototype](task-prototype-2026-09-16.md) family, with `:my.task/subject`
able to point at any of the rows above (a test, a function, a fault
signature's regression, an issue, a message) and `:my.task/tests` the
success set.

## 4. The namespace view as one pull

With 3.1–3.4 in place, this single selector from `[:seon.ns/name N]`
answers "what is wrong in my namespace, who owns it, what is being done":

```clojure
[:seon.ns/name {:seon.ns/steward [:seon.agent/id]}
 {:seon.fn/_ns [:seon.fn/sym :seon.fn/private? :seon.fn/spec
                {:seon.test/_subject [:seon.test/sym :seon.test/pass-count :seon.test/fail-count :seon.test/error-count :seon.test/reach-digest]}
                {:seon.error/_fn [:seon.error/signature :seon.error/kind]}
                {:seon.lint/_fn [:seon.lint/rule :seon.lint/level :seon.lint/message]}
                {:seon.issue/_functions [:seon.issue/id :seon.issue/status :seon.issue/severity]}]}
 {:seon.test/_namespace-under-test [:seon.test/sym :seon.test/fail-count :seon.test/error-count]}
 {:seon.issue/_namespaces [:seon.issue/id :seon.issue/title :seon.issue/status :seon.issue/severity {:seon.issue/task [:my.task/id]}]}
 {:my.task/_namespace [:my.task/id :my.task/title {:my.task/agent [:seon.agent/id]} {:my.task/tests [:seon.test/sym]}]}]
```

Today that pull returns the name, the functions and their specs, and
nothing else: no tests (0 subjects), no faults (string, not ref), no issues
(files), no tasks (no family), no steward (2 of 411). Each row of §3 turns
one of those branches on. The namespace picture pair (`seon.render.ns`) then
renders this pull, and the steward's opening IS this view; every branch that
is non-empty is a candidate task with its required tests already named.

## 5. Order to land the data (each: one seam, one regression, one live pull)

1. **Test subject and namespace-under-test** at index time; `:seon.fn/file` and `:form-span`. Unblocks "tests of N", reach without the 39 s walk for the common case, and write-back later.
2. **`:seon.error/fn` ref and `:seon.error/steward` set by the committer.** Faults reach their engineer.
3. **`seon.issue` rows indexed from the notes**, class rows, `:seon.issue/task`. The 240 notes become assignable and closable facts; `bin/issues-index` becomes a query.
4. **Stewards for the first production namespaces** (owner picks; candidates by evidence: `seon.render.web` 28 issue citations, 130 functions, 4 loose specs, 1 fault; `seon.cluster` 37 citations, 8 loose specs; `seon.sci.eval` 32 citations).
5. **`:seon.test/reach-digest`** on results (lane in flight; beat 1 says the full pass must be incremental, not per-check).
6. **Lint findings and elisions** as rows.

Then tasks are definable from data alone, and the trigger question becomes
"which non-empty branch of the namespace pull opens a task", which is a
query, later a schedule, later a listener.

## 6. Decisions for the owner

1. Issues as indexed rows from the notes (3.3), keeping the markdown as the body, yes or no.
2. The derived `:seon.test/namespace-under-test` default when no subject metadata exists: the `-test` naming rule applied ONCE at index time and stored, or metadata only (then most of 1,659 tests stay unlinked until annotated).
3. The first three production namespaces to receive stewards.
4. Fault → function as a ref beside the existing string (3.2), yes or no.

## 7. Faults as a connected graph, and the full list of prose and unlinked storage

Owner (2026-09-16 01:45Z): "Faults and errors need to be properly stored so
we can detect, look up, and assign agents to fix them and respond to them;
each class is a task, so the agent sees the faults rolling in and we do not
spawn multiple agents on the same problem. Find all the places we have prose
or not properly linked data; define the schemas and fix the functions.
Never strings for things that should be symbols; refs to related entities;
a real connected graph; record intelligently for future tasks."

### 7.1 What the fault writer records today (`src/seon/error.clj:513`, `:279`)

An occurrence row per fault: `id`, `at`, `basis-t`, `process`, `kind`
(keyword), `message` (string), `signature` = sha-256 of
`[process throwable-class kind top-frame]`, `throwable-class` (string),
`op` and `proc` (keywords naming Flow procs), `cid`, `data-edn`/`data-blob`
(the ex-data as EDN text), `:seon.instrument/fn` (a STRING naming the
function), sparse `agent` and `run` refs, `steward` never set. Two
consequences: the class identity includes the PROCESS, so every restart
mints new classes (audit A noted it); and nothing links an occurrence to
the function row, the namespace, the steward, a regression, a task, or a
resolution ([issue](../../../seon/issues/fault-resolution-has-no-declared-fact.md)).

### 7.2 The schema: `seon.fault` is the class, `seon.error` rows are its occurrences

"Fault" is already the platform's word for a core failure that rides the
error channel into the fault committer (AGENTS.md §1). The class is an
entity; an occurrence refs it; a task refs the class; the regression refs
the class. Counts, first/last seen, and open/resolved are DERIVED.

```clojure
;; seon.fault.edn (new family: one row per distinct failure, across processes)
#:seon.fault{:signature [:string {:seon.db/identity true
                                  :description "sha-256 of [throwable-class kind fn-symbol top-frame] — the PROCESS is provenance of an occurrence, never part of the class."}]
             :kind      :qualified-keyword
             :fn        [:and {:description "The function whose contract or body raised it; a ref to its :seon.fn row (identity stubs exist for every reachable symbol, so the ref never dangles)."} :seon.db/ref]
             :throwable-class [:symbol {:description "The JVM class as a symbol, e.g. clojure.lang.ExceptionInfo."}]
             :frame     [:tuple {:description "Top frame as data: class symbol, method symbol, file, line."} :symbol :symbol :string :int]
             :regression [:set {:description "Tests declaring ^{:seon.test/faults [signature …]}; a green one on the current reach digest is the repair evidence."} :seon.db/ref]
             :task      [:and {:description "The one task open on this class; start! refuses a second while it exists."} :seon.db/ref]
             :resolved-tx [:and {:description "The transaction that recorded the repair (a regression verified with no later occurrence); absence means open."} :seon.db/ref]
             :fault [:map {:seon.db/attributes true
                           :seon.render/units [:seon.fault/fn :seon.error/_fault :seon.fault/regression :seon.fault/task]
                           :seon.render/ai seon.fault/render-ai :seon.render/html seon.fault/render-html}
                     [:seon.fault/signature :seon.fault/signature] [:seon.fault/kind :seon.fault/kind]
                     [:seon.fault/fn {:optional true} :seon.fault/fn]
                     [:seon.fault/throwable-class {:optional true} :seon.fault/throwable-class]
                     [:seon.fault/frame {:optional true} :seon.fault/frame]
                     [:seon.fault/regression {:optional true} :seon.fault/regression]
                     [:seon.fault/task {:optional true} :seon.fault/task]
                     [:seon.fault/resolved-tx {:optional true} :seon.fault/resolved-tx]]}

;; seon.error.edn — occurrences link to the class and to program rows; the strings stay as exact evidence
#:seon.error{:fault [:and {:seon.db/index true :description "The class this occurrence belongs to."} :seon.db/ref]
             :fn    [:and {:description "The raising function's program row (the string :seon.instrument/fn remains the wrapper's exact evidence)."} :seon.db/ref]
             :proc-fn [:and {:description "The Flow proc's step function row, when the fault rode the error channel; :seon.error/proc keeps the proc keyword."} :seon.db/ref]}
```

Derived, never stored: occurrences per class (`count`), first and last
`:seon.error/at`, the steward (`fn → ns → steward`), open = no
`resolved-tx`, recurring = count over a threshold config dial. The fault
committer (`src/seon/cluster.clj` `commit-fault!`, `error/prepare`) writes
the class row by upsert on the signature and the two refs in the same
transaction as the occurrence; the task writer sets `:seon.fault/task` under
`:db.fn/call` so two workers cannot claim one class. A worker's opening
renders the class through its units: its function, its occurrences rolling
in (the `:seon.error/_fault` read is re-evaluated by the since-diff as new
ones arrive), its regressions, and the completing calls.

### 7.3 The full list: prose, EDN text, and code names stored as strings

Rule applied to each row: exact text the system SAW (source, shown text,
messages, notes, a provider's reply) is legitimate prose and stays; a
STRUCTURED value serialised to a string, or a program identity stored as a
string or symbol where a ref is possible, is a defect. Holders are from
default at the time of writing (reforked 19:30Z, so fault counts are small).

| # | Attribute (holders) | What it is | Verdict | Fix at |
|---|---|---|---|---|
| 1 | `:seon.instrument/fn` (string) | the raising function's symbol | **ref** `:seon.error/fn` beside it (7.2) | `error/prepare` |
| 2 | `:seon.error/signature` includes `process` | class identity per process | **re-derive without process** into `seon.fault`; keep the old string on occurrences as evidence | `error/signature` |
| 3 | `:seon.error/op`, `:seon.error/proc` (keywords) | Flow op and proc names | proc → **ref** `:seon.error/proc-fn` to the step function row; keep the keyword | `error/prepare` |
| 4 | `:seon.error/throwable-class` (string) | a JVM class | **symbol** on the class row | `error/prepare` |
| 5 | `:seon.error/data-edn` / `data-blob` | ex-data serialised | keep as the exact evidence blob; ADD the typed diagnostic fields (`diagnostic-layer`, `-operation`, `-member`, `-expected`, `-offending`, `-cause`) as attributes on the occurrence when the source is a `seon.error/diagnostic` — they already exist as declared keys | `error/prepare` |
| 6 | `:seon.error/steward` (0) | who is on the hook | derive, do not store; the committer's routing reads `fn → ns → steward` | `error/steward` |
| 7 | `:seon.ai.attempt/usage-edn` (91) | provider usage as EDN text with string keys | **facts**: the four declared `:seon.ai.usage/*` attributes (prompt, completion, total, cached) are installed and empty; write them | `turn/record-attempt!` (`src/seon/turn.clj:3812`) |
| 8 | `:seon.ai.attempt/settings-edn` (91) | the effective dials as EDN text | **ref** to the settings entity that was in force, plus a `:seon.ai.attempt/model` symbol; the text can stay as a blob | `turn/record-attempt!` |
| 9 | `:seon.cluster.eval/triage-edn` (22) | evaluation error triage as EDN | **attributes**: the triage is a small map of declared keys; store them | `sci/eval.clj` settlement |
| 10 | `:seon.effect/request-edn` / `result-edn` / `result-blob` | capability request and result | request keys are declared per capability: store the capability as a **ref** to its function row plus the declared argument attributes; the result blob stays | `effect.clj`, `background.clj` |
| 11 | `:seon.eval/renderer` (symbol, 44) | which render function produced the shown text | **ref** to the `:seon.fn` row | `turn/evaluation-facts` |
| 12 | `:seon.render.call/selected-producer`, `:seon.render.web/function`, `:seon.render.unknown/producer` (symbols) | render functions by name in retained calls and requests | in-memory values may stay symbols; any DURABLE row (render cost, lint) refs the function row | `render.clj`, `render/web.clj` |
| 13 | `:seon.maintenance.request/handler`, `receipt/handler` (symbols) | the maintenance function | **ref** (the schedule task already refs `:seon.schedule.task/function`) | `schedule.clj` |
| 14 | `:seon.effect/capability` (qualified-symbol) | which capability | **ref** to the capability's function row | `effect.clj`, `fn.clj` capability rows |
| 15 | `:my.plan.item/about` tokens (symbols) | what a step is about | superseded by `:my.plan.item/subject` (ref); retire tokens | `plan.clj` |
| 16 | `:my.plan.item/done-when` (string, 7) | prose criterion | keep as the human sentence; `done-query` is the fact | — |
| 17 | `:seon.test/failure-message` (22), `failing-assertions` (identity strings) | assertion failures | **structured**: one `seon.test.failure` component per failing `is` with expected/actual as exact EDN blobs, message, file, line; the identity stays | `runner/record-tx` |
| 18 | `:seon.test.runner/summary`, `long-reason`, accretion `explanation`/`skip-reason` | run prose | values, not stored rows; fine as long as the stored result carries the structured fields above | — |
| 19 | `:seon.test/pending-subject` (string) | a symbol that does not exist yet | legitimate: no row to ref; the resolver retracts it into `subject` when the row appears | — |
| 20 | `:seon.context.capture/prompt` (91, big) | the exact prompt | keep, but route through the blob owner ([issue](../../../seon/issues/context-capture-prompts-bypass-the-blob-splitter.md)) | `context/capture-tx` |
| 21 | `:seon.turn/reply` + `reply-blob` | the provider's exact reply | legitimate prose | — |
| 22 | `:seon.message/content`, `:my.note/content`, `:seon.fn/source`, `:seon.test/source`, `:seon.ns/source`, `:seon.eval/shown`, `:seon.cluster.eval/source`, `:seon.cluster.eval/comment` | text the system saw or wrote | legitimate | — |
| 23 | `:seon.fn/spec` (pr-str of the contract) | the contract | legitimate as exact text; the AST/arity/argument/binding rows already exist beside it | — |
| 24 | `:seon.fn/arglists` (string) | arglists | **retire**: `:seon.fn.arity/arguments` and bindings carry the same as rows | `fn.clj` |
| 25 | `:seon.fn.ast.entry/value-edn`, `argument/label-edn`, `binding.entry/default-edn` | literal values inside contract ASTs | legitimate: arbitrary literals have no entity | — |
| 26 | `:my.agent/namespace` (symbol) | an agent's namespace in the `my.*` value | the durable fact is the ref `:seon.agent/namespace`; the protocol value may print a symbol | — |
| 27 | Issues: 240 markdown notes | problems | **rows** (§3.3) | issue indexer |
| 28 | Lint findings | clj-kondo output | **rows** (§3.4) with `:seon.lint/fn` ref | hook |
| 29 | `:seon.schema.admission/source` (`:agent`/`:core`), `:seon.render.block/name` | provenance and DOM names | legitimate scalars | — |

Rows 1–4, 7–14, 17, 24 are the "fix the functions" list: eleven writers,
each one seam, each accretive (new attributes beside the old ones; the old
strings retire in a later commit once every reader uses the ref).

### 7.4 Decisions for the owner

1. `seon.fault` named as above (class = fault, occurrence = `seon.error` row).
2. The class identity drops `process` (one class survives restarts) — yes or no.
3. Land order: 7.2 with rows 1–4 first (the fault graph), then 7 and 11 (usage facts, renderer ref), then 17 (structured failures), then the rest.
