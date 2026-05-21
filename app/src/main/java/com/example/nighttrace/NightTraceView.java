package com.example.nighttrace;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class NightTraceView extends View {

    public enum GameState {
        READY,
        RUNNING,
        PAUSED,
        GAME_OVER
    }

    public interface OnGameStateChangeListener {
        void onStateChanged(int score, int distanceMeters, int stage, int lives, GameState state);
    }

    private static final float BASE_SPEED = 560f;
    private static final float MAX_SPEED = 1180f;
    private static final float LANE_CHANGE_SPEED = 14f;
    private static final float PLAYER_WIDTH = 70f;
    private static final float PLAYER_HEIGHT = 132f;
    private static final float OBSTACLE_WIDTH = 84f;
    private static final float OBSTACLE_HEIGHT = 86f;
    private static final float COIN_RADIUS = 18f;
    private static final float MAX_FRAME_SECONDS = 0.045f;
    private static final int MAX_LIVES = 3;
    private static final long JUMP_MS = 900L;
    private static final long SLIDE_MS = 620L;
    private static final long SHIELD_MS = 9000L;
    private static final long MAGNET_MS = 8500L;
    private static final long BOOST_MS = 3600L;
    private static final long OIL_SPLAT_MS = 2300L;
    private static final int TYPE_BARRIER = 0;
    private static final int TYPE_LASER = 1;
    private static final int TYPE_DRONE = 2;
    private static final int TYPE_OIL = 3;
    private static final int ITEM_COIN = 0;
    private static final int ITEM_SHIELD = 1;
    private static final int ITEM_MAGNET = 2;
    private static final int ITEM_BOOST = 3;
    private static final int ITEM_DIAMOND = 4;
    private static final int COLOR_BG_TOP = Color.parseColor("#050816");
    private static final int COLOR_BG_BOTTOM = Color.parseColor("#150724");
    private static final int COLOR_ROAD = Color.parseColor("#111827");
    private static final int COLOR_ROAD_EDGE = Color.parseColor("#0EA5E9");
    private static final int COLOR_LANE_LEFT = Color.parseColor("#22D3EE");
    private static final int COLOR_LANE_RIGHT = Color.parseColor("#FB7185");
    private static final int COLOR_GOLD = Color.parseColor("#FACC15");
    private static final int COLOR_GREEN = Color.parseColor("#34D399");
    private static final int COLOR_PURPLE = Color.parseColor("#C084FC");
    private static final int COLOR_RED = Color.parseColor("#F43F5E");

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF playerRect = new RectF();
    private final RectF itemRect = new RectF();
    private final Path path = new Path();
    private final Random random = new Random(20260513L);
    private final Choreographer choreographer = Choreographer.getInstance();
    private final List<Obstacle> obstacles = new ArrayList<>();
    private final List<Collectible> collectibles = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final float density;

    private Bitmap playerBitmap;
    private Bitmap highwaySceneBitmap;
    private Bitmap coneBitmap;
    private Bitmap tiresBitmap;
    private Bitmap barrierBitmap;
    private Bitmap oilBitmap;
    private Bitmap droneBitmap;
    private Bitmap barrelBitmap;
    private Bitmap lightBitmap;
    private Bitmap coinBitmap;
    private Bitmap diamondBitmap;
    private OnGameStateChangeListener listener;
    private GameState state = GameState.READY;
    private Player player = new Player();
    private boolean framePosted;
    private long lastFrameNanos;
    private long startMs;
    private long jumpUntil;
    private long slideUntil;
    private long shieldUntil;
    private long magnetUntil;
    private long boostUntil;
    private long slowUntil;
    private long shakeUntil;
    private long flashUntil;
    private long oilSplatUntil;
    private long lastHitMs;
    private float roadLeft;
    private float roadRight;
    private float roadTop;
    private float roadBottom;
    private float playerBaseY;
    private float worldScroll;
    private float distanceMeters;
    private float spawnTimer;
    private float coinTimer;
    private int score;
    private int combo;
    private int lives = MAX_LIVES;
    private int stage = 1;
    private int bestScore;
    private boolean beatBestThisRun;
    private float touchDownX;
    private float touchDownY;
    private long touchDownMs;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            framePosted = false;
            if (state != GameState.RUNNING) {
                return;
            }
            float deltaSeconds = lastFrameNanos == 0L
                    ? 1f / 60f
                    : Math.min(MAX_FRAME_SECONDS, (frameTimeNanos - lastFrameNanos) / 1_000_000_000f);
            lastFrameNanos = frameTimeNanos;
            update(deltaSeconds, System.currentTimeMillis());
            postInvalidateOnAnimation();
            postFrame();
        }
    };

    public NightTraceView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setFocusable(true);
        setClickable(true);
        loadBitmaps();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(16f * density);
        glowPaint.setMaskFilter(new BlurMaskFilter(18f * density, BlurMaskFilter.Blur.NORMAL));
    }

    public void setOnGameStateChangeListener(OnGameStateChangeListener listener) {
        this.listener = listener;
        notifyState();
    }

    public void setBestScore(int bestScore, boolean newRecord) {
        this.bestScore = bestScore;
        beatBestThisRun = beatBestThisRun || newRecord;
        invalidate();
    }

    public void startNewGame() {
        obstacles.clear();
        collectibles.clear();
        particles.clear();
        player = new Player();
        resetPlayerToRoad();
        state = GameState.RUNNING;
        score = 0;
        combo = 0;
        lives = MAX_LIVES;
        stage = 1;
        beatBestThisRun = false;
        distanceMeters = 0f;
        worldScroll = 0f;
        spawnTimer = 1.4f;
        coinTimer = 0.2f;
        jumpUntil = 0L;
        slideUntil = 0L;
        shieldUntil = 0L;
        magnetUntil = 0L;
        boostUntil = 0L;
        slowUntil = 0L;
        shakeUntil = 0L;
        flashUntil = 0L;
        oilSplatUntil = 0L;
        lastHitMs = 0L;
        startMs = System.currentTimeMillis();
        lastFrameNanos = 0L;
        notifyState();
        postFrame();
        invalidate();
    }

    public void togglePause() {
        if (state == GameState.READY || state == GameState.GAME_OVER) {
            startNewGame();
            return;
        }
        if (state == GameState.RUNNING) {
            state = GameState.PAUSED;
            framePosted = false;
            choreographer.removeFrameCallback(frameCallback);
        } else {
            state = GameState.RUNNING;
            lastFrameNanos = 0L;
            postFrame();
        }
        notifyState();
        invalidate();
    }

    public void returnToReady() {
        state = GameState.READY;
        choreographer.removeFrameCallback(frameCallback);
        framePosted = false;
        notifyState();
        invalidate();
    }

    public void pauseIfRunning() {
        if (state == GameState.RUNNING) {
            state = GameState.PAUSED;
            choreographer.removeFrameCallback(frameCallback);
            framePosted = false;
            notifyState();
            invalidate();
        }
    }

    public boolean handleKeyDown(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            startNewGame();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_P || keyCode == KeyEvent.KEYCODE_BUTTON_START) {
            togglePause();
            return true;
        }
        if (state != GameState.RUNNING) {
            return false;
        }
        if (keyCode == KeyEvent.KEYCODE_A || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            changeLane(-1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_D || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            changeLane(1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_W || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_SPACE) {
            jump();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_S || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_J) {
            slide();
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            requestFocus();
            touchDownX = event.getX();
            touchDownY = event.getY();
            touchDownMs = System.currentTimeMillis();
            if (state == GameState.READY || state == GameState.GAME_OVER) {
                startNewGame();
                return true;
            }
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (state != GameState.RUNNING) {
                return true;
            }
            float dx = event.getX() - touchDownX;
            float dy = event.getY() - touchDownY;
            float threshold = 36f * density;
            if (Math.abs(dx) < threshold && Math.abs(dy) < threshold && System.currentTimeMillis() - touchDownMs < 240L) {
                if (touchDownX < getWidth() * 0.5f) {
                    changeLane(-1);
                } else {
                    changeLane(1);
                }
            } else if (Math.abs(dx) > Math.abs(dy)) {
                changeLane(dx > 0f ? 1 : -1);
            } else if (dy < 0f) {
                jump();
            } else {
                slide();
            }
            return true;
        }
        return true;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        roadTop = 0f;
        roadBottom = height;
        roadLeft = width * 0.24f;
        roadRight = width * 0.76f;
        playerBaseY = height * 0.76f;
        resetPlayerToRoad();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.currentTimeMillis();
        canvas.save();
        if (now < shakeUntil) {
            float remain = (shakeUntil - now) / 450f;
            float strength = Math.max(0f, remain) * 9f * density;
            float shakeX = (float) Math.sin(now * 0.09f) * strength;
            float shakeY = (float) Math.cos(now * 0.13f) * strength * 0.45f;
            canvas.translate(shakeX, shakeY);
        }
        drawCity(canvas, now);
        drawRoad(canvas, now);
        drawCollectibles(canvas, now);
        drawObstacles(canvas, now);
        drawPlayer(canvas, now);
        drawParticles(canvas);
        drawEffects(canvas, now);
        canvas.restore();
        drawHitFlash(canvas, now);
        drawOilSplatOverlay(canvas, now);
        drawOverlay(canvas);
    }

    private void update(float deltaSeconds, long now) {
        float speed = currentSpeed(now);
        worldScroll += speed * deltaSeconds;
        distanceMeters += speed * deltaSeconds / 42f;
        stage = 1 + Math.min(5, (int) (distanceMeters / 300f));
        score += Math.max(1, stage) * (boostActive(now) ? 3 : 1);

        player.x += (player.targetX - player.x) * Math.min(1f, LANE_CHANGE_SPEED * deltaSeconds);
        spawnTimer -= deltaSeconds;
        coinTimer -= deltaSeconds;
        if (spawnTimer <= 0f) {
            spawnObstacle();
            spawnTimer = nextObstacleDelay();
        }
        if (coinTimer <= 0f) {
            spawnCollectible();
            coinTimer = 0.52f + random.nextFloat() * 0.45f;
        }

        for (Obstacle obstacle : obstacles) {
            obstacle.y += speed * deltaSeconds * obstacle.speedScale;
            if (obstacle.type == TYPE_DRONE) {
                obstacle.drift += deltaSeconds * obstacle.driftDirection;
                if (Math.abs(obstacle.drift) > 1.2f) {
                    obstacle.driftDirection *= -1f;
                }
            }
        }
        for (Collectible collectible : collectibles) {
            float itemSpeed = speed * deltaSeconds;
            if (magnetActive(now)) {
                float dx = player.x - collectible.x;
                float dy = playerY(now) - collectible.y;
                float dist = (float) Math.hypot(dx, dy);
                if (dist < 250f * density && dist > 1f) {
                    collectible.x += dx / dist * speed * deltaSeconds * 0.72f;
                    collectible.y += dy / dist * speed * deltaSeconds * 0.72f;
                }
            }
            collectible.y += itemSpeed;
            collectible.spin += deltaSeconds * 4.8f;
        }
        for (Particle particle : particles) {
            particle.age += deltaSeconds;
            particle.x += particle.vx * deltaSeconds;
            particle.y += particle.vy * deltaSeconds;
        }

        handleCollisions(now);
        cleanLists();
        if (random.nextFloat() < 0.35f) {
            particles.add(Particle.trail(player.x + randomSigned(12f), playerY(now) + 48f * density));
        }
        notifyState();
    }

    private void handleCollisions(long now) {
        float py = playerY(now);
        playerRect.set(player.x - PLAYER_WIDTH * density * 0.46f, py - PLAYER_HEIGHT * density * 0.42f,
                player.x + PLAYER_WIDTH * density * 0.46f, py + PLAYER_HEIGHT * density * 0.42f);

        for (Obstacle obstacle : obstacles) {
            if (obstacle.hit) {
                continue;
            }
            RectF hitBox = obstacle.hitBox(rect);
            if (!RectF.intersects(playerRect, hitBox)) {
                continue;
            }
            boolean avoided = (obstacle.type == TYPE_BARRIER && jumping(now))
                    || (obstacle.type == TYPE_LASER && sliding(now))
                    || boostActive(now);
            if (avoided) {
                if (!obstacle.scored) {
                    obstacle.scored = true;
                    score += 120 + stage * 20;
                    combo++;
                    burst(obstacle.x, obstacle.y, COLOR_LANE_LEFT, 8);
                }
            } else {
                hitPlayer(now, obstacle);
            }
        }

        for (Collectible collectible : collectibles) {
            if (collectible.collected) {
                continue;
            }
            itemRect.set(collectible.x - 24f * density, collectible.y - 24f * density,
                    collectible.x + 24f * density, collectible.y + 24f * density);
            if (RectF.intersects(playerRect, itemRect)) {
                collect(now, collectible);
            }
        }
    }

    private void collect(long now, Collectible collectible) {
        collectible.collected = true;
        if (collectible.type == ITEM_COIN) {
            combo++;
            score += 60 + Math.min(10, combo) * 12;
            burst(collectible.x, collectible.y, COLOR_GOLD, 10);
        } else if (collectible.type == ITEM_DIAMOND) {
            combo += 2;
            score += 220 + Math.min(10, combo) * 20;
            burst(collectible.x, collectible.y, COLOR_LANE_LEFT, 18);
        } else if (collectible.type == ITEM_SHIELD) {
            shieldUntil = now + SHIELD_MS;
            score += 90;
            burst(collectible.x, collectible.y, COLOR_GREEN, 14);
        } else if (collectible.type == ITEM_MAGNET) {
            magnetUntil = now + MAGNET_MS;
            score += 90;
            burst(collectible.x, collectible.y, COLOR_PURPLE, 14);
        } else {
            boostUntil = now + BOOST_MS;
            score += 150;
            burst(collectible.x, collectible.y, Color.WHITE, 18);
        }
    }

    private void hitPlayer(long now, Obstacle obstacle) {
        if (now - lastHitMs < 850L) {
            return;
        }
        lastHitMs = now;
        if (obstacle.type == TYPE_BARRIER) {
            hitSoftObstacle(now, obstacle);
            return;
        }
        if (obstacle.type == TYPE_OIL) {
            hitOilObstacle(now, obstacle);
            return;
        }
        if (shieldActive(now)) {
            shieldUntil = 0L;
            obstacle.hit = true;
            score += 80;
            combo = 0;
            burst(obstacle.x, obstacle.y, COLOR_GREEN, 20);
            return;
        }
        state = GameState.GAME_OVER;
        choreographer.removeFrameCallback(frameCallback);
        framePosted = false;
        combo = 0;
        burst(player.x, playerY(now), COLOR_RED, 34);
        notifyState();
    }

    private void hitSoftObstacle(long now, Obstacle obstacle) {
        obstacle.hit = true;
        combo = 0;
        lives = Math.max(0, lives - 1);
        slowUntil = now + 1200L;
        shakeUntil = now + 380L;
        flashUntil = now + 300L;
        score = Math.max(0, score - 70);
        burst(obstacle.x, obstacle.y, COLOR_RED, 14);
        if (lives <= 0) {
            state = GameState.GAME_OVER;
            choreographer.removeFrameCallback(frameCallback);
            framePosted = false;
            burst(player.x, playerY(now), COLOR_RED, 34);
        }
        notifyState();
    }

    private void hitOilObstacle(long now, Obstacle obstacle) {
        obstacle.hit = true;
        combo = 0;
        slowUntil = now + 1650L;
        shakeUntil = now + 520L;
        oilSplatUntil = now + OIL_SPLAT_MS;
        score = Math.max(0, score - 120);
        forceSkidLane();
        burst(obstacle.x, obstacle.y, COLOR_PURPLE, 18);
        notifyState();
    }

    private void spawnObstacle() {
        int lane = randomClearLaneForObstacle();
        int type;
        if (distanceMeters < 300f) {
            type = random.nextBoolean() ? TYPE_BARRIER : TYPE_OIL;
        } else if (distanceMeters < 800f) {
            type = random.nextInt(3);
        } else {
            type = random.nextInt(4);
        }
        Obstacle obstacle = new Obstacle(type, lane, laneCenter(lane), -90f * density);
        if (type == TYPE_DRONE) {
            obstacle.speedScale = 1.18f;
            obstacle.driftDirection = random.nextBoolean() ? 1f : -1f;
        }
        obstacles.add(obstacle);

        if (stage >= 3 && random.nextFloat() < 0.34f) {
            int secondLane = (lane + 1 + random.nextInt(2)) % 3;
            obstacles.add(new Obstacle(random.nextBoolean() ? TYPE_LASER : TYPE_BARRIER,
                    secondLane, laneCenter(secondLane), -220f * density));
        }
    }

    private void spawnCollectible() {
        int lane = randomClearLaneForCollectible();
        if (lane < 0) {
            return;
        }
        int type = ITEM_COIN;
        float roll = random.nextFloat();
        if (distanceMeters > 180f && roll < 0.065f) {
            type = ITEM_DIAMOND;
        } else if (distanceMeters > 220f && roll < 0.12f) {
            type = ITEM_SHIELD;
        } else if (distanceMeters > 420f && roll < 0.15f) {
            type = ITEM_MAGNET;
        } else if (distanceMeters > 650f && roll < 0.18f) {
            type = ITEM_BOOST;
        }
        collectibles.add(new Collectible(type, laneCenter(lane), -40f * density));
    }

    private int randomClearLaneForObstacle() {
        int start = random.nextInt(3);
        for (int offset = 0; offset < 3; offset++) {
            int lane = (start + offset) % 3;
            if (!hasCollectibleNearSpawnLane(lane)) {
                return lane;
            }
        }
        return start;
    }

    private int randomClearLaneForCollectible() {
        int start = random.nextInt(3);
        for (int offset = 0; offset < 3; offset++) {
            int lane = (start + offset) % 3;
            if (!hasObstacleNearSpawnLane(lane)) {
                return lane;
            }
        }
        return -1;
    }

    private boolean hasObstacleNearSpawnLane(int lane) {
        for (Obstacle obstacle : obstacles) {
            if (obstacle.lane == lane && obstacle.y < 240f * density) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCollectibleNearSpawnLane(int lane) {
        float laneX = laneCenter(lane);
        for (Collectible collectible : collectibles) {
            if (Math.abs(collectible.x - laneX) < 8f * density && collectible.y < 180f * density) {
                return true;
            }
        }
        return false;
    }

    private void cleanLists() {
        float bottom = getHeight() + 180f * density;
        Iterator<Obstacle> obstacleIterator = obstacles.iterator();
        while (obstacleIterator.hasNext()) {
            Obstacle obstacle = obstacleIterator.next();
            if (obstacle.y > bottom || obstacle.hit) {
                obstacleIterator.remove();
            }
        }
        Iterator<Collectible> collectibleIterator = collectibles.iterator();
        while (collectibleIterator.hasNext()) {
            Collectible collectible = collectibleIterator.next();
            if (collectible.y > bottom || collectible.collected) {
                collectibleIterator.remove();
            }
        }
        Iterator<Particle> particleIterator = particles.iterator();
        while (particleIterator.hasNext()) {
            Particle particle = particleIterator.next();
            if (particle.age > particle.life) {
                particleIterator.remove();
            }
        }
    }

    private void drawCity(Canvas canvas, long now) {
        if (highwaySceneBitmap != null) {
            rect.set(0f, 0f, getWidth(), getHeight());
            canvas.drawBitmap(highwaySceneBitmap, null, rect, null);

            paint.setShader(new LinearGradient(0f, 0f, 0f, getHeight(),
                    Color.argb(84, 4, 8, 20), Color.argb(28, 8, 12, 24), Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
            paint.setShader(null);

            float lightGap = 128f * density;
            float lightOffset = worldScroll * 0.36f % lightGap;
            for (float y = -lightGap + lightOffset; y < getHeight() + lightGap; y += lightGap) {
                float pulse = 0.55f + 0.45f * randomHash((int) (y / Math.max(1f, density)) + 9);
                drawRoadsideLight(canvas, getWidth() * 0.12f, y, COLOR_LANE_LEFT, pulse);
                drawRoadsideLight(canvas, getWidth() * 0.88f, y + lightGap * 0.42f, COLOR_GOLD, pulse * 0.86f);
            }
            return;
        }

        paint.setShader(new LinearGradient(0f, 0f, 0f, getHeight(), COLOR_BG_TOP, COLOR_BG_BOTTOM, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
        paint.setShader(null);

        float blockHeight = 92f * density;
        float offset = worldScroll * 0.22f % blockHeight;
        for (float y = -blockHeight + offset; y < getHeight() + blockHeight; y += blockHeight) {
            drawBuilding(canvas, 0f, y, roadLeft - 12f * density, blockHeight * 0.82f, true);
            drawBuilding(canvas, roadRight + 12f * density, y + blockHeight * 0.35f,
                    getWidth() - roadRight - 12f * density, blockHeight * 0.9f, false);
        }

        paint.setColor(Color.argb(28, 34, 211, 238));
        for (int i = 0; i < 24; i++) {
            float x = (i * 71f * density + (now % 3000L) * 0.02f) % Math.max(1, getWidth());
            float y = (i * 137f * density + worldScroll * 0.12f) % Math.max(1, getHeight());
            canvas.drawCircle(x, y, 1.4f * density, paint);
        }
    }

    private void drawRoadsideLight(Canvas canvas, float x, float y, int color, float pulse) {
        glowPaint.setColor(Color.argb((int) (85 * pulse), Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawCircle(x, y, 20f * density, glowPaint);
        paint.setColor(Color.argb((int) (150 * pulse), Color.red(color), Color.green(color), Color.blue(color)));
        rect.set(x - 2.5f * density, y - 18f * density, x + 2.5f * density, y + 18f * density);
        canvas.drawRoundRect(rect, 3f * density, 3f * density, paint);
    }

    private void drawBuilding(Canvas canvas, float x, float y, float width, float height, boolean leftSide) {
        if (width <= 4f) {
            return;
        }
        paint.setShader(new LinearGradient(x, y, x + width, y + height,
                Color.parseColor("#101827"), Color.parseColor("#26113A"), Shader.TileMode.CLAMP));
        rect.set(x, y, x + width, y + height);
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint);
        paint.setShader(null);

        int rows = Math.max(2, (int) (height / (18f * density)));
        int cols = Math.max(1, (int) (width / (20f * density)));
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int color = ((row + col) % 3 == 0) ? COLOR_LANE_LEFT : COLOR_LANE_RIGHT;
                paint.setColor(Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)));
                float wx = x + 8f * density + col * 18f * density;
                float wy = y + 8f * density + row * 16f * density;
                rect.set(wx, wy, wx + 8f * density, wy + 4f * density);
                canvas.drawRoundRect(rect, 2f * density, 2f * density, paint);
            }
        }
        if (lightBitmap != null && height > 120f * density) {
            rect.set(leftSide ? x + width - 28f * density : x + 8f * density,
                    y + 14f * density,
                    leftSide ? x + width - 6f * density : x + 30f * density,
                    y + 68f * density);
            paint.setAlpha(150);
            canvas.drawBitmap(lightBitmap, null, rect, paint);
            paint.setAlpha(255);
        }
    }

    private void drawRoad(Canvas canvas, long now) {
        float bottomLeft = getWidth() * 0.13f;
        float bottomRight = getWidth() * 0.87f;
        path.reset();
        path.moveTo(roadLeft, roadTop);
        path.lineTo(roadRight, roadTop);
        path.lineTo(bottomRight, roadBottom);
        path.lineTo(bottomLeft, roadBottom);
        path.close();
        if (highwaySceneBitmap != null) {
            paint.setShader(new LinearGradient(0f, 0f, 0f, getHeight(),
                    Color.argb(36, 12, 18, 28), Color.argb(118, 8, 12, 19), Shader.TileMode.CLAMP));
        } else {
            paint.setColor(COLOR_ROAD);
        }
        canvas.drawPath(path, paint);
        paint.setShader(null);

        drawBridgeShoulder(canvas, roadLeft, bottomLeft, Color.argb(232, 34, 211, 238));
        drawBridgeShoulder(canvas, roadRight, bottomRight, Color.argb(232, 250, 204, 21));

        for (int lane = 1; lane < 3; lane++) {
            float ratio = lane / 3f;
            float topX = roadLeft + (roadRight - roadLeft) * ratio;
            float bottomX = bottomLeft + (bottomRight - bottomLeft) * ratio;
            drawLaneDivider(canvas, topX, bottomX, lane);
        }

        float stripeGap = 164f * density;
        float offset = worldScroll % stripeGap;
        for (float y = -stripeGap + offset; y < getHeight() + stripeGap; y += stripeGap) {
            float topY = y;
            float bottomY = y + 5f * density;
            float roadT = topY / Math.max(1f, getHeight());
            float leftX = lerp(roadLeft, bottomLeft, roadT);
            float rightX = lerp(roadRight, bottomRight, roadT);
            paint.setColor(Color.argb(88, 226, 232, 240));
            canvas.drawLine(leftX + 10f * density, topY, rightX - 10f * density, bottomY, paint);
        }

    }

    private void drawBridgeShoulder(Canvas canvas, float topX, float bottomX, int color) {
        glowPaint.setStrokeWidth(10f * density);
        glowPaint.setColor(Color.argb(96, Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawLine(topX, 0f, bottomX, getHeight(), glowPaint);
        paint.setStrokeWidth(2.8f * density);
        paint.setColor(color);
        canvas.drawLine(topX, 0f, bottomX, getHeight(), paint);
        paint.setStrokeWidth(1f);
    }

    private void drawLaneDivider(Canvas canvas, float topX, float bottomX, int lane) {
        float dashGap = 88f * density;
        float offset = (worldScroll * 0.92f + lane * dashGap * 0.35f) % dashGap;
        glowPaint.setStrokeWidth(4.2f * density);
        glowPaint.setColor(Color.argb(52, 255, 255, 255));
        paint.setStrokeWidth(2f * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(Color.argb(190, 241, 245, 249));
        for (float y = -dashGap + offset; y < getHeight() + dashGap; y += dashGap) {
            float y2 = y + 34f * density;
            float startX = lerp(topX, bottomX, y / Math.max(1f, getHeight()));
            float endX = lerp(topX, bottomX, y2 / Math.max(1f, getHeight()));
            canvas.drawLine(startX, y, endX, y2, glowPaint);
            canvas.drawLine(startX, y, endX, y2, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(1f);
    }

    private void drawCollectibles(Canvas canvas, long now) {
        for (Collectible collectible : collectibles) {
            if (collectible.type == ITEM_COIN) {
                drawCoin(canvas, collectible);
            } else if (collectible.type == ITEM_DIAMOND) {
                drawDiamond(canvas, collectible);
            } else {
                drawPowerUp(canvas, collectible);
            }
        }
    }

    private void drawCoin(Canvas canvas, Collectible coin) {
        float radius = COIN_RADIUS * density * (1f + 0.08f * (float) Math.sin(coin.spin));
        glowPaint.setColor(Color.argb(130, 250, 204, 21));
        canvas.drawCircle(coin.x, coin.y, radius * 1.65f, glowPaint);
        if (coinBitmap != null) {
            rect.set(coin.x - radius * 1.15f, coin.y - radius * 1.15f, coin.x + radius * 1.15f, coin.y + radius * 1.15f);
            canvas.drawBitmap(coinBitmap, null, rect, null);
        } else {
            paint.setShader(new RadialGradient(coin.x - radius * 0.4f, coin.y - radius * 0.4f, radius * 1.4f,
                    Color.WHITE, COLOR_GOLD, Shader.TileMode.CLAMP));
            canvas.drawCircle(coin.x, coin.y, radius, paint);
            paint.setShader(null);
        }
    }

    private void drawDiamond(Canvas canvas, Collectible diamond) {
        float pulse = 1f + 0.08f * (float) Math.sin(diamond.spin * 1.4f);
        float size = 31f * density * pulse;
        glowPaint.setColor(Color.argb(165, 34, 211, 238));
        canvas.drawCircle(diamond.x, diamond.y, 32f * density, glowPaint);
        if (diamondBitmap != null) {
            rect.set(diamond.x - size, diamond.y - size, diamond.x + size, diamond.y + size);
            canvas.drawBitmap(diamondBitmap, null, rect, null);
        } else {
            paint.setColor(COLOR_LANE_LEFT);
            path.reset();
            path.moveTo(diamond.x, diamond.y - size);
            path.lineTo(diamond.x + size, diamond.y);
            path.lineTo(diamond.x, diamond.y + size);
            path.lineTo(diamond.x - size, diamond.y);
            path.close();
            canvas.drawPath(path, paint);
        }
    }

    private void drawPowerUp(Canvas canvas, Collectible item) {
        int color = item.type == ITEM_SHIELD ? COLOR_GREEN : item.type == ITEM_MAGNET ? COLOR_PURPLE : Color.WHITE;
        glowPaint.setColor(Color.argb(150, Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawCircle(item.x, item.y, 30f * density, glowPaint);
        paint.setColor(color);
        rect.set(item.x - 20f * density, item.y - 20f * density, item.x + 20f * density, item.y + 20f * density);
        canvas.drawRoundRect(rect, 10f * density, 10f * density, paint);
        paint.setColor(Color.parseColor("#0F172A"));
        if (item.type == ITEM_SHIELD) {
            drawShieldIcon(canvas, item.x, item.y);
        } else if (item.type == ITEM_MAGNET) {
            drawMagnetIcon(canvas, item.x, item.y);
        } else {
            drawBoostIcon(canvas, item.x, item.y);
        }
    }

    private void drawShieldIcon(Canvas canvas, float cx, float cy) {
        path.reset();
        path.moveTo(cx, cy - 13f * density);
        path.lineTo(cx + 11f * density, cy - 7f * density);
        path.lineTo(cx + 8f * density, cy + 8f * density);
        path.lineTo(cx, cy + 14f * density);
        path.lineTo(cx - 8f * density, cy + 8f * density);
        path.lineTo(cx - 11f * density, cy - 7f * density);
        path.close();
        canvas.drawPath(path, paint);
    }

    private void drawMagnetIcon(Canvas canvas, float cx, float cy) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(5f * density);
        rect.set(cx - 12f * density, cy - 10f * density, cx + 12f * density, cy + 14f * density);
        canvas.drawArc(rect, 205f, 130f, false, paint);
        canvas.drawLine(cx - 9f * density, cy + 5f * density, cx - 14f * density, cy + 13f * density, paint);
        canvas.drawLine(cx + 9f * density, cy + 5f * density, cx + 14f * density, cy + 13f * density, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawBoostIcon(Canvas canvas, float cx, float cy) {
        path.reset();
        path.moveTo(cx + 2f * density, cy - 15f * density);
        path.lineTo(cx - 10f * density, cy + 2f * density);
        path.lineTo(cx - 1f * density, cy + 2f * density);
        path.lineTo(cx - 5f * density, cy + 15f * density);
        path.lineTo(cx + 11f * density, cy - 4f * density);
        path.lineTo(cx + 2f * density, cy - 4f * density);
        path.close();
        canvas.drawPath(path, paint);
    }

    private void drawObstacles(Canvas canvas, long now) {
        for (Obstacle obstacle : obstacles) {
            RectF dst = obstacle.drawBox(rect);
            if (obstacle.type == TYPE_BARRIER) {
                drawBitmapWithGlow(canvas, coneBitmap != null ? coneBitmap : barrierBitmap, dst, Color.parseColor("#FB923C"));
            } else if (obstacle.type == TYPE_LASER) {
                drawLaserGate(canvas, obstacle);
            } else if (obstacle.type == TYPE_DRONE) {
                RectF droneDst = new RectF(dst);
                droneDst.offset(obstacle.drift * 18f * density, 0f);
                drawBitmapWithGlow(canvas, droneBitmap, droneDst, COLOR_LANE_LEFT);
            } else {
                drawBitmapWithGlow(canvas, oilBitmap, dst, COLOR_PURPLE);
            }
        }
    }

    private void drawLaserGate(Canvas canvas, Obstacle obstacle) {
        float x = obstacle.x;
        float y = obstacle.y;
        glowPaint.setColor(Color.argb(150, 192, 132, 252));
        glowPaint.setStrokeWidth(8f * density);
        canvas.drawLine(x - 42f * density, y - 34f * density, x + 42f * density, y + 34f * density, glowPaint);
        canvas.drawLine(x - 42f * density, y + 34f * density, x + 42f * density, y - 34f * density, glowPaint);
        paint.setColor(COLOR_PURPLE);
        paint.setStrokeWidth(3f * density);
        canvas.drawLine(x - 42f * density, y - 34f * density, x + 42f * density, y + 34f * density, paint);
        canvas.drawLine(x - 42f * density, y + 34f * density, x + 42f * density, y - 34f * density, paint);
        if (barrelBitmap != null) {
            rect.set(x - 60f * density, y - 24f * density, x - 28f * density, y + 24f * density);
            canvas.drawBitmap(barrelBitmap, null, rect, null);
            rect.set(x + 28f * density, y - 24f * density, x + 60f * density, y + 24f * density);
            canvas.drawBitmap(barrelBitmap, null, rect, null);
        }
    }

    private void drawPlayer(Canvas canvas, long now) {
        float py = playerY(now);
        float jumpLift = jumpLift(now);
        float y = py - jumpLift;
        float stretch = sliding(now) ? 1.18f : 1f;
        float squash = sliding(now) ? 0.78f : 1f;

        glowPaint.setColor(boostActive(now) ? Color.argb(190, 255, 255, 255) : Color.argb(150, 34, 211, 238));
        canvas.drawOval(player.x - 42f * density * stretch, py + 26f * density,
                player.x + 42f * density * stretch, py + 68f * density, glowPaint);

        if (boostActive(now)) {
            paint.setShader(new LinearGradient(player.x, y + 18f * density, player.x, y + 92f * density,
                    Color.argb(230, 255, 255, 255), Color.argb(20, 34, 211, 238), Shader.TileMode.CLAMP));
            path.reset();
            path.moveTo(player.x - 17f * density, y + 38f * density);
            path.lineTo(player.x, y + 108f * density);
            path.lineTo(player.x + 17f * density, y + 38f * density);
            path.close();
            canvas.drawPath(path, paint);
            paint.setShader(null);
        }

        float width = PLAYER_WIDTH * density * stretch;
        float height = PLAYER_HEIGHT * density * squash;
        rect.set(player.x - width * 0.5f, y - height * 0.5f,
                player.x + width * 0.5f, y + height * 0.5f);
        if (playerBitmap != null) {
            Paint bitmapPaint = null;
            if (boostActive(now)) {
                bitmapPaint = paint;
                bitmapPaint.setColorFilter(new PorterDuffColorFilter(Color.argb(85, 255, 255, 255), PorterDuff.Mode.SRC_ATOP));
            }
            canvas.drawBitmap(playerBitmap, null, rect, bitmapPaint);
            paint.setColorFilter(null);
        } else {
            paint.setColor(COLOR_LANE_LEFT);
            canvas.drawRoundRect(rect, 14f * density, 14f * density, paint);
        }

        if (shieldActive(now)) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f * density);
            paint.setColor(COLOR_GREEN);
            canvas.drawCircle(player.x, y, 58f * density, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void drawParticles(Canvas canvas) {
        for (Particle particle : particles) {
            float alpha = Math.max(0f, 1f - particle.age / particle.life);
            paint.setColor(Color.argb((int) (alpha * 220), Color.red(particle.color), Color.green(particle.color), Color.blue(particle.color)));
            canvas.drawCircle(particle.x, particle.y, particle.radius * density * alpha, paint);
        }
    }

    private void drawEffects(Canvas canvas, long now) {
        if (boostActive(now)) {
            paint.setColor(Color.argb(35, 255, 255, 255));
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
        }
        if (stage >= 4) {
            paint.setColor(Color.argb(20 + stage * 4, 244, 63, 94));
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
        }
    }

    private void drawHitFlash(Canvas canvas, long now) {
        if (now >= flashUntil) {
            return;
        }
        float alpha = Math.max(0f, (flashUntil - now) / 320f);
        paint.setColor(Color.argb((int) (alpha * 120), 244, 63, 94));
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
    }

    private void drawOilSplatOverlay(Canvas canvas, long now) {
        if (now >= oilSplatUntil) {
            return;
        }
        float fade = Math.min(1f, (oilSplatUntil - now) / 720f);
        int alpha = (int) (fade * 230);
        paint.setColor(Color.argb((int) (fade * 46), 1, 2, 5));
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);

        drawOilScreenSplat(canvas, getWidth() * 0.48f, getHeight() * 0.34f,
                getWidth() * 0.72f, getHeight() * 0.42f, alpha, -12f);
        drawOilScreenSplat(canvas, getWidth() * 0.15f, getHeight() * 0.60f,
                getWidth() * 0.52f, getHeight() * 0.35f, (int) (alpha * 0.9f), 24f);
        drawOilScreenSplat(canvas, getWidth() * 0.86f, getHeight() * 0.50f,
                getWidth() * 0.47f, getHeight() * 0.32f, (int) (alpha * 0.86f), -31f);
        drawOilScreenSplat(canvas, getWidth() * 0.51f, getHeight() * 0.79f,
                getWidth() * 0.78f, getHeight() * 0.34f, (int) (alpha * 0.94f), 9f);
    }

    private void drawOilScreenSplat(Canvas canvas, float cx, float cy, float width, float height, int alpha, float rotation) {
        canvas.save();
        canvas.rotate(rotation, cx, cy);
        rect.set(cx - width * 0.5f, cy - height * 0.5f, cx + width * 0.5f, cy + height * 0.5f);
        if (oilBitmap != null) {
            paint.setAlpha(alpha);
            canvas.drawBitmap(oilBitmap, null, rect, paint);
            paint.setAlpha(255);
        } else {
            paint.setColor(Color.argb(alpha, 4, 5, 10));
            canvas.drawOval(rect, paint);
            canvas.drawCircle(cx - width * 0.29f, cy + height * 0.08f, height * 0.22f, paint);
            canvas.drawCircle(cx + width * 0.25f, cy - height * 0.11f, height * 0.18f, paint);
        }
        canvas.restore();
    }

    private void drawOverlay(Canvas canvas) {
        textPaint.setTextSize(13f * density);
        textPaint.setColor(Color.argb(210, 226, 232, 240));
        if (state == GameState.READY) {
            drawCenterPanel(canvas, "夜幕疾影", "点击开始  |  左右滑动换道  上滑跳跃  下滑滑铲");
        } else if (state == GameState.PAUSED) {
            drawGameHud(canvas);
        } else if (state == GameState.GAME_OVER) {
            drawGameOverPanel(canvas);
        } else {
            drawGameHud(canvas);
            String buff = "";
            long now = System.currentTimeMillis();
            if (shieldActive(now)) {
                buff += "护盾 ";
            }
            if (magnetActive(now)) {
                buff += "磁吸 ";
            }
            if (boostActive(now)) {
                buff += "冲刺 ";
            }
            if (now < slowUntil) {
                buff += "打滑 ";
            }
            if (!buff.isEmpty() || combo >= 3) {
                paint.setColor(Color.argb(130, 15, 23, 42));
                rect.set(getWidth() * 0.24f, 68f * density, getWidth() * 0.76f, 98f * density);
                canvas.drawRoundRect(rect, 10f * density, 10f * density, paint);
                textPaint.setTextSize(13f * density);
                textPaint.setColor(Color.WHITE);
                canvas.drawText(buff + (combo >= 3 ? "COMBO x" + Math.min(10, combo) : ""),
                        getWidth() * 0.5f, 89f * density, textPaint);
            }
        }
    }

    private void drawGameHud(Canvas canvas) {
        drawLivesHud(canvas);
        drawScoreHud(canvas);
    }

    private void drawLivesHud(Canvas canvas) {
        float left = 12f * density;
        float top = 12f * density;
        float right = left + 132f * density;
        float bottom = top + 52f * density;
        paint.setColor(Color.argb(165, 15, 23, 42));
        rect.set(left, top, right, bottom);
        canvas.drawRoundRect(rect, 10f * density, 10f * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f * density);
        paint.setColor(Color.argb(190, 251, 113, 133));
        canvas.drawRoundRect(rect, 10f * density, 10f * density, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < MAX_LIVES; i++) {
            float cx = left + (30f + i * 36f) * density;
            float cy = top + 27f * density;
            paint.setColor(i < lives ? COLOR_RED : Color.argb(130, 71, 85, 105));
            drawHeart(canvas, cx, cy, 11f * density);
        }
    }

    private void drawScoreHud(Canvas canvas) {
        float right = getWidth() - 12f * density;
        float top = 12f * density;
        float left = right - 132f * density;
        float bottom = top + 52f * density;
        paint.setColor(Color.argb(182, 15, 23, 42));
        rect.set(left, top, right, bottom);
        canvas.drawRoundRect(rect, 12f * density, 12f * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f * density);
        paint.setColor(Color.argb(190, 250, 204, 21));
        canvas.drawRoundRect(rect, 12f * density, 12f * density, paint);
        paint.setStyle(Paint.Style.FILL);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(Color.WHITE);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(23f * density);
        canvas.drawText(String.valueOf(score), right - 12f * density, top + 32f * density, textPaint);
        textPaint.setFakeBoldText(false);
        textPaint.setTextSize(10f * density);
        textPaint.setColor(Color.argb(210, 253, 230, 138));
        canvas.drawText("SCORE", right - 12f * density, bottom - 8f * density, textPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    private void drawHeart(Canvas canvas, float cx, float cy, float size) {
        path.reset();
        path.moveTo(cx, cy + size * 0.72f);
        path.cubicTo(cx - size * 1.45f, cy - size * 0.1f, cx - size * 0.95f, cy - size * 1.05f, cx, cy - size * 0.45f);
        path.cubicTo(cx + size * 0.95f, cy - size * 1.05f, cx + size * 1.45f, cy - size * 0.1f, cx, cy + size * 0.72f);
        path.close();
        canvas.drawPath(path, paint);
    }

    private void drawCenterPanel(Canvas canvas, String title, String subtitle) {
        paint.setColor(Color.argb(178, 5, 8, 22));
        rect.set(24f * density, getHeight() * 0.35f, getWidth() - 24f * density, getHeight() * 0.55f);
        canvas.drawRoundRect(rect, 18f * density, 18f * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * density);
        paint.setColor(Color.argb(180, 34, 211, 238));
        canvas.drawRoundRect(rect, 18f * density, 18f * density, paint);
        paint.setStyle(Paint.Style.FILL);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(28f * density);
        canvas.drawText(title, getWidth() * 0.5f, getHeight() * 0.43f, textPaint);
        textPaint.setTextSize(13f * density);
        textPaint.setColor(Color.parseColor("#BAE6FD"));
        canvas.drawText(subtitle, getWidth() * 0.5f, getHeight() * 0.49f, textPaint);
    }

    private void drawGameOverPanel(Canvas canvas) {
        paint.setColor(Color.argb(190, 5, 8, 22));
        rect.set(24f * density, getHeight() * 0.32f, getWidth() - 24f * density, getHeight() * 0.58f);
        canvas.drawRoundRect(rect, 18f * density, 18f * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * density);
        paint.setColor(beatBestThisRun ? Color.argb(220, 250, 204, 21) : Color.argb(180, 34, 211, 238));
        canvas.drawRoundRect(rect, 18f * density, 18f * density, paint);
        paint.setStyle(Paint.Style.FILL);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(Color.WHITE);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(27f * density);
        canvas.drawText(beatBestThisRun ? "新纪录" : "信号断裂", getWidth() * 0.5f, getHeight() * 0.405f, textPaint);
        textPaint.setTextSize(19f * density);
        canvas.drawText("本局分数 " + score, getWidth() * 0.5f, getHeight() * 0.47f, textPaint);
        textPaint.setFakeBoldText(false);
        textPaint.setTextSize(14f * density);
        textPaint.setColor(Color.parseColor("#BAE6FD"));
        canvas.drawText("最高分数 " + Math.max(bestScore, score) + "  |  点击重开",
                getWidth() * 0.5f, getHeight() * 0.525f, textPaint);
    }

    private void drawBitmapWithGlow(Canvas canvas, Bitmap bitmap, RectF dst, int color) {
        glowPaint.setColor(Color.argb(120, Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawOval(dst.left - 10f * density, dst.top - 6f * density, dst.right + 10f * density, dst.bottom + 6f * density, glowPaint);
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, dst, null);
        } else {
            paint.setColor(color);
            canvas.drawRoundRect(dst, 8f * density, 8f * density, paint);
        }
    }

    private void postFrame() {
        if (!framePosted) {
            framePosted = true;
            choreographer.postFrameCallback(frameCallback);
        }
    }

    private void loadBitmaps() {
        playerBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.player_motorcycle_v2);
        highwaySceneBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.gameplay_highway_scene);
        coneBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.obstacle_cone);
        tiresBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.obstacle_tires);
        barrierBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.obstacle_barrier);
        oilBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.obstacle_oil_v2);
        droneBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.drone_car);
        barrelBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.prop_barrel_blue);
        lightBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.prop_light_yellow);
        coinBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.item_coin);
        diamondBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.item_diamond);
    }

    private void changeLane(int delta) {
        player.lane = Math.max(0, Math.min(2, player.lane + delta));
        player.targetX = laneCenter(player.lane);
    }

    private void resetPlayerToRoad() {
        player.lane = 1;
        player.x = laneCenter(player.lane);
        player.targetX = player.x;
    }

    private void forceSkidLane() {
        int delta;
        if (player.lane == 0) {
            delta = 1;
        } else if (player.lane == 2) {
            delta = -1;
        } else {
            delta = random.nextBoolean() ? -1 : 1;
        }
        changeLane(delta);
    }

    private void jump() {
        long now = System.currentTimeMillis();
        if (!sliding(now)) {
            jumpUntil = now + JUMP_MS;
        }
    }

    private void slide() {
        long now = System.currentTimeMillis();
        if (!jumping(now)) {
            slideUntil = now + SLIDE_MS;
        }
    }

    private float laneCenter(int lane) {
        float bottomLeft = getWidth() == 0 ? roadLeft : getWidth() * 0.16f;
        float bottomRight = getWidth() == 0 ? roadRight : getWidth() * 0.84f;
        return bottomLeft + (bottomRight - bottomLeft) * (lane + 0.5f) / 3f;
    }

    private float playerY(long now) {
        return playerBaseY + (sliding(now) ? 20f * density : 0f);
    }

    private float jumpLift(long now) {
        if (!jumping(now)) {
            return 0f;
        }
        float elapsed = 1f - (jumpUntil - now) / (float) JUMP_MS;
        float arc = (float) Math.sin(Math.PI * Math.max(0f, Math.min(1f, elapsed)));
        return arc * 84f * density;
    }

    private boolean jumping(long now) {
        return now < jumpUntil;
    }

    private boolean sliding(long now) {
        return now < slideUntil;
    }

    private boolean shieldActive(long now) {
        return now < shieldUntil;
    }

    private boolean magnetActive(long now) {
        return now < magnetUntil;
    }

    private boolean boostActive(long now) {
        return now < boostUntil;
    }

    private float currentSpeed(long now) {
        float ramp = Math.min(MAX_SPEED - BASE_SPEED, distanceMeters * 1.05f);
        float speed = BASE_SPEED + ramp + (boostActive(now) ? 280f : 0f);
        if (now < slowUntil && !boostActive(now)) {
            speed *= 0.52f;
        }
        return speed;
    }

    private float nextObstacleDelay() {
        float base = 2.05f - (stage - 1) * 0.27f;
        return Math.max(0.62f, base + random.nextFloat() * 0.48f);
    }

    private void burst(float x, float y, int color, int count) {
        for (int i = 0; i < count; i++) {
            particles.add(Particle.spark(x, y, color, random));
        }
    }

    private float randomSigned(float range) {
        return (random.nextFloat() * 2f - 1f) * range * density;
    }

    private float randomHash(int seed) {
        int n = seed * 1103515245 + 12345;
        return ((n >>> 8) & 1023) / 1023f;
    }

    private float randomSignedSeed(int seed) {
        return randomHash(seed + 17) * 2f - 1f;
    }

    private float lerp(float start, float end, float amount) {
        return start + (end - start) * amount;
    }

    private void notifyState() {
        if (listener != null) {
            listener.onStateChanged(score, (int) distanceMeters, stage, lives, state);
        }
    }

    private class Player {
        int lane = 1;
        float x;
        float targetX;
    }

    private class Obstacle {
        final int type;
        final int lane;
        final float x;
        float y;
        float drift;
        float driftDirection = 1f;
        float speedScale = 1f;
        boolean hit;
        boolean scored;

        Obstacle(int type, int lane, float x, float y) {
            this.type = type;
            this.lane = lane;
            this.x = x;
            this.y = y;
        }

        RectF drawBox(RectF out) {
            float width = type == TYPE_LASER ? 116f * density : OBSTACLE_WIDTH * density;
            float height = type == TYPE_BARRIER ? 58f * density : OBSTACLE_HEIGHT * density;
            if (type == TYPE_BARRIER) {
                width = 138f * density;
                height = 78f * density;
            }
            if (type == TYPE_DRONE) {
                width = 72f * density;
                height = 132f * density;
            }
            out.set(x - width * 0.5f, y - height * 0.5f, x + width * 0.5f, y + height * 0.5f);
            return out;
        }

        RectF hitBox(RectF out) {
            drawBox(out);
            out.inset(10f * density, 10f * density);
            return out;
        }
    }

    private class Collectible {
        final int type;
        float x;
        float y;
        float spin;
        boolean collected;

        Collectible(int type, float x, float y) {
            this.type = type;
            this.x = x;
            this.y = y;
        }
    }

    private static class Particle {
        float x;
        float y;
        float vx;
        float vy;
        float age;
        float life;
        float radius;
        int color;

        static Particle spark(float x, float y, int color, Random random) {
            Particle p = new Particle();
            float angle = random.nextFloat() * (float) Math.PI * 2f;
            float speed = 90f + random.nextFloat() * 280f;
            p.x = x;
            p.y = y;
            p.vx = (float) Math.cos(angle) * speed;
            p.vy = (float) Math.sin(angle) * speed;
            p.life = 0.38f + random.nextFloat() * 0.34f;
            p.radius = 2.5f + random.nextFloat() * 4.5f;
            p.color = color;
            return p;
        }

        static Particle trail(float x, float y) {
            Particle p = new Particle();
            p.x = x;
            p.y = y;
            p.vx = 0f;
            p.vy = 120f;
            p.life = 0.26f;
            p.radius = 5f;
            p.color = Color.parseColor("#22D3EE");
            return p;
        }
    }
}
