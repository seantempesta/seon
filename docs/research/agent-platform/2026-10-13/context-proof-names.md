---
title: "Proof-selection names: what each one is, who reads it, why it exists"
type: research
date: 2026-10-13
scope: read-only map; no source edited, no MCP eval, no bin/seon
---

# Proof-selection names

Owner question: why are there so many names (tags, obligations, proofs, product)? Goal: one set of deftests per task.
This note maps every name, its readers and its introducing commit. Facts are from reading code and `git log -S`
at HEAD fdc34a9ba. "inferred" marks a conclusion drawn from code reading without a run.
Commit "earliest" = first commit in `git log -S'<name>' --reverse`, which matches the string, not necessarily the definition.

## (A) READ THIS, in order

1. `docs/seon/plan.md` section 0, owner decision (3): one stored set `:seon.task/proofs`; `:seon/tasks` tags feed it
   only at ingestion; `:seon.task/product` deleted; one reader replaces `seon.task/obligations`.
2. `docs/seon/schedule/cards/TASK-CHAIN.md` section 1 (Need table rows "Judge work" and "Freeze selected defaults"),
   section 2 "Select once", section 4 (templates), section 5 A1 (`one-selector-serves-every-proof-consumer`), and the
   phase-3 row `seon.task/chain-obligations` (the open work that deletes successor inference).
3. `seon.task/obligations` (src/seon/task.clj): the one selector today, both arities. Read the `at` closure in full.
4. `seon.task/chain!` and `seon.task/chain-copy` (src/seon/task.clj): what a clone copies.
5. `seon.task/assignment-read` and `seon.task/assignment`: the `:prove` branch selects product, `:implement` proofs.
6. `seon.test.runner/single-tags`, `seon.test.runner/task-input`, `seon.test.runner/inputs-of` (task-inputs), and
   `seon.test.runner/designed-red?`.
7. `seon.test/task` and `*task*` (src/seon/test.clj): the test-facing reader of the input.
8. `test/seon/step_test.clj`: the five default deftests and `judged`, `worked-results`, `designed-product?`.
9. `resources/seon/tasks/seon.step.edn`: the five templates (no `:seon.task/needs`, no proofs, no product).
10. `seon.cluster.source/tested-task-basis`, `seon.cluster.source/results-verdict`, `seon.cluster.source/merge-verdict`.
11. `seon.context/proofs` and `seon.context/step-observation`; `seon.turn/task-obligations`, `task-proofs`, `step-tests`.
12. `seon.error/proof`; `seon.cluster.agent/basis-valid?`.
13. Ingestion of tags: `seon.fn/link`-style `:seon/tasks` handling (src/seon/fn.clj, the deftest refusal near its
    `:seon/tasks` authored check) and `resources/seon/schemas/seon.task.edn` (the `:seon.task/proofs` and
    `:seon.task/product` schemas).

Commits to read first: `63c54b9e0` and `e166b9ac1` (plan and hybrid, round 5), `941c494ec` (A1 selector),
`c5eb7b814` (`:seon.test/task` input), `a98fd86b9` (templates and `chain!`), `9d25711e7` (successor inference),
`89bf15cf3` (tags on the code), `1de64b45b` (`:seon.task/*` schema).

## (B) Name table

Columns: name, definition, readers (keyword and symbol uses), introduced by (earliest string match, and its stated reason).

| Name | Definition | Readers | Introduced by |
|---|---|---|---|
| `:seon/tasks` (metadata on a defn, deftest, ns) | Authored tag: the set of task symbols the definition or test serves. | Read at ingestion by `seon.fn` (authored check near `:seon/tasks` link) and `seon.fn.analyzer` (metadata capture). Used on ~60 src and test files (`rg -c`). | `89bf15cf3` "task information lives on the code it is about; deftests name their task; the task file is the backstop". |
| `:seon.task/tasks` (stored attribute on fn, test, ns rows) | The ingested copy of `:seon/tasks`. | `seon.task/obligations` (`tagged`), `seon.task/task-edges` (`tagged`), `seon.task/task-findings`, `seon.task/candidate-dangling`, `seon.task/open-tasks`, `seon.task/task-edges`, `seon.error/proof` (via obligations), tests (test/seon/task_ingestion_test.clj, merge_test.clj). | `1de64b45b` "task schema (7.9): `:seon.task/*` attributes ... task + link members on fn/test/ns rows" (schema placement). |
| `:seon.task/proofs` (declaration member; stored when authored or copied) | Explicit set of deftest symbols on a task, "the copied part of obligations" (TASK-CHAIN section 1). | Schema `resources/seon/schemas/seon.task.edn`; `seon.task/obligations` (set union); `seon.task/chain-copy` (writes it); `seon.task/assignment-read` (`:implement` branch), `seon.task/assignment`, `seon.task/declaration`/`create!`; `seon.cluster.source/results-verdict`, `tested-task-basis`; `seon.turn/task-proofs`; `seon.context/proofs`. | Earliest string match `31cf5360f` (writer delta; not its reason). The reason is stated in TASK-CHAIN section 1 "Freeze selected defaults without copying test bodies". Inferred: added with `chain-copy` in `a98fd86b9`. |
| `:seon.task/product` (declaration member; stored) | The deftests a Prove step declares as its behavior tests, which Implement makes green. | `seon.task/obligations` (`declared`, `product`); `seon.task/assignment-read` (`:prove` branch); `seon.task/effective` (content key); `seon.cluster.source/results-verdict`, `merge-verdict`, `tested-task-basis`; `seon.context/step-observation`; `seon.turn/step-tests`; `seon.test/` designed-red judging; `my.task/refine!` (writes it, `src/my/task.clj` lines ~49-73); `test/seon/step_test.clj` prose only. | `e166b9ac1` "hybrid (round 5): step semantics ... `:seon.task/step` as data, one obligation selector". Stated reason in the Prove template instructions: product is what Implement makes green. |
| `seon.task/obligations` (2-arity `[database sym]`, 4-arity `[main candidate base sym]`) | The step kind, proofs and product of a task. Proofs = tagged(sym) ∪ copied proofs ∪ (Implement: tagged(subject) ∪ declared product); product = declared product ∪ tagged(successor). 4-arity = set formula over main, candidate, base. | `seon.task/assigned-obligations`, `seon.task/assignment-read`, `seon.task/assignment`, `seon.task/task-edges` (doc only), `seon.turn/task-obligations`, `seon.context/proofs`, `seon.cluster.agent/basis-valid?`, `seon.cluster.source/tested-task-basis`, `seon.test.runner/task-input`, `seon.error/proof` (via requiring-resolve), `seon.render.ns/task-ai` (displays a read), `test/seon/ref_test.clj`, `test/seon/task_test.clj` (tests). | Name in plan `63c54b9e0` (Opus round 5 "one obligation selector"); 4-arity added in `941c494ec` "One proof selector over main, candidate and merge base (TASK-CHAIN A1)". |
| `seon.task/assigned-obligations` | `obligations` over main head, branch and its base-ref commit (merge base); falls back to `obligations` on the branch. | `seon.context/proofs`, `seon.context/step-observation`, `seon.turn/task-obligations`, `seon.task` itself. | `941c494ec` (A1). Reason: the assignment is held to main + its own additions, not its current tags. |
| goal-owner use in obligations | `seon.task/goal-owner` of an Implement step (the subject declaring `:seon.task/intent`). Its tagged tests and its effective product join Implement's proofs. | `seon.task/obligations`, `my.task` (`goal-owner` for refine routing), `seon.task/effective` lineage. | Introduced with `3410f6ca8` "Chain task data (H2): ... so one :running on the subject launches it" (refine routing); the obligations use is in `941c494ec` or later (inferred). |
| Product inference ("tests tagged to a task that needs this one") | Query `[?next :seon.task/needs ?task] [?e :seon.task/tasks ?n]` in `obligations`: tests tagged to the successor count as this task's product. | `seon.task/obligations`; `seon.cluster.agent/basis-valid?` (a Prove successor check); `seon.task/ready` (successor readiness) indirectly. | `9d25711e7` "Select task obligations by step kind and declare step lifecycle facts". TASK-CHAIN section 2 says "Delete successor-tag inference" (not yet deleted). |
| `seon.test.runner/single-tags` | For test symbols with exactly one `:seon.task/tasks` tag, maps each to that task. | `seon.test.runner/inputs-of` only. | `c5eb7b814` "A default deftest judges the task it is given: the `:seon.test/task` test-run input" (TASK-CHAIN chain-obligations item 2). Reason: an inputless template run must still name its one task. |
| `seon.test.runner/task-input` (singular) | `{:seon.task/sym, :seon.task/obligations, :seon.task/effective}` for one task on a database value; refusal if read fails. | `seon.test.runner/inputs-of`; `seon.test/task` value shape (`:seon.test/task-input` schema). | `c5eb7b814`. |
| `seon.test.runner/task-inputs` (plural, arities 2 and 3) | Each test that calls the reader `seon.test/task`, mapped to its `task-input`: the request's `:seon.test/task`, else the single tag. | `seon.test/` (runs and `:seon.test/task-inputs` in the request handle), `seon.test.runner/reach-digests` (folds the input into reach identity via `id/id [digest input]`). | `c5eb7b814`. Reason: a clone runs its copied default against its own declaration; evidence never crosses tasks. |
| `:seon.test/task` (run request key) | The task a run judges (`request :seon.test/task`). | `seon.test/` run (`src/seon/test.clj` lines ~847, 2687, 2800, 2842), `seon.test.runner` (lines ~1986, 2337), `seon.turn` (2 uses), tests `step_test.clj`. | `c5eb7b814` (the clone-run input). |
| `:seon.test/task-input` (custody key) and `*task*` | The same `task-input` value carried in the member's custody so the body can read it. | `seon.test/*task*` binding (test.clj ~345), `seon.test/task`, custody map (test.clj ~287, 363). | `c5eb7b814`. |
| `seon.test/task` (function, `[]`) | What the running member body reads of the task it judges: `*task*`. | Default deftests in `test/seon/step_test.clj` (`judged`), `test/seon/test_support.clj`. | `c5eb7b814` (code); the name is also in doc commit `c898739b8` (research). |
| `seon.test.runner/designed-red?` | A recorded result that is red and has at least one failing report (`red-result?` and `:seon.test.failure/reports`). | `seon.cluster.source/results-verdict` (via designed reds), `test/seon/step_test.clj` `designed-product?`, `test/seon/test_failure_facts_test.clj` (3). | Earliest string `7ddbb2c1e` "P3: one rule judges a step against its fork point in the agent's compartment" (designed-red as one rule). Earlier red-by-design logic: `be32e7501` "Record typed test errors and recognize unimplemented Prove products" (inferred origin of the idea). |
| Step template deftests `seon.step-test/{survey,define,design,prove,implement}-handoff` | One default per template, tagged `:seon/tasks #{seon.step/<stage>}`, judging the one tagged template (inputless run) or a clone (`:seon.test/task`). Each calls `judged`, then `document-gaps`/`sections`, and prove-handoff/implement-handoff read `step`. | `seon.test.runner` (the template's tag), `seon.task/chain-copy` (copies `:seon.task/proofs` = template's obligations), `test/seon/step_test.clj` itself, `seon.render` (help). | `a98fd86b9` "Five step templates and one-write `seon.task/chain!` (TASK-CHAIN phase 6, A3)". Reason: templates are ordinary tasks with defaults; a clone copies the defaults it selects. |
| `worked-results`, `designed-product?` (step_test.clj) | A constant hand-written product result and a predicate over it. | Only `prove-handoff`. | Added with the step templates `a98fd86b9` (inferred). |
| `seon.task/chain!` and `seon.task/chain-copy` | One write of subject + copies + sequential needs; each copy takes the template's `:seon.task/step`, `:seon.task/instructions`, its `obligations`' proofs (only, not product), a `:seon.task/needs` on the previous copy, and a customization. | `my.root` and root tools (inferred), `test/seon/step_test.clj`, `test/seon/task_test.clj`, `test/seon/cluster/db_flow_test.clj`, `test/seon/cluster/write_back_test.clj`. | `a98fd86b9` (TASK-CHAIN A3). Reason: "Construct once" (TASK-CHAIN section 2). |
| `seon.task/assignment-read` | The read an assignment makes: revision, agent id and the task's proofs; refuses `:no-proofs` when the step's set is empty, where `:prove` tests product and other steps test proofs. | `seon.task/assign!`, `test/seon/task_ingestion_test.clj` (2), `test/seon/task_test.clj`, `test/seon/cluster/agent_namespace_test.clj`. | Name predates the chain: earliest string `b7d4582cf` (2026-09-24, unrelated refusal). The `:no-proofs` branch is inferred to date from the step-kind work (`e166b9ac1`). |
| `seon.task/assignment` | The held assignment's `:seon.task/proofs` (`(:seon.task/proofs edges #{})`), not product, for every step. | `seon.cluster.agent` (assignment reads), `seon.context` (inferred). | Pre-chain (inferred). See (G). |
| `seon.context/proofs` | The proofs section of a block: counts, then each proof's status, from `assigned-obligations`' `:seon.task/proofs`. | `seon.context/task-section`, `seon.context/block` render, `seon.bootstrap` help text, `seon.render.ns` task forms. | `88bfd19cb` (2026-10-08, "CS-01b: the block shows proofs and their status"). |
| `seon.context/step-observation` | The step's observation for the results judgment: proofs (+ product for `:prove`) and the tests reaching changed functions. | `seon.turn/step-proved?`, `seon.cluster.source/results-verdict` (through the judged input). | `25973cd04` (One results judgment; step-status previews the merge, slice 4b). |
| `seon.turn/task-obligations`, `task-proofs`, `step-tests` | Three wrappers: obligations of the agent's held task on its branch; its proofs as a set; and, for `:prove`, proofs plus product tests the branch already holds. | `seon.turn/test-step`, `seon.turn/prove-turn`, `seon.turn/run-proofs` (turn.clj ~5186-5290). | `task-proofs` first `09f86c000` (F4a, 2026-10-08, "the proofs run after a pass that changed code"); `task-obligations` and `step-tests` first in `941c494ec`/`d7ccec1b6` (2026-10-13, F22/F23: "a Prove step whose product main already holds"). |
| `seon.error/proof` (defn-, `proof`) | Whether a task's proof holds: obligations' proofs each have a green stored run completed after the producer's current definition. | `seon.error` resolution (`resolved-by :seon.task/proof`); tests `test/seon/error/resolution_test.clj`. | Name `proof` predates (`9a885319f`, unrelated); the task-proof resolution is inferred from `seon.error` history. It calls `obligations` through `requiring-resolve` to avoid a cycle. |
| `seon.cluster.agent/basis-valid?` | Whether a task has admission obligations on main: proofs, or a `:prove` step with a successor. | `seon.cluster.agent` admission of ready leaves. | Inferred from `9d25711e7`. |
| `seon.cluster.source/tested-task-basis` | The selected task's obligations over main, candidate and base, plus the declaration basis of its ancestors. | `seon.cluster.source/prepare-merge!`, `seon.cluster.source/accept-merge!`, `seon.cluster.source/task-basis-refusal`, `seon.merge` (comment), `test/seon/cluster/merge_test.clj` (1). | `dee8a411d` "Specify task-chain acceptance interfaces" (2026-10-13), spec A2. Reason: writer compares the same basis the run was selected by. |
| `seon.cluster.source/results-verdict` | The one judgment of recorded results: proofs members green, product designed-red, red-on-target, dangling references. | `seon.cluster.source/merge-verdict`, `seon.context/step-status`/judged input. | `25973cd04` (slice 4b). |

## (C) Per-step obligations for a chain built by `seon.task/chain!`

Chain: subject `work/fix` (declares `:seon.task/intent`; `:seon.task/product` empty until Prove declares it) with
copies `work/survey-fix`, `work/define-fix`, `work/design-fix`, `work/prove-fix`, `work/implement-fix`, each needing the
previous. Templates `seon.step/*` have no needs, no proofs, no product.

What `chain-copy` copies: `:seon.task/step`, `:seon.task/instructions`, ns and name, `:seon.task/needs`, and
`:seon.task/proofs` = template's `obligations` proofs. It does NOT copy the template's product (the template has none
anyway) and it does not copy tags. Customizations are merged over the copy.

Source key: copied = `:seon.task/proofs` stored on the copy; subject-inherited = tests tagged to the subject
(`:seon.task/tasks` = `work/fix`); declared product = `:seon.task/product` through `seon.task/effective` (inherited from the subject);
inferred product = tests tagged to a successor (`needs` back-link).

| Step | Proofs (today) | Product (today) | Source |
|---|---|---|---|
| Survey `work/survey-fix` | `seon.step-test/survey-handoff` | none | copied |
| Define `work/define-fix` | `seon.step-test/define-handoff` | none | copied |
| Design `work/design-fix` | `seon.step-test/design-handoff` | none | copied |
| Prove `work/prove-fix` | `seon.step-test/prove-handoff` | declared product of subject (empty until Prove declares it); inferred product from tests tagged `work/implement-fix` (none in a fresh chain) | copied (proofs); declared product (product) |
| Implement `work/implement-fix` | `seon.step-test/implement-handoff`, plus subject-inherited tests (tagged `work/fix`) and declared product | declared product; inferred product from successors (none) | copied; subject-inherited; declared product (inherited through `seon.task/effective`) |

Notes (inferred from code, not run):
- Before Prove runs, the Implement proofs are only `implement-handoff`. After Prove writes product tests tagged to
  `work/fix` and declares them, Implement's proofs gain those tests by two routes at once (subject-inherited and
  declared product); `seon.task/obligations` unions them.
- `seon.task/assignment-read` for `work/prove-fix` selects PRODUCT for `:prove`. A fresh chain's Prove has empty
  product, so assignment is refused `:no-proofs` even though its copied proof `prove-handoff` exists (inferred; not run).
- Prove's product is empty in a fresh chain, so the `:prove` branch of `obligations` adds nothing from inference.

## Does `prove-handoff` check the clone's real product?

No. Quoted from test/seon/step_test.clj:

```clojure
(deftest ^{:seon/tasks #{'seon.step/prove}} prove-handoff
  (let [[database {intent :seon.task/intent} {step :seon.task/step}] (judged)
        product (set (keys worked-results))]
    (is (= :prove step))
    ...
    (is (designed-product? product worked-results) "the worked product ran red by design")
    (is (not (designed-product? #{} worked-results)) "an empty product is no handoff")
    ...
```

`judged` returns the clone's obligations, but `prove-handoff` reads only its `step`. The product it judges is the
constant `worked-results`, a fixed red result for `seon.fs-test/under-path-tells-what-lies-under-a-root`. The assertion
`(designed-product? product worked-results)` therefore cannot fail for any clone. The clone's real product is judged
elsewhere: by `seon.cluster.source/results-verdict` at merge, and by `seon.context/step-observation` for the agent.

## (D) Pure duplicates

1. `:seon/tasks` metadata vs `:seon.task/tasks` attribute: same information, one authored and one ingested. Keep the
   authored form (the code); the attribute is the derived copy. Not a duplicate of meaning, but a second store.
2. `seon.task/obligations`'s `tagged` closure vs `seon.task/task-edges`'s `tagged` closure: the same tag query written twice
   (task.clj, lines ~536 and ~590).
3. `seon.test.runner/single-tags` vs the tag query inside `seon.task/obligations`: both map tests to tasks through
   `:seon.task/tasks`; the runner's version exists only for the inputless template run.
4. `seon.turn/task-proofs` vs `seon.task/obligations` `:seon.task/proofs`: the same set, returned through a wrapper.
5. Four names for one run input: `:seon.test/task` (request), `:seon.test/task-input` (custody), `*task*` (binding),
   `seon.test/task` (reader); plus `seon.test.runner/task-input` and `task-inputs` (shape and plural).
6. Test set derivation: `seon.context/step-observation`, `seon.turn/step-tests` and `seon.cluster.source/tested-task-basis`
   each compute "proofs plus product" from obligations (three copies of `(into (set owed) product)`).
7. `seon.cluster.source/results-verdict` keeps `:seon.task/proofs` and `:seon.task/product` as separate keys; for a
   non-prove step the product is empty, so the split is a case distinction, not new information.
8. `seon.task/assignment` (proofs only) and `seon.task/assignment-read` (product for prove) select different sets from the
   same obligations (inferred inconsistency, see (G)).
9. Successor inference (`needs` back-link) vs stored `:seon.task/product`: two routes to the same set.
10. `seon.task/chain-copy` copying `:seon.task/proofs` vs the tags: the copy is a frozen duplicate of the template's tags.

## (E) What the one-set change touches

Stored attribute: `:seon.task/proofs` on the task entity (already a declaration member and schema member). Product
attribute deleted. Tags stop being read by any selector; they are read only at ingestion.

Writers:
- Ingestion: tags on fn, test and ns rows (`:seon.task/tasks`) are read once to compute each task's proof set (the
  tagged deftests), written as `:seon.task/proofs` on the task row through `seon.task/adopt-tasks-tx`/`tasks-tx`.
- `seon.task/chain!` (`chain-copy`): writes the copied set (already does).
- `my.task/refine!` and `seon.task/create!` (src/my/task.clj, lines ~49-73): today writes `:seon.task/product`. It would
  write `:seon.task/proofs`.
- Root customization (`seon.task/customization` `:seon.task/proofs`): already a member.

Readers to convert (one selector: `seon.task/obligations` reduced to `:seon.task/proofs` of the effective task, plus the
step kind):
- `seon.task/obligations` (both arities), `seon.task/assigned-obligations`, `seon.task/assignment-read`, `seon.task/assignment`,
  `seon.task/task-edges` (keep subjects and referrers, drop proofs), `seon.task/effective` (product content key).
- `seon.turn/task-obligations`, `task-proofs`, `step-tests`; `seon.context/proofs`, `step-observation`.
- `seon.error/proof`; `seon.cluster.agent/basis-valid?`.
- `seon.cluster.source/tested-task-basis`, `results-verdict`, `merge-verdict`, `prepare-merge!`.
- `seon.test.runner/single-tags`, `task-input`, `inputs-of`, `reach-digests`; `seon.test/task`, `*task*`, custody key.
- `seon.render.ns/task-ai`; `seon.bootstrap` help text.
- `test/seon/step_test.clj` (`judged`, `prove-handoff`, `worked-results`) and every test named in (B).

## (F) Open questions

1. Designed red versus green once product is gone: a Prove step's set would mix `prove-handoff` (green) with product
   tests (designed red). What distinguishes them without a product key? Options: a per-step judging rule (Prove: handoff
   green, the rest designed red), or a member role on the stored set.
2. Stale copies: `:seon.task/proofs` written at ingestion goes stale when someone tags a new deftest later. Is ingestion
   re-run on every load, and does that write count as a `seon.task/tasks-tx` revision on a running task?
3. Implement's subject-inherited tests: keep them as stored proofs written at Prove's refine, or re-derive?
4. `assignment-read` refusing `:no-proofs` for a fresh Prove: should the copied proof alone suffice?
5. The inputless template run needs one task binding. If tags stop being read by selectors, the template's default
   must be bound by a stored fact, not `single-tags`. Which fact?
6. Freezing defaults (copied proofs) vs re-deriving from template tags on every run: the owner wants freezing. Confirm.

## (G) Overengineered paths

Items marked (inferred) are from reading only. "Lines removable" is a rough count.

| # | Fully qualified symbol | What it does | Simpler form | Lines removable |
|---|---|---|---|---|
| G1 | `seon.task/obligations` successor query (`product` binding) | Infers product from tests tagged to successor tasks. | Delete; product is only the declared set (TASK-CHAIN "Delete successor-tag inference"). | ~5 |
| G2 | `seon.task/obligations` Implement owner branch (`owner`, `subject`, `inherited`, `(into declared)`) | Adds subject-tagged tests and declared product to Implement's proofs; the declared product is already inside effective product, so `(into declared)` is a duplicate union. | Implement's proofs = copied ∪ tagged(sym); the effective product carries the subject's set. (inferred) | ~8 |
| G3 | `seon.task/obligations` 4-arity `at` closure called three times | Re-pulls the task, effective and tags for candidate, main and base separately, even when identical. Only the `identical?` shortcut avoids work. | Compute the proof set once per value; diff sets. (inferred cost; not measured) | ~6 |
| G4 | `seon.task/obligations` `tagged` vs `seon.task/task-edges` `tagged` | Same tag query written twice. | One helper `seon.task/tagged-tests`. | ~3 |
| G5 | `seon.turn/task-obligations` / `task-proofs` / `step-tests` | Three wrappers. `task-proofs` forwards and converts to a set; `step-tests` filters product by stored datoms. | Call `seon.task/assigned-obligations` at the one use site. (inferred) | ~20 |
| G6 | `seon.context/step-observation` and `seon.cluster.source/tested-task-basis` `(into (set owed) product)` | A third and fourth copy of the proofs-plus-product set. | One derived value passed down. | ~4 |
| G7 | `seon.cluster.agent/basis-valid?` `:prove` successor branch | Special case: a Prove step is admissible only with a successor task (a query duplicating `ready`). | Delete with inference (G1); admissible iff the set is non-empty. (inferred) | ~10 |
| G8 | `seon.error/proof` `requiring-resolve` of `seon.task/obligations` | A cycle workaround: error reads obligations through requiring-resolve. | Move `proof` into `seon.task` (or pass the obligations value in); remove the indirection. (inferred) | ~3 |
| G9 | `seon.test.runner/single-tags` | Tag-derived binding for an inputless run, only to pick one task for `inputs-of`. | Bind the template run to its task explicitly (`:seon.test/task` on the request), delete the tag reader; see (F5). | ~10 |
| G10 | `seon.test.runner/inputs-of` (`readers` + `single-tags` + zipmap) | Two code paths (explicit task, or derive per test). | One path once G9 lands. | ~6 |
| G11 | Four names for one run input: `:seon.test/task`, `:seon.test/task-input`, `*task*`, `seon.test/task`, plus `task-input` and `task-inputs` | Renames of one value across request, custody, binding and reader. | Keep one request key and one reader; drop the custody key by reading the request. | ~15 |
| G12 | `test/seon/step_test.clj` `judged` + `prove-handoff` constant | `prove-handoff` judges a hand-written `worked-results` constant, so it can never fail for a clone. | Judge the clone's product through `seon.cluster.source/results-verdict` (or delete the assertion; the merge gate already judges product). | ~12 |
| G13 | `test/seon/step_test.clj` `templates` vector and the five repeated `document-gaps` section lists (`["problem" "what-exists" "examples" ...]`) | Hand lists that repeat the template's section order and the template list in `seon.step.edn`. | Derive section lists from the template instructions or one `section-order` value. (inferred) | ~8 |
| G14 | `seon.task/assignment` `:seon.task/proofs` for every step vs `assignment-read` product for `:prove` | Two selectors for assignment: one ignores product for prove (possible inconsistency). | One selector (F1/F4). (inferred; check whether this is a bug) | ~6 |
| G15 | `seon.test.runner/task-reach-digests` keying by `id/id [digest input]` | Re-keys every reach digest by task input, even when only one test reads the task. | Key only tests whose closure reads `seon.test/task` (already so) — delete the extra step if the input is absent. (inferred) | ~5 |
| G16 | `seon.task/chain-copy` `before` and needs construction | Builds the needs ref and handle inline with `task-sym` for each copy. | Fine; keep. Listed to show no change needed. | 0 |
| G17 | `seon.fn` `:seon/tasks` authored check and link path (near the deftest refusal) | Two paths for one metadata key: authored check and the link fallback. | One ingestion path. (inferred) | ~6 |

Total rough removable: about 150 lines across G1-G15 and G17, most of it in `seon.task`, `seon.turn` and the runner.
Net effect is a fold of the proof-set derivation into one value (the one-set change in (E)) rather than a line count.

## Summary for root

- Stored today: `:seon.task/tasks` (ingested from `:seon/tasks`), `:seon.task/proofs` (copied or authored), `:seon.task/product` (authored, inherited).
- One selector: `seon.task/obligations`, but five derived copies of its output exist (turn, context, error, source, agent).
- Clones get frozen proofs (`chain-copy`) and no product; a fresh Prove has no product and `assignment-read` refuses it (inferred).
- `prove-handoff` never judges the clone's product; it judges a hand-written constant.
- Pure duplicates: tag query twice, four names for the run input, three proofs-plus-product derivations.
- One-set change: stored `:seon.task/proofs` written at ingestion and chain!/refine!; product attribute and successor inference deleted; readers in (E).
- Open: how designed red is judged without a product key (F1), stale copies (F2), template binding (F5).
