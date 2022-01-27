package de.nellessen.muscle_card_on_android;

import junit.framework.TestCase;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@RunWith(PowerMockRunner.class)
@PrepareForTest(MSCHostApduService.class)
public class MSCHostApduServiceTest extends TestCase {

    private static final String EXPECTED_KEY_CARD_STATUS = "======= Status of MUSCLE Applet =======\nmocked status======= Keys in MUSCLE Applet =======\n======= Objects in MUSCLE Applet =======\n";

    public void testGetKeyCardStatus() throws Exception {
        MSCHostApduService mscHostApduService = MSCHostApduService.getInstance(null);
        MSCHostApduService spiedMscHostApduService = PowerMockito.spy(mscHostApduService);

        PowerMockito.doReturn("mocked status")
                    .when(spiedMscHostApduService,
                          "getStatus");

        String actualKeyCardStatus = spiedMscHostApduService.getKeyCardStatus();

        assertThat("Did not create expected key card status!",
                   actualKeyCardStatus,
                   is(equalTo(EXPECTED_KEY_CARD_STATUS)));
    }
}