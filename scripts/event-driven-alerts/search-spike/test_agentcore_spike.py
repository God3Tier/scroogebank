import json
import unittest

import agentcore_spike as spike


class ValidationTests(unittest.TestCase):
    def test_accepts_public_market_query(self):
        self.assertEqual(
            "DBS Group official announcement",
            spike.validate_query("  DBS   Group official announcement  "),
        )

    def test_rejects_private_field_name(self):
        with self.assertRaisesRegex(ValueError, "private-data"):
            spike.validate_query("client id 123 market news")

    def test_rejects_unapproved_domain(self):
        with self.assertRaisesRegex(ValueError, "subset"):
            spike.validate_domains(["example.com"])

    def test_rejects_non_utc_date(self):
        with self.assertRaisesRegex(ValueError, "UTC"):
            spike.validate_utc("2026-09-27T10:00:00+08:00")


class ResponseTests(unittest.TestCase):
    def test_parses_event_stream(self):
        raw = 'event: message\ndata: {"jsonrpc":"2.0","result":{"tools":[]}}\n\n'
        parsed = spike.parse_mcp_response(raw, "text/event-stream")
        self.assertEqual([], parsed["result"]["tools"])

    def test_selects_prefixed_web_search_tool(self):
        name = spike.select_web_search_tool([
            {"name": "approved_public_search___WebSearch"}
        ])
        self.assertEqual("approved_public_search___WebSearch", name)

    def test_sanitizes_results_without_snippet(self):
        inner = {
            "results": [{
                "title": "Official notice",
                "url": "https://sgx.com/example",
                "publishedDate": "2026-09-27",
                "text": "This snippet must not be persisted.",
            }]
        }
        response = {
            "result": {"content": [{"type": "text", "text": json.dumps(inner)}]}
        }
        result = spike.sanitized_results(response)
        self.assertEqual("sgx.com", result[0]["domain"])
        self.assertNotIn("text", result[0])

    def test_allows_subdomain_but_not_suffix_attack(self):
        self.assertTrue(spike.domain_is_allowed("links.sgx.com", ["sgx.com"]))
        self.assertFalse(spike.domain_is_allowed("sgx.com.example.org", ["sgx.com"]))

    def test_wait_until_deleted_accepts_not_found(self):
        class NotFound(Exception):
            response = {"Error": {"Code": "ResourceNotFoundException"}}

        def fetch():
            raise NotFound()

        self.assertIsNone(spike.wait_until_deleted(fetch, timeout=1))


if __name__ == "__main__":
    unittest.main()
