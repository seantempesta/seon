(ns seon.dev.issues-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [seon.dev.issues :as issues])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temporary-root []
  (.toFile (Files/createTempDirectory "seon-issues-"
                                      (make-array FileAttribute 0))))

(defn- write-file! [root path content]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file content)))

(defn- issue-note [status severity title]
  (str "---\n"
       "type: issue\n"
       "status: " status "\n"
       "severity: " severity "\n"
       "tags: [issue, test]\n"
       "---\n\n"
       "# " title "\n"))

(defn- index-with [rows]
  (str "---\n"
       "type: orchestrator\n"
       "status: active\n"
       "tags: [orchestrator, issue, index]\n"
       "---\n\n"
       "# Open Issues — Index\n\n"
       "| Issue | Severity | Destination |\n"
       "|-------|----------|-------------|\n"
       rows))

(deftest issue-index-validates-the-owner-schedule-without-rewriting-it
  (let [root (temporary-root)]
    (write-file! root "docs/seon/issues/one.md"
                 (issue-note "open" "blocker" "One"))
    (write-file! root "docs/seon/issues/archive/done.md"
                 (issue-note "resolved" "cleanup" "Done"))
    (write-file! root "docs/seon/issues/index.md"
                 (index-with
                  "| [One](one.md) | blocker | running: source-owner |\n"))
    (is (= 1 (::issues/open-count (issues/check! root))))
    (is (= 1 (::issues/archive-count (issues/check! root))))

    (testing "an omitted open note is refused"
      (write-file! root "docs/seon/issues/two.md"
                   (issue-note "open" "friction" "Two"))
      (is (= :missing-schedule-row
             (-> (try (issues/check! root)
                      (catch Exception error error))
                 ex-data ::issues/errors first ::issues/problem))))

    (testing "a duplicate row is refused"
      (write-file! root "docs/seon/issues/index.md"
                   (index-with
                    (str "| [One](one.md) | blocker | running: a |\n"
                         "| [One again](one.md) | blocker | wave: b |\n"
                         "| [Two](two.md) | friction | wave: c |\n")))
      (is (some #(= :duplicate-schedule-row (::issues/problem %))
                (-> (try (issues/check! root)
                         (catch Exception error error))
                    ex-data ::issues/errors))))

    (testing "a row title containing brackets is still a row"
      ;; A title naming a Clojure form — `Register the inline [:fn] predicate`
      ;; — parsed as no row at all, so the checker reported a scheduled note as
      ;; missing its row and no edit to the index could satisfy it (2026-08-08).
      (write-file! root "docs/seon/issues/index.md"
                   (index-with
                    (str "| [Register the inline `[:fn]` predicate](one.md) "
                         "| blocker | wave: a |\n"
                         "| [Two](two.md) | friction | wave: c |\n")))
      (is (= 2 (::issues/open-count (issues/check! root)))))))
