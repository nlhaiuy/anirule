import unittest

from rulegen import GenerateError, generate_and_test, generate_rule, test_rule

# 1) 次元城风格：名称与链接分开 (indexed)
INDEXED = """
<html><head><title>搜索 葬送的芙莉莲</title></head><body>
<div class="box-width"><div class="search-box">
  <input value="葬送的芙莉莲">
  <div class="item"><div class="thumb-menu"><a href="/bangumi/1.html"><img src="1.jpg"></a></div>
    <div class="thumb-content"><div class="thumb-txt cor4">葬送的芙莉莲</div></div></div>
  <div class="item"><div class="thumb-menu"><a href="/bangumi/2.html"><img src="2.jpg"></a></div>
    <div class="thumb-content"><div class="thumb-txt cor4">葬送的芙莉莲 第二季</div></div></div>
  <div class="item"><div class="thumb-menu"><a href="/bangumi/3.html"><img src="3.jpg"></a></div>
    <div class="thumb-content"><div class="thumb-txt cor4">另一部无关的番</div></div></div>
</div></div></body></html>
"""

# 2) 名称就在 a 里 (a 格式)
ANCHOR = """
<html><body><ul class="results">
  <li class="row"><a class="title" href="/v/10">孤独摇滚！</a></li>
  <li class="row"><a class="title" href="/v/11">孤独摇滚！ 剧场版</a></li>
  <li class="row"><a class="title" href="/v/12">别的番</a></li>
</ul><div class="nav"><a href="/">首页</a></div></body></html>
"""

# 3) 页头回显了关键词 + 结果列表，应选结果列表
ECHO = """
<html><body>
<h2 class="hint"><a href="/search">搜索：药屋少女的呢喃</a></h2>
<div class="list">
  <div class="card"><a class="name" href="/d/1">药屋少女的呢喃</a></div>
  <div class="card"><a class="name" href="/d/2">药屋少女的呢喃 第二季</a></div>
</div></body></html>
"""

NO_RESULT = "<html><body><div class='empty'>没有找到相关内容</div></body></html>"
JS_PAGE = "<html><body><div id='app'></div><script>load('药屋少女的呢喃')</script></body></html>"


class RuleGenTest(unittest.TestCase):
    def test_indexed(self):
        rule, rep = generate_and_test(INDEXED, "葬送的芙莉莲", "https://x.com/s?wd={keyword}")
        cfg = rule["arguments"]["searchConfig"]
        self.assertEqual(cfg["subjectFormatId"], "indexed")
        self.assertTrue(rep.ok, rep.message)
        self.assertEqual(len(rep.names), 3)  # 含未包含关键词的第三项，说明规则泛化成功
        self.assertEqual(rep.links, ["/bangumi/1.html", "/bangumi/2.html", "/bangumi/3.html"])

    def test_anchor(self):
        rule, rep = generate_and_test(ANCHOR, "孤独摇滚", "https://x.com/s?q={keyword}")
        self.assertEqual(rule["arguments"]["searchConfig"]["subjectFormatId"], "a")
        self.assertTrue(rep.ok, rep.message)
        self.assertEqual(len(rep.names), 3)

    def test_prefers_result_list_over_echo(self):
        rule, rep = generate_and_test(ECHO, "药屋少女的呢喃", "https://x.com/s?q={keyword}")
        self.assertTrue(rep.ok, rep.message)
        self.assertEqual(rep.links, ["/d/1", "/d/2"])

    def test_no_result_raises(self):
        with self.assertRaises(GenerateError):
            generate_rule(NO_RESULT, "药屋少女的呢喃", "u")

    def test_js_page_raises(self):
        with self.assertRaises(GenerateError):
            generate_rule(JS_PAGE, "药屋少女的呢喃", "u")

    def test_empty_keyword(self):
        with self.assertRaises(GenerateError):
            generate_rule(ANCHOR, "  ", "u")

    def test_rule_test_detects_bad_selector(self):
        rule = generate_rule(ANCHOR, "孤独摇滚", "u")
        rule["arguments"]["searchConfig"]["selectorSubjectFormatA"]["selectLists"] = ".nothing"
        rep = test_rule(rule, ANCHOR, "孤独摇滚")
        self.assertFalse(rep.ok)

    def test_rule_test_detects_count_mismatch(self):
        rule = generate_rule(INDEXED, "葬送的芙莉莲", "u")
        c = rule["arguments"]["searchConfig"]["selectorSubjectFormatIndexed"]
        c["selectLinks"] = ".item:first-child .thumb-menu > a"
        rep = test_rule(rule, INDEXED, "葬送的芙莉莲")
        self.assertFalse(rep.ok)

    def test_selectors_have_no_nth(self):
        rule = generate_rule(INDEXED, "葬送的芙莉莲", "u")
        self.assertNotIn("nth", str(rule))


if __name__ == "__main__":
    unittest.main(verbosity=2)
