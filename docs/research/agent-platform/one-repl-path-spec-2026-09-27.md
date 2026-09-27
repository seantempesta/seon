---
type: spec
status: revision 3 — the prototype is the implementation; 63 named proofs, all passing, run at default's REPL and in a scratch clone of the SCI fork
created: 2026-09-27
revised: 2026-09-27 (rev 3: Astra V2-1..V2-8 on a531e47ab; owner OQ1, test agents, entities vocabulary, feedback from facts, MCP as a thin wrapper)
lane: one-path-spec-opus
head: 79c10321d (every file:line below is this commit's blob)
jvm: default pid 71396 (root restarted it at 22:01:39Z; earlier pids 29866, 51173, 53665); implementation loaded as new namespaces only; every write through `seon.db/with` or a disposable branch's writer
implementation: tmp/one-path/src (the namespaces that land), tmp/one-path/resources/seon/schemas/seon.batch.edn, tmp/one-path/prototype-only (the pinned-SCI observer adapter, never landed)
proof: tmp/one-path/one_path_proof.clj (`(one-path-proof/prove!)` answers the map of named proofs; tmp/one-path/proof-results.edn, proof-summary.md); the file is not proof.clj because another lane defines a `proof` namespace in the same JVM, tmp/one-path/scratch_rows.clj (SCI fork proofs, scratch-results.edn)
sci-fork: tmp/one-path/sci-observer-v3.diff (src +107/−32, test +139), SCI's suite on tmp/one-path/sci-clone
---

# The one REPL path: spec and instructions

## 0. Answer

**What exists.** A form is read by `cluster.reply/sources`, read again by `sci.eval/evaluate`, admitted per form
through a context diff and a kondo pass, gated on a disposable branch per declaration, analyzed again inside the writer,
and printed twice by MCP. A refused settlement rejects the whole batch and a restoration machine rewinds SCI.

**What replaces it.** One fold, `seon.batch/evaluate-batch`, whose request carries every stage as a value. Each form is
read once at the live cursor (through the forgiving input stage), evaluated once, its writes observed at SCI's producer,
its facts derived, validated against the threaded working value and install-gated *when it is evaluated*; a form whose
facts are refused keeps its value and logs the refusal as its result. The one transaction records the run, its
evaluations and the folded program, and holds only facts already validated. The reply is one data map — the same for a
Seon agent turn and an MCP call — carrying each evaluation's shown text and the feedback queried from the validated
working value the transaction commits.

**Proof.** `(one-path-proof/prove!)` answers **63 named proofs, all passing** in the final run on default (pid 71396):
the corpus, every Astra counterexample (V2-1..V2-8 and R1-R9), every owner behavior (Q1-Q5, OQ1, test agents, feedback,
MCP = agent turn), every entrant, the formerly unproven cases (`future`, two threads, intern arming, captured locals,
extension replay) and the timings. The SCI fork passes SCI's own suite: 397 tests, the same 5 failures as the pinned
fork's 393. One owner question remains (§12): `seon.db`'s projection cache loses its committed bases to a batch's
speculative schema values (reproduced at 642–659 ms per later write against 30–31 ms; in the final run the defn after a
schema batch took 428–434 ms against 226–237 ms).

**Size.** Measured on HEAD 79c10321d: the scripted deletion removes **2,286** production lines (96 defns and the rest of
`cluster/reply.clj`); the implementation adds **1,642** (six new namespaces, 93 contracts and the docstrings included) and 188
schema lines. With the range-counted and estimated conversions (§10.1), production src+script is **≈ −900**, tests
**≈ −290**, the SCI fork +107/−32 (+139 test).

## 1. Rulings and findings answered

Each answer names the proof (§9) that shows it. Where a proof runs on the pinned SCI of today's default, the observer is
`prototype-only/seon/sci/pinned.clj` (an env-watch adapter that never lands); the producer observer's own proofs run
in the scratch clone of the fork.

| Ruling or finding | Answer | Proofs |
|---|---|---|
| **OQ1** (owner): execute every form, save what can be saved; a bad form's error is a logged return value | Facts are validated per form by the writer's own validator (`seon.db/with` on the threaded working value) and install-gated. A refusal is the entry's `:seon.batch/facts-error`, recorded as that evaluation's error; its facts are not proposed. The one transaction carries only validated facts. `restore-evaluations!` and the whole restoration machine are deleted. | `oq1-bad-form-logged`, `entrant-gate` |
| OQ1 — which failures can still reach the writer | (1) **A concurrent write changed a checked basis**: the commit's content is refused on the new head, so `settle` re-derives each form's facts on the head in order and only the forms the head now refuses lose their facts (logged); the rest commit. (2) **Storage fails**: every value is kept; the reply answers `:seon.batch/storage-error`; nothing is retried (recovery closes the run). There is no whole-batch rejection for content. | `oq1-head-moved`, `oq1-storage-failure` |
| **Test agents** (owner; test-agents audit 378b1529f) | The test entrant is an ordinary agent on its own branch and forked context: its body forms go through the same read, evaluate, observe, one transaction and reply; its evaluations are its test evidence. Its recording is the **settle stage value supplied beside evaluate**, committing through the **requesting agent's branch** writer, where that agent's feedback reads it. No test-side runner or recorder. Interface in §6. | `test-agent`, `test-agents-concurrent` |
| **Entities, not rows** (owner) | The spec and the new code say entities, facts and values. The existing registry names `:seon.program/row` and `program/declaration-row` are other owners' names and are cited, not revived. The proof suite answers a map of named proofs. | — |
| **Feedback from facts** (owner; feedback lane 785f28f1e) | One reply key, `:seon.eval/feedback`, queried from the batch's validated working value and scoped by the definitions it changed; `:seon.eval/contract-note` / `:seon.eval/contract-warning` beside it. MCP JVM mode gets it too. G1 is closed: an uncontracted defn is admitted with the note or growing warning, never refused. Interface in §7. | `feedback-from-facts`, `jvm-gets-feedback`, `contract-warning` |
| Found by the feedback lane | `(do (ns …) (defn …))` in one form, and an agent created in `user` running a batch that starts in another namespace, both work: the namespace is observed like any write, the start namespace is a stage value. | `ns-and-defn-in-one-form`, `agent-starts-in-another-namespace` |
| **MCP is a thin wrapper** (owner) | `seon.turn.batch/reply` is the one reply; MCP only encodes it as JSON. Every owner behavior run as an MCP eval and as a Seon agent turn (scripted local provider stub) answers the same reply. What MCP does today that an agent turn does not is listed in §5.2 and each is deleted or moved into the path. | `mcp-equals-agent-turn` |
| **Q1** (A): kondo reads SCI's recorded expansion | The fork's `*expansion-observer*` records `[call expansion]`; the analyzer receives it as that call's hook result only for a call that is a generated definition's origin. | `q1-single-def-kondo`, `expansion-observer`, `corpus-join` |
| **Q2**: forgiving input stage | `seon.sci.forgive`: prose becomes comments, prompt markers go, delimiters are repaired when the rest is structurally clean, anything else is returned unread. Trials are structural only (V2-6). | `q2-live-reply`, `q2-recorded-replies`, `v2-6-*` |
| **Q3**: threaded db-after | Each form reads the working value, which holds every earlier form's validated facts. | `q3-threaded-facts`, `v2-4-*` |
| **Q4**: a storable registered key is an attribute | Installed with no entity-map listing; an unstorable form is a note with the bridge's reason. S2 moves the rule into `compiled-attribute-selection` and deletes the two interim lines in `schema-tx`. | `q4-lone-key` |
| **Q5**: `def`/`defonce` are program entities with edges | Stored with `:seon.fn/calls`; acquired in edge order. Extension entities (defmethod, extend) are deferred: §10 S14. | `q5-def-edges`, `extension-replay` |
| **V2-1** observer inside a retry, behind watches | `eval-def`'s swap only publishes the Var; the root is written after it, on the published Var, under `*def-origin*`; `bindRoot` notifies the observer before watches; arming binds the observer to nil and arms the exact Var. | `v2-1-inherited`, `v2-1-nested-callback`, `v2-1-throwing-watch`, `r1-every-write` |
| **V2-2** provenance | A def the reader made keeps its own span (a macro passing it through does not change that); a def produced by an expansion takes the innermost call's span and `:expanded-by`; the expansion root is recognised by identity (`*expansion-root*`), not by metadata. Captured locals are found with clj-kondo's local analysis; such a definition stays private. A record's helper names its member at its producer and acquires once with its declaration. | `v2-2-single-def-macro`, `v2-2-generated-in-let`, `captured-locals`, `record-acquires-once`, `extensions-marked` |
| **V2-3** value published after fallible bookkeeping | The outcome is published the moment evaluation returns; a misreported read span, an observer failing after the form, a failing printer, a shadowed `ns-name`, and failing settle/conclude/show/fault stages each add a diagnostic and keep every value. | `v2-3-*` |
| **V2-4** schema batching | Every schema version the form registered is applied (declarations and attributes) before the next form evaluates, keyed by content (working projection vs delta). Attribute changes are derived in proportion to the change (`attribute-change-tx`, proven equal to the whole-projection one). | `v2-4-schema-reflush`, `v2-4-register-then-use`, `attribute-change-equivalence` |
| **V2-5** effects in order; read-only evidence | Writes fold in execution order by identity (def, unmap, def again → the second; remove-ns removes its definitions). `read-only?` requires no observed write and no schema change. | `v2-5-remove-rebind`, `v2-5-remove-ns`, `r4-new-then-removed`, `v2-5-read-only-evidence` |
| **V2-6** rereads | Trials use edamame with every semantic hook neutral (tags unread, any alias); the semantic read happens once at the live cursor. | `v2-6-tag-once`, `v2-6-repair-before-require` |
| **V2-7** names, requires, delegates | All new names are `:seon.batch/*` (no collision; every form and contract compiles against the live registry). `seon.turn.batch` requires no `seon.turn`: `seon.turn` passes its writers down as values. The require graph with the landing's new edges has no cycle. Each step retires a name in the same commit as its last caller (§10). | `v2-7-schema-names`, `v2-7-no-reverse-requires` |
| **V2-8** recording split | Every entrant records through the same settle stage (JVM runs and test agents included); a nested render contributes to its run's one transaction. | `v2-8-jvm-recorded`, `v2-8-nested-render-one-tx`, `test-agent` |
| **R1–R9** (rev 2) | Carried: R1/R2 by the producer observer and the join; R3 by V2-3; R4/R5 by V2-5 and `r5-namespace-facts`; R6 by §10's manifest; R7 by §5; R8 by `r8-render-read-only`; R9 by the committed suite. | as listed |
| Formerly unproven | `future` conveys the observer (Clojure binding conveyance); two threads in one context each see only their own writes; an intern over a contracted defn is armed at once and never persisted as the defn's source; captured locals stay private; extension replay is a recorded decision (deferred). | `future-conveys`, `two-threads-one-context`, `r2-rebound-intern`, `captured-locals`, `extension-replay` |

## 2. The implementation

Six namespaces land as new files; they touch no existing namespace except through the calls listed in §10.

| Namespace (file, lines) | Role | Public functions |
|---|---|---|
| `seon.batch` (`src/seon/batch.clj`, 306) | The fold: order and totality only. Requires nothing of Seon. | `evaluate-batch`, `diagnostic` |
| `seon.sci.forgive` (`src/seon/sci/forgive.clj`, 269) | The forgiving input stage in front of the live reader. | `next-form`, `read-state`, `version`, `skip-to`, `structure?`, `forms` |
| `seon.sci.batch` (`src/seon/sci/batch.clj`, 73) | SCI stage values: live read, one evaluation under the kernel arm against the working value (registration delta over its projection), current namespace, batch namespace binding. Requires no evaluator owner, so `seon.sci.eval` can build acquisition from it. | `read-next`, `read-code`, `evaluate`, `current-ns`, `around`, `parse-options` |
| `seon.sci.arm` (`src/seon/sci/arm.clj`, 91) | Arming at the write, on the exact Var; marks the evaluated Var installed; the arming projection (working projection plus the delta's changes). | `arm!`, `arming-projection`, `write-event`, `changed-namespaces` |
| `seon.sci.observe` (`src/seon/sci/observe.clj`, 34) | The write-observer stage over the fork's `sci.lang/*write-observer*`. | `observe` |
| `seon.turn.batch` (`src/seon/turn/batch.clj`, 869) | Program stages (derive, facts, apply with the install gate, fold, settle, conclude, show), the one reply, and every entrant's request. | `derive-form`, `form-facts`, `apply-facts`, `fold-change`, `settle`, `conclude`, `show`, `reply`, `read-only?`, `join-write`, `captured-locals`, `attribute-change-tx`, `analyzed-entities`, `program-facts`, `record-facts`, `program-request`, `turn-request`, `code-request`, `jvm-request`, `render-request`, `acquire-request`, `test-request` |

Schemas: `resources/seon/schemas/seon.batch.edn` (188 lines): request, read state and step, text version, repair,
write, def origin, join, change, note, entry, diagnostic, commit, batch, reply. `v2-7-schema-names` compiles every form
and all 93 contracts of the new namespaces against the live registry plus these forms.

## 3. The fold (`seon.batch/evaluate-batch`)

For each top-level form, each step guarded so a throw is a diagnostic and nothing published is lost:

1. **Read** one step at the live cursor (`:seon.batch/read-next`); a span outside the text is a diagnostic and the
   form's own print becomes its source.
2. **Evaluate once** (`:seon.batch/evaluate`) under the write observer (`:seon.batch/observe`), which hands each write
   (bind, unmap, remove-ns, and each namespace whose SCI map changed) to the batch in order and arms each definition at
   its write. **Publish** the value or error at once; an observer failure after the form returned keeps the value.
3. **Place**: source, line, column, namespace before and after.
4. **Show** the entry at once, as a REPL prints (a lazy value is realized before the next form runs).
5. **Derive** the form's program change from its writes (`:seon.batch/derive`); **facts** on the working value;
   **apply** through the writer's validator and the install gate. Accepted: the working value advances and the change
   folds into the batch's program by identity. Refused: the entry's `:seon.batch/facts-error`.

After the loop: **conclude** (feedback queried from the validated working value), then **settle** (the one
transaction; at landing it also saves the feedback's shown text on the last evaluation), then **fault** (each diagnostic through the entrant's fault stage; production supplies `seon.fault/fault!`).

**Deferred gates.** A contracted function calling a Var only declared so far (`declare`) is gated at settlement on the
batch's final working value (`gate-deferred`); corpus item h05 is the case.

## 4. Writer failures (OQ1)

| Failure | Handling | Proof |
|---|---|---|
| A form's facts invalid on the working value | Its facts are not proposed; `:seon.batch/facts-error` is its recorded error; later forms read the value without them | `oq1-bad-form-logged` |
| The install gate refuses a contracted function | Same; the value and the live Var stay (a private definition) | `entrant-gate` |
| The head moved under the batch and the commit is refused | `settle` rebases: each applied change is re-derived as facts on the head in order; forms now refused get their facts error; the rest commit in one transaction | `oq1-head-moved` |
| Storage fails at commit | Values kept; `:seon.batch/storage-error` answered; no retry | `oq1-storage-failure` |

## 5. Entrants

### 5.1 Stage values per entrant

| Entrant | Request builder | Read | Evaluate | Observe | Derive / apply / settle |
|---|---|---|---|---|---|
| Seon agent turn | `turn-request` | forgiving, `:reply` | SCI | producer observer | yes / yes / run + evaluations + program |
| MCP SCI (root or branch) | `code-request` | forgiving, `:code` | SCI | producer observer | yes / yes / same |
| MCP JVM | `jvm-request` | host `read+string` after `skip-to` | host `eval` | none | no / no / the run and its evaluations, with the same feedback |
| Test agent | `code-request` in the test agent's own execution | forgiving, `:code` | SCI on its branch (its body; a resolved body is a thunk evaluate stage, `test-request`) | producer observer | yes / yes / the settle stage beside evaluate, committing on the **requesting agent's branch** |
| Render and context assembly | `render-request` | forgiving, `:code` | SCI on a fork, database-value custody | reported | no / no / none (the owning run records the shown text) |
| Acquisition | `acquire-request` (lands as `seon.sci.eval`'s, from `seon.batch` + `seon.sci.batch`) | code | SCI | none | none (the facts exist) |
| `register!` | inside any batch: the registration delta is bound per evaluation over the working projection | — | — | — | schema facts per form |
| Install gate | inside `apply-facts` (and `gate-deferred`) | — | seeded contract check and reaching tests in a fork with database-value custody | — | a refusal is the form's facts error |
| Merge / write-back | none: merge moves entities by identity (`my.program`, unchanged); write-back's reindex is one analysis of the file's bytes | — | — | — | reindex equals the stored digest |

### 5.2 MCP versus a Seon agent turn: what differs today, and where it goes

| Today (HEAD 79c10321d) | One path |
|---|---|
| `script/seon/dev/mcp.clj:391` `run-form` segments with `seon.cluster.reply/code-sources`, runs `agent/evaluate-source!`, then re-reads the run with `run-observation` | `code-request` → `evaluate-batch` → `reply`; the MCP tool JSON-encodes the reply |
| `mcp.clj:440` `run-events` decodes terminal EDN and re-prints it as `:seon.repl/response` | deleted: the reply's `:seon.batch/shown` is the one print |
| `src/seon/cluster.clj:352` `mcp-project` selects a floor print for MCP and stores cut values as blobs (`:seon.blob/digest`), and `:279` `project-next-prepl-value!` wraps JVM values as `{:seon.dev.mcp/value …}` | moved into the path: the floor's cut and its blob are the show stage's for every entrant (an agent's context reads the same digest); the JVM wrapper is deleted |
| `mcp.clj:376` `jvm-evaluation-form` reads one form, host-evaluates, records nothing | `jvm-request`: several forms, one entry each, recorded as a run |
| MCP's `read_only` flag and windowing (`execute-clj-eval` :544) | read-only is `read-only?` over the batch's observed writes, the same test the refresh classifier uses |
| An agent turn's context reads prose repairs and feedback that MCP never showed | the reply carries `:seon.batch/repairs`, `:seon.batch/unread` and the feedback keys for both |

`mcp-equals-agent-turn` runs eight owner behaviors (OQ1, Q3, Q4, Q5, contract warning, gate, feedback, removal) through
both and finds equal replies apart from transport.

## 6. Test agents: the interface

The test-agents audit (docs/research/agent-platform/test-agents-concurrency-2026-09-27.md, 378b1529f) chose option B:
each test is a one-body agent with its own branch and Flow graph, running through this path. Its measured facts: six
concurrent requests in one JVM run 3.5× faster than serial; the per-branch writer is not a bottleneck; the blocker is
JVM-global test state (263 `with-redefs` uses). This path supplies the agent's side:

- **Entrance**: an isolated agent execution on its own branch (`agent/acquire-context!` with `:seon.agent/isolate?`),
  its context forked there (`sci.eval/fork-cluster-ctx`). The Flow graph around it is that lane's.
- **What the test agent supplies as values** (the `:seon.batch/request` of §2): `:seon.batch/text` (its body: fixtures,
  definitions, assertions as forms), `:seon.sci.eval/ctx` (its fixtures world), `:seon.sci.eval/time-limit-ms` (its
  bound), and, **beside evaluate, its recording as the settle stage**: `:seon.batch/commit!` and `:seon.batch/head` are
  the **requesting agent's branch** writer and value, so `latest-results` and the requester's feedback read the run
  there. `:seon.agent/id` and `:seon.turn/id` name the run.
- **What it gets back**: the batch and the one reply. Its evaluations (values, errors, facts errors) are its test
  evidence. At landing (S12) the member facts `:seon.test.member/*` with `:seon.test.member/reach-digest` and counts are
  written by that settle stage, not by a runner-side `record-tx`.
- Two test agents run at the same time on two branches without sharing definitions and both record on the requester's
  branch (`test-agents-concurrent`).
- Open for that lane: how an assertion reports as data (a failing `is` reports through `clojure.test` today).

## 7. Feedback from facts: the interface

The feedback lane (docs/research/agent-platform/feedback-from-facts-2026-09-27.md, 785f28f1e) owns the queries' design and
the reaching-test speed (the test-feedback-perf lane, not duplicated here).

**The reply keys.** `:seon.eval/feedback` (schema `:seon.batch/feedback`): `:seon.feedback/changed` (the definitions the
batch changed), `:seon.feedback/removed`, `:seon.feedback/missing-contracts` (changed functions without a contract),
`:seon.feedback/reaching-tests` (per changed function, each reaching test with `:seon.feedback/status` `:pass`, `:fail`,
`:stale` or `:never-run`; a test reaching a just-changed definition is stale by construction when it has results).
Beside it `:seon.eval/contract-note` or `:seon.eval/contract-warning` (the growing warning over the branch's agent
definitions). Computed by `seon.turn.batch/conclude` over the batch's validated working value, **before** the one
settlement, so its shown text rides that settlement on the batch's last evaluation. Every entrant's reply carries it,
MCP JVM mode included (`jvm-gets-feedback`); MCP projects it in the final batch event, a Seon agent reads it in its next
context.

**Facts the batch writes so feedback is only a query** (one transaction): each definition's entity, uncontracted ones
included, with `:seon.schema.admission/source :agent` (G1 closed: never refused), `:seon.fn/spec` when contracted,
`:seon.program/definition-digest`, `:seon.program/analyzed-source-digest`, `:seon.fn/calls` and `:seon.fn/call-arities`;
its namespace's entity with its resolver bindings; each schema declaration and attribute; the run and each evaluation.

**Facts added at landing (S7)**, each with its consumer:

| Attribute | Written by | Consumer |
|---|---|---|
| `:seon.cluster.eval/changed` (refs to the definitions the evaluation changed) and `:seon.cluster.eval/removed` (symbols) | `record-facts`, from the evaluation's change | feedback scope from history; `read-only?` over stored facts |
| `:seon.cluster.eval/registered-keys` | same | the same |
| `:seon.eval/feedback-shown` on the batch's last evaluation | `record-facts`, from `conclude` | the agent's next context and the MCP final event read the same saved text |
| `:seon.fn/malformed-spec` and its explanation (G11) | `form-facts`, when arming finds the authored contract malformed | "malformed contracts" feedback; the definition's entity is stored without `:seon.fn/spec` and its Var refuses by name |
| Install-gate test results as `:seon.test.member/*` facts (G5) | the gate's reaching tests run as test agents recording on the requester's branch | a failing redefinition names its test |

## 8. Measurements

Default's JVM (pid 71396, 18 processors), warm, implementation loaded as new namespaces, root's test rounds running.
The suite relieves memory pressure itself (a collection when the old generation retained after the last one exceeds
5 GB), because default refuses new evaluations above its bound and two earlier runs hit it (F-F).

| Operation | Measured (final run) | Notes |
|---|---|---|
| One contracted `defn`, recorded commit | **226–237 ms** warm (first 1,128) | derive 8, validated apply + install gate ~100, settle ~96 |
| The same after a 22-registration batch ran | 428–434 ms (final run); **1,450–1,650 ms** in two earlier runs | F-A |
| The install gate, per new contracted function | ~30 ms | ~300 ms until `arm!` marked the evaluated Var installed (F-D) |
| h36, 22 `register!`, each version applied and validated before the next form | **3.2–5.5 s** across runs | 22 × `seon.db/with` on a new value (~100–190 ms each, F-B). Proportional to schema versions, each validated when its form runs (OQ1). Today 50.8–54.7 s |
| Astra's chained input: 40 contracted defns, 39 pulls, 79 forms | **7.7 s** | 40 × (validated apply + gate ~125 ms); proportional to contracted definitions |
| Acquisition through the one fold | **150 µs per definition** (100 definitions) | code reader, no observation |
| Forgiving stage, 52 recorded replies | 16–26 ms total | |
| Corpus, 94 items | p50 230 ms, p90 433 ms, max 3.3 s (h36) | |
| The suite | ~150 s | run as a future: the MCP tool's bound is 120 s (F-H) |

## 9. The proofs

`(one-path-proof/prove!)` writes `tmp/one-path/proof-results.edn` (the map of named proofs, id → evidence) and
`tmp/one-path/proof-summary.md`. The SCI fork proofs are read from `tmp/one-path/scratch-results.edn`, written by
`tmp/one-path/scratch_rows.clj` in the patched clone. The final run, as written:

| proof | class | pass | ms | evidence |
|---|---|---|---|---|
| `v2-7-schema-names` | static | PASS | 17 | {:taken [], :contract-failures [], :contracts 89, :declared 53, :form-failures []} |
| `entrant-merge-write-back` | entrant | PASS | 542 | {:reindexed [true true]} |
| `r5-namespace-facts` | effects | PASS | 436 | {:facts-errors []} |
| `time-h36` | timing | PASS | 5521 | {:ms 5516, :timings {:read 3.074334, :evaluate 299.271625, :show 78.12721000000002, :derive 192.60295800000003, :apply 4207.3992880000005, :settle 733.319583}} |
| `corpus-join` | observer | PASS | 583 | {:writes 102, :by-join {:matched 95, :member 5, :unanalyzed 2}, :agent-macro [["h40-macro-two-defns" user/h40-x] ["h40-macro-two-defns" user/h40-y]], :bad []} |
| `v2-4-schema-reflush` | schema | PASS | 962 | {:values [":review.v2/n" ":int" ":review.v2/n" "[:int {:min 3}]"], :timings {:read 0.545417, :evaluate 28.008834, :show 11.978917, :derive 20.353876000000003... |
| `oq1-storage-failure` | owner | PASS | 96 | {:commit #:seon.batch{:storage-error "storage unavailable"}} |
| `v2-6-tag-once` | input | PASS | 4 | {:reader-calls 1} |
| `time-one-defn` | timing | PASS | 2064 | {:ms [1128 237 235 232 226], :timings {:read 0.10245800000000001, :evaluate 1.303, :show 1.360417, :derive 7.974792, :apply 101.935375, :settle 96.116958}} |
| `agent-starts-in-another-namespace` | effects | PASS | 3259 | {:values ["#'op3.elsewhere/there" ":here"]} |
| `q2-recorded-replies` | owner | PASS | 30 | {:count 52, :designed [6], :ms 25, :diffs []} |
| `expansion-observer` | sci-fork | PASS | 0 | {} |
| `contract-warning` | owner | PASS | 467 | {:note "user/nc1 has no :malli/schema; specify it before proposing a merge.", :warning [user/nc2 user/nc3]} |
| `r8-render-read-only` | entrant | PASS | 27 | {:values ["#'user/leaked" "{:seon.schema/expected-value :seon.db/connection, :seon.error/operation seon.db/missing-connection-error, :seon.schema/refused-val... |
| `write-after-schema-batch` | timing | PASS | 5561 | {:ms [731 428 434], :timings {:read 0.094874, :evaluate 1.493041, :show 2.618042, :derive 14.653666, :apply 203.148791, :settle 174.177125}, :cause "seon.db/... |
| `extensions-marked` | sci-fork | PASS | 0 | {:protocol-writes [:root :root :extend :extend]} |
| `v2-3-later-stages-fail` | totality | PASS | 119 | {:diagnostics ["show: stage failed" "show: stage failed" "conclude: stage failed" "settle: stage failed" "fault: stage failed" "fault: stage failed" "fault: ... |
| `attribute-change-equivalence` | schema | PASS | 872 | {:results [true true true]} |
| `v2-1-nested-callback` | sci-fork | PASS | 0 | {:events ["outer"]} |
| `future-conveys` | sci-fork | PASS | 0 | {:events [user/fut]} |
| `v2-6-repair-before-require` | input | PASS | 52 | {:values [nil 3 :clojure.string/k]} |
| `q1-single-def-kondo` | sci-fork | PASS | 0 | {:made {:name made, :row 2, :col 1, :defined-by clojure.core/def}} |
| `r2-rebound-intern` | observer | PASS | 365 | {:values ["#'user/review-rebound" "#'user/review-rebound" "[:error \"user/review-rebound refused argument 0: expected an integer, got a string. Fix: Supply a... |
| `test-agent` | entrant | PASS | 1422 | {:values ["#'user/op3-add" "true" "false"], :recorded 3} |
| `entrant-mcp-sci-recorded` | entrant | PASS | 298 | {:evaluations 2, :commit #:seon.batch{:applied true, :tx-count 2}} |
| `v2-1-inherited` | sci-fork | PASS | 0 | {:value 12, :events [{:same-var? true, :observed 2}]} |
| `v2-4-register-then-use` | schema | PASS | 560 | {:values [":op3.h16/n" "#'user/h16f" "4" "[:error \"user/h16f refused argument 0: expected :op3.h16/n (an integer at least 0), got an integer -1. Fix: Supply... |
| `captured-locals` | observer | PASS | 326 | {:private #:user{capture-f :captures-locals}} |
| `jvm-gets-feedback` | entrant | PASS | 21 | {:feedback #:seon.feedback{:changed []}} |
| `corpus` | corpus | PASS | 26530 | {:same 84, :designed 10, :facts-errors [["h32-multi-arity" ["Function install refused for user/h32-f: its gate failed. Fix the contract or the function and r... |
| `oq1-bad-form-logged` | owner | PASS | 880 | {:facts-errors ["Schema reference :no.such/key is not declared in the candidate projection; it is named by user/oq1bad. Declare :no.such/key in the same chan... |
| `extension-replay` | decision | PASS | 297 | {:decision "S16 needs a producer event for addMethod/removeMethod and extend in the SCI fork before any extension entity; deferred to its own spec"} |
| `v2-7-no-reverse-requires` | static | PASS | 1611 | {:cycles []} |
| `v2-5-read-only-evidence` | effects | PASS | 676 | {:read-only [false false false true]} |
| `jvm` | context | PASS | 0 | {:before {:heap-used-mb 4065, :old-gen-after-gc-mb 4053, :processors 18}, :after {:heap-used-mb 7844, :old-gen-after-gc-mb 4942, :processors 18}} |
| `time-acquisition` | timing | PASS | 15938 | {:entities 100, :ms 15, :us-per-entity 150} |
| `q5-def-edges` | owner | PASS | 1278 | {:calls [[clojure.core/def] [clojure.core/def user/base-v] [clojure.core/defonce clojure.core/inc user/base-v]], :acquired [[42 {:k 41}]]} |
| `v2-3-show-fails` | totality | PASS | 47 | {:shown ["()" "42"], :diagnostics []} |
| `v2-5-remove-rebind` | effects | PASS | 519 | {:source "(def rr-v2 2)"} |
| `record-acquires-once` | observer | PASS | 333 | {:sources ("(defrecord OpR [a])"), :acquired [7]} |
| `mcp-equals-agent-turn` | entrant | PASS | 3904 | {:behaviors 8, :differ []} |
| `ns-and-defn-in-one-form` | effects | PASS | 1996 | {:facts-errors []} |
| `entrant-gate` | entrant | PASS | 592 | {:facts-errors ["Function install refused for user/gt1: its gate failed. Fix the contract or the function and re-evaluate the defn."]} |
| `v2-5-remove-ns` | effects | PASS | 1213 | {:namespace-facts nil, :commit #:seon.batch{:applied true, :tx-count 1}} |
| `v2-8-jvm-recorded` | entrant | PASS | 34 | {:values ["" "A" "[:error \"Divide by zero\"]" "3"], :commit #:seon.batch{:applied true, :tx-count 1}} |
| `r4-new-then-removed` | effects | PASS | 1142 | {} |
| `sci-suite` | sci-fork | PASS | 1 | {:patched {:tests 397, :assertions 1517, :fail-count 5, :errors 0}, :base {:tests 393, :assertions 1485, :fail-count 5, :errors 0}, :failures ["FAIL in (arit... |
| `v2-3-ns-name-shadow` | totality | PASS | 284 | {:values ["42" "#'user/ns-name" "43"]} |
| `q4-lone-key` | owner | PASS | 1092 | {:attribute #:db{:valueType :db.type/long, :cardinality :db.cardinality/one}, :notes [{:seon.schema/key :one.path.q4/f, :seon.schema/installed? false, :seon.... |
| `r1-every-write` | sci-fork | PASS | 0 | {:retained [{:root 2, :origin {:line 1, :column 1, :end-line 1, :end-column 10}} {:root 3, :origin nil} {:root 4, :origin nil}]} |
| `v2-3-observer-fails` | totality | PASS | 33 | {:diagnostics ["observe: stop failed"]} |
| `v2-8-nested-render-one-tx` | entrant | PASS | 256 | {:commits 1, :values ["#'user/nr" "[\"3\"]"]} |
| `q2-live-reply` | owner | PASS | 572 | {:repairs [:prose :prompt-marker :delimiters :prose], :text "; Let me require it first.\n(require '[clojure.string :as str])\n::str/k\n(+ 1 2)\n(defn bad [x]... |
| `test-agents-concurrent` | entrant | PASS | 659 | {:branches [":agent-b9393a02b3b2" ":agent-670cce12031d"]} |
| `v2-2-generated-in-let` | sci-fork | PASS | 0 | {:origins [{:line 3, :column 3, :end-line 3, :end-column 9, :expanded-by pair} {:line 3, :column 3, :end-line 3, :end-column 9, :expanded-by pair}]} |
| `v2-2-single-def-macro` | sci-fork | PASS | 0 | {:origin {:line 2, :column 1, :end-line 2, :end-column 9, :expanded-by single}} |
| `oq1-head-moved` | owner | PASS | 1433 | {:facts-errors ["Program retraction refused."], :first-refusal "Program retraction refused."} |
| `v2-1-throwing-watch` | sci-fork | PASS | 0 | {:answer "watch failed", :events [{:op :bind, :root 2}]} |
| `v2-3-span-invalid` | totality | PASS | 34 | {:diagnostics ["read: the read stage answered span [0, 999) outside its text of 2 characters"]} |
| `feedback-from-facts` | owner | PASS | 1782 | {:feedback #:seon.feedback{:changed [user/fb-f user/fb-g], :missing-contracts [user/fb-f], :reaching-tests #:user{fb-f [{:seon.test/sym user/fb-t, :seon.feed... |
| `q3-threaded-facts` | owner | PASS | 230 | {:values ["#'user/q3f" "First doc." "#'user/q3f" "Second doc."]} |
| `two-threads-one-context` | sci-fork | PASS | 0 | {:counts [50 50]} |
| `time-chained-40` | timing | PASS | 7680 | {:ms 7655, :forms 79, :timings {:read 6.653331000000002, :evaluate 146.090003, :show 135.055578, :derive 949.467046, :apply 4935.947501000001, :settle 293.28... |

63/63 proofs pass


## 10. The instructions

Every step cites HEAD 79c10321d, lints `src script test` with `--cache false`, compiles touched schemas and contracts
with their consumers from a `git archive` of HEAD plus the diff, and converts every caller of every name it deletes —
source, tests, quoted symbols, schema and task references — in the same commit. The complete reference manifest is
`tmp/one-path/manifest-v3.edn`: **126 references in 22 files (46 src, 80 test)**, kondo's var usages of every deleted
definition plus every `(:require [seon.cluster.reply …])`, each assigned to the step that deletes its target.

| Step | What | Deletes (measured lines) | Converts | Focused tests |
|---|---|---|---|---|
| **S1** SCI fork | Apply `sci-observer-v3.diff` (src +107/−32, `sci.write-observer-test` +139); push the fork; bump the gitlink | — | — | SCI's suite (397 tests, the pinned 5 failures) |
| **S2** Q4 | `schema/datahike.clj:225` `compiled-attribute-selection`: an agent-admitted storable key is an attribute (+4); delete the two interim lines in `seon.turn.batch/schema-tx` | 2 | — | `seon.schema.datahike-test`, a regression from `q4-lone-key` |
| **S3** new files | Add the six namespaces and `seon.batch.edn`. Make public (one token each): `eval.clj` `reader-context` :1092, `binding-rows` :1177, `namespace-context-row` :1266, `instrumentation-config` :1332, `install-function-contract!` :1346 (and read `:seon.sci.eval/sci-var` when supplied, +1); `fn.clj` `runtime-analysis-batch` :943 and the five analysis helpers `analyzed-entities` calls; `my/program.clj` `deletion-report` :650, `locate`, `blocking?`, `refusal`. Move `captured-locals`' kondo call into `seon.fn.analyzer` (`:locals true` in `analysis-config` :22, +6) | — | — | `seon.batch-test` (the fold proofs), `seon.sci.forgive-test` (52 replies, tag, alias) |
| **S4** product turn | `turn.clj:5056` `evaluate-sources` becomes `evaluate-batch` over `turn-request`, supplying `row-tx` :1285 and `record-evaluated-call` :1569 as values; `resume-turn` :5297 answers the reply | `evaluate-sources` 164, `restore-evaluations!` 19, `with-settled` 8, `settled-value` 10, `settlement-form` 10, `analyze-settlement` 23, `namespace-rows` 20, `evaluated-namespaces` 17, `schema-attribute-change-tx` 43, `affected-schema-attributes` 9 | manifest rows naming these (turn.clj, turn_test, cluster/turn_test, turn_loop_test) | `seon.turn-test`, `seon.cluster.turn-test`, `evaluate-source-test` |
| **S5** gate | the install gate is `apply-facts`' (deferred to settlement for a function calling a declared-only Var); its reaching tests run as test agents whose results are `:seon.test.member/*` facts on the requester's branch (G5) | `gate-function-install` 73 and `eval.clj`'s candidate family (7 defns, 193) | accretion_test, core_functions_test, restoration_test | `seon.test.accretion-test`, from `entrant-gate` |
| **S6** refresh | `read-only-evaluation?` :2075 reads the evaluation's observed writes; `source-key` :1980 keys by `[ns source]` | `source-events` 6, `evaluable-source?` 7 | — | `seon.turn-test` refresh case (from `v2-5-read-only-evidence`) |
| **S7** MCP | `mcp.clj` `run-form` :391, `run-events` :440, `jvm-evaluation-form` :376 become `code-request`/`jvm-request` → `reply` → JSON; `cluster.clj:352` `mcp-project` and `:279` `project-next-prepl-value!` move their floor/blob selection into the show stage. Add the §7 landing attributes (`:seon.cluster.eval/changed`, `removed`, `registered-keys`, `:seon.eval/feedback-shown`, `:seon.fn/malformed-spec`) with their writers | ~180 (range) | mcp bridge tests | `seon.dev.mcp-bridge-test`, a regression from `mcp-equals-agent-turn` |
| **S8** input and render | `agent.clj:659` `segmenter`, `turn.clj` `declared-sources`, `call-turn`, `system-turn` :2230, `web.clj:1384` `render-source-call`, `eval.clj:4059` `evaluated-source` use `seon.sci.forgive` / `render-request` | `reply.clj` (426), `turn.clj` `read-source`…`planned-sources` and `preview-sources` (8 defns, 140) | `(:require [seon.cluster.reply …])` in `turn.clj:16`, `ai_test`, `cluster/turn_test`, `repl_parity_test`; `reply_test` deleted (481) | `seon.render.transcript-test`, `prompt_test`, `loop_proof_test` |
| **S9** acquisition and removal | `eval.clj` `install-function-from-database!` :1414, `install-row!` :1764 evaluate through `seon.batch` + `seon.sci.batch`; `transfer-evaluated-roots!` :1983 reads the batch's writes; `my/program.clj` `ns-unmap!` :718 and `remove-ns!` :734 call SCI's natives (observed) | `native-mutation` 19, `native-call-refusal` 43, `one-event` 49 | eval_test | `seon.sci.eval-test`, from `r4-new-then-removed`, `v2-5-remove-ns` |
| **S10** evaluate | `eval.clj:4380` `evaluate` keeps its kernel arm; its admission body goes | the admission region (27 defns, 565) and `declared-row`/`unmap-row` (126) | eval_test, repl tests | `seon.sci.eval-test`, `seon.repl-test` |
| **S11** reader | `reader.cljc` loses declaration facts and namespace prediction | 19 defns, 316 | `read-events`, `reader-error` | `seon.sci.reader-test` |
| **S12** test agents | `test.clj:1968` `member-result` runs its body as a test agent (§6): the body is the request's text or evaluate stage, its recording the settle stage beside it, committing on the requester's branch; `bounded-result` :218 keeps bound and exit ownership; `runner/record-tx` and `commit-results!` lose their test-side callers | runner-side recording (range per the test-agents design) | test runner tests | `seon.test.one-request-test`, from `test-agent`, `test-agents-concurrent` |
| **S13** proofs as tests | `tmp/one-path/one_path_proof.clj` becomes `test/seon/batch_proof_test.clj` (each named proof a deftest); `scratch_rows.clj` stays the fork's `sci.write-observer-test` | — | — | root's central round |
| **S14** extension entities (deferred) | Requires a producer event for `addMethod`/`removeMethod` and `extend` in the fork before any extension entity; not in this cut (`extension-replay` records the decision) | — | — | — |

Each step up to S11 deletes a name only in the commit that converts its last caller; until then the old function stays
callable (it is not a compatibility delegate: nothing new calls it).

### 10.1 Net lines

| Item | Removed | Added | Kind |
|---|---|---|---|
| Scripted deletion at 79c10321d: 96 defns (`deletions.edn` + `deletions-v3-extra.edn`) and the rest of `reply.clj` | 2,286 | 0 | **measured** |
| The implementation: six namespaces | 0 | 1,642 | **measured** |
| `seon.batch.edn` / retired `seon.sci.eval` and `seon.cluster.reply` schema families | ~80 + 50 | 188 | measured + estimate |
| `evaluate`'s admission body (:4380 region) → per-form stage | ~200 | ~20 | range + estimate |
| MCP `run-events`, `jvm-evaluation-form`, `mcp-project`'s selection, `project-next-prepl-value!` | ~180 | ~20 | range + estimate |
| `my.program` removal path | ~30 | 0 | estimate |
| Caller conversions (46 src references), publicised privates, the §7 attributes' writers | 0 | ~120 | estimate |
| **src + script** | **≈ 2,696** | **≈ 1,802** | **≈ −900** |
| Resources | ≈ 130 | 188 + ~20 | ≈ +80 |
| Tests: `reply_test` 481, `restoration_test` 132, reader facts ~110, eval/accretion ~130 / proofs as tests ~500, conversions ~60 | ≈ 853 | ≈ 560 | ≈ −290 |
| SCI fork | 32 | 107 (+139 test) | measured |

Additions exceed rev 2's estimate because rev 3 does work rev 2 left out: validation and the gate per form, the
recording settle, rebase, feedback, the one reply, and every entrant's request.

## 11. Findings

Root files these; this lane edits no task file.

- **F-A (blocker for per-form schema threading; caused in default by this lane's probes).** `seon.db/nearest-base`
  (`db.clj` near :1437) picks the cached *committed revision key* sharing most revisions; the projection cache holds 24
  entries (`projection-cache-policy` :1345, an LRU outside `seon.cache`). A batch applying 22 schema versions inserts
  speculative revision entries that evict the committed ones; afterwards every speculative value (every `seon.db/with`
  and every in-writer value) derives its projection from a far base: `carried-projection` of the same one-function value
  **30–31 ms before, 642–659 ms after** (reproduced twice; the final run's defn after a schema batch took 428–434 ms against 226–237 ms, so the size of the
  effect depends on what else the cache held). Commit-key hits never re-insert a revision entry, so a JVM stays
  degraded until a new declaration commit or restart. This lane's probes ran such batches in default repeatedly.
  Owner question §12.
- **F-B.** `seon.db/with` on a value first seen costs 65–250 ms (then 11–24 ms warm); per-form threading pays it once per
  schema version (h36: 22 × ~110 ms).
- **F-C.** The floor prints a lazy seq whose realization throws as `()` (`v2-3-show-fails`): an error shown as an empty
  value.
- **F-D.** The install gate cost ~300 ms per new contracted function because its generated calls re-installed the
  function from stored facts (a second evaluation); fixed in `seon.sci.arm/arm!` (mark installed).
- **F-E.** `schema-attribute-change-tx` and `database-attributes-in` compare every attribute of two projections per
  call (75 ms and 35 ms on default); replaced by `attribute-change-tx` (proportional; proven equal).
- **F-F.** Default refused new evaluations under memory pressure twice during this lane's suite runs (G1 old gen
  8.5–8.9 of 11 GB after collection, root's test round running); a collection brought it to 4.2 GB, so the pressure was
  allocation, not retention. The suite now collects between proofs when the retained old generation exceeds 5 GB.
- **F-G.** The working tree's `test/seon/schema/vector_storage_test.clj:561` is unreadable (unmatched brackets), from
  another lane's uncommitted edit; the hook reported it after one of this lane's commands. Not touched.
- **F-H.** The MCP tool's 120 s bound cannot hold the proof suite; it runs as a future and is read from its files.
- **F-J.** Once, in a full suite run, `corpus-join` matched 60 of 102 definition writes; alone and in every rerun
  (including after the same preceding proofs) it matched 95 with 5 members and 2 agent-macro definitions. Suspected
  shared clj-kondo cache state under other lanes' concurrent analyses; not reproduced, so not attributed.
- **F-K.** Another lane defines a `proof` namespace in default's JVM (`proof/proofs` became a function mid-run); this
  suite now lives in `one-path-proof`. Generic scratch namespace names collide in the one shared JVM.
- **F-I.** SCI's own suite has 5 failures on the pinned fork (unchanged): `function-results-dont-have-metadata` ×2,
  `error-location-test` :559/:567, `arity-error-test`.
- Earlier findings still open: `source-rows` throws on a nested `in-ns` (fixed by S3's per-form analysis), a contract
  refusal once stored a 6.4 MB blob, `seon.db/pull` slows with the speculative overlay, the `clojure.core/defn` call edge
  on every `defn` entity.

## 12. Owner question

**F-A: speculative schema values evict `seon.db`'s committed projection bases.** Per-form schema application (V2-4, owner
Q3/OQ1) makes one speculative value per schema version; with the 24-entry cache they push out the committed bases and
every later write in the JVM pays ~650 ms.

- **(A) Keep speculative values out of the revision tier** (`seon.db`: a speculative value's projection is cached by
  content only, never under a revision key): committed bases stay; a speculative chain derives from its committed basis
  (~30 ms per value). ~10 lines, 1 h. Sacrifice: consecutive speculative values in one batch each derive from the
  committed basis rather than their predecessor (22 × ~30 ms in h36).
- **(B, recommended) Derive a speculative value's projection from its own basis value's projection** (its lineage,
  already in its cache context), with no search over the cache: cost follows the change (~3 ms per schema version, the
  measured `arming-projection` cost), independent of what else is cached. ~25 lines, 2 h. Sacrifice: a second derivation
  path beside `load-projection` until the committed path uses it too.
- **(C) Move `seon.db`'s projection cache into `seon.cache`** (one central, byte-weighted cache, as the laws require) and
  give committed bases a retention weight: fixes this and the rule breach together. ~40 lines, half a day. Sacrifice:
  projections weigh ~13 MB each against the 512 MB budget.
