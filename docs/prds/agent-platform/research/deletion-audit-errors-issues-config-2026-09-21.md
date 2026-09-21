---
type: research
status: draft
created: 2026-09-21
tags: [deletion, errors, faults, issues, tasks, config, dials, effects, search, blob, audit]
---

# Deletion audit — errors, issues/tasks, config, effects, search/blob

Read-only. No JVM, no gate, no cluster. Every row below was verified by
reading the cited bytes at `b2f34ebaf` (branch `steward-platform`); counts are
from `rg`/`python3` over the working tree and are reproducible.

Area under audit: 9,896 src lines across `seon.error` (2,658) + `seon.error.refusal`
(132), `seon.issue` (1,510) + `issue/detect` (332) + `issue/opening` (232) +
`my.issue` (35), `seon.config` (926), `seon.effect` (1,053) + `my.fs` (109) /
`my.web` (41) / `my.edit` (104), `seon.search` (571), `seon.blob` (430),
`seon.bootstrap` (932), `seon.env` (531), `seon.oversight` (300).

## 0. Headline

| # | Deletion | Net src lines | Evidence |
|---|---|---|---|
| 1 | `:seon.error/diagnostic-*` ceremony (7 required keys per construction) | **−1,600** | `src/seon/error/refusal.clj:37-74`; 2,310 key lines in src; 275 call sites |
| 2 | 14 copies of one hand-maintained ~100-member error union | **−630** | `src/seon/error.clj:88`, `:235`, `:332`, `:861`, `:909`, `:1801`, `:1868`, `:2058`; `resources/seon/schemas/seon.effect.edn:190-458` |
| 3 | `seon.search` Lucene index — zero production queries | **−720** | only `similar-identities` (pure) is called: `src/seon/schema/admission.clj:332` |
| 4 | `seon.error` tail: 16 schema predicates + 17 generators | −345 | `src/seon/error.clj:2314-2658`; each used once |
| 5 | `:seon.error/class true` markers (retired by PRD R3) | −183 edits, −182 test | 183 sites in `resources/`; `test/seon/error_class_schema_test.clj` |
| 6 | Dead prose builders | −66 + tests | `error.clj:1304-1326`, `:2204-2218`, `:2226-2239`, `:2270-2283` |
| 7 | Legacy `:seon.ns/steward` fault routing (measured: routed 0 of 23) | −60 | `error.clj:498-500` (its own docstring), `:1526`, `:2112`, `:2178` |
| 8 | Synchronous effect rows + `receipt`/`face` vocabulary | −250 | `effect.clj:53-72`; only `seon.background` reads them back |

**Estimated total deletable: ~4,100 src lines + ~1,500 test lines** (§5), plus
**~200–220 of 492 issue notes** archivable (§2), leaving ~180–200 live.

---

## 1. Errors

### 1.1 The measured shape

| Fact | Count | Evidence |
|---|---|---|
| Public + private fns in `seon.error.clj` | 95 | `rg '^\(defn'` |
| `(error/diagnostic` construction sites in `src` | 275 | `rg '\(error/diagnostic\|\(error\.refusal/diagnostic' src` |
| `:seon.error/diagnostic-*` key lines in `src` | 2,310 | `rg '^\s*:seon\.error/diagnostic-[a-z]+ ' src` |
| …of which the value is `:seon.error/unknown` | 28 | same |
| `:seon.error/kind` references still live | 306 src / 443 test / 46 script / 4 bin | PRD §8 acceptance target is 0 |
| `:seon.error/class true` markers still live | 183 | PRD §2 R3 says delete |
| `seon.error/error?` general predicate | **0** | already landed (`bc8152438`) |
| Inline base-three checks (`contains? … :seon.error/at`) | 200 | PRD §1.3 calls these transitional debt |

The kind-retirement PRD's acceptance (`docs/prds/steward-platform/plan/error-conversion-prd-2026-09-20.md:§8`)
is **not met**: only the general predicate landed. The program now carries
**two error models at once** — `:seon.error/kind` + `:seon.error/class` stamps
(799 sites) *and* the base+facet model — which is precisely the bloat the owner
named. That is one mechanism accreted beside another (AGENTS §2.5), not a
migration.

### 1.2 The `diagnostic-*` ceremony — the single largest deletion

`seon.error.refusal/diagnostic` (`src/seon/error/refusal.clj:37-74`) requires
**seven** evidence keys, wraps each in `known-or-unknown`, and moves them under
`:seon.error/data`. Measured redundancy across all 275 src call sites:

| Key | Literally repeats another key at the same site | Share |
|---|---|---|
| `:seon.error/diagnostic-operation` | `:seon.error/operation` | 235/275 = **85%** |
| `:seon.error/diagnostic-layer` | `:seon.error/layer` | 218/275 = **79%** |
| `:seon.error/diagnostic-offending` | `:seon.error/offending` | 181/275 = **66%** |
| `:seon.error/diagnostic-evidence` | `:seon.error/diagnostic-offending` | 83/275 = 30% |

Verbatim at `src/seon/env.clj:89-102`, 5 of the 7 evidence keys restate a
member already on the same map (`diagnostic-layer` = `layer`,
`diagnostic-operation` = `operation`, `diagnostic-offending` =
`diagnostic-evidence` = `offending`, `diagnostic-expected` =
`::expected-schema`). The parked lane reached this independently:
`…/error-result-retirement-hunks-2026-09-23.md:1` ("removing duplicated
top-level producer members"), listing the same sites.

| src span | lines | duplicates / seam | recommendation | what preserves the function | risk |
|---|---|---|---|---|---|
| `error/refusal.clj:37-74` + 275 sites | ~1,925 | `diagnostic-layer/operation/offending/evidence` duplicate `layer`/`operation`/`offending`; `diagnostic-expected` duplicates the facet's own expected member | Keep **`:seon.error/diagnostic-member`** and **`-cause`** only; derive the rest from the base members already on the map | The base three + `offending` + the facet's members already carry every fact the render grammar (PRD §1.4) prints | low — mechanical; wrapper validates |
| | **−1,600** | | | | |

### 1.3 Fourteen copies of one hand-maintained union

`seon.error/refusal` — a **pure cause-chain walk** — declares a 39-line `:or`
union enumerating ~100 error schema keys (`src/seon/error.clj:88-126`). The
identical block is copy-pasted at `error.clj:235`, `:332`, `:861`, `:909`,
`:1801`, `:1868`, `:2058`, plus `src/seon/sci/kernel.clj:538-574`,
`src/seon/sci/admit.clj:574-613`, and — 269 lines, one key per line —
`resources/seon/schemas/seon.effect.edn:190-458`.

This is the banned hand-maintained mirror (AGENTS §2.2, "derive or die"), and
the derivation **already exists in the same file**:
`seon.error/facet-keys` (`src/seon/error.clj:1921-1936`) computes exactly this
set from the projection registry by asking which schemas extend
`:seon.error/base`. The unions are therefore a stale copy of a live query.

`seon.effect.edn:189` even comments the mirror as deliberate
("Polymorphic handler pass-through: explicit canonical facets per PRD 1q.
effect-test checks this manifest against the canonical fixture population") —
a checker enforcing a mirror, where §2.2 asks for the derivation instead.

PRD §1.2 already authorises the fix: `:seon.error/value` (the base alias) "is
admissible … as an input type at a genuinely polymorphic inspection boundary
(a renderer, a recorder)". Every one of the 14 sites is exactly that.

| src span | lines | duplicates / seam | recommendation | what preserves the function | risk |
|---|---|---|---|---|---|
| 8 blocks in `error.clj` (88/235/332/861/909/1801/1868/2058) | 292 | copies of `facet-keys` (`:1921`) | replace each with `:seon.error/base` | `facets` (`:1938`) still answers which facets a value satisfies | low |
| `sci/kernel.clj:538-574`, `sci/admit.clj:574-613` | 77 | same | same | same | low |
| `seon.effect.edn:190-458` | 269 | same, one key per line | `:seon.error/base`; delete the sync test that enforces the mirror | `request!` is declared polymorphic at `effect.clj:1046-1050` already | low |
| | **−630** | | | | |

### 1.4 The tail: 16 predicates + 17 generators, each used once

`src/seon/error.clj:2314-2658` (345 lines) declares 16 `…-complete?` /
`ordered-…?` / `…-valid?` / `…-agree?` predicates plus one `gen/` generator
each. Measured usage: every one is `defn` + one `register-core-predicate!` +
one schema reference; **none has more than one call site, and 14 of 16 have no
test of their own**.

What they check is that a `count` attribute agrees with a cardinality-many
child set whose members carry contiguous `…/ordinal` values
(`ordered-members?`, `:2331-2348`). That machinery exists only because an
ordered Malli `explain` result (a vector) was flattened into a Datahike
cardinality-many set, which loses order, and the count/ordinal/omission triple
reconstructs it. Datahike has an ordered construct — `:db/tupleType`
(`reference-code/datahike/src/datahike/index/persistent_set.cljc`), the
vocabulary table's own row — and `seon.blob` already stores bulky payloads.

| src span | lines | duplicates / seam | recommendation | what preserves the function | risk |
|---|---|---|---|---|---|
| `error.clj:2314-2658` + the resource `:fn` refs | 345 | reconstructs vector order that a tuple/blob keeps natively; `malli.error/humanize` already orders problems | Delete all 33 defs; store the explanation as an ordered value | `explain-problem` (`:1020`) and `problem-sentence` (`:1072`) already build the sentence from the Malli problem directly | medium — one schema-shape change, which is a RESET, and database data is disposable by ruling |

### 1.5 Dead and legacy members

| src span | lines | finding | recommendation | risk |
|---|---|---|---|---|
| `error.clj:1304-1326` `refusal-prose` | 23 | **zero** references in `src`, `resources`, `bin`, `script` (including backquoted-symbol form); only `test/seon/error_test.clj` keeps it alive | delete with its test | none |
| `error.clj:2204-2218` `time-limit-prose` | 15 | same | delete with its test | none |
| `error.clj:2226-2239` `edit-prose` | 14 | same | delete with its test | none |
| `error.clj:2270-2283` `unclassified-prose` | 14 | same | delete with its test | none |
| `error.clj:1526-1536` `steward` + queries at `:2112`, `:2178` | ~60 | routes through `:seon.ns/steward`, retired to namespace agents (`:seon.ns/agents`). `error.clj:498-500` records the measurement in its own docstring: *"routed exactly nothing in production: 23 of 23 faults on a live cluster carried no failing"* function | delete; route by task per the ruled `seon.task` (D1/D2) | low — it routes nothing today |
| `error.clj:484-490` `machinery-namespace-prefixes` | 7 | hand-maintained string prefix roster (`"clojure." "java." …`) classifying frames by NAME — the banned naming convention (§2.2) | derive from `:seon.fn/sym` presence in the program graph | low |
| `error.clj:37-41` `sci-eval-docstring-parts` delay; `db.clj:56-57` `error-scalar-text` delay | 8 | `requiring-resolve` caches breaking a load cycle `seon.ai → seon.repl → seon.error → seon.cluster.wake → seon.render.value → seon.ai` (PRD §1.1 amendment) | the cycle is the defect: `seon.error` is recorder **and** renderer. Split the renderer out; both delays go | medium |
| `error.clj:304-328` `diagnostic` | 25 | a pure pass-through to `error.refusal/diagnostic` kept "so no caller breaks" (PRD §1.1) — a compatibility facade, banned by §2.5 | convert the 275 callers to the leaf in one slice (§13 rule) | low |

### 1.6 What a minimal model looks like under D12/D13

One flat map: `:seon.error/at`, `/layer`, `/operation`, optional `/message`,
optional `/offending`, plus the owning facet's own members. Validated at the
armed wrapper against the function's declared facet union
(`src/seon/instrument.clj`, landed `796a76314`). Recurrence identity is
`seon.id/id` of [layer, operation, sorted satisfied facet keys, throwable class
+ top frame, violated expected key/shape, location path] — already implemented
at `src/seon/error.clj:200-225` and correct under D13. Nothing else is needed.

Deleting to that model removes items 1, 2, 4, 5, 6, 7 above: **≈2,900 src lines
and ~800 test lines**, and `seon.error.clj` falls from 2,658 to roughly 900.

---

## 2. Issues / tasks

### 2.1 Code

| src span | lines | duplicates / seam | recommendation | what preserves the function | risk |
|---|---|---|---|---|---|
| `issue.clj:41-58` `words` + `:103-300` note parse / citation index | ~260 | A hand-rolled ASCII character scanner over the note corpus, self-documented at `:43-45` as *"the note corpus is ~7 MB per index and the character-sequence version of this split was 484 ms of every publication"* — a full-corpus text scan on the publication path to recover citations by name. §2.2 bans a text scan as a substitute for a fact | Declare citations in the note's frontmatter (or drop notes entirely, §2.3) and query them | the citation facts survive as declared data | low |
| `issue.clj:64-102` `note-opened` + `git-bound-seconds` | ~40 | shells out to `git` to date notes; dating is `:seon.issue/opened` frontmatter | delete with the note pipeline | date is authored | none |
| `issue.clj:338-542` `index-tx`/`index!` | ~205 | ingests markdown files into `seon.issue` rows: the **second registry** — the directory and the database each hold the same issue set | one family: `seon.task` facts in the database | `adopt-tx` (`:950`) already migrates identities | medium |
| `issue/opening.clj:26-213` | 232 | a prose renderer (`comment-lines`, `block`, `commented-form`) selecting a rendering with the `:seon.config.render/issue-opening` dial (`:26-36`) | per the ruled vocabulary a "template" is *the render pair and units the task's linked data selects, never an entity or a registry* — this is a render pair, so it belongs in the schema's `:seon.render/ai` | the block composer moves to the render pair | low |
| `issue/detect.clj:181,228,285` three detectors | 332 | `public-without-doc`, `public-without-contract`, `public-without-reaching-test` — each a whole-program query minting one prospective issue per public function. `bodiless-defining-forms` (`:120-130`) is a hand-maintained set (mitigated: it is read from `:seon.fn/defined-by`) | keep as **queries**; delete the issue-minting. A question the graph answers is not an issue row | `my.program/breaks` (`src/my/program.clj:238`) already returns the same data as a read | low |
| `my/issue.clj` (whole file, 35) | 35 | three pass-throughs to `seon.issue` with restated contracts | keep — this is the ruled `my.*` surface over one owner | — | none |
| `bin/issues-index` | — | **not** a hand-maintained index — falsified, §2.4 | keep | — | none |

### 2.2 The note corpus, measured

492 notes. Frontmatter counts, `rg -h '^status:' docs/seon/issues/*.md`:

| status | count | severity | count | type | count |
|---|---|---|---|---|---|
| open | 400 | friction | 351 | issue | 490 |
| resolved | 86 | blocker | 108 | reference | 2 |
| superseded | 4 | cleanup | 31 | orchestrator | 1 |
| active | 3 | | | | |

`status: active` (3 notes) is not a declared status —
`docs/seon/issues/README.md` declares `open → resolved | superseded`. Three
notes are already outside the schema, which is what a hand-maintained
directory does.

Verified by a stratified read of 70 notes plus a scripted frontmatter pass.

| status | blocker | friction | cleanup | total |
|---|---|---|---|---|
| open | 62 | 307 | 30 | **399** |
| resolved | 44 | 42 | 0 | **86** |
| superseded | 2 | 2 | 0 | **4** |
| **total** | **108** | **351** | **30** | **489** |

(3 of the 492 files are `README.md`, `AGENTS.md`, `index.md`.)

**86 resolved + 4 superseded notes are still in the open directory**, though
`docs/seon/issues/README.md` rules that closed notes live in `archive/` —
which independently holds ~1,398 files. 18% of the open directory is already
closed. That is the first, unambiguous deletion: **90 notes move to `archive/`
today, no judgement needed.**

Age (earliest `git log --diff-filter=A` per filename; `created:` frontmatter is
present on only 145 of 489):

| bucket | open | all |
|---|---|---|
| before 2026-09-01 | 124 | 125 |
| 2026-09-01..09-15 | 122 | 140 |
| after 2026-09-15 | 153 | 224 |

**(a) Legacy-vocabulary class — smaller than the grep suggests.** A raw union
over 18 retired terms matches 273 of 489 notes (56%), but `steward` (182),
`receipt` (28), `facet` (28), `family` (42), `transcript` (30), `kind` (70) and
`adapter` (3) are all still live in `src/`, so the grep over-counts massively.
Five terms have **zero** `src/` hits — `relay JVM`, `self-host`, `inbox-edge`,
`my.refactor`, `flavor` — and `cljs` (4 notes), `pod` (1), `form-identity` (1)
name deleted mechanisms. Narrowing to notes that also carry an explicit
retirement signal gives 87; reading those gives a hard core of **~20–25 notes** on a genuinely deleted mechanism, e.g.
`agent-html-still-uses-the-retired-transcript-assembler.md`,
`operator-test-status-still-reads-retired-results-branch.md`,
`retired-form-projection-still-declared-and-selected.md`,
`evaluation-fixtures-still-read-retired-lifecycle-shapes.md`,
`fresh-cljc-files-are-jvm-only.md`, `one-identity-string-names-two-entities.md`.

**(b) Duplicate classes.** The directory already carries the dedup machinery:
a `class-kill` note tags members with `class/<id>` (README, "Lifecycle"). Nine
groups of ≥3 open members, 76 notes total:

| class | keeper | open members |
|---|---|---|
| class/p1 | `a-hot-adopted-handle-shape-change-wedges-the-live-turn-proc-silently.md` | 20 |
| class/n11 readerless duplicate mechanisms | `class-readerless-duplicate-mechanisms-survive-cuts.md` | 9 |
| class/p3 | `orphan-ast-nodes-outlive-the-declaration-that-held-them.md` | 8 |
| class/p2 | `operator-root-inference-guesses-from-directory-names.md` | 8 |
| class/absence-as-health | `a-fixture-refusal-loses-its-diagnostic-at-the-test-reporter.md` | 8 |
| class/n9 recompute cost | `class-local-updates-recompute-global-projections.md` | 7 |
| class/n1 render contract escape | `class-outward-values-bypass-total-render-contract.md` | 6 |
| class/n4 unowned lifetime | `class-mutable-resources-lack-explicit-root-and-lifetime.md` | 5 |
| class/n3 hot-reload correctness | `class-loaded-artifacts-lack-source-identity.md` | 5 |

Collapsing each to its keeper archives **67 member notes** while preserving
nine class statements — the ruled "fix the CLASS, not the instance" shape.

**(c) Resolved in code but still open — my prior was wrong.** Twenty open notes
were checked against current `src/`: **1 definitively stale**
(`settle-is-public-without-a-complete-contract.md` — `src/seon/cluster/loop.clj`
is gone and `settle!` now carries its contract at `src/seon/turn.clj:3742`),
**1 with a wrong citation but a possibly live claim**
(`an-agent-cannot-make-its-own-source-edit-live.md` cites `src/seon/test.clj:314`
for a `:reload` that now lives at `src/seon/cluster.clj:1978`), and **18
verified current**. Extrapolated: **20–40 notes (5–10%)** are already fixed but
open — far fewer than expected. Line citations drift; the prose claims hold,
because the notes cite symbols more than lines.

**(d) Genuine open platform defects.** At least 15 verified against current
source. Three bear directly on this audit:
`mcp-exception-projection-is-opaque-after-the-kind-removal.md` —
`src/seon/cluster.clj` still carries 25 `:seon.error/kind` stamps that
`error.clj` no longer understands, which is §1.1's two-model split *observed in
production*; `armed-error-validation-throws-on-non-keyword-sorted-maps.md` — a
crash class in the error wrapper; `a-hot-adopted-handle-shape-change-wedges-the-live-turn-proc-silently.md`
— absence-read-as-health, with a live reproduction. The others:
`retracting-a-listened-entity-broadens-the-pattern.md` (`cluster/wake.clj:428`),
`the-thirty-second-write-bound-fails-program-publication-under-load.md`
(`db.clj:4307-4356`), `complete-program-publication-is-refused-on-a-cardinality-many-set.md`,
`scratch-boot-cannot-load-the-cluster-namespace.md`,
`a-blocking-realization-is-not-bounded-by-the-interrupt.md`,
`orderly-stop-completion-joins-have-no-bound.md`,
`a-missing-required-dial-kills-every-io-prepl-connection.md`.

### 2.3 `bin/issues-index` is derived — do not delete it

Falsified my own hypothesis. `bin/issues-index:4` execs `bin/seon issues-index`;
`bin/seon:16-20` dispatches to `seon.dev.issues/run!`;
`script/seon/dev/issues.clj:14-16` queries `seon.issue/report` against the live
cluster database and `:29-38` only **prints** — it writes no file. The
hand-maintained predecessor is visible in history (`806659e06`
"rebuild the schedule from note state"), replaced at `a7d1e115e`
"Index issue entities at source publication and query the index".
`docs/seon/issues/index.md` is documentation *about* the query, not its output.

One residual drift: the index reads *published* state, so a note edited
without `bin/seon init --dev default --changed docs/seon/issues` is invisible
to it.

### 2.4 Recommendation for the directory

The owner wants a clean slate without losing goals. The evidence does **not**
support bulk deletion: 90% of sampled open notes describe live defects. What it
supports is applying the directory's own declared lifecycle, which has not been
applied:

| Step | Notes moved | Basis |
|---|---|---|
| 1. Move `resolved` + `superseded` to `archive/` | **90** | `docs/seon/issues/README.md` already rules this; `issue.clj:68-70` follows a note through the move |
| 2. Collapse the nine class groups to their `class-kill` keepers | **67** | the keeper states the class; members are instances |
| 3. Archive the hard legacy-vocabulary core | **~22** | mechanism deleted (CLJS/pod, retired transcript assembler, retired results branch, form-identity) |
| 4. Close the verified-stale notes | **~20–40** | 5–10% measured rate |
| **Total archivable** | **~200–220** | leaving **~180–200 live notes** |
| 5. Promote the survivors to `seon.task` rows | ~180 | linked facts + optional agent, the ruled family |

Then delete the note-ingestion pipeline (§2.1 rows 1–3, ~505 src lines) and
keep `bin/issues-index`, which is already derived (§2.3).

---

## 3. Configuration

### 3.1 How config is read

Database facts, as ruled. `config/effective` is called at **93** sites across
`src` (top readers: `cluster.clj` 15, `ai.clj` 7, `render.clj` 5,
`fs/jvm.clj` 5). `config/defaults` — the shipped program constant — has **8**
readers in `src`. `config/result-caps` is used 203× across src+test.

**Rule violation found.** AGENTS §2.1's shipped-defaults exception says it
"permits no atom, delay, memoization, or cache for the compiled defaults".
Two sites cache derivations of it:

- `src/seon/render.clj:88` — `(delay (agent-render-profile config/defaults))`
- `src/seon/render/value.clj:25` — `(delay (requiring-resolve 'seon.config/defaults))`

Both are in the render audit's files; recorded here because they are config
readers. No atom/memoize exists in `seon.config` itself (`rg` over
`config.clj`: zero) — that part is clean.

**`seon.env` is not clean.** `src/seon/env.clj:71` and `:105` build the
environment as an **atom** (`environment-state`, `replace-environment!`), with
`environment-state?` (`:78-84`) asserting `IAtom`. §2.1 says running code
receives its world as a value, "not from a process-global registry or atom".
`src/seon/effect.clj:42` goes further with a **dynamic var**
`*request-context*`, read at `:482`, `:551`, `:793`, `:826`, `:828`, `:835`,
`:846` — the exact shape §2.1 names first.

### 3.2 The dials

93 declared dials in `config/default.edn`; 86 carry `:seon.config/dial true` in
`resources/seon/schemas/seon.config.*.edn`. Classification against §2.3 (a
bound belongs to the seam that admits work; a tuned constant standing in for an
observable event is the defect):

| Class | Count | Members (representative) |
|---|---|---|
| **A — genuine declared bound** (the admitting seam's contract) | **41** | `seon.config.db/write-time-limit-ms`, `/validation-node-limit`, `seon.config.eval/time-limit-ms`, `seon.config.eval.result/max-bytes`, `/max-depth`, `/max-collection`, `/max-string`, `/max-source`, `/max-nodes`, `/blob-threshold`, `seon.config.fs/max-read-bytes`, `/max-write-bytes`, `/max-glob-results`, `/max-traversal-entries`, `/max-depth`, `/max-inline-bytes`, `seon.config.shell/time-limit-ms`, `/stdin-max-bytes`, `seon.config.web/max-response-bytes`, `/max-redirects`, `/timeout-ms`, `/max-inline-bytes`, `seon.config.ai/max-tokens`, `/prompt-token-budget`, `/timeout-ms`, `seon.config.error/max-evidence-bytes`, `seon.config.render.agent/token-budget`, `/max-depth`, `/max-children`, `seon.config.effect.background/time-limit-ms`, `seon.config.flow.compute/concurrency`, `/queue-depth`, `seon.config.flow.io/concurrency`, `/queue-depth`, `seon.config.message/max-chain`, `seon.config.maintenance/min-usable-bytes`, `/log-max-bytes`, `/log-retained-files`, `seon.config.db/*` remainder |
| **B — stands in for an observable event** (§2.3: the bound is the bug report; the event should be awaited) | **9** | `seon.config.agent/turn-completion-backstop-ms` (the turn-closed datom), `seon.config.operator/event-silence-backstop-ms` (the worker exchange event), `seon.config.shell/termination-grace-ms` (child-exit), `seon.config.flow/ping-timeout-ms` = **20 ms** (proc readiness — a 20 ms tuned number is not a bound, it is a guess), `seon.config.render/coalesce-ms` = 16 (a frame), `seon.test-support/event-backstop-seconds`, `seon.test/long-ms`, `seon.test/time-limit-ms`, `seon.effect/time-limit-ms` |
| **C — tuned constant to delete** | **43** | the six-dial retry backoff `seon.config.ai.retry/{base-delay-ms, multiplier, jitter-fraction, maximum-delay-ms, maximum-retries, maximum-total-delay-ms}` (one provider, one retry policy — six dials for one decision); `seon.config.ai/chars-per-token-prior` = 3.2 (a guess standing in for `seon.ai.tokens/estimate`); `seon.config.shell/inline-output-bytes` + `/preview-bytes` (two names for one inline cut, beside `seon.config.fs/max-inline-bytes` and `seon.config.web/max-inline-bytes` — **four** inline-bytes dials); `seon.config.run/max-episode-runs`; `seon.config.test/auto-check-cases`; `seon.config.bootstrap/beyond-closure-token-budget`; `seon.config.agent/write-refusal-bound`; `seon.config.error/recurrence-limit` + `/escalate-to` (see §1.5 — escalation routes through the retired steward); `seon.config.maintenance/min-usable-ratio`; `seon.config.web/max-search-results`; `seon.config.render.agent/composition`; `seon.config.render/issue-opening`; the 16 `:seon.config/absent` provider passthroughs (`temperature`, `top-p`, `frequency-penalty`, `presence-penalty`, `response-format`, `extra-body-edn`, `no-auth`, `no-provider`, `retain-reasoning`, `ai.backup/*` 4, `shell/{path,home,lang}`) — an absent dial that no one sets is a dial nobody needed |

Class C is ~43 dials; deleting them removes their schema declarations
(`resources/seon/schemas/seon.config.*.edn`, ~180 lines), their manifest rows
(`config/default.edn`, ~130 lines) and their readers.

---

## 4. Effects, capabilities, search, blob

| src span | lines | duplicates / seam | recommendation | what preserves the function | risk |
|---|---|---|---|---|---|
| `seon/search.clj:168-571` (Lucene: `document-specs`, `document`, `entity-documents`, `declared-entity-ids`, `set-basis!`, `rebuild!`, `apply-report!`, `open!`, `close!`, `family-query`, `namespace-query`, `text-query`, `search-owner`, `search`, `index-step`, the `IndexHandle` record + 2 atoms + `ReentrantLock`) | ~470 | **`search/search` has ZERO callers in `src`.** The only production consumer of the namespace is `src/seon/schema/admission.clj:332`, which calls `similar-identities` (`search.clj:143-167`) — a **pure** function over a supplied key sequence that touches no index. Datahike's AVET index already answers identity lookup | Delete the Lucene tier, the `derived/lucene` directory, and its cluster wiring (`cluster.clj:789`, `:796`, `:2859-2867`, `:2923-2924`, `:3022`, `:3094-3099`, `:3133-3137`, `:3158` ≈ 30 lines) and `test/seon/search_test.clj` (218). Move `tokens` + `similar-identities` (~45 lines) into `seon.schema.admission`, their only caller | registry-query-first schema discovery is unchanged | low — falsifiable in one grep; if a planned consumer exists, it is not in the tree |
| `seon/effect.clj:42` `*request-context*` | 1 + 7 reads | dynamic var carrying the request's world — §2.1's first-named violation | pass the context as an argument through `request*` (`:788`) | the value is already assembled at `:514`'s `binding` | medium — touches dispatch |
| `seon/effect.clj:53-72` `receipt-state`, `payload-face`, `receipt-identities` | 20 | **legacy vocabulary**: "receipt" is retired (vocabulary table: use *evaluation* / *result*), "face" is retired (use *render function*) | rename or delete with the row (next row) | none |
| `seon/effect.clj:209-386` durable row per request + `seon.effect.edn` entity | ~250 + 483 schema | Every capability request writes a durable entity. Readers of `:seon.effect/*` outside `effect.clj`: only `src/seon/background.clj:69-86` (backgrounded requests, which genuinely need the fact) and `src/seon/turn.clj:1732` (`interruption-stamps`, boot recovery). Synchronous `my.fs`/`my.web`/`my.edit`/`my.shell` rows are written and never read back. Per the crash model, *interrupted execution never resumes* — so a synchronous request's row records nothing recovery needs | Keep the row for **background** requests and for write-back provenance (`:seon.effect/file`, `/form-span`, `/program` — genuinely needed by the program graph). Drop it for synchronous ones | medium — the effect surface's render pair loses its subject; the evaluation's shown text already carries the result |
| `seon/effect.clj:142-169` `reach-rules` + `capabilities` | 28 | recursive Datalog transitive closure over `:seon.fn/calls` for the **whole** call graph; **one** production caller (`src/seon/test/accretion.clj:98`) | keep, but it is O(program) per call with no declared bound — §2.3 requires one | the accretion gate is its only consumer | low |
| `seon/blob.clj:149-176` `verify-stored!` | 28 | after konserve writes the blob it **reads the whole blob back** and recomputes its digest — verifying konserve against itself, when the digest was already computed on the way in through `DigestOutputStream` (`blob.clj:121-148`). An O(blob-size) read per write | delete; the content address *is* the verification | low |
| `seon/blob.clj` remainder | ~400 | correctly thin over konserve (`k/bget-range`, `:392`, `:403`) and Datahike's `gc-guard` (`:7`) | keep | — | — |
| `my/fs.clj`, `my/web.clj`, `my/edit.clj` | 254 | 11 thin declarations, each one `(effect/request! #'f request)` | keep — this is the ruled `my.*` surface, no duplication | — | none |
| `seon/env.clj:37-105` `Environment` defrecord + `defonce` class pin + `environment?` + `environment-state?` + 2 generators + `print-method` | ~65 | a record exists to get a `print-method`; the atom is §2.1's banned reference | a namespaced map with a declared schema and one render pair | `:seon.boot/cluster-name` + members print through the value renderer | medium |
| `seon/oversight.clj` | 300 | derives live state from Flow ping + database facts, commits nothing; one consumer (`render/web.clj:460`) | **keep** — it is the correct shape | — | — |

---

## 5. Tests in this area

| test file | lines | verdict |
|---|---|---|
| `test/seon/error_class_schema_test.clj` | 182 | **delete** — it is the proof of `:seon.error/class` (`:29-30` `error-class?` reads `(:seon.error/class (class-properties form))`), the marker PRD §2 R3 rules deleted |
| `test/seon/search_test.clj` | 218 | **delete with §4 row 1** — tests a tier with no production query |
| `test/seon/error_write_timing_test.clj` | 182 | **delete** — `^{:seon.test/long-ms 60000}` (`:80-81`) to profile **one** armed error write. A 60 s bound on one write is the defect it should be reporting, not a regression. Its subject (`prepare`/`prepare-result`/`recording`/`commit-call`/`recurrence`, `:20-23`) is all in §1's deletion set |
| `test/seon/error_test.clj` prose blocks at `:763`, `:791`, `:818`, `:824`, `:841` | ~90 | **delete** — the only callers of the four dead prose builders (§1.5) |
| `test/seon/returned_error_test.clj` | 62 | keep — asserts the wrapper's undeclared-facet refusal, the landed D12 guarantee |
| `test/seon/error_result_test.clj` | 160 | `^{:seon.test/long-ms 60000}` (`:99-101`). Keep the assertion, **delete the bound**: its subject is one bounded admission |
| `test/seon/issue_test.clj`, `issue_generate_test.clj`, `issue_settlement_test.clj`, `issue_deletion_test.clj`, `issue/detect_test.clj` | 1,091+ | **~700 deletable with §2.1**: everything proving the note-ingestion pipeline and the issue lifecycle registry; keep the detector queries' tests as `my.program` reads |
| `test/seon/config_functions_test.clj` | 32 | `^{:seon.test/long-ms 20000}` with the defect **written into its own metadata** (`:8`): *"measured 13.05 s. The symbol query itself is 4.70 ms; writer-wide work remains a measured defect"* — 2,800× overhead. **Algorithm defect**, not a long test: file it, do not tune it |
| `test/seon/config_application_test.clj:147` | 254 | `:seon.test/long` — starts a real cluster. **Genuinely long** (cold boot is an authorised exception) |
| `test/my/test_test.clj:13-14` | — | `^{:seon.test/long-ms 300000}` — **five minutes** for "two owned requests" after acquiring the canonical SCI program. **Algorithm defect**: base-context acquisition is O(program) where accepted-row installation should make it O(change) |
| `test/seon/bootstrap_drive_test.clj:28` | — | `:seon.test/long`, no `-ms` — genuinely long (real drive) |
| `test/seon/blob_error_test.clj` (18), `blob_threshold_test.clj` (16) | 34 | keep — one class each |

Long-test classification for this area: **3 algorithm defects**
(`config_functions_test`, `my/test_test`, `error_write_timing_test`),
**2 genuinely long** (`config_application_test`, `bootstrap_drive_test`),
**1 deletable bound** (`error_result_test`).

Repo-wide there are 131 `:seon.test/long-ms` declarations; the audit of the
other 125 belongs to the test-system area.

---

## 6. What is genuinely needed, and how it survives

| Need | One line | Survives as |
|---|---|---|
| An agent mistake is a flat error value it sees | base three + the facet's members, nothing thrown | the base value (§1.6); every deletion above is ceremony around it |
| A core fault is a durable fact with provenance | one occurrence row under its signature root | `error/recording` (`error.clj:1683`) + `commit-tx` (`:1753`) unchanged; Datahike tx-meta carries `:seon.db/user`/`/process` |
| A recurring fault is one root with a repeat count | `:seon.error/occurrences` component sum | `recurrence` (`error.clj:1538`) + D13 `signature` (`:200`), both already correct |
| A task is linked facts an agent's context renders | subject refs + tests + functions + optional agent | `seon.task` per the ruled vocabulary; `my.program/breaks` already returns the read |
| Effects cross a bound | the capability's declared deadline and output caps as config facts | class-A dials (§3.2) + `effect/request!`'s settlement; the durable row shrinks to background + write-back provenance |
| Schema discovery is registry-query-first | one pure token overlap over declared keys | `similar-identities` moved into `seon.schema.admission`; the Lucene tier goes |

---

## 7. What I could not verify

- **Runtime cost.** No JVM was started, so the 484 ms note-scan and 13.05 s
  config-write figures are the tree's own recorded measurements
  (`issue.clj:43-45`, `config_functions_test.clj:8`), not mine.
- **Stored datoms.** Whether live clusters still carry `:seon.error/kind`
  datoms is a database question; PRD §6 rules it a RESET item either way.
- **`seon.bootstrap.clj` (932 lines)** was surveyed but not read line by line;
  no claim is made about it.
- **Issue-note classification (§2.2)** rests on a 70-note stratified read of
  489, not a full read; the 5–10% stale rate is extrapolated from a 20-note
  verification, so the true figure could be twice that.
- **Two of my own hypotheses were falsified and are recorded as such**:
  `bin/issues-index` is derived, not hand-maintained (§2.3); and the note
  corpus is mostly *live*, not mostly stale (§2.2c).
