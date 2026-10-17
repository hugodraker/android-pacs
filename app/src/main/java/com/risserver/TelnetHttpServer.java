/* ============================================================================
 * RIS Multi-Protocol Server (DICOM + Telnet + HTTP) - ANDROID VERSION
 *
 * THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
 * AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
 *
 * 2026-10-16 Hugo
 *
 * ============================================================================ */

package com.hugo.risserver;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class TelnetHttpServer {
    private final RisDataStore store;
    private ServerSocket telnetServerSocket;
    private ServerSocket httpServerSocket;
    private volatile boolean running = false;

    private static final String HTML_START =
            "<html><head><title>RIS Worklist Manager</title><meta name='viewport' content='width=device-width, initial-scale=1'><style>" +
            "table{border-collapse:collapse;width:100%;font-size:14px;}th,td{border:1px solid #dddddd;padding:12px;}th{background:#009879;color:#fff;text-align:left;}" +
            "tr{border-bottom:1px solid #ddd;}tr:nth-child(even){background:#f3f3f3;}input,select{margin:5px;padding:5px;}div.query-box{margin:20px;padding:10px;background:#f9f9f9;border-radius:5px;}div.form-row{display:inline-block;margin-right:15px;}" +
            ".top-btn { min-width: 140px; height: 36px; padding: 0 15px; box-sizing: border-box; text-align: center; border: none; cursor: pointer; color: white; display: inline-block; font-size: 14px; margin: 0; line-height: 36px; border-radius: 4px; text-decoration: none; }" +
            ".bg-green { background: #4CAF50; } .bg-blue { background: #2196F3; } .bg-red { background: #f44336; }" +
            "</style>" +
            "<script>var fnames=['PatientName','PatientID','BirthDate','Sex','ReqPhys','ReqSvc','ProcDesc','Reason','Accession','ProcID','Priority','StationAE','StartDate','StartTime','PerfPhys','SPSDesc','SPSID','StationName','Location','Status','Modality','RefPhys','StudyDesc','ProtoCode','ProtoScheme','ProtoMeaning','StudyUID'];" +
            "function f(r){var c=r.cells;for(var i=0;i<c.length&&i<fnames.length;i++){var e=document.getElementsByName(fnames[i])[0];if(e)e.value=(c[i].innerText||c[i].textContent).trim();}" +
            "var sel=document.getElementById('procCodeSel');if(sel){var pid=document.getElementsByName('ProcID')[0].value;for(var j=0;j<sel.options.length;j++){sel.options[j].selected=(sel.options[j].getAttribute('data-id')===pid);}}}" +
            "window.maxAcc=window.maxAcc||0;" +
            "function nxtA(){var t=document.getElementById('pTable'),m=0,p='ACC',c=5;if(t){for(var i=1;i<t.rows.length;i++){var v=t.rows[i].cells[8].innerText||'',mt=v.match(/([^0-9]*)([0-9]+)/);if(mt){p=mt[1];var val=parseInt(mt[2],10);if(val>m){m=val;c=mt[2].length;}}}}if(window.maxAcc<=m)window.maxAcc=m;window.maxAcc++;document.getElementsByName('Accession')[0].value=p+String(window.maxAcc).padStart(c,'0');}" +
            "function sendMan(){var a=document.getElementsByName('Accession')[0].value.trim();if(!a){alert('Accession number is required!');return false;}" +
            "var sel=document.getElementById('procCodeSel');var opts=[];if(sel){for(var j=0;j<sel.options.length;j++){if(sel.options[j].selected&&sel.options[j].value!=='')opts.push(sel.options[j]);}}" +
            "if(opts.length>1){var p=[];for(var k=0;k<opts.length;k++){nxtA();" +
            "document.getElementsByName('Modality')[0].value=opts[k].getAttribute('data-mod')||'';" +
            "document.getElementsByName('ProcDesc')[0].value=opts[k].text||'';" +
            "document.getElementsByName('ProcID')[0].value=opts[k].getAttribute('data-id')||'';" +
            "document.getElementsByName('SPSID')[0].value=opts[k].getAttribute('data-code')||'';" +
            "document.getElementsByName('StationAE')[0].value='';" +
            "document.getElementsByName('SPSDesc')[0].value='';" +
            "var sn=document.getElementsByName('StationName')[0];if(sn)sn.value='';" +
            "document.getElementsByName('Location')[0].value='';" +
            "document.getElementsByName('StudyDesc')[0].value='';" +
            "var c=[];for(var i=0;i<fnames.length;i++){var el=document.getElementsByName(fnames[i])[0];var v=el?el.value:'';if(v.indexOf(',')>-1||v.indexOf('\"')>-1)v='\"'+v.replace(new RegExp('\"','g'),'\"\"')+'\"';c.push(v);}" +
            "p.push(fetch('/manual',{method:'POST',body:c.join(',')}));}Promise.all(p).then(function(){window.location.href='/';});return false;}" +
            "var c=[];for(var i=0;i<fnames.length;i++){var el=document.getElementsByName(fnames[i])[0];var v=el?el.value:'';if(v.indexOf(',')>-1||v.indexOf('\"')>-1)v='\"'+v.replace(new RegExp('\"','g'),'\"\"')+'\"';c.push(v);}fetch('/manual',{method:'POST',body:c.join(',')}).then(function(){window.location.href='/';});return false;}" +
            "function delP(){var a=document.getElementsByName('Accession')[0].value;if(a&&confirm('Delete Patient '+a+'?'))fetch('/delpat',{method:'POST',body:a}).then(function(){window.location.href='/';});}" +
            "function delAll(){var p=prompt('Enter password to delete all data:');if(p!==null){document.getElementById('delPwd').value=p;document.getElementById('delAllForm').submit();}}" +
            "function multiStudy(){alert('Stub not implemented');}" +
            "function sortTable(n){var t,r,sw,i,x,y,sd,d;t=document.getElementById('pTable');sw=true;d='asc';while(sw){sw=false;r=t.rows;for(i=1;i<(r.length-1);i++){sd=false;x=r[i].getElementsByTagName('TD')[n];y=r[i+1].getElementsByTagName('TD')[n];if(d=='asc'){if(x.innerHTML.toLowerCase()>y.innerHTML.toLowerCase()){sd=true;break;}}else{if(x.innerHTML.toLowerCase()<y.innerHTML.toLowerCase()){sd=true;break;}}}if(sd){r[i].parentNode.insertBefore(r[i+1],r[i]);sw=true;}}}" +
            "function formatDates(){var t=document.getElementById('pTable');if(!t)return;for(var i=1;i<t.rows.length;i++){var c=t.rows[i].cells;if(c.length>13){" +
            "var b=c[2].innerText;if(b.length===8)c[2].innerText=b.substr(0,4)+'-'+b.substr(4,2)+'-'+b.substr(6,2);" +
            "var s=c[12].innerText;if(s.length===8)c[12].innerText=s.substr(0,4)+'-'+s.substr(4,2)+'-'+s.substr(6,2);" +
            "var tm=c[13].innerText;if(tm.length===6)c[13].innerText=tm.substr(0,2)+':'+tm.substr(2,2);" +
            "}}}" +
            "function newPatient(){for(var i=0;i<fnames.length;i++){var el=document.getElementsByName(fnames[i])[0];if(el)el.value='';}var d=new Date(),y=d.getFullYear(),mo=('0'+(d.getMonth()+1)).slice(-2),da=('0'+d.getDate()).slice(-2);var sd=document.getElementsByName('StartDate')[0];if(sd)sd.value=y+'-'+mo+'-'+da;nxtA();var su=document.getElementsByName('StudyUID')[0];if(su){var sec=Math.floor(Date.now()/1000);var rnd=Math.floor(Math.random()*10000);su.value='1.2.840.113619.2.1.'+sec+'.'+rnd;}var stat=document.getElementsByName('Status')[0];if(stat)stat.value='1';var pri=document.getElementsByName('Priority')[0];if(pri)pri.value='LOW';var tm=document.getElementsByName('StartTime')[0];if(tm)tm.value='16:00';}" +
            "function loadProcCode(){var sel=document.getElementById('procCodeSel');if(sel&&sel.selectedIndex>=0){var idx=sel.selectedIndex;" +
            "var id=document.getElementsByName('ProcID')[0];var code=document.getElementsByName('SPSID')[0];var mod=document.getElementsByName('Modality')[0];var desc=document.getElementsByName('ProcDesc')[0];" +
            "if(id&&code&&mod&&desc){id.value=sel.options[idx].getAttribute('data-id')||'';code.value=sel.options[idx].getAttribute('data-code')||'';mod.value=sel.options[idx].getAttribute('data-mod')||'';desc.value=sel.options[idx].text;}}" +
            "}" +
            "function filterProcCodes(){var i,f=document.getElementById('procFilter').value.toUpperCase(),s=document.getElementById('procCodeSel'),o=s.options;for(i=0;i<o.length;i++){if((o[i].text||o[i].innerText).toUpperCase().indexOf(f)>-1){o[i].style.display='';}else{o[i].style.display='none';o[i].selected=false;}}}" +
            "let ptDebounce=null;let ptAbort=null;" +
            "function filterPatients(){" +
            "    var f=document.getElementById('patientFilter').value.trim();" +
            "    if(ptDebounce)clearTimeout(ptDebounce);" +
            "    ptDebounce=setTimeout(async function(){" +
            "        if(ptAbort)ptAbort.abort();" +
            "        ptAbort=new AbortController();" +
            "        try{" +
            "            var res=await fetch('/api/patients?filter='+encodeURIComponent(f),{signal:ptAbort.signal});" +
            "            if(!res.ok)return;" +
            "            var names=await res.json();" +
            "            var sel=document.getElementById('patientSel');" +
            "            sel.options.length=0;" +
            "            names.forEach(function(n){" +
            "                var opt=document.createElement('option');" +
            "                opt.value=n;opt.text=n;" +
            "                sel.appendChild(opt);" +
            "            });" +
            "        }catch(e){}" +
            "    },250);" +
            "}" +
            "async function loadPatientDetails(name){if(!name)return;try{var res=await fetch('/api/patient/'+encodeURIComponent(name));if(!res.ok)return;var data=await res.json();if(!data.error){document.getElementsByName('PatientName')[0].value=data.name||'';document.getElementsByName('PatientID')[0].value=data.patient_id||'';" +
            "var bd=data.birthdate||'';if(/^\\d{8}$/.test(bd)){bd=bd.substr(0,4)+'-'+bd.substr(4,2)+'-'+bd.substr(6,2);}else if(bd.indexOf('/')>-1){var p=bd.split('/');if(p.length===3){if(p[2].length===4)bd=p[2]+'-'+p[0].padStart(2,'0')+'-'+p[1].padStart(2,'0');else if(p[0].length===4)bd=p[0]+'-'+p[1].padStart(2,'0')+'-'+p[2].padStart(2,'0');}}" +
            "document.getElementsByName('BirthDate')[0].value=bd;document.getElementsByName('Sex')[0].value=data.sex||'';}}catch(e){}}" +
            "function filterStatus(s){var t=document.getElementById('pTable');if(!t)return;for(var i=1;i<t.rows.length;i++){var st=t.rows[i].cells[19].innerText||t.rows[i].cells[19].textContent;if(s===''||st.trim()===s)t.rows[i].style.display='';else t.rows[i].style.display='none';}}" +
            "window.onload=function(){newPatient();filterPatients();filterStatus('1');};</script></head><body style='font-family:Arial,sans-serif;padding:20px;'>";

    private static final String HTML_FORMS_TOP =
            "<div class='form-row' style='display:flex; flex-wrap:wrap; align-items:center; gap:10px; margin-top:20px; margin-bottom:15px;'>" +
            "<button type='button' onclick='newPatient()' class='top-btn bg-green'>New Patient</button>" +
            "<button type='button' onclick='filterPatients()' class='top-btn bg-blue'>Search</button>" +
            "<form action='/upload' method='POST' enctype='multipart/form-data' id='uploadForm' style='display:inline-flex; align-items:center; gap:10px; margin:0;'>" +
            "<input type='file' name='csvfile' id='csvfile' style='display:none;' onchange='document.getElementById(\"uploadForm\").submit();'>" +
            "<label for='csvfile' class='top-btn bg-blue'>Choose File</label>" +
            "</form>" +
            "<form action='/delete' method='POST' id='delAllForm' style='margin:0;'>" +
            "<input type='hidden' name='pwd' id='delPwd' value=''>" +
            "<input type='button' value='Delete All Data' class='top-btn bg-red' onclick='delAll()'>" +
            "</form></div><hr>" +
            "<form id='manForm'>";

    private static final String HTML_FORMS_INPUTS =
            "<input type='text' name='PatientName' placeholder='Patient Name'> <input type='text' name='PatientID' placeholder='Patient ID'> Birth: <input type='date' name='BirthDate'> <select name='Sex'><option value=''>Sex</option><option value='M'>M</option><option value='F'>F</option><option value='O'>O</option></select><br>" +
            "<input type='text' name='ReqPhys' placeholder='Req Phys'> <input type='text' name='ReqSvc' placeholder='Req Svc'> <input type='text' name='ProcDesc' placeholder='Proc Desc'> <input type='text' name='Reason' placeholder='Reason'><br>" +
            "<input type='text' name='Accession' placeholder='Accession'> <input type='text' name='ProcID' placeholder='Proc ID'> <select name='Priority'><option value='LOW' selected>LOW</option><option value='MEDIUM'>MEDIUM</option><option value='ROUTINE'>ROUTINE</option><option value='HIGH'>HIGH</option></select> <input type='text' name='StationAE' placeholder='Station AE'><br>" +
            "Start: <input type='date' name='StartDate'> Time: <input type='time' name='StartTime'> <input type='text' name='PerfPhys' placeholder='Perf Phys'> <input type='text' name='SPSDesc' placeholder='SPS Desc'><br>" +
            "<input type='text' name='SPSID' placeholder='SPS ID'> ";

    private static final String HTML_FORMS_2 =
            " <select name='Status'><option value='0'>NONE</option><option value='1' selected>SCHEDULED</option><option value='2'>ARRIVED</option><option value='3'>READY</option><option value='4'>STARTED</option><option value='5'>DEPARTED</option></select><br>" +
            "<input type='text' name='Modality' placeholder='Modality'> <input type='text' name='RefPhys' placeholder='Ref Phys'> <input type='text' name='StudyDesc' placeholder='Study Desc'><br>" +
            "<input type='text' name='ProtoCode' placeholder='Proto Code'> <input type='text' name='ProtoScheme' placeholder='Proto Scheme'> <input type='text' name='ProtoMeaning' placeholder='Proto Meaning'><br>" +
            "<input type='text' name='StudyUID' placeholder='Study UID' style='width:300px;'><br>" +
            "<div style='margin-top:15px; display:flex; flex-wrap:wrap; gap:10px;'>" +
            "<button type='button' onclick='sendMan()' class='top-btn bg-green'>Add/Edit Patient</button> " +
            "<button type='button' onclick='nxtA()' class='top-btn bg-blue'>Next Accession</button> " +
            "<button type='button' onclick='multiStudy()' class='top-btn bg-blue'>Stub</button> " +
            "<button type='button' onclick='delP()' class='top-btn bg-red'>Delete Patient</button></div></form><hr>";

    private static final String HTML_END = "</body></html>";

    public TelnetHttpServer(RisDataStore store) {
        this.store = store;
    }

    public void start() {
        running = true;
        new Thread(this::telnetAcceptLoop, "TelnetAcceptThread").start();
        new Thread(this::httpAcceptLoop, "HttpAcceptThread").start();
    }

    public void stop() {
        running = false;
        try { if (telnetServerSocket != null) telnetServerSocket.close(); } catch (IOException ignored) {}
        try { if (httpServerSocket != null) httpServerSocket.close(); } catch (IOException ignored) {}
    }

    // ===== Telnet Server =====
    private void telnetAcceptLoop() {
        try {
            telnetServerSocket = new ServerSocket();
            telnetServerSocket.setReuseAddress(true);
            try {
                telnetServerSocket.bind(new InetSocketAddress("0.0.0.0", store.telnetPort));
            } catch (BindException e) {
                if (store.telnetPort < 1024) {
                    store.writeLog("Port " + store.telnetPort + " restricted on Android; falling back to 2323");
                    store.telnetPort = 2323;
                    telnetServerSocket.bind(new InetSocketAddress("0.0.0.0", store.telnetPort));
                    store.notifyStatusChanged();
                } else {
                    throw e;
                }
            }
            store.writeLog("Telnet listening on port " + store.telnetPort);
            store.notifyStatusChanged();

            while (running && !telnetServerSocket.isClosed()) {
                Socket client = telnetServerSocket.accept();
                store.writeLog("Telnet Client connected (" + client.getRemoteSocketAddress() + ")");
                new Thread(() -> handleTelnetClient(client), "TelnetClientWorker").start();
            }
        } catch (IOException e) {
            if (running) store.writeLog("ERROR: Failed to listen on Telnet port " + store.telnetPort);
        }
    }

    private void handleTelnetClient(Socket client) {
        try {
            client.setSoTimeout(Math.max(1, store.telnetTimeout) * 1000);
            OutputStream out = client.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));

            out.write("Connected to RIS Telnet Server. Waiting for data...\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            String line;
            while (running && (line = reader.readLine()) != null) {
                String pLine = line.trim();
                if (pLine.isEmpty()) continue;

                if (pLine.length() >= 4 && pLine.substring(0, 4).equalsIgnoreCase("DISP")) {
                    String param = pLine.substring(4).trim();
                    int comma = param.indexOf(',');
                    if (comma >= 0) param = param.substring(0, comma).trim();

                    store.writeLog("DISP query received");
                    List<String> rows = store.getCsvRowsSnapshot();
                    int count = 0;
                    for (int i = 1; i < rows.size(); i++) {
                        String rowStr = rows.get(i);
                        if (lineMatchesDisp(rowStr, param)) {
                            out.write((rowStr + "\r\n").getBytes(StandardCharsets.UTF_8));
                            count++;
                        }
                    }
                    out.write((count + " RESULTS\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } else {
                    String[] entry = RisDataStore.parseCsvLineToEntry(pLine);
                    if (!RisDataStore.validateEntry(entry)) {
                        out.write("INVALID LINE\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else {
                        boolean updated = store.normalizeAndUpsertEntry(entry);
                        if (updated) {
                            out.write("UPDATED\r\n".getBytes(StandardCharsets.UTF_8));
                            store.writeLog("UPDATED Accession " + entry[8]);
                        } else {
                            out.write("INSERTED\r\n".getBytes(StandardCharsets.UTF_8));
                            store.writeLog("INSERTED Accession " + entry[8]);
                        }
                        out.flush();
                    }
                }
            }
        } catch (SocketTimeoutException te) {
            store.writeLog("Telnet client timed out");
        } catch (Exception ignored) {
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private boolean lineMatchesDisp(String line, String param) {
        if (param == null || param.isEmpty()) return true;
        String[] temp = RisDataStore.parseCsvLineToEntry(line);
        if (temp[20].equalsIgnoreCase(param)) return true; // Modality
        if (temp[11].equalsIgnoreCase(param)) return true; // StationAE
        return param.matches("^\\d{8}$") && temp[1].equals(param); // 8-digit PatientID
    }

    // ===== HTTP Server =====
    private void httpAcceptLoop() {
        try {
            httpServerSocket = new ServerSocket();
            httpServerSocket.setReuseAddress(true);
            try {
                httpServerSocket.bind(new InetSocketAddress("0.0.0.0", store.httpPort));
            } catch (BindException e) {
                if (store.httpPort < 1024) {
                    store.writeLog("Port " + store.httpPort + " restricted on Android; falling back to 8080");
                    store.httpPort = 8080;
                    httpServerSocket.bind(new InetSocketAddress("0.0.0.0", store.httpPort));
                    store.notifyStatusChanged();
                } else {
                    throw e;
                }
            }
            store.writeLog("HTTP listening on port " + store.httpPort);
            store.notifyStatusChanged();

            while (running && !httpServerSocket.isClosed()) {
                Socket client = httpServerSocket.accept();
                new Thread(() -> handleHttpClient(client), "HttpClientWorker").start();
            }
        } catch (IOException e) {
            if (running) store.writeLog("ERROR: Failed to listen on HTTP port " + store.httpPort);
        }
    }

    private void handleHttpClient(Socket client) {
        try {
            client.setSoTimeout(10000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            ByteArrayOutputStream reqBuf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int headerEnd = -1;
            int contentLength = 0;

            while (true) {
                int r = in.read(chunk);
                if (r <= 0) break;
                reqBuf.write(chunk, 0, r);
                byte[] current = reqBuf.toByteArray();

                if (headerEnd < 0) {
                    headerEnd = findHeaderEnd(current);
                    if (headerEnd >= 0) {
                        String hdrStr = new String(current, 0, headerEnd, StandardCharsets.UTF_8);
                        for (String hLine : hdrStr.split("\r\n")) {
                            if (hLine.toLowerCase().startsWith("content-length:")) {
                                try {
                                    contentLength = Integer.parseInt(hLine.substring(15).trim());
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                }
                if (headerEnd >= 0 && current.length >= headerEnd + 4 + contentLength) {
                    break;
                }
            }

            if (headerEnd < 0) return;

            byte[] fullReqBytes = reqBuf.toByteArray();
            String headerStr = new String(fullReqBytes, 0, headerEnd, StandardCharsets.UTF_8);
            String bodyStr = new String(fullReqBytes, headerEnd + 4, fullReqBytes.length - (headerEnd + 4), StandardCharsets.UTF_8);

            String firstLine = headerStr.split("\r\n")[0];
            String[] parts = firstLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String url = parts.length > 1 ? parts[1] : "/";

            if (method.equals("GET") && url.startsWith("/api/patient/")) {
                String rawName = url.substring(13);
                String decodedName = URLDecoder.decode(rawName, "UTF-8");
                String json = store.getPatientDetailsJson(decodedName);
                if (json != null) {
                    sendHttpResponse(out, "200 OK", "application/json; charset=utf-8", json);
                } else {
                    sendHttpResponse(out, "404 Not Found", "application/json; charset=utf-8", "{\"error\": \"Patient not found\"}");
                }
                return;
            }

            if (method.equals("GET") && url.startsWith("/api/patients")) {
                String filter = "";
                int qIdx = url.indexOf('?');
                if (qIdx >= 0) {
                    for (String kv : url.substring(qIdx + 1).split("&")) {
                        if (kv.startsWith("filter=")) {
                            filter = URLDecoder.decode(kv.substring(7), "UTF-8");
                        }
                    }
                }
                int maxResults = filter.isEmpty() ? 10 : 0;
                String json = store.getPatientNamesJson(filter, maxResults);
                sendHttpResponse(out, "200 OK", "application/json; charset=utf-8", json);
                return;
            }

            if (method.equals("GET") && url.startsWith("/download?sop=")) {
                String sop = url.substring(14);
                File f = store.getImageFilePath(sop);
                if (f != null && f.exists()) {
                    byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
                    String hdr = "HTTP/1.1 200 OK\r\n" +
                                 "Content-Type: application/dicom\r\n" +
                                 "Content-Disposition: attachment; filename=\"" + f.getName() + ".dcm\"\r\n" +
                                 "Content-Length: " + data.length + "\r\n" +
                                 "Connection: close\r\n\r\n";
                    out.write(hdr.getBytes(StandardCharsets.UTF_8));
                    out.write(data);
                    out.flush();
                } else {
                    sendHttpResponse(out, "404 Not Found", "text/plain", "File not found.");
                }
                return;
            }

            if (method.equals("GET") && url.startsWith("/header?sop=")) {
                String sop = url.substring(12);
                File f = store.getImageFilePath(sop);
                if (f != null && f.exists()) {
                    byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(data.length, 4096); i++) {
                        char c = (char) data[i];
                        if (c >= 32 && c <= 126) sb.append(c);
                        else sb.append(".");
                    }
                    sendHttpResponse(out, "200 OK", "text/plain; charset=utf-8", "DICOM HEADER DUMP:\n\n" + sb.toString());
                } else {
                    sendHttpResponse(out, "404 Not Found", "text/plain", "File not found.");
                }
                return;
            }

            if (method.equals("GET")) {
                sendHttpResponse(out, "200 OK", "text/html; charset=utf-8", buildMainHtml());
                return;
            }

            if (method.equals("POST")) {
                if (url.startsWith("/delete")) {
                    String inputPwd = "";
                    int pwdIdx = bodyStr.indexOf("pwd=");
                    if (pwdIdx >= 0) {
                        String sub = bodyStr.substring(pwdIdx + 4);
                        int amp = sub.indexOf('&');
                        if (amp >= 0) sub = sub.substring(0, amp);
                        inputPwd = URLDecoder.decode(sub.trim(), "UTF-8");
                    }
                    if (inputPwd.equals(store.delPwd)) {
                        store.resetWorklistToHeader();
                        sendHttpResponse(out, "200 OK", "text/html; charset=utf-8",
                                HTML_START + "<meta http-equiv='refresh' content='1; url=/' /><h2>Data Deleted Successfully. Redirecting...</h2>" + HTML_END);
                    } else {
                        sendHttpResponse(out, "200 OK", "text/html; charset=utf-8",
                                HTML_START + "<meta http-equiv='refresh' content='2; url=/' /><h2>Invalid Password! Redirecting...</h2>" + HTML_END);
                    }
                    return;
                }

                if (url.startsWith("/delpat")) {
                    store.deletePatientByAccession(bodyStr.trim());
                } else if (url.startsWith("/manual")) {
                    String[] entry = RisDataStore.parseCsvLineToEntry(bodyStr.trim());
                    if (RisDataStore.validateEntry(entry)) {
                        store.normalizeAndUpsertEntry(entry);
                    }
                } else if (url.startsWith("/upload")) {
                    int csvIdx = bodyStr.indexOf(RisDataStore.CSV_HEADER);
                    if (csvIdx >= 0) {
                        String after = bodyStr.substring(csvIdx + RisDataStore.CSV_HEADER.length());
                        for (String l : after.split("\\r?\\n")) {
                            String trimmed = l.trim();
                            if (trimmed.isEmpty()) continue;
                            if (trimmed.startsWith("-")) break;
                            String[] entry = RisDataStore.parseCsvLineToEntry(trimmed);
                            if (RisDataStore.validateEntry(entry)) {
                                store.normalizeAndUpsertEntry(entry);
                            }
                        }
                    }
                }

                sendHttpResponse(out, "200 OK", "text/html; charset=utf-8",
                        HTML_START + "<meta http-equiv='refresh' content='0; url=/' /><h2>Action Complete</h2>" + HTML_END);
            }
        } catch (Exception ignored) {
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private String buildMainHtml() {
        StringBuilder sb = new StringBuilder(HTML_START);

        sb.append("<div style='margin-bottom:10px; display:flex; flex-wrap:wrap; gap:5px;'>" +
                  "<button onclick=\"filterStatus('')\" class='top-btn' style='background:#555555;'>ALL</button>" +
                  "<button onclick=\"filterStatus('0')\" class='top-btn' style='background:#9e9e9e;'>NONE</button>" +
                  "<button onclick=\"filterStatus('1')\" class='top-btn' style='background:#2196F3;'>SCHEDULED</button>" +
                  "<button onclick=\"filterStatus('2')\" class='top-btn' style='background:#ff9800;'>ARRIVED</button>" +
                  "<button onclick=\"filterStatus('3')\" class='top-btn' style='background:#9c27b0;'>READY</button>" +
                  "<button onclick=\"filterStatus('4')\" class='top-btn' style='background:#4CAF50;'>STARTED</button>" +
                  "<button onclick=\"filterStatus('5')\" class='top-btn' style='background:#f44336;'>DEPARTED</button>" +
                  "</div>");

        List<String> rows = store.getCsvRowsSnapshot();
        sb.append("<div style='overflow-x:auto;'><table id='pTable'>");
        for (int ri = 0; ri < rows.size(); ri++) {
            boolean isHeader = (ri == 0);
            String[] entry = RisDataStore.parseCsvLineToEntry(rows.get(ri));
            sb.append(isHeader ? "<tr>" : "<tr onclick='f(this)' style='cursor:pointer;'>");
            for (int fi = 0; fi < RisDataStore.FIELD_COUNT; fi++) {
                sb.append(isHeader ? "<th onclick='sortTable(this.cellIndex)'>" : "<td>");
                sb.append(entry[fi]);
                sb.append(isHeader ? "</th>" : "</td>");
            }
            
            if (isHeader) {
                sb.append("<th>Images</th>");
            } else {
                String acc = entry[8];
                List<RisDataStore.StoredImageInfo> images = store.getStoredImagesForAccession(acc);
                if (!images.isEmpty()) {
                    sb.append("<td style='white-space:nowrap;'>");
                    for (int imgIdx = 0; imgIdx < images.size(); imgIdx++) {
                        RisDataStore.StoredImageInfo img = images.get(imgIdx);
                        sb.append("<div style='margin-bottom:4px; font-size:12px;'>")
                          .append(img.modality).append(" #").append(imgIdx + 1).append(": ")
                          .append("<a href='/download?sop=").append(img.sopInstanceUID).append("' style='color:#2196F3;text-decoration:none;font-weight:bold;'>DL .dcm</a> | ")
                          .append("<a href='/header?sop=").append(img.sopInstanceUID).append("' style='color:#9c27b0;text-decoration:none;font-weight:bold;'>Header</a></div>");
                    }
                    sb.append("</td>");
                } else {
                    sb.append("<td>0</td>");
                }
            }
            
            sb.append("</tr>");
        }
        sb.append("</table></div><script>formatDates();</script>");

        sb.append(HTML_FORMS_TOP);

        sb.append("<div style='display:flex; flex-wrap:wrap; gap:20px; margin-bottom:15px;'>" +
                  "<div>" +
                  "<input type='text' id='patientFilter' oninput='filterPatients()' placeholder='Filter patients...' style='width: 300px; display: block; margin-bottom: 5px; padding: 5px;'>" +
                  "<select id='patientSel' size='5' onchange='loadPatientDetails(this.value)' style='width: 300px; vertical-align:top;'></select></div>" +
                  "<div>" +
                  "<input type='text' id='procFilter' onkeyup='filterProcCodes()' placeholder='Filter procedure codes...' style='width: 300px; display: block; margin-bottom: 5px; padding: 5px;'>" +
                  "<select id='procCodeSel' multiple size='5' onchange=\"loadProcCode()\" style='width: 300px; vertical-align:top; margin-bottom: 15px;'>");

        if (store.procCodesLoaded) {
            for (RisDataStore.ProcCodeEntry pc : store.procCodes) {
                sb.append("<option value='").append(pc.id)
                  .append("' data-id='").append(pc.id)
                  .append("' data-code='").append(pc.procCode)
                  .append("' data-mod='").append(pc.modality).append("'>")
                  .append(pc.name).append("</option>");
            }
        }
        sb.append("</select></div></div>");
        sb.append(HTML_FORMS_INPUTS);

        // Station rooms dropdown from rooms.csv
        File roomsFile = new File(store.dataDir, "rooms.csv");
        if (roomsFile.exists()) {
            List<String[]> rList = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(roomsFile))) {
                String line;
                boolean isHeader = true;
                while ((line = br.readLine()) != null && rList.size() < 64) {
                    if (isHeader) { isHeader = false; continue; }
                    if (line.trim().isEmpty()) continue;
                    String[] p = line.split(",", 6);
                    String[] item = new String[6];
                    for (int i = 0; i < 6; i++) {
                        item[i] = (i < p.length) ? p[i].replace("\"", "").trim() : "";
                    }
                    rList.add(item);
                }
            } catch (Exception ignored) {}

            sb.append("<script>var rData = [");
            for (String[] r : rList) {
                sb.append(String.format("{id:'%s',loc:'%s',mod:'%s',ae:'%s',name:'%s',room:'%s'},",
                        r[0], r[1], r[2], r[3], r[4], r[5]));
            }
            sb.append("]; function updR() { var idx = document.getElementsByName('StationName')[0].selectedIndex - 1; if(idx>=0) { " +
                      "var r=rData[idx]; document.getElementsByName('Location')[0].value=r.room; " +
                      "document.getElementsByName('Modality')[0].value=r.mod; " +
                      "document.getElementsByName('StationAE')[0].value=r.ae; " +
                      "document.getElementsByName('SPSID')[0].value=r.ae; " +
                      "document.getElementsByName('ProcDesc')[0].value=r.name; } }</script>");

            sb.append("<select name='StationName' onchange='updR()'><option value=''>Station Name</option>");
            for (String[] r : rList) {
                sb.append("<option value=\"").append(r[4]).append("\">").append(r[4]).append("</option>");
            }
            sb.append("</select> <input type='text' name='Location' placeholder='Location'> ");
        } else {
            sb.append("<input type='text' name='StationName' placeholder='Station Name'> <input type='text' name='Location' placeholder='Location'> ");
        }

        sb.append(HTML_FORMS_2);
        sb.append(HTML_END);
        return sb.toString();
    }

    private void sendHttpResponse(OutputStream out, String status, String contentType, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String hdr = "HTTP/1.1 " + status + "\r\n" +
                     "Content-Type: " + contentType + "\r\n" +
                     "Content-Length: " + bodyBytes.length + "\r\n" +
                     "Connection: close\r\n\r\n";
        out.write(hdr.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

    private int findHeaderEnd(byte[] buf) {
        for (int i = 0; i <= buf.length - 4; i++) {
            if (buf[i] == '\r' && buf[i+1] == '\n' && buf[i+2] == '\r' && buf[i+3] == '\n') {
                return i;
            }
        }
        return -1;
    }
}