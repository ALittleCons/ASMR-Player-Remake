package com.codex.asmrplayer;

import android.net.Uri;
import android.text.TextUtils;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 作品库缓存（v2，流式）。
 *
 * 旧实现把整库构造成一棵 JSONObject 树再 toString() 存进 SharedPreferences，
 * 而每条音轨都持有作品内全部图片，序列化规模是 O(音轨数 × 图片数)，
 * 在大体积 SMB 库上会直接 OOM 闪退。这里改为：
 *   1. 用 JsonWriter 边遍历边写私有文件，内存峰值恒定；
 *   2. 图片只在作品级保存一份，音轨用索引数组引用，
 *      缓存体积从 O(音轨 × 图片) 降到 O(音轨 + 图片)；
 *   3. 先写临时文件再原子替换，写入中断不会损坏旧缓存。
 */
final class LibraryCache {
    private static final int VERSION = 3;

    private LibraryCache() {
    }

    static void save(File target, List<MainActivity.WorkItem> works) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        JsonWriter writer = null;
        boolean ok = false;
        try {
            writer = new JsonWriter(new OutputStreamWriter(
                    new BufferedOutputStream(new FileOutputStream(tmp), 64 * 1024),
                    StandardCharsets.UTF_8));
            writer.beginObject();
            writer.name("v").value(VERSION);
            writer.name("works").beginArray();
            for (MainActivity.WorkItem work : works) {
                writeWork(writer, work);
            }
            writer.endArray();
            writer.endObject();
            writer.close();
            writer = null;
            if (target.exists() && !target.delete()) {
                throw new IOException("无法删除旧缓存");
            }
            if (!tmp.renameTo(target)) {
                throw new IOException("缓存重命名失败");
            }
            ok = true;
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
            if (!ok) {
                // 保留上一次的完整缓存，只清理半成品
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    static List<MainActivity.WorkItem> load(File target) throws IOException {
        List<MainActivity.WorkItem> works = new ArrayList<>();
        JsonReader reader = new JsonReader(new InputStreamReader(
                new BufferedInputStream(new FileInputStream(target), 64 * 1024),
                StandardCharsets.UTF_8));
        try {
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if ("v".equals(field)) {
                    if (reader.nextInt() != VERSION) {
                        throw new IOException("cache version changed, rescan required");
                    }
                } else if ("works".equals(field)) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        MainActivity.WorkItem work = readWork(reader);
                        if (work != null && !work.tracks.isEmpty()) {
                            works.add(work);
                        }
                    }
                    reader.endArray();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        } finally {
            try {
                reader.close();
            } catch (Exception ignored) {
            }
        }
        return works;
    }

    // ---------------- 写 ----------------

    private static void writeWork(JsonWriter w, MainActivity.WorkItem work) throws IOException {
        List<MainActivity.FileDoc> pool = new ArrayList<>();
        Map<String, Integer> poolIndex = new HashMap<>();
        addToPool(pool, poolIndex, work.cover);
        for (MainActivity.MediaItem track : work.tracks) {
            for (MainActivity.FileDoc image : track.images) {
                addToPool(pool, poolIndex, image);
            }
        }

        w.beginObject();
        w.name("key").value(safe(work.key));
        w.name("name").value(safe(work.name));
        w.name("rootUri").value(safe(work.rootUri));
        Integer coverIndex = work.cover == null ? null : poolIndex.get(work.cover.uri.toString());
        w.name("cover").value(coverIndex == null ? -1 : coverIndex.longValue());

        w.name("images").beginArray();
        for (MainActivity.FileDoc image : pool) {
            writeFile(w, image);
        }
        w.endArray();

        w.name("tracks").beginArray();
        for (MainActivity.MediaItem track : work.tracks) {
            w.beginObject();
            w.name("audio");
            writeFile(w, track.audio);
            w.name("vtt");
            if (track.vtt == null) {
                w.nullValue();
            } else {
                writeFile(w, track.vtt);
            }
            w.name("imgs").beginArray();
            for (MainActivity.FileDoc image : track.images) {
                Integer index = poolIndex.get(image.uri.toString());
                if (index != null) {
                    w.value(index.longValue());
                }
            }
            w.endArray();
            w.endObject();
        }
        w.endArray();
        w.endObject();
    }

    private static void addToPool(List<MainActivity.FileDoc> pool, Map<String, Integer> index,
                                  MainActivity.FileDoc file) {
        if (file == null || file.uri == null) {
            return;
        }
        String key = file.uri.toString();
        if (!index.containsKey(key)) {
            index.put(key, pool.size());
            pool.add(file);
        }
    }

    private static void writeFile(JsonWriter w, MainActivity.FileDoc file) throws IOException {
        w.beginObject();
        if (file != null) {
            w.name("n").value(safe(file.name));
            w.name("m").value(safe(file.mime));
            w.name("u").value(file.uri == null ? "" : file.uri.toString());
            w.name("r").value(safe(file.relativePath));
            w.name("p").value(safe(file.parentPath));
        }
        w.endObject();
    }

    // ---------------- 读 ----------------

    private static MainActivity.WorkItem readWork(JsonReader r) throws IOException {
        String key = "";
        String name = "";
        String rootUri = "";
        int coverIndex = -1;
        List<MainActivity.FileDoc> images = new ArrayList<>();
        List<MainActivity.MediaItem> tracks = new ArrayList<>();

        r.beginObject();
        while (r.hasNext()) {
            String field = r.nextName();
            switch (field) {
                case "key":
                    key = nextStringSafe(r);
                    break;
                case "name":
                    name = nextStringSafe(r);
                    break;
                case "rootUri":
                    rootUri = nextStringSafe(r);
                    break;
                case "cover":
                    coverIndex = nextIntSafe(r);
                    break;
                case "images":
                    r.beginArray();
                    while (r.hasNext()) {
                        MainActivity.FileDoc image = readFile(r);
                        if (image != null) {
                            images.add(image);
                        }
                    }
                    r.endArray();
                    break;
                case "tracks":
                    r.beginArray();
                    while (r.hasNext()) {
                        MainActivity.MediaItem track = readTrack(r, images);
                        if (track != null) {
                            tracks.add(track);
                        }
                    }
                    r.endArray();
                    break;
                default:
                    r.skipValue();
                    break;
            }
        }
        r.endObject();

        MainActivity.FileDoc cover = coverIndex >= 0 && coverIndex < images.size()
                ? images.get(coverIndex) : null;
        MainActivity.WorkItem work = new MainActivity.WorkItem(key, name, cover, tracks);
        work.rootUri = rootUri;
        if (cover != null) {
            for (MainActivity.MediaItem track : tracks) {
                track.moveCoverFirst(cover);
            }
        }
        return work;
    }

    private static MainActivity.MediaItem readTrack(JsonReader r,
                                                    List<MainActivity.FileDoc> pool) throws IOException {
        MainActivity.FileDoc audio = null;
        MainActivity.FileDoc vtt = null;
        List<MainActivity.FileDoc> images = new ArrayList<>();
        r.beginObject();
        while (r.hasNext()) {
            String field = r.nextName();
            switch (field) {
                case "audio":
                    audio = readFile(r);
                    break;
                case "vtt":
                    vtt = readFile(r);
                    break;
                case "imgs":
                    r.beginArray();
                    while (r.hasNext()) {
                        int index = nextIntSafe(r);
                        if (index >= 0 && index < pool.size()) {
                            images.add(pool.get(index));
                        }
                    }
                    r.endArray();
                    break;
                default:
                    r.skipValue();
                    break;
            }
        }
        r.endObject();
        if (audio == null) {
            return null;
        }
        return new MainActivity.MediaItem(audio, images, vtt);
    }

    /** 读一个文件对象；JSON null 或缺少 uri 时返回 null。 */
    private static MainActivity.FileDoc readFile(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.NULL) {
            r.skipValue();
            return null;
        }
        String fileName = "";
        String mime = "";
        String uri = "";
        String relativePath = "";
        String parentPath = "";
        r.beginObject();
        while (r.hasNext()) {
            String field = r.nextName();
            switch (field) {
                case "n":
                    fileName = nextStringSafe(r);
                    break;
                case "m":
                    mime = nextStringSafe(r);
                    break;
                case "u":
                    uri = nextStringSafe(r);
                    break;
                case "r":
                    relativePath = nextStringSafe(r);
                    break;
                case "p":
                    parentPath = nextStringSafe(r);
                    break;
                default:
                    r.skipValue();
                    break;
            }
        }
        r.endObject();
        if (TextUtils.isEmpty(uri)) {
            return null;
        }
        return new MainActivity.FileDoc(fileName, mime, Uri.parse(uri), relativePath, parentPath);
    }

    private static String nextStringSafe(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.NULL) {
            r.skipValue();
            return "";
        }
        return r.nextString();
    }

    private static int nextIntSafe(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.NULL) {
            r.skipValue();
            return -1;
        }
        try {
            return r.nextInt();
        } catch (Exception ex) {
            r.skipValue();
            return -1;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
