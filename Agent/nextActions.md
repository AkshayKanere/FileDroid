# FileDroid — Next Actions

## Completed
- [x] Send/Receive home screen
- [x] Rich file picker (5 tabs: Images/Videos/Audio/Docs/All Files)
- [x] Transfer screen with QR + progress
- [x] Server mode support (SEND/RECEIVE)
- [x] Web UI: send.html (download page) + receive.html (upload page)
- [x] PIN auth removal
- [x] Settings cleanup
- [x] Real-time transfer progress tracking

## Potential Improvements
1. **Apps tab** in picker — list installed APKs with app icons
2. **Dark mode** for web UI — `@media (prefers-color-scheme: dark)`
3. **Sorting chips** in picker tabs — sort by date/size/name
4. **Select All** per-tab in picker
5. **Grid/List toggle** on send.html download page
6. **Transfer history** — persist completed transfers to DB
7. **Auto-stop server** after all transfers complete (with timeout)
8. **Share sheet integration** — share files from other apps into FileDroid
9. **Folder upload** support in receive.html
10. **Connection status** — WebSocket or polling for real-time client tracking on phone
11. **Polish**: animations, shimmer loading, empty state illustrations
12. **Testing**: Unit tests for ApiHandler, SecurityManager, FileUtils
