(ns seon.server.boot
  "Wire-server boot entry — the platform-lane glue that composes the listener
   contributors WITHOUT coupling them to each other.

   Why this ns exists: `seon.server.wire` deliberately does NOT `require`
   `seon.server.reactive` (the P1 decoupling — platform must boot/test the
   wire-server with no reactive ns present). But at runtime both need to load so
   each registers its OWN `register-on-ensure-db-hook!` (wire → `::raw-broadcast`,
   reactive → `::reactive`) and its OWN schema. Requiring reactive from a thin
   boot ns — not from `wire.clj` — keeps `wire.clj` reactive-free while ensuring
   reactive is actually on the load path when the server starts. This is
   review coordination item 2 (`:seon.fn` / reactive schema on the load path)
   and the platform half of m3-prep R2.

   Loading `seon.server.reactive` here has these side effects at ns-load:
   - registers the reactive data-boundary schemas (`:seon.subscription/*`,
     `:seon.server.reactive/*`, `:seon.fn/*`, `:seon.render/ai`); and
   - (once reactive lands its integration plug) registers reactive's
     `::reactive` on-ensure-db hook, so every conn the registry opens gets the
     reactive engine wired alongside the raw broadcaster.

   The wire-server is launched via `:writer` → `-m seon.server.boot` (deps.edn);
   `-main` delegates straight to `wire/-main`. The reactive op-wrappers
   (`register-subscription` / `unregister-subscription` `handle-op` methods) will
   live HERE too — they need both `wire` (the multimethod) and `reactive` (the
   pure fns), so this is their natural home, keeping `wire.clj` decoupled."
  (:require [seon.server.wire :as wire]
            ;; side-effecting load: registers reactive's schemas + on-ensure-db
            ;; hook. Referenced for the load, not for a var (yet — Phase B adds
            ;; the op-wrapper defmethods that call into it).
            [seon.server.reactive])
  (:gen-class))

(defn -main
  "Boot the wire-server. Both `seon.server.wire` and `seon.server.reactive` are
   loaded by this ns's requires, so their on-ensure-db hooks + schemas are
   registered before any `ensure-db` opens a conn. Delegates to `wire/-main`."
  [& args]
  (apply wire/-main args))
