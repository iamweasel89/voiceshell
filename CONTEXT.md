# CONTEXT.md — VoiceShell session log

## 2026-03-18

### Done
- nucleus.html — PWA input field, WebSocket client, word log with timestamps
- server.js — WebSocket server, serves HTML, receives words, broadcasts to all clients
- contour-text.js — first contour, accumulates words into text
- README.md — project description in English
- Git + GitHub: github.com/iamweasel89/voiceshell
- Claude Code installed for sshuser on remote PC
- Node.js v25.8.1 installed

### Metrics
- Nucleus: 45ms minimum word interval, no packet loss
- Socket overhead: ~5ms

### Next step
Add LLM polishing contour: buffer words, send to Claude API every N words, return cleaned text.
