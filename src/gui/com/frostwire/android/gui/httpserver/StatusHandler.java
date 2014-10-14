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

package com.frostwire.android.gui.httpserver;

import com.frostwire.bittorrent.BTEngine;
import com.frostwire.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import org.apache.commons.io.IOUtils;
import sun.net.httpserver.Code;

import java.io.IOException;
import java.io.OutputStream;

/**
 * @author gubatron
 * @author aldenml
 */
class StatusHandler extends AbstractHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        OutputStream os = null;

        try {
            String response = JsonUtils.toJson(new Status(), true);

            exchange.sendResponseHeaders(Code.HTTP_OK, response.length());

            os = exchange.getResponseBody();

            os.write(response.getBytes("UTF-8"));

        } finally {
            IOUtils.closeQuietly(os);
            exchange.close();
        }
    }

    private static class Status {

        public final boolean bt_engine_is_started = BTEngine.getInstance().isStarted();
        public final boolean bt_engine_is_firewalled = BTEngine.getInstance().isFirewalled();
    }
}
