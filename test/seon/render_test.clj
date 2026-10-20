(ns seon.render-test
  "Renders (lane W2): selection (override key, schema-declared, printer), root's AI face,
  and the render proc's step as a plain function: inputs before render, bytes before
  send, a new agent appended, a retired one removed, keyframes composed from what was
  retained. Run: bin/test seon.render-test"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str] [clojure.core.async :as a]
            [seon.render :as r] [seon.render.ui :as ui] [seon.render.ai :as ai] [seon.render.proc :as p]
            [seon.web.html :as h]))

(def ^:private task
  {:seon.task/id "calc/add" :seon.task/ns 'calc :seon.task/name "add" :seon.task/plan "Make `app.calc/add` pass"
   :seon.task/execution :running :seon.task/branch :task-calc-add :seon.task/proofs #{'app.proofs/add-test}
   :seon.task/needs #{} :seon.task/kind :work
   :seon.task/accumulated {:plan "Make `app.calc/add` pass" :proofs #{'app.proofs/add-test}}})

(deftest selection
  (testing "the value's own override key wins: data as is"
    (is (= [:b "mine"] (r/html {:a 1 :seon.render/html [:b "mine"]} {:seon.render/schema :seon.task/task}))))
  (testing "the schema's declared render, named by the producer"
    (is (= 'seon.render.ui/task-box (r/declared :seon.task/task :html)))
    (is (str/includes? (h/html (r/html task {:seon.render/schema :seon.task/task})) "task · calc/add")))
  (testing "a pulled entity's schema is the one its identity attribute names"
    (is (= :seon.attempt/attempt (r/schema-of {:seon.attempt/id "x#1"} nil)))
    (is (str/includes? (h/html (r/html {:seon.attempt/id "calc/add#1" :seon.attempt/agent :a3 :seon.attempt/status :running} {}))
                       "href=\"/agent/a3\"")))
  (testing "no declaration: the one value printer's hiccup"
    (let [out (r/html {:k [1 2 3]} {})]
      (is (= "seon-data-panel" (get-in out [1 :class])))))
  (testing "both faces when asked; the AI face of an undeclared value is the printer's text"
    (let [out (r/render {:k 1} {:seon.render/faces #{:html :ai}})]
      (is (= "{:k 1}" (:seon.render/ai out)))
      (is (vector? (:seon.render/html out))))))

(def ^:private sys
  {:agents {:a1 {:status :idle :tests {:pass 1 :fail 1 :error 0 :reports [{:type :fail :var 'agent.a1/c}]}}
            :a2 {:status :dead}
            :a3 {:status :killed :tests {:pass 0 :fail 3 :error 0}}}
   :health {:api {:status :running :count 3 :state {}} :writer :unknown}
   :writer {:tx-count 4 :tx-per-s 2.0} :server {:status :up :pid 7} :main {:t 9 :datoms 100}
   :tasks {} :attempts {} :events [] :root-errors [{:pid :render :message "boom"}] :now-ms 0})

(deftest root-context
  (let [text (r/ai sys {:seon.render/schema :seon.render/system :seon.render/budget 700})
        lines (str/split-lines text)]
    (testing "problems first: the proc that did not answer, root's defect, the live red agent, the dead one"
      (is (= "# problems" (second lines)))
      (is (not (re-find #"\d\d:\d\d:\d\d" (first lines))) "no clock: an unchanged system gives the same text")
      (is (some #(str/includes? % "proc writer UNKNOWN") lines))
      (is (some #(str/includes? % "defect in render: boom") lines))
      (is (some #(str/includes? % "a1 idle: tests 1 pass 1 fail 0 error (agent.a1/c)") lines))
      (is (some #(str/includes? % "a2 dead: its process died") lines)))
    (testing "an agent root stopped is not a problem, whatever its last run said"
      (is (not-any? #(str/starts-with? % "- a3") lines)))
    (testing "over the budget: later sections are named, not silently cut"
      (let [small (r/ai sys {:seon.render/schema :seon.render/system :seon.render/budget 40})]
        (is (str/includes? small "not shown, over the budget"))
        (is (<= (ai/tokens (first (str/split small #"\n\(not shown"))) 40))))))

(defn- answer
  "The render proc's step on an api :state answer for st (the request it had in flight)."
  [st view]
  (let [st (assoc st :pending {1 :state} :asked {1 (System/currentTimeMillis)} :main-tx (get-in view [:writer :tx-count]))]
    (#'p/render-proc st :answers {:tag 1 :r view})))

(def ^:private view0
  {:agents {:a1 {:branch :agent-1 :status :idle :task "t1" :evals []}}
   :writer {:tx-count 1 :recent []} :server {:status :up} :stats {} :store {} :boot {}})

(defn- fresh-state []
  {:rid 1 :pending {} :watch #{:root} :waiting {} :kf-wanted #{} :new-events [] :events [] :event-rows []
   :views {} :zoom {} :series {} :merges 0 :health {:api {:status :running :count 1 :state {}}} :main {:t 1}})

(deftest render-step
  (let [[st1 out1] (answer (fresh-state) view0)
        patch1 (first (filter #(= :patch (:op %)) (:to-web out1)))]
    (testing "the first pass renders every unit and sends the agents' index"
      (is (some #(= "t-agents" (first %)) (:frags patch1)))
      (is (= "#grid" (ffirst (:appends patch1))))
      (is (str/includes? (second (first (:appends patch1))) "id=\"cb-a1\""))
      (is (= {:a1 {:branch :agent-1 :status :idle}} (:agents (first (filter #(= :index (:op %)) (:to-web out1)))))))
    (testing "nothing changed: no unit renders, no patch is sent"
      (let [[st2 out2] (answer st1 view0)]
        (is (empty? (filter #(= :patch (:op %)) (:to-web out2))))
        (is (= (get-in st1 [:counts :rendered]) (get-in st2 [:counts :rendered])))))
    (testing "one agent's evaluation: its card and the swarm re-render; the other tiles do not"
      (let [view1 (assoc-in view0 [:agents :a1 :evals] [{:id 0 :tag :ret :form "(+ 1 2)" :printed "3" :ms 0.1}])
            [_ out] (answer st1 view1)
            ids (set (map first (:frags (first (filter #(= :patch (:op %)) (:to-web out))))))]
        (is (contains? ids "cb-a1"))
        (is (contains? ids "t-agents"))
        (is (not (contains? ids "t-writer")))
        (is (not (contains? ids "t-server")))))
    (testing "a new agent: its whole card appended to the grid, its body inside"
      (let [view2 (assoc-in view0 [:agents :a2] {:branch :agent-2 :status :spawning :task "t2" :evals []})
            [st3 out] (answer st1 view2)
            patch (first (filter #(= :patch (:op %)) (:to-web out)))
            [[sel html]] (:appends patch)]
        (is (= "#grid" sel))
        (is (str/includes? html "id=\"card-a2\""))
        (is (str/includes? html "id=\"cb-a2\""))
        (testing "an agent ended long ago leaves the grid: its card removed"
          (let [view3 (assoc-in view2 [:agents :a2] {:branch :agent-2 :status :killed :ended-ms 0 :evals []})
                [_ out] (answer st3 view3)
                patch (first (filter #(= :patch (:op %)) (:to-web out)))]
            (is (= ["card-a2"] (:removes patch)))))))
    (testing "a keyframe is composed from the retained fragments, rendering nothing"
      (let [[st out] (#'p/render-proc st1 :from-web {:op :keyframe :view :root})
            html (:html (first (:to-web out)))]
        (is (= (get-in st1 [:counts :rendered]) (get-in st [:counts :rendered])))
        (is (str/includes? html "id=\"cb-a1\""))
        (is (not (str/includes? html "<!--s:")))))))

(deftest compose-fills-slots-recursively
  (is (= "<a><b>x</b></a>" (p/compose "<a><!--s:b--></a>" {"b" {:html "<b><!--s:c--></b>"} "c" {:html "x"}}))))

(deftest stream-prepends-new-rows
  (let [[st1 _] (answer (fresh-state) view0)
        st1 (assoc st1 :new-events [{:type :ready :agent :a1 :at-ms 1} {:type :killed :agent :a1 :at-ms 2}])
        [st2 out] (answer st1 view0)
        patch (first (filter #(= :patch (:op %)) (:to-web out)))
        [[sel html]] (:prepends patch)]
    (testing "new events: their rows prepended, newest first, each with its own id; the list itself not re-sent"
      (is (= "#events" sel))
      (is (< (str/index-of html "id=\"ev-2\"") (str/index-of html "id=\"ev-1\"")))
      (is (not-any? #(= "events" (first %)) (:frags patch))))
    (testing "past the cap, the oldest rows are removed by id"
      (let [st (assoc st2 :new-events (vec (for [i (range p/EVENT-CAP)] {:type :ready :agent :a1 :at-ms i})))
            [_ out] (answer st view0)
            patch (first (filter #(= :patch (:op %)) (:to-web out)))]
        (is (= #{"ev-1" "ev-2"} (set (filter #(str/starts-with? % "ev-") (:removes patch)))))))))
