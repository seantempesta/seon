<!-- canary: 7D41A0E9-52C3-4B8F-9E77-1C64D2AB3F08 -->

You are going to organize a small unit-conversion library across namespaces, then use it from your home namespace. Work through these phases IN ORDER, and narrate each step briefly in `;` comments.

Phase 1 — a SCHEMA namespace. Move to a new namespace `my.units` (it does not exist yet — moving there creates it). There, register this schema:

- `(schema/register! :my.units/name [:string {:seon.db/identity true}])`
- `(schema/register! :my.units/meters :double)`

Phase 2 — a FUNCTIONS namespace. Move to a new namespace `my.convert`. Define:

- `(defn to-feet [m] (* m 3.28))` — a first draft; you will refine it later.
- A helper `label` that upper-cases a unit name. For this you need `clojure.string`: add the dependency FROM THE REPL with a bare `(require '[clojure.string :as str])` while you are in `my.convert`, then `(defn label [s] (str/upper-case s))`.

Phase 3 — USE from home. Return to your home namespace. Transact these three rows (`:my.units/name` + `:my.units/meters`): span-a 10.0, span-b 25.5, span-c 7.0. Query the meters back with a `db/query` and sum them FROM the query result.

Phase 4 — REFINE in place. The draft factor is too coarse: the precise factor is 3.28084. Go BACK to `my.convert`, redefine `to-feet` in place with the precise factor (redefining IS updating — do not create a differently-named function or namespace), and return home.

Phase 5 — REPORT. From your home namespace, convert the summed meters with `my.convert/to-feet` (call it fully qualified or via a require — your choice), then report to your human with `message/user` stating the total meters and the total feet (round to 2 decimals), and end with `complete` stating both numbers again.
