#!/usr/bin/env python3
"""Download files from a public MEGA folder by handle, without an account.

The listing gives each node a handle and a `k` field; for a public link the node key is that
field's last segment unwrapped with the link key. A file download is then two calls: `g` for a
temporary URL, then a straight GET of it. The bytes are AES-CTR encrypted with the node key, the
nonce being the low 8 bytes of that key.

Verified against the reference implementation (mega.py's crypto module) because every part of this
had a wrong-looking plausible answer: the handle goes in the query string, attributes are AES-CBC
with a zero IV, and decrypt_key unwraps one 16-byte block at a time without folding.
"""
import base64
import json
import os
import struct
import sys
import urllib.request

API = "https://g.api.mega.co.nz/cs"

try:
    from Crypto.Cipher import AES
    from Crypto.Util import Counter
except ImportError:
    sys.exit("needs pycryptodome")


def b64d(s):
    s = s.replace("-", "+").replace("_", "/")
    return base64.b64decode(s + "=" * (-len(s) % 4))


def decrypt_key(k, parent=None):
    """16-byte AES key for a node's attributes (see the module docstring)."""
    raw = k if isinstance(k, (bytes, bytearray)) else b64d(k)
    if parent is not None:
        out = b""
        for i in range(0, len(raw) - (len(raw) % 16), 16):
            out += AES.new(parent, AES.MODE_CBC, b"\x00" * 16).decrypt(raw[i:i + 16])
        raw = out
    if len(raw) >= 32:
        folded = []
        for i in range(4):
            folded.append(struct.unpack(">I", raw[i * 4:i * 4 + 4])[0]
                          ^ struct.unpack(">I", raw[(i + 4) * 4:(i + 4) * 4 + 4])[0])
        return struct.pack(">IIII", *folded)
    return bytes(raw[:16])


def _fold(raw):
    """XOR the two 16-byte halves of a 32-byte key, which is how MEGA derives an AES key from a link key."""
    if len(raw) < 32:
        return b""
    w = struct.unpack(">IIIIIIII", raw[:32])
    return struct.pack(">IIII", w[0] ^ w[4], w[1] ^ w[5], w[2] ^ w[6], w[3] ^ w[7])


def _unwrap(raw, parent):
    """AES-CBC unwrap, keeping every byte: for a file the upper 16 bytes are the CTR nonce."""
    out = b""
    for i in range(0, len(raw) - (len(raw) % 16), 16):
        out += AES.new(parent, AES.MODE_CBC, b"\x00" * 16).decrypt(raw[i:i + 16])
    return out


def dec_attrs(aes_key, enc):
    data = b64d(enc)
    if not data:
        return {}
    data = data[:len(data) - (len(data) % 16)]
    plain = AES.new(aes_key, AES.MODE_CBC, b"\x00" * 16).decrypt(data)
    txt = plain.rstrip(b"\x00").decode("utf-8", "replace")
    if txt.startswith("MEGA"):
        txt = txt[4:]
    try:
        return json.loads(txt)
    except Exception:
        return {"_raw": txt[:200]}


def api(payload, n=""):
    body = json.dumps([payload]).encode()
    req = urllib.request.Request(API + "?id=0" + ("&n=" + n if n else ""),
                                 data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        out = json.loads(r.read().decode())
    if isinstance(out, list):
        out = out[0] if out else {}
    if isinstance(out, int):
        raise RuntimeError("MEGA error %d for n=%s" % (out, n))
    return out


def listing(handle, key):
    """Every node under the link, with its resolved name, path, size and decrypted file key."""
    aes_key = decrypt_key(key)
    node = api({"a": "f", "c": 1, "r": 1}, n=handle)
    raw = {}
    for child in node.get("f", []):
        ck = child.get("k", "")
        # `k` can carry SEVERAL "key-id:base64key" pairs. The right one is whichever unwraps with a key we hold AND
        # yields valid attribute JSON - every pair "decrypts" to something, so taking the first silently produced a
        # handle where a name should be and a ciphertext file on disk (measured: 52 files saved as noise).
        # Trying each and keeping the one that parses is self-checking and needs no guess about ordering.
        node_key = None
        file_key = None
        if ck:
            for pair in [p for p in ck.split("/") if ":" in p]:
                try:
                    cand = _unwrap(b64d(pair.partition(":")[2]), aes_key)
                except Exception:
                    continue
                looks_ok = False
                for cand_aes in (cand[:16], _fold(cand)):
                    if not cand_aes or len(cand_aes) != 16:
                        continue
                    probe = dec_attrs(cand_aes, child.get("a", ""))
                    if probe.get("n"):
                        node_key = cand_aes
                        file_key = cand
                        looks_ok = True
                        break
                if looks_ok:
                    break
        if node_key is None:
            node_key = aes_key
            file_key = file_key or aes_key
        attrs = dec_attrs(node_key, child.get("a", ""))
        raw[child.get("h")] = {
            "name": attrs.get("n", child.get("h")),
            "kind": "dir" if child.get("t") == 1 else "file",
            "size": child.get("s", 0) if child.get("t") == 0 else 0,
            "handle": child.get("h"),
            "parent": child.get("p"),
            # the file's own key: 32 bytes for a file, and the low 8 are the CTR nonce
            "fkey": file_key if file_key else node_key,
        }

    def path_of(h, seen=None):
        seen = seen or set()
        if h in seen:
            return ""
        seen.add(h)
        n = raw.get(h)
        if not n:
            return ""
        par = n["parent"]
        if par in raw:
            return (path_of(par, seen) + "/" + n["name"]).lstrip("/")
        return n["name"]

    for h, n in raw.items():
        n["path"] = path_of(h)
    return raw


def download(handle, fkey, dest, link_root=None):
    """Fetch one file: ask for a URL, then GET it and decrypt with AES-CTR.

    <p>The `n` in the query string must be the LINK ROOT handle, with the file's own handle in the body. Passing the
    file handle as `n` answers -9 (verified: `n=<file>` errors, `n=<root>` with `{"n":"<file>"}` returns the URL).
    """
    info = api({"a": "g", "g": 1, "n": handle}, n=link_root or "")
    url = info.get("g")
    if not url:
        raise RuntimeError("no download url for %s: %s" % (handle, info))
    k = fkey
    if len(k) < 24:
        raise RuntimeError("file key is %d bytes, need >=24 for the CTR nonce" % len(k))
    w = struct.unpack(">IIIIIIII", k[:32])
    aes_key = struct.pack(">IIII", w[0] ^ w[4], w[1] ^ w[5], w[2] ^ w[6], w[3] ^ w[7])
    iv0, iv1 = w[4], w[5]
    ctr = Counter.new(128, initial_value=((iv0 << 32) + iv1) << 64)
    aes = AES.new(aes_key, AES.MODE_CTR, counter=ctr)
    tmp = dest + ".part"
    with urllib.request.urlopen(url, timeout=300) as r, open(tmp, "wb") as f:
        while True:
            block = r.read(1 << 16)
            if not block:
                break
            f.write(aes.decrypt(block))
    os.replace(tmp, dest)
    return dest


def main():
    link = sys.argv[1]
    frag = link.split("/folder/")[-1].split("/")[0]
    handle, _, key = frag.partition("#")
    nodes = listing(handle, key)
    if len(sys.argv) == 2:
        for n in sorted(nodes.values(), key=lambda x: x["path"]):
            tag = "DIR " if n["kind"] == "dir" else "FILE"
            print("%s %10d %s" % (tag, n["size"], n["path"]))
        return
    # download every file whose path contains the substring in argv[2]
    want = sys.argv[2]
    outdir = sys.argv[3] if len(sys.argv) > 3 else "."
    todo = [n for n in nodes.values() if n["kind"] == "file" and want in n["path"]]
    print("%d files match %r" % (len(todo), want))
    for n in todo:
        safe = n["path"].replace("/", "__")
        dest = os.path.join(outdir, safe)
        if os.path.exists(dest) and os.path.getsize(dest) == n["size"]:
            print("  skip (have) %s" % safe)
            continue
        download(n["handle"], n["fkey"], dest, link_root=handle)
        print("  got %8d %s" % (n["size"], safe))


if __name__ == "__main__":
    main()
