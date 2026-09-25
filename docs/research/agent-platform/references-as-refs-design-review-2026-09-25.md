---
type: design-review
status: complete
created: 2026-09-25
verdict: REVISE
reviewed-commit: 2b5fe18cd
scope: references-as-refs design; read-only source review; no tests or runtime mutations
---

# References as refs — independent review

**REVISE.** The existing parser produces declaration rows, the database writer admits them, and the render and deletion consumers read their relations. Declaring target identities once and deriving consumer views is the right Clojure composition. The design does not yet preserve the distinctions those consumers need: a traversable reference is not automatically a deletion obligation, an invocation, or an issue citation; a render function's role is not its return type. Its migration and forward-reference plans also contradict their own constraints.

Keep the relation inventory, native values, named targets, before/after deletion reasoning, AVET correction, and measured caveats. Revise the design in place before implementation. This is a design verdict, not evidence that any proposed behavior is installed.

## Required revisions

Line references to **design** below mean `docs/research/agent-platform/references-as-refs-design-2026-09-25.md` at **2b5fe18cd**; the reviewed file was unchanged in the working tree. Each numbered item gives the smallest repair, not an additional implementation mechanism.

1. **Separate target identity from lifecycle and consumer meaning.** Design:136, :157, :228, :326.

   The derived map contains only `:targets` and `:storage`, yet is meant to replace every deletion obligation. The table says calls, requires and subjects have lifecycle “none”; §3c says every declared relation is protected. Neither reading works universally. A surviving caller or test subject must block deletion of its target, even when the edge is a value. Historical reach and fault-operation values must survive deletion without blocking it. Optional agent→task refs have ordinary sweep semantics. These distinctions cannot be derived from ref/value storage or target identity alone.

   The source already includes test subjects in `removed-definition-error` (`src/seon/db.clj:4086` in the inspected checkout; :4080 at the review pin), contrary to design:38 and :338. The unbreakable-connections mission distinguishes live obligations from stale reach (`docs/prds/steward-platform/research/unbreakable-connections-2026-09-16.md:153`).

   **Smallest fix:** keep one target property, but state the existing authority that selects live declaration obligations versus historical observations for each relation. Derive from declaration ownership, component ownership, required members and invocation declarations where those suffice. Do not make every `reference-to` an obligation or add a second hand-maintained relation list. Resolve the remaining policy gap explicitly before claiming S0 deletes the old list. Correct the table to say “value persists; surviving declaration still blocks removal” for calls/references, requires, writes and subjects. Preserve the special written-attribute obligation for removal of `:db/ident`, currently at `db.clj:4095`, not just schema-row removal.

2. **The final target check must cover changed targets, not only asserted edges, and deletion repair needs positive evidence.** Design:145, :219, :258, :326.

   Checking “every asserted ref datom” misses a transaction that retracts a target's identity while leaving its other datoms and incoming refs unchanged. Numeric eids still resolve to themselves (`reference-code/datahike/src/datahike/db/utils.cljc:109`). Whole-entity deletion instead sweeps incoming refs (`db/transaction.cljc:999`), making a final ref lookup alone insufficient. Renaming an identity or retracting and recreating it under another eid also needs an explicit outcome.

   §3c correctly calls for before/after comparison, but “new definition digest no longer names the target” is not a complete repair rule: a digest hashes data; changing it alone does not prove that the producer re-analyzed the declaration. Arity refs are on owned components, while their source and analysis evidence belong to the enclosing function. Schema render refs are derived from the schema form. An unrelated digest change or a swept edge cannot count as a repair. Deleting `write-render-target-error` also removes a check of that authoritative form; a replacement check over optional ref datoms is equivalent only if publication and admission prove the form-to-ref derivation.

   **Smallest fix:** put the final check in the existing report validator, selecting asserted relations and incoming relations to changed/removed target identities from before and after. Resolve identity-less arity components to their owners. Specify repair as current producer evidence agreeing with the final authored declaration and its derived relations, or coordinated removal of the referrer. A before-edge disappearing through sweep is not that evidence. Include identity-only removal, rename, last-member sweep, one-of-many sweep, coordinated repair/deletion and target replacement in the proposed witnesses. Preserve complete component validation rather than requiring identities on all components. Target existence checks should use EAVT for a known eid, correcting the AEVT claim at design:149.

3. **A mechanical `cites`→`reference-to` substitution is incorrect.** Design:117, :136, :326.

   `citation-attributes` currently scans properties into a single `identity-attribute → issue-attribute` map (`src/seon/issue.clj:189`). Once the property is global, many attributes target `:seon.fn/sym`; an unrestricted scan can select calls, must-read or a schedule field as the destination of an issue citation. Collection order would decide which one wins.

   There is also an actual two-hop relation omitted from the inventory. `:seon.issue/files` declares `:seon.issue/cites [:seon.fn.file/relative-path]`, but its ref targets a citation component (`resources/seon/schemas/seon.issue.edn:20`), whose separate `:seon.issue.citation/file` points at the file (`seon.issue.citation.edn:4`). Renaming this property literally would make the proposed target check reject valid citation components.

   **Smallest fix:** give `reference-to` exactly one meaning: the direct target identity. Derive issue citation destinations within the issue schema, preserving the component path through its declared component schema. Put the file target on `citation/file`, not on the parent-to-citation edge. Specify ambiguity handling rather than overwriting a map entry. Keep invocation selection explicitly tied to the existing invocation semantics; broadening the old Datalog property join (`src/seon/fn.clj:1696`) must not turn must-read links into call reach.

4. **Name the producer's candidate world and use it consistently for forward refs.** Design:146, :200, :204, :328, :329.

   The producer supposedly refuses any target with “no row,” while §3b admits targets being created in the same publication. It then reserves lookup refs for schemas/renderers presumed already installed, and S2 relies on dependency-first schema order. A new renderer and a schema declaring it can arrive together; a function can use a schema created in the same admission. Lookup resolution reads the transaction database at that point (`db/utils.cljc:115`), not all future operations. Dependency ordering alone is not a treatment of self-reference or cycles. The current ordering helper marks a node seen after visiting its dependencies (`src/seon/schema.clj:3681`); do not promise general cycle support from that helper.

   **Smallest fix:** define producer resolution against the explicit admitted base plus the complete candidate identity set. Existing targets may use lookup refs; candidates use identity-keyed tempids whose definitions must be present, including renderer and schema candidates. Preserve one canonical identity representation before transaction encoding. State whether recursive schema cases are supported or refused at the schema owner's existing boundary. Reject absent candidates by name; no placeholder rows or pending queue. Resolve symbol ambiguity between function and test identities explicitly instead of assuming value type alone chooses the target. Validation remains at `var-row` and its existing sibling constructors; downstream renderers do not parse links again.

5. **Reuse the existing portable-ref owner and correct the merge scope and digest claim.** Design:240, :244, :328, :340.

   `seon.cluster.source/replacement` already delegates to `seon.fn/published-index-rows` and `reconcile-tx` (`src/seon/cluster/source.clj:772`). `published-index-rows` explicitly reads portable program refs and complete owned components; its `reference` function resolves peer refs through identities, and `row` removes database ids (`src/seon/fn.clj:3597`, :3628, :3639). Adding another normalizer in `replacement` duplicates this owner.

   The cited merge entrance currently refuses additions and retractions and accepts replacements of existing functions/tests (`cluster/source.clj:780`, :811). “Two branches each adding a schema key, merged” is therefore not an executable witness through that entrance as described. The collision hazard is real, but the stated missing implementation and proof surface are inaccurate.

   Digests also need one portable representation. `var-row` digests the constructed row (`fn.clj:823`); the writer reconstructs peers as lookup refs (`db.clj:4224`) before checking the canonical digest. Changing keywords/symbols to lookup refs or tempids requires demonstrating equality across producer, stored reconstruction and branch export. “Before storage” alone does not guarantee it.

   **Smallest fix:** extend or verify `published-index-rows` for the new relations; delete the proposed second normalization work. Keep source eids out of peer refs and retain owned components structurally. State the current merge refusal boundary and place the colliding-eid witness at the existing portable-row transfer seam; full schema-addition merge needs an explicitly owned prerequisite. Specify digest input as the same canonical identity-based row everywhere, with tempids confined to transaction encoding. Refuse missing destination targets and preserve the existing program/data partition rule.

6. **Index renderers by their face role; key each derived index by everything it reads.** Design:264, :278, :382.

   The proposed output-schema query preserves an existing implementation restriction, not the owner's rendering law. A renderer may return any specified shape, which is rendered in turn (`AGENTS.md:234`; language requirements:177). Returning a map or string unrelated to the three listed schema names does not disqualify it. Conversely, mentioning a face key inside an output schema does not establish a render role. The mandatory input-ref join also drops a function with a valid unnamed input schema, and the query omits the public/context visibility filtering of `namespace-stage` (`src/seon/render.clj:618`). Arity reference sets describe names occurring in a contract, not its complete dispatch meaning.

   Furthermore, projection identity is a valid dependency for schema pairs, but does not by itself identify namespace function membership, roles, contracts or private SCI definitions. Caching the namespace map under only that identity risks stale additions/removals when functions change without a schema change.

   **Smallest fix:** first name the canonical face-role declaration and have the one producer publish it; use that to retrieve namespace candidates, then the existing contract/default-supply owner to match the value. Do not infer role from return shape or require named input refs merely to be a candidate. Preserve applicable visibility, ambiguity, private-context and first-match behavior. Separate projection-derived pair data from namespace/context-derived candidate data, keying the latter by the actual declaration/context inputs. If the role fact is not installed, mark it as an S3 prerequisite. Keep the 1.31 ms measurement as a measurement of the old output-reference query, not proof of the required index.

7. **Give every retype a new name, and make the add/retire steps concrete.** Design:162, :319, :330, :353.

   S4 explicitly retypes `:seon.fn/must-read` and `:seon.fn/see-also` in place because default had zero datoms. This contradicts the assignment and §6's own rule. An empty data population does not remove the installed attribute definition, other branches, or stored row-schema compatibility. S4 also becomes optional while the recommended option promises authored links are refs.

   **Smallest fix:** choose new registered names for both ref attributes, keep authored metadata spelling stable, and include their migration in the recommended scope. For each S2–S4 retype, list old/new storage attributes, owning row-schema changes, the publication that adds/backfills and switches readers, and the later publication that retracts obsolete facts and retires the old declarations. S3 must retire only the schema-row codec use: value-level `:seon.render/ai` and `/html` overrides still exist. Describe the actual number of adoptions rather than “four adoptions” alongside two-step retypes. Preserve e00a3ccda's precise lesson: old stored row requirements blocked retirement; zero observed datoms is no exemption from the owner's new-name rule.

8. **Specify and validate the relation descriptor itself; do not call an identity-presence test a complete named-schema guarantee.** Design:125, :145, :327, :375.

   `[:set {:min 1} :qualified-keyword]` permits non-identity attributes and unregistered keys. `m/properties` reads schema properties; it does not validate the target property as a value against its separately declared schema (`reference-code/malli/src/malli/core.cljc:2592`). A named identity datom establishes a target family, not by itself a valid complete target row. The design also needs to distinguish input metadata, canonical identity links, transaction tempids/lookups, stored eids and pulled shapes.

   **Smallest fix:** give each stage an existing or proposed named schema and its producer call site. Validate descriptor values once per supplied projection with Malli's compiled `validator` (`core.cljc:2637`), checking target keys are declared identities and identify the intended row schemas. Reuse the writer's complete owning-value checks for affected target rows, including unchanged incoming refs when a target changes. Bound and memoize by target plus allowed identity set; one target checked for one attribute is not proof for a second attribute with another target family. Do not add a parallel validator or a discriminator stamp. Reconcile the earlier parser prototype with current task schemas instead of importing its proposed keyword task-id shape over the installed string-handle producer.

9. **Do not close owner requirements while their semantic choices and evidence remain open.** Design:106, :314, :332, :367, :393.

   The sentence “None of D1–D6 applies” to targets present at admission is false: deletion sweep, identity replacement and foreign-eid replay happen later. Those are precisely the obligations §§3c–3d must prove. The design's Q3 proposes weakening the newer error-member rule; preserving historical operation names is sound for observations, but is not authority to classify every actual live error relation as a token. Q2's tokenizer choice also does not decide whether a code-example `[[sym]]` is a link.

   **Smallest fix:** state that refs are acceptable only with the final-writer and portability proofs above. Split historical identity observations from actual live error relations and leave the latter requirement open pending the owner's ruling; do not silently amend language row 12.2. Define authored docstring link syntax and resolution at the existing parser producer, including examples and malformed input; no reader-side parser or unapproved production regex. Mark rows dependent on these decisions as open. Recompute the line/cost estimate after including the target validator, changed-target selection, role fact/index, citation paths and both migrations. Do not use the current +90/−100 estimate as an implementation bound for omitted work.

## Per-relation verdicts

These verdicts concern meaning and storage. They do not waive final admission, deletion, merge or migration requirements.

| Relation | Review verdict |
|---|---|
| Function/test calls and non-call references | Keep native symbol values as analyzed name observations; known live declaration targets remain deletion obligations. No synthetic external function rows. |
| Unresolved references | Values; absence must remain inspectable. Do not treat them as proof of a live target. |
| Keyword mentions | Values, not automatically schema references or deletion obligations. |
| Written keys | Values are defensible because analysis observes keys; retain protection against removing a schema or installed attribute still named by a surviving writer. P10 is unmeasured. |
| Function/namespace/test authored must-read, see-also and docstring links | Refs are appropriate if the authored relation promises a live target. Required part of the stated scope, with new storage names, candidate-world resolution and explicit docstring grammar. |
| Arity input/output/guard schema dependencies | Refs are appropriate for admitted named schema dependencies; sets do not preserve argument position. Protect each member through its declaration owner, and distinguish malformed experimental contracts from armed ones. Guard coverage is unmeasured. |
| Schema dependencies | Refs are appropriate; missing targets, same-admission targets and recursive forms need the schema owner's explicit rules. |
| Schema AI/HTML renderer pair | Refs under new schema-specific names; form/ref agreement and deletion protection are prerequisites. Keep value-level overrides separate. |
| Namespace requires | Values, including external namespaces; removal of a known required program namespace remains protected. |
| Defining macro (`defined-by`) | Value grammar token; not a demand to populate dependency Vars as program rows. |
| Test subject | Value permits test-first authoring; later removal of an existing subject while its declaration survives is not permitted silently. |
| Test reach | Historical value evidence; retain names and invalidate/reselect proof as appropriate, without making old reach an eternal deletion blocker. |
| Code→task; task→must-read | Values; tasks remain data outside program merge. Validate task handles at the destination gate. Task instructions may name future work. |
| Function/test→namespace; function→file | Keep refs, honoring the owning row's requiredness and provenance; portability already has an owner. |
| Retiring arity shape mirrors | No new work here; coordinate retirement with their owning slice and do not revive them. |
| Scheduled task→function | Keep the required ref; the common target property must preserve its invocation and whole-row deletion guarantees. |
| Issue→functions/keys/namespaces/tests/errors/runs/issues | Existing refs are reasonable live citation links, subject to their own required/optional and retention rules. They do not all become program-deletion obligations. |
| Issue→file citation→file | Two refs with different direct targets, one owned component. Cannot copy the old `cites` target onto the outer edge. |
| Agent→task | Ref is consistent with a live data→data relationship; task closure and task deletion are separate operations. Preserve the declared lifecycle. |
| Fault operation/instrumented function/evaluation renderer observations; offending names | Native values when they record what was observed and must outlive it. Actual live entity relations in errors remain subject to the newer ref requirement and Q3 resolution. |
| Capability, seed-function and invocation relations | Preserve the existing producers and semantics explicitly in S0's inventory. `reference-to` enables navigation; it must not erase capability deletion protection or turn every navigable link into invocation reach. |

## Evidence and review boundary

- Reviewed design bytes at **2b5fe18cd**, including all slices, questions and probe caveats. Read AGENTS.md, the data-oriented Clojure, data-modeling and datahike skills, and the cited model/deletion/parser/mission authorities relevant to these decisions. This review adds no design implementation and delegates no work.
- Source citations above are from the inspected checkout at **259dd1375** unless marked otherwise. Compared relevant owners against 2b5fe18cd: `fn.clj`, `program.cljc`, `schema.clj`, `cluster/source.clj`, `issue.clj` and `render.clj` were unchanged. `db.clj` had a six-line net shift before the cited region from bootstrap guards, so its reviewed-pin lines there are the cited checkout lines minus six. Those guards are unrelated to the findings.
- Dependency pins at the reviewed commit: Datahike **4157916b5a1b8d80d7f809c889bce1b098b1d75d**, Malli **befcd302a8e626a67d84b80aabee0cd1b38dadb6**. Inspected native lookup resolution, incoming-ref sweep, Malli property reading and compiled validation, and their first-party callers. These are source guarantees, not a fresh installed-system probe.
- Read e00a3ccda's retirement failure and two-adoption recovery rationale. No `bin/seon` lifecycle command, MCP mutation, test request, gate, archive build or worktree was run. No source, skill, issue index or other lane's file was edited. The explicit no-tests instruction governs this review lane.
- P1–P9 timings/counts are the design author's historical evidence, not independently repeated here; P10, committed-store performance, current runtime installation and memory remain unverified. The with-tail benchmark does not establish committed-store speed. The design's 500-frontier comparison also has differing edge counts, so retain that qualification rather than promote its ratio to a requirement proof.
- Foreign working-tree changes in config/schema/test files and the http-kit submodule were present and left untouched. They did not block this document review. Net source/test change: **0/0 lines**. No runtime or memory measurements were made; none are claimed as passing evidence.

The next review should inspect the revised design's descriptor semantics, final-writer cases, producer/candidate representation, existing portable-row seam, role-based index and explicit adoption sequence. Implementation and its installed proofs follow only after those questions are resolved in the owning design.
