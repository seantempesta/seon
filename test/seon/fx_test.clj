(ns seon.fx-test
  "The effect owner and lane step-fns as plain functions (no graph): admission by lanes,
  priority order, budget refusal, the queue bound, and a lane answering a defect."
  (:require [clojure.test :refer [deftest is testing]] [seon.fx :as fx] [seon.config :as cfg]
            [clojure.core.async.flow :as flow]))

(defn- owner [lanes] (fx/owner-proc {:kind :t :lanes lanes :work nil :back nil}))
(defn- req [k & {:as more}] (merge {:req {:k k} :dest {:agent :a1 :op :fx-reply :k k}} more))

(deftest owner-admits-by-lanes-and-priority
  (let [[st o] (fx/owner-proc (owner 1) :in (req 1))
        _ (is (= [1] (map #(get-in % [:req :k]) (:to-lanes o))) "a free lane takes the first request")
        [st o] (fx/owner-proc st :in (req 2))
        _ (is (empty? (:to-lanes o)) "no free lane: queued")
        [st _] (fx/owner-proc st :in (req 3 :prio 5))
        [st o] (fx/owner-proc st :done {:dest {:agent :a1 :op :fx-reply :k 1} :outcome {:value 1}})]
    (is (= [{:dest {:agent :a1 :op :fx-reply :k 1} :r {:value 1}}] (:out o)) "the outcome goes to its asker")
    (is (= [3] (map #(get-in % [:req :k]) (:to-lanes o))) "the higher priority runs next")
    (is (= 1 (:busy st)))))

(deftest owner-refuses-a-spent-budget
  (let [[st _] (fx/owner-proc (owner 1) :in (req 1 :budget 10 :budget-key :task-a))
        [st _] (fx/owner-proc st :done {:dest {:agent :a1 :op :fx-reply :k 1} :outcome {:value 1} :budget-key :task-a :cost 10})
        [_ o] (fx/owner-proc st :in (req 2 :budget 10 :budget-key :task-a))]
    (is (= :seon.fx/budget-spent (get-in (first (:out o)) [:r :seon.error/type])))))

(deftest owner-stops-reading-at-its-bound
  (let [st (reduce (fn [st k] (first (fx/owner-proc st :in (req k)))) (owner 0) (range (cfg/value :seon.config.fx/buf)))]
    (is (false? ((::flow/input-filter st) :in)))
    (is (true? ((::flow/input-filter st) :done)))))

(deftest lane-answers-a-defect-as-data
  (let [st (fx/lane-proc {:kind :t :perform (fn [_] (throw (ex-info "boom" {}))) :owner :fx/t})
        [_ o] (fx/lane-proc st :work (assoc (req 1) :in-ns 0 :taken-ns 0))
        out (first (get o [:fx/t :done]))]
    (is (= :seon.fx/defect (get-in out [:outcome :seon.error/type])))
    (is (= {:agent :a1 :op :fx-reply :k 1} (:dest out)))))

;; ---- write-back answers what happened (audit FENCES #21) ------------------------------

(defn- scratch-dir []
  (let [d (str (System/getProperty "user.dir") "/target/tmp/fx-test-" (random-uuid))]
    (.mkdirs (java.io.File. d))
    d))

(deftest a-failed-rename-is-an-io-error-and-leaves-no-tmp
  (let [d (scratch-dir)
        target (str d "/occupied")
        _ (.mkdirs (java.io.File. (str target "/inside")))
        r (fx/write-file {:req {:path target :text "text"}})]
    (is (= :seon.fx/io-error (:seon.error/type r)) "renaming over a non-empty directory fails, and says so")
    (is (pos? (:errno r)) "with rename(2)'s errno")
    (is (= target (:path r)))
    (is (not (.exists (java.io.File. ^String (:tmp r)))) "the staged file is removed")
    (is (= ["occupied"] (vec (.list (java.io.File. d)))) "nothing else is left beside it"))
  (let [r (fx/write-file {:req {:path "/nonexistent-dir/x.clj" :text "text"}})]
    (is (= :seon.fx/io-error (:seon.error/type r)) "a missing directory: the stage itself fails")))

(deftest a-write-back-answers-the-bytes-on-disk
  (let [d (scratch-dir)
        p (str d "/f.clj")
        text "(def s \"héllo — world\")\n"
        r (fx/write-file {:req {:path p :text text}})]
    (is (= {:value {:bytes (.length (java.io.File. p)) :path p}} r))
    (is (= (+ (count text) 1 2) (get-in r [:value :bytes])) "bytes on disk, not characters: e-acute 2, em dash 3")
    (is (= text (slurp p)))
    (is (= ["f.clj"] (vec (.list (java.io.File. d)))) "no staged file left")))
