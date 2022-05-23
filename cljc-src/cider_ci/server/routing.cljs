(ns cider-ci.server.routing
  (:refer-clojure :exclude [keyword str])
  (:require
    [cider-ci.server.html.history-navigation :as navigation]
    [cider-ci.server.routes :as routes :refer [path]]
    [cider-ci.server.state :as state]
    [cider-ci.server.routing-resolver :refer [route-page-table]]
    [cider-ci.utils.core :refer [keyword str presence]]
    [cider-ci.utils.query-params :refer [decode] :rename {decode query-params-decode}]
    [cider-ci.utils.yaml :as yaml]
    [clojure.pprint :refer [pprint]]
    [reagent.core :as reagent]
    [reitit.core :as reitit]
    [taoensso.timbre :refer [debug info warn error spy]])
  (:require-macros
    [reagent.ratom :as ratom :refer [reaction]]
    ))


(defn on-navigate [url match]
  (let [name (get-in match[:data :name])
        component (get route-page-table name)]
    (as-> match state
      (assoc state :name name)
      (assoc state :page (-> component :page))
      (assoc state :center-nav (-> component :center-nav))
      (assoc state :page-nav (-> component :page-nav))
      (assoc state :query-params (some->> url :query query-params-decode))
      (assoc state :query-params-parsed (some->> state :query-params
                                                 (map (fn [[k v]] [k (yaml/parse v)]))
                                                 (into {})))
      (assoc state :route (path (:name state)
                                (:path-params state)
                                (:query-params state)))
      (reset! state/routing* state))))

(defn navigate? [url]
  (debug 'navigate? url)
  (when-let [match (spy (reitit/match-by-path routes/router (:path url)))]
    (debug 'navigate? {:url url :match match})
    (when (not (or (get-in match [:data :bypass-spa])
                   (get-in match [:data :external])))
      match)))

(defn init-navigation []
  (navigation/init! on-navigate :navigate? navigate?))

(defn init-query-param-debug-state []
  (swap! state/debug?*
         (fn [v] (or v (boolean (get-in @state/routing* [:query-params-parsed :debug]))))))

(defn init []
  (info "initializing routing ...")
  (init-navigation)
  (init-query-param-debug-state)
  (when (-> @state/server* :needs_init)
    (navigation/navigate! (path :init)))
  (info "initialized routing " @state/routing*))
