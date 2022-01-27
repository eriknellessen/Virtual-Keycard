package de.nellessen.virtual_keycard;

import android.content.Context;
import android.widget.TextView;
import de.nellessen.muscle_card_on_android.MSCHostApduService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
@PrepareForTest({MSCHostApduService.class})
public class MainActivityTest {

    private static final String TEST_TEXT = "mocked status";

    @Test
    public void showExpectedText() {
        MSCHostApduService mockedMscHostApduService = PowerMockito.mock(MSCHostApduService.class);
        PowerMockito.when(mockedMscHostApduService.getKeyCardStatus())
                    .thenReturn(TEST_TEXT);
        PowerMockito.mockStatic(MSCHostApduService.class);
        PowerMockito.when(MSCHostApduService.getInstance(Mockito.any(Context.class)))
                    .thenReturn(mockedMscHostApduService);

        MainActivity mainActivity = Mockito.spy(MainActivity.class);
        TextView mockedTextView = PowerMockito.mock(TextView.class);
        Mockito.doReturn(mockedTextView)
               .when(mainActivity)
               .findViewById(Mockito.anyInt());
        mainActivity.onCreate(null);
        mainActivity.refresh(null);

        Mockito.verify(mockedTextView)
               .setText(TEST_TEXT.toCharArray(),
                        0,
                        TEST_TEXT.length());
    }
}

