"""Download the pinned OTel Java agent, verifying the release asset digest."""
import hashlib
from pathlib import Path
import urllib.request

VERSION = '2.20.0'
DIGEST = '191666bf4346ab0677f92304bb272cd85f5954f49c03bb9636134e59c9a3276d'


def ensure_agent():
    path = Path(__file__).resolve().parents[1] / '.tools/opentelemetry-javaagent.jar'
    if not path.exists():
        urllib.request.urlretrieve('https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v'+VERSION+'/opentelemetry-javaagent.jar', path)
    if hashlib.sha256(path.read_bytes()).hexdigest() != DIGEST:
        raise RuntimeError('OTel Java agent checksum mismatch')
    return path
