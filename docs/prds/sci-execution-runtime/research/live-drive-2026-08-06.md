---
type: research
status: complete
tags: [research, live-drive, agent, context, rendering, database]
---

# Default-cluster live drive — 2026-08-06

## Verdict

The drive stopped at the first real turn because the freshly reset root agent
was not capable of completing it. The public message boundary worked and a
real DeepSeek call returned a plan, but the plan was attached to the wrong
triggering run and the run then remained open before its first evaluation
receipt. At the final read-only basis, the live-drive message was still
unclaimed.

This was the explicit stop condition in the owner's assignment: a foreign
in-flight run blocked the gate, so the drive did not resume, complete, message,
reset, stop, or refork that run. The remaining work was read-only evidence
collection. Consequently the contracted-function, schema/database,
multi-agent, and deliberate-error phases are **not tested**, not failed, and
not silently counted as covered.

The live system also exposed independent shipping defects in the problems
projection, transcript renderer, `/data`, namespace debug page, maintenance
schema installation, and the agent's defs attribution. Seven issue notes were filed
or updated in the ranked index; every other finding below maps to an existing
open note.

## Scope and method

Target:

- cluster `default`;
- PID `52509`, start instant `2026-08-06T17:21:54Z`;
- prepl `127.0.0.1:51998`;
- web `http://127.0.0.1:7994`;
- source head `797423af3`;
- baseline database basis `536871002`, commit
  `6a74c387-ea53-5416-aeb4-889f5bffc91b`.

I read `src/seon/render/route.clj`, `src/seon/cluster/message.clj`,
`src/seon/eval/drive.clj`, and `src/seon/bootstrap_drive.clj` end to end before
choosing the entry. The honest driver-seat entry was the one public route,
`POST /agent/{id}/message`: it preflights and commits the same
`message/inbound-tx` function under `:db.fn/call`, with process and root-user
transaction metadata. JVM probes used `mcp__seon__eval_clj`; database waits
and inspections were read-only.

The browser runtime had no connected browser (`No browser is available`,
availability list `[]`). Per its control instructions, no alternative browser
automation was substituted. Route status, response bytes, aliases, and HTML
structure were therefore inspected over the real HTTP server; CSS layout,
screenshots, and browser console state remain explicitly unproven.

## Session narrative

### Baseline

The initial database contained one agent (`root`, namespace
`my.agents.root`), four runs, nine messages, three context captures, and two
attempts. `bin/seon status` found the requested live PID and URL, but also
printed eight full-path invalid-external-claim warnings. The MCP health call
did not return health at all: `seon.problems/problems` violated its output
contract.

### The public message committed correctly

At `2026-08-06T17:25:50.238Z`, the HTTP boundary committed:

```text
LIVE-DRIVE-0806-A. Inspect the message that woke you and your current run
using ordinary database facts. Complete this run with exactly the value
{:live-drive/phase :opened :live-drive/agent "root"}. Do not create functions
or agents yet.
```

The response was HTTP 204 in 61 ms. The committed message is
`inbound-536871002-0`, entity id `23679`; its transaction entity is
`536871003` and carries both `:seon.db/process` and root's `:seon.db/user`
(agent eid `23594`). This is a clean pass for public submission, admission,
transaction metadata, and wake-fact creation.

### The message entered the wrong run

No run claimed `inbound-536871002-0`. Instead, at
`2026-08-06T17:26:19.882Z`, root opened run
`f56667dc-a2ec-4f92-af47-e37cdb06535c` (eid `23687`) on the earlier trigger
`d05eafc5-35f3-4555-b131-0a80b5d06cc4-your-run`. That trigger says the
scheduled `:step` failed with `:seon.schedule/settlement-refused`.

The exact prompt capture at basis `536871009` nevertheless included the newer
live-drive message. DeepSeek acted on it and froze these two exact sources:

```clojure
;; Inspect the wake message and current run using ordinary database facts.
(let [db (seon.db/db)
      run-eid (ffirst (seon.db/q '[:find ?e :where [?e :seon.cluster.run/id "f56667dc-a2ec-4f92-af47-e37cdb06535c"]]))]
  {:current-run (seon.db/pull db '[*] run-eid)
   :wake-message (seon.db/pull db '[*] 23679)})
```

```clojure
;; Complete with the exact requested value.
(my.run/complete "{:live-drive/phase :opened :live-drive/agent \"root\"}")
```

The second form also changed the requested map into a string containing EDN.
That is a model mistake, not a runtime verdict, but it is calibrated evidence
that the current 44,306-token prompt does not yield reliable instruction
fidelity.

Attempt `f56667dc-a2ec-4f92-af47-e37cdb06535c-attempt-0` used
`deepseek-v4-flash`, finished with `stop`, and recorded:

- latency: 89,726 ms;
- prompt: 44,306 tokens;
- completion: 7,329 tokens (7,179 reasoning);
- total: 51,635 tokens;
- prompt cache hit: 17,792 tokens;
- prompt cache miss: 26,514 tokens; and
- no HTTP 402 and no retry.

### The frozen plan never began

The run acquired a plan digest but produced no evaluation receipt. A direct
work derivation returned `:resume` at form ordinal zero. At final basis
`536871034`, the run was still open with zero receipts and the live-drive
message still had no triggering run. No cause was assigned beyond that
observed transition boundary; doing so without a proc/event probe would be
guessing.

This closes the authorized mutation phase of the drive.

## Exact rendered context — two captures

These are the durable `:seon.context.capture/prompt` strings committed before
the provider call, not later rerenders. Source grounding confirms
`seon.ai/request-body` puts the same string in the provider's user-role
content. I inspected exact byte ranges at both ends of each string and ran the
full strings through read-only byte/line/occurrence reductions.

| Capture | Run | Basis | Chars | Lines | Provider prompt tokens | Largest line |
|---|---|---:|---:|---:|---:|---:|
| `f9a0547f-761a-427a-84e1-d81f2764aff7-context-536871000` | `f9a0547f-761a-427a-84e1-d81f2764aff7` | 536871000 | 103,563 | 943 | 34,237 | 2,725 |
| `f56667dc-a2ec-4f92-af47-e37cdb06535c-context-536871009` | `f56667dc-a2ec-4f92-af47-e37cdb06535c` | 536871009 | 135,272 | 1,065 | 44,306 | 9,308 |

Both exact strings begin with:

```text
;; (seon.render/walk {:root [:seon.cluster.agent/id "root"], :depth 2}) ...
;; d0 · :seon.instrument/contract-violated
Renderer unavailable.
```

Both then teach the stale string-only form
`(my.message/send "agent-id" "message")`, state that prose is kept as `;;`
comments, and dump the `my.background` namespace plus hundreds of schema and
registration lines.

Full-string census:

| Signal | First capture | Second capture |
|---|---:|---:|
| `;;` comment lines | 115 | 132 |
| `; schema` lines | 215 | 223 |
| `; (register!` lines | 86 | 126 |
| `:seon.config.*` occurrences | 168 | 484 |
| complete config-literal endings | — | 6 |
| maintenance request ids | — | 10 |
| identical `pull-many` renderer failures | — | 5 |
| false unrestorable rows for the agent's defs | 0 | 3 |

The second capture places the live-drive message between an entire successful
DeepSeek attempt/config face and four false rows for the agent's defs. It also repeats the
same `seon.db/pull-many` contract error five times. Its tail is mostly unit
ids/branch paths, ending with the volatile basis/time line.

The problems are not fake optimization targets. The context currently begins
with a broken renderer, spends tens of thousands of paid tokens on raw program
and config facts, repeats identical errors, teaches pre-wave messaging prose,
and misattributes system Vars to the agent.

## Requested web surfaces

| Route | Result | Evidence |
|---|---|---|
| `/` | 200 | 518,673 bytes in 2.3 ms in the isolated curl pass |
| `/agent/root` | 200 | 518,673 bytes in 2.5 ms |
| `/ns/my.agents.root` | 200 | 518,673 bytes in 2.6 ms |
| `/ns/my.agents.root/debug` | no first byte | zero bytes before the 5.003 s cutoff; an earlier combined JVM probe exceeded 30 s |
| `/data` | 500 | 136-byte contract error in 41 ms |

The three successful aliases had the identical SHA-256
`374a5cb2da5d4f6bca73e63efcccf1be8ebc6bfdc1159de91ea5cfc70a5496c9`
in the same sample. Alias routing is therefore calibrated as working.

The root HTML is structurally extreme: a later read counted 518,919
characters, 233 `<article>` elements, 20 `<pre>` elements, 14 tables, 171 raw
`:db/id` strings, one renderer-unavailable unit, and fourteen copies of the
`pull-many` contract error. The fixed message form and agent/debug links were
present.

`/data` returned exactly:

```text
seon.sci.kernel/context-projection violated its contract (invalid-input):
[[{:value nil, :message "must be an SCI evaluation context"}]]
```

Without a browser, no claim is made about typography, viewport overflow,
caret behavior, Datastar console errors, or screenshot appearance.

## Ranked inventory

### Broken

1. **A successful plan freezes before receipt zero and blocks the root queue.**
   New blocker:
   [Settle or refuse a frozen plan's first form](docs/seon/issues/run-freezes-before-first-receipt-after-plan-freeze.md).
2. **An unclaimed human message enters an unrelated trigger's prompt and owns
   the model's plan semantically but not causally.** New blocker:
   [Keep an unclaimed message out of an unrelated run's prompt](docs/seon/issues/unclaimed-message-enters-an-unrelated-run-prompt.md).
3. **Fresh scheduled maintenance cannot settle.** The four-million-character
   error tree reduces to one real refusal: `:seon.operator.log/path` is not
   installed. Existing blocker:
   [Install maintenance result attributes on a fresh cluster](docs/seon/issues/fresh-maintenance-result-attributes-are-not-installed.md).
4. **The problems projection breaks both health and root rendering.** New
   blocker:
   [Keep committed error facts valid in the problems projection](docs/seon/issues/problems-projection-breaks-health-and-root-render.md).
5. **Transcript rendering passes a set to `pull-many`, producing a core-fault
   message loop.** New blocker:
   [Pass ordered entity ids to transcript `pull-many`](docs/seon/issues/transcript-about-lookup-passes-a-set-to-pull-many.md).
6. **`/data` is a deterministic 500 because its unit omits the SCI context.**
   New blocker:
   [Supply the live SCI context to the data page renderer](docs/seon/issues/data-page-omits-the-live-sci-context.md).
7. **The debug page produces no first byte within five seconds.** New
   friction issue:
   [Return the namespace debug page without blocking the response](docs/seon/issues/debug-page-blocks-before-first-byte.md).
8. **A prior real provider call returned HTTP 200/output-observed but its body
   was unreadable JSON (`closed`), so nothing ran.** The provider-integrity
   acceptance and review live in
   [Refuse malformed SSE data before it can change agent code](docs/seon/issues/malformed-sse-data-can-change-agent-code.md).

### Dishonest

9. **System runtime atoms are committed and rendered as root's agent-authored
   agent defs.** Eids `23683`–`23686` name `held-flocks`, `running-instances`,
   `root-store-holder`, and `source-analysis-cache`. New blocker:
   [Keep newly loaded system Vars out of the agent's defs](docs/seon/issues/agent-desk-captures-newly-loaded-system-vars.md).
10. **The bootstrap context teaches stale messaging and comment semantics.**
    Existing owner:
    [Make production docstrings describe the surviving runtime](docs/seon/issues/production-docstrings-teach-deleted-semantics.md),
    plus the strict-display owners
    [Render the walk as ordinary REPL values](docs/seon/issues/render-walk-frames-values-as-comments.md)
    and
    [Render run forms and receipts with strict REPL fidelity](docs/seon/issues/run-renderer-narrates-forms-and-receipts.md).
11. **The debug page is not grounded in the exact captured prompt even if it
    eventually responds.** Existing issue:
    [Make the debug left pane the exact bytes the agent received](docs/seon/issues/debug-left-pane-is-not-the-exact-prompt.md).

### Ugly

12. **The exact prompts are 103 KB/135 KB and 34k/44k provider tokens.** They
    carry hundreds of schema/config lines and repeated full config faces.
    Existing owners:
    [Give render token budgets one declared owner](docs/seon/issues/render-token-budgets-are-private-dials-no-producer-supplies.md)
    and
    [Give cluster, config, and bootstrap plan named concise producers](docs/seon/issues/cluster-config-and-bootstrap-plan-render-as-raw-maps.md).
13. **The root namespace page is about 519 KB with 233 articles and 171 raw
    database ids.** The same two render issues own the recurring size and raw
    configuration/program faces; the fourteen repeated errors are owned by
    the new transcript issue above.
14. **One maintenance error's `:seon.error/data-edn` is 4,010,918 characters
    of nested `#:seon.print` nodes.** Existing issue:
    [Keep contract-violation evidence as data](docs/seon/issues/contract-violation-serializes-print-tree-inside-error-data.md).
15. **`bin/seon status` prints eight unreadable full-path external-claim
    warnings on every healthy-process check.** Existing issue:
    [Quiet the unreadable-external-claim flood](docs/seon/issues/status-floods-unreadable-external-claim-warnings.md).

### Friction and blocked coverage

16. **The live drive cannot honestly reach the remaining arc while the root
    run owns custody.** Contracted function publication/next-turn call,
    runtime schema registration/transact/query, second-agent messaging/wait,
    and deliberate bad-arity/unresolved-symbol faces remain untested. If those
    later serial probes fail, the existing issue audit found no generic note
    broad enough to absorb them; they require evidence-specific notes rather
    than being pre-filed from speculation.
17. **Visual QA is incomplete because no controllable browser was connected.**
    This is a drive-environment limitation, not a Seon issue; the HTTP and HTML
    evidence above is complete within that boundary.

## What worked well

- The route table's public inbound path accepted the real human message, used
  the writer-basis identity, stamped honest user/process transaction metadata,
  and returned 204 without painting.
- Exact context capture exists before provider transmission and remained
  queryable by run, capture id, basis, transaction, and byte content.
- DeepSeek returned a parseable two-form plan with durable attempt usage and
  cache accounting. No 402 or retry occurred.
- `/`, `/agent/root`, and `/ns/my.agents.root` were byte-identical aliases and
  included the fixed message form and canonical debug link.
- The database made the stop diagnosis possible without touching process
  memory: trigger, run, plan forms, attempt, missing receipts, messages,
  captures, errors, and rows for the agent's defs were all queryable facts.
- The issue authority already contained the exact fresh-maintenance schema
  defect and several render/output owners, preventing duplicate issue notes.

## Arc coverage

| Requested probe | Outcome |
|---|---|
| Real root task, run, turns, reply | POST and DeepSeek plan observed; no evaluated form or reply; blocking defect filed |
| Contracted function, next-turn call | not reached after required stop |
| Schema declaration, transact, query | not reached after required stop |
| Second agent, send/wait/complete | not reached after required stop |
| Honest bad arity/unresolved symbol | not reached after required stop |
| Exact rendered context twice | completed from two durable captures |
| `/`, agent alias, namespace, debug, `/data` | all loaded over HTTP; three 200, debug no first byte, data 500; browser-only checks unproven |

## Issue-index proof

The drive added six blocker notes and one friction note, then updated
`docs/seon/issues/index.md`. `bin/issues-index --check` passed with 133 open
and 957 archived notes.
