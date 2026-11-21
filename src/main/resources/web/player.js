// The player : a deck full screen, alone, driving a live presentation (with the token), or following one.

import { api, debounce, downloadPdf, listen, params, presenter, toast, tokens, useTheme } from './common.js';
import { Deck } from './deck.js';

const host = document.getElementById('deck');
const bar = document.getElementById('player-bar');
const banner = document.getElementById('live-banner');
const help = document.getElementById('help');

let config;
let data;
let deck;
let deckId = params.get('id');
let room = params.get('room') || (/^\/live\/([a-z0-9]+)/.exec(window.location.pathname)?.[1] ?? null);
let token = null;
let driver = null; // sends the position when presenting
let following = false; // follows the presenter
let liveState = null; // the presenter latest position
let lastSeq = 0;
let liveStream = null;
let themeName = null;

function readHash() {
  const m = /^#\/(\d+)(?:\/(\d+))?(?:\/(-?\d+))?/.exec(window.location.hash);
  return m ? { h: Number(m[1]), v: Number(m[2] || 0), f: m[3] !== undefined ? Number(m[3]) : -1 } : null;
}

/** The slide holding the heading a `#my-title` link points at. */
function anchorTarget(hash = window.location.hash) {
  if (!deck || hash.length < 2 || hash.startsWith('#/') || hash.startsWith('#token=')) return null;
  const anchor = decodeURIComponent(hash.slice(1));
  for (const [h, stack] of deck.sections.entries())
    for (const [v, section] of stack.entries())
      if ([...section.querySelectorAll('[data-anchor]')].some(el => el.dataset.anchor === anchor)) return { h, v, f: -1 };
  return null;
}

function writeHash(state) {
  const hash = `#/${state.h}${state.v || state.f >= 0 ? `/${state.v}` : ''}${state.f >= 0 ? `/${state.f}` : ''}`;
  if (window.location.hash !== hash) history.replaceState(null, '', window.location.pathname + window.location.search + hash);
}

function setUrl() {
  const query = new URLSearchParams();
  if (room) query.set('room', room);
  else query.set('id', deckId);
  history.replaceState(null, '', `/play?${query}${window.location.hash}`);
}

function onChange(state, origin) {
  writeHash(state);
  if (origin === 'local' || origin === 'load') {
    if (driver && origin === 'local') driver.send(state);
    if (following && origin === 'local' && liveState && !samePosition(state, liveState)) setFollowing(false);
  }
  document.title = `${data?.deck.title || 'Slides'} · ${state.h + 1}${state.v ? '.' + (state.v + 1) : ''}`;
}

const samePosition = (a, b) => a.h === b.h && a.v === b.v && (a.f ?? -1) === (b.f ?? -1);

async function loadDeck(keepPosition = true) {
  data = await api(`/api/deck?id=${encodeURIComponent(deckId)}`);
  if (data.theme !== themeName) { themeName = data.theme; await useTheme(themeName); }
  if (!deck) deck = new Deck(host, { width: config.slideWidth, height: config.slideHeight, mode: 'play', onChange });
  // read before loading : showing the first slide writes its position in the URL
  const anchor = window.location.hash;
  if (!keepPosition) Object.assign(deck.state, readHash() || { h: 0, v: 0, f: -1 });
  await deck.load(data);
  const target = keepPosition ? null : anchorTarget(anchor);
  if (target) deck.go(target.h, target.v, target.f);
  document.body.classList.add('ready');
}

// ====================================================================================================================
// Live

function renderBanner(text, actions = []) {
  banner.hidden = !text;
  banner.replaceChildren();
  if (!text) return;
  const span = document.createElement('span');
  span.textContent = text;
  banner.appendChild(span);
  actions.forEach(([label, action]) => {
    const button = document.createElement('button');
    button.textContent = label;
    button.onclick = action;
    banner.appendChild(button);
  });
}

function setFollowing(value) {
  following = value;
  if (following && liveState) deck.setState(liveState);
  if (token) return;
  if (following) renderBanner('Following the presenter', [['Browse freely', () => setFollowing(false)]]);
  else renderBanner('Browsing on your own', [['Back to the presenter', () => setFollowing(true)]]);
}

function connectLive() {
  liveStream?.close();
  liveStream = listen(`/api/live/events?room=${encodeURIComponent(room)}${token ? '&presenter=true' : ''}`, {
    state: message => {
      if (message.state.seq < lastSeq) return;
      lastSeq = message.state.seq;
      liveState = message.state;
      if (message.deckId && message.deckId !== deckId) { deckId = message.deckId; loadDeck(); return; }
      // the other windows of the presenter, and the followers
      if (token || following) {
        if (!samePosition(deck.state, liveState) || deck.state.paused !== liveState.paused || deck.state.overview !== liveState.overview) deck.setState(liveState);
      }
      if (token) updatePresenterBanner(message.followers);
    },
    followers: message => { if (token) updatePresenterBanner(message.followers); },
    ended: () => {
      liveStream?.close();
      if (token) { tokens.remove(room); token = null; driver = null; }
      room = null;
      setUrl();
      renderBanner('The live presentation has ended', [['Close', () => renderBanner('')]]);
    },
  });
}

function updatePresenterBanner(followers) {
  const count = followers === 1 ? '1 participant' : `${followers} participants`;
  renderBanner(`Live · ${count}`, [['Share', share], ['End', endLive]]);
}

async function startLive() {
  if (room && token) return;
  const started = await api('/api/live/start', { method: 'POST', body: { deckId, h: deck.state.h, v: deck.state.v, f: deck.state.f } });
  room = started.room;
  token = started.token;
  tokens.set(room, token);
  driver = presenter(room, token);
  setUrl();
  connectLive();
  updatePresenterBanner(0);
}

async function share() {
  const info = await api('/api/share').catch(() => ({ urls: [] }));
  const path = `/live/${room}`;
  const urls = info.urls.length ? info.urls.map(u => u + path) : [window.location.origin + path];
  const dialog = document.getElementById('share');
  dialog.querySelector('.share-urls').replaceChildren(...urls.map(url => {
    const li = document.createElement('li');
    const a = document.createElement('a');
    a.href = url; a.textContent = url; a.target = '_blank';
    li.appendChild(a);
    return li;
  }));
  dialog.querySelector('.share-code').textContent = room;
  dialog.querySelector('.share-local').hidden = info.urls.length > 0 || !['127.0.0.1', 'localhost', '::1'].includes(config.listenHost);
  dialog.showModal();
}

async function endLive() {
  if (!driver) return;
  await driver.stop().catch(e => toast(e.message, 'error'));
}

async function openSpeaker() {
  try {
    await startLive();
    const w = window.open(`/speaker?room=${encodeURIComponent(room)}#token=${encodeURIComponent(token)}`, `speaker-${room}`, 'width=1280,height=800');
    if (!w) toast('The speaker view was blocked : allow pop-ups for this site', 'error');
  } catch (e) {
    toast(`Unable to open the speaker view : ${e.message}`, 'error');
  }
}

// ====================================================================================================================
// Controls

function fullscreen() {
  if (document.fullscreenElement) document.exitFullscreen();
  else document.documentElement.requestFullscreen?.();
}

function toggleHelp(show = help.hidden) { help.hidden = !show; }

let zoomed = false;
function zoom(event) {
  const slides = deck.slidesElement;
  if (zoomed) { slides.style.transformOrigin = ''; host.classList.remove('zoomed'); zoomed = false; deck.layout(); return; }
  const box = host.getBoundingClientRect();
  host.classList.add('zoomed');
  host.style.setProperty('--zoom-x', `${((event.clientX - box.left) / box.width) * 100}%`);
  host.style.setProperty('--zoom-y', `${((event.clientY - box.top) / box.height) * 100}%`);
  zoomed = true;
}

document.addEventListener('keydown', event => {
  if (event.target.closest('input, textarea, select, dialog[open]')) return;
  if (event.ctrlKey || event.metaKey) return;
  const key = event.key;
  const actions = {
    ArrowRight: () => deck.right(), ArrowLeft: () => deck.left(), ArrowDown: () => deck.down(), ArrowUp: () => deck.up(),
    l: () => deck.right(), h: () => deck.left(), j: () => deck.down(), k: () => deck.up(),
    ' ': () => (event.shiftKey ? deck.prev() : deck.next()), PageDown: () => deck.next(), n: () => deck.next(), Enter: () => deck.next(),
    PageUp: () => deck.prev(), p: () => deck.prev(), Backspace: () => deck.prev(),
    Home: () => deck.first(), End: () => deck.last(),
    Escape: () => (help.hidden ? deck.toggleOverview() : toggleHelp(false)), o: () => deck.toggleOverview(),
    f: fullscreen, s: openSpeaker, S: openSpeaker, b: () => deck.setPaused(!deck.state.paused), '.': () => deck.setPaused(!deck.state.paused),
    '?': () => toggleHelp(), g: async () => { try { await startLive(); share(); } catch (e) { toast(e.message, 'error'); } },
  };
  if (deck.state.overview) {
    // Enter and space activate the focused slide, as buttons do
    if (key === 'Escape' || key === 'o') { event.preventDefault(); deck.hideOverview(); }
    else if (key.startsWith('Arrow')) { event.preventDefault(); moveOverviewFocus(key); }
    return;
  }
  const action = actions[key];
  if (!action) return;
  event.preventDefault();
  action();
});

function moveOverviewFocus(key) {
  const columns = [...deck.overviewElement.querySelectorAll('.overview-stack')].map(c => [...c.querySelectorAll('.overview-slide')]);
  const focused = document.activeElement;
  let h = columns.findIndex(c => c.includes(focused));
  let v = h < 0 ? 0 : columns[h].indexOf(focused);
  if (h < 0) h = deck.state.h;
  if (key === 'ArrowRight') { h = Math.min(h + 1, columns.length - 1); v = 0; }
  if (key === 'ArrowLeft') { h = Math.max(h - 1, 0); v = 0; }
  if (key === 'ArrowDown') v = Math.min(v + 1, columns[h].length - 1);
  if (key === 'ArrowUp') v = Math.max(v - 1, 0);
  const target = columns[h][v];
  target.focus();
  target.scrollIntoView({ block: 'nearest', inline: 'nearest' });
}

host.addEventListener('click', event => {
  if (event.altKey || event.ctrlKey) { event.preventDefault(); zoom(event); }
});

let touch = null;
host.addEventListener('touchstart', e => { touch = { x: e.touches[0].clientX, y: e.touches[0].clientY }; }, { passive: true });
host.addEventListener('touchend', e => {
  if (!touch) return;
  const dx = e.changedTouches[0].clientX - touch.x;
  const dy = e.changedTouches[0].clientY - touch.y;
  touch = null;
  if (Math.max(Math.abs(dx), Math.abs(dy)) < 40) return;
  if (Math.abs(dx) > Math.abs(dy)) (dx < 0 ? deck.next() : deck.prev());
  else (dy < 0 ? deck.down() : deck.up());
});

// the toolbar shows while the mouse moves
const hideBar = debounce(() => bar.classList.remove('shown'), 2500);
document.addEventListener('mousemove', () => { bar.classList.add('shown'); hideBar(); });
bar.addEventListener('focusin', () => bar.classList.add('shown'));

document.getElementById('bar-overview').onclick = () => deck.toggleOverview();
document.getElementById('bar-speaker').onclick = openSpeaker;
document.getElementById('bar-live').onclick = async () => { try { await startLive(); share(); } catch (e) { toast(e.message, 'error'); } };
document.getElementById('bar-pdf').onclick = event => downloadPdf(deckId, event.currentTarget);
document.getElementById('bar-fullscreen').onclick = fullscreen;
document.getElementById('bar-help').onclick = () => toggleHelp();
help.addEventListener('click', () => toggleHelp(false));
document.querySelector('#share .share-close').onclick = () => document.getElementById('share').close();

window.addEventListener('hashchange', () => { const s = readHash() || anchorTarget(); if (s) deck.go(s.h, s.v, s.f); });

// ====================================================================================================================

async function main() {
  config = await api('/api/config');
  if (room) {
    const info = await api(`/api/live/room?room=${encodeURIComponent(room)}`).catch(() => null);
    if (!info) {
      if (!deckId) { document.getElementById('missing').hidden = false; return; }
      room = null;
      toast('This live presentation has ended');
    } else {
      deckId = info.deckId;
      token = window.location.hash.startsWith('#token=') ? decodeURIComponent(window.location.hash.slice(7)) : tokens.get(room);
      if (token) { tokens.set(room, token); driver = presenter(room, token); }
      if (window.location.hash.startsWith('#token=')) history.replaceState(null, '', window.location.pathname + window.location.search);
    }
  }
  if (!deckId) { document.getElementById('missing').hidden = false; return; }
  document.getElementById('bar-pdf').hidden = !config.browser;
  document.getElementById('bar-back').href = '/';
  await loadDeck(false);
  setUrl();
  if (room) {
    connectLive();
    if (!token) setFollowing(true);
  }
  listen('/api/events', {
    'deck-changed': event => { if (event.deckId === deckId) loadDeck().then(() => toast('Slides updated')); },
    'assets-changed': event => { if (event.deckId === deckId) loadDeck(); },
    'deck-removed': event => { if (event.deckId === deckId) toast('This deck was removed from the sources', 'error'); },
    'theme-changed': event => { if (event.stamp === themeName) useTheme(themeName, String(Date.now())).then(() => deck.layout()); },
    // the theme or the custom css changed
    'settings-changed': () => { themeName = null; loadDeck(); },
  });
}

main().catch(e => {
  console.error(e);
  toast(`Unable to open the slides : ${e.message}`, 'error');
});
