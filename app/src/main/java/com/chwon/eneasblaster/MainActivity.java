package com.chwon.eneasblaster;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
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
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;

import com.spotify.protocol.client.CallResult;
import com.spotify.protocol.client.Subscription;
import com.spotify.protocol.types.PlayerContext;
import com.spotify.protocol.types.PlayerState;

public class MainActivity extends AppCompatActivity {

    private SpotifyConnectorService mBoundConnectorService;
    private boolean mShouldUnbind;

    private String[][] currentList;

    private MenuItem switchAppMenuItem;
    private MenuItem playPinnedTrackMenuItem;
    private Long seekToPosition = 0L;

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

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        switchAppMenuItem = menu.findItem(R.id.switchapp);
        Boolean switchApp = preferences.getBoolean("switchapp", false);
        switchAppMenuItem.setChecked(switchApp);
        playPinnedTrackMenuItem = menu.findItem(R.id.playpinnedtrack);
        String pTrackUri = preferences.getString("pinnedtrack", "");
        if (!pTrackUri.equals("")) {
            playPinnedTrackMenuItem.setEnabled(true);
        } else {
            playPinnedTrackMenuItem.setEnabled(false);
        }

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
            case R.id.choosefile:
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                processSelectedFileLauncher.launch(intent);
                return true;
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
                return true;
            case R.id.switchlocal:
                if (mBoundConnectorService.getSpotifyAppRemote() != null) {
                    mBoundConnectorService.getSpotifyAppRemote().getConnectApi().connectSwitchToLocalDevice();
                } else {
                    Toast.makeText(getApplicationContext(),
                            "Switch to local device: Spotify is not connected.", Toast.LENGTH_SHORT).show();
                }
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void refreshUI() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        Boolean switchApp = preferences.getBoolean("switchapp", false);
        if (switchAppMenuItem != null) {
            switchAppMenuItem.setChecked(switchApp);
        }
        currentList = mBoundConnectorService.getCurrentList();
        createButtons(currentList);
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
            Button newButton = new Button(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams
                    (LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, dpAsPixels(24));
            newButton.setLayoutParams(params);
            newButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
            int paddingPx = dpAsPixels(20);
            newButton.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);
            newButton.setText(list[i][0]);
            newButton.setTextColor(Color.parseColor("#ffffff"));
            newButton.setBackgroundColor(Color.parseColor("#6a5acd"));
            newButton.getBackground().setAlpha(200);

            // Colors: 6200EE

            final int f_i = i;
            newButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (mBoundConnectorService.isSpotifyInstalled()) {
                        if (!switchAppMenuItem.isChecked()) {
                            if (mBoundConnectorService.isSpotifyConnected()) {
                                mBoundConnectorService.getSpotifyAppRemote().getPlayerApi().play(list[f_i][1]);
                            } else {
                                Toast.makeText(getApplicationContext(),
                                        "Playback: Spotify not connected.", Toast.LENGTH_SHORT).show();
                            }

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

}