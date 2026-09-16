---
type: research
status: active
tags: [research, test, database, performance]
---

# Reach digest — beat 1, 2026-09-16

**Beat 1 was reviewed; beat 2 implementation is committed as `f2d537187`. Integration boundaries remain below.**
The proposed guarantee is: a recorded green is reusable only when the test's
current declared dependency closure has exactly the same source, contracts,
and named schema forms as the closure that was tested.

That guarantee depends on complete recorded edges. Juniper falsifies treating
digest equality alone as proof of complete coverage: its current and historical
digests match while its tested function is absent from the recorded closure.

## Scope and authority

This is the explicitly assigned reach-digest probe, not a claim to close the
entire N7 or N9 class. Read end to end: root [AGENTS.md](../../../../AGENTS.md),
[issues README](../../../seon/issues/README.md), the
[class-mining report](../../sci-execution-runtime/research/issue-class-mining-2026-08-11.md),
[N7 class](../../../seon/issues/class-classification-is-inferred-from-hand-lists.md)
and its three current member notes (agent-form calls, config-dial discovery,
cluster namespace projection), and
[N9 class](../../../seon/issues/class-local-updates-recompute-global-projections.md)
and its five current member notes (AI context, complete publication, core
namespace page cost, repeated renderers, retained producer replacement).
The specific two-beat assignment supersedes the generic class-closure/gate
boilerplate. No member was re-fixed or closed in this beat.

N7's structural kill is “Record the missing fact, then query it”; N9's is
“Projection rides basis/generation and invalidates only the recorded dependency
closure.” This probe supplies evidence for the test owner's application of
those rules. The roadmap entry and current working-edge checkpoints were also read.
Skills used: data-oriented-clojure, repl, datahike.

## Captured world and dependency ledger

All calculations use one immutable database value, captured through MCP
`mode: jvm`, cluster `default`, root `/Users/sean/src/seon`:

```clojure
(seon.db/db (seon.operator/connection "default"))
```

Basis **536872536**, branch **:cluster-default**, recorded source commit
**6aa9dfe0-ab4c-58fa-892d-7bab168f2c65**, JVM PID **69622**.
Git HEAD observed during the probe was
`1c0f61feac12b374a9d3b8cca7cdfc67ac4c5a1e`; Git HEAD is not the database
program's identity. Concurrent adoption continued after capture.

| Owner | Evidence and use |
|---|---|
| Shared reach rules | `src/seon/fn.clj:787–843`: the existing `test-reach-rules`, `gate-set`, and `tests-reaching`. The census passes that exact rule Var to one bulk query. |
| Carried database projection | `src/seon/db.clj:981,1398`: use `db/db`, not raw connection dereference. Raw dereference returned no carried projection and a typed pull refusal. |
| Schema forms | `:seon.schema.projection/forms`; `resources/seon/schemas/seon.schema.edn:45–48` declares stored key/form. All 2,603 current carried forms equal the parsed current schema rows. |
| Historical database values | `reference-code/datahike/src/datahike/db.cljc:142–152`: as-of includes the basis. Historical forms are queried at that basis; its carried physical-schema projection is current. |
| Digest | `src/seon/id.clj:46`: `(seon.id/digest 64 parts)`, hashing the ordered printed data through the existing identity owner. |
| Result custody | `src/seon/test/runner.clj:1405–1472`: `provenance` records branch/basis and `record-tx` replaces latest result facts at the writer. A basis on another branch cannot be interpreted as default's history. |

The [probe source](reach-digest-probe-2026-09-16.clj) captures 6,462
function/test rows in one pull-many, derives all test reach pairs in one rule
query, groups that relation once, and computes digests without a query per
test. Datalog computes a fixed point internally; “one pass” here means one
bulk graph derivation, not a claim that recursion examines each edge once.

Digest parts include the test itself and every reached row as
`[symbol source spec]`, sorted by identity, followed by sorted named schema
forms. Specs are parsed with the EDN reader; keywords and schema aliases are
followed transitively. Nested unordered schema collections are canonicalized
before hashing. Calls and explicit subjects use the shared rules; resolved
pending subjects also follow calls, while unresolved pending subjects retain
an explicit marker. There were **zero pending subjects** in the captured
current database, so that branch is implemented in the probe but not a live
pending-subject coverage claim. Config/fixture files are not inferred from paths.
There were **zero recorded fixture-observation attributes** at this basis.

## Measured census

The requested approximately 1,659 tests are exactly **1,659 source-bearing
test rows**. There are additionally **94 test identities without source**:
1,753 total identities. These must not silently count as executable tests.

| Measure | Source-bearing tests | All test identities |
|---|---:|---:|
| Count | 1,659 | 1,753 |
| Empty reached-function set | 1 | 95 |
| Exactly one reached function | 0 | 0 |
| Minimum | 0 | 0 |
| Median | 668 | 410 |
| 90th percentile | 1,138 | 1,104 |
| 99th percentile | 1,882 | 1,882 |
| Maximum | 2,045 | 2,045 |

Counts above exclude the separately included test's own source row.
All identities together produce **907,605 test/function reach pairs**.
Named-schema counts over all identities: minimum 0, median 285,
90th percentile 757, maximum 1,343.

| Reached functions | Source-bearing tests |
|---|---:|
| 0 | 1 |
| 1 | 0 |
| 2–10 | 60 |
| 11–100 | 368 |
| 101–500 | 366 |
| 501–1,000 | 614 |
| 1,001–2,000 | 249 |
| Above 2,000 | 1 |

| Probe | Measured time |
|---|---:|
| Shared-rule reach relation alone, 907,605 pairs | 12,023.091 ms |
| Complete bulk capture + reach + schema closure + every digest | **39,282.496 ms** |
| Alternative in-memory traversal and all digests; equivalence check | 46,318.788 ms |
| Historical comparisons: 130 bases, 176 green rows, serial batches | 484,712.647 ms summed evaluation work |

These are single live measurements under concurrent development load, not
performance gates. The complete pass includes the 94 source-less rows. The
prototype is too expensive to repeat blindly on every local check. Before
beat 2, preserve shared graph/schema derivation and avoid repeating schema
canonicalization or printing common closures; do not replace this with one
database query per test.

The historical traversal captures each historical graph once and walks the
selected roots in memory. Its complete current result was compared with the
shared Datalog rule result for **every one of 1,753 tests**: all target sets
and all digests equal. It exists only in the research probe to avoid paying
the full Datalog fixed point 130 more times, not as a second production selector.

## Empty/small closure verdicts

The only source-bearing empty closure is
`seon.test-runner-failure-fixture/assertionless-example`.
Its exact source at `test/seon/test_runner_failure_fixture.clj:11` is
`(deftest assertionless-example nil)`; the runner's explicit assertionless
regression references it at `test/seon/test_runner_test.clj:766–774`.
This is an intentional negative fixture, not an inferred healthy test.

**Juniper does not satisfy the requested empty-or-single-edge heuristic.**
It has three recorded edges and zero named schemas, so that heuristic misses
the known defect. Its source says:

```clojure
(deftest largest-customer-test
  (let [rows [[42195 "Ada" 60] [42196 "Ada" 55] [42197 "Bea" 100] [42198 "Cy" 40]]]
    (is (= {:customer "Ada" :total 115} (largest-customer rows)))))
```

Test identity `my.agents.juniper/largest-customer-test`, entity **42768**,
has only `clojure.test/is`, `clojure.core/let`, and `clojure.core/=`
(edges 3630, 3237, 3380). It has no subject or pending-subject fact.
`my.agents.juniper/largest-customer` is absent from the closure.
The function row itself exists as entity **42547**; the existing
`seon.fn/tests-reaching` returned `[]` for that function on the captured value.

Its current digest and reconstructed digest at run basis **536871342**
are both:

```text
3b0fdce40c7fd0aa752f1eff4363421b4202793dea68208bca2b4657549b19a5
```

The n7 ordinary-evaluation repair does not backfill this historical test row.
This probe does not establish whether a freshly admitted equivalent test is
now correct; that is the explicitly required beat 2/n7 proof. The existing
[edge issue](../../../seon/issues/agent-form-calls-to-core-namespaces-are-not-indexed.md)
records this remaining evidence. No hand-written edge was inserted.

The other **94 empty closures all lack test source**. Their identities are
listed below so absence cannot be mistaken for coverage. This probe does not
attribute each source-less identity to deletion, failed indexing, or result
import; identity-only rows are permitted by the program model.

<details>
<summary>All 94 source-less empty closures at basis 536872536</summary>

- `seon.cluster.agent-test/install-gate-core-fault-reaches-flow-with-a-live-backstop`
- `seon.cluster.mcp-test/jvm-exceptions-retain-the-root-location-and-flat-error`
- `seon.cluster.mcp-test/ordinary-value-artifacts-drill-from-the-result-root`
- `seon.cluster.mcp-test/oversized-values-share-one-digest-across-storeless-and-stored-modes`
- `seon.cluster.mcp-test/retrievable-artifacts-have-an-identified-no-history-root`
- `seon.cluster.mcp-test/sci-value-artifacts-drill-from-the-result-root`
- `seon.cluster.mcp-test/stored-strings-page-by-character-offset`
- `seon.contracts-plan-test/missing-note-id-names-the-key-and-the-docstring-example`
- `seon.contracts-plan-test/run11-about-refusal-names-the-attribute-and-reference-shape`
- `seon.contracts-plan-test/run7-reader-refusal-uses-the-shared-grammar`
- `seon.contracts-plan-test/unresolved-symbol-shows-the-correction-without-the-evidence-map`
- `seon.db-test/ten-unhanded-queries-stay-within-twice-raw-query-cost`
- `seon.db.declaration-population-test/a-read-resolves-the-declaration-population-at-most-once`
- `seon.db.declaration-population-test/a-read-that-decodes-nothing-resolves-nothing`
- `seon.db.declaration-population-test/edn-backed-attributes-still-round-trip`
- `seon.problems-test/projection-twins-preserve-the-generated-family-structure`
- `seon.render-simplification-test/nested-values-render-their-declared-faces`
- `seon.render.retained-test/identical-database-retains-reads-without-replaying-them`
- `seon.render.value-test/declared-pairs-render-inside-response-values`
- `seon.render.web-context-test/adoption-invalidates-pages-with-an-unchanged-sci-snapshot`
- `seon.render.web-test/render-proc-one-derivation-many-tabs-test`
- `seon.render.web-test/the-pass-oracle-observes-a-derivation-longer-than-flows-ping-window`
- `seon.repl-parity-test/parity-a1`
- `seon.repl-parity-test/parity-a10`
- `seon.repl-parity-test/parity-a2`
- `seon.repl-parity-test/parity-a3`
- `seon.repl-parity-test/parity-a4`
- `seon.repl-parity-test/parity-a5`
- `seon.repl-parity-test/parity-a6`
- `seon.repl-parity-test/parity-a8`
- `seon.repl-parity-test/parity-a9`
- `seon.repl-parity-test/parity-b1`
- `seon.repl-parity-test/parity-b10`
- `seon.repl-parity-test/parity-b11`
- `seon.repl-parity-test/parity-b2`
- `seon.repl-parity-test/parity-b3`
- `seon.repl-parity-test/parity-b4`
- `seon.repl-parity-test/parity-b5`
- `seon.repl-parity-test/parity-b6`
- `seon.repl-parity-test/parity-b7`
- `seon.repl-parity-test/parity-b8`
- `seon.repl-parity-test/parity-b9`
- `seon.repl-parity-test/parity-c1`
- `seon.repl-parity-test/parity-c2`
- `seon.repl-parity-test/parity-c3`
- `seon.repl-parity-test/parity-c4`
- `seon.repl-parity-test/parity-c5`
- `seon.repl-parity-test/parity-c6`
- `seon.repl-parity-test/parity-c7`
- `seon.repl-parity-test/parity-c8`
- `seon.repl-parity-test/parity-d1`
- `seon.repl-parity-test/parity-d11`
- `seon.repl-parity-test/parity-d2`
- `seon.repl-parity-test/parity-d3`
- `seon.repl-parity-test/parity-d4`
- `seon.repl-parity-test/parity-d5`
- `seon.repl-parity-test/parity-d6`
- `seon.repl-parity-test/parity-d7`
- `seon.repl-parity-test/parity-d8`
- `seon.repl-parity-test/parity-d9`
- `seon.repl-parity-test/parity-e11`
- `seon.repl-parity-test/parity-e12`
- `seon.repl-parity-test/parity-e13`
- `seon.repl-parity-test/parity-e14`
- `seon.repl-parity-test/parity-e2`
- `seon.repl-parity-test/parity-e3`
- `seon.repl-parity-test/parity-e4`
- `seon.repl-parity-test/parity-e6`
- `seon.repl-parity-test/parity-e7`
- `seon.repl-parity-test/parity-e8`
- `seon.repl-parity-test/parity-f1`
- `seon.repl-parity-test/parity-f2`
- `seon.repl-parity-test/parity-f3`
- `seon.repl-parity-test/parity-f4`
- `seon.repl-parity-test/parity-g1`
- `seon.repl-parity-test/parity-g10`
- `seon.repl-parity-test/parity-g2`
- `seon.repl-parity-test/parity-g3`
- `seon.repl-parity-test/parity-g4`
- `seon.repl-parity-test/parity-g6`
- `seon.repl-parity-test/parity-g7`
- `seon.repl-parity-test/parity-g8`
- `seon.repl-parity-test/parity-h1`
- `seon.repl-parity-test/parity-h2`
- `seon.repl-parity-test/parity-h3`
- `seon.repl-parity-test/parity-h4`
- `seon.repl-parity-test/parity-h5`
- `seon.repl-parity-test/parity-h6`
- `seon.repl-parity-test/parity-h7`
- `seon.repl-parity-test/parity-h8`
- `seon.repl-parity-test/parity-i6`
- `seon.sci.reader-test/invalid-prose-tokens-recover-at-token-granularity`
- `seon.search-test/document-roster-is-the-declared-search-property-query`
- `seon.test-reaching-test/adoption-check-runs-ordinary-tests-and-defers-declared-observations`

</details>

## Reconstructed green reuse

Latest result facts: **526** rows = **505 green**, **21 red**; **1,227**
identities have no result. Source-bearing rows account for 420 green and
12 red; the remaining 85 green and 9 red rows lack source.

| Source-bearing green result | Count | Verdict |
|---|---:|---|
| Same branch, reconstructed closure equal | **77** | Conditional reuse candidates; includes the known incomplete Juniper graph |
| Same branch, reconstructed closure changed | **98** | Would need rerun |
| Different recorded branch | **245** | Unknown from default's as-of history |

For completeness, comparison of **all** same-branch greens gives
**78 equal / 98 changed**, across **130 distinct run bases**.
The extra equal result lacks source:
`seon.test-reaching-test/adoption-check-runs-ordinary-tests-and-defers-declared-observations`
at basis **536872516**. It must not be called verified executable coverage.

All 176 compared historical test identities were present. The remaining
**329** green results overall belong to 15 other recorded build branches;
their numeric bases cannot be used against default as if they were the same
history. The 245 source-bearing subset is included in the table above.

**No historical reach digest was recorded.** These numbers compare today's
digest with a digest reconstructed from same-branch `seon.db/as-of` at
the run's recorded basis. They do not prove which hot-loaded Var bodies the
past JVM executed, supply missing edges, validate undeclared fixture inputs,
or establish availability of the other branches. Consequently this is a
conditional reuse census, not 77 newly verified test results.

A direct probe established that current/as-of carried projections were
identical while historical stored forms numbered 2,563 versus 2,603 current.
Using the current carried forms for both sides would conceal schema changes.
The historical helper therefore reads schema forms at the historical basis.

## Verification and exact stop boundary

No production function, schema, runner, or selector was edited. No
`seon.test/run`, `seon.test/check`, `bin/test`, or `bin/test-fast`
invocation occurred. This is the requested probe-only beat; in-process
regressions and the orchestrator gate belong to beat 2. No gate request is
claimed or needed for production changes in beat 1.

Probe definitions were evaluated through MCP before their research file was
written. Complete returned values were inspected for the census summaries,
Juniper, and every historical batch. Large census envelopes were explicitly
elided by the MCP render profile; their small summaries were re-queried and
the full numeric distributions exported locally for this note, not inferred
from clipped output. The final `reach-closure` Juniper probe returned the
same digest, 3 functions, and 0 schemas after the research-helper cleanup.
This was a loaded research Var proof, not production adoption or a canonical
test result. No canonical regression has been claimed for pending subjects.

The mandated hyphenated dated filename is not a Clojure classpath filename;
the probe locally suppresses only namespace-name-mismatch, with the reason
beside the namespace. Other lint findings were corrected. Research file
syntax/lint and path-limited diff checks are the validation for this artifact.
Final research-file `load-file` plus the Juniper probe returned the same
digest/counts in **700 ms**. `clj-kondo --lint` on the probe returned
**0 errors / 0 warnings**; both owned Markdown files passed the existing
`seon.dev.markdown/validate-file` with no violations. The repository-wide
Markdown hook separately reported **12 dependency-pin findings**, including
`docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`
lines 226, 227, 232, and 234. Those are outside these owned artifacts; the
global hook is not claimed green. Its feedback was elided after the first
findings, so this note does not attribute all 12 to one file.

Default was never stopped, restarted, or reforked. MCP occasionally returned
cluster-state unknown alongside complete ret events; subsequent calls reported
alive. This is a health-observation boundary, not a lost evaluation result.
The namespace-local probe objects are disposable and removed after export.
No background shell, scratch cluster, or worktree was created.

At launch, protected edits were `script/seon/fresh_operator.clj` and
`test/seon/dev/hook_test.clj`. Repeated status checks observed additional
foreign MCP, cluster, analyzer, SCI, test-owner/schema, and test-file edits.
All were preserved. No foreign lane's session was operated. The commit owns
only this note, its probe source, and the appended evidence in the existing
edge issue.

## Full source-bearing closure-size distribution

Dated frequencies, derived from the captured database (size → test count):

```edn
{0 1, 2 1, 3 7, 4 1, 5 6, 6 8, 7 11, 8 10, 9 5, 10 11, 11 6, 12 17, 13 8, 14 8, 15 9, 16 7, 17 7, 18 3, 19 8, 20 7, 21 3, 22 6, 23 5, 24 4, 25 4, 26 6, 27 6, 28 4, 29 4, 30 3, 31 3, 32 3, 33 1, 34 1, 35 4, 36 5, 37 3, 38 2, 39 3, 40 3, 41 2, 42 1, 43 2, 44 2, 45 2, 46 4, 47 2, 48 1, 49 2, 50 2, 51 4, 52 2, 53 9, 54 2, 55 4, 56 1, 57 5, 58 5, 59 4, 60 2, 61 5, 62 5, 63 6, 64 4, 65 5, 66 1, 67 5, 68 2, 69 2, 70 1, 71 6, 72 3, 75 1, 76 5, 78 8, 79 5, 80 3, 81 3, 82 6, 83 5, 84 6, 85 7, 86 8, 87 5, 88 7, 89 4, 90 3, 91 1, 92 7, 93 3, 94 6, 95 5, 96 5, 97 1, 98 3, 99 3, 100 2, 101 4, 103 1, 104 2, 106 2, 107 1, 110 1, 112 1, 113 2, 115 1, 116 1, 117 3, 118 2, 119 1, 120 2, 122 2, 123 1, 124 2, 125 1, 129 1, 130 1, 132 3, 134 2, 136 1, 141 1, 144 1, 145 5, 146 3, 147 1, 148 2, 149 5, 150 2, 151 2, 152 3, 153 3, 154 1, 155 4, 156 6, 157 5, 158 8, 159 5, 160 4, 161 2, 162 1, 163 2, 164 1, 171 1, 173 4, 176 2, 177 4, 178 2, 179 1, 180 3, 181 4, 182 1, 183 2, 184 4, 185 2, 186 1, 187 2, 188 2, 189 1, 190 5, 191 5, 192 3, 195 4, 196 2, 197 1, 200 2, 201 2, 203 2, 204 1, 205 5, 206 4, 207 4, 208 3, 209 1, 210 3, 211 2, 212 3, 213 6, 214 5, 217 2, 218 3, 219 1, 220 2, 222 2, 224 1, 225 2, 226 1, 228 1, 229 1, 233 1, 236 1, 239 1, 243 1, 244 2, 246 1, 247 2, 248 1, 249 3, 250 2, 251 4, 252 2, 253 1, 254 1, 256 1, 258 1, 259 3, 263 1, 265 1, 266 1, 269 1, 270 1, 272 3, 273 2, 274 4, 275 1, 276 1, 277 3, 278 2, 279 2, 280 2, 282 1, 291 1, 292 1, 296 1, 297 3, 299 8, 300 2, 301 1, 303 1, 304 1, 306 1, 307 1, 308 1, 310 2, 311 1, 313 1, 316 1, 323 1, 324 1, 325 1, 326 4, 327 3, 328 2, 329 1, 330 2, 337 1, 338 1, 343 1, 344 1, 347 1, 354 1, 359 1, 360 2, 364 1, 365 1, 376 3, 377 1, 378 1, 380 1, 382 1, 383 3, 385 2, 386 1, 387 1, 389 1, 390 2, 391 1, 393 1, 395 1, 398 1, 399 1, 400 2, 406 1, 408 1, 410 2, 412 4, 413 2, 415 1, 422 1, 426 1, 465 1, 488 1, 532 1, 563 1, 564 2, 565 1, 650 1, 667 22, 668 20, 669 17, 670 17, 671 15, 672 13, 673 10, 674 7, 675 4, 676 4, 677 6, 678 8, 679 10, 680 6, 681 6, 682 8, 683 6, 684 10, 685 6, 686 2, 687 9, 688 5, 689 6, 690 3, 691 3, 692 5, 693 7, 694 18, 695 8, 696 8, 697 4, 698 7, 699 6, 700 3, 701 5, 702 8, 703 3, 704 1, 705 2, 706 4, 707 1, 708 5, 709 5, 710 3, 711 4, 712 4, 713 4, 714 4, 715 3, 716 4, 717 6, 719 1, 720 1, 721 3, 722 3, 726 2, 729 2, 730 2, 731 3, 732 6, 733 5, 734 6, 735 5, 736 3, 737 1, 738 1, 739 4, 740 3, 741 1, 742 3, 743 4, 744 1, 745 1, 747 3, 748 1, 750 1, 751 5, 752 1, 754 1, 755 1, 758 1, 759 1, 762 4, 763 2, 765 3, 766 1, 769 1, 770 2, 773 2, 778 1, 779 2, 780 2, 781 1, 787 1, 789 1, 790 1, 791 1, 792 1, 794 3, 795 2, 796 1, 798 1, 801 4, 802 1, 804 5, 805 1, 806 1, 807 1, 808 1, 809 1, 810 1, 814 1, 816 3, 817 3, 818 3, 820 2, 821 2, 822 2, 823 1, 824 1, 827 1, 828 1, 832 1, 834 1, 839 1, 841 1, 842 1, 844 1, 846 1, 847 1, 848 1, 849 1, 851 1, 853 1, 854 1, 861 1, 870 1, 871 1, 874 1, 875 1, 876 1, 881 1, 882 1, 887 1, 892 2, 894 1, 895 2, 898 1, 900 1, 907 1, 909 1, 910 1, 911 1, 912 1, 915 1, 919 2, 920 2, 921 4, 922 1, 923 1, 927 1, 929 1, 931 11, 934 1, 936 1, 937 1, 941 2, 946 1, 952 1, 957 1, 958 1, 963 1, 965 2, 966 2, 967 1, 969 1, 970 7, 971 5, 972 1, 973 1, 974 1, 975 1, 976 2, 981 1, 982 2, 983 1, 991 1, 993 4, 995 2, 998 2, 999 1, 1000 2, 1001 1, 1002 1, 1003 1, 1005 2, 1006 3, 1007 1, 1008 4, 1009 1, 1010 1, 1012 1, 1018 1, 1019 1, 1021 1, 1022 1, 1027 1, 1028 2, 1030 2, 1031 1, 1035 2, 1037 1, 1038 1, 1039 1, 1046 2, 1048 1, 1049 1, 1050 2, 1052 1, 1057 1, 1061 6, 1062 1, 1063 1, 1064 1, 1065 1, 1066 2, 1067 1, 1069 1, 1070 2, 1074 1, 1077 3, 1078 1, 1080 2, 1081 1, 1084 1, 1090 1, 1091 1, 1096 1, 1097 2, 1098 1, 1099 1, 1100 1, 1103 1, 1104 3, 1109 1, 1114 2, 1115 1, 1127 1, 1128 1, 1136 1, 1138 5, 1139 2, 1140 1, 1141 1, 1145 2, 1149 2, 1150 1, 1215 1, 1221 1, 1229 1, 1250 1, 1309 1, 1324 1, 1344 1, 1352 1, 1353 1, 1355 1, 1388 1, 1403 1, 1406 1, 1427 1, 1429 1, 1447 1, 1452 1, 1471 1, 1473 1, 1475 2, 1476 2, 1491 1, 1495 1, 1496 4, 1498 11, 1499 11, 1500 7, 1501 7, 1502 1, 1506 1, 1508 1, 1511 1, 1514 2, 1518 1, 1520 1, 1521 1, 1535 8, 1536 5, 1537 4, 1540 1, 1541 3, 1542 4, 1543 1, 1544 1, 1545 2, 1546 4, 1547 2, 1549 1, 1555 1, 1556 3, 1558 2, 1720 1, 1722 1, 1727 1, 1733 1, 1740 1, 1742 1, 1743 1, 1873 1, 1874 1, 1878 1, 1879 2, 1880 6, 1881 1, 1882 2, 1883 4, 1884 3, 1885 3, 1886 1, 1887 2, 1890 1, 1902 1, 2045 1}
```

## Beat 2 — implementation and live probes

Beat 2 is authorized. The owning guarantee is: **a source-bearing test's
recorded result is reusable when its recorded digest equals the current
source, contract, and named-schema closure; declared fixture observations
remain selected every time.** This guarantee requires complete analysis
edges. The fresh declaration regression below falsifies that prerequisite
for an unqualified agent-local call, so this is not closure of N7.

The implementation accretes `seon.test/verified?` with its two-argument
arity and retains the three-argument program-digest arity. `stale` selects
missing or changed results; `check` without `:seon.test/changed` uses that
selection and prints the skipped count and reason. Red unchanged results
are not automatically rerun; `verified?` still requires positive passing
assertions and zero failures/errors. A fixture-observation result can
describe a green latest execution while remaining always stale for selection.

### Incremental derivation

`src/seon/test/runner.clj` captures function/test rows and schema rows once,
hashes each source/spec tuple once, and derives each schema's transitive
closure once. Each cached test retains its reached identities, including
unresolved named schema/subject identities. A later database value reads
only relevant datoms since the cached basis, replaces changed rows, and
invalidates only intersecting test closures. Result-only writes invalidate
none. Selection does not derive digests for tests which have no digest-bearing
result: those are already known to require execution.

The cache rides the database's carried projection-state metadata, with the
existing projection-owned compiled cache as fallback. There is no global
registry. It retains one latest committed basis; historical or speculative
values derive independently without replacing that basis. The dependency
owner `seon.db/committed-value-identity` distinguishes speculative values.
Historical schema forms come from historical rows, not a current physical
schema projection. Digests hash sorted symbol/leaf-digest and schema/leaf-digest
pairs through `seon.id/digest`; leaf hashes avoid repeatedly printing large
shared sources. Equality has the same declared membership as beat 1, but
the digest encoding is new and is not compared to a stored beat-1 digest.

The writer `record-tx` adds the result digest beside counts and run provenance.
In-process execution captures its database before running. A live database
cannot itself cross transaction normalization: the first prototype caused
`empty is not supported on Datom`. `commit-results!` now derives ordinary
digest data from that captured value before transaction transport. The
prepared gate base supplies the corresponding evidence across JVM boundaries;
its program digest must match the run before transport. No test JVM was
launched to exercise that external transport.

### Measured costs

Measured through MCP JVM mode on default, PID **7595**, using the checked-in
functions hot-loaded into that JVM. These are not claims of completed
development adoption.

| Probe | Result |
|---|---:|
| Cold initialization, one immutable default value at basis 536871202 | 1,670 tests; 9,136 rows; 2,658 schema closures; **6,032.810 ms** |
| Same value, all 1,670 cached digests | **1.503 ms**, zero recomputed |
| Stale census after a result-only transaction, basis 536871203 | **49.278 ms**, 1,661 stale; zero rows updated, closures invalidated, or digests recomputed |
| Canonical fixture: change only `seon.id/valid?` source, compare all 1,672 test digests | **56.226 ms**, one row updated, exactly three digests invalidated and recomputed |
| Default explicit execution of two `seon.id-test` tests | **8,315.808 ms**, 13 passing assertions; run 45256 |
| Consecutive no-change default check | **33.315 ms**, zero executed, two skipped |
| Next no-change default check | **2.648 ms**, zero executed, two skipped |

The three changed digests in the canonical fixture were exactly:

- `seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol`
- `seon.id-test/data-shape-and-explicit-length-determine-identity`
- `seon.schedule-test/returned-and-thrown-handler-errors-use-the-existing-root-wake`

The fixture census differs from default because the values contain different
source populations. No historical digest has been invented. Full cold
initialization is a census probe, not the unchanged-check path.

Both live no-change checks printed
`Skipped 2 tests: recorded result has an unchanged reach digest`.
They used the real default connection, with a scoped canonical fixture base
replacement because the process's existing fixture delay retained an
`InterruptedException`. The replacement was acquired by the existing
`seon.test-support/create-base` owner, not hand-rostered. No fixture source
or default process was reset by this lane.

### In-process regression evidence

The recurring invocation is
`(seon.test/run #'<test> (seon.operator/connection "default") options)`,
where options carry the captured database, its `runner/provenance`, and
a 120,000 ms bound. The earlier default 20,000 ms bound fired on the first
reuse probe. The research probe records the full scoped arming/base setup.
Each changed function was evaluated before its source edit; returned values
and full failure messages were inspected.

| Test in `seon.test-reaching-test` | Before source edit | After source edit / loaded definitions |
|---|---|---|
| `reach-digests-follow-only-changed-closures` | 19/0/0, runs 68208, 68281, 68286 | 19/0/0, final run 57600, basis 536871355 |
| `unchanged-closures-reuse-green-results` | 11/0/0, runs 68279 and 68289 | 11/0/0, final run 59924, basis 536871385; earlier run 43560 detected four removed wrappers |
| `transported-results-retain-the-tested-database-digest` | 6/0/0, runs 68284 and 68296 | 6/0/0, run 57601, basis 536871357 |
| `fixture-observations-remain-stale` | 4/0/0, runs 43562 and 51128; final semantics preserve green verification while always selecting the fixture | 4/0/0, run 57602, basis 536871358 |
| `in-process-results-detect-worker-global-drift` | 3/0/0, run 43564; deliberately removes the real digest wrapper inside the preserving fixture | 3/0/0, run 57603, basis 536871359 |
| `failed-results-with-native-datoms-remain-recordable` | 4/0/0, run 43601 | 4/0/0, run 59930, basis 536871399 |
| `agent-admitted-tests-reach-their-tested-function` | **5/2/0**, run 45227, using real SCI evaluation plus production `analyze-forms` | Expected analysis-owner residual |

Counts are pass/fail/error. Failed exploratory probes are not omitted:
the first closure fixture lacked the required function namespace and failed;
a malformed candidate did not compile; the initial reuse probe hit its
20-second bound; an older fixture contract refused the new verified arity;
and drift detection reported concurrently added/removed wrappers. The first
agent probe omitted `analyze-forms` and is not production-admission evidence.
The corrected probe still lacks the tested function edge.

### Per-member verdict and exact boundaries

- **Repeated complete reach work:** incremental derivation verified by the
  one-row/three-digest measurement and the class regression.
- **Missing fresh or historical declaration edges:** open. The corrected
  agent regression persists only `clojure.core/=` and `clojure.test/is`
  calls. Changing the tested function leaves digest
  `8aae56c01668c6853a09877504aba1739ec48c1aa18eb25b0372984ec5c04b2a`
  equal. Exact bytes and run evidence are in
  [the existing call-edge issue](../../../seon/issues/agent-form-calls-to-core-namespaces-are-not-indexed.md).
  No synthetic edge, inferred subject, or protected analyzer/turn edit exists.
- **Native Datom recording failure:** the live database in the preliminary
  completion map was the measured normalization trigger. The corrected
  transport and the real-Datom failing-test regression record the original
  test diagnostic. Committed in `f2d537187`; final hot-loaded regression run 59930 passed 4/0/0. No complete adoption claim.
- **Full-publication result preservation:** required change remains outside
  this slice while `src/seon/cluster/source.clj` has concurrent edits.
  Its `result-preservation-tx` selector must conditionally include
  `:seon.test/reach-digest` when installed. A prototype passed 3/0/0,
  run 43599. Its file hunk and temporary Var were withdrawn after discovering
  the concurrent edits. Without it, a complete publication conservatively
  makes prior results stale; it does not falsely reuse them.
- **Schema identity:** direct aliasing of identity-bearing
  `:seon.source/digest` was corrected to
  `[:and {:seon.db/identity false} :seon.source/digest]`.
  A fresh canonical base has a plain scalar string attribute. Default had
  already installed the earlier unique declaration; the final 02:20Z probe still reports `:db/unique :db.unique/identity`.
  **RESET NEEDED for `f2d537187`** in the orchestrator's next authorized batch; this lane never resets default.
- **Adoption and fixture availability:** the default JVM changed from 69622
  to 7595 while this lane was active; no lane stop/refork/restart command
  occurred. Before that change, publication hit the concurrent native-tuple
  and program-identity contract work. Later publication reported unresolved
  namespaces in `test/seon/issue_test.clj`. The manual adoption request exited 1 after **900,066 ms**,
  without acquiring the operator lifecycle lock; no convergence is claimed.
  The new default initially had no historical Juniper row.

### Gate request

The orchestrator owns the batched isolated gate. This lane ran no
`bin/test`, `bin/test-fast`, or test JVM. Request
`seon.test-reaching-test`, `seon.test-runner-test`, then the platform tier,
with the owned paths in `tmp/orchestrator/gate-requests/reach-digest.txt`.
The fresh agent-edge regression is deliberately an unresolved dependency,
not a green gate claim. The result-publication selector change must be
integrated by its current owner before complete-publication reuse is proven.

### Final slice evidence and remaining live proof

Implementation commit: `f2d537187`. The final post-edit observer regression
`seon.test-reaching-test/fixture-state-observation-is-total` passed **7/0/0**,
run **57596**, basis **536871350**; its pre-edit run **55954** also passed
7/0/0. The existing result replacement regression
`seon.test-runner-test/result-facts-live-on-the-test-row-and-reruns-replace-them`
passed **8/0/0**, run **59926**, basis **536871393**. The native-Datom
repeat first reported 3/1/1, run 59925, because the detector observed 708
removed wrappers; its next run 59930 passed 4/0/0. This is observed drift,
not an attributed cause or a suppressed failure.

The existing observer now takes an explicit base delay, does not force an
unrealized delay, and returns an explicit unavailable observation for a
memoized acquisition failure. The shared failed delay is not reset. This
fix and the Datom transport fix each have their own observable regression.

The proof is **hot-loaded definitions in default**, with contracts armed
against the real canonical fixture projection. The final default source fact
was `6aa9fc8a-9227-50a6-a4e3-cc7d3e673fb3`; it was not verified equal to the
publication head. A zero-argument `source/current` exploratory probe was
correctly refused by its contract (it requires a store), and a subsequent
ambient-snapshot probe reached its 20,000 ms MCP bound. Neither is adoption
evidence. The two consecutive zero-test checks are live-default evidence;
the one-function change measurement is a canonical database source edit,
**not a completed file-edit/adoption/check proof on default**. That exact
live proof remains in the orchestrator gate request with the schema reset
and protected publication selector boundary. No test JVM was launched.

All owned implementation paths are in the implementation commit. The foreign
entity-render metadata in the shared schema was committed by its owner before
this commit; it is not part of this lane's diff. No protected analyzer,
program schema, turn, source-publication, or fixture-owner change is included.
The class remains open while the recorded agent edge is absent.

Final static checks: `git diff --check` passed for the owned changes.
`clj-kondo --lint` over the five implementation/test paths and replay capsule
reported zero errors, four warnings (two pre-existing shadowed bindings, one
pre-existing unused private function, and one unused test binding), plus one
qualified `t/is` informational finding; exit 2 reflects those warnings.
The owned canonical base was closed through `close-base!`; retained probe
values were unmapped. No owned background shell or scratch cluster remains.

## Batch 19 triage — 2026-09-16

Triage of the batch-19 isolated gate result
(`tmp/orchestrator/gate-results/batch-19/reach-digest.md`), which ran the cold
snapshot `74b46eeb4`. Every run below is in-process on default (JVM pid 7595)
through `(seon.test/run #'<test> (seon.operator/connection "default") options)`
with `:seon.test/remaining-ms 110000`; the default 20,000 ms bound fired once
on the deferral regression and is recorded as such. No test JVM was launched,
and default was never stopped, restarted, or reforked.

| Test | In-process at HEAD | Attribution | Fix |
|---|---|---|---|
| `seon.test-reaching-test/declared-observations-defer-before-cheap-reaching-tests` (7 blocks) | first attempt errored on the 20,000 ms bound while `datahike.writer` logged `:datahike/write-rejected {:kind :entity-id/missing}`; after the fix **9/0/0**, run 67427, basis 536871642 | **Not reach-digest.** `f2d537187` never touched this regression. `3402913f3 Index source file spans and lint findings as program facts` added `:seon.fn/file [:seon.fn.file/path …]` to every indexed test row (`src/seon/fn.clj:369`, `src/seon/fn.clj:389`), so the regression's single-row transact carried a dangling lookup ref | Fixed at this lane's own test owner in `e441e0263` |
| `seon.test-reaching-test/agent-admitted-tests-reach-their-tested-function` (2 blocks) | **7/0/0**, runs 67429 and 67441 (run 67429 additionally reported foreign wrapper drift adding `seon.test.runner/commit-results!`; run 67441 was clean) | **Pre-existing, owned by lane agent-call-edges.** It passes in-process only because that lane's *uncommitted* `src/seon/fn.clj` and `src/seon/fn/analyzer.clj` (now exposing `program-prelude` as kondo namespace context) are hot-loaded into pid 7595. At committed HEAD the agent-local call edge is still absent | Left red. Not this lane's owner; see [the call-edge issue](../../../seon/issues/agent-form-calls-to-core-namespaces-are-not-indexed.md) |
| `seon.test-runner-test/the-agent-fork-callable-returns-the-committed-projection` | **1/0/0**, run 66112, basis 536871623 | **Caused by reach-digest.** `f2d537187` added `:seon.test/reach-digest` to `record-tx`; the regression's pull selector omitted it | Already fixed at HEAD by `1b5c09e15` |
| `seon.test-runner-test/concurrent-bin-test-invocations-both-reach-their-tallies` | **Not run** — the regression launches two real `bin/test` subprocesses, which this triage is forbidden to do | **Caused by reach-digest.** The `f2d537187` `completion-reach-digests` opened the shared published base store (`-Dseon.test.published-base`, `bin/test:714`/`bin/test:776`) under its lifetime `flock` on every `commit-results!`. The gate's own notices name that exact path: `the store at …/target/test-published-bases/c5ef…/base/data/store is held by another live process`, and the coordinator's `:seon.fresh-operator/prepl-response-silent` after 30,000 ms is the same contention. Both nested gates therefore exited non-zero at the `exitValue` assertion | Already fixed at HEAD by `1b5c09e15`, which replaced the store open with the canonical private fixture. Its regression `concurrent-completions-use-the-canonical-program` passed **4/0/0**, run 67440, basis 536871649 |

Verification boundary: the two `1b5c09e15` attributions are proven by the
committed diff plus the in-process runs above; the cold two-process
`bin/test` path itself remains the orchestrator's gate to re-observe. The
agent-call-edges red will only clear cold once that lane commits its analyzer
work.
