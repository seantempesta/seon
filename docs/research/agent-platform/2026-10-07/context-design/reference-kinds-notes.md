---
type: notes
status: active
created: 2026-10-07
tags: [notes, context-design, tasks]
---

# Every way something is referenced in Seon (lane context-mockups, 2026-10-07)

Measured on default (installed `42e569d9d`, store basis 536872749) from agent `context-mockups`'s SCI context, read-only.
The corpus is the 392 tasks on default (342 `:function`, 44 `:namespace`, 3 `:schema`, 2 `:work`, 1 `:merge`); the
working tree holds 366 file tasks. The counting forms are in the appendix and ran in 840 ms. Text references are
candidate spans in `:seon.task/intent`, found by a probe's regexes (not production code). "Resolves" means an entity
with that identity exists on default.

## The kinds

### 1. Fully qualified symbol naming a definition (function, macro, def)

- **Syntax**: `seon.db/q`, backticked in docstrings by law; bare in nearly all task text (only 1 of 340 task uses is
  backticked).
- **Resolve**: identity `:seon.fn/sym` (pull `[:seon.fn/sym s]`). `:seon.fn/defined-by` says which form made it.
- **Render**: `:seon.fn/fn` → `seon.render.ns/function-ai`. Today it returns `{:seon.fn/sym, :seon.fn/doc <first
  physical line>}`, and at distance ≥ 1 also `:seon.render.ns/source-lines` (a vector of strings) plus `:seon.fn/spec`
  when the source lacks `:malli/schema`. A live Var value also has `:seon.render.var/var` →
  `seon.render.var/render-ai` (symbol, missing-contract warning, pending or newly red reaching tests).
  See [real-renders/03](real-renders/03-function-seon.db.q.md) and [04](real-renders/04-private-functions.md).
- **Counts**: intents hold 340 resolving spans and 44 dangling (e.g. `seon.print.value/bounded-text`,
  `seon.cluster/mcp-get-value`, `my.note/add!`, `seon.turn.batch/render-request`). Across intents and must-reads, the
  505 distinct resolving symbols were made by `defn` 340, `defn-` 126, `def` 9, `defmacro` 3 and `defonce` 1. There is
  no separate macro or def kind: one identity serves all of them, and `:seon.fn/macro?` and `:seon.fn/defined-by` tell
  them apart.

### 2. Fully qualified symbol naming a test (deftest)

- **Resolve**: `:seon.test/sym`.
- **Render**: `:seon.test/test` → `seon.render.test/render-ai`. Today it returns `{sym, state "pass"|"fail"|…,
  run-basis-t, host}`, and failure reports when red. It shows nothing of what the test asserts. See
  [real-renders/05](real-renders/05-test-proof.md).
- **Counts**: intents name tests 0 times; must-reads name one. Tests reach tasks through `:seon/tasks` tags (kind 8).

### 3. Task symbol `ns/task-name`

- **Syntax**: `seon.db/the-query-wrapper-costs-36x-datahike`, the CLAUDE.md form for a reference to a task.
- **Resolve**: at HEAD the identity is `:seon.task/sym`. **It is not installed on default**, where a query naming it
  refuses ("seon.db/q cannot read uninstalled attribute :seon.task/sym"). On default, resolve `ns` and `name` to
  `:seon.task/id` with `seon.task/task-handle`.
- **Render**: `:seon.task/task` → `seon.render.ns/task-ai`. Today it returns id, kind, ns, name, intent and must-read
  (as a set), plus steps and progress. It shows no proofs and no evidence. The reply then appends `:seon.related/data`
  from the related walk. See [real-renders/01](real-renders/01-task.md).
- **Counts**: intents use this syntax 2 times.

### 4. Task handle (12 hex characters)

- **Syntax**: `"d6e3b775f474"`. This is the installed `:seon/tasks` tag value; HEAD's working tree converts the tags
  to symbols.
- **Resolve**: `:seon.task/id`.
- **Render**: as kind 3, once resolved. On its own it is a string with no render.
- **Counts**: intents hold 18 handles, all resolving.

### 5. Namespace symbol

- **Syntax**: `seon.render.walk`.
- **Resolve**: `:seon.ns/name`. Every task also carries one structurally, `:seon.task/ns`: 369 of 392 resolve; the
  23 that dangle include `seon.operator` (9), `seon.dev.mcp` (5), `seon.print.value` (3) and `seon.documentation`.
- **Render**: `:seon.ns/ns` → `seon.render.ns/render-ai`. It returns name, doc (first physical line) and requires, and
  at distance ≥ 1 the whole `dir` table (`seon.sci.eval/directory-value`) as positional rows. For seon.db that is
  4,132 tokens, 166 ms and 18,883 armed calls. See [real-renders/02](real-renders/02-namespace-seon.db.md).
- **Counts**: intents hold 117 resolving spans and 34 dangling (e.g. `my.plan`, `seon.help`, which is a schema
  namespace and not a code namespace, and `seon.config.eval`); 6 name dependency namespaces.

### 6. Fully qualified keyword: a registered schema

- Schema registration, attribute, entity schema and error type all share one identity: `:seon.schema/key`. Of the 126
  distinct resolving keywords in intents, 75 are installed Datahike attributes (`:db/ident`) and 5 are `…-error`
  types.
- **Render**: `:seon.schema/schema` (and the registration value `:seon.schema/registration`) →
  `seon.render.schema/render-ai`. It returns key, definition as data, referenced-by, the contracts using it, and counts.
  This is the most readable render today. See [real-renders/06](real-renders/06-schemas.md).
- **Counts**: intents hold 179 resolving spans and 42 dangling (e.g. `:seon.issue/id`, `:seon.task/done-when`,
  `:seon.batch/timings`, `:seon.error/cause`).

### 7. Fully qualified keyword: not a schema

- **Metadata keys**: `:seon/tasks`, `:seon/must-read`, `:seon/see-also`, `:malli/schema`. Intents mention them 4 times.
- **Dependency keys**: `:db/noHistory`, `:malli.core/invalid-input`. Intents mention them 9 times. They have no Seon
  entity; Malli or Datahike documents them.

### 8. Definition metadata `:seon/tasks`, `:seon/must-read`, `:seon/see-also`

- **Syntax**: `^{:seon/tasks #{'ns/task-name}}` on a deftest marks a proof. On a defn or ns it marks a subject.
  Installed default still holds handle strings.
- **Resolve**: `seon.fn/task-links` indexes them into `:seon.task/tasks`, `:seon.fn/must-read` and `:seon.fn/see-also`.
  `seon.task/task-edges` answers `{subjects proofs referrers}`. `seon.task/task-findings` reports dangling tags.
- **Render**: none of its own. The related walk shows them under `:seon.task/_tasks`.
- **Counts (default)**: 76 tag edges: 36 proof, 35 function subject, 5 namespace subject. Only 62 of 392 tasks are
  tagged: **27 have a proof** and 36 a subject; 0 tags dangle. At HEAD the working tree holds 86 `:seon/tasks` tags.

### 9. Task `:seon.task/must-read`

- **Syntax**: a set of bare qualified symbols in the task EDN.
- **Resolve**: `:seon.program/edge-symbol` values, resolved as kind 1.
- **Render**: shown as a set by `task-ai`; the related walk adds each function's doc line.
- **Counts**: 711 entries on 370 tasks: 669 functions, 1 test and **41 dangling** (e.g.
  `seon.call-preparation/hook`, `seon.render.web/debug-value-html`, `my.program/overrides`). Nothing reports them.

### 10. File paths: `:seon.task/evidence` and paths in prose

- **Syntax**: a repository-relative path (`docs/…/x.md`), or `file.clj:633` for a file and line.
- **Resolve**: file existence (`my.fs/stat`). The schema says evidence is "linked, never parsed".
- **Render**: none.
- **Counts**: 456 evidence entries and 406 path spans in intents. In the working tree's task files there are 298
  distinct path-like tokens: 226 exist and up to 72 are missing (an upper bound: the probe also catches
  `seon.config/effective` as `config/effective`). There are 81 `file:line` spans. CLAUDE.md forbids `file:line` in
  source, and task text is where those spans go stale.

### 11. Result handle `result/e…`

- **Resolve**: a symbol bound to a live value in the SCI context that produced it, and only there. A handle copied
  into task text from another context is unresolvable. Probe: `result/e7b3b69b578ae` gave "Unable to resolve symbol".
- **Render**: the held value's own selected render. A reply's dig forms (`(get-in result/e… [...])`) are references of
  this kind.
- **Counts**: intents hold 2, and both dangle by construction.

### 12. Commit id (7-12 hex characters)

- **Resolve**: `git cat-file -e <id>^{commit}` in the checkout or the datahike fork.
- **Render**: none.
- **Counts**: 140 distinct candidates: 97 are commits, 38 resolve to nothing (some are Seon 12-hex ids such as
  evaluation or run ids, others are commits of other forks), and 5 are numbers. A commit tells the agent history, not
  what to do. CLAUDE.md keeps history in Git.

### 13. Alias-qualified and prose-slashed names

- **Examples**: `d/q`, `me/humanize`, `db/q`, `query-patterns/parsed-nodes` (meaning "and"), `ns/test-name` (a
  placeholder).
- **Resolve**: only through the owning namespace's aliases (`:seon.ns/aliases`, components with
  `:seon.ns.alias/local` and `:seon.ns.alias/target-ns`), and only when the reader knows which namespace's aliases
  apply. They are ambiguous otherwise.
- **Counts**: 118 spans. The task text of the chosen task uses three of these.

### 14. Dependency Vars

- **Examples**: `datahike.api/q`, `clojure.core/future-call`, `clojure.java.io/resource`.
- **Resolve**: SCI `resolve` when the dependency is exposed. `(doc datahike.api/q)` answers
  `:seon.sci.eval/documentation-unavailable`.
- **Render**: none in Seon.
- **Counts**: 8 resolve in SCI; others count as dangling in kind 1.

### 15. Document sections

- **Examples**: `§3`, `spec 7.14`.
- **Resolve**: none (a Markdown heading in an unnamed spec).
- **Counts**: 11.

### 16. Backticked spans

- **Resolve**: none in task text. CLAUDE.md: "A backticked span reading as one fully qualified symbol or keyword is a
  reference … the index resolves it". **There is no such index**: `seon.fn` indexes metadata links and analyzer
  `:seon.fn/references`, never docstring spans. This is a TARGET, recorded as task
  `seon.fn/backticked-docstring-references-have-no-index`.
- **Counts**: intents hold 15 backticked spans: 13 are words or commands (`data-text`, `bin/seon check`), 1 is a form
  and 1 is a dependency Var.

### 17. Runtime identities that are values

- **Examples**: agent ids `"root"`, branch keywords `:agent-80e4a2ca8c45`, cluster names, run ids.
- **Resolve**: `:seon.agent/id`, `:seon.test.run/id` and so on. These are observed names, values not refs, by the
  CLAUDE.md rule.
- **Render**: through their entity's render once pulled.

### 18. Offered forms

- **Examples**: `:seon.render/operations` and requery forms (`seon.render/form`).
- **What they are**: references as runnable code. They are shown, never run by the render. They are the cheapest kind
  to follow, because the agent copies them.

## Totals (intent spans plus structured fields, 392 tasks)

| Kind | References | Resolve | Dangle | Declared AI render |
|---|---:|---:|---:|---|
| Qualified symbol: definition | 384 | 340 | 44 | `seon.render.ns/function-ai` |
| Qualified symbol: test | 0 | 0 | 0 | `seon.render.test/render-ai` |
| Task symbol | 2 | 2 | 0 | `seon.render.ns/task-ai` |
| Task handle | 18 | 18 | 0 | (via the task) |
| Namespace (text) | 158 | 117 (+6 dependency, +1 test ns) | 34 | `seon.render.ns/render-ai` |
| Namespace (`:seon.task/ns`) | 392 | 369 | 23 | `seon.render.ns/render-ai` |
| Schema keyword | 221 | 179 | 42 | `seon.render.schema/render-ai` |
| Other keyword (metadata, dependency) | 13 | n/a | n/a | none |
| `:seon/tasks` tag edges | 76 | 76 | 0 | none (related walk) |
| `:seon.task/must-read` | 711 | 670 | 41 | via kind 1 |
| Evidence and paths | 862 | 226 of 298 distinct | ≤ 72 | none |
| Result handle | 2 | 0 | 2 | value's own |
| Commit id | 140 | 97 | 38 (+5 numbers) | none |
| Alias-qualified or slashed | 118 | ambiguous | ambiguous | none |
| Dependency Var | 8 | 8 | n/a | none |
| Section | 11 | 0 | 11 | none |

325 of 392 tasks hold at least one symbol, keyword or namespace reference in their intent.

## What this means for a task brief

- **Five kinds carry a brief**: definition symbols, test symbols (through tags), schema keywords, the task's namespace,
  and its must-reads. Each has one identity and one declared render, so a brief is: collect the references, dedupe by
  identity, call each declared render once.
- **Dangling references are common** (about 11% of symbols, 19% of keywords, 6% of must-reads). The brief should show
  each as a hole, `{:ref x :resolves-to nothing}`, never drop it silently. Task
  `seon.task/task-text-references-are-never-resolved`.
- **Prose names are the weak spot**: alias-qualified names (118) and bare private names. The fix belongs at the
  author: task text should write `seon.db/query-patterns`, not `query-patterns`. Ingestion can then refuse unresolved
  names, as it already refuses dangling `:seon/tasks` tags.
- **History kinds** (commits, file:line, result handles, sections) resolve poorly and help an agent least. They belong
  in evidence documents, not in the intent the agent reads.

## Appendix: the measurement forms

Run in agent `context-mockups`'s SCI context, namespace `scratch.context-mockups`, form by form.

```clojure
(def db0 (seon.db/db))
(def tasks (seon.db/q '[:find [(pull ?t [:seon.task/id :seon.task/ns :seon.task/name :seon.task/kind :seon.task/intent
                                          :seon.task/must-read :seon.task/evidence :seon.task/outcome]) ...]
                        :where [?t :seon.task/id _]] db0))
(def fn-syms (set (seon.db/q '[:find [?s ...] :where [_ :seon.fn/sym ?s]] db0)))
(def test-syms (set (seon.db/q '[:find [?s ...] :where [_ :seon.test/sym ?s]] db0)))
(def ns-names (set (seon.db/q '[:find [?s ...] :where [_ :seon.ns/name ?s]] db0)))
(def schema-keys (set (seon.db/q '[:find [?s ...] :where [_ :seon.schema/key ?s]] db0)))
(def task-syms (set (map #(symbol (str (:seon.task/ns %)) (:seon.task/name %)) tasks)))
(def task-ids (set (map :seon.task/id tasks)))
(def roots #{"seon" "my" "clojure" "datahike" "malli" "sci" "konserve"})
(defn trim-tok [s] (clojure.string/replace s #"(?:'s|[.\-,;:])+$" ""))
(defn sym-kind [s]
  (cond (fn-syms s) :function (test-syms s) :test (task-syms s) :task
        (and (roots (first (clojure.string/split (namespace s) #"\."))) (resolve s)) :dependency-var
        (not (clojure.string/includes? (namespace s) ".")) :alias-qualified
        :else :dangling))
(defn kw-kind [s]
  (let [k (keyword (subs (trim-tok s) 1))]
    (cond (schema-keys k) :schema-key
          (#{:seon/tasks :seon/must-read :seon/see-also :malli/schema} k) :metadata-key
          (#{"db" "malli.core" "db.fn" "db.type" "db.cardinality" "db.unique"} (namespace k)) :dependency-key
          :else :dangling)))
(defn ns-kind [s]
  (let [n (symbol (trim-tok s))]
    (cond (ns-names n) :namespace (find-ns n) :dependency-ns
          (clojure.string/ends-with? (str n) "-test") :test-ns-unindexed :else :dangling)))
(defn refs [text]
  (let [bt (map second (re-seq #"`([^`]+)`" text))
        stripped (clojure.string/replace text #"`[^`]+`" " ")
        q-kw (map second (re-seq #"(?<![\w/])(:[a-zA-Z][\w.\-]*/[\w.\-\?!\*<>=]+)" stripped))
        no-kw (clojure.string/replace stripped #":[a-zA-Z][\w.\-]*/[\w.\-\?!\*<>=]+" " ")
        handles (re-seq #"result/e[0-9a-f]+" no-kw)
        paths (map first (re-seq #"(?<![\w.])((?:docs|src|test|resources|config|bin|script|reference-code|data|tmp|\.agents)/[\w./\-]+|[\w\-]+\.(?:clj|cljc|edn|md|cljs|sh)(?::\d+(?:-\d+)?)?)" no-kw))
        no-path (reduce #(clojure.string/replace %1 %2 " ") no-kw (sort-by (comp - count) paths))
        q-sym (map second (re-seq #"(?<![\w.:/])([a-z][\w\-]*(?:\.[\w\-]+)*/[\w\-\?!\*<>=']+[\w\-\?!\*<>=]*)"
                                  (clojure.string/replace no-path #"result/e[0-9a-f]+" " ")))
        ns-sym (map second (re-seq #"(?<![\w.:/\-])((?:seon|my|clojure|datahike|malli|sci|konserve)(?:\.[a-z][\w\-]*)+)(?![\w/])"
                                   (clojure.string/replace no-path #"[a-z][\w\-]*(?:\.[\w\-]+)*/[\w\-\?!\*<>=]+" " ")))
        hex (map second (re-seq #"(?<![\w/])([0-9a-f]{7,12})(?![\w])" no-path))
        sect (re-seq #"(?:§\s?\d+[\w.]*|\b(?:spec|plan) \d+(?:\.\d+)+)" text)]
    {:backtick bt :keyword q-kw :handle handles :path paths :qsym q-sym :ns ns-sym
     :hex (filter #(re-find #"\d" %) hex) :section sect}))
(def per-task
  (for [t tasks :let [r (refs (:seon.task/intent t))]]
    {:task (symbol (str (:seon.task/ns t)) (:seon.task/name t)) :refs r
     :sym (map #(sym-kind (symbol (trim-tok %))) (:qsym r))
     :kw (map kw-kind (:keyword r)) :ns (map ns-kind (:ns r))
     :hex (map #(if (task-ids %) :task-id :commit-or-other) (:hex r))
     :must-read (map #(cond (fn-syms %) :function (test-syms %) :test (ns-names %) :namespace :else :dangling)
                     (:seon.task/must-read t))}))
(defn tally [k] (frequencies (mapcat k per-task)))
{:qualified-symbols (tally :sym) :keywords (tally :kw) :namespaces (tally :ns) :hex (tally :hex)
 :must-read (tally :must-read) :evidence (reduce + (map (comp count :seon.task/evidence) tasks))}
;; :seon/tasks tag edges (installed: handle strings; HEAD: task symbols)
(frequencies (map #(nth % 2)
                  (seon.db/q '[:find ?t ?s ?kind
                               :where [?e :seon.task/tasks ?t]
                               (or (and [?e :seon.test/sym ?s] [(ground :proof) ?kind])
                                   (and [?e :seon.fn/sym ?s] [(ground :fn-subject) ?kind])
                                   (and [?e :seon.ns/name ?s] [(ground :ns-subject) ?kind]))] db0)))
```

Commit and path checks were shell loops over the extracted tokens (`git cat-file -e <id>^{commit}`; `[ -e path ]`).
