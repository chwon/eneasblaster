package com.chwon.eneasblaster;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class SpotifyConnectorService extends Service {

    private final IBinder mBinder = new LocalBinder();
    private final String CLIENT_ID = BuildConfig.CLIENT_ID;
    private final String REDIRECT_URI = "http://localhost/";
    private final String INT_TRACKLIST_FILENAME = "internal.eneas";
    protected static final String EXT_TRACKLIST_KEY = "blastfile";

    private SpotifyAppRemote mSpotifyAppRemote = null;

    private Uri intTrackUri = null;

    private List<String[]> internalList = null;
    private List<String[]> externalList = null;

    private boolean lastUsedListIsInternal = true;

    Queue<Subscription.EventCallback<PlayerState>> callbackQueue;

    private NotificationChannel ebNotificationChannel;
    private final String CHANNEL_ID = "Blaster Main";
    private final int NOTIFICATION_ID = 1704*89;
    private final String notificationTitle = "Eneas Blaster is running";

    private long lastExternalCall = 0L;
    private Handler sleepCheckHandler;
    private final Long SLEEP_CHECK_PERIOD = 21600000L;  // 6 hours

    public class LocalBinder extends Binder {
        SpotifyConnectorService getService() {
            return SpotifyConnectorService.this;
        }
    }

    Subscription.EventCallback<PlayerState> cb = new Subscription.EventCallback<PlayerState>() {
        @Override
        public void onEvent(PlayerState data) {
            // do something related to player state (if needed)
        }
    };

    String blastList[][] = {
            {"We will rock you", "spotify:track:4pbJqGIASGPr0ZpGpnWkDn"},
            {"Star Wars Imperial March", "spotify:track:1AR2uj8ADsuTRqPZBIXHCm"},
            {"Macarena", "spotify:track:6mhw2fEPH4fMF0wolNm96e"},
            {"Help!", "spotify:track:3Smida2eCUsLzDcmZqXEZ3"},
            {"Jerusalema", "spotify:track:2MlOUXmcofMackX3bxfSwi"},
            {"Ai Se Eu Te Pego", "spotify:track:4yPKDDClQA51Hjeao0ckZq"},
            {"Call me Cruella", "spotify:track:7lGfhlbpUkI1DOE51Vb2Mt"},
            {"Try Everything", "spotify:track:4XAOFJ9F10fdu3mVdqlUFd"},
            {"Circle of Life", "spotify:track:0HU5JnVaKNTWf6GykV9Zn8"},
            {"Bella ciao", "spotify:track:5XYPTwya4YqPystALy9cLJ"}
    };


    @Override
    public void onCreate() {
        callbackQueue = new LinkedList<Subscription.EventCallback<PlayerState>>();
        intTrackUri = Uri.fromFile(new File(getApplicationContext().getFilesDir(), INT_TRACKLIST_FILENAME));
        sleepCheckHandler = new Handler();

        // Subscription is queued, of Spotify is not yet connected
        subscribeToPlayerState(cb);
    }

    // onStartCommand is called only, when the service is started using
    // startService(). Not called, when creating the service upon binding.
    // https://stackoverflow.com/questions/14182014/android-oncreate-or-onstartcommand-for-starting-service
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ebNotificationChannel = new NotificationChannel(CHANNEL_ID, CHANNEL_ID, NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ebNotificationChannel);
        Intent notificationIntent = new Intent(getApplicationContext(), MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentText("")
                .setContentTitle(notificationTitle)
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.drawable.ic_stat_music_video);

        startForeground(NOTIFICATION_ID, notification.build());
        checkAndUpdateConnectionStatus();
        sleepCheckHandler.postDelayed(new SleepCheckRunnable(), SLEEP_CHECK_PERIOD);
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        SpotifyAppRemote.disconnect(mSpotifyAppRemote);
        mSpotifyAppRemote = null;
    }

    private void checkAndUpdateConnectionStatus() {
        if (!isSpotifyConnected()) {
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
                            while (!callbackQueue.isEmpty()) {
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
    }

    public boolean isSpotifyConnected() {
        lastExternalCall = System.currentTimeMillis();
        if (mSpotifyAppRemote == null) {
            return false;
        } else {
            return mSpotifyAppRemote.isConnected();
        }
    }

    public SpotifyAppRemote getSpotifyAppRemote() {
        lastExternalCall = System.currentTimeMillis();
        return mSpotifyAppRemote;
    }

    public String[][] getCurrentList() {
        return getCurrentList(lastUsedListIsInternal);
    }

    public String[][] getCurrentList(boolean useIntList) {
        lastExternalCall = System.currentTimeMillis();
        checkAndUpdateConnectionStatus();
        lastUsedListIsInternal = useIntList;
        loadInternalList();
        loadExternalList();

        if (useIntList) {
            return trackListToArray(internalList);
        } else {
            return trackListToArray(externalList);
        }
    }

    public void addTrack(String title, String id, boolean useInternalList) {
        lastExternalCall = System.currentTimeMillis();
        Log.d("addTrack", "Adding " + title + " (" + id + ")");
        String[] s = new String[2];
        s[0] = title;
        s[1] = "spotify:track:" + id;
        if (useInternalList) {
            internalList.add(s);
            storeTrackFile(internalList, intTrackUri);
        } else {
            externalList.add(s);
            storeTrackFile(externalList, getExternalListUri());
        }
    }

    public void deleteTrack(int number, boolean inInternalList) {
        lastExternalCall = System.currentTimeMillis();
        if (inInternalList) {
            internalList.remove(number);
            storeTrackFile(internalList, intTrackUri);
        } else {
            externalList.remove(number);
            storeTrackFile(externalList, getExternalListUri());
        }
    }

    private Uri getExternalListUri() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String storedBlastFile = preferences.getString(EXT_TRACKLIST_KEY, null);
        if (storedBlastFile != null) {
            return Uri.parse(storedBlastFile);
        } else {
            return null;
        }
    }

    private void loadInternalList() {
        if (internalList != null) return;

        if (new File(intTrackUri.getPath()).exists()) {
            internalList = loadTrackFile(intTrackUri);
        }

        // Internal list is filled up with static blast list - either if there isn't an internal
        // list file yet, or if loading of the file has failed
        if (internalList == null) {
            internalList = new ArrayList<String[]>();
            for (int i = 0; i < blastList.length; i++) {
                internalList.add(blastList[i]);
            }
            storeTrackFile(internalList, intTrackUri);
        }
    }

    private void loadExternalList() {
        if (externalList != null) return;

        externalList = loadTrackFile(getExternalListUri());
        if (externalList == null) {
            // No error msg here, as no external list might have been defined
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            SharedPreferences.Editor editor = preferences.edit();
            editor.putString(EXT_TRACKLIST_KEY, null);
            editor.apply();
        }
    }

    private List<String[]> loadTrackFile(Uri uri) {
        if (uri == null) return null;

        boolean fileCorrupt = false;
        String[][] entries = null;
        StringBuilder sb = new StringBuilder();
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line = null;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            entries = new Gson().fromJson(sb.toString(), String[][].class);
            if ((entries == null) | (entries.length == 0)) {
                toastOnUiThread("Error: Invalid file.");
                fileCorrupt = true;
            }
        } catch (IOException | JsonSyntaxException e) {
            toastOnUiThread("Error: File could not be read.");
            fileCorrupt = true;
            e.printStackTrace();
        }
        if (fileCorrupt) return null;
        List<String[]> retList = new ArrayList<String[]>();
        for (int i = 0; i < entries.length; i++) {
            retList.add(entries[i]);
        }
        return retList;
    }

    private void storeTrackFile(List<String[]> tracks, Uri uri) {
        if (uri == null) return;

        String s = new Gson().toJson(trackListToArray(tracks), String[][].class);
        try {
            FileWriter writer = new FileWriter(uri.getPath());
            writer.write(s);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            toastOnUiThread("Error: File could not be written.");
        }
    }

/*    private void loadBlastFile() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String storedBlastFile = preferences.getString("blastfile", null);
        if (storedBlastFile != null) {
            boolean storedFileCorrupt = false;
            String[][] blist = null;
            Uri uri = Uri.parse(storedBlastFile);
            Log.d("ERRORSEAR", "loadblastfile uri: " + uri);
            StringBuilder sb = new StringBuilder();
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                String line = null;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                    Log.d("ERRORSEAR", "blist: " + line);
                }
                reader.close();
                blist = new Gson().fromJson(sb.toString(), String[][].class);
                if ((blist == null) | (blist.length == 0)) {
                    toastOnUiThread("Error: Invalid file.");
                    storedFileCorrupt = true;
                }
            } catch (IOException | JsonSyntaxException e) {
                toastOnUiThread("Error: File could not be read.");
                storedFileCorrupt = true;
            }
            if (storedFileCorrupt) {
                SharedPreferences.Editor editor = preferences.edit();
                editor.putString("blastfile", null);
                editor.apply();
                currentList = blastList;
            } else {
                currentList = blist;
                toastOnUiThread("File loaded.");
            }
        } else {
            currentList = blastList;
        }
    }*/

    public boolean isSpotifyInstalled() {
        lastExternalCall = System.currentTimeMillis();
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
        lastExternalCall = System.currentTimeMillis();
        if (isSpotifyConnected()) {
            Subscription<PlayerState> psSub = mSpotifyAppRemote.getPlayerApi().subscribeToPlayerState();
            psSub.setEventCallback(cb);
        } else {
            callbackQueue.add(cb);
        }
    }

    public void updateNotification(String text) {
        lastExternalCall = System.currentTimeMillis();
        Intent notificationIntent = new Intent(getApplicationContext(), MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent intent = PendingIntent.getActivity(getApplicationContext(), 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentText(text)
                .setContentTitle(notificationTitle)
                .setContentIntent(intent)
                .setSmallIcon(R.drawable.ic_stat_music_video)
                .build();

        NotificationManager mNotificationManager = (NotificationManager) getSystemService(getApplicationContext().NOTIFICATION_SERVICE);
        mNotificationManager.notify(NOTIFICATION_ID, notification);
    }

    private void toastOnUiThread(String message) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(),
                        message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static String[][] trackListToArray(List<String[]> trackList) {
        String[][] array = new String[trackList.size()][2];
        for (int i = 0; i < trackList.size(); i++) {
            array[i] = trackList.get(i);
        }
        return array;
    }

    private class SleepCheckRunnable implements Runnable {

        @Override
        public void run() {
            if ((lastExternalCall + SLEEP_CHECK_PERIOD) < System.currentTimeMillis()) {
                // No external call within last SLEEP_CHECK_PERIOD
                Log.d("SleepCheckRunnable", "Sleep check overdue - stopping foreground service.");
                sleepCheckHandler.removeCallbacksAndMessages(null);
                SpotifyConnectorService.this.stopForeground(Service.STOP_FOREGROUND_REMOVE);
                SpotifyConnectorService.this.stopSelf();

            } else {
                sleepCheckHandler.postDelayed(this, SLEEP_CHECK_PERIOD);
                Log.d("SleepCheckRunnable", "Sleep check - activity ongoing, keeping service in foreground.");
            }
        }
    }

}