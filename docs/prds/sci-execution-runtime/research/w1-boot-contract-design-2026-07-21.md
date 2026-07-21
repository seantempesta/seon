---
type: research
status: active
tags: [research, architecture, database]
---

# W1 boot-contract design: one resolution, three consumers, provable equality

Implementable design for W1's two-phase boot authority under the two
2026-07-21 PM owner rulings ([[../program-synthesis-2026-07-21]] §"Owner
rulings — W1 boot contract"): (1) there is NO config-free boot — config is
always resolved at boot and the writer receives its boot-critical limits
from that always-present resolution; (2) `config apply` with a
boot-critical change performs LIVE WRITER RECONSTRUCTION. This settles the
four items that stopped the first `specs/w1.1-boot-contract.md` attempt and
becomes the W1.1 implementation-spec base. Every interface claim below
cites the source line verified today on branch
`codex/runtime-reliability-refactor`, or says NOT GROUNDED.

## 0. Dependency ledger

| Dependency | Where read | What it settles |
|---|---|---|
| aero 1.1.6 | `deps.edn:136` pins `aero/aero {:mvn/version "1.1.6"}`; vendored source `reference-code/aero/src/aero/core.cljc` (cljc: `:clj` branch uses only `clojure.edn`, `clojure.java.io`, `StringReader`; `:cljs` branch uses `cljs.tools.reader`) | bb-loadability probe below; the `#merge` override seam (`config.cljs:689-695`) must move with the resolver |
| babashka v1.12.212 | `bb.edn:1-8` — `:paths ["script" "src" "test"]`, deps cheshire/malli/bencode; PROBE (this machine, 2026-07-21): `bb -Sdeps '{:deps {aero/aero {:mvn/version "1.1.6"}}}' -e "(require '[aero.core :as aero]) (aero/read-config \"config/system.edn\" {})"` → 19 top-level keys, success | option (a) is live: aero loads and reads the real manifest in bb; the "aero not loadable from bb.edn" finding from the first attempt is FALSIFIED (exact prior failure mode NOT GROUNDED — most likely a require of CLJS-only `seon.config` rather than of `aero.core`) |
| first-party cljc in bb | `script/seon/dev/config.clj:7-9` requires `malli.core` and `seon.launch`; `config.clj:75` runs `m/validate :seon.launch/descriptor` in bb today | the pattern "bb loads a src/ `.cljc` with `seon.schema` registrations" is already shipped, not speculative |
| pod aero seam | `src/seon/config.cljs:697-706` (`read-config-file` — "The ONE reader seam"), `:708-728` (`load-manifest-path` validates against `:seon.config/manifest`), `:730-739` (`load-manifest`: absent `SEON_CONFIG` → nil = preserve database) | stays the pod's only aero IO for the live `config apply` HTTP path |
| singleton resolver | `config.cljs:850-974` `resolve-config-singleton` (manifest → flat singleton, defaults inline), `:840-848` `configuration-read-profile`, `:816-820` `default-repl-mode` reads `SEON_AI_PROVIDER`/`SEON_AI_MODEL` env DIRECTLY — the one impurity the extraction must lift into explicit inputs | the pure body to extract |
| cold-boot reconcile | `src/seon/client.cljs:2142-2145` (manifest load gated on `startup?`), `:1932-1978` `apply-config!` (routes via `config/resolve-routes` `config.cljs:1589` + `route/core-routes-tx`, skills via `my.skills/seed-skills-tx-data` over `config/skills-dir` `config.cljs:1051`, singleton — one `seon.state/reconcile!`), `:606-623` `acquire-configuration!`, `:2212-2214` + `src/seon/db.cljs:702-706` one installed configuration context | the handoff must carry the full resolved manifest value, not only the singleton (§6) |
| launch descriptor | `src/seon/launch.cljc` — the EXISTING portable operator↔pod launch-data mechanism: closed `::descriptor` schema (`launch.cljc:110-119`), operator composes it (`script/seon/dev/config.clj:429-446`), exports `SEON_LAUNCH_DESCRIPTOR` (`script/seon/dev/process.clj:442`), pod decodes+validates (`launch.cljc:474-487`, `client.cljs:2134-2135`) | the envelope rides this mechanism; no new channel |
| writer entry | `src/seon/db/server.clj:188-225` `parse-arguments` (backend/db-name/path/req-sock/repl-port only), `:374-414` `start!` → `writer/start!` with NO operational limits, `:253-262` `read-bounded-edn` (bounded EDN file input precedent, restore-admin), `:431-440` + `:104-128` terminal result carries a generation | argv envelope-file seam and generation signaling |
| writer/executor constructors | `src/seon/db/writer.clj:4182-4263` `start!` (accepts optional `::selected-processors`, schema `writer.clj:115,123`; builds `executor/capacity`, `uds/start-request-server!` at `:4239-4245` passing NO caps), `src/seon/db/executor.clj:119-158` `capacity` (the house hardware derivations), `src/seon/db/transport/uds.cljc:150-161` (server options schema ALREADY has optional shutdown/input/slots/output/connections), `:166-174` (private defaults; frame bytes from `protocol/maximum-frame-bytes`), `:299-316` codec workers (private `max(2,min(8,procs))`, queue 256), `src/seon/db/protocol.cljc:100-102` (4 MiB frame = one wire contract) | exactly which limits thread now vs need step-5 surfaces (§2). NOTE: `uds.cljc` has uncommitted concurrent edits in the tree (`git status` dirty); lines verified as observed today |
| operator writer launch | `script/seon/dev/config.clj:44-50` (`default-writer-max-heap "512m"`, env `SEON_WRITER_MAX_HEAP` `:466-467`), `:110-131` `select-manifest` (explicit → inherited env → fresh-database → `config/system.edn`; packaged mode pre-sets `SEON_CONFIG` to the release config member `selected.edn` `:349-373`), `process.clj:549-568` writer argv (`-Xmx` at `:554`), `:485-488` pod depends on writer/watcher | resolution location, default-manifest location, heap render |
| pod session lifecycle | `src/seon/db.cljs:504-514` (`on-close!` clears session state), `:641-653` `active-or-reconnect!` (demand-driven reopen), `:495-564` `connect-selection!` (restores listener interests, emits resynchronization events), `:594-623` `open-session!` | pod-side quiesce/resume for live reconstruction needs NO new mechanism |
| host writer pool (W0.4) | `src/seon/host/context.clj:189-199` `writer-pool-defaults` (`(max 1 (dec cores))`, docstring already names the W1 relocation), `:434` `replace-member!`, `:522-542` `recovery-write!` + `sleep-before-recovery-poll!` | pool members already replace/recover across writer death |
| admission gate | `client.cljs:625-629` `shadow-build-notify!` closes admission on build start (`seon.runtime.admission`) | the existing pause mechanism live reconstruction reuses |
| config-apply path | `script/seon/dev/cli.clj:339-358` (operator POSTs `{:seon.config/path …}` to `/_seon/operator/config` under `state/with-lock` `:375-379`), `cli.clj:1056` `test operator` subcommand, `bb.edn:6-8` `operator-test` task | the one repair path §4 extends; the bb test surface the boot proof needs |
| hardware observation in bb | PROBES (this machine): `(.availableProcessors (Runtime/getRuntime))` → 18; `sysctl -n hw.memsize` via `babashka.process` → 137438953472; `java.lang.management.ManagementFactory/getOperatingSystemMXBean` FAILS in bb | observations are operator-side shell/interop data, passed to the pure resolver |

## 1. Where the operator resolves config

**Decision: option (a) — add `aero/aero {:mvn/version "1.1.6"}` to
`bb.edn` `:deps` and resolve in the operator (bb), sharing one pure
resolver `.cljc` with the pod.**

Grounding:

- The probe above shows `aero.core` loads in babashka v1.12.212 and reads
  the real `config/system.edn` (19 top-level keys). The vendored source
  confirms why: the `:clj` branch of `reference-code/aero/src/aero/core.cljc`
  needs only `clojure.edn`, `clojure.java.io`, `java.io.StringReader`,
  multimethods, and `defrecord` — all bb built-ins. `bb.edn` must pin the
  same coordinate `deps.edn:136` pins so both runtimes read one aero.
- bb already loads first-party `src/` `.cljc` with malli registrations:
  `script/seon/dev/config.clj:7-9` requires `seon.launch` (whose require
  chain includes `seon.schema`, `seon.db.protocol`, `my.blob.schema`) and
  validates `:seon.launch/descriptor` in bb at `config.clj:75`. The
  resolver extraction rides exactly this proven pattern.
- Option (b) — shelling to a small Clojure resolver process — was the
  fallback if aero could not load; it costs a JVM boot on every operator
  command and creates a second process mechanism. Rejected now that (a) is
  probed live.
- Option (c) — resolve in the pod and hand the writer a descriptor — is
  ordered backwards: the pod process spec DEPENDS on the writer
  (`process.clj:485-488`), so the writer exists first; a pre-pod Bun
  resolver run would be a second launcher. Rejected.

One caveat the spec must carry: `config.cljs:689-695` overrides aero's
built-in `#merge` with the manifest-aware `merge-manifest-pair` fold. That
`defmethod aero/reader 'merge` must live in the shared resolver `.cljc`
(§5) so bb-side and pod-side aero reads compose manifests identically —
otherwise the operator and the live `config apply` path would resolve the
same file to different data.

### The always-present manifest

Under the no-config-free ruling the operator resolves a manifest at every
boot. Selection precedence extends today's `select-manifest`
(`script/seon/dev/config.clj:110-131`):

1. explicit `--config` path;
2. inherited `SEON_CONFIG` (packaged operator pre-sets this to the release
   config member `<config-member>/selected.edn`, `config.clj:349-373`);
3. born database, nothing selected → **the cluster's retained applied
   manifest** (new; see below);
4. fresh database → the shipped default manifest **`config/system.edn`**
   at the repo root (`config.clj:124`; packaged installs use the config
   member per 2).

Item 3 is the one genuinely new fact. Today a born database with no
selection resolves nothing and the pod preserves database facts
(`config.cljs:730-739`). The ruling keeps that preservation meaning for
DESIRED-STATE reconciliation but requires limits anyway. The cluster must
therefore retain what was last applied. Recommendation (owner decision 1,
§8): at every successful apply/boot-reconcile the operator writes the
**post-aero resolved manifest VALUE** (ordinary EDN data, tags already
resolved) to `data/clusters/<name>/config/applied.edn` (atomic write, the
`server.clj:157-186` atomic-write-edn idiom). Config-free boots then run
only the PURE resolution over that retained data plus fresh hardware
observations — aero executes only when a manifest path is explicitly
selected, and boot resolution is a pure function every time. Precedent:
packaged mode already retains a per-install `selected.edn`
(`config.clj:349-351`).

## 2. The launch-envelope contract

The envelope is the boot-critical subset of the resolved singleton, plus
identity. It travels two ways from one resolution:

- **to the pod**: as a new closed section of the existing launch
  descriptor (`::launch/operational-envelope` inside
  `:seon.launch/descriptor`, `launch.cljc:110-119`), through the existing
  `SEON_LAUNCH_DESCRIPTOR` export (`process.clj:442`) and pod decode
  (`launch.cljc:474-487`). The pod uses it only for the equality proof
  (§6) and to configure its own UDS session peer once step 5 lands.
- **to the writer**: as one new argument `--launch-envelope <path>`
  pointing at an operator-written EDN file, parsed by `parse-arguments`
  (`server.clj:188-225`) via the existing bounded-EDN-file idiom
  (`read-bounded-edn`, `server.clj:253-262`), validated against the
  envelope schema registered in the shared resolver `.cljc`. Argv stays
  small; the envelope file lives in the writer process dir. (Individual
  flags per limit were rejected: the envelope is one value with one
  schema and one generation.)

Envelope keys, using the inventory's accepted names, split by when the
writer can actually enforce them:

**Threads NOW (constructor surfaces exist today):**

| Key | Consumer surface | Evidence |
|---|---|---|
| `:seon.config.database.writer/jvm-heap-mb` | operator only — rendered as `-Xmx<n>m` in writer argv | `process.clj:554`, `config.clj:44-50`; the JVM cannot receive heap post-launch |
| `:seon.config.database.executor/selected-processors` | `writer/start!` option `::writer/selected-processors` → `executor/capacity` | `writer.clj:4185,4199-4201`, schema `writer.clj:115,123` |
| `:seon.config.database.transport/maximum-connections` | `uds/start-request-server!` optional `::uds/maximum-connections` — writer just doesn't pass it yet | option schema `uds.cljc:161`, gap at `writer.clj:4239-4245` (pure pass-through, no uds internals edit) |
| `:seon.config.database.transport/maximum-input-bytes`, `/maximum-response-slots`, `/maximum-session-response-slots`, `/maximum-output-bytes`, `/maximum-session-output-bytes`, `/shutdown-timeout-ms` | same pass-through | `uds.cljc:153-160` |
| coordinates (db-name, backend, path, socket paths) | already argv | `server.clj:188-225` |

**Carried NOW, enforced at W1 order step 5 (option surfaces do not exist):**

| Key | Missing surface | Evidence |
|---|---|---|
| `:seon.config.database.transport/maximum-frame-bytes` | frame cap is a private def from the protocol constant, closed over by `write-frame!`/`read-frame` and the pod peer | `uds.cljc:166,221-224,235-238,253-256`, `protocol.cljc:100-102`, pod `uds.cljs:19` (inventory) |
| `:seon.config.database.executor.<class>/maximum-active`, `/maximum-queued`, `/maximum-queued-by-database`; `:seon.config.database.executor/maximum-queued-request-bytes` | `executor/capacity` computes only from processors; no injected-capacity arity | `executor.clj:119-158` |
| `:seon.config.database.transport/codec-workers`, `/codec-worker-queue-capacity` | private literals/derivation in `codec-workers` | `uds.cljc:167,299-316` |

Contract rule so step 5 slots in without changing the contract: **the
envelope is complete from day one**. The writer's ready log records, per
key, `enforced` or `carried` (carried = constructor still used its
compiled default). Step 5 flips keys from carried to enforced by adding
the option surface; no envelope, descriptor, or operator change. The
equality proof (§6) compares the envelope against committed facts either
way — a carried key whose resolved value diverges from the compiled
default is a loud fault before step 5 lands, which is correct: the
operator asked for a limit the writer cannot yet honor.

Envelope identity: `:seon.launch.envelope/generation` (monotonic long,
operator-owned) — matched by the writer terminal result's existing
generation idiom (`server.clj:104-128,431-440`) and used by live
reconstruction (§4).

## 3. Hardware-derivation formulas

House style precedent (these ARE the formulas' idiom): clamp chains
`(max 1 (dec processors))` (`executor.clj:124`, `host/context.clj:196`),
`(max 2 (min 8 processors))` (`uds.cljc:310`), and processor-tiered MiB
`(cond (<= p 2) 8 (<= p 4) 16 :else 32)` (`executor.clj:136-139`).

Hardware observations are an explicit input map produced by the operator
(IO stays operator-side; the resolver is pure):

```clojure
{:seon.hardware/cores 18                     ; (.availableProcessors (Runtime/getRuntime)) — bb PROBED
 :seon.hardware/system-memory-bytes 137438953472 ; darwin: `sysctl -n hw.memsize` — bb PROBED
                                             ; linux: /proc/meminfo MemTotal — NOT GROUNDED (no linux host probed)
 :seon.hardware/fd-soft-limit 10240}         ; `sh -c "ulimit -n"` — mechanism NOT probed; see §8 decision 3
```

`ManagementFactory/getOperatingSystemMXBean` is NOT usable in bb (probe
failed); the shell/interop observations above are the bb path.

Proposed computed defaults (aero-resolver defaults when the manifest omits
the key; every one overridable by an explicit manifest value):

| Key | Formula | Rationale / today |
|---|---|---|
| `jvm-heap-mb` | `(-> (quot system-mb 16) (max 512) (min 4096))` | today 512 dev (`config.clj:44`) vs 2048 Docker (inventory duplicate bug 4) — one formula replaces both: 8 GiB machine → 512, 32 GiB → 2048, ≥64 GiB → 4096 |
| `selected-processors` | `(.availableProcessors …)` observation, floor 1 | current behavior `executor.clj:121-123` |
| executor class families | keep `executor.clj:123-131,141-158` derivations **verbatim** as resolver formulas over `selected-processors` (cpu-workers `(max 1 (dec p))`, knn `(max 1 (min 2 (quot cpu-workers 2)))`, mutation `(max 1 (min 4 (quot (inc p) 2)))`, provider `(min 6 p)`, queues as coded) | the change is WHERE they compute (resolver, into facts), not WHAT; `read-defaults`' fresh-capacity bug (inventory) dies because writer and read policy consume the same resolved values |
| `maximum-queued-request-bytes` | `(-> (quot heap-bytes 16) (max (* 8 1024 1024)) (min (* 64 1024 1024)))` | replaces the processor tier `executor.clj:136-139` with the heap coupling the inventory asks for; 512 MiB heap → 32 MiB (matches today's large-machine tier) |
| `maximum-frame-bytes` | constant default `protocol/maximum-frame-bytes` (4 MiB); resolver VALIDATES `(<= value protocol/maximum-frame-bytes)` | the protocol ceiling is a wire contract, "one wire contract must reject the same legal frame on every host" (`protocol.cljc:100-102`); config may lower, never raise — not hardware-derived |
| `maximum-connections` | `(-> (* 16 cpu-workers) (max 64) (min 1024 (quot fd-soft-limit 4)))` | today constant 256 (`uds.cljc:174`); 18-core → 272 ≈ parity; couples to the FD budget the inventory names (each connection = 1 FD) |
| `maximum-input-bytes` | `(min (* 32 1024 1024) (quot heap-bytes 16))` | today constant 32 MiB (`uds.cljc:169`); parity at 512 MiB heap |
| `maximum-output-bytes` / session variant | `(min (* 256 1024 1024) (quot heap-bytes 2))` / half that | today 256/128 MiB constants (`uds.cljc:172-173`); the formula only bites below 512 MiB heap |
| `maximum-response-slots` / session variant | `maximum-connections` / `(quot maximum-connections 4)` | today 256/64 (`uds.cljc:170-171`) — slots track admitted connections |
| `codec-workers` / queue | `(max 2 (min 8 selected-processors))` verbatim / 256 constant default | `uds.cljc:310,167` |

The formulas live in the shared resolver `.cljc` as pure functions of the
observation map — they become "aero computed-defaults" in the sense that
resolution materializes them into the singleton; the manifest never has to
mention them, and `config/system.edn` should NOT hard-code them (a written
literal would freeze one machine's derivation into every cluster).

## 4. Live writer reconstruction (`config apply` with a boot-critical change)

**Verdict: this is its own unit (W1.2), separate from boot-resolution
(W1.1).** Reasoning at the end of this section.

The decisive physical fact: `-Xmx` is process-immutable — a heap change
CANNOT happen inside the running JVM. So at least one boot-critical key
always requires a new writer process. One-mechanism law then forbids a
second in-process rebuild path for the other keys: **reconstruction =
supervised writer-process replacement by the operator**, for every
boot-critical change. (Post-step-5, an in-process `stop!`/`start!` of just
the transport would be possible for frame/connection changes, but it would
be a second lifecycle mechanism that still can't cover heap. Rejected.)

The operator already owns everything required: the writer spec, readiness,
30 s shutdown grace (`process.clj:549-568`), the apply lock
(`cli.clj:375-379`), and the POST to `/_seon/operator/config`
(`cli.clj:339-358`).

### Sequence

1. **Resolve + diff.** Under the existing apply lock, resolve the selected
   manifest through the one resolver; compute the new envelope; diff its
   boot-critical subset against the retained envelope file (the operator
   wrote it at last launch, §2). No boot-critical delta → today's path
   exactly: POST reconcile, done.
2. **Quiesce.** Signal the pod to close runtime admission — the mechanism
   exists: `shadow-build-notify!` already closes admission on build start
   (`client.cljs:625-629`, `seon.runtime.admission`); reconstruction
   reuses that admission gate through a new operator endpoint next to
   `/_seon/operator/config`. Nothing throws into the agent loop: requests
   that still race the teardown become `:seon/error` values at the
   `seon.db` boundary (runtime contract).
3. **Stop the writer.** Ordered supervisor stop (existing grace/readiness
   machinery). `writer/stop!` refuses to report stopped while transport
   workers or interests remain (`writer.clj:4269-4291`); the terminal
   result publishes with the closing generation
   (`server.clj:104-128,431-440`). Committed transactions are durable on
   disk; in-flight uncommitted requests fail as values — errors-as-values,
   no data loss.
4. **Relaunch onto generation g+1.** New argv: new `-Xmx`, new
   `--launch-envelope` file with `generation g+1`. Same readiness gate as
   boot.
5. **Resume.** Pod-side needs NOTHING new: `on-close!` already cleared the
   session (`db.cljs:504-514`); the next demand reopens it via
   `active-or-reconnect!` (`db.cljs:641-653`) and `connect-selection!`
   restores every listener interest and emits resynchronization events
   (`db.cljs:533-564`). The host writer pool (W0.4) already replaces dead
   members and polls recovery (`host/context.clj:434,510-542`). The
   operator reopens admission after the pod session is re-proven.
6. **Reconcile + prove.** POST the config apply (same one resolver output);
   the equality proof (§6) now must pass against generation g+1's
   envelope. The proof failing here is the loud signal that a carried
   (pre-step-5) key was changed — see §2's contract rule.

Fact/generation signaling: the envelope generation in the descriptor and
writer terminal results makes "which limits is this writer actually
running under" a queryable fact chain; a derived render (reactive-context
law — computed from facts, never stored) can show "writer reconstructed
onto generation g+1" without any new notification mechanism.

### Why a separate unit

W1.1 (boot resolution) is complete and provable without reconstruction:
boot threads the envelope, the equality proof works, `config apply`
without a boot-critical delta works today. Reconstruction adds operator
orchestration (diff, quiesce endpoint, ordered replace, resume proof) and
a live drive proof (change `maximum-connections`, prove the new cap
admits/refuses, prove zero agent-loop crashes and listener resurrection).
Its full value also lands only after step 5 makes frame/executor keys
enforceable. Cutting it as W1.2 keeps W1.1 commit-sized and lets W1.2
depend on both W1.1 and (partially) W1.5.

## 5. Resolver extraction shape

New portable namespace **`src/seon/config/resolve.cljc`**
(`seon.config.resolve`; not `.internal` — bb's operator namespaces are not
`seon.config`'s parent, and the internals law restricts `.internal`
requires to the parent). Contents, all pure (data in → data out):

- `merge-manifest-pair` + the `defmethod aero/reader 'merge` override
  (moved from `config.cljs:665-695`) — aero is cljc and loads in both
  runtimes (probe, §1), so the defmethod registers identically for the
  operator's read and the pod's live-apply read;
- the manifest and singleton schemas the resolver validates
  (`:seon.config/manifest`, `:seon.config/singleton`, currently registered
  in CLJS-only `seon.config` — they move with the resolver; `seon.schema`
  is already bb-proven via `seon.launch`, ledger row 3);
- `resolve-config-singleton` moved from `config.cljs:850-974` with its
  default tables (`:822-848`), REPURIFIED: the env reads inside
  `default-repl-mode` (`config.cljs:816-820`) become fields of an explicit
  `:seon.config/env-observations` input map that each process's IO owner
  supplies;
- NEW `resolve-envelope`: `{manifest, hardware-observations}` → the §2
  envelope, including the §3 formulas and the frame-ceiling validation;
- the envelope schema + generation.

IO ownership after extraction (single-owner per process, unchanged rule):

- **operator (bb)**: reads manifest file via aero, observes hardware and
  env, writes the envelope file, the applied-manifest retention file, and
  `SEON_LAUNCH_DESCRIPTOR`; consumes `jvm-heap-mb` into `-Xmx`.
  (`script/seon/dev/config.clj` gains the aero read; `process.clj:549-568`
  gains the envelope path + rendered heap.)
- **pod (cljs)**: `config.cljs:697-739` remains the pod's only aero seam,
  used ONLY by the live `config apply` HTTP path; cold boot no longer
  aero-reads — it receives the resolved manifest value (§6) and calls the
  shared resolver. `config.cljs` keeps the accessors and re-exports the
  moved functions so its ~30 call sites do not churn.
- **writer (clj)**: `server.clj` reads the envelope file via the
  `read-bounded-edn` idiom; no aero, no env for operational limits.

No process runs aero twice per operation, and no process other than the
selected IO owner reads files or env for configuration.

## 6. Handoff and equality-proof mechanics

**The handoff carries the full resolved desired-state, not just the
singleton+envelope — CONFIRMED required.** `apply-config!`
(`client.cljs:1942-1950`) derives all three reconciled populations from
the MANIFEST: routes (`config/resolve-routes` `config.cljs:1589` over
`route/core-routes-tx`), skills (`my.skills/seed-skills-tx-data` over
`config/skills-dir` `config.cljs:1051`), and the singleton. A
singleton-only handoff would force the pod to re-read aero to rebuild
routes/skills, violating the no-second-aero rule. Therefore the operator
hands the pod the **post-aero resolved manifest value** and the pod feeds
it to `apply-config!` unchanged. (Skills-dir file reads inside the pod are
manifest-declared corpus IO, not a second config read.) Transport: the
manifest value with agent-context trees is too large to assume env-var
safety — the operator writes it next to the envelope file and the
descriptor carries `path + SHA-256 digest`; the pod reads, digests, and
validates before use. NOT GROUNDED: an actual measured size of a resolved
`config/system.edn` value vs the platform env limit — the file+digest
seam removes the question rather than answering it.

Proof mechanics, two directions:

1. **Envelope ⊆ committed facts (the pod proof).** After reconciliation
   (or, on a non-reconciling boot, after `acquire-configuration!`
   `client.cljs:606-623`), the pod takes `select-keys` of the decoded
   singleton over the envelope's key set and compares with the
   descriptor's envelope section. Divergence → `seon.error/record!` fault
   naming each differing key with envelope-vs-fact values, routed through
   the existing `:seon.config/on-core-error` dial
   (`config.cljs:881-882`) — dev fast-loud, never silent drift.
2. **Resolution vs retained facts (the config-free-boot drift check).**
   On a born-database boot with no explicit selection, the resolution
   comes from the retained applied manifest (§1) — the proof compares it
   against retained facts and records any divergence as a fault whose
   steering names `bin/seon config apply` as the repair, WITHOUT
   re-reconciling (preserving the absent-`SEON_CONFIG` meaning: no
   desired-state re-reconcile; see §8 decision 4).

Writer-side, the ready log's per-key `enforced`/`carried` record (§2) is
evidence, not the authority; the pod proof is the one gate.

Tests: resolver purity golden test (same manifest + observation map →
same resolved value in bb and pod — one representative manifest,
`bin/test-cljs` for the cljc via the pod suite and the bb runner for the
operator side); doctored-envelope → recorded fault; envelope threading
(writer boots with a non-default `maximum-connections`, connection 257 is
refused naming the key — after the pass-through lands); config-free boot
green with retained facts. **The boot proof needs all THREE surfaces —
`bin/test-cljs`, `bin/test-writer`, AND `bin/seon test operator` —
CONFIRMED: the operator-side resolution/selection/argv logic lives in bb
(`script/seon/dev/*.clj`) and is exercised only by the bb operator suite
(`bb.edn:6-8` `operator-test` task, `cli.clj:1056` `test operator`
subcommand), which neither CLJS nor writer gates touch.** Plus the live
gate: one `bin/seon up`/`status`/`down` cycle on the default cluster.

## 7. Work-package cut

| Unit | Content | Depends on |
|---|---|---|
| **W1.1 boot-resolution** (respec of `specs/w1.1-boot-contract.md`) | aero into `bb.edn` deps; extract `seon.config.resolve` (§5); operator resolves at every boot (selection ladder §1, applied-manifest retention); envelope computation + file + descriptor section + `--launch-envelope` argv; thread the NOW keys (§2 table 1: heap render, selected-processors, uds pass-throughs); resolved-manifest handoff file + digest; equality proof + drift check (§6); three-surface tests + live cycle | none (dispatch-ready; `config.cljs` is free post-W1.3a) |
| **W1.5 option surfaces** (= the existing W1 implementation-order step 5, unchanged scope) | injectable frame bytes (uds cljc+cljs peers + protocol-ceiling validation), injectable `executor/capacity` value, codec worker/queue options; flip envelope keys carried→enforced; predicate-specific admission errors per the inventory | W1.1 (consumes the envelope) |
| **W1.2 live writer reconstruction** (NEW unit, owner-ruled) | operator diff of boot-critical subset; admission-quiesce endpoint; ordered stop → relaunch(g+1) → resume; generation fact chain + derived render; live drive proof (cap change enforced, zero agent-loop crashes, listeners resynchronized) | W1.1 hard; W1.5 soft (heap/connections reconstructible before it; frame/executor after) |

W1.1 remains the first W1 step (program ledger q17); W1.2 and W1.5 can
proceed in parallel after it, in separate owners (operator scripts vs
`src/seon/db/*`).

## 8. Open owner decisions (genuine taste only — the two rulings are settled)

1. **Retained applied manifest: resolved VALUE vs PATH.** Recommend the
   post-aero resolved value at `data/clusters/<name>/config/applied.edn`
   (§1): aero runs only at explicit selection, boots are pure, and a
   manifest file edited on disk cannot silently change a cluster that
   never re-applied it. Alternative (retain the path, re-run aero each
   boot) keeps `#env` tags live across boots but makes every boot's limits
   depend on ambient file state — against config-through-DB doctrine.
2. **Heap formula ceiling.** Recommend `clamp(system-mb/16, 512, 4096)`
   (§3). The 4096 ceiling is taste: Datahike writer working sets have not
   been measured above that (NOT GROUNDED beyond the existing 512 MiB/2 GiB
   defaults); raise it later by editing one formula.
3. **FD-derived connection cap.** Recommend including the `ulimit -n`
   observation and the `(quot fd-soft-limit 4)` clamp (§3); if the owner
   prefers zero new observations, the fallback is `max(64, min(1024,
   16*cpu-workers))`, parity-adjacent on current machines. (The ulimit
   observation mechanism in bb is unprobed — NOT GROUNDED.)
4. **Config-free-boot drift handling.** When the retained resolution
   disagrees with retained database facts, recommend FAULT-ONLY with
   steering to `bin/seon config apply` — auto-repair would re-reconcile
   desired state without an explicit selection, exactly what the
   absent-`SEON_CONFIG` rule forbids. Alternative: auto-repair the
   singleton scalar subset only; rejected as a second reconcile scope.
