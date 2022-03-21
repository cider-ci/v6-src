(ns cider-ci.server.state
  (:require
    [reagent.core :as reagent]
    [cljs.pprint :refer [pprint]]
    [taoensso.timbre :refer [debug info warn error spy]])
  (:require-macros
    [reagent.ratom :as ratom :refer [reaction]]))


(def routing* (reagent/atom {}))

(def debug?* (reagent/atom false))

(def state* (reaction
                  {:routing @routing*
                   :debug @debug?*}
                  ))


(defn debug-ui-component []
  [:div.debug.state-debug
   (when @debug?*
     [:hr]
     [:h4 "Debug global " [:code "@state*"]]
     [:pre.bg-light
      [:code
       (with-out-str (pprint @state*))
       ]])])
