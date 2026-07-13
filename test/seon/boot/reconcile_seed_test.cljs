(ns seon.boot.reconcile-seed-test
  "Boot-path proof that `seon.client/boot-seed!` syncs its DECLARATIVE desired
   set (routes + skills) through `seon.state/reconcile!` — the keystone of the
   startup-load unification. The old boot-seed! raw-transacted routes/skills and
   NEVER retracted, so a route/skill dropped from the manifest left a stale datom
   live. Here we drive the REAL boot-seed! on a fresh :memory agent conn twice,
   flipping SEON_CONFIG between boots to a temp manifest that DROPS one core
   route, and assert the stale route is RETRACTED (absent), not merely skipped —
   the behavior a raw upsert-only seed could never give.

   On the SAME conn: boot 1 (empty manifest) seeds all 4 core routes; boot 2
   (manifest with `:seon.config/removes [:seon.route/agent-call]`) reconciles the
   route out. Env is saved + restored so the suite stays env-clean."
  (:require
    [cljs.test :refer [deftest is async]]
    [datahike.api :as d]
    [seon.client :as client]))

(def ^:private tmp-dir "tmp")

(defn- write-manifest!
  "Write `content` (an EDN string) to a temp manifest under tmp/ and return its
   path — SEON_CONFIG points the pod's config reader at it."
  [name content]
  (let [fs   (js/require "fs")
        path (str tmp-dir "/" name)]
    (.mkdirSync fs tmp-dir #js {:recursive true})
    (.writeFileSync fs path content)
    path))

(defn- set-config!
  "Set (or, for nil, delete) the SEON_CONFIG env var the config reader honors."
  [v]
  (let [env (.. js/globalThis -process -env)]
    (if v
      (aset env "SEON_CONFIG" v)
      (js-delete env "SEON_CONFIG"))))

(defn- route-names
  "The set of live `:seon.route/name` values in `conn` — the managed route
   population reconcile! syncs."
  [conn]
  (set (d/q '[:find [?n ...] :where [?e :seon.route/name ?n]] @conn)))

(deftest boot-seed-reconciles-routes-retract-on-drop
  (async done
    (let [prev-config (.. js/globalThis -process -env -SEON_CONFIG)
          full   (write-manifest! "reconcile-seed-full.edn" "{}")
          ;; A manifest that DROPS the POST action route. resolve-routes keys
          ;; off :seon.route/name; :seon.route/agent-call is seon.route/::agent-call.
          dropped (write-manifest! "reconcile-seed-drop.edn"
                                   (str "{:seon.config/routes "
                                        "[{:seon.config/removes [:seon.route/agent-call]}]}"))]
      (-> (client/open-agent-conn!)
          (.then
            (fn [conn]
              ;; BOOT 1 — empty manifest ⇒ all 4 core routes seeded.
              (set-config! full)
              (-> (client/boot-seed! {:seon.db/conn conn})
                  (.then
                    (fn [_]
                      (let [names (route-names conn)]
                        (is (contains? names :seon.route/agent-call)
                            "boot 1 (full manifest) seeds the agent-call route")
                        (is (contains? names :seon.route/root)
                            "…and the root route"))))
                  ;; BOOT 2 — same conn, manifest drops agent-call ⇒ RETRACTED.
                  (.then (fn [_] (set-config! dropped)))
                  (.then (fn [_] (client/boot-seed! {:seon.db/conn conn})))
                  (.then
                    (fn [_]
                      (let [names (route-names conn)]
                        (is (not (contains? names :seon.route/agent-call))
                            "boot 2 RETRACTS the dropped route (not just skips it) — the reconcile keystone")
                        (is (contains? names :seon.route/root)
                            "a route still in the desired set survives")
                        (is (contains? names :seon.route/agent)
                            "…as does the agent page route"))))
                  ;; BOOT 3 — no selected config means PRESERVE, not silently
                  ;; reapply config/system.edn or an implicit empty manifest.
                  (.then (fn [_] (set-config! nil)))
                  (.then (fn [_] (client/boot-seed! {:seon.db/conn conn})))
                  (.then
                    (fn [_]
                      (let [names (route-names conn)]
                        (is (not (contains? names :seon.route/agent-call))
                            "config-free boot preserves the prior desired set")
                        (is (contains? names :seon.route/root)
                            "config-free boot preserves retained routes"))))
                  (.then (fn [_] (js/Promise.resolve conn))))))
          (.then (fn [_] (set-config! prev-config) (done)))
          (.catch (fn [e]
                    (set-config! prev-config)
                    (is false (str "unexpected rejection: " e))
                    (done)))))))
