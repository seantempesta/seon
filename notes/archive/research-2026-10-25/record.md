# The agent's record, what the model is sent, and one render of both (read-only learning, 2026-10-24)

Repo /Users/sean/src/seon-next at main 8517a97d6. Branch worktree: .worktrees/acts-by-second-move. Nothing was built, run or written in the repo.

## 1. The record today (schemas in resources/seon/schemas/seon.facts.edn, tx builders in src)

Agent (facts.edn:~115-135; writer seon.task/agent-tx on root's view of it; readers: web, seon.render walk)
- :seon.agent/key string, identity + content-id (agent entity per boot) · /id keyword idx · /root ref · /branch · /status · /pid int · /mem-mb · /running string (form running) · /description prose · /forked-ms /ended-ms /answered-t int.

Run (facts.edn:195-262; writers: root's reconciler and seon.turn/end!; readers: reconciler, judge, web)
- :seon.run/id (identity, content-id) · /task ref · /agent ref · /status enum · /outcome enum · /reason prose · /boot /pid /start-ms /end-ms /branch /model /max-turns · /turns /model-calls /tokens ints (counters of the turns below) · /basis-t /merged-t /commit /commit-error · /plan string · /results, /conflicts, /deferred-results (component refs) · /unmerged /changed /deferred /caused /uncontracted (sets) · /gate-unread (data). Mostly done-rule evidence, read by seon.reconcile and seon.problems, not by the context.

Turn (facts.edn:356-402; one writer, seon.turn/act! turn.clj:206-224 plus a second tx for :split at :225; context-block turn.clj ~250 for outside reads)
- :seon.turn/id identity string "run/n" · /run ref idx · /n int · /origin enum :model|:outside.
- /reply prose (the model's assistant text) · /reasoning prose · /finish string · /model string · /truncated bool.
- /basis data map {:commit :task :run :turn :max-turns :origin :observed :merge? :last-evals}: what the context was built from, never its text. Read by seon.context/render (context.clj:458-479) and the web.
- /calls data vector of {:id :code :evals(string) :items[eval ids] :thinking}. :evals is the text the next turn's tool message shows (context/evaluations-text, folded, budgeted by the printer at that turn). Read by context/render (scrollback) and render.ui/turn.
- Usage: /tokens /cache-hit-tokens /cache-miss-tokens (from model usage), /forms /errors /red /ms ints (counts derived from the calls), /split data {:context-ms :model-ms :eval-ms :record-ms :tests-ms}. Read only by render.ui/turn (tokens, cache, finish, model, errors, forms) and test/seon/e2e.clj (:split); /forms /errors /red /ms are read by tests and the page only. Nothing in src reads :split.

Eval (facts.edn:111-115,133; writer seon.repl/record-tx repl.clj:350-358, called from record! repl.clj:~375; reader render.ui/evaluation ui.clj:425-460, seon.problems, tests)
- :seon.cluster.eval/id string "<agent>-<pid>-<ns>/<n>" · /agent ref (the agent entity; the pull is the reverse ref _agent) · /record ONE data blob {:tag :ret|:err, :form (text), :lead (the `;;` comment lead), :shown (the printed text, already cut by the printer), :error (error map), :out (stdout), :ms :alloc-bytes :ticks :fns, :origin}.
- No attribute ties an eval to its run or turn. The turn names its evals by id in :calls/:items; the agent ref holds all of an agent's evals together.

Message (facts.edn:~140-150; writers root and the agent's branch): :seon.message/id /from /to(ref run) /body /reply-to. Read by context (taken-messages) and the web.

Model call: no entity. seon.model's request {:messages :task :turn} (facts.edn "the model" section) and completion {:text :tool-calls :prompt-tokens ... :reasoning} are values that flow through the :model effect and are folded into the turn's attributes above. `:seon.model/message` and `tool-call` are schemas, not stored facts.

Other records the run produces: :seon.fx/* (effects), :seon.judge/* (scored first moves), :seon.error/* signatures. Not part of the turn story.

Recorded and (nearly) never read: :seon.turn/split, :seon.turn/forms/errors/red/ms (derived counts, re-derivable from calls and items), eval :alloc-bytes/:ticks/:fns (page only).
Duplicated: the same answer is stored twice, as eval :shown (inside the record) and as the turn's calls :evals string. The two can differ (different budgets).
Lost: the eval's VALUE as data. Only the printed, budget-cut text survives (real record from the standing store: :shown "{:platform 1, :eval 0, ... :test-re" cut mid-key). The live :value exists only in the agent process, behind a handle (:h3 / my.agent/$).

## 2. What the model is sent per turn

Flow: seon.turn/work! loop -> observe -> act! (turn.clj:~185) -> basis! (turn.clj:140-168, records the basis) -> seon.context/render (context.clj:462-479) -> agent/fx! :model {:messages ...} -> seon.model/request-body (model.clj:115-126, wire-message 100-113 adds the one eval tool) -> reply -> repl/eval-string per tool call -> the turn tx.

Message list (context.clj/messages :369-390):
1. system: docs/agent.md as the wiki holds it at the basis commit (docs/page-text, context.clj:~26). ~2350 tokens, the same for every agent, cached.
2. user, "run sections" (run-keys :task :references :found :map :sections :evidence), printed by the one printer; made from the FIRST turn's basis and re-used for all later turns (run-text, context.clj:~446).
3. earlier model turns, within history-budget (kept-turns): assistant {content = :seon.turn/reply, reasoning, tool-calls = {:id :code :thinking}} then one tool message per call with content = the stored :evals string. No calls: user "Nothing ran: the reply called no eval."
4. last user, "The current state:" + the turn sections (:turn :proofs :refused :test-run :unmerged :will-merge :attention :tests :messages :your-last-reply, and :last-evals only before any turn shows them), computed by current-state from the db at the basis commit plus basis :observed.

What a tool message looks like (real, from /Users/sean/src/seon-jolt/next/notes/context-sample-design.md, run 6, turn 12, message 4): the printed EDN of the item map, not a REPL transcript:
```
{:printed "nil",
 :form "(clojure.repl/source seon.problems/report)",
 :out
   "(defn report\n  \"Every problem recorded ...  (the source with \\\" and \\n escapes inside an EDN string)"}
```
The value (nil), the real output (:out) and the form are all fields of one map, escaped.

Recorded versus sent:
- Sent and re-derivable: everything except step 3's strings is made again from the basis (commit + observed). seon.context/remade (context.clj:~416) and turn-text re-render it; request-text (context.clj:~436) prints the whole request. Re-render depends on the code version: the next render of an old turn uses today's printer and today's current-state, so it may differ from what was sent. The stored :evals strings and :reply do not drift.
- Sent and not recorded verbatim: steps 1, 2 and 4 as text (only the basis). The wire body (eval-tool, max_tokens) is rebuilt by model.clj, never stored.
- Recorded and not sent: eval :ms/:ticks/:alloc/:fns, :split, :out beyond the budget, :reasoning of the current turn (sent back only as scrollback).
- The model's `thinking` argument on each call is recorded (:calls :thinking) and replayed; the eval record's :lead (the `;;` lead) is recorded too but unreachable from the turn except by id.

## 3. Real examples

Standing store (live root 7888, read through `@(:conn sys)`): 680 tasks, 23 evals, 0 runs, 0 turns, 0 messages, 0 agents. Run #10 and every paid run live on branches that are no longer in this store (runs/store-agents/main is empty; jolt/dry-run-merges is the merge of run #10, kept as evidence, no store with it). So only evals can be shown from the store; a model turn comes from the saved transcript above. One more transcript: the notes tree in ~/src/seon-jolt/next/notes/context-sample-design.md (1190 lines, turns 0 and 12 of design run 6).

Eval record from the standing store (an outside agent's, the form is root's own query):
```
"root-outside-1394598569294750/0"
{:tag :ret :ms 64.2 :alloc-bytes 84309808 :ticks 16184822 :origin :outside
 :form "(into {} (for [[k v] (:classes (my.root/problems sys))] [k (:n v)]))"
 :shown "{:agent 0,\n :cost 0, ... :orphan-page 0,\n :platform 0,\n :problem-unlinked 0,\n "}}   ; cut mid-map
```
Another one's :shown is `":ok"` after a (spit ...), with the form in the record but no :lead.

Turn excerpt (design run 6, turn 12): message 3 = assistant "I'll start by reading the current state of the code this task touches." with two eval calls `(clojure.repl/source seon.problems/report)` and `(clojure.repl/source my.root/problems)`; messages 4-5 = the two tool results shown above; message 39 = the last user message "The current state: {:turn \"13 of 40\", :will-merge {}, :proofs [{:test ... :status :undefined}], :refused [{:refusal :no-design ...} {:refusal :proof-unknown ... :do (do (in-ns ...) ...)}]}". Whole request: 39 messages, ~15254 tokens, of which the first two (system 2350 + run 2756) are the cached prefix.

## 4. jolt/acts-by-second-move (/Users/sean/src/seon-next/.worktrees/acts-by-second-move/src/seon/judge.clj; owner's turn design in docs/decisions.md:10-24)

- standard-system (judge.clj:~157): ~600 chars, REPL semantics stated ("Everything you send is Clojure code the REPL evaluates, and everything you get back is the REPL's output ... followed by the status of your task's proofs now").
- stories (judge.clj:166-196): one paragraph per stage (:design :implement :test :merge): goal, done-when, means.
- standard-user (judge.clj:~224): story, "Task <id>", the accumulated plan, "Proofs now: p1 :not-run, ...", "The vars the plan cites:" one `- sym: first doc sentence` line each, then optional named pieces (:repl-calls).
- standard-messages (judge.clj:262-285): system; user; for each recorded model turn (from :seon.turn/reply and :calls, not from render): assistant {content reply, tool-calls [{:id :code}]}, then tool messages {:content evals}, the last one suffixed with "\n\nProofs now: ..." ; or, with :live, the last turn ends with an assistant ";; the system evaluates, as it does after every turn:" holding two tool calls.
- live-state (judge.clj:254-260): evaluates "(my.branch/changes)" and "(my.task/status)" through repl/eval-string with origin :context (so recorded as evals) and returns [{:id "live-0" :code :evals}].
- Example (test/seon/judge_test.clj:141-145): live = [{:id "live-0" :code "(my.branch/changes)" :evals "(my.branch/changes)\n=> []"} {:id "live-1" :code "(my.task/status)" :evals "(my.task/status)\n=> [...]"}]; messages 2..3 are the assistant call and the tool answer "(my.branch/changes)\n=> []". So the tool content is REPL-shaped text, `form\n=> value`, not an EDN item map. No real recorded run shows it (the tests use stubs).
- It still reads :seon.turn/calls :evals as the stored string, so an eval's value is still only text.

## 5. Sketch: one sequence of evals, two renders

```
;; an eval is one entity, ordered inside its turn; the turn is the agent's model step
:seon.eval/id     string identity               ; "run/turn/k"
:seon.eval/turn   ref  (idx)  :seon.eval/n int  ; replaces :seon.cluster.eval/agent as the link; agent is reachable run->agent
:seon.eval/prose  prose       ; what the agent wrote around it: the `;;` lead and the call's :thinking, one string (today :lead + :calls :thinking)
:seon.eval/form   string      ; exactly as evaluated
:seon.eval/value  data        ; the value as EDN, with its schema (:seon.eval/schema qualified keyword, e.g. :seon.problems/report), ONLY when it round-trips; else {:seon.eval/unreadable "#object[...]" :type sym}
:seon.eval/out    string      ; stdout
:seon.eval/err    data        ; the error map (today :error)
:seon.eval/cost   data        ; {:ms :ticks :alloc-bytes :fns}  (today's record keys, unchanged)
:seon.eval/origin enum        ; :model :context(live forms) :outside :root
;; the turn keeps only: :seon.turn/id run n origin reply(prose before the calls) reasoning basis(commit, observed) usage(tokens cache finish model split)
;; :seon.turn/calls goes away: the turn's evals are the ones with :seon.eval/turn = it (one reverse-ref pull)

render(eval, audience) = [prose?] form  "=> " (printed value by the value's schema render, within a budget) out? err?
  audience :model  = tool message content "form\n=> value" (the 'REPL transcript' shape judge.clj uses), prose in the assistant's reply, out de-emphasised ";; out: ...", cost omitted
  audience :human  = the same sequence with prose in front (italic), value rendered by its schema's :seon.render/html, out collapsed, cost line, full value no cut
context = system ++ task ++ (for turn in run: assistant(reply, calls from evals) ++ tool messages from each eval's model-render) ++ live evals (origin :context, replaced each turn)
debug   = the same walk, :human render, plus the basis and usage per turn
```

What today's record maps onto:
- form, out, error, cost, origin, lead: the eval record's :form :out :error :ms :ticks :alloc-bytes :fns :origin :lead already exist.
- prose: :seon.turn/reply, :seon.turn/reasoning, :calls :thinking, and eval :lead (four homes).
- value: only :shown, the printed and cut string. Missing: the value as data and its schema. The rendering seam exists (seon.render/printed, render/rendered?, wrap-print repl.clj:~120-136 holds the live :value) but the record drops it.
- ordering: :seon.turn/calls :items holds the eval ids in order. Missing: the eval's own back-ref to its turn (only the agent ref exists), so the ordered sequence needs a pull through the turn today.
- the live-state forms (judge branch) already record as evals with origin :context; a turn should not duplicate their :evals text.

What is missing, in order of value:
1. Eval value as data (with schema), so the model render and the human render each print it under their own budget and no cut is permanent. Needs a storage rule for values that are not EDN-readable (the :unreadable marker above); a large value needs its own size bound (config key).
2. An eval -> turn ref (and n), and dropping :calls :evals so the answer is stored once.
3. The tool message in the standard shape (judge.clj's `form\n=> value`) instead of the printed item map, for both context and debug.
4. Prose in one attribute, and the system's own evals (live state) in the same sequence with origin :context.
5. Provenance of the printed text: if a render's printer changes, old turns re-render differently; because values would be stored as data, the sent text can be remade exactly again only if the render version or the printed text is also kept. Decide: store the sent text per eval, or accept re-render drift and show the render version.

Open question for the owner: the model's context text is a render of the record; whether an old turn's exact sent text must be reproducible (debug view's purpose) decides point 5.
