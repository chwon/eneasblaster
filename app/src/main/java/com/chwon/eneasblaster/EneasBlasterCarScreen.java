package com.chwon.eneasblaster;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Item;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.OnClickListener;
import androidx.car.app.model.Row;
import androidx.car.app.model.SearchTemplate;
import androidx.car.app.model.Template;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.util.List;
import java.util.Locale;

public class EneasBlasterCarScreen extends Screen {

    private int itemLimit;
    private ItemList globalItemList;
    private ItemList searchList;
    private SpotifyConnectorService mBoundConnectorService;
    private boolean mShouldUnbind;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            mBoundConnectorService = ((SpotifyConnectorService.LocalBinder) iBinder).getService();
            ItemList.Builder listBuilder = new ItemList.Builder();
            for (int i = 0; i < mBoundConnectorService.getCurrentList().length; i++) {
                final int f_i = i;
                final String title = mBoundConnectorService.getCurrentList()[i][0];
                listBuilder.addItem(new Row.Builder()
                        .setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick() {
                                triggerTrack(f_i);
                            }
                        })
                        .setTitle(title).build());
            }
            globalItemList = listBuilder.build();
            searchList = globalItemList;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBoundConnectorService = null;
        }
    };

    void doBindService() {
        if (getCarContext().bindService(new Intent(getCarContext(), SpotifyConnectorService.class),
                mConnection, getCarContext().BIND_AUTO_CREATE)) {
            mShouldUnbind = true;
        } else {
            Log.e("MY_APP_TAG", "Error: The requested service doesn't " +
                    "exist, or this client isn't allowed access to it.");
        }
    }

    void doUnbindService() {
        if (mShouldUnbind) {
            getCarContext().unbindService(mConnection);
            mShouldUnbind = false;
        }
    }

    public EneasBlasterCarScreen(CarContext carContext) {
        super(carContext);

        doBindService();

        ConstraintManager manager = getCarContext().getCarService(ConstraintManager.class);
        itemLimit = manager.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST);
        Log.d("EneasBlasterCarScreen", "itemLimit: " + itemLimit);

        getLifecycle().addObserver(new LifecycleEventObserver() {
            @Override
            public void onStateChanged(@NonNull LifecycleOwner source, @NonNull Lifecycle.Event event) {
                switch (event) {
                    case ON_PAUSE:
                        doUnbindService();
                        break;
                    case ON_RESUME:
                        doBindService();
                        break;
                    default:
                        return;
                }
            }
        });
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        return new SearchTemplate.Builder(new SearchTemplate.SearchCallback() {
            @Override
            public void onSearchTextChanged(@NonNull String searchText) {
                searchList = getItemListForString(searchText);
                invalidate();
            }
        }).setItemList(searchList)
                .setShowKeyboardByDefault(false)
                .build();
    }

    private void triggerTrack(int number) {
        if (! mBoundConnectorService.isSpotifyInstalled()) {
            Toast.makeText(getCarContext(),
                    "Please install Spotify.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mBoundConnectorService.getSpotifyAppRemote().isConnected()) {
            mBoundConnectorService.getSpotifyAppRemote().getPlayerApi().play(mBoundConnectorService.getCurrentList()[number][1]);
        } else {
            Log.d("EneasBlasterCarScreen", "Spotify is not connected.");
        }
    }

    private ItemList getItemListForString(String s) {
        ItemList.Builder listBuilder = new ItemList.Builder();
        List<Item> list = globalItemList.getItems();
        String searchText = s.toUpperCase(Locale.ROOT);
        for (int i = 0; i < list.size(); i++) {
            String title = ((Row)list.get(i)).getTitle().toString().toUpperCase(Locale.ROOT);
            if (title.contains(searchText)) {
                listBuilder.addItem(list.get(i));
            }
        }
        return listBuilder.build();
    }

}
