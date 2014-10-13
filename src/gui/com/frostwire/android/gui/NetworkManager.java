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

package com.frostwire.android.gui;

import android.app.Application;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import com.frostwire.android.core.ConfigurationManager;
import com.frostwire.android.core.Constants;
import com.frostwire.android.core.CoreRuntimeException;
import com.frostwire.util.ByteUtils;

import java.io.IOException;
import java.net.InetAddress;

/**
 * @author gubatron
 * @author aldenml
 */
public final class NetworkManager {

    private final Application context;

    private int listeningPort;

    private static NetworkManager instance;

    public synchronized static void create(Application context) {
        if (instance != null) {
            return;
        }
        instance = new NetworkManager(context);
    }

    public static NetworkManager instance() {
        if (instance == null) {
            throw new CoreRuntimeException("NetworkManager not created");
        }
        return instance;
    }

    private NetworkManager(Application context) {
        this.context = context;

        if (ConfigurationManager.instance().getBoolean(Constants.PREF_KEY_NETWORK_USE_RANDOM_LISTENING_PORT)) {
            listeningPort = ByteUtils.randomInt(40000, 49999);
        } else {
            listeningPort = Constants.GENERIC_LISTENING_PORT;
        }
    }

    public int getListeningPort() {
        return listeningPort;
    }

    public boolean isDataUp() {
        // boolean logic trick, since sometimes android reports WIFI and MOBILE up at the same time
        return (isDataWIFIUp() != isDataMobileUp());
    }

    public boolean isDataMobileUp() {
        return isDataUp(ConnectivityManager.TYPE_MOBILE);
    }

    public boolean isDataWIFIUp() {
        return isDataUp(ConnectivityManager.TYPE_WIFI);
    }

    public WifiManager getWifiManager() {
        return (WifiManager) context.getSystemService(Application.WIFI_SERVICE);
    }

    public InetAddress getMulticastInetAddress() throws IOException {
        WifiManager wifi = getWifiManager();
        int intaddr = wifi.getConnectionInfo().getIpAddress();
        byte[] byteaddr = new byte[]{(byte) (intaddr & 0xff), (byte) (intaddr >> 8 & 0xff), (byte) (intaddr >> 16 & 0xff), (byte) (intaddr >> 24 & 0xff)};
        return InetAddress.getByAddress(byteaddr);
    }

    private boolean isDataUp(int type) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Application.CONNECTIVITY_SERVICE);
        NetworkInfo info = connectivityManager.getActiveNetworkInfo();
        return info != null && info.getType() == type && info.isAvailable() && info.isConnected();
    }
}
