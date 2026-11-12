package com.codex.asmrplayer;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQ_OPEN_TREE = 1001;
    private static final int REQ_OVERLAY_PERMISSION = 1002;
    private static final int MAX_SCAN_DEPTH = 8;
    private static final int PAGE_WORKS = 0;
    private static final int PAGE_TRACKS = 1;
    private static final int PAGE_PLAYER = 2;
    private static final int PAGE_LYRICS = 3;
    private static final int TAB_FIND = 0;
    private static final int TAB_HEARD = 1;
    private static final int TAB_MY = 2;
    private static final int TAB_SETTINGS = 3;
    private static final String ICON_PLAY = "▶";
    private static final String ICON_PAUSE = "⏸";
    private static final String ICON_LYRICS = "☰";
    private static final String PREFS = "asmr_pocket_prefs";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_TREE_URIS = "tree_uris";
    private static final String KEY_NET_SOURCES = "net_sources";
    private static final long RESCAN_DEBOUNCE_MS = 1200;
    private static final long POLL_INTERVAL_MS = 6000;
    /** 含网络路径时自动重扫的最小间隔，避免每 6 秒全量列举远端目录。 */
    private static final long NET_RESCAN_MIN_MS = 60000;
    private static final String KEY_LIBRARY_CACHE = "library_cache";
    private static final String KEY_FLOATING_LYRICS = "floating_lyrics";
    private static final String KEY_FLOATING_LINES = "floating_lines";
    private static final String KEY_WEB_HISTORY = "web_history";
    private static final String KEY_RECENT_AUDIO = "recent_audio";
    private static final String KEY_DARK_THEME = "dark_theme";
    private static final String GITHUB_URL = "https://github.com/Baimo1209/ASMR-Player";
    private static final String TAG = "AsmrPlayer";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<WorkItem> works = new ArrayList<>();
    private final List<MediaItem> playlist = new ArrayList<>();
    private final List<MediaItem> playbackQueue = new ArrayList<>();
    private final List<Cue> cues = new ArrayList<>();
    private final List<String> webHistory = new ArrayList<>();
    private final List<RecentTrack> recentTracks = new ArrayList<>();
    /** 列表缩略图；与播放页大图、波形/字幕分流，避免大库下互相饿死。 */
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(2);
    /** 播放页大图 / 相邻预览 / 全屏看图，专用高优先级线程池。 */
    private final ExecutorService bigImageExecutor = Executors.newFixedThreadPool(2);
    /** 波形计算、字幕解析等辅助 IO。 */
    private final ExecutorService auxExecutor = Executors.newFixedThreadPool(2);
    private final TextDrawable workPlaceholder = new TextDrawable("ASMR");
    /**
     * 缩略图/大图解码在途去重：ListView 复用和动画重入不再重复拉取同一张 SMB 原图。
     * 用 Collections.newSetFromMap 而非 ConcurrentHashMap.newKeySet()——后者是
     * Java 8/API 24 新增方法，本项目 minSdk 23 且未启用 core library desugaring。
     */
    private final Set<String> coverLoadsInFlight =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<String> bigImageLoadsInFlight =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** 等待同一张缩略图的控件；ListView 复用后仍能让后来绑定的行拿到结果（仅主线程访问）。 */
    private final Map<String, List<ImageView>> coverWaiters = new HashMap<>();

    private final LruCache<String, Bitmap> coverCache = new LruCache<String, Bitmap>(8192) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };
    private final BrowserAdapter browserAdapter = new BrowserAdapter();
    private final LyricsAdapter lyricsAdapter = new LyricsAdapter();
    private final WebHistoryAdapter webHistoryAdapter = new WebHistoryAdapter();
    private final RecentAdapter recentAdapter = new RecentAdapter();
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 250);
        }
    };

    private MediaPlayer player;
    private WorkItem activeWork;
    private WorkItem playbackWork;
    private int currentIndex = -1;
    private int currentCueIndex = -1;
    private int currentImageIndex = 0;
    private int movingWorkIndex = -1;
    private int movingTrackIndex = -1;
    private boolean draggingListItem;
    private boolean floatingLyricsEnabled;
    private boolean userSeeking;
    private int currentTab = TAB_MY;
    private int pageMode = PAGE_WORKS;

    private final List<RootInfo> roots = new ArrayList<>();
    private final List<NetSource> netSources = new ArrayList<>();
    private int filterRootIndex = -1;
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService cacheExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean scanInFlight = new AtomicBoolean(false);
    private volatile long lastCacheFingerprint = -1;
    private volatile long lastNetScanAt = 0L;
    private final Runnable pendingRescan = () -> rescanLibrary(true);
    private Runnable pollTick;
    private ContentObserver libraryObserver;
    private boolean activityResumed;

    private FrameLayout rootLayout;
    private View drawerScrim;
    private LinearLayout settingsDrawer;
    private Button floatingLyricsButton;
    private WindowManager windowManager;
    private LinearLayout floatingLyricView;
    private WindowManager.LayoutParams floatingLyricParams;
    private final TextView[] floatingLineViews = new TextView[3];
    private int floatingLineCount = 1;
    private String lastFloatingRenderKey;
    // 静态引用用于清理孤儿悬浮窗（Activity 重建/异常泄漏时旧窗口残留导致双字幕）
    private static View sFloatingLyricRoot;

    private boolean darkTheme;
    private int colorBg;
    private int colorTextPrimary;
    private int colorTextSecondary;
    private int colorAccent;
    private int colorCard;
    private int colorCardStroke;
    private int colorChip;
    private int colorIcon;
    private int colorThumbBg;
    private int colorWaveUnplayed;
    private int colorLyric;

    private LinearLayout findArea;
    private EditText urlInput;
    private WebView webView;
    private ListView webHistoryList;
    private ListView recentList;
    private LinearLayout settingsArea;
    private ScrollView settingsScroll;
    private LinearLayout contentPanel;
    private LinearLayout bottomNav;
    private Button findTabButton;
    private Button heardTabButton;
    private Button myTabButton;
    private Button settingsTabButton;
    private LinearLayout playerArea;
    private ListView browserList;
    private ListView lyricsList;
    private TextView sectionTitleView;
    private TextView sectionSubtitleView;
    private ImageView coverView;
    private ImageView coverTopThumb;
    private LinearLayout imageIndicator;
    private TextView imageIndexText;
    private GestureDetector playerImageGesture;
    private boolean imageSwitching;
    private boolean imageDragging;
    private boolean imageHorizontalIntent;
    private float imageDownX;
    private float imageDownY;
    private float imageDragBase;
    private int imageAnimGeneration;
    private VelocityTracker imageVelocityTracker;
    /** 拖动切换图片时显示相邻图片的预览（同时触发预解码，避免松手后空白等待）。 */
    private ImageView imagePeerView;
    private int imagePeerIndex = -1;
    private int imagePeerSide;
    private float imagePeerBaseX;
    private float imagePeerBaseY;
    private FrameLayout viewerOverlay;
    private ZoomImageView viewerImage;
    private TextView viewerIndexLabel;
    private List<FileDoc> viewerImages;
    private int viewerPosition;
    /** 大图缓存按字节限制（32MB）；旧版按「24 张」计数，1440px 图会吃到近 200MB 导致解码失败。 */
    private final LruCache<String, Bitmap> bigImageCache = new LruCache<String, Bitmap>(32 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };
    /** 当前期望显示的大图 key，用于丢弃线程池里已经过期的解码任务。 */
    private volatile String pendingBigImageKey;
    private volatile String pendingPeerImageKey;
    private TextView titleView;
    private TextView folderView;
    private TextView lyricView;
    private TextView timeView;
    private TextView statusView;
    private WaveformView waveform;
    private Button playButton;
    private ImageButton lyricsButton;
    private LinearLayout miniPlayer;
    private ImageView miniCoverThumb;
    private TextView miniTitleView;
    private TextView miniTimeView;
    private SeekBar miniSeekBar;
    private Button miniPlayButton;
    private HorizontalScrollView filterBar;
    private LinearLayout filterChipRow;
    private LinearLayout rootListContainer;
    private boolean playerCollapseIntent;
    private float playerCollapseDownX;
    private float playerCollapseDownY;
    private VelocityTracker collapseVelocityTracker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        purgeLegacyCacheIfOversized();
        darkTheme = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DARK_THEME, false);
        computeThemeColors();
        applySystemBarAppearance();
        if (workPlaceholder != null) {
            workPlaceholder.setColor(colorTextSecondary);
        }
        floatingLyricsEnabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_FLOATING_LYRICS, false);
        floatingLineCount = Math.max(1, Math.min(3,
                getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_FLOATING_LINES, 1)));
        buildUi();
        loadWebHistory();
        loadRecentTracks();
        updateFloatingLyricsButton();
        if (floatingLyricsEnabled && canDrawOverlays()) {
            showFloatingLyrics();
        }

        loadRoots();
        StreamProxy.get().start();
        syncProxySources();
        registerLibraryObserver();
        refreshRootRows();
        refreshFilterBar();
        pollTick = () -> {
            if (activityResumed && !roots.isEmpty()) {
                rescanLibrary(true);
            }
            handler.postDelayed(pollTick, POLL_INTERVAL_MS);
        };
        if (!roots.isEmpty() && !loadCachedLibrary()) {
            folderView.setText(rootNamesText());
            titleView.setText("已保存文件夹授权");
            lyricView.setText("请点击“重新扫描”刷新作品列表，之后会使用缓存快速进入");
            sectionSubtitleView.setText("未找到预加载缓存");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(progressTick);
        unregisterLibraryObserver();
        hideFloatingLyrics();
        releasePlayer();
        imageExecutor.shutdownNow();
        bigImageExecutor.shutdownNow();
        auxExecutor.shutdownNow();
        scanExecutor.shutdownNow();
        cacheExecutor.shutdownNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        applySystemBarAppearance();
        if (!roots.isEmpty()) {
            rescanLibrary(true);
        }
        handler.removeCallbacks(pollTick);
        handler.postDelayed(pollTick, POLL_INTERVAL_MS);
        if (floatingLyricsEnabled) {
            if (canDrawOverlays()) {
                showFloatingLyrics();
            } else {
                floatingLyricsEnabled = false;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_FLOATING_LYRICS, false).apply();
                updateFloatingLyricsButton();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        activityResumed = false;
        handler.removeCallbacks(pollTick);
        handler.removeCallbacks(pendingRescan);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applySystemBarAppearance();
        }
    }

    @Override
    public void onBackPressed() {
        if (viewerOverlay != null && viewerOverlay.getVisibility() == View.VISIBLE) {
            closeImageViewer();
            return;
        }
        if (settingsDrawer != null && settingsDrawer.getVisibility() == View.VISIBLE) {
            hideSettingsDrawer();
            return;
        }
        if (currentTab != TAB_MY) {
            showMainTab(TAB_MY);
            return;
        }
        if (pageMode != PAGE_WORKS) {
            goBackPage();
            return;
        }
        moveTaskToBack(true);
    }    private void buildUi() {
        rootLayout = new FrameLayout(this);
        FrameLayout root = rootLayout;
        root.setBackgroundColor(colorBg);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(34), 0, 0);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout sectionHeader = new LinearLayout(this);
        sectionHeader.setOrientation(LinearLayout.VERTICAL);
        sectionHeader.setPadding(dp(24), dp(4), dp(20), dp(2));
        page.addView(sectionHeader, new LinearLayout.LayoutParams(-1, -2));

        sectionTitleView = label("作品列表", 22, colorTextPrimary);
        sectionTitleView.setSingleLine(true);
        sectionTitleView.setEllipsize(TextUtils.TruncateAt.END);
        sectionTitleView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        sectionTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        sectionHeader.addView(sectionTitleView, new LinearLayout.LayoutParams(-1, dp(34)));

        LinearLayout subtitleRow = new LinearLayout(this);
        subtitleRow.setOrientation(LinearLayout.HORIZONTAL);
        subtitleRow.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

        coverTopThumb = new ImageView(this);
        coverTopThumb.setVisibility(View.GONE);
        coverTopThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverTopThumb.setBackgroundColor(colorThumbBg);
        coverTopThumb.setOnClickListener(v -> {
            currentImageIndex = 0;
            showCurrentTrackImage();
        });
        LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        thumbParams.rightMargin = dp(7);
        subtitleRow.addView(coverTopThumb, thumbParams);

        sectionSubtitleView = label("请选择文件夹，自动识别 ASMR 作品", 13, colorTextSecondary);
        sectionSubtitleView.setSingleLine(true);
        sectionSubtitleView.setEllipsize(TextUtils.TruncateAt.END);
        sectionSubtitleView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        subtitleRow.addView(sectionSubtitleView, new LinearLayout.LayoutParams(0, -1, 1));
        sectionHeader.addView(subtitleRow, new LinearLayout.LayoutParams(-1, dp(30)));

        contentPanel = new LinearLayout(this);
        contentPanel.setOrientation(LinearLayout.VERTICAL);
        contentPanel.setPadding(dp(18), dp(8), dp(18), dp(10));
        page.addView(contentPanel, new LinearLayout.LayoutParams(-1, 0, 1));

        coverView = new ImageView(this);
        coverView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        playerImageGesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent event) {
                openImageViewer();
                return true;
            }
        });
        coverView.setOnTouchListener((view, event) -> {
            playerImageGesture.onTouchEvent(event);
            handleImageTouch(event);
            return true;
        });

        // 拖动切图时的相邻图片预览层：与 coverView 同尺寸，跟随拖动显示目标图，
        // 同时提前解码目标大图，使松手后 coverView 换图零延迟、动画无缝衔接
        imagePeerView = new ImageView(this);
        imagePeerView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imagePeerView.setVisibility(View.GONE);
        imagePeerView.setClickable(false);
        imagePeerView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        rootLayout.addView(imagePeerView, new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.START));

        playerArea = new LinearLayout(this);
        playerArea.setOrientation(LinearLayout.VERTICAL);
        playerArea.setVisibility(View.GONE);
        contentPanel.addView(playerArea, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(-1, 0);
        coverParams.weight = 1f;
        coverParams.topMargin = dp(2);
        playerArea.addView(coverView, coverParams);

        imageIndicator = new LinearLayout(this);
        imageIndicator.setOrientation(LinearLayout.HORIZONTAL);
        imageIndicator.setGravity(Gravity.CENTER);
        imageIndicator.setVisibility(View.GONE);
        imageIndicator.addView(miniIndicatorButton("‹", -1), new LinearLayout.LayoutParams(dp(34), -1));
        imageIndexText = label("", 11, colorTextSecondary);
        imageIndexText.setGravity(Gravity.CENTER);
        imageIndexText.setMinWidth(dp(42));
        imageIndicator.addView(imageIndexText, new LinearLayout.LayoutParams(-2, -1));
        imageIndicator.addView(miniIndicatorButton("›", 1), new LinearLayout.LayoutParams(dp(34), -1));
        LinearLayout.LayoutParams indicatorRowParams = new LinearLayout.LayoutParams(-2, dp(22));
        indicatorRowParams.gravity = Gravity.CENTER_HORIZONTAL;
        indicatorRowParams.topMargin = dp(2);
        playerArea.addView(imageIndicator, indicatorRowParams);

        LinearLayout infoPanel = panel();
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, dp(108));
        infoParams.topMargin = dp(10);
        playerArea.addView(infoPanel, infoParams);

        titleView = label("请选择包含 RJ 作品文件夹的目录", 17, colorTextPrimary);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(Gravity.CENTER);
        infoPanel.addView(titleView, new LinearLayout.LayoutParams(-1, dp(36)));

        folderView = label("会递归识别 mp3/wav、图片和 .vtt，并自动按作品分组。", 13, colorTextSecondary);
        folderView.setSingleLine(true);
        folderView.setEllipsize(TextUtils.TruncateAt.END);
        folderView.setGravity(Gravity.CENTER);
        folderView.setVisibility(View.GONE);
        infoPanel.addView(folderView, new LinearLayout.LayoutParams(-1, 0));

        lyricView = label("", 15, colorLyric);
        lyricView.setGravity(Gravity.CENTER);
        lyricView.setMaxLines(3);
        infoPanel.addView(lyricView, new LinearLayout.LayoutParams(-1, 0, 1));

        waveform = new WaveformView(this);
        waveform.setOnTouchListener((v, event) -> handleWaveformTouch(event));
        playerArea.addView(waveform, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        playerArea.addView(controls, new LinearLayout.LayoutParams(-1, dp(62)));

        controls.addView(controlButton("⏮", v -> playRelative(-1, true)), buttonParams());
        controls.addView(controlButton("-15", v -> seekBy(-15000)), buttonParams());
        playButton = new Button(this);
        playButton.setText(ICON_PLAY);
        playButton.setTextColor(Color.WHITE);
        playButton.setTextSize(20);
        playButton.setAllCaps(false);
        playButton.setBackground(roundRect(colorAccent, 27));
        playButton.setStateListAnimator(null);
        playButton.setElevation(0f);
        playButton.setOnClickListener(v -> togglePlayback());
        playButton.setMinWidth(0);
        playButton.setMinimumWidth(0);
        playButton.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(0, dp(54), 1.15f);
        playParams.leftMargin = dp(3);
        playParams.rightMargin = dp(3);
        controls.addView(playButton, playParams);
        controls.addView(controlButton("+15", v -> seekBy(15000)), buttonParams());
        controls.addView(controlButton("⏭", v -> playRelative(1, true)), buttonParams());
        lyricsButton = controlButton(ICON_LYRICS, v -> showLyrics());
        LinearLayout.LayoutParams lyricsParams = buttonParams();
        controls.addView(lyricsButton, lyricsParams);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        playerArea.addView(meta, new LinearLayout.LayoutParams(-1, dp(30)));

        timeView = label("00:00 / 00:00", 12, colorTextSecondary);
        meta.addView(timeView, new LinearLayout.LayoutParams(0, -1, 1));
        statusView = label("未加载", 12, colorTextSecondary);
        statusView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        meta.addView(statusView, new LinearLayout.LayoutParams(0, -1, 1));

        filterBar = new HorizontalScrollView(this);
        filterBar.setHorizontalScrollBarEnabled(false);
        filterBar.setBackgroundColor(Color.TRANSPARENT);
        filterBar.setVisibility(View.GONE);
        filterChipRow = new LinearLayout(this);
        filterChipRow.setOrientation(LinearLayout.HORIZONTAL);
        filterChipRow.setGravity(Gravity.CENTER_VERTICAL);
        filterChipRow.setPadding(dp(6), dp(3), dp(6), dp(3));
        filterBar.addView(filterChipRow, new HorizontalScrollView.LayoutParams(-2, -2));
        contentPanel.addView(filterBar, new LinearLayout.LayoutParams(-1, dp(40)));

        browserList = new ListView(this);
        browserList.setDivider(new ColorDrawable(Color.TRANSPARENT));
        browserList.setDividerHeight(dp(8));
        browserList.setCacheColorHint(Color.TRANSPARENT);
        browserList.setBackgroundColor(Color.TRANSPARENT);
        browserList.setClipToPadding(false);
        browserList.setScrollingCacheEnabled(false);
        browserList.setSmoothScrollbarEnabled(true);
        browserList.setPadding(0, dp(6), 0, dp(12));
        browserList.setAdapter(browserAdapter);
        browserList.setOnItemClickListener(this::onListItemClick);
        browserList.setOnItemLongClickListener((parent, view, position, id) -> onListItemLongClick(position));
        browserList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        browserList.setOnTouchListener((v, event) -> handleListDrag(event));
        contentPanel.addView(browserList, new LinearLayout.LayoutParams(-1, 0, 1));

        lyricsList = new ListView(this);
        lyricsList.setDivider(new ColorDrawable(Color.TRANSPARENT));
        lyricsList.setDividerHeight(dp(8));
        lyricsList.setCacheColorHint(Color.TRANSPARENT);
        lyricsList.setBackgroundColor(Color.TRANSPARENT);
        lyricsList.setClipToPadding(false);
        lyricsList.setScrollingCacheEnabled(false);
        lyricsList.setSmoothScrollbarEnabled(true);
        lyricsList.setPadding(0, dp(6), 0, dp(12));
        lyricsList.setVisibility(View.GONE);
        lyricsList.setAdapter(lyricsAdapter);
        lyricsList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        lyricsList.setOnItemClickListener((parent, view, position, id) -> seekToCue(position));
        lyricsList.setOnTouchListener((v, event) -> handleLyricsSwipeBack(event));
        contentPanel.addView(lyricsList, new LinearLayout.LayoutParams(-1, 0, 1));

        recentList = new ListView(this);
        recentList.setDivider(new ColorDrawable(Color.TRANSPARENT));
        recentList.setDividerHeight(dp(8));
        recentList.setCacheColorHint(Color.TRANSPARENT);
        recentList.setBackgroundColor(Color.TRANSPARENT);
        recentList.setClipToPadding(false);
        recentList.setScrollingCacheEnabled(false);
        recentList.setSmoothScrollbarEnabled(true);
        recentList.setPadding(0, dp(6), 0, dp(12));
        recentList.setVisibility(View.GONE);
        recentList.setAdapter(recentAdapter);
        recentList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        recentList.setOnItemClickListener((parent, view, position, id) -> openRecentTrack(recentTracks.get(position)));
        contentPanel.addView(recentList, new LinearLayout.LayoutParams(-1, 0, 1));

        settingsArea = buildSettingsArea();
        // 内层 settingsArea 的可见性由外层 settingsScroll 统一控制
        // 设置内容较多时需要可滚动，否则底部"感谢使用"面板会被截断
        settingsScroll = new ScrollView(this);
        settingsScroll.setFillViewport(true);
        settingsScroll.setVerticalScrollBarEnabled(false);
        settingsScroll.addView(settingsArea, new LinearLayout.LayoutParams(-1, -2));
        settingsScroll.setVisibility(View.GONE);
        contentPanel.addView(settingsScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        miniPlayer = new LinearLayout(this);
        miniPlayer.setOrientation(LinearLayout.VERTICAL);
        miniPlayer.setPadding(dp(12), dp(6), dp(12), dp(4));
        miniPlayer.setBackground(cardDrawable());
        miniPlayer.setVisibility(View.GONE);
        miniPlayer.setOnClickListener(v -> showPlayer());
        LinearLayout.LayoutParams miniParams = new LinearLayout.LayoutParams(-1, dp(62));
        miniParams.leftMargin = dp(10);
        miniParams.rightMargin = dp(10);
        miniParams.topMargin = dp(6);
        contentPanel.addView(miniPlayer, miniParams);

        LinearLayout miniTop = new LinearLayout(this);
        miniTop.setOrientation(LinearLayout.HORIZONTAL);
        miniTop.setGravity(Gravity.CENTER_VERTICAL);
        miniPlayer.addView(miniTop, new LinearLayout.LayoutParams(-1, dp(42)));

        miniCoverThumb = new ImageView(this);
        miniCoverThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        miniCoverThumb.setBackgroundColor(colorThumbBg);
        miniTop.addView(miniCoverThumb, new LinearLayout.LayoutParams(dp(40), dp(40)));

        miniTitleView = label("未播放", 14, colorTextPrimary);
        miniTitleView.setSingleLine(true);
        miniTitleView.setEllipsize(TextUtils.TruncateAt.END);
        miniTitleView.setPadding(dp(8), 0, 0, 0);
        miniTop.addView(miniTitleView, new LinearLayout.LayoutParams(0, -1, 1));

        miniTop.addView(miniControlButton("⏮", v -> playRelative(-1, false)), new LinearLayout.LayoutParams(dp(40), dp(36)));
        miniPlayButton = new Button(this);
        miniPlayButton.setText(ICON_PAUSE);
        miniPlayButton.setTextColor(Color.WHITE);
        miniPlayButton.setTextSize(16);
        miniPlayButton.setAllCaps(false);
        miniPlayButton.setMinWidth(0);
        miniPlayButton.setMinimumWidth(0);
        miniPlayButton.setPadding(0, 0, 0, 0);
        miniPlayButton.setBackground(roundRect(colorAccent, 18));
        miniPlayButton.setStateListAnimator(null);
        miniPlayButton.setElevation(0f);
        miniPlayButton.setOnClickListener(v -> togglePlayback());
        LinearLayout.LayoutParams miniPlayParams = new LinearLayout.LayoutParams(dp(40), dp(36));
        miniPlayParams.leftMargin = dp(6);
        miniPlayParams.rightMargin = dp(6);
        miniTop.addView(miniPlayButton, miniPlayParams);
        miniTop.addView(miniControlButton("⏭", v -> playRelative(1, false)), new LinearLayout.LayoutParams(dp(40), dp(36)));

        miniSeekBar = new SeekBar(this);
        miniSeekBar.setProgressDrawable(lineSeekDrawable());
        miniSeekBar.setThumb(null);
        miniSeekBar.setOnTouchListener((v, event) -> {
            showPlayer();
            return true;
        });
        miniPlayer.addView(miniSeekBar, new LinearLayout.LayoutParams(-1, dp(8)));

        miniTimeView = label("00:00 / 00:00", 1, Color.TRANSPARENT);
        miniTimeView.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        miniPlayer.addView(miniTimeView, new LinearLayout.LayoutParams(-1, 0, 0));

        bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER);
        bottomNav.setPadding(dp(2), dp(2), dp(2), dp(2));
        bottomNav.setBackgroundColor(Color.TRANSPARENT);
        heardTabButton = navButton("听过", TAB_HEARD);
        myTabButton = navButton("我的", TAB_MY);
        settingsTabButton = navButton("设置", TAB_SETTINGS);
        bottomNav.addView(heardTabButton, navButtonParams());
        bottomNav.addView(myTabButton, navButtonParams());
        bottomNav.addView(settingsTabButton, navButtonParams());
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(-1, dp(44));
        navParams.topMargin = dp(4);
        contentPanel.addView(bottomNav, navParams);

        updatePageChrome(false);
        setContentView(root);
    }

    private void computeThemeColors() {
        if (darkTheme) {
            colorBg = Color.rgb(15, 18, 22);
            colorTextPrimary = Color.rgb(228, 232, 238);
            colorTextSecondary = Color.rgb(154, 163, 174);
            colorAccent = Color.rgb(242, 160, 61);
            colorCard = Color.rgb(23, 27, 33);
            colorCardStroke = Color.rgb(38, 44, 53);
            colorChip = Color.rgb(30, 35, 43);
            colorIcon = Color.rgb(205, 211, 219);
            colorThumbBg = Color.rgb(34, 39, 47);
            colorWaveUnplayed = Color.rgb(42, 47, 56);
            colorLyric = Color.rgb(204, 210, 218);
        } else {
            colorBg = Color.rgb(255, 255, 255);
            colorTextPrimary = Color.rgb(62, 66, 72);
            colorTextSecondary = Color.rgb(138, 145, 156);
            colorAccent = Color.rgb(242, 160, 61);
            colorCard = Color.rgb(255, 255, 255);
            colorCardStroke = Color.rgb(231, 234, 239);
            colorChip = Color.rgb(242, 244, 247);
            colorIcon = Color.rgb(92, 98, 108);
            colorThumbBg = Color.rgb(237, 240, 244);
            colorWaveUnplayed = Color.rgb(226, 229, 234);
            colorLyric = Color.rgb(86, 90, 97);
        }
    }

    private GradientDrawable roundRect(int fillColor, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp((int) radiusDp));
        return drawable;
    }

    private GradientDrawable cardDrawable() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(colorCard);
        drawable.setCornerRadius(dp(12));
        drawable.setStroke(dp(1), colorCardStroke);
        return drawable;
    }

    private android.graphics.drawable.Drawable lineSeekDrawable() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(colorWaveUnplayed);
        background.setCornerRadius(dp(2));
        GradientDrawable progress = new GradientDrawable();
        progress.setColor(colorAccent);
        progress.setCornerRadius(dp(2));
        android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                progress, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable layer =
                new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{background, clip});
        layer.setId(0, android.R.id.background);
        layer.setId(1, android.R.id.progress);
        return layer;
    }

    private void applySystemBarAppearance() {
        Window window = getWindow();
        window.setStatusBarColor(colorBg);
        window.setNavigationBarColor(colorBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
            if (controller != null) {
                int appearance = darkTheme ? 0
                        : (WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                controller.setSystemBarsAppearance(appearance,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else if (!darkTheme) {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private LinearLayout buildFindArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setPadding(0, dp(4), 0, 0);

        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        urlRow.setGravity(Gravity.CENTER_VERTICAL);
        area.addView(urlRow, new LinearLayout.LayoutParams(-1, dp(42)));

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setTextColor(colorTextPrimary);
        urlInput.setHintTextColor(colorTextSecondary);
        urlInput.setTextSize(14);
        urlInput.setHint("输入网址，例如 https://example.com");
        urlInput.setPadding(dp(12), 0, dp(12), 0);
        urlInput.setBackground(roundRect(colorChip, 12));
        urlRow.addView(urlInput, new LinearLayout.LayoutParams(0, dp(38), 1));

        Button goButton = drawerButton("前往");
        goButton.setOnClickListener(v -> loadBrowserUrl(urlInput.getText().toString()));
        LinearLayout.LayoutParams goParams = new LinearLayout.LayoutParams(dp(64), dp(38));
        goParams.leftMargin = dp(8);
        urlRow.addView(goButton, goParams);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        settings.setTextZoom(100);
        settings.setLoadsImagesAutomatically(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        webView.setBackgroundColor(colorBg);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                urlInput.setText(url);
                addWebHistory(url);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                Toast.makeText(MainActivity.this, "网页加载失败: " + description, Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(-1, 0, 1);
        webParams.topMargin = dp(6);
        area.addView(webView, webParams);

        TextView historyTitle = label("访问记录", 13, colorTextSecondary);
        LinearLayout.LayoutParams historyTitleParams = new LinearLayout.LayoutParams(-1, dp(22));
        historyTitleParams.topMargin = dp(4);
        area.addView(historyTitle, historyTitleParams);

        webHistoryList = new ListView(this);
        webHistoryList.setDivider(new ColorDrawable(colorCardStroke));
        webHistoryList.setDividerHeight(1);
        webHistoryList.setCacheColorHint(Color.TRANSPARENT);
        webHistoryList.setBackgroundColor(Color.TRANSPARENT);
        webHistoryList.setAdapter(webHistoryAdapter);
        webHistoryList.setOnItemClickListener((parent, view, position, id) -> loadBrowserUrl(webHistory.get(position)));
        area.addView(webHistoryList, new LinearLayout.LayoutParams(-1, dp(62)));
        return area;
    }

    private LinearLayout buildSettingsArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setPadding(0, dp(18), 0, dp(12));

        Button themeButton = drawerButton(darkTheme ? "深色主题  开" : "深色主题  关");
        themeButton.setOnClickListener(v -> {
            darkTheme = !darkTheme;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DARK_THEME, darkTheme).apply();
            recreate();
        });
        area.addView(themeButton, new LinearLayout.LayoutParams(-1, dp(50)));

        Button addFolder = drawerButton("添加文件夹路径");
        addFolder.setOnClickListener(v -> openFolderPicker());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, dp(50));
        addParams.topMargin = dp(10);
        area.addView(addFolder, addParams);

        Button addNetwork = drawerButton("添加网络路径 (SMB/FTP)");
        addNetwork.setOnClickListener(v -> showNetSourceDialog());
        area.addView(addNetwork, new LinearLayout.LayoutParams(-1, dp(50)));

        Button rescanButton = drawerButton("重新扫描");
        rescanButton.setOnClickListener(v -> {
            if (roots.isEmpty()) {
                Toast.makeText(this, "请先添加文件夹路径", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "开始重新扫描", Toast.LENGTH_SHORT).show();
            rescanLibrary(false);
        });
        LinearLayout.LayoutParams rescanParams = new LinearLayout.LayoutParams(-1, dp(50));
        rescanParams.topMargin = dp(10);
        area.addView(rescanButton, rescanParams);

        TextView folderHint = label("支持添加多个文件夹路径，目录内容变化时会自动刷新作品列表。", 13, colorTextSecondary);
        folderHint.setMaxLines(3);
        LinearLayout.LayoutParams folderHintParams = new LinearLayout.LayoutParams(-1, -2);
        folderHintParams.topMargin = dp(8);
        area.addView(folderHint, folderHintParams);

        rootListContainer = new LinearLayout(this);
        rootListContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rootListParams = new LinearLayout.LayoutParams(-1, -2);
        rootListParams.topMargin = dp(10);
        area.addView(rootListContainer, rootListParams);
        refreshRootRows();

        floatingLyricsButton = drawerButton("台词悬浮");
        floatingLyricsButton.setOnClickListener(v -> toggleFloatingLyrics());
        LinearLayout.LayoutParams floatingParams = new LinearLayout.LayoutParams(-1, dp(50));
        floatingParams.topMargin = dp(10);
        area.addView(floatingLyricsButton, floatingParams);

        Button lineCountButton = drawerButton("台词行数  " + floatingLineCount + " 行");
        lineCountButton.setOnClickListener(v -> {
            floatingLineCount = floatingLineCount % 3 + 1;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt(KEY_FLOATING_LINES, floatingLineCount).apply();
            lineCountButton.setText("台词行数  " + floatingLineCount + " 行");
            lastFloatingRenderKey = null;
            updateFloatingLyricText();
        });
        LinearLayout.LayoutParams lineCountParams = new LinearLayout.LayoutParams(-1, dp(50));
        lineCountParams.topMargin = dp(10);
        area.addView(lineCountButton, lineCountParams);

        TextView hint = label("台词悬浮开启后会显示在手机页面上层，切到后台也会继续播放并更新台词，可拖动调整位置。", 13, colorTextSecondary);
        hint.setMaxLines(4);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(14);
        area.addView(hint, hintParams);

        LinearLayout thanksPanel = panel();
        LinearLayout.LayoutParams thanksParams = new LinearLayout.LayoutParams(-1, -2);
        thanksParams.topMargin = dp(24);
        area.addView(thanksPanel, thanksParams);

        TextView thanksTitle = label("感谢使用白沫播放器", 16, colorTextPrimary);
        thanksTitle.setGravity(Gravity.CENTER_VERTICAL);
        thanksPanel.addView(thanksTitle, new LinearLayout.LayoutParams(-1, dp(28)));

        TextView thanksBody = label("欢迎在 GitHub 提出意见、反馈问题或支持项目。你的建议会用于后续功能和体验优化。", 13, colorTextSecondary);
        thanksBody.setMaxLines(4);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.topMargin = dp(6);
        thanksPanel.addView(thanksBody, bodyParams);

        Button githubButton = drawerButton("打开 GitHub 项目");
        githubButton.setOnClickListener(v -> openExternalUrl(GITHUB_URL));
        LinearLayout.LayoutParams githubParams = new LinearLayout.LayoutParams(-1, dp(46));
        githubParams.topMargin = dp(12);
        thanksPanel.addView(githubButton, githubParams);
        return area;
    }

    private Button navButton(String text, int tab) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(v -> showMainTab(tab));
        return button;
    }

    private LinearLayout.LayoutParams navButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        return params;
    }

    private void buildSettingsDrawer(FrameLayout root) {
        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(Color.argb(130, 0, 0, 0));
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setOnClickListener(v -> hideSettingsDrawer());
        root.addView(drawerScrim, new FrameLayout.LayoutParams(-1, -1));

        settingsDrawer = new LinearLayout(this);
        settingsDrawer.setOrientation(LinearLayout.VERTICAL);
        settingsDrawer.setPadding(dp(18), dp(28), dp(18), dp(18));
        settingsDrawer.setBackgroundColor(colorBg);
        settingsDrawer.setVisibility(View.GONE);

        TextView title = label("设置", 20, colorTextPrimary);
        title.setGravity(Gravity.CENTER_VERTICAL);
        settingsDrawer.addView(title, new LinearLayout.LayoutParams(-1, dp(44)));

        Button chooseFolder = drawerButton("添加文件夹路径");
        chooseFolder.setOnClickListener(v -> {
            hideSettingsDrawer();
            openFolderPicker();
        });
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(-1, dp(48));
        chooseParams.topMargin = dp(12);
        settingsDrawer.addView(chooseFolder, chooseParams);

        Button chooseNetwork = drawerButton("添加网络路径 (SMB/FTP)");
        chooseNetwork.setOnClickListener(v -> {
            hideSettingsDrawer();
            showNetSourceDialog();
        });
        settingsDrawer.addView(chooseNetwork, new LinearLayout.LayoutParams(-1, dp(48)));

        floatingLyricsButton = drawerButton("台词悬浮");
        floatingLyricsButton.setOnClickListener(v -> toggleFloatingLyrics());
        LinearLayout.LayoutParams floatingParams = new LinearLayout.LayoutParams(-1, dp(48));
        floatingParams.topMargin = dp(10);
        settingsDrawer.addView(floatingLyricsButton, floatingParams);

        TextView hint = label("开启后台词会显示在手机页面上层，可拖动位置。", 12, colorTextSecondary);
        hint.setMaxLines(3);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(12);
        settingsDrawer.addView(hint, hintParams);

        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(dp(260), -1, Gravity.END);
        root.addView(settingsDrawer, drawerParams);
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable drawable = cardDrawable();
        drawable.setCornerRadius(dp(16));
        panel.setBackground(drawable);
        return panel;
    }

    private TextView label(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private Button drawerButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(colorTextPrimary);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(roundRect(colorChip, 12));
        button.setStateListAnimator(null);
        button.setElevation(0f);
        return button;
    }

    private void showSettingsDrawer() {
        if (settingsDrawer == null || drawerScrim == null) {
            return;
        }
        updateFloatingLyricsButton();
        drawerScrim.setVisibility(View.VISIBLE);
        settingsDrawer.setVisibility(View.VISIBLE);
        settingsDrawer.setTranslationX(dp(260));
        settingsDrawer.animate().translationX(0).setDuration(180).start();
    }

    private void hideSettingsDrawer() {
        if (settingsDrawer == null || drawerScrim == null || settingsDrawer.getVisibility() != View.VISIBLE) {
            return;
        }
        settingsDrawer.animate().translationX(dp(260)).setDuration(160).withEndAction(() -> {
            settingsDrawer.setVisibility(View.GONE);
            drawerScrim.setVisibility(View.GONE);
            settingsDrawer.setTranslationX(0);
        }).start();
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1f);
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        return params;
    }

    private ImageButton controlButton(String text, View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setContentDescription(text);
        button.setBackgroundColor(Color.TRANSPARENT);
        TextDrawable drawable = new TextDrawable(text, dp(15));
        drawable.setColor(colorIcon);
        button.setImageDrawable(drawable);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(listener);
        return button;
    }

    private ImageButton iconButton(String text, View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setContentDescription(text);
        button.setBackgroundColor(Color.TRANSPARENT);
        TextDrawable drawable = new TextDrawable(text);
        drawable.setColor(colorIcon);
        button.setImageDrawable(drawable);
        button.setOnClickListener(listener);
        return button;
    }

    private ImageButton miniControlButton(String text, View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setContentDescription(text);
        button.setBackgroundColor(Color.TRANSPARENT);
        TextDrawable drawable = new TextDrawable(text, dp(13));
        drawable.setColor(colorIcon);
        button.setImageDrawable(drawable);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(listener);
        return button;
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQ_OPEN_TREE);
    }

    private void openExternalUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        try {
            startActivity(intent);
        } catch (Exception ex) {
            Toast.makeText(this, "无法打开链接: " + url, Toast.LENGTH_LONG).show();
        }
    }

    private void toggleFloatingLyrics() {
        hideSettingsDrawer();
        if (!floatingLyricsEnabled) {
            floatingLyricsEnabled = true;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_FLOATING_LYRICS, true).apply();
            if (canDrawOverlays()) {
                showFloatingLyrics();
                Toast.makeText(this, "台词悬浮已开启", Toast.LENGTH_SHORT).show();
            } else {
                requestOverlayPermission();
            }
        } else {
            floatingLyricsEnabled = false;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_FLOATING_LYRICS, false).apply();
            hideFloatingLyrics();
            Toast.makeText(this, "台词悬浮已关闭", Toast.LENGTH_SHORT).show();
        }
        updateFloatingLyricsButton();
    }

    private void updateFloatingLyricsButton() {
        if (floatingLyricsButton == null) {
            return;
        }
        floatingLyricsButton.setText(floatingLyricsEnabled ? "台词悬浮  开" : "台词悬浮  关");
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        Toast.makeText(this, "请允许白沫播放器显示在其他应用上层", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, REQ_OVERLAY_PERMISSION);
    }

    private void showFloatingLyrics() {
        if (!floatingLyricsEnabled || !canDrawOverlays()) {
            return;
        }
        if (floatingLyricView != null) {
            updateFloatingLyricText();
            return;
        }
        // 清理可能残留的孤儿悬浮窗，避免出现双字幕
        if (sFloatingLyricRoot != null) {
            try {
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    wm.removeView(sFloatingLyricRoot);
                }
            } catch (Exception ignored) {
            }
            sFloatingLyricRoot = null;
        }
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(14), dp(8), dp(14), dp(8));
        box.setBackgroundColor(Color.argb(150, 16, 18, 21));
        for (int i = 0; i < floatingLineViews.length; i++) {
            TextView line = new TextView(this);
            line.setTextSize(i == 1 ? 15 : 13);
            line.setGravity(Gravity.CENTER);
            line.setSingleLine(false);
            line.setMaxLines(2);
            line.setEllipsize(TextUtils.TruncateAt.END);
            floatingLineViews[i] = line;
            box.addView(line, new LinearLayout.LayoutParams(-2, -2));
        }
        floatingLyricView = box;
        box.setOnTouchListener(new View.OnTouchListener() {
            private int startX;
            private int startY;
            private float downX;
            private float downY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (floatingLyricParams == null || windowManager == null) {
                    return false;
                }
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = floatingLyricParams.x;
                    startY = floatingLyricParams.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    floatingLyricParams.x = startX + (int) (event.getRawX() - downX);
                    floatingLyricParams.y = startY + (int) (event.getRawY() - downY);
                    windowManager.updateViewLayout(floatingLyricView, floatingLyricParams);
                    return true;
                }
                return true;
            }
        });

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        floatingLyricParams = new WindowManager.LayoutParams(
                dp(300),
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        floatingLyricParams.gravity = Gravity.TOP | Gravity.START;
        floatingLyricParams.x = dp(28);
        floatingLyricParams.y = dp(120);
        try {
            windowManager.addView(floatingLyricView, floatingLyricParams);
            sFloatingLyricRoot = floatingLyricView;
            lastFloatingRenderKey = null;
            updateFloatingLyricText();
        } catch (Exception ex) {
            floatingLyricView = null;
            java.util.Arrays.fill(floatingLineViews, null);
            Toast.makeText(this, "无法显示悬浮台词: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void hideFloatingLyrics() {
        if (floatingLyricView == null || windowManager == null) {
            floatingLyricView = null;
            sFloatingLyricRoot = null;
            java.util.Arrays.fill(floatingLineViews, null);
            return;
        }
        try {
            windowManager.removeView(floatingLyricView);
        } catch (Exception ignored) {
        }
        floatingLyricView = null;
        floatingLyricParams = null;
        sFloatingLyricRoot = null;
        java.util.Arrays.fill(floatingLineViews, null);
        lastFloatingRenderKey = null;
    }

    private void updateFloatingLyricText() {
        if (floatingLineViews[1] == null) {
            return;
        }
        MediaItem current = currentPlaybackItem();
        boolean hasCues = current != null && !cues.isEmpty();
        int idx = hasCues ? currentCueIndex : -1;
        String line0;
        String line1;
        String line2;
        if (!hasCues || idx < 0) {
            String fallback = current == null ? "等待播放台词" : current.audio.name;
            line0 = null;
            line1 = fallback;
            line2 = null;
        } else if (floatingLineCount == 1) {
            line0 = null;
            line1 = cues.get(idx).text;
            line2 = null;
        } else if (floatingLineCount == 2) {
            line0 = null;
            line1 = cues.get(idx).text;
            line2 = idx + 1 < cues.size() ? cues.get(idx + 1).text : null;
        } else {
            line0 = idx - 1 >= 0 ? cues.get(idx - 1).text : null;
            line1 = cues.get(idx).text;
            line2 = idx + 1 < cues.size() ? cues.get(idx + 1).text : null;
        }
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(floatingLineCount).append('|').append(line0).append('|').append(line1).append('|').append(line2);
        String key = keyBuilder.toString();
        if (key.equals(lastFloatingRenderKey)) {
            return;
        }
        lastFloatingRenderKey = key;
        setFloatingLine(0, line0, false);
        setFloatingLine(1, line1, idx >= 0);
        setFloatingLine(2, line2, false);
    }

    private void setFloatingLine(int index, String text, boolean highlight) {
        TextView line = floatingLineViews[index];
        if (line == null) {
            return;
        }
        if (TextUtils.isEmpty(text)) {
            line.setVisibility(View.GONE);
            line.setText("");
            return;
        }
        line.setVisibility(View.VISIBLE);
        line.setText(text);
        line.setTextColor(highlight ? Color.WHITE : Color.argb(210, 190, 196, 205));
        line.setTypeface(null, highlight ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void loadBrowserUrl(String rawUrl) {
        String url = normalizeUrl(rawUrl);
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(this, "请输入网址", Toast.LENGTH_SHORT).show();
            return;
        }
        urlInput.setText(url);
        webView.loadUrl(url);
    }

    private String normalizeUrl(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            url = "https://" + url;
        }
        return url;
    }

    private void loadWebHistory() {
        webHistory.clear();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_WEB_HISTORY, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String url = array.optString(i, "");
                if (!TextUtils.isEmpty(url)) {
                    webHistory.add(url);
                }
            }
        } catch (JSONException ignored) {
        }
        webHistoryAdapter.notifyDataSetChanged();
    }

    private void addWebHistory(String url) {
        if (TextUtils.isEmpty(url) || url.startsWith("about:")) {
            return;
        }
        webHistory.remove(url);
        webHistory.add(0, url);
        while (webHistory.size() > 50) {
            webHistory.remove(webHistory.size() - 1);
        }
        saveWebHistory();
        webHistoryAdapter.notifyDataSetChanged();
    }

    private void saveWebHistory() {
        JSONArray array = new JSONArray();
        for (String url : webHistory) {
            array.put(url);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_WEB_HISTORY, array.toString())
                .apply();
    }

    private void loadRecentTracks() {
        recentTracks.clear();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_RECENT_AUDIO, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                recentTracks.add(new RecentTrack(
                        object.optString("workKey", ""),
                        object.optString("trackKey", ""),
                        object.optString("workName", ""),
                        object.optString("trackName", "")
                ));
            }
        } catch (JSONException ignored) {
        }
        recentAdapter.notifyDataSetChanged();
    }

    private void recordRecentTrack(MediaItem item, WorkItem work) {
        if (item == null) {
            return;
        }
        String workKey = work == null ? "" : work.key;
        String workName = work == null ? item.audio.parentPath : work.name;
        for (int i = recentTracks.size() - 1; i >= 0; i--) {
            RecentTrack recent = recentTracks.get(i);
            if (recent.trackKey.equals(item.key) && recent.workKey.equals(workKey)) {
                recentTracks.remove(i);
            }
        }
        recentTracks.add(0, new RecentTrack(workKey, item.key, workName, item.audio.name));
        while (recentTracks.size() > 80) {
            recentTracks.remove(recentTracks.size() - 1);
        }
        saveRecentTracks();
        recentAdapter.notifyDataSetChanged();
        if (currentTab == TAB_HEARD) {
            sectionSubtitleView.setText("近期播放 " + recentTracks.size() + " 条");
        }
    }

    private void saveRecentTracks() {
        JSONArray array = new JSONArray();
        try {
            for (RecentTrack recent : recentTracks) {
                JSONObject object = new JSONObject();
                object.put("workKey", recent.workKey);
                object.put("trackKey", recent.trackKey);
                object.put("workName", recent.workName);
                object.put("trackName", recent.trackName);
                array.put(object);
            }
        } catch (JSONException ignored) {
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_RECENT_AUDIO, array.toString())
                .apply();
    }

    private void openRecentTrack(RecentTrack recent) {
        WorkItem work = findWorkByKey(recent.workKey);
        if (work == null) {
            Toast.makeText(this, "未找到该音频所在作品，请重新选择文件夹扫描", Toast.LENGTH_SHORT).show();
            return;
        }
        int trackIndex = findTrackIndex(work, recent.trackKey);
        if (trackIndex < 0) {
            Toast.makeText(this, "未找到该音频文件", Toast.LENGTH_SHORT).show();
            return;
        }
        MediaItem current = currentPlaybackItem();
        if (current != null && current.key.equals(recent.trackKey) && playbackWork != null && playbackWork.key.equals(work.key)) {
            showPlayer();
            return;
        }
        showMainTab(TAB_MY);
        openWork(work);
        playAt(trackIndex, true);
    }

    private WorkItem findWorkByKey(String key) {
        for (WorkItem work : works) {
            if (work.key.equals(key)) {
                return work;
            }
        }
        return null;
    }

    private int findTrackIndex(WorkItem work, String trackKey) {
        for (int i = 0; i < work.tracks.size(); i++) {
            if (work.tracks.get(i).key.equals(trackKey)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY_PERMISSION) {
            if (floatingLyricsEnabled && canDrawOverlays()) {
                showFloatingLyrics();
                Toast.makeText(this, "台词悬浮已开启", Toast.LENGTH_SHORT).show();
            } else if (floatingLyricsEnabled) {
                floatingLyricsEnabled = false;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_FLOATING_LYRICS, false).apply();
                updateFloatingLyricsButton();
            }
            return;
        }
        if (requestCode == REQ_OPEN_TREE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            addRoot(uri);
        }
    }

    private void addRoot(Uri uri) {
        String uriString = uri.toString();
        for (RootInfo root : roots) {
            if (root.uri.equals(uriString)) {
                Toast.makeText(this, "该文件夹已在路径列表中", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        roots.add(new RootInfo(uriString, displayNameFromTreeId(DocumentsContract.getTreeDocumentId(uri))));
        saveRoots();
        registerLibraryObserver();
        refreshRootRows();
        refreshFilterBar();
        rescanLibrary(false);
    }

    private void addNetRoot(NetSource source) {
        for (RootInfo root : roots) {
            if (root.net != null && root.net.type.equals(source.type)
                    && root.net.host.equals(source.host) && root.net.port == source.port
                    && root.net.path.equals(source.path)) {
                Toast.makeText(this, "该网络路径已在列表中", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if (TextUtils.isEmpty(source.id)) {
            source.id = "n" + Long.toHexString(System.currentTimeMillis());
        }
        if (TextUtils.isEmpty(source.name)) {
            source.name = source.host + (source.path.isEmpty() ? "" : "/" + source.path);
        }
        netSources.add(source);
        roots.add(rootForNet(source));
        saveRoots();
        syncProxySources();
        refreshRootRows();
        refreshFilterBar();
        rescanLibrary(false);
    }

    /** 添加 SMB / FTP 网络路径的输入对话框。 */
    private void showNetSourceDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(6), pad, 0);

        LinearLayout typeRow = new LinearLayout(this);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        final String[] type = {"smb"};
        Button smbToggle = typeToggle("SMB", true);
        Button ftpToggle = typeToggle("FTP", false);
        smbToggle.setOnClickListener(v -> {
            type[0] = "smb";
            smbToggle.setTextColor(colorAccent);
            ftpToggle.setTextColor(colorTextSecondary);
        });
        ftpToggle.setOnClickListener(v -> {
            type[0] = "ftp";
            ftpToggle.setTextColor(colorAccent);
            smbToggle.setTextColor(colorTextSecondary);
        });
        typeRow.addView(smbToggle, new LinearLayout.LayoutParams(0, dp(40), 1));
        typeRow.addView(ftpToggle, new LinearLayout.LayoutParams(0, dp(40), 1));
        box.addView(typeRow, new LinearLayout.LayoutParams(-1, -2));

        EditText hostInput = inputField(box, "服务器地址（如 192.168.1.10）");
        EditText portInput = inputField(box, "端口（SMB 默认 445，FTP 默认 21）");
        EditText pathInput = inputField(box, "SMB: 共享名/子目录    FTP: 目录（可空）");
        EditText userInput = inputField(box, "用户名（留空使用匿名/来宾访问）");
        EditText passInput = inputField(box, "密码（可空）");
        passInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText nameInput = inputField(box, "显示名（可空，默认用地址）");

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("添加网络路径")
                .setView(box)
                .setPositiveButton("添加", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            NetSource source = new NetSource();
            source.type = type[0];
            source.host = hostInput.getText().toString().trim();
            source.path = pathInput.getText().toString().trim();
            source.user = userInput.getText().toString().trim();
            source.pass = passInput.getText().toString();
            source.name = nameInput.getText().toString().trim();
            if (source.host.isEmpty()) {
                Toast.makeText(this, "请填写服务器地址", Toast.LENGTH_SHORT).show();
                return;
            }
            if ("smb".equals(source.type) && source.path.isEmpty()) {
                Toast.makeText(this, "SMB 需要填写共享名，如 media/asmr", Toast.LENGTH_SHORT).show();
                return;
            }
            String portText = portInput.getText().toString().trim();
            if (portText.isEmpty()) {
                source.port = "ftp".equals(source.type) ? 21 : 445;
            } else {
                try {
                    source.port = Integer.parseInt(portText);
                } catch (NumberFormatException ex) {
                    Toast.makeText(this, "端口格式不正确", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            Toast.makeText(this, "正在测试连接...", Toast.LENGTH_SHORT).show();
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            scanExecutor.execute(() -> {
                String error = null;
                try {
                    NetClient.list(source, 1);
                } catch (Exception ex) {
                    error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                }
                final String finalError = error;
                handler.post(() -> {
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    if (finalError != null) {
                        Toast.makeText(this, "连接失败: " + finalError, Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.dismiss();
                    addNetRoot(source);
                    Toast.makeText(this, "网络路径已添加，开始扫描", Toast.LENGTH_SHORT).show();
                });
            });
        }));
        dialog.show();
    }

    private Button typeToggle(String text, boolean active) {
        Button button = drawerButton(text);
        button.setTextColor(active ? colorAccent : colorTextSecondary);
        button.setTextSize(14);
        return button;
    }

    private EditText inputField(LinearLayout box, String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(colorTextPrimary);
        input.setHintTextColor(colorTextSecondary);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setBackground(cardDrawable());
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        box.addView(input, params);
        return input;
    }

    private void removeRoot(int index) {
        if (index < 0 || index >= roots.size()) {
            return;
        }
        RootInfo root = roots.remove(index);
        if (root.net == null) {
            try {
                getContentResolver().releasePersistableUriPermission(
                        Uri.parse(root.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
        } else {
            netSources.remove(root.net);
        }
        if (filterRootIndex >= roots.size()) {
            filterRootIndex = -1;
        }
        saveRoots();
        syncProxySources();
        registerLibraryObserver();
        refreshRootRows();
        refreshFilterBar();
        rescanLibrary(false);
        Toast.makeText(this, "已移除 " + root.name, Toast.LENGTH_SHORT).show();
    }

    private void loadRoots() {
        roots.clear();
        netSources.clear();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE_URIS, null);
        if (TextUtils.isEmpty(raw)) {
            String legacy = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE_URI, null);
            if (!TextUtils.isEmpty(legacy)) {
                try {
                    roots.add(new RootInfo(legacy,
                            displayNameFromTreeId(DocumentsContract.getTreeDocumentId(Uri.parse(legacy)))));
                } catch (Exception ignored) {
                }
            }
        } else {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    String uriString = array.optString(i, "");
                    if (TextUtils.isEmpty(uriString)) {
                        continue;
                    }
                    try {
                        roots.add(new RootInfo(uriString,
                                displayNameFromTreeId(DocumentsContract.getTreeDocumentId(Uri.parse(uriString)))));
                    } catch (Exception ignored) {
                    }
                }
            } catch (JSONException ignored) {
            }
        }
        try {
            String netRaw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_NET_SOURCES, null);
            if (!TextUtils.isEmpty(netRaw)) {
                JSONArray array = new JSONArray(netRaw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject object = array.optJSONObject(i);
                    if (object == null) {
                        continue;
                    }
                    NetSource source = NetSource.fromJson(object);
                    if (TextUtils.isEmpty(source.id)) {
                        continue;
                    }
                    netSources.add(source);
                    roots.add(rootForNet(source));
                }
            }
        } catch (JSONException ignored) {
        }
    }

    private RootInfo rootForNet(NetSource source) {
        RootInfo root = new RootInfo("net://" + source.id, source.name);
        root.net = source;
        return root;
    }

    private void saveRoots() {
        JSONArray array = new JSONArray();
        for (RootInfo root : roots) {
            if (root.net == null) {
                array.put(root.uri);
            }
        }
        JSONArray netArray = new JSONArray();
        for (NetSource source : netSources) {
            try {
                netArray.put(source.toJson());
            } catch (JSONException ignored) {
            }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_TREE_URIS, array.toString())
                .putString(KEY_NET_SOURCES, netArray.toString())
                .apply();
    }

    private void syncProxySources() {
        Map<String, NetSource> map = new HashMap<>();
        for (NetSource source : netSources) {
            map.put(source.id, source);
        }
        StreamProxy.get().setSources(map);
    }

    private void refreshRootRows() {
        if (rootListContainer == null) {
            return;
        }
        rootListContainer.removeAllViews();
        if (roots.isEmpty()) {
            TextView empty = label("尚未添加文件夹路径", 13, colorTextSecondary);
            rootListContainer.addView(empty, new LinearLayout.LayoutParams(-1, dp(34)));
            return;
        }
        for (int i = 0; i < roots.size(); i++) {
            final int index = i;
            RootInfo root = roots.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(cardDrawable());
            TextView name = label(root.name, 14, colorTextPrimary);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(dp(10), 0, dp(6), 0);
            row.addView(name, new LinearLayout.LayoutParams(0, -1, 1));
            Button remove = drawerButton("移除");
            remove.setTextSize(12);
            remove.setPadding(dp(6), 0, dp(6), 0);
            remove.setOnClickListener(v -> removeRoot(index));
            row.addView(remove, new LinearLayout.LayoutParams(dp(72), dp(38)));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(50));
            if (i > 0) {
                rowParams.topMargin = dp(6);
            }
            rootListContainer.addView(row, rowParams);
        }
    }

    private void registerLibraryObserver() {
        unregisterLibraryObserver();
        if (roots.isEmpty()) {
            return;
        }
        libraryObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                handler.removeCallbacks(pendingRescan);
                handler.postDelayed(pendingRescan, RESCAN_DEBOUNCE_MS);
            }
        };
        for (RootInfo root : roots) {
            if (root.net != null) {
                continue;
            }
            try {
                Uri treeUri = Uri.parse(root.uri);
                String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
                Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId);
                getContentResolver().registerContentObserver(childrenUri, true, libraryObserver);
            } catch (Exception ignored) {
            }
        }
    }

    private void unregisterLibraryObserver() {
        if (libraryObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(libraryObserver);
            } catch (Exception ignored) {
            }
            libraryObserver = null;
        }
        handler.removeCallbacks(pendingRescan);
    }

    private List<WorkItem> visibleWorks() {
        if (filterRootIndex < 0 || filterRootIndex >= roots.size()) {
            return works;
        }
        String rootUri = roots.get(filterRootIndex).uri;
        List<WorkItem> result = new ArrayList<>();
        for (WorkItem work : works) {
            if (rootUri.equals(work.rootUri)) {
                result.add(work);
            }
        }
        return result;
    }

    private void setFilter(int index) {
        filterRootIndex = index;
        updateFilterChips();
        browserAdapter.notifyDataSetChanged();
        int shown = visibleWorks().size();
        sectionSubtitleView.setText(filterRootIndex >= 0
                ? shown + " 个作品 · 筛选: " + roots.get(filterRootIndex).name
                : works.size() + " 个作品");
    }

    private void refreshFilterBar() {
        if (filterChipRow == null) {
            return;
        }
        filterChipRow.removeAllViews();
        if (roots.size() <= 1) {
            filterBar.setVisibility(View.GONE);
            return;
        }
        filterBar.setVisibility(View.VISIBLE);
        addFilterChip("全部", -1);
        for (int i = 0; i < roots.size(); i++) {
            addFilterChip(roots.get(i).name, i);
        }
        updateFilterChips();
    }

    private void addFilterChip(String text, final int index) {
        Button chip = new Button(this);
        chip.setText(text);
        chip.setTextSize(13);
        chip.setAllCaps(false);
        chip.setMinWidth(0);
        chip.setMinimumWidth(0);
        chip.setSingleLine(true);
        chip.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable chipBg = roundRect(colorChip, 16);
        chipBg.setStroke(dp(1), colorCardStroke);
        chip.setBackground(chipBg);
        chip.setStateListAnimator(null);
        chip.setElevation(0f);
        chip.setOnClickListener(v -> setFilter(index));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(32));
        if (filterChipRow.getChildCount() > 0) {
            params.leftMargin = dp(6);
        }
        filterChipRow.addView(chip, params);
    }

    private void updateFilterChips() {
        if (filterChipRow == null) {
            return;
        }
        for (int i = 0; i < filterChipRow.getChildCount(); i++) {
            Button chip = (Button) filterChipRow.getChildAt(i);
            boolean active = (i == 0 && filterRootIndex < 0) || (i > 0 && filterRootIndex == i - 1);
            chip.setTextColor(active ? colorAccent : colorTextSecondary);
            chip.setTextSize(active ? 14 : 13);
        }
    }

    private String rootNamesText() {
        StringBuilder builder = new StringBuilder();
        for (RootInfo root : roots) {
            if (builder.length() > 0) {
                builder.append("、");
            }
            builder.append(root.name);
        }
        return builder.length() == 0 ? "未选择文件夹" : builder.toString();
    }

    private void rescanLibrary(boolean auto) {
        if (roots.isEmpty()) {
            if (!auto) {
                Toast.makeText(this, "请先添加文件夹路径", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        boolean hasNetRoot = false;
        for (RootInfo candidate : roots) {
            if (candidate.net != null) {
                hasNetRoot = true;
                break;
            }
        }
        if (auto && hasNetRoot && System.currentTimeMillis() - lastNetScanAt < NET_RESCAN_MIN_MS) {
            return;
        }
        if (!scanInFlight.compareAndSet(false, true)) {
            return;
        }
        final boolean keepPlayback = currentPlaybackItem() != null;
        final List<RootInfo> rootsSnapshot = new ArrayList<>(roots);
        final List<WorkItem> cachedOrder = new ArrayList<>(works);
        if (!auto) {
            statusView.setText("扫描中...");
        }
        scanExecutor.execute(() -> {
            lastNetScanAt = System.currentTimeMillis();
            List<WorkItem> scanned = new ArrayList<>();
            Collator collator = Collator.getInstance(Locale.CHINA);
            for (RootInfo root : rootsSnapshot) {
                try {
                    List<FileDoc> files;
                    if (root.net != null) {
                        files = netListFilesRecursive(root.net);
                    } else {
                        Uri treeUri = Uri.parse(root.uri);
                        String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
                        files = listTreeFilesRecursive(treeUri, treeDocumentId);
                    }
                    List<WorkBuilder> builders = buildWorks(files, root.name, root.name + "::");
                    Collections.sort(builders, (left, right) -> collator.compare(left.name, right.name));
                    for (WorkBuilder builder : builders) {
                        WorkItem work = builder.toWork(collator);
                        work.rootUri = root.uri;
                        if (!work.tracks.isEmpty()) {
                            scanned.add(work);
                        }
                    }
                } catch (Exception ex) {
                    handler.post(() -> Toast.makeText(MainActivity.this,
                            "扫描 " + root.name + " 失败: " + ex.getMessage(), Toast.LENGTH_LONG).show());
                }
            }
            List<WorkItem> merged = mergeWorks(scanned, cachedOrder);
            scanInFlight.set(false);
            handler.post(() -> applyScanResults(merged, auto, keepPlayback));
        });
    }

    private void applyScanResults(List<WorkItem> merged, boolean auto, boolean keepPlayback) {
        boolean keepBrowsing = false;
        if (auto && activeWork != null) {
            for (WorkItem work : merged) {
                if (work.key.equals(activeWork.key)) {
                    activeWork = work;
                    playlist.clear();
                    playlist.addAll(work.tracks);
                    keepBrowsing = true;
                    break;
                }
            }
        }
        works.clear();
        if (!keepPlayback && !keepBrowsing) {
            playlist.clear();
            playbackQueue.clear();
            cues.clear();
            activeWork = null;
            playbackWork = null;
            currentIndex = -1;
            releasePlayer();
            resetPlaybackUi();
        }
        movingWorkIndex = -1;
        movingTrackIndex = -1;
        draggingListItem = false;
        works.addAll(merged);
        saveLibraryCache();
        browserAdapter.notifyDataSetChanged();
        updateMiniPlayer();
        boolean onWorksPage = currentTab == TAB_MY && pageMode == PAGE_WORKS;
        if (!auto || onWorksPage) {
            folderView.setText(rootNamesText());
        }
        if (!auto) {
            currentTab = TAB_MY;
            pageMode = PAGE_WORKS;
            updatePageChrome(false);
        }
        if (works.isEmpty()) {
            if (!auto || onWorksPage) {
                sectionTitleView.setText("作品列表");
                sectionSubtitleView.setText("没有识别到可播放作品");
                titleView.setText("没有识别到 ASMR 音频作品");
                lyricView.setText("请确认所选目录内包含 RJ 作品文件夹或音频文件");
                statusView.setText("目录已无作品");
            }
        } else if (!auto || onWorksPage) {
            sectionTitleView.setText("作品列表");
            sectionSubtitleView.setText("已识别 " + works.size() + " 个作品");
            titleView.setText("已识别 " + works.size() + " 个作品");
            lyricView.setText("点击作品查看音轨并播放");
            primeCoverFromCache(visibleWorks().get(0));
        }
        if (auto && onWorksPage) {
            statusView.setText("检测到文件变化，已自动刷新");
        } else if (!auto) {
            statusView.setText(works.size() + " 个作品");
        }
    }

    private List<WorkItem> mergeWorks(List<WorkItem> scannedWorks, List<WorkItem> cachedOrder) {
        Map<String, WorkItem> scannedByKey = new LinkedHashMap<>();
        for (WorkItem work : scannedWorks) {
            scannedByKey.put(work.key, work);
        }

        Map<String, WorkItem> cachedByKey = new HashMap<>();
        for (WorkItem work : cachedOrder) {
            cachedByKey.put(work.key, work);
        }

        List<WorkItem> merged = new ArrayList<>();
        for (WorkItem work : scannedWorks) {
            if (!cachedByKey.containsKey(work.key)) {
                merged.add(work);
            }
        }
        for (WorkItem cached : cachedOrder) {
            WorkItem scanned = scannedByKey.get(cached.key);
            if (scanned != null) {
                List<MediaItem> mergedTracks = mergeTracks(scanned.tracks, cached.tracks);
                scanned.tracks.clear();
                scanned.tracks.addAll(mergedTracks);
                merged.add(scanned);
            }
        }
        return merged;
    }

    private List<MediaItem> mergeTracks(List<MediaItem> scannedTracks, List<MediaItem> cachedOrder) {
        Map<String, MediaItem> scannedByKey = new LinkedHashMap<>();
        for (MediaItem item : scannedTracks) {
            scannedByKey.put(item.key, item);
        }

        Map<String, MediaItem> cachedByKey = new HashMap<>();
        for (MediaItem item : cachedOrder) {
            cachedByKey.put(item.key, item);
        }

        List<MediaItem> merged = new ArrayList<>();
        for (MediaItem item : scannedTracks) {
            if (!cachedByKey.containsKey(item.key)) {
                merged.add(item);
            }
        }
        for (MediaItem cached : cachedOrder) {
            MediaItem scanned = scannedByKey.get(cached.key);
            if (scanned != null) {
                merged.add(scanned);
            }
        }
        return merged;
    }

    private boolean loadCachedLibrary() {
        // 旧版缓存把整库 JSON 存进 SharedPreferences，大库读入即 OOM，直接废弃。
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_LIBRARY_CACHE).apply();
        File cacheFile = libraryCacheFile();
        if (!cacheFile.isFile()) {
            return false;
        }
        List<WorkItem> loaded;
        try {
            loaded = LibraryCache.load(cacheFile);
        } catch (Exception | OutOfMemoryError ex) {
            //noinspection ResultOfMethodCallIgnored
            cacheFile.delete();
            return false;
        }
        if (loaded.isEmpty()) {
            return false;
        }
        works.clear();
        playlist.clear();
        cues.clear();
        activeWork = null;
        playbackWork = null;
        currentIndex = -1;
        movingWorkIndex = -1;
        movingTrackIndex = -1;
        draggingListItem = false;
        resetPlaybackUi();
        updateMiniPlayer();
        works.addAll(loaded);
        lastCacheFingerprint = libraryFingerprint(works);
        currentTab = TAB_MY;
        pageMode = PAGE_WORKS;
        browserAdapter.notifyDataSetChanged();
        updatePageChrome(false);
        folderView.setText(rootNamesText());
        statusView.setText("已加载缓存");
        sectionTitleView.setText("作品列表");
        sectionSubtitleView.setText("已预加载 " + works.size() + " 个作品");
        titleView.setText("已预加载 " + works.size() + " 个作品");
        lyricView.setText("点击作品查看音轨并播放");
        primeCoverFromCache(works.get(0));
        return true;
    }

    /**
     * 旧版把整库 JSON 存进 SharedPreferences，大库残留会让首次读取直接 OOM。
     * 正常配置只有几 KB，超过 4MB 判定为旧缓存并丢弃（需要重新添加路径）。
     */
    private void purgeLegacyCacheIfOversized() {
        File prefsFile = new File(new File(getFilesDir().getParentFile(), "shared_prefs"), PREFS + ".xml");
        if (prefsFile.isFile() && prefsFile.length() > 4L * 1024 * 1024) {
            //noinspection ResultOfMethodCallIgnored
            prefsFile.delete();
            Toast.makeText(this, "已清除旧版缓存，请重新添加文件夹路径", Toast.LENGTH_LONG).show();
        }
    }

    private File libraryCacheFile() {
        return new File(getFilesDir(), "library_cache_v4.json");
    }

    /** 内容指纹：自动重扫结果没变化时不再重复写盘。 */
    private static long libraryFingerprint(List<WorkItem> snapshot) {
        long hash = 1125899906842597L;
        for (WorkItem work : snapshot) {
            hash = hash * 31 + (work.key == null ? 0 : work.key.hashCode());
            hash = hash * 31 + work.tracks.size();
            for (MediaItem item : work.tracks) {
                hash = hash * 31 + (item.audio == null || item.audio.uri == null
                        ? 0 : item.audio.uri.toString().hashCode());
            }
        }
        return hash;
    }

    private void saveLibraryCache() {
        final List<WorkItem> snapshot = new ArrayList<>(works);
        long fingerprint = libraryFingerprint(snapshot);
        if (fingerprint == lastCacheFingerprint) {
            return;
        }
        lastCacheFingerprint = fingerprint;
        cacheExecutor.execute(() -> {
            try {
                LibraryCache.save(libraryCacheFile(), snapshot);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_LIBRARY_CACHE).apply();
            } catch (Exception | OutOfMemoryError ex) {
                lastCacheFingerprint = -1;
                Log.w("AsmrPlayer", "保存作品缓存失败: " + ex);
            }
        });
    }

    private List<WorkBuilder> buildWorks(List<FileDoc> files, String selectedName, String keyPrefix) {
        Map<String, WorkBuilder> map = new LinkedHashMap<>();
        boolean selectedLooksLikeWork = looksLikeWorkName(selectedName);

        for (FileDoc file : files) {
            if (!isSupportedAsset(file.name)) {
                continue;
            }
            String key = selectedLooksLikeWork ? selectedName : firstPathSegment(file.relativePath);
            String fullKey = keyPrefix + key;
            String name = selectedLooksLikeWork || TextUtils.isEmpty(key) ? selectedName : key;
            WorkBuilder builder = map.get(fullKey);
            if (builder == null) {
                builder = new WorkBuilder(fullKey, name);
                map.put(fullKey, builder);
            }
            builder.add(file);
        }

        if (map.size() == 1 && !hasRootAudio(files) && !selectedLooksLikeWork) {
            WorkBuilder only = map.values().iterator().next();
            if (!looksLikeWorkName(only.name) && looksLikeWorkName(selectedName)) {
                only.name = selectedName;
            }
        }
        return new ArrayList<>(map.values());
    }

    private boolean hasRootAudio(List<FileDoc> files) {
        for (FileDoc file : files) {
            if (isAudio(file.name) && firstPathSegment(file.relativePath).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private List<FileDoc> listTreeFilesRecursive(Uri treeUri, String treeDocumentId) {
        List<FileDoc> files = new ArrayList<>();
        scanDocumentChildren(treeUri, treeDocumentId, treeDocumentId, "", 0, files);
        return files;
    }

    private List<FileDoc> netListFilesRecursive(NetSource source) throws Exception {
        List<NetClient.Entry> entries = NetClient.list(source, NetClient.MAX_SCAN_DEPTH);
        List<FileDoc> files = new ArrayList<>();
        for (NetClient.Entry entry : entries) {
            if (entry.isDir) {
                continue;
            }
            String relativePath = entry.dir.isEmpty() ? entry.name : entry.dir + "/" + entry.name;
            files.add(new FileDoc(entry.name, mimeForName(entry.name),
                    Uri.parse("net://" + source.id + "/" + relativePath), relativePath, entry.dir));
        }
        return files;
    }

    private static String mimeForName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return "";
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime != null) {
            return mime;
        }
        if (ext.equals("mp3")) {
            return "audio/mpeg";
        }
        if (ext.equals("wav")) {
            return "audio/wav";
        }
        if (ext.equals("vtt")) {
            return "text/vtt";
        }
        if (ext.equals("lrc")) {
            return "text/plain";
        }
        return "";
    }

    /** net:// 文件走本地代理；其余原样返回。 */
    private Uri toPlayableUri(Uri uri) {
        if (uri != null && "net".equals(uri.getScheme())) {
            String url = StreamProxy.get().urlFor(uri.toString());
            return Uri.parse(url);
        }
        return uri;
    }

    /** 统一的文件流打开入口：net:// 走 SMB/FTP，其余走 ContentResolver。 */
    private InputStream openStreamFor(Uri uri) throws java.io.IOException {
        if (uri != null && "net".equals(uri.getScheme())) {
            NetSource source = resolveNetSource(uri);
            if (source == null) {
                throw new java.io.IOException("网络路径不存在");
            }
            String path = uri.getPath();
            if (path == null) {
                path = "";
            }
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            try {
                return NetClient.open(source, path);
            } catch (java.io.IOException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new java.io.IOException(ex.getMessage() == null ? ex.toString() : ex.getMessage());
            }
        }
        return getContentResolver().openInputStream(uri);
    }

    private NetSource resolveNetSource(Uri uri) {
        if (uri == null) {
            return null;
        }
        String id = uri.getAuthority();
        for (NetSource source : netSources) {
            if (source.id.equals(id)) {
                return source;
            }
        }
        return null;
    }

    private void scanDocumentChildren(Uri treeUri, String rootId, String documentId, String relativeDir, int depth, List<FileDoc> files) {
        if (depth > MAX_SCAN_DEPTH) {
            return;
        }
        ContentResolver resolver = getContentResolver();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                return;
            }
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (TextUtils.isEmpty(name)) {
                    continue;
                }
                String relativePath = relativeDir.isEmpty() ? name : relativeDir + "/" + name;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    scanDocumentChildren(treeUri, rootId, id, relativePath, depth + 1, files);
                } else {
                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                    files.add(new FileDoc(name, mime, docUri, relativePath, relativeDir));
                }
            }
        } catch (Exception ex) {
            Toast.makeText(this, "读取文件夹失败: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onListItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (draggingListItem || movingWorkIndex >= 0 || movingTrackIndex >= 0) {
            return;
        }
        if (pageMode == PAGE_WORKS) {
            openWork(visibleWorks().get(position));
        } else if (pageMode == PAGE_TRACKS) {
            playAt(position, true);
        }
    }

    private boolean onListItemLongClick(int position) {
        if (pageMode == PAGE_WORKS && position >= 0 && position < visibleWorks().size()) {
            if (filterRootIndex >= 0) {
                Toast.makeText(this, "筛选状态下不能拖动排序，请先选“全部”", Toast.LENGTH_SHORT).show();
                return true;
            }
            movingWorkIndex = position;
            movingTrackIndex = -1;
            draggingListItem = true;
            browserAdapter.notifyDataSetChanged();
            sectionSubtitleView.setText("按住拖动作品，松开保存位置");
            Toast.makeText(this, "拖动到目标位置后松开", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (pageMode == PAGE_TRACKS && position >= 0 && position < playlist.size()) {
            movingTrackIndex = position;
            movingWorkIndex = -1;
            draggingListItem = true;
            browserAdapter.notifyDataSetChanged();
            sectionSubtitleView.setText("按住拖动音轨，松开保存位置");
            statusView.setText("拖动排序中");
            Toast.makeText(this, "拖动到目标位置后松开", Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    private boolean handleListDrag(MotionEvent event) {
        if (!draggingListItem) {
            if (currentTab == TAB_MY && pageMode == PAGE_TRACKS) {
                return handleTracksSwipeBack(event);
            }
            return false;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            int target = browserList.pointToPosition((int) event.getX(), (int) event.getY());
            if (target != AdapterView.INVALID_POSITION) {
                if (pageMode == PAGE_WORKS && movingWorkIndex >= 0 && target != movingWorkIndex) {
                    moveWorkDuringDrag(movingWorkIndex, target);
                } else if (pageMode == PAGE_TRACKS && movingTrackIndex >= 0 && target != movingTrackIndex) {
                    moveTrackDuringDrag(movingTrackIndex, target);
                }
            }
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            finishListDrag();
            return true;
        }
        return true;
    }

    private void finishListDrag() {        if (!draggingListItem) {
            return;
        }
        boolean movedWork = movingWorkIndex >= 0;
        boolean movedTrack = movingTrackIndex >= 0;
        movingWorkIndex = -1;
        movingTrackIndex = -1;
        draggingListItem = false;
        browserAdapter.notifyDataSetChanged();
        saveLibraryCache();
        if (movedWork) {
            sectionSubtitleView.setText("已保存作品顺序");
            statusView.setText("已移动作品");
        } else if (movedTrack) {
            sectionSubtitleView.setText("已保存音轨顺序");
            statusView.setText("已移动音轨");
        }
    }

    private int listSwipeBackDownX;
    private int listSwipeBackDownY;
    private boolean listSwipeBackActive;
    private boolean listSwipeBackAnimating;
    private int lyricsSwipeDownX;
    private int lyricsSwipeDownY;
    private boolean lyricsSwipeActive;

    /** 台词界面左右滑动返回正在播放界面的手势检测。 */
    private boolean handleLyricsSwipeBack(MotionEvent event) {
        if (listSwipeBackAnimating) {
            return true;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            lyricsSwipeDownX = (int) event.getRawX();
            lyricsSwipeDownY = (int) event.getRawY();
            lyricsSwipeActive = false;
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getRawX() - lyricsSwipeDownX;
            float dy = event.getRawY() - lyricsSwipeDownY;
            if (!lyricsSwipeActive
                    && Math.abs(dx) > dp(18) && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                lyricsSwipeActive = true;
                // 给 ListView 发 CANCEL 清除按压/滚动状态，避免松手误触台词跳转
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                lyricsList.onTouchEvent(cancel);
                cancel.recycle();
            }
            return lyricsSwipeActive;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean wasActive = lyricsSwipeActive;
            float dx = event.getRawX() - lyricsSwipeDownX;
            lyricsSwipeActive = false;
            if (wasActive && action == MotionEvent.ACTION_UP && Math.abs(dx) > dp(70)) {
                animateLyricsBackToPlayer(dx >= 0 ? 1 : -1);
                return true;
            }
            return wasActive;
        }
        return false;
    }

    /** 台词界面 → 正在播放界面的滑动返回动画。 */
    private void animateLyricsBackToPlayer(int dir) {
        if (listSwipeBackAnimating || pageMode != PAGE_LYRICS || lyricsList == null) {
            return;
        }
        listSwipeBackAnimating = true;
        lyricsList.animate().cancel();
        lyricsList.animate().translationX(dir * lyricsList.getWidth() * 0.35f).alpha(0f)
                .setDuration(200)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    lyricsList.setTranslationX(0f);
                    lyricsList.setAlpha(1f);
                    showPlayer();
                    listSwipeBackAnimating = false;
                }).start();
    }

    /** 音轨列表左右滑动返回作品列表的手势检测。 */
    private boolean handleTracksSwipeBack(MotionEvent event) {
        if (listSwipeBackAnimating) {
            return true;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            listSwipeBackDownX = (int) event.getRawX();
            listSwipeBackDownY = (int) event.getRawY();
            listSwipeBackActive = false;
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getRawX() - listSwipeBackDownX;
            float dy = event.getRawY() - listSwipeBackDownY;
            if (!listSwipeBackActive
                    && Math.abs(dx) > dp(18) && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                listSwipeBackActive = true;
                // 直接给 ListView 的 onTouchEvent 发 CANCEL，绕过本 view 的触摸监听，
                // 清除其按压/点击状态，避免松手时误触发条目点击或长按拖动
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                browserList.onTouchEvent(cancel);
                cancel.recycle();
            }
            return listSwipeBackActive;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean wasActive = listSwipeBackActive;
            float dx = Math.abs(event.getRawX() - listSwipeBackDownX);
            listSwipeBackActive = false;
            if (wasActive && action == MotionEvent.ACTION_UP && dx > dp(70)) {
                animateTracksBackToWorks();
                return true;
            }
            return wasActive;
        }
        return false;
    }

    private Bitmap snapshotView(View view) {
        Bitmap bitmap = Bitmap.createBitmap(
                Math.max(1, view.getWidth()), Math.max(1, view.getHeight()), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        view.draw(canvas);
        return bitmap;
    }

    /** 音轨列表 → 作品列表的上下覆盖切换动画。 */
    private void animateTracksBackToWorks() {
        if (listSwipeBackAnimating) {
            return;
        }
        listSwipeBackAnimating = true;
        // 只快照列表本身：标题、筛选 chips、迷你播放条、底部导航保持真实 UI
        // 静止显示，避免固定控件被快照捕获后随覆盖层滑动产生重影错乱
        View snapshotSource = browserList;
        // rectOnRoot 返回相对 rootLayout 的坐标，必须传 rootLayout 的屏幕位置，
        // 传 [0,0] 会得到屏幕绝对坐标，使覆盖层偏低一个窗口顶部偏移量
        int[] rootLoc = new int[2];
        rootLayout.getLocationOnScreen(rootLoc);
        int[] posTracks = rectOnRoot(snapshotSource, rootLoc);
        int listW = snapshotSource.getWidth();
        int tracksH = snapshotSource.getHeight();
        Bitmap tracksShot = snapshotView(snapshotSource);
        ImageView tracksOverlay = new ImageView(this);
        tracksOverlay.setImageBitmap(tracksShot);
        // ListView 快照的空白区是透明的，必须用页面背景色填死，
        // 否则会透出下方真实列表造成条目残影
        tracksOverlay.setBackgroundColor(colorBg);
        // 挡住触摸，避免动画期间穿透点到下方真实列表
        tracksOverlay.setClickable(true);
        FrameLayout.LayoutParams overlayParams =
                new FrameLayout.LayoutParams(listW, tracksH, Gravity.TOP | Gravity.START);
        tracksOverlay.setTranslationX(posTracks[0]);
        tracksOverlay.setTranslationY(posTracks[1]);
        rootLayout.addView(tracksOverlay, overlayParams);

        showWorks();
        // showWorks 会触发 180ms 淡入动画，立即取消并置为不透明，
        // 否则快照在淡入中截取会得到半透明列表
        browserList.animate().cancel();
        browserList.setAlpha(1f);
        // 筛选 chips 与覆盖动画同步：从上方滑入并淡入，避免突兀出现
        filterBar.setAlpha(0f);
        filterBar.setTranslationY(-dp(40));
        filterBar.animate().alpha(1f).translationY(0f).setDuration(300)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> filterBar.setTranslationY(0f)).start();

        browserList.post(() -> {
            int[] rootLocNow = new int[2];
            rootLayout.getLocationOnScreen(rootLocNow);
            int[] posWorks = rectOnRoot(snapshotSource, rootLocNow);
            int worksH = snapshotSource.getHeight();
            Bitmap worksShot = snapshotView(snapshotSource);
            // 裁剪容器固定在作品列表区域：快照在容器内部从上滑入，
            // 超出部分被裁剪，全程不会遮挡标题、chips、迷你播放条
            FrameLayout clipContainer = new FrameLayout(this);
            clipContainer.setClickable(true);
            clipContainer.setTranslationX(posWorks[0]);
            clipContainer.setTranslationY(posWorks[1]);
            rootLayout.addView(clipContainer,
                    new FrameLayout.LayoutParams(listW, worksH, Gravity.TOP | Gravity.START));
            ImageView worksOverlay = new ImageView(this);
            worksOverlay.setImageBitmap(worksShot);
            worksOverlay.setBackgroundColor(colorBg);
            worksOverlay.setClickable(true);
            clipContainer.addView(worksOverlay,
                    new FrameLayout.LayoutParams(listW, worksH, Gravity.TOP | Gravity.START));
            // 快照从容器顶部上方滑入，最终充满容器
            worksOverlay.setTranslationY(-worksH);
            worksOverlay.animate().translationY(0f)
                    .setDuration(300)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(() -> {
                        rootLayout.removeView(tracksOverlay);
                        rootLayout.removeView(clipContainer);
                        listSwipeBackAnimating = false;
                    }).start();
        });
    }

    private void moveWorkDuringDrag(int from, int to) {
        if (from < 0 || from >= works.size() || to < 0 || to >= works.size()) {
            movingWorkIndex = -1;
            return;
        }
        WorkItem item = works.remove(from);
        works.add(to, item);
        movingWorkIndex = to;
        browserAdapter.notifyDataSetChanged();
        sectionSubtitleView.setText("作品移动到第 " + (to + 1) + " 位");
        statusView.setText("拖动排序中");
    }

    private void moveTrackDuringDrag(int from, int to) {
        if (from < 0 || from >= playlist.size() || to < 0 || to >= playlist.size()) {
            movingTrackIndex = -1;
            return;
        }
        MediaItem item = playlist.remove(from);
        playlist.add(to, item);
        if (activeWork != null) {
            activeWork.tracks.clear();
            activeWork.tracks.addAll(playlist);
        }
        if (activeWork != null && activeWork == playbackWork && playbackQueue.size() == playlist.size()) {
            playbackQueue.clear();
            playbackQueue.addAll(playlist);
            if (currentIndex == from) {
                currentIndex = to;
            } else if (from < currentIndex && to >= currentIndex) {
                currentIndex--;
            } else if (from > currentIndex && to <= currentIndex) {
                currentIndex++;
            }
        }
        movingTrackIndex = to;
        browserAdapter.notifyDataSetChanged();
        sectionSubtitleView.setText("音轨移动到第 " + (to + 1) + " 位");
        statusView.setText("拖动排序中");
    }

    private void openWork(WorkItem work) {
        currentTab = TAB_MY;
        activeWork = work;
        movingWorkIndex = -1;
        movingTrackIndex = -1;
        draggingListItem = false;
        playlist.clear();
        playlist.addAll(work.tracks);
        pageMode = PAGE_TRACKS;
        updatePageChrome(true);
        browserAdapter.notifyDataSetChanged();
        if (player == null) {
            currentIndex = -1;
            resetPlaybackUi();
        }
        sectionTitleView.setText(work.name);
        sectionSubtitleView.setText("选择音轨进入播放页");
        titleView.setText(work.name);
        folderView.setText(work.tracks.size() + " 首音轨");
        lyricView.setText("点击音轨开始播放");
        primeCoverFromCache(work);
        statusView.setText("作品已打开");
        updateMiniPlayer();
    }

    private void showWorks() {
        currentTab = TAB_MY;
        pageMode = PAGE_WORKS;
        movingWorkIndex = -1;
        movingTrackIndex = -1;
        draggingListItem = false;
        updatePageChrome(true);
        browserAdapter.notifyDataSetChanged();
        // 复位筛选条状态，滑动返回动画会在此基础上重新做过渡
        filterBar.setAlpha(1f);
        filterBar.setTranslationY(0f);
        setSectionHeader("作品列表", works.size() + " 个作品");
        titleView.setText(works.isEmpty() ? "没有识别到 ASMR 音频作品" : "作品列表");
        folderView.setText(works.size() + " 个作品");
        lyricView.setText(works.isEmpty() ? "请选择包含 RJ 文件夹或音频文件的目录" : "点击作品查看音轨并播放");
        primeCoverFromCache(activeWork);
        updateMiniPlayer();
    }

    private void showTracks() {
        currentTab = TAB_MY;
        if (activeWork == null) {
            showWorks();
            return;
        }
        pageMode = PAGE_TRACKS;
        movingWorkIndex = -1;
        movingTrackIndex = -1;
        draggingListItem = false;
        updatePageChrome(true);
        browserAdapter.notifyDataSetChanged();
        setSectionHeader(activeWork.name, "音轨列表 · " + activeWork.tracks.size() + " 首音轨");
        titleView.setText(activeWork.name);
        folderView.setText(activeWork.tracks.size() + " 首音轨");
        MediaItem current = currentPlaybackItem();
        lyricView.setText(current == null ? "点击音轨进入播放页" : "正在播放: " + current.audio.name);
        statusView.setText(currentIndex >= 0 && player != null && player.isPlaying() ? "播放中" : "音轨列表");
        primeCoverFromCache(activeWork);
        updateMiniPlayer();
    }

    private void showPlayer() {
        currentTab = TAB_MY;
        pageMode = PAGE_PLAYER;
        updatePageChrome(true);
        browserAdapter.notifyDataSetChanged();
        MediaItem current = currentPlaybackItem();
        setPlayerHeader(playbackWork == null ? "" : playbackWork.name);
        if (current != null) {
            titleView.setText(current.audio.name);
            folderView.setText(playbackWork == null ? current.audio.parentPath : playbackWork.name);
            showCurrentTrackImage();
        }
        updateMiniPlayer();
        playerArea.setTranslationY(dp(150));
        playerArea.setAlpha(0f);
        playerArea.animate().translationY(0f).alpha(1f).setDuration(260)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    private void showLyrics() {
        if (cues.isEmpty()) {
            Toast.makeText(this, "当前音轨没有可查看的台词（支持 .vtt / .lrc）", Toast.LENGTH_SHORT).show();
            return;
        }
        currentTab = TAB_MY;
        pageMode = PAGE_LYRICS;
        updatePageChrome(true);
        lyricsAdapter.notifyDataSetChanged();
        MediaItem current = currentPlaybackItem();
        setSectionHeader("台词", current == null ? "点击台词跳转播放位置" : current.audio.name);
        if (currentCueIndex >= 0) {
            lyricsList.post(() -> lyricsList.smoothScrollToPosition(currentCueIndex));
        }
        updateMiniPlayer();
    }

    private void goBackPage() {
        if (viewerOverlay != null && viewerOverlay.getVisibility() == View.VISIBLE) {
            closeImageViewer();
            return;
        }
        if (pageMode == PAGE_PLAYER) {
            collapsePlayerToPlaylist();
        } else if (pageMode == PAGE_LYRICS) {
            showPlayer();
        } else if (pageMode == PAGE_TRACKS) {
            showWorks();
        }
    }

    private void collapsePlayerToPlaylist() {
        if (pageMode != PAGE_PLAYER || playerArea == null) {
            return;
        }
        playerArea.animate().cancel();
        playerArea.animate().translationY(playerArea.getHeight()).alpha(0f).setDuration(240)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    playerArea.setTranslationY(0f);
                    playerArea.setAlpha(1f);
                    showTracks();
                    if (miniPlayer != null && miniPlayer.getVisibility() == View.VISIBLE) {
                        miniPlayer.setTranslationY(dp(70));
                        miniPlayer.animate().translationY(0f).setDuration(200).start();
                    }
                }).start();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (pageMode == PAGE_PLAYER && playerArea != null && playerArea.getVisibility() == View.VISIBLE
                && (viewerOverlay == null || viewerOverlay.getVisibility() != View.VISIBLE)) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                playerCollapseDownX = event.getRawX();
                playerCollapseDownY = event.getRawY();
                playerCollapseIntent = false;
                if (collapseVelocityTracker != null) {
                    collapseVelocityTracker.clear();
                } else {
                    collapseVelocityTracker = VelocityTracker.obtain();
                }
                collapseVelocityTracker.addMovement(event);
            } else if (collapseVelocityTracker != null) {
                collapseVelocityTracker.addMovement(event);
            }
            if (action == MotionEvent.ACTION_MOVE && !playerCollapseIntent) {
                float dx = event.getRawX() - playerCollapseDownX;
                float dy = event.getRawY() - playerCollapseDownY;
                if (dy > dp(26) && dy > Math.abs(dx) * 1.2f) {
                    playerCollapseIntent = true;
                    resetImageTouchState();
                }
            }
            if (playerCollapseIntent) {
                if (action == MotionEvent.ACTION_MOVE) {
                    float dy = Math.max(0f, event.getRawY() - playerCollapseDownY);
                    playerArea.animate().cancel();
                    playerArea.setTranslationY(dy * 0.9f);
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    float velocityY = 0f;
                    if (collapseVelocityTracker != null) {
                        collapseVelocityTracker.computeCurrentVelocity(1000);
                        velocityY = collapseVelocityTracker.getYVelocity();
                        collapseVelocityTracker.recycle();
                        collapseVelocityTracker = null;
                    }
                    playerCollapseIntent = false;
                    float dy = Math.max(0f, event.getRawY() - playerCollapseDownY);
                    if (dy > playerArea.getHeight() * 0.22f || velocityY > dp(1400)) {
                        collapsePlayerToPlaylist();
                    } else {
                        playerArea.animate().translationY(0f).alpha(1f).setDuration(180).start();
                    }
                    return true;
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (collapseVelocityTracker != null) {
                    collapseVelocityTracker.recycle();
                    collapseVelocityTracker = null;
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private void resetImageTouchState() {
        if (imageVelocityTracker != null) {
            imageVelocityTracker.recycle();
            imageVelocityTracker = null;
        }
        imageDragging = false;
        imageHorizontalIntent = false;
        if (coverView != null && !imageSwitching) {
            coverView.setTranslationX(0f);
        }
    }

    private boolean handleWaveformTouch(MotionEvent event) {
        if (player == null) {
            return true;
        }
        int width = waveform.getWidth();
        int duration;
        try {
            duration = player.getDuration();
        } catch (IllegalStateException ex) {
            return true;
        }
        if (width <= 0 || duration <= 0) {
            return true;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            userSeeking = true;
            float fraction = Math.max(0f, Math.min(1f, event.getX() / (float) width));
            int target = (int) (fraction * duration);
            waveform.setPosition(target, duration);
            timeView.setText(formatTime(target) + " / " + formatTime(duration));
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            float fraction = Math.max(0f, Math.min(1f, event.getX() / (float) width));
            int target = (int) (fraction * duration);
            player.seekTo(target);
            waveform.setPosition(target, duration);
            timeView.setText(formatTime(target) + " / " + formatTime(duration));
            userSeeking = false;
            return true;
        }
        return true;
    }

    private void showMainTab(int tab) {
        if (tab == TAB_MY) {
            if (currentTab == TAB_MY && (pageMode == PAGE_TRACKS || pageMode == PAGE_PLAYER || pageMode == PAGE_LYRICS)) {
                showWorks();
                return;
            }
            restoreMyTabFast();
            return;
        }
        if (currentTab == tab) {
            return;
        }
        currentTab = tab;
        updatePageChrome(false);
        if (tab == TAB_HEARD) {
            setSectionHeader("听过", recentTracks.isEmpty() ? "还没有播放记录" : "近期播放 " + recentTracks.size() + " 条");
            recentAdapter.notifyDataSetChanged();
        } else if (tab == TAB_SETTINGS) {
            setSectionHeader("设置", "文件夹选择与台词悬浮");
            updateFloatingLyricsButton();
        }
    }

    private void restoreMyTabFast() {
        currentTab = TAB_MY;
        if ((pageMode == PAGE_TRACKS && activeWork == null)
                || (pageMode == PAGE_PLAYER && currentPlaybackItem() == null)
                || (pageMode == PAGE_LYRICS && cues.isEmpty())) {
            pageMode = PAGE_WORKS;
        }
        updateMyHeaderFast();
        updatePageChrome(false);
    }

    private void updateMyHeaderFast() {
        if (pageMode == PAGE_WORKS) {
            setSectionHeader("作品列表", works.size() + " 个作品");
            titleView.setText(works.isEmpty() ? "没有识别到 ASMR 音频作品" : "作品列表");
            folderView.setText(works.size() + " 个作品");
            lyricView.setText(works.isEmpty() ? "请选择包含 RJ 文件夹或音频文件的目录" : "点击作品查看音轨并播放");
        } else if (pageMode == PAGE_TRACKS && activeWork != null) {
            setSectionHeader(activeWork.name, "音轨列表 · " + activeWork.tracks.size() + " 首音轨");
            titleView.setText(activeWork.name);
            folderView.setText(activeWork.tracks.size() + " 首音轨");
            MediaItem current = currentPlaybackItem();
            lyricView.setText(current == null ? "点击音轨进入播放页" : "正在播放: " + current.audio.name);
        } else if (pageMode == PAGE_PLAYER) {
            MediaItem current = currentPlaybackItem();
            setPlayerHeader(playbackWork == null ? "" : playbackWork.name);
            if (current != null) {
                titleView.setText(current.audio.name);
                folderView.setText(playbackWork == null ? current.audio.parentPath : playbackWork.name);
            }
        } else if (pageMode == PAGE_LYRICS) {
            MediaItem current = currentPlaybackItem();
            setSectionHeader("台词", current == null ? "点击台词跳转播放位置" : current.audio.name);
        }
    }

    private void updateBottomNav() {
        configureNavButton(heardTabButton, currentTab == TAB_HEARD);
        configureNavButton(myTabButton, currentTab == TAB_MY);
        configureNavButton(settingsTabButton, currentTab == TAB_SETTINGS);
    }

    private void configureNavButton(Button button, boolean active) {
        if (button == null) {
            return;
        }
        button.setTextColor(active ? colorAccent : colorTextSecondary);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setTextSize(active ? 15 : 13);
    }

    private void setSectionHeader(String title, String subtitle) {
        sectionTitleView.setText(title);
        sectionTitleView.setTextSize(22);
        sectionTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        sectionTitleView.setTextColor(colorTextPrimary);
        sectionSubtitleView.setText(subtitle);
        sectionSubtitleView.setTextSize(13);
        sectionSubtitleView.setTypeface(Typeface.DEFAULT);
        sectionSubtitleView.setTextColor(colorTextSecondary);
    }

    private void setPlayerHeader(String subtitle) {
        sectionTitleView.setText("正在播放");
        sectionTitleView.setTextSize(13);
        sectionTitleView.setTypeface(Typeface.DEFAULT);
        sectionTitleView.setTextColor(colorTextSecondary);
        sectionSubtitleView.setText(subtitle);
        sectionSubtitleView.setTextSize(20);
        sectionSubtitleView.setTypeface(Typeface.DEFAULT_BOLD);
        sectionSubtitleView.setTextColor(colorTextPrimary);
    }

    private void updatePageChrome(boolean animate) {
        boolean myTab = currentTab == TAB_MY;
        boolean playerVisible = myTab && pageMode == PAGE_PLAYER;
        boolean lyricsVisible = myTab && pageMode == PAGE_LYRICS;
        boolean listVisible = myTab && (pageMode == PAGE_WORKS || pageMode == PAGE_TRACKS);
        if (!playerVisible) {
            hideImagePeer(false);
        }
        if (!playerVisible && coverTopThumb != null) {
            coverTopThumb.setVisibility(View.GONE);
        }
        playerArea.setVisibility(playerVisible ? View.VISIBLE : View.GONE);
        browserList.setVisibility(listVisible ? View.VISIBLE : View.GONE);
        filterBar.setVisibility(listVisible && pageMode == PAGE_WORKS && roots.size() > 1 ? View.VISIBLE : View.GONE);
        lyricsList.setVisibility(lyricsVisible ? View.VISIBLE : View.GONE);
        recentList.setVisibility(currentTab == TAB_HEARD ? View.VISIBLE : View.GONE);
        settingsScroll.setVisibility(currentTab == TAB_SETTINGS ? View.VISIBLE : View.GONE);
        if (currentTab == TAB_SETTINGS) {
            settingsScroll.scrollTo(0, 0);
        }
        updateBottomNav();
        updateMiniPlayer();
        if (animate) {
            View target = playerVisible ? playerArea
                    : (lyricsVisible ? lyricsList
                    : (listVisible ? browserList
                    : (currentTab == TAB_HEARD ? recentList : settingsScroll)));
            target.setAlpha(0f);
            target.animate().alpha(1f).setDuration(180).start();
        }
    }

    /**
     * 列表页只把「已在内存中」的封面大图放进 coverView，供切换动画衔接使用。
     * 旧实现在这里调用 showBigImage，会在播放区不可见时仍下载 1200px 大图并
     * 预取相邻两张，是 SMB 作品列表卡顿的主要来源。
     */
    private void primeCoverFromCache(WorkItem work) {
        FileDoc cover = work == null ? null : work.cover;
        if (cover == null) {
            coverView.setTag(null);
            coverView.setImageDrawable(null);
            return;
        }
        String key = cover.uri.toString();
        coverView.setTag(key);
        Bitmap cached = bigImageCache.get(key);
        coverView.setImageBitmap(cached != null && !cached.isRecycled() ? cached : null);
    }

    private void playAt(int index) {
        playAt(index, true);
    }

    private void playAt(int index, boolean openPlayerPage) {
        if (index < 0 || index >= playlist.size()) {
            return;
        }
        playbackQueue.clear();
        playbackQueue.addAll(playlist);
        playbackWork = activeWork;
        playFromQueue(index, openPlayerPage);
    }

    private void playFromQueue(int index, boolean openPlayerPage) {
        if (index < 0 || index >= playbackQueue.size()) {
            return;
        }
        // 捕获切换前正在显示的图片，切歌后若无同名图则保持显示它
        MediaItem prevTrack = currentPlaybackItem();
        FileDoc prevShownImage = null;
        if (prevTrack != null && !prevTrack.images.isEmpty()
                && currentImageIndex >= 0 && currentImageIndex < prevTrack.images.size()) {
            prevShownImage = prevTrack.images.get(currentImageIndex);
        }
        currentIndex = index;
        if (openPlayerPage) {
            showPlayer();
        }
        browserAdapter.notifyDataSetChanged();
        MediaItem item = playbackQueue.get(index);
        recordRecentTrack(item, playbackWork);
        titleView.setText(item.audio.name);
        folderView.setText(playbackWork == null ? item.audio.parentPath : playbackWork.name);
        lyricView.setText(item.vtt == null ? "未找到字幕文件" : "字幕已载入");
        updateFloatingLyricText();
        currentImageIndex = resolveImageIndexForCurrentAudio(prevShownImage);
        coverView.setTranslationX(0f);
        coverView.setAlpha(1f);
        imageSwitching = false;
        imageDragging = false;
        imageAnimGeneration++;
        showCurrentTrackImage();
        loadCues(item.vtt);
        currentCueIndex = -1;
        lyricsAdapter.notifyDataSetChanged();
        releasePlayer();
        waveform.setWaveform(null);
        player = new MediaPlayer();
        try {
            player.setDataSource(this, toPlayableUri(item.audio.uri));
            player.setOnPreparedListener(mp -> {
                int duration = mp.getDuration();
                waveform.setDuration(duration);
                timeView.setText("00:00 / " + formatTime(duration));
                waveform.setPosition(0, duration);
                mp.start();
                prepareWaveform(item.audio.uri, duration);
                playButton.setText(ICON_PAUSE);
                miniPlayButton.setText(ICON_PAUSE);
                statusView.setText("播放中");
                handler.removeCallbacks(progressTick);
                handler.post(progressTick);
                updateMiniPlayer();
                updateFloatingLyricText();
            });
            player.setOnCompletionListener(mp -> playRelative(1, pageMode == PAGE_PLAYER));
            player.prepareAsync();
            statusView.setText("加载中");
            updateMiniPlayer();
        } catch (Exception ex) {
            Toast.makeText(this, "无法播放: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            statusView.setText("播放失败");
            updateMiniPlayer();
            updateFloatingLyricText();
        }
    }

    private void togglePlayback() {
        if (player == null) {
            if (!playbackQueue.isEmpty()) {
                playFromQueue(currentIndex >= 0 ? currentIndex : 0, true);
            } else if (!playlist.isEmpty()) {
                playAt(0, true);
            } else if (!works.isEmpty() && pageMode == PAGE_WORKS) {
                openWork(works.get(0));
            }
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            playButton.setText(ICON_PLAY);
            miniPlayButton.setText(ICON_PLAY);
            statusView.setText("已暂停");
        } else {
            player.start();
            playButton.setText(ICON_PAUSE);
            miniPlayButton.setText(ICON_PAUSE);
            statusView.setText("播放中");
            handler.post(progressTick);
        }
        updateMiniPlayer();
        updateFloatingLyricText();
    }

    private void playRelative(int delta) {
        playRelative(delta, true);
    }

    private void playRelative(int delta, boolean openPlayerPage) {
        if (playbackQueue.isEmpty()) {
            return;
        }
        int next = currentIndex + delta;
        if (next < 0) {
            next = playbackQueue.size() - 1;
        } else if (next >= playbackQueue.size()) {
            next = 0;
        }
        playFromQueue(next, openPlayerPage);
    }

    private void seekBy(int millis) {
        if (player == null) {
            return;
        }
        int target = Math.max(0, Math.min(player.getDuration(), player.getCurrentPosition() + millis));
        player.seekTo(target);
        updateProgress();
    }

    private void updateProgress() {
        if (player == null) {
            return;
        }
        try {
            int position = player.getCurrentPosition();
            int duration = player.getDuration();
            if (!userSeeking) {
                waveform.setPosition(position, duration);
            }
            timeView.setText(formatTime(position) + " / " + formatTime(duration));
            if (miniSeekBar.getMax() != duration) {
                miniSeekBar.setMax(duration);
            }
            miniSeekBar.setProgress(position);
            miniTimeView.setText(formatTime(position) + " / " + formatTime(duration));
            updateLyric(position);
            if (player.isPlaying() && !"播放中".contentEquals(statusView.getText())) {
                statusView.setText("播放中");
            }
            updateMiniPlayer();
        } catch (IllegalStateException ignored) {
        }
    }

    private void updateLyric(int positionMs) {
        if (cues.isEmpty()) {
            return;
        }
        int activeIndex = -1;
        for (int i = 0; i < cues.size(); i++) {
            Cue cue = cues.get(i);
            if (positionMs >= cue.startMs) {
                activeIndex = i;
            } else {
                break;
            }
        }
        if (activeIndex < 0) {
            lyricView.setText("");
            updateFloatingLyricText();
            if (currentCueIndex != -1) {
                currentCueIndex = -1;
                lyricsAdapter.notifyDataSetChanged();
            }
            return;
        }
        Cue activeCue = cues.get(activeIndex);
        boolean textChanged = !activeCue.text.contentEquals(lyricView.getText());
        if (textChanged) {
            lyricView.setText(activeCue.text);
        }
        if (currentCueIndex != activeIndex) {
            currentCueIndex = activeIndex;
            lyricsAdapter.notifyDataSetChanged();
            if (pageMode == PAGE_LYRICS) {
                lyricsList.smoothScrollToPosition(activeIndex);
            }
        }
        if (textChanged || currentCueIndex == activeIndex) {
            updateFloatingLyricText();
        }
    }

    private void clearLyricState() {
        lyricView.setText("");
        updateFloatingLyricText();
        if (currentCueIndex != -1) {
            currentCueIndex = -1;
            lyricsAdapter.notifyDataSetChanged();
        }
    }

    private void loadCues(FileDoc subtitleFile) {
        cues.clear();
        currentCueIndex = -1;
        lyricsAdapter.notifyDataSetChanged();
        updateFloatingLyricText();
        if (subtitleFile == null) {
            return;
        }
        boolean isLrc = subtitleFile.name.toLowerCase(Locale.ROOT).endsWith(".lrc");
        Uri subtitleUri = subtitleFile.uri;
        // 网络字幕走原始 socket，禁止在主线程读取，放到后台执行
        auxExecutor.execute(() -> {
            String text;
            try (InputStream input = openStreamFor(subtitleUri)) {
                if (input == null) {
                    return;
                }
                text = new String(readAll(input), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")
                        .replace("\r", "\n");
            } catch (Exception ex) {
                handler.post(() -> Toast.makeText(MainActivity.this,
                        "字幕读取失败: " + ex.getMessage(), Toast.LENGTH_LONG).show());
                return;
            }
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            List<Cue> parsed = new ArrayList<>();
            if (isLrc) {
                parseLrcLines(text, parsed);
            } else {
                String[] blocks = text.split("\n\n+");
                for (String block : blocks) {
                    parseCueBlock(block.trim(), parsed);
                }
            }
            Collections.sort(parsed, (left, right) -> Long.compare(left.startMs, right.startMs));
            handler.post(() -> {
                // 快速切歌时仅当字幕仍属于当前曲目才应用
                MediaItem current = currentPlaybackItem();
                if (current == null || current.vtt == null || !subtitleUri.equals(current.vtt.uri)) {
                    return;
                }
                cues.clear();
                cues.addAll(parsed);
                lyricsAdapter.notifyDataSetChanged();
                updateFloatingLyricText();
            });
        });
    }

    /** 解析 LRC：一行可带多个 [mm:ss.xx] 时间标签。 */
    private void parseLrcLines(String text, List<Cue> out) {
        java.util.regex.Pattern tagPattern = java.util.regex.Pattern.compile("\\[(\\d{1,2}:\\d{1,2}(?:[.:]\\d{1,3})?)\\]");
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("[") && !tagPattern.matcher(line).find()) {
                continue;
            }
            java.util.regex.Matcher matcher = tagPattern.matcher(line);
            List<Long> starts = new ArrayList<>();
            int textStart = 0;
            while (matcher.find()) {
                try {
                    starts.add(parseTimestamp(matcher.group(1).replace(',', '.')));
                } catch (Exception ignored) {
                }
                textStart = matcher.end();
            }
            String content = line.substring(textStart).trim();
            if (starts.isEmpty() || TextUtils.isEmpty(content) || content.startsWith("<")) {
                continue;
            }
            for (long start : starts) {
                out.add(new Cue(start, start + 5000, content));
            }
        }
    }

    private void seekToCue(int position) {
        if (position < 0 || position >= cues.size() || player == null) {
            return;
        }
        Cue cue = cues.get(position);
        player.seekTo((int) cue.startMs);
        if (!player.isPlaying()) {
            player.start();
        }
        currentCueIndex = position;
        lyricView.setText(cue.text);
        updateFloatingLyricText();
        playButton.setText(ICON_PAUSE);
        miniPlayButton.setText(ICON_PAUSE);
        statusView.setText("播放中");
        handler.post(progressTick);
        lyricsAdapter.notifyDataSetChanged();
        updateProgress();
    }

    private void parseCueBlock(String block, List<Cue> out) {
        if (block.isEmpty() || block.equals("WEBVTT") || block.startsWith("NOTE")) {
            return;
        }
        String[] lines = block.split("\n");
        int timeLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("-->")) {
                timeLine = i;
                break;
            }
        }
        if (timeLine < 0) {
            return;
        }
        String[] parts = lines[timeLine].split("-->");
        if (parts.length < 2) {
            return;
        }
        long start = parseTimestamp(parts[0].trim());
        long end = parseTimestamp(parts[1].trim().split("\\s+")[0]);
        if (end <= start) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = timeLine + 1; i < lines.length; i++) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(lines[i].replaceAll("<[^>]+>", "").trim());
        }
        String cueText = builder.toString().trim();
        if (!cueText.isEmpty()) {
            out.add(new Cue(start, end, cueText));
        }
    }

    private long parseTimestamp(String raw) {
        String timestamp = raw.replace(',', '.');
        String[] parts = timestamp.split(":");
        try {
            double seconds;
            if (parts.length == 3) {
                seconds = Integer.parseInt(parts[0]) * 3600
                        + Integer.parseInt(parts[1]) * 60
                        + Double.parseDouble(parts[2]);
            } else if (parts.length == 2) {
                seconds = Integer.parseInt(parts[0]) * 60
                        + Double.parseDouble(parts[1]);
            } else {
                seconds = Double.parseDouble(parts[0]);
            }
            return (long) (seconds * 1000);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void resetPlaybackUi() {
        coverView.setImageDrawable(null);
        coverView.setTranslationX(0f);
        coverView.setAlpha(1f);
        imageSwitching = false;
        imageDragging = false;
        imageAnimGeneration++;
        if (coverTopThumb != null) {
            coverTopThumb.setVisibility(View.GONE);
        }
        lyricView.setText("");
        updateFloatingLyricText();
        currentImageIndex = 0;
        if (waveform != null) {
            waveform.setPosition(0, 0);
            waveform.setDuration(0);
        }
        miniSeekBar.setProgress(0);
        miniSeekBar.setMax(0);
        miniTimeView.setText("00:00 / 00:00");
        timeView.setText("00:00 / 00:00");
        playButton.setText(ICON_PLAY);
        miniPlayButton.setText(ICON_PLAY);
        updateMiniPlayer();
    }

    private void releasePlayer() {
        handler.removeCallbacks(progressTick);
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        updateMiniPlayer();
    }

    private void updateMiniPlayer() {
        if (miniPlayer == null) {
            return;
        }
        MediaItem item = currentPlaybackItem();
        boolean hasPlayback = player != null && item != null;
        boolean showMini = hasPlayback && !(currentTab == TAB_MY && pageMode == PAGE_PLAYER);
        miniPlayer.setVisibility(showMini ? View.VISIBLE : View.GONE);
        if (!hasPlayback) {
            return;
        }
        miniTitleView.setText(item.audio.name);
        Object coverTag = miniCoverThumb.getTag();
        String coverKey = playbackWork != null && playbackWork.cover != null
                ? playbackWork.cover.uri.toString() : null;
        if (!TextUtils.equals(coverKey, coverTag == null ? null : coverTag.toString())) {
            loadCoverInto(miniCoverThumb, playbackWork != null ? playbackWork.cover : null);
        }
        boolean playing = false;
        try {
            playing = player.isPlaying();
        } catch (IllegalStateException ignored) {
        }
        String playGlyph = playing ? ICON_PAUSE : ICON_PLAY;
        if (!playGlyph.contentEquals(miniPlayButton.getText())) {
            miniPlayButton.setText(playGlyph);
        }
        if (!playGlyph.contentEquals(playButton.getText())) {
            playButton.setText(playGlyph);
        }
    }

    private void showCurrentTrackImage() {
        MediaItem item = currentPlaybackItem();
        if (item == null) {
            coverView.setImageDrawable(null);
            coverTopThumb.setVisibility(View.GONE);
            if (imageIndicator != null) {
                imageIndicator.setVisibility(View.GONE);
            }
            return;
        }
        FileDoc shown = null;
        if (!item.images.isEmpty()) {
            currentImageIndex = Math.max(0, Math.min(currentImageIndex, item.images.size() - 1));
            shown = item.images.get(currentImageIndex);
        } else if (playbackWork != null && playbackWork.cover != null) {
            shown = playbackWork.cover;
        }
        if (shown != null) {
            showBigImage(shown);
        } else {
            coverView.setImageDrawable(null);
        }
        FileDoc cover = playbackWork != null ? playbackWork.cover : (item.images.isEmpty() ? null : item.images.get(0));
        boolean showThumb = currentImageIndex > 0 && cover != null;
        if (showThumb) {
            coverTopThumb.setVisibility(View.VISIBLE);
            if (!coverRelocationInFlight) {
                loadCoverInto(coverTopThumb, cover);
            }
        } else {
            coverTopThumb.setVisibility(View.GONE);
        }
        if (item.images.size() > 1) {
            imageIndicator.setVisibility(View.VISIBLE);
            imageIndexText.setText((currentImageIndex + 1) + " / " + item.images.size());
        } else {
            imageIndicator.setVisibility(View.GONE);
        }
    }

    /** 打开播放页或切歌时决定显示的图片索引：优先与音频同名的图片，否则保持当前浏览的图片。 */
    private int resolveImageIndexForCurrentAudio(FileDoc prevShown) {
        MediaItem item = currentPlaybackItem();
        if (item == null || item.images.isEmpty()) {
            return 0;
        }
        String audioStem = stemStatic(item.audio.name);
        if (!TextUtils.isEmpty(audioStem)) {
            for (int i = 0; i < item.images.size(); i++) {
                if (stemStatic(item.images.get(i).name).equalsIgnoreCase(audioStem)) {
                    return i;
                }
            }
        }
        if (prevShown != null) {
            for (int i = 0; i < item.images.size(); i++) {
                if (item.images.get(i).uri.equals(prevShown.uri)) {
                    return i;
                }
            }
        }
        return Math.max(0, Math.min(currentImageIndex, item.images.size() - 1));
    }

    private boolean coverRelocationInFlight;

    private void animateCoverRelocationIfNeeded(int prevIndex, int newIndex, float fromOffsetX) {
        if (prevIndex == newIndex) {
            return;
        }
        MediaItem item = currentPlaybackItem();
        if (item == null) {
            return;
        }
        FileDoc cover = playbackWork != null && playbackWork.cover != null
                ? playbackWork.cover
                : (!item.images.isEmpty() ? item.images.get(0) : null);
        if (cover == null) {
            return;
        }
        if (prevIndex == 0 && newIndex > 0) {
            relocateCoverOverlay(cover, true, fromOffsetX);
        } else if (prevIndex > 0 && newIndex == 0) {
            relocateCoverOverlay(cover, false, 0f);
        }
    }

    private int[] rectOnRoot(View view, int[] rootLoc) {
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        return new int[]{
                loc[0] - rootLoc[0] - Math.round(view.getTranslationX()),
                loc[1] - rootLoc[1] - Math.round(view.getTranslationY())
        };
    }

    private void relocateCoverOverlay(FileDoc cover, boolean toThumb, float fromOffsetX) {
        final ImageView overlay = new ImageView(this);
        overlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // 只取已缓存的缩略图；绝不在这里同步解码——动画运行于主线程，
        // SMB 读整张原图会直接卡住界面
        Bitmap cached = coverCache.get(cover.uri.toString());
        if (cached != null && !cached.isRecycled()) {
            overlay.setImageBitmap(cached);
        } else {
            overlay.setImageDrawable(workPlaceholder);
        }

        coverRelocationInFlight = true;
        if (toThumb) {
            coverTopThumb.setVisibility(View.VISIBLE);
            coverTopThumb.setAlpha(0f);
        } else {
            // 预先把封面大图放进 coverView（若已缓存），保证滑动切入时内容与飞行动画一致；
            // 未缓存则按需解码这一张封面，不牵连其它图片
            Bitmap big = bigImageCache.get(cover.uri.toString());
            if (big != null && !big.isRecycled()) {
                coverView.setImageBitmap(big);
            } else {
                coverView.setImageBitmap(cached);
                requestBigImageDecode(cover);
            }
        }

        final int[] rootLoc = new int[2];
        rootLayout.getLocationOnScreen(rootLoc);
        final Bitmap aspectSource = cached;
        if (toThumb) {
            // 立即把迁移 overlay 放在封面当前显示位置（起点不依赖缩略图布局），
            // 消除松手后缩略图 post 布局完成前封面闪没的一帧
            int[] startRect = fitRectOnRoot(coverView, rootLoc, aspectSource);
            startRect[0] += Math.round(fromOffsetX);
            if (startRect[2] > 0 && startRect[3] > 0) {
                overlay.setTranslationX(startRect[0]);
                overlay.setTranslationY(startRect[1]);
                overlay.setPivotX(0f);
                overlay.setPivotY(0f);
                overlay.setScaleX(1f);
                overlay.setScaleY(1f);
                rootLayout.addView(overlay, new FrameLayout.LayoutParams(
                        startRect[2], startRect[3], Gravity.TOP | Gravity.START));
                overlay.bringToFront();
            }
        }
        coverTopThumb.post(() -> {
            int[] fromRect;
            int[] toRect;
            int srcW;
            int srcH;
            int dstW;
            int dstH;
            if (toThumb) {
                fromRect = fitRectOnRoot(coverView, rootLoc, aspectSource);
                // 支持从封面被拖到的位置起飞，避免迁移与出场动画重复
                fromRect[0] += Math.round(fromOffsetX);
                toRect = rectOnRoot(coverTopThumb, rootLoc);
                srcW = fromRect[2];
                srcH = fromRect[3];
                dstW = coverTopThumb.getWidth();
                dstH = coverTopThumb.getHeight();
            } else {
                fromRect = rectOnRoot(coverTopThumb, rootLoc);
                toRect = fitRectOnRoot(coverView, rootLoc, aspectSource);
                srcW = coverTopThumb.getWidth();
                srcH = coverTopThumb.getHeight();
                dstW = toRect[2];
                dstH = toRect[3];
                coverTopThumb.setVisibility(View.GONE);
            }
            if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
                finishCoverRelocation(toThumb);
                rootLayout.removeView(overlay);
                return;
            }
            if (overlay.getParent() == null) {
                // toThumb 分支已在 post 前立即添加；其余情况（回封面）此时添加
                FrameLayout.LayoutParams overlayParams =
                        new FrameLayout.LayoutParams(srcW, srcH, Gravity.TOP | Gravity.START);
                overlay.setTranslationX(fromRect[0]);
                overlay.setTranslationY(fromRect[1]);
                overlay.setPivotX(0f);
                overlay.setPivotY(0f);
                overlay.setScaleX(1f);
                overlay.setScaleY(1f);
                rootLayout.addView(overlay, overlayParams);
                overlay.bringToFront();
            }
            overlay.animate().translationX(toRect[0]).translationY(toRect[1])
                    .scaleX(dstW / (float) srcW).scaleY(dstH / (float) srcH)
                    .setDuration(320)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(() -> {
                        finishCoverRelocation(toThumb);
                        rootLayout.removeView(overlay);
                    }).start();
        });
    }

    /** 计算 bitmap 在 view 内按 FIT_CENTER 展示时的实际显示矩形（root 坐标 x,y,宽,高）。 */
    private int[] fitRectOnRoot(View view, int[] rootLoc, Bitmap bitmap) {
        int[] pos = rectOnRoot(view, rootLoc);
        int w = view.getWidth();
        int h = view.getHeight();
        int innerW = w;
        int innerH = h;
        if (bitmap != null && w > 0 && h > 0) {
            float aspect = bitmap.getWidth() / (float) bitmap.getHeight();
            float viewAspect = w / (float) h;
            if (viewAspect > aspect) {
                innerH = h;
                innerW = Math.round(h * aspect);
            } else {
                innerW = w;
                innerH = Math.round(w / aspect);
            }
        }
        return new int[]{
                pos[0] + (w - innerW) / 2,
                pos[1] + (h - innerH) / 2,
                innerW,
                innerH
        };
    }

    private void finishCoverRelocation(boolean toThumb) {
        coverRelocationInFlight = false;
        coverTopThumb.setAlpha(1f);
        if (toThumb) {
            MediaItem item = currentPlaybackItem();
            FileDoc cover = null;
            if (item != null) {
                cover = playbackWork != null && playbackWork.cover != null
                        ? playbackWork.cover
                        : (!item.images.isEmpty() ? item.images.get(0) : null);
            }
            loadCoverInto(coverTopThumb, cover);
        }
    }

    private void handleImageTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (imageVelocityTracker != null) {
                imageVelocityTracker.clear();
            } else {
                imageVelocityTracker = VelocityTracker.obtain();
            }
            imageVelocityTracker.addMovement(event);
            imageDownX = event.getRawX();
            imageDownY = event.getRawY();
            imageHorizontalIntent = false;
            if (imageSwitching) {
                coverView.animate().cancel();
                imageSwitching = false;
                imageAnimGeneration++;
            }
            hideImagePeer(false);
            imageDragBase = coverView.getTranslationX();
            coverView.setAlpha(1f);
            return;
        }
        if (imageVelocityTracker != null) {
            imageVelocityTracker.addMovement(event);
        }
        MediaItem item = currentPlaybackItem();
        boolean canSwipe = pageMode == PAGE_PLAYER && item != null && item.images.size() > 1;
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getRawX() - imageDownX;
            float dy = event.getRawY() - imageDownY;
            if (!imageHorizontalIntent && canSwipe
                    && Math.abs(dx) > dp(8) && Math.abs(dx) > Math.abs(dy) * 0.85f) {
                imageHorizontalIntent = true;
            }
            if (imageHorizontalIntent) {
                imageDragging = true;
                coverView.setTranslationX(imageDragBase + dx);
                updateImagePeer(item, dx);
            }
            return;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            float velocityX = 0f;
            if (imageVelocityTracker != null) {
                imageVelocityTracker.computeCurrentVelocity(1000);
                velocityX = imageVelocityTracker.getXVelocity();
                imageVelocityTracker.recycle();
                imageVelocityTracker = null;
            }
            float totalDx = event.getRawX() - imageDownX;
            float totalDy = event.getRawY() - imageDownY;
            boolean quickFlick = canSwipe && action == MotionEvent.ACTION_UP
                    && Math.abs(totalDx) > dp(18) && Math.abs(totalDx) > Math.abs(totalDy) * 0.9f;
            if (imageDragging || quickFlick) {
                imageDragging = false;
                imageHorizontalIntent = false;
                settlePlayerImage(imageDragBase + totalDx, velocityX);
            }
        }
    }

    /** 拖动时更新相邻图片预览：跟随 coverView 显示方向对应的目标图并预解码。 */
    private void updateImagePeer(MediaItem item, float dx) {
        if (item == null || item.images.size() <= 1) {
            hideImagePeer(false);
            return;
        }
        int side = dx < 0 ? 1 : -1;
        int peerIdx = currentImageIndex + side;
        if (peerIdx < 0 || peerIdx >= item.images.size()) {
            hideImagePeer(false);
            return;
        }
        if (imagePeerView.getVisibility() != View.VISIBLE
                || imagePeerView.getWidth() != coverView.getWidth()
                || imagePeerView.getHeight() != coverView.getHeight()) {
            int[] rootLoc = new int[2];
            rootLayout.getLocationOnScreen(rootLoc);
            int[] pos = rectOnRoot(coverView, rootLoc);
            imagePeerBaseX = pos[0];
            imagePeerBaseY = pos[1];
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) imagePeerView.getLayoutParams();
            p.width = coverView.getWidth();
            p.height = coverView.getHeight();
            imagePeerView.setLayoutParams(p);
            imagePeerView.setTranslationY(imagePeerBaseY);
        }
        if (imagePeerView.getVisibility() != View.VISIBLE) {
            imagePeerView.setTranslationX(imagePeerBaseX + coverView.getTranslationX() + side * coverView.getWidth());
            imagePeerView.setVisibility(View.VISIBLE);
            imagePeerView.bringToFront();
        }
        if (imagePeerSide != side) {
            imagePeerSide = side;
            imagePeerIndex = -1;
        }
        if (imagePeerIndex != peerIdx) {
            imagePeerIndex = peerIdx;
            loadPeerImage(item.images.get(peerIdx));
        }
        imagePeerView.setTranslationX(imagePeerBaseX + coverView.getTranslationX() + side * coverView.getWidth());
    }

    /** 拖动时才加载目标图：命中缓存立即显示，否则发起唯一的按需解码任务。 */
    private void loadPeerImage(FileDoc doc) {
        final String key = doc.uri.toString();
        pendingPeerImageKey = key;
        Bitmap cached = bigImageCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            imagePeerView.setImageBitmap(cached);
            return;
        }
        imagePeerView.setImageBitmap(coverCache.get(key));
        requestBigImageDecode(doc);
    }

    /** 隐藏相邻图片预览。slideOut=true 时向当前所在侧滑出，false 时立即消失。 */
    private void hideImagePeer(boolean slideOut) {
        int side = imagePeerSide;
        imagePeerSide = 0;
        pendingPeerImageKey = null;
        if (imagePeerView == null || imagePeerView.getVisibility() != View.VISIBLE) {
            imagePeerIndex = -1;
            return;
        }
        imagePeerView.animate().cancel();
        imagePeerIndex = -1;
        if (slideOut) {
            ImageView peer = imagePeerView;
            float out = imagePeerBaseX + (side == -1 ? -1 : 1) * coverView.getWidth() * 1.2f;
            peer.animate().translationX(out).setDuration(150)
                    .withEndAction(() -> peer.setVisibility(View.GONE))
                    .start();
        } else {
            imagePeerView.setVisibility(View.GONE);
        }
    }

    private void settlePlayerImage(float translation, float velocityX) {
        MediaItem item = currentPlaybackItem();
        float width = Math.max(dp(80), coverView.getWidth());
        int dir = 0;
        if (item != null && item.images.size() > 1) {
            float absTranslation = Math.abs(translation);
            float absVelocity = Math.abs(velocityX);
            if (absVelocity > dp(120) && absTranslation > dp(10)) {
                // 轻微快速甩动即可翻页
                dir = velocityX < 0 ? 1 : -1;
            } else if (absTranslation > width * 0.09f) {
                // 慢速拖动只需约 90px（不到图宽的 1/10）即可翻页
                dir = translation < 0 ? 1 : -1;
            } else if (absTranslation > dp(16) && absVelocity > dp(50)) {
                dir = velocityX < 0 ? 1 : -1;
            }
        }
        if (dir == 0) {
            springImageBack();
            return;
        }
        int target = Math.max(0, Math.min(item.images.size() - 1, currentImageIndex + dir));
        if (target == currentImageIndex) {
            springImageBack();
            return;
        }
        beginImageSettle(dir, target);
    }

    private void beginImageSettle(int dir, int target) {
        MediaItem item = currentPlaybackItem();
        if (item == null) {
            return;
        }
        if (target >= 0 && target < item.images.size()) {
            // 用户已经决定切换到这张图，此时才允许加载它（按钮切换没有拖动预览层）
            requestBigImageDecode(item.images.get(target));
        }
        imageAnimGeneration++;
        final int gen = imageAnimGeneration;
        imageSwitching = true;
        float width = Math.max(dp(80), coverView.getWidth());
        float startX = coverView.getTranslationX();
        float outX = -dir * width;
        long outDuration = Math.max(90, (long) (220 * Math.abs(startX - outX) / width));
        FileDoc cover = playbackWork != null && playbackWork.cover != null
                ? playbackWork.cover
                : (!item.images.isEmpty() ? item.images.get(0) : null);
        int prev = currentImageIndex;
        if (prev == 0 && target > 0 && cover != null) {
            // 离开封面：封面不做滑出，直接从当前拖动位置迁移到顶部缩略图；
            // 新图已在拖动时由预览层显示并预解码，松手后 coverView 接管预览
            // 的位置（无缝），继续滑入到位
            float dragX = coverView.getTranslationX();
            currentImageIndex = target;
            animateCoverRelocationIfNeeded(prev, target, dragX);
            showCurrentTrackImage();
            hideImagePeer(false);
            float enterX = dragX + dir * width;
            coverView.setTranslationX(enterX);
            long enterDuration = Math.max(90, (long) (200 * Math.abs(enterX) / width));
            coverView.animate().translationX(0f).setDuration(enterDuration)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(() -> {
                        if (gen == imageAnimGeneration) {
                            imageSwitching = false;
                        }
                    }).start();
            return;
        }
        if (prev > 0 && target == 0 && cover != null) {
            // 回到封面：旧图先滑出屏幕，结束后封面从缩略图飞向大图位置，
            // 落地后才显示 coverView，避免进场的封面与飞行的封面重叠
            hideImagePeer(false);
            currentImageIndex = target;
            coverView.animate().translationX(outX).setDuration(outDuration).withEndAction(() -> {
                if (gen != imageAnimGeneration) {
                    return;
                }
                animateCoverRelocationIfNeeded(prev, target, 0f);
                coverView.setTranslationX(0f);
                coverView.setAlpha(0f);
                showCurrentTrackImage();
                handler.postDelayed(() -> {
                    if (gen == imageAnimGeneration) {
                        coverView.setAlpha(1f);
                        imageSwitching = false;
                    }
                }, 320);
            }).start();
            return;
        }
        // 普通图片间切换：新图已由预览层显示在相邻位置，旧图滑出后
        // coverView 直接接管预览位置继续滑入，避免从全宽外进场造成割裂
        coverView.animate().translationX(outX).setDuration(outDuration).withEndAction(() -> {
            if (gen != imageAnimGeneration) {
                return;
            }
            currentImageIndex = target;
            showCurrentTrackImage();
            hideImagePeer(false);
            float enterX = startX + dir * width;
            coverView.setTranslationX(enterX);
            long enterDuration = Math.max(90, (long) (200 * Math.abs(enterX) / width));
            coverView.animate().translationX(0f).setDuration(enterDuration)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(() -> {
                        if (gen == imageAnimGeneration) {
                            imageSwitching = false;
                        }
                    }).start();
        }).start();
    }

    private void springImageBack() {
        imageAnimGeneration++;
        final int gen = imageAnimGeneration;
        imageSwitching = true;
        // 预览层跟随封面一起弹回原位
        if (imagePeerView != null && imagePeerView.getVisibility() == View.VISIBLE) {
            ImageView peer = imagePeerView;
            int side = imagePeerSide == -1 ? -1 : 1;
            peer.animate().translationX(imagePeerBaseX + side * coverView.getWidth())
                    .setDuration(180)
                    .withEndAction(() -> peer.setVisibility(View.GONE))
                    .start();
            imagePeerSide = 0;
            imagePeerIndex = -1;
        }
        coverView.animate().translationX(0f).setDuration(180).withEndAction(() -> {
            if (gen == imageAnimGeneration) {
                imageSwitching = false;
            }
        }).start();
    }

    private void switchPlayerImage(int direction) {
        MediaItem item = currentPlaybackItem();
        if (pageMode != PAGE_PLAYER || item == null || item.images.size() <= 1 || imageDragging) {
            return;
        }
        int target = currentImageIndex + direction;
        if (target < 0 || target >= item.images.size()) {
            return;
        }
        beginImageSettle(direction, target);
    }

    private Button miniIndicatorButton(String text, int direction) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(colorTextSecondary);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(v -> switchPlayerImage(direction));
        return button;
    }

    private void showBigImage(FileDoc doc) {
        final String key = doc.uri.toString();
        coverView.setTag(key);
        pendingBigImageKey = key;
        Bitmap cached = bigImageCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            coverView.setImageBitmap(cached);
            return;
        }
        // 优先用「封面缩略图」即时占位：它在作品列表里已加载并落了磁盘缓存，
        // 这里纯本地读取、零网络。随后只解码当前需要的这一张，不预取其它图片。
        Bitmap placeholder = coverCache.get(key);
        if (placeholder == null && playbackWork != null && playbackWork.cover != null) {
            placeholder = coverCache.get(playbackWork.cover.uri.toString());
        }
        coverView.setImageBitmap(placeholder);
        requestBigImageDecode(doc);
    }

    /**
     * 按需解码单张大图：播放页打开时只解码当前显示的封面，滑动/按钮切换到
     * 其它图片时才解码那一张。同一 key 在途只排一个任务，避免拖动预览与
     * 正式换图重复拉取 SMB 原图。
     */
    private void requestBigImageDecode(FileDoc doc) {
        if (doc == null) {
            return;
        }
        final String key = doc.uri.toString();
        if (bigImageCache.get(key) != null || !bigImageLoadsInFlight.add(key)) {
            return;
        }
        bigImageExecutor.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = decodeSampled(doc.uri, 1200);
            } catch (Throwable ex) {
                // 任何异常都必须释放 in-flight 标记，否则这张图本次运行内再也不会被加载
                Log.w(TAG, "big image decode failed: " + key + " -> " + ex.getMessage());
            }
            final Bitmap result = bitmap;
            handler.post(() -> {
                bigImageLoadsInFlight.remove(key);
                if (result == null || result.isRecycled()) {
                    return;
                }
                bigImageCache.put(key, result);
                if (key.equals(pendingBigImageKey) && key.equals(coverView.getTag())) {
                    coverView.setImageBitmap(result);
                }
                if (key.equals(pendingPeerImageKey)
                        && imagePeerView != null
                        && imagePeerView.getVisibility() == View.VISIBLE) {
                    imagePeerView.setImageBitmap(result);
                }
            });
        });
    }

    private final Map<String, float[]> waveformCache = new HashMap<>();
    private String waveformKey;

    private void prepareWaveform(Uri uri, int durationMs) {
        String key = uri.toString();
        waveformKey = key;
        waveform.setWaveform(null);
        float[] cached = waveformCache.get(key);
        if (cached != null) {
            waveform.setWaveform(cached);
            return;
        }
        auxExecutor.execute(() -> {
            float[] data = computeWaveform(uri, durationMs);
            if (data == null) {
                return;
            }
            waveformCache.put(key, data);
            handler.post(() -> {
                if (key.equals(waveformKey)) {
                    waveform.setWaveform(data);
                }
            });
        });
    }

    private float[] computeWaveform(Uri uri, int durationMs) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(this, toPlayableUri(uri), null);
            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    format = f;
                    break;
                }
            }
            if (trackIndex < 0 || format == null) {
                return null;
            }
            extractor.selectTrack(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            long durationUs = durationMs > 0 ? durationMs * 1000L
                    : (format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0L);
            if (durationUs <= 0) {
                return null;
            }
            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int pcmEncoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                    ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
            final int buckets = 160;
            float[] maxAmp = new float[buckets];
            long totalFrames = durationUs * sampleRate / 1_000_000L;
            int stride = (int) Math.max(1, totalFrames / 300_000);

            if ("audio/raw".equals(mime)) {
                ByteBuffer raw = ByteBuffer.allocateDirect(128 * 1024);
                while (true) {
                    raw.clear();
                    int size = extractor.readSampleData(raw, 0);
                    if (size < 0) {
                        break;
                    }
                    long pts = extractor.getSampleTime();
                    ingestPcm(raw, size, pts, durationUs, maxAmp, sampleRate, channels, pcmEncoding, stride);
                    extractor.advance();
                }
            } else {
                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(format, null, null, 0);
                codec.start();
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inputDone = false;
                boolean outputDone = false;
                while (!outputDone) {
                    if (!inputDone) {
                        int inIdx = codec.dequeueInputBuffer(10_000);
                        if (inIdx >= 0) {
                            ByteBuffer in = codec.getInputBuffer(inIdx);
                            int size = in == null ? -1 : extractor.readSampleData(in, 0);
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                long pts = extractor.getSampleTime();
                                codec.queueInputBuffer(inIdx, 0, size, pts, 0);
                                extractor.advance();
                            }
                        }
                    }
                    int outIdx = codec.dequeueOutputBuffer(info, 10_000);
                    if (outIdx >= 0) {
                        ByteBuffer out = codec.getOutputBuffer(outIdx);
                        if (out != null && info.size > 0) {
                            out.position(info.offset);
                            out.limit(info.offset + info.size);
                            ingestPcm(out, info.size, info.presentationTimeUs, durationUs, maxAmp,
                                    sampleRate, channels, pcmEncoding, stride);
                        }
                        codec.releaseOutputBuffer(outIdx, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true;
                        }
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat outFormat = codec.getOutputFormat();
                        sampleRate = outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                                ? outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : sampleRate;
                        channels = outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                                ? outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : channels;
                        pcmEncoding = outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                ? outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) : pcmEncoding;
                    }
                }
                codec.stop();
                codec.release();
                codec = null;
            }
            float peak = 0f;
            for (float v : maxAmp) {
                peak = Math.max(peak, v);
            }
            if (peak <= 0.0001f) {
                return null;
            }
            for (int i = 0; i < buckets; i++) {
                maxAmp[i] = maxAmp[i] / peak;
            }
            return maxAmp;
        } catch (Exception ex) {
            return null;
        } finally {
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
            if (codec != null) {
                try {
                    codec.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void ingestPcm(ByteBuffer buffer, int size, long ptsUs, long durationUs, float[] maxAmp,
                           int sampleRate, int channels, int pcmEncoding, int stride) {
        int bytesPerSample = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT ? 4
                : (pcmEncoding == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2);
        int frameBytes = Math.max(1, bytesPerSample * Math.max(1, channels));
        int frames = size / frameBytes;
        if (frames <= 0) {
            return;
        }
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int startPos = buffer.position();
        for (int f = 0; f < frames; f += stride) {
            buffer.position(startPos + f * frameBytes);
            float peak = 0f;
            for (int c = 0; c < channels && buffer.remaining() >= bytesPerSample; c++) {
                float v;
                if (bytesPerSample == 1) {
                    v = Math.abs(buffer.get() / 128f);
                } else if (bytesPerSample == 4) {
                    v = Math.abs(buffer.getFloat());
                } else {
                    v = Math.abs(buffer.getShort() / 32768f);
                }
                peak = Math.max(peak, v);
            }
            long sampleUs = ptsUs + (long) f * 1_000_000L / Math.max(1, sampleRate);
            int bucket = (int) Math.min(maxAmp.length - 1, Math.max(0, sampleUs * maxAmp.length / durationUs));
            maxAmp[bucket] = Math.max(maxAmp[bucket], peak);
        }
    }

    private MediaItem currentPlaybackItem() {        if (currentIndex < 0 || currentIndex >= playbackQueue.size()) {
            return null;
        }
        return playbackQueue.get(currentIndex);
    }

    private void openImageViewer() {
        List<FileDoc> images = null;
        MediaItem item = currentPlaybackItem();
        if (pageMode == PAGE_PLAYER && item != null && !item.images.isEmpty()) {
            images = item.images;
        } else if (playbackWork != null && playbackWork.cover != null) {
            images = Collections.singletonList(playbackWork.cover);
        } else if (activeWork != null && activeWork.cover != null) {
            images = Collections.singletonList(activeWork.cover);
        }
        if (images == null || images.isEmpty()) {
            return;
        }
        viewerImages = images;
        viewerPosition = Math.max(0, Math.min(currentImageIndex, images.size() - 1));
        hideImagePeer(false);
        buildViewerOverlay();
        viewerOverlay.setVisibility(View.VISIBLE);
        viewerShowImage(viewerPosition);
    }

    private void buildViewerOverlay() {
        if (viewerOverlay != null) {
            return;
        }
        viewerOverlay = new FrameLayout(this);
        viewerOverlay.setBackgroundColor(Color.WHITE);
        viewerImage = new ZoomImageView(this);
        viewerOverlay.addView(viewerImage, new FrameLayout.LayoutParams(-1, -1));

        TextView closeButton = label("✕", 22, Color.rgb(36, 40, 48));
        closeButton.setGravity(Gravity.CENTER);
        closeButton.setPadding(dp(16), dp(8), dp(16), dp(8));
        closeButton.setOnClickListener(v -> closeImageViewer());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        closeParams.topMargin = dp(16);
        closeParams.rightMargin = dp(16);
        viewerOverlay.addView(closeButton, closeParams);

        LinearLayout viewerBar = new LinearLayout(this);
        viewerBar.setOrientation(LinearLayout.HORIZONTAL);
        viewerBar.setGravity(Gravity.CENTER);

        Button prevButton = new Button(this);
        prevButton.setText("‹");
        prevButton.setTextColor(Color.rgb(36, 40, 48));
        prevButton.setTextSize(24);
        prevButton.setAllCaps(false);
        prevButton.setBackgroundColor(Color.TRANSPARENT);
        prevButton.setOnClickListener(v -> viewerSwitch(-1));
        viewerBar.addView(prevButton, new LinearLayout.LayoutParams(dp(64), -2));

        viewerIndexLabel = label("", 13, Color.rgb(96, 104, 116));
        viewerIndexLabel.setGravity(Gravity.CENTER);
        viewerIndexLabel.setSingleLine(true);
        viewerIndexLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        viewerBar.addView(viewerIndexLabel, new LinearLayout.LayoutParams(0, -2, 1f));

        Button nextButton = new Button(this);
        nextButton.setText("›");
        nextButton.setTextColor(Color.rgb(36, 40, 48));
        nextButton.setTextSize(24);
        nextButton.setAllCaps(false);
        nextButton.setBackgroundColor(Color.TRANSPARENT);
        nextButton.setOnClickListener(v -> viewerSwitch(1));
        viewerBar.addView(nextButton, new LinearLayout.LayoutParams(dp(64), -2));

        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        barParams.bottomMargin = dp(24);
        viewerOverlay.addView(viewerBar, barParams);

        rootLayout.addView(viewerOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void viewerSwitch(int direction) {
        if (viewerImages == null || viewerImages.isEmpty()) {
            return;
        }
        int target = viewerPosition + direction;
        if (target < 0 || target >= viewerImages.size()) {
            Toast.makeText(this, target < 0 ? "已经是第一张" : "已经是最后一张", Toast.LENGTH_SHORT).show();
            return;
        }
        viewerShowImage(target);
    }

    private void viewerShowImage(int position) {
        if (viewerImages == null || viewerImages.isEmpty()) {
            return;
        }
        position = Math.max(0, Math.min(position, viewerImages.size() - 1));
        viewerPosition = position;
        final FileDoc doc = viewerImages.get(position);
        final Uri uri = doc.uri;
        viewerImage.setBitmap(null);
        viewerIndexLabel.setText((position + 1) + " / " + viewerImages.size() + " · " + doc.name + " · 双指缩放");
        final ZoomImageView target = viewerImage;
        bigImageExecutor.execute(() -> {
            Bitmap bitmap = decodeSampled(uri, 2048);
            if (bitmap == null) {
                return;
            }
            handler.post(() -> {
                if (viewerOverlay != null && viewerOverlay.getVisibility() == View.VISIBLE
                        && viewerImages.get(viewerPosition).uri.equals(uri)) {
                    target.setBitmap(bitmap);
                }
            });
        });
    }

    private void closeImageViewer() {
        if (viewerOverlay == null || viewerOverlay.getVisibility() != View.VISIBLE) {
            return;
        }
        viewerOverlay.setVisibility(View.GONE);
        viewerImage.setBitmap(null);
        currentImageIndex = viewerPosition;
        showCurrentTrackImage();
    }

    private class ZoomImageView extends View {
        private Bitmap bitmap;
        private final Matrix drawMatrix = new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestureDetector;
        private float minScale = 1f;
        private float maxScale = 5f;
        private float swipeAccumX;

        ZoomImageView(Context context) {
            super(context);
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    float[] values = new float[9];
                    drawMatrix.getValues(values);
                    float current = values[Matrix.MSCALE_X];
                    if (current <= 0f) {
                        return true;
                    }
                    float target = Math.max(minScale, Math.min(maxScale, current * detector.getScaleFactor()));
                    drawMatrix.postScale(target / current, target / current, detector.getFocusX(), detector.getFocusY());
                    clampMatrix();
                    invalidate();
                    return true;
                }
            });
            gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapConfirmed(MotionEvent event) {
                    closeImageViewer();
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent event) {
                    float current = currentScale();
                    if (current > minScale * 1.05f) {
                        resetMatrix();
                    } else {
                        float target = Math.min(minScale * 2.5f, maxScale);
                        drawMatrix.postScale(target / current, target / current, event.getX(), event.getY());
                        clampMatrix();
                        invalidate();
                    }
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent event1, MotionEvent event2, float distanceX, float distanceY) {
                    if (currentScale() > minScale + 0.02f) {
                        drawMatrix.postTranslate(-distanceX, -distanceY);
                        clampMatrix();
                        invalidate();
                    } else {
                        swipeAccumX += -distanceX;
                        if (Math.abs(swipeAccumX) > Math.max(dp(72), getWidth() / 4f)) {
                            int direction = swipeAccumX < 0 ? 1 : -1;
                            swipeAccumX = 0;
                            viewerSwitch(direction);
                        }
                    }
                    return true;
                }
            });
        }

        void setBitmap(Bitmap value) {
            bitmap = value;
            swipeAccumX = 0;
            resetMatrix();
            invalidate();
        }

        float currentScale() {
            float[] values = new float[9];
            drawMatrix.getValues(values);
            return values[Matrix.MSCALE_X];
        }

        void resetMatrix() {
            if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
                return;
            }
            float scale = Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
            minScale = scale;
            maxScale = scale * 5f;
            drawMatrix.reset();
            drawMatrix.postScale(scale, scale);
            drawMatrix.postTranslate((getWidth() - bitmap.getWidth() * scale) / 2f,
                    (getHeight() - bitmap.getHeight() * scale) / 2f);
            invalidate();
        }

        private void clampMatrix() {
            if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
                return;
            }
            float[] values = new float[9];
            drawMatrix.getValues(values);
            float scale = values[Matrix.MSCALE_X];
            float tx = values[Matrix.MTRANS_X];
            float ty = values[Matrix.MTRANS_Y];
            float width = bitmap.getWidth() * scale;
            float height = bitmap.getHeight() * scale;
            float minX;
            float maxX;
            if (width <= getWidth()) {
                minX = maxX = (getWidth() - width) / 2f;
            } else {
                maxX = 0f;
                minX = getWidth() - width;
            }
            float minY;
            float maxY;
            if (height <= getHeight()) {
                minY = maxY = (getHeight() - height) / 2f;
            } else {
                maxY = 0f;
                minY = getHeight() - height;
            }
            values[Matrix.MTRANS_X] = Math.max(minX, Math.min(maxX, tx));
            values[Matrix.MTRANS_Y] = Math.max(minY, Math.min(maxY, ty));
            drawMatrix.setValues(values);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            resetMatrix();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, drawMatrix, null);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            boolean handled = scaleDetector.onTouchEvent(event);
            handled = gestureDetector.onTouchEvent(event) || handled;
            return handled || super.onTouchEvent(event);
        }
    }

    private class WaveformView extends View {
        private static final int BAR_COUNT = 96;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private int durationMs;
        private int positionMs;
        private float[] amplitudes;

        WaveformView(Context context) {
            super(context);
        }

        void setDuration(int ms) {
            durationMs = Math.max(0, ms);
            invalidate();
        }

        void setPosition(int ms, int duration) {
            positionMs = Math.max(0, ms);
            if (duration > 0) {
                durationMs = duration;
            }
            invalidate();
        }

        void setWaveform(float[] data) {
            amplitudes = data;
            invalidate();
        }

        private float amplitudeAt(int index, int count) {
            if (amplitudes == null || amplitudes.length == 0) {
                double v = Math.abs(Math.sin(index * 12.9898 + 4.1414)) * 0.72
                        + Math.abs(Math.sin(index * 3.71 + 1.37)) * 0.28;
                return 0.16f + (float) v * 0.84f;
            }
            float pos = index * amplitudes.length / (float) count;
            int i0 = Math.min(amplitudes.length - 1, (int) pos);
            int i1 = Math.min(amplitudes.length - 1, i0 + 1);
            float t = pos - i0;
            return 0.1f + (amplitudes[i0] * (1f - t) + amplitudes[i1] * t) * 0.9f;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            float fraction = durationMs > 0 ? Math.min(1f, positionMs / (float) durationMs) : 0f;
            if (amplitudes == null) {
                float barHeight = dp(4);
                float top = (height - barHeight) / 2f;
                paint.setColor(colorWaveUnplayed);
                canvas.drawRoundRect(0, top, width, top + barHeight, barHeight / 2f, barHeight / 2f, paint);
                if (fraction > 0f) {
                    paint.setColor(colorAccent);
                    canvas.drawRoundRect(0, top, width * fraction, top + barHeight, barHeight / 2f, barHeight / 2f, paint);
                }
                return;
            }
            float slot = width / BAR_COUNT;
            float barWidth = Math.max(dp(2), slot * 0.42f);
            for (int i = 0; i < BAR_COUNT; i++) {
                float amp = amplitudeAt(i, BAR_COUNT);
                float barHeight = Math.max(dp(3), height * 0.94f * amp);
                float left = i * slot + (slot - barWidth) / 2f;
                float top = (height - barHeight) / 2f;
                paint.setColor(i / (float) BAR_COUNT < fraction ? colorAccent : colorWaveUnplayed);
                canvas.drawRoundRect(left, top, left + barWidth, top + barHeight, barWidth / 2f, barWidth / 2f, paint);
            }
        }
    }

    private boolean isSupportedAsset(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return isAudio(name) || isImage(lower) || lower.endsWith(".vtt") || lower.endsWith(".lrc");
    }

    private boolean isAudio(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".wav");
    }

    private boolean isImage(String lowerName) {
        return lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".png")
                || lowerName.endsWith(".webp")
                || lowerName.endsWith(".bmp");
    }

    private boolean looksLikeWorkName(String name) {
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.matches(".*(RJ|VJ|BJ)\\d{5,}.*");
    }

    private String firstPathSegment(String relativePath) {
        int slash = relativePath.indexOf('/');
        return slash >= 0 ? relativePath.substring(0, slash) : "";
    }

    private String displayNameFromTreeId(String treeDocumentId) {
        int colon = treeDocumentId.indexOf(':');
        String path = colon >= 0 ? treeDocumentId.substring(colon + 1) : treeDocumentId;
        if (TextUtils.isEmpty(path)) {
            return "已选文件夹";
        }
        String[] parts = path.split("/");
        return parts.length == 0 ? path : parts[parts.length - 1];
    }

    private String stem(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT);
    }

    private String formatTime(int millis) {
        int total = Math.max(0, millis / 1000);
        int minutes = total / 60;
        int seconds = total % 60;
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void loadCoverInto(ImageView imageView, FileDoc cover) {
        if (cover == null) {
            imageView.setTag(null);
            imageView.setImageDrawable(workPlaceholder);
            return;
        }
        final String key = cover.uri.toString();
        imageView.setTag(key);
        Bitmap cached = coverCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            imageView.setImageBitmap(cached);
            return;
        }
        imageView.setImageDrawable(workPlaceholder);
        List<ImageView> waiters = coverWaiters.get(key);
        if (waiters == null) {
            waiters = new ArrayList<>();
            coverWaiters.put(key, waiters);
        }
        if (!waiters.contains(imageView)) {
            waiters.add(imageView);
        }
        // 同一个 uri 只排一个任务；ListView 快速滚动时不再重复下载整张原图
        if (!coverLoadsInFlight.add(key)) {
            return;
        }
        final File disk = coverThumbFile(key);
        final int targetSize = dp(72);
        final List<ImageView> snapshot = new ArrayList<>(waiters);
        imageExecutor.execute(() -> {
            // 快速滚动时该行可能已被复用给别的作品；没有任何等待者就跳过网络读取
            boolean stillWanted = false;
            for (ImageView target : snapshot) {
                if (key.equals(target.getTag())) {
                    stillWanted = true;
                    break;
                }
            }
            if (!stillWanted) {
                handler.post(() -> {
                    coverLoadsInFlight.remove(key);
                    coverWaiters.remove(key);
                });
                return;
            }
            Bitmap bitmap = null;
            try {
                if (disk.isFile() && disk.length() > 0) {
                    bitmap = BitmapFactory.decodeFile(disk.getAbsolutePath());
                }
                if (bitmap == null) {
                    bitmap = decodeThumbnail(cover.uri, targetSize);
                    if (bitmap != null) {
                        saveCoverThumb(disk, bitmap);
                    }
                }
            } catch (Exception ex) {
                Log.w(TAG, "cover load failed: " + ex.getMessage());
            }
            final Bitmap result = bitmap;
            handler.post(() -> {
                coverLoadsInFlight.remove(key);
                List<ImageView> pending = coverWaiters.remove(key);
                if (result == null || result.isRecycled() || pending == null) {
                    return;
                }
                coverCache.put(key, result);
                for (ImageView target : pending) {
                    if (key.equals(target.getTag())) {
                        target.setImageBitmap(result);
                    }
                }
            });
        });
    }

    /** 缩略图磁盘缓存：SMB 原图只需读取一次，之后冷启动/滚动都读本地小文件。 */
    private File coverThumbFile(String key) {
        File dir = new File(getCacheDir(), "cover_thumbs");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create cover cache dir");
        }
        String name = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        return new File(dir, name + ".jpg");
    }

    private void saveCoverThumb(File target, Bitmap bitmap) {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)) {
                tmp.delete();
                return;
            }
        } catch (Exception ex) {
            tmp.delete();
            return;
        }
        if (target.exists() && !target.delete()) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
        }
    }

    /**
     * 一次把图片读进内存再从字节数组解码。
     * 旧实现为「查尺寸 + 解码」各开一次流；对 SMB/FTP 意味着每张图重新协商两次会话，
     * 在 NAS 上会明显卡顿甚至超时。这里只建立一个连接。
     */
    private byte[] readImageBytes(Uri uri) {
        try (InputStream input = openStreamFor(uri)) {
            if (input == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > 32 * 1024 * 1024) {
                    Log.w(TAG, "image too large, skipped: " + uri);
                    return null;
                }
            }
            return out.toByteArray();
        } catch (Exception ex) {
            Log.w(TAG, "read image failed: " + uri + " -> " + ex.getMessage());
            return null;
        }
    }

    private Bitmap decodeThumbnail(Uri uri, int targetSize) {
        byte[] bytes = readImageBytes(uri);
        if (bytes == null) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            int sampleSize = 1;
            while ((bounds.outWidth / sampleSize) > targetSize * 2
                    || (bounds.outHeight / sampleSize) > targetSize * 2) {
                sampleSize *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sampleSize);
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (OutOfMemoryError ex) {
            Log.w(TAG, "decodeThumbnail OOM: " + uri);
            return null;
        } catch (Exception ex) {
            Log.w(TAG, "decodeThumbnail failed: " + uri + " -> " + ex.getMessage());
            return null;
        }
    }

    private Bitmap decodeSampled(Uri uri, int maxDim) {
        byte[] bytes = readImageBytes(uri);
        if (bytes == null) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            // 按最长边降采样；旧实现用 && 判断宽高，超宽/超高图完全不降采样会撑爆内存
            int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
            int sampleSize = 1;
            while (maxSide / (sampleSize * 2) >= maxDim) {
                sampleSize *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (OutOfMemoryError ex) {
            Log.w(TAG, "decodeSampled OOM: " + uri);
            return null;
        } catch (Exception ex) {
            Log.w(TAG, "decodeSampled failed: " + uri + " -> " + ex.getMessage());
            return null;
        }
    }

    private class BrowserAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return pageMode == PAGE_WORKS ? visibleWorks().size() : playlist.size();
        }

        @Override
        public Object getItem(int position) {
            return pageMode == PAGE_WORKS ? visibleWorks().get(position) : playlist.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (pageMode == PAGE_WORKS) {
                return workRow(visibleWorks().get(position), position, convertView);
            }
            return trackRow(playlist.get(position), position, convertView);
        }

        private View workRow(WorkItem work, int position, View convertView) {
            WorkRowHolder holder;
            LinearLayout row;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof WorkRowHolder) {
                row = (LinearLayout) convertView;
                holder = (WorkRowHolder) row.getTag();
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                row.setMinimumHeight(dp(88));
                GradientDrawable rowBg = cardDrawable();
                rowBg.setCornerRadius(dp(14));
                row.setBackground(rowBg);

                holder = new WorkRowHolder();
                holder.thumb = new ImageView(MainActivity.this);
                holder.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                holder.thumb.setBackgroundColor(colorThumbBg);
                row.addView(holder.thumb, new LinearLayout.LayoutParams(dp(72), dp(72)));

                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setGravity(Gravity.CENTER_VERTICAL);
                texts.setPadding(dp(12), 0, 0, 0);
                row.addView(texts, new LinearLayout.LayoutParams(0, -1, 1));

                holder.title = label("", 16, colorTextPrimary);
                holder.title.setSingleLine(true);
                holder.title.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(holder.title, new LinearLayout.LayoutParams(-1, 0, 1));

                holder.subtitle = label("", 13, colorTextSecondary);
                holder.subtitle.setSingleLine(true);
                holder.subtitle.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(holder.subtitle, new LinearLayout.LayoutParams(-1, 0, 1));
                row.setTag(holder);
            }

            loadCoverInto(holder.thumb, work.cover);
            boolean moving = position == movingWorkIndex;
            holder.title.setText(work.name);
            holder.title.setTextColor(moving ? colorAccent : colorTextPrimary);
            int imageCount = work.tracks.isEmpty() ? 0 : work.tracks.get(0).images.size();
            String coverState = work.cover == null ? "无封面" : "有封面 · " + imageCount + " 张图片";
            String subtitle = moving ? "拖动中 · 松开保存位置" : work.tracks.size() + " 首音轨 · " + coverState;
            holder.subtitle.setText(subtitle);
            holder.subtitle.setTextColor(moving ? colorAccent : colorTextSecondary);
            return row;
        }

        private View trackRow(MediaItem item, int position, View convertView) {
            TrackRowHolder holder;
            LinearLayout row;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof TrackRowHolder) {
                row = (LinearLayout) convertView;
                holder = (TrackRowHolder) row.getTag();
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(8), dp(12), dp(8));
                row.setMinimumHeight(dp(58));
                GradientDrawable rowBg = cardDrawable();
                rowBg.setCornerRadius(dp(14));
                row.setBackground(rowBg);

                holder = new TrackRowHolder();
                holder.title = label("", 15, colorTextPrimary);
                holder.title.setSingleLine(true);
                holder.title.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(holder.title, new LinearLayout.LayoutParams(-1, 0, 1));

                holder.subtitle = label("", 12, colorTextSecondary);
                holder.subtitle.setSingleLine(true);
                holder.subtitle.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(holder.subtitle, new LinearLayout.LayoutParams(-1, 0, 1));
                row.setTag(holder);
            }

            MediaItem current = currentPlaybackItem();
            boolean sameWork = playbackWork != null && activeWork != null && playbackWork.key.equals(activeWork.key);
            boolean isCurrent = sameWork && current != null && current.key.equals(item.key);
            int titleColor = position == movingTrackIndex
                    ? colorAccent
                    : (isCurrent ? colorAccent : colorTextPrimary);
            holder.title.setText(item.audio.name);
            holder.title.setTextColor(titleColor);

            String subtitle = position == movingTrackIndex
                    ? "拖动中 · 松开保存位置"
                    : (item.vtt == null ? "无字幕" : "有字幕") + " · " + item.audio.parentPath;
            holder.subtitle.setText(subtitle);
            holder.subtitle.setTextColor(position == movingTrackIndex ? colorAccent : colorTextSecondary);
            return row;
        }
    }

    private static class WorkRowHolder {
        ImageView thumb;
        TextView title;
        TextView subtitle;
    }

    private static class TrackRowHolder {
        TextView title;
        TextView subtitle;
    }

    private class LyricsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return cues.size();
        }

        @Override
        public Object getItem(int position) {
            return cues.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Cue cue = cues.get(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setMinimumHeight(dp(72));
            GradientDrawable rowBg = cardDrawable();
            rowBg.setCornerRadius(dp(14));
            row.setBackground(rowBg);

            boolean active = position == currentCueIndex;
            TextView time = label(formatTime((int) cue.startMs), 12, active ? colorAccent : colorTextSecondary);
            row.addView(time, new LinearLayout.LayoutParams(-1, dp(22)));

            TextView text = label(cue.text, active ? 17 : 15, active ? colorTextPrimary : colorTextSecondary);
            text.setGravity(Gravity.CENTER_VERTICAL);
            text.setMaxLines(4);
            text.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(text, new LinearLayout.LayoutParams(-1, -2));
            return row;
        }
    }

    private class WebHistoryAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return webHistory.size();
        }

        @Override
        public Object getItem(int position) {
            return webHistory.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return pageMode == PAGE_WORKS ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView row = label(webHistory.get(position), 13, colorTextPrimary);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setPadding(dp(10), 0, dp(10), 0);
            row.setMinimumHeight(dp(44));
            GradientDrawable rowBg = cardDrawable();
            rowBg.setCornerRadius(dp(10));
            row.setBackground(rowBg);
            return row;
        }
    }

    private class RecentAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return recentTracks.size();
        }

        @Override
        public Object getItem(int position) {
            return recentTracks.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RecentTrack recent = recentTracks.get(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            row.setMinimumHeight(dp(68));
            GradientDrawable rowBg = cardDrawable();
            rowBg.setCornerRadius(dp(14));
            row.setBackground(rowBg);

            TextView title = label(recent.trackName, 15, colorTextPrimary);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(title, new LinearLayout.LayoutParams(-1, 0, 1));

            TextView sub = label(TextUtils.isEmpty(recent.workName) ? "未知作品" : recent.workName, 12, colorTextSecondary);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(sub, new LinearLayout.LayoutParams(-1, 0, 1));
            return row;
        }
    }

    private static class WorkBuilder {
        final String key;
        String name;
        final List<FileDoc> audio = new ArrayList<>();
        final List<FileDoc> images = new ArrayList<>();
        final Map<String, FileDoc> vttByName = new HashMap<>();
        final Map<String, FileDoc> lrcByName = new HashMap<>();
        final Map<String, FileDoc> imageByStem = new HashMap<>();

        WorkBuilder(String key, String name) {
            this.key = TextUtils.isEmpty(key) ? name : key;
            this.name = name;
        }

        void add(FileDoc file) {
            String lower = file.name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".vtt")) {
                vttByName.put(lower, file);
                vttByName.put(stemStatic(file.name), file);
            } else if (lower.endsWith(".lrc")) {
                lrcByName.put(lower, file);
                lrcByName.put(stemStatic(file.name), file);
            } else if (lower.endsWith(".mp3") || lower.endsWith(".wav")) {
                audio.add(file);
            } else if (isImageStatic(lower)) {
                images.add(file);
                imageByStem.put(stemStatic(file.name), file);
            }
        }

        WorkItem toWork(Collator collator) {
            // collator 仅用于作品名排序；资源一律走固定全序 NameOrder
            Collections.sort(audio, (left, right) -> {
                int cmp = NameOrder.compare(left.name, right.name);
                return cmp != 0 ? cmp : NameOrder.compare(left.relativePath, right.relativePath);
            });
            // 先给全部图片一个与音轨一致的固定顺序
            Collections.sort(images, (left, right) -> {
                int cmp = NameOrder.compare(left.name, right.name);
                return cmp != 0 ? cmp : NameOrder.compare(left.relativePath, right.relativePath);
            });
            // 候选 = 多语言封面关键词图片 + 排序第一位的图片（兜底）。
            // 只把选中的那张提到第一位，其余图片相对顺序完全不变。
            List<String> imageNames = new ArrayList<>(images.size());
            for (FileDoc image : images) {
                imageNames.add(image.name);
            }
            int coverPosition = CoverNames.chooseCoverIndex(imageNames);
            if (coverPosition > 0) {
                images.add(0, images.remove(coverPosition));
            }
            FileDoc cover = images.isEmpty() ? null : images.get(0);
            List<MediaItem> tracks = new ArrayList<>();
            for (FileDoc file : audio) {
                List<FileDoc> trackImages = collectImagesFor(file, cover);
                FileDoc vtt = findVtt(file);
                tracks.add(new MediaItem(file, trackImages, vtt));
            }
            return new WorkItem(key, name, cover, tracks);
        }

        private List<FileDoc> collectImagesFor(FileDoc audioFile, FileDoc cover) {
            List<FileDoc> result = new ArrayList<>();
            addUnique(result, cover);
            FileDoc sameName = imageByStem.get(stemStatic(audioFile.name));
            addUnique(result, sameName);
            // 其余图片一律按统一固定顺序追加；旧版「同目录优先」会让同一作品内
            // 不同音轨看到的图片顺序不一致
            for (FileDoc image : images) {
                addUnique(result, image);
            }
            return result;
        }

        private void addUnique(List<FileDoc> result, FileDoc image) {
            if (image == null) {
                return;
            }
            for (FileDoc existing : result) {
                if (existing.uri.equals(image.uri)) {
                    return;
                }
            }
            result.add(image);
        }

        private FileDoc findVtt(FileDoc audioFile) {
            String lower = audioFile.name.toLowerCase(Locale.ROOT);
            String stem = stemStatic(audioFile.name);
            FileDoc exact = vttByName.get(lower + ".vtt");
            if (exact != null) {
                return exact;
            }
            exact = vttByName.get(stem + ".vtt");
            if (exact != null) {
                return exact;
            }
            exact = vttByName.get(stem);
            if (exact != null) {
                return exact;
            }
            FileDoc lrc = lrcByName.get(lower + ".lrc");
            if (lrc != null) {
                return lrc;
            }
            lrc = lrcByName.get(stem + ".lrc");
            if (lrc != null) {
                return lrc;
            }
            return lrcByName.get(stem);
        }

    }

    private static boolean isImageStatic(String lowerName) {
        return lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".png")
                || lowerName.endsWith(".webp")
                || lowerName.endsWith(".bmp");
    }

    private static String stemStatic(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT);
    }

    static class FileDoc {
        final String name;
        final String mime;
        final Uri uri;
        final String relativePath;
        final String parentPath;

        FileDoc(String name, String mime, Uri uri, String relativePath, String parentPath) {
            this.name = name;
            this.mime = mime;
            this.uri = uri;
            this.relativePath = relativePath;
            this.parentPath = parentPath;
        }
    }

    static class WorkItem {
        final String key;
        final String name;
        final FileDoc cover;
        final List<MediaItem> tracks;
        String rootUri;

        WorkItem(String key, String name, FileDoc cover, List<MediaItem> tracks) {
            this.key = TextUtils.isEmpty(key) ? name : key;
            this.name = name;
            this.cover = cover;
            this.tracks = tracks;
        }
    }

    private static class RootInfo {
        final String uri;
        final String name;
        NetSource net;

        RootInfo(String uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    static class MediaItem {
        final String key;
        final FileDoc audio;
        final FileDoc image;
        final List<FileDoc> images;
        final FileDoc vtt;

        MediaItem(FileDoc audio, List<FileDoc> images, FileDoc vtt) {
            this.key = audio.relativePath;
            this.audio = audio;
            this.images = images;
            this.image = images.isEmpty() ? null : images.get(0);
            this.vtt = vtt;
        }

        void moveCoverFirst(FileDoc cover) {
            for (int i = 0; i < images.size(); i++) {
                if (images.get(i).uri.equals(cover.uri)) {
                    images.remove(i);
                    images.add(0, cover);
                    break;
                }
            }
        }
    }

    private static class RecentTrack {
        final String workKey;
        final String trackKey;
        final String workName;
        final String trackName;

        RecentTrack(String workKey, String trackKey, String workName, String trackName) {
            this.workKey = workKey;
            this.trackKey = trackKey;
            this.workName = workName;
            this.trackName = trackName;
        }
    }

    private static class Cue {
        final long startMs;
        final long endMs;
        final String text;

        Cue(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    private static class TextDrawable extends android.graphics.drawable.Drawable {
        private final String text;
        private final int fixedTextSize;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        TextDrawable(String text) {
            this(text, 0);
        }

        TextDrawable(String text, int textSizePx) {
            this.text = text;
            this.fixedTextSize = textSizePx;
            paint.setColor(Color.WHITE);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            paint.setFakeBoldText(true);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidateSelf();
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            if (fixedTextSize > 0) {
                paint.setTextSize(fixedTextSize);
            } else {
                paint.setTextSize(Math.max(18, bounds.height() * 0.28f));
            }
            android.graphics.Paint.FontMetrics metrics = paint.getFontMetrics();
            float y = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(text, bounds.centerX(), y, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
