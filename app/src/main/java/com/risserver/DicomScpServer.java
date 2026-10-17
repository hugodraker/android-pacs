/* ============================================================================
 * DicomScpServer.java
 * RIS Multi-Protocol Server (DICOM + Telnet + HTTP) - ANDROID VERSION
 *
 * THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
 * AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
 *
 * 2026-11-17 Hugo
 * ============================================================================ */

package com.hugo.risserver;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DicomScpServer {private final RisDataStore store;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    // DICOM VR types
    private static final Set<String> KNOWN_VRS = new HashSet<>(Arrays.asList(
            "DA","TM","CS","SH","LO","PN","UI","AE","AS","IS","DS","ST","LT","UT",
            "OB","OW","SQ","UN","FD","FL","SL","SS","UL","US","OD","OL","OV","OF"
    ));
    private static final Set<String> LONG_VRS = new HashSet<>(Arrays.asList("OB","OW","OF","SQ","UT","UN"));

    // Command VR hints for Group 0x0000 elements (implicit VR only in command datasets)
    private static final Map<Integer, String> COMMAND_VRS = new HashMap<>();
    static {
        COMMAND_VRS.put(0x00000000, "UL");
        COMMAND_VRS.put(0x00000002, "UI");
        COMMAND_VRS.put(0x00000100, "US");
        COMMAND_VRS.put(0x00000110, "US");
        COMMAND_VRS.put(0x00000120, "US");
        COMMAND_VRS.put(0x00000700, "US");
        COMMAND_VRS.put(0x00000800, "US");
        COMMAND_VRS.put(0x00000900, "US");
        COMMAND_VRS.put(0x00000902, "LO");
        COMMAND_VRS.put(0x00001000, "UI");
        COMMAND_VRS.put(0x00001001, "UI");
    }

    // Per-connection state
    private ByteArrayOutputStream findCmd = new ByteArrayOutputStream();
    private ByteArrayOutputStream findData = new ByteArrayOutputStream();
    private int findPcId = 0;
    private int findMsgId = 0;
    private int findCommand = 0;
    private String findSopClassUid = "";
    private static final int MAX_STORE_BYTES = 96 * 1024 * 1024; // 96MB limit

    public DicomScpServer(RisDataStore store) {
        this.store = store;
    }

    public void start() {
        running = true;
        new Thread(this::acceptLoop, "DicomScpThread").start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            try {
                serverSocket.bind(new InetSocketAddress("0.0.0.0", store.dicomPort));
            } catch (BindException e) {
                if (store.dicomPort < 1024) {
                    store.writeLog("Port " + store.dicomPort + " restricted on Android; falling back to 1104");
                    store.dicomPort = 1104;
                    serverSocket.bind(new InetSocketAddress("0.0.0.0", store.dicomPort));
                    store.notifyStatusChanged();
                } else {
                    throw e;
                }
            }
            store.writeLog("DICOM SCP listening on port " + store.dicomPort);
            store.notifyStatusChanged();

            while (running && !serverSocket.isClosed()) {
                Socket client = serverSocket.accept();
                store.writeLog("DICOM SCU connected.");
                new Thread(() -> handleClient(client), "DicomClientWorker").start();
            }
        } catch (IOException e) {
            if (running) store.writeLog("ERROR: Failed to listen on DICOM port " + store.dicomPort);
        }
    }

    private void handleClient(Socket client) {
        try (DataInputStream in = new DataInputStream(client.getInputStream());
             OutputStream out = client.getOutputStream()) {

            findCmd.reset();
            findData.reset();
            findPcId = 0;
            findMsgId = 0;
            findCommand = 0;
            findSopClassUid = "";

            while (running) {
                byte[] hdr = new byte[6];
                try {
                    in.readFully(hdr);
                } catch (IOException eof) {
                    break;
                }

                int pduType = hdr[0] & 0xFF;
                int pduLen = ((hdr[2] & 0xFF) << 24) |
                             ((hdr[3] & 0xFF) << 16) |
                             ((hdr[4] & 0xFF) << 8)  |
                              (hdr[5] & 0xFF);

                if (pduLen < 0 || pduLen > 131072) break;
                byte[] pdu = new byte[6 + pduLen];
                System.arraycopy(hdr, 0, pdu, 0, 6);
                in.readFully(pdu, 6, pduLen);

                switch (pduType) {
                    case 0x01:
                        handleAssociationRq(out, pdu);
                        break;
                    case 0x04: {
                        int pos = 6;
                        int end = pdu.length;
                        while (pos + 6 <= end) {
                            int pdvLen = ((pdu[pos] & 0xFF) << 24) |
                                         ((pdu[pos + 1] & 0xFF) << 16) |
                                         ((pdu[pos + 2] & 0xFF) << 8)  |
                                          (pdu[pos + 3] & 0xFF);
                            int pcId = pdu[pos + 4] & 0xFF;
                            int msgCtrl = pdu[pos + 5] & 0xFF;
                            int dataOff = pos + 6;
                            int dataLen = pdvLen - 2;
                            if (dataLen < 0 || dataOff + dataLen > end) break;

                            boolean isCommand = (msgCtrl & 0x01) != 0;
                            boolean isLastFrag = (msgCtrl & 0x02) != 0;

                            if (isCommand) {
                                findCmd.write(pdu, dataOff, dataLen);
                                findPcId = pcId;
                                if (isLastFrag) {
                                    byte[] cBuf = findCmd.toByteArray();
                                    Map<Integer, String> ce = new HashMap<>();
                                    walkElements(cBuf, 0, cBuf.length, true, ce, 0);
                                    findCommand = parseIntOr(ce.get(0x00000100), 0);
                                    findMsgId = parseIntOr(ce.get(0x00000120), 0);
                                    findSopClassUid = ce.getOrDefault(0x00000002, "");
                                    findCmd.reset();
                                    if (findCommand == 0x0030) {
                                        handleCEchoRq(out, findPcId, findMsgId);
                                        findData.reset();
                                        findCommand = 0;
                                    }
                                }
                            } else {
                                if (findData.size() + dataLen > MAX_STORE_BYTES) {
                                    store.writeLog("WARN: dataset exceeds " + MAX_STORE_BYTES + " bytes, discarding");
                                    findCmd.reset();
                                    findData.reset();
                                    findCommand = 0;
                                    pos += 4 + pdvLen;
                                    continue;
                                }
                                
                                findData.write(pdu, dataOff, dataLen);
                                if (isLastFrag) {
                                    byte[] dataBytes = findData.toByteArray();
                                    if (findCommand == 0x0020) {
                                        handleCFindRq(out, findPcId, findMsgId, dataBytes);
                                    } else if (findCommand == 0x0001) {
                                        handleCStoreRq(out, findPcId, findMsgId, dataBytes);
                                    }
                                    findCmd.reset();
                                    findData.reset();
                                    findCommand = 0;
                                }
                            }
                            pos += 4 + pdvLen;
                        }
                        break;
                    }
                    case 0x05: {
                        byte[] relRp = { 0x06, 0x00, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00 };
                        out.write(relRp);
                        out.flush();
                        return;
                    }
                    case 0x02: {
                        byte[] abortPdu = { 0x03, 0x00, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x02 };
                        out.write(abortPdu);
                        out.flush();
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private void handleAssociationRq(OutputStream out, byte[] buffer) throws IOException {
        store.writeLog("Parsing A-ASSOCIATE-RQ...");
        if (buffer.length < 42) return;

        byte[] acPdu = new byte[2048];
        acPdu[0] = 0x02;
        acPdu[1] = 0x00;
        acPdu[6] = 0x00;
        acPdu[7] = 0x01;
        System.arraycopy(buffer, 10, acPdu, 10, 16);
        System.arraycopy(buffer, 26, acPdu, 26, 16);

        int offset = 74;
        byte[] appCtxHdr = { 0x10, 0x00, 0x00, 0x15 };
        byte[] appCtxUid = "1.2.840.10008.3.1.1.1".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(appCtxHdr, 0, acPdu, offset, 4); offset += 4;
        System.arraycopy(appCtxUid, 0, acPdu, offset, appCtxUid.length); offset += appCtxUid.length;

        int i = 74;
        while (i < buffer.length - 4) {
            int itemType = buffer[i] & 0xFF;
            int itemLen = ((buffer[i + 2] & 0xFF) << 8) | (buffer[i + 3] & 0xFF);
            if (itemType == 0x20 && i + 4 + itemLen <= buffer.length) {
                store.writeLog("Accepting PC ID");
                byte pcId = buffer[i + 4];
                acPdu[offset++] = 0x21; acPdu[offset++] = 0x00;
                acPdu[offset++] = 0x00; acPdu[offset++] = 0x19;
                acPdu[offset++] = pcId; acPdu[offset++] = 0x00;
                acPdu[offset++] = 0x00; acPdu[offset++] = 0x00;

                byte[] tsHdr = { 0x40, 0x00, 0x00, 0x11 };
                byte[] tsUid = "1.2.840.10008.1.2".getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(tsHdr, 0, acPdu, offset, 4); offset += 4;
                System.arraycopy(tsUid, 0, acPdu, offset, tsUid.length); offset += tsUid.length;
                
                byte[] tsHdrExt = { 0x40, 0x00, 0x00, 0x15 };
                byte[] tsUidExt = "1.2.840.10008.1.2.1".getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(tsHdrExt, 0, acPdu, offset, 4); offset += 4;
                System.arraycopy(tsUidExt, 0, acPdu, offset, tsUidExt.length); offset += tsUidExt.length;
            }
            i += 4 + itemLen;
        }

        byte[] userInfo = {
                0x50, 0x00, 0x00, 0x1E,
                0x51, 0x00, 0x00, 0x04, 0x00, 0x00, 0x40, 0x00,
                0x52, 0x00, 0x00, 0x12,
                '1','.','2','.','2','7','6','.','0','.','7','2','3','.','1','.','9','9'
        };
        System.arraycopy(userInfo, 0, acPdu, offset, userInfo.length);
        offset += userInfo.length;

        int pduLen = offset - 6;
        acPdu[2] = (byte) ((pduLen >> 24) & 0xFF);
        acPdu[3] = (byte) ((pduLen >> 16) & 0xFF);
        acPdu[4] = (byte) ((pduLen >> 8) & 0xFF);
        acPdu[5] = (byte) (pduLen & 0xFF);

        out.write(acPdu, 0, offset);
        out.flush();
    }

    private void handleCEchoRq(OutputStream out, int pcId, int msgId) throws IOException {
        ByteArrayOutputStream cmd = new ByteArrayOutputStream();
        dicomAddUl(cmd, 0x0000, 0x0000, 0);
        dicomAddStr(cmd, 0x0000, 0x0002, "1.2.840.10008.1.1");
        dicomAddUs(cmd, 0x0000, 0x0100, 0x8030);
        dicomAddUs(cmd, 0x0000, 0x0120, msgId);
        dicomAddUs(cmd, 0x0000, 0x0800, 0x0101);
        dicomAddUs(cmd, 0x0000, 0x0900, 0x0000);

        byte[] cmdBytes = patchGroupLength(cmd.toByteArray());
        sendPDataTf(out, pcId, 0x03, cmdBytes);
    }

    private void handleCFindRq(OutputStream out, int pcId, int msgId, byte[] reqBuf) throws IOException {
        String qMod = dicomGetString(reqBuf, 0x0008, 0x0060);
        String qAET = dicomGetString(reqBuf, 0x0040, 0x0001);
        String qDate = dicomGetString(reqBuf, 0x0040, 0x0002);
        String qTime = dicomGetString(reqBuf, 0x0040, 0x0003);
        String qName = dicomGetString(reqBuf, 0x0010, 0x0010).replace("*", "").toUpperCase();

        List<String> rows = store.getCsvRowsSnapshot();
        for (int row = 1; row < rows.size(); row++) {
            String[] f = RisDataStore.parseCsvLineToEntry(rows.get(row));
            String patientName = f[0], patientID = f[1], dob = f[2], sex = f[3], procDesc = f[6];
            String accNum = f[8], procID = f[9], aeTitle = f[11], spsDate = f[12];
            String spsTime = f[13], spsDesc = f[15], spsID = f[16], status = f[19];
            String modality = f[20], refPhys = f[21], protoCode = f[23];
            String protoScheme = f[24], protoMeaning = f[25], studyUID = f[26];

            int statCode = 0;
            try { statCode = Integer.parseInt(status.trim()); } catch (Exception ignored) {}
            if (statCode < 2 || statCode > 4) continue;

            if (!qMod.isEmpty() && !qMod.equals(modality)) continue;
            if (!qAET.isEmpty() && !qAET.equals(aeTitle)) continue;
            
            if (!qName.isEmpty() && !patientName.toUpperCase().contains(qName)) continue;

            if (!qDate.isEmpty()) {
                int dash = qDate.indexOf('-');
                if (dash >= 0) {
                    String d1 = qDate.substring(0, dash);
                    String d2 = qDate.substring(dash + 1);
                    if (!d1.isEmpty() && spsDate.compareTo(d1) < 0) continue;
                    if (!d2.isEmpty() && spsDate.compareTo(d2) > 0) continue;
                } else if (!spsDate.equals(qDate)) {
                    continue;
                }
            }

            if (!qTime.isEmpty()) {
                int dash = qTime.indexOf('-');
                if (dash >= 0) {
                    String t1 = qTime.substring(0, dash);
                    String t2 = qTime.substring(dash + 1);
                    if (!t1.isEmpty() && spsTime.compareTo(t1) < 0) continue;
                    if (!t2.isEmpty() && spsTime.compareTo(t2) > 0) continue;
                } else if (!spsTime.startsWith(qTime)) {
                    continue;
                }
            }

            ByteArrayOutputStream cmd = new ByteArrayOutputStream();
            dicomAddUl(cmd, 0x0000, 0x0000, 0);
            dicomAddStr(cmd, 0x0000, 0x0002, "1.2.840.10008.5.1.4.31");
            dicomAddUs(cmd, 0x0000, 0x0100, 0x8020);
            dicomAddUs(cmd, 0x0000, 0x0120, msgId);
            dicomAddUs(cmd, 0x0000, 0x0800, 0x0102);
            dicomAddUs(cmd, 0x0000, 0x0900, 0xFF00);
            sendPDataTf(out, pcId, 0x03, patchGroupLength(cmd.toByteArray()));

            ByteArrayOutputStream data = new ByteArrayOutputStream();
            if (!isFieldOmitted(0x0008, 0x0050)) dicomAddStr(data, 0x0008, 0x0050, accNum);
            if (!isFieldOmitted(0x0008, 0x0090)) dicomAddStr(data, 0x0008, 0x0090, refPhys);
            if (!isFieldOmitted(0x0010, 0x0010)) dicomAddStr(data, 0x0010, 0x0010, patientName);
            if (!isFieldOmitted(0x0010, 0x0020)) dicomAddStr(data, 0x0010, 0x0020, patientID);
            if (!isFieldOmitted(0x0010, 0x0030)) dicomAddStr(data, 0x0010, 0x0030, dob);
            if (!isFieldOmitted(0x0010, 0x0040)) dicomAddStr(data, 0x0010, 0x0040, sex);
            if (!isFieldOmitted(0x0020, 0x000D)) dicomAddStr(data, 0x0020, 0x000D, studyUID);
            if (!isFieldOmitted(0x0032, 0x1060)) dicomAddStr(data, 0x0032, 0x1060, procDesc);

            dicomSeqHdr(data, 0x0040, 0x0100);
            dicomItemHdr(data);
            if (!isFieldOmitted(0x0008, 0x0060)) dicomAddStr(data, 0x0008, 0x0060, modality);
            if (!isFieldOmitted(0x0040, 0x0001)) dicomAddStr(data, 0x0040, 0x0001, aeTitle);
            if (!isFieldOmitted(0x0040, 0x0002)) dicomAddStr(data, 0x0040, 0x0002, spsDate);
            if (!isFieldOmitted(0x0040, 0x0003)) dicomAddStr(data, 0x0040, 0x0003, spsTime);
            if (!isFieldOmitted(0x0040, 0x0007)) dicomAddStr(data, 0x0040, 0x0007, spsDesc);

            dicomSeqHdr(data, 0x0040, 0x0008);
            dicomItemHdr(data);
            if (!isFieldOmitted(0x0008, 0x0100)) dicomAddStr(data, 0x0008, 0x0100, protoCode);
            if (!isFieldOmitted(0x0008, 0x0102)) dicomAddStr(data, 0x0008, 0x0102, protoScheme);
            if (!isFieldOmitted(0x0008, 0x0104)) dicomAddStr(data, 0x0008, 0x0104, protoMeaning);
            dicomItemDelim(data);
            dicomSeqDelim(data);

            if (!isFieldOmitted(0x0040, 0x0009)) dicomAddStr(data, 0x0040, 0x0009, spsID);
            dicomItemDelim(data);
            dicomSeqDelim(data);

            if (!isFieldOmitted(0x0040, 0x1001)) dicomAddStr(data, 0x0040, 0x1001, procID);

            sendPDataTf(out, pcId, 0x02, data.toByteArray());
        }

        ByteArrayOutputStream endCmd = new ByteArrayOutputStream();
        dicomAddUl(endCmd, 0x0000, 0x0000, 0);
        dicomAddStr(endCmd, 0x0000, 0x0002, "1.2.840.10008.5.1.4.31");
        dicomAddUs(endCmd, 0x0000, 0x0100, 0x8020);
        dicomAddUs(endCmd, 0x0000, 0x0120, msgId);
        dicomAddUs(endCmd, 0x0000, 0x0800, 0x0101);
        dicomAddUs(endCmd, 0x0000, 0x0900, 0x0000);
        sendPDataTf(out, pcId, 0x03, patchGroupLength(endCmd.toByteArray()));
    }

    private void handleCStoreRq(OutputStream out, int pcId, int msgId, byte[] ds) throws IOException {
        Map<Integer, String> el = new HashMap<>();
        walkElements(ds, 0, ds.length, false, el, 0);

        String sopClassUid = el.getOrDefault(0x00080016, findSopClassUid);
        String sopInstUid = el.getOrDefault(0x00080018, "");
        String modality = el.getOrDefault(0x00080060, "OT");
        String patId = el.getOrDefault(0x00100020, "");
        String patName = el.getOrDefault(0x00100010, "");
        String studyUid = el.getOrDefault(0x0020000D, "");
        String accNum = el.getOrDefault(0x00080050, "").trim();

        boolean isUnlinked = accNum.isEmpty();
        String effectiveAcc = accNum;

        if (isUnlinked) {
            effectiveAcc = store.generateNextUnlinkedAccession();
            store.writeLog("C-STORE: no accession, generated " + effectiveAcc);
            
            // Only change the header on upload if the accession is missing
            patchTagInPlace(ds, 0x0008, 0x0050, effectiveAcc);

            // Add basic row to worklist so the image isn't orphaned in the UI
            String[] newEntry = new String[RisDataStore.FIELD_COUNT];
            Arrays.fill(newEntry, "");
            newEntry[0] = patName.isEmpty() ? "Unknown Patient" : patName;
            newEntry[1] = patId.isEmpty() ? "UNKNOWN" : patId;
            newEntry[8] = effectiveAcc;
            newEntry[19] = "5"; // DEPARTED
            newEntry[20] = modality;
            newEntry[22] = "Unlinked Image Received";
            store.normalizeAndUpsertEntry(newEntry);
        }

        int status = 0x0000;
        String folder = "", filename = "";

        try {
            File dest = store.getImageFilePath(sopInstUid);
            if (dest != null) {
                if (!dest.getParentFile().exists()) dest.getParentFile().mkdirs();
                folder = dest.getParentFile().getName();
                filename = dest.getName();
                store.writeLog("C-STORE: Overwriting existing SOP: " + sopInstUid);
            } else {
                dest = store.allocateImageFile();
                folder = dest.getParentFile().getName();
                filename = dest.getName();
            }

            try (FileOutputStream fos = new FileOutputStream(dest)) {
                fos.write(ds);
            }

            java.text.SimpleDateFormat tsFmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            store.recordStoredImage(effectiveAcc, patName, patId, studyUid, sopInstUid,
                                   folder, filename, modality, dest.length(), tsFmt.format(new Date()));

            store.getImageCountForAccession(effectiveAcc);
            store.writeLog("C-STORE OK -> " + folder + "/" + filename + " (" + ds.length + " B) mod=" + modality +
                          " pid=" + patId + " acc=" + effectiveAcc);
        } catch (Exception ex) {
            status = 0xA700;
            store.writeLog("C-STORE FAILED: " + ex.getMessage());
        }

        ByteArrayOutputStream cmd = new ByteArrayOutputStream();
        dicomAddUl(cmd, 0x0000, 0x0000, 0);
        dicomAddStr(cmd, 0x0000, 0x0002, sopClassUid.isEmpty() ? "1.2.840.10008.5.1.4.1.1.2" : sopClassUid);
        dicomAddUs(cmd, 0x0000, 0x0100, 0x8001);
        dicomAddUs(cmd, 0x0000, 0x0120, msgId);
        dicomAddUs(cmd, 0x0000, 0x0800, 0x0102);
        dicomAddUs(cmd, 0x0000, 0x0900, status);
        if (!sopInstUid.isEmpty()) dicomAddStr(cmd, 0x0000, 0x1000, sopInstUid);

        sendPDataTf(out, pcId, 0x03, patchGroupLength(cmd.toByteArray()));
    }
/* 
     * ========================================================================
     * COMMENTED OUT: Header rewrites are disabled so images are stored
     * exactly as received via network streams. 
     * 
     * Implementation Class UID Examples (Tag 0002,0012):
     * - DCMTK / OFFIS:      "1.2.276.0.7230010.3.0.3.6.4"
     * - dcm4che:            "1.2.40.0.13.1.3"
     * - ClearCanvas:        "1.2.826.0.1.3680043.2.109.3"
     * - OsiriX:             "1.2.276.0.7230010.3.0.3.6.1"
     * - Merge Healthcare:   "1.2.840.113674.514.2"
     * - Previous RIS Server:"1.2.276.0.723.1.99"
     * ========================================================================
     *
    private void writePart10(File dest, byte[] ds, String sopClassUid, String sopInstUid) throws IOException {
        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        meta.write(new byte[]{0x02, 0x00, 0x00, 0x00, 'U', 'L', 0x04, 0x00, 0x00, 0x00, 0x00, 0x00});
        writeMetaElement(meta, 0x0002, 0x0002, "UI", (sopClassUid.isEmpty() ? "1.2.840.10008.5.1.4.1.1.2" : sopClassUid).getBytes(StandardCharsets.US_ASCII));
        writeMetaElement(meta, 0x0002, 0x0003, "UI", (sopInstUid.isEmpty() ? "1.2.840.10008.1.2.0" : sopInstUid).getBytes(StandardCharsets.US_ASCII));
        writeMetaElement(meta, 0x0002, 0x0010, "UI", "1.2.840.10008.1.2".getBytes(StandardCharsets.US_ASCII));
        writeMetaElement(meta, 0x0002, 0x0012, "UI", "1.2.276.0.723.1.99".getBytes(StandardCharsets.US_ASCII));
        byte[] m = meta.toByteArray();
        int gl = m.length - 12;
        m[8] = (byte)(gl & 0xFF); m[9] = (byte)((gl >> 8) & 0xFF); m[10] = (byte)((gl >> 16) & 0xFF); m[11] = (byte)((gl >> 24) & 0xFF);
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
            byte[] preamble = new byte[128];
            fos.write(preamble); fos.write("DICM".getBytes(StandardCharsets.US_ASCII)); fos.write(m); fos.write(ds);
        }
    }

    private void writeMetaElement(ByteArrayOutputStream out, int g, int e, String vr, byte[] v) {
        out.write(g & 0xFF); out.write((g >> 8) & 0xFF); out.write(e & 0xFF); out.write((e >> 8) & 0xFF);
        out.write(vr.charAt(0)); out.write(vr.charAt(1));
        int pad = v.length % 2; int totalLen = v.length + pad;
        if (LONG_VRS.contains(vr)) {
            out.write(0); out.write(0); out.write(totalLen & 0xFF); out.write((totalLen >> 8) & 0xFF); out.write((totalLen >> 16) & 0xFF); out.write((totalLen >> 24) & 0xFF);
        } else { out.write(totalLen & 0xFF); out.write((totalLen >> 8) & 0xFF); }
        out.write(v, 0, v.length); if (pad == 1) out.write(0); 
    }
    */

    public static boolean patchTagInPlace(byte[] buf, int g, int e, String newValue) {
        if (newValue == null || newValue.isEmpty()) return false;
        byte tg0 = (byte) (g & 0xFF), tg1 = (byte) ((g >> 8) & 0xFF);
        byte tg2 = (byte) (e & 0xFF), tg3 = (byte) ((e >> 8) & 0xFF);

        for (int i = 0; i <= buf.length - 10; i++) {
            if (buf[i] == tg0 && buf[i+1] == tg1 && buf[i+2] == tg2 && buf[i+3] == tg3) {
                char v1 = (char) (buf[i+4] & 0xFF);
                char v2 = (char) (buf[i+5] & 0xFF);
                String vr = "" + v1 + v2;
                int vlen, offset;
                
                boolean isKnownVR = (v1 >= 'A' && v1 <= 'Z' && v2 >= 'A' && v2 <= 'Z');

                if (isKnownVR && KNOWN_VRS.contains(vr)) {
                    if (vr.equals("OB") || vr.equals("OW") || vr.equals("SQ") || vr.equals("UN") || vr.equals("OF") || vr.equals("UT")) {
                        continue; 
                    } else {
                        vlen = (buf[i+6] & 0xFF) | ((buf[i+7] & 0xFF) << 8);
                        offset = 8;
                    }
                } else {
                    vlen = (buf[i+4] & 0xFF) | ((buf[i+5] & 0xFF) << 8) |
                           ((buf[i+6] & 0xFF) << 16) | ((buf[i+7] & 0xFF) << 24);
                    offset = 8;
                }

                if (vlen > 0 && vlen < 2048 && i + offset + vlen <= buf.length) {
                    byte[] newBytes = newValue.getBytes(StandardCharsets.UTF_8);
                    boolean changed = false;
                    for (int j = 0; j < vlen; j++) {
                        byte b = (j < newBytes.length) ? newBytes[j] : (byte) ' '; 
                        if (buf[i + offset + j] != b) {
                            buf[i + offset + j] = b;
                            changed = true;
                        }
                    }
                    return changed; 
                }
            }
        }
        return false;
    }

    private static int walkElements(byte[] b, int start, int end, boolean forceImplicit,
                                    Map<Integer, String> out, int depth) {
        if (depth > 8) return end;
        int pos = start;
        while (pos + 8 <= end) {
            int g = u16(b, pos);
            int e = u16(b, pos + 2);

            if (g == 0xFFFE) {
                int len = i32(b, pos + 4);
                if (e == 0xE000) { 
                    if (len == -1) {
                        pos = walkElements(b, pos + 8, end, forceImplicit, out, depth + 1);
                    } else {
                        int itemEnd = Math.min(pos + 8 + len, end);
                        pos = walkElements(b, pos + 8, itemEnd, forceImplicit, out, depth + 1);
                    }
                } else if (e == 0xE00D || e == 0xE0DD) { 
                    return pos + 8;
                } else {
                    pos += 8;
                }
                continue;
            }

            int key = (g << 16) | e;
            String vr = null;
            boolean explicit = !forceImplicit;

            if (explicit && pos + 6 <= end) {
                String cand = "" + (char)(b[pos + 4] & 0xFF) + (char)(b[pos + 5] & 0xFF);
                if (KNOWN_VRS.contains(cand)) {
                    vr = cand;
                } else {
                    explicit = false;
                }
            }

            int len, valOff;
            if (explicit && vr != null) {
                if (LONG_VRS.contains(vr)) {
                    len = i32(b, pos + 8);
                    valOff = pos + 12;
                } else {
                    len = u16(b, pos + 6);
                    valOff = pos + 8;
                }
            } else {
                len = i32(b, pos + 4);
                valOff = pos + 8;
                if (g == 0x0000) {
                    vr = COMMAND_VRS.get(key);
                }
            }

            if (len == -1) {
                pos = walkElements(b, valOff, end, forceImplicit, out, depth + 1);
                continue;
            }
            if (len < 0 || valOff + len > end) return end;

            if ("SQ".equals(vr)) {
                pos = walkElements(b, valOff, valOff + len, forceImplicit, out, depth + 1);
            } else if (!out.containsKey(key)) {
                String s = decodeValue(b, valOff, len, vr);
                if (s != null) out.put(key, s);
                pos = valOff + len;
            } else {
                pos = valOff + len;
            }
        }
        return pos;
    }

    private static String decodeValue(byte[] b, int off, int len, String vr) {
        if (len <= 0) return null;
        if ("US".equals(vr)) return String.valueOf(u16(b, off));
        if ("SS".equals(vr)) return String.valueOf((short)(b[off] & 0xFF) << 8 | (b[off + 1] & 0xFF));
        if ("UL".equals(vr)) return String.valueOf(i32(b, off));
        if (len > 4096) return null; 
        return new String(b, off, len, StandardCharsets.UTF_8).replaceAll("[\\u0000 ]+$", "");
    }

    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    private static int i32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static int parseIntOr(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private boolean isFieldOmitted(int g, int e) {
        String omitted = store.omittedFields;
        if (omitted == null || omitted.isEmpty() || omitted.equals("0")) return false;
        String tagStr = String.format(Locale.US, "%04X%04X", g, e);
        for (String token : omitted.split("[, ]+")) {
            if (token.trim().equalsIgnoreCase(tagStr)) return true;
        }
        return false;
    }

    private String dicomGetString(byte[] buf, int g, int e) {
        byte tg0 = (byte) (g & 0xFF), tg1 = (byte) ((g >> 8) & 0xFF);
        byte tg2 = (byte) (e & 0xFF), tg3 = (byte) ((e >> 8) & 0xFF);

        for (int i = 0; i <= buf.length - 10; i++) {
            if (buf[i] == tg0 && buf[i+1] == tg1 && buf[i+2] == tg2 && buf[i+3] == tg3) {
                char v1 = (char) (buf[i+4] & 0xFF);
                char v2 = (char) (buf[i+5] & 0xFF);
                String vr = "" + v1 + v2;
                int vlen, offset;

                if (KNOWN_VRS.contains(vr)) {
                    if (vr.equals("OB") || vr.equals("OW") || vr.equals("SQ") || vr.equals("UN")) {
                        if (i + 12 > buf.length) break;
                        vlen = (buf[i+8] & 0xFF) | ((buf[i+9] & 0xFF) << 8) |
                               ((buf[i+10] & 0xFF) << 16) | ((buf[i+11] & 0xFF) << 24);
                        offset = 12;
                    } else {
                        vlen = (buf[i+6] & 0xFF) | ((buf[i+7] & 0xFF) << 8);
                        offset = 8;
                    }
                } else {
                    vlen = (buf[i+4] & 0xFF) | ((buf[i+5] & 0xFF) << 8) |
                           ((buf[i+6] & 0xFF) << 16) | ((buf[i+7] & 0xFF) << 24);
                    offset = 8;
                }

                if (vlen > 0 && vlen < 128 && i + offset + vlen <= buf.length) {
                    String s = new String(buf, i + offset, vlen, StandardCharsets.UTF_8);
                    return s.replaceAll("[\\0 ]+$", "");
                }
            }
        }
        return "";
    }

    private void sendPDataTf(OutputStream out, int pcId, int msgCtrl, byte[] data) throws IOException {
        int pdvLen = 2 + data.length;
        int pduLen = 4 + pdvLen;
        byte[] pdu = new byte[6 + pduLen];
        pdu[0] = 0x04; pdu[1] = 0x00;
        pdu[2] = (byte) ((pduLen >> 24) & 0xFF);
        pdu[3] = (byte) ((pduLen >> 16) & 0xFF);
        pdu[4] = (byte) ((pduLen >> 8) & 0xFF);
        pdu[5] = (byte) (pduLen & 0xFF);
        pdu[6] = (byte) ((pdvLen >> 24) & 0xFF);
        pdu[7] = (byte) ((pdvLen >> 16) & 0xFF);
        pdu[8] = (byte) ((pdvLen >> 8) & 0xFF);
        pdu[9] = (byte) (pdvLen & 0xFF);
        pdu[10] = (byte) (pcId & 0xFF);
        pdu[11] = (byte) (msgCtrl & 0xFF);
        System.arraycopy(data, 0, pdu, 12, data.length);
        out.write(pdu);
        out.flush();
    }

    private void dicomSeqHdr(ByteArrayOutputStream out, int g, int e) {
        out.write(g & 0xFF); out.write((g >> 8) & 0xFF);
        out.write(e & 0xFF); out.write((e >> 8) & 0xFF);
        out.write(0xFF); out.write(0xFF); out.write(0xFF); out.write(0xFF);
    }

    private void dicomItemHdr(ByteArrayOutputStream out) {
        out.write(0xFE); out.write(0xFF); out.write(0x00); out.write(0xE0);
        out.write(0xFF); out.write(0xFF); out.write(0xFF); out.write(0xFF);
    }

    private void dicomItemDelim(ByteArrayOutputStream out) {
        out.write(0xFE); out.write(0xFF); out.write(0x0D); out.write(0xE0);
        out.write(0x00); out.write(0x00); out.write(0x00); out.write(0x00);
    }

    private void dicomSeqDelim(ByteArrayOutputStream out) {
        out.write(0xFE); out.write(0xFF); out.write(0xDD); out.write(0xE0);
        out.write(0x00); out.write(0x00); out.write(0x00); out.write(0x00);
    }

    private byte[] patchGroupLength(byte[] cmdBytes) {
        int gl = cmdBytes.length - 12;
        cmdBytes[8] = (byte) (gl & 0xFF);
        cmdBytes[9] = (byte) ((gl >> 8) & 0xFF);
        cmdBytes[10] = (byte) ((gl >> 16) & 0xFF);
        cmdBytes[11] = (byte) ((gl >> 24) & 0xFF);
        return cmdBytes;
    }

    private void dicomAddStr(ByteArrayOutputStream out, int g, int e, String s) {
        byte[] strBytes = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        int pad = (strBytes.length % 2 != 0) ? 1 : 0;
        int tot = strBytes.length + pad;
        out.write(g & 0xFF); out.write((g >> 8) & 0xFF);
        out.write(e & 0xFF); out.write((e >> 8) & 0xFF);
        out.write(tot & 0xFF); out.write((tot >> 8) & 0xFF);
        out.write((tot >> 16) & 0xFF); out.write((tot >> 24) & 0xFF);
        out.write(strBytes, 0, strBytes.length);
        if (pad == 1) out.write(' ');
    }

    private void dicomAddUs(ByteArrayOutputStream out, int g, int e, int v) {
        out.write(g & 0xFF); out.write((g >> 8) & 0xFF);
        out.write(e & 0xFF); out.write((e >> 8) & 0xFF);
        out.write(2); out.write(0); out.write(0); out.write(0);
        out.write(v & 0xFF); out.write((v >> 8) & 0xFF);
    }

    private void dicomAddUl(ByteArrayOutputStream out, int g, int e, int v) {
        out.write(g & 0xFF); out.write((g >> 8) & 0xFF);
        out.write(e & 0xFF); out.write((e >> 8) & 0xFF);
        out.write(4); out.write(0); out.write(0); out.write(0);
        out.write(v & 0xFF); out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF); out.write((v >> 24) & 0xFF);
    }
}