import json, re, subprocess, sys
KEY = re.search(r'"INNERTUBE_API_KEY":"([^"]*)"', open('w.html',encoding='utf-8',errors='replace').read()).group(1)
CTX = {"context":{"client":{"clientName":"WEB","clientVersion":"2.20240726.00.00","hl":"en","gl":"IN"}}}
ids=[]
def post(body):
    p = subprocess.run(["curl","-s","-X","POST",
        f"https://www.youtube.com/youtubei/v1/browse?key={KEY}&prettyPrint=false",
        "-H","Content-Type: application/json",
        "-H","User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36",
        "-d", json.dumps(body)], capture_output=True, text=True, timeout=120)
    return p.stdout
body = dict(CTX); body["browseId"]="UCcRzfXOtaGnOqACoDMaW6Ow"; body["params"]="EgZ2aWRlb3PyBgQKAjoA"
page = post(body); pages=0
while True:
    pages+=1
    for m in re.finditer(r'"(?:contentId|videoId)":"([A-Za-z0-9_-]{11})"', page):
        v=m.group(1)
        if v not in ids: ids.append(v)
    m = list(re.finditer(r'"continuationCommand":\{"token":"([^"]+)"', page))
    if not m or pages>25: break
    body2 = dict(CTX); body2["continuation"]=m[-1].group(1)
    nxt = post(body2)
    if not nxt or nxt==page: break
    page=nxt
json.dump(ids, open('ids.json','w'))
print(f"pages={pages} ids={len(ids)}")
