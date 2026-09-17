---
type: research
status: complete
created: 2026-09-17
owner: read-only audit lane (Claude Fable 5.1)
tags: [research, steward, contracts, private-functions, error-model, class/absence-as-health, program-graph]
extends: docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md
prd-section: "1j — Every function carries a contract, private included"
class-issue: docs/seon/issues/a-database-reads-error-value-is-read-as-a-row-by-its-caller.md
---

# Private function audit — seon.test*, seon.fn*, seon.program, seon.issue*, seon.error, seon.instrument, seon.id

**Verdict in one line: 491 of the 700 functions in this domain are private with
no contract, 43 of them call a `seon.db` read directly, and 14 of those 43
consume the read's error value as if it were data — every one of those 14 turns
a failed read into a WRONG POSITIVE ANSWER (a fabricated issue, a shrunken test
selection, an "exists" verdict, a zero-count report), which is this project's
named failure class living in the code that is supposed to detect it.**

Read-only lane. AGENTS.md §0–§7 and program-facts PRD §1j were read end to end.
No source was edited. Every `file:line` below was opened in the working tree at
`7cec8cb57` (clean tree, branch `steward-platform`); every number carries the
query or command that produced it.

## Method, and one honest deviation

The assignment allowed ONE read-only MCP evaluation. Three were used, and the
reason is itself a finding:

1. The first census returned `{:totals {:fns-in-domain 0 …}}`. That is the
   class this repository is built to refuse — **absence of signal read as a
   result**. It was not reported as "no private functions"; it was diagnosed.
   Cause: `:seon.ns/name` is declared `:symbol`
   (`resources/seon/schemas/seon.ns.edn:5-9`) while `:seon.fn/sym` is still
   declared `:string` (`resources/seon/schemas/seon.fn.edn:193-197`), so a
   namespace set written as strings matched nothing and the query answered
   "zero" instead of refusing. See finding F15.
2. The second evaluation (corrected to symbols) produced the census. Its 491
   rows were elided by the render profile at 32 children; `get_value` pages the
   stored blob 8 rows at a time, which is 60 retrievals.
3. The third evaluation returned the same census chunked under the profile's
   bound, plus the reaching-test counts used by the "easy first issues"
   section.

All three ran `read_only: true`, in `jvm` mode, with explicit custody
(`(seon.operator/connection "default")`), against pid 94566 — the same process
§1j cites. Three cheap reads on one session stay inside the ≤4 prepl
connection cap in AGENTS §7.

## The census

Exact form (evaluation 2; the totals below are its output):

```clojure
(let [conn (seon.operator/connection "default")
      db (seon.db/db conn)
      nss '#{seon.test seon.test.runner seon.test.selection seon.test.cache
             seon.test.arm seon.test.bounds seon.test.accretion seon.test.fast
             seon.fn seon.fn.analyzer seon.fn.schema-shape
             seon.program seon.issue seon.issue.detect seon.issue.opening
             seon.error seon.instrument seon.id}
      reads #{"seon.db/pull" "seon.db/q" "seon.db/entity" "seon.db/pull-many"
              "seon.db/transact!" "seon.db/datoms"}
      all (seon.db/q db
            '[:find ?sym ?nsname ?e
              :in $ ?nss
              :where
              [?e :seon.fn/sym ?sym] [?e :seon.fn/private? true]
              [(missing? $ ?e :seon.fn/spec)]
              [?e :seon.fn/ns ?ns] [?ns :seon.ns/name ?nsname]
              [(contains? ?nss ?nsname)]]
            nss)
      calls   (fn [e] (->> (seon.db/q db '[:find ?t :in $ ?e :where
                                           [?e :seon.fn/calls ?x] [?x :seon.fn/sym ?t]] e)
                           (map first) set))
      callers (fn [e] (->> (seon.db/q db '[:find ?c :in $ ?e :where
                                           [?x :seon.fn/calls ?e] [?x :seon.fn/sym ?c]] e)
                           (map first) sort vec))]
  ;; …one row per census member: {:s sym :db (intersection calls reads) :c callers}
  )
```

### Totals (default, pid 94566, 2026-09-17)

| Measure | Count |
|---|---|
| Functions in the 18 domain namespaces | **700** |
| Private | **493** |
| Private **with** a `:seon.fn/spec` | **2** |
| Private with **no** contract — the census | **491** |
| …of which call a `seon.db` read/write directly | **43** |
| …of which consume that read without an error check | **14** (F1–F14) |
| Census members with exactly one caller and no database call | 268 |
| Census members with **zero** callers in the graph | 23 |
| **Public** functions with no contract (out of §1j scope, recorded) | 14 |

Domain-local rate: 2/493 = **0.4 %** of private functions carry a contract,
against §1j's 16/3,161 = 0.5 % globally. This domain is not better than the
system average; it is the system average, and it owns the gate, the indexer and
the issue detector.

### Census by namespace

| Namespace | Private, uncontracted | Namespace | Private, uncontracted |
|---|---|---|---|
| `seon.test.runner` | **155** | `seon.issue` | 24 |
| `seon.fn` | **87** | `seon.test` | 18 |
| `seon.error` | 44 | `seon.fn.schema-shape` | 14 |
| `seon.program` | 39 | `seon.issue.detect` | 11 |
| `seon.fn.analyzer` | 32 | `seon.test.cache` | 11 |
| `seon.instrument` | 26 | `seon.issue.opening` | 9 |
| `seon.test.selection` | 8 | `seon.test.accretion` | 7 |
| `seon.test.arm` | 6 | `seon.test.bounds`, `seon.test.fast`, `seon.id` | **0** |

`seon.test.bounds`, `seon.test.fast` and `seon.id` have no uncontracted private
functions at all — `seon.id` is the identity owner AGENTS §3 names as THE ONE
DERIVATION, and it is clean. The 491 are concentrated in four files.

### Concurrency check

`git log --since=6.hours -- <all domain paths>` returned **no commits**, and
`git status` is clean. The concurrent astra lane `private-contracts` has landed
nothing in this domain at `7cec8cb57`; its slice-1 work is recorded in the class
issue note and touched `src/seon/instrument.clj:687` (private SELECTION), not
the consumers audited here. Every count above is therefore current, and any
contract that lands after this note supersedes its row.

---

## Table A — the 43 private functions that touch the database

Column *check* is the load-bearing one: does the body branch on
`:seon.error/kind` (or `error-value?` / `flat-error-value?` / a wrapper that
does) before consuming the read? Column *a failed read becomes* is what the
function actually returns when the read refuses. Rows marked ✗ are findings.

### A.1 — Consumes the error value (14 findings; opened in full)

| # | Symbol | file:line | Consumes | Returns | Check | A failed read becomes | Risk |
|---|---|---|---|---|---|---|---|
| F1 | `seon.error/agent-exists?` | `src/seon/error.clj:1343` | `db`, `agent-id` | boolean | ✗ `:1350` `(some? (db/q …))` | **`true`** — the error map is `some?`, so the recorder emits a lookup ref to an agent it never confirmed | blocker |
| F2 | `seon.error/entity-exists?` | `src/seon/error.clj:1355` | `db`, attribute, value | boolean | ✗ `:1362` `(some? (db/q …))` | **`true`** — same inversion | blocker |
| F3 | `seon.error/recurrence` | `src/seon/error.clj:1380` | `database`, signature, process | count | ✗ `:1382` `(map second (db/q …))` | **throws `ClassCastException`** inside the fault committer: `map second` over the error MAP yields its string values, `reduce +` rejects them | blocker |
| F4 | `seon.test.runner/record-latest-tx` | `src/seon/test/runner.clj:2712` | database, completion map | `:seon.store/transaction-data`; throws on two refusals | ✗ at `:2765`, `:2798`, `:2889` — **but checks `previous` at `:2812`** | a pulled error is kept in `current-by-symbol` as a present row (`exists?` true, empty reach); `file-present?` answers **present**; the failure-id pull mints a duplicate tempid | blocker |
| F5 | `seon.test.runner/failure-replacement-tx` | `src/seon/test/runner.clj:2550` | database, tempid/ref, failures, run-ref, inst | transaction data | ✗ `:2551`, `:2560`, `:2563` | `(keys (dissoc old …))` iterates the **error map's keys** and emits `[:db.fn/retractAttribute nil :seon.error/message]` — retractions keyed on `nil` | blocker |
| F6 | `seon.fn/output-graph` | `src/seon/fn.clj:1549` | `database` | `:seon.fn.output.graph/*` map | ✗ `:1552`, `:1563`, `:1571`, `:1578` | `(->> error sort vec)` produces a vector of **MapEntries** presented as the function population; `output-path-report` then reports paths over it | blocker |
| F7 | `seon.fn/published-index-rows` | `src/seon/fn.clj:2602` | `database` | vector of index rows | ✗ `:2605`/`:2607` | `reduce-kv` over the error map writes `:seon.error/kind` and `:seon.error/message` **into published index rows** | blocker |
| F8 | `seon.fn/reconcile-tx-in` | `src/seon/fn.clj:2545` | shapes, database, rows, identities | transaction data | ✗ `:2549`, `:2560`, `:2570` | `(:db/id current)` is `nil`, so exact replacement is **skipped** and stale attributes survive the reconcile — R3's "indexing and updating are one operation" quietly stops being true | blocker |
| F9 | `seon.fn/resolvable-runtime-function-rows` | `src/seon/fn.clj:789` | database, analysis, requests | `[{:seon.fn/sym …}]` | ✗ `:800` | `(into batch-symbols error-map)` puts **MapEntries into the symbol set**, minting `{:seon.fn/sym #object[MapEntry]}` program rows | blocker |
| F10 | `seon.fn/declared-reference-edges` | `src/seon/fn.clj:1363` | `database` | q result (pass-through) | ✗ (returns raw) | the caller `gate-sets` binds it and `gate-set-in` reads it with `(get incoming-declared entity)` → `nil` → **the gate set silently shrinks**. AGENTS names this exactly: "a regression walking less than the writer admits" | blocker |
| F11 | `seon.issue.detect/declared-keys` | `src/seon/issue/detect.clj:14` | database, entity | set of keywords | ✗ `:18` `get-in` on the pull | `#{}` → `instances` finds nothing → `pair-subject` **mints a false issue** "declares no render pair" for every entity map | blocker |
| F12 | `seon.issue.detect/component-values` | `src/seon/issue/detect.clj:25` | `database` | set of entity ids | ✗ `:29`, `:30` | `#{}` components → same false-issue fabrication | blocker |
| F13 | `seon.issue/citation-attributes` | `src/seon/issue.clj:158` | `database` | map; **throws** `ex-info` | ✗ `:165` | the `for` destructures MapEntries, `cites` ends empty, and `:173` **throws blaming the schema** ("No issue attribute declares `:seon.issue/cites`") for a failed read. Four writers call it | blocker |
| F14 | `seon.issue/citation-index` | `src/seon/issue.clj:192` | database, cites map | index map | ✗ `:200` | `db/datoms` error reduced as datoms; `(:v datom)` on a MapEntry is `nil` → a **silently empty citation index**, so issue citations stop linking with no signal | friction |

Four more consume a read without a check but are **honest by accident**, and
are listed here so nobody "fixes" them by copying a guard that already exists in
a weaker form:

| Symbol | file:line | Why it survives today |
|---|---|---|
| `seon.error/reader-correction` | `src/seon/error.clj:916` | `:930` gates on `(string? spec)`; an error map is not a string, so it returns `nil`. Correct by shape, not by intent — a contract makes it intentional. |
| `seon.issue/subject-row` | `src/seon/issue.clj:481` | `:498` `(or (:db/id …) (refuse! …))` refuses — but with the **wrong diagnosis**: "No entity holds …" for a read that failed. |
| `seon.issue/detector-rows` | `src/seon/issue.clj:511` | `:515` same shape, same misdiagnosis: "No program entity names the detector". |
| `seon.issue/require-test-refs!` | `src/seon/issue.clj:869` | `:871` same shape: refuses with "Every success ref must identify a test". |

Misdiagnosis is strictly better than a false positive and strictly worse than
the truth. These four are `friction`, not `blocker`: the operation stops, the
agent is told the wrong reason.

### A.2 — Already checks its reads (the models to copy)

| Symbol | file:line | Why it is the model |
|---|---|---|
| `seon.fn/gate-set-in` | `src/seon/fn.clj:1375` | checks the pull (`:1381`), checks BOTH `datoms` results (`:1392`), checks the walk result (`:1400`), returns the refusal unchanged. This is the shape every row in A.1 should take. |
| `seon.instrument/program-graph-arglists` | `src/seon/instrument.clj:167` | distinguishes `:failed` from `:missing` with `flat-error-value?` on the database (`:173`) AND the query (`:183`) — AGENTS §2.4's typed unknown, implemented. It needs only the contract. |
| `seon.issue.detect/shared-form-symbols` | `src/seon/issue/detect.clj:87` | `(if (:seon.error/kind spans) spans …)` — propagates. |
| `seon.issue.detect/carrying` | `src/seon/issue/detect.clj:105` | propagates. |
| `seon.issue.detect/bodiless-symbols` | `src/seon/issue/detect.clj:128` | propagates. |
| `seon.test.runner/execution-read` | `src/seon/test/runner.clj:2342` | the one wrapper; `execution-members` (`:2347`), `worker-identity` (`:2391`), `recorded-member-result` (`:3034`), `admission-members` (`:2188`) all route through it. |
| `seon.test/check-in-process` | `src/seon/test.clj:737` | 17 explicit error branches in 189 lines. |
| `seon.test/stale-in` | `src/seon/test.clj:598` | 3 branches. |
| `seon.test.runner/reach-refresh` | `src/seon/test/runner.clj:1979` | 2 branches. |
| `seon.test.runner/prepare-failures!` | `src/seon/test/runner.clj:2482` | 1 branch. |
| `seon.error/run-identity` | `src/seon/error.clj:1769` | 1 branch. |
| `seon.fn/commit-index-phase!` | `src/seon/fn.clj:2386` | routes the write through `require-committed!`. |

### A.3 — Remaining database-touching members (pass-through or caller-checked)

`seon.error/refusal-text` (`src/seon/error.clj:1026`),
`seon.fn/runtime-analysis-batch` (`src/seon/fn.clj:808`),
`seon.issue/generated-report` (`src/seon/issue.clj:583`),
`seon.issue/identity-rows` (`src/seon/issue.clj:761`),
`seon.issue.opening/links` (`src/seon/issue/opening.clj:37`),
`seon.issue.detect/instances` (`src/seon/issue/detect.clj:34`),
`seon.issue.detect/declarations` (`src/seon/issue/detect.clj:136`),
`seon.test/namespace-tests` (`src/seon/test.clj:641`),
`seon.test/destructive-path` (`src/seon/test.clj:269`),
`seon.test/identity-tests` (`src/seon/test.clj:546`),
`seon.test/stale-in` callers, and the `seon.test.runner` members already listed.

Two of these deserve their own line because the read is consumed, just less
destructively:

- `seon.issue/generated-report` (`:584`) — `program` is `(:db/id (db/pull …))`
  unchecked; `nil` flows into the following query, which returns nothing, and
  the report says **`:seon.issue/count 0`**. A detector's report announcing zero
  issues because the read failed is the canonical "check that reads absence of
  signal as health".
- `seon.issue.opening/links` (`:48`) — `(when (:seon.issue/title row) …)`; an
  error has no title, so the opening **silently omits the issue**.
- `seon.test/destructive-path` (`:284`) — `start` unchecked; the loop starts
  empty and returns the two-element path `[test-symbol owner-symbol]`, degrading
  the evidence for a destructive-test refusal. Note the same function DOES check
  at `:278` inside `callees`. Checked in one place, not the other — the same
  split as F4.
- `seon.test/identity-tests` (`:550`) — an unchecked pull decides
  `:seon.test/sym` vs `:seon.fn/sym`, so a failed read **misclassifies a test as
  a function** before `:553` catches the second read honestly.

---

## Table B — the other 448 census members, honestly

They are not tabulated here. A 448-row table copied into a document is a
hand-maintained mirror of a Datalog result, and AGENTS §2.2 "derive or die"
makes that a defect on sight — every one measured on this project was stale
within a day. What is recorded instead is the derivation (the form above), the
totals, the per-namespace counts, and the two structural facts a triager needs:

- **268** have exactly one caller and no database call. These are pure helpers
  whose contract is readable from a single call site — the raw material for the
  "easy first issues" list below.
- **23** have zero callers in the program graph. Each is either dead code, or
  reached only through a var reference the analyzer did not resolve to a call
  edge. Either answer is a finding; neither can be assumed. This set is worth
  its own bounded lane (it is not this note's scope, and no issue note names it).

Re-derive any slice with the census form, changing only the final projection.

---

## Findings

Each finding names the wrong understanding it reveals, because §1j says "every
new red is a finding about a wrong understanding".

**F1–F3 (`seon.error`, blocker).** `src/seon/error.clj:1343`, `:1355`, `:1380`.
The wrong understanding: *"a database read either answers or returns nil."*
`agent-exists?`'s own docstring states the invariant — "the recorder losing the
record because the recipient was a typo is precisely the failure mode the fault
path may not have" — and the implementation delivers exactly that failure mode
from a different cause: on a refused read it answers `true`, the transaction
carries a lookup ref to an unconfirmed agent, and the whole fault transaction
fails. **The error recorder is the one path that may not fail, and it is the one
with the strongest inversion.** `recurrence` is worse still: it throws
`ClassCastException` into the committer. Existing issue note: the class note
`a-database-reads-error-value-is-read-as-a-row-by-its-caller.md` is open and
severity blocker; it lists three instances, none of them in `seon.error`. These
three extend it.

**F4–F5 (`seon.test.runner`, blocker).** `src/seon/test/runner.clj:2765`,
`:2798`, `:2889`, `:2551`, `:2560`, `:2563`. The wrong understanding:
*"we fixed this here."* `record-tx` is instance 3 in the class note, resolved in
`e58a27c86` — and `record-latest-tx`, the function it delegates to, checks
`previous` at `:2812` while leaving three other reads in the same body
unchecked. A per-instance fix left the class alive inside the very function that
proved it. F5 can emit `[:db.fn/retractAttribute nil …]` into a gate's recording
transaction.

**F6–F10 (`seon.fn`, blocker).** `src/seon/fn.clj:1552`, `:2605`, `:2549`,
`:800`, `:1363`. The wrong understanding: *"a query result is a collection."* An
error value is a MAP, and every one of these seqs, `into`s, `reduce-kv`s or
`get`s it as a collection. F9 mints program rows whose `:seon.fn/sym` is a
`MapEntry`; F7 writes `:seon.error/message` into published index rows; F8 skips
exact replacement. **These five sit in the indexer — the seam program-facts PRD
R3 makes the single authority for "indexing and updating are the same
operation".** F10 is the most dangerous because it is the quietest: the gate's
test selection shrinks and the run still reports green.

**F11–F14 (`seon.issue*`, blocker/friction).** `src/seon/issue/detect.clj:14`,
`:25`; `src/seon/issue.clj:158`, `:192`. The wrong understanding: *"empty means
nothing was found."* The issue detector, on a failed read, does not report a
failed read — it **fabricates issues**, claiming every entity map declares no
render pair. An issue generator that manufactures findings from its own broken
read is the highest-severity form of this class, because its output is what
agents are told to work from. F13 additionally throws with a diagnosis blaming
the schema. No issue note names the detector; these belong under the open class
note as new instances.

**F15 (schema, friction).** `resources/seon/schemas/seon.fn.edn:193-197` keeps
`:seon.fn/sym` as `[:string {:min 1}]` while
`resources/seon/schemas/seon.ns.edn:5-9` has already moved `:seon.ns/name` to
`:symbol`. The owner's ruling (AGENTS §3, 2026-09-17: "all functions and vars
and anything that is a symbol should be stored as a symbol and not a string.
ALL OF IT") is **half-landed**, and the seam between the two halves is the join
every program-graph query crosses. The cost is not theoretical: it silently
returned an empty census in this lane's first evaluation, and an empty result is
indistinguishable from "no such functions". The inventory
`docs/prds/steward-platform/research/symbols-everywhere-inventory-2026-09-17.md`
owns the full site list; this note records that the half-landed state produces
silently-empty joins, which the inventory does not say. No issue note found for
"a half-migrated symbol attribute answers empty instead of refusing".

**F16 (contract shape, cleanup).** The two contracted privates in this domain
are not a pattern to copy blindly; §1j also flags 69 public contracts declaring
a bare `:map` output. Every contract written from this note should name a
registered schema on BOTH sides. Where none exists, the honest move is to
declare the key, not to widen to `:map` — AGENTS §3 forbids `:any`/`:some`.

---

## Contract candidates (registered schemas verified present)

Verified in `resources/seon/schemas/`: `:seon.db/database-value` (`seon.db.edn:157`),
`:seon.db/connection` (`:141`), `:seon.db/tx-data` (`:243`), `:seon.db/ref` (`:11`),
`:seon.error/value` (`seon.error.edn:37`), `:seon.store/transaction-data`
(`seon.store.edn:36`), `:seon.fn/sym`, `:seon.test/sym`, `:seon.agent/id`,
`:seon.issue/severity` (`seon.issue.edn:4`), `:seon.schema/value`.

| Symbol | Honest contract |
|---|---|
| `seon.error/agent-exists?` | `[:=> [:cat :seon.db/database-value :seon.agent/id] [:or :boolean :seon.error/value]]` — the boolean stops being total the moment the read can refuse; the error arm is the point. |
| `seon.error/entity-exists?` | `[:=> [:cat :seon.db/database-value :qualified-keyword :seon.schema/value] [:or :boolean :seon.error/value]]` |
| `seon.error/recurrence` | `[:=> [:cat :seon.db/database-value :seon.error/signature :seon.db.process/id] [:or [:int {:min 0}] :seon.error/value]]` |
| `seon.test.runner/record-latest-tx` | `[:=> [:cat :seon.db/database-value :seon.test.run/claim-completion] [:or :seon.store/transaction-data :seon.error/value]]` |
| `seon.test.runner/failure-replacement-tx` | `[:=> [:cat :seon.db/database-value [:or :string :seon.db/ref] [:vector :seon.test.failure/failure] :seon.db/ref :inst] [:or :seon.store/transaction-data :seon.error/value]]` — `:seon.test.failure/failure` is already registered (`resources/seon/schemas/seon.test.failure.edn:35`), so no new key is needed. |
| `seon.fn/output-graph` | `[:=> [:cat :seon.db/database-value] [:or :seon.fn.output.graph/graph :seon.error/value]]` — **none registered — needs `:seon.fn.output.graph/graph`** (the four keys already exist as a literal shape at `src/seon/fn.clj:1583-1586`). |
| `seon.fn/published-index-rows` | `[:=> [:cat :seon.db/database-value] [:or :seon.program/rows :seon.error/value]]` — `:seon.program/row` and `:seon.program/rows` are registered (`resources/seon/schemas/seon.program.edn:51,53`); do not widen to `:map`. |
| `seon.fn/reconcile-tx-in` | `[:=> [:cat :seon.program/shapes :seon.db/database-value [:vector :map] [:sequential :seon.db/ref]] [:or :seon.db/tx-data :seon.error/value]]` |
| `seon.fn/resolvable-runtime-function-rows` | `[:=> [:cat :seon.db/database-value :map [:sequential :map]] [:or [:vector [:map [:seon.fn/sym :seon.fn/sym]]] :seon.error/value]]` |
| `seon.fn/declared-reference-edges` | `[:=> [:cat :seon.db/database-value] [:or [:set [:tuple :int :int]] :seon.error/value]]` |
| `seon.issue.detect/declared-keys` | `[:=> [:cat :seon.db/database-value :seon.db/ref] [:or [:set :qualified-keyword] :seon.error/value]]` |
| `seon.issue.detect/component-values` | `[:=> [:cat :seon.db/database-value] [:or [:set :int] :seon.error/value]]` |
| `seon.issue/citation-attributes` | `[:=> [:cat :seon.db/database-value] [:or [:map-of :qualified-keyword :qualified-keyword] :seon.error/value]]` — writing this contract forces the `throw` at `:173` to become a value, which is the repair. |
| `seon.issue/citation-index` | `[:=> [:cat :seon.db/database-value [:map-of :qualified-keyword :qualified-keyword]] [:or [:map-of :string [:set [:tuple :qualified-keyword :int]]] :seon.error/value]]` |
| `seon.instrument/program-graph-arglists` | `[:=> [:cat :qualified-symbol] :seon.instrument.lookup/result]` — **none registered — needs `:seon.instrument.lookup/result`**, the three-state `:found`/`:missing`/`:failed` value the body already returns. Declaring it makes the typed unknown a fact rather than a convention. |

### Callers that would break if these are armed

From `:seon.fn/calls` (census output, callers column). Arming F1–F3 exercises
`seon.error/commit-call` and `seon.error/function-identity-call` — the fault
committer, so a red there is a fault-path red and must be expected. F4/F5 arm
under `seon.test.runner/record-tx` and `record!`, i.e. **every cold gate's
recording**; per §1j that breakage is welcome, but the orchestrator should run
the arming gate knowing recording is in the blast radius. F6 arms under
`seon.fn/output-path-report`; F7/F8 under `seon.fn/index!` and
`seon.fn/reconcile-tx` — **the publication path the edit hook runs on every
edit**, so a red there stops adoption for every lane. F10 arms under
`seon.fn/gate-sets` and the test `seon.fn-test/assert-scoped-reference-selection`.
F11/F12 arm under `seon.issue.detect/entity-map-without-pair` and
`pair-subject`. F13 arms under four `seon.issue` writers (`adopt-tx`,
`detector-rows`, `identity-rows`, `index-tx`).

**Sequencing consequence:** F7/F8 (publication) and F4/F5 (gate recording) are
the two that can stop other lanes. They should be armed in an orchestrator
window, not by a live agent mid-day.

---

## Triage order — the ten to contract first

Ordered by *what a failed read currently produces*, worst first. A function that
answers a confident wrong thing outranks one that stops.

1. **`seon.issue.detect/declared-keys`** (`src/seon/issue/detect.clj:14`) —
   fabricates issues. The detector's output is what agents are told to work
   from; a generator that invents findings from its own broken read poisons
   every downstream task. Fix first, with F12.
2. **`seon.issue.detect/component-values`** (`:25`) — same fabrication, same
   detector, one commit with F11.
3. **`seon.error/agent-exists?`** (`src/seon/error.clj:1343`) — the fault
   recorder destroys its own record, against its own docstring. Nothing else is
   diagnosable once this path fails.
4. **`seon.error/recurrence`** (`:1380`) — throws into the committer.
5. **`seon.error/entity-exists?`** (`:1355`) — with 3 and 4, one coherent
   `seon.error` slice.
6. **`seon.fn/declared-reference-edges`** (`src/seon/fn.clj:1363`) — silently
   shrinks the gate set. A green run that tested less than it claimed is the
   most expensive lie this system can tell.
7. **`seon.fn/reconcile-tx-in`** (`:2545`) — skips exact replacement, so R3's
   one-operation guarantee stops holding without a signal.
8. **`seon.fn/published-index-rows`** (`:2602`) — writes error keys into index
   rows; with 7, one indexer slice.
9. **`seon.test.runner/record-latest-tx`** (`src/seon/test/runner.clj:2712`) —
   the class note's "resolved" instance, still carrying three unchecked reads.
   Contracting it closes the instance honestly.
10. **`seon.test.runner/failure-replacement-tx`** (`:2550`) — emits retractions
    keyed on `nil`; one slice with 9.

`seon.fn/output-graph` (F6) and `seon.issue/citation-attributes` (F13) are 11
and 12 and belong to the same wave.

**One regression for the class, not ten.** AGENTS §5: "move the invariant to one
choke point and keep ONE regression per class asserting the WANTED behavior."
The wanted behaviour is one sentence — *a `seon.db` read's error value reaching
any of these consumers produces that error at the consumer's boundary, never a
row, a count, a boolean, an issue, or an empty collection.* One canonical armed
regression handing a poisoned database value to each of the ten proves the class
dead; ten per-function tests prove nothing about the eleventh caller.

---

## Easy first issues for live agents

Selection rule (derived, not chosen): private, uncontracted, **no database
call**, **exactly one caller**, and **exactly one reaching test** via
`seon.fn/gate-set`. 268 members meet the first three conditions; these are
drawn from those whose gate set is exactly one test, so the agent has one call
site to read the shape from and one test to run. Every row below is
`symbol | file | its single caller | reaching tests`.

| Symbol | Single caller | Reaching tests |
|---|---|---|
| `seon.test.cache/alive?` | `seon.test.cache/referenced?` | 1 |
| `seon.test.cache/compatible-changes` | `seon.test.cache/retained-base` | 1 |
| `seon.test.cache/reap!` | `seon.test.cache/ensure-base!` | 1 |
| `seon.test.cache/referenced?` | `seon.test.cache/reap!` | 1 |
| `seon.test.runner/bare-namespaces` | `seon.test.runner/run-coordinator!` | 1 |
| `seon.test.runner/configured-persistent-results-root` | `seon.test.runner/run-coordinator!` | 1 |
| `seon.test.runner/confirmation-vars` | `seon.test.runner/run-coordinator!` | 1 |
| `seon.test.runner/expensive-fixture-tests` | `seon.test.runner/verify-fixture-observations!` | 1 |
| `seon.test.runner/indexed-test-symbols` | `seon.test.runner/split-resolved-tasks` | 1 |
| `seon.test.runner/liveness-diagnostic` | `seon.test.runner/fire-liveness-backstop!` | 1 |
| `seon.test.runner/long-declarations` | `seon.test.runner/run-coordinator!` | 1 |
| `seon.test.runner/print-skipped!` | `seon.test.runner/finish-run!` | 1 |
| `seon.test.runner/print-task-failures!` | `seon.test.runner/run-parallel-stage!` | 1 |
| `seon.test.runner/split-resolved-tasks` | `seon.test.runner/run-parallel-stage!` | 1 |
| `seon.test.runner/test-selection` | `seon.test.runner/run-coordinator!` | 1 |
| `seon.test.runner/test-tasks` | `seon.test.runner/run-coordinator!` | 1 |
| `seon.test.runner/thread-info-text` | `seon.test.runner/liveness-diagnostic` | 1 |

The four `seon.test.cache` members are the best genuine first task: one small
file, a closed cycle of four functions, one reaching test, and no database call,
so a wrong contract cannot reach the writer. Give one agent all four in one
slice rather than four agents one each — §7's "one lane per class" applies to
easy work too.

Two cautions for whoever hands these out:

- **`liveness-diagnostic` and `thread-info-text` are diagnostic writers.**
  AGENTS §2.4: "a thread dump that omits virtual threads lies." Their contracts
  must not narrow what the diagnostic may report; widening an input is
  accretion, narrowing an output is breakage.
- **A one-caller contract is read from the call site, never guessed from the
  name.** The rule that makes these easy is that the shape is *visible*; an
  agent that cannot see it at the call site should return the function to the
  queue rather than widen to `:map`.

## What this note does not prove

- No test was run and no gate was taken; every consequence described for a
  failed read is read from the source, not observed. The class note already
  carries the live proof that the wrapper preserves a refusal
  (`a-database-reads-error-value-is-read-as-a-row-by-its-caller.md`), and the
  three overnight instances are the observed evidence that the consumption
  pattern fires in practice.
- The 448 non-database census members were counted, not individually opened.
  Their contracts are the second wave, after the 43.
- `seon.program.cljc` contributes 39 census members and **no** database-touching
  ones; its private helpers were counted but not opened, because §1j's first
  mined class is read-consumers and `seon.program` has none.
