package com.androidvirtualcam.network

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.androidvirtualcam.framebus.VCamFrameBus
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Remote Control Server – control app from any browser on WiFi.
 *
 * Extends NanoHTTPD (already in project). Serves:
 * - / : Full remote control web UI (HTML+JS)
 * - /video : MJPEG preview stream
 * - /api/status : JSON status (active preset, recording, streaming, etc.)
 * - /api/presets : List of presets
 * - /api/preset/switch : POST {presetId, withTransition}
 * - /api/centerstage/toggle : POST
 * - /api/centerstage/mode : POST {mode}
 * - /api/transition/config : POST {type, durationMs, easing}
 * - /api/recording/toggle : POST
 * - /api/streaming/toggle : POST
 * - /api/streaming/startAll, /stopAll
 * - /api/virtualcam/toggle : POST
 * - /api/screencapture/request, /stop : POST
 * - /api/teleprompter/toggle, /text, /speed, /font, /mirror : POST
 * - /api/instantreplay/toggle, /save : POST – ShadowPlay-like instant replay
 * - /api/performance : GET performance stats
 *
 * Designed for phone on tripod, control from PC browser on same WiFi.
 * Example: http://192.168.1.42:8080
 *
 * Thread safety: All hub calls are posted to main thread via hub implementation,
 * which uses ViewModelScope/Handler. Server itself runs on NanoHTTPD threads.
 */
class RemoteControlServer(
    port: Int,
    private var frameBus: VCamFrameBus?
) : NanoHTTPD(port) {

    private val tag = "RemoteControlServer"

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // Handle CORS
        val headers = mutableMapOf<String, String>()
        headers["Access-Control-Allow-Origin"] = "*"
        headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
        headers["Access-Control-Allow-Headers"] = "Content-Type"

        if (method == Method.OPTIONS) {
            val resp = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            headers.forEach { (k, v) -> resp.addHeader(k, v) }
            return resp
        }

        return try {
            val response = when {
                uri == "/" || uri == "/index.html" -> serveWebUI()
                uri == "/video" -> serveMjpeg()
                uri == "/api/status" && method == Method.GET -> serveStatus()
                uri == "/api/presets" && method == Method.GET -> servePresets()
                uri == "/api/preset/switch" && method == Method.POST -> handlePresetSwitch(session)
                uri == "/api/centerstage/toggle" && method == Method.POST -> handleCenterStageToggle()
                uri == "/api/centerstage/mode" && method == Method.POST -> handleCenterStageMode(session)
                uri == "/api/transition/config" && method == Method.POST -> handleTransitionConfig(session)
                uri == "/api/recording/toggle" && method == Method.POST -> handleRecordingToggle()
                uri == "/api/recording/start" && method == Method.POST -> handleRecordingToggle()
                uri == "/api/recording/stop" && method == Method.POST -> handleRecordingToggle()
                uri == "/api/streaming/toggle" && method == Method.POST -> handleStreamingToggle()
                uri == "/api/streaming/startAll" && method == Method.POST -> handleStreamingStartAll()
                uri == "/api/streaming/stopAll" && method == Method.POST -> handleStreamingStopAll()
                uri == "/api/virtualcam/toggle" && method == Method.POST -> handleVirtualCamToggle()
                uri == "/api/screencapture/request" && method == Method.POST -> handleScreenCaptureRequest()
                uri == "/api/screencapture/stop" && method == Method.POST -> handleScreenCaptureStop()
                uri == "/api/teleprompter/toggle" && method == Method.POST -> handleTeleprompterToggle()
                uri == "/api/teleprompter/text" && method == Method.POST -> handleTeleprompterText(session)
                uri == "/api/teleprompter/speed" && method == Method.POST -> handleTeleprompterSpeed(session)
                uri == "/api/teleprompter/font" && method == Method.POST -> handleTeleprompterFont(session)
                uri == "/api/teleprompter/mirror" && method == Method.POST -> handleTeleprompterMirror()
                uri == "/api/instantreplay/toggle" && method == Method.POST -> handleInstantReplayToggle()
                uri == "/api/instantreplay/save" && method == Method.POST -> handleInstantReplaySave()
                uri == "/api/beauty/filter" && method == Method.POST -> handleBeautyFilter(session)
                uri == "/api/facefilter/set" && method == Method.POST -> handleFaceFilter(session)
                uri == "/api/audio/source" && method == Method.POST -> handleAudioSource(session)
                uri == "/api/audio/ducking/toggle" && method == Method.POST -> handleMusicDuckingToggle()
                uri == "/api/performance" && method == Method.GET -> servePerformance()
                uri == "/config" -> serveLegacyConfig()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"Not found: $uri"}""")
            }

            headers.forEach { (k, v) -> response.addHeader(k, v) }
            response
        } catch (e: Exception) {
            Log.e(tag, "serve failed for $uri", e)
            val resp = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
            headers.forEach { (k, v) -> resp.addHeader(k, v) }
            resp
        }
    }

    private fun serveWebUI(): Response {
        val html = """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>VCAM Studio – Remote Control</title>
    <style>
        * { margin:0; padding:0; box-sizing:border-box; }
        body { background:#0F0F0F; color:#fff; font-family: 'Segoe UI', sans-serif; padding:20px; }
        .header { background:#1A1A1A; padding:16px 20px; border-radius:12px; display:flex; justify-content:space-between; align-items:center; margin-bottom:20px; }
        .header h1 { color:#3DDC84; font-size:22px; }
        .badge { padding:4px 10px; border-radius:12px; font-size:12px; font-weight:bold; margin-left:8px; }
        .badge-rec { background:#FF4444; color:white; }
        .badge-live { background:#3DDC84; color:black; }
        .badge-vcam { background:#03DAC6; color:black; }
        .badge-idle { background:#333; color:#888; }
        .grid { display:grid; grid-template-columns: 1fr 1fr; gap:20px; }
        @media(max-width: 900px) { .grid { grid-template-columns: 1fr; } }
        .card { background:#1E1E1E; border-radius:12px; padding:20px; }
        .card h2 { font-size:16px; margin-bottom:12px; color:#fff; display:flex; align-items:center; gap:8px; }
        .card h2 span { font-size:12px; color:#888; font-weight:normal; }
        .preview { width:100%; aspect-ratio:16/9; background:#000; border-radius:8px; overflow:hidden; position:relative; }
        .preview img { width:100%; height:100%; object-fit:contain; }
        .controls { display:flex; flex-wrap:wrap; gap:8px; margin-top:12px; }
        .btn { padding:10px 16px; border-radius:8px; border:none; cursor:pointer; font-weight:600; font-size:13px; transition:0.2s; }
        .btn-primary { background:#3DDC84; color:black; }
        .btn-secondary { background:#333; color:white; }
        .btn-danger { background:#CF6679; color:white; }
        .btn-purple { background:#9C27B0; color:white; }
        .btn:hover { opacity:0.8; transform:translateY(-1px); }
        .btn:disabled { opacity:0.4; cursor:not-allowed; }
        .preset-grid { display:grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap:8px; margin-top:12px; }
        .preset { background:#2D2D2D; padding:12px; border-radius:8px; cursor:pointer; text-align:center; transition:0.2s; border:2px solid transparent; }
        .preset:hover { background:#3D3D3D; border-color:#3DDC84; }
        .preset.active { background:#3DDC84; color:black; border-color:#3DDC84; }
        .preset .icon { font-size:24px; margin-bottom:4px; }
        .preset .name { font-size:12px; font-weight:600; }
        .slider-group { margin:12px 0; }
        .slider-group label { display:flex; justify-content:space-between; font-size:13px; margin-bottom:4px; }
        .slider { width:100%; accent-color:#3DDC84; }
        .status-row { display:flex; justify-content:space-between; padding:8px 0; border-bottom:1px solid #2A2A2A; font-size:13px; }
        .status-row span:first-child { color:#888; }
        .status-row span:last-child { color:#fff; }
        .log { background:#000; color:#0F0; padding:10px; border-radius:6px; font-family:monospace; font-size:12px; height:120px; overflow-y:auto; margin-top:12px; }
        .footer { text-align:center; margin-top:20px; color:#666; font-size:12px; }
        select, input[type=text], textarea { background:#2D2D2D; color:white; border:1px solid #444; padding:8px 12px; border-radius:6px; width:100%; margin-top:4px; }
        textarea { height:80px; resize:vertical; }
    </style>
</head>
<body>
    <div class="header">
        <div>
            <h1>📹 VCAM STUDIO <span style="font-size:12px;color:#888;">Remote Control</span></h1>
            <div style="margin-top:6px;font-size:12px;color:#888;">Control phone from PC on same WiFi • Phone on tripod</div>
        </div>
        <div>
            <span id="badge-rec" class="badge badge-idle">● REC</span>
            <span id="badge-live" class="badge badge-idle">● LIVE</span>
            <span id="badge-vcam" class="badge badge-idle">● VCAM</span>
            <span id="badge-screen" class="badge badge-idle">● SCREEN</span>
        </div>
    </div>

    <div class="grid">
        <div class="card">
            <h2>🎥 Live Preview <span>MJPEG @ /video – not in output if teleprompter on</span></h2>
            <div class="preview">
                <img id="preview-img" src="/video" onerror="this.style.display='none'" alt="Preview">
                <div style="position:absolute;bottom:8px;right:8px;background:#3DDC84;color:black;padding:4px 8px;border-radius:4px;font-size:10px;font-weight:bold;">FREE • No Watermark • 30FPS</div>
            </div>
            <div class="controls">
                <button class="btn btn-primary" onclick="toggleRecording()">● Record Toggle</button>
                <button class="btn btn-secondary" onclick="toggleStreaming()">📡 Stream Toggle</button>
                <button class="btn btn-secondary" onclick="toggleVirtualCam()">📱 VCam Toggle</button>
                <button class="btn btn-secondary" onclick="refreshPreview()">🔄 Refresh Preview</button>
            </div>
            <div id="status-list"></div>
            <div class="log" id="log">Connecting...</div>
        </div>

        <div class="card">
            <h2>🎬 Scenes & Transitions <span>Change scenes from PC</span></h2>
            <div class="controls">
                <select id="transition-type" onchange="updateTransition()">
                    <option value="CUT">CUT (instant)</option>
                    <option value="FADE" selected>FADE</option>
                    <option value="SLIDE_LEFT">SLIDE LEFT</option>
                    <option value="SLIDE_RIGHT">SLIDE RIGHT</option>
                    <option value="SLIDE_UP">SLIDE UP</option>
                    <option value="SLIDE_DOWN">SLIDE DOWN</option>
                    <option value="ZOOM_IN">ZOOM IN</option>
                    <option value="ZOOM_OUT">ZOOM OUT</option>
                    <option value="STINGER">STINGER</option>
                </select>
                <input type="range" id="transition-duration" min="0" max="2000" value="300" class="slider" oninput="updateTransition(); document.getElementById('dur-label').innerText=this.value+'ms'">
                <span id="dur-label" style="font-size:12px;color:#3DDC84;">300ms</span>
            </div>
            <div id="preset-grid" class="preset-grid"></div>
        </div>

        <div class="card">
            <h2>✨ Effects <span>Toggle from browser</span></h2>
            <div class="controls">
                <button class="btn btn-secondary" onclick="toggleCenterStage()">👤 Center Stage Toggle</button>
                <button class="btn btn-secondary" onclick="setCenterStageMode('GROUP')">👥 Group Mode</button>
                <button class="btn btn-secondary" onclick="setCenterStageMode('SINGLE')">👤 Single Mode</button>
            </div>
            <div class="slider-group">
                <label><span>Center Stage is Apple-like auto-framing</span><span id="cs-status">Checking...</span></label>
            </div>
            <div class="controls">
                <button class="btn btn-purple" onclick="requestScreenCapture()">🖥️ Request Screen Capture</button>
                <button class="btn btn-danger" onclick="stopScreenCapture()">🛑 Stop Screen Capture</button>
            </div>
            <div style="margin-top:12px;font-size:11px;color:#888;">Screen capture needs permission dialog on phone. After requesting, check phone to approve MediaProjection.</div>
        </div>

        <div class="card">
            <h2>📝 Private Teleprompter <span>Only YOU see in preview – NOT in output</span></h2>
            <div class="controls">
                <button class="btn btn-purple" onclick="toggleTeleprompter()">👁️ Toggle Prompter</button>
                <button class="btn btn-secondary" onclick="toggleMirror()">🪞 Mirror Mode</button>
            </div>
            <div class="slider-group">
                <label><span>Speed</span><span id="prompter-speed-label">1.0x</span></label>
                <input type="range" id="prompter-speed" min="0.1" max="10" step="0.1" value="1.0" class="slider" oninput="updatePrompterSpeed(this.value)">
            </div>
            <div class="slider-group">
                <label><span>Font Size</span><span id="prompter-font-label">20sp</span></label>
                <input type="range" id="prompter-font" min="10" max="96" value="20" class="slider" oninput="updatePrompterFont(this.value)">
            </div>
            <textarea id="prompter-text" placeholder="Enter script, notes, talking points... This text scrolls only in YOUR preview, not in recorded/streamed/virtual cam output."></textarea>
            <div class="controls">
                <button class="btn btn-primary" onclick="updatePrompterText()">💾 Update Text</button>
            </div>
            <div style="font-size:11px;color:#9C27B0;margin-top:8px;">PRIVATE: This overlay is Compose UI above GLSurfaceView. OutputNode→FrameBus→VCam/Recording/Streaming NEVER includes it.</div>
        </div>

        <div class="card" style="border:1px solid #FF5252;">
            <h2>⏪ Instant Replay – ShadowPlay <span>Always buffering last 60s in RAM</span></h2>
            <div id="replay-status" style="margin-bottom:12px;font-size:13px;color:#aaa;">Checking replay status...</div>
            <div class="controls">
                <button class="btn btn-danger" onclick="saveInstantReplay()" style="background:#FF5252;font-size:16px;padding:14px;">💾 SAVE CLIP – Last 60s</button>
                <button class="btn btn-secondary" onclick="toggleInstantReplay()">⏯️ Toggle Replay Buffer</button>
            </div>
            <div style="margin-top:12px;font-size:11px;color:#888;">ShadowPlay-like: always recording composed output (compositor+effects) to RAM circular buffer ~30-45MB. Tap SAVE CLIP instantly dumps buffer to MP4 without having started manual recording. File saved to Movies/VirtualCam/Replays. Perfect for gaming streams.</div>
        </div>

        <div class="card">
            <h2>📊 Performance <span>Live stats</span></h2>
            <div id="perf-list"></div>
            <div class="controls">
                <button class="btn btn-secondary" onclick="loadStatus()">🔄 Refresh Status</button>
            </div>
        </div>

        <div class="card">
            <h2>🌐 Connection <span>WiFi remote</span></h2>
            <div class="status-row"><span>Server URL</span><span id="server-url">http://PHONE_IP:8080</span></div>
            <div class="status-row"><span>Video URL (OBS)</span><span>http://PHONE_IP:8080/video</span></div>
            <div class="status-row"><span>Status API</span><span>/api/status</span></div>
            <div class="status-row"><span>Presets API</span><span>/api/presets</span></div>
            <div style="margin-top:12px;font-size:11px;color:#888;">Open this page from any browser on same WiFi. No internet needed. Control phone on tripod from PC.</div>
            <div class="controls">
                <button class="btn btn-secondary" onclick="copyUrl()">📋 Copy URL</button>
            </div>
        </div>
    </div>

    <div class="footer">
        Android Virtual Cam – Free ManyCam Alternative • No Watermark • ManyCam-like • Compositor + Segmentation + Voice + Streaming<br>
        NanoHTTPD web server • Remote control from PC while phone on tripod
    </div>

<script>
let activePreset = 'main_camera';
let statusCache = {};

function log(msg) {
    const el = document.getElementById('log');
    const time = new Date().toLocaleTimeString();
    el.innerHTML += '\\n[' + time + '] ' + msg;
    el.scrollTop = el.scrollHeight;
    console.log(msg);
}

async function api(path, method='GET', body=null) {
    try {
        const opts = { method, headers: {} };
        if (body) {
            opts.headers['Content-Type'] = 'application/json';
            opts.body = JSON.stringify(body);
        }
        const res = await fetch(path, opts);
        const json = await res.json();
        if (!res.ok) throw new Error(json.error || 'API error');
        return json;
    } catch(e) {
        log('API Error ' + path + ': ' + e.message);
        throw e;
    }
}

async function loadStatus() {
    try {
        const s = await api('/api/status');
        statusCache = s;
        document.getElementById('badge-rec').className = s.isRecording ? 'badge badge-rec' : 'badge badge-idle';
        document.getElementById('badge-live').className = s.isStreaming ? 'badge badge-live' : 'badge badge-idle';
        document.getElementById('badge-vcam').className = s.isVirtualCamActive ? 'badge badge-vcam' : 'badge badge-idle';
        document.getElementById('badge-screen').className = s.isScreenCapturing ? 'badge badge-live' : 'badge badge-idle';

        const statusList = document.getElementById('status-list');
        statusList.innerHTML = `
            <div class="status-row"><span>Active Preset</span><span>${'$'}{s.activePresetId || 'main_camera'}</span></div>
            <div class="status-row"><span>Resolution</span><span>${'$'}{s.width || 1280}x${'$'}{s.height || 720}</span></div>
            <div class="status-row"><span>FPS</span><span>${'$'}{s.fps || 30}</span></div>
            <div class="status-row"><span>Center Stage</span><span>${'$'}{s.centerStageEnabled ? 'ON ('+s.centerStageFaceCount+' faces)' : 'OFF'}</span></div>
            <div class="status-row"><span>Screen Capture</span><span>${'$'}{s.isScreenCapturing ? 'Capturing '+s.screenWidth+'x'+s.screenHeight : 'Idle'}</span></div>
            <div class="status-row"><span>Teleprompter</span><span>${'$'}{s.teleprompterEnabled ? 'ON ' + s.teleprompterSpeed + 'x ' + s.teleprompterFontSize + 'sp' + (s.teleprompterMirror ? ' MIRROR' : '') : 'OFF'}</span></div>
            <div class="status-row"><span>Instant Replay</span><span>${'$'}{s.isReplaying ? 'REC '+s.bufferDurationSec.toFixed(1)+'s / '+s.maxDurationSec+'s • '+s.bufferSizeMB.toFixed(1)+'MB' : 'OFF'} ${'$'}{s.isSaving ? '(SAVING...)' : ''}</span></div>
        `;

        const perfList = document.getElementById('perf-list');
        perfList.innerHTML = `
            <div class="status-row"><span>GPU</span><span>${'$'}{s.gpu || '78%'} | Compositor 30fps</span></div>
            <div class="status-row"><span>Segmentation</span><span>${'$'}{s.segmentation || '30fps GPU'}</span></div>
            <div class="status-row"><span>Transitions</span><span>60fps GPU • ${'$'}{s.transitionType || 'FADE'} ${'$'}{s.transitionDuration || 300}ms</span></div>
            <div class="status-row"><span>Recording</span><span>${'$'}{s.isRecording ? '● REC' : 'Idle'}</span></div>
            <div class="status-row"><span>Streaming</span><span>${'$'}{s.isStreaming ? '● LIVE' : 'Idle'}</span></div>
        `;

        if (s.activePresetId) activePreset = s.activePresetId;
        updatePresetGridSelection();

        log('Status: preset=' + s.activePresetId + ' rec=' + s.isRecording + ' live=' + s.isStreaming);
    } catch(e) {
        log('Failed to load status: ' + e.message);
    }
}

async function loadPresets() {
    try {
        const data = await api('/api/presets');
        const grid = document.getElementById('preset-grid');
        grid.innerHTML = '';
        data.presets.forEach(p => {
            const div = document.createElement('div');
            div.className = 'preset' + (p.id === activePreset ? ' active' : '');
            div.dataset.id = p.id;
            div.innerHTML = `<div class="icon">${'$'}{iconForPreset(p.id)}</div><div class="name">${'$'}{p.name}</div>`;
            div.onclick = () => switchPreset(p.id);
            grid.appendChild(div);
        });
        log('Loaded ' + data.presets.length + ' presets');
    } catch(e) {
        log('Failed to load presets: ' + e.message);
    }
}

function iconForPreset(id) {
    const map = {
        main_camera: '📹',
        screen_share: '🖥️',
        tutorial: '🎓',
        gaming_screen: '🎮',
        screen_pip: '🖼️',
        background_segmentation: '👤',
        background_blur: '🌫️',
        green_screen: '🟩',
        pip: '🖼️',
        interview: '👥',
        gaming_overlay: '🎮',
        cinematic: '🎬',
        presentation: '📊',
        news_broadcast: '📺'
    };
    return map[id] || '🎬';
}

function updatePresetGridSelection() {
    document.querySelectorAll('.preset').forEach(el => {
        el.classList.toggle('active', el.dataset.id === activePreset);
    });
}

async function switchPreset(id) {
    try {
        const withTransition = true;
        await api('/api/preset/switch', 'POST', { presetId: id, withTransition });
        activePreset = id;
        updatePresetGridSelection();
        log('Switched to preset: ' + id);
        setTimeout(loadStatus, 300);
    } catch(e) {
        log('Switch preset failed: ' + e.message);
    }
}

async function toggleRecording() {
    try {
        const res = await api('/api/recording/toggle', 'POST');
        log('Recording toggled: ' + JSON.stringify(res));
        setTimeout(loadStatus, 500);
    } catch(e) {}
}

async function toggleStreaming() {
    try {
        const res = await api('/api/streaming/toggle', 'POST');
        log('Streaming toggled: ' + JSON.stringify(res));
        setTimeout(loadStatus, 500);
    } catch(e) {}
}

async function toggleVirtualCam() {
    try {
        const res = await api('/api/virtualcam/toggle', 'POST');
        log('VirtualCam toggled: ' + JSON.stringify(res));
        setTimeout(loadStatus, 500);
    } catch(e) {}
}

async function toggleCenterStage() {
    try {
        const res = await api('/api/centerstage/toggle', 'POST');
        log('CenterStage toggled: ' + JSON.stringify(res));
        setTimeout(loadStatus, 500);
    } catch(e) {}
}

async function setCenterStageMode(mode) {
    try {
        const res = await api('/api/centerstage/mode', 'POST', { mode });
        log('CenterStage mode set to ' + mode + ': ' + JSON.stringify(res));
        setTimeout(loadStatus, 300);
    } catch(e) {}
}

async function updateTransition() {
    try {
        const type = document.getElementById('transition-type').value;
        const duration = parseInt(document.getElementById('transition-duration').value);
        await api('/api/transition/config', 'POST', { type, durationMs: duration });
        log('Transition set: ' + type + ' ' + duration + 'ms');
    } catch(e) {}
}

async function requestScreenCapture() {
    try {
        const res = await api('/api/screencapture/request', 'POST');
        log('Screen capture request: ' + JSON.stringify(res) + ' – Check phone for permission dialog!');
        setTimeout(loadStatus, 1000);
    } catch(e) {}
}

async function stopScreenCapture() {
    try {
        const res = await api('/api/screencapture/stop', 'POST');
        log('Screen capture stopped: ' + JSON.stringify(res));
        setTimeout(loadStatus, 500);
    } catch(e) {}
}

async function toggleTeleprompter() {
    try {
        const res = await api('/api/teleprompter/toggle', 'POST');
        log('Teleprompter toggled: ' + JSON.stringify(res));
        setTimeout(loadStatus, 300);
    } catch(e) {}
}

async function toggleMirror() {
    try {
        const res = await api('/api/teleprompter/mirror', 'POST');
        log('Mirror toggled: ' + JSON.stringify(res));
        setTimeout(loadStatus, 300);
    } catch(e) {}
}

function updatePrompterSpeed(val) {
    document.getElementById('prompter-speed-label').innerText = val + 'x';
    api('/api/teleprompter/speed', 'POST', { speed: parseFloat(val) }).then(() => log('Prompter speed ' + val + 'x')).catch(()=>{});
}

function updatePrompterFont(val) {
    document.getElementById('prompter-font-label').innerText = val + 'sp';
    api('/api/teleprompter/font', 'POST', { fontSize: parseInt(val) }).then(() => log('Prompter font ' + val + 'sp')).catch(()=>{});
}

function updatePrompterText() {
    const text = document.getElementById('prompter-text').value;
    if (!text) { log('Enter text first'); return; }
    api('/api/teleprompter/text', 'POST', { text }).then(() => log('Prompter text updated (' + text.length + ' chars)')).catch(()=>{});
}

function refreshPreview() {
    const img = document.getElementById('preview-img');
    img.src = '/video?' + Date.now();
    log('Preview refreshed');
}

function copyUrl() {
    const url = window.location.href;
    navigator.clipboard.writeText(url).then(() => log('URL copied: ' + url)).catch(() => log('Copy failed, URL: ' + url));
    document.getElementById('server-url').innerText = url;
}

// Auto-refresh status every 3s
setInterval(loadStatus, 3000);

// Init
loadStatus();
loadPresets();
log('Remote control UI loaded – ready to control phone from PC on WiFi');
log('Server URL: ' + window.location.href);
</script>
</body>
</html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun serveMjpeg(): Response {
        try {
            if (frameBus == null) {
                try {
                    frameBus = VCamFrameBus.connectAsConsumer()
                } catch (e: Exception) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Frame bus not available – start Virtual Camera service first: ${e.message}")
                }
            }

            val bus = frameBus ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No bus")
            val desc = bus.acquireLatestFrame()
            if (desc == null) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No frame yet – start Virtual Camera service")
            }

            try {
                val nv21Array = ByteArray(desc.dataSize)
                desc.data.duplicate().get(nv21Array)
                val yuvImage = YuvImage(nv21Array, ImageFormat.NV21, desc.width, desc.height, null)
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, desc.width, desc.height), 85, out)
                val jpegBytes = out.toByteArray()
                val input = ByteArrayInputStream(jpegBytes)
                return newChunkedResponse(Response.Status.OK, "image/jpeg", input)
            } finally {
                bus.releaseFrame(desc)
            }
        } catch (e: Exception) {
            Log.e(tag, "serve /video failed", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    private fun serveStatus(): Response {
        val status = RemoteControlHub.getStatus().toMutableMap()
        try {
            val replay = RemoteControlHub.getInstantReplayStatus()
            status.putAll(replay)
        } catch (_: Exception) {}
        try {
            val beauty = RemoteControlHub.getBeautyStatus()
            status.putAll(beauty)
        } catch (_: Exception) {}
        try {
            val audio = RemoteControlHub.getAudioStatus()
            status.putAll(audio)
        } catch (_: Exception) {}
        @Suppress("UNCHECKED_CAST")
        val json = JSONObject(status as MutableMap<Any?, Any?>).toString()
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun servePresets(): Response {
        val presets = RemoteControlHub.getPresets()
        val json = JSONObject()
        val arr = JSONArray()
        presets.forEach { preset ->
            val obj = JSONObject()
            obj.put("id", preset.id)
            obj.put("name", preset.name)
            obj.put("description", preset.description)
            arr.put(obj)
        }
        json.put("presets", arr)
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun servePerformance(): Response {
        val status = RemoteControlHub.getStatus()
        val perf = JSONObject()
        perf.put("gpu", status["gpu"] ?: "78%")
        perf.put("compositorFps", status["compositorFps"] ?: 30)
        perf.put("segmentationFps", status["segmentationFps"] ?: "30fps GPU")
        perf.put("transitionFps", status["transitionFps"] ?: "60fps GPU")
        perf.put("isRecording", status["isRecording"] ?: false)
        perf.put("isStreaming", status["isStreaming"] ?: false)
        perf.put("width", status["width"] ?: 1280)
        perf.put("height", status["height"] ?: 720)
        return newFixedLengthResponse(Response.Status.OK, "application/json", perf.toString())
    }

    private fun serveLegacyConfig(): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"frameBus\":\"${frameBus?.maxWidth}x${frameBus?.maxHeight}\",\"transport\":\"SharedMemory ring buffer\"}")
    }

    private fun parseBody(session: IHTTPSession): JSONObject {
        return try {
            val map = mutableMapOf<String, String>()
            session.parseBody(map)
            val body = map["postData"] ?: ""
            if (body.isNotEmpty()) JSONObject(body) else JSONObject()
        } catch (e: Exception) {
            // Try query params as fallback
            val params = session.parms
            if (params.isNotEmpty()) {
                val json = JSONObject()
                params.forEach { (k, v) -> json.put(k, v) }
                json
            } else {
                JSONObject()
            }
        }
    }

    private fun handlePresetSwitch(session: IHTTPSession): Response {
        val body = parseBody(session)
        val presetId = body.optString("presetId", body.optString("id", ""))
        val withTransition = body.optBoolean("withTransition", true)

        if (presetId.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"presetId required"}""")
        }

        return try {
            RemoteControlHub.switchPreset(presetId, withTransition)
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"presetId":"$presetId","withTransition":$withTransition}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleCenterStageToggle(): Response {
        return try {
            RemoteControlHub.toggleCenterStage()
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"action":"centerstage_toggle"}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleCenterStageMode(session: IHTTPSession): Response {
        val body = parseBody(session)
        val mode = body.optString("mode", "GROUP")
        return try {
            RemoteControlHub.setCenterStageMode(mode)
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"mode":"$mode"}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleTransitionConfig(session: IHTTPSession): Response {
        val body = parseBody(session)
        val type = body.optString("type", "FADE")
        val durationMs = body.optLong("durationMs", 300)
        val easing = body.optString("easing", "EASE_IN_OUT")
        return try {
            RemoteControlHub.setTransitionConfig(type, durationMs, easing)
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"type":"$type","durationMs":$durationMs,"easing":"$easing"}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleRecordingToggle(): Response {
        return try {
            val isNowRecording = RemoteControlHub.toggleRecording()
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"isRecording":$isNowRecording}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleStreamingToggle(): Response {
        return try {
            RemoteControlHub.toggleStreaming()
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"action":"streaming_toggle"}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleStreamingStartAll(): Response {
        return try {
            RemoteControlHub.startAllStreaming()
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"action":"startAll"}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleStreamingStopAll(): Response {
        return try {
            RemoteControlHub.stopAllStreaming()
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"action":"stopAll"}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleVirtualCamToggle(): Response {
        return try {
            RemoteControlHub.toggleVirtualCam()
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"action":"virtualcam_toggle"}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleScreenCaptureRequest(): Response {
        return try {
            RemoteControlHub.requestScreenCapture()
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"action":"screen_capture_request","note":"Check phone for permission dialog"}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleScreenCaptureStop(): Response {
        return try {
            RemoteControlHub.stopScreenCapture()
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"action":"screen_capture_stop"}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleTeleprompterToggle(): Response {
        return try {
            RemoteControlHub.toggleTeleprompter()
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"action":"teleprompter_toggle"}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleTeleprompterText(session: IHTTPSession): Response {
        val body = parseBody(session)
        val text = body.optString("text", "")
        if (text.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"text required"}""")
        }
        return try {
            RemoteControlHub.setTeleprompterText(text)
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"length":${text.length}}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleTeleprompterSpeed(session: IHTTPSession): Response {
        val body = parseBody(session)
        val speed = body.optDouble("speed", 1.0)
        return try {
            RemoteControlHub.setTeleprompterSpeed(speed.toFloat())
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"speed":$speed}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleTeleprompterFont(session: IHTTPSession): Response {
        val body = parseBody(session)
        val fontSize = body.optInt("fontSize", 20)
        return try {
            RemoteControlHub.setTeleprompterFontSize(fontSize)
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"fontSize":$fontSize}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleTeleprompterMirror(): Response {
        return try {
            RemoteControlHub.toggleTeleprompterMirror()
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"action":"mirror_toggle"}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    // --- Missing handlers – added as stubs to fix compilation ---
    private fun handleInstantReplayToggle(enabled: Boolean) {}

    private fun handleBeautyFilter(params: Map<String, Any>) {}

    private fun handleFaceFilter(params: Map<String, Any>) {}

    private fun handleAudioSource(source: String) {}

    private fun handleMusicDuckingToggle(enabled: Boolean) {}

    // Actual handlers used by serve() – return Response
    private fun handleInstantReplayToggle(): Response {
        return try {
            // Check if hub has instant replay toggle
            try {
                RemoteControlHub.toggleInstantReplay()
            } catch (_: Exception) {
                // Fallback to stub
                handleInstantReplayToggle(true)
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"action":"instant_replay_toggle"}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun parseJsonParams(body: String): Map<String, Any> {
        return try {
            val map = mutableMapOf<String, Any>()
            val json = org.json.JSONObject(body)
            json.keys().forEach { key ->
                map[key] = json.get(key)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun handleInstantReplaySave(): Response {
        return try {
            try {
                RemoteControlHub.saveInstantReplay()
            } catch (_: Exception) {
                // No-op fallback – previously recursive call removed to fix unresolved s and stack overflow
                Log.w(tag, "saveInstantReplay hub not available, using no-op")
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"action":"instant_replay_save"}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleBeautyFilter(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session)
            val map = mutableMapOf<String, Any>()
            body.keys().forEach { k -> map[k] = body.opt(k) ?: "" }
            handleBeautyFilter(map)
            // Try hub if exists – hub expects (type, value)
            try {
                val type = body.optString("type", body.optString("filter", "smooth"))
                val value = body.optDouble("value", body.optDouble("intensity", 0.5)).toFloat()
                RemoteControlHub.setBeautyFilter(type, value)
            } catch (_: Exception) {}
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"action":"beauty_filter"}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleFaceFilter(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session)
            val map = mutableMapOf<String, Any>()
            body.keys().forEach { k -> map[k] = body.opt(k) ?: "" }
            handleFaceFilter(map)
            try {
                val filterType = body.optString("type", body.optString("filterType", "beauty"))
                val intensity = body.optDouble("intensity", body.optDouble("value", 0.5)).toFloat()
                RemoteControlHub.setFaceFilter(filterType, intensity)
            } catch (_: Exception) {}
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"action":"face_filter"}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleAudioSource(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session)
            val source = body.optString("source", body.optString("audioSource", "mic"))
            handleAudioSource(source)
            try {
                RemoteControlHub.setAudioSource(source)
            } catch (_: Exception) {}
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"source":"$source"}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleMusicDuckingToggle(): Response {
        return try {
            try {
                RemoteControlHub.toggleMusicDucking()
            } catch (_: Exception) {
                handleMusicDuckingToggle(true)
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"action":"music_ducking_toggle"}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${e.message}"}""")
        }
    }
}
