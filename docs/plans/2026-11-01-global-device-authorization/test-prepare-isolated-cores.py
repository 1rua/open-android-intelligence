#!/usr/bin/env python3
"""Focused checks for the isolated acceptance environment helper."""

import importlib.util
import hashlib
import os
from pathlib import Path
import secrets
import ssl
import unittest
from unittest import mock


PLAN = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("isolated_cores", PLAN / "prepare-isolated-cores.py")
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


class PreparationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tests = PLAN / "runtime" / "test-runs"
        cls.tests.mkdir(mode=0o700, exist_ok=True)

    def test_private_files_do_not_overwrite(self):
        directory = self.tests / secrets.token_hex(8)
        directory.mkdir(mode=0o700)
        destination = directory / "credential"
        helper.private_file(destination, b"first")
        with self.assertRaises(FileExistsError):
            helper.private_file(destination, b"second")
        self.assertEqual(destination.read_bytes(), b"first")
        self.assertEqual(destination.stat().st_mode & 0o777, 0o600)

    def test_isolated_environment_does_not_inherit_or_change_host_paths(self):
        directory = self.tests / secrets.token_hex(8)
        with mock.patch.dict(os.environ, {"HOME": "original-home", "OAI_HISTORY_MEDIA_ROOT": "original-media",
                                        "OPEN_ANDROID_INTELLIGENCE_GATEWAY_TLS_SPKI_SHA256": "unrelated-pin"}):
            with helper.isolated_environment(directory):
                self.assertEqual(os.environ["HOME"], str(directory / "home"))
                self.assertEqual(os.environ["OAI_HISTORY_MEDIA_ROOT"], str(directory / "history-media"))
                self.assertNotIn("OPEN_ANDROID_INTELLIGENCE_GATEWAY_TLS_SPKI_SHA256", os.environ)
            self.assertEqual(os.environ["HOME"], "original-home")
            self.assertEqual(os.environ["OAI_HISTORY_MEDIA_ROOT"], "original-media")
            self.assertEqual(os.environ["OPEN_ANDROID_INTELLIGENCE_GATEWAY_TLS_SPKI_SHA256"], "unrelated-pin")

    def test_certificate_is_signed_and_valid_for_loopback(self):
        directory = self.tests / secrets.token_hex(8)
        directory.mkdir(mode=0o700)
        helper.create_certificates(directory, "unit-test")
        result = helper.run("openssl", "verify", "-x509_strict", "-CAfile", str(directory / "ca-cert.pem"),
                            "-verify_ip", "127.0.0.1", str(directory / "server-cert.pem"))
        self.assertTrue(result.endswith(": OK"))
        self.assertEqual((directory / "server-key.pem").stat().st_mode & 0o777, 0o600)

    def handshake(self, ca: str, hostname: str):
        runtime = PLAN / "runtime"
        server_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        server_context.load_cert_chain(runtime / "core-a/tls/v2/server-cert.pem", runtime / "core-a/tls/v2/server-key.pem")
        client_context = ssl.create_default_context(cafile=runtime / ca)
        server_in, server_out, client_in, client_out = (ssl.MemoryBIO() for _ in range(4))
        server = server_context.wrap_bio(server_in, server_out, server_side=True)
        client = client_context.wrap_bio(client_in, client_out, server_hostname=hostname)
        server_done = client_done = False
        for _ in range(20):
            if not client_done:
                try:
                    client.do_handshake()
                    client_done = True
                except ssl.SSLWantReadError:
                    pass
            if client_out.pending:
                server_in.write(client_out.read())
            if not server_done:
                try:
                    server.do_handshake()
                    server_done = True
                except ssl.SSLWantReadError:
                    pass
            if server_out.pending:
                client_in.write(server_out.read())
            if server_done and client_done:
                return
        self.fail("TLS handshake did not finish")

    def test_tls_trusts_own_ca_and_rejects_other_ca_and_hostname(self):
        self.handshake("core-a/tls/v2/ca-cert.pem", "localhost")
        with self.assertRaises(ssl.SSLCertVerificationError):
            self.handshake("core-b/tls/v2/ca-cert.pem", "localhost")
        with self.assertRaises(ssl.SSLCertVerificationError):
            self.handshake("core-a/tls/v2/ca-cert.pem", "wrong.example")

    def test_reprepare_keeps_existing_keys_and_passwords(self):
        runtime = PLAN / "runtime"
        files = [runtime / "test-credentials.json", runtime / "core-a/secrets/master-key",
                 runtime / "core-b/secrets/master-key", runtime / "core-a/tls/v2/server-key.pem",
                 runtime / "core-b/tls/v2/server-key.pem"]
        digest = lambda: [hashlib.sha256(file.read_bytes()).digest() for file in files]
        before = digest()
        # Test data idempotency independently of currently running acceptance listeners.
        with mock.patch.object(helper, "assert_no_listeners"):
            helper.prepare()
        self.assertEqual(digest(), before)

    def test_fresh_prepare_replays_in_an_isolated_runtime(self):
        fresh = self.tests / secrets.token_hex(8)
        bootstrap = helper.import_bootstrap()
        with mock.patch.object(helper, "RUNTIME", fresh), \
             mock.patch.object(helper, "SNAPSHOT", fresh / "bootstrap-source"), \
             mock.patch.object(helper, "import_bootstrap", return_value=bootstrap), \
             mock.patch.object(helper, "assert_no_listeners"):
            helper.prepare()
            helper.verify()
            self.assertTrue((fresh / "prepared.json").is_file())
            self.assertTrue((fresh / "core-a/tls/v2/ca-cert.pem").is_file())
            self.assertTrue((fresh / "core-b/tls/v2/ca-cert.pem").is_file())

    def test_reverse_refuses_a_physical_device(self):
        with mock.patch.object(helper, "verify"):
            with self.assertRaisesRegex(RuntimeError, "restricted"):
                helper.reverse("physical-device")

    def test_reverse_refuses_conflicting_mapping_before_changes(self):
        calls = []

        def fake_run(*args, **kwargs):
            calls.append(args)
            if args[-1] == "get-state":
                return "device"
            if args[-1] == "ro.kernel.qemu":
                return "1"
            if args[0] == "ss":
                return "State Recv-Q Send-Q Local Address:Port Peer Address:Port"
            if args[-1] == "--list":
                return "emulator-1 tcp:19841 tcp:12345"
            raise AssertionError(args)

        with mock.patch.object(helper, "verify"), mock.patch.object(helper, "run", side_effect=fake_run):
            with self.assertRaisesRegex(RuntimeError, "already maps elsewhere"):
                helper.reverse("emulator-1")
        self.assertFalse(any("--no-rebind" in call for call in calls))

    def test_reverse_refuses_a_listening_host_port(self):
        calls = []

        def fake_run(*args, **kwargs):
            calls.append(args)
            if args[-1] == "get-state":
                return "device"
            if args[-1] == "ro.kernel.qemu":
                return "1"
            if args[0] == "ss":
                return "State Recv-Q Send-Q Local Address:Port Peer Address:Port\nLISTEN 0 128 127.0.0.1:19841 0.0.0.0:*"
            raise AssertionError(args)

        with mock.patch.object(helper, "verify"), mock.patch.object(helper, "run", side_effect=fake_run):
            with self.assertRaisesRegex(RuntimeError, "already listening"):
                helper.reverse("emulator-1")
        self.assertFalse(any("--no-rebind" in call for call in calls))

    def test_reverse_reuses_identical_mappings_without_rebinding(self):
        calls = []

        def fake_run(*args, **kwargs):
            calls.append(args)
            if args[-1] == "get-state":
                return "device"
            if args[-1] == "ro.kernel.qemu":
                return "1"
            if args[0] == "ss":
                return "State Recv-Q Send-Q Local Address:Port Peer Address:Port"
            if args[-1] == "--list":
                return "host-1 tcp:19841 tcp:19841\nhost-1 tcp:19842 tcp:19842"
            raise AssertionError(args)

        with mock.patch.object(helper, "verify"), mock.patch.object(helper, "run", side_effect=fake_run):
            helper.reverse("emulator-1")
        self.assertFalse(any("--no-rebind" in call for call in calls))


if __name__ == "__main__":
    unittest.main()
