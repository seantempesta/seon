# The Seon way

Everything an agent reads, and everything a person sees of it, is data on the agent or a query away, drawn by a render its schema declares, so a different agent with different data renders differently with no code changed. This page is the direction all work on context and rendering follows, and it says what is proven and what still has to change. The owner's words are in [decisions](decisions.md#2026-11-04-rulings); the mechanisms as they stand are in [ctx](ctx.md) and [render](render.md).

## The idea

Make all data known, validate it, and make it easy to process for whatever the user needs. The owner: "The fact that the agent's pull is what makes an agent an agent is what makes it render the UI and which parts go where."

Entities have attributes and those have values; that is what makes something something. The database is not rows of table schemas, and we store only what we retrieve. A task's history is its earlier states, read from the db's history, so no entity holds a copy of them.

This is Rails-style, batteries included: there is one way, it is the easy way, and the constraints are the point. Store data for how it will be used, so that use is easy and cheap, and do not organize things on top of other entities when a value on the record says it. Before fixing a problem, ask whether it is the cause or a consequence of something else being wrong, and remove the cause (`docs/decisions.md#a-bug-is-a-class-remove-what-allowed-it-owner-2026-11-03`). The data's shape is ours to change: rename attributes, add refs, reverse their direction; the standing store is test data, so no change of shape needs a migration.

## The flow

There is one way, in three steps, and nothing else.

```
curated data: attributes on the agent, or a lookup ref away, each with a schema
   |
the pull: the agent's pattern (:seon.ctx/pattern), an ordered vector, into @ctx
   |
render functions: data to hiccup, one render, which the page draws and the model reads
```

Everything before the model's own conversation, evals and replies, is that. The pattern's order is the order things appear.

## The rules

Before changing anything an agent reads or a person sees, **name the attribute, the pull clause and the render function.** A change is one or more of those three. If it can't be said that way, it is the owner's to decide: do not build around it.

1. **Data is curated.** Add, remove or refactor what's on an entity and how it's joined. Data nothing reads gets deleted, not stored.
2. **The pull says what an agent holds and in what order.** An agent can edit its own.
3. **A render is a function of data, and may query the db.** What it reads is tracked, so it runs again when that moves. Its schema names it; the nearest wins: the agent's own (``:seon.agent/renders``), over the schema's, over a parent's, over the default, which draws any valid entity.
4. **One render, read by both.** The page and the model read the same hiccup and the same words. A render speaks to the agent: what this is, why it matters now, what to do next. If a field answers no question the agent has at that moment, take it out.
5. **Only the one printer cuts** ([no clipping](render.md#no-clipping)). A render chooses what to show; it never trims text.
6. **Each thing is computed once,** kept by the walk's cache. There is no second cache and no second path to the model.
7. **A value is stored as data, up to a stated maximum.** Past it, what it was is stored and the value is gone, and that is fine.
8. **An agent's branch is kept and queried for what happened to it; its records never merge.** Main holds what root needs, and a merge stays the program and tasks. Reclaiming a record is the owner's act.

There is no other kind of thing to add: no new part, placement, slot or layout mechanism, no separate render for the model, no list beside the pull, no new word for a piece of the page, and no guard where a clear message would do. If you find yourself adding one, stop.

## What this gives you

Each of these is a change of data, and the next request regenerates from it; the provider's cache breaks only where the history changed, which is the intended price.

- **Delete anything:** `swap!` on ``@ctx`` unlinks it from the agent. The entity stays, so nothing is redelivered and it can be put back; history keeps everything.
- **Compaction:** replace the first record of a range with a summary and unlink the rest. The summary stays at the range's place.
- **Edit and resubmit:** `swap!` a record.
- **Forget from a point:** unlink everything after it. Earlier messages stay byte-identical.
- **Reset to a point:** a new branch from that point's commit and the same agent key, so context and program both rewind, with nothing destroyed.
- **Examples:** agents are sessions, so an example is a ref to an ended agent: real messages for the model, a list for the person, and curated sessions are loaded the same way.
- **Better rendering over time:** every agent can register a schema and a render on its branch; a render that throws is its own part, so a bad one breaks nothing else.

## What is proven

A live test agent tried each of these by data alone, on the 2026-11-04 tip of the first-message work; each was one test, and only what says merged here is.

- A layout walking the agent's pull: new keys appear on both sides, a dropped join leaves both, a bad value is refused (``:seon.ctx/invalid``), and another agent stays as it was.
- A part named by the agent's data that queries the db: a new red test redraws it; an unrelated write does not run it.
- Delete, forget, restore, edit and compaction, with messages before the change byte-identical.
- Removal as unlink, merged: retracting the entity had made delivery send a removed message again.
- Examples as a set of refs, added mid-session: 24 real messages sent, and the same pull draws the list.
- Re-rendering when a declared render, or a helper it calls, is redefined, merged ([reactive renders](render.md#reactive-renders)). The prototype watched 665 vars for 736 ms once; the merged one watches nothing and walks `:form/refs` only when a definition moves, about 2 MB for a typical render and 122 MB for one reaching 3,530 vars.
- Reset to a turn's commit with the same key: the record, program and layout of that point, a further turn taken, and the source agent untouched.
- A turn as a number on each record: one query reads a session (about 1 ms at 26 records) in the transcript's order, on a session mixing a model's turns, a message and outside evals. Writing it on new evals, and moving replies off the turn entity, are not yet tried.
- An eval as real attributes, and a turn folded into its model call: on a copy of a real session, all 16 evals and 6 turns rebuilt to the same message bytes. Finding model evals cost about half (2.6 times fewer bytes), and summing tokens without joining through a turn cost about a third. Reading one field warm cost the same, because a warm read does not decode a stored value, and one edit about 30% less; so the gain is queries and less duplication, not reads. The eval half is built on branch jolt/simpler-schema (unmerged): an eval is attributes (`:seon.eval/code`, `response`, `call-id`, `prose`, `schema`, `basis` and the rest), its value is plain data up to `:seon.config.store/max-value-chars` and a marker past it, and an `@ctx` eval is rebuilt from its basis for the model as for the page; the turn folded into its model call is not yet.
- The debug view made by the live agent: it shows the branch's own renders, which root's rebuild cannot.
- A status summary: 22 red tests went from 32k tokens to 2k.

## What needs to change

These are the known departures from the rules, in the order of the work, each with its cause rather than its symptom.

**Facts nobody writes, so code guesses:**
- Nothing says which turn an eval or a message belongs to, so ``seon.render.ui/turn-evals`` infers it from write order. A turn is a number, not an entity: each record carries it, and the conversation is one query, ordered by turn, messages first, then evals. What a turn entity holds today moves to where it belongs: a reply is a record, and what a call cost is on the model call.

**Data shaped against its use:**
- ``:seon.turn/*`` and ``:seon.model-call/*`` describe one thing under one id, with reasoning and calls also on the eval. A turn entity goes: the number is on each record, and how a call went is on the model call.
- Also: a branch is a keyword repeated on four entities; ``:seon.process/test-error`` is about tests but lives on a process; seven declared attributes nothing names.

**Assembly outside the render system:**
- ``seon.context/request`` assembles the transcript. It should be a declared model render on the agent, answering a transcript schema, called by the turn, the debug page and the judge.
- ``seon.context/page-lists``, ``seon.render.ui/page-cites``, ``seon.context/namespaces-of`` and one component per part become data and declared renders. Cites are ordinary data with a render, and a stub resolves through what ``@ctx`` already tracks, but only for a key that is a join in that entity's spec, since a ref has the same shape.
- A part sent as a diff (step 6 of the current work ends this): the page is only ever the last message, latest only.

**Rendering not knowing its own functions:**
- A var call in hiccup is a component only if its contract asks for ctx or the db; otherwise it is silently left as a call (seon.render/a-var-call-a-render-cannot-draw-says-why). Making every var call a component took one list's draw from 616 to 1,563 calls and removed the kept call structure needs, so it is not that simple.

**The debug page draws from root:** root's rebuild runs main's code and costs up to 5 GB a draw on a real page. A live agent should draw its own; an ended agent's needs an answer.

**There are no runs** (owner, 2026-11-04: "There are no runs get rid of them"; built on `jolt/simpler-schema`, unmerged): an agent works a task and root decides once, when the agent ends, how the task stands; a task holds that (its agent, outcome, reason, reasons, commit, and when they happen the rest), the agent holds its environment and, on its branch, its own end, and a flight is keyed by task id ([reconcile](reconcile.md#a-task-holds-how-its-work-ended)). A retry after a failure, a wake and a load are the same agent, the cap is agent-wide ([turn](turn.md#the-cap)), and no verdict or merge entity exists beside the task.

**Smaller:** two identities on an agent (``:seon.agent/id``, this process's name, and ``:seon.agent/key``, the session); two html renders of an eval; several words for a part (part, unit, component, tile); ``my.task/status`` and ``my.task/data`` computing what parts hold.

**Tried and not simpler:** an example cannot simply ref the finished agent, because a session's records live on its branch, not main, and its call ids would collide, so loading it as a copy stays. The agent's three html renders are a head, a record and a page, not three of one thing; the default render shrinks them but they remain.

## Costs still open

Latest-only text costs more uncached tokens per turn until the page is small: on method-key it cost about 3.5 times the baseline with cites still on the page, one run. Moving cites to structure should bring the page to about 1.3k tokens, which is predicted and unmeasured. An image costs about 990 tokens uncached per turn, reads names exactly at 1300 by 1300 pixels and invented them on a taller page, and needs a browser whose memory and time are unknown. Whether any of this makes agents better is not known until the six-task comparison against the baselines.
