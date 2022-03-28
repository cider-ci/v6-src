(ns cider-ci.server.resources.sign-in.page
  (:refer-clojure :exclude [keyword str])
  (:require
    [cljs.core.async :refer [go]]
    [cider-ci.server.html.utils.forms :as forms]
    [cider-ci.server.http.client.main :as http-client]
    [reagent.core :as reagent]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))


(defn page []
  [:div
   [:h2.mt-3 "Sign-in"]
   ])

