package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * App tiles. The first positions are the dock (the user's own order first, then the config list),
 * the grid after it: the arrangement the user saved by hand (long-press → Move) first, untouched
 * apps after it, alphabetical. Hidden apps (config list, or the user's own hides) are left out;
 * the restore panel lists them all.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);

        /** Called when OK is held on a tile: hide / move / app details menu. */
        void onTileLongPress(int position);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Square artwork used as a banner (e.g. SportzX): show all of it instead of cropping it. */
        final boolean fitBanner;
        /** Edge colour of a fitted banner, so the tile's side bars blend with the artwork. */
        final int bannerFill;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon, boolean fitBanner) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.fitBanner = fitBanner;
            this.bannerFill = fitBanner ? edgeColor(banner) : 0;
        }
    }

    static final float FOCUS_SCALE = 1.10f;

    private static final String PREFS = "tvhome";
    private static final String PREF_HIDDEN = "hidden";
    private static final String PREF_SHOWN = "shown";
    private static final String PREF_ORDER = "order";
    private static final String PREF_DOCK_ORDER = "dock_order";

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (res/values/config.xml, dock_apps). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();
    /** Every launchable app, hidden ones included: what the restore dialog lists. */
    private final List<Entry> all = new ArrayList<>();
    /** Apps the user hid (tile menu → Hide); wins over the config list. */
    private final Set<String> userHidden = new HashSet<>();
    /** Apps the user brought back that the config list hides. */
    private final Set<String> userShown = new HashSet<>();
    /** Grid arrangement the user saved by hand; apps never moved come after it, alphabetical. */
    private final List<String> order = new ArrayList<>();
    /** Dock arrangement the user saved by hand; falls back to the config order for the rest. */
    private final List<String> dockOrder = new ArrayList<>();
    /** Items in the dock row (the present favourites); the grid starts at this index. */
    private int dockCount;

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps));
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    void reload() {
        SharedPreferences sp = prefs();
        userHidden.clear();
        userHidden.addAll(sp.getStringSet(PREF_HIDDEN, Collections.emptySet()));
        userShown.clear();
        userShown.addAll(sp.getStringSet(PREF_SHOWN, Collections.emptySet()));
        order.clear();
        for (String s : sp.getString(PREF_ORDER, "").split("\n")) {
            if (!s.isEmpty()) order.add(s);
        }
        dockOrder.clear();
        for (String s : sp.getString(PREF_DOCK_ORDER, "").split("\n")) {
            if (!s.isEmpty()) dockOrder.add(s);
        }

        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<Entry> fresh = new ArrayList<>();
        all.clear();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName())) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            boolean fitBanner = banner != null && bannerNeedsFit(banner);
            Drawable icon = ri.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            Entry e = new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon, fitBanner);
            all.add(e);
            boolean visible = !hidden.contains(pkg) || userShown.contains(pkg);   // config hides, undone
            if (!visible || userHidden.contains(pkg)) continue;
            fresh.add(e);
        }
        Collections.sort(fresh, (a, b) -> {
            int fa = dockRank(a.pkg), fb = dockRank(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            int oa = order.indexOf(a.pkg), ob = order.indexOf(b.pkg);
            if (oa >= 0 || ob >= 0) {
                if (oa < 0) return 1;
                if (ob < 0) return -1;
                return Integer.compare(oa, ob);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        items.clear();
        items.addAll(fresh);
        dockCount = 0;
        for (Entry e : items) {
            if (favourites.indexOf(e.pkg) < 0) break;
            dockCount++;
        }
        notifyDataSetChanged();
    }

    /** Position in the dock: the user's own arrangement first, the config list after it. */
    private int dockRank(String pkg) {
        int i = dockOrder.indexOf(pkg);
        if (i >= 0) return i;
        int j = favourites.indexOf(pkg);
        return j < 0 ? -1 : dockOrder.size() + j;
    }

    /**
     * Square artwork used as a banner (adaptive icons, e.g. SportzX) would fill a 16:9 tile as one
     * huge crop; fit it inside the tile instead, so the whole logo is visible.
     */
    private static boolean bannerNeedsFit(Drawable d) {
        if (d instanceof AdaptiveIconDrawable) return true;
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        return w > 0 && h > 0 && w < h * 1.4f;
    }

    /**
     * Colour along the edges of a fitted banner: the tile's side bars then blend into the artwork
     * instead of showing as borders (SportzX: white edges → a full white tile, logo centred).
     */
    private static int edgeColor(Drawable live) {
        if (live == null) return 0;
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 24;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        long a = 0, r = 0, g = 0, b = 0;
        int count = 0;
        for (int y : new int[]{1, n - 2}) {
            for (int x : new int[]{1, n - 2}) {
                int p = bmp.getPixel(x, y);
                a += Color.alpha(p);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                count++;
            }
        }
        bmp.recycle();
        if (a / count < 200) return 0;   // see-through edges: keep the tile background
        return Color.rgb((int) (r / count), (int) (g / count), (int) (b / count));
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.label);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        if (e.banner != null) {
            h.banner.setScaleType(e.fitBanner ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
            h.banner.setBackgroundColor(e.bannerFill);
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return false;
            listener.onTileLongPress(pos);
            return true;
        });
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    String pkgAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).pkg : null;
    }

    /** Items in the dock row: the grid starts at this index. */
    int dockCount() {
        return dockCount;
    }

    /** Moves a tile inside its own section (dock or grid); true when something moved. */
    boolean move(int from, int to) {
        if (from == to || from < 0 || to < 0) return false;
        if (from >= items.size() || to >= items.size()) return false;
        if ((from < dockCount) != (to < dockCount)) return false;   // never across dock and grid
        items.add(to, items.remove(from));
        notifyItemMoved(from, to);
        return true;
    }

    /** Saves the current arrangement of both the dock and the grid as the user's own order. */
    void persistOrder() {
        StringBuilder dock = new StringBuilder();
        StringBuilder grid = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            StringBuilder sb = i < dockCount ? dock : grid;
            if (sb.length() > 0) sb.append('\n');
            sb.append(items.get(i).pkg);
        }
        prefs().edit().putString(PREF_DOCK_ORDER, dock.toString())
                .putString(PREF_ORDER, grid.toString()).apply();
    }

    void hidePackage(String pkg) {
        SharedPreferences sp = prefs();
        Set<String> h = new HashSet<>(sp.getStringSet(PREF_HIDDEN, Collections.emptySet()));
        h.add(pkg);
        Set<String> shown = new HashSet<>(sp.getStringSet(PREF_SHOWN, Collections.emptySet()));
        shown.remove(pkg);   // hiding beats an earlier "bring back"
        sp.edit().putStringSet(PREF_HIDDEN, h).putStringSet(PREF_SHOWN, shown).apply();
        reload();
    }

    void restorePackage(String pkg) {
        SharedPreferences sp = prefs();
        Set<String> h = new HashSet<>(sp.getStringSet(PREF_HIDDEN, Collections.emptySet()));
        h.remove(pkg);
        Set<String> shown = new HashSet<>(sp.getStringSet(PREF_SHOWN, Collections.emptySet()));
        shown.add(pkg);      // beats the config list too (an app the launcher ships hidden)
        sp.edit().putStringSet(PREF_HIDDEN, h).putStringSet(PREF_SHOWN, shown).apply();
        reload();
    }

    /** Launchable apps currently not shown, for the restore dialog. */
    List<Entry> hiddenEntries() {
        Set<String> visible = new HashSet<>();
        for (Entry e : items) visible.add(e.pkg);
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (!visible.contains(e.pkg)) out.add(e);
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
