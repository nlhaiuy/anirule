import json
import unittest
from urllib.parse import quote

from flow import run
from jsonapi import jp_select

SEARCH = [{"id": 101, "title": "葬送的芙莉莲", "score": 9.1},
          {"id": 102, "title": "葬送的芙莉莲 第二季", "score": 8.9},
          {"id": 103, "title": "别的番", "score": 7.0}]
DETAIL = {"id": 101, "sources": [{"name": "线路A"}, {"name": "线路B"}],
          "episodes": [{"id": 9001, "title": "冒险的结束", "episode_number": 1, "kind": "main"},
                       {"id": 9002, "title": "下一站", "episode_number": 2, "kind": "main"}]}
PLAY = {"candidates": [{"source_name": "线路A", "url": "https://cdn.example.com/a.m3u8"}]}
HEADERS = {"apikey": "k123", "Authorization": "Bearer k123", "Content-Type": "application/json"}

RESULTS_HTML = """<html><body><ul class="results">
<li class="row"><a class="title" href="/v/1">{kw}</a></li>
<li class="row"><a class="title" href="/v/2">{kw} 第二季</a></li></ul></body></html>"""


class FakeBrowser:
    def __init__(self, site):
        self.site, self._records, self._url = site, [], ""
        self.dom_html = "<html><body></body></html>"
        site.b = self

    def load(self, url):
        self._url = url
        self.site.reset()

    def call(self, fn, *args):
        return getattr(self.site, fn)(*args)

    def current_url(self):
        return self._url

    def wait_idle(self):
        pass

    def records(self):
        return self._records

    def emit(self, url, body, response, method="POST", headers=None, status=200):
        self._records.append({"type": "fetch", "url": url, "method": method, "headers": headers or {},
                              "body": body, "status": status, "response": json.dumps(response, ensure_ascii=False)})


class Site:
    """默认：什么都找不到。"""
    def reset(self): pass
    def fillSearch(self, kw): return "no-input"
    def openSearch(self): return "none"
    def clickResult(self, kw): return "none"
    def clickEpisode(self): return "none"
    def clickPlay(self): return "none"
    def dom(self): return ""


class JsonSite(Site):
    def __init__(self, results_for=("葬送的芙莉莲",), with_detail=True, with_play=True, need_play_click=False,
                 hidden_search=False):
        self.results_for, self.with_detail, self.with_play = results_for, with_detail, with_play
        self.need_play_click, self.hidden_search, self.opened = need_play_click, hidden_search, False

    def reset(self):
        self.opened = False

    def openSearch(self):
        if self.hidden_search:
            self.opened = True
            return "ok"
        return "none"

    def fillSearch(self, kw):
        if self.hidden_search and not self.opened:
            return "no-input"
        data = [{"id": 101 + i, "title": t.replace("葬送的芙莉莲", kw)} for i, t in
                enumerate(x["title"] for x in SEARCH)] if kw in self.results_for else []
        self.b.emit("https://api.site.example/rpc/search",
                    json.dumps({"search_term": kw, "page_number": 1, "items_per_page": 50},
                               ensure_ascii=False, separators=(",", ":")),
                    data, headers=HEADERS)
        return "ok:button"

    def clickResult(self, kw):
        self.b._url = "https://site.example/anime/101"
        if self.with_detail:
            self.b.emit("https://api.site.example/rpc/detail", '{"p_id":101}', DETAIL, headers=HEADERS)
        return "ok:a"

    def clickEpisode(self):
        self.b._url = "https://site.example/anime/101/play/9001"
        if self.with_play and not self.need_play_click:
            self.b.emit("https://api.site.example/fn/issue", '{"action":"fallback","episode_id":9001}', PLAY, headers=HEADERS)
        return "ok"

    def clickPlay(self):
        if self.with_play and self.need_play_click:
            self.b.emit("https://api.site.example/fn/issue", '{"action":"fallback","episode_id":9001}', PLAY, headers=HEADERS)
            return "ok"
        return "none"


class HtmlSite(Site):
    def fillSearch(self, kw):
        self.b._url = "https://x.com/search.html?wd=" + quote(kw)
        self.b.dom_html = RESULTS_HTML.format(kw=kw)
        return "ok:enter"

    def dom(self):
        return self.b.dom_html


KW = ["葬送的芙莉莲", "孤独摇滚"]


def go(site, kws=KW):
    return run("https://site.example/", FakeBrowser(site), kws)


class FlowTest(unittest.TestCase):
    def test_json_site_full_auto(self):
        r = go(JsonSite())
        self.assertTrue(r.ok, r.notes)
        self.assertEqual((r.mode, r.partial), ("json-api", False))
        cfg = r.rule["arguments"]["searchConfig"]
        s = cfg["search"]
        self.assertEqual(s["request"]["url"], "https://api.site.example/rpc/search")
        self.assertEqual(s["request"]["body"], '{"search_term":"{keyword}","page_number":{page},"items_per_page":{pageSize}}')
        self.assertEqual(s["pageSize"], 50)
        self.assertEqual(s["subjectUrlTemplate"], "https://site.example/anime/{id}")
        self.assertEqual(cfg["subject"]["request"]["body"], '{"p_id":{id}}')
        self.assertEqual(cfg["subject"]["episodes"]["episodeUrlTemplate"],
                         "https://site.example/anime/{subjectId}/play/{id}")
        self.assertEqual(cfg["play"]["request"]["body"], '{"action":"fallback","episode_id":{id}}')
        self.assertEqual(cfg["baseUrl"], "https://api.site.example")
        self.assertEqual(cfg["headers"], "apikey: k123\nAuthorization: Bearer k123")
        self.assertIn("线路 2 条", r.report.message)

    def test_json_rule_works_on_captured_responses(self):
        r = go(JsonSite())
        cfg = r.rule["arguments"]["searchConfig"]
        self.assertEqual(len(jp_select(SEARCH, cfg["search"]["selectItems"])), 3)

    def test_second_keyword_when_first_has_no_results(self):
        r = go(JsonSite(results_for=("孤独摇滚",)))
        self.assertTrue(r.ok, r.notes)
        self.assertEqual(r.keyword, "孤独摇滚")

    def test_hidden_search_box_is_opened(self):
        r = go(JsonSite(hidden_search=True))
        self.assertTrue(r.ok, r.notes)

    def test_play_needs_extra_click(self):
        r = go(JsonSite(need_play_click=True))
        self.assertTrue(r.ok, r.notes)
        self.assertFalse(r.partial)

    def test_no_detail_gives_partial_rule(self):
        r = go(JsonSite(with_detail=False))
        self.assertTrue(r.ok, r.notes)
        self.assertTrue(r.partial)
        self.assertTrue(any("详情" in n for n in r.notes))

    def test_no_play_is_partial(self):
        r = go(JsonSite(with_play=False))
        self.assertTrue(r.ok, r.notes)
        self.assertTrue(r.partial)
        self.assertTrue(any("播放" in n for n in r.notes))

    def test_html_site(self):
        r = go(HtmlSite())
        self.assertTrue(r.ok, r.notes)
        self.assertEqual(r.mode, "html")
        self.assertEqual(r.rule["arguments"]["searchConfig"]["searchUrl"], "https://x.com/search.html?wd={keyword}")

    def test_no_search_box(self):
        r = go(Site())
        self.assertFalse(r.ok)
        self.assertTrue(any("搜索框" in n for n in r.notes))

    def test_nothing_found_anywhere(self):
        r = go(JsonSite(results_for=()))
        self.assertFalse(r.ok)


if __name__ == "__main__":
    unittest.main(verbosity=2)
