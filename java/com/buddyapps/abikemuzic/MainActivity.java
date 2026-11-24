package com.buddyapps.abikemuzic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int BG = Color.parseColor("#0B0F1A");
    private static final int CARD = Color.parseColor("#151B2B");
    private static final int ACCENT = Color.parseColor("#22E0A1");
    private static final int PINK = Color.parseColor("#FF3D7F");
    private static final int TEXT = Color.parseColor("#EAF0F6");
    private static final int MUTED = Color.parseColor("#94A3B8");

    private EditText urlEdit;
    private EditText lyricsEdit;
    private TextView statusText;
    private TextView lyricView;
    private ListView list;
    private ArrayAdapter<String> adapter;

    private final ArrayList<File> files = new ArrayList<File>();
    private final ArrayList<LyricLine> lyrics = new ArrayList<LyricLine>();

    private MediaPlayer player;
    private SharedPreferences prefs;
    private ExecutorService executor;
    private Handler handler;
    private Runnable ticker;
    private File songsDir;
    private String currentKey = "none";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("abikemuzic", MODE_PRIVATE);
        executor = Executors.newSingleThreadExecutor();
        handler = new Handler(Looper.getMainLooper());
        player = new MediaPlayer();

        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        songsDir = new File(base, "songs");
        if (!songsDir.exists()) songsDir.mkdirs();

        buildUi();
        refreshDownloads();
    }

    @Override
    protected void onDestroy() {
        stopPlayback();
        if (player != null) {
            player.release();
            player = null;
        }
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(14), dp(16), dp(12));
        setContentView(root);

        TextView title = new TextView(this);
        title.setText("ABIKEMUZIC");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ACCENT);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullParam());

        TextView sub = new TextView(this);
        sub.setText("stream  •  save  •  sing");
        sub.setTextSize(13);
        sub.setTextColor(MUTED);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub, fullParam());

        urlEdit = makeEdit("Direct audio URL (https://…/song.mp3)", 1);
        urlEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(urlEdit, fullParamWithMargin(0, 12, 0, 6));

        LinearLayout playRow = row();
        Button playOnline = makeButton("PLAY ONLINE", ACCENT);
        playOnline.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String u = urlEdit.getText().toString().trim();
                if (u.length() == 0) {
                    toast("Paste a direct audio URL first");
                    return;
                }
                play(u, "online_" + sanitize(u), "online stream");
            }
        });
        playRow.addView(playOnline, weightParam());

        Button download = makeButton("DOWNLOAD", PINK);
        download.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String u = urlEdit.getText().toString().trim();
                if (u.length() == 0) {
                    toast("Paste a direct audio URL first");
                    return;
                }
                startDownload(u);
            }
        });
        playRow.addView(download, weightParam());

        Button stop = makeButton("STOP", MUTED);
        stop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopPlayback();
                status("Stopped");
            }
        });
        playRow.addView(stop, weightParam());
        root.addView(playRow, fullParam());

        statusText = new TextView(this);
        statusText.setText("Ready. Paste a direct audio link to stream or save it offline.");
        statusText.setTextSize(13);
        statusText.setTextColor(MUTED);
        statusText.setGravity(Gravity.CENTER);
        statusText.setMinHeight(dp(36));
        root.addView(statusText, fullParam());

        root.addView(divider(), fullParam());

        TextView lyricsLabel = new TextView(this);
        lyricsLabel.setText("LYRICS  (paste LRC, or fetch a .lrc file)");
        lyricsLabel.setTextSize(12);
        lyricsLabel.setTypeface(Typeface.DEFAULT_BOLD);
        lyricsLabel.setTextColor(ACCENT);
        root.addView(lyricsLabel, fullParamWithMargin(0, 4, 0, 4));

        lyricsEdit = makeEdit("[00:12.34]Your lyrics here…", 3);
        lyricsEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        lyricsEdit.setGravity(Gravity.TOP);
        root.addView(lyricsEdit, fullParam());

        LinearLayout lyricRow = row();
        Button saveLyrics = makeButton("SAVE LYRICS", ACCENT);
        saveLyrics.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String text = lyricsEdit.getText().toString();
                prefs.edit().putString("lyrics_" + currentKey, text).apply();
                parseLyrics(text);
                status("Lyrics saved for this track");
            }
        });
        lyricRow.addView(saveLyrics, weightParam());

        Button fetchLrc = makeButton("FETCH .LRC", PINK);
        fetchLrc.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String u = urlEdit.getText().toString().trim();
                if (u.length() == 0) {
                    toast("Paste the audio URL, then fetch its .lrc");
                    return;
                }
                startLrcFetch(u);
            }
        });
        lyricRow.addView(fetchLrc, weightParam());

        Button clearLyrics = makeButton("CLEAR", MUTED);
        clearLyrics.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().remove("lyrics_" + currentKey).apply();
                lyricsEdit.setText("");
                lyrics.clear();
                lyricView.setText("No lyrics for this track.");
                status("Lyrics cleared");
            }
        });
        lyricRow.addView(clearLyrics, weightParam());
        root.addView(lyricRow, fullParam());

        lyricView = new TextView(this);
        lyricView.setText("No lyrics yet. Paste LRC above and tap Save lyrics.");
        lyricView.setTextSize(15);
        lyricView.setTextColor(TEXT);
        lyricView.setBackgroundColor(CARD);
        lyricView.setPadding(dp(12), dp(12), dp(12), dp(12));
        lyricView.setMinHeight(dp(72));
        root.addView(lyricView, fullParamWithMargin(0, 6, 0, 8));

        root.addView(divider(), fullParam());

        TextView savedLabel = new TextView(this);
        savedLabel.setText("OFFLINE LIBRARY");
        savedLabel.setTextSize(12);
        savedLabel.setTypeface(Typeface.DEFAULT_BOLD);
        savedLabel.setTextColor(ACCENT);
        root.addView(savedLabel, fullParamWithMargin(0, 8, 0, 2));

        list = new ListView(this);
        list.setBackgroundColor(CARD);
        list.setDividerHeight(1);
        list.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < files.size()) playFile(files.get(position));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View view, final int position, long id) {
                if (position < 0 || position >= files.size()) return true;
                final File f = files.get(position);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Delete download")
                        .setMessage("Remove " + f.getName() + " from your offline library?")
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                if (f.delete()) {
                                    prefs.edit().remove("lyrics_" + f.getName()).apply();
                                    refreshDownloads();
                                    status("Deleted " + f.getName());
                                } else {
                                    toast("Could not delete the file");
                                }
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            }
        });
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listParams.setMargins(0, dp(4), 0, dp(4));
        root.addView(list, listParams);

        LinearLayout libRow = row();
        Button playSaved = makeButton("PLAY", ACCENT);
        playSaved.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                File f = selectedFile();
                if (f == null) toast("Tap a song in the list first");
                else playFile(f);
            }
        });
        libRow.addView(playSaved, weightParam());

        Button deleteSaved = makeButton("DELETE", PINK);
        deleteSaved.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                File f = selectedFile();
                if (f == null) {
                    toast("Tap a song in the list first");
                    return;
                }
                final File target = f;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Delete download")
                        .setMessage("Remove " + target.getName() + "?")
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                if (target.delete()) {
                                    prefs.edit().remove("lyrics_" + target.getName()).apply();
                                    refreshDownloads();
                                    status("Deleted " + target.getName());
                                }
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        libRow.addView(deleteSaved, weightParam());

        Button refresh = makeButton("REFRESH", MUTED);
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                refreshDownloads();
            }
        });
        libRow.addView(refresh, weightParam());
        root.addView(libRow, fullParam());
    }

    private void playFile(File f) {
        play(f.getAbsolutePath(), f.getName(), f.getName());
    }

    private void play(final String source, final String key, final String label) {
        stopPlayback();
        currentKey = key;
        loadLyrics(key);
        status("Buffering " + label + "…");
        player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer mp) {
                mp.start();
                status("Playing " + label);
                startTicker();
            }
        });
        player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) {
                status("Playback error (" + what + "). Check the URL.");
                return true;
            }
        });
        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer mp) {
                stopTicker();
                status("Finished " + label);
            }
        });
        try {
            player.setDataSource(source);
            player.prepareAsync();
        } catch (Exception e) {
            status("Cannot play: " + e.getMessage());
        }
    }

    private void stopPlayback() {
        stopTicker();
        if (player == null) return;
        try {
            if (player.isPlaying()) player.stop();
        } catch (Exception ignored) {
        }
        try {
            player.reset();
        } catch (Exception ignored) {
        }
    }

    private void startDownload(final String urlText) {
        status("Downloading…");
        executor.execute(new Runnable() {
            public void run() {
                HttpURLConnection conn = null;
                InputStream in = null;
                FileOutputStream out = null;
                try {
                    URL url = new URL(urlText);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(20000);
                    conn.setInstanceFollowRedirects(true);
                    int code = conn.getResponseCode();
                    if (code >= 400) throw new IOException("HTTP " + code);

                    File target = uniqueFile(fileNameFromUrl(urlText));
                    in = conn.getInputStream();
                    out = new FileOutputStream(target);
                    byte[] buf = new byte[16384];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        total += n;
                        final long kb = total / 1024;
                        status("Downloading… " + kb + " KB");
                    }
                    out.flush();
                    out.close();
                    out = null;
                    in.close();
                    in = null;
                    conn.disconnect();
                    refreshDownloads();
                    status("Saved offline: " + target.getName());
                } catch (final Exception e) {
                    status("Download failed: " + e.getMessage());
                } finally {
                    closeQuietly(out);
                    closeQuietly(in);
                    if (conn != null) conn.disconnect();
                }
            }
        });
    }

    private void startLrcFetch(final String urlText) {
        status("Looking for lyrics…");
        executor.execute(new Runnable() {
            public void run() {
                try {
                    String lrcUrl = replaceExtensionWithLrc(urlText);
                    String text = null;
                    try {
                        text = fetchText(lrcUrl);
                    } catch (Exception first) {
                        text = fetchText(urlText + ".lrc");
                    }
                    final String found = text;
                    prefs.edit().putString("lyrics_" + currentKey, found).apply();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            lyricsEdit.setText(found);
                            parseLyrics(found);
                            status("Lyrics loaded and saved offline");
                        }
                    });
                } catch (final Exception e) {
                    status("No .lrc found: " + e.getMessage());
                }
            }
        });
    }

    private String fetchText(String urlText) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code >= 400) throw new IOException("HTTP " + code);
        InputStream in = conn.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1 && bos.size() < 2000000) {
            bos.write(buf, 0, n);
        }
        in.close();
        conn.disconnect();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private void refreshDownloads() {
        files.clear();
        File[] arr = songsDir.listFiles();
        if (arr != null) {
            for (File f : arr) {
                if (f.isFile()) files.add(f);
            }
        }
        Collections.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        ArrayList<String> names = new ArrayList<String>();
        for (File f : files) names.add(f.getName());
        if (adapter == null) {
            adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names);
            list.setAdapter(adapter);
        } else {
            adapter.clear();
            adapter.addAll(names);
            adapter.notifyDataSetChanged();
        }
        status(files.size() + " song(s) in your offline library");
    }

    private File selectedFile() {
        int p = list.getSelectedItemPosition();
        if (p < 0 || p >= files.size()) return null;
        return files.get(p);
    }

    private void loadLyrics(String key) {
        String saved = prefs.getString("lyrics_" + key, "");
        lyricsEdit.setText(saved);
        parseLyrics(saved);
    }

    private void parseLyrics(String text) {
        lyrics.clear();
        if (text == null || text.trim().length() == 0) {
            lyricView.setText("No lyrics for this track.");
            return;
        }
        Pattern tag = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2}(?:[.,]\\d{1,3})?)\\]");
        String[] lines = text.replace("\r", "").split("\n");
        for (String line : lines) {
            if (line.trim().length() == 0) continue;
            Matcher m = tag.matcher(line);
            ArrayList<Long> times = new ArrayList<Long>();
            int lastEnd = 0;
            while (m.find()) {
                try {
                    long minutes = Long.parseLong(m.group(1));
                    double seconds = Double.parseDouble(m.group(2).replace(',', '.'));
                    times.add(minutes * 60000L + (long) (seconds * 1000));
                } catch (Exception ignored) {
                }
                lastEnd = m.end();
            }
            String content = line.substring(lastEnd).trim();
            if (times.isEmpty()) {
                lyrics.add(new LyricLine(-1, line.trim()));
            } else {
                for (Long t : times) {
                    lyrics.add(new LyricLine(t, content));
                }
            }
        }
        Collections.sort(lyrics, new Comparator<LyricLine>() {
            public int compare(LyricLine a, LyricLine b) {
                return a.timeMs < b.timeMs ? -1 : (a.timeMs == b.timeMs ? 0 : 1);
            }
        });
        updateLyrics(0);
    }

    private void startTicker() {
        stopTicker();
        ticker = new Runnable() {
            public void run() {
                if (player == null || !player.isPlaying()) return;
                int pos;
                try {
                    pos = player.getCurrentPosition();
                } catch (Exception e) {
                    return;
                }
                updateLyrics(pos);
                handler.postDelayed(this, 400);
            }
        };
        handler.post(ticker);
    }

    private void stopTicker() {
        if (ticker != null) {
            handler.removeCallbacks(ticker);
            ticker = null;
        }
    }

    private void updateLyrics(int positionMs) {
        if (lyrics.isEmpty()) {
            lyricView.setText("No lyrics yet. Paste LRC above and tap Save lyrics.");
            return;
        }
        boolean timed = false;
        for (LyricLine l : lyrics) {
            if (l.timeMs >= 0) {
                timed = true;
                break;
            }
        }
        if (!timed) {
            StringBuilder sb = new StringBuilder();
            for (LyricLine l : lyrics) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(l.text);
            }
            lyricView.setText(sb.toString());
            return;
        }
        int idx = 0;
        for (int i = 0; i < lyrics.size(); i++) {
            LyricLine l = lyrics.get(i);
            if (l.timeMs >= 0 && l.timeMs <= positionMs) idx = i;
            else if (l.timeMs > positionMs) break;
        }
        String current = lyrics.get(idx).text;
        String next = idx + 1 < lyrics.size() ? lyrics.get(idx + 1).text : "";
        if (next.length() > 0) lyricView.setText("♪  " + current + "\n\n" + next);
        else lyricView.setText("♪  " + current);
    }

    private File uniqueFile(String name) {
        File f = new File(songsDir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return new File(songsDir, stem + "_" + System.currentTimeMillis() + ext);
    }

    private String fileNameFromUrl(String urlText) {
        String name = "song";
        try {
            String path = new URL(urlText).getPath();
            name = path.substring(path.lastIndexOf('/') + 1);
            name = URLDecoder.decode(name, "UTF-8");
        } catch (Exception ignored) {
        }
        name = sanitize(name);
        if (!name.matches(".*\\.[a-zA-Z0-9]{2,5}$")) name += ".mp3";
        return name;
    }

    private String replaceExtensionWithLrc(String urlText) {
        String clean = urlText.split("#")[0].split("\\?")[0];
        int slash = clean.lastIndexOf('/');
        int dot = clean.lastIndexOf('.');
        if (dot > slash) return clean.substring(0, dot) + ".lrc";
        return clean + ".lrc";
    }

    private String sanitize(String s) {
        String out = s.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (out.length() == 0) out = "track";
        if (out.length() > 90) out = out.substring(0, 90);
        return out;
    }

    private void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    private void status(final String message) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (statusText != null) statusText.setText(message);
            }
        });
    }

    private void toast(final String message) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private TextView divider() {
        TextView d = new TextView(this);
        d.setHeight(1);
        d.setBackgroundColor(CARD);
        return d;
    }

    private Button makeButton(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setAllCaps(true);
        b.setTextColor(BG);
        b.setBackgroundColor(color);
        b.setPadding(dp(4), dp(8), dp(4), dp(8));
        return b;
    }

    private EditText makeEdit(String hint, int lines) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(TEXT);
        e.setBackgroundColor(CARD);
        e.setLines(lines);
        e.setTextSize(13);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    private LinearLayout.LayoutParams fullParam() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams fullParamWithMargin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = fullParam();
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private LinearLayout.LayoutParams weightParam() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), dp(4), dp(2), dp(4));
        return lp;
    }

    private static class LyricLine {
        final long timeMs;
        final String text;

        LyricLine(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }
}
