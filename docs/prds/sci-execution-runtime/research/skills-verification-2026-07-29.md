---
type: research
status: complete
tags: [research, audit, docs]
---

# Independent skills verification — 2026-07-29

## Verdict

The skill corpus was not trustworthy as received. It contained current-source
contradictions, stale line ranges, unconditioned measurements, a broken
command, an over-broad trigger, and executable examples that had not been run.
The most dangerous defects were:

- Datahike was described as one writer per physical store even though current
  Seon deliberately opens one branch connection per cluster in one locked
  physical store.
- The querying reference denied the maintained fork's implemented
  `:order-by`/`:limit` query-map support and told callers to control a planner
  decision by clause order.
- The Clojure testing and data-oriented skills repeated old timings without
  their benchmark conditions.
- The REPL skill blurred the agent-reply reader with JVM REPL behavior and its
  first corrected probe called a live-instrumented arity that currently fails.
- The flow skill quoted the topology rebuild number without its readiness
  boundary: ping proved responsive procs, while `stop` returning did not prove
  an exit join.

Those claims were corrected or deleted. The resulting corpus is substantially
safer, but `browser-automation` remains conditionally trusted: no browser was
available to verify layout, focus retention, or console state. I would stake an
agent's context on every other skill at the source revision recorded below. I
would not use this pass as evidence for browser-only behavior.

## Verification boundary

| Item | Verified revision or condition |
|---|---|
| Seon | moving shared tree; source claims rechecked after `811ec4356` and again before the verification commit |
| Datahike fork | `19f5cdd950dc3c5ad2c8777a176d2ec4cb18c0bb` |
| core.async | `dc35f3e0d7bc2eef502e77982f48641f025c8051` |
| SCI | `8fac6e88f32d53a5fd82ebe80640881e317b84fd` |
| http-kit | `238a85cc555a38892f2f9a7583c9cf5cec0fb201` |
| JVM used for probes | OpenJDK 26.0.1 |
| Live system | lane-owned operator root, cluster `skills-verification-20260729`, JVM pid 3473, prepl 53717, web `127.0.0.1:7702`; later cross-root discovery proved the root was not an isolation boundary |
| Protected tree | `src/` and `test/` read only; no protected file was edited |

The original owner JVM was not restarted, reset, or stopped. A temporary
advertisement link made the lane-owned cluster discoverable to the repository
MCP bridge. During the pass, the owner found that a project-root `start
default` selected this verification JVM; that defect is recorded in
`docs/seon/issues/operator-start-discovers-jvms-from-other-roots.md`. A
`default` cluster subsequently appeared under the verification root and pid.
Cleanup stopped both advertised clusters through the verification root and
then reaped pid 3473. Nothing was restarted after that boundary.

## Executed evidence

| Taught operation | Result |
|---|---|
| `bin/seon-fresh status` | passed against the lane-owned operator |
| `bin/seon status` | initially failed in the old Babashka path; after `c073093e2`/`d0281f935` landed concurrently, it and `bin/seon-fresh status` both passed with identical rows |
| `bin/seon-fresh open skills-verification-20260729` | passed; opened the advertised dynamic URL |
| `bin/seon-fresh logs skills-verification-20260729` | passed; log showed readiness and 359 instrumented vars |
| `bin/seon start skills-verification-20260729` | passed from an isolated operator root and booted a current-source JVM |
| `bin/seon config apply ... empty-overlay.edn` | passed; converged with zero operations |
| `clojure -M:dev` load/read/schema probe | passed; reader and schema bridge loaded, a form read, and run schema derived |
| MCP `runtime_status` and `eval_clj` | selected the scratch cluster and returned complete prepl envelopes |
| `seon.sci.reader/read` with `#=` | returned the flat `:seon.sci.reader/refused-tag` value |
| `seon.sci.reader/read` with an unbalanced form | returned the flat `:seon.sci.reader/unreadable` value with position |
| `(seon.cluster.reply/sources text 'user)` | passed; confirmed structured forms, prose comments, and the trailing-standalone-symbol rule |
| one-arity `seon.cluster.reply/sources` | failed under live instrumentation because it delegates with nil where the schema requires a namespace symbol; the skill now teaches the working two-arity probe |
| Datahike query-map `:order-by '[?score :desc] :limit 2` | passed and returned `[["b" 5] ["c" 3]]` |
| first drafted replacement `:order-by '[[?score :desc]]` | failed as invalid; corrected before landing |
| `bin/test seon.schema.datahike-test seon.test-support-test seon.cluster.reply-test` | 18 tests, 53 assertions, green |
| `bin/test seon.datahike-fork-test` | 1 test, 1 assertion, green |
| Datahike `bb kaocha --focus datahike.test.query-planner-test` | 96 tests, 396 assertions, green |
| `npm run css:build` | passed |
| `npm run css:watch` | started, performed its initial build, and was interrupted intentionally |
| Browser plugin | unavailable: browser inventory was empty |
| GET `/`, `/agent/root`, `/data` | all returned 200 from the scratch JVM |
| GET `/feed/root` plus POST `/agent/root/message` | POST returned 204; SSE emitted initial and changed Datastar fragments; the client ended only at its deliberate 15-second bound |

The message submission also invoked the configured model because credentials
were present. That was not repeated. No claim about visual layout, focus, or
browser console state was made from the server-side fallback.

## Per-skill claim audit

### `browser-automation`

| Claim | Verdict | Evidence | Action |
|---|---|---|---|
| URL must come from the advertisement; ports are dynamic | verified | scratch advertised 7702; `src/seon/cluster.clj` advertisement owner and `src/seon/render/web.clj` bind path | retained |
| Current live routes are `/`, `/agent/{id}`, `/data`, `/feed/{id}`, and message POST | verified | route branches in `src/seon/render/web.clj:734-840`; direct HTTP results above | retained |
| Server-side client is the SSE fallback | verified | curl held `/feed/root`, observed Datastar fragments before and after POST | retained |
| Browser checks cover layout, focus, and console | not verified in this environment | browser inventory was empty | retained as workflow, explicitly excluded from this pass's trust claim |
| Trigger loads for live browser verification, not renderer implementation | verified | compared with `datastar-web-ui` trigger | no change |

Trust: **conditional**. Safe for route discovery and fallback procedure; not
independently proven here for browser-only assertions.

### `clojure-testing`

| Claim | Verdict | Evidence | Action |
|---|---|---|---|
| `bin/test` discovers `_test.clj[c]`, accepts explicit namespaces, and runs one JVM | verified | `bin/test:1-89`; focused commands executed | added direct citation and removed timing folklore |
| `with-database` creates a fresh memory database, populates it, then releases and deletes it | verified | `test/seon/test_support.clj:151-183` | added direct citation |
| Root and maintained-fork gates are separate | verified | both commands executed; 1/1 root and 96/396 fork | retained |
| Every generative trial needs a new connection | false as a universal claim | immutable planner property reuses one `db/empty-db`; mutating trials require isolation | retained the already-corrected mutating/pure split |
| Six refusal tests print about 15,700 lines; JVM/Datahike timings | unverifiable under stated conditions and stale | old research recorded an earlier tree but not a complete reusable condition set | deleted |
| Trigger is testing-specific and does not capture ordinary database work | verified | frontmatter boundary against `datahike` and `data-modeling` | no change |

Trust: **high** after deletion of unconditioned measurements.

### `clojurescript`

| Claim | Verdict | Evidence | Action |
|---|---|---|---|
| Fresh Seon is CLJ-only and contains no current `.cljs` runtime | verified | no `.cljs` under fresh `src/`; plan ruling 2026-07-27 | retained |
| Deleted self-host/pod code is quarry only | verified | implementations exist under `src-old/`, not fresh `src/` | retained |
| Historical async/await mechanisms existed | verified | cited analyzer/compiler/core sources and archived research | retained |
| Trigger must not load for current runtime, UI, eval, or async work | verified | adjacent current owners are `repl`, `datastar-web-ui`, and flow | no change |

Trust: **high**. Its narrow negative trigger is important and accurate.

### `data-modeling`

| Claim | Verdict | Evidence | Action |
|---|---|---|---|
| Malli shapes derive Datahike type/cardinality/unique/component facets | verified | `src/seon/schema/datahike.cljc:36-48,149-205`; load-only probe | retained |
| `[:maybe X]` stored attrs refuse; many values are cardinality-many sets | verified | bridge source and maintained Datahike persistent-set owner | retained |
| Entity schema projection derives identity enumeration without a kind stamp | verified | `src/seon/schema.cljc:1149-1209` | retained |
| Provenance refs are the only scope mechanism | false | ownership is a separate domain-ref axis, e.g. run → agent | split scope into provenance and ownership |
| Config composites derive from leaf declarations | verified | `seon.schema.edn/derive-config-forms` and config source | retained |
| Trigger owns shape design, not query/transact mechanics | verified | frontmatter boundary against `datahike` | no change |

Trust: **high** after the scope correction.

### `data-oriented-clojure`

| Claim | Verdict | Evidence | Action |
|---|---|---|---|
| `clojure -M:test -e` gives an in-memory Datahike database | false | alias supplies classpath only; database creation is explicit | changed to `clojure -M:dev` for load-only and explicit memory fixture for DB behavior |
| A named grounding document has a line citation for every following claim | false | no such document was named | deleted |
| Datahike DB equality is unsafe as a memoize key | verified | `reference-code/datahike/src/datahike/db.cljc:703-715`; archived primer §5 | retained with current-source citation |
| Memory reads are universally sub-millisecond | unverifiable without conditions | no current complete benchmark attached | deleted |
| A prior drive produced exactly 494 inline assertions | unverifiable and nonessential | no condition-complete evidence | deleted |
| Unordered sets/maps must not break tied decisions | verified | maintained Datahike planner defect and repair at `19f5cdd9` | retained |
| Trigger applies to Seon Clojure and Seon-owned vendored forks, not unrelated Clojure | verified | frontmatter explicitly states boundary | no change |

Trust: **high** for design guidance after removal of anecdotal numbers.

### `datahike`

| Claim | Verdict | Evidence | Action |
|---|---|---|---|
| Seon permits one live writer connection per physical store | false | current process owns one physical store and one connection per branch; connection id is `[store-id branch]` | corrected topology and cited both Seon and Datahike owners |
| Datahike lacks query-map ordering/limit | false | `query.cljc:98-121,3475-3505`; executable query | replaced with the maintained query-map form |
| The replacement nested order vector is valid | false on first execution | maintained fork rejected `[[?score :desc]]` | corrected to `'[?score :desc]` and reran |
| Caller clause order controls scan choice | false | maintained planner orders operations in `query/plan.cljc:1524-1663` | changed to provide selective facts and let the planner order |
| Reverse-ref pull is “free” | unverified performance claim | current pull implementation/tests prove support, not cost | changed “free” to “supported” with citations |
| Current-db queries carry no history overhead | unverifiable broad performance claim | no condition-complete measurement | deleted |
| Fork-maintenance private-var, cache-evidence, reload, and dual-gate forms | verified | current fork sources; both gates executed | retained |
| Trigger owns Datahike mechanics and not general modeling rationale | verified | frontmatter and hand-off section | no change |

Trust: **high** at fork `19f5cdd9`. This skill had the largest factual repair.

### `datastar-web-ui`

| Claim | Verdict | Evidence | Action |
|---|---|---|---|
| Current UI is JVM `seon.render.web`, not the deleted pod | verified | current namespace and live pages | retained |
| Routes and message submission exist now | verified | source branches plus live GET/POST/SSE proof | retained |
| Current pipeline snapshots, mults, and emits changed blocks | verified | `src/seon/render/web.clj` and observed SSE fragments | retained |
| CSS commands work | verified | build passed; watch started and built | retained |
| General canvas/control restoration is tabled | verified | plan ruling and absence of fresh `my.canvas`/`/call` route | retained |
| Trigger owns renderer/routes/SSE; agent-authored controls go to `ui-canvas` | verified | adjacent trigger comparison | no change |

Trust: **high** for server behavior; visual browser behavior retains the same
environmental limitation as `browser-automation`.

### `repl`

| Claim | Verdict | Evidence | Action |
|---|---|---|---|
| Agent replies, io-prepl/MCP, and raw JVM REPL share one repair reader | false in the pre-pass version | current reply, SCI reader, Clojure io-prepl, and main sources are distinct | retained the landed three-surface rewrite |
| Agent reply reader repairs missing delimiters/parinfer-style | false | current reader returns flat unreadable values | retained deletion of repair guidance |
| Bare standalone symbol may be code only alongside structured code | verified | live two-arity `reply/sources` probe | clarified that trailing human-looking text can classify as code |
| One-arity `reply/sources` is a safe fast diagnosis form | false under live instrumentation | one-arity delegates nil; schema requires namespace symbol | teach explicit two-arity form |
| Private Var invocation and reload forms work | verified | maintained planner probe and Clojure `require :reload` owner | retained |
| File ranges after repeated source movement | stale | reply ended at line 348, not 354 | corrected every range |
| Trigger should load for reply parsing and live/private/reload probes, not generic syntax | previous trigger too narrow | skill itself teaches live probe behavior | expanded trigger without capturing ordinary REPL use |

Trust: **high** for the taught two-arity probe. The one-arity implementation
defect remains a protected-source issue, not skill guidance.

### `seon-context-config`

| Claim | Verdict | Evidence | Action |
|---|---|---|---|
| Config is typed database state derived from EDN leaves | verified | `src/seon/config.cljc`, `src/seon/schema/edn.clj`, config schemas | retained |
| Sparse apply converges and a repeated empty overlay is a no-op | verified | isolated live `config apply` returned converged/zero operations | retained |
| `start --config` and `config apply` grammar | verified | operator source and isolated command | retained; corrected moved wrapper range |
| Old `config/system.edn`, `SEON_CONFIG`, routes, context blocks, and skill corpus are not current config | verified | absent current owners; present only in quarry/history | retained |
| Trigger captures config changes and stale old-config instructions, not general data modeling | verified | frontmatter comparison | no change |

Trust: **high**.

### `seon-flow-architecture`

| Claim | Verdict | Evidence | Action |
|---|---|---|---|
| `var-process` requires a Var and explicit `:io`/`:compute` | verified | `src/seon/flow.clj:83-115` | retained |
| Topology rebuild median is 0.343 ms | number verified, conditions incomplete in skill | 50 samples after 5 warm-ups, JDK 26.0.1, 18 processors, three-proc ping-ready API round trip; stop was not exit-joined | added all decisive conditions and caveat |
| Parked proc baseline is about 8.5 KB | number verified, conditions incomplete in skill/reference | fresh-JVM sections, 18-core Mac, JDK 26, `-Xmx512m`, steady 1,000 one-proc case | added conditions; explicitly not a production-agent heap |
| Render-proc feasibility numbers | verified with conditions | 100 in-memory agents, JDK 26/G1/512m, two warm-ups, GC/settle/park method | added conditions and full +7.3–9.2 KB range |
| Render pipeline numbers | verified with conditions | two runs, JDK 26.0.1/18 processors, Chrome 150, Datastar RC7; browser excluded transport | added conditions in main and reference |
| Scheduling probe labels and elapsed table | false/incomplete | research used 72 tasks each blocking 100 ms; the skill called 100 the task count and gave elapsed values without a repeat count | corrected variables and deleted exact elapsed values; retained the four-wave/one-wave result |
| 800 ms listener caused 804 ms transaction | number existed but reusable conditions were incomplete | source proves listeners run before delivery | deleted number; retained source-derived critical-path rule |
| Workload reachability, `::renders`, packages/keyframes are current | false | absent current owners | retained/strengthened `[TARGET]` labels |
| Degraded-start status command | broken at start, repaired concurrently | initially failed; both entry points passed after `c073093e2` | current skill teaches repaired `bin/seon status` and identifies alias |
| Alternate operator root isolates JVM discovery | false | project-root start selected verification pid 3473; open blocker `operator-start-discovers-jvms-from-other-roots.md` | deleted the guarantee; require pid/store-path confirmation and stop on a foreign selection |
| Trigger captures any runtime machinery design, not ordinary DB/UI work | verified | frontmatter hand-offs and adjacent skill comparison | no change |

Trust: **high** after condition-complete measurement edits. Recheck after any
topology change because this skill has the widest runtime blast radius.

### `ui-canvas`

| Claim | Verdict | Evidence | Action |
|---|---|---|---|
| No fresh `my.canvas` API exists | verified | no fresh namespace/symbol owner; old implementation only in `src-old/` |
| No current generalized `/call` action route exists | verified | current web route dispatch; a CSS/data attribute mention is not a route |
| Current message POST and Datastar pages are narrower built UI | verified | current source and live proof |
| `::renders`, generalized controls, guarded action boundary, packages are target work | verified unbuilt | current graph has mailbox/turn only; no action route |
| Trigger loads for every button/input/form task | over-broad | it captured maintenance of the existing message form owned by `datastar-web-ui` | narrowed to agent-authored generalized canvas/control requests |

Trust: **high** as a stop sign against inventing APIs.

## Remaining defects outside the authorized edit boundary

- Live instrumentation rejects the one-arity
  `seon.cluster.reply/sources` convenience call because its nil namespace does
  not satisfy the function schema. The skill no longer teaches that arity.
- Operator JVM discovery crosses operator roots, so a lane-owned root does not
  currently guarantee process isolation. The flow and config skills now name
  the blocker and require pid/store-path confirmation.
- A browser was unavailable, so layout, focus retention, and console cleanliness
  remain unproven by this pass.

Neither defect was repaired because `src/` and `test/` were protected.

## Final trust summary

| Skill | Trust after this pass | Would stake agent context? |
|---|---|---|
| browser-automation | conditional | no, not for browser-only behavior |
| clojure-testing | high | yes |
| clojurescript | high | yes |
| data-modeling | high | yes |
| data-oriented-clojure | high | yes |
| datahike | high at pinned fork | yes |
| datastar-web-ui | high for server behavior | yes, with browser limitation |
| repl | high with explicit namespace probe | yes |
| seon-context-config | high | yes |
| seon-flow-architecture | high, widest blast radius | yes |
| ui-canvas | high | yes |

There is no separate live-REPL skill. The current `repl` skill now intentionally
covers the live/private/reload probe boundary while excluding generic Clojure
syntax and ordinary application code.
