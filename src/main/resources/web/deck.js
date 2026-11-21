// A deck as reveal.js shows it : the same DOM (.reveal > .slides > section), so that reveal.js themes apply as they are,
// scaled to fit, with horizontal and vertical slides, fragments, an overview and slide backgrounds.

import { applyAttributes, drawDiagrams, parseAttributes, prepare, renderInto, resolveUrl } from './markdown.js';

const MARGIN = 0.04;

/** The slides of a deck, by stack. */
export function stacksOf(slides) {
  const stacks = [];
  slides.forEach(slide => { (stacks[slide.h] = stacks[slide.h] || [])[slide.v] = slide; });
  return stacks.map(stack => stack.filter(Boolean)).filter(stack => stack.length);
}

function fragmentsOf(section) {
  const all = [...section.querySelectorAll('.fragment')].filter(el => el.closest('section') === section);
  // fragments sharing a data-fragment-index show together
  const groups = new Map();
  let next = 0;
  all.forEach(el => {
    const index = el.hasAttribute('data-fragment-index') ? parseInt(el.getAttribute('data-fragment-index'), 10) : null;
    const key = index ?? 1000 + next++;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(el);
  });
  return [...groups.entries()].sort((a, b) => a[0] - b[0]).map(e => e[1]);
}

/** Builds one slide section. */
export function buildSection(slide, context) {
  const section = document.createElement('section');
  renderInto(section, slide.markdown, context);
  if (slide.attributes) applyAttributes(section, slide.attributes);
  section.dataset.h = slide.h;
  section.dataset.v = slide.v;
  ['data-background-image', 'data-background-video', 'data-background-iframe'].forEach(name => {
    if (section.hasAttribute(name)) section.setAttribute(name, resolveUrl(section.getAttribute(name), context));
  });
  return section;
}

export class Deck {
  /**
   * @param root the element the deck fills
   * @param options width, height, mode ('play' | 'static' | 'print'), onChange(state, origin), controls, progress, slideNumber
   */
  constructor(root, options) {
    this.root = root;
    this.options = Object.assign({ mode: 'play', controls: true, progress: true, slideNumber: true, center: true }, options);
    this.state = { h: 0, v: 0, f: -1, overview: false, paused: false };
    this.sections = [];
    this.root.classList.add('deck-host');
    this.resizeObserver = new ResizeObserver(() => this.layout());
    this.resizeObserver.observe(this.root);
  }

  /** (Re)builds the deck from its slides, keeping the position when possible. */
  async load(data) {
    this.data = data;
    const context = { base: data.deck.base, stamp: data.deck.assetsStamp };
    const options = data.frontMatter || {};
    const flag = (name, fallback) => options[`revealOptions.${name}`] === undefined ? fallback : options[`revealOptions.${name}`] !== 'false';
    this.showControls = this.options.mode === 'play' && flag('controls', this.options.controls);
    this.showProgress = this.options.mode === 'play' && flag('progress', this.options.progress);
    this.showNumber = this.options.mode === 'play' && flag('slideNumber', this.options.slideNumber);
    this.center = flag('center', this.options.center);
    await prepare(data.slides.map(s => s.markdown));
    this.stacks = stacksOf(data.slides);

    const reveal = document.createElement('div');
    reveal.className = 'reveal';
    const backgrounds = document.createElement('div');
    backgrounds.className = 'backgrounds';
    const slides = document.createElement('div');
    slides.className = 'slides';
    slides.style.width = `${this.options.width}px`;
    slides.style.height = `${this.options.height}px`;
    reveal.append(backgrounds, slides);

    this.sections = this.stacks.map(stack => {
      const built = stack.map(slide => buildSection(slide, context));
      if (built.length === 1) {
        slides.appendChild(built[0]);
      } else {
        const wrapper = document.createElement('section');
        wrapper.className = 'stack';
        built.forEach(s => wrapper.appendChild(s));
        slides.appendChild(wrapper);
      }
      return built;
    });

    if (this.options.mode === 'play') {
      reveal.insertAdjacentHTML('beforeend', `
        <aside class="controls" ${this.showControls ? '' : 'hidden'}>
          <button class="navigate-left" aria-label="Previous slide"></button>
          <button class="navigate-right" aria-label="Next slide"></button>
          <button class="navigate-up" aria-label="Slide above"></button>
          <button class="navigate-down" aria-label="Slide below"></button>
        </aside>
        <div class="progress" ${this.showProgress ? '' : 'hidden'}><span></span></div>
        <div class="slide-number" ${this.showNumber ? '' : 'hidden'}></div>
        <div class="pause-overlay"></div>`);
      reveal.querySelector('.navigate-left').onclick = () => this.left();
      reveal.querySelector('.navigate-right').onclick = () => this.right();
      reveal.querySelector('.navigate-up').onclick = () => this.up();
      reveal.querySelector('.navigate-down').onclick = () => this.down();
    }
    this.root.replaceChildren(reveal);
    this.reveal = reveal;
    this.slidesElement = slides;
    this.backgroundsElement = backgrounds;
    this.fragments = this.sections.map(stack => stack.map(fragmentsOf));
    if (this.overviewElement) { this.overviewElement = null; }
    const { h, v, f } = this.state;
    this.go(h, v, f, { origin: 'load' });
    if (this.state.overview) this.showOverview();
  }

  get count() { return this.sections.reduce((n, s) => n + s.length, 0); }

  /** The position of the current slide in reading order, from 0. */
  get index() {
    let index = 0;
    for (let h = 0; h < this.state.h; h++) index += this.sections[h].length;
    return index + this.state.v;
  }

  fragmentCount(h = this.state.h, v = this.state.v) { return this.fragments?.[h]?.[v]?.length || 0; }

  current() { return this.sections[this.state.h]?.[this.state.v]; }

  /** Goes to a slide, showing its first `f + 1` fragments ; f = -1 shows none, f = Infinity all. */
  go(h, v = 0, f = -1, { origin = 'local' } = {}) {
    if (!this.sections.length) return;
    h = Math.max(0, Math.min(h, this.sections.length - 1));
    v = Math.max(0, Math.min(v, this.sections[h].length - 1));
    const count = this.fragmentCount(h, v);
    f = Math.max(-1, Math.min(f, count - 1));
    const changed = h !== this.state.h || v !== this.state.v || f !== this.state.f;
    Object.assign(this.state, { h, v, f });
    this.sections.forEach((stack, sh) => stack.forEach((section, sv) => {
      const present = sh === h && sv === v;
      section.classList.toggle('present', present);
      section.classList.toggle('past', sh < h || (sh === h && sv < v));
      section.classList.toggle('future', sh > h || (sh === h && sv > v));
      section.hidden = !present && this.options.mode !== 'print';
      if (section.parentElement.classList.contains('stack')) section.parentElement.classList.toggle('present', sh === h);
    }));
    this.sections.forEach((stack, sh) => {
      if (stack.length > 1) stack[0].parentElement.hidden = sh !== h && this.options.mode !== 'print';
    });
    const groups = this.fragments[h][v];
    groups.forEach((group, i) => group.forEach(el => {
      el.classList.toggle('visible', i <= f);
      el.classList.toggle('current-fragment', i === f);
    }));
    this.updateChrome();
    this.background();
    this.layout();
    const section = this.current();
    // reveal.js lazy loading : frames, pictures and videos given a data-src load once their slide is shown
    section.querySelectorAll('[data-src]').forEach(el => { if (!el.hasAttribute('src')) el.setAttribute('src', el.getAttribute('data-src')); });
    drawDiagrams(section).then(() => this.layout());
    section.querySelectorAll('img').forEach(img => { if (!img.complete) img.addEventListener('load', () => this.layout(), { once: true }); });
    if (changed || origin === 'load') this.options.onChange?.({ ...this.state }, origin);
  }

  setState(state, origin = 'remote') {
    if (state.paused !== undefined) this.setPaused(state.paused, false);
    this.go(state.h, state.v, state.f, { origin });
    if (state.overview !== undefined && state.overview !== this.state.overview) state.overview ? this.showOverview(false) : this.hideOverview(false);
  }

  // ------------------------------------------------------------------------------------------------------------------
  // Navigation

  next() {
    const { h, v, f } = this.state;
    if (f < this.fragmentCount() - 1) return this.go(h, v, f + 1);
    if (v < this.sections[h].length - 1) return this.go(h, v + 1, -1);
    if (h < this.sections.length - 1) return this.go(h + 1, 0, -1);
  }

  prev() {
    const { h, v, f } = this.state;
    if (f >= 0) return this.go(h, v, f - 1);
    if (v > 0) return this.go(h, v - 1, Infinity);
    if (h > 0) return this.go(h - 1, this.sections[h - 1].length - 1, Infinity);
  }

  right() { if (this.state.h < this.sections.length - 1) this.go(this.state.h + 1, 0, -1); }

  left() { if (this.state.h > 0) this.go(this.state.h - 1, 0, -1); }

  down() {
    const { h, v, f } = this.state;
    if (f < this.fragmentCount() - 1) return this.go(h, v, f + 1);
    if (v < this.sections[h].length - 1) this.go(h, v + 1, -1);
  }

  up() { if (this.state.v > 0) this.go(this.state.h, this.state.v - 1, -1); }

  first() { this.go(0, 0, -1); }

  last() { this.go(this.sections.length - 1, 0, -1); }

  setPaused(paused, notify = true) {
    this.state.paused = paused;
    this.reveal?.classList.toggle('paused', paused);
    if (notify) this.options.onChange?.({ ...this.state }, 'local');
  }

  // ------------------------------------------------------------------------------------------------------------------
  // Layout

  /** Scales the slides to fit, and centers the current slide vertically as reveal.js does. */
  layout() {
    if (!this.slidesElement) return;
    const { width, height } = this.options;
    if (this.options.mode !== 'print') {
      const available = this.root.getBoundingClientRect();
      const scale = Math.min(available.width / (width * (1 + MARGIN)), available.height / (height * (1 + MARGIN))) || 1;
      this.scale = scale;
      this.slidesElement.style.transform = `translate(-50%, -50%) scale(${scale})`;
    }
    const sections = this.options.mode === 'print' ? this.sections.flat() : [this.current()].filter(Boolean);
    sections.forEach(section => {
      if (this.center && !section.classList.contains('stack')) section.style.top = `${Math.max((height - section.scrollHeight) / 2, 0)}px`;
      else section.style.top = '0px';
    });
  }

  updateChrome() {
    if (this.options.mode !== 'play' || !this.reveal) return;
    const { h, v } = this.state;
    const total = Math.max(this.count - 1, 1);
    this.reveal.querySelector('.progress span').style.width = `${(this.index / total) * 100}%`;
    this.reveal.querySelector('.slide-number').textContent = this.sections[h].length > 1 ? `${h + 1}.${v + 1}` : `${h + 1}`;
    const can = { left: h > 0, right: h < this.sections.length - 1, up: v > 0, down: v < this.sections[h].length - 1 };
    Object.entries(can).forEach(([dir, enabled]) => this.reveal.querySelector(`.navigate-${dir}`).classList.toggle('enabled', enabled));
  }

  /** reveal.js slide backgrounds : `<!-- .slide: data-background="#000" -->`, images, videos, frames. */
  background() {
    if (!this.backgroundsElement) return;
    const section = this.current();
    const layer = document.createElement('div');
    layer.className = 'slide-background present';
    const attribute = name => section.getAttribute(`data-background-${name}`);
    const plain = section.getAttribute('data-background');
    const color = attribute('color') || (plain && !/\.(png|jpe?g|gif|svg|webp)(\?|$)|^url\(/i.test(plain) ? plain : null);
    const image = attribute('image') || (plain && !color ? resolveUrl(plain.replace(/^url\((['"]?)(.*)\1\)$/, '$2'), { base: this.data.deck.base, stamp: this.data.deck.assetsStamp }) : null);
    if (color) layer.style.background = color;
    if (image) {
      layer.style.backgroundImage = `url("${image}")`;
      layer.style.backgroundSize = attribute('size') || 'cover';
      layer.style.backgroundPosition = attribute('position') || 'center';
      layer.style.backgroundRepeat = attribute('repeat') || 'no-repeat';
    }
    if (attribute('video')) {
      const video = document.createElement('video');
      video.src = attribute('video');
      video.autoplay = true; video.muted = attribute('video-muted') !== null; video.loop = attribute('video-loop') !== null;
      video.playsInline = true;
      layer.appendChild(video);
    }
    if (attribute('iframe')) {
      const frame = document.createElement('iframe');
      frame.src = attribute('iframe');
      layer.appendChild(frame);
    }
    if (attribute('opacity')) layer.style.opacity = attribute('opacity');
    const dark = color && isDark(color);
    section.classList.toggle('has-dark-background', !!dark);
    section.classList.toggle('has-light-background', color ? !dark : false);
    this.reveal.classList.toggle('has-dark-background', !!dark);
    this.backgroundsElement.replaceChildren(...(color || image || attribute('video') || attribute('iframe') ? [layer] : []));
  }

  // ------------------------------------------------------------------------------------------------------------------
  // Overview

  showOverview(notify = true) {
    this.state.overview = true;
    const overlay = document.createElement('div');
    overlay.className = 'deck-overview';
    overlay.setAttribute('role', 'dialog');
    overlay.setAttribute('aria-label', 'All slides');
    this.stacks.forEach((stack, h) => {
      const column = document.createElement('div');
      column.className = 'overview-stack';
      stack.forEach((slide, v) => {
        const button = document.createElement('button');
        button.className = 'overview-slide';
        if (h === this.state.h && v === this.state.v) button.classList.add('current');
        button.title = `${h + 1}${stack.length > 1 ? '.' + (v + 1) : ''} ${slide.title || ''}`;
        const mini = document.createElement('div');
        button.appendChild(mini);
        const label = document.createElement('span');
        label.textContent = stack.length > 1 ? `${h + 1}.${v + 1}` : `${h + 1}`;
        button.appendChild(label);
        button.onclick = () => { this.hideOverview(); this.go(h, v, -1); };
        column.appendChild(button);
        renderStatic(mini, this.data, h, v, this.options);
      });
      overlay.appendChild(column);
    });
    this.root.appendChild(overlay);
    this.overviewElement = overlay;
    overlay.querySelector('.current')?.scrollIntoView({ block: 'center', inline: 'center' });
    overlay.querySelector('.current')?.focus();
    if (notify) this.options.onChange?.({ ...this.state }, 'local');
  }

  hideOverview(notify = true) {
    this.state.overview = false;
    this.overviewElement?.remove();
    this.overviewElement = null;
    if (notify) this.options.onChange?.({ ...this.state }, 'local');
  }

  toggleOverview() { this.state.overview ? this.hideOverview() : this.showOverview(); }

  destroy() { this.resizeObserver.disconnect(); }
}

function isDark(color) {
  const probe = document.createElement('div');
  probe.style.color = color;
  document.body.appendChild(probe);
  const rgb = getComputedStyle(probe).color.match(/\d+(\.\d+)?/g)?.map(Number) || [255, 255, 255];
  probe.remove();
  return (rgb[0] * 299 + rgb[1] * 587 + rgb[2] * 114) / 1000 < 128;
}

/** One slide drawn small and still, every fragment shown : previews, overview, thumbnails. */
export function renderStatic(container, data, h, v, options, { fragments = Infinity } = {}) {
  container.classList.add('static-slide');
  const slide = data.slides.find(s => s.h === h && s.v === v);
  const reveal = document.createElement('div');
  reveal.className = 'reveal';
  const slides = document.createElement('div');
  slides.className = 'slides';
  slides.style.width = `${options.width}px`;
  slides.style.height = `${options.height}px`;
  reveal.appendChild(slides);
  container.replaceChildren(reveal);
  if (!slide) return null;
  const section = buildSection(slide, { base: data.deck.base, stamp: data.deck.assetsStamp });
  section.classList.add('present');
  slides.appendChild(section);
  const groups = fragmentsOf(section);
  groups.forEach((group, i) => group.forEach(el => el.classList.toggle('visible', i <= fragments)));
  const background = section.getAttribute('data-background-color') || section.getAttribute('data-background');
  if (background && !/\.(png|jpe?g|gif|svg|webp)/i.test(background)) reveal.style.background = background;
  const fit = () => {
    const box = container.getBoundingClientRect();
    if (!box.width) return;
    const scale = Math.min(box.width / options.width, (box.height || box.width * options.height / options.width) / options.height);
    slides.style.transform = `translate(-50%, -50%) scale(${scale})`;
    section.style.top = `${Math.max((options.height - section.scrollHeight) / 2, 0)}px`;
  };
  new ResizeObserver(fit).observe(container);
  drawDiagrams(section).then(fit);
  section.querySelectorAll('img').forEach(img => img.addEventListener('load', fit, { once: true }));
  requestAnimationFrame(fit);
  return section;
}

export { parseAttributes };
