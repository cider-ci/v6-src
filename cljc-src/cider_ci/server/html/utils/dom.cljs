(ns cider-ci.server.html.utils.dom
  (:require
    [cider-ci.utils.url :as url]
    [cider-ci.utils.json :as json]
    [camel-snake-kebab.core :refer [->camelCase]]
    [goog.dom :as dom]
    [goog.dom.dataset :as dataset]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))

(defn data-attribute
  "Retrieves JSON and urlencoded data attribute with attribute-name
  from the first element with element-name."
  [element-name attribute-name]
  (debug 'data-attribute [element-name attribute-name])
  (try (-> (.getElementsByTagName js/document element-name)
           (aget 0)
           (dataset/get (->camelCase attribute-name))
           url/decode
           json/decode
           cljs.core/js->clj
           clojure.walk/keywordize-keys)
       (catch js/Object e
         (warn e)
         nil)))
