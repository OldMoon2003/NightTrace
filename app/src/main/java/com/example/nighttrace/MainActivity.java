package com.example.nighttrace;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity implements NightTraceView.OnGameStateChangeListener {

    private static final String PREFS_NAME = "night_trace";
    private static final String KEY_BEST_SCORE = "best_score";

    private NightTraceView nightTraceView;
    private TextView stateView;
    private TextView startBestView;
    private TextView countdownView;
    private View startScreen;
    private View startTitleGroup;
    private View startControls;
    private View startTransition;
    private View transitionSweep;
    private View transitionLabel;
    private View pauseMenu;
    private Button heroStartButton;
    private Button pauseIconButton;
    private SharedPreferences preferences;
    private int bestScore;
    private boolean startingGame;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        bestScore = preferences.getInt(KEY_BEST_SCORE, 0);

        stateView = findViewById(R.id.tv_state);
        startBestView = findViewById(R.id.tv_start_best);
        countdownView = findViewById(R.id.tv_countdown);
        startScreen = findViewById(R.id.start_screen);
        startTitleGroup = findViewById(R.id.start_title_group);
        startControls = findViewById(R.id.start_controls);
        startTransition = findViewById(R.id.start_transition);
        transitionSweep = findViewById(R.id.transition_sweep);
        transitionLabel = findViewById(R.id.transition_label);
        pauseMenu = findViewById(R.id.pause_menu);
        pauseIconButton = findViewById(R.id.btn_pause_icon);
        nightTraceView = findViewById(R.id.night_trace_view);

        nightTraceView.setFocusableInTouchMode(true);
        nightTraceView.requestFocus();
        nightTraceView.setOnGameStateChangeListener(this);

        heroStartButton = findViewById(R.id.btn_start_hero);
        Button resumeButton = findViewById(R.id.btn_resume);
        Button restartButton = findViewById(R.id.btn_restart_menu);
        Button homeButton = findViewById(R.id.btn_home);
        pauseIconButton.setOnClickListener(v -> nightTraceView.togglePause());
        heroStartButton.setOnClickListener(v -> startGame());
        resumeButton.setOnClickListener(v -> nightTraceView.togglePause());
        restartButton.setOnClickListener(v -> startGame());
        homeButton.setOnClickListener(v -> returnHome());

        updateStartBestScore();
        animateStartScreenIn();
        onStateChanged(0, 0, 1, 3, NightTraceView.GameState.READY);
    }

    private void startGame() {
        if (startingGame) {
            return;
        }
        startingGame = true;
        pauseMenu.setVisibility(View.GONE);
        heroStartButton.setEnabled(false);

        startTitleGroup.animate()
                .alpha(0f)
                .translationY(-28f * getResources().getDisplayMetrics().density)
                .setStartDelay(0L)
                .setDuration(250L)
                .start();
        startControls.animate()
                .alpha(0f)
                .translationY(34f * getResources().getDisplayMetrics().density)
                .setStartDelay(0L)
                .setDuration(250L)
                .start();

        startScreen.setVisibility(View.GONE);
        nightTraceView.prepareNewGame();
        nightTraceView.requestFocus();
        runCountdown(3);
    }

    private void runCountdown(int count) {
        if (!startingGame) {
            countdownView.setVisibility(View.GONE);
            heroStartButton.setEnabled(true);
            return;
        }
        if (count < 1) {
            countdownView.setVisibility(View.GONE);
            showLaunchTransition();
            return;
        }

        countdownView.setText(String.valueOf(count));
        countdownView.setAlpha(0f);
        countdownView.setScaleX(1.34f);
        countdownView.setScaleY(1.34f);
        countdownView.setVisibility(View.VISIBLE);
        countdownView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(0L)
                .setDuration(220L)
                .withEndAction(() -> countdownView.animate()
                        .alpha(0.18f)
                        .scaleX(0.88f)
                        .scaleY(0.88f)
                        .setStartDelay(330L)
                        .setDuration(180L)
                        .withEndAction(() -> runCountdown(count - 1))
                        .start())
                .start();
    }

    private void showLaunchTransition() {
        transitionLabel.setAlpha(0f);
        transitionLabel.setScaleX(0.92f);
        transitionLabel.setScaleY(0.92f);
        transitionSweep.setTranslationY(-getResources().getDisplayMetrics().heightPixels * 0.42f);
        startTransition.setAlpha(0f);
        startTransition.setVisibility(View.VISIBLE);
        startTransition.animate()
                .alpha(1f)
                .setStartDelay(0L)
                .setDuration(210L)
                .withStartAction(() -> {
                    transitionLabel.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(180L)
                            .start();
                    transitionSweep.animate()
                            .translationY(getResources().getDisplayMetrics().heightPixels * 0.35f)
                            .setDuration(380L)
                            .start();
                })
                .withEndAction(() -> {
                    nightTraceView.launchPreparedGame();
                    nightTraceView.requestFocus();
                    startTransition.animate()
                            .alpha(0f)
                            .setStartDelay(0L)
                            .setDuration(220L)
                            .withEndAction(() -> {
                                startTransition.setVisibility(View.GONE);
                                startingGame = false;
                                heroStartButton.setEnabled(true);
                            })
                            .start();
                })
                .start();
    }

    private void returnHome() {
        startingGame = false;
        heroStartButton.setEnabled(true);
        pauseMenu.setVisibility(View.GONE);
        pauseIconButton.setVisibility(View.GONE);
        startTransition.setVisibility(View.GONE);
        countdownView.setVisibility(View.GONE);
        nightTraceView.returnToReady();
        startScreen.setVisibility(View.VISIBLE);
        animateStartScreenIn();
    }

    private void animateStartScreenIn() {
        float density = getResources().getDisplayMetrics().density;
        startTitleGroup.setAlpha(0f);
        startTitleGroup.setTranslationY(-22f * density);
        startControls.setAlpha(0f);
        startControls.setTranslationY(30f * density);
        startTitleGroup.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(520L)
                .start();
        startControls.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(120L)
                .setDuration(520L)
                .start();
    }

    private void updateStartBestScore() {
        startBestView.setText("最高分 " + bestScore);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && nightTraceView.handleKeyDown(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        nightTraceView.pauseIfRunning();
    }

    @Override
    public void onStateChanged(int score, int distanceMeters, int stage, int lives, NightTraceView.GameState state) {
        boolean newRecord = state == NightTraceView.GameState.GAME_OVER && score > bestScore;
        if (newRecord) {
            bestScore = score;
            preferences.edit().putInt(KEY_BEST_SCORE, bestScore).apply();
            updateStartBestScore();
        }
        nightTraceView.setBestScore(bestScore, newRecord);

        stateView.setText("阶段 " + stage);
        startScreen.setVisibility(state == NightTraceView.GameState.READY ? View.VISIBLE : View.GONE);
        pauseIconButton.setVisibility(state == NightTraceView.GameState.RUNNING ? View.VISIBLE : View.GONE);
        pauseMenu.setVisibility(state == NightTraceView.GameState.PAUSED ? View.VISIBLE : View.GONE);
    }
}
