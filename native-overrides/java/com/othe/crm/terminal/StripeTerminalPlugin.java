package com.othe.crm.terminal;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.WebViewListener;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.othe.crm.R;
import com.stripe.stripeterminal.Terminal;
import com.stripe.stripeterminal.external.callable.Callback;
import com.stripe.stripeterminal.external.callable.Cancelable;
import com.stripe.stripeterminal.external.callable.ConnectionTokenCallback;
import com.stripe.stripeterminal.external.callable.ConnectionTokenProvider;
import com.stripe.stripeterminal.external.callable.DiscoveryListener;
import com.stripe.stripeterminal.external.callable.MobileReaderListener;
import com.stripe.stripeterminal.external.callable.PaymentIntentCallback;
import com.stripe.stripeterminal.external.callable.ReaderCallback;
import com.stripe.stripeterminal.external.callable.TapToPayReaderListener;
import com.stripe.stripeterminal.external.callable.TerminalListener;
import com.stripe.stripeterminal.external.models.BatteryStatus;
import com.stripe.stripeterminal.external.models.CaptureMethod;
import com.stripe.stripeterminal.external.models.CardPresentDetails;
import com.stripe.stripeterminal.external.models.Charge;
import com.stripe.stripeterminal.external.models.CollectPaymentIntentConfiguration;
import com.stripe.stripeterminal.external.models.ConfirmPaymentIntentConfiguration;
import com.stripe.stripeterminal.external.models.ConnectionConfiguration;
import com.stripe.stripeterminal.external.models.ConnectionStatus;
import com.stripe.stripeterminal.external.models.ConnectionTokenException;
import com.stripe.stripeterminal.external.models.DeviceType;
import com.stripe.stripeterminal.external.models.DisconnectReason;
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration;
import com.stripe.stripeterminal.external.models.PaymentIntent;
import com.stripe.stripeterminal.external.models.PaymentIntentParameters;
import com.stripe.stripeterminal.external.models.PaymentIntentStatus;
import com.stripe.stripeterminal.external.models.PaymentMethod;
import com.stripe.stripeterminal.external.models.PaymentMethodDetails;
import com.stripe.stripeterminal.external.models.Reader;
import com.stripe.stripeterminal.external.models.ReaderDisplayMessage;
import com.stripe.stripeterminal.external.models.ReaderInputOptions;
import com.stripe.stripeterminal.external.models.ReaderSoftwareUpdate;
import com.stripe.stripeterminal.external.models.TerminalException;
import com.stripe.stripeterminal.log.LogLevel;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native bridge between the CRM web app and the Stripe Terminal Android SDK
 * (Tap to Pay on Android + Bluetooth M2 reader).
 *
 * The web app talks to window.StripeTerminal, which is defined by res/raw/stripe_terminal_shim.js
 * (injected into CRM pages only) on top of this Capacitor plugin.
 *
 * Rebuilt in Oct 2026 from the v1.0.0 APK; behaviour and JS interface are unchanged.
 */
@CapacitorPlugin(
    name = "StripeTerminal",
    permissions = {
        @Permission(
            alias = StripeTerminalPlugin.LOCATION,
            strings = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }
        ),
        @Permission(
            alias = StripeTerminalPlugin.BLUETOOTH,
            strings = { "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT" }
        )
    }
)
public class StripeTerminalPlugin extends Plugin {

    public static final String LOCATION = "location";
    public static final String BLUETOOTH = "bluetooth";

    /** Pages allowed to use the card reader (the CRM itself, on its own domain and on Base44). */
    private static final Set<String> CRM_ORIGINS = new HashSet<>(
        Arrays.asList("https://ontimehomexpertscrm.com", "https://lead-dispatch-flow.base44.app")
    );

    private static final int BLUETOOTH_DISCOVERY_TIMEOUT_SECONDS = 60;
    private static final long TOKEN_REQUEST_TIMEOUT_MS = 30000L;

    private final Handler main = new Handler(Looper.getMainLooper());

    private String locationId;
    private boolean isSimulated;
    private Cancelable discoveryCancelable;
    private List<Reader> discoveredReaders = Collections.emptyList();
    private Cancelable paymentCancelable;
    private PaymentIntent pendingIntent;
    private String pendingClientSecret;
    private final ConcurrentHashMap<String, ConnectionTokenCallback> tokenRequests = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- listeners

    private final ConnectionTokenProvider tokenProvider = new ConnectionTokenProvider() {
        @Override
        public void fetchConnectionToken(ConnectionTokenCallback callback) {
            final String requestId = UUID.randomUUID().toString();
            tokenRequests.put(requestId, callback);
            notifyListeners("connectionTokenRequested", new JSObject().put("requestId", requestId));
            main.postDelayed(() -> {
                ConnectionTokenCallback pending = tokenRequests.remove(requestId);
                if (pending != null) {
                    pending.onFailure(
                        new ConnectionTokenException("The CRM page did not provide a connection token. Is the user logged in?")
                    );
                }
            }, TOKEN_REQUEST_TIMEOUT_MS);
        }
    };

    private final TerminalListener terminalListener = new TerminalListener() {
        @Override
        public void onConnectionStatusChange(ConnectionStatus status) {
            notifyListeners("connectionStatusChange", new JSObject().put("status", status.name()));
        }
    };

    private final TapToPayReaderListener tapToPayReaderListener = new TapToPayReaderListener() {
        @Override
        public void onDisconnect(DisconnectReason reason) {
            reportDisconnect(reason);
        }
    };

    private final MobileReaderListener mobileReaderListener = new MobileReaderListener() {
        @Override
        public void onBatteryLevelUpdate(float batteryLevel, BatteryStatus batteryStatus, boolean isCharging) {}

        @Override
        public void onDisconnect(DisconnectReason reason) {
            reportDisconnect(reason);
        }

        @Override
        public void onRequestReaderInput(ReaderInputOptions options) {
            readerMessage("input", options.toString());
        }

        @Override
        public void onRequestReaderDisplayMessage(ReaderDisplayMessage message) {
            readerMessage("display", message.toString());
        }

        @Override
        public void onStartInstallingUpdate(ReaderSoftwareUpdate update, Cancelable cancelable) {
            readerMessage("update", "Updating card reader software. Keep the reader on and nearby…");
        }

        @Override
        public void onFinishInstallingUpdate(ReaderSoftwareUpdate update, TerminalException e) {
            readerMessage("update", e == null ? "Card reader update finished." : "Card reader update failed: " + e.getErrorMessage());
        }

        @Override
        public void onReportLowBatteryWarning() {
            readerMessage("battery", "Card reader battery is low.");
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void load() {
        final String shim = readShim();
        if (shim == null) return;
        WebView webView = bridge.getWebView();
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, shim, CRM_ORIGINS);
        }
        bridge.addWebViewListener(new WebViewListener() {
            @Override
            public void onPageLoaded(WebView view) {
                if (isCrmUrl(view.getUrl())) view.evaluateJavascript(shim, null);
            }
        });
        if (isCrmUrl(webView.getUrl())) webView.evaluateJavascript(shim, null);
    }

    private String readShim() {
        try (InputStream in = getContext().getResources().openRawResource(R.raw.stripe_terminal_shim);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isCrmUrl(String url) {
        if (url == null) return false;
        Uri uri = Uri.parse(url);
        if (uri == null || uri.getHost() == null) return false;
        return CRM_ORIGINS.contains(uri.getScheme() + "://" + uri.getHost());
    }

    // ---------------------------------------------------------------- plugin methods

    @PluginMethod
    public void isAvailable(PluginCall call) {
        call.resolve(new JSObject().put("value", true));
    }

    @PluginMethod
    public void connect(PluginCall call) {
        String modeArg = call.getString("mode");
        final String mode = modeArg == null ? "tap_to_pay" : modeArg;
        if (!"tap_to_pay".equals(mode) && !"m2".equals(mode)) {
            call.reject("Unknown mode: " + mode, "BAD_MODE");
            return;
        }
        Boolean sim = call.getBoolean("isSimulated", false);
        isSimulated = sim != null && sim;
        String loc = call.getString("locationId");
        if (loc != null && !loc.trim().isEmpty()) locationId = loc;
        if (!ensurePermissions(call, false)) return;

        onMain(() -> {
            if (!initTerminal(call)) return;
            Reader connected = Terminal.getInstance().getConnectedReader();
            if ("m2".equals(mode)) {
                if (connected != null && connected.getDeviceType() != DeviceType.TAP_TO_PAY_DEVICE) {
                    call.resolve(connectedResult(connected));
                } else {
                    call.resolve(new JSObject().put("connected", false).put("reader", new JSObject().put("deviceType", "m2_pending")));
                }
                return;
            }
            if (connected == null) {
                connectTapToPay(call);
            } else if (connected.getDeviceType() == DeviceType.TAP_TO_PAY_DEVICE) {
                call.resolve(connectedResult(connected));
            } else {
                disconnectThen(call, () -> connectTapToPay(call));
            }
        });
    }

    @PluginMethod
    public void discoverReaders(PluginCall call) {
        if (!ensurePermissions(call, true)) return;
        onMain(() -> {
            if (!initTerminal(call)) return;
            if (!isSimulated && !bluetoothEnabled()) {
                toast("Please turn on Bluetooth to connect the card reader.");
                call.reject("Bluetooth is turned off", "BLUETOOTH_DISABLED");
                return;
            }
            stopDiscovery(() -> {
                final AtomicBoolean settled = new AtomicBoolean(false);
                discoveryCancelable = Terminal.getInstance().discoverReaders(
                    new DiscoveryConfiguration.BluetoothDiscoveryConfiguration(BLUETOOTH_DISCOVERY_TIMEOUT_SECONDS, isSimulated),
                    new DiscoveryListener() {
                        @Override
                        public void onUpdateDiscoveredReaders(List<Reader> readers) {
                            discoveredReaders = readers;
                            JSArray arr = new JSArray();
                            for (Reader r : readers) {
                                String label = r.getLabel() != null ? r.getLabel() : r.getSerialNumber();
                                arr.put(new JSObject().put("serialNumber", r.getSerialNumber()).put("label", label));
                            }
                            notifyListeners("readersDiscovered", new JSObject().put("readers", arr));
                            if (!readers.isEmpty() && settled.compareAndSet(false, true)) call.resolve();
                        }
                    },
                    new Callback() {
                        @Override
                        public void onSuccess() {
                            discoveryCancelable = null;
                            if (settled.compareAndSet(false, true)) call.resolve();
                        }

                        @Override
                        public void onFailure(TerminalException e) {
                            discoveryCancelable = null;
                            if (settled.compareAndSet(false, true)) reject(call, e);
                        }
                    }
                );
            });
        });
    }

    @PluginMethod
    public void connectReader(PluginCall call) {
        final String serial = call.getString("readerSerialNumber");
        if (serial == null || serial.trim().isEmpty()) {
            call.reject("readerSerialNumber required", "BAD_ARGS");
            return;
        }
        final String loc = locationId;
        if (loc == null) {
            call.reject("locationId required to connect an M2 (pass it to connect())", "NO_LOCATION");
            return;
        }
        if (!ensurePermissions(call, true)) return;
        onMain(() -> {
            if (!initTerminal(call)) return;
            Reader found = null;
            for (Reader r : discoveredReaders) {
                if (serial.equals(r.getSerialNumber())) {
                    found = r;
                    break;
                }
            }
            if (found == null) {
                call.reject("Reader not found in discovery list", "READER_NOT_FOUND");
                return;
            }
            final Reader reader = found;
            Runnable doConnect = () -> Terminal.getInstance().connectReader(
                reader,
                new ConnectionConfiguration.BluetoothConnectionConfiguration(loc, true, mobileReaderListener),
                readerCallback(call)
            );
            if (Terminal.getInstance().getConnectedReader() != null) disconnectThen(call, doConnect);
            else doConnect.run();
        });
    }

    @PluginMethod
    public void collectPayment(PluginCall call) {
        String cs = call.getString("clientSecret");
        final String clientSecret = (cs == null || cs.trim().isEmpty()) ? null : cs;
        final long amount = call.getData().optLong("amount", -1L);
        String cur = call.getString("currency");
        final String currency = (cur == null ? "usd" : cur).toLowerCase(Locale.ROOT);
        if (clientSecret == null && amount <= 0) {
            call.reject("clientSecret or amount (cents) required", "BAD_AMOUNT");
            return;
        }
        if (!ensurePermissions(call, false)) return;
        onMain(() -> {
            if (!initTerminal(call)) return;
            if (Terminal.getInstance().getConnectionStatus() != ConnectionStatus.CONNECTED) {
                call.reject("Reader not connected", "NOT_CONNECTED");
                return;
            }
            if (paymentCancelable != null && !paymentCancelable.isCompleted()) {
                call.reject("A payment is already in progress", "BUSY");
                return;
            }
            if (clientSecret != null) collectServerIntent(clientSecret, call);
            else collectLocalIntent(amount, currency, call);
        });
    }

    @PluginMethod
    public void cancelCollect(PluginCall call) {
        onMain(() -> {
            Cancelable c = paymentCancelable;
            if (c == null || c.isCompleted()) {
                call.resolve();
                return;
            }
            c.cancel(new Callback() {
                @Override
                public void onSuccess() {
                    call.resolve();
                }

                @Override
                public void onFailure(TerminalException e) {
                    reject(call, e);
                }
            });
        });
    }

    @PluginMethod
    public void getConnectionStatus(PluginCall call) {
        String status = Terminal.isInitialized() ? Terminal.getInstance().getConnectionStatus().name() : "NOT_CONNECTED";
        call.resolve(new JSObject().put("status", status));
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        onMain(() -> {
            if (!Terminal.isInitialized()) {
                call.resolve();
                return;
            }
            stopDiscovery(() -> {
                if (Terminal.getInstance().getConnectedReader() == null) call.resolve();
                else disconnectThen(call, call::resolve);
            });
        });
    }

    @PluginMethod
    public void provideConnectionToken(PluginCall call) {
        String requestId = call.getString("requestId");
        ConnectionTokenCallback cb = requestId != null ? tokenRequests.remove(requestId) : null;
        if (cb == null) {
            call.reject("Unknown or expired token request", "UNKNOWN_REQUEST");
            return;
        }
        String secret = call.getString("secret");
        if (secret == null || secret.trim().isEmpty()) {
            String err = call.getString("error");
            cb.onFailure(new ConnectionTokenException(err != null ? err : "No connection token returned"));
        } else {
            cb.onSuccess(secret);
        }
        call.resolve();
    }

    // ---------------------------------------------------------------- payment flow

    private void collectServerIntent(final String clientSecret, final PluginCall call) {
        PaymentIntent pi = pendingIntent;
        if (pi != null && clientSecret.equals(pendingClientSecret) && isCollectable(pi)) {
            processPayment(pi, clientSecret, call);
            return;
        }
        Terminal.getInstance().retrievePaymentIntent(clientSecret, new PaymentIntentCallback() {
            @Override
            public void onSuccess(PaymentIntent intent) {
                onMain(() -> {
                    if (isCollectable(intent)) {
                        processPayment(intent, clientSecret, call);
                    } else if (intent.getStatus() == PaymentIntentStatus.REQUIRES_CAPTURE
                        || intent.getStatus() == PaymentIntentStatus.SUCCEEDED) {
                        // Already paid (e.g. the app was interrupted after the tap): report it, never charge twice.
                        call.resolve(paymentResult(intent));
                    } else {
                        call.reject("This payment can no longer be collected (" + intent.getStatus() + ")", "INTENT_NOT_COLLECTABLE");
                    }
                });
            }

            @Override
            public void onFailure(TerminalException e) {
                reject(call, e);
            }
        });
    }

    private void collectLocalIntent(long amount, String currency, final PluginCall call) {
        PaymentIntent pi = pendingIntent;
        if (pi != null && pendingClientSecret == null && pi.getAmount() == amount
            && currency.equalsIgnoreCase(pi.getCurrency()) && isCollectable(pi)) {
            processPayment(pi, null, call);
            return;
        }
        PaymentIntentParameters params = new PaymentIntentParameters.Builder()
            .setAmount(amount)
            .setCurrency(currency)
            .setCaptureMethod(CaptureMethod.Automatic)
            .build();
        Terminal.getInstance().createPaymentIntent(params, new PaymentIntentCallback() {
            @Override
            public void onSuccess(PaymentIntent intent) {
                processPayment(intent, null, call);
            }

            @Override
            public void onFailure(TerminalException e) {
                reject(call, e);
            }
        });
    }

    private boolean isCollectable(PaymentIntent intent) {
        return intent.getStatus() == PaymentIntentStatus.REQUIRES_PAYMENT_METHOD
            || intent.getStatus() == PaymentIntentStatus.REQUIRES_CONFIRMATION;
    }

    private void processPayment(PaymentIntent intent, String clientSecret, final PluginCall call) {
        pendingIntent = intent;
        pendingClientSecret = clientSecret;
        paymentCancelable = Terminal.getInstance().processPaymentIntent(
            intent,
            new CollectPaymentIntentConfiguration.Builder().build(),
            new ConfirmPaymentIntentConfiguration.Builder().build(),
            new PaymentIntentCallback() {
                @Override
                public void onSuccess(PaymentIntent paid) {
                    paymentCancelable = null;
                    pendingIntent = null;
                    pendingClientSecret = null;
                    call.resolve(paymentResult(paid));
                }

                @Override
                public void onFailure(TerminalException e) {
                    paymentCancelable = null;
                    if (e.getPaymentIntent() != null) pendingIntent = e.getPaymentIntent();
                    reject(call, e);
                }
            }
        );
    }

    private JSObject paymentResult(PaymentIntent intent) {
        CardPresentDetails card = null;
        PaymentMethod pm = intent.getPaymentMethod();
        if (pm != null) card = pm.getCardPresentDetails();
        if (card == null && intent.getCharges() != null && !intent.getCharges().isEmpty()) {
            Charge charge = intent.getCharges().get(0);
            PaymentMethodDetails details = charge.getPaymentMethodDetails();
            if (details != null) card = details.getCardPresentDetails();
        }
        String status = intent.getStatus() == null ? null : intent.getStatus().name().toLowerCase(Locale.ROOT);
        JSObject result = new JSObject()
            .put("paymentIntentId", intent.getId())
            .put("status", status)
            .put("amount", intent.getAmount())
            .put("currency", intent.getCurrency());
        if (card != null) {
            String brand = card.getBrand() == null ? null : card.getBrand().toLowerCase(Locale.ROOT);
            result.put("card", new JSObject().put("brand", brand).put("last4", card.getLast4()));
        }
        return result;
    }

    // ---------------------------------------------------------------- connection helpers

    private boolean initTerminal(PluginCall call) {
        if (Terminal.isInitialized()) return true;
        try {
            Terminal.init(getContext().getApplicationContext(), LogLevel.WARNING, tokenProvider, terminalListener, null);
            return true;
        } catch (TerminalException e) {
            reject(call, e);
            return false;
        }
    }

    private void connectTapToPay(final PluginCall call) {
        final String loc = locationId;
        if (loc == null) {
            call.reject("locationId required to connect Tap to Pay", "NO_LOCATION");
            return;
        }
        if (!isSimulated) {
            NfcAdapter nfc = NfcAdapter.getDefaultAdapter(getContext());
            if (nfc == null) {
                call.reject("This phone has no NFC, so it can't use Tap to Pay", "NFC_UNSUPPORTED");
                return;
            }
            if (!nfc.isEnabled()) {
                toast("Please turn on NFC in your phone settings to use Tap to Pay.");
                call.reject("NFC is turned off", "NFC_DISABLED");
                return;
            }
        }
        stopDiscovery(() -> {
            final AtomicBoolean connecting = new AtomicBoolean(false);
            discoveryCancelable = Terminal.getInstance().discoverReaders(
                new DiscoveryConfiguration.TapToPayDiscoveryConfiguration(isSimulated),
                new DiscoveryListener() {
                    @Override
                    public void onUpdateDiscoveredReaders(List<Reader> readers) {
                        if (readers.isEmpty() || !connecting.compareAndSet(false, true)) return;
                        final Reader reader = readers.get(0);
                        onMain(() -> Terminal.getInstance().connectReader(
                            reader,
                            new ConnectionConfiguration.TapToPayConnectionConfiguration(loc, true, tapToPayReaderListener),
                            readerCallback(call)
                        ));
                    }
                },
                new Callback() {
                    @Override
                    public void onSuccess() {
                        discoveryCancelable = null;
                        if (!connecting.get()) call.reject("Tap to Pay is not available on this phone", "NO_READER");
                    }

                    @Override
                    public void onFailure(TerminalException e) {
                        discoveryCancelable = null;
                        if (!connecting.get()) reject(call, e);
                    }
                }
            );
        });
    }

    private void stopDiscovery(final Runnable then) {
        Cancelable c = discoveryCancelable;
        discoveryCancelable = null;
        if (c == null || c.isCompleted()) {
            then.run();
            return;
        }
        c.cancel(new Callback() {
            @Override
            public void onSuccess() {
                onMain(then);
            }

            @Override
            public void onFailure(TerminalException e) {
                onMain(then);
            }
        });
    }

    private void disconnectThen(final PluginCall call, final Runnable then) {
        Terminal.getInstance().disconnectReader(new Callback() {
            @Override
            public void onSuccess() {
                onMain(then);
            }

            @Override
            public void onFailure(TerminalException e) {
                reject(call, e);
            }
        });
    }

    private ReaderCallback readerCallback(final PluginCall call) {
        return new ReaderCallback() {
            @Override
            public void onSuccess(Reader reader) {
                call.resolve(connectedResult(reader));
            }

            @Override
            public void onFailure(TerminalException e) {
                reject(call, e);
            }
        };
    }

    private JSObject connectedResult(Reader reader) {
        return new JSObject().put("connected", true).put("reader", new JSObject().put("deviceType", reader.getDeviceType().name()));
    }

    private void reportDisconnect(DisconnectReason reason) {
        notifyListeners("unexpectedDisconnect", new JSObject().put("reason", reason.name()));
    }

    private void readerMessage(String type, String message) {
        notifyListeners("readerMessage", new JSObject().put("type", type).put("message", message));
        String text = message.replace('_', ' ').toLowerCase(Locale.ROOT);
        if (!text.isEmpty()) text = text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
        toast(text);
    }

    // ---------------------------------------------------------------- permissions

    private boolean bluetoothRuntimePermissionsApply() {
        return Build.VERSION.SDK_INT >= 31;
    }

    private List<String> requiredPermissions(boolean needsBluetooth) {
        List<String> list = new ArrayList<>();
        list.add(Manifest.permission.ACCESS_FINE_LOCATION);
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (needsBluetooth && bluetoothRuntimePermissionsApply()) {
            list.add("android.permission.BLUETOOTH_SCAN");
            list.add("android.permission.BLUETOOTH_CONNECT");
        }
        return list;
    }

    private List<String> undeclaredPermissions(boolean needsBluetooth) {
        List<String> missing = new ArrayList<>();
        try {
            String[] declared = getContext().getPackageManager()
                .getPackageInfo(getContext().getPackageName(), PackageManager.GET_PERMISSIONS)
                .requestedPermissions;
            Set<String> set = new HashSet<>(declared == null ? Collections.emptyList() : Arrays.asList(declared));
            for (String p : requiredPermissions(needsBluetooth)) if (!set.contains(p)) missing.add(p);
        } catch (Exception ignored) {}
        return missing;
    }

    private List<String> missingPermissions(boolean needsBluetooth) {
        List<String> list = new ArrayList<>();
        if (getPermissionState(LOCATION) != PermissionState.GRANTED) list.add(LOCATION);
        if (needsBluetooth && bluetoothRuntimePermissionsApply() && getPermissionState(BLUETOOTH) != PermissionState.GRANTED) {
            list.add(BLUETOOTH);
        }
        return list;
    }

    private boolean needsBluetooth(PluginCall call) {
        String m = call.getMethodName();
        return "discoverReaders".equals(m) || "connectReader".equals(m);
    }

    private boolean ensurePermissions(PluginCall call, boolean needsBluetooth) {
        List<String> missing = missingPermissions(needsBluetooth);
        if (missing.isEmpty()) return true;
        List<String> undeclared = undeclaredPermissions(needsBluetooth);
        if (!undeclared.isEmpty()) {
            call.reject("App is missing required permissions in its manifest: " + String.join(", ", undeclared), "PERMISSION_DENIED");
            return false;
        }
        requestPermissionForAliases(missing.toArray(new String[0]), call, "afterPermissionRequest");
        return false;
    }

    @PermissionCallback
    private void afterPermissionRequest(PluginCall call) {
        List<String> missing = missingPermissions(needsBluetooth(call));
        if (!missing.isEmpty()) {
            String what;
            if (!missing.contains(LOCATION)) {
                what = "Nearby devices (Bluetooth)";
            } else if (getPermissionState(LOCATION) != PermissionState.GRANTED
                && bridge.getActivity().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                what = "Precise location";
            } else {
                what = "Location";
            }
            String msg = what + " permission is needed to take card payments. If you aren't asked again, turn it on in Settings > Apps > OTHE CRM > Permissions.";
            toast(msg);
            call.reject(msg, "PERMISSION_DENIED");
            return;
        }
        String m = call.getMethodName();
        if ("connectReader".equals(m)) connectReader(call);
        else if ("collectPayment".equals(m)) collectPayment(call);
        else if ("connect".equals(m)) connect(call);
        else if ("discoverReaders".equals(m)) discoverReaders(call);
        else call.resolve();
    }

    private boolean bluetoothEnabled() {
        Object svc = getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        if (!(svc instanceof BluetoothManager)) return false;
        BluetoothAdapter adapter = ((BluetoothManager) svc).getAdapter();
        return adapter != null && adapter.isEnabled();
    }

    // ---------------------------------------------------------------- utilities

    private void onMain(Runnable block) {
        if (Looper.myLooper() == Looper.getMainLooper()) block.run();
        else main.post(block);
    }

    private void reject(PluginCall call, TerminalException e) {
        call.reject(e.getErrorMessage(), e.getErrorCode().name());
    }

    private void toast(final String message) {
        AppCompatActivity activity = getActivity();
        if (activity == null) return;
        activity.runOnUiThread(() -> Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show());
    }
}
