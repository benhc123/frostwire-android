/*
 * Created by Angel Leon (@gubatron), Alden Torres (aldenml)
 * Copyright (c) 2011, 2012, FrostWire(TM). All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.frostwire.android.gui.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.Environment;
import android.telephony.TelephonyManager;
import com.frostwire.android.core.ConfigurationManager;
import com.frostwire.android.core.Constants;
import com.frostwire.android.core.player.CoreMediaPlayer;
import com.frostwire.android.gui.Librarian;
import com.frostwire.android.gui.NetworkManager;
import com.frostwire.android.gui.UniversalScanner;
import com.frostwire.android.util.SystemUtils;
import com.frostwire.logging.Logger;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Receives and controls messages from the external world. Depending on the
 * status it attempts to control what happens with the Engine.
 *
 * @author gubatron
 * @author aldenml
 */
public final class EngineBroadcastReceiver extends BroadcastReceiver {

    private static final Logger LOG = Logger.getLogger(EngineBroadcastReceiver.class);

    private boolean connected;

    private final ExecutorService engineExecutor;

    private boolean wasPlaying;

    public EngineBroadcastReceiver() {
        engineExecutor = Executors.newFixedThreadPool(1);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String action = intent.getAction();

            if (action.equals(Intent.ACTION_MEDIA_MOUNTED)) {
                handleMediaMounted(context, intent);

                if (Engine.instance().isDisconnected()) {
                    engineExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            Engine.instance().startServices();
                        }
                    });
                }
            } else if (action.equals(Intent.ACTION_MEDIA_UNMOUNTED)) {
                handleMediaUnmounted(context, intent);
            } else if (action.equals(TelephonyManager.ACTION_PHONE_STATE_CHANGED)) {
                handleActionPhoneStateChanged(intent);
            } else if (action.equals(Intent.ACTION_MEDIA_SCANNER_FINISHED)) {
                Librarian.instance().syncMediaStore();
            } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                handleConnectivityAction();
            } else if (action.equals(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) {
                if (Engine.instance().getMediaPlayer().isPlaying()) {
                    Engine.instance().getMediaPlayer().togglePause();
                }
            } else if (action.equals(Intent.ACTION_PACKAGE_ADDED)) {
                Librarian.instance().syncApplicationsProvider();
            } else if (action.equals(Intent.ACTION_PACKAGE_REMOVED)) {
                // no sure about this case
            }

            if (!Librarian.instance().isExternalStorageMounted()) {
                LOG.error("Halting process due to lack of external storage");
                Librarian.instance().halt();
            }
        } catch (Throwable e) {
            LOG.error("Error processing broadcast message", e);
        }
    }

    private void handleActionPhoneStateChanged(Intent intent) {
        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        String msg = "Phone state changed to " + state;
        LOG.info(msg);

        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state) || TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state) || TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            CoreMediaPlayer mediaPlayer = Engine.instance().getMediaPlayer();
            if (mediaPlayer.isPlaying()) {
                wasPlaying = true;
                mediaPlayer.togglePause();
            } else if (wasPlaying && TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
                mediaPlayer.seekTo(Math.max(0, mediaPlayer.getPosition() - 2000));
                wasPlaying = false;
                mediaPlayer.togglePause();
            }
        }
    }

    private void handleConnectivityAction() {
        if (NetworkManager.instance().isDataUp()) {
            handleConnectedNetwork();
        } else {
            handleDisconnectedNetwork();
        }
    }

    private void handleDisconnectedNetwork() {
        if (!connected) {
            return;
        }
        connected = false;

        LOG.info("Disconnected from network");
        Engine.instance().getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                Engine.instance().stopServices(true);
            }
        });
    }

    private void handleConnectedNetwork() {
        if (connected) {
            return;
        }
        connected = true;

        LOG.info("Connected to " + NetworkManager.instance().getActiveNetworkInfo().getTypeName());
        if (Engine.instance().isDisconnected()) {
            Engine.instance().getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    Engine.instance().startServices();
                }
            });
        }
    }

    // TODO:BITTORRENT
    /*
    private void handleConnectedNetwork(NetworkInfo networkInfo) {
        if (NetworkManager.instance().isDataUp()) {

            boolean useTorrentsOnMobileData = ConfigurationManager.instance().getBoolean(Constants.PREF_KEY_NETWORK_USE_MOBILE_DATA);

            // "Boolean Master", just for fun.
            // Let a <= "mobile up",
            //     b <= "use torrents on mobile"
            //
            // In English:
            // is mobile data up and not user torrents on mobile? then abort else start services.
            //
            // In Boolean:
            // if (a && !b) then return; else start services.
            //
            // since early 'return' statements are a source of evil, I'll use boolean algebra...
            // so that we can instead just start services under the right conditions.
            //
            // negating "a && !b" I get...
            // ^(a && !b) => ^a || b
            //
            // In English:
            // if not mobile up or use torrents on mobile data then start services. (no else needed)
            //
            // mobile up means only mobile data is up and wifi is down.

            if (!NetworkManager.instance().isDataMobileUp() || useTorrentsOnMobileData) {
                LOG.info("Connected to " + networkInfo.getTypeName());
                if (Engine.instance().isDisconnected()) {
                    // avoid ANR error inside a broadcast receiver
                    engineExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            Engine.instance().startServices();

                            if (!ConfigurationManager.instance().getBoolean(Constants.PREF_KEY_TORRENT_SEED_FINISHED_TORRENTS) || (!NetworkManager.instance().isDataWIFIUp() && ConfigurationManager.instance().getBoolean(Constants.PREF_KEY_TORRENT_SEED_FINISHED_TORRENTS_WIFI_ONLY))) {
                                TransferManager.instance().stopSeedingTorrents();
                            }
                        }
                    });
                }
            }
        }
    }*/

    private void handleMediaMounted(final Context context, Intent intent) {
        try {
            String path = intent.getDataString().replace("file://", "");
            if (!SystemUtils.isPrimaryExternalPath(new File(path))) {
                Intent i = new Intent(Constants.ACTION_NOTIFY_SDCARD_MOUNTED);
                context.sendBroadcast(i);

                if (SystemUtils.hasKitKat()) {
                    final File privateDir = new File(path + File.separator + "Android" + File.separator + "data" + File.separator + context.getPackageName() + File.separator + "files" + File.separator + "FrostWire");
                    if (privateDir.exists() && privateDir.isDirectory()) {
                        Thread t = new Thread(new Runnable() {

                            @Override
                            public void run() {
                                new UniversalScanner(context).scanDir(privateDir);
                            }
                        });

                        t.setName("Private MediaScanning");
                        t.setDaemon(true);
                        t.start();
                    }
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * make sure the current save location will be the primary external if
     * the media being unmounted is the sd card.
     *
     * @param context
     * @param intent
     */
    private void handleMediaUnmounted(Context context, Intent intent) {
        String path = intent.getDataString().replace("file://", "");
        if (!SystemUtils.isPrimaryExternalPath(new File(path)) &&
                SystemUtils.isPrimaryExternalStorageMounted()) {
            File primaryExternal = Environment.getExternalStorageDirectory();
            ConfigurationManager.instance().setStoragePath(primaryExternal.getAbsolutePath());
        }
    }
}
