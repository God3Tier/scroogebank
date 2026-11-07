"""Tests for scripts/seed-demo-portfolios.py. Run: python -m unittest scripts/test_seed_demo_portfolios.py"""
import importlib.util
import pathlib
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location(
    "seed", pathlib.Path(__file__).with_name("seed-demo-portfolios.py"))
seed = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(seed)


class PositionsTest(unittest.TestCase):
    def held(self, profile, index):
        return {p["instrumentId"]: p["quantity"] for p in seed.positions_for(profile, index)}

    def test_the_dbs_profile_is_unchanged_for_every_account(self):
        expected = {"SGX-D05": 1000, "SGX-O39": 500}
        for index in range(4):
            self.assertEqual(self.held("dbs", index), expected)

    def test_the_news_profile_gives_one_client_several_tickers(self):
        self.assertEqual(self.held("news", 0), {"LSE-SHEL": 600, "PRIV-ANTHROPIC": 400, "NASDAQ-AAPL": 60})
        self.assertEqual(self.held("news", 1), {"NASDAQ-AAPL": 60, "PRIV-ANTHROPIC": 140})
        self.assertEqual(self.held("news", 2), {"NASDAQ-AAPL": 60})
        self.assertEqual(self.held("news", 7), {"NASDAQ-AAPL": 60})

    def test_every_news_position_is_complete_and_a_copy(self):
        for index in range(3):
            for position in seed.positions_for("news", index):
                self.assertEqual(
                    {"instrumentId", "quantity", "unitPrice", "valuationCurrency", "fxToSgd"}, set(position))
        first = seed.positions_for("news", 0)
        first[0]["quantity"] = 1
        self.assertEqual(seed.positions_for("news", 0)[0]["quantity"], 600, "a change must not leak into the profile")


class WebsitesAndSearchTest(unittest.TestCase):
    def test_websites_added_or_already_searched_are_fine_and_anything_else_stops(self):
        replies = iter([(201, {}), (409, {"code": "ALREADY_COVERED"}), (201, {}), (201, {}), (201, {})])
        with mock.patch.object(seed, "request", side_effect=lambda *a, **k: next(replies)) as call:
            seed.add_websites("http://x", "t")
        self.assertEqual([c.args[4]["domain"] for c in call.call_args_list], seed.NEWS_WEBSITES)
        with mock.patch.object(seed, "request", return_value=(400, {"code": "BLOCKED_DOMAIN"})):
            with self.assertRaises(SystemExit):
                seed.add_websites("http://x", "t")

    def test_search_names_the_company_and_stops_when_it_is_not_held(self):
        with mock.patch.object(seed, "request", return_value=(200, {"outcome": "STARTED"})) as call:
            seed.search_now("http://x", "t", "LSE-SHEL")
        self.assertEqual(call.call_args.args[4], {"instrumentId": "LSE-SHEL"})
        with mock.patch.object(seed, "request", return_value=(403, {})):
            with self.assertRaises(SystemExit):
                seed.search_now("http://x", "t", "LSE-SHEL")


if __name__ == "__main__":
    unittest.main()
