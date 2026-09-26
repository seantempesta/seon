---
type: design-review
status: complete
created: 2026-09-26
reviewed-commit: fb87019a7
source-pin: 0818f4497
verdict: REVISE
recommendation: B after the findings below are resolved
scope: language-plan status, documentation ownership, parsing, detector proof, migration and option costs; read-only review
---

# Docs in code — independent review

Default remains `:faulted`: `seon.schema/valid-candidate-value?`, signature
`fa60ad84404c9720c3e6cd72ecdba9756f92bde2721e5568cf43d96d54a72aa3`.
Both status entrances reported pid 80197, started 2026-09-26T17:26:52Z. This existing
fault did not prevent the bounded reads below; it is not evidence about this design.

The design places documentation and links at their existing producers and uses the
existing database, render and write-back owners. That matches the owner's direction.
The proposed Markdown dependency supplies the required structure without a second
Clojure analyzer. The remaining decisions concern what a detector proves, which
record owns migrated work, and the prerequisites of the stated guarantees.

Read in order: `CLAUDE.md`, `language-requirements-2026-09-24.md`, and the complete
design. The design's checkout bytes matched **fb87019a7**. In this review,
`design:N` means `docs/research/agent-platform/docs-in-code-design-2026-09-26.md:N`
at that commit. Source citations mean **0818f4497**, the design's declared pin,
unless identified as live observations. The references-as-refs review was also read;
its unresolved findings remain prerequisites, not new owner questions.

## Findings

### 1. Blocking — the proposed empty result does not establish the stated property

**Locations:** `design:232`, `design:239`, `design:241`, `design:245`, `design:342`.

**Failure scenario:** a public function keeps its docstring while its behavior changes,
but it has no changed linked neighbour. The conjunction in the drift rule omits it.
Conversely, a linked callee changes while the documenting caller's body stays the same;
the rule also omits that case. Namespace docs and schema descriptions have no analogous
drift rule. `:seon.program/definition-digest` is a declaration digest, including source,
metadata and resolver context, not a body digest (`src/seon/program.cljc:388`). An
empty result can therefore accompany stale prose, while a metadata edit can produce
a finding without a body change.

The comparison basis and clearing operation are unspecified. Scanning every historical
unchanged-doc/body-change pair can keep finding an incident after the doc is repaired;
examining only the latest commit can lose it after an unrelated commit. For pages,
a text digest does not supply the target digests at the section's review basis.
Checking an evidence path against a mutable checkout also makes the proposed function
depend on more than its one database argument. Missing history, an unreadable path or
an unexamined population cannot mean no findings (requirements 3.13 and 9.1).

**Smallest fix:** specify a conservative detector of **unreviewed documentation changes**,
with its exact subject population, content inputs, comparison basis and repair/review
event. Reuse the declaration digest unless the existing analyzer supplies the narrower
input needed; add no body parser. Treat own-definition changes and linked-definition
changes as separate sufficient findings. Keep filesystem checking outside the pure
proof, or supply a recorded, identified file observation through the database. Define
missing evidence as unknown/refusal. An empty result then proves only that the named
checks found no outstanding findings on those inputs, not semantic truth of prose.

Specify the ordinary namespace-scoped proof and its dependency on the detector task
(2.11), recurrence identity (2.5), and root acceptance (2.6/7.6). The current task schema
has no needs, runtime-subject or acceptance members (`resources/seon/schemas/seon.task.edn:12`).
Those are dependencies to name, not capabilities supplied by a 40-line detector.
Require red→repair→empty evidence, repeat-without-duplicates, unrelated-commit,
linked-only-change and missing-basis cases; one initially empty fixture is insufficient.

### 2. Blocking — retiring Markdown ingestion does not finish the one-task migration

**Locations:** `design:120`, `design:158`, `design:261`, `design:339`, `design:355`.

**Failure scenario:** a migrated note gets a new task while an existing runtime producer
continues writing its issue. `seon.issue/generate` still builds issue rows
(`src/seon/issue.clj:828`, `:837`); `my.issue/add!` delegates to the issue writer
(`src/my/issue.clj:33`; `src/seon/issue.clj:1551`). Removing `index-issues!` does not
convert those entrances, issue status/tests, attempts or detector subjects. No step
specifies how an old issue id/path resolves the same current task, as 7.15 requires.
Nor does a note's occurrence in task evidence establish a one-to-one migration: one
sample note is evidence for two tasks (evidence below).

Merely changing a header to `status: migrated` is also insufficient. The installed
indexer accepts only `:open`, `:resolved`, `:superseded` (`src/seon/issue.clj:374`),
and a refused note still on disk retains its prior stored row (`:477`). M2 must define
the actual owner transition. Its count-rises-by-notes proof can require duplicate tasks
when a reviewed task already represents an issue. Grouping independent authored issues
by namespace likewise changes their identities and proofs; 2.10 governs generated
detector findings, not all migrated issues.

**Smallest fix:** extend the existing issue owner with an explicit per-record conversion:
review the old-id/path→task association, reuse an existing task when it represents the
same aspect, retain history/uncertainty, redirect old entrances, and retire current
Markdown input and old writable issue content together. Leave unmigrated records with
their current owner. Convert runtime producers/readers before claiming one family.
Preserve individual authored issues unless an explicit supersession decision groups
them. Test repeat ingestion, an old-id read/write, existing-task reuse and one runtime
detector after conversion. Keep the one task writer and its basis check; no second
migration registry or independently writable mirror is needed.

### 3. Blocking — the migration order can remove unresolved evidence and current authorities

**Locations:** `design:120`, `design:122`, `design:127`, `design:339`–`design:346`.

**Failure scenario:** the residual non-open population is described as 48 resolved/closed
notes, but it includes `fixed-unverified`, `fixed-pending-verification` and
`fix implemented; fixture sweep pending`. For example,
`docs/seon/issues/declaration-row-mints-a-definition-digest-without-its-resolver-context.md:3`
says `fixed-unverified`. Deleting that class by subtraction imports uncertainty as
settlement, contrary to 7.15. Deleting referenced evidence also makes the proposed
evidence-path detector red; “git keeps it” does not make a current path resolve.

M2 requires `(task h)` before M4 supplies it. M6 deletes architecture before M8 creates
the cross-cutting pages. M6's “deleted except vocabulary” includes ADRs which §2 says
remain evidence. M7 removes PRDs before M9 updates skills that require them. These are
active references: `AGENTS.md:10`, `:106`, `:215` name architecture authorities;
`.agents/skills/repl/SKILL.md:10` and
`.agents/skills/data-oriented-clojure/SKILL.md:9` name the context-generation turn PRD.
Removing those references without relocating their current requirements is not a
replacement proof. A namespace already having any docstring is also not proof that
its architecture requirements moved.

**Smallest fix:** classify uncertain records for reviewed migration/triage, not deletion.
Retain evidence while ordinary path links require it; revision-qualified historical
links would need an explicit reader contract. Put task lookup and conversion before
their first acceptance check. For B, install the page home before moving cross-cutting
content. In each content move, update current authority links and verify the moved
claims at their new owner before deleting the old input. Defer the broad archive
deletion to a separate reviewed manifest; it is not needed to integrate docs with code.

### 4. Blocking — link guarantees depend on unresolved writer and grammar decisions

**Locations:** `design:153`, `design:155`, `design:170`, `design:177`, `design:259`, `design:338`.

**Failure scenario:** A promises every link is deletion-protected, but M1 installs S1
only; it never explicitly requires refs S0's descriptor/deletion work. The cited refs
review leaves holder/target lifecycle and repair evidence unresolved (findings 1, 2,
4 and 8 in `references-as-refs-design-review-2026-09-25.md`). Folding its S1 findings
does not establish those writer guarantees. An external Var may resolve without a
program row, and a future subject intentionally has none; “every link” cannot promise
the same existence/deletion policy for both.

The extension to schema descriptions adds `:seon.schema/key` targets, but the inherited
S1 grammar explicitly refuses keyword link text such as `[[:seon.db/db]]`. Page symbols,
namespace links, local-name resolution and page alias context are also unspecified.
`seed-symbol` resolves aliases/refers; it supplies no holder-namespace fallback
(`src/seon/fn.clj:497`). These are required inputs to the claimed reuse.

**Smallest fix:** name resolved refs S0 obligations as a prerequisite or narrow the
initial guarantee to producer validation. Specify supported link target kinds and
their resolution environments at each existing producer, including schema-key syntax
or its deliberate omission. Reuse namespace-level `:seon.fn/see-also` already emitted
by `task-links`, rather than adding an unspecified “ns-level twin.” Name the authored,
parsed and resolved stage schemas and exact refusal alternatives under 1.15. Retain
the library tokenization grammar: unmatched text that yields no link node is not a
malformed-link refusal without another proposed grammar. No new regex is needed.

### 5. Should-fix — B/C need a minimal page ownership and reconciliation contract

**Locations:** `design:195`–`design:205`, `design:243`, `design:286`, `design:330`.

**Failure scenario:** two headings have the same text, a heading is renamed, or a section
is removed. `[page-id heading-slug]` alone does not specify collision, reference repair
or retirement. A reindex can reset the section's target basis without a documentation
review and make its drift disappear. The page model also omits the stored content
shape, ordering/nesting, row partition and ordinary merge/adoption path. Those decide
whether the rows can follow a branch and whether docs survive reconstruction from files.
B and C have not yet been shown to produce “the same data either way.”

**Smallest fix:** specify one complete canonical page value and its producer-derived
sections, using the Markdown library's heading structure. State duplicate-heading and
rename semantics, component ownership, the page identity, program/data classification,
whole-page reconciliation, and which content change establishes the comparison basis.
Validate stages with the existing Malli projection. B's Markdown can be the sole
authored input and its rows derived; that is not inherently a second writable copy.
C's accepted value and gated EDN write-back can likewise be one authority. Neither
option should leave a moved Markdown page, a new page and an independently editable
database body as separate current inputs. Make any 7.14 extension for B an explicit
owner decision; the existing exception names task bootstrap, not arbitrary pages.

### 6. Should-fix — the status table conflates stored samples with complete requirements

**Locations:** `design:84`, `design:88`, `design:91`, `design:92`, `design:95`, `design:98`, `design:227`.

**Failure scenario:** an implementer treats 7.1–7.4 as complete and migrates a schema-owned
issue. The installed `task-edges` selects only function, namespace and test holders
(`src/seon/issue.clj:716`); `:seon.task/subjects` accepts symbols only
(`resources/seon/schemas/seon.task.edn:36`). The 14 existing holders do not prove schema
subjects or not-yet-existing subjects. A thin wrapper over `task-edges` returns only
edges, not the task's id/intent/evidence, so it does not alone implement 7.10.

In the other direction, 7.9b is marked wholly open despite seed occurrence production
at `src/seon/fn.clj:754` and a named admission/storage/edge regression at
`test/seon/fn/seed_admission_test.clj:60`. This review did not run that regression:
the supported statement is source-present with end-to-end context proof still needed.
Task must-read names in `task-ai` are not themselves proof of mandatory context content;
the walk does have task/function edges (`src/seon/render/walk.clj:912`), which should
be cited and exercised for that claim. String handles alone do not prove 7.13; the
task schema's `:seon.data` partition at `seon.task.edn:23` is the relevant authority.
`task-basis` demonstrates part of 7.16, not its runtime edit and acceptance guarantees.

**Smallest fix:** split these rows into implemented subclauses and remaining proofs.
Name missing schema/runtime-subject support, the actual task lookup+edges composition,
the source-present seed path, and current destination-gate/partition evidence. Use one
status taxonomy: the count summary omits “started” and “active” and promotes several
source-only rows into installed+proven. Empty populations are installation observations,
not behavior proofs. Keep 5.6 and 5c.6 explicitly outside the first cut rather than
implying that the language's documentation work is complete.

### 7. Should-fix — prices omit common prerequisites and count shared code as removable

**Locations:** `design:250`–`design:279`, `design:294`–`design:330`.

**Failure scenario:** the owner chooses B at A+110 lines, then discovers that the common
scope also needs runtime task conversion, old-entry routing, detector proof/basis,
page reconciliation, stage schemas and refs S0. B's listed increments sum to **130**,
not 110. A's listed increments sum to 168, so its rounded 170 is consistent.
The proposed 490-line deletion includes `replacement-tx` (`src/seon/issue.clj:287`),
which `task-delta` still calls (`:635`), and `citation-attributes` (`:189`), which the
retained detector/status paths still use (`:872`, `:1138`). Deleting that contiguous
region is not the priced loadable cut.

The 1,500-line skill saving is also attributed to B even though A already proposes
moving skill mechanism claims to namespace docs. C inherits “everything B guarantees”
including plain Markdown on GitHub, then lists that readability as a sacrifice. EDN
files remain readable by outside agents, so rendering is not their only possible
entrance. A generic EDN write-back slice does not automatically define Markdown
round-trip installation for B. The B→C conversion needs its own cost unless deferred.

**Smallest fix:** price common prerequisites once, distinguish migration effort from
steady-state code, and list removable functions/callers rather than a broad line range.
Give A/B/C separate guarantees and incremental costs after the preceding findings are
resolved. Preserve the 4–5/+2/+5 lane-day figures as unverified estimates, not measured
prices. Keep parse-only 31 µs separate from total publication, resolution, validation,
memory and changed-input costs. Retain migration/measurement evidence beyond `tmp/`;
the selected migration cannot be audited from a disposable script alone.

### 8. Should-fix — count units and migration evidence need a reproducible pin

**Locations:** `design:49`–`design:52`, `design:82`, `design:110`, `design:120`, `design:364`–`design:369`.

**Failure scenario:** the migration treats 34 evidence references as 34 distinct migrated
issues, or treats every non-open file as closed. At 0818f4497 there are **35 task→issue
path occurrences, 34 distinct paths; 34 occurrences name exact `status: open`, covering
33 distinct notes**. The shared note is
`an-agents-test-world-has-no-agent-so-my-functions-cannot-be-tested-as-documented.md`,
cited by tasks in `seon.call-preparation.edn` and `seon.test.edn`. The quoted example
`d6e3b775f474` (`resources/seon/tasks/seon.db.edn:1`) cites two research files, not an
issue note. An evidence link identifies a candidate association, not proof that both
records describe one writable task.

The pin has **92 task handle literals in 33 files**. Excluding README/index/AGENTS gives
**586 direct issue-note files**: 537 exact `open`, two annotated `open (…)`, and 47 other
statuses, including uncertainty. Thus 539 is reproducible only with the annotated
statuses; the “48 closed” deduction is not. A tracked `docs/` plus `.agents/skills/`
Markdown inventory gives 1,771 at that pin; the design's 1,755 needs its own inclusion
rule. This is a scope mismatch to reconcile, not a claim that every additional file
belongs in the migration.

**Smallest fix:** retain the exact inventory commands, inclusion rules, count units and
captured database basis/source identity. Correct the example and distinct-note count;
review associations before calling them 7.15 violations. Record status categories
without translating uncertain values into closure. A pid is insufficient to reproduce
database counts across live adoption; the live sample below differs without proving
the earlier observation false.

## Library and simplicity checks

- Keep clj-kondo as the Clojure analyzer. `src/seon/fn/analyzer.clj:3` imports it and the
  row producers consume its doc/metadata. The design does not need another source walker.
- The cached **nextjournal/markdown 0.7.213** supplies `parse` configuration
  (`nextjournal/markdown.cljc:20`), text-node tokenization
  (`impl/utils.cljc:259`) and `internal-link-tokenizer` (`:296`). The tokenizer's regex
  belongs to that dependency. Code-node separation and the existing library grammar
  should remain at this seam; renderers should consume produced values/edges.
- The dependency is absent from Seon's `deps.edn`; a cached jar is not installed proof.
  This review inspected the jar source but did not load it or repeat P16's timing.
- Keep `:description` in the canonical schema form, links as native values with declared
  targets, and task annotations as handles. Neither a second schema-description copy
  nor a documentation-specific analyzer/cache/scheduler is required.
- The smallest B is the corrected common cut plus a bounded canonical page ingestion
  and render slice. Archive deletion and a subsequent C conversion are separate choices.

## Bounded live evidence and verification limits

Two read-only JVM evaluations used explicit root `/Users/sean/src/seon`, cluster
`default`, namespace `user`, session `review-docs-in-code`, and `timeout_ms 3000`.
No `require`, definitions, writes, branch creation or tests were submitted. MCP may
record/render its own observations; the second envelope reported a retained blob
`8bf731b9e0d8a30afc3b89395a520cef8b4b7eecb1d533c66f5f912492b7bce5`, size 5,671.
No installation or complete health proof is inferred from these reads.

The first probe counted `?v` alone and therefore counted distinct values, not datoms.
It returned fn/doc 2,766, ns/doc 279 and task/must-read 143. Those results were not used
as holder/edge counts. Its `seon.issue/task-findings` call returned triage 93 and no
dangling handles; the form took **13.087 ms**, MCP event **16 ms**. The returned error
window carried the existing fault above and basis-t 536870992.

```clojure
(let [database @(seon.cluster.boot/connection "default")
      start (System/nanoTime)
      attributes [:seon.fn/sym :seon.fn/doc :seon.test/sym :seon.ns/name :seon.ns/doc
                  :seon.task/id :seon.task/tasks :seon.task/must-read
                  :seon.fn/must-read :seon.fn/see-also :seon.issue/id]
      counts (mapv (fn [attribute]
                     [attribute (seon.db/q database
                       '[:find (count ?v) . :in $ ?a :where [?e ?a ?v]] attribute)])
                   attributes)
      findings (seon.issue/task-findings database)]
  {:counts counts
   :task-findings (if (:seon.error/at findings) findings
                   {:triage (count (:seon.task/triage findings))
                    :dangling (:seon.task/dangling findings)})
   :elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)})
```

Its complete count vector was `[[fn/sym 5309] [fn/doc 2766] [test/sym 2270]
[ns/name 464] [ns/doc 279] [task/id 101] [task/tasks 8] [task/must-read 143]
[fn/must-read nil] [fn/see-also nil] [issue/id 463]]`, with `:seon.*` abbreviated.
The nil aggregate results were checked by the second probe's explicit relation counts.

The corrected probe retained attribute, entity and value:

```clojure
(let [database @(seon.cluster.boot/connection "default")
      start (System/nanoTime)
      attributes [:seon.fn/sym :seon.fn/doc :seon.test/sym :seon.ns/name :seon.ns/doc
                  :seon.task/id :seon.task/tasks :seon.task/must-read
                  :seon.fn/must-read :seon.fn/see-also :seon.issue/id]
      rows (seon.db/q database
             '[:find ?a ?e ?v :in $ [?a ...] :where [?e ?a ?v]] attributes)]
  (if (:seon.error/at rows) rows
    {:counts (mapv (fn [a] (let [matches (filter #(= a (first %)) rows)]
                           [a {:datoms (count matches)
                               :holders (count (set (map second matches)))
                               :values (count (set (map #(nth % 2) matches)))}]))
                   attributes)
     :elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)}))
```

| Attribute | Datoms | Holders | Distinct values |
|---|---:|---:|---:|
| fn/sym | 5,309 | 5,309 | 5,309 |
| fn/doc | 2,775 | 2,775 | 2,766 |
| test/sym | 2,270 | 2,270 | 2,270 |
| ns/name | 464 | 464 | 464 |
| ns/doc | 280 | 280 | 279 |
| task/id | 101 | 101 | 101 |
| task/tasks | 15 | 14 | 8 |
| task/must-read | 194 | 101 | 143 |
| fn/must-read | 0 | 0 | 0 |
| fn/see-also | 0 | 0 | 0 |
| issue/id | 463 | 463 | 463 |

Attribute prefixes above abbreviate `:seon.*`. The corrected form took **26.177 ms**,
MCP event **29 ms**. It confirms the 15-edge/14-holder sample, zero function link
population, namespace-doc count and issue count. New task/function/test populations
are later live observations, not corrections to the historical P1–P5 counts. This
review did not measure allocation, retained heap, parser cost or publication cost.

The pinned file sample was read using `git ls-tree -r --name-only 0818f4497` and
`git archive 0818f4497 resources/seon/tasks docs/seon/issues` streamed into Python's
`tarfile` reader in memory. No archive checkout was created. Task handles were literal
`:id "[0-9a-f]{12}"` matches; issue evidence was quoted `docs/seon/issues/*.md` paths;
statuses were the complete `^status:` line. These regexes were measurement-only.
The slow initial per-file `git show` census took 9.08 seconds for hundreds of Git
processes; the batched in-memory archive read removed that repeated acquisition cost.
It is not a Seon operation timing.

No production source or tests changed (**net source/test lines: 0/0**). No test gate,
reload, publication or worktree was performed. No other lane was messaged or modified.
The unrelated staged `.gitmodules`/`reference-code/CLM`, dirty http-kit submodule and
untracked `src-clm/` and next-turn work were left untouched. Only this review file is
written and committed; its ownership is released after that commit.

**REVISE. Recommendation: B — a single authored Markdown page can supply queryable sections and code links with the existing parser, once the common migration and detector guarantees are specified.**
