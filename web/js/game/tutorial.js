/* "How to play" overlay: three slides explaining the Ultimate rules. */
import { $, $$, overlay, store } from './util.js';
import { S } from './state.js';

let slide = 0;

function moveSlide() {
  $('#how-track').style.transform = `translateX(${-slide * 100}%)`;
  $$('#how-dots i').forEach((d, i) => d.classList.toggle('on', i === slide));
  $('#how-next').firstElementChild.textContent = slide === 2 ? "LET'S PLAY" : 'NEXT';
}

export function openHow() {
  slide = 0;
  moveSlide();
  overlay('ov-how', true);
  store.set('ng.tutorial', '1');
}

/** Shown automatically before a player's first Ultimate game. */
export function maybeTutorial() {
  if (S.mode === 'ultimate' && !store.get('ng.tutorial')) openHow();
}

export function initTutorial() {
  $('#how-next').addEventListener('click', () => { if (slide < 2) { slide++; moveSlide(); } else overlay('ov-how', false); });
  $('#how-close').addEventListener('click', () => overlay('ov-how', false));
  $('#btn-how').addEventListener('click', openHow);
  $('#btn-how-top').addEventListener('click', openHow);
  $('#ov-how').addEventListener('click', (e) => { if (e.target.id === 'ov-how') overlay('ov-how', false); });
}
