const WebSocket = require('ws');

const PORT = 8080;
const url = `ws://127.0.0.1:${PORT}`;

let text = '';

const ws = new WebSocket(url);

ws.on('open', () => {
  console.log('Connected to server');
});

ws.on('message', (data) => {
  const word = data.toString();
  text = text ? `${text} ${word}` : word;
  console.log(text);
});

ws.on('close', () => {
  console.log('Disconnected from server');
});

ws.on('error', (err) => {
  console.error('WebSocket error:', err.message);
});
