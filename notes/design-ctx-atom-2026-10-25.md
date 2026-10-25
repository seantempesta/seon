# Design: the `ctx` atom: the agent's record as a map it reads with `@` and changes with `swap!` (2026-10-25, revision 2, research only, for root's review and the owner's gate)

Revision 2 folds in the owner's reply to revision 1 (section 8: the rulings, the answers and what each changed). It adds three measured experiments (`exp9`-`exp12`): short keys generated from the schemas, a cardinality-many attribute's order, and the livelock of a `swap!` whose function writes the db, with its fix.

THE PLAN steps it serves: 1 (the context tells the model what to change; the instructions get shorter) and E (turns to green, to be measured by dogfooding, idea 5). It answers the owner's idea 1 (`notes/root-handoff-2026-10-25.md`, "New ideas"), and touches ideas 2 and 4. It builds on the merged agent-view work (design `notes/design-agent-view-2026-10-24.md` r3: `with-reads`, the render cache, the screen evals) and on `jolt/agent-loop` (design `notes/design-agent-loop-2026-10-25.md`: sessions, parking, resume). Nothing here is built on main.

Evidence: every number below was measured for this note in lab agents (`:a1` with 3,041 evals, `:a2` with 115) of the `ctx-lab` worktree's own root (ports 8230/8231, provider `:none`, no model calls). The lab code is on this branch: `notes/ctx-lab/ctx.clj` (db → map, patch), `notes/ctx-lab/ctx-atom.clj` (the whole atom: read hook, write validator), and `exp2`-`exp8` (each experiment, as evaluated; `notes/ctx-lab/run.sh AGENT FILE` loads one). Costs are `seon.cost/measure` (ticks, bytes allocated; the minimum of 3 calls after a warm one), or one `seon.profile/measure!` where a call changes state.

## Decided (owner, 2026-10-25), and the plan
Rulings on revisions 1-3 and sections 10-11 ("In general I'm down with everything you're proposing"). Root files them in `docs/decisions.md`.

1. **`ctx`:** a plain atom over the agent's record on its branch, built by one pull derived from `:seon.render/units`, `:parts` and `:keys`, kept by a patch from each transaction's tx-data, written through `swap!` (the validator: one transaction, schema-checked, held-checked).
2. **Short keys**, generated from the schemas (`:evals :runs :turns :messages :task :agents :proofs`). Attributes keep their qualified names. **Things are named by themselves:** a var by its symbol, a namespace by its symbol, a schema entry by its keyword, any other entity by a lookup ref; never a printed string.
3. **The map carries its environment,** read-only: `:t`, `:branch`, `:commit`. A `swap!` computed from another basis is refused, which closes the livelock (§8.8).
4. **Everything writable,** every change shown on the page with history. Refused only by the schemas and the basis check: no producer check at write, and approval is the merge. Agents are told not to cheat. **Provenance is a query** on tx-meta: `:seon.tx/branch` exists, and two keys are added, the branch a catch-up merged and the eval that wrote (§9).
5. **Removing a context chunk is a retraction;** history keeps it.
6. **Nothing regenerates on a read.** `@ctx` is a deref, and the full build runs only at init and resume.
7. **Every agent is the same,** root included; views render from the db by the one mechanism (`with-reads`). The page renders the agent's environment, `ctx` included, with removals and provenance shown.
8. **Screen forms** evaluate at the latest state, like every eval. They become a fact on the agent, written through `ctx`.
9. **Keep the `my.*` functions;** an experiment's instructions are what its task's plan cites.
10. **The session sees its diff from main** (`:changes`: data and functions alike), and **marks what it proposes to merge** (`:merge`, refs, checked closed under `:form/refs`); only marked ∩ changed merges (§10).
11. **Superseded (root, 2026-10-25): messages are main-first.** A message is written on main with `:seon.run/taken`, and the recipient holds the taken copy on its branch. The agent loop measured both designs at n ≤ 100 (agent-loop design §3a (16066cc0b on jolt/agent-loop)): main-first costs 4.96 ms a delivery, sender-branch at least 16.79 ms, because the reconciler reads only main where sender-branch would read every branch's every commit; both are safe against lost wakes. §11 is kept as the record of the alternative.

12. **Vectors in schemas are supported;** the one conversion stores them serialized (owner: "Don't we have a conversion function between schemas and the db? can't we just support vectors and then under the hood serialize them? … as an alternative to the hacks").
    - The conversion is `seon.schema.datahike/storage`. Today it refuses `[:vector T]` and `[:sequential T]` (`::ordered-collection`, datahike.clj:91) unless the schema is marked `{:seon.db/data true}` by hand.
    - Our Datahike already stores plain data as `:db.type/data`, written by the store's serializer (jfasl), with no text in between. Measured on 5,006 records: 1.3 µs a read, 1.6 µs a write (docs/storage.md#plain-values).
    - So the conversion infers it: any schema that is a collection value (`:vector`, `:sequential`, `:tuple`, a `:map` that is not `{:seon.db/attributes true}`) stores as `:db.type/data`, card one, and reads back as itself, order and duplicates kept.
    - `[:set T]` of scalars or refs stays cardinality many (indexed per member).
    - The hand mark `{:seon.db/data true}` goes (every use is one of those shapes), and so do the hacks that worked around it: `:seon.run/changed` printing tagged keys as strings becomes a set of refs (decision 10).
    - Cost: none at read beyond the serializer's. Built (step 0): every one of the 364 declarations is the same as before, so no stored type changes and no store is erased. `:seon.run/changed` as refs moves to step 4, with the mark it serves.
13. **Order of work:** the build starts after agent-loop part 1. This session builds it, step by step in `ctx-lab`. Root briefs agent-loop on decision 11.

**Step 0, built (jolt/ctx-atom):** `storage` infers `:db.type/data` for `:vector :sequential :tuple :map :map-of :any` and an `:or` whose arms differ in type; an `:or` with a refused arm stays refused; a `[:set T]` of data is refused (`::nested-collection`: one datom a member is for scalars and refs). The 46 `{:seon.db/data true}` marks and `::ordered-collection` are gone. The 364 declarations equal main's (compared in a REPL against main's `declarations`, loaded beside the new one). Proof: `seon.db-test/a-value-with-no-one-type-is-plain-data-by-its-shape` (red on main: `[:vector :keyword]` is refused there, and its schemas carry the 46 marks).

**Slice 1, built (jolt/ctx-atom; root's reorder of 2026-10-25, the owner: "focus on the rendering functions part"):** read-only `@ctx` (`seon.ctx`, docs/ctx.md) and the page's block of it, with the agent's add path: register a key with a `:seon.render/html`, `defn` the render, write the datum, `@ctx`, and the block draws it by that render, unmerged (browser-checked). The block is drawn in the agent at record time and kept as HTML, since root cannot call an agent's render. Datahike pin 336de2ab: a replaced card-one value is retracted in the report (upstream's behaviour too). Open: the model can read an earlier `@ctx` block's HTML inside `@ctx`'s `:evals` (cut by the printer), since the REPL's reply prints without the nested renders a recorded value's own `:seon.render/ai` would use.

**The plan** (each step red then green, measured, reported before the next; after agent-loop part 1 fixes the agent key and session):

| Step | Builds | Proofs |
|---|---|---|
| 0 | vectors in schemas: `storage` infers `:db.type/data` for collection values; the `{:seon.db/data true}` marks deleted | every schema's declaration is the same type as before (a generated comparison over the registry), a `[:vector T]` attribute round-trips order and duplicates; `mg/check` |
| 1 | read-only `ctx`: the derived pull, short keys, `:t :branch :commit`, the hook on `transact!`'s tx-data, `datoms-diff` for `merge-main!` | property test: after n random transactions `@ctx` equals a fresh build; cost proof: the patch is constant in the session's size; init and resume build it; across runs of one session (agent-loop part 3: a `:wake` on the same agent and process after a `:closed` run) `ctx` is kept and patched, never rebuilt at a run's start; a `:lost` wake's next run is a spawn that loads the session, and builds it once |
| 2 | the write: validator, held check, basis check | each refusal of §3 and §8.8 leaves the db unchanged; the livelock case calls its function once; cost proof per write |
| 3 | provenance: `:seon.tx/eval` and a catch-up's source in tx-meta | a query names the eval and branch of every change; a caught-up fact names main |
| 4 | `:changes` and `:merge` (refs), checked closed under `:form/refs`; the merge carries marked ∩ changed | a mark of an unchanged thing or an open call is refused; only marked keys reach main |
| 5 | `ctx` shows messages as agent-loop delivers them (main-first, agent-loop design §3a (16066cc0b on jolt/agent-loop)): the taken copy on the agent's branch, patched in like any fact | a taken message appears in `ctx` from its datoms; nothing in `ctx` sends or delivers |
| 6 | the page renders `ctx` with removals and provenance; the help page section; a dogfood run comparing plans that cite `ctx` and `my.*` | checked in a browser; turns to green measured |

## 0. The answer in one paragraph
Yes, there is a simple and sure way, and it is mostly Datahike's and Clojure's own parts. **db → map** is one `d/pull` whose pattern is derived from the schemas' existing `:seon.render/units`, `:parts` and `:keys`, with each reverse-ref collection made a map keyed by its members' identity attribute. **Keeping it current** is a patch from the transaction's own `:tx-data`: per datom, one map lookup and one `assoc-in`, plus a pull of each entity that joins. Measured at 8.0-8.3K ticks for a new eval whatever the session's size, against a rebuild that grows linearly (0.89M ticks at 115 evals, 19.4M at 3,041). **map → db** is the atom's validator (`set-validator!`, Clojure's hook that refuses a state). It turns the change between the old and new map into one transaction, checks every changed value against its Malli schema, checks that the db's own form of the write holds what the agent wrote, and transacts. An invalid write throws out of `swap!`, with Malli's explanation, and changes neither the atom nor the db. The live atom stayed equal to a fresh pull through REPL records, writes, a commit, retractions and seven write cases. What it doesn't do: it doesn't render. The page and the screen stay root's renders from the db (`with-reads`); `ctx` is the agent's working copy of its own record, so it simplifies the agent's instructions, not root's views.

## 1. db → map: the data tree and its pull

**The data tree** is the agent entity and the units its schema declares, followed down:
- agent (`:seon.db/agent-fact`, units `[:seon.run/_agent :seon.eval/_agent]`);
- its runs (`:seon.run/run-fact`, units `[:seon.run/task :seon.turn/_run :seon.message/_to]`), each with its task (`:seon.task/task-fact`, unit `[:seon.task/proofs]`), turns and messages;
- its evals (`:seon.db/eval-fact`, each one `:seon.eval/record` data value: code, response, `:fns`, `:cites`).

The screen inputs (`my.task/status`, `my.branch/changes`) are computed values, not facts, so they are not in the tree. A screen eval's record is, like any eval.

**One pull pattern, derived** (`pattern`, `notes/ctx-lab/ctx.clj`): an entity schema's `:seon.render/keys` (else `*`), plus its identity attribute (the agent schema's keys leave out `:seon.agent/key`), plus one nested map per unit, by the unit's own entity schema (`seon.render/entity-schemas`). For the agent it produces:
```clojure
[:seon.agent/key :db/id :seon.agent/id :seon.agent/status :seon.agent/pid :seon.agent/branch :seon.agent/running :seon.agent/description
 {:seon.run/_agent [:seon.run/id :db/id :seon.run/status ... {:seon.run/task [:seon.task/id * :seon.task/proofs]}
                    {:seon.turn/_run [:seon.turn/id *]} {:seon.message/_to [:seon.message/id *]}]}
 {:seon.eval/_agent [:seon.eval/id *]}]
```
So yes: the existing render properties drive it, and the pattern is the same one `seon.render/walk` follows one hop at a time.

**The shape (`keyed`).** Each reverse-ref vector becomes `{identity-value member}`:
```clojure
(get-in @ctx [:seon.eval/_agent "e12" :seon.eval/record :response])
(get-in @ctx [:seon.run/_agent "lab/ctx#1" :seon.run/task :seon.task/plan])
```
Members are addressable by the identity the schema already declares, and a new member written into the map needs no tempid: its key is its identity, so the write is an upsert.

Two things the pull doesn't give, found here:
- **Cardinality-many comes back as a vector** (`:seon.task/proofs [t0 t1]`), while the schema says `[:set T]`. The map holds a set, so a `conj` or `disj` means what the schema means.
- **Order.** A map loses tx order, and content-id entities (runs, messages, the agent) have hashed eids, so `:db/id` doesn't order them either. The conversation keeps reading `seon.context/records` (tx order). `ctx` is for looking up and changing, not for ordering.

**Cost of the whole build (cold), measured:**

| Session | Evals | Turns | Branch datoms | EDN of the map | Ticks | Bytes allocated |
|---|---|---|---|---|---|---|
| a2 | 103 | 34 | | | 0.89M | 4.7 MB |
| a1 | 1,007 | 333 | 121,322 | 1.31 MB | 6.47M | 35 MB |
| a1 | 3,035 | 1,000 | | 3.92 MB | 19.4M | 103 MB |

- That is about 6.4K ticks an eval: linear, as a build of everything must be.
- Of it, the pull is 6.2M at 1,007 evals (evals alone with `*`: 3.95M; ids only: 2.27M), `keyed` is 0.24M, and the path index (`index-paths`, 4,072 entities) is 0.73M at 3,041.
- It runs once per process: at init, and at a resume (agent-loop §4, whose whole resume is 0.38-0.40 s). At 3,000 evals it adds 19M ticks (about 100-145 ms CPU on the loaded machine).
- Memory held: not measurable here (481.8 MB with the map, 486.7 MB without, after a full collect, in a lab process grown to ~480 MB by seeding, which is noise). The record values are probably shared with the db's own data values. Not measured: a parked agent's footprint with and without `ctx` (agent-loop's 101-102 MB setup).

## 2. Keeping it current: datoms → `swap!`

**The patch** (`patch`): group a transaction's datoms by entity, retractions first.
- A known entity: each datom sets or removes its attribute (a set member for cardinality many, `{:db/id v}` for a ref, a pulled subtree for a forward ref unit like `:seon.run/task`). An attribute the view leaves out (`:seon.render/keys`) is ignored.
- An unknown entity joins when one of its datoms refs a known entity by a unit's attribute (`[e :seon.eval/agent me]`). It is pulled whole by that unit's pattern.
- A member whose identity is retracted (`:db/retractEntity`) leaves.
- A path index `{eid [path schema]}` finds an entity's place in one lookup.

**O(change), measured** (`exp4`, `exp7`; one new eval: 5 datoms):

| | 115 evals | 3,041 evals |
|---|---|---|
| patch from the transaction's `:tx-data`, schema held | 8,040 ticks, 44 KB | 8,269 ticks, 45 KB |
| of which the pull of the new eval | 7,496 | 7,700 |
| a card-one change (the task's plan) | | ~1K (the patch less its schema read) |
| `datoms-diff` of the same move (what the state lets a watch see today) | 218,101 | 157,752 |
| `agent/transact!` of the eval itself, no ctx | 82,459 | 90,320 |
| a rebuild | 0.89M | 19.4M |

- The patch is about 10% of the transaction it follows, and does not grow with the session.
- **`d/schema` costs 175K ticks a call** (it builds the map). A first version that read it per attribute cost 176K for a two-datom plan change. The schema is read once and held, read again only when a schema datom moves (`d/datom-keys` already marks those `:all`).
- **`datoms-diff` is 20x the patch** on the run-ahead db (157-218K for 5 datoms, not cheaper at the smaller session). So the hook must take the report's `:tx-data`, not diff two db values. `datoms-diff` stays for what moves the db without a report in hand: `merge-main!` (the catch-up), O(Δ log N).

**Refs and cardinality many** map as pull maps them: a ref is `{:db/id e}` (a unit ref is the pulled subtree), a reverse-ref unit is the identity-keyed map, card-many is a set.

**The hook, and where it runs.** Every write of an agent process goes through `seon.agent/transact!` (the REPL's records, the turn's facts, `my.*`'s writes, `my.db/transact!`), and `merge-main!` moves `:db` too. All of them `swap!` the one atom `seon.agent/state`. So `add-watch` on `seon.agent/state` is the right Clojure hook. It runs synchronously, in the agent's one thread, inside the `swap!` that moved `:db`: no thread, no queue, no Flow.
- Measured live (`exp3`): with the watch installed, the atom stayed equal to a fresh pull of 3,033 evals after a plain eval (its REPL record), a `transact!`, a plan change, a `commit!` and a `retractEntity`. A commit moves `:db` to the same datoms flushed, the same `max-tx`, so it is skipped.
- The lab's watch could only diff (330-450K a move), because `seon.agent/state` holds the db and not the report. The spec has `transact!` put the report's `:tx-data` on the state beside `:db`, so the watch costs the patch's 8K. The check that `:db` didn't move is one `identical?` per state swap (the inbox and counters swap the state too).
- **Not Flow.** The owner pictured the listen "as part of the flow setup for the agent". Flow runs only in root, and an agent process has none. The agent's proc in root (`seon.agent-proc`) sees frames, not the agent's datoms. The listen belongs where the datoms are made, in the process that makes them. Root as an agent on main (2026-10-25 ruling) would get the same patch from the writer's transaction reports on main, if root wants a `ctx` of its own.

## 3. map → db: `swap!` writes, validated, atomically

**Why a validator.** Jolt's `swap!` accepts only Jolt's own atom record (`jolt-need-atom`, `reference-code/jolt/host/chez/atoms.ss:74`), so `ctx` can't be a custom `IAtom` type (Reagent's cursor way). It is a plain atom. Its validator (`set-validator!`) is the hook Clojure gives for refusing a new state. Measured in Jolt: inside the validator, `@a` is still the old value, and a throw refuses the swap with its `ex-data`, the atom unchanged (`[2 1 ["too big" {:explain {:v 12}}] 2]`).

**The write (`write!`, `notes/ctx-lab/ctx-atom.clj`), inside the validator:**
1. **The change as transaction data** (`diff-entity`), walking old and new together and skipping every subtree `identical?` to its old self:
   - a changed attribute becomes `:db/add`, or `:db/retract` when gone;
   - set members added or removed become one `:db/add` or `:db/retract` each;
   - a new member becomes an upsert map with its identity and its link to its parent (`:seon.message/to run`);
   - a member gone becomes `:db/retractEntity`.
2. **Valid by the schemas:** each changed value against its attribute's schema, each new entity against its entity schema (`contract/valid?`, `m/explain`). Cost O(change).
3. **Held:** apply the tx to a copy (`d/with`), patch the old map with its `:tx-data`, and require that every path the write changed holds what the agent wrote (the db may add `:db/id` and the member's link). This catches a write the db would silently transform or drop.
4. **Transact** (`agent/transact!`) with `*writing*` bound, so the state hook leaves `ctx` to the write. A watch on `ctx` then installs the db's own form (the new members' `:db/id`).

**Atomic:** one transaction. A refusal at step 2 or 3 throws before anything is written. **Recorded as an eval:** the `swap!` form is the eval's code, recorded as every eval is, and its transaction carries the branch's tx-meta. Nothing new is needed.

**Prototyped, each case measured on `a2`** (`exp5`; afterwards the atom equals a fresh pull):

| The agent evaluated | Answer | The db after |
|---|---|---|
| `(swap! ctx assoc-in [..run.. :seon.run/task :seon.task/plan] "Plan five.")` | the new map | plan is "Plan five." |
| `(swap! ctx assoc-in [.. :seon.task/execution] :sideways)` | **refused**: `ctx: invalid (:seon.task/execution)`, with Malli's error | `:running`, unchanged |
| `(swap! ctx assoc-in [..run.. :seon.message/_to "m-new"] {:seon.message/id "m-new" :seon.message/from "a1" :seon.message/body "a note"})` | the new map, the member now with its `:db/id` | a message fact, `:to` the run |
| `(swap! ctx update :seon.eval/_agent dissoc "s9")` | the new map | `s9` retracted; its 2 datoms are kept in `d/history` |
| a new message without `:body` | **refused**: `:malli.core/missing-key` at `:seon.message/body` | unchanged |
| `(swap! ctx assoc-in [..run.. :seon.run/pid] 4242)` (an attribute the run's view leaves out) | **refused**: `ctx: the db does not hold [..run.. :seon.run/pid]`, `:wrote [4242] :held [nil]` | unchanged |
| `(swap! ctx assoc-in [.. :seon.task/proofs] #{t1 t5})` | the new map | proofs `#{t1 t5}` |

A finding from this: the lab's first version skipped a non-ref unit (`:seon.task/proofs`). The atom then said `#{t1 t5}` while the db kept `#{t0 t1}`, which is a stale atom. The fix put such a unit with the attributes. The lesson for the spec: the "held" check must cover every path the structural diff found changed, not only the paths the generated transaction touched.

**Costs of a write** (`exp6`):
- A plan change through `swap!`, all of it (diff, validation, `d/with`, patch, transact): 87.6K ticks at 115 evals, 90.6K at 3,041 (`transact!` alone: 82-90K).
- Removing one eval: 428K at 115 and 544K at 3,041. Its structural diff is 4K vs 118K: **O(members of the changed collection)**, about 39 ticks a member (`identical?` per entry of the 3,000-entry `:seon.eval/_agent` map), not O(change). Persistent maps have no diff by structure, so this is the honest cost of `dissoc` on a large collection. It is stated in its cost proof as linear in the collection with that constant.

**What the agent may write.** Whatever `transact!` accepts today: no new guard (AGENTS §4, "never gate"). The schemas and the held check are the only refusals, and both explain themselves. An observation an agent rewrote (an eval's record) stays visible as a write: its eval's code is the `swap!`, and history keeps the old value. Whether observations should be read-only through `ctx` is an owner question (3).

**Removing a context chunk, as facts.** `(swap! ctx update :seon.eval/_agent dissoc "e12")` is `[:db/retractEntity e12]` on the agent's branch:
- The current conversation drops it from the next request.
- History keeps the datoms: a request made at an earlier commit (agent-loop §1) still has it, and the debug page resolves `:seon.turn/sent` as of the turn's t, so what was sent stays visible.
- It breaks the provider's prefix cache from that record on.
- A resume replays only the evals present.
- Evals are observations, never merged, so main never sees the retraction.

The alternative is a fact that hides it (`:seon.eval/hidden`, a new attribute) with the record intact (owner question 2).

## 4. Context and UI from `ctx`
- **The page and the screen stay root's renders of the db.** Root's render proc renders every view from records, once per set of reads (`d/with-reads`, `seon.render.proc/comp-entry`). The screen is a request to root for that cached page (`seon.agent/screen`), so an agent can't draw its own view (design r2 §3). The agent's atom is in the agent's process, where root can't read it. Rendering from `ctx` would need a second way to record reads (Reagent's deref tracking over paths) beside Datahike's. That is a second mechanism, so no.
- **The request** (`seon.context/request`) reads `records` by query since the last printed tx, with each record printed once and cached by its ref (`:printed`). That is already O(new records). `ctx` could feed it the new members (each patch knows which entities joined), but the order is tx order, which the map doesn't hold (§1). Gain: one query per request, unmeasured. Not proposed.
- **What `ctx` gives the screen:** the agent's own state as diffs. Agent-loop §5's `(seon.screen/show {:since t})` compares the agent's own state forms with the previous screen's by equality. The patch is already that diff for the record's part (the task, its runs, its messages). Nothing to build until §5 is.
- **Idea 2: an agent changes its screen forms or a view and sees it at once.**
  - **Screen forms: yes, with no new mechanism.** Once they are data on the agent's record (`:seon.screen/forms`, design r2 §1; today they are the var `seon.screen/forms`), `(swap! ctx assoc :seon.screen/forms [...])` is a validated write on the branch. The next request evaluates them at once, in the agent's own process. Root's page reads the agent's branch (`:branches`, `datoms-diff`, touched components), so the new screen evals appear on the page at the next commit.
  - **A view (a render function): not through `ctx`.** A render is code. Root renders the page, and root can't call a function defined in an agent's process. Today an override reaches the page when the branch merges. Making it immediate means either root renders that agent's page with the branch's definitions (a fork of the agent renders it, which design r2 rejected so an agent can't hide a red proof), or the page shows an agent-drawn pane beside root's view, marked as the agent's. That is an owner question (5), and `ctx` doesn't decide it.

## 5. Compared with what exists: what `ctx` would replace or simplify

| Today (an agent's function) | With `ctx` |
|---|---|
| `my.task/current` (the task as data) | `(get-in @ctx [..run.. :seon.run/task])`, or `(:task @ctx)` with aliases (§6) |
| `my.task/update!` (only `:plan`, `:instructions`, through `clojure.core/update`) | `(swap! ctx update-in [:task :seon.task/plan] str "...")`, any attribute, validated |
| `my.agent/whoami` | `(select-keys @ctx [:seon.agent/key :seon.agent/id :seon.agent/branch])` |
| `my.turn/evals`, an eval's record by `my.db/pull` | `(get-in @ctx [:evals "e12"])` (the live value stays `result/e12` and `my.agent/$`) |
| `my.db/transact!` for its own record (a note, a message to itself) | `swap!`, checked by the schemas before it is written |
| "remove this from my context": no way today | `dissoc` (owner question 2) |

What stays: `my.db/q`, `pull`, `transact!` (any query or write outside the tree), `my.task/fail!` (it also stops the turn loop: an effect, not a fact), `my.task/story` and `status` (computed renders), `my.agent/messages` (the inbox is before facts), `$`, `my.branch/*` and every root view.

**The instructions get shorter.** The help page's "your record" lines become: "`@ctx` is your record: your task, runs, turns, messages and evals, as a map, kept current as you work. Change it with `swap!` like any atom: an invalid change is refused with why, and a valid one is written to your branch. `(dissoc ...)` removes a part from your context." Every model knows `@`, `swap!`, `assoc-in`, `update-in` and `dissoc`. Today the agent learns five `my.*` functions and their limits. Whether this moves turns to green is unmeasured: the dogfood run of idea 5 is the test.

**Code:** `ctx` is about 150 src lines (pattern 20, keyed 20, patch 50, diff → tx 45, validator and install 15). It deletes nothing for now (section 8.4). It is a net addition, judged by correctness and work done (2026-10-25 ruling), with one mechanism: the patch serves the read, the write's held check and a resume's catch-up.

## 6. The spec

**Namespace and names.** `seon.ctx` in the agent's process. The agent's namespace refers `ctx` (the atom).
- `(seon.ctx/pattern schema-key)`: the pull pattern, from `:seon.render/units`, `:parts`, `:keys` and the identity.
- `(seon.ctx/tree db eid)`: the keyed map.
- `(seon.ctx/patch m db tx-data)`: the map after a transaction.
- `(seon.ctx/tx old new)`: `{:tx :invalid :paths}`.
- `load!` at init and at a resume.
- The validator and the two watches (`seon.agent/state` → patch; `ctx` → install the db's form).

**Schema.** `:seon.ctx/tree` is derived from the pattern: `[:map [:seon.agent/key ..] [:seon.eval/_agent [:map-of :seon.eval/id :seon.db/eval-fact]] [:seon.run/_agent [:map-of :seon.run/id ...]] ...]`. It is validated per changed entity, never whole (whole is O(session)). If aliases are ruled (owner question 1), they are pull's own `:as`, measured working: `(d/pull db [[:seon.agent/key :as :key] [:seon.eval/_agent :as :evals]] me)` answered `(:key :evals)`. They would be declared once, where the units are, and the diff maps them back.

**Change to existing code:** `seon.agent/transact!` puts its report's `:tx-data` on the state beside `:db` (one key). `merge-main!`'s move uses `datoms-diff`.

**Costs (each a cost proof):**

| Operation | Work | Measured |
|---|---|---|
| build (init, resume) | O(session): one pull | 6.4K ticks an eval; 19.4M at 3,041 |
| patch per transaction | O(datoms) + a pull of each joining entity | 8.0-8.3K for an eval, flat from 115 to 3,041 |
| catch-up after `merge-main!` | O(Δ log N) diff + O(Δ) patch | diff 158-218K for 5 datoms |
| `swap!` write | O(change) + O(members) of each changed collection; one `d/with` and one `transact!` | 88-91K a plan change; drop one of 3,041 evals 544K (118K of it the diff) |
| schema read | once, and again on a schema datom | 175K a read |

**Hazards, and how each closes:**
1. **A `swap!` whose function writes the db** (`(swap! ctx (fn [c] (my.db/transact! ..) ..))`): the write moves the db, the hook moves `ctx`, the CAS fails, and Clojure runs the function again, writing twice. This is Clojure's own rule ("f may be called multiple times, and thus should be free of side effects"), stated in the help line. Not guarded.
2. **A write inside the validator re-entering the atom:** `*writing*` keeps the state hook out, and the db's form is installed after the CAS by a watch, never inside it (measured: no double write, the atom equals the db).
3. **A stale atom:**
   - Every path that moves the db patches it (the hook), and a refused write changes nothing (validator).
   - A silent transformation is caught by the held check, which must cover every changed path (the lab's proofs bug, §3).
   - The proof is a property test: after n random transactions and writes, `@ctx` equals `(tree db me)`.
4. **An eval in flight:** the process is one thread, and the hook runs inside `transact!`, so `@ctx` is current at every form boundary. A message delivered mid-eval lands in the inbox, not in the db, so it isn't in `ctx` until a turn takes it (its fact on the branch).
5. **Other processes:** root and the page never read `ctx`, only the db, so nobody else can see it stale. Another agent's view of this agent is its own pull.
6. **History:** `ctx` is the current value only. Earlier commits are `d/as-of` or the commit's db, as now. A retraction through `ctx` keeps its datoms in history.
7. **A schema transaction:** it touches every read (`:all`), so `ctx` is rebuilt: O(session), rare.
8. **A large collection's diff:** O(members) at about 39 ticks each (§3). If sessions grow past about 10^4 evals, a removal names its path (`(seon.ctx/dissoc! path)`) instead, which is still just a `swap!`.
9. **Memory:** the map's values are the db's data values. The extra footprint is unmeasured: measure it on a parked agent (agent-loop §2's setup) before building.

**What it deletes:** nothing for now (owner, revision 2: keep both; section 8.4). The help page gains a section on `ctx`, beside the `my.*` functions.

**Build order, if approved** (after agent-loop part 1, which fixes the agent key and the session):
1. Read only: the hook, `@ctx`, `transact!`'s `:tx-data`. Proofs: the property test above; a cost proof that the patch is constant in the session's size; init and resume build it.
2. The write: the validator and the held check. Proofs: each table row in §3 (refused writes leave the db unchanged); a cost proof per write.
3. The help page; then a dogfood run (idea 5) measuring turns to green against today's functions.

## 7. For the owner
1. **The map's keys:** pull's attribute names (`:seon.eval/_agent`, `:seon.run/_agent`), or short aliases by pull's own `:as` (`:evals`, `:runs`, `:task`), declared once with the units. *Recommended: aliases*, because they are what makes the help line short. With agent-loop's one agent per task, the task could sit at the top (`(:task @ctx)`) rather than under the run.
2. **Removing a context chunk** = a retraction on the agent's branch (history keeps it; the request at an earlier commit still has it; the prefix cache breaks from there), or a new "hidden" fact with the record intact. *Recommended: the retraction*: it is what `dissoc` means, adds no attribute, and the record survives in history.
3. **What an agent may write through `ctx`:** everything `transact!` accepts, refused only by the schemas (*recommended*, no guard), or observations (eval records, turns, root's run fields) read-only.
4. **Whether to build it now.** It moves no number until the dogfood run measures it. *Recommended:* build step 1 (read only) after agent-loop part 1, then measure with idea 5 before step 2.
5. **Idea 2 for views (code, not data):** keep "root renders every view" (an override reaches the page at merge), or let an agent draw a marked pane of its own page at once. `ctx` makes screen forms immediate already (§4).

## 8. Revision 2: the owner's reply, answered

The owner's rulings (2026-10-25, on revision 1). Root files them in `docs/decisions.md`:
- **Short keys:** generate them.
- **Removing a context chunk:** a retraction, not a hidden fact ("we have history for any references to the past").
- **What may be written:** "everything is writable for now. An observation is of course read only."
- **Reads:** "definitely don't regenerate anything for a read".
- **Views:** "not necessarily root renders. Just make it so every agent is the same for now." An agent's context mimics its environment: its branch, its commit, and whatever else is needed.
- **The `my.*` functions:** keep them ("both work so don't delete them for now"). The plan's references pull in the functions and instructions an experiment uses.
- **The dynamic forms** "should always reflect the latest state at the time they eval like every other eval".

### 8.1 Vectors instead of sets?
What a cardinality-many attribute holds, measured (`exp10`). Members `c`, `a`, `b` were written in three transactions, then `a` again:

| Read | Answer | Cost |
|---|---|---|
| `d/pull` | `[a b c]`: a vector in index (value) order | 4,906 ticks |
| `d/datoms :eavt` | `[a b c]` | |
| the datoms sorted by their transaction | `[c a b]`: the order written | 2,682 ticks |

- Writing `a` again added nothing: it kept its first transaction.
- So a reliable mapping to a vector exists, but only one kind: **insertion order, no duplicates, no reordering.** Each member's position is the transaction that first added it; members added in one transaction tie and fall back to value order. A writer can't move a member: re-adding is a no-op, and retract then add puts it last.
- A real vector (an order the writer controls, duplicates allowed) can't be a cardinality-many attribute. Datahike and Datomic index cardinality many as a set of datoms. Datomic's own advice is the same: one value, or component entities with a position attribute.

The options, simplest first:
1. **Keep `[:set T]` for what is a set** (a task's proofs, needs, references: each is a set in meaning), and have `ctx` hold a set: one line, done in the lab. *Recommended.*
2. **A schema `[:vector T]` means one value** (`{:seon.db/data true}`, which exists): exact vector semantics, and a write replaces the whole vector, O(its size).
   - Today `seon.schema.datahike` refuses `[:vector T]` with `::ordered-collection` ("mark an ordered value {:seon.db/data true}").
   - The change is to infer that mark instead of asking for it: about 3 lines in `storage`, so a vector in a schema just works. *Recommended with 1.*
3. `[:vector T]` as cardinality many in insertion order (above): reliable as described, but a vector a writer can't reorder is not a vector. Not recommended.

The large ordered collections (evals, turns, messages) are entities, not values. Their order is the transaction that wrote each, which `seon.context/records` already uses.

### 8.2 Why "tx-data", and the agent's state in the db
Revision 1 said `transact!` should "put the report's `:tx-data` on the state". That is the transaction's **datoms** (tx-data), not its tx-metadata, held for one step in the agent process's in-memory `seon.agent/state`, so the hook reads the datoms the write made (8K ticks) instead of diffing two db values (158-218K). Nothing new goes in the db.

The owner's point stands, and the design meets it:
- `ctx` holds nothing the db doesn't. It is a function of the branch's db at its basis: `(= (bare @ctx) (tree db me))`, true after every experiment.
- Every change to `ctx` is a transaction made inside an eval (the `swap!` form is the eval's code).
- So replaying the evals (agent-loop §4) replays the writes, and the `ctx` built at the end of a replay is the same map.
- What is not in the db (the inbox before a turn takes it, live values behind handles) is not in `ctx` either.

### 8.3 Every agent the same, and the context mimics the environment
- **The map carries its environment:** `:t` (the db's max-tx, the basis it was read at), `:branch`, `:commit` (the last commit id).
  - Measured on `a1`: `{:t 536879653 :branch :main/ctx-lab3 :commit "6add7c5e-…"}`.
  - They are read-only: a write of them is refused, and they are left out of the diff.
  - More of the environment joins here as needed (main's t at the last merge, the turn's basis), each a fact or a value the process holds, never a computation on a read.
- **Root is an agent like the others.** Root's `ctx` is the same build over main, patched from the writer's reports, and nothing in `seon.ctx` names root.
- **Views: every agent the same, for now.** Whoever renders, renders from the db through `with-reads`, as one mechanism. Today that is root's render proc for every page, the same code for every agent. Whether an agent may draw its own page (idea 2, owner question 5) stays open.
- **What screen forms are:** the forms the system evaluates in the agent's REPL just before each model request: today `seon.screen/forms`, a var holding `["(seon.screen/show)" "(my.task/status)" "(my.branch/changes)"]`.
  - Each runs as an ordinary eval, recorded with `:seon.eval/by "screen"` at the db's state at that moment.
  - Its response is sent as the request's last messages and replaced by the next request's, so they never repeat in the conversation.
  - They are the "dynamic section", and they already reflect the latest state at the time they evaluate, like every other eval (the ruling).
  - An agent changes them by redefining the var today. With `ctx`, `(swap! ctx assoc :screen-forms [...])` once they are a fact on the agent (`:seon.screen/forms`, design r2 §1; a new attribute, owner's gate).
  - The page shows the new screen evals at the agent's next commit, because they are records on its branch.

### 8.4 Keep both, and let the plan's references pick
Nothing is deleted (§5 and §6 corrected here):
- `ctx` sits beside `my.task/current`, `update!` and `whoami`.
- An experiment's instructions are what its task's plan cites. The reference parser already pulls a cited var's source and doc into the task story (`seon.context/run-state`'s write set), so a plan citing `seon.ctx/ctx` and the help section on it gives that run the `ctx` way, and a plan citing `my.task/update!` gives the other.
- One task, two plans, compared on turns to green. No mechanism is needed beyond the help page having a section on each.
- The session can do anything either way: both are plain functions in a plain REPL.

### 8.5 Short keys, generated (`exp9`)
The rule, from the schemas alone:
- a reverse ref `:ns.thing/_attr` is the plural of its thing (`:seon.eval/_agent` → `:evals`, `:seon.run/_agent` → `:runs`, `:seon.turn/_run` → `:turns`, `:seon.message/_to` → `:messages`, `:seon.agent/_root` → `:agents`);
- a forward unit is its attribute's name (`:seon.run/task` → `:task`, `:seon.task/proofs` → `:proofs`);
- **attributes keep their qualified names** (`:seon.task/plan`).

Over every entity schema with units: **no collisions**. Shortening attributes too would collide (`:turns` against the run's own `:seon.run/turns`, `:task` against `:seon.run/task`), so attributes stay as the schema names them, which is also how they validate. A collision in a later schema is invalid at registration (`my.schema/register!`'s check), explained. Pull renames with its own `:as` (measured), and the diff maps each short key back.

The agent's paths become:
```clojure
(get-in @ctx [:runs "lab/ctx#1" :task :seon.task/plan])
(swap! ctx update :evals dissoc "e12")
```
With agent-loop's one agent per task, `(:task @ctx)` at the top is one unit more (owner question 1).

### 8.6 Observations are read-only, and a removal is a retraction
These two rulings meet at one point: removing an eval from the context retracts an observation. My reading, for the owner to confirm:
- **an observation's content is read-only** (an eval's record, a turn, a run's measured fields, a taken message);
- **an observation may be retracted whole** (`dissoc`, the removal), with history keeping it.
- A write that changes an observation's attribute is refused: `ctx: … is an observation, read-only`.

How an observation is known: today no schema says so. The merge keeps a code set of what it carries (`seon.merge/carried-platform-attrs`), and `seon.facts.edn` says "Observations" in a comment. Proposed: one schema property, `{:seon.db/observed true}`, on the entity schemas of evals, turns, ends, proof results and messages, and on the attributes root observes of runs and agents. It is a new schema property (owner's gate). It names what the comments already say, and the write check reads it once per changed entity.

### 8.7 Nothing regenerates on a read
- `@ctx` is a deref. Every change to the map is made by a write (the patch), never by a read.
- The full build happens only at process start and at a resume, and both are writes of the process.
- A schema transaction (revision 1 said "rebuilt") needs only the units whose pattern changed pulled again, which is the patch's join path. So that is no rebuild either.

### 8.8 The hazards, addressed

| Hazard | How it closes | Evidence |
|---|---|---|
| A `swap!` whose function writes the db | **It was a livelock, not a double write:** the function ran 4,111 times, one db write each, until the eval's 20G-tick budget stopped it; each write moved `ctx`, Clojure's CAS failed, and it retried. **Fix: the basis in the map.** The validator refuses a value whose `:t` isn't the db's now (an optimistic check, as `:db/cas` is). The validator throws, so nothing retries: the function runs once, its own write stands like any eval's, and the swap's change isn't written | `exp11`: 4,111 calls, `:seon.repl/ticks-spent`. `exp12`: 1 call, refused "ctx: the db moved while your swap! function ran (it read t …647, the db is at …652): a function given to swap! must not write; write after the swap!", ordinary write applied, atom equal to the db |
| A write re-entering the atom from its validator | `*writing*` keeps the hook out; the db's form is installed after the CAS by a watch | no double write in `exp5`, `exp6`, `exp12` |
| A stale atom | Every move of the db patches it. A refused write changes nothing. A transformed write is refused by the held check, over every path the structural diff found. `:t` makes staleness a fact anyone can check: `(= (:t @ctx) (:max-tx (my.db/db)))`. Proof: a property test, n random transactions and writes, then `@ctx` equals a fresh build | equal after every experiment; the `#{t1 t5}` divergence of revision 1 found and closed |
| An eval in flight | One thread; the hook runs inside `transact!`, so `@ctx` is current at every form | — |
| Another process reading it | No process reads another's atom; every agent is the same: each reads the db | — |
| History | `ctx` is the present; the past is `d/as-of` or the commit's db; a retraction keeps its datoms | `s9`'s 2 datoms in `d/history` |
| A large collection's diff | O(members) at ~39 ticks each (118K at 3,041). The cause is that a persistent map has no structural diff, so the fix belongs in Jolt: a `jolt/` primitive diffing two hash maps by their shared nodes (`identical?` subtrees), O(change × log n). It is our fork, so the fix goes where the cause is. Not needed until a session is about 10× larger: a removal is 544K now, beside a write's ~90K and a model call | `exp6` |
| Memory | Unmeasured. Measure a parked agent (agent-loop §2) with and without, before building | — |
| A refusal message | Writing `:t` itself reads as "the db moved" (both checks see a different `:t`). The read-only check comes first in the build, so it says "read-only" | `exp12` |

### 8.9 For the owner, what remains
1. **Observations:** content read-only and whole retraction allowed (my reading of the two rulings), marked by a schema property `{:seon.db/observed true}` (new: owner's gate).
2. **Vectors:** keep sets for sets, and infer `{:seon.db/data true}` for a `[:vector T]` in a schema (about 3 lines in `seon.schema.datahike`)? *Recommended.*
3. **Screen forms as a fact on the agent** (`:seon.screen/forms`, new attribute), so `ctx` writes them.
4. **The task at the top** (`(:task @ctx)`) once agent-loop's one agent per task lands.
5. **Idea 2 for views** (an agent drawing its own page), still open.

## 9. Revision 3: who made a fact is a query; nothing is refused at write

The owner's ruling (2026-10-25, on revision 2):
- Agents may cheat in theory: they are instructed not to, and approval happens regardless.
- The rule: "everything writable, every change shown on the page with history; refused only when the fact belongs to another producer".
- `{:seon.db/observed true}` "seems like a hack": can whether something was observed be known by a query?

**Yes: provenance is already in the db.** Every transaction Seon makes carries `:seon.tx/branch` in its tx-meta (`seon.db/tx-meta`), so the branch that made a datom is its transaction's `:seon.tx/branch`. Measured (`exp13`, lab agent `a1` on `:main/ctx-lab4`):
- the agent entity's `:seon.agent/key` and `:id`, the config, the program and the schema: `:main`, inherited at the fork;
- root's evals carried in the fork: `:main`;
- the agent's own evals (e5-e7) and its message: `:main/ctx-lab4`.

For one entity this is `(d/q '[:find ?a ?b :in $ ?e :where [?e ?a _ ?tx] [?tx :seon.tx/branch ?b]] db e)`, at 34.7K ticks. This is Datomic's reified transactions, the field's known way, and it replaces the proposed schema property: no mark on any schema.

**Two gaps, each fixed where the fact is made** (both one key in the one tx-meta function, so the owner's gate):
1. **A catch-up hides the source.** `merge-main!` writes main's changes in a merge transaction tagged with the agent's branch (`seon.agent/merge-main!`, its `(pdb/tx-meta (:branch @state))`). After a catch-up, main's facts read as the agent's. The fix: the merge transaction's tx-meta also names the branch it merged (its commit graph already has main as a parent, but per datom the tx-meta is what a query reads).
2. **Which eval made a change.** An eval's own record and the writes its code made are separate transactions. For the page to show "e43 `(swap! ctx …)` changed the plan" and for a replay to be checkable, the tx-meta names the eval that was running (its id): `:seon.tx/eval`.

**What "belongs to another producer" refuses, then: nothing on the agent's branch.** An agent writes only its own branch. Main's facts on it (the task as root loaded it, config, root's evals) are its copies. Refusing those would refuse what is meant to be improved (`my.task/update!` edits main's plan on the branch). Another producer's facts that anyone acts on live where their producer writes them: the reconciler's runs, the test service's results and the judge's verdicts, all on main, which an agent can't write. The approval is the merge: only `seon.merge/carried-platform-attrs` (plan, instructions) and the program cross to main, through its review. So:
- **No write check** for producers. The schemas' validation and the basis check (§8.8) stay, because they are about a well-formed write, not about who.
- **The page shows provenance**: each changed fact with the branch and the eval that made it, from the tx-meta, and a removed member as removed, from history. A write over a fact another branch made (main's, another agent's) can be shown as such, for the user to see and approve.
- **The help page says it:** "your record is yours to change. Every change is shown with the eval that made it. Don't write facts to pass a check (a proof's status, a run's outcome): root reads its own."

The proposal of `{:seon.db/observed true}` (§8.6, §8.9 question 1) is withdrawn.

## 10. The session sees what differs from main, and marks what it proposes to merge

The owner (2026-10-25): an agent's session diffs everything, so the agent sees the data and the functions (one thing: functions are forms, forms are data) that differ from main. Not all of main's data needs to be on the agent's branch for it to work. The agent marks what it is attempting to merge.

**Main's data costs nothing to have.** A branch is a Datahike branch of main: it shares main's whole tree from the fork on (`d/branch!`, 1.4-3.7 ms, a pointer). Nothing is copied, and nothing of main's needs to be in `ctx`: the agent reads it with `pull` and `q` when it needs it. `ctx` holds the agent's own record and **the diff**, never main.

**The diff is the one the merge already computes.** `seon.merge/delta-three-way` (base, ours, theirs) answers each side's changes since the merge base. It reads only where the sides differ (task `seon.merge/program-cost-follows-what-changed`). `my.branch/changes` (`seon.program/divergence`) is the same diff as an agent reads it today.

**Things are named by themselves, never by a printed string** (owner, 2026-10-25: "we shouldn't be using strings like :var … Use a symbol to something if you need to reference a var"):
- a var is its symbol, `app.x/f`;
- a namespace is its symbol, `app.x`;
- a schema entry is its keyword, `:seon.task/plan`;
- anything else is its entity, by Datahike's lookup ref: a method `[:method/key ..]`, an impl `[:impl/key ..]`, a task `[:seon.task/id "lab/ctx"]`, a message `[:seon.message/id ..]`.

The merge's internal tagged tuples (`:seon.merge/program-key`, `[:var sym]`) stay inside `seon.merge`; nothing an agent reads or writes uses them. (`:seon.run/changed` stores them printed as strings today, `"[:var app.x/f]"`, because a card-many attribute can't hold a vector. That is a follow-up task: a set of refs, as below.)

So `(:changes @ctx)` is `{app.x/f {:base "(defn f ..)" :ours "(defn f ..)"}, :seon.task/plan {..}, [:seon.task/id "lab/ctx"] {..}}`: each changed var's source before and after, each changed entity's changed attributes before and after.
- It is built once at init (O(changed)).
- It is kept by the same hook: each transaction's datoms map to the things they touch, O(datoms).
- It is rebuilt only when the merge base moves (`merge-main!`).
- A read regenerates nothing.

**The mark is the merge's own input.** `seon.merge/merge-tx` already carries exactly a list of keys (`ks`). Today the octopus merge passes it everything the branch changed (`:seon.run/changed`). The mark is that list chosen by the agent:
```clojure
(:changes @ctx)                                   ; what differs from main, with base and ours
(swap! ctx update :merge conj 'app.x/f)           ; propose the var
(swap! ctx update :merge conj [:seon.task/id "lab/ctx"])   ; propose the task's changed content
(swap! ctx update :merge disj 'app.x/g)           ; an experiment: stays on the branch, kept
```
- **Stored as refs to the entities themselves:** `:seon.task/merge [:set :seon.db/ref]` (new attribute, owner's gate). The var's entity is `[:var/name app.x/f]` and the schema entry's is `[:schema/key :seon.task/plan]`, each a content id, so a ref names the same entity on every branch.
- **Shown and written in `ctx` by the names above:** the patch shows a ref by its entity's identity, and the write maps a symbol, keyword or lookup ref back to the ref.
- At the run's end root reads the mark and merges marked ∩ changed: nothing unmarked, nothing unchanged.

**Safe, checked at the mark as an ordinary schema refusal:**
1. **A mark names a change:** a key not in `:changes` is invalid, explained ("app.x/h is the same as main's").
2. **A mark is closed under what it calls:** a marked form whose compiler-resolved references (`:form/refs`, one AVET seek per var, never regex) include a changed, unmarked var is invalid, explained ("app.x/f calls app.x/g, changed on this branch and not marked: mark it or put it back"). Without this, main would get f calling g's old version.
3. **What can't cross is said, not silently dropped:** a datum of a platform record (`seon.merge/platform-record?`: runs, evals, root's measurements) is invalid to mark ("written where it happened; never merged"). Data marks are task content (`carried-platform-attrs`) and data entities the merge's `data-attrs` names.
4. **The rest is the merge gate as today:**
   - conflicts with what main changed meanwhile (three-way, kept as unmerged entries);
   - `bin/prove` on the merged tree;
   - the review reads only the marked keys' diff, so the reviewer reads what is proposed, not the experiment around it.

**What it replaces:**
- the merge carrying every change of a branch;
- the help line "Anything listed that the task does not need, put back" (`my.branch/changes`' docstring), which makes an agent undo its experiments by hand before it can finish.

Unmarked work stays on the branch as a record, and the page shows changes and marks side by side.

**Cost:**
- a mark: one validation, O(the marked form's refs);
- the merge: O(marked keys) instead of O(changed);
- the diff: the merge's own, already O(changed).

Not measured here: the diff's maintenance per transaction (keys from datoms), which is a cost proof when it is built.

## 11. Every agent has its own environment: a message is made in one and delivered into the other

**Superseded (root, 2026-10-25).** Messages are main-first, with `:seon.run/taken`, and the recipient holds the taken copy on its branch: agent-loop design §3a (16066cc0b on jolt/agent-loop) measured 4.96 ms a delivery against at least 16.79 ms for this design, since the reconciler reads only main and this one would read every branch's every commit. Both are safe against lost wakes. What follows is kept as the record of the alternative, not as the plan.

The owner (2026-10-25): "In general I'm down with everything you're proposing. I just want to make sure it's done in a way that allows agents to have their own envs. So messages can get merged but are delivered into the other env."

An agent's environment is its branch (its db, its `ctx`, its process). Main is root's environment, not a hub every message passes through. **A message is a fact the sender makes in its own environment, delivered by merging that one fact into the recipient's.** That is the one mechanism of section 10 (`merge-tx` over a list of keys) applied to data between two branches, so there is no messaging store and no second path.

1. **Send:** A writes the message on its own branch: `{:seon.message/id .. :from A :to B :body ..}`, a content id (`:seon.message/id` is `{:seon.db/identity true :seon.db/content-id true}`), so it names one entity on every branch it reaches. The answer is `{:id ..}` once it is applied on A's branch. A's `ctx` shows it under its sent messages.
2. **Deliver:** root is the router (the one server), told by the send's frame. The recipient's process writes its own branch (agents own their branch), so delivery is B merging A's message into B's branch: `merge-tx` onto B's head, from A's head, for that message's keys only. Nothing else of A's crosses.
   - **B live** (parked or busy): the frame reaches B's process, which merges it at once. B's hook patches B's `ctx` from those datoms, and the turn loop sees the message in its own record. Today's inbox becomes "messages on my branch not yet taken", a query.
   - **B with no process:** the message waits where it was made, on A's branch. Root's index (from each branch's commit notice, the datoms of new message facts, O(datoms)) counts messages to B not on B's branch. That count is agent-loop's wake rule, keyed by branch instead of main. The resume merges them in at init.
3. **Safe by construction:**
   - The content id makes delivery idempotent: merging the same message twice upserts identical datoms, which is a no-op, so a lost frame is only re-delivered, never doubled. No dedupe code.
   - Only the message's own keys cross: A's experiments, records and code stay in A's environment.
   - Order per recipient is the tx order on B's branch, so B reads in the order things were delivered.
   - A message can't be lost: it is a fact in A's environment until B's branch has it, and the count says which.
4. **Merged to main only like anything else:** marked (section 10), or as the user's and root's own messages, which they make in main because main is their environment. So "messages can get merged" means the merge carries a marked message to main as a record (the user's timeline, a review), while "delivered into the other env" is the branch-to-branch merge above.
5. **The page:** an agent's page renders its own environment, so it shows the messages on its branch, each with where it came from (`:seon.tx/branch` of its first transaction, section 9).

**What changes elsewhere:** agent-loop's design §3 ("one send") writes the fact on main first and delivers to the process. Under this ruling the fact is made on the sender's branch and merged into the recipient's; main holds only the user's, root's and merged messages. Its wake count (§2) reads per-branch message facts instead of main's. The rest of §3 holds: one function, one op, typed refusal, replies as the same send. Root decides how that design takes it.

**Cost, estimated, not measured:**
- a send: one transaction on A's branch (about 5 datoms) and one frame;
- a delivery: a `merge-tx` of one entity's datoms onto B (O(those datoms)) and one commit on B;
- root's index: O(message datoms) per commit notice.

A cost proof when it is built: a delivery's cost doesn't grow with either branch's size.
