---
type: living-list
status: current
updated: 2026-09-27
---

# Open items (living list — root rewrites it in place; ledger of file owners: tmp/orchestrator/file-ownership.md)

## Now (current state — rewritten in place 2026-09-28; never append; ledger: tmp/orchestrator/file-ownership.md)

### Resume here — read everything, don't tail
**Milestone (owner 2026-09-26): dogfood first.** One lane completes a real change entirely through the REPL path — MCP
branch (hosted agent, P3b) → `defn` → the tests reaching the change → root merge → write-back — with no file edits.
Everything else is subordinate until that works: green host-test, the write-back loop (merge gate), agent-test; then one
real task through it; then lanes work that way. Beside it, always at least one lane deleting obsolete code or converting
callers to an existing component/function/test that does the same thing better (not dogmatic: net-negative is the aim).
1. Read `AGENTS.md` end to end. Laws added 2026-09-25 that shape everything below: the one REPL path is a composition of
   stages (read → evaluate → record → print; other readers/evaluators plug in as values, never a parallel pipeline); the Seon
   agent is the perspective (MCP, tests, web are views into its world); core is composable (shared db values, a shared SCI
   base each agent forks into its private universe, one Flow graph per agent); errors are ordinary values; render selection
   order (value key → viewing agent's ns fn → schema pair → floor) with contract-declared inputs supplied as defaults;
   bound code by what it deletes; REPL-first on real data; Opus designs and implements, Astra reviews and audits.
2. Read this whole section, the ACTIVE ledger rows, and the landing note of every lane before touching its files.
3. Find the existing owner before designing: `rg` the concept, `reference-code/` (clj-kondo fork = the ONE analyzer;
   edamame = SCI's reader; malli, fipp, datahike, core.async, sci vendored), the duplicate-paths audit §11, the
   library-replacement audit, the lost-errors audit. Never write a second parser, renderer, registry, runner, walker or cache.
4. Verify at the REPL before trusting a doc (MCP `runtime_status`; `eval_clj` jvm with `(seon.cluster.boot/connection "default")`).

### Standing rulings (owner, 2026-09-25 — folded here, not a log)
- **Pillars, not parallel solutions:** every slice rgs for duplicates and deletes them in the same slice, listing deleted
  paths with line counts and net lines; ≥2 deletion/migration lanes run at all times; lean on libraries for the tricky
  parts — our code is the data transformation between their seams; hunt whole functions a library call replaces.
- **Context = a composed graph render of the agent entity.** The agent names its task by a task handle
  (`:seon.agent/task`); anything attached by reference renders. One walker (set-at-a-time index joins per level, bounded by
  N, default 3, caller-overridable) → each node through its own schema render pair (presence of a pair decides inclusion; no
  mode keywords) → one composer (topological: definitions before uses; turns by tx; each node once, later refs back-point;
  renders cached by content digest + face). Renderers MAY read the stable db value they are handed. Root gets a standing
  root task. `:seon.render/units` is to be deleted (the walk replaces it). The debug page shows it: `/agent/{id}/debug?n=0..3`.
- **Where context executes** (owner 2026-09-26): in the same context the agent runs in — root's in its cluster; an
  isolated experimental agent's fully contained (nothing escapes SCI, database changes only on its private fork) so root
  merges back what it wants. No separate render fork. Custody is the default world, not a security boundary (AGENTS.md; owner revised 2026-09-26: no isolation for security, agents are cooperative).
- **Rulings of 2026-09-26 afternoon (owner):** (1) *Gate selection* — when fn A changes, run the tests that exercise (call) A
  and those exercising A's callers; call reach, never symbol mentions; a docstring-only edit runs no tests; most modifications
  select a contained set (survey of the average case in progress). (2) *Render forms* go through the same mechanism as the agent's
  own forms, in the agent's env, recorded the same way — ONE SYSTEM (no transient/unrecorded render path). (3) *Boot reads the
  committed archive*, never the working tree. (4) *Change detection is by digest*: updating a definition updates its digest;
  differing digests = changed; reuse keys on digests.
- **REPL niceties are not requirements** (owner 2026-09-26): every REPL result already has a reference id, so `*1`/`*2`/`*3`/`*e`
  and similar conveniences are dropped from scope; only what an agent needs to do its work counts as REPL parity.
- **Caches** (owner 2026-09-26): every cache is `clojure.core.cache` (or another highly regarded library — Datahike's own
  cache for Datahike), never hand-rolled; bounded with an eviction policy (LRU by default); its size is a configuration dial,
  not a code constant. A cached SCI context is worth only the eval tax it saves — evict it rather than grow.
- **Docs live in the codebase and the database** (owner 2026-09-26): move away from external docs; parse, store and link doc data
  in Datahike so docs are integrated with code and refs (language plan §1/§5a/§7; 7.11 issues→tasks). Out-of-date external docs are
  triaged (migrate / evidence / delete), not hand-updated. Design + options: docs-in-code-design-2026-09-26.md (owner decides).
- **Rulings 2026-09-26 evening (owner):** (1) *Arity clash*: the declared form wins whenever its own contract accepts the call;
  defaults are supplied only when it does not (reverses #41's first-argument shortcut). (2) *Docs stay docs, linked by data* (owner): not everything moves into code files — a doc lives where it reads best (function-specific knowledge in the docstring; cross-cutting knowledge in Markdown pages); what matters is that every doc is parsed, indexed and LINKED to the code, schemas, tests and tasks it concerns, so relevance comes from database links, not file location; unlinked prose is dead and is linked up or deleted. *Structured docstrings*: do NOT roll
  out at once — experiment on the namespaces we are working in, verify the indexing is right, the content is useful and not
  repetitive of the code, and it pulls relevant context where no direct reference (require / call) already links it; settle a
  workable format before telling agents; everything migrates eventually. Pages (vocabulary, cross-cutting prose) use the same
  parser. (2b) *Doc format settled after exploration (docs-exploration-2026-09-26.md)*: Markdown docstrings with a few fixed headings (Examples, Edge cases, Costs, Recommendations, Errors, Known issues) + Markdown pages with [[sym]] links for cross-cutting knowledge; context shows EVERY doc item that links the subject; costs are prose stating armed/unarmed and when measured; every Known-issues item links its task/issue id; anything derivable from the call graph is not authored. First slice pilots seon.fault, seon.render.value (+ my.program when free) and vocabulary.md. (3) *Durability of mined tasks/reviews*: not now — writing back to files is a root operation done only when we like
  the updates and want to advance the git branch. (4) *Merge*: build B per merge-task-design-2026-09-26.md (91ba2e3f0, 8 cuts).
- **AI renders are REPL entries, evaluated; HTML renders are hiccup** (owner 2026-09-26, refining the same-day "text"
  ruling). An AI render returns `;;` comments and forms; the one REPL path evaluates each form read-only in the viewing
  agent's SCI world (disposable fork, cached by content digest) and prints form + result — the context is a `clojure.main`
  transcript the agent can rerun (language requirements 5c.3/5c.5). Curated namespaces attach to the agent/task and render as
  `(require '[my.program :as program])` then `dir`/`doc` with contracts, so the wrapper API is explained, never magic. The
  floor printer lays out results; its provenance budget applies to the printed transcript. Dogfooding requires this API to
  be working and intuitive: every valid SCI form at the REPL, not only `defn`.
  Rendering evaluates through the SAME one REPL path (reader, `seon.sci.eval/evaluate`, shown-result, run owners) — no
  second parse, eval, printer, cache or store; a missing capability is a stage value supplied to that pipeline (owner).
- **Store references, never copies:** errors/facts carry a db value as commit id/basis, an SCI ctx as agent/branch identity,
  a request as its evaluation/turn ref, a large value as one blob digest. Opaque world objects are typed identities at the
  admission edge, never walked.
- **References are decided at the one producer** (`var-row` and siblings from clj-kondo analysis). Program references become
  validated refs with named schema keys where the design proves it (`references-as-refs-design`); `:seon.db/reference-to`
  names targets; docstring links come from a markdown library's parse tree (no regex); error members stay symbol values;
  a render function's output is known by its attachment key (`:seon.render/ai`/`/html`) or its declared output schema. The word "face" is being eliminated from code, schemas and docs (owner 2026-09-26): delete every face concept that re-says the key, contract or call site; rename any genuinely needed one to a grounded term (audit-face-concept). Every top-level def is a declaration (P2-A).
- **Agent data model (owner 2026-09-27; binding on the data-model plan):** (1) *Agent-centric, one hop + render*: the agent's
  record is its set of one-hop attachments (session, plan, settings, messages…), each with a declared render function; context is
  rendering what is attached, with detail one requery away; root is the same process with refs to the agents it oversees (a short
  summary render per agent). (2) *Story, not a star*: agent → session (ongoing) → turns (ordered) → evaluations; "run" is a budget
  of turns within a session that lasts unlimited runs; the debug graph is the design review ("click around: no, this is awkward").
  (3) *Every stored fact earns its place* — a named consumer or deleted; never hidden by filters or missing renders; one fact,
  one place, one writer, one direction; no mirrors or back-refs. (4) *Two halves of context*: the agent half (its history across
  all namespaces, tasks, messages) follows the agent; the namespace half (the current namespace's definitions, docs, tests) is
  namespace information, the same for every agent — a namespace never holds agent information. (5) *Current namespace* lives on
  the session, written once by the run's settlement transaction (where SCI ended); a request may carry a namespace, which simply
  sets it. (6) *Compaction* replaces the session's forms (drop old, add e.g. post-compaction instructions); every other attachment
  (plan, settings, messages) stays; nothing is truly deleted (history). (7) *Settings*: the agent links to its cluster's config
  entity; the agent override (e.g. switching provider) layers on it; a settings render shows the important dials. (8) *Messages*
  are their own entities; send/receive = create one. (9) *Modeling rigor*: every relationship is one of the data-modeling guide's
  five choices (component / optional peer / required peer / identity value / settlement-moved edge); a statement about a living
  entity is a ref, an observed name (history) is a value; components validate as whole owning values per write, so trees are
  sized by what each write owns. Research and design lanes read `.agents/skills/data-modeling` and `datahike` first.
- **Tests and platform (2026-09-27):** a test never arms or re-arms the whole JVM; test drivers wait for what production waits for
  (the settlement commit); a program's classpath is a function of its commit (pins, never the checkout); a failed restart is
  recovered by reverting the offending commit's paths (`git show <c> | git apply -R`, commit `--only` those paths) when others
  have staged changes.
- **Faults:** core faults deliver to root by default AND surface as errors in the cluster's MCP replies.
- **Tests:** slow = poorly designed. A test member's world is a branch of a prepared commit (ms), never a file store, full
  canonical population, whole-program rebuild or nested full run. One way to write a test for us and for agents (deftest via
  the REPL path or a file, run by the one `seon.test/run`), one setup/fixture system callable from SCI and files alike — no
  duplicate harness. Runs report new/fixed/still-red from stored evidence and run only against committed program states.
- **Paid calls:** default stays no-provider; a stray cheap call is not an emergency (fix silent fallbacks as correctness).
- **Process owner:** a timeout freezes the descendant tree (SIGSTOP walk to a fixpoint) then kills it; survivors named by (pid, start-instant).
- Standing: no PRs; push personal forks (sci, clj-kondo, datahike); root parks dirty lane edits itself; nuke/reset freely (db data is disposable).

### Definition of done
A component is DONE only when REPL-proven on real data, green on published default, Astra diff-reviewed, measured, and its
duplicates deleted. Unverified commits are debt. Two lessons from 2026-09-25: a scratch-namespace REPL copy runs UNARMED (validate
real values against the declared contract before committing); a new attribute must install before its first write on an
EXISTING store (prove adoption on a branch of the live store, never only on a fresh one).

### Platform state (rewritten in place 2026-09-28, pre-compaction)
- **Default:** 52e163bee, healthy. After the owner's Claude Code restart, MCP works in both JVM and SCI modes, and every
  reply carries the call profile (`:calls`, `:repeated`).
- **Faults are still open on default:** root's system turn refused three times at 17:07Z ("A generated context read
  depends on the agent's own turn-taking"), so the turn proc parked again. The disposition Datom cast is resolved by
  its digest. A publish Datom→Map$Entry cast came from the data-renders branch.
- **HEAD 8bd3d4d91 is ahead of default:**
  - Astra's set-difference publication, 3b86a7ba1;
  - no-crash's contract-gate rule plus cause chains, 8bd3d4d91.
- **Uncommitted work in the tree, from lanes paused for the restart:**
  - batch.clj and turn.clj (one-path-impl-2);
  - cluster.clj and fn.clj (the Astra fixer);
  - seon.batch.edn, seon.test.edn, test/seon/analysis_identity_test.clj, reload_measure.clj and hook_measure.clj.
  Owners resume them.
- **Store:** 57 GB after the GC (165 GB reclaimed). The daily GC is fixed.
- **Tests:** no complete central round yet. r14 and r15 were cut short: by a live crash, then by the pause.
- **Lines:** S8 plus S10 deleted about 1,550 src lines. `src` is roughly 94k lines.

### Lanes to resume (all paused; each lane's detail is in the ledger tail)
1. **one-path-impl-2** (Opus; owns turn, sci/*, batch, forgive, instrument, call_preparation, profile, repl, my/agent,
   test/accretion). Next:
   - the parked root turn fault;
   - the value zoo, with every stage guarded and stages returning error values;
   - derive refusals as the form's error;
   - S10b, then deleting accretion's install-gate machinery;
   - dogfood frictions: one deletion path for `ns-unmap!`, the deftest status render, reacquiring compiled callers;
   - the debug page as one assembly;
   - profiling Part A (8e9a5f320);
   - audit findings 2, 3, 5 and 6.
2. **Astra fixer** (Codex `astra-algorithmic-audit`; owns db, test/runner, test.clj, cluster, schema/datahike, fn, id,
   cache, program). Next: the store-write audit fixes 2–6 (ce8fbea66):
   - batched test recording;
   - lint identity and the 8,332-digest churn;
   - deleting adoption-identities;
   - transaction provenance;
   - the branching factor.
   Then apply `tmp/data-renders/patches/transaction-report-data.patch`. Then shrink S4a's additions and dbe5edf1d.
3. **dogfood-loop** (Opus). Loop 1 on `loop1-uncut-total` (`seon.render.value/tree`, task 36aa005ffd4e): propose →
   prepare → accept → write-back → drill, then loop 2. The B7 SCI fix is in 77dda7bcf (live).
4. **no-crash** (Opus). The census of 518 throws; boundaries proven by forced failures; producer conversions. The gate
   rule landed in 8bd3d4d91.
5. **mcp-thin-bridge** (Opus; owns script/seon/dev/mcp.clj): one stable data entrance, so JVM renames never need a
   Claude Code restart. It needs ONE more owner restart after it lands.
6. **test-data-assertions** (Opus). Phase 2 continues from db_test onward. The lint hook that errors on non-prose `is`
   messages lands after one-path applies phase1-one-path-files.patch. `tmp/test-data-assertions/golden-marker.patch`
   (schema) waits for the next drill batch.
7. **Central round:** a full, frozen-HEAD round once the lanes' landings settle.
Owner decision pending: history retention (the audit says the per-commit cost, not the data, is the waste; fix writes
first).

### Reflection (2026-09-28): what worked, what didn't, and the rule each lesson became
- **Worked:**
  - **REPL workbenches as specs:** the prototype is the code, as a map of named proofs. Astra falsifying at the REPL
    caught real defects before landing: B1–B7, C1–C6, A1–A6.
  - **Owner pivots taken early:** render forms → data; the gate → Var render; the value printer as last stage; supply
    fills what's missing. These cut code instead of adding it.
  - **Measure, then delete:** `:seon.turn/changes` (7.9 s → 92 ms); per-file boot keys (43 → 8 s); precise currency;
    reach digests (16.7 → 1.0 s).
  - **Class-killing guards:** the print lint, the schema-placement gate, override shapes, union refusals.
- **Didn't work, and the rule each became:**
  1. **HEAD failed boot about 7 times.** Each landing was rehearsed unarmed, or only as a `start --head` move, never
     from scratch. Rule: every commit touching schema resources, publication, indexing, boot or arming runs an ARMED
     real-boot drill on a disposable root, BOTH a move and from scratch, before commit, with one scripted command (see
     d0). Root restarts right after each landing batch, so a break is caught in minutes.
  2. **Central test rounds were cut short** (restarts, OOM) and never gave full signal. Rule: a round runs on a
     frozen HEAD window with no restarts until it ends. Rounds get shorter via test agents (concurrency). Reds are
     triaged by the commit that changed them, per round.
  3. **Two OOMs from unbounded prints**, in probes and test paths. Rule: the print lint covers `test/` and `tmp/`
     probe scripts. A test assertion message prints through the value printer. Every OOM dump is analyzed before
     the next round.
  4. **Lanes swept foreign hunks** (3 times) and loaded files into default (twice). Rule: `git diff <file>` before
     every commit (it is in every brief), plus a commit helper that refuses a hunk touching a file the ledger assigns
     to another lane (d0).
  5. **Too many lanes on shared files:** root spent much of its time routing patches. Rule: fewer lanes, each owning
     a whole subsystem, run in sequence where files overlap; patch routing is an exception.
  6. **Code growth:** vectors +1,057 against 312, and renders added lines. Rule: every landing reports net lines, and
     a shrink pass follows each feature before the next feature in that subsystem.
  7. **Lane context and rate limits** killed work mid-flight. Rule: lanes commit early and small; handoff state lives
     in the spec, the ledger and committed proofs, never in a transcript.

### The order (rewritten in place 2026-09-28)
**d0. Stability tooling: DONE except the commit helper (2026-09-28).** `bin/seon drill` (63a5674eb) runs a move plus a from-scratch build, armed. From scratch is green on HEAD, and nuke's fallback chain boots. The class-cache race is fixed. The 21:07 OOM was an `is` message printing a projection (ac2e100e4; test messages become prose via test-data-assertions). Still open: the ledger-aware commit helper; first population 139–200 s (task 50134301bf4e). Was:
- a scripted armed boot drill (move and from scratch, on a disposable root) that every schema, publication or boot
  commit runs;
- re-drill from scratch on HEAD, which proves nuke has a fallback;
- analyze the 21:07 OOM and fix its producer;
- the print lint extended to test/ and tmp/;
- a ledger-aware commit helper.
**d1. The one REPL path (one-path-impl-2):**
- S4a lands after Astra's final verdict on §10.0 rev 2 (turn onto the fold, per-form code evidence, the publish
  stage, future counting);
- then the shared-fork preview cut, since the turn prelude must reach 50 ms;
- then S4b and S5–S14;
- then delete the text-evaluation stage.
Refused-read consumer unions (d4) go into each file as its step touches it.
**d2. Test agents** (design 38e5a6d47 and prototype 33e878596, Astra REVISE addressed): land after S12. Then central
rounds run concurrently in one JVM.
**d3. Renders:**
- value→schema identification from instrumented outputs (the wrapper's validated output schema), target 95% of values;
- the effortless agent-authored render flow (defn in own namespace → promote by ref → discover);
- remaining render pairs by owner: test, namespace, task, merge proposal;
- renders for the value printer's "needs a render" list.
**d4. Refused reads:** precise consumer output unions (census `tmp/one-path/db-read-refusal-callers.edn`, 219 callers).
**d5. A full central round** on a frozen HEAD, triaged by commit.
**d6. Performance:**
- `/agent/root` 1.5 s and `/agent/root/debug` 18.7 s, after P1;
- the first-population cost (54.9 s, task 50134301bf4e);
- `seon.test/run` of one test: 2–5 s of harness.
**d7. Shrink:**
- vectors (+1,057 net);
- the print-census sweep (1,816 lint sites);
- "floor" vocabulary left in other files.
**d8. The agent data model** (Q1–Q7), after the path.
**Owner decisions pending:** deleting the heap dumps (about 47 GB in data/heap).

### Designs (authorities; each revised in place)
| Design | Verdict |
|---|---|
| `repl-session-entity-design-2026-09-27.md` (agent data model: audit, renames, phased plan) | in revision under owner graph rulings; Astra review next |
| `repl-session-prior-art-2026-09-27.md`, `agent-session-vocabulary-2026-09-27.md` | research inputs (vocabulary: session, turn, run = derived budget of turns, evaluation, compaction, message, task) |
| `config-read-scope-design-2026-09-27.md` + review | REVISE, folded into the data-model plan |
| `eval-path-audit-2-2026-09-27.md` (Astra) | verdict NO; §5 being redesigned to the owner's one-read / observe-at-definition / exact-span invariant |
| `valid-clojure-corpus-probe-2026-09-27.md` | evidence: SCI fine, Seon layers F1–F12 |
| `vector-storage-design-2026-09-27.md` | cell encoding recommended; Astra review pending |
| `red-triage-3/4-2026-09-27.md` | today's red classification (slices landed; residue above) |
| earlier (task-context-install, references-as-refs, one-repl-language, lost-error-seams, contracts-out, derive-once) | unchanged; re-read before starting |

### Queued (tasks logged today, all in resources/seon/tasks/)
seon.turn: nested in-ns loses the run; gate exception refuses the whole run + non-ExceptionInfo loses the reply; analyze-settlement
unreached. seon.sci.eval: gate copy lacks the run's rows; refused run's program lingers; own-namespace class imports; agent defn
refused by install gate. seon.render.value: count as entity ref; throwing lazy seq shows (); uncut string has no total.
seon.cluster: MCP reads JVM-wide roster; MCP reply without db prints unbounded; incremental-publication redefines Vars.
seon.fn: build-artifact is a second indexing path; fn-test redefines Vars. seon.instrument: violation quadratic.
seon.schema: definition-row discards a projection. seon.db: admission without projection embeds its error. seon.error:
refusal HTML reads as fragments. seon.render: unit sweep may have retyped non-map inputs. seon.config: compute concurrency has
no executor. seon.schema.datahike: parity baseline stale. seon.test.cache: dependency revisions are not test inputs.
seon.cluster.message: unresolved sender logs unbounded.

### Operating checklist
- ONE PERSPECTIVE (owner, repeated): root acts as an agent at root's REPL (MCP SCI) — merges, proposal review, task
  work — never JVM-mode calls into internals (read-only diagnosis only). A missing agent entrance is a defect to fix.
- After `bin/seon start --head`, read its WHOLE result and then `bin/seon status` `:seon.operator/source-root`: a move
  whose JVM misses readiness or adoption falls back to the previous archive and still prints a pid and ready-ms
  (operator.clj:1319-1352). 2026-09-26 root filtered the output and ran three test passes against a stale archive.
- Publish/adopt: `bin/seon init --dev default --changed <existing paths only>`; park dirty lane files first; after a fork or
  dependency bump run `(seon.fn.analyzer/analyze-dependencies!)` (or the shell form) before restart.
- Tests: root runs serially with `tmp/orchestrator/run-verify2.sh LIST OUT`; never in a publication window; `--include-long`
  for long members (skipped long members show `passed? false`).
- Probes: bounded loops only (default Vars are direct-linked; republishing never stops a running loop); scratch copies are unarmed.

### Where evidence lives
Ledger `tmp/orchestrator/file-ownership.md` · verify outputs `tmp/orchestrator/verify-*.out(.d)` · red routing
`tmp/orchestrator/red-routing-2330.md` · landing notes `docs/prds/agent-platform/landing/` · issues `docs/seon/issues/` ·
lane preamble `tmp/orchestrator/fix-lane-preamble-2026-09-24.md` · parked patches `tmp/orchestrator/parked/`.

## New platform findings (2026-09-25)
- Small publication 14.6 s: 9.4 s was relinting all 179 callers of zero-argument functions whose arity rows never compared
  equal (empty :seon.fn.arity/arguments written, never stored) — FIXED 5d8309442 (root applied the lane's patch; regression
  green). Remaining >1 s: seon.db arity check rebuilds its whole-program input on a branch's first write (1.2 s,
  db.clj:3738); Datahike transaction ~1.8 s. Analyzer-digest reach KEPT (root ruling: seon.db/schema/program genuinely shape
  rows; narrowing risks stale rows, a marker is a maintained list) — a db.clj edit costs one full reanalysis until full
  analysis is cheap. Restart procedure: checkpoint (init --dev) before down/start.

- A namespace loaded after boot arming is NOT armed (4d849406e): boot arms only src/ namespaces (cluster.clj:2419, by design
  in ed62a3e06); a test request loads its namespace lazily via requiring-resolve inside the member body (test.clj:1364) and
  nothing arms it — loaded test namespaces 3 of 46 contracted Vars armed vs 1,908 of 1,952 elsewhere. Ruled: seon.test/run
  loads each selected test namespace before members run and arms exactly those through instrument/apply! (lane
  fix-late-load-arming). Also: script/ and dev/ namespaces are never armed (seon.operator 0 of 27) — not yet assigned.
- seon.test/failure-text (test.clj:39) prints a blob hint `(seon.blob/get digest)` that refuses when followed (get takes a
  connection and a digest) → queued for the test.clj owner.
- Every seon.db read on an as-of view rebuilds its projection (2,334 ms for 18 reads) — the cold as-of projection item.

## Recurring classes — root causes, not symptoms (owner 2026-09-24: "step back and find root causes")

1. **Unbounded printed values kill default** — FOURTH OOM 2026-09-24T23:27Z ("Requested array size exceeds VM limit",
   10.8 GB dump java_pid39156.hprof) during seon.test.overlay-world-test: a test error carried a whole program overlay as
   its offending value and seon.test.runner failure-identity/failure-report still pr-str whole expected/actual. Fixed so far:
   failure-message datom, runner `is` message (10d629c54), flow/store panic print (d8d80331d). Now: lane
   fix-runner-failure-print-bound (identity by digest, report through the bounded renderer) + S2 lane bounds its thrown error.
   Class rule: an error's inline data is a bounded summary; the whole value goes to the blob tier; every text sink uses
   the one bounded renderer.
2. **Custody inferred from a label, not the held connection** (agent.clj:205 → 43 "fresh owned branch" refusals; latent
   live-root escape through a shared context-state atom). Fixed b2fcb5225: the writer records the branch the transacting connection holds; an isolated acquisition gets its own context-state (a member's inherited root now refuses loudly instead of borrowing live root). Residue: 17 fixture sites hand-write the label branch (turn suites — after the subsumption review, survivors only); my/agent.clj mode now reads the acquisition's own entry (5a60f51d8). NEW: the MCP entrance acquires branches with a nil agent id, so (my.agent/branch ...) refuses on MCP branches — the entrance should acquire as a named agent. seon.env/supplied-agent-id puts the whole environment in :seon.error/offending (render 2.5 s; class 1). Design question for the owner: should inherited agents run inside a member at all?
3. **Hand-built rows in fixtures** (ns, fn and test rows missing required digests → most save-gate reds). The rows should
   come from canonical writers; Namespace rows DONE (4 commits, 53ae022b2: 31 digest reds → 0). Fn/test rows (59 sites, 45 carrying keys the analyzer does not derive) and agent rows without :seon.agent/branch (first failure in 22 tests; agent-tx also makes stewardship) are NOT hand-converted: they are harness S5 material (seeds through canonical writers) — owner rule, do not repair what the harness replaces.
4. **Every save runs a heavy save gate on the shared JVM** ROOT CAUSE FOUND (36af5c620, projection-call-cost): instrument keeps one compiled wrapper per function keyed on the exact projection object it last saw, so any fresh-but-equal projection recompiles wrappers (14–23× per call); carry-derived-projection builds a fresh 460 ms projection every publication; every seon.db read pays ~0.11 ms and published-index-rows reads rows one at a time (866k reads ≈ 25 s); canonical-value-string recurses through its own contract wrapper. Fixes: F1: caller converted 6a6db1862 (equal forms 3,381 / keys 5,565; 465 ms → 0.02–0.09 ms, only on 4 of ~36 publications); ROOT applies tmp/fix-f1-carry-derived-projection/delete-carry-derived-projection.patch with fn_test.clj:1966 → @connection once fix-f4 releases fn_test; then seon.schema/projection-delta has no src caller (delete); platform regression seon.cluster.source-test/publication-hands-its-commit-projection-unstamped to run at integration, F4 DONE 36abe8bef (9 reads instead of 901 for two files; full reanalysis 24.8 → 16.0 s, still over 10 s; publication reads the same rows up to 3× at fn.clj 2505/2912/2946; whole-program index rows overflow on the my.agents.root steward cycle — issue whole-program-index-rows-overflow-on-an-agent-steward-cycle.md); steward cycle FIXED f4c56ad12 (non-program refs written as their declared identity via :seon.program/partition; 12,460 rows in ~750 ms; a non-program entity with no unique identity would still inline as {} — should refuse), F2 delete per-call projection selection in instrument (~−100, needs a recompile counter first), F3 hasch (scheduled), F5 sweep call-with-projection (~−650), F6 lazy registry (~−1,260). F2/F5/F6 are one-happy-path wave 3 — owner go. (ROOT OBSERVATION: .claude/settings.json runs bin/seon-hook on EVERY Pre/PostToolUse event in every session, and :shell-writes compares every src/test file digest with that session's previous event — so any session's next tool call, even a Read, publishes OTHER sessions' edits; N sessions race to publish the same change — read-only lanes triggered 14.9 s publications; fed to the publication research) (plus: a lane that commits without publishing blocks every later publication that reloads its file; the hook never retries; one held dependent refuses an unrelated adoption — issue a-db-adoption-refuses-while-another-lane-edits-a-dependent.md) (65 reaching tests, ~100 s, 274% CPU; refresh-source! 107 s max;
   call-with-projection 34,393 calls / 362 s). With reds from class 3 each also prints class 1's giant errors. Cost follows
   the whole reach, not the changed inputs — the projection-passed-explicitly item and the harness's per-member cost target.
6. **A red baseline blocks every adoption.** The save gate correctly requires every reaching test green; fn.clj reaches 13 fail + 4 error that pre-date today (fixture refusals = class 3), so correct fixes publish but do not adopt (f4c56ad12). The gate is right; the red baseline is harness S5 work — the strongest argument for the harness go.
5. **Tests redefine default's Vars** (four replaced roots from web_debug_test). Harness S-slices + redef census.
- Seen in these lanes: SCI cannot interpret test macro seon.render.web-test/with-server (18 errors; same class as affected callers); a member branch inherits default's open faults (issue test-fixture-worlds-inherit-...); adoption does not load the test namespaces a --test request names ("no executable test Var" until loaded); a save gate spent 47 s of 59 s in adoption while lanes published concurrently (class 4).
Rule while fixing: code on the one-happy-path deletion list is deleted or routed to its surviving owner, never repaired.

- Tool: the operator cannot read PREPL values holding Vars (#'seon.test.runner/record-tx in retained tx-data) → every such hook result is "degraded". FIXED 1a3a9acbe + ce149b23d (clojure read-string, *read-eval* false, tagged-literal default; thrown values surface their own message; 8/8). Root reloaded seon.operator by hand: script/ namespaces loaded by the JVM are not published (issue script-namespaces-loaded-by-the-jvm-are-not-published.md). seon.adoption-diagnostic-test bound 1024 bytes vs 2596 (a thrown io-prepl :val carries its stack trace) — red before; decide bound vs kept trace.

- Delta branch deleted 104239f33 (−69: build-projection's projection+delta branch, compose-projection-data, reverse-dependencies). Its adoption waited ~6 min on other lanes' uncommitted edits, then 20.8 s (class 4).
- Schema rules DONE: projection-delta deleted (53a038419, −38); assert-config-display! at admission (e9f6dc805); :inst refusal and [:maybe X] were already fixed (ab7cc7190, a70688cc8) — the :inst regression never ran its path because the runner hands every test a projection (1cc45977a binds it nil). 9 pre-existing reds each in seon.schema-test and seon.config-test.

- Render pair selection FIXED 14a9291ad (an all-optional map declaring a render pair gets a shape row; census: 358 pairs, problems was the only unselected one). LIVE since d8951c477: every carried-projection key includes a builder digest (stored definition digests reached from seon.schema/load-projection, 244 symbols; 1.6 ms once per loaded commit; one 402 ms rebuild); problems now selects ai-prose. Follow-up: share one reach-digest function in seon.db for analyzer-digest (a whole-program query, 8.8 ms) and the builder digest (index walk, 1.6 ms).
- A new MCP branch's first evaluation lists 10 inherited errors from other agent branches (since 0) — branch-errors should start at the branch's fork basis. A tiny SCI eval allocated 667 MB (acquisition).

- SCI host names FIXED 4f880a823 (an interpreted caller also gets the loaded namespace's aliases): the 24 live class-2 reds were all `effect/request!` in my.edit — its alias comes from a top-level (require '[seon.effect :as effect]) outside the ns form, which namespace rows do not record. ROOT CAUSE of that require: my.edit/my.fs/my.shell must define predicates before seon.effect loads the schema population that resolves them (load-order coupling) — the one-happy-path "predicates in schemas → Malli fork m/eval" replacement removes it; do not patch the requires. 19 of the 24 are now correctly red on another lane's unadopted runner.clj edit. Stale test: branch_execution_test interpreted-callers-resolve-unchanged-names-to-compiled-vars expects seon.cluster/serve! interpreted (host-bound since 55af9e04b).

- Retired-shape tests: 16 reds cleared (041102a76, 3791dcfd2), 9 more committed unrun. Found: creation-tx still refuses :seon.agent/branch on fixture branches although branch-call answers (agent.clj:186-196; census class 3's 27 reds, my.plan-test 21); orphan ;;; Development Helpers banner at the end of schema.clj breaks the reader round-trip test; instrument-test sovereign-fork (base-ctx lacks the interpreted wrapper, :record still throws). Node-not-found has an existing CRITICAL issue: published-head-references-never-written-index-nodes.md (2026-09-23).
- Unstarted members DONE (2a543c27a deleted tests not counted; cb632bd81 unstarted members read unknown, the run records :seon.test.run/stopped with its cause, a stopped run no longer covers a later request). Default: failed 658 → 571, unknown 1 → 135. Open: a test's time bound covers only its body, not the request (111 s vs 60 s declared); a member stopped unfinished may never get its exit recorded (seen once in a draft); one-request-test red needs a parent comparison (queued).
- S3a DONE d77a9461a (call-with-program-change marks the program unknown before touching Vars; record inside the lock; a failed reload/arm/record leaves members refused with the cause; status shows program-unavailable). Open: refused members counted red not unknown (test.clj:184); evaluations/seeds not gated (S3b); the failure is status-only, not a delivered fault; the mark blocks every host member JVM-wide. Adoption took 7 attempts (6 refused by other lanes' edits; 136.8 s) — class 4.
- ns-usages span filter DONE a2bf4d8ed (requires/aliases/refers from every kondo usage by :from; only my.edit/fs/shell rows change; 4f880a823's caller-bindings deleted as redundant; imports/:rename keep the ns-form span — kondo gives them no :from).
- agent-eid refused read DONE 053a91b92. basis-t nil REFUTED as a release bug: it comes from with-redefs stand-ins of seon.cluster.prompt/prompt in test/seon/cluster/agent_test.clj (~837, ~899, ~1002) — redef class, harness S5; unexplained: capture-tx's input contract was not in force at those occurrences (re-arming gap during adoption?). Same refused-map-through-[:int] defect in seon.turn/latest-answering-turn-t.
- Fault delivery: 76e4e0ab6 (a fault with no steward and no attributed agent reaches root with or without an exception class) and addab5ae5 (an oversized fault keeps its cause chain: class, message, first-party frames). :seon.error/chain is blob-only by design. Test-branch faults not copied to cluster: premise wrong — :seon.test.run/branch is the result branch; members' agent-* branches carry no test marker; design (≈15 lines in seon.test member-result: record the member branch, copy? false in fault.clj:144, count its errors as test errors) → S3 lane follow-up. NOTE: 76e4e0ab6 sends MORE faults to root's paid DeepSeek turn once installed — OWNER RULED: record, no LLM wake (dial default off) — lane fault-no-llm-wake, lands before the restart.
- S1 DONE (aed1b3838 routing owned by the execution handle; d6e93ce72 release marks routing released, joins every graph, closes only after exit, keeps resources on a failed join; 10/0/0, and red without the fix). RESTART NEEDED (boot handle routing; two leaked probe graphs). Verification queue: tmp/orchestrator/verification-queue-2026-09-24.md. Class-1 residue: a failed assertion printing a routing entry OOMed — runner failure-identity/failure-report still pr-str whole expected/actual (runner.clj).

- ROOT LESSON (21:4xZ): with hook publication paused, a restart loads code from the checkout, but schema
  attributes and test rows install only through publication. S3c's :seon.test.run/loaded-commit-id made EVERY
  test request refuse ("Bad entity attribute") and new tests read "not eligible". Root publishes at each
  checkpoint with `bin/seon init --dev default --changed <paths>` (zsh: split the list with `${=paths}`);
  102 files adopted in 11 s. Start should publish or refuse when the checkout differs from the published
  program (schema resources especially), rather than run mismatched.
- Verification results: tmp/orchestrator/verification-queue-2026-09-24.md (8 of 17 focused regressions green; reds returned to their lanes).
- Datahike fused-root pin: fork commit fa0cc1a7 (+19/−6, regression datahike.test.fusion-root-pin-test);
  fork test RED (the test does not reproduce the unwritten-root precondition; count 50 vs 51) — back to the lane; no gitlink bump yet. force-branch! was never
  exposed (it writes fused roots); only commit! was.

## Integration (root, at the end of the fix round)

1. Default already runs the nuked store from the checkout; after the running lanes land, `bin/seon down && bin/seon start`.
2. Verify HEAD loads; run each changed namespace's tests (landing notes list them):
   seon.id-test, seon.problems-test, seon.cluster-test, seon.cluster.publication-delta-test, seon.cluster.mcp-test,
   seon.fn.analyzer-test, seon.sci.entry-gate-test, seon.sci.eval-test, seon.schema.admission-gate-test,
   seon.schema.agent-nil-contract-test, seon.db-test, seon.error-test, seon.turn-disposition-test, seon.cluster.prompt-test,
   seon.render.transcript-test, seon.render.web-debug-test, seon.cluster.process-test, seon.shell.jvm-test,
   seon.maintenance-test, seon.fn-test, seon.sci.reader-test, seon.dev.mcp-bridge-test, seon.test.* (after runner lanes).
3. Triage reds by the three questions (deleted machinery / retired assumption / surviving owner); report to owner.

## Queued correctness (file held; launch when released)

- error/commit-tx callers (sci/eval.clj:1889, cluster.clj:3432) still record capped faults with no blob — route through recording + evidence-stage; declare :seon.blob/staged-writes on :seon.error/recording (seon.error.edn).
- test/seon/schema/datahike_test.clj unsupported-database-attributes-refuse-at-one-rule is stale (register! outside a registration delta now refuses) — retired assumption.
- Hook publications refused when several lanes publish at once (fix-history-keeps-its-rules item 4) — publication concurrency; with adoption exclusion (da9910fec) re-measure.
- Cold as-of projection 263–273 ms per time point, re-derived each new commit (seon.db keying) — performance.
- HIGH (one shared JVM): tests that redefine default's Vars (with-redefs / alter-var-root) break everyone — e.g. fn_test.clj:1022 redefines seon.fn.analyzer/analyze JVM-wide, refusing concurrent publications ("analysis must not run"). Census + rewrite through injected worlds (law "Protect execution ownership"); launch after sweep-hand-built-ns-rows releases its files. Live evidence 2026-09-24: default faulted with four replaced roots from web_debug_test.
- Publication half-applies on a red save gate: some changed files loaded, a schema not stored (leftovers-1 landing) — publication must be all-or-nothing / leave affected execution unavailable until convergence (cluster.clj owner, after fix-adoption-excludes-tests).
- A stored :or attribute drops its children's nil refusal (docs/seon/issues/a-stored-or-attribute-drops-its-childrens-nil-refusal.md) — schema/datahike.clj.
- Error evidence cut with no blob kept (root turn 46118 render fault on :seon.render.history/entries: reason unrecoverable) — keep-the-whole-cause violation in the error writer (error.clj).
- Root's idle prompt takes 5,079 ms (66 KB); profile top seon.schema/canonical-value-string 723k calls / 9 s — performance.
- test/seon/schema/datahike_parity.edn is stale by 322 changed forms, 4 missing and 28 unexpected attributes (a hand-maintained baseline — a smell): after fix-leftovers-1 lands, regenerate its current sections from the schema, or replace the baseline file with a derived check; decide which (landing lane-fix-leftovers-2-2026-09-24.md).
- "Isolation requires a fresh owned branch." (src/seon/cluster/agent.clj:848) stops seon.issue-settlement-test's first test early — diagnose.
- Publication refuses while :cluster-default-candidate holds a connection (issue publication-refuses-while-the-candidate-branch-holds-a-connection.md); test worlds inherit the live cluster's errors/messages (issue test-fixture-worlds-inherit-the-live-clusters-errors-and-messages.md).
- Subprocess descendants die of SIGPIPE after the root exits (docs/seon/issues/subprocess-descendants-die-of-sigpipe-after-the-root-exits.md) — needs an owner ruling on stream handling.
- test/runner.clj + test.clj: reach leaves omit fixture metadata (stale green); test diagnostics drop causes; `find-var` on a referred Var makes the live-cluster drift snapshot always empty (issue filed).
- refers slice (tmp/fix-entry-2-refers.patch): needs script/seon/dev/mcp.clj, test/seon/custody_stability_test.clj, src/seon/test/runner.clj.
- error.clj: "Resolved" shown after recurrence (error.clj:1726) — after errors-on-their-branch.
- schema/admission.clj: restore one core guard — core :any/:some must carry the exemption + reason annotation.
- Ambiguous-nil producers: census running → per-file fix lanes; then add the one-sentence rule to the data-oriented-clojure skill.
- Tests red at HEAD before today: seon.db-test (19, EDN codec fixture), seon.sci.reader-test (19, symbols-as-strings), mcp_test fake connection, bounded-boundary-census (34 script subjects), shell time-limit fixture, fn-test members over 5 s bound, resume-turn test 16.7 s.

## Testing framework (owner 2026-09-24: "we need to nail this")

- Env-reading macro callers: fixed 55af9e04b (host-bound when using a JVM macro from a namespace SCI does not interpret, from the loaded Var's :macro; clj-kondo does not flag dependency macros). Over-approximates (timbre/warn also host-bound): no analysis fact separates &env readers. 0 acquisition refusals, 722 ms (was 1,052 ms with 5 refusals).
- Partial render profile: fixed at entry (c3c48bde2): request-profile refuses a partial carried profile and tags a carried config refusal; the log's "profile" was an effective-in refusal for cluster "flow-evaluation" (producer already removed by c1cd41b25). Stale test expectations: seon.render.call-test:28-29, seon.flow-test:948 (fixture has no config row for its cluster).
- Turn-suite subsumption DONE (c8621765a, read-only): 152 tests → 98 keep, 51 subsumed, 3 delete (−54 tests, −1,909 lines). Deletions wait for the harness go (with S12). Survivors to trim: retirement checks, timing-as-behaviour asserts, call-count spies; turn_test:1386 declares 60 s with no authorization; provider-progress test sleeps 2×10 s.

1. Survey DONE (01c6571d6, d0d9dad21): one-test-harness/test-survey — harness 1,913 / platform 230 / delete 19; redef-census classifies the 351 sites.
2. Design DONE (623918f70, one-test-harness/design-2026-09-24.md; capabilities dropped: effects through config facts or owner arguments) — OWNER RULING 2026-09-24: the harness adds nothing the agent path lacks; a test is an agent attempt with a deftest body instead of model turns. Every lesson lands in the ONE agent path (retire stops the graph and awaits exit; attempts record base + program identity; capabilities as world values) and the harness only composes it; harness-only concerns are limited to running a body and recording test evidence — anything more is a smell. Design: ONE harness through the one interface — world = checkpoint fork + seed branch + capabilities as values; seon.test/run fork→inject→run→record→unlink; each member records the program it actually ran (root ruling on adoption-between-members); everything else is a platform test.
2b. Design input: agent graphs keep stepping after their test branch's store closes (docs/seon/issues/an-agent-graph-steps-after-its-test-branch-store-closes.md) — the harness's unlink must stop the member's graph and await its exit before releasing the store.
3. Astra review DONE (218aa33dc): revise before implementation, 9 findings, all accepted by root; open question ruled: base plus the branch's differing program rows. Revision RUNNING (revise-test-harness-astra, in place); probe-test-custody-escape verifies finding 1 against today's tests. Then owner go; then scripted conversion by class with deletions.

## Scheduled (owner: wait for fixes before large items)

- Committer-graph deletion (~470 lines): needs turn.clj:5359-5406, render/web.clj, cluster/wake.clj, cluster/agent.clj, cluster.clj, cluster/boot.clj (join-launcher-errors! now inert), schemas seon.flow/boot/cluster.wake/render.web (landing lane-fix-fault-route-2026-09-24.md; issue durable-faults-cross-dropping-channels.md).
- Launcher compute half: submit!! referenced by dead turn.clj:3290-3334, env_test.clj, instrument_test.clj.
- First-ever delivery gate still in error.clj:1393.
- hasch.core/edn-hash identity switch (prereqs in ledger).
- Ingestion slice: analyzer stores :seon/tasks, :seon/must-read, :seon/see-also, ^{:seon/task}, and :seon.test/seed (entries: literal tx data, qualified seed symbol, or inline fn — owner 2026-09-24, harness design §2b) as edges; contract schema keys as keyword edges; task EDN loader; dangling refusal (requirements 7.9, 7.9b, 5a.5).
- Checkpoint execution model (§4b with review corrections): base/cluster/task checkpoints, host Vars installed directly.
- Render pipeline 5 slices (clojure.main grammar, one printer, faults opener done, openers as data + help topics, retire transcript projection).
- Proof entrance: run a task's named deftests through seon.test/run on a throwaway fork (~130 ms target).
- One-happy-path waves 2–4 (dead code, dependency replacements, collapse second paths).
- `bin/seon faults --wait` (after errors-on-their-branch).
- Datahike fork gaps: the writer's next-write starting value must carry committed revisions (today temporary revision ids on the writer's in-memory value differ from the published value's commit-id revisions on 130 attributes at the same :max-tx, writer.cljc:246-253, db/transaction.cljc:1064-1070) — then land tmp/fix-unbounded-revisions-slice.patch (~25 lines; needs seon.test.run.edn) to make program-written-since? 0.2 ms instead of 0.1–0.7 s; total comparator, merge base, as-of revisions, avet seek cast, pull-find dependencies; Malli seed; SCI bind-root! on host Vars; clj-kondo cache/in-memory/rename; edamame ex-data.
- Projection passed explicitly (call-with-projection 79,689 calls; resume-turn 16.7 s) — includes SCI row 5 remainder: base contexts carry no environment; projection-state invents "current-src" for ~118 call sites (docs/seon/issues/sci-arming-guesses-the-cluster-from-a-database-row.md).
- Live DeepSeek loop test — only once the context is not garbage.
- Update the plan artifact (still shows the v0.2 task list).
- MEMORY.md is over its size limit: consolidate.

## Owner rulings 2026-09-24

- ab7e4a643: REVIEW NOW (Astra review launched).
- Entry gate: GATE ONLY, no :warn dial.
- exp-* branches: DISCARD at reset.
- Ingestion slice: AFTER THIS FIX ROUND (first language work after the reset + namespace tests).

## Queued (from config-read-2, 91c9079a9)

- `:seon.boot/cluster-name` supplier: one row in config/default.edn + small supplier in src/seon/env.clj, so (agent-history {}) works; transcript ledger's per-model calibrations still store refusals as calibrations.
- Tests passing only :seon.db/db to render/request-profile will now refuse (expected; count at integration).
