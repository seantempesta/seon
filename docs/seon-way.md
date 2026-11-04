# The Seon way

Everything an agent reads, and everything a person sees of it, is data on the agent or a query away, drawn by a render its schema declares, so a different agent with different data renders differently with no code changed. This page is the direction all work on context and rendering follows, and it says what is proven and what still has to change. The owner's words are in [decisions](decisions.md#2026-11-04-rulings); the mechanisms as they stand are in [ctx](ctx.md) and [render](render.md).

## The idea

Make all data known, validate it, and make it easy to process for whatever the user needs. The owner: "The fact that the agent's pull is what makes an agent an agent is what makes it render the UI and which parts go where."

This is Rails-style, batteries included: there is one way, it is the easy way, and the constraints are the point. Before adding a mechanism, ask whether a schema, a pattern and a render do it. Before fixing a problem, ask whether it is the cause or a consequence of something else being wrong, and remove the cause (`docs/decisions.md#a-bug-is-a-class-remove-what-allowed-it-owner-2026-11-03`). The data's shape is ours to change: rename attributes, add refs, reverse their direction; the standing store is test data, so no change of shape needs a migration.

## The flow

One pass makes both views, and nothing later in it is read by anything earlier.

```
schemas: what data exists, validated, each declaring its renders
   |
the agent's pull pattern (:seon.ctx/pattern), which it can edit
   |
@ctx: one record, written from the REPL or the db
   |
the walk: each key by its schema's render, else the default; parts its data names
   |
   |- html -> the person's page -> an image of the page, overwritten each turn
   |
   '- the model's render -> the transcript:
        system prompt, example sessions, the conversation   (cached, each record once)
        the page, latest only, and the image                (redrawn each request)
```

## The rules

Each rule removes a way of doing it some other way.

1. **What an agent reads is data** on its entity, a pull away, or queried by a render.
2. **Its schema declares its renders,** html for the page and model for the model. With none, a default draws it, and any valid entity renders.
3. **A render may ask for the db and query anything;** what it reads is tracked, so it runs again only when that moves. It need not be reachable from the agent, and nothing has to be pulled to be shown.
4. **The agent's data names what it shows:** its pattern for what it holds, a list of parts for what is computed or queried. Defaults come from its schema.
5. **A render runs again when its data changes or when a function that renders it changes,** at any depth.
6. **Each thing is computed once,** kept by the walk's existing cache. There is no second cache and no second path to the model.
7. **Derived values are drawn, never stored.** A summary, such as status's when many tests are red, is that schema's model render, and any agent can override it.

So adding something an agent reads is a schema and a pattern entry, or a schema and a part. Nothing else.

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

A live test agent tried each of these by data alone, on the 2026-11-04 tip of the first-message work; each was one test, and none of it is merged.

- A layout walking the agent's pull: new keys appear on both sides, a dropped join leaves both, a bad value is refused (``:seon.ctx/invalid``), and another agent stays as it was.
- A part named by the agent's data that queries the db: a new red test redraws it; an unrelated write does not run it.
- Delete, forget, restore, edit and compaction, with messages before the change byte-identical.
- Removal as unlink: retracting the entity had made delivery send a removed message again.
- Examples as a set of refs, added mid-session: 24 real messages sent, and the same pull draws the list.
- Re-rendering when a declared render, or a helper it calls, is redefined. The prototype watched 665 vars for 736 ms once, so the real one should read the program's reach index, measured.
- Reset to a turn's commit with the same key: the record, program and layout of that point, a further turn taken, and the source agent untouched.
- A turn tree: one real session's evals, grouped by their turn and flattened, reproduce the actual order.
- The debug view made by the live agent: it shows the branch's own renders, which root's rebuild cannot.
- A status summary: 22 red tests went from 32k tokens to 2k.

## What needs to change

These are the known departures from the rules, in the order of the work, each with its cause rather than its symptom.

**Facts nobody writes, so code guesses:**
- ``:ns/doc`` is declared and read but never written: all 148 namespaces were empty.
- Nothing says which turn an eval or a message belongs to, so ``seon.render.ui/turn-evals`` infers it from write order. A turn should own them, making the conversation one tree. Its call ids already hold the turn. Messages and evals from outside a model turn are not yet tried.

**Assembly outside the render system:**
- ``seon.context/request`` assembles the transcript. It should be a declared model render on the agent, answering a transcript schema, called by the turn, the debug page and the judge.
- ``seon.context/page-lists``, ``seon.render.ui/page-cites``, ``seon.context/namespaces-of`` and one component per part become data and declared renders. Cites are ordinary data with a render, and a stub resolves through what ``@ctx`` already tracks, but only for a key that is a join in that entity's spec, since a ref has the same shape.
- A part sent as a diff (step 6 of the current work ends this): the page is only ever the last message, latest only.

**Rendering not knowing its own functions:**
- Redefining a declared render does not redraw until the data moves.
- A var call in hiccup is a component only if its contract asks for ctx or the db; otherwise it is silently left as a call.

**The debug page draws from root:** root's rebuild runs main's code and costs up to 5 GB a draw on a real page. A live agent should draw its own; an ended agent's needs an answer.

**Runs:** the owner ruled there is no concept of a run, yet ``:seon.run/*`` remains: 17 source files, 64 schema lines and 422 test lines, most in the reconciler, and it is why a reset agent has no turn loop. It is the largest change here, and it is not needed by the core, so it comes after.

**Smaller:** two identities on an agent (``:seon.agent/id`` and ``:seon.agent/key``); two html renders of an eval; several words for a part (part, unit, component, tile); ``my.task/status`` and ``my.task/data`` computing what parts hold.

**Tried and not simpler:** an example cannot simply ref the finished agent, because a session's records live on its branch, not main, and its call ids would collide, so loading it as a copy stays. The agent's three html renders are a head, a record and a page, not three of one thing; the default render shrinks them but they remain.

## Costs still open

Latest-only text costs more uncached tokens per turn until the page is small: on method-key it cost about 3.5 times the baseline with cites still on the page, one run. Moving cites to structure should bring the page to about 1.3k tokens, which is predicted and unmeasured. An image costs about 990 tokens uncached per turn, reads names exactly at 1300 by 1300 pixels and invented them on a taller page, and needs a browser whose memory and time are unknown. Whether any of this makes agents better is not known until the six-task comparison against the baselines.
