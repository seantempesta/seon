---
type: landing
status: owned fix verified; root-turn panic assigned to error/turn owners
created: 2026-09-22
tags: [agent-platform, root-seed, definition-digest]
---

# Root seed declaration constructor

## Current result

The owner extended `program/declaration-row` and `bootstrap/seed-tx` on the final
resume. The complete authorized patch is applied:

- `cluster.agent/creation-tx` generates one `ns` form through the existing
  `seon.sci.reader/read` → `program/declaration-row` path, including the bootstrap
  and clojure.test refer bindings. Its transaction function derives absence and
  projection from the current transaction database. No fake file is required.
- `bootstrap/seed-tx` no longer alters the namespace through a second raw map.
- `program/declaration-row` canonicalizes before computing a missing digest.
  Already-analyzed supplied digests retain their existing resolver semantics.
- The canonical fixture proves the root seed's stored source/digest and first
  turn identity; a second regression reads the same form at lines 1 and 3 and
  checks identical digests equal to `definition-digest` of each constructed row.

Final armed in-process result: **2 tests, 13 assertions, zero failures/errors**,
2,146.06225 ms. Recorded test admission remains unavailable. From-zero readiness
was **90,648 ms** in the final run; the earlier **95,992 ms** is also retained.
Both are far beyond the ten-second target; these are measurements, not waivers
or a speed pass. Root's first turn still panics at the out-of-scope error/turn
boundary diagnosed below. No HEALTHY/completed-turn claim is made.

Production sizes: `cluster/agent.clj` +29/-6, `bootstrap.clj` +1/-21,
`program.cljc` +3/-2; new regression file 65 lines. No cluster publication span
or other lane's source was edited. Implementation commit and final HEAD load
are recorded in the completion section.

## Confirmed original bypass and canonical path

At inspected HEAD `30222ee233af38b37e42f4e027fd27d3e75feb26`, boot called
`seed-root-agent!` → `ensure-entity!` → `ensure-entity-call` →
`cluster.agent/creation-tx`. The last hand-built `my.agents.root` as a namespace
map with neither source nor digest. Step 1.2 was `f27b96c19`.

The initial explicit-root/cluster JVM probe fed that map to
`program/declaration-row` and returned in 227 ms with
`:seon.program/missing-attributes [:seon.ns/source]`. The owner then ruled that
source must be read through the existing path and extended ownership to
`cluster/agent.clj` and its callers.

The existing path is `sci.eval/one-event` (`eval.clj:495`) → `reader/read`,
whose `declaration-facts` (`reader.cljc:399`) derives namespace facts and source;
`sci.eval/row` (`eval.clj:326`) calls `program/declaration-row`.
The new seed calls those same public reader and constructor functions, with
`:contracted` policy and `:agent` admission. No hand-built declaration remains
in `creation-tx`; the second bootstrap writer still needs conversion.

Datahike gitlink: `cc2b2bc7dbe774ea1bcc7487e8225a0d420d0e17`.
`reference-code/datahike/src/datahike/db/transaction.cljc:1152` supplies the
mid-transaction database to `:db.fn/call`. Inputs are one namespace name and
that database's carried projection; construction occurs only for an absent
namespace, with work proportional to its source form.

## REPL proof and preflight

`bin/seon status` and MCP `runtime_status` found default degraded/unknown, PID
28922, start `2026-09-22T17:05:36.127Z`, zero agents and no cluster connection.
Explicit connection acquisition refused. Default was never reset or mutated.

The source-only JVM probe on default was:

```clojure
(let [source (pr-str '(ns my.agents.root
                       (:require [my.message] [my.turn] [seon.db])))
      events (seon.sci.reader/read
               {:seon.sci.reader/text source
                :seon.config.eval.result/max-source (count source)})
      projection (seon.schema/declaration-projection
                   (seon.schema.edn/packaged-forms))
      row (seon.program/declaration-row
            projection (first events) :contracted :agent)]
  {:events events :row row
   :digest-matches? (= (:seon.program/definition-digest row)
                       (seon.program/definition-digest row))})
```

Returned in 58 ms: valid namespace/source/requirements, digest
`ae6b212212888b2aa6da3a6a3f98c2faa186847be9db0f630035b633fcc3be0d`,
`:digest-matches? false`. Earlier exploratory probes used a nonexistent
`schema/projection` Var and omitted the reader's required max-source bound;
those refused and are not counted as successful proof.

## Earlier focused test and in-process evidence (before final owner fixes)

```sh
bin/test-fast --paths src/seon/cluster/agent.clj test/seon/cluster/root_seed_digest_test.clj -- seon.cluster.root-seed-digest-test
```

Snapshot base `60b94954a3deb1afb33910eb2e504fdec199f978`, only the two named
paths. Loaded and armed 1,683 Vars / 1,677 program-armable Vars. Refused before
execution at `seon.test.runner/record-persistent-results!`: `Test recording
requires a published current-src.` No recorded green or executed test is claimed.
Log: `tmp/orchestrator/root-seed-digest-test.log`.

An authorized detached snapshot at `dcc15e5b3` with only owned changes and linked
vendored dependencies supplied the isolated boot and in-process proof. The
in-process form is retained in `tmp/orchestrator/root-seed-digest-probe.clj`.
It opened the stopped scratch store through the store owner, retained the core
fault and root facts, released it, then used the scratch publication as the
canonical fixture base and armed through `seon.test.arm/initialize-contracts!`.

The class regression establishes absent root/namespace, seeds through production,
reads the source-derived namespace and digest, and establishes the root and its
first turn. Latest in-process result: **1 test, 9 assertions, 7 pass, 2 fail,
0 errors**, 2,129 ms; 1,684 Vars armed / 1,678 program-armable. Requires unexpectedly
include `seon.bootstrap` and `clojure.test` from the second writer. Digest equality
also fails (`5ddbb3...` recomputed versus `ae6b212...` stored). Pull normalization
uses the existing `render.value/transacted` owner; it does not fix these defects.
Logs: `root-seed-digest-probe.log` and `root-seed-digest-probe-normalized.log`
under `tmp/orchestrator/`. This diagnostic bypasses recording; it is not green.

## Earlier from-zero boot and first-turn failure

Created the initially absent scratch directory, then ran:

```sh
bin/seon --root tmp/root-seed-root reset --force
```

Shared-tree attempt exited with `Source changed during publication; retry.`
The authorized stable snapshot ran the equivalent absolute-root command:

```sh
# cwd: tmp/root-seed-digest-wt at dcc15e5b3 plus owned changes
bin/seon --root /Users/sean/src/seon/tmp/root-seed-root reset --force
```

PID 41038, exact start `2026-09-22T17:43:48.833Z`. Reported readiness **95,992 ms**,
one agent, no missing layers, source commit
`6ab2beba-88a6-5199-85ce-c225915cc2a8`, web bound at port 7994.
Observed RSS during boot was approximately 3.1–3.8 GiB. A stack observation
identified the expensive publication phase in `seon.db/write-owned-values-error`
under the Datahike final-report validator; no timing is attributed to namespace
construction from this whole-program boot measurement.

Immediately afterward the root turn proc panicked at
`seon.schema.internal/extends-schema?`, with Malli `:malli.core/invalid-schema`
for nil form/schema, through `seon.error/validate-declaration!` and `prepare`.
Signature `3b0fee54475a2dcc98b2ffd5bcb195947072c329eb2fcf2662537b3b7c66866d`.
The JVM exited; the subsequent `status` found no live exact-root JVM.
**No HEALTHY status or unparked first-turn pass is claimed.** The durable root
namespace and first-turn-linked error were independently read from the stopped
store. This is a foreign error-rendering boundary, not a reason to omit the
remaining owned fix or to claim success.

Full fault blob was preserved before scratch cleanup:
`tmp/orchestrator/root-seed-fault-036f0f9f1926a801a06d24d813bae1a94b363437dd1820c7fd973234f79b1e22.edn`.
Boot, status and probe logs are retained beside it. No new cause is inferred beyond
the recorded stack; no error/schema owner was edited.

## Writer survey

`rg` covered `:seon.fn/sym` writers outside fn/program, plus namespace writes on
the boot path. Confirmed bypasses: agent creation and bootstrap seed namespace
maps. Config occurrences at `config.clj:625,638` check function existence;
schedule root-maintenance rows use lookup refs, not declaration writes.
SCI agent defn uses `fn/source-rows` → `program/declaration-row`; its later
runtime metadata updates remain for the realities/program orchestrator to assess.
This is a targeted survey, not an exhaustive digest-invariance proof.

The shared publication hunks in `cluster.clj` were neither edited nor staged.
The final source/test proof and remaining first-turn boundary are below.

Cleanup completed: verified boot/probe PIDs absent and `lsof +D` reported no
holders; removed only `tmp/root-seed-root`, `tmp/root-seed-digest-wt` and the
owned `tmp/test-runs/run.sFr1ws` if present. Recursive cleanup did not follow
symlinks. All owned shells/JVMs have exited. Evidence remains in `tmp/orchestrator`.


## Final proof after the authorized owner fixes

Stable source snapshot: `d23367f31455399ebae101860caa2491fb823d4e` plus only
`src/seon/cluster/agent.clj`, `src/seon/bootstrap.clj`, `src/seon/program.cljc`,
and the new regression. All four final paths were supplied to:

```sh
bin/test-fast --paths src/seon/cluster/agent.clj src/seon/bootstrap.clj src/seon/program.cljc test/seon/cluster/root_seed_digest_test.clj -- seon.cluster.root-seed-digest-test
```

It loaded and armed 1,684 Vars / 1,678 program-armable Vars, then refused before
execution: `Test recording requires a published current-src.` Run request
`4c9e5cfc48c2`. Log: `tmp/orchestrator/root-seed-digest-final-test-fast.log`.
A literal-newline correction in the test was made after that snapshot; the final
in-process proof below exercised the corrected test. No launcher green is claimed.

The final in-process proof used the canonical fixture on a private branch of the
scratch root's from-zero publication, through the same retained probe script and
`seon.test.arm/initialize-contracts!`; it ran in the isolated snapshot after the
scratch JVM exited. **2 tests / 13 assertions / 13 pass / 0 failures / 0 errors**,
**2,146.06225 ms**, 1,684 Vars armed / 1,678 program-armable. This is an armed
`clojure.test` execution, not recorded admission. The owner explicitly permitted
this proof on recording refusal. Log: `tmp/orchestrator/root-seed-digest-final-probe.log`.

Read-only MCP JVM proof on the final scratch root returned in **41 ms**:
reader lines `[1 3]`, identical source strings, identical digests
`8ca3b065562cd1d3c7390940d1d65aae0f485259c7ad9a57fd0852793550a90f`,
and `[true true]` for equality to `definition-digest` of the constructed rows.
The stored root declaration digest was
`57f2d7088e55ff53d7092f792b5b324f74af3261d1f8c0249fbf38bd0ef7c65c`.

The final exact reset command (snapshot cwd) was:

```sh
bin/seon --root /Users/sean/src/seon/tmp/root-seed-root reset --force
```

PID 42566, start `2026-09-22T17:54:15.540Z`, readiness **90,648 ms**, one agent,
no missing layers, source commit `6ab2c128-7b92-5859-a406-a4409abeafef`.
`status` observed root and proc ping replies, but one error signature and zero
completed turn passes. REPL read returned root turn `12a2b18544e6` with no
closed transaction. The same core panic was durably recorded twice; the JVM
subsequently exited. Logs: `root-seed-digest-final-boot.log` and
`root-seed-digest-final-status.edn` under `tmp/orchestrator/`.

## Exact panic cause and smallest out-of-scope repair

The missing value is the **declaration name in the recording request**, not a
missing source file or a digest. The preserved full exception/stack decodes to:

```clojure
{:type :malli.core/invalid-schema
 :message :malli.core/invalid-schema
 :data {:schema nil :form nil}}
```

Actual call chain:

- `src/seon/turn.clj:5069` generates a failed phase and calls `settle!`.
- `turn.clj:3826` → `refusal-terminal-data`, whose line 3705 uses the legacy
  five-argument `error/recording` arity with only agent/turn attribution.
- `src/seon/error.clj:1557-1564` constructs the recording request without
  `:seon.error/declared-schema`; the request arity calls `prepare` at line 1534.
- `error.clj:597` calls `validate-declaration!`; line 539 looks up the nil name
  and line 538 passes nil to `internal/extends-schema?`.
- `src/seon/schema/internal.cljc:118` calls `malli.core/type` on nil, causing
  the exact exception above. This helper's declared contract requires a compiled
  schema; teaching it that nil means false would conceal its caller's defect.

Read-only reproduction at the live scratch REPL (explicit root/cluster, JVM),
**44 ms**, returned nil registry lookup and the identical exception/frames:

```clojure
(let [projection (seon.schema/declaration-projection
                   (seon.schema.edn/packaged-forms))]
  {:missing-lookup (malli.registry/schema
                    (:seon.schema.projection/registry projection) nil)
   :refusal
   (try (#'seon.error/validate-declaration!
          projection nil {} (java.util.Date. 0))
        (catch clojure.lang.ExceptionInfo e
          {:message (ex-message e) :data (ex-data e)
           :frames (mapv str (take 18 (.getStackTrace e)))}))})
```

History: `git log -S extends-schema? --oneline -- src` identifies `a86e93e21` as
the new error-validator caller; the helper itself predates today (`22a1a0567`).
`a86e93e21` adds both `validate-declaration!` and the mandatory declared-schema
members to error request schemas, while leaving the legacy five-argument arity
and its turn callers unchanged. Its parent inferred matching declarations;
that old behavior was removed. Therefore **a86e93e21 introduced this missing
caller conversion**. The reviewed `6d84f27fa` rename, `f27b96c19` digest,
`60b94954a`/`dcc15e5b3` publication changes and `62487dbc3` search correction did
not introduce this validator or convert the failing legacy request.

Smallest owner repair: convert the legacy recording call boundary to carry the
producer's explicit `:seon.error/declared-schema` alongside its source (the
existing `:seon.error/recording-observation` schema already declares both), and
pass that through to the request arity. `turn.clj` has four legacy calls at
3705, 3752, 4061, 4066. Escaped host failures may be explicitly declared at their
producer as `:seon.error/normalization-error`; do not infer a declaration or add
a universal fallback inside the recorder. `validate-declaration!` must also
refuse an absent registry entry through its existing named refusal before
calling the compiled-schema helper. This is error/turn ownership, not any file
owned by this slice, so no production fix was made there per the owner's stop
rule. The root's original phase failure is masked by this recording panic;
its cause is not claimed established.

Raw evidence: `tmp/orchestrator/root-seed-fault-decoded.edn`, original full blob,
and final probe log. Existing issue-authority search found related historical
error-recording/retirement issues but no exact legacy-arity/nil-declaration class;
this named defect is recorded here as the owner expressly requested.
