# agent-view: hand-over (2026-10-24)

For the session that restarts agent-view in `~/src/seon-next`. Read AGENTS.md whole, then `docs/plan.md` (THE PLAN step 3 is yours), `docs/decisions.md` (the 2026-10-23/24 rulings below are there), then this note. Trust commits and numbers; re-check before acting.

## The goal

THE PLAN step 3, as the owner set it on 2026-10-24: **he can see exactly what an agent's model was sent and what came back, turn by turn, in a view made for reading.** In his words: "We made no improvements to the context and this is not a user friendly design and it's not a /debug url."

- **What to build:** `/agent/{id}/debug`, the whole model conversation as a chat transcript.
  - Every message in order: system, user, the model's tool calls (eval, with their `thinking`), the tool results.
  - Nothing cut: whole text, scrolling. A cut on this page defeats its purpose.
  - Per message: its tokens; per call: the provider's cache-hit tokens, finish reason, model, cost (`:seon.turn/*` usage facts).
  - What is recorded is the turn's basis, not its text, so the conversation is remade from the basis (`seon.context/remade`, `seon.context/request-text`; the request a turn sent is `seon.context/render` of its basis).
- **What v1 did, a reference only:** `~/src/seon/src/seon/render/web.clj` `debug-response` ("Model calls: what each was sent", one row per provider call, each opening into what it was sent) and `sent.clj` (`view-html`: the call's facts, its sections, the exchanges). The owner ruled data over prose renders, and v1 is not to be ported wholesale.
- **The context itself is agent-loop's,** not yours: the standard chat format (a short system prompt, the task as the user message, one eval tool, and at the end of each turn the live state, as eval calls and their results: the diff from main and the proofs to pass; decisions.md). It is on `jolt/acts-by-second-move`, going into `seon.turn`. Build the debug view over whatever the turn recorded, so it shows that format the moment it lands. Don't build a second context.

## What exists (merged on main)

- **Runs, turns, messages and agents are linked by refs.** Run → agent, turn → run, message → run, agent → root. Each identity takes a content id, so a branch and main name one entity. There is one agent entity per agent per boot (`:seon.agent/key`, `seon.task/agent-key`). Root records an agent's live state as facts (`seon.root/record-agents`, `seon.task/agent-tx`).
- **The walk** (`seon.render/walk`, `data`, `rendered`): an entity pulled with the neighbours its schema declares (`:seon.render/units`), only its relevant keys (`:seon.render/keys`), what its prose cites pulled once, as one data value. `docs/render.md#the-walk`.
- **The page** (`seon.render/page`, `seon.render.ui/page`): `/` root, `/agent/{id}`, `/walk/{eid}`. Each lists its evaluations newest first, with older rows loaded on scroll (`/more`). Tailwind v4 standalone runs in `bin/build css`, from `resources/public/css/input.css`. This is not the debug view: it shows a context read as one evaluation row, cut to its first lines.
- **Per-evaluation cost** (`:ticks`, `:ms`, `:alloc-bytes`, `:fns` on each eval's record; eval-cost's branch). The model's text never shows a reused/computed marker (decisions). Cost numbers in the context come from the record, so they're stable across turns.
- **The outside agent's `:done`** writes its run's end: `jolt/an-outside-run-writes-its-end`, with merge at last report.

## The owner's rulings this work rests on (2026-10-23/24, in decisions.md)

- A view is data, pulled from an entity, rendered by the value's own render through the one printer; no hand-wired pages. Improve data at its producer (relevant keys, derived facts). Prose renders were tried and dropped.
- The one printer stays general; a render shapes its data so the important part survives the printer's cut. Change the printer only for the general case.
- No invented names. Fully namespaced, registered keys. Port nothing wholesale; v1 is a reference.
- The standard chat format is the base for the model's context, because it measured as well as our 17 KB custom context in 1.6 KB. Data goes into it only where agent-loop's judge shows a gain.
- Reuse is transparent memoization keyed by content (the reuse session; `notes/reuse/design.md`). Nothing shows reused or computed.

## How to do it right (what cost hours on 2026-10-23)

- **Show the owner nothing he hasn't asked for until a fresh-eyes check passes.** Give a Sonnet subagent only the rendered output and ask what it is and what it would do; for HTML, a Haiku browser subagent gets a screenshot (`notes/browser-tester.md`). He judges by looking, and debug output, prose glue or a cut text reads as failure.
- **One worktree per task:** `cd ~/src/seon-next && bin/worktree <task>`. Never rebuild a worktree's `target/seon` while a test or root in it uses the binary: a test runner then waits for ever on a child that is gone (task `seon.check/a-runner-sees-its-childs-end`).
- **Your own root, on your worktree's ports** (`bin/ports`), started `SEON_PROFILE=scripted bin/root start`, then `(my.root/load-tasks! sys)` (a fresh store has no tasks), then `{:op :context :task "ns/name"}` attaches an outside agent. A good demo task cites its vars and has a real proof, e.g. `seon.context/the-map-reads-the-program-not-the-process`. Stop the root when done, after checking its PID and cwd.
- **A store built before a schema change can't open after it** ("types differ"). `target/check-store` and `target/runs/test-store` are reseeded only when missing: move them aside, don't patch them.
- **Datahike gives a content id only to a top-level entity map** (`content-tempids`), never a nested one. A producer that refs an identity entity writes it as its own map, with the identity value as its tempid.
- **A branch holds a stub of a run** (its id alone, so a turn's ref resolves). A run on a db is the entity its start recorded (`:seon.run/status`): `seon.task/run`, `runs` and `entity-value` read it so.
- **Merging main:** `git merge main`, take both sides, then check pins (`git diff main HEAD -- reference-code` is empty). A new submodule needs its worktree: `git -C ~/src/seon-next/reference-code/<sub> worktree add --detach <wt>/reference-code/<sub> <sha>`.
- **Load:** heavy runs only while `uptime` is under 8, at most three machine-wide. Queue with an `until` loop, not by hand.

## Sessions this one started (all in `~/src/seon-next` now)

- **page:** merged. Archive.
- **embed:** merged (3bf931bca). Archive.
- **evaluations:** `jolt/an-outside-run-writes-its-end` is with merge. `jolt/evaluations` is parked, superseded by the standard format. Its data pieces (a piece is a named function adding to the task message, scored by agent-loop's judge) wait on agent-loop's turn.
- **eval-cost:** `jolt/task-plans-cite` (the `:uncited-task` health class) and `jolt/wiki-true-2` (77 stale sections reviewed) are with merge.
- **walk:** now root's. `jolt/a-vanished-file-is-a-fact` is with merge.

Nothing of this session is running. The `viewer` worktree (`.worktrees/viewer`) holds only this note and the plan edit; remove it once they merge. The old tree `~/src/seon-jolt/next` is an archive: never commit there.
