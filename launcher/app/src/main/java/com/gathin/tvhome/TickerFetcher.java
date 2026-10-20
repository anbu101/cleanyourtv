package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Feeds the ticker with headlines from RSS.
 *
 * Modes: MODE_NEWS (Google News India), MODE_CRICKET (Google News cricket),
 * MODE_BOTH (interleaves both). All sources are HTTPS, no API key needed.
 *
 * Internally maintains a pre-fetched queue so each scroll cycle just pops the
 * next item instantly; a background refetch happens when the queue runs low.
 */
public final class TickerFetcher {

    public static final int MODE_OFF     = 0;
    public static final int MODE_NEWS    = 1;
    public static final int MODE_CRICKET = 2;
    public static final int MODE_BOTH    = 3;

    public interface Callback {
        void onItem(String text);
    }

    private static final String TAG        = "TvHome.Ticker";
    private static final int    TIMEOUT_MS = 8_000;
    private static final int    BATCH_SIZE = 8;   // headlines per fetch
    private static final int    REFETCH_AT = 2;   // start next fetch when queue drops this low

    // Primary feeds
    private static final String FEED_NEWS    =
            "https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en";
    private static final String FEED_CRICKET =
            "https://news.google.com/rss/search?q=cricket+India+match+score&hl=en-IN&gl=IN&ceid=IN:en";

    // Fallback feeds (if primary fails)
    private static final String FEED_NEWS_FB    = "https://feeds.bbci.co.uk/news/world/rss.xml";
    private static final String FEED_CRICKET_FB = "https://feeds.bbci.co.uk/sport/cricket/rss.xml";

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TickerFetch");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Handler        mainHandler = new Handler(Looper.getMainLooper());
    private final Callback       callback;
    private final List<String>   queue       = new ArrayList<>();
    private int                  mode        = MODE_OFF;
    private boolean              fetching    = false;

    public TickerFetcher(Callback callback) {
        this.callback = callback;
    }

    /** Start the ticker in the given mode; fetches the first batch immediately. */
    public void start(int mode) {
        this.mode = mode;
        queue.clear();
        scheduleFetch();
    }

    /**
     * Deliver the next headline. Call this when the ticker finishes one scroll cycle.
     * If the queue is empty, the next item arrives asynchronously.
     */
    public void next() {
        if (!queue.isEmpty()) {
            String item = queue.remove(0);
            callback.onItem(item);
            if (queue.size() <= REFETCH_AT && !fetching) scheduleFetch();
        } else if (!fetching) {
            scheduleFetch();
        }
        // else: fetch in progress, item will arrive via onFetchDone
    }

    // -------------------------------------------------------------------------

    private void scheduleFetch() {
        if (fetching) return;
        fetching = true;
        executor.submit(() -> {
            List<String> fresh = fetchBatch(mode);
            mainHandler.post(() -> onFetchDone(fresh));
        });
    }

    private void onFetchDone(List<String> items) {
        fetching = false;
        queue.addAll(items);
        if (!queue.isEmpty()) {
            callback.onItem(queue.remove(0));
        }
    }

    private List<String> fetchBatch(int m) {
        List<String> news    = new ArrayList<>();
        List<String> cricket = new ArrayList<>();

        if (m == MODE_NEWS || m == MODE_BOTH) {
            news = fetchFeed(FEED_NEWS);
            if (news.isEmpty()) news = fetchFeed(FEED_NEWS_FB);
        }
        if (m == MODE_CRICKET || m == MODE_BOTH) {
            cricket = fetchFeed(FEED_CRICKET);
            if (cricket.isEmpty()) cricket = fetchFeed(FEED_CRICKET_FB);
        }

        // Interleave or just use whichever is populated
        List<String> result = new ArrayList<>();
        if (m == MODE_BOTH) {
            int n = Math.min(BATCH_SIZE / 2, Math.min(news.size(), cricket.size()));
            for (int i = 0; i < n; i++) {
                result.add(news.get(i));
                result.add(cricket.get(i));
            }
            // Append leftovers
            List<String> longer  = news.size() > cricket.size() ? news : cricket;
            int          already = n * 2;
            for (int i = n; i < longer.size() && result.size() < BATCH_SIZE; i++) {
                result.add(longer.get(i));
            }
        } else if (m == MODE_NEWS) {
            result = news.subList(0, Math.min(BATCH_SIZE, news.size()));
        } else if (m == MODE_CRICKET) {
            result = cricket.subList(0, Math.min(BATCH_SIZE, cricket.size()));
        }

        Log.d(TAG, "fetched " + result.size() + " items (mode=" + m + ")");
        return new ArrayList<>(result);
    }

    // -------------------------------------------------------------------------
    // RSS fetch + parse
    // -------------------------------------------------------------------------

    private List<String> fetchFeed(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != 200) return Collections.emptyList();
            return parseRssTitles(conn.getInputStream());
        } catch (Exception e) {
            Log.d(TAG, "feed error " + urlStr + ": " + e.getMessage());
            return Collections.emptyList();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Extracts item/title text from an RSS stream using XmlPullParser. */
    private static List<String> parseRssTitles(InputStream in) {
        List<String> result = new ArrayList<>();
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(in, null);

            boolean inItem  = false;
            boolean inTitle = false;

            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT && result.size() < BATCH_SIZE) {
                switch (event) {
                    case XmlPullParser.START_TAG:
                        if ("item".equals(parser.getName()))        inItem  = true;
                        else if (inItem && "title".equals(parser.getName())) inTitle = true;
                        break;
                    case XmlPullParser.END_TAG:
                        if ("item".equals(parser.getName()))        inItem  = false;
                        else if ("title".equals(parser.getName()))  inTitle = false;
                        break;
                    case XmlPullParser.TEXT:
                        if (inItem && inTitle) {
                            String text = cleanTitle(parser.getText());
                            if (!text.isEmpty()) result.add(text);
                            inTitle = false;
                        }
                        break;
                    case XmlPullParser.CDSECT:
                        if (inItem && inTitle) {
                            String text = cleanTitle(parser.getText());
                            if (!text.isEmpty()) result.add(text);
                            inTitle = false;
                        }
                        break;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "parse error: " + e.getMessage());
        }
        return result;
    }

    /** Strips the trailing " - Source Name" that Google News appends to every title. */
    private static String cleanTitle(String raw) {
        if (raw == null) return "";
        raw = raw.trim();
        // Google News: "Headline text - Source Name"
        int dash = raw.lastIndexOf(" - ");
        if (dash > 20) raw = raw.substring(0, dash).trim();
        return raw;
    }
}
