---
type: review
status: completed
reviewer: Astra
date: 2026-09-26
design: 0af223c5360ba65003cf8edf07cd401cec38ab8a
source-basis: 3c40f0c21e682dd8504d08a758cf5a402e600fdd
verdict: REVISE
---

# Independent review: REPL-entry renders

**REVISE.** Reusing `preview-sources` → `evaluate-sources` → `evaluate` →
`shown-result` → `seon.repl/text` is the right composition. It does not yet
establish read-only execution, one read per form, content-valid transcript
reuse, or the promised history semantics. Those are design defects, not reasons
to add another evaluator.

Broken evidence boundary first: `bin/seon status` and MCP `runtime_status`
reported default alive but **faulted**, pid 25654, start
`2026-09-26T02:39:49.524Z`, 9 error signatures, 95 failed tests and 26 unknown
failed tests. Publication was off; the running source root was
`data/source/fbd0897dfa9ee3cb4e0943b2c6196a7f4dfc3892`, not this checkout.
Its reported ready time was 39,333 ms. These are observations, not passing proof
of the proposed stages. Other lanes continued changing the checkout; source
citations below are pinned to **3c40f0c21**, unless another revision is named.
I inspected committed versions where working files changed.

Read authorities: all of `AGENTS.md`, including the render ruling introduced by
`e5c19877f` and “The one REPL path”; all of `open-items.md`'s `## Now`; all 444
lines of the design at `0af223c53`; the data-oriented Clojure, REPL and Datahike
skills. The later open-items text and live status disagree about the active JVM;
the live observations above govern this review. No production edits, publication,
restart, foreign-session interaction, paid provider run, or worktree was used.

## Findings, most severe first

### 1. Blocking: dropping two connections does not make the viewing world read-only

The two carriers identified in §2.3 are real: custody is bound by
`src/seon/sci/eval.clj:4060,4092`; the call-preparation hook reads the environment
connection at `src/seon/call_preparation.clj:1189-1196`. Removing them stops those
implicit connection supplies. It does **not** revoke write authority:

- `seon.db/transact!` explicitly allows a supplied live connection when custody
  is absent (`src/seon/db.clj:4872-4901`). Removing custody therefore removes its
  ordinary agent connection comparison, rather than installing a read-only
  policy. `branch-connection` can recover an open connection from a database
  value and branch (`src/seon/db.clj:344-353`). A held connection, closure or
  callable host function is another route. I did not execute a database write.
- SCI's `fork` copies the environment map into a new atom, not every object
  reachable from its roots (`reference-code/sci/src/sci/core.cljc:345-351`, pin
  `409888f61fa1f3bbade8d7e3eecb9b8e09c8004a`). Seon deliberately preserves actual
  private roots (`src/seon/sci/eval.clj:2891-2916,2962-2985`). The live dependency
  probe below mutated an atom through the child and observed **1 in the parent**.
- `evaluate-sources` still carries the cluster connection into the evaluation
  request and walk context (`src/seon/turn.clj:4807-4826`) and the function gate
  (`:4827`, `:3271-3293`). `shown-result` prefers a request connection and can
  prepare durable error evidence (`src/seon/sci/eval.clj:3679-3703`). Two map
  deletions do not establish “persists nothing” for the entire composition.

P4 proves that a particular `defn` did not settle a program row. It does not
prove read-only execution. It also returned a Var, contradicting §4's promised
regression that a render `defn` “shows its refusal.” Require/alias changes in the
disposable world and externally visible mutation must have distinct semantics.

**Required revision:** specify and enforce the read-only stage at the existing
execution/write/effect authorities, including explicit arguments and shared host
objects; audit every carried authority. Do not add a render-only evaluator or
claim that a deny-list of top-level source names catches nested compiled calls.
Test an elided write, an explicit-connection write, a supplied writer, a nested
writer, a shared atom/closure, and permitted `require` on the real agent path.
The smaller immediate cut is to establish this stage in the existing preview
owner before migrating renderers. If the complete guarantee needs a constrained
admission contract, name that unresolved owner decision instead of calling the
current two-field omission sufficient.

### 2. High: the proposed transcript cache is not content-valid

`[program identity, namespace, entry bytes]` plus nonempty database read evidence
is insufficient. `read-evidence-current?` checks retained database dependency
plans/revisions, with optional read replay; it does not claim to track all inputs
to an arbitrary Clojure expression (`src/seon/db.clj:1159-1202`). The live probe
below changes a local atom from 0 to 1 while the expression also pulls root's id:
the answer changes, there is one captured database read, and the predicate is
still **true**. The proposed empty-evidence exclusion does not catch mixed reads.

Other missing inputs are private callable/value roots, alias/refers state, the
viewing agent's supplied identity, effective print/profile settings and preceding
entry state. `reader-context` resolves aliases from the actual SCI namespace
table (`src/seon/sci/eval.clj:940-965`); namespace name alone is not that table.
Print settings advance between forms (`:4278-4288`). Profile acquisition occurs
outside the per-form read sink (`src/seon/turn.clj:4772-4777,4810-4813`), so the
sink is not proof that every presentation input was captured.

Caching `{shown, read-evidence}` also drops stdout and ending-namespace data
needed by the transcript (`src/seon/repl.clj:171-205`). Skipping a cached entry
that changes local alias/namespace state can change the next form's meaning.
A whole-program identity causes all entries to miss after unrelated program
changes, contrary to the reached-input cost law.

`render/shared-cache` is a real, existing branch-scoped holder
(`src/seon/render.clj:1602-1652`); using it adds no second physical cache/store.
It does add a new memoized derivation whose validity and eviction still need
definition. Existing `call-cache-evidence` already accounts for callable identity,
projection, arguments and agent (`:803-839`); that is a better starting point,
though it is not proof for arbitrary source expressions either. If custody loses
its connection, carry the held database there so `branch-scope` does not become
empty (`:1623-1627`). Treat only literal `true` from a predicate returning an
error alternative as positive proof.

**Smaller:** omit this new cross-prompt transcript memoization from the first cut.
Keep dependency-owned query/pull reuse and measure one composed fork. Later cache
only computations with complete immutable input evidence, retaining the complete
transcript outcome. Never label a nonempty subset of reads complete evidence.

### 3. High: §2.3's “read once” claim is falsified by its own finalization stage

`evaluate-for-install` calls `one-event` and supplies that event to `evaluate`
(`src/seon/sci/eval.clj:4467-4489`); `evaluate` consumes it without reparsing
(`:4225-4234`) and calls `sci/eval-form` once for the form (`:4274-4275`). This
part supports the design. However, `evaluate-sources` subsequently calls
`disposition-rule-error`, which **calls `read-source` again** on the same source
(`src/seon/turn.clj:4725-4727,4833-4836`). It receives a namespace name, not the
reader's complete alias context. Removing `planned-sources` does not remove
that second read. `repl/def-note` additionally uses `edn/read-string` on source
(`src/seon/repl.clj:119-140,204`) until its claimed retirement actually lands.

**Required revision:** pass the already-read event/form to disposition and
declaration-note consumers on the common path; retire reparsing there. The
ordinary reply segmenter's separately ruled passes are not this finalization
reread. No replacement parser is needed. Verify alias-qualified keywords and
syntax-quote after `require`, not just `(+ 1 2)`.

### 4. High: terminal result rendering and recursion are unspecified

The design says `shown-result` selects a renderer and then prints the result,
while every AI renderer now returns executable entries. Selection is not a
terminal floor today: `shown-result` calls `render.value/prepare`
(`src/seon/sci/eval.clj:3715-3723`), whose map-node path invokes declared pairs
(`src/seon/render/value.clj:617-644,646-652` →
`src/seon/render.clj:1153-1220`). Thus printing an entry's result can select
another entry-producing renderer. A renderer that reads back its own entity is
the obvious cycle; `render-note-form` already emits that read
(`src/seon/note.clj:131-137`). Errors also carry declared render pairs.

Without a specified terminal stage, simply changing output contracts either
leaves vectors of entries/refusals in the print path, or creates recursive
evaluation while printing. The graph's back-references and node limits do not
bound recursion inside `shown-result`. This is an unclosed design seam, not a
claim that the unimplemented recursion has already happened.

**Required revision:** state how selection, entry evaluation and terminal result
printing compose once, including nested declared values and failures. Examine
the existing structural-print option (`src/seon/render/value.clj:623-624`) and
reuse the same floor, rather than introducing another printer. Prove an entity
self-read, a returned entity with a declared pair, and a failing renderer.

Also, the live `seon.repl/text` probe below still prints
`#:seon.repl{:value 3}`. Its current `response` owns that grammar
(`src/seon/repl.clj:216-237,264-281`). §2.5's manually laid-out examples are
target illustrations, not an end-to-end installed `clojure.main` transcript.
Move the shared grammar conversion into the claimed end-to-end proof slice,
preserving stdout, errors and saved shown bytes through that owner.

### 5. High: D7 must be fixed with read-only preparation, but its diagnosis is too broad

The defect is in **`hook`**, not `prepare`. The hook derives the database for
preparation-plan lookup only from the connection, returning raw arguments if
none exists (`src/seon/call_preparation.clj:1189-1215`). This explains both
missing database and missing agent-id defaults in P3: preparation was bypassed.

The actual database supplier **already prefers the scoped database value**:
`src/seon/db.clj:2270-2295`. It did so at the design revision too. Therefore D7's
statement that a supplied read necessarily receives current head is not supported
by its cited hook code. That code does establish a potentially inconsistent
**plan** basis. Retain the existing supplier; do not write a second one.

Changing the hook's database expression alone is also insufficient for older
held values. `current-snapshot` returns its held snapshot whenever
`checked-through-t >= requested basis` (`src/seon/call_preparation.clj:598-609`),
and `adopt` keeps the newest basis (`:568-580`). A newer preparation plan is not
automatically valid for an older program world. A fork also inherits the
call-preparation carrier unless its owner explicitly handles that state.

**Required revision:** keep this fix in the same slice, using the scoped value
to acquire a content-valid plan while preserving supplied-argument precedence
and ordinary connection-backed calls. Prove no-connection defaults, held value
versus later head, and a held old program after a supplier/contract changes.
Correct the hook's temporality prose (`:26-30`) along with the implementation.
This is smaller and more accurate than replacing database supply generally.

### 6. High: option A is a policy change, and none of A/B/C is acceptable as written

The design correctly exposes this decision, but §2.3 already chooses A by saying
“record: none.” A provider capture is real: `seon.context/capture-tx` records
exact prompt bytes and contributions (`src/seon/context.clj:359-402`), and the
turn commits them before the provider (`src/seon/turn.clj:4542-4568`). However,
that write is conditional on **not** `no-provider` (`:4553`); a debug preview
does not execute it. It is neither an evaluation record nor each form's read
evidence, and it only covers the prompt selected for that attempt.

`AGENTS.md` still requires recorded/queryable results on the one REPL path,
changed-read refresh including agent-authored reads, and settling shown-text
durability before deleting evidence. `system-plan` explicitly includes those
agent reads (`src/seon/turn.clj:2017-2026`). Removing its refresh path to save
about 200 lines can remove a separate required behavior, not just obsolete
opening assembly. A disposable evaluation fork does not imply no owner may
record its observations after evaluation.

- **B, corrected, is the smallest compatible first slice (recommended for now):**
  keep the existing record/refresh owners, feed entries, and use the read-only
  fork for the rendering execution. Recording by the lifecycle owner is separate
  from admitting writes inside a render form. Preserve handles through the
  existing result owner or explicitly scope their absence; do not invent another
  result store. B's assertion that the read-only fork must be unused is false.
- **A is a possible later owner decision**, not established by the render ruling.
  It needs an explicit durability/retrieval contract for every shown surface and
  a decision about agent-read refresh. Its “always current” guarantee also
  depends on fixing finding 2. Retain historical records already written.
- **C adds unnecessary comparison/writing policy.** Equal shown text does not
  imply equal read evidence or live values; a new dependency may matter later.
  If pursued, advance that evidence even when output text is equal, through the
  existing recorder, not a parallel fresh/recorded evaluator.

Do not count an unchosen policy's deletions as the first slice's savings.

### 7. Medium: the proposed entry schema cannot represent all promised entries

`:seon.cluster.reply/form` requires `:seon.cluster.eval/source`
(`resources/seon/schemas/seon.cluster.reply.edn:1-11`). §2.2's standalone
back-reference comment has no source. Live validation against the installed
schema returned **false** for that comment and **true** for a normal source
entry. Adding only `:seon.eval/origin` does not fix this. An empty source is not
a form and cannot silently become an evaluation.

The loop also uses its starting/ending namespace, not each entry's optional
`:seon.ns/name` (`src/seon/turn.clj:4778-4785,4883-4884`). Specify whether that
entry member is advisory or operative; do not promise a namespace field the
execution ignores. `seon.repl/entry` is another existing entry vocabulary
(`resources/seon/schemas/seon.repl.edn:36-43,93`), and `run/usage-form` already
returns a vector of those entries despite its `:seon.render/form` contract
(`src/seon/run.clj:58-84`). The claim that every third-output producer simply
wraps one form is false for that producer.

**Smaller:** converge on one entry representation at the existing reader/loop
boundary, explicitly admitting a comment-only variant there or preserving it as
composition data that never goes through evaluation. Retire the other entry
vocabulary and convert the walkthrough deliberately; do not merely rename its
output contract.

### 8. Medium: cost and bounds do not establish the one-second guarantee

§5 honestly labels totals estimates, but 60 selected `doc`/pull forms at
168–201 ms do not measure renderer invocation, graph acquisition, nested
rendering, history assembly, selection, all cache checks, or the final composed
prompt. The 3.40/3.50 s debug path is already over the law. Naming only
“turn/settlement” is not the required responsible armed-function profile; no
memory result or end-to-end hit/miss count accompanies these claims. One fork
per prompt is necessary, not sufficient. “Same order as 5 ms” is not measured
warm latency for the new path.

Graph `max-nodes`/`max-collection` bounds govern graph acquisition
(`src/seon/render/walk.clj:1034-1050`); they do not limit the number of entries
one renderer returns. The proposed sources schema is an unbounded vector.
`evaluate-sources` runs every source sequentially (`src/seon/turn.clj:4778-4885`);
`await-turn-part!` merely invokes an optional observer (`:4349-4353`). Whole-unit
prompt selection after execution is not an execution bound. Per-form deadlines
alone allow arbitrarily many individually cheap forms.

**Required proof:** explicitly bound aggregate render work/entry admission on
the existing owner, distinguish timeout from actual exit, and measure the same
real root graph on parent and candidate, cold and warm, with armed profiles,
allocated/retained memory, final bytes and cache counts. Include a node with many
entries and nested rendering. Fix or justify each operation over one second;
costs over ten seconds need the separate owner authorization/issue required by
the law. The design's measurements support feasibility, not acceptance.

### 9. Medium: aliases are the right attribute, but seeding and graph scope are incomplete

Use `:seon.ns/aliases`: it already owns component bindings with local and target
symbols and an AI/HTML pair (`resources/seon/schemas/seon.ns.edn:2,26`;
`seon.ns.alias.edn:1-18`). No new curation registry is justified for actual
`:as` requirements. A require plus `dir`, and task-level `doc` entries, matches
the owner rule.

But `namespace-seed-call` returns `[]` for an existing namespace
(`src/seon/cluster/agent.clj:166-178`). Editing that seed does not give today's
root the promised aliases. Include existing-root adoption and preserve its
current requires/refers. Do not delete toolkit facts before consumers migrate.

Moreover, aliases belong to their **owning namespace**, not to every viewer of
that namespace. The graph walks forward and reverse references and can reach
other namespace declarations (`src/seon/render/walk.clj:944-998,1034-1042`).
Blindly executing every reached alias's `require` in one viewing namespace can
retarget the same local name or refuse it. Graph definition order derives from
the enumerated value edges, which do not include aliases (`:914-922,1023-1028`);
it does not prove all namespace setup precedes every dependent entry.

**Required revision:** identify the viewer/task attachment selection, preserve
alias ownership, and prove two reached namespaces using the same local alias
for different targets. Adopt root's declaration through the existing writer.
Keep `:requires`/`:aliases` coherent using the normal namespace declaration
producer, not independent alias facts that invent a second source of truth.

### 10. Medium: the deletion budget is an estimate, not a verified retirement plan

The seven included §4 numbers add to **741**, not 640:
`57 + 110 + 113 + 78 + (63 + 20) + 50 + 150`.
The 150 is already a *net conversion estimate*, unlike gross deleted spans;
subtracting 120 additions from that mixed total is not reproducible accounting.
The first slice's `+60/-80` has no concrete 80-line retirement list and retains
the old string adapter during migration.

Several nominated spans contain surviving work:

- `repl.clj:119-237` includes `value-text` and `error-text` (`:143-167`), which
  preserve shown bytes and Clojure error formatting, not just the response map.
- `entity-emission` (`src/seon/repl.clj:368-447`) translates namespace/renderer
  refs and saved fields, and derives handles; its callers remain in the render
  pair (`:449-454`) and `src/seon/render/transcript.clj:1466,1864`. Removing
  render-time reads is not deleting the entire conversion without replacement.
- Help includes a surviving HTML projection (`src/seon/bootstrap.clj:72`), and
  the third-output producer census misses the walkthrough shape in finding 7.

The ~750 walk and ~500 transcript candidates are correctly excluded pending
other slices/caller audits; retain that exclusion. Give an exact owner/caller
retirement table, distinguish gross deletion from conversion deltas, and count
schema/test migrations. Prefer the existing preview and supplier fixes first;
do not justify new cache/composer machinery with unrelated future deletions.

## Stage-by-stage verdict on §2.3

| Stage | Verified installed seam | Assessment |
|---|---|---|
| World | `sci.eval/fork-for-turn`, `eval.clj:2962-2994` | Existing fork; no installed stage parameter implementing the proposed read-only semantics. Private objects are shared. Do not pass the held agent as `agent-ctx` when a disposable child is intended: that branch regenerates the supplied context in place. |
| Segment | Entries directly into `evaluate-sources`, `turn.clj:4757` | Correct smaller seam; bypass text planning for already segmented entries. Comment-only and legacy entry shapes remain to settle. |
| Read | `evaluate-for-install` → `one-event` → `reader/read`, `eval.clj:4476-4489,957-966` | One primary read in actual SCI namespace context. Finalization and `def-note` still reread. |
| Evaluate | `evaluate`, `eval.clj:3908,4225-4275` | Existing evaluator; no second evaluator needed. It binds the held read value at `:4099-4101`. Authority revocation and plan acquisition are incomplete. |
| Finalize | `disposition-rule-error`, `turn.clj:4721-4740,4833` | Existing policy, but it reparses; completed/wait results can also stop the entry loop (`:4880-4882`). Define render behavior through the common stage rather than assuming every following entry runs. |
| Print | `shown-result`, `eval.clj:3665-3728` | Existing selector/floor, with nested renderer invocation and connection-dependent error preparation. Terminal entry-result semantics are missing. |
| Record | `preview-sources` returns an in-memory preview, `turn.clj:4888-4924` | No new result store, but “none” is a deliberate recording-policy change; capture does not automatically cover this entrance. |
| Transcript | `seon.repl/text`, `repl.clj:264-281` | One existing grammar owner, currently response-map grammar. Change it in place; do not manually assemble a second transcript. |
| Preview | `preview-sources`, `turn.clj:4907-4924` | Already composes fork/planner/evaluate-sources, so extend it, not a parallel orchestration. Its current docstring is not a read-only guarantee. |

`clojure.main/repl` supplies the appropriate dependency precedent: `:read`,
`:eval`, `:print`, `:caught` are values used in one loop
(`reference-code/clojure/src/clj/clojure/main.clj:368-437`, pin
`3bc2b3e91fdff462620d84bbdfd40379d9c562e5`). It does not itself provide the
read-only authority or Seon recording policy. No new parser, evaluator, printer,
physical cache or durable store is justified by this review.

## Reproducible live evidence and limits

All three calls used MCP JVM mode, `root /Users/sean/src/seon`, cluster `default`,
namespace `user`, `read_only true`, 5,000 ms request timeout. They changed only
fresh locally bound objects, not an agent's state or program Vars. The first is
a **dependency** probe, the second a **database/contract-owner** probe; neither
pretends to be an installed read-only agent render. Tools returned complete
small result envelopes. Concurrent branch-error notices were foreign test
observations; I did not operate on their sessions or files.

```clojure
(let [started (System/nanoTime)
      cell (atom 0)
      parent (sci.core/init {:bindings {'cell cell}})
      child (sci.core/fork parent)
      result (sci.core/eval-string* child "(swap! cell inc)")]
  {:fork-result result :parent-cell @cell
   :elapsed-ms (/ (- (System/nanoTime) started) 1e6)})
;; {:fork-result 1, :parent-cell 1, :elapsed-ms 0.859958}
;; io-prepl event: 4 ms. Allocation was not measured for this call.

(let [bean (java.lang.management.ManagementFactory/getThreadMXBean)
      tid (.getId (Thread/currentThread))
      allocated-before (.getThreadAllocatedBytes
                         ^com.sun.management.ThreadMXBean bean tid)
      start (System/nanoTime)
      database @(seon.cluster.boot/connection "default")
      projection (seon.db/carried-projection database)
      valid? (seon.schema/projection-validator
               projection :seon.cluster.reply/sources)
      sink (atom [])
      cell (atom 0)
      read-value (fn [] [(seon.db/pull database [:seon.agent/id]
                                     [:seon.agent/id "root"]) @cell])
      before (binding [seon.db/*read-evidence-sink* sink] (read-value))
      evidence (seon.db/read-evidence @sink)
      _ (reset! cell 1)
      after (read-value)]
  {:comment-entry-valid?
   (valid? [{:seon.cluster.eval/comment ";; see root above"}])
   :form-entry-valid? (valid? [{:seon.cluster.eval/source "(+ 1 2)"}])
   :before before :after after :evidence-count (count evidence)
   :evidence-current? (seon.db/read-evidence-current? database evidence)
   :elapsed-ms (/ (- (System/nanoTime) start) 1e6)
   :thread-allocated-bytes
   (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid)
      allocated-before)})
;; {:comment-entry-valid? false, :form-entry-valid? true,
;;  :before [#:seon.agent{:id "root"} 0],
;;  :after [#:seon.agent{:id "root"} 1],
;;  :evidence-count 1, :evidence-current? true,
;;  :elapsed-ms 0.41275, :thread-allocated-bytes 190104}
;; io-prepl event: 4 ms. This allocation includes the complete bounded form;
;; it is not a render-memory benchmark or retained-heap measurement.

(seon.repl/text {:seon.ns/name 'user
                 :seon.cluster.eval/source "(+ 1 2)"
                 :seon.eval/shown "3"})
;; "user=> (+ 1 2)\n#:seon.repl{:value 3}"
;; io-prepl event: 3 ms.
```

Contract evidence uses the installed projection's retained Malli validator
(`src/seon/schema.clj:3699-3707` → `malli.core/validator`,
`reference-code/malli/src/malli/core.cljc:2637-2643`, pin
`befcd302a8e626a67d84b80aabee0cd1b38dadb6`).

The inspected `reference-code/datahike` gitlink is
`f3a3cb59380c8b1e9ea0a3d6074c0863012cf0dd`; Seon's read-evidence owner is the
actual probe entrance. No claim of validation for the proposed new schema or
stage is made.

This is a design review, with **0 source lines and 0 test lines changed**. No
`bin/test` gate, `seon.test/run`, candidate publication or browser-paint proof was
run; there is no implementation diff to exercise. The live archive mismatch is
the installation limit, not a reason the source audit stopped. No scratch branch,
JVM, namespace or filesystem measurement resource was created to clean up.
Only this review file is owned and committed; its commit identity is reported
in the handoff. Release this path after the commit.
