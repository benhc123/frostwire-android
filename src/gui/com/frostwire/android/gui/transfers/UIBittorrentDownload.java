package com.frostwire.android.gui.transfers;

import com.frostwire.android.core.ConfigurationManager;
import com.frostwire.android.core.Constants;
import com.frostwire.android.gui.Librarian;
import com.frostwire.android.gui.NetworkManager;
import com.frostwire.android.gui.services.Engine;
import com.frostwire.bittorrent.BTDownload;
import com.frostwire.bittorrent.BTDownloadListener;
import com.frostwire.logging.Logger;
import com.frostwire.transfers.BittorrentDownload;
import com.frostwire.transfers.TransferItem;
import com.frostwire.transfers.TransferState;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * This proxy class is necessary to handle android specific UI actions different to other
 * platforms.
 *
 * @author gubatron
 * @author aldenml
 */
public final class UIBittorrentDownload implements BittorrentDownload {

    private static final Logger LOG = Logger.getLogger(UIBittorrentDownload.class);

    private final TransferManager manager;
    private final BTDownload dl;

    private String displayName;
    private long size;
    private List<TransferItem> items;

    private TransferState state;

    public UIBittorrentDownload(TransferManager manager, BTDownload dl) {
        this.manager = manager;
        this.dl = dl;
        this.dl.setListener(new StatusListener());

        this.displayName = dl.getDisplayName();
        this.size = calculateSize(dl);
        this.items = calculateItems(dl);

        this.state = dl.getState();

        // TODO:BITTORRENT
        // review the logic of under what conditions we can actually start with resume
        dl.resume();
    }

    @Override
    public String getInfoHash() {
        return dl.getInfoHash();
    }

    @Override
    public int getConnectedPeers() {
        return dl.getConnectedPeers();
    }

    @Override
    public int getTotalPeers() {
        return dl.getTotalPeers();
    }

    @Override
    public int getConnectedSeeds() {
        return dl.getConnectedSeeds();
    }

    @Override
    public int getTotalSeeds() {
        return dl.getTotalSeeds();
    }

    @Override
    public boolean isPaused() {
        return dl.isPaused();
    }

    @Override
    public boolean isSeeding() {
        return dl.isSeeding();
    }

    @Override
    public boolean isFinished() {
        return dl.isFinished();
    }

    @Override
    public void pause() {
        dl.pause();
    }

    @Override
    public void resume() {
        dl.resume();
    }

    @Override
    public boolean isDownloading() {
        return dl.isDownloading();
    }

    @Override
    public boolean isUploading() {
        return dl.isUploading();
    }

    @Override
    public String getName() {
        return dl.getName();
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public File getSavePath() {
        return dl.getSavePath();
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public Date getCreated() {
        return dl.getCreated();
    }

    @Override
    public TransferState getState() {
        if (state != TransferState.ERROR) {
            state = dl.getState();
        }

        return state;
    }

    @Override
    public long getBytesReceived() {
        return dl.getBytesReceived();
    }

    @Override
    public long getBytesSent() {
        return dl.getBytesSent();
    }

    @Override
    public long getDownloadSpeed() {
        return dl.getDownloadSpeed();
    }

    @Override
    public long getUploadSpeed() {
        return dl.getUploadSpeed();
    }

    @Override
    public long getETA() {
        return dl.getETA();
    }

    @Override
    public int getProgress() {
        return dl.getProgress();
    }

    @Override
    public boolean isComplete() {
        return dl.isComplete();
    }

    @Override
    public List<TransferItem> getItems() {
        return items;
    }

    @Override
    public void remove() {
        remove(false);
    }

    @Override
    public void remove(boolean deleteData) {
        remove(false, deleteData);
    }

    @Override
    public void remove(boolean deleteTorrent, boolean deleteData) {
        try {
            dl.remove(deleteTorrent, deleteData);
            manager.remove(this);
        } catch (Throwable e) {
            state = TransferState.ERROR;
        }
    }

    private long calculateSize(BTDownload dl) {
        long size = dl.getSize();

        boolean partial = dl.isPartial();
        if (partial) {
            List<TransferItem> items = dl.getItems();

            long totalSize = 0;
            for (TransferItem item : items) {
                if (!item.isSkipped()) {
                    totalSize += item.getSize();
                }
            }

            if (totalSize > 0) {
                size = totalSize;
            }
        }

        return size;
    }

    private List<TransferItem> calculateItems(BTDownload dl) {
        List<TransferItem> items = new ArrayList<TransferItem>();

        for (TransferItem item : dl.getItems()) {
            if (!item.isSkipped()) {
                items.add(item);
            }
        }

        return items;
    }

    private void finalCleanup(Set<File> incompleteFiles) {
        for (File f : incompleteFiles) {
            try {
                if (f.exists() && !f.delete()) {
                    LOG.warn("Can't delete file: " + f);
                }
            } catch (Throwable e) {
                LOG.warn("Can't delete file: " + f + ", ex: " + e.getMessage());
            }
        }
        File saveLocation = dl.getSavePath();
        deleteEmptyDirectoryRecursive(saveLocation);
    }

    public static boolean deleteEmptyDirectoryRecursive(File directory) {
        // make sure we only delete canonical children of the parent file we
        // wish to delete. I have a hunch this might be an issue on OSX and
        // Linux under certain circumstances.
        // If anyone can test whether this really happens (possibly related to
        // symlinks), I would much appreciate it.
        String canonicalParent;
        try {
            canonicalParent = directory.getCanonicalPath();
        } catch (IOException ioe) {
            return false;
        }

        if (!directory.isDirectory())
            return false;

        boolean canDelete = true;

        File[] files = directory.listFiles();
        for (int i = 0; i < files.length; i++) {
            try {
                if (!files[i].getCanonicalPath().startsWith(canonicalParent))
                    continue;
            } catch (IOException ioe) {
                canDelete = false;
            }

            if (!deleteEmptyDirectoryRecursive(files[i])) {
                canDelete = false;
            }
        }

        return canDelete ? directory.delete() : false;
    }

    private class StatusListener implements BTDownloadListener {

        @Override
        public void update(BTDownload dl) {
            displayName = dl.getDisplayName();
            size = calculateSize(dl);
            items = calculateItems(dl);
        }

        @Override
        public void finished(BTDownload dl) {
            boolean seedFinishedTorrents = ConfigurationManager.instance().getBoolean(Constants.PREF_KEY_TORRENT_SEED_FINISHED_TORRENTS);
            boolean seedFinishedTorrentsOnWifiOnly = ConfigurationManager.instance().getBoolean(Constants.PREF_KEY_TORRENT_SEED_FINISHED_TORRENTS_WIFI_ONLY);
            boolean isDataWIFIUp = NetworkManager.instance().isDataWIFIUp();
            if (!seedFinishedTorrents || (!isDataWIFIUp && seedFinishedTorrentsOnWifiOnly)) {
                dl.pause();
            }

            TransferManager.instance().incrementDownloadsToReview();

            // TODO:BITTORRENT
            //VuzeUtils.finalCleanup(dm.getDM()); //make sure it cleans unnecessary files (android has handpicked seeding off by default)
            Engine.instance().notifyDownloadFinished(displayName, getSavePath().getAbsoluteFile());
            Librarian.instance().scan(getSavePath().getAbsoluteFile());
        }

        @Override
        public void removed(BTDownload dl, Set<File> incompleteFiles) {
            finalCleanup(incompleteFiles);
        }
    }
}
