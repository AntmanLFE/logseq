# LAN-Only Web Mirror for Logseq Desktop App

## Overview

This feature allows you to access your Logseq desktop application from other devices on your local network (LAN) through a web interface. This is useful for viewing and editing your notes from tablets, phones, or other computers without needing to install Logseq on each device.

## Security Considerations

⚠️ **IMPORTANT SECURITY NOTES:**

1. **LAN-Only**: This feature is designed for local network use only. It binds to `127.0.0.1` (localhost) by default.
2. **Password Protection**: Always set a strong password before enabling LAN access.
3. **Network Trust**: Only enable LAN binding when connected to a trusted network (e.g., your home network).
4. **HTTPS**: The current implementation uses HTTP. For production use in untrusted environments, HTTPS should be implemented.
5. **No WAN**: This feature intentionally does not support WAN (internet) access. For cloud access, use Logseq's official sync features.

## How to Use

### 1. Enable Mirror Server

1. Open Logseq Settings
2. Navigate to the "AI" section (where API server settings are located)
3. Find "Enable LAN Mirror Server" toggle
4. Enable the toggle
5. Set a strong access password
6. Click "Save" to store the password

### 2. Configure Network Binding (Optional)

By default, the mirror server binds to `127.0.0.1` (localhost only). To allow access from other devices on your LAN:

1. Open Logseq Settings
2. Find the HTTP API Server settings
3. Change the host from `127.0.0.1` to `0.0.0.0` (all interfaces) or your specific LAN IP
4. Note the port number (default: 12315)
5. Restart the server

### 3. Access from Another Device

1. Ensure both devices are on the same network
2. Find your desktop computer's IP address:
   - **macOS**: System Preferences → Network
   - **Windows**: `ipconfig` in Command Prompt
   - **Linux**: `ip addr` or `ifconfig`
3. On the other device, open a web browser
4. Navigate to: `http://<desktop-ip>:12315/app`
   - Example: `http://192.168.1.100:12315/app`
5. Enter the password you set in the settings

### 4. Using the Web Interface

The web interface mirrors the desktop UI as closely as possible:

- **View**: Browse your graphs, pages, and blocks
- **Edit**: Make changes to pages and blocks
- **Search**: Use the search functionality
- **Navigation**: Navigate between pages and sections

All changes are synchronized in real-time with the desktop application.

## Technical Details

### Architecture

The mirror server implements:

1. **WebSocket RPC Bridge**: Bidirectional communication between web UI and desktop backend
2. **Platform Adapter**: Abstraction layer allowing frontend code to work with both Electron IPC and WebSocket RPC
3. **Authentication**: Password-based authentication for web access
4. **Static File Serving**: Serves the built frontend assets at `/app`
5. **Event Subscriptions**: Real-time graph change notifications to web clients

### Configuration Options

All configuration is stored in the Logseq configuration system:

- `server/mirror-enabled?`: Enable/disable mirror server (boolean)
- `server/mirror-password`: Access password (string)
- `server/host`: Bind address (default: `127.0.0.1`)
- `server/port`: Server port (default: `12315`)

### API Endpoints

- `GET /app`: Main web application
- `GET /app/*`: Static frontend assets
- `WS /api/ws`: WebSocket endpoint for RPC communication

## Development

### Building

The web assets are automatically built as part of the normal Logseq build process. No additional build steps are required.

### Testing

1. Start Logseq desktop application
2. Enable mirror server in settings
3. Open browser to `http://localhost:12315/app`
4. Test functionality:
   - Authentication
   - Page viewing
   - Editing
   - Search
   - Navigation

## Troubleshooting

### Cannot Connect from Other Device

- Verify both devices are on the same network
- Check firewall settings on desktop computer
- Ensure server is bound to `0.0.0.0` or specific LAN IP, not `127.0.0.1`
- Verify port is not blocked

### Authentication Fails

- Ensure password is set correctly in settings
- Password is case-sensitive
- Try resetting the password

### Changes Not Syncing

- Check WebSocket connection in browser console
- Verify desktop application is running
- Restart the mirror server

### Performance Issues

- Mirror server performance depends on network quality
- For best performance, use wired connection or strong WiFi
- Close unnecessary applications on desktop

## Future Enhancements

Potential improvements for future versions:

1. **HTTPS Support**: SSL/TLS encryption for secure communication
2. **Multiple Users**: Support for multiple simultaneous users
3. **OAuth Integration**: Integration with Logseq accounts
4. **Offline Mode**: Progressive Web App (PWA) capabilities
5. **Mobile Optimizations**: Better mobile UI/UX
6. **Conflict Resolution**: Advanced conflict handling for concurrent edits

## Support

For issues or questions:

1. Check the troubleshooting section above
2. Review Logseq documentation
3. Open an issue on GitHub
4. Ask in the Logseq community forums
