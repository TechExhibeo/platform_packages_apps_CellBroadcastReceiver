/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.cellbroadcastreceiver;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Telephony;
import android.telephony.CellBroadcastMessage;
import android.telephony.TelephonyManager;
import android.telephony.SmsCbCmasInfo;
import android.telephony.SmsCbEtwsInfo;
import android.telephony.SmsCbLocation;
import android.telephony.SmsCbMessage;
import android.telephony.SubscriptionManager;
import android.util.Log;
import com.android.internal.telephony.PhoneConstants;

import java.util.ArrayList;
import java.util.HashSet;
import android.database.Cursor;
import java.util.Iterator;

import android.preference.PreferenceManager;
import android.content.SharedPreferences;

/**
 * This service manages the display and animation of broadcast messages.
 * Emergency messages display with a flashing animated exclamation mark icon,
 * and an alert tone is played when the alert is first shown to the user
 * (but not when the user views a previously received broadcast).
 */
public class CellBroadcastAlertService extends Service {
    private static final String TAG = "CellBroadcastAlertService";

    /** Intent action to display alert dialog/notification, after verifying the alert is new. */
    static final String SHOW_NEW_ALERT_ACTION = "cellbroadcastreceiver.SHOW_NEW_ALERT";

    /** Use the same notification ID for non-emergency alerts. */
    static final int NOTIFICATION_ID = 1;

    /** Sticky broadcast for latest area info broadcast received. */
    static final String CB_AREA_INFO_RECEIVED_ACTION =
            "android.cellbroadcastreceiver.CB_AREA_INFO_RECEIVED";
    /** system property to enable/disable broadcast duplicate detecion.  */
    private static final String CB_DUP_DETECTION = "persist.cb.dup_detection";

    /** Check for system property to enable/disable duplicate detection.  */
    static boolean mUseDupDetection = SystemProperties.getBoolean(CB_DUP_DETECTION, true);

    /** Channel 50 Cell Broadcast. */
    static final int CB_CHANNEL_50 = 50;

    /** Channel 60 Cell Broadcast. */
    static final int CB_CHANNEL_60 = 60;
    private static int TIME12HOURS = 12*60*60*1000;
    private boolean mDuplicateCheckDatabase = false;

    @Override
    public void onCreate() {
        super.onCreate();
        mDuplicateCheckDatabase = getResources().getBoolean(
                R.bool.config_regional_wea_duplicated_check_database);
        if (mDuplicateCheckDatabase) {
            initHalfDayCmasList();
        }
    }

    /**
     *  Container for service category, serial number, location, and message body hash code for
     *  duplicate message detection.
     */
    private static final class MessageServiceCategoryAndScope {
        private final int mServiceCategory;
        private final int mSerialNumber;
        private final SmsCbLocation mLocation;
        private final int mBodyHash;
        private final SmsCbEtwsInfo mEtwsWarningInfo;
        private final long mDeliveryTime;
        private final String mMessageBody;

        MessageServiceCategoryAndScope(int serviceCategory, int serialNumber,
                SmsCbLocation location, int bodyHash) {
            mServiceCategory = serviceCategory;
            mSerialNumber = serialNumber;
            mLocation = location;
            mBodyHash = bodyHash;
            mEtwsWarningInfo = null;
            mMessageBody = null;
            mDeliveryTime = 0;
        }

        MessageServiceCategoryAndScope(int serviceCategory, int serialNumber,
                SmsCbLocation location, int bodyHash, SmsCbEtwsInfo etwsWarningInfo) {
            mServiceCategory = serviceCategory;
            mSerialNumber = serialNumber;
            mLocation = location;
            mBodyHash = bodyHash;
            mEtwsWarningInfo = etwsWarningInfo;
            mMessageBody = null;
            mDeliveryTime = 0;
        }

        MessageServiceCategoryAndScope(int serviceCategory, int serialNumber,
                SmsCbLocation location, String messageBody, long deliveryTime, int bodyHash) {
            mServiceCategory = serviceCategory;
            mSerialNumber = serialNumber;
            mLocation = location;
            mMessageBody = messageBody;
            mDeliveryTime = deliveryTime;
            mBodyHash = bodyHash;
            mEtwsWarningInfo = null;
        }

        @Override
        public int hashCode() {
            if (mEtwsWarningInfo != null) {
                return mEtwsWarningInfo.hashCode() + mLocation.hashCode() + 5 * mServiceCategory
                        + 7 * mSerialNumber + 13 * mBodyHash;
            }
            return mLocation.hashCode() + 5 * mServiceCategory + 7 * mSerialNumber + 13 * mBodyHash;
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (o instanceof MessageServiceCategoryAndScope) {
                MessageServiceCategoryAndScope other = (MessageServiceCategoryAndScope) o;
                if (mEtwsWarningInfo == null && other.mEtwsWarningInfo != null) {
                    return false;
                } else if (mEtwsWarningInfo != null && other.mEtwsWarningInfo == null) {
                    return false;
                } else if (mEtwsWarningInfo != null && other.mEtwsWarningInfo != null
                        && !mEtwsWarningInfo.equals(other.mEtwsWarningInfo)) {
                    return false;
                }
                return (mServiceCategory == other.mServiceCategory &&
                        mSerialNumber == other.mSerialNumber &&
                        mLocation.equals(other.mLocation) &&
                        mBodyHash == other.mBodyHash &&
                        mMessageBody.equals(other.mMessageBody));
            }
            return false;
        }

        @Override
        public String toString() {
            return "{mServiceCategory: " + mServiceCategory + " serial number: " + mSerialNumber
                    + " location: " + mLocation.toString() + " mEtwsWarningInfo: "
                    + (mEtwsWarningInfo == null ? "NULL" : mEtwsWarningInfo.toString())
                    + " body hash: " + mBodyHash +'}';
        }
    }

    /** Cache of received message IDs, for duplicate message detection. */
    private static final HashSet<MessageServiceCategoryAndScope> sCmasIdSet =
            new HashSet<MessageServiceCategoryAndScope>(8);

    /** Maximum number of message IDs to save before removing the oldest message ID. */
    private static final int MAX_MESSAGE_ID_SIZE = 65535;

    /** List of message IDs received, for removing oldest ID when max message IDs are received. */
    private static final ArrayList<MessageServiceCategoryAndScope> sCmasIdList =
            new ArrayList<MessageServiceCategoryAndScope>(8);

    /** Index of message ID to replace with new message ID when max message IDs are received. */
    private static int sCmasIdListIndex = 0;
    /** List of message IDs received for recent 12 hours. */
    private static final ArrayList<MessageServiceCategoryAndScope> s12HIdList =
        new ArrayList<MessageServiceCategoryAndScope>(8);

    private void initHalfDayCmasList() {
        long now = System.currentTimeMillis();
        // This is used to query necessary fields from cmas table
        // which are related duplicate check
        // for example receive date, cmas id and so on
        String[] project = new String[] {
            Telephony.CellBroadcasts.PLMN,
            Telephony.CellBroadcasts.LAC,
            Telephony.CellBroadcasts.CID,
            Telephony.CellBroadcasts.DELIVERY_TIME,
            Telephony.CellBroadcasts.SERVICE_CATEGORY,
            Telephony.CellBroadcasts.SERIAL_NUMBER,
            Telephony.CellBroadcasts.MESSAGE_BODY};
        Cursor cursor = getApplicationContext().getContentResolver().query(
                Telephony.CellBroadcasts.CONTENT_URI,project,
                Telephony.CellBroadcasts.DELIVERY_TIME + ">?",
                new String[]{now - TIME12HOURS + ""},
                Telephony.CellBroadcasts.DELIVERY_TIME + " DESC");
        if (s12HIdList != null) {
            s12HIdList.clear();
        }
        MessageServiceCategoryAndScope newCmasId;
        int serviceCategory;
        int serialNumber;
        String messageBody;
        long deliveryTime;
        if(cursor != null){
            int plmnColumn = cursor.getColumnIndex(Telephony.CellBroadcasts.PLMN);
            int lacColumn = cursor.getColumnIndex(Telephony.CellBroadcasts.LAC);
            int cidColumn = cursor.getColumnIndex(Telephony.CellBroadcasts.CID);
            int serviceCategoryColumn = cursor.getColumnIndex(
                    Telephony.CellBroadcasts.SERVICE_CATEGORY);
            int serialNumberColumn = cursor.getColumnIndex(
                    Telephony.CellBroadcasts.SERIAL_NUMBER);
            int messageBodyColumn = cursor.getColumnIndex(Telephony.CellBroadcasts.MESSAGE_BODY);
            int deliveryTimeColumn = cursor.getColumnIndex(
                    Telephony.CellBroadcasts.DELIVERY_TIME);
            while(cursor.moveToNext()){
                String plmn = getStringColumn(plmnColumn, cursor);
                int lac = getIntColumn(lacColumn, cursor);
                int cid = getIntColumn(cidColumn, cursor);
                SmsCbLocation location = new SmsCbLocation(plmn, lac, cid);
                serviceCategory = getIntColumn(serviceCategoryColumn, cursor);
                serialNumber = getIntColumn(serialNumberColumn, cursor);
                messageBody = getStringColumn(messageBodyColumn, cursor);
                deliveryTime = getLongColumn(deliveryTimeColumn, cursor);
                newCmasId = new MessageServiceCategoryAndScope(
                        serviceCategory, serialNumber, location, messageBody,
                        deliveryTime, messageBody.hashCode());
                s12HIdList.add(newCmasId);
            }
        }
        if(cursor != null){
            cursor.close();
        }
    }

    private boolean isDuplicated(SmsCbMessage message) {
        if(!mDuplicateCheckDatabase) {
            return false ;
        }
        final CellBroadcastMessage cbm = new CellBroadcastMessage(message);
        long lastestDeliveryTime = cbm.getDeliveryTime();
        int hashCode = message.isEtwsMessage() ? message.getMessageBody().hashCode() : 0;
        MessageServiceCategoryAndScope newCmasId = new MessageServiceCategoryAndScope(
                message.getServiceCategory(), message.getSerialNumber(),
                message.getLocation(), message.getMessageBody(), lastestDeliveryTime, hashCode);
        Iterator<MessageServiceCategoryAndScope> iterator = s12HIdList.iterator();
        ArrayList<MessageServiceCategoryAndScope> tempMessageList =
                new ArrayList<MessageServiceCategoryAndScope>();
        boolean duplicatedMessage = false;
        while(iterator.hasNext()){
            MessageServiceCategoryAndScope tempMessage =
                    (MessageServiceCategoryAndScope)iterator.next();
            boolean moreThan12Hour = (lastestDeliveryTime - tempMessage.mDeliveryTime >= TIME12HOURS);
            if (moreThan12Hour) {
                break;
            } else {
                tempMessageList.add(tempMessage);
                if (tempMessage.equals(newCmasId)) {
                    duplicatedMessage = true;
                    break;
                }
            }
        }
        if (duplicatedMessage) {
            if (tempMessageList != null) {
                tempMessageList.clear();
                tempMessageList = null;
            }
            return true;
        } else {
            if (s12HIdList != null) {
                s12HIdList.clear();
            }
            if (tempMessageList != null) {
                s12HIdList.addAll(tempMessageList);
                tempMessageList.clear();
                tempMessageList = null;
            }
            s12HIdList.add(0, newCmasId);
        }
        return false;
    }

    private String getStringColumn (int column, Cursor cursor) {
        if (column != -1 && !cursor.isNull(column)) {
            return cursor.getString(column);
        } else {
            return null;
        }
    }

    private int getIntColumn (int column, Cursor cursor) {
        if (column != -1 && !cursor.isNull(column)) {
            return cursor.getInt(column);
        } else {
            return -1;
        }
    }

    private long getLongColumn (int column, Cursor cursor) {
        if (column != -1 && !cursor.isNull(column)) {
            return cursor.getLong(column);
        } else {
            return -1;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        if (Telephony.Sms.Intents.SMS_EMERGENCY_CB_RECEIVED_ACTION.equals(action) ||
                Telephony.Sms.Intents.SMS_CB_RECEIVED_ACTION.equals(action)) {
            handleCellBroadcastIntent(intent);
        } else if (SHOW_NEW_ALERT_ACTION.equals(action)) {
            try {
                if (UserHandle.myUserId() ==
                        ActivityManagerNative.getDefault().getCurrentUser().id) {
                    showNewAlert(intent);
                } else {
                    Log.d(TAG,"Not active user, ignore the alert display");
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        } else {
            Log.e(TAG, "Unrecognized intent action: " + action);
        }
        return START_NOT_STICKY;
    }

    private void handleCellBroadcastIntent(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            Log.e(TAG, "received SMS_CB_RECEIVED_ACTION with no extras!");
            return;
        }

        SmsCbMessage message = (SmsCbMessage) extras.get("message");

        if (message == null) {
            Log.e(TAG, "received SMS_CB_RECEIVED_ACTION with no message extra");
            return;
        }

        final CellBroadcastMessage cbm = new CellBroadcastMessage(message);
        int subId = intent.getExtras().getInt(PhoneConstants.SUBSCRIPTION_KEY);
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            cbm.setSubId(subId);
        } else {
            Log.e(TAG, "Invalid subscription id");
        }
        if (!isMessageEnabledByUser(cbm)) {
            Log.d(TAG, "ignoring alert of type " + cbm.getServiceCategory() +
                    " by user preference");
            return;
        }
        if (getResources().getBoolean(R.bool.config_regional_disable_cb_message))
            return;

        // If this is an ETWS message, then we want to include the body message to be a factor for
        // duplicate detection. We found that some Japanese carriers send ETWS messages
        // with the same serial number, therefore the subsequent messages were all ignored.
        // In the other hand, US carriers have the requirement that only serial number, location,
        // and category should be used for duplicate detection.
        int hashCode = message.isEtwsMessage() ? message.getMessageBody().hashCode() : 0;

        if (mDuplicateCheckDatabase) {
            if (isDuplicated(message)) {
                return;
            }
        } else if (mUseDupDetection) {
            // Check for duplicate message IDs according to CMAS carrier requirements. Message IDs
            // are stored in volatile memory. If the maximum of 65535 messages is reached, the
            // message ID of the oldest message is deleted from the list.
            MessageServiceCategoryAndScope newCmasId = new MessageServiceCategoryAndScope(
                    message.getServiceCategory(), message.getSerialNumber(), message.getLocation(),
                            hashCode, message.getEtwsWarningInfo());
            Log.v(TAG,"newCmasId:" + newCmasId + " hash: " + newCmasId.hashCode()
                + "body hash:" + hashCode);

            // Add the new message ID to the list. It's okay if this is a duplicate message ID,
            // because the list is only used for removing old message IDs from the hash set.
            if (sCmasIdList.size() < MAX_MESSAGE_ID_SIZE) {
                sCmasIdList.add(newCmasId);
            } else {
                // Get oldest message ID from the list and replace with the new message ID.
                MessageServiceCategoryAndScope oldestCmasId = sCmasIdList.get(sCmasIdListIndex);
                sCmasIdList.set(sCmasIdListIndex, newCmasId);
                Log.d(TAG, "message ID limit reached, removing oldest message ID " + oldestCmasId);
                // Remove oldest message ID from the set.
                sCmasIdSet.remove(oldestCmasId);
                if (++sCmasIdListIndex >= MAX_MESSAGE_ID_SIZE) {
                    sCmasIdListIndex = 0;
                }
             }
            // Set.add() returns false if message ID has already been added
            if (!sCmasIdSet.add(newCmasId)) {
                Log.d(TAG, "ignoring duplicate alert with " + newCmasId);
                return;
            }
        }

        final Intent alertIntent = new Intent(SHOW_NEW_ALERT_ACTION);
        alertIntent.setClass(this, CellBroadcastAlertService.class);
        alertIntent.putExtra("message", cbm);

        // write to database on a background thread
        new CellBroadcastContentProvider.AsyncCellBroadcastTask(getContentResolver())
                .execute(new CellBroadcastContentProvider.CellBroadcastOperation() {
                    @Override
                    public boolean execute(CellBroadcastContentProvider provider) {
                        if (provider.insertNewBroadcast(cbm)) {
                            // new message, show the alert or notification on UI thread
                            startService(alertIntent);
                            return true;
                        } else {
                            return false;
                        }
                    }
                });
    }

    private void showNewAlert(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            Log.e(TAG, "received SHOW_NEW_ALERT_ACTION with no extras!");
            return;
        }

        CellBroadcastMessage cbm = (CellBroadcastMessage) intent.getParcelableExtra("message");

        if (cbm == null) {
            Log.e(TAG, "received SHOW_NEW_ALERT_ACTION with no message extra");
            return;
        }

        if (CellBroadcastConfigService.isEmergencyAlertMessage(cbm)) {
            // start alert sound / vibration / TTS and display full-screen alert
            openEmergencyAlertNotification(cbm);
        } else {
            // add notification to the bar
            addToNotificationBar(cbm);
        }
    }

    /**
     * Filter out broadcasts on the test channels that the user has not enabled,
     * and types of notifications that the user is not interested in receiving.
     * This allows us to enable an entire range of message identifiers in the
     * radio and not have to explicitly disable the message identifiers for
     * test broadcasts. In the unlikely event that the default shared preference
     * values were not initialized in CellBroadcastReceiverApp, the second parameter
     * to the getBoolean() calls match the default values in res/xml/preferences.xml.
     *
     * @param message the message to check
     * @return true if the user has enabled this message type; false otherwise
     */
    private boolean isMessageEnabledByUser(CellBroadcastMessage message) {
        // Check if ETWS/CMAS test message is forced to disabled on the device.
        boolean forceDisableEtwsCmasTest =
                CellBroadcastSettings.isEtwsCmasTestMessageForcedDisabled(this, message.getSubId());

        if (message.isEtwsTestMessage()) {
            return !forceDisableEtwsCmasTest &&
                    SubscriptionManager.getBooleanSubscriptionProperty(
                    message.getSubId(), SubscriptionManager.CB_ETWS_TEST_ALERT, false, this);
        }

        if (message.isEtwsMessage()) {
            // ETWS messages.
            // Turn on/off emergency notifications is the only way to turn on/off ETWS messages.
            return SubscriptionManager.getBooleanSubscriptionProperty(message.getSubId(),
                    SubscriptionManager.CB_EMERGENCY_ALERT, true, this);
        }

        if (message.isCmasMessage()) {
            switch (message.getCmasMessageClass()) {
                case SmsCbCmasInfo.CMAS_CLASS_EXTREME_THREAT:
                    return SubscriptionManager.getBooleanSubscriptionProperty(
                            message.getSubId(),
                            SubscriptionManager.CB_EXTREME_THREAT_ALERT, true, this);

                case SmsCbCmasInfo.CMAS_CLASS_SEVERE_THREAT:
                    return SubscriptionManager.getBooleanSubscriptionProperty(
                            message.getSubId(),
                            SubscriptionManager.CB_SEVERE_THREAT_ALERT, true, this);

                case SmsCbCmasInfo.CMAS_CLASS_CHILD_ABDUCTION_EMERGENCY:
                    return SubscriptionManager.getBooleanSubscriptionProperty(
                            message.getSubId(), SubscriptionManager.CB_AMBER_ALERT, true, this);

                case SmsCbCmasInfo.CMAS_CLASS_REQUIRED_MONTHLY_TEST:
                case SmsCbCmasInfo.CMAS_CLASS_CMAS_EXERCISE:
                case SmsCbCmasInfo.CMAS_CLASS_OPERATOR_DEFINED_USE:
                    return !forceDisableEtwsCmasTest &&
                            SubscriptionManager.getBooleanSubscriptionProperty(
                            message.getSubId(),
                                    SubscriptionManager.CB_CMAS_TEST_ALERT, false, this);
                default:
                    return true;    // presidential-level CMAS alerts are always enabled
            }
        }
        int serviceCategory = message.getServiceCategory();
        if (serviceCategory == CB_CHANNEL_50 || serviceCategory == CB_CHANNEL_60) {
            boolean channel60Preference = false;
            if (serviceCategory == CB_CHANNEL_50) {
                // save latest area info on channel 50 for Settings display
                CellBroadcastReceiverApp.setLatestAreaInfo(message);
            } else { //it is Channel 60 CB
                boolean enable60Channel =  SubscriptionManager.getResourcesForSubId(
                        getApplicationContext(), message.getSubId()).getBoolean(
                        R.bool.show_india_settings);
                if (enable60Channel) {
                    channel60Preference = PreferenceManager.getDefaultSharedPreferences(this).
                            getBoolean(CellBroadcastSettings.KEY_ENABLE_CHANNEL_60_ALERTS,
                            enable60Channel);
                }
            }
            // send broadcasts for channel 50 and 60
            Intent intent = new Intent(CB_AREA_INFO_RECEIVED_ACTION);
            intent.putExtra("message", message);
            // Send broadcast twice, once for apps that have PRIVILEGED permission and once
            // for those that have the runtime one
            sendBroadcastAsUser(intent, UserHandle.ALL,
                    android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
            sendBroadcastAsUser(intent, UserHandle.ALL,
                    android.Manifest.permission.READ_PHONE_STATE);

            String country = TelephonyManager.getDefault().getSimCountryIso(message.getSubId());
            // In Brazil(50)/India(50/60) the area info broadcasts are displayed in Settings,
            // CBwidget or Mms.
            // But in other country it should be displayed as a normal CB alert.
            boolean needIgnore = "in".equals(country)
                    || ("br".equals(country) && (message.getServiceCategory() == CB_CHANNEL_50));
            return ((!needIgnore) || channel60Preference);
        }
        return true;    // other broadcast messages are always enabled
    }

    /**
     * Display a full-screen alert message for emergency alerts.
     * @param message the alert to display
     */
    private void openEmergencyAlertNotification(CellBroadcastMessage message) {
        // Acquire a CPU wake lock until the alert dialog and audio start playing.
        CellBroadcastAlertWakeLock.acquireScreenCpuWakeLock(this);

        // Close dialogs and window shade
        Intent closeDialogs = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        sendBroadcast(closeDialogs);
        // start audio/vibration/speech service for emergency alerts
        Intent audioIntent = new Intent(this, CellBroadcastAlertAudio.class);
        audioIntent.setAction(CellBroadcastAlertAudio.ACTION_START_ALERT_AUDIO);

        int duration;   // alert audio duration in ms
        if (message.isCmasMessage()) {
            // CMAS requirement: duration of the audio attention signal is 10.5 seconds.
            duration = 10500;
        } else {
            duration = SubscriptionManager.getIntegerSubscriptionProperty(message.getSubId(),
                    SubscriptionManager.CB_ALERT_SOUND_DURATION,
                    Integer.parseInt(CellBroadcastSettings.ALERT_SOUND_DEFAULT_DURATION), this)
                    * 1000;
        }
        audioIntent.putExtra(CellBroadcastAlertAudio.ALERT_AUDIO_DURATION_EXTRA, duration);

        if (!getResources().getBoolean(
                R.bool.config_regional_presidential_wea_with_tone_vibrate)
                && message.isEtwsMessage()) {
            // For ETWS, always vibrate, even in silent mode.
            audioIntent.putExtra(CellBroadcastAlertAudio.ALERT_AUDIO_VIBRATE_EXTRA, true);
            audioIntent.putExtra(CellBroadcastAlertAudio.ALERT_AUDIO_ETWS_VIBRATE_EXTRA, true);
        } else if ((getResources().getBoolean(
            R.bool.config_regional_presidential_wea_with_tone_vibrate))
            && (message.isCmasMessage())
            && (message.getCmasMessageClass()
                == SmsCbCmasInfo.CMAS_CLASS_PRESIDENTIAL_LEVEL_ALERT)){
            audioIntent.putExtra(CellBroadcastAlertAudio.ALERT_AUDIO_VIBRATE_EXTRA, true);
            audioIntent.putExtra(CellBroadcastAlertAudio.ALERT_AUDIO_TONE_EXTRA, true);
            audioIntent.putExtra(
                    CellBroadcastAlertAudio.ALERT_AUDIO_PRESIDENT_TONE_VIBRATE_EXTRA, true);
        } else {
            // For other alerts, vibration can be disabled in app settings.
            boolean vibrateFlag = SubscriptionManager.getBooleanSubscriptionProperty(
                    message.getSubId(), SubscriptionManager.CB_ALERT_VIBRATE, true, this);
            audioIntent.putExtra(CellBroadcastAlertAudio.ALERT_AUDIO_VIBRATE_EXTRA, vibrateFlag);
        }

        if (getResources().getBoolean(
                    R.bool.config_regional_wea_alert_tone_enable)) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            audioIntent.putExtra(CellBroadcastAlertAudio.ALERT_AUDIO_TONE_EXTRA,
                    prefs.getBoolean(CellBroadcastSettings.KEY_ENABLE_ALERT_TONE, true));
        }

        String messageBody = message.getMessageBody();

        if (SubscriptionManager.getBooleanSubscriptionProperty(message.getSubId(),
                SubscriptionManager.CB_ALERT_SPEECH, true, this)) {
            audioIntent.putExtra(CellBroadcastAlertAudio.ALERT_AUDIO_MESSAGE_BODY, messageBody);

            String language = message.getLanguageCode();
            if (message.isEtwsMessage() && !"ja".equals(language)) {
                Log.w(TAG, "bad language code for ETWS - using Japanese TTS");
                language = "ja";
            } else if (message.isCmasMessage() && !"en".equals(language)) {
                Log.w(TAG, "bad language code for CMAS - using English TTS");
                language = "en";
            }
            audioIntent.putExtra(CellBroadcastAlertAudio.ALERT_AUDIO_MESSAGE_LANGUAGE,
                    language);
        }
        startService(audioIntent);

        // Decide which activity to start based on the state of the keyguard.
        Class c = CellBroadcastAlertDialog.class;
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km.inKeyguardRestrictedInputMode()) {
            // Use the full screen activity for security.
            c = CellBroadcastAlertFullScreen.class;
        }

        ArrayList<CellBroadcastMessage> messageList = new ArrayList<CellBroadcastMessage>(1);
        messageList.add(message);

        Intent alertDialogIntent = createDisplayMessageIntent(this, c, messageList);
        alertDialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(alertDialogIntent);
    }

    /**
     * Add the new alert to the notification bar (non-emergency alerts), or launch a
     * high-priority immediate intent for emergency alerts.
     * @param message the alert to display
     */
    private void addToNotificationBar(CellBroadcastMessage message) {
        int channelTitleId = CellBroadcastResources.getDialogTitleResource(message);
        CharSequence channelName = getText(channelTitleId);
        String messageBody = message.getMessageBody();

        // Pass the list of unread non-emergency CellBroadcastMessages
        ArrayList<CellBroadcastMessage> messageList = CellBroadcastReceiverApp
                .addNewMessageToList(message);

        // Create intent to show the new messages when user selects the notification.
        Intent intent = createDisplayMessageIntent(this, CellBroadcastAlertDialog.class,
                messageList);
        intent.putExtra(CellBroadcastAlertFullScreen.FROM_NOTIFICATION_EXTRA, true);

        PendingIntent pi = PendingIntent.getActivity(this, NOTIFICATION_ID, intent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT);

        // use default sound/vibration/lights for non-emergency broadcasts
        Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_notify_alert)
                .setTicker(channelName)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .setPriority(Notification.PRIORITY_HIGH)
                .setColor(getResources().getColor(R.color.notification_color))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDefaults(Notification.DEFAULT_ALL);

        builder.setDefaults(Notification.DEFAULT_ALL);

        // increment unread alert count (decremented when user dismisses alert dialog)
        int unreadCount = messageList.size();
        if (unreadCount > 1) {
            // use generic count of unread broadcasts if more than one unread
            builder.setContentTitle(getString(R.string.notification_multiple_title));
            builder.setContentText(getString(R.string.notification_multiple, unreadCount));
        } else {
            builder.setContentTitle(channelName).setContentText(messageBody);
        }

        NotificationManager notificationManager =
            (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);

        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    static Intent createDisplayMessageIntent(Context context, Class intentClass,
            ArrayList<CellBroadcastMessage> messageList) {
        // Trigger the list activity to fire up a dialog that shows the received messages
        Intent intent = new Intent(context, intentClass);
        intent.putParcelableArrayListExtra(CellBroadcastMessage.SMS_CB_MESSAGE_EXTRA, messageList);
        return intent;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;    // clients can't bind to this service
    }
}
