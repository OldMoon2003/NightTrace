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
    private View startScreen;
    private View pauseMenu;
    private Button pauseIconButton;
    private SharedPreferences preferences;
    private int bestScore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        bestScore = preferences.getInt(KEY_BEST_SCORE, 0);

        stateView = findViewById(R.id.tv_state);
        startScreen = findViewById(R.id.start_screen);
        pauseMenu = findViewById(R.id.pause_menu);
        pauseIconButton = findViewById(R.id.btn_pause_icon);
        nightTraceView = findViewById(R.id.night_trace_view);

        nightTraceView.setFocusableInTouchMode(true);
        nightTraceView.requestFocus();
        nightTraceView.setOnGameStateChangeListener(this);

        Button heroStartButton = findViewById(R.id.btn_start_hero);
        Button resumeButton = findViewById(R.id.btn_resume);
        Button restartButton = findViewById(R.id.btn_restart_menu);
        Button homeButton = findViewById(R.id.btn_home);
        pauseIconButton.setOnClickListener(v -> nightTraceView.togglePause());
        heroStartButton.setOnClickListener(v -> startGame());
        resumeButton.setOnClickListener(v -> nightTraceView.togglePause());
        restartButton.setOnClickListener(v -> startGame());
        homeButton.setOnClickListener(v -> returnHome());

        onStateChanged(0, 0, 1, 3, NightTraceView.GameState.READY);
    }

    private void startGame() {
        startScreen.setVisibility(View.GONE);
        pauseMenu.setVisibility(View.GONE);
        pauseIconButton.setVisibility(View.VISIBLE);
        nightTraceView.startNewGame();
        nightTraceView.requestFocus();
    }

    private void returnHome() {
        pauseMenu.setVisibility(View.GONE);
        pauseIconButton.setVisibility(View.GONE);
        nightTraceView.returnToReady();
        startScreen.setVisibility(View.VISIBLE);
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
        }
        nightTraceView.setBestScore(bestScore, newRecord);

        stateView.setText("阶段 " + stage);
        startScreen.setVisibility(state == NightTraceView.GameState.READY ? View.VISIBLE : View.GONE);
        pauseIconButton.setVisibility(state == NightTraceView.GameState.RUNNING ? View.VISIBLE : View.GONE);
        pauseMenu.setVisibility(state == NightTraceView.GameState.PAUSED ? View.VISIBLE : View.GONE);
    }
}
