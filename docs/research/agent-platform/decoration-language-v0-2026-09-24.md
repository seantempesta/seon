---
type: design-draft
status: experiment v0 — to be tested on seon.note and seon.fault before any adoption
created: 2026-09-24
scope: how code is decorated and supplemented with EDN so tasks, their data model and their proofs are data the system can load, validate, query and render into agent context
---

# The decoration language, v0

Two surfaces, one vocabulary of fully qualified keys:

1. **Code** carries what is true of the code: intent (docstrings), contracts, and links.
2. **EDN beside it** carries what is not code yet: tasks — the problem, the target data model,
   the proofs, the deliverables — reconciled into the database like configuration.

Everything is a symbol or keyword where it names something; prose is plain strings and is never
parsed. The plan, the backlog and every agent's brief are queries and renders over these facts.

## 1. In code (standard Clojure; the only additions are two metadata keys)

```clojure
(ns seon.note
  "Medium-form intent: what this namespace is for, its data, its rules, what it must never do."
  {:seon/must-read [`seon.db/pull `seon.render.route/path]}   ; always in context for this ns
  (:require …))

(defn render-note-html
  "What it does and why, in a few sentences."
  {:malli/schema   [:=> [:cat :my.note/note] :seon.render/hiccup]
   :seon/must-read [`seon.render.route/path]                 ; always in context for this fn
   :seon/see-also  [`seon.note/render-note-ai]}              ; related, ranked
  [note] …)

(deftest a-note-survives-its-subject-being-deleted …)         ; a GUARANTEE: ordinary, merged, kept
```

| Key | On | Meaning |
|---|---|---|
| docstring | ns, fn, test | intent — the only authored prose |
| `:malli/schema` | fn | contract, armed; its keys link the fn to the data model |
| `:seon/must-read` | ns, fn | always included when that node is in focus; one hop; over budget is a named finding |
| `:seon/see-also` | ns, fn, test, schema property | related; ranked into context |

Links in code are written with syntax quote (`` `seon.db/pull ``) so the reader resolves aliases and
nothing needs loading first; in EDN they are bare fully qualified symbols.

## 2. In EDN: tasks (`resources/seon/tasks/<subject-namespace>.edn`)

One file per namespace the tasks are about, a vector of task maps, reconciled into the database
(converged = zero transactions; a task removed from the file is retracted). A task's identity is a
qualified **keyword**, so it can never be mistaken for a Var.

```clojure
[{:seon.task/id        :seon.note.task/about-is-a-value
  :seon.task/problem   "A note's subject is a ref …; notes about functions, keys or tasks render as \"a related record\"."
  :seon.task/subjects  #{seon.note/add! seon.note/render-note-ai}      ; what changes (producers), symbols
  :seon.task/keys      #{:my.note/about}                                ; schema keys it changes
  :seon.task/schemas   {:my.note/about-symbol [:qualified-symbol {:description "…"}]}  ; STAGE 1: the target data model
  :seon.task/requirements                                               ; STAGE 2: proofs, run on the task's branch, never merged
  [(clojure.string/includes?
    (seon.note/render-note-ai #:my.note{:id "n1" :agent [:seon.agent/id "root"] :content "slow"
                                        :about-symbol seon.db/pull})
    "seon.db/pull")]
  :seon.task/delivers  #{seon.note/render-note-ai seon.note/note-line}  ; STAGE 3: what root may cherry-pick
  :seon.task/needs     #{}                                               ; other task ids that must settle first
  :seon.task/context   seon.task/implement}]                             ; the context function (task type)
```

| Key | Type | Meaning |
|---|---|---|
| `:seon.task/id` | qualified keyword | identity; generated tasks derive it from producer + subject |
| `:seon.task/problem` | string | intent: what is wrong or wanted, and why |
| `:seon.task/subjects` | set of symbols | the producers that change (fns, tests, namespaces) |
| `:seon.task/keys` | set of qualified keywords | schema keys the task defines or changes |
| `:seon.task/entities` | set of `[attr value]` | stored data the task is about |
| `:seon.task/schemas` | map key → Malli form | the target data model, decided before code |
| `:seon.task/requirements` | vector of forms | each form must evaluate truthy (or be a passing `deftest`) on the branch |
| `:seon.task/delivers` | set of symbols | definitions root may cherry-pick |
| `:seon.task/needs` | set of task ids | declared order; calls between tasks add derived order |
| `:seon.task/context` | symbol | the task type: a contracted function `(db, task) → forms` |

**Task files are read with the Clojure reader (edamame, `:quote true`), never evaluated.**
Requirements are code: `'seon.db/pull` must mean the symbol. Measured 2026-09-24: `clojure.edn`
silently reads `'seon.db/pull` as ONE symbol whose name begins with a quote mark — no error, wrong
data. Everything outside `:seon.task/requirements` is plain data either way.

## 3. What the writer refuses (validation is data too)

Measured on the first six real tasks (`resources/seon/tasks/`): 9 ms against the real program
database; it caught six genuine mistakes in the orchestrator's own task writing (two invented
functions, an untested deliverable, a misread quote, an unquoted symbol, a contract-violating
example).

| Rule | Refuses |
|---|---|
| `:shape` | the task map fails `:seon.task/task` (closed map; problem ≥ 20 chars; ≥ 1 requirement; ≥ 1 deliverable) |
| `:subject-missing` | a subject names no function, test or namespace row |
| `:needs-unknown-task` | `needs` names no task |
| `:key-unknown` | a key in `keys` is neither in the registry nor given a target form in `schemas` |
| `:delivers-missing-and-untested` | a deliverable does not exist and no requirement references it (the TDD allowance is: missing but referenced) |
| `:requirement-calls-unknown` | a requirement calls something that is not a program row, a loaded Var, or delivered by this task or a task it `needs` (transitively) |
| `:requirement-exercises-nothing-it-changes` | no requirement references a delivered symbol, a subject (or its namespace), or a key the task changes |
| `:unquoted-symbol-value` | a bare qualified symbol in value position (it evaluates to the Var's value); `*dynamic*` Vars are exempt |
| (next) example values | a requirement's literal arguments that fail the called function's input schema, checked against the task's target `schemas` plus the registry |

## 4. What is derived, never written

State (raised / specified / attempted / settled / obsolete), order, backlinks ("open tasks on this
function"), staleness (a function changed while its docstring did not; a must-read changed), and the
brief each agent receives. Runtime facts — attempts, agents, branches, results — are separate entities
that point at the task, so reconciling the EDN never erases what happened.
