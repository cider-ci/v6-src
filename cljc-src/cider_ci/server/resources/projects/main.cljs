(ns cider-ci.server.resources.projects.main
  (:refer-clojure :exclude [keyword str])
  (:require
    ["react-bootstrap" :as bs]
    [cider-ci.server.html.icons :as icons]
    [cider-ci.server.html.utils.forms :as forms]
    [cider-ci.server.http.client.main :as http-client]
    [cider-ci.server.routes :refer [path navigate!]]
    [cider-ci.server.state :as state :refer [routing*] :rename {routing* routing-state*}]
    [cljs.core.async :refer [go]]
    [cljs.pprint :refer [pprint]]
    [reagent.core :as reagent :refer [reaction]]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))


;;; PROJECTS ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defonce data* (reagent/atom {}))

(def fetch-id* (atom nil))


(defn projects-component []
  [:div.projects
   [:h2 [icons/projects] " Projects"]
   [state/hidden-routing-state-component
    :did-change #(http-client/route-cached-fetch
                   data* :reload true :timeout 500)]

   (if-not (contains? @data* (:route @routing-state*))
     [:div "Spinner..."]
     (let [projects (seq (get @data* (:route @routing-state*)))]
       (if-not projects
         [:div "Empty..."]
         [:table.table.table-striped.projects
          [:thead]
          [:tbody
           (for [project projects]
             ^{:key (:id project)}
             [:tr.project
              [:td (:id project)]
              [:td (:name project)]])]])))])



;;; CREATE ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce create-mode* (reagent/atom false))
(defonce create-data* (reagent/atom {}))


(defn create [data]
  (go (when (-> {:json-params data
                 :method :post}
                http-client/request
                :chan <! http-client/filter-success)
        (reset! create-mode* false))))


(defn create-project []
  [:div
   [:h2 "Create a new project"]
   [:form
    {:on-submit (fn [e] (.preventDefault e) (create @create-data*))}
    [forms/input-component create-data* [:id]]
    [forms/input-component create-data* [:name]]
    [forms/input-component create-data* [:git_url]]
    [forms/cancel-component :on-click #(reset! create-mode* false)]
    [forms/submit-component
     :inner [:span [icons/create] " Create"]
     :btn-classes [:btn-primary]]]])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn page-nav []
  [:<>
   [:> bs/Nav.Item
    [:button.btn.btn-outline-primary.btn-sm
     {:on-click #(reset! create-mode* true)
      :disabled @create-mode*}
     [icons/create] " Create project"]]])

(defn page []
  [:div.page
   (if @create-mode*
     [create-project]
     [projects-component])])


(def components {:page page
                 :page-nav page-nav})
