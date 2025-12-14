# Developer Guide: LAN Web Mirror

## Quick Start

### Prerequisites
- Node.js >= 22.20.0
- Logseq development environment set up
- Familiarity with ClojureScript and Electron

### Building

```bash
# Install dependencies
cd resources && npm install

# Build the app (includes frontend assets needed for mirror mode)
cd ..
yarn release-app
```

### Testing Locally

1. **Start the Desktop App**
   ```bash
   yarn dev-electron-app
   ```

2. **Enable Mirror Server**
   - Open Settings → AI section
   - Enable "LAN Mirror Server"
   - Set a password (e.g., "test123")
   - Save the password

3. **Enable HTTP Server**
   - Find "HTTP API server" toggle
   - Enable it
   - Set host to `127.0.0.1` (localhost) for testing
   - Note the port (default: 12315)

4. **Test from Browser**
   ```bash
   # Open in another browser or private window
   open http://localhost:12315/app
   ```

5. **Test Authentication**
   - WebSocket should connect
   - Send auth message: `{auth: "test123"}`
   - Should receive `{type: "auth-success"}`

## Code Structure

### Backend (Electron Main Process)

```
src/electron/electron/
├── mirror_server.cljs     # New: WebSocket RPC server
├── server.cljs            # Modified: Integrates mirror server
├── handler.cljs           # Existing: IPC handlers
└── core.cljs              # Existing: App initialization
```

### Frontend (Renderer Process)

```
src/main/frontend/
├── platform_adapter.cljs         # New: IPC/WS abstraction
├── components/settings.cljs      # Modified: Mirror settings UI
└── electron/ipc.cljs            # Existing: Electron IPC
```

## Key Namespaces

### `electron.mirror-server`

Main server namespace for mirror functionality.

**Key Functions:**
- `setup-mirror-server-in-main!` - Initialize mirror server routes
- `ws-connection-handler` - Handle WebSocket connections
- `notify-graph-change!` - Broadcast changes to clients
- `validate-mirror-auth` - Authenticate clients

**State:**
- `*ws-connections` - Map of active WebSocket connections
- `*connection-id-counter` - Unique ID generator

### `frontend.platform-adapter`

Abstraction layer for platform communication.

**Key Functions:**
- `ipc` - Platform-agnostic IPC (Electron or WebSocket)
- `invoke` - Platform-agnostic invoke
- `connect-ws-mirror!` - Connect to mirror server
- `disconnect-ws-mirror!` - Disconnect from mirror server

**State:**
- `*ws-connection` - Current WebSocket connection
- `*pending-rpc-calls` - Pending RPC call registry

## Development Workflow

### Adding New RPC Methods

1. **Backend**: Add handler in `electron.handler`
   ```clojure
   (defmethod handle :my-new-method [_window [_ arg1 arg2]]
     ;; Implementation
     )
   ```

2. **Frontend**: Call via platform adapter
   ```clojure
   (require '[frontend.platform-adapter :as pa])
   
   (pa/ipc :my-new-method arg1 arg2)
   ```

3. **Testing**: Works in both desktop and mirror modes automatically

### Adding Event Broadcasting

1. **Backend**: Call `notify-graph-change!`
   ```clojure
   (require '[electron.mirror-server :as mirror])
   
   (mirror/notify-graph-change! :graph-updated {:graph-name "My Graph"})
   ```

2. **Frontend**: Handle in `platform-adapter`
   ```clojure
   ;; In handle-ws-message function
   type
   (case type
     "graph-updated" (handle-graph-update payload)
     ;; Add new handlers here
     )
   ```

## Testing

### Unit Tests (Planned)

```clojure
;; Example test structure
(ns electron.mirror-server-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [electron.mirror-server :as sut]))

(deftest test-validate-auth
  (testing "Valid password"
    (is (nil? (sut/validate-mirror-auth "correct-password"))))
  
  (testing "Invalid password"
    (is (thrown? js/Error (sut/validate-mirror-auth "wrong")))))
```

### Manual Testing Checklist

- [ ] Enable mirror server in settings
- [ ] Set password and save
- [ ] Connect from browser
- [ ] Authenticate successfully
- [ ] Make RPC call (e.g., get graph list)
- [ ] Edit a page
- [ ] Verify changes sync to desktop
- [ ] Test with invalid password
- [ ] Test timeout (wait > 30s)
- [ ] Test multiple connections
- [ ] Test connection close/reconnect

## Debugging

### Enable Logging

The mirror server uses `lambdaisland.glogi` for logging:

```clojure
;; Set log level in development
(require '[lambdaisland.glogi :as log])
(log/set-level :debug)
```

### WebSocket Debugging (Browser)

```javascript
// In browser console
const ws = new WebSocket('ws://localhost:12315/api/ws');

ws.onopen = () => {
  console.log('Connected');
  // Authenticate
  ws.send(JSON.stringify({auth: 'your-password'}));
};

ws.onmessage = (event) => {
  console.log('Received:', JSON.parse(event.data));
};

// Make RPC call
ws.send(JSON.stringify({
  id: 1,
  method: 'readFile',
  args: ['/path/to/file']
}));
```

### Common Issues

1. **"Frontend assets not found"**
   - Build the app first: `yarn release-app`
   - Check `static/` directory exists

2. **"Authentication required"**
   - Ensure password is set in settings
   - Check password matches in WebSocket message
   - Verify auth message sent before RPC calls

3. **"RPC call timed out"**
   - Check desktop app is running
   - Verify method exists in handler
   - Check logs for errors

4. **Connection keeps closing**
   - Check for authentication errors
   - Verify valid JSON in messages
   - Check firewall settings

## Performance Considerations

### Connection Limits

No hard limit on connections, but consider:
- Memory usage: ~1KB per connection
- CPU usage: Minimal when idle
- Network: Depends on graph size and edit frequency

### Optimization Tips

1. **Throttle Events**: Don't broadcast every keystroke
   ```clojure
   ;; Use debounce for rapid changes
   (defonce broadcast-debounced
     (goog.functions/debounce notify-graph-change! 500))
   ```

2. **Batch Updates**: Send multiple changes in one message
   ```clojure
   {:type "batch-update"
    :payload [{:type :edit ...}
              {:type :delete ...}]}
   ```

3. **Selective Broadcasting**: Only send to relevant clients
   ```clojure
   ;; Future enhancement: filter by graph
   (broadcast-to-mirrors-filtered! :graph-updated payload
     (fn [conn-meta] (= (:graph conn-meta) current-graph)))
   ```

## Security Checklist

- [ ] Password is required and validated
- [ ] Default bind is localhost (127.0.0.1)
- [ ] No sensitive data in logs
- [ ] Timeout protection enabled
- [ ] Connection isolation working
- [ ] Error messages don't leak info
- [ ] No XSS in HTML responses
- [ ] No CSRF vulnerabilities

## Future Work

### High Priority
1. HTTPS/WSS support
2. Better conflict resolution
3. Mobile-optimized UI
4. Connection health monitoring

### Medium Priority
1. Multi-user authentication
2. Read-only mode
3. Rate limiting
4. Compression

### Low Priority
1. Binary protocol (MessagePack)
2. Automatic reconnection
3. Connection pooling
4. Advanced caching

## Resources

- [Fastify WebSocket Plugin](https://github.com/fastify/fastify-websocket)
- [WebSocket API (MDN)](https://developer.mozilla.org/en-US/docs/Web/API/WebSocket)
- [Logseq Architecture](../CODEBASE_OVERVIEW.md)
- [User Documentation](mirror-server.md)
- [Implementation Summary](implementation-summary.md)

## Getting Help

1. Check logs in both desktop app and browser console
2. Review the troubleshooting section in `mirror-server.md`
3. Search existing issues on GitHub
4. Ask in Logseq Discord development channel
5. Create a detailed issue with:
   - Steps to reproduce
   - Expected vs actual behavior
   - Relevant logs
   - Environment details

## Contributing

When contributing to this feature:

1. Follow Logseq ClojureScript conventions
2. Add appropriate logging
3. Update documentation
4. Test in both modes (desktop and mirror)
5. Consider security implications
6. Run code review before submitting PR

### Code Style

- Use `lambdaisland.glogi` for logging (not `js/console.*`)
- Add docstrings to public functions
- Use meaningful variable names
- Keep functions small and focused
- Prefer `let` over deeply nested code
- Use `promesa.core` for async operations

### Testing Guidelines

- Test happy path and error cases
- Test with and without authentication
- Test timeout scenarios
- Test connection lifecycle
- Test concurrent operations
- Test with realistic data sizes
