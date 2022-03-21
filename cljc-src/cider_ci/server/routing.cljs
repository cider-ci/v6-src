(ns cider-ci.server.routing
  (:refer-clojure :exclude [keyword str])
  (:require
    [cider-ci.server.http.history-navigation :as navigation]
    [cider-ci.server.routes :as routes :refer [path]]
    [cider-ci.utils.core :refer [keyword str presence]]
    [cider-ci.utils.query-params :refer [decode] :rename {decode query-params-decode}]
    [cider-ci.server.state :as state]
    [clojure.pprint :refer [pprint]]
    [reagent.core :as reagent]
    [reitit.core :as reitit]
    [taoensso.timbre :refer [debug info warn error spy]])
  (:require-macros
    [reagent.ratom :as ratom :refer [reaction]]
    ))


(def resolve-table
  {; :home #'home/page
   })


(defn on-navigate [url match]
  (info 'on-navigate2 match)
  (as-> match state
    (assoc state :name (get-in state [:data :name]))
    (assoc state :page (get resolve-table (:name state)))
    (assoc state :query-params (some->> url :query query-params-decode))
    (assoc state :route (path (:name state)
                                      (:path-params state)
                                      (:query-params state)))
    (reset! state/routing* state)))

(defn navigate? [url]
  (debug 'navigate? {:url url})
  (when-let [match (reitit/match-by-path routes/router (:path url))]
    (debug 'navigate? {:url url :match match})
    (when (not (or (get-in match [:data :bypass-spa])
                   (get-in match [:data :external])))
      match)))

(defn init-navigation []
  (navigation/init! on-navigate :navigate? navigate?))

(defn init []
  (info "initializing routing ...")
  (init-navigation)
  (info "initialized routing " @state/routing*))
