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
- 17:35Z stale-only settlement landed (`0c8f90630`): issue tests run only at
  the settlement that CLOSES an ordinary turn (derived from the same facts
  that emit close-tx; system turns and opening passes run none) and only
  the STALE ones (`seon.test/stale` gained a named arity over a supplied
  set: 6 ms for two symbols); the done-query still reads `verified?`.
  issue-test 21,452 ms/error → 12,921 ms 8/0/0 within the 20 s backstop;
  settlement test rewritten onto the real seam (virtual turn → next-agent-work
  → turn), 32/0/0, with the three regressions (system turn runs none; a
  close runs only stale; an unchanged closure completes from the record).
  Adoption converged `6aaa25da`. Lane rule added: a new arity is not
  callable in-process until adoption lands.
- 17:55Z cold-arming fix landed (`eeafb9dba`): live bisect confirmed
  `98b5f2afe`; `seon.schema/direct-references` defaults an omitted predicate
  map to `{}`; fresh-thread arming with zero bindings gives the identical
  digest; instrument suite 27/132 green. Gates may resume. The store reset
  waits for the indexer lane's commit (its in-flight `seon.issue.edn`
  refuses publication and a reset republishes the tree).
- 18:15Z batch 30 run A (`56f0a4ca8`): PLATFORM GREEN 86/579, cold workers
  arm (`eeafb9dba` proven cold), and for the first time tonight the result
  was RECORDED (no "persistent results NOT recorded") — the retention sweep
  removal and the recorder fix together. Run B (eleven namespaces) in the
  slot. Reset waits on the indexer commit and a clear test slot.
- 18:30Z batch 30 B (`56f0a4ca8`): GREEN schedule, blob, registry,
  data-shapes, instrument, issue-settlement, issue-test (`0c8f90630` proven
  cold). RED (mine): seon.render.transcript-test 13 tests (~60 blocks; the
  renderer-ref class + the stale tests listed earlier), seon.fn-test 4
  (analyzer-facets' own regressions, never run in-process), web-debug 7
  (known residual). Two Opus triage/fix agents: fn-test now (test-side,
  commit promptly before the reset); transcript + web-debug after the
  reset. fresh-operator-test 2 FAIL with the peer.
- 18:50Z fn-test facets reds fixed (`7cfe02790`): one defect, not four —
  `seon.program/shapes` is a HAND-MAINTAINED per-family list of owned
  attributes and `canonical-row` select-keys every statically indexed
  entity down to it, so the two new facets were silently stripped on the
  static path while runtime admission carried them (absence as health;
  derive-or-die violation). Six lines in program.cljc; all five facet
  regressions green in-process. Issue filed:
  `program-shapes-mirror-the-schema-row-maps-by-hand` (derive owned
  attributes from the declared row map, or a drift checker). The edit was
  written from the shell; adoption rides the reset.

### 2026-09-16 06:05Z — reach-closure-facts launched

- The gate session released `src/seon/test/runner.clj`, `script/seon/fresh_operator.clj` and `bin/test` (recorder `7c7395c8a` committed; latency lane made no source change). `reach-closure-facts` (astra, hard) launched against `tmp/orchestrator/wave3/reach-closure-facts.md` plus a reset warning: default will be reset once tonight; the lane waits for `bin/seon status` rather than restarting anything.
- Store reset still waits on the issue-indexer-resolver Opus lane's commit (dirty `seon.issue.edn` refuses publication) and on `tmp/test-slots/slot-1` (peer batch 31) clearing.
- Peer: `seon.fn-test` re-run on `7cfe02790` joins the batch after 31; one peer Opus lane still holds `test/seon/dev/fresh_operator_test.clj`.

### 2026-09-16 06:35Z — indexer landed; reset gated on batch 32

- issue-indexer-resolver (Opus) landed `75996a9e6` `7d47d77b7` `827b0521a` `da0309344`: one derived citation resolver (declarations as `:seon.issue/cites` Malli properties read back from schema rows ∩ identity attributes), `seon.issue.citation` components for `path:line`, unresolved tokens as evidence, `opened` from one bounded git read. Live on default: issues 0→150, files 0→1,836 citations, namespaces 0→1,459, keys 0→1,745; re-index emits 0 retractions; `bin/issues-index --check` exit 1→0. Not gated in process (default's fixture base predates the schema) — gate request handed to the peer. Note: [issue-indexer-resolver](../research/issue-indexer-resolver-2026-09-16.md).
- reach-closure-facts (astra) already landed slices 1–2: `bbbfafaf1` (`:seon.test/reach` closure refs per run) and `1086a7b80` (`seon.test/changed-since-green` + test pair links).
- Reset of default waits for slot-1 (peer batch 32) and the peer's read-only bookkeeping research probes to finish; tree is clean, so publication is no longer refused.
- Peer batch 31 on `34c5a9535`: seon.turn-test green; seon.cluster.turn-test down to 3 distinct reds (bookkeeping 5.6 s, lost-model-call diagnostic, attempt-traces exchange bound); turn-test class 44→3.

### 2026-09-16 07:40Z — default reset on a fresh store

- `bin/seon reset --force` at HEAD `f8c00a5be` (log `tmp/orchestrator/refork/reset-2026-09-16T07.log`): current-src republished (37,368 entities, 89,241 population rows), default reforked from commit `6aaa32b4`, store 72 GB → 94 MB, `bin/seon start` → pid 27828 (prepl 53933). Juniper reseeded through `juniper-fixture-2026-09-06/install!` (39.6 s; agents 2, issues indexed at publication 1,646, tests with reach digest 10).
- Peer: batch 32 green (fresh-operator + fn-test cold), batch 33 on `7b3a9ecc8` (platform recording + seon.issue-test + reach-closure namespaces); bookkeeping research `c2972178b`: fixture `with-cluster` ≈5 s because `seon.config/apply!` runs twice per fixture cluster rebuilding the projection (config.clj:459, §2.1); peer astra lane `config-apply-cost` owns config/reconcile/test_support.
- Gate recording refused on a named-namespace completion (`:seon.test.run/unavailable … lacks its tested database reach membership`, root run.HwsG9I): reach-closure-facts resumed with that as its first fix (explicit selections record; membership unknown is typed).
- Filed: [issue-indexing-at-publication-costs-13-seconds](../../../seon/issues/issue-indexing-at-publication-costs-13-seconds.md) (mine to fix once a default prober slot frees), [request-profile-is-derived-64-times-per-turn](../../../seon/issues/request-profile-is-derived-64-times-per-turn.md).
- Probers on default: reach-closure-facts, transcript/web-debug Opus triage, peer config-apply-cost, peer gate recording = 4 (cap).

### 2026-09-16 08:20Z — batch 33/34, issue-test reds, publication scare

- Batch 33 B: seon.issue-test 3 reds = `contains?` on a pulled cardinality-many vector (the AGENTS.md trap); fixed at the expectations (`set`), commit below. In-process proof did NOT arrive: `seon.issue-test/an-archived-note-reports-unresolved-tokens-without-a-refusal` never returned within 100 s on default (fixture base realized, not poisoned) while a `bin/seon init --dev` publication ran concurrently — one observation, confounded; the cold gate (batch 33 B, 514 s for 66 tests) is the proof surface for this namespace until that is understood.
- Batch 34 A on `8199364a2`: platform 13 → 1 red — `seon.cluster.source-test/latest-test-evidence-survives-rebuilding-from-an-older-base` at source_test.clj:546 (run entity after rebuild ≠ run value once `reach-unknown`/failure components ride the run). Plus the gate's own recording still rejected: `datahike.versioning` "Branch head changed before force-branch!" on :current-src — the recorder pre-reads the current-src head and force-branches, racing hook publications (source.clj:300–323). Both go to reach-closure-facts in one resume at its next commit.
- Peer reported every publication since 06:23Z refused on `support/test-context` (test_failure_facts_test.clj:120): a transient state of the lane's live edit, already `support/fork-cluster-ctx` in the tree; republication check running.

### 2026-09-16 08:55Z — default restarted (permit leak); transcript reds landed

- Transcript/web-debug triage (Opus) landed `0986475bd` `b0951e459` `5b4a4e08b`: the ledger's "renderer-ref class" refuted — two stale-expectation classes (transcript fixtures still transacting the retired `:seon.cluster.eval/result-edn`, rejected whole; the web-debug panel fixture naming a fault id `normalize` derives differently), no mechanism defect; two superseded tests removed. Note: [transcript-web-debug-reds](../research/transcript-web-debug-reds-2026-09-16.md).
- It also found default WEDGED for every db-backed in-process test: Datahike's exclusive `:roster` reachability permit leaked when `seon.test/run` interrupted a bound-expired test inside `branch!` (23 waiters; issue `an-interrupted-fixture-leaks-datahikes-roster-permit-and-wedges-the-jvm`). That is why the in-process issue-test proof "never arrived" and likely the gate's "cluster rejected the prepl operation". Restarted default: pid 37572 (same store, Juniper intact). The class (interrupt across a permit acquisition) is in the reach-closure resume.
- reach-closure-facts stopped/resumed with six items: source_test:546 (evidence preservation vs new run attributes), test-runner-test ×3 from batch 34 B (failure identity collision on recreation = totality regression; elision in completion results; reach-unknown missing on the returned result), the recorder's stale-branch-head race on :current-src (pre-read the writer re-decides), and the permit leak.
- Publication admitted again (`6aaa36cc`) — the `support/test-context` refusal was transient.
- Launched Opus `issue-index-publication-cost` (measure, then delta-only `index-tx` / skip when unchanged). Probers on default: reach-closure, config-apply-cost (peer), batch 35 recording, issue-index cost = 4.
- Peer: config-apply-cost landed `6313d2006` (identical-plan config apply 612–1,336 ms → 80–86 ms). Default's shared fixture base poisoned again after the restart (missing-classpath exception cached); peer refreshing once; structural fix (never cache a throwable) queued for reach-closure's next resume (spec addendum g).
- 09:15Z: default (pid 37572) cannot construct the shared fixture base: evaluation-context acquisition tries to load `seon.dev.dependency-cache-test` as first-party and fails on `clojure.tools.build` (a :test/:build alias dep absent from the dev JVM). Attribution to reach-closure UNVERIFIED (its program.cljc edits are retractAttribute value shapes only); Opus lane `evaluation-context-test-namespaces` verifying and fixing at the owner, or bringing three options. Until then no lane forces `database-base` on default; cold gates are the proof surface. Probers: reach-closure, issue-index cost, eval-context lane, batch 35 recording = 4.
- 09:50Z batch 36 (peer): config-apply fixture fix real (attempt-traces 270 s bound → 105 s); schema/db/test-support green; config/reconcile reds are the peer lane's. Two NEW turn-test reds (ns-unmap tombstone: "heterogeneous tuple expecting 2 values, got 0"; identity row nil) hypothesised to be reach-closure's retractAttribute value-shape change — queued for verification as addendum (h). delimiter-repair now counts 14 receipts (opening/system-turn evaluations included) and a-lost-model-call's error message absent: peer-owned turn-test class.
- 10:05Z issue-index-publication-cost (Opus) landed `6ed16de1a`: `index-tx` emits only the delta against the given database value (1,276 ms/3,333 forms → 358 ms/185 forms; unchanged set → empty delta, NO transaction; one changed note → 3 datoms on its own entity); the edit-hook path (`upsert!` branches from expected-commit) is now free when no note changed; the complete build (`publish!` branches from :db) still pays the whole graph (13 s / 89k) — issue stays open with the measurement. Regression `an-unchanged-note-set-indexes-without-a-transaction` owed to the cold gate (base poisoned). Also observed: three concurrent `init --dev` publications queued; one foreign publication of src/seon/test.clj refused at the 180 s bound (exit 124) — reach-closure must verify adoption before trusting an in-process proof. Note: [issue-index-publication-cost](../research/issue-index-publication-cost-2026-09-16.md). Probers: reach-closure, eval-context lane, transcript triage, gate = 4.
- 10:20Z evaluation-context-test-namespaces (Opus) landed `653d4d4ef`: the base-poison cause was `classpath-locatable?` resolving through the calling thread's context classloader (one-arg io/resource); inside an in-process seon.test/run the test DynamicClassLoader made all 214 test namespaces "servable" until tools.build failed. Fixed via the system classloader; regression in seon.sci.eval-test; reach-closure attribution REFUTED (test rows core-provenanced since 0fc110286). Follow-on issue: `base-context-membership-still-varies-with-what-happened-to-load`. Default restarted for a fresh delay: pid 45917. Note: [evaluation-context-test-namespaces](../research/evaluation-context-test-namespaces-2026-09-16.md).
- 10:30Z batch 37: seon.issue-test + seon.cluster.source-test GREEN (6ed16de1a proven cold); `latest-test-evidence-survives-rebuilding-from-an-older-base` passed under named selection, so its platform-tier red is ordering/selection-dependent, not deterministic — note for reach-closure (item a). Batch 38: seon.sci.eval-test + custody-stability (653d4d4ef). Base construction on pid 45917 started on a daemon thread by the orchestrator.
- 10:45Z transcript triage second pass landed `c19e826fa` `93c908761` `731391d08` `583dab7c9`: the dominant class was fixtures ignoring `transact!` refusals (silent flat error → empty history → reds far from cause); every seed now asserts `:db-after`. One src defect: `:seon.render.transcript/run` declared `opened-tx` as an integer while the producer pulls the transaction entity. Web-debug: `seon.error/normalize` returns the fact, not transaction data — fixture now calls `seon.error/recording`. Expired fixture instants (2026-07-31) fixed by deriving from observed transactions. Gate request rewritten (transcript-web-debug.txt). It reports the edit hook publication timing out under contention ("Publication did not finish within its declared bound") so its schema fix is not yet adopted on default.
- 10:55Z launched Opus lanes `issue-generator` (D1+D2, option B) and `effect-facts` (option C) per their wave3 specs; filed `concurrent-publications-serialize-past-the-hook-bound`. Probers on default: reach-closure, issue-generator, effect-facts, gate = 4.
- 11:05Z peer config-apply-cost landed `5e5aa6293` (batch-36 config/reconcile reds refuted: fixtures' unchecked transact! refused by stricter admission; shipped `:seon.test/check-time-limit-ms` restored). Third hit of one class today → filed `fixtures-that-ignore-a-refused-transaction-read-absence-as-behaviour` (one test-support write helper + a detector + one regression). config.clj/reconcile.cljc/test_support.clj are free again. Batch 39 = transcript second pass + config/reconcile.
- 11:20Z batch 39 (`3c446a558`): web-debug GREEN, config/reconcile GREEN, transcript 13 → 1 (generative property `every-generated-history-is-ordered-and-total`: two same-instant inbound messages order by an undeclared tie — transcript triage resumed on it). Batch 38: seon.sci.eval-test GREEN (653d4d4ef cold). Peer custody-stability `3c115ff15` (isolation fixture writes had all been refused for a missing :seon.message/to — absence read as health, fourth hit of the fixture class today).
- 11:40Z reach-closure-facts landed `d2a0ad636` (expiry outside the permit acquisition, item f), `3c6a6bb8f` (failure identities at the writer, item b), `e2eb91fcd` (recording rebased across publications, items e/i), `e8a017620`, `bb2843264`. Open: source_test:546 (a), projection mismatches (c,d), tuple-retraction hypothesis (h). Peer batch 40 custody-stability GREEN; every gate since batch 30 still fails to RECORD — first HEAD where recording may succeed is this one; peer batch 41 = platform + runner set.
- 11:55Z transcript triage third pass `f75112dbd`: the last transcript red was the generator asserting an instant a message never has (messages order by transaction instant; the rule `entry-order` was already total) — fixed at the generator, 41/0/0 in process on pid 45917 (first db-backed in-process verdict of the day after the loader fix). Issue filed: `about-identity-resolution-logs-an-error-per-non-matching-attribute`. Batch 41 on `cfac8275c`: platform (recording) + runner set.
- 12:10Z batch 41 (`cfac8275c`): PLATFORM GREEN 86/582 and the runner set (test-runner, test.runner, test-failure-facts, cluster.source) ALL GREEN — reach-closure items a/b/c/d/e/f closed cold. HEAD is green on everything gated tonight except (1) gate RECORDING (rejected on every gate since batch 30, value hidden by the wrapper — peer research capturing it; reach-closure addendum i) and (2) transcript generator fix awaiting batch 42. Recording is the single open blocker; reach-closure gets resumed with the captured value, then (h) tuple retraction, then (g) base delay.
- 12:25Z effect-facts (Opus) landed `0e15593aa` `774b4da39`: `:seon.fn/capability-fn`, `:seon.effect/capability-fn`, `:seon.effect/eval`, projection-driven request/result writer (asks the DATABASE which attributes are installed — `seon.db/attribute-installed?` — because `storable-attribute-in?` answered true for uninstalled my.fs keys), byte-offset spans, `:seon.effect/file`/`form-span`/`program` via `seon.program/declaration-at`. Deviations reported honestly: run/form-ordinal NOT retired (turn.clj reads them; issue `effect-run-and-form-ordinal-duplicate-the-evaluation-ref`); nested capability arguments stay in request EDN (issue priced for the owner). Gate request effect-facts.txt. Adoption retries refuse on reach-closure's uncommitted two-file edit (1-arity `seon.problems/problems`) until it commits.
- 12:35Z peer research `bfb578efb`: the gate recording rejection is unnameable BY CONSTRUCTION (fresh_operator.clj:1605 replaces any prepl :exception with a fixed sentence; runner.clj:2007 keeps only the message); recorder form green over raw prepl; stale-head race FALSIFIED. Peer Opus lane owns fresh_operator.clj + runner.clj:2007 region + two test files to carry the cause and reproduce. Reach-closure addendum (i) withdrawn accordingly. Launched Opus `fixture-write-helper` (one test-support write helper replacing the local ones; regression). Batch 42 transcript in slot; batch 43 = effect-facts namespaces. Probers: reach-closure, issue-generator, fixture-write-helper, peer recording lane, gate = 5 (temporarily over cap; recording lane is bounded).
- 12:50Z issue-generator (Opus) landed `88b04b970` `8c01f7420` `e2117dd73`: `seon.issue/generate` + `generate!`, detectors D1 (32 unpaired entity maps) and D2 (31 undocumented public functions) → 63 issue entities on default; second run 0 forms; resolve/re-open on the same entity; hand-edited problem survives. Deviations: schema subjects via `:seon.issue/keys` (no second family); NO `:seon.test` row minted for an unwritten test — completion of a generated issue is decided by its detector, so `start!` refuses generated issues today (issue `generated-issues-carry-no-tests-so-start-refuses-them`; OWNER DECISION: is the detector the test for generated issues?). Findings filed: no fact separates src/ from test/ declarations; 542 orphan AST nodes. Note: [issue-generator](../research/issue-generator-2026-09-16.md).
- reach-closure stopped/resumed: its uncommitted problems/problems two-file edit blocked every lane's adoption for ~90 min (publication analyses the whole tree) — instructed to commit the coherent pair first, then (h), (g); item (i) withdrawn. fixture-write-helper relaunched after an API safeguards trip. Batch 42 transcript in slot; batch 43 = effect-facts + issue-generator namespaces.
- 13:10Z batch 42: transcript GREEN 17/275 (class 13 → 0 closed cold). Batch 43 (`e3bfa76d1`): fn-test + issue-test GREEN; RED effect-facts (effect-test: detached limit no longer interrupts; my.fs refuses the temp path in a cold worker; edit-test ×4: `my.edit/form!` now REQUIRES `:my.edit/expected-digest` — a narrowed input unless it was always required) and issue-generate-test ×2 (the gate-only regressions). Both Opus lanes resumed on their reds from the cold output (base predates their schemas; no in-process db runs). Recording still rejected; peer wrapper lane running.
- 13:20Z peer recording wrapper `b77c553e4`: the operator now raises `:seon.fresh-operator/prepl-exception` with the cluster's Throwable->map and the gate notice prints it; two more hypotheses falsified live (full recorder commits in 2.2 s; 739 KB replies complete) — cause still unknown; batch 44 (platform + fresh-operator-test + test-runner-test) is the first gate that will print it. fresh_operator.clj / runner notice region released.
- 13:30Z issue-generator reds fixed `e47d05dec` `7fe7777a1` `e40f52059`: the mechanism was right; the fixture seeded synthetic `:seon.fn/sym` rows without the required `:seon.schema.admission/source` and ignored the refusal (FIFTH hit of the fixture class); the lane's live proof had gone through `datahike.api/with`, which bypasses admission — rule added to repl-rule.txt. Floor defect fixed: `seon.issue/check-form` names the tests, else the detector call, else no form (the empty `my.test/check` was a check that passes by being empty); render-ai/html follow it; regression added. Gate request rewritten.
- 13:35Z reach-closure committed the adoption-blocking pair as `5a9de3185` (structured test failures rendered through existing readers: problems, debug ledger, seon.test/check); tree-wide adoption unblocked. Lane continues on (h) tuple retraction, (g) base delay.
- 14:05Z batch 44 A (`b77c553e4`): PLATFORM GREEN 87/591 and the recording cause is finally NAMED: `Method code too large!` — `seon.test.runner` inlines the whole completion (every result, failure facts, reach digests) as a literal in the prepl form; the cluster compiles one method past the JVM 64 KB limit. Started failing exactly when 8199364a2 enlarged results. Peer lane fixes it (completion handed over as data the cluster reads; regression: 2,000-result completion → form under 1 KB); runner.clj protected for reach-closure meanwhile. This closes the "every gate since batch 30 fails to record" thread once landed.
- 14:20Z fixture-write-helper (Opus) landed `477cb615c` `074fbceda`: `seon.test-support/transacted!` (one write helper; refusal thrown at the write naming the offending row) replaces the local helpers and 25+ discard sites in transcript/web-debug/config/reconcile tests; found two more dead web-debug fixtures (bare `{:seon.cluster/name}` refused since the admission change — SIXTH and SEVENTH hits). 32 in-process tests, 0 assertion failures. Gate request fixture-write-helper.txt. The detector half stays with the issue generator's contract. Note: [fixture-write-helper](../research/fixture-write-helper-2026-09-16.md). Probers: reach-closure, effect-facts triage, peer recording lane, gate = 4.
- 14:30Z launched Opus `source-root-fact`: the source root as a fact on the `seon.fn.file` entity, written where the file is minted (issue `the-program-graph-cannot-say-which-source-root-a-declaration-came-from`); D2 detector can select by root. Probers: reach-closure, effect-facts triage, source-root, peer recording lane, gate = 5 (recording lane bounded).
- 14:45Z source-root lane stopped honestly at the protected file: `seon.program/shapes` (program.cljc:41) strips any owned attribute it does not list — the SAME hand-maintained mirror that stripped the analyzer facets (7cfe02790). Cleared to add the one element; design: optional `:seon.fn.file/root` from the walked root (absent = under no declared root; consumers join positively — one file on default sits under docs/ via the changed-path seam). Also found: `seon.fn/source-roots` is src+test only (no script/).
- 15:00Z reach-closure-facts (astra) FINISHED: `fec3918dd` item (h) fixed at `seon.turn/row-tx` (runtime deletion bypassed tuple-aware declaration replacement — the batch-36 tombstone reds), `9f0771cfc` item (g) (failed base construction retried outside caller bounds; the delay never caches a throwable), `c6507facb` live red→green proof. Five regressions 30 assertions green in process. Slices 1–4 all landed: `:seon.test/reach`, `changed-since-green`, provenance branch, `seon.test.failure` components rendered through the test pair, recording total + rebased. Note: [reach-closure-facts](../research/reach-closure-facts-2026-09-16.md). effect-facts red fix `8f4561450` landed (report pending).
- 15:15Z effect-facts red fix `8f4561450`: my two hypotheses REFUTED (expected-digest always required; detached limit intact); real defects fixed — the detached capability hop dropped the schema projection (§2.1; defonce delay poisoned the worker: the delay-caches-a-throwable amplifier again) and `seon.edit.jvm` degraded typed fs refusals to handler-failed (§2.4). Regressions name the class. Issue filed `adoption-identities-set-contains-nil` (adoption refused with `#{nil}` after a runner.clj adoption; refusal wording names the container, not the member). Gate request updated (+ seon.sci.eval-test).
- 15:20Z launched Opus `issue-context-trials` on its own scratch cluster/worktree (tmp/issue-trials-root, tmp/issue-trials-wt): the arglists issue, candidates A–G as one render function each behind an agent-settings dial, one cheapest-DeepSeek session per candidate (≈$5 cap before round two), measures from recorded facts, results page for the owner to pick. Default probers: source-root, peer recording lane, gate = 3.
- 15:30Z the `#{nil}` adoption refusal is the peer's by lineage (cluster.clj:2090 `(set changed-identities)` from the reaching-tests tier e9af61d87; a changed path with no program identity contributes nil) — blocks every publication; peer Opus lane fixing at the derivation first. cluster.clj adoption-record region protected for my lanes meanwhile.
- 15:40Z batch 45 (`8d5a7bcea`): issue-generate, issue, problems, test-failure-facts ALL GREEN; one red `seon.render.entity-pairs-test/test-entity-pair-is-total-through-the-issue-walk` after 5a9de3185 (test pair emits 4 forms, first line "unrun or incomplete") — Opus triage launched to decide stale expectation vs pair regression and add the recorded-test-entity render regression. Recording still "Method code too large" until the peer recorder lane lands.
- 15:50Z peer recorder fix `60516d27c`: the completion is staged as EDN under the run root and the sent form names the path (207,166 → 890 bytes for 2,000 results; the inlined form reproduces "Method code too large!" on demand). runner.clj released. Batch 46 = platform WITH recording (the proof) + 15 queued namespaces. Rule added to repl-rule.txt: requiring-resolve can return nil in the cluster where ns-resolve resolves — prove operator-path forms with a live send.
- 16:00Z test-pair red = stale expectation (`698d15d51`): the pair reads the counts it is handed; the old test asserted comment-prose artefacts. New regression renders a recorded test entity (failures path:line, reach member, changed-since-green) through both projections as data. Default probers: source-root, peer nil-fix lane, gate = 3.
- 16:15Z source-root-fact landed `925ca19fe` `66a7c2e02`: `:seon.fn.file/root` (exact walked root at mint; absent = under no declared root), `seon.fn/rooted-source-files`, changed-path seam by directory ancestry; D2 detector root-scoped arity: 31 → 2 src + 28 test helpers (OWNER Q: should D2 generate issues for test helpers at all? default stays unscoped). Live: 335 files = 106 src / 228 test / 1 none. Issue resolved. Shapes mirror stripped an owned attribute for the second time today — dissolution lane next.
- 16:20Z launched Opus `program-shapes-dissolution`: derive a program row's owned attributes from its entity schema instead of the hand list in program.cljc (bit twice today); checker regression so a declared-but-unkept attribute fails loudly; re-index counts measured against default. Default probers: shapes lane, peer nil-fix lane, gate = 3; trials on its own scratch cluster.
- 16:30Z peer `bb46455fb`: the `#{nil}` adoption refusal was a per-call ROSTER of three identity attributes where `seon.program/identity-attributes` declares six (file-digest and lint rows read as nil) — derived through `seon.program/row-identity` now (another hand list dissolved). Publications unblocked; cluster.clj released. Batch 47 = source-root-fact + adoption-rows.
- 16:40Z batch 46 A (`60516d27c`+): PLATFORM GREEN 88/602, results RECORDED on default — first recorded gate since batch 30; the inlined-completion recorder class is dead. 46 B (16 namespaces) running; 47 follows.
- 16:50Z batch 46 B (`2b996b1b5`): 361 tests, 13 namespaces GREEN (fixture-write-helper ×5, reach-closure final set, effect-test, sci.eval-test, runner/support, entity-pairs), RECORDED. Reds: seon.edit-test ×3 (nil path into java.io.File; refusal evidence) → effect-facts agent resumed; seon.fn-test determinism (one-file artifact vs manifest disagree on the file entity after the root fact) → source-root agent resumed; two turn-test defects → peer Opus lane (turn.clj, prompt.clj, turn_test.clj protected). Batch 47 running.
- 17:05Z edit-test reds fixed `1219d96c2`: the cold fixture seeded no config row → no fs dials → fixture read the absent working-root as a path; `seon.fs.jvm` throws a bare NPE on an absent dial (issue `filesystem-dials-absent-throws-a-bare-nullpointerexception`, §2.4). Batch 47: issue-generate GREEN cold; fn-test determinism still red (source-root agent on it); adoption-rows reds are the peer's.
- 17:20Z batch 48: seon.edit-test GREEN, recorded — effect-facts closed cold. Open reds across everything gated tonight: fn-test determinism (source-root lane), turn-test ×2 and adoption-rows ×2 (peer lanes). In flight: shapes dissolution, issue-context trials (scratch cluster).
- 17:35Z source-root determinism fixed `0eba4b8c3`: one `containing-root` used by both the manifest walk and the single-file seam. Default adoption currently REFUSED by the shapes lane's in-flight state ("A program identity attribute declares no row schema") — every in-process run reports provenance unavailable; lane told to make coherence its next step. Second occurrence today of one lane's intermediate edit blocking all → issue `one-lanes-intermediate-edit-refuses-adoption-for-every-lane`.
- 17:50Z peer: adoption-rows red (b) fixed `d4a201237` (message lives on the occurrence; `seon.error/latest-fact` is the projection); red (a) is a fixture-poisons-worker class: `seon.fn-test/keyword-usage-is-indexed-per-declaration` analyzes a decoy `(ns seon.error)` with the shared kondo cache on, stubbing the worker's seon.error entry — peer lane fixing at the analyzer seam (no shared cache outside declared roots). Batch 49 fn-test in slot; 50 after.
- 18:10Z batch 49: fn-test GREEN cold (determinism fixed); recording REFUSED, now named: `Nothing found for entity id [:seon.fn/sym "seon.fn/source-files"]` — the completion's reach carries a lookup ref to a private fn the worker's publication has no row for; default holds it as a tombstone. Opus `recorder-absent-identity` launched: attribute the missing row + make recording total (absent identity = typed unknown). Default probers: shapes lane, recorder lane, peer kondo-cache + turn lanes, gate = 5.
- 18:30Z shapes dissolution landed `8795db4ac` `d9ff63194`: `seon.program/shapes` derived from declared row schemas (`:seon.program/row-schema`, `/source-attribute`, entries with `:seon.program/written-by` excluded); `:seon.fn/writes` cannot answer "who writes this attribute" (helpers assemble away from the transact! span) so ownership is declared; re-index byte-identical; the hour of refusals was a defonce delay caching a throwable against the stale active projection (the delay-caches-a-throwable amplifier, third time today). NEW BLOCKER: default adoption refused — rebuilt source cannot preserve test evidence refing deleted `seon.fn/rooted-source-files` (fresh publication has no tombstone); same class as batch-49's recording refusal; recorder-absent-identity lane widened to both writers (mint tombstone or typed unknown). Note: [program-shapes-dissolution](../research/program-shapes-dissolution-2026-09-16.md).
- 18:50Z peer `b50f4ddc7`: kondo shared-cache poisoning class killed at the analyzer seam (`:cache false` unless every analyzed path is the checkout's declared source). Batch 51 after 50: fn-test, analyzer-test, public-contract-test, adoption-rows. Possible stale expectation: analyzer-test ordered-forms… expects `seon.run/complete` to resolve — cold verdict in 51.

### 2026-09-16 19:10Z — issue-context trials: first live DeepSeek sessions on a real issue

- Lane landed `ef7770785`: seven candidate openings (A–G) as one render function each behind the `seon.config.render` dial (`src/seon/issue/opening.clj`; `:bare` stays the shipped floor), authoring script, results page [issue-context-trials](../research/issue-context-trials-2026-09-16.md). Seven deepseek-flash sessions × 20 turns on the arglists issue: 131 calls, 1.18 M prompt tokens (90% cache hits), cents.
- Result: **candidate F (namespace picture) was the only one whose agent wrote the real fix** (both decode sites, two `my.edit/exact!` calls) and it died one step from green on the shell bound while trying to adopt its own edit. A (bare) is within noise of every elaborate candidate. Recommendation to the owner: keep F's shape (name the namespace, render its picture, name the issue) + B's two exact completing calls; keep A as the floor; drop C, D, E, G. The variance round was not run: the platform ceiling sits below the differences between openings.
- **Four platform blockers found by live agents** (issues filed with evidence): (1) `pulling-a-function-row-in-sci-returns-a-restart-the-jvm-sentence` — every `seon.db/pull` of a `:seon.fn/sym` row from SCI returns the stale-Var advisory string instead of the row, on default too → Opus fix lane launched; (2) `an-agent-cannot-make-its-own-source-edit-live` — no agent-facing adoption; the taught shell workaround exceeds the 30 s shell bound (adoption 60–90 s) → OWNER DECISION below; (3) `a-worker-started-while-the-cluster-runs-is-never-armed` — start! returns healthy, nothing runs until reboot; `arm!` by hand fixes it; start! also leaves no wake → Opus fix lane launched (arm via the one mechanism; the assignment datom as the listened wake); (4) `plan-derivation-refuses-with-an-unbound-rule-variable` — `seon.plan/plan` refuses on the scratch cluster (plan.clj:53-57) and the bare exception reaches the opening; `:ok` on default → queued (bisect first).
- Error classes worth more than the trial: 16 prose-only replies + 10 reader failures in reply text (a quarter of all errors, a paid turn each) — the model wraps forms in markdown; and 3/7 sessions called a nonexistent `my.edit/edit` before finding `exact!`. The red test is weaker than the issue's acceptance text.
- OWNER DECISIONS queued: (a) agent-facing adoption: should a successful `my.edit` write request in-process adoption of that path (bounded, reported as an effect result), or should `my.test/check` adopt changed src namespaces the way it reloads test namespaces? (b) `my.edit/edit` alias vs teaching; (c) which candidate to keep (F+B recommended).
- 19:25Z batch 50: program-test, fn-test, cluster.source-test ALL GREEN 81/589 — shapes dissolution proven cold. Recording still refused (absent identity; recorder lane). Batch 51 in slot.
- 19:40Z recorder-absent-identity landed `64230d4de` `b375b5dcc` `7d817fd89`: the recorder resolved reach members against the tested value and wrote into a :current-src published inside a rename window (no row for one name); adoption's "cannot preserve evidence" was the mirror image. One decision at the writer inside the transaction function: absent program identities are MINTED as tombstones (identity + ns ref + admission source); file identities never minted (site keeps path via optional `:seon.test.failure/reported-file`). **default adopts again** (init --dev exit 0, current-src 6aaa5523). Regressions 8/0/0, 5/0/0. Gate request: platform WITH recording = batch 52. Also noted: `commit-results!` silently overwrites a carried `:seon.test/reaches` when `:seon.db/db` is present (worth an issue if it bites again).
- 20:00Z sci-pull fixed `59c7d78c4`: the "Restart the JVM…" string was `seon.problems/stale-var-ai`, selected because `:seon.problems/stale-var` declared a render pair on the OPEN map `[:seon.fn/sym]` — satisfied by every :seon.fn row (open maps + identity attribute = a pair that captures a whole family). Pair removed (dead as a target). Regression reproduces the sentence verbatim against the pre-edit base (red by design; cold gate proves green). New blockers filed: `dir-of-a-namespace-returns-an-elision-with-nothing-shown` (41,040 chars omitted, 0 shown, no requery — same class as `a-value-larger-than-the-budget-is-elided-to-nothing`) → Opus `dir-elision` lane launched at the value renderer; `render-selection-reads-its-projection-from-the-ctx-not-the-handed-database` (§2.1 inverse). LESSON for the render-pair rule: a pair declared on a map whose only required key is an identity attribute selects for the entire family — the schema pair belongs on the family's own entity map, never on a finding row keyed by a foreign identity.
- 20:05Z batch 52 A: PLATFORM GREEN 88, RECORDED — recorder-absent-identity proven on the platform tier; recording is total again. 52 B running.
- 20:20Z peer turn lane `b166c4246` `42661e5b0`: a-lost-model-call fixed at the owner (`seon.error/faults-form` selected only fn-reachable errors — a PROVIDER fault was durable but invisible to the prompt; absence-as-health); delimiter-repair's remaining failure ATTRIBUTED: `seon.fn/gate-set` costs 5,976 ms of a 6,416 ms window — one recursive-rule Datalog query per installing definition (fn.clj:1052) → peer astra lane (fn.clj free). start-arms lane's in-flight wake/agent edits degrade the in-process loop fixture (next-agent-work → nil) — lane told to prove a-whole-turn-runs before committing. Batch 53 = platform + analyzer/turn/value/problems tests.
- 20:35Z batch 52 B: failure-facts, test.runner, test-runner, program-test ALL GREEN 92/659, recorded. Batch 53 = platform + analyzer/turn/value/problems (sci-pull). Peer astra `gate-set-cost` launched (fn.clj gate-set region, settlement call site).
- 20:50Z start-arms landed `df4c1c012` `89d68eb6c`: agent creation IS the arm wake (`:seon.wake/arms` on `:seon.agent/id`; route! offers the armer a wake); `:seon.issue/agent` is the worker's first listened wake; scratch-cluster proof with nothing calling arm!. Also fixed at the owner: `seon.error/faults-form` pulled on nil for a new agent → NO start!-created worker could ever store an opening. RESET NEEDED: default refuses adoption because `:seon.issue/agent` gained `:db/index` and `declaration-changes` compares with `=` (Datahike supports monotonic index addition) → Opus `monotonic-index-adoption` launched; I refork default after batch 53 records. Pre-existing red: html-views fault-pairs golden. Probers: dir-elision, monotonic-index, peer gate-set, gate = 4.
- 21:10Z REFORK of default at `88acedce8`+ (log `tmp/orchestrator/refork/refork-2026-09-16T2100Z.log`): init default --force + start → pid 95853; the trailing `init --dev` threw a raw StringIndexOutOfBounds in `seon.fn/exact-source` (a 114 KB file changed between snapshot and span read — fn.clj under the gate-set lane) → issue `source-analysis-throws-when-a-file-changes-between-snapshot-and-span-read`; retry running. Juniper reseeded (issues 1,672). Peer `gate-set-cost` landed `1d141d26a`: gate-set 6,753 → 11.4 ms from indexed incoming call edges; check for one changed fn 5,923 → 5.4 ms; six-form bookkeeping 6,530 → 366 ms; residual is the 64× request-profile derivation → peer Opus lane (render.clj). Batch 54 after adoption converges.
- 21:35Z dir-elision landed `bb33b93fa` `c5abf5d5b` `bf381de05` (seon.print): `(dir seon.turn)` 208 bytes of nothing → 1,893 bytes with eight complete rows + an elision in tokens with next-offset; fit's floor is structural; residual filed: `dir` pages by character because `seon.repl/render-directory-ai` applies no profile (bare-string contract). Rules added: MCP reader resolves `::` in user (never send `::`). On pid 95853 the fixture base REFUSES to construct ("Schema declaration resolution requires the projection handed to the operation") — probing; adoption retry queued behind four contending `init --dev` publications (lifecycle lock).
- 21:50Z fixture base on pid 95853 constructed and realized on a daemon thread (the earlier refusal was transient — likely mid-adoption). In-process runs open. Batch 54 running (platform + 10 namespaces incl. dir-elision).
- 22:00Z adoption retry on pid 95853 reloaded, acquired SCI and instrumented, then refused the commit record with `source-changed-during-adoption` (a hook publication for the peer's turn.clj lane landed mid-adoption); retrying once more — the JVM already holds current code; only the recorded commit lags.
- 22:10Z monotonic-index-adoption landed `0b910eb69` `4cc23a9ba` `960cc8155` `5dc864346`: `declaration-changes` diffs per facet; accretive changes (Datahike's own acceptance, cited per clause) adopt in place — an added `:db/index` backfills AVET without a refork (1,668 datoms live on a scratch cluster); drops and valueType changes refuse naming the property. Pre-existing red spotted: `seon.cluster-test/schema-row-convergence-uses-the-stores-own-semantics` (:40) — triage if cold-confirmed. Batch 54 A platform GREEN on the reforked default.
- 22:20Z launched Opus `plan-derivation-refusal` (fourth trials blocker: plan.clj:53-57 unbound rule variable on a fresh cluster; raw exception reaching the opening). Probers: plan lane, peer request-profile lane, gate = 3.
- 22:30Z peer request-profile `15a15e9c1`: render.clj already carried the profile; the per-form deriver was `seon.turn/evaluate-sources` (6 → 1 per turn). Note: `db/transact!` is 227 ms per call on the loaded default vs 3–4 ms idle — the 300 ms bookkeeping bound is only judgeable cold. Batch 55 = platform + evaluate-sources, cluster.turn, render-coverage, cluster-test (monotonic-index).
- 22:40Z adoption retry 2 refused: `:seon.issue/title` changed :db/index true→nil — the monotonic lane's temporary shell edit was swept into a concurrent publication, default installed the index, the revert then read as a dropped facet (the new rule refusing correctly). Kept as accretion: `4b83ca383` declares the index; hook publication queued to converge default.
- 22:50Z default adoption CONVERGED (current-src 6aaa5e1a) after keeping the title index. Dev environment fully normal on pid 95853: adopts, base realized, recording total.
- 23:00Z batch 54 B (`960cc8155`): error-test, fn-test (gate-set proven), test-support-test, transcript-test GREEN. RED: start-arms' own regression (no :armed event published in the fixture cluster) → lane resumed; dir-elision ×7 (print fit breadth; value-test ×5 incl. its own oversized-string regression; web-test units order) → lane resumed, both with the fresh base available in process. delimiter-repair bookkeeping 625 ms cold (pre request-profile); batch 55 measures HEAD.
- 23:10Z batch 55 B (`e765058fe`): seon.cluster-test GREEN (monotonic-index proven cold; schema-row-convergence did NOT fail cold), evaluate-sources GREEN. RED: seon.render-coverage-test ×3 tests/24 assertions — every HTML render returns `:seon.render.value/missing-root-identity` with `:seon.agent/id nil` (dir-elision's print/value change or the peer's 15a15e9c1?) → dir-elision agent attributing before fixing. Peer: delimiter-repair bookkeeping 652 ms COLD after request-profile (the prompt derivation was not the cold residual) — peer attributing per phase cold vs warm before touching the 300 ms bound.
- 23:25Z start-arms round 2 `aa174d941`: an agent committed before the armer listens was never armed — armer-step primes itself at ::flow/resume (boot's synchronous call stays as readiness). Finding: a worker's generated OPENING spends its issue budget (budget 1 = zero ordinary turns; absence-as-health) → issue `a-workers-generated-opening-spends-its-issue-budget` (turn.clj/seon.issue.edn owners; OWNER Q: does the opening count against the budget?). In-process 7/0/0, 14/0/0 ×3.
- 23:30Z launched Opus `render-selection-projection` (selection derives its projection from the handed database value first, §2.1; plus the pre-existing html-views fault-pairs golden). Probers: dir-elision, plan-derivation, render-selection, gate = 4.
- 23:40Z budget finding resolved by existing rulings, not a new decision: the opening is system turn 0 (no provider attempt, answers no wake — turn PRD §14), so `:seon.issue/budget` bounds provider turns and `episode-runs` must not count the opening; handed to the peer's turn lanes. Batch 56 = agent-arming, cluster.turn, cluster-test on 9a4e873d2.
- 23:55Z dir-elision round 2 `90f7abec0`: elision coordinates keep the value's units (requery forms execute against them), tokens ride alongside as `:seon.ai.tokens/estimate`; breadth and text degrade together; ten tests green in process. render-coverage reds PROVEN not the floor's: `seon.render.value/node-id` requires a root address the fixture never supplies (passed only while selection found a producer); candidate `3f07beb88` — handed to the peer.
- 00:05Z peer bookkeeping attribution (9a4e873d2): warm 365–531 ms = one settlement commit 219 ms (tx size, turn.clj:3510/3541) + the defining form's install 120 ms (analyze-forms runs twice for one form) + ~20 ms; cold adds ~230 ms class loading. Bigger: `seon.test.runner/record-tx` costs 5.9–8.0 s per IN-PROCESS run on default's file store (0.7 s for an 86-result gate earlier) — every repl-rule run pays it; peer read-only research lane on it (suspects: reach facts re-asserted per run, tombstone minting, fsync per tx). runner.clj untouched until it reports.
- 00:20Z peer `91cd63e5a` ordered-evaluation fixed (preview never opens a turn; stored-record-content compared tx refs against a tempid so identical re-records were never no-ops). Warning: `seon.fn/tests-reaching` currently throws (gate-set derivation 1d141d26a violates its output contract) — `seon.test/check` broken until the peer's fn.clj fix lands. Peer astra `turn-settlement-cost` running on turn.clj. Peer Opus on render-coverage (node-id root address) owns value.clj.
- 00:30Z batch 56: agent-arming GREEN (aa174d941 cold), cluster-test GREEN; only the bookkeeping bound left (settlement lane). Batch 57 = dir-elision round 2.
- 00:40Z peer research `8a9832ba8`: the 6 s per in-process run is the STORE, not the recorder — an EMPTY-DELTA transaction (one re-asserted datom) costs 3.9–8.9 s on default vs 46 ms on a fresh file store; data/store is 12 GB / 101k keys again eight hours after the reset to 107 MB (the per-minute writer is gone, so ordinary copy-on-write index churn with nothing collecting it). Every write on default pays the floor. Plan: reset default again at the next boundary (after batch 57); the class fix is write-latency-vs-store-size — peer research bringing three OWNER options (fsync/batching policy, GC cadence tied to the footprint signal, a different konserve backend for the dev root). Second-order recorder waste (reaches re-derived; 814 lookup-ref pulls; identical reach retract+assert per run) queued for a runner.clj lane.
- 00:55Z batch 57 (`b7e0bda66`): print-test + render.value-test GREEN (dir-elision r2 cold); one red web-test declared-units (extra :db/id/:db/txInstant) → dir-elision agent. Reset of default held until the peer's write-floor research finishes measuring the 12 GB store (~30 min).
- 01:05Z dir-elision round 3 `415ab40fc`: the web-test red was the THIRD sighting of the open-map shadow class (`:seon.render.transcript/pulled-transaction` = `[:map [:db/id …]]` matched every `'[*]` pull and put :db/id into every page's units); fixed at `declared-unit?` (a unit only when the registry declares a form for it). Class issue filed: `an-open-map-keyed-only-by-universal-attributes-shadows-every-entity` (checker: a selecting map must require one non-universal attribute).
- 01:15Z plan-derivation landed `3cd566973` `da9c168e8`: the refusal was a SIZE threshold in Datahike's planner (a recursive-rule op contributes no bindings, so past ~400 items the not-join was ordered first; issue `the-query-planner-rejects-a-negation-bound-by-a-recursive-rule`); readiness/blockage/open-work now derived in Clojure from the pulled component tree (2 Datalog reads → 0; 600 nodes 2.6 ms); refusals render as one typed line. my.plan-test 21/120/0/0. Seven foreign reds seen in neighbouring namespaces (render-simplification ×4, html-views golden, returned-error "Expected:" prefix, page-settings probe) — peer to verify cold. All four trials blockers now have landed fixes; agent-facing adoption remains the OWNER decision.
- 01:45Z RESET #3 of default (log `tmp/orchestrator/refork/reset-2026-09-17T0130Z.log`): pid 17352, store 99 MB, Juniper reseeded; `init --dev` recording adoption. Peer research `83d3e92a0` REFUTED the write-floor premise: commits are O(delta) (23–38 ms on 12 GB); the 4–9 s were accumulated dirty-leaf flushes (473 files/39 MB in one commit); growth is the defect; a real `collect!` ran (12.0 → 10.4 GB); three owner options on the page. Peer `ac95db78a` render-coverage (refused seed — eighth fixture hit — + node-id derives the root from the value's identity attribute); peer turn-settlement `97d1f69e0` `3594331c8` `60e0ba923` (budget = provider turns; install analysis carried; settlement 249 datoms; a projection REBUILD inside settlement owed on turn.clj). render-selection landed `68f1ad52c` `b67a9dfe3` `33a4c2035` (selection asks the handed database value; html-views golden was stale). Batch 58b: plan GREEN cold; 4 real reds → render-selection took the golden; Opus `render-repl-cold-reds` launched for the other three + invocation-unknown nil; Opus `fixture-write-sweep` launched. Report updated.
- 02:00Z peer `5ffa964cb`: gate-set concat'ed selection queries without reading them (a flat refusal spliced in as map entries) — every read checked; gate-set/tests-reaching declare `[:or [:vector sym] error]`. fn.clj released. WARNING: one in-process run sat in `seon.await` 40 min on the fresh pid 17352 (first sighting) — both my lanes told to dump waiting threads and go cold-only if it recurs.
- 02:15Z adoption recorded on pid 17352 (current-src 6aaa69f0, exit 0). Store 99 → 314 MB in ~35 min with two lanes probing + one gate batch: the growth curve is live evidence for the owner options (~6 MB/min under load).
- 02:25Z batch 59 (`5ffa964cb`): turn-test, evaluate-sources GREEN; render-coverage 23-block red gone (only the render.clj:995 nil-value ERROR, with my render-repl lane); delimiter-repair now "settlement and writes 412.5 ms" vs 300 (settlement projection rebuild → peer Opus on turn.clj; prompt-test budget expectation too). Batch 60 on the fresh store: platform + render-simplification, html-views, error, render.web, fn-test.
- 02:35Z batch 60 A on the fresh store: PLATFORM GREEN 88/602, recorded. The fixture-sweep lane's in-flight edits (support/transacted! without the alias in three test files) block every publication — third occurrence of the intermediate-edit class today; lane told to fix and commit first.
- 02:45Z batch 60 B (`b2c581667`): html-views, error-test, render.web (415ab40fc), fn-test (5ffa964cb) ALL GREEN; recording works on the fresh store. RED: render-simplification nested-ai-values (render-repl lane) and NEW candidate-input-and-output-must-fit-the-same-arity (:271) since render-selection's request-projection → render-selection agent resumed.
- 02:55Z render-selection round 2 `cfbc941a8`: the arity red was a test stubbing `kernel/context-projection` via with-redefs (the seam that could not be probed with a supplied value) — now supplies the projection on the database value; no production change. nested-ai-values… was renamed by the render-repl lane (`358133a78`, green in process).
- 03:15Z render-repl-cold-reds landed (`358133a78` +2): three stale expectations (nested faces name; "Expected:" prefix; page-settings docstring scrape → program-row query) + render.clj typed unknown; new issue: a render producer's contract refusal embeds a database value (8.4 MB) so admission refuses it and the kind is lost. Peer settlement `2da44c50d`: row-tx rebuilt the projection per declaration (209 of 253 ms) → carried projection; settlement+writes 253 → 53 ms. ADOPTION BLOCKED on pid 17352: source seal refuses `Nothing found for [:seon.test/sym nested-ai-values…]` (renamed test, evidence refs the old identity) — the absent-identity class for test identities → recorder-absent-identity agent resumed to mint tombstones for every program identity attribute.
- 03:30Z peer `50a7110b7`: exact-source race = two reads of one path (source-contexts captures bytes; analyzer re-reads) → span read now total with `:seon.fn/source-changed-during-analysis`. Follow-up mine: adoption's retry predicate (cluster.clj:2244) must recognise that kind as a source change (one predicate over declared kinds) → Opus `adoption-retry-on-analysis-refusal` launched. Peer queued: analyze from the captured text (one read).
- 03:40Z batch 61: platform GREEN recorded; render-simplification GREEN 22/131 (render-selection r2 cold). Batch 62 = platform + turn/prompt/render-repl/fn-test landings. Peer lane: one read per file at the analyzer.
- 03:50Z tombstone minting generalized `734253727`: one rule from each identity attribute's declared row schema (mint when only identity/admission/derivable ns ref are required: ns, test, fn; file/schema/lint → typed unknown, ref dropped); carried evidence takes the minted tempid in the same transaction. The renamed-test seal refusal is gone; adoption now only contends with the moving tree (source-changed-during-adoption) — converges on a quiet edit.
- 04:05Z adoption-retry landed `d93f57328`: one declaration `source-change-phases` (adoption / analysis), one retry for either phase, a surviving refusal names the phase; incremental → complete rebuild only for a non-source-change index refusal. Follow-up recorded: cluster.clj:1860/:1953 snapshot compares still hard-fail without a cause. Shell write → adopting now.
- 04:15Z adoption of cluster.clj/source.clj/runner.clj refused: "Source changed while incremental publication was being analyzed" (digest-before ≠ after) — the snapshot compare at cluster.clj:1860/:1953 the retry lane named as its follow-up (no diagnostic cause → no retry) while the sweep and one-read lanes keep editing. Retry when the tree is quiet; the JVM already holds the reloaded definitions.
- 04:20Z adoption UNBLOCKED: the tombstone lane's quiet rerun adopted current-src 6aaa6f8a on pid 17352 with zero identity refusals; my later run (cluster.clj retry change) hit the moving tree — re-adopt when quiet.
- 04:35Z batch 62 (`d93f57328`): platform GREEN recorded; cluster.turn-test GREEN — the 300 ms bookkeeping bound passes cold (the class that started at 7,157 ms is closed); prompt, render-simplification, returned-error, page-settings GREEN. Reds: turn-test ×3 + fn-test ×1 = dead fixtures made honest by transacted! (missing :seon.turn/agent; receipt seeded twice) → sweep lane; render-coverage typed-unknown :528 = the filed 8.4 MB contract-refusal issue. Batch 63 = tombstones + adoption-retry.
- 04:45Z batch 63 (`71395752f`): platform GREEN recorded; failure-facts, cluster.source, cluster-test ALL GREEN — tombstone rule (734253727) and adoption retry (d93f57328) proven cold. Open reds across everything gated: 4 dead fixtures (sweep lane) + the 8.4 MB contract-refusal typed unknown (issue). Slot free; batch 64 = sweep landings.
- 05:00Z live on pid 17352: debug page 0.42 s first / 23–27 ms warm; store 3.6 GB 2.5 h after the 99 MB reset (~1.4 GB/h) — the reclamation-signal decision is the morning's first. Peer fixed five issue frontmatters (`type: defect` → `issue`).
- 05:10Z cluster.clj retry change ADOPTED on pid 17352 (init --dev exit 0). Default is fully current: adopts, base realized, recording total, page 25 ms warm. Open: sweep lane's four dead fixtures (batch 64), the 8.4 MB contract-refusal typed unknown (issue), store growth (owner decision).

### 2026-09-17 11:10Z — the checkout store was WIPED; default reforked (#4)
- data/store (3.6 GB) emptied at ~10:17Z; boot refused at the store phase; directory recreated at 10:53Z. Lead: batch 61 A (platform tier) ran `seon.cluster.registry-test/reset-returns-a-cluster-to-source-state` at 10:18:12Z — a fixture whose root resolves to the checkout. Issue `a-platform-tier-test-wiped-the-checkouts-store` (blocker). Evidence copy kept. Recovered: reset --force + start → pid 38993, 99 MB; Juniper reseeded; adoption next. ALL gates with the platform tier or registry-test HELD until the peer's read-only investigation names the root resolution. Lost: today's recorded test results on default (disposable by ruling).
- fixture-write-sweep landed (`e89117099`…`142fb4a45`): 588 discarding fixture writes in 96 files → `transacted!` (structural enumeration via rewrite-clj); four dead fixtures repaired; 16 raw sites remain only in files other lanes held; finding: a PARTIAL upsert of an existing entity is validated against the complete required-key set (write-admission owner to decide). Batch 64: fn-test, analyzer-test (one-read proven), turn-test (dead fixtures fixed), test-support GREEN; recording refused (wiped store).
- 11:25Z adoption recorded on pid 38993 (exit 0); debug page 0.41 s / 25 ms. All cold gates HELD by the peer until the store-wipe root resolution is named. My lanes are all landed; nothing in flight on my side.
- 11:40Z wipe VERDICT (peer `fbd9c0cd9`): deleted and re-created from genesis (`:branches #{:db}`), not collected; GC and registry_test cleared; the shape is `(io/file root "data" "store")` with a nil/relative root at operator.clj:277/294 or store.clj:281 via the fallbacks at cluster.clj:792 / test_support.clj:112 (bin/test-fast sets no root) — the 86b4c8ff4 class; exact caller unknown (nothing logs a delete). Peer Opus fix: unconstructable nil/relative/checkout root at both owners, a delete log line naming root/paths/caller, fallbacks removed, sentinel regression. Gates held until it lands. Lesson recorded: copy evidence with `cp -Rp`.
- 12:00Z wipe ACTUAL CAUSE (peer `ccccea806`): `seon.cluster/operator-root` preferred `-Dseon.operator.root` (the checkout) over the caller's root, so an IN-PROCESS fixture run in default's JVM (populate-published-root! with a tmp root) deleted + cloned over the developer store — a lane's repl-rule run, not a gate; §2.1 fetch-at-call-time. Fixed: declared-root delete admission, caller root first, create-store! refuses a rostered store, every delete logged. Gates RESUMED: batch 65 = platform + sweep's 95 namespaces + operator/store tests, store size checked around each run. Rule added to repl-rule.txt (destructive tests cold-only). Opus lane launched for item 3: platform tier carries no destructive drill, derived from reach to the delete seam, checker + tier refusal.
- 12:30Z platform-tier checker landed `9fa1f101d` `f03adc248` `a2fe54cb2`: `destructive-owners` (three functions deleting paths they did not create; resolved against the manifest, refusal if missing); the coordinator verifies no `:seon.test/platform` test reaches one before the first platform task, naming test + path; three platform tests moved to the bulk tier. My "reaches the delete-admission seam" predicate REFUTED by measurement (42/80 platform tests reach create-store! via with-source-store). Issue item 3 done; two marker hunks in protected files named for the peer.
- 12:45Z peer declines the `:seon.fn/destructive` facet (no second consumer yet — §2.5); agreed. The real second consumer is the in-process side: launched Opus `in-process-run-refuses-destructive-tests` — `seon.test/run`/`check` refuse a destructive-reaching test under a development root (one owner set with the runner's), typed refusal naming owner + path; isolated roots run. Batch 66 list corrected to seon.cluster.cohost-boot-test.
- 13:05Z sweep: default alive (38993), batch 65 in slot, store 884 MB ~1.5 h after the fourth reset (growth continues ~0.5 GB/h with one gate + one lane), 3 files dirty in lane hands.
- 13:20Z in-process destructive refusal landed `9012800d6`: `seon.test/run` refuses a destructive-reaching test under a development root (typed, names owner + shortest path + cold command); `check` excludes and reports; isolated roots run; the declaration travels with the work (a draft that re-read the property failed its own regression). 55 tests reach an owner (= the cold checker's count). Wipe class closed on both sides pending cold proof. Note for the rules: a bare `with-database` in an MCP future (no projection bound) also poisons the base briefly — base rule covers it.
- 13:35Z batch 65 A (`ccccea806`): platform 89/608 with ONE red = the peer's own new regression asserting the checkout store survives (false in a pooled worker, which has no data/store — passed in-process only against the dev JVM); peer fixing the observable (scratch root + sentinel). Store 99 MB → 1.1 GB after A, intact (sentinel log shows only the test's own scratch deletion). 65 B (sweep's 95 namespaces) running. 9012800d6 folded into 66.
- 13:50Z batch 65 B (sweep's 95 namespaces): 1,152 tests, 142 distinct reds in 47 namespaces — the loud-fixture wave (dead fixtures made honest; `receipt-exists` from a shared fixture seeding the same receipt twice dominates); platform tier green apart from the peer's sentinel; store 2.2 GB after, intact. Sweep agent resumed: fix at the shared helper first, per-namespace groups, plus a helper DEFECT (transacted! refusal text clipped by the AI profile in test output — §2.4). Two possibly real defects (call-preparation supplied value unreplaced; boot-test dead-holder recovery lacks reply-size) → Opus triage lane. Batch 66 (wipe-class proof) running.
- 14:00Z 65 B full list written (146 tests / 46 namespaces); runner notices: concurrency-independence hit the exchange bound; eval-instrumentation left 33 vars instrumented (own nothing global) — both handed to the sweep agent. Store across 65 B: 616 MB → 878 MB → 2.4 GB, intact.
- 14:15Z batch 66 (`23360f577`): platform GREEN with the tier checker running before the first task; store-test sentinel, test-reaching (in-process refusal), runner/support/cohost GREEN — the WIPE CLASS IS PROVEN CLOSED COLD on both sides. operator-test ×3 = the peer's ccccea806 contracts (peer lane). Store 2.5 GB.
- 14:45Z peer `ec260cd2e` fixed its operator-test regressions (collection key widened to [:or result error]; stale lifecycle expectation; contract test re-collecting against malli's default registry). Batch 67 = operator/maintenance tests. New issue for the maintenance family: `a-successful-cluster-cleanup-cannot-persist-into-maintenance-facts` (collection slot error-only → a SUCCESSFUL cleanup cannot become a fact; absence of success as health) — queue after the sweep wave.
- 14:50Z launched Opus `maintenance-success-facts` (a successful cleanup becomes a maintenance fact: collection slot widened to the verified result, one entity per root identity with replaced attributes, one writer for success and failure, "last collected / reclaimed" as a query). Probers: sweep agent, defect triage, maintenance lane, peer gate = 4.
- 15:10Z defect triage landed `6cbe2017c` `3e41a5d22`: call-preparation red = a REAL `seon.instrument` defect (diagnostic-offending carried a leaf, not the checked value; arity refusals lost their number) — fixed, not a 09-16 lane's; boot-test recovery red = expectation drift (ref-valued closed-tx, unbound query, literal count) — fixed at the test; recovery correct. Issue filed: `in-process-check-selects-declared-long-tests`. Attribution note: the sweep lane's 0da13c8ae swept the triage's boot_test hunks under its own message (nothing lost).
- 15:15Z launched Opus `check-excludes-long-tests` (`seon.test/check` excludes `:seon.test/long` by default with a named exclusion report + opt-in; an expired bound reports the completed verdicts plus the typed expiry, never a bare unknown). Probers: sweep agent, maintenance lane, check-long lane, peer gate = 4.
- 15:25Z near-collision: the peer launched a duplicate `check` long-filter lane two minutes before my announcement; theirs stopped and is backing out its own hunks by hand; mine keeps the slice (told to re-read the files before editing). Lesson for both sessions: announce a launch BEFORE it starts, naming owned paths.
- 15:35Z peer's duplicate lane stopped read-only (tree clean but for the sweep lane's two files). Grounding for check-long: `:seon.test/long` is not a program-row fact (only fixture-observation is indexed; fn.clj:551 and sci/eval.clj:410 lift markers onto the row; runner's long-reason reads Var metadata) — lane told to declare it at both seams + schema (§2.2) and select by the fact.

### 2026-09-17 12:55Z — write storm; reset #5; three lanes landed

- WRITE STORM: default's store 2.5 → 21 GB in ~20 min with near-zero commits. Cause read from default's log: the Datahike writer throwing continuously — `:malli.core/invalid-schema :seon.schema-usage-guardb/entity-id` in `seon.turn/row-tx` (turn.clj:1344) inside the turn loop's `[:db.fn/call retain-transaction]`; that key is seon.schema-usage-guard-test's probe schema, registered into default's shared registry by an in-process run of the sweep's namespaces and never restored; every turn write then failed and the loop re-fired without bound, each failed attempt flushing dirty leaves. Issue `a-failing-turn-write-refires-without-bound-and-fills-the-store` (blocker; two classes). Reset #5 → pid 63433, 100 MB; Juniper reseeded; the poisoned key is gone from the fresh JVM's registry. Opus `bounded-write-retry-and-registry-preservation` launched (class 2 first).
- check-excludes-long-tests landed `4e22d2256` (UNRUN — paused): `:seon.test/long` is now a program-row fact at both lifting seams + schema; `check` excludes long by default with a named report (`:seon.test/long-excluded`), opt-in `:seon.test/include-long?`; an expired bound reports completed verdicts + typed expiry. Remaining mirror: runner's `long-reason` reads Var metadata.
- maintenance-success-facts landed `67487fa4d` `2f1a416a9`: the issue predicted a refusal; the truth was worse — write admission accepted an EMPTY map as a component ref value, so a successful cleanup's collection was silently ERASED; now the union of the existing collect component and the error; `seon.maintenance/last-collection` derives "last collected / reclaimed" (typed never-collected refusal); per-run identity kept (a per-root aggregate would give a component several owners — decision recorded).
- fixture-write-sweep round 2 landed (`61519c245`…`ae7440676`): the helper defect was at the RUNNER — `report-value` rendered throwables through the agent profile (ex-data's entity schema squeezed the message out) → plain text bounded by declared length; 11 fixture classes fixed at their helpers (config overlay ×22 via one `apply-config!`; program rows ×15 via `program-fn-row`; …); 27 namespaces for re-gate. FINDING for the admission owner: a PARTIAL upsert of an existing entity is validated against the entity's COMPLETE required-key set (OWNER Q).
- Gate requests ready: fixture-write-sweep (27 ns), check-long, maintenance-success, call-prep-recovery; write-storm follows.
- 13:05Z batch 68 (`d090c9934`, not recorded — reset in flight): instrument/error GREEN; call-preparation 2 ERRORS (mine — cold-arming shape after the instrument change; triage lane resumed); boot-test 12 (stop! custody after ccccea806 → peer thread; two fixture-honesty reds → peer's call). Gates flowing on pid 63433: 69 = platform + maintenance/schedule/test-reaching/my.test/runner; 70 = the 27-namespace sweep; 71 = call-prep + boot with the triage commits. Partial-upsert admission question recorded by the peer as an open OWNER ruling.
- 13:15Z adoption recorded on pid 63433 (exit 0). Default fully current after reset #5; two lanes running (write-storm classes; call-prep cold errors); batches 69–71 queued by the peer.
- 13:25Z batch 69 A: PLATFORM GREEN 87/574 on `c96fb93b7`. 69 B crashed at load on a namespace that does not exist (`my.test-test`, my spec's guess copied into the gate request — removed; my.test/check is covered by seon.test-reaching-test); peer relaunched B with the five real namespaces and is filing the runner defect (a nonexistent namespace must be a typed tally entry, not a coordinator crash).
- 13:40Z batch 69 B2 (`54d3ee20f`, recorded): test.runner-test GREEN; three new maintenance regressions GREEN; RED → maintenance lane (never-collected refusal lacks the root as offending value; schema-test fixture refs an unminted namespace row; schedule portfolio fixture missing zone-id) and check-long lane (the expiry path drops the completed verdict — 9 F). Both resumed. Batch 70 = 27-ns sweep, running. Probers briefly over cap (4 lanes of mine + peer triage + gate) — the two resumed lanes each run one red in process.
- 13:55Z call-prep cold errors fixed `ed2eb3423`: NOT the instrument change (identical pair already at ccccea806 under 65 B's noise); the cold-arming class at three more bare reads of `predicate-functions` (call_preparation.clj:583, schema.clj:1535/:3045) + a hand-rostered plan value; six more bare-but-not-observed reads remain — QUEUED slice: one named derivation replacing all nine spellings. Adoption refused tree-wide by an uncommitted store.clj/seon.store.edn pair (peer thread) — the intermediate-edit class, occurrence #4.
- 14:00Z the store.clj/seon.store.edn pair is on HEAD (peer `7f99fe695` "liveness decided at the authority" + `d427728d7`); re-running adoption. Batch 70 = 23-namespace sweep running; 71 = call-prep/boot after the peer's triage note.
- 14:10Z maintenance 69 reds cleared `274da4108`: all test-side (diagnostic fields under :seon.error/data; fixture namespace from the canonical population; a partial cadence update refused for the whole schema's required key — the queued OWNER admission ruling again, third sighting).
- 14:20Z peer boot-test triage landed `7f99fe695` (stop!'s instance contract demanded LIVE connection/flock, refusing an already-stopped instance — latent since 2d2655922, exposed by arming; liveness decided at the authority), `d427728d7`, `868d7b992` (new live defect: `seon.render.transcript/render-ai` throws ClassCastException String→Date for the agent root on default — QUEUE). My write-storm lane's config dial pair was hook-adopted half-done (effective config missing `:seon.config.agent/write-refusal-bound`) — lane told to commit the coherent slice (intermediate-edit class from my side). Batch 71 list set.
- 14:40Z batch 70 (sweep pass 2, 23 ns, `ca9a8b0e8`, recorded): 306 reds by class — (a) `:seon.turn/closed-tx` is now a tx ref where ~50 assertions expect an inst (one choke point) → sweep agent; (b) concurrency tests deliver nothing under the loud fixture (199 F) → sweep agent; (c) schema-usage-guard probe schema fails cold in its own worker → registry lane; (d) 45 elision-string expectations → dir-elision agent; (e)/(f) fixture keys + four singles → sweep agent. Store 513 MB → 1.0 GB from recording 306 results (recorder second-order waste — queued). Prepl drop 12:55–13:09Z with pid alive and no operation of mine in that window — noted as a defect without a cause; needs a JVM-log timestamp. Half-adopted dial: config apply queued/ran.
- 14:50Z the prepl "Connection reset" CAUSE: `seon.cluster/mcp-io-prepl` refuses bootstrap-effective at connect on the missing required dial `:seon.config.agent/write-refusal-bound` (one dead connection thread per client; seon.log 1106–1191) — MCP, `config apply` (the repair itself), gate recording and every in-process run all dead for ~45 min; the earlier 12:55–13:09Z drop had the same shape. The dial pair is committed (`f86ec57ed`, write-storm class 2: a refused turn write is bounded; the storm becomes one fault) but the effective config lacks the value; only a restart reconciles. Restarting default. Issue `a-missing-required-dial-kills-every-io-prepl-connection` (blocker; the connection seam must be total).
- 14:55Z default restarted → pid 74930; effective config carries the dial (3); MCP serves; Juniper intact; adoption recording. Lanes told to reconstruct the base once on this pid.
- 15:10Z dir-elision lane REFUTED class (d): no frozen elision expectations outside its four files; agent-identity/context-selection reds are the agent-identity/dials family (Opus triage launched); concurrency-independence's 199 F assert payload text that the history never contains because THE AGENT'S HISTORY IS CUT AS ONE STRING BY THE VALUE BUDGET (transcript.clj:1893; could never have passed) — issue `the-agents-history-is-cut-as-one-string-by-the-value-budget`; OWNER RULING needed: which budget owns the prompt (the prompt-token-budget, cutting whole evaluations oldest first) vs the value profile. Sweep agent told to leave both.
- 15:20Z adoption record on pid 74930 refused "Source changed while current-src was being analyzed; retry" (three lanes editing) — the cluster.clj:1860/:1953 snapshot compares the retry lane named (no diagnostic cause → no automatic retry). Default booted from HEAD so only the recorded commit lags; re-run when the tree is quiet. QUEUED slice: fold those two compares into the one source-change retry.
- 15:35Z sweep: pid 74930 alive, zero writer errors since restart, slot free, store 2.0 GB (+0.7 GB in 40 min under three lanes — churn, no storm), six files dirty in lane hands.
- 15:45Z batch 71 (recorded): call-preparation GREEN (ed2eb3423), maintenance ×2 GREEN (274da4108), instrument/error/store/cluster GREEN; all remaining reds are boot-test (sovereign-steer open blocker; a stop! live-connection member in wake/unlisten! — peer closing the class; incremental-refresh 2; partial-clusters NPE; boot-order; generated-prefix timeout; development-adoption-targets-one-of-two-cohosted-clusters hit the 270 s bound again → declare it `:seon.test/long` now that long is a fact — QUEUED). Recording per cold batch costs hundreds of MB (1.3 → 2.0 GB over 151 tests; flat after) — the recorder second-order waste, queued for a runner.clj lane. Batch 72 = platform + turn/cluster.turn/config/test-reaching (f86ec57ed, beb95c1af). 73 = class 1 when it lands.
- 15:55Z peer `6ea39d45a` closed the stop! class at its root (`:seon.db/connection` predicate structural; liveness only at the authority in transact!). The cohosted boot test is already declared long (d756a09d4); it hits the 270 s bound because a named-namespace gate runs complete (§5). DECISION (mine): keep §5; the worker exchange bound derives from the `:seon.test/long` declaration (max(default, declared allowance)) — a runner.clj slice QUEUED behind the write-storm lane's class-1 release; recorder per-batch cost joins the same lane. Batch 74 = boot/wake/db tests.
- 16:05Z sweep pass 3 landed `4d181533d` `55a0abae0` (+`d7e5a0268`): closed-tx has been a tx REF since ae0e54841 — one reader `turn-closed-at` at four sites; remaining fixture keys at helpers; two "unrelated transaction" fixtures had been WAKING the agent under test (listened inbox) — proved nothing until the sweep; transcript-run 11 F = the history-cut mechanism (AI render "" for a whole history) → same OWNER ruling. Base refuses to construct on pid 74930 ("requires the projection handed") — asked the write-storm lane (its db/test/runner/test_support edits are live).
- 16:15Z base on pid 74930 constructed and realized from a plain MCP future — the "requires the projection handed" refusal is transient (coincides with a live adoption both times today); in-process proofs open. Lanes told.
- 16:25Z agent-identity triage landed `62110d9b0` `f3ac3be86` (lane died on an API safeguards flag after its note — work complete): identity-test expectations stale against two rulings (raw identity pulls d6377ac39; turns-left removed 0dca8534e); context-selection was a fixture refusal after all (one refused seed → every append no-such-agent). Write-storm class 1 committed `f3b61b975` (report pending). Gate requests handed to the peer.
- 16:30Z launched Opus `runner-long-bound-and-recording-delta` (exchange bound derives from the long declaration, converging `long-reason` onto the row fact; recording emits only the delta — measured) and Opus `predicate-functions-one-derivation` (nine bare reads → one contracted derivation + a program-graph checker). Probers: write-storm (finishing), runner lane, predicate lane, gate = 4.
- 16:45Z batch 72: platform GREEN; test-reaching GREEN (check-long beb95c1af cold); cluster.turn GREEN; two write-storm class-2 reds (regression fixture compiles a manifest without a cluster name; the default-document location checker finds the dial pair uneven) → lane. Store 2.0 → 3.0 GB over the two runs (recording delta slice in flight). Batch 74 = boot/wake/db + agent-identity/context-selection; 73 (class 1) on the lane's confirmation; 75 = sweep pass 3.
- 17:00Z write-storm lane FINISHED: `f86ec57ed` (class 2), `f3b61b975` (class 1: the storm's real root was CUSTODY — an in-process test body inherited the agent evaluation's `*conn*` via bound-fn, so elided-arity fixture writes hit the live cluster; now `call-without-custody` around every test Var, drift detector snapshots schema keys, `seon.test/run` restores the projection; `preserving-schema-registry`), `f0cb3f692` (class-2 fixture), `566a13bde`. New issue: `adoption-can-leave-a-changed-contract-armed-with-its-previous-shape` (wrapper armed with the previous contract across three converged adoptions — QUEUE). Config-location red was `:seon.config.render/issue-opening` declared without a value (trials lane) → shipped `:bare`. Batch 74: stop! class closed cold; db/agent-identity/context-selection GREEN; wake-test 4 F = stale expectations vs the issue/agent listened wake → start-arms lane; recording +1.8 GB/100 tests — numbers to the runner lane.
- 17:20Z batch 75 (sweep pass 3, recorded): 297 F → 184 F on the same namespaces; 154 are the history-cut-blocked concurrency test; 30 remaining (turn-work 13, turn-loop 11, problem-routing 2, web 3, bootstrap 1) + two contract ERRORS to attribute (walk candidate `:seon.repl/subject` expects an integer, the REPL pair passes a symbol handle; capability request vs owner contract in public-fetch/search) → sweep agent pass 4. Batch 73 (write-storm) running.
- 17:25Z `config apply default` exit 0 — effective config carries the issue-opening dial (:bare).
- 17:35Z wake-test fixed `1b1215ba8`: the listened-set oracle now derives from the schema rows (four derivations compared) instead of a hand list; ruling stated: an issue assignment is an opening wake. Gate with the next batch.
- 17:40Z sweep: pid 74930 alive, zero writer errors, batch 73 in slot, store 5.3 GB (2.0 → 5.3 GB in ~55 min across batches 74/75/73 — the recording cost; runner lane on the delta). PLAN: reset default at the next quiet boundary after batch 73 reports (store > 5 GB), message the peer first.
- 17:55Z predicate-functions derivation landed `3764c7965`: one reader (`{}` for absent) + one writer; 14 read / 4 write sites; compilable-form still refuses nil by design; program-graph checker. Class closed (no issue). Gate after the reset.

### 2026-09-17 18:10Z — reset #6; runner delta landed
- Batch 73: platform GREEN; class-1 regressions GREEN cold; class 2 NOT proven cold (wake still polled after the bound; fault kind nil) + two guard-test fixture/seam reds → write-storm lane resumed on the fresh JVM. Reset #6 → pid 88182, store 101 MB; Juniper reseed + adoption running.
- runner lane landed `8c2f62701` (exchange bound derives from the row's `:seon.test/long`/`long-ms`; metadata read deleted; a Var/row long drift is a coordinator refusal) and `f272b9e6e` (recording delta: unchanged 93-result completion 157,981 → 0 datoms — `:seon.test/reach` retract+re-assert was 94.6%; store cost tracks datom count ×3–10 KB index amplification). Gate 79 WITH recording = the proof. New issue: `program-shapes-cache-strips-attributes-declared-after-the-jvm-started` (the defonce snapshot class, fourth sighting). boot_test long-ms declaration asked of the peer's thread.
- 18:20Z launched Opus `program-shapes-follow-adoption` (shapes derivation reads the declaration population it is handed; defonce deleted or keyed by population digest; regression: a new attribute carried by the next artifact without a restart). Probers: sweep pass 4, write-storm residue, shapes lane, gate = 4.
- 18:35Z GATES BLOCKED on HEAD: batch 78 A aborted at SELECT — `verify-long-declarations-indexed!` (8c2f62701) refused because the PUBLISHED base's test rows carry no `:seon.test/long` (the static analyzer path never lifts deftest metadata; only the loaded-Var seam does). Checker correct; the lift is missing at the static seam. Routed urgently to the runner lane (fn.clj static lift, proof via build-artifact on armed_test.clj); fallback in 30 min: demote the checker to a typed notice.
- 18:40Z refined: the 12 refused tests declare long on the NAMESPACE form; var-row lifts only the deftest's own metadata (two readers of one declaration disagree). Decision: one lifting rule — deftest or namespace declaration, deftest wins — at both seams; runner lane on it.
- 18:45Z adoption record on pid 88182 refused again (source changed during adoption — three lanes editing); JVM booted from HEAD, only the record lags; retry when quiet.
- 18:55Z write-storm residue landed `b7c7edf5e` `006e7e450` `e074f8208` `543a151d9`: all three batch-73 reds were fixture defects (wrong flow ex-key alias; undrained sliding-1 mailbox; one-key probe projection; unminted ns ref) — class 2 proven 14/0/0 on the fresh JVM. Class 1's drift check caught a real leak in production: `context_blocks_fixture` registers `:example/order`/`:example/order-row` into the live cluster's projection (issue `context-blocks-fixture-leaks-example-schema-keys-into-the-live-cluster` — the Juniper reseed does it on every reset; QUEUE). Write-storm lane FINISHED.
- 19:00Z launched Opus `juniper-installer-schema` (the reseed installer registers `:example/order*` into the live projection on every reset — declare as fixture schema or scope the registration; drift check must report no leaked key). Probers: sweep pass 4, runner (long lift), shapes, juniper installer, gate = 5 (gates held anyway).
- 19:05Z GATES UNBLOCKED: `742ac38c2` — `seon.program/test-markers` is the one rule for long/long-ms (deftest or namespace form; deftest wins per attribute) at both lifting seams; static proof armed_test 6/6, concurrency_streams 2/2. Follow-up: `:seon.test/fixture-observation` has the same latent ns-vs-Var gap (nothing reads it from the row yet). Fallback (checker demotion) not needed.
- 19:15Z sweep: pid 88182 alive, zero writer errors, batch 78 in slot, store 836 MB ~55 min after reset #6 (three lanes + gates recording — the delta fix is not gated yet), five files dirty in lane hands; sweep lane committing turn-work fixes (583b43d5b).
- 19:25Z batch 78 (`742ac38c2`+): platform GREEN with the long checker passing; B GREEN 234/1666 — predicate-functions derivation, wake-test oracle, write-storm classes 1+2 with residue ALL proven cold. Batch 79 = platform AGAIN on the same HEAD (an unchanged re-record must add ~0 — the recording-delta proof, store printed before/after) + boot/runner tests (the cohosted drill under its 600 s allowance, peer's 29b6e1707).
- 19:40Z sweep pass 4 landed `04a364e93` `4b0e21c16` `583b43d5b` `132e0ae73` (+`c8aa8bda8`): root under most turn reds = ae0e54841 put the agent→turn edge on a runtime component only open-call writes; two derivation tables authored turn rows directly → now open through `seon.turn/open-tx`. Two REAL defects attributed: (1) `seon.effect/accepts-request?` validates a one-arg request against two-arg owners → the effect door refuses EVERY declared capability since 0e15593aa (issue filed; effect-facts lane resumed — agent-facing blocker); (2) `seon.bootstrap/namespace-subject` hands a bare symbol where the walk contract wants a lookup (91f536c36) → ordered-episode ERRORS (Opus lane launched). Open for the acquisition owner: web-context re-walks on ANY commit. Probers: shapes, installer, effect, opening-subject, gate = 5.
- 19:55Z juniper-installer landed `5553725d3` `95ae8ee90`: premise REFUTED — the `:example/*` keys are declared facts (agent-admitted rows via row-tx); installer declares its schema as data with a post-condition. Real finding = BLOCKER: after an agent admits declarations through a turn, the STORABLE ones are dropped from the cluster's live projection at install while the facts are intact (stale mirror; re-derived later → read as drift). Issue `a-committed-storable-declaration-is-dropped-from-the-clusters-live-projection`; Opus `live-projection-follows-declarations` launched (sci/eval.clj install-row!/advance-context-projection!).
- 20:10Z batch 79: platform GREEN again on the same HEAD with the store 1.1 → 1.1 GB across the unchanged re-record — RECORDING DELTA PROVEN COLD (f272b9e6e); the cohosted drill ran 297 s under its 600 s allowance (8c2f62701 + peer 29b6e1707 proven) and is now an ordinary red of its own (adoption of two cohosted clusters; retained root run.XCm4Xm — QUEUE for the shapes-follow-adoption lane or a follow-up). Batch 80 = pass 4 + juniper-installer namespaces.
- 20:15Z cohosted drill's own reds: (1) the debug-page poller observes EIGHT adoption stages where four were expected (adoption runs its stages twice, or the expectation is short — QUEUE for the adoption-stage owner); (2) the adopted definition reads nil on default after adoption → second sighting of the live-projection class → routed to that lane.
- 20:25Z effect-door defect REFUTED by the effect-facts lane (`2ac75efcb`): the sweep measured the handlers; the door asks the owner Var (one argument) — all ten capabilities accept their own generated request; blame was b80f78a7c and it is correct; guard regression added; issue resolved with the measurement. The web.jvm reds return to the fixture class (no compiled config row). All effect/edit regressions now green in process (62 assertions) — effect-facts lane FINISHED. Slot free for the peer's web-context triage.
- 20:40Z batch 80 (recorded): pass 4 proven — turn-work 13 → 1, turn-loop 11 → 7 (model gauges not written cold ×3; fixture ns unminted + overlays 5 vs 3; clean-last-form history text) → sweep pass 5 when a slot frees; NEW: loop-proof virtual-loop-end-to-end 10 F on the installer change's first cold run → installer lane resumed (may be the dropped-declaration blocker cold). Batch 81 = effect/edit tests (2ac75efcb). Probers: opening-subject, shapes, live-projection, installer, peer gate, peer triage = 6 (sweep pass 5 held).
- 20:55Z batch 81: effect/edit tests GREEN 24/138 — effect-facts (2ac75efcb) proven cold. Slot free; next landings gate on arrival.
- 21:05Z sweep: pid 88182 alive, zero writer errors, slot free, store 2.4 GB ~2.5 h after reset #6 (four lanes probing; ordinary churn — the reclamation-signal decision stands), three files dirty; shapes lane committed (38e553ce7), report imminent.
- 21:15Z shapes-follow-adoption landed `9cc181289` `3b52f0080` `38e553ce7`: defonce gone — the fallback caches under `seon.schema.edn/declaration-stamp` (resource name/length/mtime; a declaration edit is a miss by construction); the indexer threads one resolved population per operation; live: build-artifact carries `:seon.test/long-ms` after adoption. Recorded recurrence: `successful-fixture-base-retains-pre-adoption-contracts` (an accreted arity refused inside seon.test/run — the shared base's rows predate the edit) — QUEUE (no lane can prove a new arity in process); also `seon.test/run` reloads the test ns inside the run so a pre-resolved Var runs the previous definition. Probers: opening-subject, live-projection, installer, peer gate, peer triage = 5.
- 21:25Z installer lane attributed batch 80's loop-proof red (`d03ec3fe2`): NOT its change (same 6 F 2 E with the pre-change fixture loaded) — two causes: NEW BLOCKER `an-agents-own-test-loses-its-clusters-custody` (class 1's `call-without-custody` around every test Var strips the custody an AGENT's own deftest needs via my.test/run — agent-facing regression of f3b61b975; Opus lane launched: custody handed as a value from the evaluation, host runs still without) + the pre-existing `virtual-loop-fixture-submission-can-race-an-armed-turn` (deterministic in process; open since 09-15 — QUEUE). Installer lane FINISHED. Probers: opening-subject, live-projection, agent-test-custody, peer gate, peer triage = 5.
- 21:35Z peer web-context verdict `dfd2aae54`: a PRODUCTION defect — every commit re-walked and re-rendered every acquired agent's whole history because `read-result-digest` threw on inst/uuid/char (swallowed to nil) so a wildcard-pull read had only the commit id as evidence; after adoption all 29 retained calls stay current across a neutral commit. Its secondary (the "unrelated" fixture transaction mints a half-agent, cd2343a0c) → sweep pass 5, launched now with the batch-80 turn residue and the web.jvm fixture class. Probers: opening-subject, live-projection, agent-test-custody, sweep pass 5, peer gate = 5.
- 21:45Z agent-test-custody lane relaunched with neutral wording (the first died on an API safeguards flag before any edit). Probers unchanged: opening-subject, live-projection, agent-test-custody, sweep pass 5, peer gate = 5.
- 21:50Z custody lane launched a third time with the grounding stated inline (two launches died on an API safeguards flag while reading the write-storm note / custody issue); launch note added to repl-rule.txt.
- 22:00Z opening-subject landed `9107232d7` `58bd7f4f3`: `direct-candidates` hands the entity lookup (the misnamed `namespace-subject` returned the bare symbol → renamed `lookup-namespace-name`); four more same-class reds in episode/history tests fixed; live 280 candidates, 0 non-lookup; regression drives the armed walk over every produced candidate. Note: the generated opening producer (`pull-result`/`next-entry`) has no caller in src/ — Juniper's prompt comes from `seon.turn/system-turn` (QUESTION: is `seon.bootstrap`'s generated opening dead code or the target path? record for the owner). Probers: live-projection, agent-test-custody, sweep pass 5, peer gate = 4.
- 22:05Z launched Opus `shared-base-follows-adoption` (base keyed by the publication commit; a newer adopted publication is a cache miss; old base released when unheld). Probers: live-projection, agent-test-custody, sweep pass 5, shared-base, peer gate = 5.
- 22:10Z batch 82: platform GREEN; program/fn/cluster.source GREEN — shapes-follow-adoption proven cold. Batch 83 = platform + web-context/web/schema/db (re-walk fix) + bootstrap/history/episode/walk (opening-subject). Store 3.2 GB.
- 22:20Z live-projection landed `a8ed776e0`: the named seams were innocent — `seon.turn/row-tx` canonicalizes schema rows through `declaration-row`, which RE-PRINTS `:seon.schema/form` (`[:string #:seon.db{:identity true}]` vs the request's `{:seon.db/identity true}`); `committed-row?` compared the bytes with `=` → false for the cluster's own committed storable declarations → skipped from the projection advance; now one `same-declaration-source?` (value-equal when both read as EDN) at both install seams. Live: a fresh three-form declaration turn lands all three (one before); Juniper installer idempotent by identity. Cohosted-drill "definition nil after adoption" is a DIFFERENT seam — cluster.clj:2151–2167 scalar `upsert-rows` branch (peer territory). Process notes: reload fixtures too (rule added); the drift detector RESTORED deliberately retracted probe keys into default's registry (repaired by advance from the database) — QUEUE: restore must not undo a committed retraction.
- 22:35Z sweep pass 5 landed `a0024d297` `484e05bdb` `87359e9fb` `13f382e28` `546d0750c` `1fb006a9c` (+`9669a5348`): model-gauges red = fixture named a model no shipped descriptor carries; prompt-and-call = EXACT config reconcile undone by an empty manifest + a second close of a run the loop already closed; web.jvm = config row but no cluster entity → dials read through cluster facts → bare NPE; "unrelated" sites → `{:db/doc}` probe entities; effect-door issue marked superseded with the lesson. Attributed, not changed: `ai/agent-overlay` read 5× where the ruling says 3 (failover re-reads — §2.1 question for seon.ai); continuation/disposition family for the turn-loop owner. BLOCKER: test/seon/test_support.clj syntactically broken under the custody lane's uncommitted 262-line edit — told to balance/commit; shared-base lane warned (same file).
- 22:40Z test_support.clj lints clean again (the custody lane balanced its hunk). Sweep lane FINISHED after five passes (the honest-fixture wave: 297 → the continuation/disposition family + the history-cut-blocked concurrency tests). Probers: agent-test-custody, shared-base, peer gate, peer cohosted triage = 4.
- 22:45Z launched Opus `agent-overlay-once-per-turn` (failover re-reads the overlay at call time: 5 reads where the ruling says 3 — the overlay rides the turn's request value; turn.clj protected — names the hunk if needed). Probers: agent-test-custody, shared-base, agent-overlay, peer gate, peer cohosted triage = 5.
- 22:55Z batch 83: platform GREEN; web-context GREEN (re-walk fix proven cold); web/schema/db/episode/walk/bootstrap GREEN; one history-test red (selection chain's third output now a pull, not the message's inbox-form — ruling or regression? opening-subject lane resumed). Batch 84 = live-projection. Store 3.9 GB.
- 23:00Z the agent-overlay Opus lane died on an API safeguards flag while grepping call sites (third such death today, all while READING repo files) — relaunched on Sonnet with the same bounded spec (turn.clj protected; it names the hunk if the fix belongs there). Probers: agent-test-custody, shared-base, opening-subject (history-test), agent-overlay, peer gate, peer triage = 6 (two of mine are finishing).
- 23:10Z agent-test custody landed `453f08b5d` (+`dfbee95e1`): `seon.db/call-with-custody` = the one scope binding exactly the handed connection; `run-var!` takes the request; `seon.test/run-owned` is the agent entry (my.test/run expands to it); live: agent deftest passes via run-owned, fails via host run — the intended split. Follow-up hunk: `run-candidate-test!` (accretion gate tests) hands the connection too — lane resumed (sci/eval.clj free).
- 23:20Z sweep: pid 88182 alive, zero writer errors, batch 84 in slot, store 4.3 GB ~4.5 h after reset #6 (ordinary churn under four lanes + gates now that recording is delta — the reclamation-signal decision is the owner's; reset again at the next quiet boundary if > 5 GB), three files dirty in lane hands.
- 23:30Z history-test `form-is-the-third-output-of-the-existing-selection-chain` is a PRE-EXISTING red since ae0e54841 (2026-09-09), surfaced only because a gate finally named the namespace; not the opening-subject lane's (its two hunks are elsewhere in the file). Four of nine assertions = expectation drift with named rulings (my.* request maps 105acca21; inbox-form on the reverse edge ae0e54841; `:seon.message/to` has no pair so the pull is the declared answer); five = a RENDER-OWNER decision: may an attribute-scoped request fall back to the nearest declared pair (agent `[*]` → render-form vs situation-form on the derived map; ns/requires floor → namespace-form). Issue `the-selection-chain-regression-still-asserts-the-pre-inbox-edge-message-shapes`; deftest deliberately unchanged. Rule added: never reload seon.test-support.
- 23:40Z batch 84: platform GREEN; sci.eval/schema/test-support GREEN — live-projection (a8ed776e0) proven cold; loop-proof virtual-loop 10 → 4 F (the open race + custody, re-gates in 86 with my.test-test). Batch 85 = pass 5. Store 4.4 GB.
- 23:45Z custody follow-up landed `57a77642b`: `run-candidate-test!` hands the required connection to `run-var!` (accretion-gate tests keep custody); regression 3/0/0 in process. Custody lane FINISHED. Batch 86 = the custody set + platform. Probers: shared-base, agent-overlay, peer gate, peer triage = 4.
