# Render: what a person or a model reads

Every view, the model's context included, is a render: the one value printer (`seon.print`), or a render function declared on the value's schema (``:seon.render/hiccup``). A render answers hiccup, and the page and the model read that same hiccup: there is no second face for the model. Nothing else shapes output, and nothing clips.

**It's like Reagent.** A render is a component, a plain function `(fn [opts & children] result)`; a render of one value is `(fn [opts v] result)`. Its optional props are `:as` (`:inline`, `:block`, the default, `:tile`, `:row`), `:budget` in tokens and `:handle`, the form its cuts read the rest through. A schema declares the component of its values (`{:seon.render/hiccup sym}`), and ```seon.render/hiccup``` finds it in O(1) by the value's schema (```seon.render/schema-of```: the producer's `:schema`, the value's metadata, an error map's type, or an identity attribute). A value with none takes the default component, the one printer (```seon.render/print-hiccup```), which shows a nested value that has a render of its own as a call of that render, in its one pass (`seon.render-cost-test/the-printer-renders-nested-values-in-one-pass`). Inside hiccup a vector headed by a function is a component call, expanded by the one serializer (`seon.web.html/html`); a keyword head is an HTML element. The render proc renders a page's units again only when their input changed and sends only the bytes that changed.

## A render does no work of its own

What a person or the model reads is a render of what already exists, the render each entity's schema declares over the pull, and it never computes again what the system already computed. The debug page shows what was sent, made again from the facts of that turn's commit ([below](#the-debug-page-is-the-wire-request)). A change to what an agent is sent or what the owner sees is opened in a browser before it is reported ([checks](checks.md#checking-a-page-in-a-browser)).

## No clipping

A cut never drops silently: it says what it omitted and how to read the rest. Storage keeps the exact value; renders budget it. There is no `clip`, no `subs` for display, no `take n` without its count, and no stylesheet clip (`text-overflow: ellipsis`, `line-clamp`, `overflow: hidden` on text): the render's budget is the clamp.

## Text, and the one string cutter

Text is cut one way, by the printer's string cutter (`seon.print.select/break-at`): after a line break in its room's second half, else after a space in its final quarter, else at the room, and the cut says `…+N chars in M more lines` and the form that reads exactly the rest (`seon.print.emit/cut-words`), as in `"its prefix"…+7279 chars in 88 more lines (subs result/h3 19523)`. A keyword or symbol longer than `:seon.config.render.agent/max-name` cuts the same way.

Source text is code, not a string: its producer marks it (```seon.print/code```, plain data `{:seon.print/code text}`, recognized by ```seon.print.select/code?```), and the printer prints it exactly as written, a string's newlines as newlines and its later lines at column 0 (`seon.print.emit/code-text`, `seon.print.select/code-width`), so any slice of the shown text is the recorded text and an edit copied from it applies (`seon.print-test/code-prints-exactly-as-recorded`, `seon.repl-test/an-edit-copied-from-the-shown-source-applies`). Plain text (```seon.print/plain```) is indented to where it stands. The printer never guesses code from a string's text.

`seon.print/plain` shows text unquoted under a token budget, sized by ```seon.print.select/estimate``` (the one sizer, `:seon.config.ai/chars-per-token-prior` characters a token): whole when it fits, else the same cut, naming `(subs source offset)` when given a source form. On the page `seon.render.ui/plain` links the cut to the page that shows the whole. A cited thing is drawn by ```seon.render.ui/cite-hiccup``` ([an entity cites](references.md#an-entity-cites)): a section as Markdown, a var as its source, each with the form that reads it whole.

A cut is read as its text where it stands; the printer answers no separate list of its cuts. Blind scoring chose this: readers answered every question from the inline cut alone and preferred it to a closing "left out" list or footnotes.

## A value on the page is its structure

The page draws a value from its data and the model reads it as text, both from one selection: `seon.print.emit`'s hiccup fold reads the operation stream the text layout reads.
- A map is a `<dl>` of keys and values; a vector, list or set an `<ol>` (a set `<ul>`); two or more maps in a vector sharing two or more keys a `<table>` (`seon.print.emit/table`).
- A reference, a map of identity attributes alone, prints as its identity value, never drawn by its schema's render (```seon.render/nested```; `seon.render-test/a-printed-reference-is-its-identity-value`).
- Code (`{:seon.print/code s}`) is `<pre><code>`, highlighted as Clojure by highlight.js (served at `/highlight.js`) through one observer in the page's shell (`seon.render.ui/shell`, `seon.print.emit/highlight`), which also re-highlights an element a patch morphed back; text (`{:seon.print/plain s}`) is a paragraph.
- A value holding hiccup under `:seon.render/hiccup` renders as that hiccup wherever it sits (`seon.render/nested`).
- A handle, ``result/a3f9c1d2``, links to its eval's line (`eval-e12`).
- A cut is a button that posts `/open` with its element's id and the form the cut prints; that element then renders what the form reads (`seon.print/read-rest`), read once at the edge (`seon.web/open-form`), and renders again only when its set of opened cuts changes.

Proofs: `seon.print-test/a-map-is-key-and-value-rows`, `maps-sharing-keys-are-a-table`, `code-and-text-read-as-themselves`; `seon.render-test/an-eval-row-renders-its-value-as-structure`.

## A render is a value, printed once

A view that chooses what to show makes a value, never text: an array map of what matters, in its order, which the one printer prints once at the reader's budget. Nothing is cut before the printer; where a list's whole lives elsewhere, the value carries the form that reads it (`:all-in (seon.task/agents (deref (:conn sys)))`).

A value too large or too host-bound to print whole declares a model render on its schema (`{:seon.render/model sym}`, ```seon.render/model```): `(f {} v)` answers the value to print in its place. The agent's process state is one (```seon.agent/state-summary```): each key as a scalar, a count or a host type, with the form that reads it whole.

A tool's answer says what it is and what reads more, one way for every tool: its schema's `:seon.render/more` holds [words form] pairs, and ```seon.render/answer``` prints `{:seon.print/is <the schema description's first sentence> :value <the value> :seon.print/to-use [[words form] ...]}`, each form naming the answer by its handle (```seon.print/at-handle```). A form may name `v` (the answer), `path` (a part's path) and `id` (an entity); a reader that cannot fill one shows no pair naming it. A plain value prints as itself (`seon.render-test/an-answer-says-what-it-is-and-what-reads-more`).

A render's default budget is config: `:seon.config.render/value-budget` for a value, `:seon.config.render/root-context-budget` for root's context.

## The page

Every page is the render of a ctx through the one walk (```seon.render/walker```): the root render's output is the page ([a layout places its lists](#a-layout-places-its-lists)), set in the frame (```seon.render.ui/page-chrome```: the top bar, the agents list, the page, the caption and the input).

- **A location is a ctx** (```seon.render.proc/location```). `/` is the signed-in user's focus, else root's page ([users](users.md#whats-on-screen)); `/root` is root's session; `/user/{name}` a user; `/agent/{name}` an agent ([a session's address](#a-sessions-address-and-the-agents-list)); anything else is `:seon.render/no-location`, a 404.
- **Each page is its entity's render** (```seon.render/render-var-of```): the `:seon.render/hiccup` its schema declares (an agent's and a user's ```seon.render.ui/agent```, the system entity's ```seon.render.ui/root```), and every walk is rooted on it: the kept walks, root's cold draw of a branch, the model's request (```seon.ctx.wire/render```) and an agent's own walk (```seon.repl/the-walk```). No caller chooses a page's render (`seon.render-test/an-entitys-schema-names-its-layout`). Where a parent's pull joins an entity, the walk places it nested (```seon.render/entity-render```), and its render answers the entity's entry.
- **A user's page** is an agent found by `:seon.user/name`, read from main. Its schema's pattern joins the agents it started by `:seon.agent/entry`'s pattern to `:seon.config.render/agent-tree-depth` levels; the last level says how many lie below it.
- **Root's page** is root's session drawn like any agent, its tiles including the user who started it with the agents under them. Beside it the frame places the system's own view (```seon.render.proc/root-walk```): a sentence of how the tasks stand (```seon.render.ui/root-head```), root's process (```seon.render.ui/process-tile```, from ```seon.render.proc/process-value```, assoc'd into the system walk's ctx as `:process` and never written), main's open problems (`seon.problems/health`) and the evals sent to root (```seon.render.ui/root-evals```). A stored value that no longer fits its schema is drawn as plain data (`seon.contract/fits?`).
- **The input**: an agent's REPL, messages, interrupt and release (```seon.render.ui/agent-input```), and root's request box (```seon.render.ui/root-input```; spawn and evaluate-in-every-agent are on root's debug page, ```seon.render.ui/root-tools```), each a UI call of root's request table ([dashboard](dashboard.md#callbacks-are-data)).
- **An agent's page is one layout for the model and the person.** A chat column (`#c-rows`): the example sessions, then a component per turn (```seon.render.ui/conversation-turn```) holding its model call's reply as Markdown with its thinking folded and its evals under it (```seon.render.ui/model-call-view```), then the evals of other hands. Beside it the task's list (`#p-task`, ```seon.render.ui/agent-parts```): what the model reads as parts. The head is the task's name and how it stands; its pid shows only while its process runs (```seon.task/newest-process```). The model reads the same components (`seon.render-test/the-model-and-the-page-read-the-same-parts`). While the agent's process runs the page is what it renders (```seon.repl/render-ctx```), when that is at least as new as the branch; otherwise root renders it from the branch with the same walk, so a person's message shows as soon as it is delivered (`seon.render-test/a-working-agents-page-follows-its-branch`).
- **The chat reads as a chat.** It opens with what was asked (```seon.render.ui/chat-ask```). A model call's evals fold under one line naming each one's intent (```seon.render.ui/steps```); each step (```seon.render.ui/evaluation```) is one line, opened to its code, its cost, its handle and its value by the render its record names (```seon.render.ui/eval-output```), whole. Seon's own evals are the model's alone and not drawn. Counts are tokens (```seon.print/tokens-text```). A person-only page draws Markdown inline (```seon.render.ui/markdown``` with `{:inline? true}`, by ```seon.ref/scan``` and ```seon.ref/emphasis```); the model keeps the delimiters, since a plan's delimiters pick what it cites. An opened step carries Run, Edit and the rest (```seon.render.ui/step-tools```, [run again](turn.md#an-eval-can-be-run-again)). The last card is the agent's own end (```seon.render.ui/chat-end```); the gate's judgment is the head's (```seon.render.ui/standing```). Once work has ended, ```seon.render.ui/proof-story``` reads each proof's status at each turn's basis.
- **Every page says which system it is**: the top bar holds root's `:seon.system/source` (the checkout main was indexed from, its branch and commit), drawn by ```seon.render.ui/source-line``` in the system walk (```seon.render.ui/system-source```).
- **Tabs** (```seon.web/view-of``` reads the address, ```seon.render.ui/view-param``` writes it): chat (`/agent/{name}`); `@ctx` (`/agent/{name}/ctx`: each part beside what the model reads of it, ```seon.render.ui/ctx-parts```, then ctx printed by ```seon.render.ui/ctx```; a part past `:seon.config.render/value-budget` is a closed `<details>` opened by `/open`); debug (`/agent/{name}/debug`, [the debug page](#the-debug-page-is-the-wire-request)).
- **An agent's view is its components** ([components](#components)): each list holds what the agent's pull holds, so a smaller pattern is a smaller page and context (`seon.ctx.wire-test/a-smaller-pattern-is-a-smaller-context`). The agent renders its own when root's render proc asks (the `:render` op, which first takes the facts main holds of its own entities); root lays the components out beside the agents list, so a changed component is sent and a new one is an append. An agent with no process, or one busy since its last render, is rendered by root from its branch (```seon.render/draw```). `seon.render-test/a-page-is-its-components`, `a-new-member-is-an-append`.

### A session's address and the agents list

A session's address is its key (`:seon.agent/key`, ```seon.render.ui/session-address```), the one name that survives a restart, so every link is a bookmark. ```seon.render.proc/session-of``` resolves the key, then the task's id, then its branch (the newest agent that worked it, of any boot), else a 404; ```seon.render.ui/focus-of``` keeps the address a page was asked by.

The agents list (```seon.render.ui/agents-list```, `#c-side`) is the signed-in user's, the same on every page, from the user's walk (```seon.render.proc/user-sides```): root's line, then the agents the user started, each with those it started, read from each entry's `:seon.agent/agents` ([who started whom](users.md#who-started-whom)).

The head and the list read main, so they say what main judged; the task tile (```seon.render.ui/task-view```) reads the agent's branch and says how the agent's own end left it. An eval's `:out` and `:err` show beside its value, each cut only with the form that reads the rest.

### How the work stands

How an agent's work on its task stands is one value, ```seon.ctx.status/status```, a function of its branch at a t, read by the turn (`seon.turn/status-now`, once a turn) and drawn by ```seon.render.ui/status``` (its hiccup ```seon.render.ui/status-hiccup```), which the page and the model both read. It holds:
- each proof of the task's accumulated plan (```seon.task/pulled-accumulated```), with the verdict its record pulled (```seon.ctx.status/task-of```: `:seon.test/status`, `:seon.test/failures`; [verdicts are a cache](merging.md#verdicts-are-a-cache)), never read again from the db (`seon.render-test/the-status-proofs-are-what-the-record-pulled`);
- the done rule's reasons (```seon.reconcile/done-explain```), each with the form that acts on it;
- from one diff of the branch against `:seon.agent/base-commit` (```seon.merge/content-datoms```): functions without a contract, problems caused and sections made stale (```seon.problems/caused-split```), what its merge carries, and its unmerged entries;
- a test run that failed as a whole (`:seon.agent/test-error`).

It reads facts about the work only, never the conversation, so a turn does not draw it again. It reads `:seon.agent/main-program`, main at its last content move (```seon.merge/content-commit```), so main's commit moving at each eval does not rerun it. A red test outside the task's proofs needs the reconciler's index: the agent's own, advanced by what differs (```seon.agent/reconcile-index```), else one built cold, and only then. A red here is the agent's to clear whoever caused it; one red at the merge base too is said as `:also-red-on-main`. A stray red is offered the form that registers it as a proof only when the branch changed that test (`seon.ctx.wire-test/a-red-main-was-red-on-too-is-the-agents-and-is-said`, `a-new-turn-does-not-run-the-status-again`). The component that shows it decides from what it kept with its hiccup (```seon.render/kept-of```).

### In-flight output streams

What an effect is still producing (a model's reply, an eval's output) is shown live and written once: the effect sends its text to one Flow channel keyed by the id of an entity already in the db, the web proc puts it in the element the render drew with that id, and the final value's commit redraws the component. Nothing in flight is held, rebuilt or written.
- **The message:** `{:stream id :in e :part p :agent a :text t}`, t the part's whole text so far, then `{:stream id :agent a :end true}`. e is the entity whose element was drawn (a model call's id), p the kind of text (reasoning, content, message, intent, code, out, err), `:agent` the stable key. Sent to `[:web :streams]` on a sliding buffer of `:seon.config.web/streams-buf`, so a producer never waits and a dropped message costs nothing.
- **An eval's output** is a part of its model call, `<call>/<k>/eval-<i>/out` (or `/err`), k and i its place in the reply (`:seon.model/at`, from `seon.model/reply-of`; the turn puts them on the request as `:stream`, ```seon.turn/act!```). *out*, *err* and *test-out* are a writer (```seon.repl/streaming```) that keeps the text for the record and sends each write over the agent's socket; root's agent proc (```seon.agent-proc/agent-frame```) appends it and puts the whole on `[:web :streams]`. One `:end` per opened part however the eval ends. Only the REPL's own thread sends, since ```seon.sock/send!``` frames can interleave across threads; an eval with no model call sends nothing.
- **The web proc** (```seon.web/stream-in```) holds each open part's latest text in memory and, for each tab whose page has e's element, replaces the element whole (```seon.web/container-frame```, ```seon.render.ui/stream-container```), since it is `data-ignore-morph`. It learns which elements a tab has from each patch and keyframe (```seon.web/drawn```), so a tab gets the text so far when the element appears; a stale tab is caught up by its keyframe. Selectors are `[id="…"]` (```seon.web/id-sel```), and no id holds a `.` (```seon.render/key-id```). Cost: `seon.web-cost-test/a-stream-message-costs-its-text`.
- **The feed is compressed** (```seon.web/feed-encoding```, ```seon.web/deflate-chunks```): each tab's SSE stream is one zlib stream flushed at each event, its window shared across events; `:seon.config.web/feed-encoding :none` sends it plain. `seon.web-test/a-feed-is-one-deflate-stream-that-a-browser-reads-as-it-comes`, `a-tab-opened-mid-stream-gets-the-text-so-far`.
- **The render** (```seon.render.ui/model-call-view```) draws the in-flight element (`aria-busy`, a cursor, `data-ignore-morph`), for the page alone, from the model call's commit until its evals have all run (`:seon.model-call/forms`) or it failed (`:seon.model-call/error`), while its agent lives; the streamed copy of the committed reply is hidden by CSS (```seon.render.ui/stream-part```). `seon.render-test/a-call-in-flight-draws-its-stream-element-until-its-evals-ran`.

## Components

A component is a call in hiccup, `[f opts & children]`, of a render whose opts contract declares what it reads: `:seon.ctx/ctx`, ctx as a tracking view ([reactive renders](#reactive-renders)), or `:seon.db/db`, the branch's db read under `d/with-reads`. The walk (```seon.render/render!```, ```seon.render/flush!```) fills those keys, runs the render in its reaction, and gives its root element its key as id (```seon.render/key-id```: the render's name and opts' `:key`, else its children's keys, never a raw entity id).

One cache, in the process whose page it is, holds each component's reaction, args, output and db reads. A component runs again when its children are not `=` to last time's, when a change of ctx reaches a path it read, or when a transaction's datoms touch its db reads; a run whose output is `=` to the last changes nothing. The datoms are matched once against the walk's index from read key to component, so a transaction costs its datoms and the components it reached. A plain form drawn before and not queued is a marker lookup (```seon.render/emit```). A flush draws queued components in mount order, parents first; a child no parent holds at the flush's end goes (```seon.render/unmount!```; `seon.render-test/a-child-moved-between-parents-stays`). Each render's runs are counted (```seon.render/ledger```). A call of a render that declares neither key stays a call, expanded by the page's writer and read by the model as the call.

Cost: a change draws its components, flat in the page (`seon.render-cost-test/a-change-costs-its-components`); a kept walk moved to another value of its branch draws the turn's component plus a lookup per earlier turn (`seon.render-cost-test/a-step-costs-what-changed`), and draws what a cold draw would (`seon.render-test/a-kept-walk-steps-any-way`).

### What a render is handed

A render is `(f opts node)`: opts, then the pulled node; everything else it reads is a key of opts.

| key of opts | what it holds | given |
|---|---|---|
| `:seon.render/index` | the entity index, what every schema lookup takes (```seon.render/schema-property```, ```seon.render/entity-pattern```, ```seon.render/entity-schema```) | always |
| `:seon.ctx/ctx` | the whole pull, as a tracking view | when its contract names it |
| `:seon.db/db` | the branch's db, under `d/with-reads` | when its contract names it |
| `:seon.render/drawn` | the renders this walk has drawn, var to function | when its contract names it |
| `:seon.render/nested` | true where a parent placed this entity | by the walk |
| `:key` | the name of what it shows, its element's id | by whoever placed it |

A contract names a key as `[:and :seon.render/opts [:map [:seon.ctx/ctx :any]]]` (```seon.render/inputs``` reads it). A render may later be handed the page as it stood at the previous stored point, never the walk in progress (ruled 2026-11-11, not built). At a REPL a render is tried as `(f {:seon.render/index (seon.agent/branch-index)} node)`; what a whole walk drew is `(my.render/tree)`.

### A component answers hiccup

A component answers hiccup and nothing else, the one state with the meaning in it: what it answers is what the page draws and the model reads ([a part prints as its render](#a-part-prints-as-its-render)), so no second function per component can drift from it. Data a reader decides from is a function of the facts, read by that reader: the turn reads ```seon.ctx.status/status```, the page's ```seon.render.ui/status``` draws the same data.

A component that answers nil (```seon.render.ui/own-problems``` with no problem) draws nothing: no element, no part, nothing sent (```seon.render/placed```).

A component that throws draws its error in its place: a line naming the render, what was thrown and where (```seon.error/thrown-in```), then a call of the error's render on `:seon.render/component-defect` (the render, its message, the thrown data under `:seon.render/threw`); every other part draws (`seon.render-test/a-components-defect-says-what-threw-and-where-in-a-line`). Each view of a pass renders on its own (```seon.render.proc/view-pass```, a [failure point](failure-points.md)): a view whose render throws is a `:seon.render/view-defect` page.

The agent's page parts (```seon.render.ui/agent-parts```, `#p-task`) are the tiles of what its pull joins, in the pull's order (its task first, each cite by ```seon.render.ui/cite```, its searches), the tiles of its evals' rendered values, then its own state: how its work stands (```seon.render.ui/status```), its open problems, the keys it added, its turn (```seon.render.ui/turn```) and, for the person alone, ```seon.render.ui/agent-state```.
- **The task** (```seon.render.ui/task-view```): how its work stands, its name, its instructions folded, its accumulated plan as Markdown, its id, then what it joins, each by its own render.
- **A cite** (```seon.render.ui/cite```, by ```seon.render.ui/cite-hiccup```): a function at `:doc` or `:code` depth as its source, the tests that reach it and its callers, and one real call taken from a test or caller (```seon.render.ui/call-in```; `seon.render-test/a-cites-part-shows-a-callable-form`); at `:summary` its arglists and first doc line; a section as heading, text at its depth and how to read the rest; a schema key as its form and description, with one real value for an attribute; a namespace as its doc.
- **A proof's failures** (```seon.ctx.status/failures```): failing assertions with the same value and message are one entry, `{:asserts [...] :got v}`.

### An entity may name its own render

What draws an entity's page is the render its schema names, unless the entity names one of its own: `:seon.render/hiccup` is an attribute too (```seon.render/render-var-of``` reads the entity's first, when this process has that var). So one agent's page can differ from another's over the same pull: an agent sets its own by `(swap! ctx assoc :seon.render/hiccup 'its.ns/its-render)`; a nested entity is always drawn by its schema's render; a kept walk whose entity changed its render is walked again from the new root (```seon.render.proc/advance-walk!```).

Two examples. The stage (```seon.render.ui/stage```), root's session's render: the same lists as `agent`, and in front of them for the person ```seon.render.ui/stage-front```, a welcome, else the last ask and its answer in large type with earlier exchanges folded. The pull view (```seon.render.ui/pull-view```, ```seon.render.ui/pull-front```): an entity shown as its data model, each clause with what the pull holds for it and the attributes its schema names that the pattern does not ask, from one producer, ```seon.render/pulled``` (`(my.db/pulled @ctx)` at a REPL; `seon.render-test/an-entity-as-its-data-model-has-one-producer`). A part an entity's own render places is read by the model unless its call is marked `:wire/for "page"`, as the stage's front is. Both put their front at the top of the chat column (```seon.render.ui/agent-page```).

### The hiccup is checked

A render's output is the intermediate both readers take, so what it may hold is a schema: an element's attributes are ``:seon.render/hiccup-attrs`` (unqualified keys with plain values; never `:class` or `:style`, whose look is CSS keyed on the render's name; never a `:wire/*` mark; never an entity map). ```seon.render/misfits``` counts what one output holds against it, ```seon.render/misfits-by-render``` sums a walk's. Nothing is refused yet: each render's count is pinned so it only goes down (`seon.render-test/every-render-fits-the-hiccup`), and the check costs one output's elements (`seon.render-cost-test/the-hiccup-check-costs-the-components-own-output`). The plan: task ``seon.render/the-hiccup-is-a-schema-checked-intermediate``.

### A part prints as its render

The model reads each part as the hiccup its render returned, headed by the function that drew it and the ctx path it read: ``[seon.render.ui/status {:ctx [:seon.agent/task :proved]} [:h2 "Where your work stands"] ...]`` (```seon.render/part-form```, printed by ```seon.render/part-text```). `(get-in @ctx path)` answers the data it read. A child component is a part of its own, nested. Class and style are not printed; ids and `:key` are. An element or call marked `:wire/for "page"` is left out of the model's reading (a walk only the model reads does not draw it: ```seon.render/draw```'s `:model-only`), and one marked `:wire/for "model"` is left out of the page. Source is `[:pre [:code src]]`, printed in a Markdown fence longer than any backtick run inside it (```seon.render/fence```). `seon.render-test/a-parts-head-is-the-function-that-drew-it-and-its-path-is-real`.

### The agent's turn

The turn line is the heading of ```seon.render.ui/turn```, `You're in seon.program.`, the namespace the REPL is in (```seon.ctx/current-ns```, from `:seon.agent/ns`, which ```seon.repl/record-tx``` writes only when it moved): a part placed at the end of the task's list, sent again only when the namespace moves ([a change is edits by name](#a-change-is-edits-by-name)). The turn number is `:seon.agent/turn` ([turn](turn.md#the-turn-is-a-number)). A move into a namespace whose own var hides a REPL helper answers `:helper-shadowed` (```seon.repl/shadowed```).

### Cost is shown one way

Wherever work is shown it is one line from ```seon.print.emit/cost-text```: `5.2 ms · 873k ticks · 4.1 MB · ~1.2k tok`. An eval's text carries it last, `:cost "..."` (```seon.print/response-text```), `"not run"` when none was recorded; the numbers stay data (`:ms :ticks :bytes`). Every origin goes through `seon.repl/eval-string`, so every eval carries a cost, a thrown one too (`seon.repl-test/every-eval-carries-its-cost-whatever-asked-it-and-whether-it-threw`).

### A search is a part

A search is a `:seon.search/search-fact` (its query, and `:seon.search/k` when not `:seon.config.render/search`) of a task or an agent, joined by their patterns, so an agent changes or adds one with `swap!` ([ctx](ctx.md)). Its component, ```seon.render.ui/searched```, reads the db by `seon.ctx.search/search` and answers one hiccup, a line a hit, drawn only once its query is embedded; `my.agent/search` answers the hits left out. A changed query redraws that part alone (`seon.ctx-test/a-search-is-a-member-a-swap-adds-and-changes`). What it finds: [vector](vector.md#what-the-work-is-likely-to-need-as-an-eval).

## One rendering system, drawn from the data

There is one rendering system, and the data describes the page: every part of a page and every message of a request is the declared render of an entity or attribute the pull holds ([ctx](ctx.md#the-pattern), `:seon.ctx/pattern`, which an agent edits by `swap!`), in the data's order. Adding something a person or agent sees is a write of data (a schema's render, a pattern clause, an entity), never an edit to a function that lists parts. Nothing about a page is stored beside the data it shows, and no render queries the db for what the pattern can join: what a text cites is entities joined by the pull ([an entity cites](references.md#an-entity-cites)); what exists only when rendered, such as search hits, is a render that declares `:seon.db/db`. Components still placed by hand are being moved to declared renders: tasks ``seon.render/an-entity-is-drawn-by-its-schemas-render`` and ``seon.render/a-planted-entity-reaches-the-page-and-the-request``.

It is re-frame's model, which is React's memo: one immutable value (`@ctx`) is the app-db, a transaction patches it, and a component is a pure function of its slice, run again only when its slice changed. Immutability makes "did my input change" a pointer check. The source is a database, hence `d/with-reads` for renders that query.

One pass over the pull renders every output asked of it ([users](users.md#the-stages-agreed-2026-11-09), stage 2a). A walk's `:outputs` is the page's hiccup by default, and the model's messages too when it holds `:seon.render/openai-compat`. An entity's schema names a render per output (```seon.render/render-for```); in one component run its hiccup render runs, then its model render, `(f {:seon.db/db db :seon.render/index ix} entity)`, answering messages and the calls it places, each its own component. Each answer is kept under its output's name and read by ```seon.render/answer-of```; the request is the root's model output with each placed component's in its place (```seon.render/openai-compat```). Until stage 2b's root swap, a walk root called with no entity takes the walk's source as its entity. A past point is read by ```seon.render/render-pull```, keeping no walk. A walk asked for the page alone runs no model render. An entity is recognized as the write check reads it, by the best-matching schema (```seon.db/most-specific```).

## Reactive renders

Your page and your model's context are renders of `@ctx`, and a render runs again only when a path it read changes: read the narrowest path you need, and give each member of a list its own component. A rerun indexes only the reads it gained or lost (```seon.ratom/index-reads!```; `seon.ratom-test/a-rerun-indexes-only-the-reads-it-gained-or-lost`), but a layout that lists every member still reruns at O(members).

**A render is an input of what it draws, as the data is.** A move of a definition (`:var/form`) queues each component whose render reaches it through `:form/refs` (`seon.render/queue-moved-renders!`, `seon.render-test/a-render-that-changes-draws-again`); it costs nothing until a definition moves.

**Writing one.** A render is a function `[opts & args]` answering hiccup; it is a component when its opts contract names `:seon.ctx/ctx` or `:seon.db/db`. A call `[#'f {} arg]` in a render's hiccup draws f there as a component of its own, keyed by f and its args:

```clojure
(defn eval-code "An eval's code." {:malli/schema [:=> [:cat [:map [:seon.ctx/ctx :map]] :int] :any]}
  [{c :seon.ctx/ctx} id] [:li (get-in c [:seon.eval/_agent id :seon.eval/code])])
(defn page "The plan, then the code of each eval." {:malli/schema [:=> [:cat [:map [:seon.ctx/ctx :map]]] :any]}
  [{c :seon.ctx/ctx}] [:div [:p (get-in c [:seon.agent/task :seon.task/plan])] (into [:ol] (for [id (keys (:seon.eval/_agent c))] [#'eval-code {} id]))])
```

A flush (`seon.render/flush!`) answers each component it ran, `:miss` for a new output and `:same` for an unchanged one (`seon.render-test/a-flush-answers-what-it-redrew`). A path that names nothing reads `nil` with no error. To change your page, redefine a render it drew (the walk watches every one), or register your render as the layout or a part ([adding a datum and its render](ctx.md#adding-a-datum-and-its-render)); each part is headed by its render's name, so a line you see leads to the function to redefine. What moved between two points is `(my.render/diff p0 p1)`. `seon.render-test/a-redefined-render-redraws`.

**What a read costs** (`seon.ratom-test/inferred-paths`):
- `(get-in c path)`, `(:k m)`, `(contains? m :k)`: that value changed, or something above it was replaced.
- `(count m)`, `(keys m)`, `(seq m)`: a member added, removed or replaced, never a change inside one.
- `(for [[id e] evals] (:seon.eval/code e))`: any member's attribute; `[#'eval-code {} id]` per member runs only the one that changed.
- `seon.ratom/unwrap`, `seon.ratom/realize`, printing: anything below.
- A component call whose arguments hold a view is never looked up among forms drawn before (```seon.render/emit```), since hashing a view reads all of it.
- A flush ends with nothing queued: a component drawn before a later one changed what it read is drawn again in the same flush.

ctx's diff (`seon.ctx/changes`) is by entity: a map holding `:db/id` is diffed key by key, any other map replaced whole. A db render reruns when a transaction's datoms touch the ranges it read (`seon.ctx.wire-test/a-db-component-runs-again-on-what-it-read`).

**How it works.** ```seon.ratom``` is reagent.ratom ported (`RAtom`, `Reaction`, `run-in-reaction`, `flush!`), with the cursor inferred: a render declaring `:seon.ctx/ctx` gets a tracking view that records each path it reads (a presence, a whole value, or a node iterated), as Vue 3's reactive proxy does. A source read through views has one watch: its reset! runs the diff once and reaches only the reactions whose read paths it touches, by index (`seon.ratom/source-changed`). The queued reactions run at each flush of the walk.

The frame is the one part with no value behind it: the document (`seon.web/page-doc`) and its head and scripts (`seon.render.ui/shell`), fixed hiccup served once. Its script is Datastar's bundle, `resources/public/datastar.js`, copied from `bundles/datastar.js` at the pin of `reference-code/datastar`; an upgrade moves the pin, copies the file and is checked in a browser.

### What draws what

`seon.render/tree` reads a walk's cache as data from its root, each component `{:render :args :id :tokens :reads? :children}` (`:tokens` of its own output, children as markers), so it chains: `(->> (my.render/tree) :children (sort-by :tokens) last)`. An agent's own is `my.render/tree`, over its live walk; root's draw of a branch (`seon.render/draw`) answers alike.

## Tailwind

The page is laid out by Tailwind's classes in each render's hiccup, responsive at every width, with no hand-written stylesheet beyond the CSS rules a render states itself ([Style](#style)). `resources/public/css/input.css` holds every directive, token and source glob (Tailwind v4, CSS-first): `source(none)` with an explicit `@source` over `src/**/*.clj`, the Phosphor theme's tokens, a base layer for semantic hiccup and the printer's classes, and a safelist of the utilities an agent may use in a render defined at its REPL. `bin/build css` (a step of `bin/build seon`) runs Tailwind's pinned standalone binary into `target/css/app.css` (`:seon.config.web/stylesheet`, served at `/app.css`); its stamp is the digest of its inputs, so unchanged inputs regenerate nothing.

### Where things go

A page is a bar over columns: the agents on the left (the frame's), then the page render's own columns, an agent's chat in the middle and its parts and tiles on the right (`seon.render.ui/agent-page`); the right column shows only when it holds a part, and each column scrolls on its own on a wide screen and stacks on a narrow one. `input.css` styles them by name: the frame's classes `seon-page`, `seon-bar`, `seon-columns`, and the layout's ids `#p-agents`, `#c-side`, `#p-chat`, `#c-rows`, `#p-context`, `#p-task`, `#p-tiles`. Each child of `#p-task` and `#p-tiles` is a block headed by what it is; its `:data-render`, on the page's html only, names the render that drew it. Shared styling: `seon-note`, `seon-stack`, `seon-stack-2`, `seon-row`, `muted`, `kicker`, `box`.

### Style

A component's root element carries its render's name as its first class, then any classes the render wrote on its root, and its render's var states its style (```seon.render/styled```, at html writing). The model reads no class: it reads the render as `:render` on the part's root, and no `:data-*` or `:aria-*` attribute; `:href`, `:title` and `:alt` stay (```seon.ctx.wire/presentation-free```; `seon.ctx.wire-test/the-models-page-holds-no-attribute-of-the-pages-alone`, `seon.render-test/a-components-root-keeps-its-classes`). A plan's text stays verbatim, backticks included.

A render's style is `:seon.render/style` on its var: Tailwind classes, its descendants by arbitrary variants (`[&>h2]:uppercase`), or plain CSS rules scoped by its name using the theme's colours (`.task-view > h2 { color: var(--color-text-400); }`, `seon.render/css?`), which need no build. ```seon.render.ui/page-rules``` is a component that reads the walk's drawn renders (`:seon.render/drawn`) and answers one style element per render with rules (```seon.render/rules```), in its own list (```seon.render.ui/rules-list```), so only a rule edited moves it (`seon.ctx-test/the-rules-move-only-when-a-rule-does`). It is for the page alone: no CSS reaches the model's request.

An agent reads and changes a style as any definition: `(:seon.render/style (meta #'seon.render.ui/task-view))`, `(source seon.render.ui/task-view)`, and `(seon.repl/edit! 'seon.render.ui/task-view "padding: 0.5rem" "padding: 1rem")` on its branch.

## A value draws itself

A value is drawn by the render its attribute's schema declares, found by the one lookup (```seon.render/render-for```): `(seon.render/hiccup {:seon.render/index ix :schema :seon.task/plan} plan)`. An attribute whose schema names another schema inherits its properties (```seon.db/parents```). Prose a person or model wrote is `:seon.db/text`, drawn as its Markdown's structure by ```seon.render.ui/text``` for both readers; a string the code composed stays plain (`seon.render-test/an-attributes-value-is-drawn-by-its-schemas-render`).

An eval in the chat is one line, its intent; its value appears when the person opens it (```seon.render.ui/eval-output```), html only. A value that has a render is also a tile of the agent's parts (```seon.render.ui/eval-tile```), which the model reads expanded; the eval's tool result keeps the real value as data. A value has a render four ways: it holds hiccup under `:seon.render/hiccup`; it names a render function there; its record names a schema that declares one; or it is hiccup itself. A function whose contract output is a schema declaring a render tags each value it answers with that schema (```seon.contract/named```, read once at definition). A render whose contract answers `:seon.render/hiccup-value` is drawn as html with the data folded under it (```seon.render.ui/hiccup-value```) while the model reads the data (`seon.render-test/a-value-that-is-hiccup-is-drawn-on-the-page`); a render this process lacks falls to the printer.

## Tiles are data; the context reads the page's derivations

Each fact the page and root's context both show has one producer they share:
- **A lifecycle is declared once, on its enum's schema,** read through `seon.contract/properties` (members in order: `seon.contract/enum-values`): `:seon.agent/status` its live, stopped and gone sets (`seon.system/gone-statuses`, `seon.render.ui/live`); `:seon.task/outcome` its finished, judged and failed sets, read once by `seon.task` (`seon.main-test/a-tasks-outcomes-are-read-once`); `:seon.task/state` each state's meaning, the state derived once (`seon.reconcile/task-state`). A new status is one enum entry.
- **How an agent is:** `seon.render.ui/shown-status`, `seon.render.ui/ended-as`, `seon.render.ui/event-detail`; a cost verdict's lines `seon.cost/verdict-lines`; root's process ```seon.render.proc/process-value```, drawn by ```seon.render.ui/process-tile```.
- **Test runs, merges, evaluations:** `seon.render.ui/run-words` and `seon.proofs/red?`; a merge is its task's outcome; an evaluation is ```seon.render.ui/evaluation```.
- **Errors** read `:seon.error/message` of the one error map ([errors](errors.md#errors)).
- **Numbers:** `seon.system/view-numbers`. One-line text is `seon.print/line`. A root function called from the page is recorded on main with origin `:ui` ([dashboard](dashboard.md)).

## Health is what flows

Nothing is pinged and nothing runs on a beat: a render pass happens on an event, an api answer or a tab's request. What is wrong has one producer, what is recorded or derived from main's facts: root's page reads root's defects and main's open problems (`seon.problems/health`, [health](health.md)), never re-derived from the live view. Root records an agent's death (`seon.system/end-agent`) and a refused spawn or failure of an agent with no task as it folds it into the agent's view (`seon.system/set-agent`). `bin/check idle` proves an idle root with a tab open steps nothing.

## The model's context: a cached prefix, then the turn

A model request sends what does not change first, so the provider serves it from its prefix cache, and sends nothing twice.

### What the agent reads is its native history, and everything else through the hiccup

The agent's history is its raw data in its native format, and everything processed or looked up reaches it through the hiccup render ([decisions](decisions.md#the-agents-history-is-native-and-what-is-looked-up-goes-through-the-hiccup-owner-2026-11-10)):
1. Its messages, eval calls and results are system, user, assistant and tool messages, made from `@ctx` by each entity schema's model render (`:seon.render/openai-compat`), never changed by anything derived from the page.
2. What is processed or looked up (the task, its proofs, cites, search, the turn, the agent's state) is the hiccup render.
3. That hiccup is an intermediate format: each part headed by the function that renders it, with no styling. It renders to html for the person and to the model's page message, whole once and then what changed.
4. The page's view of messages and evals repeats (1): each is an unexpanded call the html expands and the model never reads again.
5. A part may render differently for the model than for the page, never at the cost of (1).

How far this is built: tasks ``seon.render/a-model-render-draws-each-turns-diff`` and ``seon.render/one-tree-is-read-by-the-page-and-the-model``.

The request is a reading of the agent's one walk: the walk asked for `:seon.render/openai-compat` answers it (```seon.render/openai-compat```, read by ```seon.ctx.wire/request```). The agent's model render (```seon.ctx.wire/agent-msgs```) answers the system message and the session's opening and places its records in conversation order (`seon.ctx.wire/turn-entities`). Each record's model render (```seon.ctx.wire/eval-msgs```, ```seon.ctx.wire/message-msgs```, ```seon.ctx.wire/model-call-msgs```) runs in its own component and is kept on its cache entry, computed the first time the model reads it. An eval's result is printed by the one printer (```seon.render/result-text```). A model render that throws answers the error's model render (```seon.ctx.wire/defect-msgs```). ```seon.model/wire-messages``` turns the messages into the wire shape (```seon.model/wire-groups```: a reply's assistant messages folded into one; reasoning sent back unless `:seon.config.ai/replay-reasoning` is false).

What only a person wants is in the html render alone (an eval's cost and structured value, a fold's summary).

The order is the conversation's, so nothing sorts it (`seon.ctx/placed-messages`, `seon.ctx/turn-evals`):
1. **The system message** (```seon.render.ui/system-message```): the doc sections the agent links (`:seon.agent/system`), else `docs/prompt.md` (```seon.ctx.wire/agent-system-text```), the same bytes every turn. Then **example sessions**, when the task names any (`:seon.task/examples`, loaded by `my.root/load-example!`; ```seon.render.ui/session-examples```, ```seon.render.ui/example-session```): each framed by user messages `<session id="example/<id>" kind="example" from="seon">` and `</session>`, its records drawn by the agent's own renders as of its last t. Then a user message `<session id="<agent key>" from="seon">` opens the agent's own session.
2. **The page**, as calls Seon makes before each model call ([the render is an eval](#the-render-is-an-eval)).
3. **The conversation**, the agent's turns in number order ([turn](turn.md#the-turn-is-a-number)), read from what its `@ctx` holds: each turn's messages and the other history members its pattern joins with `:tx :at` (```seon.ctx/placed-messages``` places them by their link, naming no join; the joins whose members carry `:seon.model-call/n` make the turns, and a member that refs a turn's call stays under it), then its reply and evals (```seon.ctx.wire/model-call-msgs```), then the evals of other hands. A message is `{:from :body}`; an eval an assistant call and a tool message with the response as printed; a reply one assistant message. A record's text is a function of that record alone, so an older message keeps its bytes and the cache holds; a re-eval updates its own message in place (`seon.ctx.wire-test/a-re-eval-updates-its-own-message-in-place`). All of it is sent every turn (no history budget), so each request is the one before plus what was added; a conversation past the model's window ends the turn as `:seon.model/provider-error`.

A response is the map ``:seon.eval/response`` declares (`seon.repl/response`; `docs/prompt.md` lists its keys): one per eval call, its forms run in order and stopped by the first exception, its value printed under its handle (`e` plus the eval's id in the `result` namespace, `seon.print/response-text`). The wire id of a call is its eval's id. An eval keeps `:seon.eval/response` (handle, cost, namespace, warnings) and `:seon.eval/result`, a ref to its result entity ([users](users.md#what-each-thing-is)); `seon.db/response-of` answers the whole. The value is stored as plain data (```seon.contract/plainly```; past `:seon.config.store/max-value-chars` it is `{:seon.value/gone {:type :over}}`), its other parts as attributes (```seon.repl/record-details```); nothing printed for a reader is stored. A reply with text and no call ends the turn.

So each request starts with the whole of the one before (`seon.ctx.wire-test/the-prefix-stays-byte-identical-across-turns`), and each section goes out once a request (`seon.ctx.wire-test/each-section-is-told-once-on-the-page`). What the provider served from cache is recorded on each model call (`:seon.model-call/cache-hit-tokens`, `:seon.model-call/cache-miss-tokens`, by `seon.turn/usage`).

DeepSeek's prefix cache ([its guide](https://api-docs.deepseek.com/guides/kv_cache)) serves only a stored prefix unit fully matched: units are made at the end of each request's input and output, at prefixes several requests share and at intervals in a long input, best effort.

A short cache hit is a problem: each turn compares its request with the one before (```seon.turn/cache-miss```), sizes the equal leading messages (```seon.ctx.wire/messages-tokens```) rounded down to `:seon.config.ai/cache-block-tokens`, and a hit more than one block short is a `:seon.turn/cache-miss` error (`seon.model-test/a-turn-whose-cache-misses-its-prefix-is-a-problem`).

A reply cut at `:seon.config.ai/max-tokens` (`:seon.model-call/truncated`; `seon.model/reply-of` keeps the cut text) is almost always a repetition loop, not long work.

The field's evidence for this shape: SWE-agent (arXiv 2405.15793), CodeAct (arXiv 2402.01030; Seon's one `eval` tool, [model](model.md#one-tool-repl)), and Manus's append-only, deterministically serialized context. What grows a request is the conversation's tool outputs, not the page; masking old ones (JetBrains, arXiv 2508.21433) is an experiment, not a change: the owner's ruling sends the whole conversation.

### The render is an eval

The page is not a message: before each model call the agent's model render places it as a call Seon made and its answer (```seon.ctx.wire/page-diff-msgs```), generated from the call's basis and the one before, never written: the first turn's `(my.render/page p)`, answered with the page parts whole (```seon.render.ui/agent-parts```, stripped of presentation), each later turn's `(my.render/diff p0 p1)`, answered with the edits ([a change is edits by name](#a-change-is-edits-by-name)). A turn that moved nothing places none. Its id is a digest of its two points, so a request made again is the same bytes (`seon.ctx.wire-test/the-recorded-requests-come-out-the-same`). The agent can run either at its REPL.

It goes to the model as a whole reply of the agent's, an example it copies: its text and the call with its intent, read from the called function's var (`:seon.model-call/reply`, `:seon.eval/intent` on ```my.render/ctx```, ```my.render/page```, ```my.render/diff```). A call of a function without them is refused, `:seon.ctx/unworded` (```seon.ctx.wire/page-message```; `seon.ctx.wire-test/every-call-seon-writes-is-a-whole-reply`).

A request on a branch whose turns hold no basis yet is drawn at the branch as it is (```seon.turn/context-block```), and a cold one at a turn's commit (```seon.ctx.wire/render```). A tree with no system message is refused, `:seon.ctx/no-system-message`. A cold draw for a model reads only what the model reads (`seon.render-cost-test/a-cold-request-draws-what-the-model-reads`). What the walk does not know, so a page diff cannot say: config read inside a render, and the printer's own code.

### A change is edits by name

What moved of a component reaches the model as [editscript](https://github.com/juji-io/editscript)'s edits over its old and new hiccup, in names, or the component whole when that is shorter, so the model sees where the change is; `docs/prompt.md` teaches it once.

One generic function reads a component's hiccup before the diff (```seon.render/keyed```): an element's children become a map in their order, each under a name, and the element without them under `:own`. A child's name is its `:key`, else its `:id`, else its tag when no sibling shares it, else its first words; an element holding text, or one unnamed child, is one value. So no path holds a position (```seon.render/named-edit```); names were far better recalled than positions. Strings are never opened (`:str-diff` off). The operations are `[path :r value]`, `[path :+ value]`, `[path :-]`, each path starting with the component's id; a new component is `[[id] :+ form]`. Children that changed places are their element replaced (```seon.render/settled```). ```seon.render.ui/status-hiccup``` keys each proof and each merge blocker for this.

No clock decides the output: `seon.render/component-edits` passes `:vec-timeout nil` ([clocks](clocks.md)). `seon.render-test/a-change-speaks-in-names`.

A turn's page message is the edits between the page parts at two bases. The parts along the turns' bases are drawn by one walk (```seon.ctx.wire/page-parts-through```), advanced point to point (```seon.ctx/moved```, ```seon.ctx/patch```), so a cold request of T turns costs one draw and what moved at each turn. The edits between two points are one function (```seon.ctx.wire/page-edits-between```), what the message prints and `my.render/diff` answers. Nothing is stored: a rendering is made again from its point in time. Cost: `seon.render-cost-test/edits-cost-the-component`, `seon.render-cost-test/a-turns-page-edits-cost-the-same-at-any-length`.

### A layout places its lists

A layout lays its page out as lists, each a component of its own: a render whose var carries `:seon.render/list`, the id of the container element its output is (```seon.render.ui/agent-parts``` is `"p-task"`, `conversation-list` `"c-rows"`). ```seon.render/placed``` walks through a list component to its members (`placed-units`, `placed-hiccup`). Placement reads the components, never their hiccup: each component's placements (```seon.render/places```) are kept by its revision, so placing a page whose one leaf changed costs that leaf (`seon.render-cost-test/a-flush-places-what-moved`).

A unit that changes place is moved, never its container sent (```seon.render.proc/moves```: the longest run keeping its order stays, each other unit is put back by element id beside a neighbour); a unit that changed only in which child each slot holds is not sent (`seon.render-test/a-reordered-unit-is-moved-not-its-container-sent`). Units are keyed by element id, so each is named once on a page: the user's walk names its elements apart (`:id-prefix` "u-", ```seon.render.proc/user-walk```; `seon.render-test/a-page-names-each-element-once`).

**The root's own output is the page**: ```seon.render/placed-units``` answers it first, a unit in ```seon.render/root-container``` (`#p-page`), and every element with an id in it is a region (```seon.render/region```), where the units placed there go when the page is composed (```seon.render.proc/compose```): a list's members, and what another walk places (an agent's head in `#p-head`, root's system view in `#p-head` and `#p-tiles`). It is written again only when its own revision or a list's moves, so a member added is an append (```seon.render.proc/finish-patch```). The frame keeps the top bar, the agents list (unless the page draws its own), the page's slot, the caption and the input; a page no walk draws is ```seon.render.ui/bare-page```, and a frame the tab lacks is sent whole (```seon.render.proc/pass-view```; `seon.render-test/the-agent-page-holds-what-the-frame-held`).

Which lists the model reads as parts is the lists' own: ```seon.render/page-lists``` answers each list component holding no messages (`:seon.render/messages` on its var) and not marked `:wire/for "page"`; the model reads the others through their model renders. An agent's own walk draws what its model reads until a person's page asks for the rest (`:model-only`, ```seon.repl/whole-walk```, ```seon.render/draw-all```), then keeps drawing them while its process lives. A layout must put each list in a component that reads only that list, so one member's change reruns that list alone; the layout itself reads nothing (```seon.render.ui/agent```).

### A page is a kept walk

A page root draws is a walk kept across moves, advanced by what each move changed: root's own, and one per agent a tab watches, held with the branch's one read (`:branch-reads`) and disposed with it. `seon.render.proc/kept-walk` builds one (`seon.ctx/build` in a reactive atom); `seon.render.proc/advance-walk!` moves it to a later value (`seon.ctx/patch` with the moved datoms, else `seon.ctx/moved` diffs the two values); `seon.render.proc/branch-walk!` is an agent's. A move that adds a record reruns its turn's component (```seon.render.ui/conversation-turn```) and the record's own; earlier turns are a marker lookup each. The debug page's turn to come draws the tree cold (`seon.ctx.wire/render`), which measured cheaper than moving a kept walk over it and back.

The model's page (```seon.ctx.wire/page-parts-through```) is kept the same way at the last point it drew: a later point of the same branch within ```:seon.config.ctx/kept-walk-steps``` parents advances it, the same point moves nothing, and anything else is drawn again (```seon.ctx.wire-test/a-later-point-advances-the-kept-page-walk-and-answers-a-cold-draw```). The slot also holds the page at its chain's first point, so a flush between turns leaves the next turn's pair to advance, flat in turns (```seon.ctx.wire-test/a-flush-between-turns-leaves-the-next-turns-pair-to-advance```, ```seon.ctx.wire-cost-test/a-turns-pair-after-a-flush-costs-flat-in-turns```). A turn's page message reads the db only at its two fixed past points, which no later write changes, so those reads are not tracked; the render that places the pairs reads the current db and reruns at a write (```seon.ctx.wire-test/a-write-reruns-the-placing-render-and-no-earlier-page-pair```).

### The debug page is the wire request

The debug page shows what a turn put on the wire, made again from its records at its commit with today's renders, never a second render. A turn records its basis (`:seon.model-call/basis`: its commit, the main and merge base it heard, its model); ```seon.ctx.wire/wire-turn``` makes the request body again there, the messages (`seon.ctx.wire/render`) under the provider (`seon.ctx.wire/provider-of`, which the turn itself calls at its basis) by `seon.model/request-body` (`seon.ctx.wire-test/a-turns-wire-request-is-made-again-at-its-basis`).

The page (```seon.render.ui/debug```) is a timeline of the branch's model calls, a bar a turn sized by the context it sent and split by what the cache served, with previous, next and latest (`/at`). One request is drawn at a time (```seon.render.ui/debug-turn```, ```seon.render.ui/debug-input```): each message of the body in order, one row typed by its wire role with a line of its text and its size, coloured against `:seon.config.render/tokens-warn` and `tokens-over` (a request against `request-tokens-warn`, `request-tokens-over`). Nothing is grouped or renamed. Opening a row posts `/open` (unit `d-turn-o<i>`) and shows its fields whole as sent; the body's JSON stays whole at the end. A message the previous request lacked is marked new; a turn hashes what it sent (`:seon.model-call/sent-hash`) and the page says when its redraw differs; a turn whose commit the store no longer keeps (`seon.db/retain!`) is drawn from the branch's head, and the page says so. Latest, while the agent works, is the request its next turn would send, drawn at the branch's head each time it moves and only while a tab watches (`seon.render-cost-test/drawing-latest-costs-one-request`). A turn is to come while its agent has written no end (```seon.render.ui/to-come?```). What a tab chose of a view is one map, the view's `:tab` in the render proc.

## One path to the model

Everything a model is sent is read from the tree through one conversion, and an action on the page is in the model's reading or is named here with what the model has for it.

`seon.render-test/one-path-to-the-model` reads the compiler's facts: ```seon.ctx.wire/request``` is referenced by ```seon.ctx.wire/render``` alone, ```seon.model/request-body``` by the provider's call and the debug page's replay, and a form holding a message (`:role` and `:content`) outside the model renders, or a request that does not read ```seon.ctx.wire/render```, is red. It cannot see a message built from computed keys or text sent by a process outside src.

`seon.render-test/an-action-on-the-page-is-the-models-too` lists every render whose hiccup holds an action (`:seon/call` or a `data-on` handler). Each is named below; a new one is red until it says what the model has for it.

- `seon.render.ui/agent-input`: a person's REPL, messages, interrupt and release. The model's REPL is its eval tool and a message reaches it as a message; interrupt and release act on it.
- `seon.render.ui/root-input`: a person's request, which becomes a task (``seon.system/ask!``). An agent messages root, or `seon_eval root` asks by the same call.
- `seon.render.ui/root-tools`: root's spawn and evaluate-in-every-agent. An agent messages root, or `seon_eval root` does the same.
- `seon.render.ui/debug`, `seon.render.ui/debug-turn`: stepping through recorded requests. The model reads its own conversation whole.
- `seon.render.ui/toggle`: a fold's open and close. The model reads what is in it.
- `seon.render.ui/evaluation`, `seon.print.emit/elided-hiccup`: a cut opened where it stands. The model has the form the cut prints.
- `seon.render.ui/eval-step`: a person's opening of an eval (`seon.system/opened!`). The model reads the eval whole.
- `seon.render.ui/step-tools`: Run, To here and Edit are ``(seon.repl/again id)``, ``(seon.repl/again id code)`` and ``(seon.repl/again-to id)`` for the model; Copy holds the code it already reads; Back to here is a person's ([run again](turn.md#an-eval-can-be-run-again)).

## How a value is printed

A value is printed in two passes, selection under one token budget and then layout, so its text and its hiccup come from one selection and every cut names the form that reads the rest.

```seon.print/print-value``` is the one entry: a value, a handle the cuts read through, a budget in tokens (```seon.print/budgets```, each a `:seon.config.render*` key), a depth and a width. ```seon.print.select/show``` selects breadth before depth, identity keys first, building a tree whose cut nodes carry their continuation forms; ``:seon.print/full`` metadata prints whole and ``:seon.print/budget`` raises a value's own budget. A collection of alike members (```seon.print.select/row-class```) shows each whole in order while it fits, then one cut that counts and reads the rest (```seon.print.select/fill-rows```; `seon.print-test/rows-show-whole-then-one-cut`, `seon.print-cost-test/printing-rows-costs-what-it-shows`); a keyword-keyed map keeps breadth. Members indent one column inside their opener. ```seon.print.emit/emit-both``` lays the tree out as fipp does and answers the text and, when asked, the hiccup; fipp is only the layout's test oracle (`seon.print-test/the-layout-is-fipps`), and Jolt's `clojure.pprint` is not used. `seon.print/text`, `seon.print/line` and `seon.print/plain` are the printer at a budget ([Text](#text-and-the-one-string-cutter)). A map of two or more keys of one namespace prints as `#:ns{:a 1, :b 2}`, decided once by ```seon.print.select/lifted-namespace``` over every key, so charge and layout agree (`seon.print-test/a-lifted-map-indents-its-keys-past-its-opener`).

## One printer

There is one value printer, `seon.print`, and nothing is cut before it. A value a person or a model reads is its schema's declared render or the printer's text; a value that is not text reaching `seon.web.html/html`, `seon.print/line` or `seon.render.ui/plain` is printed by the printer, never `str`. The REPL answers every value by the printer; an error map is printed whole ([errors](errors.md#an-eval-errors-diagnostics)). An MCP answer that root's REPL can answer again (an op's `:again` form in `seon.server/ops`) is printed under the reply budget with that form as its handle.

`seon.render-test/one-printer-and-no-cut-before-it` reads the compiler's facts: every var outside `seon.print` that references `pr-str`, `prn-str`, `print-str`, `with-out-str`, `pr`, `prn`, `printf` or `clojure.pprint`'s `pprint`, `pp`, `write` or `cl-format`; every var of a display namespace (`my.*`, `seon.render.*`, `seon.web*`, `seon.ctx`, `seon.server`, `seon.cost`, `seon.repl`, `seon.agent`, `seon.problems`) that references `subs`, `take`, `take-last` or `drop-last`; and every var outside them that calls the printer with a budget, is named below with its reason. An unnamed one is red, and so is a name here that no longer does it. Not covered: `str`, `print`, `println`, `format`, and `first` or `drop` on shown members; review reads for them.

Equal values print the same bytes. A vector, a list and a sorted map keep their order; a keyword map of at most `:seon.config.render.agent/sort-bound` keys shows short members first, then by name, except an array map that is no entity, which keeps its author's order. An entity (a map holding `:db/id`) sorts, so one made two ways prints the same (```seon.print.select/held-order```; `seon.print-test/an-entity-prints-its-keys-in-one-order-and-a-view-in-its-own`).

### Printing beside the printer: the wire

Text for the wire, storage, a digest, a file, a URL, a command line or code to evaluate is `seon.edn/encode` (EDN that reads back, whole), and only these print beside the printer:

- `seon.edn/encode`: EDN for storage and the wire, whole.
- `seon.program/form-ids`: a form's code digest text, under `seon.edn/encode`'s bindings ([source](source.md#what-a-test-takes-of-a-forms-text)).
- `seon.render.ui/table-cells`: telling a Markdown table's rule row from its rows; a cut of data, not output.
- `my.agent/help-nearest`: a doc miss's nearest addresses, the best `:seon.config.render/search` of them as a search's hits are; a cut of data, not output.
- `seon.render/result-text`: the EDN envelope of a user message around a response already printed.
- `seon.ctx.search/search`: the call in its last hit's `:more`, code to evaluate.
- `seon.ctx.wire/describe-msgs`: the path in the call it generates, code to evaluate.
- `my.schema/registration`: the part of a schema form that is no EDN, in its error message.
- `seon.config/resolved`: the config keys a refused combination names, in its error message.
- `my.root/first-moves!`: the code it sends an agent to evaluate.
- `seon.server/verdict-line`: the form in its `:handle`, code to evaluate.

An agent's namespace refers `clojure.pprint`'s `pp` and `pprint` (`seon.agent/make-ns!`), as a plain REPL does; what they write is the eval's `:out`. An eval's `*out*` and `*err*` are kept marked verbatim (`seon.print/code`, in `seon.repl/eval-code`), never as string literals. A check's console lines are the printer's whole text (`seon.main/shown`).

### Cut where a value is made

The places that cut a value where it is made, not in the printer, each with its reason.

- `seon.main/shown`: names a budget only to print whole (``:seon.print/full``).
- `seon.contract/kept`: root keeps no live value of another process's error ([errors](errors.md#an-error-keeps-data)); one past the part budget is kept as the printer's cut.
- `seon.contract/problems`, `seon.task/file-declarations`: a refused value may be infinite, lazy or a host object and travels as text, so its error keeps the printer's cut.
- `seon.turn/tell-error!`: the error a turn threw becomes its message's text, printed whole (`:seon.print/full`).
- `seon.db/invalid-entity`: the refused entity's identity in a write's refusal, at the part budget ([the write checks every entity it touches](storage.md#the-write-checks-every-entity-it-touches)).
- `seon.contract/first-problem`: the offending value in a contract error's message, under 60 tokens; the whole is in `:seon.error/problems`.
- A test child's reports: a child prints an assertion's expected and actual at its pipe; `seon.proofs/failures` keeps every one whole as data (`:seon.test/failures`), printed by the one printer where shown.

### Cuts in a display namespace that are not cuts of output

Uses of take, subs and the like in display code that choose what to find or read, not what to cut from output.

- `my.schema/unknown-key`: a key's text without its colon, to rank the nearest keys.
- `seon.render.ui/kname`: a keyword's text without its colon.
- `seon.ctx.status/form-at`: a form sliced from source the db holds by its delimiters.
- `seon.render.ui/markdown`, `seon.render.ui/inline`, `seon.render.ui/task-title`: Markdown split by its parse's offsets, nothing left out.
- `seon.ctx.search/relevance`: the pool a score reads, never shown.
- `seon.ctx.search/search`: its first `:k` hits with a count of the rest and the form that returns them.
- `seon.ctx.search/neighbours`: a bounded read of the reference graph for a score.
- `seon.agent/embed-written!`: a batch bound on one `:embed` request; the rest stay due.
- `my.agent/file-source`, `seon.repl/form-src`: a form's own text at a reader's offsets.
- `my.agent/lines`: the lines a to b the agent asked for.
- `seon.repl/do-parts`: a top-level `(do ...)` split into its inner forms by the reader's positions.
- `seon.repl/edit!`, `seon.repl/place`: rebuilding a source around an edit's offsets.
- `seon.repl/file-name`: a path relative to a root it is under.
- `seon.repl/streaming`: a Writer's `write(str, off, len)` slice.
- `seon.web/handler`, `seon.web/view-of`, `seon.server/mcp-reply`: parsing a request's path and a tool's name.
- `seon.ctx.search/near`: the whole line a repaired delimiter is on.
- `seon.render.proc/pass`: root's process keeps the newest `:seon.config.render.list/stream` events, a window of data.
