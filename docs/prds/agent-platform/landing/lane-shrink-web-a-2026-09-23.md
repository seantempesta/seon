---
type: landing
status: S6 landed (9440698f3); S2 stopped at its byte-equality and hot-path gates (not landed)
created: 2026-09-23
tags: [agent-platform, shrink, render, hiccup, chassis]
---

# Lane shrink-web-a (Opus 5.5), 2026-09-23

Spec: slices S6 and S2 of `docs/research/agent-platform/shrink-web-2026-09-23.md` (428707005).

## S6: delete `seon.render.lint` and the two transcript clips (commit 9440698f3)

Changed paths:
- deleted `src/seon/render/lint.clj` (529)
- deleted `resources/seon/schemas/seon.render.lint.edn` (135)
- deleted `test/seon/render/lint_test.clj` (180)
- edited `src/seon/render/transcript.clj`

Net: src −532 lines, test −180, schema EDN −135.

- **No production caller.** `rg -lw seon.render.lint src test resources script bin .agents docs/seon`
  finds only `lint_test.clj` and a docstring in `src/seon/render.clj:973`. That docstring is not
  in this lane's ledger; its fix is below under "Needs another holder".
- **Tests.** Every `lint_test` deftest called `seon.render.lint/check`, `balance`,
  `text-content` or `element-with-id` on synthetic hiccup. None of them read served HTML.
  Under the three questions they test deleted machinery, so they were deleted and none was
  converted to jsoup: no surviving seam carried their behavior.
- **`transcript.clj:1759` `reply-intent`.** The `(subs line 0 90)` + "…" clip is gone, so HTML
  carries the whole intent line. The function gained a docstring and a contract
  `[:=> [:cat [:or :nil :string]] [:or :nil :string]]`.
- **`transcript.clj:386` `floor-text`.** The `pr-str` fallback for a unit with no SCI context is
  gone, and every history value goes through `seon.render.value/render-ai`. The fallback was
  added in 066fc962c when the floor threw without a ctx. That reason no longer holds:
  `value-node*` tries a declared pair only `(when (and (map? value) (:seon.sci.eval/ctx unit) …))`
  (`src/seon/render/value.clj:351`) and otherwise prints structurally. The function also
  gained a contract.

### Evidence

- **Incremental adoption of the schema retirement (the 1.3e gate).** The scratch root was
  `tmp/shrink-web-a/root`, started at parent `ee55febe8` with provider keys unset. It was moved
  (`seon.operator/request! {:command :start :head? true :seon.source/revision …}`) to proof
  revision `ac98f0b4f`, which is `ee55febe8` plus exactly this commit's four paths, built with
  `git commit-tree` from a temporary index. The move exited 0, the git sha was `ac98f0b4f…`,
  the adoption commit id was `6ab34d4c-8e7f-5015-a586-34fa01dfcc72`, `:missing-layers []`, and
  there was no retirement refusal. Logs are in `tmp/shrink-web-a/{nuke,move}.log`. The scratch
  JVM was stopped right afterwards on the orchestrator's correction (one shared JVM), and no
  other scratch will be started.
- **The value renderer needs no ctx.** On default, in JVM mode and read-only, this form
  returned `"{:my.message/reason \"why\", :seon.message/about \"x\"}"` in 1.7 ms:
  `(seon.render.value/render-ai {:seon.render/value {:seon.message/about "x" :my.message/reason "why"} :seon.render.call/id [:probe 1]})`
- **The new `reply-intent` body.** It was read from the committed file and evaluated in the
  throwaway namespace `probe.shrink-web-a` on default. A 149-character comment line comes out
  at 149 characters (`:whole? true`), and `nil` input returns `nil`. The run took 2.2 ms.
- **Contracts compile from zero.** `seon.contracts-compile-test/check` was run over
  `src/seon/render/transcript.clj` against the checkout's packaged forms, which are read from
  disk and no longer contain the lint schema. It found `[]` in 1,147 ms.
- **Hot path, parent.** On the scratch at `ee55febe8`, warm GETs took 63–74 ms for
  `/agent/root` (21,038 B) and 103–124 ms for `/agent/root/debug` (11,268 B). The first GETs
  took 3.8 s and 2.4 s.
- **Hot path, own.** Not measured, because the scratch was stopped on the orchestrator's
  instruction before the post-move GETs. Default runs archive 428707005, so it does not
  execute this commit.

### Verification limits

- `bin/test-check` was not run. On default it would exercise default's archive (428707005),
  not this commit, so a green there would not be evidence for S6. `seon.render.transcript-test`
  runs once default moves to a HEAD that contains 9440698f3.
- There is no browser paint of the changed transcript. Default's page runs old code.
- `test/seon/schema/datahike_parity.edn` still lists 32 `seon.render.lint` forms. That
  baseline has already drifted: 3,277 forms differ between it and default's handed projection
  (a read-only probe on default, 85 ms). It is left untouched.

## S2: chassis serializer (stopped at its gates, nothing deleted)

The spec says to prove byte-equal output first and only then delete. The proof failed, so
nothing changed in `hiccup.clj` or `deps.edn`.

The measurements ran in a standalone JVM on `clojure -Spath` plus
`~/.m2/…/chassis-1.0.365.jar`, which was already local because hyperlith uses it. Chassis
1.0.365 depends on nothing but Clojure 1.11.1. The old serializer was HEAD's `hiccup.clj`
with its schema requires stripped (probe files are in the lane scratchpad). The new one was
chassis `c/html` over a normalizer that keeps Seon's grammar: shorthand parsed by
`shorthand`, a sorted attribute map, `class-value` and `style-value` strings, keyword,
symbol and inst text as `str`, void children dropped, and `Raw` mapped to `c/raw`.

1. **Byte-equal is impossible with chassis's own escaping.** Chassis escapes text as `& < >`
   only, while Seon also escapes `"` → `&quot;` and `'` → `&#39;`. In attributes chassis writes
   `'` as `&apos;` (`chassis/core.clj:234-243`, `:829-836`). Over a corpus of 2,001 values
   (2,000 `hiccup-generator` samples plus a 170-unit page), 796 differ. With Seon's five-character
   escape swapped in, 385 still differ, because chassis always writes `id` before `class`
   (`core.clj:594-622`) where Seon sorts attributes. Both differences change bytes that
   equality suppression and existing test assertions rely on (7 assertions contain
   `&quot;` or `&#39;`). The escape seam is a global `alter-var-root` of chassis's
   `escape-text-fragment`.
2. **The hot path gets slower.** Over 300 iterations of the 170-unit page (80,989 B):
   - HEAD `->string`: 2.60–2.76 ms.
   - chassis plus normalizer: 3.86–4.16 ms, about 1.5× slower. That breaks the 20% hot-path rule.
   - chassis alone on a pre-normalized tree: 0.91–1.16 ms. The whole cost is the normalizer,
     which rebuilds the tree.
3. **Only about 100–120 lines would go, not about 290.** `shorthand`, `style-value`, `class-value`
   and `attribute-name` must stay, because chassis has no camelCase style keys, no sorted
   attributes, no keyword text with a colon and no string or symbol tag heads.

Options, simplest first:
- **(a) Keep the hand serializer. Recommended.** It is already the fast path at 2.7 ms per
  170-unit page, and its bytes stay as they are. It costs nothing and gives up about 100 lines.
- **(b) Chassis plus normalizer, accepting chassis's escaping and id-then-class order.** About
  −110 lines. It is about 1.5× slower on every page. Bytes change, so every string-asserting
  test and the equality suppression need re-baselining.
- **(c) Chassis with no normalizer, narrowing `hiccup?`** to keyword heads, string style values
  and string class values. About −300 lines, and 2.7× faster. It narrows an admitted grammar
  (breakage by law): agent `:fontSize` styles go dead and keyword text loses its colon, and
  every caller would need converting first.

## Needs another holder

- **`src/seon/render.clj:973-979`.** The `unknown-html` docstring names "the ONE placeholder
  class `seon.render.lint` counts". Replace that sentence with: "It keeps the
  `seon-render-unavailable` class, the compact diagnostic face in the stylesheet."
- **`resources/public/css/input.css`.** Visual truncation of the ledger story is optional,
  because HTML now carries the whole text:
  `.seon-ledger-story { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }`

## Out-of-scope issues filed

- `docs/seon/issues/index-evidence-current-refuses-nil-source-on-agent-page-get.md`
- `docs/seon/issues/mcp-eval-refuses-map-results-without-cluster-projection-state.md`

## TIMINGS

| Operation | Wall ms | Justification / status |
|---|---|---|
| scratch nuke at ee55febe8 | 149,030 (launch ~, ready 100,194, source 8,598) | This is a from-zero boot of the whole program; the orchestrator has since retracted this method. Over 10 s is a DEFECT, already covered by `docs/seon/issues/from-zero-boot-takes-minutes.md` (for the orchestrator to fold in). |
| move ee55febe8 → ac98f0b4f | 168,658 (launch 136,098, ready 106,712, adopt 13,535, archive 7,661, classpath 6,930, source 14,919, down 3,138) | Resume plus incremental adoption. Over 10 s is a DEFECT; see `a-three-file-changed-path-publication-takes-a-minute.md`. The adoption itself took 13.5 s for a 4-path difference. |
| first GET /agent/root, /agent/root/debug (parent) | 3,838 / 2,384 | The first render on a fresh JVM, which pays cold render-call caches. Over 1 s is unjustified here; it belongs to the page-cache owner (research S5). |
| second GET /agent/root (parent) | 1,142 | Same as the row above. |
| contracts-compile check, transcript.clj | 1,147 | Builds the whole packaged projection from disk (306 entity declarations) because the working tree differs from default's carried forms. Proportional to the schema population, not to the one file. This is the checker's own cost. |
| standalone load of `seon.render.hiccup` | 19,933 | Loading `seon.schema` from source with no AOT. Avoided by stripping the schema requires for the S2 bench. Over 10 s is a DEFECT of the source-load path; it is the same class as the from-zero boot note. |
| S2 bench JVMs (3 runs) | 12,746 / ~13,000 / ~13,000 | JVM start, then 300 warm iterations × 4 variants plus 2,001 equality samples. One-off lane measurement, not a system operation. |
| `bin/seon down` scratch | 1,086 | Stops the JVM and waits for its exit. |
| every REPL probe on default | 6–91 | Sub-second. |
