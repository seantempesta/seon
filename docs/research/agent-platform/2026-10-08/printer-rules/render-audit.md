---
title: Render audit, do renders return data and does everything reach the value printer
type: research
status: complete
date: 2026-10-08
lane: render-audit
---

# Render audit (lane render-audit)

Owner, 2026-10-08: "We shouldn't be dumping in a ton of garbage to begin with. The whole point of the render functions
is to strip that shit away in the first place so the value printer doesn't have to do everything and handle every edge
case. Confirm the value printer is still printing the results of the render function and the render functions are
returning data not just text."

Method: HEAD `f3784a6b5` plus the working tree. Every `:seon.render/ai`, `:seon.render/html`, `:seon.render/form`
attachment in `resources/seon/schemas/*.edn` and `src/` was listed (including the `#:seon.render{:ai ...}` spelling);
each AI producer's body was read. Live probes ran on scratch agent `render-audit` through MCP SCI (nothing defined in
default's JVM beyond my own `def v`). Tokens are `seon.ai.tokens/estimate` of `pr-str`.

## Verdict

1. **The printer path holds.** Every render output is handed to the value printer, nothing re-dispatches, and a
   render's output is never fed back into selection. `inline-rendered` and `declared-projection` wrap a producer's
   output as a `:seon.print/projected` node; `make-node` prints non-text data by the printer's ordinary rules and a
   string as raw text under the printer's string budget. Nested values are never offered to a render again.
2. **Renders mostly return data, but 17 of 53 AI producers return text, and four of those call the printer or build
   printed EDN themselves.** The violators are listed below. HTML faces all return hiccup: `face-valid?` enforces
   `hiccup/hiccup?` and I found no count cut in any HTML face.
3. **The garbage the owner means is in three places, not in the printer**: the error reply (`:expected` plus a
   generated `:example` are 87 percent of a contract refusal), the entity walk (`:seon.related/data` repeats the
   rows the render already listed), and static prose (`help`, 1,105 tokens each call).

## 1. The reply path, with file:line

| Step | Where |
| --- | --- |
| Evaluation settles; shown text made | `seon.sci.eval/shown-result` (`src/seon/sci/eval.clj` around 3640-3690): builds a unit, calls `render.value/prepare`, then `render.value/render-ai-data` to take the text |
| Projection, one budget | `seon.render.value/prepare` -> `project` (`src/seon/render/value.clj:1704`); text sink `render-ai-data` `:1727` |
| Only the ROOT is offered to a render | `make-node`: `(when (zero? id) (declared unit value path output))` (`value.clj:1048`); comment there: "RENDER OUTPUT GOES STRAIGHT TO THE VALUE PRINTER, WHICH NEVER RE-DISPATCHES" |
| Stages: explicit value key, viewer namespace function, declared pair, floor | `seon.render/selection`, `explicit-value-stage` (`render.clj:622`), `namespace-stage` (`:670`), `declared-stage` (`:703`); floor producer `floor-producer` `:607` |
| Producer run under the SCI guard | `invoke-selected` `render.clj:1149`, `produce` `:1250` (output must satisfy `face-valid?` `:1236`: AI = any non-nil non-error value, HTML = hiccup) |
| Output becomes a print node | `declared-projection` `render.clj:1326-1375`: `{:seon.print/node {:seon.print/face :seon.print/projected :seon.print/value face}}`. A value's own inline rendered data takes `inline-rendered` `:639` |
| Data vs text decided | `value.clj:1053`: `render-data?` = projected AI output that is not a string, then "an AI face that is not text is printed by these same rules in the value's place, and nothing in it is offered to a render again". A string is `raw-text` (`:1066`): "A RENDER'S TEXT IS A STRING SHOWN AS WRITTEN (`:raw?`) ... cut like any long string, under the one budget" |
| Terminal emit | `seon.print/emit-text` `::projected` arm (`print.cljc:510`) |
| Entity one-hop appended AFTER the render | `value.clj:1032`: `(@walk-with-related unit value output answer)` adds `:seon.related/data` beside the render's data |
| Other entrants | `seon.mcp/printed` (`mcp.clj:123-126`, `:348`) calls `eval/shown-result` or `health/render-ai` then prints; no second print |

So the claim "render returns data, the printer prints it" is true wherever the render returns data. When a render
returns a string, the printer treats it as one long string leaf: it cannot spread its budget over the shape, and a
cut falls mid-form (the existing task `directory-render-returns-text-the-printer-clips-mid-form` is that failure).

Not a render, but relevant to "one printer": `seon.render.value` holds a second text printer for failures, `datum`,
`bounded-text`, `link-lines`, `throwable-text` (`value.clj:85-190`) with its own `…` cuts (`:96`, `:124`, `:187`),
`"… further frames"`, `"… further causes"`. That belongs to the printer-rules census, listed here so it is not
mistaken for a render.

## 2. Every render function

AI faces (the form faces are all quoted read forms, data; HTML faces all hiccup). "Cuts" = cuts by count or writes
"N more"/`…` itself. The class column is what the function returns today.

### Data (36), no violation unless noted

| Producer | file:line | Returns | Prints values / cuts |
| --- | --- | --- | --- |
| `seon.agent/render-settings-ai` | `agent.clj:159` | map | none |
| `seon.cluster.agent/render-identity-ai` | `cluster/agent.clj:291` | map + operations | none |
| `seon.cluster.message/render-ai` | `cluster/message.clj:371` | map | none |
| `seon.cluster.message/render-inbox-ai` | `cluster/message.clj:485` | vector/map | `(str/split-lines content)` lines, no cut |
| `seon.cluster.status/render-ai` | `cluster/status.clj:164` | map + operations | none |
| `seon.cluster/render-ai` | `cluster.clj:180` | map of counts | none |
| `seon.db/render-transaction-ai` | `db.clj:5762` | report map | `(take 3)` of the offered operations, no elision mark |
| `seon.error/render-ai` | `error.clj:2122` | reply map (string only for a message-only error) | `reply-value` cuts: `names-in` at most eight, `call-lines` at most three (docstring `error.clj:1992`), no elision mark |
| `seon.error/finding-ai` | `error.clj:2209` | finding map | none |
| `seon.health/render-ai` | `health.clj:644` | map | `(take shown found)` ranked top findings plus `:seon.health/counts` per schema |
| `seon.render.ns/render-ai` | `render/ns.clj:264` | name, doc line, requires, columns/rows | doc first line only (selection) |
| `seon.render.ns/function-ai`, `task-ai`, `held-task-ai`, `agent-namespace-ai`, alias/refer/import | `render/ns.clj:193,101,160,306,20,41,64` | maps | none |
| `seon.render.schema/render-ai` | `render/schema.clj:114` | listing or report map | none |
| `seon.render.test/render-ai`, `render-run-ai` | `render/test.clj:50,97` | maps | none |
| `seon.render.transcript/render-runtime-ai` | `transcript.clj:940` | map + operations | none |
| `seon.render.var/render-ai` | `render/var.clj:128` | map + operations | `(take shown-tests v)` with the full counts in `:seon.render.var/counts` |
| `seon.repl/render-directory-ai` | `repl.clj:37` | vector of `{:sym :arglists :summary}` | none (now data; the task `directory-render-returns-text-the-printer-clips-mid-form` is stale) |
| `seon.db/render-rejection-ai` | `db.clj:5861` | delegates to `error/render-ai` | none |
| `seon.render.value/render-ai` | `value.clj:1804` | the floor, i.e. the printer itself | the one printer |

### Text (17)

| Producer | file:line | Builds | Violation | Class | Smallest fix |
| --- | --- | --- | --- | --- | --- |
| `seon.bootstrap/render-help-ai` | `bootstrap.clj:67` | joins 14 static lines, `help` is a macro value carrying its own pair (`:46`) | static prose, 1,105 tokens per call | genuinely text (instructions are prose) | cut the content, not the form: census slice (1,180 to 237 tokens) |
| `seon.cluster.instruction/instruction-ai` | `instruction.clj:66` | the instruction text verbatim | none | genuinely text | none |
| `seon.repl/render-ai` | `repl.clj:592` | the stored prompt and shown text of one evaluation | none, assembles SAVED shown text | genuinely text (history of printed output) | none |
| `seon.render.transcript/render-ai`, `render-run-ai` (with `seon.turn/render-ai` `turn.clj:1604`) | `transcript.clj:648,696` | joined stored text entries, interruption sentences | none, whole prompt units | text (history) | none now; sentences in `turn/render-ai` could be a small map later |
| `seon.render.transcript/render-history-ai` | `transcript.clj:897` | returns `""` | a render that renders nothing | n/a | delete the attachment, or return `nil` |
| `seon.context/capture-ai` | `context.clj:309` | `nil` | dead stub | n/a | delete the attachment |
| `seon.cluster.agent/render-situation-ai` | `cluster/agent.clj:371` | one paragraph of counts and tool doc lines | `str` of facts | convertible | return `{:agent-id :namespace :unread N :turns-remaining N :trigger id}` |
| `seon.cluster.agent/render-creation-ai` | `cluster/agent.clj:407` | one line | `str` of facts | convertible | return the four-key map |
| `seon.config/render-ai` | `config.clj:52` | `Configuration c. Model m (thinking ...)` | `str` of facts | convertible | return select-keys of the config unit |
| `seon.ai/model-ai`, `provider-ai` | `ai.clj:184,239` | `str`, `format "%.2f"` | `str` of facts, formats a number | convertible | return select-keys of the model row |
| `seon.problems/missing-model-ai` | `problems.clj:476` | one sentence | `str` of facts | convertible | return the entry map |
| `seon.background/render-ai` | `background.clj:11` | `(:seon.error/message error)` | none, one message | text (message) | none |
| `seon.effect/render-ai` | `effect.clj:68` | `payload-face`: `(str label " (~" tokens " tokens): " payload)` | prints a payload into a sentence, writes its own token note | convertible | return `{:effect id :run r :state s :request-edn .. :result-edn ..}`; the printer owns the size note |
| `seon.test.accretion/render-ai` | `test/accretion.clj:96` | grouped failure text | CUTS BY COUNT: `(take 3 members)` and `"N more in the complete gate-report blob."` | convertible | return `{:failure-groups [{:count n :failures [..all ranked..]}]}`; the printer cuts and names the elision form |
| `seon.error/ai-prose` | `error.clj:1031` | a sentence from `str/join` of flags | `str` of facts | convertible | return `(select-keys error [:seon.error/message :seon.ai/request-transmitted? ...])`; the sentence advice becomes `:seon.error/fix` |
| `seon.error/elision-prose` | `error.clj:2519` | message plus `evidence-text` (`(str attribute "=" (pr-str value))`) | PRINTS values with `pr-str` | convertible | return `(select-keys value [:seon.error/message :seon.error/data])` |
| `seon.error/mcp-prose` | `error.clj:2558` | same `evidence-text` | PRINTS values with `pr-str` | convertible | same |
| `seon.print/render-elision-ai` | `print.cljc:369` | `(literal (into (sorted-map) ...))`, printed EDN | the elision's own face is printed text built by the printer's `literal` | convertible | return the sorted map; the printer prints it |
| `seon.render/unknown-ai` | `render.clj:1101` | header line plus `admit/canonical-edn` | PRINTS the unknown to EDN text | convertible | return `(unknown-evidence-of unit)` |
| `seon.render.value/render-database-identity-ai` | `value.clj:367` | `(str "database " (pr-str name) " at basis transaction " t " commit " id)` | `pr-str` of a value | convertible | return `{:db-name .. :t .. :commit-id ..}` |
| `seon.ai/attempt-ai` | `ai.clj:96` | `(render.value/render-ai (attempt-without-private-provider-data unit))` | CALLS THE VALUE PRINTER INSIDE A RENDER: the result is printed twice, and the outer printer clips it as a long string | convertible | return `(attempt-without-private-provider-data unit)` itself |
| `seon.problems/ai-prose` | `problems.clj:551` | builds the family -> lines map, then `render.value/render-ai` with a profile and requery id | same double print (existing task `ai-prose-returns-printed-text-not-data`, `resources/seon/tasks/seon.problems.edn`) | convertible | return the lines map and the `:seon.render/operations` requery offer |
| `seon.error/render-faults-ai` | `error.clj:2440` | vector of replies, or a source string when it builds a steward read | text only in the source arm | the source arm is code | none beyond the vector arm |

Dead renders: `seon.error/time-limit-prose`, `edit-prose`, `unclassified-prose`, `index-refusal-prose`
(`error.clj:2490,2509,2547,2568`) are attached to no schema (only `test/seon/error_test.clj:1520-1574` calls them) and
all four render `pr-str` evidence. Delete them with their tests.

Inline renders found in code: `help` value (`bootstrap.clj:46`), `dir` value (`sci/eval.clj:2023`, the directory
map, data), and the renderer-failure message `renderer-failure` (`render.clj:1784`, which calls `unknown-ai`/
`unknown-html`). `my.program` `:259` is a query over the property, not a render. Renders found by contract fit in the
viewing agent's namespace (`namespace-stage`): none in the shipped program; they are agent-authored.

Contracts: the schema `[:seon.sci.admit/value :seon.render/ai-data]` (`seon.turn.edn:3`, `seon.render.edn:193`) already
admits any data on the AI face, so none of these conversions needs a schema change; only the `:string` return
contracts above change.

## 3. Live probes (agent `render-audit`)

| Value | Reply | Came from | Tokens |
| --- | --- | --- | --- |
| a Var `#'seon.render.var/render-ai` | `{:seon.render.var/symbol seon.render.var/render-ai}` | render data (`render.var`), minimal | about 12 |
| a task (pulled `ai-prose-returns-printed-text-not-data`) | id, sym, kind, ns, must-read set, name, intent, `:seon.related/data` | `task-ai` data, plus the related walk | about 330, one intent paragraph dominates |
| a namespace (`pull '[*] seon.render`) | name, doc, requires, columns `[:sym :arglists :summary]`, 28 rows, THEN `:seon.related/data` with the same functions again (`:seon.fn/_ns` count 95, 7 items), aliases, imports | `render.ns/render-ai` data plus walk | shown 1,406 of 2,458 whole; the name and doc alone are 254 |
| `(help)` | 14 lines of prose | text from `render-help-ai` | 1,105, flagged `:large-value` |
| `(doc seon.render/render-form)` | `{:body "" :example "" :supplied [...] :summary ... :arglists ... :in ... :out ...}` | an ordinary returned map, no render; two empty-string keys violate "absence is no key" | about 150 |
| `(my.program/source 'seon.render/render-form)` | map: `:sym :kind :line :file :source :doc :contract` | an ordinary returned map; `:source` is whole code (genuinely text, one string with newlines); `:doc` repeats the docstring that `:source` already holds | about 330 |
| `(dir seon.render)` | 30 maps `{:sym :summary :arglists}` | `render-directory-ai` data | 1,513 as maps, 1,286 as columns/rows; the stored directory value is 9,384 |
| a query result, 33 `[sym doc]` rows | set of tuples | no render, floor printer | 1,338, shown whole under the profile; the agent's query chose to pull whole docstrings |
| an error (contract refusal of `render-ai-data nil`) | message, problems with `:path :offending :expected :example` | `error/render-ai` data (`reply-value`) | 899 as the reply; `:expected` 285 and `:example` 497 |

## 4. The garbage, and what each render sends against what the agent needs

| Source | Today | Needed | Why it is garbage |
| --- | --- | --- | --- |
| Error reply for a refused argument whose schema is a big map | 899 tokens | about 80: message, path, offending value, expected NAME | `problem-shown` (`error.clj`, via `reply-value` `:2085`) emits the whole expected schema form and a generated example. The message already says "expected :seon.render.value/projection (a map)". Fix at the producer: show `:expected` as the schema's registered name when it has one and an `:example` only when the expected form is under a few leaves. |
| Entity walk `:seon.related/data` on a namespace | about 450 of 1,406 shown | 0 where the render already lists the same rows | the walk appends one-hop data after the render (`value.clj:1032`) without asking the render what it already covers. Fix: a render that lists an edge declares it, or the namespace render drops `directory` when related carries the functions (one of the two, not both). |
| `help` | 1,105 | 237 (census slice) | static prose repeated per call; also the future static prefix, so it should leave the reply |
| `dir` rows | 1,513 | 1,286 as columns/rows, matching `render.ns/directory` | keys repeated 30 times; two render families disagree on the shape of the same listing |
| `effect/render-ai`, `config/render-ai`, `ai/model-ai`, `situation`, `creation` | one paragraph each | the same facts as 4-6 keys, about equal size | no saving in tokens; the gain is that the printer owns layout and an agent can `get` a field instead of parsing a sentence |
| `accretion/render-ai` | not measured | all failure groups ranked, printer cuts with an elision form | cut by `take 3` plus "N more in the blob" text, an unreachable remainder |

## 5. Violators and fixes, in order

1. `seon.ai/attempt-ai`, `seon.problems/ai-prose`: render calls the printer. Return the pruned map. Closes the double
   print. Existing task for `ai-prose`; `attempt-ai` has none.
2. `seon.print/render-elision-ai`, `seon.render/unknown-ai`, `seon.error/elision-prose`, `mcp-prose`,
   `render-database-identity-ai`, `effect/render-ai`: printed values inside render text (`literal`, `canonical-edn`,
   `pr-str`). Return the maps.
3. `seon.test.accretion/render-ai`: count cut and "N more" text. Return all groups as data.
4. `seon.error/reply-value`: `:expected` form and `:example` as the large part of a contract refusal.
5. Walk-appended `:seon.related/data` duplicating render rows.
6. Delete dead attachments and functions: `capture-ai` (returns nil), `render-history-ai` (returns ""), the four unattached prose functions.
7. Facts as sentences: `situation`, `creation`, `config`, `model`, `provider`, `missing-model` to maps.
8. Counted cuts without an elision mark inside renders: `db/render-transaction-ai (take 3)`, `health (take shown)`,
   `var (take shown-tests)`, `reply-value` eight and three. Each is ranked selection and acceptable only if the shown
   count and the total both reach the agent; today `health` and `var` carry counts, `db` and `error` do not.
9. Stale tasks: `directory-render-returns-text-the-printer-clips-mid-form` describes text that `render-directory-ai` no longer returns.

Findings not recorded as task EDN: the issue index is the owner's; this file is the evidence and the assigned lane
does not edit it.
