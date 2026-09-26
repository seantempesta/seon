---
type: research
status: complete (exploration only; no production source, schema, test or doc edited)
created: 2026-09-26
lane: docs-explore-opus
scope: empirical comparison of doc formats (Markdown docstrings, prose + metadata keys, per-namespace pages, cross-cutting pages) on three namespaces under active work and the vocabulary page — parse, index, surfacing in five agent situations, mining, recommendation
aligned-with: docs-in-code-design-2026-09-26.md (rev 3, fafa83de6) and its review; open-items.md ruling (2) "docs stay docs, linked by data" (21d6ac127); language-requirements §1.4, §1.5, §5a, §7
evidence: tmp/docs-explore-opus/ (git-ignored; drafts, parser, probes, outputs listed in the appendix)
---

# Docs exploration: which format links docs to the code best

**What exists.** Docstrings are flat prose on program rows (`:seon.fn/doc`, 0 headings in 3,064 docs, design §0).
The render walk already follows `:seon.fn/see-also` / `:seon.fn/must-read` value edges (`walk.clj:912`), and
function context shows callers and callees as addresses. Cross-cutting docs (`vocabulary.md`) cite `file:line`.

**The approach tested.** Write the knowledge the code cannot say (edge cases, measured costs, recommendations,
errors, known issues) for 13 holders in three namespaces plus five vocabulary sections, in three formats that carry
the same content. Parse each with the libraries already in use (clj-kondo analysis, nextjournal/markdown with its
`internal-link-tokenizer`, edamame), index into a `d/with` value of the live database, then build the context five
agent situations would get with and without the doc links.

## 0. Broken things first

1. **Default was down for this lane's first half.** pid 15369 (booted 18:39Z) never produced `config-result`,
   `sci.eval/ctx` or the Flow graph (log: a write-validator "Map literal contains duplicate keys" at 18:40:17Z,
   then a rejected transaction report). Every MCP `eval_clj` answered only `seon.cluster/mcp-effective` "The MCP
   config read has no cluster projection state", even for `(+ 1 2)`. Probes P1–P4 therefore went through the raw
   io-prepl (`tmp/docs-explore-opus/prepl.sh`). Root replaced default (pid 21381, 18:56Z); P5–P9 used MCP.
2. **My probe P4 left a spinning thread in pid 15369 for about seven minutes.** `seon.fault/carried-receipt` on a
   two-link cyclic cause chain never returns, and `future-cancel` cannot stop it (`iterate`/`some` never checks
   interruption). pid 15369 went to 99% CPU until root's replacement ended it. Probes of possibly non-terminating
   host calls belong in SCI (interruptible), not JVM mode. This is also defect 3.
3. **New defect: `seon.fault/carried-receipt` hangs on a cyclic cause chain** (`src/seon/fault.clj:190-197`,
   `(some … (take-while some? (iterate ex-cause failure)))`, no identity check, no bound). `(.initCause a b)` with
   `b` caused by `a` is legal JDK. `seon.render.value/floor` walks the same chain safely (`causes`,
   `value.clj:96-103`, identity-checked and bounded by `:links`). Found by writing its Edge cases section. No issue
   exists (`rg carried-receipt docs/seon/issues` is empty).
4. **New defect: `my.program/breaks` takes 7.8 s on a hub** (`seon.db/q`, 786 callers; armed, pid 21381). A leaf
   takes 170 ms. The profiler's explanation: `proposed-plan` → `seon.fn/gate-sets` → `gate-set-in` ×1,590 →
   `seon.db/datoms` ×2,034,948 and `seon.db/with-declarations` ×4,088,626 (P9). `ns-unmap!` and
   `native-call-refusal` call it, so an ordinary `intern` of a hub program symbol inside SCI costs seconds.
5. **New defect: `my.program/native-call-refusal` advises a function that does not exist.** For `intern` and
   `alter-var-root` it returns `:seon.program/advised-operation my.program/define!` (`src/my/program.clj:791-792`).
   No `define!` exists in `src/`. This is the same class as dogfood #16 ("breaks plans name a launch function that
   does not exist"): hand-written advice never resolved at the producer.
6. **Armed cost differs from unarmed by up to 108x.** `seon.render.value/bounded-text` of `(vec (range 100000))`
   takes 4.4 µs unarmed and 474 µs armed: the input contract validates the whole value before the bounded walk. Its
   docstring says the walk is bounded; the armed call is O(value). A documented cost must say armed or unarmed.
7. **clj-kondo drops a `defn` attribute map written as a namespaced map literal.** `(defn f "d" #:seon.doc{:x [1]} [] nil)`
   exports `:meta nil`; `{:seon.doc/x [1]}` exports it. Reproduced with system clj-kondo v2026.07.24 and with Seon's
   fork loaded in default (`clj-kondo.core/run!`, P5). Any `#:seon{:tasks …}` attribute map would silently lose
   `:seon/tasks` (7.2). I did not check whether current source uses that spelling.
8. **Every `my.program` line citation in `vocabulary.md` is stale:** 5 of 5 (`:238`, `:129`, `:144`, `:183`, `:311`;
   the definitions are now at 281, 152, 167, 219, 360). The 34 `[[symbol]]` links written for this exploration
   all resolve.
9. Observed, not investigated: the armed JVM holds two host profile cells per symbol for the functions I sampled
   (`breaks`, `callers`, `fault!`, `record!`, `floor`, `bounded-text`).

## 1. What was built

| Part | Content |
|---|---|
| Subjects | `seon.fault` (ns, `record!`, `carried-receipt`, `fault!`), `my.program` (ns, `callers`, `breaks`, `ns-unmap!`, `native-call-refusal`), `seon.render.value` (ns, `bounded-text`, `floor`, `keyword-lookup?`), and five sections of `vocabulary.md` rewritten as a page with `[[symbol]]` links |
| Content rule | Only what the contract, callers list and profile cannot say. Every cost was measured (P2 unarmed, P9 armed). Every edge case was either read from the body or probed (cyclic chain, `HashMap`, symbol-keyed `sorted-map`, 200-deep map) |
| (a) Markdown docstring | Lead, then `## Edge cases`, `## Costs` (prose plus an `edn` cost map), `## Recommendations`, `## Errors`, `## Known issues`, `## Examples`, `## See also`; links are `[[sym]]` |
| (b) Prose + metadata | A lead-only docstring plus `:seon.doc/edge-cases`, `:seon.doc/costs`, … and `:seon/see-also` in the attribute map, with links as quoted symbols (1.5) |
| (c) Page per namespace | `formats/c/<ns>.md`: `## [[ns/fn]]` switches the holder, `### Edge cases` etc. below it. Docstrings stay as they are today |
| Page | `formats/pages/vocabulary.md`: headings are free (`:other` sections), links are `[[qualified/sym]]` |

The (b) and (c) files were generated from the parsed (a) structure (`gen_bc.clj`), so all three carry the same
facts. Authoring ergonomics were judged by reading them, not by writing each by hand.

## 2. Parse and index

| Measure | (a) Markdown docstring | (b) metadata | (c) ns page | vocabulary page |
|---|---|---|---|---|
| Authored characters | 10,329 in docstrings | 1,245 docstring + 5,879 metadata | 8,639 in pages + today's docstrings | 2,024 |
| clj-kondo analysis (3 files, bb process) | 25–59 ms | 25–57 ms | — | — |
| Markdown parse, all holders (bb) | 3.1–7.9 ms | 0.3–0.8 ms (leads only) | 2.1–5.3 ms | 0.5–1.7 ms |
| Holders / items | 13 / 78 | 13 / 56 (**3 holders lost**, item 0.7) | 13 / 63 (no lead items) | 1 / 10 |
| `d/with` datoms (scratch schema) | 380 | 271 | 311 | 60 |
| `d/with` time | 4.7 ms | 3.0 ms | 3.2 ms | 0.64 ms |
| Link refusals | 0 malformed; 1 unregistered key | same | same | 0 |

- **One parser serves all of them.** The same `sections` function (lead, heading enum, `:other`, level-2 `[[link]]`
  heading switches the holder) parsed (a), (c) and the page.
- **D7 dedent is required.** clj-kondo returns docstrings with their two-space continuation indent.
- **Two parser pitfalls came up.** Without `:softbreak` handling, words from adjacent lines join. Without
  `{:all true}`, edamame reads `'seon.turn/open?` in an example as one symbol named `'seon.turn/open?` (the 1.6
  hazard).
- **Resolution.** All 34 symbol links resolve against the live db (P6, 1.3 ms). The one refusal was mine:
  `[[:seon.error/transaction-failure]]` is not a registered key, and D3 caught it.
- **Size.** About 29 datoms per enriched holder. At 1,500 public functions that would be about 44k, still below
  `:seon.fn/calls`. The design's "a few thousand" holds only for today's flat docs.
- **Query.** A backlink query ("which doc items link `seon.problems/open-faults`") answers in 1.4 ms on the
  `d/with` value.

## 3. Surfacing: five agent situations

Without: today's `(doc f)` text plus `;; calls / callers / tests` addresses. **Rule A:** situation-selected sections
plus items from holders that no call, same-namespace or require edge already reaches. **Rule B:** the same, plus
every item anywhere that links the subject (item text only; the holder is never expanded). Tokens are chars/4, on
simulated contexts built per design §3.4, not the installed `function-ai`.

| Situation | Subject | Without | Rule A | Rule B | Relevant and not linked by any require/call? |
|---|---|---:|---:|---:|---|
| S1 calling it for the first time | `my.program/breaks` | 216 | 369 | 531 | The **Costs** item (7.8 s on a hub, use `callers` first) is the most valuable line. It is the subject's own content, not a link. The page section on caller/reference/reach is relevant background |
| S2 receiving its refusal | `my.program/ns-unmap!` | 104 | 252 | 332 | The **Known issues** item (a refused run poisons the context) plus, under rule B only, the ns-level recommendation "breaks → repair callers → ns-unmap!". That item is same-namespace, so rule A hides the one line that tells the agent what to do |
| S3 editing it | `seon.fault/carried-receipt` | 63 | 260 | 294 | **Yes, the best case.** `floor`'s edge case links it, from a namespace with no call or require edge, and names the safe algorithm to copy (`causes`) |
| S4 working a task that names it | `seon.fault/fault!` | 382 | 528 | 603 | **Yes.** `seon.problems/open-faults` and `resolve!` (no require either direction) and `seon.error.refusal/diagnostic`, via `fault!`'s own See also, the ns recommendations and the page |
| S5 "how do `fault!` and `open-faults` relate?" | both | 499 | 613 | — | **Yes.** No call path or require exists. The page's one sentence ("recorded by fault!, counted by signature, open until open-faults says otherwise") answers the question outright |

- **Links that add relevance.**
  - Of the 34 docstring links: 11 point into the same namespace, 8 duplicate a call edge, 6 duplicate a require
    edge, and **9 reach something no edge links** (2 of those are page references and 1 is an example's argument).
  - All 13 page links cross namespaces that do not require each other.
  - **Pages and cross-namespace edge cases carry almost all of the new relevance.** Docstring See-also lists mostly
    restate the call graph.
- **Item-level beats holder-level.**
  - Surfacing the linking *item* costs 50–230 tokens and was relevant each time.
  - Expanding the linked *holder* would cost its whole doc.
  - "Already linked by an edge" is the wrong filter for items (S2): an item's link says what the item is about,
    which a call edge does not.
- **Repetition.**
  - No item pair across link ends shares more than 20% of its words.
  - Reading found one semantic duplicate class: "inherits `breaks`' cost" is written on `ns-unmap!`,
    `native-call-refusal` and `breaks`. The call graph plus the callee's cost already say it, so it should be
    derived, not authored.
  - Some (a) Edge cases for `record!` retell the body's branch order. They explain why (the copy outlives branch
    retirement), which is the allowed kind, but they are close to the line.

## 4. Mining test

| Miner (design §3.3) | Input today | Findings | Useful? |
|---|---|---|---|
| M1 known issues without a task | 4 Known-issue items | 4 | **3 useful and new** (defects 3, 4, 5). 1 duplicate: the `ns-unmap!` item cites dogfood numbers, not the two existing issues, so M1 would re-file them. Known-issue items need an issue/task link, and M1 must dedupe on it |
| M2 recurring faults | 1 signature, 1 occurrence (store reset; P3, 16 ms) | 0 | Starved: error history does not survive a reset |
| M3 repeated misuse | 0 retained declared agent errors | 0 | Starved. The dogfood report lists at least 3 misuse classes around `my.program` (#15, #16, #21), all gone from the store |
| M4 contradictions | structure + contracts | 1 real (unregistered key) + 1 false | The `fault!` Errors item describes a **thrown** `ex-info`. No error schema exists for it, so D4 `:unlinked-doc-error` would refuse a correct doc: a grammar gap for thrown errors |
| M5 metadata | — | 0 in scope | The class that did appear (advice naming a non-existent function, defect 5) is a **producer** check on refusal values, not a doc miner |
| M7 cost drift / over one second | `breaks` Costs vs live profile | 1 | Correct, but the over-one-second half duplicates `seon.profile/top`'s `:over-second` and its explanation. Only doc-vs-live drift is new, and comparing an unarmed doc number with the armed profile fires falsely (defect 6) |

**Verdict.** The findings came from *writing* the Edge cases and Costs sections against measurements: three new
defects in three namespaces. M1 then turns them into tasks. Error-based miners produce nothing useful until error
history is durable (ruled "not now").

## 5. What works and what does not

**Works:**
- one Markdown parser and one section splitter for docstrings and pages;
- `[[symbol]]` links that resolve (34/34) where `file:line` citations rot (5/5 stale);
- backlink queries in milliseconds;
- item-level surfacing that brings in sibling knowledge the call graph cannot (S3, S4, S5);
- the page as the home of "how do X and Y relate".

**Does not work, or costs more than it gives:**
- EDN cost maps: each is about 150 characters of noise in the source, and their only consumer (M7) mostly
  duplicates the profiler;
- See-also lists that repeat calls;
- authored "inherits X's cost" lines;
- (b)'s metadata: 5,879 characters of metadata for 1,245 of docstring. That is the "metadata larger than the
  function" the owner warned about, and the clj-kondo namespaced-map drop loses it silently;
- error-driven miners on a store that resets;
- the rule "hide items already linked by an edge".

## 6. Recommendation and alternatives

**Recommended: (a), slimmed, plus pages.**
- **Docstrings:**
  - A lead whose first line is a sentence (`dir` shows it; the repo hook already enforces this).
  - Optional headings: `Edge cases`, `Costs`, `Errors`, `Known issues`, `Recommendations`, `Examples`.
  - `Costs` are prose items carrying the number, the case, and whether it was armed. The live profile is shown
    beside them, so no EDN cost map is needed.
  - `Known issues` items must link an issue or task.
  - `[[links]]` may appear in any item. A separate `See also` section is kept only for links that no edge makes.
- **Pages:** cross-cutting Markdown under one parser, holding relations and vocabulary, with `[[qualified/sym]]`
  links in place of `file:line`.
- **Surfacing:** rule B, item text only.
- **Trade-off:** docstrings grow roughly 3x on enriched functions (`breaks` 490 → about 1,500 chars). The
  knowledge sits where grep and `(doc f)` find it.

**Alternative 1: (c), per-namespace pages.**
- For: code files are untouched; readable on GitHub; no dedent; the same parser and datoms.
- Against:
  - the knowledge sits away from the `defn` (grep of the definition misses the edge cases);
  - editing needs page write-back (shell-only today, 7.14);
  - a renamed function strands its section until the link refuses.
- Best if the owner wants code files to stay terse.

**Alternative 2: (b), prose plus metadata keys.**
- For: data without Markdown; links are quoted symbols (1.5 as written); no prose parsing at all (1.4 strictly).
- Against:
  - it is the heaviest source (4.7x the docstring);
  - pprinted maps read badly;
  - clj-kondo drops `#:ns{}` attribute maps silently.
- Worth it only if 1.4 "prose is never parsed" is read to forbid Markdown structure.

## 7. Smallest conversion slice

1. **Producer.**
   - The existing docstring producer (`var-row`, `namespace-row`) runs D7 dedent, then parse, then the section
     split.
   - It stores `:seon.doc/sections` → items → `:seon.doc.item/links` (symbols) and `/keys`.
   - No cost maps and no miners.
   - About +90 lines, the design's S1 minus cost maps.
2. **Surfacing.** `function-ai` renders the sections by situation, plus the items linking the subject (rule B).
   The error render shows the holder's `Known issues` and `Edge cases` beside a refusal. About +50.
3. **Page holder** for `vocabulary.md` only, with its `file:line` citations converted to `[[sym]]` (about +60, the
   design's B, minus reconciliation extras until a second page exists).
4. **Pilot content.** Hand-convert the three namespaces here from `tmp/docs-explore-opus/formats/a` (correcting
   `bounded-text`'s armed cost first), through their owners' ordinary `defn` path.
5. **File the three new defects** (3, 4, 5) as tasks. They are the first M1 outputs, created by hand until
   `seon.task/create!` lands.

About +200 lines in total, against the design's +250 first slice. Deletions: none until the Markdown issue reader
retires, so the slice needs the owner's justification per the growth law.

## 8. Questions for the owner

1. **Costs:** prose items with numbers, shown beside the live profile (recommended), or typed EDN cost maps for
   M7 (queryable, heavier, mostly duplicates the profiler)?
2. **Surfacing:** every item that links the subject (rule B, +50–230 tokens per situation, recommended), or only
   items from holders no edge reaches (rule A, which hid the key line in S2)?
3. **Known issues:** must every item link an issue or task handle, so that writing one is how an agent files a
   defect and M1 never duplicates?
4. **Thrown errors:** should an `Errors` item describing a deliberate throw (`fault!` when storage fails) link the
   thrown data's key, or should D4 accept an unlinked throw?
5. **Where knowledge lives:** docstrings with headings (a, recommended), or code stays terse and knowledge lives in
   per-namespace pages (c)?

## Appendix: evidence, probes and limits

Files are under `tmp/docs-explore-opus/`:
- `formats/{a,b,c,pages}/` hold the drafts;
- `parse.clj` is the parser (bb; nextjournal.markdown, edamame, clj-kondo);
- `gen_bc.clj` generates (b) and (c) from (a);
- `surface.clj` builds the situation contexts;
- outputs: `structure.edn`, `parse.out`, `surfacing.edn`, `surfacing-rule-b.txt`, `surface.out`,
  `context-inputs.edn`, `probes/*.clj` and `probes/*.out`.

| Probe | Where | What | Time |
|---|---|---|---|
| P1 | pid 15369 prepl | profile cells for the three namespaces: none observed (boot never armed) | 0 ms |
| P2 | pid 15369 prepl, unarmed | costs: `callers` hub 75.8 ms, leaf 0.66 ms; `breaks` leaf 157 ms, hub 7.20 s; `tests-reaching` 1.9 ms; `reads-key` 0.53 ms; `bounded-text` 4.4 µs (100k vector) / 16.7 µs (200-deep map); `floor` 783 µs (13 links, 714 chars); `keyword-lookup?` 45 ns; `carried-receipt` 3.2 µs | 23.7 s (the `breaks` hub dominates) |
| P3 | pid 15369 prepl | error store: 1 signature, 1 occurrence, 0 multi-agent pairs | 16 ms |
| P4 | pid 15369 prepl | cyclic cause chain: `carried-receipt` timed out at 500 ms and kept spinning; `floor` 73 chars | 506 ms (plus the leaked thread, §0.2) |
| P5 | pid 21381 MCP JVM | Seon's clj-kondo fork drops `#:ns{}` attribute-map metadata | 34 ms |
| P6 | pid 21381 MCP JVM | link resolution and edge classification | 1.3 ms |
| P7 | pid 21381 MCP JVM | `d/with` indexing for each format; backlink query; over-1 s cost query | 42 ms |
| P8 | pid 21381 MCP JVM | docstrings, calls, callers and test counts for 12 subjects | 12 ms |
| P9 | pid 21381 MCP JVM, armed | costs armed: `callers` hub 71.2 ms; `breaks` leaf 170 ms, hub 7.77 s; `bounded-text` 474 µs; `floor` 977 µs; `keyword-lookup?` 0.22 µs; `carried-receipt` 4.1 µs; profiler over-one-second explanation | 16.7 s (over one second: the `breaks` hub probe itself, defect 4) |

**Limits:**
- The parse ran in babashka, not in Seon's producer.
- Contexts are simulated, not rendered by the installed `function-ai`.
- Tokens are estimated as chars/4.
- One author (this lane) wrote and judged the content.
- No tests were run, no MCP branch was created, and no scratch namespace was defined: each probe was a `let` form.
- **Net source/test lines: 0/0.**
- The committed file is this note. The MCP JVM sessions `docs-explore-opus` (lost at the restart) and
  `docs-explore-opus-2` hold no state beyond their prepl.
