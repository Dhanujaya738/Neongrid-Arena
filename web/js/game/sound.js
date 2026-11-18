/* Tiny synthesised sound effects (Web Audio, no audio files). */
import { S } from './state.js';

export const Sound = {
  ctx: null,
  tone(f, d = .12, type = 'sine', v = .05, delay = 0) {
    if (!S.sound) return;
    try {
      this.ctx = this.ctx || new (window.AudioContext || window.webkitAudioContext)();
      const t = this.ctx.currentTime + delay, o = this.ctx.createOscillator(), g = this.ctx.createGain();
      o.type = type; o.frequency.setValueAtTime(f, t);
      g.gain.setValueAtTime(v, t); g.gain.exponentialRampToValueAtTime(.0001, t + d);
      o.connect(g).connect(this.ctx.destination); o.start(t); o.stop(t + d + .03);
    } catch (e) { /* no audio */ }
  },
  place(side) { side === 1 ? (this.tone(660, .08, 'triangle'), this.tone(880, .1, 'triangle', .035, .05)) : (this.tone(440, .08, 'triangle'), this.tone(330, .12, 'triangle', .035, .05)); },
  claim() { [523, 784].forEach((f, i) => this.tone(f, .18, 'triangle', .05, i * .08)); },
  win() { [523, 659, 784, 1046, 1318].forEach((f, i) => this.tone(f, .25, 'triangle', .06, i * .09)); },
  lose() { [392, 330, 262].forEach((f, i) => this.tone(f, .3, 'sawtooth', .022, i * .13)); },
  turn() { this.tone(988, .07, 'sine', .035); },
  nope() { this.tone(160, .14, 'square', .025); },
  join() { this.tone(880, .08, 'sine', .04); this.tone(1320, .12, 'sine', .03, .08); },
};
