"""Local smoke/concurrency exercise; Python 3 standard library only."""
import argparse
import collections
import concurrent.futures
import json
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument("--url", default="http://localhost:8080")
parser.add_argument("--requests", type=int, default=100)
parser.add_argument("--workers", type=int, default=16)
args = parser.parse_args()

def attempt(_):
    request = urllib.request.Request(
        args.url + "/api/reservations",
        data=json.dumps({"customer": "load-buyer", "quantity": 1}).encode(),
        headers={"Content-Type": "application/json", "Idempotency-Key": str(uuid.uuid4())},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code
    except (urllib.error.URLError, TimeoutError):
        return "connection-error"

with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
    print("HTTP outcomes:", dict(collections.Counter(pool.map(attempt, range(args.requests)))))
with urllib.request.urlopen(args.url + "/api/inventory", timeout=10) as response:
    inventory = json.load(response)
print("SQL inventory:", inventory)
assert inventory["available"] + inventory["reserved"] + inventory["sold"] == inventory["total"]
