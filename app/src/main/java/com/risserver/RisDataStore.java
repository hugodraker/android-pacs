/* ============================================================================
 * RIS Multi-Protocol Server (DICOM + Telnet + HTTP) - ANDROID VERSION
 *
 * THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
 * AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
 *
 * 2026-10-11 Hugo
 *
 * ============================================================================ */

package com.hugo.risserver;

import android.content.Context;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class RisDataStore {
    public static final int FIELD_COUNT = 27;
    public static final int MAX_CSV_ROWS = 16384;
    public static final int MAX_PROC_CODES = 256;

    public static final String CSV_HEADER =
            "PatientName,PatientID,BirthDate,Sex,ReqPhys,ReqSvc,ProcDesc,Reason,Accession," +
            "ProcID,Priority,StationAE,StartDate,StartTime,PerfPhys,SPSDesc,SPSID,StationName," +
            "Location,Status,Modality,RefPhys,StudyDesc,ProtoCode,ProtoScheme,ProtoMeaning,StudyUID";

    // ===== UNLINKED IMAGES PATIENT =====
    // All images arriving without an Accession Number (0008,0050) are filed under this
    // single worklist row so their count rolls up and stays visible in the web UI.
    public static final String UNLINKED_PATIENT_NAME = "Unlinked Images";
    public static final String UNLINKED_PATIENT_ID = "UNLINKED-001";
    public static final String UNLINKED_ACCESSION = "UNLINKED-000";
    public static final String UNLINKED_ACCESSION_PREFIX = "UNLINKED-";

    // ===== C-STORE TRACKING CONSTANTS =====
    public static final int MAX_FILES_PER_FOLDER = 10_000_000; // I0000000..I9999999
    public static final String CSTORE_CSV_NAME = "c-store.csv";

    public interface LogListener {
        void onLogUpdated(String fullLog);
        void onStatusChanged();
    }

    public static class ProcCodeEntry {
        public String id = "";
        public String procCode = "";
        public String modality = "";
        public String name = "";
    }

    public static class StoredImageInfo {
        public String accession = "";
        public String patientName = "";
        public String patientID = "";
        public String studyUID = "";
        public String sopInstanceUID = "";
        public String folder = "";
        public String filename = "";
        public String modality = "";
        public long fileSize = 0;
        public String timestamp = "";
    }

    public File dataDir;
    public File iniFile;
    public File logFile;
    public File cStoreFile;

    // Server Configuration (Ports default to unprivileged 2323/8080/1104 if 23/80/104 fail on Android)
    public String aeCalled = "PACS";
    public int telnetPort = 2323;
    public int httpPort = 8080;
    public int dicomPort = 1104;
    public int telnetTimeout = 10;
    public int debugLog = 1;
    public String delPwd = "0";
    public String omittedFields = "0";

    // DICOM Storage Configuration
    public String storageRootName = "dicomstore";
    public String storageFolder = "D0000000";
    public int storageIndex = 0;

    // In-memory CSV Worklist Store
    private final List<String> csvData = new ArrayList<>();
    private boolean csvInitialized = false;

    // Procedure Codes Store
    private final Map<String, Integer> accessionImageCount = new HashMap<>();
    public final List<ProcCodeEntry> procCodes = new ArrayList<>();
    public boolean procCodesLoaded = false;

    // Patient Directory Store (patients.csv)
    private String patientsCsvData = null;
    private int ptsNameColIdx = -1;
    private int ptsIdColIdx = 0;
    private int ptsBirthdateColIdx = -1;
    private int ptsSexColIdx = -1;

    // Console Log Buffer
    private final List<String> logBuffer = new ArrayList<>();
    private LogListener logListener;
    private final Random random = new Random();

public RisDataStore(Context context) {
    dataDir = context.getExternalFilesDir(null);
    if (dataDir == null) {
        dataDir = context.getFilesDir();
    }
    if (!dataDir.exists()) {
        dataDir.mkdirs();
    }

    iniFile = new File(dataDir, "RIS_SERVER.ini");
    logFile = new File(dataDir, "ris_server.log");
    cStoreFile = new File(dataDir, CSTORE_CSV_NAME);

    loadConfig();
    ensureCsvInitialized();
    resetWorklistToHeader();      // also seeds the Unlinked Images row
    loadProcedureCodes();
    loadCStoreTracking();
}

//public RisDataStore(Context context) {
    // Target a public shared folder on the primary shared storage (accessible to other apps)
 //   File publicDir = android.os.Environment.getExternalStorageDirectory();
  //  dataDir = new File(publicDir, "RisServerData");

  //  if (!dataDir.exists()) {
  //      dataDir.mkdirs();
  //  }

  //  iniFile = new File(dataDir, "RIS_SERVER.ini");
  //  logFile = new File(dataDir, "ris_server.log");
  //  cStoreFile = new File(dataDir, CSTORE_CSV_NAME);

  //  loadConfig();
  //  ensureCsvInitialized();
 //   resetWorklistToHeader();
 //   loadProcedureCodes();
 //   loadCStoreTracking();
//}

    public void setLogListener(LogListener listener) {
        this.logListener = listener;
    }

    public synchronized String getLogsText() {
        StringBuilder sb = new StringBuilder();
        for (String s : logBuffer) sb.append(s).append("\n");
        return sb.toString();
    }

    public synchronized void clearLogs() {
        logBuffer.clear();
        if (logListener != null) logListener.onLogUpdated("");
    }

    public synchronized void writeLog(String msg) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        String line = "[" + sdf.format(new Date()) + "] " + msg.replaceAll("[\\r\\n]+$", "");

        if (debugLog == 1) {
            try (FileWriter fw = new FileWriter(logFile, true)) {
                fw.write(line + "\n");
            } catch (Exception ignored) {}
        }

        logBuffer.add(line);
        if (logBuffer.size() > 250) logBuffer.remove(0);

        if (logListener != null) {
            logListener.onLogUpdated(getLogsText());
        }
    }

    public void notifyStatusChanged() {
        if (logListener != null) logListener.onStatusChanged();
    }

    // ===== INI Configuration =====
    public void setAeTitle(String src) {
        if (src == null || src.trim().isEmpty()) src = "PACS";
        aeCalled = src.trim();
        if (aeCalled.length() > 31) aeCalled = aeCalled.substring(0, 31);
    }

    public synchronized void loadConfig() {
        if (!iniFile.exists()) {
            saveConfig();
            return;
        }
        Map<String, String> map = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(iniFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String k = line.substring(0, eq).trim().toLowerCase(Locale.US);
                    String v = line.substring(eq + 1).trim();
                    map.put(k, v);
                }
            }
            setAeTitle(map.getOrDefault("aetitle", "PACS"));
            delPwd = map.getOrDefault("deletepassword", "0").trim();
            omittedFields = map.getOrDefault("omittedfields", "0").trim();
            telnetPort = parseIntSafe(map.get("telnetport"), 2323);
            httpPort = parseIntSafe(map.get("httpport"), 8080);
            dicomPort = parseIntSafe(map.get("dicomport"), 1104);
            telnetTimeout = parseIntSafe(map.get("telnettimeout"), 10);
            debugLog = parseIntSafe(map.get("debuglog"), 1);

            // Storage config
            storageRootName = map.getOrDefault("storageroot", "dicomstore").trim();
            storageFolder = map.getOrDefault("storagefolder", "D0000000").trim();
            storageIndex = parseIntSafe(map.get("storageindex"), 0);
            if (!storageFolder.matches("D\\d{7}")) storageFolder = "D0000000";
            if (storageIndex < 0 || storageIndex > MAX_FILES_PER_FOLDER) storageIndex = 0;
        } catch (Exception e) {
            writeLog("Failed to read INI: " + e.getMessage());
        }
    }

    public synchronized void saveConfig() {
        String ini = "[Server]\r\n" +
                "AETitle=" + aeCalled + "\r\n" +
                "DeletePassword=" + delPwd + "\r\n" +
                "omittedfields=" + omittedFields + "\r\n" +
                "TelnetPort=" + telnetPort + "\r\n" +
                "HttpPort=" + httpPort + "\r\n" +
                "DicomPort=" + dicomPort + "\r\n" +
                "TelnetTimeout=" + telnetTimeout + "\r\n" +
                "DebugLog=" + debugLog + "\r\n" +
                "StorageRoot=" + storageRootName + "\r\n" +
                "StorageFolder=" + storageFolder + "\r\n" +
                "StorageIndex=" + storageIndex + "\r\n";
        try (FileOutputStream fos = new FileOutputStream(iniFile)) {
            fos.write(ini.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            writeLog("Failed to save INI: " + e.getMessage());
        }
    }

    private int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    // ===== Formatting Helpers =====
    public static String stripDashes(String s) {
        return s == null ? "" : s.replace("-", "");
    }

    public static String stripColons(String s) {
        if (s == null) return "";
        String res = s.replace(":", "");
        if (res.length() == 4) res += "00";
        return res;
    }

    public static String formatPatientName(String name) {
        if (name == null) return "";
        String pName = name.trim();
        if (pName.isEmpty() || pName.contains("^")) return pName;

        int comma = pName.indexOf(',');
        if (comma >= 0) {
            String last = pName.substring(0, comma).trim();
            String rest = pName.substring(comma + 1).trim().replaceAll("\\s+", "^");
            return (last + "^" + rest).replaceAll("\\^+$", "");
        } else {
            String[] parts = pName.split("\\s+");
            if (parts.length < 2) return pName;
            if (parts.length == 2) return parts[1] + "^" + parts[0];
            String last = parts[parts.length - 1];
            StringBuilder sb = new StringBuilder(last);
            for (int i = 0; i < parts.length - 1; i++) {
                sb.append("^").append(parts[i]);
            }
            return sb.toString();
        }
    }

    // ===== CSV Parsing & Worklist Store =====
    public static String[] parseCsvLineToEntry(String line) {
        String[] entry = new String[FIELD_COUNT];
        for (int i = 0; i < FIELD_COUNT; i++) entry[i] = "";
        if (line == null) return entry;

        int fieldIdx = 0;
        boolean quoteState = false;
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\r' || c == '\n') break;
            if (c == '~') c = '"';
            if (c == '"') {
                quoteState = !quoteState;
                continue;
            }
            if (c == ',' && !quoteState) {
                entry[fieldIdx] = sb.toString().trim();
                sb.setLength(0);
                fieldIdx++;
                if (fieldIdx >= FIELD_COUNT) break;
                continue;
            }
            sb.append(c);
        }
        if (fieldIdx < FIELD_COUNT) {
            entry[fieldIdx] = sb.toString().trim();
        }
        return entry;
    }

    public static boolean validateEntry(String[] entry) {
        return entry != null && entry.length > 8 && !entry[8].trim().isEmpty();
    }

    public static String buildCsvLineFromEntry(String[] entry) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FIELD_COUNT; i++) {
            if (i > 0) sb.append(',');
            String f = entry[i] == null ? "" : entry[i];
            boolean q = f.contains(",") || f.contains("\"");
            if (q) sb.append('"');
            for (int j = 0; j < f.length(); j++) {
                char c = f.charAt(j);
                if (q && c == '"') sb.append('"');
                sb.append(c);
            }
            if (q) sb.append('"');
        }
        return sb.toString();
    }

    public synchronized void ensureCsvInitialized() {
        if (csvInitialized) return;
        csvInitialized = true;
        csvData.clear();
        csvData.add(CSV_HEADER);
    }

    public synchronized void resetWorklistToHeader() {
        csvData.clear();
        csvData.add(CSV_HEADER);
        ensureUnlinkedPatientExists();
        notifyStatusChanged();
    }

    public synchronized List<String> getCsvRowsSnapshot() {
        ensureCsvInitialized();
        return new ArrayList<>(csvData);
    }

    public synchronized int getPatientRowCount() {
        return Math.max(0, csvData.size() - 1);
    }

    /**
     * Ensure the Unlinked Images row exists in the worklist.
     * Inserted with Status 5 (DEPARTED) so it is excluded from modality C-FIND results.
     */
    public synchronized void ensureUnlinkedPatientExists() {
        ensureCsvInitialized();

        // Already present?
        for (int i = 1; i < csvData.size(); i++) {
            String[] existing = parseCsvLineToEntry(csvData.get(i));
            if (UNLINKED_PATIENT_NAME.equals(existing[0])
                    && UNLINKED_PATIENT_ID.equals(existing[1])) {
                return;
            }
        }

        String[] entry = new String[FIELD_COUNT];
        entry[0] = UNLINKED_PATIENT_NAME;                 // PatientName
        entry[1] = UNLINKED_PATIENT_ID;                   // PatientID
        entry[2] = "";                                    // BirthDate
        entry[3] = "";                                    // Sex
        entry[4] = "";                                    // ReqPhys
        entry[5] = "";                                    // ReqSvc
        entry[6] = "";                                    // ProcDesc
        entry[7] = "";                                    // Reason
        entry[8] = UNLINKED_ACCESSION;                    // Accession
        entry[9] = "";                                    // ProcID
        entry[10] = "LOW";                                // Priority
        entry[11] = aeCalled;                             // StationAE
        entry[12] = formatDate8(new Date());              // StartDate YYYYMMDD
        entry[13] = "000000";                             // StartTime HHMMSS
        entry[14] = "";                                   // PerfPhys
        entry[15] = "Studies received without accession";  // SPSDesc
        entry[16] = UNLINKED_PATIENT_ID;                  // SPSID
        entry[17] = "";                                   // StationName
        entry[18] = "";                                   // Location
        entry[19] = "5";                                  // Status = DEPARTED
        entry[20] = "";                                   // Modality
        entry[21] = "";                                   // RefPhys
        entry[22] = "No Accession Number";                // StudyDesc
        entry[23] = "";                                   // ProtoCode
        entry[24] = "";                                   // ProtoScheme
        entry[25] = "";                                   // ProtoMeaning
        entry[26] = "1.2.840.113619.2.1.UNLINKED";        // StudyUID

        csvData.add(buildCsvLineFromEntry(entry));
        writeLog("Created 'Unlinked Images' worklist row (DEPARTED)");
        notifyStatusChanged();
    }

    private String formatDate8(Date d) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.US);
        return sdf.format(d);
    }

    /** True if the accession belongs to the Unlinked Images bucket. */
    public static boolean isUnlinkedAccession(String acc) {
        return acc != null && acc.trim().toUpperCase(Locale.US)
                .startsWith(UNLINKED_ACCESSION_PREFIX);
    }

    /**
     * Normalize and upsert entry. An empty accession is redirected into the
     * Unlinked Images bucket rather than being rejected.
     */
    public synchronized boolean normalizeAndUpsertEntry(String[] entry) {
        ensureCsvInitialized();
        entry[2] = stripDashes(entry[2]);
        entry[12] = stripDashes(entry[12]);
        entry[13] = stripColons(entry[13]);
        entry[0] = formatPatientName(entry[0]);

        // Empty accession -> Unlinked Images bucket
        String acc = entry[8];
        if (acc == null || acc.trim().isEmpty()) {
            entry[8] = UNLINKED_ACCESSION;
            entry[0] = UNLINKED_PATIENT_NAME;
            entry[1] = UNLINKED_PATIENT_ID;
            entry[22] = "No Accession Number";
            writeLog("Empty accession -> filed under Unlinked Images");
        }

        if (entry[26] == null || entry[26].isEmpty()) {
            long ticks = System.currentTimeMillis() & 0xFFFFFFFFL;
            int r = random.nextInt(10000);
            entry[26] = "1.2.840.113619.2.1." + ticks + "." + r;
        }

        String lineOut = buildCsvLineFromEntry(entry);
        acc = entry[8];
        boolean found = false;

        for (int i = 1; i < csvData.size(); i++) {
            String[] existing = parseCsvLineToEntry(csvData.get(i));
            if (existing[8].equals(acc)) {
                csvData.set(i, lineOut);
                found = true;
                break;
            }
        }
        if (!found && csvData.size() < MAX_CSV_ROWS) {
            csvData.add(lineOut);
        }
        notifyStatusChanged();
        return found;
    }

    public synchronized void deletePatientByAccession(String acc) {
        if (acc == null || acc.trim().isEmpty()) return;
        acc = acc.trim();

        // The Unlinked Images bucket is permanent
        if (isUnlinkedAccession(acc)) {
            writeLog("Refused deletion of Unlinked Images row");
            return;
        }

        for (int i = 1; i < csvData.size(); i++) {
            String[] existing = parseCsvLineToEntry(csvData.get(i));
            if (existing[8].equals(acc)) {
                csvData.remove(i);
                notifyStatusChanged();
                break;
            }
        }
    }

    // ===== C-STORE Tracking CSV =====

    /** Create the tracking CSV with a header only if it is missing or empty. */
    public synchronized void loadCStoreTracking() {
        if (!cStoreFile.exists() || cStoreFile.length() == 0) {
            writeCStoreEntry("Accession", "PatientName", "PatientID", "StudyUID", "SOPInstanceUID",
                             "Folder", "Filename", "Modality", "FileSize", "Timestamp");
        }
    }

    /** Append an entry to the c-store CSV */
    public synchronized boolean writeCStoreEntry(String... values) {
        if (values == null || values.length == 0) return false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            String v = values[i] == null ? "" : values[i];
            boolean q = v.contains(",") || v.contains("\"");
            if (q) sb.append('"');
            for (int j = 0; j < v.length(); j++) {
                char c = v.charAt(j);
                if (q && c == '"') sb.append('"');
                sb.append(c);
            }
            if (q) sb.append('"');
        }

        try (FileWriter fw = new FileWriter(cStoreFile, true)) {
            fw.write(sb.toString() + "\n");
        } catch (Exception e) {
            writeLog("Failed to write c-store tracking: " + e.getMessage());
            return false;
        }
        return true;
    }

    /** Record a successfully stored image */
    public synchronized boolean recordStoredImage(String accession, String patientName, String patientID,
                                                  String studyUID, String sopInstanceUID,
                                                  String folder, String filename,
                                                  String modality, long fileSize, String timestamp) {
        return writeCStoreEntry(accession, patientName, patientID, studyUID, sopInstanceUID,
                               folder, filename, modality, String.valueOf(fileSize), timestamp);
    }

    /** Get list of all stored images for a given accession ("" = all images) */
    public synchronized List<StoredImageInfo> getStoredImagesForAccession(String accession) {
        List<StoredImageInfo> result = new ArrayList<>();
        if (!cStoreFile.exists()) return result;

        String want = accession == null ? "" : accession.trim();

        try (BufferedReader br = new BufferedReader(new FileReader(cStoreFile))) {
            String line = br.readLine(); // skip header
            if (line == null) return result;

            while ((line = br.readLine()) != null) {
                String[] vals = parseCsvLineToEntry(line);
                if (vals.length < 9) continue;
                if (want.isEmpty() || vals[0].trim().equalsIgnoreCase(want)) {
                    StoredImageInfo info = new StoredImageInfo();
                    info.accession = vals[0];
                    info.patientName = vals[1];
                    info.patientID = vals[2];
                    info.studyUID = vals[3];
                    info.sopInstanceUID = vals[4];
                    info.folder = vals[5];
                    info.filename = vals[6];
                    info.modality = vals[7];
                    try { info.fileSize = Long.parseLong(vals[8]); } catch (Exception ignored) {}
                    info.timestamp = vals.length > 9 ? vals[9] : "";
                    result.add(info);
                }
            }
        } catch (Exception e) {
            writeLog("Failed to read c-store tracking: " + e.getMessage());
        }
        return result;
    }

    /** Get image count for an accession */
    public synchronized int getImageCountForAccession(String accession) {
        if (accession == null || accession.trim().isEmpty()) return 0;
        int count = getStoredImagesForAccession(accession).size();
        accessionImageCount.put(accession.trim(), count);
        return count;
    }

    /** Get total image count across all worklist entries */
    public synchronized int getTotalImageCount() {
        int total = 0;
        List<String> rows = getCsvRowsSnapshot();
        for (int i = 1; i < rows.size(); i++) {
            String[] f = parseCsvLineToEntry(rows.get(i));
            String acc = f.length > 8 ? f[8] : "";
            total += getImageCountForAccession(acc);
        }
        return total;
    }

    /** Get file path for an image by SOP instance UID */
    public synchronized File getImageFilePath(String sopInstanceUID) {
        for (StoredImageInfo info : getAllStoredImages()) {
            if (info.sopInstanceUID != null && info.sopInstanceUID.equals(sopInstanceUID)) {
                return new File(new File(getStorageRoot(), info.folder), info.filename);
            }
        }
        return null;
    }

    /** Get ALL stored images (for admin view) */
    public synchronized List<StoredImageInfo> getAllStoredImages() {
        return getStoredImagesForAccession("");
    }

    // ===== DICOM Storage Path Allocation =====

    public File getStorageRoot() {
        File root = new File(dataDir, storageRootName);
        if (!root.exists()) root.mkdirs();
        return root;
    }

    /** Returns the next free image file, rolling D0000000 -> D0000001 at I9999999. */
    public synchronized File allocateImageFile() {
        if (storageIndex >= MAX_FILES_PER_FOLDER) {
            int n = 0;
            try { n = Integer.parseInt(storageFolder.substring(1)); } catch (Exception ignored) {}
            storageFolder = String.format(Locale.US, "D%07d", n + 1);
            storageIndex = 0;
            writeLog("Storage folder rolled over to " + storageFolder);
            saveConfig();
        }
        File dir = new File(getStorageRoot(), storageFolder);
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, String.format(Locale.US, "I%07d", storageIndex));
        storageIndex++;
        saveConfig();
        return f;
    }

    /** Relative HTTP path for an image */
    public String getImageUrl(String folder, String filename) {
        return "/images/" + storageRootName + "/" + folder + "/" + filename;
    }

    // ===== Procedure Codes (procedurecodes.csv) =====
    public synchronized void loadProcedureCodes() {
        procCodes.clear();
        File f = new File(dataDir, "procedurecodes.csv");
        if (!f.exists()) {
            procCodesLoaded = true;
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null && procCodes.size() < MAX_PROC_CODES) {
                if (first) { first = false; continue; }
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",", 4);
                ProcCodeEntry pce = new ProcCodeEntry();
                if (parts.length > 0) pce.id = parts[0].trim();
                if (parts.length > 1) pce.procCode = parts[1].trim();
                if (parts.length > 2) pce.modality = parts[2].trim();
                if (parts.length > 3) pce.name = parts[3].trim();
                procCodes.add(pce);
            }
            procCodesLoaded = true;
            writeLog("Procedure codes loaded");
        } catch (Exception e) {
            procCodesLoaded = true;
        }
    }

    // ===== Patient Directory Filtering (patients.csv) =====
    private String loadAndConvertCsv(File file) {
        if (!file.exists()) return null;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int read = fis.read(buf);
            if (read <= 0) return null;

            if (read >= 3 && (buf[0] & 0xFF) == 0xEF && (buf[1] & 0xFF) == 0xBB && (buf[2] & 0xFF) == 0xBF) {
                return new String(buf, 3, read - 3, StandardCharsets.UTF_8);
            }
            if (read >= 2 && (buf[0] & 0xFF) == 0xFF && (buf[1] & 0xFF) == 0xFE) {
                return new String(buf, 2, read - 2, StandardCharsets.UTF_16LE);
            }
            if (read >= 2 && (buf[0] & 0xFF) == 0xFE && (buf[1] & 0xFF) == 0xFF) {
                return new String(buf, 2, read - 2, StandardCharsets.UTF_16BE);
            }
            return new String(buf, 0, read, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static String getCsvField(String line, int targetCol) {
        int currentCol = 0;
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length() && currentCol <= targetCol; i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                if (currentCol == targetCol) break;
                currentCol++;
            } else if (currentCol == targetCol) {
                sb.append(c);
            }
        }
        return currentCol == targetCol ? sb.toString().trim() : null;
    }

    private synchronized boolean initPatientsCsv() {
        if (patientsCsvData == null) {
            patientsCsvData = loadAndConvertCsv(new File(dataDir, "patients.csv"));
            if (patientsCsvData == null || patientsCsvData.isEmpty()) return false;

            String[] lines = patientsCsvData.split("\\r?\\n", 2);
            String header = lines[0].trim();
            int colIdx = 0;
            ptsNameColIdx = -1;
            while (colIdx < 50) {
                String f = getCsvField(header, colIdx);
                if (f == null || (f.isEmpty() && colIdx > 15)) break;
                String fl = f.toLowerCase(Locale.US);
                if (fl.contains("birth") || fl.contains("dob")) ptsBirthdateColIdx = colIdx;
                else if (fl.contains("sex") || fl.contains("gender")) ptsSexColIdx = colIdx;
                else if (fl.contains("id") || fl.contains("patid")) ptsIdColIdx = colIdx;
                else if ((fl.contains("name") || fl.contains("patient")) && ptsNameColIdx == -1) {
                    ptsNameColIdx = colIdx;
                }
                colIdx++;
            }
            if (ptsNameColIdx < 0) {
                ptsNameColIdx = 0;
                ptsIdColIdx = 1;
                ptsBirthdateColIdx = 2;
                ptsSexColIdx = 3;
            }
        }
        return true;
    }

    public synchronized String getPatientNamesJson(String filter, int maxCount) {
        if (!initPatientsCsv()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        String[] lines = patientsCsvData.split("\\r?\\n");
        int count = 0;
        String filterLower = filter == null ? "" : filter.toLowerCase(Locale.US);

        for (int i = 1; i < lines.length; i++) {
            if (maxCount > 0 && count >= maxCount) break;
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String name = getCsvField(line, ptsNameColIdx);
            if (name != null) {
                if (!filterLower.isEmpty() && !name.toLowerCase(Locale.US).contains(filterLower)) {
                    continue;
                }
                if (count > 0) sb.append(",");
                sb.append("\"").append(name.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
                count++;
            }
        }
        sb.append("]");
        return sb.toString();
    }

    public synchronized String getPatientDetailsJson(String patientName) {
        if (!initPatientsCsv() || patientName == null) return null;
        String[] lines = patientsCsvData.split("\\r?\\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String name = getCsvField(line, ptsNameColIdx);
            if (name != null && name.equalsIgnoreCase(patientName)) {
                String id = ptsIdColIdx >= 0 ? getCsvField(line, ptsIdColIdx) : "";
                String bd = ptsBirthdateColIdx >= 0 ? getCsvField(line, ptsBirthdateColIdx) : "";
                String sex = ptsSexColIdx >= 0 ? getCsvField(line, ptsSexColIdx) : "";
                return String.format(Locale.US,
                        "{\"patient_id\":\"%s\",\"name\":\"%s\",\"birthdate\":\"%s\",\"sex\":\"%s\"}",
                        escapeJson(id), escapeJson(name), escapeJson(bd), escapeJson(sex));
            }
        }
        return null;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}