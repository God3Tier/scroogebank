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

    def test_the_news_profile_gives_the_first_three_clients_their_fixed_holdings(self):
        self.assertEqual(self.held("news", 0), {"NASDAQ-AAPL": 60})
        self.assertEqual(self.held("news", 1), {"NASDAQ-AAPL": 60, "PRIV-ANTHROPIC": 140})
        self.assertEqual(self.held("news", 2), {"NASDAQ-AAPL": 60, "PRIV-ANTHROPIC": 140, "LSE-SHEL": 400})

    def test_thirty_more_clients_hold_a_mix_of_the_three_companies_and_the_six_others(self):
        counts = {}
        for index in range(len(seed.DEMO_CLIENTS)):
            for instrument in self.held("news", index):
                counts[instrument] = counts.get(instrument, 0) + 1
        self.assertEqual(len(seed.DEMO_CLIENTS), 33)
        self.assertEqual((counts["NASDAQ-AAPL"], counts["PRIV-ANTHROPIC"], counts["LSE-SHEL"]), (20, 19, 17))
        self.assertEqual(set(counts) - {"NASDAQ-AAPL", "PRIV-ANTHROPIC", "LSE-SHEL"},
                         {"NASDAQ-MSFT", "NASDAQ-NVDA", "NYSE-JPM", "NYSE-V", "HKEX-0700", "LSE-HSBA"})
        for index in range(3, 33):
            self.assertGreaterEqual(len(self.held("news", index)), 2, f"client {index} holds a mix")

    def test_a_client_after_the_demo_clients_holds_apple_only(self):
        self.assertEqual(self.held("news", 40), {"NASDAQ-AAPL": 60})

    def test_every_news_position_is_complete_and_a_copy(self):
        for index in range(len(seed.DEMO_CLIENTS) + 1):
            for position in seed.positions_for("news", index):
                self.assertEqual(
                    {"instrumentId", "quantity", "unitPrice", "valuationCurrency", "fxToSgd"}, set(position))
        first = seed.positions_for("news", 0)
        first[0]["quantity"] = 1
        self.assertEqual(seed.positions_for("news", 0)[0]["quantity"], 60, "a change must not leak into the profile")


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


class CreateClientsTest(unittest.TestCase):
    EMAILS = ["a@x.com", "b@x.com", "c@x.com"]  # replaces the addresses of the first three clients only

    def fake(self, existing, accounts_for=()):
        """A tiny stand-in for the CRM: records the calls and answers like it."""
        calls = []

        def request(base, token, method, path, body=None, headers=None):
            calls.append((method, path))
            if method == "GET" and path.startswith("/api/clients?"):
                return 200, {"content": existing, "totalPages": 1}
            if method == "GET" and path.startswith("/api/accounts?"):
                held = any(c in path for c in accounts_for)
                return 200, [{"id": "acc", "account_type": "INVESTMENT", "status": "ACTIVE"}] if held else []
            if method == "POST" and path == "/api/clients":
                return 201, {"client_id": "id-" + body["first_name"].replace(" ", "_")}
            return 201 if path == "/api/accounts" else 200, {}
        return request, calls

    def test_a_fresh_agent_gets_every_demo_client_verified_with_an_active_account(self):
        request, calls = self.fake([])
        with mock.patch.object(seed, "request", side_effect=request):
            seed.create_clients("http://x", "t", self.EMAILS, False)
        clients = len(seed.DEMO_CLIENTS)
        self.assertEqual(sum(c == ("POST", "/api/clients") for c in calls), clients)
        self.assertEqual(sum(c == ("POST", "/api/accounts") for c in calls), clients)
        self.assertEqual(sum(c[1].endswith("/verify") for c in calls), clients)

    def test_the_given_addresses_go_to_the_first_clients_and_the_rest_get_made_up_ones(self):
        sent = []
        base, _ = self.fake([])

        def request(basepath, token, method, path, body=None, headers=None):
            if method == "POST" and path == "/api/clients":
                sent.append(body["email"])
            return base(basepath, token, method, path, body, headers)
        with mock.patch.object(seed, "request", side_effect=request):
            seed.create_clients("http://x", "t", self.EMAILS, False)
        self.assertEqual(sent[:3], self.EMAILS)
        self.assertEqual(sent[3], "aaron.teo@example.test")
        self.assertEqual(len(set(sent)), len(seed.DEMO_CLIENTS), "every address is different")
        self.assertIn("xinyi.tan@example.test", sent)

    def test_a_rerun_creates_nothing_when_everything_is_there(self):
        there = [{"client_id": "id-" + f.replace(" ", "_"), "first_name": f, "last_name": l, "verification_status": "VERIFIED"}
                 for f, l in seed.DEMO_CLIENTS]
        request, calls = self.fake(there, accounts_for=["id-" + first.replace(" ", "_") for first, _ in seed.DEMO_CLIENTS])
        with mock.patch.object(seed, "request", side_effect=request):
            seed.create_clients("http://x", "t", self.EMAILS, False)
        self.assertFalse([c for c in calls if c[0] == "POST"])

    def test_a_dry_run_changes_nothing_and_there_cannot_be_more_addresses_than_clients(self):
        request, calls = self.fake([])
        with mock.patch.object(seed, "request", side_effect=request):
            seed.create_clients("http://x", "t", self.EMAILS, True)
            with self.assertRaises(SystemExit):
                seed.create_clients("http://x", "t", ["a@x.com"] * (len(seed.DEMO_CLIENTS) + 1), False)
        self.assertFalse([c for c in calls if c[0] == "POST"])

    def test_every_generated_client_gets_a_random_singapore_mobile_and_a_valid_shape(self):
        bodies = [seed.client_body(f, l, "e@x.com", i + 1) for i, (f, l) in enumerate(seed.DEMO_CLIENTS)]
        self.assertGreater(len({b["phone_number"] for b in bodies}), 1, "the numbers are not all the same")
        for b in bodies:
            self.assertRegex(b["phone_number"], r"^\+659[0-9]{7}$")
            self.assertRegex(b["postal_code"], r"^[0-9]{6}$")

    def fake_with_taken_phones(self, taken):
        """The CRM answering 409 to the first `taken` client creations, as when the phone number is already used."""
        base, calls = self.fake([])
        phones = []

        def request(basepath, token, method, path, body=None, headers=None):
            if method == "POST" and path == "/api/clients":
                phones.append(body["phone_number"])
                if len(phones) <= taken:
                    calls.append((method, path))
                    return 409, {}
            return base(basepath, token, method, path, body, headers)
        return request, calls, phones

    def test_a_taken_phone_number_is_retried_with_a_new_one(self):
        request, calls, phones = self.fake_with_taken_phones(2)
        with mock.patch.object(seed, "request", side_effect=request), \
                mock.patch.object(seed, "random_phone", side_effect=[f"+659000000{n}" for n in range(9)] + ["+6591234567"] * 40):
            seed.create_clients("http://x", "t", self.EMAILS, False)
        self.assertEqual(phones[:3], ["+6590000000", "+6590000001", "+6590000002"], "a new number on each try")
        self.assertEqual(sum(c == ("POST", "/api/accounts") for c in calls), len(seed.DEMO_CLIENTS))

    def test_a_409_on_every_try_stops_and_names_the_email(self):
        request, calls, phones = self.fake_with_taken_phones(seed.PHONE_ATTEMPTS)
        with mock.patch.object(seed, "request", side_effect=request):
            with self.assertRaises(SystemExit) as stop:
                seed.create_clients("http://x", "t", self.EMAILS, False)
        self.assertEqual(len(phones), seed.PHONE_ATTEMPTS)
        self.assertIn("a@x.com", str(stop.exception.code))
        self.assertFalse([c for c in calls if c == ("POST", "/api/accounts")])


if __name__ == "__main__":
    unittest.main()
