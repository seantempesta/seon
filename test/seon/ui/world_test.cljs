(ns seon.ui.world-test
  "Behavior + never-crash regressions for the per-agent world layout
   (`seon.ui.world/world-layout`) — the `/agent/{id}` page that places an
   agent's OWN html-rendering `:seon.agent/ctx` blocks as TILES via the
   `seon.render/slot` primitive.

   Style (matches seon.web.datastar-test): assert MECHANISM — a tile APPEARS
   for an html block and an ai-only block contributes NONE; the focal comms
   block lands in the canvas; canvas selection is DATA (`:canvas` over
   `:transcript`); pure-fn-of-(db,id) determinism + per-agent isolation;
   NEVER-CRASH. Never pin rendered HTML/prose (refactoring surfaces) — assert
   the stable DOM markers `data-slot=\"<name>\"` / `#world-canvas` / `#world`.
   Each test uses a fresh ISOLATED `:memory` conn carrying the pod's full
   schema (`client/open-agent-conn!`), never the live cluster store, so the
   test is self-contained and a pure function of the db value it builds."
  (:require
    [cljs.test :refer [deftest is testing async]]
    [clojure.string :as str]
    [seon.client :as client]
    [seon.db :as db]
    [seon.render :as render]
    [seon.ui.html :as html]
    [seon.ui.world :as world]))

;; Valid 14-char ids (`:seon.db/id` is [:string {:min 14 :max 14}]).
(def ^:private agent-a "world-aaaa0001")
(def ^:private agent-b "world-bbbb0002")

(defn- mixed-blocks
  "A mixed block set: a comms tile (html+ai) named `comms-name`, a plain tile
   (html-only), and an ai-only block (prompt-only, contributes NO tile)."
  [comms-name]
  [{:seon.agent.ctx/name comms-name :seon.agent.ctx/priority 100
    :seon.render/html [:div {:class "comms"} "COMMS-BODY"]
    :seon.render/ai "comms prose"}
   {:seon.agent.ctx/name :soul :seon.agent.ctx/priority 5
    :seon.render/html [:div "SOUL-BODY"]}
   {:seon.agent.ctx/name :namespaces :seon.agent.ctx/priority 20
    :seon.render/ai "ai-only block — no tile"}])

(defn- with-agents
  "Fresh isolated `:memory` conn (full pod schema). Transact each `[id blocks]`
   pair as an agent owning `blocks` in `:seon.agent/ctx`, then call
   `(body conn)` (→ Promise|value). Returns a Promise. `world-layout` reads
   the explicit db value, so the tests stay pure functions of a db value."
  [agents body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (if (seq agents)
                 (-> (db/transact!
                       {:seon.db/conn    conn
                        :seon.db/tx-data (mapv (fn [[id bs]]
                                                 {:seon.agent/id id :seon.agent/ctx bs})
                                               agents)})
                     (.then (fn [_] (body conn))))
                 (body conn))))))

;; ============================================================
;; 1. Tiles = the html-rendering blocks; ai-only blocks contribute NONE.
;; Assert by the stable `data-slot="<name>"` marker the slot primitive emits.
;; ============================================================

(deftest world-layout-tiles-html-blocks-not-ai-only
  (async done
    (-> (with-agents [[agent-a (mixed-blocks :transcript)]]
          (fn [conn]
            (let [view (world/world-layout @conn agent-a)
                  s    (html/->string view)]
              (testing "the root is the #world morph target the shim page declares"
                (is (vector? view) "world-layout returns hiccup, not a thrown error")
                (is (= "world" (:id (second view))) "root element carries id=world"))
              (testing "an html-rendering block becomes a tile (slot data-slot marker)"
                (is (str/includes? s "data-slot=\"soul\"")
                    "the soul html block renders as a tile"))
              (testing "an ai-only block contributes NO tile (prompt-only, zero tokens here)"
                (is (not (str/includes? s "data-slot=\"namespaces\""))
                    "the namespaces ai-only block has no tile")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; 2. The focal comms block lands in the prominent canvas region.
;; ============================================================

(deftest world-layout-places-comms-block-in-the-canvas
  (async done
    (-> (with-agents [[agent-a (mixed-blocks :transcript)]]
          (fn [conn]
            (let [s          (html/->string (world/world-layout @conn agent-a))
                  canvas-idx (str/index-of s "world-canvas")
                  tr-idx     (str/index-of s "data-slot=\"transcript\"")]
              (testing "the focal comms block (:transcript) sits inside #world-canvas"
                (is (some? canvas-idx) "the canvas region renders")
                (is (and tr-idx (< canvas-idx tr-idx))
                    "the transcript tile is placed within the canvas region")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; 3. Canvas selection is DATA, not a flag — a `:canvas` block wins over
;; `:transcript`; the third party names its comms block and gets the canvas.
;; ============================================================

(deftest world-layout-canvas-name-is-data-not-a-flag
  (async done
    (-> (with-agents [[agent-a (conj (mixed-blocks :transcript)
                                     {:seon.agent.ctx/name :canvas :seon.agent.ctx/priority 1
                                      :seon.render/html [:div "CANVAS-BODY"]})]]
          (fn [conn]
            (let [s          (html/->string (world/world-layout @conn agent-a))
                  canvas-idx (str/index-of s "world-canvas")
                  cv-idx     (str/index-of s "data-slot=\"canvas\"")
                  tr-idx     (str/index-of s "data-slot=\"transcript\"")]
              (testing "when a :canvas block exists it is the focal block, not :transcript"
                (is (and canvas-idx cv-idx (< canvas-idx cv-idx))
                    "the :canvas block is placed in the canvas region")
                (is (and tr-idx (< cv-idx tr-idx))
                    ":transcript drops to the tile scroll below the canvas")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; 4. Pure fn of (db, id) + per-agent isolation — rendering A never surfaces
;; B's blocks (each agent owns its OWN ctx; :name is not a datahike identity).
;; ============================================================

(deftest world-layout-is-pure-and-per-agent-isolated
  (async done
    (-> (with-agents [[agent-a (mixed-blocks :transcript)]
                      [agent-b [{:seon.agent.ctx/name :soul :seon.agent.ctx/priority 5
                                 :seon.render/html [:div "B-ONLY-SOUL"]}]]]
          (fn [conn]
            (let [dbv @conn
                  sa  (html/->string (world/world-layout dbv agent-a))]
              (testing "same db value twice → identical output (pure fn of db,id)"
                (is (= sa (html/->string (world/world-layout dbv agent-a)))))
              (testing "rendering A surfaces A's OWN soul tile, never B's"
                (is (str/includes? sa "SOUL-BODY") "A's own soul tile renders")
                (is (not (str/includes? sa "B-ONLY-SOUL"))
                    "B's same-named block never leaks into A's world")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; 5. NEVER-CRASH — a nonexistent agent renders a valid #world (the eid
;; resolves via a non-throwing query → no tiles), never a propagated throw.
;; ============================================================

(deftest world-layout-missing-agent-never-crashes
  (async done
    (-> (with-agents []
          (fn [conn]
            (let [view (try (world/world-layout @conn "ghost-agent-xx")
                            (catch :default e {:threw (str e)}))
                  s    (html/->string view)]
              (testing "a nonexistent agent renders #world WITHOUT throwing"
                (is (vector? view) "returned hiccup, not a thrown error")
                (is (= "world" (:id (second view))) "root is the #world morph target")
                (is (str/includes? s "world-empty")
                    "an empty world surfaces a no-tiles note")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; 6. NEVER-CRASH — a throwing slot render is CONTAINED: world-layout
;; degrades to a visible #world-error and never propagates the throw.
;; ============================================================

(deftest world-layout-survives-a-throwing-slot
  (async done
    (-> (with-agents [[agent-a (mixed-blocks :transcript)]]
          (fn [conn]
            (let [dbv @conn]
              (with-redefs [render/slot (fn [_ _] (throw (js/Error. "slot boom")))]
                (let [view (try (world/world-layout dbv agent-a)
                                (catch :default e {:propagated (str e)}))
                      s    (html/->string view)]
                  (testing "a throwing slot is contained — world-layout never propagates"
                    (is (vector? view) "render did not propagate the throw")
                    (is (= "world" (:id (second view))) "still the #world morph target")
                    (is (str/includes? s "world-error")
                        "degrades to a visible #world-error rather than crashing")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
