"""Нагрузка на MCP-шлюз: N параллельных клиентов в течение T секунд, вперемешку docs_search и rasp_find.

python3 deploy/load/mcp-load.py --key-file <файл с мастер-ключом> [--workers 50] [--seconds 300] [--url http://127.0.0.1:8081/mcp]

docs_search — каждый запрос с уникальной припиской, чтобы нагрузка доходила до rag, а не оседала в кэше шлюза.
rasp_find — пять постоянных запросов: после первого раза они в кэше, поэтому rasp.tpu.ru почти не нагружается.
Мастер-ключу нужны scopes docs.read и rasp.read и высокие лимиты (--rate=docs_search:100000,rasp_find:100000).
"""
import argparse
import collections
import json
import random
import threading
import time
import urllib.error
import urllib.request

DOCS = ["как получить справку об обучении", "как восстановиться после отчисления", "как уйти в академический отпуск",
        "какие есть стипендии", "как заселиться в общежитие", "как поменять пароль от учётной записи",
        "где найти учебный план", "как подать апелляцию", "есть ли бассейн", "как получить материальную помощь"]
RASP = ["403", "Иванов", "8К31", "ГК", "Петров"]


def call(url, key, name, arguments, rid):
    body = json.dumps({"jsonrpc": "2.0", "id": rid, "method": "tools/call",
                       "params": {"name": name, "arguments": arguments}}).encode("utf-8")
    request = urllib.request.Request(url, data=body, method="POST", headers={
        "Content-Type": "application/json", "Accept": "application/json, text/event-stream",
        "Authorization": f"Bearer {key}"})
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            result = json.loads(response.read().decode("utf-8"))["result"]
            outcome = result["structuredContent"].get("error", "ok")
    except urllib.error.HTTPError as e:
        outcome = f"http_{e.code}"
    except Exception as e:  # сеть, таймаут клиента
        outcome = type(e).__name__
    return outcome, (time.monotonic() - started) * 1000


def worker(args, key, deadline, stats, lock, index):
    rnd = random.Random(index)
    n = 0
    while time.monotonic() < deadline:
        n += 1
        if rnd.random() < 0.7:
            name, arguments = "docs_search", {"query": f"{rnd.choice(DOCS)} {index}-{n}"}
        else:
            name, arguments = "rasp_find", {"query": rnd.choice(RASP)}
        outcome, ms = call(args.url, key, name, arguments, n)
        with lock:
            stats[name].append(ms)
            stats["outcomes"].append(f"{name}:{outcome}")


def percentile(values, p):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(len(ordered) * p / 100))] if ordered else 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8081/mcp")
    parser.add_argument("--key-file", required=True)
    parser.add_argument("--workers", type=int, default=50)
    parser.add_argument("--seconds", type=int, default=300)
    args = parser.parse_args()
    key = open(args.key_file, encoding="utf-8").read().strip()
    stats = collections.defaultdict(list)
    lock = threading.Lock()
    deadline = time.monotonic() + args.seconds
    threads = [threading.Thread(target=worker, args=(args, key, deadline, stats, lock, i)) for i in range(args.workers)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    total = len(stats["outcomes"])
    print(f"вызовов: {total} за {args.seconds} с ({total / args.seconds:.1f} в секунду), клиентов: {args.workers}")
    for name in ("docs_search", "rasp_find"):
        values = stats[name]
        print(f"{name}: {len(values)} вызовов, p50 {percentile(values, 50):.0f} мс, p95 {percentile(values, 95):.0f} мс, макс {max(values or [0]):.0f} мс")
    print("итоги:", dict(collections.Counter(stats["outcomes"])))


if __name__ == "__main__":
    main()
