"""Behavioral checks for local configuration and migration runner guardrails."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


runner = load("run-service")
migration = load("migrate")


class LocalEnvironmentTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def test_env_values_are_literal_and_can_contain_equals(self):
        file = self.root / "local.env"
        file.write_text("# comment\n\nVALUE=a=b\nCOMMAND=$(no_execution)\n")
        self.assertEqual({"VALUE": "a=b", "COMMAND": "$(no_execution)"}, runner.read_env(file))

    def test_invalid_env_entry_reports_location_without_exposing_value(self):
        file = self.root / "local.env"
        file.write_text("# comment\ninvalid-name=private-value\n")
        with self.assertRaises(ValueError) as failure:
            runner.read_env(file)
        self.assertIn(":2", str(failure.exception))
        self.assertNotIn("private-value", str(failure.exception))

    def test_shell_overrides_service_file_and_infrastructure_credentials(self):
        service = self.root / "services/auth"
        service.mkdir(parents=True)
        (service / "local.env").write_text("PORT=8081\nPGUSER=auth\n")
        infra = self.root / "infra/local"
        infra.mkdir(parents=True)
        (infra / ".env").write_text("AUTH_DB_PASSWORD=generated\nNATS_PASSWORD=nats\nREDIS_PASSWORD=redis\n")
        with patch.object(runner, "ROOT", self.root), patch.object(
            runner, "local_credentials", return_value=({"auth": "auth-key", "game": "game-key", "lobby": "lobby-key", "matchmaking": "matchmaking-key"}, Path("key-file"))
        ), patch.dict(os.environ, {"PORT": "19001", "AUTH_SERVICE_TOKEN": "override"}, clear=True):
            env = runner.environment("auth")
        self.assertEqual("19001", env["PORT"])
        self.assertEqual("override", env["AUTH_SERVICE_TOKEN"])
        self.assertEqual("generated", env["PGPASSWORD"])
        self.assertEqual("auth", env["PGUSER"])

    def test_lobby_uses_its_database_and_service_credential(self):
        service = self.root / "services/lobby"
        service.mkdir(parents=True)
        (service / "local.env").write_text("PORT=8083\nPGUSER=lobby\n")
        infra = self.root / "infra/local"
        infra.mkdir(parents=True)
        (infra / ".env").write_text("LOBBY_DB_PASSWORD=lobby-password\nNATS_PASSWORD=nats\n")
        with patch.object(runner, "ROOT", self.root), patch.object(
            runner, "local_credentials", return_value=({"auth": "a", "game": "g", "lobby": "l", "matchmaking": "m"}, Path("key-file"))
        ), patch.dict(os.environ, {}, clear=True):
            env = runner.environment("lobby")
        self.assertEqual("lobby-password", env["PGPASSWORD"])
        self.assertEqual("l", env["LOBBY_SERVICE_TOKEN"])
        self.assertEqual("g", env["GAME_SERVICE_TOKEN"])

    def test_local_credentials_upgrade_preserves_existing_service_tokens(self):
        import json
        directory = self.root / "infra/local/secrets"
        directory.mkdir(parents=True)
        credentials = directory / "services.json"
        credentials.write_text(json.dumps({"auth": "existing-auth", "game": "existing-game"}))
        (directory / "login-keys.properties").write_text("existing-key")
        with patch.object(runner, "ROOT", self.root):
            first, _ = runner.local_credentials()
            second, _ = runner.local_credentials()
        self.assertEqual(first, second)
        self.assertEqual("existing-auth", first["auth"])
        self.assertEqual("existing-game", first["game"])
        self.assertGreaterEqual(len(first["lobby"]), 32)
        self.assertGreaterEqual(len(first["matchmaking"]), 32)
        self.assertEqual("existing-key", (directory / "login-keys.properties").read_text())

    def test_matchmaking_database_password_and_shell_dsn_override(self):
        service = self.root / "services/matchmaking"
        service.mkdir(parents=True)
        (service / "local.env").write_text("PORT=8085\nPG_DSN=local-dsn\n")
        infra = self.root / "infra/local"
        infra.mkdir(parents=True)
        (infra / ".env").write_text("MATCHMAKING_DB_PASSWORD=queue-password\nNATS_PASSWORD=nats\n")
        with patch.object(runner, "ROOT", self.root), patch.object(
            runner, "local_credentials", return_value=({"auth": "a", "game": "g", "lobby": "l", "matchmaking": "m"}, Path("key"))
        ), patch.dict(os.environ, {"PG_DSN": "custom-dsn"}, clear=True):
            env = runner.environment("matchmaking")
        self.assertEqual("queue-password", env["PGPASSWORD"])
        self.assertEqual("custom-dsn", env["PG_DSN"])
        self.assertEqual("m", env["MATCHMAKING_SERVICE_TOKEN"])

    def test_realtime_loads_own_database_redis_and_internal_credentials(self):
        service = self.root / "services/realtime"
        service.mkdir(parents=True)
        (service / "local.env").write_text("PORT=8086\nPG_DSN=local-dsn\n")
        infra = self.root / "infra/local"
        infra.mkdir(parents=True)
        (infra / ".env").write_text("REALTIME_DB_PASSWORD=socket-password\nNATS_PASSWORD=nats\nREDIS_PASSWORD=redis\n")
        with patch.object(runner, "ROOT", self.root), patch.object(
            runner, "local_credentials", return_value=({"auth": "a", "game": "g", "lobby": "l", "matchmaking": "m"}, Path("key"))
        ), patch.dict(os.environ, {"PORT": "9090"}, clear=True):
            env = runner.environment("realtime")
        self.assertEqual("socket-password", env["PGPASSWORD"])
        self.assertEqual("redis", env["REDIS_PASSWORD"])
        self.assertEqual("nats", env["NATS_PASSWORD"])
        self.assertEqual("a", env["AUTH_SERVICE_TOKEN"])
        self.assertEqual("g", env["GAME_SERVICE_TOKEN"])
        self.assertEqual("m", env["MATCHMAKING_SERVICE_TOKEN"])
        self.assertEqual("9090", env["PORT"])

    def test_missing_service_file_fails_instead_of_silently_using_defaults(self):
        with patch.object(runner, "ROOT", self.root):
            with self.assertRaises(FileNotFoundError):
                runner.environment("lobby")

    def test_service_language_mismatch_does_not_report_false_success(self):
        import subprocess
        result = subprocess.run(
            ["python3", str(ROOT / "scripts/check-command-stores.py"), "--only", "go", "--service", "auth"],
            capture_output=True, text=True,
        )
        self.assertEqual(2, result.returncode)
        self.assertIn("no tests would run", result.stderr)


class MigrationValidationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def test_empty_migration_directory_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "No migrations"):
            migration.render(self.root)

    def test_duplicate_versions_are_rejected_even_with_different_descriptions(self):
        (self.root / "V001__first.sql").write_text("SELECT 1;")
        (self.root / "V1__second.sql").write_text("SELECT 2;")
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            migration.render(self.root)

    def test_invalid_and_nonpositive_versions_are_rejected(self):
        for name in ("V000__zero.sql", "001__missing_prefix.sql"):
            with self.subTest(name=name):
                file = self.root / name
                file.write_text("SELECT 1;")
                with self.assertRaises(ValueError):
                    migration.render(self.root)
                file.unlink()


if __name__ == "__main__":
    unittest.main()
