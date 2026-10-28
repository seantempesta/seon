# The dashboard

Root's dashboard is a live web page of the whole system, served on 127.0.0.1 by two procs of the root graph: the render proc decides what changed and renders it, and the web proc owns the listener and the tabs and renders nothing.

The port is `:seon.config.web/port` (`bin/ports` gives each checkout its own; the owner's main tree uses 7777; 0 serves nothing). The page is a render like every other view ([render](render.md#the-page)): the render of a ctx (root's, an agent's) through the one walk, each value by its schema's render over the one printer, laid out by Tailwind's classes, with every number measured by the system and carried in the value.

## The render proc

The render proc turns root's events into patches per view, one pass per burst, with no beat: ```seon.render.proc/render-proc```.

An event asks the api for root's view; while that request is in flight more events only queue, so a burst becomes one pass and no step sleeps. The answer is joined with each proc's health and the events into root's process value, `seon.render.proc/process-value` (`:seon.render/process`), which is part of root's ctx: it is assoc'd in when it changed and only the tile reading it draws again. Each page a tab watches is the render of a ctx through the one walk (```seon.render/walker```): root's page is root's walk of its ctx on main (`seon.render.proc/root-walk`), an agent's page its own walk's components, drawn by its process (or by root from its branch), and ```seon.render.ui/page-chrome``` places them as units ([render](render.md#components)). A unit is sent only when its revision moved, and then only when its bytes changed. A tab that opened or fell behind gets a keyframe composed from the retained fragments (`seon.render.proc/compose`), rendering nothing. What root reads (`/root/debug`, `/context.txt`) is its page's hiccup, printed once a pass. The render proc holds the reconciler's index of main and moves it by the watched datoms each commit changed (`seon.render.proc/main-info`), so a commit costs what it moved, never a rebuild of every task (`seon.render-cost-test/a-move-of-main-costs-what-moved`).

Health is what flows: a proc is healthy unless a problem was recorded of it, and `bin/check idle` proves an idle root with a tab open steps nothing ([render](render.md#health-is-what-flows)).

## The web proc

The web proc owns the HTTP listener and each tab's server-sent-events channel: ```seon.web/web-proc```.

`seon.web/handler` is the edge in front of it: `/` answers root's page, `/agent/<id>` an agent's and `/agent/<id>/debug` (`/root/debug`) what its model reads, `/more?view=` asks for a page's next rows as its list's end scrolls into view, `/app.css` is the stylesheet `bin/build css` made ([render](render.md#tailwind)), `/feed?view=` opens a tab's event stream, `/context.txt` answers root's context as the model would read it, and ``/render.edn`` and ``/web.edn`` the procs' own numbers. A patch is serialized once (Datastar's `datastar-patch-elements`) and offered to every tab of its view; a full channel marks the tab stale and it gets a keyframe, morphed by id so inputs survive; a closed one is dropped.

Hiccup becomes HTML through one serializer, ```seon.web.html/html```: attributes in sorted order, so the same value is always the same bytes, which is what lets the render proc compare bytes.

## Callbacks are data

A button on the page is a call named as data, posted to the one stateless `/call` route: `{:seon/call 'ns/fn}` on an element, with ``:seon/args`` and ``:seon/agent`` (`seon.web.html/call-url`).

A `seon.root` function (`seon.root/spawn!`, `submit!`, `run-tests!`, `merge-main!`, `clone!`, `kill!`, `message!`, `release!`, `opened!`) becomes the request it names (`seon.web/requests`, through `seon.root/request`, which refuses any other name as data); a `submit!` is an evaluation recorded with origin `:ui`, and `all` as its agent sends it to every live agent; `seon.root/interrupt!` is a signal to a process, sent by the web proc itself. Any other function runs in the agent as an eval of ```seon.web/ui-call```, origin `:ui`, refused as data (`:seon.render/not-callable`) unless its var is marked `^:seon/callable`. The page calls what an agent or the server would call; there is no second path.
