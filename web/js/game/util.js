/* Small shared helpers: DOM shortcuts, escaping, storage, toasts, overlays, X/O artwork. */

export const $ = (s, el = document) => el.querySelector(s);
export const $$ = (s, el = document) => Array.from(el.querySelectorAll(s));
export const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
export const hue = (h) => `hsl(${h} 85% 68%)`;
export const LEVEL = { easy: 'Rookie', medium: 'Pro', hard: 'Legend' };

/** localStorage that never throws (private mode, blocked storage). */
export const store = {
  get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* ignore */ } },
};

export function toast(text, kind, icon) {
  const el = document.createElement('div');
  el.className = 'toast ' + (kind || '');
  el.innerHTML = `<span>${icon || (kind === 'warn' ? '⚠️' : kind === 'good' ? '✨' : '💬')}</span><span>${esc(text)}</span>`;
  $('#toasts').appendChild(el);
  setTimeout(() => { el.classList.add('out'); setTimeout(() => el.remove(), 300); }, 2800);
  while ($('#toasts').children.length > 3) $('#toasts').firstChild.remove();
}

export function overlay(id, on) { $('#' + id).classList.toggle('on', on); }

/** id of the screen currently shown, e.g. "v-game". */
export const current = () => ($('.view.on') || {}).id;

/** Neon X and O pieces as SVG (anim = draw the strokes). */
export const SX = (anim, cls = '') => `<svg viewBox="0 0 100 100" class="${cls}"><path class="piece x ${anim ? 'draw' : ''}" d="M24 24 L76 76"/><path class="piece x ${anim ? 'draw d2' : ''}" d="M76 24 L24 76"/></svg>`;
export const SO = (anim, cls = '') => `<svg viewBox="0 0 100 100" class="${cls}"><circle class="piece o ${anim ? 'draw' : ''}" cx="50" cy="50" r="27" transform="rotate(-90 50 50)"/></svg>`;
