---
type: design-draft
status: draft for owner review — becomes the architecture page and replaces plan sections once ruled
created: 2026-09-23
scope: the data model and context generation for namespace agents; how docs, code, tests, errors and reference code link and render
---

# Namespace agents: the data tells its own story

**The idea in five lines.** The program is data in Datahike. Every producer that already runs —
clj-kondo, Malli, the REPL, the test runner, instrumentation, the database — emits values that
point from the data to what it is about. An agent is fired up *for a namespace*, and its whole
context is one query rooted at that namespace plus the task it was given, rendered by the render
functions of the schemas it reaches. Nothing is hand-assembled; how the data links decides what
the agent sees. Docs are the program's own docstrings, schema descriptions and link metadata.

This draft synthesizes eight research notes (evidence at the end) and the owner's rulings of
2026-09-23. Items marked **OPEN** are waiting for the owner. Nothing here claims an installed seam.

## 1. Principles

1. **One perspective: the namespace.** An agent instance is started for one namespace
   (`:seon.agent/ns`). That namespace is its responsibility and its point of view. A fault in a
   function starts (or joins) a task for the function's namespace — ownership is derived from the
   symbol, never routed by a table.
2. **Everything is a value that satisfies a Malli schema.** Tasks, agents, errors, evaluations,
   context forms. A kind of thing is a schema, never a stored `:kind`.
3. **Links are values written where the knowledge is produced.** Observed names are qualified
   symbols/keywords (they survive deletion of their target; "names nothing" is one query).
   Reverse edges ("who calls me", "what links here") are queries, never stored.
   **Every symbol reference is stored as a Datahike symbol, never a string** (owner, 2026-09-23):
   function, namespace, test, renderer, context-function and link targets are `:qualified-symbol`
   (namespaces `:symbol`). Known violations to convert: issue citations' unresolved tokens as
   strings (`resources/seon/schemas/seon.issue.edn:27`), `pr-str`'d forms and triage
   (producer inventory §1.5).
4. **Prose is never parsed.** Docstrings and `:description` are plain text. Every link is a real
   symbol or keyword that clj-kondo or Malli already reports. No mini-language in strings.
5. **Context is forms, ordered like code.** Render functions return a comment plus a form; forms
   are ordered so a name is introduced before it is used, evaluated through the one REPL entrance,
   and their values printed by the value renderer. Text an agent has already seen is never
   re-rendered.
6. **Standard Clojure first.** `defn` + docstring + metadata, `deftest`, Malli schemas in EDN.
   Agents already know how to write these; we add two metadata keys, nothing else.

## 2. Producers and the edges they emit

Every edge below is emitted by a producer that already runs (producer inventory, `22d55c378`;
clj-kondo probe in this session: 0.23 s for a namespace, a test and a schema file).

| Producer | Native output used | Edge / fact (value, from → to) | Stored today? |
|---|---|---|---|
| clj-kondo var-definitions | name, ns, doc, arglists, span, `:defined-by`, `:test`, `:meta` | the function/test row, its docstring and authored metadata | yes (metadata selectively) |
| clj-kondo var-usages | `:from-var`, `:to`, `:arity` (alias-resolved) | function → functions it calls (`:seon.fn/calls`); test → functions it exercises | yes |
| clj-kondo keywords | `:ns`, `:name`, `:from-var`, `:keys-destructuring` | function → keys it reads/produces; test → keys it uses; contract → keys | partly (`:seon.fn/keywords`); test → key **no** |
| clj-kondo usages inside metadata | resolved symbols by line range (no `:from-var`) | `:seon/see-also`, `:seon/must-read` → targets | **no** (≈10 lines in the indexer) |
| clj-kondo `:symbols` in EDN | fully qualified symbols in schema properties | schema key → functions (`:seon/see-also` in properties) | **no** |
| Malli schema forms | `m/walk`/`mu/subschemas` refs, properties | key → keys it references; key → `:description` | yes (`direct-references*`, `src/seon/schema.clj:61-79`) |
| Malli explain (instrumentation) | `{:schema :value :errors [{:path :in :schema :value :type}]}` | error → refused function, paths, keys, offending value | **lost**: explained twice, native value discarded (`src/seon/instrument.clj:287-304,609-636`) |
| clojure.test runs | pass/fail/error, expected/actual | test → result evidence (current per definition digest) | yes; expected/actual flattened to strings (`src/seon/test/runner.clj:189`) |
| REPL / SCI evaluation | source, result, out/err, exception chain | evaluation → symbols its source references, result schema, error | partly |
| Runtime vars | `(meta var)` `:file` `:line`, `io/resource` | symbol → the file that actually runs (reference-code mapping, §5) | **no** |
| Datahike transactions | tx-data, tx-meta, commit id | provenance; staleness ordering (§6) | yes |
| Git / pins | gitlink, checkout, deps coordinate | dependency identity and revision | yes, now checked (pin fix `186957285`) |

Not every schema is stored: profile values, Flow pings and wake observations are volatile
(producer inventory §1.8). Universal context needs readable values, not a transaction per object.

## 3. The two authored link keys (ruled)

```clojure
(defn transact!
  "Write through the one database owner. Retracting an entity also sweeps every
   incoming ref and cascades components; required refs refuse at final validation."
  {:malli/schema   [:=> [:cat :seon.db/transact-request] :seon.db/transact-result]
   :seon/must-read [`datahike.db.transaction/retract-entity `datahike.writer/transact!]
   :seon/see-also  [`seon.db/pull]}
  [request] …)
```

- **`:seon/see-also`** — related. Ranked into context by relevance.
- **`:seon/must-read`** — always included in context whenever the declaring node (function, or
  its namespace) is in focus, before anything ranked. One hop only. If the must-reads alone
  exceed the budget, generation returns a named finding (node, count, tokens, budget) — never a
  silent drop. Symbols only; schema keys are already reached through `:malli/schema`.
- Same fully qualified key in function/test/namespace metadata, in Malli schema properties
  (bare fully qualified symbols in EDN), and as the stored Datahike attribute
  (cardinality-many qualified symbols, indexed).
- **Checked like calls:** a link naming no row warns at entry and refuses at merge. When a
  must-read target's definition changes after the declaring function did, the function is
  flagged "a function you must read changed". A pin bump raises the same flag.
- Lessons learned live here: on **our wrapper** (only owners call a dependency directly), whose
  docstring carries the lesson and whose `:seon/must-read` points at the implementation that
  actually runs (e.g. `seon.db/pull` → `datahike.pull-api/pull`, not the `emit-api` macro).

**OPEN — spelling in code.** Syntax quote `` `str/join `` (recommended: the reader resolves the
alias to `clojure.string/join`; no load-order coupling, so namespaces can link to each other;
typos caught by our entry check) versus var-quote `#'str/join` (compiler-checked, but the target
namespace must already be loaded, so mutual links need circular requires). Probe results:
bare `str/join` stores the function object (identity lost); `'str/join` stores an unexpanded
alias that resolves nowhere.

## 4. Node families (Malli schemas, EDN)

Sketches, not admitted declarations. Existing families are reused; only `seon.task` and the two
link keys are new, and `seon.task` replaces issue, plan-step and error-work lifecycles
(schema orientation audit, `f8f69e629`).

```clojure
;; the namespace — exists (:seon.ns/*); its docstring is the namespace's story
;; the function / test — exist (:seon.fn/*, :seon.test/*); plus :seon/see-also, :seon/must-read

;; an agent is an instance fired up for a namespace
#:seon.agent{:agent [:map [:seon.agent/id :seon.agent/id]
                          [:seon.agent/ns :qualified-symbol]      ; perspective and responsibility
                          [:seon.agent/branch :keyword]           ; its own reality
                          [:seon.agent/task {:optional true} :seon.db/ref]]}

;; a task is data that selects context
#:seon.task{:task [:map [:seon.task/id :seon.task/id]
                        [:seon.task/ns :qualified-symbol]          ; whose perspective
                        [:seon.task/context :qualified-symbol]     ; the context function
                        [:seon.task/instructions {:optional true} :string] ; case-specific only
                        [:seon.task/subject {:optional true} :seon.db/ref]  ; e.g. the error
                        [:seon.task/done-when [:set :qualified-symbol]]     ; tests that must pass
                        [:seon.task/parent {:optional true} :seon.db/ref]]} ; cross-namespace child

;; context is a vector of comment + form, the same grammar the REPL reads
:seon.context/forms [:vector [:map [:seon.repl/comment :string] [:seon.repl/form :any]]]
```

**Task types are functions**, not stored kinds: any contracted function whose output is
`:seon.context/forms` is a task type, found by query.

```clojure
(defn fix-error
  "Standing instructions for fixing a fault: reproduce it as a failing test,
   fix the producer (not the call site), then make every done-when test pass."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/task] :seon.context/forms]
   :seon/must-read [`seon.error/explain]}
  [db task] …)
```

The function's docstring is the standing instruction for that kind of work; the task carries only
what is specific. Task types have tests, can be overridden on a branch, and merge like code.

## 5. Reference code

A symbol maps to the code that **runs**, not to a directory: `(meta var)` → `:file`/`:line` →
`io/resource` → classpath root → `deps.edn` coordinate and pin → clj-kondo over that root
(reference-code research, `c442a4d69`; probed on default this session). Library vars become rows of
the same shape as ours (symbol, doc, arglists, span, calls) with a value naming their dependency.
Examples come from the library's own tests (Malli: 458 calls to `explain`), our call sites
(already `:seon.fn/calls`), and, for generated APIs, the library's specification (Datahike's
`:impl`/`:args`/`:examples`) — rare; our wrapper's must-read covers it.

Consistency check, no configuration: vars that exist at runtime (`ns-publics`/`ns-interns`) but
have no analyzed definition are exactly what the graph cannot see (macro-generated or body `def`s);
each is a finding.

**OPEN — where library rows live.** Recommended: one reference database keyed by each library's
revision, shared by every cluster, recomputed only when a pin moves, joined in queries (Datahike
queries accept several databases). Alternative: a partition in each cluster (simpler joins, copied
into every branch's history). ≈8,800 vars; whole-library analysis 0.3–2.7 s each.

## 6. Context for a namespace agent

Context = the namespace's story + the task's slice, joined and ordered by dependency.

**The namespace's story** (always, one query rooted at `:seon.agent/ns`):
1. the namespace docstring and its `:seon/must-read` (always);
2. its functions in dependency order, each with contract and current test results;
3. the schema keys it owns, their descriptions and the keys they reference;
4. who uses it: other namespaces' functions that call it (reverse `:seon.fn/calls`);
5. its data: a few real entities carrying its keys, printed by its own renderers;
6. recent errors raised in its functions.

**The task's slice** (`(seon.task/fix-error db task)` for a fault):
- the error value, with the native Malli explanation reachable at a result handle;
- `:description` of the keys along the explanation's paths;
- the refused function's must-reads;
- the reproduction: the offending input the contract saw, as a re-runnable call;
- each `done-when` test's state: green, red, or not yet written.

**Selection and budget.** Must-reads first and never ranked; then personalized PageRank over the
symbol graph rooted at the focus (Aider's repo-map algorithm on better, fully resolved edges),
weighted by call edges over see-also; whole units under the budget, elisions named. Budget the
final composition including the frame (today the frame is appended after selection,
`src/seon/cluster/prompt.clj:398`).

**Renderer rule** (already installed, `src/seon/render.clj:334-389`): a value renders through the
most specific schema it satisfies that declares an AI renderer, else the attribute-map printer. A
namespace agent owns the renderers of its keys; another agent inherits them, and may override one
on its own branch as an ordinary function override (merge promotes it).

**Staleness is a query:** a definition is stale when something it links to (calls, see-also,
must-read) was redefined in a later transaction than its own definition digest. Re-indexing
identical content adds no transaction. Examples are tests, so a changed function reruns them.

## 7. From an error to a working agent

1. A function in `my.workout` refuses or throws → one error entity (function symbol, native
   explanation, offending value, caller).
2. The function's namespace owns it (derived from the symbol).
3. The task writer creates, or joins by the error's identity, a `seon.task` with
   `:seon.task/context seon.task/fix-error` and `done-when` = the tests reaching the function
   plus a regression test for the reproduction.
4. Starting the task creates an agent for `my.workout` on a new branch; its first context is
   `(render (fix-error db task))` joined to the namespace's story.
5. **Done is derived, never declared:** every `done-when` test has current green evidence on the
   merged head, and the regression test fails on the base and passes on the branch.

## 8. What agents feel today (measured on default, `209019505`)

These are the first fixes, before any new feature; each has a query that proves it.

| Pain | Measured | Fix |
|---|---|---|
| The prompt is mostly one empty query | the "inspect my errors" read is 95% of the 115 k-char opening and returns `[]`; it spells every error attribute in its source (`src/seon/error.clj:1786-1818`); 5× in a 680 k prompt | render the read as a short named form, not an inlined selector |
| Replays dominate | 142 of 176 evaluations are system replays; one failing query replayed 23× | replay only reads whose evidence changed; never replay a failing read |
| Agents never compose | 1 of 51 forms uses a result handle; 0 use map/filter/reduce/for | `rN =>` on every result incl. errors; tools as data→data functions; primer shows composition |
| Errors arrive garbled | 10 results "More than one function accepts this value" (two error renderers) hid a Datalog parse error | one error renderer on `:seon.error/base` replaces ~275 copies (`5ce1f8370`) |
| Refusals lose the handle | `src/seon/repl.clj:218-221` drops result entries when a renderer is declared | keep the handle always |
| Explanations are text | Malli explanation flattened; expected schema stored as a SHA-256 | keep the native explanation on the refusal (10–20 lines, `67b10f40f`) |
| Most data has no renderer | 23 of 84 non-error entity schemas declare one | namespace agents own their renderers; the default printer stays total |
| A trivial SCI eval | 833 ms, 2.6 GB allocated (`5ce1f8370`, undiagnosed) | diagnose before anything else in the environment system |

Composition rate becomes a stored query, so primer and renderer changes are measured by what
agents actually write — including by running three agents from one forked environment.

## 9. What this replaces (estimates from the research, unmeasured)

- the contract-fit renderer stage and ~275 copied error renderer declarations (−110 to −140);
- duplicated explanation construction and generic error prose (net reduction, 45–80 new lines);
- ~600 lines of test-only bootstrap ordering (`walk/ordered-episode`, `bootstrap/next-entry`),
  replaced by 40–60 lines of forms-in-dependency-order (`9c152286f`);
- issue citations as refs, issue/plan/error lifecycles → one `seon.task` family;
- redundant back-pointers (agent↔runtime, agent↔plan, renderer symbol + ref);
- the whole-history prompt recomputation (4,068 ms after an unrelated write) → incremental
  membership, each value rendered once (`d0b0c7a62`).

New code: the two link keys in the indexer (~10–20), library rows and link resolution (~110),
ranking (~50), the task family writer and two or three context functions (size to be designed).

## 10. Open decisions

1. Link spelling: syntax quote (recommended) or var-quote (§3).
2. Library rows: shared reference database (recommended) or per-cluster partition (§5).
3. `done-when` tests that do not exist yet: allowed as unresolved obligations (recommended).
4. Cross-namespace work: child task for the other namespace's agent (recommended).
5. Keep the offending input for reproduction, bounded, with opt-out for sensitive schemas
   (recommended).
6. One agent instance per task on its own branch (recommended) or a standing agent per namespace.
7. Known-good examples: green reaching test source first; sampled real inputs from the armed
   wrapper later; seeded Malli generation as the fallback.

## Evidence

Research notes in this directory: `malli-data-story-2026-09-23.md` (67b10f40f),
`data-tells-its-story-2026-09-23.md` (5ce1f8370), `agent-repl-reality-2026-09-23.md` (209019505),
`context-as-query-and-render-2026-09-23.md` (d0b0c7a62), `reference-code-as-context-2026-09-23.md`
(c442a4d69), `schema-orientation-audit-2026-09-23.md` (f8f69e629),
`clojure-native-doc-metadata-2026-09-23.md` (9c152286f), `producer-inventory-2026-09-23.md`
(22d55c378). Orchestrator probes this session: clj-kondo on a scratch namespace/test/schema file
(metadata symbols alias-resolved; test and schema keywords attributed), the four link spellings in
a Clojure 1.12 JVM, and `datahike.api/pull` runtime metadata on default (`(emit-api)` source).
