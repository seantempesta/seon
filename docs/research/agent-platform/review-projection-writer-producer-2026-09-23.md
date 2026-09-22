---
type: review
status: complete
created: 2026-09-23
---

# Review: projection writer producer

Reviewed `9b8c5b4053a782dab6cb0e0eeee471c7bdde3d17`; source/test citations below mean that commit, not the moving working tree.
Dependency prefix `DH` means `reference-code/datahike/src/datahike/`, pinned and checked out at `41c79c1a70f108cf969b8c5ec6d3ba81c8835eb8`.
Authority: `AGENTS.md:65` (No stamps), `docs/prds/agent-platform/plan/README.md:379`, and `docs/prds/agent-platform/plan/lane-projection-as-a-read-sweep.md:87` (new ruling supersedes the older stamp proposal).
Verdict: request changes. Findings ranked below; P1 = correctness/authority, P2 = design, performance or verification gap.

## 1. P1 — as-of projection reads the future (question 2)

`src/seon/db.clj:1264` unconditionally calls `schema-database`, whose `:1164` recursively discards temporal wrappers; `:1276` derives from that origin.
Datahike deliberately shares *physical schema* with the origin (`DH/db.cljc:595`), but declaration datoms obey the as-of time predicate (`:611`, `:621`). These are different authorities.
An as-of value before a form replacement therefore receives the replacement projection. The history identity assertion (`test/seon/schema/projection_writer_test.clj:66`) cannot detect this.
Smallest fix: preserve the as-of value for declaration derivation (initially unmemoized), including the reader at `src/seon/db.clj:1291`; add replacement-then-as-of regression. Define history's decoding policy separately: history has multiple versions, not one old population.
Read-only JVM probe below confirms the lost distinction; it does not claim the reviewed commit was loaded.

## 2. P1 — writer still trusts a metadata stamp (questions 3–4)

`src/seon/db.clj:3739` prefers metadata over the new memo for **every** write, without a genesis/publication discriminator.
Ordinary `transact!` calls `resolve-database-value` (`:4024`), which stamps from connection state or the ambient projection (`:301`, `:307`); `carry-projection-state` retains existing snapshots (`:269`).
Consequently stale construction metadata can select validation/encoding against a different population even when declaration rows exist. Removing report/callback stamping alone does not establish the claimed writer authority.
Smallest fix: ordinary writes obtain their projection from their database; pass the cold publication candidate explicitly through its existing boundary, and remove this metadata precedence. Regress a stale construction projection after a committed declaration.

## 3. P2 — six dependencies are handwritten; invalidation is duplicated (question 1)

`src/seon/db.clj:1223` is a literal vector, not derived from a schema or reader. It matches today's ranges (`src/seon/schema.clj:2545`, `:2560`, `:2798`); no current seventh-attribute miss is established.
A seventh reader dependency omitted from this vector would silently reuse stale projections. `:1238` independently assembles Datahike's connection/generation/conservative/attribute revision rules.
Datahike already advances revisions (`DH/query.cljc:2568`) and derives query dependencies (`:2877`), compares revisions (`:2963`), and promotes safe cached results (`:3001`). Seon duplicates that invalidation policy, although its projection LRU stores a different result and is explicitly authorized.
Smallest correctness fix: use the complete `:cache-context` as ruled; delete the hand list/key projection. This is simpler and conservative, but misses after unrelated commits.
Datahike's revision comparison can preserve *query* results; it does not automatically reuse an arbitrary projection or these `d/datoms` scans. Cross-commit projection reuse needs reader-derived evidence and a dependency-owned comparison seam, not a copied private algorithm. Do not claim full-context keying alone retains today's hit rate.

## 4. P2 — ordinary reader contains a cold metadata fallback (question 3)

`src/seon/db.clj:1266` treats absence of any schema identity as genesis, then reads metadata; it does not establish that the caller is a cold constructor. A stripped population with an old stamp takes the same arm.
This is a fallback, though **not** packaged-form reconstruction. Named refusal without metadata is good; the success arm still makes the result depend on a stamp instead of declaration facts.
Smallest fix: confine supplied construction projection to explicit cold-boundary arguments; the ordinary accessor refuses absent declarations. This follows sweep §3's explicit cold-owner distinction (`lane-projection-as-a-read-sweep.md:85`, `:111`).

## 5. P2 — complete contracts and regression sensitivity are not proved (question 5)

New functions have contract metadata (`src/seon/db.clj:1236`, `:1248`, `:1262`; `src/seon/schema.clj:2790`), but loader input `:map` admits non-databases that its body then rejects; the key's output `:map` declares none of its required members. Touched `src/seon/turn.clj:1202` still has no inline contract.
Smallest fix: use the existing database-value input schema; deleting the bespoke key removes its shape gap; declare/verify the writer callback contract through the canonical arming path. Metadata presence alone is not armed proof.
Ordering/rollback tests (`test/seon/schema/projection_writer_test.clj:17`) are useful; hit-count and speculative tests (`:49`, `:86`) exercise the changed mechanism. There is no as-of-before-change or cold-fallback regression.
The landing note explicitly reports no armed run of the final commit (`docs/prds/agent-platform/landing/lane-projection-writer-producer-2026-09-23.md:246`); its earlier green run predates the final fixes (`:259`). No before/after mutation run proves “fail without the change.”
Smallest proof fix: run the focused namespace under armed contracts, then reverse the behavior change in an isolated snapshot and show the relevant assertion fails; do not count missing private cache Vars as behavior sensitivity.

## 6. P2 — ordered writes still pay whole-population work (question 6)

Datahike clears speculative context (`DH/db.cljc:408`); `src/seon/db.clj:1278` always reloads. Each `row-tx` (`src/seon/turn.clj:1240`) scans all three declaration populations (`src/seon/schema.clj:2795`) and rebuilds (`:2750`). Work scales with population × callbacks, not the changed dependency closure; even repeated reads of one unchanged speculative value reload.
The reported 277.038916 ms is one predecessor unarmed warm derivation, not a fresh measurement of every final ordered declaration (landing note `:160`). It does not prove proportionality or retained memory (`:164`).
Unmemoized intermediates are explicitly allowed by the new §3 ruling (`lane-projection-as-a-read-sweep.md:97`); that permits this implementation step, not a claim of incremental cost.
Smallest follow-up: profile the existing loader, reuse dependency-owned compiled schema work for unchanged definitions, and measure fixed-change cost against growing populations. Do not restore candidate transport or invent speculative identity. Existing issue: `docs/seon/issues/class-local-updates-recompute-global-projections.md:12`.

## Confirmed pieces and exact evidence boundary

The authorized core.cache LRU has bound **8** and a reason as data (`src/seon/db.clj:1213`); delayed misses use `lookup-or-miss` (`:1275`). No new per-connection atom was introduced. Existing connection state remains (`:238`), so “no atom beside the connection” is not yet a system-wide result.
Callback stamping is removed (`src/seon/schema/datahike.clj:409`); transaction reports return directly (`src/seon/db.clj:3811`). Writer-path stamp removal remains false for finding 2.
`bin/seon status`: 0.077 s; MCP runtime status answered, PID 51528, all proc replies, 14 existing errored receipts. No reload, adoption, write to a connection, source/test/resource edit, gate, or test JVM was performed for this diff review.
MCP `eval_clj`, explicit root `/Users/sean/src/seon`, cluster `default`, JVM, read-only, 10000 ms bound: **2 ms evaluation / 233 ms tool wall**; full returned envelope had a normal `ret` and no error. Exact form:

```clojure
(let [d @(seon.cluster.boot/connection "default") row (first (datahike.api/datoms d :avet :seon.schema/key)) e (:e row) old (:seon.schema/form (datahike.api/pull d [:seon.schema/form] e)) next (:db-after (datahike.api/with d [[:db/add e :seon.schema/form ":string"]])) view (datahike.api/as-of next (:max-tx d)) origin (seon.db/schema-database view)] {:key (:v row) :old-form (subs old 0 (min 100 (count old))) :as-of-sees-old (= old (:seon.schema/form (datahike.api/pull view [:seon.schema/form] e))) :origin-sees-new (= ":string" (:seon.schema/form (datahike.api/pull origin [:seon.schema/form] e))) :origin-is-next (identical? next origin) :view-identity (datahike.db/committed-value-identity view)})
```

Result: `{:key :inst, :old-form "inst?", :as-of-sees-old true, :origin-sees-new true, :origin-is-next true, :view-identity nil}`. Only immutable speculative values were constructed. Source establishes how the reviewed accessor consumes that origin; no armed acceptance is claimed.
Timing: document creation plus automatic markdown lint took 1.3 s (tool total; phase split unavailable); other measured review operations were below 1 s. Lint found missing frontmatter here (fixed) and unrelated historical dependency-pin citations (untouched). The 277 ms and historical test/boot costs are cited lane evidence. Only this review file is owned and committed.
