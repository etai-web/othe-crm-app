package com.othe.crm;

import android.net.Uri;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.getcapacitor.Plugin;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Google blocks its sign-in page inside embedded web views, so instead of a broken Google page
 * we show a short explanation and ask the user to sign in with email + password.
 */
@CapacitorPlugin(name = "AuthNavigation")
public class AuthNavigationPlugin extends Plugin {

    private static final List<String> GITHUB_LOGIN_PATHS = Arrays.asList("/login", "/session", "/sessions");
    private AlertDialog googleDialog;

    @Override
    public Boolean shouldOverrideLoad(Uri url) {
        if (url == null || !"https".equals(url.getScheme()) || url.getHost() == null) return null;
        String host = url.getHost().toLowerCase(Locale.ROOT);
        String path = url.getPath() == null ? "" : url.getPath();

        if ("accounts.google.com".equals(host)) {
            showGoogleNotSupported();
            return true;
        }
        if ("github.com".equals(host)) {
            for (String p : GITHUB_LOGIN_PATHS) {
                if (path.equals(p) || path.startsWith(p + "/")) return false;
            }
        }
        return null;
    }

    private void showGoogleNotSupported() {
        AppCompatActivity activity = getActivity();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            if (googleDialog != null && googleDialog.isShowing()) return;
            googleDialog = new AlertDialog.Builder(activity)
                .setTitle("Google sign-in isn't available in the app")
                .setMessage(
                    "Google doesn't allow signing in with Google inside apps like this one.\n\n"
                        + "Please sign in with your email and password instead. If you don't have a password yet, "
                        + "use \"Forgot password\" on the login screen to set one."
                )
                .setPositiveButton("OK", null)
                .show();
        });
    }
}
