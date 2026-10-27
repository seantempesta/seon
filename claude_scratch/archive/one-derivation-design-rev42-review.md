# Review of revision 4.2 (one derivation): independent, read-only

Reviewer had not seen the work. Code read on design-rev42 (main at 3dbe4dd42) and `jolt/ctx-atom`.

**Verdict: approve with changes.** The two outputs and "no derived data in ctx" are applied faithfully. But two claims in §1 are false of today's renders, and the plan sends outside changes twice. The deletion table also misses live callers.

## 1. Faithful to the rulings?
- The two outputs, no `:born`/`order`/derived members, and the deleted `:page`/`:value` split (render.clj:247-250) all match the rulings.
- **"A detail the page folds is still present in the hiccup data" is false today.**
  - Folded content is drawn only when a tab opens it: ui.clj:785 `(when open? (evaluation own …))`, also :764 and :622.
  - Eval values are cut to the page budget, with per-tab `:opened` cuts (ui.clj:498-504).
  - So the model's page would depend on the user's tab state. The model's draw must be stateless: details drawn eagerly, no `:opened`.
- **The renders are not meaning-only.** ui.clj has 182 utility `:class` strings and Datastar `data-on:*` JS attributes (ui.clj:502, 588). The owner asked for hiccup "without presentation attributes or layout wrappers" (decisions.md:45). §5 implies rewriting every render. Choose and state one of these:
  - rewrite every render;
  - one print-time projection that drops `:class` utilities, `data-*` and `:style`. This is cheaper, and the owner's wording points to it.
- **A value inside hiccup must stay data.** `print-html` makes `seon-print-node` span trees (render.clj:115-123), several times the tokens of the printer's text. §5's "values by their own structure" must mean the value stays as data inside the hiccup, and the HTML emitter expands it.
- **Added beyond the rulings:**
  - the theme map (§5, §9b). It is presentation (CSS declarations in input.css:88-98), against "meaning only". Cut it.
  - the first-t memo by entity id (§3). It is a non-content cache (§5 laws) for one cheap seek. Cut it unless measured.

## 2. Deletion table
- **It counts 9 schema keys; there are 8.** The 9th grep hit is a comment (seon.render.edn:3). The 8: error/base, problems/row, problems/report, render/system, db/eval-fact, message/fact, task/task, run/run.
- **Callers it omits:**
  - `render/ai` at proc.clj:642: root's context. It feeds `/context.txt` (web.clj:176), root's debug tile (proc.clj:238, ui.clj:1089) and docs/dashboard.md and docs/render.md.
  - proc.clj:748: the stream detail for web calls.
  - server.clj:233 and :258: MCP error text.
  - `ai-messages` (context.clj:401-407): the request's conversation dispatches through `render-of … :ai`. It needs a direct call.
  - `rendered?`/`printed` (render.clj:274-292), `nested`'s text (render.clj:94), `drawn`'s `fs` (render.clj:263).
  - The config key `root-context-budget` (default.edn:647) and the `:seon.render/root-context` schema.
  - 14 test references, including render_cost_test.clj:66.
- **What breaks:**
  - root's context has no replacement. Root has no parts page yet, so "root's page as hiccup" does not exist. `system-data`'s problems-first ordering under budget would be lost.
  - MCP errors and `(my.root/problems sys)` (AGENTS §6) would print raw maps, and `error-data` would no longer put the message first.
- **Fixes:**
  - keep the `-data` functions as part values. Make `system-data` root's `[:system]` part value until root's page is parts.
  - put error ordering at its producer (`contract/error` builds an array map with the message first), not in a face.

## 3. Render in seon.context; the memo key
- **No cycle, but the wrong home.** `requiring-resolve` at draw time (render.clj:150) avoids a compile-time cycle. But seon.render.ui already requires seon.context (ui.clj:19), so `task-page` written in seon.context cannot use ui's helpers.
  - Put `task-page` and `related` in seon.render.ui. They call `context/run-state`, as ui already does.
  - Also say that declaring `:seon.render/html` on `:seon.task/task` replaces `task-box` (ui.clj:357) everywhere a task is drawn.
- **"The db key is equal" is undefined.** Make it a value: the t of the newest transaction ≤ t for which `story-reads?` answers true. The page diff must compare hiccup by `=`, not by memo miss.
- **Staleness, by ctx-atom's own docstring** (jolt/ctx-atom context.clj:282-290):
  - files on disk and namespace metadata are not tracked;
  - nor is config (`cfg/value` in `similar`/`related-count`, context.clj:155, 182).
- **Frequency:** the agent's own `defn` writes `var`/`form` datoms, so the story redraws on most defining turns. That is correct, but costly (next section). Byte stability is unaffected, because terms are read as of their t.

## 4. The `[:eval "e14"] result/e14` reference
- It is consistent: the tool answer is ruling 1's output, and the reference points to it. But it is too narrow.
  - Own `[:turn id]` parts (the model's reply and reasoning, ui.clj:541) duplicate the assistant message.
  - `[:run]` changes every turn (`:turns-taken`, render.clj:227).
- **Better:** the model's page leaves out the conversation parts its own transcript already carries (its evals and turns). Outside evals and messages stay as parts, and a resume re-derives the transcript. That gives no reference tokens and no special case.

## 5/6. Token cost
- **Understated.** §1's ctx diff is the patch's `:changed` "since the agent's last turn". 4.1 §5's "less what its own evals caused" is dropped.
  - So an own eval reaches the model three times: the tool answer, `[:evals "e14"]` with its record in the ctx diff, and the page part.
  - Every outside change (proofs, verdict) reaches it twice, as a ctx path and as a part's hiccup. That breaks the standing "told once" ruling (4.1 §3).
- **State the rule:** the ctx diff leaves out paths that a page part sent this turn already shows, or the reverse. Name which.
- **Measure first:** print a1's parts with the real printer, as projected hiccup and as text, and compare to today's start. Then run one recorded a-map turn's ctx diff plus page diff, with and without own parts, counting how often `story-reads?` fires.

## 6. Mechanisms
- No clock, and no guard.
- Two differs (ctx patch, part hiccup) are owner-asked.
- The eager stateless draw (point 1) must be the page's own render with no tab state, not a second "model render".
- Overbuilt: the theme map and the first-t memo.

## Top changes, ranked
1. A stateless, eager, meaning-only model draw. Values stay data inside the hiccup. Choose the projection or the rewrite.
2. Fix the double and triple telling: own conversation parts out, and a ctx-diff/page-diff split rule.
3. Complete the deletion table. Keep `system-data` and the other `-data` functions as part values, and order errors at their producer.
4. Move `task-page`/`related` to seon.render.ui, and define the memo key as a t.
5. Cut the theme map and the first-t memo.
