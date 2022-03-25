(ns cider-ci.server.http.client.shared
  (:refer-clojure :exclude [str keyword send-off])
  (:require-macros
    [reagent.ratom :as ratom :refer [reaction]]
    [cljs.core.async.macros :refer [go go-loop]])
  (:require
    [reagent.core :as reagent]
    [cider-ci.utils.core :refer [keyword str presence]]
    [clojure.string :as string]
    [taoensso.timbre :refer [debug info warn error spy]]))


(defn wait-component [req]
  [:div.wait-component
   {:style {:opacity 0.4}}
   [:div.text-center
    [:i.fas.fa-spinner.fa-spin.fa-5x]]
   [:div.text-center
    {:style {}}
    "Wait for " (-> req :method str string/upper-case)
    " " (:url req)]])

