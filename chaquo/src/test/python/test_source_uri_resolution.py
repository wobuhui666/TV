"""Dependency URI resolution keeps path empties, including CDN composite URLs."""
import importlib.util
from pathlib import Path
import unittest


PATH = Path(__file__).resolve().parents[2] / 'main' / 'python' / '_tv_loader.py'
SPEC = importlib.util.spec_from_file_location('source_uri_loader_test', PATH)
LOADER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LOADER)

# Exact immutable URLs recorded in the delivery manifest, not floating branch paths.
CDN_ROOT = ('https://gh-proxy.com/https://raw.githubusercontent.com/zhhshss/tv-python-sources/'
            '25ff7031e94b14c94ce7e8894394f04f3c1a0907/packs/live-python-20261107-a09dd575/')


class SourceUriResolutionTest(unittest.TestCase):
    def test_rfc3986_normal_and_abnormal_reference_examples(self):
        base = 'http://a/b/c/d;p?q'
        cases = {
            'g:h': 'g:h', 'g': 'http://a/b/c/g', './g': 'http://a/b/c/g',
            'g/': 'http://a/b/c/g/', '/g': 'http://a/g', '//g': 'http://g',
            '?y': 'http://a/b/c/d;p?y', 'g?y': 'http://a/b/c/g?y',
            '#s': 'http://a/b/c/d;p?q#s', 'g#s': 'http://a/b/c/g#s',
            'g?y#s': 'http://a/b/c/g?y#s', ';x': 'http://a/b/c/;x',
            'g;x': 'http://a/b/c/g;x', 'g;x?y#s': 'http://a/b/c/g;x?y#s',
            '': 'http://a/b/c/d;p?q', '.': 'http://a/b/c/', './': 'http://a/b/c/',
            '..': 'http://a/b/', '../': 'http://a/b/', '../g': 'http://a/b/g',
            '../..': 'http://a/', '../../': 'http://a/', '../../g': 'http://a/g',
            '../../../g': 'http://a/g', '../../../../g': 'http://a/g',
            '/./g': 'http://a/g', '/../g': 'http://a/g',
            'g.': 'http://a/b/c/g.', '.g': 'http://a/b/c/.g',
            'g..': 'http://a/b/c/g..', '..g': 'http://a/b/c/..g',
            './../g': 'http://a/b/g', './g/.': 'http://a/b/c/g/',
            'g/./h': 'http://a/b/c/g/h', 'g/../h': 'http://a/b/c/h',
            'g;x=1/./y': 'http://a/b/c/g;x=1/y',
            'g;x=1/../y': 'http://a/b/c/y',
            'g?y/./x': 'http://a/b/c/g?y/./x',
            'g?y/../x': 'http://a/b/c/g?y/../x',
            'g#s/./x': 'http://a/b/c/g#s/./x',
            'g#s/../x': 'http://a/b/c/g#s/../x', 'http:g': 'http:g',
        }
        for reference, expected in cases.items():
            with self.subTest(reference=reference):
                self.assertEqual(LOADER.resolve_uri(base, reference), expected)

    def test_composite_cdn_helper_urls_match_verified_delivery_addresses(self):
        runtime = LOADER.Runtime.__new__(LOADER.Runtime)
        runtime.source = CDN_ROOT + 'vod/game_live.py'
        for name in ('game_live_protocol.py', 'game_live_stream.py', 'game_live_yy.py'):
            with self.subTest(helper=name):
                self.assertEqual(runtime._resolve(name), CDN_ROOT + 'vod/' + name)
        self.assertEqual(runtime._resolve('../live/cctv.json'), CDN_ROOT + 'live/cctv.json')
        self.assertEqual(runtime._resolve('../live/./yangshipin.json'), CDN_ROOT + 'live/yangshipin.json')

    def test_empty_path_segments_survive_without_a_cdn_specific_rule(self):
        base = 'https://example.test/a//b///main.py'
        cases = {
            'helper.py': 'https://example.test/a//b///helper.py',
            '../helper.py': 'https://example.test/a//b//helper.py',
            'dir//helper.py': 'https://example.test/a//b///dir//helper.py',
            'dir//../helper.py': 'https://example.test/a//b///dir/helper.py',
            '/a//./b///helper.py': 'https://example.test/a//b///helper.py',
            '/a/%2e/%2e%2e//helper.py': 'https://example.test/a/%2e/%2e%2e//helper.py',
        }
        for reference, expected in cases.items():
            with self.subTest(reference=reference):
                self.assertEqual(LOADER.resolve_uri(base, reference), expected)

    def test_query_and_fragment_inheritance_and_empty_delimiters(self):
        source = CDN_ROOT + 'vod/game_live.py?token=one%2Ftwo#original'
        path = CDN_ROOT + 'vod/game_live.py'
        cases = {
            '': path + '?token=one%2Ftwo', '#next': path + '?token=one%2Ftwo#next',
            '?next=x#part': path + '?next=x#part', '?': path + '?',
            '#': path + '?token=one%2Ftwo#', '?#': path + '?#',
            'helper.py?query=/../x#fragment//': CDN_ROOT + 'vod/helper.py?query=/../x#fragment//',
        }
        for reference, expected in cases.items():
            with self.subTest(reference=reference):
                self.assertEqual(LOADER.resolve_uri(source, reference), expected)

    def test_absolute_and_authority_references_keep_authentication_and_slashes(self):
        base = 'https://user:p%40ss@example.test:8443/a//main.py?old=1'
        self.assertEqual(LOADER.resolve_uri(base, 'helper.py'),
                         'https://user:p%40ss@example.test:8443/a//helper.py')
        self.assertEqual(LOADER.resolve_uri(base, '//other:pw@mirror.test:9443/x//helper.py?q=1#f'),
                         'https://other:pw@mirror.test:9443/x//helper.py?q=1#f')
        absolute = 'http://account:secret@host.test:8080/a//dir/../helper.py?x=1#f'
        self.assertEqual(LOADER.resolve_uri(base, absolute),
                         'http://account:secret@host.test:8080/a//helper.py?x=1#f')
        self.assertEqual(LOADER.resolve_uri('https://example.test', 'helper.py'), 'https://example.test/helper.py')
        self.assertEqual(LOADER.resolve_uri('file:///a//main.py', '../helper.py'), 'file:///a/helper.py')


if __name__ == '__main__':
    unittest.main()
