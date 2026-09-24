---
type: research
status: current
created: 2026-09-24
scope: The one pipeline that renders an LLM agent's prompt, its braids and printers, measured on a live capture, with a layered REPL-transcript design.
---
# The agent's prompt, from the agent's side: one pipeline, braided

Research only (implementation frozen). Tree `295df8e3b`. Live evidence: the
captured provider prompt of turn `c0a2a5f1cd8e` (agent `root`, cluster
`default`), read in JVM mode via
`(seon.db/q '[:find ?text . :where [?r :seon.turn/id "c0a2a5f1cd8e"] [?c :seon.context.capture/run ?r] [?c :seon.context.capture/prompt ?text]] db)`.
Timings of prompt derivation were not measured here (that needs a turn
or a prompt preview, which this read-only pass did not run). That gap stays open.

## 0. Headline findings (broken things first)

1. **81% of the live prompt is a single generated query, reprinted five times.** The
   capture is 680,643 chars across 175 evaluation units. Five units are 109–110 KB
   each, 549 KB in total. Each is the steward fault read `render-faults-ai`
   (`src/seon/error.clj:1808-1830`), whose pull selector is built from the *whole
   schema projection* (`observation-selector`, `:limit nil` on every attribute:
   8,972 occurrences of `:limit nil`). A system refresh reprints the full source
   every time the read changes (`;; changed since your last turn`, 134 times).
   Its value is `[]` or `["Error evidence is unavailable. Occurrences: 7."]`.
2. **The context does not end with a prompt.** The last bytes are
   `turns left: 74 of 100`, the frame that `seon.repl/frame` appends
   (`src/seon/cluster/prompt.clj:396-399`; `src/seon/repl.clj:50-76`). The agent never
   sees `my.agents.root=> ` as the place where it types.
3. **Results are not printed the way a REPL prints them.** Ordinary values come
   back wrapped as `#:seon.repl{:value … :result result/e… :ms 6}`, a string-assembled
   map (`seon.repl/response`, `src/seon/repl.clj:213-229`). Errors appear as an
   *escaped string* inside that map (`:error "Execution error …\nSCI program …"`).
   Declared-renderer results print bare prose. Refreshed reads put a
   `:seon.repl/changes` diff in the `:value` slot, which the form never returned.
   That makes four response shapes under one prompt.
4. **`(quote x)` leaks into values.** The value printer (`seon.print` node emitter,
   `src/seon/print.cljc:856-891`) has no reader-macro case. A quoted form prints as
   `(seon.db/q (quote [:find … (quote my.agents.root/largest) …]))`.
   Sources go through `pprint` code-dispatch (`seon.repl/source-text`,
   `src/seon/repl.clj:91-97`), which does print `'x`. So the same datum looks different
   depending on which printer drew it. `render-default-ai-source` uses bare `pr-str`
   on `(list 'quote '[*])` (`src/seon/render.clj:1368-1376`), a third printer.
5. **System-written evaluations speak in the agent's voice.** Opening comments say
   "I should know my identity…" and "I should read my saved notes…"
   (`src/seon/cluster/agent.clj:229,263`; `src/seon/note.clj:63`; `src/seon/plan.clj:1544`).
   The faults opener uses `; ` where every other comment uses `;; `. The agent then
   imitated the grammar it was shown: in this capture it quoted a whole form
   (`'(seon.db/q …)`) and got the form back as data.
6. **There are two help sources, and they disagree.** `(help)` is a hard-coded vector of 13 prose
   lines inside `seon.bootstrap/help-value` (`src/seon/bootstrap.clj:22-80`).
   It does render first. A second, seeded instruction entity,
   `:getting-started` (`src/seon/cluster/instruction.clj:12-29`), claims
   "Everything above is the output of `(seon.render/walk)`". It appears **zero** times
   in the prompt. Help order is vector order, and help comes first only because
   `render-identity-ai` string-concatenates `(help)` in front of the identity read
   (`src/seon/cluster/agent.clj:259-283`).

## 1. The one pipeline today, end to end

```
system turn (opening + refreshed reads)          agent turn
  walk/neighborhood from [:seon.agent/id a]         model reply text
  └ per-entity :seon.render/ai producer             └ seon.cluster.reply/sources  (reply.clj:301)
     returns a SOURCE STRING (comment+form)            strips "ns=> ", prose→:comment
  └ turn/declared-sources re-READS that string         exact source bytes kept
     into forms (turn.clj:1852-1905)                 │
  └ turn/system-turn evaluates, diffs vs latest      │
     (turn.clj:2059)                                 │
                  ▼                                  ▼
        SCI evaluate → seon.sci.admit projects a print node (admit.clj:698)
        → seon.render.value/render-ai → seon.print/fit (value.clj:608)   ← CLIP #1 (lawful)
        → stored :seon.eval/shown (+ out, error, triage, ms)
                  ▼
  seon.cluster.prompt/prompt (prompt.clj:418)
   └ render/acquire-context! → walk/history (walk.clj:927): every stored evaluation
       → render/render-call → schema pair → seon.repl/render-ai (repl.clj:441)
         └ entity-emission (repl.clj:360): more db reads (ns name, renderer, changed-since Datalog)
         └ seon.repl/text (repl.clj:256): "ns=> " + comment + source + "\n" + response
   └ select (prompt.clj:297): drop oldest whole units under token budget  ← SELECTION #2
   └ compose "\n\n" + frame "turns left: N of M"     ← LAST TOKEN
   └ capture tx, then call-turn puts text in ONE user message
     (turn.clj:4494-4527; ai.clj:686-688; a system message only on failover)
```

Other clipping or bounding spots on the agent's path: the admission source cap
`:seon.config.eval.result/max-source` in `planned-sources` (turn.clj, via
declared-sources), the `truncated-string` face in `seon.sci.admit`
(`admit.clj:500`), and the elision text `seon.print/render-elision-ai`
(`print.cljc:457`). The experimental brief has its own `clip-lines`
(`experiments/tasks_v02.clj.txt`, `brief`). Sources are never clipped, and they should
not be. The fault query shows that the producer must emit small sources.

There is a parallel path: `seon.render.transcript` (2,379 lines). It is a second
agent-session projection with its own `history`, `entry-order`, `emission` and
full/summary/elided decisions. It does call `seon.repl/text`
(`transcript.clj:418-463`). It is used by `seon.eval.drive` and the web pages, not
by `seon.cluster.prompt`. So the page's "what the agent sees" and the provider
bytes come from two selections.

### Live excerpt (verbatim, the capture's lines 17–22 and tail)

```
my.agents.root=> ;; I should know my identity, namespace, and its steward.
;; (seon.cluster.status/agents {}) shows agent work and turn accounting on demand.
(seon.db/pull
  '[:seon.agent/id {:seon.agent/namespace [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}]
  [:seon.agent/id "root"])
#:seon.repl{:value #:seon.agent{:id "root", :namespace #:seon.ns{:name my.agents.root, :steward #:seon.agent{:id "root"}}}, :result result/e172d5f690409, :ms 6}
…
my.agents.root=> (my.agent/done)
#:seon.repl{:error "Execution error (ExceptionInfo) at seon.sci.eval/evaluate$fn$fn$fn$fn (eval.clj:3417).\nSCI program acquisition refused a declaration.", :result result/e19250c9f83a9}
;; full value: (get-in result/e19250c9f83a9 [])
…
turns left: 74 of 100
```

## 2. How many places print forms or values for agents

These are the agent-facing printers I found, each with its own conventions:

| # | Printer | Where | Convention |
|---|---|---|---|
| 1 | `seon.repl/source-text` (pprint code-dispatch, `*print-namespace-maps*` false) | repl.clj:91 | `'x`, explicit keys; 28 call sites |
| 2 | `seon.repl/response` (hand-built `#:seon.repl{…}` string, per-field `pr-str`) | repl.clj:168-229 | wrapper map, escaped error string |
| 3 | `seon.print` node emitter + `fit` | print.cljc:856-1294 | `#:ns{}` maps, `(quote x)` |
| 4 | `seon.sci.admit` projection `pr-str` for scalars and names | admit.clj:95,183,495 | `#'v` |
| 5 | `render-default-ai-source` bare `pr-str` | render.clj:1368 | `(quote [*])` |
| 6 | `repl/error-text` via `clojure.main/ex-str` | repl.clj:148 | correct, then re-`pr-str`'d into #2 |
| 7 | `render-elision-ai` | print.cljc:457 | own prose |
| 8 | per-domain `*-ai` producers that return strings (help, note, plan, faults, identity, directory, instruction, message, ns pages …) | 67 `defn *-ai` in 30 files | mixed: prose, `;`/`;;`, `pr-str`, source-text |
| 9 | experimental brief (`r =>`, `;; ── Section`, `;; contract:`) | tasks_v02 `brief` | a fifth grammar |

Also: 35 hand-built `(list 'quote …)` in 13 files, and 321 `pr-str` in `src/`.
Not all of those are agent-facing.

### The braids (Simple Made Easy: one function, several concerns)

- **B1 `render-identity-ai`** (agent.clj:259-283) mixes storage lookup (issues query),
  selection (root-only hint, issue openings), ordering (help first, by `str`), narration
  (first-person comments) and printing (`source-text`).
- **B2 Domain producers return printed source**, not `{comment, form}` data
  (`render-notes-ai`, note.clj:59-70; plan.clj:1544; `render-faults-ai`, error.clj:1822-1830).
  `identity-form` returns data, and then its caller prints it.
- **B3 Print, then read back.** `declared-sources` re-reads the producer's printed
  string into forms (turn.clj:1880-1896) before evaluating. The form→string→form round trip
  exists only because B2 prints too early.
- **B4 `response-entries`** (repl.clj:168-211) mixes derivation (`def-note` re-parses
  the source with `edn/read-string`), error formatting, suppressing `:ms` on refresh,
  per-field printing and map assembly.
- **B5 `entity-emission`** (repl.clj:360-439) does database acquisition (namespace
  name, renderer symbol, a changed-since Datalog query) inside the renderer, so the
  render is not a function of the selected unit.
- **B6 Refresh is shown by reprinting.** `system-turn` stores a *new evaluation with
  the full source* for every changed read. The history then shows the same 110 KB
  form again with a diff as its value, plus a `;; full value: (get-in h [])` line.
- **B7 Two projections of one session** (`seon.render.transcript` vs
  `walk/history` + `prompt/select`).

## 3. What REPL transcripts look like in training data

- **clojure.main**: the prompt is `(printf "%s=> " (ns-name *ns*))`
  (`reference-code/clojure/src/clj/clojure/main.clj:102-105`). The value is printed
  with `prn` (`main.clj:492`), and the next prompt follows. Errors print as plain
  `Execution error (Class) at ns/fn (file:line).\nmessage`, and the prompt returns.
  clojure.org's guides use exactly `user=> (+ 2 3)\n5`
  ([REPL basic usage](https://clojure.org/guides/repl/basic_usage),
  [REPL and main](https://clojure.org/reference/repl_and_main)).
- **Printed output precedes the value.** `(println "x")` shows `x` then `nil`.
  `(doc f)`/`(source f)` *print* text and return `nil`. That is the native form for help
  and source ([clojure.repl/doc](https://clojuredocs.org/clojure.repl/doc)).
- **Reader conventions in printed data**: `'x` in pprint code-dispatch
  (`reference-code/clojure/src/clj/clojure/pprint/dispatch.clj:45` reader-macros).
  `#'ns/v` for Vars, and `#:ns{…}` maps at a 1.9+ REPL.
- **Written documentation** (ClojureDocs, READMEs, books) annotates with a trailing
  `;;=> value` or `;; => value`. That convention is for prose, not for an interactive
  session. Transcriptor ([cognitect-labs/transcriptor](https://github.com/cognitect-labs/transcriptor))
  treats a `.repl` file as forms whose results follow.
- **CIDER/nREPL buffers** use `user> ` and Jupyter uses `In [n]:`/`Out[n]:`.
  Both have the same structure: a prompt marks the author's input, and unmarked
  output belongs to the machine.
- **For an LLM**, ending the context on the prompt marker makes "type the next
  input" the most likely continuation. The model is primed to write forms, not
  narrate results. This matches the existing `seon.repl` docstring's own complaint
  (a second grammar "taught agents to echo results"). With chat APIs the user message can end
  in `ns=> `. DeepSeek also supports an *assistant prefix*
  ([Chat Prefix Completion (beta)](https://api-docs.deepseek.com/guides/chat_prefix_completion/)),
  which could carry `ns=> ` as the start of the reply. That needs a deliberate paid probe
  before we rely on it. The reply reader already strips leading prompt markers
  (`reply.clj:301`).

## 4. Recommended layered design

Each layer is a function of the previous layer's value. Only the last two know about text.

1. **Data (stored facts).** Evaluations keep what they keep today: source bytes,
   comment, namespace, print node or shown text, out, error triage, ms, handle.
   Nothing about presentation is stored except the clipped shown text (clip #1).
2. **Selection (one query of prompt units).** A single function
   `(prompt-units db agent-id turn-id)` returns an ordered vector of plain maps:
   help topics, opening evaluations, history evaluations. It resolves everything B5
   now looks up at render time (namespace name, renderer, changed-since). Budget
   selection (`prompt/select`) stays as it is. `seon.render.transcript` and the debug page read this
   same vector, which retires B7.
3. **Per-schema AI render returns data, not text.** An opening producer returns
   `{:seon.repl/comment "…" :seon.repl/form '(my.error/mine)}` (a *call to a named
   function*, never an inlined 100 KB selector). The function's docstring and name tell
   the story. System comments are third person or absent. An evaluation's AI render
   returns a small map: `{:prompt ns :comment … :source … :out … :value-text … | :error-text …
   :handle … :ms …}`.
4. **One printer with reader conventions.** One `seon.repl/print-form` (pprint
   code-dispatch) for forms. The value path, `seon.print` emit, gets the same reader-macro
   cases (`quote`→`'`, `var`→`#'`, `deref`→`@`), about 10 lines at `emit-node`.
   Namespaced maps are on for values, as at a real REPL. The agent's own source is printed
   verbatim (its bytes). Generated sources go through `print-form`.
5. **One clipping spot**, which stays at the value renderer at evaluation time
   (`value.clj:608`). No other layer cuts, and sources stay whole because producers
   emit small calls. A refresh stores a small evaluation that re-invokes the *same short
   call*. It does not reprint an inlined form or a diff-as-value.
6. **Assembly**, `seon.repl/text` over the vector, uses clojure.main's grammar:
   `ns=> ` + comment + source, then `:out` raw, then the value as printed (or
   the raw `ex-str` error), then a one-line machine comment `;; result/e… · 6 ms`
   (handle and timing retained, per the llm-providers skill), then a blank line. The frame
   becomes a comment, and the context **ends with `ns=> `**.

### Before / after (same session, about 20 lines)

Before (real bytes, abridged only where marked):

```
my.agents.root=> ;; I should understand how this REPL works before I act.
(help)
The prompt shows your namespace my.agents.root and is drawn for you. Send only ;; thinking comments and forms.
…12 more lines…
my.agents.root=> ;; I should know my identity, namespace, and its steward.
(seon.db/pull
  '[:seon.agent/id {:seon.agent/namespace [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}]
  [:seon.agent/id "root"])
#:seon.repl{:value #:seon.agent{:id "root", :namespace #:seon.ns{:name my.agents.root, …}}, :result result/e172d5f690409, :ms 6}

my.agents.root=> ; Inspect errors in the namespaces assigned to me and in my own turns.
(seon.db/q
  '[:find
    [(pull ?error [:db/id [:seon.error/declared-schema :limit nil] …2,100 lines…
#:seon.repl{:value [], :result result/e9fbef1bb2afe, :ms 26}

my.agents.root=> (my.agent/done)
#:seon.repl{:error "Execution error (ExceptionInfo) at seon.sci.eval/evaluate$fn$fn$fn$fn (eval.clj:3417).\nSCI program acquisition refused a declaration.", :result result/e19250c9f83a9}
;; full value: (get-in result/e19250c9f83a9 [])

turns left: 74 of 100
```

After (target):

```
my.agents.root=> (help)
;; You are at a Clojure REPL in namespace my.agents.root. Write ;; comments and forms only;
;; each form's result arrives in your next turn.
;; (dir my.note) lists functions; (doc my.note/add!) explains one.
nil
my.agents.root=> (my.agent/whoami)
#:seon.agent{:id "root", :namespace #:seon.ns{:name my.agents.root, :steward #:seon.agent{:id "root"}}}
;; result/e172d5 · 6 ms

my.agents.root=> (my.error/mine)
[]
;; result/e9fbef · 26 ms

my.agents.root=> ;; The task asks for a durable contracted function.
(seon.db/q '[:find ?spec . :where [?f :seon.fn/sym 'my.agents.root/largest] [?f :seon.fn/spec ?spec]])
[:=> [:cat [:vector [:map [:example/amount :int]]]] [:map [:example/amount {:optional true} :int]]]

my.agents.root=> (my.agent/done)
Execution error (ExceptionInfo) at seon.sci.eval/evaluate (eval.clj:3417).
SCI program acquisition refused a declaration.
;; result/e19250 holds the error data · turns left: 74 of 100
my.agents.root=>
```

The briefs get the same treatment. `r => (seon.db/pull … (quote …))` + `;; contract:` +
source becomes `ns=> (source seon.note/forget!)`, which prints the source once, as
`clojure.repl/source` does, and returns `nil`. The docstring and contract are then shown
once, inside the source. Done-when forms are printed by `print-form` (`'x`).

### Help as data, ordered by derivation

- Schema `:seon.help/topic`: `:seon.help/id` (identity), `:seon.help/text` (one
  line), optional `:seon.help/example` (a stored *form*, printed by `print-form`),
  `:seon.help/needs` (refs to topics it builds on), and `:seon.help/about` (the Vars it
  teaches, as symbols). The AI render is `;; text` + example. The HTML render is `<li>`.
  Seeded from an EDN resource, which replaces both `help-value`'s literal vector and the
  `:getting-started` instruction entity. Owner edits are then transactions, not code changes.
- **Order is derived, not listed.** Sort topics topologically by `:seon.help/needs`, with
  ties broken by id. This is the same algorithm as `tasks_v02` `cycles`, and a cycle is
  refused at the writer. Situational topics (issue agent vs root, from `help-value`'s
  `issue?` branch) are selected by a query over the agent's facts, not an `if` in prose.
  The "Tools:" line is derived from `:seon.ns/requires`, as today.
- `(help)` prints the topics to `:out` and returns `nil`, like `doc`, so help sits
  inside the REPL grammar as ordinary evaluation output.

## 5. Size estimate (lines of source; estimates, not measured diffs)

| Change | Delete | Add |
|---|---|---|
| `response`/`response-entries`/wrapper map → clojure.main grammar | ~70 | ~30 |
| `entity-emission` lookups move to selection | ~60 | ~20 |
| Domain `*-ai` openers return `{comment, form}`; declared-sources stops re-reading strings | ~120 | ~25 |
| Faults opener: inline projection selector → call `my.error/mine` | ~40 | ~10 |
| `help-value` literal + `seon.cluster.instruction` → help topics (EDN, schema, render, topo) | ~140 | ~60 (+~40 EDN) |
| `seon.print` reader-macro cases; delete `render-default-ai-source` pr-str path | ~15 | ~12 |
| Frame as comment, trailing `ns=> ` | ~5 | ~5 |
| `seon.render.transcript` agent-prompt half reads the shared unit vector | ~500 (uncertain; needs a caller audit of web/drive) | ~40 |
| **Total** | **~950** | **~200 + 40 EDN** |

Without the transcript retirement: about 450 lines deleted and 160 added. Every row
passes the "≤100 added lines per slice" law when cut in this order: (a) trailing prompt,
frame, and response grammar; (b) printer reader macros; (c) the faults opener as a named
call (the 549 KB win); (d) openers as data and the help topics; (e) retiring the
transcript projection.

## 6. Verification limits

- Measured: capture size and per-unit sizes, and counts of `:limit nil`, `#:seon.repl{`,
  and `;; changed since` in one live capture. The printer and producer map was built by
  reading source at `295df8e3b`.
- Not measured: prompt derivation time, token count from the stored budget report, the
  model's behavior under the target grammar (this needs a deliberate paid A/B on
  identical tasks), or the DeepSeek assistant-prefix path.
- The transcript-retirement estimate is the least certain row.
