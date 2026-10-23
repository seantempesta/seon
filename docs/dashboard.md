# The dashboard

Root's dashboard is a live web page of the whole system, served on 127.0.0.1 by two procs of the root graph: the render proc decides what changed and renders it, and the web proc owns the listener and the tabs and renders nothing.

The port is `:seon.config.web/port` (`bin/ports` gives each checkout its own; the owner's main tree uses 7777; 0 serves nothing). The page is a render like every other view ([render](render.md#the-page)): an entity's walk, each part by its schema's render over the one printer, laid out by Tailwind's classes, with every number measured by the system and carried in the value.

## The render proc

The render proc turns root's events into patches per view, one pass per burst, with no beat: ```seon.render.proc/render-proc```.

An event asks the api for root's view; while that request is in flight more events only queue, so a burst becomes one pass and no step sleeps. The answer is joined with each proc's health, main's tasks and attempts and the events into one system value, `seon.render.proc/system-value` (`:seon.render/system`), whose `:ai` face is root's context. Each page a tab watches is an entity's: ```seon.render/page``` walks it, ```seon.render.ui/page``` lays it out into units ([render](render.md#the-page)). Each unit names the exact value it reads, so a unit renders only when its input changed and sends a patch only when its bytes changed. A tab that opened or fell behind gets a keyframe composed from the retained fragments (`seon.render.proc/compose`), rendering nothing. Every task row reads `seon.render.proc/task-rows`, over the reconciler's one derivation of a task's state ([root](root.md#root-causes-at-quiescence)).

Health is what flows: a proc is healthy unless a problem was recorded of it, and `bin/check idle` proves an idle root with a tab open steps nothing ([render](render.md#health-is-what-flows)).

## The web proc

The web proc owns the HTTP listener and each tab's server-sent-events channel: ```seon.web/web-proc```.

`seon.web/handler` is the edge in front of it: `/`, `/agent/<id>` and `/walk/<eid>` answer an entity's page, `/more?view=` asks for a page's next rows as its list's end scrolls into view, `/app.css` is the stylesheet `bin/build css` made ([render](render.md#tailwind)), `/feed?view=` opens a tab's event stream, `/context.txt` answers root's context as the model would read it, and ``/render.edn`` and ``/web.edn`` the procs' own numbers. A patch is serialized once (Datastar's `datastar-patch-elements`) and offered to every tab of its view; a full channel marks the tab stale and it gets a keyframe, morphed by id so inputs survive; a closed one is dropped.

Hiccup becomes HTML through one serializer, ```seon.web.html/html```: attributes in sorted order, so the same value is always the same bytes, which is what lets the render proc compare bytes.

## Callbacks are data

A button on the page is a call named as data, posted to the one stateless `/call` route: `{:seon/call 'ns/fn}` on an element, with ``:seon/args`` and ``:seon/agent`` (`seon.web.html/call-url`).

A `seon.root` function (`seon.root/spawn!`, `submit!`, `run-tests!`, `merge!`, `clone!`, `kill!`, ...) becomes the request it sends (`seon.web/requests`, through `seon.root/request`), recorded on main as root's evaluation with origin `:ui`; `seon.root/interrupt!` is a signal to a process, sent by the web proc itself. Any other function runs in the agent as an eval of ```seon.web/ui-call```, origin `:ui`, refused as data unless its var is marked `^:seon/callable`. The page calls what an agent or the server would call; there is no second path.
