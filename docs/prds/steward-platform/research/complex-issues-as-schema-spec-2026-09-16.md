---
type: research
status: current
created: 2026-09-16
tags: [research, steward, schema, issue, indexer]
---

# R6 — eight complex issues converted by hand: the facts, the links, the missing families

Owner (2026-09-16 08:35Z): "tackle converting some more complex issues to see
what other attributes we should be storing and how things should be linked."

Every abbreviated hex token quoted below is the citation as the ISSUE NOTE
wrote it at its own date — including submodule commits in §1.2 and §1.3. They
are historical quotations preserved for accuracy, never claims about the
current `reference-code/` gitlinks.

Read end to end: `AGENTS.md` §2.2/§3;
[namespace-data-model](../plan/namespace-data-model-2026-09-16.md) §0, §3.3,
§7.3, §8; [issue-family-spec](../plan/issue-family-spec-2026-09-16.md) §1, §7,
§8; `resources/seon/schemas/seon.issue.edn` as landed;
`src/seon/issue.clj` (`parse-note`, `qualified-token`, `index-tx`);
`docs/seon/issues/README.md`. Research only: no source or test edits, no test
JVM, exactly one live read-only probe (§6), default never restarted.

## 0. What the landed indexer extracts, exactly

`src/seon/issue.clj:26-47` tokenises the WHOLE note text on a
letter/digit/punctuation partition, then `index-tx` (`:107-186`) keeps five
citation shapes and nothing else:

| shape | recogniser | becomes | lost otherwise |
|---|---|---|---|
| `ns/sym` qualified symbol | `qualified-token` `:77-90` (rejects `:`-bearing, `.clj`, `.md`) | `:seon.issue/functions` or `:seon.issue/tests` if the symbol is an existing `:seon.fn/sym` / `:seon.test/sym` | unresolved symbols are a refusal, never stored |
| 64-hex token | `hex-token? 64` `:72` | `:seon.issue/errors` ref by `:seon.error/signature` | — |
| 9-hex token | `hex-token? 9` `:72` | `:seon.issue/commits` (STRING, `min 9 max 9`) | 40-hex SHAs, 7/8-hex submodule commits, Datahike commit UUIDs |
| `class/x` tag on a `class-kill` note | `:120-125` | `:seon.issue/members` | — |
| frontmatter `created:` | `:143-148` | `:seon.issue/opened` | 243 of 263 notes have no `created:` |

Everything else in a note is folded into one `:seon.issue/problem` string:
file:line spans, qualified keywords, measurements with units, UUIDs (source
commits, error ids, publication ids), pids, roots, gate tallies, dates,
verdicts, research-note links, sibling-issue links, dependency commits.

Corpus census (263 notes under `docs/seon/issues/`, `grep -lE`, 2026-09-16):

| citation shape | notes carrying it | stored today |
|---|---:|---|
| `file:line` (`.clj`/`.cljc`/`.edn`/`.md`) | 146 | no |
| qualified keyword `:seon.x/y` | 138 | no |
| link to a `docs/prds/**` research/landing note | 115 | no |
| 9-hex commit | 128 | yes (string) |
| measurement with a unit (ms/s/bytes/KB/MB/GB) | 74 | no |
| `ns-test/var` symbol | 51 | yes (ref) when the entity exists |
| UUID (source commit, error id, publication id) | 23 | no |
| `N tests / M assertions` gate tally | 23 | no |
| sibling-issue markdown link | 18 | no (only `class-kill` members) |
| 64-hex signature | 7 | yes (ref) |
| frontmatter `created:` | 20 | yes |

Two parse consequences worth naming: 90 notes have no `## Problem` heading, so
`parse-note` `:36-39` stores the entire body as `:seon.issue/problem` (measured
below: 8,325 bytes for one of the eight); and `README.md`/`AGENTS.md`/`index.md`
are excluded by name (`:60`), which is why the three `status: active` files are
not refusals.

## 1. The eight notes, converted by hand

Chosen for deliberately different kinds. Per note: (a) the facts the prose
carries, (b) stored / resolved / lost today, (c) the family that should own a
fact that is not an issue attribute, (d) acceptance as deftests.

### 1.1 `complete-publication-takes-seventy-seconds` — performance with measurements

(a) 70.2 s for one `seon.cluster/refresh-source!` on 2026-08-06; a 2.7 s
reset→republish→refork→READY measurement from 2026-08-05 with an unknown scope;
a 300 s liveness backstop hit by the fresh-operator multi-init lifecycle test;
operator exit 124; publication id `733b79b5-89d6-40d5-9b05-ee872281ad71`;
recorded source `6aa9fe1b-203b-5ca1-bea2-9047ea996105` at 02:59 UTC; default
PID 7595; `logs/current-source-failure.log`; the diagnostic string
"Publication did not finish within its declared bound."; 13 file citations;
11 qualified keywords; 2 commits; the phase list (analysis, schema population,
malli registration, transaction batches); the ten-second law as the budget.

(b) Stored: 3 function refs, 2 commits, the prose. Lost: every measurement, the
phase breakdown, both UUIDs, the pid, the log path, the bound, the exit code.

(c) The measurements do not belong on the issue. A complete publication is
identified by the source commit it produced: **`seon.publication` keyed by
`:seon.source/commit-id`** (already a uuid identity in `seon.source.edn`),
carrying `duration-ms`, `outcome` (`:sealed`/`:refused`/`:bound-exceeded`), the
declared bound, and one component per phase (`phase`, `ms`). One entity per
commit is an aggregate over that publication's attempts, not an entity per
event: a retry replaces `duration-ms` and history keeps the prior value with its
transaction, exactly the rule §8.5 of the data model states for occurrences.
The issue then carries `:seon.issue/measurements` → those entities, and "is it
still 70 s?" is a query, not a re-read of prose.

(d) Acceptance: `seon.cluster.source-test/publication-records-its-phase-durations`
(new) asserting a sealed publication writes one `seon.publication` with a phase
component per declared phase; and
`seon.publication-test/a-second-publication-of-one-commit-replaces-its-duration`
(new) for the aggregate rule. No existing deftest asserts either.

### 1.2 `class-outward-values-bypass-total-render-contract` — class note with members

(a) 5 commits (`563034709`, `4bc8104d8`, `5e449b275`, `e8e37eb50`, plus
`7e35df213` as a HEAD basis); dependency commits Malli `3517a3cd` and SCI
`fcbd886` with the semantics they ground; four dated slices with verdicts;
per-namespace gate tallies (`seon.render.ns-test` 5/134,
`seon.render-simplification-test` 11/134, combined 87/457 with two named
residual failures, 109 assertions after adoption); exact bytes 9,266 → 1,296
characters for one live SCI evaluation; `src/seon/render/value.clj:271-289` and
`:360-368`; the derivation `bin/issues-index --class class/n1`; archived member
verdicts; a `surface: context-generation` trailer.

(b) Stored: 8 function refs, 5 commits, **34 members**, the prose. Lost: the
dependency commits (8- and 7-hex fail `hex-token? 9`), every gate tally, the
byte measurement, the file spans, the per-slice verdict and its date.

(c) Gate tallies belong to the test run, not the issue: `seon.test.run` already
exists with `:seon.test.run/id`; the missing link is
**`:seon.issue/runs` → `seon.test.run`**, so "which gate proved this slice"
is a ref. The dependency commits belong to the submodule:
**`seon.reference` keyed by `[path commit]`** — or, cheaper, refuse to invent a
family and store the submodule commit on the existing citation as a
`seon.issue/evidence` component (§3, option B). The 9,266 → 1,296 byte pair is
a render measurement whose subject already has a family: `seon.render.cost`
(`resources/seon/schemas/seon.render.cost.edn`) is a FACT map with no identity;
give it `:seon.render.cost/id = (seon.id/id [shape-key profile])` and it becomes
the aggregate the issue can ref.

(d) Acceptance exists in part: `seon.fn/output-path-report` backs the class
regression named in the note. New: `seon.issue-test/a-class-note-links-its-run`
asserting `:seon.issue/runs` after a gate records results.

### 1.3 `anonymous-runtime-contracts-have-recurred` — cross-namespace (25 namespaces)

(a) 72 distinct qualified symbols across 25 namespaces; 27 qualified keywords;
26 file citations with spans (`src/seon/cluster/run.clj:1139-1140`, `:1136-1138`,
`:1162`, `resources/seon/schema.edn:2677-2688`,
`reference-code/datahike/src/datahike/api/impl.cljc:30-42`,
`reference-code/core.async/.../flow.clj:166-178,245-260`); submodule commits
`0e8601d7f2f6` and `dc35f3e0d7bc2eef502e77982f48641f025c8051`; a four-row census
table (19 `:any` in schema leaves across 18 keys, 58/23 parsed source, 54/22 in
`:malli/schema`, 4/1 outside contracts); a per-instance status table with
"remaining—active shared-file owner" verdicts; a live instrumented refusal
("must be an immutable Datahike database value"); gate 26/141.

(b) Stored: **30 function refs** (the best-linked of the eight) and 2 commits.
Lost: the census numbers, every file span, both submodule commits, the per-row
status, the refusal text.

(c) The census is a measurement of the program graph and must not be a stored
mirror — `AGENTS.md` derive-or-die. The honest shape is a **detector**
(`issue-family-spec` §7): `seon.issue/generate` over a contract detector yields
one fine-grained issue per anonymous leaf with identity
`(seon.id/id {:seon.issue/detector 'seon.issue/anonymous-contract :seon.fn/sym 'seon.cluster.run/…})`,
each carrying its own function ref; the count is then
`(count (q … :seon.issue/detector …))`, derived every time. This note becomes a
class note whose `members` are the generated issues — and the 25-namespace
spread becomes 25 stewards' inboxes instead of one unreadable table.

(d) Acceptance: `seon.issue-test/anonymous-contract-detector-is-idempotent`
(new: two runs, one entity per subject) and
`seon.issue-test/a-repaired-subject-resolves-its-generated-issue` (new).

### 1.4 `runtime-block-html-is-raw-ids-and-instants` — render / ugly output

(a) Exact bad output: "13 referenced entities", bare ids `69338 69335 78199`,
`Runtime / Idle / Trigger: c2421a79`, a raw `#inst "2026-09-10T20:02:27.231-00:00"`,
"Trigger None" while the trigger is set; `src/seon/render/web.clj:1294-1313` and
`:1144`; commits `237c4c572`, `881c720f4`; gates 10/71, 84/505, 10/66, 68/459;
HTTP 200 with zero `#inst`/`:db/id` literals; three refused adoption attempts;
adopted marker `6aa31517-2ccf-50a2-a61a-9f0a9261b8e2` versus published
`6aa31c20-1ac0-53b5-959b-b810cd0c5c92`; an error id
`9c931b6e-8128-4552-8290-abb5ca69e00d`; two research notes; the owner quote;
`seon.db/identity-attributes` as the repair.

(b) Stored: 2 function refs, 2 commits. Lost: the observed bytes, both source
commit UUIDs, the error id (a 36-char UUID is not a 64-hex signature, so the
`errors` ref is empty), the file spans, both research links, the gate tallies.

(c) The error id is the sharpest miss: `:seon.error/id` IS an installed identity
attribute, so the citation is resolvable today with no new family — the indexer
simply does not look. The observed-versus-wanted output belongs to the render
subject: `seon.render.cost` (given an identity, §1.2) or, for "what the page
actually served", the existing `seon.render.block` name plus a measurement
entity keyed by `[block-name profile]`. The two source-commit UUIDs are
`:seon.source/commit-id` values — again resolvable by identity, not a new family.

(d) Acceptance exists: the note's own HTTP capture assertion is
`seon.render.web-test` territory. New:
`seon.issue-test/a-cited-error-id-resolves-to-its-occurrence`.

### 1.5 `pre-rename-root-claims-are-unreadable-noise-on-every-status` — operator / process

(a) 288 claim records, 149 naming absent roots, largest 787,381 bytes, oldest
Aug 5 15:51, newest Sep 15 17:29; a status run of nine lines / 653 bytes with
zero refusal lines; pid 4883 with start-instant `2026-08-05T15:19:11.630Z` as a
STRING; record uuid `1ff66f77-6d55-351a-a96a-37d657a5d485`; eight qualified
keywords including the retired `:seon.dev.process/pid` and the current
`:seon.boot/pid`, and the classifications `:seon.operator.claim/absent-root` /
`/malformed-record`; `resources/seon/operator/state.clj:837` and `:809-814`;
commit `a073f7b51`; four dated verdicts; `surface: operator (claim state)`.

(b) Stored: **zero function refs** (the note cites no `ns/sym` at all), 1
commit, the prose. Lost: all of the above. This note is invisible from every
namespace page — the exact rot the data model's §0 principle is against.

(c) The keywords are the link: `:seon.operator.claim/absent-root` and friends
are `:seon.schema/key` entities (an installed identity attribute), so
**`:seon.issue/keys` → schema-key entities** makes this note discoverable from
the claim family with no new identity. The counts belong to operator state, and
`seon.operator.footprint` already measures a root (`root`, `file-bytes`,
`observed-at`) — it has no identity attribute, so it is a fact, not an
aggregate; giving it `:seon.operator.footprint/id = (seon.id/id [root])` makes
"149 of 288 stale" a temporal query over one entity per root instead of prose.

(d) Acceptance: `seon.operator.state-test/an-absent-root-claim-is-reclaimed`
(new) and `.../a-malformed-record-names-its-first-unknown-key` (new); neither
exists, and the note's three acceptance lines are today unlinked English.

### 1.6 `development-adoption-can-mix-host-and-sci-generations` — schema lifecycle / refork

(a) Nine file citations with exact spans across five owners
(`script/seon/fresh_operator.clj:2366-2407`, `:2429-2435`;
`src/seon/cluster.clj:1403-1407,1931`, `:1771-1905`, `:1868`, `:1965`, `:1967`,
`:2027`; `src/seon/sci/eval.clj:1093-1160`, `:957`, `:1558-1670`;
`src/seon/sci/kernel.clj:108-115`; `src/seon/cluster/loop.clj:1633-1645`;
`src/seon/cluster/agent.clj:915-951`); two dependency spans
(`reference-code/sci/src/sci/core.cljc:345-351`,
`reference-code/core.async/.../flow/impl.clj:174-189`); basis
`7e35df2131c71f476a85c6a38bfc8eb292cb36f5` (40-hex) and checkout `806659e06`;
the admitted key `:my.adoption-freshness/third-request` versus the loaded
`:my.adoption-freshness/new-request`; refusal text "Source changed during
development adoption"; 221,788 ms convergence; 74.21 s repair; gates 26/124 and
85/514; a named required-namespace list (`seon.cluster.source-test`,
`seon.custody-stability-test`); `bin/test-fast --paths …` completed
2026-09-15T18:21:42Z; a landing note link; `surface: adoption-publication`.

(b) Stored: **zero function refs** (every citation is a file:line, never a
symbol), 1 commit (`806659e06`; the 40-hex basis is dropped), the whole 8,325-byte
body as `problem`. Lost: everything else.

(c) This is the strongest case for **`:seon.issue/files` → `seon.fn.file`** with
a span: the family exists (`:seon.fn.file/path` identity, `digest`), so a
file:line citation resolves to an entity, and a span component
(`:seon.issue.citation/file`, `/row`, `/end-row`) carries the line. It is also
the case for an adoption measurement whose subject is the adoption attempt's
target commit — the same `seon.publication` entity as §1.1, with
`outcome :refused` and `retry-ms`, so "how long does a refused adoption take to
converge" stops being a sentence in a note.

(d) Acceptance: the note names its own — an adoption/ordinary-call overlap
fixture in `seon.cluster.source-test` and `seon.custody-stability-test`, plus
the already-written `seon.adoption-contract-freshness-test`. Only the third
exists; none is linked (`:seon.issue/tests` is empty because the note cites no
test symbol, only a file path and a `bin/test-fast` command line).

### 1.7 `a-platform-test-leaves-its-worker-stripped-of-every-contract` — test infrastructure

(a) Five named `ns-test/deftest` symbols; drift counts 918, 926, 3, 1, 1; worker
identities `pool-1`, `pool-2`, `pool-5`; an 11-row census table of
`instrument/remove!` sites with file:line and a yes/no verdict; the gate's exact
stderr block; two sibling issue links; a code block showing the fix; the
`bin/test --all` acceptance line; `seon.test.runner/reassert-contracts!` as the
reason it is `cleanup`, not a blocker.

(b) Stored: 1 function ref, **5 test refs** (the only one of the eight with
tests), no commits. Lost: the drift counts, the worker ids, the census verdicts,
both sibling links.

(c) The drift measurement belongs to the run, not the issue: `seon.test.run`
plus a **`seon.test.drift` component keyed by `[run test-sym]`** carrying
`removed-count`/`added-count` — one per (run, task), which is an aggregate on a
stable identity rather than an event stream. The 11-row census is derivable:
the sites are `:seon.fn/calls` edges into `seon.instrument/remove!` from test
entities — a query, not a table, and exactly the derive-or-die rule.

(d) Acceptance: the note's acceptance is already a query
("no `Tasks that changed worker-global state` section"). As a deftest:
`seon.test-runner-test/a-task-that-strips-wrappers-is-reported-with-its-count`
(exists in spirit — the detector that printed the block) and
`seon.test-runner-test/no-task-leaves-the-worker-unarmed` (new, the wanted
invariant).

### 1.8 `fresh-cljc-files-are-jvm-only` — commits and landing/verification history

(a) **16 commits**, each paired with a namespace and a focused gate tally
(`290416d38` seon.ai 33/128; `4d59e0b5c` 14/59; `2b6d84dc2` 18/173; …); a 21-row
audit table of files with convert/leave verdicts and deciding spans; a lint
result (0 errors / 32 warnings over seven `.cljc` files); `resources/seon/schema.edn:2443`;
`.agents/skills/datastar-web-ui/SKILL.md:85` citing a deleted file; four dated
sections (Resolution evidence, Triage 2026-08-03, Re-grounded 2026-08-13,
stale-guidance observation 2026-09-05) each with its own verdict.

(b) Stored: **zero function refs**, 16 commits (strings), the prose. Lost: the
21-row verdict table, every gate tally, the skill citation, the dates, the
per-commit namespace attribution.

(c) A commit string is the one place the landed schema stores an identity as
text. Two honest options (priced in §5): resolve the prefix to a
**`seon.commit` entity keyed by the full 40-hex sha** at index time, or keep
strings and accept that `(q … :seon.issue/commits …)` cannot join to anything.
The dated sections are the note's real structure: each is
(date, verdict, evidence) — a **`seon.issue.finding` component** (`at`,
`verdict` enum, `problem` text, refs) instead of one 856-byte blob, so
"what changed since my last turn" is a temporal query on components rather than
a diff of prose.

## 2. Ground truth: what those eight actually hold on `default` (§6 probe)

| slug | fns | tests | errors | commits | members | opened | problem bytes |
|---|---:|---:|---:|---:|---:|---|---:|
| anonymous-runtime-contracts-have-recurred | 30 | 0 | 0 | 2 | 0 | no | 1,242 |
| class-outward-values-bypass-total-render-contract | 8 | 0 | 0 | 5 | 34 | no | 492 |
| complete-publication-takes-seventy-seconds | 3 | 0 | 0 | 2 | 0 | no | 600 |
| runtime-block-html-is-raw-ids-and-instants | 2 | 0 | 0 | 2 | 0 | no | 4,129 |
| a-platform-test-leaves-its-worker-stripped-of-every-contract | 1 | 5 | 0 | 0 | 0 | no | 6,178 |
| development-adoption-can-mix-host-and-sci-generations | 0 | 0 | 0 | 1 | 0 | no | 8,325 |
| fresh-cljc-files-are-jvm-only | 0 | 0 | 0 | 16 | 0 | no | 856 |
| pre-rename-root-claims-are-unreadable-noise-on-every-status | 0 | 0 | 0 | 1 | 0 | no | 614 |

Three of eight — including one blocker — have NO program link at all and are
therefore invisible from every namespace page. None has `opened`. One has tests.

Citation resolution against installed identity attributes (50 on default):

| cited shape (distinct, across the eight) | count | resolves to an existing entity today |
|---|---:|---:|
| qualified keyword → `:seon.schema/key` | 51 | **41** |
| repository file path → `:seon.fn.file/path` | 73 | 0 |
| UUID → `:seon.source/commit-id` | 6 | 0 |
| UUID → `:seon.error/id` | 6 | 0 |
| UUID → `:seon.message/id` | 6 | 0 |

The 41/51 is the headline: four fifths of the keywords these notes cite are
already entities with an identity, dropped on the floor by a five-shape
recogniser. The three zero rows are honest but not conclusive: the file paths
are cited repository-relative (`src/seon/cluster.clj`) and 15 of the 73 are
`reference-code` submodule paths, so the spelling or the population of
`:seon.fn.file/path` must be established before the `files` slice (one query,
first task of that lane); the UUIDs were recorded on other roots/branches, so
absence on `default`'s branch is expected and is not evidence that the
identity-lookup rule fails.

## 3. Consolidated accretion table

### 3a. New refs and attributes on `seon.issue`

| addition | shape | resolves from | why it is not derivable today |
|---|---|---|---|
| `:seon.issue/keys` | `[:set :seon.db/ref]` → `:seon.schema/key` entities | cited qualified keywords | 138/263 notes; 41/51 in the eight already exist as entities |
| `:seon.issue/files` | `[:set :seon.db/ref]` → `seon.fn.file` | `path:line` citations | 146/263 notes; the only link 2 of 8 unlinked notes have |
| `:seon.issue/namespaces` | `[:set :seon.db/ref]` → `seon.ns` | a cited namespace with no symbol | was in data-model §3.3, dropped when the family landed; `functions → ns` misses notes citing only a namespace or a file |
| `:seon.issue/runs` | `[:set :seon.db/ref]` → `seon.test.run` | the gate that proved a slice | 23 notes carry `N tests / M assertions` as prose |
| `:seon.issue/issues` | `[:set :seon.db/ref]` → `seon.issue` | sibling markdown links | 18 notes; today only `class-kill` produces members |
| `:seon.issue/measurements` | `[:set :seon.db/ref]` → §3b entities | a number with a unit | 74 notes; the issue must LINK the measurement, never copy it |
| `:seon.issue/findings` | `[:set {:seon.db/component true} :seon.db/ref]` `seon.issue.finding{at, verdict, problem, refs}` | each dated `## …` section | the 8,325-byte `problem` blob is the whole history of one note |
| `:seon.issue/detector` | `:seon.db/ref` → `seon.fn` | generated issues (spec §7) | specified, not landed |
| `:seon.issue/opened` (fix) | `:inst` | git first-commit date of `:seon.issue/path` when no frontmatter | 243/263 notes have no `created:`; the fact exists in git, not in the note |

Deliberately NOT added: `:seon.issue/agents`, `/evaluations`, `/transactions`,
`/processes`. An agent, evaluation, transaction or pid named in a note is
evidence of ONE observation, not a property of the problem; it belongs to the
measurement or finding that recorded it (3b), which the issue then refs. Adding
them to the issue would be the event-per-entity mistake one level up.

### 3b. Entities the measurements and history need

The aggregate-not-event rule applied: identity is (subject, metric), never
(subject, metric, moment); a later observation REPLACES the value and Datahike
history keeps the prior one with its transaction, so series, rates and recency
are temporal queries (data-model §8.5).

| family | identity | carries | replaces which prose |
|---|---|---|---|
| `seon.publication` (new) | `:seon.source/commit-id` (existing uuid identity) | `duration-ms`, `outcome`, `bound-ms`, phase components | §1.1 70.2 s, §1.6 221,788 ms / 74.21 s, exit 124 |
| `seon.render.cost` (exists, no identity) | add `:seon.render.cost/id = (seon.id/id [shape-key profile])` | already has `estimated-tokens`, `profile`, `at`; add `producer` ref | §1.2 9,266 → 1,296 bytes, §1.4 page bytes |
| `seon.operator.footprint` (exists, no identity) | add `:seon.operator.footprint/id = (seon.id/id [root])` | already has `file-bytes`, `observed-at` | §1.5 288 records / 149 absent / 787,381 bytes |
| `seon.test.drift` (new component of `seon.test.run`) | `(seon.id/id [run-id test-sym])` | `removed-count`, `added-count`, `worker` | §1.7 918 / 926 / 3 / 1 / 1 |
| `seon.commit` (new, owner decision §5) | full 40-hex sha | `abbrev`, `at`, `subject` | §1.8 16 commits as strings |
| `seon.issue.finding` (component, 3a) | `(seon.id/id [issue-id at])` | `at`, `verdict`, `problem`, refs | every dated section in all eight |

Each new family needs its own `:seon.render/ai` + `/html` pair; see §7.

### 3c. Indexer changes — one derived resolver, not five more shapes

The landed recogniser is a hand-roster of five shapes (`src/seon/issue.clj:107-186`);
adding `files`, `keys`, `runs` and UUIDs as four more `hex-token?`-style
branches repeats the mistake `AGENTS.md` §2.2 names ("a hand-maintained list, a
naming convention, and a regex over text"). The structural version:

1. Derive the target set once per index: `seon.db/identity-attributes`
   (`src/seon/db.clj:943`) returns the 50 installed `:db.unique/identity`
   attributes; the indexer queries the existing values of the ones it accepts
   (`:seon.fn/sym`, `:seon.test/sym`, `:seon.schema/key`, `:seon.fn.file/path`,
   `:seon.error/signature`, `:seon.error/id`, `:seon.source/commit-id`,
   `:seon.message/id`, `:seon.issue/id`, `:seon.test.run/id`).
2. A citation token is READ once (`edn/read-string`, already used at `:86-89`)
   and looked up by value in that map. A hit yields (entity, identity
   attribute); the issue attribute it lands on is derived from the identity
   attribute's own namespace, not from a hand-written `cond`.
3. A file citation carries a row: the path resolves to the file entity, the
   `:NNN` suffix becomes the span on the `seon.issue.citation` component.
4. Unresolved tokens stay refusals, as today.

This is the same construction the debug header already uses to stop printing raw
ids (`seon.db/identity-attributes`, see §1.4's note), so it is one mechanism
accreted, not a second one.

## 4. What a worker's opening would render, and which pair is missing

The issue block LINKS; each linked entity renders through its own pair.

| linked family | pair declared today | verdict |
|---|---|---|
| `seon.fn` | `seon.render.ns/function-ai` + `function-html` (`seon.fn.edn`) | present (P2 landed) |
| `seon.test` | `seon.render.test/render-ai` + `render-html` (`seon.test.edn`) | present |
| `seon.error` | `seon.error/render-ai` + `render-html` | present |
| `seon.ns` | `seon.render.ns/render-ai` + `render-html` | present |
| `seon.issue` | `seon.issue/render-ai` + `render-html` | present |
| `seon.fn.file` | none | **missing** — blocks §3a `files` from rendering |
| `seon.lint` | none | **missing** — the family exists with `fn`/`file`/`row`/`col` |
| `seon.test.run` | only the error pair | **missing** for the run entity — blocks `runs` |
| `seon.render.cost`, `seon.operator.footprint` | none | **missing** — blocks `measurements` |
| `seon.publication`, `seon.commit`, `seon.issue.finding` | n/a (new) | must land with the family |

Per-note, a worker's opening needs: §1.1 the publication measurement + the two
source commits; §1.2 the 34 members and the run that proved each slice; §1.3 the
30 functions grouped by namespace (25 stewards); §1.4 the error occurrence and
the render cost; §1.5 the schema keys and the footprint; §1.6 the nine files
with spans; §1.7 the five tests plus the drift on the run; §1.8 the 16 commits
and the 21-row verdict table as findings. Five of those eight need a pair that
does not exist.

## 5. Three priced options for the next indexer slice

| | A — four typed shapes | B — the derived identity resolver | C — resolver + citation components |
|---|---|---|---|
| what lands | `:seon.issue/files`, `/keys`, `/namespaces`, `/runs` as four more branches in `index-tx` | §3c: one lookup over `seon.db/identity-attributes`; the issue attribute derives from the identity attribute's namespace | B plus the `seon.issue.citation` component (file + row/end-row) and `:seon.issue/findings` |
| cost | ~0.5 day, 1 file (`src/seon/issue.clj`), 1 schema | ~1 day, same file, 1 schema, 1 new query at index time | ~2 days, + 2 component schemas, + the `seon.fn.file` and `seon.test.run` render pairs |
| buys | the 146 file-citing and 138 keyword-citing notes become discoverable | the same, plus every FUTURE identity family is linked with no indexer change (UUID error ids, source commits, message ids resolve on day one) | the same, plus line-accurate "open this file here" for a worker, and the dated history as queryable findings instead of an 8 KB blob |
| risk | the fifth, sixth, seventh hand-rostered shape — the defect class `AGENTS.md` §2.2 names | a token that is a legitimate value of two identity attributes needs a rule (prefer the most specific; refuse ambiguity loudly) | span parsing must accept `:1136-1138` and `:2366-2407,2429-2435`; more surface to get wrong in one slice |
| leaves undone | UUIDs, measurements, findings | file spans, measurements, findings | measurements (§3b) and the commit decision (§5.1) |

**Recommendation: B**, then §3b's measurement families as a separate slice. B is
the only one that gets cheaper with every family the platform adds, and it
deletes the shape roster rather than extending it.

### 5.1 The one decision inside every option

`:seon.issue/commits` stores a 9-char string. Options: (i) leave it (join to
nothing, 128 notes affected); (ii) widen to any 7–40 hex and keep strings
(accepts submodule commits, still joins to nothing); (iii) resolve to a
`seon.commit` entity keyed by the full sha at index time (one `git rev-parse`
per distinct citation at publication). (iii) is the only one that makes "which
issues did this commit touch" a query, and it is the only one that needs an
owner's yes because it adds a family and a git read to the indexer.

## 6. The one live probe

Read-only, `jvm` mode, explicit custody, one call, 11 ms on `default`
(PID unchanged, nothing started, stopped or reforked):

```clojure
(let [conn (seon.operator/connection "default")
      db   (seon.db/db conn)
      resolved (fn [attr vals]
                 (count (seon.db/q '[:find [?v ...] :in $ ?a [?v ...]
                                     :where [?e ?a ?v]] db attr (vec vals))))]
  {:identity-attributes (count (seon.db/identity-attributes db))
   :issues (into (sorted-map)
                 (for [s slugs]                       ; the eight slugs
                   [s (let [e (seon.db/pull db '[*] [:seon.issue/id s])] …)]))
   :citation-targets {:schema-keys    [(count kws)   (resolved :seon.schema/key …)]
                      :files          [(count files) (resolved :seon.fn.file/path files)]
                      :source-commits [(count uuids) (resolved :seon.source/commit-id …)]
                      :error-ids      [(count uuids) (resolved :seon.error/id uuids)]
                      :message-ids    [(count uuids) (resolved :seon.message/id uuids)]}})
```

Hypothesis: the eight notes' facts are mostly unlinked, and the citations that
ARE resolvable today are dropped by the five-shape recogniser. Verdict:
confirmed — 3 of 8 notes hold zero program refs, 0 of 8 hold `opened`, and 41 of
51 cited keywords already exist as `:seon.schema/key` entities. Results are the
two tables in §2.

## 7. Files to own and protected paths

Owns (a later implementation lane, not this research): `src/seon/issue.clj`,
`resources/seon/schemas/seon.issue.edn`, the new family schemas in §3b, and
their tests. Protected at the time of writing (`git status`, 2026-09-16): no
tracked file was modified in the working tree; untracked `build/`, `workers/`,
and `docs/prds/steward-platform/research/writer-census-probe-2026-09-16.clj`
belong to other lanes and were not touched. This note is the only file this lane
wrote.
