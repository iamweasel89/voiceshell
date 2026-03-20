# VoiceShell (working title)

A voice wrapper over the terminal. Android phone as a thin client for controlling a remote PC by voice.

## Concept

Gboard on Android dictates words into a PWA input field. Each word is an event. Words flow onto a bus and are distributed to handler contours.

**Key principles:**
- The nucleus only recognizes and forwards — no classification
- Bus: publish/subscribe, contours are independent
- Silent consent: a new command = confirmation of the previous classification
- Single file where possible, no unnecessary dependencies

## Stack

- Phone → Gboard → PWA (input field) → WebSocket
- Node.js WebSocket server → remote PC (Windows 10, i7-3610QM)
- Tailscale IP: 100.107.205.27, port 8080
- Repository: github.com/iamweasel89/voiceshell

## Files

- `nucleus.html` — PWA, input field, WebSocket client, word log with timestamps
- `server.js` — WebSocket server, serves nucleus.html, receives words, prints to console

## Status

Nucleus is working: 45ms minimum interval between words, no packet loss. Socket overhead ~5ms.

## Next step

First contour: accumulating words into text, with LLM polishing applied as early as possible (not at the end).
