(ns cider-ci.server.html.icons
  (:refer-clojure :exclude [next])
  (:require
    ["@fortawesome/react-fontawesome" :as fa-react-fontawesome :refer [FontAwesomeIcon]]
    ["@fortawesome/free-solid-svg-icons" :as solids]
    ["@fortawesome/free-brands-svg-icons" :as fa-free-brands-svg-icons]
    ))


(defn password [] (FontAwesomeIcon #js{:icon solids/faKey :className ""}))
