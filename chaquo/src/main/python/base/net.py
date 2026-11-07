"""Native crawler API adapters. Legacy requests-based Spider helpers stay separate."""
import json
import os
from threading import Lock

from java import dynamic_proxy, jclass

_Callable = jclass("java.util.concurrent.Callable")
_File = jclass("java.io.File")
_Init = jclass("com.github.catvod.Init")
_Options = jclass("com.github.catvod.net.NetOptions")
_CacheException = jclass("com.fongmi.chaquo.CacheException")
_JavaThrowable = jclass("java.lang.Throwable")


def _encode(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"))


def _decode(value):
    return None if value is None else json.loads(str(value))


def _response(value, options):
    result = _decode(value)
    if options.get("buffer") == 3 and isinstance(result.get("content"), list):
        result["content"] = bytes(n & 255 for n in result["content"])
    return result


class _Loader(dynamic_proxy(_Callable)):
    def __init__(self, function):
        super().__init__()
        self.function = function

    def call(self):
        try:
            return _encode(self.function())
        except _JavaThrowable:
            raise
        except BaseException as error:
            raise _CacheException(error) from None


class Js:
    def __init__(self, native):
        self._native = native

    def eval(self, source, options=None):
        if not isinstance(source, str) or options is not None and not isinstance(options, dict):
            raise TypeError("Script requires source and an options dict")
        if self._native is None:
            raise RuntimeError("Script bridge is unavailable")
        return _decode(self._native.eval(source, _encode({} if options is None else options)))


class Socket:
    def __init__(self, native):
        self._native = native

    def send(self, data):
        if not isinstance(data, (str, bytes, bytearray)):
            raise TypeError("send requires text or bytes")
        if not isinstance(data, str):
            from java import jarray, jbyte
            data = jarray(jbyte)(data)
        return bool(self._native.send(data))

    def close(self):
        self._native.close()

    def cancel(self):
        self._native.cancel()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


class Stream:
    def __init__(self, native):
        self._native = native
        metadata = _decode(native.metadata())
        self.code, self.headers, self.contentType = metadata['code'], metadata['headers'], metadata['contentType']

    def read(self, size=65536):
        if isinstance(size, bool) or not isinstance(size, int) or not 1 <= size <= 1048576:
            raise ValueError("Chunk size must be 1..1048576 bytes")
        value = self._native.readChunk(size)
        return None if value is None else bytes(n & 255 for n in value)

    def close(self):
        self._native.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


class _StreamBody:
    def __init__(self, source):
        self._iterator = iter(source)
        self._chunk = None
        self._closed = False

    def pull(self, size):
        if not 1 <= size <= 1048576:
            raise ValueError("Chunk size must be 1..1048576 bytes")
        if self._closed:
            return None
        if not self._chunk:
            try:
                value = next(self._iterator)
            except StopIteration:
                return None
            if not isinstance(value, (bytes, bytearray, memoryview)):
                raise TypeError("Stream body must yield bytes")
            self._chunk = memoryview(value).cast('B')
        result = self._chunk[:size].tobytes()
        self._chunk = self._chunk[size:]
        return result

    def close(self):
        if self._closed:
            return
        self._closed = True
        self._chunk = None
        close = getattr(self._iterator, 'close', None)
        if close is not None:
            close()


_listener_type = None
_mapper_type = None
_adapter_lock = Lock()


def _listener_class():
    global _listener_type
    with _adapter_lock:
        if _listener_type is None:
            class Listener(dynamic_proxy(jclass('com.github.catvod.net.NetSocket$Listener'))):
                def __init__(self, callback):
                    super().__init__()
                    self._callback = callback

                def accept(self, socket, event):
                    metadata, content = event.parts()
                    value = _decode(metadata)
                    if value['type'] == 'message':
                        value['content'] = bytes(n & 255 for n in content) if value['binary'] else str(content)
                    self._callback(Socket(socket), value)
            _listener_type = Listener
        return _listener_type


def _mapper_class():
    global _mapper_type
    with _adapter_lock:
        if _mapper_type is None:
            class Mapper(dynamic_proxy(jclass('com.github.catvod.net.Net$Mapper'))):
                def __init__(self, items, mapper, results):
                    super().__init__()
                    self._items, self._mapper, self._results = items, mapper, results

                def apply(self, item, index):
                    try:
                        self._results[index] = self._mapper(self._items[index], index)
                    except _JavaThrowable:
                        raise
                    except BaseException as error:
                        raise _CacheException(error) from None
                    return None
            _mapper_type = Mapper
        return _mapper_type


class Net:
    def __init__(self, native, streams=None, user_agent=None):
        self._native = native
        self._streams = streams
        self._user_agent = user_agent

    def req(self, url, options=None):
        options = {} if options is None else options
        if hasattr(self._native, 'reqParts'):
            return self._parts(self._native.reqParts(url, _encode(options)), options)
        return _response(self._native.req(url, _encode(options)), options)

    def json(self, url, options=None):
        return _decode(self._native.json(url, _encode({} if options is None else options)))

    def get(self, url, headers=None):
        if headers is None:
            return str(self._native.get(url))
        result = self.req(url, {"headers": headers})
        if result.get("error"):
            raise RuntimeError(result["error"])
        return result["content"]

    def post(self, url, value, headers=None):
        options = {"method": "POST", "data": value}
        if headers is not None:
            options["headers"] = headers
        result = self.req(url, options)
        if result.get("error"):
            raise RuntimeError(result["error"])
        return result["content"]

    def ws(self, url, options=None):
        options = {} if options is None else options
        if hasattr(self._native, 'wsParts'):
            return self._parts(self._native.wsParts(url, _encode(options)), options)
        return _response(self._native.ws(url, _encode(options)), options)

    @staticmethod
    def _parts(parts, options):
        metadata, content = parts
        result = _decode(metadata)
        if options.get('buffer') == 3 and content is not None and not isinstance(content, str):
            content = bytes(n & 255 for n in content)
        elif content is not None and not isinstance(content, str):
            content = list(content)
        result['content'] = content
        return result

    def batch(self, requests, limit=4):
        if hasattr(self._native, 'batchParts'):
            results = self._native.batchParts(_encode(requests), int(limit))
            if len(results) != len(requests):
                raise RuntimeError("Batch response length does not match requests")
            return [self._parts(result, request.get('options') or {}) for result, request in zip(results, requests)]
        results = self._native.batch(_encode(requests), int(limit))
        output = []
        for index, result in enumerate(results):
            options = requests[index].get("options") or {}
            settings = getattr(_Options, "from")(_encode(options))
            output.append(_response(result.json(settings), options))
        return output

    def cached(self, key, options, loader):
        return _decode(self.cachedText(key, options, loader))

    def cachedText(self, key, options, loader):
        if not callable(loader):
            raise TypeError("Cache loader must be callable")
        try:
            return str(self._native.cached(key, _encode(options), _Loader(loader)))
        except _CacheException as error:
            original = error.error()
        raise original

    def invalidateCache(self, key, expected):
        return bool(self._native.invalidateCache(key, _encode(expected)))

    def peek(self, key):
        return _decode(self._native.peek(key))

    def refresh(self, key, options, loader):
        self._native.refresh(key, _encode(options), _Loader(loader))

    def clearCache(self, key=None):
        if key is None:
            self._native.clearCache()
        else:
            self._native.clearCache(key)

    def download(self, url, path, options=None):
        path = os.fspath(path)
        if not os.path.isabs(path):
            path = os.path.join(str(_Init.context().getCacheDir().getAbsolutePath()), path)
        self._native.download(url, _encode({} if options is None else options), _File(path))
        return path

    def getUserAgent(self):
        if self._user_agent is not None:
            return str(self._user_agent.getUserAgent())
        return str(jclass("com.github.catvod.utils.Util").CHROME)

    def getCookie(self, url):
        return str(self._native.getCookie(url) or "")

    def setCookie(self, url, value):
        return bool(self._native.setCookie(url, value))

    def sleep(self, milliseconds):
        self._native.sleep(int(milliseconds))

    def session(self):
        return Net(self._native.session(), self._streams, self._user_agent)

    def map(self, items, mapper, limit=4):
        if not isinstance(items, list) or not callable(mapper) or type(limit) is not int or not 1 <= limit <= 2147483647:
            raise ValueError("map requires a list, callable mapper and positive limit")
        items = list(items)
        if not items:
            return []
        from java import jclass
        results = [None] * len(items)
        copies = jclass('java.util.Collections').nCopies(len(items), None)
        try:
            self._native.map(copies, _mapper_class()(items, mapper, results), limit)
        except _CacheException as error:
            original = error.error()
            raise original
        return results

    def connect(self, url, options, callback):
        if not isinstance(url, str) or options is not None and not isinstance(options, dict) or not callable(callback):
            raise TypeError("connect requires a URL, options dict and callable listener")
        return Socket(self._native.connect(url, _encode({} if options is None else options), _listener_class()(callback)))

    def _open_stream(self, url, options):
        if not isinstance(url, str) or options is not None and not isinstance(options, dict):
            raise TypeError("Stream requires a URL and options dict")
        if self._streams is None:
            raise RuntimeError("Stream bridge is unavailable")
        return self._streams.open(self._native, url, _encode({} if options is None else options))

    def openStream(self, url, options=None):
        return Stream(self._open_stream(url, options))

    def stream(self, source, options=None):
        if isinstance(source, str):
            body = self._open_stream(source, options)
            result = _decode(body.metadata())
        else:
            if options is not None and (not isinstance(options, dict) or options):
                raise TypeError("Stream body does not accept request options")
            if isinstance(source, (bytes, bytearray, memoryview, dict)):
                raise TypeError("Stream body requires a byte iterable")
            if self._streams is None:
                raise RuntimeError("Stream bridge is unavailable")
            body = self._streams.body(_StreamBody(source))
            result = {'code': 200, 'headers': {}, 'contentType': 'application/octet-stream'}
        result['stream'] = body
        return result

    def close(self):
        self._native.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


class Local:
    def __init__(self, native):
        self._native = native

    def get(self, key, default=None):
        value = self._native.get(key)
        return default if value is None else _decode(value)

    def set(self, key, value):
        self._native.set(key, _encode(value))

    def delete(self, key):
        self._native.delete(key)

    def clear(self):
        self._native.clear()


def bind(spider, native_net, native_local, streams=None, js=None, user_agent=None):
    # An old source may already use these attribute names for its own objects.
    if getattr(spider, "net", None) is None or isinstance(spider.net, Net):
        spider.net = Net(native_net, streams, user_agent)
    if getattr(spider, "local", None) is None or isinstance(spider.local, Local):
        spider.local = Local(native_local)
    if getattr(spider, 'js', None) is None or isinstance(spider.js, Js):
        spider.js = Js(js)
