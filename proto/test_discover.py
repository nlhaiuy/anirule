import unittest
from urllib.parse import quote

from discover import discover, search_templates_from_home

HOME_GET = """<html><body><form action="/search.html" method="get">
<input type="hidden" name="type" value="1"><input type="text" name="wd"></form></body></html>"""
HOME_POST = """<html><body><form action="/s" method="post"><input type="text" name="q"></form></body></html>"""
HOME_PLAIN = "<html><body>首页</body></html>"

RESULTS = """<html><body><ul class="results">
<li class="row"><a class="title" href="/v/1">{kw}</a></li>
<li class="row"><a class="title" href="/v/2">{kw} 第二季</a></li></ul></body></html>"""
EMPTY = "<html><body><div>没有找到相关内容</div></body></html>"


def make_fetch(pages: dict, results_for=("葬送的芙莉莲",), results_path=None):
    def fetch(url):
        if url in pages:
            return pages[url]
        if results_path and url.startswith(results_path):
            for kw in results_for:
                if quote(kw) in url:
                    return RESULTS.format(kw=kw)
            return EMPTY
        raise IOError("404")
    return fetch


class DiscoverTest(unittest.TestCase):
    def test_form_template_with_hidden(self):
        notes = []
        tpls = search_templates_from_home(HOME_GET, "https://x.com/", notes)
        self.assertEqual(tpls, ["https://x.com/search.html?type=1&wd={keyword}"])

    def test_post_form_noted(self):
        notes = []
        self.assertEqual(search_templates_from_home(HOME_POST, "https://x.com/", notes), [])
        self.assertTrue(any("POST" in n for n in notes))

    def test_discover_from_home_form(self):
        fetch = make_fetch({"https://x.com/": HOME_GET}, results_path="https://x.com/search.html?type=1&wd=")
        r = discover("https://x.com/", fetch)
        self.assertTrue(r.ok, r.notes)
        self.assertEqual(r.search_url, "https://x.com/search.html?type=1&wd={keyword}")
        self.assertEqual(r.keyword, "葬送的芙莉莲")

    def test_tries_next_keyword(self):
        fetch = make_fetch({"https://x.com/": HOME_GET}, results_for=("鬼灭之刃",),
                           results_path="https://x.com/search.html?type=1&wd=")
        r = discover("https://x.com/", fetch)
        self.assertTrue(r.ok, r.notes)
        self.assertEqual(r.keyword, "鬼灭之刃")

    def test_fallback_common_path(self):
        fetch = make_fetch({"https://x.com/": HOME_PLAIN}, results_path="https://x.com/search.html?wd=")
        r = discover("https://x.com/", fetch)
        self.assertTrue(r.ok, r.notes)
        self.assertEqual(r.search_url, "https://x.com/search.html?wd={keyword}")

    def test_user_gives_search_template(self):
        fetch = make_fetch({}, results_path="https://x.com/q?k=")
        r = discover("https://x.com/q?k={keyword}", fetch)
        self.assertTrue(r.ok, r.notes)

    def test_json_response_is_reported(self):
        pages = {"https://x.com/": HOME_PLAIN}
        fetch = lambda u: pages.get(u, '{"list": []}')
        r = discover("https://x.com/", fetch)
        self.assertFalse(r.ok)
        self.assertTrue(any("json-api" in n for n in r.notes))

    def test_unreachable(self):
        def fetch(u):
            raise IOError("boom")
        r = discover("https://x.com/", fetch)
        self.assertFalse(r.ok)
        self.assertTrue(any("无法访问" in n for n in r.notes))

    def test_no_results_anywhere(self):
        fetch = lambda u: EMPTY
        r = discover("https://x.com/", fetch)
        self.assertFalse(r.ok)


if __name__ == "__main__":
    unittest.main(verbosity=2)
