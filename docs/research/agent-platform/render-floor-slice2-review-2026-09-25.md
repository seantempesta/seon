---
type: review
created: 2026-09-25
scope: Render floor slice 2 uncommitted diff
status: commit after listed fixes
---
# Render floor slice 2 — independent static review

The existing floor converts live values or print nodes into one allocated tree, then emits it. The principal Clojure approach in §3 remains appropriate: shallow measures as data, one recursive allocation of selected children, and emitter-owned character costs. This draft still mixes measurement with recursive rendering and loses some carried coordinates.

**Do not commit this diff as is.** Findings below are P1/P2; no P0 was established. The owner's 100-added-line waiver applies and is not a finding.

## Evidence and limits

Read end to end: `CLAUDE.md`, `one-render-path-design-2026-09-25.md`, the render-floor-opus landing, `tmp/render-floor-opus/slice2.diff`, and both current source files. Also read the data-oriented Clojure skill and its relevant §13–§15 authority, the affected callers/tests, and the local Malli seam. This review ran **no tests, JVM, init, reload, adoption, or instrumentation**. Examples below are static counterexamples and call traces, not executed results.

Reviewed diff SHA-256: `232f42a963693971d37e6dc62e7a301f33ef060e8d39cb8aa2767bccdcc2888d`. Checkout HEAD observed: `ad9f579f4ed0bf899e3bcdccf77fbaf1bcc748f4`. Source citations use the **new-side line numbers in that diff**; `diff:N` additionally identifies its physical line. Caller citations are checkout observations. In-flight `error.clj` changes are outside the reviewed diff and are not accepted by this review.

The landing's scratch measurements (14.5 ms median, 52,370 KB allocation, simulated contracts) are useful reported evidence, not installed proof. Its random checks establish neither the missing cases below nor an actual B bound: “charge ≥ emitted” is a different property. No fresh timing, memory, or installed-health claim is made here.

## P1 findings — fix before committing

### 1. Minimum measurement recursively renders hidden descendants and can invoke producers twice

**Evidence:** `src/seon/render/value.clj:598–621` (`diff:972–995`) computes an item's minimum by calling `value-node` with share zero at line 609. That enters `allocate-children` again, forces its `first-level`, and measures another first child. Line 651 then calls `value-node` again for the selected child with its real share. Producer selection at lines 737–744 occurs on both calls.

A chain of singleton maps therefore descends its first-child chain during minimum measurement, even if the outer collection will become one elision. Selected nested chains repeat this work at successive ancestors; with depth ceilings removed, a fully retained depth-d chain can incur quadratic visits. The current depth dial limits the damage but cannot substantiate §3's no-hidden-subtree and recurse-once claims. A producer on a candidate can run during pricing and again during allocation, including candidates ultimately omitted.

**Smallest fix:** separate shallow measurement from allocation. Return the floor/first-level target and already obtained leaf/producer result as data; do not obtain a minimum by entering `value-node` recursively. Allocate only selected children once, retaining any producer result already obtained. Verify producer-call counts and visits on a deep singleton chain and a wide collection whose candidates are mostly omitted.

### 2. Candidate pricing and ordering perform unbounded content work before affordability is known

**Evidence:** `value.clj:587–595` (`diff:961–969`) calls `cost` on a whole scalar string before deciding that the shallow collection is too expensive. `value.clj:599–600` (`diff:973–974`) renders every candidate map key through the unbounded HTML path. `value.clj:516–519` (`diff:890–893`) uses `sort-by` with `emit-text` as the key function for node maps/sets. Clojure's `sort-by` calls its key function during comparisons; these bytes are not cached once per key. `print.cljc:439–446` (changed comparator hunk at `diff:300–310`) recursively sorts composite maps/sets inside comparisons and treats uncounted sequences as equal rather than using the specified opaque identity order.

Thus `[a-five-megabyte-string]` still prints that entire string to reject its shallow floor, despite the landing's fast **root-string** measurement. A tiny-budget map with a huge composite key fully traverses that key before omitting its entry. A lazy key reaches the HTML collection walker, where all entries are collected (`value.clj:568`); an infinite lazy key need not terminate. Ordinary lazy collection values also enter `live-parts`, realizing candidates rather than becoming opaque. The changed comparator protects only one comparison branch, not this rendering path.

**Smallest fix:** use the string's raw length as an early lower-bound rejection before escaped printing; use one cached safe ordering key per reached finite key; treat lazy/host keys as opaque before any traversal. Bound whole-key measurement by the available allowance and elide the whole entry if the key cannot fit. Use the same ordering operation for live and admitted children. These changes implement the existing §3 algorithm; no new cache registry or printer is needed.

### 3. Reconstructible original paths and carried retrieval refusal are not preserved

**Evidence:** `value.clj:522–523` (`diff:896–897`) reconstructs a node map's step from the key node's `:seon.print/value`, falling back to its ordinal. Node sets always receive ordinal steps. `value.clj:606` constructs a new path and `value.clj:761` (`diff:1256`) unconditionally overwrites an existing node path. The deleted `fit-node` instead preferred the carried path. The depth-cut branch at `value.clj:719–720` copies only `:seon.print/requery-id`, unlike the allocation branch's id/refusal handling at lines 561–564.

For an admitted map keyed by `[1 2]`, its vector key node has no scalar `:seon.print/value`; the new cut path becomes `[0]` rather than `[[1 2]]`. For an admitted set, `[0]` is not a member-based path accepted by `print/value-at`. A producer child already naming `[:original :payload]` instead gets its display position. Existing elision nodes can acquire a new path while keeping their old `:seon.print/requery-form`. A carried explicit refusal is replaced by the outer profile's retrieval identity/refusal when the depth branch fires.

**Smallest fix:** carry the original child step/path in the node data and prefer it during allocation, including sets and compound keys; never infer it from printed text or an ordinal fallback. Reuse one id/refusal transfer operation for all replacement cuts and derive any requery form from that same retained path. Add carried-node cases with compound keys, set members, pre-existing paths, and a refusal under an outer requery id.

### 4. The final check does not enforce B or report the authorized smallest-face exception

**Evidence:** `value.clj:838–852` (`diff:1316–1330`) converts B once, but compares emitted length only with `charged`. It never checks `charged` or emitted length against `allowance`. `fit-string` explicitly returns an over-share floor at lines 485–489; collections can raise the local share at line 596 or return an over-share cut at line 621. These can be legitimate minimum representations, but no diagnostic distinguishes that case from allocator overrun. The landing expressly acknowledges that omission.

The new exception is also not the named, path-bearing allocator error required by §3: it has operation/expected/offending fields, no declared error schema, no path, and unqualified nested `:allowance/:charged/:emitted` keys. The broad catch at lines 762–776 still converts arbitrary faults to an ad hoc layer/message face using the independently clipped Throwable floor. It can return a large terminal face irrespective of its assigned share; charging it accurately does not prove budget compliance.

**Smallest fix:** carry explicit minimum-representation evidence with allocation results and perform the final emitted-length comparison against the original character allowance. Over B must yield the declared insufficient-floor-budget diagnostic only when that minimum cannot fit; other overages must be a declared allocator defect naming path, allowance, charge and actual size. Preserve the real failure through the existing error owner rather than treating a message-only face as successful allocation. Do not add a clipping retry.

### 5. The compact face removes refusal evidence from callers that never receive the legend

**Evidence:** `print.cljc:477–481` (`diff:346–361`) drops each elision's refusal. Only `value/prepare` appends a legend, and only to its AI text (`value.clj:841–843`, `diff:1319–1321`), after `emit-both`. The same prepared Hiccup has no legend. The root profile, not the actually retained cuts, supplies the legend's refusal at lines 833–836; carried cuts can have different custody.

The schema still directly declares `seon.print/render-elision-ai` as an elision renderer (`resources/seon/schemas/seon.print.edn:348`). A direct invocation on a refusal-bearing elision now says neither how to requery nor why requery is unavailable. Likewise, the admitted-receipt reader `src/seon/eval/drive.clj:135` emits nodes directly and reads their compact face back as data, so refusal information formerly present in that derived value disappears. Neither caller is listed in the landing's conversion holds. This is separate from the known transcript/web assertion changes.

**Smallest fix:** compose compact cuts and their retrieval legend at the shared owning emission boundary, including the Hiccup representation, and convert the standalone renderer/receipt callers in the same cut. Derive the legend from the actual retained custody; where refusals differ, preserve their association with cuts instead of claiming one outer refusal explains all of them. Keep one charged legend, not one fresh budget per caller.

## P2 findings — complete in the same correction

### 6. Canonical order does not satisfy the identity and representation requirements

**Evidence:** `value.clj:503–504` orders live members with `print/compare-values`; `value.clj:516–519` sorts admitted members by emitted text. Live numeric map keys `2, 10` sort numerically, while admitted numeric key faces sort as `"10", "2"`. Neither branch puts declared entity identity attributes first. The landing acknowledges both differences, but §3 requires identity-first ordering, and the retained poll regression specifically protects identity as payloads grow.

**Smallest fix:** use one canonical order descriptor for both representations, with the supplied projection's declared identities first. Preserve sequence order and held-value ties for opaque values. Do not restore an emitter re-sort. The fixed poll example may still fit; that is not proof that a late-sorting identity survives an affordable-prefix cut.

### 7. Contracts are present on most new helpers, but the full law is not met

**Evidence:** new `print/namespace-factoring` has an unexplained bare `:any` tuple member (`print.cljc:612`, `diff:374`); changed `print/literal` still has no contract (`print.cljc:11`, `diff:286`); new test helper `floor-tree` has none (`test/seon/print_test.clj:322`, diff's new helper hunk). New allocator helpers mostly declare contracts, with named value/path/node types and no anonymous contract predicates. Several internal records are specified only as `:map`, leaving the allocator's required measure/path fields unchecked.

Malli's actual seam is `malli.core/validator`, `reference-code/malli/src/malli/core.cljc:2637`, revision `befcd302a8e626a67d84b80aabee0cd1b38dadb6`: it caches a validator for a compiled schema. No contract was compiled in this review. Locally, `seon.schema.internal/assert-complete-schema!` at lines 309–345 records undefined-slot advisories; I do **not** claim the bare `:any` necessarily prevents load. It still fails the assignment's complete-contract rule.

**Smallest fix:** give touched/new functions complete contracts; make `namespace-factoring` accept the key nodes it actually needs rather than `[key ignored-any]` pairs. Declare the required internal measure fields or explicitly justify the polymorphic boundary. Use the declared error shapes from finding 4. No new global state or provenance stamps were found in this diff; local delays/heap state are per invocation.

### 8. The allocation function braids too many responsibilities, and scalar bytes are discarded

**Evidence:** `allocate-children`, `value.clj:547–668`, is 122 physical lines; `value-node*`, lines 697–751, is 55; `prepare`, lines 802–867, is 66 including its existing boundary work. The largest function combines carried custody, candidate acquisition, syntax pricing, shallow floors, key rendering, attribute conversion, recursive minimum discovery, prefix choice, allocation and node assembly. `print/text-cost` (`print.cljc:892–901`, `diff:468–477`) prints scalars to count them and discards their bytes; scalar minima can be priced twice at `value.clj:608` and printed again at emission. `fit-string:487–489` retains a local emit/adjust loop, although the old whole-tree fit loop is gone.

**Smallest fix:** use the shallow measure data from finding 1 to separate measurement, affordable-prefix selection and allocation, retaining encoded leaf bytes for the final emitter. Share emitter syntax rather than expand the arithmetic duplicated in `entry-cost`, `group`, `syntax` and the final charge. This should simplify the existing seam, not introduce a second allocator. The owner waived total slice size, not these mixed responsibilities.

## What is supported, deferred, or still unknown

- **Supported statically:** the old `fit`, `fit-node`, `fit-children`, `fit-entry`, structural helpers, node counter and emitter re-sort are deleted. Budget arithmetic uses characters, not a sum of counts and characters. The chosen children receive reserved minima plus a share of surplus and are placed back in canonical positions. The whole-collection affordability case is separately tested; the remaining fixed group reserve makes prefix costs monotone before that last case. There is no whole-tree refitting loop.
- **Costs partly shared, not fully emitter-owned:** separators, elision keys, scalar literal printing and namespace factoring are reused. The allocator still assembles its own structural charge and repeats leaf printing. Selection is O(n log k) only when comparator work is bounded; findings 1–2 add hidden descendant traversal, repeated sorting/printing and potentially nontermination. The output-tree `tree-seq` used to detect cuts is an extra O(output-tree) pass, not a hidden scan of the raw value.
- **Carried totals:** `fit-string:474` preserves admitted string length; `node-parts:524–525` preserves a carried children total or derives it from omitted count plus admitted children. These arithmetic intentions survive. Path/custody preservation does not, per finding 3. The rewritten carried-count test checks a vector with a carried id, not compound keys, original paths, sets, or refusal precedence.
- **Retained regressions:** `fit-preserves-breadth-and-long-strings` still tests useful breadth and string-prefix intent through `prepare`; its longer tiny-budget string is consistent with avoiding an elision larger than the string. `background-poll-keeps-identity-while-payloads-grow` remains present but was not run by the lane's fixture-free scratch suite or this reviewer. Its fields/cut totals remain the right assertions; identity-first and real-SCI proof are still required. The `preserve-requery` intent is only partially implemented, not verified by its helper's deletion.
- **Second clipping paths remain:** `value.clj:66–132` contains independent bounded-text/Throwable cuts (slice 3); selected producer text still enters `fit-string` at lines 710–714 (slice 4); `window:312–356` remains (slice 5); depth/children/string ceilings remain at lines 475, 705–706 (slice 10). `prepare` disables emitter length/level cuts, so those are not an additional cut in this floor call. These scheduled retirements are not grounds to demand every later slice now, but “nothing else clips” is not yet true of the system.
- **Caller sweep:** no executable source call to deleted `print/fit` was found in `src/` or `script/`. The direct `error.clj` caller is already a documented atomic-conversion hold; its separate in-flight edit needs owner review. `eval/drive:135` and the schema-declared elision renderer are additional affected callers (finding 5). `cluster/prompt:280` also calls `render-elision-ai` directly, but its evaluation-unit elision has a requery form and no character estimate, so no concrete regression there was established. Direct emitter tests continue to exercise a public seam; replacing the one canonical-order test does not prove all callers are converted.
- **Publication:** scratch validators are not actual arming. After the fixes, root must obtain installed adoption/arming and the focused retained regressions, then the affected integration proof and before/after allocation/visit/order/emit measurements already required by the design. This review grants no runtime pass and changes no production/test source. The automatic Markdown hook reports 66 repository citation issues outside this file; those foreign files were not edited.

**Verdict: commit after listed fixes.** Keep the node/emitter composition and the reviewed fair-share design; replace recursive pricing with shallow measure data, preserve coordinates/custody, enforce and diagnose B, and complete the compact-face caller conversion before integration.
