package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Stub {@link NotificationListenerService}: its existence (enabled via
 * {@code settings put secure enabled_notification_listeners}) lets
 * {@link NowPlayingListener} call {@code MediaSessionManager.getActiveSessions()}.
 *
 * Enable over adb:
 *   settings put secure enabled_notification_listeners \
 *       com.gathin.tvhome/com.gathin.tvhome.MediaNotificationListener
 */
public class MediaNotificationListener extends NotificationListenerService {
}
