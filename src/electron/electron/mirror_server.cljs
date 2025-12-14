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

;; Static files directory - relative to the electron app directory
(def ^:private static-dir-relative-path "../../../static")

;; WebSocket connections registry - now maps connection to metadata
(defonce ^:private *ws-connections (atom {}))
(defonce ^:private *connection-id-counter (atom 0))

(defn- broadcast-to-mirrors!
  "Broadcast an event to all connected WebSocket clients"
  [event-type payload]
  (doseq [[^js conn _metadata] @*ws-connections]
    (try
      (.send conn (js/JSON.stringify (clj->js {:type event-type :payload payload})))
      (catch :default e
        (logger/error "[mirror-server] broadcast error" e)))))

(defn- validate-mirror-auth
  "Validate authentication for mirror server access"
  [auth-token]
  (let [mirror-password (cfgs/get-item :server/mirror-password)]
    (when (or (string/blank? mirror-password)
              (not= auth-token mirror-password))
      (throw (js/Error. "Invalid authentication")))))

(defn- ws-connection-handler
  "Handle WebSocket connection for RPC bridge"
  [^js connection ^js request win]
  (let [authenticated? (atom false)
        conn-id (swap! *connection-id-counter inc)
        socket (.-socket connection)]
    (swap! *ws-connections assoc socket {:id conn-id :authenticated? authenticated?})
    (logger/info "[mirror-server] WebSocket client connected" {:conn-id conn-id})
    
    (.on socket "message"
         (fn [^js message]
           (try
             (let [msg (js/JSON.parse message)
                   auth (.-auth msg)]
               ;; Handle authentication message
               (if (and (not @authenticated?) auth)
                 (do
                   (validate-mirror-auth auth)
                   (reset! authenticated? true)
                   (logger/info "[mirror-server] Client authenticated successfully" {:conn-id conn-id})
                   (.send socket 
                          (js/JSON.stringify (clj->js {:type "auth-success"}))))
                 ;; Handle RPC messages only after authentication
                 (if @authenticated?
                   (let [method (.-method msg)
                         args (.-args msg)
                         id (.-id msg)
                         ;; Use connection-specific channel to avoid cross-connection interference
                         ipc-channel (str ::ws-rpc-result conn-id "-" id)]
                     (logger/debug "[mirror-server] WS RPC call" {:method method :id id :conn-id conn-id})
                     
                     ;; Forward RPC call to main window via IPC
                     (p/let [result (p/create
                                    (fn [resolve _reject]
                                      (let [ret-handle (fn [^js _w ret] (resolve ret))]
                                        (utils/send-to-renderer win :invokeLogseqAPI 
                                                              {:syncId id :method method :args args})
                                        (.handleOnce ipcMain ipc-channel ret-handle))))]
                       (.send socket 
                              (js/JSON.stringify (clj->js {:id id :result result})))))
                   ;; Reject unauthenticated RPC calls
                   (do
                     (logger/warn "[mirror-server] Unauthenticated RPC attempt" {:conn-id conn-id})
                     (.send socket
                            (js/JSON.stringify (clj->js {:error "Authentication required"})))
                     (.close socket)))))
             (catch :default e
               (logger/error "[mirror-server] WS message error" e)
               (.send socket
                      (js/JSON.stringify (clj->js {:error (.-message e)})))))))
    
    (.on socket "close"
         (fn []
           (swap! *ws-connections dissoc socket)
           (logger/info "[mirror-server] WebSocket client disconnected" {:conn-id conn-id})))
    
    (.on socket "error"
         (fn [err]
           (logger/error "[mirror-server] WebSocket error" {:err err :conn-id conn-id})))))

(defn- setup-mirror-routes!
  "Setup routes for serving frontend and WebSocket RPC"
  [^js server win]
  (let [static-dir (.join node-path js/__dirname static-dir-relative-path)]
    
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
