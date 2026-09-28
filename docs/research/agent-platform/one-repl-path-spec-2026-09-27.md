---
type: spec
status: revision 6 — owner reversal (313e81181, b1d63d856): renders return data and nothing evaluates render forms (§7.3); revision 5 — owner ruling: the Var render replaces the feedback stage and the REPL install gate (§7); S1–S3b landed; the proof map (§9) run at default's REPL, the SCI fork proofs in a scratch clone; the supply stage and four root-owned patches (§7.2, tmp/one-path/patches) wait on their owners
created: 2026-09-27
revised: 2026-09-27 (rev 4: Astra's rev-3 execution blockers B1-B7 (f3c1db347); the world value; §12 ruling B; REPL dogfood items 1-6; red-triage r6; feedback from the batch's digests (39d94eef0); the per-world call sink; the supply stage ruling)
lane: one-path-spec-opus
head: 79c10321d for §10's measured deletions; caa379797 for the rev-4 patches (tmp/one-path/patches apply to it)
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

**Proof.** `(one-path-proof/prove!)` answers the map of named proofs in §9 (rev 4: every rev-3 execution blocker,
the world, ruling B, the dogfood items, the r6 reds, the change-judging gate, the hub feedback timing and the landing's
contracts gate). The SCI fork passes SCI's own suite: 397 tests, the same 5 failures as the pinned fork's 393. Three
rev-4 requirements are not provable in default until root-owned patches are installed (§7.2): the supply stage (world
members filled at the armed wrapper), the per-world call sink inside `seon.profile`'s wrapper, and the stored entity of
a macro-generated definition (clj-kondo hooks carried into `seon.fn.analyzer`). Each has a mechanism proof here that
loads the patch's own code; the armed proofs run after the step that installs it.

**Size.** Measured on HEAD 79c10321d: the scripted deletion removes **2,286** production lines (96 defns and the rest of
`cluster/reply.clj`). The implementation now adds **2,071** lines (rev 3: 1,642; +429 for B1-B7, the world, the gate as
test agents, the fault stage, the JVM floor and feedback from digests; §10.1) and 228 schema lines. Production
src+script is **≈ −470**; the rev-4 patches add ≈ +90 in root's files.

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
| **B1** (rev 3, P0) a later print failure erased the value | The outcome is retained at the evaluator's return with a unique object sentinel; the observation, print and bookkeeping that follow add diagnostics to a batch already holding the entry; the unprintable fallback is total (nil prints as `#<unprintable nil>`); a write that is not a map is a diagnostic, never an erasure | `b1-nil-print-failure`, `b1-value-equal-to-sentinel`, `b1-malformed-observation` |
| **B2** (P1) exact span and generated definitions | A complete authored span joins exactly or not at all; the start-only rule is the pinned adapter's alone. The observer stage binds the fork's `*expansion-observer*` and hands each expansion (its call occurrence's span, macro and expansion) as an `:expansion` observation; the per-form analysis carries them as clj-kondo `:analyze-call` hooks keyed by that exact occurrence. The analyzer seam is root's (`analyzer-expansion-hooks.patch`); until it lands the proof is the hook mechanism | `b2-exact-span-no-fallback`, `b2-generated-definition-hook` |
| **B3** (P1) repair re-invoked a tag reader | The delimiter decision is structural (edamame, every semantic hook neutral) and made before the one semantic read; a malformed tag answers its read error once | `b3-repair-reads-tag-once`, `b3-malformed-tag-read-once` |
| **B4** (P1) settlement hid refusals and reported uncommitted facts | `settle` answers the writer's refusal as data (`:seon.batch/writer-refusal`), and a settle diagnostic goes through the fault stage, which records a core fault (`seon.fault/fault!`) in the world's environment; its receipt is in the reply of every entrant. A rebase concludes again from the state that commits | `b4-writer-refusal-reply`, `b4-moved-head-feedback` |
| **B5** (P0) the landing's contracts gate | `seon.contracts-compile-test/check` over `git archive` HEAD plus the six namespaces and the schema, empty baselines: 0 findings (8 before). Worlds validate against `:seon.batch/world`; no member is installed as nil | `b5-contracts-gate`, `world-members-agree` |
| **B6** (P1) JVM mode's own printer | JVM mode shows through the same floor (`shown-result`, `shown-failure`) with the world's profile and the host observation the fold measured (duration, allocated bytes); its local printer and `ERR` stringifier are deleted | `b6-jvm-floor`, `b6-jvm-throwing-print-and-error` |
| **B7** (P1) concurrent and print-time observations | One observation scope per evaluation holds its value's print; writes accumulate in a per-call concurrent queue closed at the scope's completion (a later write is refused to its writer) | `b7-concurrent-observation`, `b7-lazy-definition` |
| **The world** (owner) | One value, `:seon.batch/world`, built from the entrant's execution by one constructor per entrant kind (§7.1); every stage reads it from the request; `with-world` re-establishes it after a thread hop; the fold binds it (its database the working value) around each evaluation and its print | `world-members-agree`, `worlds-never-cross`, `world-survives-compute-hop` |
| **§12 ruling B** | A speculative value derives its projection from its own basis and is never inserted into the committed projection cache: proven with `schema/load-projection` from the basis (a defn after the 22-registration batch stays ~30 ms); the change is `seon.db`'s (root) | `speculative-batch-keeps-committed-projection`, `write-after-schema-batch` |
| **Dogfood 1-4** | The gate runs each reaching test as a test agent in the after world (and, only when something is red, the basis world): the test's stored source re-evaluated in a fork, in its own namespace with that namespace's aliases installed from its entity. It judges the change: only a newly red test blocks; still-red tests are named. Its evidence is data (`:seon.batch/gate`). Auto-check covers the changed definition; a callee's contract finding is feedback | `gate-judges-the-change`, `gate-runs-tests-in-their-namespace`, `gate-callee-contract-is-feedback`, `entrant-gate` |
| **Dogfood 5** turn overhead | Profiled on default: wake-to-open is `system-turn` (3.8-5.2 s), 3.5 s of it `latest-evaluations` decoding every stored read plan of root's history (36.5 MB, one plan 4.5 M chars from the opening's `for-agent` read); the one path reads only the kept evaluations' evidence (same answer, 457 ms, 400 of it that one plan). Open-to-request is the cold prompt, 7.6 s: `of-agent` 3.7 s (the same decode) and `call-reach` ×143 3.1 s. Root-owned remainder in task `a-trivial-agent-turn-takes-eleven-seconds` | `turn-overhead` |
| **Dogfood 6** parity | Both replies carry the fault receipts; the opening reads the inbox (5d95c4913); a refusing renderer is one diagnostic and a fault, never a parked loop; the branch-creation entrance is §5.2 | `parity-faults-in-both-replies`, `r6-e1-opening-reads-the-inbox`, `parity-render-refusal-never-parks` |
| **r6 reds** | The shown text equals the one print owner's rendering; unresolved call and require names are kept as values and mint nothing | `r6-shown-by-the-one-print-owner`, `r6-unresolved-names-kept-as-values` |
| **Feedback from digests** (39d94eef0) | The batch hands `conclude` its changed definitions and their digests; a definition whose digest equals the basis's seeds nothing; a reached test with results is stale without re-deriving reach digests | `feedback-from-facts`, `feedback-hub-fresh` |
| **Call sink** (root) | Each world owns a call sink; the armed wrapper counts into the calling evaluation's sink (`profile-world-sink.patch`) | `sink-per-world` |
| **R1–R9** (rev 2) | Carried: R1/R2 by the producer observer and the join; R3 by V2-3; R4/R5 by V2-5 and `r5-namespace-facts`; R6 by §10's manifest; R7 by §5; R8 by `r8-render-read-only`; R9 by the committed suite. | as listed |
| Formerly unproven | `future` conveys the observer (Clojure binding conveyance); two threads in one context each see only their own writes; an intern over a contracted defn is armed at once and never persisted as the defn's source; captured locals stay private; extension replay is a recorded decision (deferred). | `future-conveys`, `two-threads-one-context`, `r2-rebound-intern`, `captured-locals`, `extension-replay` |

## 2. The implementation

Six namespaces land as new files; they touch no existing namespace except through the calls listed in §10.

| Namespace (file, lines) | Role | Public functions |
|---|---|---|
| `seon.batch` (`src/seon/batch.clj`, 414) | The fold: order and totality only; the world binding (`*world*`), the world-member table and the call sink. Requires nothing of Seon. | `evaluate-batch`, `diagnostic`, `*world*`, `world-members`, `sink`, `calls` |
| `seon.sci.forgive` (`src/seon/sci/forgive.clj`, 282) | The forgiving input stage in front of the live reader. | `next-form`, `read-state`, `version`, `skip-to`, `structure?`, `forms` |
| `seon.sci.batch` (`src/seon/sci/batch.clj`, 73) | SCI stage values: live read, one evaluation under the kernel arm against the working value (registration delta over its projection), current namespace, batch namespace binding. Requires no evaluator owner, so `seon.sci.eval` can build acquisition from it. | `read-next`, `read-code`, `evaluate`, `current-ns`, `around`, `parse-options` |
| `seon.sci.arm` (`src/seon/sci/arm.clj`, 91) | Arming at the write, on the exact Var; marks the evaluated Var installed; the arming projection (working projection plus the delta's changes). | `arm!`, `arming-projection`, `write-event`, `changed-namespaces` |
| `seon.sci.observe` (`src/seon/sci/observe.clj`, 45) | The observer stage over the fork's `sci.lang/*write-observer*` and `*expansion-observer*`. | `observe` |
| `seon.turn.batch` (`src/seon/turn/batch.clj`, 1,166) | The world constructors and `with-world`; program stages (derive, facts, apply with the change-judging install gate, fold, settle, conclude, show, the fault stage), the one reply, and every entrant's request. Over the size a namespace should have: S3 splits the world and the gate into `seon.turn.world` and `seon.turn.gate` before copying. | `agent-world`, `test-agent-world`, `jvm-world`, `read-only-world`, `merge-world`, `with-world`, `expansion-hooks`, `record-fault`, `derive-form`, `form-facts`, `apply-facts`, `fold-change`, `settle`, `conclude`, `show`, `reply`, `read-only?`, `join-write`, `captured-locals`, `attribute-change-tx`, `analyzed-entities`, `program-facts`, `record-facts`, `program-request`, `turn-request`, `code-request`, `jvm-request`, `render-request`, `acquire-request`, `test-request` |

Schemas: `resources/seon/schemas/seon.batch.edn` (228 lines): world, gate, feedback, printed, writer-refusal, sink, request, read state and step, text version, repair,
write, def origin, join, change, note, entry, diagnostic, commit, batch, reply. `v2-7-schema-names` compiles every form
and all 115 contracts of the new namespaces against the live registry plus these forms.

## 3. The fold (`seon.batch/evaluate-batch`)

For each top-level form, each step guarded so a throw is a diagnostic and nothing published is lost:

1. **Read** one step at the live cursor (`:seon.batch/read-next`); a span outside the text is a diagnostic and the
   form's own print becomes its source. The source and position are known here, before evaluation.
2. **Evaluate once and print, in one observation scope.** The fold binds `seon.batch/*world*` (the entrant's world with
   its database the working value) and calls the observer stage around a thunk that evaluates the form, retains its
   outcome at once (a unique object sentinel tells a returned value from none) and prints it through the show stage.
   Writes (bind, unmap, remove-ns, namespace, expansion) accumulate in a per-call concurrent queue, from any thread the
   form starts; the scope completes when the observer returns, after the print, and a later write is refused to its
   writer. **Publish** the entry: a value (even nil, even equal to any keyword), or the error. An observer failure after
   the form returned, a print failure, the printer's own diagnostics (a renderer that refused) and a write that is not a
   map each add a diagnostic. Every entry carries its measured evaluation time and allocated bytes.
3. **Place**: the namespace before and after.
4. **Derive** the form's program change from its writes (`:seon.batch/derive`); **facts** on the working value;
   **apply** through the writer's validator and the change-judging install gate. Accepted: the working value advances
   and the change folds into the batch's program by identity. Refused: the entry's `:seon.batch/facts-error`.

After the loop: **conclude** (feedback queried from the validated working value, from the changed definitions and
digests the batch holds), then **settle** (the one transaction; a rebase concludes again from what commits; a refusal is
data), then **fault** (each diagnostic recorded by `seon.fault/fault!` in the world's environment; the receipts ride the
reply).

**Deferred gates.** A contracted function calling a Var only declared so far (`declare`) is gated at settlement on the
batch's final working value (`gate-deferred`); corpus item h05 is the case.

## 4. Writer failures (OQ1)

| Failure | Handling | Proof |
|---|---|---|
| A form's facts invalid on the working value | Its facts are not proposed; `:seon.batch/facts-error` is its recorded error; later forms read the value without them | `oq1-bad-form-logged` |
| The install gate refuses a contracted function | Same; the value and the live Var stay (a private definition) | `entrant-gate` |
| The head moved under the batch and the commit is refused | `settle` rebases: each applied change is re-derived as facts on the head in order; forms now refused get their facts error; the rest commit in one transaction | `oq1-head-moved` |
| Storage fails at commit | Values kept; `:seon.batch/storage-error` answered; no retry | `oq1-storage-failure` |
| The writer refuses the recording itself | Values kept; `:seon.batch/writer-refusal` in the reply's commit; a settle diagnostic recorded as a core fault, its receipt in the reply | `b4-writer-refusal-reply` |

## 5. Entrants

### 5.1 Stage values per entrant

| Entrant | Request builder | Read | Evaluate | Observe | Derive / apply / settle |
|---|---|---|---|---|---|
| Seon agent turn | `turn-request` | forgiving, `:reply` | SCI | producer observer | yes / yes / run + evaluations + program |
| MCP SCI (root or branch) | `code-request` | forgiving, `:code` | SCI | producer observer | yes / yes / same |
| MCP JVM | `jvm-request` | host `read+string` after `skip-to` | host `eval` | none | no / no / the run and its evaluations, with the same feedback; shown by the same floor (B6) |
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
| MCP carries fault entries an agent's context lacked (dogfood 6) | every entrant's diagnostics are recorded by the fault stage and their receipts ride the one reply |
| MCP's `branch` tool (`mcp.clj:477` `branch-form`) creates a branch; an agent has no entrance (dogfood 6) | one function in `my.agent` (root's file) that both call: `branch-form`'s create/list/retire body moves there and the tool calls it. Not proven here: `my/agent.clj` is not this lane's |

`mcp-equals-agent-turn` runs nine owner behaviors (OQ1, Q3, Q4, Q5, contract warning, gate, feedback, removal, a refusing
renderer) through both and finds equal replies apart from transport (timings, fault occurrence ids, printed instants).

## 6. Test agents: the interface

The test-agents audit (docs/research/agent-platform/test-agents-concurrency-2026-09-27.md, 378b1529f) chose option B:
each test is a one-body agent with its own branch and Flow graph, running through this path. Its measured facts: six
concurrent requests in one JVM run 3.5× faster than serial; the per-branch writer is not a bottleneck; the blocker is
JVM-global test state (263 `with-redefs` uses). This path supplies the agent's side:

- **Entrance**: an isolated agent execution on its own branch (`agent/acquire-context!` with `:seon.agent/isolate?`),
  its context forked there (`sci.eval/fork-cluster-ctx`). The Flow graph around it is that lane's.
- **Its world** (`test-agent-world`): its own execution's branch, connection, forked context and call sink, and
  `:seon.batch/settlement` naming the **requesting agent's branch** connection: the settle stage, supplied beside
  evaluate, commits there, so `latest-results` and the requester's feedback read the run there. Its sink counts only
  its own armed calls (`sink-per-world`), which retires the JVM-wide counters two concurrent members share (task
  5b1b9776f).
- **The install gate's reaching tests are test agents** (dogfood 1): each test's stored source is evaluated in a fork of
  the after world, in its own namespace with that namespace's aliases and refers installed from its entity, then run;
  the basis world runs only when something is red after.
- **What it gets back**: the batch and the one reply. Its evaluations (values, errors, facts errors) are its test
  evidence. At landing (S12) the member facts `:seon.test.member/*` with `:seon.test.member/reach-digest` and counts are
  written by that settle stage, not by a runner-side `record-tx`.
- Two test agents run at the same time on two branches without sharing definitions and both record on the requester's
  branch (`test-agents-concurrent`).
- Open for that lane: how an assertion reports as data (a failing `is` reports through `clojure.test` today).

## 7. The Var render: what a definition's reply says (owner ruling, rev 5)

**What replaces feedback and the REPL install gate.** A `defn` returns its Var; the Var's declared render pair says
what matters about it, and says nothing when nothing changed. The AI render (data for the value printer: the Var's
symbol, its status and the forms that act on it, as data) and the HTML render (hiccup) share one selection, one set of
facts and one query; only the final output differs. Neither runs anything. There is no feedback stage, no
reply key for it, no special uncontracted-defn warning and no REPL install gate: the REPL always admits, and merge
acceptance stays the single refusal point (red reaching tests, missing contracts).

**What the Var render shows**, each only when it has something to say:

| Part | Source | Shown when |
|---|---|---|
| Contract | the definition's entity: `:seon.fn/spec`, or none, or `:seon.fn/malformed-spec` | missing (with an example form) or malformed |
| Reaching tests at this digest | test results as a **derived value keyed by exercised digests**: a read with a stored result for the function's current digest is a pure read; a read with none answers pending. The render starts nothing: the definition's batch schedules its reaching test agents as an effect of the definition (S12, recording on the requester's branch, §6) | a result exists or is pending; a pending or red test carries `(my.test/check {:seon.test/changed [sym]})` as data |
| Delta | this digest's results against the previous digest's | a test turned red or green; nothing changed shows nothing |

**Late results.** A render made before the tests finish shows them pending. The render's read of the results is
ordinary read evidence, so when the test agents record, the changed-read refresh delivers the results in the next
context. No new delivery mechanism.

**Deleted by this ruling** (measured at 2fbaf89c6): `seon.turn.gate` (135 lines: the change-judging gate,
`gate-deferred`, `test-outcomes`), `apply-facts`' gate calls and the deferred-callee tracking in `derive-form`,
`conclude` with `reaching-tests` (75 lines), the `:seon.eval/feedback`, `:seon.eval/contract-note` and
`:seon.eval/contract-warning` reply keys and their schemas (`:seon.batch/feedback`, `:seon.batch/gate`), and in the old
path `seon.sci.eval/missing-contract`'s refusal (the live refusal of an uncontracted defn) and `gate-function-install`.
The proofs of the gate and the feedback stage (`gate-*`, `entrant-gate`, `feedback-*`, `jvm-gets-feedback`,
`contract-warning`, `c4-deferred-gates`) are replaced by the render proofs below.

**The same mechanism elsewhere** (render pairs over values, each a follow-up with its owner):

| Value | Its render shows | Owner |
|---|---|---|
| `ns-unmap` / `remove-ns` result | the callers the removal breaks | `my.program` |
| `register!` result | the schema's users and how many stored entities would fail it | `seon.schema` |
| `in-ns` result | the namespace's `dir` with contracts, tasks and the missing-contract count | `seon.repl` |
| an error value | its linked task, prior occurrences, the contract and the fix | `seon.error` |
| a `deftest` Var | its latest result | `seon.test` |
| a task | its done-when as a live check | `seon.task` |
| a merge proposal | its diff and the reaching results | `my.program` |

### 7.3 Renders return data (owner reversal, rev 6)

A render function takes the value and returns data: the AI face (`:seon.render/ai`) the pruned, processed data the
value printer prints; the HTML face hiccup; the form face (`:seon.render/form`) the form that finds this data again,
shown as data beside the value. Renders are read-only (a database value, no connection or writer), run in the viewer's
world, have their reads captured as read evidence on the shown unit, are cached by content, and the printer never
re-dispatches into their output. **Nothing evaluates render forms**: B1's data-form path and `:seon.render/entries`
are deleted (rev 6), and the text path — `seon.sci.eval/evaluated-source`, render.clj's `source-producer?` /
`source-return?` / `:seon.sci.eval/evaluate-source` branch of `declared-projection`, `:seon.render.call/source-output?`
and `:seon.render/source-blocks` — is deleted in P1 once its producers return data (below). Until then the AI face
accepts data beside text (`render-ai-data-face.patch`, render.clj): any value but an error value, printed as data.

**The producers that return REPL entries today** (each becomes an AI data render; its forms move to
`:seon.render/form`, the "how to find this" face). Measured on the working tree 2026-09-27:

| Producer | Value | Its form today |
|---|---|---|
| `seon.render.ns/render-ai` :300, `function-ai` :230, `schema-ai` :189, `render-import-ai` :83 | curated namespace (`require` + `dir`), function, schema, import | `seon.render.ns/namespace-form`, `schema-form` |
| `seon.cluster.message/render-inbox-ai` | inbox (`(my.message/inbox {...})`) | `seon.cluster.message/inbox-form` |
| `seon.note/render-notes-ai` :60 | notes | `seon.note/render-notes-form`, `render-note-form` |
| `seon.plan/render-plan-ai` :1538, `render-ready-items-ai` :1470 | plan, ready items | — |
| `seon.cluster.agent/render-identity-ai` :292, `render-id-ai` :320 | agent identity | — |
| `seon.agent/render-settings-ai` :163 | settings | — |
| `seon.cluster.status/render-ai` :178, `seon.cluster/render-ai` :205 | cluster status, cluster | — |
| `seon.render.test/render-ai` :42 | test results | — |
| `seon.render.transcript/render-runtime-ai` :1032 | runtime | — |
| `turn.clj` `declared-sources` (the opening's evaluated units) | the opening | — |

**Proofs, armed, through both entrants and the HTML render:** a defn with no contract shows the missing contract in AI
and HTML; a defn whose change turns a reaching test red shows that test, and an unchanged one shows nothing; pending
results arrive in the next context through refresh; an uncontracted defn is admitted.

### 7.1 The world

One value, `:seon.batch/world`, extends `:seon.db/custody-request`. Every stage reads it from the request; nothing is
read from an ambient binding except `seon.batch/*world*`, which the fold binds (its database the working value) and
`with-world` re-establishes after a thread hop.

| Member | Agent turn / MCP SCI | MCP JVM | Test agent | Render, gate (`read-only-world`) | Merge |
|---|---|---|---|---|---|
| `:seon.boot/cluster-name`, `:seon.agent/branch` | the execution's | the cluster | its execution's | the viewer's | root's merge branch |
| `:seon.db/connection` | the execution's branch | the cluster's | its own branch | none (a write refuses) | the merge branch |
| `:seon.db/db` | its connection's value; the working value per form | the cluster's value | its branch's value | the given value | the merge branch's value |
| `:seon.db/writer` | `seon.db/transact!` unless supplied | same | same | none | same |
| `:seon.sci.eval/ctx`, `:seon.sci.eval/base-ctx` | forked on its connection; the cluster context | none (the host evaluates) | forked on its branch | a fork with database-value custody | forked on the merge branch |
| `:seon.agent/id`, `:seon.task/id`, `:seon.turn/id` | the agent's, when given | same | the requester names them | the viewer's | root |
| `:seon.render/profile` | when carried; else derived from the working value and cluster | same | same | same | same |
| `:seon.env/environment`, `:seon.db.process/id` | the execution's (the fault stage records there) | the cluster's | its execution's | the viewer's | the execution's |
| `:seon.batch/sink` | a new sink | a new sink | its own sink | none | a new sink |
| `:seon.batch/settlement` | — | — | the requester's connection | — | — |

Constructors: `agent-world`, `test-agent-world`, `jvm-world`, `read-only-world`, `merge-world` (`seon.turn.batch`). A
constructor installs no member as nil. Proofs: `world-members-agree` (every member agrees and validates for every
entrant), `worlds-never-cross` (two clusters, two concurrent test agents), `world-survives-compute-hop`.

### 7.2 The supply stage and the root-owned patches

**The ruling.** An input a called function declares under a world member's schema name (`seon.batch/world-members`:
database value, connection, writer, agent, task, profile, call sink, SCI context), required or optional, positional or a
key missing from a map argument whose schema declares it, is filled from the calling evaluation's world **when the
caller omitted it**. A value the caller passes is never replaced. Supply runs before input validation in the armed
wrapper, planned at arm time and filled at call time (a call's target is known only at run time). Other omitted optional
inputs stay absent. A function whose meaning needs a member absent declares another schema name.

**What exists.** `seon.call-preparation` already plans at arm time and fills omitted positional inputs and missing
required map entries before `wrapped` validates, never replacing a present key (`prepare`, call_preparation.clj:913).
Its suppliers read the environment `seon.instrument/calling-environment` derives from the kernel arm's context and the
dynamic custody bindings; JVM mode and host threads have no arm and are never prepared. `read-evidence-current?`
declares its database as `[:or database-value error]`, which no schema name identifies, so it alone is refused on arity.

**The patches** (tmp/one-path/patches, each `git apply --check` clean at caa379797, each for its owner):

| Patch | Owner | What |
|---|---|---|
| `supply-from-the-world.patch` | `seon.instrument`, `seon.call-preparation` | `calling-environment`'s one source is `seon.batch/*world*` (every entrant: SCI, JVM, test agents); an optional map entry whose schema is a world member is planned like a required one |
| `read-evidence-current-supplied-db.patch` | `seon.db` | its database is a declared `:seon.db/database-value` (supplied like `q`'s); the error pass-through goes |
| `profile-world-sink.patch` | `seon.profile` | the armed wrapper also counts into the calling evaluation's sink (a dynamic read and one increment, no armed call) |
| `analyzer-expansion-hooks.patch` | `seon.fn.analyzer`, `seon.fn` | `analyze` merges `::hooks` into its clj-kondo config; `runtime-analysis-batch` builds them from each request's observed expansions at that form's first row (`expansion-hooks` moves there from `seon.turn.batch`) |

The functions that declare an optional world-member input today are listed in
`tmp/one-path/optional-world-members.edn` (127 functions from the live contract registry, by input map); the supply
step classifies each (absence meaningful → a new schema name) in the same commit.

**Proofs for the supply step** (armed, after it installs): each member supplied when omitted; on an agent's branch the
supplied database is that branch's working value; two concurrent test agents each receive their own; after a compute
hop the supplied values are still the world's; the caller's value (another branch's database, an explicit connection)
wins; a map missing `:seon.db/connection` gets the world's and validates; a non-member optional stays absent; no
prefixing when an arity matches; MCP and an agent turn supply identically; a member the world lacks refuses by name
with the missing schema key; a "no custody" function under its new name still refuses.

## 8. Measurements

Default's JVM (18 processors), warm, implementation loaded as new namespaces, root's test rounds running. The suite
relieves memory pressure itself (a collection when the old generation retained after the last one exceeds 5 GB).

| Operation | Measured (rev 4 final run) | Notes |
|---|---|---|
| One contracted `defn`, recorded commit | **214–248 ms** warm | derive 7, validated apply + install gate ~80, settle ~126 |
| The same after a 22-registration batch ran | 218–279 ms | ruling B's mechanism keeps it at the committed basis (~30 ms projection) |
| h36, 22 `register!`, each version applied and validated before the next form | **6.1 s** | 3.2–5.5 s in rev 3; apply 4.1 s (22 × `seon.db/with`, F-B), settle 1.7 s. Today 50.8–54.7 s |
| Astra's chained input: 40 contracted defns, 39 pulls, 79 forms | **15.6 s** (rev 3: 7.7 s) | profiled: 13.8 s in `schema/load-projection` ×42 under `seon.db/with` ×40, a full rebuild per speculative value: F-A, after the suite's schema batches evicted the committed bases. Ruling B (root's `seon.db` change) removes it |
| Reaching-test feedback, fresh head | 10 tests 19 ms, 22 tests 13 ms, 150 tests ~15 ms | from the batch's digests (`feedback-hub-fresh`) |
| Turn prelude history read (root, 146 evaluations) | 3.5–4.2 s today, **0.46–0.63 s** one path | 0.40 s of it is one 4.5 M-char stored plan (F-L) |
| Acquisition through the one fold | **110 µs per definition** (100 definitions) | code reader, no observation |
| Batch retained heap | not measurable by heap delta in the shared JVM (−49.6 and −2.4 MB: other threads freed more than the batch held) | an object-graph measurement of the batch value replaces it at S13 |
| Corpus, 94 items | 50.4 s total, each item on its own branch (F1) | |
| The suite | ~6 min | run as a future: the MCP tool's bound is 120 s (F-H) |

## 9. The proofs

`(one-path-proof/prove!)` writes `tmp/one-path/proof-results.edn` (the map of named proofs, id → evidence) and
`tmp/one-path/proof-summary.md`. The SCI fork proofs are read from `tmp/one-path/scratch-results.edn`, written by
`tmp/one-path/scratch_rows.clj` in the patched clone. The final run, as written:

| proof | class | pass | ms | evidence |
|---|---|---|---|---|
| `v2-7-schema-names` | static | PASS | 233 | {:taken [], :contract-failures [], :contracts 113, :declared 59, :form-failures []} |
| `entrant-merge-write-back` | entrant | PASS | 585 | {:reindexed [true true]} |
| `r5-namespace-facts` | effects | PASS | 287 | {:facts-errors []} |
| `time-h36` | timing | PASS | 6362 | {:ms 6103, :timings {:read 2.248624, :evaluate 204.43566699999997, :derive 78.58212499999999, :apply 4069.2349169999998, :settle 1747.907958}} |
| `b7-lazy-definition` | effects | PASS | 469 | {:shown ["(42)"], :diagnostics []} |
| `corpus-join` | observer | PASS | 600 | {:writes 102, :by-join {:matched 95, :member 5, :unanalyzed 2}, :agent-macro [["h40-macro-two-defns" user/h40-x] ["h40-macro-two-defns" user/h40-y]], :bad []} |
| `world-members-agree` | world | PASS | 883 | {:worlds {:agent-turn {:db-is-connections true, :ctx-custody-is-connection true, :writer-takes-connection true, :landed true, :own-branch? true, :elsewhere f... |
| `v2-4-schema-reflush` | schema | PASS | 309 | {:values [":review.v2/n" ":int" ":review.v2/n" "[:int {:min 3}]"], :timings {:read 0.296124, :evaluate 21.836083000000002, :derive 7.3395, :apply 112.9487080... |
| `speculative-batch-keeps-committed-projection` | schema | PASS | 3756 | {:ruling-b-ms {:before 32, :after 28}, :head-path-ms {:before 4, :after 4}} |
| `oq1-storage-failure` | owner | PASS | 316 | {:commit #:seon.batch{:storage-error "storage unavailable"}} |
| `world-survives-compute-hop` | world | PASS | 860 | {:here #:seon.agent{:id "root"}, :bare {:seon.schema/expected-value :seon.db/connection, :seon.error/operation seon.db/missing-connection-error, :seon.schema... |
| `v2-6-tag-once` | input | PASS | 187 | {:reader-calls 1} |
| `b6-jvm-throwing-print-and-error` | entrant | PASS | 53 | {:shown ["#object[one-path-proof.host/eval4702632/reify--4702633]" "#:seon.error{:exception-class clojure.lang.ExceptionInfo,\n :message \"jvm boom\"}"], :di... |
| `time-one-defn` | timing | PASS | 2214 | {:ms [244 221 214 248 219], :timings {:read 0.11924900000000001, :evaluate 2.384083, :derive 7.439792, :apply 79.74475, :settle 125.547667}, :batch-retained-... |
| `r6-e1-opening-reads-the-inbox` | parity | PASS | 1025 | {:inbox-reads ["(my.message/inbox {:seon.agent/id \"root\"})"], :walk-duplicates 1, :opening-forms 10} |
| `agent-starts-in-another-namespace` | effects | PASS | 670 | {:values ["#'op3.elsewhere/there" ":here"]} |
| `q2-recorded-replies` | owner | PASS | 188 | {:count 52, :designed [6], :ms 22, :diffs []} |
| `expansion-observer` | sci-fork | PASS | 0 | {} |
| `contract-warning` | owner | PASS | 1422 | {:note "user/nc1 has no :malli/schema; specify it before proposing a merge.", :warning [user/nc2 user/nc3 user/nc1]} |
| `b5-contracts-gate` | landing | PASS | 1026 | {:ms 338, :findings 0, :each []} |
| `r8-render-read-only` | entrant | PASS | 242 | {:values ["#'user/leaked" "{:seon.schema/expected-value :seon.db/connection, :seon.error/operation seon.db/missing-connection-error, :seon.schema/refused-val... |
| `write-after-schema-batch` | timing | PASS | 6314 | {:ms [279 218 230], :timings {:read 0.11787500000000001, :evaluate 2.067667, :derive 8.075208, :apply 88.272583, :settle 126.943875}, :cause "seon.db/nearest... |
| `extensions-marked` | sci-fork | PASS | 0 | {:protocol-writes [:root :root :extend :extend]} |
| `v2-3-later-stages-fail` | totality | PASS | 241 | {:diagnostics ["show: stage failed" "show: stage failed" "conclude: stage failed" "settle: stage failed" "fault: stage failed" "fault: stage failed" "fault: ... |
| `attribute-change-equivalence` | schema | PASS | 919 | {:results [true true true]} |
| `v2-1-nested-callback` | sci-fork | PASS | 0 | {:events ["outer"]} |
| `future-conveys` | sci-fork | PASS | 0 | {:events [user/fut]} |
| `b3-malformed-tag-read-once` | reader | PASS | 0 | {:reader-calls 1, :reason "bad tag"} |
| `v2-6-repair-before-require` | input | PASS | 83 | {:values [nil 3 :clojure.string/k]} |
| `b1-value-equal-to-sentinel` | totality | PASS | 0 | {:diagnostics [["observe: review injected failure"] ["observe: review injected failure"] ["observe: review injected failure"]]} |
| `gate-runs-tests-in-their-namespace` | gate | PASS | 1328 | {:gate #:seon.gate{:sym gjf.core/f, :blocks? false, :tests 1}, :refusal {:seon.gate/sym gjf.core/f, :seon.gate/blocks? true, :seon.gate/tests 1, :seon.gate/n... |
| `b1-malformed-observation` | totality | PASS | 0 | {:diagnostics ["observe: 2 observed writes were not maps and were dropped"]} |
| `q1-single-def-kondo` | sci-fork | PASS | 0 | {:made {:name made, :row 2, :col 1, :defined-by clojure.core/def}} |
| `r2-rebound-intern` | observer | PASS | 438 | {:values ["#'user/review-rebound" "#'user/review-rebound" "[:error \"user/review-rebound refused argument 0: expected an integer, got a string. Fix: Supply a... |
| `gate-judges-the-change` | gate | PASS | 1391 | {:still-red-gate #:seon.gate{:sym user/gj, :blocks? false, :tests 2, :still-red [#:seon.test{:pass-count 0, :fail-count 1, :error-count 0, :sym user/gj-red}]... |
| `parity-faults-in-both-replies` | parity | PASS | 1023 | {:mcp-faults 1, :agent-faults 1, :diagnostics ["show: [{:seon.error/at #inst \"2026-09-27T23:14:17.459Z\",\n  :seon.error/data\n    #:seon.render.unknown{:ca... |
| `test-agent` | entrant | PASS | 1136 | {:values ["#'user/op3-add" "true" "false"], :recorded 3} |
| `entrant-mcp-sci-recorded` | entrant | PASS | 479 | {:evaluations 2, :commit #:seon.batch{:applied true, :tx-count 2}} |
| `turn-overhead` | timing | PASS | 8826 | {:kept 22, :kept-plan-chars [4536371 15640 1533 991 652 618 608 608 608 608 608 278], :remaining {:largest-plan "seon.error/for-agent's read stores one whole... |
| `b2-generated-definition-hook` | join | PASS | 5 | {:made [{:name made, :row 2, :col 1, :end-row 2, :end-col 9}]} |
| `v2-1-inherited` | sci-fork | PASS | 0 | {:value 12, :events [{:same-var? true, :observed 2}]} |
| `b3-repair-reads-tag-once` | reader | PASS | 0 | {:reader-calls 1, :result {:forms ["[#review/tag 1]"], :text "[#review/tag 1]", :repairs [#:seon.batch{:repair-kind :delimiters, :at 0}]}} |
| `v2-4-register-then-use` | schema | PASS | 584 | {:values [":op3.h16/n" "#'user/h16f" "4" "[:error \"user/h16f refused argument 0: expected :op3.h16/n (an integer at least 0), got an integer -1. Fix: Supply... |
| `captured-locals` | observer | PASS | 431 | {:private #:user{capture-f :captures-locals}} |
| `jvm-gets-feedback` | entrant | PASS | 229 | {:feedback #:seon.feedback{:changed []}} |
| `corpus` | corpus | PASS | 50363 | {:same 84, :designed 10, :facts-errors [["h32-multi-arity" ["The install gate refused user/h32-f: its own contract check failed"]]], :entities 73, :differ []... |
| `oq1-bad-form-logged` | owner | PASS | 463 | {:facts-errors ["Schema reference :no.such/key is not declared in the candidate projection; it is named by user/oq1bad. Declare :no.such/key in the same chan... |
| `extension-replay` | decision | PASS | 221 | {:decision "S16 needs a producer event for addMethod/removeMethod and extend in the SCI fork before any extension entity; deferred to its own spec"} |
| `b4-moved-head-feedback` | effects | PASS | 1538 | {:errors ["Program retraction refused."], :feedback #:seon.feedback{:changed [user/review-new], :missing-contracts [user/review-new]}} |
| `v2-7-no-reverse-requires` | static | PASS | 1541 | {:cycles []} |
| `v2-5-read-only-evidence` | effects | PASS | 618 | {:read-only [false false false true]} |
| `sink-per-world` | world | PASS | 600 | {:a #:user{counted 1000}, :b #:user{counted 1500}, :jvm-wide 2500} |
| `jvm` | context | PASS | 0 | {:before {:heap-used-mb 4454, :old-gen-after-gc-mb 2095, :processors 18}, :after {:heap-used-mb 5107, :old-gen-after-gc-mb 2405, :processors 18}} |
| `time-acquisition` | timing | PASS | 23495 | {:entities 100, :ms 11, :us-per-entity 110} |
| `q5-def-edges` | owner | PASS | 529 | {:calls [[clojure.core/def] [clojure.core/def user/base-v] [clojure.core/defonce clojure.core/inc user/base-v]], :acquired [[42 {:k 41}]]} |
| `v2-3-show-fails` | totality | PASS | 108 | {:shown ["()" "42"], :diagnostics ["show: [#:seon.error{:at #inst \"2026-09-27T23:14:20.937Z\",\n  :layer :seon.render/invocation,\n  :operation seon.sci.eva... |
| `v2-5-remove-rebind` | effects | PASS | 389 | {:source "(def rr-v2 2)"} |
| `record-acquires-once` | observer | PASS | 403 | {:sources ("(defrecord OpR [a])"), :acquired [7]} |
| `b1-nil-print-failure` | totality | PASS | 0 | {:entries [#:seon.batch{:ordinal 0, :ns-before user, :value nil, :source "nil", :line 1, :shown "#<unprintable nil>", :column 1, :ns-after user}], :diagnosti... |
| `mcp-equals-agent-turn` | entrant | PASS | 9479 | {:behaviors 9, :differ []} |
| `ns-and-defn-in-one-form` | effects | PASS | 230 | {:facts-errors []} |
| `entrant-gate` | entrant | PASS | 533 | {:facts-errors ["The install gate refused user/gt1: its own contract check failed"]} |
| `v2-5-remove-ns` | effects | PASS | 613 | {:namespace-facts nil, :commit #:seon.batch{:applied true, :tx-count 1}} |
| `b6-jvm-floor` | entrant | PASS | 551 | {:jvm-shown-chars 3192, :sci-shown-chars 3192, :jvm-tail "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx... |
| `v2-8-jvm-recorded` | entrant | PASS | 65 | {:values ["" "A" "[:error \"Divide by zero\"]" "3"], :commit #:seon.batch{:applied true, :tx-count 1}} |
| `r4-new-then-removed` | effects | PASS | 1179 | {} |
| `sci-suite` | sci-fork | PASS | 0 | {:patched {:tests 397, :assertions 1517, :fail-count 5, :errors 0}, :base {:tests 393, :assertions 1485, :fail-count 5, :errors 0}, :failures ["FAIL in (arit... |
| `v2-3-ns-name-shadow` | totality | PASS | 575 | {:values ["42" "#'user/ns-name" "43"]} |
| `r6-shown-by-the-one-print-owner` | print | PASS | 65 | {:shown ["{:seon.error/exception-class clojure.lang.ExceptionInfo,\n :seon.sci.eval/stacktrace [[user 1 1]],\n :seon.error/message \"owner\"}" "(+ 1 2)" "{:a... |
| `q4-lone-key` | owner | PASS | 497 | {:attribute #:db{:valueType :db.type/long, :cardinality :db.cardinality/one}, :notes [{:seon.schema/key :one.path.q4/f, :seon.schema/installed? false, :seon.... |
| `r1-every-write` | sci-fork | PASS | 0 | {:retained [{:root 2, :origin {:line 1, :column 1, :end-line 1, :end-column 10}} {:root 3, :origin nil} {:root 4, :origin nil}]} |
| `b4-writer-refusal-reply` | totality | PASS | 476 | {:reply #:seon.batch{:commit #:seon.batch{:writer-refusal #:seon.error{:message "review writer refusal"}, :first-refusal "review writer refusal"}, :diagnosti... |
| `v2-3-observer-fails` | totality | PASS | 99 | {:diagnostics ["observe: stop failed"]} |
| `feedback-hub-fresh` | timing | PASS | 1282 | {:bounded-text {:fresh-ms 19, :tests 10, :counts {:never-run 10}}, :shown-value {:fresh-ms 13, :tests 22, :counts {:never-run 22}}, :config-effective {:fresh... |
| `v2-8-nested-render-one-tx` | entrant | PASS | 258 | {:commits 1, :values ["#'user/nr" "[\"3\"]"]} |
| `q2-live-reply` | owner | PASS | 244 | {:repairs [:prose :prompt-marker :delimiters :prose], :text "; Let me require it first.\n(require '[clojure.string :as str])\n::str/k\n(+ 1 2)\n(defn bad [x]... |
| `test-agents-concurrent` | entrant | PASS | 665 | {:branches [":agent-20a76ae2ed8e" ":agent-fdff5fe99fed"]} |
| `b2-exact-span-no-fallback` | join | PASS | 0 | {:join #:seon.batch{:join :unanalyzed, :reason :no-definition-at-origin}} |
| `parity-render-refusal-never-parks` | parity | PASS | 1061 | {:mcp {:applied? true, :stored? true, :values 2, :shown "{:seon.render/ai no.such/renderer, :x 1}"}, :agent {:applied? true, :stored? true, :values 2, :shown... |
| `v2-2-generated-in-let` | sci-fork | PASS | 0 | {:origins [{:line 3, :column 3, :end-line 3, :end-column 9, :expanded-by pair} {:line 3, :column 3, :end-line 3, :end-column 9, :expanded-by pair}]} |
| `gate-callee-contract-is-feedback` | gate | PASS | 1207 | {:findings [#:seon.gate{:contract user/cal2, :arguments [-1]}]} |
| `v2-2-single-def-macro` | sci-fork | PASS | 0 | {:origin {:line 2, :column 1, :end-line 2, :end-column 9, :expanded-by single}} |
| `oq1-head-moved` | owner | PASS | 1515 | {:facts-errors ["Program retraction refused."], :first-refusal "Program retraction refused."} |
| `v2-1-throwing-watch` | sci-fork | PASS | 0 | {:answer "watch failed", :events [{:op :bind, :root 2}]} |
| `r6-unresolved-names-kept-as-values` | effects | PASS | 814 | {:calls #{clojure.core/defn clojure.set/union}, :requires #{seon.bootstrap clojure.test clojure.set seon.db my.turn my.message}} |
| `worlds-never-cross` | world | PASS | 1193 | {:sources ["(def op3-cross :a)" "(def op3-cross :b)"]} |
| `v2-3-span-invalid` | totality | PASS | 439 | {:diagnostics ["read: the read stage answered span [0, 999) outside its text of 2 characters"]} |
| `feedback-from-facts` | owner | PASS | 1174 | {:feedback #:seon.feedback{:changed [user/fb-f user/fb-g], :missing-contracts [user/fb-f], :tests #:user{fb-t :never-run}, :counts {:never-run 1}}, :contract... |
| `q3-threaded-facts` | owner | PASS | 254 | {:values ["#'user/q3f" "First doc." "#'user/q3f" "Second doc."]} |
| `two-threads-one-context` | sci-fork | PASS | 0 | {:counts [50 50]} |
| `b7-concurrent-observation` | effects | PASS | 2 | {:observed 8000, :late "The evaluation that owned this observation has completed."} |
| `time-chained-40` | timing | PASS | 19244 | {:timings {:read 7.983127999999998, :evaluate 212.51099700000006, :derive 836.7979510000001, :apply 14110.272003, :settle 448.270875}, :retained-forms 79, :b... |

92/92 proofs pass


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
| **S3** new files | Split `seon.turn.batch` (world → `seon.turn.world`, gate → `seon.turn.gate`); run `seon.contracts-compile-test/check` over the archive (B5, 0 findings required); then add the namespaces and `seon.batch.edn`. Make public (one token each): `eval.clj` `reader-context` :1092, `binding-rows` :1177, `namespace-context-row` :1266, `instrumentation-config` :1332, `install-function-contract!` :1346 (and read `:seon.sci.eval/sci-var` when supplied, +1); `fn.clj` `runtime-analysis-batch` :943 and the five analysis helpers `analyzed-entities` calls; `my/program.clj` `deletion-report` :650, `locate`, `blocking?`, `refusal`. Move `captured-locals`' kondo call into `seon.fn.analyzer` (`:locals true` in `analysis-config` :22, +6) | — | — | `seon.batch-test` (the fold proofs), `seon.sci.forgive-test` (52 replies, tag, alias) |
| **S3a** supply stage | Apply `supply-from-the-world.patch` and `read-evidence-current-supplied-db.patch` (root owns the files: ownership or patch); classify the 127 functions of `optional-world-members.edn`; supplied-default rows for writer, task, profile, sink and context | `calling-environment`'s arm/custody derivation (~12) | the 127 functions where absence is meaningful | the §7.2 supply proofs, armed; `seon.call-preparation-test` |
| **S3b** call sink | Apply `profile-world-sink.patch`; tests that count calls read their own world's sink | the JVM-wide exact-count assertions (task 5b1b9776f) | the counting tests | `sink-per-world` armed |
| **S3c** host bindings (F1) | `eval.clj` `install-caller-host-bindings!` skips analyzer forms and special symbols, so a namespace with stored facts never shadows `import*` | — | — | a regression from the corpus on one branch |
| **S4** product turn | `turn.clj:5056` `evaluate-sources` becomes `evaluate-batch` over `turn-request`, supplying `row-tx` :1285 and `record-evaluated-call` :1569 as values; `resume-turn` :5297 answers the reply | `evaluate-sources` 164, `restore-evaluations!` 19, `with-settled` 8, `settled-value` 10, `settlement-form` 10, `analyze-settlement` 23, `namespace-rows` 20, `evaluated-namespaces` 17, `schema-attribute-change-tx` 43, `affected-schema-attributes` 9 | manifest rows naming these (turn.clj, turn_test, cluster/turn_test, turn_loop_test) | `seon.turn-test`, `seon.cluster.turn-test`, `evaluate-source-test` |
| **V1** the Var render (rev 5, data rev 6) | The Var render pair (AI data, HTML hiccup, one selection) over the definition's entity and its digest-keyed test results; a results read with no result for the digest answers pending; its read evidence refreshes the next context | the REPL install gate: `seon.turn.gate` 135, `apply-facts`' gate calls and deferred tracking ~40, `conclude`/`reaching-tests` 75 and the feedback/gate reply keys and schemas ~45; old path `missing-contract`'s refusal and `gate-function-install` 73 | the proofs and tests of the gate and feedback (repl_test, cluster_test, cluster/agent_test assert the refusal) | the four §7 render proofs, armed, both entrants and HTML |
| **V2..V8** render pairs | ns-unmap/remove-ns, register!, in-ns, error, deftest, task, merge proposal (§7 table), one per owner | — | — | one render proof each |
| **S6** refresh | `read-only-evaluation?` :2075 reads the evaluation's observed writes; `source-key` :1980 keys by `[ns source]` | `source-events` 6, `evaluable-source?` 7 | — | `seon.turn-test` refresh case (from `v2-5-read-only-evidence`) |
| **S7** MCP | `mcp.clj` `run-form` :391, `run-events` :440, `jvm-evaluation-form` :376 become `code-request`/`jvm-request` → `reply` → JSON; `branch-form` :477's body becomes one `my.agent` entrance both call; `cluster.clj:352` `mcp-project` and `:279` `project-next-prepl-value!` move their floor/blob selection into the show stage. Add the §7 landing attributes (`:seon.cluster.eval/changed`, `removed`, `registered-keys`, `:seon.eval/feedback-shown`, `:seon.fn/malformed-spec`) with their writers | ~180 (range) | mcp bridge tests | `seon.dev.mcp-bridge-test`, a regression from `mcp-equals-agent-turn` |
| **P1** turn prelude (rev 5, owner; rev 6) | Context = the opening (rendered once, cached by content digest, re-rendered only when a read's evidence changed) + the stored shown text of prior evaluations; a refresh check per distinct read (~0.1 ms) re-renders only the changed ones. The opening's units are AI data renders (§7.3 table), printed by the value printer; their forms become `:seon.render/form`. Targets: wake → provider request under 50 ms with no changed read, under 100 ms with one, a per-stage breakdown as data | the per-turn history re-render path, `:seon.turn/changes` (no consumer); the render-form evaluation stage: `evaluated-source` ~40, render.clj's source branch ~45, `source-blocks`/`source-output?` schemas, `declared-sources`' evaluation of opening units | turn/prompt tests | armed timing proofs on root's real history (149 evaluations) |
| **S7a** turn prelude | `turn.clj` `latest-evaluations` :2041 pulls the read evidence of the kept evaluations only (`turn-overhead`: 3.5 s → 0.46 s on root's history); root's patches for the stored-plan producer, `of-agent` and per-render `call-reach` follow the task | — | — | `seon.turn-test` refresh cases, `turn-overhead` |
| **S8** input and render | `agent.clj:659` `segmenter`, `turn.clj` `declared-sources`, `call-turn`, `system-turn` :2230, `web.clj:1384` `render-source-call`, `eval.clj:4059` `evaluated-source` use `seon.sci.forgive` / `render-request` | `reply.clj` (426), `turn.clj` `read-source`…`planned-sources` and `preview-sources` (8 defns, 140) | `(:require [seon.cluster.reply …])` in `turn.clj:16`, `ai_test`, `cluster/turn_test`, `repl_parity_test`; `reply_test` deleted (481) | `seon.render.transcript-test`, `prompt_test`, `loop_proof_test` |
| **S8a** analyzer hooks | Apply `analyzer-expansion-hooks.patch`; `expansion-hooks` moves into `seon.fn.analyzer` | the prototype's copy | — | `b2-generated-definition-hook` becomes the stored-entity and reacquisition proof |
| **S9** acquisition and removal | `eval.clj` `install-function-from-database!` :1414, `install-row!` :1764 evaluate through `seon.batch` + `seon.sci.batch`; `transfer-evaluated-roots!` :1983 reads the batch's writes; `my/program.clj` `ns-unmap!` :718 and `remove-ns!` :734 call SCI's natives (observed) | `native-mutation` 19, `native-call-refusal` 43, `one-event` 49 | eval_test | `seon.sci.eval-test`, from `r4-new-then-removed`, `v2-5-remove-ns` |
| **S10** evaluate | `eval.clj:4380` `evaluate` keeps its kernel arm; its admission body goes | the admission region (27 defns, 565) and `declared-row`/`unmap-row` (126) | eval_test, repl tests | `seon.sci.eval-test`, `seon.repl-test` |
| **S11** reader | `reader.cljc` loses declaration facts and namespace prediction | 19 defns, 316 | `read-events`, `reader-error` | `seon.sci.reader-test` |
| **S12** test agents | A definition's batch schedules its reaching test agents as an effect of the definition (settle, after commit; the Var render only reads their results). `test.clj:1968` `member-result` runs its body as a test agent (§6): the body is the request's text or evaluate stage, its recording the settle stage beside it, committing on the requester's branch; `bounded-result` :218 keeps bound and exit ownership; `runner/record-tx` and `commit-results!` lose their test-side callers | runner-side recording (range per the test-agents design) | test runner tests | `seon.test.one-request-test`, from `test-agent`, `test-agents-concurrent` |
| **S13** proofs as tests | `tmp/one-path/one_path_proof.clj` becomes `test/seon/batch_proof_test.clj` (each named proof a deftest); `scratch_rows.clj` stays the fork's `sci.write-observer-test` | — | — | root's central round |
| **S14** extension entities (deferred) | Requires a producer event for `addMethod`/`removeMethod` and `extend` in the fork before any extension entity; not in this cut (`extension-replay` records the decision) | — | — | — |

Each step up to S11 deletes a name only in the commit that converts its last caller; until then the old function stays
callable (it is not a compatibility delegate: nothing new calls it).

### 10.0 S4a: the product turn on the fold (rev 2: answers Astra's A1–A6, b469fc675; root ruling: option 1)

**What exists** (HEAD 1a56cba66). `resume-turn` (turn.clj:5423) calls `evaluate-sources` (:5182), a loop that per
pre-opened row re-reads the row's source inside `sci.eval/evaluate` (:4506, the one production caller), gates
contracted installs (`gate-function-install` :3527), applies the disposition rule by reading the source a third time
(`disposition-rule-error` :5161), binds the handle, and threads earlier rows (`with-settled`). `settle-batch!` (:3990)
turns each `:seon.sci.eval/evaluation` into terminal data and commits one transaction with the run's side effects.
The rows were opened from `planned-sources` (:3422), whose `reply/sources` reads every form semantically (tags, deferred
aliases) before anything runs. `sci.eval/evaluate` is two things braided: the **evaluation frame** (custody, turn
environment, request context, receipt and pending open, projection state, arm, loaded-identity check, acquisition,
namespace, output and print-option capture, the form's read sink, the cost record, the shown result, the success or
failure evaluation) and **admission** (`declared-row`, top-level `do` admission, `unmap-row`, `admit!`, restoration).

**What S4a changes.** `evaluate-sources` keeps its name and its answer (`:seon.turn.loop/evaluated-sources`, so
`resume-turn`, `preview-sources` and `system-turn` keep their shapes) and its body becomes
`(seon.batch/evaluate-batch (turn.batch/turn-request ...))`. The fold's observe, derive, apply and fold stages replace
admission, gating and row threading; the turn supplies the stage values below. Admission stays inside
`sci.eval/evaluate` only for its tests until S10 deletes both.

| Stage value | Supplied by the turn | Replaces |
|---|---|---|
| `:seon.batch/read-state`/`read-next` | **A1.** The run's rows in ordinal order; each step reads the row's source once with the live reader (`sci.batch/read-code`, the fork's current namespace and aliases); the form, span and version stay on the entry (`:seon.batch/form`). A row whose read fails is that form's reader error, read once | the loop over `:seon.cluster.reply/sources` and `one-event`'s re-read |
| `:seon.batch/evaluate` | **A2.** `sci.eval/evaluation-frame` (the frame split out of `evaluate`, edited in place) running `(sci/eval-form ctx form)` on the held form under the batch's registration delta, with the per-ordinal receipt, handle, pending open and profile `evaluate-sources` builds today. It answers the complete `:seon.sci.eval/evaluation` (value or error value, output, interruption, print options, integer duration/acquisition/allocation, namespace lookup ref and ending symbol, the whole shown map) | `sci.eval/evaluate` + `gate-function-install` |
| `:seon.batch/show` | the frame's shown map (the frame prints inside its arm, as today); nothing prints twice | the loop's print |
| `:seon.batch/publish` (new, optional) | **A2.** The entrant's last word on a published entry, before derive: `(publish request batch entry)` answers `{:seon.batch/entry e :seon.batch/stop? b}`. The turn's: the disposition rule over the **held** form and whether a row remains (`last?` from the read state); an early `(my.agent/done ...)` is replaced by its refusal through `unrun-evaluation` + `shown-failure` (the refused disposition kept as `:seon.batch/refused-value`), a disposition stops reading; the generated-read fault check; then `bind-result!` of the (final) value | the loop's `disposition`/`disposition-rule-error` branch and bind site |
| `:seon.batch/settle` | none here: `resume-turn`'s `settle-batch!` settles. **Q2 accepted**: the fold's program (`:seon.batch/program`) is handed to `settle-batch!` as data and `program-facts` on the head joins its one transaction inside the committed phase; no second settlement | `settle-batch!`'s `:seon.program/row` path and `final-namespace-digests` |
| `:seon.batch/fault` | `world/record-fault` in the cluster's environment | nothing: the old loop threw |

**A1: one semantic read.** `planned-sources` segments with `forgive/forms` under the structural read options
(edamame, tags unread, any alias: `forgive/structural-read-options`, made public) instead of `reply/sources` +
`repair-sources`. Its rows are the accepted form texts of the repaired text; an unread remainder becomes one last row
whose evaluation-time read answers its reader error once; no form is the no-forms error as today. The row's namespace
attribution goes: the fold places each form in the fork's live namespace. REPL evidence
(`tmp/one-path/s4a/a1-segmentation.edn`, 52 recorded replies, 64 ms): structural equals semantic segmentation except
two designed improvements, #25 (`::parity-s/k` after `(require ... :as parity-s)`: the semantic segmenter stops because
the alias does not exist before evaluation; the structural one reads all three forms and the evaluation-time read
resolves it) and #41 (an unaccepted tag is one form whose read error is answered once). Against today's planner, 45 of
52 equal or both empty; five are where the forgiving stage already differs from today's planner (prose and a stray
delimiter: #1, #5, #6, #45, #46) and two are malformed texts (#40 `#=`, #50 odd map) that become the unread last row. Pre-opened rows stay until S8 (Q1 accepted): they are receipt
identities, not parsed-form storage.

**A2: the evaluation handoff.** Producer/consumer matrix; every field has one producer:

| Consumer | Field | Producer in S4a |
|---|---|---|
| `evaluation-facts` :94 | ordinal, run | the row (read stage) |
| | value, error, outcome, output, interrupted-at, print options, record | the frame (unchanged code) |
| | `:seon.cluster.eval/ns` lookup ref, `:seon.sci.eval/ending-ns` | the frame (`[:seon.ns/name ns]`, symbol) |
| | integer duration, acquisition, allocation | the frame (`elapsed-ms`, kernel record) |
| | shown, renderer, diagnostics, value-size keys, `value-edn`, `unstored` | the frame's `shown-result`/`shown-failure` |
| | read evidence and basis | the per-form world (below) |
| | code evidence (new) | the per-form sink (below); declared in `seon.cluster.eval.edn`, selected by the receipt writer and `latest-evaluations` |
| | facts error | the fold's apply stage; recorded as the evaluation's error beside its kept value |
| `settlement-projection` :209 | `value-edn`, report EDN | the frame (unchanged) |
| `evaluation-terminal-data` :3750 | disposition and original value | the publish stage; the refused disposition kept separately |
| `settle-batch!` :3990 | program facts | `program-facts` over the batch's program on the head, in the committed phase |
| `resume-turn` installation :5562 | installations | the batch's accepted entities with their observed Vars: `{:seon.program/row entity :seon.sci.eval/evaluation {:seon.sci.eval/bindings {sym {value, metadata}}}}` from the fold's writes, never `:seon.program/row` of an evaluation |

REPL evidence (`a2-frame-vs-entry.edn`): for `(do (println "kept") 42)` the frame answers output `"kept\n"`, integer
duration 1, allocation 871,784, `[:seon.ns/name user]`, shown `"42"`. The same form through today's fold SCI stage
(`sci.batch/evaluate`, via `render-request`) fails: `sci/out` is unbound (`SciUnbound` cannot be cast to `Writer`),
finding **F-Q** below. The frame is the fix for every SCI entrant; `sci.batch/evaluate` is deleted with acquisition's
move to the frame (S9).

**The fold's contract changes (seon.batch):**
- **The evaluate stage answers an outcome**, `{:seon.batch/value v}` or `{:seon.batch/error t}` with any entrant keys
  (the turn's `:seon.batch/evaluation`); the four existing stages adapt (they are this lane's). B1's sentinel becomes
  "no outcome".
- **Per-form evidence (A3).** Each form runs in a world with a fresh call sink, a fresh read-evidence sink and an
  owned-work count (`:seon.batch/in-flight`). `kernel/future-call`, the arm's one seam for an agent's futures, counts
  the future in the world it carries and uncounts it when its body exits (its terminal event); nested futures count in
  the same form. At the form's completion the entry freezes `:seon.batch/read-evidence`, `:seon.batch/executed` and
  `:seon.batch/evidence` `:complete`, or `:unknown` with a diagnostic naming the missing terminal event ("a future this
  form started had not exited") when owned work is still in flight. The value is published at once; nothing waits for
  the future. Unknown evidence is never current: the refresh re-runs that unit. REPL evidence (`a3-in-flight.edn`): a
  form whose future (and its nested future) is held is `:unknown` after the form and after the next form, whose own
  evidence is `:complete`; after exit a fresh freeze is `:complete`. **Boundary:** concurrency that does not pass through
  `kernel/future-call` is not counted: SCI's `pmap` (`reference-code/sci/src/sci/addons/future.clj`, which calls the host
  `future-call`, so the arm does not govern it either) and executors inside host functions. Its late reads are not in
  the frozen evidence. SCI's `pmap` escaping the arm is an existing kernel defect, filed as its own task, not fixed here.
- **Executed definitions (A4).** `profile/with-cell` binds the cell's key `[sym digest]` once at install;
  `count-into-world!` takes the key's adder at **entry** (`computeIfAbsent`) and increments at exit, so an entered,
  unfinished call is evidence (count 0). `batch/calls` sums by symbol (callers unchanged); `batch/executed` answers
  key → count. A nil digest is explicit unknown. A definition this batch armed at its write has no digest yet (arming
  precedes derive): the fold resolves `[sym nil]` to the digest it derived for the one write of `sym` at or before
  that form, and leaves it unknown when the batch wrote `sym` more than once. An armed call-preparation refusal names
  its callee by `:seon.instrument/fn` (confirmed by Astra) and joins the same set. REPL evidence
  (`a4-executed-digests.edn`): two cells of `review/f` with different digests are two keys; an unknown digest is
  `[review/g nil]`; an entered, unfinished call is present with count 0.
- **Settlement never replaces a published entry (A5).** `settled-into` merges the settlement's program and working
  value and, per held ordinal, only a facts error (that form's change dropped, its value kept); an update naming an
  ordinal the batch never published is a diagnostic. `:seon.batch/settled`'s schema is closed to those members.
  REPL evidence (`a5-settled-into.edn`): Astra's empty-entries settlement drops `[42 3]` under today's `merge` and
  keeps both under `settled-into`; a refusal of ordinal 1 marks only that form; a stray ordinal 7 is one diagnostic.

**Code evidence and the refresh.** The evaluation stores `:seon.cluster.eval/code-evidence`: the `[sym digest]` pairs
executed, or `:unknown`. Before a turn, a kept read-only unit re-runs when a read's evidence changed, when any executed
pair's digest is not the symbol's current `:seon.program/definition-digest` (one query for the distinct symbols of all
kept units), when a symbol is gone, or when its evidence is `:unknown` or absent (stored before S4a): absent evidence
re-runs once and stores its evidence. Uncontracted definitions are not armed, so they have no cell; a change to one does
not refresh a unit that called it. That is the contract law's boundary (the growing warning at the REPL, refusal at
merge), not a new rule.

**A6: a loadable deletion list** (current Var usages from clj-kondo over the archive, resource symbols included):

| Deleted in S4a | Lines | Its callers, converted in the same commit |
|---|---|---|
| `evaluate-sources`' loop body (the name stays) | ~120 | — |
| `gate-function-install` | 73 | `cluster/turn_test.clj:2638`, `cluster/agent_test.clj:1342` (quoted `ns-resolve`: the tests of the REPL gate, deleted with it per V1); task symbols `seon.sci.eval.edn:44,55,198` retargeted to `seon.turn/evaluate-sources` |
| `with-settled`, `settled-value` | 18 | task `seon.turn.edn:126-127` retargeted |
| `restore-evaluations!` | 19 | `turn_test.clj:268,278` become `a-refused-settlement-keeps-each-evaluated-value` cases (no restoration: OQ1) |
| `final-namespace-digests` | 28 | `settle-batch!` (rows gone) |
| `evaluation-entity-id` | 11 | the loop only |
| `repairable-delimiter-error?`, `repaired-span`, `repair-source`, `repair-sources` | 62 | `planned-sources` only |
| `disposition-rule-error`'s re-read | (rewritten as the publish stage) | — |

**Kept** until the step retiring their last caller: `namespace-rows` and `evaluated-namespaces` (`source-rows` :665,
`record-evaluated-call` :1620, `receipt-settle-batch-tx` :1101: S8/S9), `analyze-settlement` and `settlement-form`
(`receipt-settle-tx` :1074), `schema-attribute-change-tx` and `affected-schema-attributes` (`row-tx` :1319, :1399: S9),
`reply/sources` (other callers until S8), `sci.eval/evaluate`'s admission (its tests: S10).

**Size.** Added ≈ 235: the frame split (+25 net, moved code), the fold's outcome, per-form evidence, publish and
`settled-into` (+50), profile and kernel (+10), the turn's stage values and entry adapter (+110), code-evidence storage
and the refresh rule with its attribute (+35), `planned-sources` (+5). Deleted ≈ 330 (table). Net ≈ −95 src, measured at
landing.

**Proofs** (armed, on a disposable branch, through both entrants: an MCP SCI run and a Seon agent turn with a scripted
local stub provider): `held-reader-form` (one reader call per form; `::alias/k` after its `require` in the same reply
reads), `settlement-field-parity` (every consumer field above present and equal between an MCP run and an agent turn
of the same reply, `(do (println "kept") 42)` included), `early-done-replacement` (`(my.agent/done "d") 42` answers the
refusal on form 0 and still evaluates 42; a last-form disposition stops), `late-form-evidence` (held future: unknown
after the form, after the next form, after settlement), `executed-digest-evidence` (two versions in one form; a
callee digest change re-runs its unit, an unrelated change does not), `settlement-preserves-published-entries`
(Astra's A5 form), plus: a defn, a def, a require and a schema registration commit through the fold's facts; the
pre-S3a `(help)` refusal (no evidence) re-runs once; the turn-prelude timings (P1) do not regress by more than 20% or
50 ms. Focused tests for root: `seon.turn-test`, `seon.cluster.turn-test`, `seon.rereads-test`,
`seon.profile-test`, `seon.sci.kernel-test`, `seon.dev.mcp-bridge-test`, `seon.batch-test`.

### 10.1 Net lines

| Item | Removed | Added | Kind |
|---|---|---|---|
| Scripted deletion at 79c10321d: 96 defns (`deletions.edn` + `deletions-v3-extra.edn`) and the rest of `reply.clj` | 2,286 | 0 | **measured** |
| The implementation: six namespaces | 0 | 2,071 | **measured** |
| `seon.batch.edn` / retired `seon.sci.eval` and `seon.cluster.reply` schema families | ~80 + 50 | 188 | measured + estimate |
| `evaluate`'s admission body (:4380 region) → per-form stage | ~200 | ~20 | range + estimate |
| MCP `run-events`, `jvm-evaluation-form`, `mcp-project`'s selection, `project-next-prepl-value!` | ~180 | ~20 | range + estimate |
| `my.program` removal path | ~30 | 0 | estimate |
| Caller conversions (46 src references), publicised privates, the §7 attributes' writers | 0 | ~120 | estimate |
| Root-owned patches (§7.2) | ≈ 15 | ≈ 105 | measured |
| Rev 5: the gate and feedback stage deleted (V1) | ≈ 370 | 0 | measured |
| Rev 5: the Var render pair and digest-keyed results read (V1) | 0 | 133 | measured |
| Rev 6: B1's data-form evaluation deleted (eval.clj, net) | 29 | 0 | measured |
| Rev 6: the render-form evaluation stage (P1: `evaluated-source`, render.clj source branch, schemas) | ≈ 120 | 0 | estimate |
| Rev 6: 15 producers return data instead of entries (P1) | ≈ 60 | ≈ 30 | estimate |
| **src + script** | **≈ 3,290** | **≈ 2,499** | **≈ −791** |
| Resources | ≈ 130 | 228 + ~20 | ≈ +120 |
| Tests: `reply_test` 481, `restoration_test` 132, reader facts ~110, eval/accretion ~130 / proofs as tests ~500, conversions ~60 | ≈ 853 | ≈ 560 | ≈ −290 |
| SCI fork | 32 | 107 (+139 test) | measured |

Additions exceed rev 2's estimate because rev 3 does work rev 2 left out: validation and the gate per form, the
recording settle, rebase, feedback, the one reply, and every entrant's request. Rev 4 adds 429 more (the B1/B7
observation scope, the fault stage, the JVM floor, the world constructors, the gate's test agents, feedback from
digests, the expansion hooks): more than the ~100-line stop rule allows without the owner's word, reported with this
revision. What it lets go: the JVM printer and stringifier, the runner-side recording, the JVM-wide counters and the
arm/custody supply derivation.

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
- **F-T (S12, 2026-09-28, default pid 77505).** `seon.test/run` of ONE named test costs 4,657 ms cold and 2,244 ms warm
  around a 147 ms body (`member-result`). Cold, by inclusive time: `select` 1,274 ms (`selection-facts` 1,092 ms x4; 239
  `seon.db/q`, 786 `with-declarations`), 8 transactions 1,786 ms (`encode-call-output-in` 1,220 ms x7),
  `clean-request-handle` 1,532 ms (`fork-cluster-ctx` 553, `seed-cluster-facts!` 500, `config/apply-compiled!` 409),
  `program-written-since?` 1,096 ms. Warm: `select` 1,336, transactions 524, `clean-request-handle` 362. Task
  `one-test-run-costs-seconds-around-a-147-ms-body` (seon.test.edn).
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
- **F-L (dogfood 5).** Loading an agent's history decodes every stored read plan: 477 plans, 36.5 MB, the largest
  4,536,371 chars from the opening's `(seon.error/for-agent (seon.db/db) "root")` read, whose
  `:datahike.query.dependency/sources` holds one whole vector. `latest-evaluations` 3.5 s, `of-agent` 3.7 s per cold
  prompt. Extended in task `a-trivial-agent-turn-takes-eleven-seconds` (seon.turn.edn); existing
  `history-acquisition-decodes-stored-read-plans`.
- **F-M.** `seon.render/render-program-evidence` computes `call-reach` once per rendered value (×143, 3.1 s in one cold
  prompt); existing task `call-reach-is-recomputed-per-render`.
- **F-N (this lane's, fixed).** The prototype's show stage printed without a profile ("Rendering requires a carried
  profile or handed projection"), so every shown value was the floor's fallback, never a declared render; it was
  invisible until printer diagnostics became fold diagnostics (B4/dogfood 6). The show request now carries the working
  value and cluster.
- **F-O.** The agent's opening walk renders the runtime pull twice (two units); the system plan keeps one per source.
- **F-P.** `(seon.cluster.message/…)` renders `:seon.render/operations`, which no schema declares; its owner is
  `seon.render`'s schema (root).
- **F-Q (this lane's, 2026-09-28, default pid 3360).** The fold's SCI evaluate stage (`seon.sci.batch/evaluate`) binds
  no `sci/out`: `(do (println "kept") 42)` through `render-request` fails with `SciUnbound` cannot be cast to
  `java.io.Writer` (`tmp/one-path/s4a/a2-frame-vs-entry.edn`). No product turn uses the stage yet; S4a's evaluation
  frame is the fix for every SCI entrant.
- **F1 (again).** Once a namespace has stored facts, host bindings shadow analyzer forms (`clojure.core/import*`
  unresolvable); the corpus runs each item on its own branch until S3c.
- **F-I.** SCI's own suite has 5 failures on the pinned fork (unchanged): `function-results-dont-have-metadata` ×2,
  `error-location-test` :559/:567, `arity-error-test`.
- Earlier findings still open: `source-rows` throws on a nested `in-ns` (fixed by S3's per-form analysis), a contract
  refusal once stored a 6.4 MB blob, `seon.db/pull` slows with the speculative overlay, the `clojure.core/defn` call edge
  on every `defn` entity.

## 12. Owner questions

**F-A, ruled (B).** A speculative value derives its projection from its own basis and is never inserted into the
committed projection cache; the change is in `seon.db` (root). Follow-up task: move that cache into `seon.cache`
(a `seon.cache` hit is about 5× slower, so the move is measured first).

**Open: the size of this cut.** Rev 4 adds 429 lines over rev 3 (§10.1) to satisfy B1-B7 and the rev-4 rulings.
