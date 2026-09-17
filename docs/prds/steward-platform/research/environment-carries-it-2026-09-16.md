---
type: research
status: active; resumed after owner clarified item-local stop boundary
created: 2026-09-16
tags: [workarounds, program, environment, values-carry-their-world]
---

# Environment carries it — review boundary

## Resumed assignment: item 21

The owner clarified that item 20's stop applies only to that item. The
initial stop record below is historical; independent work resumed.

`widening-inputs` is removed. `widening-path?` is the complement of
`graph-roots`, with exact path-segment matching. Input hashing enumerates
Git's tracked and non-ignored files, using the snapshot's own working-tree
bytes. Git supplies NUL-delimited paths; no filename/prose parsing or input
roster remains. Directory links and submodule directories are not walked.
The existing Babashka process owner supplies bounded completion and cleanup
(`reference-code/babashka-process/src/babashka/process.cljc:119`, `:165`).

At edit time selection.clj was clean; its existing `read-basis` region was
unchanged. No foreign hunk was included. Verification:
`bin/test-fast --paths src/seon/test/selection.clj
test/seon/test/selection_test.clj -- seon.test.selection-test`: **5 tests,
27 assertions, 0 failures, 0 errors**. Log:
`tmp/environment-selection-fast.log`. The regression covers new outside
paths, near-prefix paths and every declared graph root; existing content
and symlink regressions pass. A subsequent cleanup removed the now-unused
private symlink predicate and corrected the docstring; behavior is unchanged.

Read AGENTS.md §0–§3 and §5–§7 and
[workaround-inventory-2026-09-16.md](workaround-inventory-2026-09-16.md)
end to end. Assignment covers ranked items 20, 21, 24 and 30.

The assignment explicitly says for item 20: “program.cljc may be held by
the platform-tier agent — check; hunk + stop if so.” At HEAD
`0dec68dc43530b8087d4b93c1790c62f6f374905`, `git status --short --
src/seon/program.cljc` reports ` M src/seon/program.cljc`. Its existing
diff changes `test-marker-attributes`, adding `:seon.test/platform` and
`:seon.test/fixture` and their documentation. Those edits are preserved.
This is the explicit ownership stop, not a failed gate.

No production changes were made. Items 21, 24 and 30 remain unimplemented;
none of the four requested regressions has run. No `bin/test` or
`bin/test-fast` invocation was made. No cluster was started or restarted.

## Observed system and dependency ledger

- `bin/seon status`: default alive, PID 41413, prepl 51534, no orphan JVMs.
- MCP runtime status answered and reported existing problems: 95 failed
  tests, 11 errored evaluations, 2 error signatures and 2 stale Vars.
  These are inherited observations, not causes attributed to this work.
- Read-only MCP JVM evaluation of `(select-keys (seon.program/shape
  :seon.fn/sym) [:seon.program/identity-attribute
  :seon.program/owned-attributes])` returned the function identity and
  owned attributes in 42 ms. It exercised the currently loaded owner,
  not a changed definition or a fresh adoption.
- `src/seon/program.cljc` already derives row shapes from the identity
  attribute's `:seon.program/row-schema` link in `derived-shape`.
  `seon.schema.form/map-entries` strips the map head/properties; the
  existing `entry-properties` reads each entry's Malli properties.
  Required entries are those without `:optional true`.
- `seon.program/shapes-in` accepts an explicit declaration population.
  `canonical-row` already accepts its derived shape value. The proposed
  change uses that same value for canonicalization and required-key checks.

## Item 20: exact proposed hunks, not applied or tested

```diff
--- a/src/seon/program.cljc
+++ b/src/seon/program.cljc
@@
     {:seon.program/identity-attribute identity-attribute
      :seon.program/source-attribute source-attribute
+     :seon.program/required-attributes
+     (into []
+           (comp (remove #(true? (:optional (entry-properties %))))
+                 (keep entry-attribute))
+           entries)
      :seon.program/owned-attributes
@@
-(def ^:private declaration-required-attributes
-  {:seon.ns/name [:seon.ns/source]
-   :seon.fn/sym [:seon.fn/ns :seon.fn/source :seon.fn/arglists
-                 :seon.fn/private?]
-   :seon.schema/key [:seon.schema/form]
-   :seon.test/sym [:seon.test/ns :seon.test/source]})
-
 (defn declaration-row
@@
-  (let [event (assoc event :seon.schema.admission/source admission-source)
+  (let [row-shapes (shapes)
+        event (assoc event :seon.schema.admission/source admission-source)
@@
-        row (canonical-row candidate)]
+        row (canonical-row row-shapes candidate)]
@@
-                  (get declaration-required-attributes identity-attribute))]
+                  (:seon.program/required-attributes
+                   (shape row-shapes identity-attribute)))]
--- a/resources/seon/schemas/seon.program.edn
+++ b/resources/seon/schemas/seon.program.edn
@@
  :seon.program/owned-attributes
  [:or [:vector :qualified-keyword] :seon.program/schema-row-properties]
+ :seon.program/required-attributes
+ [:vector :qualified-keyword]
@@
   [:seon.program/source-attribute :seon.program/source-attribute]
+  [:seon.program/required-attributes :seon.program/required-attributes]
   [:seon.program/owned-attributes :seon.program/owned-attributes]]
```

The row-schema declaration becomes authoritative, including where it
disagrees with the deleted literal: for example `:seon.ns/source` is
currently optional in `resources/seon/schemas/seon.ns.edn`. Preserving
the literal's stronger requirement would require changing that declaration,
not retaining a second requirement list. The hunks must land together.

Required follow-through after release: add one regression using the
canonical population, change one optional row entry to required in the
supplied forms, and prove the derived required attributes change without
changing program code. Exercise declaration refusal under armed contracts;
check existing row-shape fixtures for the newly required shape member.
The supplied-population declaration path should carry its derived shapes
through the declaration operation, rather than resolving them per row.

## Remaining traced boundaries

Item 30: `seon.search/open!` returns a string index ID after putting the
live owner in `owners`; `apply-report!` and `close!` recover it by ID;
`search` recovers it through `db/*conn*`. The owner already contains the
Lucene `SearcherManager` and acquires/releases readers in `search-owner`.
Removing the registry requires carrying that actual owner through boot,
environment, flow and search requests together. The existing
`:seon.search/index` schema is a tokenizer enum, so it cannot be repurposed
as the handle schema. `cluster.clj` was dirty on entry but clean at the
final ownership check; it is not claimed protected at this stop.

Item 21: `widening-inputs` also feeds `input-digests`. Deleting only the
predicate's list leaves its input enumeration unresolved. The predicate
must use the complement of `graph-roots`; enumeration also needs an
authoritative input source that works in the gate's copied checkout.

Item 24: the script currently makes a configuration call before the work
call. Both protocol construction and default-bound acquisition need to move
to the JVM owner for the requested single-call result. The successful
result must require pass/fail/error counts; typed failure must remain
distinct from a legitimate zero-pass result.

Only this landing note is committed. Stop for owner review as instructed.

---

## Resumption after the lane's account expired (same day, Claude Fable 5.1)

Read AGENTS.md §0–§3 and §5–§7, this note, and
[workaround-inventory-2026-09-16.md](workaround-inventory-2026-09-16.md)
ranked items 20, 21, 24 and 30 end to end before editing.

Inherited state at resumption, from `git status --short` and `git log`:

| item | inherited | disposition |
|---|---|---|
| 21 `widening-inputs` | committed at `6df6967b8` | verified, not redone |
| 24 `bin/test-check` | `seon.test/check-request` committed at `a00e73e49`; its schema resource, its regression and the script rewrite were left UNCOMMITTED and UNTRACKED | finished and landed together |
| 30 search half | `src/seon/search.clj`, both schemas, `config/default.edn`, `test/seon/search_test.clj`, and the wiring hunks in `src/seon/cluster.clj` uncommitted | finished and landed |
| 30 `mcp-projection` | untouched | hunk recorded below; `cluster.clj` is held |
| 20 `declaration-required-attributes` | `src/seon/program.cljc` had become CLEAN (the platform-tier agent landed `f54771e84`) | dissolved by G1; nothing committed (see below) |

### Item 20: dissolved by G1, not implemented

The proposal above derived `:seon.program/required-attributes` from the row
schema's Malli optionality. A read-only MCP JVM evaluation of the declared row
schemas showed that derivation would WEAKEN admission — the non-optional
entries of the four row schemas are

| identity | the literal required | non-optional row entries |
|---|---|---|
| `:seon.ns/name` | `[:seon.ns/source]` | `[:seon.ns/name]` |
| `:seon.fn/sym` | `[:seon.fn/ns :seon.fn/source :seon.fn/arglists :seon.fn/private?]` | `[:seon.fn/sym :seon.schema.admission/source :seon.fn/ns]` |
| `:seon.schema/key` | `[:seon.schema/form]` | `[:seon.schema/key :seon.schema.admission/source :seon.schema/form]` |
| `:seon.test/sym` | `[:seon.test/ns :seon.test/source]` | `[:seon.test/sym :seon.schema.admission/source]` |

— so `:seon.ns/source`, `:seon.fn/source`, `:seon.fn/arglists`,
`:seon.fn/private?`, `:seon.test/ns` and `:seon.test/source` would stop being
required at admission.

A `:seon.program/declaration-required` entry property was written and then
REMOVED on the orchestrator's correction. Its justification was the
identity-only tombstone a deletion leaves behind, and tombstones are retired
by owner ruling (program-facts PRD §1f G1/G3): deletion is retraction, no
identity-only rows survive the reset, so a Malli `required` entry IS the
admission rule and a second "required at admission, optional in storage"
mechanism is precisely the duplicated mechanism this inventory removes.

**Item 20 is therefore dissolved by G1**, not implemented here: the literal
`declaration-required-attributes` in `src/seon/program.cljc` is deleted when
the reset makes the row schemas' required entries authoritative, which means
tightening those six entries in the same publication. The reset-batch
integrator owns that deletion. Nothing for item 20 is committed from this
lane; `src/seon/program.cljc` and the four row-schema resources are unchanged.

### Item 24: one call, and why the transport bound is derived

`seon.test/check-request` already existed at HEAD; what was missing was its
schema, its regression, and the script. All three landed here:

- `resources/seon/schemas/seon.test.check.edn` declares the request, the
  response, and a result whose contract REQUIRES `:seon.test/pass-count`,
  `:seon.test/fail-count` and `:seon.test/error-count` — a genuine zero is a
  value, and a result that omits a count is not constructable;
- `src/seon/test.clj`'s `check-request` gained the
  `:seon.error/diagnostic-evidence` its `seon.await/await!` request contract
  requires; without it the armed bound refused the await and every check came
  back as `:seon.test/unknown` naming the cluster (the first gate run caught
  this — the counts were nil, not zero);
- `test/seon/test/check_request_test.clj` proves the missing-adoption case is a
  typed error satisfying the response contract, the present-adoption case
  carries all three counts, and that dropping `:seon.test/pass-count` fails
  the result contract;
- `bin/test-check` is now one `eval!` of `(seon.test/check-request {…})`. The
  twenty-line `scoped`/`work` program composed in bash is gone; projection,
  configuration, preparation and execution are the cluster's.

One correction to the inherited draft: it passed `(+ (or bound 0)
transport-bound)` as the prepl timeout. That timeout is a SILENCE backstop
(`.setSoTimeout`, `script/seon/fresh_operator.clj:1607`) and one check emits
nothing until it returns, so an ordinary adoption check with no
`--time-limit-ms` would have tripped the transport before the cluster's own
120 s allowance expired — a hang reported as a transport fault. The script now
derives the transport backstop from the shipped decision for
`:seon.test/check-time-limit-ms` and refuses up front if that decision is
absent. The cluster re-reads its own effective dial and remains the authority
over the work.

### Item 30: the search half, and the supplier the gate falsified

The first gate run refused the agent-facing call:

```
seon.search/search refused request at [:seon.search/handle]: expected the
required key :seon.search/handle … got a map missing :seon.search/handle.
```

`supplied-handle` declared its return as
`[:or :seon.search/handle :seon.search/unavailable-error]`. The call
preparation coherence proof requires a supplier's return to be the row's value
shape plus `:seon.error/value` exactly
(`supplier-shape-query`/`supplier-return-arms-query`,
`src/seon/call_preparation.clj:166`), so the row was never proved, was dropped,
and the call then refused at the callee's contract for a key nobody filled.
The declared return is now `[:or :seon.search/handle :seon.error/value]` and
the docstring says why, because the failure mode is silent: an incoherent
supplier does not announce itself at the row, only at the first call that
needed it.



The registry atom `owners` and `:seon.search/index-id` are deleted. `open!`
returns an `IndexHandle` record carrying the directory, analyzer, writer,
`SearcherManager`, basis atom, lock and closed flag; `apply-report!`, `close!`
and `search` take that handle. `close!` releases every resource it acquired
even when an earlier close throws, and `open!` closes what it built when
construction fails part way.

The handle reaches an agent's call the declared way, not by a dynamic var:
`:seon.search/handle` is an optional `:facts`-layer member of
`:seon.env/environment`, `seon.search/supplied-handle` is its declared
call-preparation supplier (`config/default.edn`), and `:seon.search/request`
requires the handle — so `(seon.search/search {…})` with no handle receives
its own cluster's, and a cluster whose index never opened gets a typed
refusal naming the member instead of an empty result set. The absence value
carries `:seon.error/kind`, so `seon.call-preparation/supply` reports the
missing member rather than "the supplier produced an invalid value".

`src/seon/cluster.clj` carries the boot and graph wiring: the instance keeps
`:seon.search/handle`, the environment carries it, and the search proc's
`env/carry` takes it from the environment rather than the view. That file is
concurrently held by the incremental-publication continuation, whose own
hunks (`source-roots`, `issue-notes?`, `:seon.fn/root`) remain uncommitted in
the working tree; only the search hunks were committed from it.

### Item 30, other half: `mcp-projection`, recorded not applied

`src/seon/cluster.clj:236` is a `ThreadLocal` set by
`project-next-prepl-value!` and consumed by `consume-mcp-projection!` when the
prepl value returns. It is a side channel for a fact the CALLER already knows
at the moment it writes the form. The environment does not want to carry it —
it is per-request intent, not cluster state — so the shape is to make it an
argument of the evaluated form instead of a process-global:

```diff
-(defonce ^:private mcp-projection
-  (ThreadLocal.))
-
 (defn project-next-prepl-value!
-  ([] (project-next-prepl-value! false))
-  ([request] (.set mcp-projection …) nil))
-
-(defn- consume-mcp-projection! [] …)
+(defn mcp-value
+  "Evaluate `thunk` and project its value under the intent this call names."
+  {:malli/schema [:=> [:cat :seon.dev.mcp/intent [:=> [:cat] :seon.schema/value]]
+                  :seon.dev.mcp/projection]}
+  [intent thunk] …)
```

The MCP bridge then writes ONE form — `(seon.cluster/mcp-value {…} (fn [] …))`
— instead of a marking call followed by the work, and the intent cannot be
left over from a previous evaluation on the same prepl thread. This is not
applied: `cluster.clj` is held, and the change also touches the bridge that
composes the two calls today, which is outside this item's boundary.

### Not mine, left alone

`src/seon/test/selection.clj` and the remaining hunk in
`test/seon/test/selection_test.clj` carry inventory item 13 (green-basis
absence and `(catch Throwable _ nil)`) — a coherent implementation-plus-
regression pair written by another hand. It is not one of items 20, 21, 24 or
30 and was neither edited nor committed here; it was present in the working
tree during verification and is reported as such.

### Corrections made while finishing the inherited hunks

Two defects in the inherited uncommitted work were found and fixed here, both
of them the same shape — a cost or a refusal hidden behind something that
looked like detail:

1. `handle-generator` acquired REAL Lucene resources per sample (a
   `ByteBuffersDirectory`, an `IndexWriter`, a `SearcherManager`, and the
   commit that closing an `IndexWriter` performs). A generator runs wherever a
   schema embedding it is sampled — the request, the environment, the proc
   contract — so ordinary schema generation would have opened and committed
   hundreds of indexes. It now returns a closed handle holding no resources,
   which is exactly what its `closed?` flag already declares.
2. `handle?` was public and carried a `:malli/schema`, so the predicate every
   validation of every embedding schema calls ran inside an armed contract
   wrapper. It is now private and uncontracted, like `ping-map-fn?` and
   `datahike-datom?` beside it, and is still registered by symbol.
3. `bin/test-check` read `@#'operator/shipped-default-decisions`, which is the
   DELAY, not the decisions; `(get <delay> k)` is nil. The script's own
   up-front refusal caught it on the first live invocation
   (`check unavailable: config/default.edn declares no
   :seon.test/check-time-limit-ms`), which is the check behaving as AGENTS.md
   §0 asks — it reported absence rather than proceeding with no bound.

### Verification boundary

- `clj-kondo` over the five edited Clojure files: **0 errors** (16 pre-existing
  shadowed-var warnings, none in the new forms).
- `bin/test-check default --test seon.search-test/tokenization-follows-natural-name-separators
  --time-limit-ms 60000` reached the live cluster and returned
  `No such var: seon.test/check-request` — which proves the transport, the
  argument composition and the single evaluated form, and NOT the function's
  behaviour: the `default` JVM has been up since before `check-request` was
  committed and a lane never restarts it. The function's behaviour is proven
  by its regression, not by this invocation.
- `bin/test-fast seon.search-test seon.test.check-request-test
  seon.program-test seon.test.selection-test` (log
  `tmp/fable-fast-all.log`): **41 tests, 312 assertions, 6 failures, 0
  errors** — all six in the two namespaces this lane changed, none elsewhere.
  `seon.program-test` and `seon.test.selection-test` were green, including the
  item 21 work already committed at `6df6967b8`. The six failures were the
  incoherent supplier (two, `seon.search-test`) and the missing await
  diagnostic evidence (four, `seon.test.check-request-test`); both causes are
  named above and fixed. **The re-run proving them green was still queued for
  one of the three test slots when this lane reported**, so those two
  namespaces are verified by diagnosis and fix, not yet by a green run — the
  honest state, recorded rather than implied.
- `seon.test-test` NAMED IN THE ASSIGNMENT DOES NOT EXIST —
  `bin/test-fast seon.test-test` fails to locate `seon/test_test.clj`; the
  `seon.test` namespace's regressions live in `seon/test/*_test.clj`.
- The first two attempts died at the liveness backstop, in the cold fixture
  base, with no verdict. That is not this change: a foreign run
  (`tmp/test-liveness/95401-1789598207079.log`, 16:36) fired the same backstop
  in the same machinery eight minutes before the earliest edit here, and two
  other lanes' live command lines already carried
  `SEON_TEST_SILENCE_SECONDS=900`. Filed as
  [the-cold-fixture-base-outruns-the-liveness-silence-backstop](../../../seon/issues/the-cold-fixture-base-outruns-the-liveness-silence-backstop.md).
- No cluster was started, stopped or reforked. `default` was read from and
  invoked through `bin/test-check`; it was not restarted.

## The committed slice refused every live publication — and why

Resumed 2026-09-16 after the lane was terminated at 23:17Z. Item #24 and item
#30's search half were already committed (`68e95b029`); the landing note and
the issue were owed. The first thing found on resuming was that the committed
slice had wedged the whole machine.

### The refusal

Every `bin/seon init --dev default` — so every edit hook run, for every agent —
failed in `logs/current-source-failure.log` with:

```clojure
{:seon.schema/error :seon.schema/unresolved-predicate,
 :seon.schema/unresolved-predicate seon.search/handle?,
 :seon.schema/predicate seon.search/handle?,
 :seon.error/kind :user-input,
 :seon.error/message
 "Predicate seon.search/handle? has no admitted callable in the corpus projection."}
```

raised as `:seon.cluster.source/scratch-schema-refused` during the branch
publication of `current-src` (`src/seon/cluster/source.clj:602`).

### It is not privacy, and the siblings are not special

`resources/seon/schemas/seon.search.edn:6` declares `:seon.search/handle` as
`[:fn {…} seon.search/handle?]`, and `src/seon/search.clj:72-79` defines
`handle?` as a `defn-` calling `schema/register-core-predicate!` at load —
byte-for-byte the shape of `datahike-datom?` and `ping-map-fn?` above it
(`src/seon/search.clj:31-66`). Privacy is irrelevant: `ns-resolve` returns a
private Var, which is what `seon.schema/loaded-predicate-var`
(`src/seon/schema.clj:150-163`) uses.

The difference is measured, not inferred. In the live JVM (pid 41413):

```clojure
(some-> (find-ns 'seon.search) (ns-resolve 'handle?))      ⟹ nil
(some-> (find-ns 'seon.search) (ns-resolve 'ping-map-fn?)) ⟹ #'seon.search/ping-map-fn?
(get (#'seon.schema/packaged-forms) :seon.search/handle)
  ⟹ [:fn {…} seon.search/handle?]         ; 2790 forms, read from disk
```

The siblings resolve only because they PREDATE this JVM's load of
`seon.search`. Schema resources are read from the classpath on every
publication, so the declaration was live the instant the file was written,
while the JVM still held the copy of `seon.search` it loaded at boot.

### The order is the defect

`compilable-form` (`src/seon/schema.clj:~208`) resolves a `[:fn …]` predicate
through the caller's explicit `predicate-functions` or through
`loaded-predicate-var`, which deliberately never loads a namespace. The
reload that would install `handle?` is part of development ADOPTION
(`src/seon/cluster.clj:2378`), which runs strictly AFTER the publication that
was refused — so the publication that would have fixed the JVM could never
run. A fresh JVM compiles the same commit without complaint; the long-lived
one can never leave the state. `require` alone cannot leave it either: on a
loaded namespace it is a no-op, and only `:reload` replays the
`register-core-predicate!` forms.

This is the owner law's pre-read: the JVM's var table is a MIRROR of the
source the publication is about to re-decide.

### The fix

`src/seon/schema.clj` — `converged-predicate-var` resolves a predicate
against its own classpath SOURCE on the path that would otherwise refuse: a
namespace this process ALREADY LOADED, whose copy lacks a predicate its
source declares, is RELOADED. An UNLOADED namespace is left alone and still
refuses, so `loaded-predicate-var`'s guarantee — an authored `[:fn …]`
cannot make the process require code, which
`seon.schema-test/canonical-definition-keeps-admitted-predicate-symbols`
owns — is intact. It is the stale MIRROR, not an absent one, that wedged the
machine. It runs ONLY where the alternative is the refusal itself — a predicate that already resolves is never reloaded — so it
can turn a wedge into a success and cannot change a working compile.
Convergence is attempted at most once per predicate per state of its source
(`namespace-source-stamp`: the classpath resource URL and its last-modified
millis, derived from the classpath, never from the namespace's name), so a
declaration naming a definition its source genuinely does not have reloads
once rather than on every form — and a later source edit is a new stamp, so
the next publication retries. That surviving refusal now also names
`:seon.schema/predicate-namespace`, which is what the acceptance criterion in
[live-publication-has-a-hand-maintained-predicate-owner-reload](../../../seon/issues/live-publication-has-a-hand-maintained-predicate-owner-reload.md)
asks for.

The two seams the issue's own "To do" proposed — `seon.cluster.clj:2466` and
`seon.schema.edn/packaged-forms` — were both held by concurrently editing
lanes (`git status`), so the fix landed at the one resolver, in
`src/seon/schema.clj`, which no other lane held. That placement is also the
better one: it is the single point every compile path already goes through,
so no new caller can forget it.

### The regression

`test/seon/schema/predicate_owner_probe.clj` is a loaded namespace that owns
nothing but one private predicate and its registration.
`seon.schema-test/a-declaration-compiles-against-a-predicate-its-loaded-owner-lacks`
`ns-unmap`s that predicate — which is exactly the live state, a namespace
LOADED without a predicate its source declares — proves a plain `require`
cannot leave it, then proves compilation converges and binds the Var. The
probe has a file on the classpath deliberately: it must be RELOADABLE, not
unloadable, and a reload restores it to the definition in the file, so the
regression leaves the worker in the state it entered. The second arm proves a
predicate the source genuinely does not define still refuses, naming both the
predicate and its namespace.

### The live proof, and the reset it exposed

The live JVM could not adopt the fix while it was itself wedged, so the
owner's namespace was reloaded through the supported MCP JVM evaluation —
the same repair recorded on 2026-09-09 in
[a-new-core-predicate-and-its-schema-cannot-be-adopted-in-place](../../../seon/issues/a-new-core-predicate-and-its-schema-cannot-be-adopted-in-place.md).
No process lifecycle action was taken; `default` (pid 41413) was never
stopped, restarted or reforked.

```clojure
(require 'seon.search :reload)
(seon.schema/core-predicate-registered? 'seon.search/handle?)  ⟹ true
```

`bin/seon init --dev default --changed src/seon/search.clj --changed
src/seon/schema.clj` then ran the whole publication it had been refusing:

```text
● current-src: branch publication started
● current-src: schema population started
● current-src: schema population complete
● current-src: contract projection started: 2790 schemas, 1140 functions
● current-src: program population compiled: 39604 entities, 19584 identities,
  36287 keyword facts
● current-src: population: 95475/95475
● current-src: branch publication complete
● current-src: development schema declarations
✗ The cluster population transaction was refused.
```

The scratch-schema refusal is gone and the branch publication completes. The
refusal that now stops ADOPTION is a different, foreign one:

```text
Cluster `default` cannot reopen in place: `:seon.context.capture/prompt`
changed :db/noHistory from true to nil, which Datahike does not apply to an
installed attribute.
```

**RESET NEEDED — commit `79c106925` ("fix: retain captured prompts in temporal
history").** That commit removed `:db/noHistory` from
`:seon.context.capture/prompt`; the installed `default` store predates it and
Datahike does not apply the change to an installed attribute. A lane does not
refork `default`, so this is recorded for the orchestrator, which batches it
with any other pending schema change. Live publication of `current-src` is
unblocked for every lane regardless — that is the branch this change repairs.

### Deferred hunks — released, not blocked

Both were held by concurrently editing lanes when this lane resumed. Both
holders have since landed, so the honest record is "released and unclaimed",
not "protected":

- **Item #20**, `declaration-required-attributes`
  (`src/seon/program.cljc:899-904`) — a hand-maintained mirror of what the
  declared row schema's own `:required` already says. `src/seon/program.cljc`
  is clean at `ec5f0ba59`; the literal is unchanged.
- **Item #30's second half**, `mcp-projection` (`src/seon/cluster.clj:236`) —
  a process-global `ThreadLocal` where the environment should carry the
  value. `src/seon/cluster.clj` is clean at `ec5f0ba59`; the `ThreadLocal` is
  unchanged. The search half of the same item landed at `68e95b029`.

The remaining `src/seon/search.clj` hunk in the working tree (the catch-all
removal around `:204-211`) belongs to the tier-0 cleanup lane and was not
touched, read into, or committed here.

### What the gate changed about the fix

The first draft of `converged-predicate-var` memoized its attempts in a
process-global atom, and
`seon.schema-test/one-predicate-symbol-cannot-name-two-environments-callables`
failed on it by name:

```text
actual: (not (empty? ([converged-predicate-attempts
                       #'seon.schema/converged-predicate-attempts])))
```

That regression exists because a process-global predicate cache was the
2026-08-07 isolation defect (I.3), and it was right: the memo was exactly the
shape it forbids. It was deleted rather than renamed, and it is not needed —
a successful reload makes the predicate resolve, so convergence never runs
for it again, and a predicate that survives its own reload throws out of
`compilable-form` and ends the walk. The remaining cost is repeated reloads
in an already-broken state, paid only by a caller that swallows the refusal
and keeps asking (`storable-attribute-in?` is one). That trade is stated in
the function's docstring rather than hidden.

The first draft also loaded UNLOADED namespaces, which weakened the
guarantee `canonical-definition-keeps-admitted-predicate-symbols` owns: an
authored `[:fn …]` must not make the process require code. Convergence is now
bounded to namespaces this process has already loaded — which is the actual
wedge, a stale MIRROR rather than an absent one — and the regression carries
a third arm asserting that an unloaded predicate namespace is still never
required.

### Verification boundary

- `bin/test-fast --paths src/seon/schema.clj test/seon/schema_test.clj
  test/seon/schema/predicate_owner_probe.clj -- seon.schema-test
  seon.search-test seon.cluster.source-test`, snapshot HEAD `57ac57ad7` plus
  those three paths: **47 tests, 656 assertions, 19 failures, 9 errors.**
  Read by cause:
  - `seon.schema-test/a-declaration-compiles-against-a-predicate-its-loaded-owner-lacks`
    **passed** — the class this change kills.
  - `canonical-definition-keeps-admitted-predicate-symbols` **passed** — the
    guarantee this change must not weaken.
  - 1 failure was MINE and is fixed above (the memo atom).
  - 17 failures are `pulled-references-satisfy-every-declared-entity-contract`
    on `:my.plan/entity`, `:my.plan.item/item` — `[:my.plan/steps]`,
    `[:my.plan.item/needs]`. No predicate is involved; this is the
    entity-schema-versus-pulled-shape class AGENTS.md §3 already records (29
    marked component maps unselected). NOT baselined by a run of my own, and
    named here as foreign rather than claimed as proven foreign.
  - 9 errors are `seon.cluster.source-test`, every one
    `the source seal transaction was refused` at the ACTIVATION SEAL
    (`src/seon/cluster/source.clj:626`), with
    `:datahike/write-rejected {:kind :transaction/validation-rejected}`.
- **The seal refusal is a SECOND CAUSE, not `seon.search/handle?`.** Three
  independent reasons: the string `unresolved-predicate` does not appear once
  in the whole run; the wedge's refusal is a different rule at a different
  transaction (`::scratch-schema-refused` at the scratch SCHEMA transaction,
  `:601`, kind `:user-input`) while this is `::source-seal-refused` at the
  activation seal with Datahike's own writer rejection; and a cold worker
  loads `seon.search` fresh, so `handle?` resolves there by construction and
  the wedge cannot occur in a test JVM at all. It is red at HEAD independent
  of this lane, as the coordinator measured separately.
- A second `bin/test-fast --paths … -- seon.schema-test` run proves the final
  bytes, after the memo was deleted and the unloaded-namespace arm added.
- `clj-kondo` over `src/seon/schema.clj`: **0 errors** (27 pre-existing
  shadowed-var warnings, none in the new forms).
- `default` (pid 41413) was read from, evaluated in, and published to. It was
  never stopped, restarted, reset or reforked.
