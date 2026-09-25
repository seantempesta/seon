---
type: review
status: redesign before implementation
created: 2026-09-25
scope: Shell-order floor, safe rendering, and shared MCP/browser retrieval
---

# Floor, shell order, and the shared render entrance — independent review

2026-09-25. Review of Opus design `0b83d61a5`, principally §3 and folded §6 row 2. **Verdict: redesign before implementation.** Retain the common selected tree, fipp layout, saved AI text, and lazy HTML expansion; revise acquisition, classification, accounting, and continuation semantics first. No demonstrated P0 in this static review; the P1 findings below block the stated guarantees.

Today `value/prepare` builds a print node, fits it, and emits it; MCP additionally admits, serializes, reconstructs, and projects values, while the browser independently retrieves artifacts and renders them. The smaller composition is one custody-aware retrieval/render function feeding one safe selection and one node tree, followed by two terminal faces. A pretty-printing library belongs in the AI face, not in acquisition or selection.

## Owner ruling: ONE pipeline for MCP and the browser

The owner's ruling is the review criterion: “'printing' is rendering really … It should be the same happy path and pipeline so we build minimal infrastructure to support it.” Two terminal encodings are necessary; two admission, navigation, selection, or clipping systems are not.

### P1-1 — Sharing `prepare` does not yet give one pipeline

The design's diagram (`one-render-path-design-2026-09-25.md:128–144`) is a good terminal architecture. Its actual callers still bypass it. The folded row does not retire all of the following:

| Duplicate machinery and trace | Smallest composition / retirement |
| --- | --- |
| `cluster/mcp-project` admits raw input before the floor (`src/seon/cluster.clj:431–443`), makes an artifact/digest (`444–448`), calls `prepare` on the artifact's reconstructed semantic value (`467–470`), then reconstructs semantic data from the selected node (`481`). `mcp-valf` serializes that envelope again (`525`). | MCP consumes the terminal AI text of the shared render result, with retrieval coordinates as separate structured fields. Preserve the existing exact-shown-text bypass at `414–422`. Remove the semantic-value round trip and MCP's presentation threshold/window. Wire-format serialization of the envelope remains, but must not reinterpret the selected value or replace its pretty text. |
| `mcp-get-value` reads a blob and calls `data/at` (`cluster.clj:555–564`), then has its own string and collection windows (`575–594`). `/data` separately reads and reconstructs the artifact (`render/web.clj:3527–3529`), resolves the cursor (`3580–3581`), constructs a unit, and renders HTML (`3590–3620`). | One internal retrieval/render entrance accepts the source, custody, cursor, and profile. MCP and HTTP adapt requests and responses around that same function. They need not call one another over HTTP. Removing only MCP's window leaves duplicate acquisition and error behavior. |
| `print/value-at` (`src/seon/print.cljc:403–411`) and `render.data/at` (`src/seon/render/data.clj:46–89`) are separate navigators. The design proposes the new set ordinal in the former (`design:311–313`), but actual retrieval uses the latter. | Put structural navigation in the existing `render.data` owner, preserve its explicit missing-versus-nil result, convert or delete the other navigator. Define map-entry and set ordinals there once. |
| `value/prepare` still depends on output target while selecting (`render/value.clj:611–658`; `value-node*:502` gives HTML effectively unlimited limits). The debug experiment independently requests AI and HTML (`web.clj:1293–1296`). | Generic floor selection takes no face target. It returns one tree and its continuation information; both faces consume it. Additional browser pages are additional bounded selections, not an unlimited initial traversal. Source resolution and declaration lookup are shared; authorized explicit face overrides can choose different content without introducing a second floor implementation. |
| `repl/pretty-response` reads a string back and sends it through `clojure.pprint` (`src/seon/repl.clj:457–463`). Generic live HTML obtains prepared text and pretty-prints it again (`493–496`); the historical fallback does the same (`501`). `web/debug-value-html` uses raw `pr-str` (`795–798`). | Render the shared HTML result for live values and an escaped, unchanged saved-text block when that is all that remains. Delete read-back/reprinting and the raw debug printer. In particular, an appended legend is not safely round-trippable through `edn/read-string`, which reads one form. |
| Existing Sink/tee emitters, fit/re-emission, `window`, independent `floor`, and table emission overlap with the proposed selector and faces. | The folded retirement list mostly identifies these correctly (`design:664`). Keep old entry names only as thin calls into the same floor where boot/error callers require them; no independent fallback budget or printer body. |

**Fipp AI versus HTML is not itself a duplicate pipeline.** Layout and Hiccup are different terminal representations. Share admitted literal bytes, node kinds, paths, cut metadata, and traversal semantics; use fipp only for text layout and an HTML encoder only for markup. Do not replace Sink with a new generic backend/plugin framework merely to share a small case dispatch. The duplication is selecting or interpreting the underlying value again inside a face or transport.

Moving `mcp-project` behind the new classifier is also a safety requirement. `admit/Throwable->map` (`src/seon/sci/admit.clj:295–297`) and Java collection admission (`331–350`) run before the proposed safe floor today. A safe `prepare` cannot retroactively make that acquisition safe. A finite artifact is an admitted representation, not proof that producing it was harmless.

### P1-2 — Lazy expansion has two entrances and insufficient source identity

The design keeps both `get_value` and `/data` acquisition code (`design:420–485`). It therefore does **not yet** satisfy one lazy-expansion entrance. Put the operation in an existing render owner, with plain data roughly equivalent to:

`held source + explicit custody + typed path + absolute offset + profile → selected tree, AI text, HTML, continuations or typed refusal`.

The source alternatives already exist: a live evaluation object, an immutable artifact digest, or an entity query against an explicitly held database. Reuse them; do not introduce another result registry or cache. `repl/live-response` already obtains an agent context and finds an evaluation in `result-objects` (`repl.clj:465–480`); `bind-result!` is the producer (`src/seon/sci/eval.clj:583–600`). The web path already holds its DB (`web.clj:3517`), and `data/entity-continuation` already checks snapshot/eid/direction (`render/data.clj:124–133`). Compose those seams, not ambient default custody.

Both transports must receive the same missing/stale-source result, navigation semantics, offsets, and prepared result. A URL, digest, or DOM id does not confer custody. MCP currently supplies a floor unit without the web path's explicit database/context (`cluster.clj:467–470` versus `web.clj:3607–3608`); this cannot support an identical declared-renderer path by naming `prepare` alone.

### Infrastructure to drop

Drop semantic-value reconstruction merely to render an artifact, transport-specific windows, the second navigator, saved-text read-back, independent HTML selection, full canonical ordering where no semantic ordering is required, and the proposed new historical evaluation blob write unless its durability contract is explicitly chosen. Keep HTTP/SSE and MCP as thin adapters. Keep one identity owner and one source lookup owner. Do not add a parallel cursor registry, renderer registry, eager snapshotter, or replacement pretty-print engine.

## Evidence and limits

Read end to end: root `AGENTS.md` and its `CLAUDE.md` symlink; the complete design; `value-printer-prior-art-2026-09-25.md`; all three reviews in `render-floor-slice2-review-2026-09-25.md`; current `print.cljc`, `render/value.clj`, and `render/web.clj`. Also read the relevant caller/acquisition code and the dependency implementations below. Applied `.agents/skills/data-oriented-clojure/SKILL.md`; read its relevant authority, the context-generation PRD §13–15. The prior reviews' safety traces remain requirements, not evidence that the fixes are installed.

Design line references in this review are pinned to `0b83d61a5`. The design file initially matched that revision; before landing, another lane added the owner-authorized inline-face rules in §1 (net eight lines), shifting later working-tree lines. I read that diff; it does not change the reviewed §3 or folded row. Explicit face overrides are legitimate content choices, not justification for parallel generic acquisition/selection machinery. Observed repository HEAD during source inspection: `084b12d15bfe283db430045dff599edf009aa866`. Other lanes advanced the shared tree. For reproducibility, inspected source blobs were: `print.cljc` `5384cb8b468dfa1d923755933d877b7c4682dee9`; `render/value.clj` `79b8fd6e8b9f451284433e66cb8d4df70f1d2489`; `render/web.clj` `315fd01e963f01ad5ad5fdc51285304804b43496`; `cluster.clj` `4f45182be2522f4a980525ccf3a3576c9a58f0b9`; `render/data.clj` `be11191af766833d8b733fe7903a20eecac99668`.

Dependency pins inspected locally:

| Dependency | Revision | Relevant owner |
| --- | --- | --- |
| fipp, v0.6.29 | `255a19061fdc20d67eca09a17e9af282aa8bf909` | `reference-code/fipp/src/fipp/engine.cljc`, entire engine |
| Clerk | `4c2599946256592858ca2d65e8d453f9f77489ba` | `reference-code/clerk/src/nextjournal/clerk/viewer.cljc:1460–1870`; `webserver.clj:75–113` |
| Orchard | `da8b9de0e1323a4f4119052b662ae0acc4930c02` | `reference-code/orchard/src/orchard/inspect.clj:1–275` |
| Clojure, 1.12.5 | `3bc2b3e91fdff462620d84bbdfd40379d9c562e5` | `reference-code/clojure/src/jvm/clojure/lang/*` and core printing; this is the inspected pin, not an inferred runtime version |

This is static source review, not a runtime proof. Per assignment: no code changes, tests, JVM probes, `bin/seon`, or test launcher. No timings or allocation measurements are claimed. One read command's automatic check reported foreign `test/seon/fn_test.clj:506:79`, unmatched `)`; subsequent reads worked. That boundary did not block the review and was neither repaired nor used as an excuse to omit work. Unrelated hook/test/submodule/landing-file changes remain outside this file's ownership.

## Safety claims: verify/falsify against the earlier traces

### P1-3 — Classification must describe permitted operations, not a package name

The design improves the old walker by classifying before ordinary collection operations (`design:148–181`). But “a `clojure.lang` seq class” is not an inertness proof. Concrete source counterexamples:

- `Cons.more` returns the stored tail (`reference-code/clojure/src/jvm/clojure/lang/Cons.java:44–47`): this narrow claim is verified. Its `count` walks the tail (`50–51`), so do not infer safe counting from safe stepping.
- `ChunkedCons.more` calls `chunk.count` and `chunk.dropFirst` (`ChunkedCons.java:48–53`); `first` calls `chunk.nth` (`38–39`). Its constructor accepts `IChunk` (`28–31`). These are user-supplied protocol calls unless the chunk itself is classified. The design's “returns fields” assertion is false.
- A realized `LazySeq` caches an `ISeq`, but `first` delegates to that object's `first` and `more` delegates to its `more` (`LazySeq.java:115–133`). Only `seq` on the already-realized exact class returns the cached object (`102–105`; realization flag `287–288`). Classify that object before touching it. Checking `IPending` on the wrapper is insufficient.
- `StringSeq` holds arbitrary `CharSequence` (`StringSeq.java:22–30`); its element/count operations call `charAt`/`length` (`43–58`). `APersistentMap.KeySeq` can wrap arbitrary `ISeq`, and its `first` calls through that seq and `Map.Entry.getKey` (`APersistentMap.java:140–174`). Both defeat the package-name rule.
- `ASeq.more` calls `next` (`ASeq.java:138–141`). `Cycle.first` delegates to its current seq (`Cycle.java:53–59`). Neither “use more” nor “realized IPending” makes all their contents inert.

**Smallest fix:** an explicit, narrow operation table for exact trusted representations, including their held objects, with everything else opaque. Unwrap an already-realized exact `LazySeq` to its cached seq and reclassify it. Support known-safe cells incrementally; unsupported realized prefixes may remain opaque rather than calling them to discover whether they are safe. Do not add a timeout around arbitrary accessors on a shared thread.

Default `print-method` identity is also not proof that a record's `seq`/`count` is compiler-generated (`design:179–181`). The record marker is an interface, not proof of provenance for arbitrary implementations. Either establish admitted record provenance or leave unproved record operations opaque. Stock persistent containers still need safe handling of their elements and navigation; exact outer-class approval is not recursive approval.

### Required trace disposition

| Requirement from the three prior reviews | Current trace and verdict on the new design | Smallest fix |
| --- | --- | --- |
| Classification precedes effects, including attribute conversion | `value-node*` reads metadata/identity and obtains count before raw traversal (`render/value.clj:449–457`); `display-value` counts first (`357–366`). The design moves classification earlier, but its outer guard for `attribute-value` (`design:315`) does not prove nested operations safe: current conversion invokes member lookup, numeric comparison, whole-value `pr-str`, and set construction (`value.clj:388–423`). **Not closed.** | Keep generic floor selection structural. Run schema-specific reshaping only on producer-owned, validated representations whose member operations are safe. Removing a `pr-str` tie-break alone is insufficient; set construction can hash nested objects and sorted-map lookup can invoke a supplied comparator. |
| Lazy sequences show only already-realized values | Old `datum` uses `take`/`bounded-count` (`value.clj:47–63`); old collection traversal can advance seqs (`446–566`). New policy is right, implementation rule is false for the classes above. **Not closed.** | Apply the exact-operation table before every cell/held-object operation. Unknown total and an opaque/pending tail are valid results. Never promise a prefix exists merely because the wrapper is realized. |
| Cyclic Throwables terminate without realizing all frames | Current floor's `causes` already uses identity tracking plus a link cap (`value.clj:90–97`), but other entry paths use `Throwable->map` (`admit.clj:295–297`). Clojure's cause loop has no visited set (`core_print.clj:494–497`) and eagerly maps stack frames (`499–501`). New identity tracking fixes repeated-link traversal **conditionally**, but the proposed complete preprojection remains unbounded and calls virtual accessors. **Partly fixed, still unsafe.** | Remove pre-floor `Throwable->map`. Use identity, not equality/hash, for visited objects. Arbitrary Throwable subclasses stay opaque; render known inert error representations or already-stored flat error data. Where projection is permitted, advance it under the same selection work bound instead of materializing every cause/frame first. |
| Host `Counted` never runs | Current `counted-size` calls arbitrary `count` (`value.clj:306–309`), reached via `display-value` before the walk. The design explicitly deletes this and makes a host `Counted` opaque (`design:162–165`). **Correct direction; not installed.** | Keep `count` restricted to exact known constant-time representations. A catch cannot rescue a nonreturning implementation. Do not retain the old count in source lookup, schema reshaping, or transport admission. |
| Carried paths/requery metadata are checked before printing, hashing, sequencing | Existing requery handling accepts a seq form (`print.cljc:444–455`), and coordinates flow into cut printing (`457–489`, `982–1011`). Design C=256 bounded admission (`design:197–212`) is helpful but inherits the unsafe classifier and scalar encoder. `prepare` vectorizes the cursor before `node-id` (`value.clj:622–624`); URL creation prints raw path (`265–269`). **Not closed.** | Validate bounded wire input before EDN parsing and coordinate operations; validate internal carried objects through the corrected inert selector before `vec`, printing, hashing, or lookup. Derive requery from a typed source reference. “Finite EDN” does not authorize evaluating a carried list or establish custody. |
| `node-id` cannot trigger raw printing/hash/user code | Root identity is sought via `identity-address` (`value.clj:204–233`), then the missing-root diagnostic `pr-str`s raw fields (`251–256`), or `id/digest` prints `[agent-id root-address path]` (`259`; `src/seon/id.clj:29–38`). The proposed coordinate gate covers the goal but must precede identity lookup and refusal construction, including agent id. **Not closed.** | Require a validated typed root address at the entrance; validate every digest input. Produce diagnostics from safe admitted descriptions. Use the same validated structural coordinates for retrieval and DOM identity, including page/occurrence distinctions. |

The old review's `:NEW` line coordinates were diff-relative; the table above deliberately gives current source traces instead. Relevant prior requirements are in `render-floor-slice2-review-2026-09-25.md:23–95`, `98–154`, and the six concrete recheck traces at `177–189`; custody precedence remains at `193–199`.

### P1-4 — “Safe scalar, then `pr-str`” still runs user code or allocates before charging

The design admits exact scalar classes and prices the remaining scalar types as constant-size (`design:148–158`). Two counterexamples survive that rule:

1. A normal `Symbol` carrying `{:type :user/hostile}` metadata is still the exact Symbol class. Clojure's print dispatch examines metadata `:type` (`reference-code/clojure/src/clj/clojure/core.clj:3693–3695`). A method for that dispatch can execute arbitrary code. `*print-meta* false` controls metadata output, not this dispatch. Quoted-symbol length is not a safety proof for `pr-str`.
2. `Ratio.toString` prints its arbitrary-size numerator and denominator (`reference-code/clojure/src/jvm/clojure/lang/Ratio.java:23–43`). A ratio containing million-digit integers is not constant-size. Its public constructor also retains its component objects. Similarly, exact `BigInt` retains a `BigInteger` component (`BigInt.java:23`, `61–65`); an outer-class test does not independently prove that component's methods inert.

**Smallest fix:** encode known scalar representations without metadata-driven multimethod dispatch and calculate a conservative bound for every unbounded component before conversion. Inspect held component classes where operations dispatch through them. If the bound does not fit, emit a charged opaque/cut face. Do not create decimal text to learn whether it fits. The proposed O(1) estimates also need source/measurement evidence, particularly cold precision computations; bit-length bounds can be conservative without pretending conversion cost is zero.

The record print-method comparison does not solve scalar metadata dispatch. Coordinate admission uses these same scalar rules, so this is also a path/node-id defect, not just cosmetic printing.

### Throwable and lazy-result qualifications cannot be footnotes

The Throwable subsection explicitly admits that a nonreturning override cannot be interrupted (`design:196`). That contradicts the owner's “never run user code” guarantee if arbitrary Throwable subclasses are nevertheless projected. Per-accessor `try` handles a throw, not blocking or effects. Even ordinary accessors over a huge finite cause chain/stack do not make eager preprojection bounded by B. An identity set needs space proportional to visited links; apply its bound during acquisition.

The design also asserts top-level lazy results were realized before `shown-result` (`design:176–178`, later marked unproven). The ordinary evaluation path binds the actual result and renders it (`sci/eval.clj:3842–3856`); `shown-result` calls `prepare` on the actual value (`3186–3231`) and `bind-result!` retains the object (`583–600`). There is no universal eager-admission step in that trace. **The asserted guarantee is falsified as a description of this path.** Do not add realization to make the example true. An unrealized result should visibly remain pending; explicit user evaluation may consume it under its own execution contract.

## Shell order: useful ordering, overstated fairness

### What can be proved

For a structural path with depth d and sibling ordinals i₁…i_d, define shell `max(d,i₁,…,i_d)` as proposed (`design:219`). Extending a path or advancing a sibling never decreases that shell. Assuming one queued frontier member per open collection, admission of a member followed by enqueueing its next sibling/first child enumerates the currently discoverable frontier in nondecreasing shell order. With finite positive prices and bounded admission, this is a reasonable finite best-first prefix.

That establishes an **ordering**, not “every branch gets k members at depth k for this B.” Complete shells give such a geometric statement only when every member in those shells is admissible, no ancestor closes early, and B pays all node, key, cut, legend, and layout costs. Deep singleton chains still cost one ancestor per level; no finite B can show every part of an arbitrary value. State the conditional guarantee and expose omitted regions honestly.

### P1-5 — Prefix cuts conflict with skipping keys; expensive members defeat the fairness claim

**Composite-key counterexample:** take a held iteration order equivalent to `(array-map :a 1 [2] :hidden :z 3)`. The design elides composite keys but allows other scalar-key entries to appear (`design:306–309`). If two entries are shown, `next-offset = shown count` (`248–252`) is 2, which points to `:z`, not to the skipped middle entry. A single tail cannot describe that selection: it is not a prefix. Reopening at the composite key under the same rule can instead produce a cut that never progresses. Asking the agent to evaluate `seq` is not browser exploration of that cut.

**Atomic-price counterexample:** in a trusted, ordered map with a first scalar key whose encoded price exceeds remaining B and a later short key such as `:ok`, the first admission fails and closes the parent (`design:238`). `:ok` is never shown although it fits. This is explicitly allowed by the proposed algorithm, so the unrestricted “huge sibling cannot starve others” argument (`256–265`) is false. The string-value special case works because it is chunkable; arbitrary atomic keys/scalars are different. Even without a huge scalar, an early bushy branch can consume a shell before a later root sibling is eligible. This is not equal per-branch budget allocation.

**Worked-example counterexample:** the B=100 example (`design:277–282`) reserves the roughly 90-character legend first (`290–295`), leaving about 10 characters. The displayed collection alone exceeds that. The example cannot demonstrate the stated allocation under its own charges.

**Smallest fixes:**

- Traverse maps structurally as entries, with separate key/value child roles and a held-order ordinal when a literal key cannot be used safely. Composite keys are values to inspect, not a reason to omit an entry permanently. Navigation should not hash or compare the displayed raw key. Clerk/Orchard supply the useful precedent below.
- Track the next **absolute source ordinal**, not the number rendered on this page. Use prefix pages consistently; if holes remain, represent those holes explicitly instead of calling them one tail. Prove every successful expansion advances or terminates with an explicit unexpandable reason.
- Give an oversized atomic member a bounded charged descriptor at its position, permitting subsequent siblings where budget permits. Reserve a minimal key-and-value entry skeleton before refining either side, so no admitted map entry lacks a value face. Then shell-order refinements can grow strings/children. This is a correction to the node admission unit, not a second allocator.
- Replace the unconditional fairness claim with the completed-shell guarantee and examples using the real legend/layout charge. Specify the scalar-root case too: there is no parent to close when the root's literal is unaffordable.

### P1-6 — `O(V log V)` omits acquisition, ordering, and coordinate costs

The heap-selection bound (`design:242`) can apply to admitted frontier operations. It is not the request bound. `ordered-head` scans all entries (`render/value.clj:425–442`); the design acknowledges `O(N log n)` sorting but still presents a sub-second floor for arbitrarily large maps. One pending child does not bound that preparatory scan. Canonical comparison may also need bounded representations of huge scalar keys. Sorted persistent maps can carry custom comparators: key lookup calls `doCompare` and the supplied comparator (`PersistentTreeMap.java:313–330`), even with an innocuous lookup key.

Full-path processing adds another missing factor. Hashing/printing the length-d path for each node in a depth-D chain can accumulate Θ(D²) coordinate work, despite O(D) selected nodes. Use carried structural addresses and create bounded external coordinates only where required; include their actual work in the complexity statement. Do not claim that finite paths automatically have bounded total cost.

Smallest policy: preserve a held collection's iteration order for generic inspection, or sort only an explicitly bounded finite page without claiming global canonical order. Keep semantic/schema ordering for trusted producer-owned blocks. A global sorted prefix requires a whole-input scan unless an upstream index supplies it; charge that as a separate query bound, not as free rendering. Offset navigation over a linked sequence likewise costs the skipped prefix and must not realize it.

## Fipp as the AI face

### P1-7 — A finite selected tree can still overflow the serializer or create quadratic whitespace

The dependency choice is sound: feed owned document operations to `fipp.engine`, avoiding fipp's EDN visitor and its fallback `str`/deref behavior (`reference-code/fipp/src/fipp/ednize.clj:45–61`). However:

- `engine/serialize` recursively composes lazy sequences (`engine.cljc:12–19`); group/nest serialization recurses through child documents (`47–56`). An iterative Seon `node->doc` does not prove that forcing a deeply nested document is stack-safe. The design correctly lists a depth-5000 proof as outstanding (`design:594–607`); it is a blocker for the deep-value promise, not a finishing benchmark.
- `annotate-begins` bounds its lookahead (`engine.cljc:92–161`), but `format-nodes` retains a nesting/tab-stop stack (`171`, `210–212`). “O(width) memory” (`design:342`) is not the whole emitter's memory bound. Count the selected tree, serializer realization, depth stack, and emitted output.
- `annotate-rights` counts flat widths (`68–85`). `format-nodes` emits newline plus indentation on a broken line (`179–204`). A depth-d chain of nested maps can have O(d) flat punctuation and Θ(d²) indentation bytes. Nesting by one or two rather than aligning to the opening bracket improves constants; it does not remove this asymptotic case.

**Smallest fix:** use an explicit-stack traversal to feed a flat stream of the engine's existing operations into its existing annotation/layout stages; prove those stages on a deep input without recursively nested serializer work. This reuses the engine rather than replacing its layout algorithm. Audit HTML tree construction and final Hiccup serialization for the same depth hazard; fixing AI alone is not one safe pipeline. Alternatively, an explicit charged structural depth cut is honest but must be accepted as a weaker deep-inspection guarantee.

The engine also supports an explicit writer (`engine.cljc:233–244`); `pprint-document` adds a final newline (`247–259`). The design need not invent a writer abstraction because it assumes only `*out*` is available.

### P1-8 — Flat-width charging is not the one budget on saved AI text

The design expressly excludes layout whitespace from B (`design:369–380`) and compares B only to final flat position. That is a different resource from the actual saved text consumed by the AI. The reported 2.4× example is evidence against treating the difference as negligible; deeper examples can be worse as above. Token-to-character calibration is already an approximation; exempting an unbounded byte category adds an avoidable second discrepancy.

There are smaller accounting inconsistencies too: pricing every string chunk as `pr-str(chunk)` charges quotation marks repeatedly, while joining those chunks prints one quoted string (`design:365`). Conservative overcharging can be acceptable, but it is not exact equality with final flat width. Namespace factoring changes key spelling after selection; legend reservation and map-key/value punctuation need equally explicit ownership.

**Smallest fix:** define B against the complete saved AI representation, including newlines, indentation, delimiters, descriptors, and legend. Reserve a conservative layout cost during selection, for example each potential break's maximum emitted whitespace under a bounded indentation policy; emit once and compare actual length against that same bound. Capping indentation is a layout policy, not truncating content. Do not silently add a post-emission clipping/retry loop. If the owner instead chooses “B is flat content only,” explicitly weaken the AI budget and sub-second guarantees; that is not the current requirement.

Width 100 is a soft layout width, not a hard limit on a long indivisible literal. Test the documented behavior rather than implying that fipp splits arbitrary strings. **P2:** the proposed universal map cut key `:…` can collide with a real `:…` entry; the current emitter avoids elision-key collision (`print.cljc:623–627`). Keep that property or deliberately make the display non-EDN without claiming it reads as the original map shape.

## Retrieval, custody, and historical cuts

### P1-9 — The endpoint must retrieve the selected structure, not restart an incompatible interpretation

`data/parse-cursor` parses EDN and defaults malformed paths/offsets (`render/data.clj:27–44`); it is not the design's bounded coordinate validator. A check after parsing does not bound parser stack, input bytes, or work. Invalid cursors should be typed refusals, not silently treated as the root. The source selector, typed path, and offset must be validated before lookup and must refer to the same structural interpretation used to produce the cut.

This matters for declared renderers returning transformed data: a cut path into that selected representation may not be a path into the original source object. Re-running a producer at a raw path can choose a different value or run code twice. Clerk solves this by retaining presented values with path-specific fetch closures (`viewer.cljc:1731–1734`, `1842–1856`), not by assuming every presented path is a raw `get-in`. Seon need not copy its registry, but must specify whether a continuation addresses a held rendered representation or a reproducible source query with a checked basis. One source/path contract owns that choice.

Likewise the new set marker `[:seon.render.data/nth i]` must be an unambiguous typed path step, not a shape guess that collides with a literal map key. Carry key/value roles for entries. Bind the cut to a source/basis; treat the DOM selector as derived output, not as authority supplied by the caller. Existing Datastar replacement support (`web.clj:3281–3309`) is sufficient delivery machinery, but only if a response targets the intended occurrence/page and does not duplicate node ids when the same digest is shown twice.

The design's blob path does whole-blob EDN parsing and semantic reconstruction before selection (`web.clj:3527–3529`; `value.clj:708–718`; `admit/semantic-value:584–622`). Cost is at least O(artifact size), plus path/offset traversal and selection; it is not O(page size). A reference to a cache for >50 ms requests is not an identified installed seam or proof. Render the admitted artifact representation directly where possible; for large durable random access, a storage format/index decision is required. Do not smuggle that into a 45-line HTML emitter estimate.

### P1-10 — Historical expansion is a durability decision, not a free use of existing blobs

The current durable evaluation schema saves exact `:seon.eval/shown` text (`resources/seon/schemas/seon.eval.edn:31`, `41–44`); it does not declare an artifact digest. The governing context-generation PRD §15 (`docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md:1005–1080`, especially its live-result/shown-text distinction) intentionally keeps actual objects live and removes result serialization. Current `shown-result` follows that model. MCP stores an artifact only in the qualifying oversized branch (`cluster.clj:448`, `478`), so “MCP values already have blobs” is not a universal existing guarantee either.

A new evaluation artifact requires admission safety, storage cost, schema/reference ownership, retention, and failure semantics. `maintenance/branch-digests` queries history (`src/seon/maintenance.clj:578–600`, particularly `584`); adding a digest attribute is not a promise that removing a current evaluation will immediately reclaim its blob. A blob digest identifies admitted bytes, not the original arbitrary object, renderer implementation, profile, or historical selection.

The debug left face must always be the exact saved AI text. The current outline already has that text and a live/fallback HTML call (`src/seon/render/transcript.clj:1574–1612`); replacing its toggle with side-by-side presentation is small. Re-rendering the digest later with today's B/profile/program does **not** reconstruct the original node tree or guarantee “same B.” Label the right face's source/basis truthfully. Never regenerate the left face as a substitute for the saved observation.

There are exactly three meaningful durability options for the owner; these are planning costs, not measured implementation estimates:

1. **Live object + exact saved text (recommended minimal scope):** reuse `result-objects` and existing custody lookup; after eviction/restart, show saved text and a typed unavailable-live-source result. No new durable write or full-result admission. Cost: shared entrance/adapters only; ordinary page work is proportional to safely visited data. Sacrifice: old unseen values are not recoverable after the live subject disappears.
2. **Durable bounded admitted representation:** persist an explicitly bounded safe snapshot plus its provenance, and navigate that representation directly. Cost: new schema/reference/retention/failure integration and O(admitted bytes) write/read unless indexed. Sacrifice: a historical cut cannot recover information excluded from that snapshot; do not advertise it as the full result.
3. **Durable fully navigable supported results:** design indexed storage and resumable source queries for an explicitly supported finite data domain. Cost: a separate storage/admission project with O(total supported data) acquisition and retention, not the proposed folded page patch. Sacrifice: arbitrary host/lazy/infinite objects remain unsupported, and full capture cannot have a size-independent sub-second guarantee.

The first option is compatible with existing authority. If the owner's “cuts load lazily” includes post-restart historical unseen data, the first two do not satisfy that added promise; choose and price the third explicitly. The design correctly flags a root ruling, but must not count the digest write as a decided, trivial part of the fold.

## A smaller hybrid from the prior art

Use the useful parts of all three dependencies without importing their unsafe acquisition defaults:

- **Fipp:** its established layout transducers over an inert flat document stream; no EDN visitor, raw-value recursion, or bespoke wrapping printer.
- **Clerk:** one presented tree, explicit elisions with absolute offsets, and fetch tied to the represented source/path. Its child continuation advances from the actual child path (`viewer.cljc:1681–1705`), and string replacement paths distinguish their offset (`1707–1716`). These are stronger precedents than “a button calls `/data`.” Do not copy its eager sorting (`1463–1468`) or bounded-count realization (`1576`).
- **Orchard:** separate the displayed label from the role/index used to navigate. Its inspector tracks the inspected value and entry role/key (`inspect.clj:197–214`), while role navigation is explicit (`31–40`); page state is separate from rendering. Adapt this to structural map entries, including keys, so large/composite keys remain explorable. Do not copy arbitrary `Counted` (`104–111`) or `drop`/`take` over unknown seqs (`130–148`).

The resulting composition is: resolve a held source under custody; safely expose structural entry/cell operations; select/refine charged nodes in shell order; emit two faces; use the same entrance for a continuation. It needs no automatic lazy realization, global sort, raw-key hashing, transport projection, or new result registry. Identity/schema-specific renderers can supply a trusted smaller value, but their transformed continuation basis must be explicit and repeated-producer guards must survive the refactor.

“Unclipped HTML” should mean all **safely available** structure is eventually reachable by progressive expansion, with no second presentation truncation. It cannot mean forcing a lazy tail or rendering an infinite value in full. Pending, opaque, stale, and gone sources need distinct outcomes; repeatedly returning an unchanged cut is not exploration.

## Size and landing order

### P2 — The +310/−1,150 figure is not yet an honest whole-fold estimate

The design labels its estimate as unmeasured, which is correct. The deletion list is substantial and credible as a retirement inventory, but the additions omit or compress the fixes above: safe scalar/seq operations, common acquisition, structural navigation, producer continuation basis, real whitespace accounting, deep serialization, and existing browser read-back conversion. `node->doc` 55 plus `node->hiccup` 45 (`design:357`) does not price the endpoint/storage work.

There is also a checkable arithmetic error: the §6 table rows sum to **700 added / 1,965 deleted**, not the final paragraph's **600 / 1,865** (`design:659–675`). Both yield net −1,265, so a net-only check hides the discrepancy. Fold row 2 separately says approximately 10 schema and 150 test lines beyond its 310 source additions; the final statement says its total includes schemas/contracts/regressions. Those bases must be reconciled. Row 2's own source arithmetic is −840; adding its stated 160 other additions makes −680 before corresponding test deletions are specified.

Do not approve a new precise estimate from this static review. Reprice the corrected ownership boundaries as source, declarations, tests, and durable-storage work separately, with a deletion inventory and caller list. A plausible short emitter does not establish a short safe selector or retrieval service. The existing “each slice ≤100 added lines including regressions” header also conflicts with the folded +190 selection and +310 total. The row acknowledges a waiver is still needed; an old slice's approval is not proof that the enlarged fold is authorized.

**Split the fold by proof boundary, not by leaving two pipelines alive:**

1. Correct the design and source/path/budget contracts first. Implement the safe shared selection/navigation entrance with all MCP and browser callers converted in the same activation cut; retire old windows/projection/fit behavior rather than keeping a second active route. Keep current terminal emitters temporarily only if both consume this exact shared tree and accounting.
2. Replace terminal text layout with fipp and HTML emission after deep-stack and actual-output accounting are proved; remove Sink and read-back consumers together. The safe selection contract should not change in this step.
3. Add cut replacement and side-by-side debug delivery through the shared entrance. Keep new durable historical capture out of this cut until its separate owner decision is resolved.

If the first activation cannot fit the line law, report its concrete size and seek the required owner exception through the implementer/root. Do not turn three commits into three independent selectors, nor claim +190 already includes these missing seams. This review itself requests no implementation authorization.

## Required implementation evidence and landing note

Before integration, the revised design needs current positive evidence for: hostile `IChunk`/`CharSequence`/wrapped-ISeq inputs without calls; metadata-dispatched Symbols and huge Ratios; cyclic and hostile Throwables at **every entrance**; inert host `Counted`; carried path/root/agent-id validation before digest/refusal; arbitrary-comparator maps; composite-key continuations; nil versus missing; strict offset progress; transformed-producer paths; one prepared tree across MCP/browser; exact saved-text preservation; depth-5000 text **and HTML** serialization; actual emitted bytes within B; and acquisition plus render costs on large artifacts/maps. Use the existing focused installed test process when implementation is authorized, with parent/change timings, allocations, visited counts, and source/basis evidence. These are required future proofs, not tests run by this review.

No current guarantee of sub-second operation or O(width) total memory is established. The present static counterexamples suffice to reject the proposed proof, without running unsafe host calls or an additional JVM. The corrected design can still shrink the code substantially: the largest savings come from deleting the parallel acquisition/projection/navigation/printing paths, not from squeezing two face encoders into an optimistic line allowance.

Landing scope: this review file only; zero source/test changes and no runtime/test execution. Foreign boundary: transient automatic syntax checking reported `test/seon/fn_test.clj:506:79`; unrelated shared-tree edits were preserved. No foreign session was resumed, messaged, or edited, and no worktree was created. Release this review path after its documentation-only commit.
