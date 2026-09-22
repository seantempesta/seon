---
type: evidence
status: c3-c4-c5-f2-landed; c13-probe-driven-deletion-landed
created: 2026-09-23
tags: [agent-platform, a2, datahike]
---

# A2 database deletions — 2026-09-23 assignment

## Scope and baseline

Assignment: A2 c3, c4, c5 and the conditional c13 parser probe. Initial
Seon HEAD was `1ada78050`; Datahike gitlink was
`cc2b2bc7dbe774ea1bcc7487e8225a0d420d0e17`. Other lanes' dirty files were
left alone. Source spans were relocated by definition names.

`bin/seon status` and MCP `runtime_status` observed default pid **51528**,
start instant **2026-09-22T18:46:42.624Z**, with no missing readiness layers.
Status reported 14 errored receipts; their causes were not investigated here.
Only read-only JVM MCP calls were made directly against default. No reload,
publication, stop or reset of default was requested. The authorized test-fast
command's recorder subsequently attempted its automatic operator-store write;
that write was refused as `:seon.test/report-conflict` (details below).

## f2 prerequisite

Datahike `pull_api.cljc:16` still defined `+default-limit+` as 1000.
`pull-attr-datoms` uses `(get opts :limit +default-limit+)` and applies
`take` only for a non-nil limit. Changed that constant to nil at its owner.
The supplied selector and per-operation budget remain authoritative; work is
proportional to the requested datoms and charges `resource/charge-work!` and
`resource/charge-value!` as each datom is visited. No Seon cache or selector
replacement is introduced by f2.

Fork commit **`41c79c1a`**, pushed to `origin/main`. Changes: source +1/−1;
tests +10/−4. The existing `test-pull-limit` now covers exactly 1,001 members,
explicit `:limit 5`, 2,000-member named and wildcard pulls, nil and larger
explicit limits. Existing `global-pull-budget` covers bounded refusals.

Command, from `reference-code/datahike`:

```sh
clojure -M:test -m kaocha.runner \
  --focus datahike.test.pull-api-test/test-pull-limit \
  --focus datahike.test.pull-api-test/global-pull-budget --no-capture-output
```

Result: **6 test executions, 45 assertions, 0 failures**, across the fork's
configured hht, persistent-set and specs tasks. Log: `tmp/a2-f2-tests.log`.
This is dependency proof, not Seon adoption or browser proof.

Seon pin commit: **`59e86fd8b`**. High-priority documentation follow-up outside
the assigned paths: `.agents/skills/datahike/SKILL.md:175,231,241,371` still
describes the removed default cap. Those claims are now false for the pinned
fork; explicit limits still truncate by request. The skill was not edited
outside this lane's ownership.

## First-pass c3 scope boundary — resolved below

`rg` over production Clojure found only `turn.clj`'s map-arity `db/diff`
call. The multi-arity callers are the obsolete tests. However,
`resources/seon/schemas/seon.db.diff.edn:25` still names
`seon.db/render-diff-ai`, within the family the assignment deletes. That
resource is outside the enumerated owned files. Deleting the renderer alone
would leave a live declaration pointing at an absent Var.

Per §8's stop rule, options supplied to the owner:

1. **Recommended:** include `seon.db.diff.edn`, prune the obsolete result
   declarations with their renderer; small resource edit, complete retirement.
2. Keep renderer/result declarations; assigned paths only, incomplete deletion.
3. Defer c3 to coordinated schema ownership; preserves loadability, no c3 saving.

No c3 production edit or scratch-root reset has run pending that decision.

## First-pass c4 presentation boundary — resolved below

f2 is proven. The Seon deletion remains pending because the existing
`:seon.print/elision` schema requires a positive omitted count, profile id and
coordinates (`seon.print.edn:346`), while a budget refusal has no complete
result count or render profile. `render-elision-ai` (`print.cljc:457`) selects
specific fields and does not show Datahike's name/observed/allowed members.
The assigned paths exclude both owners. `print/elision` can construct a node
without those fields, but that does not prove it satisfies the named elision
schema. No omitted count was fabricated.

Options supplied to the owner:

1. **Recommended under current scope:** defer Seon's c4 deletion, retaining
   its existing refusal while the presentation contract is resolved.
2. Extend ownership to the print schema/renderer; support unknown-count budget
   elisions honestly, with broader contract and renderer verification.
3. Revise c4 to the existing invalid-read diagnostic, retaining exact budget
   evidence and removing selector rewriting; gives up the requested elision.

## c5 caller-named pulled form

Deleted `pulled-entity-schema-key` and its per-entity EAVT/shape-index guess,
plus `selector-names-attributes?`. `validate-pulled-result` now checks only a
caller-supplied `:schema-key`. It no longer takes database/entity-id inputs.
`validate-pulled-value` and its selector-derived contract remain. The pull
caller passes the same decoded result and literal selector.

Replaced the obsolete guessing test with
`pull-checks-only-the-caller-named-schema`: a real agent row reads, a named
namespace schema reads, absent rows stay nil, and a wrong-typed named result
refuses. Schema-resource edits for this row are comments only.

Line delta versus §8 target −120 source: **source +14/−131 (net −117)**;
schema comments +2/−21; tests +18/−61. No writer or codec region was changed.

Working-tree load command exited 0:

```sh
clojure -M -e "(require 'seon.db 'seon.render 'seon.turn)"
```

Read-only baseline probe on default, **193 ms**, returned
`{:seon.agent/id "root"}`:

```clojure
(let [database @(seon.cluster.boot/connection "default")
      projection (seon.schema/projection-from-database database)]
  (seon.schema/call-with-projection
   projection
   (fn [] (seon.db/pull database [:seon.agent/id]
                       [:seon.agent/id "root"]))))
```

Two preceding probes omitted an available carried projection and correctly
returned a projection refusal. They were corrected by explicitly deriving and
handing the projection; default's loaded code was not changed.

Focused snapshot command:

```sh
bin/test-fast --paths src/seon/db.clj resources/seon/schemas/seon.db.edn \
  test/seon/db_test.clj -- seon.db-test
```

The first attempt queued behind the launcher's stale two-slot limit. Its
owned launcher `(73503, 2026-09-22 13:37:14 local)` was verified, terminated,
and observed exited 143 before another launch. The current owner instruction
removes the slot cap. The second attempt uses the existing launcher override
(`env -u SEON_CODEX_LANE SEON_TEST_ORCHESTRATOR=1 SEON_TEST_SLOTS=16`), still
the same **fast named snapshot**, no cold gate. No other process was signaled.
Snapshot basis: `295c03f6a63639c71e8cd175bfd9882274cc82ca`; only the three
listed files overlay HEAD. Dependency source is the linked f2 checkout.
Result: **67 tests, 443 assertions, 64 failures, 18 errors**, exit 1.
Recording then refused `:seon.test/report-conflict`, reported through
`Malformed PREPL result`; there is no recorded green. Full output is
`tmp/a2-c5-tests.log`, bounded failure details `tmp/a2-c5-failures.txt`.

The c5 regression's concrete refusal is stale installed arity data:
`:seon.instrument/arglists` shows the edited five arguments, but
`:seon.instrument/declared-arities` still reports min=max=7 from the published
base. The wrapper refuses before entering the edited body. This cascades to
ordinary pulls and config population reads. The source and its Malli contract
both declare five; neither is weakened to fit the old fixture.

The three-question classification for the observed red classes:

- Multi-arity diff cases: machinery c3 deletes, pending its resource ownership.
- Old `1000` pull assertion: retired dependency assumption after f2; that
  completeness case belongs with the fork and leaves in c4.
- Digest/replay cases: c1 machinery, outside this cut; retained untouched.
- `analyzed-source-digest` versus `definition-digest` and required missing
  definition digests: retired fixture assumptions from another landed cut.
- Five-versus-seven contract refusal and downstream pull/config assertions:
  surviving behavior blocked by old fixture facts; fresh scratch proof below.
- Error diagnostic fields, identical upstream errors, codec refusal NPE,
  instrumentation registration and recording failure: surviving boundaries,
  not proven fixed by this cut; retained as non-green evidence, no foreign
  owner edits or wholesale lane triage.

## c5 scratch publication and armed proof

The first authorized scratch reset refused static analysis in a foreign dirty
file: `test/seon/dev/hook_test.clj:90:21` called `seon.operator/prepl-value!`
with five arguments while its source admitted two, three or four. Neither
foreign file was edited. Its JVM exited; `bin/seon --root tmp/a2-db-root down
--force` found no remaining process.

Following the assignment's fallback, created detached `tmp/a2-db-wt` at
`59e86fd8b`, linked `reference-code`, and overlaid exactly the three c5 paths.
Running that checkout's `bin/seon --root /Users/sean/src/seon/tmp/a2-db-root
reset --force` succeeded, exit 0, **162,616 ms readiness**, no missing layers,
one agent, source commit `6ab2dafa-00a0-508a-b984-b17fb6a4ecaa`.
Peak observed RSS was **3,780,928 KiB**. A thread dump at roughly 140 seconds
showed `write-owned-values-error` → `owners-of`/`owning-ancestors` running in
the initial publication's final-report validator. That writer region was not
changed. Evidence: `tmp/a2-c5-boot-isolated.log`, `tmp/a2-c5-boot-threads.txt`.

The first ready JVM was absent after its launching tool session ended; the
cause was not established. Reopened the same scratch root in a held shell:
pid 83640, start 2026-09-22T19:46:52.450Z; readiness **15,054 ms**, same source
commit. This is a reopen, not a second from-zero publication.

The first scratch probe observed zero armed Vars. Applied the existing
`seon.instrument/apply!` on this scratch JVM only, under its database-derived
projection and `:seon.config/on-core-error :panic`: **1,820 registered and
instrumented**, **3,743 ms**. Repeated the c5 probe under that projection:

- `pull` on `[:seon.agent/id "root"]` returned `{:seon.agent/id "root"}`.
- Named `:seon.ns/ns` pull returned `{:seon.ns/name 'my.message}`.
- Named pull of `a2.absent` returned nil.
- `validate-pulled-result` on `{:seon.ns/name "my.message"}` returned
  `:seon.db.read/invalid-pulled-result :seon.schema.pulled/c69ab5b3bded`, with
  the offending map unchanged.
- Both `#'seon.db/pull` and `#'seon.db/validate-pulled-result` were present in
  `seon.instrument/instrumented`; actual arity is five.

The complete armed form/envelope is `tmp/a2-c5-armed-probe.json`; **589 ms**.
This proves the changed host read and its named contract on freshly indexed
facts. It does not turn the stale-base namespace run into green and does not
claim SCI or browser-paint evidence. Default pid 51528 was not reloaded.

## c13 eight-input probe

The current spec names eight inputs but supplies no literal eight-row table.
The following concrete inputs exercise its eight pre-check classes. Exact
MCP form and envelope are retained in `tmp/a2-c13-probe.txt` and
`tmp/a2-c13-probe-result.json`. JVM/default/read-only, total **11 ms**;
`database` is the immutable dereference of the explicit default connection.

| Class / dependency call | Result | ms | Decision |
|---|---|---:|---|
| Missing query: `(d/q {:args [database]})` | `ExceptionInfo`, `:error :parser/find`, `:fragment nil` | 0.607459 | Delete missing-query pre-check; retain typed diagnostic through the existing translation |
| Missing input: `(d/q '[:find ?e :in $ ?id :where [?e :seon.agent/id ?id]] database)` | **Accepted**, `#{[38800]}` | 0.798167 | Keep input-count/alignment checks |
| Invalid source: `(d/q '[:find ?e :in $ :where [?e :seon.agent/id _]] 42)` | `IllegalArgumentException`, no ex-data | 0.844500 | Keep source-shape check; not typed |
| Six-position pattern: `(d/q '[:find ?e :where [?e :seon.agent/id "root" ?tx true :extra]] database)` | `ExceptionInfo`, `Pattern mismatch`, `:input`/`:pattern`, **no :type/:error** | 0.393833 | Keep malformed-pattern check |
| Unknown attribute: `(d/q '[:find ?e :where [?e :seon.agent/a2-uninstalled _]] database)` | Empty relation | 0.740917 | Keep unknown-attribute check and its support functions |
| Wrong lookup value: `(d/pull database [:seon.agent/id] [:seon.agent/id 'root])` | nil | 0.443208 | Keep lookup-ref type check |
| Missing selector: `(d/pull database {:eid [:seon.agent/id "root"]})` | nil | 0.120083 | Keep missing-selector / pull shape checks |
| Invalid index: `(vec (d/datoms database {:index :not-an-index :components []}))` | `IllegalArgumentException`, no ex-data | 0.427666 | Keep datoms shape check |

`query-variable-attributes` and `query-find-attributes` also feed the surviving
codec (`encode-query-request`/`decode-query-result`), not just parser checks;
that region is explicitly outside this assignment. No blanket deletion is
justified. `q`/`pull`/`datoms` error unions remain intact.

The requested `:seon.error/cause` translation additionally reaches an unowned
contract: its current schema is `:seon.db/ref`, not a dependency diagnostic
value (`resources/seon/schemas/seon.error.edn:316`). No untyped diagnostic map
or parser keyword has been stored there by this lane. The c13 production
deletion is held pending a compatible owned error representation; the probe
itself is complete.

Options supplied to the owner: (1) **recommended** land probe only, keeping
checks and unions; (2) delete only the missing-query check using existing
dependency-data, revising the requested cause representation; (3) coordinate
an owned error-schema change first, preserving the requested cause semantics
at broader scope and cost.

## First-pass landing boundary and cleanup — superseded below

- **f2:** fork `41c79c1a`, pushed; Seon pin `59e86fd8b`.
- **c5:** `4fe00e120`; source −117, schema comments −19, tests −43 net.
  A clean detached checkout of committed **`4fe00e120`**, linked only to the
  pinned dependency checkout, ran
  `clojure -M -e "(require 'seon.db 'seon.render 'seon.turn)"` and exited **0**.
  Log: `tmp/a2-c5-head-load.log`. The armed scratch probe above is the changed
  behavior proof; the namespace test run remains red/unrecorded.
- **c13:** probe complete, **zero production lines deleted**. Seven classes
  fail the typed-error prerequisite; the one typed candidate reaches the
  unsettled cause representation. This is the explicit conditional outcome,
  not an asserted 450-line saving. All three public error unions remain.
- **c3/c4:** no Seon deletion; the explicit scope/contract decisions above
  are still pending. No system-turn changed-read probe is claimed for c3.
  Probe F on unchanged default returned `{:eid 6272 :pulled 29 :datoms 29}`
  in 218 ms; this remains the totalized baseline, not c4 proof. f2's 1,001-row
  test is the raw dependency completeness proof.

The scratch operator reported exact pid/start identity 83640 /
2026-09-22T19:46:52.450Z stopped with `:process-exit? true`. Its held shell
then exited 0. Verified no scratch JVM holders before deleting
`tmp/a2-db-root`; retained the runtime log outside it as
`tmp/a2-c5-runtime.log`. The dependency symlink was unlinked before removing
each owned detached checkout, so recursive cleanup could not follow it.
All owned test and probe shell sessions have exited.

Final original-root `bin/seon status` and MCP status: pid **51528** still
alive, no missing readiness layers, same 14 errored receipts. No default
publication/reload/stop/reset was performed. Final status evidence:
`tmp/a2-final-default-status.edn`. Owner summary:
`tmp/orchestrator/a2-db-deletions-summary.txt`.


## Resumed owner rulings — c3

The subsequent owner ruling adds `resources/seon/schemas/seon.db.diff.edn`
to this slice. Removed the replay/identity diff family, its renderer, all
obsolete result/refusal declarations and four machinery tests. `rg` found
one production `db/diff` caller: `src/seon/turn.clj`, calling the retained
map arity. `value-changes` and `apply-diff` remain. New regressions cover
value round trips and one changed system-turn read.

Schema retirement was proven from zero with
`bin/seon --root /Users/sean/src/seon/tmp/a2-db-root reset --force`
in a detached HEAD (`6c578954d`) plus these four owned files, with the
pinned reference-code linked. This isolates the previously observed foreign
`test/seon/dev/hook_test.clj` publication boundary. Shared-tree namespace
load also succeeded before the snapshot. Scratch pid 88805, start
2026-09-22T19:57:42.678Z, ready **135793 ms**, source commit
`6ab2de3e-cdee-5f58-a6e2-a84d1a4cc94b`; observed RSS 3841216 KiB at 56 s.
Initial command setup refused an absent root directory, without starting
a JVM; after creating that directory the from-zero command above succeeded.

On that scratch JVM, `instrument/apply!` armed 1819 Vars. The canonical
`with-database` fixture seeded an agent and message, saved a system opening,
changed that message's content and previewed the next system turn. Exactly
one read changed. Its text contained
`{[:seon.message/_to 0 :seon.message/content] {:seon.db.diff/after "after"}}`.
Repeat: **4 assertions, 0 failures, 0 errors, 7849.153 ms**; `diff` and
`system-turn` positively armed. The regression declares 10000 ms for that
measured fixture/opening/preview operation. This is JVM system-turn execution
using real SCI on a fixture branch, not browser paint or default adoption.
Exact form: `tmp/a2-system-turn-body.clj`; first envelope:
`tmp/a2-c3-probe-result.json`; boot/load/stop: `tmp/a2-c3-{boot,load,stop}.log`.

§8 comparison (commit `f1e55a824`): source +5/−383 = **−378** net
versus planned −330; declarations +2/−31 = **−29** net; tests
+48/−100 = **−52** net. Exact committed HEAD load exited 0
(`tmp/a2-c3-head-load.log`). No writer or codec region changed.
Scratch `down --force` observed process exit; its held shell exited.


### c4 schema placement evidence

The first focused snapshot refused top-level `:datahike.budget/*` keys in
`seon.db.edn` at `seon.schema.edn/validate-resource-placement!`; the second
refused inline-only required error members at
`seon.schema.internal/owned-storage!` ("A stored error member must have a
storable registered attribute."). These are wanted guarantees: fix the
new declaration, not either schema owner. Both runs exited before tests.
Logs: `tmp/a2-c4-tests.log`, `tmp/a2-c4-tests-retry.log`.
The concrete three-attribute resource draft is
`tmp/a2-c4-budget-attributes.edn`; ownership clarification requested because
`resources/seon/schemas/datahike.budget.edn` is outside the assigned list.


## Resumed owner ruling — c13

Applied the eight-row table exactly: removed `missing-query-error` and its
call; retained the seven other pre-check classes and explicit `q`/`pull`/
`datoms` error unions. The query-variable/find attribute helpers also serve
the codec, so they remain. `:seon.db/invalid-request*` and
`:seon.db/missing-request-member` remain because the transaction writer
still reads/writes those declarations. The malformed-request regression now
expects Datahike's `:parser/find` rather than the removed Seon diagnostic.

Unresolved representation: `:seon.error/cause` is an entity ref (`seon.error.edn`), so Datahike's typed diagnostic stays in `:seon.error/data :seon.db/dependency-data`; no incompatible cause value is invented.

The new source loaded `seon.db`, `seon.render`, and `seon.turn` and
`(seon.db/q {:args []})` returned `:seon.db/invalid-read true`, operation
`seon.db/q`, layer `:seon.db/database-read`, the parser's message, and
`{:error :parser/find :fragment nil}` in dependency-data. CLI envelope:
`tmp/a2-c13-load-probe.log`. This is changed-source JVM boundary evidence;
the eight counterpart decisions remain the read-only live probe above.
§8 comparison: source +2/−20 = **−18**, tests +6/−8 = **−2** net;
the spec's conditional −450/+10 is not claimed. No schema resources change
in c13, and there is no schema-retirement boot requirement for this row.

The focused lint found no new error in these edits. Its existing
`parser.type/->Variable` unresolved-var diagnostic remained after rebuilding
the dependency cache with the publication classpath; runtime namespace loads
resolve that constructor. No codec reference was rewritten to appease lint.


Focused `bin/test-fast --paths ... -- seon.db-test` reached **65 tests,
410 assertions, 52 failures, 18 errors** (`tmp/a2-c13-tests.log`). Its
reported fixture graph is still `d73e0a6c...`, **30 commits behind** the
snapshot HEAD, not the owner's mentioned `394b58f09` base. Three-question
triage: c3 machinery tests already left; the malformed-request test's two
remaining reds assert retired nested instrumentation paths, corrected to the
producer's current top-level `:seon.instrument/problem-paths`; c5/pull and
system setup failures still report the base's seven-argument validator
against current five-argument calls (fresh armed c3/c5 proofs passed).
Other surviving c1 digest and writer/codec fixtures are outside these cuts,
with their previously recorded boundaries retained. The new missing-query
assertion and eight value-diff round-trip assertions passed in this run.
Automatic result recording again refused `:seon.test/report-conflict`;
this run is red and unrecorded, never claimed green.


## Current landing boundary after the rulings

| Row | Commit / candidate | Net source | §8 comparison | Evidence |
|---|---|---:|---|---|
| f2 | fork `41c79c1a`, pushed; pin `59e86fd8b` | 0 | prerequisite | 45 assertions, default 1001 complete, explicit limits preserved |
| c3 | `f1e55a824` | −378 | planned −330 | committed load; from-zero boot; armed changed-read system turn |
| c5 | `4fe00e120` | −117 | planned −120 | armed agent/named-schema probes; canonical fixture correction included in c4 candidate |
| c13 | **`b6fe3ffa1`** | −18 | conditional −450/+10 not justified by table | typed missing-query row delegated; other seven kept |
| c4 | **landed by this commit**, `Remove pull selector totalization with named budget refusals (A2 c4)` | −56 | −82/+26 versus planned −70/+10 | raw-selector probe F; typed budget refusal; from-zero boot; five focused regressions |

During c13's commit, a concurrent edit inserted foreign projection-cache and
writer hunks into the shared `db.clj`. The initial commit's isolated load
positively failed on `schema/load-projection` absent from its committed
consumer. Corrected **only the commit**, preserving all foreign working-tree
bytes: `b6fe3ffa1` was reconstructed from its parent plus the exact c13
patch, then inspected: source **+2/−20**, tests **+6/−8**. Superseded
`56bae6708` and `fe374f1ef` are not the landed slice. The final committed
HEAD load is recorded separately in `tmp/a2-c13-final-head-load.log`.
No foreign session, source file or process was operated.

### c4 resource proof before the scope ruling (historical)

The named error remains in `seon.db.edn`. The minimal scope addition is
`resources/seon/schemas/datahike.budget.edn`, four lines declaring the three
required members. This is required by both resource placement
(`schema/edn.clj`, `validate-resource-placement!`) and error-member storage
(`schema/internal.cljc`, `owned-storage!`); neither owner was weakened.
The proposed file exists only in the retained patch, not the shared tree.
The question offers exactly three choices: add this resource (recommended),
rename the members into `:seon.db/*` (changes the requested interface), or
expand schema-placement/admission ownership (broader rule change).
The owner subsequently selected option 1 and assigned this resource; the final landing below supersedes this pending boundary.

The proposed four-file c4 patch was applied only to an isolated snapshot of
`b6fe3ffa1`. From-zero command:
`bin/seon --root /Users/sean/src/seon/tmp/a2-db-root reset --force`.
Pid/start **94743 / 2026-09-22T20:12:01.287Z**, ready **152951 ms**,
source commit `6ab2e1ad-7147-5929-b376-148efc122e61`, observed RSS
**3725248 KiB** at 58 seconds. No missing readiness layers. Then
`instrument/apply!` armed **1821** Vars. In **559 ms**:

- Probe F found `seon.turn/step`, eid 6264: **29 pulled members = 29 datoms**,
  with raw `[:seon.fn/calls]`; no selector rewriting family remains.
- `pull` with `:max-work 1` returned the named error with
  `:datahike.budget/name :query-work`, `observed 2`, `allowed 1`, exactly
  matching Datahike's exception members. Its schema validator returned true.
- The default attribute renderer showed all three budget members, operation,
  layer, message and time. No partial entity or elision/count was fabricated.
- The same agent pull without the bound returned `{:seon.agent/id "root"}`;
  `seon.db/pull` was positively armed.

Forms/envelopes: `tmp/a2-c4-probe.clj`, `tmp/a2-c4-probe-result.json`.
The five focused fixture regressions initially gave 37 passes/one error:
the c5 fixture's bare agent row lacked the now-required `:seon.agent/branch`.
Three-question answer: a retired fixture assumption, fixed with the canonical
`agent/creation-tx`, not a writer guard. The candidate also asserts the
rendered budget members. After reloading only the test namespace on the
scratch JVM: **5 tests, 46 assertions, zero failures/errors, 7715.006 ms**.
This includes value-diff, c5 named-schema, c4 budget+render, c13 malformed
requests, and the changed-read system turn. Files:
`tmp/a2-c4-focused-probe.clj`, `tmp/a2-c4-focused-green.json`.
This direct armed fixture proof is distinct from the red/stale-base
`bin/test-fast` result above; no suite green or platform proof is claimed.

Candidate deltas: source +26/−82; db schema +6; proposed attribute resource
+4; tests +37/−77 (includes removing the obsolete totalization fixture and
correcting the c5 fixture). `tmp/a2-c4-ready.patch` is the complete reviewable
candidate. Earlier `tmp/a2-c4-pending.patch` is superseded by it.
All c4 edits were removed from the shared tree by reversing only their exact
hunks while retaining the foreign `db.clj` changes. No `print`, renderer,
turn, codec or writer edit is included in the candidate.

Scratch stop observed exact pid/start and `:process-exit? true`
(`tmp/a2-c4-stop.log`); the held shell exited. No live Java/Babashka holder
remained before deleting its root and isolated checkout; the dependency
symlink was unlinked first. Runtime log retained as `tmp/a2-c4-runtime.log`.
Original default remains **51528**, same start identity, no missing layers,
same **14** errored receipts. No default publication, reload, stop or reset.

Final clean committed `b6fe3ffa1` namespace load exited **0**; its shell
exited and its detached checkout was removed. All owned shell sessions have
terminated. Retained fast-run evidence belongs to the runner; no shared
cache or another lane's root was swept.


## c4 final landing — owner selected option 1

Applied the exact validated `tmp/a2-c4-ready.patch`, including
`resources/seon/schemas/datahike.budget.edn` (four lines). Datahike keeps
its member names; the schema follows the repository's namespace placement
rule (`06f4ebc4b`). The named pull refusal is over `:seon.error/base` and is
an explicit output alternative of the pull entry points. No elision or
omitted count is manufactured. No render/print owner changed.

The from-zero boot **152951 ms** and **5 tests / 46 assertions / zero
failures or errors** above exercised this exact candidate's production
bytes and final test bodies. These are retained proof, not a claim that
unchanged default adopted the source. Per README §6, no cold gate or repeat
suite was run just to land the already-proven candidate.

Final §8 row: **−82/+26 = −56 source**, versus budgeted **−70/+10 = −60**;
**+6** db-schema lines, **+4** namespace-owned budget attribute lines;
**−77/+37 = −40 test lines**. The obsolete totalization family/fixture leave;
the canonical c5 fixture correction and budget/render regression survive.

The shared `db.clj` still contains the other lane's projection-cache/writer
hunks. To avoid including them, this commit was built from HEAD plus only
the exact c4 patch in an isolated checkout and committed with `--only` for
its five paths. The shared file's foreign bytes are preserved. The final
commit id and namespace-load result are recorded in
`tmp/orchestrator/a2-db-deletions-summary.txt`; final load output is
`tmp/a2-c4-final-head-load.log`. The authorized scratch-root boot is the
completed and cleaned proof above, not another default reset.
