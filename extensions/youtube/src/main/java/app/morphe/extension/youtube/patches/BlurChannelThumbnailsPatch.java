package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.LruCache;

import androidx.annotation.Nullable;

import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;
import org.chromium.net.impl.CronetUrlRequest;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.LithoRelayoutPatch;
import app.morphe.extension.shared.patches.components.BufferPhraseFilter;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.patches.utils.requests.AiSListSubmitRequest;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Blurs the thumbnails of the channels listed in the settings and draws a text over them.
 * <p>
 * 1. {@link app.morphe.extension.youtube.patches.components.BlurChannelThumbnailsFilter}
 *    sees each video card and records the video IDs whose card has a listed @handle.
 * 2. The image URL hook sends those thumbnails to a small server on 127.0.0.1 inside the app,
 *    which downloads the original thumbnail, blurs it and draws the text.
 * 3. If the app refuses to load from 127.0.0.1, the low resolution thumbnail is used instead.
 */
@SuppressWarnings("unused")
public final class BlurChannelThumbnailsPatch {

    private static final String THUMBNAIL_HOST = "https://i.ytimg.com/vi/";

    /** i.ytimg.com, i9.ytimg.com, etc. Path is /vi/ID/..., /vi_webp/ID/..., /an_webp/ID/... */
    private static final Pattern YTIMG_VIDEO_ID =
            Pattern.compile("^https://i\\d*\\.ytimg\\.com/[a-z_]+/([\\w-]{11})/");
    /** DeArrow thumbnail API urls, if that patch rewrote the URL before this hook. */
    private static final Pattern DEARROW_VIDEO_ID = Pattern.compile("[?&]videoID=([\\w-]{11})");
    private static final Pattern SERVER_REQUEST =
            Pattern.compile("^GET /t/([\\w-]{11})\\?x=([^ ]*) HTTP/1\\.[01]$");

    private static final int MAX_REMEMBERED_VIDEOS = 2000;

    /** Video ID -> lower case handle of a listed channel. */
    private static final Map<String, String> blockedVideos = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_REMEMBERED_VIDEOS;
                }
            });

    /** Videos the user chose to show from the video menu. Cleared when the app restarts. */
    private static final Set<String> revealedVideos = ConcurrentHashMap.newKeySet();

    // region Channel list

    /** Lower case handle -> overlay text, or empty for the default text. */
    private static volatile Map<String, String> channels = Collections.emptyMap();
    private static volatile String lastParsedList;

    private static Map<String, String> getChannels() {
        String raw = Settings.BLUR_CHANNEL_THUMBNAILS_CHANNELS.get();
        //noinspection StringEquality
        if (raw != lastParsedList) {
            synchronized (BlurChannelThumbnailsPatch.class) {
                //noinspection StringEquality
                if (raw != lastParsedList) {
                    channels = parseChannels(raw);
                    lastParsedList = raw;
                }
            }
        }
        return channels;
    }

    /**
     * One channel per line: {@code @handle} or {@code @handle | text on the thumbnail}.
     */
    private static Map<String, String> parseChannels(String raw) {
        Map<String, String> parsed = new HashMap<>();
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.charAt(0) == '!') continue;

            String text = "";
            final int separator = line.indexOf('|');
            if (separator >= 0) {
                text = line.substring(separator + 1).trim();
                line = line.substring(0, separator).trim();
            }
            if (!line.startsWith("@")) line = "@" + line;
            if (line.length() < 2) continue;

            parsed.put(line.toLowerCase(Locale.ROOT), text);
        }
        Logger.printDebug(() -> "Blur channel list: " + parsed.keySet());
        return parsed;
    }

    private static String overlayTextFor(String handle) {
        String text = getChannels().get(handle);
        return (text == null || text.isEmpty())
                ? Settings.BLUR_CHANNEL_THUMBNAILS_TEXT.get().trim()
                : text;
    }

    private static boolean isHandleByte(byte b) {
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')
                || b == '_' || b == '-' || b == '.' || (b & 0x80) != 0; // UTF-8 multi byte.
    }

    /**
     * Called by the Litho filter for every video card.
     */
    public static void processCardBuffer(byte[] buffer) {
        try {
            Map<String, String> listed = getChannels();
            if (listed.isEmpty()) return;

            String videoId = BufferPhraseFilter.extractVideoIdFromBuffer(buffer);
            if (videoId == null) return;

            final int length = buffer.length;
            for (int i = 0; i < length; i++) {
                if (buffer[i] != '@') continue;

                int end = i + 1;
                while (end < length && isHandleByte(buffer[end])) end++;
                // Handles end with a letter or number, not a period of a sentence.
                while (end > i + 1 && buffer[end - 1] == '.') end--;
                if (end - i < 2) continue;

                String handle = new String(buffer, i, end - i, StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT);
                if (listed.containsKey(handle)) {
                    blockedVideos.put(videoId, handle);
                    return;
                }
                i = end - 1;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "processCardBuffer failure", ex);
        }
    }

    /**
     * @return The listed handle of the video, or null if it is not blurred.
     */
    @Nullable
    private static String blockedHandle(String videoId) {
        String handle = blockedVideos.get(videoId);
        // Removed from the list since the card was seen.
        if (handle == null || !getChannels().containsKey(handle)) return null;
        return handle;
    }

    // endregion

    // region Image URL hook

    @Nullable
    private static String extractVideoId(String url) {
        Matcher matcher = YTIMG_VIDEO_ID.matcher(url);
        if (matcher.find()) return matcher.group(1);

        matcher = DEARROW_VIDEO_ID.matcher(url);
        if (matcher.find()) return matcher.group(1);

        return null;
    }

    private static String lowResUrl(String videoId) {
        // 120x90 image stretched over the thumbnail.
        return THUMBNAIL_HOST + videoId + "/default.jpg";
    }

    /**
     * Injection point. Called off the main thread and by multiple threads at the same time.
     */
    public static String overrideImageURL(String originalURL) {
        try {
            if (!Settings.BLUR_CHANNEL_THUMBNAILS.get()) return originalURL;
            if (originalURL.startsWith(BlurServer.URL_PREFIX)) return originalURL;

            String videoId = extractVideoId(originalURL);
            if (videoId == null || revealedVideos.contains(videoId)) return originalURL;

            String handle = blockedHandle(videoId);
            if (handle == null) return originalURL;

            if (Settings.BLUR_CHANNEL_THUMBNAILS_LOW_RES.get() || !BlurServer.ensureStarted()) {
                return lowResUrl(videoId);
            }

            return BlurServer.urlFor(videoId, overlayTextFor(handle));
        } catch (Exception ex) {
            Logger.printException(() -> "overrideImageURL failure", ex);
        }

        return originalURL;
    }

    /**
     * Injection point.
     */
    public static void handleCronetSuccess(UrlRequest request, UrlResponseInfo responseInfo) {
        // Nothing to do. A blur that failed is answered with the low resolution image.
    }

    /**
     * Injection point. The app could not connect at all, such as if it blocks 127.0.0.1.
     */
    public static void handleCronetFailure(UrlRequest request,
                                           @Nullable UrlResponseInfo responseInfo,
                                           IOException exception) {
        try {
            String url = ((CronetUrlRequest) request).getHookedUrl();
            if (url != null && url.startsWith(BlurServer.URL_PREFIX)) {
                BlurServer.markUnusable(exception);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "handleCronetFailure failure", ex);
        }
    }

    // endregion

    // region Video menu (long press a video, or its 3 dot button)

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    @Nullable
    private static Drawable icon() {
        return ResourceUtils.getDrawable(VersionCheckPatch.IS_20_31_OR_GREATER
                ? "yt_outline_experimental_circle_slash_vd_theme_24"
                : "yt_outline_flag_black_24");
    }

    public static int addFlyoutButton(Object flyoutPanel, int index, String videoId) {
        if (!isPatchIncluded() || videoId.isEmpty()
                || !Settings.BLUR_CHANNEL_THUMBNAILS.get()
                || !Settings.BLUR_CHANNEL_THUMBNAILS_FLYOUT_MENU.get()) {
            return index;
        }

        try {
            if (blockedHandle(videoId) != null) {
                final boolean revealed = revealedVideos.contains(videoId);
                return FlyoutUtils.addFlyoutButton(
                        flyoutPanel,
                        icon(),
                        str(revealed ? "morphe_blur_channel_thumbnails_hide_again"
                                : "morphe_blur_channel_thumbnails_show"),
                        v -> {
                            if (revealed) revealedVideos.remove(videoId);
                            else revealedVideos.add(videoId);
                            FlyoutUtils.dismissFlyout();
                            LithoRelayoutPatch.remountListViews();
                        },
                        index
                );
            }

            return FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    icon(),
                    str("morphe_blur_channel_thumbnails_add"),
                    v -> {
                        FlyoutUtils.dismissFlyout();
                        addChannelOfVideo(videoId);
                    },
                    index
            );
        } catch (Exception ex) {
            Logger.printException(() -> "addFlyoutButton failure", ex);
            return index;
        }
    }

    private static void addChannelOfVideo(String videoId) {
        Utils.runOnBackgroundThread(() -> {
            String handle = AiSListSubmitRequest.fetchChannelHandle(videoId);
            if (handle == null) {
                Utils.showSnackbarShort(str("morphe_blur_channel_thumbnails_add_failed"));
                return;
            }

            String key = handle.toLowerCase(Locale.ROOT);
            if (!getChannels().containsKey(key)) {
                String list = Settings.BLUR_CHANNEL_THUMBNAILS_CHANNELS.get().trim();
                Settings.BLUR_CHANNEL_THUMBNAILS_CHANNELS.save(list.isEmpty() ? handle : list + "\n" + handle);
            }
            blockedVideos.put(videoId, key);
            revealedVideos.remove(videoId);

            Utils.showSnackbarShort(str("morphe_blur_channel_thumbnails_added", handle));
            Utils.runOnMainThread(LithoRelayoutPatch::remountListViews);
        });
    }

    // endregion

    /**
     * Serves blurred thumbnails on 127.0.0.1, so the app's own image loader can show them.
     */
    private static final class BlurServer {
        static final String URL_PREFIX = "http://127.0.0.1:";

        private static final int OUTPUT_WIDTH = 640;
        private static final int OUTPUT_HEIGHT = 360;

        private static final LruCache<String, byte[]> cache = new LruCache<>(4 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, byte[] value) {
                return value.length;
            }
        };

        private static volatile int port;
        private static volatile boolean unusable;
        private static ExecutorService workers;

        static synchronized boolean ensureStarted() {
            if (unusable) return false;
            if (port != 0) return true;

            try {
                ServerSocket serverSocket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
                port = serverSocket.getLocalPort();
                workers = Executors.newFixedThreadPool(3, runnable -> {
                    Thread worker = new Thread(runnable, "BlurThumbnailWorker");
                    worker.setDaemon(true);
                    return worker;
                });

                Thread acceptThread = new Thread(() -> {
                    while (true) {
                        try {
                            Socket socket = serverSocket.accept();
                            workers.execute(() -> handle(socket));
                        } catch (Exception ex) {
                            Logger.printException(() -> "Blur server accept failure", ex);
                            return;
                        }
                    }
                }, "BlurThumbnailServer");
                acceptThread.setDaemon(true);
                acceptThread.start();

                Logger.printDebug(() -> "Blur server listening on port " + port);
                return true;
            } catch (Exception ex) {
                markUnusable(ex);
                return false;
            }
        }

        static void markUnusable(Exception ex) {
            if (unusable) return;
            unusable = true;
            Logger.printInfo(() -> "Blur server cannot be used, using low resolution thumbnails", ex);
            LithoRelayoutPatch.remountListViews();
        }

        static String urlFor(String videoId, String text) throws IOException {
            return URL_PREFIX + port + "/t/" + videoId + "?x=" + URLEncoder.encode(text, "UTF-8");
        }

        private static void handle(Socket socket) {
            try (Socket s = socket) {
                s.setSoTimeout(10_000);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                String requestLine = reader.readLine();
                // Rest of the headers are not needed.
                String header;
                while ((header = reader.readLine()) != null && !header.isEmpty()) {
                    // Skip.
                }

                OutputStream out = s.getOutputStream();
                Matcher matcher = requestLine == null ? null : SERVER_REQUEST.matcher(requestLine);
                if (matcher == null || !matcher.matches()) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    return;
                }

                String videoId = matcher.group(1);
                String text = URLDecoder.decode(matcher.group(2), "UTF-8");

                byte[] image = render(videoId, text);
                if (image == null) {
                    // Original thumbnail could not be downloaded. Send the app to the low resolution image.
                    out.write(("HTTP/1.1 302 Found\r\nLocation: " + lowResUrl(videoId)
                            + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    return;
                }

                out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + image.length
                        + "\r\nCache-Control: max-age=86400\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.write(image);
                out.flush();
            } catch (Exception ex) {
                Logger.printDebug(() -> "Blur server request failed: " + ex);
            }
        }

        @Nullable
        private static byte[] render(String videoId, String text) {
            final String cacheKey = videoId + '\n' + text;
            byte[] cached = cache.get(cacheKey);
            if (cached != null) return cached;

            Bitmap original = download(THUMBNAIL_HOST + videoId + "/mqdefault.jpg"); // 320x180, always exists.
            if (original == null) return null;

            // Scaling down a lot and back up with filtering in two steps gives a smooth blur.
            Bitmap tiny = Bitmap.createScaledBitmap(original, 16, 9, true);
            Bitmap small = Bitmap.createScaledBitmap(tiny, OUTPUT_WIDTH / 8, OUTPUT_HEIGHT / 8, true);
            Bitmap result = Bitmap.createScaledBitmap(small, OUTPUT_WIDTH, OUTPUT_HEIGHT, true)
                    .copy(Bitmap.Config.ARGB_8888, true);

            Canvas canvas = new Canvas(result);
            canvas.drawColor(Color.argb(90, 0, 0, 0)); // Darken so white text is readable.

            if (!text.isEmpty()) {
                TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
                paint.setColor(Color.WHITE);
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setTextSize(OUTPUT_HEIGHT * 0.12f);
                paint.setShadowLayer(6f, 0f, 2f, Color.BLACK);

                final int textWidth = (int) (OUTPUT_WIDTH * 0.85f);
                StaticLayout layout = StaticLayout.Builder
                        .obtain(text, 0, text.length(), paint, textWidth)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setMaxLines(3)
                        .build();

                canvas.save();
                canvas.translate((OUTPUT_WIDTH - textWidth) / 2f, (OUTPUT_HEIGHT - layout.getHeight()) / 2f);
                layout.draw(canvas);
                canvas.restore();
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            result.compress(Bitmap.CompressFormat.JPEG, 85, bytes);
            byte[] jpeg = bytes.toByteArray();
            cache.put(cacheKey, jpeg);
            return jpeg;
        }

        @Nullable
        private static Bitmap download(String url) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                if (connection.getResponseCode() != 200) return null;
                try (InputStream in = connection.getInputStream()) {
                    return BitmapFactory.decodeStream(in);
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Thumbnail download failed: " + url);
                return null;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
    }
}
