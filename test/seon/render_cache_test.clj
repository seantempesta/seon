(ns seon.render-cache-test
  "The render cache belongs to the branch a context derives in."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.env :as env]
            [seon.render :as render]
            [seon.render.web :as web]
            [seon.test-support :as support]))

(defn- root-render-state
  "One render-pass state for agent `root` on the handle's own branch."
  [handle]
  (let [ctx (:seon.sci.eval/ctx handle)]
    {:seon.turn.loop/cluster
     (support/cluster-handle
      {:seon.cluster/name (:seon.boot/cluster-name (env/of ctx))
       :seon.db/connection (:seon.db/connection handle)
       :seon.sci.eval/ctx ctx
       :seon.config/on-core-error :record
       :seon.db.process/id "render-cache-test"})
     :seon.render.web/registration (atom {"root" 1})
     :seon.render.web/latest-packages (atom {})
     :seon.render.web/root-agent-id "root"
     ::web/streams {}
     ::web/passes 0}))

(defn- speculative-scope
  "The branch scope of a ctx whose custody is a `with` value of `connection`'s head."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/connection]
                  [:map-of :qualified-keyword :seon.schema/value]]}
  [ctx connection]
  (render/branch-scope
   (assoc ctx :seon.sci.eval/custody {:seon.db/db (:db-after (d/with (db/db connection) []))})))

(defn- root-package
  [ctx]
  (get-in @(render/shared-cache ctx) [::web/packages "root"]))

;; 2026-09-23 (landing lane-page-key-2026-09-23.md, out of scope 2): the cache
;; rode the environment, and a cluster handle carries its live environment
;; reference. Every context the agent entrance acquired from it (test members,
;; their fixtures) copied the parent's cache atom, so a fixture branch's render
;; pass replaced its parent's `::packages` and web tests using `root` raced the
;; live `root` page.
(deftest a-fixture-branch-rendering-root-leaves-the-parent-branch-packages-unchanged
  (let [member (support/execution-handle nil)
        parent-ctx (:seon.sci.eval/ctx member)
        ;; The parent serves like a cluster: its handle carries the live
        ;; environment reference, as `:seon.turn.loop/cluster` does.
        parent (env/carry-state member (:seon.sci.eval/projection-state parent-ctx))
        parent-connection (:seon.db/connection parent)
        _ (#'web/render-pass (root-render-state parent))
        parent-cache (render/shared-cache parent-ctx)
        parent-package (root-package parent-ctx)
        parent-basis (db/basis-t (db/db parent-connection))]
    (testing "the parent branch serves root from its own cache"
      (is (= (render/branch-scope parent-ctx)
             (select-keys (:cache-context @parent-connection)
                          [:datahike.cache/connection-id :datahike.cache/generation])))
      (is (= parent-basis (:seon.render.package/basis-transaction parent-package)))
      (is (= (render/branch-scope parent-ctx) (speculative-scope parent-ctx parent-connection))
          "a speculative value of the parent's head derives in the parent's branch"))
    ;; The fixture is the entrance's isolated fork of the serving parent,
    ;; acquired after the parent's cache existed.
    (let [fork (agent/acquire-context!
                parent nil {:seon.agent/isolate? true
                            :seon.cluster.registry/from (db/commit-id (db/db parent-connection))})]
      (try
        (let [connection (:seon.db/connection fork)
              fork-ctx (:seon.sci.eval/ctx fork)
              ;; A message only the fork's root page shows.
              _ (support/transacted!
                 connection
                 [{:seon.message/id "render-cache-fork-message"
                   :seon.message/to [:seon.agent/id "root"]
                   :seon.message/content "Only the fixture branch has this message."}])
              _ (#'web/render-pass (root-render-state fork))
              fork-basis (db/basis-t (db/db connection))
              fork-package (root-package fork-ctx)]
          (testing "the fork renders root on its own branch"
            (is (not= (render/branch-scope parent-ctx) (render/branch-scope fork-ctx)))
            (is (= (render/branch-scope fork-ctx) (speculative-scope fork-ctx connection))
                "a speculative value of the fork's head derives in the fork's branch")
            (is (not (identical? parent-cache (render/shared-cache fork-ctx))))
            (is (identical? (render/shared-cache fork-ctx) (render/shared-cache fork-ctx))
                "one branch reuses its cache")
            (is (= fork-basis (:seon.render.package/basis-transaction fork-package)))
            (is (not= parent-basis fork-basis))
            (is (not= (:seon.render.package/keyframe parent-package)
                      (:seon.render.package/keyframe fork-package))
                "the fork's root page shows the fork's message"))
          (testing "the parent's cache and packages are unchanged"
            (is (identical? parent-cache (render/shared-cache parent-ctx)))
            (is (= parent-package (root-package parent-ctx)))
            (is (not-any? #(= fork-basis (:seon.render.package/basis-transaction %))
                          (vals (::web/packages @parent-cache))))))
        (finally (agent/release-context! fork))))))
