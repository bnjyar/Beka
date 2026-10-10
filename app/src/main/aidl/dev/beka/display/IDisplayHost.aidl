package dev.beka.display;

import android.content.Intent;
import android.view.Surface;

/** Runs as the shell user (app_process started through Shizuku or ADB) and owns Beka's virtual display. */
interface IDisplayHost {
    /** Ends the host process. */
    void destroy() = 10;

    int uid() = 1;

    /** Creates the display rendering into [surface]; released when [client] dies. Returns its id. */
    int create(in Surface surface, int width, int height, int dpi, IBinder client) = 2;

    void release() = 3;

    /** Wakes the display's power group without touching the main screen. */
    boolean wake() = 4;

    /** Holds or drops a screen wake lock bound to the display. Returns false if the system refused. */
    boolean holdAwake(boolean on) = 5;

    /**
     * Shows [intent] on the display. A task of the same package on another display is moved over
     * instead of starting a second copy. Returns null on success, else the reason.
     */
    String launch(in Intent intent) = 6;

    /** Sends a key press (e.g. KEYCODE_BACK) to the display. */
    boolean key(int keyCode) = 7;

    /** Moves the task of [packageName] from the display to the main screen. */
    boolean moveToMain(String packageName) = 8;

    /** Package names of the tasks on the display, top first. */
    List<String> packages() = 9;
}
