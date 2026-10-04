"""Throwaway Nostr relay spike for 20.07 v3 (decision 66). Not part of the repo.

Publishes small random-byte *ephemeral* events (kind 20001) tagged with a random handle, from one
connection, and listens on a second. Measures: connect time, accept/reject + reason, delivery
latency, filter isolation (wrong handle not delivered), ephemeral non-storage, size limits, and
burst rate limiting. Payload is random bytes -- no real data of any kind.
"""
import asyncio, base64, hashlib, json, os, sys, time, secrets
import websockets

# ---- BIP340 schnorr (pure python, reference algorithm) ----
P = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
     0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)

def padd(a, b):
    if a is None: return b
    if b is None: return a
    if a[0] == b[0] and a[1] != b[1]: return None
    if a == b: l = 3 * a[0] * a[0] * pow(2 * a[1], P - 2, P) % P
    else: l = (b[1] - a[1]) * pow(b[0] - a[0], P - 2, P) % P
    x = (l * l - a[0] - b[0]) % P
    return (x, (l * (a[0] - x) - a[1]) % P)

def pmul(k, pt):
    r = None
    for i in range(256):
        if (k >> i) & 1: r = padd(r, pt)
        pt = padd(pt, pt)
    return r

def th(tag, *m):
    t = hashlib.sha256(tag.encode()).digest()
    return hashlib.sha256(t + t + b"".join(m)).digest()

def sign(msg, sk):
    d = sk
    pk = pmul(d, G)
    if pk[1] % 2: d = N - d
    pkb = pk[0].to_bytes(32, "big")
    a = th("BIP0340/aux", secrets.token_bytes(32))
    t = (d ^ int.from_bytes(th("BIP0340/aux", a), "big")).to_bytes(32, "big")
    k = int.from_bytes(th("BIP0340/nonce", t, pkb, msg), "big") % N
    R = pmul(k, G)
    if R[1] % 2: k = N - k
    e = int.from_bytes(th("BIP0340/challenge", R[0].to_bytes(32, "big"), pkb, msg), "big") % N
    return R[0].to_bytes(32, "big") + ((k + e * d) % N).to_bytes(32, "big"), pkb.hex()

def make_event(sk, kind, tags, content):
    pub = pmul(sk, G)[0].to_bytes(32, "big").hex()
    ts = int(time.time())
    ser = json.dumps([0, pub, ts, kind, tags, content], separators=(",", ":"), ensure_ascii=False)
    eid = hashlib.sha256(ser.encode()).digest()
    sig, _ = sign(eid, sk)
    return {"id": eid.hex(), "pubkey": pub, "created_at": ts, "kind": kind, "tags": tags,
            "content": content, "sig": sig.hex()}

# ---- spike ----
KIND = 20001
RELAYS = ["wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net",
          "wss://offchain.pub", "wss://nostr.mom", "wss://relay.snort.social",
          "wss://relay.nostr.band", "wss://nostr.wine"]

def rnd_content(n):
    return base64.b64encode(os.urandom(n * 3 // 4)).decode()[:n]

async def run_relay(url, handle, sk):
    res = {"relay": url}
    sub_events = {}   # event id -> recv time
    try:
        t0 = time.time()
        sub = await asyncio.wait_for(websockets.connect(url, open_timeout=10, max_size=2**20), 15)
        pub = await asyncio.wait_for(websockets.connect(url, open_timeout=10, max_size=2**20), 15)
        res["connect_s"] = round(time.time() - t0, 2)
    except Exception as e:
        res["error"] = f"connect failed: {type(e).__name__} {str(e)[:80]}"
        return res
    notices = []
    other_handle = secrets.token_hex(8)
    other_seen = []

    async def reader():
        try:
            async for raw in sub:
                m = json.loads(raw)
                if m[0] == "EVENT":
                    ev = m[2]
                    sub_events[ev["id"]] = time.time()
                    if any(t[:2] == ["t", other_handle] for t in ev["tags"]):
                        other_seen.append(ev["id"])
                elif m[0] in ("NOTICE", "CLOSED"):
                    notices.append(str(m)[:140])
        except Exception:
            pass
    rtask = asyncio.create_task(reader())
    await sub.send(json.dumps(["REQ", "s1", {"kinds": [KIND], "#t": [handle], "since": int(time.time()) - 5}]))
    await asyncio.sleep(1.5)

    acks = {}
    async def ack_reader():
        try:
            async for raw in pub:
                m = json.loads(raw)
                if m[0] == "OK": acks[m[1]] = (m[2], m[3] if len(m) > 3 else "", time.time())
                elif m[0] in ("NOTICE", "AUTH"): notices.append(str(m)[:140])
        except Exception:
            pass
    atask = asyncio.create_task(ack_reader())

    async def send(size, h=None):
        ev = make_event(sk, KIND, [["t", h or handle]], rnd_content(size))
        ts = time.time()
        await pub.send(json.dumps(["EVENT", ev]))
        return ev["id"], ts

    # 1) steady: 10 events of 400 B, 1/s
    sent = {}
    for _ in range(10):
        eid, ts = await send(400); sent[eid] = ts
        await asyncio.sleep(1.0)
    # 2) wrong handle must not arrive
    await send(400, other_handle)
    await asyncio.sleep(3)
    ok = sum(1 for e in sent if acks.get(e, (False,))[0])
    got = [e for e in sent if e in sub_events]
    lat = sorted(sub_events[e] - sent[e] for e in got)
    res["steady"] = {"sent": 10, "accepted": ok, "delivered": len(got),
                     "lat_p50_s": round(lat[len(lat)//2], 2) if lat else None,
                     "lat_max_s": round(lat[-1], 2) if lat else None}
    rej = [acks[e][1] for e in sent if e in acks and not acks[e][0]]
    if rej: res["steady"]["reject_reasons"] = sorted(set(r[:80] for r in rej))
    res["wrong_handle_leaked"] = len(other_seen)

    # 3) size limits (ack only)
    sizes = {}
    for size in (1000, 2000, 8000, 32000, 100000):
        eid, ts = await send(size)
        await asyncio.sleep(2.0)
        a = acks.get(eid)
        sizes[size] = ("ok" if a and a[0] else ("rej: " + a[1][:60] if a else "no ack/closed"))
        if pub.close_code is not None: sizes[size] += " (conn closed)"; break
    res["sizes"] = sizes

    # 4) burst: 30 events as fast as possible
    if pub.close_code is None:
        burst = {}
        t1 = time.time()
        for _ in range(30):
            eid, ts = await send(400); burst[eid] = ts
        await asyncio.sleep(4)
        bok = sum(1 for e in burst if acks.get(e, (False,))[0])
        brej = sorted(set(acks[e][1][:70] for e in burst if e in acks and not acks[e][0]))
        res["burst30"] = {"accepted": bok, "delivered": sum(1 for e in burst if e in sub_events),
                          "send_took_s": round(time.time() - t1, 2), "reject_reasons": brej}

    # 5) ephemeral not stored: fresh connection, same filter, should return no old events
    try:
        chk = await asyncio.wait_for(websockets.connect(url, open_timeout=10), 15)
        await chk.send(json.dumps(["REQ", "s2", {"kinds": [KIND], "#t": [handle], "since": int(time.time()) - 600}]))
        stored = 0
        try:
            while True:
                m = json.loads(await asyncio.wait_for(chk.recv(), 4))
                if m[0] == "EVENT": stored += 1
                elif m[0] == "EOSE": break
        except asyncio.TimeoutError:
            pass
        res["stored_after_reconnect"] = stored
        await chk.close()
    except Exception as e:
        res["stored_after_reconnect"] = f"check failed {type(e).__name__}"
    if notices: res["notices"] = sorted(set(notices))[:4]
    for t in (rtask, atask): t.cancel()
    for c in (sub, pub):
        try: await c.close()
        except Exception: pass
    return res

async def main():
    handle = secrets.token_hex(8)
    sk = int.from_bytes(secrets.token_bytes(32), "big") % N
    print("handle", handle, flush=True)
    results = await asyncio.gather(*[run_relay(u, handle, sk) for u in RELAYS])
    print(json.dumps(results, indent=1))
    json.dump(results, open("results.json", "w"), indent=1)

asyncio.run(main())
