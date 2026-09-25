---
type: design-review
status: complete
created: 2026-09-25
verdict: REVISE
reviewed-commit: 413e26aa4
previous-review: 93b519229
scope: revision 3; read-only source and dependency review; no tests or runtime mutations
---

# References as refs — independent re-review

**REVISE.** Revision 3 keeps the right composition: the existing declaration producers emit canonical rows; one direct-target property supplies navigation; the final database writer checks admission and deletion; the existing portable-row owner exports refs. Root's Q1–Q4 rulings are incorporated, including the reversal of Q4. The markdown dependency is justified, and no second Seon link parser is proposed. Three original findings are closed; six remain open. The remaining blockers concern deletion policy, repair evidence, missing-target policy, renderer candidate completeness and specified stages—not a request for a face fact or another registry.

This replaces the review at **93b519229** in place. `design:N` below means `docs/research/agent-platform/references-as-refs-design-2026-09-25.md:N` at **413e26aa4** (revision 3); its working-tree bytes matched that commit. First-party source and resource citations below also mean **413e26aa4**, so concurrent edits and shifted checkout line numbers do not change the evidence. Dependency citations use the gitlinks recorded below. “Closed” means the design addresses the finding, not that implementation or installation is proved.

## Disposition of the nine original findings

1. **Still open — target identity is separated from lifecycle, but the derived lifecycle rule contradicts the inventory.**

   **Addressed:** `design:25` makes `reference-to` navigation-only. `design:91`, `design:101` and `design:193` preserve written-attribute and test-subject obligations. Data observations and invocation are distinguished at `design:195` and `design:35`.

   **Remaining:** the three conditions at `design:187` select any declared relation owned by a program family whose target identity matches. A function's `:seon.task/tasks` satisfies all three: the design declares its target as `:seon.task/id` (`design:103`), and the function and namespace schemas own that member (`resources/seon/schemas/seon.fn.edn:166`, `resources/seon/schemas/seon.ns.edn:29`). Yet `design:103` explicitly says this is not a program deletion obligation. Saying “tasks are data” at `design:197` does not resolve this case: the **holder** is program. Also, §3c's “every holder” at `design:288` must explicitly apply the obligation predicate; otherwise data referrers lose their optional-sweep semantics.

   **Required repair:** derive holder and target classification from the existing row/partition authorities and state which combinations create an obligation, including the existing `writes`→`:db/ident` case. Make §3c use that decision. Add a code→task counterexample and a data optional-ref counterexample to the equality witness at `design:201`; the empty must-read population cannot establish policy equivalence. No second hand-maintained relation list is needed.

2. **Still open — changed-target selection improves, but the claimed repair evidence is not available or sufficient as described.**

   **Addressed:** `design:276` selects asserted relations and changed identities; `design:282` resolves arities to their owners; `design:285` uses EAVT and `(eid, targets)`; W1–W9 cover the previously omitted deletion cases (`design:308`). These are the correct places to work.

   **Remaining, with a dependency-level contradiction:** `design:298` says swept datoms are absent from the attempted set. They are present. `retract-entity` sends incoming refs through `transact-retract-datom` (`reference-code/datahike/src/datahike/db/transaction.cljc:999`, `:1014`); that calls `transact-report` (`:814`), which appends the datom to the internal attempted `:tx-data` (`:616`). The completed report exposes that collection as `:datahike/attempted-tx-data` (`:1230`, `:1295`). It has no authored-versus-swept origin distinction. It also represents expanded operations, not a complete submitted canonical row. Thus `design:295` cannot identify the producer's complete row with “the attempted datoms of that entity.”

   The universal repair requirement at `design:292` is also fn/test-specific evidence. `namespace-row` emits a definition digest but no analyzed-source digest (`src/seon/fn.clj:256`); `canonical-schema-rows` does likewise (`src/seon/schema.clj:3718`). A coordinated schema-use or schema-renderer repair cannot satisfy the stated rule. Reasserting an analysis digest is not independently proof that final derived edges agree with source: those graph members and the analysis digest are excluded from the definition digest (`src/seon/program.cljc:374`).

   Finally, `design:301` says the writer already recomputes schema-row digests. `write-digest-error` selects only `:seon.fn/sym` and `:seon.test/sym` (`src/seon/db.clj:4287`). Retaining `write-render-target-error` until W-form is good, but the named replacement guarantee does not yet exist.

   **Required repair:** define positive repair evidence for each declaration producer using its actual authored input and derived canonical value; name how the final writer verifies that agreement without treating attempted datoms as producer provenance. Cover fn/test, namespace and schema repairs, including a last-member sweep plus an idempotent digest assertion. Keep form/ref validation until the schema producer-to-writer proof really replaces it. Changed-target refs from selection (b) must undergo target validity as well as obligation checks; `design:285` currently limits existence to (a), while W7 invokes it for an unchanged old-eid ref (`design:314`). This repair belongs in the existing report validator, not another parser or validator pipeline.

3. **Closed — issue citations and invocation retain their separate meanings.**

   `design:204` keeps `:seon.issue/cites` issue-local, restricts the scan to issue-schema members and refuses duplicate destinations. `design:211` preserves the two-hop citation component and puts the file identity on `:seon.issue.citation/file`, not the outer component edge. `design:141` deletes the old generic reach branch while retaining `:seon.fn/invokes`. This resolves the global-property overwrite and direct-target mistakes. Q1 is honored; identity-less citation components need no invented identity.

4. **Still open — the candidate world is named, but its universal missing-target refusal contradicts the retained value relations.**

   **Addressed:** `design:245` names the admitted base plus complete candidate identities. `design:251` keeps identity lookup refs canonical and confines tempids to transaction encoding. Same-admission renderer/schema targets, ambiguity and named cycle refusal are explicit (`design:255`, `design:259`, `design:262`).

   **Remaining:** “every link” (`design:245`) and “a target in neither set refuses” (`design:257`) also cover the external Vars and future subjects that the inventory deliberately keeps as values (`design:93`, `design:101`, `design:238`). Those targets have no candidate row by design. Retaining value storage alone does not exempt them from this stated producer rule.

   **Required repair:** delimit must-exist ref resolution from name observations and test-first values; separately state doc-link resolution, including an existing external Var with no program row and a first-party Var missing its row. Keep all cases in `var-row` and its sibling producers. Clarify local symbol qualification too: `seed-symbol` resolves aliases and explicit refers but has no current-namespace fallback (`src/seon/fn.clj:496`), while `design:377` promises that behavior. Supply the holder namespace at that existing producer seam rather than implying the helper already implements it.

5. **Closed — portable rows and canonical identity encoding reuse the existing owner.**

   `design:321` uses `seon.fn/published-index-rows`; `design:325` explicitly removes the second normalizer. The actual owner exports program peers through `program/row-identity` and data peers through declared identities (`src/seon/fn.clj:3637`), and removes `:db/id` in `row` (`:3656`). `design:327` confines tempids to encoding and requires canonical digest equality. `design:331` accurately bounds today's merge entrance and places W10 at portable transfer rather than pretending schema additions already merge. Equality and collision witnesses remain implementation obligations; arity relations being excluded from the definition digest means digest equality alone is not their derivation proof (finding 2).

6. **Still open — Q4 is correctly reversed, but the proposed face fold is not equivalent to current candidate selection.**

   **Withdrawn:** the original request for a canonical face-role fact. Root's ruling controls: attachment key first, otherwise declared output contract, with **no face fact** (`design:399`, `design:537`; `AGENTS.md:238`). `design:406` correctly reads compiled contracts instead of requiring named arity input/output refs. `design:424` also separates projection and namespace/context cache inputs.

   **Remaining:** `design:404` and `design:420` claim the fold preserves today's semantics and that any plain text output is currently a candidate. In fact `namespace-stage` checks exact output-form membership via `function-accepts-and-returns-in?`, plus its separate source-output rule (`src/seon/render.clj:625`, `:631`; `src/seon/schema.clj:376`, `:3849`). Plain `:string` is not equal to `:seon.render/ai`; the existing source rule recognizes `:seon.render/source` **and `:seon.render/source-blocks`** (`src/seon/render.clj:265`, `:274`). The proposed fold recognizes strings and hiccup (`design:408`), but `source-blocks` is a map (`resources/seon/schemas/seon.render.edn:68`). It can therefore discard an existing source-blocks candidate before the retained matcher sees it. Conversely, broadening the index to string alternatives does not make the retained exact matcher accept them.

   **Required repair:** derive the index from the same output-contract authority used for final matching, preserving every currently eligible candidate, including source blocks and same-arity input/output matching. If output compatibility is deliberately expanded under the ruling, state the change in that owner instead of claiming it is installed already. Keep attachment-selected arbitrary output shapes valid. Key private-context candidates by definition/contract content, not merely an unchanged set of symbol names. Add equivalence witnesses for raw `:string`, named AI output, source, source-blocks, aliases, unions and overloaded contracts. P15's 17.8 ms is a fold measurement, not proof of equivalence.

7. **Closed — every remaining retype has a new name and an explicit retirement sequence.**

   `design:220` names `inputs`, `outputs`, `guards`, `uses`, `render-ai` and `render-html`; `design:222` preserves value-level overrides. `design:469` specifies three publications: optional old/new members and dual production, reader switch, then old-fact/declaration retirement. Must-read and see-also no longer retype at all (`design:93`, `design:238`), with the existing external-Var authoring case providing a concrete reason. The zero-datom exemption and optional S4 are gone. Implementation must populate existing rows before switching readers and prove each adoption, but the original naming/retirement contradiction is resolved.

8. **Still open — descriptor validation is specified, but the promised stage contracts remain partly owner labels.**

   **Addressed:** `design:157` checks descriptor members against registered identity properties and row schemas, with the explicit `:db/ident` exception. It correctly distinguishes `m/properties` from compiled `m/validator` (`reference-code/malli/src/malli/core.cljc:2592`, `:2637`). `design:283` preserves complete owning-value validation rather than mistaking identity presence for full validity. The task id remains a string handle (`design:175`).

   **Remaining:** the table promising “each with its schema” at `design:171` names the writer for transaction encoding, Datahike for datoms and the guide for pulls (`design:178`), not the actual schemas or their derivation. The authored row attributes docstring `[[sym]]` to `:seon.task/annotation`, but that installed schema covers only task/must-read/see-also metadata (`resources/seon/schemas/seon.task.edn:28`). The compiled descriptor's derived map (`design:166`) likewise has no named contract in the plan. S1 says “stage schemas” (`design:480`) without resolving these holes.

   **Required repair:** distinguish installed schema, proposed named schema and selector-derived schema in the table, naming the producer validation call for each added value and the doc-link refusal alternatives. Reuse existing transaction/datoms/pull contracts where applicable; no second schema registry or per-attribute pull-schema copies. Include that work in the line estimate and leave requirement 1.15 open until specified.

9. **Still open — root's semantic decisions are closed, but requirement closure and cost claims must reflect the remaining design gaps.**

   **Addressed:** `design:129` retracts the “D1–D6 do not apply” claim. Q2 now uses the library's markdown tree, explicitly excluding code spans/blocks (`design:340`). Q3 preserves error-member symbols and reconciles language row 12.2 (`design:528`; `docs/research/agent-platform/language-requirements-2026-09-24.md:186`). Q4 is reversed explicitly. The estimate is recomputed to +150/−103 with the new dependency (`design:483`), and three scoped options remain (`design:497`). None of those owner rulings should be reopened.

   **Remaining:** requirement 1.10 says “closed by S0” (`design:519`) despite finding 1 and S0 relying on repair evidence deferred to S2+S3. The claimed stage completion at `design:522` and unchanged render behavior at `design:423` have the gaps above. The +80 S2+S3 estimate assumes the attempted-datom provenance and existing schema-digest guarantees disproved in finding 2. The 80 ms reindex number is extrapolated from markdown parsing alone (`design:370`), excluding resolution, validation, storage and dependency acquisition; “one-file publication adds microseconds” is not a measured end-to-end publication result.

   **Required repair:** mark the affected rows as design/proof pending, revise the owning slices and costs after repairing findings 1, 2, 4, 6 and 8, and label parse extrapolation separately from publication clocks. Keep historical P1–P17 measurements and caveats; do not turn them into installed acceptance evidence. The smaller values-only option still needs a coherent S0 deletion rule.

## Root rulings, one producer and the dependency

| Check | Result |
|---|---|
| Q1: direct target only | **Satisfied in the design.** `design:25`, `design:153`, `design:211`. Citation routing and invocation keep their own authorities. |
| Q2: nextjournal/markdown parse tree; new, vendored dependency | **Satisfied as a planned change.** `design:345` pins 0.7.213, admits that it is absent from Seon's dependency declaration and assigns dependency addition/vendoring to S1. This review does not claim it is installed or already vendored. |
| Q3: error-member function names stay symbols | **Satisfied.** `design:110`, `design:112`, `design:535`; language row 12.2 is reconciled. Occurrence-to-live-data refs remain distinct. |
| Q4 reversed: no face fact | **Satisfied as policy.** `design:399`, `design:537`. Finding 6 concerns the derived index's behavior, not authorization for another fact. |
| One producer (`var-row`) | **Satisfied structurally.** `design:16`, `design:268`, `design:373`, `design:480` put extraction/resolution in the existing row constructors and shared link step. `var-row` already merges `task-links` before digesting (`src/seon/fn.clj:832`); `namespace-row` does likewise (`:256`). Final admission validates their values; it must not become another extractor. |
| No second parser | **Satisfied structurally.** Markdown interprets docstring markup; existing edamame reads each extracted symbol; existing alias resolution qualifies it (`design:378`, `design:383`). No renderer or walk parses links again (`design:386`). The candidate-resolution gap is finding 4. |

**The dependency is justified.** clj-kondo supplies the docstring, not Markdown structure; edamame reads Clojure values, not code-fence and inline-code boundaries. The chosen library supplies a Clojure data tree and an existing internal-link tokenizer, avoiding a Seon tokenizer and renderer-side extraction. Upstream describes the AST and CommonMark JVM implementation in its [README](https://github.com/nextjournal/markdown#readme). Inspection of the locally cached **0.7.213** jar confirms `nextjournal/markdown.cljc:20` accepts the parse configuration, `impl.clj:237` routes Text nodes through the tokenizer, `impl/utils.cljc:259` limits tokenization to text nodes, and `impl/utils.cljc:296` supplies the internal-link tokenizer. Its regex is library implementation, not a new Seon production regex. Its predicate also excludes ordinary link ancestors; retain that library grammar rather than adding a parallel scanner for unmatched text.

The 0.7.213 POM names CommonMark **0.24.0** and five extension artifacts (footnotes, strikethrough, task-list-items, tables and autolink), not just one jar. S1's vendored pin and dependency clock should record that closure. The inspected usages in Clerk and Clay are different versions; Babashka declares 0.7.213. Those usages support library reuse, not proof of Seon's resolved classpath. P16's 58.4 ms/1,858-docstring measurement is the author's evidence and makes the proposed parse cost plausible; resolution, admission, memory and actual publication costs remain unmeasured by this review.

## Evidence and lane boundary

- Reviewed revision 3 and the nine original findings at their named commits, plus the relevant declaration, deletion, rendering, portable-row and library owners. Applied the data-oriented Clojure, datahike and data-modeling skills to those boundaries; no implementation or delegation occurred.
- Datahike gitlink: **4157916b5a1b8d80d7f809c889bce1b098b1d75d**. Malli gitlink: **befcd302a8e626a67d84b80aabee0cd1b38dadb6**. The attempted-datom counterexample is a source derivation from those pinned functions, not a newly executed regression.
- No test, gate, `bin/seon` command, MCP evaluation, runtime mutation, archive build or worktree was run. The assignment's explicit no-tests boundary takes precedence over its generic test-loop paragraph. No runtime installation, timing or memory result is claimed as independently verified.
- Foreign edits were present in cluster source, render source/tests, schema resources and the http-kit submodule; they were not used as the evidence pin and were left untouched. They did not block the review. Source/test change: **0/0 lines**. Only this review file is revised and committed; no other lane was resumed, messaged or edited.

The next design revision must resolve the six open findings in the existing owning sections. The closed citation, portable-row and migration decisions, root's four rulings, and the one-producer markdown approach should carry forward unchanged.
