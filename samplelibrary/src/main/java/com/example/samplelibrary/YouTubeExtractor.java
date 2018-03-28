package com.example.samplelibrary;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;

import com.evgenii.jsevaluator.JsEvaluator;
import com.evgenii.jsevaluator.interfaces.JsCallback;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class YouTubeExtractor extends AsyncTask<String, Void, SparseArray<YtFile>> {

    private final static boolean CACHING = true;

    protected static boolean LOGGING = false;

    private final static String LOG_TAG = "YouTubeExtractor";
    private final static String CACHE_FILE_NAME = "decipher_js_funct";
    private final static int DASH_PARSE_RETRIES = 5;

    private Context context;
    private String videoID;
    private String errMsg;
    private VideoMeta videoMeta;
    private boolean includeWebM = true;
    private boolean useHttp = false;
    private boolean parseDashManifest = false;

    private volatile String decipheredSignature;

    private static String decipherJsFileName;
    private static String decipherFunctions;
    private static String decipherFunctionName;

    private final Lock lock = new ReentrantLock();
    private final Condition jsExecuting = lock.newCondition();

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/40.0.2214.115 Safari/537.36";

    private static final Pattern patYouTubePageLink = Pattern.compile("(http|https)://(www\\.|m.|)youtube\\.com/watch\\?v=(.+?)( |\\z|&)");
    private static final Pattern patYouTubeShortLink = Pattern.compile("(http|https)://(www\\.|)youtu.be/(.+?)( |\\z|&)");

    private static final Pattern patDashManifest1 = Pattern.compile("dashmpd=(.+?)(&|\\z)");
    private static final Pattern patDashManifest2 = Pattern.compile("\"dashmpd\":\"(.+?)\"");
    private static final Pattern patDashManifestEncSig = Pattern.compile("/s/([0-9A-F|\\.]{10,}?)(/|\\z)");

    private static final Pattern patTitle = Pattern.compile("title=(.*?)(&|\\z)");
    private static final Pattern patAuthor = Pattern.compile("author=(.+?)(&|\\z)");
    private static final Pattern patChannelId = Pattern.compile("ucid=(.+?)(&|\\z)");
    private static final Pattern patLength = Pattern.compile("length_seconds=(\\d+?)(&|\\z)");
    private static final Pattern patViewCount = Pattern.compile("view_count=(\\d+?)(&|\\z)");

    private static final Pattern patHlsvp = Pattern.compile("hlsvp=(.+?)(&|\\z)");
    private static final Pattern patHlsItag = Pattern.compile("/itag/(\\d+?)/");

    private static final Pattern patItag = Pattern.compile("itag=([0-9]+?)(&|,)");
    private static final Pattern patEncSig = Pattern.compile("s=([0-9A-F|\\.]{10,}?)(&|,|\")");
    private static final Pattern patUrl = Pattern.compile("url=(.+?)(&|,)");

    private static final Pattern patVariableFunction = Pattern.compile("(\\{|;| |=)([a-zA-Z$][a-zA-Z0-9$]{0,2})\\.([a-zA-Z$][a-zA-Z0-9$]{0,2})\\(");
    private static final Pattern patFunction = Pattern.compile("(\\{|;| |=)([a-zA-Z$_][a-zA-Z0-9$]{0,2})\\(");
    private static final Pattern patDecryptionJsFile = Pattern.compile("jsbin\\\\/(player-(.+?).js)");
    private static final Pattern patSignatureDecFunction = Pattern.compile("\\(\"signature\",(.{1,3}?)\\(.{1,10}?\\)");

    private static final SparseArray<Format> FORMAT_MAP = new SparseArray<>();

    static {
        // http://en.wikipedia.org/wiki/YouTube#Quality_and_formats

        // Video and Audio
        FORMAT_MAP.put(17, new Format(17, "3gp", 144, Format.VCodec.MPEG4, Format.ACodec.AAC, 24, false));
        FORMAT_MAP.put(36, new Format(36, "3gp", 240, Format.VCodec.MPEG4, Format.ACodec.AAC, 32, false));
        FORMAT_MAP.put(5, new Format(5, "flv", 240, Format.VCodec.H263, Format.ACodec.MP3, 64, false));
        FORMAT_MAP.put(43, new Format(43, "webm", 360, Format.VCodec.VP8, Format.ACodec.VORBIS, 128, false));
        FORMAT_MAP.put(18, new Format(18, "mp4", 360, Format.VCodec.H264, Format.ACodec.AAC, 96, false));
        FORMAT_MAP.put(22, new Format(22, "mp4", 720, Format.VCodec.H264, Format.ACodec.AAC, 192, false));

        // Dash Video
        FORMAT_MAP.put(160, new Format(160, "mp4", 144, Format.VCodec.H264, Format.ACodec.NONE, true));
        FORMAT_MAP.put(133, new Format(133, "mp4", 240, Format.VCodec.H264, Format.ACodec.NONE, true));
        FORMAT_MAP.put(134, new Format(134, "mp4", 360, Format.VCodec.H264, Format.ACodec.NONE, true));
        FORMAT_MAP.put(135, new Format(135, "mp4", 480, Format.VCodec.H264, Format.ACodec.NONE, true));
        FORMAT_MAP.put(136, new Format(136, "mp4", 720, Format.VCodec.H264, Format.ACodec.NONE, true));
        FORMAT_MAP.put(137, new Format(137, "mp4", 1080, Format.VCodec.H264, Format.ACodec.NONE, true));
        FORMAT_MAP.put(264, new Format(264, "mp4", 1440, Format.VCodec.H264, Format.ACodec.NONE, true));
        FORMAT_MAP.put(266, new Format(266, "mp4", 2160, Format.VCodec.H264, Format.ACodec.NONE, true));

        FORMAT_MAP.put(298, new Format(298, "mp4", 720, Format.VCodec.H264, 60, Format.ACodec.NONE, true));
        FORMAT_MAP.put(299, new Format(299, "mp4", 1080, Format.VCodec.H264, 60, Format.ACodec.NONE, true));

        // Dash Audio
        FORMAT_MAP.put(140, new Format(140, "m4a", Format.VCodec.NONE, Format.ACodec.AAC, 128, true));
        FORMAT_MAP.put(141, new Format(141, "m4a", Format.VCodec.NONE, Format.ACodec.AAC, 256, true));

        // WEBM Dash Video
        FORMAT_MAP.put(278, new Format(278, "webm", 144, Format.VCodec.VP9, Format.ACodec.NONE, true));
        FORMAT_MAP.put(242, new Format(242, "webm", 240, Format.VCodec.VP9, Format.ACodec.NONE, true));
        FORMAT_MAP.put(243, new Format(243, "webm", 360, Format.VCodec.VP9, Format.ACodec.NONE, true));
        FORMAT_MAP.put(244, new Format(244, "webm", 480, Format.VCodec.VP9, Format.ACodec.NONE, true));
        FORMAT_MAP.put(247, new Format(247, "webm", 720, Format.VCodec.VP9, Format.ACodec.NONE, true));
        FORMAT_MAP.put(248, new Format(248, "webm", 1080, Format.VCodec.VP9, Format.ACodec.NONE, true));
        FORMAT_MAP.put(271, new Format(271, "webm", 1440, Format.VCodec.VP9, Format.ACodec.NONE, true));
        FORMAT_MAP.put(313, new Format(313, "webm", 2160, Format.VCodec.VP9, Format.ACodec.NONE, true));

        FORMAT_MAP.put(302, new Format(302, "webm", 720, Format.VCodec.VP9, 60, Format.ACodec.NONE, true));
        FORMAT_MAP.put(308, new Format(308, "webm", 1440, Format.VCodec.VP9, 60, Format.ACodec.NONE, true));
        FORMAT_MAP.put(303, new Format(303, "webm", 1080, Format.VCodec.VP9, 60, Format.ACodec.NONE, true));
        FORMAT_MAP.put(315, new Format(315, "webm", 2160, Format.VCodec.VP9, 60, Format.ACodec.NONE, true));

        // WEBM Dash Audio
        FORMAT_MAP.put(171, new Format(171, "webm", Format.VCodec.NONE, Format.ACodec.VORBIS, 128, true));

        FORMAT_MAP.put(249, new Format(249, "webm", Format.VCodec.NONE, Format.ACodec.OPUS, 48, true));
        FORMAT_MAP.put(250, new Format(250, "webm", Format.VCodec.NONE, Format.ACodec.OPUS, 64, true));
        FORMAT_MAP.put(251, new Format(251, "webm", Format.VCodec.NONE, Format.ACodec.OPUS, 160, true));

        // HLS Live Stream
        FORMAT_MAP.put(91, new Format(91, "mp4", 144 ,Format.VCodec.H264, Format.ACodec.AAC, 48, false, true));
        FORMAT_MAP.put(92, new Format(92, "mp4", 240 ,Format.VCodec.H264, Format.ACodec.AAC, 48, false, true));
        FORMAT_MAP.put(93, new Format(93, "mp4", 360 ,Format.VCodec.H264, Format.ACodec.AAC, 128, false, true));
        FORMAT_MAP.put(94, new Format(94, "mp4", 480 ,Format.VCodec.H264, Format.ACodec.AAC, 128, false, true));
        FORMAT_MAP.put(95, new Format(95, "mp4", 720 ,Format.VCodec.H264, Format.ACodec.AAC, 256, false, true));
        FORMAT_MAP.put(96, new Format(96, "mp4", 1080 ,Format.VCodec.H264, Format.ACodec.AAC, 256, false, true));
    }

    public YouTubeExtractor(Context con) {
        context = con;
    }

    @Override
    protected void onPostExecute(SparseArray<YtFile> ytFiles) {

        onExtractionComplete(ytFiles, videoMeta,errMsg);
    }


    /**
     * Start the extraction.
     *
     * @param youtubeLink       the youtube page link or video id
     * @param parseDashManifest true if the dash manifest should be downloaded and parsed
     * @param includeWebM       true if WebM streams should be extracted
     */
    public void extract(String youtubeLink, boolean parseDashManifest, boolean includeWebM) {
        this.parseDashManifest = parseDashManifest;
        this.includeWebM = includeWebM;
        this.execute(youtubeLink);
    }

    protected abstract void onExtractionComplete(SparseArray<YtFile> ytFiles, VideoMeta videoMeta, String errMsg);

    @Override
    protected SparseArray<YtFile> doInBackground(String... params) {
        try {
            videoID = null;
            String ytUrl = params[0];
            if (ytUrl == null) {
                errMsg = "Error-"+"Video Id Empty";
                return null;
            }
            Matcher mat = patYouTubePageLink.matcher(ytUrl);
            if (mat.find()) {
                videoID = mat.group(3);
            } else {
                mat = patYouTubeShortLink.matcher(ytUrl);
                if (mat.find()) {
                    videoID = mat.group(3);
                } else if (ytUrl.matches("\\p{Graph}+?")) {
                    videoID = ytUrl;
                }
            }
            if (videoID != null) {
                try {
                    return getStreamUrls();
                } catch (Exception e) {
                    e.printStackTrace();
                    errMsg = "Error-"+e.getMessage();
                }
            } else {
                Log.e(LOG_TAG, "Wrong YouTube link format");
                errMsg = "Error-"+"Wrong YouTube link format";
            }
        }catch (Exception e){
            e.printStackTrace();
            errMsg = "Error-"+e.getMessage();
        }
        return null;
    }

    private SparseArray<YtFile> getStreamUrls() throws IOException, InterruptedException {
        try {
            String ytInfoUrl = (useHttp) ? "http://" : "https://";
            ytInfoUrl += "www.youtube.com/get_video_info?video_id=" + videoID + "&eurl="
                    + URLEncoder.encode("https://youtube.googleapis.com/v/" + videoID, "UTF-8");
            Log.e("ytInfoUrl =", ytInfoUrl);
            String dashMpdUrl = null;
            String streamMap = null;
            BufferedReader reader = null;
            URL getUrl = new URL(ytInfoUrl);
            HttpURLConnection urlConnection = (HttpURLConnection) getUrl.openConnection();
            urlConnection.setRequestProperty("User-Agent", USER_AGENT);
            try {
                reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                streamMap = reader.readLine();

            }catch(Exception e){
                errMsg = "Error-"+e.getMessage();
            } finally {
                if (reader != null)
                    reader.close();
                urlConnection.disconnect();
            }
            Matcher mat;
            String curJsFileName = null;
            String[] streams;
            SparseArray<String> encSignatures = null;
            Log.e("streamMap =", streamMap);
            parseVideoMeta(streamMap);

            if (videoMeta.isLiveStream()) {
                mat = patHlsvp.matcher(streamMap);
                if (mat.find()) {
                    String hlsvp = URLDecoder.decode(mat.group(1), "UTF-8");
                    SparseArray<YtFile> ytFiles = new SparseArray<>();

                    getUrl = new URL(hlsvp);
                    urlConnection = (HttpURLConnection) getUrl.openConnection();
                    urlConnection.setRequestProperty("User-Agent", USER_AGENT);
                    try {
                        reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("https://") || line.startsWith("http://")) {
                                mat = patHlsItag.matcher(line);
                                if (mat.find()) {
                                    int itag = Integer.parseInt(mat.group(1));
                                    YtFile newFile = new YtFile(FORMAT_MAP.get(itag), line);
                                    ytFiles.put(itag, newFile);
                                }
                            }
                        }
                    }catch(Exception e){
                        errMsg = "Error-"+e.getMessage();
                    }  finally {
                        if (reader != null)
                            reader.close();
                        urlConnection.disconnect();
                    }

                    if (ytFiles.size() == 0) {
                        if (LOGGING)
                            Log.d(LOG_TAG, streamMap);
                        errMsg = "Error-"+streamMap;
                        return null;
                    }
                    return ytFiles;
                }
                return null;
            }


            // Some videos are using a ciphered signature we need to get the
            // deciphering js-file from the youtubepage.
            if (streamMap == null || !streamMap.contains("use_cipher_signature=False")) {
                // Get the video directly from the youtubepage

                Log.e("If_new =","");
                if (CACHING
                        && (decipherJsFileName == null || decipherFunctions == null || decipherFunctionName == null)) {
                    readDecipherFunctFromCache();
                }
                getUrl = new URL("https://youtube.com/watch?v=" + videoID);
                Log.e("getUrl =", getUrl.toString());
                urlConnection = (HttpURLConnection) getUrl.openConnection();
                urlConnection.setRequestProperty("User-Agent", USER_AGENT);
                try {
                    reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Log.d("line", line);
                        if (line.contains("url_encoded_fmt_stream_map")) {
                            streamMap = line.replace("\\u0026", "&");
                            break;
                        }
                    }
                } finally {
                    if (reader != null)
                        reader.close();
                    urlConnection.disconnect();
                }
                encSignatures = new SparseArray<>();
                Log.e("streamMap =", streamMap);
                mat = patDecryptionJsFile.matcher(streamMap);
                Log.e("A_patDecryptionJsFile","");
                if (mat.find()) {
                    Log.e("IfpatDecryptionJsFile =", streamMap);
                    curJsFileName = mat.group(1).replace("\\/", "/");
                    if (decipherJsFileName == null || !decipherJsFileName.equals(curJsFileName)) {
                        decipherFunctions = null;
                        decipherFunctionName = null;
                    }
                    decipherJsFileName = curJsFileName;
                }
                Log.e("IfdecipherJsFileName =", decipherJsFileName);
                Log.e("parseDashManifest =", decipherJsFileName);
                if (parseDashManifest) {
                    Log.e("ifparseDashManifest =","");
                    mat = patDashManifest2.matcher(streamMap);
                    if (mat.find()) {
                        dashMpdUrl = mat.group(1).replace("\\/", "/");
                        mat = patDashManifestEncSig.matcher(dashMpdUrl);
                        if (mat.find()) {
                            encSignatures.append(0, mat.group(1));
                        } else {
                            dashMpdUrl = null;
                        }
                    }
                }
            } else {
                Log.e("else_new =","");
                if (parseDashManifest) {
                    mat = patDashManifest1.matcher(streamMap);
                    if (mat.find()) {
                        dashMpdUrl = URLDecoder.decode(mat.group(1), "UTF-8");
                    }
                }
                streamMap = URLDecoder.decode(streamMap, "UTF-8");
            }
            Log.e("streamMap  =",streamMap.toString());
            streams = streamMap.split(",|url_encoded_fmt_stream_map|&adaptive_fmts=");
            Log.e("After_Split  =",streams.toString());
            SparseArray<YtFile> ytFiles = new SparseArray<>();
            int j =0;
            for (String encStream : streams) {
                j++;
                Log.e(String.valueOf(j)+" th pos of encStream  =",streams.toString());
                encStream = encStream + ",";
                if (!encStream.contains("itag%3D")) {
                    continue;
                }
                String stream;
                stream = URLDecoder.decode(encStream, "UTF-8");
                Log.e("before_patitag","");
                Log.e("check stream",stream);
                mat = patItag.matcher(stream);
                int itag;
                if (mat.find()) {
                    Log.e("if_patitag","");
                    itag = Integer.parseInt(mat.group(1));
                    if (LOGGING)
                        Log.d(LOG_TAG, "Itag found:" + itag);
                    if (FORMAT_MAP.get(itag) == null) {
                        if (LOGGING)
                            Log.d(LOG_TAG, "Itag not in list:" + itag);
                        continue;
                    } else if (!includeWebM && FORMAT_MAP.get(itag).getExt().equals("webm")) {
                        continue;
                    }
                } else {
                    continue;
                }
                Log.e("curJsFileName",curJsFileName);
                if (curJsFileName != null) {
                    Log.e("if_curJsFileName","");
                    Log.e("before_patEncSig","");
                    mat = patEncSig.matcher(stream);
                    if (mat.find()) {
                        Log.e("if_patEncSig","");
                        encSignatures.append(itag, mat.group(1));
                    }
                }
                Log.e("beforepatUrl",encStream);
                mat = patUrl.matcher(encStream);
                String url = null;
                if (mat.find()) {
                    Log.e("if_patUrl",encStream);
                    url = mat.group(1);
                }
                Log.e("url",url);
                if (url != null) {
                    Log.e("if_url",url);
                    Format format = FORMAT_MAP.get(itag);
                    String finalUrl = URLDecoder.decode(url, "UTF-8");
                    Log.e("if_finalUrl",finalUrl);
                    YtFile newVideo = new YtFile(format, finalUrl);
                    ytFiles.put(itag, newVideo);
                }
            }
            Log.e("encSignatures ",encSignatures.toString());
            if (encSignatures != null && encSignatures.size()>0) {
                if (LOGGING)
                    Log.d(LOG_TAG, "Decipher signatures");
                String signature;
                decipheredSignature = null;
                if (decipherSignature(encSignatures)) {
                    lock.lock();
                    try {
                        jsExecuting.await(7, TimeUnit.SECONDS);
                    } finally {
                        lock.unlock();
                    }
                }

                Log.e("decipheredSignature", decipheredSignature);
                signature = decipheredSignature;
                if (signature == null) {
                    Log.e("if_signature_null", decipheredSignature);

                    errMsg = "Error-"+"signature is empty";
                    return null;
                } else {
                    Log.e("else_signature_null", decipheredSignature);
                    String[] sigs = signature.split("\n");
                    for (int i = 0; i < encSignatures.size() && i < sigs.length; i++) {
                        int key = encSignatures.keyAt(i);
                        Log.e("key", String.valueOf(key));
                        if (key == 0) {
                            Log.e("if_key_is_zero", String.valueOf(key));
                            dashMpdUrl = dashMpdUrl.replace("/s/" + encSignatures.get(key), "/signature/" + sigs[i]);
                            Log.e("dashMpdUrl", dashMpdUrl);
                        } else {
                            Log.e("else_key_is_zero", String.valueOf(key));
                            String url = ytFiles.get(key).getUrl();
                            Log.e("Last_url", String.valueOf(key));
                            url += "&signature=" + sigs[i];
                            Log.e("Last_url_with_sign", String.valueOf(key));
                            YtFile newFile = new YtFile(FORMAT_MAP.get(key), url);
                            ytFiles.put(key, newFile);
                        }
                    }
                }
            }

            if (parseDashManifest && dashMpdUrl != null) {
                for (int i = 0; i < DASH_PARSE_RETRIES; i++) {
                    try {
                        // It sometimes fails to connect for no apparent reason. We just retry.
                        parseDashManifest(dashMpdUrl, ytFiles);
                        break;
                    } catch (IOException io) {
                        Thread.sleep(5);
                        if (LOGGING)
                            Log.d(LOG_TAG, "Failed to parse dash manifest " + (i + 1));
                    }catch (Exception ioe) {
                        Thread.sleep(5);
                        if (LOGGING)
                            Log.d(LOG_TAG, "Failed to parse dash manifest " + (i + 1));
                    }
                }
            }

            if (ytFiles.size() == 0) {
                if (LOGGING)
                    Log.d(LOG_TAG, streamMap);
                errMsg = "Error-"+streamMap;
                return null;
            }
            return ytFiles;

        }catch (Exception e){
            e.printStackTrace();
            return null;
        }
      //  return ytFiles;
    }

    private boolean decipherSignature(final SparseArray<String> encSignatures) throws IOException {
        try {
            // Assume the functions don't change that much

            Log.e("I am decipherSignature",encSignatures.toString());
            decipherFunctions =null;
            if (decipherFunctionName == null || decipherFunctions == null) {
                Log.e("if_Content_empty",encSignatures.toString());
                String decipherFunctUrl = "https://s.ytimg.com/yts/jsbin/" + decipherJsFileName;
                Log.e("decipherFunctUrl",decipherFunctUrl);
                BufferedReader reader = null;
                String javascriptFile = null;
                URL url = new URL(decipherFunctUrl);
                Log.e("decipherFunctUrl_url",url.toString());
                HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestProperty("User-Agent", USER_AGENT);
                try {
                    reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                    StringBuilder sb = new StringBuilder("");
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                        sb.append(" ");
                    }
                    javascriptFile = sb.toString();
                    Log.e("javascriptFile",javascriptFile);
                } finally {
                    if (reader != null)
                        reader.close();
                    urlConnection.disconnect();
                }
                Log.e("javascriptFile",javascriptFile);
                if (LOGGING)
                    Log.d(LOG_TAG, "Decipher FunctURL: " + decipherFunctUrl);
                Log.e("beforepatsignature","beforepatsignature");

                Matcher mat;
                Character c='"';

                String []sp=javascriptFile.split(c+"signature"+c+",", 2);
                Log.e("sp",String.valueOf(sp.length));
                Log.e("sp[1]",sp[1]);
                String f=Character.toString((char) 40);
                Log.e("filter",f);
                String []sp1=sp[1].split("\\(", 2);
                Log.e("sp1",String.valueOf(sp1.length));
               //
                if (sp1.length>1){
                    Log.e("ifpatsignature",sp1[0]);
                    decipherFunctionName = sp1[0];
                    Log.e("decipherFunctionName",decipherFunctionName);
                    if (LOGGING)
                        Log.d(LOG_TAG, "Decipher Functname: " + decipherFunctionName);

                    Pattern patMainVariable = Pattern.compile("(var |\\s|,|;)" + decipherFunctionName.replace("$", "\\$") +
                            "(=function\\((.{1,3})\\)\\{)");

                    String mainDecipherFunct;
                    Log.e("beforepatMainVariable",decipherFunctionName);
                    mat = patMainVariable.matcher(javascriptFile);

                    if (mat.find()) {
                        Log.e("ifpatMainVariable",decipherFunctionName);
                        mainDecipherFunct = "var " + decipherFunctionName + mat.group(2);
                    } else {
                        Log.e("elsepatMainVariable",decipherFunctionName);
                        Pattern patMainFunction = Pattern.compile("function " + decipherFunctionName.replace("$", "\\$") +
                                "(\\((.{1,3})\\)\\{)");
                        Log.e("beelsepatMainVariable",decipherFunctionName);
                        mat = patMainFunction.matcher(javascriptFile);
                        if (!mat.find())
                            return false;
                        mainDecipherFunct = "function " + decipherFunctionName + mat.group(2);
                        Log.e("mainDecipherFunct",mainDecipherFunct);
                    }

                    int startIndex = mat.end();

                    for (int braces = 1, i = startIndex; i < javascriptFile.length(); i++) {
                        if (braces == 0 && startIndex + 5 < i) {
                            mainDecipherFunct += javascriptFile.substring(startIndex, i) + ";";
                            break;
                        }
                        if (javascriptFile.charAt(i) == '{')
                            braces++;
                        else if (javascriptFile.charAt(i) == '}')
                            braces--;
                    }
                    decipherFunctions = mainDecipherFunct;
                    // Search the main function for extra functions and variables
                    // needed for deciphering
                    // Search for variables
                    mat = patVariableFunction.matcher(mainDecipherFunct);
                    while (mat.find()) {
                        String variableDef = "var " + mat.group(2) + "={";
                        if (decipherFunctions.contains(variableDef)) {
                            continue;
                        }
                        startIndex = javascriptFile.indexOf(variableDef) + variableDef.length();
                        for (int braces = 1, i = startIndex; i < javascriptFile.length(); i++) {
                            if (braces == 0) {
                                decipherFunctions += variableDef + javascriptFile.substring(startIndex, i) + ";";
                                break;
                            }
                            if (javascriptFile.charAt(i) == '{')
                                braces++;
                            else if (javascriptFile.charAt(i) == '}')
                                braces--;
                        }
                    }
                    // Search for functions
                    mat = patFunction.matcher(mainDecipherFunct);
                    while (mat.find()) {
                        String functionDef = "function " + mat.group(2) + "(";
                        if (decipherFunctions.contains(functionDef)) {
                            continue;
                        }
                        startIndex = javascriptFile.indexOf(functionDef) + functionDef.length();
                        for (int braces = 0, i = startIndex; i < javascriptFile.length(); i++) {
                            if (braces == 0 && startIndex + 5 < i) {
                                decipherFunctions += functionDef + javascriptFile.substring(startIndex, i) + ";";
                                break;
                            }
                            if (javascriptFile.charAt(i) == '{')
                                braces++;
                            else if (javascriptFile.charAt(i) == '}')
                                braces--;
                        }
                    }

                    if (LOGGING)
                        Log.d(LOG_TAG, "Decipher Function: " + decipherFunctions);
                    decipherViaWebView(encSignatures);
                    if (CACHING) {
                        writeDeciperFunctToChache();
                    }
                } else {
                    Log.e("elsepatsignature","elsepatsignature");
                    return false;
                }
            } else {
                Log.e("else_Content_empty",decipherFunctionName);
                decipherViaWebView(encSignatures);
            }
            return true;
        }catch (Exception e){
            e.printStackTrace();
            return false;
        }
    }

    private void parseDashManifest(String dashMpdUrl, SparseArray<YtFile> ytFiles) throws IOException,Exception {
        Pattern patBaseUrl = Pattern.compile("<BaseURL yt:contentLength=\"[0-9]+?\">(.+?)</BaseURL>");
        String dashManifest;
        BufferedReader reader = null;
        URL getUrl = new URL(dashMpdUrl);
        HttpURLConnection urlConnection = (HttpURLConnection) getUrl.openConnection();
        urlConnection.setRequestProperty("User-Agent", USER_AGENT);
        try {
            reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
            reader.readLine();
            dashManifest = reader.readLine();

        } finally {
            if (reader != null)
                reader.close();
            urlConnection.disconnect();
        }
        if (dashManifest == null)
            return;
        Matcher mat = patBaseUrl.matcher(dashManifest);
        while (mat.find()) {
            int itag;
            String url = mat.group(1);
            Matcher mat2 = patItag.matcher(url);
            if (mat2.find()) {
                itag = Integer.parseInt(mat2.group(1));
                if (FORMAT_MAP.get(itag) == null)
                    continue;
                if (!includeWebM && FORMAT_MAP.get(itag).getExt().equals("webm"))
                    continue;
            } else {
                continue;
            }
            url = url.replace("&amp;", "&").replace(",", "%2C").
                    replace("mime=audio/", "mime=audio%2F").
                    replace("mime=video/", "mime=video%2F");
            YtFile yf = new YtFile(FORMAT_MAP.get(itag), url);
            ytFiles.append(itag, yf);
        }

    }

    private void parseVideoMeta(String getVideoInfo) throws UnsupportedEncodingException,Exception {
        boolean isLiveStream = false;
        String title = null, author = null, channelId = null;
        long viewCount = 0, length = 0;
        Matcher mat = patTitle.matcher(getVideoInfo);
        if (mat.find()) {
            title = URLDecoder.decode(mat.group(1), "UTF-8");
        }

        mat = patHlsvp.matcher(getVideoInfo);
        if(mat.find())
            isLiveStream = true;

        mat = patAuthor.matcher(getVideoInfo);
        if (mat.find()) {
            author = URLDecoder.decode(mat.group(1), "UTF-8");
        }
        mat = patChannelId.matcher(getVideoInfo);
        if (mat.find()) {
            channelId = mat.group(1);
        }
        mat = patLength.matcher(getVideoInfo);
        if (mat.find()) {
            length = Long.parseLong(mat.group(1));
        }
        mat = patViewCount.matcher(getVideoInfo);
        if (mat.find()) {
            viewCount = Long.parseLong(mat.group(1));
        }
        videoMeta = new VideoMeta(videoID, title, author, channelId, length, viewCount, isLiveStream);

    }

    private void readDecipherFunctFromCache() {
        if (context != null) {
            File cacheFile = new File(context.getCacheDir().getAbsolutePath() + "/" + CACHE_FILE_NAME);
            // The cached functions are valid for 2 weeks
            if (cacheFile.exists() && (System.currentTimeMillis() - cacheFile.lastModified()) < 1209600000) {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new InputStreamReader(new FileInputStream(cacheFile), "UTF-8"));
                    decipherJsFileName = reader.readLine();
                    decipherFunctionName = reader.readLine();
                    decipherFunctions = reader.readLine();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
    }

    /**
     * Parse the dash manifest for different dash streams and high quality audio. Default: false
     */
    public void setParseDashManifest(boolean parseDashManifest) {
        this.parseDashManifest = parseDashManifest;
    }


    /**
     * Include the webm format files into the result. Default: true
     */
    public void setIncludeWebM(boolean includeWebM) {
        this.includeWebM = includeWebM;
    }


    /**
     * Set default protocol of the returned urls to HTTP instead of HTTPS.
     * HTTP may be blocked in some regions so HTTPS is the default value.
     * <p/>
     * Note: Enciphered videos require HTTPS so they are not affected by
     * this.
     */
    public void setDefaultHttpProtocol(boolean useHttp) {
        this.useHttp = useHttp;
    }

    private void writeDeciperFunctToChache() {
        if (context != null) {
            File cacheFile = new File(context.getCacheDir().getAbsolutePath() + "/" + CACHE_FILE_NAME);
            BufferedWriter writer = null;
            try {
                writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(cacheFile), "UTF-8"));
                writer.write(decipherJsFileName + "\n");
                writer.write(decipherFunctionName + "\n");
                writer.write(decipherFunctions);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private void decipherViaWebView(final SparseArray<String> encSignatures) {
        try {
            if (context == null) {
                return;
            }
            Log.e("Content_empty",encSignatures.toString());
            final StringBuilder stb = new StringBuilder(decipherFunctions + " function decipher(");
            stb.append("){return ");
            for (int i = 0; i < encSignatures.size(); i++) {
                int key = encSignatures.keyAt(i);
                if (i < encSignatures.size() - 1)
                    stb.append(decipherFunctionName).append("('").append(encSignatures.get(key)).
                            append("')+\"\\n\"+");
                else
                    stb.append(decipherFunctionName).append("('").append(encSignatures.get(key)).
                            append("')");
            }
            stb.append("};decipher();");

            new Handler(Looper.getMainLooper()).post(new Runnable() {

                @Override
                public void run() {
                    JsEvaluator js = new JsEvaluator(context);
                    js.evaluate(stb.toString(),
                            new JsCallback() {
                                @Override
                                public void onResult(final String result) {
                                    lock.lock();
                                    try {
                                        decipheredSignature = result;
                                        jsExecuting.signal();
                                    } finally {
                                        lock.unlock();
                                    }
                                }

                            });
                }
            });
        }catch (Exception e){
            e.printStackTrace();
        }
    }

}
