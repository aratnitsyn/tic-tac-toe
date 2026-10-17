import importlib.util
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[2] / "scripts/migrate.py"
SPEC = importlib.util.spec_from_file_location("migrations", MODULE)
migrations = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(migrations)


class MigrationRenderTests(unittest.TestCase):
    def test_duplicate_version_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "V1__first.sql").write_text("SELECT 1;")
            (root / "V01__duplicate.sql").write_text("SELECT 2;")
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                migrations.render(root)

    def test_invalid_name_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "unversioned.sql").write_text("SELECT 1;")
            with self.assertRaisesRegex(ValueError, "Invalid"):
                migrations.render(root)

    def test_migration_content_is_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "V1__first.sql"
            path.write_text("SELECT 1;")
            original = migrations.render(root)
            path.write_text("SELECT 2;")
            self.assertNotEqual(original, migrations.render(root))
            self.assertIn("checksum mismatch", original)


if __name__ == "__main__":
    unittest.main()
