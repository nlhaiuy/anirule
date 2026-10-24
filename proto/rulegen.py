"""Animeko web-selector 规则自动生成（原型）。

输入：搜索页 HTML + 搜索关键词（页面里应能搜到该番剧）。
输出：web-selector 数据源规则 JSON（只含"搜索条目"这一步）。
"""
import json
import re
from dataclasses import dataclass, field
from typing import Optional

from bs4 import BeautifulSoup, Tag

SKIP_TAGS = {"script", "style", "head", "title", "meta", "link", "noscript",
             "input", "textarea", "option", "select", "button", "form"}
MAX_DEPTH = 10


class GenerateError(Exception):
    pass


@dataclass
class TestReport:
    ok: bool
    names: list = field(default_factory=list)
    links: list = field(default_factory=list)
    message: str = ""


def norm(s: str) -> str:
    return re.sub(r"\s+", "", s or "").lower()


def stable_classes(tag: Tag) -> list:
    out = []
    for c in tag.get("class") or []:
        # 丢弃带长数字/哈希的动态 class，如 swiper-slide-3、css-1x2y3z
        if re.search(r"\d", c):
            continue
        out.append(c)
    return out


def chain_of(tag: Tag) -> list:
    """从 body 下到 tag 的 (tag.class) 片段列表。"""
    parts = []
    cur = tag
    while cur is not None and cur.name not in (None, "[document]", "html", "body"):
        seg = cur.name + "".join("." + c for c in stable_classes(cur))
        parts.append(seg)
        cur = cur.parent
    parts.reverse()
    return parts


def select_set(soup, selector: str) -> list:
    try:
        return soup.select(selector)
    except Exception:
        return []


def minimal_selector(soup, targets: list) -> str:
    """找出能精确选中 targets 的最短 CSS 选择器（只用 class 与 '>'，不用 nth）。"""
    chains = [chain_of(t) for t in targets]
    # 取各目标链的"形状"一致的第一条；形状不同的目标会被完整链覆盖不到，交给后续校验
    chain = chains[0]
    full = " > ".join(chain)
    full_set = {id(e) for e in select_set(soup, full)}
    for d in range(1, min(len(chain), MAX_DEPTH) + 1):
        sel = " > ".join(chain[-d:])
        if {id(e) for e in select_set(soup, sel)} == full_set:
            return sel
    return full


def find_seeds(soup, keyword: str) -> list:
    key = norm(keyword)
    if not key:
        raise GenerateError("关键词为空")
    seeds = []
    for el in soup.find_all(True):
        if el.name in SKIP_TAGS or any(p.name in SKIP_TAGS for p in el.parents if p.name):
            continue
        if key not in norm(el.get_text()):
            continue
        # 只要最深的：子元素里没有再包含关键词的
        if any(isinstance(c, Tag) and c.name not in SKIP_TAGS and key in norm(c.get_text())
               for c in el.children):
            continue
        seeds.append(el)
    return seeds


def link_anchor_of(name_el: Tag) -> Optional[Tag]:
    """name 元素本身是 a，或在某个 a[href] 里面。"""
    cur = name_el
    while cur is not None and cur.name not in (None, "[document]", "body"):
        if cur.name == "a" and cur.get("href"):
            return cur
        cur = cur.parent
    return None


def sibling_link_of(name_el: Tag) -> Optional[Tag]:
    """name 元素不在 a 里时：向上找最近一个包含 a[href] 的祖先，取其第一个 a[href]。"""
    cur = name_el.parent
    while cur is not None and cur.name not in (None, "[document]", "body", "html"):
        a = cur.find("a", href=True)
        if a is not None:
            return a
        cur = cur.parent
    return None


def group_seeds(seeds: list) -> dict:
    groups = {}
    for s in seeds:
        groups.setdefault(" > ".join(chain_of(s)), []).append(s)
    return groups


def generate_rule(html: str, keyword: str, search_url: str, name: str = "示例动漫") -> dict:
    soup = BeautifulSoup(html, "html.parser")
    seeds = find_seeds(soup, keyword)
    if not seeds:
        raise GenerateError("页面里没有找到包含关键词的元素：关键词没搜到结果，或结果由 JS 动态加载")

    # 选出最像"结果列表"的一组：有可解析链接的个数多者优先
    best = None
    for key, els in group_seeds(seeds).items():
        linked = []
        for e in els:
            a = link_anchor_of(e) or sibling_link_of(e)
            if a is not None and a.get("href"):
                linked.append((e, a))
        hrefs = {a.get("href") for _, a in linked}
        score = (len(hrefs), len(linked))
        if linked and (best is None or score > best[0]):
            best = (score, els, linked)
    if best is None:
        raise GenerateError("找到了关键词，但附近没有可用的链接 (a[href])")

    _, els, linked = best
    in_anchor = all(link_anchor_of(e) is not None for e, _ in linked)

    if in_anchor:
        anchors = [link_anchor_of(e) for e, _ in linked]
        sel = minimal_selector(soup, anchors)
        args = {"subjectFormatId": "a", "selectorSubjectFormatA": {"selectLists": sel}}
    else:
        name_sel = minimal_selector(soup, [e for e, _ in linked])
        link_sel = minimal_selector(soup, [a for _, a in linked])
        args = {
            "subjectFormatId": "indexed",
            "selectorSubjectFormatIndexed": {"selectNames": name_sel, "selectLinks": link_sel},
        }

    rule = {
        "factoryId": "web-selector",
        "version": 2,
        "arguments": {
            "name": name,
            "description": "",
            "iconUrl": "",
            "searchConfig": {"searchUrl": search_url, **args},
        },
    }
    return rule


def test_rule(rule: dict, html: str, keyword: str) -> TestReport:
    """用规则在页面上实际跑一遍，判断是否可用。"""
    soup = BeautifulSoup(html, "html.parser")
    cfg = rule["arguments"]["searchConfig"]
    fmt = cfg.get("subjectFormatId")
    try:
        if fmt == "a":
            els = soup.select(cfg["selectorSubjectFormatA"]["selectLists"])
            names = [e.get_text(strip=True) for e in els]
            links = [e.get("href") for e in els if e.name == "a"]
        elif fmt == "indexed":
            c = cfg["selectorSubjectFormatIndexed"]
            names = [e.get_text(strip=True) for e in soup.select(c["selectNames"])]
            links = [e.get("href") for e in soup.select(c["selectLinks"])]
        else:
            return TestReport(False, message=f"暂不支持的条目格式: {fmt}")
    except Exception as e:  # 选择器语法错误
        return TestReport(False, message=f"选择器无效: {e}")

    if not names:
        return TestReport(False, names, links, "选择器没有选中任何条目")
    if len(names) != len(links):
        return TestReport(False, names, links, f"名称数 {len(names)} 与链接数 {len(links)} 不一致")
    if any(not l for l in links):
        return TestReport(False, names, links, "存在空链接")
    if not any(norm(keyword) in norm(n) for n in names):
        return TestReport(False, names, links, "选中的条目里没有包含搜索关键词的标题")
    return TestReport(True, names, links, f"通过：{len(names)} 个条目")


def generate_and_test(html: str, keyword: str, search_url: str, name: str = "示例动漫"):
    rule = generate_rule(html, keyword, search_url, name)
    return rule, test_rule(rule, html, keyword)


if __name__ == "__main__":
    import sys
    html = open(sys.argv[1], encoding="utf-8").read()
    rule, rep = generate_and_test(html, sys.argv[2], sys.argv[3])
    print(json.dumps(rule, ensure_ascii=False, indent=2))
    print(rep.message)
