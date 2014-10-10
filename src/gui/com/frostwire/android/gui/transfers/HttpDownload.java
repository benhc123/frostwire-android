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

package com.frostwire.android.gui.transfers;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.frostwire.transfers.DownloadTransfer;
import com.frostwire.transfers.TransferItem;
import com.frostwire.transfers.TransferState;
import org.apache.commons.io.FilenameUtils;

import android.os.SystemClock;
import android.util.Log;

import com.frostwire.android.core.Constants;
import com.frostwire.android.core.HttpFetcher;
import com.frostwire.android.core.HttpFetcherListener;
import com.frostwire.android.gui.Librarian;
import com.frostwire.android.gui.services.Engine;
import com.frostwire.android.gui.util.SystemUtils;
import com.frostwire.util.ZipUtils;

/**
 * @author gubatron
 * @author aldenml
 *
 */
public final class HttpDownload implements DownloadTransfer {

    private static final String TAG = "FW.HttpDownload";

    static final TransferState STATUS_DOWNLOADING = TransferState.DOWNLOADING;
    static final TransferState STATUS_COMPLETE = TransferState.COMPLETE;
    static final TransferState STATUS_ERROR = TransferState.ERROR;
    static final TransferState STATUS_CANCELLED = TransferState.CANCELED;
    static final TransferState STATUS_WAITING = TransferState.WAITING;
    static final TransferState STATUS_UNCOMPRESSING = TransferState.UNCOMPRESSING;

    private static final int SPEED_AVERAGE_CALCULATION_INTERVAL_MILLISECONDS = 1000;

    private final TransferManager manager;
    private final HttpDownloadLink link;
    private final Date dateCreated;
    private final File savePath;

    private TransferState status;
    private long bytesReceived;
    private long averageSpeed; // in bytes

    // variables to keep the download rate of file transfer
    private long speedMarkTimestamp;
    private long totalReceivedSinceLastSpeedStamp;

    private HttpDownloadListener listener;

    HttpDownload(TransferManager manager, File savePath, HttpDownloadLink link) {
        this.manager = manager;
        this.link = link;
        this.dateCreated = new Date();

        this.savePath = new File(savePath, link.getFileName());

        this.status = STATUS_DOWNLOADING;
    }

    HttpDownload(TransferManager manager, HttpDownloadLink link) {
        this(manager, SystemUtils.getTorrentDataDirectory(), link);
    }

    public HttpDownloadListener getListener() {
        return listener;
    }

    public void setListener(HttpDownloadListener listener) {
        this.listener = listener;
    }

    @Override
    public String getName() {
        return getDetailsUrl();
    }

    public String getDisplayName() {
        return link.getDisplayName();
    }

    public TransferState getState() {
        return status;
    }

    public int getProgress() {
        if (link.getSize() > 0) {
            return isComplete() ? 100 : (int) ((bytesReceived * 100) / link.getSize());
        } else {
            return 0;
        }
    }

    public long getSize() {
        return link.getSize();
    }

    public Date getCreated() {
        return dateCreated;
    }

    public long getBytesReceived() {
        return bytesReceived;
    }

    public long getBytesSent() {
        return 0;
    }

    public long getDownloadSpeed() {
        return (!isDownloading()) ? 0 : averageSpeed;
    }

    public long getUploadSpeed() {
        return 0;
    }

    public long getETA() {
        if (link.getSize() > 0) {
            long speed = getDownloadSpeed();
            return speed > 0 ? (link.getSize() - getBytesReceived()) / speed : Long.MAX_VALUE;
        } else {
            return 0;
        }
    }

    public boolean isComplete() {
        if (bytesReceived > 0) {
            return (bytesReceived == link.getSize() && status == STATUS_COMPLETE) || status == STATUS_ERROR;
        } else {
            return false;
        }
    }

    public boolean isDownloading() {
        return status == STATUS_DOWNLOADING;
    }

    public List<TransferItem> getItems() {
        return Collections.emptyList();
    }

    public File getSavePath() {
        return savePath;
    }

    public void remove() {
        remove(false);
    }

    public void remove(boolean deleteData) {
        if (status != STATUS_COMPLETE) {
            status = STATUS_CANCELLED;
        }
        if (status != STATUS_COMPLETE || deleteData) {
            cleanup();
        }
        manager.remove(this);
    }

    public void start() {
        start(0, 0);
    }

    /**
     * 
     * @param delay in seconds.
     * @param retry
     */
    private void start(final int delay, final int retry) {
        Engine.instance().getThreadPool().execute(new Thread(getDisplayName()) {
            public void run() {
                try {
                    status = STATUS_WAITING;
                    SystemClock.sleep(delay * 1000);

                    status = STATUS_DOWNLOADING;
                    String uri = link.getUrl();
                    new HttpFetcher(uri, 10000).save(savePath, new DownloadListener(retry));
                    Librarian.instance().scan(savePath);
                } catch (Throwable e) {
                    error(e);
                }
            }
        });
    }

    private void updateAverageDownloadSpeed() {
        long now = System.currentTimeMillis();

        if (isComplete()) {
            averageSpeed = 0;
            speedMarkTimestamp = now;
            totalReceivedSinceLastSpeedStamp = 0;
        } else if (now - speedMarkTimestamp > SPEED_AVERAGE_CALCULATION_INTERVAL_MILLISECONDS) {
            averageSpeed = ((bytesReceived - totalReceivedSinceLastSpeedStamp) * 1000) / (now - speedMarkTimestamp);
            speedMarkTimestamp = now;
            totalReceivedSinceLastSpeedStamp = bytesReceived;
        }
    }

    private void complete() {
        boolean success = true;
        String location = null;
        if (link.isCompressed()) {
            status = STATUS_UNCOMPRESSING;
            location = FilenameUtils.removeExtension(savePath.getAbsolutePath());
            success = ZipUtils.unzip(savePath, new File(location));
        }

        if (success) {
            if (listener != null) {
                listener.onComplete(this);
            }

            status = STATUS_COMPLETE;

            manager.incrementDownloadsToReview();
            Engine.instance().notifyDownloadFinished(getDisplayName(), getSavePath());

            if (savePath.getAbsoluteFile().exists()) {
                Librarian.instance().scan(link.isCompressed() ? new File(location) : getSavePath().getAbsoluteFile());
            }
        } else {
            error(new Exception("Error"));
        }
    }

    private void error(Throwable e) {
        if (status != STATUS_CANCELLED) {
            Log.e(TAG, String.format("Error downloading url: %s", link.getUrl()), e);
            status = STATUS_ERROR;
            cleanup();
        }
    }

    private void cleanup() {
        try {
            savePath.delete();
        } catch (Throwable tr) {
            // ignore
        }
    }

    private final class DownloadListener implements HttpFetcherListener {

        private final int retry;

        public DownloadListener(int retry) {
            this.retry = retry;
        }

        public void onData(byte[] data, int length) {
            bytesReceived += length;
            updateAverageDownloadSpeed();

            if (status == STATUS_CANCELLED) {
                // ok, this is not the most elegant solution but it effectively breaks the
                // download logic flow.
                throw new RuntimeException("Invalid status, transfer cancelled");
            }
        }

        public void onSuccess(byte[] body) {
            complete();
        }

        public void onError(Throwable e, int statusCode, Map<String, String> headers) {
            try {
                if (statusCode == 503 && headers.containsKey("Retry-After") && retry < Constants.MAX_PEER_HTTP_DOWNLOAD_RETRIES) {
                    int delay = Integer.parseInt(headers.get("Retry-After"));
                    if (delay > 0) {
                        start(delay, retry + 1);
                    } else {
                        error(e);
                    }
                } else {
                    error(e);
                }
            } catch (Throwable tr) {
                error(tr);
            }
        }
    }

    static void simpleHTTP(String url, OutputStream out) throws Throwable {
        simpleHTTP(url, out, 1000);
    }

    static void simpleHTTP(String url, OutputStream out, int timeout) throws Throwable {
        URL u = new URL(url);
        URLConnection con = u.openConnection();
        con.setConnectTimeout(timeout);
        con.setReadTimeout(timeout);
        InputStream in = con.getInputStream();
        try {

            byte[] b = new byte[1024];
            int n = 0;
            while ((n = in.read(b, 0, b.length)) != -1) {
                out.write(b, 0, n);
            }
        } finally {
            try {
                out.close();
            } catch (Throwable e) {
                // ignore   
            }
            try {
                in.close();
            } catch (Throwable e) {
                // ignore   
            }
        }
    }

    public String getDetailsUrl() {
        return link.getUrl();
    }
}
