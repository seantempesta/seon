---
type: research
status: ready-for-lane
created: 2026-09-21
lane: A1
tags: [malli, schema, projection, instrumentation, call-preparation, deletion, agent-platform]
---

# Lane A1 — the projection is read, never rebuilt

Owned files: `src/seon/schema.clj`, `src/seon/schema/*.clj`,
`src/seon/instrument.clj`, `src/seon/call_preparation.clj`,
`src/seon/test/arm.clj`, `src/seon/fn/schema_shape.clj`, their tests under
`test/seon/`, and `reference-code/malli` (our fork).

Every `file:line` below was opened and verified on 2026-09-21 at
`reference-code/malli` gitlink `606083c5`. Rows marked **UNVERIFIED** were not
confirmed; treat them as questions, not facts. Corrections to the audits are
marked **AUDIT WRONG**.

## 1. Goal

One immutable compiled Malli generation per cluster is built where the program
is published, carried on the database value, and READ everywhere else. Today
the same generation is rebuilt from stored rows at every acquisition:
`schema/projection-from-database` is **3,996 ms of the 4,648 ms** fixture base
(`docs/prds/steward-platform/research/test-system-fork-2026-09-23.md:104`,
quoted in `deletion-audit-test-system-2026-09-21.md:§4`), **248 ms / 1,560
contracts** per newly materialized commit on the edit path
(`deletion-audit-publication-operator-2026-09-21.md:§3`), and a **whole-population
`declaration-projection`** per arming pass at `src/seon/instrument.clj:995-997`.
After this lane: the carried value is read, arming performs zero
named-declaration compilations (bridge-step5 acceptance, goals note
`durable-goals-and-rulings-2026-09-21.md:131`), and the fixture base loses its
last O(program) step. The numbers that prove it are in §7.

## 2. Reading list — open these BEFORE editing

### 2a. The fork (`reference-code/malli`, gitlink `606083c5`)

| Open | Learn |
|---|---|
| `src/malli/registry.cljc:11-22` `Registry` / `fast-registry` | `-schema` is a `HashMap.get`; a sealed registry answers by lookup, never by compiling |
| `src/malli/registry.cljc:54,81,97-102` `composite-registry`, `lazy-registry`, `schema`, `schemas` | `mr/schema` is the read; `lazy-registry` memoizes per identity |
| `src/malli/core.cljc:2550,2574,2581,2595` `schema`, `form`, `properties`, `children` | `m/schema` on a compiled Schema is identity; `m/properties` is free on the node the registry already built |
| `src/malli/core.cljc:2771-2798` `entries` | keys **and** `:optional` come off the compiled node — this replaces the map-entry Datalog in `call_preparation.clj` |
| `src/malli/core.cljc:2848-2880` `from-ast` / `ast` | the canonical normal form; §4e depends on what it does and does not normalize |
| `src/malli/core.cljc:91-92, 2193-2277` `-function-schema-arities`, `-function-info` | arities, `:input`, `:output`, `:min`/`:max` off the retained function schema |
| `src/malli/core.cljc:3082, 3118-3139` `function-schema`, `-instrument` | `-instrument` takes `:schema` and calls `(schema options)` — a compiled schema passes through; our wrapper may hand it one |
| `src/malli/core.cljc:268, 345` `-memoize`, `-create-cache` | Malli's own per-schema cache; this is why Seon must not add a second validator cache |
| `src/malli/error.cljc:44-172` `default-errors` | **AUDIT WRONG**: the table ALREADY carries `:any :nil :string :int :double :float :boolean :keyword :symbol :qualified-keyword :qualified-symbol :uuid :enum :re :=> := :not= :> :>= :< :<=`. Only `:vector :sequential :map :set :tuple :and :or :fn` are absent. See §4d |
| `src/malli/instrument.cljc` (whole) | why the Seon wrapper stays — §5 |
| `src/malli/generator.cljc:299-310` | **UNVERIFIED** here; the audit flags an O(registry) ref-cycle helper. Probe only if a number points at it |

### 2b. Our own landed idiom (read, then copy the shape)

| Open | Learn |
|---|---|
| `src/seon/schema.clj:454-503` `projection-registry` | the target: compile serially once, then `(mr/fast-registry (mr/schemas registry))`. Every contract is already realized at `:501-502` |
| `src/seon/schema.clj:357-385` `with-compiled-cache` | what a legitimate second holder looks like, and its docstring stating it stores only what Malli does not |
| `src/seon/schema.clj:387-417` `projection-cache` / `projection-cache-value` | nil is honest; correctness never depends on the holder |
| `src/seon/schema.clj:2737-2770` `refuse-projection-source` | the typed refusal shape the classpath fallback becomes |
| `src/seon/schema.clj:1272-1296` `structural-registry` / `structural-schema` | a `reify` Malli has no equivalent for — leave alone |

### 2c. Seams read, never edited by this lane

| Open | Learn |
|---|---|
| `src/seon/db.clj:1219-1225` `carried-projection` | **AUDIT WRONG** (malli audit said `:1168`; publication audit's `:1219-1225` is right). This is the value every converted caller reads |
| `src/seon/db.clj:249-271` `carry-projection-state` | how a projection gets onto a database value; existing metadata wins |
| `src/seon/db.clj:272-292` `carry-derived-projection` | the commit-value path that calls `projection-from-database` at `:286` — the publication lane's conversion, not yours; your seam change must keep it correct |
| `src/seon/test.clj:576-600` `run-owned`, `src/seon/test.clj:1901+` `check` | the only two run entry points this lane uses (§6) |

## 3. REPL protocol

**Access, verified this session.** `.codex/config.toml` declares
`[mcp_servers.seon] command = "bin/mcp-server"`; `bin/mcp-server` execs
`seon.dev.mcp` (`script/seon/dev/mcp.clj`), whose `eval_clj` tool
(`:848-886`) takes `mode` ∈ `{"jvm","sci"}` (`:583-584`, `:637-640`) and
`read_only` (`:674`). **A Codex lane therefore evaluates against the live
`default` cluster through `eval_clj`** — no scratch cluster needed for reads.
`bin/codex-agent` exports only `SEON_CODEX_LANE` (`:386`) and test env
(`:43,:59`); it grants no extra REPL path. Rules: at most **one** `eval_clj`
in flight, `mode: "jvm"`, `read_only: true` for probes; `mode: "sci"` mutates
the shared agent ctx, so never for measurement. Start a scratch cluster
(`bin/seon --root tmp/a1-root start a1`, downed and deleted in the same turn)
ONLY if a probe must mutate; a lane never restarts `default`.

**Before the cut — record these numbers in the landing note.**

```clojure
;; 1. what a projection build costs at the current seam
(let [db (seon.db/db (seon.operator/connection "default"))
      t0 (System/nanoTime)
      p  (seon.schema/projection-from-database db)
      ms (/ (- (System/nanoTime) t0) 1e6)]
  {:ms ms
   :forms (count (:seon.schema.projection/forms p))
   :contracts (count (:seon.schema.projection/function-contracts p))})

;; 2. is the value already carried? (the whole premise)
(let [db (seon.db/db (seon.operator/connection "default"))]
  {:carried? (some? (seon.db/carried-projection db))
   :same-forms? (= (:seon.schema.projection/forms (seon.db/carried-projection db))
                   (:seon.schema.projection/forms (seon.schema/projection-from-database db)))})

;; 3. count Malli compiles at the seam — count the construction, do not time it
(let [n (atom 0)]
  (with-redefs [malli.core/schema (let [f malli.core/schema]
                                    (fn [& args] (swap! n inc) (apply f args)))]
    (seon.instrument/apply! {...your arming request...}))
  @n)
;; the acceptance number is ZERO named-declaration compiles for an unchanged
;; generation (goals note :131). Count provider calls, never scratch timings.
```

**After each commit** re-run 1 and 3 and paste both rows. A timing without its
count is not evidence.

## 4. The cut, as ordered commits

Each commit is net-negative, path-limited (`git commit --only -- <paths>`), and
must leave HEAD loadable:

```
clojure -M -e "(require 'seon.schema 'seon.instrument 'seon.call-preparation)"
```

| # | Commit | Delete | Keep / convert | Verified spans |
|---|---|---|---|---|
| A1-1 | Arming reads the compiled contract | `instrument.clj:747-754` per-wrapper recompile (`compilable-form` + `bind-contract-predicates` + `m/schema`) | `(mr/schema (:seon.schema.projection/registry projection) function-symbol)`; the refusal/facet/cap logic is untouched | `instrument.clj:734-760`, `schema.clj:501-503`, `core.cljc:3118-3139` |
| A1-2 | Delete the dead per-Var digest | `instrument.clj:871-873` `contract-digest` + the `:seon.instrument/contract-digest` metadata key at `:896` | `current-wrapper?` (`:844-858`) already decides staleness from `:seon.instrument/definitions`. **AUDIT + AGENTS.md WRONG**: both cite `instrument.clj:593` for the re-arm comparison; the seam is `:844-858` — fix the AGENTS.md citation in this commit | `instrument.clj:844-858, 871-873, 896`; readers only at `test/seon/instrument_test.clj:944,968,979` |
| A1-3 | Close over the generation at arm time | `instrument.clj:604-619` `supplied-projection` (three-way argument scan on every instrumented call) and its per-call branch at `:884-889` | arm once per generation; `apply!` already re-arms on contract/definition change. A foreign projection becomes an arming decision | `instrument.clj:604-619, 860-896` |
| A1-4 | One projection per arming pass | `instrument.clj:995-997` `bootstrap` (`declaration-projection (packaged-forms)`) and the `boot-wrapper` delay `:874-877` | the missing-projection refusal at `:970-981` becomes total; `restore!`'s own `declaration-projection` at `:1122` is a separate decision — name it, do not silently keep it | `instrument.clj:874-877, 970-997, 1019, 1122` |
| A1-5 | The classpath fallback becomes a refusal | `schema.clj:929-1085` (frame walking, `clojure.basis` parsing, canonical-directory containment, `decade?`, `!fallback-counts`, `warn-classpath-fallback!`) | `packaged-forms` (`:1086-1088`) keeps the resource read; the fallback ARM of `candidate-forms` (`:1090-1099`) refuses with `error.refusal/diagnostic` in the shape of `refuse-projection-source` (`:2737-2770`). Delete `schema/edn.clj:361` `packaged-population-cache` + `forget-packaged-population!` (`:378`) with it | `schema.clj:891-1099, 2737-2770`; `schema/edn.clj:361,378` |
| A1-6 | Per-declaration config admission | `schema.clj:1206-1225` `assert-config-display!`'s whole-population `doseq` over `forms` | move the check into `register!` (`schema.clj:1545`) reading `(m/properties (mr/schema registry k))` — O(edit), not O(3,333). This is the measurable half of "config compiled 35 times" | `schema.clj:1206-1225, 1227-1258, 1545` |
| A1-7 | The seam reads the carried value | `schema.clj:2514-2545` `projection-rows`/`projection-admissions`, `:2546-2736` `projection-from-rows`, `:2771-2787` `derive-projection-from-database` | **`projection-from-database` (`:2788-2810`) stays as the SEAM and changes meaning**: return `db/carried-projection`; when absent, the typed refusal naming the caller. Caller conversion is §4b | `schema.clj:2514-2810`, `db.clj:1219-1225` |
| A1-8 | Fingerprint off `m/ast` | `fn/schema_shape.clj:21-27` `split-schema-form`, `:29-37` `canonical-map-entry`, `:39-56` `canonical-form` | `m/ast` + the normalizer §4e requires; bump `normalization-revision` (`:10-12`). **RESET NEEDED** — record it in the landing note | `schema_shape.clj:10-12,14-17,21-56,67-72,75-81,100-113,184-192` |
| A1-9 | Argument structure off the compiled contract | `call_preparation.clj:604-641` (`arity-query`, `positional-query`, `argument-shape-query`), `:642-650` `argument-validators`, `:651-713` (four map-entry queries) | `m/-function-schema-arities` + `m/-function-info` → `:input`; `m/children` for slots; `m/entries` for keys and `:optional` (`core.cljc:2771-2798`); `m/validator` off the retained Schema. **Supplier rows (`:seon.call-preparation/*`) are genuine facts and stay** — do not touch `:149-423`, `:1099`, `:1277`, `:1371` | `call_preparation.clj:604-748, 837, 981, 1004, 1099, 1277, 1371`; `core.cljc:2771-2798, 2193-2277` |
| A1-10 | `sha-256` / `byte-array?` | `schema.clj:761-772` | convert call sites to `seon.id/sha-256` and `clojure.core/bytes?`; `register-core-predicate!` (`:1153-1187`) re-points at `clojure.core/bytes?` | `schema.clj:761-772, 1153-1194` |
| A1-11 | Fork: the missing error types | — | §4d | `error.cljc:44-172` |

**Not in this lane.** `schema/admission.clj:33-135, 159-184` (the file-reading
walker) is the edit hook's gate (`bin/seon-hook:498,503`) and is **lane B1's**
hook half. A1 writes the seam it will call — the advisory channel on
`register!` (`schema.clj:1545`) — and stops. Do not edit `bin/seon-hook`.

### 4a. The eleven inline error guards in this lane's files

**AUDIT/BRIEF SCOPE CORRECTION**: the 179 guards are repo-wide; in A1's owned
files there are exactly **11**, all in `call_preparation.clj`, all carrying
their `;; debt: <callee>` comment: `:240, 313, 414, 421, 438, 557, 583, 725,
740, 1400, 1404`. Ruling D12 applies: delete the guard, declare the callee's
error union in the callee's contract, branch on a distinguishing required
member where a branch is genuinely needed. Seven name `seon.db/q`, `db` or
`history` as the callee — **those unions live in `db.clj`, which A1 does not
own**. Convert the four whose callee is `seon.call-preparation/snapshot` /
`current-snapshot` (`:557, 583, 1404`, and `:1400`'s inner test) in A1; list
the remaining seven in the landing note with callee and line for the Datahike
lane. Do not introduce a predicate.

### 4b. The `projection-from-database` callers, classified by reading

**AUDIT WRONG**: "138 callers" is 43 src call sites in 15 files plus 101 test
references. `grep -rn 'projection-from-database' src/`, verified:

| Owner | Sites | Class | Action |
|---|---:|---|---|
| `schema.clj` | 4 | the seam itself | A1-7 |
| `test/arm.clj:35-46` `packaged-test-projection` | 0 (uses `declaration-projection`) | the worker's ONE legitimate acquisition | **keep**, unchanged |
| `db.clj:286` (`carry-derived-projection`) | 1 | bootstrap: a commit value has no live connection | **keep the acquisition**, but it must read the publisher's carried generation when present — coordinate with the publication lane, do not rewrite `db.clj` |
| `cluster/source.clj:160,267`, `cluster/registry.clj:236`, `cluster.clj:1677,2093,3251,3269` | 7 | boot / adoption acquisition | **keep, named**; they run once per cluster construction |
| `sci/eval.clj:824,853,903,944,988,2233,2234,2326,2371,2377` | 10 | running-code rebuild, per evaluation/fork | read carried — **foreign file**, list for the SCI lane |
| `error.clj` ×7, `fn.clj:831,1662,2678,3055,3479`, `turn.clj:1235`, `config.clj:797`, `schedule.clj:613`, `cluster/wake.clj:415`, `reconcile.cljc:316`, `test.clj:1587`, `test/runner.clj:1506` | 22 | running-code rebuild | read carried — **foreign files**, list them |
| `fn.clj:2778` | 1 | already correct (`or (db/carried-projection …)`) | the pattern every other site converts to |

A1 does not edit those foreign files. Because A1-7 changes the seam's meaning
in place, every one of them starts reading the carried value without being
touched; the landing note lists the 33 now-redundant call wrappers for
mechanical follow-on lanes. If the seam change makes any of them refuse, that
refusal is the finding — record it, do not add a fallback.

### 4c. `with-compiled-cache` and the digests

Keep `with-compiled-cache` (`schema.clj:357-385`) — probe first (§5). Keep the
32-bit XOR `projection-fingerprint` (`:695-816`) as the **reuse aid** that makes
`replace-fingerprint-entry` (`:808-816`) O(changed); raise
`portable-string-hash` (`:695-697`, `.hashCode`, 32-bit) to a `seon.id`
truncation in A1-10 — a 32-bit collision silently reuses the wrong compiled
generation, which is this project's named failure class.

### 4d. The fork change — exact entries

Verified at `reference-code/malli/src/malli/error.cljc:44-172`. Seon's
`schema-expectation` (`src/seon/error.clj:977-1000`) has a 17-branch `case`;
`default-errors` already answers **ten** of them. The fork accretes only the
absent types, in the table's own idiom:

| Add to `default-errors` | Present already |
|---|---|
| `:vector`, `:sequential`, `:map`, `:set`, `:tuple`, `:and`, `:or`, `:fn` | `:string :int :double :boolean :keyword :qualified-keyword :symbol :qualified-symbol :nil :enum` |

**The grammar mismatch is real and must be settled by probe, not by
assumption.** Malli's entries read `"should be a vector"`; Seon's grammar
(ERR:148-168) is `expected <noun phrase>` — `"a vector"`. Options, in order of
preference: (i) add the types to the fork with malli's sentence phrasing and
hand `me/error-message` a Seon `{:errors …}` overlay for the noun phrasing,
deleting the `case` recursion but keeping a DATA table; (ii) add them with
Seon's noun phrasing — the fork is ours, but every fork consumer's message
changes; (iii) leave phrasing in Seon. Probe `me/error-message` with an
`:errors` option before choosing, and write the choice into the landing note.
`src/seon/error.clj` is **not an A1 file**: land the fork entries and the probe
here; delete `error.clj:977-1000` only if `git status` shows no other lane
holding `error.clj`, and list the file if you do.

### 4e. `m/ast` and the fingerprint — settled by probe this session

One read-only `eval_clj` (jvm, `default`) returned:

```clojure
(m/ast (m/schema [:map {:closed false} [:x {:optional false} :int]]))
;; => {:type :map, :properties {:closed false},
;;     :keys {:x {:order 0, :properties {:optional false}, :value {:type :int}}}}
(m/ast (m/schema [:map [:b :int] [:a :int]]))
;; => {:type :map, :keys {:a {:order 1, :value {:type :int}}
;;                        :b {:order 0, :value {:type :int}}}}
```

**Therefore, decided:** `m/ast` does **not** drop `{:closed false}` or
`{:optional false}` — the normalization at `schema_shape.clj:14-17` and `:32-33`
**must move to the fingerprint site**. `m/ast` keys entries by an unordered map
(so the hand-written `sort-by` at `:48-49` disappears) but carries `:order`,
which must be dropped or a pure entry reorder moves the fingerprint. The
replacement is a small AST normalizer: drop `:order`, drop `{:closed false}`,
drop `{:optional false}` — three dissocs against a 36-line walker. Fingerprints
change ⇒ **RESET NEEDED**, recorded in the landing note with the commit, and
`normalization-revision` (`:10-12`) bumped in the same commit. Note the call
path before editing: `fingerprint` (`:67-72`) hashes the raw form; the
normalization runs upstream in `authored-form` (`:75-81`) and `canonical-form`
(`:128-129`). Probe the real path (`form-fingerprint`, `:100-113`), not
`fingerprint` in isolation.

## 5. Where a smarter cut may exist — probe these first

| Candidate | Probe | Why it may beat the audit |
|---|---|---|
| **`with-compiled-cache` may be unnecessary** | `registry.cljc:17-22` seals to a `HashMap`; `core.cljc:268,345` gives every Schema its own `-create-cache`. Count `m/schema`/`m/validator` constructions with and without the holder for one arming pass (§3 form 3) | If arity descriptors are the only product Malli does not retain, the holder shrinks to that one key — or disappears if `m/-function-schema-arities` off the sealed registry is already O(1). A deleted mechanism beats a justified one |
| **`malli.instrument` may host the wrapper** | Read `src/malli/instrument.cljc` end to end, then `core.cljc:3118-3139` | `-instrument` already does input/output/arity dispatch. **The Seon wrapper stays**: the archive ruled against per-cluster authority in global `m/function-schemas` (goals note `:368`) because global state cannot own independent cluster generations, and our refusal is a flat `:seon.error` value where Malli's default is `-fail!`. What it may BORROW is `-instrument`'s arity dispatch and `:report` seam — hand it our refusal constructor as `:report` and delete our dispatch, keeping our shape |
| **`mr/schema` may make A1-1 and A1-9 one commit** | `(mr/schema registry 'some.ns/f)` on the live projection, then `m/-function-schema-arities` → `m/-function-info` → `m/entries` | If the sealed registry answers contracts AND their argument structure, the instrument recompile and the call-preparation Datalog are the same deletion, and the ordering in §4 collapses |

If a probe shows a smaller cut than §4, take it and say so in the landing note
with the evidence. §4's ordering is a floor, not a ceiling.

## 6. Tests

**The lane runs ONLY the tests reaching its change, in process.** The exact
call, from `src/seon/test.clj`: `seon.test/check` (`:1901`) for a change set,
or `seon.test/run-owned` (`:576`) for one test with explicit cluster custody
(`[{:seon.db/connection conn :seon.test/var #'the-test}]`). Never `bin/test`,
never `--all`, never `--full`, never a cold gate — `bin/test` refuses one
carrying `SEON_CODEX_LANE` anyway. `bin/test-fast --paths <owned files> --
<namespaces>` is the fallback if `check` is unavailable. A foreign red is a
verification boundary, not your work.

| Group | Members (verified) | What happens |
|---|---|---|
| Benchmarks as deftests | `schema_test.clj:41, 305, 1468`; `instrument_test.clj:1195, 1223` | **delete** (~150 lines). They `println` and assert no bound; three `with-redefs` `m/schema` or a private fingerprint Var. Measurement belongs in the committed script with its clock row |
| Pinned to deleted mechanisms | `instrument_test.clj:944-945, 968-969, 979` (contract-digest); `:960` `cold-arming-derives-wrapper-identity-without-a-predicate-binding`; `:1091` `applying-without-a-handed-projection-refuses-before-collection` | **delete with the mechanism** (~90 lines) |
| Class B "a refusal names the offending argument" | `instrument_test.clj:280,612,644,655,687,721,770,1339,1357,1425`; `refusal_grammar_test.clj:13,29,44`; `schema_test.clj:806` | **collapse 14 → 2**, one per surface (host wrapper, SCI) |
| Class A "arming is idempotent" | `instrument_test.clj:471,866,878,913,960,1064,1117,1285`; `registry_isolation_test.clj:51` | **collapse 9 → 3**: idempotent; re-arm on contract change; re-arm on referenced-declaration change |
| Class C "the population resolves once" | `schema/edn_test.clj:77,107,122,185`; `schema/declaration_population_test.clj:63,82`; `schema/datahike_test.clj:441` | **delete 5**; with A1-5 the fallback is unconstructable |
| Pinned to private helpers | 20 tests redef or `ns-resolve` a private Var (heaviest `schema_test.clj:1265`; `schema/edn_test.clj` ×8 on `#'schema.edn/resource-population`) | **rewrite against behaviour, do not delete** — each is a real claim asserted against an implementation detail |

**The six `:seon.test/long` declarations in this area** (all verified):

| Declaration | Becomes |
|---|---|
| `schema/admission_test.clj:126-127` — 300,000 ms | **bound removed**: the O(program) step is `assert-config-display!` + whole-population compile (A1-6). Publish a SMALL fixture program, not `src/` |
| `schema/datahike_test.clj:243-244` — 25,000 ms, 80 generated cases | **kept**, reason unchanged: real generative work |
| `schema/datahike_test.clj:360-361` — 120,000 ms, three renderer contracts | **bound removed**: the cost is the publication path, not the contracts |
| `schema/projection_acquisition_test.clj:37-38` — 10,000 ms | **deleted** with `projection-from-rows` (A1-7); it exists to prove the rebuild path correct |
| `schema/projection_acquisition_test.clj:94-95` — 10,000 ms, four projections | **bound removed**: one carried generation makes three of the four unnecessary |
| `schema_redeclare_test.clj:72-73` — 300,000 ms, isolated JVM publish/boot/seed/adopt | **kept**: a real cold boot, which the owner law exempts. Tighten the number to the measured time |

One class-killing regression lands with the cut: **an arming pass over an
unchanged generation performs zero named-declaration compilations** (counted at
the construction seam, per §3 form 3), asserting the wanted behaviour.

## 7. Done criteria, and the stop rules

| Done | Evidence |
|---|---|
| Net deletion | **≥ 700 production lines** across the eleven commits (audit floor 1,430–1,960 for the whole Malli area; A1 owns the projection/arming/call-preparation share), **≥ 350 test lines** |
| Numbers moved | projection acquisition at the seam: **3,996 ms → a read** (§3 form 1, before and after); arming compiles: **N → 0** (§3 form 3); `declaration-projection` calls per arming pass: **1 → 0** |
| HEAD loads | `clojure -M -e "(require 'seon.schema 'seon.instrument 'seon.call-preparation)"` after EVERY commit |
| Tests | the reaching set green in process via `seon.test/check` / `run-owned`; the tally pasted; the cold proof named as still owed to the orchestrator |
| Landing note | `docs/prds/agent-platform/research/lane-a1-landing.md` |

The landing note must contain, as exact bytes and measured numbers: the §3
forms as evaluated with their returned values before and after; the commit list
with `git diff --stat` per commit; **RESET NEEDED** with the A1-8 commit id;
the eleven guard sites with their callee and disposition; the 33 now-redundant
`projection-from-database` call sites listed by `file:line` for follow-on
lanes; the §4d grammar choice with the probe that decided it; each §5 probe's
result including the candidates you rejected; and any span in §2/§4 whose line
numbers had moved, corrected.

**Stop rules.**

1. **A held file.** Before touching any owned file, `git status --short`. If
   another lane holds uncommitted edits in it, STOP and report the path. At
   session start these were dirty: `src/seon/cluster.clj`, `src/seon/fn.clj`,
   `test/seon/cluster/publication_delta_test.clj`,
   `test/seon/fn/publication_cache_test.clj`,
   `docs/prds/agent-platform/plan/README.md` — none are A1's, and A1 must not
   edit them.
2. **An unsettled design question.** Write the three options — guarantee, cost,
   what we give up — into the landing note and STOP. Do not improvise a
   mechanism, a cache, a constant or a noun.
3. **A public Var retirement you cannot complete.** Retirement plus every
   caller's conversion is ONE slice; if callers sit in foreign files, stop
   BEFORE deleting and list them. This is why A1-7 changes the seam's meaning
   instead of deleting the Var.
4. **The profiling sample is NOT yours.** Lane C1 lands the armed-wrapper
   sample (two `nanoTime` reads, `LongAdder` count/total + `LongAccumulator`
   max per armed definition keyed by (symbol, `:seon.fn/digest`, branch),
   flushed on a bound as facts). Leave the invocation path in `arm-var!`
   (`instrument.clj:860-896`) clean and cite that span as C1's hook point.
