package com.othe.crm;

import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.util.Log;
import com.getcapacitor.BridgeActivity;
import com.othe.crm.terminal.StripeTerminalPlugin;

public class MainActivity extends BridgeActivity {

    private static final String TAG = "OtheMainActivity";

    /**
     * NFC "tag guard".
     *
     * Tap to Pay collects the card inside Stripe's own screen (a separate activity in the
     * ":stripetaptopay" process). When that screen closes after a successful payment, the
     * customer's card is often still touching the phone. Without this guard Android itself
     * reads the card and shows its system "New tag scanned" screen on top of our app.
     *
     * While THIS screen is in the foreground we put NFC into reader mode and ignore every
     * tag, so the system never gets a chance to show that screen. As soon as Stripe's
     * payment screen opens on top, this activity is paused and the guard is released, so
     * Stripe has full control of NFC during the payment itself.
     */
    private static final int NFC_GUARD_FLAGS =
        NfcAdapter.FLAG_READER_NFC_A
            | NfcAdapter.FLAG_READER_NFC_B
            | NfcAdapter.FLAG_READER_NFC_F
            | NfcAdapter.FLAG_READER_NFC_V
            | NfcAdapter.FLAG_READER_NFC_BARCODE
            | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
            | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AuthNavigationPlugin.class);
        registerPlugin(StripeTerminalPlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        enableNfcGuard();
    }

    @Override
    public void onPause() {
        disableNfcGuard();
        super.onPause();
    }

    private void enableNfcGuard() {
        try {
            NfcAdapter nfc = NfcAdapter.getDefaultAdapter(this);
            if (nfc == null || !nfc.isEnabled()) return;
            // Callback intentionally does nothing: the tag is swallowed.
            nfc.enableReaderMode(this, tag -> { }, NFC_GUARD_FLAGS, null);
        } catch (Exception e) {
            Log.w(TAG, "Could not enable NFC guard", e);
        }
    }

    private void disableNfcGuard() {
        try {
            NfcAdapter nfc = NfcAdapter.getDefaultAdapter(this);
            if (nfc != null) nfc.disableReaderMode(this);
        } catch (Exception e) {
            Log.w(TAG, "Could not disable NFC guard", e);
        }
    }
}
