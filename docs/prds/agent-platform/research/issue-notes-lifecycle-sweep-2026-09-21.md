---
type: research
status: draft
created: 2026-09-21
tags: [issues, lifecycle, archive, class-kill, deletion, audit]
---

# Issue-note lifecycle sweep — applying the declared lifecycle

Read-only against `src/`; every write is under `docs/seon/issues/` (frontmatter
plus `git mv`) and this note. No JVM, no gate, no cluster, no commit. Inputs read
end to end: [the issues README](../../../seon/issues/README.md),
[the deletion audit §2.2–§2.4](deletion-audit-errors-issues-config-2026-09-21.md),
[durable goals §4–§5](durable-goals-and-rulings-2026-09-21.md).

## 0. Counts

| Step | Planned | Done | Delta and why |
|---|---|---|---|
| 1. `resolved`/`superseded` → `archive/` | 90 | **90** | exact |
| 2. Collapse nine class groups to keepers | ~67 | **21** | four of the nine groups have NO keeper note — §2 |
| 3. Legacy-mechanism core | ~22 | **4** | step 1 pre-absorbed the rest; ten retired terms re-checked against `src/` — §3 |
| 4. Verified-stale sample | ~20–40 (5–10 %) | **5 of 40 = 12.5 %** | §4 |
| **Archived this sweep** | ~200–220 | **120** | |
| **Live notes remaining** | ~180–200 | **369** | plus `README.md`, `AGENTS.md`, `index.md` |

Remaining open by severity: **61 blocker, 279 friction, 29 cleanup**.

Shared-tree note: a concurrent session's commit `344d289b7` swept step 1's
staged renames into its own commit while this sweep was running. Step 1 is
therefore already in history; steps 2–4 are staged and uncommitted.

## 1. Step 1 — the 90 already-closed notes

86 `status: resolved` + 4 `status: superseded` moved with `git mv`. No content
edited. The four superseded ones:
`canonical-fixture-roster-permit-remains-held.md`,
`issue-family-guarantees-cross-prohibited-owners.md`,
`one-identity-string-names-two-entities.md`,
`the-evaluation-deadline-latches-around-a-ten-millisecond-refusal.md`.

### The three `status: active` notes — the audit miscounted them

| File | `type:` | Decision | Why |
|---|---|---|---|
| `README.md` | `reference` | left `active` | The directory's own lifecycle document. It declares `open → resolved \| superseded` **for issue notes** ("One note records one problem"); it is not one. |
| `AGENTS.md` | `orchestrator` | left `active` | The localized issue authority read before planning. Not an issue. |
| `index.md` | `reference` | left `active` | Documentation *about* the `bin/issues-index` query (audit §2.3). Not an issue. |

Neither `open` nor `resolved` is meaningful on any of the three: they name no
problem and have no acceptance. The audit's "three notes are already outside
the schema" is itself the miscount — 489 issue notes all carried a declared
status, and zero were `active`.

## 2. Step 2 — the class groups

### 2a. The audit's premise holds for five of nine groups

The README rules: "A note carrying `class-kill` and a `class/<id>` tag
references the member issues carrying that class tag." `grep -l class-kill`
over the open directory returns 13 class-kill notes, every one tagged
`class/n<N>`. **No `class-kill` note exists for `class/p1`, `class/p2`,
`class/p3` or `class/absence-as-health`, in the open directory or in
`archive/`.** Those four are DESIGN-LAW QUERY TAGS, not groups:

- `class/p1` is named by AGENTS.md §2.1 itself — "open members are tagged
  `class/p1` in `docs/seon/issues/`" — and its class statement lives in the
  [seon-env PRD](../../sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)
  and [the p1 research note](../../context-generation/research/p1-ambient-state-2026-09-15.md);
  `p2`/`p3` are its siblings from the same PRD.
- `class/absence-as-health` is the recurring failure class AGENTS.md states in
  prose. The audit named `a-fixture-refusal-loses-its-diagnostic-at-the-test-reporter.md`
  as its keeper; that note is an ordinary instance. The one note carrying
  `class-kill` with that tag,
  `a-database-reads-error-value-is-read-as-a-row-by-its-caller.md`, states a
  much narrower class ("a database read's error value is read as a row by its
  caller") that none of the other seven members instantiate.

Collapsing 40 evidence-carrying notes into four instance notes would have
destroyed the evidence and left the laws with no subjects. **Those 40 stay
open.** Membership stays derivable through `bin/issues-index --class class/p1`.

| Group | Keeper the audit named | Verdict | Members left open |
|---|---|---|---|
| class/p1 | `a-hot-adopted-handle-shape-change-wedges-the-live-turn-proc-silently.md` | REFUTED — an ordinary blocker instance, no `class-kill` tag | 19 |
| class/p2 | `operator-root-inference-guesses-from-directory-names.md` | REFUTED — same | 7 |
| class/p3 | `orphan-ast-nodes-outlive-the-declaration-that-held-them.md` | REFUTED — same | 7 |
| class/absence-as-health | `a-fixture-refusal-loses-its-diagnostic-at-the-test-reporter.md` | REFUTED — the real `class-kill` note states a narrower class | 7 |

### 2b. The five real class-kill groups — fold table

Each keeper gained a dated `## Folded members` section with an explicit member
table (note, one-sentence claim, current `file:line`) plus one folded paragraph
per (b) member. The keepers' `Evidence` sections previously said "derived with
`bin/issues-index --class class/nN`"; archiving the members empties that query,
so the member list is now STATED in the keeper rather than derived — the one
place in this sweep where a derivation was replaced by a list, done
deliberately and dated.

| Member | Class | Case | Disposition |
|---|---|---|---|
| `context-capture-prompts-bypass-the-blob-splitter.md` | n11 | a | superseded → keeper |
| `error-class-catalog-and-renderers-disagree.md` | n11 | b | paragraph folded, superseded |
| `failover-adds-an-uncaptured-system-context-fragment.md` | n11 | a | superseded → keeper |
| `flow-has-no-read-set-control-and-a-hand-rolled-egress.md` | n11 | b | paragraph folded, superseded |
| `value-floor-residue-duplicate-cursors-and-marker-hand-lists.md` | n11 | b | 3 of 4 findings resolved at HEAD; the surviving one folded |
| `agent-html-still-uses-the-retired-transcript-assembler.md` | n11 | b | central claim CORRECTED, residue folded, superseded — counted under step 3 |
| `complete-publication-takes-seventy-seconds.md` | n9 | b | measured numbers folded, superseded |
| `core-namespace-pages-spend-seven-seconds-without-declaration-fallbacks.md` | n9 | a | superseded → keeper |
| `render-package-proc-reruns-unchanged-renderers.md` | n9 | a | superseded → keeper |
| `retained-render-packages-survive-producer-replacement.md` | n9 | a | superseded → keeper |
| `boot-refusal-has-no-render-producer.md` | n1 | a | superseded → keeper |
| `database-diff-renderer-appends-prose-elision.md` | n1 | a | the keeper already linked it |
| `init-failure-dumps-entire-prepl-event-history.md` | n1 | a | superseded → keeper |
| `pre-rename-root-claims-are-unreadable-noise-on-every-status.md` | n1 | a | superseded → keeper |
| `artifact-releases-the-fence-between-install-and-start.md` | n4 | a | superseded → keeper |
| `dependency-cache-lock-wait-has-no-deadline.md` | n4 | a | the keeper already named it a current member |
| `render-adversarial-roots-outlive-their-experiment.md` | n4 | a | superseded → keeper |
| `render-live-proof-roots-have-no-lifecycle-owner.md` | n4 | a | superseded → keeper |
| `live-publication-has-a-hand-maintained-predicate-owner-reload.md` | n3 | a | superseded → keeper |
| `partial-hot-reload-produces-mixed-code-with-no-warning.md` | n3 | b | the keeper's designated residual owner; evidence folded |
| `publication-reload-hand-lists-namespaces-and-misses-dependencies.md` | n3 | a | superseded → keeper |
| `stale-language-specific-kondo-cache-blocks-correct-code.md` | n3 | a | superseded → keeper |

### 2c. Case (c) — members left open

| Member | Class | Why it is not an instance |
|---|---|---|
| `turn-bookkeeping-exceeds-recorded-regression-bound.md` | n9 | Records 5,645–7,198 ms against an unchanged 300 ms assertion and states outright that no performance cause is inferred. Nothing in it shows a local update recomputing a global projection; it is an unattributed bound failure (law §2.3). |
| `expected-refusal-logs-raw-datom-error-twice.md` | n1 | Its subject is the vendored Datahike fork's own transaction/writer logging seam. That output never crosses `seon.render`, so the one total render construction the keeper owns cannot own it. |

Two more n11/n9 members turned out to be stale rather than instances and were
archived `resolved` under §3: `opening-walkthrough-replicates-a-usage-test.md`
and `schema-population-retains-five-readerless-rows.md` (n11),
`ai-context-bypasses-render-proc-retained-bytes.md` (n9).

## 3. Step 3 — the legacy-mechanism core

Ten retired mechanisms re-grepped against `src/` and `resources/` at
`e7721a963`, then every open note matching them was read:

| Term | `src/` hits | Open notes | Verdict |
|---|---|---|---|
| relay JVM, self-host, inbox-edge, `my.refactor`, flavor, form-identity | 0 | **0** | step 1 already archived every note on these |
| `cljs` | 9 | 2 | `fresh-cljc-files-are-jvm-only.md` LIVE (7 `.cljc` files in `src/`); `unlogged-findings-2026-08-01.md` LIVE |
| pod | 0 | 1 | `unlogged-findings-2026-08-01.md` — its subject is SCI `:classes`, not the pod |
| retired results branch | 0 in `src` | 1 | `operator-test-status-still-reads-retired-results-branch.md` LIVE — `:test-results` still read in `script/seon/fresh_operator.clj` |
| retired transcript assembler | 1 | 1 | claim CORRECTED, archived — below |

Archived (4):

| Note | New status | Resolution recorded |
|---|---|---|
| `agent-html-still-uses-the-retired-transcript-assembler.md` | `superseded` → n11 keeper | The cited `resources/seon/schemas/seon.cluster.agent.edn` no longer exists and the agent's HTML projection is `seon.cluster.agent/render-identity-html` (`seon.agent.edn:17`). What survives is the n11 shape: `render-session-html` is still DEFINED at `src/seon/render/transcript.clj:864` with zero callers anywhere. |
| `opening-walkthrough-replicates-a-usage-test.md` | `resolved` | `src/my/run.clj` and the whole `my.run` namespace are deleted; zero hits for `my.run`/`my/run` in `src/` and `resources/`. |
| `schema-population-retains-five-readerless-rows.md` | `resolved` | `resources/seon/schema.edn` is deleted; zero hits for `:seon.cluster.loop/evaluation`, `:seon.render.data/window`, `:seon.render.block/band`, `:seon.render/literal`. |
| `ai-context-bypasses-render-proc-retained-bytes.md` | `resolved` | `seon.cluster.prompt/prompt` now acquires one RETAINED walk (`src/seon/cluster/prompt.clj:383-392`) and the volatile `basis=` prefix is gone from `render/walk.clj` and `render.clj`. |

The audit's "~22" was measured before step 1 ran; step 1 archived most of that
core as already-`resolved` notes.

## 4. Step 4 — stale-by-code sample

The 40 oldest open notes by `git log --diff-filter=A` (2026-07-29 through
2026-08-03). Each central claim re-grepped at HEAD. **Measured stale rate:
5 / 40 = 12.5 %** — above the audit's 5–10 % projection, as expected for the
oldest stratum.

| Note | Resolution recorded |
|---|---|
| `render-wave-properties-cannot-produce-their-failing-cases.md` | None of the five named properties exists; `test/seon/render/walk_test.clj` now holds four ordinary deftests (`:54`, `:84`, `:139`, `:179`), including the connection-truncation regression the note asked for. |
| `host-bound-first-party-vars-break-in-value-position.md` | `install-first-party-namespaces!` wraps every host Var with `sci/copy-var*` (`src/seon/sci/eval.clj:1378`, `:1396`); a value-position read no longer receives a raw `clojure.lang.Var`. |
| `context-mvp-drive-can-false-green-after-cross-agent-delivery.md` | `tmp/context-mvp-drive.clj` no longer exists. |
| `cluster-toolkit-stores-a-prefix-derived-projection.md` | Both prohibited shapes gone: `src/seon/cluster/instruction.clj:31-57` selects by the declared `:seon.ns/context-relevant?` fact, not a `my.` name prefix, and derives per supplied database value; no durable roster is written. |
| `schema-map-extraction-still-depends-on-position-two.md` | `src/seon/cluster/loop.clj` is deleted; every current `(drop 2 …)` over a Malli form is guarded by `(map? (second value))` (`src/seon/schema.clj:131`, `:310`). |

### Verified-current in the same sample (citation drift only, left untouched)

| Note | Drift | Claim at HEAD |
|---|---|---|
| `schema-guard-refuses-accretive-loosenings-with-data.md` | `seon.cluster.run` → `seon.turn` | `assert-schema-data-unused!` lives in `src/seon/turn.clj` |
| `runtime-turn-and-evaluate-kernels-conflate-boundaries.md` | `cluster/loop.cljc` deleted | the `turn` half IS split (`src/seon/turn.clj:4936`, ~75 lines); the `evaluate` half GREW to ~396 lines (`src/seon/sci/eval.clj:2774-3170`) |
| `production-docstrings-teach-deleted-semantics.md` | 4 of 7 cited files deleted | `src/seon/flow.clj:2` still says "testbed"; `src/seon/turn.clj:1801`, `:2367` still say "fold" |
| `provider-output-token-wire-key-is-hard-coded.md` | `schema.edn:607` → `seon.config.ai.edn:47` | the literal `[["max_tokens" identity]]` is still on the dial, not on the provider descriptor row |
| `eval-drives-duplicate-a-four-minute-run-clock.md` | — | `(* run-cap 240000)` behind `(or …)` still at `src/seon/eval/drive.clj:375` |
| `search-index-property-collides-with-process-index-id.md` | — | `:seon.search/index` is the tokenizer enum in `src/seon/search.clj:172` AND a Flow proc id at `src/seon/cluster.clj:2859`; two meanings survive, though the second is now a proc name, not an index-ID string |
| `terminal-refusal-error-fact-fails-on-oversized-data.md` | its named recurring test is gone | UNVERIFIABLE, not stale — left open deliberately: a note whose only witness disappeared is not evidence the defect did |

The remaining 28 of the 40 verified current by symbol grep and were not edited.

## 5. What remains open

| Severity | Count | Largest concentrations |
|---|---|---|
| blocker | 61 | `class/p1` (19 members, the law-2.1 group), publication/adoption, absence-as-health |
| friction | 279 | the long tail; the four law-tag groups hold 40 of them |
| cleanup | 29 | retired vocabulary and readerless residue |
| **total** | **369** | plus `README.md`, `AGENTS.md`, `index.md` |

Thirteen `class-kill` notes remain open. Five had their members folded in by
this sweep (`n1`, `n3`, `n4`, `n9`, `n11`); the other eight (`n2`, `n6`, `n7`,
`n8`, `n10`, `n12`, `n13`, `n14`) were untouched because each has fewer than
three open members.

## 6. Left undecided for the owner

1. **`class/p1`, `p2`, `p3`, `absence-as-health` have no keeper.** 40 notes
   carry them. Either a `class-kill` note is written for each (four notes, each
   stating one design law's failure class, members folded as here), or the tags
   stay purely as query tags and the 40 notes stay individually open. This
   sweep chose the second only because it could not invent the first without
   writing a class statement the owner has not ruled.
2. **Step 5 of the audit's plan (promote survivors to `seon.task` rows) was not
   attempted** — explicitly out of scope for this sweep.
3. **Keeper member lists are now stated, not derived.** That is a hand-maintained
   mirror by AGENTS.md §2.2's own definition. It is dated and it is the price of
   archiving the members; the derivation returns if the members are ever
   promoted to `seon.task` facts.
