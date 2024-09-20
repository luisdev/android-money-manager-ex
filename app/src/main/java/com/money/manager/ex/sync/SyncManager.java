/*
 * Copyright (C) 2012-2018 The Android Money Manager Ex Project Team
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.money.manager.ex.sync;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Messenger;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.money.manager.ex.Constants;
import com.money.manager.ex.MmexApplication;
import com.money.manager.ex.R;
import com.money.manager.ex.core.IntentFactory;
import com.money.manager.ex.core.UIHelper;
import com.money.manager.ex.core.database.DatabaseManager;
import com.money.manager.ex.home.DatabaseMetadata;
import com.money.manager.ex.home.DatabaseMetadataFactory;
import com.money.manager.ex.home.MainActivity;
import com.money.manager.ex.home.RecentDatabasesProvider;
import com.money.manager.ex.settings.AppSettings;
import com.money.manager.ex.settings.SyncPreferences;
import com.money.manager.ex.utils.MmxDatabaseUtils;
import com.money.manager.ex.utils.MmxDate;
import com.money.manager.ex.utils.MmxDateTimeUtils;
import com.money.manager.ex.utils.NetworkUtils;

import java.io.File;
import java.util.HashMap;

import javax.inject.Inject;

import dagger.Lazy;
import timber.log.Timber;

/**
 * Class used to manage the database file synchronization process.
 */
public class SyncManager {

    public static int scheduledJobId = Constants.NOT_SET;

    @Inject Lazy<MmxDateTimeUtils> dateTimeUtilsLazy;

    @Inject
    public SyncManager(Context context) {
        mContext = context;

        MmexApplication.getApp().iocComponent.inject(this);
    }

    @Inject Lazy<RecentDatabasesProvider> mDatabases;

    private final Context mContext;
    private SyncPreferences mPreferences;

    public void abortScheduledUpload() {
        Timber.d("Aborting scheduled sync");

        PendingIntent pendingIntent = getPendingIntentForDelayedUpload();
        getAlarmManager().cancel(pendingIntent);
/*
        if (scheduledJobId != Constants.NOT_SET) {
            WorkManager.getInstance(getContext()).cancelWorkById(scheduledJobId);
        }
*/
    }

    public Context getContext() {
        return mContext;
    }

    /**
     * Performs checks if automatic synchronization should be performed.
     * Used also on immediate upload after file changed.
     * @return boolean indicating if auto sync should be done.
     */
    public boolean canSync() {
        // check if online
        if (!isActive()) return false;

        // should we sync only on wifi?
        if (getPreferences().shouldSyncOnlyOnWifi()) {
            Timber.d("Preferences set to sync on WiFi only.");

            // check if we are on WiFi connection.
            NetworkUtils network = new NetworkUtils(getContext());
            if (!network.isOnWiFi()) {
                Timber.i("Not on WiFi connection. Not synchronizing.");
                return false;
            }
        }

        return true;
    }

    /**
     * Gets last saved datetime of the remote file modification from the preferences.
     * Get the saved date from Database Metadata.
     * @param remotePath file name, key
     * @return date of last modification
     */
    @Deprecated
    public MmxDate getRemoteLastModifiedDatePreferenceFor(String remotePath) {
        String dateString = getPreferences().get(remotePath, null);
        if (TextUtils.isEmpty(dateString)) return null;

        return new MmxDate(dateString, Constants.ISO_8601_FORMAT);
    }

//    public Date getModificationDateFrom(CloudMetaData remoteFile) {
//        return new MmxDate(remoteFile.getModifiedAt()).toDate();
//    }

    public String getRemotePath() {
        DatabaseMetadata db = getDatabases().getCurrent();
        if (db == null) return null;

        String fileName = db.remotePath;
        return fileName;
    }

    public void invokeSyncService(String action) {
        // Validation.
        String remoteFile = getRemotePath();
        // We need a value in remote file name preferences.
        if (TextUtils.isEmpty(remoteFile)) return;

        // Action

        AlertDialog progressDialog = null;
        // Create progress dialog only if called from the UI.
        if ((getContext() instanceof AppCompatActivity)) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext()); // Replace with 'getContext()' if in fragment
            LayoutInflater inflater = LayoutInflater.from(getContext());

            // Inflate the custom layout
            View view = inflater.inflate(R.layout.progress_dialog, null);
            builder.setView(view);
            builder.setCancelable(false);  // Disable cancel if needed

            progressDialog = builder.create();

            progressDialog.show();
        }

        DatabaseMetadata current = mDatabases.get().getCurrent();
        String localFile = getDatabases().getCurrent().localPath;
        Messenger messenger = null;
        if (getContext() instanceof AppCompatActivity) {
            // Messenger handles received messages from the sync service. Can run only in a looper thread.
            messenger = new Messenger(new SyncServiceMessageHandler(getContext(), progressDialog));
        }

        Intent syncServiceIntent = IntentFactory.getSyncServiceIntent(getContext(), action, current.localPath, current.remotePath, messenger);
        // start service
        SyncService.enqueueWork(getContext(), syncServiceIntent);

        // Reset any other scheduled uploads as the current operation will modify the files.
        // abortScheduledUpload();

        // The messages from the service are received via messenger.
    }

    /**
     * Indicates whether synchronization service can be performed
     * remote file is set.
     * @return A boolean indicating that sync service can be performed.
     */
    public boolean isActive() {
        // network is online.
        NetworkUtils networkUtils = new NetworkUtils(getContext());
        if (!networkUtils.isOnline()) {
            Timber.i("Not online.");
            return false;
        }

        // Remote file must be set.
        return !TextUtils.isEmpty(getRemotePath());
    }

    /**
     * Resets the synchronization preferences and cache.
     */
    void resetPreferences() {
        getPreferences().clear();
    }

    public void setSyncInterval(int minutes) {
        getPreferences().setSyncInterval(minutes);
    }

    public void startSyncServiceHeartbeat() {
        Intent intent = new Intent(getContext(), SyncSchedulerBroadcastReceiver.class);
        intent.setAction(SyncSchedulerBroadcastReceiver.ACTION_START);
        getContext().sendBroadcast(intent);

        // SyncSchedulerBroadcastReceiver does not receive a broadcast when using LocalManager!
//        LocalBroadcastManager.getInstance(getContext()).sendBroadcast(intent);

        // todo migrate to JobManager.
//        new JobRequest.Builder(SyncConstants.INTENT_ACTION_SYNC)
//                .setPeriodic()
//                .build()
//                .schedule();
    }

    public void stopSyncServiceAlarm() {
        Intent intent = new Intent(mContext, SyncSchedulerBroadcastReceiver.class);
        intent.setAction(SyncSchedulerBroadcastReceiver.ACTION_STOP);
        getContext().sendBroadcast(intent);
        // SyncSchedulerBroadcastReceiver does not receive a broadcast when using LocalManager!
//        LocalBroadcastManager.getInstance(getContext()).sendBroadcast(intent);

        // todo use JobManager.
    }

    /**
     * Synchronization using job manager.
     */
//    public void triggerSyncJob() {
//        // validations
//
//        if (!isActive())  return;
//
//        // Make sure that the current database is also the one linked in the cloud.
//        String localPath = new DatabaseManager(getContext()).getDatabasePath();
//        if (TextUtils.isEmpty(localPath)) {
//            new UIHelper(getContext()).showToast(R.string.filenames_differ);
//            return;
//        }
//
//        String remotePath = getRemotePath();
//        if (TextUtils.isEmpty(remotePath)) {
//            Toast.makeText(getContext(), R.string.select_remote_file, Toast.LENGTH_SHORT).show();
//            return;
//        }
//
//        // easy comparison, just by the file name.
//        if (!areFileNamesSame(localPath, remotePath)) {
//            // The current file was probably opened through Open Database.
//            Toast.makeText(getContext(), R.string.db_not_dropbox, Toast.LENGTH_LONG).show();
//            return;
//        }
//
//        // action
//
//        new JobRequest.Builder(SyncConstants.INTENT_ACTION_SYNC)
//            .setExecutionWindow(500, 1000)
//            .build()
//            .schedule();
//
//        // todo sync
//        // todo abort scheduled job, if any.
//    }

    public void triggerSynchronization() {
        if (!canSync())  return;

        // Make sure that the current database is also the one linked in the cloud.
        String localPath = new DatabaseManager(getContext()).getDatabasePath();
        if (TextUtils.isEmpty(localPath)) {
            new UIHelper(getContext()).showToast(R.string.filenames_differ);
            return;
        }

        String remotePath = getRemotePath();
        if (TextUtils.isEmpty(remotePath)) {
            Toast.makeText(getContext(), R.string.select_remote_file, Toast.LENGTH_SHORT).show();
            return;
        }

        invokeSyncService(SyncConstants.INTENT_ACTION_SYNC);
        Uri uri = Uri.parse(remotePath);
        MmexApplication.getAmplitude().track("synchronize", new HashMap() {{
            put("authority", uri.getAuthority());
            put("result", "triggerSynchronization");
        }});
    }

    public void triggerDownload() {
        invokeSyncService(SyncConstants.INTENT_ACTION_DOWNLOAD);
    }

    public void triggerUpload() {
        invokeSyncService(SyncConstants.INTENT_ACTION_UPLOAD);
    }

    /**
     * Sets the downloaded database as current. Restarts the Main Activity.
     */
    public void useDownloadedDatabase() {
        // Do this only if called from an activity.
        if (!(getContext() instanceof AppCompatActivity)) return;

        MmxDatabaseUtils dbUtils = new MmxDatabaseUtils(getContext());
        String localFile = new DatabaseManager(getContext()).getDatabasePath();

        DatabaseMetadata db = getDatabases().get(localFile);
        if (db == null) {
            db = DatabaseMetadataFactory.getInstance(localFile, getRemotePath());
        }
        boolean isDbSet = dbUtils.useDatabase(db);

        if (!isDbSet) {
            Timber.w("could not change the database");
            return;
        }

        Intent intent = IntentFactory.getMainActivityNew(getContext());
        // Send info to not check for updates as it is redundant in this case.
        intent.putExtra(MainActivity.EXTRA_SKIP_REMOTE_CHECK, true);
        getContext().startActivity(intent);
    }

    /*
        Private
     */

    /**
     * Compares the local and remote db filenames. Use for safety check before synchronization.
     * @return A boolean indicating if the filenames are the same.
     */
    private boolean areFileNamesSame(String localPath, String remotePath) {
        if (TextUtils.isEmpty(localPath)) return false;
        if (TextUtils.isEmpty(remotePath)) return false;

        File localFile = new File(localPath);
        String localName = localFile.getName();

        File remoteFile = new File(remotePath);
        String remoteName = remoteFile.getName();

        return localName.equalsIgnoreCase(remoteName);
    }

    private RecentDatabasesProvider getDatabases() {
        return mDatabases.get();
    }

    private File getExternalStorageDirectoryForSync() {
        // todo check this after refactoring the database utils.
        //MmxDatabaseUtils dbUtils = new MmxDatabaseUtils(getContext());
        DatabaseManager dbManager = new DatabaseManager(getContext());
        File folder = new File(dbManager.getDefaultDatabaseDirectory());

        // manage folder
        if (folder.exists() && folder.isDirectory() && folder.canWrite()) {
            // create a folder for remote files
            File folderSync = new File(folder + "/sync");
            // check if folder exists otherwise create
            if (!folderSync.exists()) {
                if (!folderSync.mkdirs()) return getContext().getFilesDir();
            }
            return folderSync;
        } else {
            return mContext.getFilesDir();
        }
    }

    private SyncPreferences getPreferences() {
        if (mPreferences == null) {
            mPreferences = new SyncPreferences(getContext());
        }
        return mPreferences;
    }

    private void markLocalFileChanged(boolean changed) {
        String localPath = new AppSettings(getContext()).getDatabaseSettings().getDatabasePath();
        DatabaseMetadata currentDbEntry = getDatabases().get(localPath);

        if (currentDbEntry.isLocalFileChanged == changed) return;

        currentDbEntry.isLocalFileChanged = changed;
        getDatabases().save();
    }

    /**
     * Schedule delayed upload via timer.
     */
    private void scheduleDelayedUpload() {
        PendingIntent pendingIntent = getPendingIntentForDelayedUpload();
        AlarmManager alarm = getAlarmManager();

        Timber.d("Setting delayed upload alarm.");

        // start the sync service after 30 seconds.
        alarm.set(AlarmManager.RTC_WAKEUP, new MmxDate().getMillis() + 30*1000, pendingIntent);
    }

    private PendingIntent getPendingIntentForDelayedUpload() {
        DatabaseMetadata db = getDatabases().getCurrent();

        Intent intent = new Intent(getContext(), SyncService.class);

        intent.setAction(SyncConstants.INTENT_ACTION_SYNC);

        intent.putExtra(SyncConstants.INTENT_EXTRA_LOCAL_FILE, db.localPath);
        intent.putExtra(SyncConstants.INTENT_EXTRA_REMOTE_FILE, db.remotePath);

        return PendingIntent.getService(getContext(),
                SyncConstants.REQUEST_DELAYED_SYNC,
                intent, PendingIntent.FLAG_CANCEL_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }

    private AlarmManager getAlarmManager() {
        return (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
    }
}
