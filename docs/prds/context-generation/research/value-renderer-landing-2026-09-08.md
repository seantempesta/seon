---
type: research
status: active
date: 2026-09-08
tags: [render, performance]
---

# Value renderer landing

The value renderer applies the AI profile before walking collection children
or printing string contents. HTML traverses the complete live value. Both
projections use the same print-node grammar. Removed the separate map-layout
formatter; an entity without a declared renderer prints its sorted attribute
map, with installed refs represented as identities. Declared renderers still
receive the original value.

Every presentation cut carries count, total (explicitly unknown for uncounted
sequences), path, offset, profile key, and an executable requery form when the
caller supplies a result handle or entity identity. Unknown identities are an
explicit refusal, never a fabricated handle. Stored shown text remains the
history owner's responsibility; this lane leaves the artifact API used by
concurrent evaluation code in place.

Maps and sets are ordered at the print node, including nested keys; vectors
and lists preserve order. Strings use readable Clojure quoting independently
of caller print bindings. Long strings are sliced before quoting. Lists name
the nearest associative parent because `get-in` cannot index a list.

## Measurements

Input construction is outside timers. Three AI warmups precede each measured
AI+HTML pair. Single observations under concurrent development load; these
are latency observations rather than statistical benchmark distributions.
The committed `large-values-have-bounded-ai-work-and-complete-html` regression repeats
the measurement under armed contracts and verifies complete HTML contents.

| Input | Baseline AI ms | Baseline HTML ms | Live AI ms | Live HTML ms |
|---|---:|---:|---:|---:|
| 100,000-element vector | 1993.589250 | 5119.195958 | 0.195500 | 1319.597917 |
| 5 MiB ASCII string | 841.141750 | 1437.115166 | 0.170000 | 80.382583 |

The baseline built a complete admission tree before fitting, then emitted
children that the profile would discard. A regression observes an unchunked
sequence and verifies only three retained children plus one lookahead are
realized. Full HTML remains proportional to its complete output. Canonical
map/set ordering necessarily examines keys/members to select a stable prefix;
it does not traverse omitted map values.

Exact vector AI bytes, one line, no trailing newline:

```text
vector 100000 items, depth 0 [0 1 2 … 99997 more children of 100000; bounded by :seon.render.profile/max-children; requery (get-in result/e0123456789ab []) at path [] offset 3 with :seon.render.profile/test]
```

## Reproducible probe

Run as one form in MCP JVM mode with explicit root and cluster:

```clojure
(let [profile {:seon.render.profile/id :seon.render.profile/test
               :seon.render.profile/token-budget 1024
               :seon.render.profile/max-depth 4
               :seon.render.profile/max-children 3
               :seon.render.profile/composition :single-line}
      unit {:seon.render.call/id [:seon.render.value-test/probe]
            :seon.render.value/root 'result/e0123456789ab
            :seon.render/profile profile
            :seon.render.value/options
            {:seon.render.value/structural? true}}]
  (mapv
   (fn [[label raw]]
     (let [request (assoc unit :seon.render/value raw)
           _ (dotimes [_ 3] (seon.render.value/render-ai request))
           start (System/nanoTime)
           ai (seon.render.value/render-ai request)
           middle (System/nanoTime)
           html (seon.render.value/render-html request)
           end (System/nanoTime)]
       {:probe/value label
        :probe/ai-ms (/ (- middle start) 1e6)
        :probe/html-ms (/ (- end middle) 1e6)
        :probe/ai ai
        :probe/html? (vector? html)}))
   [[:vector (vec (range 100000))]
    [:string (.repeat "x" (* 5 1024 1024))]]))
```

## Dependency ledger and authority

Read AGENTS.md, the named turn-loop PRD (including §10, §13 and §15),
`src/seon/render/value.clj`, `src/seon/print.cljc`, `src/seon/repl.clj`, and
both owned schema files end to end. Applied the data-oriented-clojure, repl,
clojure-testing, data-modeling and datahike skills.

- Clojure `get-in`: `reference-code/clojure/src/clj/clojure/core.clj:6288`.
  Result forms retrieve live objects; entity forms use `seon.db/pull`.
- Clojure string escaping: `reference-code/clojure/src/clj/clojure/core_print.clj:212`.
  The emitter binds readable, unlimited stock literal printing explicitly.
- SCI context binding/evaluation: `reference-code/sci/src/sci/core.cljc`;
  tests use the canonical `seon.test-support/fork-cluster-ctx` and
  `agent-value` boundary so database custody is supplied as in production.
- Datahike installed schema (`:db/valueType`, `:db/cardinality`,
  `:db/unique`) supplies ref and identity semantics; `seon.db/pull` is the
  first-party read owner. No entity kind or separate renderer registry added.
- Existing readable-map issue:
  [the-value-floors-map-face-is-not-readable-edn.md](../../../seon/issues/the-value-floors-map-face-is-not-readable-edn.md).
  The duplicate formatter it described is removed by this change.

## Verification

`bin/test-fast seon.render.value-test seon.print-test seon.repl-test`:
55 tests, 217 assertions, zero failures/errors, contracts armed in panic mode.
The complete armed run measured vector AI **0.212750 ms**, HTML
**1102.353333 ms**; string AI **0.556708 ms**, HTML **212.817333 ms**.
The sub-100 ms AI regression and full HTML-content assertions passed.

Final path-isolated gate:

```text
bin/test --paths src/seon/render/value.clj src/seon/print.cljc resources/seon/schemas/seon.print.edn resources/seon/schemas/seon.render.value.edn test/seon/render/value_test.clj test/seon/print_test.clj -- seon.render.value-test seon.print-test seon.repl-test
Ran 55 tests containing 217 assertions.
0 failures, 0 errors.
```

The final snapshot overlays only the six owned implementation/test files.
The successful run removed its isolated operator root. The temporary
`tmp/value-wt` worktree was also removed. Platform and publication results
are recorded below.
Live measurements above exercised the loaded renderer Vars through MCP JVM
mode on root `/Users/sean/src/seon`, cluster `default`; they are not an
agent-turn or browser-paint proof. Earlier publication was refused for source
drift and an unrelated reader error at `test/seon/fn/analyzer_test.clj:57`;
no foreign file or process was operated to repair that boundary.

Development adoption completed successfully at `:current-src` commit
`6aa06f77-a3a5-5bd4-bc86-95ae6d9a4045`, source digest
`0909bd1f636cd2fbbc08d7375e985a928e57c5c85269f4f0981063e5bc59da90`.
The live measurements in the table were repeated **after** the operator
reported `development cluster converged`, through MCP JVM evaluation.
This proves loaded renderer behavior following in-place development adoption;
no browser paint or agent-turn execution is claimed.

`bin/test --paths <the same six paths> --platform` exited **0**:
74 tests, 404 assertions, zero failures/errors. It reported one worker-state
warning in the concurrently owned
`seon.cluster.cohost-boot-test/a-second-cluster-boots-under-the-first-cluster-s-instrumentation`:
`:seon.test.runner/snapshot-instrumented` removed 949 wrappers. This is a
reported platform hygiene finding, not a renderer test failure; this lane
neither edited that test nor operated its owner's session. The runner removed
its successful isolated root. All owned background commands have exited.

The platform drift is the already tracked class
[a-platform-test-leaves-its-worker-stripped-of-every-contract.md](../../../seon/issues/a-platform-test-leaves-its-worker-stripped-of-every-contract.md).
