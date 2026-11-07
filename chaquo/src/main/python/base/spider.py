import re
import os
import json
import time
import requests
from lxml import etree
from com.github.catvod import Proxy, Init
from urllib.parse import urlencode

_PROXY_DEFAULT = object()
from com.chaquo.python import Python
from importlib.machinery import SourceFileLoader


class Spider:
    _instance = None

    def __init__(self):
        self.extend = ''

    def __new__(cls, *args, **kwargs):
        if cls.__dict__.get('_instance') is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def init(self, extend=""):
        pass

    def homeContent(self, filter):
        return {"class": []}

    def homeVideoContent(self):
        return {"list": []}

    def categoryContent(self, tid, pg, filter, extend):
        return {"list": []}

    def detailContent(self, ids):
        return {"list": []}

    def searchContent(self, key, quick, pg="1"):
        return {"list": []}

    def playerContent(self, flag, id, vipFlags):
        return {}

    def liveContent(self, url):
        return ""

    def localProxy(self, param):
        return [404, "text/plain", ""]

    def proxy(self, params):
        return [404, "text/plain", ""]

    def danmaku(self, id, emit):
        raise NotImplementedError("Spider does not support live danmaku")

    def isVideoFormat(self, url):
        return False

    def manualVideoCheck(self):
        return False

    def action(self, action):
        return {}

    def destroy(self):
        pass

    def getName(self):
        return self.__class__.__name__

    def dialog(self, options, onAction=None):
        bridge = getattr(self, '_tv_dialog', None)
        if bridge is None:
            raise RuntimeError("Dialog bridge is unavailable")
        return bridge.show(options, onAction)

    def getDependence(self):
        return []

    def loadSpider(self, name):
        runtime = getattr(self, 'runtime', None)
        spider = runtime.spider(name) if runtime is not None else self.loadModule(name).Spider()
        for attribute in ('net', 'local', 'js', 'siteKey'):
            if hasattr(self, attribute):
                setattr(spider, attribute, getattr(self, attribute))
        if hasattr(self, '_tv_dialog'):
            spider._tv_dialog = self._tv_dialog
        return spider

    def loadModule(self, name):
        runtime = getattr(self, 'runtime', None)
        if runtime is not None:
            return runtime.load_reference(name)
        cache_dir = Python.getPlatform().getApplication().getCacheDir().getAbsolutePath()
        path = os.path.join(os.path.join(cache_dir, 'py'),  f'{name}.py')
        return SourceFileLoader(name, path).load_module()

    def regStr(self, reg, src, group=1):
        m = re.search(reg, src)
        src = ''
        if m:
            src = m.group(group)
        return src

    def removeHtmlTags(self, src):
        clean = re.compile('<.*?>')
        return re.sub(clean, '', src)

    def cleanText(self, src):
        clean = re.sub('[\U0001F600-\U0001F64F\U0001F300-\U0001F5FF\U0001F680-\U0001F6FF\U0001F1E0-\U0001F1FF]', '', src)
        return clean

    def fetch(self, url, params=None, cookies=None, headers=None, timeout=5, verify=True, stream=False, allow_redirects = True):
        rsp = requests.get(url, params=params, cookies=cookies, headers=headers, timeout=timeout, verify=verify, stream=stream, allow_redirects=allow_redirects)
        rsp.encoding = 'utf-8'
        return rsp

    def post(self, url, params=None, data=None, json=None, cookies=None, headers=None, timeout=5, verify=True, stream=False, allow_redirects = True):
        rsp = requests.post(url, params=params, data=data, json=json, cookies=cookies, headers=headers, timeout=timeout, verify=verify, stream=stream, allow_redirects=allow_redirects)
        rsp.encoding = 'utf-8'
        return rsp

    def html(self, content):
        return etree.HTML(content)

    def str2json(str):
        return json.loads(str)

    def json2str(str):
        return json.dumps(str, ensure_ascii=False)
    
    def getProxyUrl(self, local=_PROXY_DEFAULT):
        # Preserve the explicit legacy boolean overload (including LAN URLs).
        if isinstance(local, bool):
            return f'{Proxy.getUrl(local)}?do=py'
        params = {} if local is _PROXY_DEFAULT or local is None else dict(local)
        if "do" in params or "siteKey" in params:
            raise ValueError("Reserved proxy parameter")
        query = {"do": "py", "siteKey": getattr(self, "siteKey", "")}
        query.update(params)
        return str(Proxy.getUrl(True)) + "?" + urlencode(query)

    def proxyUrl(self, params=None, local=True):
        params = {} if params is None else params
        if not isinstance(params, dict) or any(not isinstance(key, str) or not isinstance(value, str)
                                              for key, value in params.items()):
            raise TypeError("Proxy parameters require string keys and values")
        if not isinstance(local, bool):
            raise TypeError("Proxy local flag requires a boolean")
        if 'do' in params or 'siteKey' in params:
            raise ValueError("Reserved proxy parameter")
        return str(Proxy.getUrl(local)) + '?' + urlencode({'do': 'py', 'siteKey': getattr(self, 'siteKey', ''), **params})

    def danmakuUrl(self, id):
        from java import jclass
        return str(jclass('com.github.catvod.crawler.Spider').danmakuUrl(getattr(self, 'siteKey', ''), id))

    def getActivity(self):
        return Init.activity()

    def page(self, items, pg, limit, total=None):
        pg, limit = int(pg), int(limit)
        if pg < 1 or limit < 1:
            raise ValueError("Page and page size must be positive")
        if total is None:
            total = len(items)
            start = (pg - 1) * limit
            items = items[start:start + limit]
        if isinstance(total, bool) or not isinstance(total, int) or total < 0:
            raise ValueError("Total must be a nonnegative integer")
        return {"list": items, "page": pg, "pagecount": (total + limit - 1) // limit,
                "limit": limit, "total": total}

    @staticmethod
    def link(name, target):
        from java import jclass
        value = jclass("com.google.gson.JsonParser").parseString(json.dumps(target, ensure_ascii=False))
        return str(jclass("com.github.catvod.utils.Json").link(name, value.getAsJsonObject()))

    def log(self, msg):
        if isinstance(msg, dict) or isinstance(msg, list):
            print(json.dumps(msg, ensure_ascii=False))
        else:
            print(f'{msg}')

    def getCache(self, key):
        value = self.fetch(f'http://127.0.0.1:{Proxy.getPort()}/cache?do=get&key={key}', timeout=5).text
        if len(value) > 0:
            if value.startswith('{') and value.endswith('}') or value.startswith('[') and value.endswith(']'):
                value = json.loads(value)
                if type(value) == dict:
                    if not 'expiresAt' in value or value['expiresAt'] >= int(time.time()):
                        return value
                    else:
                        self.delCache(key)
                        return None
            return value
        else:
            return None

    def setCache(self, key, value):
        if type(value) in [int, float]:
            value = str(value)
        if len(value) > 0:
            if type(value) == dict or type(value) == list:
                value = json.dumps(value, ensure_ascii=False)
        r = self.post(f'http://127.0.0.1:{Proxy.getPort()}/cache?do=set&key={key}', data={"value": value}, timeout=5)
        return 'succeed' if r.status_code == 200 else 'failed'

    def delCache(self, key):
        r = self.fetch(f'http://127.0.0.1:{Proxy.getPort()}/cache?do=del&key={key}', timeout=5)
        return 'succeed' if r.status_code == 200 else 'failed'
