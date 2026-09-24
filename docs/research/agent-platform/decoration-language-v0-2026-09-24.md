---
type: design-draft
status: v0.3 (rewritten in place 2026-09-24; v0.1 and v0.2 are in git history) — prototyped on the live default database, not installed
created: 2026-09-24
scope: the language that decorates code and supplements it with EDN so that a task's references alone generate an agent's whole context
---

# The task language, v0.3

**A task's references bootstrap everything the agent sees.** Code, tests, schemas and namespaces are
already rows in the database; the language adds only the few links nothing can derive, and a task names
what matters. Context generation walks from the task's references along typed edges and emits REPL forms;
evaluating them at the agent's REPL entrance both teaches the REPL and delivers the data. Nothing is pulled
because it shares a namespace.

## 1. The graph

Every node is a row with a fully qualified identity; every edge is a value in the database.

| Node | Identity | Produced by |
|---|---|---|
| function | `:seon.fn/sym` | clj-kondo analysis of source (file or REPL definition) |
| test | `:seon.test/sym` | same analysis |
| schema key | `:seon.schema/key` | schema declarations |
| namespace | `:seon.ns/name` | analysis |
| stored entity | its identity attribute, `[attr value]` | its writer |
| task | `:seon.task/id` | EDN preload, `seon.task/create!`, detectors, errors |
| help topic | `:seon.help/id` | EDN preload |

| Edge | Stored as | Derived or authored | Status (live db, 2026-09-24) |
|---|---|---|---|
| function → functions it calls | `:seon.fn/calls` symbol* | derived | stored (includes `clojure.core` and special forms; filter to rows) |
| function → keys it reads / writes | `:seon.fn/keywords`, `:seon.fn/writes` keyword* | derived | stored |
| function → schema keys of its contract | — | derived | **GAP**: contract stored only as a string (`:seon.fn/spec`); `faults-form`'s keywords lack `:seon.render/unit` |
| test → functions it calls | — | derived | **GAP**: tests have no call edge; `:seon.test/reach` is empty for a test that calls `faults-form`, saturated (1,720) for core functions |
| schema → schema keys | `:seon.schema/references` keyword* | derived | stored |
| namespace → namespaces | `:seon.ns/requires`, aliases | derived | stored (aliases not installed in branch SCI — fix lane running) |
| any node → must-read | `:seon/must-read` | **authored** | **GAP**: not ingested (no attribute) |
| any node → see-also | `:seon/see-also` | **authored** | **GAP**: not ingested |
| help topic → topics it builds on, vocabulary it teaches | `:seon.help/needs`, `:seon.help/about` | **authored** | new |
| task → tasks it needs | `:seon.task/needs` | **authored** (suggested by the writer) | new |
| task → what it is about | `:seon.task/about` | derived from its proofs | new |

Reverse edges are free: Datahike indexes values, so "who calls f", "which tasks mention f", "which tests
exercise f" are one indexed query each (measured: reverse lookup + dangling check 24 ms on native Datahike).

## 2. What is authored

**In code — ordinary Clojure, nothing custom:**

```clojure
(defn faults-form
  "Read errors assigned to this agent: its stewarded namespaces, and its turns."   ; intent, not history
  {:malli/schema   [:=> [:cat :seon.render/unit] [:or :nil :seon.render/form :seon.db/error-result]]
   :seon/must-read [`seon.error/observation-selector]}                               ; always shown with it
  [unit] …)
```

A docstring states what is true and why; incident narratives belong in tasks and evidence, not docstrings
(`faults-form`'s current docstring is a 2026-09-16 incident log — a detector should raise that).

**Schema properties:** `:description`, and `:seon/must-read` when a key is unusable without another.

**A task — three keys:**

```clojure
#:seon.task{:id        :seon.error.task/faults-read-is-a-call
            :intent    "Root's opening prints an 81,805-character error query five times (81% of its prompt).
                        It should be a short call that returns the same errors."
            :done-when [seon.error.task-test/opener-is-short
                        seon.error.task-test/for-agent-matches-the-query]}   ; §3
```

Optional only when nothing else can say it: `:seon.task/must-read`, `:seon.task/needs`, `:seon.task/merge`
(a pure `(fn [{:keys [base source target task]}]) → tx-data` when the default program-row copy is wrong),
`:seon.task/attempts`, `:seon.task/schedule`. There is no authored scope: what the work touched is the
diff, and root's decision selects from it.

**A help topic** — one line, what it builds on, what it teaches:

```clojure
#:seon.help{:id :seon.help/read :needs #{:seon.help/repl}
            :text "(source f) prints a function; (callers 'f) and (calls 'f) walk the program."
            :about #{source callers calls}}
```

## 3. Done when: symbols of ordinary deftests

`:seon.task/done-when` names tests by their fully qualified symbols; EDN never holds function bodies. The tests
are code in an ordinary test namespace, written first (TDD) — so they are rows in the graph like any test,
and their calls seed the context:

```clojure
#:seon.task{:id        :seon.error.task/faults-read-is-a-call
            :intent    "…"
            :done-when [seon.error.task-test/opener-is-short
                        seon.error.task-test/for-agent-matches-the-query]}

;; test/seon/error/task_test.clj — the tests, as code
(deftest opener-is-short
  (is (> 300 (count (pr-str (:seon.repl/form
                              (seon.error/faults-form {:seon.db/db (db/db)
                                                       :seon.render/value [:seon.agent/id "root"]})))))))
```

Anything else a task must run (a merge function, a detector) is likewise a fully qualified symbol, or an
inline anonymous function only when it is a one-liner with no other home.

Each test runs through the existing `seon.test/run` on a throwaway fork of the attempt's branch — `branch!` →
fork the SCI context → inject the connection → run the one body → record → unlink — with `is` failures
returned as data and re-prompted to the agent. Measured (`agent-deftest-and-test-forks-2026-09-24.md`): the
same deftest through the existing owners on a throwaway fork takes ~130 ms; through `seon.test/run` today one
test costs 3,320 ms (history scan 1,596 ms, blob publication 617 ms; body 121 ms). A deftest defined at an
agent's REPL today returns `nil` and loses its failures.

Invariant: at least one proof red on the base (a run on 2026-09-24 found the faults task already green on
default's rows — the check must settle such a task, not farm it); run after every turn and on the merge branch.

## 4. Context generation: task → forms → transcript

1. **Seeds**: the first-party functions, schema keys and `[attr value]` entities the task's named tests reference (task → test → function), plus its must-reads.
2. **Tools are not subjects**: a symbol some help topic teaches (`:seon.help/about`) is vocabulary — its topic
   is included, the symbol is never expanded. Without this rule `seon.db/q` in a proof pulled 410 callers
   and 12 schemas (27.8 KB).
3. **Per seed, forms by node type** — hop 1 is content, hop 2 is names the agent can follow with one call:

| Seed | Content forms (hop 1) | Address forms (hop 2) |
|---|---|---|
| existing function | `(source f)` — doc, contract, body, once | `(callers 'f)`, `(calls 'f)` (first-party only) |
| missing function | `;; f does not exist yet: you create it` | the namespace's intent `(doc 'ns)` |
| schema key | `(schema :k)` | keys it references |
| stored entity | its AI render | its refs as identities |
| must-read | content, always | — |

4. **Help is derived**: the topics whose vocabulary the opener or the proofs use, ordered by `:seon.help/needs`
   (topological, ties by id; a cycle is refused). No fixed preamble.
5. **Order**: `(help)`, `(task)`, `(proofs)` (red on base shows what to do), then each seed's forms in proof order.
6. **Evaluate** the forms at the agent's REPL entrance on its branch, so every value is current; render as a
   clojure.main transcript: `ns=> form`, printed output, value, and **end with `ns=> `**.

Measured on the live default database for the task above: **4,276 characters in 10 ms**, every value real
(`tmp/wiki-toy/opener-faults-read.txt`). The live root prompt is 680,643 characters. Excerpt:

```text
my.agents.codex=> (proofs)
[false false]

my.agents.codex=> (callers 'seon.error/faults-form)
[seon.error/render-faults-ai seon.render/invoke-selected]

my.agents.codex=> (calls 'seon.error/faults-form)
[seon.db/carried-projection seon.db/pull seon.error/faults-input
 seon.error/observation-selector seon.schema/projection-from-database]

my.agents.codex=> (source seon.error/for-agent)
;; seon.error/for-agent does not exist yet: you create it
nil
my.agents.codex=>
```

Defects the prototype showed: pprint code-dispatch lays out `(fn [db] …)` one token per line (the one
printer must lay out data forms); callers/calls of a missing function are noise (skip); help ties sort by id,
putting `query` before `task` (needs must express it).

## 4b. Execution model: checkpoints (owner 2026-09-24)

A checkpoint is an immutable pair: a Datahike commit and an SCI context value (persistent namespace maps;
`sci/fork` 0.006 ms). Levels, keyed by content: base (loaded program, aliases, armed wrappers) → cluster
(+ its differing rows) → task (+ its tests, vocabulary, evaluated opening). An attempt is a fork of the task
checkpoint plus a branch of its commit, and never advances; a retry or a proof run is another fork. A
long-lived agent moves to a new checkpoint at a turn boundary when its cluster's program changes.

Agent atoms, results and quick defs are scratch: private to their fork (probe: another context cannot see
them; a fork of a context that holds an atom shares that object, so proofs judge the database value, never
scratch). Scratch never merges and is dropped when an agent moves checkpoints; anything worth keeping goes in
the database.

## 5. Derived, never authored

| Derived | From |
|---|---|
| `about` (back-references) | the proofs' symbols, keys and entities |
| raised / ready / blocked / settled | proofs, needs, latest attempt's runs for the current task digest, decision |
| what changed | the base→branch diff (merge classifies added / replaced / retracted; a rename is retract + add of the same body) |
| deliveries, affected callers, reaching tests | the diff + the graph |
| order (plan) | `needs`, topologically |

## 6. Stored model

Task and attempt are new root entities; proof, delivery and decision are their components; proof results are
existing test-run entities. Worked example with values: the plan page
(https://claude.ai/artifact/N2TBGUvajfYUbu3W1sQFxj, "The task data model").

## 7. The writer refuses

Unknown references (a symbol, key or entity that resolves nowhere, except a missing function a proof calls —
TDD); duplicate ids; `needs` cycles or unknown ids; no proof red on the base; a proof example whose literal
arguments violate the called function's contract; a help-topic cycle.

## 8. What must change in the data before this works

1. Tests store their direct calls (the known-good examples of a function).
2. Contracts store their schema keys as an edge (analyzer; no string parsing at readers).
3. `:seon/must-read` / `:seon/see-also` are ingested from metadata and schema properties.
4. Help topics become data (today: 13 hard-coded lines + an unused seeded instruction).
5. Docstrings state intent; incident history moves to tasks/evidence (detector).

## 8b. The loop, run on 2026-09-24 (scripted steps, real evaluation, no model)

`tmp/wiki-toy/experiment/scratch/loop.clj` on branch `exp-loop-faults`: tests defined, vocabulary defined,
the opening derived from the task and evaluated at the agent entrance (1.3 KB, 107 ms), then scripted steps
each evaluated with its result appended; total 522 ms. It exposed: printed output is dropped by the
evaluation entrance (`(source 'f)` shows `nil`); the task was already green on default's rows while the JVM
still ran the old code; a reused branch carries private state between attempts (each attempt needs a fresh
branch); the alias defect (`db/` unresolved); the `contract-error!` crash hiding the real refusal.

## 9. Open decisions

- Task identity: keywords for authored and generated tasks (recommended).
- The entry gate's `:warn` dial.
