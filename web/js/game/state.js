/* Shared game state and the single secure connection to the server (see ../net.js). */
import { store } from './util.js';

const params = new URLSearchParams(location.search);

export const S = {
  me: null,                 // our profile from the server
  mode: store.get('ng.mode') || 'ultimate',
  room: null,               // latest room state from the server
  lobby: null,              // latest lobby info (online count, leaderboard, live rooms)
  prevCells: null,
  prevMacro: null,
  builtFor: null,           // which room/game the board DOM was built for
  deadline: 0,              // turn clock deadline (performance.now() time)
  sound: store.get('ng.sound') !== 'off',
  wantRoom: (params.get('room') || '').toUpperCase().replace(/[^A-Z0-9]/g, '') || null,
  wantSpectate: params.get('spectate') === '1',
  shownResult: null,
  hover: -1,
  unread: 0,
  qrFor: null,
  leaveArmed: 0,
  lastReact: 0,
  retry: 0,
  pending: false,
};

const hooks = { onClose: () => {} };

/** main.js registers what to do when the connection drops (reconnect). */
export function onConnectionLost(fn) { hooks.onClose = fn; }

/** One NeonGrid secure WebSocket (Diffie-Hellman + HMAC), defined in net.js as window.NG. */
export const net = new window.NG.SecureSocket({ onClose: (e) => hooks.onClose(e) });
