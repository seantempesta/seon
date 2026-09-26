---
type: plan
lane: shrink-web-opus
status: "PLAN ONLY. No source edits were made; root rules on each slice before any is executed."
created: 2026-09-26
files: src/seon/render/web.clj (3,911), src/seon/render/transcript.clj (2,459), src/seon/render.clj (1,919), src/seon/render/value.clj (1,288, unowned, taken); total 9,577
---

# Shrink plan: render.clj, render/web.clj, render/transcript.clj, render/value.clj

**What exists.** One render pipeline. `render/selection` picks a producer (the value's own key, then the viewer
namespace, then the declared pair, then the floor). `render.value/prepare` is the floor, and the only place that
clips. `render/render-call` wraps a producer call in a retained-call cache that read evidence validates. `web.clj`
derives pages from `render.walk` units and serializes them into Datastar packages. `transcript.clj` renders history
(for the AI and HTML faces), the runtime row, and a family of debug pages (session, outline, ledger, problems).

**Principal approach.** Fix the costs first where render work is proportional to something other than what is shown.
After that, delete dead and duplicate paths that have proof. Page families that may be abandoned go to the owner,
not the knife.

## Evidence used

- **Profile rows.** Default pid 89341, read-only through JVM-mode MCP, at an uptime of **666 s**. The JVM was barely
  exercised: no provider turns, and the page was touched about 20 times. The render rows below are real, but the
  cold-page figures come from today's landings.
  - `seon.render.value/prepare`: 343 calls, mean 6.6 ms, max 97 ms.
  - `select-nodes`: 197 calls, mean 2.7 ms.
  - `render/viewer`: 125 calls, mean 2.1 ms.
  - `render/declared-projection` (selection per nested map): 36 calls, mean 3.3 ms.
  - `render/render-call`: 8 calls, mean 16.8 ms. Of that, `call-cache-evidence` is 8.1 ms and
    `render-program-evidence` is 7.5 ms.
  - `transcript/history-entries`: 1 call, 31 ms.
  - `web/render-step`: 20 calls, mean 1.7 ms.
  - `render/call-with-walk-context` shows **113 calls, mean 144 ms, max 1,385 ms**. This is *not* a render cost.
    It is a `binding` wrapper around the whole form evaluation (`turn.clj:4952`), so the time of each evaluation is
    charged to render. See item D10.
- **Measured here.** `(seon.db/stored-call-reach db 'seon.render.transcript/render-ai)` takes **6.5–7.3 ms per
  call**, 5 of 5 runs, returns **795 symbols**, and is recomputed on the same database value (nothing memoizes it).
- **Landings and tasks.**
  - `landing/debug-context-page-2026-09-26.md`: the root n=2 page costs 2.25 s and 6.8 GB cold. An n=2 load ran
    14.8 s and preceded the 10:10 OOM. A 48-node page made 2.3 M `canonical-value-string` calls and spent 1.1 s in
    `read-evidence`.
  - `landing/mem-idle-2026-09-26.md`: the `:seon.render/cache` atom holds 69.8 MB at idle.
    `::graph-calls` is 36.7 MB and `::invocations` is 27.5 MB.
  - `perf-profile-2026-09-26.md` rows 8 and 10.
  - `perf-eval-overhead-2026-09-26.md`: per form, recording and printing cost about 2.5 ms and `history-entries`
    about 2.1 ms.
  - Tasks `76e1261553f3` (cold renders recompute read evidence), `55974f161f71` (the render cache keeps every debug
    view), `c5e9496b4cb1` (captured invocations blank source faces) and `135d92ea1308` (a renderer is chosen by key
    presence).
- **Dead-code proof.**
  - A clj-kondo analysis over `src script test` with `--cache false`
    (`tmp/shrink-web-opus/analysis.edn`, `dead.bb`, `defs.bb`).
  - Then `rg` over `src script resources config test docs/seon .agents bin`. This catches schema `:gen/gen`,
    `:seon.render/*` pairs and `requiring-resolve` symbols, which kondo cannot see. Examples: `derive-context!` and
    `declared-projection` are called through `requiring-resolve`, and they are live.

## 1. Slow paths first

| # | Where | What the cost is proportional to | What it should be proportional to | Fix | Size |
|---|---|---|---|---|---|
| P1 | `render.clj:1569` `(db/read-evidence @captured {:seon.db/retain-read-results? true})`, and the same flag at `web.clj:1439, 1496, 1710, 1982, 2557` and `walk.clj:455` | Every captured read of every render call gets `stable-value` plus `read-result-digest` over its **whole result** (`db.clj:948-979` → `schema/canonical-value-string`), and the result itself is retained. That is 2.3 M `canonical-value-string` calls and most of 6.8 GB cold, and it is the retained bulk of the 70 MB idle cache. | Revisions plus index evidence. `db/read-evidence-current?` (`db.clj:1173-1213`) checks those first; the replay/digest arm runs only when revisions differ. | Drop `retain-read-results?` at the render sites (six flags, net 0 lines). **Trade:** after a write to an attribute a call read, where index evidence is not decisive, the call re-renders instead of replaying and comparing the read. **Probe before commit:** debug page n=2 cold/warm ms and bytes, and an idle cache size after 10 page loads. Measure on parent and change on the same probe. | −6 flags. Semantics: fewer "still current" proofs, never a false one. |
| P2 | `render.clj:828` `render-program-evidence` → `db/stored-call-reach` (6.6 ms, 795 syms), inside `call-cache-evidence`, which `render-call` computes **twice** when a retained call misses (fast-path check at `:1466`, then again at `:1508`). `web.clj:2506` computes it again in `derive-context!`. | Every render call on a new program snapshot × the producer's transitive reach. Each check then walks those 795 rows in `program-evidence-current?` (`:820`). | Once per (producer, program identity). | (a) Compute `call-cache-evidence` once per `render-call`. The fast path's value is the slow path's value; today the second computation repeats it (≈ −6 lines). (b) Key program evidence by what it reads: `seon.cache/lookup ::call-reach [selected program-snapshot]` (the snapshot is an immutable value; Caffeine hashes it once). Or ask the db.clj owner to memoize `stored-call-reach` per commit id. (c) Compare program evidence by the rows' definition digests, not by row maps. | ≈ −10 / +4 |
| P3 | `web.clj` `derive-page!` / `render-pass` / `graph-debug-html` cache keys `::graph-calls`, `::invocations`, `::calls`, `::ai-calls`, `::page-results`, `::fragments`, `::page-databases`, `::programs`, `::packages` in one **unbounded atom** (`render.clj:1655 shared-cache`, riding `projection-state`) | Every debug view ever opened, every invocation ever made. 69.8 MB idle, no eviction. This breaks the CLAUDE.md cache law ("every cache is `clojure.core.cache` … bounded … size is a configuration dial"). | Views currently open, under the one byte budget. | This is a design decision, not a patch. Options are in §1a. | see §1a |
| P4 | `render.clj:665 namespace-stage`, called per nested map by `declared-projection` (3.3 ms mean) | For every map the floor reaches: viewer functions × `schema/function-accepts-and-returns-in?` over the value. | Viewer functions × distinct argument *shapes*. | Hold for now. Cheap until an agent defines many render functions. Recorded as a known cost; no slice. | 0 |
| P5 | `transcript.clj:805 history-entries`, per MCP SCI form (~2.1 ms) | An extra namespace `q` (`:813`), plus `entry-basis` (`:798`), which scans **every datom** of each entry lacking `read-basis`, plus a `branch-errors` pull per fault. | The entries shown. | Pass `namespace-name` the caller already has, or read it from the pulled receipt (`::namespace` is already derived in `receipt-entry:274`), which deletes the `q`. `entry-basis` should use the receipt's `:seon.cluster.eval/read-basis-transaction`; a frozen evaluation without one is the only fallback. | ≈ −12 |
| P6 | `render.clj` `request-profile` is re-entered at `render-ai`, `render-html`, `render-form-value`, `render-call` and `selection` (5 copies of `(let [profile (request-profile r)] (if refused profile (let [r (assoc r …)] …)))`) | One `config/effective` per entrance when the request carries no profile (451 calls in 11 min). | Once per request. | Fold into D6: one face entrance. | in D6 |

### 1a. The render cache: three options (owner/Astra ruling needed before P3)

1. **Bound what exists (simplest).**
   - Delete `::graph-calls`. The debug context page re-renders on each load: cold is 2.25 s today, and P1 should
     lower it.
   - Hold `::invocations` in `seon.cache` under kind `:seon.render/invocation`, keyed by the existing
     `invocation-cache-key`, with a per-kind weight.
   - Page state (`::packages`, `::fragments`, `::calls` per registration key) is already limited to open tabs
     (`invalidate-runtime-derived-state` keeps watched tabs only).
   - **Guarantee:** bounded memory, no behaviour change on live pages. **Cost:** about 1 slice, ±30 lines.
     **Sacrifice:** the debug page is never warm.
2. **(Recommended) One content-keyed render entry.**
   - `render-call` looks up `seon.cache` by `[producer program-evidence-digest argument-digest output profile]`
     (the existing `::selection-input`, digested). The entry carries its read evidence without results (P1).
   - A stale entry is simply re-derived under the same key and replaced.
   - This deletes `retained-calls`/`captured-calls`/`invocations`/`captured-invocations` threading through
     `render.clj`, `web.clj` (69 mentions), `walk.clj`, `transcript.clj:2093-2112` and `prompt.clj`, and it deletes
     `web.clj`'s `::calls`/`::page-results`/`::page-databases`/`::programs`, `program-key`, `candidate-call-ids`,
     `refresh-root`, `newer-page?`, `replace-current-invocation!`, and `refresh-retained-read-evidence`.
   - **Guarantee:** one cache, bounded, no page-specific keys. It also kills `c5e9496b4cb1`, because captured
     invocations stop being an argument that changes what the output means.
   - **Cost:** 3–4 slices across render.clj, web.clj and walk.clj, plus `prompt.clj`/`turn.clj` callers. That means
     cross-owner patches. **Estimated net −600 to −900 lines.**
   - **Sacrifice:** fragment-level "unchanged, skip" (`page-result` fragment evidence) becomes one cache hit per
     unit, not a map compare. This needs Astra review of the design first.
3. **Replace the page-side cache with Datastar whole-page morph.**
   - Drop `next-package` deltas and `::packages` revisions, and send the keyframe every pass; Datastar morphs by id.
   - **Guarantee:** the least page code. **Cost:** 1 slice (−120 lines).
   - **Sacrifice:** bytes per pass on large pages. Measure the keyframe size first; the landing's root page is
     312 KB.

## 2. Deletions, with proof

Line numbers refer to HEAD `b6660a34b`. "In/out" is the kondo usage count inside the file / in other files, and `rg`
confirms each one.

### Dead: no production caller

| # | Definition | Proof | Lines | Tests to delete or convert | Risk |
|---|---|---|---|---|---|
| D1 | `web/join-package` (`web.clj:1797-1805`, the identity function) | kondo: 0 src callers. rg: only `render_simplification_test.clj:670-672` (via `target-call`) and stale claims in `.agents/skills/datastar-web-ui/SKILL.md` and `ui-canvas/SKILL.md`. | −9 | delete the 3 test lines; delete the skill sentences in the same commit | none |
| D2 | `value/keyword-entry` (`value.clj:613-619`) | kondo 0/0; rg: no reference anywhere | −7 | — | none |
| D3 | `render/unknown-output` (`render.clj:1094-1102`) | 0 src; rg: `render_coverage_test.clj:541,551,577` only. `unknown-ai`/`unknown-html` are the live faces and have their own tests. | −9 | rewrite the 3 asserts to call `unknown-ai`/`unknown-html` | low |
| D4 | `render/render-default-ai-source` (`render.clj:1417-1425`) | 0 src; rg: `render_source_test.clj:165-224` only. It is `pr-str` of the live `render-form`. | −9 | the test asserts through `render-form` | low |
| D5 | `render/render-form-value` (`render.clj:1427-1438`) | 0 src; rg: `render/history_test.clj:91,99` only | −12 | the test calls `render-call` with `:seon.render/output :seon.render/form` | low |
| D6 | `render/render-html` (`render.clj:1352-1368`) is a copy of `render-ai` that differs only in the output key | 0 src; tests in `render_coverage_test`, `entity_pairs_test`, `web_test`. The live HTML path is `render-call`. | −17, and with D5 and P6 one `render-face [request]` reading `:seon.render/output` (≈ −25 net) | convert tests to `render-ai` with `:seon.render/output` | medium: `:seon.fn/external-sink :html-response` metadata feeds `fn.clj:2276-2319` sink checks; verify no required-sink finding appears when it goes |
| D7 | `transcript/ledger-evaluations` (`transcript.clj:1722-1726`, private) | kondo `unused-private-var`; used only through `#'` in `web_debug_test.clj:158,196` | −5 | the test reads `ledger-acquisition` directly | low |
| D8 | `render.walk/entity-lookup` (`walk.clj:166-175`), **walk.clj is not in my set** | kondo 0/0 in src and test; only its schema row in `seon.render.walk.edn` | −10, plus the schema entry | — | none. Root assigns walk.clj or applies the patch. |
| D9 | unused requires `seon.blob` and `seon.sci.admit` (`web.clj:55,82`) and `seon.error` (`render.clj:25`); unused bindings (`render.clj:1013,1033-1034`, `web.clj:1718`); `(or x)` (`web.clj:1428`) | kondo findings | −8 | — | none |

### Duplicate paths: keep one

| # | Duplicate | Keep | Proof | Lines | Risk |
|---|---|---|---|---|---|
| U1 | `web/refresh-retained-read-evidence` (`web.clj:1360` declare and `:1555-1568`) is the second hand-written copy of `render/refresh-read-evidence` (`render.clj:943`). The keeper's own docstring says: "a second hand-written copy of this walk is a defect". | `render/refresh-read-evidence` | Same body, over the vector instead of the entry: `(:seon.render.call/read-evidence (render/refresh-read-evidence db {:seon.render.call/read-evidence v}))` | −15 | low |
| U2 | `render/same-call-cache-evidence?` (`render.clj:874` declare and `:941` `def` alias) renames `same-invocation-evidence?` | `same-invocation-evidence?` | the alias adds nothing; 4 in-file uses | −3 | none |
| U3 | `render/transacted` (`render.clj:300-304`) only renames `render.value/transacted` | `render.value/transacted` | callers: `walk.clj:104` (not mine; patch), `history_test.clj:76` | −5 | none |
| U4 | `render/entity-lookup` (`render.clj:1370-1378`) and `walk/stable-lookup` (`walk.clj:158`) re-derive `render.value/value-identity` with a `:db/id` fallback | `(or (value/value-identity db v) (:db/id v))` | same identity-attribute scan; `value-identity` also guards with `keyword-lookup?` | −9 | low: `value-identity` refuses non-`keyword-lookup?` maps, where `entity-lookup` did `contains?`. A regression is needed for a sorted map with a non-keyword key. |
| U5 | `value/identity-address` (`value.clj:293-318`) is a 25-line docstring over `(value-identity (:seon.db/db u) (:seon.render/value u))` | inline at its one caller (`node-id`) | one caller | −24 | none |
| U6 | `web/assigned-agent-namespace` (`web.clj:1371`, a pull) duplicates `web/agent-namespace` (`web.clj:3055`, a q) in the same file. `render/viewing-namespace` (`:641`), `render/repl-state` (`:1793`) and `transcript/history-entries` (`:813`) query the same fact. | `web/agent-namespace`, public from one place (or `seon.agent` if an owner fn exists; the project has 13 copies) | same read | −6 in web.clj; −10 more once render and transcript call it | low |
| U7 | `transcript/receipt-entry` (`:254-296`) renames about 20 evaluation keys to `::keys`, and `transcript/emission` (`:457-496`) renames them back to the *same* evaluation keys for `seon.repl` | `emission` reads the receipt entity (`::entity`) with `select-keys`, adding handle, missing, errors text and namespace | by `grep -c`, 13 of the 17 renamed keys appear only in `receipt-entry` and `emission` (the rename in, then a test and the rename out) | ≈ −35 | medium: the in-flight `candidate-history` branch (`:645-690`) builds an evaluation map and must supply the same keys. The regression is `transcript_test` history bytes, stored vs. in-flight equal. |
| U8 | `transcript/history` has a three-arity nil-threading chain (`:376-381`) | one arity with an options map | 2 callers | −4 | none |
| U9 | The "is this a refusal" test `(or (:seon.render/refused-member x) (:seon.render/candidates x) (:seon.render.unknown/reason x))` appears 4× in render.clj and 3× in web.clj | one private predicate in render.clj, used by web.clj | textual | ≈ −6 | none |
| U10 | `render-call` reads `call-cache-evidence` twice and `invocation-argument-evidence` twice (via `call-static-evidence` and `call-cache-evidence`) | compute once in `render-call`, pass both | same inputs | ≈ −8 | low (this is P2a) |

### Page features: owner decision, not deleted without a ruling

`debug-response` (`web.clj:3403-3530`) serves **seven** debug surfaces behind query flags:

| Surface | Flag | Where | Lines |
|---|---|---|---|
| session record | `?record` | `web.clj` | — |
| outline | `?outline` | `transcript.clj:1467-1705` | ~240 |
| turn card/context/ledger/full | `?turn` | `web.clj:3187-3230` + `transcript.clj:1706-1976` | ~290 |
| sent context | `?sent` | `web.clj:3287` | — |
| graph context page | `?n=` | `web.clj:3231-3402`, from 2026-09-25/26 | ~170 |
| session ledger / loading | (none) | `transcript.clj:1110-1466` + `2286-2403` | ~475 |
| subject debug with renderer experiments | `?subject=` | `web.clj:578-1796` | ~1,200 |

The problems panel (`transcript.clj:1977-2285`, ~310 lines) sits beside these. Tests: `web_debug_test.clj`
(1,116 lines), `run6_stall_test`, `rereads_panel_test`, and part of `transcript_test`.

**Owner question:**

- The graph context page (`?n=`) is the owner's newest context-tuning page.
- The outline replaced the session dump (owner, 2026-09-16).
- The subject/renderer-experiment page dates from 2026-09-06.

If the graph page supersedes the subject/experiment page and the session ledger, the cut is **about 2,300 source lines
and about 1,000 test lines**. That is the single largest shrink available in these files, and the largest reduction
in the unbounded cache keys (P3). I recommend asking before anything else, and not touching it until the answer
arrives.

### D10: ambient walk custody, a design note with no slice yet

`render/call-with-walk-context` binds the dynamic `*walk-context*` and `db/*conn*` around every form's evaluation
(`turn.clj:4952`), only so that `render/walk` (the agent's `(seon.render/walk)`) can find its custody. The SCI
context already carries custody (`:seon.sci.eval/custody`). Reading it there would delete the binding, the dynamic
Var and the profiling artifact. Measuring shows `walk` has no production caller besides agents, and it is taught in
`cluster/instruction.clj:15`. This needs `turn.clj` (shrink-turn lane) and a check of how an SCI-called fn receives
its ctx, so I am not slicing it.

## 3. Ordered loadable slices, slow first

Each slice runs its checks before commit:

- `clj-kondo --lint src script test --cache false`: 0 errors.
- `seon.contracts-compile-test/check` over the changed files: 0 findings.
- Touched schema resources still read.
- At most one focused `bin/test-check default --ns <ns>`, only when no other test-check is running.

**S1 (P1). `retain-read-results?` off at the render sites.**
- Files: render.clj, web.clj (walk.clj:455 as a patch).
- Probe:
  1. Debug page n=2, cold and warm: ms and allocated bytes.
  2. `seon.render/cache` size after 10 loads.
  3. Page-feed repaint count across 5 unrelated commits: expect more re-renders, not wrong ones.
- Proves behaviour: `test/seon/render/retained_test.clj`, `render_cache_test.clj`.
- Net: 0 lines. The biggest cold cost.

**S2 (P2a + U10). One evidence computation per `render-call`.**
- Files: render.clj.
- Proves: `test/seon/render/call_test.clj`, `render_source_test.clj`.
- Timing: `render-call` p50 on the debug page, parent vs change.
- Net: about −14 lines.

**S3 (P2b). Program-evidence keyed by program identity.**
- Files: render.clj; a patch to db.clj if root prefers memoizing `stored-call-reach` there.
- Proves: the same tests plus `web_context_test`.
- Probe: 166 faces cold ms.
- Net: about ±0.

**S4 (P5). `history-entries` without the extra namespace `q` or the datoms scan.**
- Files: transcript.clj.
- Proves: `transcript_test`, `sci/render_source_test`, `repl_grammar_test`.
- Timing: MCP form p50.
- Net: about −12.

**S5 (D1 D2 D3 D4 D5 D7 D9 U2 U3-own-part U5). Dead definitions and aliases.**
- Files: all four and their tests, plus the two skill sentences for D1.
- Net: about −90 source lines and about −30 test lines.
- Proves: kondo, the contracts check, and the converted tests.

**S6 (U1 U4 U6 U9). Duplicate helpers.**
- Files: render.clj, web.clj, transcript.clj.
- Net: about −45.
- Proves: `web_test`, `web_debug_test`, and a new regression for the U4 sorted-map edge.

**S7 (D6 + P6). One face entrance, `render-face`.**
- Collapse `render-ai`/`render-html`/`render-form-value` and the repeated profile preamble.
- Files: render.clj; `turn.clj:4794` keeps calling `render-ai` (kept as the name) or gets a patch.
- Net: about −40.
- Needs the fn.clj sink check (D6 risk) first.

**S8 (U7 U8). Transcript emission reads the receipt directly.**
- Files: transcript.clj.
- Net: about −40.
- Proves: the transcript byte-equality regression, stored vs in-flight.

**S9 (P3, after the §1a ruling).**
- Option 1: one slice, about ±30.
- Option 2: 3–4 slices, about −600 to −900, with an Astra design review first.
- Option 3: one slice, about −120.

**S10 (page families, after the owner's ruling).** Up to about −2,300 source lines and about −1,000 test lines.

**Totals without S9 and S10:** about −240 source lines across the four files, from 9,577 to about 9,340. The slow
slices S1–S4 carry the performance wins. The structural shrink the target needs is S9 plus S10.

## For root

- **Cross-owner patches needed.**
  - `walk.clj` (D8, U3 caller, S1 flag). It is unassigned; I can take it.
  - `turn.clj` (D10, S7 caller): shrink-turn.
  - `db.clj` (S3 option): shrink-db.
- **Owner questions.**
  - (1) The debug-page families (S10).
  - (2) The render cache option (§1a).
  - (3) The S1 trade: re-render rather than replay a read whose attribute changed.
- **RESET NEEDED:** none. No source was edited.
