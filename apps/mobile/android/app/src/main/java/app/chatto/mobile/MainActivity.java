package app.chatto.mobile;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

import app.chatto.mobile.notifications.ChattoNotificationsPlugin;
import app.chatto.mobile.oauth.ChattoAuthorizationPlugin;
import app.chatto.mobile.oauth.ChattoOAuthPlugin;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    registerPlugin(ChattoNotificationsPlugin.class);
    registerPlugin(ChattoAuthorizationPlugin.class);
    registerPlugin(ChattoOAuthPlugin.class);
    super.onCreate(savedInstanceState);
  }
}
