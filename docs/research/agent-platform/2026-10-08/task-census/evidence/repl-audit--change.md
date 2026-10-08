# REPL-only change-and-land audit (lane repl-audit-change, agent `audit-change`, sci, 2026-09-28)

Tokens are chars/4 of the reply as shown. Fix class: (a) missing owner fn, (b) producer returns garbage/loses data, (c) needs schema render, (d) value-printer.
Environment note: root replaced the JVM 4 times during the audit (pid 77844 -> 81023 -> 82291 ...). My branch `:agent-8fd9f4299ba5` and its defs survived every restart (good). Not attributable: one propose-merge! reply "PREPL closed" coincided with a restart.

| # | op / exact form | verdict | tok | ms | focused or garbage | fix |
|---|---|---|---|---|---|---|
| 0a | `(help)` | works | ~600 | 192 | focused; one dense paragraph of rules | - |
| 0b | `(dir my.program)` | awkward | ~6,000 | 54 | GARBAGE: :value is a STRING of EDN clipped "...+20514", full contract schemas per row | (b)/(c) dir returns a string, not data rows; needs a render (sym + first doc line) |
| 0c | `(dir user)` | awkward | ~60 | 8 | empty rows, empty schemas (help says use it to list own publics) | (b) |
| 0d | `(ns-publics 'my.program)` names | works | ~80 | 1 | focused. Discovery result: NO public for lint, diff-vs-cluster, contract-compile, boot check, branch listing of changes | (a) |
| 0e | `(println (:doc (meta #'x)))` for 12 vars | works | ~1,500 | 1 | docs focused; reply adds :findings :large-output because I used println (an agent has no other way to read a doc: `(doc sym)` returns?) | (d) |
| 1 | `(in-ns 'my.auditchange)` `(defn add2 {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 2))` | works, awkward | ~25 | 6 | terse `#'my.auditchange/add2`; does NOT say admitted as program entity, contract compiled, digest, or which tests reach it | (c) Var render should say admitted/digest/contract/reaching tests |
| 1b | `(defn nocontract [x] (* x 2))` | AWKWARD | ~20 | 0 | NO uncontracted warning, contrary to CLAUDE.md ("growing warning beside its value") | (b) producer of REPL admission omits the warning |
| 1c | `(deftest add2-test (is (= 5 (add2 2))))` | works | ~20 | 1 | terse Var only | (c) |
| 1d | two forms in first reply of a fresh agent: `(defn nocontract ..)(deftest ..)` | BLOCKER-ish | ~80 | 3 | `{:error "(my.agent/done) must be the last form of your reply"}` + a `:wait "need input"` disposition; second call with same forms one-per-call worked; second time two defns in one call worked | (b) known task branch-mcp-takes-one-form (resources/seon/tasks/seon.dev.mcp.edn) but the message is wrong/misleading: it names `done`, which I never wrote |
| 1e | `(defn lint3 {:malli/schema [:=> [:cat :int] :int]} [x] (add2 x x))` (wrong-arity call) | BLOCKER-class GARBAGE | ~15 | 1 | reply is `{:error "#'my.auditchange/lint3"}`: the string of the Var in the error slot, NO message. Program history then says "No program declaration names my.auditchange/lint3" yet the SCI Var exists and is callable (`(lint3 1)` runs). So refusal is silent and the SCI world and the program disagree. | (b) producer of defn admission: refusal reason lost (probably static arity finding); root cause: admission returns an error whose message never reaches the reply |
| 2a | redefine: `(defn add2 ... (+ x 3))` | works, awkward | ~25 | 2 | bare Var; no old/new digest, no "tests now stale/reaching" | (c) |
| 2b | `(my.program/ns-unmap! {:seon.program/subject 'my.auditchange/dbl})` | works (good refusal) | ~120 | 3 | focused error: "expected a namespaced symbol, got a map. Fix: Supply a namespaced symbol." Note sibling `history`/`callers` take the map form: inconsistent | (a) unify request shape |
| 2c | `(my.program/ns-unmap! 'my.auditchange/dbl)` | works | ~330 | 769 | five identical boilerplate `:seon.program/unknown` gaps (outside-graph, dispatch-not-modelled, arity-unknown-sites count 0, macro-mediated, unresolvable-callers) ~250 tokens of constant prose; ~0.77 s for a 1-function delete | (c) render: omit gaps that are empty/constant; (d) none. Perf: 52k calls, seon.db/read-declarations/with-declarations repeated 3300x |
| 2d | `(my.program/remove-ns! 'my.auditscratch)` | works | ~330 | 606 | same boilerplate | (c) |
| 2e | ns-unmap! of a symbol whose Var exists only in SCI (lint2) | awkward | ~60 | 1 | "No program declaration names ..."; Var stays in SCI, no way shown to drop it | (a) |
| 3 | lint/analysis findings: `(my.program/history {:seon.program/subject 'my.auditchange/add2})` | works (partial) | ~500 | 88 | focused: source, digest, calls, call-arities, spec. NO kondo findings (unused binding y, unresolved symbol, arity) surfaced anywhere. Unresolved symbol in a defn body is a hard SCI resolve error naming the symbol (good, ~150 tok); arity error only appears at CALL time as a fault (task "faults of lint2") | (a) missing `my.program/lint` (analysis findings of my declarations by span) |
| 3b | `(seon.fn/contract-findings)` (found by ns search, not by help) | works, GARBAGE | ~1,000 shown, value 283,528 tokens | 398 | whole-program: 4,500 rows, `:large-value` finding says "select what you need". Takes a db only; no per-ns/subject filter; I had to filter `(filter #(= "my.auditchange" (namespace (:seon.fn/sym %))))` -> 1 focused row saying nocontract is `missing-spec` | (a) scope arg (namespace/changed); not exposed in my.* |
| 4 | contract compile check | MISSING | - | - | only `contract-findings` (static) and the arming at defn; no "compile against the projection" entrance under my.* | (a) |
| 5a | `(my.test/run-owned {:seon.ns/name 'my.auditchange})` (first JVM) | works | ~800 | 2700 | RED shown: fail-message "expected: (= 5 (add2 2))\nactual: (not (= 5 4))"; but also 16 fields of digests/ids/tx, plus :out duplicating the message, plus :slow finding | (c) test-result render: sym, pass/fail, failure text, expected/actual only. 2.7 s for 1 test (281k calls) = over bound |
| 5b | red -> fix (`+ x 3`) -> green | BLOCKED | | | after root's restart every run fails (below) so green never observed | see blocker B1 |
| 5c | `(my.test/check {})` | BLOCKED | | >32,000 | no result in 32 s, connection closed, outcome unknown | (b) B1 |
| 5d | `(my.test/check {:seon.test/changed ['my.auditchange/add2]})`, `run-owned`, `propose-merge!` (all three) | BLOCKER B1 | ~350 | 1,000-3,700 | "The test world's program overlay (3166 entries) was refused: seon.db/transact! refused transaction data at [2679 :seon.task/tasks]: expected a set of count at least 1, got a set." Contradictory wording ("at least 1, got a set" = an EMPTY set); index 2679 is an overlay position, not an entity, so the agent cannot find it; the error is repeated verbatim 4 times (chain duplicates message) | (b) producer of the overlay: an entity's `:seon.task/tasks` is `#{}` (likely default's tree diverges from published program: 3,166 entries differ, ~all of my branch's overlay is foreign to my change). Look: src/seon/test.clj:1852-1892 (overlay read/refusal), the value builder that stamps :seon.task/tasks; refusal should name the entity identity and drop empty cardinality-many at the producer. Wedges every test/propose for every agent |
| 6 | boot/publication check | MISSING | | | `runtime_status` (MCP) works, ~1,000 tokens, top-5 health rows with clues + `:seon.render/operations` — focused. In-REPL: `(seon.health/report {:seon.boot/cluster-name "default"})` exists (found by ns listing) but no `my.*` entrance for "does my branch load/adopt". `bin/seon status` prints "SEON status OK" while the cluster is still starting (layers prepl+store only), and the raw eval error says so properly | (a) `my.program/check`-style entrance; (b) status OK while :starting |
| 6b | health of MY branch | awkward | | | runtime_status ranks cluster-wide faults incl. my scratch `my.auditchange/lint2`, and other lanes' `user/bad3` : agent's scratch faults become cluster tasks visible to all (`my.task/open` listed lint2 as claimable) | (c) filter by branch |
| 7 | diff of my branch vs cluster | MISSING | | | `my.program/overrides` -> `[]` (indexed src roots only, empty although I hold 4 changed declarations). `seon.test/changes` needs (execution recording), not callable by agent. No `my.program/diff`. Tell-tale: (my.agent/branch) -> `{:branch :agent-8fd9f4299ba5 :mode :live}` only | (a) missing owner fn: diff value of declarations by identity/digest (merge task already needs it: "chooses from the diff value") |
| 8a | `(my.task/open)` | works, focused | ~500 | 13 | 3 tasks, each with intent + `(my.task/claim! ...)` offered | - |
| 8b | `(my.program/propose-merge! {})` unassigned | works | ~70 | 17 | "seon.task is refused: unassigned." with agent, branch, empty assignment. Doesn't offer `my.task/open` | (c) add operations |
| 8c | `(my.task/claim! {:seon.task/assigned "0b04faddd648"})` | works, focused | ~120 | 32 | returns assignment + offered `(my.program/propose-merge! {})` | - |
| 8d | tag the test: `(deftest ^{:seon/tasks #{"0b04faddd648"}} add2-test ...)` | UNKNOWN | ~20 | 2 | accepted; I guessed the metadata key from the fault-task intent ("a deftest naming this task"); nothing in help/docs/reply says how to name a task; `my.issue/tests!` doc is one line "Add success tests to an existing issue" | (a)/(c) claim! reply should say how tests name the task |
| 8e | propose-merge! after claim | BLOCKED by B1 | | 1,038 | same overlay refusal; root's prepare/accept never reached | B1 |
| 9 | write-back | not observable | | | `my.program/write-back!` doc: needs `{:seon.source/accepted accepted :seon.fn/root path}`, "Only the cluster's own custody (root) writes files." Entrance exists for root; no way for an agent to ask "what will change on disk" (preview) | (a) preview |
| 10 | `(my.agent/retire-branch! {:seon.store/branch :agent-8fd9f4299ba5})` | works, awkward | ~25 | - | reply `{:seon.error/message "run transition refused: admission-closed"}` — it was the SUCCESS (own branch retires, admission closes) but is delivered as an error; a following `(my.agent/branches)` gives the same string. No confirmation of retirement | (b) own-branch retire must reply a value naming :my.agent/retiring? |
| misc | reply metadata on every call | (d) | ~40-250 | | `:repeated [[seon.db/database-value? 719 0] ...]` on most calls: profiler noise (not agent-actionable) inside every reply; `:calls` similar | (d) show only when over a bound |

## Ranked gap list

Blockers to working REPL-only (in order)
1. **B1 test overlay refused** (`[2679 :seon.task/tasks]`, 3,166 entries): no test, check, run-owned or propose-merge! works, so the whole loop after step 4 is dead. Producer bug + unhelpful error (b). src/seon/test.clj:1852-1892.
2. **defn silently not admitted, reply `{:error "#'ns/f"}` with no reason** (wrong-arity call in body): SCI Var exists, program does not (b). Highest data-integrity risk: agent believes it defined something.
3. **No diff of my branch vs cluster** (my.program/overrides is `[]`): agent cannot see what it will propose (a).
4. **No lint entrance** (kondo findings by span for my declarations) and **no contract-compile check under my.\*** (only whole-program `seon.fn/contract-findings`, 283k tokens) (a).
5. **Retire own branch replies an error string** and the branch stops answering (b).
6. Multi-form first run on a branch fails with an error naming `done` (known task branch-mcp-takes-one-form) (b).
7. How a test names a task is undiscoverable (a/c); no write-back preview for agents (a); boot check has no my.* entrance (a).

Worst output
1. `(dir my.program)`: ~6,000 tokens, a clipped STRING (b/c).
2. `seon.fn/contract-findings`: 283,528-token value, no scoping (a).
3. `my.test/run-owned` result: ~800 tokens for one test, 16 id/digest keys, duplicate :out; 2.7 s (c).
4. ns-unmap!/remove-ns!: 250 tokens of constant unknowns boilerplate x every delete; 0.6-0.8 s (c).
5. `:repeated` profiler rows on every reply (d).
6. B1 error text repeated twice via :chain, contradictory "at least 1, got a set" (b).
7. Var-only reply for defn/deftest: says nothing of admitted/contract/reaching tests; no uncontracted warning (c/b).
