/*
 * Copyright (C) 2013 Google Inc. All Rights Reserved. 
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

package com.example.castsample;

import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.support.v7.app.MediaRouteButton;
import android.support.v7.media.MediaRouteSelector;
import android.support.v7.media.MediaRouter;
import android.support.v7.media.MediaRouter.RouteInfo;
import android.text.Html;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import com.example.castsample.mediaroutedialog.SampleMediaRouteDialogFactory;
import com.google.cast.ApplicationChannel;
import com.google.cast.ApplicationMetadata;
import com.google.cast.ApplicationSession;
import com.google.cast.CastContext;
import com.google.cast.CastDevice;
import com.google.cast.ContentMetadata;
import com.google.cast.MediaProtocolCommand;
import com.google.cast.MediaProtocolMessageStream;
import com.google.cast.MediaRouteAdapter;
import com.google.cast.MediaRouteHelper;
import com.google.cast.MediaRouteStateChangeListener;
import com.google.cast.SessionError;

import java.io.IOException;

/**
 * An activity that plays a chosen sample video on a Cast device and exposes playback and volume
 * controls in the UI.
 */
public class CastSampleActivity extends FragmentActivity implements MediaRouteAdapter {

    private static final String TAG = CastSampleActivity.class.getSimpleName();

    public static final boolean ENABLE_LOGV = true;

    protected static final double MAX_VOLUME_LEVEL = 20;
    private static final double VOLUME_INCREMENT = 0.05;
    private static final int SEEK_FORWARD = 1;
    private static final int SEEK_BACK = 2;
    private static final int SEEK_INCREMENT = 10;

    private boolean mPlayButtonShowsPlay = false;
    private boolean mVideoIsStopped = false;

    private CastContext mCastContext = null;
    private CastDevice mSelectedDevice;
    private CastMedia mMedia;
    private ContentMetadata mMetaData;
    private ApplicationSession mSession;
    private MediaProtocolMessageStream mMessageStream;
    private MediaRouteButton mMediaRouteButton;
    private MediaRouter mMediaRouter;
    private MediaRouteSelector mMediaRouteSelector;
    private MediaRouter.Callback mMediaRouterCallback;
    private MediaSelectionDialog mMediaSelectionDialog;
    private MediaProtocolCommand mStatus;

    private ImageButton mPlayPauseButton;
    private ImageButton mStopButton;
    private TextView mStatusText;
    private TextView mCurrentlyPlaying;
    private String mCurrentItemId;
    private RouteInfo mCurrentRoute;

    private SampleMediaRouteDialogFactory mDialogFactory;

    /**
     * Initializes MediaRouter information and prepares for Cast device detection upon creating
     * this activity.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        logVIfEnabled(TAG, "onCreate called");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cast_sample);

        mCastContext = new CastContext(getApplicationContext());
        mMedia = new CastMedia(null, null);
        mMetaData = new ContentMetadata();

        mDialogFactory = new SampleMediaRouteDialogFactory();

        MediaRouteHelper.registerMinimalMediaRouteProvider(mCastContext, this);
        mMediaRouter = MediaRouter.getInstance(getApplicationContext());
        mMediaRouteSelector = MediaRouteHelper
                .buildMediaRouteSelector(MediaRouteHelper.CATEGORY_CAST,
                        getResources().getString(R.string.app_name), null);

        mMediaRouteButton = (MediaRouteButton) findViewById(R.id.media_route_button);
        mMediaRouteButton.setRouteSelector(mMediaRouteSelector);
        mMediaRouteButton.setDialogFactory(mDialogFactory);
        mMediaRouterCallback = new MyMediaRouterCallback();

        mStatusText = (TextView) findViewById(R.id.play_status_text);
        mCurrentlyPlaying = (TextView) findViewById(R.id.currently_playing);
        mCurrentlyPlaying.setText(getString(R.string.tap_to_select));
        mMediaSelectionDialog = new MediaSelectionDialog(this);

        mPlayPauseButton = (ImageButton) findViewById(R.id.play_pause_button);
        mStopButton = (ImageButton) findViewById(R.id.stop_button);
        initButtons();

        Thread myThread = null;
        Runnable runnable = new StatusRunner();
        myThread = new Thread(runnable);
        logVIfEnabled(TAG, "Starting statusRunner thread");
        myThread.start();
    }

    /**
     * Initializes all buttons by adding user controls and listeners.
     */
    public void initButtons() {
        mPlayPauseButton.setEnabled(false);
        mPlayPauseButton.setImageResource(R.drawable.pause_button);
        mPlayPauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPlayClicked(!mPlayButtonShowsPlay);
            }
        });
        mStopButton.setEnabled(false);
        mStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onStopClicked();
            }
        });
        mCurrentlyPlaying.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logVIfEnabled(TAG, "Selecting Media");
                mMediaSelectionDialog.setTitle(getResources().getString(
                        R.string.medial_dialog_title));
                mMediaSelectionDialog.show();
            }
        });
    }

    /**
     * Skips forward or backward by some fixed increment in the currently playing media.
     *
     * @param direction an integer corresponding to either SEEK_FORWARD or SEEK_BACK
     */
    public void onSeekClicked(int direction) {
        try {
            if (mMessageStream != null) {
                double cPosition = mMessageStream.getStreamPosition();
                if (direction == SEEK_FORWARD) {
                    mMessageStream.playFrom(cPosition + SEEK_INCREMENT);
                } else if (direction == SEEK_BACK) {
                    mMessageStream.playFrom(cPosition - SEEK_INCREMENT);
                } else {
                    Log.e(TAG, "onSeekClicked was not FWD or BACK");
                }
            } else {
                Log.e(TAG, "onSeekClicked - mMPMS==null");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to send pause command.");
        }
    }

    /**
     * Handles stopping the currently playing media upon the stop button being pressed.
     */
    public void onStopClicked() {
        try {
            if (mMessageStream != null) {
                mMessageStream.stop();
                mVideoIsStopped = !mVideoIsStopped;
                mPlayPauseButton.setImageResource(R.drawable.play_button);
                mPlayButtonShowsPlay = true;
            } else {
                Log.e(TAG, "onStopClicked - mMPMS==null");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to send pause command.");
        }
    }

    /**
     * Mutes the currently playing media when the mute button is pressed.
     */
    public void onMuteClicked() {
        try {
            if (mMessageStream != null) {
                if (mMessageStream.isMuted()) {
                    mMessageStream.setMuted(false);
                } else {
                    mMessageStream.setMuted(true);
                }
            } else {
                Log.e(TAG, "onMutedClicked - mMPMS==null");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to send pause command.");
        }
    }

    /**
     * Plays or pauses the currently loaded media, depending on the current state of the <code>
     * mPlayPauseButton</code>.
     *
     * @param playState indicates that Play was clicked if true, and Pause was clicked if false
     */
    public void onPlayClicked(boolean playState) {
        if (playState) {
            try {
                if (mMessageStream != null) {
                    mMessageStream.stop();
                } else {
                    Log.e(TAG, "onClick-Play - mMPMS==null");
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to send stop command.");
            }
            mPlayPauseButton.setImageResource(R.drawable.play_button);
        } else {
            try {
                if (mMessageStream != null) {
                    if (mVideoIsStopped) {
                        mMessageStream.play();
                        mVideoIsStopped = !mVideoIsStopped;
                    } else {
                        mMessageStream.resume();
                    }
                } else {
                    Log.e(TAG, "onClick-Play - mMPMS==null");
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to send play/resume command.");
            }
            mPlayPauseButton.setImageResource(R.drawable.pause_button);
        }
        mPlayButtonShowsPlay = !mPlayButtonShowsPlay;
    }

    @Override
    public void onDeviceAvailable(CastDevice device, String myString,
                                  MediaRouteStateChangeListener listener) {
        mSelectedDevice = device;
        logVIfEnabled(TAG, "Available device found: " + myString);
        openSession();
    }

    @Override
    public void onSetVolume(double volume) {
        try {
            mMessageStream.setVolume(volume);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Problem sending Set Volume", e);
        } catch (IOException e) {
            Log.e(TAG, "Problem sending Set Volume", e);
        }
    }

    @Override
    public void onUpdateVolume(double volumeChange) {
        try {
            if ((mCurrentItemId != null) && (mCurrentRoute != null)) {
                mCurrentRoute.requestUpdateVolume((int) (volumeChange * MAX_VOLUME_LEVEL));
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Problem sending Update Volume", e);
        }
    }

    /**
     * Processes volume up and volume down actions upon receiving them as key events.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int action = event.getAction();
        int keyCode = event.getKeyCode();
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (action == KeyEvent.ACTION_DOWN) {
                    double currentVolume;
                    if (mMessageStream != null) {
                        currentVolume = mMessageStream.getVolume();
                        logVIfEnabled(TAG, "Volume up from " + currentVolume);
                        if (currentVolume < 1.0) {
                            logVIfEnabled(TAG, "New volume: " + (currentVolume + VOLUME_INCREMENT));
                            onSetVolume(currentVolume + VOLUME_INCREMENT);
                        }
                    } else {
                        Log.e(TAG, "dispatchKeyEvent - volume up - mMPMS==null");
                    }
                }

                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (action == KeyEvent.ACTION_DOWN) {
                    double currentVolume;
                    if (mMessageStream != null) {
                        currentVolume = mMessageStream.getVolume();
                        logVIfEnabled(TAG, "Volume down from: " + currentVolume);
                        if (currentVolume > 0.0) {
                            logVIfEnabled(TAG, "New volume: " + (currentVolume - VOLUME_INCREMENT));
                            onSetVolume(currentVolume - VOLUME_INCREMENT);
                        }
                    } else {
                        Log.e(TAG, "dispatchKeyEvent - volume down - mMPMS==null");
                    }
                }
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mMediaRouter.addCallback(mMediaRouteSelector, mMediaRouterCallback,
                MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
        logVIfEnabled(TAG, "onStart called and callback added");
    }

    /**
     * Closes a running session upon destruction of this Activity.
     */
    @Override
    protected void onStop() {
        mMediaRouter.removeCallback(mMediaRouterCallback);
        super.onStop();
        logVIfEnabled(TAG, "onStop called and callback removed");
    }

    @Override
    protected void onDestroy() {
        logVIfEnabled(TAG, "onDestroy called, ending session if session exists");
        if (mSession != null) {
            try {
                if (!mSession.hasStopped()) {
                    mSession.endSession();
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to end session.");
            }
        }
        mSession = null;
        super.onDestroy();
    }

    /**
     * A callback class which listens for route select or unselect events and processes devices
     * and sessions accordingly.
     */
    private class MyMediaRouterCallback extends MediaRouter.Callback {
        @Override
        public void onRouteSelected(MediaRouter router, RouteInfo route) {
            MediaRouteHelper.requestCastDeviceForRoute(route);
        }

        @Override
        public void onRouteUnselected(MediaRouter router, RouteInfo route) {
            try {
                if (mSession != null) {
                    logVIfEnabled(TAG, "Ending session and stopping application");
                    mSession.setStopApplicationWhenEnding(true);
                    mSession.endSession();
                } else {
                    Log.e(TAG, "onRouteUnselected: mSession is null");
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "onRouteUnselected:");
                e.printStackTrace();
            } catch (IOException e) {
                Log.e(TAG, "onRouteUnselected:");
                e.printStackTrace();
            }
            mMessageStream = null;
            mSelectedDevice = null;
        }
    }

    /**
     * Starts a new video playback session with the current CastContext and selected device.
     */
    private void openSession() {
        mSession = new ApplicationSession(mCastContext, mSelectedDevice);

        // TODO: The below lines allow you to specify either that your application uses the default
        // implementations of the Notification and Lock Screens, or that you will be using your own.
        int flags = 0;

        // Comment out the below line if you are not writing your own Notification Screen.
        // flags |= ApplicationSession.FLAG_DISABLE_NOTIFICATION;

        // Comment out the below line if you are not writing your own Lock Screen.
        // flags |= ApplicationSession.FLAG_DISABLE_LOCK_SCREEN_REMOTE_CONTROL;
        mSession.setApplicationOptions(flags);

        logVIfEnabled(TAG, "Beginning session with context: " + mCastContext);
        logVIfEnabled(TAG, "The session to begin: " + mSession);
        mSession.setListener(new com.google.cast.ApplicationSession.Listener() {

            @Override
            public void onSessionStarted(ApplicationMetadata appMetadata) {
                logVIfEnabled(TAG, "Getting channel after session start");
                ApplicationChannel channel = mSession.getChannel();
                if (channel == null) {
                    Log.e(TAG, "channel = null");
                    return;
                }
                logVIfEnabled(TAG, "Creating and attaching Message Stream");
                mMessageStream = new MediaProtocolMessageStream();
                channel.attachMessageStream(mMessageStream);

                if (mMessageStream.getPlayerState() == null) {
                    if (mMedia != null) {
                        loadMedia();
                    }
                } else {
                    logVIfEnabled(TAG, "Found player already running; updating status");
                    updateStatus();
                }
            }

            @Override
            public void onSessionStartFailed(SessionError error) {
                Log.e(TAG, "onStartFailed " + error);
            }

            @Override
            public void onSessionEnded(SessionError error) {
                Log.i(TAG, "onEnded " + error);
            }
        });

        mPlayPauseButton.setEnabled(true);
        mStopButton.setEnabled(true);
        try {
            logVIfEnabled(TAG, "Starting session with app name " + getString(R.string.app_name));

            // TODO: To run your own copy of the receiver, you will need to set app_name in 
            // /res/strings.xml to your own appID, and then upload the provided receiver 
            // to the url that you whitelisted for your app.
            // The current value of app_name is "YOUR_APP_ID_HERE".
            mSession.startSession(getString(R.string.app_name));
        } catch (IOException e) {
            Log.e(TAG, "Failed to open session", e);
        }
    }

    /**
     * Loads the stored media object and casts it to the currently selected device.
     */
    protected void loadMedia() {
        logVIfEnabled(TAG, "Loading selected media on device");
        mMetaData.setTitle(mMedia.getTitle());
        try {
            MediaProtocolCommand cmd = mMessageStream.loadMedia(mMedia.getUrl(), mMetaData, true);
            cmd.setListener(new MediaProtocolCommand.Listener() {

                @Override
                public void onCompleted(MediaProtocolCommand mPCommand) {
                    logVIfEnabled(TAG, "Load completed - starting playback");
                    mPlayPauseButton.setImageResource(R.drawable.pause_button);
                    mPlayButtonShowsPlay = false;
                    onSetVolume(0.5);
                }

                @Override
                public void onCancelled(MediaProtocolCommand mPCommand) {
                    logVIfEnabled(TAG, "Load cancelled");
                }
            });

        } catch (IllegalStateException e) {
            Log.e(TAG, "Problem occurred with MediaProtocolCommand during loading", e);
        } catch (IOException e) {
            Log.e(TAG, "Problem opening MediaProtocolCommand during loading", e);
        }
    }

    /**
     * Stores and attempts to load the passed piece of media.
     */
    protected void mediaSelected(CastMedia media) {
        this.mMedia = media;
        updateCurrentlyPlaying();
        if (mMessageStream != null) {
            loadMedia();
        }
    }

    /**
     * Updates the status of the currently playing video in the dedicated message view.
     */
    public void updateStatus() {
        this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    setMediaRouteButtonVisible();
                    updateCurrentlyPlaying();

                    if (mMessageStream != null) {
                        mStatus = mMessageStream.requestStatus();

                        String currentStatus = "Player State: "
                                + mMessageStream.getPlayerState() + "\n";
                        currentStatus += "Device " + mSelectedDevice.getFriendlyName() + "\n";
                        currentStatus += "Title " + mMessageStream.getTitle() + "\n";
                        currentStatus += "Current Position: "
                                + mMessageStream.getStreamPosition() + "\n";
                        currentStatus += "Duration: "
                                + mMessageStream.getStreamDuration() + "\n";
                        currentStatus += "Volume set at: "
                                + (mMessageStream.getVolume() * 100) + "%\n";
                        currentStatus += "requestStatus: " + mStatus.getType() + "\n";
                        mStatusText.setText(currentStatus);
                    } else {
                        mStatusText.setText(getResources().getString(R.string.tap_icon));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Status request failed: " + e);
                }
            }
        });
    }

    /**
     * Sets the Cast Device Selection button to visible or not, depending on the availability of
     * devices.
     */
    protected final void setMediaRouteButtonVisible() {
        mMediaRouteButton.setVisibility(
                mMediaRouter.isRouteAvailable(mMediaRouteSelector, 0) ? View.VISIBLE : View.GONE);
    }

    /**
     * Updates a view with the title of the currently playing media.
     */
    protected void updateCurrentlyPlaying() {
        String playing = "";
        if (mMedia.getTitle() != null) {
            playing = "Media Selected: " + mMedia.getTitle();
            if (mMessageStream != null) {
                String colorString = "<br><font color=#0066FF>";
                colorString += "Casting to " + mSelectedDevice.getFriendlyName();
                colorString += "</font>";
                playing += colorString;
            }
            mCurrentlyPlaying.setText(Html.fromHtml(playing));
        } else {
            String castString = "<font color=#FF0000>";
            castString += getResources().getString(R.string.tap_to_select);
            castString += "</font>";
            mCurrentlyPlaying.setText(Html.fromHtml(castString));
        }
    }

    /**
     * A Runnable class that updates a view to display status for the currently playing media.
     */
    private class StatusRunner implements Runnable {
        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    updateStatus();
                    Thread.sleep(1500);
                } catch (Exception e) {
                    Log.e(TAG, "Thread interrupted: " + e);
                }
            }
        }
    }

    /**
     * Logs in verbose mode with the given tag and message, if the LOCAL_LOGV tag is set.
     */
    private void logVIfEnabled(String tag, String message) {
        if (ENABLE_LOGV) {
            Log.v(tag, message);
        }
    }
}
