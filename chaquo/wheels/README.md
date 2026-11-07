# pywasm 1.0.8 Android build dependency

`pywasm-1.0.8-py3-none-any.whl` packages the unchanged upstream Python source. PyPI
publishes this version only as a source distribution, while Chaquopy installs
cross-platform requirements using binary distributions. The local `--find-links`
setting supplies this wheel for normal local builds and CI. Requirements continue
to pin `pywasm==1.0.8` and `numpy==1.26.2`.

The wheel is 28,828 bytes, uses `Root-Is-Purelib: true` and `Tag: py3-none-any`, and
contains no native libraries or Python bytecode. Its MIT license and all 10 package
files match the source archive. Metadata requires Python `>=3.6` and `numpy`;
NumPy remains a separate pinned Android requirement. Every `RECORD` digest was
verified. Two independent wheel builds produced identical bytes.

| Artifact | SHA256 |
| --- | --- |
| Official `pywasm-1.0.8.tar.gz` | `bdfb0b03394c601254caee415f3982107887a04ec7f40751ed878130d2ab7e15` |
| Vendored `pywasm-1.0.8-py3-none-any.whl` | `3651662d838fc0e83bbf8aa9a38185091ac96a40947b12c43355c9026196818a` |

Source metadata: <https://pypi.org/pypi/pywasm/1.0.8/json>.
The archive URL and source hash were checked against that metadata on 2026-11-07.
Full source and wheel audit details are in `pywasm-1.0.8.provenance.json`.

Rebuild on Python 3.12 with the pinned build tools. `SOURCE_DATE_EPOCH` is the
official source upload time (`2023-02-14T01:57:49Z`); it fixes ZIP timestamps.

```sh
python3.12 -m venv /tmp/pywasm-wheel-build
/tmp/pywasm-wheel-build/bin/python -m pip install pip==24.0 setuptools==75.6.0 wheel==0.45.1
curl --fail --location https://files.pythonhosted.org/packages/40/ad/a8e9f90870697df8c91d26141317704f24844981edf2dcdfa9f6ac4e4aee/pywasm-1.0.8.tar.gz --output /tmp/pywasm-1.0.8.tar.gz
printf '%s\n' 'bdfb0b03394c601254caee415f3982107887a04ec7f40751ed878130d2ab7e15  /tmp/pywasm-1.0.8.tar.gz' | sha256sum --check
SOURCE_DATE_EPOCH=1676339869 /tmp/pywasm-wheel-build/bin/python -m pip wheel --no-deps --no-build-isolation --no-cache-dir --wheel-dir /tmp/pywasm-wheel-output /tmp/pywasm-1.0.8.tar.gz
sha256sum /tmp/pywasm-wheel-output/pywasm-1.0.8-py3-none-any.whl
```

`test_vendored_wheels.py` checks the wheel hash, version, platform-independent tag,
license, NumPy dependency and complete `RECORD` during host regression tests.
The source integration suite also exercises the original Yangshipin `_Keygen`
against a minimal WASM fixture using these pinned versions.
