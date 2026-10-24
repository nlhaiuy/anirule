"""全自动流程：给一个网址 → 在内置浏览器里自动搜索、点进详情、点第一集 → 抓到接口 → 生成并测试规则。

Browser 由调用方实现（Android 上是 WebView 适配层，测试里是假浏览器）：
    load(url) / call(fn, *args) -> str / current_url() / wait_idle() / records() -> list[dict]
call 对应注入脚本 capture.js 里的 __rg.<fn>(...)。
"""
import json
import re
from dataclasses import dataclass, field
from typing import Any, Optional
from urllib.parse import quote, quote_plus, urlparse

from jsonapi import (generate_json_api_rule, infer_play, infer_search, infer_subject, jp_select,
                     test_json_api_rule)
from rulegen import GenerateError, generate_rule, test_rule

KEYWORDS = ["葬送的芙莉莲", "孤独摇滚", "药屋少女的呢喃", "咒术回战", "鬼灭之刃"]
SKIP_HEADERS = {"accept", "accept-language", "accept-encoding", "content-length", "user-agent",
                "referer", "cookie", "host", "connection"}
PAGE_KEYS = r"page|page_number|pageNum|pageNo|pn|current"
SIZE_KEYS = r"pageSize|page_size|items_per_page|limit|per_page|perPage|size"


@dataclass
class FlowResult:
    ok: bool
    mode: str = ""            # "json-api" | "html" | ""
    partial: bool = False     # json-api 只拿到搜索阶段
    rule: Optional[dict] = None
    report: Any = None
    keyword: str = ""
    notes: list = field(default_factory=list)


def _json(text: str):
    t = (text or "").lstrip()
    if t[:1] not in "{[":
        return None
    try:
        return json.loads(t)
    except Exception:
        return None


def _api_records(recs: list) -> list:
    out = []
    for r in recs:
        if r.get("type") in ("fetch", "xhr") and r.get("status") == 200:
            data = _json(r.get("response", ""))
            if data is not None:
                out.append((r, data))
    return out


def _sub_id(text: str, value, placeholder: str) -> str:
    v = str(value)
    return re.sub(r"(?<![\w.%])" + re.escape(v) + r"(?![\w.%])", placeholder, text)


def _sub_keyword(text: str, kw: str) -> str:
    for form in (kw, quote(kw), quote_plus(kw)):
        text = text.replace(form, "{keyword}")
    return text


def _paginate(url: str, body: str):
    """把页码/每页条数换成 {page}/{pageSize}，返回 (url, body, page_size)。"""
    size = None

    def grab_size(m):
        nonlocal size
        size = int(m.group(2))
        return m.group(1) + "{pageSize}"

    body = re.sub(r'("(?:%s)"\s*:\s*)(\d+)' % PAGE_KEYS, lambda m: m.group(1) + "{page}", body)
    body = re.sub(r'("(?:%s)"\s*:\s*)(\d+)' % SIZE_KEYS, grab_size, body)
    url = re.sub(r"([?&](?:%s)=)\d+" % PAGE_KEYS, lambda m: m.group(1) + "{page}", url)
    url = re.sub(r"([?&](?:%s)=)(\d+)" % SIZE_KEYS, grab_size, url)
    return url, body, size


def _headers_text(recs: list) -> str:
    seen, lines = {}, []
    for r in recs:
        for k, v in (r.get("headers") or {}).items():
            lk = k.lower()
            if lk in SKIP_HEADERS or lk.startswith("sec-"):
                continue
            if lk == "content-type" and "json" in str(v).lower():
                continue
            if lk not in seen:
                seen[lk] = True
                lines.append(f"{k}: {v}")
    return "\n".join(lines)


def _origin(url: str) -> str:
    p = urlparse(url)
    return f"{p.scheme}://{p.netloc}"


def _do_search(browser, kw: str, notes: list) -> bool:
    res = browser.call("fillSearch", kw)
    if res == "no-input":
        if browser.call("openSearch") == "ok":
            browser.wait_idle()
            res = browser.call("fillSearch", kw)
    if res == "no-input":
        notes.append("页面上找不到搜索框")
        return False
    browser.wait_idle()
    return True


def run(url: str, browser, keywords=None, name: str = "示例动漫") -> FlowResult:
    keywords = keywords or KEYWORDS
    notes: list = []
    browser.load(url)
    browser.wait_idle()

    for kw in keywords:
        mark = len(browser.records())
        if not _do_search(browser, kw, notes):
            browser.load(url)
            browser.wait_idle()
            continue
        new = browser.records()[mark:]

        # ---- JSON 接口：挑条目数最多的搜索响应 ----
        best = None
        for rec, data in _api_records(new):
            try:
                sel = infer_search(data, kw)
            except ValueError:
                continue
            n = len(jp_select(data, sel["selectItems"]))
            if best is None or n > best[0]:
                best = (n, rec, data, sel)
        if best is not None:
            res = _json_flow(browser, name, kw, best[1], best[2], best[3], notes)
            if res.ok:
                return res
            notes.append(f"关键词「{kw}」的 json-api 流程未通过: {res.report.message if res.report else ''}")
        else:
            # ---- HTML：用渲染后的 DOM ----
            res = _html_flow(browser, name, kw, notes)
            if res and res.ok:
                return res

        browser.load(url)
        browser.wait_idle()

    notes.append("所有关键词都没有生成可用规则")
    return FlowResult(False, notes=notes)


def _html_flow(browser, name, kw, notes) -> Optional[FlowResult]:
    cur = browser.current_url()
    search_url = _sub_keyword(cur, kw)
    if "{keyword}" not in search_url:
        notes.append("搜索后网址里没有关键词，无法生成 HTML 规则")
        return None
    dom = browser.call("dom")
    try:
        rule = generate_rule(dom, kw, search_url, name)
    except GenerateError as e:
        notes.append(f"HTML 解析失败: {e}")
        return None
    rep = test_rule(rule, dom, kw)
    return FlowResult(rep.ok, "html", False, rule, rep, kw, notes)


def _json_flow(browser, name, kw, srec, sdata, ssel, notes) -> FlowResult:
    items = jp_select(sdata, ssel["selectItems"])
    item_id = jp_select(items[0], ssel["selectId"])[0]

    used = [srec]
    surl, sbody, page_size = _paginate(srec["url"], srec.get("body", ""))
    search_request = {"url": _sub_keyword(surl, kw), "method": srec["method"], "body": _sub_keyword(sbody, kw)}

    # ---- 详情：点进第一个结果 ----
    mark = len(browser.records())
    browser.call("clickResult", kw)
    browser.wait_idle()
    detail_url = browser.current_url()
    drec = ddata = dsel = None
    for rec, data in _api_records(browser.records()[mark:]):
        if str(item_id) not in (rec["url"] + rec.get("body", "")):
            continue
        try:
            dsel = infer_subject(data)
        except ValueError:
            continue
        drec, ddata = rec, data
        break

    def partial(why):
        notes.append(why)
        rule = generate_json_api_rule(
            name, search_request=search_request, search_response=sdata, keyword=kw,
            headers=_headers_text(used), base_url=_origin(srec["url"]), page_size=page_size,
            subject_url_template=_sub_id(detail_url, item_id, "{id}") if str(item_id) in detail_url else "")
        rep = test_json_api_rule(rule, kw, sdata)
        return FlowResult(rep.ok, "json-api", True, rule, rep, kw, notes)

    if drec is None:
        return partial("没有抓到详情接口（可能是服务端渲染），只生成了搜索阶段")
    used.append(drec)
    subject_request = {"url": _sub_id(drec["url"], item_id, "{id}"), "method": drec["method"],
                       "body": _sub_id(drec.get("body", ""), item_id, "{id}")}
    ep_id = None
    eps = jp_select(ddata, dsel["episodes"]["selectEpisodes"])
    if eps and dsel["episodes"]["selectId"]:
        got = jp_select(eps[0], dsel["episodes"]["selectId"])
        ep_id = got[0] if got else None

    # ---- 播放：点第一集 ----
    mark = len(browser.records())
    if browser.call("clickEpisode") == "none":
        notes.append("详情页上找不到集数入口")
    browser.wait_idle()
    play_page = browser.current_url()
    prec = pdata = None
    for rec, data in _api_records(browser.records()[mark:]):
        if ep_id is not None and str(ep_id) not in (rec["url"] + rec.get("body", "")):
            continue
        try:
            infer_play(data)
        except ValueError:
            continue
        prec, pdata = rec, data
        break
    if prec is None and browser.call("clickPlay") == "ok":  # 有些站点要再点一下播放
        browser.wait_idle()
        play_page = browser.current_url()
        for rec, data in _api_records(browser.records()[mark:]):
            try:
                infer_play(data)
            except ValueError:
                continue
            prec, pdata = rec, data
            break

    ep_tpl = ""
    if play_page and str(item_id) in play_page:
        ep_tpl = _sub_id(play_page, item_id, "{subjectId}")
        if ep_id is not None:
            ep_tpl = _sub_id(ep_tpl, ep_id, "{id}")

    kwargs = dict(
        headers=_headers_text(used + ([prec] if prec else [])), base_url=_origin(srec["url"]),
        page_size=page_size, episode_url_template=ep_tpl,
        subject_url_template=_sub_id(detail_url, item_id, "{id}") if str(item_id) in detail_url else "")
    play_request = None
    if prec is not None:
        play_request = {"url": _sub_id(prec["url"], ep_id, "{id}") if ep_id is not None else prec["url"],
                        "method": prec["method"],
                        "body": _sub_id(prec.get("body", ""), ep_id, "{id}") if ep_id is not None else prec.get("body", "")}
    else:
        notes.append("没有抓到播放接口，规则里不含播放阶段")

    rule = generate_json_api_rule(
        name, search_request=search_request, search_response=sdata, keyword=kw,
        subject_request=subject_request, subject_response=ddata,
        play_request=play_request, play_response=pdata, **kwargs)
    rep = test_json_api_rule(rule, kw, sdata, ddata, pdata)
    return FlowResult(rep.ok, "json-api", prec is None, rule, rep, kw, notes)
