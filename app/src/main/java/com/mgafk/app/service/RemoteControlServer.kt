package com.mgafk.app.service

import android.content.Context
import android.net.wifi.WifiManager
import com.mgafk.app.data.AppLog
import com.mgafk.app.ui.MainViewModel
import com.mgafk.app.ui.UiState
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*

import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.NetworkInterface

import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * HTTP + WebSocket server nhúng trong app để điều khiển từ xa.
 * Dùng Ktor (Netty engine) chạy trên port 8080.
 *
 * Endpoints:
 *   GET  /api/status          — trạng thái bot hiện tại
 *   POST /api/connect         — kết nối session (body: {"sessionId":"..."})
 *   POST /api/disconnect      — ngắt kết nối session
 *   GET  /api/watchlist       — xem watchlist
 *   POST /api/watchlist/add   — thêm item (body: {"shopType":"seed","itemId":"Carrot"})
 *   POST /api/watchlist/remove — xoá item
 *   WS   /ws/events           — stream log realtime
 *   GET  /                    — Web UI (HTML)
 */
class RemoteControlServer(
    private val context: Context,
    private val viewModel: MainViewModel,
) {
    private val TAG = "RemoteControlServer"
    private val PORT = 8080

    private var server: ApplicationEngine? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wsClients = CopyOnWriteArrayList<DefaultWebSocketSession>()

    fun start() {
        if (server != null) return
        AppLog.d(TAG, "Starting remote control server on port $PORT")

        server = embeddedServer(Netty, port = PORT, host = "0.0.0.0") {
            install(WebSockets) {
            }
            routing {
                // ── Web UI ──
                get("/") { call.respondText(WEB_UI_HTML, ContentType.Text.Html) }

                // ── API ──
                get("/api/status") {
                    val state = viewModel.state.value
                    val session = state.activeSession
                    call.respondText(
                        buildJsonObject {
                            put("connected", session.status.name)
                            put("sessionName", session.name)
                            put("sessionId", session.id)
                            put("weather", session.weather)
                            put("watchlistCount", state.watchlist.size)
                            putJsonArray("sessions") {
                                state.sessions.forEach { s ->
                                    add(buildJsonObject {
                                        put("id", s.id)
                                        put("name", s.name)
                                        put("status", s.status.name)
                                        put("weather", s.weather)
                                    })
                                }
                            }
                            putJsonArray("watchlist") {
                                state.watchlist.forEach { w ->
                                    add(buildJsonObject {
                                        put("shopType", w.shopType)
                                        put("itemId", w.itemId)
                                    })
                                }
                            }
                        }.toString(),
                        ContentType.Application.Json
                    )
                }

                post("/api/connect") {
                    val body = call.receiveText()
                    val sessionId = Json.parseToJsonElement(body)
                        .let { it as? kotlinx.serialization.json.JsonObject }
                        ?.get("sessionId")?.let {
                            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                        } ?: viewModel.state.value.activeSessionId
                    viewModel.connect(sessionId)
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                }

                post("/api/disconnect") {
                    val body = call.receiveText()
                    val sessionId = runCatching {
                        Json.parseToJsonElement(body)
                            .let { it as? kotlinx.serialization.json.JsonObject }
                            ?.get("sessionId")?.let {
                                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                            }
                    }.getOrNull() ?: viewModel.state.value.activeSessionId
                    viewModel.disconnect(sessionId)
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                }

                get("/api/watchlist") {
                    val watchlist = viewModel.state.value.watchlist
                    val json = buildJsonObject {
                        putJsonArray("items") {
                            watchlist.forEach { w ->
                                add(buildJsonObject {
                                    put("shopType", w.shopType)
                                    put("itemId", w.itemId)
                                })
                            }
                        }
                    }
                    call.respondText(json.toString(), ContentType.Application.Json)
                }

                post("/api/watchlist/add") {
                    val body = call.receiveText()
                    val obj = Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
                    val shopType = (obj?.get("shopType") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    val itemId = (obj?.get("itemId") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    if (shopType != null && itemId != null) {
                        viewModel.addWatchlistItem(shopType, itemId)
                        call.respondText("""{"ok":true}""", ContentType.Application.Json)
                    } else {
                        call.respond(HttpStatusCode.BadRequest, """{"error":"missing shopType or itemId"}""")
                    }
                }

                post("/api/watchlist/remove") {
                    val body = call.receiveText()
                    val obj = Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
                    val shopType = (obj?.get("shopType") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    val itemId = (obj?.get("itemId") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    if (shopType != null && itemId != null) {
                        viewModel.removeWatchlistItem(shopType, itemId)
                        call.respondText("""{"ok":true}""", ContentType.Application.Json)
                    } else {
                        call.respond(HttpStatusCode.BadRequest, """{"error":"missing shopType or itemId"}""")
                    }
                }

                // ── WebSocket: stream state updates ──
                webSocket("/ws/events") {
                    wsClients.add(this)
                    AppLog.d(TAG, "WS client connected (total=${wsClients.size})")
                    try {
                        // Gửi state hiện tại ngay khi kết nối
                        send(Frame.Text(stateToJson(viewModel.state.value)))
                        // Giữ connection sống — updates được push từ broadcastState()
                        for (frame in incoming) { /* ignore client messages */ }
                    } finally {
                        wsClients.remove(this)
                        AppLog.d(TAG, "WS client disconnected (total=${wsClients.size})")
                    }
                }
            }
        }.start(wait = false)

        // Broadcast state mỗi khi có thay đổi
        scope.launch {
            viewModel.state.collectLatest { state ->
                val json = stateToJson(state)
                wsClients.forEach { ws ->
                    runCatching { ws.send(Frame.Text(json)) }
                }
            }
        }

        AppLog.d(TAG, "Remote control server started. Local IP: ${getLocalIp()}")
    }

    fun stop() {
        server?.stop(500L, 1000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        server = null
        scope.cancel()
        AppLog.d(TAG, "Remote control server stopped")
    }

    fun getLocalIp(): String {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                val addrs = Collections.list(iface.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
            "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun stateToJson(state: UiState): String {
        val session = state.activeSession
        return buildJsonObject {
            put("type", "state")
            put("connected", session.status.name)
            put("sessionName", session.name)
            put("sessionId", session.id)
            put("weather", session.weather)
            put("watchlistCount", state.watchlist.size)
            putJsonArray("sessions") {
                state.sessions.forEach { s ->
                    add(buildJsonObject {
                        put("id", s.id)
                        put("name", s.name)
                        put("status", s.status.name)
                        put("weather", s.weather)
                    })
                }
            }
            putJsonArray("watchlist") {
                state.watchlist.forEach { w ->
                    add(buildJsonObject {
                        put("shopType", w.shopType)
                        put("itemId", w.itemId)
                    })
                }
            }
        }.toString()
    }

    companion object {
        // Web UI HTML nhúng thẳng vào server — không cần file assets
        val WEB_UI_HTML = """
<!DOCTYPE html>
<html lang="vi">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>MG AFK Remote</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
         background: #0f0f14; color: #e0e0e8; min-height: 100vh; padding: 16px; }
  h1 { font-size: 1.4rem; font-weight: 700; color: #7c9cff; margin-bottom: 16px;
       display: flex; align-items: center; gap: 8px; }
  .dot { width: 10px; height: 10px; border-radius: 50%; background: #555; display: inline-block; }
  .dot.connected { background: #4ade80; box-shadow: 0 0 8px #4ade80; }
  .dot.connecting { background: #facc15; animation: pulse 1s infinite; }
  .card { background: #1a1a24; border: 1px solid #2a2a38; border-radius: 12px;
          padding: 16px; margin-bottom: 12px; }
  .card h2 { font-size: 0.85rem; text-transform: uppercase; letter-spacing: 0.1em;
              color: #888; margin-bottom: 12px; }
  .status-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
  .badge { padding: 3px 10px; border-radius: 20px; font-size: 0.78rem; font-weight: 600; }
  .badge.CONNECTED { background: #16301f; color: #4ade80; }
  .badge.DISCONNECTED { background: #2a1a1a; color: #f87171; }
  .badge.CONNECTING { background: #2a2010; color: #facc15; }
  .session-name { font-weight: 600; font-size: 1rem; }
  .weather { font-size: 0.85rem; color: #aaa; }
  .btn { border: none; border-radius: 8px; padding: 10px 18px; font-size: 0.9rem;
         font-weight: 600; cursor: pointer; transition: opacity 0.15s; }
  .btn:hover { opacity: 0.85; }
  .btn:active { opacity: 0.7; }
  .btn-green { background: #16a34a; color: #fff; }
  .btn-red { background: #dc2626; color: #fff; }
  .btn-blue { background: #2563eb; color: #fff; }
  .btn-sm { padding: 6px 12px; font-size: 0.8rem; }
  .btn-row { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 12px; }
  .session-list { display: flex; flex-direction: column; gap: 8px; }
  .session-item { background: #12121c; border: 1px solid #2a2a38; border-radius: 8px;
                  padding: 10px 14px; display: flex; align-items: center; justify-content: space-between; }
  .session-item.active { border-color: #7c9cff44; background: #16183a; }
  .watchlist-item { display: flex; align-items: center; justify-content: space-between;
                    padding: 8px 0; border-bottom: 1px solid #2a2a38; }
  .watchlist-item:last-child { border-bottom: none; }
  .tag { font-size: 0.72rem; background: #2a2a38; border-radius: 4px; padding: 2px 6px; color: #aaa; }
  .add-row { display: flex; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
  input, select { background: #12121c; border: 1px solid #2a2a38; border-radius: 8px;
                  color: #e0e0e8; padding: 8px 12px; font-size: 0.85rem; outline: none; }
  input:focus, select:focus { border-color: #7c9cff; }
  .log-box { background: #0a0a10; border-radius: 8px; padding: 10px; font-family: monospace;
              font-size: 0.78rem; color: #7cffb4; max-height: 160px; overflow-y: auto; }
  .log-line { margin-bottom: 2px; }
  .ws-status { font-size: 0.72rem; color: #666; text-align: right; margin-top: 4px; }
  @keyframes pulse { 0%,100% { opacity:1; } 50% { opacity:0.4; } }
</style>
</head>
<body>
<h1>🌿 MG AFK Remote <span class="dot" id="wsDot"></span></h1>

<div class="card" id="statusCard">
  <h2>Trạng thái</h2>
  <div class="status-row">
    <span class="badge DISCONNECTED" id="statusBadge">--</span>
    <span class="session-name" id="sessionName">--</span>
  </div>
  <div class="weather" id="weatherText">🌤 Weather: --</div>
  <div class="btn-row">
    <button class="btn btn-green" onclick="doConnect()">▶ Connect</button>
    <button class="btn btn-red" onclick="doDisconnect()">⏹ Disconnect</button>
  </div>
</div>

<div class="card">
  <h2>Sessions</h2>
  <div class="session-list" id="sessionList"></div>
</div>

<div class="card">
  <h2>Watchlist</h2>
  <div id="watchlistItems"></div>
  <div class="add-row">
    <select id="shopType">
      <option value="seed">Seed</option>
      <option value="tool">Tool</option>
      <option value="egg">Egg</option>
    </select>
    <input id="itemId" placeholder="VD: Carrot, WateringCan" style="flex:1;min-width:120px">
    <button class="btn btn-blue btn-sm" onclick="addWatchlist()">+ Thêm</button>
  </div>
</div>

<div class="card">
  <h2>Log realtime</h2>
  <div class="log-box" id="logBox"></div>
  <div class="ws-status" id="wsStatus">Đang kết nối...</div>
</div>

<script>
let ws = null;
let currentSessionId = '';
let reconnectTimer = null;

function connectWs() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  ws = new WebSocket(proto + '//' + location.host + '/ws/events');

  ws.onopen = () => {
    document.getElementById('wsDot').className = 'dot connected';
    document.getElementById('wsStatus').textContent = '🟢 WebSocket kết nối';
    addLog('WebSocket connected');
  };

  ws.onmessage = (e) => {
    try {
      const data = JSON.parse(e.data);
      if (data.type === 'state') updateUI(data);
    } catch {}
  };

  ws.onclose = () => {
    document.getElementById('wsDot').className = 'dot';
    document.getElementById('wsStatus').textContent = '🔴 Mất kết nối — đang thử lại...';
    addLog('WebSocket disconnected, retrying...');
    clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(connectWs, 3000);
  };

  ws.onerror = () => ws.close();
}

function updateUI(data) {
  // Status
  const badge = document.getElementById('statusBadge');
  badge.textContent = data.connected;
  badge.className = 'badge ' + data.connected;
  document.getElementById('sessionName').textContent = data.sessionName || '--';
  document.getElementById('weatherText').textContent = '🌤 Weather: ' + (data.weather || '--');
  currentSessionId = data.sessionId;

  // Sessions
  const list = document.getElementById('sessionList');
  list.innerHTML = '';
  (data.sessions || []).forEach(s => {
    const div = document.createElement('div');
    div.className = 'session-item' + (s.id === data.sessionId ? ' active' : '');
    div.innerHTML = '<div><strong>' + esc(s.name || s.id) + '</strong><br>'
      + '<span class="tag">' + s.status + '</span>'
      + ' <span style="color:#aaa;font-size:0.78rem">' + esc(s.weather || '') + '</span></div>'
      + '<div style="display:flex;gap:6px">'
      + '<button class="btn btn-green btn-sm" onclick="doConnect(\'' + s.id + '\')">▶</button>'
      + '<button class="btn btn-red btn-sm" onclick="doDisconnect(\'' + s.id + '\')">⏹</button>'
      + '</div>';
    list.appendChild(div);
  });

  // Watchlist
  const wl = document.getElementById('watchlistItems');
  wl.innerHTML = '';
  if (!data.watchlist || data.watchlist.length === 0) {
    wl.innerHTML = '<p style="color:#555;font-size:0.85rem">Chưa có item nào</p>';
  } else {
    data.watchlist.forEach(w => {
      const div = document.createElement('div');
      div.className = 'watchlist-item';
      div.innerHTML = '<div><span class="tag">' + esc(w.shopType) + '</span>'
        + ' <strong>' + esc(w.itemId) + '</strong></div>'
        + '<button class="btn btn-red btn-sm" onclick="removeWatchlist(\'' + esc(w.shopType) + '\',\'' + esc(w.itemId) + '\')">✕</button>';
      wl.appendChild(div);
    });
  }
}

async function doConnect(id) {
  const sessionId = id || currentSessionId;
  addLog('Connecting ' + sessionId + '...');
  await fetch('/api/connect', {
    method: 'POST',
    headers: {'Content-Type':'application/json'},
    body: JSON.stringify({ sessionId })
  });
}

async function doDisconnect(id) {
  const sessionId = id || currentSessionId;
  addLog('Disconnecting ' + sessionId + '...');
  await fetch('/api/disconnect', {
    method: 'POST',
    headers: {'Content-Type':'application/json'},
    body: JSON.stringify({ sessionId })
  });
}

async function addWatchlist() {
  const shopType = document.getElementById('shopType').value;
  const itemId = document.getElementById('itemId').value.trim();
  if (!itemId) return;
  addLog('Adding watchlist: ' + shopType + '/' + itemId);
  await fetch('/api/watchlist/add', {
    method: 'POST',
    headers: {'Content-Type':'application/json'},
    body: JSON.stringify({ shopType, itemId })
  });
  document.getElementById('itemId').value = '';
}

async function removeWatchlist(shopType, itemId) {
  addLog('Removing watchlist: ' + shopType + '/' + itemId);
  await fetch('/api/watchlist/remove', {
    method: 'POST',
    headers: {'Content-Type':'application/json'},
    body: JSON.stringify({ shopType, itemId })
  });
}

function addLog(msg) {
  const box = document.getElementById('logBox');
  const line = document.createElement('div');
  line.className = 'log-line';
  line.textContent = new Date().toLocaleTimeString() + ' › ' + msg;
  box.appendChild(line);
  box.scrollTop = box.scrollHeight;
  if (box.children.length > 200) box.removeChild(box.firstChild);
}

function esc(s) {
  return String(s || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

connectWs();
</script>
</body>
</html>
        """.trimIndent()
    }
}
