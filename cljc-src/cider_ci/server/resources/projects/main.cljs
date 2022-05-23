(ns cider-ci.server.resources.projects.main
  (:refer-clojure :exclude [keyword str])
  (:require
    ["react-bootstrap" :as bs]
    [cider-ci.server.html.icons :as icons]
    [cider-ci.server.html.utils.forms :as forms]
    [cider-ci.server.http.client.main :as http-client]
    [cider-ci.server.routes :refer [path navigate!]]
    [cider-ci.server.state :as state]
    [cljs.core.async :refer [go]]
    [cljs.pprint :refer [pprint]]
    [reagent.core :as reagent :refer [reaction]]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))


(defonce create-mode* (reagent/atom false))

(defonce form-data* (reagent/atom {}))


(defn page-nav []
  [:<>
   [:> bs/Nav.Item
    [:button.btn.btn-outline-primary.btn-sm
     {:on-click #(reset! create-mode* true)}
     [icons/create] " Create project"]]])

(defn create-project []
  [:div
   [:h2 "Create a new project"]
   [:form
    {:on-submit (fn [e] (.preventDefault e))}
    [forms/input-component form-data* [:id]]
    [forms/input-component form-data* [:name]]
    [forms/input-component form-data* [:url]]
    [forms/cancel-component]
    [forms/submit-component
     :inner [:span [icons/create] " Create"]
     :btn-classes [:btn-primary]]]])

(defn projects []
  [:h2 [icons/projects] " Projects"]
  )

(defn page []
  [:div.page
   (if @create-mode*
     [create-project]
     [projects])])


(def components {:page page
                 :page-nav page-nav})
