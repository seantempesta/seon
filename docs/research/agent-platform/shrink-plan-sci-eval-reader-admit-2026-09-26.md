---
type: audit
lane: shrink-eval-opus
date: 2026-09-26
files: src/seon/sci/eval.clj (5,022 lines), src/seon/sci/reader.cljc (857), src/seon/sci/admit.clj (870), their tests
status: plan only; no source edits (owner 2026-09-26: "Don't break shit until we understand and have proper refactor plans in place"; "prioritize slow shit first")
evidence: HEAD b6660a34b; live default pid 89341 (start 2026-09-26T21:35:37Z), JVM mode, read-only, MCP session shrink-eval-opus
companion: docs/research/agent-platform/eval-path-audit-2026-09-26.md (the per-form design; cited here as "audit Dn/Sn", not repeated)
---

# Shrink plan: seon.sci.eval, seon.sci.reader, seon.sci.admit

**What exists.** `seon.sci.eval` has three jobs. It acquires a program into an SCI context: base context, forks
and loaded-program comparison, eval.clj:2079-3775. It evaluates one form: `evaluate` plus admission,
restoration and printing. It installs settled rows. `seon.sci.reader` reads agent text into events and adds
head-check declaration facts. `seon.sci.admit` is a streaming, byte-bounded EDN projection of values that
leave SCI. **Principal finding:** the slow path is acquisition, not evaluation. Its cost follows the whole
program (5,385 function rows, ~460 namespaces, ~4,000 host Vars), not the rows a branch changed. The memo
that should hide this cost misses 90% of the time, because the memo is keyed per branch connection.

## 1. Slow paths first

### Live profile (pid 89341, cumulative since 21:35Z; `seon.profile/observation` over my three namespaces)

| definition | calls | total ms | mean | max ms | note |
|---|---:|---:|---:|---:|---|
| `call-detecting-program-change` | 201 | 227,944 | 1.13 s | 146,305 | **inclusive of the work it wraps** (test bodies; 146 s = one test run). Its own cost is two derefs. Not a target |
| `acquire!` | 104 | 16,365 | 157 ms | 2,158 | |
| `base-ctx` | 50 | 10,993 | 220 ms | 1,067 | |
| `derive-base-ctx` | **45** | 10,572 | 235 ms | 1,027 | **45 derivations for 50 lookups: 10% hit rate** |
| `acquire-program!` | 45 | 10,438 | 232 ms | 1,022 | all of `derive-base-ctx` |
| `evaluate` / `trusted-evaluation` | 92 | 8,331 | 91 ms | 1,052 | inclusive of the agent's form. perf-eval-overhead measured own cost at 1.8 ms |
| `evaluate-candidate` | 21 | 4,270 | 203 ms | 584 | test gate for one defn (audit D11) |
| `disposable-copy` | 21 | 2,739 | 130 ms | 355 | a whole branch plus fork per gated defn |
| `admit-program-change!` | 81 | 2,592 | 32 ms | 347 | includes the clj-kondo run (12-25 ms/form, audit §0) |
| `install-evaluated-rows!` | 20 | 2,206 | 110 ms | 249 | |
| `function-digests` | 90 | 1,992 | 22 ms | 301 | a query over **every** function row |
| `analyzed-rows` | 24 | 1,867 | 78 ms | 232 | clj-kondo |
| `fork-cluster-ctx` | 115 | 1,516 | 13 ms | 135 | re-arms every interpreted function with a `pull '[*]` |
| `installation-covers-program-change?` | 20 | 732 | 37 ms | 156 | history scan already removed (ab87961d5); task 361088de3d56 is stale-open |
| `acquired-database?` | 213 | 764 | 3.6 ms | 173 | runs on every form (`evaluate`, eval.clj:4535) |
| `loaded-program` | 51 | 487 | 9.5 ms | 36 | `d/commit-as-db` on every call, never memoized |
| `copy-host-var` | 178,554 | 115 | ~0 | 2 | ~4,000 per derivation. Cheap per call, but proportional to the program |
| `seon.sci.reader/read` | 432 | 257 | 0.6 ms | 14 | fine |
| `seon.sci.admit/admit*` | 710 | 342 | 0.5 ms | 61 | fine |

### One derivation, explained (`seon.profile/begin` → `derive-base-ctx` on default → `explain`)

The derivation took 398 ms elapsed (a second run took 629 ms). Its inclusive growth:
`acquire-program!` 395 ms; `db/q` ×7 300 ms; **`function-digests` ×2 267 ms** (the branch's and the loaded
program's, each over all 5,385 rows); `db/pull` ×469 52 ms (one per namespace); `loaded-program` 9 ms;
`build-base-ctx` 3 ms; `copy-host-var` ×4,004 2 ms. Point probes on warm values: `loaded-program` 8.0-8.6 ms per
call (uncached `d/commit-as-db`); `function-digests` 7-15 ms on a warmed value, ~130 ms on a fresh branch value;
`load-core-namespaces!` 59 ms cold and 0.5 ms warm (Datahike's query cache); `acquisition-attributes` 0.02 ms warm.

### Why the memo misses (live)

These are the four keys held now in `base-context-cache` (eval.clj:3285):

| `:datahike.cache/connection-id` | attribute revisions in key | loaded commit |
|---|---:|---|
| `[store :agent-21ac596639c2]` | 86 | 6ab83b0e… |
| `[store :host-test-c8b539dd2cda]` | 9 | 6ab83b0e… |
| `[store :agent-f07bdd01b972]` | 86 | 6ab83b0e… |
| `[store :defn-scan-opus]` | 0 | 6ab83b0e… |

All four compare against one loaded program. `base-ctx-key` (eval.clj:3399) keys on `revision-basis`, which
includes the connection id and generation (eval.clj:3307-3322). A branch's first own commit, whether a turn,
a receipt or a test write, therefore gets a key that no other branch shares. The `d/listen` observer
(`watch-program-identity!`, eval.clj:3357) only lets a branch *opened at* a commit read the parent's basis.
That is why every agent, every MCP branch and every test request pays a full derivation: 235 ms mean, up to
1-3 s cold. Task d215b1ebde7c measured a 2,925 ms miss and 11.2 GB allocated on a fresh branch.

### Slow-path fixes, each with what its cost is proportional to

**F1. Memoize `loaded-program` by commit id** (eval.clj:1645). *Now:* one `d/commit-as-db` per call,
~8 ms, ×51. *After:* one per loaded commit. It is a function of an immutable commit. It goes into the one
cache (`seon.cache`, cache-central's landed owner) as a new kind with a config dial, not an atom. +6/−0 lines.
**Regression:** `an-adoption-record-moves-the-loaded-program-every-acquisition-compares-against`
(branch_execution_test.clj:277) must still see the new loaded commit after an adoption. The memo is keyed by
the commit the record names, so a moved record is a new key.

**F2. Memoize the loaded program's digests by loaded commit id** (`function-digests loaded`,
eval.clj:2832). *Now:* one of the two full-program queries per derivation (~130 ms of 398 on a fresh value).
*After:* once per loaded commit. The branch side stays one query. +4/−0. **Regression:** the existing
acquisition tests (branch_execution_test.clj:62, :345, :428); override detection must be unchanged.

**F3. Key the base context by program content, not by branch revision** (the big win, and a design call; see
the options below). The base is a function of what `acquire-program!` reads: the loaded commit; the
branch's function, test and namespace rows *where they differ from the loaded program's*; the projection; and
the fault recorder. For the common branch (an agent, MCP or test branch with no authored overrides) that
difference is empty. Every such branch then shares one base per loaded commit, so 45 derivations become
about one. The key costs one digest query per new commit (7-15 ms), which F2 halves. **Deletes**
`revision-basis`, `program-basis`, `commit-id-of`, `remember-program-identity!`, `program-identity`,
`watch-program-identity!` and its two `d/listen` call sites (eval.clj:3698, :3773), `acquisition-attributes`,
`program-identity-cache`, and the lineage branch of `acquired-database?` (eval.clj:3509-3523). That is about
−150 lines, +40.

**F4. `acquire-program!` reads only what it installs** (eval.clj:2774, 328 lines). The function-row query
fetches `?source` for all 5,385 rows (eval.clj:2822). Source is needed only for the `interpreted` set and
`kernel/cache-program!`'s lazy install. Every namespace is pulled with four nested selectors (469 pulls),
although only `selected-namespace-names` install definitions. *Verify first:* whether
`install-first-party-namespaces!` and `install-declared-classes!` truly need every namespace row, or only the
imports and aliases, which one query could return. Estimated saving 50-150 ms per derivation, before F3 makes
derivations rare. Lines ≈ −20. **Regression:**
`interpreted-callers-resolve-unchanged-names-to-compiled-vars`,
`an-interpreted-caller-resolves-its-compiled-namespace-aliases-privates-and-core`.

**F5. The candidate gate's disposable branch** (`disposable-copy` 130 ms, `evaluate-candidate` 203 ms per
gated defn). This is audit D11/S5: gate through `seon.test/run` on a disposable branch, and delete
`evaluate-candidate`, `disposable-copy`, `run-candidate-test!`, `candidate-test-result`,
`install-candidate-function!` and `accept-candidate!` (≈ −224 in eval.clj). It needs turn.clj
(`gate-function-install`, turn.clj:3352), which shrink-turn-opus holds, so it is cross-lane. With F3 in
place, the copy's acquisition becomes a memo hit, which removes most of the 130 ms even before the deletion.

**F6. `acquired-database?` on every form** (3.6 ms mean, 173 ms max, ×213). With F3 it becomes one
equality of two memoized keys. The in-`evaluate` reacquisition (eval.clj:4533-4558) moves to the fork
(audit D6/D7).

**Not slow, but recorded:** `call-detecting-program-change` is inclusive by construction. The profile's
over-second line for it names the wrapped test run, not this function. Consider `:seon.profile` exclusion or
a note in `seon.profile/summary`; that is the profile owner's call.

### F3 options (a design call: root/Astra review before any edit)

1. **Key = [loaded commit id, digest diff of function/test/namespace rows vs loaded, projection identity,
   fault recorder]. Simplest correct; recommended.** *Guarantee:* two values with equal keys read equal inputs
   in `acquire-program!` (every read is a row the diff covers, the projection, or the loaded program). *Cost:*
   one branch digest query per new commit (≈7-15 ms warm). F2 memoizes the loaded side. About 1 day plus
   review. *Sacrifice:* the diff still scans every function row per new commit (O(program) in one query,
   not O(program) in 7 queries, 469 pulls and a context build).
2. **Key = [loaded commit id, agent-authored rows' digests, projection identity, fault recorder].** This
   queries only rows where `:seon.schema.admission/source :agent`, so its cost follows the agent's own rows.
   *Guarantee:* holds only if a branch's file-derived rows always equal the loaded program's. That is false
   for a branch behind an adoption (`overridden-row?`, eval.clj:1678, documents that case), so it needs a
   second check. *Cost:* cheapest per commit. *Sacrifice:* a correctness condition to prove.
3. **Keep the per-branch key and make derivation proportional to the diff** (the current base plus
   installing only overrides; F2+F4 alone). *Guarantee:* unchanged semantics. *Cost:* smallest diff. *Sacrifice:*
   every branch still builds a context (~4,000 host Vars, namespaces). This gets 400 ms down to perhaps 150 ms,
   not to ~0.

## 2. Deletions (proof, lines, risk, regression)

### 2a. Mechanical: proven by lint and rg, no behaviour change (slice D0)

| item | proof | lines |
|---|---|---:|
| `[clojure.main :as main]` require, eval.clj:104 | kondo `--cache false`: required but never used; `rg 'main/' eval.clj` = 0 | −1 |
| `[seon.error.refusal :as error]` require, reader.cljc:10 | kondo: never used | −1 |
| unused `caps` bindings in `success-evaluation`/`failed-evaluation` (eval.clj:4077, :4121); unused `interrupt-fn` in `evaluate` (eval.clj:4339) | kondo unused-binding | −3 |
| `bindings`: 4 dead parameters (eval.clj:1074), and both callers compute `(built-in-calls)` for nothing | audit D13; source | −4 |
| `declared-row`'s `with-contract-facts` branch (eval.clj:3839-3849): `row` (:469) never yields a `:seon.fn/spec` | audit D12; source | −11 |
| `program-cache-policy` is public with no outside caller | rg | 0 (privatize) |
| `seon.sci.admit/record-name` is public, with callers only in admit.clj | rg | 0 (privatize) |

**Not dead, despite kondo:** `evaluate-native!` (eval.clj:1904, "unused private var") is called through
`requiring-resolve` at my/program.clj:680. Deleting it would break `my.program` retraction. Every "unused"
claim in this plan was checked with rg across src/script/test/resources for exactly this reason.

### 2b. Cache-law violations (slice D1; the law: caches are `seon.cache`, bounded, size a config dial)

`program-identity-cache`, `base-context-cache` and `refusal-recording-cache` (eval.clj:3281, :3285, :3525)
are `defonce` core.cache atoms sized by a code constant (`program-cache-policy`, eval.clj:3272). Move them into
`seon.cache` kinds with dials in the cache config resource. F3 deletes `program-identity-cache` outright. Audit
D16 proposes deleting `refusal-recording-cache` in favour of the fault writer's signature dedup. Net about
−15. The dial resource belongs to cache-central's released files, so this needs the ledger.

### 2c. The three derivations of "what is a program definition" (the owner's smell)

Fully analysed in audit §0 and D1-D3. The live disagreement: clj-kondo makes a row for `(def m {:a 1})`,
`(def a (atom 0))` and `(defmulti mm :k)`, and `program-value?` says false for all three. Plan (audit S1):

- **Keep:** clj-kondo rows (`seon.fn/source-rows`) as the one authority, looked up by name.
- **Delete in eval.clj:** `program-value?` (561), `held-intern` (570), `same-intern-value?` (550),
  `turn-intern-values` (544), the `absent-intern` sentinel's classification uses (502 and 10 sites;
  `sci.vars/hasRoot` answers unbound directly), `source-offset` (607), and most of `program-change` (626).
  About −150.
- **Delete in reader.cljc:** `function-declaration` (252, 55 lines) and `test-declaration` (307, 11) (task
  5f48e860a402). Their consumer is turn.clj's `read-only-evaluation?` (turn.clj:2051), which shrink-turn
  holds. Either that lane converts it to read the evaluation's analyzed row, or I send it a patch. About −70.
- **Blocking owner ruling (audit §3):** is an agent's `(def m {:a 1})` a program row, as it is when a file
  publishes it? Either data defs become rows everywhere (`missing-contract` exempts arglist-less defs), or
  `seon.fn/function-definition?` (fn.clj:259, held by perf-boot) narrows. **I will not start S1 without this
  ruling.** Either answer changes what the REPL admits.
- **Regressions:** `entry_gate_test`, `program_change_test`, `restoration_test`, `eval_test`, plus one new
  class regression: the same `def`/`defmulti` text gives the same program-row answer through a file and through
  the REPL.

### 2d. Other duplicates in my files (cited from the audit, with slice)

| item | audit | eval.clj lines | blocked by |
|---|---|---:|---|
| contract armed up to four times per defn → once | D4 | −75 | S5's gate deletion removes two of the four |
| projection advanced at five sites → one `row → projection` | D5 | −50 | schema.clj owner fn (shrink-schema) |
| result handle bound at three sites → `shown-result` only | D8 | −4 here | turn.clj, error.clj (other lanes) |
| `one-event` re-reads the source already read | D10 | −20 | turn.clj passes the event |
| `success-evaluation`/`failed-evaluation`/`unrun-evaluation` → one constructor | D14 | −50 | none |
| doc/dir rendering (eval.clj:2328-2650, 303 lines) out of the evaluator | D15 | −303 moved | a doc/render owner (0 net) |

### 2e. reader.cljc is CLJ-only in practice

It has 10 `#?` conditionals and no `.cljs` file under src (the pod is deleted; see the clojurescript skill and
the R28 ruling). Renaming it to `reader.clj` and deleting the `:cljs` branches saves about 10 lines. Kondo's
"condition always false" at reader.cljc:199 is the cljs `conformed` returning nil. *Risk:* a
resource or test naming the file path. rg for `reader.cljc` before the cut. Low value; last.

### 2f. admit.clj: a question, not a slice

admit.clj is a hand-rolled streaming EDN serializer (open-node, frame-deliver, frame-advance, leaf and
escaped-char counting; about 500 lines) plus a semantic-value derivation. Possible library seam:
`print-method` onto a bounded `java.io.Writer` that throws at `max-bytes` and polls the interrupt, with the
semantic value from `clojure.walk`. *Unknown:* whether print nodes (`::print/face`) are still consumed
downstream, or whether the floor printer (`seon.render.value`) now owns that. If the floor owns it, admit
duplicates the printer, contrary to CLAUDE.md "Printing happens only at the edge". This needs an Astra audit
before anyone plans lines. It is not slow (0.5 ms mean).

## 3. Ordered loadable slices (slow first)

Each slice is one commit, `git commit --only`, HEAD loads, `clj-kondo --lint src script test --cache false` 0
errors, and `seon.contracts-compile-test/check` 0 findings over changed files. Root runs the named tests.

| # | slice | files | net lines | gain | needs |
|---|---|---|---:|---|---|
| 1 | **F1 + F2**: memoize `loaded-program` and loaded digests by commit id in `seon.cache` | eval.clj, cache config resource | +10 | ~140 ms per derivation, 8 ms per `loaded-program` call | ledger for the cache resource |
| 2 | **F3** base-context key by program content (option 1), deleting the revision-basis identity machinery | eval.clj | ≈ −110 | 45/50 derivations → ~1 per loaded commit; `acquired-database?` becomes key equality | **root + Astra design review first** |
| 3 | **F4** acquire-program! reads what it installs | eval.clj | ≈ −20 | 50-150 ms per remaining derivation | verify namespace-row consumers first |
| 4 | **D0** mechanical deletions (2a) | eval.clj, reader.cljc, admit.clj | ≈ −20 | none (hygiene) | none; can go first if root prefers a warm-up |
| 5 | **D1** caches into `seon.cache` with dials (2b) | eval.clj, cache config | ≈ −15 | law conformance | after slice 2 (it deletes one cache) |
| 6 | **D14** one evaluation constructor | eval.clj | ≈ −50 | none | none |
| 7 | **S1** one definition authority (2c) | eval.clj, reader.cljc (+ turn.clj patch) | ≈ −220 | removes the class behind today's four breakages; admission O(declared rows) | **owner `def` ruling**; shrink-turn for turn.clj:2051 |
| 8 | **F5 / audit S5** gate through `seon.test/run` | eval.clj (+ turn.clj) | ≈ −224 (+turn −35) | 130-200 ms per gated defn; one test path | turn.clj holder; auto-check re-proved on the test path |
| 9 | audit S2/S3/S6 (arm once, pre-eval gate, no re-reads) | eval.clj, turn.clj | ≈ −400 | per-form | the audit's option B ruling |
| 10 | reader.cljc → reader.clj (2e); doc/dir move (D15) | reader, eval.clj, new doc owner | ≈ −10; −303 moved | none | owner for the doc/dir home |

My files alone total about −650 net over slices 1-8, plus about −400 with slice 9. eval.clj falls to about
3,900 before any move. The honest limit is the same as the audit's: eval.clj shrinks by thousands only if
acquisition (1,697 lines) and doc/dir (303) move to their own namespaces, which moves code and does not
delete it.

## 4. Measurements root should repeat after each slow slice

- `(let [m (seon.profile/begin)] (#'seon.sci.eval/derive-base-ctx (seon.db/db (seon.cluster.boot/connection "default")) {}) (seon.profile/explain m 20))`:
  today 398-629 ms. After slices 1+3, `function-digests` ×2 and the 469 pulls should be gone from the top.
- The base-context hit rate: `derive-base-ctx` calls ÷ `base-ctx` calls in `seon.profile/observation`, today
  45/50. After slice 2 it should be ≤ the number of distinct loaded commits plus branches with overrides.
- The first form on a fresh MCP branch, end to end. Task d215b1ebde7c cites 3.6 s and 11.2 GB. Record
  before/after with `:seon.eval/acquisition-ms` on the receipt (65d0ca8e0).

## 5. Defects and stale tasks found

- **Task 361088de3d56** (`program-change-check-scans-the-whole-history`) is still open, but ab87961d5 fixed it
  (`installation-covers-program-change?` now reads the settlement's datoms; 37 ms mean live). The task should
  be verified and closed by root; I did not edit the task EDN (plan-only mode).
- **Task d215b1ebde7c** (`acquisition-rebuilds-the-whole-program`) is the owning task for slices 1-3. This plan
  adds the live cause: the connection-scoped memo key, with its 10% hit rate.
- The kondo "unused private var" for `evaluate-native!` is a false positive (requiring-resolve caller).
  Deleting it on lint evidence alone would break `my.program`.

## Verification limits

- No source was edited and no test was run. The profile rows are cumulative since 21:35Z under concurrent
  lanes, so means include contention. The derivation timing is two runs on one value.
- F3's option 1 soundness argument ("every read of `acquire-program!` is covered by the key") comes from
  reading eval.clj:2774-3100 and is not yet proved. The first step of slice 2 is to enumerate every `db/q` and
  `db/pull` in `acquire-program!` and its callees (`install-row!`, `install-first-party-namespaces!`,
  `install-program-doc!`, `seon.fn/reverse-closure`) against the key.
- The line estimates for audit-derived items are the audit's, not diffs.
