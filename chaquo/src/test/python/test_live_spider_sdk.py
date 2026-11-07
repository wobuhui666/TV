"""Production Python adapters and unmodified source fixtures, without external I/O."""
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import types
import unittest
from importlib.metadata import version
from urllib.parse import parse_qs, urlsplit
from lxml import etree as real_etree, html as real_html

import test_spider_api as support
from test_source_uri_resolution import CDN_ROOT


class Reader:
    def __init__(self, data=b'fixture', code=200):
        self.data = data
        self.code = code
        self.closed = False
        self.requests = []
        self.chunk_limit = 1048576

    def metadata(self):
        return json.dumps({'code': self.code, 'headers': {'X-Fixture': 'yes'}, 'contentType': 'video/x-flv'})

    def readChunk(self, size):
        self.requests.append(size)
        if not self.data:
            return None
        size = min(size, self.chunk_limit)
        result, self.data = self.data[:size], self.data[size:]
        return [n if n < 128 else n - 256 for n in result]

    def close(self):
        self.closed = True


class Body:
    def __init__(self, source):
        self.source = source
        self.closed = False

    def read(self, size=65536):
        while True:
            value = self.source.pull(size)
            if value is None:
                self.close()
                return None
            if value:
                return value

    def close(self):
        if not self.closed:
            self.closed = True
            self.source.close()


class Streams:
    def __init__(self):
        self.opened = []
        self.bodies = []
        self.payload = b'fixture'
        self.chunk_limit = 1048576

    def open(self, native, url, options):
        reader = Reader(self.payload)
        reader.chunk_limit = self.chunk_limit
        self.opened.append((url, json.loads(options), reader))
        return reader

    def body(self, source):
        body = Body(source)
        self.bodies.append(body)
        return body


class Native(support.Native):
    def __init__(self):
        super().__init__()
        self.routes = {}
        self.callback = None
        self.socket = types.SimpleNamespace(sent=[], canceled=False)
        self.socket.send = lambda data: self.socket.sent.append(data) or True
        self.socket.cancel = lambda: setattr(self.socket, 'canceled', True)
        self.socket.close = self.socket.cancel

    def reqParts(self, url, options):
        options = json.loads(options)
        self.calls.append(('reqParts', url, options))
        route = url if url in self.routes else url.split('?')[0]
        if route not in self.routes:
            raise AssertionError('Unexpected network request: ' + url)
        value = self.routes[route]
        value = value(options) if callable(value) else value
        content = value if isinstance(value, bytes) else json.dumps(value, ensure_ascii=False).encode()
        metadata = json.dumps({'code': 200, 'headers': {}})
        return metadata, list(content) if options.get('buffer') == 3 else content.decode()

    def json(self, url, options):
        return self.reqParts(url, options)[1]

    def map(self, items, mapper, limit):
        with ThreadPoolExecutor(max_workers=limit) as executor:
            futures = [executor.submit(mapper.apply, value, index) for index, value in enumerate(items)]
            return [future.result() for future in futures]

    def connect(self, url, options, callback):
        self.calls.append(('connect', url, json.loads(options)))
        self.callback = callback
        return self.socket

    def event(self, value, content=None):
        self.callback.accept(self.socket, types.SimpleNamespace(parts=lambda: (json.dumps(value), content)))


class SdkFixture(unittest.TestCase):
    def setUp(self):
        self.host = support.SpiderApiTest()
        self.host.setUp()
        self.addCleanup(self.host.doCleanups)
        self.net_module = self.host.net_module
        self.app = self.host.app
        self.base = self.host.spider_module.Spider
        self.directory = tempfile.TemporaryDirectory(prefix='python-spider-sdk-')
        self.addCleanup(self.directory.cleanup)

    def create(self, source, site='fixture', native=None):
        value = self.app.spider(self.directory.name, str(source), site, native)
        self.addCleanup(lambda: self.app.release(value))
        self.app.bind(value, native or self.host.native, self.host.native_local, streams=Streams())
        self.app.init(value, '')
        return value


class LiveSpiderSdkTest(SdkFixture):
    def test_modern_legacy_inherited_and_default_proxy_dispatch(self):
        base = self.base
        class Modern(base):
            def proxy(self, params): return [201, 'text/plain', params['text']]
        class Legacy(base):
            def localProxy(self, params): return [202, 'text/plain', params['text']]
        class Both(Legacy):
            def proxy(self, params): return [203, 'text/plain', params['text']]
        class Child(Modern): pass
        for cls, code in ((Modern, 201), (Legacy, 202), (Both, 203), (Child, 201), (base, 404)):
            with self.subTest(cls=cls.__name__):
                result = self.app.localProxy(cls(), '{"text":"中文"}')
                self.assertEqual(result[0], code)
        class Broken(base):
            def proxy(self, params): raise ValueError('source failure')
            def localProxy(self, params): return [200, 'text/plain', 'incorrect fallback']
        with self.assertRaisesRegex(ValueError, 'source failure'):
            self.app.proxy(Broken(), '{}')

    def test_proxy_alias_preserves_site_encoding_lan_and_old_boolean_overload(self):
        spider = self.base()
        spider.siteKey = 'site & 中文'
        params = {'url': 'https://example.invalid/a?b=中&c=', 'empty': ''}
        value = spider.proxyUrl(params, False)
        self.assertEqual(urlsplit(value).hostname, '192.0.2.1')
        self.assertEqual(parse_qs(urlsplit(value).query, keep_blank_values=True), {'do':['py'], 'siteKey':['site & 中文'], 'url':[params['url']], 'empty':['']})
        self.assertNotIn('siteKey', spider.getProxyUrl(False))
        for params in ({'do':'x'}, {'siteKey':'x'}, {'integer':1}):
            with self.assertRaises((ValueError, TypeError)):
                spider.proxyUrl(params)

    def test_defaults_json_string_and_remote_page_total(self):
        spider = self.base()
        self.assertEqual(json.loads(self.app.homeContent(spider, True)), {'class':[]})
        self.assertFalse(self.app.manualVideoCheck(spider))
        self.assertFalse(self.app.isVideoFormat(spider, 'https://example.invalid'))
        source = types.SimpleNamespace(homeContent=lambda _: '{"class":[]}', action=lambda _: 'ordinary text')
        self.assertEqual(self.app.homeContent(source, True), '{"class":[]}')
        self.assertEqual(json.loads(self.app.action(source, '')), 'ordinary text')
        self.assertEqual(spider.page([3,4], 2, 2, total=9), {'list':[3,4], 'page':2, 'limit':2, 'pagecount':5, 'total':9})
        self.assertEqual(spider.page([], 1, 2)['pagecount'], 0)
        with self.assertRaises(ValueError): spider.page([], 0, 2)
        async def asynchronous(): return {}
        with self.assertRaisesRegex(TypeError, 'synchronous'):
            self.app.encode_json(asynchronous())

    def test_generator_stream_is_lazy_fragmented_and_closed_once(self):
        values, closed = [], []
        def chunks():
            try:
                values.append('started')
                yield b''
                yield bytearray(b'abc')
                yield memoryview(b'def')
            finally:
                closed.append(True)
        streams = Streams()
        net = self.net_module.Net(self.host.native, streams)
        result = net.stream(chunks())
        self.assertEqual(values, [])
        body = result['stream']
        self.assertEqual(body.read(2), b'ab')
        self.assertEqual(body.read(2), b'c')
        self.assertEqual(body.read(2), b'de')
        body.close(); body.close()
        self.assertEqual(closed, [True])
        self.assertIsNone(body.read(2))
        bad = net.stream(iter(['invalid']))['stream']
        with self.assertRaises(TypeError): bad.read()
        bad.close()

    def test_stream_metadata_read_sizes_eof_and_native_body_identity(self):
        streams = Streams(); streams.payload = b'\xfffixture'
        net = self.net_module.Net(self.host.native, streams)
        reader = net.openStream('https://example.invalid/live', {'callTimeout':0})
        self.assertEqual(reader.code, 200)
        self.assertEqual(reader.read(1), b'\xff')
        self.assertEqual(streams.opened[0][2].requests, [1])
        self.assertEqual(reader.read(100), b'fixture')
        self.assertIsNone(reader.read(1))
        reader.close(); self.assertTrue(streams.opened[0][2].closed)
        for size in (0, True, 1048577):
            with self.assertRaises(ValueError): reader.read(size)
        response = net.stream('https://example.invalid/second')
        self.assertIs(response['stream'], streams.opened[1][2])
        self.assertEqual(response['contentType'], 'video/x-flv')

    def test_map_preserves_arbitrary_python_objects_and_mapper_errors(self):
        net = self.net_module.Net(Native())
        marker = object()
        results = net.map([3,1,2], lambda item,index: (item,index,marker), 2)
        self.assertEqual([(row[0],row[1]) for row in results], [(3,0),(1,1),(2,2)])
        self.assertTrue(all(row[2] is marker for row in results))
        self.assertEqual(net.map([], lambda *_: self.fail('empty mapper')), [])
        with self.assertRaisesRegex(ValueError, 'mapper failure'):
            net.map([1], lambda *_: (_ for _ in ()).throw(ValueError('mapper failure')))
        with self.assertRaises(ValueError): net.map([1], lambda *_: None, True)

    def test_socket_binary_callback_and_send_use_java_byte_array(self):
        native = Native(); net = self.net_module.Net(native); events = []
        socket = net.connect('wss://example.invalid/', {'reconnect':True}, lambda socket,event: events.append(event))
        native.event({'type':'message','binary':True}, [-128,-1,0,127])
        native.event({'type':'message','binary':False}, '中文')
        self.assertEqual(events[0]['content'], b'\x80\xff\x00\x7f')
        self.assertEqual(events[1]['content'], '中文')
        self.assertTrue(socket.send(b'\xff\x00'))
        self.assertEqual(native.socket.sent, [[-1,0]])
        socket.cancel(); self.assertTrue(native.socket.canceled)

    def test_cache_errors_default_local_and_js_json_results(self):
        net = self.net_module.Net(self.host.native)
        self.assertEqual(json.loads(net.cachedText('key', {'ttl':1}, lambda: {'n':1})), {'n':1})
        error = ValueError('original Python error')
        with self.assertRaises(ValueError) as raised:
            net.cached('bad', {'ttl':1}, lambda: (_ for _ in ()).throw(error))
        self.assertIs(raised.exception, error)
        self.assertEqual(self.host.local.get('missing', {'default':1}), {'default':1})
        calls = []
        js = self.net_module.Js(types.SimpleNamespace(eval=lambda source,opts: calls.append(json.loads(opts)) or '"answer"'))
        self.assertEqual(js.eval('rid', {'globals':{'rid':'12'}}), 'answer')
        self.assertEqual(calls, [{'globals':{'rid':'12'}}])

    def test_danmaku_stop_is_idempotent_and_late_events_are_ignored(self):
        emitters, stopped, events = [], [], []
        def subscribe(id, emit):
            emitters.append(emit)
            return lambda: stopped.append(id)
        source = types.SimpleNamespace(danmaku=subscribe, destroy=lambda: None)
        stop = self.app.danmaku(source, 'room', types.SimpleNamespace(emit=lambda value: events.append(json.loads(value))))
        emitters[0]({'text':'before'})
        stop(); stop(); emitters[0]({'text':'late'})
        self.app.destroy(source)
        self.assertEqual(stopped, ['room'])
        self.assertEqual(events, [{'text':'before'}])

    def test_destroy_keeps_cleaning_resources_after_dialog_or_source_failure(self):
        calls = []
        original = ValueError('dialog failure')
        def dialog_close():
            calls.append('dialog')
            raise original
        def destroy():
            calls.append('source')
            raise RuntimeError('source failure')
        source = types.SimpleNamespace(_tv_dialog=types.SimpleNamespace(close=dialog_close),
                                       destroy=destroy, runtime=types.SimpleNamespace(close=lambda: calls.append('runtime')))
        with self.assertRaises(ValueError) as raised:
            self.app.destroy(source)
        self.assertIs(raised.exception, original)
        self.assertEqual(calls, ['dialog','source','runtime'])

    def test_same_basename_helpers_and_generation_are_isolated(self):
        roots = []
        for number in (1,2):
            directory = Path(self.directory.name) / str(number); directory.mkdir()
            (directory/'main.py').write_text('from base.spider import Spider as Base\nclass Spider(Base):\n def getDependence(self): return ["helper"]\n def init(self,extend=""): self.helper=self.loadModule("helper")\n')
            (directory/'helper.py').write_text('VALUE=' + str(number))
            roots.append(directory/'main.py')
        first = self.create(roots[0], 'first'); second = self.create(roots[1], 'second')
        self.assertNotEqual(first.__class__.__module__, second.__class__.__module__)
        self.assertEqual((first.helper.VALUE, second.helper.VALUE), (1,2))
        first_namespace = first.runtime.namespace
        self.app.destroy(first)
        self.assertFalse(any(name.startswith(first_namespace) for name in sys.modules))
        self.assertEqual(second.loadModule('helper').VALUE, 2)
        refreshed = self.create(roots[1], 'second')
        self.assertNotEqual(refreshed.__class__.__module__, second.__class__.__module__)

    def test_load_spider_shares_host_resources_and_relative_dependency_url(self):
        native = Native()
        main = 'https://example.invalid/version/main.py?version=1'
        native.routes[main] = b'from base.spider import Spider as Base\nclass Spider(Base):\n def getDependence(self): return ["child"]\n def init(self,extend=""): self.child=self.loadSpider("child")\n'
        native.routes['https://example.invalid/version/child.py'] = b'from base.spider import Spider as Base\nclass Spider(Base): pass\n'
        spider = self.create(main, 'site', native)
        for attribute in ('net','local','js','_tv_dialog','siteKey'):
            self.assertIs(getattr(spider.child,attribute), getattr(spider,attribute))
        self.assertEqual(spider.runtime.site_key, 'site')


class OriginalLiveSourceTest(SdkFixture):
    # Keep fixture integration separate; require the sources in both local and CI runs.
    def setUp(self):
        super().setUp()
        value = os.environ.get('TV_LIVE_PYTHON_FIXTURES')
        if not value:
            self.fail('TV_LIVE_PYTHON_FIXTURES must point to the pinned original source package')
        self.fixtures = Path(value)
        for relative in ('live/cctv.py','live/gdtv.py','live/yangshipin.py','live/cctv.json','live/yangshipin.json','vod/game_live.py','vod/game_live_protocol.py','vod/game_live_stream.py','vod/game_live_yy.py'):
            self.assertTrue((self.fixtures/relative).is_file(), relative)
        sys.modules['lxml'].etree = real_etree
        sys.modules['lxml'].html = real_html

    def test_original_cctv_and_yangshipin_snapshot_catalogs(self):
        # The raw code uses its own checked snapshot readers; only transport is stubbed.
        for name, minimum in (('cctv',18),('yangshipin',50)):
            with self.subTest(source=name):
                native = Native()
                snapshot = self.fixtures/'live'/ (name+'.json')
                snapshot_url = 'https://fixture.invalid/' + name + '.json'
                native.routes[snapshot_url] = snapshot.read_bytes()
                catalog_url = 'https://tv.cctv.com/live/' if name == 'cctv' else 'https://capi.yangshipin.cn/api/oms/pc/navigation/home_top_nav'
                native.routes[catalog_url] = lambda _: (_ for _ in ()).throw(ValueError('fixture: catalog offline'))
                spider = self.app.spider(self.directory.name, str(self.fixtures/'live'/(name+'.py')), name)
                self.addCleanup(lambda spider=spider: self.app.release(spider))
                self.app.bind(spider,native,self.host.native_local,streams=Streams())
                self.app.init(spider,snapshot_url)
                groups = json.loads(self.app.liveContent(spider,''))
                channels = [channel for group in groups for channel in group['channel']]
                self.assertGreaterEqual(len(channels),minimum)
                self.assertTrue(all('siteKey='+name in channel['urls'][0] for channel in channels))
                if name == 'cctv':
                    spider._resolve = lambda _: 'https://example.invalid/live.m3u8'
                    self.assertEqual(self.app.proxy(spider,'{"id":"cctv1"}')[0],302)

    def test_original_guangdong_categories_and_proxy(self):
        native = Native(); url='https://fixture.invalid/gdtv'
        native.routes[url] = {'categoryTvChannelResps':[{'categoryPk':key,'categoryName':'group'+key,'tvChannelEntityList':[{'tvType':1,'tvChannelPk':str(index+1),'channelName':'channel'+key}]} for index,key in enumerate(('3041','3043','3044'))]}
        spider = self.create(self.fixtures/'live/gdtv.py','gdtv',native)
        groups=json.loads(self.app.liveContent(spider,url))
        self.assertEqual(len(groups),4)
        self.assertEqual(len(groups[0]['channel']),3)
        spider._play_url=lambda _: 'https://example.invalid/live.m3u8'
        self.assertEqual(self.app.proxy(spider,'{"id":"1"}')[3]['Location'],'https://example.invalid/live.m3u8')

    def test_original_game_home_huya_player_stream_and_danmaku(self):
        native=Native(); spider=self.create(self.fixtures/'vod/game_live.py','game',native)
        spider._groups=lambda platform: [{'id':'group','name':platform}]
        home=json.loads(self.app.homeContent(spider,True))
        self.assertEqual(set(home['filters']),{'douyu','huya','yy'})
        spider._huya_lines=lambda _: [{'sCdnType':'fixture'}]
        result=json.loads(self.app.playerContent(spider,'虎牙','huya:1','[]'))
        self.assertEqual(result['format'],'video/x-flv')
        self.assertEqual(result['danmaku'][0]['url'],'spider://game/huya:1')
        self.assertIn('siteKey=game',result['url']['values'][0]['v'])
        spider._huya_stream_url=lambda *_: 'https://example.invalid/live.flv'
        response=self.app.proxy(spider,'{"live":"huya:1","cdn":"fixture"}')
        self.assertEqual(response[:2],[200,'video/x-flv'])
        self.assertIsInstance(response[2],Body)
        self.assertEqual(spider.net._streams.opened,[])
        response[2].close()
        events=[]
        stop=self.app.danmaku(spider,'douyu:1',types.SimpleNamespace(emit=lambda value: events.append(json.loads(value))))
        native.event({'type':'open'})
        packet=spider.protocol.douyu_packet('type@=chatmsg/txt@=hello@Sworld@A/col@=1/')
        native.event({'type':'message','binary':True},list(packet[:7]))
        native.event({'type':'message','binary':True},list(packet[7:]))
        stop(); stop()
        self.assertEqual(events[-1]['text'],'hello/world@')
        self.assertTrue(native.socket.canceled)
        self.assertEqual(len(native.socket.sent),3)

    def test_original_game_dependencies_load_through_composite_cdn_addresses(self):
        native = Native()
        names = ('game_live.py','game_live_protocol.py','game_live_stream.py','game_live_yy.py')
        for name in names:
            native.routes[CDN_ROOT + 'vod/' + name] = (self.fixtures/'vod'/name).read_bytes()
        spider = self.create(CDN_ROOT + 'vod/game_live.py', 'cdn-game', native)
        downloaded = [call[1] for call in native.calls if call[0] == 'reqParts']
        self.assertCountEqual(downloaded, [CDN_ROOT + 'vod/' + name for name in names])
        self.assertEqual(len(json.loads(self.app.homeContent(spider,False))['class']), 3)
        self.assertTrue(callable(spider.protocol.douyu_packet))

    def test_original_douyu_signing_crosses_js_globals_bridge(self):
        native=Native(); spider=self.create(self.fixtures/'vod/game_live.py','game',native)
        native.routes['https://www.douyu.com/swf_api/homeH5Enc']={'error':0,'data':{'room1':'function ub98484234(rid,did,tt){return "sign=" + CryptoJS.MD5(rid+did+tt);}'}}
        captured=[]
        # The real QuickJS/MD5 engine is separately exercised by Java tests.
        def evaluate(source,settings):
            settings=json.loads(settings); self.assertIn('CryptoJS = {MD5: md5X}',source)
            globals=settings['globals']; captured.append(globals)
            return json.dumps('sign='+hashlib.md5((globals['rid']+globals['did']+globals['tt']).encode()).hexdigest())
        spider.js=self.net_module.Js(types.SimpleNamespace(eval=evaluate))
        def play(options):
            self.assertEqual(options['data']['sign'],hashlib.md5(('1'+spider.device_id+captured[0]['tt']).encode()).hexdigest())
            return {'error':0,'data':{'rtmp_url':'https://example.invalid','rtmp_live':'live.flv'}}
        native.routes['https://www.douyu.com/lapi/live/getH5Play/1']=play
        self.assertEqual(spider._douyu_url('1'),'https://example.invalid/live.flv')

    def test_original_game_flv_fragmentation_closes_upstream_on_downstream_close(self):
        spider=self.create(self.fixtures/'vod/game_live.py','game',Native())
        spider._douyu_url=lambda _: 'https://example.invalid/live.flv'
        def tag(stamp):
            value=bytearray(11); value[0]=9; value[1:4]=(3).to_bytes(3,'big')
            value[4:8]=bytes((stamp>>16&255,stamp>>8&255,stamp&255,stamp>>24))
            return bytes(value)+b'\x17\x01\x00'+(14).to_bytes(4,'big')
        streams=spider.net._streams
        streams.payload=b'FLV\x01\x01\x00\x00\x00\x09'+bytes(4)+tag(1000)+tag(1040)
        streams.chunk_limit=5
        response=self.app.proxy(spider,'{"live":"douyu:1"}')
        body=response[2]
        self.assertEqual(body.read(13),streams.payload[:13])
        packets=body.read(100)
        stamps=[int.from_bytes(packets[offset+4:offset+7],'big')+packets[offset+7]*(1<<24) for offset in (0,18)]
        self.assertEqual(stamps,[0,40])
        body.close()
        self.assertTrue(streams.opened[0][2].closed)

    def test_original_yangshipin_keygen_pinned_pywasm_and_numpy(self):
        self.assertEqual(version('pywasm'),'1.0.8')
        self.assertEqual(version('numpy'),'1.26.2')
        spider=self.create(self.fixtures/'live/yangshipin.py','yangshipin',Native())
        module=sys.modules[spider.__class__.__module__]
        # Minimal WASM exports the precise old ABI; its two routines return fixed text.
        payload=bytes.fromhex(
            '0061736d0100000001160460017f017f60027f7f017f60037f7f7f0060017f00'
            '03060500010203030503010001077206066d656d6f727902001f5f5f7762696e'
            '6467656e5f6164645f746f5f737461636b5f706f696e7465720000115f5f7762'
            '696e6467656e5f6d616c6c6f6300010f5f5f7762696e6467656e5f6672656500'
            '020d6765745f746f6b656e5f726e6400030d6765745f7369676e61747572650004'
            '0a3405050041c0000b05004180010b02000b110020004180023602002000410336'
            '02040b11002000418402360200200041033602040b0b0e01004180020b07726e64'
            '00736967')
        path=Path(self.directory.name)/'keygen.wasm'; path.write_bytes(payload)
        keygen=module._Keygen(str(path))
        self.assertEqual(keygen.token_random({}),'rnd')
        self.assertEqual(keygen.signature({}),'sig')


if __name__ == '__main__':
    unittest.main()
