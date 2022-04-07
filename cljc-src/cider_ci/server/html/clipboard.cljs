(ns cider-ci.server.html.clipboard
  (:refer-clojure :exclude [str keyword])
  (:require
    [cider-ci.utils.core :refer [str keyword deep-merge presence]]
    [cljs.core.async :refer [go]]
    [reagent.ratom :as ratom :refer [reaction]]
    [taoensso.timbre :as logging]))


; copy-text taken from
; https://github.com/metosin/komponentit/blob/master/src/cljs/komponentit/clipboard.cljs
; Copyright © 2014-2017 Metosin Oy
; Distributed under the Eclipse Public License, the same as Clojure.

(defn copy-text [text]
  (let [el (js/document.createElement "textarea")
        prev-focus-el js/document.activeElement
        y-pos (or (.. js/window -pageYOffset)
                  (.. js/document -documentElement -scrollTop))]
    (set! (.-style el) #js {:position "absolute"
                            :left "-9999px"
                            :top (str y-pos "px")
                            ;; iOS workaround?
                            :fontSize "12pt"
                            ;; reset box-model
                            :border "0"
                            :padding "0"
                            :margin "0"})
    (set! (.-value el) text)
    (.addEventListener el "focus" (fn [_] (.scrollTo js/window 0 y-pos)))
    (js/document.body.appendChild el)
    (.setSelectionRange el 0 (.. el -value -length))
    (.focus el)
    (js/document.execCommand "copy")
    (.blur el)
    (when prev-focus-el
      (.focus prev-focus-el))
    (.removeAllRanges (.getSelection js/window))
    (js/window.document.body.removeChild el)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn button-tiny [text]
  [:button.btn.btn-outline-secondary.btn-sm.py-0.px-1
   {:on-click #(copy-text text)}
   [:span [:i.fas.fa-clipboard]]])


(defn button [text]
  [:button.btn.btn-outline-secondary.btn-sm.py-0.px-1
   {:on-click #(copy-text text)}
   [:span [:i.fas.fa-clipboard] " Copy to clipboard"]])
