---
type: research
status: active
tags: [research, architecture, database]
---

# N7 — verification and required design decision

## Outcome

Design stop before production edits, as explicitly required by the assignment
and AGENTS.md §2.5. N7 remains open. This is not a class closure or a green gate.
Baseline inspected: `22893b713`, branch `steward-platform`, 2026-09-15.

Class statement: classifications inferred from copied membership or spelling
miss newly valid members. The requested structural kill is “Record the missing
fact, then query it; constructors accept no roster/prefix/count.”

Read end to end: AGENTS.md, docs/seon/issues/README.md, the N7 class note,
all nine assignment member notes, and
docs/prds/sci-execution-runtime/research/issue-class-mining-2026-08-11.md.
Loaded data-oriented-clojure and repl skills. Read the graph inventory change
`0ca3c1d64`, including its schema syntax inspector and canonical graph census.

## Why the design gate applies

There is no single classification constructor shared by these owners. A literal
collection can be an authoritative declaration (schema enum, JVM option key,
closed protocol state) or a prohibited copy. Program call and keyword edges do
not by themselves encode that distinction. `0ca3c1d64` checks a defined schema
grammar; copying its census does not supply a classification grammar.

The remaining implementation crosses AI request construction, schema admission,
SCI acquisition, cluster reconciliation, process claim custody, and program
analysis. Several actual owners differ from the assignment's path list:
`resources/seon/operator/state.clj`, `src/seon/program.cljc`,
`src/seon/ai.clj`, and `src/seon/schema/edn.clj`. Cluster reconciliation is
currently under another lane's edits. This requires hours of cross-owner work;
the stop is the explicit design gate, not a foreign test failure.

## Three priced options

Estimates are engineering effort, not measured timings; serial gate waits add
wall time.

1. **Recommended — constrain classification declarations at publication.**
   Use existing schema/program declaration and admission owners to represent
   classification inputs and their authority; validate a deliberately bounded
   form grammar and derive checker subjects from those program facts. Replace
   remaining classifications with queries at each existing owner, deleting the
   obsolete mechanisms. **Guarantee:** admitted classification declarations
   cannot supply a literal membership roster or a spelling-derived selector.
   **Cost:** approximately 2–3 engineer-days across the owners above, including
   canonical regression and independent process-root proof. **Give up:** arbitrary
   Clojure as an admitted classifier; the guarantee covers this declared surface,
   not an unmarked computation anywhere in the program. Requires agreement on
   that scope before claiming the requested class checker exists.
2. **General source provenance analysis.** Extend canonical analysis to follow
   literal collection/string provenance through function calls into classified
   results, with explicit declaration provenance for legitimate constants.
   **Guarantee:** reject literal-derived classification within the analyzed
   subset, and report unsupported analysis as unknown. **Cost:** approximately
   5–10 engineer-days plus ongoing analyzer maintenance. **Give up:** a small
   implementation and unrestricted analyzable Clojure; a universal semantic
   proof over arbitrary functions is not promised.
3. **Owner-specific query invariants.** Apply the missing-fact repairs and one
   behavioral property per distinct owning mechanism; retain N7 as an umbrella
   until all member proofs close. **Guarantee:** adding a member at each repaired
   owner changes its query without a classifier edit. **Cost:** approximately
   1–2 engineer-days plus protected-owner coordination. **Give up:** the requested
   single checker that prevents future literal-roster classifiers repository-wide.

## Per-member verification at the boundary

| Member note under docs/seon/issues/ | Verdict and HEAD evidence | Missing fact / remaining proof |
|---|---|---|
| sci-base-context-silently-hand-lists-special-callables.md | Confirmed roster remains: `src/seon/program.cljc:14–40`; live base injection map contains six namespace entries. `750ed404d` consolidated consumers but did not derive the exceptions. | Interpreter-only binding semantics/provenance at declaration; ordinary bindings should use acquisition. |
| render-walk-maintains-a-derived-edge-hand-list.md | Historical roster deleted by `bc3dfe3fd`; no `derived-edge-functions` remains in the walk. Current `src/seon/render/walk.clj:628–664` consults schema properties for declared concerns. | Existing schema relationship/derived-render facts are the candidate authority. Live relationship coverage is not yet verified; do not infer it from deletion. |
| operator-classifies-processes-by-command-substrings.md | Old classifier removed by `5342b2b4d`. `script/seon/fresh_operator.clj:537–548` consumes `observed-property-processes`; `resources/seon/operator/state.clj:957–988` reads explicit JVM root/generation properties plus OS start instant. | Root, generation, PID and start instant already exist. Parsing the explicit `-Dproperty=` argument is a protocol parse, not a role inference from arbitrary command text. Negative process-classification regression not run. |
| operator-down-misses-a-live-scratch-jvm-from-another-checkout.md | Residual authority problem remains plausible: `script/seon/fresh_operator.clj:140–174` uses the invoking repository to find process claims, then filters by selected root; `:2903–2948` consumes that census for down. | Selected-root relationship to the owning claim installation. Cross-checkout live reproduction still required; no process was stopped. |
| config-ai-request-idents-are-derived-by-string-surgery.md | Confirmed: `src/seon/ai.clj:353–367` rebuilds request keys and selects by namespace. Live helper maps `:unrelated/temperature` to `:seon.ai/temperature`. `:597–602` retains inert-settings set with an older explicit provider ruling. | Per-dial request attribute, plus provider-specific inert semantics. Declare at schema/provider owner and query at AI owner; do not silently override the older provider ruling. |
| config-dial-discovery-has-three-authorities.md | Confirmed: `src/seon/schema/edn.clj:31–37` accepts a namespace prefix. Live nonexistent `:seon.config.fake/not-a-dial` with `:string` definition returns true. Consumer roster partly dissolved, but `test/seon/config_application_test.clj:28` still starts an application-modes map. | Explicit dial membership and application acquisition semantics. Actual schema owner is in p1's assigned scope. |
| cluster-toolkit-stores-a-prefix-derived-projection.md | Confirmed: `src/seon/cluster/instruction.clj:32–57` filters `my.`; `src/seon/cluster.clj:2165–2201` stores/reconciles the projection. Live query returned 13 namespaces. | Context relevance from current agent/program relationships; delete stored projection. `cluster.clj` has concurrent uncommitted edits and was not modified. |
| initial-paint-census-is-a-hand-maintained-count.md | Historical assertion deleted by `9eca070ed`; `test/seon/render/web_test.clj:1235–1239` records removal of the old initial-paint machinery. | Expected emitted block identities belong to the current delivery/walk proof. Current replacement coverage not verified; deletion alone is not an exactly-once paint proof. |
| agent-form-calls-to-core-namespaces-are-not-indexed.md | Partial dissolution: `1f3c099d2` introduced current resolvable function-row query, `src/seon/fn.clj:475–497`. Live valid declaration analysis returns BOTH core and my.* call edges. `analyzed-form` at `:558–588` returns empty first-map facts and merges edges into declaration rows. | Ordinary evaluation edge persistence remains unproven. The nil-row call still refuses before analysis. Do not close the historical ordinary-form defect based on declaration analysis. |

All nine notes remain open at this design stop; prospective deletion-based
closures need their stated remaining proof. No member was re-fixed.

## Exact live probes and measured observations

Read-only MCP JVM mode, root `/Users/sean/src/seon`, cluster `default`.
`bin/seon status` observed PID 69622, PREPL 55914, HTTP 7994, one live cluster,
no orphan JVM. MCP runtime status answered; it reported 10 errored historical
evaluations, not a clean-runtime verdict. Default was never restarted/reforked.

Successful classification probe: 1 ms reported by PREPL:

```clojure
(let [db @(seon.operator/connection "default")]
  {:n7/core-row
   (seon.db/q '[:find ?e . :where [?e :seon.fn/sym "seon.db/q"]] db)
   :n7/fake-dial
   (#'seon.schema.edn/config-dial? :seon.config.fake/not-a-dial :string)
   :n7/route
   (#'seon.ai/config-ai-ident->request-ident :unrelated/temperature)})
;; => {:n7/core-row 4912, :n7/fake-dial true,
;;     :n7/route :seon.ai/temperature}
```

Successful declaration-analysis probe: 1,156 ms, two call edges. It also emitted
the existing `seon.db/projection-fallback` warning (1,136 ms); this is within the
concurrent p1 ambient-state class and is not attributed to N7.

```clojure
(let [db @(seon.operator/connection "default")
      source "(defn n7-probe [] (seon.db/q '[:find ?e :where [?e :seon.agent/id]]) (my.turn/wait))"
      row {:seon.fn/sym "seon.db/n7-probe"
           :seon.fn/ns [:seon.ns/name 'seon.db]
           :seon.schema.admission/source :agent
           :seon.fn/source source}]
  (seon.fn/analyze-form db source [:seon.ns/name 'seon.db] row))
;; => [{} { ... :seon.fn/calls
;;          #{[:seon.fn/sym "seon.db/q"]
;;            [:seon.fn/sym "my.turn/wait"]} ... }]
```

No definition was installed or evaluated by that analysis probe. Earlier probes
used the wrong literal types: function identity is a string, namespace identity
is a symbol. Those refusals are probe-input errors, not N7 findings. The separate
nil program-row probe reproduced the nested `analyze-forms` contract refusal at
`[0 :seon.program/row]` already recorded in the member note.

## Gates, authority boundary, and cleanup

No production changes; no test runner or scratch JVM launched. Scoped test and
platform gates are **not run**, pending the design decision. No background shell,
worktree, or scratch root was created.

`bin/issues-index --class class/n7` returned exit 1 because the shared schedule
has eight missing rows: mcp-ordinary-values-bypass-the-value-renderer,
missing-artifact-marker-refuses-its-own-admission-contract,
nested-test-snapshot-overwrites-its-fresh-run-claim,
test-results-persistence-can-time-out-during-development-adoption,
test-result-recording-refuses-after-branch-head-change,
adoption-probe-emits-an-invalid-root-namespace-lookup,
test-program-rows-omit-admission-provenance, and
test-launcher-fixtures-omit-required-helpers. These existing notes preserve the
findings; the owner owns schedule edits. The nine explicitly assigned N7 notes
were read directly; no complete validated class census is claimed.

Inherited edits were preserved in schema admission, cluster, value renderer,
and MCP/effect/REPL/search tests. This lane changes only this landing note and
the N7 class note's link to the decision.
