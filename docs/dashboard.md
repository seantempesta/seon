# The dashboard

Root's dashboard is a live web page of the whole system, served on 127.0.0.1 by two procs of the root graph: the render proc decides what changed and renders it, and the web proc owns the listener and the tabs and renders nothing.

The port is `:seon.config.web/port` (`bin/ports` gives each checkout its own; 0 serves nothing). The page is a render like every other view ([render](render.md#the-page)): a ctx through the one walk, each value by its schema's render over the one printer, laid out with Tailwind's classes.

## The render proc

The render proc turns root's events into patches per view, one pass per burst, with no beat: `seon.render.proc/render-proc`.

An event asks the api for root's view; while that request is in flight more events only queue, so a burst is one pass. The answer, with each proc's health, is root's process value (`seon.render.proc/process-value`, `:seon.render/process`), part of root's ctx, so only the tile reading it draws again. Each page a tab watches is a ctx through the one walk (`seon.render/walker`): root's page is root's ctx on main (`seon.render.proc/root-walk`), an agent's page its own walk, and `seon.render.ui/page-chrome` places the components ([render](render.md#components)). A unit is sent only when its revision moved and its bytes changed; a tab that opened or fell behind gets a keyframe composed from the kept fragments (`seon.render.proc/compose`). The render proc moves its reconciler index by the watched datoms each commit changed (`seon.render.proc/main-info`), so a commit costs what it moved (`seon.render-cost-test/a-move-of-main-costs-what-moved`).

Health is what flows: a proc is healthy unless a problem was recorded of it, and `bin/check idle` proves an idle root with a tab open steps nothing ([render](render.md#health-is-what-flows)).

## The web proc

The web proc owns the HTTP listener and each tab's server-sent-events channel: `seon.web/web-proc`.

`seon.web/handler` is the edge in front of it; its routes:
- `/`: the chat home; `/root`: root's page; `/root/debug`: root's session's requests, as any agent's, beside what the system reads;
- `/agent/<id>`, `/user/<name>`: an agent's page (with `/ctx`, its `@ctx` printed, and `/debug`, what its model was sent);
- `/open`, `/at`: a POST naming which cut is open and which turn a page shows;
- `/feed?view=`: a tab's event stream;
- `/call`: a callback ([below](#callbacks-are-data));
- `/app.css` (made by `bin/build css`, [render](render.md#tailwind)), `/context.txt` (root's context as the model reads it), ``/render.edn`` and ``/web.edn`` (the procs' numbers).

A patch is serialized once (Datastar's `datastar-patch-elements`) and offered to every tab of its view; a full channel marks the tab stale and it gets a keyframe, morphed by id so inputs survive; a closed one is dropped. Every POST from another origin is refused with 403 (`seon.web/same-origin?`).

Hiccup becomes HTML through one serializer, `seon.web.html/html`, attributes in sorted order, so the same value is always the same bytes.

## Callbacks are data

A button is a call named as data, posted to the one stateless `/call` route: `{:seon/call 'ns/fn}` on an element, with ``:seon/args`` and ``:seon/agent`` (`seon.web.html/call-url`).

A `seon.system` function (`spawn!`, `submit!`, `run-tests!`, `merge-main!`, `clone!`, `kill!`, `message!`, `release!`, `opened!`) becomes the request it names (`seon.web/requests`, through `seon.system/request`, which refuses any other name as data); a `submit!` is an eval with origin `:ui`, and agent `all` sends it to every live agent. `seon.system/interrupt!` is a signal the web proc sends itself. Any other function runs in the agent as an eval of `seon.web/ui-call`, refused (`:seon.render/not-callable`) unless its var is marked `^:seon/callable`. The page calls what an agent or the server would call; there is no second path.
