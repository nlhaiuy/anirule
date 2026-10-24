/* 注入到 WebView 的脚本：抓取网络请求 + 自动操作页面。
 * 抓到的记录通过 AndroidRG.onEvent(jsonString) 发回原生层；原生层也可以用 evaluateJavascript 调用 __rg.* 方法。
 * 只用基础 DOM 接口（getElementsByTagName / click / dispatchEvent），便于在没有完整浏览器的环境里测试。 */
(function (g) {
  if (g.__rg) return;
  var MAX_BODY = 2 * 1024 * 1024;
  var seq = 0;
  var records = [];

  function abs(u) {
    try { return new g.URL(String(u), g.location.href).href; } catch (e) { return String(u); }
  }
  function emit(rec) {
    rec.seq = ++seq;
    rec.page = g.location.href;
    rec.t = Date.now();
    records.push(rec);
    if (records.length > 500) records.shift();
    try { g.AndroidRG.onEvent(JSON.stringify(rec)); } catch (e) {}
  }
  function cut(s) { s = s == null ? '' : String(s); return s.length > MAX_BODY ? s.slice(0, MAX_BODY) : s; }
  function headersToObj(h) {
    var o = {};
    if (!h) return o;
    try {
      if (typeof h.forEach === 'function' && !Array.isArray(h)) { h.forEach(function (v, k) { o[k] = v; }); return o; }
      if (Array.isArray(h)) { h.forEach(function (p) { o[p[0]] = p[1]; }); return o; }
      for (var k in h) o[k] = h[k];
    } catch (e) {}
    return o;
  }
  function bodyToString(b) {
    if (b == null) return '';
    if (typeof b === 'string') return b;
    try { if (typeof b.toString === 'function' && String(b) !== '[object Object]') return String(b); } catch (e) {}
    return '';
  }

  // ---- fetch ----
  var _fetch = g.fetch;
  if (typeof _fetch === 'function') {
    g.fetch = function (input, init) {
      var isReq = input && typeof input === 'object' && input.url;
      var rec = {
        type: 'fetch',
        url: abs(isReq ? input.url : input),
        method: String((init && init.method) || (isReq && input.method) || 'GET').toUpperCase(),
        headers: headersToObj((init && init.headers) || (isReq && input.headers)),
        body: bodyToString(init && init.body)
      };
      var p = _fetch.apply(this, arguments);
      try {
        p.then(function (res) {
          try {
            var c = res.clone();
            c.text().then(function (t) {
              rec.status = res.status;
              rec.contentType = (res.headers && res.headers.get && res.headers.get('content-type')) || '';
              rec.response = cut(t);
              emit(rec);
            });
          } catch (e) {}
        }, function () {});
      } catch (e) {}
      return p;
    };
  }

  // ---- XMLHttpRequest ----
  var X = g.XMLHttpRequest;
  if (X && X.prototype) {
    var _open = X.prototype.open, _set = X.prototype.setRequestHeader, _send = X.prototype.send;
    X.prototype.open = function (m, u) {
      this.__rg = { type: 'xhr', method: String(m || 'GET').toUpperCase(), url: abs(u), headers: {}, body: '' };
      return _open.apply(this, arguments);
    };
    X.prototype.setRequestHeader = function (k, v) {
      if (this.__rg) this.__rg.headers[k] = v;
      return _set.apply(this, arguments);
    };
    X.prototype.send = function (b) {
      var self = this;
      if (self.__rg) {
        self.__rg.body = bodyToString(b);
        self.addEventListener('loadend', function () {
          var r = self.__rg, t = '';
          try {
            t = (self.responseType === '' || self.responseType === 'text') ? self.responseText : JSON.stringify(self.response);
          } catch (e) {}
          r.status = self.status;
          r.contentType = (self.getResponseHeader && self.getResponseHeader('content-type')) || '';
          r.response = cut(t);
          emit(r);
        });
      }
      return _send.apply(this, arguments);
    };
  }

  // ---- 页面内跳转（SPA） ----
  var h = g.history;
  if (h) {
    ['pushState', 'replaceState'].forEach(function (name) {
      var orig = h[name];
      if (typeof orig !== 'function') return;
      h[name] = function () {
        var r = orig.apply(this, arguments);
        emit({ type: 'nav', url: g.location.href });
        return r;
      };
    });
  }
  if (g.addEventListener) {
    g.addEventListener('popstate', function () { emit({ type: 'nav', url: g.location.href }); });
    g.addEventListener('hashchange', function () { emit({ type: 'nav', url: g.location.href }); });
  }
  // 新窗口改为当前页面打开，避免 WebView 丢失上下文
  g.open = function (u) { if (u) g.location.href = abs(u); return null; };

  // ---- 自动操作 ----
  var doc = g.document;
  function all(tag) { return Array.prototype.slice.call(doc.getElementsByTagName(tag)); }
  function norm(s) { return String(s || '').replace(/\s+/g, '').toLowerCase(); }
  function visible(el) {
    if (el.disabled || el.type === 'hidden') return false;
    if (el.getBoundingClientRect) {
      var r = el.getBoundingClientRect();
      if (r && r.width === 0 && r.height === 0) return false;
    }
    return true;
  }
  function fire(el, type) {
    try { el.dispatchEvent(new g.Event(type, { bubbles: true })); } catch (e) {}
  }
  function pressEnter(el) {
    ['keydown', 'keypress', 'keyup'].forEach(function (t) {
      try { el.dispatchEvent(new g.KeyboardEvent(t, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true })); } catch (e) {}
    });
  }
  function click(el) {
    if (el.target === '_blank' || el.getAttribute && el.getAttribute('target') === '_blank') {
      try { el.target = '_self'; el.setAttribute && el.setAttribute('target', '_self'); } catch (e) {}
    }
    el.click();
  }
  var SEARCH_RE = /search|搜索|搜尋|wd|kw|keyword|query|查找|找番/i;

  function findSearchInput() {
    var best = null, bestScore = -1;
    all('input').forEach(function (el) {
      var t = (el.type || 'text').toLowerCase();
      if (['text', 'search', ''].indexOf(t) < 0 || !visible(el)) return;
      var hay = [el.name, el.id, el.placeholder, el.className, el.getAttribute && el.getAttribute('aria-label')].join(' ');
      var score = (SEARCH_RE.test(hay) ? 10 : 0) + (t === 'search' ? 5 : 0) + (el.form ? 1 : 0);
      if (score > bestScore) { best = el; bestScore = score; }
    });
    return best;
  }
  function setValue(el, v) {
    try {
      var proto = g.HTMLInputElement && g.HTMLInputElement.prototype;
      var d = proto && Object.getOwnPropertyDescriptor(proto, 'value');
      if (d && d.set) d.set.call(el, v); else el.value = v;
    } catch (e) { el.value = v; }
    fire(el, 'input');
    fire(el, 'change');
  }
  function submitButtonNear(el) {
    var scope = el.form || el.parentNode;
    for (var depth = 0; scope && depth < 3; depth++, scope = scope.parentNode) {
      var btns = [];
      ['button', 'input', 'a', 'i', 'span'].forEach(function (tag) {
        Array.prototype.slice.call(scope.getElementsByTagName(tag)).forEach(function (b) {
          if (b === el) return;
          var hay = [b.className, b.id, b.type, b.textContent, b.value, b.getAttribute && b.getAttribute('aria-label')].join(' ');
          if ((b.tagName === 'BUTTON' || /submit|button/i.test(b.type || '') || /search|搜索|搜尋/i.test(hay))) btns.push(b);
        });
      });
      if (btns.length) return btns[0];
    }
    return null;
  }

  var rg = {
    records: records,
    // 点击页面上疑似"搜索图标"的元素，用于展开隐藏的搜索框
    openSearch: function () {
      var cands = [];
      ['a', 'button', 'i', 'span', 'div'].forEach(function (tag) {
        all(tag).forEach(function (el) {
          var hay = [el.className, el.id, el.getAttribute && el.getAttribute('aria-label'), el.title].join(' ');
          if (/search|搜索/i.test(hay) && visible(el)) cands.push(el);
        });
      });
      if (!cands.length) return 'none';
      click(cands[0]);
      return 'ok';
    },
    fillSearch: function (keyword) {
      var el = findSearchInput();
      if (!el) return 'no-input';
      try { el.focus && el.focus(); } catch (e) {}
      setValue(el, keyword);
      var btn = submitButtonNear(el);
      if (btn) { click(btn); return 'ok:button'; }
      if (el.form && typeof el.form.requestSubmit === 'function') { el.form.requestSubmit(); return 'ok:form'; }
      pressEnter(el);
      return 'ok:enter';
    },
    clickResult: function (keyword) {
      var key = norm(keyword), found = null;
      all('a').forEach(function (a) {
        if (found) return;
        var href = a.getAttribute && a.getAttribute('href');
        if (!href || href === '#' || /^javascript:/i.test(href)) return;
        if (key && norm(a.textContent).indexOf(key) >= 0 && visible(a)) found = a;
      });
      if (found) { click(found); return 'ok:a'; }
      // SPA 卡片：点击最深的、文本包含关键词的元素，事件会冒泡到卡片
      var deepest = null;
      all('*').forEach(function (el) {
        if (['SCRIPT', 'STYLE', 'INPUT', 'HTML', 'BODY', 'HEAD'].indexOf(el.tagName) >= 0) return;
        if (norm(el.textContent).indexOf(key) < 0) return;
        var childHas = false;
        Array.prototype.slice.call(el.children || []).forEach(function (c) { if (norm(c.textContent).indexOf(key) >= 0) childHas = true; });
        if (!childHas) deepest = deepest || el;
      });
      if (deepest) { click(deepest); return 'ok:el'; }
      return 'none';
    },
    clickEpisode: function () {
      var first = null, any = null;
      all('a').concat(all('li'), all('span'), all('div')).forEach(function (el) {
        if (el.children && el.children.length > 2) return;
        var t = String(el.textContent || '').trim();
        if (!t || t.length > 12) return;
        if (!first && /^(第\s*)?0*1\s*[话話集]?$|^EP?\s*0*1$/i.test(t)) first = el;
        if (!any && /^第\s*\d+\s*[话話集]$|^EP?\s*\d+$/i.test(t)) any = el;
      });
      var el = first || any;
      if (!el) return 'none';
      click(el);
      return 'ok';
    },
    clickPlay: function () {
      var found = null;
      ['a', 'button', 'div', 'span'].forEach(function (tag) {
        all(tag).forEach(function (el) {
          if (found) return;
          var t = String(el.textContent || '').trim();
          if (t.length <= 8 && /^(立即|在线)?播放$|^play$/i.test(t) && visible(el)) found = el;
        });
      });
      if (!found) return 'none';
      click(found);
      return 'ok';
    },
    dom: function () { return doc.documentElement ? doc.documentElement.outerHTML : ''; },
    status: function () { return JSON.stringify({ url: g.location.href, ready: doc.readyState, count: seq }); }
  };
  g.__rg = rg;
})(typeof window !== 'undefined' ? window : this);
