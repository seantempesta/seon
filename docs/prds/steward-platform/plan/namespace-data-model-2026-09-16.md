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
