(ns electron.mirror-server
  "Mirror server for LAN-based web UI access to desktop app"
  (:require ["@fastify/static" :as FastifyStatic]
            ["@fastify/websocket" :as FastifyWebSocket]
            ["electron" :refer [ipcMain]]
            ["fs-extra" :as fs-extra]
            ["path" :as node-path]
            [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.configs :as cfgs]
            [electron.logger :as logger]
            [electron.utils :as utils]
            [electron.window :as window]
            [promesa.core :as p]))

;; WebSocket connections registry
(defonce ^:private *ws-connections (atom #{}))

(defn- broadcast-to-mirrors!
  "Broadcast an event to all connected WebSocket clients"
  [event-type payload]
  (doseq [^js conn @*ws-connections]
    (try
      (.send conn (js/JSON.stringify (clj->js {:type event-type :payload payload})))
      (catch :default e
        (logger/error "[mirror-server] broadcast error" e)))))

(defn- validate-mirror-auth
  "Validate authentication for mirror server access"
  [auth-header]
  (let [mirror-password (cfgs/get-item :server/mirror-password)]
    (when (and mirror-password (not (string/blank? mirror-password)))
      (let [provided-auth (string/replace (or auth-header "") "Bearer " "")]
        (when (not= provided-auth mirror-password)
          (throw (js/Error. "Invalid authentication")))))))

(defn- ws-connection-handler
  "Handle WebSocket connection for RPC bridge"
  [^js connection ^js request win]
  (try
    ;; Validate auth on WebSocket upgrade
    (let [^js headers (.-headers request)]
      (validate-mirror-auth (.-authorization headers)))
    
    (swap! *ws-connections conj (.-socket connection))
    (logger/info "[mirror-server] WebSocket client connected")
    
    (.on (.-socket connection) "message"
         (fn [^js message]
           (try
             (let [msg (js/JSON.parse message)
                   method (.-method msg)
                   args (.-args msg)
                   id (.-id msg)]
               (logger/debug "[mirror-server] WS RPC call" {:method method :id id})
               
               ;; Forward RPC call to main window via IPC
               (p/let [result (p/create
                              (fn [resolve _reject]
                                (let [ret-handle (fn [^js _w ret] (resolve ret))]
                                  (utils/send-to-renderer win :invokeLogseqAPI 
                                                        {:syncId id :method method :args args})
                                  (.handleOnce ipcMain (str ::ws-rpc-result id) ret-handle))))]
                 (.send (.-socket connection) 
                        (js/JSON.stringify (clj->js {:id id :result result})))))
             (catch :default e
               (logger/error "[mirror-server] WS message error" e)))))
    
    (.on (.-socket connection) "close"
         (fn []
           (swap! *ws-connections disj (.-socket connection))
           (logger/info "[mirror-server] WebSocket client disconnected")))
    
    (.on (.-socket connection) "error"
         (fn [err]
           (logger/error "[mirror-server] WebSocket error" err)))
    
    (catch :default e
      (logger/error "[mirror-server] WebSocket connection error" e)
      (.close (.-socket connection)))))

(defn- setup-mirror-routes!
  "Setup routes for serving frontend and WebSocket RPC"
  [^js server win]
  (let [static-dir (.join node-path js/__dirname "../../../static")]
    
    ;; Serve main app at /app
    (.get server "/app"
          (fn [^js _req ^js rep]
            (let [index-path (.join node-path static-dir "index.html")]
              (if (fs-extra/existsSync index-path)
                (.sendFile rep "index.html")
                (-> rep
                    (.code 404)
                    (.send "Frontend assets not found. Please build the app first."))))))
    
    ;; Register static file serving for frontend assets
    (p/let [_ (.register server FastifyStatic
                        (bean/->js {:root static-dir
                                   :prefix "/app/"
                                   :decorateReply false}))]
      
      ;; WebSocket endpoint for RPC bridge
      (.get server "/api/ws"
            #js {:websocket true}
            (fn [^js connection ^js request]
              (ws-connection-handler connection request win))))))

(defn setup-mirror-server-in-main!
  "Add mirror server routes to existing server instance"
  [^js server win]
  (when (cfgs/get-item :server/mirror-enabled?)
    (logger/info "[mirror-server] Setting up mirror server routes")
    
    ;; Register WebSocket plugin first
    (p/let [_ (.register server FastifyWebSocket)]
      ;; Then setup routes
      (setup-mirror-routes! server win)
      
      (logger/info "[mirror-server] Mirror server routes initialized"))))

(defn notify-graph-change!
  "Notify connected mirror clients about graph changes"
  [change-type payload]
  (when (cfgs/get-item :server/mirror-enabled?)
    (broadcast-to-mirrors! change-type payload)))
