"""只输入一个网址 → 自动找搜索入口、选关键词、生成并测试规则。

fetch 由调用方注入：fetch(url) -> str（失败时抛异常）。方便测试，也方便在 Android 上换成 OkHttp。
"""
from dataclasses import dataclass, field
from typing import Callable, Optional
from urllib.parse import quote, urljoin, urlparse, urlencode

from bs4 import BeautifulSoup

from rulegen import GenerateError, TestReport, generate_rule, test_rule

KEYWORDS = ["葬送的芙莉莲", "孤独摇滚", "药屋少女的呢喃", "咒术回战", "鬼灭之刃"]
COMMON_PATHS = [
    "/search.html?wd={keyword}",
    "/vodsearch/-------------.html?wd={keyword}",
    "/index.php/vod/search.html?wd={keyword}",
    "/search/?q={keyword}",
    "/search?keyword={keyword}",
    "/?s={keyword}",
]
TEXT_INPUT_NAMES = {"wd", "q", "keyword", "key", "kw", "s", "searchword", "query", "search"}


@dataclass
class DiscoverResult:
    ok: bool
    rule: Optional[dict] = None
    report: Optional[TestReport] = None
    search_url: str = ""
    keyword: str = ""
    notes: list = field(default_factory=list)


def search_templates_from_home(home_html: str, base_url: str, notes: list) -> list:
    """从首页的搜索表单推导搜索链接模板（GET）。"""
    soup = BeautifulSoup(home_html, "html.parser")
    out = []
    for form in soup.find_all("form"):
        field_el = None
        for inp in form.find_all("input"):
            t = (inp.get("type") or "text").lower()
            if t in ("text", "search") or (inp.get("name") or "") in TEXT_INPUT_NAMES:
                if inp.get("name"):
                    field_el = inp
                    break
        if field_el is None:
            continue
        method = (form.get("method") or "get").lower()
        if method != "get":
            notes.append("发现 POST 搜索表单，暂不支持自动生成（需要 json-api 或手动配置）")
            continue
        action = urljoin(base_url, form.get("action") or base_url)
        fixed = {}
        for inp in form.find_all("input", type="hidden"):
            if inp.get("name"):
                fixed[inp["name"]] = inp.get("value", "")
        params = [urlencode(fixed)] if fixed else []
        params.append(field_el["name"] + "={keyword}")
        joiner = "&" if "?" in action else "?"
        out.append(action + joiner + "&".join(params))
    return out


def candidates(base_url: str, home_html: Optional[str], notes: list) -> list:
    p = urlparse(base_url)
    origin = f"{p.scheme}://{p.netloc}"
    tpls = []
    if "{keyword}" in base_url:  # 用户直接给了搜索链接模板
        tpls.append(base_url)
    if home_html:
        tpls += search_templates_from_home(home_html, base_url, notes)
    tpls += [origin + c for c in COMMON_PATHS]
    seen, out = set(), []
    for t in tpls:
        if t not in seen:
            seen.add(t)
            out.append(t)
    return out


def discover(url: str, fetch: Callable[[str], str], keywords=None, name: str = "示例动漫") -> DiscoverResult:
    keywords = keywords or KEYWORDS
    notes: list = []
    home_html = None
    if "{keyword}" not in url:
        try:
            home_html = fetch(url)
        except Exception as e:
            notes.append(f"首页无法访问: {e}")

    last_report = None
    for tpl in candidates(url, home_html, notes):
        for kw in keywords:
            real = tpl.replace("{keyword}", quote(kw))
            try:
                html = fetch(real)
            except Exception as e:
                notes.append(f"请求失败 {real}: {e}")
                break  # 这个模板整个不可用，换下一个
            if html.lstrip()[:1] in "{[":
                notes.append(f"{tpl} 返回 JSON，需要 json-api 规则（暂未支持）")
                break
            try:
                rule = generate_rule(html, kw, tpl, name)
            except GenerateError as e:
                last_report = TestReport(False, message=str(e))
                continue
            rep = test_rule(rule, html, kw)
            if rep.ok:
                return DiscoverResult(True, rule, rep, tpl, kw, notes)
            last_report = rep
    notes.append("所有搜索入口与关键词都没有生成可用规则" + (f"（最后一次: {last_report.message}）" if last_report else ""))
    return DiscoverResult(False, None, last_report, notes=notes)
