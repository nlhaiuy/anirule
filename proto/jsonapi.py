"""Animeko json-api 数据源规则生成（原型）。

输入：搜索/详情/播放三个阶段各自的"请求模板 + 一份 JSON 响应样本"。
输出：json-api v1 规则（字段结构与 Animeko 编辑器导出的空白模板一致）。
只用到 JSONPath 的子集：$  .field  [*]
"""
import copy
import json
import re
from typing import Any, Optional

from rulegen import TestReport, norm

NAME_KEYS = ["title", "name", "vod_name", "title_cn", "anime_name", "subject_name"]
ALIAS_KEYS = ["aliases", "alias", "other_names", "other_name", "vod_sub", "original_title",
              "title_original", "title_en", "title_jp", "name_en"]
SORT_KEYS = ["episode_number", "episode", "sort", "ep", "number", "index", "order", "num"]
EP_NAME_KEYS = ["title", "name", "episode_title"]
TYPE_KEYS = ["kind", "type"]
CHANNEL_KEYS = ["source_name", "line_name", "channel", "source", "line", "from", "name"]
URL_KEYS = ["url", "play_url", "video_url", "m3u8", "src", "link"]

DEFAULT_TYPE_MAPPING = "main=MainStory\nsp=SP\nova=OVA\noad=OAD\nmovie=MainStory\nex=SP"


def _req(url="", method="GET", headers="", body=""):
    return {"url": url, "method": method, "headers": headers, "body": body}


def default_args(name: str = "JSON API") -> dict:
    """与 Animeko 编辑器导出的空白 json-api 模板同构。"""
    return {
        "name": name, "description": "", "iconUrl": "",
        "searchConfig": {
            "baseUrl": "", "headers": "", "userAgent": "",
            "requestInterval": 1000, "searchCacheTtl": 7200000,
            "search": {
                "request": _req(), "pageSize": 20,
                "selectItems": "$[*]", "selectName": "$.title", "selectId": "$.id",
                "selectAliases": "", "subjectUrlTemplate": "",
            },
            "subject": {
                "request": _req(),
                "episodes": {
                    "selectEpisodes": "$[*]", "selectName": "$.title", "selectId": "$.id",
                    "selectSort": "$.episode_number", "selectType": "$.kind",
                    "typeMapping": DEFAULT_TYPE_MAPPING,
                    "matchEpisodeSortFromName": "第\\s*(?<ep>.+)\\s*[话集]",
                    "nameTemplate": "{sort} {name}", "episodeUrlTemplate": "", "selectVideoUrl": "",
                },
                "channelFormatId": "no-channel",
                "channelFormatGrouped": {"selectChannels": "$.lines[*]", "selectChannelName": "$.name"},
                "channelFormatDeclared": {"channels": []},
            },
            "play": {
                "request": _req(), "selectCandidates": "", "selectCandidateChannel": "",
                "selectVideoUrl": "$.url", "fallbackToFirstCandidate": True,
                "referer": "", "videoHeaders": "",
            },
            "defaultResolution": "1080P", "defaultSubtitleLanguage": "CHS",
            "onlySupportsPlayers": [],
            "autoMatch": {
                "enabled": True, "searchUseOnlyFirstWord": True, "searchRemoveSpecial": True,
                "searchUseSubjectNamesCount": 1, "preferShorterName": True, "filterByEpisodeSort": True,
            },
        },
        "tier": 2, "channelTiers": {},
    }


# ---------- 迷你 JSONPath ----------

_TOKEN = re.compile(r"\.([A-Za-z0-9_\-]+)|\[\*\]|\['([^']+)'\]")


def jp_select(doc: Any, path: str) -> list:
    """支持 $ / .field / [*] / ['field']。空路径返回 []。"""
    if not path or not path.startswith("$"):
        return []
    cur = [doc]
    pos = 1
    while pos < len(path):
        m = _TOKEN.match(path, pos)
        if not m:
            return []
        nxt = []
        if m.group(0) == "[*]":
            for c in cur:
                if isinstance(c, list):
                    nxt.extend(c)
        else:
            key = m.group(1) or m.group(2)
            for c in cur:
                if isinstance(c, dict) and key in c:
                    nxt.append(c[key])
        cur = nxt
        pos = m.end()
    return cur


def _scalar(v) -> bool:
    return isinstance(v, (str, int, float)) and not isinstance(v, bool)


def find_arrays(doc: Any, path: str = "$") -> list:
    """所有"元素为对象"的数组：[(路径如 $.data.list[*], 数组)]，浅层在前。"""
    out = []
    if isinstance(doc, list):
        if doc and all(isinstance(x, dict) for x in doc):
            out.append((path + "[*]", doc))
    elif isinstance(doc, dict):
        for k, v in doc.items():
            if isinstance(v, (list, dict)):
                out.extend(find_arrays(v, f"{path}.{k}" if re.fullmatch(r"[A-Za-z0-9_\-]+", k) else f"{path}['{k}']"))
    out.sort(key=lambda x: x[0].count("."))
    return out


def _first_key(items: list, keys: list, pred=_scalar) -> Optional[str]:
    for k in keys:
        if sum(1 for it in items if k in it and pred(it[k])) >= max(1, len(items) // 2):
            return k
    return None


def _id_key(items: list, exclude=()) -> Optional[str]:
    cands = [k for k in items[0] if k not in exclude and _scalar(items[0][k])]
    pri = [k for k in cands if k == "id"] + [k for k in cands if k.endswith("_id") or k.endswith("Id")] + \
          [k for k in cands if k in ("slug", "uid", "vod_id")]
    for k in pri + cands:
        vals = [it.get(k) for it in items]
        if all(_scalar(v) and str(v) != "" for v in vals) and len({str(v) for v in vals}) == len(vals):
            return k
    return None


# ---------- 三个阶段的推导 ----------

def infer_search(resp: Any, keyword: str) -> dict:
    key = norm(keyword)
    best = None
    for path, arr in find_arrays(resp):
        for nk in NAME_KEYS + [k for k in arr[0] if k not in NAME_KEYS]:
            hits = sum(1 for it in arr if isinstance(it.get(nk), str) and key in norm(it[nk]))
            if hits and (best is None or hits > best[0]):
                best = (hits, path, arr, nk)
        if best:  # 浅层优先：已命中就不再看更深的数组
            break
    if best is None:
        raise ValueError("响应里没有找到包含关键词的条目：检查关键词、请求，或这个接口不是搜索接口")
    _, path, arr, nk = best
    ik = _id_key(arr, exclude=(nk,))
    if ik is None:
        raise ValueError("条目里找不到可用作 ID 的字段（需要每项唯一且非空）")
    out = {"selectItems": path, "selectName": f"$.{nk}", "selectId": f"$.{ik}"}
    ak = next((k for k in ALIAS_KEYS if k in arr[0] and k != nk), None)
    out["selectAliases"] = f"$.{ak}" if ak else ""
    return out


def infer_subject(resp: Any) -> dict:
    arrays = find_arrays(resp)
    ep = None
    for path, arr in arrays:
        sk = _first_key(arr, SORT_KEYS)
        if sk:
            ep = (path, arr, sk)
            break
    if ep is None:
        raise ValueError("详情响应里没有找到剧集数组（需要带集数字段，如 episode_number）")
    path, arr, sk = ep
    nk = _first_key(arr, EP_NAME_KEYS) or sk
    ik = _id_key(arr, exclude=(nk, sk))
    tk = _first_key(arr, TYPE_KEYS)
    uk = _first_key(arr, URL_KEYS, pred=lambda v: isinstance(v, str) and v.startswith("http"))
    episodes = {
        "selectEpisodes": path, "selectName": f"$.{nk}", "selectId": f"$.{ik}" if ik else "",
        "selectSort": f"$.{sk}", "selectType": f"$.{tk}" if tk else "",
        "selectVideoUrl": f"$.{uk}" if uk else "",
    }
    # 与剧集同级的"线路"数组
    chan = None
    for p, a in arrays:
        if p == path or any(k in a[0] for k in SORT_KEYS):
            continue
        ck = _first_key(a, ["name", "title", "label"])
        if ck:
            chan = (p, ck)
            break
    out = {"episodes": episodes, "channelFormatId": "no-channel"}
    if chan:
        out["channelFormatId"] = "grouped"
        out["channelFormatGrouped"] = {"selectChannels": chan[0], "selectChannelName": f"$.{chan[1]}"}
    return out


def _is_http(v) -> bool:
    return isinstance(v, str) and v.startswith("http")


def infer_play(resp: Any) -> dict:
    for path, arr in find_arrays(resp):
        uk = _first_key(arr, URL_KEYS, pred=_is_http)
        if uk:
            ck = _first_key(arr, [k for k in CHANNEL_KEYS if k != uk])
            return {"selectCandidates": path, "selectCandidateChannel": f"$.{ck}" if ck else "",
                    "selectVideoUrl": f"$.{uk}", "fallbackToFirstCandidate": ck is None}
    if isinstance(resp, dict):
        for k in URL_KEYS:
            if _is_http(resp.get(k)):
                return {"selectCandidates": "", "selectCandidateChannel": "",
                        "selectVideoUrl": f"$.{k}", "fallbackToFirstCandidate": True}
    raise ValueError("播放响应里没有找到视频地址（需要以 http 开头的字符串）")


# ---------- 生成与测试 ----------

def _literal_to_placeholder(text: str, literal: str, placeholder: str) -> str:
    return text.replace(literal, placeholder) if literal else text


def generate_json_api_rule(name: str, *, search_request: dict, search_response: Any, keyword: str,
                           subject_request: Optional[dict] = None, subject_response: Any = None,
                           play_request: Optional[dict] = None, play_response: Any = None,
                           headers: str = "", base_url: str = "", page_size: Optional[int] = None,
                           subject_url_template: str = "", episode_url_template: str = "") -> dict:
    """request 形如 {"url","method","headers","body"}；其中的关键词字面量会被换成 {keyword}。"""
    args = default_args(name)
    cfg = args["searchConfig"]
    cfg["baseUrl"], cfg["headers"] = base_url, headers
    if page_size:
        cfg["search"]["pageSize"] = page_size
    cfg["search"]["subjectUrlTemplate"] = subject_url_template
    cfg["subject"]["episodes"]["episodeUrlTemplate"] = episode_url_template

    sreq = {**_req(), **search_request}
    for f in ("url", "body"):
        sreq[f] = _literal_to_placeholder(sreq[f], keyword, "{keyword}")
    cfg["search"]["request"] = sreq
    cfg["search"].update(infer_search(search_response, keyword))

    if subject_request is not None and subject_response is not None:
        cfg["subject"]["request"] = {**_req(), **subject_request}
        inf = infer_subject(subject_response)
        cfg["subject"]["episodes"].update(inf["episodes"])
        cfg["subject"]["channelFormatId"] = inf["channelFormatId"]
        if "channelFormatGrouped" in inf:
            cfg["subject"]["channelFormatGrouped"] = inf["channelFormatGrouped"]
    if play_request is not None and play_response is not None:
        cfg["play"]["request"] = {**_req(), **play_request}
        cfg["play"].update(infer_play(play_response))

    return {"factoryId": "json-api", "version": 1, "arguments": args}


def wrap_export(rule: dict) -> dict:
    """Animeko 编辑器导出/导入用的外层包装。"""
    return {"schemaVersion": 2, "mediaSources": [rule]}


def test_json_api_rule(rule: dict, keyword: str, search_response: Any,
                       subject_response: Any = None, play_response: Any = None) -> TestReport:
    cfg = rule["arguments"]["searchConfig"]
    s = cfg["search"]
    items = jp_select(search_response, s["selectItems"])
    if not items:
        return TestReport(False, message="搜索：selectItems 没有选中任何条目")
    names = [x for it in items for x in jp_select(it, s["selectName"])]
    ids = [str(x) for it in items for x in jp_select(it, s["selectId"])]
    if len(names) != len(items) or len(ids) != len(items):
        return TestReport(False, names, ids, "搜索：名称或 ID 的数量与条目数不一致")
    if len(set(ids)) != len(ids):
        return TestReport(False, names, ids, "搜索：ID 有重复")
    if not any(norm(keyword) in norm(str(n)) for n in names):
        return TestReport(False, names, ids, "搜索：没有标题包含关键词")
    msg = [f"搜索 {len(items)} 项"]

    if subject_response is not None:
        sub = cfg["subject"]
        eps = jp_select(subject_response, sub["episodes"]["selectEpisodes"])
        if not eps:
            return TestReport(False, names, ids, "详情：selectEpisodes 没有选中任何剧集")
        sorts = [x for e in eps for x in jp_select(e, sub["episodes"]["selectSort"])]
        if len(sorts) != len(eps):
            return TestReport(False, names, ids, "详情：集数字段缺失")
        msg.append(f"剧集 {len(eps)} 集")
        if sub["channelFormatId"] == "grouped":
            ch = jp_select(subject_response, sub["channelFormatGrouped"]["selectChannels"])
            if not ch:
                return TestReport(False, names, ids, "详情：selectChannels 没有选中任何线路")
            msg.append(f"线路 {len(ch)} 条")

    if play_response is not None:
        p = cfg["play"]
        cands = jp_select(play_response, p["selectCandidates"]) if p["selectCandidates"] else [play_response]
        urls = [x for c in cands for x in jp_select(c, p["selectVideoUrl"])]
        if not urls or not all(_is_http(u) for u in urls):
            return TestReport(False, names, ids, "播放：没有取到以 http 开头的视频地址")
        msg.append(f"视频地址 {len(urls)} 个")

    return TestReport(True, names, ids, "通过：" + "，".join(msg))


if __name__ == "__main__":
    print(json.dumps(default_args(), ensure_ascii=False)[:200])
