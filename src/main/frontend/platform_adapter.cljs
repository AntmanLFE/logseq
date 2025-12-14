(ns frontend.platform-adapter
  "Platform adapter abstraction layer to support both Electron IPC and WebSocket RPC
  for mirror server functionality"
  (:require [cljs-bean.core :as bean]
            [electron.ipc :as electron-ipc]
            [frontend.util :as util]
            [lambdaisland.glogi :as log]
            [promesa.core :as p]))

;; WebSocket connection state for mirror mode
(defonce ^:private *ws-connection (atom nil))
(defonce ^:private *ws-rpc-id (atom 0))
(defonce ^:private *pending-rpc-calls (atom {}))

(defn- ws-rpc-call
  "Make an RPC call over WebSocket connection"
  [method & args]
  (if-let [ws @*ws-connection]
    (p/create
     (fn [resolve reject]
       (let [id (swap! *ws-rpc-id inc)
             msg (bean/->js {:id id :method method :args args})]
         (swap! *pending-rpc-calls assoc id {:resolve resolve :reject reject})
         (.send ws (js/JSON.stringify msg)))))
    (p/rejected (js/Error. "WebSocket not connected"))))

(defn- handle-ws-message
  "Handle incoming WebSocket messages (RPC responses and events)"
  [^js event]
  (try
    (let [msg (js/JSON.parse (.-data event))
          id (.-id msg)
          result (.-result msg)
          type (.-type msg)
          payload (.-payload msg)]
      (cond
        ;; RPC response
        id
        (when-let [pending-call (get @*pending-rpc-calls id)]
          (swap! *pending-rpc-calls dissoc id)
          ((:resolve pending-call) result))
        
        ;; Event notification (e.g., graph changes)
        type
        (do
          (log/info ::ws-event {:type type :payload payload})
          ;; TODO: Dispatch to appropriate event handlers
          nil)
        
        :else
        (log/warn ::unknown-ws-message msg)))
    (catch :default e
      (log/error ::ws-message-error e))))

(defn connect-ws-mirror!
  "Connect to mirror server WebSocket endpoint"
  [host port auth-token]
  (p/create
   (fn [resolve reject]
     (try
       (let [ws-url (str "ws://" host ":" port "/api/ws")
             ws (js/WebSocket. ws-url)]
         
         (set! (.-onopen ws)
               (fn []
                 (log/info ::ws-connected {:url ws-url})
                 (reset! *ws-connection ws)
                 ;; Send auth token
                 (.send ws (js/JSON.stringify (clj->js {:auth auth-token})))
                 (resolve ws)))
         
         (set! (.-onmessage ws) handle-ws-message)
         
         (set! (.-onerror ws)
               (fn [err]
                 (log/error ::ws-error err)
                 (reject err)))
         
         (set! (.-onclose ws)
               (fn []
                 (log/info ::ws-closed)
                 (reset! *ws-connection nil))))
       (catch :default e
         (reject e))))))

(defn disconnect-ws-mirror!
  "Disconnect from mirror server"
  []
  (when-let [ws @*ws-connection]
    (.close ws)
    (reset! *ws-connection nil)))

(defn ipc
  "Platform-agnostic IPC call - uses Electron IPC or WebSocket RPC depending on mode"
  [& args]
  (if @*ws-connection
    ;; Mirror mode: use WebSocket RPC
    (apply ws-rpc-call args)
    ;; Desktop mode: use Electron IPC
    (apply electron-ipc/ipc args)))

(defn invoke
  "Platform-agnostic invoke - uses Electron IPC or WebSocket RPC depending on mode"
  [channel & args]
  (if @*ws-connection
    ;; Mirror mode: use WebSocket RPC with channel as method
    (apply ws-rpc-call channel args)
    ;; Desktop mode: use Electron IPC invoke
    (apply electron-ipc/invoke channel args)))

(defn mirror-mode?
  "Check if currently running in mirror mode (WebSocket connection active)"
  []
  (some? @*ws-connection))

(defn electron-mode?
  "Check if currently running in Electron desktop mode"
  []
  (and (util/electron?) (not (mirror-mode?))))
