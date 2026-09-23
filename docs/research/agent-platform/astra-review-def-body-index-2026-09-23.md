---
type: review
status: ready after fixes
created: 2026-09-23
scope: B1 definition dependencies and D1 pending-test admission
---

# Independent review: definition initializer dependencies

The system already has kondo's definition owners, symbolic edges and a reverse
dependency walk. Preserve those values at row construction; distinguish a possible
dependent selected for safety from positive evidence that a test reaches a function.
The smallest repair composes those owners, without another analyzer or pending registry.

**Verdict: ready after fixes.** No P0 finding. The omitted-declaration diagnosis is
right; the proposed B1 paragraph is not ready verbatim. Resolve P1-1 through P1-4
below before implementation. Pending-test admission is a separate D1 completion,
not a prerequisite for repairing existing definition edges.

Reviewed research commits `06e62e0a3` and `ef0f51891`, their proposed paragraph
at `docs/research/agent-platform/def-body-index-gap-2026-09-23.md:466`, and D1's
strict-default ruling `eb709fcb8`. Source anchors describe the inspected shared
checkout (HEAD observed as `26bdef4d18de9710271db77f26dca8446ee6a5e7`), including
foreign uncommitted changes to `src/seon/fn.clj`. They are static evidence, not
claims that those bytes are installed. No foreign file or session was changed.

## Mechanism and upstream evidence

Pinned clj-kondo is `57252e07975710aa579b24f0d1b2b1e04195caa2`, upstream base
`794a508d` (2026.07.24). I inspected the base's source and the base-to-pin diff:
`analysis.clj` and `linters.clj` have no differences; `analyzer.clj` differs only
at two metadata conversions near line 984, outside the seams below. These are
upstream guarantees, not capabilities invented in Seon's fork. Paths below with
`K/` mean `reference-code/clj-kondo/src/clj_kondo/impl/`.

* `K/analyzer.clj:1889` associates `:in-def` before initializer analysis at
  `:1902` or other defining-form children at `:1959`. `K/analysis.clj:19–55`
  emits `:from`, optional `:from-var`, target and optional call arity. Missing
  `:from-var` does not delete the usage. `:name-row` and the other name coordinates
  already exist at `:43–46`; a finding join needs no new parser.
* Seon's adapter preserves ownership at `src/seon/fn/analyzer.clj:130–138` and
  additionally repairs ownership at `:444–483`. The loss is downstream:
  `src/seon/fn.clj:330–353` excludes nonliteral definitions without arglists,
  `:663–734` omits their rows, and `:406–421` drops calls from omitted callers.
  Literal `def`, direct function initializers with analyzed arglists, explicitly
  arglisted definitions and `defmulti` are not the general missing-row case.
* Factory results, memoized wrappers, delays and maps holding callbacks can lose
  owners. `(memoize F)` needs a reference edge, not an invented F call arity.
  Literal **defonce** is a separate nuance: literal classification only accepts
  `def` (`analyzer.clj:512–518,597–604`). “Literal defs qualify” must not be read
  as a guarantee about literal defonce.
* `references-by-caller` retains those resolved targets under nil
  (`fn.clj:382–400`), and file construction preserves them (`:1244–1273`,
  `:1411–1421`). Both fallback consumers require a test in that very file
  (`:1488–1492`, `:1570–1573`). This cannot traverse a separate-file test's
  `test → g → F` path when g was omitted. The diagnosis is supported without
  claiming a runtime selection experiment.
* Qualified missing callees already survive: `K/linters.clj:702–730` records the
  unresolved finding and still registers usage; `fn.clj:355–368,406–421` does not
  require a callee row on the two-argument path. The reverse walk seeks the seed
  symbol directly (`:1543–1545`). Preserve this; do not add another edge producer.

Inputs are captured changed-file analysis, resolved namespace context and current
immutable program facts. Recompute declaration facts on changed source or analyzer
inputs, and reconcile exact replacements. Owner retention adds O(D_changed +
U_changed) work and O(D_changed + E_changed) data; it needs neither runtime value
inspection nor a full-program lint. Selection's existing global setup is a separate
cost, addressed in P1-4.

## Findings and concrete text changes

### P1-1 — Widened selection must not manufacture reaching-test evidence

The proposed conversion of both fallback joins is underspecified. Removing the
same-file restriction and making every test a graph predecessor would also make
unrelated tests satisfy coverage: `test-reach-rules` derives `tested` from that
relation (`fn.clj:1493–1502`), and `functions-without-tests` consumes it
(`:1700–1724`). D1 requires an actual current reaching test and its existence in the
prior basis (`lane-d1-isolation-merge-writeback.md:344–349`). An all-test safety
selection is not proof of either. B1 itself requires explainable edge provenance
(`lane-b1-one-publication-path.md:523–533`).

**Replace the paragraph's fallback sentence with:**

> Preserve ownerless usages on the existing file row. If reverse reach encounters
> one without a proven dependent set, report coverage unknown and either widen
> execution selection to all eligible tests with that reason or refuse selection.
> Widening creates no call/reference edge and cannot satisfy test-present,
> test-first or functions-without-tests. Convert both consumers consistently;
> unknown remains unknown for positive coverage. The first slice may refuse rather
> than add a new widened-result mechanism. Excluded tests remain explicit missing
> evidence, never an implicitly successful gate.

Add a regression with an ownerless F reference and an unrelated prior test: the
request widens/refuses, but that test cannot establish F's test-first admission.
This fails the tempting global-fallback-edge implementation.

### P1-2 — Pending declarations need an explicit completion boundary and visible typos

The note correctly separates source storage from compilation and rejects global
lint suppression (`research:399–445`). Its proposed paragraph does not require
root-visible pending diagnostics or revalidation when a target appears. A symbol
acquiring a row does not prove valid arity, visibility or executable dependencies.
B1 still requires blocking findings before completed publication and caller lint
after signature changes (`lane-b1-one-publication-path.md:123–127`). D1 says to
reuse diagnostics in root's diff and merge (`lane-d1-isolation-merge-writeback.md:358–369`).

**Replace the pending-admission sentences with:**

> Only the shared experimental-branch test-declaration entrance may store an
> analyzed test whose qualified target resolves to a namespace but lacks a var.
> Keep every unresolved finding attached to its test/source identity and expose
> the derived pending targets in the declaration response and root's branch diff.
> This is stored source, not executable installation, completed publication or
> green evidence. Revalidate affected pending tests through the existing analyzer
> and caller lint when target declarations or resolver bindings change; existence
> alone does not clear pending status. Execution and merge refuse until all such
> diagnostics and ordinary admission checks are resolved. Ordinary functions and
> completed file publication retain their unresolved-var refusal.

This is simpler than introducing a new namespace-ownership permission model.
“Will later resolve” cannot be established at admission; it is a completion
condition. A typo may be stored as an explicitly pending test, but is immediately
visible and cannot silently become a valid program. Restricting targets to existing
branch-owned namespaces is an optional tighter policy only if that authority is
already supplied; a new ownership registry would not solve misspellings within an
owned namespace. Qualification is not evidence of spelling correctness.

Keep the proposed no-stub/no-bare-name-inference rule. Add negative cases for a
qualified misspelling in a known namespace (visible pending, merge refused), a
misspelled external library call in completed publication (refused), and a future
callee that resolves with incompatible arity or private access (still refused).
Use the analyzer's existing name coordinates to associate diagnostics; do not
“fix” `external-usage-spans` to suppress every externally named unresolved call
(`fn.clj:1114–1143`; kondo name-span rewrite at `K/linters.clj:538–543`).

### P1-3 — Broadening rows must preserve the existing execution guard and ownerless agent cases

“Coordinate with B2 before claiming isolation” is too weak as a landing condition.
Today `host-bound-declaration?` defaults an ordinary nonliteral def to false unless
its analyzed body contains a classified host operation (`fn.clj:895–928`). Simply
admitting rows therefore positively classifies many new initializers as interpretable.
SCI installation trusts that fact (`src/seon/sci/eval.clj:943–953,1020–1035`).
Also, agent `analyzed-form` returns no facts for a form without a program owner
(`fn.clj:1007–1009`); the file fallback is not automatically an agent-form fallback.

**Add after the callable-proof sentence:**

> The row-admission slice must also make the existing host-bound producer refuse
> affected reconstruction of newly retained nonliteral initializers until B2 proves
> it safe. Do not merely omit a fact after another producer supplied false. Prove
> ordinary loaded reuse, explicit refusal of unsafe affected overrides, and no
> inferred zero arity or constant status. An ownerless agent submission cannot
> disappear from durable-program analysis: use a real existing source owner where
> available; otherwise refuse its admission as a complete program declaration.
> This does not turn disposable REPL evaluation into a stored declaration.

No new execution mechanism is required: use the installed host-bound fact and
refusal owner. Both file and agent producers must agree. A map/delay test should
assert dependency facts and refusal of unsafe reconstruction, not attempt to prove
successful isolated invocation before B2 supplies it.

### P1-4 — State incremental costs honestly; do not build a fallback cross product

The proposed owner reduction can be linear in changed analysis. The assertion
that selection remains proportional only to reachable edges is not an installed
guarantee: `gate-sets-in` queries all function/test identities, all tests, declared
relations and file fallback relations before the walk (`fn.clj:1564–1588`). A
global test × unresolved-target join would make this worse.

**Replace the cost sentence with:**

> Additional row construction is O(changed definitions + usages + findings).
> Publication lint covers changed files and affected caller files using the
> dependency cache. The reverse walk follows reached edges; its existing global
> identity/relation setup is a separately reported cost, not claimed incremental.
> Seek unresolved file references for reached targets; never materialize every
> test against every unresolved target. Full-test enumeration is an explicit
> widening cost. Reanalysis for changed analyzer semantics is a declared migration
> over affected files, never a hidden scan on each edit.

The adapter already searches spans per usage (`analyzer.clj:439–482`), so the
linear bound describes the proposed additional reduction, not all analyzer work.
Likewise pending-target resolution revisits affected tests, not the whole program.
Require changed-file/caller counts and parent-versus-slice timings at implementation;
no new performance claim is established by this review.

### P2-1 — The 76 count is a qualified exposure count, not an exact reproducible defect census

The reduction's set difference is correct for its stated category
(`tmp/astra-def-body-index/count.clj:27–49`), and saved counts report 76 in both
language views. The analysis and script SHA-256 values match those published in
`research:177–181`. The note correctly excludes tests/scripts/resources and does
not call these 76 proven bad selections (`research:156–169`).

However, the classifier rereads live source to obtain top-level spans
(`count.clj:11–14`) after lint. Raw analysis alone is not the authoritative input
for that category, contrary to `research:34–35`. No captured source hash set proves
those bytes matched the analyzed bytes; I did not rerun the census. Method repair,
metadata/head usages and already admitted definitions also make the category an
exposure measure rather than an omitted-owner count.

**Replace “Raw analysis remains the authoritative census input” with:**

> The saved result is a reported src-only exposure census. Its classifier also
> read live source spans; without the matching captured source bytes it is not an
> independently reproducible exact count. Do not use 76 as a defect or savings
> count. Any acceptance census must lint and classify the same captured bytes.

Also change “literal defs” to “literal def forms (not a guarantee for defonce).”
No new census is required to establish the source-proven defect.

## Regression judgment

The proposed three-file F/g/test regression is the right class regression
(`research:288–301`). Positive owner and edge assertions fail without row retention;
selection alone would also pass an indiscriminate all-test implementation. Require
an unrelated known test to remain unselected in the same complete-graph fixture,
and assert the actual path/provenance. Keep separate cases for direct initializer
calls, `(memoize F)` references, defonce/delay and container callbacks. Preserve
arglisted/literal control cases and verify replacement removes an old edge and
installs the new one. Do not seed graph edges manually.

The ownerless regression must use separate files and prove widening/refusal plus
the absence of fabricated positive coverage (P1-1). The pending-test regression at
`research:447–457` is valuable: durable prior test, absent callee, prior-basis reach,
then strict function admission and successful execution. Its negative same-batch
case proves order. Add P1-2's typo/completion cases and prove a pending body is not
compiled during declaration or branch reacquisition. Source/agent parity is a row
construction assertion; it does not authorize unresolved completed file publication.

These are behavior regressions with distinct failure modes, not a new test runner.
The proposed no-observed-reach shortcut agrees with README §3 (`:102–106`), and
unconditional merge checks agree with current D1 (`:327–342`). No blanket weakening
to `:warn`, automatic definition stub, inferred runtime callability or second
test-first detector is justified.

## First implementation slice and files

**First: restore definition owners and make unresolved fallback honest.** Keep
pending-test admission as a subsequent D1 slice. Use the smaller coverage-refusal
option initially if widening cannot preserve positive-coverage semantics within
the existing result contracts. This first slice does not claim pending-test support
or safe replay of arbitrary nonliteral initializers.

Exact proposed ownership:

* `src/seon/fn.clj`: retain def/defonce owners in file construction and the eligible
  caller set; preserve agent construction parity; classify newly retained unsafe
  initializers with the existing host-bound fact; convert both fallback consumers
  without inventing reaching edges. Preserve missing-callee symbolic reach.
* `resources/seon/schemas/seon.fn.edn` and `src/seon/test.clj`: declare and propagate
  coverage-unknown at the existing result/selection boundary if the refusal option
  is used; do not disguise it as a database-read error or return an empty success.
* `test/seon/fn_test.clj`: canonical analyzer/publication and graph regressions,
  exact replacement, and file/agent parity; no hand-authored dependency facts.
* `test/seon/test/selection_test.clj`: actual selection across the three files,
  unrelated-test control and ownerless unknown behavior.
* `test/seon/sci/branch_execution_test.clj`: existing-consumer proof of loaded reuse
  and explicit affected-initializer refusal without executing its effects.
* `docs/prds/agent-platform/landing/lane-def-body-index-2026-09-23.md`: measured
  evidence, adoption/migration boundary, changed paths and net src/test lines.

The upstream attribution and current adapter need no new def-specific analyzer.
`src/seon/fn/analyzer.clj` is not a default edit in this slice. If consumer inspection
requires additional implementation files, price and review that conversion before
expanding ownership; do not silently launch a cross-owner reconstruction project.
The orchestrator must obtain exclusive file ownership before launch. The completed
repair must prove positive memoized-wrapper selection; the safety refusal alone is
an interim boundary, not completion of the entire proposal.

## Verification boundary

Docs-only static review: source/history inspection, upstream-base comparison,
saved-reduction inspection and SHA-256 verification. No JVM, REPL, tests, lint
census, publication, adoption, branch mutation or push. Review tool calls completed
below one second as reported by the shell tool; no runtime performance was measured.
Foreign shared-tree edits were observed and left untouched; they did not block this
review. Net production source **0**, test **0** lines; only this review is committed.
The first documentation patch call took 1.9 seconds including automatic Markdown
feedback spanning repository documents; this is tooling overhead, not a measured
Seon operation. That feedback reported 55 issues (45 errors); visible examples cite
historical dependency revisions in other landing notes. No repository-wide clean
Markdown result is claimed, and those files are outside this assignment.

Co-Authored-By: gpt-6-astra (Codex)
