---
type: design-draft
status: experiment v0.1 (rewritten in place; v0 is in git history) — tested on seon.schema, seon.note, seon.flow
created: 2026-09-24
scope: how code is decorated and supplemented with EDN so tasks, their data model and their proofs are data the system can load, validate, query and render into agent context
---

# The decoration language, v0.1

**Code says what is true of the code. EDN says what is not true yet.** Everything that names
something is a symbol or keyword; the only prose is intent; everything derivable is derived.

## 1. In code: two metadata keys on ordinary Clojure

```clojure
(ns seon.note
  "Intent, medium form: what this namespace is for, its data, its rules."
  {:seon/must-read [`seon.db/pull]}              ; always in context for this namespace
  (:require …))

(defn render-note-html
  "Intent: what it does and why."
  {:malli/schema   [:=> [:cat :my.note/note] :seon.render/hiccup]
   :seon/must-read [`seon.render.route/path]     ; always in context for this function
   :seon/see-also  [`seon.note/render-note-ai]}  ; related; ranked
  [note] …)
```

Links use syntax quote in code (the reader resolves aliases; nothing must load first). A custom
`ns` clause is impossible: `clojure.core/ns` refuses it by spec (measured).

## 2. In EDN: a task is intent, subjects, a target data model and proofs

`resources/seon/tasks/<namespace>.edn`, one file per namespace the tasks are about, read by the
Clojure reader without evaluation (edamame, quote on — `clojure.edn` silently misreads `'sym` as a
symbol named `'sym`).

```clojure
{:seon.task/id        :seon.note.task/about-is-a-value     ; a keyword: never mistaken for a Var
 :seon.task/intent    "What a note is about is an observed name, not a ref …"
 :seon.task/subjects  #{seon.note/add! seon.note/add-note-call}   ; the producers that change
 :seon.task/schemas   {:my.note/about-symbol [:qualified-symbol {:description "…"}]}  ; data first
 :seon.task/done-when [(true? (seon.schema/valid? :my.note/about-symbol 'seon.db/pull))]}
```

| Authored key | Required | Meaning |
|---|---|---|
| `:seon.task/id` | yes | qualified keyword; generated tasks derive it from producer + subject |
| `:seon.task/intent` | yes | why: the problem or the want (the only prose) |
| `:seon.task/subjects` | yes | symbols of the functions, tests or namespaces that change |
| `:seon.task/schemas` | no | the target data model: key → Malli form, decided before code |
| `:seon.task/done-when` | no | proofs: forms that must all return true on the task's branch; they never merge |
| `:seon/must-read` | no | always in the brief (same key as in code) |
| `:seon.task/entities` | no | stored data it is about, as `[attr value]` identities (a message, a record) |
| `:seon.task/changes` | no | narrows what may change (default: the subjects); `#{}` = a data-only task (answer, research, decomposition) |
| `:seon.task/recurs` | no | recurrence, e.g. `{:every :day}`; each run is one attempt |
| `:seon.task/evidence` | no | paths to the notes or measurements that showed the problem |
| `:seon.task/delivers`, `:seon.task/needs` | no | overrides only; normally derived |

## 3. Derived, never written (measured: 3–7 ms for eight tasks)

| Derived | Rule |
|---|---|
| state | no `done-when` → **raised** (task type: triage); `done-when` → **specified** (implement) |
| deliverables | functions in `changes` (default: subjects) + symbols the proofs reference that do not exist yet **and whose namespace may change** (a missing symbol is a deliverable only for the task that owns its namespace); `changes #{}` delivers data only |
| order (`needs`) | task B needs task A when B's proofs reference something A delivers |
| changed keys | the keys of `:seon.task/schemas` |
| plan / backlog / issue list | queries over the tasks and their derived state |

The derived plan for the eight experiment tasks puts `agents-can-validate` first, then
`about-is-a-value`, then `declared-refusals` and `guarantees` — with no hand-declared order.

## 4. The writer refuses (validation is data)

| Rule | Refuses |
|---|---|
| shape | fails the authored schema (closed map; intent ≥ 20 chars; ≥ 1 subject) |
| subject / must-read missing | names no function, test, namespace or loaded Var (open: a namespace the task creates) |
| references unknown | a proof references something that exists nowhere and is delivered by neither this task nor one it needs |
| unquoted symbol value | a bare qualified symbol in value position (evaluates to the Var); `*dynamic*` exempt |
| evidence missing | an evidence path that does not exist |
| **claimed twice** | two tasks both derive the same missing deliverable (a conflict, not an order) |
| **green on base** | a proof that already holds before any work proves nothing (caught `one-read-for-many-notes`) |
| **red for the wrong reason** | a proof red only because a needed task is unsettled is *blocked*, not specified; check it on a branch where its needs are satisfied |
| (next) example values | literal arguments that fail the called function's input schema |

Proofs are evaluated on a throwaway branch (`exp-base-check`), so a proof that writes cannot touch
the shared head: nine proofs in 756 ms.

## 4b. The standard proof vocabulary

Proofs for every kind of task need a few functions over tasks and data; delivering them is itself a
task (`:seon.task.task/proof-vocabulary`): `seon.task/children`, `specified?`, `settled?`,
`accepted?` (a root or user decision, as data), `seon.message/reply-to`, `seon.finding/get`, plus
`seon.schema/valid?`/`explain` and each detector (`seon.task/stale-docs`). With them, a question, a
decomposition, a nightly maintenance run and a research finding all state "done" the same way
(`docs/research/agent-platform/experiments/scenario-tasks.edn`).

## 5. What a brief is

Only forms the agent can evaluate itself in its SCI context, each with its current value, in this
order: the task; its proofs; its must-reads (read from the program rows — doc, contract, source as
source); where its definitions go (the namespace's intent). Never a namespace dump. The same brief
value is what the owner's debug page must render.

## 6. What the dogfood runs proved and exposed

- **Proved:** a Codex agent given only a brief delivered `seon.schema/valid?` and `explain` (docstring,
  contract, one-line bodies over `projection-validator`/`projection-explainer`) in 11 forms, 11–62 ms
  each, all proofs green in separate evaluations on branch `exp-validate-2`.
- **Exposed:** delivery (cherry-pick → program rows → files) does not exist; a schema declaration
  cannot be published to a branch; `:nil` is refused in agent-authored contracts while core contracts
  use it; `seon.schema.internal/contract-error!` crashes on a function symbol; a session's MCP server
  predating a script change silently runs the old evaluation path (definitions did not persist).
