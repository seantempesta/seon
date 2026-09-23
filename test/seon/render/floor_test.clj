(ns seon.render.floor-test
  "The floor shows any Throwable as bounded text, whatever its ex-data holds."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.render.value :as value]))

(deftype Hostile []
  Object
  (toString [_] (throw (IllegalStateException. "toString must not run"))))

(defmethod print-method Hostile [_ _]
  (throw (IllegalStateException. "print-method must not run")))

(defn- framed
  "`throwable` whose stack is exactly `frames`; a nil file is a frame the JVM
  reports without one."
  [^Throwable throwable frames]
  (doto throwable
    (.setStackTrace (into-array StackTraceElement
                                (map (fn [[class-name file line]]
                                       (StackTraceElement. class-name "invokeStatic" file (int line)))
                                     frames)))))

(deftest any-value-in-a-chain-prints-bounded-and-readable
  (let [realized (atom 0)
        forced (delay (swap! realized inc))
        never (reify clojure.lang.IDeref (deref [_] (throw (IllegalStateException. "deref must not run"))))
        cyclic (let [items (java.util.ArrayList.)] (.add items items) items)
        root (framed (doto (IllegalStateException. "root cause")
                       (.addSuppressed (RuntimeException. "could not record")))
                     [["clojure.lang.RT" "RT.java" 1] ["seon.probe$leaf" nil -1]])
        middle (framed (ex-info "middle" {:probe/store (Object.) :probe/error (StackOverflowError.)} root)
                       [["seon.probe$middle" "probe.clj" 7]])
        outer (framed (ex-info "outer"
                               {:probe/var #'clojure.core/map
                                :probe/vector (vec (range 100000))
                                :probe/infinite (map (fn [n] (swap! realized inc) n) (range))
                                :probe/string (apply str (repeat 1000000 \x))
                                :probe/hostile (Hostile.)
                                :probe/delay forced
                                :probe/deref never
                                :probe/atom (atom (Hostile.))
                                :probe/cyclic cyclic
                                :probe/deep {:a {:b {:c {:d 1}}}}}
                               middle)
                      [["seon.probe$outer" "probe.clj" 3] ["my.work$run" "work.clj" 9]])
        start (System/nanoTime)
        text (value/floor outer)
        ms (/ (- (System/nanoTime) start) 1e6)]
    (testing "every link's class and message, every ex-data key, and a first-party frame per link"
      (doseq [part ["clojure.lang.ExceptionInfo: \"outer\"" "caused by clojure.lang.ExceptionInfo: \"middle\""
                    "caused by java.lang.IllegalStateException: \"root cause\""
                    "suppressed java.lang.RuntimeException: \"could not record\""
                    ":probe/var #'clojure.core/map" ":probe/vector [0 1 2 3 4 5 6 7 ...]"
                    ":probe/deep {:a {:b {:c ...}}}" ":probe/error #object[java.lang.StackOverflowError"
                    "#object[seon.render.floor_test.Hostile" "#object[java.util.ArrayList"
                    "at seon.probe/outer (probe.clj:3)" "at my.work/run (work.clj:9)"
                    "at seon.probe/middle (probe.clj:7)" "at seon.probe/leaf (:-1)"]]
        (is (str/includes? text part) part))
      (is (not (str/includes? text "clojure.lang.RT")) "host frames are not first-party"))
    (testing "nothing is dereferenced, forced or realized past the bound"
      (is (not (realized? forced)))
      (is (<= @realized 32) "one chunk at most of the infinite seq"))
    (testing "the cost is the caps', not the value's"
      (is (< (count text) 8300))
      (is (< ms 50) (str "one floor took " ms " ms"))
      (is (= text (edn/read-string (pr-str text))) "the floor reads back as a string"))))

(deftest deep-cyclic-and-accessor-hostile-chains-stay-bounded
  (let [a (Exception. "a") b (Exception. "b" a)]
    (.initCause a b)
    (is (= 2 (count (re-seq #"java.lang.Exception" (value/floor a))))
        "a cause cycle prints each link once"))
  (let [deep (reduce (fn [cause n] (ex-info (str "link " n) {} cause)) (Exception. "bottom") (range 10000))
        text (value/floor deep)]
    (is (str/includes? text "… further causes"))
    (is (< (count text) 8300)))
  (let [hostile (proxy [RuntimeException] [] (getMessage [] (throw (IllegalStateException. "no"))))]
    (is (str/includes? (value/floor hostile) "#unprintable[java.lang.IllegalStateException]")
        "a throwing accessor names its link and the failure"))
  (is (str/starts-with? (value/floor (OutOfMemoryError.)) "java.lang.OutOfMemoryError: nil")
      "a nil message and an Error print"))

(deftest bounded-text-stops-at-its-limit
  (is (= "\"xxx…" (value/bounded-text (apply str (repeat 1000000 \x)) 4)))
  (is (= "#'clojure.core/map" (value/bounded-text #'clojure.core/map 100)))
  (is (= "(0 1 2 3 4 5 6 7 ...)" (value/bounded-text (range) 100))))
