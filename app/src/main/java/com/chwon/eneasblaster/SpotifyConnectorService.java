package com.chwon.eneasblaster;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.spotify.android.appremote.api.ConnectionParams;
import com.spotify.android.appremote.api.Connector;
import com.spotify.android.appremote.api.SpotifyAppRemote;
import com.spotify.protocol.client.Subscription;
import com.spotify.protocol.types.PlayerState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.Queue;

public class SpotifyConnectorService extends Service {

    private final IBinder mBinder = new LocalBinder();
    private final String CLIENT_ID = BuildConfig.CLIENT_ID;
    private final String REDIRECT_URI = "http://localhost/";

    private SpotifyAppRemote mSpotifyAppRemote = null;
    private boolean isSpotifyConnected = false;

    Queue<Subscription.EventCallback<PlayerState>> callbackQueue;

    public class LocalBinder extends Binder {
        SpotifyConnectorService getService() {
            return SpotifyConnectorService.this;
        }
    }

    private String[][] currentList;

    String blastList[][] = {
            {"We will rock you", "spotify:track:4pbJqGIASGPr0ZpGpnWkDn"},
            {"Star Wars Musik", "spotify:track:2wi6V9TPFAqciBWQ2FmD7o"},
            {"Star Wars Imperial March", "spotify:track:1AR2uj8ADsuTRqPZBIXHCm"},
            {"Harry Potter Musik", "spotify:track:4clXV4rlEHScwSR85lumky"},
            {"Macarena", "spotify:track:6mhw2fEPH4fMF0wolNm96e"},
            {"I like to move it", "spotify:track:4bAFo6r2ODMDoqM5YHV2gM"},
            {"Help!", "spotify:track:3Smida2eCUsLzDcmZqXEZ3"},
            {"Indiana Jones Musik", "spotify:track:60ZYLVPmSNY9r0Uquaivvs"},
            {"Jerusalema", "spotify:track:2MlOUXmcofMackX3bxfSwi"},
            {"Ai Se Eu Te Pego", "spotify:track:4yPKDDClQA51Hjeao0ckZq"},
            {"Call me Cruella", "spotify:track:7lGfhlbpUkI1DOE51Vb2Mt"},
            {"Try Everything", "spotify:track:4XAOFJ9F10fdu3mVdqlUFd"},
            {"A Place Called Slaughter Race", "spotify:track:30UNAz40wbR8CnLMZkCLe7"},
            {"Circle of Life", "spotify:track:0HU5JnVaKNTWf6GykV9Zn8"},
            {"Jurassic Park Musik", "spotify:track:2TZbQZXOuR8osP2AK8yYMN"},
            {"James Bond Musik", "spotify:track:6xmAmZxtbkhA2aOXHRhTke"},
            {"The Final Countdown", "spotify:track:3MrRksHupTVEQ7YbA0FsZK"},
            {"Bella ciao", "spotify:track:5XYPTwya4YqPystALy9cLJ"}
//          {"", "spotify:track:"},
    };


    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        callbackQueue = new LinkedList<Subscription.EventCallback<PlayerState>>();
        ConnectionParams connectionParams =
                new ConnectionParams.Builder(CLIENT_ID)
                        .setRedirectUri(REDIRECT_URI)
                        .showAuthView(true)
                        .build();
        SpotifyAppRemote.connect(this, connectionParams,
                new Connector.ConnectionListener() {

                    @Override
                    public void onConnected(SpotifyAppRemote spotifyAppRemote) {
                        mSpotifyAppRemote = spotifyAppRemote;
                        Log.d("SpotifyConnectorService", "Connected! Yay!");
                        isSpotifyConnected = true;
                        while (! callbackQueue.isEmpty()) {
                            Subscription<PlayerState> psSub = mSpotifyAppRemote.getPlayerApi().subscribeToPlayerState();
                            psSub.setEventCallback(callbackQueue.poll());
                        }
                    }

                    @Override
                    public void onFailure(Throwable throwable) {
                        Log.e("SpotifyConnectorService", throwable.getMessage(), throwable);
                    }
                });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        SpotifyAppRemote.disconnect(mSpotifyAppRemote);
        isSpotifyConnected = false;
        mSpotifyAppRemote = null;
    }

    public boolean isSpotifyConnected() {
        return isSpotifyConnected;
    }

    public SpotifyAppRemote getSpotifyAppRemote() {
        return mSpotifyAppRemote;
    }

    public String[][] getCurrentList() {
        loadBlastFile();
        return currentList;
    }

    private void loadBlastFile() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String storedBlastFile = preferences.getString("blastfile", null);
        if (storedBlastFile != null) {
            boolean storedFileCorrupt = false;
            String[][] blist = null;
            Uri uri = Uri.parse(storedBlastFile);
            StringBuilder sb = new StringBuilder();
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                String line = null;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                blist = new Gson().fromJson(sb.toString(), String[][].class);
                if ((blist == null) | (blist.length == 0)) {
                    Toast.makeText(getApplicationContext(),
                            "Error: Invalid file.", Toast.LENGTH_SHORT).show();
                    storedFileCorrupt = true;
                }
            } catch (IOException | JsonSyntaxException e) {
                Toast.makeText(getApplicationContext(),
                        "Error: File could not be read.", Toast.LENGTH_SHORT).show();
                storedFileCorrupt = true;
            }
            if (storedFileCorrupt) {
                SharedPreferences.Editor editor = preferences.edit();
                editor.putString("blastfile", null);
                editor.apply();
                currentList = blastList;
            } else {
                currentList = blist;
                Toast.makeText(getApplicationContext(),
                        "File loaded.", Toast.LENGTH_SHORT).show();
            }
        } else {
            currentList = blastList;
        }
    }

    public boolean isSpotifyInstalled() {
        boolean isSpotifyInstalled = false;
        PackageManager pm = getPackageManager();
        try {
            pm.getPackageInfo("com.spotify.music", 0);
            isSpotifyInstalled = true;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            isSpotifyInstalled = false;
        }
        return isSpotifyInstalled;
    }

    public void subscribeToPlayerState(Subscription.EventCallback<PlayerState> cb) {
        if (isSpotifyConnected) {
            Subscription<PlayerState> psSub = mSpotifyAppRemote.getPlayerApi().subscribeToPlayerState();
            psSub.setEventCallback(cb);
        } else {
            callbackQueue.add(cb);
        }
    }
}