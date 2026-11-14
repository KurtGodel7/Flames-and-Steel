package games.strategy.engine.framework.startup.login;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import games.strategy.engine.framework.startup.login.ClientLoginValidator.ErrorMessages;
import java.util.Map;
import org.jetbrains.annotations.NonNls;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

final class ClientLoginValidatorTest {
  @NonNls private static final String PASSWORD = "password";
  @NonNls private static final String OTHER_PASSWORD = "otherPassword";

  /** Request 68: a client with another build is turned away with the host's build named. */
  @Test
  void versionMismatchNamesTheHostBuild() {
    assertThat(
        ClientLoginValidator.versionProblem("2026-11.16.146", "2026-11.16.146"), is(nullValue()));
    assertThat(
        ClientLoginValidator.versionProblem("2026-11.16.146", "2026-11.16.145"),
        containsString("host is running Flames and Steel game build 2026-11.16.146"));
    assertThat(
        ClientLoginValidator.versionProblem("2026-11.16.146", null),
        containsString("you have an older build"));
  }

  @ExtendWith(MockitoExtension.class)
  @Nested
  final class AuthenticateTest {
    @Test
    void shouldReturnNoErrorWhenHmacSha512AuthenticationSucceeded() throws Exception {
      final Map<String, String> challenge = HmacSha512Authenticator.newChallenge();
      final Map<String, String> response = HmacSha512Authenticator.newResponse(PASSWORD, challenge);

      final String errorMessage = ClientLoginValidator.authenticate(challenge, response, PASSWORD);

      assertThat(errorMessage, is(ErrorMessages.NO_ERROR));
    }

    @Test
    void shouldReturnErrorWhenHmacSha512AuthenticationFailed() throws Exception {
      final Map<String, String> challenge = HmacSha512Authenticator.newChallenge();
      final Map<String, String> response =
          HmacSha512Authenticator.newResponse(OTHER_PASSWORD, challenge);

      final String errorMessage = ClientLoginValidator.authenticate(challenge, response, PASSWORD);

      assertThat(errorMessage, is(ErrorMessages.INVALID_PASSWORD));
    }
  }
}
