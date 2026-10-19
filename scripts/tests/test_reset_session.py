import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('reset_session', Path(__file__).resolve().parents[1] / 'reset-session.py')
reset_session = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reset_session)


class ResetSessionTest(unittest.TestCase):
    def test_email_normalization_and_sql_injection_boundary(self):
        self.assertEqual(reset_session.render('  USER@Example.com  '), reset_session.render('user@example.com'))
        hostile = "x'@example.com';DELETE-FROM-accounts;--"
        sql = reset_session.render(hostile)
        self.assertNotIn(hostile, sql)
        self.assertIn(hostile.lower().encode('ascii').hex(), sql)

    def test_rejects_multiline_and_non_ascii_input(self):
        for email in ('', 'not-email', 'a@b.com\n\\q', 'я@example.com', 'a' * 255 + '@b.com'):
            with self.subTest(email=email), self.assertRaises(ValueError):
                reset_session.render(email)
