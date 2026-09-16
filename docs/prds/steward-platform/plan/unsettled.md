# Steward platform — working edge

## 2026-09-15 17:00Z — opened

- Docs relocated from the context-generation program: the draft (now an
  idea, not a PRD) and the two audits. No plan yet; the owner forms the
  plan of attack from the ideas folder.
- Candidate first slices (from the audits, unapproved): test-run
  provenance + results on `:current-src` (lane test-provenance running);
  `seon.cluster.message/unanswered` + `answered?` + `seon.test/verified?`;
  the render request keys; `my.task` entities; `seon.program/source-files`.

## 2026-09-16 00:20Z — task prototype written for iteration

- Owner rulings in the design dialogue: success = a set of deftests, at least
  one, all passing, run by settlement; a session is an agent's turn chain and
  parallel sessions are parallel agents; an instance is just an entity (the
  worker agent plus its plan step); context via `:seon.render/units` on the
  task schema; no `writes` family.
- [task-prototype-2026-09-16.md](task-prototype-2026-09-16.md): one `my.task`
  family, `:my.task/agent` declared as the wake, two example entities as exact
  transaction data, the functions' contracts, the opening as the walk would
  draw it, six open questions. Nothing in `src/` yet.
- Research lanes running: test-attribution-plan (cross-namespace red-test
  attribution and steward alerts as the task trigger), suite-efficiency-plan,
  cold-page-plan; fix lane hook-publication-race.
- 00:50Z owner: no wake attribute on the task; the focus is task definition →
  a running agent entity. Prototype revised: `start!` is `ensure-entity!`
  generalised (creation-tx + task agent ref + plan + `generated-run-tx` in one
  transaction; the bootstrap's hard-coded task message becomes a task entity).
  Lanes launched since: reach-digest (per-test reach digest; probes first),
  cold-page-kills (approved plan, two slices). test-attribution plan landed
  (option 1 recommended; owner decision pending; its wake fact is now a
  separate question from starting a task).
- 01:05Z owner: "no fake tasks; index all the real tasks we need for
  self-building and repair." [plan/README.md](README.md) opened as the
  roadmap: sections A (task definition), B (test evidence), C (signals as
  facts), D (chatting task and steward), E (batches, merge), F (disk),
  G (platform defects), H (live proof ladder), five pending decisions.
  Owner rulings folded in: the chatting test is relative to the user's newest
  message; workers add tests, never remove (pending confirmation); batches on
  forked clusters merged by exact entity replacement; write-back by file span.
- 01:20Z owner: data first — good schemas, solid names, linked in the graph;
  issues rot because unlinked and unassigned; namespace-centric.
  [namespace-data-model-2026-09-16.md](namespace-data-model-2026-09-16.md)
  written from mined evidence: no production namespace has a steward
  (2/411), no test links to its subject (0 holders), faults name functions
  as strings, 240 issue notes link to code only in prose. Proposed:
  test subject/namespace-under-test/reach-digest, fn file/span, error fn
  ref + steward, `seon.issue` entities indexed from notes, `seon.lint`,
  eval elisions; the namespace view as one pull; landing order; four
  decisions. reach-digest beat 1 landed (`24d12bd48`): 39 s full pass,
  median closure 668 functions — must be incremental.
- 01:45Z owner: faults stored properly (detect, look up, assign; one task per
  class so occurrences roll into one agent's context); find every place with
  prose or unlinked data; never strings for symbols; refs everywhere.
  Data model §7 written: `seon.fault` (class, identity without process) +
  `:seon.error/fault`/`fn`/`proc-fn` refs; the full 29-entity list of prose,
  EDN-text and string-named storage with verdict and writer seam.
- 02:30Z owner: Clojure/Datahike terms only (no "fault"/"class"); the error
  entity is keyed by its signature = seon.id/id over a sorted map of
  {kind throwable-class fn frame}, process moves to `seon.error.occurrence`
  component entities; ONE family for issues and tasks (`seon.issue`; the
  my.task prototype folds in). Data model §9; spec
  [issue-family-spec-2026-09-16.md](issue-family-spec-2026-09-16.md).
  Lanes launched: error-graph, issue-family; running: reach-digest (beat 2),
  attempt-and-eval-facts, program-provenance (corrected: file/span + lint
  entities, no derived subject).
- 03:20Z **RESET NEEDED on default**: the error-graph lane's schema change
  (`:seon.error/signature` becomes an identity; occurrence components,
  `2320dc1a9`) makes development adoption refuse ("predates the incompatible
  schema change"); program-provenance landed (`3402913f3`: file/span on
  function and test entities, `seon.lint` entities with exact replacement)
  but could not prove on default. The orchestrator reforks default ONCE
  after issue-family (seon.issue.edn), reach-digest (seon.test.edn) and
  attempt-and-eval-facts (seon.ai.*.edn) land, batching every schema
  change; then reseeds and re-runs each lane's live proof. Lane
  entity-pairs launched (P2); issue-settlement spec written, launches when
  turn.clj is free.
- Owner rulings 03:05Z: one steward first (`seon.render.web`); new issues
  are authored as entities in the database, the folder is an export; the
  issue block links and never copies; vocabulary is entities/attributes/
  values; DeepSeek sessions may run freely; prerequisites P1–P11 in the
  issue-family spec §6 come first.
- 03:55Z default reforked (pid 7595). Two blockers found on it: (1) every
  system turn refused — generated reads depended on every identity
  attribute incl. turn/eval/attempt ids; introduced by `563034709`
  (value-renderer identity pulls); fixed `474234fb7` (inert identities
  excluded via the shared `wake/inert-attributes`), Juniper install and
  prompt live again, forbidden dependencies 14 → 0. (2) development
  adoption rejected: `[:db/retract e :seon.fn/form-span]` without the tuple
  value; program-provenance resumed to fix. entity-pairs landed
  (`ac34ce5a3`). Trial design written:
  [issue-context-trials-2026-09-16.md](issue-context-trials-2026-09-16.md)
  (issue: doc/dir arglists EDN reader; seven renderings A–G; measures from
  facts). Two stale issues archived.
- 04:15Z cross-session coordination with the gate-owning session (Agent debug
  page data curation [0ad908]): HEAD does not publish because `6a491f0b3`
  (issue-family) declared `:seon.issue/issue` with a render pair whose
  input contract the publication refuses; issue-family stopped+resumed to
  commit the fix first; gates held until the ledger says HEAD publishes.
  Handed to error-graph at its next resume: the third error writer that
  records refused-transition errors with no occurrence child
  (`problems-refuses-its-own-zero-occurrence-signature`); to refusal-grammar:
  the `{:min 1}` constraint dropped from the refusal text. Refork of default
  only after the four schema lanes land, announced to the peer first.
- 04:35Z reach-digest landed (`f2d537187`, `e5de6ebc7`): incremental
  digests, `stale`, result recording, drift detection; warm check 2.65 ms,
  one change 56 ms / 3 digests. Residuals routed: agent-admitted test call
  edges still missing (Juniper digest unchanged when its function changes —
  a correctness hole for agent tests; lane `agent-call-edges` launched);
  `result-preservation-tx` in cluster/source.clj must carry
  `:seon.test/reach-digest` through full publication (queued until
  issue-family frees the file). HEAD publishes again (`ff48a4110`);
  ledger line written; gate session pinged.
- 05:30Z error-graph landed (`45998fdbf` identity, `2320dc1a9` occurrences,
  `2066b8c20`/`3f4f0cdf2` one writer + derived steward + readers,
  `94019d1f1` evidence): live one error / occurrence count 2 / fn ref;
  class regression 38/0/0. Residual: `:seon.error.occurrence/proc-fn`
  declared but absent until a proc → step-function fact exists (issue
  `flow-error-proc-has-no-declared-step-function-ref`). Legacy pre-writer
  row 43542 (no occurrences) retracted on default by the orchestrator.
  Batch 19: named 355/82/23 across five lanes (resumed each on its file;
  entity-pairs green); platform 84/13 red in seon.cluster.source-test,
  attributed to issue-family's indexing inside publication (`a7d1e115e`) —
  lane stopped/resumed with it first. issue-settlement (P5/P6) launched.
- 05:45Z attempt-and-eval-facts: the missing renderer facts were a platform
  regression from `563034709` (n1 render substitution disabled AI pair
  selection); fixed `cecfaf428`, live turn shows symbol + ref on a paired
  evaluation and neither on a plain map; 94 assertions in-process. Lane
  resumed on its batch-19 file. Batch 20 (error-graph, 12 ns) running on
  `cecfaf428` by the gate session.
- 05:55Z LOAD: with eight lanes running in-process regressions inside
  default's JVM, hook publications time out (operator exit 124) and the
  gate session's runtime_status reads time out. Paused the four
  reds-triage lanes (generated-read-identities, program-provenance,
  reach-digest, attempt-and-eval-facts; sessions preserved) to keep
  issue-family (platform red), issue-settlement, agent-call-edges and the
  peer's turn-test-reds. Rule going forward: at most four lanes probing
  default at once; reds triage runs serially after the platform is green.
- 06:20Z Opus triage of reach-digest's batch-19 reds (per the owner's rule:
  astra only for hard implementations): two reds were reach-digest's own
  and already repaired at HEAD by `1b5c09e15` (pull selector missing the
  digest; and the substantive one — `completion-reach-digests` opened the
  gate's SHARED published-base store under its lifetime flock on every
  `commit-results!`, serialising concurrent gates: now the canonical private
  fixture); one was a foreign fixture bug from the file-ref accretion, fixed
  at the test owner (`e441e0263`); the remaining red is the agent-local call
  edge owned by agent-call-edges. Opus triage of error-graph's batch 20 is
  running; program-provenance, generated-read-identities and
  attempt-and-eval-facts follow serially.
- 06:35Z agent-call-edges landed (`76774d044`, evidence `d799e6ff4`): an
  agent-admitted test now carries the call edge to the function it tests in
  both admission orders; live on default the edge exists and the reach
  digest changes when the function changes; armed regression 7/0/0. Residual:
  rows admitted BEFORE the fix keep their old edges until reanalysed (the
  next full publication/refork). With this, "skip if unchanged" is sound
  for agent tests too.
- 06:55Z Opus triage of error-graph's batch 20 (`b45881f32`): nothing
  attributes to error-graph. PLATFORM CLASS found: `seon.db/write-map-error`
  validates a partial map against every entity schema that merely lists its
  identity attribute (even optional) → fixture seeds silently refused →
  tests assert on an empty database (13/15 transcript-test reds, the
  turn-loop gauge test; since `26ec13420`); issue raised to blocker; astra
  lane `write-validation-class` launched (db owner; fixture helpers must
  fail loudly on a refusal). Also: default's own fault committer is refused
  on default while accepted on the fixture — RESET NEEDED after error-graph's
  schema change; refork once issue-family lands. Stale tests (bounded-result,
  open-for-agent fixture, closed-tx ref) and `kill_child.clj` readiness
  written despite a refused transact → Opus fix agents after the triages.
- 07:15Z issue-family landed: platform fix `fe9aeb336` (source-test 21/0/0,
  17/0/0 in-process; platform re-run requested), slices through `16e472d62`
  (schema, indexer at publication replacing the CLI, start!/status/pair,
  my.issue add!/tests!), virtual-turn proof, and the FIRST paid session:
  agent 856c73b784fb on issue d1f11894d81f, deepseek-flash, prompt 10,993
  bytes, 3,355 in / 142 out, 3 successful evaluations + 1 reader error
  (inline form in prose became an evaluation — issue filed); the model
  called my.issue/status and done; an inspection session, not a repair.
  Explain probe: identified issue, test, forms and red evidence correctly;
  flagged redundant instructions across the two messages and the
  issue/plan problem text. P5 landed by issue-settlement (`d132df212`:
  issue tests run before plan settlement, resolution derived); P6 guard in
  progress. Opus triage of program-provenance's reds running.
- 07:35Z PLATFORM GREEN again: batch 21 (HEAD `7ccd30496`) 84 tests / 569
  assertions / 0 failures — issue-family's publication fix holds. Result
  recording failed (default's prepl silent 30 s under lane load); accepted
  on the log, recording-only re-run requested after the refork.
- 08:05Z write-validation-class landed (`20d30a0bd`, evidence `e11d2ec03`):
  a raw write map is matched to an entity schema only by the identity
  attribute it asserts; reverse refs admitted; canonical fixture setup fails
  loudly on a flat refusal; 12/0/0 and 6/0/0 in-process; the compound issue
  archived with residuals: 12 transcript tests and 3 turn tests remain red
  for stale reasons → Opus fixer queued. Batch 21 named: issue-family
  18 tests / 1 error = the renderer-fn reader residual (Opus fixer running;
  seon.dev.issues-test and source-test green). Research launched (Opus):
  R1 writer census (derived from the program graph, supersedes the hand
  list), R4 detectors and standards with live subject counts; R2 structured
  test failures, R3 effects and write-back provenance, R5 analyzer facets
  written and queued (tmp/orchestrator/wave3/research/).
- 08:20Z batch 22 (write-validation-class, HEAD `01539d18a`): 18 tests /
  181 assertions / 0 failures — closed green.

## 2026-09-16 08:25Z — overnight focus (owner, going to bed)

Owner: "Focus on improving code indexing, error and fault storage and
linking, getting the robust test infrastructure set up so we are updating
the test entities for each function when it passes and we always know the
state of things. Efficient updates everywhere without creating a shitload
of entities: smart aggregation based on identity and updating attributes."

Standing rules tonight: astra only for hard implementation slices; Opus
for triage, research, mechanical fixes; at most four processes probing
default (the gate session's lane counted); no test JVMs from lanes; the
gate session runs gates on request files; refork default once after
issue-settlement lands (message the peer first), then the recording-only
platform run, then reseed Juniper; every landing recorded here.

Queue, in order:
1. issue-settlement lands (guard slice) → refork → recording platform run → reseed.
2. Research (Opus, as slots free): R2 structured test failures + "what made
   it red"; R5 analyzer facets (code indexing); R3 effects + write-back
   provenance. R1 writer census and R4 detectors are running; each result
   is reviewed here and turned into one lane spec.
3. Test infrastructure follow-ups: `result-preservation-tx` carries
   `:seon.test/reach-digest` through full publication (Opus, small, file
   free now); stale tests (12 transcript + 3 turn) and `kill_child.clj`
   readiness-after-refused-transact (Opus fixers); a per-function view is a
   query (tests-reaching + verified on digest) — no new entities.
4. Error storage residuals: occurrence → turn populated when the committer
   knows the turn; proc → step-function fact derived from the flow graph
   definition at arming (astra if it needs the flow owner); the third
   writer covered by the one-writer rule — verify with a live refusal.
5. Aggregation principle for every new fact family (R1/R2/R3 outputs must
   obey): identity from canonical parts, upsert the same entity, counts and
   last-seen as replaced attributes, history for the timeline; never one
   entity per event unless the event has its own identity.
6. Then the issue-context trials (Opus) on the arglists issue.
- 08:55Z R4 detectors-and-standards landed (`190c4802f`). Live subjects:
  D1 entity map without pair 51/76; D2 public fn without docstring 39/1,137;
  D3 without reaching test 303; D4 permissive contract positions **0**
  (audit A's 205/179 is STALE — the schema owner marks stored permissive
  slots justified; a detector copying it would open 179 false issues);
  D5 non-generatable contract schema 205; D6 recurring error without
  regression 3 (needs `:seon.test/error-signatures` — not yet a detector);
  D7 test without a first-party direct call 135; D8 namespace without
  steward 431 (a decision, not 431 issues); D9 expensive test without
  observation **0** (the runner's derivation, not the naive reach: 54/54).
  First run of the five fact-complete detectors would mint 733 issues in
  176 namespaces. ORCHESTRATOR DECISION (owner asleep): option B — first
  generator run = D1 + D2 (90 issues), after excluding component-only
  schemas from D1; lane `issue-generator` (Opus) launches once
  issue-settlement releases seon.issue.edn (needs `:seon.issue/detector`,
  `:seon.issue/schema`). R2 (structured test failures) launched.
- 09:20Z renderer-fn reader residual fixed (`52044b4f4`: the evaluation
  entity map admits the pulled ref shape, precedent `:seon.test/run`;
  faults-test 13/0/0). BLOCKER found by that fixer: at committed HEAD a
  fresh fixture base refuses `:seon.schema/missing-projection` from
  `accrete-schema-population!` (cluster.clj:1347, no projection carried) —
  consequence of write-validation's stricter admission; a fresh test worker
  would refuse at its first `with-database`. Gate session told to HOLD;
  Opus fixer launched (carry the projection at the seam, regression:
  create-base on a fresh store succeeds and carries its projection). Note:
  default's `database-base` delay was swapped for a freshly built base by
  the fixer (old one lingers until JVM exit); in-process runs before
  ~03:47Z local validated against pre-edit schema.
- 09:35Z R1 writer census landed (`cb019fcc8`), derived from the program
  graph: 311 functions reach `transact!` (138 production, 55 direct) naming
  178 installed attributes; 15 defect rows, largest: `:seon.fn/sym` (4,764)
  and `:seon.test/sym` (1,779) stored as STRINGS while `:seon.ns/name` is a
  symbol; `:seon.render/ai`/`html` name functions as text on schema rows;
  `:seon.ns.alias/target-ns`; `:seon.fn/arglists` duplicated by arity
  entities; `:seon.error/process` mixes three conventions in one string;
  `:seon.test/subject` 0. Eight §7.3 rows already landed, two refuted
  (`op`/`proc` 0 holders; `:seon.error/id` already the signature), six
  missed. Class kill: declaration-time refusal in the advisory walk
  (`schema/internal.cljc:119`) for storable string/symbol attributes naming
  a program family or `-edn` keys unless justified; regression naming the
  15. OWNER DECISION NEEDED (morning): changing `:seon.fn/sym`/`:seon.test/sym`
  from string to symbol is a key-type change = breakage → new keys + reader
  migration, or justified as-is; the census recommends the advisory-walk
  kill (6 h) first. Also proposed: `:seon.fn/writes` at the analyzer so the
  census stops joining through literal keyword mentions. R5 (analyzer
  facets) launched into the freed slot.
- 09:50Z R6 complex-issues-as-schema-spec landed (`090401fb3`): the indexer
  keeps five hand-rostered citation shapes and drops the rest — 3 of the 8
  complex notes have ZERO program refs (their citations are file:line),
  41/51 cited qualified keywords already exist as `:seon.schema/key`
  entities and are dropped, `:seon.issue/commits` are 9-char strings that
  join to nothing. Accretions: on the issue — `keys` (schema entities),
  `files` (seon.fn.file + span), `runs`, `issues` (siblings),
  `measurements`, `findings` (component per dated section), `detector`,
  `opened` from git when absent; new/fixed aggregate families —
  `seon.publication` keyed by commit, identities for `seon.render.cost` and
  `seon.operator.footprint`, `seon.test.drift`, `seon.commit` (owner
  decision). DECISION (orchestrator): option B — replace the roster with
  ONE resolver over `seon.db/identity-attributes` (the debug header's own
  construction); lane `issue-indexer-resolver` (Opus) after issue-settlement
  releases issue.clj. Pairs missing for linked entities: seon.fn.file,
  seon.lint, seon.test.run, render.cost, operator.footprint → one Opus
  pairs lane. R3 (effects + write-back provenance) launched.
- 10:05Z issue-settlement landed both slices: P5 `d132df212` (an agent's
  open issue tests run in-process before each plan settlement under one
  deadline; resolution derived, `resolved-tx` written) and P6 `a6fee5b31`
  (immutable guard snapshots; creator/assignment authority; a worker's test
  retraction refused, the two-transaction bypass covered); default
  converged; 19/0/0 and 33/0/0 post-adoption; live settlement recorded joint
  step completion and resolution. Residual: a full live worker turn hits a
  separately filed generated-read refusal. ALL schema lanes have landed →
  refork default as soon as the fixture-base fixer commits (cluster.clj).
- 10:10Z R2 test-failure-facts landed (`72090b6fe`). Schema:
  `seon.test.failure` components per failing `is` under `:seon.test/failures`
  (identity `seon.id/id [test-sym site ordinal]`, site = file+line or the
  existing normalized claim; file as a REF to seon.fn.file; expected/actual
  with the blob dial; seen-count/last-run replaced per run; green retracts).
  MAJOR FINDING: "what made it red" by digest comparison is UNSOUND today —
  runs are recorded on transient `building-source-*` publication branches
  (42 on default) that default's history cannot address; recorded digests do
  not reproduce; 73 of 87 red rows have no prior digest at all. The sound
  derivation costs ≈58 ms: last green from the result's own history (3.9 ms)
  ∩ functions whose source/spec changed since (3.4 ms) + warm closure
  (50.6 ms); ONE accretion completes it: `:seon.test/reach` closure
  membership refs written per result and replaced per run (never a digest
  per reached function). Lane `reach-closure-facts` (astra — runner
  recording + provenance) queued behind the refork.
- 10:25Z program-provenance batch-19 triage (Opus, `35889c232`,
  `d47ebcc3e`): all 11 reds reproduce and are green in-process; two real
  defects fixed at the analyzer (`exact-form-span` column clamp; lint
  row/col coerced to long), four fixtures admit the emitted file entities as
  publication does, five were pre-existing fixture/expectation staleness.
  METHOD RULE added to tmp/orchestrator/wave2/repl-rule.txt: test/ is not on
  default's classpath; reload the test namespace through seon.test's loader
  before an in-process run or you get false greens. New evidence on
  `declaration-settlement-consumes-invalid-read-as-ref` (row-tx built
  retractions from a `q` refusal map). Adoption converged at
  `6aaa1292…`. Next Opus triage: generated-read-identities + attempt files.
- 10:45Z R5 analysis-facets landed (`114344f23`): every clj-kondo facet on
  costs 4.0 s for 332 inputs (already paid by linting); per-usage entities
  REFUSED on measurement (124,557 usages ≈ +197 % datoms); aggregates on the
  `:seon.fn` identity ≈ +16.6 %. `:seon.fn/writes` needs no new facet —
  span-containment of keyword positions inside transact! call sites yields
  2,620 (writer, attribute) refs across 524 writers vs the census's 178
  approximation; discarded only because fn.clj collapses positions to sets.
  Priced order: writes (3 h), call-arities (4 h; arity mismatch becomes a
  query over 117k call sites), interop census (3 h), dispatch graph (2 h),
  macro-calls + destructured keywords (3 h). Lane `analyzer-facets` (Opus,
  items 1–2 first) queued for the next slot.
- 10:55Z R3 effects-and-write-back landed (`ad065c47d`): all 16 seon.effect
  attributes have ZERO holders on the reforked default, so the schema
  accretes with no migration; 10/10 capabilities already declare request
  schemas and 7/7 handlers have program entities — the writer needs no
  per-capability mapping: walk the admitted request, transact the storable
  keys (`storable-attribute-in?`), components for declared nested maps,
  bulk to the blob. Refs: `:seon.effect/eval` (replacing the hand-rolled
  run+form-ordinal join; zero holders, readers in effect.clj/background.clj
  only — DECIDED yes), `:seon.effect/capability-fn`. LOAD-BEARING DEFECT:
  `seon.edit/actual-edit` computes the exact replaced span and
  `edit/jvm.clj:51-64` drops it — the system knows which bytes it replaced
  and keeps nothing a merge can use; new facts `:seon.effect/file` (ref),
  `/form-span` (UTF-8 BYTES — seon.edit's indices are Java chars: convert,
  or non-ASCII files join wrong), `/program` (containing declaration). This
  turns roadmap E2 into a query since the fork basis and F3 into a digest
  equality. Recommendation C (~1.5 d) accepted; lane `effect-facts` (Opus)
  queued after test-infra and indexing lanes. ALL SIX research pages are in.
- 11:10Z batch 25 (issue-settlement, HEAD `a14a3101c`): issue-settlement-test,
  my.plan-test, contracts-plan-test, db-test, cluster.source-test GREEN;
  residuals: issue-test opening (in triage), seon.turn-test 2 (handed by the
  peer to turn-test-reds; batch-20 triage attributed them to
  agent-call-edges' settlement of :seon.fn/calls and the renderer-fn class).
  Load: default at 18 GB RSS / 98 % CPU, machine load 13.7 — the
  fixture-base fixer's fresh canonical base builds; the peer's own init --dev
  exceeded its bound. No new launches until the fixer lands; the refork
  clears the JVM.
- 11:30Z REFORK of default started (second tonight). Cause found by the gate
  session: default's installed schema still had `:seon.test/reach-digest` as
  `:db.unique/identity` from an early reach-digest edit while the current
  bridge derives it non-unique; any test whose digest changed carried two
  identities and `seon.fn/index-tempids` refused every publication
  ("multiple entity identities", fn.clj:1842); adoption cannot drop
  `:db/unique` in place and did not report RESET NEEDED (issue filed by the
  peer). Refork also batches error identity/occurrences, issue, lint,
  file/span, settlement guard schemas and clears the 17 GB JVM. After it:
  reseed Juniper, recording-only platform run, then launch
  reach-closure-facts, issue-indexer-resolver, issue-generator.
- 11:45Z REFORK DONE: default pid 53378, from current-src `6aaa15f4`,
  adoption converged `6aaa17a3` (exit 0); `:seon.test/reach-digest`
  non-unique, error signature identity, 1,632 issue entities indexed at
  publication. Juniper reseed running; recording-only platform run
  requested from the gate session. Queued lanes launch as the three running
  Opus agents (fixture-base fixer with the uniqueness-change fix folded in,
  reds triage, analyzer-facets) finish: reach-closure-facts (astra),
  issue-indexer-resolver, issue-generator, effect-facts (Opus).
- 12:05Z issues-index --check exits 1 on the reforked default: 1,521
  unresolved-symbol refusals over 728 notes (archive included; Java members,
  Maven coordinates, deleted symbols in archived notes) since the indexer
  resolves symbols through the program graph. Ruled for lane
  issue-indexer-resolver: unresolved tokens are evidence
  (`:seon.issue/unresolved`), archived notes never fail the check, the
  check reports counts per path and exits non-zero only for malformed
  frontmatter or duplicate slugs.
- 12:30Z fixture-base fixer landed (`a3cbcd9a8`, `957e8f7f9`): schema
  population hands its projection to every transaction it makes
  (populate-source! and accrete-schema-population!), fixture seal after the
  connection carries its projection; `declaration-changes` compares the
  union of installed and new facets → a dropped `:db/unique` is RESET
  NEEDED, not a silent keep. Fresh in-memory population 26.8 s with no
  projection bound anywhere. Two regressions added. Batch 26 phase A
  (platform, bare over the working tree) was red on registry-test and
  store-test — attributed to the fixer's in-flight tree; cold re-run on HEAD
  requested. FINDING to verify: the fixer reports `seon.test/run`
  unusable in the reforked JVM (pid 53378): test/ not on its classpath and
  `clojure/core/async/flow_monitor` missing when the loader adds it
  (issue `the-development-cluster-jvm-cannot-run-an-in-process-regression`).
  Old JVM 7595 died of a dev-panic core fault (root's turn completion
  backstop 600 s) after the index refusals. Orphan JVM: none now.
- 12:45Z In-process regressions DO work on the reforked JVM (refutes the
  fixer's note): `(seon.test/run (#'seon.test/resolve-test 'ns/test) conn)`
  ran registry-test in 1,340 ms. That test is RED at HEAD: its fixture
  builds a bare non-temporal store with no schema population/projection and
  admission now refuses `:seon.schema/key` — fixture defect from the stricter
  admission; store-test's branch marker may be a real owner defect (branch
  connections inheriting the root's projection). Opus fixer launched for
  both + the note's refutation. Batch 27 (platform cold, with recording)
  queued by the gate session behind batch 26 phase B.
- 13:05Z The two platform reds re-diagnosed by the fixer's measurement
  (`0c1fcd473`): both PASS under the worker's handed projection and fail
  without it → not fixtures. Real owners: `runner.clj:53 on-caller-loader`
  pins the classloader with a plain fn and drops dynamic bindings on
  executor threads (the handed projection is lost when a test hops
  threads); `db.clj:2844 retention-snapshot` calls `history` on a
  `:keep-history? false` store and fails the write inside Datahike's
  writer. Fixer redirected to those two owners (issue
  `two-platform-tests-lose-the-workers-handed-projection`). If the
  in-process `seon.test/run` path also lacks the handed projection, it is
  the third instance of the same class.
- 13:20Z Opus triage of generated-read + attempt reds (`31173071d`,
  `eb254719d`): generated-read all clear (two resolved by error-graph's
  derived steward, one test deleted by it); attempt: two fixed at the test
  (raw plan probe bypassed the encoding seam; 8-char id mirror), one green.
  Two REAL residuals: (1) renderer-fn — the schema now admits the pulled ref
  shape but the ARMED wrapper still enforces the bare ref: wrappers bake
  referenced schema definitions at arm time and adoption re-arms only when
  the function's own authored contract changes → class
  `arming-includes-referenced-schemas` (astra, queued); (2) issue-test
  opening: the last opening evaluation `(my.issue/status …)` is appended and
  never evaluated (ordinal 3 has no shown, no error) — absence read as
  health at `src/seon/issue.clj:257` meeting `seon.turn/generate-turn`;
  Opus fixer launched (issue.clj free). Also: `database-base` delay caches
  a failed construction forever (issue filed; daemon-thread rule added to
  repl-rule.txt).
- 13:40Z platform reds fixed at the real owners (`6d4705498`): the
  `:seon.db/append-only-after` retention rule called `history` on every
  write, so ONE declared rule broke every write to every non-temporal store
  (fixtures were correct; "registered candidates = base attrs" means the
  store genuinely had no first-party attributes because its schema write
  had failed inside the writer) — now derived from the database when it
  has no temporal index; `on-caller-loader` conveys the caller's frame with
  `bound-fn*`. Four platform/fixture tests green in-process; two new
  regressions (falsified against the old code). Gate recording fixed by the
  peer (`7c7395c8a`: the send is the authority, not a census pre-read).
  Batch 27 running on `7c7395c8a` (platform with recording + nine
  namespaces); my two Opus agents told to pause probes ~15 min.
- 13:55Z PLATFORM GREEN on `7c7395c8a` (batch 27 run A): 86 tests / 579
  assertions / 0 failures / 0 errors, registry-test and store-test included.
  Recording notice pending; run B (nine namespaces) next; probe pause held.
- 14:05Z Gate recording still fails with probes paused (the record send
  itself is slow; peer's Opus lane owns runner.clj, fresh_operator.clj,
  bin/test to fix it) → reach-closure-facts (needs record-tx) DEFERRED
  until that lands. Launched: `arming-includes-referenced-schemas` (astra:
  wrapper identity = contract resolved against the armed projection; a
  projection change re-arms exactly the affected wrappers). Waiting on the
  opening-evaluation fixer before the two issue.clj lanes (indexer
  resolver, generator) launch. Probes resumed lightly.
- 14:35Z batch 27 B (`b033e0860`): 155 tests / 1,001 assertions / 0 failures
  / 1 error — registry, store, transact-feedback, test.runner, test-runner,
  test-support, cluster, cluster.source all GREEN cold: `6d4705498` proven;
  the one error (fresh-operator-test hanging to the worker bound) is with
  the peer's recorder-latency lane. Batch 28 (turn-test-reds' four
  namespaces) in the slot. Running: arming lane (astra), analyzer-facets and
  opening-evaluation fixer (Opus); heartbeat load 10.9.
- 14:50Z opening-evaluation fix landed (`3596cfb96`): the issue block's
  status form WAS evaluated and then refused — `generated-read-fault`
  answered an `:all` evidence set (a whole-database identity scan inside the
  reach-digest refresh, attribute as a query variable) with the entire
  68-attribute inert roster, and `resume-turn` settled the failure without
  the evaluation's ordinal, so the refusal vanished (absence as health).
  Now: faults only on NAMED attributes; refusals recorded on the evaluation.
  issue-test 8/0/0 on assertions; new regression
  `a-refused-generated-form-records-its-refusal`. NEXT DEFECT measured:
  `seon.plan/run-issue-tests!` runs the agent's issue tests on EVERY
  settlement (once per opening form), 2,290 ms per pass; the opening no
  longer fits the 20 s event backstop (issue
  `opening-turn-pass-costs-seconds-per-form`). Fix (Opus, launching): run
  only the STALE tests (reach-digest's `stale`) and only at the settlement
  that closes an ordinary turn, never per generated form — the efficiency
  rule applied to P5.
- 15:05Z ROOT CAUSE of tonight's slowness (peer's latency lane, `4764c233a`,
  issue `blob-retention-sweep-starves-every-roster-writer`):
  `seon.blob.retention/reclaim!` fires every minute, takes the store's
  EXCLUSIVE sweep permit and walks all 380,285 konserve keys (64.6 s) to
  find 3,624 blobs; `branch!` waits on that permit unbounded → the
  reachability gate closed 96 % of wall time, every branch/retire 57–74 s,
  recording stalls, hook publication timeouts, slow forks. Store 72 GB /
  380k files. Peer lanes: astra `retention-sweep` (candidates as a query
  over blob-write facts; permit only around deletes — the aggregation rule
  again) owning blob/retention/schedule files; Opus research on what the
  380k keys are. The every-minute maintenance commits were also the
  cold-page invalidator earlier tonight. My lanes stay off those files.
- 15:20Z batch 28 (turn-test-reds' four namespaces, `258150603`): sci.eval and
  datahike-fork GREEN (the fork's cache fix holds cold); cluster.turn-test
  four tests red (that lane's declared set); seon.turn-test two red to
  re-baseline on HEAD after the opening fixer
  (`3596cfb96`, landed after the snapshot) — virtual-turns datom counts and
  settlement-mints (agent-call-edges' settlement of `:seon.fn/calls`).
  Load cap full with arming (astra), analyzer-facets and the stale-only
  settlement fixer (Opus) plus the peer's retention-sweep lane; the two
  issue.clj lanes wait for a slot.
- 15:45Z Peer's decision on the retention sweep (option 1, dissolution law;
  FLAGGED FOR THE OWNER, reversible by one revert): delete automatic
  byte-budget blob retention (retention.clj, its schema/dial, the per-minute
  schedule seed) and rely on the existing weekly reachability GC
  (`seon.operator/collect!` → datahike gc-storage with referenced blobs),
  plus a retirement transaction for the seeded cron row on default. Caveat:
  the weekly collect! also holds the exclusive permit for a full key walk
  (Sunday 03:00 UTC), so the 72 GB / 380k-key store question (peer's
  research pending) still matters. analyzer-facets probes paused until that
  lane commits (est. 1–2 h).
- 16:00Z Store research (peer, `cfef57241`): default's store is 71.6 GB /
  382k files, 93 % copy-on-write index leaves, ≥93 % (~67 GB) unreachable;
  nothing runs GC in practice; the per-minute maintenance rows retain
  ~1.5 MB / ~50 files each; 292 files/min live now. PLAN: `bin/seon reset
  --force` (down, destroy, republish, refork; pre-authorised by the
  disposable-data rule) right after retention-sweep commits (so the
  per-minute seed cannot be reseeded) and when my two active lanes have
  committed (a reset republishes the working tree); then reseed Juniper,
  lift analyzer-facets' pause, and the peer records the platform tier.
- 16:20Z analyzer-facets landed (`efaa45a68`): `:seon.fn/writes` (refs to
  schema-key entities by span containment inside transact! call sites) —
  470 writers, 2,691 refs, 334 distinct attributes (census had 178);
  `:seon.fn/call-arities` — 67,941 tuples on 5,548 holders; the analysis
  config is unchanged (positions/arity were already requested). RULING
  FALSIFIED: Datahike cannot store a ref inside a tuple (stored verbatim,
  unresolved), so call-arities landed as `[string long]` like the
  `:seon.fn/pending-calls` precedent; OWNER DECISION: spend ~½ day on an
  interned (callee, arity) identity family (4,892 entities, real refs) or
  accept the string tuple. `seon.fn/arity-mismatches`: ZERO over 7,866 call
  sites with declared arities (blind check flags 6,449 with arity+1 —
  falsified). Regressions written, not run (probes paused) — gate covers
  `seon.fn-test`. Filed: `write-seam-is-a-named-set-not-a-declared-fact`
  (mark the write seam on `seon.db/transact!` var metadata). Also seen:
  two concurrent `init --dev default` runs produced "instrumentation did not
  restore contracts (registered 1090, instrumented 0)" once — reported.
- 16:40Z arming-includes-referenced-schemas landed (`98b5f2afe`,
  `1c98259ba`): a wrapper's identity now includes the schema definitions its
  contract references, so a projection change re-arms exactly the affected
  wrappers; class regression 8 assertions; live `of-agent` accepts pulled
  renderer refs; the ledger selector keeps renderer `:db/id`. Residual: seven
  web-debug fixture/budget failures (Opus fixer after the reset). Launching
  issue-indexer-resolver (Opus) into the freed slot.
- 17:05Z BLOCKER from the arming landing: on cold workers contract arming
  dies — `compilable-form refused predicate-functions: got nil`
  (instrument.clj:557 passes a dynamic binding a fresh JVM has not
  established); batch 29 aborted before any test; all gates held; the store
  reset is held behind the fix. Arming lane resumed: predicate functions
  ride the projection or the identity avoids compiling predicates;
  regression for cold arming.
- 17:25Z retention-sweep landed (`5a10f5dfa`, evidence `af0359703`):
  automatic byte-budget retention, its dial, schemas, test and schedule seed
  removed; default's per-minute row retired; weekly reachability GC stays.
  Live: reachability gate open 3.36 % → 100 %, zero scheduler key walks.
  Store reset proceeds the moment the cold-arming fix lands (a reset must
  not republish through the broken path).
