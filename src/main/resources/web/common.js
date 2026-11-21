// Shared by the pages : API calls, change events, theme loading.

export async function api(path, { method = 'GET', body } = {}) {
  const response = await fetch(path, {
    method,
    headers: Object.assign({ 'X-Requested-With': 'markdown-slides' }, body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const type = response.headers.get('Content-Type') || '';
  const data = type.includes('json') ? await response.json() : null;
  if (!response.ok) throw new Error(data?.message || `${response.status} ${response.statusText}`);
  return data;
}

/** Server sent events, reconnected after a failure ; `onStatus(true | false)` tells whether connected. */
export function listen(url, handlers, onStatus) {
  let source;
  let retry = 1000;
  let closed = false;
  const open = () => {
    if (closed) return;
    source = new EventSource(url);
    source.addEventListener('open', () => { retry = 1000; onStatus?.(true); });
    source.addEventListener('error', () => {
      onStatus?.(false);
      source.close();
      setTimeout(open, retry);
      retry = Math.min(retry * 2, 15000);
    });
    Object.entries(handlers).forEach(([kind, handler]) =>
      source.addEventListener(kind, event => {
        let data = {};
        try { data = JSON.parse(event.data); } catch (e) { /* not json */ }
        handler(data);
      }));
  };
  open();
  return { close() { closed = true; source?.close(); } };
}

/** Swaps the theme stylesheet ; `stamp` reloads a changed theme file. */
export function useTheme(theme, stamp = '') {
  let link = document.getElementById('theme');
  if (!link) {
    link = document.createElement('link');
    link.id = 'theme';
    link.rel = 'stylesheet';
    document.head.appendChild(link);
  }
  link.href = `/themes/${encodeURIComponent(theme)}.css${stamp ? `?v=${encodeURIComponent(stamp)}` : ''}`;
  let custom = document.getElementById('custom-css');
  if (!custom) {
    custom = document.createElement('link');
    custom.id = 'custom-css';
    custom.rel = 'stylesheet';
    document.head.appendChild(custom);
  }
  custom.href = `/custom.css?v=${Date.now()}`;
  return new Promise(resolve => {
    link.addEventListener('load', resolve, { once: true });
    link.addEventListener('error', resolve, { once: true });
  });
}

export const params = new URLSearchParams(window.location.search);

export function debounce(f, ms) {
  let timer;
  return (...args) => { clearTimeout(timer); timer = setTimeout(() => f(...args), ms); };
}

/** The token driving a live presentation, kept for the browser tab and the windows it opens. */
export const tokens = {
  get(room) { try { return sessionStorage.getItem(`live-token:${room}`) || localStorage.getItem(`live-token:${room}`); } catch (e) { return null; } },
  set(room, token) { try { sessionStorage.setItem(`live-token:${room}`, token); localStorage.setItem(`live-token:${room}`, token); } catch (e) { /* private mode */ } },
  remove(room) { try { sessionStorage.removeItem(`live-token:${room}`); localStorage.removeItem(`live-token:${room}`); } catch (e) { /* private mode */ } },
};

/** Sends the presenter position, the latest one only when several are pending. */
export function presenter(room, token) {
  let pending = null;
  let sending = false;
  const flush = async () => {
    if (sending || !pending) return;
    sending = true;
    const state = pending;
    pending = null;
    try {
      await api(`/api/live/move?room=${encodeURIComponent(room)}`, { method: 'POST', body: Object.assign({ token }, state) });
    } catch (e) {
      console.warn('Live update failed', e);
    } finally {
      sending = false;
      flush();
    }
  };
  return {
    send(state) {
      pending = { h: state.h, v: state.v, f: Number.isFinite(state.f) ? state.f : -1, overview: !!state.overview, paused: !!state.paused };
      flush();
    },
    async stop() { await api(`/api/live/stop?room=${encodeURIComponent(room)}`, { method: 'POST', body: { token } }); tokens.remove(room); },
  };
}

export function toast(message, kind = 'info') {
  let box = document.getElementById('toasts');
  if (!box) {
    box = document.createElement('div');
    box.id = 'toasts';
    box.setAttribute('role', 'status');
    box.setAttribute('aria-live', 'polite');
    document.body.appendChild(box);
  }
  const item = document.createElement('div');
  item.className = `toast toast-${kind}`;
  item.textContent = message;
  box.appendChild(item);
  setTimeout(() => item.remove(), kind === 'error' ? 8000 : 4000);
}

/** Downloads the PDF export of a deck, printed on demand. */
export async function downloadPdf(deckId, button) {
  const label = button?.textContent;
  if (button) { button.disabled = true; button.textContent = 'Printing…'; }
  try {
    const response = await fetch(`/api/pdf?id=${encodeURIComponent(deckId)}`);
    if (!response.ok) {
      const data = await response.json().catch(() => ({}));
      throw new Error(data.message || `${response.status} ${response.statusText}`);
    }
    const disposition = response.headers.get('Content-Disposition') || '';
    const name = /filename="?([^";]+)"?/.exec(disposition)?.[1] || 'slides.pdf';
    const url = URL.createObjectURL(await response.blob());
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
    toast(`Exported ${name}`);
  } catch (e) {
    toast(`PDF export failed : ${e.message}`, 'error');
  } finally {
    if (button) { button.disabled = false; button.textContent = label; }
  }
}

export function formatDate(date) {
  if (!date) return '';
  const [y, m, d] = date.split('-');
  const month = m ? new Date(Number(y), Number(m) - 1, 1).toLocaleString(undefined, { month: 'long' }) : '';
  return [d ? Number(d) : '', month, y].filter(Boolean).join(' ');
}

export const escapeHtml = text => String(text ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
