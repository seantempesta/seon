---
type: landing
lane: rc-m5-presentation-opus
status: partial (M5 total floor + selection gate + MCP :err committed; fault recording, eval-side conversion, doc/dir/nested budget left; RESET NEEDED)
commits: 958bf0ed6 649a472b2
date: 2026-09-26
base: c236d2d97
---

# rc-m5-presentation-opus — M5: presentation never reinterprets or replaces the value (2026-09-26)

**Design.** The floor printer (`seon.render.value/prepare`) is the one print edge. It already had a permitted-operation table (classify, `:lookup?`), but renderer selection bypassed it with `map?` and ran keyword lookups on any map. A renderer failure was caught in `declared` (value.clj:650 at c236d2d97) and its text replaced the node. Now the table owns the question. `keyword-lookup?` is the single gate for attachment, identity and entity shape. A failure becomes a diagnostic beside the value. `prepare` is one total boundary. An entity renderer claims a map only if the map satisfies its row schema.

## Broken or not done (first)

- **RESET NEEDED / adoption.** Default runs a committed archive. 958bf0ed6 and 649a472b2 are not adopted; the live symptom stays until root adopts them.
- **Recording as a core fault is not wired.** `prepare` returns `:seon.render.value/diagnostics` (the Throwables or error values), but the caller at `seon.sci.eval/shown-result` (sci/eval.clj:3909, M1/M2b's file) does not record them yet. Root needs to add `seon.fault/fault!` per diagnostic there. Under `:panic`, `fault!` throws, which conflicts with the new "the REPL path is total" law. Root decides the policy.
- **Schema key undeclared.** `:seon.render.value/diagnostics` sits on an open map. The patch is `tmp/rc-m5-presentation-opus/diagnostics-schema.patch` (seon.render.value.edn, not my file).
- **Evaluator-side display→failure conversion remains (not my file).**
  - sci/eval.clj:4597 calls `shown-result` inside the success `try`, and 4363 renders the failure from its catch.
  - Printing can no longer throw from `prepare`. `shown-result` itself can still throw at 3877 (a refused profile), in `error/prepare-result`, and in `db/pull`.
  - Moving `shown-result` out of the success `try` would delete the conversion. That belongs to the sci/eval.clj holder.
- **Same class outside my files.** The ClassCastException comes from a keyword lookup on a symbol-keyed sorted map. It also occurs in:
  - `seon.turn/disposition` (audit Q3);
  - the open fault `seon.schema/valid-candidate-value?` on default. Malli's `:map` validator calls `.valAt` (malli/core.cljc:1275), so validating any `[:map …]` against a sorted symbol map throws.
  - Those owners should gate with `seon.render.value/keyword-lookup?`, or validate only what the table permits.
- **Not attempted in this slice:**
  - P5 doc postwalk (sci/eval.clj:2361, M1's file).
  - P6 `dir` display keys (repl.clj:32).
  - P7 nested declared-text clipping at value.clj `make-node` (the `ceiling` elision). This is a second local budget (task 0e6dc884b838) and should be removed next so the floor's one budget covers renderer text.
- **Tests not run by me.** The load rule applies. The only focused test-check I ran reported "reused", so it is not evidence for this change.

## Evidence (live default, MCP)

- **Before** (branch `rc-m5`, SCI): `[(inc 41) (sorted-map 'b 2 'a 1)]` printed `[42 "java.lang.ClassCastException: … at seon.render/explicit-value (render.clj:587) … seon.render.value/declared (value.clj:667)"]`. A bare `(sorted-map 'b 2 'a 1)` printed the same exception.
- **After.** Scratch copies of both namespaces were loaded as `rcm5.scratch.*` in the JVM with default's ctx, database and projection. They are unarmed; I validated outputs against the named schemas by hand.

  | Input | Output |
  |---|---|
  | `[42 (sorted-map 'b 2 'a 1)]` | `[42 {a 1, b 2}]`, no diagnostics |
  | `(sorted-map-by compare 'z 1)` | `{z 1}` |
  | `(sorted-map :seon.render/ai "custom face" :x 1)` | `custom face` (a keyword-keyed sorted map keeps its attachment) |
  | `[{:seon.render/ai boom :a 1} {:b {:seon.render/ai boom :c 2}}]` | the whole vector as data, then one line: `;; renderer failed at [0], value printed as data: … boom (and 1 further renderer failures)` |
  | directory renderer on a non-directory | the map, plus one line naming `seon.repl/render-directory-ai` and its contract refusal |
  | `{:seon.turn/id 12}` | `{:seon.turn/id 12}` (before: the turn renderer was selected and its error reached emit) |
  | `(select-keys issue [:seon.issue/id :seon.issue/status])` | the map as data (before: "Issue … still open.") |
  | the full pulled issue | still its renderer's "Issue …" text |

- **Row validity over real data.** Each of the 43 identity rows' first stored entity (`pull '[*]`) satisfies its row after `transacted` and Malli's `collection-transformer`. Without the transformer, 2 rows failed: `:seon.config.ai/stop` and `:seon.cluster.eval/read-evidence` are declared as `[:vector …]` but are card-many. That run took 24 ms for 43 cold, uncached compiles; the committed code caches the decoder and validator per row and projection.
- **Timings.** 6 renders in 20.6 ms; 8 in 47 ms (includes the first compile).
- **Total fallback.** `plain-projection` output validates as `:seon.render.value/projection` and emits the same text.
- **Contract check** (`seon.contracts-compile-test/check` over the three changed src/script files at the checkout root) found no problems (614 ms).

## Changes

- **958bf0ed6** (render.clj +64/−67, value.clj +117/−44, value_test +40/−6, mcp_test +16/−10)
  - Adds `keyword-lookup?` and `value-identity`, `diagnostic-line`, `plain-projection`, `row-satisfied?` and `invalid-output`.
  - Deletes:
    - the catch that replaced a node with failure text;
    - the `{:value refusal}` substitution of a renderer's refusal for the value;
    - `keyword-entry`'s separate class set and 64-entry scan;
    - `target-profile`'s copy of the identity lookup (−6);
    - `raw-output`'s three copied invalid-output maps (≈−30);
    - the `map?` gates on `explicit-value`, `declared-producers`, `render-invocation-argument` and `declared-projection`.
  - Updated tests: `value_test/a-value-s-own-inline-function-renders-it`, whose old assertion wanted the replacement, and `mcp_test`'s directory case.
- **649a472b2** (mcp.clj +6/−1, mcp_bridge_test +15): when a recorded error's text differs from the shown text, it becomes its own `:err` event before the flagged `:ret`, so a refused settlement's refusal is visible. An ordinary error whose shown text is the error prints once.
- **Net source:** +75 (render −3, value +73, mcp +5). Most of value.clj's growth is the total boundary and docstrings.

## Root runs

```
bin/test-check default --test seon.render.value-test/a-map-that-cannot-hold-keywords-prints-as-clojure-prints-it
bin/test-check default --test seon.render.value-test/a-failing-renderer-never-replaces-the-value
bin/test-check default --test seon.dev.mcp-bridge-test/a-refused-settlement-shows-its-refusal-beside-the-value
bin/test-check default --ns seon.render.value-test
bin/test-check default --ns seon.mcp-test
bin/test-check default --ns seon.render-simplification-test
bin/test-check default --ns seon.render-coverage-test
bin/test-check default --ns seon.render.entity-pairs-test
bin/test-check default --ns seon.render.web-test
bin/test-check default --ns seon.render.transcript-test
```

`render-simplification-test`, `render-coverage-test` and `entity-pairs-test` matter most. Row validity can change which walk or transcript renders select a renderer for a partially pulled entity. That change is intended (task 135d92ea1308), but those namespaces may hold old expectations.

Then the MCP acceptance, after adoption, on a fresh branch:

- `[(inc 41) (sorted-map 'b 2 'a 1)]` prints `[42 {a 1, b 2}]`;
- a refused settlement shows its `:err` refusal.
