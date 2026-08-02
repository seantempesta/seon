---
type: research
status: complete
tags: [prd, research, audit]
---

# Fresh code-quality sweep — 2026-08-02

## Verdict

Fresh Seon is notably disciplined at its construction doors, but four
high-blast shapes remain: render failures become absence, fleet state is
inferred from a 20 ms non-response, namespace context is both prefix-classified
and stored as a second projection, and the durable session image stores prose
after deleting its evidence. The central turn/evaluation path is also too deep
to keep repairing safely without splitting at its already-settled durable
boundaries.

This was a read-only production-code audit. It made no source or test fixes.
The only changes are this report, open issue notes, and their schedule rows.

## Scope and boundary

The sweep covered live `src/`, `test/`, and `script/` at parent HEAD
`83a6693d2` plus the visible working snapshot on 2026-08-02. It inventoried 158
files and 66,099 lines. A sibling implementation lane had uncommitted edits in
`src/my/run.cljc`, `src/seon/bootstrap.clj`, `src/seon/cluster.clj`, and
`src/seon/sci/eval.clj`; those edits were preserved. No finding depends on the
new bootstrap mechanism, and the `evaluate` finding is only its stable
structural span.

Dead code, old CLJS/pod paths, documentation, and skills were excluded for the
concurrent rot audit. Existing issue clusters were not refiled.

## Dependency ledger and method

- Clojure core data and source-reading idioms were assessed against fresh
  first-party usages and the `data-oriented-clojure` catch list.
- Malli is selected at `0.20.0` in `deps.edn:16`; contract checks were read
  against the admitted schema forms and `test/seon/public_contract_test.clj`.
- Core.async Flow is selected at `1.10.874-alpha3` and vendored at
  `reference-code/core.async` commit `dc35f3e0`; proc workload and ping behavior
  were grounded in that source plus `src/seon/flow.clj`.
- SCI is vendored at commit `a27e2c0e`; the evaluation and session-image seams
  were read from `src/seon/sci/eval.clj`, `src/seon/cluster/loop.cljc`, and the
  surviving session-image tests.
- Datahike is vendored at commit `256b714d`; stored-derived findings were
  checked against the transaction/query owners, not inferred from attribute
  names alone.

The audit used an `rg` clock/catch/classifier census, a raw-reader census of
public `defn` contracts, clj-kondo function-span data, targeted source reads,
and three executable falsifiers. The current public-function census found no
missing `:malli/schema`. A fake `:seon.config.fake/not-a-dial` was nevertheless
classified as a dial; two Flow channel generations returned the identical
object; and a forced render namespace-load failure selected the ordinary data
floor with `:seon.render/would-fall-to-floor? true`.

## Ranked findings

| Rank | Finding | Class | Blast radius | Dissolve the class |
|------|---------|-------|--------------|--------------------|
| 1 | Renderer/schema resolution and the SSE writer erase broad failures (`src/seon/render.clj:282-305`, `364-382`; `src/seon/render/web.clj:775-797`). | Error discipline; absence as health | Every AI/HTML render and every live browser feed can show a floor, generic missing Var, or closed connection instead of the fault. | One structured resolution result distinguishes declaration, true absence, and failure; the feed commits failures through the existing fault path. |
| 2 | Fleet state is inferred from failure to pong within 20 ms (`src/seon/oversight.clj:34-39`, `87-142`). | Unjustified clocks; absence as health | Every agent and plumbing row can be mislabeled under ordinary scheduler delay. | Publish/retain named Flow lifecycle observations; a deadline reports uncertainty, never state. |
| 3 | Session-image rows delete replay-safety evidence and store an English conclusion (`src/seon/cluster/loop.cljc:340-410`; `resources/seon/schema/program.edn:188-210`). | Stored-derived; error discipline; exact-string tests | Every cold restore and forensic query loses why a name was refused. | Store/reference observations once and derive prose at render time. |
| 4 | Cluster namespace context is selected by `my.` and copied into `:seon.cluster/toolkit` (`src/seon/cluster/instruction.cljc:36-61`; `src/seon/cluster.clj:831-881`). | Hand list; stored-derived; parallel truth | Every cluster context can stale or omit relevant namespaces because of spelling. | Query relevance from current graph/context facts; delete the stored projection and prefix rule. |
| 5 | `turn` is 623 lines and `evaluate` 276, each spanning multiple settled boundaries (`src/seon/cluster/loop.cljc:899-1521`; `src/seon/sci/eval.clj:1230-1505`). | Oversized/deep; mixed ownership | Nearly every agent turn, refusal, schema publication, and session repair edits the same nested kernels. | Keep the two entries but extract pure planners/transformations at durable boundaries; add no second loop. |
| 6 | Config membership comes from a namespace prefix while tests repeat dial and consumer rosters (`src/seon/schema/edn.clj:61-84`; `test/seon/config_application_test.clj:17-128`). | Hand lists; duplicated mechanisms | Every config addition can be admitted or tested against the wrong authority. | Explicit leaf membership plus graph-derived consumer/application evidence. |
| 7 | Changed-test host impact is classified by literal prefixes and filenames (`script/seon/dev/changed_test.clj:167-220`). | Hand lists; name/prefix rules | A new/moved source family can make the development gate select too little. | Namespace dependency graph plus canonical runner roots; unknown executable input widens all. |
| 8 | The public-contract gate reparses source and accepts an empty subject census (`test/seon/public_contract_test.clj:39-81`). | Duplicated mechanism; absence as health | Contract coverage can false-green if source discovery fails. | Query canonical program analysis and assert nonempty identified subjects before completeness. |
| 9 | Opaque Flow generators reuse one mutable executor/graph/channel forever and `var-process` contracts its Var as `:any` (`src/seon/flow.clj:56-107`). | Contract quality; dishonest generators | Schema-driven properties cannot expose lifecycle or freshness failures in Flow handles. | Fresh lifecycle-safe generators or honestly nongenerative opaque contracts; register the Var predicate. |
| 10 | Changed-test child cleanup polls at 10 ms and treats twenty samples as a complete tree (`script/seon/dev/changed_test.clj:243-281`). | Unjustified clocks; polling | Every test run pays timing heuristics and can leak a late descendant. | Exact `ProcessHandle.onExit` completions plus explicit child readiness; retain only a loud external bound. |
| 11 | Render walk/block duplicate cursor and marker mechanisms remain large. | Duplicated mechanisms; oversized functions | Context pagination and truncation fixes can diverge across projections. | Already owned by `value-floor-residue-duplicate-cursors-and-marker-hand-lists.md`; no duplicate note filed. |
| 12 | Agent/Flow tests infer observable graph transitions by polling. | Test smells; unjustified clocks | Timing-sensitive integration tests obscure lifecycle contracts. | Already owned by `observable-graph-transitions-are-polled-in-tests.md`; no duplicate note filed. |
| 13 | The schema projection remains process-global and reconstructable state is cached as authority. | Stored-derived; second registry | Cross-cluster program identity and hot update can disagree. | Already owned by `one-program-graph-is-shared-across-clusters.md`; no duplicate note filed. |
| 14 | MCP first-party frame provenance retains a source-root roster. | Hand list | New source roots can lose error provenance. | Already owned by `mcp-frame-provenance-duplicates-the-program-source-root-roster.md`; no duplicate note filed. |

## New issue clusters

| Issue | Ranked finding | Destination |
|-------|----------------|-------------|
| `render-resolution-and-feed-swallow-failures.md` | 1 | render error-evidence wave |
| `oversight-treats-a-20ms-ping-absence-as-state.md` | 2 | Flow observability wave |
| `session-image-stores-derived-unrestorable-prose.md` | 3 | session-image evidence wave |
| `cluster-toolkit-stores-a-prefix-derived-projection.md` | 4 | context derivation wave |
| `runtime-turn-and-evaluate-kernels-conflate-boundaries.md` | 5 | runtime boundary refactor |
| `config-dial-discovery-has-three-authorities.md` | 6 | config derivation wave |
| `changed-test-selector-classifies-hosts-by-path-prefix.md` | 7 | changed-test selector repair |
| `public-contract-census-can-pass-with-no-subjects.md` | 8 | contract-gate repair |
| `flow-generators-reuse-one-mutable-sample.md` | 9 | contract-generator repair |
| `changed-test-process-cleanup-polls-observable-exit.md` | 10 | changed-test process repair |

## Clock adjudication

The clock census did not label every deadline a defect. Provider HTTP
timeouts in `src/seon/ai.cljc`, prepl/socket deadlines in the MCP/operator
edge, and the changed-test child deadline itself bound genuinely external
state. Render coalescing at `src/seon/render/web.clj:653-661` is a declared
presentation cadence, and provider retry sleeps at
`src/seon/cluster/loop.cljc:1172-1185` come from the admitted retry strategy.
Foreign-child fixture waits remain backstops.

The unjustified clocks are the clocks used as primary truth: oversight's 20 ms
state classifier and changed-test's 10 ms process polls. In-process graph
polling already has an open owner.

## Contract and executor calibration

The current public `defn` census is clean: every observed public function has a
`:malli/schema`. Most `:any` occurrences are genuine arbitrary-value or
third-party boundaries; `var-process` is the exception because its body
immediately proves a tighter predicate. Most `[:maybe ...]` forms are return
contracts for ordinary absence, not database attributes.

Flow workload construction is genuinely strong. `src/seon/flow.clj:83-111`
rejects both missing/`:mixed` workload and non-Var proc steps, and
`test/seon/flow_configuration_test.clj:30-88` first proves a nonempty proc
census before requiring every production proc to be `:io` or `:compute`.
No unpinned production proc was found. The recently landed virtual-I/O change
also removed the platform-pool override that motivated this audit class.

## Honest clean areas

- Database transactions and agent-facing runtime results generally use flat,
  evidence-bearing error maps. The broad render catches are exceptions, not
  the dominant style.
- Process-local atoms mostly own actual handles, registrations, or keyed
  reconstructable caches. The durable toolkit copy and global schema-state
  issue are identifiable exceptions.
- `test/seon/repl_parity_test.clj` and the instruction-call test guard their
  computed censuses with identities/counts before universal assertions.
- Exact-string assertions in printer and Hiccup tests generally protect wire
  bytes or grammar, which is appropriate. The session-image reason strings are
  different because they store rendered diagnosis as database truth.
- Malformed optional provider stream chunks are deliberately skipped at the
  transport fold with standing tests; that is not an accidental catch-and-
  swallow path.
- The fresh code has one Flow proc constructor, one database API, and one
  render contract. The findings above are wrong-shaped seams inside those
  owners, not evidence that the deleted pod mechanisms have been recreated.

## Verification boundary

No production test suite was rerun because this audit changed no live code and
a sibling implementation lane was actively editing four source files. The
deliverable gate is documentation structure plus issue-authority validation.
Sibling rot-audit notes may appear while that gate runs; only failures naming
the ten notes above are owned by this sweep.
