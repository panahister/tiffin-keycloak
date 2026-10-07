import json
import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
REALM = ROOT / "product/tiffin-local/import/tiffin-realm.json"
THEME = ROOT / "themes/tiffin/login/theme.properties"
CSS = ROOT / "themes/tiffin/login/resources/css/tiffin.css"
CONTROLS_CSS = ROOT / "themes/tiffin/login/resources/css/tiffin-controls-20261007-16.css"
CHEVRON_CSS = ROOT / "themes/tiffin/login/resources/css/tiffin-chevron-20261007-18.css"


class TiffinThemeLocalizationTests(unittest.TestCase):
    def test_realm_and_theme_declare_the_supported_locales(self):
        realm = json.loads(REALM.read_text())
        properties = dict(
            line.split("=", 1)
            for line in THEME.read_text().splitlines()
            if line and not line.startswith("#") and "=" in line
        )

        self.assertTrue(realm["internationalizationEnabled"])
        self.assertEqual(set(realm["supportedLocales"]), {"en", "ar"})
        self.assertEqual(realm["defaultLocale"], "en")
        self.assertEqual(set(properties["locales"].split(",")), {"en", "ar"})
        self.assertIn("css/tiffin-controls-20261007-16.css", properties["styles"].split())
        self.assertIn("css/tiffin-chevron-20261007-18.css", properties["styles"].split())

    def test_visible_prose_is_not_hard_coded_in_css(self):
        css = CSS.read_text()
        string_content = re.findall(r'content:\s*"([^"]+)"', css)

        self.assertEqual(string_content, ["T"])
        self.assertIn("#kc-header-wrapper::after", css)
        self.assertIn("#kc-info-wrapper::before", css)
        self.assertGreaterEqual(css.count("content: none !important"), 2)
        self.assertIn("#kc-registration > span", css)
        self.assertNotIn("Fresh meals, delivered with care.", css)
        self.assertNotIn("New to Tiffin?", css)

        controls_css = CONTROLS_CSS.read_text()
        self.assertNotRegex(controls_css, r'content:\s*["\']')
        self.assertIn("content: none !important", controls_css)
        self.assertIn("#reset-login .kc-tooltip-text", controls_css)
        self.assertIn("#login-select-toggle", controls_css)
        self.assertIn("padding-inline: 14px 36px", controls_css)
        self.assertIn("inset-inline-end: 14px", controls_css)
        self.assertIn("align-self: flex-end", controls_css)

        chevron_css = CHEVRON_CSS.read_text()
        self.assertIn("padding: 0 !important", chevron_css)
        self.assertIn("inset-inline-end: 24px !important", chevron_css)
        self.assertIn("z-index: 2", chevron_css)


if __name__ == "__main__":
    unittest.main()
