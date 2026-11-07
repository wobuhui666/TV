import os
import requests
import json
from inspect import isawaitable, iscoroutine
from threading import Lock
from urllib.parse import urlsplit
from urllib.request import url2pathname

from _tv_loader import Runtime, SourceNotFoundError, cleanup_failure, raise_failure


def spider(cache, api, site_key="", net=None):
    return Runtime(cache, api, site_key, lambda url: read_source(url, net, inline=url == api)).create()


def read_source(api, net=None, inline=True):
    parsed = urlsplit(api)
    if parsed.scheme in ('http', 'https'):
        if net is not None:
            from base.net import Net
            result = Net(net).req(api, {'timeout': 10000, 'callTimeout': 10000, 'buffer': 3})
            if result.get('error'):
                raise RuntimeError(result['error'])
            code, content = result['code'], result['content']
        else:
            response = requests.get(api, timeout=10)
            code, content = response.status_code, response.content
        if code == 404:
            raise SourceNotFoundError('HTTP 404')
        if not 200 <= code < 300:
            raise RuntimeError('HTTP ' + str(code))
        return content
    if parsed.scheme == 'file':
        api = url2pathname(parsed.path)
        if parsed.netloc:
            api = '//' + parsed.netloc + api
    if not inline or parsed.scheme == 'file' or os.path.isfile(api):
        try:
            with open(api, 'rb') as stream:
                return stream.read()
        except FileNotFoundError as error:
            raise SourceNotFoundError(error.errno, error.strerror, error.filename) from error
    return api.encode()


def download(path, api):
    writeFile(path, read_source(api))


def writeFile(path, content):
    with open(path, 'wb') as f:
        f.write(content)


def redirect(url):
    return requests.get(url, timeout=10)


def sync_result(value):
    if isawaitable(value):
        if iscoroutine(value):
            value.close()
        raise TypeError('Python spider methods must be synchronous')
    return value


def _invalid_constant(value):
    raise ValueError('Invalid JSON constant: ' + value)


def encode_json(value):
    value = sync_result(value)
    if isinstance(value, str):
        try:
            json.loads(value, parse_constant=_invalid_constant)
            return value
        except (ValueError, TypeError):
            pass
    return json.dumps(value, ensure_ascii=False, allow_nan=False)


def str2json(content):
    return json.loads(content)


def getDependence(ru):
    result = ru.getDependence()
    return result


def getName(ru):
    result = ru.getName()
    return result


def init(ru, extend):
    runtime = getattr(ru, 'runtime', None)
    if runtime is not None:
        runtime.prepare(ru)
    sync_result(ru.init(extend))


def homeContent(ru, filter):
    result = ru.homeContent(filter)
    formatJo = encode_json(result)
    return formatJo


def homeVideoContent(ru):
    result = ru.homeVideoContent()
    formatJo = encode_json(result)
    return formatJo


def categoryContent(ru, tid, pg, filter, extend):
    result = ru.categoryContent(tid, pg, filter, str2json(extend))
    formatJo = encode_json(result)
    return formatJo


def detailContent(ru, array):
    result = ru.detailContent(str2json(array))
    formatJo = encode_json(result)
    return formatJo


def searchContent(ru, key, quick, pg="1"):
    result = ru.searchContent(key, quick, pg)
    formatJo = encode_json(result)
    return formatJo


def playerContent(ru, flag, id, vipFlags):
    result = ru.playerContent(flag, id, str2json(vipFlags))
    formatJo = encode_json(result)
    return formatJo


def liveContent(ru, url):
    result = ru.liveContent(url)
    result = sync_result(result)
    return result if isinstance(result, str) else encode_json(result)


def isVideoFormat(ru, url):
    result = sync_result(ru.isVideoFormat(url))
    return False if result is None else bool(result)


def manualVideoCheck(ru):
    result = sync_result(ru.manualVideoCheck())
    return False if result is None else bool(result)


def localProxy(ru, param):
    return proxy(ru, param)


def proxy(ru, params):
    from base.spider import Spider
    modern = getattr(ru, 'proxy', None)
    legacy = getattr(ru, 'localProxy', None)
    if callable(modern) and getattr(modern, '__func__', modern) is not Spider.proxy:
        method = modern
    elif callable(legacy) and getattr(legacy, '__func__', legacy) is not Spider.localProxy:
        method = legacy
    else:
        method = modern if callable(modern) else legacy
    if not callable(method):
        return [404, 'text/plain', '']
    return sync_result(method(str2json(params)))


def action(ru, action):
    result = ru.action(action)
    formatJo = encode_json(result)
    return formatJo


class _Subscription:
    def __init__(self, emitter):
        self._lock = Lock()
        self._emitter = emitter
        self._stop = None
        self._closed = False

    def emit(self, value):
        with self._lock:
            emitter = None if self._closed else self._emitter
        if emitter is not None:
            emitter.emit(encode_json(value))

    def attach(self, stop):
        with self._lock:
            closed = self._closed
            if not closed:
                self._stop = stop
        if closed:
            sync_result(stop())

    def close(self):
        with self._lock:
            if self._closed:
                return
            self._closed = True
            self._emitter = None
            stop, self._stop = self._stop, None
        if stop is not None:
            sync_result(stop())


def danmaku(ru, id, emitter):
    subscription = _Subscription(emitter)
    subscriptions = getattr(ru, '_tv_subscriptions', None)
    if subscriptions is None:
        subscriptions = ru._tv_subscriptions = set()
    subscriptions.add(subscription)
    try:
        stop = sync_result(ru.danmaku(id, subscription.emit))
        if not callable(stop):
            raise TypeError('danmaku must return a stop function')
        subscription.attach(stop)
    except BaseException:
        subscription.close()
        subscriptions.discard(subscription)
        raise
    def close():
        try:
            subscription.close()
        finally:
            subscriptions.discard(subscription)
    return close


def destroy(ru):
    failure = None
    for subscription in tuple(getattr(ru, '_tv_subscriptions', ())):
        try:
            subscription.close()
        except BaseException as error:
            failure = cleanup_failure(failure, error)
    try:
        closeDialog(ru)
    except BaseException as error:
        failure = cleanup_failure(failure, error)
    try:
        sync_result(ru.destroy())
    except BaseException as error:
        failure = cleanup_failure(failure, error)
    try:
        release(ru)
    except BaseException as error:
        failure = cleanup_failure(failure, error)
    if failure is not None:
        raise_failure(failure)


def release(ru):
    runtime = getattr(ru, 'runtime', None)
    if runtime is not None:
        runtime.close()


def closeDialog(ru):
    dialog = getattr(ru, '_tv_dialog', None)
    if dialog is not None:
        dialog.close()


def run():
    pass


if __name__ == '__main__':
    run()


def bind(ru, native_net, native_local, native_dialog=None, streams=None, js=None, user_agent=None):
    from base.net import bind as bind_api
    bind_api(ru, native_net, native_local, streams, js, user_agent)
    from _tv_dialog import DialogAdapter
    ru._tv_dialog = DialogAdapter(native_dialog)
    runtime = getattr(ru, 'runtime', None)
    if runtime is not None:
        runtime.site_key = getattr(ru, 'siteKey', runtime.site_key)
        runtime.bind_helpers(ru)
