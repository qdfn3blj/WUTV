package com.fongmi.android.tv.server.process;

import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.utils.WebViewUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

/**
 * WUTV MCP Server v1.3 - 让外部 AI (Operit) 完全支配壳内能力
 *
 * v1.3: WebView 离屏模式 - 不挂视图树, 手动layout(1080x1920) + setNetworkAvailable(true)
 *       修复挂载2x2视图导致渲染暂停、页面加载不启动的问题
 */
public class Mcp implements Process {

    private static final String TOKEN = "wutv-mcp-2026";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/mcp");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        if (!"POST".equals(session.getMethod().name())) {
            JsonObject info = new JsonObject();
            info.addProperty("server", "WUTV-MCP");
            info.addProperty("version", "1.3");
            info.addProperty("usage", "POST JSON-RPC 2.0 to /mcp with header X-MCP-Token");
            return Nano.ok(info.toString());
        }

        String token = session.getHeaders().get("x-mcp-token");
        if (!TOKEN.equals(token)) {
            return Nano.error(Response.Status.UNAUTHORIZED, "{\"error\":\"bad token\"}");
        }

        String body = files.get("postData");
        if (TextUtils.isEmpty(body)) body = "";
        JsonObject req;
        try {
            req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            return Nano.error("{\"error\":\"invalid json\"}");
        }

        String method = req.has("method") ? req.get("method").getAsString() : "";
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.get("params").getAsJsonObject() : new JsonObject();

        JsonObject result = new JsonObject();
        String err = null;
        try {
            switch (method) {
                case "initialize":
                    result.addProperty("protocolVersion", "2024-11-05");
                    result.addProperty("serverInfo", "WUTV-MCP/1.3");
                    break;
                case "tools/list":
                    result.add("tools", toolsList());
                    break;
                case "tools/call":
                    result = callTool(params);
                    break;
                default:
                    err = "unknown method: " + method;
            }
        } catch (Throwable e) {
            err = type(e) + ": " + msg(e);
        }

        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        if (req.has("id")) resp.add("id", req.get("id"));
        if (err != null) {
            JsonObject eo = new JsonObject();
            eo.addProperty("code", -32000);
            eo.addProperty("message", err);
            resp.add("error", eo);
        } else {
            resp.add("result", result);
        }
        return Nano.ok(resp.toString());
    }

    private static String type(Throwable e) { return e == null ? "null" : e.getClass().getSimpleName(); }
    private static String msg(Throwable e) { return e == null ? "" : String.valueOf(e.getMessage()); }

    private JsonArray toolsList() {
        JsonArray arr = new JsonArray();
        tool(arr, "site_home", "站点首页分类+筛选", "{key}");
        tool(arr, "site_category", "分类列表(含筛选)", "{key,tid,pg,extend_json}");
        tool(arr, "site_search", "搜索(走壳网络栈,可过CF)", "{site_key,keyword,pg}");
        tool(arr, "site_detail", "详情+线路集数", "{key,id}");
        tool(arr, "site_player", "播放地址(嗅探后真实url)", "{key,flag,id}");
        tool(arr, "webview_fetch", "壳WebView加载URL返回渲染后HTML(过CF利器)", "{url,wait_ms}");
        tool(arr, "webview_eval", "在壳WebView页面执行JS", "{url,js,wait_ms}");
        tool(arr, "webview_cookies", "读取指定域的Cookie", "{domain}");
        tool(arr, "player_state", "当前播放器状态/进度/地址", "{}");
        tool(arr, "player_stop", "停止播放", "{}");
        tool(arr, "debug_logs", "读取壳调试日志(报错/Spider输出)", "{lines}");
        tool(arr, "config_sites", "列出已配置的点播站点", "{}");
        return arr;
    }

    private void tool(JsonArray arr, String name, String desc, String schema) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", desc + " | args: " + schema);
        arr.add(t);
    }

    private JsonObject callTool(JsonObject params) throws Exception {
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.get("arguments").getAsJsonObject() : new JsonObject();

        String text;
        switch (name) {
            case "site_home":      text = tSiteHome(args); break;
            case "site_category":  text = tSiteCategory(args); break;
            case "site_search":    text = tSiteSearch(args); break;
            case "site_detail":    text = tSiteDetail(args); break;
            case "site_player":    text = tSitePlayer(args); break;
            case "webview_fetch":  text = tWebviewFetch(args); break;
            case "webview_eval":   text = tWebviewEval(args); break;
            case "webview_cookies":text = tWebviewCookies(args); break;
            case "player_state":   text = tPlayerState(); break;
            case "player_stop":    text = tPlayerStop(); break;
            case "debug_logs":     text = tDebugLogs(args); break;
            case "config_sites":   text = tConfigSites(); break;
            default: throw new IllegalArgumentException("unknown tool: " + name);
        }

        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);
        JsonArray contents = new JsonArray();
        contents.add(content);
        JsonObject out = new JsonObject();
        out.add("content", contents);
        return out;
    }

    private static String arg(JsonObject a, String k, String def) {
        return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsString() : def;
    }

    private static int argInt(JsonObject a, String k, int def) {
        try { return a.has(k) ? Integer.parseInt(a.get(k).getAsString()) : def; }
        catch (Exception e) { return def; }
    }

    // ---------- Spider 组 ----------

    private String tSiteHome(JsonObject a) throws Exception {
        final String key = arg(a, "key", "");
        final AtomicReference<String> ref = new AtomicReference<>("{\"error\":\"timeout\"}");
        final CountDownLatch latch = new CountDownLatch(1);
        App.post(() -> {
            try {
                Result r = SiteApi.homeContent(VodConfig.get().getSite(key));
                ref.set(r.toString());
            } catch (Throwable e) {
                ref.set("{\"error\":\"" + type(e) + ":" + msg(e) + "\"}");
            }
            latch.countDown();
        });
        latch.await(30, TimeUnit.SECONDS);
        return ref.get();
    }

    private String tSiteCategory(JsonObject a) throws Exception {
        final String key = arg(a, "key", "");
        final String tid = arg(a, "tid", "1");
        final String pg = arg(a, "pg", "1");
        final String ext = arg(a, "extend_json", "{}");
        final AtomicReference<String> ref = new AtomicReference<>("{\"error\":\"timeout\"}");
        final CountDownLatch latch = new CountDownLatch(1);
        App.post(() -> {
            try {
                HashMap<String,String> map = new HashMap<>();
                try {
                    for (Map.Entry<String, com.google.gson.JsonElement> en
                            : com.google.gson.JsonParser.parseString(ext).getAsJsonObject().entrySet()) {
                        map.put(en.getKey(), en.getValue().isJsonNull() ? "" : en.getValue().getAsString());
                    }
                } catch (Exception ignored) {}
                Result r = SiteApi.categoryContent(key, tid, pg, true, map);
                ref.set(r.toString());
            } catch (Throwable e) {
                ref.set("{\"error\":\"" + type(e) + ":" + msg(e) + "\"}");
            }
            latch.countDown();
        });
        latch.await(60, TimeUnit.SECONDS);
        return ref.get();
    }

    private String tSiteSearch(JsonObject a) throws Exception {
        final String key = arg(a, "site_key", "");
        final String kw = arg(a, "keyword", "");
        final String pg = arg(a, "pg", "1");
        final AtomicReference<String> ref = new AtomicReference<>("{\"error\":\"timeout\"}");
        final CountDownLatch latch = new CountDownLatch(1);
        App.post(() -> {
            try {
                Site site = VodConfig.get().getSite(key);
                Result r = SiteApi.searchContent(site, kw, false, pg);
                ref.set(r.toString());
            } catch (Throwable e) {
                ref.set("{\"error\":\"" + type(e) + ":" + msg(e) + "\"}");
            }
            latch.countDown();
        });
        latch.await(90, TimeUnit.SECONDS);
        return ref.get();
    }

    private String tSiteDetail(JsonObject a) throws Exception {
        final String key = arg(a, "key", "");
        final String id = arg(a, "id", "");
        final AtomicReference<String> ref = new AtomicReference<>("{\"error\":\"timeout\"}");
        final CountDownLatch latch = new CountDownLatch(1);
        App.post(() -> {
            try {
                Result r = SiteApi.detailContent(key, id);
                ref.set(r.toString());
            } catch (Throwable e) {
                ref.set("{\"error\":\"" + type(e) + ":" + msg(e) + "\"}");
            }
            latch.countDown();
        });
        latch.await(60, TimeUnit.SECONDS);
        return ref.get();
    }

    private String tSitePlayer(JsonObject a) throws Exception {
        final String key = arg(a, "key", "");
        final String flag = arg(a, "flag", "");
        final String id = arg(a, "id", "");
        final AtomicReference<String> ref = new AtomicReference<>("{\"error\":\"timeout\"}");
        final CountDownLatch latch = new CountDownLatch(1);
        App.post(() -> {
            try {
                Result r = SiteApi.playerContent(key, flag, id);
                ref.set(r.toString());
            } catch (Throwable e) {
                ref.set("{\"error\":\"" + type(e) + ":" + msg(e) + "\"}");
            }
            latch.countDown();
        });
        latch.await(60, TimeUnit.SECONDS);
        return ref.get();
    }

    // ---------- WebView 组 ----------

    private String tWebviewFetch(JsonObject a) throws Exception {
        final String url = arg(a, "url", "");
        final long waitMs = argInt(a, "wait_ms", 8000);
        if (TextUtils.isEmpty(url)) return "{\"error\":\"url required\"}";
        final AtomicReference<String> ref = new AtomicReference<>("{\"error\":\"timeout\"}");
        final CountDownLatch latch = new CountDownLatch(1);
        App.post(() -> {
            WebView wv = null;
            try {
                android.app.Activity act = App.activity();
                wv = new WebView(act != null ? act : App.get());
                wv.setNetworkAvailable(true);
                wv.layout(0, 0, 1080, 1920);
                WebViewUtil.configureBase(wv, "mcp-fetch");
                wv.getSettings().setUserAgentString(
                        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36");
                final WebView fv = wv;
                wv.loadUrl(url);
                long deadline = System.currentTimeMillis() + waitMs;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(500);
                    final String[] holder = new String[1];
                    final CountDownLatch inner = new CountDownLatch(1);
                    App.post(() -> {
                        try { fv.evaluateJavascript("(function(){return document.documentElement.outerHTML})()", v -> { holder[0] = v; inner.countDown(); }); }
                        catch (Throwable t) { inner.countDown(); }
                    });
                    inner.await(3, TimeUnit.SECONDS);
                    String html = holder[0];
                    if (html != null && html.length() > 20000 && !html.contains("Just a moment")) {
                        ref.set(html);
                        break;
                    }
                }
                if ("{\"error\":\"timeout\"}".equals(ref.get())) {
                    final String[] h2 = new String[1];
                    final CountDownLatch i2 = new CountDownLatch(1);
                    App.post(() -> { try { fv.evaluateJavascript("(function(){return document.documentElement.outerHTML})()", v -> { h2[0] = v; i2.countDown(); }); } catch (Throwable t) { i2.countDown(); } });
                    i2.await(3, TimeUnit.SECONDS);
                    if (h2[0] != null) ref.set(h2[0]);
                }
            } catch (Throwable e) {
                ref.set("{\"error\":\"" + type(e) + ":" + msg(e) + "\"}");
            } finally {
                try { if (wv != null) App.post(wv::destroy); } catch (Throwable ignored) {}
                latch.countDown();
            }
        });
        latch.await(waitMs + 15000, TimeUnit.MILLISECONDS);
        return ref.get();
    }

    private String tWebviewEval(JsonObject a) throws Exception {
        final String url = arg(a, "url", "");
        final String js = arg(a, "js", "document.title");
        final long waitMs = argInt(a, "wait_ms", 8000);
        final AtomicReference<String> ref = new AtomicReference<>("{\"error\":\"timeout\"}");
        final CountDownLatch latch = new CountDownLatch(1);
        App.post(() -> {
            WebView wv = null;
            try {
                android.app.Activity act = App.activity();
                wv = new WebView(act != null ? act : App.get());
                wv.setNetworkAvailable(true);
                wv.layout(0, 0, 1080, 1920);
                WebViewUtil.configureBase(wv, "mcp-eval");
                final WebView fv = wv;
                final CountDownLatch loaded = new CountDownLatch(1);
                wv.setWebViewClient(new android.webkit.WebViewClient() {
                    @Override public void onPageFinished(android.webkit.WebView v, String u) { loaded.countDown(); }
                });
                wv.loadUrl(url);
                loaded.await(waitMs, TimeUnit.MILLISECONDS);
                final String[] res = new String[1];
                final CountDownLatch inner = new CountDownLatch(1);
                App.post(() -> { try { fv.evaluateJavascript(js, v -> { res[0] = v; inner.countDown(); }); } catch (Throwable t) { res[0] = "{\"error\":\"" + t.getMessage() + "\"}"; inner.countDown(); } });
                inner.await(10, TimeUnit.SECONDS);
                ref.set(res[0] == null ? "{\"error\":\"eval timeout\"}" : res[0]);
            } catch (Throwable e) {
                ref.set("{\"error\":\"" + type(e) + ":" + msg(e) + "\"}");
            } finally {
                try { if (wv != null) App.post(wv::destroy); } catch (Throwable ignored) {}
                latch.countDown();
            }
        });
        latch.await(waitMs + 20000, TimeUnit.MILLISECONDS);
        return ref.get();
    }

    private String tWebviewCookies(JsonObject a) throws Exception {
        final String domain = arg(a, "domain", ".kpkuang.fun");
        final AtomicReference<String> ref = new AtomicReference<>("{}");
        final CountDownLatch latch = new CountDownLatch(1);
        App.post(() -> {
            try {
                CookieManager cm = CookieManager.getInstance();
                String c = cm.getCookie(domain);
                ref.set(c == null ? "{}" : "{\"cookie\":\"" + c + "\"}");
            } catch (Throwable e) {
                ref.set("{\"error\":\"" + msg(e) + "\"}");
            }
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    // ---------- 播放器组 ----------

    private String tPlayerState() throws Exception {
        final AtomicReference<String> ref = new AtomicReference<>("{}");
        final CountDownLatch latch = new CountDownLatch(1);
        App.post(() -> {
            try {
                com.fongmi.android.tv.service.PlaybackService svc =
                        com.fongmi.android.tv.server.Server.get().getService();
                if (svc == null) { ref.set("{\"state\":\"no_service\"}"); }
                else {
                    com.fongmi.android.tv.player.PlayerManager pm = svc.player();
                    JsonObject o = new JsonObject();
                    o.addProperty("released", pm.isReleased());
                    o.addProperty("playing", pm.isPlaying());
                    o.addProperty("duration", pm.getDuration());
                    o.addProperty("position", pm.getPosition());
                    o.addProperty("speed", pm.getSpeed());
                    o.addProperty("url", pm.getUrl() == null ? "" : pm.getUrl());
                    ref.set(o.toString());
                }
            } catch (Throwable e) { ref.set("{\"error\":\"" + msg(e) + "\"}"); }
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    private String tPlayerStop() throws Exception {
        final AtomicReference<String> ref = new AtomicReference<>("\"ok\"");
        final CountDownLatch latch = new CountDownLatch(1);
        App.post(() -> {
            try {
                com.fongmi.android.tv.service.PlaybackService svc =
                        com.fongmi.android.tv.server.Server.get().getService();
                if (svc != null) svc.player().stop();
            } catch (Throwable ignored) {}
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    // ---------- 诊断组 ----------

    private String tDebugLogs(JsonObject a) throws Exception {
        int lines = argInt(a, "lines", 100);
        try {
            java.net.URL u = new java.net.URL("http://127.0.0.1:" + com.github.catvod.Proxy.getPort()
                    + "/debug/logs?raw=1&lines=" + lines);
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(10000);
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            int n = 0;
            while ((line = br.readLine()) != null && n++ < lines) sb.append(line).append('\n');
            br.close();
            return sb.toString();
        } catch (Throwable e) {
            return "{\"error\":\"" + msg(e) + "\"}";
        }
    }

    private String tConfigSites() throws Exception {
        final AtomicReference<String> ref = new AtomicReference<>("[]");
        final CountDownLatch latch = new CountDownLatch(1);
        App.post(() -> {
            try {
                JsonArray arr = new JsonArray();
                for (Site s : VodConfig.get().getSites()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("key", s.getKey());
                    o.addProperty("name", s.getName());
                    o.addProperty("type", s.getType());
                    arr.add(o);
                }
                ref.set(arr.toString());
            } catch (Throwable e) { ref.set("[{\"error\":\"" + msg(e) + "\"}]"); }
            latch.countDown();
        });
        latch.await(10, TimeUnit.SECONDS);
        return ref.get();
    }
}
