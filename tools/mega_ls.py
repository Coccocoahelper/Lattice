#!/usr/bin/env python3
"""List a public MEGA folder without an account.

MEGA's folder links carry two ids: a *handle* (the folder id) and a *key* (the decryption
key). The listing is served by api.mega.nz/cs with just those, as long as the request is
shaped like the web client's: `{"a":"f","c":1,"r":1}` with `n=<handle>` for a folder.

Decryption is AES-CBC with a zero IV over the base64-decoded key, and the folder contents
come back as an AES-CTR encrypted attribute string. Doing it directly avoids megatools
(which insists on a login) and mega.py (which is broken on Python 3.11+ because
asyncio.coroutine was removed).
"""
import base64
import json
import struct
import sys
import urllib.error
import urllib.request

API = "https://g.api.mega.co.nz/cs"

try:
    from Crypto.Cipher import AES
    from Crypto.Util import Counter
except ImportError:
    sys.exit("needs pycryptodome: pip install pycryptodome")


def b64d(s):
    """MEGA uses URL-safe base64 with the padding stripped."""
    s = s.replace("-", "+").replace("_", "/")
    return base64.b64decode(s + "=" * (-len(s) % 4))


def a32(b):
    """The key is a sequence of 32-bit big-endian ints; AES wants 16 bytes at a time."""
    n = len(b) // 4
    return b"".join(struct.pack(">I", int.from_bytes(b[i * 4:i * 4 + 4], "big")) for i in range(n))


def decrypt_key(k, parent=None):
    """Resolve a node key to the 16-byte AES key used for its attributes.

    <p>MEGA's own `decrypt_key` unwraps 16 bytes at a time with AES-CBC and concatenates - it does NOT fold halves.
    With no parent, a 32-byte link key folds to 16 (the low half is the nonce); a 16-byte one is already the key.
    With a parent, the bytes are an unwrap: AES-CBC each 16-byte block with the parent key and concatenate, which for a
    single block is just the plaintext.
    """
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


def dec_attrs(aes_key, enc):
    """Attribute strings are AES-CBC with a zero IV - not CTR, which decrypts to noise."""
    data = b64d(enc)
    if not data:
        return {}
    data = data[:len(data) - (len(data) % 16)]
    plain = AES.new(aes_key, AES.MODE_CBC, b"\x00" * 16).decrypt(data)
    txt = plain.rstrip(b"\x00").decode("utf-8", "replace")
    # MEGA prefixes the JSON with "MEGA{...}" on newer nodes
    if txt.startswith("MEGA"):
        txt = txt[4:]
    try:
        return json.loads(txt)
    except Exception:
        return {"_raw": txt[:200]}


def api(payload, n=""):
    """The folder handle must ride in the query string as n=; in the body alone the API answers -11."""
    body = json.dumps([payload]).encode()
    req = urllib.request.Request(API + "?id=0" + ("&n=" + n if n else ""),
                                 data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        out = json.loads(r.read().decode())
    if isinstance(out, list):
        out = out[0] if out else {}
    if isinstance(out, int):
        raise RuntimeError("MEGA error %d for n=%s" % (out, n))
    return out


def walk(handle, key):
    """One request returns the whole subtree; `p` on each node is its parent, so paths come from the links."""
    aes_key = decrypt_key(key)
    parent_handle = handle
    node = api({"a": "f", "c": 1, "r": 1}, n=handle)
    raw = {}
    for child in node.get("f", []):
        # `k` is "own_handle:base64key" for a node under the link's key, and a longer chained form for a node shared
        # from elsewhere. Take the LAST segment, which is the key this node's attributes were written with.
        # `k` is a list of "key-id:base64key" pairs, comma-free and slash-separated, each key ENCRYPTED WITH THE KEY OF
        # THE NODE NAMED BY ITS ID. For a node under the public link that id is the link's own handle, so the segment
        # whose id matches is the one to unwrap with the key we already hold.
        ck = child.get("k", "")
        # Several "key-id:base64key" pairs can be present and every one of them "decrypts" to something; the right one
        # is whichever yields valid attribute JSON. Taking the first silently produced handles instead of names.
        node_key = None
        if ck:
            for pair in [p for p in ck.split("/") if ":" in p]:
                try:
                    cand = decrypt_key(b64d(pair.partition(":")[2]), aes_key)
                except Exception:
                    continue
                if dec_attrs(cand, child.get("a", "")).get("n"):
                    node_key = cand
                    break
        if node_key is None:
            node_key = aes_key
        try:
            attrs = dec_attrs(node_key, child.get("a", ""))
        except Exception:
            attrs = {}
        raw[child.get("h")] = {
            "name": attrs.get("n", child.get("h")),
            "kind": "dir" if child.get("t") == 1 else "file",
            "size": child.get("s", 0) if child.get("t") == 0 else 0,
            "handle": child.get("h"),
            "parent": child.get("p"),
            "key": child.get("k", ""),
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

    rows = []
    for h, n in raw.items():
        n = dict(n)
        n["path"] = path_of(h)
        rows.append(n)
    return rows


def main():
    url = sys.argv[1]
    frag = url.split("/folder/")[-1]
    if "/" in frag:
        frag = frag.split("/")[0]
    handle, _, key = frag.partition("#")
    rows = walk(handle, key)
    for r in sorted(rows, key=lambda x: (x["kind"] != "dir", x["name"].lower())):
        if r["kind"] == "dir":
            print("DIR                          %s" % r["path"])
        else:
            print("FILE  %10d  %s" % (r["size"], r["path"]))
    print("\n%d entries" % len(rows), file=sys.stderr)


if __name__ == "__main__":
    main()
