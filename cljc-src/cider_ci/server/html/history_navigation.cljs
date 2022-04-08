(ns cider-ci.server.html.history-navigation
  "SPA History Navigation"
  (:require
    [goog.events :as gevents]
    [lambdaisland.uri :as uri]
    [clojure.string :as string]
    [taoensso.timbre :refer [debug info warn error spy]]))

;; dynamic refs set by init
(defonce popstate-listener-key nil)
(defonce click-handler-listener-key nil)
(declare
  on-navigate
  navigate?)

;; helpers, static refs defined below ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(declare
  click-handler
  event-target
  handle-anchor-click?
  location-url
  popstate-handler
  replace-strategy
  same-scheme-host-port?
  search-a-node-element)

;;; main functions ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn navigate!
  "Navigate to the given url. Also used internally via anchor click events.
  Ignores query-param changes via replaceState; pushes state if and only if
  the path changes."
  ([uri] (navigate! uri nil))
  ([uri event]
   (let [target-url (if (uri/uri? uri) uri (uri/uri uri))]
     (debug 'navigate! {:target-url target-url :event event})
     (let [current-url (location-url)]
       (debug 'current-url current-url)
       ; let event bubble up if it is not for this service
       (when (spy (same-scheme-host-port? target-url current-url))
         ; let event bubble up if the SPA is not meant to handle it
         (when-let [res (spy (navigate? target-url))]
           ; at this time we knwo we handle the event
           ; and we stop event from bubbling up
           (when event (.preventDefault event))
           (if (spy (= (:path target-url) (:path current-url)))
             (.replaceState js/window.history nil "" (str target-url))
             (.pushState js/window.history nil "" (str target-url)))
           (on-navigate target-url res)))))))

(defn deinit! []
  "For hot reloading in development. Not really necessary in prod since
  there is no point in disabling navigation during the lifecycle of one
  instance of the SPA."
  (when click-handler-listener-key
    ; TODO: TypeError: goog.events.unlistenBykey is not a function
    ; should avoid multiple event dispatching after hot reloading, but it
    ; seems that this is not a problem somehow
    ; (goog.events/unlistenBykey click-handler-listener-key)
    (set! click-handler-listener-key nil))
  (when popstate-listener-key
    ; TODO see above
    ; (goog.events/unlistenBykey popstate-listener-key)
    (set! click-handler-listener-key nil)))

(defn init! [on-navigate
             & {:keys [navigate?]
                :or {navigate? identity}}]
  (deinit!)
  (def ^:dynamic on-navigate on-navigate)
  (def ^:dynamic navigate? navigate?)
  (set! click-handler-listener-key
        (goog.events/listen
          js/document goog.events.EventType.CLICK click-handler))
  (set! popstate-listener-key
        (goog.events/listen
          js/window goog.events.EventType.POPSTATE popstate-handler false))
  (navigate! (location-url)))


;;; navigation helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- click-handler [event]
  (when-let [el (some-> event event-target search-a-node-element)]
    (when (handle-anchor-click? event el)
      (navigate! (uri/uri (.-href el)) event))))

(defn- popstate-handler [_]
  (navigate! (location-url)))


;;; WINDOW and DOM helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn same-scheme-host-port?
  "Returns true if the target-url has the same scheme, host, and port
  as the current-location-url "
  ([target-url] (same-scheme-host-port? target-url (location-url)))
  ([target-url current-location-url]
   (->> [:scheme :host :port]
        ; either can be nil in the target, implies equivalence to the current
        (every? #(if-let [target-part (get target-url %)]
                   (= target-part (get current-location-url %))
                   true)))))

(defn location-url []
  (uri/uri (.. js/window -location)))

(defn key-event? [e]
  (boolean (or (.-altKey e)
               (.-ctrlKey e)
               (.-metaKey e)
               (.-shiftKey e))))

(defn not-left-button-event? [e]
  (not= (.-button e) 0))

(defn not-self-target? [el]
  "Element targets some other frame|tab|window than itself"
  (not (or (not (.hasAttribute el "target"))
        (contains? #{"" "_self"} (.getAttribute el "target")))))

(defn- content-editable-element? [el]
  "True if and only if the anchor is inside a contenteditable div"
  (boolean (.-isContentEditable el)))

(defn- handle-anchor-click? [e el]
  "True if a anchor-click should be handled by this SPA.
  The scope of this function is UX resp browser standards like right clicks,
  target properties etc.  The check based on URLs (external, bypass SPA)
  is handled in `navigate?` not here. "
  (not (or (key-event? e)
           (not-left-button-event? e)
           (not-self-target? el)
           (content-editable-element? el))))

(defn- event-target
  "Read event's target from composed path to get shadow dom working,
  fallback to target property if not available"
  [event]
  (let [original-event ^goog.events.BrowserEvent (.getBrowserEvent event)]
    (if (exists? (.-composedPath original-event))
      (aget (.composedPath original-event) 0)
      (.-target event))))

(defn- search-a-node-element [el]
  "Returns el if el is an achor itself, or the closest anchor ancestor of el, or nil."
  (loop [el el]
    (when el
      (if (= "A" (.-nodeName el))
        el
        (recur (.-parentNode el))))))
