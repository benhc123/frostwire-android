/*
 * Created by Angel Leon (@gubatron), Alden Torres (aldenml)
 * Copyright (c) 2011-2013, FrostWire(R). All rights reserved.
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

import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import com.frostwire.android.core.ConfigurationManager;
import com.frostwire.android.core.Constants;
import com.frostwire.android.core.FileDescriptor;
import com.frostwire.android.gui.Librarian;
import com.frostwire.android.gui.NetworkManager;
import com.frostwire.android.gui.Peer;
import com.frostwire.android.gui.services.Engine;
import com.frostwire.android.gui.util.SystemUtils;
import com.frostwire.bittorrent.BTDownload;
import com.frostwire.bittorrent.BTEngine;
import com.frostwire.bittorrent.BTEngineListener;
import com.frostwire.jlibtorrent.Sha1Hash;
import com.frostwire.jlibtorrent.TorrentHandle;
import com.frostwire.logging.Logger;
import com.frostwire.search.HttpSearchResult;
import com.frostwire.search.SearchResult;
import com.frostwire.search.soundcloud.SoundcloudSearchResult;
import com.frostwire.search.torrent.TorrentCrawledSearchResult;
import com.frostwire.search.torrent.TorrentSearchResult;
import com.frostwire.search.youtube.YouTubeCrawledSearchResult;
import com.frostwire.transfers.BittorrentDownload;
import com.frostwire.transfers.DownloadTransfer;
import com.frostwire.transfers.Transfer;
import com.frostwire.transfers.UploadTransfer;
import com.frostwire.util.StringUtils;
import com.frostwire.uxstats.UXAction;
import com.frostwire.uxstats.UXStats;
import com.frostwire.vuze.*;
import com.frostwire.vuze.VuzeManager.LoadTorrentsListener;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author gubatron
 * @author aldenml
 */
public final class TransferManager implements VuzeKeys {

    private static final Logger LOG = Logger.getLogger(TransferManager.class);

    private final List<DownloadTransfer> downloads;
    private final List<UploadTransfer> uploads;
    private final List<BittorrentDownload> bittorrentDownloads;

    private int downloadsToReview;

    private final Object alreadyDownloadingMonitor = new Object();

    private volatile static TransferManager instance;

    private OnSharedPreferenceChangeListener preferenceListener;

    public static TransferManager instance() {
        if (instance == null) {
            instance = new TransferManager();
        }
        return instance;
    }

    private TransferManager() {
        registerPreferencesChangeListener();

        this.downloads = new CopyOnWriteArrayList<DownloadTransfer>();
        this.uploads = new CopyOnWriteArrayList<UploadTransfer>();
        this.bittorrentDownloads = new CopyOnWriteArrayList<BittorrentDownload>();

        this.downloadsToReview = 0;

        loadTorrents();
    }

    public List<Transfer> getTransfers() {
        List<Transfer> transfers = new ArrayList<Transfer>();

        if (downloads != null) {
            transfers.addAll(downloads);
        }

        if (uploads != null) {
            transfers.addAll(uploads);
        }

        if (bittorrentDownloads != null) {
            transfers.addAll(bittorrentDownloads);
        }

        return transfers;
    }

    private boolean alreadyDownloading(String detailsUrl) {
        synchronized (alreadyDownloadingMonitor) {
            for (DownloadTransfer dt : downloads) {
                if (dt.isDownloading()) {
                    // TODO:BITTORRENT
                    //if (dt.getDetailsUrl() != null && dt.getDetailsUrl().equals(detailsUrl)) {
                    //    return true;
                    //}
                }
            }
        }
        return false;
    }

    private boolean alreadyDownloadingByInfoHash(String infohash) {
        synchronized (alreadyDownloadingMonitor) {
            for (BittorrentDownload bt : bittorrentDownloads) {
                if (bt.getInfoHash().equalsIgnoreCase(infohash)) {
                    return true;
                }
            }
        }
        return false;
    }


    public TransferResult download(SearchResult sr) {
        TransferResult r = TransferResult.ERROR;

        if (alreadyDownloading(sr.getDetailsUrl())) {
            r = TransferResult.DUPLICATED;
        }

        if (sr instanceof TorrentSearchResult) {
            r = newBittorrentDownload((TorrentSearchResult) sr);
        } else if (sr instanceof HttpSlideSearchResult) {
            r = newHttpDownload((HttpSlideSearchResult) sr);
        } else if (sr instanceof YouTubeCrawledSearchResult) {
            r = newYouTubeDownload((YouTubeCrawledSearchResult) sr);
        } else if (sr instanceof SoundcloudSearchResult) {
            r = newSoundcloudDownload((SoundcloudSearchResult) sr);
        } else if (sr instanceof HttpSearchResult) {
            r = newHttpDownload((HttpSearchResult) sr);
        }

        // TODO:BITTORRENT
//        if (isBittorrentDownloadAndMobileDataSavingsOn(transfer)) {
//            //give it time to get to a pausable state.
//            try { Thread.sleep(5000);  } catch (Throwable t) { /*meh*/ }
//            enqueueTorrentTransfer(transfer);
//            //give it time to stop before onPostExecute
//            try { Thread.sleep(5000);  } catch (Throwable t) { /*meh*/ }
//        }

        return r;
    }

    // TODO:BITTORRENT
//    private void enqueueTorrentTransfer(DownloadTransfer transfer) {
//        if (transfer instanceof AzureusBittorrentDownload) {
//            AzureusBittorrentDownload btDownload = (AzureusBittorrentDownload) transfer;
//            btDownload.enqueue();
//        } else if (transfer instanceof TorrentFetcherDownload) {
//            TorrentFetcherDownload btDownload = (TorrentFetcherDownload) transfer;
//            // TODO:BITTORRENT
//            //btDownload.enqueue();
//        }
//    }


    public DownloadTransfer download(Peer peer, FileDescriptor fd) {
        PeerHttpDownload download = new PeerHttpDownload(this, peer, fd);

        if (alreadyDownloading(download.getDetailsUrl())) {
            return new ExistingDownload();
        }

        downloads.add(download);
        download.start();

        UXStats.instance().log(UXAction.WIFI_SHARING_DOWNLOAD);

        return download;
    }

    public PeerHttpUpload upload(FileDescriptor fd) {
        PeerHttpUpload upload = new PeerHttpUpload(this, fd);
        uploads.add(upload);
        return upload;
    }

    public void clearComplete() {
        List<Transfer> transfers = getTransfers();

        for (Transfer transfer : transfers) {
            if (transfer != null && transfer.isComplete()) {
                if (transfer instanceof BittorrentDownload) {
                    BittorrentDownload bd = (BittorrentDownload) transfer;
                    // TODO:BITTORRENT
//                    if (bd != null && bd.isResumable()) {
//                        bd.remove();
//                    }
                } else {
                    transfer.remove();
                }
            }
        }
    }

    public int getActiveDownloads() {
        int count = 0;

        for (BittorrentDownload d : bittorrentDownloads) {
            if (!d.isComplete() && d.isDownloading()) {
                count++;
            }
        }

        for (DownloadTransfer d : downloads) {
            if (!d.isComplete() && d.isDownloading()) {
                count++;
            }
        }

        return count;
    }

    public int getActiveUploads() {
        int count = 0;

        for (BittorrentDownload d : bittorrentDownloads) {
            if (!d.isComplete() && d.isSeeding()) {
                count++;
            }
        }

        for (UploadTransfer u : uploads) {
            if (!u.isComplete() && u.isUploading()) {
                count++;
            }
        }

        return count;
    }

    public long getDownloadsBandwidth() {
        long torrenDownloadsBandwidth = VuzeManager.getInstance().getDataReceiveRate();

        long peerDownloadsBandwidth = 0;
        for (DownloadTransfer d : downloads) {
            peerDownloadsBandwidth += d.getDownloadSpeed() / 1000;
        }

        return torrenDownloadsBandwidth + peerDownloadsBandwidth;
    }

    public double getUploadsBandwidth() {
        long torrenUploadsBandwidth = VuzeManager.getInstance().getDataSendRate();

        long peerUploadsBandwidth = 0;
        for (UploadTransfer u : uploads) {
            peerUploadsBandwidth += u.getUploadSpeed() / 1000;
        }

        return torrenUploadsBandwidth + peerUploadsBandwidth;
    }

    public int getDownloadsToReview() {
        return downloadsToReview;
    }

    public void incrementDownloadsToReview() {
        downloadsToReview++;
    }

    public void clearDownloadsToReview() {
        downloadsToReview = 0;
    }

    public void stopSeedingTorrents() {
        for (BittorrentDownload d : bittorrentDownloads) {
            if (d.isSeeding() || d.isComplete()) {
                d.pause();
            }
        }
    }

    public void loadTorrents() {
        bittorrentDownloads.clear();

        boolean stop = false;
        if (!ConfigurationManager.instance().getBoolean(Constants.PREF_KEY_TORRENT_SEED_FINISHED_TORRENTS)) {
            stop = true;
        } else {
            if (!NetworkManager.instance().isDataWIFIUp() && ConfigurationManager.instance().getBoolean(Constants.PREF_KEY_TORRENT_SEED_FINISHED_TORRENTS_WIFI_ONLY)) {
                stop = true;
            }
        }

        BTEngine engine = BTEngine.getInstance();

        engine.setListener(new BTEngineListener() {
            @Override
            public void downloadAdded(BTDownload dl) {
                String name = dl.getName();
                if (name != null && name.contains("fetchMagnet - ")) {
                    return;
                }

                bittorrentDownloads.add(new UIBittorrentDownload(TransferManager.this, dl));
            }
        });

        engine.restoreDownloads();
    }

    boolean remove(Transfer transfer) {
        if (transfer instanceof BittorrentDownload) {
            return bittorrentDownloads.remove(transfer);
        } else if (transfer instanceof DownloadTransfer) {
            return downloads.remove(transfer);
        } else if (transfer instanceof UploadTransfer) {
            return uploads.remove(transfer);
        }

        return false;
    }

    public void pauseTorrents() {
        for (BittorrentDownload d : bittorrentDownloads) {
            d.pause();
        }
    }

    public void downloadTorrent(String uri) {
        try {
            URI u = URI.create(uri);

            BittorrentDownload download = null;

            if (u.getScheme().equalsIgnoreCase("file")) {
                BTEngine.getInstance().download(new File(u.getPath()), null);
            } else if (u.getScheme().equalsIgnoreCase("http") || u.getScheme().equalsIgnoreCase("magnet")) {
                BittorrentDownload dl = new TorrentFetcherDownload(this, new TorrentUrlInfo(uri.toString()));
                if (dl != null) {
                    bittorrentDownloads.add(dl);
                }
            } else {
                //download = new InvalidBittorrentDownload(R.string.torrent_scheme_download_not_supported);
            }
            // TODO:BITTORRENT
//            if (!(download instanceof InvalidBittorrentDownload)) {
//                if ((download instanceof AzureusBittorrentDownload && !alreadyDownloadingByInfoHash(download.getInfoHash())) ||
//                        (download instanceof TorrentFetcherDownload && !alreadyDownloading(uri.toString()))) {
//                    if (!bittorrentDownloads.contains(download)) {
//                        bittorrentDownloads.add(download);
//
//                        if (isBittorrentDownloadAndMobileDataSavingsOn(download)) {
//                            //give it time to get to a pausable state.
//                            try {
//                                Thread.sleep(5000);
//                            } catch (Throwable t) { /*meh*/ }
//                            enqueueTorrentTransfer(download);
//                            //give it time to stop before onPostExecute
//                            try {
//                                Thread.sleep(5000);
//                            } catch (Throwable t) { /*meh*/ }
//                        }
//                    }
//                }
//            }
        } catch (Throwable e) {
            LOG.warn("Error creating download from uri: " + uri);
        }
    }

    private static BittorrentDownload createBittorrentDownload(TransferManager manager, TorrentSearchResultInfo info) {
        BTEngine engine = BTEngine.getInstance();

        TorrentHandle th = engine.getSession().findTorrent(new Sha1Hash(info.getInfoHash()));

        if (th == null) { // new download, I need to download the torrent
            return new TorrentFetcherDownload(manager, info);
        } else {
            engine.download(th.getTorrentInfo(), null, info.getSelection());
        }

        return null;
    }

    private static BittorrentDownload createBittorrentDownload(TransferManager manager, TorrentSearchResult sr) {
        TorrentSearchResultInfo info = new TorrentSearchResultInfo(sr);
        if (StringUtils.isNullOrEmpty(sr.getHash())) {
            return new TorrentFetcherDownload(manager, info);
        } else {
            return createBittorrentDownload(manager, info);
        }
    }

    private TransferResult newBittorrentDownload(TorrentSearchResult sr) {
        try {
            if (sr instanceof TorrentCrawledSearchResult) {
                BTEngine.getInstance().download((TorrentCrawledSearchResult) sr, null);
            } else {

                BittorrentDownload dl = createBittorrentDownload(this, sr);

                if (dl != null) {
                    bittorrentDownloads.add(dl);
                }
            }

            return TransferResult.SUCCESS;
        } catch (Throwable e) {
            LOG.warn("Error creating download from search result: " + sr);
            return TransferResult.ERROR;
        }
    }

    private TransferResult newHttpDownload(HttpSlideSearchResult sr) {
        HttpDownload download = new HttpDownload(this, sr.getDownloadLink());

        downloads.add(download);
        download.start();

        return TransferResult.SUCCESS;
    }

    private TransferResult newYouTubeDownload(YouTubeCrawledSearchResult sr) {
        YouTubeDownload download = new YouTubeDownload(this, sr);

        downloads.add(download);
        download.start();

        return TransferResult.SUCCESS;
    }

    private TransferResult newSoundcloudDownload(SoundcloudSearchResult sr) {
        SoundcloudDownload download = new SoundcloudDownload(this, sr);

        downloads.add(download);
        download.start();

        return TransferResult.SUCCESS;
    }

    private TransferResult newHttpDownload(HttpSearchResult sr) {
        HttpDownload download = new HttpDownload(this, new HttpSearchResultDownloadLink(sr));

        downloads.add(download);
        download.start();

        return TransferResult.SUCCESS;
    }

    private boolean isBittorrentDownload(DownloadTransfer transfer) {
        return transfer instanceof BittorrentDownload;
    }

    public boolean isBittorrentDownloadAndMobileDataSavingsOn(DownloadTransfer transfer) {
        return isBittorrentDownload(transfer) &&
                NetworkManager.instance().isDataMobileUp() &&
                !ConfigurationManager.instance().getBoolean(Constants.PREF_KEY_NETWORK_USE_MOBILE_DATA);
    }

    public boolean isBittorrentDownloadAndMobileDataSavingsOff(DownloadTransfer transfer) {
        return isBittorrentDownload(transfer) &&
                NetworkManager.instance().isDataMobileUp() &&
                ConfigurationManager.instance().getBoolean(Constants.PREF_KEY_NETWORK_USE_MOBILE_DATA);
    }

    public boolean isBittorrentDisconnected() {
        return Engine.instance().isStopped() || Engine.instance().isStopping() || Engine.instance().isDisconnected();
    }

    public void resumeResumableTransfers() {
        List<Transfer> transfers = getTransfers();

        for (Transfer t : transfers) {
            if (t instanceof BittorrentDownload) {
                BittorrentDownload bt = (BittorrentDownload) t;
                if (!bt.isPaused()) {
                    bt.resume();
                }
            }
        }
    }

    /**
     * Stops all HttpDownloads (Cloud and Wi-Fi)
     */
    public void stopHttpTransfers() {
        List<Transfer> transfers = new ArrayList<Transfer>();
        transfers.addAll(downloads);
        transfers.addAll(uploads);

        for (Transfer t : transfers) {
            if (t instanceof DownloadTransfer) {
                DownloadTransfer d = (DownloadTransfer) t;
                if (!d.isComplete() && d.isDownloading()) {
                    d.remove();
                }
            } else if (t instanceof UploadTransfer) {
                UploadTransfer u = (UploadTransfer) t;

                if (!u.isComplete() && u.isUploading()) {
                    u.remove();
                }
            }
        }
    }

    private void registerPreferencesChangeListener() {
        preferenceListener = new OnSharedPreferenceChangeListener() {
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                if (key.equals(Constants.PREF_KEY_TORRENT_MAX_DOWNLOAD_SPEED)) {
                    setAzureusParameter(MAX_DOWNLOAD_SPEED);
                } else if (key.equals(Constants.PREF_KEY_TORRENT_MAX_UPLOAD_SPEED)) {
                    setAzureusParameter(MAX_UPLOAD_SPEED);
                } else if (key.equals(Constants.PREF_KEY_TORRENT_MAX_DOWNLOADS)) {
                    setAzureusParameter(MAX_DOWNLOADS);
                } else if (key.equals(Constants.PREF_KEY_TORRENT_MAX_UPLOADS)) {
                    setAzureusParameter(MAX_UPLOADS);
                } else if (key.equals(Constants.PREF_KEY_TORRENT_MAX_TOTAL_CONNECTIONS)) {
                    setAzureusParameter(MAX_TOTAL_CONNECTIONS);
                } else if (key.equals(Constants.PREF_KEY_TORRENT_MAX_TORRENT_CONNECTIONS)) {
                    setAzureusParameter(MAX_TORRENT_CONNECTIONS);
                }
            }
        };
        ConfigurationManager.instance().registerOnPreferenceChange(preferenceListener);
    }

    private void setAzureusParameter(String key) {
        VuzeManager.getInstance().setParameter(key, ConfigurationManager.instance().getLong(key));
    }

    public enum TransferResult {
        SUCCESS,
        DUPLICATED,
        ERROR
    }
}
