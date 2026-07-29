---
type: issue
status: resolved
severity: blocker
tags: [issue, docs]
---

# Five skills teach the deleted pod system, and no skill teaches flow

## Problem

Skills are load-bearing agent context (owner ruling 2026-07-29: a skill
that does not match the system design is a HIGH-PRIORITY fix). The
2026-07-29 skills-update lane corrected the four data/schema skills.
Five others were never touched and still teach State A — the CLJS pod
that no longer exists:

- `clojurescript` — "the Seon CLJS pod", `cljs.js` self-host,
  `eval-str`, bootstrap compile-state. **CLJS is OFF** (owner ruling
  2026-07-27) and the pod is deleted. Actively misleading.
- `datastar-web-ui` — "the active pod web UI" on `127.0.0.1:7890`,
  editing `seon.web.*` / `seon.ui.*` (src-old namespaces). The fresh UI
  is CLJ in the cluster JVM on 7994.
- `ui-canvas` — points at `src-old/my/canvas.cljc` as the public API.
- `seon-context-config` — `config/system.edn` + `SEON_CONFIG` at pod
  boot. Config was rebuilt 2026-07-29: `resources/seon/schema/*.edn`,
  one manifest compiler, one `seon.config/apply!`,
  `bin/seon start --config` / `config apply`.
- `browser-automation` — port 7890, pod feeds.

Simultaneously there is **no skill for the architecture agents actually
work in**: `core.async.flow` (procs, workloads, buffers, graph
construction, live update), agents-as-flows, the cluster/store/boot
tower, the render pipeline, and the code-as-facts corpus. That
knowledge lives only in `docs/prds/sci-execution-runtime/research/`
and must be re-derived by every lane.

## Owner

`.agents/skills/` — retire or rewrite the five; author the missing
`seon-flow-architecture` skill from the research corpus, every claim
verified against current source.

## Acceptance

No skill references the pod, `cljs.js`, port 7890, `src-old/`, or
`config/system.edn` as current. A flow/architecture skill exists whose
claims each carry a current-source file:line, and it names the standing
prohibitions (never block `:compute`, never compute on `:io`, `:mixed`
refused at construction, channel contents must be losable).

## Resolution

Resolved 2026-07-29 by re-grounding all six skills in the fresh JVM tree and
adding five progressive-disclosure references under
`.agents/skills/seon-flow-architecture/references/`.

The issue's fixed-port sentence was itself stale: fresh web ports are derived
from cluster names with an ephemeral fallback, and the actual URL/port is
published in the cluster advertisement
(`src/seon/render/web.clj:850-953`; `src/seon/cluster.clj:900-922`).

### Per-skill disposition

| skill | disposition | current boundary taught |
|---|---|---|
| `seon-flow-architecture` | corrected and expanded | current executor ownership, two-proc agents, wakes, faults, evaluation submission, and current-vs-target rendering |
| `clojurescript` | retained and retitled as historical quarry | triggers only for intentional `src-old`/CLJS forensics; current work is redirected to JVM owners |
| `datastar-web-ui` | rewritten | current `seon.render.web` routes, advertised port, snapshot/mult/per-tab-delta pipeline, and tabled UI restoration |
| `browser-automation` | rewritten | advertised cluster URL, current routes, agent-owned tabs, and server-side SSE verification |
| `ui-canvas` | rewritten as a tabled-target guard | no fresh `my.canvas`, controls, `/call`, or agent-owned render proc exists |
| `seon-context-config` | rewritten | schema EDN, complete defaults, sparse overlays, `seon.config/apply!`, database reads, and live-vs-arm-time acquisition |

The CLJS skill was retained rather than deleted because the program explicitly
mines `src-old/` for lessons before deletion. Its self-host and Promise details
remain useful for interpreting old tests and research, but its trigger now
excludes every current runtime/UI/eval task and its body forbids restoration.

The final global sweep also found `datahike/references/querying.md` still
calling two `src-old` examples live and teaching the retired `seon.db` facade.
That adjacent reference now uses current co-located `datahike.api` examples and
labels the two old snippets as quarry.

## Flow-skill verification

Every material claim in `seon-flow-architecture/SKILL.md` was checked against
current source, vendored dependency source, or the named measured research.
The table records corrections as well as confirmations so later audits can
distinguish verified current behavior from ruled target behavior.

| claim | verdict | evidence |
|---|---|---|
| Fresh runtime uses independent flow graphs rather than a central agent scheduler | confirmed | `src/seon/cluster/agent.clj:246-270,426-475`; `src/seon/cluster.clj:638-783` |
| Boot layers store/facts/flow and applies config before launcher/agent/web | corrected to the complete current order | `src/seon/cluster.clj:843-922` |
| One process-root compute/I/O pair is shared by every graph | rejected and corrected | pair at `src/seon/cluster.clj:156-179`; overrides only on launcher at `src/seon/flow.clj:381-425`; ordinary graphs omit overrides |
| Core.async defaults `:io` to virtual-per-task and compute/mixed to cached platform executors | confirmed | `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:71-96` |
| Evaluation tasks use the root I/O executor | rejected and corrected | separate virtual task executor at `src/seon/flow.clj:402-432`; submission at `src/seon/flow.clj:199-277` |
| Var-backed step functions hot-update without topology rebuild | confirmed | `src/seon/flow.clj:83-115` |
| Topology rebuild is about 0.3 ms | confirmed with exact scope/condition | 0.343 ms median three-proc create/start/resume/ping-ready/stop round trip in `flow-dynamic-update-2026-07-27.md` |
| `var-process` refuses missing and `:mixed` workloads | confirmed; “pins” wording corrected to platform-thread occupation | `src/seon/flow.clj:91-100`; `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:243-323` |
| `:compute` automatically splits I/O from computation | rejected | whole-transform submission at `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:243-323` |
| Explicit function workload metadata is lifted into program-graph facts | confirmed with expanded line range | `src/seon/sci/reader.cljc:198-245` |
| Workload reachability over `:seon.fn/calls` exists | rejected; marked **[TARGET]** | no deriving owner in current `src/`; only metadata lift exists |
| Channel loss is safe only for re-derivable or superseded values | confirmed | agent sliding wake at `src/seon/cluster/agent.clj:246-270`; render/stream taps at `src/seon/cluster.clj:638-783`; flow fault taps at `src/seon/flow.clj:635-711` |
| Every current agent graph has mailbox and turn procs | confirmed | `src/seon/cluster/agent.clj:246-270` |
| Parked proc baseline is about 8.5 KB and one virtual thread | confirmed as a measured per-proc baseline, not a universal production heap total | `flow-mechanics-2026-07-28.md` |
| Episode cap is built and database-backed | confirmed | `config/default.edn:67-81`; `src/seon/cluster/work.cljc:365-454` |
| Agent graph has a production `::renders` proc | rejected; marked **[TARGET]** | current graph has two procs at `src/seon/cluster/agent.clj:246-270`; feasibility and unresolved seams in `agent-flow-render-falsification-2026-07-29.md` |
| One listener routes cluster commit wakes | confirmed with current selectivity clarified | `src/seon/cluster/wake.cljc:156-217`; render currently receives every report |
| Listener must neither throw nor park; identical reassertion gives no datom/wake | confirmed | source-grounded probes at `src/seon/cluster/wake.cljc:6-63` |
| Old E/A/V interest machinery is current | rejected; documented only as historical design to reuse | `src-old/seon/db/writer.clj:2756-3205` |
| Core faults fan out to a durable committer; agent mistakes are values | confirmed | `src/seon/flow.clj:593-711`; current dial wiring at `src/seon/cluster.clj:708-747` |
| Panic mode throws/crashes from the recorder | rejected and corrected | current handler commits and prints at `src/seon/cluster.clj:708-747` |
| Current render delivery uses revisioned packages/keyframes | rejected; marked **[TARGET]** | current snapshots/deltas at `src/seon/render/web.clj:229-285,530-608`; target in `render-pipeline-design-2026-07-29.md` |
| 50-tab once+mult is 1.17 ms versus “up to 53 ms per-tab” | corrected | 0.872–1.171 ms p95 versus 31.783–42.479 ms p50 in `render-pipeline-design-2026-07-29.md` |
| http-kit exposes pending bytes and drain/close completion | confirmed | `reference-code/http-kit/src/org/httpkit/server.clj:321-326`; consumer at `src/seon/render/web.clj:502-528` |
| Production evaluation goes through bounded `submit!!` | confirmed | `src/seon/flow.clj:469-497`; `src/seon/cluster/loop.cljc:218-234` |

## Proof

- All six primary `SKILL.md` files remain below 500 lines.
- Each new reference begins with a “read this when…” routing sentence and has a
  table of contents because it exceeds 100 lines.
- Skill frontmatter contains only `name` and `description`.
- Current UI skills name only current routes and discover the port from the
  cluster advertisement.
- Historical pod, `cljs.js`, `src-old/`, `config/system.edn`, and port 7890
  appear only in explicit quarry/prohibition statements, never as current
  instructions.
