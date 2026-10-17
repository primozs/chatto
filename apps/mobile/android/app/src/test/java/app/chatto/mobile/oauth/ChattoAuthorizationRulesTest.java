package app.chatto.mobile.oauth;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure rule checks for authorize / callback URIs (no Android runtime). */
public class ChattoAuthorizationRulesTest {

  @Test
  public void isValidAuthorizeUrl_requiresHttpsMobileClientAndCallback() {
    assertTrue(
        ChattoAuthorizationPlugin.isValidAuthorizeUrl(
            "https://remote.example/oauth/authorize"
                + "?client_id=eu.chattocorp.chatto.mobile"
                + "&redirect_uri=eu.chattocorp.chatto.mobile:/oauth/callback"
                + "&state=s&code_challenge=c&code_challenge_method=S256"
                + "&response_type=code"));
    assertFalse(
        ChattoAuthorizationPlugin.isValidAuthorizeUrl(
            "http://remote.example/oauth/authorize"
                + "?client_id=eu.chattocorp.chatto.mobile"
                + "&redirect_uri=eu.chattocorp.chatto.mobile:/oauth/callback"));
    assertFalse(
        ChattoAuthorizationPlugin.isValidAuthorizeUrl(
            "https://remote.example/oauth/authorize"
                + "?client_id=chatto://loopback"
                + "&redirect_uri=eu.chattocorp.chatto.mobile:/oauth/callback"));
    assertFalse(ChattoAuthorizationPlugin.isValidAuthorizeUrl(null));
  }

  @Test
  public void isValidCallbackUri_requiresExactMobileCallback() {
    assertTrue(
        ChattoAuthorizationPlugin.isValidCallbackUri(
            "eu.chattocorp.chatto.mobile:/oauth/callback?state=s&code=c"));
    assertFalse(
        ChattoAuthorizationPlugin.isValidCallbackUri(
            "https://evil.example/oauth/callback?state=s&code=c"));
    assertFalse(
        ChattoAuthorizationPlugin.isValidCallbackUri(
            "eu.chattocorp.chatto.mobile:/oauth/callback#frag"));
  }
}
