---
type: report
status: measured
created: 2026-09-26
tags: [agent-platform, render, print]
---

# Lane impl-floor-on-fipp (2026-09-26)

The floor's AI text is now laid out by fipp's own engine over an explicit-stack
operation stream; the HTML is a fold over the same stream. Commit `63eb0dbcb`.

## Broken or incomplete first

1. **Root's default boot failed on this lane's uncommitted tree (23:57Z).** The
   first write deleted public `seon.print` Vars that `test/seon/print_test.clj`
   and `test/seon/instrument_test.clj:1404` still named. Boot's static analysis
   (`seon.fn/assert-clean-analysis!`, `fn.clj:1336`) treats a test file's
   unresolved Var as blocking. Root parked the patch and restored the files.
   The committed slice was linted together with every file that names
   `seon.print` before it was written.
2. **Dead code kept on purpose:** the `Sink` protocol, `text-sink`, `sink?` and
   `:seon.print/sink` stay only because `test/seon/instrument_test.clj:1404`
   asserts `(print/sink? (print/text-sink …))`. That file is not this lane's.
   To delete them, remove that one assertion. The probe namespace in the same
   test already covers the reload class. Then delete the four definitions
   (about 25 lines).
3. **The one-budget shell-order selection (assignment items 1 and 2) is not
   built.** Root queued it separately as design §6 row 2(i). The files it needs
   are listed below. `print/fit` still does the halving search, but it now
   measures the laid-out text that is saved.
4. **Layout width stays 72, not the design's 100.** `cluster.clj:580` uses
   `:seon.print/width` as `get_value`'s string page size, and
   `test/seon/cluster/mcp_test.clj:254` hard-codes 72. Row 2(i) deletes that
   window, and then the default can become 100. At 72, an entry of about 80
   characters breaks after its key.
5. **`:seon.print/face` is not renamed.** It is the node's dispatch key, used by
   `sci/admit.clj`, `render.clj` and stored artifacts, and the face audit
   (`face-concept-audit-2026-09-26.md`) had not landed. This lane renamed only
   its own concepts: `:seon.print/node-face` → `:seon.print/node-kind`,
   `scalar-faces`/`structural-faces` → `*-kinds`, and `:seon.print/unknown-face`
   → `:seon.print/unknown-kind`. It also fixed the botched
   `(:?_current-ns_?/face node)` read in the unknown-kind diagnostic.
6. **Expected foreign reds (retired assumptions, not regressions):** any test
   that compares a long AI text with a single-line string now sees line breaks.
   `test/seon/schema/datahike_parity.edn` records `:seon.print/node-face`,
   `:seon.print/unknown-face(-error)` and the `:sink` message, so the canonical
   drift check reports them.

## What changed (`63eb0dbcb`, +329 / −621 across 6 files)

- `src/seon/print.cljc` (1,307 → 1,045 lines). `node-ops` walks a node tree with
  an explicit stack and emits fipp operation maps (`engine.cljc:12–65`).
  `ops-text` runs `annotate-rights`, `annotate-begins` and `format-nodes`
  (`engine.cljc:68–223`) into one `StringBuilder`. There is no `serialize` and no
  `pprint-document`. Width 0 is one flat line. `ops-hiccup` folds the same ops
  into the same `<details>` hiccup as before.
- Layout rules: a collection is begin/open/nest/members/outdent/close/end. A map
  entry is begin/key/nest 2/line/value/end. A scalar-only vector, list or set
  fills its lines. Nesting stops growing at depth 6. A map lifts to `#:ns{}` only
  when it has at least two keys.
- **Deleted:**
  - the `TextSink`, `HiccupSink` and `TeeSink` emitters, with `append-chunk!`,
    `soft-separator`, `append-hiccup!` and `sink-result` (about 150 lines);
  - the `emit` multimethod family, `emit-sequential`, `emit-map-like`,
    `emit-entry`, `emit-separated`, `emit-node` and its per-node re-emission
    sort (about 330);
  - table emission: `table-row`, `table-data`, `pad-left`, `table-text`,
    `emit-table` (about 60), and the `:table?` reads in `value.clj`;
  - the duplicate child walk `node-children`, now collapsed into `child-nodes`;
  - `structural-cut?`, `visible-items` and `node-description`;
  - `:seon.print/unknown-face-error`, which had no producer.
- `src/seon/render/value.clj`: `prepare` builds its options once and lays the
  AI text out at their width. A `:single-line` profile stays flat.
  `print/fit profile options` measures that same laid-out text, so the budget is
  charged on the text that is saved.
- `deps.edn`: `fipp/fipp 0.6.29`. This is the version malli already brings
  (`reference-code/malli/deps.edn:7`), now declared because seon uses it
  directly.
- Tests: `print_test` converts the tee emission to the op stream and drops the
  table test. `value_test` replaces "AI values never wrap" with "laid out by fipp
  at their width" and drops the table-cardinality test.

## Evidence (REPL, default PID 76020, JVM mode, read-only)

The new code was proved first in a scratch namespace, `tmp.fipp-print`,
generated from the file with `::` rewritten to `:seon.print/`. It is unarmed.
The comparison script is `tmp/impl-floor-on-fipp/compare.clj`.

- **Flat equality:** with `namespace-maps? false` at width 0, the new text equals
  the parent's text on the agent row and on a stored error row. The hiccup's
  lexical text is equal too.
- **300 generated nodes (`print/node-generator`) at widths 0, 20 and 72:** text
  and hiccup lexical text agree, and the laid-out text reads back equal to the
  flat text. The only mismatches are `##NaN`, which is never equal to itself.
  One flat difference came from the object-without-class diagnostic and is
  fixed before the commit.
- **Depth 5,000 nested vector:** no stack overflow. The output has 49,988
  characters and 5,001 lines in 571 ms (text and hiccup).
- **The first measurement found reflection** on `StringBuilder.append`
  (1.16 ms for 316 ops). It is type-hinted now: flat takes 0.008 ms and layout
  0.08 ms.
- **`emit-both` on a stored error row:** 0.45 ms for the parent (unarmed
  original), against 0.25 ms flat and 0.29 ms laid out.

**Real values, before (parent `fit` + flat emit, armed) and after (`fit` +
fipp emit at 72, unarmed).** The same tree comes from `value-node`. The data
read back from both texts is identical in all 10 cases:

| value | budget (tokens) | chars old → new | lines | ms old → new | KB allocated old → new |
|---|---|---|---|---|---|
| namespace `seon.print` | 15,000 | 1,734 → 1,887 | 1 → 42 | 1.22 → 2.56 | 3,193 → 2,616 |
| namespace `seon.print` | 150 | 319 → 321 | 1 → 3 | 3.72 → 4.11 | 7,114 → 5,896 |
| stored error | 15,000 | 4,281 → 4,482 | 1 → 44 | 1.45 → 0.98 | 3,685 → 3,005 |
| stored error | 150 | 348 → 350 | 1 → 3 | 2.52 → 1.72 | 7,229 → 5,769 |
| turn | 15,000 | 270 → 277 | 1 → 8 | 0.47 → 0.27 | 1,010 → 821 |
| test run | 15,000 | 14,684 → 16,561 | 1 → 372 | 9.77 → 10.76 | 30,538 → 24,995 |
| test run | 150 | 424 → 428 | 1 → 5 | 4.40 → 3.22 | 14,564 → 11,840 |
| projection forms (3,435 keys) | 15,000 | 4,103 → 4,453 | 1 → 107 | 2.54 → 2.66 | 7,747 → 6,344 |
| projection forms | 150 | 314 → 315 | 1 → 2 | 8.76 → 6.62 | 12,521 → 9,553 |

Every row is single-run, warm. Layout adds 1–13% whitespace. Allocation falls
by 18–23%. The namespace row at 15,000 tokens is the only one that is slower by
more than 20%, by 1.3 ms, and the rest are within noise or faster. The parent
column is armed, so part of its cost is contract checking. The new code has not
been measured armed: root's adoption run will show that number.

Sample of the laid-out error row:

```
{:db/id 33545,
 :seon.error/declared-schema :seon.flow/exception-error,
 :seon.error/exception-class clojure.lang.ExceptionInfo,
 :seon.error/frame
   [seon.test$run$own_write_BANG___1090016 invoke "test.clj" 2204],
 :seon.error/occurrences
   [{:db/id 33547,
     :seon.error.occurrence/branch :agent-83890fd28bcc,
     …
```

## Root runs

```
bin/test-check default --ns seon.print-test
bin/test-check default --ns seon.render.value-test
bin/test-check default --ns seon.render.web-test
bin/test-check default --ns seon.render.transcript-test
bin/test-check default --ns seon.error-test
bin/test-check default --ns seon.mcp-bridge-test
bin/test-check default --ns seon.mcp-test
bin/test-check default --ns seon.instrument-test
```

Adoption: `src/seon/print.cljc`, `resources/seon/schemas/seon.print.edn`,
`src/seon/render/value.clj`. Default was still running the parent `seon.print`
when this note was written. The `deps.edn` line resolves to the jar that is
already on the classpath.

## Row 2(i) (shell-order selection and continuation): files it needs

The design's line references were checked against the tree today. The
`repl/pretty-response` read-back and `eval/drive` `read-result` it names no
longer exist in those files, so those conversions are already gone.

- **Selection and cuts:**
  - `src/seon/render/value.clj`: the queue and charge. It deletes `value-node*`,
    `value-node`, `ordered-head`, `window`, `display-value` and
    `attribute-value`'s reshaping, and replaces the bodies of
    `bounded-text`/`floor`.
  - `src/seon/print.cljc`: deletes the fit family, `value-at`, `elision-node`,
    `requery-fields` and `preserve-requery`, and adds the `…N` cut plus one
    legend.
  - Schemas: `resources/seon/schemas/seon.print.edn`, `seon.render.value.edn`
    and `seon.render.data.edn` (the step, the tree node, and the
    insufficient-budget and charge errors).
- **Navigation:** `src/seon/render/data.clj`, `parse-cursor` (`:27`) and `at`
  (`:46`).
- **Continuation entrance:** `src/seon/render.clj` needs `units`, which does not
  exist yet (building block S3).
- **Callers:**
  - `src/seon/cluster.clj`: `mcp-project` at `:388`, including `admit-value` at
    `:432` and `semantic-value` at `:481`; `mcp-get-value` at `:555` and its
    window at `:575–594`.
  - `src/seon/render/web.clj`: `data-response` at `:3606` and
    `debug-value-html` at `:796`.
  - `src/seon/sci/eval.clj`: `shown-result` at `:3663`, whose raw
    `:seon.instrument/check` reads at `:3684` and `:3698` move after
    classification.
  - `src/seon/db.clj:3036–3049`: `print/value-at` and `compare-values`.
- **Tests:**
  - `test/seon/print_test.clj` and `test/seon/render/value_test.clj`.
  - `test/seon/mcp_test.clj:80`, which redefines `#'value/value-node`. This one
    blocks boot analysis if `value-node` is deleted.
  - `test/seon/cluster/mcp_test.clj:254` and `:443`.
  - `test/seon/problems_test.clj:597`, `test/seon/render/transcript_test.clj:507–518`
    and `test/seon/cluster/prompt_test.clj:242`, which assert the requery and
    elision-map text.
  - `test/seon/instrument_test.clj:1404`.
  - `test/seon/schema/datahike_parity.edn`.

Released paths: `src/seon/print.cljc`, `src/seon/render/value.clj`,
`resources/seon/schemas/seon.print.edn`, `deps.edn`, `test/seon/print_test.clj`,
`test/seon/render/value_test.clj`, this note.

## Round 2 (root, after 63eb0dbcb was adopted): commits `03f029096`, `890a3de11`

### Broken or open first

1. **My defect in 63eb0dbcb, now fixed.** A bare cut among map entries lost
   its `:seon.print/elision` key, so a cut map printed an odd number of forms
   and could not be read back. The live default showed it:
   `{:db/id 33229, …, :seon.agent/namespace {:db/id 33223}, {:seon.print/bound-by …}}`.
   This caused the value-test reds `dir-of-a-large-namespace…`,
   `explicit-structural-results…` and very likely
   `generated-values-have-deterministic-readable-executable-elisions`.
2. **Foreign red: `an-explicit-pull-keeps-its-nested-shape-in-shown-text`.**
   A nested partial pull (`{:seon.ns/name …, :seon.ns/steward …}`) is claimed
   by the namespace pair through identity ownership. The pair then renders
   public functions and drops the pulled steward. This is the declared-stage
   selection (render.clj, identity owners from 0a `9b4a7522f`); it is not the
   floor. It needs a ruling: an attribute request should stay an attribute
   request.
3. **Foreign red: `block-pairs-remain-explicit`.** Probed on default:
   `render/render-ai` with `:seon.render.call/function
   'seon.sci.eval/directory-value` selects `seon.render.value/render-ai` (the
   floor), not `seon.repl/render-directory-ai`. The producer's head-Var
   return lookup does not reach the directory pair. Owner: render.clj.
4. **Foreign red: `transcript-test/one-reply-reads-identically…` (6 fails).**
   The prompt side (`walk/history` → `render-call`) renders every saved
   evaluation through the floor. The page instead uses `seon.repl/text`.
   Probed on default: a saved evaluation is valid `:seon.eval/entity`, which
   carries `seon.repl/render-ai`. But `:seon.cluster.eval/id` now declares
   `:seon.program/row-schema :seon.cluster.eval/receipt`, which has no pair,
   so selection reports `:declared :no-match` and falls to the floor. Owner:
   the identity-owner declaration (0a) or render.clj selection.
5. **The pair behind the walker's map is `seon.render.ns/render-ai`.** It
   returns its declared `:seon.db/invalid-read-error` ("datahike
   query-results budget exceeded") whenever `public-functions`' query exceeds
   `:max-results` = the caps' `max-collection`. This reproduces on default
   with max-collection 64 for `my.agents.root`. The file is held by
   delete-r8-r6-r10. Its fix is to stop bounding a namespace's member query by
   an admission cap, or to show a count and a continuation.
6. **Nested renderer prose reads poorly.** Declared AI text nested inside a
   raw value prints as an escaped string literal (`"…\n(seon.db/pull\n  '[…]"`),
   because the floor keeps its text readable as EDN. Printing that prose raw
   would read much better, but it gives up read-back. That trade is a ruling
   for the owner, and this lane did not make it.
7. **The ceiling cuts; it does not store.** Past
   `:seon.print/rendered-token-ceiling`, renderer text becomes an ordinary
   character cut with a prefix and its requery. It is not a blob reference:
   writing a blob needs a connection and publication, and a render must not
   write. Turning a giant into a blob belongs to whoever saves the shown text.
   Root-level pair output returned by render.clj `raw-output` never reaches
   the floor, so it has no ceiling at all.
8. The ceiling is a declared print option (`:seon.print/rendered-token-ceiling`,
   50,000 estimated tokens), next to `:seon.print/width`. It is not a config
   fact: making it one needs config/default.edn and render.clj's profile
   derivation, which this lane does not own. No existing config key fits.
   `:seon.config.eval.result/blob-threshold` is 4,096 bytes, a storage
   decision.

### What changed

- `03f029096` render/walk.clj (+5/−1): any map returned as output is a
  renderer failure (a diagnostic, delivered to the owner). AI output stays
  text and HTML stays hiccup (owner 2026-09-26). The schemas already say so:
  `:seon.render/ai [:or :qualified-symbol :string]`, `:seon.render/html [:or
  :qualified-symbol :seon.render/hiccup]`, `:seon.render/rendered [:or :string
  hiccup form]`. None was widened.
- `890a3de11` (+131/−62):
  - **Provenance budget.** `fit` charges only raw values to the profile
    budget. An `:ai` `::projected` node (declared renderer text) is never
    halved; it is cut only beyond the ceiling, and that cut names
    `:seon.print/rendered-token-ceiling`.
  - **One ordering owner.** `ordered-children` is used by `fit` and the
    emitter. It puts scalar nodes in `compare-values`' own order (type, then
    `compare`) and cuts last. `fit`'s text sort had reordered the walk's
    selection, which caused the value-test child-bound red.
  - **The elision key is back.**
  - **The Sink residue is deleted**, with the `instrument_test.clj:1404`
    assertion.
  - **Regressions:** `the-budget-is-chosen-by-provenance` and
    `a-cut-among-map-entries-reads-back-as-an-entry`.

### Measured on default (PID 87270, which still runs 63eb0dbcb; after = the committed file in a scratch namespace, unarmed)

| render | budget (tokens) | before | after | after fit+emit ms |
|---|---|---|---|---|
| root agent row (nested `:seon.agent/runtime` pair) | 150 | 119 | 221 | 7.8 |
| root agent row | 15,000 | 221 | 221 | 1.4 |
| turn with its agent (turn pair at the root) | 150 | 140 | 1,899 | 0.15 |
| turn with its agent | 15,000 | 1,899 | 1,899 | 0.07 |
| stored error row (error pair) | 150 and 15,000 | 55 | 55 | 0.06 |

At the default budget nothing changes. At a tight budget, the renderer's
text is kept whole and the raw siblings still show, where before the text
was cut to an elision. The root agent row at 150 tokens, after:

```
{:db/id 33229,
 :seon.agent/branch :cluster-default,
 :seon.agent/id "root",
 :seon.agent/namespace {:db/id 33223},
 :seon.agent/plan {:db/id 33230, :my.plan/agent {:db/id 33229}},
 :seon.agent/runtime
   ";; My trigger and listens; (seon.db/pull '[{:seon.agent/runtime [:seon.runtime/turns]}] [:seon.agent/id \"root\"]) reads my turns on demand.\n(seon.db/pull\n  '[{:seon.agent/runtime\n …",
 :seon.agent/settings {:db/id 33231, :seon.config/agent {:db/id 33229}}}
```

The balance is right: renderer text is whole, and the raw structure is laid
out and bounded. The nested prose's escaping is the weak point (item 6).

### Root runs

```
bin/test-check default --ns seon.print-test
bin/test-check default --ns seon.render.value-test
bin/test-check default --ns seon.render.transcript-test
bin/test-check default --ns seon.render.walk-graph-test
bin/test-check default --test seon.instrument-test/restoring-instrumentation-state-never-reinstalls-a-replaced-definition
```

Released: src/seon/render/walk.clj, src/seon/print.cljc,
resources/seon/schemas/seon.print.edn, test/seon/print_test.clj,
test/seon/instrument_test.clj, this note.

## Round 3: commits `a33382621`, `595bac7f6`, `0f54105f5`, `225b23639`

### Open first

1. **(4) Directory selection is not fixed: it needs `src/seon/sci/eval.clj`.**
   `seon.sci.eval/directory-value` declares `[:or :map :seon.db/error-result
   …]`. Its value validates as `:seon.repl/directory` on default, but an
   anonymous `:map` names no schema, so `satisfied-alternatives` finds no pair
   and selection falls to the floor. The fix belongs to the producer's
   contract: return `:seon.repl/directory`. A special case in render.clj
   would be a patch at the wrong owner. Request: grant sci/eval.clj, one
   contract line.
2. **Two walk.clj atoms remain.** `captured` is the `db/*read-evidence-sink*`
   binding protocol, owned by seon.db. `cache` is the shared render cache,
   owned by seon.render. Neither is walk's to change. The three local atoms
   in `acquired-tree` are gone.
3. **The same pair is declared twice.** `:seon.eval/entity` and the receipt
   row both carry `seon.repl/render-ai`. Selection picks the owner (the
   receipt). The `:seon.eval/entity` copy can go once seon.eval.edn is
   granted, if nothing else selects it structurally.

### What changed

- **(1) `a33382621` render/ns.clj.** Members are one unlimited reverse pull,
  measured at 0.2 ms for an empty namespace and 1.8 ms for `seon.db`
  (230 rows) on default. The old query exceeded the read budget even for
  `my.agents.root`. The AI face shows the first max-collection names, the
  total, and `(dir ns)`; HTML shows all of them. The ns-test that asserted
  the refusal was replaced by a continuation test.
- **(2) `595bac7f6`.** The `:seon.cluster.eval/receipt` row declares the REPL
  pair. `walk/history` hands the row without the read's `:t` basis, so the
  owner and its pair are found. Probed with the edited projection: the
  prompt's text equals `seon.repl/render-ai`'s, and the HTML selects the
  `seon-eval-entry` article. The walk's pulled/visited/evidence state is a
  threaded value; its output equals the atom version's for root (root map
  and 57 evidence entries).
- **(3) + (5) `0f54105f5` render.clj.** An identity owner's pair claims only
  a root value (empty path). Nested values take only their reaching
  attribute's component pair: `:seon.agent/runtime` → `:seon.runtime/entity`
  still renders, while `:seon.agent/namespace` has none and keeps its pulled
  shape. `:seon.print/rendered-token-ceiling` is now a config dial
  (config/default.edn 50000). `agent-render-profile` carries it when
  effective config has it, and `fit` prefers the profile's value.
- **mcp-test `225b23639`**, classified by the provenance ruling:
  - `nested-bulk` and `sci-top-level-strings` were retired assumptions: an
    8,192-byte window that predates the one budget. Measured: 25,130
    characters, about 8,400 tokens, under the 15,000-token profile. They now
    assert the profile bound.
  - `ordinary-mcp-results` relied on budget pressure that the default budget
    no longer creates: at 15,000 tokens, 32 whole rows fit. At a 1,000-token
    budget the nested rows are cut with paths `[0]`…`[3]` plus `[]`, and the
    36-character strings stay readable (probed on default).

### Root runs

```
bin/test-check default --ns seon.render.ns-test
bin/test-check default --ns seon.render.transcript-test
bin/test-check default --ns seon.render.value-test
bin/test-check default --ns seon.render.walk-test
bin/test-check default --ns seon.render.walk-graph-test
bin/test-check default --ns seon.cluster.mcp-test
bin/test-check default --ns seon.render-test
bin/test-check default --ns seon.config-test
```

Released: src/seon/render/ns.clj, test/seon/render/ns_test.clj,
resources/seon/schemas/seon.cluster.eval.edn, src/seon/render/walk.clj,
src/seon/render.clj, src/seon/print.cljc,
resources/seon/schemas/seon.print.edn, config/default.edn,
test/seon/cluster/mcp_test.clj, this note.

## Round 4: `47cfeacfa`, `eeb335064`

- `47cfeacfa`: `seon.sci.eval/directory-value` now returns `:seon.repl/directory`.
  On default, 450 of 461 namespaces return a valid directory. The other 11
  throw `Invalid token: ::…` on arglists: that is the existing issue
  `documentation-arglists-with-auto-keywords-are-not-edn`.
- `eeb335064`: deleted `:seon.eval/entity`'s duplicate REPL pair.
  **`test/seon/render_coverage_test.clj:229-232` still asserts it**, and the
  file is not this lane's. It must name `:seon.cluster.eval/receipt`.
- Row 2(i) has not started. Its file list and prerequisites are in the reply
  to root. Nothing prerequisite exists yet: `seon.render/units` (building
  block S3) is not in the tree.

## Round 5: row 2(i), the selection activation (`65bf078ec`, `7eb1219f3`, `5112657ad`, `bc1021402`, `260fed262`)

### Open first

1. **Three reds sit in tests outside the ledger.** They assert the retired EDN
   elision face in AI text:
   - `test/seon/instrument_test.clj:718-721` (`:seon.print/bound-by …`,
     `:seon.print/omitted 37`, `:seon.render.data/total 40`);
   - `test/seon/test/runner_test.clj:51` and `:83`;
   - `test/seon/test_runner_test.clj:427`.

   Each needs the one-face assertion (`…N` plus the legend). Their files need
   a grant.
2. **Default boot state.** Default PID 2530 sits with missing layers
   (`seon.sci.eval/ctx`, flow, web), and MCP JVM projection of any return
   value fails with "The MCP config read has no cluster projection state". The
   probes below printed to `*out*` for that reason. `65bf078ec` made the
   rendered-token ceiling an optional dial, as root asked.
3. **Not yet done in 2(i):**
   - the MCP conversion (`cluster.clj` is held for the pipeline lane): shared AI
     text, the stored selection tree, and `get_value` over
     `seon.render.walk/continue`;
   - with it, deleting `window`, `display-value`, `pager`, `stable-entries`,
     `counted-size` and the print-node artifact functions.

   The `floor`/`bounded-text` bodies are kept: they print first-party frames
   for panics and test failures, and the design's "no frames" rule applies to
   the generic floor. Replacing them needs an owner ruling on panic output.
4. **Nested renderer prose** still prints as an escaped string inside a raw
   value (round 2, item 6).
5. **Allocation:**
   - production bounds, forms map at 15,000 tokens: 22.8 MB → 10.1 MB (−55%);
   - uncapped 45,000-character selection: about 13 KB per admitted node (78 MB
     for the forms map, 67 MB for 5,121 nodes of a 100,000-member vector).
     That is the cost of the persistent node map and sorted queue that give
     rollback by value. Cutting it needs a transient commit after a
     charge-only trial, which is another design step.
6. **Armed cost has not been measured.** Default runs a committed snapshot
   without these commits. The battery (`tmp/impl-floor-on-fipp/battery.clj`)
   reruns armed after adoption.

### What changed

- **`7eb1219f3`** (+594/−631 source):
  - the selection (classification table, skeletons, shell queue, exact
    charge) replaces `value-node`/`value-node*`, `ordered-head`,
    `attribute-value`/`reference-identity` and `print/fit` with its family;
  - the one cut face and one legend;
  - held order;
  - scalars print their classified text;
  - `mcp_test:80`'s redefinition becomes an assertion.

  Tests now read cuts from the tree. Retired: reshaped plan/component/ref
  faces, realizing prints, the canonical re-sort, and dir as a floor cut (it is
  its declared renderer's text now).
- **`5112657ad`** (render.clj +47/−132): one `declared-projection` question
  per reached map replaces the print-node projection walk (`project-node*`,
  `project-entry`, `project-children`, `bounded-error-node`).
- **`bc1021402`**: `shown-result` reads `:seon.instrument/check` only through
  `keyword-entry`: an exact small persistent map, keys compared by identity.
- **`260fed262`**: `seon.render.walk/continue` (node designator, path,
  absolute offset). It returns a `:gone` continuation error for an evicted
  result. `prepare` honours the cursor's offset and roots cut paths at the
  cursor's path.

### Measured: the 28 design cases, before (default's installed prepare, armed) and after (the working tree, unarmed scratch)

| value | tokens | chars before → after | ms before → after | MB before → after |
|---|---|---|---|---|
| three-branch | 128 | 577 → 385 | 3.7 → 1.7 | 3.6 → 0.6 |
| agent row | 150 | 405 → 477 | 10.1 → 2.5 | 15.8 → 1.2 |
| agent row | 600 | 405 → 1,911 | 23.0 → 3.2 | 32.3 → 4.3 |
| agent row | 15,000 | 12,417 → 12,227 | 22.5 → 15.7 | 58.7 → 25.5 |
| forms | 15,000 | 4,465 → 6,010 | 6.6 → 5.9 | 22.8 → 10.1 |
| error row | 600 | 525 → 1,910 | 2.2 → 1.1 | 5.8 → 2.0 |
| test run | 15,000 | 15,967 → 15,962 | 11.7 → 10.8 | 47.0 → 21.7 |
| Throwable | 15,000 | 2,230 → 307 | 3.0 → 0.3 | 11.6 → 0.7 |
| `{:a (vec (range 100000)) …}` | 150 | 401 → 240 | 0.6 → 0.5 | 2.4 → 0.9 |

The other 19 rows are in the session log; every "after" is at or under its
budget. At 128 tokens, before, only one of the three branches showed its text
(577 characters, over the 384-character budget). After, all three show a
prefix within the budget. The Throwable is now type, message and data per
link; frames are no longer acquired by the generic floor. The agent row at 150
tokens, after:

```
{:db/id 33229,
 :seon.agent/branch :cluster-default,
 :seon.agent/id "root",
 :seon.agent/namespace {:db/id 33223},
 :seon.agent/plan {:db/id 33230, :my.plan/agent {:db/id 33229}},
 :seon.agent/runtime
   {:db/id 33232,
    :seon.runtime/agent {:db/id 33229},
    :seon.runtime/trigger {:db/id 33754},
    :seon.runtime/turns [{…8} {…11} {…9} {…9} {…9} …30]},
 …1}
;; …N = N more, …+N = N more characters, …? = not yet realized. Not retrievable: the value has no result handle.
```

### Root runs

```
bin/test-check default --ns seon.print-test
bin/test-check default --ns seon.render.value-test
bin/test-check default --ns seon.render.transcript-test
bin/test-check default --ns seon.render.web-test
bin/test-check default --ns seon.problems-test
bin/test-check default --ns seon.loop-proof-test
bin/test-check default --ns seon.mcp-test
bin/test-check default --ns seon.render-coverage-test
bin/test-check default --ns seon.render-test
bin/test-check default --ns seon.sci.eval-test
```

## Round 6: `9bc140eb5`, `d97994b43`, `97f4d1f90`

- **`9bc140eb5`**:
  - `floor`/`bounded-text` keep Seon frames on panics and test failures
    (root ruling), stated in their docstrings.
  - Three foreign tests (`instrument_test.clj`, `test/runner_test.clj`,
    `test_runner_test.clj`) now assert the one cut face.
- **`d97994b43` (my defect, fixed)**: 260fed262 declared
  `:seon.render.walk/node` a second time, and clojure.edn refused
  `seon.render.walk.edn`. The designator is now
  `:seon.render.walk/continuation-node`. The class regression
  `every-packaged-schema-resource-reads-as-edn` is in
  `seon.schema.edn-test`, and every file under `seon/schemas` reads today.
- **`97f4d1f90`**:
  - MCP hands the floor the held value; admission only writes the stored
    artifact.
  - `get_value` returns the floor's page at the path and absolute offset:
    text, total and next-offset.
  - Deleted: `window`, `display-value`, `pager`, `stable-entries`,
    `counted-size` and the MCP string window (source +48/−122).

### Open

- **`cluster/mcp_test` `retrievable-artifacts-commit-no-transaction`** stays
  red. Its fixture hands `running-instances` a hand-built map that
  `seon.cluster.boot/selected-instance`'s `:seon.boot/instance` contract
  refuses. It needs a canonical instance constructor, which test-support and
  boot own, not a patch in the test.
- **Dead declarations** remain in `seon.render.value.edn`:
  `:window-failed`, `:window-failed-error`, `:window-offset` and
  `:window-realization-failed-error`, with no producer since `window` went.
  They are `:seon.db/attributes` schemas, so their removal is the schema
  owner's call.
- **2(i) items not done:**
  - the MCP stored artifact is still the admission print node; the design's
    stored selection tree is not built;
  - `render.data/at` still navigates raw key paths (no typed steps);
  - the page expanders are row 2(iii).
- **Measurement is pending.** Armed cost and adoption proof wait for root's
  next adoption, measured with `tmp/impl-floor-on-fipp/battery.clj`.

### Root runs

```
bin/test-check default --ns seon.schema.edn-test
bin/test-check default --ns seon.cluster.mcp-test
bin/test-check default --ns seon.render.value-test
bin/test-check default --ns seon.instrument-test
bin/test-check default --ns seon.test.runner-test
bin/test-check default --ns seon.test-runner-test
```

## Round 7 (lane impl-floor-fipp-opus-2, resuming after the usage limit): `0c748bcd5`, `272a02eab`

### Broken or open first

1. **Two parked edits were discarded, not committed.** The copy is at
   `tmp/orchestrator/parked/20260926-0808-floor-fipp/floor-fipp.patch`.
   - `sci/admit.clj` `readable-name?`: this refused a keyword or symbol that
     does not read back, and stored a `::print/failed` leaf in its place. It
     checked with `clojure.edn`, but the artifact reader is `seon.sci.reader`.
     It also added a per-keyword character scan on the admission hot path, and
     it lost the value. The unreadable name is a producer defect
     (`fixture-branch-keywords-are-not-readable-edn`: the fixture names a
     branch `:…fixture/1`). `read-artifact` already refuses such content by
     name (`unreadable-artifact-error`).
   - `test/runner.clj` failure location: this rewrote `:file`/`:line` from
     the first JVM frame in the test's own file basename. An agent's SCI
     deftest has no JVM frames in its file. There the edit only deletes the
     location, and the `AFn.java:154` report (dogfood cycle 2) remains. The
     real location for an SCI test is in SCI's own stack. Owner: the
     SCI-test reporting path. The runner heuristic would be a patch at the
     wrong seam.
2. **Help still renders prose, not REPL entries.** `0c748bcd5` takes option 1
   of `help-shows-its-map-because-a-macro-call-names-no-producer`: the value
   carries its own pair. `render-help-ai` still returns bare lines. Whether
   those become `;;` entries is part of the REPL-entry render design, as that
   issue records.
3. **Not done here, because the remaining 2(i) and §4 rows need files outside
   the ledger row:**
   - `print/value-at` → `render.data/at` needs `db.clj:3067/3080`;
   - the stored selection tree needs `cluster.clj`;
   - site 12, the transcript history page, needs `:seon.config.render.agent/max-children`
     removed from config (row 10);
   - the four dead `window-*` declarations in `seon.render.value.edn` are
     installed `:seon.db/attributes` schemas. Deleting them is a live-store
     schema change that needs a branch proof, and
     `test/schema/datahike_parity.edn` (not mine) records them.
4. **Nothing was run.** Only static checks were run: clj-kondo, and
   `seon.contracts-compile-test/check` over the five touched sources, which
   gave `[]` in 502 ms at the REPL. The new classify row was proved only on
   SCI's own fields in a scratch `sci/init` context (below). It has not been
   proved armed through `prepare`.

### What changed

- **`0c748bcd5`** (value.clj +19/−3, bootstrap.clj +7/−1, value-test +10):
  - An agent's record prints `#user.Card{:rank 1, :suit :s}`. A SciRecord's
    final `ext-map` is classified as the exact map it is, and faced
    `::print/record` with its `rec-name`. No method is called. The earlier
    duplicate of the map row was collapsed into a recursive `classify`.
  - A `sci.lang.Type` prints `user.Card`.
  - This fixes dogfood #19, where records showed `#object[SciRecord]`.
  - The `help-value` map carries `:seon.render/ai`/`:html`.
  - The unused `clojure.edn` require is deleted.
- **`272a02eab`** (+35/−116 source, runner-test +12). Three presentation cuts
  outside the floor go (§4 sites 5, 14 and 19):
  - site 5: the walk's `min width query-width` and its two-bound
    observation (−14 net);
  - site 14: web `generic-entity`'s dead reverse-ref branch with its `take
    width` and hand-built elision, the unused `reverse-attribute` and the
    unused `Date` import (−54 net);
  - site 19: runner drift's take-10 string cut and `-count` keys, so the whole
    native sets now travel (−13 net).

Net for the round: source +61/−120 (−59). Tests: +22.

### Evidence

Scratch probe on default PID 95668, JVM mode, read-only: `(sci.core/init {})`,
then `(defrecord Card [rank suit])`. It read back `:rec-name user.Card`
(a Symbol), `:ext {:rank 1, :suit :s}` (a PersistentArrayMap),
`:type-name user.Card`, and `(pr-str rec)` = `"#user.Card{:rank 1, :suit :s}"`,
in 9 ms. The regression asserts that the floor's text equals `pr-str`.

### Root runs

```
bin/test-check default --ns seon.render.value-test
bin/test-check default --test seon.render.value-test/agent-records-and-types-print-as-clojure-prints-them
bin/test-check default --ns seon.render.walk-test
bin/test-check default --ns seon.render.web-test
bin/test-check default --ns seon.test.runner-test
bin/test-check default --ns seon.test-reaching-test
bin/test-check default --ns seon.loop-proof-test
bin/test-check default --test seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection
```

Adopt: `src/seon/render/value.clj`, `src/seon/bootstrap.clj`,
`src/seon/render/walk.clj`, `src/seon/render/web.clj`, `src/seon/test/runner.clj`.

Released: the `sci/admit.clj` edit was discarded, and the file is unchanged at HEAD.
