---
type: review
status: complete
date: 2026-09-26
reviewer: gpt-6-astra
source-pin: 5bed888d02c300ca3dc798109687f2b88471a436
verdict: REVISE
blocking-findings: 4
---

# Floor selection and continuation: independent diff review

The new selector removes the fitting search and threads selection state as a value, while fipp still owns layout. However, the activated callers do not yet share one bounded selection or one continuation representation. Four concrete defects prevent accepting slice 2(i).

## Scope and evidence

Read `CLAUDE.md`, `one-render-path-design-2026-09-25.md`, and `docs/prds/agent-platform/landing/lane-impl-floor-on-fipp-2026-09-26.md`. Inspected the requested path history since September 25, the selection/continuation changes `7eb1219f3`, `5112657ad`, `bc1021402`, `260fed262`, `d97994b43`, `97f4d1f90`, the subsequent classification/artifact corrections, and the named `0c748bcd5` and `272a02eab`. The original fipp landing explicitly distinguishes layout from the later selection work; its earlier measurements are not measurements of the final selector.

Source lines below are pinned to the committed source above, not concurrent working-tree edits. Static review only: no JVM, tests, rendering, browser or new timings. Findings describe source-derived failure scenarios, not claimed executed reproductions.

## Findings

### F1 — blocking: HTML disables the selector's bounds

**Location:** `src/seon/render/value.clj:965`, especially `:977`–`:983`.

**Failure scenario:** render the same large finite persistent vector for AI and HTML. AI selection charges a budget; HTML sets `:charge? false`, budget/depth/children/string limits to `Long/MAX_VALUE`, and visits every member. The realization cap at `:987` covers lazy acquisition, not an already finite persistent vector. A browser request can allocate a complete enormous tree, and its selection/cuts differ from the agent's. Producing both faces with `emit-both` afterward does not repair selecting twice under different rules.

**Smallest fix:** select one tree with one floor budget and reuse it for both faces; HTML expansion must continue that selection through the shared continuation entrance. Remove output-dependent admission. Keep query-work bounds separate from presentation. This is the design's atomic 2(i) obligation, not the later optional page layout.

### F2 — blocking: map-key coordinates identify a different value

**Location:** `src/seon/render/value.clj:810`, `src/seon/render/data.clj:54`, `src/seon/render/walk.clj:836`.

**Failure scenario:** in an array map whose first key is a large composite value, the key node receives path `[0]`. `data/at` interprets that as the map key `0`, not the key of entry zero. With no integer key it refuses a valid cut; with a `0` key it expands that entry's value instead. A key's cut and its value's path use incompatible coordinate conventions. Nested composite-key cuts inherit the error. The new `continue` function calls this unchanged raw navigator.

**Smallest fix:** implement the design's declared typed entry/key/value/index/character steps in the single navigator and emit those steps from selection. Have both continuation adapters consume them. Preserve held iteration order; do not recover keys by sorting or stringifying them.

### F3 — blocking: the initial value and its advertised continuation have different backing

**Location:** `src/seon/cluster.clj:374`, `:409`, `:495`; `src/seon/render/value.clj:1033` and `:1089` (`97f4d1f90`).

**Failure scenario:** an admitted artifact retains a bounded prefix or replacement for a large result. The initial projection now selects the original held result at `:410`, but the digest still names admission's print-node artifact. `get_value` reconstructs semantic data from that artifact at `:505`, then independently selects it. Continuing beyond the artifact's admitted prefix cannot reach the original member, even when the original display supplied its path/offset. Reconstructed maps/sets also need not preserve the original ordinal order. The new `walk/continue` reads live result objects, but `get_value` does not use it.

**Smallest fix:** make new cuts name the actual retained source/selection that the one continuation owner resolves, and make MCP and web adapters call that owner. Where only an old admission artifact exists, explicitly label its restricted backing and return the declared unavailable continuation instead of advertising it as continuation of the held raw value. Retire the duplicate navigation/window body in the same activation.

### F4 — blocking: an unaffordable root is emitted, and a page can make no progress

**Location:** `src/seon/render/value.clj:834`, `:850`, `:1001`; `resources/seon/schemas/seon.render.profile.edn:2`.

**Failure scenario:** a legal one-token profile renders a root string of sixteen ordinary characters. `make-node` initially admits the string chunk; `select-nodes` unconditionally installs the root. No root-budget check occurs. The final assertion checks emitted length against the computed charge, not charge against budget, so it can successfully emit more than the allocation. With a collection whose first skeleton plus cut/legend cannot fit, every member admission fails and the returned continuation repeats the same offset indefinitely. There is no successful first-member progress guarantee.

**Smallest fix:** check the mandatory root/cut/legend charge before selection, and return the design's named insufficient-floor-budget error when it cannot fit or a continuation cannot advance. Include the minimum required charge and supplied budget in native error data. Do not add a second clipping pass or a retrying fit loop.

## Dependency use, simplicity and laws

Using fipp's `annotate-rights`, `annotate-begins` and `format-nodes` is appropriate (`reference-code/fipp/src/fipp/engine.cljc:68`, `:92`, `:164`, pin `255a19061`). Fipp owns group fit, indentation and line emission (`:196`–`:224`); it does not enforce Seon's value budget or navigation semantics. The incremental charge mirrors those decisions, so equality against the actual fipp emission needs proof on changed inputs, including lifted maps and nested entries. A final length-versus-charge assertion proves neither budget admission nor progress.

The queue is an immutable value threaded through a loop, an improvement over scattered selection holders. `5112657ad` removes the recursive print-node redispatch and asks the render owner about reached native values. `0c748bcd5` uses SCI's record/type representation rather than inventing a parallel record model. `272a02eab` usefully removes presentation cuts outside the floor and preserves native drift members.

Storage must not silently become a second result model: konserve's ordered content/pointer publication guarantee (`reference-code/konserve/src/konserve/core.cljc:435`, pin `5b39fddd6`) establishes durable reachability, not equivalence between an admission artifact and a different live value. Malli `validator` (`reference-code/malli/src/malli/core.cljc:2637`, pin `befcd302a`) validates declared shapes; accepting a path vector is not proof it names the selected member. The typed path and insufficient-budget contracts belong at their producers.

## Size, costs and remaining proof

For the eight explicitly counted selection/follow-up commits (`7eb1219f3`, `5112657ad`, `bc1021402`, `260fed262`, `d97994b43`, `97f4d1f90`, `0c748bcd5`, `272a02eab`): source **+847/−1,033 (net −186)**; schemas **+28/−4**; tests **+225/−447 (net −222)**. This is commit churn; it excludes the earlier fipp layout cut and other small corrections. Actual deletions include `print/fit`, ordering helpers, print-node redispatch and window/pager helpers. Net deletion is welcome but does not excuse the two active continuation models or HTML's unbounded selection.

The earlier landing reports 0.29 ms layout and a depth-5,000 case at 571 ms for its earlier unarmed implementation. Those are historical, not installed 2(i) evidence. Current elapsed time, allocation, visited-node cost, cold acquisition and browser paint remain unverified. Root should prove F1–F4 through both adapters, including composite map keys, tiny budgets, exact-end/beyond-end offsets and backing eviction. No test or installation pass is inferred here.

**REVISE**
