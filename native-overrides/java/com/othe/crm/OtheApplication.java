package com.othe.crm;

import android.app.Application;
import com.stripe.stripeterminal.TerminalApplicationDelegate;
import com.stripe.stripeterminal.taptopay.TapToPay;

/**
 * Stripe Terminal must be told about the Application lifecycle. Tap to Pay runs a second
 * process (":stripetaptopay") which must NOT initialise the Terminal delegate.
 */
public class OtheApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        if (TapToPay.isInTapToPayProcess()) {
            return;
        }
        TerminalApplicationDelegate.onCreate(this);
    }
}
