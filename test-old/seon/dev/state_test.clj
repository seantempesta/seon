(ns seon.dev.state-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [seon.dev.state :as state]))

(deftest durable-edn-publication-syncs-before-and-after-rename
  (let [directory (fs/create-temp-dir {:prefix "seon-state-durable-"})
        path (fs/path directory "state.edn")
        events (atom [])
        sync-path! @#'state/sync-path!
        move fs/move]
    (try
      (with-redefs [state/sync-path!
                    (fn [selected options]
                      (swap! events conj [:sync (if (= (fs/path directory) selected)
                                                   :directory :file)])
                      (sync-path! selected options))
                    fs/move
                    (fn [source target options]
                      (swap! events conj [:move])
                      (move source target options))]
        (is (= {:seon.dev.state/value 1}
               (state/write-edn! path {:seon.dev.state/value 1}))))
      (is (= [[:sync :file] [:move] [:sync :directory]] @events))
      (is (= {:seon.dev.state/value 1} (state/read-edn path)))
      (finally (fs/delete-tree directory {:force true})))))

(deftest durable-edn-publication-never-exposes-a-partial-replacement
  (let [directory (fs/create-temp-dir {:prefix "seon-state-cut-"})
        path (fs/path directory "state.edn")]
    (try
      (state/write-edn! path {:seon.dev.state/value :old})
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"before rename"
           (with-redefs [fs/move
                         (fn [& _]
                           (throw (ex-info "before rename" {})))]
             (state/write-edn! path {:seon.dev.state/value :new}))))
      (is (= {:seon.dev.state/value :old} (state/read-edn path)))
      (is (empty? (fs/glob directory "*.tmp")))
      (finally (fs/delete-tree directory {:force true})))))

(deftest renamed-edn-is-complete-even-before-directory-sync
  (let [directory (fs/create-temp-dir {:prefix "seon-state-renamed-"})
        path (fs/path directory "state.edn")
        sync-path! @#'state/sync-path!]
    (try
      (state/write-edn! path {:seon.dev.state/value :old})
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"after rename"
           (with-redefs [state/sync-path!
                         (fn [selected options]
                           (if (= (fs/path directory) selected)
                             (throw (ex-info "after rename" {}))
                             (sync-path! selected options)))]
             (state/write-edn! path {:seon.dev.state/value :new}))))
      (is (= {:seon.dev.state/value :new} (state/read-edn path)))
      (is (empty? (fs/glob directory "*.tmp")))
      (finally (fs/delete-tree directory {:force true})))))

(deftest durable-edn-deletion-syncs-the-containing-directory
  (let [directory (fs/create-temp-dir {:prefix "seon-state-delete-"})
        path (fs/path directory "state.edn")
        synced (atom [])
        sync-path! @#'state/sync-path!]
    (try
      (state/write-edn! path {:seon.dev.state/value :present})
      (with-redefs [state/sync-path!
                    (fn [selected options]
                      (swap! synced conj selected)
                      (sync-path! selected options))]
        (is (true? (state/delete-edn! path))))
      (is (= [(fs/path directory)] @synced))
      (is (false? (state/delete-edn! path)))
      (finally (fs/delete-tree directory {:force true})))))
