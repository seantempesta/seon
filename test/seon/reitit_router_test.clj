(ns seon.reitit-router-test
  "End-to-end proofs for data-driven Reitit routing (`:seon.route/*`), bookmarkable URLs,
   build-time conflict detection, SSR GET, `navigate-url!`, `toggle-expert-mode!`, `<a href>` links,
   and the `compute-ns-enrichment` container-namespace OOM fix."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [reitit.core :as r]
            [seon.code :as code]
            [seon.render :as render]
            [seon.render.data :as rdata]
            [seon.render.links :as rlinks]
            [seon.render.ui :as ui]
            [seon.schema :as schema]
            [seon.test-fixture :refer [with-test-store]]
            [seon.transactor :as txr]
            [seon.web :as web]
            [seon.web.actions :as actions]
            [seon.web.css :as wcss]
            [seon.web.router :as router]))