package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private TextView greeting;
    private TextView banner;
    private boolean ambientShown;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Arranging mode: adapter position of the tile travelling with the arrows, or -1. */
    private int movingPos = -1;
    /** Bumped on every step and on exit, so a pending highlight re-glue from an older step is ignored. */
    private int moveGen;
    /** When the last arrangement was saved, so a held OK does not reopen the menu right after. */
    private long moveConfirmedAt;
    /** The open glass panel (tile menu or hidden apps), so a row click can close it. */
    private PopupWindow panelPopup;
    /** When the open panel appeared; clicks arriving right after are remote bounces, not choices. */
    private long panelShownAt;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            handler.postDelayed(this, MINUTE_MS);
        }
    };
    private final Runnable dismissBanner = () -> banner.animate().alpha(0f).setDuration(220).start();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        greeting = findViewById(R.id.greeting);
        banner = findViewById(R.id.banner);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = (int) ((145 + 6 + 12) * density);   // item height plus DockDecoration offsets
            int want = Math.max(0, h - dockRow - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        }

        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnSettings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        // OK held on Settings: the hidden-apps list, to bring one back.
        btnSettings.setOnLongClickListener(v -> {
            showHiddenApps();
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnInputs) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        ambient.setAlpha(0f);
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        movingPos = -1;
        moveGen++;
        adapter.reload();
        grid.scheduleLayoutAnimation();
        updateGreeting();
        handler.removeCallbacks(dismissBanner);
        banner.setAlpha(0f);
        handler.postDelayed(minuteTick, MINUTE_MS);

        // Return to the app we left from, or the same slot if that app is gone. Always refocus: after
        // reload() the view that holds focus may no longer show the app it had.
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(minuteTick);
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Arranging mode owns the D-pad: arrows slide the tile, OK saves, Back puts it back.
        if (movingPos >= 0 && event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return moveStep(-1);
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return moveStep(1);
                case KeyEvent.KEYCODE_DPAD_UP:
                    return moveStep(-COLUMNS);
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return moveStep(COLUMNS);
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    confirmMove();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    cancelMove();
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(16f * density * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        View item = (View) card.getParent();
        if (position < adapter.dockCount()) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            showHero(position, r.top - dy);
        } else {
            // Grid: no top shelf, but the backdrop takes the app's colour just like the dock.
            hideHeroText();
            showAmbient(adapter.ambientColorAt(position));
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());
        showAmbient(adapter.ambientColorAt(position));

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
        } else {
            heroName.setY(y);
        }
    }

    /** Backdrop glow in the app's colour: dock tiles and grid tiles alike (the top shelf text is dock-only). */
    private void showAmbient(int color) {
        if (!ambientShown) {
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            ambientShown = true;
        } else if (color != ambientColor) {
            ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                setAmbient(color);
                ambient.animate().alpha(1f).setDuration(500).start();
            }).start();
        }
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    private void hideHeroText() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- tile menu: hide, move, app details ------------------------------------------------------

    @Override
    public void onTileLongPress(int position) {
        // Already arranging (OK is the confirm key then), or a held OK right after saving.
        if (movingPos >= 0 || SystemClock.uptimeMillis() - moveConfirmedAt < 800) return;
        final String pkg = adapter.pkgAt(position);
        if (pkg == null) return;
        final String label = adapter.labelAt(position);
        View panelView = getLayoutInflater().inflate(R.layout.popup_menu, root, false);
        ((TextView) panelView.findViewById(R.id.header)).setText(label);
        LinearLayout rows = panelView.findViewById(R.id.rows);

        addMenuRow(rows, R.drawable.ic_hide, getString(R.string.hide), () -> {
            adapter.hidePackage(pkg);
            showBanner(getString(R.string.hidden_toast, label), 5000);
            grid.post(() -> focusTile(Math.min(position, adapter.getItemCount() - 1)));
        });
        boolean inDock = position < adapter.dockCount();
        if (inDock ? adapter.dockCount() > 1 : adapter.getItemCount() - adapter.dockCount() > 1) {
            addMenuRow(rows, R.drawable.ic_move, getString(R.string.move), () -> startMove(position));
        }
        addMenuRow(rows, R.drawable.ic_info, getString(R.string.app_details), () -> openAppDetails(pkg));

        View card = cardAt(position);
        showPanel(panelView, rows, card != null ? card : grid, card);
    }

    private View cardAt(int position) {
        RecyclerView.ViewHolder vh = grid.findViewHolderForItemId(adapter.getItemId(position));
        if (vh == null) vh = grid.findViewHolderForAdapterPosition(position);
        return vh != null ? vh.itemView.findViewById(R.id.card) : null;
    }

    private void addMenuRow(LinearLayout rows, int iconRes, String title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_row, rows, false);
        ImageView icon = row.findViewById(R.id.icon);
        icon.setImageResource(iconRes);
        icon.setImageTintList(getColorStateList(R.color.panel_icon_tint));
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setTag(action);
        rows.addView(row);
    }

    private void addAppRow(LinearLayout rows, Drawable icon, String title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_row, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageDrawable(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView trailing = row.findViewById(R.id.trailing);
        trailing.setText(R.string.restore);
        trailing.setVisibility(View.VISIBLE);
        row.setTag(action);
        rows.addView(row);
    }

    /** One of the launcher's glass panels, dropped from its anchor; rows act on OK, Back closes. */
    private void showPanel(View content, LinearLayout rows, View anchor, View returnFocus) {
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            final Runnable action = (Runnable) row.getTag();
            // The OK press that opened this panel can leak a click when it is released: swallow a
            // key-up whose press started before the panel appeared. A fresh press selects normally.
            row.setOnKeyListener((v, keyCode, event) ->
                    event.getAction() == KeyEvent.ACTION_UP && event.getDownTime() < panelShownAt);
            row.setOnClickListener(v -> {
                if (panelPopup != null) panelPopup.dismiss();
                action.run();
            });
        }
        content.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int width = Math.min(Math.max(content.getMeasuredWidth(), (int) (260 * density)), (int) (480 * density));
        int height = Math.min(content.getMeasuredHeight(), (int) (430 * density));
        panelPopup = new PopupWindow(content, width, height, true);
        panelPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        panelPopup.setOutsideTouchable(true);
        panelPopup.setAnimationStyle(0);
        panelPopup.setElevation(0f);
        panelShownAt = SystemClock.uptimeMillis();
        panelPopup.setOnDismissListener(() -> {
            panelPopup = null;
            if (returnFocus != null) returnFocus.requestFocus();
        });
        content.setAlpha(0f);
        content.setPivotY(0f);
        content.setScaleY(0.94f);
        content.setTranslationY(-8 * density);
        panelPopup.showAsDropDown(anchor, 0, (int) (10 * density));
        content.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void openAppDetails(String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    // ---- arranging: the tile travels with the arrows ---------------------------------------------

    /** The tile travels with the arrows; OK keeps the order, Back restores it. */
    private void startMove(int position) {
        movingPos = position;
        showBanner(getString(R.string.move_hint), 0);   // stays up until the arrangement ends
    }

    private boolean moveStep(int delta) {
        int target = movingPos + delta;
        int dock = adapter.dockCount();
        boolean inDock = movingPos < dock;
        if (target >= (inDock ? 0 : dock) && target < (inDock ? dock : adapter.getItemCount())
                && adapter.move(movingPos, target)) {
            movingPos = target;
            glueHighlight();
        }
        return true;   // the key belongs to arranging, never to focus navigation
    }

    /**
     * The focused tile keeps its focus while it moves, so no focus-change event fires and the glass
     * highlight would stay where the tile used to be. Re-glue it to the tile: once now (in case the
     * view lost focus in the move) and once after the grid has laid out its new positions.
     */
    private void glueHighlight() {
        final int pos = movingPos;
        final int gen = ++moveGen;
        grid.post(() -> {
            if (gen == moveGen) focusTile(pos);
        });
        grid.postDelayed(() -> {
            if (gen == moveGen) focusTile(pos);
        }, 90);
    }

    private void confirmMove() {
        adapter.persistOrder();
        movingPos = -1;
        moveGen++;
        moveConfirmedAt = SystemClock.uptimeMillis();
        showBanner(getString(R.string.move_saved), 2400);
    }

    private void cancelMove() {
        String key = adapter.keyAt(movingPos);
        movingPos = -1;
        moveGen++;
        adapter.reload();
        showBanner(getString(R.string.move_cancelled), 2400);
        grid.post(() -> {
            int pos = adapter.positionOf(key);
            focusTile(pos >= 0 ? pos : 0);
        });
    }

    // ---- hidden apps: bring one back -------------------------------------------------------------

    /** OK held on the Settings pill: the apps the launcher is not showing. */
    private void showHiddenApps() {
        List<AppAdapter.Entry> hiddenApps = adapter.hiddenEntries();
        if (hiddenApps.isEmpty()) {
            showBanner(getString(R.string.hidden_apps_empty), 2400);
            return;
        }
        View panelView = getLayoutInflater().inflate(R.layout.popup_menu, root, false);
        ((TextView) panelView.findViewById(R.id.header)).setText(getString(R.string.hidden_apps_header));
        LinearLayout rows = panelView.findViewById(R.id.rows);
        for (AppAdapter.Entry e : hiddenApps) {
            addAppRow(rows, e.banner != null ? e.banner : e.icon, e.label, () -> {
                adapter.restorePackage(e.pkg);
                showBanner(getString(R.string.restored_toast, e.label), 2400);
            });
        }
        View settings = findViewById(R.id.btnSettings);
        showPanel(panelView, rows, settings, settings);
    }

    // ---- status banner (instead of toasts) -------------------------------------------------------

    /** A glass line under the top bar: arranging hint, order saved/restored, hidden/restored app. */
    private void showBanner(CharSequence text, long stayMs) {
        banner.setText(text);
        handler.removeCallbacks(dismissBanner);
        banner.animate().cancel();
        banner.animate().alpha(1f).setDuration(180).start();
        if (stayMs > 0) handler.postDelayed(dismissBanner, stayMs);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (tunerId == null || info.getId().compareTo(tunerId) < 0)) {
                    tunerId = info.getId();
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        // Drop in: fade and unfold from the top edge.
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), Gravity.START);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
