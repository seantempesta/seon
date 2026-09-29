# defn write census (lane defn-write-census, 2026-09-28)

Probe: MCP eval_clj cluster default, agent `defn-census`, own branch `:agent-9065ab3020ee` (fork basis 536871601).
Run 1 (SCI): `(ns scratch.census)` then `(defn census-probe {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))`.
Run 3 (SCI): same defn with docstring "changed docstring". Read back in jvm mode: `(d/datoms (d/since (seon.db/branch-connection @cn :agent-9065ab3020ee deref) 536871601) :eavt)`, grouped by tx.
Each run settles as ONE tx (run 1 = tx 536871602, redefine = tx 536871626). Other txs on the branch are my own tiny jvm-mode probe runs: 40-85 datoms each.
Evaluation `:ms` 0-4, `:calls` 224-352; settlement wall not visible in the reply (Astra measured 1,128 ms tool wall for the same shape).

## 1. Histogram (run 1, tx 536871602: 3,557 datoms, 1,129 entities incl. tx entity)

By attribute namespace: seon.db.vector 1107, datahike.read 1100, seon.cluster.eval 671, seon.db 550,
seon.profile.measurement 69, seon.eval 16, seon.fn 14, seon.turn 7, seon.fn.arity 5, seon.fn.binding 3,
seon.program 3, seon.fn.argument 2, seon.ns 2, seon.db.transaction 2, seon.schema.admission 2, seon.sci.eval 2, db 1, seon.turn.work 1.

By attribute (top 30): seon.db.vector/cells 552, seon.db.vector/position 552, seon.cluster.eval/read-evidence 550,
datahike.read/revision 550, datahike.read/dependency-plan 550, seon.db/source-argument-position 550,
seon.cluster.eval/executed 102, seon.profile.measurement/at 18, /calls 18, /p99 16, /errors 9, /p50 5,
seon.db.vector/present 3, then 2 each: read-basis-transaction, source, author, seon.eval/duration-ms, value-tokens,
cluster.eval/id, eval/value-class, program/definition-digest, sci.eval/ending-ns, cluster.eval/ns, run, eval/shown-tokens,
ordinal, eval/shown, code-evidence, eval/unstored, schema.admission/source.

Entities by kind (majority attribute namespace): read-evidence rows (datahike.read) 550, vector cell/position entities 285,
eval-owned entities 269, profile measurements 18, one each of fn, fn.arity, fn.binding, fn.argument, ns, turn, db.transaction.
Read-evidence is 550 of 1,129 entities and about 3,300 of 3,557 datoms (93%): 550 x (row + revision + dependency-plan +
argument-position) = 2,200, plus 1,104 vector cell/position datoms holding the ordered list of those rows.
Everything else (the defn itself, its evaluation, the turn) is about 250 datoms; the fn/program facts proper are about 25.

Read-evidence content: 527 of 550 reads are `[entity :seon.fn/sym]` patterns (revision `{:attributes #{:seon.fn/sym}}`, 20 distinct
revisions; 549 distinct dependency-plan strings, about 366 chars each, 202 KB of EDN in one tx). Their pattern-entities are
366 SCHEMA entities (attributes seon.schema/key, seon.render/ai, seon.render/html, seon.render/form, seon.program/definition-digest)
plus about 150 function entities (129 have render/html/ai in their symbol names, e.g. seon.render.ns/*, seon.note/render-notes-html,
seon.context/capture-html). I.e. the read set is the population of render-declaring schemas and render functions, not the one defn.

## 2. Top 5 groups: producer, proportionality, consumer

1. Read-evidence rows (datahike.read/*, seon.db/source-argument-position, seon.cluster.eval/read-evidence): 2,200 datoms.
   Writer: `receipt-read-evidence-tx` src/seon/turn.clj:1233-1250 (one component row per entry, id `seon.cluster.eval/read-evidence/<eval>/<ordinal>`).
   Capture: `append-read-evidence!` src/seon/db.clj:557-590 (called from `db/q` db.clj:2839 via append-query-evidence! :852-883 and
   `db/pull` db.clj:2989 via append-pull-evidence! :896-925, live stack seen at call_preparation.clj:1129).
   Proportional to: the SCHEMA/RENDER POPULATION (about 550 entities = every render-declaring schema and render fn), not the change.
   Same 549 reads for the redefinition, and my direct `sci/eval-string*` of the defn made 0 reads (so they come from the form's
   window in the real run: the shown-result/related-neighbourhood stage, `render/walk` evidence into the sink at walk.clj:496, is the strongest
   candidate; I did NOT pin the exact caller by stack because the per-form sink `:seon.batch/reads` is created inside `seon.batch`
   (batch.clj:302, frozen at :455-466) and is not reachable from outside; a warm shown-result of the same Var made only 4 reads).
   Consumer: `db/read-evidence-current?` (db.clj:1294) used to decide whether a shown unit needs re-render (render.clj:980,1529,1585;
   turn.clj:1941 `:changed`); `latest-evaluations` turn.clj:1830-1860 pulls it whole for the kept evaluations; health.clj:72 reads attributes only.
   For a Var result whose reply is stored history ("history is never re-rendered") the refresh has no work to do, so the evidence
   has a consumer only if the shown unit is re-rendered when a read changes; none of 550 patterns is about the defn.
2. Vector cells/position (seon.db.vector/*): 1,107 datoms, 285 entities. Writer: the ordered-vector encoding of
   `:seon.cluster.eval/read-evidence` (552 cells + 552 positions, 3 present). Pure overhead of group 1 (2 datoms per read row).
   Proportional to group 1. Consumer: none of its own (decode on pull).
3. `seon.cluster.eval/executed`: 102 datoms (89 on redefine). Writer: turn.clj:4830-4838 (the batch's executed set) from `executed` in
   batch.clj:455 freeze-step. Proportional to the FUNCTIONS THE RUN CALLED (platform code executed by the defn path: arm, admit, render),
   not to the change. Consumer: turn.clj:1900-1916 (a stored evaluation is current if these `[sym digest]` pairs still match) and turn.clj:3536.
   Legitimate for an evaluation whose result depends on code, but 90+ of them are the platform's own path for every defn.
4. `seon.profile.measurement/*`: 69 datoms, 18 entities (41 and 12 on redefine). Writer: src/seon/profile/measurement.clj:~98-200
   (records a measurement when a function's bucket moved beyond stored). Proportional to PROFILE VOLUME of unrelated armed functions
   whose bucket advanced during this run, not the change. Consumer: health.clj:478-529 (hot/over-bound warnings).
   It rides the run's settlement tx but belongs to a separate maintenance write.
5. The evaluation + turn + fn/program facts: about 40 datoms in `seon.cluster.eval` (id, ordinal, at, source, author, ns, run,
   read-basis-transaction, code-evidence), `seon.eval` (shown, shown-tokens, value-tokens, value-class, duration-ms, renderer, unstored),
   `seon.turn`, `seon.fn` (14) + arity/binding/argument (10), `seon.program` (definition-digest, analyzed-source-digest), `seon.ns`, `seon.schema.admission/source`.
   This is the only group proportional to the change (one fn, one eval). Consumers: the context/transcript render, program entities, gates.

## 3. Second identical-shape run (redefine with a changed docstring, tx 536871626)

3,462 datoms, 1,114 entities. Other new defns of the same shape: 3,504 (census-probe2), 3,581 (two forms).
By ns: seon.db.vector 1099, datahike.read 1098, seon.cluster.eval 648, seon.db 549, profile.measurement 41, seon.eval 9, seon.turn 7, seon.fn 4, ...
Read-evidence 549 rows again; after stripping the read basis (t and instant) 547 of the 548 distinct plans are byte-identical to run 1's,
so 99.6% of the read evidence is rewritten unchanged. Non-read datoms 167 (vs 196). The fn change proper is `seon.fn/source`, `seon.fn/ns`,
`seon.program/definition-digest`, `analyzed-source-digest` (2-4 datoms). Answer: yes, unchanged content is rewritten in full every defn.

## 4. Minimal datom set and what should stop being written

Minimal for one defn (about 60-90 datoms, one tx):
- the evaluation entity (about 20: id, ordinal, at, source, author, run, ns, shown, shown-tokens, value-class, duration-ms, renderer, call-count, read-basis);
- the function entity with its arity/argument/binding components and program facts (about 25-30);
- the turn/run bookkeeping (about 10) and the tx entity.
Stop writing, in this order of size:
1. Per-form read evidence for a shown result that is never refreshed: 3,300 datoms (93%). Store evidence only for reads whose change must
   re-render (an entity-scoped read of the shown value's neighbourhood), or hold the schema/render-function population as ONE digest
   (definition digests already exist) instead of 550 rows; at minimum bound a form's rows by the change, not the registry.
   If it must stay per-read, dedupe against the previous evaluation of the same source (547 of 548 identical) and drop the 2-datoms-per-row vector encoding.
2. `executed` sets of platform code: store the digest of the set, or only definitions the form named (about 90-100 datoms).
3. Profile measurements: write from the profile listener as its own maintenance transaction (41-69 datoms).
Expected result: settlement of tens of datoms, and the writer-validation cost Astra measured (573 validated entities, 21 owning roots)
falls with it because it is proportional to the datoms written.

## Limits and follow-ups
- Exact caller of the 550 reads is not pinned by stack (sink unreachable from outside the batch); next step is a focused test
  of `seon.batch` with a watched sink around `shown-result` of a fresh Var, or a temporary counter in the renderer on a branch.
- No src/test/resources touched. My branch `:agent-9065ab3020ee` and scratch ns `scratch.census` remain (disposable).
