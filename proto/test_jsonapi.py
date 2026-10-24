import json
import os
import unittest

from jsonapi import (default_args, find_arrays, generate_json_api_rule, infer_play, infer_search,
                     infer_subject, jp_select, test_json_api_rule, wrap_export)

HERE = os.path.dirname(__file__)


def load(name):
    with open(os.path.join(HERE, "fixtures", name), encoding="utf-8") as f:
        return json.load(f)


def key_tree(d):
    """只比较键结构（忽略值与列表内容）。"""
    if isinstance(d, dict):
        return {k: key_tree(v) for k, v in d.items()}
    return None


SEARCH = [{"id": 101, "title": "葬送的芙莉莲", "score": 9.1},
          {"id": 102, "title": "葬送的芙莉莲 第二季", "score": 8.9},
          {"id": 103, "title": "别的番", "score": 7.0}]
DETAIL = {"id": 101, "sources": [{"name": "线路A"}, {"name": "线路B"}],
          "episodes": [{"id": 9001, "title": "冒险的结束", "episode_number": 1, "kind": "main"},
                       {"id": 9002, "title": "下一站", "episode_number": 2, "kind": "main"}]}
PLAY = {"candidates": [{"source_name": "线路A", "url": "https://cdn.example.com/a.m3u8"},
                       {"source_name": "线路B", "url": "https://cdn.example.com/b.m3u8"}]}
NESTED_SEARCH = {"code": 0, "data": {"total": 2, "list": [
    {"vod_id": "7", "vod_name": "孤独摇滚！", "vod_sub": "Bocchi the Rock!"},
    {"vod_id": "8", "vod_name": "孤独摇滚！ 剧场版", "vod_sub": ""}]}}


class JsonPathTest(unittest.TestCase):
    def test_basic(self):
        doc = {"a": {"b": [{"c": 1}, {"c": 2}]}}
        self.assertEqual(jp_select(doc, "$.a.b[*].c"), [1, 2])
        self.assertEqual(jp_select([{"x": 1}], "$[*]"), [{"x": 1}])
        self.assertEqual(jp_select(doc, "$.a.nope"), [])
        self.assertEqual(jp_select(doc, ""), [])

    def test_find_arrays_shallow_first(self):
        paths = [p for p, _ in find_arrays(DETAIL)]
        self.assertEqual(sorted(paths), ["$.episodes[*]", "$.sources[*]"])


class SchemaTest(unittest.TestCase):
    def test_default_args_matches_blank_template(self):
        blank = load("blank_json_api.json")["mediaSources"][0]["arguments"]
        self.assertEqual(key_tree(default_args("JSON API")), key_tree(blank))
        self.assertEqual(default_args("JSON API"), blank)

    def test_generated_rule_is_superset_of_real_sample(self):
        sample = load("sample_json_api.json")["arguments"]
        rule = generate_json_api_rule(
            "x", search_request={"url": "https://a.com/s", "method": "POST", "body": "{\"q\":\"葬送的芙莉莲\"}"},
            search_response=SEARCH, keyword="葬送的芙莉莲")["arguments"]

        def missing(a, b, path=""):
            out = []
            for k, v in a.items():
                if k not in b:
                    out.append(path + k)
                elif isinstance(v, dict) and isinstance(b[k], dict):
                    out += missing(v, b[k], path + k + ".")
            return out
        self.assertEqual(missing(sample, rule), [])


class InferTest(unittest.TestCase):
    def test_search_top_level_array(self):
        r = infer_search(SEARCH, "葬送的芙莉莲")
        self.assertEqual((r["selectItems"], r["selectName"], r["selectId"]), ("$[*]", "$.title", "$.id"))

    def test_search_nested_with_alias(self):
        r = infer_search(NESTED_SEARCH, "孤独摇滚")
        self.assertEqual(r["selectItems"], "$.data.list[*]")
        self.assertEqual(r["selectName"], "$.vod_name")
        self.assertEqual(r["selectId"], "$.vod_id")
        self.assertEqual(r["selectAliases"], "$.vod_sub")

    def test_search_keyword_missing(self):
        with self.assertRaises(ValueError):
            infer_search(SEARCH, "不存在的番")

    def test_search_duplicate_ids_rejected(self):
        with self.assertRaises(ValueError):
            infer_search([{"id": 1, "title": "葬送的芙莉莲"}, {"id": 1, "title": "葬送的芙莉莲2"}], "葬送")

    def test_subject_grouped(self):
        r = infer_subject(DETAIL)
        self.assertEqual(r["episodes"]["selectEpisodes"], "$.episodes[*]")
        self.assertEqual(r["episodes"]["selectSort"], "$.episode_number")
        self.assertEqual(r["episodes"]["selectType"], "$.kind")
        self.assertEqual(r["channelFormatId"], "grouped")
        self.assertEqual(r["channelFormatGrouped"]["selectChannels"], "$.sources[*]")

    def test_subject_no_channel(self):
        r = infer_subject({"episodes": [{"id": 1, "name": "a", "ep": 1}]})
        self.assertEqual(r["channelFormatId"], "no-channel")

    def test_subject_without_episodes_fails(self):
        with self.assertRaises(ValueError):
            infer_subject({"foo": [{"a": 1}]})

    def test_play_candidates(self):
        r = infer_play(PLAY)
        self.assertEqual(r["selectCandidates"], "$.candidates[*]")
        self.assertEqual(r["selectCandidateChannel"], "$.source_name")
        self.assertEqual(r["selectVideoUrl"], "$.url")
        self.assertFalse(r["fallbackToFirstCandidate"])

    def test_play_single_object(self):
        r = infer_play({"url": "https://x.com/v.mp4"})
        self.assertEqual((r["selectCandidates"], r["selectVideoUrl"]), ("", "$.url"))

    def test_play_no_url_fails(self):
        with self.assertRaises(ValueError):
            infer_play({"candidates": [{"url": "relative/path"}]})


class GenerateTest(unittest.TestCase):
    def build(self):
        return generate_json_api_rule(
            "示例 JSON 源",
            search_request={"url": "https://api.x.com/search", "method": "POST",
                            "body": '{"search_term":"葬送的芙莉莲","page":1}'},
            search_response=SEARCH, keyword="葬送的芙莉莲",
            subject_request={"url": "https://api.x.com/detail", "method": "POST", "body": '{"p_id":{id}}'},
            subject_response=DETAIL,
            play_request={"url": "https://api.x.com/play", "method": "POST", "body": '{"episode_id":{id}}'},
            play_response=PLAY)

    def test_keyword_literal_becomes_placeholder(self):
        s = self.build()["arguments"]["searchConfig"]["search"]["request"]
        self.assertIn("{keyword}", s["body"])
        self.assertNotIn("葬送", s["body"])

    def test_full_pipeline_passes_test(self):
        rule = self.build()
        rep = test_json_api_rule(rule, "葬送的芙莉莲", SEARCH, DETAIL, PLAY)
        self.assertTrue(rep.ok, rep.message)
        self.assertIn("线路 2 条", rep.message)

    def test_export_wrapper(self):
        w = wrap_export(self.build())
        self.assertEqual(w["schemaVersion"], 2)
        self.assertEqual(w["mediaSources"][0]["factoryId"], "json-api")

    def test_detects_broken_selector(self):
        rule = self.build()
        rule["arguments"]["searchConfig"]["search"]["selectItems"] = "$.nothing[*]"
        self.assertFalse(test_json_api_rule(rule, "葬送的芙莉莲", SEARCH).ok)

    def test_detects_bad_play_url(self):
        rule = self.build()
        rule["arguments"]["searchConfig"]["play"]["selectVideoUrl"] = "$.source_name"
        self.assertFalse(test_json_api_rule(rule, "葬送的芙莉莲", SEARCH, DETAIL, PLAY).ok)


if __name__ == "__main__":
    unittest.main(verbosity=2)
