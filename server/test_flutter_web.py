import secrets
import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient
from app import create_app
from flutter_web import mount_flutter_web


class FlutterWebPolicyTests(unittest.TestCase):
    def setUp(self):
        # Exercise the production mount and real response headers with an isolated database and a minimal static web document.
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        web = root / "web"
        web.mkdir()
        (web / "index.html").write_text("<!doctype html><title>photo policy test</title>")
        app = create_app(root / "data", secrets.token_urlsafe(32), "https://rental.example.test")
        mount_flutter_web(app, web)
        self.client = TestClient(app)
        self.addCleanup(self.client.close)

    def test_flutter_can_read_local_selected_photos_without_allowing_external_connections(self):
        # Blob-backed XFiles need connect-src as well as img-src; keep every previously allowed network destination explicit.
        response = self.client.get("/mobile/")
        self.assertEqual(200, response.status_code)
        policy = {parts[0]: parts[1:] for rule in response.headers["content-security-policy"].split(";") if (parts := rule.split())}
        self.assertEqual({"'self'", "blob:", "https://fonts.gstatic.com"}, set(policy["connect-src"]))
        self.assertIn("blob:", policy["img-src"])
        self.assertEqual(["'self'", "'wasm-unsafe-eval'"], policy["script-src"])
        self.assertEqual(["'none'"], policy["frame-ancestors"])
        self.assertEqual(["'self'"], policy["form-action"])

    def test_flutter_exceptions_do_not_apply_to_accounts_api_or_other_paths(self):
        # File access needed by Flutter must not spread its script/style/connection exceptions to the account UI or API responses.
        for path in ["/account", "/health", "/mobile-other/"]:
            policy = self.client.get(path).headers["content-security-policy"]
            self.assertNotIn("connect-src", policy)
            self.assertNotIn("wasm-unsafe-eval", policy)
            self.assertNotIn("unsafe-inline", policy)
            self.assertIn("frame-ancestors 'none'", policy)
