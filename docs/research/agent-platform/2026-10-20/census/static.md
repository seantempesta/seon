---
title: "Static census of src and script (2026-10-20)"
type: research
---

# Static census of src and script (2026-10-20)

Lane census-static. Evidence only; nothing in `src`, `script` or `test` changed. Data: `functions.edn` (one map per function), `tests.edn` (one per test), `namespaces.edn` (one per namespace with its verdict), `verdicts.edn` (the hand-written verdict inputs), `ideas.md` (the headline: ideas worth carrying over).

Reproduce: dump forms in `dump-forms.clj` (JVM-mode reads on default), then `analyze.clj`, `report.clj`, from the checkout root with only Clojure on the classpath.

## Data I could not get, exactly

- **Stored reaching-test facts do not exist on default.** `:seon.test/reach`, `:seon.test/reach-unknown` and `:seon.test/run` have zero datoms. The reach columns are my own static closure over `:seon.fn/calls` (every call edge the analysis stored, src, script and test helpers), run from each of the 3471 stored test entities. It undercounts tests that reach a function by `requiring-resolve`, `resolve` or a quoted name (266 test sites, per `requiring-resolve-inventory.md`): for example `my.test` shows 0 reaching tests. It overcounts only through hubs: a function almost every test touches (for example `seon.error.problem`) reads 2,500+, so read the reach counts of hub functions as everything.
- **Stored test results are almost nonexistent.** Default holds 3 `:seon.test.run/id` entities (all from 2026-10-19 08:46, history has the same 3): 2 tests with fail counts, 1 green. The other stored evidence is the red list of one partial-history operator run, `data/clusters/default/logs/operator-check-20261019T230731.687.edn` (candidate `d7e547e25`, 746 executed, 41 red): it records reds only, never a pass. So a test is `:red` if it is in either source, `:green` only if its stored member run passed, else `:never-run`. Test results kept on the 62 other branches of default's store were not read (a per-branch history walk is not a small request).
- **Per-function "last result"** is derived from those results through my static reach, and means `reached-by-red` (at least one stored-red test reaches it), `reached-by-green-only`, `never-run` (reached, but no reaching test has any stored result) or `no-tests` (no static reach). Because the reds sit on hubs, `reached-by-red` is a very coarse signal; the red/green counts are in the rows (`:census/red-tests`, `:census/green-tests`).
- **Assertion counts** are a textual count of `(is`/`(are`/`(thrown?` forms in the stored test source, not a stored fact (none is stored). `:census/asserts` 0 means the test body has none of those forms (it may assert through a helper).
- **Timing bound** is `:seon.test/long-ms`, else `:seon.test/check-time-limit-ms`, else the 5000 ms default; none of the measured run times except the 3 stored runs exist.
- **Lines** are the stored `:seon.fn/source` line count of each definition (sum 104365 against 107,546 file lines in `src`). **Entrants** are not a stored fact: I derived them (below).
- **Run-time lookup cross-check** against `docs/research/agent-platform/2026-10-19/requiring-resolve-inventory.md`: that document lists categories and ten cycles, not every site. I parsed the per-site scratch JSON it names (`tmp/rr-inventory/final.json`, gitignored, written 2026-10-19 20:10): 347 non-prose enclosing functions, of which 111 are src/script functions still present on default; my call-edge detection finds 95 of those 111, misses 16 (the lookup is inside a nested `delay`/`fn` that the analysis attributes to another Var, or the function changed since) and adds 30 the 10-19 scan did not list (post-scan changes, `ns-resolve`/`var-get` forms). Lists in `rr-crosscheck.edn`.

## Totals

| Measure | Value |
| --- | --- |
| Functions (every `def` form in `src` + `script`) | 5555 (src 5053, script 502) |
| Lines in those definitions | 104365 |
| Kinds | {:constant 336, :def 219, :fn 4985, :macro 7, :type 8} |
| Private / public | 3442 / 2113 |
| Host-bound | 97 |
| Namespaces | 156 |
| Tests (stored deftests; in `test/`) | 3471 |
| Test namespaces | 439 |
| Run-time lookup functions (src/script) | 131 |
| Functions whose body has a `try` (catch/finally) | 387 |
| Functions with a docstring | 4320 |

## Contract status

Applies to `:fn` and `:macro` kinds (4992 of 5555; constants, plain defs and types are `:not-applicable`). `complete` = a stored `:seon.fn/spec` with no `[:fn`, no `:any`, no `:some` and fewer than two bare `:map`; `weak` = a spec with at least one of those; `none` = no stored spec; `malformed` = `:seon.fn/malformed-spec` (preserved as data, not instrumented).

| Status | All functions | Private | Public | Lines |
| --- | --- | --- | --- | --- |
| complete | 3068 | 1595 | 1473 | 62568 |
| weak | 830 | 457 | 373 | 23099 |
| none | 1092 | 1030 | 62 | 15656 |
| malformed | 2 | 1 | 1 | 144 |

| Verdict | complete | weak | none | malformed |
| --- | --- | --- | --- | --- |
| port | 1704 | 454 | 496 | 0 |
| replaced | 1051 | 306 | 441 | 0 |
| unclear | 313 | 70 | 155 | 2 |

## Replacement verdicts (against BAGGAGE.md and NEEDS.md)

`port` means the **concept** is needed by the Jolt version; it does not mean the code is carried over. A `port` namespace names what inside it the Jolt architecture drops (`replaced-parts` in `namespaces.edn`). `replaced` means the Jolt architecture removes the need. `unclear` has a stated reason (an owner decision or no row). Citation per namespace is in `namespaces.edn` (`:census/rows`), written as `NEEDS#n` (capability table row n), `NEEDS-OD n` (owner-decides n) or `BAGGAGE:<section>`.

| Verdict | Namespaces | Functions | Lines | Share of lines | Run-time lookup fns | try fns | Uncontracted (none) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| port | 49 | 2950 | 56187 | 54% | 88 | 207 | 496 |
| replaced | 78 | 2017 | 38526 | 37% | 41 | 149 | 441 |
| unclear | 29 | 588 | 9652 | 9% | 2 | 31 | 155 |

`port` namespaces by what the Jolt tree already has (NEEDS status column): lines in `done` namespaces 26902, `partial` 28548, `missing` 577.

NEEDS.md puts the Jolt need at about 11 percent of Seon's lines (~11,400 of ~100,000 including what is still to build). The `port` namespaces here hold 54% of the lines, so the port is overwhelmingly of ideas and tests, not of code: see `ideas.md` for what each idea needs in its simplest form.

### Port namespaces, largest first

| Namespace | Lines | Fns | Jolt status | Rows | What the Jolt architecture drops from it |
| --- | --- | --- | --- | --- | --- |
| `seon.db` | 6176 | 299 | done | NEEDS#41, NEEDS#26 | read evidence capture, projection/commit-graph walkers, per-branch checkout plumbing, error renderers |
| `seon.turn` | 5513 | 218 | partial | NEEDS#9, NEEDS#11, NEEDS#40 | writer-owned transitions across SCI contexts, boot close of interrupted runs, episodes |
| `seon.schema` | 3963 | 180 | partial | NEEDS#1 | projection generations, handed registries, 4,182-schema compile closure, identity-only projections |
| `seon.render.web` | 3518 | 177 | done | NEEDS#38, BAGGAGE:Flow | http-kit adapter, per-tab mult slider, SCI-context renders |
| `seon.test` | 3398 | 104 | done | NEEDS#24, NEEDS#25 | reaching-test selection, evidence reuse by reach digest, admission, platform JVM |
| `seon.test.runner` | 2749 | 136 | done | NEEDS#24 | worker JVMs, claim/terminate bookkeeping, reach-digest reuse |
| `seon.error` | 2587 | 140 | done | NEEDS#26 | writer-owned recurrence, escalation, component reads |
| `seon.cluster.source` | 2413 | 131 | partial | NEEDS#8, NEEDS#21, NEEDS#28 | scratch-branch publication, reach-based contributions, loaded-program identity, test selection admission |
| `seon.operator` | 2360 | 137 | partial | NEEDS#37 | committed-archive start, nuke, class-cache and move-to-HEAD machinery |
| `seon.task` | 1845 | 134 | done | NEEDS#7, NEEDS#36 | chain! installed-model code, branch relink |
| `seon.program` | 1759 | 81 | partial | NEEDS#20 | name->digest runtime derivation, SCI reads |
| `seon.render.value` | 1666 | 96 | done | NEEDS#13, BAGGAGE:Inside seon.render.value | render pairs, database-aware printing, fallback chains, shared budgets |
| `seon.ai` | 1357 | 79 | partial | NEEDS#32 | provider descriptor entities, prompt-cache plumbing |
| `seon.instrument` | 1265 | 43 | done | NEEDS#3 | host wrappers, arming projections, referenced-declaration wrapper preservation |
| `my.program` | 1212 | 71 | partial | NEEDS#20, NEEDS#21 | SCI native!/write observers, referrer vs past-reach split |
| `seon.render.transcript` | 1115 | 71 | partial | NEEDS#15 | schema-declared projection dispatch |
| `seon.context` | 1076 | 72 | partial | NEEDS#9, NEEDS#15 | render-cache units, SCI-context reads |
| `seon.schema.datahike` | 1068 | 73 | partial | NEEDS#43, NEEDS-OD 7, BAGGAGE:Schema to Datahike | vector cells, EDN codecs for unions, projection caches |
| `seon.config` | 962 | 49 | partial | NEEDS#31 | database reconcile boundary and shipped-defaults compiler |
| `seon.batch` | 928 | 63 | done | NEEDS#11 | stage-as-value indirection for SCI entrants, batch world binding |
| `seon.profile` | 874 | 54 | partial | NEEDS#5, NEEDS#6 | per-function profile cells per thread, per-thread allocation attribution |
| `seon.ref` | 818 | 57 | partial | NEEDS#14 | my.program/source lookup by name, SCI lookup |
| `seon.print` | 795 | 64 | done | NEEDS#13, BAGGAGE:Inside seon.print | node generators, validation/registration, unused node kinds |
| `seon.mine` | 648 | 61 | partial | NEEDS#33 |  |
| `seon.schema.edn` | 637 | 33 | partial | NEEDS#1 | duplicate-key admission across 347 resources |
| `seon.merge` | 608 | 26 | partial | NEEDS#28 | squash from merge base inside main's writer, declaration gate |
| `seon.schema.internal` | 577 | 30 | partial | NEEDS#1, BAGGAGE:Schema to Datahike | registration admission, projection cache values |
| `seon.repl` | 545 | 34 | done | NEEDS#11, NEEDS#13 |  |
| `seon.mcp` | 508 | 30 | partial | NEEDS#35 | bridge-without-restart indirection |
| `seon.edit` | 472 | 34 | missing | NEEDS#21 | hook-loading constraints |

### Replaced namespaces, largest first (top 25)

| Namespace | Lines | Fns | Rows | Why |
| --- | --- | --- | --- | --- |
| `seon.sci.eval` | 5352 | 242 | NEEDS#11, NEEDS#45, BAGGAGE:Flow (no SCI contexts) | SCI evaluation, admission, runtime name->digest maps and the SCI reader are removed: an agent's world is a process (native eval). |
| `seon.fn` | 4226 | 175 | NEEDS#20, NEEDS#39, BAGGAGE:Whole namespaces (analysis caches) | Build-time program-graph indexing; K1's IR facts from clj-kondo replace it (callers, search, lints). |
| `seon.cluster` | 3477 | 130 | NEEDS#37, NEEDS#40, BAGGAGE:Flow | Process entry for starting named clusters in one JVM; Jolt root is a Flow graph plus a fork server. |
| `seon.cluster.agent` | 2761 | 115 | NEEDS#40, BAGGAGE:Flow | The agent-graph blueprint (a Flow per agent, SCI context acquisition); agents are forked processes. |
| `seon.sci.kernel` | 1188 | 66 | NEEDS#11, NEEDS#45, BAGGAGE:Flow (no SCI contexts) | SCI evaluation, admission, runtime name->digest maps and the SCI reader are removed: an agent's world is a process (native eval). |
| `seon.render.walk` | 1139 | 48 | BAGGAGE:Inside seon.render.value (with-related) | Bounded neighbourhood traversal feeding render pairs. |
| `seon.dev.markdown` | 1078 | 56 | NEEDS#14 (heading anchors are a line scan), NEEDS#45 | Markdown lint/fix for hooks; reference anchors need only a heading scan. |
| `seon.turn.batch` | 1066 | 60 | NEEDS#11, NEEDS#20, BAGGAGE:Whole namespaces | Program stages of the SCI path: record-form with kondo analysis joined by span; native eval + K1 replace it. |
| `seon.effect` | 908 | 34 | NEEDS#23, BAGGAGE:Flow (work launcher) | Capability request receipts around protected JVM handlers. |
| `seon.fn.analyzer` | 888 | 57 | NEEDS#20, NEEDS#39 | Static analysis for program-graph indexing; K1 (kondo IR) replaces it. |
| `seon.flow` | 860 | 47 | BAGGAGE:Flow, NEEDS#40 | Seon's executors, joins, step cost, fault port; proto uses core.async.flow directly. |
| `seon.sci.reader` | 828 | 46 | NEEDS#11, NEEDS#45, BAGGAGE:Flow (no SCI contexts) | SCI evaluation, admission, runtime name->digest maps and the SCI reader are removed: an agent's world is a process (native eval). |
| `seon.cluster.boot` | 800 | 26 | NEEDS#37 | Boot sequence layers; a Jolt binary starts in 64-172 ms. |
| `seon.cluster.registry` | 781 | 40 | NEEDS#41, BAGGAGE:Flow (cluster plumbing) | Branch lifecycle on a physical store with one connection per process; agents fork. |
| `seon.sci.admit` | 746 | 44 | NEEDS#11, NEEDS#45, BAGGAGE:Flow (no SCI contexts) | SCI evaluation, admission, runtime name->digest maps and the SCI reader are removed: an agent's world is a process (native eval). |
| `seon.error.refusal` | 731 | 42 | BAGGAGE:Whole namespaces (refusal construction with world references) | Diagnostic construction with world references and stack frames; one flat error map instead. |
| `seon.call-preparation` | 724 | 29 | NEEDS#4 | Default filling by schema name; an agent reads (db)/whoami from process state. |
| `seon.cluster.store` | 683 | 38 | NEEDS#41 | Store custody and flock; Jolt Datahike opens LMDB natively. |
| `seon.maintenance` | 649 | 32 | NEEDS#45 (maintenance machinery) | Log rotation/store collection/result projection. |
| `seon.health` | 625 | 36 | NEEDS#45 (seon.health machinery) | Cluster health report; left behind. |
| `seon.problems` | 615 | 31 | NEEDS#45 (seon.health/problems machinery), NEEDS-OD 4 | Derived 'what is wrong now' report. |
| `seon.sci.lookup` | 531 | 38 | NEEDS#11, NEEDS#45, BAGGAGE:Flow (no SCI contexts) | SCI evaluation, admission, runtime name->digest maps and the SCI reader are removed: an agent's world is a process (native eval). |
| `seon.render-bench` | 494 | 55 | NEEDS#16 | Render-pair benchmark. |
| `seon.cluster.wake` | 476 | 16 | BAGGAGE:Flow (wakes by d/listen) | Wake routes over d/listen; the :router proc indexes subscriptions by attribute and entity. |
| `seon.web.jvm` | 425 | 30 | NEEDS#45 (web fetch) | Protected JVM my.web handler. |

### Unclear namespaces

| Namespace | Lines | Fns | Reason |
| --- | --- | --- | --- |
| `seon.render` | 1627 | 96 | Typed projection selector and guarded SCI invocation; OD2 decides whether render pairs live. |
| `seon.fs.jvm` | 872 | 53 | Protected JVM my.fs handler: later; write-back (NEEDS#21) needs a small slice of it. |
| `seon.cluster.message` | 869 | 42 | Message offering via Flow and recipient storage: later, owner decides. |
| `seon.schedule` | 724 | 37 | Cron schedule facts and a per-agent schedule proc; no NEEDS or BAGGAGE row. |
| `seon.embed` | 547 | 47 | Embeddings for search: later, owner decides. |
| `seon.fs` | 472 | 30 | Path-ownership filesystem operations: later; native slurp exists. |
| `seon.schema.admission` | 469 | 26 | Enforcing 'a changed meaning takes a new name' is owner decision OD1. |
| `seon.shell.jvm` | 424 | 20 | Protected shell handler; write-back git needs jolt.process only. |
| `seon.fault` | 407 | 13 | Error recording once per signature: later, owner decides. |
| `seon.render.hiccup` | 402 | 20 | Hiccup to HTML serializer; proto/web may use a Jolt hiccup library (not checked). |
| `seon.blob` | 366 | 32 | Content-addressed blobs: later; printer budget already bounds stored text. |
| `seon.id` | 297 | 14 | Structural digest: proto does not import it; needed again only if merge keys on definition digests. |
| `seon.render.ns` | 286 | 31 | Render pairs are owner decision OD2; everything prints as data meanwhile. |
| `seon.agent` | 232 | 12 | Reads of the calling agent's own record components; the agent record shape in Jolt is proto.agents.* attributes (no NEEDS row). |
| `my.agent` | 211 | 11 | Branch verbs (create-branch!, branches, retire-branch!); NEEDS has no row for them, branch lifecycle moves to fork+merge. |
| `seon.render.data` | 189 | 9 | Entity observations and get-in cursors for URL-routed values; Seon pages only. |
| `seon.render.test` | 174 | 17 | Render pairs are owner decision OD2; everything prints as data meanwhile. |
| `seon.render.var` | 173 | 13 | Render pairs are owner decision OD2; everything prints as data meanwhile. |
| `seon.cost.growth` | 169 | 11 | Growth profile of a function's cost measured generatively from its contract; related to the estimate-first law but not mentioned in NEEDS. |
| `seon.render.schema` | 149 | 11 | Render pairs are owner decision OD2; everything prints as data meanwhile. |
| `my.fs` | 113 | 8 | File verbs for model agents: later, owner decides. |
| `seon.run` | 104 | 4 | Completion/waiting values for an agent session; no NEEDS row. |
| `seon.render.sent` | 97 | 8 | Render pairs are owner decision OD2; everything prints as data meanwhile. |
| `my.message` | 69 | 4 | Messages between agents: later, owner decides. |
| `seon.cluster.instruction` | 65 | 6 | Cluster-owned instruction facts and verbatim renders; may map to the help.md system prompt. |
| `seon.vocabulary` | 64 | 8 | Retired-words prose lint; not mentioned in NEEDS or BAGGAGE. |
| `seon.order` | 30 | 1 | Canonical structural comparator used by storage and identity; no NEEDS row. |
| `my.shell` | 29 | 2 | Shell verb; native jolt.process exists. |
| `seon.shell` | 22 | 2 | Process request predicates; native jolt.process exists. |

## Uncontracted functions in `port` namespaces

496 functions have no contract and 454 have a weak one, in `port` namespaces. Private: 465 / 496 uncontracted. Top 30 uncontracted by callers (src/script callers; then lines):

| Function | Callers | Test callers | Lines | Private | Reaching tests |
| --- | --- | --- | --- | --- | --- |
| `seon.operator/fail!` | 21 | 0 | 2 | true | 49 |
| `seon.operator/command!` | 17 | 0 | 23 | true | 19 |
| `seon.operator/selected-processes` | 14 | 0 | 11 | false | 6 |
| `seon.operator/advertisement` | 14 | 3 | 4 | false | 8 |
| `seon.test.runner/execution-read` | 14 | 0 | 4 | true | 744 |
| `seon.operator/elapsed-ms` | 13 | 0 | 1 | true | 6 |
| `seon.db/current-database-value` | 12 | 0 | 6 | true | 2575 |
| `seon.schema/predicate-symbols-in` | 11 | 1 | 15 | true | 2752 |
| `seon.schema/loaded-predicate-var` | 9 | 0 | 20 | true | 2753 |
| `seon.schema/candidate-forms` | 9 | 0 | 13 | true | 2716 |
| `seon.operator/matching-handle` | 9 | 1 | 12 | false | 16 |
| `seon.render.web/text-response` | 9 | 0 | 4 | true | 82 |
| `seon.operator/terminate!` | 8 | 1 | 9 | false | 14 |
| `seon.operator/down!` | 7 | 0 | 22 | false | 4 |
| `seon.render.web/html-response` | 7 | 0 | 4 | true | 82 |
| `seon.db/ask-declarations` | 6 | 0 | 16 | true | 2576 |
| `seon.db/edn-encoded?` | 6 | 0 | 13 | true | 2576 |
| `seon.print/literal` | 6 | 0 | 9 | true | 2369 |
| `seon.db/append-database-evidence!` | 6 | 0 | 7 | true | 2575 |
| `seon.edit/utf8-bytes` | 6 | 0 | 3 | true | 13 |
| `seon.render.web/current-page` | 5 | 0 | 17 | true | 80 |
| `my.program/observation` | 5 | 0 | 8 | true | 703 |
| `seon.edit/line-range` | 5 | 0 | 6 | true | 13 |
| `seon.render.web/with-database` | 5 | 0 | 6 | true | 156 |
| `seon.turn/resolve-namespace-name` | 5 | 0 | 6 | true | 364 |
| `seon.render.web/forward-attribute` | 5 | 0 | 5 | true | 157 |
| `seon.render.web/not-found` | 5 | 0 | 3 | true | 80 |
| `my.program/present-groups` | 5 | 0 | 2 | true | 703 |
| `seon.operator/launch-child!` | 4 | 0 | 87 | true | 4 |
| `seon.db/decode-attribute-maps` | 4 | 0 | 26 | true | 2576 |

Port namespaces by uncontracted count:

| Namespace | None | Weak | Complete |
| --- | --- | --- | --- |
| `seon.render.web` | 95 | 25 | 38 |
| `seon.db` | 52 | 53 | 174 |
| `seon.turn` | 42 | 26 | 136 |
| `seon.operator` | 41 | 31 | 51 |
| `seon.schema` | 30 | 69 | 66 |
| `seon.edit` | 25 | 1 | 8 |
| `seon.print` | 25 | 8 | 17 |
| `seon.test.runner` | 24 | 25 | 71 |
| `seon.ai` | 23 | 3 | 46 |
| `seon.render.transcript` | 22 | 6 | 34 |
| `seon.dev.mcp` | 20 | 0 | 0 |
| `seon.program` | 18 | 27 | 32 |
| `seon.reconcile` | 17 | 1 | 7 |
| `seon.schema.edn` | 12 | 6 | 11 |
| `seon.config` | 11 | 3 | 27 |

## Run-time lookups in `port` namespaces

88 functions in `port` namespaces call a run-time name lookup (`requiring-resolve`, `resolve`, `ns-resolve`, `find-var`, `find-ns`, `intern`, `var-get`, plus the enumerators `the-ns`, `ns-publics`, `ns-interns`, `ns-map`). Per the 2026-10-19 inventory, 63 of the 178 production sites are load-cycle workarounds and 61 more are lazy by choice, so most of these are not needed by an idea; each is a place the idea is more complicated than it has to be.

| Namespace | Functions with a lookup | Lookup forms |
| --- | --- | --- |
| `seon.turn` | `cluster-agent-acquire-context!`, `context-end-summary-tx`, `context-model-context`, `context-step-members`, `context-step-verdict`, `test-branch-tests`, `test-latest-results`, `test-reaching` | {requiring-resolve 11} |
| `seon.cluster.source` | `contribution`, `done-refusals`, `index-tasks!`, `judged-merge!`, `members-of`, `merge-members`, `record-results-at-head!`, `red-test-verdicts` | {requiring-resolve 11} |
| `seon.program` | `base-context-injected-symbols`, `changed-identities`, `content-digest`, `declaration-changed-identities`, `declaration-digest-map`, `differing-declarations`, `fold-content-digest`, `overrides` | {ns-publics 1, requiring-resolve 9} |
| `seon.test` | `acquire-context!`, `arm-first-loads!`, `attempt-writers`, `release-context!`, `resolve-loaded`, `routing`, `run-request`, `seed-cluster-facts!` | {requiring-resolve 6, find-ns 3, ns-interns 1} |
| `seon.instrument` | `apply!`, `armable`, `collect-contracts!`, `definition-location`, `example-forms`, `jvm-arglists`, `replaced-roots` | {find-ns 6, find-var 6, ns-interns 2} |
| `seon.ref` | `alias-form?`, `at-level`, `library-definition`, `named?`, `namespace-name`, `resolve-name` | {find-ns 5, requiring-resolve 1, find-var 2} |
| `seon.test.runner` | `ambient-snapshot`, `live-cluster-schema-states`, `loaded-identity`, `resolve-loaded`, `run-selected-tests`, `task-identity` | {var-get 2, requiring-resolve 2, find-ns 1, find-var 1, ns-interns 1} |
| `seon.render.value` | `config-defaults`, `render-agent-render-profile`, `render-declared-projection`, `render-viewer`, `walk-with-related` | {requiring-resolve 5} |
| `seon.schema` | `core-predicate-registered?`, `database-projection`, `register-candidate!`, `runtime-predicate` | {var-get 1, requiring-resolve 2, find-ns 1, ns-resolve 1} |
| `seon.task` | `process-id`, `record-fault!`, `retire-branch!`, `submit-work!` | {requiring-resolve 4} |
| `seon.error` | `candidate-calling-form`, `library-calling-form`, `library-names`, `proof` | {resolve 2, find-ns 1, ns-publics 1, requiring-resolve 1} |
| `seon.repl` | `cluster-agent-armed`, `config-defaults`, `render-agent-render-profile` | {requiring-resolve 3} |
| `seon.operator` | `head-capture-form`, `launch-form` | {resolve 2} |
| `seon.context` | `judged-input` | {requiring-resolve 1} |
| `seon.render.web` | `cluster-ensure-entity!` | {requiring-resolve 1} |
| `seon.agent-cost` | `group-run` | {ns-resolve 1} |
| `seon.profile` | `pain` | {requiring-resolve 1} |
| `seon.ai` | `coercion-function` | {requiring-resolve 1} |
| `my.program` | `native!` | {requiring-resolve 1} |
| `seon.batch` | `compiled-function?` | {find-ns 1} |

Full list with symbol: filter `functions.edn` on `:census/lookups`.

## Tests

| State | Tests |
| --- | --- |
| green | 1 |
| never-run | 3428 |
| red | 42 |

Result sources: {:none 3428, :operator-check-20261019T230731.687-red-list 40, :stored-member-run-on-default 3}.

| Measure | Value |
| --- | --- |
| Declared long (`:seon.test/long`) | 349 |
| Platform (`:seon.test/platform`, a throwaway JVM) | 115 |
| Time bound source | {:default-5000 3146, :long-ms 325} |
| Tests with zero textual assertions | 61 |
| Assertions (textual count) | 15095 |
| Median / max reach (src/script fns, static) | 1377 / 3839 |
| Tests reaching 0 src/script functions statically | 84 |

### Red in stored evidence (42)

- `seon.cluster.registry-test/the-fork-point-is-not-walked-for-on-every-request` (operator-check-20261019T230731.687-red-list)
- `seon.cluster.source-test/flat-scratch-write-refusal-retires-the-candidate` (operator-check-20261019T230731.687-red-list)
- `seon.cluster.source-test/incremental-publication-does-not-change-an-existing-cluster` (operator-check-20261019T230731.687-red-list)
- `seon.cluster.source-test/publication-hands-its-commit-projection-unstamped` (operator-check-20261019T230731.687-red-list)
- `seon.cluster.wake-test/a-fault-wakes-the-steward-of-the-failing-functions-namespace` (operator-check-20261019T230731.687-red-list)
- `seon.cluster.wake-test/a-live-but-non-accepting-route-still-faults-loudly` (operator-check-20261019T230731.687-red-list)
- `seon.cluster.wake-test/a-routed-attribute-wakes-its-agent-and-nothing-else-does` (operator-check-20261019T230731.687-red-list)
- `seon.cluster.wake-test/an-unindexed-listened-attribute-is-refused-at-registration` (operator-check-20261019T230731.687-red-list)
- `seon.cluster.wake-test/route-render-wake-and-disjointness-property` (operator-check-20261019T230731.687-red-list)
- `seon.config-test/config-loads-the-packaged-population-in-a-fresh-jvm` (operator-check-20261019T230731.687-red-list)
- `seon.config-test/the-default-document-has-one-canonical-complete-location` (operator-check-20261019T230731.687-red-list)
- `seon.db-test/equal-declaration-content-shares-one-projection-across-values` (operator-check-20261019T230731.687-red-list)
- `seon.db-test/the-stable-walk-costs-less-than-encoding-the-read-result` (operator-check-20261019T230731.687-red-list)
- `seon.fn-test/a-contract-naming-a-local-def-is-kept-as-data-and-the-namespace-publishes` (operator-check-20261019T230731.687-red-list)
- `seon.fn-test/output-path-report-finds-the-shortest-bypass` (operator-check-20261019T230731.687-red-list)
- `seon.fn-test/sci-evaluation-has-one-first-party-owning-namespace` (operator-check-20261019T230731.687-red-list)
- `seon.program-test/equal-schema-forms-share-one-program-attribute-derivation` (operator-check-20261019T230731.687-red-list)
- `seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities` (operator-check-20261019T230731.687-red-list)
- `seon.reconcile-test/apply-then-reapply-converges-over-generated-populations` (operator-check-20261019T230731.687-red-list)
- `seon.render-cache-test/a-turn-re-renders-only-the-context-units-whose-reads-changed` (stored-member-run-on-default)
- `seon.render.history-test/form-is-the-third-output-of-the-existing-selection-chain` (stored-member-run-on-default)
- `seon.render.history-test/form-output-validation-is-the-declared-open-shape` (operator-check-20261019T230731.687-red-list)
- `seon.render.web-test/a-reconnect-refuses-a-pass-derived-before-it-connected` (operator-check-20261019T230731.687-red-list)
- `seon.render.web-test/a-user-reply-post-wakes-the-addressed-agent` (operator-check-20261019T230731.687-red-list)
- `seon.render.web-test/debug-algorithm-carries-the-render-evaluation-inputs` (operator-check-20261019T230731.687-red-list)
- `seon.render.web-test/declared-units-are-components-in-schema-order` (operator-check-20261019T230731.687-red-list)
- `seon.render.web-test/the-chains-page-lists-every-chain-and-repaints-on-main-commits` (operator-check-20261019T230731.687-red-list)
- `seon.render.web-test/unrelated-transaction-reuses-debug-observation-and-render-call` (operator-check-20261019T230731.687-red-list)
- `seon.schema-reference-graph-test/datalog-walks-the-schema-reference-closure` (operator-check-20261019T230731.687-red-list)
- `seon.schema-reference-graph-test/persisted-reference-edges-equal-the-canonical-projection` (operator-check-20261019T230731.687-red-list)
- `seon.schema-test/a-refused-projection-source-never-yields-a-projection-with-no-forms` (operator-check-20261019T230731.687-red-list)
- `seon.schema-test/an-unchanged-population-names-its-predicates-without-walking-its-forms` (operator-check-20261019T230731.687-red-list)
- `seon.schema-test/canonical-reference-values-use-the-pull-collection-grammar` (operator-check-20261019T230731.687-red-list)
- `seon.schema-test/every-predicate-schema-declares-what-it-accepts` (operator-check-20261019T230731.687-red-list)
- `seon.schema-test/pulled-forms-derive-from-the-entity-schema-and-selector` (operator-check-20261019T230731.687-red-list)
- `seon.schema-usage-guard-test/generic-schema-deletion-refuses-committed-dependencies` (operator-check-20261019T230731.687-red-list)
- `seon.test-support-test/a-canonical-database-is-the-production-source-population` (operator-check-20261019T230731.687-red-list)
- `seon.test-support-test/a-long-refusal-is-stored-whole-and-shown-bounded` (operator-check-20261019T230731.687-red-list)
- `seon.test-test/admission-refuses-stale-program-and-records-empty-selection` (operator-check-20261019T230731.687-red-list)
- `seon.test-test/one-interpreted-test-run-allocates-a-bounded-multiple-of-its-body` (operator-check-20261019T230731.687-red-list)
- `seon.test-test/recording-distinguishes-run-replay-from-a-new-event` (operator-check-20261019T230731.687-red-list)
- `seon.test-test/resolution-follows-admitted-source-and-acquisition` (operator-check-20261019T230731.687-red-list)

### Never run

3428 of 3471 tests have no stored result on default. That is not a pass; it is the absence of evidence, and it is the dominant finding of the test census: the stored program has 3,471 tests and 3 stored runs. The 42 reds above (41 from the 10-19 run's red list, plus one that is red only in default's own stored run) are the only red evidence, so they are a lower bound. Per-namespace counts of never-run tests are derivable from `tests.edn`.

Longest static reach (top 10 tests, src/script functions):

| Test | Reach | Asserts | Long |
| --- | --- | --- | --- |
| `seon.cluster.publication-host-test/live-init-keeps-the-host-process-and-launches-no-jvm` | 3839 | 9 | true |
| `seon.schema-redeclare-test/seeded-schema-families-survive-development-adoption` | 3831 | 2 | true |
| `seon.sci.eval-instrumentation-test/an-instrumented-dev-cluster-builds-an-attempt-ready-prompt` | 3829 | 4 | true |
| `seon.cluster.program-restart-test/an-agent-definition-survives-restart-and-another-agent-calls-it` | 3827 | 31 | true |
| `seon.concurrency-independence-test/n-agents-fold-independently-on-one-live-cluster` | 3812 | 1 | true |
| `seon.cluster.armed-test/an-eval-request-after-a-collection-reads-no-collected-value` | 3774 | 0 | true |
| `seon.cluster.boot-test/a-reset-development-cluster-keeps-its-overlay` | 3709 | 3 | true |
| `seon.cluster.boot-reply-test/a-refused-request-carries-its-large-evidence-as-bounded-shown-text` | 3705 | 5 | false |
| `seon.cluster.boot-test/status-answers-faulted-health-and-lists-the-open-fault` | 3705 | 6 | false |
| `seon.cluster.boot-test/status-answers-while-a-test-agent-stands-in-the-registry` | 3705 | 2 | false |

## Dead code

Dead means: no src/script caller, no test caller, no reaching test, and not an entrant (`:census/dead :direct`); or not reachable by static call edges from any entrant or test (`:transitive`). Entrants (1777): `-main`, named in a resource/`bin`/`deps.edn` file (`referenced-by-resource`, 1583, generous: a symbol named in a schema or a render attachment counts), public `my.*` agent verbs, public script functions, macros. Reachability by a name resolved at run time is invisible to call edges, so a function reached only by `requiring-resolve` appears `:direct` here only if it has no static caller anywhere; check before deleting.

| Function | Kind | Lines | Private | Verdict | Class |
| --- | --- | --- | --- | --- | --- |
| `seon.agent/effective-settings` | fn | 29 | false | unclear | direct |
| `seon.ai/labelled-price` | fn | 4 | true | port | direct |
| `seon.bootstrap/render-help-section-ai` | fn | 5 | false | port | direct |
| `seon.cache/counters` | fn | 6 | true | replaced | direct |
| `seon.cluster.agent/->CountedSlidingBuffer` | type | 22 | false | replaced | direct |
| `seon.cluster.process/process?` | fn | 3 | false | replaced | direct |
| `seon.db/logical-missing?` | fn | 9 | false | port | direct |
| `seon.env/->Environment` | type | 1 | false | replaced | direct |
| `seon.flow/*proc-thread-name*` | constant | 4 | true | replaced | direct |
| `seon.flow/fault-port` | fn | 18 | true | replaced | direct |
| `seon.flow/proc-thread-name` | fn | 8 | true | replaced | direct |
| `seon.flow/request-graph-stop!` | fn | 20 | true | replaced | direct |
| `seon.heap/notified!` | fn | 16 | false | replaced | direct |
| `seon.maintenance/observe-footprint!` | fn | 14 | false | replaced | direct |
| `seon.mine/kept-branches` | fn | 11 | false | port | transitive |
| `seon.mine/kept-situations` | fn | 13 | false | port | direct |
| `seon.profile/bucket` | fn | 5 | false | port | direct |
| `seon.profile/counted` | type | 1 | false | port | direct |
| `seon.profile/exit` | type | 2 | false | port | direct |
| `seon.profile/host-entry` | def | 3 | false | port | transitive |
| `seon.render.hiccup/map->Raw` | type | 1 | false | unclear | direct |
| `seon.render.walk/->DatabaseSchemaIdentity` | type | 7 | true | replaced | direct |
| `seon.sci.eval/context-state?` | fn | 5 | false | replaced | direct |
| `seon.sci.eval/function-symbols` | fn | 8 | true | replaced | direct |
| `seon.sci.eval/reified-return` | fn | 9 | true | replaced | direct |
| `seon.sci.kernel/pending?` | fn | 9 | false | replaced | direct |
| `seon.test.runner/reach-closure` | fn | 20 | false | port | direct |
| `seon.turn/render-html` | fn | 8 | false | port | direct |
| `seon.web.extract/html` | fn | 9 | true | replaced | direct |
| `seon.web.search/organic-results` | fn | 10 | true | replaced | direct |
| `seon.web.search/organic-row` | fn | 17 | true | replaced | transitive |

Counts by verdict: {:port 11, :replaced 18, :unclear 2}. Rows of kind `type` are the generated `->Rec`/`map->Rec` constructors of a record: they are dead only if the record is never constructed, which this call-edge view cannot tell from a constructor used through interop.

Functions with no static test reach (any caller): 381; of those with no src/script caller either and not entrants: 28.

