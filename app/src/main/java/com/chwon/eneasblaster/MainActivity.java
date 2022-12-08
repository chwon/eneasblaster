package com.chwon.eneasblaster;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.DialogFragment;

import com.spotify.protocol.client.CallResult;
import com.spotify.protocol.client.Subscription;
import com.spotify.protocol.types.Image;
import com.spotify.protocol.types.PlayerState;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity implements Refreshable {

    private final String HELPPAGE_URI = "https://chwon.github.io/eneasblaster/docs/help.html";

    private SpotifyConnectorService mBoundConnectorService;
    private boolean mShouldUnbind;

    private String[][] currentList;
    private boolean trackDeleteMode = false;
    private String currentTrackUri = "";

    private MenuItem togglePlaylistMenuItem;
    private MenuItem choosePlaylistMenuItem;
    private MenuItem exportPlaylistMenuItem;
    private MenuItem addTrackMenuItem;
    private MenuItem deleteTrackMenuItem;
    private MenuItem pinTrackMenuItem;
    private MenuItem playPinnedTrackMenuItem;
    private MenuItem playRandomTrackMenuItem;
    private MenuItem continuousPlayMenuItem;
    private MenuItem randomPlayMenuItem;
    private MenuItem switchAppMenuItem;
    private MenuItem switchLocalMenuItem;
    private MenuItem helpMenuItem;

    private Long seekToPosition = 0L;
    private Long lastTrackChangeTimestamp = 0L;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            mBoundConnectorService = ((SpotifyConnectorService.LocalBinder) iBinder).getService();
            Subscription.EventCallback<PlayerState> cb = new Subscription.EventCallback<PlayerState>() {
                @Override
                public void onEvent(PlayerState data) {
                    String prefix = "";
                    if (data.isPaused) {
                        prefix = "\u23F8 ";
                    } else {
                        prefix = "\u25B6 ";
                    }
                    getSupportActionBar().setSubtitle(prefix + data.track.name);
                    mBoundConnectorService.getSpotifyAppRemote().getImagesApi().getImage(data.track.imageUri, Image.Dimension.LARGE)
                            .setResultCallback(new CallResult.ResultCallback<Bitmap>() {
                                @Override
                                public void onResult(Bitmap bitmapdata) {
                                    BitmapDrawable bitmapDrawable = new BitmapDrawable(getResources(), bitmapdata);
                                    //ImageView overlay = (ImageView)findViewById(R.id.overlayframe);
                                    ImageView backgroundImg = (ImageView) findViewById(R.id.ImageViewBackground);
                                    if (data.isPaused) {
                                        backgroundImg.setImageDrawable(getDrawable(R.drawable.eb_background_signal));
                                    } else {
                                        backgroundImg.setImageDrawable(bitmapDrawable);
                                        backgroundImg.setAlpha(0.67f);
                                    }
                                }
                            });
                    if (currentTrackUri != "") {
                        if ((!data.track.uri.equals(currentTrackUri)) &
                                ((lastTrackChangeTimestamp + 1000) < System.currentTimeMillis()) &
                                // Autoplay only kicks in if a track was started via EB in the last 15 min
                                ((lastTrackChangeTimestamp + 900000) > System.currentTimeMillis())) {
                            Log.d("Player State", "New track started.");
                            if (continuousPlayMenuItem.isChecked() | randomPlayMenuItem.isChecked()) {
                                String nextTrackUri = getNextTrackUri();
                                playTrack(nextTrackUri);
                            }
                        }
                    }
                    mBoundConnectorService.updateNotification(prefix + " " + data.track.name);
                }
            };
            mBoundConnectorService.subscribeToPlayerState(cb);
            refreshUI();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBoundConnectorService = null;
        }
    };

    ActivityResultLauncher<Intent> seekToPositionAfterSwitchLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    mBoundConnectorService.getSpotifyAppRemote().getPlayerApi().seekTo(seekToPosition);
                }
            }
    );

    ActivityResultLauncher<Intent> processSelectedFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @SuppressLint("WrongConstant")
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Intent data = result.getData();
                        if (data != null) {
                            Uri selectedFile = data.getData();
                            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                            SharedPreferences.Editor editor = preferences.edit();
                            editor.putString("blastfile", selectedFile.toString());
                            editor.apply();
                            final int takeFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                            getContentResolver().takePersistableUriPermission(selectedFile, takeFlags);
                            refreshUI();
                        } else {
                            Toast.makeText(getApplicationContext(),
                                    "No valid file selected.", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });

    void doBindService() {
        if (bindService(new Intent(MainActivity.this, SpotifyConnectorService.class),
                mConnection, getApplicationContext().BIND_AUTO_CREATE)) {
            mShouldUnbind = true;
        } else {
            Log.e("MY_APP_TAG", "Error: The requested service doesn't " +
                    "exist, or this client isn't allowed access to it.");
        }
    }

    void doUnbindService() {
        if (mShouldUnbind) {
            unbindService(mConnection);
            mShouldUnbind = false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Drawable dr = ResourcesCompat.getDrawable(getResources(), R.mipmap.ic_eneasblaster4, null);

        // Convert to bmp and resize
        TypedValue tv = new TypedValue();
        int actionBarHeight = 0;
        if (this.getTheme().resolveAttribute(androidx.appcompat.R.attr.actionBarSize, tv, true)) {
            actionBarHeight = TypedValue.complexToDimensionPixelSize(tv.data, getResources().getDisplayMetrics());
        }
        int iconSize = (int) (actionBarHeight * .9);

        Bitmap bmp = Bitmap.createBitmap(dr.getIntrinsicWidth(), dr.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        dr.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        dr.draw(canvas);
        Drawable d = new BitmapDrawable(getResources(), Bitmap.createScaledBitmap(bmp, iconSize, iconSize, true));

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeAsUpIndicator(d);

        Intent intent = getIntent();
        String type = intent.getType();
        if (Intent.ACTION_SEND.equals(intent.getAction()) && type != null && "text/plain".equals(type)) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null) {
                String trackId = sharedText.substring(sharedText.indexOf("/track/") + 7, sharedText.indexOf("?"));
                resolveAndAddSharedTrack(trackId);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Starting service to run permanently, even beyond app termination
        if (!connectorForegroundServiceRunning()) {
            startForegroundService(new Intent(this, SpotifyConnectorService.class));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        doUnbindService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        doBindService();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.options_menu, menu);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            menu.setGroupDividerEnabled(true);
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        togglePlaylistMenuItem = menu.findItem(R.id.toggleplaylist);
        Boolean toggleList = preferences.getBoolean("togglelist", true);
        togglePlaylistMenuItem.setChecked(toggleList);
        if (!externalListDefined()) {
            togglePlaylistMenuItem.setEnabled(false);
        }

        choosePlaylistMenuItem = menu.findItem(R.id.choosefile);
        exportPlaylistMenuItem = menu.findItem(R.id.exportplaylist);
        addTrackMenuItem = menu.findItem(R.id.addtrack);
        deleteTrackMenuItem = menu.findItem((R.id.deletetrack));
        pinTrackMenuItem = menu.findItem(R.id.pintrack);

        playPinnedTrackMenuItem = menu.findItem(R.id.playpinnedtrack);
        String pTrackUri = preferences.getString("pinnedtrack", "");
        if (!pTrackUri.equals("")) {
            playPinnedTrackMenuItem.setEnabled(true);
        } else {
            playPinnedTrackMenuItem.setEnabled(false);
        }

        playRandomTrackMenuItem = menu.findItem(R.id.randomtrack);

        continuousPlayMenuItem = menu.findItem(R.id.continuousplay);
        boolean continuousplay = preferences.getBoolean("continuousplay", false);
        continuousPlayMenuItem.setChecked(continuousplay);

        randomPlayMenuItem = menu.findItem(R.id.randomplay);
        boolean randomplay = preferences.getBoolean("randomplay", false);
        randomPlayMenuItem.setChecked(randomplay);

        switchAppMenuItem = menu.findItem(R.id.switchapp);
        Boolean switchApp = preferences.getBoolean("switchapp", false);
        switchAppMenuItem.setChecked(switchApp);

        switchLocalMenuItem = menu.findItem(R.id.switchlocal);
        helpMenuItem = menu.findItem(R.id.help);

        // Apparently, the first call to refreshUI() happens before this method has completed,
        // leading to non-initialized values (e.g. for menu items identifiers). Calling it again
        // here to have a consistent configuration at startup.
        refreshUI();

        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        SharedPreferences.Editor editor = preferences.edit();
        switch (item.getItemId()) {
            case android.R.id.home:
                if (mBoundConnectorService.getSpotifyAppRemote() != null) {
                    CallResult<PlayerState> playerState = mBoundConnectorService.getSpotifyAppRemote().getPlayerApi().getPlayerState();
                    playerState.setResultCallback(new CallResult.ResultCallback<PlayerState>() {
                        @Override
                        public void onResult(PlayerState data) {
                            if (data.isPaused) {
                                mBoundConnectorService.getSpotifyAppRemote().getPlayerApi().resume();
                            } else {
                                mBoundConnectorService.getSpotifyAppRemote().getPlayerApi().pause();
                            }
                        }
                    });
                } else {
                    Toast.makeText(getApplicationContext(),
                            "Toggle playback: Spotify is not connected.", Toast.LENGTH_SHORT).show();
                }
                return true;
            case R.id.toggleplaylist:
                item.setChecked(!item.isChecked());
                editor.putBoolean("togglelist", item.isChecked());
                editor.apply();

                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
                item.setActionView(new View(getApplicationContext()));
                item.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                    @Override
                    public boolean onMenuItemActionExpand(MenuItem item) {
                        return false;
                    }

                    @Override
                    public boolean onMenuItemActionCollapse(MenuItem item) {
                        return false;
                    }
                });
                refreshUI();
                return false;
            case R.id.choosefile:
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                processSelectedFileLauncher.launch(intent);
                return true;
            case R.id.addtrack:
                Toast.makeText(getApplicationContext(),
                        "To add a track, use Spotify's share function.", Toast.LENGTH_LONG).show();
                return true;
            case R.id.deletetrack:
                setTrackDeleteMode(!trackDeleteMode);
                refreshUI();
                return true;
            case R.id.pintrack:
                if (mBoundConnectorService.getSpotifyAppRemote() != null) {
                    CallResult<PlayerState> playerState = mBoundConnectorService.getSpotifyAppRemote().getPlayerApi().getPlayerState();
                    playerState.setResultCallback(new CallResult.ResultCallback<PlayerState>() {
                        @Override
                        public void onResult(PlayerState data) {
                            editor.putString("pinnedtrack", data.track.uri);
                            editor.putLong("pinnedPlaybackPosition", data.playbackPosition);
                            editor.apply();
                            if (playPinnedTrackMenuItem != null) {
                                playPinnedTrackMenuItem.setEnabled(true);
                            }
                            Toast.makeText(getApplicationContext(),
                                    "Pinned " + data.track.name, Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    Toast.makeText(getApplicationContext(),
                            "Pin track: Spotify is not connected.", Toast.LENGTH_SHORT).show();
                }
                return true;
            case R.id.playpinnedtrack:
                if (mBoundConnectorService.getSpotifyAppRemote() != null) {
                    String pTrackUri = preferences.getString("pinnedtrack", "");
                    Long pPlaybackPosition = preferences.getLong("pinnedPlaybackPosition", 0);
                    if (!pTrackUri.equals("")) {
                        Intent intent2 = new Intent(Intent.ACTION_VIEW);
                        intent2.setData(Uri.parse(pTrackUri));
                        intent2.putExtra(Intent.EXTRA_REFERRER,
                                Uri.parse("android-app://" + getApplicationContext().getPackageName()));
//                    seekToPosition = pPlaybackPosition;
//                    seekToPositionAfterSwitchLauncher.launch(intent2);
                        startActivity(intent2);
                    } else {
                        Toast.makeText(getApplicationContext(),
                                "Play pinned track: No pinned track found.", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(getApplicationContext(),
                            "Play pinned track: Spotify is not connected.", Toast.LENGTH_SHORT).show();
                }
                return true;
            case R.id.randomtrack:
                Random rnd = new Random();
                int rndTrackIndex = rnd.nextInt(currentList.length);
                playTrack(currentList[rndTrackIndex][1]);
                return true;
            case R.id.continuousplay:
                item.setChecked(!item.isChecked());
                if (item.isChecked()) randomPlayMenuItem.setChecked(false);

                editor.putBoolean("continuousplay", item.isChecked());
                editor.putBoolean("randomplay", randomPlayMenuItem.isChecked());
                editor.apply();

                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
                item.setActionView(new View(getApplicationContext()));
                item.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                    @Override
                    public boolean onMenuItemActionExpand(MenuItem item) {
                        return false;
                    }

                    @Override
                    public boolean onMenuItemActionCollapse(MenuItem item) {
                        return false;
                    }
                });
                return false;
            case R.id.randomplay:
                item.setChecked(!item.isChecked());
                if (item.isChecked()) continuousPlayMenuItem.setChecked(false);

                editor.putBoolean("randomplay", item.isChecked());
                editor.putBoolean("continuousplay", continuousPlayMenuItem.isChecked());
                editor.apply();

                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
                item.setActionView(new View(getApplicationContext()));
                item.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                    @Override
                    public boolean onMenuItemActionExpand(MenuItem item) {
                        return false;
                    }

                    @Override
                    public boolean onMenuItemActionCollapse(MenuItem item) {
                        return false;
                    }
                });
                return false;
            case R.id.switchapp:
                item.setChecked(!item.isChecked());
                editor.putBoolean("switchapp", item.isChecked());
                editor.apply();

                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
                item.setActionView(new View(getApplicationContext()));
                item.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                    @Override
                    public boolean onMenuItemActionExpand(MenuItem item) {
                        return false;
                    }

                    @Override
                    public boolean onMenuItemActionCollapse(MenuItem item) {
                        return false;
                    }
                });
                return false;
            case R.id.switchlocal:
                if (mBoundConnectorService.getSpotifyAppRemote() != null) {
                    mBoundConnectorService.getSpotifyAppRemote().getConnectApi().connectSwitchToLocalDevice();
                } else {
                    Toast.makeText(getApplicationContext(),
                            "Switch to local device: Spotify is not connected.", Toast.LENGTH_SHORT).show();
                }
                return true;
            case R.id.help:
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(HELPPAGE_URI));
                startActivity(browserIntent);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void setTrackDeleteMode(boolean mode) {
        trackDeleteMode = mode;
        if (trackDeleteMode) {
            deleteTrackMenuItem.setTitle("[Return to playback mode]");
            choosePlaylistMenuItem.setEnabled(false);
            exportPlaylistMenuItem.setEnabled(false);
            addTrackMenuItem.setEnabled(false);
            pinTrackMenuItem.setEnabled(false);
            playPinnedTrackMenuItem.setEnabled(false);
            playRandomTrackMenuItem.setEnabled(false);
            continuousPlayMenuItem.setEnabled(false);
            randomPlayMenuItem.setEnabled(false);
            switchAppMenuItem.setEnabled(false);
            switchLocalMenuItem.setEnabled(false);
            helpMenuItem.setEnabled(false);
        } else {
            deleteTrackMenuItem.setTitle("Delete track from playlist");
            choosePlaylistMenuItem.setEnabled(true);
            exportPlaylistMenuItem.setEnabled(true);
            addTrackMenuItem.setEnabled(true);
            pinTrackMenuItem.setEnabled(true);
            playPinnedTrackMenuItem.setEnabled(true);
            playRandomTrackMenuItem.setEnabled(true);
            continuousPlayMenuItem.setEnabled(true);
            randomPlayMenuItem.setEnabled(true);
            switchAppMenuItem.setEnabled(true);
            switchLocalMenuItem.setEnabled(true);
            helpMenuItem.setEnabled(true);
        }
    }

    public void refreshUI() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        Boolean switchApp = preferences.getBoolean("switchapp", false);
        //Boolean toggleList = preferences.getBoolean("togglelist", false);

        if (switchAppMenuItem != null) {
            switchAppMenuItem.setChecked(switchApp);
        }

        if (mBoundConnectorService != null) {
            if (togglePlaylistMenuItem != null) {
                currentList = mBoundConnectorService.getCurrentList(togglePlaylistMenuItem.isChecked());
                togglePlaylistMenuItem.setEnabled(externalListDefined());
            } else {
                currentList = mBoundConnectorService.getCurrentList();
                Log.d("refreshUI()", "togglePlaylistMenuItem=null, using defaults.");
            }

            if (currentList == null) {
                // Fall back to internal list
                currentList = mBoundConnectorService.getCurrentList(true);
                if (togglePlaylistMenuItem != null) {
                    togglePlaylistMenuItem.setChecked(true);
                    togglePlaylistMenuItem.setEnabled(false);
                }
            }
        }

        if (choosePlaylistMenuItem != null) {
            String prefix = choosePlaylistMenuItem.getTitle().toString().split("\\[")[0];
            if (externalListDefined()) {
                choosePlaylistMenuItem.setTitle(prefix + "[available]");
            } else {
                choosePlaylistMenuItem.setTitle(prefix + "[none]");
            }
        }

        createButtons(currentList);
    }

    private boolean externalListDefined() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String storedBlastFile = preferences.getString(SpotifyConnectorService.EXT_TRACKLIST_KEY, null);
        return (storedBlastFile != null);
    }

    private int dpAsPixels(int dp) {
        float f_dp = new Integer(dp).floatValue();
        Resources r = getResources();
        float f_px = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, f_dp, r.getDisplayMetrics());
        return (int) f_px;
    }

    private void createButtons(String[][] list) {

        LinearLayout layout = findViewById(R.id.linearlayout);
        layout.removeAllViews();

        for (int i = 0; i < list.length; i++) {
            String prefix = "";
            String suffix = "";
            Button newButton = new Button(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams
                    (LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, dpAsPixels(24));
            newButton.setLayoutParams(params);
            newButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
            int paddingPx = dpAsPixels(20);
            newButton.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);
            if (trackDeleteMode) {
                newButton.setBackgroundColor(Color.RED);
                prefix = "\u2718 ";
                suffix = "";
            } else {
                newButton.setBackgroundColor(Color.parseColor("#6a5acd"));
            }
            newButton.setText(prefix + list[i][0] + suffix);
            newButton.setTextColor(Color.parseColor("#ffffff"));
            newButton.getBackground().setAlpha(200);

            // Colors: 6200EE

            final int f_i = i;
            newButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (trackDeleteMode) {
                        setTrackDeleteMode(false);
                        deleteTrack(f_i);
                        return;
                    }

                    if (mBoundConnectorService.isSpotifyInstalled()) {
                        if (!switchAppMenuItem.isChecked()) {
                            playTrack(list[f_i][1]);
                        } else {
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setData(Uri.parse(list[f_i][1]));
                            intent.putExtra(Intent.EXTRA_REFERRER,
                                    Uri.parse("android-app://" + getApplicationContext().getPackageName()));
                            startActivity(intent);
                        }
                    } else {
                        final String appPackageName = "com.spotify.music";
                        final String referrer = "adjust_campaign=PACKAGE_NAME&adjust_tracker=ndjczk&utm_source=adjust_preinstall";
                        try {
                            Uri uri = Uri.parse("market://details")
                                    .buildUpon()
                                    .appendQueryParameter("id", appPackageName)
                                    .appendQueryParameter("referrer", referrer)
                                    .build();
                            startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        } catch (android.content.ActivityNotFoundException ignored) {
                            Uri uri = Uri.parse("https://play.google.com/store/apps/details")
                                    .buildUpon()
                                    .appendQueryParameter("id", appPackageName)
                                    .appendQueryParameter("referrer", referrer)
                                    .build();
                            startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        }
                    }
                }
            });
            layout.addView(newButton);
        }
    }

    private String getNextTrackUri() {
        int currTrkInd = 0;
        for (int i = 0; i < currentList.length; i++) {
            if (currentList[i][1].equals(currentTrackUri)) {
                currTrkInd = i;
                break;
            }
        }
        Log.d("getNextTrackUri()", "Current track was: [" + currTrkInd + "] " + currentList[currTrkInd][0]);
        if (continuousPlayMenuItem.isChecked()) {
            int nextTrackIndex = (currTrkInd + 1) % currentList.length;
            Log.d("getNextTrackUri()", "Continuous Play - next track is: [" + nextTrackIndex + "] " + currentList[nextTrackIndex][0]);
            return currentList[nextTrackIndex][1];
        }
        if (randomPlayMenuItem.isChecked()) {
            Random rnd = new Random();
            int nextTrackIndex = currTrkInd;
            while (nextTrackIndex == currTrkInd) {
                nextTrackIndex = rnd.nextInt(currentList.length);
            }
            Log.d("getNextTrackUri()", "Random Play - next track is: [" + nextTrackIndex + "] " + currentList[nextTrackIndex][0]);
            return currentList[nextTrackIndex][1];
        }
        return "";
    }

    private void resolveAndAddSharedTrack(String track) {
        Refreshable re = this;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                String title = trackToTitle(track);
                if ((mBoundConnectorService != null) & (title != "")) {
                    Log.d("resolveSharedTrack", "Adding shared track " + title);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            DialogFragment newFragment = new AddTrackDialog(title, track, mBoundConnectorService, re);
                            newFragment.show(getSupportFragmentManager(), "addTrackDialog");
                        }
                    });
                }
                if (title == "") {
                    Log.d("resolveSharedTrack", "Title for track '" + track + "' could not be resolved.");
                }
            }
        });
    }

    private void playTrack(String trackUri) {
        if (mBoundConnectorService != null) {
            if (mBoundConnectorService.isSpotifyConnected()) {
                currentTrackUri = trackUri;
                mBoundConnectorService.getSpotifyAppRemote().getPlayerApi().play(trackUri);
                lastTrackChangeTimestamp = System.currentTimeMillis();
            } else {
                Toast.makeText(getApplicationContext(),
                        "Playback: Spotify not connected.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void deleteTrack(int number) {
        if ((mBoundConnectorService != null) & (togglePlaylistMenuItem != null)) {
            mBoundConnectorService.deleteTrack(number, togglePlaylistMenuItem.isChecked());
        }
        refreshUI();
    }

    private String trackToTitle(String track) {
        OkHttpClient client = new OkHttpClient();

        RequestBody body = new FormBody.Builder().
                add("grant_type", "client_credentials").
                build();
        Request request = new Request.Builder().
                header("Authorization", "Basic " + BuildConfig.CLIENT_CREDENTIAL_BASE64).
                header("Content-Type", "application/x-www-form-urlencoded").
                url("https://accounts.spotify.com/api/token").post(body).build();
        Response response;
        String auth_token = "";
        try {
            response = client.newCall(request).execute();
            if (response.code() != 200) return "";
            JSONObject reader = new JSONObject(response.body().string());
            auth_token = reader.getString("access_token");
        } catch (JSONException | IOException e) {
            e.printStackTrace();
        }

        request = new Request.Builder().
                header("Authorization", "Bearer " + auth_token).
                url("https://api.spotify.com/v1/tracks/" + track).
                build();
        String title = "";
        try {
            response = client.newCall(request).execute();
            if (response.code() != 200) return "";
            JSONObject reader = new JSONObject(response.body().string());
            title = reader.getString("name");
        } catch (IOException | JSONException e) {
            Log.e("trackToTitle", "Resolving track name via Spotify Web API failed.");
            e.printStackTrace();
        }

        Log.d("trackToTitle", "Track " + track + " resolved to '" + title + "'.");
        return title;
    }

    private boolean connectorForegroundServiceRunning() {
        ActivityManager activityManager = (ActivityManager) getSystemService(getApplicationContext().ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (SpotifyConnectorService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public static class AddTrackDialog extends DialogFragment {
        private String title;
        private String trackId;
        private SpotifyConnectorService connectorService;
        private Refreshable re;

        AddTrackDialog(String title, String trackId, SpotifyConnectorService connectorService, Refreshable re) {
            this.title = title;
            this.trackId = trackId;
            this.connectorService = connectorService;
            this.re = re;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            String[] items = new String[2];
            items[0] = "Internal playlist";
            items[1] = "Selected playlist file";
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Add '" + title + "' to:")
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            if (i == 0) {
                                connectorService.addTrack(title, trackId, true);
                            } else {
                                connectorService.addTrack(title, trackId, false);
                            }
                            re.refreshUI();
                        }
                    });
            return builder.create();
        }
    }
}

interface Refreshable {
    void refreshUI();
}