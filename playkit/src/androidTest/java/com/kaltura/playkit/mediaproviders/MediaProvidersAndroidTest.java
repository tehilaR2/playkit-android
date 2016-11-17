package com.kaltura.playkit.mediaproviders;

import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import com.kaltura.playkit.PKMediaEntry;
import com.kaltura.playkit.backend.base.OnMediaLoadCompletion;
import com.kaltura.playkit.backend.mock.MockMediaProvider;
import com.kaltura.playkit.connect.ErrorElement;
import com.kaltura.playkit.connect.ResultElement;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Created by tehilarozin on 09/11/2016.
 */

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MediaProvidersAndroidTest {

    final static String InputFile = "mock/entries.playkit.json";

    public MediaProvidersAndroidTest(){}

    @Test
    public void testMockProvider() {

        final MockMediaProvider mockMediaProvider = new MockMediaProvider(InputFile, InstrumentationRegistry.getTargetContext(), "m001");
        mockMediaProvider.load(new OnMediaLoadCompletion() {
            @Override
            public void onComplete(ResultElement<PKMediaEntry> response) {
                if (response.isSuccess()) {
                    PKMediaEntry mediaEntry = response.getResponse();
                    System.out.println("got some response. id = " + mediaEntry.getId());
                } else {
                    assertFalse(response.getError() == null);
                    System.out.println("got error on json load: " + response.getError().getMessage());
                }

                mockMediaProvider.id("1_1h1vsv3z").load(new OnMediaLoadCompletion() {
                    @Override
                    public void onComplete(ResultElement<PKMediaEntry> response) {
                        assertTrue(response.isSuccess());
                        assertTrue(response.getError() == null);
                        PKMediaEntry mediaEntry = response.getResponse();
                        assertTrue(mediaEntry.getId().equals("1_1h1vsv3z"));
                        assertTrue(mediaEntry.getSources().get(0).getId().equals("1_ude4l5pb"));

                        mockMediaProvider.id("notexists").load(new OnMediaLoadCompletion() {
                            @Override
                            public void onComplete(ResultElement<PKMediaEntry> response) {
                                assertTrue(!response.isSuccess());
                                assertTrue(response.getError() != null);
                                assertTrue(response.getError().equals(ErrorElement.MediaNotFound));
                            }
                        });
                    }
                });

            }
        });
    }
}
