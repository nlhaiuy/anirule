// 运行：node --test capture.test.js
// 没有 jsdom（环境装不了），所以用手写的极简页面模型，只覆盖 capture.js 用到的接口。
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const code = fs.readFileSync(path.join(__dirname, 'capture.js'), 'utf8');

class El {
  constructor(tag, attrs = {}, text = '') {
    this.tagName = tag.toUpperCase();
    this.attrs = { ...attrs };
    this._text = text;
    this.children = [];
    this.parentNode = null;
    this.events = [];
    this.clicked = 0;
    this.value = '';
    this.type = attrs.type || (tag === 'input' ? 'text' : '');
    this.name = attrs.name || '';
    this.id = attrs.id || '';
    this.placeholder = attrs.placeholder || '';
    this.className = attrs.class || '';
    this.target = attrs.target || '';
    this.disabled = false;
    this.form = null;
    this.title = '';
  }
  add(...cs) { cs.forEach((c) => { c.parentNode = this; this.children.push(c); }); return this; }
  get textContent() { return this._text + this.children.map((c) => c.textContent).join(''); }
  getAttribute(k) { return k in this.attrs ? this.attrs[k] : null; }
  setAttribute(k, v) { this.attrs[k] = v; }
  getBoundingClientRect() { return { width: 100, height: 20 }; }
  dispatchEvent(e) { this.events.push(e.type); return true; }
  click() { this.clicked++; }
  focus() {}
  getElementsByTagName(tag) {
    const out = [];
    const walk = (n) => n.children.forEach((c) => {
      if (tag === '*' || c.tagName === tag.toUpperCase()) out.push(c);
      walk(c);
    });
    walk(this);
    return out;
  }
}

function makeEnv(body, { fetchImpl, XHR } = {}) {
  const html = new El('html').add(body);
  const bridge = [];
  const sandbox = {
    URL, Promise, JSON, Object, Array, String, Date, console,
    Event: class { constructor(t, o) { this.type = t; Object.assign(this, o); } },
    KeyboardEvent: class { constructor(t, o) { this.type = t; Object.assign(this, o); } },
    location: { href: 'https://site.example/' },
    document: { readyState: 'complete', documentElement: Object.assign(html, { outerHTML: '<html></html>' }),
      getElementsByTagName: (t) => html.getElementsByTagName(t) },
    history: { pushState() {}, replaceState() {} },
    addEventListener() {},
    AndroidRG: { onEvent: (s) => bridge.push(JSON.parse(s)) },
    fetch: fetchImpl,
    XMLHttpRequest: XHR,
  };
  sandbox.window = sandbox;
  vm.runInNewContext(code, sandbox);
  return { g: sandbox, bridge };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

function fakeResponse(body, ct = 'application/json') {
  return { status: 200, headers: { get: () => ct }, clone() { return this; }, text: () => Promise.resolve(body) };
}

test('fetch：记录方法、请求头、请求体、绝对地址和响应', async () => {
  const { g, bridge } = makeEnv(new El('body'), { fetchImpl: () => Promise.resolve(fakeResponse('[{"id":1}]')) });
  await g.fetch('/api/search', { method: 'post', headers: { apikey: 'k' }, body: '{"q":"x"}' });
  await flush();
  assert.strictEqual(bridge.length, 1);
  const r = bridge[0];
  assert.strictEqual(r.url, 'https://site.example/api/search');
  assert.strictEqual(r.method, 'POST');
  assert.deepStrictEqual(r.headers, { apikey: 'k' });
  assert.strictEqual(r.body, '{"q":"x"}');
  assert.strictEqual(r.response, '[{"id":1}]');
  assert.strictEqual(r.contentType, 'application/json');
  assert.strictEqual(r.page, 'https://site.example/');
});

test('fetch：不改变原始返回值，请求失败不抛错', async () => {
  const { g, bridge } = makeEnv(new El('body'), { fetchImpl: () => Promise.reject(new Error('net')) });
  await assert.rejects(g.fetch('/x'));
  await flush();
  assert.strictEqual(bridge.length, 0);
});

test('XHR：记录请求头、请求体、响应', async () => {
  class FakeXHR {
    constructor() { this.l = {}; this.status = 200; this.responseType = ''; this.responseText = '{"ok":1}'; }
    open() {} setRequestHeader() {}
    addEventListener(n, f) { this.l[n] = f; }
    getResponseHeader() { return 'application/json'; }
    send() { this.l.loadend(); }
  }
  const { g, bridge } = makeEnv(new El('body'), { XHR: FakeXHR });
  const x = new g.XMLHttpRequest();
  x.open('post', '/rpc');
  x.setRequestHeader('Authorization', 'Bearer t');
  x.send('{"a":1}');
  assert.strictEqual(bridge.length, 1);
  assert.strictEqual(bridge[0].type, 'xhr');
  assert.strictEqual(bridge[0].method, 'POST');
  assert.strictEqual(bridge[0].url, 'https://site.example/rpc');
  assert.deepStrictEqual(bridge[0].headers, { Authorization: 'Bearer t' });
  assert.strictEqual(bridge[0].body, '{"a":1}');
  assert.strictEqual(bridge[0].response, '{"ok":1}');
});

test('pushState 产生 nav 记录', () => {
  const { g, bridge } = makeEnv(new El('body'));
  g.location.href = 'https://site.example/anime/5';
  g.history.pushState({}, '', '/anime/5');
  assert.deepStrictEqual(bridge.map((r) => [r.type, r.url]), [['nav', 'https://site.example/anime/5']]);
});

test('window.open 改为当前页面跳转', () => {
  const { g } = makeEnv(new El('body'));
  g.open('/play/1');
  assert.strictEqual(g.location.href, 'https://site.example/play/1');
});

test('fillSearch：选中搜索框（而不是别的输入框），填值并点提交按钮', () => {
  const body = new El('body');
  const email = new El('input', { name: 'email', placeholder: '邮箱' });
  const search = new El('input', { name: 'wd', placeholder: '搜索动漫' });
  const btn = new El('button', { type: 'submit' }, '搜索');
  const form = new El('form').add(search, btn);
  search.form = form;
  body.add(email, form);
  const { g } = makeEnv(body);
  assert.strictEqual(g.__rg.fillSearch('葬送的芙莉莲'), 'ok:button');
  assert.strictEqual(search.value, '葬送的芙莉莲');
  assert.strictEqual(email.value, '');
  assert.deepStrictEqual(search.events, ['input', 'change']);
  assert.strictEqual(btn.clicked, 1);
});

test('fillSearch：没有按钮时回车提交', () => {
  const body = new El('body');
  const search = new El('input', { type: 'search', name: 'q' });
  body.add(search);
  const { g } = makeEnv(body);
  assert.strictEqual(g.__rg.fillSearch('x'), 'ok:enter');
  assert.deepStrictEqual(search.events, ['input', 'change', 'keydown', 'keypress', 'keyup']);
});

test('fillSearch：没有输入框', () => {
  const { g } = makeEnv(new El('body'));
  assert.strictEqual(g.__rg.fillSearch('x'), 'no-input');
});

test('openSearch：点击搜索图标', () => {
  const body = new El('body');
  const icon = new El('i', { class: 'icon-search' });
  body.add(icon);
  const { g } = makeEnv(body);
  assert.strictEqual(g.__rg.openSearch(), 'ok');
  assert.strictEqual(icon.clicked, 1);
});

test('clickResult：跳过无效链接，点包含关键词的 a，并把 _blank 改成当前窗口', () => {
  const body = new El('body');
  const hint = new El('a', { href: '#' }, '搜索：葬送的芙莉莲');
  const nav = new El('a', { href: '/' }, '首页');
  const hit = new El('a', { href: '/anime/1', target: '_blank' }, '葬送的芙莉莲 第一季');
  body.add(hint, nav, hit);
  const { g } = makeEnv(body);
  assert.strictEqual(g.__rg.clickResult('葬送的芙莉莲'), 'ok:a');
  assert.strictEqual(hit.clicked, 1);
  assert.strictEqual(hit.target, '_self');
  assert.strictEqual(hint.clicked, 0);
});

test('clickResult：没有链接时点最深的包含关键词的元素（SPA 卡片）', () => {
  const body = new El('body');
  const title = new El('span', {}, '孤独摇滚！');
  const card = new El('div', { class: 'card' }).add(title);
  body.add(card);
  const { g } = makeEnv(body);
  assert.strictEqual(g.__rg.clickResult('孤独摇滚'), 'ok:el');
  assert.strictEqual(title.clicked, 1);
});

test('clickResult：没有匹配', () => {
  const { g } = makeEnv(new El('body').add(new El('a', { href: '/x' }, '别的')));
  assert.strictEqual(g.__rg.clickResult('葬送'), 'none');
});

test('clickEpisode：优先第 1 集', () => {
  const body = new El('body');
  const e3 = new El('a', { href: '/e/3' }, '第3集');
  const e1 = new El('a', { href: '/e/1' }, '第1集');
  body.add(e3, e1);
  const { g } = makeEnv(body);
  assert.strictEqual(g.__rg.clickEpisode(), 'ok');
  assert.strictEqual(e1.clicked, 1);
  assert.strictEqual(e3.clicked, 0);
});

test('clickPlay：点播放按钮', () => {
  const body = new El('body');
  const b = new El('button', {}, '立即播放');
  body.add(b);
  const { g } = makeEnv(body);
  assert.strictEqual(g.__rg.clickPlay(), 'ok');
  assert.strictEqual(b.clicked, 1);
});

test('重复注入不会重复挂钩', async () => {
  let calls = 0;
  const { g, bridge } = makeEnv(new El('body'), { fetchImpl: () => { calls++; return Promise.resolve(fakeResponse('{}')); } });
  vm.runInContext(code, vm.createContext(g)); // 第二次注入
  await g.fetch('/a');
  await flush();
  assert.strictEqual(calls, 1);
  assert.strictEqual(bridge.length, 1);
});
