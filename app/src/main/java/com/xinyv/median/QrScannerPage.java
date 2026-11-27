// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Median Browser contributors

package com.xinyv.median;

/** Lightweight QR camera page backed by the WebView/provider BarcodeDetector when available. */
final class QrScannerPage {
    private QrScannerPage() {}

    static String html(String token, boolean dark) {
        String background = dark ? "#111315" : "#f8f9fa";
        String card = dark ? "#23262a" : "#ffffff";
        String text = dark ? "#e8eaed" : "#202124";
        String muted = dark ? "#9aa0a6" : "#5f6368";
        return "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1,viewport-fit=cover'>"
                + "<style>*{box-sizing:border-box}body{margin:0;min-height:100vh;padding:24px 18px;background:" + background
                + ";color:" + text + ";font:15px/1.45 system-ui,sans-serif}main{max-width:520px;margin:auto}h1{font-size:25px;margin:10px 0 4px}.sub{color:"
                + muted + ";margin-bottom:18px}.camera{position:relative;overflow:hidden;aspect-ratio:1/1;border-radius:24px;background:#090a0b;box-shadow:0 10px 36px rgba(0,0,0,.24)}video{width:100%;height:100%;object-fit:cover}.frame{position:absolute;inset:19%;border:3px solid #8ab4f8;border-radius:22px;box-shadow:0 0 0 999px rgba(0,0,0,.22)}#status{min-height:48px;margin:16px 2px 8px;color:"
                + muted + "}.actions{display:grid;gap:10px}button{width:100%;border:0;border-radius:16px;padding:13px 16px;background:#1a73e8;color:#fff;font:600 15px system-ui,sans-serif}button.secondary{background:"
                + card + ";color:" + text + ";box-shadow:0 1px 4px rgba(0,0,0,.14)}</style></head><body><main><h1>Scan QR Code</h1><div class=sub>Align the QR code inside the frame, then choose to open or copy</div><div class=camera><video id=v playsinline muted></video><div class=frame></div></div><div id=status>Preparing camera…</div><div class=actions><button id=system class=secondary>Use System QR Scanner</button><button id=cancel class=secondary>Back to Browser</button></div></main><script>"
                + "'use strict';var T=" + quote(token) + ",video=document.getElementById('v'),status=document.getElementById('status'),stream=null,stopped=false,starting=false,restart=false,last=0;"
                + "function stop(){stopped=true;if(stream)stream.getTracks().forEach(function(t){t.stop();});stream=null;video.srcObject=null;}window.__medianStopScanner=stop;"
                + "function send(value){stop();try{window.prompt(T,JSON.stringify(value));}catch(_){}}"
                + "document.getElementById('system').onclick=function(){send({external:true});};document.getElementById('cancel').onclick=function(){send({cancel:true});};"
                + "window.addEventListener('pagehide',stop,{once:true});document.addEventListener('visibilitychange',function(){if(document.hidden)stop();else if(starting)restart=true;else start();});"
                + "async function start(){if(starting||stream||document.hidden)return;if(!('BarcodeDetector'in window)||!navigator.mediaDevices||!navigator.mediaDevices.getUserMedia){status.textContent='Built-in QR detection is unavailable in this System WebView. Please use the system scanner app.';return;}restart=false;starting=true;stopped=false;"
                + "try{var detector=new BarcodeDetector({formats:['qr_code']}),next=await navigator.mediaDevices.getUserMedia({audio:false,video:{facingMode:{ideal:'environment'},width:{ideal:1280},height:{ideal:1280}}});if(stopped){next.getTracks().forEach(function(t){t.stop();});return;}stream=next;video.srcObject=stream;await video.play();status.textContent='Place QR code inside the frame';"
                + "async function scan(now){if(stopped)return;if(now-last<120){requestAnimationFrame(scan);return;}last=now;try{var codes=await detector.detect(video);if(stopped)return;if(codes&&codes.length&&codes[0].rawValue){send({value:String(codes[0].rawValue)});return;}}catch(_){}if(!stopped)requestAnimationFrame(scan);}requestAnimationFrame(scan);"
                + "}catch(e){status.textContent='Unable to access camera. Check permissions or use the system scanner app.';stop();}finally{starting=false;if(restart&&!document.hidden){restart=false;start();}}}window.__medianRestartScanner=start;start();</script></body></html>";
    }

    private static String quote(String value) {
        String input = value == null ? "" : value;
        StringBuilder out = new StringBuilder(input.length() + 2).append('"');
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                String hex = Integer.toHexString(c);
                out.append("\\u");
                for (int pad = hex.length(); pad < 4; pad++) out.append('0');
                out.append(hex);
            } else out.append(c);
        }
        return out.append('"').toString();
    }
}
