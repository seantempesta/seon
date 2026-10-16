---
title: "Dig: the recording path from a REPL form to program entities, and the 2026-10-15/16 defects on it"
type: research
status: evidence only (Haiku dig); no decisions, no code edits
date: 2026-10-16
basis: "HEAD e559595ff (working tree has other lanes' uncommitted edits in src/seon/sci/eval.clj and others; read by symbol at HEAD)"
---

# Dig: program-entity recording path

Evidence for a later design round in which program entities derive from one place. Symbols are fully qualified; file
quotes are at most two lines. Line counts are from `wc`/`awk` on HEAD.

## 1. Data flow: form text to stored program entities

Entry: an eval request. `seon.turn.batch/turn-request`, `code-request`, `jvm-request`, `test-request` all answer through
`seon.batch/evaluate-batch` (`src/seon/turn/batch.clj` ns docstring: "Entrants ... differ only in these values").

Stages in order, each a function of data:

1. **Read and evaluate once.** SCI evaluates the form in the batch's `seon.sci.eval/ctx`. Each definition write is recorded
   as a `:seon.batch/write` (`:op :bind | :unmap | :remove-ns | :namespace`, `:kind :root`). Facts come from what the
   evaluation observed (SCI's own Var/namespace state), not from re-reading text.
2. **Analyze the same text.** `seon.turn.batch/analyzed-entities` runs clj-kondo analysis of the form source
   (`seon.fn/analysis-entities-by-file`, `seon.fn/program-targets`, `seon.fn/population-targets`), and
   `seon.turn.batch/join-write` joins each final root write to exactly one analyzed definition by its source span.
   Entity identity key is `[request-index utf8-offset name]`.
3. **Derive the change.** `seon.turn.batch/derive-form` builds `:seon.batch/change` (`entities`, `removals`,
   `schema-keys`, `namespaces`, `private`, `notes`). `seon.turn.batch/write-entity` builds each row through
   `seon.program/declaration-row` and `seon.program/canonical-row`, then `seon.program/definition-digest`.
   `seon.turn.batch/analysis` uses the namespace row from `seon.turn.batch/stored-namespace-row`, which reads SCI's live
   resolver bindings (`seon.turn.batch/live-bindings` -> `seon.sci.eval/binding-rows`, `seon.sci.eval/reader-context`).
4. **Fold.** `seon.turn.batch/fold-change` folds applied changes by identity into the batch's `:seon.batch/program`.
5. **Facts on a value.** `seon.turn.batch/program-facts` (changed-since-basis refusal, then `form-facts`):
   - `form-facts` runs `schema-tx`, then `removal-facts` (judged as one set, see 10f6a4d5a), then `cycle-closing`
     (refuses a require that closes a namespace cycle), then per namespace `namespace-facts`, then per entity
     `entity-facts`.
   - `namespace-facts` declares only the live bindings the stored row or the change's definitions use:
     `seon.turn.batch/declared-bindings` calls `seon.program/used-resolver-context` (see 6cda06602, 3da13c834).
   - `entity-facts` returns `[]` when the stored definition digest equals the row's; otherwise an exact replacement
     via `seon.program/exact-replacement-tx`, keeping `seon.turn.batch/file-attributes` (`:seon.fn/file`,
     `:seon.fn/form-span`) from the stored row (504ff11d0, 7f2558dbe).
6. **Writer.** `seon.turn.batch/apply-facts` runs `db/with` (the writer's own validation). `seon.turn.batch/settle`
   commits the run's evaluations and program facts in one transaction (`record-facts`, `program-facts`); on a writer
   refusal it re-derives on the head (`rebase` -> `revalidate`) and drops only the forms now refused.
   `program-facts-call` (the `:db.fn/call` used by settlement) throws when the facts are a refusal.
7. **Install into SCI after settlement.** `seon.sci.eval/install-evaluated-rows!` (line ~1864) and
   `seon.sci.eval/record-settled-rows!` put committed rows into the evaluating context. `seon.sci.eval/install-row!`
   (~1507) resolves the committed row by identity. Def values are kept per compartment (4f15bf3d7):
   `seon.sci.eval/keep-evaluated-values!` -> `keep-value!` under `[compartment(database) definition-digest]`, read by
   `seon.sci.eval/kept-value` when a later program context installs the row.
8. **Program contexts (derived, cached by content).** `seon.sci.eval/program-context` ->
   `derive-program-context` -> `derive-base-ctx` (loaded commit) or `derive-overlay-ctx` over
   `seon.sci.eval/derive-overlay-plan` (I(B,L): digests differing, added, retracted, namespace rows) ->
   `interpret-overlay!` -> `install-selected!` -> `install-row!` / `install-function-from-database!`. Keys:
   `program-content-key`, `seon.cache` entries `::program-context`, `::program-key`, `::kept-value`.
   `seon.sci.eval/fork-cluster-ctx` (3-arity) and `receiving-state` build a fork's environment (2af8586ec).
9. **Write-back to files.** `seon.cluster.source` file digests: `file-digests` (~1731) reads the stored
   `:seon.fn/file` links (`[?e :seon.fn/file ?file]`) and `declaration-digests`; the merge check
   (~1937) compares `(file-digests after path)` with the reindexed candidate bytes; `committed-bytes` (~1811) reads
   the file from git HEAD. A file-linked row that lost its `:seon.fn/file` drops out of `file-digests`, which is
   the failure 7f2558dbe fixed.

Derived from **SCI runtime/resolver state**: the namespace bindings (`live-bindings`, `binding-rows`), Var roots and
metadata (`write-entity` under `sci.ctx-store/with-ctx`), arity and `def` evaluation, and the printed value.
Derived from **form text** (clj-kondo analysis of the same source): calls, references, keywords, source-span joins,
`seon.program/source-resolver-names` (an edamame/parser read of the source string), docstring stripping.
Derived from **files**: `seon.fn/file` and `seon.fn/form-span` on stored rows; `committed-bytes` at git HEAD for
write-back; the loaded commit's namespace digests (`seon.sci.eval/derive-overlay-plan` reads `namespace-digests` of the database and the loaded commit). Default's program is derived from files
by the boot path (not traced here; CLAUDE.md "Default's program entities and loaded namespaces derive from files").

Two facts a form cannot know, both carried from the stored row: the file link and the form span (`file-attributes`).
Not verified in this dig: how `seon.sci.eval/install-selected!` chooses between the loaded Var root and an interpreted
definition for each row; the boot path that derives default's program from files was only named, not traced.

## 2. Law and vocabulary (CLAUDE.md, docs/seon/architecture/vocabulary.md)

- CLAUDE.md "The program is entities": "functions, tests, schemas, namespaces, render pairs, contracts, analysis and test
  evidence keyed by exercised definition digests ... Default's program entities and loaded namespaces derive from files.
  A JVM has one set of compiled Vars; other branches interpret differing entities and their affected callers in SCI."
- CLAUDE.md "One REPL path": "read -> evaluate -> record -> print ... Forms are read once, SCI evaluates them, the facts
  come from what the evaluation observed (with clj-kondo's analysis of the same text joined by exact source span), and
  one transaction records it all." The pipeline in section 1 implements that sentence.
- CLAUDE.md "A form is parsed once and evaluated once; its facts and printed value are stored and queryable, while its
  result value lives in the runtime."
- CLAUDE.md "Recover facts, not execution" and "Derive work and completion": settlement is one synchronous transaction.
- CLAUDE.md "Write-back uses the same checks, checks file bases, stages exact-span changes and reindexes to prove
  equivalent declarations before installation."
- CLAUDE.md "Fix at the producer" and "NO GUARDS OR FENCES": the commits below are mostly fixes at the producer, not
  guards; several retired filed tasks (section 5).
- CLAUDE.md "Prefer values to places": "Mutable state is at most one validated state map per process ... `seon.cache`".
  The compartment-keyed kept values are in `seon.cache` (`::kept-value`).
- Vocabulary (docs/seon/architecture/vocabulary.md): "write-back", "task", "proof", "turn", "render function", "wake".
  Program words from the table in CLAUDE.md: Datahike "entity, attribute, value, datom, transaction"; "row" is retired
  for facts (filed as seon.program row-vocabulary-sweep, section 5).

## 3. Prior design docs and attempts

- `docs/research/agent-platform/one-repl-path-spec-2026-09-27.md`: the one REPL path specification (source of the
  pipeline shape in CLAUDE.md). Review: `review-one-repl-path-spec-2026-09-27.md`.
- `docs/research/agent-platform/program-rows-data-pack-2026-09-22.md`: earlier data pack on program rows (the "row"
  wording now retired).
- `docs/research/agent-platform/write-back-and-custody-2026-09-24.md`, `write-back-data-pack-2026-09-23.md`,
  `merge-and-write-back-data-pack-2026-09-22.md`: write-back and custody; the file link as stored owned data.
- `docs/research/agent-platform/2026-10-11/runtime-follows-db.md`: "The runtime follows the database": owner-approved
  2026-10-11; one path; every state as primitives. Sets the rule that SCI state is derived from the database.
- `docs/research/agent-platform/2026-10-12/program-context-notes.md`: where the E3/E4 hybrid text contradicts the code
  (program-key comparison, `fork-for-turn`, `install-evaluated-rows!`).
- `docs/research/agent-platform/2026-10-14/overlay-opus.md` (one of two independent plans): incremental overlay
  acquisition, fork the previous program context and interpret only changed digests plus their reverse closure.
  Measured: the loaded base is re-interpreted whole for a changed plan.
- `docs/research/agent-platform/2026-10-12/dig-immutable-env.md`, `hybrid-immutable-env.md` (cited by the notes above).
- `docs/research/agent-platform/2026-10-16/printer-design/` and `printer-audit/`: printer work of the same day, not
  program recording.

Why earlier approaches changed (from commit messages):
- The file link was carried by a hand list in `entity-facts` (`(dissoc entity :seon.fn/file :seon.fn/form-span)` in
  `analyzed-entities`), then a single `:seon.fn/file` carry (pre-504ff11d0), then the one `file-attributes` rule.
- `used-resolver-context` kept every require SCI reported; 6cda06602 excluded a namespace's own require ("Clojure
  accepts a self-require ... a namespace's own names resolve through its interns").
- Kept def values were keyed by definition digest only (10f6a4d5a), then by compartment (4f15bf3d7) after two agents
  shared one atom. This is the producer of the "identical value in two branches" failure.
- `fork-cluster-ctx` took the environment from the base context, which is cached by content and carries another
  execution's registry (2af8586ec); the first attempt (leave the connection state untouched when it carries one)
  broke 20 `seon.cluster.db-flow-test` chain worlds, per that commit message.

## 4. Library seams (reference-code)

- clj-kondo analysis of the same text: `reference-code/clj-kondo/src/clj_kondo/core.clj` (`run!`, line 67). Seon uses
  it through `seon.fn.analyzer`; the join by source span is Seon's own (`join-write`).
- clj-reload (namespace model from files): `reference-code/clj-reload/src/clj_reload/parse.clj` has `parse-ns-form`
  (line 52), `parse-require-form` (16), `read-file` (69), `topo-sort` (163), `transitive-closure` (133). Its
  `keep.clj` patches a file's forms by identity (`keep-patch` 126, `patch-file` 135), a write-back model.
- SCI's own namespace model: `reference-code/sci/src/sci/impl/namespaces.cljc` (`sci-create-ns` at line 442). SCI
  owns resolver state per namespace; Seon reads it through `seon.sci.eval/binding-rows`.
- rewrite-clj and edamame (reading and editing text without string parsing): `reference-code/rewrite-clj/src/rewrite_clj/`
  (`zip.cljc`, `node.cljc`), `reference-code/edamame`. `seon.program/source-resolver-names` uses edamame's
  `parser/parse-string-all` and a `tree-seq` over nodes; that is the one text read on the definition-digest path.
- Datahike owns the transaction and history; Seon's program writes go through `seon.db/with` and `db/with-branch`.

No library in reference-code offers a per-Var "what the file declares" model that also tracks REPL-evaluated changes;
clj-reload's model is file-to-namespace, the reverse direction from Seon's need.

## 5. Open tasks touching this path (resources/seon/tasks/*.edn)

Names as declared (`:seon.task/name`); file in brackets.
- `seon.program`: `program-attributes-trust-any-partition`; `a-defn-scans-every-projection-form-per-call`;
  `row-vocabulary-sweep`; `values-printed-to-order-or-compare`; `cluster-attributes-are-the-declared-cluster-entities`;
  `an-evaluated-namespace-keeps-its-read-source-and-call-edges` (red on HEAD 606110768: the evaluated ns entity's
  `:seon.ns/source` formats differently, so another definition digest); `write-back-keeps-the-evaluated-order-of-ns-requires`;
  `a-schema-key-source-names-every-taker-the-program-holds` (red on c63a8eb22); `sci-scratch-definitions-refuse-their-produced-digest`.
- `seon.turn.batch`: `a-stored-namespace-replacement-keeps-removed-components`; `analysis-uses-the-namespace-row-after-the-form`;
  `an-unjoined-definition-write-is-silent`; `a-core-unmap-with-a-surviving-caller-is-silently-undone`;
  `a-repl-namespace-row-keeps-neither-doc-nor-source`; `redefining-a-file-function-in-seon-error-is-refused-by-its-digest`;
  `a-refused-remove-ns-still-removes-the-namespace-from-the-context`; `a-new-agents-first-request-costs-twenty-seconds`.
- `seon.sci.eval`: `repl-namespace-changes-drift-from-the-ns-text` (alias, refer and import at the REPL change the entity
  but keep the stored ns text; a REPL ns form drops `:seon.fn/file`); `acquisition-rebuilds-the-whole-program` (base-ctx
  miss 2,925 ms; first agent evaluation on a new branch 11.2 GB, 3.6 s); `incremental-install-leaves-callers-on-the-jvm-definition`;
  `program-movement-is-checked-three-ways-per-form`; `a-refused-run-leaves-its-program-in-the-context`;
  `non-defn-definitions-store-no-entity`; `a-value-definition-in-a-changes-closure-is-bound-to-a-lazy-function`;
  `write-back-renders-a-namespaces-fixtures`; `doc-and-dir-erase-request-keys`; `copied-vars-pin-old-wrapper-generations`;
  `forms-just-after-boot-cost-hundreds-of-ms`; `analysis-order-differs-from-evaluation-order`.
- `seon.repl`: `the-first-requests-on-a-new-agent-branch-are-sub-second` (1.2 to 1.4 s and 1.6 to 1.7 GB for an `ns`, a
  `def` and one query; 24 MB per trivial defn; reads `seon.turn.batch/program-facts-call`); `a-quoted-symbol-auto-requires-nothing`.
- `seon.cluster.source`: `write-back-of-a-new-require-analyses-the-published-snapshot`; `held-digests-refuses-a-map-on-the-write-back-path`;
  `a-merge-compares-only-against-committed-files-and-never-writes-a-runtime-task-into-one`;
  `a-docstring-only-change-lands-without-interpreting-its-namespace`.
- `seon.fn`: `an-edit-rewrites-every-definition-below-it`; `a-renamed-refer-drops-its-call-edge`;
  `a-dependent-keeps-no-edge-to-a-callee-defined-after-it`.
- `my.program`: `a-one-line-change-resends-the-whole-definition`; `a-repl-alias-stays-on-the-namespace-row-after-ns-unalias`;
  `alias-and-require-as-retarget-silently`; `remove-ns-allocates-two-gigabytes`; `a-test-var-run-at-the-repl-writes-its-fixture-into-my-branch`.
- `seon.turn`: `an-agent-form-is-read-again-by-each-consumer`; `a-definition-is-settled-twice`; `row-tx-is-a-second-declaration-writer`;
  `a-refused-multi-form-settlement-returns-nothing`; `a-turn-pass-derives-its-work-twice`.
- `seon.cluster.agent`: `agent-creation-writes-no-program-namespace`; `a-fresh-agents-environment-costs-one-fork`.
- `seon.sci.arm`: `changed-namespaces-flags-a-namespace-sci-merely-touched`; `a-fresh-acquisition-restores-the-persisted-imports`.

Retired by this week's commits (no longer open): `seon.turn.batch/an-identical-redefinition-keeps-its-file-link` (7f2558dbe),
`seon.sci.eval/an-overlay-install-reevaluates-an-agents-def-init` (10f6a4d5a), `seon.sci.eval/a-fork-onto-a-held-connection-replaces-its-holders-environment` (2af8586ec).

## 6. Counts (HEAD e559595ff)

- `src/seon/turn/batch.clj`: 1,249 lines; 66 `def`/`defn` forms; the program-facts path (lines 55-1000: analysis,
  derive, facts, settle) holds 52 of them.
- `src/seon/program.cljc`: 1,725 lines; 63 defs; 17 on the record path (declaration rows, digests, replacement tx).
- `src/seon/sci/eval.clj`: 5,380 lines; 218 defs; 141 between lines 940 and 4140 (install, overlay, program context,
  keys, kept values, fork); 17 in the overlay plan/interpret region (3027-3450).
- `src/seon/cluster/source.clj`: 2,631 lines; 16 defs in the write-back region (1700-2000).
- `src/seon/fn.clj` 4,281 lines and `src/seon/fn/analyzer.clj` 1,005 lines (analysis entities, population targets).
- Rough total of the recording path: about 5,000 lines across these five files, counting whole ranges (an estimate, not a
  measured figure: the ranges include code that is not recording).
- Commits on the path 2026-10-15/16 (this dig's list): 6cda06602, 3da13c834, 7f2558dbe, 504ff11d0, 10f6a4d5a, 4f15bf3d7,
  2af8586ec, plus 3a31c93f8 and ae7130094 (chain-world fixture and install-of-def-init follow-ups) and 4a8a83c11 (the filing
  commit for the first-request task). Seven commits fixed a defect on this path in about 36 hours (the first seven listed
  in the request); the other three are follow-up or filing commits.

## 7. The five facts most relevant to a redesign where program entities derive from one place

1. **The form's facts are computed in three different places and joined by a span.** SCI state (`seon.sci.eval/binding-rows`,
   `seon.turn.batch/live-bindings`, Var roots) supplies resolver and value facts; clj-kondo analysis of the same text
   supplies calls and references (`seon.turn.batch/analyzed-entities`, `seon.turn.batch/join-write`); files supply the
   file link and form span that a form cannot know (`seon.turn.batch/file-attributes`). Of the seven defects in the request,
   at least four are a value from one of these sources surfacing in another's output: the self-require (SCI bindings in a
   digest), the REPL require (SCI bindings in an ns form), the lost file link (form text without the file's facts), and the
   kept value (one branch's runtime value read by another).
2. **Two digests depend on the resolver context, and only one of them filters it.** `seon.program/used-resolver-context`
   filters the live SCI bindings to what a declaration uses (6cda06602, 3da13c834). Any caller passing the unfiltered
   `live` bindings moves digests for no change to the program. The filter's own rules (imports whole, own namespace
   excluded, alias qualifier by `source-resolver-names`) carry the edge cases.
3. **Stored state is layered by identity with no single owner per field.** `seon.turn.batch/entity-facts` decides per
   definition digest whether to write nothing, a replacement that keeps `file-attributes`, or a new entity; and
   `seon.sci.eval/kept-value` keeps def values per compartment in `seon.cache` by definition digest. A value's life is
   split between the database (digest, row), the cache (kept value, program context by content) and SCI (the Var).
4. **The same declarations are computed twice: at settlement and when a context installs them.** `program-facts` derives
   facts from the batch on the head (`seon.turn.batch/settle` -> `attempt`), and `seon.sci.eval/install-evaluated-rows!`
   then re-installs the committed rows into a context, through `install-row!` and `install-function-from-database!`. The
   def-init re-run (10f6a4d5a) was that second derivation evaluating a form again. Program contexts are derived again
   per content key (`seon.sci.eval/program-context`, `derive-overlay-plan`), so one row can be evaluated at each derivation.
5. **Write-back trusts the stored file link, and the link is the thing the recording path can lose.** `seon.cluster.source`
   `file-digests` selects declarations by `[?e :seon.fn/file ?file]` (~1731) and the merge check compares them with the
   reindexed candidate (~1937); a row that loses its `:seon.fn/file` (the pre-504ff11d0 `entity-facts`) makes its file
   look unwritten. A file-derived model (clj-reload parse, `committed-bytes` at git HEAD) gives the file as the owner of
   its declarations; Seon currently gives the database the owner role for the link and the file the role for bytes.
